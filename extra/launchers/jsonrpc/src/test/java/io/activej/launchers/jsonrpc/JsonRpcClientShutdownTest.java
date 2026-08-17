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
import io.activej.http.HttpUtils;
import io.activej.http.IHttpClient;
import io.activej.inject.Injector;
import io.activej.common.initializer.Initializer;
import io.activej.inject.Key;
import io.activej.inject.annotation.Provides;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.http.JsonRpcHttpClientTransport;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import io.activej.service.ServiceGraph;
import io.activej.launcher.LauncherService;
import io.activej.service.ServiceGraphModuleSettings;
import io.activej.service.ServiceGraphModule;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * User story 3 (FR-060, FR-061): a DI-provided client's in-flight calls are failed deterministically at
 * graph shutdown — {@code AsyncCloseException} for a plain {@code close()} — instead of left pending.
 * <p>
 * The eventloop is bound into the graph and the <b>graph owns its lifecycle</b> ({@code forEventloop}):
 * starting it twice would tear {@code eventloopThread} between two threads and make every thread guard
 * fail.
 */
public class JsonRpcClientShutdownTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private EventloopThread loop;
	private java.net.ServerSocket blackHole;
	private final java.util.List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
	private Thread blackHoleThread;

	@Before
	public void setUp() throws Exception {
		loop = EventloopThread.create("jsonrpc-client-test");
		// accepts connections, reads nothing and never answers — keeps a client call in flight until
		// the graph closes it (an immediate close would fail the call with a connection error instead)
		blackHole = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
		blackHoleThread = new Thread(() -> {
			while (!blackHole.isClosed()) {
				try {
					accepted.add(blackHole.accept());
				} catch (java.io.IOException ignored) {
					return;
				}
			}
		});
		blackHoleThread.setDaemon(true);
		blackHoleThread.start();
	}

	@After
	public void tearDown() throws Exception {
		for (java.net.Socket socket : accepted) {
			socket.close();
		}
		if (blackHole != null) blackHole.close();
		if (loop != null) loop.close();
	}

	private record Graph(ServiceGraph serviceGraph, JsonRpcClient client, Eventloop eventloop, Injector injector) {}

	private static void startGraph(Injector injector) throws Exception {
		// the graph's LauncherService applies the ServiceGraphModuleSettings initializers
		// (including adapter registrations) — driving serviceGraph.startFuture() directly skips them
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

	/**
	 * The client graph: ServiceGraphModule + JsonRpcClientModule + a real HTTP client on the loop.
	 * The loop runs on the {@link EventloopThread} and is <b>excluded from the graph</b> — the graph
	 * would otherwise start it on a second thread (tearing {@code eventloopThread} between two
	 * threads) and stop it at graph stop, after which no assertion could run on it.
	 */
	private Graph createGraph(boolean withAdapter) {
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
			IHttpClient httpClient(Eventloop eventloop) {
				// a fresh DNS client on the loop; the unreachable URL never needs a real query
				return HttpClient.create(eventloop,
					DnsClient.create(eventloop, HttpUtils.inetAddress("8.8.8.8")));
			}

			@Provides
			Config config() {
				// the black-hole server accepts but never answers — the call stays in flight until the graph stops
				return Config.create().with("jsonrpc.client.url", "http://127.0.0.1:" + blackHole.getLocalPort() + "/");
			}
		}, withAdapter ? new JsonRpcClientModule() : new AbstractModule() {
			@Provides
			JsonRpcHttpClientTransport transport(Eventloop eventloop, IHttpClient httpClient, Config config) {
				return JsonRpcHttpClientTransport.create(eventloop, httpClient,
					config.getChild("jsonrpc").get("client.url", "http://127.0.0.1:" + blackHole.getLocalPort() + "/"));
			}

			@Provides
			JsonRpcClient client(Eventloop eventloop, JsonRpcHttpClientTransport transport) {
				return JsonRpcClient.builder(eventloop, transport).build();
			}
		}, new AbstractModule() {
			@ProvidesIntoSet
			Initializer<ServiceGraphModuleSettings> serviceGraphSettings() {
				// the loop is ours (EventloopThread); the graph must not manage it
				return settings -> {
					settings.withExcludedKey(Key.of(Eventloop.class));
					settings.withExcludedKey(Key.of(NioReactor.class));
					if (!withAdapter) {
						// T042: without the client adapter the graph must not touch the client's transport
						// either — the HTTP client's stop would close the pending call and mask the gap
						settings.withExcludedKey(Key.of(IHttpClient.class));
					}
				};
			}

			@Provides
			io.activej.service.Service dummyService() {
				// a graph with nothing to manage refuses to start — the no-adapter scenario still needs a root
				return new io.activej.service.Service() {
					@Override
					public CompletableFuture<?> start() {return CompletableFuture.completedFuture(null);}

					@Override
					public CompletableFuture<?> stop() {return CompletableFuture.completedFuture(null);}
				};
			}
		}, ServiceGraphModule.create());
		Eventloop eventloop = injector.getInstance(Eventloop.class);
		ServiceGraph serviceGraph = injector.getInstance(ServiceGraph.class);
		if (!withAdapter) {
			// instantiate the dummy root so the graph has a service to manage
			injector.getInstance(io.activej.service.Service.class);
		}
		// the client's build() must run on its own reactor thread
		JsonRpcClient client = loop.submit(() -> injector.getInstance(JsonRpcClient.class));
		return new Graph(serviceGraph, client, eventloop, injector);
	}

	@SuppressWarnings("unchecked")
	private UserApi proxyOnLoop(Graph graph) {
		return loop.eventloop().submit(AsyncComputation.of(() -> (UserApi) graph.client().proxy(UserApi.class))).join();
	}

	@Test
	public void inFlightCallFailsWithAsyncCloseExceptionAtGraphStop() throws Exception {
		Graph graph0 = createGraph(true);
		ServiceGraph graph = graph0.serviceGraph();
		JsonRpcClient client = graph0.client();

		startGraph(graph0.injector());
		UserApi api = proxyOnLoop(graph0);
		Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(42))).join();

		stopGraph(graph0.injector());

		// the in-flight call completed exceptionally with the graph's close exception
		Exception e = loop.eventloop().submit(AsyncComputation.of(() -> {
			try {
				call.toCompletableFuture().get();
				return null;
			} catch (ExecutionException ex) {
				return (Exception) ex.getCause();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError(interrupted);
			}
		})).join();
		assertTrue("expected AsyncCloseException, got: " + e, e instanceof AsyncCloseException);

		// the correlation table is empty
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
	}

	@Test
	public void stoppingTwiceDeliversNoSecondFailure() throws Exception {
		Graph graph0 = createGraph(true);
		ServiceGraph graph = graph0.serviceGraph();
		JsonRpcClient client = graph0.client();

		startGraph(graph0.injector());
		UserApi api = proxyOnLoop(graph0);
		Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(42))).join();

		stopGraph(graph0.injector());
		// a second stop is a completed no-op
		stopGraph(graph0.injector());

		// a second closeEx after shutdown is idempotent and fails nothing new
		loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
		Exception e = loop.eventloop().submit(AsyncComputation.of(() -> {
			try {
				call.toCompletableFuture().get();
				return null;
			} catch (ExecutionException ex) {
				return (Exception) ex.getCause();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError(interrupted);
			}
		})).join();
		assertTrue(e instanceof AsyncCloseException);
		assertEquals(0, (int) loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join());
	}

	@Test
	public void closeRunsOnTheClientsOwnReactorThread() throws Exception {
		Graph graph0 = createGraph(true);
		ServiceGraph graph = graph0.serviceGraph();
		JsonRpcClient client = graph0.client();

		startGraph(graph0.injector());
		Thread reactorThread = loop.eventloop().submit(AsyncComputation.of(Thread::currentThread)).join();
		UserApi api = proxyOnLoop(graph0);
		Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(42))).join();

		stopGraph(graph0.injector());

		// the failure was delivered on the client's own reactor thread (FR-061)
		Thread failureThread = loop.eventloop().submit(AsyncComputation.of(() -> {
			try {
				call.toCompletableFuture().get();
				return null;
			} catch (ExecutionException e) {
				return Thread.currentThread();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		})).join();
		assertSame(reactorThread, failureThread);
	}

	@Test
	public void withoutTheAdapterTheGraphSilentlyIgnoresTheClient() throws Exception {
		// pin the failure mode this story exists for: AsyncCloseable is not AutoCloseable, so without
		// the registered adapter the graph neither starts nor stops the client and nothing warns
		Graph graph0 = createGraph(false);
		ServiceGraph graph = graph0.serviceGraph();
		JsonRpcClient client = graph0.client();

		startGraph(graph0.injector());
		UserApi api = proxyOnLoop(graph0);
		Promise<User> call = loop.eventloop().submit(AsyncComputation.of(() -> api.getUser(42))).join();

		stopGraph(graph0.injector());

		// the call is STILL pending — the silent failure is pinned so a refactor cannot reintroduce it
		assertFalse(loop.eventloop().submit(AsyncComputation.of(call::isComplete)).join());
		assertTrue(loop.eventloop().submit(AsyncComputation.of(client::inFlightCount)).join() > 0);
		loop.eventloop().submit(AsyncComputation.of(() -> client.closeEx(new AsyncCloseException()))).join();
	}
}
