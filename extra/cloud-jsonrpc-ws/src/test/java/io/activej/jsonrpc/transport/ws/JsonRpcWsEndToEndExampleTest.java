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

package io.activej.jsonrpc.transport.ws;

import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
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
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.test.EventloopThread.await;
import static org.junit.Assert.assertEquals;

/**
 * The runnable, self-checking example of this feature (FR-063): one annotated interface per direction,
 * a real {@link HttpServer} mounting {@link JsonRpcWsServlet}, and a client connected through
 * {@link JsonRpcWsTransport#connect} — with the server <b>pushing a notification</b> and <b>calling
 * the client and awaiting its answer</b>, while the client calls the server. The whole bidirectional
 * story on real sockets, asserted.
 * <p>
 * The example lives in this module's {@code src/test} and runs on every build under {@code -P extra}
 * (the class name carries the {@code Test} suffix for exactly that reason — Surefire's include
 * patterns are what make "compiled and exercised by the build" a fact rather than a promise).
 * <p>
 * The browser equivalent — no ActiveJ code at all, the wire is plain JSON-RPC 2.0 in TEXT messages:
 * <pre>{@code
 * // the client the browser would run — same wire, same JSON-RPC 2.0 documents
 * const ws = new WebSocket("ws://localhost:PORT/ws");
 *
 * // client -> server: a call, answered by the server's dispatcher
 * ws.onopen = () => ws.send(JSON.stringify({
 *   "jsonrpc": "2.0", "id": 1, "method": "calc.add",
 *   "params": { "a": 2, "b": 3 }
 * }));
 *
 * // server -> client: the server's broadcast arrives as one TEXT message per document
 * ws.onmessage = (event) => {
 *   const doc = JSON.parse(event.data);
 *   if (doc.method === "events.changed") console.log("pushed", doc.params.user);
 *   if (doc.id !== undefined) {
 *     // answer a server-initiated call: the reply echoes its id
 *     ws.send(JSON.stringify({
 *       "jsonrpc": "2.0", "id": doc.id,
 *       "result": "decided-42"
 *     }));
 *   }
 * };
 * }</pre>
 * {@code PORT} is whatever the server printed at the start of this test — the port is bound by the
 * kernel and read back, never guessed (ADR-028). The Java calls below produce exactly those
 * exchanges, and the assertions check the decoded results on both sides.
 * <p>
 * The server binds <b>port {@code 0}</b> and is asked where it landed via
 * {@link HttpServer#getBoundAddresses()} — {@code TestUtils.getFreePort()} is deliberately absent
 * from this module (FR-078). The example's own reactor runs on a dedicated {@link EventloopThread},
 * which keeps a listening server and its open connection alive while the JUnit thread awaits
 * answers; {@code readWriteTimeout} is set to {@code 0} so the 60 s connection sweep can never kill
 * the long-lived session mid-example (FR-096).
 */
public final class JsonRpcWsEndToEndExampleTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private EventloopThread loop;
	private HttpServer server;
	private JsonRpcWsServlet wsServlet;
	private HttpClient httpClient;
	private JsonRpcClient client;

	/** The last notification pushed by the server — lets the example check the broadcast reached the client. */
	private final AtomicReference<String> lastPushed = new AtomicReference<>();


	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-ws-example");
		loop.submit(() -> {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(Calculator.class, new CalculatorImpl())
				.build();
			wsServlet = JsonRpcWsServlet.builder(loop.eventloop(), dispatcher)
				.build();
			server = HttpServer.builder(loop.eventloop(), RoutingServlet.builder(loop.eventloop())
					.withWebSocket("/ws", wsServlet)
					.build())
				.withListenPort(0)                          // :0, then asked where it landed (ADR-028)
				.withReadWriteTimeout(Duration.ZERO)         // FR-096: the 60 s sweep would kill the session
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
	 * The example end to end: the client calls the server, the server broadcasts a notification to the
	 * client, and the server calls the client and awaits its answer — three exchanges in two
	 * directions over one connection, all asserted.
	 */
	@Test
	public void theBidirectionalExample() {
		int port = server.getBoundAddresses().get(0).getPort();
		System.out.println("JSON-RPC over WebSocket example: listening on ws://127.0.0.1:" + port + "/ws");

		connectClient(port);

		// client -> server: the call the browser's onopen would send, answered by the server's dispatcher.
		// proxy() and the call itself are reactor-confined — the proxy is issued on the loop thread
		Sum sum = await(loop.submit(() -> {
				Calculator api = client.proxy(Calculator.class);
				return api.add(2, 3);
			})
			.toCompletableFuture(), "calc.add");
		assertEquals("the result decoded from the wire", 5, sum.value());
		assertEquals("no correlation entry is left behind", 0, (int) loop.submit(client::inFlightCount));

		// server -> client, as a broadcast: one notification reaches the connected client
		loop.submit(() -> wsServlet.broadcast(Events.class, events -> events.changed(new User("ada"))));
		awaitUntilSet("events.changed broadcast");
		assertEquals("the broadcast notification reached the client", "ada", lastPushed.getAndSet(null));

		// server -> client, as a call: the server initiates, the client answers, the server's promise
		// resolves with the client's answer — the await is on the decide promise itself
		String decided = await(loop.submit(() -> {
				// one connected session is the example's; capture its decide promise
				return wsServlet.sessions().iterator().next().proxy(Events.class).decide("shall we?");
			})
			.toCompletableFuture(), "events.decide call");
		assertEquals("the server-initiated call's answer round-tripped", "decided-shall we?", decided);

		// the client's own in-flight table is empty after the call it answered
		assertEquals(0, (int) loop.submit(client::inFlightCount));

		// close the client cleanly BEFORE the server closes, so no connection is torn down mid-read
		// (the core-http close-path read-buffer strand documented in JsonRpcWsOversizeTest)
		loop.submit(() -> client.closeEx(new java.io.IOException("example complete")));
	}

	/**
	 * Polls the {@link #lastPushed} latch until it is set. The loop runs continuously on its own
	 * {@link EventloopThread}, so the notification is delivered in the background while the JUnit
	 * thread polls — a notification has no promise to await, so a bounded poll is the honest wait.
	 */
	private void awaitUntilSet(String what) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (lastPushed.get() == null && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted awaiting " + what, e);
			}
		}
		if (lastPushed.get() == null) {
			throw new AssertionError("timed out awaiting " + what);
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// The example's wiring — the whole of what a developer writes.
	// ---------------------------------------------------------------------------------------------------

	private void connectClient(int port) {
		// the handshake is async: submit() issues the connect on the loop thread and returns its
		// promise; toCompletableFuture + await then block the JUnit thread until the 101 resolved
		// and the client exists (the promise completes with it)
		await(loop.submit(() -> {
				JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(Events.class, new EventsImpl())
					.build();
				return JsonRpcWsTransport.connect(loop.eventloop(), httpClient,
						HttpRequest.get("ws://127.0.0.1:" + port + "/ws").build())
					.map(transport -> {
						client = JsonRpcClient.builder(loop.eventloop(), transport)
							.withPeerHandler(clientDispatcher)      // the whole server -> client direction
							.build();
						return client;
					});
			})
			.toCompletableFuture(), "client connect");
	}

	/**
	 * The whole of what a developer writes for the client-facing direction (FR-063): one annotated
	 * interface is the protocol. Every wire name is explicit — an empty {@code @JsonRpcMethod} value
	 * would fall back to the Java identifier, and a later rename would silently change the wire format.
	 */
	@JsonRpcService("events")
	public interface Events {
		/** Wire name {@code events.changed}: a notification pushed to every client, never answered. */
		@JsonRpcNotification("changed")
		void changed(@JsonRpcParam("user") User user);

		/** Wire name {@code events.decide}: a server-initiated call, answered by the client. */
		@JsonRpcMethod("decide")
		Promise<String> decide(@JsonRpcParam("question") String question);
	}

	/** The client's implementation of the events interface — what the server's calls dispatch to. */
	final class EventsImpl implements Events {
		@Override
		public void changed(User user) {
			// the example latches the pushed payload on the client side to prove delivery
			lastPushed.set(user.name());
		}

		@Override
		public Promise<String> decide(String question) {
			return Promise.of("decided-" + question);
		}
	}

	/** The record a notification carries — a codec is derived for any record, nothing to register. */
	public record User(String name) {}

	/** The client-facing interface of the server — what the client calls (the same as the browser's). */
	@JsonRpcService("calc")
	public interface Calculator {
		/** Wire name {@code calc.add}: two named parameters in, one record out. */
		@JsonRpcMethod("add")
		Promise<Sum> add(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b);
	}

	/** The record result of {@link Calculator#add} — a codec is derived for any record, nothing to register. */
	public record Sum(int value) {}

	/** The implementation the server dispatches to. */
	static final class CalculatorImpl implements Calculator {
		@Override
		public Promise<Sum> add(int a, int b) {
			return Promise.of(new Sum(a + b));
		}
	}
}
