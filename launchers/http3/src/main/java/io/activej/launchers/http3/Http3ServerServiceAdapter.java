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

import io.activej.http3.Http3Server;
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
 * <h2>The {@code stop()} contract, stated so it is not "fixed"</h2>
 *
 * {@code Http3Server.close()} returns {@code void}. It announces GOAWAY on every connection and,
 * when request streams are still open, schedules {@code reactor.delay(shutdownTimeoutMillis,
 * this::closeNow)}. There is <b>no</b> {@code whenClosed()}, no {@code Promise}-returning close, and
 * {@code isClosed()} flips at the <i>start</i> of {@code close()} — so nothing observable marks the
 * end of the drain.
 * <p>
 * This adapter therefore does not try to wait. What waits is the <b>{@code Eventloop} service the
 * graph stops after it</b>: {@code ServiceAdapters.forEventloop().stop} sets {@code keepAlive(false)}
 * and then <b>joins the reactor thread</b>, and that thread cannot exit while the drain's scheduled
 * task is pending and the endpoint's UDP socket is a registered selector key — {@code closeNow()} is
 * what releases both.
 * <p>
 * An adapter that completed {@code stop()} early would normally be a bug. Here it is correct, and
 * the guarantee lives in the stop <i>ordering</i>, not in the adapter: an exchange in flight when
 * the launcher stops finishes within {@code shutdownTimeoutMillis}, asserted by the launcher test's
 * graceful-stop case (SC-006a).
 * <p>
 * Rejected: adding {@code Promise<Void> whenShutDown()} to {@code Http3Server} (a public API change
 * this feature's FR-034 forbids, duplicating a guarantee the ordering already gives); polling
 * {@code isClosed()} (answers the wrong question); sleeping {@code shutdownTimeoutMillis} (turns
 * the drain ceiling into the cost of every clean shutdown).
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
		CompletableFuture<Object> future = new CompletableFuture<>();
		server.getReactor().execute(() -> {
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
