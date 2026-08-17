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
import io.activej.inject.binding.DIException;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.assertListens;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.causeChainHas;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * D2, D3, D5, D6 of the adversarial plan: hostile values for the {@code jsonrpc.*} keys and the
 * precedence ladder (defaults &lt; properties file &lt; system properties).
 * <p>
 * The oracle for every value battery is "never starts with an illegal value": numeric out-of-domain
 * values are refused at the servlet's {@code build()}; unparseable strings fail conversion at the
 * first {@code get}; nothing ever binds.
 */
public class JsonRpcServerLauncherConfigValuesTest {
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
		loader = new URLClassLoader(new URL[]{propsDir.toUri().toURL()});
		previous = Thread.currentThread().getContextClassLoader();
	}

	@After
	public void tearDown() throws Exception {
		Thread.currentThread().setContextClassLoader(previous);
		System.clearProperty("config.jsonrpc.maxBodySize");
		loader.close();
		Files.deleteIfExists(propsDir.resolve("jsonrpc-server.properties"));
		Files.deleteIfExists(propsDir);
	}

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
				// note: the captured parameter is named 'overrides' — 'config' would resolve to the
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
	// D2 — jsonrpc.emptyResponseCode
	// ---------------------------------------------------------------------------------------------

	@Test
	public void emptyResponseCodeOutside200And204IsRefusedAtBuildAndNeverStarts() {
		for (String value : List.of("0", "418", "-1", "999")) {
			JsonRpcServerLauncher launcher = singleLauncher(
				Config.create().with("jsonrpc.emptyResponseCode", value));
			DIException e = assertThrows("emptyResponseCode=" + value,
				DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
			assertTrue("emptyResponseCode=" + value + " must fail at build with IllegalArgumentException, got: " + e,
				causeChainHas(e, IllegalArgumentException.class));
			assertTrue("the offending key must be named: " + e,
				messageContains(e, "emptyResponseCode"));
			assertTrue("no server may exist after a refused code", launcher.httpServer == null);
		}
	}

	@Test
	public void emptyResponseCodeNonNumericFailsConversionAndNeverStarts() {
		for (String value : List.of("abc", "204.0")) {
			JsonRpcServerLauncher launcher = singleLauncher(
				Config.create().with("jsonrpc.emptyResponseCode", value));
			DIException e = assertThrows("emptyResponseCode=" + value,
				DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
			assertTrue("emptyResponseCode=" + value + " must fail conversion, got: " + e,
				causeChainHas(e, NumberFormatException.class));
			assertTrue("no server may exist after a refused code", launcher.httpServer == null);
		}
	}

	@Test
	public void multiWorkerLauncherRefusesIllegalEmptyResponseCodes() {
		for (String value : List.of("418", "abc")) {
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
					return super.config()
						.overrideWith(Config.create().with("http.listenAddresses", "0"))
						.overrideWith(Config.create().with("jsonrpc.emptyResponseCode", value));
				}

				@Override
				protected void onFatalError(Throwable throwable) {}
			};
			DIException e = assertThrows("emptyResponseCode=" + value,
				DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
			assertTrue("emptyResponseCode=" + value + " must be refused, got: " + e,
				causeChainHas(e, IllegalArgumentException.class));
		}
	}

	// ---------------------------------------------------------------------------------------------
	// D3 — jsonrpc.maxBodySize
	// ---------------------------------------------------------------------------------------------

	@Test
	public void maxBodySizeZeroAndNegativeAreRefusedAtBuildAndNeverStart() {
		for (String value : List.of("0", "-1kb")) {
			JsonRpcServerLauncher launcher = singleLauncher(
				Config.create().with("jsonrpc.maxBodySize", value));
			DIException e = assertThrows("maxBodySize=" + value,
				DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
			assertTrue("maxBodySize=" + value + " must fail at build with IllegalArgumentException, got: " + e,
				causeChainHas(e, IllegalArgumentException.class));
			assertTrue("no server may exist after a refused bound", launcher.httpServer == null);
		}
	}

	@Test
	public void maxBodySizeNonNumericFailsConversionAndNeverStarts() {
		JsonRpcServerLauncher launcher = singleLauncher(
			Config.create().with("jsonrpc.maxBodySize", "banana"));
		DIException e = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("maxBodySize=banana must fail conversion, got: " + e,
			messageContains(e, "Invalid MemSize"));
		assertTrue("no server may exist after a refused bound", launcher.httpServer == null);
	}

	@Test
	public void maxBodySizeAboveTheConnectionTierIsCappedByTheConnectionTier() throws Exception {
		// D3 oracle: 10gb is a legal servlet-tier bound and a 100mb connection tier caps the effective
		// bound — a declared length above 100mb is a malformed 400 + close, never 413. The tier is a
		// launcher-config key (http.maxBodySize), disabled by default (Initializers.ofHttpWorker
		// defaults it to MemSize.ZERO), so the oracle configures it explicitly.
		// (D3-1 fixed: the servlet used to refuse >2GB at build because MemSize.toInt() overflowed.)
		JsonRpcServerLauncher launcher = singleLauncher(
			Config.create()
				.with("jsonrpc.maxBodySize", "10gb")
				.with("http.maxBodySize", "100mb"));
		launchAndAwaitStart(launcher);

		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertListens(port);

			// a 5mb body is below the servlet's 10gb bound and the 100mb connection tier, so it reaches
			// the dispatcher: 200, never the servlet's 413 (a bound silently clamped to the 1mb default
			// would answer 413 first) and never the connection tier's 400. The dispatcher's process-wide
			// envelope bound (JsonRpcLimits.MAX_BODY_SIZE, 1mb) then answers -32001 for the >1mb document
			// — the transport-tier 200 is the D3 signal, not the JSON-RPC result.
			ReadResponse ok = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42],\"pad\":\""
					+ "x".repeat(5 * 1024 * 1024) + "\"}");
			assertEquals(200, ok.code());
			assertTrue(ok.bodyContains("-32001"));

			// a declared 200mb length: the connection tier evaluates it before any byte — 400 + close
			try (Socket socket = new Socket("127.0.0.1", port)) {
				OutputStream out = socket.getOutputStream();
				out.write(("POST / HTTP/1.1\r\n" +
					"Host: 127.0.0.1:" + port + "\r\n" +
					"Content-Type: application/json\r\n" +
					"Content-Length: 209715200\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				String statusLine = in.readLine();
				assertTrue("expected a 400 from the connection tier, got: " + statusLine,
					statusLine != null && statusLine.contains("400"));
				assertTrue("never a 413 from the servlet: " + statusLine, !statusLine.contains("413"));
				String line;
				StringBuilder headers = new StringBuilder();
				while ((line = in.readLine()) != null) {
					headers.append(line).append('\n');
				}
				// the tier closes the connection after the rejection
				assertTrue("the tier must close the connection: " + headers,
					headers.toString().contains("Connection: close"));
			}
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// D5 — workers
	// ---------------------------------------------------------------------------------------------

	@Test
	public void workersNonNumericFailsConversionAndNeverStarts() {
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
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "abc"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		DIException e = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("workers=abc must fail conversion, got: " + e,
			causeChainHas(e, NumberFormatException.class));
	}

	@Test
	public void workersNegativeIsRefusedAtPoolCreationNamingTheKeyAndNeverStarts() {
		// D5-1 fixed: workers is validated in the pool-creation provider — a raw
		// NegativeArraySizeException naming neither the key nor a remedy is gone.
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
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "-1"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		DIException e = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("workers=-1 must fail with IllegalArgumentException, got: " + e,
			causeChainHas(e, IllegalArgumentException.class));
		assertTrue("the refusal must name the workers key: " + e,
			messageContains(e, "workers"));
		assertTrue("no server may exist after a refused pool size", launcher.primaryServer == null);
	}

	@Test
	public void workersZeroIsRefusedAtPoolCreationNamingTheKeyAndNeverStarts() {
		// D5-1 fixed: a zero-worker pool previously made ServiceGraphModule's worker-instance start
		// adapter read Instances.get(0) — a raw ArrayIndexOutOfBoundsException naming neither
		// 'workers' nor a remedy. The launcher now refuses it up front, naming the key.
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
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		DIException e = assertThrows(DIException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("workers=0 must fail with IllegalArgumentException, got: " + e,
			causeChainHas(e, IllegalArgumentException.class));
		assertTrue("the refusal must name the workers key: " + e,
			messageContains(e, "workers"));
		assertTrue("no server may exist after a refused pool size", launcher.primaryServer == null);
	}

	@Test
	public void workersFiftyStartsAndServes() throws Exception {
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
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "50"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		launchAndAwaitStart(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			ReadResponse ok = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}");
			assertEquals(200, ok.code());
			assertTrue(ok.bodyContains("\"result\""));
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// D6 — precedence: defaults < jsonrpc-server.properties < -Dconfig.*
	// ---------------------------------------------------------------------------------------------

	@Test
	public void systemPropertiesWinOverThePropertiesFileWhichWinsOverDefaults() throws Exception {
		// the file says 2mb — above the 1mb default
		Files.writeString(propsDir.resolve("jsonrpc-server.properties"),
			"jsonrpc.maxBodySize=2mb\n");

		// (a) file vs default: a 1.5mb body is 413 under the default and 200 under the file's 2mb
		Thread.currentThread().setContextClassLoader(loader);
		JsonRpcServerLauncher fileLauncher = singleLauncher(Config.create());
		launchAndAwaitStart(fileLauncher);
		try {
			int port = fileLauncher.httpServer.getBoundAddresses().get(0).getPort();
			ReadResponse overDefault = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42],\"pad\":\""
					+ "x".repeat(1500 * 1024) + "\"}");
			assertEquals("the properties file (2mb) must beat the 1mb default", 200, overDefault.code());
		} finally {
			stop(fileLauncher);
		}

		// (b) sysprops vs file: the system property says 3kb — a 4kb body is 413 only if it wins
		System.setProperty("config.jsonrpc.maxBodySize", "3kb");
		JsonRpcServerLauncher syspropLauncher = singleLauncher(Config.create());
		launchAndAwaitStart(syspropLauncher);
		try {
			int port = syspropLauncher.httpServer.getBoundAddresses().get(0).getPort();
			ReadResponse overSysprop = post(Reactor.getCurrentReactor(), port,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42],\"pad\":\""
					+ "x".repeat(4096) + "\"}");
			assertEquals("the system property (3kb) must beat the file (2mb)", 413, overSysprop.code());
		} finally {
			stop(syspropLauncher);
		}
	}

	static void launchAndAwaitStart(JsonRpcServerLauncher launcher) throws Exception {
		launchAndAwaitStart((Launcher) launcher);
	}

	static void launchAndAwaitStart(MultithreadedJsonRpcServerLauncher launcher) throws Exception {
		launchAndAwaitStart((Launcher) launcher);
	}

	private static void launchAndAwaitStart(Launcher launcher) throws Exception {
		java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
		new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}).start();
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			if (failure.get() != null) {
				throw new AssertionError("launch failed", failure.get());
			}
			if (launcher.getStartFuture().toCompletableFuture().isDone()) return;
			Thread.sleep(50);
		}
		throw new AssertionError("launch did not start within 10s", failure.get());
	}

	private static boolean messageContains(Throwable t, String needle) {
		for (Throwable current = t; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(needle)) return true;
		}
		return false;
	}
}
