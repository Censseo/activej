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
import io.activej.http.HttpHeaders;
import io.activej.http3.qpack.QpackInstructions.Duplicate;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T158, FR-018's third RFC 9204 §4.3 insertion instruction: the encoder emits {@code Duplicate} for
 * the one case it exists for — an entry it is about to reference that sits so close to the FIFO tail
 * that the next insertion of its own size evicts it.
 * <p>
 * Both halves are asserted: that the instruction appears on the encoder stream with the right relative
 * index, and that a <b>real</b> {@link QpackDynamicDecoder} fed those bytes holds the copy at its new
 * absolute index with the original gone. The three cases where a duplicate would be pure cost — no
 * eviction pressure, a copy this section could not reference, a tail pinned by an outstanding
 * section — are asserted to emit nothing, since a wasted instruction is a compression regression that
 * no round trip would fail on.
 * <p>
 * No {@code EventloopRule}: this package is synchronous (ADR-016).
 */
public class QpackDynamicEncoderDuplicateTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** {@code x-a0} (4) + {@code 0} (1) + the RFC 9204 §3.2.1 overhead (32), four times over. */
	private static final int ENTRY = 37;
	private static final int FOUR_ENTRIES = 4 * ENTRY;

	private static final int SECTIONS = 64;
	private static final long MAX_INSTRUCTION_SIZE = 16 * 1024;

	private static final long STREAM_0 = 0;
	private static final long STREAM_4 = 4;
	private static final long STREAM_8 = 8;

	private static final QpackField F0 = field("x-a0");
	private static final QpackField F1 = field("x-a1");
	private static final QpackField F2 = field("x-a2");
	private static final QpackField F3 = field("x-a3");
	private static final List<QpackField> FOUR_FIELDS = List.of(F0, F1, F2, F3);

	@Test
	public void anEntryAboutToAgeOutIsDuplicatedRatherThanReferencedAtTheTail() {
		QpackDynamicEncoder encoder = encoder(FOUR_ENTRIES, 16);
		fillTable(encoder);

		IndexedLine line = encodeOneIndexedLine(encoder, STREAM_4, F0);

		// insertCount was 4, so the oldest entry is 3 back from the encoder stream's base.
		assertEquals(List.of(new Duplicate(3)), encoder.drainPendingInstructions());
		assertEquals(5, encoder.insertCount());
		assertEquals(1, encoder.evictedCount());

		// Base is the Required Insert Count, 5, and the copy is absolute index 4.
		assertFalse(line.staticTable);
		assertEquals(0, line.index);
		assertEquals(6, line.encodedInsertCount);
	}

	@Test
	public void thePeerHoldsTheDuplicatedEntryAtItsNewIndex() throws QpackException {
		QpackDynamicEncoder encoder = encoder(FOUR_ENTRIES, 16);
		QpackDynamicDecoder decoder = new QpackDynamicDecoder(FOUR_ENTRIES, 16, Long.MAX_VALUE);
		QpackEncoderStreamReader encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
		try {
			ByteBuf primed = encoder.encode(STREAM_0, FOUR_FIELDS);
			deliver(encoder, encoderStream);
			assertEquals(FOUR_FIELDS, decoder.decode(primed));
			acknowledge(encoder, STREAM_0);

			ByteBuf section = encoder.encode(STREAM_4, List.of(F0));
			List<EncoderInstruction> drained = encoder.drainPendingInstructions();
			assertEquals(List.of(new Duplicate(3)), drained);
			encoderStream.feed(write(drained));
			assertEquals(List.of(F0), decoder.decode(section));

			QpackDynamicTable table = decoder.table();
			assertEquals(5, table.insertCount());
			assertEquals(1, table.droppedCount());
			assertFalse(table.isAvailable(0));
			assertEquals(HttpHeaders.of("x-a0"), table.nameAt(4));
			assertArrayEquals(encodeAscii("0"), table.valueAt(4));
		} finally {
			encoderStream.recycle();
		}
	}

	@Test
	public void aDuplicatedEntryIsNotDuplicatedAgainOnTheNextReference() {
		QpackDynamicEncoder encoder = encoder(FOUR_ENTRIES, 16);
		fillTable(encoder);
		encoder.encode(STREAM_4, List.of(F0)).recycle();
		assertEquals(List.of(new Duplicate(3)), encoder.drainPendingInstructions());

		IndexedLine line = encodeOneIndexedLine(encoder, STREAM_8, F0);

		assertEquals(List.of(), encoder.drainPendingInstructions());
		assertEquals(5, encoder.insertCount());
		// Base 5 again, and the copy at absolute index 4 is still the newest entry.
		assertEquals(0, line.index);
	}

	@Test
	public void aTableWithRoomToSpareNeverDuplicates() {
		QpackDynamicEncoder encoder = encoder(4096, 16);
		fillTable(encoder);

		IndexedLine line = encodeOneIndexedLine(encoder, STREAM_4, F0);

		assertEquals(List.of(), encoder.drainPendingInstructions());
		assertEquals(4, encoder.insertCount());
		assertEquals(0, encoder.evictedCount());
		// Base 1, the oldest entry, referenced where it stands.
		assertEquals(0, line.index);
	}

	@Test
	public void aCopyThisSectionCouldNotReferenceIsNeverDuplicated() throws QpackException {
		QpackDynamicEncoder encoder = encoder(FOUR_ENTRIES, 0);
		encoder.encode(STREAM_0, FOUR_FIELDS).recycle();
		encoder.applyDecoderInstruction(new InsertCountIncrement(4));
		encoder.drainPendingInstructions();
		assertEquals(4, encoder.knownReceivedCount());

		IndexedLine line = encodeOneIndexedLine(encoder, STREAM_4, F0);

		assertEquals(List.of(), encoder.drainPendingInstructions());
		assertEquals(4, encoder.insertCount());
		assertEquals(0, encoder.blockedStreamCount());
		assertEquals(0, line.index);
	}

	@Test
	public void aTailPinnedByAnOutstandingSectionIsNeverDuplicated() {
		QpackDynamicEncoder encoder = encoder(FOUR_ENTRIES, 16);
		encoder.encode(STREAM_0, FOUR_FIELDS).recycle();
		encoder.drainPendingInstructions();

		IndexedLine line = encodeOneIndexedLine(encoder, STREAM_8, F0);

		assertEquals(List.of(), encoder.drainPendingInstructions());
		assertEquals(4, encoder.insertCount());
		assertEquals(0, encoder.evictedCount());
		assertEquals(0, line.index);
	}

	@Test
	public void nothingIsDuplicatedWhenNoTableWasNegotiated() {
		QpackDynamicEncoder encoder = new QpackDynamicEncoder(0, 0, 16, SECTIONS, Set.of());
		encoder.encode(STREAM_0, FOUR_FIELDS).recycle();
		encoder.encode(STREAM_4, FOUR_FIELDS).recycle();
		assertEquals(0, encoder.insertCount());
		assertEquals(List.of(), encoder.drainPendingInstructions());
	}

	// ------------------------------------------------------------------------------------- harness

	private static QpackField field(String name) {
		return new QpackField(HttpHeaders.of(name), encodeAscii("0"));
	}

	private static QpackDynamicEncoder encoder(int capacity, int peerBlockedStreams) {
		return new QpackDynamicEncoder(capacity, capacity, peerBlockedStreams, SECTIONS, Set.of());
	}

	/** Four entries inserted, referenced and acknowledged, so nothing is left pinned. */
	private static void fillTable(QpackDynamicEncoder encoder) {
		encoder.encode(STREAM_0, FOUR_FIELDS).recycle();
		acknowledge(encoder, STREAM_0);
		encoder.drainPendingInstructions();
		assertEquals(4, encoder.insertCount());
		assertEquals(4, encoder.knownReceivedCount());
	}

	private static void acknowledge(QpackDynamicEncoder encoder, long streamId) {
		try {
			encoder.applyDecoderInstruction(new SectionAcknowledgment(streamId));
		} catch (QpackException e) {
			throw new AssertionError("unexpected QPACK failure: " + e.reason(), e);
		}
	}

	private static void deliver(QpackDynamicEncoder encoder, QpackEncoderStreamReader stream) throws QpackException {
		List<EncoderInstruction> instructions = encoder.drainPendingInstructions();
		if (instructions.isEmpty()) return;
		stream.feed(write(instructions));
	}

	/** The reader owns what it is fed on every path, a throw included. */
	private static ByteBuf write(List<? extends EncoderInstruction> instructions) {
		int length = 0;
		for (EncoderInstruction instruction : instructions) length += instruction.encodedLength();
		ByteBuf out = ByteBufPool.allocate(length);
		for (EncoderInstruction instruction : instructions) instruction.writeTo(out);
		return out;
	}

	/**
	 * Reads the one field line of a one-field section, asserting it is an Indexed Field Line — parsed
	 * here rather than through {@link QpackDynamicDecoder}, so a matched pair of bugs cannot pass.
	 */
	private static IndexedLine encodeOneIndexedLine(QpackDynamicEncoder encoder, long streamId, QpackField field) {
		byte[] bytes = encoder.encode(streamId, List.of(field)).asArray();
		try {
			ByteBuf buf = ByteBuf.wrapForReading(bytes);
			long encodedInsertCount = QpackIntegers.readInteger(buf, 8);
			QpackIntegers.readInteger(buf, 7);
			int first = buf.peek() & 0xFF;
			assertTrue("expected an Indexed Field Line, got 0x" + Integer.toHexString(first), (first & 0x80) != 0);
			IndexedLine line =
				new IndexedLine(encodedInsertCount, (first & 0x40) != 0, QpackIntegers.readInteger(buf, 6));
			assertFalse("expected exactly one field line", buf.canRead());
			return line;
		} catch (QpackException e) {
			throw new AssertionError("the encoder emitted bytes this reader cannot parse: " + e.reason(), e);
		}
	}

	private record IndexedLine(long encodedInsertCount, boolean staticTable, long index) {}
}
