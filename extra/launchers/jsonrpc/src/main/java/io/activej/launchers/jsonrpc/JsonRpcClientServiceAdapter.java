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

package io.activej.launchers.jsonrpc;

import io.activej.async.exception.AsyncCloseException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.service.adapter.ServiceAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The {@link ServiceAdapter} that makes a DI-provided {@link JsonRpcClient} part of the service graph
 * (FR-060, ADR-025).
 * <p>
 * <b>Why it exists at all</b>: {@code AsyncCloseable} does not extend {@code AutoCloseable}, so
 * {@code ServiceGraphModule}'s generic machinery silently ignores a {@code JsonRpcClient} bound into
 * the graph — it is neither started nor stopped, and no warning is emitted. This adapter registers the
 * client explicitly.
 * <p>
 * <b>Start</b> is a completed no-op: a client binds nothing and listens on nothing.
 * <p>
 * <b>Stop</b> hops to the client's own reactor thread and calls {@code closeEx}, which fails every
 * in-flight call synchronously on that thread (FR-061). There is <b>no drain wait</b> — unlike
 * {@code Http3ServerServiceAdapter}'s GOAWAY drain (ADR-026), {@code closeEx} announces its end
 * synchronously, so the guarantee is delivered before the reactor task returns.
 */
public final class JsonRpcClientServiceAdapter implements ServiceAdapter<JsonRpcClient> {
	public static JsonRpcClientServiceAdapter create() {
		return new JsonRpcClientServiceAdapter();
	}

	private JsonRpcClientServiceAdapter() {}

	@Override
	public CompletableFuture<?> start(JsonRpcClient client, Executor executor) {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<?> stop(JsonRpcClient client, Executor executor) {
		CompletableFuture<Object> future = new CompletableFuture<>();
		client.getReactor().execute(() -> {
			try {
				// the exception the graph closed with: AsyncCloseException for a plain close (FR-061)
				client.closeEx(new AsyncCloseException());
				future.complete(null);
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
