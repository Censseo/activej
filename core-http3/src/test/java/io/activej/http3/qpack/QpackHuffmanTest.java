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

import java.io.ByteArrayOutputStream;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.*;

/**
 * RFC 7541 Appendix B Huffman coding: round-trip for every octet value, the RFC 7541 §C.4.1
 * conformance vector, and the padding/EOS adversarial cases RFC 7541 §5.2 requires a decoder to
 * reject.
 */
public class QpackHuffmanTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static byte[] decodeAll(byte[] encoded) throws QpackException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		QpackHuffman.decode(encoded, 0, encoded.length, out::write);
		return out.toByteArray();
	}

	@Test
	public void everySingleOctetRoundTrips() throws QpackException {
		for (int value = 0; value <= 0xFF; value++) {
			byte[] original = {(byte) value};
			ByteBuf encoded = ByteBufPool.allocate(16);
			QpackHuffman.encode(encoded, original, 0, original.length);
			assertEquals("encodedLength mismatch for " + value,
				QpackHuffman.encodedLength(original, 0, original.length), encoded.readRemaining());
			byte[] encodedBytes = new byte[encoded.readRemaining()];
			encoded.read(encodedBytes);
			encoded.recycle();

			byte[] decoded = decodeAll(encodedBytes);
			assertArrayEquals("round-trip mismatch for " + value, original, decoded);
		}
	}

	@Test
	public void multiByteStringRoundTrips() throws QpackException {
		byte[] original = encodeAscii("The quick brown fox jumps over the lazy dog! 0123456789");
		ByteBuf encoded = ByteBufPool.allocate(128);
		QpackHuffman.encode(encoded, original, 0, original.length);
		byte[] encodedBytes = new byte[encoded.readRemaining()];
		encoded.read(encodedBytes);
		encoded.recycle();

		assertArrayEquals(original, decodeAll(encodedBytes));
	}

	// RFC 7541 §C.4.1 — Huffman-encoding "www.example.com" produces exactly this 12-byte sequence.
	@Test
	public void rfc7541example_wwwExampleCom() throws QpackException {
		byte[] original = encodeAscii("www.example.com");
		byte[] expected = {
			(byte) 0xf1, (byte) 0xe3, (byte) 0xc2, (byte) 0xe5, (byte) 0xf2, 0x3a,
			0x6b, (byte) 0xa0, (byte) 0xab, (byte) 0x90, (byte) 0xf4, (byte) 0xff,
		};

		ByteBuf encoded = ByteBufPool.allocate(32);
		QpackHuffman.encode(encoded, original, 0, original.length);
		assertEquals(expected.length, encoded.readRemaining());
		byte[] encodedBytes = new byte[encoded.readRemaining()];
		encoded.read(encodedBytes);
		encoded.recycle();
		assertArrayEquals(expected, encodedBytes);

		assertArrayEquals(original, decodeAll(expected));
	}

	@Test
	public void paddingLongerThanSevenBitsIsRejected() {
		// Two whole bytes of 1-bits: 16 leftover bits, none of which ever complete the 30-bit EOS
		// code, so this is not "a bit of padding" but a structurally invalid tail.
		byte[] encoded = {(byte) 0xFF, (byte) 0xFF};
		QpackException e = assertThrows(QpackException.class, () -> decodeAll(encoded));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}

	@Test
	public void paddingThatIsNotAnEosPrefixIsRejected() {
		// Symbol '0' (code 0b00000, length 5) followed by padding bits "011" instead of "111": the
		// last three bits are not a prefix of EOS's all-ones code.
		byte[] encoded = {0x03}; // 00000 011
		QpackException e = assertThrows(QpackException.class, () -> decodeAll(encoded));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}

	@Test
	public void encodedEosSymbolAppearingInContentIsRejected() {
		// 32 one-bits: the first 30 complete the EOS code itself, which must never be accepted as a
		// decoded symbol — it exists only implicitly, as trailing padding shorter than a full symbol.
		byte[] encoded = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		QpackException e = assertThrows(QpackException.class, () -> decodeAll(encoded));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}

	@Test
	public void emptyInputDecodesToEmptyOutput() throws QpackException {
		assertArrayEquals(new byte[0], decodeAll(new byte[0]));
	}

	@Test
	public void encodedLengthNeverExceedsInputWhenChosenByCaller() {
		// Not every input compresses; the encoder always emits what it's asked to, but the encoder's
		// caller (QpackStaticEncoder) is expected to compare encodedLength() against the literal
		// length itself before deciding to Huffman-code at all.
		byte[] incompressible = {0x00, 0x01, 0x02, 0x03}; // control octets: length 28+ each in the table
		int huffmanLength = QpackHuffman.encodedLength(incompressible, 0, incompressible.length);
		assertTrue(huffmanLength > incompressible.length);
	}
}
