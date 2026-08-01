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

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Round-trips every {@link Http3Frame} subtype through {@link Http3Frames#write}/
 * {@link Http3FrameReader#feed}, and asserts {@link Http3Frame#encodedLength()} is exactly the
 * byte count {@link Http3Frame#writeTo} emits — a mismatch between the two is silent corruption.
 */
public class Http3FramesTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_FRAME_SIZE = 16 * 1024;

	private final Random random = new Random(4831);

	@Test
	public void dataFrameRoundTrips() throws Http3Exception {
		ByteBuf original = randomPayload(23);
		byte[] expected = snapshot(original);
		DataFrame frame = new DataFrame(original);

		Http3Frame decoded = roundTrip(frame);

		assertTrue(decoded instanceof DataFrame);
		assertArrayEquals(expected, snapshot(((DataFrame) decoded).data));
		frame.recycle();
		((Recyclable) decoded).recycle();
	}

	@Test
	public void zeroLengthDataFrameRoundTrips() throws Http3Exception {
		DataFrame frame = new DataFrame(ByteBuf.empty());
		Http3Frame decoded = roundTrip(frame);
		assertTrue(decoded instanceof DataFrame);
		assertEquals(0, ((DataFrame) decoded).data.readRemaining());
		((Recyclable) decoded).recycle();
	}

	@Test
	public void headersFrameRoundTrips() throws Http3Exception {
		ByteBuf original = randomPayload(11);
		byte[] expected = snapshot(original);
		HeadersFrame frame = new HeadersFrame(original);

		Http3Frame decoded = roundTrip(frame);

		assertTrue(decoded instanceof HeadersFrame);
		assertArrayEquals(expected, snapshot(((HeadersFrame) decoded).fieldSection));
		frame.recycle();
		((Recyclable) decoded).recycle();
	}

	@Test
	public void settingsFrameRoundTrips() throws Http3Exception {
		SettingsFrame frame = new SettingsFrame(
			new long[] {0x01, 0x06, 0x07},
			new long[] {0, 64 * 1024, 0});

		Http3Frame decoded = roundTrip(frame);

		assertEquals(frame, decoded);
	}

	@Test
	public void emptySettingsFrameRoundTrips() throws Http3Exception {
		SettingsFrame frame = new SettingsFrame(new long[0], new long[0]);
		Http3Frame decoded = roundTrip(frame);
		assertEquals(frame, decoded);
	}

	@Test
	public void goAwayFrameRoundTrips() throws Http3Exception {
		GoAwayFrame frame = new GoAwayFrame(12345);
		Http3Frame decoded = roundTrip(frame);
		assertEquals(frame, decoded);
	}

	@Test
	public void cancelPushFrameRoundTrips() throws Http3Exception {
		CancelPushFrame frame = new CancelPushFrame(9);
		Http3Frame decoded = roundTrip(frame);
		assertEquals(frame, decoded);
	}

	@Test
	public void maxPushIdFrameRoundTrips() throws Http3Exception {
		MaxPushIdFrame frame = new MaxPushIdFrame(777);
		Http3Frame decoded = roundTrip(frame);
		assertEquals(frame, decoded);
	}

	@Test
	public void unknownFrameHeaderIsSelfSizing() {
		// UnknownFrame never buffers a payload (it is a decode-side classification result), so
		// its own writeTo/encodedLength contract covers only the Type/Length header it writes.
		UnknownFrame frame = new UnknownFrame(0x21, 40);
		ByteBuf buf = encode(frame);
		assertEquals(frame.encodedLength(), buf.readRemaining());
		buf.recycle();
	}

	// ---- helpers ----

	private Http3Frame roundTrip(Http3Frame frame) throws Http3Exception {
		ByteBuf encoded = encode(frame);
		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame decoded = reader.feed(encoded);
		assertNotNull("a whole buffer must decode to a complete frame in one feed() call", decoded);
		assertFalse("the reader must consume every byte belonging to the frame", encoded.canRead());
		encoded.recycle();
		return decoded;
	}

	private static ByteBuf encode(Http3Frame frame) {
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		Http3Frames.write(buf, frame);
		assertEquals(Http3Frames.encodedLength(frame), buf.readRemaining());
		return buf;
	}

	private ByteBuf randomPayload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		buf.put(bytes);
		return buf;
	}

	private static byte[] snapshot(ByteBuf buf) {
		return Arrays.copyOfRange(buf.array(), buf.head(), buf.tail());
	}
}
