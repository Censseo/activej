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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.http3.Http3Errors;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 9204 §4.1.1 / RFC 7541 §5.1 N-bit-prefix integer vectors, exercised at every prefix width
 * this feature actually uses on the wire (contracts/wire-protocol.md §4.2): 8-bit (Required Insert
 * Count), 7-bit (Delta Base / string length), 6-bit (Indexed Field Line index), 4-bit
 * (Literal-with-Name-Reference index), 3-bit (Literal-with-Literal-Name name length). Vectors
 * reproduced from RFC 7541 Appendix C.1 (QPACK's integer encoding is defined by reference to it).
 */
public class QpackIntegersTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// RFC 7541 C.1.1 — 10, 5-bit prefix, no flag bits -> 0x0a
	@Test
	public void rfc7541example_5bitPrefix_smallValue() throws QpackException {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {0x0a});
		assertEquals(10, QpackIntegers.readInteger(buf, 5));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	// RFC 7541 C.1.2 — 1337, 5-bit prefix, no flag bits -> 0x1f 0x9a 0x0a
	@Test
	public void rfc7541example_5bitPrefix_continuation() throws QpackException {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {0x1f, (byte) 0x9a, 0x0a});
		assertEquals(1337, QpackIntegers.readInteger(buf, 5));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	// RFC 7541 C.1.3 — 42, 8-bit prefix (whole octet, no flag bits) -> 0x2a
	@Test
	public void rfc7541example_8bitPrefix() throws QpackException {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {0x2a});
		assertEquals(42, QpackIntegers.readInteger(buf, 8));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	@Test
	public void roundTripsAcrossEveryWireWidthAndBoundaryValues() throws QpackException {
		int[] prefixWidths = {8, 7, 6, 4, 3, 1};
		long[] values = {0, 1, 30, 62, 126, 127, 128, 129, 1000, 1337, 100_000, Integer.MAX_VALUE, QpackIntegers.MAX_VALUE};
		for (int prefixBits : prefixWidths) {
			for (long value : values) {
				ByteBuf buf = ByteBufPool.allocate(16);
				QpackIntegers.writeInteger(buf, prefixBits, 0, value);
				int written = buf.readRemaining();
				assertEquals("prefixBits=" + prefixBits + " value=" + value,
					QpackIntegers.encodedLength(prefixBits, value), written);
				long decoded = QpackIntegers.readInteger(buf, prefixBits);
				assertEquals("prefixBits=" + prefixBits + " value=" + value, value, decoded);
				assertFalse(buf.canRead());
				buf.recycle();
			}
		}
	}

	@Test
	public void flagBitsSharingTheFirstByteAreIgnoredByRead() throws QpackException {
		// Indexed Field Line, static: top bits "11", 6-bit prefix. flags=0xC0, value=5 -> 0xC5.
		ByteBuf buf = ByteBufPool.allocate(4);
		QpackIntegers.writeInteger(buf, 6, 0xC0, 5);
		assertEquals(1, buf.readRemaining());
		assertEquals((byte) 0xC5, buf.array()[buf.head()]);
		assertEquals(5, QpackIntegers.readInteger(buf, 6));
		buf.recycle();
	}

	@Test
	public void emptyBufferIsTruncated() {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[0]);
		QpackException e = assertThrows(QpackException.class, () -> QpackIntegers.readInteger(buf, 6));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		buf.recycle();
	}

	@Test
	public void truncatedContinuationIsRejected() {
		// prefix marker (6-bit, all ones = 0x3f) with a continuation byte declaring "more follows"
		// but nothing after it.
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {0x3f, (byte) 0x80});
		QpackException e = assertThrows(QpackException.class, () -> QpackIntegers.readInteger(buf, 6));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		buf.recycle();
	}

	@Test
	public void unterminatedContinuationRunNeverHangsAndIsRejected() {
		// Every continuation byte keeps the high bit set and contributes zero value bits, so a
		// naive decoder would loop forever; this must be bounded and rejected instead.
		byte[] bytes = new byte[64];
		bytes[0] = 0x3f; // 6-bit prefix marker
		for (int i = 1; i < bytes.length; i++) {
			bytes[i] = (byte) 0x80; // continuation bit set, 7 value bits all zero
		}
		ByteBuf buf = ByteBuf.wrapForReading(bytes);
		QpackException e = assertThrows(QpackException.class, () -> QpackIntegers.readInteger(buf, 6));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		buf.recycle();
	}

	@Test
	public void valueOverflowing62BitsIsRejected() {
		// 6-bit prefix marker, then continuation bytes whose 7-bit groups sum well past 2^62-1.
		byte[] bytes = new byte[] {
			0x3f,
			(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
			(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
			(byte) 0xff, 0x7f,
		};
		ByteBuf buf = ByteBuf.wrapForReading(bytes);
		QpackException e = assertThrows(QpackException.class, () -> QpackIntegers.readInteger(buf, 6));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		buf.recycle();
	}
}
