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

import io.activej.async.callback.AsyncComputation;
import io.activej.async.exception.AsyncCloseException;
import io.activej.async.function.AsyncBiPredicate;
import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.BasicAuthServlet;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.inject.Injector;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.ws.JsonRpcWsServlet;
import io.activej.jsonrpc.transport.ws.JsonRpcWsSession;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import io.activej.worker.WorkerPool;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.initializers.Initializers.ofHttpServer;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * User story 6 — the launcher mounts the WebSocket endpoint beside HTTP POST (T022, US6, SC-006,
 * FR-100…FR-105): one launcher instance answers a WebSocket call on {@code jsonrpc.ws.path} (default
 * {@code /ws}) <b>and</b> a POST on {@code jsonrpc.path}, on one server bound to {@code :0} and asked
 * where it landed ({@code getBoundAddresses()}, ADR-028 — never {@code getFreePort}).
 * <p>
 * <b>Co-mount (SC-006).</b> The WS endpoint dispatches through the launcher's own
 * {@code JsonRpcDispatcher} to the same {@link UserApi} binding the POST path answers — the client
 * drives a real WebSocket upgrade ({@link JsonRpcWsTransport#connect}) over a real {@link HttpClient}
 * and issues {@code user.get} through a {@link JsonRpcClient} on top of it.
 * <p>
 * <b>Disabled mount (FR-102, CHK050).</b> {@code jsonrpc.ws.path=} empty must disable the endpoint
 * cleanly: no {@code JsonRpcWsServlet} is constructed (the mount is guarded — the empty form builds
 * nothing, sidestepping {@code IWebSocket.ENABLED}'s {@code checkState} in the {@code WebSocketServlet}
 * constructor, research R9), so the path 404s. The observable form asserted here: a real WebSocket
 * upgrade to the path is refused ({@code HANDSHAKE_FAILED} — the route does not exist), while the POST
 * route answers unchanged.
 * <p>
 * <b>Unknown {@code jsonrpc.ws.*} key (US6 scenario 4, FR-101).</b> The fail-closed {@code rejectNonKeys}
 * check admits exactly {@code ws.path} and rejects every other key under {@code jsonrpc.ws.*} at
 * startup, naming the offending key. A <b>scalar</b> {@code jsonrpc.ws} value — a plausible typo for
 * the one real key — carries no children for the key loop to find, so the check rejects the scalar
 * node itself: the typo fails startup instead of silently mounting the default.
 * <p>
 * <b>The mounted servlet is injectable.</b> {@code JsonRpcWsServlet} is an ordinary DI binding of the
 * launcher modules (per-worker in the multithreaded launcher): the application injects it — or
 * retrieves each worker's through {@code WorkerPool.getInstances} — to reach {@code sessions()},
 * {@code broadcast(...)} and the per-session server-initiated clients. The binding is resolved
 * lazily and only on a non-empty {@code jsonrpc.ws.path}, so a disabled endpoint constructs nothing
 * at startup (a lookup while disabled yields an unmounted, always-empty servlet — pinned).
 * <p>
 * <b>Per-worker mount (US6 scenario 3, FR-103).</b> The worker-pool launcher mounts the route per
 * worker; a WS call over the {@code PrimaryServer}'s shared accept socket is answered by whichever
 * worker accepted the connection — its own servlet, dispatcher and session registry (cross-worker
 * broadcast is out of scope, documented).
 * <p>
 * <b>Non-key through the properties file (F1, adversarial plan).</b> The same fail-closed rejection is
 * reached through {@code jsonrpc-server.properties} on the classpath, not only through
 * {@code -Dconfig.*} — an operator who wrote {@code jsonrpc.ws.maxMessageSize=10mb} into the file gets
 * the same loud failure, naming the key and the one key that does exist.
 * <p>
 * <b>Disabled mount through the properties file (F3, adversarial plan).</b> The same clean disable is
 * reached through an empty {@code jsonrpc.ws.path} in {@code jsonrpc-server.properties} — the file path
 * and the programmatic-{@code Config} path produce the same refused upgrade and the same untouched POST.
 * <p>
 * <b>Per-worker registry isolation (F4, adversarial plan).</b> Six connections over the shared
 * {@code PrimaryServer} accept socket are distributed round-robin across two workers; every one of them
 * makes a working call, and the two workers' {@code JsonRpcWsServlet.sessions()} registries are strictly
 * disjoint.
 * <p>
 * <b>Hostile path values (F2, adversarial plan).</b> Seven hostile {@code jsonrpc.ws.path} values are
 * launched one by one and their actual routing behaviour pinned — mounted, or refused by a clean named
 * exception; never an NPE, never a hang. The observed table is in that test's Javadoc.
 * <p>
 * <b>Idle session under a disabled sweep (F5, adversarial plan).</b> With {@code http.readWriteTimeout=0}
 * the host server's expired-connections sweep never touches the read/write pools, so an upgraded session
 * survives silence and keeps answering. Bounded and behavioural, never chronometric (WI-17) — see that
 * test's comment for the scope limitation.
 * <p>
 * <b>A composable admission gate (F6, adversarial plan).</b> The root servlet the launcher provides is an
 * ordinary DI binding, so an operator can wrap it: {@code getOverrideModule()} rebinds the
 * {@link HttpServer} onto a {@link BasicAuthServlet} in front of the launcher's own, unchanged
 * {@code AsyncServlet}. An unauthenticated upgrade is refused before any session exists; an authenticated
 * one works normally.
 * <p>
 * <b>Co-mount route ordering (F7, adversarial plan).</b> An ordinary POST to the WebSocket path — where
 * only the WebSocket ordinal is mapped — is answered deterministically (404), with both legitimate routes
 * left answering; no ambiguity between the POST route and the WebSocket route.
 * <p>
 * The existing {@code testInjector()} smoke tests of both launchers stay green unchanged (FR-105);
 * every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * kills the Surefire JVM).
 */
public class JsonRpcWsMountTest {
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

	/**
	 * F1's properties-file harness, copied from {@code JsonRpcServerLauncherPropertiesRejectionTest}: a
	 * {@code jsonrpc-server.properties} carrying the offending key, placed on a dedicated
	 * {@link URLClassLoader} that only the F1 test installs as the launching thread's context class
	 * loader — {@code Config.ofClassPathProperties(PROPERTIES_FILE, true)} resolves the file through
	 * exactly that loader, so the rest of the suite never sees it.
	 */
	private Path propsDir;
	private URLClassLoader loader;
	private ClassLoader previousContextClassLoader;

	@Before
	public void setUpPropertiesFile() throws Exception {
		propsDir = Files.createTempDirectory("jsonrpc-ws-props");
		Files.writeString(propsDir.resolve("jsonrpc-server.properties"), "jsonrpc.ws.maxMessageSize=10mb\n");
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
	public void webSocketAndPostAnswerOnTheSameServer() throws Exception {
		// SC-006 / FR-100, FR-105: ONE launcher instance, one server, both transports answering —
		// a WS call on the default /ws and a POST on the default /, both reaching UserApi.get.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			ReadResponse response = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));

			User user = await(wsGetUser(Reactor.getCurrentReactor(), port, "/ws", 42));
			assertEquals(new User(42, "user-42"), user);
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void disabledMountRefusesTheUpgradeAndLeavesPostUntouched() throws Exception {
		// FR-102: jsonrpc.ws.path= disables the endpoint. The observable form of "no JsonRpcWsServlet
		// constructed" (CHK050): a real WebSocket upgrade to /ws is refused (HANDSHAKE_FAILED — the
		// route does not exist, so RoutingServlet 404s it), while the POST route answers unchanged.
		// Had a servlet been constructed and mounted, the upgrade would have succeeded.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create().with("jsonrpc.ws.path", ""));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			ReadResponse response = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));

			HttpClient httpClient = HttpClient.create(Reactor.getCurrentReactor(),
				DnsClient.create(Reactor.getCurrentReactor(), inetAddress("8.8.8.8")));
			Exception e = awaitException(JsonRpcWsTransport.connect(Reactor.getCurrentReactor(), httpClient,
				HttpRequest.get("ws://127.0.0.1:" + port + "/ws").build()));
			assertEquals("the disabled path must refuse the upgrade (404 -> HANDSHAKE_FAILED)",
				"Failed to perform a proper opening handshake", e.getMessage());
		} finally {
			stop(launcher);
		}
	}

	@After
	public void clearWsKeyProperty() {
		System.clearProperty("config.jsonrpc.ws.bogus");
	}

	@Test
	public void unknownWebSocketKeyFailsStartupNamingTheKey() throws Exception {
		// US6 scenario 4 / FR-101: a key under jsonrpc.ws.* other than path is a non-key — the
		// fail-closed check rejects it at startup, naming the exact key (contracts/config-keys.md).
		// The launcher runs on its own thread and is driven by the start future, so the failing-first
		// state (the key NOT rejected, the launcher happily running) is observed and torn down instead
		// of hanging the JVM in awaitShutdown().
		System.setProperty("config.jsonrpc.ws.bogus", "1");

		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable ignored) {
				// the launch failure is observed through the start future below
			}
		});
		thread.start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
			// startup succeeded — the key was NOT rejected (the failing-first state before T023)
			launcher.shutdown();
			fail("startup must fail, naming jsonrpc.ws.bogus");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			assertTrue("the rejected key must be named: " + cause.getMessage(),
				cause.getMessage().contains("jsonrpc.ws.bogus"));
		} finally {
			thread.join(5000);
		}
	}

	@Test
	public void scalarWebSocketKeyFailsStartupNamingTheKey() throws Exception {
		// US6 scenario 4 follow-up: a SCALAR jsonrpc.ws (a plausible typo for jsonrpc.ws.path) carries
		// no children, so the child-key loop alone would walk an empty map and silently mount the
		// default /ws — the fail-closed check rejects the scalar node itself, naming the one key that
		// exists. Same thread-harness shape as unknownWebSocketKeyFailsStartupNamingTheKey.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create().with("jsonrpc.ws", "/ws"));
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable ignored) {
				// the launch failure is observed through the start future below
			}
		});
		thread.start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
			// startup succeeded — the scalar was NOT rejected
			launcher.shutdown();
			fail("startup must fail, naming jsonrpc.ws");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			assertTrue("the rejected key must be named: " + cause.getMessage(),
				cause.getMessage().contains("'jsonrpc.ws'"));
			assertTrue("the only supported WebSocket key must be named: " + cause.getMessage(),
				cause.getMessage().contains("the only WebSocket key is 'jsonrpc.ws.path'"));
		} finally {
			thread.join(5000);
		}
	}

	@Test
	public void unknownWebSocketKeyInAPropertiesFileFailsStartupNamingTheKey() {
		// F1, adversarial plan (P0): the FR-101 fail-closed check must also fire when the non-key arrives
		// through the PROPERTIES FILE (jsonrpc-server.properties on the classpath), not only through the
		// -Dconfig.* system properties the test above covers. An operator who wrote
		// jsonrpc.ws.maxMessageSize=10mb into the file believing they had bounded WebSocket frames must
		// get the same loud startup failure, naming the key and the one key that does exist.
		Thread.currentThread().setContextClassLoader(loader);

		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> launcher.launch(Launcher.NO_ARGS));
		assertTrue("the rejected key must be named: " + e.getMessage(),
			e.getMessage().contains("jsonrpc.ws.maxMessageSize"));
		assertTrue("the only supported WebSocket key must be named: " + e.getMessage(),
			e.getMessage().contains("the only WebSocket key is 'jsonrpc.ws.path'"));
	}

	@Test
	public void anEmptyWebSocketPathInAPropertiesFileDisablesTheMount() throws Exception {
		// F3, adversarial plan (P1): FR-102's clean disable reached through the PROPERTIES FILE, not only
		// through the programmatic Config of disabledMountRefusesTheUpgradeAndLeavesPostUntouched. An
		// operator who wrote `jsonrpc.ws.path=` (an empty value) into jsonrpc-server.properties must get
		// exactly the same behaviour: no JsonRpcWsServlet is constructed, so a real upgrade to the default
		// /ws is refused (HANDSHAKE_FAILED), while the POST route answers unchanged.
		Files.writeString(propsDir.resolve("jsonrpc-server.properties"), "jsonrpc.ws.path=\n");
		// the launcher thread LauncherTestHarness spawns inherits this context class loader at
		// construction — and that is what Config.ofClassPathProperties(PROPERTIES_FILE, true) resolves through
		Thread.currentThread().setContextClassLoader(loader);

		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			ReadResponse response = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));

			HttpClient httpClient = HttpClient.create(Reactor.getCurrentReactor(),
				DnsClient.create(Reactor.getCurrentReactor(), inetAddress("8.8.8.8")));
			// the success branch closes the transport so that a regression (the mount left enabled) fails
			// this assertion instead of hanging await() on a connection that never quiesces
			Exception e = awaitException(JsonRpcWsTransport.connect(Reactor.getCurrentReactor(), httpClient,
					HttpRequest.get("ws://127.0.0.1:" + port + "/ws").build())
				.whenResult(transport -> transport.closeEx(new AsyncCloseException())));
			assertEquals("an empty jsonrpc.ws.path from the properties file must disable the mount exactly " +
				"like the programmatic one (404 -> HANDSHAKE_FAILED)",
				"Failed to perform a proper opening handshake", e.getMessage());
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void theWsServletBindingIsUnmountedWhenTheEndpointIsDisabled() throws Exception {
		// FR-102 follow-up: with jsonrpc.ws.path empty the launcher mounts nothing — and a
		// JsonRpcWsServlet lookup then yields an UNMOUNTED servlet (the provider cannot re-check
		// jsonrpc.ws.path at lookup time: Config is readable during startup only). Its registry is
		// empty and stays so; the endpoint behaviour — a refused upgrade, an untouched POST route —
		// is pinned by disabledMountRefusesTheUpgradeAndLeavesPostUntouched.
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				injectorRef.set(injector);
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("jsonrpc.ws.path", ""));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		LauncherTestHarness.launch(launcher);
		try {
			JsonRpcWsServlet wsServlet = injectorRef.get().getInstance(JsonRpcWsServlet.class);
			assertTrue("a looked-up-but-unmounted servlet's registry is empty",
				await(sessionsOf(launcher.httpServer.getReactor(), wsServlet)).isEmpty());
		} finally {
			stop(launcher);
		}
	}

	/**
	 * F2, adversarial plan (P0) — hostile {@code jsonrpc.ws.path} values. Every outcome below was
	 * <b>observed</b> by this test (it prints the table on every run), not inferred from the router's
	 * source. {@code jsonrpc.path} is moved to {@code /post} throughout, so the POST route never
	 * collides with the WebSocket path under test.
	 * <pre>
	 * value                     launch     upgrade to that literal path
	 * ------------------------  ---------  ----------------------------------------------------------
	 * "/"                       starts     OK — WS on "/" co-mounts with POST on "/post"
	 * "//"                      starts     OK — the empty middle segment is a route of its own
	 * "*"                       REFUSED    DIException &lt;- IllegalArgumentException "Invalid path: *"
	 *                                      (RoutingServlet.doMap: a path must start with '/', and may
	 *                                      contain '*' only as the trailing "/*" wildcard)
	 * "/:param"                 starts     OK — but it is a PARAMETER route, not a literal one: the
	 *                                      mount also answers "/some-other-segment" (asserted below)
	 * "ws" (no leading slash)   REFUSED    DIException &lt;- IllegalArgumentException "Invalid path: ws"
	 * "/" + 100_000 * 'a'       starts     not attempted — the mount is accepted, but the CLIENT cannot
	 *                                      express the request: HttpRequest.get throws
	 *                                      IllegalArgumentException &lt;- MalformedHttpException
	 *                                      "URL length exceeds 32767 bytes"
	 * "../"                     REFUSED    DIException &lt;- IllegalArgumentException "Invalid path: ../"
	 *                                      (no leading '/'; path traversal never reaches the router)
	 * </pre>
	 * No value produced a {@code NullPointerException}, a hang, or an exception escaping an unrelated
	 * layer: every refusal is the router's own {@code checkArgument}, named and reported at wiring time
	 * (so {@code Launcher.launch} rethrows it before the start future is ever completed — the probe
	 * polls both, which is why a refusal costs milliseconds rather than the harness' 10s timeout).
	 */
	@Test
	public void hostileWebSocketPathsAreMountedOrRefusedCleanly() throws Exception {
		// F2, adversarial plan: every value is either mounted (and the upgrade then succeeds or is
		// refused by the router) or rejected by a CLEAN, named exception — never an NPE, never a hang.
		// jsonrpc.path is moved to /post so the POST route cannot collide with the ws path under test,
		// except for the deliberate "/" case, which tests co-mounting POST and WS on distinct paths.
		Map<String, String> observed = new LinkedHashMap<>();
		for (String wsPath : List.of("/", "//", "*", "/:param", "ws", "/" + "a".repeat(100_000), "../")) {
			String outcome = probeHostileWsPath(wsPath);
			System.out.println("F2 [" + summarize(wsPath) + "] -> " + outcome);
			observed.put(wsPath, outcome);
		}

		// '/:param' is not a literal route: RoutingServlet files ':param' in its `parameters` map, so the
		// mount matches ANY single segment. Observed here rather than inferred — an upgrade to a path
		// the operator never wrote succeeds, which is the routing ambiguity F2 asks about.
		String parameterRoute = probeHostileWsPath("/:param", "/some-other-segment");
		System.out.println("F2 [/:param mounted, /some-other-segment probed] -> " + parameterRoute);
		observed.put("/:param (probed at /some-other-segment)", parameterRoute);
		assertTrue("a ':param' mount is a parameter route matching any segment: " + parameterRoute,
			parameterRoute.startsWith("launched; upgrade OK"));

		observed.forEach((path, outcome) -> {
			assertFalse("unclean crash for jsonrpc.ws.path=" + summarize(path) + ": " + outcome,
				outcome.contains("NullPointerException"));
			assertFalse("startup neither completed nor failed for jsonrpc.ws.path=" + summarize(path),
				outcome.startsWith("TIMEOUT"));
		});
	}

	/**
	 * Launches one launcher with {@code jsonrpc.ws.path=wsPath}, and reports what happened as a single
	 * line: either the launch was refused (with the exception chain that refused it), or it started and
	 * a real WebSocket upgrade to that literal path was attempted. The launcher is always stopped, and
	 * its JMX bean always dropped, before the caller moves to the next value.
	 */
	private static String probeHostileWsPath(String wsPath) throws Exception {
		return probeHostileWsPath(wsPath, wsPath);
	}

	private static String probeHostileWsPath(String wsPath, String probePath) throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.path", "/post")
			.with("jsonrpc.ws.path", wsPath));
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "hostile-ws-path");
		thread.start();

		// a refusal raised while the injector creates the servlet never completes the start future
		// (Launcher.launch rethrows it before the try that completes it), so both are polled
		CompletableFuture<Void> startFuture = launcher.getStartFuture().toCompletableFuture();
		long deadline = System.currentTimeMillis() + 10_000;
		while (!startFuture.isDone() && failure.get() == null && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		if (!startFuture.isDone()) {
			Throwable t = failure.get();
			if (t == null) {
				launcher.shutdown();
				thread.join(5000);
				return "TIMEOUT: neither started nor failed within 10s";
			}
			thread.join(5000);
			return "launch refused: " + describe(t);
		}
		try {
			startFuture.get();
		} catch (ExecutionException e) {
			thread.join(5000);
			return "launch refused at onStart: " + describe(e.getCause());
		}
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			return await(wsGetUser(Reactor.getCurrentReactor(), port, probePath, 42)
				.map(user -> "launched; upgrade OK, call returned " + user,
					e -> "launched; upgrade refused: " + e.getClass().getSimpleName() + ": " + summarize(String.valueOf(e.getMessage()))));
		} catch (RuntimeException e) {
			// the upgrade could not even be expressed as a request — still a clean, named refusal
			return "launched; upgrade not attempted: " + describe(e);
		} finally {
			stop(launcher);
			unregisterDispatcherBeans();
			thread.join(5000);
		}
	}

	/** The whole cause chain, so a wrapped refusal is still readable as a named exception. */
	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable current = t; current != null; current = current.getCause()) {
			if (!sb.isEmpty()) sb.append(" <- ");
			sb.append(current.getClass().getSimpleName()).append(": ").append(summarize(String.valueOf(current.getMessage())));
		}
		return sb.toString();
	}

	private static String summarize(String s) {
		return s.length() > 60 ? s.substring(0, 40) + "...(" + s.length() + " chars)" : s;
	}

	@Test
	public void multithreadedLauncherAnswersTheWebSocketCallPerWorker() throws Exception {
		// US6 scenario 3 / FR-103: the worker-pool launcher mounts the WS route per worker; a WS call
		// over the PrimaryServer's shared accept socket is answered by whichever worker accepted it.
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "2"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			User user = await(wsGetUser(Reactor.getCurrentReactor(), port, "/ws", 7));
			assertEquals(new User(7, "user-7"), user);
		} finally {
			stop(launcher);
		}
	}

	/** How many connections F4 opens over the shared accept socket — round-robin over two workers. */
	private static final int F4_CONNECTIONS = 6;

	@Test
	public void perWorkerSessionRegistriesAreIsolated() throws Exception {
		// F4, adversarial plan (P1): FR-103 — each worker owns its OWN JsonRpcWsServlet, dispatcher and
		// session registry. Six connections over the PrimaryServer's shared accept socket (distributed
		// round-robin, PrimaryServer.getNextWorkerServer) each make a working user.get call — whichever
		// worker accepted a connection answers it — and the two workers' registries are strictly disjoint:
		// a session registered in worker A is invisible from worker B (cross-worker broadcast is out of
		// scope, documented). The registries inspected are the LAUNCHER'S OWN: no override module and no
		// copied provider — the mounted servlets are retrieved through the DI binding itself
		// (WorkerPool.getInstances), which is exactly what an application reaching for
		// sessions()/broadcast(...) would do.
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		MultithreadedJsonRpcServerLauncher launcher = new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				injectorRef.set(injector);
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "2"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			WorkerPool workerPool = injectorRef.get().getInstance(WorkerPool.class);
			// the mounted servlet is an ordinary @Worker binding — the public path to per-worker
			// sessions()/broadcast(...), no reflection into RoutingServlet required
			List<JsonRpcWsServlet> wsServlets = workerPool.getInstances(JsonRpcWsServlet.class).getList();
			List<NioReactor> workerReactors = workerPool.getInstances(NioReactor.class).getList();
			assertEquals("the pool must hold the two configured workers", 2, wsServlets.size());
			assertNotSame("each worker must own its own JsonRpcWsServlet instance (@Worker scope)",
				wsServlets.get(0), wsServlets.get(1));

			NioReactor reactor = Reactor.getCurrentReactor();
			HttpClient httpClient = HttpClient.create(reactor,
				DnsClient.create(reactor, inetAddress("8.8.8.8")));
			Observed observed = await(openSessionsAndSnapshot(reactor, httpClient, port, workerReactors, wsServlets));

			for (int i = 0; i < F4_CONNECTIONS; i++) {
				assertEquals("connection " + i + " must be answered by whichever worker accepted it",
					new User(i, "user-" + i), observed.users().get(i));
			}

			Set<JsonRpcWsSession> workerA = observed.registries().get(0);
			Set<JsonRpcWsSession> workerB = observed.registries().get(1);
			assertEquals("every open connection is registered exactly once, in exactly one worker",
				F4_CONNECTIONS, workerA.size() + workerB.size());
			assertFalse("round-robin must have landed at least one connection on worker 0", workerA.isEmpty());
			assertFalse("round-robin must have landed at least one connection on worker 1", workerB.isEmpty());
			assertTrue("the registries are strictly per-worker — no session is visible from the other " +
				"worker (FR-103): A=" + workerA + " B=" + workerB,
				Collections.disjoint(workerA, workerB));
		} finally {
			stop(launcher);
		}
	}

	/** What F4 observed while all {@link #F4_CONNECTIONS} connections were open. */
	private record Observed(List<User> users, List<Set<JsonRpcWsSession>> registries) {}

	/**
	 * Opens {@link #F4_CONNECTIONS} WebSocket connections at once, issues one {@code user.get} per
	 * connection, snapshots every worker's session registry <b>while they are all still open</b>, and only
	 * then closes them — the close is inside the awaited chain, which is what lets {@code TestUtils.await}
	 * reach eventloop quiescence (R3).
	 */
	private static Promise<Observed> openSessionsAndSnapshot(
		NioReactor reactor, HttpClient httpClient, int port,
		List<NioReactor> workerReactors, List<JsonRpcWsServlet> wsServlets
	) {
		List<Promise<JsonRpcWsTransport>> connects = new ArrayList<>();
		for (int i = 0; i < F4_CONNECTIONS; i++) {
			connects.add(JsonRpcWsTransport.connect(reactor, httpClient,
				HttpRequest.get("ws://127.0.0.1:" + port + "/ws").build()));
		}
		return Promises.toList(connects)
			.then(transports -> {
				List<Promise<User>> calls = new ArrayList<>();
				for (int i = 0; i < transports.size(); i++) {
					calls.add(JsonRpcClient.builder(reactor, transports.get(i)).build()
						.proxy(UserApi.class).getUser(i));
				}
				return Promises.toList(calls)
					.then(users -> snapshotRegistries(workerReactors, wsServlets)
						.map(registries -> new Observed(users, registries)))
					.whenComplete(() -> transports.forEach(transport -> transport.closeEx(new AsyncCloseException())));
			});
	}

	/**
	 * One {@code sessions()} snapshot per worker. {@code sessions()} is reactor-confined (FR-033/FR-037),
	 * so it is read <b>on the worker's own eventloop thread</b> and the immutable snapshot handed back
	 * through the future — {@code Promise.ofCompletionStage} keeps the test loop alive across the hop.
	 */
	private static Promise<List<Set<JsonRpcWsSession>>> snapshotRegistries(
		List<NioReactor> workerReactors, List<JsonRpcWsServlet> wsServlets
	) {
		List<Promise<Set<JsonRpcWsSession>>> snapshots = new ArrayList<>();
		for (int i = 0; i < wsServlets.size(); i++) {
			JsonRpcWsServlet wsServlet = wsServlets.get(i);
			snapshots.add(Promise.ofCompletionStage(((Eventloop) workerReactors.get(i))
				.submit(AsyncComputation.of(wsServlet::sessions))));
		}
		return Promises.toList(snapshots);
	}

	/** The bounded idle window F5 keeps a session silent for — see that test for why it is bounded. */
	private static final Duration F5_IDLE_WINDOW = Duration.ofMillis(1200);

	@Test
	public void anIdleWebSocketSessionSurvivesSilenceWhenTheSweepIsDisabled() throws Exception {
		// F5, adversarial plan (P1): FR-096/FR-106 — with http.readWriteTimeout=0 the host server's
		// expired-connections sweep skips the read/write pools entirely (HttpServer.
		// scheduleExpiredConnectionsCheck: `if (readWriteTimeoutMillis != 0 || isClosing)`), so an
		// upgraded WebSocket session survives silence and keeps answering.
		//
		// SCOPE LIMITATION (WI-17). This is a BOUNDED, BEHAVIOURAL proof, not a literal-60s one: no
		// absolute-time assertion is made and no 60-second wait is performed. The idle window is 1200 ms,
		// which crosses at least one tick of the server's 1000 ms sweep — that is what keeps it
		// non-vacuous: an accidentally-small effective timeout (or a sweep that ran despite the 0) would
		// have closed the session inside that window. It does NOT prove the 60 s default itself.
		//
		// NOTE — the inherited default is NOT 0: Initializers.ofHttpWorker defaults http.readWriteTimeout
		// to HttpServer.READ_WRITE_TIMEOUT (60 s), so a long-lived deployment must set the key, exactly as
		// this module's README instructs (FR-106). It is set here explicitly, and the value actually in
		// force on the built server is asserted rather than assumed.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("http.readWriteTimeout", "0 seconds"));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertEquals("the configured 0 must be the value in force on the host server",
				Duration.ZERO, launcher.httpServer.getReadWriteTimeout());

			List<User> users = await(wsCallIdleCall(Reactor.getCurrentReactor(), port, "/ws", F5_IDLE_WINDOW));
			assertEquals("the call before the silence must be answered", new User(1, "user-1"), users.get(0));
			assertEquals("the same session must still answer after the silence — the sweep did not tear " +
				"it down", new User(2, "user-2"), users.get(1));
		} finally {
			stop(launcher);
		}
	}

	/**
	 * Opens one WebSocket session, issues a call, keeps the session <b>silent</b> for {@code idle} (no
	 * frames in either direction), then issues a second call on the same session and closes it. The whole
	 * sequence — delay included — lives inside the awaited chain, which is what lets
	 * {@code TestUtils.await} reach eventloop quiescence (R3).
	 */
	private static Promise<List<User>> wsCallIdleCall(NioReactor reactor, int port, String path, Duration idle) {
		HttpClient httpClient = HttpClient.create(reactor,
			DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient,
				HttpRequest.get("ws://127.0.0.1:" + port + path).build())
			.then(transport -> {
				UserApi api = JsonRpcClient.builder(reactor, transport).build().proxy(UserApi.class);
				return api.getUser(1)
					.then(before -> Promises.delay(idle)
						.then(() -> api.getUser(2))
						.map(after -> List.of(before, after)))
					.whenComplete(() -> transport.closeEx(new AsyncCloseException()));
			});
	}

	private static final String F6_USER = "operator";
	private static final String F6_PASSWORD = "s3cret";

	@Test
	public void theAdmissionGateStaysComposableInTheLauncher() throws Exception {
		// F6, adversarial plan (P2): FR-036's admission gate must stay composable once the endpoint is
		// mounted by the launcher — an operator can put a BasicAuthServlet in front of the co-mounted
		// route. APPROACH: the DI route, through the launcher's own override hook. JsonRpcModule's
		// rootServlet(...) is an ordinary @Provides, so it cannot be replaced by a binding that depends on
		// itself; the wrap is therefore applied where the servlet is CONSUMED — getOverrideModule() rebinds
		// HttpServer onto BasicAuthServlet(launcher's unchanged rootServlet), the same decorate-a-binding
		// shape as DecoratedFileSystemExample and HttpReactiveWorkerServerTest's override module. The
		// launcher's own routing (POST route + WebSocket mount) is reused verbatim — nothing is rebuilt by
		// hand, which is exactly the composability claim under test.
		AtomicReference<NioReactor> serverReactor = new AtomicReference<>();
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		BiPredicate<String, String> credentials = BasicAuthServlet.lookupFrom(Map.of(F6_USER, F6_PASSWORD));

		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				injectorRef.set(injector);
			}

			@Override
			protected Module getOverrideModule() {
				return new AbstractModule() {
					@Provides
					HttpServer server(NioReactor reactor, AsyncServlet rootServlet, Config config) {
						serverReactor.set(reactor);
						return HttpServer.builder(reactor,
								BasicAuthServlet.create(reactor, rootServlet, "jsonrpc",
									AsyncBiPredicate.of(credentials::test)))
							.initialize(ofHttpServer(config.getChild("http")))
							.build();
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
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			NioReactor reactor = Reactor.getCurrentReactor();
			HttpClient httpClient = HttpClient.create(reactor,
				DnsClient.create(reactor, inetAddress("8.8.8.8")));

			// unauthenticated: the gate answers 401 and the upgrade never reaches the WebSocket servlet
			Exception e = awaitException(JsonRpcWsTransport.connect(reactor, httpClient,
					HttpRequest.get("ws://127.0.0.1:" + port + "/ws").build())
				.whenResult(transport -> transport.closeEx(new AsyncCloseException())));
			assertEquals("an unauthenticated upgrade must be refused (401 -> HANDSHAKE_FAILED)",
				"Failed to perform a proper opening handshake", e.getMessage());

			// ... and it was refused BEFORE any session existed: the mounted servlet IS the DI
			// binding, so its registry is read straight from the injector — on the server's reactor
			// thread (sessions() is reactor-confined, FR-037)
			JsonRpcWsServlet wsServlet = injectorRef.get().getInstance(JsonRpcWsServlet.class);
			assertTrue("a refused upgrade must leave no session behind (FR-036)",
				await(sessionsOf(serverReactor.get(), wsServlet)).isEmpty());

			// authenticated: the very same mount works normally — a real user.get over a real session
			User user = await(wsGetUser(reactor, port, "/ws", 42,
				builder -> builder.withHeader(HttpHeaders.AUTHORIZATION, basicAuth(F6_USER, F6_PASSWORD))));
			assertEquals(new User(42, "user-42"), user);
		} finally {
			stop(launcher);
		}
	}

	private static String basicAuth(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(UTF_8));
	}

	/** One {@code sessions()} snapshot, read on the servlet's own reactor thread (FR-033/FR-037). */
	private static Promise<Set<JsonRpcWsSession>> sessionsOf(Reactor reactor, JsonRpcWsServlet wsServlet) {
		return Promise.ofCompletionStage(((Eventloop) reactor).submit(AsyncComputation.of(wsServlet::sessions)));
	}

	@Test
	public void aPostToTheWebSocketPathIs404AndLeavesBothRoutesAnswering() throws Exception {
		// F7, adversarial plan (P2): the co-mounted path receives the "wrong" kind of request — an ordinary
		// HTTP POST to jsonrpc.ws.path (the default /ws), where only the WebSocket ordinal is mapped (the
		// POST route lives at jsonrpc.path, the default "/"). OBSERVED and pinned here, not inferred:
		// RoutingServlet walks into the child route for "ws", finds no servlet for the POST ordinal and no
		// ANY_HTTP fallback there, does not fall back to the "/" POST route, and answers its own 404 —
		// the default HttpExceptionFormatter's HTML page, titled "404. Not Found" (printed on every run).
		// No ambiguity, no crash — and both legitimate routes keep answering afterwards on the same server.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create());
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			NioReactor reactor = Reactor.getCurrentReactor();

			ReadResponse wrongKind = post(reactor, port, "/ws", POST_DOCUMENT);
			System.out.println("F7 [POST /ws] -> " + wrongKind.code() + " " + summarize(wrongKind.body()));
			assertEquals("a POST to the WebSocket path must be a clean 404 from RoutingServlet",
				404, wrongKind.code());
			assertFalse("the WebSocket path must NOT answer the POST document through the POST route",
				wrongKind.bodyContains(POST_RESULT));
			assertTrue("the observed body is core-http's own 404 page: " + summarize(wrongKind.body()),
				wrongKind.bodyContains("404. Not Found"));

			// the two legitimate routes are untouched by the misdirected request
			ReadResponse response = post(reactor, port, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));

			User user = await(wsGetUser(reactor, port, "/ws", 42));
			assertEquals(new User(42, "user-42"), user);
		} finally {
			stop(launcher);
		}
	}

	/** A single-worker launcher with the {@link UserApi} binding, a {@code :0} bind and {@code onFatalError} overridden. */
	private static JsonRpcServerLauncher singleWorkerLauncher(Config overrides) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
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

	/**
	 * Connects a real WebSocket client to {@code path} on {@code port} and issues one {@code user.get}
	 * call through the launcher's dispatcher; closes the connection once the result (or failure) is in.
	 * The close happens inside the awaited chain — that is what lets {@code TestUtils.await} reach
	 * eventloop quiescence (R3: an open connection keeps {@code Eventloop.isAlive()} true).
	 */
	private static Promise<User> wsGetUser(NioReactor reactor, int port, String path, long id) {
		return wsGetUser(reactor, port, path, id, UnaryOperator.identity());
	}

	/** As above, with the upgrade request decorated — F6 uses it to carry the {@code Authorization} header. */
	private static Promise<User> wsGetUser(
		NioReactor reactor, int port, String path, long id, UnaryOperator<HttpRequest.Builder> decorator
	) {
		HttpClient httpClient = HttpClient.create(reactor,
			DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient,
				decorator.apply(HttpRequest.get("ws://127.0.0.1:" + port + path)).build())
			.then(transport -> JsonRpcClient.builder(reactor, transport).build()
				.proxy(UserApi.class).getUser(id)
				.whenComplete(() -> transport.closeEx(new AsyncCloseException())));
	}
}
