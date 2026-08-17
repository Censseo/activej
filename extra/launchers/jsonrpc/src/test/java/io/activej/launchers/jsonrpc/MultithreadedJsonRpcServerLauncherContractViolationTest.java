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
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.BrokenApis;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * US4 scenario 3 (FR-072): a contract violation aborts startup <b>once</b> — the first worker's
 * dispatcher build fails and the graph rolls back; the failure is never multiplied per worker.
 */
public class MultithreadedJsonRpcServerLauncherContractViolationTest {
	@Test
	public void contractViolationAbortsStartupOnce() {
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding brokenApi() {
						BrokenApis.ManyViolations implementation = (BrokenApis.ManyViolations) Proxy.newProxyInstance(
							getClass().getClassLoader(),
							new Class<?>[]{BrokenApis.ManyViolations.class},
							(proxy, method, args) -> null);
						return new JsonRpcServiceBinding(BrokenApis.ManyViolations.class, implementation);
					}
				};
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		// one exception out of launch — the workers never start, the graph rolls back
		DIException wrapper = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		JsonRpcContractException e = (JsonRpcContractException) wrapper.getCause();
		assertNotNull(e);
		assertEquals(4, e.violations().size());
		assertTrue(e.getMessage().contains("alpha"));

		// nothing ever bound: the primary server was never created
		assertTrue("primaryServer must not exist", launcher.primaryServer == null);
	}
}
