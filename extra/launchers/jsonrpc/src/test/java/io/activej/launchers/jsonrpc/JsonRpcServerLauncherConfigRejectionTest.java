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

import io.activej.config.Config;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-036 fail-closed: a {@code -Dconfig.jsonrpc.maxBatchSize=...} must <b>fail</b> startup naming both
 * the rejected key and the controlling {@code ApplicationSettings} property — {@code ConfigModule}
 * reports unconsumed keys as {@code ##} in the dump but never fails, so the launcher's own check in
 * {@code onStart()} is what an operator's mistake hits.
 */
public class JsonRpcServerLauncherConfigRejectionTest {
	@After
	public void tearDown() {
		System.clearProperty("config.jsonrpc.maxBatchSize");
		System.clearProperty("config.jsonrpc.maxJsonDepth");
	}

	@Test
	public void maxBatchSizeKeyIsRejectedNamingThePropertyThatControlsIt() {
		System.setProperty("config.jsonrpc.maxBatchSize", "10");

		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}
				};
			}

			@Override
			Config config() {
				// :0 keeps the test off the 8080 default — the rejection in onStart() runs only after
				// the service graph has started (Launcher.launch starts services before onStart), so
				// the bind must not be able to fail first (same pattern as PropertiesRejectionTest)
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("the rejected key must be named: " + e.getMessage(), e.getMessage().contains("jsonrpc.maxBatchSize"));
		assertTrue("the controlling property must be named: " + e.getMessage(),
			e.getMessage().contains("-DJsonRpcLimits.maxBatchSize"));
	}

	@Test
	public void maxJsonDepthKeyIsRejectedNamingThePropertyThatControlsIt() {
		System.setProperty("config.jsonrpc.maxJsonDepth", "32");

		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}
				};
			}

			@Override
			Config config() {
				// :0 keeps the test off the 8080 default — same rationale as maxBatchSizeKeyIsRejected...
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher.launch(Launcher.NO_ARGS));
		assertTrue(e.getMessage().contains("jsonrpc.maxJsonDepth"));
		assertTrue(e.getMessage().contains("-DJsonRpcLimits.maxJsonDepth"));
	}
}
