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

package io.activej.starter.http;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.inspector.ThrottlingController;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpServer;
import io.activej.inject.annotation.Provides;
import io.activej.inject.binding.OptionalDependency;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.net.PrimaryServer;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.starter.ReactorModule;
import io.activej.worker.WorkerPool;
import io.activej.worker.WorkerPoolModule;
import io.activej.worker.WorkerPools;
import io.activej.worker.annotation.Worker;

import java.net.InetSocketAddress;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;
import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.launchers.initializers.Initializers.*;

/**
 * A composable starter module for HTTP servers.
 * <p>
 * Supports two modes:
 * <ul>
 *     <li><b>Single-thread mode</b> (default): consumes {@link NioReactor} from {@link ReactorModule},
 *         provides a single {@link HttpServer}.</li>
 *     <li><b>Multi-thread mode</b> (via {@link Builder#withWorkers(int)}): provides its own primary
 *         and worker reactors, a {@link PrimaryServer}, and worker {@link HttpServer} instances.</li>
 * </ul>
 * <p>
 * Default configuration: {@code localhost:8080}, properties file {@code http-server.properties}.
 */
public final class HttpStarterModule extends AbstractModule {
	public static final String DEFAULT_HOSTNAME = "localhost";
	public static final int DEFAULT_PORT = 8080;
	public static final int DEFAULT_WORKERS = 4;
	public static final String DEFAULT_PROPERTIES_FILE = "http-server.properties";

	private String hostname = DEFAULT_HOSTNAME;
	private int port = DEFAULT_PORT;
	private String propertiesFile = DEFAULT_PROPERTIES_FILE;
	private int workers = 0;

	private HttpStarterModule() {
	}

	public static HttpStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new HttpStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, HttpStarterModule> {
		private Builder() {}

		public Builder withHost(String hostname) {
			checkNotBuilt(this);
			HttpStarterModule.this.hostname = hostname;
			return this;
		}

		public Builder withPort(int port) {
			checkNotBuilt(this);
			HttpStarterModule.this.port = port;
			return this;
		}

		public Builder withPropertiesFile(String propertiesFile) {
			checkNotBuilt(this);
			HttpStarterModule.this.propertiesFile = propertiesFile;
			return this;
		}

		public Builder withWorkers(int workers) {
			checkNotBuilt(this);
			HttpStarterModule.this.workers = workers;
			return this;
		}

		@Override
		protected HttpStarterModule doBuild() {
			return HttpStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		install(ServiceGraphModule.create());
		install(ConfigModule.builder()
			.withEffectiveConfigLogger()
			.build());

		if (workers > 0) {
			install(WorkerPoolModule.create());
			install(multithreadModule());
		} else {
			install(ReactorModule.create());
			install(singleThreadModule());
		}
	}

	@Provides
	Config config() {
		Config config = Config.create()
			.with("http.listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(hostname, port)));
		if (workers > 0) {
			config = config.with("workers", "" + workers);
		}
		return config
			.overrideWith(ofClassPathProperties(propertiesFile, true))
			.overrideWith(ofSystemProperties("config"));
	}

	private static Module singleThreadModule() {
		return new AbstractModule() {
			@Provides
			HttpServer httpServer(NioReactor reactor, AsyncServlet servlet, Config config) {
				return HttpServer.builder(reactor, servlet)
					.initialize(ofHttpServer(config.getChild("http")))
					.build();
			}
		};
	}

	private Module multithreadModule() {
		return new AbstractModule() {
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
				return workerPools.createPool(config.get(ofInteger(), "workers", DEFAULT_WORKERS));
			}

			@Provides
			PrimaryServer primaryServer(NioReactor primaryReactor, WorkerPool.Instances<HttpServer> workerServers, Config config) {
				return PrimaryServer.builder(primaryReactor, workerServers.getList())
					.initialize(ofPrimaryServer(config.getChild("http")))
					.build();
			}

			@Provides
			@Worker
			HttpServer workerHttpServer(NioReactor reactor, AsyncServlet servlet, Config config) {
				return HttpServer.builder(reactor, servlet)
					.initialize(ofHttpWorker(config.getChild("http")))
					.build();
			}
		};
	}
}
