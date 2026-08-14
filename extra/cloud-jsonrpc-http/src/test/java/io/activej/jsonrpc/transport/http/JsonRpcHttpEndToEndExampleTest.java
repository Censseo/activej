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

package io.activej.jsonrpc.transport.http;

import io.activej.dns.DnsClient;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpClient;
import io.activej.http.HttpMethod;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.http.RoutingServlet;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.promise.Promise;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.test.EventloopThread.await;
import static org.junit.Assert.assertEquals;

/**
 * The runnable, self-checking example of this feature (FR-080…FR-083): an annotated interface, its
 * implementation, a real {@link HttpServer} mounting {@link JsonRpcServlet} under a
 * {@link RoutingServlet}, and a client calling through {@link JsonRpcClient#proxy(Class)} — with
 * the equivalent {@code curl} command in the test's own comment block, because that equivalence is
 * the feature's headline claim (FR-083).
 * <p>
 * The example lives in this module's {@code src/test} and runs on every build under {@code -P extra}
 * (FR-081, FR-082) — an example the build does not execute is an example that rots. The class name
 * carries the {@code Test} suffix for exactly that reason: Surefire's include patterns are what make
 * "compiled and exercised by the build" a fact rather than a promise.
 * <p>
 * Everything below is the <b>whole</b> of what a developer writes — no HTTP code beyond constructing
 * the transport (SC-008): the interface declares the wire protocol, the server mounts the servlet,
 * the client calls through a {@code reflect.Proxy} of the same interface.
 * <p>
 * The server binds <b>port {@code 0}</b> and is asked where it landed via
 * {@link HttpServer#getBoundAddresses()} — {@code TestUtils.getFreePort()} is deliberately absent
 * from this module (FR-050a, ADR-028). The example's own reactor runs on a dedicated
 * {@link EventloopThread}, which is also how the module's real-socket tests keep a listening server
 * alive while the JUnit thread awaits answers.
 */
public final class JsonRpcHttpEndToEndExampleTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private EventloopThread loop;
	private JsonRpcDispatcher dispatcher;
	private HttpServer server;
	private HttpClient httpClient;
	private JsonRpcClient client;

	/** The last status the servlet produced — lets the example check the wire codes it documents. */
	private final AtomicInteger lastStatus = new AtomicInteger();

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-example");
		loop.submit(() -> {
			dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(Calculator.class, new CalculatorImpl())
				.build();
			// the servlet reads no path, so one instance serves wherever it is mounted (FR-012);
			// mounting under /api mirrors the quickstart's canonical shape
			JsonRpcServlet servlet = JsonRpcServlet.create(loop.eventloop(), dispatcher);
			AsyncServlet mounted = RoutingServlet.builder(loop.eventloop())
				.with(HttpMethod.POST, "/api", servlet)
				.build();
			// a status-recording wrapper — the example's own assertion hook, nothing more
			AsyncServlet recorded = request -> mounted.serve(request)
				.map(response -> {
					lastStatus.set(response.getCode());
					return response;
				});
			server = HttpServer.builder(loop.eventloop(), recorded)
				.withListenPort(0)                          // :0, then asked where it landed (FR-050a)
				.build();
			try {
				server.listen();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			httpClient = HttpClient.create(loop.eventloop(),
				DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8")));
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				throw new AssertionError("the example server did not close cleanly", e);
			}
		}
		if (loop != null) loop.close();
	}

	/**
	 * The example end to end, with the {@code curl} command that reaches the same endpoint:
	 * <pre>{@code
	 * curl -H 'Content-Type: application/json' \
	 *      -d '{"jsonrpc":"2.0","id":1,"method":"calc.add","params":{"a":2,"b":3}}' \
	 *      http://localhost:PORT/api
	 * # {"jsonrpc":"2.0","id":1,"result":{"value":5}}
	 *
	 * # a notification answers 204 with no body and no Content-Type:
	 * curl -H 'Content-Type: application/json' \
	 *      -d '{"jsonrpc":"2.0","method":"calc.ping","params":{"message":"hello"}}' \
	 *      http://localhost:PORT/api
	 * }</pre>
	 * {@code PORT} is whatever the server printed at the start of this test — the port is bound by
	 * the kernel and read back, never guessed. The Java calls below produce exactly those two
	 * exchanges, and the assertions check the decoded result and the wire status of each.
	 */
	@Test
	public void theExampleEndToEnd() {
		int port = server.getBoundAddresses().get(0).getPort();
		System.out.println("JSON-RPC over HTTP example: listening on http://127.0.0.1:" + port + "/api");
		System.out.println("  curl -H 'Content-Type: application/json' \\");
		System.out.println("       -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"calc.add\",\"params\":{\"a\":2,\"b\":3}}' \\");
		System.out.println("       http://127.0.0.1:" + port + "/api");
		System.out.println("  # -> {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":5}}");

		// the wire names the interface declares — the ones the curl documents above must match
		assertEquals(Set.of("calc.add", "calc.ping"), loop.submit(dispatcher::wireNames));

		Calculator api = proxy();

		// the same call as the curl command, from Java through the same interface (SC-008)
		Sum sum = await(loop.submit(() -> api.add(2, 3)).toCompletableFuture(), "calc.add");
		assertEquals("the result decoded from the wire", 5, sum.value());
		assertEquals("the server answered 200, as the curl output shows", 200, lastStatus.get());
		assertEquals("no correlation entry is left behind", 0, (int) loop.submit(client::inFlightCount));

		// a notification POSTs, the server answers 204 with no body, nothing is delivered —
		// and the notification's promise IS the transport's send promise, so awaiting it proves
		// the exchange completed (feature 012 F11)
		await(loop.submit(() -> api.ping("hello from the example")).toCompletableFuture(), "calc.ping");
		assertEquals("the notification answered 204, as the curl output shows", 204, lastStatus.get());
		assertEquals("a notification registers no correlation entry", 0, (int) loop.submit(client::inFlightCount));
	}

	// ---------------------------------------------------------------------------------------------------
	// The example's protocol and wiring — the whole of what a developer writes.
	// ---------------------------------------------------------------------------------------------------

	private Calculator proxy() {
		client = loop.submit(() -> JsonRpcClient.builder(loop.eventloop(),
				JsonRpcHttpClientTransport.create(loop.eventloop(), httpClient,
					"http://127.0.0.1:" + server.getBoundAddresses().get(0).getPort() + "/api"))
			.build());
		return loop.submit(() -> client.proxy(Calculator.class));
	}

	/**
	 * The whole of what a developer writes (FR-080): one annotated interface is the protocol.
	 * Every wire name is explicit — an empty {@code @JsonRpcMethod} value would fall back to the
	 * Java identifier, and a later rename would silently change the wire format.
	 */
	@JsonRpcService("calc")
	public interface Calculator {
		/** Wire name {@code calc.add}: two named parameters in, one record out. */
		@JsonRpcMethod("add")
		Promise<Sum> add(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b);

		/** Wire name {@code calc.ping}: a notification — never answered, so the server replies {@code 204}. */
		@JsonRpcNotification("ping")
		Promise<Void> ping(@JsonRpcParam("message") String message);
	}

	/** The record result of {@link Calculator#add} — a codec is derived for any record, nothing to register. */
	public record Sum(int value) {}

	/** The implementation the server dispatches to. */
	static final class CalculatorImpl implements Calculator {
		@Override
		public Promise<Sum> add(int a, int b) {
			return Promise.of(new Sum(a + b));
		}

		@Override
		public Promise<Void> ping(String message) {
			// a notification has nowhere to put a result; completing is all there is to do
			return Promise.complete();
		}
	}
}
