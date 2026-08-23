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
import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.inject.Injector;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.launch;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 10 (E2E adversarial campaign, 018-jsonrpc-schema-discovery): every Phase 9 surface (S1-S7,
 * {@code reviews/018-jsonrpc-schema-discovery-adversarial-test-plan.md}) attacked exactly one module in
 * isolation — an in-memory dispatcher, a standalone routing servlet, WebSocket alone, framed TCP alone,
 * launcher config via {@code testInjector}. Nothing stood up <b>one live launcher with HTTP, WebSocket,
 * framed TCP and discovery mounted simultaneously</b> and hit it with concurrent, cross-transport
 * traffic — the shape a real deployment is actually in. This class closes that gap.
 *
 * <p>Concurrency here follows the codebase's own idiom: many in-flight {@link Promise}s progressing on
 * one reactor turn ({@link Promises#toList}), not OS threads — the same shape
 * {@code MultithreadedJsonRpcServerLauncherDistributionTest} and the aggregation tests already use for
 * "many requests in flight". Where a scenario needs raw wire-level control (the pipelining test) a real
 * {@link Socket} is used instead, matching Phase 9 S2's precedent.
 */
public class JsonRpcDiscoveryCrossTransportE2EAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String TITLE = "Cross-Transport E2E API";
	private static final String VERSION = "1.0.0";
	private static final String DISCOVERY_PATH = "/openrpc";
	private static final String WS_PATH = "/ws";

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	// ---------------------------------------------------------------- 1: cross-transport document identity

	@Test
	public void allThreeTransportsServeAByteIdenticalDiscoveryDocumentUnderConcurrentCrossTransportTraffic()
		throws Exception {
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.tcp.port", "0"), injectorRef);
		launch(launcher);
		try {
			NioReactor reactor = Reactor.getCurrentReactor();
			int httpPort = launcher.httpServer.getBoundAddresses().get(0).getPort();
			int tcpPort = injectorRef.get().getInstance(JsonRpcTcpMount.class).listener()
				.getBoundAddresses().get(0).getPort();

			List<Promise<Outcome>> inFlight = new ArrayList<>();
			// two rounds, each round firing one of every kind of traffic across all three transports —
			// interleaved, not sequential: every promise below is created (and its I/O started) before
			// any of them is awaited
			for (int i = 0; i < 2; i++) {
				int round = i;
				long discoverId = 100 + round;
				inFlight.add(httpGetDiscovery(reactor, httpPort, DISCOVERY_PATH)
					.map(document -> new Outcome("http-get-" + round, document)));
				inFlight.add(httpPostDiscover(reactor, httpPort, discoverId)
					.map(body -> new Outcome("http-post-discover-" + round, resultOf(body, discoverId))));
				inFlight.add(httpOrdinaryCall(reactor, httpPort, round)
					.map(body -> new Outcome("http-ordinary-" + round, body)));
				inFlight.add(wsRawDiscover(reactor, httpPort, WS_PATH, 200 + round)
					.map(document -> new Outcome("ws-discover-" + round, resultOf(document, 200 + round))));
				inFlight.add(wsOrdinaryCall(reactor, httpPort, WS_PATH, round)
					.map(user -> new Outcome("ws-ordinary-" + round, user.toString())));
				inFlight.add(tcpRawDiscover(reactor, tcpPort, 300 + round)
					.map(document -> new Outcome("tcp-discover-" + round, resultOf(document, 300 + round))));
				inFlight.add(tcpOrdinaryCall(reactor, tcpPort, round)
					.map(user -> new Outcome("tcp-ordinary-" + round, user.toString())));
			}

			List<Outcome> outcomes = await(Promises.toList(inFlight));

			String canonical = null;
			for (Outcome outcome : outcomes) {
				if (outcome.label().contains("discover") || outcome.label().contains("get")) {
					if (canonical == null) canonical = outcome.value();
					assertEquals("every discovery-bearing outcome must carry byte-identical document " +
							"bytes regardless of which transport served it: " + outcome.label(),
						canonical, outcome.value());
				}
			}
			assertNotNull("at least one discovery outcome must have been collected", canonical);
			assertTrue("the canonical document must describe the fixture: " + canonical,
				canonical.contains("user.get") && canonical.contains("user.touch"));

			for (Outcome outcome : outcomes) {
				if (outcome.label().startsWith("http-ordinary") || outcome.label().startsWith("ws-ordinary")
					|| outcome.label().startsWith("tcp-ordinary")) {
					assertTrue(outcome.label() + " must have reached UserApi unaffected by the concurrent " +
							"discovery traffic on the other transports: " + outcome.value(),
						outcome.value().contains("user-"));
				}
			}
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- 2: multi-worker aggregation

	@Test
	public void multiWorkerLauncherServesIdenticalDocumentsAndAggregatesRpcDiscoverUnderConcurrentCrossTransportLoad()
		throws Exception {
		int workers = 4;
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		MultithreadedJsonRpcServerLauncher launcher = multiWorkerLauncher(Config.create()
			.with("workers", "" + workers)
			.with("jsonrpc.tcp.port", "0"), injectorRef);
		launch(launcher);
		try {
			NioReactor reactor = Reactor.getCurrentReactor();
			int httpPort = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			int tcpPort = injectorRef.get().getInstance(JsonRpcTcpMount.class).listener()
				.getBoundAddresses().get(0).getPort();

			int roundsPerTransport = 6; // >= workers so round-robin distribution reaches every worker
			List<Promise<Outcome>> inFlight = new ArrayList<>();
			for (int i = 0; i < roundsPerTransport; i++) {
				long httpId = 1000 + i;
				long wsId = 2000 + i;
				long tcpId = 3000 + i;
				// the GET endpoint serves the pre-computed byte[] directly (FR-032/FR-041) — it never
				// reaches dispatch(...), so it is included in the document-identity check below but
				// deliberately EXCLUDED from the dispatch-counted total a few lines down
				inFlight.add(httpGetDiscovery(reactor, httpPort, DISCOVERY_PATH)
					.map(document -> new Outcome("http-get", document)));
				inFlight.add(httpPostDiscover(reactor, httpPort, httpId)
					.map(body -> new Outcome("http-post-discover", resultOf(body, httpId))));
				inFlight.add(wsRawDiscover(reactor, httpPort, WS_PATH, wsId)
					.map(document -> new Outcome("ws-discover", resultOf(document, wsId))));
				inFlight.add(tcpRawDiscover(reactor, tcpPort, tcpId)
					.map(document -> new Outcome("tcp-discover", resultOf(document, tcpId))));
			}
			List<Outcome> outcomes = await(Promises.toList(inFlight));

			String canonical = outcomes.get(0).value();
			for (Outcome outcome : outcomes) {
				assertEquals("every worker must compute the identical document from the identical " +
						"contracts, over every one of the four call shapes: " + outcome.label(),
					canonical, outcome.value());
			}

			// dispatch-counted total: the HTTP POST rpc.discover call, WS and TCP — the three that
			// actually go through dispatch(...). The GET endpoint is excluded ON PURPOSE (see above).
			int dispatchedDiscoverCalls = roundsPerTransport * 3;
			long aggregated = pollAggregatedDiscoverCount(dispatchedDiscoverCalls);
			assertEquals("the aggregated methodStats[\"rpc.discover\"] row must sum to exactly the " +
					"dispatch(...)-routed discover calls (HTTP POST + WS + TCP) across all workers — " +
					"NOT counting the GET endpoint, which structurally cannot reach dispatch(...)",
				dispatchedDiscoverCalls, aggregated);
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- 3: shutdown race under cross-transport load

	@Test
	public void stoppingTheLauncherMidFlightAcrossAllThreeTransportsWithDiscoveryLiveTearsDownCleanly()
		throws Exception {
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.tcp.port", "0"), injectorRef);
		launch(launcher);
		NioReactor reactor = Reactor.getCurrentReactor();
		int httpPort = launcher.httpServer.getBoundAddresses().get(0).getPort();
		int tcpPort = injectorRef.get().getInstance(JsonRpcTcpMount.class).listener()
			.getBoundAddresses().get(0).getPort();

		// every one of these starts its I/O immediately on construction — none is awaited yet, so all
		// are genuinely in flight when stop() is invoked below
		List<Promise<Outcome>> inFlight = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			int id = i;
			inFlight.add(httpGetDiscovery(reactor, httpPort, DISCOVERY_PATH)
				.map(document -> new Outcome("http-get", document),
					e -> new Outcome("http-get-failed", e.getClass().getSimpleName())));
			inFlight.add(wsRawDiscover(reactor, httpPort, WS_PATH, 400 + id)
				.map(document -> new Outcome("ws-discover", resultOf(document, 400 + id)),
					e -> new Outcome("ws-discover-failed", e.getClass().getSimpleName())));
			inFlight.add(tcpRawDiscover(reactor, tcpPort, 500 + id)
				.map(document -> new Outcome("tcp-discover", resultOf(document, 500 + id)),
					e -> new Outcome("tcp-discover-failed", e.getClass().getSimpleName())));
		}

		// stop() itself calls await(...), which pumps this same reactor — every in-flight promise above
		// gets serviced (answered, or failed cleanly) in the same pumping, not left dangling
		stop(launcher);

		List<Outcome> outcomes = await(Promises.toList(inFlight));
		assertEquals("every in-flight call must have settled, one way or the other", 15, outcomes.size());
		for (Outcome outcome : outcomes) {
			assertFalse("no call may hang or resolve to an empty/garbage outcome: " + outcome,
				outcome.value().isEmpty());
		}
		// ByteBufRule (a @ClassRule) is the actual assertion that nothing leaked across this race
	}

	// ---------------------------------------------------------------- 4: triple path collision under concurrency

	@Test
	public void tripleMountPathCollisionResolvesCorrectlyUnderConcurrentTraffic() throws Exception {
		// POST (method-specific slot), discovery (any-method slot) and WebSocket (protocol-routed slot)
		// all mounted on the SAME "/" — proven pairwise by JsonRpcDiscoveryConfigTest /
		// JsonRpcDiscoveryConfigAdversarialTest #46, never all three at once, and never under concurrency
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.path", "/")
			.with("jsonrpc.ws.path", "/")
			.with("jsonrpc.discovery.path", "/"), new AtomicReference<>());
		launch(launcher);
		try {
			NioReactor reactor = Reactor.getCurrentReactor();
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			List<Promise<Outcome>> inFlight = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				inFlight.add(httpOrdinaryCallAt(reactor, port, "/", i)
					.map(body -> new Outcome("post", body)));
				inFlight.add(httpGetDiscovery(reactor, port, "/")
					.map(document -> new Outcome("get", document)));
				inFlight.add(wsOrdinaryCall(reactor, port, "/", i)
					.map(user -> new Outcome("ws", user.toString())));
				inFlight.add(rawMethod(reactor, port, "/", HttpMethod.DELETE)
					.map(response -> new Outcome("delete", response.code() + " " + response.allow())));
			}
			List<Outcome> outcomes = await(Promises.toList(inFlight));

			for (Outcome outcome : outcomes) {
				switch (outcome.label()) {
					case "post" -> assertTrue("POST must reach the JSON-RPC servlet: " + outcome,
						outcome.value().contains("user-"));
					case "get" -> assertTrue("GET must reach the discovery document: " + outcome,
						outcome.value().contains("\"openrpc\""));
					case "ws" -> assertTrue("a WebSocket upgrade must reach the JSON-RPC session: " + outcome,
						outcome.value().contains("user-"));
					case "delete" -> assertEquals(
						"a verb none of the three slots claim must be the discovery servlet's own 405: " +
							outcome,
						"405 GET", outcome.value());
					default -> throw new AssertionError("unexpected outcome label: " + outcome);
				}
			}
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- 5: HTTP/1.1 pipelining, GET+POST mixed

	@Test
	public void httpKeepAlivePipeliningMixesDiscoveryGetAndOrdinaryPostWithNoCrossContamination() throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create(), new AtomicReference<>());
		launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			int rounds = 10;

			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(10_000);
				OutputStream out = socket.getOutputStream();
				InputStream in = new BufferedInputStream(socket.getInputStream());

				// write every pipelined request up front — a real HTTP/1.1 client that pipelines does not
				// wait for response N before writing request N+1
				for (int i = 0; i < rounds; i++) {
					if (i % 2 == 0) {
						out.write(getRequest(DISCOVERY_PATH));
					} else {
						String body = "{\"jsonrpc\":\"2.0\",\"id\":" + i +
							",\"method\":\"user.get\",\"params\":[" + i + "]}";
						out.write(postRequest("/", body));
					}
				}
				out.flush();

				for (int i = 0; i < rounds; i++) {
					HttpResponseLite response = readResponse(in);
					if (i % 2 == 0) {
						assertEquals("pipelined response " + i + " (GET) out of order or cross-contaminated",
							200, response.code());
						assertTrue("pipelined response " + i + " must be the discovery document: " +
							response.body(), response.body().contains("\"openrpc\""));
					} else {
						assertEquals("pipelined response " + i + " (POST) out of order or cross-contaminated",
							200, response.code());
						assertTrue("pipelined response " + i + " must answer id " + i + ": " + response.body(),
							response.body().contains("\"id\":" + i) && response.body().contains("user-" + i));
					}
				}
			}
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- shared setup

	private record Outcome(String label, String value) {}

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
				return super.config()
					.overrideWith(Config.create()
						.with("http.listenAddresses", "0")
						.with("jsonrpc.discovery.path", DISCOVERY_PATH)
						.with("jsonrpc.discovery.info.title", TITLE)
						.with("jsonrpc.discovery.info.version", VERSION))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	private static MultithreadedJsonRpcServerLauncher multiWorkerLauncher(
		Config overrides, AtomicReference<Injector> injectorRef
	) {
		return new MultithreadedJsonRpcServerLauncher() {
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
						.with("jsonrpc.discovery.path", DISCOVERY_PATH)
						.with("jsonrpc.discovery.info.title", TITLE)
						.with("jsonrpc.discovery.info.version", VERSION))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	private static Module businessLogic() {
		return new AbstractModule() {
			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
			}
		};
	}

	// ---------------------------------------------------------------- per-transport probes

	private static Promise<String> httpGetDiscovery(NioReactor reactor, int port, String path) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.get("http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONNECTION, "close")
			.build();
		return httpClient.request(request)
			.then(response -> response.loadBody().map(body -> body.getString(UTF_8)));
	}

	private static Promise<String> httpPostDiscover(NioReactor reactor, int port, long id) {
		return httpPost(reactor, port, "/", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"rpc.discover\"}");
	}

	private static Promise<String> httpOrdinaryCall(NioReactor reactor, int port, long id) {
		return httpOrdinaryCallAt(reactor, port, "/", id);
	}

	private static Promise<String> httpOrdinaryCallAt(NioReactor reactor, int port, String path, long id) {
		return httpPost(reactor, port, path,
			"{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"user.get\",\"params\":[" + id + "]}");
	}

	/**
	 * A genuinely non-blocking POST — unlike {@link LauncherTestHarness#post}, which calls {@code await(...)}
	 * internally and therefore cannot be fired-and-collected alongside other in-flight promises without
	 * collapsing this class's concurrency scenarios into sequential blocking calls.
	 */
	private static Promise<String> httpPost(NioReactor reactor, int port, String path, String document) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.withHeader(HttpHeaders.CONNECTION, "close")
			.withBody(document.getBytes(UTF_8))
			.build();
		return httpClient.request(request)
			.then(response -> response.loadBody().map(body -> body.getString(UTF_8)));
	}

	private record RawGetResponse(int code, @Nullable String allow) {}

	private static Promise<RawGetResponse> rawMethod(NioReactor reactor, int port, String path, HttpMethod method) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.builder(method, "http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONNECTION, "close")
			.build();
		return httpClient.request(request)
			.then(response -> response.loadBody()
				.map($ -> new RawGetResponse(response.getCode(), response.getHeader(HttpHeaders.ALLOW))));
	}

	private static Promise<byte[]> wsRawDiscover(NioReactor reactor, int port, String path, long id) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient, HttpRequest.get("ws://127.0.0.1:" + port + path).build())
			.then(transport -> rawCall(transport, id));
	}

	private static Promise<User> wsOrdinaryCall(NioReactor reactor, int port, String path, long id) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient, HttpRequest.get("ws://127.0.0.1:" + port + path).build())
			.then(transport -> JsonRpcClient.builder(reactor, transport).build()
				.proxy(UserApi.class).getUser(id)
				.whenComplete(() -> transport.closeEx(new AsyncCloseException())));
	}

	private static Promise<byte[]> tcpRawDiscover(NioReactor reactor, int port, long id) {
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> rawCall(transport, id));
	}

	private static Promise<User> tcpOrdinaryCall(NioReactor reactor, int port, long id) {
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return client.proxy(UserApi.class).getUser(id)
					.whenComplete(() -> client.closeEx(new AsyncCloseException()));
			});
	}

	/** One raw {@code rpc.discover} document over a duplex transport, correlated by {@code id} alone. */
	private static Promise<byte[]> rawCall(JsonRpcTransport transport, long id) {
		SettablePromise<byte[]> result = new SettablePromise<>();
		transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				result.trySet(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				result.trySetException(e != null ? e : new AsyncCloseException());
			}
		});
		return transport.send(("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"rpc.discover\"}").getBytes(UTF_8))
			.then(() -> result)
			.whenComplete(() -> transport.closeEx(new AsyncCloseException()));
	}

	/** The {@code result} member of a {@code {"jsonrpc":"2.0","id":<id>,"result":<document>}} response, as bytes or text. */
	private static String resultOf(byte[] response, long id) {
		return resultOf(new String(response, UTF_8), id);
	}

	private static String resultOf(String response, long id) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":";
		assertTrue("not the expected rpc.discover envelope for id " + id + ": " + response,
			response.startsWith(prefix) && response.endsWith("}"));
		return response.substring(prefix.length(), response.length() - 1);
	}

	private static long pollAggregatedDiscoverCount(int expected) throws Exception {
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		Set<ObjectName> names = mbs.queryNames(
			new ObjectName("io.activej.jsonrpc.service:type=JsonRpcDispatcher,*"), null);
		ObjectName aggregated = names.stream()
			.filter(name -> !name.getKeyPropertyList().containsKey("workerId"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no aggregated MBean found among: " + names));
		long total = -1;
		for (int attempt = 0; attempt < 20; attempt++) {
			try {
				TabularData methodStats = (TabularData) mbs.getAttribute(aggregated, "methodStats");
				CompositeData row = (CompositeData) methodStats.get(new Object[]{"rpc.discover"});
				assertNotNull("rpc.discover must have its own JMX row (FR-033)", row);
				total = (long) row.get("successfulRequests_totalCount");
				if (total == expected) return total;
			} catch (Exception ignored) {
				// the 1s JMX refresh may not have settled yet; retry
			}
			Thread.sleep(500);
		}
		return total;
	}

	// ---------------------------------------------------------------- raw HTTP/1.1 pipelining wire helpers

	private static byte[] getRequest(String path) {
		return ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
			.getBytes(UTF_8);
	}

	private static byte[] postRequest(String path, String body) {
		byte[] bodyBytes = body.getBytes(UTF_8);
		return ("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n" +
			"Content-Type: application/json\r\nContent-Length: " + bodyBytes.length + "\r\n\r\n" + body)
			.getBytes(UTF_8);
	}

	private record HttpResponseLite(int code, String body) {}

	/** Reads exactly one HTTP/1.1 response (status line, headers, then exactly {@code Content-Length} bytes). */
	private static HttpResponseLite readResponse(InputStream in) throws IOException {
		String statusLine = readLine(in);
		int code = Integer.parseInt(statusLine.split(" ")[1]);
		int contentLength = 0;
		String header;
		while (!(header = readLine(in)).isEmpty()) {
			if (header.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
				contentLength = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
			}
		}
		byte[] body = new byte[contentLength];
		int read = 0;
		while (read < contentLength) {
			int n = in.read(body, read, contentLength - read);
			if (n < 0) throw new IOException("connection closed after " + read + "/" + contentLength + " body bytes");
			read += n;
		}
		return new HttpResponseLite(code, new String(body, UTF_8));
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int b;
		int prev = -1;
		while ((b = in.read()) != -1) {
			if (prev == '\r' && b == '\n') {
				byte[] bytes = line.toByteArray();
				return new String(bytes, 0, bytes.length - 1, UTF_8);
			}
			line.write(b);
			prev = b;
		}
		throw new IOException("connection closed mid-line");
	}
}
