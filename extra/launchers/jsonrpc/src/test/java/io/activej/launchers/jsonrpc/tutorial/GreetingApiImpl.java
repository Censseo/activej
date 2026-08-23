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

import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.promise.Promise;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The tutorial's implementation ({@code docs/cloud-extras/jsonrpc-tutorial.md} § Two types and an
 * interface).
 * <p>
 * The {@code seen} set needs no synchronization: one dispatcher, one reactor, one thread — every call
 * into this object arrives on the same reactor thread. That is also why a blocking call here would stall
 * every other client, and why real work belongs behind {@code Promise.ofBlocking(executor, …)}.
 */
public class GreetingApiImpl implements GreetingApi {
	private final Set<String> seen = new LinkedHashSet<>();

	@Override
	public Promise<Greeting> hello(String name) {
		if (name.isBlank()) {
			return Promise.ofException(new JsonRpcException(JsonRpcErrors.of(1001, "Name must not be blank")));
		}
		return Promise.of(new Greeting("Hello, " + name + "!", "en"));
	}

	@Override
	public void seen(String name) {
		seen.add(name);
	}
}
