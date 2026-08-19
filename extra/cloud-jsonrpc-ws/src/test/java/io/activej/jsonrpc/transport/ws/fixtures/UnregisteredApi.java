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
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * A client-facing interface that is <b>never</b> registered on the client's dispatcher (US2
 * scenario 3): the client answers {@code -32601 Method not found} from its default peer-handler
 * behaviour for any request whose wire name is in no registered service. Calling this through a
 * session's proxy therefore fails the server's {@code Promise} with that error — the honest runtime
 * signal for "this client exposes no such service".
 * <p>
 * Deliberately no implementation: registering one would defeat the scenario it exists to pin.
 */
@JsonRpcService("unregistered")
public interface UnregisteredApi {
	/** The call a service-less client cannot answer. Wire name {@code unregistered.ping}. */
	@JsonRpcMethod("ping")
	Promise<String> ping();
}
