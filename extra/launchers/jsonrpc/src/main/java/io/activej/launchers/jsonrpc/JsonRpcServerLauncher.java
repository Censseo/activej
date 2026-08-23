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
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.http.HttpServer;
import io.activej.inject.annotation.Inject;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jmx.JmxModule;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.launcher.Launcher;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;

import java.net.InetSocketAddress;
import java.util.Map;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofMemSize;
import static io.activej.inject.module.Modules.combine;
import static io.activej.launchers.initializers.Initializers.ofEventloop;

/**
 * A turnkey single-eventloop JSON-RPC 2.0 server over HTTP POST (FR-050).
 * <p>
 * Extend this class and contribute one {@link JsonRpcServiceBinding} per service interface in
 * {@link #getBusinessLogicModule()}; the module set — {@link ServiceGraphModule}, {@link JmxModule},
 * {@link ConfigModule} and {@link JsonRpcModule} — does the rest (FR-052). Service start/stop order is
 * derived by the service graph: the HTTP server stops before the eventloop it runs on (FR-053).
 * <p>
 * Configuration follows {@code HttpServerLauncher} exactly: built-in defaults ←
 * {@code jsonrpc-server.properties} ← {@code -Dconfig.<key>=<value>}. The keys this launcher
 * introduces are {@code jsonrpc.path}, {@code jsonrpc.maxBodySize}, {@code jsonrpc.emptyResponseCode}
 * and — since feature 06 (FR-101) — {@code jsonrpc.ws.path} (default {@code /ws}; empty disables the
 * WebSocket endpoint, which co-mounts beside POST on the same server).
 * Since feature 07 there is one more: {@code jsonrpc.tcp.port}, the framed-TCP endpoint's own listener,
 * <b>disabled by default</b> — absent or empty means no server is constructed and no socket is opened.
 * The asymmetry is deliberate: the WebSocket route rides the HTTP listener that already exists, while a
 * raw TCP port opens a new one, plaintext and unauthenticated by design.
 * Since feature 018 there are three more: {@code jsonrpc.discovery.path} — <b>empty by default</b>,
 * and setting it both answers {@code rpc.discover} on every transport and mounts a read-only OpenRPC
 * {@code GET} endpoint at that path — together with {@code jsonrpc.discovery.info.title} and
 * {@code jsonrpc.discovery.info.version}, which are <b>both required</b> once discovery is on: OpenRPC
 * requires them and this launcher never invents metadata, so leaving one out fails startup naming it.
 * The four keys that deliberately do <b>not</b> exist — {@code jsonrpc.maxBatchSize},
 * {@code jsonrpc.maxJsonDepth}, {@code jsonrpc.callTimeout}, {@code jsonrpc.maxInFlight} — every
 * {@code jsonrpc.ws.*} key other than {@code jsonrpc.ws.path}, every {@code jsonrpc.tcp.*} key other
 * than {@code jsonrpc.tcp.port}, every {@code jsonrpc.discovery.*} key outside the three above, and a
 * scalar {@code jsonrpc.ws}, {@code jsonrpc.tcp}, {@code jsonrpc.discovery} or
 * {@code jsonrpc.discovery.info} value, fail startup loudly (FR-036, see {@link #onStart()}).
 *
 * @see Launcher
 */
public abstract class JsonRpcServerLauncher extends Launcher {
	public static final String HOSTNAME = "localhost";
	public static final int PORT = 8080;
	public static final String PROPERTIES_FILE = "jsonrpc-server.properties";

	@Inject
	HttpServer httpServer;

	/** The effective config; consumed by {@link #onStart()} — the last point at which it is readable. */
	@Inject
	Config config;

	@Provides
	NioReactor reactor(Config config, OptionalDependency<ThrottlingController> throttlingController) {
		return Eventloop.builder()
			.initialize(ofEventloop(config.getChild("eventloop")))
			.withInspector(throttlingController.orElse(null))
			.build();
	}

