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

/**
 * {@link ClientConfig}'s implementation, installed on a <b>client's</b> own {@code JsonRpcDispatcher}
 * — the second client-facing service of FR-054, so one session can carry two distinct proxied
 * interfaces and both answer.
 */
public final class ClientConfigImpl implements ClientConfig {
	@Override
	public Promise<String> get(String key) {
		return Promise.of("value-of-" + key);
	}
}
