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

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefBoolean;
import io.activej.common.ref.RefInt;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.JsonRpcTcpRawSocket;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.EventloopThread;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Domain G — cross-cutting security (adversarial test plan, 017-jsonrpc-tcp-transport): FR-097's two
 * categories the existing hostile suite does not yet name explicitly (unmatched wire name, remote
 * address), an inbound-request flood that forces real dispatch and correlation rather than the
 * absorbed-notification flood {@code JsonRpcTcpHostileTest} already covers, and re-entrant
 * {@code broadcast()} — a second complete broadcast triggered synchronously from inside the first's
 * own invocation.
 *
 * <h2>G1 — no unmatched wire name, no remote address, anywhere (FR-097)</h2>
 * Two things, verified against production source before being asserted, not assumed:
 * <ul>
 *     <li>{@code JsonRpcPeerHandler.handle} answers an unresolved request with
 *     {@code JsonRpcResponse.ofError(request.id(), JsonRpcErrors.METHOD_NOT_FOUND)}, and
 *     {@code JsonRpcErrors.METHOD_NOT_FOUND} is built by the {@code predefined(...)} factory, which
 *     hard-codes {@code JsonRpcPayload.absent()} as {@code data}. There is no code path from a request's
 *     wire method name into an error object at all — {@code onMethodNotFound(String)} is the *only*
 *     callback that ever sees it (the dispatcher's own Javadoc), and that callback is a JMX/inspector
 *     seam, never a wire path.</li>
 *     <li>Every framing-level close cause in {@code JsonRpcTcpTransport} is a fixed string
 *     ({@link JsonRpcTcpTransport#EMPTY_LINE}) or a fixed template plus a *configured* number
 *     ({@code OfByteTerminated}'s {@code "No terminator byte is found in " + maxSize + " bytes"}) —
 *     never anything derived from the peer or its address. This class re-reads the four
 *     already-written hostile scenarios ({@code JsonRpcTcpHostileTest}: empty line, no-terminator
 *     accumulation, never-terminated flood, garbage JSON) with an explicit assertion that neither the
 *     loopback address nor the server's own bound port ever appears — the "remote address" category
 *     FR-097 names and no existing test checks.</li>
 * </ul>
 *
 * <h2>G2 — a flood of answered REQUESTS, not absorbed notifications (FR-095, FR-035)</h2>
 * {@code JsonRpcTcpHostileTest.testANotificationFloodIsAbsorbedWithZeroBytesInReturn} floods
 * notifications, which by §4.1 are never answered — the server-side state that flood exercises is
 * dispatch-only. FR-095 also names "inbound calls the peer never answers" and, more basically, inbound
 * calls that *do* get answered: {@link #testFiveThousandCorrelatedRequestsInOneBurstAreAllAnsweredCorrectly()}
 * sends thousands of {@code id}-bearing requests in one burst, requires every one to come back
 * correctly correlated through {@code TestApi.add}, and checks {@code sessions()}/{@code inFlightCount()}
 * both mid-flood and after — the latter is a static invariant (inbound calls never populate the
 * *outbound* correlation table, by type-system construction: {@code JsonRpcTcpSession.inFlightCount()}
 * reads the session's own {@code JsonRpcClient}, whose table only server-*initiated* calls occupy) but
 * is worth observing directly rather than trusting.
 *
 * <h2>G5 — a broadcast re-entering a second, complete broadcast (FR-033)</h2>
 * {@code JsonRpcTcpSessionTest.testEnumerationRacingACloseNeitherThrowsNorTears} already proves
 * re-entrant {@code closeEx} from inside an enumeration. This class proves the sharper claim the
 * adversarial plan calls out separately: a handler invoked *by* one {@code broadcast(...)} call
 * triggers, synchronously and before returning, a **second, complete** {@code broadcast(...)} call on
 * the same server. Read from {@code JsonRpcTcpServer.broadcast} (source above): each call opens with
 * {@code for (JsonRpcTcpSession session : sessions())}, and {@code sessions()} is
 * {@code Set.copyOf(sessions)} taken fresh at the moment of that specific call — so the outer and the
 * inner broadcast necessarily hold two independent snapshots, never the same {@code Set} instance,
 * which is exactly what "each broadcast takes its own instantané" means operationally. No
 * {@code ConcurrentModificationException} is possible for the same reason: neither loop ever iterates
 * the live, mutable {@code sessions} field.
 *
 * <h2>G6 — zero ByteBuf leak on both new volumes</h2>
 * No separate mechanism: {@link #byteBufRule} already guards this whole class, so a single leaked
 * {@code byte[]}/{@code ByteBuf} across the 5,000-request burst (G2) or the nested-broadcast pair (G5)
 * fails the class at teardown. Nothing below opts out of it.
 */
public final class JsonRpcTcpAdversarialSecurityTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Small enough to provoke the framing bound in milliseconds (mirrors JsonRpcTcpHostileTest's tier). */
	private static final MemSize FRAMING_TIER = MemSize.kilobytes(8);

	private static final int FLOOD_REQUEST_COUNT = 5_000;

	private static final String UNMATCHED_METHOD_MARKER = "no.such.SECRET-METHOD-99";
	private static final String GARBAGE_MARKER = "TCP-ADVERSARIAL-MARKER-77";

	private @Nullable EventloopThread loop;
	private @Nullable JsonRpcTcpServer server;
	private int port;

	@After
	public void tearDown() throws Exception {
		try {
			if (server != null) {
				// closeFuture() submits close() to the server's own reactor and completes when the drain
				// has emptied the registry — the only way to join a server owned by another thread
				server.closeFuture().get(10, TimeUnit.SECONDS);
			}
		} finally {
			if (loop != null) loop.close();
		}
	}

	// -------------------------------------------------------------------------------------------
	// G1a: the unmatched wire name never reaches the -32601 answer.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testUnmatchedWireNameNeverAppearsInTheMethodNotFoundResponse() {
		startServer();
		String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + UNMATCHED_METHOD_MARKER + "\",\"params\":[]}";

		withRawSocket(peer -> {
			String answer = exchange(peer, request);
			JsonRpcResponse response = errorResponse(answer);

			assertEquals(JsonRpcErrors.METHOD_NOT_FOUND.code(), response.error().code());
			assertEquals(JsonRpcErrors.METHOD_NOT_FOUND.message(), response.error().message());
			assertEquals("no data member at all for method-not-found (dispatcher table, §II)",
				JsonRpcPayload.absent(), response.error().data());
			assertFalse("no \"data\" member was ever serialized: " + answer, answer.contains("\"data\""));
			assertFalse("the unmatched wire name must never be echoed back: " + answer,
				answer.contains(UNMATCHED_METHOD_MARKER));
			assertFalse("...nor any fragment of the embedded secret: " + answer,
				answer.contains("SECRET-METHOD-99"));

			// the connection stays usable after the refusal (framing is intact; this is a JSON-level fault)
			assertResultOfAdd(exchange(peer, requestFor(7)), 7, 8);
			assertEquals("the session survived an unmatched method name", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// G1b: relecture of the four hostile-closure scenarios — no remote address, anywhere.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testBareLineFeedCloseCauseNamesNoRemoteAddress() {
		// relecture of JsonRpcTcpHostileTest.testABareLineFeedClosesTheConnection: that test observes only
		// "the connection closed" through a blocking raw socket, which cannot see the Exception object. Here
		// the cause is captured directly, so the assertion can be the strongest form available — EXACT
		// equality against the fixed string JsonRpcTcpTransport.EMPTY_LINE — which by construction also rules
		// out any address ever being appended to it.
		CloseCapture capture = captureCloseCause(FRAMING_TIER,
			client -> client.write(ByteBuf.wrapForReading("\n".getBytes(UTF_8))));

		assertEquals(JsonRpcTcpTransport.EMPTY_LINE, capture.cause().getMessage());
		assertNoRemoteAddressLeak(capture.cause().getMessage(), capture.serverPort());
	}

	@Test
	public void testNoTerminatorCloseCauseNamesNoRemoteAddress() {
		// relecture of JsonRpcTcpHostileTest.testAStreamWithNoTerminatorDiesDuringAccumulationAtTheTransportTier,
		// which already checks the cause does not contain 'x' (the peer's byte). Extended here with the
		// remote-address category FR-097 separately names, and pinned to OfByteTerminated's exact template
		// ("No terminator byte is found in " + maxSize + " bytes", read from source above) rather than a
		// substring guess.
		byte[] noTerminator = new byte[FRAMING_TIER.toInt()];
		Arrays.fill(noTerminator, (byte) 'x');

		CloseCapture capture = captureCloseCause(FRAMING_TIER,
			client -> client.write(ByteBuf.wrapForReading(noTerminator)));

		assertThat(capture.cause(), instanceOf(MalformedDataException.class));
		assertEquals("No terminator byte is found in " + FRAMING_TIER.toInt() + " bytes",
			capture.cause().getMessage());
		assertNoRemoteAddressLeak(capture.cause().getMessage(), capture.serverPort());
	}

	@Test
	public void testNeverTerminatedFloodCloseCauseNamesNoRemoteAddressRegardlessOfChunking() {
		// relecture of JsonRpcTcpHostileTest.testANeverTerminatedFloodIsRefusedLongBeforeTheAttemptedSizeIsSent,
		// which drives the same bound through a raw blocking socket and can only observe "refused", never the
		// Exception. Reproduced here through MANY small writes (the actual "flood" shape) rather than one big
		// one, with direct access to the cause: OfByteTerminated's bound depends only on bytes accumulated,
		// never on how many separate writes produced them, so the cause is byte-for-byte identical to the
		// single-write case above — including still containing no address.
		byte[] chunk = new byte[256];
		Arrays.fill(chunk, (byte) 'z');

		CloseCapture capture = captureCloseCause(FRAMING_TIER, client -> writeUntilClosed(client, chunk, 200));

		assertThat(capture.cause(), instanceOf(MalformedDataException.class));
		assertEquals("No terminator byte is found in " + FRAMING_TIER.toInt() + " bytes",
			capture.cause().getMessage());
		assertNoRemoteAddressLeak(capture.cause().getMessage(), capture.serverPort());
	}

	@Test
	public void testGarbageLineParseErrorResponseNamesNoRemoteAddress() {
		// relecture of JsonRpcTcpHostileTest.testGarbageLineIsAnsweredParseErrorAndTheConnectionStaysUp,
		// which already checks the response does not echo the garbage marker. Extended with the
		// remote-address category and its own distinct marker, so this scenario is not just a restatement of
		// the existing one under a new name.
		startServer();
		String garbage = "not json at all, carries " + GARBAGE_MARKER;

		withRawSocket(peer -> {
			String answer = exchange(peer, garbage);
			JsonRpcResponse response = errorResponse(answer);

			assertEquals(JsonRpcErrors.PARSE_ERROR.code(), response.error().code());
			assertEquals(JsonRpcErrors.PARSE_ERROR.message(), response.error().message());
			assertEquals(JsonRpcId.NULL, response.id());
			assertFalse("no peer content in the answer: " + answer, answer.contains(GARBAGE_MARKER));
			assertNoRemoteAddressLeak(answer, port);

			assertResultOfAdd(exchange(peer, requestFor(9)), 9, 10);
			assertEquals("the session survived a parse error", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// G2: a real burst of id-bearing requests, all correlated through the dispatcher.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testFiveThousandCorrelatedRequestsInOneBurstAreAllAnsweredCorrectly() throws Exception {
		startServer();
		int n = FLOOD_REQUEST_COUNT;
		List<String> answers = new ArrayList<>(n);
		int midSessions;
		int midInFlight;
		int afterSessions;
		int afterInFlight;

		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(port)) {
			for (int i = 0; i < n / 2; i++) {
				peer.writeLine(requestFor(i));
			}
			// mid-flood: half the burst is on the wire, none of it read back yet
			midSessions = sessionCount();
			midInFlight = inFlightCount();

			for (int i = n / 2; i < n; i++) {
				peer.writeLine(requestFor(i));
			}
			for (int i = 0; i < n; i++) {
				String line = peer.readLine();
				assertNotNull("answer " + i + " of " + n + " must arrive", line);
				answers.add(line);
			}

			// "after the flood" but still connected: sessionCount() observed once the peer socket itself
			// closes would race the server's own deregistration (a real connection close, not a synthetic
			// probe), which is not what FR-035 is being asked about here — the registry must not have grown
			// from the volume of the burst, independent of the connection's own eventual lifecycle
			afterSessions = sessionCount();
			afterInFlight = inFlightCount();
		}

		assertEquals(n, answers.size());
		// sample correlation: first, a couple of intermediates, last — id <-> sum, per the adverse dispositif
		assertResultOfAdd(answers.get(0), 0, 1);
		assertResultOfAdd(answers.get(n / 4), n / 4, n / 4 + 1);
		assertResultOfAdd(answers.get(n / 2), n / 2, n / 2 + 1);
		assertResultOfAdd(answers.get(3 * n / 4), 3 * n / 4, 3 * n / 4 + 1);
		assertResultOfAdd(answers.get(n - 1), n - 1, n);

		assertEquals("exactly one session, mid-flood (FR-035)", 1, midSessions);
		assertEquals(
			"nothing in the outbound correlation table, mid-flood — inbound calls never populate it (FR-095)",
			0, midInFlight);
		assertEquals("exactly one session, after the flood, connection still open (FR-035)", 1, afterSessions);
		assertEquals(
			"nothing in the outbound correlation table, after the flood (FR-095)", 0, afterInFlight);
	}

	// -------------------------------------------------------------------------------------------
	// G5: a broadcast that re-enters a second, complete broadcast synchronously.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testReentrantBroadcastTakesItsOwnSnapshotAndLeavesNeitherIterationCorrupted() {
		JsonRpcTcpServer server = multiSessionServer();
		Ref<Peer> refA = new Ref<>();
		Ref<Peer> refB = new Ref<>();
		Ref<Peer> refC = new Ref<>();
		RefBoolean triggered = new RefBoolean(false);
		RefInt sessionsDuringReentrance = new RefInt(-1);

		await(connect(server)
			.then(a -> {
				refA.set(a);
				return connect(server);
			})
			.then(b -> {
				refB.set(b);
				return connect(server);
			})
			.then(c -> {
				refC.set(c);
				assertEquals("three live sessions before the broadcast pair", 3, server.sessions().size());

				// the first invocation encountered triggers a SECOND, complete broadcast() synchronously,
				// before returning to the outer loop — the reentrance the adversarial plan asks for, distinct
				// from JsonRpcTcpSessionTest's reentrant-closeEx-during-enumeration coverage
				server.broadcast(ClientApi.class, api -> {
					if (!triggered.get()) {
						triggered.set(true);
						server.broadcast(ClientApi.class, inner -> inner.event(200));
						sessionsDuringReentrance.set(server.sessions().size());
					}
					api.event(100);
				});

				assertEquals("no session was lost across the reentrant pair", 3, server.sessions().size());

				return Promises.all(
						refA.get().api().awaitAtLeast(2),
						refB.get().api().awaitAtLeast(2),
						refC.get().api().awaitAtLeast(2))
					.then(() -> shutdown(server, refA.get(), refB.get(), refC.get()));
			}));

		assertEquals("three sessions were still registered mid-reentrance — none torn by the nested iteration",
			3, sessionsDuringReentrance.get());
		// every live session receives BOTH notifications in the order the two broadcasts actually sent them:
		// the nested broadcast runs to completion (sending 200 to all three) before the outer call's own
		// event(100) for the triggering session even happens, and every other session's own turn in the outer
		// loop — where it receives 100 — necessarily comes after the trigger's turn, which is where the
		// nested broadcast ran. This holds regardless of the (unspecified) snapshot iteration order.
		assertEquals(List.of(200L, 100L), refA.get().api().events());
		assertEquals(List.of(200L, 100L), refB.get().api().events());
		assertEquals(List.of(200L, 100L), refC.get().api().events());
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: the server on its own loop, driven by a blocking raw socket from the JUnit thread.
	// -------------------------------------------------------------------------------------------

	private void startServer() {
		EventloopThread loop = EventloopThread.create("jsonrpc-tcp-adversarial-security-test");
		this.loop = loop;
		try {
			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
					.build();
				JsonRpcTcpServer server = JsonRpcTcpServer.builder(loop.eventloop(), dispatcher)
					.withListenPort(0)
					.build();
				server.listen();
				this.server = server;
				this.port = server.getBoundAddresses().get(0).getPort();
			});
		} catch (RuntimeException | Error e) {
			loop.close();
			this.loop = null;
			throw e;
		}
	}

	private void withRawSocket(HostilePeer body) {
		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(port)) {
			body.run(peer);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String exchange(JsonRpcTcpRawSocket peer, String document) throws IOException {
		peer.writeLine(document);
		String answer = peer.readLine();
		assertNotNull("the connection must stay up and answer", answer);
		return answer;
	}

	private static String requestFor(int i) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"test.add\",\"params\":[" + i + ",1]}";
	}

	private static JsonRpcResponse errorResponse(String document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a single response document: " + document, input, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertNotNull("expected an error response: " + document, response.error());
		return response;
	}

	private static void assertResultOfAdd(String document, long expectedId, long expectedSum) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a response document: " + document, input, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertNull("expected a successful response: " + document, response.error());
		assertEquals(new JsonRpcId.Num(expectedId), response.id());
		assertTrue("expected sum " + expectedSum + ": " + document, document.contains("\"sum\":" + expectedSum));
	}

	/** No remote address or port ever appears in a message this module produces (FR-097). */
	private static void assertNoRemoteAddressLeak(@Nullable String text, int serverPort) {
		assertNotNull("expected a message to inspect for the remote-address category (FR-097)", text);
		assertFalse("must never name the loopback address (FR-097): " + text, text.contains("127.0.0.1"));
		assertFalse("must never name the server's own bound port (FR-097): " + text,
			text.contains(String.valueOf(serverPort)));
	}

	private int sessionCount() {
		JsonRpcTcpServer server = this.server;
		EventloopThread loop = this.loop;
		if (server == null || loop == null) return 0;
		return loop.submit(() -> server.sessions().size());
	}

	private int inFlightCount() {
		JsonRpcTcpServer server = this.server;
		EventloopThread loop = this.loop;
		if (server == null || loop == null) return 0;
		return loop.submit(() -> {
			int total = 0;
			for (JsonRpcTcpSession session : server.sessions()) {
				total += session.inFlightCount();
			}
			return total;
		});
	}

	@FunctionalInterface
	private interface HostilePeer {
		void run(JsonRpcTcpRawSocket peer) throws IOException;
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: the in-reactor socket pair, for the scenarios that need the actual close Exception.
	// -------------------------------------------------------------------------------------------

	/** One transport-level close capture: the ephemeral server's own bound port, and the cause observed. */
	private record CloseCapture(int serverPort, Exception cause) {}

	/**
	 * Connects a real TCP pair on the current reactor — an {@code acceptOnce} server on port {@code 0} and a
	 * connected client — wraps the server side in a {@link JsonRpcTcpTransport} bounded by {@code tier},
	 * drives {@code trigger} against the client side, and returns the close cause the transport's listener
	 * observed together with the ephemeral server's own bound port (so a test can assert that port never
	 * appears in the cause it names).
	 */
	private static CloseCapture captureCloseCause(MemSize tier, Function<ITcpSocket, Promise<Void>> trigger) {
		NioReactor reactor = reactor();
		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer acceptServer = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		try {
			acceptServer.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int boundPort = acceptServer.getBoundAddresses().get(0).getPort();
		Ref<Exception> cause = new Ref<>();

		await(TcpSocket.connect(reactor, acceptServer.getBoundAddresses().get(0))
			.then(clientSocket -> accepted.then(serverSocket -> {
				JsonRpcTcpTransport transport = JsonRpcTcpTransport.builder(reactor, serverSocket)
					.withMaxMessageSize(tier)
					.build();
				SettablePromise<Void> closed = new SettablePromise<>();
				transport.setListener(listener(doc -> {}, e -> {
					cause.set(e);
					closed.set(null);
				}));
				return trigger.apply(clientSocket)
					.then(() -> closed)
					.whenComplete(($, e) -> {
						clientSocket.close();
						serverSocket.close();
						acceptServer.close();
					});
			})));

		return new CloseCapture(boundPort, cause.get());
	}

	/**
	 * Writes {@code chunk} up to {@code remainingAttempts} times, stopping early — without failing — once a
	 * write fails (the server has already closed its side, which races the client's own writes by design in
	 * the "flood" scenario). The byte budget ({@code remainingAttempts * chunk.length}) is chosen well above
	 * the transport tier being tested, so the loop's real stopping condition is always the server's own
	 * refusal, observed here only as an ordinary write failure.
	 */
	private static Promise<Void> writeUntilClosed(ITcpSocket clientSocket, byte[] chunk, int remainingAttempts) {
		if (remainingAttempts <= 0) return Promise.complete();
		return clientSocket.write(ByteBuf.wrapForReading(chunk))
			.map($ -> Boolean.TRUE, e -> Boolean.FALSE)
			.then(keepGoing -> keepGoing
				? writeUntilClosed(clientSocket, chunk, remainingAttempts - 1)
				: Promise.<Void>complete());
	}

	private static JsonRpcTransport.Listener listener(
		java.util.function.Consumer<byte[]> onDocument, java.util.function.Consumer<@Nullable Exception> onClosed
	) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				onDocument.accept(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				onClosed.accept(e);
			}
		};
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: a multi-session server plus connected peers, for the reentrant-broadcast scenario.
	// -------------------------------------------------------------------------------------------

	private static JsonRpcTcpServer multiSessionServer() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), dispatcher).withListenPort(0).build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/** One connected peer: its client, and the {@link RecordingClientApi} its own dispatcher answers through. */
	private record Peer(JsonRpcClient client, RecordingClientApi api) {}

	private static Promise<Peer> connect(JsonRpcTcpServer server) {
		NioReactor reactor = reactor();
		RecordingClientApi api = new RecordingClientApi();
		return JsonRpcTcpTransport.connect(reactor, server.getBoundAddresses().get(0))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
					.withPeerHandler(JsonRpcDispatcher.builder(reactor)
						.withService(ClientApi.class, api)
						.build())
					.build();
				return client.proxy(TestApi.class).add(1, 1).map($ -> new Peer(client, api));
			});
	}

	/** Closes every client and then the server, inside the awaited chain, so the loop can quiesce. */
	private static Promise<Void> shutdown(JsonRpcTcpServer server, Peer... peers) {
		for (Peer peer : peers) {
			peer.client().closeEx(new ExpectedException("end of test"));
		}
		return server.close().toVoid();
	}

	/**
	 * A {@link ClientApi} that records every {@code client.event} id delivered, in order, and exposes
	 * {@link #awaitAtLeast(int)} as the await point — a notification leaves the sender nothing to await, so
	 * the receiver's own recorded count is the only observable signal that delivery happened.
	 */
	private static final class RecordingClientApi implements ClientApi {
		private final List<Long> events = new ArrayList<>();
		private int awaitingThreshold = Integer.MAX_VALUE;
		private @Nullable SettablePromise<Void> awaiting;

		@Override
		public Promise<String> decide(int n) {
			return Promise.of("unused");
		}

		@Override
		public Promise<String> fail() {
			return Promise.of("unused");
		}

		@Override
		public void event(long id) {
			events.add(id);
			SettablePromise<Void> pending = awaiting;
			if (pending != null && events.size() >= awaitingThreshold) {
				awaiting = null;
				pending.set(null);
			}
		}

		List<Long> events() {
			return events;
		}

		Promise<Void> awaitAtLeast(int count) {
			if (events.size() >= count) return Promise.complete();
			awaitingThreshold = count;
			SettablePromise<Void> pending = new SettablePromise<>();
			awaiting = pending;
			return pending;
		}
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
