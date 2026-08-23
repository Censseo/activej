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

package io.activej.launchers.jsonrpc.tutorial;

import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;

/**
 * The tutorial's service interface ({@code docs/cloud-extras/jsonrpc-tutorial.md} § Two types and an
 * interface) — the whole of what its reader writes to define a JSON-RPC surface.
 * <p>
 * Wire names are {@code greeting.hello} and {@code greeting.seen}. Every annotation carries an explicit
 * value: an empty one falls back to the Java method identifier, so a rename would silently change the
 * wire name (DI-6/DI-7).
 */
@JsonRpcService("greeting")
public interface GreetingApi {
	@JsonRpcMethod("hello")
	Promise<Greeting> hello(@JsonRpcParam("name") String name);

	@JsonRpcNotification("seen")
	void seen(@JsonRpcParam("name") String name);
}
