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

package io.activej.jsonrpc.service.fixtures;

import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.promise.Promise;

/**
 * The README's two failure shapes in one method, so the difference between them is the only thing the
 * example varies.
 */
public final class PaymentsApiImpl implements PaymentsApi {
	@Override
	public Promise<String> charge(int amount) {
		if (amount > 100) {
			// deliberate: this error object IS the contract, and it reaches the caller verbatim. The
			// exception's own message stays local — it is never copied into the error object
			return Promise.ofException(new JsonRpcException(
				JsonRpcErrors.of(429, "Too many requests"), "tenant 17 tripped the rate limiter"));
		}
		// accidental: whatever this says, the peer is told -32603 Internal error and nothing else
		throw new IllegalStateException("db password is hunter2");
	}
}
