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
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.StubWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static io.activej.http.IWebSocket.Frame;
import static io.activej.http.IWebSocket.Message;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The hostile-peer contract (T005): every refusal the wire contract declares — BINARY message ⇒
 * close {@code 1003} with the payload recycled (FR-014, FR-022), empty TEXT ⇒ close {@code 1002}
 * (FR-015), invalid UTF-8 injected at the frame level ⇒ close {@code 1007} (FR-092, validated by
 * {@code readMessage}), and a notification flood that the serial read loop drains without any
 * server-side accumulation (FR-095). The oversize-message refusal (FR-091) lives in
 * {@link JsonRpcWsOversizeTest}: it exercises a core-http connection-cut-mid-read path that leaks a
 * read buffer (see its justification), so it carries the leak-scan opt-out and is kept apart.
 * <p>
 * Two adversarial rows extend that list. <b>A6</b> pins the framing layer BELOW this module: an
 * RFC 6455 §5 violation on a client→server frame is core-http's refusal, not the transport's, and
 * the test proves its {@code 1002} reaches {@code onClosed} intact. It is the one refusal whose
 * trigger {@link IWebSocket}'s frame-level API cannot express — {@code Frame} has no RSV or mask
 * surface, and a frame-order violation is caught by the writer's own {@code checkFrameOrder} under
 * {@code chk=on} — so its bytes, upgrade included, are written raw. <b>G3</b> is the
 * request-bearing counterpart of the notification flood: a thousand calls carrying an {@code id},
 * answered by a real dispatcher, with the session registry still at one and both correlation tables
 * empty afterwards.
 * <p>
 * The BINARY case is the module's only ByteBuf ownership (R8): the payload the transport receives
 * is pooled and must be recycled on the refusal path — {@link ByteBufRule} fails this class if the
 * transport leaks it. Frame-level writes appear here because the tests must inject what the
 * message-level API refuses to produce (FR-011 confines frames to tests).
 */
