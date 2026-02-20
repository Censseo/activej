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

import io.activej.csp.binary.BinaryChannelSupplier;
import io.activej.csp.binary.decoder.impl.OfByteTerminated;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.IHttpClient;
import io.activej.promise.Promise;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.activej.starter.ai.JsonUtils.*;

/**
 * OpenAI-compatible {@link ChatClient} implementation.
 * <p>
 * Sends POST requests to {@code {baseUrl}/chat/completions} using Bearer token
 * authentication. Builds JSON manually without external dependencies.
 */
public final class OpenAiChatClient implements ChatClient {
	private final IHttpClient httpClient;
	private final String apiKey;
	private final String baseUrl;
	private final String defaultModel;
	private final double defaultTemperature;

	OpenAiChatClient(IHttpClient httpClient, String apiKey, String baseUrl, String defaultModel, double defaultTemperature) {
		this.httpClient = httpClient;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.defaultModel = defaultModel;
		this.defaultTemperature = defaultTemperature;
	}

	@Override
	public Promise<ChatResponse> chat(ChatRequest request) {
		String model = request.model() != null ? request.model() : defaultModel;
		double temperature = request.temperature() != 0.0 ? request.temperature() : defaultTemperature;
		String jsonBody = buildRequestJson(model, request.messages(), false, temperature);

		return httpClient.request(
				HttpRequest.post(baseUrl + "/chat/completions")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
					.withBody(jsonBody)
					.build())
			.then(response -> response.loadBody()
				.map(body -> parseResponse(body.asString(StandardCharsets.UTF_8))));
	}

	@Override
	public ChannelSupplier<ChatEvent> chatStream(ChatRequest request) {
		String model = request.model() != null ? request.model() : defaultModel;
		double temperature = request.temperature() != 0.0 ? request.temperature() : defaultTemperature;
		String jsonBody = buildRequestJson(model, request.messages(), true, temperature);

		Promise<ChannelSupplier<ChatEvent>> promise = httpClient.request(
				HttpRequest.post(baseUrl + "/chat/completions")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
					.withBody(jsonBody)
					.build())
			.map(response -> {
				ChannelSupplier<String> lines = BinaryChannelSupplier.of(response.takeBodyStream())
					.decodeStream(new OfByteTerminated((byte) '\n', 64 * 1024))
					.map(buf -> buf.asString(StandardCharsets.UTF_8));

				return lines
					.filter(line -> line.startsWith("data: "))
					.map(line -> {
						String data = line.substring(6).trim();
						if ("[DONE]".equals(data)) return null;
						String delta = extractDeltaContent(data);
						return ChatEvent.of(delta != null ? delta : "");
					})
					.filter(event -> !event.delta().isEmpty());
			});

		return ChannelSuppliers.ofPromise(promise);
	}

	private String buildRequestJson(String model, List<ChatMessage> messages, boolean stream, double temperature) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"model\":\"").append(escapeJson(model)).append("\"");
		sb.append(",\"messages\":[");
		for (int i = 0; i < messages.size(); i++) {
			if (i > 0) sb.append(",");
			ChatMessage msg = messages.get(i);
			sb.append("{\"role\":\"").append(escapeJson(msg.role())).append("\"");
			sb.append(",\"content\":\"").append(escapeJson(msg.content())).append("\"}");
		}
		sb.append("]");
		sb.append(",\"temperature\":").append(temperature);
		if (stream) {
			sb.append(",\"stream\":true");
		}
		sb.append("}");
		return sb.toString();
	}

	private ChatResponse parseResponse(String json) {
		String id = extractJsonString(json, "id");
		String model = extractJsonString(json, "model");
		String content = extractNestedContent(json);
		int promptTokens = extractJsonInt(json, "prompt_tokens");
		int completionTokens = extractJsonInt(json, "completion_tokens");
		int totalTokens = extractJsonInt(json, "total_tokens");
		return new ChatResponse(id, model, content, new ChatResponse.Usage(promptTokens, completionTokens, totalTokens));
	}

	private String extractNestedContent(String json) {
		int choicesIdx = json.indexOf("\"choices\"");
		if (choicesIdx < 0) return "";
		int messageIdx = json.indexOf("\"message\"", choicesIdx);
		if (messageIdx < 0) return "";
		int contentIdx = json.indexOf("\"content\"", messageIdx);
		if (contentIdx < 0) return "";
		return extractValueAfterKey(json, contentIdx);
	}

	private String extractDeltaContent(String json) {
		int deltaIdx = json.indexOf("\"delta\"");
		if (deltaIdx < 0) return null;
		int contentIdx = json.indexOf("\"content\"", deltaIdx);
		if (contentIdx < 0) return null;
		return extractValueAfterKey(json, contentIdx);
	}
}
