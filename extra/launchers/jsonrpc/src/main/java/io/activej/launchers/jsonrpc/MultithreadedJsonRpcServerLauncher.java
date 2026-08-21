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
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.inject.InstanceProvider;
import io.activej.inject.Key;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jmx.JmxModule;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;
import io.activej.launcher.Launcher;
import io.activej.net.PrimaryServer;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.service.ServiceGraphModuleSettings;
import io.activej.worker.WorkerPool;
import io.activej.worker.WorkerPoolModule;
import io.activej.worker.WorkerPools;
import io.activej.worker.annotation.Worker;

import java.net.InetSocketAddress;
import java.util.Set;

import static io.activej.common.Checks.checkArgument;
import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofMemSize;
import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.initializers.Initializers.ofEventloop;
import static io.activej.launchers.initializers.Initializers.ofHttpWorker;
import static io.activej.launchers.initializers.Initializers.ofPrimaryServer;

/**
 * A multi-worker JSON-RPC 2.0 server over HTTP POST, following {@code MultithreadedHttpServerLauncher}
 * (FR-070): one {@code JsonRpcDispatcher}, one servlet and one {@code HttpServer} per worker reactor, a
 * {@link PrimaryServer} accepting on every core, and JMX reporting the <b>sum</b> across workers
 * (FR-038 — the per-method reducers are what T046 verifies).
 * <p>
 * Configuration adds {@code workers} (default 4) and splits the eventloop keys into
 * {@code eventloop.primary} and {@code eventloop.worker}; the {@code jsonrpc.*} and {@code http.*} keys
 * are those of {@link JsonRpcServerLauncher}. The {@code jsonrpc.*} non-keys of
 * {@code config-keys.md} §4 are rejected in {@link #onStart()} through the same fail-closed check as
 * the single-worker launcher (FR-036).
 * <p>
 * Since feature 07 (FR-100…FR-103) {@code jsonrpc.tcp.port} additionally mounts a framed-TCP endpoint,
 * <b>disabled by default</b>: with a port set, one {@link JsonRpcTcpServer} per worker sits behind a
 * {@link PrimaryServer} accepting on the primary reactor, so a connection is served by whichever worker
 * accepted it and each worker's session registry sees only its own connections.
 *
 * @see Launcher
 */
public abstract class MultithreadedJsonRpcServerLauncher extends Launcher {
	public static final String HOSTNAME = "localhost";
	public static final int PORT = 8080;
	public static final int WORKERS = 4;

	public static final String PROPERTIES_FILE = "jsonrpc-server.properties";

	@Inject
	PrimaryServer primaryServer;

	/** The effective config; consumed by {@link #onStart()} — the last point at which it is readable. */
	@Inject
	Config config;

	@Provides
	NioReactor primaryReactor(Config config) {
		return Eventloop.builder()
			.initialize(ofEventloop(config.getChild("eventloop.primary")))
			.build();
	}

	@Provides
	@Worker
	NioReactor workerReactor(Config config, OptionalDependency<ThrottlingController> throttlingController) {
		return Eventloop.builder()
			.initialize(ofEventloop(config.getChild("eventloop.worker")))
			.withInspector(throttlingController.orElse(null))
			.build();
	}

	@Provides
	WorkerPool workerPool(WorkerPools workerPools, Config config) {
		int workers = config.get(ofInteger(), "workers", WORKERS);
		// D5-1 — unconditional, fail-closed like the FR-036 checks: a non-positive pool previously
		// died inside ServiceGraphModule with an exception naming neither the key nor a remedy
		checkArgument(workers > 0, "workers must be a positive integer, got: " + workers);
		return workerPools.createPool(workers);
	}

	@Provides
	PrimaryServer primaryServer(NioReactor primaryReactor, WorkerPool.Instances<HttpServer> workerServers, Config config) {
		return PrimaryServer.builder(primaryReactor, workerServers.getList())
			.initialize(ofPrimaryServer(config.getChild("http")))
			.build();
	}

	/** One statistics table <b>per worker</b> — the aggregation happens only in the JMX layer (data-model §5). */
	@Provides
	@Worker
	JsonRpcDispatcher.Inspector inspector() {
		return new JsonRpcDispatcher.JmxInspector();
	}

