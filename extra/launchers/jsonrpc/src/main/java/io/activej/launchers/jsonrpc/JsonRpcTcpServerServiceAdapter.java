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

import io.activej.net.AbstractReactiveServer;
import io.activej.net.ReactiveServer;
import io.activej.service.adapter.ServiceAdapter;
import io.activej.service.adapter.ServiceAdapters;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The {@link ServiceAdapter} that makes the framed-TCP endpoint part of the service graph (FR-100,
 * ADR-025), registered by a {@code @ProvidesIntoSet Initializer<ServiceGraphModuleSettings>} in
 * {@link JsonRpcModule} and in {@link MultithreadedJsonRpcServerLauncher}.
 * <p>
 * <b>Why it exists at all</b>: the mounted listener is not always a binding the graph can adapt on its
 * own. In the multi-worker launcher it is a {@link io.activej.net.PrimaryServer PrimaryServer} built by
 * hand over the per-worker servers — the unqualified {@code PrimaryServer} key is already taken by the
 * HTTP one (research D9) — so nothing in the DI graph points at it. Adapting {@link JsonRpcTcpMount}
 * instead gives that listener a start and a stop in dependency order, and gives the <b>disabled</b>
 * case a place to be a completed no-op.
 * <p>
 * <b>Start</b> is {@code listen()} on the listener's own reactor; <b>stop</b> is {@code close()} on it —
 * both delegated verbatim to {@link ServiceAdapters#forReactiveServer()}, the platform's own adapter,
 * so this class contributes no lifecycle mechanics of its own. When {@link JsonRpcTcpMount#isEnabled()}
 * is false there is no listener to act on and both are an already-completed future: <b>no socket is
 * opened and nothing is constructed</b> (FR-102).
 */
public final class JsonRpcTcpServerServiceAdapter implements ServiceAdapter<JsonRpcTcpMount> {
	private static final ServiceAdapter<ReactiveServer> SERVER_ADAPTER = ServiceAdapters.forReactiveServer();

	public static JsonRpcTcpServerServiceAdapter create() {
		return new JsonRpcTcpServerServiceAdapter();
	}

	private JsonRpcTcpServerServiceAdapter() {}

	@Override
	public CompletableFuture<?> start(JsonRpcTcpMount mount, Executor executor) {
		AbstractReactiveServer listener = mount.listener();
		if (listener == null) return CompletableFuture.completedFuture(null);
		return SERVER_ADAPTER.start(listener, executor);
	}

	@Override
	public CompletableFuture<?> stop(JsonRpcTcpMount mount, Executor executor) {
		AbstractReactiveServer listener = mount.listener();
		if (listener == null) return CompletableFuture.completedFuture(null);
		return SERVER_ADAPTER.stop(listener, executor);
	}
}
