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
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * D1 (adversarial-test-plan, P0): FR-036 fail-closed reached through the <b>properties file</b> —
 * {@code jsonrpc-server.properties} on the classpath — not only through {@code -Dconfig.*} system
 * properties. An operator who put {@code jsonrpc.maxBatchSize=10} in the file believing they had
 * tightened a security bound must get the same loud startup failure, naming the key and the
 * {@code -DJsonRpcLimits.*} property that actually controls it. Both launchers.
 * <p>
 * The file is placed on a dedicated {@link URLClassLoader} installed as the context class loader of
 * the launching thread: {@code Config.ofClassPathProperties(PROPERTIES_FILE, true)} resolves the file
 * through exactly that loader, so the rest of the suite never sees it.
 */
public class JsonRpcServerLauncherPropertiesRejectionTest {
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
			"jsonrpc.maxBatchSize=10\n");
		loader = new URLClassLoader(new URL[]{propsDir.toUri().toURL()});
		previous = Thread.currentThread().getContextClassLoader();
	}

	@After
	public void tearDown() throws Exception {
		Thread.currentThread().setContextClassLoader(previous);
		loader.close();
		Files.deleteIfExists(propsDir.resolve("jsonrpc-server.properties"));
		Files.deleteIfExists(propsDir);
	}

	@Test
	public void singleWorkerLauncherFailsClosedOnAPropertiesFileKey() {
		Thread.currentThread().setContextClassLoader(loader);

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
				// :0 keeps the test off the 8080 default; the file+sysprops chain is untouched
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
	public void multiWorkerLauncherFailsClosedOnAPropertiesFileKey() {
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
			Config config() {
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
}
