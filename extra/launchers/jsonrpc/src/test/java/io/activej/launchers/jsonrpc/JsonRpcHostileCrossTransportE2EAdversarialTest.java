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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.jsonrpc.JsonRpcLimits.MAX_BATCH_SIZE;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.launch;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertTrue;

/**
 * CL-01 ({@code ideas/002-json-rpc/adversarial-e2e-test-plan.md} §4.1) — the sharpest gap five
 * per-feature adversarial campaigns (014/015/017/018/019) and
 * {@link JsonRpcDiscoveryCrossTransportE2EAdversarialTest} (018's own cross-transport follow-up) all
 * left unreached: nothing mixes <b>hostile</b> traffic with well-formed traffic, concurrently, across
 * all three live transports of <b>one</b> running deployment. Every per-transport hostile suite
 * ({@code JsonRpcServletHostileDocumentTest}, {@code JsonRpcTcpHostileTest},
 * {@code JsonRpcWsHostileTest}, …) attacks its own transport alone, against its own single-purpose
 * test server. {@code JsonRpcDiscoveryCrossTransportE2EAdversarialTest} stands up all three
 * transports live together but — by its own javadoc's scope — drives only well-formed traffic.
 *
 * <p>This class closes the gap with the one hostile input shape that is genuinely uniform across all
 * three transports without any transport-specific framing knowledge: a batch exceeding
 * {@link io.activej.jsonrpc.JsonRpcLimits#MAX_BATCH_SIZE} ({@value io.activej.jsonrpc.JsonRpcLimits#MAX_BATCH_SIZE}),
 * rejected with a single {@code -32002} error document at the envelope layer — before any per-element
 * dispatch, so the same attack means the same thing regardless of which transport carried it. The
 * batch's own elements are the fixture's real {@code user.touch} notification, so the assertion
 * cannot be confused with "silently accepted as under-limit notifications, no response" — that
 * outcome is empty, while an oversize-batch rejection is a populated {@code -32002} error document.
 *
 * <p>Concurrency follows {@link JsonRpcDiscoveryCrossTransportE2EAdversarialTest}'s own idiom exactly:
 * every promise below is created — and its I/O started — before any of them is awaited, so hostile
 * and well-formed traffic across all three transports are genuinely interleaved on one reactor turn,
 * not sequential.
 */
