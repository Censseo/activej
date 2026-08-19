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

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The server-side service a session's {@code JsonRpcDispatcher} answers (FR-030): the servlet's
 * dispatcher is the single service table every session dispatches <i>inbound</i> calls to, and this
 * is the table's one entry in the session tests. Wire name {@code test.add}. {@link AddResult} is a
 * {@code record}, so {@code JsonCodecFactory} derives its codec with no registration.
 * <p>
 * The client-facing half of the session tests is {@link UserEvents} — this interface models the
 * direction a <i>client</i> initiates, kept in the fixtures so the dispatcher is a real table rather
 * than an empty one.
 */
@JsonRpcService("test")
public interface TestApi {
	/** The plain call path: two named parameters in, one record out. Wire name {@code test.add}. */
	@JsonRpcMethod("add")
	Promise<AddResult> add(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b);

	/** The record result of {@link #add}; codec-derived, nothing registered. */
	record AddResult(int sum) {}
}
