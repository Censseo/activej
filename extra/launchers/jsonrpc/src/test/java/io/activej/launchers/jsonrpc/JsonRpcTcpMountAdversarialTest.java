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

import io.activej.async.exception.AsyncCloseException;
import io.activej.config.Config;
import io.activej.http.HttpServer;
import io.activej.inject.Injector;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.binding.DIException;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.inject.module.Modules;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.causeChainHas;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Domain F of {@code specs/017-jsonrpc-tcp-transport/adversarial-test-plan.md} — the framed-TCP mount
 * co-existing with the rest of the launcher, and its config surface under attack. Oracle throughout:
 * {@code contracts/config-keys.md} (feature 017 delta), {@code JsonRpcModule}, {@code JsonRpcTcpMount}
 * and {@code JsonRpcServerLauncher.rejectNonKeys} — never "whatever the code happens to do today"
 * without first reading it.
 * <p>
 * <b>F1 (P0).</b> {@code jsonrpc.tcp.bogus} arriving through a real {@code jsonrpc-server.properties}
 * on the classpath — not {@code -Dconfig.*}, not a programmatic {@code Config} — must fail startup
 * naming the exact key, the same as the programmatic case {@code JsonRpcTcpMountTest} (via
 * {@code JsonRpcServerLauncher.rejectNonKeys}) already covers.
 * <p>
 * <b>F2 (P0).</b> Hostile {@code jsonrpc.tcp.port} values — {@code "-1"}, {@code "70000"},
 * {@code "notanumber"} — are read by {@code JsonRpcModule.tcpPort(...)}/{@code tcpServer(...)}
 * <i>before</i> {@code onStart()}'s {@code rejectNonKeys} ever runs, inside the {@code @Eager
 * JsonRpcTcpMount} binding. Verified against {@link Launcher#launch}: an {@code @Eager} binding
 * failure happens in {@code injector.createEagerInstances()}, which sits <b>outside</b> the
 * try/catch that completes {@code onStartFuture} — so a plain {@code getStartFuture()} poll would
 * hang forever. The harness here polls both the future and the launch thread's escaped
 * {@link Throwable}, exactly like {@code JsonRpcWsMountTest}'s {@code probeHostileWsPath}. The
 * resulting message is a raw JDK one ({@code NumberFormatException} / {@code IllegalArgumentException
 * "port out of range"}) that never names {@code jsonrpc.tcp.port} — unlike {@code rejectNonKeys}'s
 * deliberate, key-naming checks — and no service, HTTP included, ever reaches {@code listen()}.
 * <p>
 * <b>F3 (P1).</b> {@code jsonrpc.tcp.port=""} (present but empty) must be indistinguishable from an
 * absent key: {@code JsonRpcTcpMount.isEnabled()==false}, {@code peekInstance(JsonRpcTcpServer.class)
 * == null}, POST untouched — {@code JsonRpcModule.tcpPort(...)}'s {@code isEmpty()} check treats both
 * identically by construction, but no existing test drives the key present-and-empty rather than
 * absent.
 * <p>
 * <b>F4 (P1).</b> TLS composed through the launcher's one extension point,
 * {@code getBusinessLogicModule()}, by contributing a second {@code @Provides JsonRpcTcpServer} built
 * with {@code withSslListenPort(...)}. Verified against {@code core-inject/CLAUDE.md} (WI-6: "duplicate
 * binding without a Multibinder" is a graph-compile-time {@code DIException}, always, at
 * {@code Injector.of(...)}, never deferred) and {@code Multibinders.errorOnDuplicate()}'s exact
 * message shape: the graph refuses to compile, naming {@code JsonRpcTcpServer} — the alternate
 * provider's body (which would need a real certificate to run) never executes at all. This pins the
 * GAP {@code contracts/config-keys.md} documents: TLS is not composable through this launcher without
 * bypassing {@code JsonRpcModule} entirely.
 * <p>
 * <b>F5 (P1).</b> No socket-timeout key exists for {@code jsonrpc.tcp.port}: a raw TCP connection that
 * never sends a byte stays open across a bounded silent window (WI-17 — no real 60s wait, a bounded
 * proof that at least one server-side sweep tick passed without effect) and is still fully live
 * afterwards (a real call answers on the very same connection). Separately, the plausible timeout-key
 * spellings an operator might reach for — {@code jsonrpc.tcp.readWriteTimeout},
 * {@code jsonrpc.tcp.socketSettings} — are rejected as unknown keys by the same
 * {@code rejectNonKeys} check F1/F2 exercise, exactly as {@code spec.md}'s Timeout table predicts.
 * <p>
 * <b>F7 (P1).</b> {@code jsonrpc.tcp.port} configured onto a port an {@code http.listenAddresses}
 * listener already occupies — the first time this launcher carries two independent listeners in one
 * instance — fails the second listener's {@code listen()} with {@code BindException}; the service
 * graph rolls back cleanly (the failing launcher's own HTTP listener, even if it raced to bind first,
 * ends up unbound too) and the pre-existing occupant keeps answering, undisturbed.
 * <p>
 * <b>F6 (P2).</b> Stopping a launcher with the TCP endpoint enabled and starting a second one
 * configured on the exact same numeric port must not hit {@code BindException} — proof that
 * {@code stop()} actually releases the listener's file descriptor before it returns control, via
 * {@code JsonRpcTcpServerServiceAdapter}'s delegation to {@code ServiceAdapters.forReactiveServer()}.
 * <p>
 * Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * would kill the Surefire JVM), and {@code :0} + {@code getBoundAddresses()} throughout (ADR-028,
 * never {@code getFreePort()} for a component under test — F7's sole exception is the occupying
 * launcher, which plays the role of a pre-existing occupant, not the subject under test, and even
 * that one binds {@code :0} and is asked back, never {@code getFreePort()}).
 */
