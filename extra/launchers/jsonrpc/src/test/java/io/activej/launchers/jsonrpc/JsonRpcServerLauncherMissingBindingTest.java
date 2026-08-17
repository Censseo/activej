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

package io.activej.launchers.jsonrpc;

import io.activej.inject.binding.DIException;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US1 scenario 3: a {@code JsonRpcServiceBinding} whose implementation depends on a binding nobody
 * provides. {@code testInjector()} fails naming the unsatisfied {@code Key} — and no socket is opened,
 * because nothing is ever instantiated (WI-6).
 */
public class JsonRpcServerLauncherMissingBindingTest {
	@Test
	public void missingImplementationFailsAtTestInjector() {
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi(UserApi userApi) {
						return new JsonRpcServiceBinding(UserApi.class, userApi);
					}
				};
			}
		};

		DIException e = assertThrows(DIException.class, launcher::testInjector);
		assertTrue("the missing Key must be named: " + e.getMessage(), e.getMessage().contains("UserApi"));
	}
}
