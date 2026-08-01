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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.recycle.Recyclable;
import io.activej.http3.Http3Exception;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * Feeding a frame one octet at a time must yield the same decoded frame as feeding it as one
 * whole buffer — including at the trickiest resumption points, the type/length and length/payload
 * field boundaries (FR-027, SC-011).
 */
public class Http3FrameReaderFragmentationTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_FRAME_SIZE = 16 * 1024;

	private final Random random = new Random(2601);

	@Test
	public void dataFrameFragmentedByteByByte() throws Http3Exception {
		ByteBuf original = randomPayload(19);
		DataFrame frame = new DataFrame(original);
		Http3Frame wholeBuffer = decodeWhole(frame);
		Http3Frame byteAtATime = decodeOneByteAtATime(frame);

		assertEquals(wholeBuffer, byteAtATime);

		frame.recycle();
		((Recyclable) wholeBuffer).recycle();
		((Recyclable) byteAtATime).recycle();
	}

	@Test
	public void settingsFrameFragmentedByteByByte() throws Http3Exception {
		SettingsFrame frame = new SettingsFrame(new long[] {0x01, 0x06}, new long[] {0, 16384});
		assertEquals(decodeWhole(frame), decodeOneByteAtATime(frame));
	}

	@Test
	public void goAwayFrameFragmentedByteByByte() throws Http3Exception {
		// A multi-byte varint id forces both the Type/Length and Length/Payload boundaries to
		// fall mid-varint under one-byte-at-a-time feeding.
		GoAwayFrame frame = new GoAwayFrame(70_000);
		assertEquals(decodeWhole(frame), decodeOneByteAtATime(frame));
	}

	@Test
	public void cancelPushFrameFragmentedByteByByte() throws Http3Exception {
		CancelPushFrame frame = new CancelPushFrame(70_000);
		assertEquals(decodeWhole(frame), decodeOneByteAtATime(frame));
	}

	@Test
	public void maxPushIdFrameFragmentedByteByByte() throws Http3Exception {
		MaxPushIdFrame frame = new MaxPushIdFrame(70_000);
		assertEquals(decodeWhole(frame), decodeOneByteAtATime(frame));
	}

	@Test
	public void headersFrameFragmentedByteByByte() throws Http3Exception {
		// The QPACK-bearing frame type: Phase 3's QpackDecodeFragmentationTest explicitly delegates
		// wire-boundary resumability for QPACK-carrying bytes to this reader's HeadersFrame handling
		// (a HEADERS frame's payload is reassembled to its declared length before QPACK ever sees
		// it) -- this is the test that actually proves that delegation holds.
		ByteBuf original = randomPayload(37);
		HeadersFrame frame = new HeadersFrame(original);
		Http3Frame wholeBuffer = decodeWhole(frame);
		Http3Frame byteAtATime = decodeOneByteAtATime(frame);

		assertEquals(wholeBuffer, byteAtATime);

		frame.recycle();
		((Recyclable) wholeBuffer).recycle();
		((Recyclable) byteAtATime).recycle();
	}

	@Test
	public void unknownFrameFragmentedByteByByte() throws Http3Exception {
		ByteBuf encoded = ByteBufPool.allocate(8);
		io.activej.quic.codec.QuicVarInts.write(encoded, 0x21); // GREASE type
		io.activej.quic.codec.QuicVarInts.write(encoded, 5);    // declared length
		encoded.put(new byte[] {1, 2, 3, 4, 5});

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame result = null;
		while (encoded.canRead()) {
			ByteBuf oneByte = ByteBufPool.allocate(1);
			oneByte.put(encoded.readByte());
			Http3Frame frame = reader.feed(oneByte);
			oneByte.recycle();
			if (frame != null) {
				assertFalse(encoded.canRead());
				result = frame;
			}
		}
		encoded.recycle();

		assertNotNull(result);
		assertTrue(result instanceof UnknownFrame);
		assertEquals(0x21, ((UnknownFrame) result).type());
		assertEquals(5, ((UnknownFrame) result).declaredLength);
	}

	@Test
	public void fragmentationSplitExactlyAtTypeLengthBoundary() throws Http3Exception {
		GoAwayFrame frame = new GoAwayFrame(5);
		ByteBuf whole = encode(frame);
		int typeLength = io.activej.quic.codec.QuicVarInts.encodedLength(GoAwayFrame.TYPE);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		ByteBuf firstPart = whole.slice(typeLength);
		whole.moveHead(typeLength);
		assertNull(reader.feed(firstPart));
		firstPart.recycle();

		Http3Frame decoded = reader.feed(whole);
		assertNotNull(decoded);
		assertEquals(frame, decoded);
		whole.recycle();
	}

	@Test
	public void fragmentationSplitExactlyAtLengthPayloadBoundary() throws Http3Exception {
		GoAwayFrame frame = new GoAwayFrame(70_000);
		ByteBuf whole = encode(frame);
		int headerLength = io.activej.quic.codec.QuicVarInts.encodedLength(GoAwayFrame.TYPE)
			+ io.activej.quic.codec.QuicVarInts.encodedLength(io.activej.quic.codec.QuicVarInts.encodedLength(frame.id));

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		ByteBuf headerPart = whole.slice(headerLength);
		whole.moveHead(headerLength);
		assertNull(reader.feed(headerPart));
		headerPart.recycle();

		Http3Frame decoded = reader.feed(whole);
		assertNotNull(decoded);
		assertEquals(frame, decoded);
		whole.recycle();
	}

	// ---- helpers ----

	private Http3Frame decodeWhole(Http3Frame frame) throws Http3Exception {
		ByteBuf encoded = encode(frame);
		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame decoded = reader.feed(encoded);
		assertNotNull(decoded);
		assertFalse(encoded.canRead());
		encoded.recycle();
		return decoded;
	}

	private Http3Frame decodeOneByteAtATime(Http3Frame frame) throws Http3Exception {
		ByteBuf encoded = encode(frame);
		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame result = null;
		while (encoded.canRead()) {
			ByteBuf oneByte = ByteBufPool.allocate(1);
			oneByte.put(encoded.readByte());
			Http3Frame decoded = reader.feed(oneByte);
			oneByte.recycle();
			if (decoded != null) {
				result = decoded;
			}
		}
		encoded.recycle();
		assertNotNull("expected a complete frame once every byte was fed", result);
		return result;
	}

	private static ByteBuf encode(Http3Frame frame) {
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		Http3Frames.write(buf, frame);
		return buf;
	}

	private ByteBuf randomPayload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		buf.put(bytes);
		return buf;
	}
}
