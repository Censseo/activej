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

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.MemSize;
import io.activej.common.ref.Ref;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.http.IWebSocket;
import io.activej.http.RoutingServlet;
import io.activej.http.WebSocketServlet;
import io.activej.jsonrpc.service.AbstractBidirectionalTransportConformanceTest;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.ConnectingTransport;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The reverse-direction conformance harness instantiated over a <b>real WebSocket connection</b>, the
 * <b>server as caller</b> (T017, US4, FR-074…FR-077, SC-002/003): every one of the 30 vectors replays
 * with the server initiating the call and the <b>client's</b> dispatcher — the harness's
 * {@code clientPeer} — answering, over a real {@link HttpServer} and a real {@link HttpClient}
 * WebSocket connection per exchange. The harness's dispatcher, service interface and comparison rules
 * are <b>not</b> overridden; the vectors are replayed, never copied.
 * <p>
 * <b>The subject wiring (D10).</b> {@code createServerTransport(clientPeer)} starts an
 * {@code acceptOnce} server on port {@code 0} whose {@code onWebSocket} exposes the <b>server-side</b>
 * transport, connects a real client, and wires that client's <i>peer handler</i> to the harness's
 * {@code clientPeer}: every inbound document the server sends is dispatched through
 * {@code clientPeer.dispatch(...)} and the answer written back — the same role a client's
 * {@code JsonRpcClient.withPeerHandler(clientPeer)} plays, realised here directly on the transport so
 * the connection can be closed after <b>each</b> inbound document. The client side then <b>closes the
 * connection after answering it — or not</b> (D8): the notification vectors produce no answer, and
 * the close is what lets {@code TestUtils.await}'s quiescence loop return (R3).
 * <p>
 * <b>The lazy handshake (D8).</b> {@code createServerTransport} is called synchronously, before the
 * loop has run, so the server transport is returned as a {@link ConnectingTransport} that defers
 * {@code setListener}/{@code send} until the connection resolves — which happens inside the harness's
 * {@code await(send)}. The server transport is released to the harness <b>only after the client side
 * is wired</b> (the server's {@code onWebSocket} precedes the {@code 101} the client's handshake
 * awaits, so both are ready by then): the harness's {@code send} must not outrun the client's
 * listener.
 * <p>
 * <b>FR-071 — the raised transport tier, and why {@code skippedVectors()} is empty.</b> In this
 * direction the <b>client receives</b> {@code envelope-too-large}'s 1,048,577-byte request, so the
 * client's tier sits strictly above the 1 mb envelope tier: the shared {@link HttpClient} is built
 * with {@code withMaxWebSocketMessageSize} 2 mb, letting the document reach the client's decoder and
 * answer {@code -32001} rather than dying {@code 1009} at the transport tier.
 * <p>
 * <b>FR-076 — the reorder test runs, roles swapped.</b> {@code createReorderableTransport(clientPeer)}
 * returns the same in-memory holding double over the harness's {@code clientPeer} dispatcher (D9), so
 * the harness's correlation-by-id test runs rather than {@code assumeTrue}-skipping — with the
 * harness's server as the caller and {@code clientPeer} as the answering client dispatcher (SC-002/003).
 */
public final class JsonRpcWsBidirectionalConformanceTest extends AbstractBidirectionalTransportConformanceTest {
	// the harness's @ClassRule EventloopRule + ByteBufRule + ActivePromisesRule are inherited

	private final List<HttpServer> servers = new ArrayList<>();
	private @Nullable HttpClient client;