public final class JsonRpcWsHostileTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testBinaryMessageRejectedWith1003AndPayloadRecycled() {
		// FR-014/FR-022 (D5): a BINARY message is refused with close 1003 (RFC 6455 §7.4.1 — data the
		// endpoint cannot accept). The payload ByteBuf the transport receives is pooled and owned by
		// it; the transport recycles it on this refusal path — ByteBufRule proves the recycle.
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from a BINARY message"),
				closed::set));
		});

		await(pair.connect().then(ws -> ws.writeMessage(Message.binary(ByteBuf.wrapForReading(new byte[]{1, 2, 3})))));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1003), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testEmptyTextRejectedWith1002() {
		// FR-015 (D5): an empty TEXT message is a protocol error — a zero-length document is never
		// legal in this stack (SPI obligation 3) and silence would hide a broken peer.
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from an empty TEXT message"),
				closed::set));
		});

		await(pair.connect().then(ws -> ws.writeMessage(Message.text(""))));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1002), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testInvalidUtf8RejectedWith1007() {
		// FR-092: readMessage() is what validates UTF-8 (RFC 6455 §8.1), so the invalid bytes have to
		// be injected at the frame level — writeMessage would encode the String itself. The server's
		// message-level read fails with 1007, which reaches onClosed.
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from invalid UTF-8"),
				closed::set));
		});

		byte[] invalidUtf8 = new byte[]{(byte) 0xC3, 0x28};          // not a valid UTF-8 sequence
		await(pair.connect().then(ws -> ws.writeFrame(Frame.text(ByteBuf.wrapForReading(invalidUtf8)))));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1007), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testNotificationFloodDeliveredWithoutGrowth() {
		// FR-095: the serial read loop holds exactly one message between the socket and the listener —
		// there is no server-side queue an attacker could grow. A flood of notifications is delivered
		// one by one, in order, and the connection stays healthy (the clean close still lands).
		int floodSize = 1000;
		List<byte[]> received = new ArrayList<>();
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			serverTransport.set(transport);
			transport.setListener(listener(doc -> {
				received.add(doc);
				// closing on the last delivery, not on the last send's promise: a send completes when
				// written, and a closeEx racing the remaining reads would discard them
				if (received.size() == floodSize) {
					clientTransport.get().closeEx(new AsyncCloseException());
					transport.closeEx(new AsyncCloseException());
				}
			}, closed::set));
		});

		byte[] document = "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}".getBytes(UTF_8);
		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			clientTransport.get().setListener(listener($ -> {}, e -> {}));
			Promise<Void> sends = Promise.complete();
			for (int i = 0; i < floodSize; i++) {
				sends = sends.then($ -> clientTransport.get().send(document));
			}
			return sends;
		}));

		assertEquals(floodSize, received.size());
		for (byte[] receivedDocument : received) {
			assertArrayEquals(document, receivedDocument);
		}
		pair.closeAll();
	}

	@Test
	public void testBinaryRefusalRacingReentrantCloseRecyclesOnceAndSignalsOnce() {
		// A1, adversarial plan: the BINARY refusal (FR-014/FR-022) racing a LOCAL close — the
		// application reacts to the very close the refusal fires by closing again, reentrantly, from
		// inside its own onClosed. doRead()'s recycle-before-signal ordering must survive that
		// caller: the payload is recycled exactly once and the reentrant closeEx is a no-op, so
		// onClosed fires exactly once with the 1003 (obligation 6). The payload is a POOLED buf, not
		// ByteBuf.wrapForReading — only a pooled buf is visible to ByteBufPool's accounting, so this
		// is what makes ByteBufRule's proof of the recycle real rather than vacuous (a double
		// recycle would offer it to the pool twice and fail the rule just as loudly as a leak).
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener(
			$ -> fail("no document may be delivered from a BINARY message"),
			e -> {
				closed.set(e);
				onClosedCount.inc();
				transport.closeEx(new AsyncCloseException("reentrant"));   // closing in reaction to the close
			}));

		ByteBuf payload = ByteBufPool.allocate(3);
		payload.put(new byte[]{1, 2, 3});
		webSocket.deliverMessage(Message.binary(payload));                 // drives the refusal synchronously

		assertEquals(1, onClosedCount.get());                              // exactly once, not twice
		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1003), ((WebSocketException) closed.get()).getCode());
	}

	@Test
	public void testEmptyTextMidStreamRejectedWith1002AfterValidDocumentDelivered() {
		// A2, adversarial plan: the empty-TEXT refusal must hold MID-STREAM, not only when the empty
		// message arrives first. Documents that preceded it are delivered, the refusal closes with
		// 1002, and nothing is ever delivered after it (FR-015 + obligation 6) — the listener fails
		// the test loudly on any second delivery rather than letting one slip past a size check.
		byte[] document = "{\"jsonrpc\":\"2.0\",\"method\":\"notify\"}".getBytes(UTF_8);
		List<byte[]> received = new ArrayList<>();
		RefInt deliveredBeforeClose = new RefInt(-1);
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				doc -> {
					if (!received.isEmpty()) fail("no document may be delivered after the empty TEXT refusal");
					received.add(doc);
				},
				e -> {
					closed.set(e);
					deliveredBeforeClose.set(received.size());
				}));
		});

		await(pair.connect().then(ws -> ws.writeMessage(Message.text(new String(document, UTF_8)))
			// the server cuts the connection on the refusal, so the second write may fail — tolerated
			.then($ -> ws.writeMessage(Message.text("")).then(($2, e) -> Promise.complete()))));

		assertEquals(1, received.size());
		assertArrayEquals(document, received.get(0));
		assertEquals(1, deliveredBeforeClose.get());          // delivered BEFORE the close, never after
		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1002), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testMessageAtExactMaxSizeAcceptedAndConnectionStaysHealthy() {
		// A4, adversarial plan: the transport tier's bound is applied as a STRICT `>` during
		// accumulation, so a message at the exact cap is accepted — the complementary edge of
		// JsonRpcWsOversizeTest's 1 200 000-byte refusal (1009). The boundary is pinned by
		// observation, not assumed: 1 048 576 bytes (HttpServer.maxWebSocketMessageSize's 1 mb
		// default) is empirically the largest accepted message — verified by this test passing while
		// its oversize sibling fails one byte class above. A second, small document afterwards proves
		// the connection stayed healthy rather than the big one squeaking past a lagging close.
		byte[] big = new byte[1_048_576];                     // pinned: exactly at the cap, accepted
		Arrays.fill(big, (byte) 'a');                         // raw filler — the transport never decodes JSON
		byte[] small = "{\"jsonrpc\":\"2.0\",\"method\":\"after\"}".getBytes(UTF_8);
		List<byte[]> received = new ArrayList<>();
		Ref<IWebSocket> clientSocket = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(doc -> {
				received.add(doc);
				// closing on the last delivery, not on the last send's promise: a send completes when
				// written, and a close racing the remaining read would discard it
				if (received.size() == 2) {
					clientSocket.get().closeEx(new AsyncCloseException());
					transport.closeEx(new AsyncCloseException());
				}
			}, e -> {}));
		});

		await(pair.connect().then(ws -> {
			clientSocket.set(ws);
			return ws.writeMessage(Message.text(new String(big, UTF_8)))
				.then($ -> ws.writeMessage(Message.text(new String(small, UTF_8))));
		}));

		assertEquals(2, received.size());
		assertArrayEquals(big, received.get(0));              // one complete document, not truncated
		assertArrayEquals(small, received.get(1));            // and the connection still carried the next one
		pair.closeAll();
	}

	@Test
	public void testMalformedFrameRejectedByCoreHttpFramingWith1002() {
		// A6, adversarial plan: an RFC 6455 §5 framing violation on a client→server frame — a reserved
		// bit (RSV1) set on an otherwise well-formed FIN/TEXT frame — is refused by core-http's OWN
		// framing layer (WebSocketBufsToFrames.processOpCode, RESERVED_BITS_SET) with close 1002, and
		// that refusal must surface through this module as a WebSocketException on onClosed. The
		// transport does nothing special here: FR-011 keeps it on the message-level API, so it never
		// sees a frame at all. What this test pins is the SURFACING — that core-http's §5 verdict
		// reaches JsonRpcTransport.Listener.onClosed with the code intact, rather than being assumed.
		//
		// Why raw bytes rather than IWebSocket.Frame: the frame-level API cannot express this violation,
		// and neither can it express the nearest fallback.
		//   (a) Frame carries exactly (FrameType, payload, isLastFrame) — there is no RSV surface and no
		//       mask surface at all; masking is applied transparently by WebSocketFramesToBufs, so a
		//       test cannot omit it.
		//   (b) An unexpected CONTINUATION (no preceding non-final TEXT/BINARY frame) — core-http's
		//       UNEXPECTED_CONTINUATION, also 1002 — is refused by the CLIENT's own writer before a byte
		//       reaches the wire: WebSocketFramesToBufs.checkFrameOrder runs under `chk=on` (which
		//       Surefire sets for this repo) and throws IllegalStateException straight out of
		//       writeFrame(). Verified, not assumed.
		// So the only way to hand core-http a malformed frame from a test is to write the bytes: a
		// hand-made RFC 6455 upgrade over a raw TcpSocket, then one frame whose first byte is 0xC1
		// (FIN | RSV1 | TEXT). Note the sibling violation A6 also names — a client frame missing its
		// mask, 0x81 0x02 … — is deliberately NOT asserted here: core-http answers it with the correct
		// close (1002 "Message should be masked") but WebSocketBufsToFrames.processLength has no
		// `return` after onProtocolError(MASK_REQUIRED) and runs on into processPayload against a
		// nulled `bufs`, throwing NullPointerException into the reactor's fatal-error handler. That is a
		// core-http defect, reported rather than papered over, and out of this module's scope. FOLLOW-UP:
		// that missing `return` — and its twin after onProtocolError(MASK_SHOULD_NOT_BE_PRESENT) — has
		// since been fixed in core-http. The oracle below is unaffected either way: the RSV path this test
		// exercises (processOpCode) always returned correctly, and the mask case still belongs to
		// core-http's own suite, not to this module's.
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from a malformed frame"),
				closed::set));
		});

		// The full RFC 6455 frame, not just its offending byte: FIN | RSV1 | TEXT, masked, "He". The
		// violation lives in the first byte and the decoder refuses it there (processOpCode's RSV_MASK
		// test is the first thing it does), so the six bytes that follow are still buffered inside the
		// input BinaryChannelSupplier when the process closes. Those bytes used to be stranded — this
		// test previously had to cut the frame at the header byte to keep ByteBufRule strict for the
		// class, because core-http's WebSocketBufsToFrames never closed its input. Fixed in core-http
		// (WebSocketBufsToFrames#closeInput, released once the CLOSE frame is written and the decoder
		// has finished): the full frame below is now the positive assertion that it recycles.
		byte[] rsvBitSetFrame = {
			(byte) 0xC1, (byte) 0x82,
			(byte) 0x37, (byte) 0xfa, (byte) 0x21, (byte) 0x3d,   // mask
			(byte) 0x7f, (byte) 0x9f};                            // "He" masked
		await(rawFrameExchange(pair.port(), rsvBitSetFrame));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1002), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testRequestFloodAnsweredWithoutServerSideGrowth() {
		// G3, adversarial plan: the flood variant that carries an `id` and therefore an OBLIGATION — a
		// thousand requests issued back to back, each answered normally by a real dispatcher, where
		// testNotificationFloodDeliveredWithoutGrowth's fire-and-forget messages left the server nothing
		// to remember. The per-message state on the server is the in-flight dispatch and nothing else
		// (FR-095): the session registry stays at exactly one entry (FR-035 — cardinality is the
		// connection count, never the message count) and the session's own in-flight table — the
		// server→client direction — never grows at all. ByteBufRule is the primary proof that answering
		// a thousand requests accumulates nothing: every frame allocated on the way in and out is
		// recycled, with pooling disabled under Surefire so a single retained buffer fails the class.
		int floodSize = 1000;
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.build())
			.build();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);
		int[] sums = new int[floodSize];
		Arrays.fill(sums, -1);
		Ref<JsonRpcClient> client = new Ref<>();
		RefInt inFlightAtPeak = new RefInt(-1);
		RefInt sessionsAtPeak = new RefInt(-1);
		RefInt sessionInFlightAtPeak = new RefInt(-1);
		RefInt inFlightAfter = new RefInt(-1);
		RefInt sessionsAfter = new RefInt(-1);
		RefInt sessionInFlightAfter = new RefInt(-1);

		await(pair.connect()
			.then(ws -> {
				client.set(JsonRpcClient.builder(reactor(), JsonRpcWsTransport.of(reactor(), ws)).build());
				TestApi api = client.get().proxy(TestApi.class);
				List<Promise<Void>> calls = new ArrayList<>(floodSize);
				for (int i = 0; i < floodSize; i++) {
					int a = i;
					calls.add(api.add(a, 1).whenResult(result -> sums[a] = result.sum()).toVoid());
				}
				// observed while every call is still outstanding — no I/O has run between the issues, so
				// this is the real peak and proves the flood was concurrent, not a serialised trickle
				inFlightAtPeak.set(client.get().inFlightCount());
				sessionsAtPeak.set(servlet.sessions().size());
				sessionInFlightAtPeak.set(servlet.sessions().iterator().next().inFlightCount());
				return Promises.all(calls);
			})
			.whenResult($ -> {
				inFlightAfter.set(client.get().inFlightCount());
				sessionsAfter.set(servlet.sessions().size());
				sessionInFlightAfter.set(servlet.sessions().iterator().next().inFlightCount());
			})
			// closing the client closes the transport it owns (and only that) — the server sees the peer
			// close and deregisters the session
			.whenComplete(() -> client.get().closeEx(new AsyncCloseException())));

		for (int i = 0; i < floodSize; i++) {
			assertEquals("call " + i + " resolved with its own answer", i + 1, sums[i]);
		}
		assertEquals("every request was outstanding at once", floodSize, inFlightAtPeak.get());
		assertEquals("one connection, one session — never one per message (FR-035)", 1, sessionsAtPeak.get());
		assertEquals("the server→client table never grew", 0, sessionInFlightAtPeak.get());
		assertEquals("the correlation table drained completely", 0, inFlightAfter.get());
		assertEquals("the connection stayed healthy throughout", 1, sessionsAfter.get());
		assertEquals("the server→client table still empty", 0, sessionInFlightAfter.get());
		pair.closeAll();
		assertTrue("the session was deregistered on close", servlet.sessions().isEmpty());
	}

	@Test
	public void testUtf8BomPrefixedDocumentRejectedWithParseErrorAndConnectionSurvives() {
		// A9, adversarial plan: a UTF-8 BOM (U+FEFF — the bytes EF BB BF) in front of an otherwise
		// valid request document. This is deliberately NOT asked of the transport alone:
		// JsonRpcWsTransport hands the decoded bytes to its listener untouched and never looks at JSON,
		// so the only place the verdict exists is a real server — servlet + dispatcher + whatever comes
		// back on the wire. The BOM is legal UTF-8, so core-http's 1007 validation passes it through and
		// what decides is dsl-json underneath JsonRpcDecoder.
		// OBSERVED (empirically verified by running this test, not assumed): dsl-json does NOT tolerate a
		// leading BOM — the document is refused and the dispatcher answers with the clean parse error
		// {"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}. The id is null
		// because the parse never got far enough to read one, which is exactly JSON-RPC 2.0 §5's rule.
		// The refusal is a DOCUMENT-level verdict, not a connection-level one: the session stays up and the
		// next, BOM-free request on the same socket is answered normally.
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.build())
			.build();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);
		String bommed = "\uFEFF" + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";   // U+FEFF = EF BB BF
		String plain = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.add\",\"params\":[4,5]}";
		Ref<String> bomAnswer = new Ref<>();
		Ref<String> plainAnswer = new Ref<>();

		await(pair.connect().then(ws -> ws.writeMessage(Message.text(bommed))
			.then(ws::readMessage)
			.whenResult(message -> bomAnswer.set(message.getText()))
			.then(() -> ws.writeMessage(Message.text(plain)))
			.then(ws::readMessage)
			.whenResult(message -> plainAnswer.set(message.getText()))
			.whenComplete(() -> ws.closeEx(new AsyncCloseException()))));

		assertEquals("the BOM is a parse error, answered rather than dropped",
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}", bomAnswer.get());
		assertEquals("the connection survived the refusal and answered the next document",
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sum\":9}}", plainAnswer.get());
		pair.closeAll();
	}

	@Test
	public void testUnknownSubprotocolOfferedIsIgnoredAndUpgradeSucceeds() {
		// A11, adversarial plan: the client offers `Sec-WebSocket-Protocol: chat` — a subprotocol this
		// stack knows nothing about. The framing contract §1 negotiates NO subprotocol, so the offer
		// must be tolerated rather than refused: the upgrade completes normally and the connection
		// carries real documents. The offer is made by building the upgrade request by hand
		// (WsPair.connect() sends the default one) — there is no HttpHeaders constant for
		// Sec-WebSocket-Protocol, so the header is interned with HttpHeaders.of(String).
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.build())
			.build();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);
		HttpHeader secWebSocketProtocol = HttpHeaders.of("Sec-WebSocket-Protocol");
		Ref<JsonRpcClient> client = new Ref<>();
		RefInt upgradeCode = new RefInt(-1);
		Ref<String> negotiated = new Ref<>();
		RefInt sum = new RefInt(-1);

		await(pair.client().webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + pair.port())
				.withHeader(secWebSocketProtocol, "chat")
				.build())
			.then(ws -> {
				upgradeCode.set(ws.getResponse().getCode());
				negotiated.set(ws.getResponse().getHeader(secWebSocketProtocol));
				client.set(JsonRpcClient.builder(reactor(), JsonRpcWsTransport.of(reactor(), ws)).build());
				return client.get().proxy(TestApi.class).add(2, 3)
					.whenResult(result -> sum.set(result.sum()));
			})
			.whenComplete(() -> {
				JsonRpcClient toClose = client.get();
				if (toClose != null) toClose.closeEx(new AsyncCloseException());
			}));

		assertEquals("the upgrade succeeded despite the unknown offer", 101, upgradeCode.get());
		assertNull("no subprotocol may be negotiated back", negotiated.get());
		assertEquals("and the connection carried a real call", 5, sum.get());
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Raw-wire injection (A6 only). Everything else in this module speaks IWebSocket; this is the one
	// refusal whose trigger the public API cannot express, so the upgrade and the malformed frame are
	// written as bytes. The exchange ends when the peer closes: the server answers a §5 violation with
	// a close frame and then cuts the connection, so draining to end-of-stream is the natural join
	// point, and every buffer read here is recycled (ByteBufRule).
	// ---------------------------------------------------------------------------------------------------

	private static Promise<Void> rawFrameExchange(int port, byte[] frame) {
		String handshake =
			"GET / HTTP/1.1\r\n" +
			"Host: 127.0.0.1:" + port + "\r\n" +
			"Upgrade: websocket\r\n" +
			"Connection: Upgrade\r\n" +
			"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
			"Sec-WebSocket-Version: 13\r\n" +
			"\r\n";
		return TcpSocket.connect(reactor(), new InetSocketAddress("127.0.0.1", port))
			.then(socket -> socket.write(ByteBuf.wrapForReading(handshake.getBytes(ISO_8859_1)))
				.then(() -> readUpgradeResponse(socket, new StringBuilder()))
				.then(response -> {
					assertTrue("the raw upgrade must have been accepted, got: " + response.lines().findFirst().orElse(""),
						response.startsWith("HTTP/1.1 101"));
					return socket.write(ByteBuf.wrapForReading(frame));
				})
				.then(() -> drainToEndOfStream(socket))
				.whenComplete(socket::close));
	}

	/** Reads until the end of the response head; the malformed frame is written only after the 101. */
	private static Promise<String> readUpgradeResponse(TcpSocket socket, StringBuilder head) {
		return socket.read().then(buf -> {
			if (buf == null) return Promise.of(head.toString());
			head.append(new String(buf.asArray(), ISO_8859_1));     // asArray copies AND recycles
			return head.indexOf("\r\n\r\n") >= 0 ?
				Promise.of(head.toString()) :
				readUpgradeResponse(socket, head);
		});
	}

	private static Promise<Void> drainToEndOfStream(TcpSocket socket) {
		return socket.read().then(buf -> {
			if (buf == null) return Promise.complete();             // the server cut the connection
			buf.recycle();                                          // the close frame — read and dropped
			return drainToEndOfStream(socket);
		});
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