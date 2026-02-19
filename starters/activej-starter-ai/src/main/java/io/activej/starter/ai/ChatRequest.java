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

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, boolean stream, double temperature) {
	public static ChatRequest of(String userMessage) {
		return new ChatRequest(null, List.of(ChatMessage.user(userMessage)), false, 0.7);
	}

	public static ChatRequest of(List<ChatMessage> messages) {
		return new ChatRequest(null, messages, false, 0.7);
	}

	public ChatRequest withModel(String model) {
		return new ChatRequest(model, messages, stream, temperature);
	}

	public ChatRequest withStream() {
		return new ChatRequest(model, messages, true, temperature);
	}

	public ChatRequest withTemperature(double temperature) {
		return new ChatRequest(model, messages, stream, temperature);
	}
}
