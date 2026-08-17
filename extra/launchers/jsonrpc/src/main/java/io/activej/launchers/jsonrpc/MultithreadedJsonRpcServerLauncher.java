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
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
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
import io.activej.launcher.Launcher;
import io.activej.net.PrimaryServer;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
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

	@Provides
	@Worker
	AsyncServlet rootServlet(NioReactor reactor, JsonRpcServlet servlet, Config config) {
		return RoutingServlet.builder(reactor)
			.with(HttpMethod.POST, config.getChild("jsonrpc").get("path", "/"), servlet)
			.build();
	}

	@Provides
	@Worker
	HttpServer workerServer(NioReactor reactor, AsyncServlet servlet, Config config) {
		return HttpServer.builder(reactor, servlet)
			.initialize(ofHttpWorker(config.getChild("http")))
			.build();
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