	@Provides
	Config config() {
		return Config.create()
			.with("http.listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(HOSTNAME, PORT)))
			.overrideWith(ofClassPathProperties(PROPERTIES_FILE, true))
			.overrideWith(ofSystemProperties("config"));
	}

	/** The JMX view of the dispatcher — without this binding the launcher would ship uninstrumented (FR-052). */
	@Provides
	JsonRpcDispatcher.Inspector inspector() {
		return new JsonRpcDispatcher.JmxInspector();
	}

	@Override
	protected final Module getModule() {
		return combine(
			ServiceGraphModule.create(),
			JmxModule.create(),
			ConfigModule.builder()
				.withEffectiveConfigLogger()
				.build(),
			new JsonRpcModule(),
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

	/**
	 * The FR-036 fail-closed check, and FR-035's config-derived logging — both here because it is the
	 * last point at which {@link Config} is readable: {@code ConfigModule} wraps it in a
	 * {@code ProtectedConfig} once {@code @OnStart} completes.
	 * <p>
	 * {@code ConfigModule} reports unconsumed keys by marking them {@code ##} in the effective-config
	 * dump and <b>never fails on them</b> — an operator who set {@code jsonrpc.maxBatchSize=10} believing
	 * they had tightened a security bound would otherwise be wrong with no signal. The rejected keys are
	 * named, never their values (SI-6). {@link #rejectNonKeys(Config)} is the shared implementation,
	 * used by the multi-worker launcher as well.
	 */
	@Override
	protected void onStart() throws Exception {
		Config jsonrpc = config.getChild("jsonrpc");
		rejectNonKeys(jsonrpc);

		if (logger.isInfoEnabled()) {
			String discoveryPath = JsonRpcModule.discoveryPath(jsonrpc);
			logger.info("JSON-RPC endpoint: path={}, maxBodySize={}, emptyResponseCode={}, discovery={}",
				jsonrpc.get("path", "/"),
				jsonrpc.get(ofMemSize(), "maxBodySize", JsonRpcLimits.MAX_BODY_SIZE),
				jsonrpc.get(ofInteger(), "emptyResponseCode", 204),
				// the path, or the fact that the disclosure surface is closed — a key name and a route,
				// never a configured value that could be a secret (SI-6)
				discoveryPath == null ? "disabled" : discoveryPath);
		}
	}

	/**
	 * FR-036 fail-closed: rejects the {@code jsonrpc.*} keys that deliberately do not exist
	 * (contracts/config-keys.md §4) — the four feature-09 keys, naming the key and the controlling
	 * {@code ApplicationSettings} property — and, since feature 06 (FR-101), every {@code ws.*}
	 * child key other than {@code path}, naming the full key. A <b>scalar</b> {@code jsonrpc.ws}
	 * value (e.g. {@code jsonrpc.ws=/ws}, a plausible typo for the one real key) is rejected too:
	 * the node carries no children, so the child-key loop alone would miss it and silently mount
	 * the default. Since feature 07 the {@code tcp.*} subtree is checked identically, admitting
	 * {@code tcp.port} alone; since feature 018 the {@code discovery.*} subtree is <b>admitted</b> the
	 * same way, closed over exactly {@code discovery.path}, {@code discovery.info.title} and
	 * {@code discovery.info.version}, with both scalar forms rejected and all three required to be
	 * <b>leaves</b> ({@link #rejectDescendantsOf}). Shared by
	 * {@link JsonRpcServerLauncher} and {@link MultithreadedJsonRpcServerLauncher}.
	 */
	static void rejectNonKeys(Config jsonrpc) {
		Map<String, Config> children = jsonrpc.getChildren();
		rejectIfPresent(children, "maxBatchSize",
			"the batch bound is process-wide and is read directly by JsonRpcDecoder. Set -DJsonRpcLimits.maxBatchSize=<n> instead.");
		rejectIfPresent(children, "maxJsonDepth",
			"the nesting bound is process-wide and is read directly by JsonRpcDecoder. Set -DJsonRpcLimits.maxJsonDepth=<n> instead.");
		rejectIfPresent(children, "callTimeout",
			"a per-call deadline is not yet available. The connection-level http.readWriteTimeout bounds a stalled request meanwhile.");
		rejectIfPresent(children, "maxInFlight",
			"an in-flight bound is not yet available — the dispatcher deliberately keeps no in-flight registry.");
		// FR-101: the ws.* subtree admits exactly ws.path; every other key under it is a non-key,
		// rejected naming the full key (contracts/config-keys.md). getChild("ws") of a config with
		// no ws subtree is EMPTY, so a deployment without WebSocket keys walks an empty map.
		Config ws = jsonrpc.getChild("ws");
		if (ws.hasValue()) {
			throw new IllegalStateException(
				"Configuration key 'jsonrpc.ws' is not supported: the only WebSocket key is 'jsonrpc.ws.path'.\n" +
				"(Set jsonrpc.ws.path to a path, or to an empty value to disable the WebSocket endpoint.)");
		}
		for (String key : ws.getChildren().keySet()) {
			if (!"path".equals(key)) {
				throw new IllegalStateException(
					"Configuration key 'jsonrpc.ws." + key + "' is not supported: the only WebSocket key is 'jsonrpc.ws.path'.\n" +
					"(Set jsonrpc.ws.path to a path, or to an empty value to disable the WebSocket endpoint.)");
			}
		}
		// FR-101, feature 07: the tcp.* subtree admits exactly tcp.port, the same shape — including the
		// scalar `jsonrpc.tcp` typo, which carries no children for the key loop to find and would
		// otherwise be swallowed silently while the endpoint stayed off.
		Config tcp = jsonrpc.getChild("tcp");
		if (tcp.hasValue()) {
			throw new IllegalStateException(
				"Configuration key 'jsonrpc.tcp' is not supported: the only TCP key is 'jsonrpc.tcp.port'.\n" +
				"(Set jsonrpc.tcp.port to a port, or leave it unset to keep the TCP endpoint disabled.)");
		}
		for (String key : tcp.getChildren().keySet()) {
			if (!"port".equals(key)) {
				throw new IllegalStateException(
					"Configuration key 'jsonrpc.tcp." + key + "' is not supported: the only TCP key is 'jsonrpc.tcp.port'.\n" +
					"(Set jsonrpc.tcp.port to a port, or leave it unset to keep the TCP endpoint disabled.)");
			}
		}
		// Feature 018 (FR-003, FR-013): the discovery.* subtree is ADMITTED — three real keys, path plus
		// the two Info Object members — and closed, the same shape ws.*/tcp.* already have. Every other
		// jsonrpc.* key is treated exactly as before; admitting this subtree neither widens nor narrows
		// the rule. The scalar forms `jsonrpc.discovery` and `jsonrpc.discovery.info` carry no children
		// for the key loops to find, so each is rejected on its own: a typo of jsonrpc.discovery.path
		// would otherwise start silently with discovery OFF, which is precisely the state an operator
		// setting the key was trying to leave.
		Config discovery = jsonrpc.getChild("discovery");
		if (discovery.hasValue()) throw discoveryNonKey("jsonrpc.discovery");
		for (Map.Entry<String, Config> child : discovery.getChildren().entrySet()) {
			String key = child.getKey();
			if ("info".equals(key)) continue;
			if (!"path".equals(key)) throw discoveryNonKey("jsonrpc.discovery." + key);
			rejectDescendantsOf(child.getValue(), "jsonrpc.discovery.path");
		}
		Config info = discovery.getChild("info");
		if (info.hasValue()) throw discoveryNonKey("jsonrpc.discovery.info");
		for (Map.Entry<String, Config> member : info.getChildren().entrySet()) {
			String key = member.getKey();
			if (!"title".equals(key) && !"version".equals(key)) {
				throw discoveryNonKey("jsonrpc.discovery.info." + key);
			}
			rejectDescendantsOf(member.getValue(), "jsonrpc.discovery.info." + key);
		}
	}

	/**
	 * All three discovery keys are <b>leaves</b>: {@code jsonrpc.discovery.info.title.extra} configures
	 * nothing, so — like every other {@code jsonrpc.discovery.*} key outside the three — it is refused
	 * by name rather than left to be reported as an unconsumed key nobody reads.
	 * <p>
	 * Without this the closed key set was closed one level deep only: the loops above walk the immediate
	 * children of {@code discovery} and of {@code discovery.info}, and a grandchild of an admitted key
	 * was invisible to both. Note the deliberate asymmetry with the {@code ws.*} and {@code tcp.*}
	 * subtrees, which still check one level: those belong to features 015 and 017, and tightening them
	 * is their owners' call.
	 */
	private static void rejectDescendantsOf(Config leaf, String key) {
		Map<String, Config> descendants = leaf.getChildren();
		if (!descendants.isEmpty()) {
			throw discoveryNonKey(key + "." + descendants.keySet().iterator().next());
		}
	}

	private static IllegalStateException discoveryNonKey(String key) {
		return new IllegalStateException(
			"Configuration key '" + key + "' is not supported: the discovery keys are " +
			"'jsonrpc.discovery.path', 'jsonrpc.discovery.info.title' and 'jsonrpc.discovery.info.version'.\n" +
			"(Set jsonrpc.discovery.path to a path — with both info keys — or leave it unset to keep " +
			"discovery disabled.)");
	}

	private static void rejectIfPresent(Map<String, Config> children, String key, String why) {
		if (children.containsKey(key)) {
			throw new IllegalStateException(
				"Configuration key 'jsonrpc." + key + "' is not supported: " + why + "\n" +
				"(A per-instance override is owned by feature 09.)");
		}
	}

	@Override
	protected void run() throws Exception {
		// FR-054 + ADR-028: the bound address comes from the server, never from Config — so this read
		// is legal after @OnStart, and a ":0" bind reports the real kernel-assigned port
		if (logger.isInfoEnabled()) {
			logger.info("JSON-RPC server is now available at {}",
				httpServer.getBoundAddresses().stream()
					.map(address -> "http://" + address.getHostString() + ":" + address.getPort())
					.toList());
		}
		awaitShutdown();
	}

	/** A minimal demo: run with {@code -Dconfig.http.listenAddresses=0} for an ephemeral port. */
	public static void main(String[] args) throws Exception {
		Launcher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding demoApi() {
						return new JsonRpcServiceBinding(DemoApi.class, new DemoApiImpl());
					}
				};
			}
		};
		launcher.launch(args);
	}

	@JsonRpcService("demo")
	public interface DemoApi {
		@JsonRpcMethod("hello")
		Promise<String> hello(@JsonRpcParam("name") String name);
	}

	/** The {@link DemoApi} implementation shown by {@code main} — greets the caller by name. */
	public static final class DemoApiImpl implements DemoApi {
		@Override
		public Promise<String> hello(String name) {
			return Promise.of("Hello, " + name + "!");
		}
	}
}
