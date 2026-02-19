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

package io.activej.starter.websocket;

import io.activej.common.MemSize;
import io.activej.config.Config;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;

import static io.activej.config.converter.ConfigConverters.ofMemSize;

/**
 * Starter module for WebSocket configuration.
 * <p>
 * Provides sensible defaults for WebSocket settings read from {@link Config}.
 * Configuration properties:
 * <ul>
 *   <li>{@code "websocket.maxMessageSize"} - maximum WebSocket message size
 *       (default: {@code 1MB})</li>
 * </ul>
 * <p>
 * This module is intended to be combined with an HTTP starter module.
 * It provides a {@link MemSize} binding for the max WebSocket message size
 * that can be consumed by HTTP server/client configuration.
 *
 * <pre>{@code
 * Module module = WebSocketStarterModule.create();
 * }</pre>
 */
public final class WebSocketStarterModule extends AbstractModule {
	public static final MemSize DEFAULT_MAX_MESSAGE_SIZE = MemSize.megabytes(1);

	private WebSocketStarterModule() {
	}

	public static WebSocketStarterModule create() {
		return new WebSocketStarterModule();
	}

	@Provides
	MemSize maxWebSocketMessageSize(Config config) {
		return config.get(ofMemSize(), "websocket.maxMessageSize", DEFAULT_MAX_MESSAGE_SIZE);
	}
}
