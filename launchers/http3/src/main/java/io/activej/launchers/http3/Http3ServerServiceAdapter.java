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

package io.activej.launchers.http3;

import io.activej.eventloop.Eventloop;
import io.activej.http3.Http3Server;
import io.activej.reactor.Reactor;
import io.activej.service.adapter.ServiceAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Lifts an {@link Http3Server} onto the {@code Service} contract so the service graph starts it
 * ({@code listen()}) and stops it ({@code close()}) — the one obligation ADR-017 assigned to this
 * feature: without this adapter the graph's built-in {@code AutoCloseable} registration silently
 * adapts the server (a no-op start that never listens, and an off-reactor {@code close()} on stop),
 * which is exactly the misbehaviour recorded in {@code specs/007-interop-examples/task-results/T030.md}.
 * <p>
 * {@code start} follows the {@code ServiceAdapters.forReactiveService()} shape: it hops to the
 * server's reactor, calls {@link Http3Server#listen()}, and bridges the resulting {@code Promise}
 * to the returned {@code CompletableFuture}. A bind failure, a missing identity or a missing listen
 * address completes the future exceptionally, so {@code ServiceGraphModule} aborts startup and
 * rolls back the already-started services instead of leaving a half-open graph.
 *
 * <h2>The {@code stop()} contract</h2>
 *
 * {@code Http3Server.close()} returns {@code void} (FR-034 keeps the API untouched), so the end of
 * the GOAWAY drain is <b>not announced by the server</b> — it is observable only through the
 * reactor. This adapter therefore waits for it there:
 * <ul>
 *     <li>{@link Eventloop#isAlive()} keeps the loop running while the drain's scheduled
 *     {@code closeNow()} task and the endpoint's UDP-socket selector key are in place;</li>
 *     <li>the adapter sets {@code keepAlive(false)} on the reactor right after {@code close()},
 *     so the loop <i>can</i> exit;</li>
 *     <li>joining the reactor thread is then a wait for the drain itself: the thread exits exactly
 *     when {@code closeNow()} has released the socket key and the scheduled task — never before,
 *     and, because {@code closeNow()} is always scheduled (immediately when nothing is left to
 *     drain, else at {@code shutdownTimeoutMillis}), the wait is bounded by the drain ceiling.</li>
 * </ul>
 * The guarantee therefore lives in <b>this adapter's own wait</b>, not in the graph's stop
 * ordering — no reliance on which service stops when, which the service-graph guard rails forbid.
 * An in-flight exchange when the launcher stops finishes within {@code shutdownTimeoutMillis},
 * asserted by the launcher test's graceful-stop case (SC-006a).
 * <p>
 * A reactor that is not running at all — the loop was never started, or was stopped before this
 * service (a launcher that excluded the {@code Eventloop} from the graph, or reordered it) — makes
 * the drain impossible: {@code close()} could not even have been processed. The adapter fails the
 * stop loudly instead of silently declaring the server stopped, naming what a correct launcher
 * must guarantee.
 * <p>
 * A non-{@code Eventloop} reactor offers no loop-exit signal; the adapter falls back to completing
 * once {@code close()} has been called on it, which delegates the drain to the graph's
 * {@code Eventloop} adapter as before — the recorded D5 limitation.
 * <p>
 * Rejected: adding {@code Promise<Void> whenShutDown()} to {@code Http3Server} (a public API change
 * this feature's FR-034 forbids); polling {@code isClosed()} (answers the wrong question — it flips
 * at the <i>start</i> of {@code close()}); sleeping {@code shutdownTimeoutMillis} (turns the drain
 * ceiling into the cost of every clean shutdown).
 */
public final class Http3ServerServiceAdapter implements ServiceAdapter<Http3Server> {
	public static Http3ServerServiceAdapter create() {
		return new Http3ServerServiceAdapter();
	}

	private Http3ServerServiceAdapter() {}

	@Override
	public CompletableFuture<?> start(Http3Server server, Executor executor) {
		CompletableFuture<Object> future = new CompletableFuture<>();
		server.getReactor().execute(() -> {
			try {
				server.listen()
					.whenResult(future::complete)
					.whenException(future::completeExceptionally);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	@Override
	public CompletableFuture<?> stop(Http3Server server, Executor executor) {
		Reactor reactor = server.getReactor();
		if (reactor instanceof Eventloop eventloop) {
			Thread eventloopThread = eventloop.getEventloopThread();
			if (eventloopThread == null) {
				return CompletableFuture.failedFuture(new IllegalStateException(
					"The HTTP/3 server's reactor is not running, so the GOAWAY drain cannot complete: " +
						"the Eventloop must be a graph service stopped after the Http3Server"));
			}
			CompletableFuture<Object> future = new CompletableFuture<>();
			eventloop.execute(() -> {
				try {
					server.close();
				} catch (Exception e) {
					future.completeExceptionally(e);
					return;
				}
				// The loop is released now; the drain's scheduled task and the endpoint's socket key
				// keep it alive until closeNow() runs (bounded by shutdownTimeoutMillis), so the join
				// below returns exactly when the drain has finished.
				eventloop.keepAlive(false);
				executor.execute(() -> {
					try {
						eventloopThread.join();
						future.complete(null);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						future.completeExceptionally(e);
					}
				});
			});
			return future;
		}
		// A non-Eventloop reactor offers no loop-exit signal: complete once close() has been
		// initiated on it, leaving the drain to the graph's Eventloop adapter (the D5 limitation).
		CompletableFuture<Object> future = new CompletableFuture<>();
		reactor.execute(() -> {
			try {
				server.close();
				future.complete(null);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
