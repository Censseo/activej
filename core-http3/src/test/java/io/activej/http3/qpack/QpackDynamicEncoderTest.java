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
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.InsertWithLiteralName;
import io.activej.http3.qpack.QpackInstructions.InsertWithNameReference;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Spec FR-017 through FR-022: the encoder's representation choice is deterministic and each step of
 * the FR-021 order is reachable, chosen and encoded in the RFC 9204 §4.5 wire form.
 * <p>
 * Assertions go against the bytes, parsed here by a deliberately independent reader over
 * {@link QpackIntegers} and {@link QpackHuffman} — asserting through {@code QpackDynamicDecoder}
 * would let a matched pair of bugs pass, and would couple this test to a class another task group
 * owns.
 * <p>
 * No {@code EventloopRule}: this package is synchronous (ADR-016).
 */
public class QpackDynamicEncoderTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Set<String> DEFAULT_NEVER_INDEXED =
		Set.of("authorization", "proxy-authorization", "set-cookie");

	private static final int CAPACITY = 4096;
	private static final int SECTIONS = 64;

	private static final long STREAM_0 = 0;
	private static final long STREAM_4 = 4;
	private static final long STREAM_8 = 8;

	private static QpackField field(String name, String value) {
		return new QpackField(HttpHeaders.of(name), encodeAscii(value));
	}

	private static QpackDynamicEncoder encoder(int capacity, int peerBlockedStreams) {
		return new QpackDynamicEncoder(capacity, capacity, peerBlockedStreams, SECTIONS, DEFAULT_NEVER_INDEXED);
	}

	private static Section encode(QpackDynamicEncoder encoder, long streamId, QpackField... fields) {
		return parse(encoder.encode(streamId, List.of(fields)).asArray());
	}

	// ------------------------------------------------------------------------------- FR-017, seeding

	@Test
	public void theFirstPendingInstructionIsSetDynamicTableCapacity() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		assertTrue(encoder.hasPendingInstructions());
		List<EncoderInstruction> drained = encoder.drainPendingInstructions();
		assertEquals(new SetDynamicTableCapacity(CAPACITY), drained.get(0));
		assertFalse(encoder.hasPendingInstructions());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	@Test
	public void aDrainReturnsInstructionsInEmissionOrderAndClearsThem() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.encode(STREAM_0, List.of(field("x-alpha", "1"), field("x-beta", "2"))).recycle();
		List<EncoderInstruction> drained = encoder.drainPendingInstructions();
		assertEquals(3, drained.size());
		assertEquals(new SetDynamicTableCapacity(CAPACITY), drained.get(0));
		assertEquals(new InsertWithLiteralName(encodeAscii("x-alpha"), encodeAscii("1")), drained.get(1));
		assertEquals(new InsertWithLiteralName(encodeAscii("x-beta"), encodeAscii("2")), drained.get(2));
		assertFalse(encoder.hasPendingInstructions());
	}

	// ------------------------------------------------------------------- FR-021 step 1: static index

	@Test
	public void aStaticExactMatchIsAnIndexedStaticFieldLine() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field(":method", "GET"));
		assertEquals(0, section.encodedInsertCount);
		Rep rep = section.only();
		assertEquals(Kind.INDEXED, rep.kind);
		assertTrue(rep.staticTable);
		assertEquals(17, rep.index);
	}

	@Test
	public void aStaticExactMatchIsNeverInserted() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.drainPendingInstructions();
		encoder.encode(STREAM_0, List.of(field(":method", "GET"), field(":scheme", "https"))).recycle();
		assertEquals(0, encoder.insertCount());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	// ------------------------------------------------------------------ FR-021 step 2: dynamic index

	@Test
	public void aRepeatedFieldIsAnIndexedDynamicFieldLine() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encode(encoder, STREAM_0, field("x-alpha", "1"));
		Section section = encode(encoder, STREAM_4, field("x-alpha", "1"));
		Rep rep = section.only();
		assertEquals(Kind.INDEXED, rep.kind);
		assertFalse(rep.staticTable);
		assertEquals(0, rep.index);
		assertEquals(1, encoder.insertCount());
	}

	@Test
	public void anEntryAlreadyInTheTableIsNeverInsertedTwice() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.encode(STREAM_0, List.of(field("x-alpha", "1"))).recycle();
		encoder.drainPendingInstructions();
		encoder.encode(STREAM_4, List.of(field("x-alpha", "1"))).recycle();
		assertEquals(1, encoder.insertCount());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	// --------------------------------------------------------------- FR-021 step 3: insert-then-index

	@Test
	public void anUnknownFieldIsInsertedAndThenIndexed() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.drainPendingInstructions();
		Section section = encode(encoder, STREAM_0, field("x-alpha", "1"));

		Rep rep = section.only();
		assertEquals(Kind.INDEXED, rep.kind);
		assertFalse(rep.staticTable);
		assertEquals(0, rep.index);
		assertEquals(1, encoder.insertCount());
		assertEquals(1, encoder.dynamicReferences());
		assertEquals(
			List.of(new InsertWithLiteralName(encodeAscii("x-alpha"), encodeAscii("1"))),
			encoder.drainPendingInstructions());
	}

	@Test
	public void anInsertionOfAStaticallyNamedFieldUsesAStaticNameReference() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.drainPendingInstructions();
		encoder.encode(STREAM_0, List.of(field("user-agent", "activej-http3/6.0"))).recycle();
		assertEquals(
			List.of(new InsertWithNameReference(true, 95, encodeAscii("activej-http3/6.0"))),
			encoder.drainPendingInstructions());
	}

	@Test
	public void anInsertionReusingAKnownNameUsesADynamicNameReference() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.encode(STREAM_0, List.of(field("x-alpha", "1"))).recycle();
		encoder.drainPendingInstructions();
		encoder.encode(STREAM_4, List.of(field("x-alpha", "2"))).recycle();
		assertEquals(
			List.of(new InsertWithNameReference(false, 0, encodeAscii("2"))),
			encoder.drainPendingInstructions());
		assertEquals(2, encoder.insertCount());
	}

	@Test
	public void aFieldTooLargeForTheCapacityIsNeverInserted() {
		QpackDynamicEncoder encoder = encoder(64, 4);
		encoder.drainPendingInstructions();
		Section section = encode(encoder, STREAM_0, field("x-alpha", "0123456789012345678901234567890123456789"));
		assertEquals(Kind.LITERAL_NAME, section.only().kind);
		assertEquals(0, encoder.insertCount());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	// ------------------------------------------------------- FR-021 step 4: literal + name reference

	@Test
	public void aStaticNameMatchWithoutAnIndexableEntryIsAStaticNameReference() {
		QpackDynamicEncoder encoder = encoder(64, 4);
		Section section = encode(encoder, STREAM_0, field("accept", "0123456789012345678901234567890123456789"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME_REF, rep.kind);
		assertTrue(rep.staticTable);
		assertEquals(29, rep.index);
		assertFalse(rep.neverIndexed);
	}

	@Test
	public void aDynamicNameMatchIsUsedWhenNothingElseFits() {
		QpackDynamicEncoder encoder = encoder(48, 4);
		encoder.encode(STREAM_0, List.of(field("x-alpha", "1"))).recycle();
		acknowledge(encoder, STREAM_0);

		Section section = encode(encoder, STREAM_4, field("x-alpha", "01234567890123456789"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME_REF, rep.kind);
		assertFalse(rep.staticTable);
		assertEquals(0, rep.index);
		assertEquals(2, section.encodedInsertCount);
	}

	@Test
	public void aStaticNameReferenceIsPreferredOverAnEquallyValidDynamicOne() {
		QpackDynamicEncoder encoder = encoder(48, 4);
		encoder.encode(STREAM_0, List.of(field("accept", "text/csv"))).recycle();
		acknowledge(encoder, STREAM_0);

		Section section = encode(encoder, STREAM_4, field("accept", "01234567890123456789"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME_REF, rep.kind);
		assertTrue(rep.staticTable);
		assertEquals(29, rep.index);
	}

	// ---------------------------------------------------------------------- FR-021 step 5: literal

	@Test
	public void anUnknownNameThatCannotBeInsertedIsAFullLiteral() {
		QpackDynamicEncoder encoder = encoder(64, 4);
		Section section = encode(encoder, STREAM_0, field("x-alpha", "0123456789012345678901234567890123456789"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME, rep.kind);
		assertFalse(rep.neverIndexed);
		assertArrayEquals(encodeAscii("x-alpha"), rep.name);
		assertArrayEquals(encodeAscii("0123456789012345678901234567890123456789"), rep.value);
	}

	// ---------------------------------------------------------- FR-020, blocked-stream bookkeeping

	@Test
	public void atZeroBlockedStreamsAFreshInsertionIsNotReferenced() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 0);
		encoder.drainPendingInstructions();
		Section section = encode(encoder, STREAM_0, field("x-alpha", "1"));

		assertEquals(Kind.LITERAL_NAME, section.only().kind);
		assertEquals(0, section.encodedInsertCount);
		assertEquals(1, encoder.insertCount());
		assertEquals(0, encoder.blockedStreamCount());
		assertEquals(
			List.of(new InsertWithLiteralName(encodeAscii("x-alpha"), encodeAscii("1"))),
			encoder.drainPendingInstructions());
	}

	@Test
	public void atZeroBlockedStreamsAnAcknowledgedEntryIsReferenced() throws QpackException {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 0);
		encoder.encode(STREAM_0, List.of(field("x-alpha", "1"))).recycle();
		encoder.applyDecoderInstruction(new InsertCountIncrement(1));
		assertEquals(1, encoder.knownReceivedCount());

		Section section = encode(encoder, STREAM_4, field("x-alpha", "1"));
		Rep rep = section.only();
		assertEquals(Kind.INDEXED, rep.kind);
		assertFalse(rep.staticTable);
		assertEquals(2, section.encodedInsertCount);
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void atOneBlockedStreamASecondStreamMayNotBlock() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 1);
		Section blocking = encode(encoder, STREAM_0, field("x-alpha", "1"));
		assertEquals(Kind.INDEXED, blocking.only().kind);
		assertEquals(1, encoder.blockedStreamCount());

		Section second = encode(encoder, STREAM_4, field("x-beta", "2"));
		assertEquals(Kind.LITERAL_NAME, second.only().kind);
		assertEquals(0, second.encodedInsertCount);
		assertEquals(1, encoder.blockedStreamCount());
		assertEquals(2, encoder.insertCount());
	}

	@Test
	public void anAlreadyBlockedStreamMayBlockAgain() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 1);
		encode(encoder, STREAM_0, field("x-alpha", "1"));
		Section second = encode(encoder, STREAM_0, field("x-beta", "2"));
		assertEquals(Kind.INDEXED, second.only().kind);
		assertEquals(1, encoder.blockedStreamCount());
	}

	@Test
	public void anAcknowledgmentUnblocksTheStream() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 1);
		encode(encoder, STREAM_0, field("x-alpha", "1"));
		assertEquals(1, encoder.blockedStreamCount());
		acknowledge(encoder, STREAM_0);
		assertEquals(0, encoder.blockedStreamCount());
		assertEquals(1, encoder.knownReceivedCount());

		Section section = encode(encoder, STREAM_4, field("x-beta", "2"));
		assertEquals(Kind.INDEXED, section.only().kind);
		assertEquals(1, encoder.blockedStreamCount());
	}

	// ------------------------------------------------------------------- FR-022, never-indexed set

	@Test
	public void aNeverIndexedFieldIsNeverInserted() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.drainPendingInstructions();
		encoder.encode(STREAM_0, List.of(
			field("authorization", "Bearer 0123456789"),
			field("proxy-authorization", "Basic dXNlcjpwYXNz"),
			field("set-cookie", "sid=abcdef"))).recycle();
		assertEquals(0, encoder.insertCount());
		assertEquals(0, encoder.dynamicReferences());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	@Test
	public void aNeverIndexedFieldWithAStaticNameCarriesTheNBit() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field("authorization", "Bearer 0123456789"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME_REF, rep.kind);
		assertTrue(rep.staticTable);
		assertEquals(84, rep.index);
		assertTrue(rep.neverIndexed);
	}

	@Test
	public void aNeverIndexedFieldWithALiteralNameCarriesTheNBit() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field("proxy-authorization", "Basic dXNlcjpwYXNz"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME, rep.kind);
		assertTrue(rep.neverIndexed);
		assertArrayEquals(encodeAscii("proxy-authorization"), rep.name);
	}

	@Test
	public void aPerFieldNeverIndexedMarkerIsHonoured() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field("x-alpha", "1").asNeverIndexed());
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME, rep.kind);
		assertTrue(rep.neverIndexed);
		assertEquals(0, encoder.insertCount());
	}

	@Test
	public void theNBitIsSuppressedWhenNoTableWasNegotiated() {
		QpackDynamicEncoder encoder = new QpackDynamicEncoder(0, 0, 0, 0, DEFAULT_NEVER_INDEXED);
		Section section = encode(encoder, STREAM_0, field("authorization", "Bearer 0123456789"));
		assertFalse(section.only().neverIndexed);

		Section literal = encode(encoder, STREAM_4, field("proxy-authorization", "Basic dXNlcjpwYXNz"));
		assertFalse(literal.only().neverIndexed);
	}

	@Test
	public void cookieIsIndexableByDefault() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field("cookie", "sid=abcdef"));
		Rep rep = section.only();
		assertEquals(Kind.INDEXED, rep.kind);
		assertFalse(rep.staticTable);
		assertEquals(1, encoder.insertCount());
	}

	@Test
	public void aConfiguredNeverIndexedNameIsHonoured() {
		QpackDynamicEncoder encoder =
			new QpackDynamicEncoder(CAPACITY, CAPACITY, 4, SECTIONS, Set.of("cookie"));
		Section section = encode(encoder, STREAM_0, field("cookie", "sid=abcdef"));
		Rep rep = section.only();
		assertEquals(Kind.LITERAL_NAME_REF, rep.kind);
		assertTrue(rep.staticTable);
		assertEquals(5, rep.index);
		assertTrue(rep.neverIndexed);
		assertEquals(0, encoder.insertCount());
	}

	// ------------------------------------------------------------------ RFC 9204 §4.5.1, the prefix

	@Test
	public void deltaBaseIsAlwaysZeroWithSUnset() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field("x-alpha", "1"));
		assertEquals(0x00, section.deltaBaseByte);
		assertEquals(2, section.encodedInsertCount);
	}

	@Test
	public void aSectionReferencingNothingDynamicCarriesRequiredInsertCountZero() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		Section section = encode(encoder, STREAM_0, field(":method", "GET"));
		assertEquals(0, section.encodedInsertCount);
		assertEquals(0x00, section.deltaBaseByte);
	}

	@Test
	public void theEncodedInsertCountWrapsAtTwiceMaxEntries() {
		QpackDynamicEncoder encoder = encoder(64, 4);
		long[] encodedInsertCounts = new long[6];
		for (int i = 0; i < encodedInsertCounts.length; i++) {
			long streamId = i * 4L;
			Section section = encode(encoder, streamId, field("x-a" + i, "0"));
			encodedInsertCounts[i] = section.encodedInsertCount;
			acknowledge(encoder, streamId);
		}
		// MaxEntries = 64 / 32 = 2, so the count wraps modulo 4 and is then offset by one.
		assertArrayEquals(new long[] {2, 3, 4, 1, 2, 3}, encodedInsertCounts);
		assertEquals(6, encoder.insertCount());
		assertEquals(5, encoder.evictedCount());
	}

	// ------------------------------------------------------- the streamless overload and its view

	@Test
	public void aStreamlessSectionHasNoSideEffects() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		encoder.drainPendingInstructions();
		Section section = parse(encoder.encode(List.of(field("x-alpha", "1"))).asArray());
		assertEquals(Kind.LITERAL_NAME, section.only().kind);
		assertEquals(0, section.encodedInsertCount);
		assertEquals(0, encoder.insertCount());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	@Test
	public void aPerStreamViewEncodesForThatStream() {
		QpackDynamicEncoder encoder = encoder(CAPACITY, 4);
		QpackEncoder view = encoder.forStream(STREAM_8);
		Section section = parse(view.encode(List.of(field("x-alpha", "1"))).asArray());
		assertEquals(Kind.INDEXED, section.only().kind);
		assertEquals(1, encoder.insertCount());

		encoder.onStreamCancelled(STREAM_8);
		assertEquals(0, encoder.blockedStreamCount());
	}

	// -------------------------------------------------------------------- construction is validated

	@Test
	public void constructionRefusesAnOutOfRangeConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(-1, 0, 0, 0, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(64, -1, 0, 0, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(64, 65, 0, 0, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(64, 64, -1, 0, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(64, 64, 0, -1, Set.of()));
		assertThrows(IllegalArgumentException.class, () -> new QpackDynamicEncoder(64, 64, 0, 0, null));
	}

	@Test
	public void aSectionThatCannotBeTrackedIsEncodedLiterally() {
		QpackDynamicEncoder encoder = new QpackDynamicEncoder(CAPACITY, CAPACITY, 8, 1, DEFAULT_NEVER_INDEXED);
		Section tracked = encode(encoder, STREAM_0, field("x-alpha", "1"));
		assertEquals(Kind.INDEXED, tracked.only().kind);

		Section untracked = encode(encoder, STREAM_4, field("x-alpha", "1"));
		assertEquals(Kind.LITERAL_NAME, untracked.only().kind);
		assertEquals(0, untracked.encodedInsertCount);
	}

	private static void acknowledge(QpackDynamicEncoder encoder, long streamId) {
		try {
			encoder.applyDecoderInstruction(new SectionAcknowledgment(streamId));
		} catch (QpackException e) {
			throw new AssertionError("unexpected QPACK failure: " + e.reason(), e);
		}
	}

	// ------------------------------------------------------------------------ an independent reader

	private enum Kind {INDEXED, INDEXED_POST_BASE, LITERAL_NAME_REF, LITERAL_POST_BASE_NAME_REF, LITERAL_NAME}

	private record Rep(Kind kind, boolean staticTable, boolean neverIndexed, long index, byte[] name, byte[] value) {}

	private record Section(long encodedInsertCount, int deltaBaseByte, List<Rep> reps) {
		private Rep only() {
			assertEquals("expected exactly one field line", 1, reps.size());
			return reps.get(0);
		}
	}

	private static Section parse(byte[] bytes) {
		try {
			ByteBuf buf = ByteBuf.wrapForReading(bytes);
			long encodedInsertCount = QpackIntegers.readInteger(buf, 8);
			int deltaBaseByte = buf.peek() & 0xFF;
			QpackIntegers.readInteger(buf, 7);
			List<Rep> reps = new ArrayList<>();
			while (buf.canRead()) {
				reps.add(readRepresentation(buf));
			}
			return new Section(encodedInsertCount, deltaBaseByte, reps);
		} catch (QpackException e) {
			throw new AssertionError("the encoder emitted bytes this reader cannot parse: " + e.reason(), e);
		}
	}

	private static Rep readRepresentation(ByteBuf buf) throws QpackException {
		int first = buf.peek() & 0xFF;
		if ((first & 0x80) != 0) {
			boolean staticTable = (first & 0x40) != 0;
			return new Rep(Kind.INDEXED, staticTable, false, QpackIntegers.readInteger(buf, 6), null, null);
		}
		if ((first & 0x40) != 0) {
			boolean neverIndexed = (first & 0x20) != 0;
			boolean staticTable = (first & 0x10) != 0;
			long index = QpackIntegers.readInteger(buf, 4);
			return new Rep(Kind.LITERAL_NAME_REF, staticTable, neverIndexed, index, null, readString(buf, 7, 0x80));
		}
		if ((first & 0x20) != 0) {
			boolean neverIndexed = (first & 0x10) != 0;
			byte[] name = readString(buf, 3, 0x08);
			return new Rep(Kind.LITERAL_NAME, false, neverIndexed, -1, name, readString(buf, 7, 0x80));
		}
		if ((first & 0x10) != 0) {
			return new Rep(Kind.INDEXED_POST_BASE, false, false, QpackIntegers.readInteger(buf, 4), null, null);
		}
		boolean neverIndexed = (first & 0x08) != 0;
		long index = QpackIntegers.readInteger(buf, 3);
		return new Rep(Kind.LITERAL_POST_BASE_NAME_REF, false, neverIndexed, index, null, readString(buf, 7, 0x80));
	}

	private static byte[] readString(ByteBuf buf, int prefixBits, int huffmanFlag) throws QpackException {
		boolean huffman = (buf.peek() & huffmanFlag) != 0;
		int length = (int) QpackIntegers.readInteger(buf, prefixBits);
		byte[] raw = new byte[length];
		buf.read(raw);
		if (!huffman) return raw;
		ByteArrayOutputStream decoded = new ByteArrayOutputStream(length * 2);
		QpackHuffman.decode(raw, 0, raw.length, decoded::write);
		return decoded.toByteArray();
	}
}
