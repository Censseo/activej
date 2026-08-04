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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;

/**
 * Randomized field sets, encode -> decode -> structural equality. Covers static-table hits
 * (Indexed), names present in the static table with a value that is not (Literal with Name
 * Reference), names absent from the static table entirely (Literal with Literal Name), and values
 * containing non-ASCII octets (0x80-0xFF) to exercise Huffman on binary-ish content.
 */
public class QpackRoundTripTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long SEED = 20260801L;

	@Test
	public void randomizedFieldSetsRoundTrip() throws QpackException {
		Random random = new Random(SEED);
		QpackStaticEncoder encoder = new QpackStaticEncoder();
		QpackStaticDecoder decoder = new QpackStaticDecoder(Long.MAX_VALUE);

		for (int trial = 0; trial < 200; trial++) {
			List<QpackField> expected = randomFieldSet(random);

			ByteBuf encoded = encoder.encode(expected);
			List<QpackField> actual = decoder.decode(encoded);

			assertEquals("trial " + trial, expected, actual);
		}
	}

	private static List<QpackField> randomFieldSet(Random random) {
		int count = 1 + random.nextInt(12);
		List<QpackField> fields = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			fields.add(randomField(random, i));
		}
		return fields;
	}

	private static QpackField randomField(Random random, int index) {
		switch (random.nextInt(4)) {
			case 0 -> {
				// Exact static-table hit: Indexed Field Line.
				return new QpackField(HttpHeaders.of(":method"), encodeAscii("GET"));
			}
			case 1 -> {
				// Static-table name, non-matching value: Literal Field Line with Name Reference.
				return new QpackField(HttpHeaders.of("content-type"), randomBytes(random, 1, 40));
			}
			case 2 -> {
				// Name absent from the static table: Literal Field Line with Literal Name.
				HttpHeader name = HttpHeaders.of("x-custom-header-" + index);
				return new QpackField(name, randomBytes(random, 0, 60));
			}
			default -> {
				// Binary-ish value spanning the full octet range, including 0x80-0xFF.
				HttpHeader name = HttpHeaders.of("x-binary-" + index);
				byte[] value = new byte[1 + random.nextInt(80)];
				random.nextBytes(value);
				return new QpackField(name, value);
			}
		}
	}

	private static byte[] randomBytes(Random random, int minLength, int maxLength) {
		int length = minLength + random.nextInt(maxLength - minLength + 1);
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			// Printable ASCII, plausible for a header value.
			bytes[i] = (byte) (0x20 + random.nextInt(0x5F));
		}
		return bytes;
	}
}
