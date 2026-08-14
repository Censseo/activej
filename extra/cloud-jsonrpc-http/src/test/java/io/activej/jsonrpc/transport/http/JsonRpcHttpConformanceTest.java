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
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.service.AbstractTransportConformanceTest;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Feature 012's conformance harness instantiated over a <b>real socket</b> (T042–T045, SC-001):
 * every one of the 30 vectors replays end-to-end through {@link JsonRpcHttpClientTransport} against
 * a real {@link HttpServer} mounting the real {@link JsonRpcServlet} over the harness's own
 * dispatcher. The harness's dispatcher, service interface and comparison rules are <b>not</b>
 * overridden (FR-054); the vectors are replayed, never copied (FR-051); no HTTP-specific harness is
 * written; no {@code StubHttpClient} appears (FR-056).
 * <p>
 * <b>Server lifecycle (plan D4/D5).</b> {@code io.activej.promise.TestUtils.await} — which
 * the harness drives every exchange with — runs the eventloop to <b>quiescence</b>, and a live
 * accept socket prevents quiescence (plan F1). Every server is therefore <b>{@code withAcceptOnce()}</b>:
 * one exchange = one transport = one accept, and with the default no-keep-alive {@link HttpClient}
 * every channel is closed by the time the send promise has been awaited (plan F2). One lazy shared
 * real client; the URL's host is fixed at {@code 127.0.0.1} — an IP literal, which never touches DNS
 * (plan F7: {@code DnsClient} over {@code 8.8.8.8} sees zero UDP traffic). The harness closes only
 * the transport per exchange; the servers are this class's to close, in {@link #stopServers()}.
 * <p>
 * <b>T043 — {@link #awaitDelivery()}.</b> FR-055 mandates the override; under this design
 * {@code await(send)} has already run the loop to quiescence, so the override is a defensive second
 * drive — a no-op when idle, the load-bearing pump if a future change ever completed send's promise
 * before the response was processed (which FR-034 forbids). The quiescence invariant of D4
 * guarantees it never blocks.
 * <p>
 * <b>T044 — {@link #skippedVectors()}.</b> Exactly {@code envelope-too-large}, reason quoted from
 * research.md §5 R7: the vector's request is {@code MAX_BODY_SIZE + 1} bytes, so through HTTP the
 * body never reaches the decoder — the declared {@code Content-Length} answers the servlet's
 * up-front {@code 413} (FR-022), or the connection's hardcoded {@code 400} mid-stream (FR-023), and
 * the envelope's {@code -32001} can never be produced. Legal: not in {@code REQUIRED_COVERAGE}, and
 * the harness fails a stale skip by design.
 * <p>
 * <b>T045 — {@link #createReorderableTransport(JsonRpcDispatcher)}.</b> The reordered-correlation
 * test <b>runs</b> rather than {@code assumeTrue}-skipping. The subject is an <b>in-memory double</b>
 * over the harness's real dispatcher: the harness asserts {@code heldCount() == 3} synchronously
 * after three proxy calls, before the loop has run — no socket-backed transport can satisfy that
 * (plan F9). Two concurrent POSTs are independent exchanges with no ordering relationship, so a
 * double that holds and reorders their answers is faithful HTTP behaviour <i>modelled</i> (FR-053),
 * and the client's id-correlation (feature 012 FR-066) is what the harness proves. Settles research
 * unknown R6.
 */
public final class JsonRpcHttpConformanceTest extends AbstractTransportConformanceTest {
	// the harness's @ClassRule EventloopRule + ByteBufRule + ActivePromisesRule are inherited (F17)

	private final List<HttpServer> servers = new ArrayList<>();
	private @Nullable HttpClient client;

	@Override
	protected JsonRpcTransport createTransport(JsonRpcDispatcher peer) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		JsonRpcServlet servlet = JsonRpcServlet.create(reactor, peer);          // the harness's dispatcher
		HttpServer server = HttpServer.builder(reactor, servlet)
			.withListenPort(0)                                                 // FR-050a: :0, then asked where it landed
			.withAcceptOnce()                                                  // plan F1/F8 — REQUIRED (D4)
			.build();
		try {
			server.listen();                                                   // reactor thread = JUnit thread here
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		servers.add(server);
		if (client == null) {                                                  // lazy, shared (D5)
			client = HttpClient.create(reactor,
				DnsClient.create(reactor, HttpUtils.inetAddress("8.8.8.8")));  // plan F7: IP literal, no DNS traffic
		}
		InetSocketAddress bound = server.getBoundAddresses().get(0);
		return JsonRpcHttpClientTransport.builder(reactor, client,
			"http://127.0.0.1:" + bound.getPort() + "/").build();
	}

	@After
	public void stopServers() {                                                // the harness closes only the transport
		for (HttpServer server : servers) server.close();
		servers.clear();
		((Eventloop) Reactor.getCurrentReactor()).run();                       // process the close tasks
	}

	@Override
	protected void awaitDelivery() {
		// plan F1: TestUtils.await already ran the loop to quiescence (acceptOnce server + default
		// no-keep-alive client); this is the FR-055-mandated second drive — a no-op when idle, and the
		// load-bearing pump if a future change ever completed send's promise before the response was
		// processed (which FR-034 forbids). Never blocks: D4's quiescence invariant guarantees it.
		((Eventloop) Reactor.getCurrentReactor()).run();
	}

	@Override
	protected Set<String> skippedVectors() {
		// R7 (research.md §5): the vector's request is 1,048,577 bytes — JsonRpcLimits.MAX_BODY_SIZE + 1.
		// Through HTTP the body never reaches the decoder: the declared Content-Length exceeds the
		// servlet's bound and the up-front 413 answers (FR-022) — or the connection's hardcoded 400
		// mid-stream (FR-023) — so the envelope's -32001 can never be produced. Legal: the name is not
		// in REQUIRED_COVERAGE, and the harness fails a stale skip by design.
		return Set.of("envelope-too-large");
	}

	@Override
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher peer) {
		return new ReorderableHttpDouble(peer);
	}

	/**
	 * The reorderable subject: an in-memory {@link JsonRpcTransport} double over the harness's real
	 * dispatcher (plan D7). {@code dispatch} over the harness's {@code ConformanceApi} completes
	 * synchronously, so the three responses are held by the time the three proxy calls return —
	 * which is exactly what the harness's synchronous {@code heldCount() == 3} assertion needs (F9).
	 * {@link #releaseInReverseOrder()} delivers last-held-first; the {@code JsonRpcClient} on top
	 * correlates by {@code id} and every promise resolves correctly. Close is idempotent (the
	 * harness's {@code client.close()} closes the double). A test double, not a component: no reactor
	 * guard, and the harness drives it on the reactor thread anyway (plan T045 risk d).
	 */
	private static final class ReorderableHttpDouble implements JsonRpcTransport, ReorderableTransport {
		private final JsonRpcDispatcher peer;                       // the harness's real dispatcher

		private ReorderableHttpDouble(JsonRpcDispatcher peer) {
			this.peer = peer;
		}

		private final List<byte[]> held = new ArrayList<>();
		private @Nullable Listener listener;
		private boolean holding;
		private boolean closed;

		@Override
		public Promise<Void> send(byte[] document) {
			peer.dispatch(document)                                  // total (F14); synchronous for ConformanceApi
				.whenResult(response -> {
					if (closed || response.length == 0) return;      // obligation 3: "no response" = no call
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
