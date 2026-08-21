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
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
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
 * Domain A of the feature 017 adversarial test plan — wire &amp; framing under a hostile peer.
 * Seven scenarios, A1–A7, each verified against {@code contracts/tcp-framing.md} and the production
 * source (never against "what the code happens to do today") before the assertion was written.
 *
 * <h2>What every scenario in this class shares</h2>
 * The framing decoder ({@code OfByteTerminated}) understands exactly one byte value, {@code 0x0A} —
 * it does no JSON parsing and no UTF-8 validation of its own. Every scenario here exploits that gap
 * between "framing-intact" and "content-valid" one way or another: a raw LF splitting a string value
 * (A1), the exact off-by-one of the size bound (A2), invalid UTF-8 reaching the JSON decoder's own
 * explicit validation (A3), several documents sharing one TCP write (A4, A7), several framing
 * violations sharing one TCP write (A5), and a local caller bypassing {@code send}'s only defence,
 * document length (A6).
 *
 * <h2>Two harness shapes, deliberately (mirrors {@link JsonRpcTcpHostileTest})</h2>
 * A1–A4 and A7 drive a real {@link JsonRpcTcpServer} on an {@link EventloopThread} with a blocking
 * {@link JsonRpcTcpRawSocket} peer from the JUnit thread — the shape a hostile peer needs. A5 and A6
 * instead need to observe an internal fact a raw socket cannot see — exactly how many times
 * {@code onClosed} fires (A5), and whether {@code send}'s own promise fails (A6) — so they use one
 * real in-reactor {@link TcpSocket} pair on the JUnit thread's own {@link EventloopRule} reactor, the
 * shape {@link JsonRpcTcpHostileTest}'s exception-observing tests use. Every server binds port
 * {@code 0} and is asked where it landed (ADR-028).
 */
public final class JsonRpcTcpAdversarialFramingTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Small enough to provoke the size bound in milliseconds (A2), mirroring {@code JsonRpcTcpHostileTest}. */
	private static final MemSize SMALL_TIER = MemSize.kilobytes(8);

	/**
	 * Strictly above the envelope tier ({@code JsonRpcLimits.MAX_BODY_SIZE}, 1&nbsp;mb), so the
	 * envelope's {@code -32001} answer becomes reachable (contract §2, ADR-039) — the same value the
	 * conformance subject and {@code JsonRpcTcpHostileTest} use.
	 */
	private static final MemSize RAISED_TIER = MemSize.megabytes(2);

	private static final String REQUEST_ID_1 =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";
	private static final String REQUEST_ID_7 =
		"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"test.add\",\"params\":[2,3]}";

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
	// A1 (P0): a raw LF inside a JSON string value splits the stream, and the connection survives.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testARawLineFeedInsideAStringValueSplitsTheStreamAndTheConnectionResynchronises() {
		// FR-012 anticipates exactly this ("a raw LF splits the stream, the leading fragment answers
		// -32700, and the connection survives") but nothing in the module tested it before this. A
		// conforming JSON text never contains an unescaped 0x0A inside a string (RFC 8259 §7), and
		// JsonRpcEncoder never emits one — but OfByteTerminated does not know that: it terminates on
		// EVERY 0x0A, so a peer that violates §7 splits its own document in two at the framing tier,
		// long before the JSON decoder ever sees it.
		startServer(RAISED_TIER);

		String secretToken = "SECRET-TOKEN-A1";
		int splitAt = secretToken.length() / 2;
		// one write, one Java String literal containing a genuine raw 0x0A in the middle of the
		// "params" string value — writeLine appends ITS OWN trailing terminator, so the framing decoder
		// sees exactly two lines from this single call, never one
		String hostile = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.echo\",\"params\":[\"" +
			secretToken.substring(0, splitAt) + "\n" + secretToken.substring(splitAt) + "\"]}";

		withRawSocket(peer -> {
			peer.writeLine(hostile);

			// fragment 1 (up to the raw LF) is an unterminated JSON string — definitely not valid JSON —
			// so it is a JSON-level fault (contract §3), never a framing violation: -32700, id null.
			String firstAnswer = peer.readLine();
			assertNotNull("the leading fragment must be answered, not silently dropped or hung on",
				firstAnswer);
			JsonRpcResponse firstError = errorResponse(firstAnswer);
			assertEquals(JsonRpcErrors.PARSE_ERROR.code(), firstError.error().code());
			assertEquals("an unrecoverable id answers as null", JsonRpcId.NULL, firstError.id());

			// fragment 2 (from the raw LF to the appended terminator) is deliberately NOT asserted on for
			// content or error code — its shape depends only on where the split happened to land and is
			// not part of the contract. It must still be non-null: the framing survived, so this line
			// gets an answer too, exactly like any other non-empty line (contract §3).
			String secondAnswer = peer.readLine();
			assertNotNull("the trailing fragment must also be answered — the framing survived",
				secondAnswer);

			// the STRUCTURAL proof of resynchronisation: a well-formed request sent afterward, on the
			// SAME connection, is answered correctly.
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);
			assertEquals("exactly one session survives the whole exchange", 1, sessionCount());

			// FR-097: nothing derived from peer content ever reaches an answer, including this secret
			assertFalse("no peer content in the first answer: " + firstAnswer, firstAnswer.contains("SECRET"));
			assertFalse("no peer content in the second answer: " + secondAnswer, secondAnswer.contains("SECRET"));
		});
	}

	// -------------------------------------------------------------------------------------------
	// A2 (P0): the exact off-by-one of the transport-tier bound, both outcomes, one connection.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testDocumentAtMaxMessageSizeMinusOneIsDeliveredButExactlyAtMaxMessageSizeIsRefused() {
		// FR-016/FR-091 + OfByteTerminated.tryDecode read directly: the scanner checks whether the
		// CURRENT byte is the terminator BEFORE it checks whether the tier bound has been reached at
		// that same position. So a terminator landing on the very last byte the tier allows (content
		// length maxSize-1) is accepted; a maxSize-th byte that is NOT the terminator trips the bound
		// immediately, and the scanner never even looks at position maxSize — it does not matter
		// whether a terminator would have followed there or not. This is an off-by-one the contract
		// does not document in these terms, and nothing in the module pinned it before this test.
		startServer(SMALL_TIER);
		int max = SMALL_TIER.toInt();

		withRawSocket(peer -> {
			// (a) content of exactly max-1 bytes, correctly LF-terminated: accepted.
			String accepted = paddedAddRequest(max - 1);
			assertEquals("the crafted request must be exactly max-1 bytes",
				max - 1, accepted.getBytes(UTF_8).length);
			assertResultOfAdd(exchange(peer, accepted), 1);
			assertEquals("the accepted document did not touch session cardinality", 1, sessionCount());

			// (b) content of exactly max bytes, no terminator anywhere within them: refused immediately —
			// on the SAME, still-open connection, right after (a) succeeded on it.
			byte[] refused = new byte[max];
			Arrays.fill(refused, (byte) 'z');
			peer.write(refused);
			assertNull("a framing violation closes the connection, it does not answer", peer.readLine());
		});

		assertEquals("the closed session left the registry", 0, sessionCount());
	}

	// -------------------------------------------------------------------------------------------
	// A3 (P0): outright invalid UTF-8 bytes before the terminator.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testInvalidUtf8BytesBeforeTheTerminatorAreAnsweredParseErrorAndTheConnectionStaysUp() {
		// The framing decoder does no UTF-8 validation at all — it understands one byte, 0x0A — so
		// outright invalid UTF-8 reaches the JSON decoder completely intact. JsonRpcDecoder's own
		// isWellFormedUtf8(...) then rejects it explicitly, BEFORE any JSON parsing: three distinct
		// ways to be invalid, verified against that exact validator's ranges (JsonRpcDecoder.java) so
		// none of these vectors is a guess.
		startServer(RAISED_TIER);
		byte[] prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[".getBytes(UTF_8);

		withRawSocket(peer -> {
			// 1) an isolated UTF-16 low surrogate encoded directly as UTF-8 (0xED 0xA0 0x80) — RFC 3629
			// forbids ANY surrogate codepoint in UTF-8, and the validator's own 0xED branch excludes
			// exactly the 0xA0-0xBF second-byte range that would encode one.
			assertInvalidUtf8IsAnsweredParseError(peer,
				concat(prefix, new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, "]}\n".getBytes(UTF_8)));

			// 2) a non-minimal (overlong) encoding using a lead byte the validator refuses outright
			// (0xC0 is below its accepted 0xC2 floor) — the classic security-relevant invalid encoding.
			assertInvalidUtf8IsAnsweredParseError(peer,
				concat(prefix, new byte[] {(byte) 0xC0, (byte) 0xAF}, "]}\n".getBytes(UTF_8)));

			// 3) a three-byte lead byte with NO continuation byte at all before the terminator — the
			// terminator sits exactly where a continuation byte was required, and 0x0A never IS a valid
			// continuation byte (those are 0x80-0xBF), so this is the case most likely to slip past a
			// decoder that conflates "well-framed" with "well-formed".
			assertInvalidUtf8IsAnsweredParseError(peer, concat(prefix, new byte[] {(byte) 0xE2}, new byte[] {'\n'}));

			// the connection survived all three: the next well-formed request answers normally
			assertResultOfAdd(exchange(peer, REQUEST_ID_7), 7);
			assertEquals("the session survived three invalid-UTF-8 lines", 1, sessionCount());
		});
	}

	private static void assertInvalidUtf8IsAnsweredParseError(JsonRpcTcpRawSocket peer, byte[] document)
		throws IOException {
		peer.write(document);
		String answer = peer.readLine();
		assertNotNull("invalid UTF-8 must still be answered, not silently dropped or left to hang", answer);
		JsonRpcResponse parseError = errorResponse(answer);
		assertEquals(JsonRpcErrors.PARSE_ERROR.code(), parseError.error().code());
		assertEquals("an unrecoverable id answers as null", JsonRpcId.NULL, parseError.id());
	}

	// -------------------------------------------------------------------------------------------
	// A4 (P1): several documents pipelined in one write, a garbage element isolated among them.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testPipelinedDocumentsInOneWriteAreAnsweredIndividuallyInOrderWithGarbageIsolated() {
		// FR-013: pipelining is only ever exercised in this module as several SEPARATE writes
		// (JsonRpcTcpFragmentationTest splits ONE document into pieces; the flood test in
		// JsonRpcTcpHostileTest issues one writeLine() per document). Nothing before this concatenates
		// several COMPLETE documents into a single socket write() — the case a peer that batches its
		// own writes for efficiency actually produces.
		startServer(RAISED_TIER);

		String garbage = "not json at all, and it carries no method name";
		String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"pipelined-A4\"]}";
		String combined = REQUEST_ID_1 + "\n" + garbage + "\n" + REQUEST_ID_7 + "\n" + notification + "\n";

		withRawSocket(peer -> {
			peer.write(combined);

			// three answers arrive, in emission order: valid, garbage (isolated, -32700), valid. The
			// notification produces none — proven structurally below, not by counting.
			assertResultOfAdd(peer.readLine(), 1);

			JsonRpcResponse garbageAnswer = errorResponse(peer.readLine());
			assertEquals(JsonRpcErrors.PARSE_ERROR.code(), garbageAnswer.error().code());
			assertEquals("the garbage element did not disturb its neighbours' correlation",
				JsonRpcId.NULL, garbageAnswer.id());

			assertResultOfAdd(peer.readLine(), 7);

			// the notification is silent: the VERY NEXT line read is the answer to a request sent
			// afterward — if the notification had produced a document, it would have been read here
			// instead, exactly the structural proof JsonRpcTcpHostileTest's flood test uses.
			assertResultOfAdd(exchange(peer, REQUEST_ID_1), 1);
			assertEquals("one session for the whole pipelined write", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// A5 (P1): a flood of bare LFs in one write closes on the first, the rest is never signalled.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAFloodOfBareLineFeedsInOneWriteClosesOnTheFirstViolationOnly() {
		// SPI obligation 6 + contract §3: a framing violation closes the connection because there is no
		// honest resynchronisation point. JsonRpcTcpHostileTest's bare-LF tests send exactly ONE `\n` as
		// the whole buffer; this sends FIVE in a single write(), on a connection that has already
		// carried real traffic, and asserts "exactly once" the only way that is actually provable: by
		// instrumenting the transport's own listener directly, in-reactor, rather than by observing the
		// registry from outside (0 vs. 0 cannot distinguish one close signal from five idempotent ones).
		AtomicInteger closeCount = new AtomicInteger();
		Ref<Exception> closeCause = new Ref<>();
		List<byte[]> delivered = new ArrayList<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.builder(reactor(), serverSocket)
				.withMaxMessageSize(RAISED_TIER)
				.build();
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(delivered::add, e -> {
				closeCount.incrementAndGet();
				closeCause.set(e);
				closed.trySet(null);
			}));

			// one legitimate document first — this connection is already in service, not freshly opened
			return clientSocket.write(ByteBuf.wrapForReading((REQUEST_ID_1 + "\n").getBytes(UTF_8)))
				.then(() -> clientSocket.write(ByteBuf.wrapForReading("\n\n\n\n\n".getBytes(UTF_8))))
				.then(() -> closed);
		});

		assertEquals("exactly one document delivered before the flood", 1, delivered.size());
		assertEquals("onClosed must fire exactly once even though five violations sit in the same buffer",
			1, closeCount.get());
		assertThat(closeCause.get(), instanceOf(MalformedDataException.class));
	}

	// -------------------------------------------------------------------------------------------
	// A6 (P2, documentary): send() with an embedded raw LF — pinned, not fixed, this pass.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSendWithAnEmbeddedRawLineFeedIsSilentlySplitByThePeerWithNoErrorOnTheSender()
		throws Exception {
		// send(byte[]) only ever scans its argument for zero length (FR-018) — nothing in the transport
		// defends against a caller handing it a document that already contains a raw 0x0A, and FR-012's
		// premise is that JsonRpcEncoder never produces one, not that send() enforces it. This pins
		// TODAY's behaviour deliberately, per the task's instruction not to "fix" a documented gap
		// without first confirming a real bug — and this is not one: it is a violation of an
		// obligation on the CALLER (FR-012), not on the transport, exactly like send(new byte[0])
		// documents its own different, defended, obligation.
		EventloopThread loop = EventloopThread.create("jsonrpc-tcp-adversarial-a6");
		String docA = "{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"a\"]}";
		String docB = "{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"b\"]}";
		// one caller-supplied document, with a raw LF ALREADY inside it — send() appends its own
		// terminator on top, so the wire ends up with TWO LF-terminated lines from ONE send() call
		byte[] hostile = (docA + "\n" + docB).getBytes(UTF_8);
		Ref<Exception> sendFailure = new Ref<>();
		try {
			// send() is issued from WITHIN the accept callback itself — not from a second submit() after
			// the raw peer's connect() returns — because the OS-level handshake completing (what
			// unblocks the peer's blocking connect()) does not happen-before the server reactor's own
			// accept event being processed; a second, separately-scheduled submit() racing that accept
			// event is a real flake this test hit once under -Dchk=on, not a production bug.
			int transportPort = loop.submit(() -> {
				SimpleServer rawServer = SimpleServer.builder(loop.eventloop(), socket -> {
					JsonRpcTcpTransport transport = JsonRpcTcpTransport.of(loop.eventloop(), socket);
					transport.setListener(listener($ -> {}, $ -> {}));
					transport.send(hostile).whenException(sendFailure::set);
				}).withListenPort(0).withAcceptOnce().build();
				rawServer.listen();
				loop.onClose(rawServer::close);
				return rawServer.getBoundAddresses().get(0).getPort();
			});

			try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(transportPort)) {
				String firstLine = peer.readLine();
				String secondLine = peer.readLine();

				assertEquals("the peer sees the caller's document silently split into two lines",
					docA, firstLine);
				assertEquals("...the second half answered as its own line, not appended or dropped",
					docB, secondLine);

				// rendezvous: by now the write has certainly reached the OS (the peer observed the
				// bytes), and one more round trip through the loop lets any already-scheduled promise
				// callback run before this assertion reads it
				loop.submit(() -> {});
				assertNull("send() must not fail on the caller's side even though it silently " +
					"desynchronised the peer's framing — send()'s only defence is FR-018's " +
					"zero-length check, and this document is not empty", sendFailure.get());
			}
		} finally {
			loop.close();
		}
	}

	// -------------------------------------------------------------------------------------------
	// A7 (P2): pipelining combined with the two-tier boundary, in one write.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testPipelinedDocumentsAcrossTheEnvelopeTierAnswerInOrderWithoutBreakingNeighbours() {
		// FR-013 (pipelining preserves order) and FR-016/FR-091 (the two-tier bound) are each proven
		// alone elsewhere in the module — JsonRpcTcpHostileTest's own oversize test sends the oversize
		// document ALONE, in its own exchange() round trip. Nothing combines them: an oversize document
		// arriving in the MIDDLE of a pipelined write, sharing a network buffer with a valid document on
		// each side of it. The transport tier is raised above the envelope tier exactly as the
		// conformance subject does, so -32001 is reachable at all (contract §2, ADR-039).
		startServer(RAISED_TIER);

		int envelopeTier = JsonRpcLimits.MAX_BODY_SIZE.toInt();
		String oversize = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.add\",\"params\":[2,3],\"pad\":\"" +
			"x".repeat(envelopeTier + 4096) + "\"}";
		assertTrue("the document must exceed the envelope tier", oversize.length() > envelopeTier);
		assertTrue("...and stay under the transport tier", oversize.length() < RAISED_TIER.toInt());

		withRawSocket(peer -> {
			String combined = REQUEST_ID_1 + "\n" + oversize + "\n" + REQUEST_ID_7 + "\n";
			peer.write(combined);

			assertResultOfAdd(peer.readLine(), 1);

			JsonRpcResponse tooLarge = errorResponse(peer.readLine());
			assertEquals(JsonRpcErrors.REQUEST_TOO_LARGE.code(), tooLarge.error().code());
			assertEquals("the oversize element in the middle did not disturb its neighbours' correlation",
				JsonRpcId.NULL, tooLarge.id());

			assertResultOfAdd(peer.readLine(), 7);
			assertEquals("one session, still live after all three pipelined documents", 1, sessionCount());
		});
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
		EventloopThread loop = EventloopThread.create("jsonrpc-tcp-adversarial-framing-test");
		this.loop = loop;
		try {
			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
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

	/**
	 * Builds a well-formed {@code test.add} request whose UTF-8 byte length is EXACTLY
	 * {@code totalBytes}, by padding an extra string member. All characters involved are ASCII, so
	 * char length and UTF-8 byte length coincide.
	 */
	private static String paddedAddRequest(int totalBytes) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3],\"pad\":\"";
		String suffix = "\"}";
		int padLen = totalBytes - prefix.length() - suffix.length();
		if (padLen < 0) {
			throw new IllegalArgumentException("totalBytes too small to craft a request of that length");
		}
		return prefix + "x".repeat(padLen) + suffix;
	}

	private static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] part : parts) total += part.length;
		byte[] result = new byte[total];
		int at = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, at, part.length);
			at += part.length;
		}
		return result;
	}

	private static JsonRpcResponse errorResponse(String document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a single response document: " + document, input, instanceOf(JsonRpcResponse.class));
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

	@FunctionalInterface
	private interface HostilePeer {
		void run(JsonRpcTcpRawSocket peer) throws IOException;
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: one in-reactor socket pair (JUnit thread), for observing an internal fact — the exact
	// number of close signals (A5) or the send() promise's own outcome (A6, over a plain SimpleServer).
	// -------------------------------------------------------------------------------------------

	/**
	 * One real TCP connection on the JUnit thread's own loop — an {@code acceptOnce} server on port
	 * {@code 0} plus a connected client — with both raw sockets handed to {@code body} and everything
	 * closed when the promise it returns completes. The same shape {@link JsonRpcTcpHostileTest} uses
	 * for its exception-observing tests, and the only way to see how many times a close signal fires: a
	 * blocking peer sees a closed connection, never the number of internal {@code onClosed} calls.
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
