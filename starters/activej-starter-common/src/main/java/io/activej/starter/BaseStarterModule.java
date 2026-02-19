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

package io.activej.starter;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.service.ServiceGraphModule;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.ofInetSocketAddress;

import java.net.InetSocketAddress;

/**
 * Base starter module that installs {@link ServiceGraphModule} and {@link ConfigModule}
 * with effective config logging, and provides a default {@link Config} assembled from
 * classpath properties and system properties.
 * <p>
 * The builder allows overriding the default host, port, and properties file.
 */
public final class BaseStarterModule extends AbstractModule {
	public static final String DEFAULT_HOSTNAME = "localhost";
	public static final int DEFAULT_PORT = 8080;
	public static final String DEFAULT_PROPERTIES_FILE = "application.properties";

	private String hostname = DEFAULT_HOSTNAME;
	private int port = DEFAULT_PORT;
	private String propertiesFile = DEFAULT_PROPERTIES_FILE;
	private String listenAddressConfigKey = "http.listenAddresses";

	private BaseStarterModule() {
	}

	public static BaseStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new BaseStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, BaseStarterModule> {
		private Builder() {}

		public Builder withHost(String hostname) {
			checkNotBuilt(this);
			BaseStarterModule.this.hostname = hostname;
			return this;
		}

		public Builder withPort(int port) {
			checkNotBuilt(this);
			BaseStarterModule.this.port = port;
			return this;
		}

		public Builder withPropertiesFile(String propertiesFile) {
			checkNotBuilt(this);
			BaseStarterModule.this.propertiesFile = propertiesFile;
			return this;
		}

		public Builder withListenAddressConfigKey(String configKey) {
			checkNotBuilt(this);
			BaseStarterModule.this.listenAddressConfigKey = configKey;
			return this;
		}

		@Override
		protected BaseStarterModule doBuild() {
			return BaseStarterModule.this;
		}
	}

	@Override
	protected void configure() {
		install(ServiceGraphModule.create());
		install(ConfigModule.builder()
			.withEffectiveConfigLogger()
			.build());
	}

	@Provides
	Config config() {
		return Config.create()
			.with(listenAddressConfigKey, Config.ofValue(ofInetSocketAddress(), new InetSocketAddress(hostname, port)))
			.overrideWith(ofClassPathProperties(propertiesFile, true))
			.overrideWith(ofSystemProperties("config"));
	}
}
