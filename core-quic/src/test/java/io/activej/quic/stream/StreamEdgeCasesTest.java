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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.MaxStreamDataFrame;
import io.activej.quic.codec.MaxStreamsFrame;
import io.activej.quic.codec.QuicStreamLimitType;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.stream.testutil.StreamFrameInjector;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static io.activej.quic.stream.testutil.StreamFrameInjector.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T079 — the spec's Edge Cases list, one test per row. Each of these is <b>legal</b> input that an
 * implementation is likely to mistake for a violation, which is why they sit next to user story 5's
 * hostile-input tests rather than among the happy paths: the boundary between "reject" and "accept"
 * is only as good as both of its sides.
 *
 * <h2>The rows, and where each is asserted</h2>
 * <table>
 *   <caption>Spec Edge Cases coverage</caption>
 *   <tr><th>Edge case</th><th>Asserted by</th></tr>
 *   <tr><td>Zero-length frame with end-of-data at offset 0</td>
 *       <td>{@link #aZeroLengthFinAtOffsetZeroIsAnEmptyStreamThatEndsAtOnce()}</td></tr>
 *   <tr><td>Zero-length frame without end-of-data</td>
 *       <td>{@link #aZeroLengthNonFinFrameOpensTheStreamAndDeliversNothing()}</td></tr>
 *   <tr><td>End-of-data before the bytes preceding it</td>
 *       <td>{@link #aFinArrivingBeforeItsBytesIsSizeKnownUntilTheGapCloses()}</td></tr>
 *   <tr><td>Frame partially overlapping delivered bytes</td>
 *       <td>{@link #aPartialOverlapIsTrimmedAndChargedByHighestOffset()}</td></tr>
 *   <tr><td>Data arriving after a local abort</td>
 *       <td>{@link #dataAfterALocalAbortIsAccountedAndDiscarded()}</td></tr>
 *   <tr><td>An abort and an end-of-data in either order</td>
 *       <td>{@link #anAbortAndAFinInEitherOrderLeaveTheSameFinalSize()}, and {@link AbortRacesTest}
 *           in full (SC-004)</td></tr>
 *   <tr><td>A stream identifier that skips ahead</td>
 *       <td>{@link #anIdentifierThatSkipsAheadOpensEveryStreamBelowIt()}</td></tr>
 *   <tr><td>The peer lowers a limit</td>
 *       <td>{@link #aLoweredStreamLimitIsIgnored()}, {@link #aLoweredConnectionLimitIsIgnored()},
 *           {@link #aLoweredStreamCountLimitIsIgnored()}</td></tr>
 *   <tr><td>Offset plus length exceeding 2^62-1</td><td>{@link OffsetOverflowTest}</td></tr>
 *   <tr><td>A stream aborted while the writer's completion signal is pending</td>
 *       <td>{@link AbortRacesTest}, {@link StreamResetTest}</td></tr>
 *   <tr><td>Connection closes with streams open</td>
 *       <td>{@link ConnectionCloseWithOpenStreamsTest}</td></tr>
 *   <tr><td>A lost frame carrying a limit update</td>
 *       <td>{@link LimitFrameRegenerationTest}</td></tr>
 *   <tr><td>The consumer of a reader stops consuming entirely</td>
 *       <td>{@link #aStreamWhoseReaderStopsConsumingStallsAloneAndTakesNothingWithIt()}</td></tr>
 * </table>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 */
public final class StreamEdgeCasesTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Client-initiated bidirectional #0 and #1. */
	private static final long STREAM_ID = 0;
	private static final long OTHER_STREAM_ID = 4;

	private static final long APP_ERROR_CODE = 0x0C0FFEEL;

	/** Small, so a whole stream window fits in one injected frame. */
	private static final int STREAM_WINDOW = 100;

	/** Ten stream windows, so one stalled stream cannot be what stops the connection. */
	private static final int CONNECTION_WINDOW = 1000;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(CONNECTION_WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(STREAM_WINDOW))
			.build());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	private ReceivePart receivePart(int index) {
		ReceivePart receivePart = injector.accepted(index).receivePart();
		assertNotNull(receivePart);
		return receivePart;
	}

	/** {@code asString} takes ownership and recycles, so there is deliberately no recycle here. */
	private static String readString(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		assertTrue("the reader parked when it should not have", read.isResult());
		ByteBuf buf = read.getResult();
		assertNotNull(buf);
		return buf.asString(StandardCharsets.US_ASCII);
	}

	private static void assertEndOfStream(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		assertTrue("end-of-stream must be deliverable, not parked", read.isResult());
		assertNull("end-of-stream is a null buffer, not an empty one", read.getResult());
	}

	// ---------------------------------------------------------------- zero-length frames

	@Test
	public void aZeroLengthFinAtOffsetZeroIsAnEmptyStreamThatEndsAtOnce() {
		injector.accepts(stream(STREAM_ID, 0, true, 0));

		QuicStream stream = injector.accepted(0);
		assertEquals("it still consumes a stream identifier", 1,
			injector.manager().streamsAcceptedFromPeer());
		assertEquals("...and it still has a final size", (Long) 0L, receivePart(0).finalSize());
		assertEquals(ReceiveState.DATA_RECVD, stream.receiveState());

		assertEndOfStream(stream);
		assertEquals(ReceiveState.DATA_READ, stream.receiveState());
	}

	@Test
	public void aZeroLengthNonFinFrameOpensTheStreamAndDeliversNothing() {
		injector.accepts(stream(STREAM_ID, 0, false, 0));

		assertEquals("it opens the stream, so it counts against the stream limit", 1,
			injector.manager().openStreamCount());
		assertNull("but it declares no final size", receivePart(0).finalSize());
		assertEquals(ReceiveState.RECV, injector.accepted(0).receiveState());

		Promise<ByteBuf> parked = injector.accepted(0).reader().get();
		assertFalse("and there is nothing whatever to deliver", parked.isComplete());

		// Left parked deliberately; the connection close in tearDown resolves it.
		injector.accepts(stream(STREAM_ID, 0, true, "later"));
		assertTrue(parked.isResult());
		ByteBuf buf = parked.getResult();
		assertNotNull(buf);
		buf.recycle();
	}

	@Test
	public void aZeroLengthNonFinFrameAtANonZeroOffsetIsAlsoLegal() {
		injector.accepts(stream(STREAM_ID, 40, false, 0));

		assertEquals(1, injector.manager().openStreamCount());
		assertEquals("it opens no gap: there is no byte to buffer", 0, receivePart(0).pendingRanges());
		// It does move the high-water mark, though, because RFC 9000 §4.1 charges the largest *offset*
		// reached rather than the bytes carried, and this frame asserts the stream has reached 40. The
		// peer only ever spends its own credit that way, and the alternative — a frame whose offset the
		// receiver ignores — would let a stream's window be re-spent from behind.
		assertEquals(40, receivePart(0).highestOffsetReceived());
		assertNull("but it still declares no final size", receivePart(0).finalSize());
	}

	// ---------------------------------------------------------------- out-of-order end-of-data

	@Test
	public void aFinArrivingBeforeItsBytesIsSizeKnownUntilTheGapCloses() {
		injector.accepts(stream(STREAM_ID, 5, true, "world"));

		QuicStream stream = injector.accepted(0);
		assertEquals("the final size is known at once...", (Long) 10L, receivePart(0).finalSize());
		assertEquals("...but the stream is not finished", ReceiveState.SIZE_KNOWN, stream.receiveState());
		Promise<ByteBuf> parked = stream.reader().get();
		assertFalse("nor may anything be delivered out of order", parked.isComplete());

		injector.accepts(stream(STREAM_ID, 0, false, "hello"));

		assertEquals(ReceiveState.DATA_RECVD, stream.receiveState());
		assertTrue(parked.isResult());
		ByteBuf first = parked.getResult();
		assertNotNull(first);
		assertEquals("hello", first.asString(StandardCharsets.US_ASCII));
		assertEquals("world", readString(stream));
		assertEndOfStream(stream);
	}

	// ---------------------------------------------------------------- overlaps

	@Test
	public void aPartialOverlapIsTrimmedAndChargedByHighestOffset() {
		injector.accepts(stream(STREAM_ID, 0, false, "hello"));
		QuicStream stream = injector.accepted(0);
		assertEquals("hello", readString(stream));

		// [3, 8) straddles what was already delivered: only [5, 8) is novel, and flow control is charged
		// three bytes rather than five — the high-water mark moved by three (RFC 9000 §4.1).
		injector.accepts(stream(STREAM_ID, 3, false, "lo wo".length()));

		assertEquals(8, receivePart(0).highestOffsetReceived());
		assertEquals("only the novel suffix is delivered", 3, readString(stream).length());
	}

	@Test
	public void aWholeDuplicateBuysNoCreditBackAndDeliversNothing() {
		injector.accepts(stream(STREAM_ID, 0, false, "hello"));
		QuicStream stream = injector.accepted(0);
		assertEquals("hello", readString(stream));

		injector.accepts(stream(STREAM_ID, 0, false, "hello"));

		assertEquals("the high-water mark never moves backwards, and never twice for the same byte", 5,
			receivePart(0).highestOffsetReceived());
		assertFalse("and not one byte of it is delivered again", stream.reader().get().isComplete());
	}

	// ---------------------------------------------------------------- data after a local abort

	@Test
	public void dataAfterALocalAbortIsAccountedAndDiscarded() {
		injector.accepts(stream(STREAM_ID, 0, false, "hello"));
		QuicStream stream = injector.accepted(0);
		stream.stopSending(APP_ERROR_CODE);

		// The peer cannot have heard yet, so its bytes keep arriving. FR-035: charged, because it spent
		// that credit before it could have known, and discarded, because there is no reader left.
		injector.accepts(stream(STREAM_ID, 5, false, "world"));

		assertEquals("charged", 10, receivePart(0).highestOffsetReceived());
		assertEquals("but not buffered", 0, receivePart(0).pendingRanges());
		assertEquals("nor delivered", 0, receivePart(0).consumedOffset());

		Exception e = stream.reader().get().getException();
		assertTrue("reads report the abandonment", e instanceof QuicStreamStopSendingException);
	}

	// ---------------------------------------------------------------- an abort racing an end-of-data

	@Test
	public void anAbortAndAFinInEitherOrderLeaveTheSameFinalSize() {
		// FIN first, then an agreeing RESET_STREAM: the reader keeps the clean end it already had.
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));
		injector.accepts(new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, 5));
		assertEquals(ReceiveState.DATA_RECVD, injector.accepted(0).receiveState());
		assertEquals((Long) 5L, receivePart(0).finalSize());
		assertEquals("hello", readString(injector.accepted(0)));
		assertEndOfStream(injector.accepted(0));

		// RESET_STREAM first, then an agreeing FIN on another stream: the abort stands, and the FIN
		// neither resurrects the stream nor changes the size.
		injector.accepts(new ResetStreamFrame(OTHER_STREAM_ID, APP_ERROR_CODE, 5));
		QuicStream other = injector.accepted(1);
		assertEquals(ReceiveState.RESET_RECVD, other.receiveState());
		injector.accepts(stream(OTHER_STREAM_ID, 0, true, "hello"));
		assertEquals((Long) 5L, receivePart(1).finalSize());
		Exception e = other.reader().get().getException();
		assertTrue("the abort is what the reader observes", e instanceof QuicStreamResetException);
	}

	// ---------------------------------------------------------------- an identifier that skips ahead

	@Test
	public void anIdentifierThatSkipsAheadOpensEveryStreamBelowIt() {
		// RFC 9000 §2.1: naming stream 12 opens client-initiated bidirectional streams 0, 4, 8 and 12.
		injector.accepts(stream(12, 0, false, "x"));

		assertEquals(4, injector.manager().openStreamCount());
		assertEquals("all four count against the limit this endpoint advertised", 4,
			injector.manager().streamsAcceptedFromPeer());
		// Announced in ascending order, and every one of them exists before the first is announced.
		assertEquals(0, injector.accepted(0).id());
		assertEquals(4, injector.accepted(1).id());
		assertEquals(8, injector.accepted(2).id());
		assertEquals(12, injector.accepted(3).id());

		assertEquals("only the named stream carries data", 0, receivePart(0).highestOffsetReceived());
		assertEquals("x", readString(injector.accepted(3)));
	}

	@Test
	public void aLaterFrameForAnAlreadyImplicitlyOpenedStreamOpensNothingMore() {
		injector.accepts(stream(12, 0, false, "x"));
		assertEquals(4, injector.manager().openStreamCount());

		injector.accepts(stream(4, 0, false, "y"));

		assertEquals("stream 4 was already open; nothing is opened twice", 4,
			injector.manager().openStreamCount());
		assertEquals(4, injector.manager().streamsAcceptedFromPeer());
		assertEquals("y", readString(injector.accepted(1)));
		assertEquals("x", readString(injector.accepted(3)));
	}

	// ---------------------------------------------------------------- lowered limits are ignored (FR-026)

	@Test
	public void aLoweredStreamLimitIsIgnored() {
		QuicStream stream = openLocalBidirectional();
		SendPart sendPart = stream.sendPart();
		assertNotNull(sendPart);
		long granted = sendPart.flowControl().limit();
		assertTrue("the peer granted something to begin with", granted > 0);

		// RFC 9000 §4.1: frames are reordered, so a stale MAX_STREAM_DATA is a normal event and not an
		// error. It must be ignored rather than shrink a window bytes may already have been sent into.
		injector.accepts(new MaxStreamDataFrame(stream.id(), 1));

		assertEquals(granted, sendPart.flowControl().limit());
	}

	@Test
	public void aLoweredConnectionLimitIsIgnored() {
		QuicStream stream = openLocalBidirectional();
		SendPart sendPart = stream.sendPart();
		assertNotNull(sendPart);

		injector.accepts(new MaxDataFrame(1));

		// Observable through what may still be written: a lowered MAX_DATA that had taken effect would
		// hold this write, and a stalled writer is the failure mode FR-026 exists to prevent.
		Promise<Void> written = stream.writer().accept(StreamFrameInjector.payload(0, 64));
		injector.wire().pump();
		assertTrue("a stale MAX_DATA must not hold a writer", written.isComplete());
	}

	@Test
	public void aLoweredStreamCountLimitIsIgnored() {
		injector.accepts(new MaxStreamsFrame(1, QuicStreamLimitType.BIDIRECTIONAL));

		// The peer advertised 100 to begin with; a MAX_STREAMS of 1 must not take three opens away.
		openLocalBidirectional();
		openLocalBidirectional();
		openLocalBidirectional();

		assertEquals(3, injector.manager().streamsOpenedLocally());
	}

	// ---------------------------------------------------------------- a reader that stops consuming

	@Test
	public void aStreamWhoseReaderStopsConsumingStallsAloneAndTakesNothingWithIt() {
		// Nobody reads stream 0. Its buffering is capped by the window this endpoint advertised for it,
		// which the peer has now filled exactly.
		injector.accepts(stream(STREAM_ID, 0, false, STREAM_WINDOW));
		assertEquals(STREAM_WINDOW, receivePart(0).highestOffsetReceived());

		injector.rejectsWith(QuicTransportErrors.FLOW_CONTROL_ERROR,
			stream(STREAM_ID, STREAM_WINDOW, false, 1));

		// ...and the connection is untouched: another stream has its own full window to work in.
		injector.accepts(stream(OTHER_STREAM_ID, 0, false, STREAM_WINDOW));
		assertEquals(STREAM_WINDOW, receivePart(1).highestOffsetReceived());
		assertEquals(STREAM_WINDOW, readString(injector.accepted(1)).length());
	}

	// ---------------------------------------------------------------- helpers

	private QuicStream openLocalBidirectional() {
		Promise<QuicStream> opened = injector.manager().openBidirectional();
		assertTrue("the peer grants bidirectional streams by default", opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}
}
