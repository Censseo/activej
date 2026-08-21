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
import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.inject.Injector;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpSession;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.net.PrimaryServer;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import io.activej.worker.WorkerPool;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.assertNothingListens;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * User story 6 — the launcher opens the framed-TCP listener beside HTTP POST and WebSocket (T025, US6,
 * SC-006, FR-100…FR-105). Every server binds {@code :0} and is asked where it landed
 * ({@code getBoundAddresses()}, ADR-028 — never {@code getFreePort}).
 * <p>
 * <b>Disabled by default (FR-102).</b> This is the mount's headline asymmetry with
 * {@code jsonrpc.ws.path}, which defaults to {@code /ws}: a WebSocket route rides the HTTP listener that
 * already exists, whereas a TCP port <b>opens a new socket</b> — plaintext and unauthenticated by design
 * — so opening it is an explicit deployment decision. The observable form asserted here is the strongest
 * available: with the key absent, {@code Injector.peekInstance(JsonRpcTcpServer.class)} is {@code null},
 * i.e. <b>no server object exists at all</b>, so there is nothing that could hold a socket — while the
 * POST route answers exactly as before, and nothing is left listening once the launcher stops.
 * <p>
 * <b>Co-mount (SC-006, FR-105).</b> With {@code jsonrpc.tcp.port=0} <i>one</i> launcher instance answers
 * a real framed-TCP call on its own listener, an HTTP POST on {@code jsonrpc.path} and a WebSocket call
 * on {@code jsonrpc.ws.path} — three transports, one dispatcher, one {@link UserApi} binding. The TCP
 * listener is a second, distinct socket: its port is asserted to differ from the HTTP one.
 * <p>
 * <b>Unrecognised {@code jsonrpc.tcp.*} key (FR-101).</b> The fail-closed {@code rejectNonKeys} check
 * admits exactly {@code tcp.port}; anything else under {@code jsonrpc.tcp.*} fails startup naming the
 * offending key, and so does a <b>scalar</b> {@code jsonrpc.tcp} value — the plausible typo for the one
 * real key, which carries no children for the key loop to find and would otherwise leave the endpoint
 * silently off.
 * <p>
 * <b>Per-worker mount (FR-103).</b> The worker-pool launcher accepts on the <b>primary</b> reactor's
 * {@link PrimaryServer} and hands each accepted channel to a per-worker {@link JsonRpcTcpServer}: six
 * connections over the one accept socket all make working calls, and the two workers'
 * {@code sessions()} registries are strictly disjoint — each sees only the connections its own worker
 * accepted (cross-worker broadcast is out of scope, documented).
 * <p>
 * <b>Shutdown drains a live session.</b> A mount is not merely "started" — it must join the service
 * graph in <b>dependency order</b>, so that a connection still open when the launcher stops is drained
 * by the server before its reactor goes away. Asserted behaviourally and without a clock: one raw
 * connection is left open, proven live by a real answer, and the peer's close is awaited.
 * <p>
 * <b>Both {@code testInjector()} smoke tests keep passing (FR-105)</b> — asserted explicitly rather than
 * assumed, because the mount adds an {@code @Eager} binding and a service-graph registration to both
 * launchers. {@code testInjector()} compiles the graph without creating eager instances, so it neither
 * constructs a server nor opens a socket.
 * <p>
 * Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * kills the Surefire JVM), and every connection is closed inside the awaited chain, which is what lets
 * {@code TestUtils.await} reach eventloop quiescence.
 */
