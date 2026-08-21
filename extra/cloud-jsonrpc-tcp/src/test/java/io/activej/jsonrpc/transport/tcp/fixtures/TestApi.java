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

package io.activej.jsonrpc.transport.tcp.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The server-side service a session's {@code JsonRpcDispatcher} answers (FR-030): the server's
 * dispatcher is the single service table every session dispatches <i>inbound</i> calls to, and this
 * is the table's entry in this module's tests. Wire names {@code test.add} and {@code test.note}.
 * {@link AddResult} is a {@code record}, so {@code JsonCodecFactory} derives its codec with no
 * registration.
 * <p>
 * Mirrors the WebSocket module's fixture of the same name, with one addition this transport's US1
 * needs: {@link #note(String)} is a <b>notification</b>, so §4.1 forbids answering it and the wire
 * carries <i>no bytes at all</i> in return — which is exactly what the US1 "a notification produces
 * zero bytes" scenario asserts on a persistent connection, where "no answer" is observable as
 * silence rather than as a status code.
 * <p>
 * The client-facing mirror is {@link ClientApi} — that interface models the direction the
 * <i>server</i> initiates against a client's dispatcher.
 */
@JsonRpcService("test")
public interface TestApi {
	/** The plain call path: two named parameters in, one record out. Wire name {@code test.add}. */
	@JsonRpcMethod("add")
	Promise<AddResult> add(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b);

	/**
	 * The no-answer path: a notification the server records and never responds to. Wire name
	 * {@code test.note}.
	 */
	@JsonRpcNotification("note")
	void note(@JsonRpcParam("text") String text);

	/** The record result of {@link #add}; codec-derived, nothing registered. */
	record AddResult(int sum) {}
}
