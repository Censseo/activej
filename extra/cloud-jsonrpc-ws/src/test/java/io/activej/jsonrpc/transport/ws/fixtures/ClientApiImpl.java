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

import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.promise.Promise;

/**
 * {@link ClientApi}'s implementation, installed on a <b>client's</b> own {@code JsonRpcDispatcher}
 * — the client side of a server-initiated call. {@link #decide(int)} answers with a value, so the
 * server's {@code Promise} completes with it (US2 scenario 1); {@link #fail()} fails with an
 * application {@code JsonRpcException} carrying a custom code, message and {@code data}, so the
 * server-side caller sees them verbatim (US2 scenario 4, FR-072-analog).
 * <p>
 * Runs on the client's reactor thread (the dispatcher invokes it); stateless, no synchronization
 * needed.
 */
public final class ClientApiImpl implements ClientApi {
	@Override
	public Promise<String> decide(int n) {
		return Promise.of("decided-" + n);
	}

	@Override
	public Promise<String> fail() {
		return Promise.ofException(new JsonRpcException(JsonRpcErrors.of(42, "the-client-said-no",
			JsonRpcPayload.encoded(JsonCodecs.ofString(), "detail"))));
	}
}
