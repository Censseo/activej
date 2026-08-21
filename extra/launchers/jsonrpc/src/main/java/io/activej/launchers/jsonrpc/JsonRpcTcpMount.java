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

import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.net.AbstractReactiveServer;
import io.activej.net.PrimaryServer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The framed-TCP mount decision, taken <b>at wiring time</b> — the last point at which {@link
 * io.activej.config.Config Config} is readable (FR-101/FR-102, research D9).
 * <p>
 * It carries either the listener to start ({@link JsonRpcTcpServer} in the single-eventloop launcher,
 * a {@link PrimaryServer} over the per-worker servers in the multi-worker one) or <b>nothing at all</b>
 * when {@code jsonrpc.tcp.port} is absent or empty. Disabled is the default: a raw TCP port opens a new
 * listening socket — plaintext and unauthenticated by design — unlike the WebSocket route, which rides
 * the HTTP listener that already exists. When disabled, no {@code JsonRpcTcpServer} is constructed at
 * all and this value holds {@code null}.
 * <p>
 * The value joins the service graph through {@link JsonRpcTcpServerServiceAdapter}, registered by a
 * {@code @ProvidesIntoSet Initializer<ServiceGraphModuleSettings>} in {@link JsonRpcModule} and in
 * {@link MultithreadedJsonRpcServerLauncher} (ADR-025's shape) — that is what lets a listener that is
 * <b>not</b> a binding of its own (the {@code PrimaryServer}, whose unqualified key is already taken by
 * the HTTP one) be started and stopped in dependency order.
 * <p>
 * <b>Not reactive</b>: it is an immutable holder read from the graph's executor thread, and every
 * reactor-bound call it enables is made by the adapter on the listener's own reactor.
 */
public final class JsonRpcTcpMount {
	private final @Nullable AbstractReactiveServer listener;

	private JsonRpcTcpMount(@Nullable AbstractReactiveServer listener) {
		this.listener = listener;
	}

	/** The endpoint is off: nothing was constructed and no socket will be opened (FR-102). */
	public static JsonRpcTcpMount disabled() {
		return new JsonRpcTcpMount(null);
	}

	/** The endpoint is on: {@code listener} is started when the service graph starts, and closed when it stops. */
	public static JsonRpcTcpMount of(AbstractReactiveServer listener) {
		return new JsonRpcTcpMount(Objects.requireNonNull(listener, "listener"));
	}

	public boolean isEnabled() {
		return listener != null;
	}

	/**
	 * The mounted listener, or {@code null} when the endpoint is disabled. Ask it where it bound —
	 * {@code getBoundAddresses()} — rather than reading the configured port back: a {@code
	 * jsonrpc.tcp.port=0} lands on a kernel-assigned port (ADR-028).
	 */
	public @Nullable AbstractReactiveServer listener() {
		return listener;
	}

	@Override
	public String toString() {
		return "JsonRpcTcpMount{" + (listener == null ? "disabled" : listener.toString()) + '}';
	}
}
