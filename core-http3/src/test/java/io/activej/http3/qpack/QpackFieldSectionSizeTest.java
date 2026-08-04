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
import io.activej.http3.Http3Errors;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The RFC 9114 §4.2.2 bound (Σ {@code len(name) + len(value) + 32}) applied to <b>decoded</b>
 * output, incrementally, as it is produced (FR-030).
 * <p>
 * <b>How this proves "incremental"</b>: {@code '0'} is one of the cheapest RFC 7541 symbols (a
 * 5-bit code), so a long run of it Huffman-compresses to about 5/8 of its decoded size — a
 * "short" wire literal expanding to a much larger decoded string, exactly the shape the security
 * note in contracts/wire-protocol.md §4.3 is about. The functional assertion below (rejection) is
 * necessarily black-box; the "never allocates the full expansion first" guarantee itself is a
 * structural property of {@code QpackStaticDecoder.readStringBytes}, provable by inspection rather
 * than a timing assertion: the {@code accountant.add(1)} call inside the {@link QpackHuffman.ByteSink}
 * passed to {@link QpackHuffman#decode} runs, and can throw, for every decoded octet <b>before</b>
 * that octet is appended to the output buffer — so the output never grows past the byte at which
 * the bound was crossed, regardless of how much larger the declared/decoded length claims to be.
 */
public class QpackFieldSectionSizeTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void shortHuffmanLiteralExpandingPastTheBoundIsRejected() {
		int decodedLength = 20_000;
		byte[] value = new byte[decodedLength];
		Arrays.fill(value, (byte) '0');
		QpackField field = new QpackField(HttpHeaders.of("x-big"), value);

		QpackStaticEncoder encoder = new QpackStaticEncoder();
		ByteBuf encoded = encoder.encode(List.of(field));
		// '0' is a 5-bit Huffman code: ~20000*5/8 =~ 12500 bytes on the wire for a 20000-byte value.
		assertTrue("wire form should be substantially smaller than the decoded value",
			encoded.readRemaining() < decodedLength);

		QpackStaticDecoder decoder = new QpackStaticDecoder(100); // far below either length
		QpackException e = assertThrows(QpackException.class, () -> decoder.decode(encoded));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}

	@Test
	public void exactlyAtTheBoundIsAccepted() throws QpackException {
		// name "x" (1 byte) + value "AAAA" (4 bytes, incompressible enough to stay literal) + 32 = 37.
		byte[] value = {'A', 'A', 'A', 'A'};
		QpackField field = new QpackField(HttpHeaders.of("x"), value);

		QpackStaticEncoder encoder = new QpackStaticEncoder();
		ByteBuf encoded = encoder.encode(List.of(field));

		QpackStaticDecoder decoder = new QpackStaticDecoder(37);
		List<QpackField> fields = decoder.decode(encoded);
		assertEquals(1, fields.size());
	}

	@Test
	public void oneByteOverTheBoundIsRejected() {
		byte[] value = {'A', 'A', 'A', 'A'};
		QpackField field = new QpackField(HttpHeaders.of("x"), value);

		QpackStaticEncoder encoder = new QpackStaticEncoder();
		ByteBuf encoded = encoder.encode(List.of(field));

		QpackStaticDecoder decoder = new QpackStaticDecoder(36);
		QpackException e = assertThrows(QpackException.class, () -> decoder.decode(encoded));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}
}
