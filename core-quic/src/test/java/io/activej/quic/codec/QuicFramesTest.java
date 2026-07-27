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

package io.activej.quic.codec;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Round-trip identity and malformed/truncated-input error paths for every frame type in
 * data-model.md (spec US1 acceptance scenarios).
 */
public class QuicFramesTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Random random = new Random(917);

	// ---- scalar frame round trips ----

	@Test
	public void roundTripsPadding() throws Exception {
		for (int count : new int[] {1, 2, 37}) {
			assertScalarRoundTrip(new PaddingFrame(count));
		}
	}

	@Test
	public void paddingStopsAtTheFirstNonZeroByteAndLeavesItForTheNextFrame() throws Exception {
		// Three 0x00 padding bytes followed immediately by a PING frame (0x01), all in one
		// packet payload: PaddingFrame must consume exactly the three zero bytes, leaving the
		// cursor positioned exactly at the start of the next frame.
		ByteBuf buf = ByteBufPool.allocate(8);
		buf.put(new byte[] {0, 0, 0});
		QuicFrames.write(buf, PingFrame.INSTANCE);

		QuicFrame first = QuicFrames.read(buf);
		assertTrue(first instanceof PaddingFrame);
		assertEquals(3, ((PaddingFrame) first).count);

		QuicFrame second = QuicFrames.read(buf);
		assertSame(PingFrame.INSTANCE, second);
		assertFalse(buf.canRead());

		buf.recycle();
	}

	@Test
	public void roundTripsPing() throws Exception {
		assertScalarRoundTrip(PingFrame.INSTANCE);
	}

	@Test
	public void roundTripsHandshakeDone() throws Exception {
		assertScalarRoundTrip(HandshakeDoneFrame.INSTANCE);
	}

	@Test
	public void roundTripsResetStream() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertScalarRoundTrip(new ResetStreamFrame(randomVarInt(), randomVarInt(), randomVarInt()));
		}
	}

	@Test
	public void roundTripsStopSending() throws Exception {
		assertScalarRoundTrip(new StopSendingFrame(randomVarInt(), randomVarInt()));
	}

	@Test
	public void roundTripsMaxData() throws Exception {
		assertScalarRoundTrip(new MaxDataFrame(randomVarInt()));
	}

	@Test
	public void roundTripsMaxStreamData() throws Exception {
		assertScalarRoundTrip(new MaxStreamDataFrame(randomVarInt(), randomVarInt()));
	}

	@Test
	public void roundTripsMaxStreamsIncludingThe2Pow60Bound() throws Exception {
		assertScalarRoundTrip(new MaxStreamsFrame(randomVarInt(), QuicStreamLimitType.BIDIRECTIONAL));
		assertScalarRoundTrip(new MaxStreamsFrame(randomVarInt(), QuicStreamLimitType.UNIDIRECTIONAL));
		assertScalarRoundTrip(new MaxStreamsFrame(1L << 60, QuicStreamLimitType.BIDIRECTIONAL));
	}

	@Test
	public void roundTripsDataBlocked() throws Exception {
		assertScalarRoundTrip(new DataBlockedFrame(randomVarInt()));
	}

	@Test
	public void roundTripsStreamDataBlocked() throws Exception {
		assertScalarRoundTrip(new StreamDataBlockedFrame(randomVarInt(), randomVarInt()));
	}

	@Test
	public void roundTripsStreamsBlocked() throws Exception {
		assertScalarRoundTrip(new StreamsBlockedFrame(randomVarInt(), QuicStreamLimitType.BIDIRECTIONAL));
		assertScalarRoundTrip(new StreamsBlockedFrame(randomVarInt(), QuicStreamLimitType.UNIDIRECTIONAL));
	}

	@Test
	public void roundTripsNewConnectionId() throws Exception {
		SecureRandom secureRandom = new SecureRandom();
		for (int len : new int[] {1, 8, 20}) {
			QuicConnectionId cid = QuicConnectionId.random(len, secureRandom);
			byte[] token = new byte[16];
			random.nextBytes(token);
			assertScalarRoundTrip(new NewConnectionIdFrame(randomVarInt(), randomVarInt(), cid, token));
		}
	}

	@Test
	public void roundTripsRetireConnectionId() throws Exception {
		assertScalarRoundTrip(new RetireConnectionIdFrame(randomVarInt()));
	}

	@Test
	public void roundTripsPathChallengeAndResponse() throws Exception {
		byte[] data = new byte[8];
		random.nextBytes(data);
		assertScalarRoundTrip(new PathChallengeFrame(data));
		random.nextBytes(data);
		assertScalarRoundTrip(new PathResponseFrame(data));
	}

	@Test
	public void roundTripsConnectionCloseTransportAndApplication() throws Exception {
		assertScalarRoundTrip(ConnectionCloseFrame.transport(0x0aL, 0x06L, "bad crypto frame".getBytes()));
		assertScalarRoundTrip(ConnectionCloseFrame.transport(0L, 0L, new byte[0]));
		assertScalarRoundTrip(ConnectionCloseFrame.application(0x01L, "goodbye".getBytes()));
	}

	@Test
	public void roundTripsAckWithoutEcn() throws Exception {
		long largest = 1000;
		long firstRange = 50;
		long[] gaps = {2, 5};
		long[] lengths = {10, 20};
		assertScalarRoundTrip(AckFrame.withoutEcn(largest, 12345, firstRange, gaps, lengths));
	}

	@Test
	public void roundTripsAckWithEcn() throws Exception {
		long largest = 500;
		long firstRange = 10;
		long[] gaps = {1};
		long[] lengths = {5};
		assertScalarRoundTrip(AckFrame.withEcn(largest, 99, firstRange, gaps, lengths, 3, 4, 5));
	}

	@Test
	public void roundTripsAckWithNoAdditionalRanges() throws Exception {
		assertScalarRoundTrip(AckFrame.withoutEcn(42, 0, 0, new long[0], new long[0]));
	}

	@Test
	public void ackFrameDefensivelyCopiesGapsAndRangeLengths() {
		long[] gaps = {2, 5};
		long[] rangeLengths = {10, 20};
		AckFrame frame = AckFrame.withoutEcn(1000, 12345, 50, gaps, rangeLengths);

		gaps[0] = 999;
		rangeLengths[0] = 999;

		assertArrayEquals(new long[] {2, 5}, frame.gaps());
		assertArrayEquals(new long[] {10, 20}, frame.rangeLengths());
		assertArrayEquals(new long[] {2, 5}, frame.gaps);
		assertArrayEquals(new long[] {10, 20}, frame.rangeLengths);
	}

	// ---- payload-carrying frame round trips ----

	@Test
	public void roundTripsCrypto() throws Exception {
		assertPayloadRoundTrip(payload -> new CryptoFrame(randomVarInt(), payload));
	}

	@Test
	public void roundTripsNewToken() throws Exception {
		assertPayloadRoundTrip(NewTokenFrame::new);
	}

	@Test
	public void roundTripsDatagram() throws Exception {
		assertPayloadRoundTrip(DatagramFrame::new);
	}

	@Test
	public void roundTripsStreamOffLenFinCombinations() throws Exception {
		for (boolean fin : new boolean[] {false, true}) {
			for (long offset : new long[] {0, 777}) {
				ByteBuf originalPayload = randomPayload(16);
				byte[] expectedBytes = snapshot(originalPayload);
				StreamFrame frame = new StreamFrame(randomVarInt(), offset, fin, originalPayload);

				ByteBuf encoded = encode(frame);
				QuicFrame decoded = QuicFrames.read(encoded);
				assertFalse(encoded.canRead());

				assertTrue(decoded instanceof StreamFrame);
				StreamFrame decodedStream = (StreamFrame) decoded;
				assertEquals(frame.streamId, decodedStream.streamId);
				assertEquals(offset, decodedStream.offset);
				assertEquals(fin, decodedStream.fin);
				assertArrayEquals(expectedBytes, snapshot(decodedStream.data));

				originalPayload.recycle();
				decodedStream.recycle();
				encoded.recycle();
			}
		}
	}

	@Test
	public void decodesStreamFrameWithoutLenBitExtendingToEndOfPacket() throws Exception {
		// type 0x08 (STREAM, no OFF/LEN/FIN bits) + streamId varint(5) + raw trailing data
		byte[] trailing = {10, 20, 30, 40};
		ByteBuf buf = ByteBufPool.allocate(16);
		buf.writeByte((byte) StreamFrame.TYPE_BASE);
		QuicVarInts.write(buf, 5);
		buf.put(trailing);

		QuicFrame decoded = QuicFrames.read(buf);
		assertFalse(buf.canRead());
		assertTrue(decoded instanceof StreamFrame);
		StreamFrame stream = (StreamFrame) decoded;
		assertEquals(5, stream.streamId);
		assertEquals(0, stream.offset);
		assertFalse(stream.fin);
		assertArrayEquals(trailing, snapshot(stream.data));

		stream.recycle();
		buf.recycle();
	}

	@Test
	public void decodesDatagramFrameWithoutLengthExtendingToEndOfPacket() throws Exception {
		byte[] trailing = {1, 2, 3};
		ByteBuf buf = ByteBufPool.allocate(8);
		buf.writeByte((byte) DatagramFrame.TYPE_WITHOUT_LENGTH);
		buf.put(trailing);

		QuicFrame decoded = QuicFrames.read(buf);
		assertFalse(buf.canRead());
		DatagramFrame datagram = (DatagramFrame) decoded;
		assertArrayEquals(trailing, snapshot(datagram.payload));

		datagram.recycle();
		buf.recycle();
	}

	@Test
	public void payloadFrameSliceSurvivesInputBufferRecycle() throws Exception {
		ByteBuf originalPayload = randomPayload(37);
		byte[] expectedBytes = snapshot(originalPayload);
		CryptoFrame frame = new CryptoFrame(0, originalPayload);

		ByteBuf encoded = encode(frame);
		CryptoFrame decoded = (CryptoFrame) QuicFrames.read(encoded);

		// Recycling the packet buffer must not corrupt the frame's owned slice: the slice
		// holds its own reference (ByteBuf.slice() increments refs), so the underlying pooled
		// array is only returned once every reference — including this one — is recycled.
		encoded.recycle();
		assertArrayEquals(expectedBytes, snapshot(decoded.payload));

		originalPayload.recycle();
		decoded.recycle();
	}

	// ---- error paths ----

	@Test
	public void unknownFrameTypeThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(4);
		QuicVarInts.write(buf, 0x20); // reserved, not a defined frame type
		try {
			QuicFrames.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void declaredLengthExceedingRemainingThrowsMalformedDataException() {
		// CRYPTO: type=0x06, offset=0, length=10, but only 3 bytes follow
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, CryptoFrame.TYPE);
		QuicVarInts.write(buf, 0);
		QuicVarInts.write(buf, 10);
		buf.put(new byte[] {1, 2, 3});
		try {
			QuicFrames.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void truncatedFixedFieldThrowsTruncatedDataException() {
		// PATH_CHALLENGE requires 8 bytes of data; only 3 are present
		ByteBuf buf = ByteBufPool.allocate(8);
		QuicVarInts.write(buf, PathChallengeFrame.TYPE);
		buf.put(new byte[] {1, 2, 3});
		try {
			QuicFrames.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (MalformedDataException e) {
			fail("unexpected MalformedDataException: " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void ackFrameWithInconsistentRangesThrowsMalformedDataException() {
		// largestAcked=10, firstAckRange=5 (smallest so far = 5), then a range whose gap (10)
		// is larger than what remains of the packet number space: 5 - 10 - 2 < 0.
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, AckFrame.TYPE_WITHOUT_ECN);
		QuicVarInts.write(buf, 10); // largestAcked
		QuicVarInts.write(buf, 0); // ackDelay
		QuicVarInts.write(buf, 1); // rangeCount
		QuicVarInts.write(buf, 5); // firstAckRange
		QuicVarInts.write(buf, 10); // gap (inconsistent)
		QuicVarInts.write(buf, 0); // rangeLength
		try {
			QuicFrames.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void ackFrameWithRangeCountExceedingRemainingBytesThrowsMalformedDataException() {
		// A huge declared range count must be rejected before allocating the range arrays.
		ByteBuf buf = ByteBufPool.allocate(16);
		QuicVarInts.write(buf, AckFrame.TYPE_WITHOUT_ECN);
		QuicVarInts.write(buf, 10); // largestAcked
		QuicVarInts.write(buf, 0); // ackDelay
		QuicVarInts.write(buf, 1_000_000); // rangeCount: cannot possibly fit in the remaining bytes
		try {
			QuicFrames.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void emptyNewTokenThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(4);
		QuicVarInts.write(buf, NewTokenFrame.TYPE);
		QuicVarInts.write(buf, 0);
		try {
			QuicFrames.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	// ---- helpers ----

	private interface PayloadFrameFactory {
		QuicFrame create(ByteBuf payload);
	}

	private void assertPayloadRoundTrip(PayloadFrameFactory factory) throws Exception {
		ByteBuf originalPayload = randomPayload(23);
		byte[] expectedBytes = snapshot(originalPayload);
		QuicFrame frame = factory.create(originalPayload);

		ByteBuf encoded = encode(frame);
		QuicFrame decoded = QuicFrames.read(encoded);
		assertFalse(encoded.canRead());

		ByteBuf decodedPayload = payloadOf(decoded);
		assertArrayEquals(expectedBytes, snapshot(decodedPayload));

		originalPayload.recycle();
		((io.activej.common.recycle.Recyclable) decoded).recycle();
		encoded.recycle();
	}

	private static ByteBuf payloadOf(QuicFrame frame) {
		if (frame instanceof CryptoFrame f) return f.payload;
		if (frame instanceof NewTokenFrame f) return f.token;
		if (frame instanceof StreamFrame f) return f.data;
		if (frame instanceof DatagramFrame f) return f.payload;
		throw new AssertionError("not a payload frame: " + frame);
	}

	private static byte[] snapshot(ByteBuf buf) {
		return Arrays.copyOfRange(buf.array(), buf.head(), buf.tail());
	}

	private ByteBuf randomPayload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		buf.put(bytes);
		return buf;
	}

	private long randomVarInt() {
		return (random.nextLong() & Long.MAX_VALUE) % (QuicVarInts.MAX_VALUE / 2);
	}

	private static void assertScalarRoundTrip(QuicFrame frame) throws Exception {
		ByteBuf buf = encode(frame);
		QuicFrame decoded = QuicFrames.read(buf);
		assertFalse(buf.canRead());
		assertEquals(frame, decoded);
		buf.recycle();
	}

	private static ByteBuf encode(QuicFrame frame) {
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		QuicFrames.write(buf, frame);
		assertEquals(frame.encodedLength(), buf.readRemaining());
		return buf;
	}
}