public class JsonRpcHostileCrossTransportE2EAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String WS_PATH = "/ws";

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	@Test
	public void oversizeBatchesFiredConcurrentlyAcrossAllThreeLiveTransportsAreRejectedWithoutDisturbingWellFormedTrafficOnTheOthers()
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

			// three rounds: every hostile call and every well-formed call, on every transport, started
			// before any of them is awaited — genuine cross-transport concurrency, not a sequence
			List<Promise<Outcome>> inFlight = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				int round = i;
				long httpId = 1000 + round;
				long tcpId = 2000 + round;
				long wsId = 3000 + round;

				// hostile: one oversize batch per transport this round
				inFlight.add(httpHostileBatch(reactor, httpPort)
					.map(body -> new Outcome("http-hostile-" + round, body)));
				inFlight.add(tcpHostileBatch(reactor, tcpPort)
					.map(document -> new Outcome("tcp-hostile-" + round, new String(document, UTF_8))));
				inFlight.add(wsHostileBatch(reactor, httpPort)
					.map(document -> new Outcome("ws-hostile-" + round, new String(document, UTF_8))));

				// well-formed: an ordinary user.get on every transport, same round, genuinely interleaved
				// with the three hostile calls above
				inFlight.add(httpOrdinaryCall(reactor, httpPort, httpId)
					.map(body -> new Outcome("http-ordinary-" + round, body)));
				inFlight.add(tcpOrdinaryCall(reactor, tcpPort, tcpId)
					.map(user -> new Outcome("tcp-ordinary-" + round, user.toString())));
				inFlight.add(wsOrdinaryCall(reactor, httpPort, wsId)
					.map(user -> new Outcome("ws-ordinary-" + round, user.toString())));
			}

			List<Outcome> outcomes = await(Promises.toList(inFlight));

			for (Outcome outcome : outcomes) {
				if (outcome.label().contains("hostile")) {
					assertTrue(outcome.label() + " must be rejected with -32002 (batch too large), " +
							"identically regardless of which transport carried it: " + outcome.value(),
						outcome.value().contains("\"code\":-32002"));
				} else {
					assertTrue(outcome.label() + " must reach UserApi correctly, unaffected by " +
							"concurrent oversize-batch attacks on the other two transports: " + outcome.value(),
						outcome.value().contains("user-"));
				}
			}
			// ByteBufRule/ActivePromisesRule (@ClassRule) are the assertion that none of the above
			// leaked a buffer or left a promise unresolved — the server surviving mixed hostile and
			// well-formed concurrent traffic on all three transports at once is the point of this test
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- shared setup (mirrors
	// JsonRpcDiscoveryCrossTransportE2EAdversarialTest's own shared setup exactly)

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
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
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

	// ---------------------------------------------------------------- the uniform hostile payload

	/** One batch, {@link io.activej.jsonrpc.JsonRpcLimits#MAX_BATCH_SIZE} + 1 elements — one over the bound. */
	private static String oversizeBatchDocument() {
		StringBuilder sb = new StringBuilder(64 * (MAX_BATCH_SIZE + 1)).append('[');
		for (int i = 0; i <= MAX_BATCH_SIZE; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"jsonrpc\":\"2.0\",\"method\":\"user.touch\",\"params\":[1]}");
		}
		return sb.append(']').toString();
	}

	private static Promise<String> httpHostileBatch(NioReactor reactor, int port) {
		return httpPost(reactor, port, oversizeBatchDocument());
	}

	private static Promise<byte[]> tcpHostileBatch(NioReactor reactor, int port) {
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> rawSend(transport, oversizeBatchDocument()));
	}

	private static Promise<byte[]> wsHostileBatch(NioReactor reactor, int port) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient, HttpRequest.get("ws://127.0.0.1:" + port + WS_PATH).build())
			.then(transport -> rawSend(transport, oversizeBatchDocument()));
	}

	/** One raw document over a duplex transport, delivered (or the close cause) via its listener. */
	private static Promise<byte[]> rawSend(JsonRpcTransport transport, String document) {
		SettablePromise<byte[]> result = new SettablePromise<>();
		transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] doc) {
				result.trySet(doc);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				result.trySetException(e != null ? e : new AsyncCloseException());
			}
		});
		return transport.send(document.getBytes(UTF_8))
			.then(() -> result)
			.whenComplete(() -> transport.closeEx(new AsyncCloseException()));
	}

	// ---------------------------------------------------------------- well-formed traffic, per transport

	private static Promise<String> httpOrdinaryCall(NioReactor reactor, int port, long id) {
		return httpPost(reactor, port,
			"{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"user.get\",\"params\":[" + id + "]}");
	}

	private static Promise<String> httpPost(NioReactor reactor, int port, String document) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.post("http://127.0.0.1:" + port + "/")
			.withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.withHeader(HttpHeaders.CONNECTION, "close")
			.withBody(document.getBytes(UTF_8))
			.build();
		return httpClient.request(request)
			.then(response -> response.loadBody().map(body -> body.getString(UTF_8)));
	}

	private static Promise<User> tcpOrdinaryCall(NioReactor reactor, int port, long id) {
		return JsonRpcTcpTransport.connect(reactor, new InetSocketAddress("127.0.0.1", port))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return client.proxy(UserApi.class).getUser(id)
					.whenComplete(() -> client.closeEx(new AsyncCloseException()));
			});
	}

	private static Promise<User> wsOrdinaryCall(NioReactor reactor, int port, long id) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient, HttpRequest.get("ws://127.0.0.1:" + port + WS_PATH).build())
			.then(transport -> JsonRpcClient.builder(reactor, transport).build()
				.proxy(UserApi.class).getUser(id)
				.whenComplete(() -> transport.closeEx(new AsyncCloseException())));
	}
}
