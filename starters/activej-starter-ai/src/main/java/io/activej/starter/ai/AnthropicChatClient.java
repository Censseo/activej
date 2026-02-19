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

import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.IHttpClient;
import io.activej.promise.Promise;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.activej.starter.ai.OpenAiChatClient.*;

/**
 * Anthropic-compatible {@link ChatClient} implementation.
 * <p>
 * Sends POST requests to {@code {baseUrl}/messages} using {@code x-api-key} header
 * authentication and {@code anthropic-version: 2023-06-01} header.
 * Builds JSON manually without external dependencies.
 */
public final class AnthropicChatClient implements ChatClient {
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private final IHttpClient httpClient;
	private final String apiKey;
	private final String baseUrl;
	private final String defaultModel;
	private final double defaultTemperature;

	AnthropicChatClient(IHttpClient httpClient, String apiKey, String baseUrl, String defaultModel, double defaultTemperature) {
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
				HttpRequest.post(baseUrl + "/messages")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withHeader(HttpHeaders.of("x-api-key"), apiKey)
					.withHeader(HttpHeaders.of("anthropic-version"), ANTHROPIC_VERSION)
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

		List<ChatEvent> events = new ArrayList<>();
		Promise<Void> requestPromise = httpClient.request(
				HttpRequest.post(baseUrl + "/messages")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withHeader(HttpHeaders.of("x-api-key"), apiKey)
					.withHeader(HttpHeaders.of("anthropic-version"), ANTHROPIC_VERSION)
					.withBody(jsonBody)
					.build())
			.then(response -> response.loadBody()
				.map(body -> {
					String responseBody = body.asString(StandardCharsets.UTF_8);
					parseStreamEvents(responseBody, events);
					return (Void) null;
				}));

		return ChannelSuppliers.ofValues(ChatEvent.of(""), ChatEvent.done());
	}

	private String buildRequestJson(String model, List<ChatMessage> messages, boolean stream, double temperature) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"model\":\"").append(escapeJson(model)).append("\"");
		sb.append(",\"max_tokens\":4096");

		// Anthropic treats system messages separately
		String systemMessage = null;
		List<ChatMessage> nonSystemMessages = new ArrayList<>();
		for (ChatMessage msg : messages) {
			if ("system".equals(msg.role())) {
				systemMessage = msg.content();
			} else {
				nonSystemMessages.add(msg);
			}
		}

		if (systemMessage != null) {
			sb.append(",\"system\":\"").append(escapeJson(systemMessage)).append("\"");
		}

		sb.append(",\"messages\":[");
		for (int i = 0; i < nonSystemMessages.size(); i++) {
			if (i > 0) sb.append(",");
			ChatMessage msg = nonSystemMessages.get(i);
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
		String content = extractAnthropicContent(json);

		// Anthropic uses "input_tokens" and "output_tokens"
		int inputTokens = extractJsonInt(json, "input_tokens");
		int outputTokens = extractJsonInt(json, "output_tokens");
		return new ChatResponse(id, model, content, new ChatResponse.Usage(inputTokens, outputTokens, inputTokens + outputTokens));
	}

	private String extractAnthropicContent(String json) {
		// Anthropic returns content as an array: "content": [{"type": "text", "text": "..."}]
		int contentIdx = json.indexOf("\"content\"");
		if (contentIdx < 0) return "";
		int arrayStart = json.indexOf('[', contentIdx);
		if (arrayStart < 0) return "";
		int textIdx = json.indexOf("\"text\"", arrayStart);
		if (textIdx < 0) return "";
		// Find the second "text" which is the actual content value (first is the type value)
		int typeIdx = json.indexOf("\"type\"", arrayStart);
		if (typeIdx >= 0 && typeIdx < textIdx) {
			// Skip the "type":"text" pair, find the actual "text" key with value
			int nextTextIdx = json.indexOf("\"text\"", textIdx + 1);
			if (nextTextIdx < 0) {
				// Only one "text" key - this is the content value
				return extractValueAfterKey(json, textIdx);
			}
			return extractValueAfterKey(json, nextTextIdx);
		}
		return extractValueAfterKey(json, textIdx);
	}

	private String extractValueAfterKey(String json, int keyIdx) {
		int colonIdx = json.indexOf(':', keyIdx);
		if (colonIdx < 0) return "";
		int start = json.indexOf('"', colonIdx + 1);
		if (start < 0) return "";
		start++;
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '\\' && i + 1 < json.length()) {
				char next = json.charAt(i + 1);
				switch (next) {
					case '"' -> { sb.append('"'); i++; }
					case '\\' -> { sb.append('\\'); i++; }
					case 'n' -> { sb.append('\n'); i++; }
					case 't' -> { sb.append('\t'); i++; }
					case 'r' -> { sb.append('\r'); i++; }
					default -> sb.append(c);
				}
			} else if (c == '"') {
				break;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private void parseStreamEvents(String responseBody, List<ChatEvent> events) {
		String[] lines = responseBody.split("\n");
		for (String line : lines) {
			if (line.startsWith("data: ")) {
				String data = line.substring(6);
				String type = extractJsonString(data, "type");
				if ("content_block_delta".equals(type)) {
					String delta = extractDeltaText(data);
					if (delta != null && !delta.isEmpty()) {
						events.add(ChatEvent.of(delta));
					}
				} else if ("message_stop".equals(type)) {
					events.add(ChatEvent.done());
				}
			}
		}
		if (events.isEmpty() || !events.get(events.size() - 1).done()) {
			events.add(ChatEvent.done());
		}
	}

	private String extractDeltaText(String json) {
		int deltaIdx = json.indexOf("\"delta\"");
		if (deltaIdx < 0) return null;
		int textIdx = json.indexOf("\"text\"", deltaIdx);
		if (textIdx < 0) return null;
		return extractValueAfterKey(json, textIdx);
	}
}
