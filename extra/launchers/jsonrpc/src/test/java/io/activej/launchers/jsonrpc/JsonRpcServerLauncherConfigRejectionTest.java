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
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-036 fail-closed: a {@code -Dconfig.jsonrpc.maxBatchSize=...} must <b>fail</b> startup naming both
 * the rejected key and the controlling {@code ApplicationSettings} property — {@code ConfigModule}
 * reports unconsumed keys as {@code ##} in the dump but never fails, so the launcher's own check in
 * {@code onStart()} is what an operator's mistake hits.
 * <p>
 * ⚠ <b>The reserved set shrank twice in feature 019</b>, and is now <b>two</b> keys. {@code jsonrpc.callTimeout}
 * was admitted first (FR-021) and belongs to {@link JsonRpcClientModule}; {@code jsonrpc.maxInFlight} followed
 * (FR-039) once {@code JsonRpcDispatcher.Builder.withMaxInFlight} gave the bound a per-instance seam, and is
 * read by the server launchers themselves. {@code jsonrpc.maxBatchSize} and {@code jsonrpc.maxJsonDepth} keep
 * failing exactly as before — those two are still read straight off the {@code JsonRpcLimits} statics with no
 * per-instance seam at all. The still-rejected cases are asserted here beside the admitted ones, so that
 * widening the admission by accident is a red test rather than a silent security downgrade.
 */
public class JsonRpcServerLauncherConfigRejectionTest {
	// the two admitted-key cases actually start and stop a launcher, and LauncherTestHarness.stop awaits
	// the complete future on the current reactor — the two rejection cases never reach one
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@After
	public void tearDown() {
		System.clearProperty("config.jsonrpc.maxBatchSize");
		System.clearProperty("config.jsonrpc.maxJsonDepth");
		System.clearProperty("config.jsonrpc.maxInFlight");
		System.clearProperty("config.jsonrpc.callTimeout");
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

	@Test
	public void maxInFlightKeyIsNoLongerRejected() throws Exception {
		// FR-039: the second reservation feature 014 placed is spent too. JsonRpcDispatcher.Builder now has
		// withMaxInFlight, so this launcher reads the key instead of refusing it — and unlike callTimeout,
		// which a server-only deployment merely ignores, this one is consumed right here
		System.setProperty("config.jsonrpc.maxInFlight", "10");

		JsonRpcServerLauncher launcher = launcher();
		LauncherTestHarness.launch(launcher);
		try {
			assertNotNull("the launcher started with the key present", launcher.getStartFuture());
		} finally {
			LauncherTestHarness.stop(launcher);
		}
	}

	@Test
	public void callTimeoutKeyIsNoLongerRejected() throws Exception {
		// FR-021: the reservation feature 014 placed on this key is spent. It is now the client module's,
		// so a server-only launcher simply does not consume it — what must not happen is a startup failure
		System.setProperty("config.jsonrpc.callTimeout", "5 seconds");

		JsonRpcServerLauncher launcher = launcher();
		LauncherTestHarness.launch(launcher);
		try {
			assertNotNull("the launcher started with the key present", launcher.getStartFuture());
		} finally {
			LauncherTestHarness.stop(launcher);
		}
	}

	private static JsonRpcServerLauncher launcher() {
		return new JsonRpcServerLauncher() {
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
				// :0 keeps the test off the 8080 default — same rationale as the two cases above
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}
}
