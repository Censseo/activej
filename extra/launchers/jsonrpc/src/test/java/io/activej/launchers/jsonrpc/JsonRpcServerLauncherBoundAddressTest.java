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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * D9 of the adversarial plan (ADR-028): with {@code :0} configured, the <b>real</b> address comes from
 * the server — {@code getBoundAddresses()} — while {@code getListenAddresses()} still shows the
 * configured port {@code 0}; and the {@code run()} log reports the real port (it must, because
 * {@code run()} is after the {@code ProtectedConfig} window closed, so the log can only come from the
 * server).
 */
public class JsonRpcServerLauncherBoundAddressTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ListAppender<ILoggingEvent> appender;
	private Level previousIoActivejLevel;

	@Before
	public void setUp() {
		// the platform test harness sets the io.activej logger to OFF (test/src/main/resources/logback-test.xml);
		// this test asserts what the launcher LOGS, so re-enable it for this class only and restore in @After
		Logger ioActivej = (Logger) LoggerFactory.getLogger("io.activej");
		previousIoActivejLevel = ioActivej.getLevel();
		ioActivej.setLevel(Level.INFO);
		appender = new ListAppender<>();
		appender.start();
		// the launcher's logger is per-subclass (getLogger(getClass())), so the root logger is the
		// only stable attachment point
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
	}

	@After
	public void tearDown() {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
		appender.stop();
		((Logger) LoggerFactory.getLogger("io.activej")).setLevel(previousIoActivejLevel);
	}

	@Test
	public void listenAddressesShowPortZeroWhileBoundAddressesReportTheRealPort() throws Exception {
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
		};
		launchAndAwaitStart(launcher);
		try {
			assertEquals("the listen address must still show the configured :0",
				List.of(new java.net.InetSocketAddress(0)), launcher.httpServer.getListenAddresses());
			int boundPort = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, boundPort);

			// the run() log carries the real port — and the configured :0 never appears in it
			String availabilityMessage = awaitLogMessage("JSON-RPC server is now available at");
			assertTrue("the run() log must show the real port " + boundPort + ": " + availabilityMessage,
				availabilityMessage.contains(":" + boundPort));
			assertTrue("the run() log must not show a :0 port: " + availabilityMessage,
				!availabilityMessage.contains(":0]"));
		} finally {
			launcher.shutdown();
			io.activej.promise.TestUtils.await(io.activej.promise.Promise.ofCompletionStage(launcher.getCompleteFuture()));
		}
	}

	private String awaitLogMessage(String needle) throws InterruptedException {
		// 200 x 100ms — no timing assertion, just a deadline long enough that a loaded surefire JVM
		// (this module runs 60+ tests in one JVM) cannot push the run() log past the window
		for (int attempt = 0; attempt < 200; attempt++) {
			for (ILoggingEvent event : appender.list) {
				if (event.getMessage() != null && event.getMessage().contains(needle)) {
					return event.getFormattedMessage();
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError("no log message containing '" + needle + "' appeared");
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
