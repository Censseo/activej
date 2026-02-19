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

public enum AiProvider {
	OPENAI("https://api.openai.com/v1", "gpt-4o"),
	ANTHROPIC("https://api.anthropic.com/v1", "claude-sonnet-4-5-20250929");

	private final String baseUrl;
	private final String defaultModel;

	AiProvider(String baseUrl, String defaultModel) {
		this.baseUrl = baseUrl;
		this.defaultModel = defaultModel;
	}

	public String baseUrl() {
		return baseUrl;
	}

	public String defaultModel() {
		return defaultModel;
	}
}