	@Provides
	@Worker
	JsonRpcDispatcher dispatcher(
		NioReactor reactor,
		OptionalDependency<Set<JsonRpcServiceBinding>> bindings,
		OptionalDependency<JsonCodecFactory> codecFactory,
		JsonRpcDispatcher.Inspector inspector
	) {
		JsonRpcDispatcher.Builder builder = JsonRpcDispatcher.builder(reactor)
			.withCodecFactory(codecFactory.orElse(JsonCodecFactory.defaultInstance()))
			.withInspector(inspector);
		for (JsonRpcServiceBinding binding : bindings.orElse(Set.of())) {
			@SuppressWarnings("unchecked")
			Class<Object> serviceType = (Class<Object>) binding.serviceType();
			builder.withService(serviceType, binding.implementation());
		}
		return builder.build();
	}

	@Provides
	@Worker
	JsonRpcServlet servlet(NioReactor reactor, JsonRpcDispatcher dispatcher, Config config) {
		Config jsonrpc = config.getChild("jsonrpc");
		return JsonRpcServlet.builder(reactor, dispatcher)
			.withMaxBodySize(jsonrpc.get(ofMemSize(), "maxBodySize", JsonRpcLimits.MAX_BODY_SIZE))
			.withEmptyResponseCode(jsonrpc.get(ofInteger(), "emptyResponseCode", 204))
			.build();
	}

	/**
	 * The servlet behind the WebSocket mount — one per worker, bound so the application can reach
	 * its worker's registry via {@code WorkerPool.getInstances(JsonRpcWsServlet.class)}. Resolved
	 * lazily and never constructed at startup when the endpoint is disabled (see
	 * {@link JsonRpcModule#wsServlet} — a lookup while disabled yields an unmounted servlet with an
	 * always-empty registry).
	 */
	@Provides
	@Worker
	JsonRpcWsServlet wsServlet(NioReactor reactor, JsonRpcDispatcher dispatcher) {
		return JsonRpcWsServlet.builder(reactor, dispatcher).build();
	}

	@Provides
	@Worker
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
	@Worker
	HttpServer workerServer(NioReactor reactor, AsyncServlet servlet, Config config) {
		return HttpServer.builder(reactor, servlet)
			.initialize(ofHttpWorker(config.getChild("http")))
			.build();
	}

	/**
	 * One framed-TCP server <b>per worker</b> (FR-103), each with its own dispatcher, session registry
	 * and transports on that worker's reactor. It carries <b>no listen address</b>: the primary acceptor
	 * built by {@link #tcpMount} owns the socket and hands accepted channels over, exactly as the HTTP
	 * worker servers are fed by {@code PrimaryServer}. Cross-worker {@code sessions()} and {@code
	 * broadcast(...)} are out of scope — each registry sees only the connections its own worker accepted.
	 * <p>
	 * Created only when {@code jsonrpc.tcp.port} carries a value, because only {@link #tcpMount} asks for
	 * these instances (FR-102).
	 */
	@Provides
	@Worker
	JsonRpcTcpServer tcpServer(
		NioReactor reactor, JsonRpcDispatcher dispatcher, OptionalDependency<JsonCodecFactory> codecFactory
	) {
		return JsonRpcTcpServer.builder(reactor, dispatcher)
			.withCodecFactory(codecFactory.orElse(JsonCodecFactory.defaultInstance()))
			.build();
	}

	/**
	 * The wiring-time mount decision (research D9): with {@code jsonrpc.tcp.port} absent or empty nothing
	 * is created at all — no worker server, no acceptor, no socket (FR-102). With a port, the per-worker
	 * servers are resolved here, while {@link Config} is still readable and before any reactor is running,
	 * and a {@link PrimaryServer} over them accepts on the primary reactor. It is <b>not</b> a binding of
	 * its own — the unqualified {@code PrimaryServer} key belongs to the HTTP acceptor — which is why it
	 * reaches the service graph through {@link JsonRpcTcpServerServiceAdapter} instead.
	 * <p>
	 * {@code workerPool} arrives as an {@link InstanceProvider} rather than directly: a binding that
	 * depends on {@code WorkerPool} and is not itself worker instances makes {@code ServiceGraphModule}
	 * log {@code "Unsupported service ... worker instances is expected"}.
	 */
	@Provides
	@Eager
	JsonRpcTcpMount tcpMount(NioReactor primaryReactor, Config config, InstanceProvider<WorkerPool> workerPool) {
		Integer port = JsonRpcModule.tcpPort(config.getChild("jsonrpc"));
		if (port == null) return JsonRpcTcpMount.disabled();
		return JsonRpcTcpMount.of(PrimaryServer.builder(primaryReactor,
				workerPool.get().getInstances(JsonRpcTcpServer.class).getList())
			.withListenPort(port)
			.build());
	}

