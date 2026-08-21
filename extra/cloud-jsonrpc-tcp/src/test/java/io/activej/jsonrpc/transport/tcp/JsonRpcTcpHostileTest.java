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
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.JsonRpcTcpRawSocket;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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
 * US3 (T015): what a hostile or merely broken peer can and cannot make this transport do — the
 * two-tier size bound, the framing-versus-JSON error split, and the flood posture, each pinned on
 * <b>real sockets</b> with hostile bytes written by hand.
 *
 * <h2>The split this class exists to pin (contracts/tcp-framing.md §3)</h2>
 * <table border="1">
 *     <caption>error taxonomy</caption>
 *     <tr><th>Inbound bytes</th><th>Level</th><th>Behaviour</th></tr>
 *     <tr><td>garbage / non-JSON line</td><td>JSON</td>
 *         <td>{@code -32700}, {@code id: null}, <b>connection stays up</b></td></tr>
 *     <tr><td>valid JSON, not a JSON-RPC object</td><td>JSON</td>
 *         <td>{@code -32600}, connection stays up</td></tr>
 *     <tr><td>document over the envelope tier, under the transport tier</td><td>JSON</td>
 *         <td>{@code -32001}, connection stays up</td></tr>
 *     <tr><td>empty line</td><td>framing</td><td>connection closes, fixed-string cause</td></tr>
 *     <tr><td>no terminator within the transport tier</td><td>framing</td>
 *         <td>connection closes, {@link MalformedDataException}</td></tr>
 * </table>
 * A framing violation closes because there is no honest resynchronisation point; a JSON error is
 * answerable precisely because the framing <i>is</i> intact. Nothing derived from peer content
 * reaches a close cause or an error document (FR-097), which
 * {@link #testGarbageLineIsAnsweredParseErrorAndTheConnectionStaysUp()} asserts directly rather than
 * by inspection.
 *
 * <h2>Why the transport tier is raised in one test and lowered in two others</h2>
 * The two tiers are equal by default — {@code JsonRpcLimits.MAX_BODY_SIZE} on both — and with equal
 * tiers the <b>transport tier wins and {@code -32001} is unreachable</b> (contract §2, ADR-039). So
 * the {@code -32001} scenario raises the transport tier to 2 mb, strictly above the envelope tier,
 * exactly as the conformance subject does; the two framing-bound scenarios lower it to 8 kb so the
 * bound can be provoked in milliseconds rather than by pushing a megabyte.
 *
 * <h2>Two harness shapes, deliberately</h2>
 * The bound's <i>exception</i> is a transport-level fact, so it is asserted in-reactor over a socket
 * pair where the close cause is observable. Everything else needs the JUnit thread to write hostile
 * bytes and block on an answer, which a loop on that same thread cannot serve — so the server runs
 * on an {@link EventloopThread} and {@link JsonRpcTcpRawSocket} drives it, the {@code printf | nc}
 * one-liner of quickstart.md §2 expressed in Java. Every server binds port {@code 0} and is asked
 * where it landed (ADR-028).
 */
public final class JsonRpcTcpHostileTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Small enough to provoke the framing bound in milliseconds, large enough to be a real accumulation. */
	private static final MemSize SMALL_TIER = MemSize.kilobytes(8);

	/**
	 * Strictly above the envelope tier, so the envelope's {@code -32001} answer becomes reachable
	 * (contract §2). With the two tiers equal — the default — the connection would simply close.
	 */
	private static final MemSize RAISED_TIER = MemSize.megabytes(2);

	private static final int FLOOD_SIZE = 10_000;

	private static final String REQUEST_ID_1 =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";
	private static final String REQUEST_ID_7 =
		"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"test.add\",\"params\":[2,3]}";

	private @Nullable EventloopThread loop;
	private @Nullable JsonRpcTcpServer server;
	private final CountingTestApi service = new CountingTestApi();
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
	// The transport tier: bounded DURING accumulation, never after.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAStreamWithNoTerminatorDiesDuringAccumulationAtTheTransportTier() {
		// FR-016/FR-091: the bound is the framing decoder's own scan, so it fires the moment maxSize bytes
		// have passed without a terminator — NOT after a document was assembled and measured. This test
		// sends EXACTLY maxSize bytes and never a byte more: the refusal therefore cannot have waited for
		// anything, because nothing further was ever offered. No buffer of the attempted size is allocated,
		// which is the whole reason OfByteTerminated is composed here instead of a length check being
		// written by hand.
		Ref<Exception> closeCause = new Ref<>();
		List<byte[]> delivered = new ArrayList<>();

		byte[] noTerminator = new byte[SMALL_TIER.toInt()];
		Arrays.fill(noTerminator, (byte) 'x');

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.builder(reactor(), serverSocket)
				.withMaxMessageSize(SMALL_TIER)
				.build();
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(delivered::add, e -> {
				closeCause.set(e);
				closed.set(null);
			}));

			return clientSocket.write(ByteBuf.wrapForReading(noTerminator)).then(() -> closed);
		});

		assertTrue("a never-terminated accumulation is never delivered", delivered.isEmpty());
		assertThat(closeCause.get(), instanceOf(MalformedDataException.class));
		String message = closeCause.get().getMessage();
		assertTrue("the cause names the bound that fired: " + message,
			message.contains(String.valueOf(SMALL_TIER.toInt())));
		// FR-097: a fixed string plus a configured number — never a byte of what the peer sent
		assertFalse("no peer content in the close cause: " + message, message.contains("x"));
	}

	@Test
	public void testANeverTerminatedFloodIsRefusedLongBeforeTheAttemptedSizeIsSent() throws Exception {
		// The same bound, observed from outside: a peer that intends to send far more than the tier gets
		// hung up on almost immediately. This is the practical form of "no buffer of the attempted size is
		// ever allocated" — the server refuses after ~8 kb of an attempt that was going to be 512 kb, so
		// there is no size a peer can name that this transport will accumulate towards.
		startServer(SMALL_TIER);

		byte[] chunk = new byte[1024];
		Arrays.fill(chunk, (byte) 'x');
		int attempted = 512 * chunk.length;                       // 64x the transport tier
		int written = 0;
		boolean refused = false;

		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(address(), 25)) {
			while (written < attempted) {
				try {
					peer.write(chunk);
					written += chunk.length;
					// a short-bounded read doubles as the pacing and as the close observation: null is the
					// server's end-of-stream, a timeout means it is still accumulating
					if (peer.readLine() == null) {
						refused = true;
						break;
					}
				} catch (SocketTimeoutException stillAccumulating) {
					// no answer within the poll window — expected while the bound has not been crossed
				} catch (IOException refusedMidWrite) {
					// the server closed and the reset reached us mid-write: the same refusal, seen earlier
					refused = true;
					break;
				}
			}
		}

		assertTrue("a never-terminated stream must be refused, not accumulated", refused);
		// measured: refused after exactly SMALL_TIER bytes of the 512 kb attempt (2026-08-20). The bound
		// below keeps four times that as slack for a slower machine, not as an expectation.
		assertTrue(
			"refused after " + written + " bytes of an attempted " + attempted +
			": the bound must fire during accumulation, not after it",
			written <= 4 * SMALL_TIER.toInt());
		assertEquals("the refused connection left the registry", 0, sessionCount());
	}

	// -------------------------------------------------------------------------------------------
	// The envelope tier, made reachable: -32001 answers and the connection survives.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testADocumentBetweenTheTwoTiersIsAnsweredRequestTooLargeAndTheConnectionSurvives() {
		// FR-091 + contract §2: with the transport tier raised strictly above the envelope tier, a document
		// that fits the wire but not the envelope is a JSON-level error, not a framing violation — so it is
		// ANSWERED (-32001, id null) and the connection stays usable. With the two tiers equal, which is the
		// default, this same document would simply close the connection and -32001 would be unreachable.
		startServer(RAISED_TIER);

		int envelopeTier = JsonRpcLimits.MAX_BODY_SIZE.toInt();
		String oversize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3],\"pad\":\"" +
						  "x".repeat(envelopeTier + 4096) + "\"}";
		assertTrue("the document must exceed the envelope tier", oversize.length() > envelopeTier);
		assertTrue("...and stay under the transport tier", oversize.length() < RAISED_TIER.toInt());

		withRawSocket(peer -> {
			JsonRpcResponse tooLarge = errorResponse(exchange(peer, oversize));
			assertEquals(JsonRpcErrors.REQUEST_TOO_LARGE.code(), tooLarge.error().code());
			assertEquals(JsonRpcErrors.REQUEST_TOO_LARGE.message(), tooLarge.error().message());
			assertEquals("an unrecoverable id answers as null", JsonRpcId.NULL, tooLarge.id());

			// the connection survived the refusal: the very next call on the SAME connection is served
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);
			// asserted while the connection is still open — after it closes, the registry is empty for a
			// reason that has nothing to do with the refusal
			assertEquals("one session, still live after the refusal", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// JSON-level errors: answered, connection stays up.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testGarbageLineIsAnsweredParseErrorAndTheConnectionStaysUp() {
		// FR-092 + contract §3 row 2: a well-framed line whose content is not JSON is a JSON-level fault.
		// The framing is intact, so there IS a resynchronisation point — the next line — and the honest
		// answer is -32700 with id null rather than a close.
		startServer(RAISED_TIER);

		String garbage = "this is not json at all, and it carries SECRET-TOKEN-42";

		withRawSocket(peer -> {
			String answer = exchange(peer, garbage);

			JsonRpcResponse parseError = errorResponse(answer);
			assertEquals(JsonRpcErrors.PARSE_ERROR.code(), parseError.error().code());
			assertEquals(JsonRpcErrors.PARSE_ERROR.message(), parseError.error().message());
			assertEquals("a document whose id could not be recovered answers null", JsonRpcId.NULL,
				parseError.id());
			// FR-097 / feature 012 FR-055: dsl-json's own message embeds the offending input by
			// construction, so this asserts that none of it is echoed back to the peer
			assertFalse("nothing derived from peer content may appear in the answer: " + answer,
				answer.contains("SECRET-TOKEN-42"));

			// the connection stays up, and the next line is served normally
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);
			assertEquals("the session survived a parse error", 1, sessionCount());
		});
	}

	@Test
	public void testValidJsonThatIsNotAJsonRpcObjectIsAnsweredInvalidRequest() {
		// FR-092 + contract §3 row 3: parseable JSON that is not a JSON-RPC 2.0 message — an object with no
		// "jsonrpc" member, and an empty top-level array, which §6 makes an Invalid Request rather than an
		// empty batch. Both are -32600, both leave the connection usable.
		startServer(RAISED_TIER);

		withRawSocket(peer -> {
			JsonRpcResponse notAnEnvelope = errorResponse(exchange(peer, "{\"foo\":\"bar\"}"));
			assertEquals(JsonRpcErrors.INVALID_REQUEST.code(), notAnEnvelope.error().code());
			assertEquals(JsonRpcErrors.INVALID_REQUEST.message(), notAnEnvelope.error().message());
			assertEquals(JsonRpcId.NULL, notAnEnvelope.id());

			JsonRpcResponse emptyArray = errorResponse(exchange(peer, "[]"));
			assertEquals(JsonRpcErrors.INVALID_REQUEST.code(), emptyArray.error().code());
			assertEquals(JsonRpcId.NULL, emptyArray.id());

			// still up after two refusals in a row
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);
			assertEquals("the session survived two invalid requests", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// Framing-level violations and framing-level tolerance.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testABareLineFeedClosesTheConnection() throws Exception {
		// FR-093 + contract §1/§3: a line that is ONLY the terminator is a zero-length document, which SPI
		// obligation 3 forbids delivering. There is no honest resynchronisation point for a framing
		// violation, so the connection closes rather than answering. ByteBufRule is the other half of this
		// assertion and cannot be written by hand: the accumulation held at the moment of the violation is
		// recycled on the close path, or this class goes red at the end of its last test.
		startServer(RAISED_TIER);

		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(port)) {
			peer.write("\n");

			assertNull("a framing violation closes the connection, it does not answer", peer.readLine());
		}

		assertEquals("the closed session left the registry", 0, sessionCount());
	}

	@Test
	public void testACrlfTerminatedDocumentIsAcceptedAndAnsweredIdentically() {
		// FR-014 / contract D10: the transport trims nothing, and the carriage return is insignificant
		// trailing whitespace to the envelope decoder — so a peer on a platform where a newline is CRLF
		// interoperates at zero cost. Asserted as byte-for-byte equality with the LF-only answer to the same
		// document rather than against a literal, which is the actual claim: it decodes IDENTICALLY.
		startServer(RAISED_TIER);

		withRawSocket(peer -> {
			peer.write(REQUEST_ID_1 + "\r\n");
			String crlfAnswer = peer.readLine();
			assertNotNull("a CRLF-terminated document must be accepted", crlfAnswer);

			peer.write(REQUEST_ID_1 + "\n");
			String lfAnswer = peer.readLine();
			assertNotNull(lfAnswer);

			assertEquals("CRLF and LF must decode identically", lfAnswer, crlfAnswer);
			assertResultOfAdd(crlfAnswer, 1);
			assertEquals("the session is untouched by CRLF framing", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// The flood posture.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testANotificationFloodIsAbsorbedWithZeroBytesInReturn() {
		// FR-095, mirroring JsonRpcHttpNotificationFloodTest on a persistent connection: 10,000 notifications
		// back to back, half of them naming a method that does not exist. §4.1 forbids answering a
		// notification whatever its outcome, so the wire carries ZERO bytes back for all 10,000 — which on
		// this transport is asserted structurally rather than by counting: the very next line read is the
		// answer to the request sent AFTER the flood, so any notification that had produced a document would
		// have been read here instead.
		startServer(RAISED_TIER);

		String known = "{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"flood\"]}";
		String unknown = "{\"jsonrpc\":\"2.0\",\"method\":\"no.such\",\"params\":{\"x\":1}}";

		withRawSocket(peer -> {
			for (int i = 0; i < FLOOD_SIZE; i++) {
				peer.writeLine(i % 2 == 0 ? known : unknown);
			}

			// the first byte the server has sent this whole connection is the answer to THIS request
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);

			assertEquals("the flood created no session beyond the one connection", 1, sessionCount());
			assertEquals("no server-initiated call is left in flight", 0, inFlightCount());
		});

		// the read loop is serial, so every notification was dispatched before the final answer was written
		assertEquals("every known notification was dispatched, none dropped", FLOOD_SIZE / 2,
			service.noteCount());
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: the server on its own loop, driven by a blocking raw socket from the JUnit thread.
	// -------------------------------------------------------------------------------------------

	/**
	 * Starts one {@link JsonRpcTcpServer} with the given transport tier on a dedicated
	 * {@link EventloopThread}, bound to port {@code 0} and asked where it landed (ADR-028). The JUnit
	 * thread stays free to block on a socket, which a loop running on it could not serve.
	 */
	private void startServer(MemSize maxMessageSize) {
		EventloopThread loop = EventloopThread.create("jsonrpc-tcp-hostile-test");
		this.loop = loop;
		try {
			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, service)
					.build();
				JsonRpcTcpServer server = JsonRpcTcpServer.builder(loop.eventloop(), dispatcher)
					.withMaxMessageSize(maxMessageSize)
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

	/** Connects one raw peer to the running server, runs {@code body} against it, and closes it. */
	private void withRawSocket(HostilePeer body) {
		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(port)) {
			body.run(peer);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Writes one LF-terminated document and reads the one line the server answers with. */
	private static String exchange(JsonRpcTcpRawSocket peer, String document) throws IOException {
		peer.writeLine(document);
		String answer = peer.readLine();
		assertNotNull("the connection must stay up and answer", answer);
		return answer;
	}

	private static JsonRpcResponse errorResponse(String document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a single response document: " + document, input,
			instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertNotNull("expected an error response: " + document, response.error());
		return response;
	}

	private static void assertResultOfAdd(String document, long expectedId) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a response document: " + document, input, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertNull("expected a successful response: " + document, response.error());
		assertEquals(new JsonRpcId.Num(expectedId), response.id());
		assertTrue("expected the sum of 2 and 3: " + document, document.contains("\"sum\":5"));
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

	private InetSocketAddress address() {
		return new InetSocketAddress("localhost", port);
	}

	@FunctionalInterface
	private interface HostilePeer {
		void run(JsonRpcTcpRawSocket peer) throws IOException;
	}

	/**
	 * {@link TestApi} without the recorded list — a flood must not be assertable only by keeping every
	 * message it sent. The counter is atomic because the dispatcher increments it on the server's loop
	 * while the JUnit thread reads it.
	 */
	private static final class CountingTestApi implements TestApi {
		private final AtomicInteger notes = new AtomicInteger();

		@Override
		public Promise<AddResult> add(int a, int b) {
			return Promise.of(new AddResult(a + b));
		}

		@Override
		public void note(String text) {
			notes.incrementAndGet();
		}

		int noteCount() {
			return notes.get();
		}
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: the in-reactor socket pair, for the one assertion that is about an exception type.
	// -------------------------------------------------------------------------------------------

	/**
	 * One real TCP connection on the JUnit thread's own loop — an {@code acceptOnce} server on port
	 * {@code 0} plus a connected client — with both raw sockets handed to {@code body} and everything
	 * closed when the promise it returns completes. The same shape {@code JsonRpcTcpTransportTest} uses,
	 * and the only way to observe a close <i>cause</i>: a blocking peer sees a closed connection, never
	 * the exception that closed it.
	 */
	private static void withSockets(BiFunction<ITcpSocket, ITcpSocket, Promise<Void>> body) {
		NioReactor reactor = reactor();
		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer server = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		await(TcpSocket.connect(reactor, server.getBoundAddresses().get(0))
			.then(clientSocket -> accepted
				.then(serverSocket -> body.apply(clientSocket, serverSocket)
					.whenComplete(($, e) -> {
						clientSocket.close();
						serverSocket.close();
						server.close();
					}))));
	}

	private static JsonRpcTransport.Listener listener(
		Consumer<byte[]> onDocument, Consumer<@Nullable Exception> onClosed
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

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
