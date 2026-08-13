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

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The README's error example: one method that fails deliberately and accidentally, so the two shapes can be
 * shown side by side without the nine methods {@link FailingApi} needs to cover every row of the Error
 * Scenarios table.
 */
@JsonRpcService("payments")
public interface PaymentsApi {
	/** Fails deliberately above 100 and accidentally below it — see {@link PaymentsApiImpl}. */
	@JsonRpcMethod("charge")
	Promise<String> charge(@JsonRpcParam("amount") int amount);
}
