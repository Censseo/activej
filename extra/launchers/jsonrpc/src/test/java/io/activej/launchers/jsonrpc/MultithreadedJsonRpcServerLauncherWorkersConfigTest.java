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
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static org.junit.Assert.assertEquals;

/**
 * F4 of the adversarial plan (documentary): the configuration is read <b>once</b> at startup. An
 * operator editing {@code jsonrpc-server.properties} while the launcher runs must not change anything
 * until a restart — here proven by rewriting the file (2 → 4 workers) mid-run and observing the same
 * two worker beans keep serving.
 */
public class MultithreadedJsonRpcServerLauncherWorkersConfigTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private Path propsDir;
	private URLClassLoader loader;
	private ClassLoader previous;

	@Before
	public void setUp() throws Exception {
		propsDir = Files.createTempDirectory("jsonrpc-props");
		Files.writeString(propsDir.resolve("jsonrpc-server.properties"),
			"workers=2\nhttp.listenAddresses=0\n");
		loader = new URLClassLoader(new URL[]{propsDir.toUri().toURL()});
		previous = Thread.currentThread().getContextClassLoader();
	}

	@After
	public void tearDown() throws Exception {
		Thread.currentThread().setContextClassLoader(previous);
		loader.close();
		Files.deleteIfExists(propsDir.resolve("jsonrpc-server.properties"));
		Files.deleteIfExists(propsDir);
		unregisterDispatcherBeans();
	}

	@Test
	public void editingThePropertiesFileMidRunHasNoEffectUntilRestart() throws Exception {
		Thread.currentThread().setContextClassLoader(loader);
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
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
			protected void onFatalError(Throwable throwable) {}
		};
		launchAndAwaitStart(launcher);
		try {
			assertEquals("the file's 2 workers must be in force", 2, workerBeans().size());
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			ReadResponse ok = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
			assertEquals(200, ok.code());

			// the operator edits the file: 4 workers now
			Files.writeString(propsDir.resolve("jsonrpc-server.properties"),
				"workers=4\nhttp.listenAddresses=0\n");

			// no effect: still two worker beans, still serving
			assertEquals("a mid-run file edit must not change the running pool", 2, workerBeans().size());
			ReadResponse again = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"user.get\",\"params\":[43]}");
			assertEquals(200, again.code());
		} finally {
			stop(launcher);
		}
	}

	private static Set<ObjectName> workerBeans() throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		return mbs.queryNames(new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null)
			.stream()
			.filter(name -> name.getKeyPropertyList().containsKey("workerId"))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static void launchAndAwaitStart(MultithreadedJsonRpcServerLauncher launcher) throws Exception {
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
