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

package io.activej.jsonrpc.transport.ws.fixtures;

import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;

/**
 * The client-facing interface a server pushes events through (US1, FR-033): one notification under
 * the {@code userEvents} namespace, so the wire name is {@code userEvents.changed}. A browser — or
 * any peer that speaks plain JSON-RPC 2.0 over a TEXT websocket — can consume it with no ActiveJ
 * code; the session registry's broadcast is exactly this interface's proxy invoked per session.
 * <p>
 * The single method is a {@code void} notification on purpose: {@code §4.1} forbids answering a
 * notification, so a broadcast produces no response document at all — which is what the "no answer"
 * half of the US1 independent test asserts.
 */
@JsonRpcService("userEvents")
public interface UserEvents {
	/** Fired when a user's state changes. Wire name {@code userEvents.changed}. */
	@JsonRpcNotification("changed")
	void userChanged(@JsonRpcParam("id") long id);
}
