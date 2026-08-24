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
 * FR-036 fail-closed in the multi-worker launcher: the reserved non-keys are rejected in {@code onStart()} —
 * an operator of either launcher gets the loud failure, never a silent {@code ##}-marked key.
 * <p>
 * ⚠ Since feature 019 the reserved set is <b>two</b>, not four. {@code jsonrpc.callTimeout} was admitted
 * first (FR-021) and belongs to {@link JsonRpcClientModule}; {@code jsonrpc.maxInFlight} followed (FR-039)
 * and is read by <b>this</b> launcher, once per worker. {@code jsonrpc.maxBatchSize} and
 * {@code jsonrpc.maxJsonDepth} are still reserved — both are read straight off the {@code JsonRpcLimits}
 * statics with no per-instance seam — and both are asserted here so the admission cannot widen by accident.
 */
public class MultithreadedJsonRpcServerLauncherConfigRejectionTest {
	// the admitted-key case actually starts and stops a launcher, and LauncherTestHarness.stop awaits the
	// complete future on the current reactor — the two rejection cases never reach one
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@After
	public void tearDown() throws Exception {
		System.clearProperty("config.jsonrpc.maxBatchSize");
		System.clearProperty("config.jsonrpc.maxJsonDepth");
		System.clearProperty("config.jsonrpc.maxInFlight");
		LauncherTestHarness.unregisterDispatcherBeans();
	}

	@Test
	public void maxBatchSizeKeyIsRejectedNamingThePropertyThatControlsIt() {
		System.setProperty("config.jsonrpc.maxBatchSize", "10");

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher().launch(Launcher.NO_ARGS));
		assertTrue("the rejected key must be named: " + e.getMessage(), e.getMessage().contains("jsonrpc.maxBatchSize"));
		assertTrue("the controlling property must be named: " + e.getMessage(),
			e.getMessage().contains("-DJsonRpcLimits.maxBatchSize"));
	}

	@Test
	public void maxJsonDepthKeyIsRejectedNamingThePropertyThatControlsIt() {
		System.setProperty("config.jsonrpc.maxJsonDepth", "32");

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher().launch(Launcher.NO_ARGS));
		assertTrue("the rejected key must be named: " + e.getMessage(), e.getMessage().contains("jsonrpc.maxJsonDepth"));
		assertTrue("the controlling property must be named: " + e.getMessage(),
			e.getMessage().contains("-DJsonRpcLimits.maxJsonDepth"));
	}

	@Test
	public void maxInFlightKeyIsNoLongerRejected() throws Exception {
		// FR-039: admitted on BOTH launchers, not only the single-eventloop one. Here it configures each
		// worker's own dispatcher — the per-worker value is asserted by
		// JsonRpcServerLauncherConfigValuesTest#maxInFlightAppliesPerWorkerUnderTheMultithreadedLauncher
		System.setProperty("config.jsonrpc.maxInFlight", "10");

		MultithreadedJsonRpcServerLauncher launcher = launcher();
		LauncherTestHarness.launch(launcher);
		try {
			assertNotNull("the launcher started with the key present", launcher.getStartFuture());
		} finally {
			LauncherTestHarness.stop(launcher);
		}
	}

	private static MultithreadedJsonRpcServerLauncher launcher() {
		return new MultithreadedJsonRpcServerLauncher() {
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
				// the service graph has started, so the bind must not be able to fail first
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}
}
