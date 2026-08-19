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
import io.activej.async.function.AsyncRunnable;
import io.activej.common.MemSize;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketServlet;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.ConformanceVectors;
import io.activej.jsonrpc.ConformanceVectors.Vector;
import io.activej.jsonrpc.service.AbstractTransportConformanceTest;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.ConnectingTransport;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static io.activej.jsonrpc.ConformanceJson.assertJsonEquals;
import static io.activej.jsonrpc.ConformanceJson.parseJson;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Feature 012's conformance harness instantiated over a <b>real WebSocket connection</b>, the
 * <b>client as caller</b> (T016, US4, FR-070…FR-073, SC-001): every one of the 30 vectors replays
 * end to end through {@link JsonRpcWsTransport} against a real {@link HttpServer} mounting a
 * {@link JsonRpcWsTransport}-backed endpoint over the harness's own dispatcher, with a real
 * {@link HttpClient} WebSocket connection per exchange. The harness's dispatcher, service interface
 * and comparison rules are <b>not</b> overridden; the vectors are replayed, never copied.
 * <p>
 * <b>Server lifecycle (D8 — quiescence by construction).</b> {@code TestUtils.await} — which the
 * harness drives every exchange with — runs the eventloop to <b>quiescence</b>, and
 * {@code Eventloop.isAlive()} counts selector keys: a live accept socket <i>or</i> connection blocks
 * return (R3). Every server is therefore <b>{@code withAcceptOnce()}</b> (the accept socket dies after
 * one exchange) and the server side <b>closes the connection after processing each inbound
 * document</b> — answering it <i>or not</i>, because the notification vectors need the close for
 * quiescence too. That close-after-answer is subject-level policy (test code, not the transport),
 * implemented in the {@link WebSocketServlet} the subject mounts: each inbound document is
 * {@code peer.dispatch}-ed, the answer (if any) written, then the connection closed once the write
 * completes.
 * <p>
 * <b>The lazy handshake (D8).</b> {@code createTransport} is called synchronously, before the loop has
 * run, so the client upgrade is issued and the returned {@link ConnectingTransport} defers
 * {@code setListener}/{@code send} until the handshake resolves — which happens inside the harness's
 * {@code await(send)}. One real client connection per exchange.
 * <p>
 * <b>FR-071 — the raised transport tier, and why {@code skippedVectors()} is empty.</b> The
 * {@code envelope-too-large} vector's request is {@code JsonRpcLimits.MAX_BODY_SIZE + 1} bytes; the
 * side that <b>receives</b> it sets its transport tier strictly above the envelope tier, so the
 * document reaches the decoder and answers {@code -32001} rather than dying {@code 1009} at the
 * transport tier. Here the server receives it, so the server is built with
 * {@code withMaxWebSocketMessageSize} 2 mb (vs the 1 mb envelope tier) — a strictly stronger result
 * than HTTP's subject, which must skip the vector (its up-front {@code 413} fires first).
 * <p>
 * <b>T043 — {@link #awaitDelivery()}.</b> FR-072 mandates the override; under this design
 * {@code await(send)} has already run the loop to quiescence (the connection closed itself), so the
 * override is a defensive second drive — a no-op when idle. The quiescence invariant of D8 guarantees
 * it never blocks.
 * <p>
 * <b>T045 — {@link #createReorderableTransport(JsonRpcDispatcher)}.</b> The reordered-correlation
 * test <b>runs</b> rather than {@code assumeTrue}-skipping, over an <b>in-memory holding double</b>
 * (D9, FR-073): the harness asserts {@code heldCount() == 3} synchronously after three proxy calls,
 * before the loop has run — no socket-backed transport can satisfy that (plan F9). The double models
 * a transport whose §6 ordering guarantee is "none" (a server answers a slow call late), and the
 * client's id-correlation (feature 012 FR-066) is what the harness proves.
 */
public final class JsonRpcWsConformanceTest extends AbstractTransportConformanceTest {
	// the harness's @ClassRule EventloopRule + ByteBufRule + ActivePromisesRule are inherited

	private final List<HttpServer> servers = new ArrayList<>();
	private @Nullable HttpClient client;

	@Override
	protected JsonRpcTransport createTransport(JsonRpcDispatcher peer) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		int port = listen(servlet(peer));
		Promise<JsonRpcWsTransport> connecting = JsonRpcWsTransport.connect(reactor, httpClient(reactor),
			HttpRequest.get("ws://127.0.0.1:" + port).build());
		// lazy handshake: resolves inside the harness's await(send) loop run (D8)
		return new ConnectingTransport(connecting);
	}

	/**
	 * Starts one {@code acceptOnce} server on port {@code 0} and answers where the kernel put it
	 * (ADR-028; {@code getFreePort()} is refused in this module, FR-078). Shared by the per-exchange
	 * subject above and by the two bespoke tests below, so all three servers are built identically —
	 * the 2 mb transport tier of FR-071 included.
	 */
	private int listen(WebSocketServlet servlet) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		HttpServer server = HttpServer.builder(reactor, servlet)
			.withListenPort(0)                                     // FR-078: :0, then asked where it landed
			.withAcceptOnce()                                      // D8 — the accept socket must not outlive one connection
			.withMaxWebSocketMessageSize(MemSize.megabytes(2))     // FR-071: the server RECEIVES envelope-too-large
			.withReadWriteTimeout(Duration.ZERO)                   // FR-096: the 60 s sweep never kills a session mid-test
			.build();
		try {
			server.listen();                                                       // reactor thread = JUnit thread here
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		servers.add(server);
		return server.getBoundAddresses().get(0).getPort();
	}

	/** The lazily created, shared client (D8). An IP literal is used, so no DNS traffic is ever issued. */
	private HttpClient httpClient(NioReactor reactor) {
		if (client == null) {
			client = HttpClient.create(reactor,
				DnsClient.create(reactor, HttpUtils.inetAddress("8.8.8.8")));
		}
		return client;
	}

	@After
	public void stopServers() {                                                    // the harness closes only the transport
		for (HttpServer server : servers) server.close();
		servers.clear();
		((Eventloop) Reactor.getCurrentReactor()).run();                           // process the close tasks
	}

	@Override
	protected void awaitDelivery() {
		// D8: await(send) already ran the loop to quiescence (close-after-answer closed the connection);
		// this is the FR-072-mandated second drive — a no-op when idle, the load-bearing pump if a
		// future change ever completed send's promise before the response was processed. Never blocks.
		((Eventloop) Reactor.getCurrentReactor()).run();
	}

	@Override
	protected Set<String> skippedVectors() {
		// FR-071: EMPTY. The server's transport tier is raised to 2 mb, strictly above the 1 mb
		// JsonRpcLimits.MAX_BODY_SIZE envelope tier, so envelope-too-large's 1,048,577-byte request
		// reaches the decoder and answers -32001 — no vector is skipped (unlike the HTTP subject,
		// whose up-front 413 makes the envelope code unreachable).
		return Set.of();
	}

	@Override
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher peer) {
		return new ReorderableWsDouble(peer);
	}

	/**
	 * The server-side endpoint of every exchange: an {@code acceptOnce} servlet whose
	 * {@code onWebSocket} wraps the accepted socket in a {@link JsonRpcWsTransport} and answers each
	 * inbound document through the harness's dispatcher, then <b>closes the connection after the
	 * answer is written</b> — or immediately, for a notification (D8). The close is what lets
	 * {@code TestUtils.await}'s quiescence loop return: without it, the open connection keeps
	 * {@code Eventloop.isAlive()} true (R3) and the harness would never see the end of the exchange.
	 * The close frame travels after the answer, so the answer is fully delivered before the channel
	 * dies.
	 */
	private static WebSocketServlet servlet(JsonRpcDispatcher peer) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		return new WebSocketServlet(reactor) {
			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor, webSocket);
				transport.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {
						peer.dispatch(document).whenResult(response -> {
							Promise<Void> write = response.length > 0 ? transport.send(response) : Promise.complete();
							write.whenComplete(() -> transport.closeEx(new AsyncCloseException("exchange complete")));
						});
					}

					@Override
					public void onClosed(@Nullable Exception e) {
						// the harness closes its own transport per exchange; nothing to do server-side
					}
				});
			}
		};
	}

	// ---------------------------------------------------------------------------------------------------
	// Two adversarial tests the inherited harness structurally cannot carry. Both are bespoke @Test
	// methods, not overrides: the harness is architected one transport per exchange (D8 above), so
	// neither an accumulated connection nor a genuinely late answer is reachable from inside it.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * <b>E3, adversarial plan</b> — all 30 vectors replayed over <b>one persistent connection</b>.
	 * <p>
	 * The inherited suite calls {@code createTransport} once per exchange <i>by design</i> (D8 above, and
	 * the harness's own "a fresh dispatcher and a fresh transport per exchange"), so nothing it asserts can
	 * observe state that accumulates on a connection. A deployed session is the opposite: one socket
	 * carrying every document a peer ever sends. This replays the same 30 vectors, in order, over one
	 * client, one server and one {@link JsonRpcWsTransport} pair.
	 * <p>
	 * <b>The fence.</b> A vector expecting <i>no</i> response cannot be proven by waiting — there is nothing
	 * to wait for. Each vector's request is therefore followed immediately by a <b>fence</b>: an ordinary
	 * {@code subtract} call whose {@code id} names the vector's index. Sends are serialised on one
	 * connection (the transport's write queue, FR-017) and the server dispatches in the order it read, so
	 * the fence's answer closes the vector's window: everything delivered before it belongs to the vector,
	 * and an empty window <i>is</i> "no response at all".
	 * <p>
	 * <b>What is asserted beyond "each vector still passes".</b> {@code JsonRpcDispatcher} deliberately owns
	 * no in-flight registry and has no {@code close()} (FR-057a), so there is no server-side counter to read
	 * back to zero; the equivalent evidence is counted here instead — the exact document total (one answer
	 * per answering vector plus 30 fence answers, so a document leaking out of one vector's decode state
	 * into the next vector's window fails), every fence answered with <b>its own</b> id and the right
	 * result, an empty window after the last fence, and a connection that was never closed during the
	 * replay. {@code ByteBufRule} covers the buffers.
	 */
	@Test
	public void allThirtyVectorsReplayOverOnePersistentConnection() {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		List<Vector> vectors = ConformanceVectors.loadAll();
		assertFalse("the vector set must not be empty", vectors.isEmpty());

		int port = listen(persistentServlet(persistentDispatcher()));
		PersistentInbox inbox = new PersistentInbox();
		List<String> failures = new ArrayList<>();

		await(JsonRpcWsTransport.connect(reactor, httpClient(reactor),
				HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(transport -> {
				transport.setListener(inbox);                        // one listener, for all 30 exchanges
				return Promises.sequence(IntStream.range(0, vectors.size())
						.mapToObj(i -> (AsyncRunnable) () ->
							replayOverOneConnection(transport, vectors.get(i), i, inbox, failures)))
					.whenComplete(() -> {
						inbox.replayComplete = true;
						// R3: the connection must die for TestUtils.await's quiescence loop to return
						transport.closeEx(new AsyncCloseException("persistent replay complete"));
					});
			}));

		if (!failures.isEmpty()) {
			fail(failures.size() + " of " + vectors.size() + " vectors failed over one persistent " +
				 "connection:\n\t" + String.join("\n\t", failures));
		}
		assertTrue("no notification may have failed while replaying: " + dispatcherFailures,
			dispatcherFailures.isEmpty());

		// the connection carried every exchange: 30 fences, each answered with its own id and result
		assertEquals("every vector must have been fenced", vectors.size(), inbox.fenceAnswers.size());
		for (int i = 0; i < vectors.size(); i++) {
			assertJsonEquals(
				parseJson("{\"jsonrpc\":\"2.0\",\"id\":\"fence-" + i + "\",\"result\":19}"),
				parseJson(inbox.fenceAnswers.get(i)));
		}

		// no leaked state: the document total is exactly what the vectors and the fences owe, so nothing
		// spilled out of one vector's decode state into the next vector's window on the shared connection
		int owed = vectors.size();                                   // one fence answer per vector
		for (Vector vector : vectors) if (!vector.expectsNoResponse()) owed++;
		assertEquals("the connection delivered exactly what the vectors and their fences owe",
			owed, inbox.documents);
		assertTrue("nothing may be left in the window after the last fence: " + render(inbox.window),
			inbox.window.isEmpty());
		assertFalse("the connection must survive all 30 vectors — no vector may close it",
			inbox.closedDuringReplay);
	}

	/**
	 * <b>E4, adversarial plan</b> — <b>real</b> out-of-order delivery, not the in-memory double.
	 * <p>
	 * {@link ReorderableWsDouble} (the harness's FR-094 subject, below) resolves synchronously and hands the
	 * held documents back in an order the test chooses; nothing about a socket is involved. Here three
	 * client-initiated calls go out back to back over one real connection and the <b>server</b> answers each
	 * after a different {@link Promises#delay(Duration, Object) real delay}, chosen so the answers reach the
	 * wire in the reverse of the order the calls were issued: call 1 waits longest, call 3 least, so call
	 * 3's response is written first.
	 * <p>
	 * Every promise must therefore resolve with <b>its own</b> result rather than a neighbour's — the
	 * {@code id} correlation of feature 012 (FR-066) surviving genuine reordering over a socket — and the
	 * client's correlation table must be empty afterwards. The observed completion order is asserted too:
	 * without it, a run in which the delays happened to preserve order would pass while proving nothing.
	 */
	@Test
	public void concurrentCallsAnsweredOutOfOrderByRealDelaysStillCorrelateById() {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		JsonRpcDispatcher peer = JsonRpcDispatcher.builder(reactor)
			.withService(DelayedApi.class, new DelayedApiImpl())
			.build();
		int port = listen(persistentServlet(peer));

		List<String> completionOrder = new ArrayList<>();            // the order the socket delivered in
		int[] inFlight = {-1, -1};                                   // while waiting, and after the answers

		List<String> results = await(JsonRpcWsTransport.connect(reactor, httpClient(reactor),
				HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				DelayedApi api = client.proxy(DelayedApi.class);
				// issued in one reactor callback: all three requests are on the wire before any answer
				Promise<String> first = api.echo("first", 400).whenResult(completionOrder::add);
				Promise<String> second = api.echo("second", 200).whenResult(completionOrder::add);
				Promise<String> third = api.echo("third", 50).whenResult(completionOrder::add);
				inFlight[0] = client.inFlightCount();
				return Promises.toList(first, second, third)
					.whenComplete(() -> {
						inFlight[1] = client.inFlightCount();
						// closes the transport, which closes the connection (R3: quiescence)
						client.closeEx(new AsyncCloseException("delayed calls complete"));
					});
			}));

		assertEquals("three calls were awaiting an answer", 3, inFlight[0]);
		// each promise resolved with the answer to ITS OWN call, in issue order
		assertEquals(List.of("echoed-first", "echoed-second", "echoed-third"), results);
		// ... and the socket really did deliver them backwards, longest delay last
		assertEquals("the server's delays must have inverted the delivery order",
			List.of("echoed-third", "echoed-second", "echoed-first"), completionOrder);
		assertEquals("every entry left the correlation table", 0, inFlight[1]);
	}

	// ---------------------------------------------------------------------------------------------------
	// The two bespoke tests' own wiring.
	// ---------------------------------------------------------------------------------------------------

	/** One vector's window on the shared connection: the request, then the fence, then the comparison. */
	private Promise<Void> replayOverOneConnection(
		JsonRpcWsTransport transport, Vector vector, int index, PersistentInbox inbox, List<String> failures
	) {
		String fenceId = "fence-" + index;
		SettablePromise<Void> fence = inbox.arm('"' + fenceId + '"');
		byte[] fenceRequest = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + fenceId +
							   "\",\"method\":\"subtract\",\"params\":[42,23]}").getBytes(UTF_8);
		return transport.send(vector.request().getBytes(UTF_8))
			.then(() -> transport.send(fenceRequest))
			.then(() -> (Promise<Void>) fence)
			.whenResult(() -> {
				checkVector(vector, inbox.window, failures);
				// the window closes with its fence: a document arriving after it lands in the NEXT
				// vector's window and fails that vector, which is exactly the leak this test hunts
				inbox.window.clear();
			});
	}

	/**
	 * The harness's three comparison rules (FR-092a), applied to one vector's window — value comparison
	 * unless {@code exactBytes}, and "no response" meaning no document at all. Failures are collected
	 * rather than thrown: this runs inside a reactor callback, where an escaping {@code AssertionError}
	 * would reach {@code EventloopRule}'s rethrowing fatal-error handler instead of the report below.
	 */
	private static void checkVector(Vector vector, List<byte[]> window, List<String> failures) {
		try {
			if (vector.expectsNoResponse()) {
				assertEquals("expected no response document at all, got " + render(window), 0, window.size());
				return;
			}
			assertEquals("expected exactly one response document, got " + render(window), 1, window.size());
			byte[] response = window.get(0);
			assertTrue("expected a response document, got zero bytes", response.length > 0);
			String actual = new String(response, UTF_8);
			if (vector.exactBytes()) {
				assertEquals(vector.response(), actual);
				return;
			}
			assertJsonEquals(parseJson(vector.response()), parseJson(actual));
		} catch (AssertionError | RuntimeException e) {
			failures.add(vector.name() + " — " + vector.description() + "\n\t\t" + e.getMessage());
		}
	}

	private static String render(List<byte[]> documents) {
		List<String> text = new ArrayList<>(documents.size());
		for (byte[] document : documents) text.add('<' + new String(document, UTF_8) + '>');
		return documents.isEmpty() ? "nothing at all" : String.join(", ", text);
	}

	/**
	 * The server endpoint of a connection that <b>outlives one exchange</b>: {@link #servlet}'s
	 * close-after-answer policy is exactly what E3 and E4 must not have, so this variant answers each
	 * inbound document and leaves the connection open. Quiescence is the client's job here — both tests
	 * close their own end inside the awaited chain.
	 */
	private static WebSocketServlet persistentServlet(JsonRpcDispatcher peer) {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();
		return new WebSocketServlet(reactor) {
			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor, webSocket);
				transport.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {
						// dispatch is total (FR-038a): there is no failure branch to write
						peer.dispatch(document).whenResult(response -> {
							if (response.length > 0) transport.send(response);
						});
					}

					@Override
					public void onClosed(@Nullable Exception e) {
						// the client owns the lifecycle of these connections; nothing to do server-side
					}
				});
			}
		};
	}

	/** Every notification failure the E3 dispatcher reported — none is expected, and none may pass unnoticed. */
	private final List<String> dispatcherFailures = new ArrayList<>();

	/**
	 * E3's peer: the harness's own service and codecs, rebuilt here because the harness keeps its
	 * {@code dispatcher()} and {@code codecFactory()} private — deliberately, since a subject that could
	 * choose what {@code subtract} means would be conforming to itself. {@code foo.get} stays unregistered,
	 * which is what {@code method-not-found} and one element of {@code batch-mixed} assert.
	 */
	private JsonRpcDispatcher persistentDispatcher() {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withCodecFactory(JsonCodecFactory.defaultInstance().rebuild()
				.with(Data.class, ctx -> JsonCodecs
					.ofArrayObject(JsonCodecs.ofString(), JsonCodecs.ofInteger())
					.transform(
						data -> new Object[]{data.greeting(), data.answer()},
						array -> new Data((String) array[0], (Integer) array[1])))
				.build())
			.withService(ConformanceApi.class, new ConformanceApiImpl())
			// EventloopRule installs a RETHROWING fatal-error handler, so the default route for a
			// notification's failure would fail the run at the point a server would merely log (FR-100)
			.withFailureHandler((descriptor, e) -> dispatcherFailures.add(descriptor.wireName() + ": " + e))
			.build();
	}

	/**
	 * The client end of E3's one connection: every inbound document is counted, and the armed fence marker
	 * — the vector's own {@code id}, quoted, so {@code "fence-1"} can never match {@code "fence-10"} —
	 * closes the current vector's window. The window is emptied by the replay once it has been compared,
	 * never here, so a document arriving late lands in the next vector's window instead of vanishing. A
	 * close mid-replay fails the armed fence rather than leaving the awaited chain to hang forever.
	 */
	private static final class PersistentInbox implements JsonRpcTransport.Listener {
		private final List<byte[]> window = new ArrayList<>();
		private final List<String> fenceAnswers = new ArrayList<>();
		private @Nullable SettablePromise<Void> fence;
		private @Nullable String fenceMarker;
		private int documents;
		private boolean replayComplete;
		private boolean closedDuringReplay;

		private SettablePromise<Void> arm(String marker) {
			fenceMarker = marker;
			fence = new SettablePromise<>();
			return fence;
		}

		@Override
		public void onDocument(byte[] document) {
			documents++;
			String text = new String(document, UTF_8);
			if (fenceMarker != null && text.contains(fenceMarker)) {
				SettablePromise<Void> armed = fence;
				fence = null;
				fenceMarker = null;
				fenceAnswers.add(text);
				armed.set(null);
				return;
			}
			window.add(document);
		}

		@Override
		public void onClosed(@Nullable Exception e) {
			if (!replayComplete) closedDuringReplay = true;
			SettablePromise<Void> armed = fence;
			if (armed == null) return;
			fence = null;
			fenceMarker = null;
			// a lost connection must fail the chain, never hang TestUtils.await's future.get()
			armed.setException(e != null ? e : new AsyncCloseException("the connection closed mid-replay"));
		}
	}

	/**
	 * E4's server-side service: the caller chooses how long its own answer is withheld, which is the whole
	 * mechanism — three calls with three delays produce three answers in the reverse order. Every wire name
	 * is explicit (an empty value would fall back to the Java identifier).
	 */
	@JsonRpcService("delayed")
	public interface DelayedApi {
		/** Wire name {@code delayed.echo}: answers {@code "echoed-" + value} after {@code delayMillis}. */
		@JsonRpcMethod("echo")
		Promise<String> echo(@JsonRpcParam("value") String value, @JsonRpcParam("delayMillis") int delayMillis);
	}

	/** {@link DelayedApi}'s implementation: a real, scheduled delay on the reactor before the answer. */
	public static final class DelayedApiImpl implements DelayedApi {
		@Override
		public Promise<String> echo(String value, int delayMillis) {
			return Promises.delay(Duration.ofMillis(delayMillis), "echoed-" + value);
		}
	}

	/**
	 * The reorderable subject: an in-memory {@link JsonRpcTransport} double over the harness's real
	 * dispatcher (D9). {@code dispatch} over the harness's {@code ConformanceApi} completes
	 * synchronously, so the three responses are held by the time the three proxy calls return — which
	 * is exactly what the harness's synchronous {@code heldCount() == 3} assertion needs (F9).
	 * {@link #releaseInReverseOrder()} delivers last-held-first; the {@code JsonRpcClient} on top
	 * correlates by {@code id} and every promise resolves correctly. A test double, not a component:
	 * no reactor guard, driven on the reactor thread by the harness.
	 */
	private static final class ReorderableWsDouble implements JsonRpcTransport, ReorderableTransport {
		private final JsonRpcDispatcher peer;                       // the harness's real dispatcher

		private ReorderableWsDouble(JsonRpcDispatcher peer) {
			this.peer = peer;
		}

		private final List<byte[]> held = new ArrayList<>();
		private @Nullable Listener listener;
		private boolean holding;
		private boolean closed;

		@Override
		public Promise<Void> send(byte[] document) {
			peer.dispatch(document)                                  // total; synchronous for ConformanceApi
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
