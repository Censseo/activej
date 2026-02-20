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

package io.activej.starter.ai;

import io.activej.common.builder.AbstractBuilder;
import io.activej.config.Config;
import io.activej.config.ConfigModule;
import io.activej.dns.DnsClient;
import io.activej.dns.IDnsClient;
import io.activej.http.HttpClient;
import io.activej.http.IHttpClient;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraphModule;
import io.activej.starter.ReactorModule;

import javax.net.ssl.SSLContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.config.Config.ofClassPathProperties;
import static io.activej.config.Config.ofSystemProperties;
import static io.activej.config.converter.ConfigConverters.*;

/**
 * A composable starter module for AI chat clients.
 * <p>
 * Supports OpenAI and Anthropic providers with configurable model, base URL,
 * and temperature settings. Configuration can be overridden via a properties file
 * ({@code ai-client.properties} by default) or system properties prefixed with
 * {@code config.ai.}.
 * <p>
 * Default configuration: OpenAI with gpt-4o model.
 */
public final class AiStarterModule extends AbstractModule {
	public static final String DEFAULT_PROPERTIES_FILE = "ai-client.properties";

	private String apiKey = "";
	private AiProvider provider = AiProvider.OPENAI;
	private String model;
	private String baseUrl;
	private double defaultTemperature = 0.7;
	private String propertiesFile = DEFAULT_PROPERTIES_FILE;

	private AiStarterModule() {
	}

	public static AiStarterModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new AiStarterModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, AiStarterModule> {
		private Builder() {}

		public Builder withOpenAi(String apiKey) {
			checkNotBuilt(this);
			AiStarterModule.this.provider = AiProvider.OPENAI;
			AiStarterModule.this.apiKey = apiKey;
			return this;
		}

		public Builder withAnthropic(String apiKey) {
			checkNotBuilt(this);
			AiStarterModule.this.provider = AiProvider.ANTHROPIC;
			AiStarterModule.this.apiKey = apiKey;
			return this;
		}

		public Builder withModel(String model) {
			checkNotBuilt(this);
			AiStarterModule.this.model = model;
			return this;
		}

		public Builder withBaseUrl(String url) {
			checkNotBuilt(this);
			AiStarterModule.this.baseUrl = url;
			return this;
		}

		public Builder withTemperature(double temperature) {
			checkNotBuilt(this);
			AiStarterModule.this.defaultTemperature = temperature;
			return this;
		}

		public Builder withPropertiesFile(String propertiesFile) {
			checkNotBuilt(this);
			AiStarterModule.this.propertiesFile = propertiesFile;
			return this;
		}

		@Override
		protected AiStarterModule doBuild() {
			return AiStarterModule.this;
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
		Config config = Config.create()
			.with("ai.provider", provider.name())
			.with("ai.apiKey", apiKey)
			.with("ai.model", model != null ? model : provider.defaultModel())
			.with("ai.baseUrl", baseUrl != null ? baseUrl : provider.baseUrl())
			.with("ai.temperature", String.valueOf(defaultTemperature));
		return config
			.overrideWith(ofClassPathProperties(propertiesFile, true))
			.overrideWith(ofSystemProperties("config"));
	}

	@Provides
	IDnsClient dnsClient(NioReactor reactor) throws UnknownHostException {
		return DnsClient.create(reactor, InetAddress.getByName("8.8.8.8"));
	}

	@Provides
	IHttpClient httpClient(NioReactor reactor, IDnsClient dnsClient) throws NoSuchAlgorithmException {
		Executor sslExecutor = Executors.newCachedThreadPool();
		return HttpClient.builder(reactor, dnsClient)
			.withSslEnabled(SSLContext.getDefault(), sslExecutor)
			.build();
	}

	@Provides
	ChatClient chatClient(IHttpClient httpClient, Config config) {
		AiProvider resolvedProvider = config.get(ofEnum(AiProvider.class), "ai.provider", provider);
		String resolvedApiKey = config.get(ofString(), "ai.apiKey", apiKey);
		String resolvedModel = config.get(ofString(), "ai.model", resolvedProvider.defaultModel());
		String resolvedBaseUrl = config.get(ofString(), "ai.baseUrl", resolvedProvider.baseUrl());
		double resolvedTemperature = config.get(ofDouble(), "ai.temperature", defaultTemperature);

		return switch (resolvedProvider) {
			case OPENAI -> new OpenAiChatClient(httpClient, resolvedApiKey, resolvedBaseUrl, resolvedModel, resolvedTemperature);
			case ANTHROPIC -> new AnthropicChatClient(httpClient, resolvedApiKey, resolvedBaseUrl, resolvedModel, resolvedTemperature);
		};
	}
}
