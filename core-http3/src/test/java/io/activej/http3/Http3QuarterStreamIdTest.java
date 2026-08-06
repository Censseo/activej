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
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T118 / spec FR-080, FR-081, FR-082: the quarter stream ID of an HTTP/3 datagram (RFC 9297 §2.1).
 * <p>
 * Three illegal cases close the connection with {@code H3_DATAGRAM_ERROR} (0x33): a stream ID that is
 * not client-initiated bidirectional, a quarter stream ID whose {@code × 4} would exceed 2^62−1, and a
 * truncated varint. The three benign ones — a completed, reset or not-yet-opened stream — are not this
 * codec's business at all: it maps and validates identifiers, and knows nothing about stream state, so
 * the caller drops and counts.
 */
public class Http3QuarterStreamIdTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// ---------------------------------------------------------------- the mapping

	@Test
	public void mapsAClientInitiatedBidirectionalStreamIdBothWays() throws Http3Exception {
		for (long streamId : new long[]{0, 4, 8, 400, 1L << 40, Http3QuarterStreamId.MAX_STREAM_ID}) {
			long quarterStreamId = Http3QuarterStreamId.encode(streamId);
			assertEquals(streamId / 4, quarterStreamId);
			assertEquals(streamId, Http3QuarterStreamId.decode(quarterStreamId));
		}
	}

	@Test
	public void theLargestMappableStreamIdIsFourTimesTheLargestQuarterStreamId() throws Http3Exception {
		assertEquals((1L << 60) - 1, Http3QuarterStreamId.MAX_VALUE);
		assertEquals(QuicVarInts.MAX_VALUE - 3, Http3QuarterStreamId.MAX_STREAM_ID);
		assertEquals(Http3QuarterStreamId.MAX_STREAM_ID, Http3QuarterStreamId.decode(Http3QuarterStreamId.MAX_VALUE));
		assertEquals(Http3QuarterStreamId.MAX_VALUE, Http3QuarterStreamId.encode(Http3QuarterStreamId.MAX_STREAM_ID));
	}

	// ---------------------------------------------------------------- illegal case (a)

	@Test
	public void aStreamIdThatIsNotClientInitiatedBidirectionalIsADatagramError() {
		// RFC 9000 §2.1: 0x1 server-initiated bidirectional, 0x2 client-initiated unidirectional,
		// 0x3 server-initiated unidirectional. Only IDs ≡ 0 (mod 4) have a quarter stream ID.
		for (long streamId : new long[]{1, 2, 3, 5, 6, 7, 4001, (1L << 40) + 2}) {
			Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.encode(streamId));
			assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
			assertTrue(e.isConnectionScoped());
			Http3Exception validated =
				assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.validateStreamId(streamId));
			assertEquals(Http3Errors.H3_DATAGRAM_ERROR, validated.errorCode());
		}
	}

	@Test
	public void aStreamIdOutsideTheVarintRangeIsADatagramError() {
		for (long streamId : new long[]{-4, -1, QuicVarInts.MAX_VALUE + 1, Long.MIN_VALUE}) {
			Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.encode(streamId));
			assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
		}
	}

	// ---------------------------------------------------------------- illegal case (b)

	@Test
	public void aQuarterStreamIdWhoseFourfoldOverflowsIsADatagramError() {
		for (long quarterStreamId : new long[]{
			Http3QuarterStreamId.MAX_VALUE + 1, 1L << 61, QuicVarInts.MAX_VALUE}) {
			Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.decode(quarterStreamId));
			assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
			assertTrue(e.isConnectionScoped());
		}
	}

	@Test
	public void anOverflowingQuarterStreamIdOnTheWireIsADatagramError() {
		ByteBuf payload = ByteBufPool.allocate(16);
		QuicVarInts.write(payload, Http3QuarterStreamId.MAX_VALUE + 1);
		payload.put(new byte[]{1, 2, 3});

		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.read(payload));
		assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
		// Unlike a truncated varint, the range check fires only after QuicVarInts.read has already
		// consumed the (well-formed) varint — so, unlike the truncated case below, the head has moved.
		// No caller relies on this; the class Javadoc says so explicitly.
		assertEquals(3, payload.readRemaining());
		payload.recycle();
	}

	@Test
	public void theOverflowIsRejectedWithoutEverMultiplying() throws Http3Exception {
		// (2^62-1)/4 × 4 fits; one more would wrap negative if the check ran after the multiplication.
		assertTrue(Http3QuarterStreamId.decode(Http3QuarterStreamId.MAX_VALUE) > 0);
		Http3Exception e =
			assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.decode((1L << 62) - 1));
		assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- illegal case (c)

	@Test
	public void aTruncatedQuarterStreamIdVarintIsADatagramError() {
		ByteBuf empty = ByteBufPool.allocate(8);
		Http3Exception onEmpty = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.read(empty));
		assertEquals(Http3Errors.H3_DATAGRAM_ERROR, onEmpty.errorCode());
		assertTrue(onEmpty.isConnectionScoped());
		empty.recycle();

		// 0xC0 declares an eight-byte varint; only three bytes follow.
		ByteBuf truncated = ByteBufPool.allocate(8);
		truncated.put(new byte[]{(byte) 0xC0, 0x00, 0x00, 0x01});
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.read(truncated));
		assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
		assertEquals("a failed read leaves the payload where it found it", 4, truncated.readRemaining());
		truncated.recycle();
	}

	// ---------------------------------------------------------------- the wire form

	@Test
	public void readReturnsTheStreamIdAndLeavesThePayloadAtItsFirstByte() throws Http3Exception {
		byte[] applicationPayload = {9, 8, 7, 6, 5};
		ByteBuf buf = ByteBufPool.allocate(32);
		Http3QuarterStreamId.write(buf, 400);
		buf.put(applicationPayload);

		assertEquals(400, Http3QuarterStreamId.read(buf));

		byte[] remaining = new byte[buf.readRemaining()];
		buf.read(remaining);
		assertArrayEquals(applicationPayload, remaining);
		buf.recycle();
	}

	@Test
	public void aZeroLengthPayloadIsLegalAndStaysZeroLength() throws Http3Exception {
		ByteBuf buf = ByteBufPool.allocate(16);
		Http3QuarterStreamId.write(buf, 8);

		assertEquals(8, Http3QuarterStreamId.read(buf));
		assertEquals(0, buf.readRemaining());
		buf.recycle();
	}

	@Test
	public void encodedLengthIsExactlyWhatWriteEmits() throws Http3Exception {
		for (long streamId : new long[]{0, 4, 252, 256, 4 * 0x3FFF, 4 * 0x40000, Http3QuarterStreamId.MAX_STREAM_ID}) {
			ByteBuf buf = ByteBufPool.allocate(16);
			int expected = Http3QuarterStreamId.encodedLength(streamId);
			Http3QuarterStreamId.write(buf, streamId);
			assertEquals(expected, buf.readRemaining());
			assertEquals(streamId, Http3QuarterStreamId.read(buf));
			buf.recycle();
		}
	}

	@Test
	public void writeRefusesAStreamIdThatIsNotClientInitiatedBidirectional() {
		ByteBuf buf = ByteBufPool.allocate(16);
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3QuarterStreamId.write(buf, 3));
		assertEquals(Http3Errors.H3_DATAGRAM_ERROR, e.errorCode());
		assertEquals(0, buf.readRemaining());
		buf.recycle();
	}

	@Test
	public void aNonMinimalVarintDecodesToTheSameStreamId() throws Http3Exception {
		// RFC 9000 §16: a decoder accepts any legal length for a value. 0x4000 | 1 is a two-byte 1.
		ByteBuf buf = ByteBufPool.allocate(16);
		buf.put(new byte[]{0x40, 0x01});

		assertEquals(4, Http3QuarterStreamId.read(buf));
		buf.recycle();
	}

	// ---------------------------------------------------------------- what it deliberately does not validate

	@Test
	public void mapsWithoutConsultingStreamState() throws Http3Exception {
		// FR-082's three benign cases — completed, reset, not yet opened — are stream *state*, which this
		// codec cannot see and must not guess at. A well-formed identifier maps, and the caller decides
		// whether to drop and count.
		for (long streamId : new long[]{0, 4_000_000}) {
			ByteBuf buf = ByteBufPool.allocate(16);
			Http3QuarterStreamId.write(buf, streamId);
			assertEquals(streamId, Http3QuarterStreamId.read(buf));
			buf.recycle();
		}
	}
}