	/**
	 * ADR-025's shape, plus one ordering edge the DI graph cannot express: when the endpoint is enabled,
	 * the acceptor must stop before the worker servers drain their sessions, so the mount is declared to
	 * depend on the per-worker {@link JsonRpcTcpServer}s. The worker servers themselves stay graph-managed
	 * by the platform's {@code forReactiveServer} adapter — that is what closes each of them on its own
	 * reactor, in order, before its eventloop stops.
	 * <p>
	 * ⚠ Unlike {@link JsonRpcModule}'s single-eventloop equivalent, this registration carries <b>no</b>
	 * {@code withExcludedKey(Key.of(JsonRpcTcpServer.class))}. That exclusion is needed there because the
	 * single-eventloop {@code JsonRpcTcpServer} <i>is</i> the directly-listening object the mount wraps —
	 * without it, {@code forReactiveServer} would start/stop the same server a second time. Here each
	 * per-worker {@link #tcpServer} carries no listen address at all ({@code listen()} no-ops on an empty
	 * address list), so the platform picking it up anyway opens no second socket and closes/stops it once,
	 * same as the pre-existing {@code @Worker HttpServer}. The asymmetry is deliberate, not a drift.
	 */
	@ProvidesIntoSet
	Initializer<ServiceGraphModuleSettings> tcpServiceGraphSettings(Config config) {
		boolean enabled = JsonRpcModule.tcpPort(config.getChild("jsonrpc")) != null;
		return settings -> {
			settings.withKey(Key.of(JsonRpcTcpMount.class), JsonRpcTcpServerServiceAdapter.create());
			if (enabled) {
				settings.withDependency(Key.of(JsonRpcTcpMount.class), Key.of(JsonRpcTcpServer.class));
			}
		};
	}

	@Provides
	Config config() {
		return Config.create()
			.with("http.listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(HOSTNAME, PORT)))
			.with("workers", "" + WORKERS)
			.overrideWith(ofClassPathProperties(PROPERTIES_FILE, true))
			.overrideWith(ofSystemProperties("config"));
	}

	@Override
	protected final Module getModule() {
		return combine(
			ServiceGraphModule.create(),
			WorkerPoolModule.create(),
			JmxModule.create(),
			ConfigModule.builder()
				.withEffectiveConfigLogger()
				.build(),
			getBusinessLogicModule()
		);
	}

	/**
	 * Override this method to supply your business logic: {@link JsonRpcServiceBinding} contributions
	 * (one per service interface) and anything else the application needs (FR-051).
	 */
	protected Module getBusinessLogicModule() {
		return Module.empty();
	}

	/** The FR-036 fail-closed check — the same four rejected keys as {@link JsonRpcServerLauncher}. */
	@Override
	protected void onStart() throws Exception {
		JsonRpcServerLauncher.rejectNonKeys(config.getChild("jsonrpc"));
	}

	@Override
	protected void run() throws Exception {
		if (logger.isInfoEnabled()) {
			logger.info("JSON-RPC server is now available at {}",
				primaryServer.getBoundAddresses().stream()
					.map(address -> "http://" + address.getHostString() + ":" + address.getPort())
					.toList());
		}
		awaitShutdown();
	}

	/** A minimal demo, mirroring {@link JsonRpcServerLauncher#main}. */
	public static void main(String[] args) throws Exception {
		Launcher launcher = new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding demoApi() {
						return new JsonRpcServiceBinding(JsonRpcServerLauncher.DemoApi.class,
							new JsonRpcServerLauncher.DemoApiImpl());
					}
				};
			}
		};
		launcher.launch(args);
	}
}
