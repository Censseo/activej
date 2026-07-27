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
 * RFC 9000 §17.1 and Appendix A.2/A.3 test vectors for packet number truncation and
 * reconstruction. The two worked examples ({@code 0xac5c02}/{@code 0xabe8b3}, a 29,519-packet
 * gap requiring 2 bytes) and ({@code 0xace9fe}/{@code 0xabe8b3}, a 65,867-packet gap requiring
 * 3 bytes) mirror RFC 9000 §17.1's own prose example.
 */
public class PacketNumbersTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void selectsEncodeLengthPerRfc9000Section17Example() {
		assertEquals(2, PacketNumbers.encodeLength(0xac5c02L, 0xabe8b3L));
		assertEquals(3, PacketNumbers.encodeLength(0xace9feL, 0xabe8b3L));
	}

	@Test
	public void selectsEncodeLengthAtBoundaries() {
		assertEquals(1, PacketNumbers.encodeLength(0L, 0L));
		assertEquals(1, PacketNumbers.encodeLength(0L, -1L));
		assertEquals(1, PacketNumbers.encodeLength(126L, 0L));
		assertEquals(2, PacketNumbers.encodeLength(127L, 0L));
		// A gap larger than 4 bytes can represent is clamped, not rejected
		assertEquals(4, PacketNumbers.encodeLength(0x3FFFFFFFFFFFFFFFL, -1L));
	}

	@Test
	public void reconstructsRfc9000AppendixA3Example() {
		assertEquals(0xa82f9b32L, PacketNumbers.reconstruct(0x9b32L, 2, 0xa82f30eaL));
	}

	@Test
	public void reconstructsAfterRfc9000Section17TruncationRoundTrip() throws TruncatedDataException {
		long largestAcked = 0xabe8b3L;

		int len1 = PacketNumbers.encodeLength(0xac5c02L, largestAcked);
		ByteBuf buf1 = ByteBuf.wrapForWriting(new byte[4]);
		PacketNumbers.write(buf1, 0xac5c02L, len1);
		long truncated1 = PacketNumbers.read(buf1, len1);
		assertEquals(0xac5c02L, PacketNumbers.reconstruct(truncated1, len1, 0xac5c01L));
		buf1.recycle();

		int len2 = PacketNumbers.encodeLength(0xace9feL, largestAcked);
		ByteBuf buf2 = ByteBuf.wrapForWriting(new byte[4]);
		PacketNumbers.write(buf2, 0xace9feL, len2);
		long truncated2 = PacketNumbers.read(buf2, len2);
		assertEquals(0xace9feL, PacketNumbers.reconstruct(truncated2, len2, 0xace9faL));
		buf2.recycle();
	}

	@Test
	public void roundTripsAtPacketNumberSpaceBoundaries() throws TruncatedDataException {
		// PN 0, no prior packet number in this space
		int len = PacketNumbers.encodeLength(0L, -1L);
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[4]);
		PacketNumbers.write(buf, 0L, len);
		assertEquals(0L, PacketNumbers.reconstruct(PacketNumbers.read(buf, len), len, -1L));
		buf.recycle();

		// Maximum packet number, immediately following the previous one
		long maxPn = 0x3FFFFFFFFFFFFFFFL;
		int lenMax = PacketNumbers.encodeLength(maxPn, maxPn - 1);
		ByteBuf bufMax = ByteBuf.wrapForWriting(new byte[4]);
		PacketNumbers.write(bufMax, maxPn, lenMax);
		assertEquals(maxPn, PacketNumbers.reconstruct(PacketNumbers.read(bufMax, lenMax), lenMax, maxPn - 1));
		bufMax.recycle();
	}

	@Test
	public void roundTripsForEachTruncationLength() throws TruncatedDataException {
		for (int len = 1; len <= 4; len++) {
			ByteBuf buf = ByteBuf.wrapForWriting(new byte[4]);
			long fullPn = (1L << (len * 8)) + 5;
			long largestPn = fullPn - 1;
			PacketNumbers.write(buf, fullPn, len);
			long reconstructed = PacketNumbers.reconstruct(PacketNumbers.read(buf, len), len, largestPn);
			assertEquals(fullPn, reconstructed);
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnEncodeLengthWithFullPnNotAfterReference() {
		PacketNumbers.encodeLength(5L, 10L);
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnNegativeFullPn() {
		PacketNumbers.encodeLength(-1L, -1L);
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnWriteLengthOutOfRange() {
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[4]);
		try {
			PacketNumbers.write(buf, 5L, 5);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnReconstructLengthOutOfRange() {
		PacketNumbers.reconstruct(5L, 0, 10L);
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnTruncatedValueWiderThanLength() {
		// 1-byte length can only hold values in [0, 255]
		PacketNumbers.reconstruct(256L, 1, 10L);
	}

	@Test
	public void readsRawBytesWithoutReconstruction() throws Exception {
		ByteBuf buf = ByteBuf.wrapForWriting(new byte[4]);
		PacketNumbers.write(buf, 0x1234L, 2);
		assertEquals(0x1234L, PacketNumbers.read(buf, 2));
		buf.recycle();
	}

	@Test(expected = TruncatedDataException.class)
	public void throwsOnReadWithInsufficientBytes() throws Exception {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[] {1, 2});
		try {
			PacketNumbers.read(buf, 3);
		} finally {
			buf.recycle();
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void throwsOnReadLengthOutOfRange() throws Exception {
		ByteBuf buf = ByteBuf.wrapForReading(new byte[4]);
		try {
			PacketNumbers.read(buf, 5);
		} finally {
			buf.recycle();
		}
	}
}
