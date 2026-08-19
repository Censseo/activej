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

import io.activej.config.Config;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.inject.InstanceProvider;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;
import io.activej.reactor.nio.NioReactor;

import java.util.Set;

import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofMemSize;
import static io.activej.launchers.initializers.Initializers.ofHttpServer;

/**
 * The server-side wiring of the JSON-RPC launcher: dispatcher, servlet, root {@link RoutingServlet} and
 * {@link HttpServer}, all composed from existing components — nothing here constructs them by hand
 * (FR-010, FR-011, FR-014, FR-015, FR-024).
 * <p>
 * Lives in this launcher module because it is the only place permitted to depend on both the transport
 * ({@code activej-jsonrpc-http}) and the boot stack (FR-011, ADR-034).
 * <p>
 * The dispatcher's {@code Inspector} is an {@link OptionalDependency} — JMX stays opt-in (FR-031);
 * {@link JsonRpcServerLauncher} binds the {@code JmxInspector}, an embedded host may bind another or none.
 * <p>
 * The mounted {@link JsonRpcWsServlet} is an ordinary binding of this module — inject it to reach
 * {@code sessions()}, {@code broadcast(...)} and each session's server-initiated client. It is
 * resolved lazily and only when {@code jsonrpc.ws.path} is non-empty, so a disabled endpoint
 * constructs nothing at startup.
 */
public final class JsonRpcModule extends AbstractModule {
	@Provides
	JsonRpcDispatcher dispatcher(
		NioReactor reactor,
		OptionalDependency<Set<JsonRpcServiceBinding>> bindings,
		OptionalDependency<JsonCodecFactory> codecFactory,
		OptionalDependency<JsonRpcDispatcher.Inspector> inspector
	) {
		JsonRpcDispatcher.Builder builder = JsonRpcDispatcher.builder(reactor)
			.withCodecFactory(codecFactory.orElse(JsonCodecFactory.defaultInstance()));
		for (JsonRpcServiceBinding binding : bindings.orElse(Set.of())) {
			@SuppressWarnings("unchecked")
			Class<Object> serviceType = (Class<Object>) binding.serviceType();
			// withService validates serviceType.isInstance(implementation) — the cast is safe by construction
			builder.withService(serviceType, binding.implementation());
		}
		if (inspector.isPresent()) {
			builder.withInspector(inspector.get());
		}
		return builder.build();
	}

	@Provides
	JsonRpcServlet servlet(NioReactor reactor, JsonRpcDispatcher dispatcher, Config config) {
		Config jsonrpc = config.getChild("jsonrpc");
		// FR-022/FR-023: the ApplicationSettings values are the defaults; a configured key overrides them
		return JsonRpcServlet.builder(reactor, dispatcher)
			.withMaxBodySize(jsonrpc.get(ofMemSize(), "maxBodySize", JsonRpcLimits.MAX_BODY_SIZE))
			.withEmptyResponseCode(jsonrpc.get(ofInteger(), "emptyResponseCode", 204))
			.build();
	}

	/**
	 * The servlet behind the WebSocket mount — bound so the application can reach
	 * {@code sessions()} / {@code broadcast(...)}. {@link #rootServlet} resolves it lazily through
	 * the {@link InstanceProvider} and only on a non-empty {@code jsonrpc.ws.path}, so a disabled
	 * endpoint constructs nothing at startup (FR-102, research R9 — {@code WebSocketServlet}'s
	 * constructor refuses construction when {@code IWebSocket.ENABLED} is off). A lookup while the
	 * endpoint is disabled yields an <b>unmounted</b> servlet with an always-empty registry: the
	 * provider cannot re-check {@code jsonrpc.ws.path} at lookup time, because {@code Config} is
	 * readable during startup only.
	 */
	@Provides
	JsonRpcWsServlet wsServlet(NioReactor reactor, JsonRpcDispatcher dispatcher) {
		return JsonRpcWsServlet.builder(reactor, dispatcher).build();
	}

	@Provides
	AsyncServlet rootServlet(NioReactor reactor, JsonRpcServlet servlet, InstanceProvider<JsonRpcWsServlet> wsServlet, Config config) {
		Config jsonrpc = config.getChild("jsonrpc");
		RoutingServlet.Builder builder = RoutingServlet.builder(reactor)
			.with(HttpMethod.POST, jsonrpc.get("path", "/"), servlet);
		// FR-100/FR-101/FR-102: the WebSocket endpoint co-mounts beside POST on the same HttpServer.
		// An empty jsonrpc.ws.path disables it — the lazy InstanceProvider is never resolved, so no
		// JsonRpcWsServlet is constructed (sidestepping IWebSocket.ENABLED's checkState in the
		// WebSocketServlet constructor, research R9). Both route sites (JsonRpcModule and
		// MultithreadedJsonRpcServerLauncher) are edited identically (CHK049).
		String wsPath = jsonrpc.get("ws.path", "/ws");
		if (!wsPath.isEmpty()) {
			builder.withWebSocket(wsPath, wsServlet.get());
		}
		return builder.build();
	}

	@Provides
	HttpServer server(NioReactor reactor, AsyncServlet rootServlet, Config config) {
		// FR-024: the existing initializer bundle, verbatim
		return HttpServer.builder(reactor, rootServlet)
			.initialize(ofHttpServer(config.getChild("http")))
			.build();
	}
}
