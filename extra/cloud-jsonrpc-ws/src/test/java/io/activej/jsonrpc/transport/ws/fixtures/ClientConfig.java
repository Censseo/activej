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
 * A second, distinct client-facing interface proxiable on the same session as {@link ClientApi}
 * (FR-054): the server may {@code session.proxy(ClientApi.class)} <i>and</i>
 * {@code session.proxy(ClientConfig.class)} on one connection, each validated at first
 * {@code proxy(...)} call by the shared contract rules. Wire name {@code config.get}.
 */
@JsonRpcService("config")
public interface ClientConfig {
	/** The plain call path: one {@link String} parameter in, one {@link String} out. Wire name {@code config.get}. */
	@JsonRpcMethod("get")
	Promise<String> get(@JsonRpcParam("key") String key);
}
