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

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocketClient;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.schema.OpenRpcDocument;
import io.activej.jsonrpc.schema.OpenRpcInfo;
import io.activej.jsonrpc.schema.OpenRpcMethod;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WideApi;
import io.activej.jsonrpc.transport.ws.fixtures.WideApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.SocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.http.IWebSocket.Frame;
import static io.activej.http.IWebSocket.Message;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code rpc.discover} (feature 018) under <b>hostile transport conditions</b>. The feature's headline
 * claim about this module is that it made <b>zero main-source changes</b> here — discovery rides the
 * WebSocket transport for free because a discovery answer is just another document. This class is the
 * adversarial audit of that claim: {@link JsonRpcWsDiscoveryTest} proved the happy path (the document
 * decodes, and its bytes off the wire are the dispatcher's own), so nothing here repeats it.
 *
 * <h2>Why discovery deserves its own hostile suite at all</h2>
 * Every size, framing and correlation mechanic in this module was tuned against <b>small</b>
 * documents: this module's other tests carry forty-byte requests and sixty-byte answers, and its two
 * size-tier tests use synthetic megabyte filler that is not a document at all. {@code rpc.discover}
 * breaks both habits at once. Its answer is
 * <ul>
 *     <li><b>the largest document a deployment ever emits</b> — one OpenRPC Method Object per wire
 *     name, one Content Descriptor per parameter, one JSON Schema per type;</li>
 *     <li><b>a function of the deployment, not of the call</b> — it grows every time a service is
 *     registered, silently, with no per-call input to blame; and</li>
 *     <li><b>unbounded by anything in the JSON-RPC line</b> — {@code JsonRpcLimits.MAX_BODY_SIZE}
 *     bounds what is <i>received</i>, never what a dispatcher generates.</li>
 * </ul>
 * So the interesting questions are the ones a small document can never ask: does heavy fragmentation
 * of the <i>request</i> still reassemble; is the <i>answer</i> one frame or many; what happens when
 * the answer outgrows the receiver's transport tier; and does a peer that dies mid-answer leave
 * anything behind. {@link WideApi} exists for this class: twenty-four wire names, so the document is
 * kilobytes rather than hundreds of bytes.
 *
 * <h2>Every bound here is derived, never hard-coded</h2>
 * The document grows whenever {@link WideApi} or the mapping rules change, so a literal byte count
 * written into a test would rot into a vacuous assertion. Each size-tier test asks the dispatcher for
 * the exact answer it is about to provoke ({@code await(dispatcher.dispatch(request))}) and derives
 * its cap from that — {@code length} and {@code length - 1}, the two sides of the strict {@code >}
 * that core-http applies. That pins the boundary itself rather than one arbitrary point near it.
 *
 * <h2>Quiescence (R3)</h2>
 * {@code TestUtils.await} runs the eventloop until the selector holds no keys, so every server is
 * {@code acceptOnce} (via {@link WsPair}) and every connection reaches closure inside the awaited
 * chain; {@link WsPair#closeAll()} is the belt to those braces. Frame-level writes appear here for the
 * same reason as in {@link JsonRpcWsFragmentationTest}: they are the only way to produce a fragmented
 * message, and FR-011 confines them to tests.
 *
 * <h2>Result: the module's claim holds — and the sweep found a leak one layer down</h2>
 * All five scenarios pass against this module's shipped {@code src/main}, which is byte-for-byte
 * feature 015's; nothing here needed a transport change. Three of them are characterization tests of
 * intentional behaviour rather than defect hunts, and say so at their own site: the answer is always
 * <b>one</b> frame; the transport tier refuses an oversized answer with {@code 1009} and there is no
 * negotiation, no truncation and no {@code -32001}; and a write failure to a vanished peer is reported
 * once per undeliverable answer through the servlet's {@code failureHandler}.
 * <p>
 * Scenario 5 did find a real defect, one layer below this module: closing a client-side WebSocket
 * while inbound messages are still buffered never ran {@code HttpClientConnection#onClosed()}, leaking
 * the pooled buffer the {@code 101} response head was parsed out of. Fixed in {@code core-http}
 * (see {@code WebSocketClientCloseWithBufferedInputTest} and the CHANGELOG); this class's
 * {@link ByteBufRule} is what caught it, and would catch a regression again.
 */
public final class JsonRpcWsDiscoveryAdversarialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Application-supplied, never derived (FR-013). */
	private static final OpenRpcInfo INFO = new OpenRpcInfo("Adversarial Discovery API", "0.0.1-hostile");

	/** The one request of this feature, written as the raw document an integrator would send. */
	private static final byte[] DISCOVER = discoverRequest("1");

	// ---------------------------------------------------------------------------------------------------
	// Scenario 1 — fragmentation, in both directions.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void discoveryRequestSplitOneBytePerFrameIsReassembledAndAnsweredInFull() {
		// The inbound half of scenario 1, at the most hostile fragmentation this API can express: the
		// 48-byte discover request written as 48 frames of one byte each — one TEXT frame with FIN
		// clear, forty-six CONTINUATIONs, and a final CONTINUATION with FIN set.
		//
		// JsonRpcWsFragmentationTest already proves that TWO frames rejoin. What this adds is that the
		// rejoin holds for a request whose ANSWER is the biggest document the deployment has, i.e. that
		// nothing about discovery is coupled to how its request arrived. The transport never sees a
		// frame (FR-011 keeps it on readMessage), so the claim under test is really "the servlet's
		// dispatch is driven by the reassembled message and by nothing else" — and the oracle is
		// byte-equality with the same dispatcher answering the same request in process.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		byte[] expected = await(dispatcher.dispatch(DISCOVER));
		WsPair pair = WsPair.serverUpgrade(reactor(), JsonRpcWsServlet.builder(reactor(), dispatcher).build());
		Ref<byte[]> answer = new Ref<>();

		await(pair.connect().then(ws -> writeOneBytePerFrame(ws, DISCOVER)
			.then(ws::readMessage)
			.whenResult(message -> answer.set(message.getText().getBytes(UTF_8)))
			.whenComplete(() -> ws.closeEx(new ExpectedException("end of test")))
			.toVoid()));

		assertNotNull("the fragmented request produced no answer at all", answer.get());
		assertArrayEquals("48 one-byte frames rejoin into the same request, answered identically",
			expected, answer.get());
		pair.closeAll();
	}

	@Test
	public void theWholeDiscoveryAnswerRidesExactlyOneOutboundFrameHoweverLargeItIs() {
		// CHARACTERIZATION — the outbound half of scenario 1, and the answer to "can core-http split a
		// large discovery response across several WS frames?" It cannot, and that is by construction
		// rather than by luck: WebSocket.writeMessage(TEXT) hands the encoder exactly one
		// Frame.text(...), and WebSocketFramesToBufs turns one Frame into one wire frame with no size
		// threshold anywhere. So the answer is ALWAYS unfragmented, however large the service surface
		// grows.
		//
		// This is worth pinning rather than assuming, because a consequence rides on it: the receiver's
		// transport tier is therefore checked against the whole document AT ONCE, in the frame header
		// (WebSocketBufsToFrames.processMask compares the DECLARED length), not incrementally across
		// fragments. That is what makes the next test's 1009 fire before a single payload byte is
		// buffered — and it is why an oversized discovery answer can never arrive truncated.
		//
		// The frame-level read API is used only as the observation instrument; nothing in src/main ever
		// calls it (FR-011).
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		byte[] expected = await(dispatcher.dispatch(DISCOVER));
		assertTrue("the fixture must make the answer genuinely large, got " + expected.length + " bytes",
			expected.length > 4096);
		WsPair pair = WsPair.serverUpgrade(reactor(), JsonRpcWsServlet.builder(reactor(), dispatcher).build());
		List<byte[]> frames = new ArrayList<>();
		Ref<Frame.FrameType> firstFrameType = new Ref<>();

		await(pair.connect().then(ws -> ws.writeMessage(Message.text(new String(DISCOVER, UTF_8)))
			.then(() -> readOneMessageFrameByFrame(ws, frames, firstFrameType))
			.whenComplete(() -> ws.closeEx(new ExpectedException("end of test")))
			.toVoid()));

		assertEquals("a " + expected.length + "-byte discovery answer is one frame, not several",
			1, frames.size());
		assertEquals("and that frame is TEXT", Frame.FrameType.TEXT, firstFrameType.get());
		assertArrayEquals("carrying the whole document", expected, frames.get(0));
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Scenario 2 — the discovery document outgrows the RECEIVER's transport tier.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aDiscoveryAnswerOneByteOverTheReceiversTransportTierCloses1009AndDeliversNothing() {
		// CHARACTERIZATION of a real gap in this module's coverage. JsonRpcWsOversizeTest proves the
		// 1009 refusal for an oversize message a hostile peer SENDS; nothing proved it for a message
		// this stack itself GENERATES. Discovery is the only document whose size the sender does not
		// choose per call — it is a property of the deployment — so this is the one realistic way for a
		// well-behaved deployment to exceed a well-behaved peer's cap, simply by registering one service
		// too many.
		//
		// The verdict is deliberately harsh and must stay so: the connection dies with 1009. There is no
		// truncated document, no partial OpenRPC, no renegotiation, and — because the transport tier
		// fires first — no -32001 either (extra/cloud-jsonrpc-ws/CLAUDE.md, "Two size tiers"). A client
		// cannot ask for a smaller schema; the operator raises maxWebSocketMessageSize or does not call
		// rpc.discover. Pinning that here means a future "helpfully truncate the schema" change fails a
		// test that explains why it must not.
		//
		// The cap is answer.length - 1, one byte under the exact document the server is about to send:
		// the sharpest possible statement of the bound, and one that cannot rot as WideApi grows.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		byte[] expected = await(dispatcher.dispatch(DISCOVER));
		WsPair pair = WsPair.serverUpgrade(reactor(), cappedClient(expected.length - 1),
			JsonRpcWsServlet.builder(reactor(), dispatcher).build());
		Ref<Exception> closed = new Ref<>();

		await(pair.connect().then(ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			SettablePromise<Exception> closePromise = new SettablePromise<>();
			Promise<Exception> awaited = closePromise;
			transport.setListener(listener(
				document -> fail("a document was delivered from an answer that exceeds the cap: " +
								 document.length + " bytes"),
				e -> {
					closed.set(e);
					closePromise.trySet(e);
				}));
			return transport.send(DISCOVER).then(() -> awaited).toVoid();
		}));

		assertThat("the receiving side refuses the oversized answer at the framing layer",
			closed.get(), instanceOf(WebSocketException.class));
		assertEquals("close 1009 — message too big", Integer.valueOf(1009),
			((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void aDiscoveryAnswerExactlyAtTheReceiversTransportTierArrivesIntact() throws MalformedDataException {
		// The complementary edge, and the proof that the previous test's 1009 was caused by the cap and
		// not by anything incidental to a large document. core-http's bound is a strict `>`, so a cap
		// equal to the answer's exact length accepts it — and the document arrives byte-identical, all
		// several kilobytes of it, through a connection whose margin is exactly zero. It is decoded as
		// well as compared: a document that survived at zero margin must still be a whole OpenRPC
		// document, not merely the right number of bytes.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		byte[] expected = await(dispatcher.dispatch(DISCOVER));
		WsPair pair = WsPair.serverUpgrade(reactor(), cappedClient(expected.length),
			JsonRpcWsServlet.builder(reactor(), dispatcher).build());
		Ref<byte[]> answer = new Ref<>();

		await(pair.connect().then(ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			SettablePromise<byte[]> first = new SettablePromise<>();
			Promise<byte[]> awaited = first;
			transport.setListener(listener(
				first::trySet,
				e -> first.trySetException(e != null ? e : new ExpectedException("closed before the answer"))));
			return transport.send(DISCOVER)
				.then(() -> awaited)
				.whenResult(answer::set)
				.whenComplete(() -> transport.closeEx(new ExpectedException("end of test")))
				.toVoid();
		}));

		assertArrayEquals("a cap equal to the answer's length accepts it — the bound is a strict `>`",
			expected, answer.get());
		JsonRpcResponse response = (JsonRpcResponse) JsonRpcDecoder.decode(answer.get());
		OpenRpcDocument document = response.result().decode(OpenRpcDocument.CODEC);
		assertEquals("the info is the one the application supplied", INFO, document.info());
		assertTrue("every registered wire name is described",
			document.methods().stream().map(OpenRpcMethod::name).toList().containsAll(
				List.of("test.add", "wide.alpha", "wide.xray")));
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Scenario 3 — rpc.discover offered on the wrong frame type.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void rpcDiscoverSentAsABinaryMessageIsRefusedWith1003AndNeverAnswered() {
		// The module rule is "BINARY ⇒ recycle the payload, close 1003", stated with no exception for
		// any wire name. Feature 018 introduced the first name this stack answers WITHOUT a registered
		// service behind it — a dispatcher-owned entry, reachable on a dispatcher whose service table
		// may be empty — so the question worth asking is whether that new entry created a path that
		// reaches the dispatcher before the frame-type check. It did not, and could not: the refusal
		// lives in JsonRpcWsTransport.doRead, upstream of every wire name, and no discovery code exists
		// in this module to special-case.
		//
		// The oracle is the pair of facts together: the peer sees 1003, AND no document ever comes back.
		// A stack that had grown a discovery shortcut would answer the binary frame instead of refusing
		// it, and only the second half of that pair would catch it.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), dispatcher).build();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);
		Ref<Exception> readFailure = new Ref<>();
		Ref<String> delivered = new Ref<>();

		// DISCOVER.clone() is load-bearing, not defensive habit: core-http's WebSocketFramesToBufs masks
		// an outbound frame's payload IN PLACE, so handing it a ByteBuf wrapping a shared array leaves
		// that array XOR-scrambled for every later reader. (JsonRpcWsTransport.send is immune by
		// construction — it converts the document to a String before the encoder ever sees it, which is
		// exactly what makes the SPI's "your array is not mutated" promise true for TEXT. This BINARY
		// path is the one place a test can reach the raw encoder, so it is the one place that must copy.)
		await(pair.connect().then(ws -> ws.writeMessage(Message.binary(ByteBuf.wrapForReading(DISCOVER.clone())))
			// the server cuts the connection on the refusal, so the write itself may fail — tolerated
			.then(($, e) -> Promise.complete())
			.then(() -> ws.readMessage().whenComplete((message, e) -> {
				readFailure.set(e);
				if (message != null) {
					if (message.getType() == Message.MessageType.TEXT) delivered.set(message.getText());
					message.recycle();
				}
			}))
			.then(($, e) -> Promise.complete())
			.whenComplete(() -> ws.closeEx(new ExpectedException("end of test")))));

		assertNull("rpc.discover on a BINARY frame must never be answered", delivered.get());
		assertThat("the refusal reaches the peer as a close, not as a document",
			readFailure.get(), instanceOf(WebSocketException.class));
		assertEquals("close 1003 — data the endpoint cannot accept", Integer.valueOf(1003),
			((WebSocketException) readFailure.get()).getCode());
		pair.closeAll();
		assertTrue("the refusal deregistered the session, leaving nothing behind", servlet.sessions().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------
	// Scenario 4 — many discover calls pipelined on one connection.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void discoverRequestsPipelinedOnOneConnectionAreCorrelatedByIdWithoutCrossTalk() {
		// Sixteen rpc.discover requests handed to the transport in ONE reactor turn — no await between
		// them, so all sixteen are queued before any I/O runs and the server reads them back to back.
		// Because each answer is kilobytes, this is also the only test in the module that pushes ~100 kB
		// of documents through one connection, which is what actually exercises reassembly across many
		// socket reads (a single 6-kilobyte answer fits one loopback read and proves nothing about it).
		//
		// The correlation trap is deliberate: the ids are the pairs 1/"1", 2/"2" … 8/"8" — a JSON-RPC id
		// is a number OR a string and §4 keeps them distinct, so a correlation that stringified the id
		// would happily answer Num(3) with Str("3")'s response and every other assertion here would
		// still pass. The oracle is per-request byte-equality with the same dispatcher answering the
		// same request in process, so a swapped pair fails on the id embedded in the answer.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		List<byte[]> requests = new ArrayList<>();
		List<byte[]> expected = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			requests.add(discoverRequest(String.valueOf(i)));          // id as a NUMBER
			requests.add(discoverRequest("\"" + i + "\""));            // id as the STRING of that number
		}
		for (byte[] request : requests) {
			expected.add(await(dispatcher.dispatch(request)));
		}
		WsPair pair = WsPair.serverUpgrade(reactor(), JsonRpcWsServlet.builder(reactor(), dispatcher).build());
		List<byte[]> received = new ArrayList<>();

		await(pair.connect().then(ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			SettablePromise<Void> allAnswered = new SettablePromise<>();
			Promise<Void> awaited = allAnswered;
			transport.setListener(listener(
				document -> {
					received.add(document);
					if (received.size() == requests.size()) allAnswered.trySet(null);
				},
				e -> allAnswered.trySetException(e != null ? e : new ExpectedException("closed early"))));
			// every send issued in this one turn: the transport's write queue is what serialises them,
			// and nothing here awaits an answer before issuing the next request
			for (byte[] request : requests) {
				transport.send(request);
			}
			return awaited.whenComplete(() -> transport.closeEx(new ExpectedException("end of test")));
		}));

		assertEquals("one answer per request, no more and no fewer", requests.size(), received.size());
		List<JsonRpcId> ids = new ArrayList<>();
		for (int i = 0; i < received.size(); i++) {
			// arrival order == request order: the dispatcher answers rpc.discover synchronously from a
			// precomputed array and the transport's write queue is FIFO. Pinned as characterization —
			// JSON-RPC does not require it, so a future asynchronous discovery would surface right here
			// rather than as a puzzling failure in someone's client
			assertArrayEquals("answer " + i + " is byte-identical to the in-process answer for request " + i,
				expected.get(i), received.get(i));
			ids.add(idOf(received.get(i)));
		}
		for (int i = 1; i <= 8; i++) {
			assertEquals("the numeric id was answered as a number", new JsonRpcId.Num(i), ids.get(2 * (i - 1)));
			assertEquals("the string id of the same digits stayed a string",
				new JsonRpcId.Str(String.valueOf(i)), ids.get(2 * (i - 1) + 1));
		}
		assertEquals("every id came back exactly once", requests.size(), Set.copyOf(ids).size());
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Scenario 5 — the peer vanishes while the server is writing discovery answers.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aPeerVanishingMidDiscoveryWriteTearsTheSessionDownCleanlyAndReportsOncePerLostAnswer() {
		// CHARACTERIZATION. Three hundred pipelined rpc.discover requests — around two megabytes of
		// answers — against a client with a 2 kB receive buffer that never reads a byte, then an abrupt
		// close (SO_LINGER 0, so the peer's kernel answers the next segment with RST rather than
		// draining politely). The server is therefore GUARANTEED to be mid-write: it cannot have flushed
		// two megabytes into a two-kilobyte window.
		//
		// This is discovery's own hazard, not a generic one. A normal answer is tens of bytes and is
		// gone into the socket buffer before a peer can plausibly die on top of it; a discovery answer is
		// kilobytes, so it is the document most likely to still be in flight when the peer disappears —
		// and the one most likely to be requested in bulk by a crawling tool that gives up.
		//
		// What is pinned:
		//   1. the session is deregistered — exactly one connection died and the registry is empty;
		//   2. every undeliverable answer is reported ONCE to the servlet's failureHandler, and the
		//      count is bounded by the number of requests. That per-answer fan-out is pre-existing
		//      feature-015 behaviour (JsonRpcClient answers an inbound element with
		//      `transport.send(document).whenException(this::reportFailure)`, and every send already
		//      queued behind the failed one fails through the writeTail's error continuation), not
		//      anything feature 018 introduced — but discovery makes it reachable in bulk, so it is
		//      pinned here rather than left as folklore;
		//   3. nothing leaks. ByteBufRule is the real assertion: two megabytes of kilobyte documents were
		//      encoded, queued and abandoned, and with pooling disabled under Surefire a single retained
		//      buffer fails the class. THIS IS WHAT FOUND THE ONE REAL BUG OF THIS SWEEP — see below.
		//
		// FOUND AND FIXED (core-http, not this module). This scenario failed on first run, leaking exactly
		// one 16 kB pooled buffer. Minimised to: close a CLIENT-side WebSocket while at least two inbound
		// messages are still buffered unread. HttpClientConnection#bindWebSocketTransformers called
		// closeWebSocketConnection from one place only — the closeSent -> closeReceived chain — which
		// never fires when this side closes first, because the peer's CLOSE frame is never read. The CSP
		// cascade still tore the socket down, so the loop went quiescent and nothing looked wrong, but
		// HttpClientConnection#onClosed() never ran and its `stashedBufs` — the pooled buffer the 101
		// response head was parsed out of — was never recycled. The connection is now closed from the same
		// gate that already released the read half. Regression test: core-http's
		// WebSocketClientCloseWithBufferedInputTest. Discovery is what makes this reachable in practice:
		// a normal answer is tens of bytes and is gone before a peer can die on top of it.
		//
		// A failureHandler is installed rather than left at its default on purpose: the default is
		// Reactor.logFatalError, and EventloopRule installs a RETHROWING fatal-error handler, so the
		// default would turn "a peer hung up" into a test-suite crash. That is a deliberate
		// configuration point of JsonRpcWsServlet (withFailureHandler), and this test is what documents
		// that a deployment expecting hostile peers must use it.
		JsonRpcDispatcher dispatcher = wideDiscoveringDispatcher();
		List<Exception> reported = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), dispatcher)
			.withFailureHandler(reported::add)
			.build();
		IWebSocketClient client = HttpClient.builder(reactor(), DnsClient.create(reactor(), inetAddress("8.8.8.8")))
			.withSocketSettings(SocketSettings.builder()
				.withReceiveBufferSize(MemSize.kilobytes(2))   // the server's answers cannot drain
				.withLingerTimeout(Duration.ZERO)              // close ⇒ RST, not a polite FIN
				.build())
			.build();
		WsPair pair = WsPair.serverUpgrade(reactor(), client, servlet);
		int requestCount = 300;
		RefInt sessionsWhileWriting = new RefInt(-1);

		await(pair.connect().then(ws -> {
			Promise<Void> writes = Promise.complete();
			for (int i = 0; i < requestCount; i++) {
				byte[] request = discoverRequest(String.valueOf(i));
				writes = writes.then(() -> ws.writeMessage(Message.text(new String(request, UTF_8))));
			}
			// nothing ever calls readMessage on this socket: the answers pile up in the server's write
			// queue behind a two-kilobyte window
			return writes
				.whenResult(() -> sessionsWhileWriting.set(servlet.sessions().size()))
				.whenComplete(() -> ws.closeEx(new ExpectedException("the peer vanishes mid-answer")));
		}));

		assertEquals("one connection, one session, while the answers were still going out",
			1, sessionsWhileWriting.get());
		// await() ran the loop to quiescence, so the server has finished tearing down by now — and it did
		// so without WsPair.closeAll() forcing anything, which is the point
		assertTrue("the vanished peer's session was deregistered", servlet.sessions().isEmpty());
		assertFalse("the server was provably mid-write: at least one answer could not be delivered",
			reported.isEmpty());
		assertTrue("failures are bounded by the requests that could not be answered, got " + reported.size(),
			reported.size() <= requestCount);
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * A discovery-enabled dispatcher over a deliberately wide service surface — 25 wire names, so the
	 * OpenRPC document is kilobytes. {@link TestApi} is registered beside {@link WideApi} so the
	 * document spans two services, which is what a real deployment looks like.
	 */
	private static JsonRpcDispatcher wideDiscoveringDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.withService(WideApi.class, new WideApiImpl())
			.withDiscovery(INFO)
			.build();
	}

	/** A real client whose inbound transport tier is exactly {@code bytes} (ADR-028 applies to the server, not here). */
	private static IWebSocketClient cappedClient(int bytes) {
		return HttpClient.builder(reactor(), DnsClient.create(reactor(), inetAddress("8.8.8.8")))
			.withMaxWebSocketMessageSize(MemSize.bytes(bytes))
			.build();
	}

	/** {@code {"jsonrpc":"2.0","id":<raw>,"method":"rpc.discover"}} — {@code raw} is spliced in verbatim. */
	private static byte[] discoverRequest(String rawId) {
		return ("{\"jsonrpc\":\"2.0\",\"id\":" + rawId + ",\"method\":\"rpc.discover\"}").getBytes(UTF_8);
	}

	/** Writes {@code document} as one message of {@code document.length} single-byte frames. */
	private static Promise<Void> writeOneBytePerFrame(IWebSocket ws, byte[] document) {
		Promise<Void> chain = Promise.complete();
		for (int i = 0; i < document.length; i++) {
			byte[] one = {document[i]};
			boolean first = i == 0;
			boolean last = i == document.length - 1;
			chain = chain.then(() -> ws.writeFrame(first ?
				Frame.text(ByteBuf.wrapForReading(one), false) :
				Frame.next(ByteBuf.wrapForReading(one), last)));
		}
		return chain;
	}

	/**
	 * Reads one whole message a frame at a time, collecting each frame's payload. {@code asArray()}
	 * copies and recycles in one call, so nothing here owns a buffer past the loop.
	 */
	private static Promise<Void> readOneMessageFrameByFrame(
		IWebSocket ws, List<byte[]> frames, Ref<Frame.FrameType> firstFrameType
	) {
		return ws.readFrame().then(frame -> {
			if (frame == null) return Promise.complete();
			if (frames.isEmpty()) firstFrameType.set(frame.getType());
			frames.add(frame.getPayload().asArray());
			return frame.isLastFrame() ?
				Promise.complete() :
				readOneMessageFrameByFrame(ws, frames, firstFrameType);
		});
	}

	/** The {@code id} of a response document, decoded rather than pattern-matched on the raw bytes. */
	private static JsonRpcId idOf(byte[] document) {
		JsonRpcInput decoded = JsonRpcDecoder.decode(document);
		assertThat(decoded, instanceOf(JsonRpcResponse.class));
		JsonRpcResponse response = (JsonRpcResponse) decoded;
		assertFalse("rpc.discover answers with a result, never an error", response.isError());
		return response.id();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private static JsonRpcTransport.Listener listener(Consumer<byte[]> onDocument, Consumer<@Nullable Exception> onClosed) {
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
}
