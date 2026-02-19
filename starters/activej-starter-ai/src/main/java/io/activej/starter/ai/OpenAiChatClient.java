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

		List<ChatEvent> events = new ArrayList<>();
		Promise<Void> requestPromise = httpClient.request(
				HttpRequest.post(baseUrl + "/chat/completions")
					.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
					.withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
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

	private void parseStreamEvents(String responseBody, List<ChatEvent> events) {
		String[] lines = responseBody.split("\n");
		for (String line : lines) {
			if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
				String data = line.substring(6);
				String delta = extractDeltaContent(data);
				if (delta != null && !delta.isEmpty()) {
					events.add(ChatEvent.of(delta));
				}
			}
		}
		events.add(ChatEvent.done());
	}

	private String extractNestedContent(String json) {
		// Extract "content" from the first choice's message
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

	static String extractJsonString(String json, String key) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx < 0) return "";
		int colonIdx = json.indexOf(':', idx + search.length());
		if (colonIdx < 0) return "";
		int start = json.indexOf('"', colonIdx + 1);
		if (start < 0) return "";
		start++;
		int end = json.indexOf('"', start);
		if (end < 0) return "";
		return json.substring(start, end);
	}

	static int extractJsonInt(String json, String key) {
		String search = "\"" + key + "\"";
		int idx = json.indexOf(search);
		if (idx < 0) return 0;
		int colonIdx = json.indexOf(':', idx + search.length());
		if (colonIdx < 0) return 0;
		int start = colonIdx + 1;
		while (start < json.length() && json.charAt(start) == ' ') start++;
		int end = start;
		while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
		if (start == end) return 0;
		try {
			return Integer.parseInt(json.substring(start, end));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String escapeJson(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}
}
