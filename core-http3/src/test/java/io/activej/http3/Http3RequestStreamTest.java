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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.frame.DataFrame;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamResetException;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T064 / FR-043, FR-053, FR-057a, FR-058c: the reactive per-stream state machine, driven directly over
 * one bidirectional QUIC stream with no {@code Http3Server} above it.
 * <p>
 * Both sides run the bare {@code QuicStreamManager} {@link Http3WirePair} builds by default, so what is
 * asserted is what one {@link Http3RequestStream} does with the bytes on one stream — the connection
 * layer, its control stream and its SETTINGS are {@code Http3ConnectionSetupTest}'s subject, not this
 * one's. The exception is the last section, T109's: FR-024 and FR-025 make a frame-<i>sequence</i>
 * violation a connection error, and "the connection closed" is not something one stream can be asked, so
 * those three tests put a real {@link Http3Connection} above the stream and assert the whole effect.
 * <p>
 * No {@code EventloopRule}: {@link ManualEventloop} installs its own reactor on a hand-driven clock.
 */
public final class Http3RequestStreamTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** A code no RFC 9114 §8.1 constant uses, so a test cannot pass by coincidence. */
	private static final long PEER_ERROR_CODE = 0x4242;

	private final List<Http3RequestStream> serverStreams = new ArrayList<>();

	/** Every violation the stream under test handed to its connection-error listener. */
	private final List<Http3Exception> connectionErrors = new ArrayList<>();

	/** Whatever the connection-level harness writes and nothing reads; recycled in {@code tearDown}. */
	private final ByteBufs discarded = new ByteBufs();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Connection h3;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		discarded.recycle();
		loop.close();
	}

	@Test
	public void headersThenBodyThenResponseThenFin() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/echo");
		fields.add(new Field("content-length", "5"));
		writer.accept(Http3TestBytes.headersFrame(fields))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("hello".getBytes(UTF_8))))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);

		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue("the request headers decoded: " + received, received.isResult());

		HttpRequest request = received.getResult();
		assertEquals(HttpVersion.HTTP_3_0, request.getVersion());
		assertEquals("POST", request.getMethod().name());
		assertEquals("/echo", request.getPath());
		assertEquals(Http3RequestStream.State.HEADERS_DONE, requestStream.state());

		Promise<ByteBuf> body = request.loadBody();
		wire.driveUntil(body::isComplete);
		assertTrue("the request body loaded: " + body, body.isResult());
		assertEquals("hello", body.getResult().getString(UTF_8));
		assertEquals(Http3RequestStream.State.COMPLETE, requestStream.state());

		Promise<Void> sent = requestStream.sendResponse(HttpResponse.ok200()
			.withBody("HELLO".getBytes(UTF_8))
			.build());
		Response response = readResponse(clientStream);
		wire.driveUntil(sent::isComplete);

		assertTrue("the response was written: " + sent, sent.isResult());
		assertEquals(200, response.status);
		assertEquals("HELLO", response.body);
		assertTrue("the server FINed the stream after the last DATA frame", response.finSeen);
	}

	@Test
	public void aPeerResetSurfacesUnwrappedAndRecyclesEverythingOwned() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/upload");
		writer.accept(Http3TestBytes.headersFrame(fields))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("partial".getBytes(UTF_8))));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		// The body is left pending on purpose: this is the promise the peer's abort must fail.
		Promise<ByteBuf> body = received.getResult().loadBody();
		wire.pump();
		clientStream.reset(PEER_ERROR_CODE);
		wire.driveUntil(body::isComplete);

		assertTrue("the body promise failed: " + body, body.isException());
		Exception e = body.getException();
		assertTrue("FR-058c: the stream layer's own exception, unwrapped, not an Http3Exception: " + e,
			e instanceof QuicStreamResetException);
		assertEquals("it carries the peer's application error code",
			PEER_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
	}

	@Test
	public void aResponseSentOnAResetStreamFailsWithoutLeaking() {
		connect();
		QuicStream clientStream = openClientStream();
		clientStream.writer().accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("GET", "/")));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		requestStream.abort(Http3Errors.H3_REQUEST_CANCELLED, "aborted by the test");
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());

		// The response body must still be released even though nothing of it can reach the wire.
		Promise<Void> sent = requestStream.sendResponse(HttpResponse.ok200()
			.withBody("never sent".getBytes(UTF_8))
			.build());
		wire.driveUntil(sent::isComplete);
		assertTrue("writing to an aborted stream fails: " + sent, sent.isException());
	}

	@Test
	public void dataBeforeHeadersIsReportedAsAConnectionError() {
		connect();
		QuicStream clientStream = openClientStream();
		clientStream.writer().accept(Http3TestBytes.dataFrame("body first".getBytes(UTF_8)));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);

		assertTrue("DATA before HEADERS is rejected: " + received, received.isException());
		Exception e = received.getException();
		assertTrue("an H3 frame-sequence violation: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, ((Http3Exception) e).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());

		// FR-024/FR-025: the stream is reset *and* the violation is handed up, because a peer that frames
		// the protocol wrongly has said nothing trustworthy about any other stream either. What an owner
		// does with it is Http3Connection's — asserted end to end below.
		assertEquals("the frame-sequence violation was escalated: " + connectionErrors, 1, connectionErrors.size());
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, connectionErrors.get(0).errorCode());
	}

	@Test
	public void aMessageErrorIsNotReportedAsAConnectionError() {
		// The negative control for the assertion above, and the FR-037 half of the rule: a field section
		// this endpoint refuses is one client's problem, and nothing above this stream hears about it.
		connect();
		QuicStream clientStream = openClientStream();
		List<Field> fields = Http3TestBytes.requestFields("GET", "/");
		fields.removeIf(field -> field.name().equals(":path"));
		clientStream.writer().accept(Http3TestBytes.headersFrame(fields));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);

		assertTrue("a request without :path is rejected: " + received, received.isException());
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, ((Http3Exception) received.getException()).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
		assertEquals("a malformed message never reaches the connection: " + connectionErrors,
			List.of(), connectionErrors);
	}

	/**
	 * RFC 9204 §3.1: an invalid <b>static</b> table index MUST be a connection error. QPACK is the one
	 * place where the scope does not follow from the error code — every failure here is
	 * {@code QPACK_DECOMPRESSION_FAILED} (0x0200) and the RFC assigns the scope per cause — so this
	 * asserts the escalation end to end, not just the decoder's verdict.
	 */
	@Test
	public void anInvalidStaticTableIndexIsReportedAsAConnectionError() {
		connect();
		QuicStream clientStream = openClientStream();
		// prefix (RIC=0, S/DeltaBase=0), then Indexed Field Line "1 1 index(6+)" with index = 99, one past
		// the last valid static entry: 6-bit prefix all ones (63) plus a continuation of 36.
		clientStream.writer().accept(Http3TestBytes.frame(HeadersFrame.TYPE,
			new byte[] {0x00, 0x00, (byte) 0xFF, 0x24}));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);

		assertTrue("an out-of-range static index is rejected: " + received, received.isException());
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			((Http3Exception) received.getException()).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
		assertEquals("a static-table disagreement was escalated: " + connectionErrors,
			1, connectionErrors.size());
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, connectionErrors.get(0).errorCode());
	}

	/**
	 * The negative control, and the reason the scope cannot be read off the code: this failure carries
	 * the <b>same</b> {@code QPACK_DECOMPRESSION_FAILED} as the test above and must stay stream-scoped.
	 * RFC 9204 §7 keeps a local decode limit on the stream; the static table holds no cross-section
	 * state that a truncated section could corrupt.
	 */
	@Test
	public void aTruncatedFieldSectionIsNotReportedAsAConnectionError() {
		connect();
		QuicStream clientStream = openClientStream();
		// A Required Insert Count marker (8-bit prefix all ones) demanding a continuation that never comes.
		clientStream.writer().accept(Http3TestBytes.frame(HeadersFrame.TYPE, new byte[] {(byte) 0xFF}));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);

		assertTrue("a truncated field section is rejected: " + received, received.isException());
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			((Http3Exception) received.getException()).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
		assertEquals("a truncated section never reaches the connection: " + connectionErrors,
			List.of(), connectionErrors);
	}

	@Test
	public void aContentLengthThatDisagreesWithTheBodyIsAMessageError() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/echo");
		fields.add(new Field("content-length", "10"));
		writer.accept(Http3TestBytes.headersFrame(fields))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("abc".getBytes(UTF_8))))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		Promise<ByteBuf> body = received.getResult().loadBody();
		wire.driveUntil(body::isComplete);

		assertTrue("the declared length is reconciled against the DATA received: " + body, body.isException());
		Exception e = body.getException();
		assertTrue("an H3 message error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, ((Http3Exception) e).errorCode());
	}

	@Test
	public void aRequestWhoseBodyNobodyTakesIsDrainedAndRecycled() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/ignored");
		fields.add(new Field("content-length", "4"));
		writer.accept(Http3TestBytes.headersFrame(fields))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("drop".getBytes(UTF_8))))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		// FR-057a: the stream owns the message, so a servlet that never looks at the body leaks nothing —
		// which is ByteBufRule's assertion, made at the end of this class rather than here.
		Promise<Void> sent = requestStream.sendResponse(HttpResponse.ofCode(204).build());
		Response response = readResponse(clientStream);
		wire.driveUntil(sent::isComplete);

		assertTrue(sent.isResult());
		assertEquals(204, response.status);
		assertEquals("", response.body);
	}

	/**
	 * T098 regression. RFC 9114 §7.1: a stream that ends in the middle of a frame ended in the middle of
	 * a message — {@code H3_FRAME_ERROR}. The reason it is asserted here rather than only at the frame
	 * layer is the buffer: the stream's {@code Http3FrameReader} has allocated a payload buffer and
	 * filled part of it, and until the abort path recycled the reader that buffer leaked on every
	 * truncated frame a peer could send. {@code ByteBufRule} is the second assertion (DI-1).
	 */
	@Test
	public void aStreamFinishedMidFrameIsAFrameErrorAndLeavesNothingBuffered() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		// A DATA frame header declaring 4096 bytes, followed by four of them and a FIN.
		ByteBuf truncated = ByteBufPool.allocate(8);
		QuicVarInts.write(truncated, DataFrame.TYPE);
		QuicVarInts.write(truncated, 4096);
		truncated.put("abcd".getBytes(UTF_8));

		writer.accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("POST", "/truncated")))
			.then(() -> writer.accept(truncated))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		Promise<ByteBuf> body = received.getResult().loadBody();
		wire.driveUntil(body::isComplete);

		assertTrue("the truncated frame failed the body: " + body, body.isException());
		Exception e = body.getException();
		assertTrue("an H3 frame error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_FRAME_ERROR, ((Http3Exception) e).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
	}

	/**
	 * T110's half of the same rule, and the reason {@code Http3FrameReader.isMidFrame()} exists. A DATA
	 * frame past the reader's chunk is delivered in instalments, so the last thing this stream saw
	 * before the FIN is a <b>complete frame</b> — and the frame it came from is still unfinished. A
	 * truncation test that inferred "mid-frame" from a {@code feed} that produced nothing would read
	 * this as a clean end of message and answer the peer as though the body were whole.
	 */
	@Test
	public void aStreamFinishedBetweenTwoInstalmentsOfOneDataFrameIsStillAFrameError() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		// 40 000 bytes declared, of which exactly one whole 16 KiB instalment is sent, then a FIN.
		byte[] sent = new byte[16 * 1024];
		writer.accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("POST", "/half-a-frame")))
			.then(() -> writer.accept(Http3TestBytes.overDeclaredDataFrame(40_000, sent)))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue(received.isResult());

		Promise<ByteBuf> body = received.getResult().loadBody();
		wire.driveUntil(body::isComplete);

		assertTrue("the unfinished frame failed the body: " + body, body.isException());
		Exception e = body.getException();
		assertTrue("an H3 frame error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_FRAME_ERROR, ((Http3Exception) e).errorCode());
		assertEquals(Http3RequestStream.State.RESET, requestStream.state());
	}

	// ------------------------------------------- the whole connection (T109 / FR-024, FR-025, US5 §1–3)

	/**
	 * The three request-stream framing violations spec.md's US5 scenarios 1–3 name, each asserted end to
	 * end: not that the stream reset — the tests above already establish that — but that the
	 * <b>connection</b> closed, with the right code, which is what FR-024 and FR-025 actually require.
	 * <p>
	 * A request stream's other refusals stay on their stream (FR-037), and
	 * {@link #aMessageErrorIsNotReportedAsAConnectionError} plus {@link Http3ServerErrorTest} are the
	 * negative controls for that. What separates the two is not severity but subject: a malformed
	 * <i>message</i> is one client's problem, while a peer that puts a frame where RFC 9114 §7.2's table
	 * does not permit one has said nothing trustworthy about any other stream either.
	 */
	@Test
	public void dataBeforeHeadersClosesTheConnectionWithFrameUnexpected() {
		connectWithServerH3();
		openPeerControlStream();

		sendOnARequestStreamAndDriveUntilClosed(Http3TestBytes.dataFrame("body first".getBytes(UTF_8)));

		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, h3.closedWithErrorCode());
	}

	@Test
	public void aThirdHeadersFrameClosesTheConnectionWithFrameUnexpected() {
		connectWithServerH3();
		openPeerControlStream();

		// HEADERS, the one trailing HEADERS RFC 9114 §4.1 allows, and then one more.
		sendOnARequestStreamAndDriveUntilClosed(
			Http3TestBytes.headersFrame(Http3TestBytes.requestFields("POST", "/trailers")),
			Http3TestBytes.headersFrame(List.of(new Field("x-checksum", "abc123"))),
			Http3TestBytes.headersFrame(List.of(new Field("x-checksum", "def456"))));

		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, h3.closedWithErrorCode());
	}

	@Test
	public void aReservedHttp2FrameTypeClosesTheConnectionWithFrameUnexpected() {
		connectWithServerH3();
		openPeerControlStream();

		// 0x02 is RFC 9114 §7.2.8's PRIORITY, reserved so that an HTTP/2 frame can never be mistaken for
		// an HTTP/3 one. The reader refuses it on the type varint, before the sequence validator sees it.
		sendOnARequestStreamAndDriveUntilClosed(
			Http3TestBytes.headersFrame(Http3TestBytes.requestFields("GET", "/reserved")),
			Http3TestBytes.frame(0x02, new byte[]{1, 2, 3}));

		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- helpers

	private void connect() {
		wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> serverStreams.add(Http3RequestStream.builder(reactor(), stream)
				.withSettings(Http3Settings.create())
				.withConnectionErrorListener(connectionErrors::add)
				.build()))
			.connect();
	}

	private QuicStream openClientStream() {
		return wire.openNow(wire.clientStreams().openBidirectional());
	}

	/**
	 * The one harness here that puts a real {@link Http3Connection} above the stream — the server half of
	 * a connection whose peer is still the bare {@code QuicStreamManager}, so a test can send frames no
	 * encoder in this module would emit. Only the FR-024/FR-025 tests need it: everything else asserts
	 * what one stream does, and a connection above it would only add moving parts.
	 */
	private void connectWithServerH3() {
		wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> Http3TestBytes.collect(stream, discarded))
			.withServerHandlerFactory(connection -> {
				h3 = Http3Connection.builder(reactor(), connection)
					.withRequestStreamListener(serverStreams::add)
					.build();
				return h3.startAndGetStreamManager();
			})
			.connect();
	}

	/** A peer control stream, so the connection under test reaches {@code READY} before anything else. */
	private void openPeerControlStream() {
		QuicStream control = wire.openNow(wire.clientStreams().openUnidirectional());
		control.writer().accept(Http3TestBytes.concat(
			Http3TestBytes.streamHeader(Http3StreamType.CONTROL.code()),
			Http3TestBytes.settingsFrame(new long[]{0x01, 0x07}, new long[]{0, 0})));
		wire.driveUntil(() -> h3.state() == Http3Connection.State.READY);
	}

	/**
	 * Opens a peer request stream, writes {@code frames} on it and drives until the connection under test
	 * has closed — reading the request head first, because nothing reads a request stream's frames until
	 * somebody asks for its message.
	 */
	private void sendOnARequestStreamAndDriveUntilClosed(ByteBuf... frames) {
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();
		Promise<Void> written = Promise.complete();
		for (ByteBuf frame : frames) {
			written = written.then(() -> writer.accept(frame));
		}

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		// The head, then the body: between them they drive every frame the peer wrote through the §4.1
		// sequence, which is where the violation is found.
		requestStream.receiveRequest().whenResult(request -> request.loadBody());

		wire.driveUntil(() -> h3.state() == Http3Connection.State.CLOSED);
	}

	private record Response(int status, String body, boolean finSeen) {}

	/** Reads whatever the server writes on {@code stream} until its FIN, then decodes it. */
	private Response readResponse(QuicStream stream) {
		ByteBufs received = new ByteBufs();
		boolean[] fin = {false};
		Promises.repeat(() -> stream.reader().get()
			.map(buf -> {
				if (buf == null) {
					fin[0] = true;
					return false;
				}
				received.add(buf);
				return true;
			}));
		wire.driveUntil(() -> fin[0]);

		ByteBuf all = received.takeRemaining();
		ByteBufs body = new ByteBufs();
		try {
			Http3FrameReader reader = new Http3FrameReader(1 << 20);
			int status = -1;
			Http3Frame frame;
			while ((frame = reader.feed(all)) != null) {
				if (frame instanceof HeadersFrame headers) {
					List<Field> fields = Http3Headers.fromQpack(
						new QpackStaticDecoder(Http3Settings.create().maxFieldSectionSize()).decode(headers.fieldSection));
					for (Field field : fields) {
						if (field.name().equals(":status")) status = Integer.parseInt(field.value());
					}
				} else if (frame instanceof DataFrame data) {
					body.add(data.data);
				} else {
					fail("unexpected frame on a response stream: " + frame);
				}
			}
			ByteBuf bodyBuf = body.takeRemaining();
			try {
				return new Response(status, bodyBuf.getString(UTF_8), fin[0]);
			} finally {
				bodyBuf.recycle();
			}
		} catch (Exception e) {
			throw new AssertionError("the response did not decode", e);
		} finally {
			all.recycle();
			body.recycle();
			received.recycle();
		}
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
