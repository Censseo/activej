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

package io.activej.starter.rpc;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;
import io.activej.rpc.server.RpcServer;
import io.activej.service.ServiceGraphModule;
import io.activej.starter.ReactorModule;

import java.net.InetSocketAddress;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;

/**
 * A composable starter module for RPC servers.
 * <p>
 * Provides a pre-configured {@link Config} with sensible defaults for RPC server
 * configuration. The user is expected to provide their own {@link RpcServer} binding
 * that consumes the {@link NioReactor} (from {@link ReactorModule}) and the {@link Config}.
 * <p>
 * Default configuration: {@code localhost:5353}, properties file {@code rpc-server.properties}.
 * The RPC config subtree is available at {@code config.getChild("rpc")}.
 */
public final class RpcStarterModule extends AbstractModule {
	public static final String DEFAULT_HOSTNAME = "localhost";
	public static final int DEFAULT_PORT = 5353;
	public static final String DEFAULT_PROPERTIES_FILE = "rpc-server.properties";

	private String hostname = DEFAULT_HOSTNAME;
	private int port = DEFAULT_PORT;
	private String propertiesFile = DEFAULT_PROPERTIES_FILE;

	private RpcStarterModule() {
	}

	public static RpcStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new RpcStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, RpcStarterModule> {
		private Builder() {}

		public Builder withHost(String hostname) {
			checkNotBuilt(this);
			RpcStarterModule.this.hostname = hostname;
			return this;
		}

		public Builder withPort(int port) {
			checkNotBuilt(this);
			RpcStarterModule.this.port = port;
			return this;
		}

		public Builder withPropertiesFile(String propertiesFile) {
			checkNotBuilt(this);
			RpcStarterModule.this.propertiesFile = propertiesFile;
			return this;
		}

		@Override
		protected RpcStarterModule doBuild() {
			return RpcStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		install(ServiceGraphModule.create());
		install(ConfigModule.builder()
			.withEffectiveConfigLogger()
			.build());
		install(ReactorModule.create());
	}

	@Provides
	Config config() {
		return Config.create()
			.with("rpc.listenAddresses", Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(hostname, port)))
			.overrideWith(ofClassPathProperties(propertiesFile, true))
			.overrideWith(ofSystemProperties("config"));
	}
}
