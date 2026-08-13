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
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The specification's worked example: one call and one notification under the {@code user} namespace, so the
 * wire names are {@code user.get} and {@code user.touch}.
 */
@JsonRpcService("user")
public interface UserApi {
	/** The identified call. */
	@JsonRpcMethod("get")
	Promise<User> getUser(@JsonRpcParam("id") long id);

	/** The notification — no response element is ever produced for it. */
	@JsonRpcNotification("touch")
	void touch(@JsonRpcParam("id") long id);
}
