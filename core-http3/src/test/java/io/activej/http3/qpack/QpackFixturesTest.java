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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Decodes the fixture files archived under {@code src/test/resources/io/activej/http3/qpack/}
 * (see the README there for provenance), so the fixtures are exercised rather than left as inert
 * archives (T027, SC-006).
 */
public class QpackFixturesTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static ByteBuf loadFixture(String name) throws IOException {
		try (InputStream in = QpackFixturesTest.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new AssertionError("Fixture not found on the classpath: " + name);
			}
			return ByteBuf.wrapForReading(in.readAllBytes());
		}
	}

	@Test
	public void rfc9204AppendixB1() throws Exception {
		List<QpackField> fields =
			new QpackStaticDecoder(Long.MAX_VALUE).decode(loadFixture("rfc9204-appendix-b1-static-name-reference.bin"));

		assertEquals(1, fields.size());
		assertEquals(HttpHeaders.of(":path"), fields.get(0).name());
		assertArrayEquals(encodeAscii("/index.html"), fields.get(0).value());
	}

	@Test
	public void syntheticGetRequest() throws Exception {
		List<QpackField> fields =
			new QpackStaticDecoder(Long.MAX_VALUE).decode(loadFixture("synthetic-get-request.bin"));

		assertEquals(List.of(
			new QpackField(HttpHeaders.of(":method"), encodeAscii("GET")),
			new QpackField(HttpHeaders.of(":scheme"), encodeAscii("https")),
			new QpackField(HttpHeaders.of(":path"), encodeAscii("/")),
			new QpackField(HttpHeaders.of(":authority"), encodeAscii("example.com")),
			new QpackField(HttpHeaders.of("user-agent"), encodeAscii("curl/8.9.0"))
		), fields);
	}

	@Test
	public void syntheticOkResponse() throws Exception {
		List<QpackField> fields =
			new QpackStaticDecoder(Long.MAX_VALUE).decode(loadFixture("synthetic-200-response.bin"));

		assertEquals(List.of(
			new QpackField(HttpHeaders.of(":status"), encodeAscii("200")),
			new QpackField(HttpHeaders.of("content-type"), encodeAscii("text/html; charset=utf-8")),
			new QpackField(HttpHeaders.of("content-length"), encodeAscii("1256")),
			new QpackField(HttpHeaders.of("server"), encodeAscii("nginx"))
		), fields);
	}
}