public class JsonRpcTcpMountAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String POST_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}";
	private static final String POST_RESULT = "\"result\":{\"id\":42,\"name\":\"user-42\"}";

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	// -------------------------------------------------------------------------------------------
	// F1 — non-key through a real properties file (P0)
	// -------------------------------------------------------------------------------------------

	private Path propsDir;
	private URLClassLoader loader;
	private ClassLoader previousContextClassLoader;

	@Before
	public void setUpPropertiesFile() throws Exception {
		propsDir = Files.createTempDirectory("jsonrpc-tcp-adversarial-props");
		Files.writeString(propsDir.resolve("jsonrpc-server.properties"), "jsonrpc.tcp.bogus=1\n");
		loader = new URLClassLoader(new URL[]{propsDir.toUri().toURL()});
		previousContextClassLoader = Thread.currentThread().getContextClassLoader();
	}

	@After
	public void tearDownPropertiesFile() throws Exception {
		Thread.currentThread().setContextClassLoader(previousContextClassLoader);
		loader.close();
		Files.deleteIfExists(propsDir.resolve("jsonrpc-server.properties"));
		Files.deleteIfExists(propsDir);
	}

	@Test
	public void unknownTcpKeyInAPropertiesFileFailsStartupNamingTheKey() {
		// F1, adversarial plan (P0): the FR-101 fail-closed check must fire when the non-key arrives
		// through the PROPERTIES FILE (jsonrpc-server.properties on the classpath), not only through
		// -Dconfig.* system properties or a programmatic Config (JsonRpcTcpMountTest's
		// unknownTcpKeyFailsStartupNamingTheKey). Config.ofClassPathProperties(PROPERTIES_FILE, true)
		// resolves the file through the thread's context class loader, which is set to the dedicated
		// loader here — the same harness JsonRpcServerLauncherPropertiesRejectionTest and
		// JsonRpcWsMountTest use.
		Thread.currentThread().setContextClassLoader(loader);

		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("the rejected key must be named exactly: " + e.getMessage(),
			e.getMessage().contains("jsonrpc.tcp.bogus"));
		assertTrue("the only supported TCP key must be named, same as the programmatic case: " + e.getMessage(),
			e.getMessage().contains("the only TCP key is 'jsonrpc.tcp.port'"));
	}

	// -------------------------------------------------------------------------------------------
	// F2 — hostile jsonrpc.tcp.port values (P0)
	// -------------------------------------------------------------------------------------------

	@Test
	public void notANumberTcpPortFailsFastWithARawNumberFormatExceptionNeverNamingTheKey() throws Exception {
		// F2 (P0): "notanumber" is read by JsonRpcTcpMount.tcpMount(...) itself, BEFORE tcpServer.get()
		// is ever called (JsonRpcModule.tcpPort: jsonrpc.get(ofInteger(), "tcp.port") throws while still
		// inside tcpMount's own body). ofInteger() is SimpleConfigConverter.of(Integer::valueOf, ...),
		// which wraps ANY exception from fromStringFn in `new IllegalArgumentException(e)` (verified
		// against boot-config's SimpleConfigConverter source) — so the directly-thrown exception is an
		// IllegalArgumentException whose OWN message is the wrapped NumberFormatException's toString(),
		// and whose cause is that NumberFormatException. Both layers are asserted.
		Failure failure = launchExpectingEagerFailure(Config.create().with("jsonrpc.tcp.port", "notanumber"));

		System.out.println("F2 [notanumber] -> " + failure.chain());
		assertTrue("the root cause must be a NumberFormatException naming the bad literal: " + failure.chain(),
			causeChainHas(failure.throwable(), NumberFormatException.class));
		assertTrue("the chain must carry the literal value: " + failure.chain(),
			failure.chain().contains("notanumber"));
		assertFalse("a raw JDK conversion failure must NEVER name the config key (unlike rejectNonKeys): "
			+ failure.chain(), failure.chain().contains("jsonrpc.tcp.port"));
		assertNothingEverListened(failure.launcher());
	}

	@Test
	public void negativeTcpPortFailsFastWithPortOutOfRangeNeverNamingTheKey() throws Exception {
		// F2 (P0): "-1" parses fine (tcpPort() succeeds), so tcpMount(...) proceeds to tcpServer.get() —
		// which invokes JsonRpcModule.tcpServer(...), whose withListenPort(-1) reaches
		// AbstractReactiveServer.Builder.withListenPort -> new InetSocketAddress(-1), raising a raw JDK
		// IllegalArgumentException("port out of range:-1") — verified directly against
		// AbstractReactiveServer.java. No JsonRpcTcpServer is ever built (the exception fires before
		// .build() is reached).
		Failure failure = launchExpectingEagerFailure(Config.create().with("jsonrpc.tcp.port", "-1"));

		System.out.println("F2 [-1] -> " + failure.chain());
		assertTrue("the chain must carry the JDK 'port out of range' wording: " + failure.chain(),
			failure.chain().contains("port out of range"));
		assertTrue("the chain must carry the offending literal port: " + failure.chain(),
			failure.chain().contains("-1"));
		assertFalse("a raw JDK range failure must NEVER name the config key: " + failure.chain(),
			failure.chain().contains("jsonrpc.tcp.port"));
		assertNothingEverListened(failure.launcher());
	}

	@Test
	public void portAbove65535FailsFastWithPortOutOfRangeNeverNamingTheKey() throws Exception {
		// F2 (P0): "70000" is > 65535 — the same new InetSocketAddress(port) range check as -1, the
		// other side of the 1..65535 boundary.
		Failure failure = launchExpectingEagerFailure(Config.create().with("jsonrpc.tcp.port", "70000"));

		System.out.println("F2 [70000] -> " + failure.chain());
		assertTrue("the chain must carry the JDK 'port out of range' wording: " + failure.chain(),
			failure.chain().contains("port out of range"));
		assertTrue("the chain must carry the offending literal port: " + failure.chain(),
			failure.chain().contains("70000"));
		assertFalse("a raw JDK range failure must NEVER name the config key: " + failure.chain(),
			failure.chain().contains("jsonrpc.tcp.port"));
		assertNothingEverListened(failure.launcher());
	}

	/** What one F2 probe observed: the launcher instance (fields may be partially populated) and the escaped failure. */
	private record Failure(JsonRpcServerLauncher launcher, Throwable throwable) {
		/** The whole cause chain, flattened, for substring assertions and failure messages alike. */
		String chain() {
			StringBuilder sb = new StringBuilder();
			for (Throwable current = throwable; current != null; current = current.getCause()) {
				if (!sb.isEmpty()) sb.append(" <- ");
				sb.append(current.getClass().getName()).append(": ").append(current.getMessage());
			}
			return sb.toString();
		}
	}

	/**
	 * Launches on a dedicated thread and returns the {@link Throwable} that escaped {@link
	 * Launcher#launch}. An {@code @Eager} binding failure (F2's case) happens in {@code
	 * injector.createEagerInstances()} — verified against {@code Launcher.launch}'s source: that call
	 * sits OUTSIDE the inner try/catch that completes {@code onStartFuture}, so on this failure path
	 * NEITHER {@code getStartFuture()} NOR {@code getCompleteFuture()} is ever completed — a bare future
	 * poll (the shape {@code rejectNonKeys} failures use) would hang for the full 10s and then time out
	 * having observed nothing. Both the future and the thread's escaped throwable are polled, exactly
	 * like {@code JsonRpcWsMountTest#probeHostileWsPath}.
	 */
	private static Failure launchExpectingEagerFailure(Config tcpOverride) throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(tcpOverride);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "jsonrpc-tcp-eager-failure");
		thread.start();

		CompletableFuture<Void> startFuture = launcher.getStartFuture().toCompletableFuture();
		long deadline = System.currentTimeMillis() + 10_000;
		while (!startFuture.isDone() && failure.get() == null && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		thread.join(5000);

		if (failure.get() != null) return new Failure(launcher, failure.get());
		if (startFuture.isDone()) {
			try {
				startFuture.get();
				fail("startup must fail for a hostile jsonrpc.tcp.port value");
			} catch (ExecutionException e) {
				return new Failure(launcher, e.getCause());
			}
		}
		throw new AssertionError("neither the start future completed nor the launch thread failed within 10s — "
			+ "either the hostile value was silently accepted, or it hung the launcher");
	}

	/**
	 * F2's "aucun socket ouvert" claim, pinned rather than assumed. Verified empirically against a
	 * failing run of this very test (not merely inferred from reading {@code Launcher.launch}):
	 * {@code launcher.httpServer} is a plain {@code @Inject} field, populated only by {@code
	 * postInjectInstances(injector)} — which {@code Launcher.launch} calls AFTER {@code
	 * injector.createEagerInstances()}, not by the earlier {@code injector.getInstance(this.getClass())}
	 * (that call only obtains/constructs the launcher singleton itself). So once the {@code @Eager
	 * JsonRpcTcpMount} binding throws, {@code postInjectInstances} never runs and the field is
	 * <b>still null</b> — asserting through it would NPE, not prove anything. The injector captured by
	 * {@link #capturedInjectors} in {@code onInit} (which DOES run before {@code createEagerInstances()})
	 * is the reliable handle: {@code peekInstance} (which, unlike {@code getInstance}, never triggers
	 * construction) shows whether an {@code HttpServer} was even built. Either it was never constructed
	 * at all — the strongest form of "no socket opened" — or, if some unrelated eager binding happened
	 * to have built it first, its {@code getBoundAddresses()} must still be empty because {@code
	 * listen()} is only ever called later, from {@code startServices(...)}, which this failure path never
	 * reaches.
	 */
	private static void assertNothingEverListened(JsonRpcServerLauncher launcher) {
		Injector injector = capturedInjectors.get(launcher);
		HttpServer httpServer = injector.peekInstance(HttpServer.class);
		if (httpServer == null) {
			// the strongest form: the HttpServer object itself was never even constructed
			return;
		}
		assertTrue("an HttpServer object existed, but listen() must never have been called: no socket is " +
			"opened for ANY service once the @Eager tcpMount fails, HTTP included",
			httpServer.getBoundAddresses().isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// F3 — present-but-empty jsonrpc.tcp.port (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void presentButEmptyTcpPortBehavesExactlyLikeAnAbsentKey() throws Exception {
		// F3 (P1): JsonRpcModule.tcpPort(Config) does `jsonrpc.get("tcp.port", "").isEmpty()` — true for
		// BOTH a missing key and a key present with an empty value. defaultConfigConstructsNoTcpServerAndOpensNoSocket
		// (JsonRpcTcpMountTest) drives the ABSENT case; this drives the key PRESENT AND EMPTY, distinctly.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create().with("jsonrpc.tcp.port", ""));
		LauncherTestHarness.launch(launcher);
		try {
			int httpPort = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, httpPort);

			Injector injector = capturedInjectors.get(launcher);
			JsonRpcTcpMount mount = injector.peekInstance(JsonRpcTcpMount.class);
			assertTrue("the @Eager mount decision must have been taken at wiring time regardless", mount != null);
			assertFalse("a present-but-EMPTY jsonrpc.tcp.port must leave the endpoint disabled, exactly like absent",
				mount.isEnabled());
			assertNull("a disabled endpoint mounts no listener", mount.listener());
			assertNull("a disabled endpoint constructs NO JsonRpcTcpServer at all, same as the absent-key case",
				injector.peekInstance(JsonRpcTcpServer.class));

			ReadResponse response = post(Reactor.getCurrentReactor(), httpPort, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	// -------------------------------------------------------------------------------------------
	// F4 — TLS composition through getBusinessLogicModule() (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void tlsCannotBeComposedThroughTheLauncherWithoutBypassingJsonRpcModule() {
		// F4 (P1): the only extension point is getBusinessLogicModule(). Contributing a SECOND
		// @Provides JsonRpcTcpServer there (built with withSslListenPort(...), the mechanism
		// contracts/config-keys.md names) collides with JsonRpcModule's OWN @Provides tcpServer(...) on
		// the unqualified Key<JsonRpcTcpServer> — verified against core-inject/CLAUDE.md (WI-6: "two
		// bindings for one key with no Multibinder" is a DIException, ALWAYS raised at Injector.of(...),
		// never deferred to getInstance) and Multibinders.errorOnDuplicate()'s exact wording
		// ("Duplicate bindings for key ..."). This is a purely STRUCTURAL check over declared bindings
		// (Preprocessor.reduce runs the multibinder over every declared key in the trie, reachable or
		// not) — the alternate provider's body never executes, so it does not even need a real
		// certificate to prove the point.
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return Modules.combine(businessLogic(), new AbstractModule() {
					@Provides
					JsonRpcTcpServer alternateTlsTcpServer(NioReactor reactor, JsonRpcDispatcher dispatcher) {
						SSLContext sslContext;
						try {
							sslContext = SSLContext.getDefault();
						} catch (Exception e) {
							throw new AssertionError(e);
						}
						Executor sslExecutor = Executors.newSingleThreadExecutor();
						// this body is asserted to NEVER run — the graph fails to compile before any
						// binding is ever invoked
						return JsonRpcTcpServer.builder(reactor, dispatcher)
							.withSslListenPort(sslContext, sslExecutor, 0)
							.build();
					}
				});
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};

		DIException e = assertThrows("the graph must refuse to compile — a second unqualified " +
			"JsonRpcTcpServer binding collides with JsonRpcModule's own",
			DIException.class, launcher::testInjector);
		System.out.println("F4 [duplicate JsonRpcTcpServer] -> " + e.getMessage());
		assertTrue("the duplicate-binding failure must name the colliding TYPE: " + e.getMessage(),
			e.getMessage().contains("JsonRpcTcpServer"));
		assertTrue("the standard Multibinders.errorOnDuplicate() wording must be present: " + e.getMessage(),
			e.getMessage().contains("Duplicate bindings for key"));
	}

	// -------------------------------------------------------------------------------------------
	// F5 — no socket-timeout key, an idle connection is never bounded (P1)
	// -------------------------------------------------------------------------------------------

	/** The bounded idle window F5 keeps a raw connection silent for — see the test for why it is bounded (WI-17). */
	private static final long F5_IDLE_WINDOW_MILLIS = 1500;

	@Test
	public void anIdleRawTcpConnectionStaysOpenAndFullyLiveAcrossTheLauncherMount() throws Exception {
		// F5 (P1): JsonRpcModule.tcpServer(...) never calls withSocketSettings(...) — verified directly
		// against its source (no such call anywhere in the method) — so nothing bounds a connection that
		// sends no bytes. Proof, BOUNDED and BEHAVIOURAL (WI-17, no literal 60s wait): a raw connection
		// is opened, a short-SO_TIMEOUT read is attempted immediately (expecting SocketTimeoutException —
		// "still open, nothing arrived", as OPPOSED to end-of-stream, which would mean the server closed
		// it), the connection is then left silent for F5_IDLE_WINDOW_MILLIS (crossing at least one tick
		// of whatever periodic sweep might exist), probed again the same way, and FINALLY proven still
		// fully functional by sending one real JSON-RPC document on the very same socket and reading a
		// real answer — a zombie half-open socket would fail this last step even if the earlier
		// timeouts looked identical.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create().with("jsonrpc.tcp.port", "0"));
		LauncherTestHarness.launch(launcher);
		try {
			int tcpPort = tcpMountPort(launcher);

			try (RawSocket raw = RawSocket.connect(tcpPort, 300)) {
				assertTimesOutRatherThanCloses(raw, "immediately after connecting");

				Thread.sleep(F5_IDLE_WINDOW_MILLIS);

				assertTimesOutRatherThanCloses(raw, "after " + F5_IDLE_WINDOW_MILLIS + "ms of silence");

				raw.writeLine(POST_DOCUMENT);
				String answer = raw.readLine();
				assertTrue("the SAME long-idle connection must still answer a real call: " + answer,
					answer != null && answer.contains(POST_RESULT));
			}
		} finally {
			stop(launcher);
		}
	}

	/** One bounded read attempt that must time out (connection still open, nothing sent) rather than hit EOF (closed). */
	private static void assertTimesOutRatherThanCloses(RawSocket raw, String when) throws Exception {
		try {
			String line = raw.readLine();
			fail("expected a read timeout " + when + " (the connection must still be OPEN with nothing to read), " +
				"but got a line — possibly the server answered unprompted, or closed: " + line);
		} catch (SocketTimeoutException expected) {
			// the connection is still open — nothing arrived within the bound, which is exactly the point
		}
	}

	/**
	 * A tiny, dependency-free blocking peer for F5, deliberately inlined here rather than reused from
	 * {@code cloud-jsonrpc-tcp}'s own {@code JsonRpcTcpRawSocket} test fixture: that fixture is
	 * {@code src/test}-scoped in a module this one does not carry a {@code test-jar} dependency on.
	 * Writes exactly the bytes given (LF-terminated, one framed document) and reads back one
	 * LF-terminated line, bounded by {@code SO_TIMEOUT} so a server that never answers fails the test
	 * quickly instead of hanging the suite — the JSON Lines framing {@code quickstart.md} describes.
	 */
	private static final class RawSocket implements AutoCloseable {
		private final Socket socket;

		private RawSocket(Socket socket) {
			this.socket = socket;
		}

		static RawSocket connect(int port, int soTimeoutMillis) throws IOException {
			Socket socket = new Socket();
			try {
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(soTimeoutMillis);
				socket.connect(new InetSocketAddress("127.0.0.1", port), soTimeoutMillis);
			} catch (IOException | RuntimeException e) {
				socket.close();
				throw e;
			}
			return new RawSocket(socket);
		}

		void writeLine(String document) throws IOException {
			socket.getOutputStream().write((document + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			socket.getOutputStream().flush();
		}

		/** Reads up to and including the first {@code \n}; {@code null} at end-of-stream before one arrives. */
		String readLine() throws IOException {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			int b;
			while ((b = socket.getInputStream().read()) != -1) {
				if (b == '\n') return line.toString(java.nio.charset.StandardCharsets.UTF_8);
				line.write(b);
			}
			return null;
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	@After
	public void clearTimeoutKeyProperties() {
		System.clearProperty("config.jsonrpc.tcp.readWriteTimeout");
		System.clearProperty("config.jsonrpc.tcp.socketSettings");
	}

	@Test
	public void readWriteTimeoutKeyIsRejectedAsUnknownUnderTcp() throws Exception {
		// F5 follow-up (P1): the plausible spelling an operator reaches for first — rejected the same way
		// as any other jsonrpc.tcp.* non-key (F1/F2's rejectNonKeys), naming the exact key.
		assertRejectedAsUnknownTcpKey(
			Config.create().with("jsonrpc.tcp.port", "0").with("jsonrpc.tcp.readWriteTimeout", "60 seconds"),
			"jsonrpc.tcp.readWriteTimeout");
	}

	@Test
	public void socketSettingsKeyIsRejectedAsUnknownUnderTcp() throws Exception {
		// F5 follow-up (P1): the other plausible spelling, also rejected — the launcher offers no
		// per-instance seam to bound an idle TCP session at all.
		assertRejectedAsUnknownTcpKey(
			Config.create().with("jsonrpc.tcp.port", "0").with("jsonrpc.tcp.socketSettings", "1"),
			"jsonrpc.tcp.socketSettings");
	}

	private static void assertRejectedAsUnknownTcpKey(Config config, String rejectedKey) throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(config);
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable ignored) {
				// observed through the start future below
			}
		}, "jsonrpc-tcp-unknown-key");
		thread.start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
			launcher.shutdown();
			fail("startup must fail, naming " + rejectedKey);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			assertTrue("the rejected key must be named: " + cause.getMessage(),
				cause.getMessage().contains(rejectedKey));
			assertTrue("the only supported TCP key must be named: " + cause.getMessage(),
				cause.getMessage().contains("the only TCP key is 'jsonrpc.tcp.port'"));
		} finally {
			thread.join(5000);
		}
	}

	// -------------------------------------------------------------------------------------------
	// F7 — port collision with an already-bound http.listenAddresses listener (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void tcpPortCollidingWithAnOccupiedHttpListenerFailsCleanlyAndRollsBack() throws Exception {
		// F7 (P1): the first time this launcher carries TWO independent listeners in one instance. The
		// occupant is a REAL launcher bound to :0 (ADR-028) — not getFreePort() — which is what
		// contracts/config-keys.md's own dispositif calls for; its actual bound port is then handed to a
		// SECOND launcher as jsonrpc.tcp.port. The second listener's listen() must fail with
		// BindException, the whole graph must roll back (including the second launcher's OWN http
		// listener, even if it raced to bind first), and the first launcher must be left completely
		// undisturbed.
		JsonRpcServerLauncher occupant = singleWorkerLauncher(Config.create());
		LauncherTestHarness.launch(occupant);
		try {
			int occupiedPort = occupant.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, occupiedPort);

			JsonRpcServerLauncher colliding = singleWorkerLauncher(
				Config.create().with("jsonrpc.tcp.port", String.valueOf(occupiedPort)));

			// unlike F2, this failure originates from startServices() — INSIDE Launcher.launch's inner
			// try/catch that completes onStartFuture exceptionally — so launch() throws synchronously on
			// the calling thread without ever reaching run()/awaitShutdown(); no background thread needed
			// (same shape as JsonRpcServerLauncherNetworkTest#occupiedPortFailsStartupWithBindErrorAndReleasesThePort).
			Exception e = assertThrows(Exception.class, () -> colliding.launch(Launcher.NO_ARGS));
			assertTrue("the failure must carry a BindException: " + e,
				causeChainHas(e, BindException.class));

			// rollback is clean: the SECOND launcher's own http listener, even if it won the start race,
			// ends up unbound too — the whole graph is torn down together, not left half-started
			assertTrue("the failed launcher's OWN http listener must have been rolled back too",
				colliding.httpServer.getBoundAddresses().isEmpty());

			// ... and the pre-existing occupant is completely undisturbed
			ReadResponse response = post(Reactor.getCurrentReactor(), occupiedPort, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));
		} finally {
			stop(occupant);
		}
	}

	// -------------------------------------------------------------------------------------------
	// F6 — stop then restart on the same numeric port (P2)
	// -------------------------------------------------------------------------------------------

	@Test
	public void restartingOnTheExactSamePortSucceedsAfterAFullStop() throws Exception {
		// F6 (P2): stop() must actually release the listener's file descriptor before it returns control
		// to the caller — proof: a SECOND launcher, configured on the EXACT numeric port the first one
		// was bound to, must bind cleanly with no BindException. JsonRpcTcpServerServiceAdapter delegates
		// stop() verbatim to ServiceAdapters.forReactiveServer(), the platform's own adapter — this pins
		// that the delegation actually drains the socket close before the service graph (and therefore
		// stop()) completes.
		JsonRpcServerLauncher launcher1 = singleWorkerLauncher(Config.create().with("jsonrpc.tcp.port", "0"));
		LauncherTestHarness.launch(launcher1);
		int tcpPort;
		try {
			tcpPort = tcpMountPort(launcher1);
			assertNotEquals("the kernel must have assigned a real port", 0, tcpPort);
			assertEquals(new User(1, "user-1"), await(tcpGetUser(tcpPort, 1)));
		} finally {
			stop(launcher1);
		}

		// the port is now free (proven, not assumed): a second, INDEPENDENT launcher binds it exactly
		JsonRpcServerLauncher launcher2 = singleWorkerLauncher(
			Config.create().with("jsonrpc.tcp.port", String.valueOf(tcpPort)));
		LauncherTestHarness.launch(launcher2);
		try {
			int rebindPort = tcpMountPort(launcher2);
			assertEquals("the second launcher must have landed on the EXACT SAME numeric port", tcpPort, rebindPort);

			User user = await(tcpGetUser(rebindPort, 2));
			assertEquals(new User(2, "user-2"), user);
		} finally {
			stop(launcher2);
		}
	}

	/** One framed-TCP call over a real connection, closed inside the awaited chain. */
	private static io.activej.promise.Promise<User> tcpGetUser(int port, long id) {
		NioReactor reactor = Reactor.getCurrentReactor();
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return client.proxy(UserApi.class).getUser(id)
					.whenComplete(() -> client.closeEx(new AsyncCloseException()));
			});
	}

	// -------------------------------------------------------------------------------------------
	// Shared harness
	// -------------------------------------------------------------------------------------------

	/** Every launcher built by {@link #singleWorkerLauncher(Config)} registers its {@link Injector} here. */
	private static final Map<JsonRpcServerLauncher, Injector> capturedInjectors = new ConcurrentHashMap<>();

	private static int tcpMountPort(JsonRpcServerLauncher launcher) {
		return capturedInjectors.get(launcher).getInstance(JsonRpcTcpMount.class)
			.listener().getBoundAddresses().get(0).getPort();
	}

	/** A single-worker launcher with the {@link UserApi} binding, a {@code :0} HTTP bind and {@code onFatalError} overridden. */
	private static JsonRpcServerLauncher singleWorkerLauncher(Config overrides) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				capturedInjectors.put(this, injector);
			}

			@Override
			Config config() {
				// :0 keeps the test off the 8080 default; overrides carry the test's jsonrpc.* keys
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	/** The fixture service binding every test launches with. */
	private static Module businessLogic() {
		return new AbstractModule() {
			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
			}
		};
	}
}
