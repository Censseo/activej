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
import io.activej.common.exception.TruncatedDataException;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 9000 §16 and Appendix A.1 test vectors for the QUIC variable-length integer encoding.
 */
public class QuicVarIntsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void decodesRfc9000AppendixA1Vectors() throws TruncatedDataException {
		assertDecodes(0L, 0x00);
		assertDecodes(37L, 0x25);
		assertDecodes(15293L, 0x7b, 0xbd);
		assertDecodes(494878333L, 0x9d, 0x7f, 0x3e, 0x7d);
		assertDecodes(151288809941952652L, 0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c);
	}

	@Test
	public void decodesNonMinimalForms() throws TruncatedDataException {
		// 37 re-encoded with a 2-byte prefix instead of the minimal 1-byte form
		assertDecodes(37L, 0x40, 0x25);
		// 63 re-encoded with a 2-byte prefix instead of the minimal 1-byte form
		assertDecodes(63L, 0x40, 0x3f);
	}

	@Test
	public void decodesMaxValue() throws TruncatedDataException {
		assertDecodes(0x3FFFFFFFFFFFFFFFL, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff);
	}

	@Test
	public void encodesMinimalFormAtBoundaries() {
		assertEncodesMinimal(0L, 1);
		assertEncodesMinimal(63L, 1);
		assertEncodesMinimal(64L, 2);
		assertEncodesMinimal(16383L, 2);
		assertEncodesMinimal(16384L, 4);
		assertEncodesMinimal(1073741823L, 4);
		assertEncodesMinimal(1073741824L, 8);
		assertEncodesMinimal(0x3FFFFFFFFFFFFFFFL, 8);
	}

	@Test
	public void roundTripsThroughEncodeAndDecode() throws TruncatedDataException {
		long[] values = {0L, 1L, 63L, 64L, 16383L, 16384L, 1073741823L, 1073741824L, 0x3FFFFFFFFFFFFFFFL};
		for (long value : values) {
			ByteBuf buf = ByteBuf.wrapForWriting(new byte[8]);
			QuicVarInts.write(buf, value);
			assertEquals(QuicVarInts.encodedLength(value), buf.readRemaining());
			assertEquals(value, QuicVarInts.read(buf));
			assertFalse(buf.canRead());
			buf.recycle();
		}
	}

	@Test(expected = TruncatedDataException.class)
	public void throwsOnEmptyInput() throws TruncatedDataException {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[0]);
		try {
			QuicVarInts.read(buf);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = TruncatedDataException.class)
	public void throwsOnTruncatedMultiByteForm() throws TruncatedDataException {
		// 0xc2 announces an 8-byte form, but only 3 bytes follow
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {(byte) 0xc2, 0x19, 0x7c});
		try {
			QuicVarInts.read(buf);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnNegativeEncode() {
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[8]);
		try {
			QuicVarInts.write(buf, -1L);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnTooLargeEncode() {
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[8]);
		try {
			QuicVarInts.write(buf, 0x4000000000000000L);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnEncodedLengthOutOfRange() {
		QuicVarInts.encodedLength(-1L);
	}

	private static void assertDecodes(long expected, int... bytes) throws TruncatedDataException {
		byte[] raw = new byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			raw[i] = (byte) bytes[i];
		}
		ByteBuf buf = ByteBuf.wrapForReading(raw);
		assertEquals(expected, QuicVarInts.read(buf));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	private static void assertEncodesMinimal(long value, int expectedLength) {
		assertEquals(expectedLength, QuicVarInts.encodedLength(value));
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[8]);
		QuicVarInts.write(buf, value);
		assertEquals(expectedLength, buf.readRemaining());
		buf.recycle();
	}
}
