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
import io.activej.quic.codec.MaxStreamsFrame;
import io.activej.quic.codec.QuicStreamLimitType;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.codec.ResetStreamFrame;
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
import static org.junit.Assert.assertTrue;

/**
 * T077 — user story 5 and FR-013: an offset and length whose sum passes 2^62-1 →
 * {@code FRAME_ENCODING_ERROR} (0x07), <b>with the sum itself computed without overflowing</b>.
 *
 * <h2>The check is written as a subtraction, and that is the test</h2>
 * A stream offset is a 62-bit value and RFC 9000 §19.8 forbids {@code Offset + Length} from exceeding
 * {@code 2^62 - 1}. Written the obvious way that reads:
 * <pre>{@code
 * if (offset + length > MAX_OFFSET) reject();     // WRONG
 * }</pre>
 * which is itself an addition that can wrap. For an offset near {@link Long#MAX_VALUE} the sum
 * becomes a large <i>negative</i> number, {@code > MAX_OFFSET} is then false, and the frame sails
 * through the very check meant to stop it — after which every downstream computation of {@code end}
 * is nonsense. Every one of the three layers that sees an offset therefore writes it as
 * <pre>{@code
 * if (offset < 0 || offset > MAX_OFFSET - length) reject();   // right: no addition, no wrap
 * }</pre>
 * and {@link #everyOffsetWhoseNaiveSumWrapsIsRefusedByAllThreeLayers()} pins down that the difference
 * is real rather than stylistic — by asking all three layers to refuse precisely the inputs the naive
 * form would wave through.
 *
 * <h2>Where such an offset comes from</h2>
 * Not from {@code QuicVarInts}, which cannot decode a value above 2^62-1 — which is exactly why the
 * check must be defended by construction rather than by trusting the decoder. A frame reaches
 * {@link QuicStreamManager#onFrame} as a parsed object, and a future codec path, a fuzzer or a
 * refactor is all it takes for the assumption to stop holding; SI-4 is that untrusted-input bounds
 * are never conditional on somebody else having checked first. Hence the same check in
 * {@link QuicStreamManager}, {@link ReceivePart} and {@link StreamReassembler}, each reachable on its
 * own.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class OffsetOverflowTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** 2^62 - 1: the largest value a QUIC variable-length integer can carry (RFC 9000 §16). */
	private static final long MAX_OFFSET = StreamReassembler.MAX_OFFSET;

	private static final long STREAM_ID = 0;
	private static final long APP_ERROR_CODE = 0x0DEADL;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.create());
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

	// ---------------------------------------------------------------- STREAM frames

	@Test
	public void oneByteAtTheMaximumOffsetOverflowsTheRange() {
		// offset == 2^62-1 leaves room for zero bytes: the first byte would sit *at* the maximum, so the
		// range ends at 2^62, one past it.
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR, stream(STREAM_ID, MAX_OFFSET, false, 1));
	}

	@Test
	public void aRangeStraddlingTheMaximumIsRejected() {
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(STREAM_ID, MAX_OFFSET - 1, false, 2));
	}

	@Test
	public void aNegativeOffsetIsRejected() {
		// Unreachable through the varint decoder and rejected anyway: a 62-bit value read into a signed
		// long is only non-negative because the decoder says so, and SI-4 does not take its word for it.
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR, stream(STREAM_ID, -1, false, 4));
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(STREAM_ID, Long.MIN_VALUE, false, 4));
	}

	/**
	 * The case a naive check lets through: {@code Long.MAX_VALUE + 1} wraps to {@code Long.MIN_VALUE},
	 * which is not greater than {@code MAX_OFFSET}, so {@code offset + length > MAX_OFFSET} answers
	 * "fine" for the largest offset expressible.
	 */
	@Test
	public void anOffsetThatWouldWrapANaiveSumIsRejected() {
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(STREAM_ID, Long.MAX_VALUE, false, 1));
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(STREAM_ID, Long.MAX_VALUE - 3, false, 8));
	}

	/**
	 * Pins the guard's shape down, so it cannot be "simplified" back into a bug: for every offset whose
	 * naive sum wraps — the exact inputs {@code offset + length > MAX_OFFSET} answers "fine" for — each
	 * of the three layers that owns a copy of the check is asked directly, and each must refuse.
	 * <p>
	 * The wrap itself is asserted as a <i>precondition</i>, not as the subject: a case where the naive
	 * sum does not wrap would be testing nothing, and would silently stop testing anything if the
	 * constants were ever changed.
	 */
	@Test
	public void everyOffsetWhoseNaiveSumWrapsIsRefusedByAllThreeLayers() {
		long[][] cases = {{Long.MAX_VALUE, 1}, {Long.MAX_VALUE - 3, 8}, {Long.MAX_VALUE - 1000, 1024}};

		for (long[] testCase : cases) {
			long offset = testCase[0];
			int length = (int) testCase[1];
			String what = "offset " + offset + " + length " + length;

			assertTrue(what + ": the naive sum must wrap, or this case tests nothing",
				offset + length < 0);
			assertTrue(what + ": ...and a naive bound check must read the wrapped sum as 'within range'",
				offset + length <= MAX_OFFSET);

			// 1. QuicStreamManager, where a frame off the wire arrives.
			injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
				stream(STREAM_ID, offset, false, length));

			// 2. ReceivePart, reachable on its own.
			ReceivePart part = new ReceivePart(STREAM_ID, 1024, 32, null);
			QuicTransportException fromPart = assertThrows(what, QuicTransportException.class,
				() -> part.onStreamFrame(offset, false, bytes(length)));
			assertEquals(what, QuicTransportErrors.FRAME_ENCODING_ERROR, fromPart.errorCode());
			part.closeEx(new QuicTransportException(QuicTransportErrors.NO_ERROR, "done"));

			// 3. StreamReassembler, likewise.
			StreamReassembler reassembler = new StreamReassembler(32);
			QuicTransportException fromReassembler = assertThrows(what, QuicTransportException.class,
				() -> reassembler.add(offset, bytes(length)));
			assertEquals(what, QuicTransportErrors.FRAME_ENCODING_ERROR, fromReassembler.errorCode());
			assertEquals(what + ": nothing of a refused frame is buffered", 0, reassembler.bufferedPieces());
			reassembler.close();
		}
	}

	@Test
	public void aRangeEndingExactlyAtTheMaximumIsLegal() {
		// The boundary from the other side, asserted where no flow-control window gets in the way: the
		// sum may *equal* 2^62-1. An off-by-one here would reject a legal frame rather than accept an
		// illegal one, which is the failure mode a "reject everything near the edge" guard produces.
		StreamReassembler reassembler = new StreamReassembler(32);
		try {
			reassembler.add(MAX_OFFSET - 2, bytes(2));
			assertEquals(1, reassembler.pendingRanges());
		} catch (QuicTransportException e) {
			throw new AssertionError("a range ending exactly at 2^62-1 is legal", e);
		} finally {
			reassembler.close();
		}
	}

	// ---------------------------------------------------------------- the bound comes before the identifier

	@Test
	public void theEncodingBoundIsCheckedBeforeAnyStreamIsOpened() {
		// A stream identifier far past the advertised limit *and* an overflowing offset. The frame's own
		// syntax is the cheapest thing wrong with it and must be what refuses it (SI-4, CHK080) — a
		// receiver that opened the implicit run first would have allocated on behalf of a frame it was
		// always going to reject.
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(StreamIds.of(1L << 40, true, true), Long.MAX_VALUE, false, 1));

		assertEquals("nothing may be opened on behalf of a frame that is refused", 0,
			injector.manager().openStreamCount());
		assertEquals(0, injector.manager().streamsAcceptedFromPeer());
		assertTrue(injector.accepted().isEmpty());
	}

	@Test
	public void anOverflowingOffsetOnAnOpenStreamLeavesItUntouched() {
		injector.accepts(stream(STREAM_ID, 0, false, "hello"));
		ReceivePart receivePart = injector.accepted(0).receivePart();
		assertNotNull(receivePart);

		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			stream(STREAM_ID, MAX_OFFSET, false, 16));

		assertEquals("no byte of a refused frame is accounted", 5, receivePart.highestOffsetReceived());
		assertEquals("nor buffered", 0, receivePart.consumedOffset());
		// Still usable: the manager rejected the frame, and injecting bypasses the connection teardown
		// that would follow it in production.
		injector.accepts(stream(STREAM_ID, 5, true, 0));
		assertEquals((Long) 5L, receivePart.finalSize());
	}

	// ---------------------------------------------------------------- RESET_STREAM's declared final size

	@Test
	public void aResetDeclaringAFinalSizePastTheMaximumIsRejected() {
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, MAX_OFFSET + 1));
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, Long.MAX_VALUE));
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, -1));

		assertEquals("and none of them opened the stream they named", 0,
			injector.manager().openStreamCount());
	}

	@Test
	public void aResetDeclaringExactlyTheMaximumPassesTheEncodingBound() {
		// It cannot be *accepted* — no endpoint advertises a 2^62-1 byte window — but the reason must be
		// the flow-control limit it exceeds, not the encoding bound it satisfies.
		QuicTransportException e = injector.rejects(
			new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, MAX_OFFSET));

		assertEquals(QuicTransportErrors.FLOW_CONTROL_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- the other 62-bit bound this layer owns

	@Test
	public void aStreamCountAboveTwoToTheSixtyIsRejected() {
		// RFC 9000 §19.11: not an offset, but the same family of check — a count this high could name a
		// stream identifier that does not exist. The codec leaves it here deliberately, since it is the
		// meaning of the number and not its syntax that is wrong.
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new MaxStreamsFrame((1L << 60) + 1, QuicStreamLimitType.BIDIRECTIONAL));
	}

	// ---------------------------------------------------------------- the two layers below, on their own

	@Test
	public void theReceivingPartRepeatsTheCheckBecauseItIsReachableOnItsOwn() {
		ReceivePart part = new ReceivePart(STREAM_ID, 1024, 32, null);

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> part.onStreamFrame(Long.MAX_VALUE, false, bytes(4)));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, e.errorCode());

		QuicTransportException reset = assertThrows(QuicTransportException.class,
			() -> part.onResetStream(APP_ERROR_CODE, MAX_OFFSET + 1));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, reset.errorCode());

		assertEquals("and the buffer of the refused frame was released, not retained", 0,
			part.highestOffsetReceived());
		part.closeEx(new QuicTransportException(QuicTransportErrors.NO_ERROR, "done"));
	}

	@Test
	public void theReassemblerRepeatsTheCheckBecauseItIsReachableOnItsOwn() {
		StreamReassembler reassembler = new StreamReassembler(32);

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(Long.MAX_VALUE, bytes(4)));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, e.errorCode());
		assertEquals(0, reassembler.pendingRanges());
		assertEquals(0, reassembler.bufferedPieces());

		reassembler.close();
	}

	// ---------------------------------------------------------------- the peer-visible half

	/**
	 * An <i>offset</i> past 2^62-1 cannot be put on the wire at all: {@code QuicVarInts} refuses to
	 * encode one, which is the wire-level half of the same rule and the reason the manager's check
	 * defends a path no conforming encoder can produce. The peer-visible half of
	 * {@code FRAME_ENCODING_ERROR} is therefore asserted with the other 62-bit bound this layer owns,
	 * which <em>is</em> encodable.
	 */
	@Test
	public void anEncodingViolationClosesTheConnectionWithFrameEncodingError() throws Exception {
		assertThrows("2^62 is not an encodable variable-length integer (RFC 9000 §16)",
			IllegalArgumentException.class,
			() -> QuicVarInts.encodedLength(MAX_OFFSET + 1));

		injector.sendOverTheWire(new MaxStreamsFrame((1L << 60) + 1, QuicStreamLimitType.BIDIRECTIONAL));

		injector.assertConnectionClosedWith(QuicTransportErrors.FRAME_ENCODING_ERROR);
	}
}
