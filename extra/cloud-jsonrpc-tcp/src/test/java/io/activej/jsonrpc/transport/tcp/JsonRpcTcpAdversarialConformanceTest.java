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

package io.activej.jsonrpc.transport.tcp;

import io.activej.common.MemSize;
import io.activej.common.ref.Ref;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.ConformanceVectors;
import io.activej.jsonrpc.ConformanceVectors.Vector;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.activej.jsonrpc.ConformanceJson.assertJsonEquals;
import static io.activej.jsonrpc.ConformanceJson.parseJson;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Domain E adversarial scenarios (Conformance &amp; rejeu) implemented as real JUnit tests, against
 * the oracle of {@code contracts/tcp-framing.md}, {@code contracts/session-api.md}, {@code spec.md}
 * and the {@code JsonRpcTransport} SPI's seven obligations — never against "whatever the code does
 * today". Every scenario opens a real {@link JsonRpcTcpServer} on {@code :0} (ADR-028) and drives it
 * with a real dialled {@link JsonRpcTcpTransport} or a real {@link JsonRpcTcpSession}; none of the
 * three doubles as an in-memory double.
 *
 * <h2>E2 (P1) — one persistent connection, several vectors, colliding ids</h2>
 * {@link #testFiveVectorsReplaySequentiallyOnOnePersistentConnectionWithCollidingIds()}. The shared
 * harness's {@code exchange()} calls {@code createTransport(dispatcher())} <b>inside</b> the
 * per-vector loop (verified by reading {@code AbstractTransportConformanceTest} directly, per the
 * design doc), so the 30-vector conformance tests never prove that a persistent connection survives
 * several distinct exchanges without reconnecting. This test does: one dial, six exchanges, the
 * request ids {@code 1} and {@code "2"} genuinely reused across three different named vectors —
 * {@code positional-params-subtract}'s request id and {@code envelope-too-large}'s request id are
 * <b>both</b> the JSON number {@code 1} (read straight from the checked-in vector files, not
 * fabricated), and {@code batch-mixed}'s string id {@code "2"} is reused by
 * {@code positional-params-subtract-reversed}'s numeric id {@code 2} — and the oracle
 * (tcp-framing.md §3: an envelope-level fault, {@code -32001} included, "connection stays up") is
 * asserted as a mid-sequence fact, not an end-of-test inference: {@link RawPeer#closedByPeer} is
 * read <b>before</b> the connection is used for one more vector.
 *
 * <h2>E3 (P1) — real differentiated latency, not the in-memory double</h2>
 * {@link #testServerInitiatedCallsResolveByIdDespiteRealDifferentiatedSocketLatency()}. The existing
 * reorder proof ({@code AbstractTransportConformanceTest.responsesAreCorrelatedByIdAloneWhen...})
 * is a synchronous in-memory double that never touches a socket; {@code JsonRpcTcpRoundTripTest}'s
 * real-socket reorder is client&rarr;server only and uses an explicit hold/release, never a real
 * clock. This test issues three <b>server-initiated</b> calls on a real {@link JsonRpcTcpSession},
 * with the client answering each on its own real {@link Promises#delay(long, Object)} timer — twice,
 * with two different delay assignments, so neither run's arrival order is coincidentally the
 * emission order, and each run's actual arrival order is asserted against its predicted order rather
 * than merely "some order".
 *
 * <h2>E6 (P2) — many interleaved rounds, not one pair</h2>
 * {@link #testManyInterleavedRoundsOfBothDirectionsResolveIndependentlyOnOneSession()}.
 * {@code JsonRpcTcpServerInitiatedTest.testCollidingNumericIdsResolveIndependentlyInBothDirections}
 * proves FR-051's independent id spaces for <b>one</b> concurrent pair. This test repeats the same
 * proof five times in a row on <b>one</b> session — both directions' correlation tables are checked
 * empty after <i>every</i> round, not only at the end — so a table that occasionally, rather than
 * always, keeps its rows separate would still be caught.
 *
 * <h2>Harness</h2>
 * {@code EventloopRule}+{@code ByteBufRule}+{@code ActivePromisesRule}, every server {@code :0} +
 * {@code getBoundAddresses()} (ADR-028, never {@code getFreePort()}), every connection closed inside
 * the awaited chain so {@code TestUtils.await}'s quiescence loop returns (a stranded promise hangs
 * the suite rather than failing it). No file this test touches was modified by this task beyond this
 * one — see the module's other tests for the established fixtures this one reuses without copying
 * their private helpers (each private helper below is a small, deliberate re-declaration, not a
 * shared one, so this file does not depend on another test class's internals).
 */
public final class JsonRpcTcpAdversarialConformanceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/**
	 * The receiving side's transport tier for E2's {@code envelope-too-large} vector, strictly above
	 * the 1 mb envelope tier (contract tcp-framing.md §2, FR-071) — matches
	 * {@code JsonRpcTcpConformanceTest}'s own raised tier, so the vector reaches the decoder and
	 * answers {@code -32001} instead of dying as a framing violation.
	 */
	private static final MemSize RAISED_TIER = MemSize.megabytes(2);

	// =================================================================================================
	// E2 (P1): one persistent connection, several vectors replayed in sequence, colliding ids.
	// =================================================================================================

	@Test
	public void testFiveVectorsReplaySequentiallyOnOnePersistentConnectionWithCollidingIds() {
		List<String> notificationFailures = new ArrayList<>();
		JsonRpcTcpServer server = adversarialServer(notificationFailures);

		RawPeer peer = withRawPeer(server, JsonRpcTcpAdversarialConformanceTest::replaySequence);

		assertEquals("six answerable exchanges came back as six documents on the one connection " +
					 "opened for the whole sequence — the notification produced none of its own",
			6, peer.received.size());
		assertEquals("no notification failure was reported by the dispatcher", List.of(), notificationFailures);
		await(server.close().toVoid());
	}

	/**
	 * The sequence itself: {@code positional-params-subtract} (success, request id {@code 1}),
	 * {@code method-not-found} (application-level error, request id {@code "1"} — the same digit as
	 * the first vector, a different JSON type), {@code batch-mixed} (batch, request ids
	 * {@code "1"/"2"/"5"/"9"}), {@code notification-update} (zero response bytes) immediately followed
	 * by {@code named-params-subtract} (id {@code 3}) as the probe that turns "zero bytes" into a
	 * counted assertion rather than a timeout, {@code envelope-too-large} at the server's raised tier
	 * (request id {@code 1} again — the exact same numeric id {@code positional-params-subtract} used,
	 * read straight from the checked-in vector file) answering {@code -32001} with the connection kept
	 * open, and finally {@code positional-params-subtract-reversed} (id {@code 2}, colliding with
	 * {@code batch-mixed}'s string id {@code "2"}) as the vector that proves the connection is still
	 * usable after the {@code -32001} exchange.
	 */
	private static Promise<Void> replaySequence(RawPeer peer) {
		Vector subtract = ConformanceVectors.byName("positional-params-subtract");
		Vector methodNotFound = ConformanceVectors.byName("method-not-found");
		Vector batchMixed = ConformanceVectors.byName("batch-mixed");
		Vector notificationUpdate = ConformanceVectors.byName("notification-update");
		Vector namedSubtract = ConformanceVectors.byName("named-params-subtract");
		Vector envelopeTooLarge = ConformanceVectors.byName("envelope-too-large");
		Vector subtractReversed = ConformanceVectors.byName("positional-params-subtract-reversed");

		return sendAndAwait(peer, subtract)
			.whenResult(doc -> {
				assertVector(subtract, doc);
				assertEquals(1, peer.received.size());
			})
			.then(() -> sendAndAwait(peer, methodNotFound))
			.whenResult(doc -> {
				// method-not-found's request id is the STRING "1" — the same digit positional-params-subtract's
				// NUMERIC id 1 used one exchange ago; a correct answer here means the two were never confused
				assertVector(methodNotFound, doc);
				assertEquals(2, peer.received.size());
			})
			.then(() -> sendAndAwait(peer, batchMixed))
			.whenResult(doc -> {
				// one message in, one message out (§6): the batch's own five-element answer, correlated by id
				// alone (ConformanceJson's rule 2), never by position
				assertVector(batchMixed, doc);
				assertEquals(3, peer.received.size());
			})
			.then(() -> peer.transport.send(utf8(notificationUpdate.request())))
			.then(() -> sendAndAwait(peer, namedSubtract))
			.whenResult(doc -> {
				// exactly one MORE document arrived for two sends: the notification produced none of its own
				// (§4.1) — observable here as a count, not inferred from a timeout
				assertVector(namedSubtract, doc);
				assertEquals("the notification produced no document of its own", 4, peer.received.size());
			})
			.then(() -> sendAndAwait(peer, envelopeTooLarge))
			.whenResult(doc -> {
				// -32001 is an envelope-level refusal, not a framing violation (tcp-framing.md §3's table:
				// "any other envelope-level failure ... connection stays up") — read BEFORE the connection is
				// used again, so this is a mid-sequence fact, not an end-of-test inference
				assertVector(envelopeTooLarge, doc);
				assertEquals(5, peer.received.size());
				assertFalse("an envelope-level -32001 must not close the connection (tcp-framing.md §3)",
					peer.closedByPeer);
			})
			.then(() -> sendAndAwait(peer, subtractReversed))
			.whenResult(doc -> {
				// the connection served one more, distinct vector after -32001 — unreconnected, per FR nothing
				// less than the whole point of this scenario
				assertVector(subtractReversed, doc);
				assertEquals("the connection remained usable for the vector after -32001", 6, peer.received.size());
			})
			.toVoid();
	}

	private static Promise<byte[]> sendAndAwait(RawPeer peer, Vector vector) {
		return peer.transport.send(utf8(vector.request())).then(peer::nextDocument);
	}

	private static void assertVector(Vector vector, byte[] actual) {
		String text = new String(actual, UTF_8);
		if (vector.exactBytes()) {
			assertEquals(vector.name(), vector.response(), text);
		} else {
			assertJsonEquals(parseJson(vector.response()), parseJson(text));
		}
	}

	private static JsonRpcTcpServer adversarialServer(List<String> notificationFailures) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withCodecFactory(adversarialCodecFactory())
			.withService(AdversarialApi.class, new AdversarialApiImpl())
			// EventloopRule installs a RETHROWING fatal-error handler; notification-update names an
			// unregistered method, which the dispatcher drops without invoking this handler at all
			// (§4.1) — this is here so an unrelated notification failure fails loudly rather than silently
			.withFailureHandler((descriptor, e) -> notificationFailures.add(descriptor.wireName() + ": " + e))
			.build();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), dispatcher)
			.withListenPort(0)
			.withAcceptOnce()
			.withMaxMessageSize(RAISED_TIER)                          // FR-071: the receiving side's tier
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/**
	 * Exactly the wire names {@code §7}'s examples use ({@code subtract}, {@code sum},
	 * {@code get_data}, {@code notify_hello}) and deliberately <b>not</b> {@code foo.get} —
	 * {@code method-not-found} and the {@code -32601} element of {@code batch-mixed} depend on that
	 * method staying absent from any dispatcher answering these vectors, mirroring
	 * {@code AbstractTransportConformanceTest}'s own fixture (which cannot be reused directly: its
	 * {@code ConformanceApi} and {@code dispatcher()} are private to that class).
	 */
	@JsonRpcService
	public interface AdversarialApi {
		@JsonRpcMethod("subtract")
		Promise<Integer> subtract(@JsonRpcParam("minuend") int minuend, @JsonRpcParam("subtrahend") int subtrahend);

		@JsonRpcMethod("sum")
		Promise<Integer> sum(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);

		@JsonRpcMethod("get_data")
		Promise<Data> getData();

		@JsonRpcNotification("notify_hello")
		void notifyHello(@JsonRpcParam("value") int value);

		/** {@code get_data}'s result, rendered as the heterogeneous array {@code ["hello", 5]}. */
		record Data(String greeting, int answer) {}
	}

	private static final class AdversarialApiImpl implements AdversarialApi {
		@Override
		public Promise<Integer> subtract(int minuend, int subtrahend) {
			return Promise.of(minuend - subtrahend);
		}

		@Override
		public Promise<Integer> sum(int a, int b, int c) {
			return Promise.of(a + b + c);
		}

		@Override
		public Promise<Data> getData() {
			return Promise.of(new Data("hello", 5));
		}

		@Override
		public void notifyHello(int value) {}
	}

	private static JsonCodecFactory adversarialCodecFactory() {
		return JsonCodecFactory.defaultInstance().rebuild()
			.with(AdversarialApi.Data.class, ctx -> JsonCodecs
				.ofArrayObject(JsonCodecs.ofString(), JsonCodecs.ofInteger())
				.transform(
					data -> new Object[]{data.greeting(), data.answer()},
					array -> new AdversarialApi.Data((String) array[0], (Integer) array[1])))
			.build();
	}

	/**
	 * A raw {@link JsonRpcTcpTransport} peer with no {@link JsonRpcClient} above it, and no
	 * close-after-answer policy on either end — unlike {@code fixtures.ConformanceAcceptor}, whose
	 * whole point is a fresh connection per exchange. This one is deliberately long-lived: it writes
	 * whole documents and hands each received document to whoever asks for the next one, and it
	 * records whether the peer (here, the server) ever closed it.
	 */
	private static final class RawPeer {
		final JsonRpcTcpTransport transport;
		final List<byte[]> received = new ArrayList<>();

		private final Deque<byte[]> undelivered = new ArrayDeque<>();
		private @Nullable SettablePromise<byte[]> waiting;
		boolean closedByPeer;

		private RawPeer(JsonRpcTcpTransport transport) {
			this.transport = transport;
			transport.setListener(new JsonRpcTransport.Listener() {
				@Override
				public void onDocument(byte[] document) {
					received.add(document);
					SettablePromise<byte[]> pending = waiting;
					if (pending != null) {
						waiting = null;
						pending.set(document);
					} else {
						undelivered.addLast(document);
					}
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					closedByPeer = true;
				}
			});
		}

		/** The next document this peer receives, or the next already-received one not yet taken. */
		Promise<byte[]> nextDocument() {
			byte[] ready = undelivered.pollFirst();
			if (ready != null) return Promise.of(ready);
			SettablePromise<byte[]> pending = new SettablePromise<>();
			waiting = pending;
			return pending;
		}
	}

	/** Connects one raw peer, runs {@code body} against it, closes it inside the chain, and returns it. */
	private static RawPeer withRawPeer(JsonRpcTcpServer server, Function<RawPeer, Promise<Void>> body) {
		NioReactor reactor = reactor();
		Ref<RawPeer> peerRef = new Ref<>();
		await(JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				RawPeer peer = new RawPeer(transport);
				peerRef.set(peer);
				return body.apply(peer)
					.whenComplete(($, e) -> transport.closeEx(new ExpectedException("end of test")));
			}));
		return peerRef.get();
	}

	// =================================================================================================
	// E3 (P1): real, differentiated per-call latency reorders server-initiated calls on a real socket.
	// =================================================================================================

	@Test
	public void testServerInitiatedCallsResolveByIdDespiteRealDifferentiatedSocketLatency() {
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService);
		DelayedClientApi clientImpl = new DelayedClientApi();
		List<Integer> firstArrivalOrder = new ArrayList<>();
		List<Integer> secondArrivalOrder = new ArrayList<>();

		await(withSession(server, serverService, clientDispatcher(clientImpl), peers -> {
			JsonRpcTcpSession session = peers.session();

			// round 1: delays [60, 20, 40] ms for calls n=1,2,3 -> real arrival order 2, 3, 1
			clientImpl.setDelaysMillis(Map.of(1, 60L, 2, 20L, 3, 40L));
			Promise<String> a1 = session.proxy(ClientApi.class).decide(1).whenResult(v -> firstArrivalOrder.add(1));
			Promise<String> a2 = session.proxy(ClientApi.class).decide(2).whenResult(v -> firstArrivalOrder.add(2));
			Promise<String> a3 = session.proxy(ClientApi.class).decide(3).whenResult(v -> firstArrivalOrder.add(3));

			return Promises.toList(a1, a2, a3)
				.whenResult(values -> assertEquals("each promise resolved with its OWN answer despite the reorder",
					List.of("decided-1", "decided-2", "decided-3"), values))
				.whenResult($ -> assertEquals("no in-flight entry survives a round", 0, session.inFlightCount()))
				.then(() -> {
					// round 2: delays [20, 60, 40] ms -> a DIFFERENT real arrival order: 1, 3, 2
					clientImpl.setDelaysMillis(Map.of(1, 20L, 2, 60L, 3, 40L));
					Promise<String> b1 = session.proxy(ClientApi.class).decide(1).whenResult(v -> secondArrivalOrder.add(1));
					Promise<String> b2 = session.proxy(ClientApi.class).decide(2).whenResult(v -> secondArrivalOrder.add(2));
					Promise<String> b3 = session.proxy(ClientApi.class).decide(3).whenResult(v -> secondArrivalOrder.add(3));
					return Promises.toList(b1, b2, b3);
				})
				.whenResult(values -> assertEquals("each promise resolved with its OWN answer despite the reorder",
					List.of("decided-1", "decided-2", "decided-3"), values))
				.whenResult($ -> assertEquals("no in-flight entry survives a round", 0, session.inFlightCount()))
				.toVoid();
		}));

		// the real arrival order is exactly what the two delay assignments predict — proving genuine
		// socket-level reordering was handled, not merely "some order, who knows which"
		assertEquals("round 1's real arrival order: n=2 (20ms), n=3 (40ms), n=1 (60ms)",
			List.of(2, 3, 1), firstArrivalOrder);
		assertEquals("round 2's real arrival order: n=1 (20ms), n=3 (40ms), n=2 (60ms)",
			List.of(1, 3, 2), secondArrivalOrder);
		closeServer(server);
	}

	/**
	 * {@link ClientApi#decide(int)} answered after a REAL {@link Promises#delay(long, Object)} timer
	 * keyed by {@code n} — never an in-memory hold/release double. The delay controls only WHEN the
	 * answer is written, never WHAT it is: {@code decide(n)} always answers {@code "decided-" + n}, so
	 * a wrong correlation would surface as a wrong value, not merely a wrong order.
	 */
	private static final class DelayedClientApi implements ClientApi {
		private Map<Integer, Long> delaysMillis = Map.of();

		void setDelaysMillis(Map<Integer, Long> delaysMillis) {
			this.delaysMillis = delaysMillis;
		}

		@Override
		public Promise<String> decide(int n) {
			long delayMillis = delaysMillis.getOrDefault(n, 0L);
			return Promises.delay(delayMillis, "decided-" + n);
		}

		@Override
		public Promise<String> fail() {
			throw new UnsupportedOperationException("unused by this scenario");
		}

		@Override
		public void event(long id) {
			// unused by this scenario
		}
	}

	// =================================================================================================
	// E6 (P2): many interleaved rounds of both directions on one session, colliding ids throughout.
	// =================================================================================================

	@Test
	public void testManyInterleavedRoundsOfBothDirectionsResolveIndependentlyOnOneSession() {
		// FR-051: two JsonRpcClients, two correlation tables, two monotonic counters — so round i's
		// client-initiated call and round i's server-initiated call draw the SAME id on both directions,
		// every round, for the whole life of the session. Five rounds, not one pair.
		int rounds = 5;
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService);
		ClientApiImpl clientImpl = new ClientApiImpl();
		List<Integer> serverSums = new ArrayList<>();
		List<String> clientDecisions = new ArrayList<>();

		await(withSession(server, serverService, clientDispatcher(clientImpl), peers -> {
			Promise<Void> chain = Promise.complete();
			for (int round = 1; round <= rounds; round++) {
				int n = round;
				int a = round;
				int b = round * 10;
				chain = chain.then(() -> {
					// issued from the SAME reactor callback: both requests are on the wire before either
					// answer can be processed, so round n's two ids genuinely collide, not just coincide
					Promise<TestApi.AddResult> clientInitiated = peers.client().proxy(TestApi.class).add(a, b)
						.whenResult(r -> serverSums.add(r.sum()));
					Promise<String> serverInitiated = peers.session().proxy(ClientApi.class).decide(n)
						.whenResult(clientDecisions::add);
					return Promises.all(clientInitiated, serverInitiated)
						.whenResult($ -> {
							// checked after EVERY round, not only the last one — a table that keeps its rows
							// separate only sometimes must still be caught
							assertEquals("round " + n + ": no client-initiated entry left behind",
								0, peers.client().inFlightCount());
							assertEquals("round " + n + ": no server-initiated entry left behind",
								0, peers.session().inFlightCount());
						});
				});
			}
			return chain;
		}));

		List<Integer> expectedSums = new ArrayList<>();
		List<String> expectedDecisions = new ArrayList<>();
		for (int round = 1; round <= rounds; round++) {
			expectedSums.add(round + round * 10);
			expectedDecisions.add("decided-" + round);
		}
		assertEquals("every client-initiated call resolved with its OWN answer, round after round",
			expectedSums, serverSums);
		assertEquals("every server-initiated call resolved with its OWN answer, round after round",
			expectedDecisions, clientDecisions);
		closeServer(server);
	}

	// =================================================================================================
	// Shared wiring for E3 and E6 — a small, self-contained re-declaration of the established
	// session/handshake pattern (private to this class; no other test file's internals are reused).
	// =================================================================================================

	/** One established connection: the client, the raw transport under it, and the server-side session. */
	private record Peers(JsonRpcClient client, JsonRpcTcpTransport transport, JsonRpcTcpSession session) {}

	private static JsonRpcTcpServer server(TestApiImpl serverService) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, serverService)
			.build();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), dispatcher)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/** The client's inbound wiring: whatever a server may call this client with. */
	private static JsonRpcDispatcher clientDispatcher(ClientApi clientService) {
		return JsonRpcDispatcher.builder(reactor())
			.withService(ClientApi.class, clientService)
			.build();
	}

	/**
	 * Connects one client, waits until the server has demonstrably dispatched from it — so the session
	 * exists (FR-032) — via a NOTIFICATION handshake (§4.1 forbids answering it, so both directions'
	 * counters are still at zero when {@code body} runs, matching {@code JsonRpcTcpServerInitiatedTest}'s
	 * established idiom), runs {@code body}, and closes the client inside the awaited chain.
	 */
	private static <T> Promise<T> withSession(
		JsonRpcTcpServer server, TestApiImpl serverService, JsonRpcDispatcher clientDispatcher,
		Function<Peers, Promise<T>> body
	) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
					.withPeerHandler(clientDispatcher)
					.build();
				client.proxy(TestApi.class).note("established");
				return serverService.firstNote()
					.then(() -> body.apply(new Peers(client, transport, server.sessions().iterator().next())))
					.whenComplete(() -> client.closeEx(new ExpectedException("end of test")));
			});
	}

	private static void closeServer(JsonRpcTcpServer server) {
		await(server.close().toVoid());
	}

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
