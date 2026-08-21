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
 * The client-facing interface a server initiates calls on (US4, FR-054): {@code session.proxy(
 * ClientApi.class)} is the whole server→client request path, installed on the client's own
 * {@code JsonRpcDispatcher} as a {@code withService} entry. Wire names {@code client.decide},
 * {@code client.fail} and {@code client.event}.
 * <p>
 * This is the mirror of {@link TestApi}: that interface models the direction a <i>client</i>
 * initiates against the server's dispatcher, this one the direction the <i>server</i> initiates
 * against the client's dispatcher. {@link #decide(int)} is the value-returning method of the
 * server-initiated matrix; {@link #fail()} is the application-error method, whose implementation
 * answers with a {@code JsonRpcException} so the code/message/{@code data} round-trip verbatim;
 * {@link #event(long)} is the notification a broadcast fans out, producing no response document at
 * all.
 */
@JsonRpcService("client")
public interface ClientApi {
	/** The plain call path: one parameter in, one {@link String} out. Wire name {@code client.decide}. */
	@JsonRpcMethod("decide")
	Promise<String> decide(@JsonRpcParam("n") int n);

	/**
	 * The application-error path: the implementation fails with a {@code JsonRpcException}. Wire name
	 * {@code client.fail}.
	 */
	@JsonRpcMethod("fail")
	Promise<String> fail();

	/** The broadcast path: a notification, so no response document is produced. Wire name {@code client.event}. */
	@JsonRpcNotification("event")
	void event(@JsonRpcParam("id") long id);
}
