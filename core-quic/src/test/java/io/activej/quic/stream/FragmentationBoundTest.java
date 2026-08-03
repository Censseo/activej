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
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.stream.testutil.StreamFrameInjector;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.quic.stream.testutil.StreamFrameInjector.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/**
 * T078 — user story 5, scenario 5 (FR-011, clarification Q1): a peer that fragments a stream past the
 * receiver's tracked-range bound → {@code INTERNAL_ERROR} (0x01).
 *
 * <h2>What the bound counts, and why that is the whole test</h2>
 * {@code maxReceiveRangesPerStream} (default 32) bounds the <b>discontiguous ranges</b> a receiving
 * part holds — the gaps — mirroring {@code maxAckRanges} in the connection layer. It deliberately does
 * <em>not</em> bound buffered pieces:
 * <ul>
 *   <li>a peer that sends a window of in-order frames with <b>one</b> retransmission outstanding leaves
 *       one gap and arbitrarily many pieces. That is ordinary loss on an ordinary path, and it scales
 *       with the bandwidth-delay product;</li>
 *   <li>a peer that sends every other byte leaves as many gaps as it sent frames. That is a peer
 *       fragmenting pathologically to make the receiver hold per-range bookkeeping, which is the thing
 *       the bound exists to refuse.</li>
 * </ul>
 * Counting pieces conflates the two and makes the defence fire on the first case — which is what the
 * implementation did before this test existed, and why {@link LossyStreamTransferTest} had to raise
 * the bound to survive 10% loss. So both cases are asserted here: {@link
 * #manyContiguousPiecesBehindOneGapDoNotTripTheBound()} is the regression, and the rest are the
 * defence.
 *
 * <h2>The second bound (T104)</h2>
 * Counting ranges leaves a hole of its own: a peer that withholds the first byte and sends everything
 * after it one byte at a time holds the range count at <b>one</b> while buying a map entry, a boxed key
 * and a buffer header per byte. {@link StreamReassembler#PIECES_PER_RANGE} closes it with a second,
 * independent bound on buffered <i>pieces</i>, raising the same {@code INTERNAL_ERROR} — see
 * {@link #oneBytePerFrameBehindOneGapTripsThePieceBound()}. The two bounds do not subsume each other,
 * and the piece bound sits far enough above ordinary loss that {@link
 * #manyContiguousPiecesBehindOneGapDoNotTripTheBound()} still passes untouched.
 *
 * <h2>Why {@code INTERNAL_ERROR} and not a code of the peer's making</h2>
 * RFC 9000 §20.1 assigns no code for "you fragmented more than I am willing to track": the limit is
 * this receiver's, not the protocol's, so clarification Q1 answers with the code for a local resource
 * bound. The frame is <b>not</b> silently dropped — its bytes have already been charged to flow
 * control and the peer will never resend them, so dropping it would stall the stream instead of
 * failing it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.2">RFC 9000 §2.2 — Sending and Receiving Data</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class FragmentationBoundTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Small enough that a handful of frames reaches it, and far below the default 32. */
	private static final int MAX_RANGES = 4;

	/** The second bound in force here — the same multiplier a default connection gets, applied to 4. */
	private static final int MAX_PIECES = MAX_RANGES * StreamReassembler.PIECES_PER_RANGE;

	private static final long STREAM_ID = 0;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.builder()
			.withMaxReceiveRangesPerStream(MAX_RANGES)
			.build());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	private static ByteBuf bytes(int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	/**
	 * Every byte from {@code offset} to {@code offset + 2 * count} at even offsets, leaving a one-byte
	 * hole between each pair — {@code count} genuinely discontiguous ranges, none of which can close.
	 */
	private void injectDiscontiguousRanges(int count) {
		for (int i = 0; i < count; i++) {
			injector.accepts(stream(STREAM_ID, 2L + 2L * i, false, 1));
		}
	}

	private ReceivePart receivePart() {
		ReceivePart receivePart = injector.accepted(0).receivePart();
		assertNotNull(receivePart);
		return receivePart;
	}

	// ---------------------------------------------------------------- the defence

	@Test
	public void exactlyTheBoundIsAccepted() {
		injectDiscontiguousRanges(MAX_RANGES);

		assertEquals(MAX_RANGES, receivePart().pendingRanges());
		assertEquals("nothing is contiguous with offset 0, so nothing is readable", 0,
			receivePart().consumedOffset());
	}

	@Test
	public void oneRangePastTheBoundIsAnInternalError() {
		injectDiscontiguousRanges(MAX_RANGES);

		injector.rejectsWith(QuicTransportErrors.INTERNAL_ERROR,
			stream(STREAM_ID, 2L + 2L * MAX_RANGES, false, 1));
	}

	@Test
	public void theRejectedFrameLeavesNothingBehind() {
		injectDiscontiguousRanges(MAX_RANGES);
		long chargedBefore = receivePart().highestOffsetReceived();

		injector.rejectsWith(QuicTransportErrors.INTERNAL_ERROR,
			stream(STREAM_ID, 2L + 2L * MAX_RANGES, false, 1));

		assertEquals("the bound is checked before a byte is retained (SI-4)", MAX_RANGES,
			receivePart().pendingRanges());
		assertEquals(chargedBefore, receivePart().highestOffsetReceived());
	}

	// ---------------------------------------------------------------- the regression the bound must not be

	/**
	 * The case that made {@code LossyStreamTransferTest} raise this bound to 204: one hole and a long
	 * run of in-order frames behind it. Fifty times the bound in pieces, and one single gap.
	 */
	@Test
	public void manyContiguousPiecesBehindOneGapDoNotTripTheBound() {
		for (int i = 1; i <= 50 * MAX_RANGES; i++) {
			injector.accepts(stream(STREAM_ID, i, false, 1));
		}

		assertEquals("adjacent pieces are one range, however many of them there are", 1,
			receivePart().pendingRanges());
		assertEquals(50 * MAX_RANGES, receivePart().bufferedPieces());
		assertEquals(50 * MAX_RANGES + 1, receivePart().highestOffsetReceived());

		// And the run really is intact: closing its one gap delivers every byte of it in order.
		injector.accepts(stream(STREAM_ID, 0, false, 1));
		assertEquals(0, receivePart().pendingRanges());
		assertEquals(50 * MAX_RANGES + 1, drainReadable(injector.accepted(0)));
	}

	// ---------------------------------------------------------------- the second bound (T104)

	/**
	 * The attack the range bound cannot see: withhold byte 0, then send every later byte as its own
	 * frame. One gap forever, one range forever, and a map entry per byte — which is where the ~100x
	 * heap multiplier over the nominal flow-control window comes from.
	 */
	@Test
	public void oneBytePerFrameBehindOneGapTripsThePieceBound() {
		for (int i = 1; i <= MAX_PIECES; i++) {
			injector.accepts(stream(STREAM_ID, i, false, 1));
		}

		assertEquals("the range bound never sees this: it is one gap throughout", 1,
			receivePart().pendingRanges());
		assertEquals(MAX_PIECES, receivePart().bufferedPieces());

		injector.rejectsWith(QuicTransportErrors.INTERNAL_ERROR,
			stream(STREAM_ID, MAX_PIECES + 1L, false, 1));
	}

	@Test
	public void theFrameRejectedByThePieceBoundLeavesNothingBehind() {
		for (int i = 1; i <= MAX_PIECES; i++) {
			injector.accepts(stream(STREAM_ID, i, false, 1));
		}
		long chargedBefore = receivePart().highestOffsetReceived();

		injector.rejectsWith(QuicTransportErrors.INTERNAL_ERROR,
			stream(STREAM_ID, MAX_PIECES + 1L, false, 1));

		assertEquals("the bound is checked before a byte is retained (SI-4)", MAX_PIECES,
			receivePart().bufferedPieces());
		assertEquals(chargedBefore, receivePart().highestOffsetReceived());
	}

	/**
	 * The piece bound must not become the range bound under another name: a frame that <i>closes</i> the
	 * gap is delivered rather than buffered, so it is accepted at the bound and empties the buffer.
	 */
	@Test
	public void closingTheGapAtThePieceBoundIsAcceptedAndFreesEveryPiece() {
		for (int i = 1; i <= MAX_PIECES; i++) {
			injector.accepts(stream(STREAM_ID, i, false, 1));
		}

		injector.accepts(stream(STREAM_ID, 0, false, 1));

		assertEquals(0, receivePart().bufferedPieces());
		assertEquals(0, receivePart().pendingRanges());
		assertEquals(MAX_PIECES + 1, drainReadable(injector.accepted(0)));
	}

	@Test
	public void aFrameSpanningSeveralBufferedPiecesIsStillOneRange() {
		// Three separate ranges, then one frame covering all of them and the holes between: what were
		// three ranges become one, made of pieces the receiver never copied (FR-014).
		injector.accepts(stream(STREAM_ID, 4, false, 1));
		injector.accepts(stream(STREAM_ID, 6, false, 1));
		injector.accepts(stream(STREAM_ID, 8, false, 1));
		assertEquals(3, receivePart().pendingRanges());

		injector.accepts(stream(STREAM_ID, 4, false, 6));   // [4, 10)

		assertEquals(1, receivePart().pendingRanges());
		// Room for three more ranges again, which piece-counting would have denied.
		injectRangesAt(12, 14, 16);
		assertEquals(4, receivePart().pendingRanges());
	}

	@Test
	public void closingAGapFreesARangeAgain() {
		injectDiscontiguousRanges(MAX_RANGES);
		injector.rejectsWith(QuicTransportErrors.INTERNAL_ERROR,
			stream(STREAM_ID, 2L + 2L * MAX_RANGES, false, 1));

		// Bridge the first hole: [2, 3) and [4, 5) become one range with the byte between them.
		injector.accepts(stream(STREAM_ID, 3, false, 1));
		assertEquals(MAX_RANGES - 1, receivePart().pendingRanges());

		injector.accepts(stream(STREAM_ID, 2L + 2L * MAX_RANGES, false, 1));
		assertEquals(MAX_RANGES, receivePart().pendingRanges());
	}

	@Test
	public void dataContiguousWithTheReadOffsetNeverCountsAgainstTheBound() {
		injectDiscontiguousRanges(MAX_RANGES);

		// At the bound, but this frame is delivered rather than buffered — and it closes the first hole
		// on the way, so it lowers the count instead of raising it.
		injector.accepts(stream(STREAM_ID, 0, false, 3));

		// [0, 3) closes the first hole and joins the byte at offset 2, so the reader gets three bytes and
		// the read offset stops at the next hole.
		assertEquals(MAX_RANGES - 1, receivePart().pendingRanges());
		assertEquals(3, drainReadable(injector.accepted(0)));
		assertEquals(3, receivePart().consumedOffset());
	}

	// ---------------------------------------------------------------- the reassembler on its own

	@Test
	public void theReassemblerBoundsRangesRatherThanPieces() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(MAX_RANGES);

		// Fifty times the range bound in pieces, behind one hole: still one range, and the range bound is
		// nowhere near. (Still under the piece bound too — that one is asserted separately, below.)
		int pieces = 50 * MAX_RANGES;
		for (int i = 1; i <= pieces; i++) {
			reassembler.add(i, bytes(1));
		}
		assertEquals(1, reassembler.pendingRanges());
		assertEquals(pieces, reassembler.bufferedPieces());

		// Three more holes reach the bound; the fifth range is refused.
		reassembler.add(pieces + 2, bytes(1));
		reassembler.add(pieces + 4, bytes(1));
		reassembler.add(pieces + 6, bytes(1));
		assertEquals(MAX_RANGES, reassembler.pendingRanges());

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(pieces + 8, bytes(1)));
		assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		assertEquals(MAX_RANGES, reassembler.pendingRanges());

		reassembler.close();
	}

	/** The piece bound on the reassembler alone: one range throughout, refused on piece count. */
	@Test
	public void theReassemblerAlsoBoundsPieces() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(MAX_RANGES);
		assertEquals(MAX_PIECES, reassembler.maxBufferedPieces());

		for (int i = 1; i <= MAX_PIECES; i++) {
			reassembler.add(i, bytes(1));
		}
		assertEquals(1, reassembler.pendingRanges());
		assertEquals(MAX_PIECES, reassembler.bufferedPieces());

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(MAX_PIECES + 1, bytes(1)));
		assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		assertEquals("refused before anything was retained", MAX_PIECES, reassembler.bufferedPieces());

		// A frame filling the hole is still accepted at the bound: it is delivered, not buffered.
		reassembler.add(0, bytes(1));
		assertEquals(0, reassembler.bufferedPieces());

		reassembler.close();
	}

	// ---------------------------------------------------------------- the peer-visible half

	@Test
	public void fragmentingPastTheBoundClosesTheConnectionWithInternalError() throws Exception {
		for (int i = 0; i <= MAX_RANGES; i++) {
			injector.sendOverTheWire(stream(STREAM_ID, 2L + 2L * i, false, 1));
		}

		injector.assertConnectionClosedWith(QuicTransportErrors.INTERNAL_ERROR);
	}

	// ---------------------------------------------------------------- helpers

	private void injectRangesAt(long... offsets) {
		for (long offset : offsets) {
			injector.accepts(stream(STREAM_ID, offset, false, 1));
		}
	}

	/** Takes every readable byte, recycling as it goes, and reports how many there were. */
	private static int drainReadable(QuicStream stream) {
		int total = 0;
		while (true) {
			var read = stream.reader().get();
			if (!read.isResult()) return total;
			ByteBuf buf = read.getResult();
			if (buf == null) return total;
			total += buf.readRemaining();
			buf.recycle();
		}
	}
}
