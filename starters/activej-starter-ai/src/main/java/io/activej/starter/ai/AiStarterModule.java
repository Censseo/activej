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
import io.activej.config.ConfigModule;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.http.IHttpClient;
import io.activej.service.ServiceGraphModule;

/**
 * A composable starter module for AI chat clients.
 * <p>
 * Supports OpenAI and Anthropic providers with configurable model, base URL,
 * and temperature settings.
 * <p>
 * Default configuration: OpenAI with gpt-4o model.
 */
public final class AiStarterModule extends AbstractModule {
	private String apiKey = "";
	private AiProvider provider = AiProvider.OPENAI;
	private String model;
	private String baseUrl;
	private double defaultTemperature = 0.7;

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
	}

	@Provides
	ChatClient chatClient(IHttpClient httpClient) {
		String effectiveBaseUrl = baseUrl != null ? baseUrl : provider.baseUrl();
		String effectiveModel = model != null ? model : provider.defaultModel();

		return switch (provider) {
			case OPENAI -> new OpenAiChatClient(httpClient, apiKey, effectiveBaseUrl, effectiveModel, defaultTemperature);
			case ANTHROPIC -> new AnthropicChatClient(httpClient, apiKey, effectiveBaseUrl, effectiveModel, defaultTemperature);
		};
	}
}
