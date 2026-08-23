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

import io.activej.common.initializer.Initializer;
import io.activej.config.Config;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.inject.InstanceProvider;
import io.activej.inject.Key;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcDiscoveryServlet;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModuleSettings;
import org.jetbrains.annotations.Nullable;

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
 * <p>
 * The {@link JsonRpcTcpServer} is bound the same lazy way and is <b>disabled by default</b>
 * (FR-100…FR-102): it is resolved only when {@code jsonrpc.tcp.port} carries a value, because — unlike
 * the WebSocket route, which rides the HTTP listener that already exists — it opens a <b>new</b>
 * listening socket, plaintext and unauthenticated by design.
 * <p>
 * The {@link JsonRpcDiscoveryServlet} follows the same lazy shape, keyed on {@code jsonrpc.discovery.path}
 * (feature 018, FR-003/FR-041/FR-042). It rides the existing HTTP listener like the WebSocket route, yet
 * it is <b>off by default</b> too: the OpenRPC document names every wire method and describes every
 * parameter and result type, so it is a disclosure surface an operator opts into. Switching it on also
 * switches on the dispatcher's {@code rpc.discover} entry — one document, generated once, served by both.
 */
public final class JsonRpcModule extends AbstractModule {
	@Provides
	JsonRpcDispatcher dispatcher(
		NioReactor reactor,
		OptionalDependency<Set<JsonRpcServiceBinding>> bindings,
		OptionalDependency<JsonCodecFactory> codecFactory,
		OptionalDependency<JsonRpcDispatcher.Inspector> inspector,
		Config config
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
		// FR-003/FR-013: presence of jsonrpc.discovery.path is the switch (ADR-042). Absent or empty and
		// withDiscovery is never called at all — the dispatcher's rpc.discover entry does not exist, no
		// document is generated, and discoveryDocument() stays null
		OpenRpcInfo discoveryInfo = discoveryInfo(config.getChild("jsonrpc"));
		if (discoveryInfo != null) {
			builder.withDiscovery(discoveryInfo);
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

	/**
	 * The read-only OpenRPC {@code GET} endpoint (FR-041) — an ordinary binding, so an application that
	 * composes its own {@link RoutingServlet} can mount it wherever it likes. {@link #rootServlet}
	 * resolves it lazily through the {@link InstanceProvider} and only when {@code jsonrpc.discovery.path}
	 * carries a value, so a deployment with discovery off constructs nothing at startup.
	 * <p>
	 * A lookup while discovery is disabled yields a servlet over a dispatcher that holds no document; it
	 * answers {@code 404}, exactly as the unmounted path does (the servlet's own {@code null}-document
	 * branch). As with {@link #wsServlet}, the provider cannot re-check the key at lookup time —
	 * {@link Config} is readable during startup only.
	 */
	@Provides
	JsonRpcDiscoveryServlet discoveryServlet(NioReactor reactor, JsonRpcDispatcher dispatcher) {
		return JsonRpcDiscoveryServlet.create(reactor, dispatcher);
	}

	@Provides
	AsyncServlet rootServlet(
		NioReactor reactor, JsonRpcServlet servlet,
		InstanceProvider<JsonRpcWsServlet> wsServlet,
		InstanceProvider<JsonRpcDiscoveryServlet> discoveryServlet,
		Config config
	) {
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
		mountDiscovery(builder, jsonrpc, discoveryServlet);
		return builder.build();
	}

	@Provides
	HttpServer server(NioReactor reactor, AsyncServlet rootServlet, Config config) {
		// FR-024: the existing initializer bundle, verbatim
		return HttpServer.builder(reactor, rootServlet)
			.initialize(ofHttpServer(config.getChild("http")))
			.build();
	}

	/**
	 * The framed-TCP endpoint — its <b>own</b> listener, on the same reactor and behind the same
	 * {@link JsonRpcDispatcher} as the POST and WebSocket routes (FR-100). Bound lazily: {@link
	 * #tcpMount} resolves it only when {@code jsonrpc.tcp.port} carries a value, so a deployment that
	 * left the key alone constructs no server and opens no socket (FR-102).
	 * <p>
	 * The port is read here, at wiring time — the only point at which {@link Config} is readable — and
	 * {@code 0} binds an ephemeral port, which {@code getBoundAddresses()} reports back (ADR-028). A
	 * lookup of this binding while the endpoint is disabled yields a server listening on port {@code 0}
	 * that nothing ever starts: the provider cannot re-check the key at lookup time.
	 */
	@Provides
	JsonRpcTcpServer tcpServer(
		NioReactor reactor, JsonRpcDispatcher dispatcher, OptionalDependency<JsonCodecFactory> codecFactory, Config config
	) {
		Integer port = tcpPort(config.getChild("jsonrpc"));
		return JsonRpcTcpServer.builder(reactor, dispatcher)
			.withCodecFactory(codecFactory.orElse(JsonCodecFactory.defaultInstance()))
			.withListenPort(port == null ? 0 : port)
			.build();
	}

	/**
	 * The wiring-time mount decision (research D9). {@code reactor} is a declared dependency so that the
	 * service graph — which derives its edges from the DI graph — orders this mount against the eventloop
	 * that runs the listener: the endpoint must close before its reactor stops.
	 */
	@Provides
	@Eager
	JsonRpcTcpMount tcpMount(NioReactor reactor, Config config, InstanceProvider<JsonRpcTcpServer> tcpServer) {
		if (tcpPort(config.getChild("jsonrpc")) == null) return JsonRpcTcpMount.disabled();
		return JsonRpcTcpMount.of(tcpServer.get());
	}

	/**
	 * ADR-025's shape: the mount joins the service graph through a settings initializer, and the
	 * {@link JsonRpcTcpServer} key is excluded from it so that the listener has exactly <b>one</b> owner
	 * — this adapter — rather than being adapted a second time by the platform's {@code
	 * forReactiveServer}.
	 */
	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> tcpServiceGraphSettings() {
		return settings -> settings
			.withKey(Key.of(JsonRpcTcpMount.class), JsonRpcTcpServerServiceAdapter.create())
			.withExcludedKey(Key.of(JsonRpcTcpServer.class));
	}

	/**
	 * The one TCP key, read from the {@code jsonrpc} subtree: {@code null} when absent or empty — the
	 * endpoint is off — and the port otherwise (contracts/config-keys.md). Shared with
	 * {@link MultithreadedJsonRpcServerLauncher}, which wires the same key through the worker scope.
	 */
	static @Nullable Integer tcpPort(Config jsonrpc) {
		if (jsonrpc.get("tcp.port", "").isEmpty()) return null;
		return jsonrpc.get(ofInteger(), "tcp.port");
	}

	/**
	 * The one discovery switch, read from the {@code jsonrpc} subtree: {@code null} when
	 * {@code jsonrpc.discovery.path} is absent or empty — discovery is <b>off</b>, no document is
	 * generated and no route is mounted — and the path otherwise (contracts/config-keys.md).
	 * <p>
	 * <b>Presence is the switch</b> (ADR-042), and here it may default to <i>on</i>-able because the
	 * endpoint rides the {@link HttpServer} the deployment already runs — yet it defaults to
	 * <b>off</b> anyway, for the other half of the rule: the document names every wire method and
	 * describes every parameter and result type, so an upgrade must not start disclosing a surface
	 * nobody asked to publish.
	 * <p>
	 * Shared with {@link MultithreadedJsonRpcServerLauncher}, which mounts the same route per worker.
	 */
	static @Nullable String discoveryPath(Config jsonrpc) {
		String path = jsonrpc.get("discovery.path", "");
		return path.isEmpty() ? null : path;
	}

	/**
	 * The OpenRPC Info Object the dispatcher is built with, or {@code null} when discovery is off.
	 * <p>
	 * Both members are REQUIRED by OpenRPC and <b>neither is invented here</b> (FR-013): nothing derives
	 * a title from a class name or a version from a POM. A deployment that switched discovery on without
	 * describing it has asked for a document it has not named, so startup fails <b>naming the key it
	 * left out</b> — the line's fail-closed posture (ADR-037), and the same shape as the
	 * {@code workers <= 0} refusal: raised from a provider, at wiring time, before anything is listening.
	 */
	static @Nullable OpenRpcInfo discoveryInfo(Config jsonrpc) {
		if (discoveryPath(jsonrpc) == null) return null;
		return new OpenRpcInfo(requiredInfoMember(jsonrpc, "title"), requiredInfoMember(jsonrpc, "version"));
	}

	/**
	 * The presence check on one required Info Object member.
	 * <p>
	 * <b>{@code isBlank()}, not {@code isEmpty()}.</b> The rule this check enforces is that a published
	 * document is never <i>unnamed</i>, and a whitespace-only title names a service exactly as poorly as
	 * a missing one — every consumer renders it blank — while passing an emptiness test. A deployment
	 * that set the key to {@code "   "} has still not described the document it switched on, so it is
	 * refused the same way, naming the same key.
	 * <p>
	 * The <b>value</b> is never trimmed: whatever the application supplied is carried into the document
	 * verbatim (FR-013, rule M9). Only the check ignores surrounding whitespace.
	 */
	private static String requiredInfoMember(Config jsonrpc, String member) {
		String value = jsonrpc.get("discovery.info." + member, "");
		if (value.isBlank()) {
			throw new IllegalStateException(
				"Configuration key 'jsonrpc.discovery.info." + member + "' is required when discovery is " +
				"enabled, and must not be blank: OpenRPC requires both info.title and info.version, and the " +
				"launcher never invents them.\n" +
				"(Set jsonrpc.discovery.info." + member + " to a non-blank value, or leave " +
				"jsonrpc.discovery.path empty to keep discovery disabled.)");
		}
		return value;
	}

	/**
	 * The {@code GET} mount, shared verbatim by <b>both</b> route-construction sites — this module's
	 * {@link #rootServlet} and {@link MultithreadedJsonRpcServerLauncher}'s {@code @Worker} one. Editing
	 * one without the other is this module's standing defect shape, so the two call one method.
	 * <p>
	 * <b>Mounted method-agnostically, deliberately.</b> {@code with(path, servlet)} lands on
	 * {@code RoutingServlet}'s any-method slot rather than the {@code GET} one, and that is what keeps a
	 * {@code jsonrpc.discovery.path} equal to {@code jsonrpc.path} (or to any other already-mounted path)
	 * working: the router prefers a method-specific slot over the any-method one, so {@code POST} still
	 * reaches {@link JsonRpcServlet} while {@code GET} — and every other method — reaches the discovery
	 * servlet. Mounting on {@code GET} alone would instead make a non-{@code GET} request to that path
	 * fall through to the router's bare {@code 404}; the pinned {@code 405} + {@code Allow: GET} is the
	 * <b>servlet's own</b> answer ({@link JsonRpcDiscoveryServlet}), never the router's, precisely so the
	 * status holds however the endpoint is mounted.
	 */
	static void mountDiscovery(
		RoutingServlet.Builder builder, Config jsonrpc, InstanceProvider<JsonRpcDiscoveryServlet> discoveryServlet
	) {
		String discoveryPath = discoveryPath(jsonrpc);
		if (discoveryPath == null) return;
		builder.with(discoveryPath, discoveryServlet.get());
	}
}
