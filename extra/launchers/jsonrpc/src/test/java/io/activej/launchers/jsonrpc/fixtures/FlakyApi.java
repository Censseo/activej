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

package io.activej.launchers.jsonrpc.fixtures;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The fixture service for the multi-worker runtime-failure test: {@code flaky.get} succeeds for most
 * ids and fails for {@link #FAILED_ID} — the dispatcher answers {@code -32603} for it (feature 013's
 * documented mapping of a throwing implementation).
 */
@JsonRpcService("flaky")
public interface FlakyApi {
	long FAILED_ID = 13;

	@JsonRpcMethod("get")
	Promise<User> get(@JsonRpcParam("id") long id);
}