	@Override
	protected JsonRpcTransport createServerTransport(JsonRpcDispatcher clientPeer) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		Ref<JsonRpcWsTransport> serverSide = new Ref<>();
		WebSocketServlet servlet = new WebSocketServlet(reactor) {
			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				serverSide.set(JsonRpcWsTransport.of(reactor, webSocket));
			}
		};
		HttpServer server = HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", servlet)
				.build())
			.withListenPort(0)                                     // FR-078: :0, then asked where it landed
			.withAcceptOnce()                                      // D8 — the accept socket must not outlive one exchange
			.withReadWriteTimeout(Duration.ZERO)                   // FR-096: the 60 s sweep never kills a session mid-test
			.build();
		try {
			server.listen();                                                       // reactor thread = JUnit thread here
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		servers.add(server);
		if (client == null) {                                                      // lazy, shared (D8)
			client = HttpClient.builder(reactor,
					DnsClient.create(reactor, HttpUtils.inetAddress("8.8.8.8")))   // IP literal, no DNS traffic
				.withMaxWebSocketMessageSize(MemSize.megabytes(2))                 // FR-071: the client RECEIVES envelope-too-large
				.build();
		}
		InetSocketAddress bound = server.getBoundAddresses().get(0);
		SettablePromise<JsonRpcWsTransport> serverTransport = new SettablePromise<>();
		JsonRpcWsTransport.connect(reactor, client,
				HttpRequest.get("ws://127.0.0.1:" + bound.getPort()).build())
			.whenComplete((clientTransport, e) -> {
				if (e != null) {
					serverTransport.setException(e);              // handshake failure: the harness's send fails with it
					return;
				}
				// the server's onWebSocket ran before the 101 the client just received, so the
				// server-side transport exists; wire the client's peer handler to clientPeer and only
				// then release the server transport — the harness's send must not outrun the client's listener
				wireClient(clientPeer, clientTransport);
				serverTransport.set(serverSide.get());
			});
		// lazy handshake: resolves inside the harness's await(send) loop run (D8)
		return new ConnectingTransport(serverTransport);
	}

	@After
	public void stopServers() {                                                    // the harness closes only the transport
		for (HttpServer server : servers) server.close();
		servers.clear();
		((Eventloop) Reactor.getCurrentReactor()).run();                           // process the close tasks
	}

	@Override
	protected void awaitDelivery() {
		// D8: await(send) already ran the loop to quiescence (the client closed after answering);
		// this is the FR-072-mandated second drive — a no-op when idle. Never blocks.
		((Eventloop) Reactor.getCurrentReactor()).run();
	}

	@Override
	protected Set<String> skippedVectors() {
		// FR-071: EMPTY. The client's transport tier is raised to 2 mb, strictly above the 1 mb
		// JsonRpcLimits.MAX_BODY_SIZE envelope tier, so envelope-too-large's 1,048,577-byte request
		// reaches the client's decoder and answers -32001 — no vector is skipped.
		return Set.of();
	}

	@Override
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher clientPeer) {
		return new ReorderableWsDouble(clientPeer);
	}

	/**
	 * The client side of every exchange: the client's <b>peer handler</b> wired to the harness's
	 * {@code clientPeer} (D10) — every inbound document the server sends is dispatched through it and
	 * the answer written back — with the close-after-answer discipline of D8: the client closes the
	 * connection once the answer is written, or immediately for a notification (which produces no
	 * answer). The close is what lets {@code TestUtils.await}'s quiescence loop return: without it,
	 * the open connection keeps {@code Eventloop.isAlive()} true (R3) and the harness would never see
	 * the end of the exchange.
	 */
	private static void wireClient(JsonRpcDispatcher clientPeer, JsonRpcWsTransport clientTransport) {
		clientTransport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				clientPeer.dispatch(document).whenResult(response -> {
					Promise<Void> write = response.length > 0 ? clientTransport.send(response) : Promise.complete();
					write.whenComplete(() -> clientTransport.closeEx(new AsyncCloseException("exchange complete")));
				});
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				// the server-side transport is the harness's to close per exchange; nothing to do client-side
			}
		});
	}

	/**
	 * The reorderable subject, roles swapped (FR-076, D9): an in-memory {@link JsonRpcTransport} double
	 * over the harness's real {@code clientPeer} dispatcher. The harness plays the server caller: it
	 * builds a {@code JsonRpcClient} over this double and issues three proxy calls, whose requests the
	 * double {@code dispatch}es to {@code clientPeer}; the three answers are held — the harness's
	 * synchronous {@code heldCount() == 3} assertion needs them to exist before the loop has run — and
	 * {@link #releaseInReverseOrder()} delivers them last-held-first, the order a transport whose §6
	 * guarantee is "none" legitimately produces. The {@code JsonRpcClient} correlates by {@code id} and
	 * every promise resolves correctly. A test double, not a component: no reactor guard, driven on the
	 * reactor thread by the harness.
	 */
	private static final class ReorderableWsDouble implements JsonRpcTransport, ReorderableTransport {
		private final JsonRpcDispatcher clientPeer;                 // the harness's real client dispatcher

		private ReorderableWsDouble(JsonRpcDispatcher clientPeer) {
			this.clientPeer = clientPeer;
		}

		private final List<byte[]> held = new ArrayList<>();
		private @Nullable Listener listener;
		private boolean holding;
		private boolean closed;

		@Override
		public Promise<Void> send(byte[] document) {
			clientPeer.dispatch(document)                            // total; synchronous for ConformanceApi
				.whenResult(response -> {
					if (closed || response.length == 0) return;       // obligation 3: "no response" = no call
					if (holding) held.add(response);
					else listener.onDocument(response);
				});
			return Promise.complete();
		}

		@Override
		public void setListener(Listener listener) {
			this.listener = listener;
		}

		@Override
		public void closeEx(Exception e) {
			if (!closed) {
				closed = true;
				if (listener != null) listener.onClosed(e);
			}
		}

		@Override
		public JsonRpcTransport transport() {
			return this;
		}

		@Override
		public void startHolding() {
			holding = true;
		}

		@Override
		public int heldCount() {
			return held.size();
		}

		@Override
		public void releaseInReverseOrder() {
			for (int i = held.size() - 1; i >= 0; i--) listener.onDocument(held.get(i));
			held.clear();
		}
	}
}
