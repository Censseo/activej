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

import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.DIException;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.BrokenApis;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * US1 scenario 4: a contract-violating interface fails startup with a {@link JsonRpcContractException}
 * naming <b>every</b> violation at once — before any port is bound, because the dispatcher is built
 * (and validated) during graph instantiation.
 */
public class JsonRpcServerLauncherContractViolationTest {
	@Test
	public void contractViolationFailsBeforeAnyPortIsBound() {
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding brokenApi() {
						// the interface is deliberately broken; the implementation is never reached
						BrokenApis.ManyViolations implementation = (BrokenApis.ManyViolations) Proxy.newProxyInstance(
							getClass().getClassLoader(),
							new Class<?>[]{BrokenApis.ManyViolations.class},
							(proxy, method, args) -> null);
						return new JsonRpcServiceBinding(BrokenApis.ManyViolations.class, implementation);
					}
				};
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				// FR-057
			}
		};

		// the provider's JsonRpcContractException surfaces wrapped by the DI machinery
		DIException wrapper = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		JsonRpcContractException e = (JsonRpcContractException) wrapper.getCause();

		// every violation named at once: the void call, the value-returning notification,
		// the unresolvable parameter type, and the duplicate wire name
		assertEquals(4, e.violations().size());
		String message = e.getMessage();
		for (String violation : new String[]{"nothing", "touch", "weird", "alpha"}) {
			assertEquals("violation " + violation + " must be named", true, message.contains(violation));
		}

		// instantiation failed — the @Inject field was never assigned, so nothing ever bound
		assertNull(launcher.httpServer);
	}
}
