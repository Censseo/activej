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
import io.activej.test.TestUtils;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.config.converter.ConfigConverters.ofInteger;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.assertNothingListens;
import static io.activej.promise.Promise.ofCompletionStage;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * D7 + D8 of the adversarial plan: the config window is a contract (FR-035), and the lifecycle must
 * be single-start with a clean stop on every path.
 * <p>
 * The one assertion written against the <b>oracle</b> that the code does not yet meet —
 * {@link #launchingTwiceMustNotStartTheApplicationTwice()} — is the D8-1 finding.
 */
public class JsonRpcServerLauncherLifecycleTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static JsonRpcServerLauncher singleLauncher(Config overrides) {
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
				// the captured parameter is named 'overrides' — 'config' would resolve to the
				// launcher's own @Inject field, which is null while this provider is running
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	// ---------------------------------------------------------------------------------------------
	// D7 — the config window
	// ---------------------------------------------------------------------------------------------

	@Test
	public void readingConfigInsideRunThrowsIllegalStateException() {
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
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}

			@Override
			protected void run() throws Exception {
				// the hostile subclass: run() must NOT be able to read Config — the window closed at @OnStart
				config.get(ofInteger(), "emptyResponseCode", 204);
			}
		};

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("the ProtectedConfig message must name the closed window: " + e.getMessage(),
			e.getMessage().contains("Config must be used during application start-up time only"));
	}

	// ---------------------------------------------------------------------------------------------
	// D8 — lifecycle
	// ---------------------------------------------------------------------------------------------
	@Test
	public void launchingTwiceMustNotStartTheApplicationTwice() throws Exception {
		AtomicInteger runCount = new AtomicInteger();
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
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}

			@Override
			protected void run() throws Exception {
				runCount.incrementAndGet();
				super.run();
			}
		};
		launchAndAwaitStart(launcher);
		// run() is entered right after the start future completes — allow the launch thread a moment
		// (100 x 50ms; a loaded surefire JVM can stall the launch thread past 2.5s)
		for (int attempt = 0; attempt < 100 && runCount.get() == 0; attempt++) {
			Thread.sleep(50);
		}
		assertEquals("run() must have been entered exactly once by the first launch", 1, runCount.get());

		AtomicReference<Throwable> secondFailure = new AtomicReference<>();
		Thread second = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				secondFailure.set(t);
			}
		}, "jsonrpc-second-launch");
		second.start();

		try {
			// the oracle (D8): no double start. A second launch must not enter run() a second time —
			// it is refused (loudly, if not very clearly: the config window is already closed).
			second.join(5000);
			assertFalse("the second launch must have settled", second.isAlive());
			assertEquals("a second launch must not start the application a second time",
				1, runCount.get());
			assertTrue("the second launch must be refused, not silently ignored: " + secondFailure.get(),
				secondFailure.get() != null);
			assertTrue("the refusal must come from the closed config window: " + secondFailure.get(),
				LauncherTestHarness.causeChainHas(secondFailure.get(), IllegalStateException.class));
		} finally {
			launcher.shutdown();
			await(ofCompletionStage(launcher.getCompleteFuture()));
		}
	}

	@Test
	public void shutdownBeforeStartFinishesStillStopsCleanly() throws Exception {
		JsonRpcServerLauncher launcher = singleLauncher(Config.create());
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "jsonrpc-launch-shutdown-race");
		thread.start();

		// immediately release the shutdown latch — the launch is very likely still wiring
		launcher.shutdown();

		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			Throwable t = failure.get();
			if (t != null) throw new AssertionError("launch failed", t);
			throw e;
		}
		// the start completed normally, run() saw the released latch and the stop is clean
		await(ofCompletionStage(launcher.getCompleteFuture()));
		thread.join(5000);
		if (failure.get() != null) {
			throw new AssertionError("launch failed", failure.get());
		}
	}

	@Test
	public void onStartRejectionLeavesNoResidualSocket() throws Exception {
		int port = TestUtils.getFreePort();
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
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "127.0.0.1:" + port));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}

			@Override
			protected void onStart() throws Exception {
				// a config rejection: the server has already bound at this point (startServices ran
				// first) — the rollback must close it
				throw new IllegalStateException("rejected");
			}
		};

		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> launcher.launch(Launcher.NO_ARGS));
		assertTrue(e.getMessage().contains("rejected"));

		// the rollback released the socket: a fresh connect is refused
		assertNothingListens(port);
	}

	private static void launchAndAwaitStart(JsonRpcServerLauncher launcher) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}).start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			Throwable t = failure.get();
			if (t != null) throw new AssertionError("launch failed", t);
			throw e;
		}
	}
}
