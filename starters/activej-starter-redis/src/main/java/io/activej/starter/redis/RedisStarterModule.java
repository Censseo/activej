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

package io.activej.starter.redis;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;
import io.activej.redis.RedisClient;

import java.net.InetSocketAddress;

import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.config.converter.ConfigConverters.ofString;

/**
 * Starter module for {@link RedisClient}.
 * <p>
 * Provides a pre-configured {@link RedisClient} that connects to a Redis server.
 * The host and port are read from config properties {@code "redis.host"} and
 * {@code "redis.port"}, defaulting to {@code localhost:6379}.
 * <p>
 * The module consumes a {@link NioReactor} which should be provided by a
 * shared ReactorModule or another starter.
 *
 * <pre>{@code
 * // Default usage (localhost:6379)
 * Module module = RedisStarterModule.create();
 *
 * // Custom host and port
 * Module module = RedisStarterModule.builder()
 *     .withHost("redis.example.com")
 *     .withPort(6380)
 *     .build();
 * }</pre>
 */
public final class RedisStarterModule extends AbstractModule {
	public static final String DEFAULT_HOST = "localhost";
	public static final int DEFAULT_PORT = 6379;

	private String host = DEFAULT_HOST;
	private int port = DEFAULT_PORT;

	private RedisStarterModule() {
	}

	public static RedisStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new RedisStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, RedisStarterModule> {
		private Builder() {}

		public Builder withHost(String host) {
			checkNotBuilt(this);
			RedisStarterModule.this.host = host;
			return this;
		}

		public Builder withPort(int port) {
			checkNotBuilt(this);
			RedisStarterModule.this.port = port;
			return this;
		}

		@Override
		protected RedisStarterModule doBuild() {
			return RedisStarterModule.this;
		}
	}

	@Provides
	RedisClient redisClient(NioReactor reactor, Config config) {
		String resolvedHost = config.get(ofString(), "redis.host", host);
		int resolvedPort = config.get(ofInteger(), "redis.port", port);
		InetSocketAddress address = new InetSocketAddress(resolvedHost, resolvedPort);
		return RedisClient.create(reactor, address);
	}
}