public class JsonRpcTcpMountTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String POST_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}";
	private static final String POST_RESULT = "\"result\":{\"id\":42,\"name\":\"user-42\"}";

	/** How many connections the per-worker isolation test opens over the shared accept socket. */
	private static final int CONNECTIONS = 6;

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	@After
	public void clearTcpKeyProperty() {
		System.clearProperty("config.jsonrpc.tcp.bogus");
	}

	@Test
	public void defaultConfigConstructsNoTcpServerAndOpensNoSocket() throws Exception {
		// FR-102: the endpoint is OFF unless jsonrpc.tcp.port carries a value. The claim is not merely
		// "no connection is accepted" but "nothing was built": the lazy binding is never resolved, so
		// peekInstance yields null and no object exists that could own a listening socket.
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create(), injectorRef);
		LauncherTestHarness.launch(launcher);
		int httpPort;
		try {
			httpPort = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, httpPort);

			Injector injector = injectorRef.get();
			JsonRpcTcpMount mount = injector.peekInstance(JsonRpcTcpMount.class);
			assertNotNull("the @Eager mount decision must have been taken at wiring time", mount);
			assertFalse("an absent jsonrpc.tcp.port must leave the endpoint disabled", mount.isEnabled());
			assertNull("a disabled endpoint mounts no listener", mount.listener());
			assertNull("a disabled endpoint constructs NO JsonRpcTcpServer at all (FR-102)",
				injector.peekInstance(JsonRpcTcpServer.class));

			// ... and the pre-existing route is untouched
			ReadResponse response = post(Reactor.getCurrentReactor(), httpPort, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));
		} finally {
			stop(launcher);
		}
		assertNothingListens(httpPort);
	}

	@Test
	public void tcpHttpAndWebSocketAnswerOnTheSameLauncher() throws Exception {
		// SC-006 / FR-100, FR-105: ONE launcher instance, three transports, one dispatcher. The TCP
		// endpoint is its own listener — there is no shared server to ride — so its port is a second,
		// distinct socket, asked for rather than configured (jsonrpc.tcp.port=0, ADR-028).
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = singleWorkerLauncher(
			Config.create().with("jsonrpc.tcp.port", "0"), injectorRef);
		LauncherTestHarness.launch(launcher);
		try {
			int httpPort = launcher.httpServer.getBoundAddresses().get(0).getPort();

			JsonRpcTcpMount mount = injectorRef.get().getInstance(JsonRpcTcpMount.class);
			assertTrue("jsonrpc.tcp.port=0 must enable the endpoint", mount.isEnabled());
			int tcpPort = mount.listener().getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, tcpPort);
			assertNotEquals("the TCP endpoint is its OWN listener, not the HTTP one", httpPort, tcpPort);

			NioReactor reactor = Reactor.getCurrentReactor();
			assertEquals(new User(42, "user-42"), await(tcpGetUser(reactor, tcpPort, 42)));

			ReadResponse response = post(reactor, httpPort, "/", POST_DOCUMENT);
			assertEquals(200, response.code());
			assertTrue("unexpected body: " + response.body(), response.bodyContains(POST_RESULT));

			assertEquals(new User(7, "user-7"), await(wsGetUser(reactor, httpPort, "/ws", 7)));

			// the TCP call left no session behind: the transport was closed inside the awaited chain
			JsonRpcTcpServer tcpServer = injectorRef.get().getInstance(JsonRpcTcpServer.class);
			assertTrue("every session of the finished call must have deregistered",
				await(sessionsOf(tcpServer.getReactor(), tcpServer)).isEmpty());
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void unknownTcpKeyFailsStartupNamingTheKey() throws Exception {
		// FR-101: a key under jsonrpc.tcp.* other than port is a non-key — the fail-closed check rejects
		// it at startup, naming the exact key (contracts/config-keys.md). The launcher runs on its own
		// thread and is driven by the start future, so a regression (the key NOT rejected, the launcher
		// happily running) is observed and torn down instead of hanging the JVM in awaitShutdown().
		System.setProperty("config.jsonrpc.tcp.bogus", "1");

		assertStartupFails(singleWorkerLauncher(Config.create(), new AtomicReference<>()),
			"jsonrpc.tcp.bogus", "the only TCP key is 'jsonrpc.tcp.port'");
	}

	@Test
	public void scalarTcpKeyFailsStartupNamingTheKey() throws Exception {
		// FR-101 follow-up: a SCALAR jsonrpc.tcp (a plausible typo for jsonrpc.tcp.port) carries no
		// children, so the child-key loop alone would walk an empty map and leave the endpoint silently
		// off — the check rejects the scalar node itself, naming the one key that exists.
		assertStartupFails(singleWorkerLauncher(Config.create().with("jsonrpc.tcp", "9000"), new AtomicReference<>()),
			"'jsonrpc.tcp'", "the only TCP key is 'jsonrpc.tcp.port'");
	}

	@Test
	public void multithreadedLauncherAcceptsOnThePrimaryReactorAndIsolatesWorkerRegistries() throws Exception {
		// FR-103: the worker-pool launcher accepts on the PRIMARY reactor's PrimaryServer and hands each
		// accepted channel to a per-worker JsonRpcTcpServer. Six connections over that one accept socket
		// are distributed round-robin; every one of them makes a working call, and the two workers'
		// session registries are strictly disjoint — a session registered in worker A is invisible from
		// worker B (cross-worker broadcast is out of scope, documented). The registries inspected are the
		// LAUNCHER'S OWN, retrieved through the DI binding itself (WorkerPool.getInstances), which is
		// exactly what an application reaching for sessions()/broadcast(...) would do.
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
					.overrideWith(Config.create()
						.with("http.listenAddresses", "0")
						.with("workers", "2")
						.with("jsonrpc.tcp.port", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		LauncherTestHarness.launch(launcher);
		try {
			JsonRpcTcpMount mount = injectorRef.get().getInstance(JsonRpcTcpMount.class);
			assertTrue("jsonrpc.tcp.port=0 must enable the endpoint", mount.isEnabled());
			assertTrue("the multi-worker mount accepts through a PrimaryServer: " + mount.listener(),
				mount.listener() instanceof PrimaryServer);
			assertEquals("the acceptor must live on the PRIMARY reactor, beside the HTTP one",
				launcher.primaryServer.getReactor(), mount.listener().getReactor());

			int tcpPort = mount.listener().getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, tcpPort);
			assertNotEquals("the TCP acceptor is its OWN socket",
				launcher.primaryServer.getBoundAddresses().get(0).getPort(), tcpPort);

			WorkerPool workerPool = injectorRef.get().getInstance(WorkerPool.class);
			List<JsonRpcTcpServer> tcpServers = workerPool.getInstances(JsonRpcTcpServer.class).getList();
			List<NioReactor> workerReactors = workerPool.getInstances(NioReactor.class).getList();
			assertEquals("the pool must hold the two configured workers", 2, tcpServers.size());
			assertNotSame("each worker must own its own JsonRpcTcpServer instance (@Worker scope)",
				tcpServers.get(0), tcpServers.get(1));

			Observed observed = await(openSessionsAndSnapshot(Reactor.getCurrentReactor(), tcpPort,
				workerReactors, tcpServers));

			for (int i = 0; i < CONNECTIONS; i++) {
				assertEquals("connection " + i + " must be answered by whichever worker accepted it",
					new User(i, "user-" + i), observed.users().get(i));
			}

			Set<JsonRpcTcpSession> workerA = observed.registries().get(0);
			Set<JsonRpcTcpSession> workerB = observed.registries().get(1);
			assertEquals("every open connection is registered exactly once, in exactly one worker",
				CONNECTIONS, workerA.size() + workerB.size());
			assertFalse("round-robin must have landed at least one connection on worker 0", workerA.isEmpty());
			assertFalse("round-robin must have landed at least one connection on worker 1", workerB.isEmpty());
			assertTrue("the registries are strictly per-worker — no session is visible from the other " +
				"worker (FR-103): A=" + workerA + " B=" + workerB,
				Collections.disjoint(workerA, workerB));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void stoppingTheLauncherDrainsALiveTcpSession() throws Exception {
		// The mounted endpoint must join the service graph in DEPENDENCY ORDER, not merely be started:
		// a session still open when the launcher stops has to be drained — by the server, before its
		// reactor goes away — or shutdown never completes. Asserted behaviourally and without a clock:
		// one raw connection is left open, proven live by a real answer, and the launcher is signalled to
		// stop from inside the awaited chain; the assertion is that the peer's close arrives.
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = singleWorkerLauncher(
			Config.create().with("jsonrpc.tcp.port", "0"), injectorRef);
		LauncherTestHarness.launch(launcher);
		try {
			int tcpPort = injectorRef.get().getInstance(JsonRpcTcpMount.class)
				.listener().getBoundAddresses().get(0).getPort();
			NioReactor reactor = Reactor.getCurrentReactor();

			String answer = await(JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", tcpPort))
				.then(transport -> {
					SettablePromise<String> answered = new SettablePromise<>();
					SettablePromise<Void> closed = new SettablePromise<>();
					transport.setListener(new JsonRpcTransport.Listener() {
						@Override
						public void onDocument(byte[] document) {
							answered.trySet(new String(document, UTF_8));
						}

						@Override
						public void onClosed(@Nullable Exception e) {
							closed.trySet(null);
						}
					});
					return transport.send(POST_DOCUMENT.getBytes(UTF_8))
						.then(() -> answered)
						// the session is live and the connection is still open: signal the stop from here
						.whenResult(() -> launcher.shutdown())
						.then(document -> closed.map($ -> document));
				}));
			assertTrue("the open session must have been answered before the stop: " + answer,
				answer.contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void bothLaunchersStillPassTheInjectorSmokeTest() {
		// FR-105: the mount adds an @Eager binding and a service-graph registration to both launchers —
		// testInjector() compiles the whole graph without creating eager instances, so it validates the
		// new bindings without constructing a server or opening a socket.
		singleWorkerLauncher(Config.create(), new AtomicReference<>()).testInjector();

		new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		}.testInjector();
	}

	// -------------------------------------------------------------------------------------------
	// Harness
	// -------------------------------------------------------------------------------------------

	/** What the per-worker isolation test observed while all {@link #CONNECTIONS} connections were open. */
	private record Observed(List<User> users, List<Set<JsonRpcTcpSession>> registries) {}

	/**
	 * Opens {@link #CONNECTIONS} framed-TCP connections at once, issues one {@code user.get} per
	 * connection, snapshots every worker's session registry <b>while they are all still open</b>, and only
	 * then closes them — the close is inside the awaited chain, which is what lets {@code TestUtils.await}
	 * reach eventloop quiescence.
	 */
	private static Promise<Observed> openSessionsAndSnapshot(
		NioReactor reactor, int port, List<NioReactor> workerReactors, List<JsonRpcTcpServer> tcpServers
	) {
		List<Promise<JsonRpcTcpTransport>> connects = new ArrayList<>();
		for (int i = 0; i < CONNECTIONS; i++) {
			connects.add(JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port)));
		}
		return Promises.toList(connects)
			.then(transports -> {
				List<JsonRpcClient> clients = transports.stream()
					.map(transport -> JsonRpcClient.builder(reactor, transport).build())
					.toList();
				List<Promise<User>> calls = new ArrayList<>();
				for (int i = 0; i < clients.size(); i++) {
					calls.add(clients.get(i).proxy(UserApi.class).getUser(i));
				}
				return Promises.toList(calls)
					.then(users -> snapshotRegistries(workerReactors, tcpServers)
						.map(registries -> new Observed(users, registries)))
					.whenComplete(() -> clients.forEach(client -> client.closeEx(new AsyncCloseException())));
			});
	}

	/**
	 * One {@code sessions()} snapshot per worker, read <b>on that worker's own eventloop thread</b> —
	 * the registry is reactor-confined — and handed back as an immutable snapshot through the future.
	 */
	private static Promise<List<Set<JsonRpcTcpSession>>> snapshotRegistries(
		List<NioReactor> workerReactors, List<JsonRpcTcpServer> tcpServers
	) {
		List<Promise<Set<JsonRpcTcpSession>>> snapshots = new ArrayList<>();
		for (int i = 0; i < tcpServers.size(); i++) {
			snapshots.add(sessionsOf(workerReactors.get(i), tcpServers.get(i)));
		}
		return Promises.toList(snapshots);
	}

	private static Promise<Set<JsonRpcTcpSession>> sessionsOf(Reactor reactor, JsonRpcTcpServer server) {
		return Promise.ofCompletionStage(((Eventloop) reactor).submit(AsyncComputation.of(server::sessions)));
	}

	/** One framed-TCP call over a real connection, closed inside the awaited chain. */
	private static Promise<User> tcpGetUser(NioReactor reactor, int port, long id) {
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return client.proxy(UserApi.class).getUser(id)
					.whenComplete(() -> client.closeEx(new AsyncCloseException()));
			});
	}

	/** One WebSocket call over the co-mounted route — the third transport of the co-mount assertion. */
	private static Promise<User> wsGetUser(NioReactor reactor, int port, String path, long id) {
		HttpClient httpClient = HttpClient.create(reactor,
			DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient,
				HttpRequest.get("ws://127.0.0.1:" + port + path).build())
			.then(transport -> JsonRpcClient.builder(reactor, transport).build()
				.proxy(UserApi.class).getUser(id)
				.whenComplete(() -> transport.closeEx(new AsyncCloseException())));
	}

	/**
	 * Launches on its own thread and asserts that startup <b>failed</b>, naming every fragment. A
	 * regression that accepts the key would otherwise leave the launcher running in
	 * {@code awaitShutdown()}; the start future is polled instead, and the launcher shut down.
	 */
	private static void assertStartupFails(Launcher launcher, String... fragments) throws Exception {
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable ignored) {
				// the launch failure is observed through the start future below
			}
		}, "jsonrpc-tcp-mount-test");
		thread.start();
		try {
			launcher.getStartFuture().toCompletableFuture().get(10, TimeUnit.SECONDS);
			// startup succeeded — the key was NOT rejected
			launcher.shutdown();
			fail("startup must fail, naming " + String.join(" and ", fragments));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			for (String fragment : fragments) {
				assertTrue("the failure must name " + fragment + ": " + cause.getMessage(),
					cause.getMessage().contains(fragment));
			}
		} finally {
			thread.join(5000);
		}
	}

	/** A single-worker launcher with the {@link UserApi} binding, a {@code :0} bind and {@code onFatalError} overridden. */
	private static JsonRpcServerLauncher singleWorkerLauncher(Config overrides, AtomicReference<Injector> injectorRef) {
		return new JsonRpcServerLauncher() {
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
