/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.test;

import io.activej.async.callback.AsyncComputation;
import io.activej.common.function.RunnableEx;
import io.activej.common.function.SupplierEx;
import io.activej.eventloop.Eventloop;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An {@link Eventloop} that owns a dedicated daemon thread, with a blocking submit bridge and an
 * idempotent, time-bounded teardown.
 * <p>
 * Use this — rather than {@link io.activej.test.rules.EventloopRule} — when the JUnit thread must
 * stay free to block: on a subprocess, on a second reactor, or on a promise that only completes
 * because <i>this</i> loop keeps running. {@code EventloopRule} puts a loop on the *current* thread,
 * which cannot serve while the test is blocked in an assertion.
 * <p>
 * <b>Threading.</b> The JUnit thread never touches a reactive component directly. Every
 * reactor-touching operation goes through {@link #submit(RunnableEx)} / {@link #submit(SupplierEx)},
 * which post to the loop and block the caller on the resulting {@link CompletableFuture}. Blocking is
 * safe precisely because the loop runs on its own thread.
 * <p>
 * <b>Lifetime.</b> The loop is held open for the object's whole life with {@code keepAlive(true)} —
 * a setup failure that never registered a selector key must not let the loop exit and strand the
 * teardown submit — and released by {@link #close()}.
 * <p>
 * <b>Teardown.</b> {@link #close()} is idempotent and safe on every path. It runs the actions
 * registered through {@link #onClose(RunnableEx)} <i>on the loop</i> (most-recently-registered
 * first), releases the keep-alive, and joins the thread within a bound; as a last resort the loop is
 * forced out with {@code breakEventloop()} after a second, shorter join. The first failure observed
 * is rethrown, so a teardown that did not complete cleanly cannot read as a clean one.
 * <p>
 * A typical owner keeps one of these and delegates:
 * <pre>{@code
 * loop = EventloopThread.create("my-client");
 * try {
 *     client = loop.submit(() -> MyClient.builder(loop.eventloop()).build());
 *     loop.onClose(client::close);
 * } catch (RuntimeException | Error e) {
 *     loop.close();
 *     throw e;
 * }
 * }</pre>
 */
public final class EventloopThread implements AutoCloseable {
	private static final long CALL_TIMEOUT_SECONDS = 10;
	private static final long JOIN_TIMEOUT_MILLIS = 10_000;
	private static final long BREAK_JOIN_TIMEOUT_MILLIS = 2_000;

	private final Eventloop eventloop = Eventloop.create();
	private final List<RunnableEx> closeActions = new CopyOnWriteArrayList<>();
	private final String threadName;
	private final Thread thread;

	private volatile boolean closed;

	private EventloopThread(String threadName) {
		this.threadName = threadName;
		this.thread = new Thread(eventloop, threadName);
	}

	/**
	 * Creates the loop, holds it open and starts its daemon thread. The loop is running — and
	 * accepting {@code submit} — by the time this returns.
	 *
	 * @param threadName names the thread, and identifies this loop in every exception it raises
	 */
	public static EventloopThread create(String threadName) {
		EventloopThread loop = new EventloopThread(threadName);
		loop.eventloop.keepAlive(true);
		loop.thread.setDaemon(true);
		loop.thread.start();
		return loop;
	}

	/** The loop itself — to hand to a builder that takes a reactor. */
	public Eventloop eventloop() {
		return eventloop;
	}

	/**
	 * Registers an action {@link #close()} will run <b>on the loop</b>, before the thread is joined.
	 * Actions run in reverse registration order, so a resource registered after the one it depends on
	 * is closed first. Registering after {@code close()} has run has no effect.
	 */
	public void onClose(RunnableEx closeAction) {
		closeActions.add(closeAction);
	}

	/** Runs an action on the loop thread and blocks the caller until it has run. */
	public void submit(RunnableEx action) {
		await(eventloop.submit(action), describe());
	}

	/** Runs a computation on the loop thread and returns its result. */
	public <T> T submit(SupplierEx<T> computation) {
		return await(eventloop.submit(AsyncComputation.of(computation)), describe());
	}

	/**
	 * Runs the registered close actions on the loop, then releases and joins it. Idempotent.
	 *
	 * @throws IllegalStateException if teardown did not complete cleanly — a close action that failed
	 *                               or timed out, or a thread still alive after both joins
	 */
	@Override
	public void close() {
		if (closed) return;
		closed = true;
		Exception failure = null;
		if (!closeActions.isEmpty()) {
			try {
				eventloop.submit(() -> {
					// reverse order: a resource registered later may depend on an earlier one
					for (int i = closeActions.size() - 1; i >= 0; i--) {
						closeActions.get(i).run();
					}
				}).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failure = e;
			} catch (ExecutionException e) {
				failure = new IllegalStateException("A close action failed on " + describe(), e.getCause());
			} catch (TimeoutException e) {
				failure = e;
			}
		}
		// The loop is ours; release it now that nothing of it is left to run.
		eventloop.keepAlive(false);
		try {
			thread.join(JOIN_TIMEOUT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = failure == null ? e : failure;
		}
		if (thread.isAlive()) {
			// Last resort: a drain that outlives its join must not hold the JVM — the loop is forced
			// out and whatever ByteBufs were still in flight surface through ByteBufRule as a red.
			eventloop.breakEventloop();
			try {
				thread.join(BREAK_JOIN_TIMEOUT_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				// a stray interrupt at this last resort must not read as a clean teardown
				failure = failure == null ? e : failure;
			}
		}
		if (failure != null) {
			throw new IllegalStateException(capitalized(describe()) + " did not tear down cleanly", failure);
		}
	}

	/**
	 * Blocks on a future from a reactor this object does <b>not</b> own, with the same bound and the
	 * same exception shape as {@link #submit(SupplierEx)}. Use it to bridge into a second loop — a
	 * launcher's, say — without hand-rolling the unwrapping again.
	 *
	 * @param what names the reactor in any exception raised, e.g. {@code "the launcher reactor"}
	 */
	public static <T> T await(CompletableFuture<T> future, String what) {
		try {
			return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for " + what, e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("A task on " + what + " failed", e.getCause());
		} catch (TimeoutException e) {
			throw new IllegalStateException(
				capitalized(what) + " did not answer within " + CALL_TIMEOUT_SECONDS + " seconds", e);
		}
	}

	private String describe() {
		return "eventloop thread '" + threadName + "'";
	}

	private static String capitalized(String what) {
		return Character.toUpperCase(what.charAt(0)) + what.substring(1);
	}

	@Override
	public String toString() {
		return "EventloopThread{" + threadName + (closed ? ", closed" : "") + '}';
	}
}
