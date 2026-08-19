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

import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;

/**
 * {@link HangApi}'s implementation for the US3 purge tests: <b>never answers</b>. A call routed to
 * this implementation produces a request document on the wire whose response never comes, which is
 * what leaves the caller's {@code Promise} in flight until the connection drop purges it (T013,
 * FR-094). The returned {@link SettablePromise} is not tracked by {@code ActivePromisesRule}, so it
 * stays pending without failing the leak rules; the only thing that settles it would be the drop,
 * and the drop settles the whole matrix.
 */
public final class HangApiImpl implements HangApi {
	@Override
	public Promise<String> request(int n) {
		return new SettablePromise<>();   // never completes — the purge matrix's pending call
	}
}
