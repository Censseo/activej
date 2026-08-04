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
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 9114 §9's GREASE tolerance: unknown frame types and unknown SETTINGS identifiers of the
 * form {@code 0x1f * N + 0x21} must decode without error rather than being rejected — Chrome
 * sends them on every connection.
 */
public class Http3GreaseToleranceTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_FRAME_SIZE = 16 * 1024;

	private static long grease(int n) {
		return 0x1fL * n + 0x21L;
	}

	@Test
	public void greaseFrameTypesDecodeAsUnknownFrame() throws Http3Exception {
		for (int n = 0; n < 5; n++) {
			long type = grease(n);
			ByteBuf buf = ByteBufPool.allocate(16);
			QuicVarInts.write(buf, type);
			QuicVarInts.write(buf, 3);
			buf.put(new byte[] {9, 9, 9});

			Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
			Http3Frame frame = reader.feed(buf);

			assertNotNull(frame);
			assertTrue(frame instanceof UnknownFrame);
			assertEquals(type, frame.type());
			assertEquals(3, ((UnknownFrame) frame).declaredLength);
			assertFalse(buf.canRead());
			buf.recycle();
		}
	}

	@Test
	public void greaseSettingsIdentifiersAreDecodedNotRejected() throws Http3Exception {
		for (int n = 0; n < 5; n++) {
			long id = grease(n);
			SettingsFrame frame = new SettingsFrame(new long[] {id}, new long[] {42});
			ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
			Http3Frames.write(buf, frame);

			Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
			Http3Frame decoded = reader.feed(buf);

			assertEquals(frame, decoded);
			buf.recycle();
		}
	}

	@Test
	public void unknownNonReservedSettingsIdentifierIsAcceptedNotRejected() throws Http3Exception {
		// 0x0a is not one of the reserved 0x02-0x05 HTTP/2 identifiers, and not one of the three
		// this implementation sends -- an ordinary forward-compatible unknown identifier.
		SettingsFrame frame = new SettingsFrame(new long[] {0x0a}, new long[] {7});
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		Http3Frames.write(buf, frame);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame decoded = reader.feed(buf);

		assertEquals(frame, decoded);
		buf.recycle();
	}

	@Test
	public void zeroLengthDataFrameDecodesAsLegalEmptyFrame() throws Http3Exception {
		DataFrame frame = new DataFrame(ByteBuf.empty());
		ByteBuf buf = ByteBufPool.allocate(frame.encodedLength());
		Http3Frames.write(buf, frame);

		Http3FrameReader reader = new Http3FrameReader(MAX_FRAME_SIZE);
		Http3Frame decoded = reader.feed(buf);

		assertNotNull(decoded);
		assertTrue(decoded instanceof DataFrame);
		assertEquals(0, ((DataFrame) decoded).data.readRemaining());
		// A zero-length DATA frame is a completely ordinary frame value, structurally
		// indistinguishable from any other DataFrame -- no special "end of body" flag exists on
		// this type. That absence is the assertion.
		buf.recycle();
		((DataFrame) decoded).recycle();
	}
}
