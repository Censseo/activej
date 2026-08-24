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

package io.activej.jsonrpc.service.fixtures;

import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The implementation behind {@link SlowApi}: every invocation returns a promise that is still pending when
 * the method returns, and completes only when the test says so.
 * <p>
 * The gate is a {@link SettablePromise} per invocation, <b>not</b> a {@code CountDownLatch} or any other
 * blocking primitive. Everything here runs on the reactor thread, and a handler that blocked it would stop
 * the very loop that has to keep accepting the calls a concurrency bound is measured against — the fixture
 * would deadlock instead of saturating.
 * <p>
 * Nothing is scheduled and no clock is consulted: the invocation is held until {@link #releaseAll()},
 * {@link #releaseNext()}, {@link #failAll(Exception)} or their indexed forms, so a test asserting on the
 * in-flight count reads a value it fixed rather than one it raced.
 */
public final class SlowApiImpl implements SlowApi {
	private final List<String> invocations = new ArrayList<>();
	private final List<Pending> pending = new ArrayList<>();

	@Override
	public Promise<String> call(String tag) {
		invocations.add("call(" + tag + ')');
		SettablePromise<String> promise = new SettablePromise<>();
		pending.add(new Pending("slow.call", tag, () -> promise.set(tag), promise::setException));
		return promise;
	}

	@Override
	public Promise<Void> notifySlowly(String tag) {
		invocations.add("notify(" + tag + ')');
		SettablePromise<Void> promise = new SettablePromise<>();
		pending.add(new Pending("slow.notify", tag, () -> promise.set(null), promise::setException));
		return promise;
	}

	// region driving the gate

	/** How many invocations have been entered and not yet completed — what an in-flight bound is measured in. */
	public int pendingCount() {
		return pending.size();
	}

	/** The {@code tag} of every pending invocation, in invocation order. */
	public List<String> pendingTags() {
		return pending.stream().map(Pending::tag).toList();
	}

	/** The wire name of every pending invocation, in invocation order — {@code slow.call} or {@code slow.notify}. */
	public List<String> pendingWireNames() {
		return pending.stream().map(Pending::wireName).toList();
	}

	/**
	 * Completes the pending invocation at {@code index} successfully: {@link SlowApi#call} with its own
	 * {@code tag}, {@link SlowApi#notifySlowly} with nothing.
	 *
	 * @throws IndexOutOfBoundsException if nothing is pending at {@code index} — a release nobody can satisfy
	 *                                   is a broken test, not a no-op
	 */
	public void release(int index) {
		pending.remove(index).complete().run();
	}

	/** Completes the oldest pending invocation. */
	public void releaseNext() {
		release(0);
	}

	/** Completes every pending invocation successfully, in invocation order. */
	public void releaseAll() {
		for (Pending call : drain()) {
			call.complete().run();
		}
	}

	/**
	 * Fails the pending invocation at {@code index} — the other completion path, which an in-flight counter
	 * must decrement exactly as it decrements a success.
	 *
	 * @throws IndexOutOfBoundsException as {@link #release(int)}
	 */
	public void fail(int index, Exception e) {
		pending.remove(index).fail().accept(e);
	}

	/** Fails the oldest pending invocation. */
	public void failNext(Exception e) {
		fail(0, e);
	}

	/** Fails every pending invocation, in invocation order. */
	public void failAll(Exception e) {
		for (Pending call : drain()) {
			call.fail().accept(e);
		}
	}

	/** Every invocation so far, in order, rendered as {@code name(tag)} — completed ones included. */
	public List<String> invocations() {
		return invocations;
	}

	// endregion

	/**
	 * A snapshot, so that a handler re-entered by the completion of an earlier invocation queues behind this
	 * batch rather than joining it — and so that iteration never sees the list it is completing out of.
	 */
	private List<Pending> drain() {
		List<Pending> batch = List.copyOf(pending);
		pending.clear();
		return batch;
	}

	private record Pending(String wireName, String tag, Runnable complete, Consumer<Exception> fail) {}
}
