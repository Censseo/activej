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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.activej.config.Config;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * D10 of the adversarial plan: two launchers in one process both register a dispatcher MBean under the
 * same {@code ObjectName}. The documented {@code boot-jmx} behaviour is a <b>logged</b> registration
 * failure — never an abandoned startup. The second launcher must be fully serving.
 */
public class JsonRpcServerLauncherJmxCollisionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ListAppender<ILoggingEvent> appender;
	private Level previousIoActivejLevel;

	@Before
	public void setUp() throws Exception {
		unregisterDispatcherBeans();
		// the platform test harness sets the io.activej logger to OFF (test/src/main/resources/logback-test.xml);
		// this test asserts the collision is LOGGED, so re-enable for this class only and restore in @After
		Logger ioActivej = (Logger) LoggerFactory.getLogger("io.activej");
		previousIoActivejLevel = ioActivej.getLevel();
		ioActivej.setLevel(Level.INFO);
		appender = new ListAppender<>();
		appender.start();
		((Logger) LoggerFactory.getLogger("io.activej.jmx.JmxRegistry")).addAppender(appender);
	}

	@After
	public void tearDown() throws Exception {
		((Logger) LoggerFactory.getLogger("io.activej.jmx.JmxRegistry")).detachAppender(appender);
		appender.stop();
		((Logger) LoggerFactory.getLogger("io.activej")).setLevel(previousIoActivejLevel);
		unregisterDispatcherBeans();
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
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	@Test
	public void secondLauncherLogsTheMBeanCollisionAndKeepsServing() throws Exception {
		JsonRpcServerLauncher first = launcher();
		JsonRpcServerLauncher second = launcher();
		launchAndAwaitStart(first);
		try {
			launchAndAwaitStart(second);

			int firstPort = first.httpServer.getBoundAddresses().get(0).getPort();
			int secondPort = second.httpServer.getBoundAddresses().get(0).getPort();

			// both launchers are fully serving despite the ObjectName collision
			ReadResponse firstResponse = post(Reactor.getCurrentReactor(), firstPort,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[1]}");
			assertEquals(200, firstResponse.code());
			ReadResponse secondResponse = post(Reactor.getCurrentReactor(), secondPort,
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"user.get\",\"params\":[2]}");
			assertEquals(200, secondResponse.code());
			assertTrue(secondResponse.bodyContains("\"result\""));

			// the collision is LOGGED — boot-jmx's documented behaviour, not a silent loss
			assertTrue("the second registration must be logged as a failure",
				awaitLogError("Cannot register MBean"));
		} finally {
			first.shutdown();
			io.activej.promise.TestUtils.await(io.activej.promise.Promise.ofCompletionStage(first.getCompleteFuture()));
			second.shutdown();
			io.activej.promise.TestUtils.await(io.activej.promise.Promise.ofCompletionStage(second.getCompleteFuture()));
		}
	}

	private boolean awaitLogError(String needle) throws InterruptedException {
		// 200 x 100ms — no timing assertion, just a deadline long enough that a loaded surefire JVM
		// (this module runs 60+ tests in one JVM) cannot push the registration log past the window
		for (int attempt = 0; attempt < 200; attempt++) {
			for (ILoggingEvent event : appender.list) {
				if (event.getLevel() == Level.ERROR && event.getFormattedMessage() != null
					&& event.getFormattedMessage().contains(needle)) {
					return true;
				}
			}
			Thread.sleep(100);
		}
		return false;
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
