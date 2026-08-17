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
import io.activej.common.initializer.Initializer;
import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.http.IHttpClient;
import io.activej.inject.Injector;
import io.activej.inject.Key;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.JsonRpcHttpClientTransport;
import io.activej.launcher.LauncherService;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraph;
import io.activej.service.ServiceGraphModule;
import io.activej.service.ServiceGraphModuleSettings;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * E1, E2, E3, E5 of the adversarial plan — the client lifecycle under hostile timing.
 * <ul>
 *     <li>E1: calls in flight at the exact stop moment, issued during the stop, issued after it —
 *     every one fails with the graph's close exception; after the stop nothing new is emitted;</li>
 *     <li>E2: {@code closeEx} idempotence — the first exception wins, no second failure is delivered;</li>
 *     <li>E3: off-reactor {@code closeEx} and wayward transport deliveries fail fast on the thread
 *     guards — the correlation table is never corrupted;</li>
 *     <li>E5: the correlation table — notifications add no entry, a black-holed call stays in flight
 *     until the close, a successful call removes its entry exactly once.</li>
 * </ul>
 * <p>
 * The black-hole server accepts connections and never answers, so a call stays in flight until the
 * graph closes it. The counting {@link IHttpClient} proves "no document emitted".
 */
public class JsonRpcClientLifecycleAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private EventloopThread loop;
	private ServerSocket blackHole;
	private final List<Socket> accepted = new CopyOnWriteArrayList<>();
	private Thread blackHoleThread;

	@Before
	public void setUp() throws Exception {
		loop = EventloopThread.create("jsonrpc-client-adversarial");
		blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		blackHoleThread = new Thread(() -> {
			while (!blackHole.isClosed()) {
				try {
					accepted.add(blackHole.accept());
				} catch (IOException ignored) {
					return;
				}
			}
		});
		blackHoleThread.setDaemon(true);
		blackHoleThread.start();
	}

	@After
	public void tearDown() throws Exception {
		for (Socket socket : accepted) {
			socket.close();
		}
		if (blackHole != null) blackHole.close();
		if (loop != null) loop.close();
	}

	/** Counts every document handed to the HTTP layer — the "no document emitted" witness. */
	private static final class CountingHttpClient implements IHttpClient {
		final IHttpClient delegate;
		final AtomicInteger requests = new AtomicInteger();

		CountingHttpClient(IHttpClient delegate) {this.delegate = delegate;}

		@Override
		public Promise<HttpResponse> request(HttpRequest request) {
			requests.incrementAndGet();
			return delegate.request(request);
		}
	}

	private record Graph(ServiceGraph serviceGraph, JsonRpcClient client, Injector injector, CountingHttpClient counting) {}

	private Graph createGraph() {
		CountingHttpClient counting = new CountingHttpClient(
			HttpClient.create(loop.eventloop(), DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8"))));
		Injector injector = Injector.of(new AbstractModule() {
			@Provides
			Eventloop eventloop() {
				return loop.eventloop();
			}

			@Provides
			NioReactor reactor(Eventloop eventloop) {
				return eventloop;
			}

			@Provides
			IHttpClient httpClient() {
				return counting;
			}

			@Provides
			Config config() {
				return Config.create().with("jsonrpc.client.url", "http://127.0.0.1:" + blackHole.getLocalPort() + "/");
			}

			@ProvidesIntoSet
			Initializer<ServiceGraphModuleSettings> serviceGraphSettings() {
				return settings -> {
					// the loop is ours (EventloopThread); the graph must not manage it
					settings.withExcludedKey(Key.of(Eventloop.class));
					settings.withExcludedKey(Key.of(NioReactor.class));
				};
			}

			@Provides
			io.activej.service.Service dummyService() {
				return new io.activej.service.Service() {
					@Override
					public CompletableFuture<?> start() {return CompletableFuture.completedFuture(null);}

					@Override
					public CompletableFuture<?> stop() {return CompletableFuture.completedFuture(null);}
				};
			}
		}, new JsonRpcClientModule(), ServiceGraphModule.create());
		JsonRpcClient client = loop.submit(() -> injector.getInstance(JsonRpcClient.class));
		ServiceGraph serviceGraph = injector.getInstance(ServiceGraph.class);
		return new Graph(serviceGraph, client, injector, counting);
	}

	private static void startGraph(Injector injector) throws Exception {
		Set<LauncherService> services = injector.getInstance(new Key<Set<LauncherService>>() {});
		for (LauncherService service : services) {
			service.start().get();
		}
	}

	private static void stopGraph(Injector injector) throws Exception {
		Set<LauncherService> services = injector.getInstance(new Key<Set<LauncherService>>() {});
		for (LauncherService service : services) {
			service.stop().get();
		}
	}

	@SuppressWarnings("unchecked")
	private UserApi proxyOnLoop(Graph graph) {
		return loop.eventloop().submit(AsyncComputation.of(() -> (UserApi) graph.client().proxy(UserApi.class))).join();
	}

	private Promise<User> callOnLoop(UserApi api, long id) {
		return loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(id))).join();
	}

	private static Exception causeOf(Promise<?> promise, Eventloop eventloop) {
		return eventloop.submit(AsyncComputation.of(() -> {
			try {
				promise.toCompletableFuture().get();
				return null;
			} catch (ExecutionException e) {
				return (Exception) e.getCause();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		})).join();
	}

	// ---------------------------------------------------------------------------------------------
	// E1 — stop mid-flight
	// ---------------------------------------------------------------------------------------------

	@Test
	public void callIssuedWhileTheGraphStopIsInProgressFailsWithTheCloseException() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> inFlight = callOnLoop(api, 42);
		assertEquals(1, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());

		// stop the graph on its own thread; the call is issued while the stop may be in progress —
		// whichever ordering the loop picks, the call must fail with the graph's close exception
		Thread stopper = new Thread(() -> {
			try {
				stopGraph(graph.injector());
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		stopper.start();
		Promise<User> duringStop = callOnLoop(api, 43);
		stopper.join(5000);
		assertFalse("the graph stop must have completed", stopper.isAlive());

		assertTrue("the during-stop call must fail with AsyncCloseException, got: " + causeOf(duringStop, loop.eventloop()),
			causeOf(duringStop, loop.eventloop()) instanceof AsyncCloseException);
		assertTrue("the in-flight call must fail with AsyncCloseException, got: " + causeOf(inFlight, loop.eventloop()),
			causeOf(inFlight, loop.eventloop()) instanceof AsyncCloseException);
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());
	}

	@Test
	public void callIssuedAfterStopFailsImmediatelyWithoutEmittingADocument() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> inFlight = callOnLoop(api, 42);
		assertEquals(1, graph.counting.requests.get());

		stopGraph(graph.injector());
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());

		Promise<User> after = callOnLoop(api, 44);
		assertTrue("the after-stop call must fail immediately with AsyncCloseException, got: " + causeOf(after, loop.eventloop()),
			causeOf(after, loop.eventloop()) instanceof AsyncCloseException);
		// no new document was handed to the HTTP layer — the closed check precedes the send
		assertEquals("no document may be emitted after the stop", 1, graph.counting.requests.get());
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());
	}

	// ---------------------------------------------------------------------------------------------
	// E2 — idempotence: the first exception wins
	// ---------------------------------------------------------------------------------------------

	@Test
	public void closeExWithTwoDifferentExceptionsFirstWinsAndNoSecondFailureIsDelivered() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> call = callOnLoop(api, 42);

		loop.eventloop().submit(AsyncComputation.of(() -> graph.client().closeEx(new IOException("first")))).join();
		loop.eventloop().submit(AsyncComputation.of(() -> graph.client().closeEx(new IOException("second")))).join();

		Exception e = causeOf(call, loop.eventloop());
		assertTrue("the first exception must win, got: " + e, e instanceof IOException && e.getMessage().equals("first"));
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());

		// a new call after both closeEx fails with the FIRST cause — the second was never adopted
		Promise<User> after = callOnLoop(api, 45);
		Exception afterCause = causeOf(after, loop.eventloop());
		assertTrue("the sticky cause must be the first exception, got: " + afterCause,
			afterCause instanceof IOException && afterCause.getMessage().equals("first"));

		stopGraph(graph.injector());
	}

	@Test
	public void closeExThenGraphStopDeliversNoSecondFailure() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> call = callOnLoop(api, 42);

		loop.eventloop().submit(AsyncComputation.of(() -> graph.client().closeEx(new IOException("first")))).join();
		// the graph's own stop is now a no-op — it must deliver nothing and fail nothing
		stopGraph(graph.injector());

		Exception e = causeOf(call, loop.eventloop());
		assertTrue("the first close must win, got: " + e, e instanceof IOException && e.getMessage().equals("first"));
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());
	}

	@Test
	public void graphStopThenCloseExKeepsTheGraphsException() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> call = callOnLoop(api, 42);

		stopGraph(graph.injector());
		// a later closeEx must not replace the exception the graph closed with
		loop.eventloop().submit(AsyncComputation.of(() -> graph.client().closeEx(new IOException("later")))).join();

		assertTrue("the graph's exception must win, got: " + causeOf(call, loop.eventloop()),
			causeOf(call, loop.eventloop()) instanceof AsyncCloseException);
		Promise<User> after = callOnLoop(api, 46);
		assertTrue("a new call must still fail with the graph's exception, got: " + causeOf(after, loop.eventloop()),
			causeOf(after, loop.eventloop()) instanceof AsyncCloseException);
	}

	// ---------------------------------------------------------------------------------------------
	// E3 — thread guards
	// ---------------------------------------------------------------------------------------------

	@Test
	public void closeExOffTheClientsReactorThrowsIllegalStateExceptionAndLeavesTheTableIntact() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> call = callOnLoop(api, 42);

		// from the test thread — not the client's reactor
		assertThrows(IllegalStateException.class, () -> graph.client().closeEx(new AsyncCloseException()));
		// the guard fired before any state was touched
		assertEquals(1, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());

		// clean up on the right thread
		loop.eventloop().submit(AsyncComputation.of(() -> graph.client().closeEx(new AsyncCloseException()))).join();
		stopGraph(graph.injector());
		assertTrue(causeOf(call, loop.eventloop()) instanceof AsyncCloseException);
	}

	@Test
	public void waywardTransportDocumentDeliveryFailsFastAndNeverCorruptsTheTable() throws Exception {
		WaywardTransport transport = new WaywardTransport();
		JsonRpcClient client = loop.submit(() -> JsonRpcClient.builder(loop.eventloop(), transport).build());
		UserApi api = loop.eventloop().submit(AsyncComputation.of(() -> client.proxy(UserApi.class))).join();
		Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(42))).join();

		// a wayward transport delivering on a foreign thread: the client's guard must throw
		// immediately — never corrupt the table
		byte[] forged = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertThrows(IllegalStateException.class, () -> transport.listener.onDocument(forged));
		assertThrows(IllegalStateException.class, () -> transport.listener.onClosed(null));

		assertEquals(1, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
		loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
		assertTrue(causeOf(call, loop.eventloop()) instanceof AsyncCloseException);
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
	}

	/** An in-memory transport that records its listener and never answers — and delivers on any thread. */
	private static final class WaywardTransport implements JsonRpcTransport {
		JsonRpcTransport.Listener listener;
		Exception closedWith;

		@Override
		public Promise<Void> send(byte[] document) {
			return Promise.complete();
		}

		@Override
		public void setListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public void closeEx(Exception e) {
			closedWith = e;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// E5 — the correlation table
	// ---------------------------------------------------------------------------------------------

	@Test
	public void notificationAddsNoCorrelationEntry() throws Exception {
		// an in-memory transport: the notification's send completes immediately, so the test is
		// deterministic and the "no entry" invariant is observable without a real server
		WaywardTransport transport = new WaywardTransport();
		JsonRpcClient client = loop.submit(() -> JsonRpcClient.builder(loop.eventloop(), transport).build());
		UserApi api = loop.eventloop().submit(AsyncComputation.of(() -> client.proxy(UserApi.class))).join();

		loop.eventloop().submit(AsyncComputation.of(() -> api.touch(1))).join();
		// a notification has no id and no entry — FR-071
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
		loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
	}

	@Test
	public void notificationSendInFlightAtCloseFailsWithTheCloseExceptionAndIsReported() throws Exception {
		// Pins the actual behavior (observation O-1): a notification send that is still in flight when
		// the client is closed fails with the close exception, and the failure is handed to the client's
		// failure handler — the "Fatal error in JsonRpcClient[0 in flight, closed]" line an operator sees
		// when a lone notification's exchange has not settled by the time of the close.
		java.util.concurrent.atomic.AtomicReference<Exception> reported = new java.util.concurrent.atomic.AtomicReference<>();
		JsonRpcHttpClientTransport transport = JsonRpcHttpClientTransport.create(loop.eventloop(),
			HttpClient.create(loop.eventloop(), DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8"))),
			"http://127.0.0.1:" + blackHole.getLocalPort() + "/");
		JsonRpcClient client = loop.submit(() -> JsonRpcClient.builder(loop.eventloop(), transport)
			.withFailureHandler(reported::set)
			.build());
		UserApi api = loop.eventloop().submit(AsyncComputation.of(() -> client.proxy(UserApi.class))).join();
		// the notification's send stays pending — the black hole never answers
		loop.eventloop().submit(AsyncComputation.of(() -> api.touch(1))).join();
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());

		loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
		// the pending notification send failed with the close exception; the failure handler saw it
		assertTrue("the failure must have been reported, got: " + reported.get(), reported.get() != null);
		assertTrue(reported.get() instanceof AsyncCloseException);
	}

	@Test
	public void blackHoledCallStaysInFlightUntilTheCloseThenEmptiesTheTable() throws Exception {
		Graph graph = createGraph();
		startGraph(graph.injector());
		UserApi api = proxyOnLoop(graph);
		Promise<User> call = callOnLoop(api, 42);
		assertEquals(1, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());

		stopGraph(graph.injector());
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(graph.client()::inFlightCount)).join());
		assertTrue(causeOf(call, loop.eventloop()) instanceof AsyncCloseException);
	}

	@Test
	public void successfulCallRemovesItsEntryExactlyOnce() throws Exception {
		// a real server: the launcher runs on its own eventloop; the client on the EventloopThread
		JsonRpcServerLauncher server = startRealServer();

		try {
			int port = server.httpServer.getBoundAddresses().get(0).getPort();
			CountingHttpClient counting = new CountingHttpClient(HttpClient.create(loop.eventloop(),
				DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8"))));
			JsonRpcHttpClientTransport transport = JsonRpcHttpClientTransport.create(loop.eventloop(),
				counting, "http://127.0.0.1:" + port + "/");
			JsonRpcClient client = loop.submit(() -> JsonRpcClient.builder(loop.eventloop(), transport).build());
			UserApi api = loop.eventloop().submit(AsyncComputation.of(() -> client.proxy(UserApi.class))).join();

			Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(7))).join();
			// the promise must be awaited from the TEST thread — a get() inside a loop task would
			// block the very loop that must deliver the response (self-deadlock)
			User user = call.toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals(7, user.id());

			// exactly one removal: the table is empty again after the single successful call
			assertEquals(1, counting.requests.get());
			assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
			loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
		} finally {
			server.shutdown();
			io.activej.promise.TestUtils.await(io.activej.promise.Promise.ofCompletionStage(server.getCompleteFuture()));
		}
	}

	private static JsonRpcServerLauncher startRealServer() throws Exception {
		JsonRpcServerLauncher server = new JsonRpcServerLauncher() {
			@Override
			protected io.activej.inject.module.Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new io.activej.launchers.jsonrpc.fixtures.UserApiImpl());
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
		java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
		new Thread(() -> {
			try {
				server.launch(io.activej.launcher.Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}).start();
		try {
			server.getStartFuture().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			if (failure.get() != null) throw new AssertionError("launch failed", failure.get());
			throw e;
		}
		return server;
	}
}
