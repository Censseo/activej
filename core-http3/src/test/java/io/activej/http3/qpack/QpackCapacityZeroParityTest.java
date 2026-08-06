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

import io.activej.http.HttpHeaders;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Spec FR-040, SC-011: a {@link QpackDynamicEncoder} negotiated to capacity 0 is
 * {@link QpackStaticEncoder} byte for byte, emits no encoder-stream instruction, and touches no
 * table. Everything after this test is guarded by it — a phase-1 peer must see phase-1 bytes.
 * <p>
 * The corpus deliberately contains the two field shapes where the two encoders could disagree: a
 * field in the default never-indexed set, and one marked never-indexed per field. The {@code N} bit
 * of RFC 9204 §7.1 is an instruction to an <i>intermediary</i> that indexes; a decoder with no table
 * has nothing to do with it, and byte identity is the stronger testable property, so it is suppressed
 * at capacity 0 — {@code QpackDynamicEncoderTest} pins the other half, that it <b>is</b> emitted once
 * a table exists.
 * <p>
 * No {@code EventloopRule}: this package is synchronous (ADR-016).
 */
public class QpackCapacityZeroParityTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Set<String> DEFAULT_NEVER_INDEXED =
		Set.of("authorization", "proxy-authorization", "set-cookie");

	private static final long STREAM_ID = 4;

	private static QpackField field(String name, String value) {
		return new QpackField(HttpHeaders.of(name), encodeAscii(value));
	}

	private static final List<List<QpackField>> CORPUS = List.of(
		List.of(),
		List.of(field(":method", "GET")),
		List.of(field(":status", "200")),
		List.of(field("content-type", "application/json")),
		List.of(field("accept", "text/plain; charset=utf-8")),
		List.of(field("x-activej-unknown", "a value no static entry names")),
		List.of(field("authorization", "Bearer 0123456789abcdef")),
		List.of(field("proxy-authorization", "Basic dXNlcjpwYXNz")),
		List.of(field("set-cookie", "sid=abcdef; Path=/; HttpOnly")),
		List.of(field("cookie", "sid=abcdef")),
		List.of(field("x-secret", "value").asNeverIndexed()),
		List.of(field(":authority", "example.test").asNeverIndexed()),
		List.of(
			field(":method", "GET"),
			field(":scheme", "https"),
			field(":authority", "example.test"),
			field(":path", "/index.html"),
			field("user-agent", "activej-http3/6.0"),
			field("cookie", "sid=abcdef"),
			field("authorization", "Bearer 0123456789abcdef"),
			field("x-activej-unknown", "trailing")));

	@Test
	public void everyFieldListEncodesExactlyAsTheStaticEncoderDoes() {
		QpackStaticEncoder staticEncoder = new QpackStaticEncoder();
		for (List<QpackField> fields : CORPUS) {
			QpackDynamicEncoder encoder = encoder();
			byte[] expected = staticEncoder.encode(fields).asArray();
			byte[] actual = encoder.encode(STREAM_ID, fields).asArray();
			assertArrayEquals("field list " + fields, expected, actual);
		}
	}

	@Test
	public void theWholeCorpusThroughOneEncoderIsStillIdentical() {
		QpackStaticEncoder staticEncoder = new QpackStaticEncoder();
		QpackDynamicEncoder encoder = encoder();
		long streamId = 0;
		for (List<QpackField> fields : CORPUS) {
			byte[] expected = staticEncoder.encode(fields).asArray();
			byte[] actual = encoder.encode(streamId, fields).asArray();
			assertArrayEquals("field list " + fields, expected, actual);
			streamId += 4;
		}
		assertEquals(0, encoder.insertCount());
		assertEquals(0, encoder.dynamicReferences());
	}

	@Test
	public void noInstructionIsEverAccumulated() {
		QpackDynamicEncoder encoder = encoder();
		assertFalse(encoder.hasPendingInstructions());
		assertEquals(0, encoder.drainPendingInstructions().size());
		for (List<QpackField> fields : CORPUS) {
			encoder.encode(STREAM_ID, fields).recycle();
			assertFalse(encoder.hasPendingInstructions());
			assertEquals(0, encoder.drainPendingInstructions().size());
		}
	}

	@Test
	public void theEncoderReportsThatItUsesNoDynamicTable() {
		QpackDynamicEncoder encoder = encoder();
		assertFalse(encoder.usesDynamicTable());
		assertEquals(0, encoder.capacity());
		assertEquals(0, encoder.insertCount());
		assertEquals(0, encoder.evictedCount());
		assertEquals(0, encoder.knownReceivedCount());
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void theStreamlessOverloadIsIdenticalToo() {
		QpackStaticEncoder staticEncoder = new QpackStaticEncoder();
		QpackDynamicEncoder encoder = encoder();
		for (List<QpackField> fields : CORPUS) {
			byte[] expected = staticEncoder.encode(fields).asArray();
			byte[] actual = encoder.encode(fields).asArray();
			assertArrayEquals("field list " + fields, expected, actual);
		}
	}

	@Test
	public void aPerStreamViewEncodesTheSameBytes() {
		QpackStaticEncoder staticEncoder = new QpackStaticEncoder();
		QpackDynamicEncoder encoder = encoder();
		QpackEncoder view = encoder.forStream(STREAM_ID);
		for (List<QpackField> fields : CORPUS) {
			byte[] expected = staticEncoder.encode(fields).asArray();
			byte[] actual = view.encode(fields).asArray();
			assertArrayEquals("field list " + fields, expected, actual);
		}
	}

	private static QpackDynamicEncoder encoder() {
		return new QpackDynamicEncoder(0, 0, 0, 0, DEFAULT_NEVER_INDEXED);
	}
}
