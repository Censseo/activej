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

import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launchers.jsonrpc.JsonRpcServerLauncher;
import io.activej.launchers.jsonrpc.JsonRpcServiceBinding;

/**
 * The tutorial's launcher ({@code docs/cloud-extras/jsonrpc-tutorial.md} § The launcher) — the whole of
 * what its reader writes to deploy the service.
 * <p>
 * This class carries <b>no test affordance</b>: it is exactly what the page prints, and
 * {@code JsonRpcTutorialTest} reaches an ephemeral port by subclassing it from the launcher package,
 * where {@code JsonRpcServerLauncher.config()} is visible.
 */
public class GreetingServerLauncher extends JsonRpcServerLauncher {
	@Override
	protected Module getBusinessLogicModule() {
		return new AbstractModule() {
			@ProvidesIntoSet
			JsonRpcServiceBinding greetingApi() {
				return new JsonRpcServiceBinding(GreetingApi.class, new GreetingApiImpl());
			}
		};
	}

	public static void main(String[] args) throws Exception {
		new GreetingServerLauncher().launch(args);
	}
}
