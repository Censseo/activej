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

import io.activej.async.exception.AsyncCloseException;
import io.activej.async.function.AsyncRunnable;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.schema.OpenRpcDocument;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.schema.OpenRpcMethod;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcNotification;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.JsonRpcTcpRawSocket;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.SocketSettings;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Feature 018 under a hostile framed-TCP peer — the adversarial companion to
 * {@link JsonRpcTcpDiscoveryTest}, which establishes only the happy path (the document decodes, and the
 * bytes off the wire are the bytes the dispatcher produced).
 *
 * <h2>Why discovery deserves its own hostile suite on <i>this</i> transport</h2>
 * Feature 018 made <b>zero</b> main-source changes to {@code cloud-jsonrpc-tcp} — {@code rpc.discover} is
 * an ordinary entry in the dispatcher's frozen handler table, so it rides this wire "for free". That claim
 * is cheap to state and easy to over-trust, because every hostile mechanic this module already pins was
 * tuned against <b>small</b> documents: {@code JsonRpcTcpFragmentationTest} dribbles a 59-byte request,
 * {@code JsonRpcTcpHostileTest} and {@code JsonRpcTcpAdversarialFramingTest} exchange few-dozen-byte
 * requests, and the two size tiers were exercised with hand-padded strings. The OpenRPC document is a
 * different animal on all three counts: it is <b>two orders of magnitude larger</b> (2.6 kb for the
 * four-method fixture below, 80 kb for the deeply-nested one), it is <b>outbound</b> rather than inbound,
 * and it <b>grows with the service surface</b> — so a deployment's document size is not a constant the
 * module's authors ever chose.
 *
 * <h2>The seven scenarios, and the property each one is really about</h2>
 * <table border="1">
 *     <caption>scenario map</caption>
 *     <tr><th>#</th><th>Hostility</th><th>Property under test</th></tr>
 *     <tr><td>D1</td><td>the response dribbled one byte at a time</td>
 *         <td>the LF delimiter reassembles a 2.6 kb line with no truncation and no early delivery</td></tr>
 *     <tr><td>D2</td><td>a transport tier <i>below</i> the response's own size</td>
 *         <td>the tier bounds <b>accumulation only</b> — the same server emits a line it would refuse to
 *         receive, and the {@code maxMessageSize − 1} boundary is exact on the receiving side</td></tr>
 *     <tr><td>D3</td><td>a valid discover line and a bare LF in one write</td>
 *         <td><b>ordering</b>: the answer is complete on the wire before the violation closes</td></tr>
 *     <tr><td>D4</td><td>CRLF termination</td>
 *         <td>the module-wide "CRLF decodes identically" claim, verified for this request/response pair
 *         rather than inherited</td></tr>
 *     <tr><td>D5</td><td>a pretty-printed request</td>
 *         <td>discovery gets <b>no exemption</b> from "multi-line JSON is not carriable"</td></tr>
 *     <tr><td>D6</td><td>five discover calls pipelined in one write</td>
 *         <td>strict 1:1, in-order delivery of five large answers</td></tr>
 *     <tr><td>D7</td><td>RST while an 80 kb response is provably in flight</td>
 *         <td>clean teardown: registry drains, no leak, no unhandled exception, the server survives</td></tr>
 * </table>
 *
 * <h2>What this class deliberately does not re-litigate</h2>
 * Discovery's <i>envelope</i> semantics — off by default, {@code -32601} when disabled, a notification
 * producing nothing, non-empty {@code params} answering {@code -32602} — belong to
 * {@code JsonRpcDispatcherDiscoveryTest} in {@code activej-jsonrpc} and are established once, over the
 * in-memory transport. The generic wire taxonomy (garbage → {@code -32700}, bare LF closes, the two tiers)
 * belongs to {@link JsonRpcTcpHostileTest} and {@link JsonRpcTcpAdversarialFramingTest}. Everything here
 * needs the discovery document to be meaningful.
 *
 * <h2>Harness shapes and quiescence (ADR-028, ADR-040)</h2>
 * D3–D7 drive a real {@link JsonRpcTcpServer} on an {@link EventloopThread} with a blocking peer from the
 * JUnit thread — the only shape in which a peer can write hostile bytes and block on an answer. D1 and D2b
 * need to observe an internal fact a blocking peer cannot see (how many documents a <i>client</i> transport
 * delivered, and the exception that closed it), so they use one in-reactor socket pair on the JUnit
 * thread's own {@link EventloopRule} loop. Every server binds port {@code 0} and is asked where it landed;
 * every socket an in-reactor test opens is closed inside the chain it awaits, or {@code TestUtils.await}
 * hangs the suite rather than failing it.
 */
public final class JsonRpcTcpDiscoveryAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/**
	 * Application-supplied, never derived (FR-013), and deliberately unlike anything this module could
	 * produce on its own — a document echoing a class name or a POM version could not pass.
	 */
	private static final OpenRpcInfo INFO = new OpenRpcInfo("Framed TCP Discovery Under Fire", "18.0.0");

	/** The one request of this feature, as the raw document an integrator with nothing but {@code nc} sends. */
	private static final String DISCOVER = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"rpc.discover\"}";

	private static final byte LF = (byte) '\n';

	private @Nullable EventloopThread loop;
	private @Nullable JsonRpcTcpServer server;
	private final List<Exception> failures = new CopyOnWriteArrayList<>();
	private int port;

	@After
	public void tearDown() throws Exception {
		try {
			if (server != null) {
				// closeFuture() submits close() to the server's own reactor and completes when the drain has
				// emptied the registry — the only way to join a server owned by another thread, and the
				// assertion that no scenario here left a session that cannot be drained
				server.closeFuture().get(10, TimeUnit.SECONDS);
			}
		} finally {
			if (loop != null) loop.close();
		}
	}

	// -------------------------------------------------------------------------------------------
	// D1: the discovery response delivered one byte at a time.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testTheDiscoveryResponseDribbledOneByteAtATimeReassemblesIntoExactlyOneDocument()
		throws MalformedDataException {
		// JsonRpcTcpFragmentationTest proves the decoder's restartability on a 59-byte REQUEST arriving at
		// the server. This is the same proof turned around and scaled up by a factor of forty-five: the
		// ~2.6 kb discovery RESPONSE arriving at a client, every byte its own socket write, each issued only
		// after the previous one completed. Two failures would be invisible at 59 bytes and obvious here —
		// a decoder that gives up and delivers a partial accumulation (truncation), and one that delivers
		// the same accumulation twice while resuming (duplication).
		//
		// The dribbled bytes are not a literal: they are what the real dispatcher answers rpc.discover
		// with, obtained in process, so this test cannot drift away from what the server actually emits.
		byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);
		assertTrue("the premise of this test is a document far larger than any other line this module " +
				   "frames: " + expected.length + " bytes",
			expected.length > 2000);

		byte[] frame = terminated(expected);
		List<byte[]> received = new ArrayList<>();
		Ref<Exception> closeCause = new Ref<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			SettablePromise<byte[]> firstDocument = new SettablePromise<>();
			client.setListener(listener(
				document -> {
					received.add(document);
					firstDocument.trySet(document);
				},
				e -> {
					// only a close that beat the document is a finding — the one at the end of the chain is
					// this test closing its own transport
					if (received.isEmpty()) {
						closeCause.set(e != null ?
							e :
							new AsyncCloseException("the peer closed before the document was delivered"));
					}
					// a close before the answer must fail the chain, never hang TestUtils.await
					firstDocument.trySetException(e != null ?
						e :
						new AsyncCloseException("the connection closed before the document was delivered"));
				}));

			List<AsyncRunnable> bytes = new ArrayList<>(frame.length);
			for (int i = 0; i < frame.length; i++) {
				int at = i;
				bytes.add(() -> write(serverSocket, frame, at, at + 1));
			}
			return Promises.sequence(bytes)
				.then(() -> firstDocument)
				.whenComplete(($, e) -> client.close())
				.toVoid();
		});

		assertNull("no close may beat the document — a decoder that gave up mid-accumulation would show " +
				   "up exactly here: " + closeCause.get(), closeCause.get());
		assertEquals("one LF-terminated line, however many TCP segments carried it", 1, received.size());
		assertArrayEquals("the reassembled document must be byte-identical, not merely well-formed",
			expected, received.get(0));

		// ...and it is still a usable OpenRPC document at the far end, not just an equal array
		OpenRpcDocument document = openRpcDocumentOf(received.get(0), 1);
		assertEquals(INFO, document.info());
		assertTrue("every wire name of the service must survive the dribble: " + names(document),
			names(document).containsAll(DISCOVERY_WIRE_NAMES));
	}

	// -------------------------------------------------------------------------------------------
	// D2a: the transport tier bounds accumulation, not emission.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAServerWhoseTierIsBelowItsOwnDiscoveryResponseStillEmitsTheWholeDocument() {
		// The module's contract says the transport tier fires "during accumulation, in the decoder's scan".
		// Taken literally that means it is an INBOUND bound and nothing else — send() scans its argument for
		// zero length and for nothing else. This test states the consequence in the sharpest form available:
		// the server's tier is set to EXACTLY the length of the discovery answer it is about to write, which
		// (by the maxMessageSize-1 rule) is one byte too small for that very line to be RECEIVED. It emits
		// it in full anyway, and then refuses the identical byte sequence when the peer sends it back.
		//
		// Both halves run on ONE connection, with the same byte count, so nothing about the comparison is
		// approximate. Report of the actual behaviour, since it is easy to get subtly wrong: an oversized
		// OUTBOUND discovery response is NOT bounded, refused, chunked or truncated by withMaxMessageSize —
		// it is written whole, and the tier that matters for it is the PEER's.
		byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);
		int tier = expected.length;
		startServer(JsonRpcTcpDiscoveryAdversarialTest::discoveryDispatcher, MemSize.bytes(tier), null);

		withRawSocket(peer -> {
			// (a) outbound: the whole document crosses, though it is one byte too long to come back
			peer.writeLine(DISCOVER);
			String answer = peer.readLine();
			assertNotNull("the discovery answer must not be truncated by the server's own tier", answer);
			assertArrayEquals("the server emitted a line longer than the tier it accumulates to",
				expected, answer.getBytes(UTF_8));
			assertEquals("one session, unaffected by emitting an over-tier line", 1, sessionCount());

			// (b) inbound, same bytes, same connection: refused during accumulation. OfByteTerminated throws
			// at index maxSize-1 when that byte is not the terminator, so a line of exactly `tier` content
			// bytes dies one byte before its own LF is ever examined
			peer.write(terminated(expected));
			assertNull("the same byte sequence is a framing violation on the way IN", peer.readLine());
		});

		assertEquals("the refused connection left the registry", 0, sessionCount());
	}

	// -------------------------------------------------------------------------------------------
	// D2b: the maxMessageSize-1 boundary, pinned on the discovery response itself.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAClientTierRefusesTheDiscoveryResponseAtItsExactLengthAndAcceptsItOneByteHigher()
		throws MalformedDataException {
		// The boundary the module's CLAUDE.md warns about — "the effective bound is maxMessageSize − 1
		// content bytes" — has only ever been pinned with hand-padded filler (A2 in
		// JsonRpcTcpAdversarialFramingTest). Here the document at the boundary is one nobody chose: it is
		// whatever the registered service happens to generate, which is exactly the situation an operator
		// sizing a client tier is in. Both sides of the boundary, same document, same server.
		byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);

		Outcome refused = discoverWithClientTier(MemSize.bytes(expected.length));
		assertTrue("a response of exactly maxMessageSize content bytes must never be delivered",
			refused.documents.isEmpty());
		assertThat("...it is a framing violation, raised during accumulation", refused.closeCause,
			instanceOf(MalformedDataException.class));
		String message = refused.closeCause.getMessage();
		assertTrue("the cause names the bound that fired: " + message,
			message.contains(String.valueOf(expected.length)));
		// FR-097: a fixed string plus a configured number — never a byte of the document that tripped it
		assertFalse("no peer content in the close cause: " + message, message.contains("openrpc"));

		Outcome accepted = discoverWithClientTier(MemSize.bytes(expected.length + 1));
		assertEquals("one byte of headroom is the whole difference", 1, accepted.documents.size());
		assertArrayEquals(expected, accepted.documents.get(0));
		assertEquals("the accepted document is the real thing, not a prefix", INFO,
			openRpcDocumentOf(accepted.documents.get(0), 1).info());
	}

	// -------------------------------------------------------------------------------------------
	// D3: ordering — the answer is complete before the next line's violation closes the connection.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testTheDiscoveryAnswerIsCompleteOnTheWireBeforeABareLineFeedClosesTheConnection() {
		// A bare LF is a framing violation and the connection closes (FR-017) — JsonRpcTcpHostileTest pins
		// that with the LF as the whole buffer. This is the harder question the module's own contract does
		// not answer in so many words: when a VALID line and the violation share a single TCP write, does
		// the valid line's answer survive the close that the violation triggers moments later, on a read
		// loop that resumes synchronously after the answer was handed to the socket?
		//
		// It is worth asking here rather than generically because the answer is 2.6 kb: a close that
		// discarded a partially-flushed write would truncate this document where it would leave a
		// forty-byte one intact. The assertion is therefore byte-exact equality with the whole expected
		// answer, not merely "an answer arrived".
		byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);
		startServer(JsonRpcTcpDiscoveryAdversarialTest::discoveryDispatcher, null, null);

		withRawSocket(peer -> {
			// ONE write: the server's decoder sees both lines in one buffer, so the violation is already
			// queued behind the request when the answer is produced
			peer.write(DISCOVER + "\n" + "\n");

			String answer = peer.readLine();
			assertNotNull("the valid line's answer must be delivered before the violation closes", answer);
			assertArrayEquals("the answer must be COMPLETE, not truncated by the close that follows it",
				expected, answer.getBytes(UTF_8));

			assertNull("...and then the bare LF closes the connection, with no second answer",
				peer.readLine());
		});

		assertEquals("the closed session left the registry", 0, sessionCount());
	}

	// -------------------------------------------------------------------------------------------
	// D3bis (CHARACTERIZATION): where D3's ordering guarantee stops — and it is a size, not a rule.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAnAnswerStillInFlightWhenAFramingViolationArrivesIsTruncatedByTheClose() {
		// CHARACTERIZATION, NOT A BUG REPORT. D3 above shows the answer arriving whole before the bare LF
		// closes the connection — but that result is a consequence of the answer having FLUSHED, not of any
		// ordering rule the transport implements. This test pins the other side of that line, because the
		// distinction is invisible at the sizes every other test in this module uses and the discovery
		// document is the thing most likely to cross it.
		//
		// The behaviour: a framing violation calls closeEx, which closes the medium immediately
		// (JsonRpcTcpTransport.closeMedium -> socket.closeEx), and TcpSocket discards whatever was still in
		// its write buffer. A peer whose receive window cannot swallow the answer in one go therefore gets a
		// PREFIX and then end-of-stream — never a complete line, so never a document.
		//
		// Why this is intentional rather than a defect:
		//   * the contract's own words are "there is no honest resynchronisation point once boundaries are
		//     lost"; nothing in it promises a drain, and §4 lists no delivery guarantee for pending writes;
		//   * draining first would let a peer that sends one hostile byte pin an arbitrarily large write on
		//     the server for as long as it declines to read — a denial-of-service primitive traded for a
		//     courtesy owed to a peer that has just violated the framing;
		//   * it is NOT discovery-specific: any answer larger than the socket's write path has always
		//     behaved this way. Discovery merely makes it easy to reach, since the OpenRPC document is the
		//     largest thing this transport emits and it grows with the service surface.
		//
		// Should the module ever decide to drain on a violation, this test fails and names the decision.
		byte[] expected = discoverAnswer(bulkDispatcher(reactor()), DISCOVER);
		startServer(JsonRpcTcpDiscoveryAdversarialTest::bulkDispatcher, null,
			SocketSettings.builder().withSendBufferSize(MemSize.kilobytes(8)).build());

		int received = 0;
		boolean sawTerminator = false;
		try (Socket peer = constrainedPeer()) {
			// the same single write as D3 — a valid discover request and a bare LF sharing one buffer
			OutputStream out = peer.getOutputStream();
			out.write((DISCOVER + "\n" + "\n").getBytes(UTF_8));
			out.flush();

			InputStream in = peer.getInputStream();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = in.read(chunk)) != -1) {
				for (int i = 0; i < read; i++) {
					if (chunk[i] == LF) sawTerminator = true;
				}
				received += read;
			}
		} catch (IOException e) {
			// a reset reaching us mid-read is the same close, observed a moment earlier
		}

		assertFalse("no complete line reached the peer — the answer was cut off before its terminator",
			sawTerminator);
		assertTrue("the peer got a prefix (" + received + " bytes) of a " + expected.length +
				   "-byte answer, not the whole of it", received < expected.length);
		assertTrue("...and it got some of it: the write had certainly begun", received > 0);

		// FR-097 holds on this path too: the fixed-string cause carries nothing the peer sent, and nothing
		// of the document that was being written
		for (Exception failure : failures) {
			String message = String.valueOf(failure.getMessage());
			assertFalse("no peer content in a reported failure: " + message, message.contains("openrpc"));
			assertFalse("no peer content in a reported failure: " + message, message.contains("rpc.discover"));
		}
	}

	// -------------------------------------------------------------------------------------------
	// D4: CRLF termination, verified for this request/response pair.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testACrlfTerminatedDiscoverRequestIsAnsweredIdenticallyToTheLfTerminatedOne() {
		// The module claims CRLF costs it zero code because the carriage return is insignificant trailing
		// whitespace to the ENVELOPE decoder — i.e. the claim is about JsonRpcDecoder, not about this
		// transport, and it is pinned generically by JsonRpcTcpHostileTest on a test.add request. Discovery
		// is the one method whose handler never touches the decoder's output (it answers from a pre-encoded
		// array), so "the request decoded fine" and "the right handler was selected" are separable here in a
		// way they are not for an ordinary method. Verified rather than inherited, and asserted as
		// byte-for-byte equality with the LF answer — which is the actual claim, "identically".
		byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);
		startServer(JsonRpcTcpDiscoveryAdversarialTest::discoveryDispatcher, null, null);

		withRawSocket(peer -> {
			peer.write(DISCOVER + "\r\n");
			String crlfAnswer = peer.readLine();
			assertNotNull("a CRLF-terminated discover request must be accepted", crlfAnswer);

			peer.write(DISCOVER + "\n");
			String lfAnswer = peer.readLine();
			assertNotNull(lfAnswer);

			assertEquals("CRLF and LF must decode identically", lfAnswer, crlfAnswer);
			assertArrayEquals("and both must be the document the dispatcher produces in process",
				expected, crlfAnswer.getBytes(UTF_8));

			OpenRpcDocument document = openRpcDocumentOf(crlfAnswer.getBytes(UTF_8), 1);
			assertEquals(INFO, document.info());
			assertTrue("the CR must not have leaked into the described surface: " + names(document),
				names(document).containsAll(DISCOVERY_WIRE_NAMES));
			assertEquals("the session is untouched by CRLF framing", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// D5: a pretty-printed request gets no exemption.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAPrettyPrintedDiscoverRequestIsAnsweredParseErrorWithNoExemptionForDiscovery() {
		// "Pretty-printed / multi-line JSON is not carriable" is one of this wire contract's three named
		// consequences, and rpc.discover is the request an integrator is most likely to paste from a
		// human-readable example — a hand-formatted curl or nc invocation, copied out of a README. It is
		// therefore the single most likely place for someone to assume a special case exists. There is none:
		// the framing decoder understands one byte and does no JSON parsing, so the five lines of the
		// pretty form are five documents, every one of them broken.
		//
		// The sharpest assertion is the negative one — no answer anywhere in the exchange contains the
		// string "openrpc" — because a transport that "helpfully" reassembled the fragments would answer the
		// document and pass every positive assertion in this test.
		startServer(JsonRpcTcpDiscoveryAdversarialTest::discoveryDispatcher, null, null);

		String pretty =
			"{\n" +
			"\t\"jsonrpc\": \"2.0\",\n" +
			"\t\"id\": 1,\n" +
			"\t\"method\": \"rpc.discover\"\n" +
			"}";
		int lines = pretty.split("\n", -1).length;
		assertEquals("the pretty form must be exactly five lines for the count below to mean anything",
			5, lines);

		withRawSocket(peer -> {
			// writeLine appends the fifth line's own terminator, so the wire carries five complete lines
			peer.writeLine(pretty);

			List<String> answers = new ArrayList<>(lines);
			for (int i = 0; i < lines; i++) {
				String answer = peer.readLine();
				assertNotNull("fragment " + i + " must be answered, not dropped or hung on", answer);
				answers.add(answer);
			}

			// FR-012 / contract §3: a JSON-level fault on the leading fragment, id unrecoverable
			JsonRpcResponse first = errorResponse(answers.get(0));
			assertEquals(JsonRpcErrors.PARSE_ERROR.code(), first.error().code());
			assertEquals("an unrecoverable id answers as null", JsonRpcId.NULL, first.id());
			for (String answer : answers) {
				assertNotNull("every fragment is a JSON-level fault, never a success: " + answer,
					errorResponse(answer).error());
				assertFalse("no fragment may be answered with a schema — discovery has no exemption from " +
							"the one-document-per-line rule: " + answer,
					answer.contains("openrpc"));
			}

			// the connection survived all five: a JSON error answers, it does not close
			byte[] expected = discoverAnswer(discoveryDispatcher(reactor()), DISCOVER);
			peer.writeLine(DISCOVER);
			String recovered = peer.readLine();
			assertNotNull("the connection must still be usable after five parse errors", recovered);
			assertArrayEquals("the single-line form is served normally on the same connection",
				expected, recovered.getBytes(UTF_8));
			assertEquals("the session survived the pretty-printed request", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// D6: five pipelined discover calls, one for one, in order.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testPipelinedDiscoverCallsAreAnsweredOneForOneInOrder() {
		// Pipelining is pinned generically in A4 with three small documents. What makes it worth repeating
		// for discovery is the ratio: five requests totalling ~240 bytes provoke five answers totalling
		// ~13 kb, all queued on one socket with no wait between them. A transport holding an outbound queue
		// — which this one deliberately does not (TcpSocket coalesces) — is exactly where interleaving or
		// reordering would appear, and it would appear here and nowhere else in the module's tests.
		//
		// Every answer is compared byte-for-byte against the in-process answer for ITS OWN id, so a
		// correctly-ordered set of five answers with two ids swapped fails, and five identical answers to
		// five distinct requests fails.
		JsonRpcDispatcher local = discoveryDispatcher(reactor());
		int count = 5;
		List<byte[]> expected = new ArrayList<>(count);
		StringBuilder pipelined = new StringBuilder();
		for (int id = 1; id <= count; id++) {
			String request = discoverRequest(id);
			expected.add(discoverAnswer(local, request));
			pipelined.append(request).append('\n');
		}
		byte[] expectedAfter = discoverAnswer(local, discoverRequest(99));

		startServer(JsonRpcTcpDiscoveryAdversarialTest::discoveryDispatcher, null, null);

		withRawSocket(peer -> {
			// one write(): the server's decoder sees all five documents in one buffer, with no pacing at all
			peer.write(pipelined.toString());

			for (int i = 0; i < count; i++) {
				String answer = peer.readLine();
				assertNotNull("answer " + (i + 1) + " of " + count + " is missing", answer);
				assertArrayEquals("answer " + (i + 1) + " must be the answer to request " + (i + 1) +
								  ", in that position", expected.get(i), answer.getBytes(UTF_8));
			}

			// strictly 1:1 — the very NEXT line is the answer to a request sent afterwards, so a sixth
			// document produced by the pipelined write would have been read here instead
			peer.writeLine(discoverRequest(99));
			String after = peer.readLine();
			assertNotNull(after);
			assertArrayEquals("the pipelined write produced exactly five documents and no sixth",
				expectedAfter, after.getBytes(UTF_8));
			assertEquals("one session for the whole pipelined exchange", 1, sessionCount());
		});
	}

	// -------------------------------------------------------------------------------------------
	// D7: an abrupt reset while a large discovery response is provably in flight.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAResetWhileALargeDiscoveryResponseIsInFlightTearsTheSessionDownCleanly() throws Exception {
		// The one scenario where "mid-write" has to be an ASSERTED premise rather than a hope. Both ends'
		// socket buffers are pinned small (8 kb send on the server, 4 kb receive on the peer, both of which
		// the kernel may round up but not to anything near 80 kb) and the document is the 80 kb one the
		// deeply-nested fixture generates. The peer reads exactly one byte — proof the server started
		// writing — then asserts that the rest cannot possibly have left the server, then sends a RST
		// (SO_LINGER 0) rather than a graceful FIN.
		//
		// Nothing here asserts a specific exception: what is under test is that the failure is CONTAINED.
		// Four independent witnesses, and it takes all four:
		//   * the session leaves the registry (the deregistration path ran on the write-failure close);
		//   * the recorded failures are connection-level and reached the failure handler, not the loop;
		//   * a fresh connection is still served the complete 80 kb document (the server survived);
		//   * ByteBufRule and tearDown's bounded closeFuture() are the other two halves, and neither can be
		//     written by hand — a discarded write buffer or a session that cannot drain shows up there.
		byte[] expected = discoverAnswer(bulkDispatcher(reactor()), DISCOVER);
		assertTrue("the premise is a document far larger than both socket buffers: " + expected.length,
			expected.length > 64 * 1024);

		startServer(JsonRpcTcpDiscoveryAdversarialTest::bulkDispatcher, null,
			SocketSettings.builder().withSendBufferSize(MemSize.kilobytes(8)).build());

		try (Socket peer = constrainedPeer()) {
			OutputStream out = peer.getOutputStream();
			out.write((DISCOVER + "\n").getBytes(UTF_8));
			out.flush();

			InputStream in = peer.getInputStream();
			assertTrue("the server must have started writing the response", in.read() != -1);
			int buffered = in.available();
			assertTrue("premise: the response must still be in flight — 1 + " + buffered + " of " +
					   expected.length + " bytes have arrived", 1 + buffered < expected.length);

			// RST, not FIN: the write in progress is torn down under the server rather than drained
			peer.setSoLinger(true, 0);
		}

		awaitSessionsEmpty();

		for (Exception failure : failures) {
			assertThat("a reset peer must produce a connection-level failure routed to the failure " +
					   "handler, never an unexpected one: " + failure, failure,
				anyOf(instanceOf(IOException.class), instanceOf(AsyncCloseException.class)));
		}

		// the server survived: a fresh connection is served the WHOLE document, not a prefix
		try (JsonRpcTcpRawSocket fresh = JsonRpcTcpRawSocket.connect(port)) {
			fresh.writeLine(DISCOVER);
			String answer = fresh.readLine();
			assertNotNull("the server must still answer after a reset peer", answer);
			assertArrayEquals("...and answer completely", expected, answer.getBytes(UTF_8));
		}
	}

	// -------------------------------------------------------------------------------------------
	// The services. Two of them, for two different kinds of largeness.
	// -------------------------------------------------------------------------------------------

	/**
	 * A service whose <b>surface</b> is wide: four methods, several parameters each, and one record reused
	 * as parameter and result — the ordinary shape whose OpenRPC document is already ~2.6 kb, forty-five
	 * times the length of the request that asks for it.
	 */
	@JsonRpcService("discovery")
	public interface DiscoveryApi {
		@JsonRpcMethod("lookup")
		Promise<Profile> lookup(@JsonRpcParam("id") String id, @JsonRpcParam("fields") List<String> fields);

		@JsonRpcMethod("search")
		Promise<List<Profile>> search(
			@JsonRpcParam("query") String query, @JsonRpcParam("limit") int limit,
			@JsonRpcParam("filters") Map<String, String> filters);

		@JsonRpcMethod("upsert")
		Promise<Profile> upsert(@JsonRpcParam("profile") Profile profile);

		@JsonRpcNotification("touch")
		void touch(@JsonRpcParam("id") String id);

		record Profile(
			String id, String displayName, String email, int age, boolean active, List<String> roles,
			Map<String, String> attributes
		) {}
	}

	/** The four wire names {@link DiscoveryApi} publishes — what a surviving document must still describe. */
	private static final List<String> DISCOVERY_WIRE_NAMES =
		List.of("discovery.lookup", "discovery.search", "discovery.touch", "discovery.upsert");

	/**
	 * A service whose <b>types</b> are deep: one method over a three-level nested record, which
	 * {@code JsonSchemaMapping} expands inline (there is no {@code $ref} in the pinned subset), producing an
	 * ~80 kb document from a single method. It exists only for D7, where the response has to be larger than
	 * anything two constrained socket buffers can hold.
	 */
	@JsonRpcService("bulk")
	public interface BulkApi {
		@JsonRpcMethod("crunch")
		Promise<Bulk> crunch(@JsonRpcParam("input") Bulk input);

		record Scalars(
			String s1, String s2, String s3, String s4, String s5, String s6,
			int i1, int i2, int i3, long l1, double d1, boolean b1
		) {}

		record Group(
			Scalars g0, Scalars g1, Scalars g2, Scalars g3, Scalars g4,
			Scalars g5, Scalars g6, Scalars g7, Scalars g8, Scalars g9
		) {}

		record Bulk(
			Group b0, Group b1, Group b2, Group b3, Group b4,
			Group b5, Group b6, Group b7, Group b8, Group b9
		) {}
	}

	/**
	 * Both services' implementation. Nothing here is ever invoked: {@code rpc.discover} is answered from the
	 * array computed at {@code build()}, and no test calls a user method. It exists because a dispatcher
	 * registration needs an instance, and because a contract validated against a real implementing class is
	 * the same contract a deployment validates.
	 */
	public static final class Services implements DiscoveryApi, BulkApi {
		@Override
		public Promise<Profile> lookup(String id, List<String> fields) {
			return Promise.of(new Profile(id, "", "", 0, false, List.of(), Map.of()));
		}

		@Override
		public Promise<List<Profile>> search(String query, int limit, Map<String, String> filters) {
			return Promise.of(List.of());
		}

		@Override
		public Promise<Profile> upsert(Profile profile) {
			return Promise.of(profile);
		}

		@Override
		public void touch(String id) {}

		@Override
		public Promise<Bulk> crunch(Bulk input) {
			return Promise.of(input);
		}
	}

	private static JsonRpcDispatcher discoveryDispatcher(Reactor reactor) {
		return JsonRpcDispatcher.builder(reactor)
			.withService(DiscoveryApi.class, new Services())
			.withDiscovery(INFO)
			.build();
	}

	private static JsonRpcDispatcher bulkDispatcher(Reactor reactor) {
		return JsonRpcDispatcher.builder(reactor)
			.withService(BulkApi.class, new Services())
			.withDiscovery(INFO)
			.build();
	}

	/** The request document for one {@code rpc.discover} call, written by hand as a peer would send it. */
	private static String discoverRequest(long id) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"rpc.discover\"}";
	}

	/**
	 * What the dispatcher answers {@code request} with, in process — the yardstick every wire assertion in
	 * this class measures against. The generator is deterministic and the answer is a pre-encoded array, so
	 * a dispatcher built here and one built on the server's own loop produce the same bytes by construction.
	 */
	private static byte[] discoverAnswer(JsonRpcDispatcher dispatcher, String request) {
		return await(dispatcher.dispatch(request.getBytes(UTF_8)));
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: the server on its own loop, driven by a blocking raw socket from the JUnit thread.
	// -------------------------------------------------------------------------------------------

	/**
	 * Starts one {@link JsonRpcTcpServer} on a dedicated {@link EventloopThread}, bound to port {@code 0}
	 * and asked where it landed (ADR-028). The JUnit thread stays free to block on a socket, which a loop
	 * running on it could not serve. Every server records its per-session failures rather than logging
	 * them, so D7 can assert that a reset peer's failure was contained.
	 */
	private void startServer(
		Function<NioReactor, JsonRpcDispatcher> dispatcherFactory, @Nullable MemSize maxMessageSize,
		@Nullable SocketSettings socketSettings
	) {
		EventloopThread loop = EventloopThread.create("jsonrpc-tcp-discovery-adversarial-test");
		this.loop = loop;
		try {
			loop.submit(() -> {
				JsonRpcTcpServer.Builder builder =
					JsonRpcTcpServer.builder(loop.eventloop(), dispatcherFactory.apply(loop.eventloop()))
						.withListenPort(0)
						.withFailureHandler(failures::add);
				if (maxMessageSize != null) builder.withMaxMessageSize(maxMessageSize);
				if (socketSettings != null) builder.withSocketSettings(socketSettings);
				JsonRpcTcpServer server = builder.build();
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

	/**
	 * A blocking peer whose <b>receive</b> buffer is pinned small, connected to the running server. Paired
	 * with a server whose send buffer is pinned small, it is what makes "the answer is still in flight" an
	 * asserted premise rather than a hope: the kernel may round both figures up, but not to anything near
	 * the 80 kb document the two tests using this send for. The receive size must be set <b>before</b>
	 * {@code connect} to take effect, and setting it explicitly also disables receive-window autotuning.
	 */
	private Socket constrainedPeer() throws IOException {
		Socket peer = new Socket();
		try {
			peer.setReceiveBufferSize(4096);
			peer.setTcpNoDelay(true);
			peer.setSoTimeout(5_000);
			peer.connect(new InetSocketAddress("localhost", port), 5_000);
		} catch (IOException | RuntimeException e) {
			peer.close();
			throw e;
		}
		return peer;
	}

	/** Connects one raw peer to the running server, runs {@code body} against it, and closes it. */
	private void withRawSocket(HostilePeer body) {
		try (JsonRpcTcpRawSocket peer = JsonRpcTcpRawSocket.connect(port)) {
			body.run(peer);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			// a body here also DECODES what it read, so MalformedDataException is reachable: a document
			// that does not decode is a failure of this test's subject, never a checked outcome to handle
			throw new AssertionError("the peer's body failed", e);
		}
	}

	/** A blocking peer's script. Declared broadly because a body both reads bytes and decodes them. */
	@FunctionalInterface
	private interface HostilePeer {
		void run(JsonRpcTcpRawSocket peer) throws Exception;
	}

	private int sessionCount() {
		JsonRpcTcpServer server = this.server;
		EventloopThread loop = this.loop;
		if (server == null || loop == null) return 0;
		return loop.submit(() -> server.sessions().size());
	}

	/**
	 * Waits for the registry to drain after a peer disappeared without warning. A reset is observed by the
	 * server asynchronously — there is no promise on this side to await — so this polls through the loop
	 * rather than sleeping blind, and fails with the count rather than hanging if the drain never happens.
	 */
	private void awaitSessionsEmpty() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			if (sessionCount() == 0) return;
			Thread.sleep(10);
		}
		assertEquals("the reset session must leave the registry", 0, sessionCount());
	}

	// -------------------------------------------------------------------------------------------
	// Fixture: one in-reactor socket pair (JUnit thread), for the two tests that observe a CLIENT
	// transport's internals — how many documents it delivered, and what closed it.
	// -------------------------------------------------------------------------------------------

	/**
	 * One real TCP connection on the JUnit thread's own loop — an {@code acceptOnce} server on port
	 * {@code 0} plus a connected client — with both raw sockets handed to {@code body} and everything closed
	 * when the promise it returns completes. The same shape {@link JsonRpcTcpAdversarialFramingTest} and
	 * {@link JsonRpcTcpHostileTest} use, and the only way to see a document count or a close cause: a
	 * blocking peer sees bytes and an EOF, never either of those.
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

	/** What one dialled {@link JsonRpcTcpTransport} saw: the documents delivered, and what closed it. */
	private record Outcome(List<byte[]> documents, @Nullable Exception closeCause) {}

	/**
	 * Dials a real {@link JsonRpcTcpServer} with a client transport configured to {@code clientTier}, sends
	 * one {@code rpc.discover} and reports what the client saw. The server is {@code acceptOnce} and closed
	 * after the client, so the chain reaches quiescence whichever way the boundary falls (ADR-040).
	 */
	private static Outcome discoverWithClientTier(MemSize clientTier) {
		NioReactor reactor = reactor();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor, discoveryDispatcher(reactor))
			.withListenPort(0)          // ADR-028: bind :0 and ask where it landed
			.withAcceptOnce()           // ADR-040: the accept socket must not outlive one connection
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		List<byte[]> documents = new ArrayList<>();
		Ref<Exception> closeCause = new Ref<>();

		await(JsonRpcTcpTransport.connect(reactor, server.getBoundAddresses().get(0), null, null, clientTier)
			.then(transport -> {
				// settled by whichever comes first: the document, or the close the size bound provokes
				SettablePromise<Void> settled = new SettablePromise<>();
				transport.setListener(listener(
					document -> {
						documents.add(document);
						settled.trySet(null);
					},
					e -> {
						closeCause.set(e);
						settled.trySet(null);
					}));
				return transport.send(DISCOVER.getBytes(UTF_8))
					.then(() -> settled)
					.whenComplete(($, e) -> transport.close())
					.toVoid();
			}));

		await(server.close().toVoid());
		return new Outcome(documents, closeCause.get());
	}

	// -------------------------------------------------------------------------------------------
	// Small shared helpers.
	// -------------------------------------------------------------------------------------------

	/** Writes {@code frame[from, to)} without a terminator of its own — the pieces carry their own. */
	private static Promise<Void> write(ITcpSocket socket, byte[] frame, int from, int to) {
		// a wrapped (non-pooled) ByteBuf has no refs, so the socket's recycle is a no-op and the shared
		// frame array is never cleared or pooled — the sanctioned way to write a constant
		return socket.write(ByteBuf.wrap(frame, from, to));
	}

	/** {@code document} plus the one framing byte of this wire contract. */
	private static byte[] terminated(byte[] document) {
		byte[] frame = new byte[document.length + 1];
		System.arraycopy(document, 0, frame, 0, document.length);
		frame[document.length] = LF;
		return frame;
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

	/** Decodes an answer as a successful response carrying an OpenRPC document under {@code expectedId}. */
	private static OpenRpcDocument openRpcDocumentOf(byte[] answer, long expectedId)
		throws MalformedDataException {
		JsonRpcInput input = JsonRpcDecoder.decode(answer);
		assertThat("expected a single response document", input, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertFalse("rpc.discover answers with a result, never an error", response.isError());
		assertEquals("the answer carries the request's own id", new JsonRpcId.Num(expectedId), response.id());
		return response.result().decode(OpenRpcDocument.CODEC);
	}

	private static JsonRpcResponse errorResponse(String document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertThat("expected a single response document: " + document, input,
			instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) input;
		assertNotNull("expected an error response: " + document, response.error());
		return response;
	}

	private static List<String> names(OpenRpcDocument document) {
		return document.methods().stream().map(OpenRpcMethod::name).toList();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
