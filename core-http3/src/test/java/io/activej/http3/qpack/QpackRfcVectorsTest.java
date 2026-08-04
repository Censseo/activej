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
import io.activej.http.HttpHeaders;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9204 Appendix B.1: {@code :path: /index.html} encoded via Literal Field Line with Name
 * Reference (static, name index 1), decoded to the exact field list (SC-006).
 * <p>
 * <b>Re-encoding is verified as a round-trip, not a byte-for-byte replay of the RFC's vector</b> —
 * a deliberate deviation. The RFC's example deliberately leaves {@code H=0} (a literal, not
 * Huffman-coded, value) purely to keep the illustration simple; RFC 9204 §4.5.4 states Huffman
 * coding is the encoder's choice. {@code "/index.html"} genuinely Huffman-compresses shorter than
 * its 11-byte literal form, and {@link QpackStaticEncoder} always prefers the shorter
 * representation (FR-029, contracts/wire-protocol.md §4.3) — so a spec-conformant encoder here
 * necessarily produces a different, smaller wire form than the RFC's illustrative bytes. Asserting
 * byte-identity would mean asserting our encoder is instead {@code H=0}-hardcoded, which
 * {@link QpackHuffmanTest} and {@link QpackStaticEncoder}'s own contract explicitly forbid.
 */
public class QpackRfcVectorsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// Prefix: Required Insert Count=0 (8-bit) `00`, S=0/Delta Base=0 (7-bit) `00`.
	// Field: 0x51 = 0101_0001 -> T=1,N=0, 4-bit prefix index=1 (":path" at static index 1);
	//        0x0b = value length 11, H=0 (7-bit prefix, no flag);
	//        "/index.html" (11 ASCII bytes).
	private static final byte[] ENCODED = {
		0x00, 0x00,
		0x51, 0x0b, '/', 'i', 'n', 'd', 'e', 'x', '.', 'h', 't', 'm', 'l',
	};

	@Test
	public void decodesToExactFieldList() throws QpackException {
		QpackStaticDecoder decoder = new QpackStaticDecoder(Long.MAX_VALUE);
		List<QpackField> fields = decoder.decode(ByteBuf.wrapForReading(ENCODED.clone()));

		assertEquals(1, fields.size());
		assertEquals(HttpHeaders.of(":path"), fields.get(0).name());
		assertArrayEquals(encodeAscii("/index.html"), fields.get(0).value());
	}

	@Test
	public void reEncodingRoundTrips() throws QpackException {
		QpackStaticEncoder encoder = new QpackStaticEncoder();
		QpackField field = new QpackField(HttpHeaders.of(":path"), encodeAscii("/index.html"));

		ByteBuf encoded = encoder.encode(List.of(field));
		// Shorter than the RFC's illustrative literal form (15 bytes): Huffman wins for this string.
		assertTrue(encoded.readRemaining() < ENCODED.length);

		QpackStaticDecoder decoder = new QpackStaticDecoder(Long.MAX_VALUE);
		List<QpackField> decoded = decoder.decode(encoded);
		assertEquals(List.of(field), decoded);
	}

	@Test
	public void aLiteralThatDoesNotCompressReEncodesToTheRfcBytesExactly() throws QpackException {
		// A control-octet-heavy value that Huffman would expand rather than shrink (every symbol
		// below 0x20 needs at least 13 bits, see QpackHuffman's CODE_LENGTHS) reliably keeps our
		// encoder on the literal path, matching the RFC vector's wire-format shape byte-for-byte.
		byte[] value = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a};
		assertEquals(11, value.length); // same length as "/index.html", for a clean byte-count comparison
		assertTrue(QpackHuffman.encodedLength(value, 0, value.length) >= value.length);

		QpackField field = new QpackField(HttpHeaders.of(":path"), value);
		QpackStaticEncoder encoder = new QpackStaticEncoder();
		ByteBuf encoded = encoder.encode(List.of(field));

		byte[] expected = ENCODED.clone();
		System.arraycopy(value, 0, expected, 4, value.length); // same shape, literal payload swapped in
		byte[] actual = new byte[encoded.readRemaining()];
		encoded.read(actual);
		encoded.recycle();

		assertArrayEquals(expected, actual);
	}
}
