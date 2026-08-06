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
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9114 §4.1.1: "Field names MUST be converted to lowercase prior to their encoding." An encoder
 * here has exactly one way to violate that — the Literal Field Line with Literal Name, the only
 * representation whose name reaches the wire as octets rather than as a static-table index.
 * <p>
 * The trap this pins is that the violation is <b>invisible</b> for the 99 names of RFC 9204
 * Appendix A, which are lowercase by construction <i>and</i> never sent literally. It bites only for
 * a legal name that is absent from the static table but present in {@code core-http}'s
 * case-insensitive {@link HttpHeaders} registry: {@code HttpHeaders.of("accept-charset")} hands back
 * the registry's own canonically-cased {@code Accept-Charset} token, so a caller that carefully
 * lowercased the name gets an uppercase one back, and a literal write of that token's bytes puts
 * {@code Accept-Charset} on the wire.
 * <p>
 * The assertions below read the name octets straight off the wire with {@link QpackIntegers} and
 * {@link QpackHuffman} rather than through {@link QpackStaticDecoder}, so what is pinned is the
 * encoded bytes themselves and not a second implementation's opinion of them.
 */
public class QpackEncoderFieldNameCaseTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Literal Field Line with Literal Name: {@code 0 0 1 N H name-len(3+)}. */
	private static final int LITERAL_LITERAL_NAME_MASK = 0xE0;
	private static final int LITERAL_LITERAL_NAME_PATTERN = 0x20;
	private static final int LITERAL_NAME_HUFFMAN_FLAG = 0x08;

	/**
	 * The precondition that makes the rest of this class meaningful, asserted rather than assumed:
	 * {@code accept-charset} is a registered {@code core-http} header (so the registry re-cases it)
	 * and is absent from the QPACK static table (so it must be written as a literal name).
	 */
	@Test
	public void acceptCharsetIsRegisteredUppercaseAndAbsentFromTheStaticTable() {
		HttpHeader name = HttpHeaders.of("accept-charset");
		assertEquals("Accept-Charset", name.toString());
		assertNotEquals("the registry hands back its own spelling", "accept-charset", name.toString());
		assertEquals(-1, QpackStaticTable.indexOfName(name));
	}

	@Test
	public void nameAbsentFromTheStaticTableIsLowercasedOnTheWire() throws QpackException {
		byte[] onTheWire = encodeAndReadLiteralName(HttpHeaders.of("accept-charset"), encodeAscii("utf-8"));

		assertEquals("accept-charset", new String(onTheWire, StandardCharsets.ISO_8859_1));
		assertNoUppercase(onTheWire);
	}

	/**
	 * The same rule for a name the {@link HttpHeaders} registry has never seen — the funnel belongs to
	 * the encoder, not to the registry, so a caller cannot opt out of it by bringing its own token.
	 */
	@Test
	public void unregisteredMixedCaseNameIsLowercasedOnTheWire() throws QpackException {
		byte[] onTheWire = encodeAndReadLiteralName(HttpHeaders.of("X-Custom-Trace-Id"), encodeAscii("abc"));

		assertEquals("x-custom-trace-id", new String(onTheWire, StandardCharsets.ISO_8859_1));
		assertNoUppercase(onTheWire);
	}

	/**
	 * The decoder's own verdict on the octets, which is what {@code Http3Headers.fromQpack} rejects a
	 * peer for: a field section this implementation encodes must never make a conformant peer raise
	 * {@code H3_MESSAGE_ERROR}.
	 */
	@Test
	public void encodedSectionNeverLooksUppercaseToADecoder() throws QpackException {
		QpackField field = new QpackField(HttpHeaders.of("accept-charset"), encodeAscii("utf-8"));

		ByteBuf encoded = new QpackStaticEncoder().encode(List.of(field));
		List<QpackField> decoded = new QpackStaticDecoder(Long.MAX_VALUE).decode(encoded);

		assertEquals(1, decoded.size());
		assertFalse("RFC 9114 §4.1.1 violation observed by the peer", decoded.get(0).nameHadUppercase());
	}

	private static void assertNoUppercase(byte[] nameBytes) {
		for (byte b : nameBytes) {
			assertTrue("uppercase octet on the wire: " + new String(nameBytes, StandardCharsets.ISO_8859_1),
				b < 'A' || b > 'Z');
		}
	}

	/**
	 * Encodes one field and returns the literal name octets as they appear on the wire, parsing the
	 * Encoded Field Section Prefix and the one Literal Field Line with Literal Name by hand.
	 */
	private static byte[] encodeAndReadLiteralName(HttpHeader name, byte[] value) throws QpackException {
		ByteBuf encoded = new QpackStaticEncoder().encode(List.of(new QpackField(name, value)));
		try {
			assertEquals(0, QpackIntegers.readInteger(encoded, 8)); // Required Insert Count
			assertEquals(0, QpackIntegers.readInteger(encoded, 7)); // S = 0, Delta Base

			int first = encoded.peek() & 0xFF;
			assertEquals("expected a Literal Field Line with Literal Name",
				LITERAL_LITERAL_NAME_PATTERN, first & LITERAL_LITERAL_NAME_MASK);
			boolean huffman = (first & LITERAL_NAME_HUFFMAN_FLAG) != 0;

			int length = (int) QpackIntegers.readInteger(encoded, 3);
			byte[] nameBytes = new byte[length];
			encoded.read(nameBytes);
			if (!huffman) return nameBytes;

			ByteArrayOutputStream decoded = new ByteArrayOutputStream(length * 2);
			QpackHuffman.decode(nameBytes, 0, nameBytes.length, decoded::write);
			return decoded.toByteArray();
		} finally {
			encoded.recycle();
		}
	}
}
