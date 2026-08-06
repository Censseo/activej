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
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.http3.qpack.QpackVectors.QpackVector;
import io.activej.http3.qpack.QpackVectors.TableEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RFC 9204 Appendix B, replayed as what it is: <b>one cumulative exchange in five parts</b>, against
 * one {@link QpackDynamicDecoder} and one {@link QpackEncoderStreamReader} at capacity 220 with a
 * blocked-stream limit of 0 (T016, SC-002).
 * <p>
 * B.2's field section references entries B.2's own encoder stream inserted; B.4 duplicates an entry
 * B.2 inserted; B.5 evicts it. Replaying any of them alone proves nothing, which is why
 * {@link QpackVectors#rfc9204AppendixB()} returns them in the series order the corpus's
 * {@code index.txt} fixes.
 * <p>
 * <b>What is asserted from the fixture, and what is not.</b> The decoded fields, the Insert Count,
 * the table size, the capacity, the entries with their absolute indices, and the decoder-stream bytes
 * are all the RFC's own. The {@code ref} column of {@code [expected-table]} is <i>not</i>: Appendix
 * B's diagrams show the <b>encoder's</b> table, and a decoder's table carries no reference counts —
 * asserting it here would be asserting the wrong side's bookkeeping. {@code known-received-count} is
 * asserted through its decoder-side mirror, {@link QpackDynamicDecoder#pendingInsertCountIncrement()},
 * which is exactly "what this decoder has not yet told the encoder about".
 */
public class QpackRfc9204VectorsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long TIMEOUT_MS = 10_000;

	private static final int CAPACITY = 220;
	private static final long MAX_INSTRUCTION_SIZE = 16 * 1024;

	private static final String ENCODER_STREAM = "encoder-stream";
	private static final String FIELD_SECTION = "field-section";
	private static final String DECODER_STREAM = "decoder-stream";

	/** One decoder plus the encoder stream that fills its table — the pair a connection owns. */
	private static final class Peer {
		final QpackDynamicDecoder decoder;
		final QpackEncoderStreamReader encoderStream;

		Peer(int blockedStreams) {
			this.decoder = new QpackDynamicDecoder(CAPACITY, blockedStreams, Long.MAX_VALUE);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
		}
	}

	@Test(timeout = TIMEOUT_MS)
	public void appendixBReplaysAsOneCumulativeExchange() throws QpackException {
		Peer peer = new Peer(0);
		try {
			List<QpackVector> vectors = QpackVectors.rfc9204AppendixB();
			assertEquals(List.of("B.1", "B.2", "B.3", "B.4", "B.5"), vectors.stream().map(QpackVector::name).toList());

			for (QpackVector vector : vectors) {
				replay(peer, vector);
				assertEndState(peer.decoder, vector);
			}
		} finally {
			peer.encoderStream.recycle();
		}
	}

	/**
	 * B.4's other reading. The RFC <i>presents</i> the encoder stream first but <i>narrates</i> its
	 * packet as delayed, and the fixture records both: under {@code blocked-delivery-order} the section
	 * arrives with Required Insert Count 4 against an Insert Count of 3 and must be <b>held</b>, not
	 * failed (FR-033).
	 * <p>
	 * Holding it is US2's; what this pins is the seam — the section comes back whole, unconsumed and
	 * still the caller's, and decodes to the same fields once the delayed byte lands.
	 */
	@Test(timeout = TIMEOUT_MS)
	public void appendixB4BlocksAndThenDecodesInTheNarratedOrder() throws QpackException {
		Peer peer = new Peer(1);
		try {
			List<QpackVector> vectors = QpackVectors.rfc9204AppendixB();
			for (QpackVector vector : vectors.subList(0, 3)) {
				replay(peer, vector);
			}

			QpackVector b4 = vectors.get(3);
			assertEquals(List.of(FIELD_SECTION, ENCODER_STREAM, DECODER_STREAM), b4.blockedDeliveryOrder());
			assertEquals(3, peer.decoder.insertCount());

			SectionResult blockedResult = peer.decoder.decodeOrBlock(b4.sectionBuf());
			if (!(blockedResult instanceof Blocked blocked)) {
				fail("B.4 must block when its encoder-stream packet is delayed");
				return;
			}
			assertEquals(4, blocked.requiredInsertCount());

			peer.encoderStream.feed(b4.encoderStreamBuf());
			assertEquals(4, peer.decoder.insertCount());

			SectionResult unblocked = peer.decoder.decodeOrBlock(blocked.section());
			assertEquals(b4.expectedFields(), ((Decoded) unblocked).fields());
		} finally {
			peer.encoderStream.recycle();
		}
	}

	// ---------------------------------------------------------------- replay

	private static void replay(Peer peer, QpackVector vector) throws QpackException {
		assertEquals("the whole series is recorded at one capacity", CAPACITY, vector.capacity());
		Decoded decoded = null;
		for (String part : vector.deliveryOrder()) {
			switch (part) {
				case ENCODER_STREAM -> peer.encoderStream.feed(vector.encoderStreamBuf());
				case FIELD_SECTION -> decoded = decodeSection(peer.decoder, vector);
				case DECODER_STREAM -> assertDecoderStream(peer.decoder, vector, decoded);
				default -> fail(vector.name() + ": unknown delivery-order part '" + part + '\'');
			}
		}
	}

	private static Decoded decodeSection(QpackDynamicDecoder decoder, QpackVector vector) throws QpackException {
		SectionResult result = decoder.decodeOrBlock(vector.sectionBuf());
		if (!(result instanceof Decoded decoded)) {
			((Blocked) result).section().recycle();
			throw new AssertionError(vector.name() + " must not block in its recorded delivery order");
		}
		assertEquals(vector.name(), vector.expectedFields(), decoded.fields());
		return decoded;
	}

	/**
	 * The RFC's {@code [decoder-stream]} bytes are what this decoder <i>owes</i> the peer at this step.
	 * Emitting them is T035's; here each is parsed, checked against the decoder state that would have
	 * produced it, and re-encoded byte-for-byte.
	 */
	private static void assertDecoderStream(QpackDynamicDecoder decoder, QpackVector vector, Decoded decoded)
		throws QpackException {
		ByteBuf buf = vector.decoderStreamBuf();
		DecoderInstruction instruction;
		try {
			instruction = QpackInstructions.readDecoderInstruction(buf);
			assertNotNull(vector.name() + ": a whole decoder instruction", instruction);
			assertFalse(vector.name() + ": one instruction per step in this corpus", buf.canRead());
		} finally {
			buf.recycle();
		}

		if (instruction instanceof SectionAcknowledgment acknowledgment) {
			assertNotNull(vector.name() + ": a Section Acknowledgment needs a decoded section", decoded);
			assertEquals(vector.name(), vector.streamId(), acknowledgment.streamId());
			// FR-024: acknowledged exactly because the Required Insert Count is non-zero.
			assertNotEquals(vector.name(), 0, decoded.requiredInsertCount());
			decoder.onInsertCountAnnounced(decoded.requiredInsertCount());
		} else if (instruction instanceof InsertCountIncrement increment) {
			// FR-026: the increment is what no acknowledgment has covered.
			assertEquals(vector.name(), decoder.pendingInsertCountIncrement(), increment.increment());
			decoder.onInsertCountAnnounced(decoder.insertCount());
		} else {
			assertEquals(vector.name(), vector.streamId(), ((StreamCancellation) instruction).streamId());
		}

		ByteBuf reEncoded = QpackInstructions.encode(instruction);
		try {
			assertArrayEquals(vector.name(), vector.decoderStream(), reEncoded.getArray());
		} finally {
			reEncoded.recycle();
		}
	}

	// ---------------------------------------------------------------- the state the RFC prints

	private static void assertEndState(QpackDynamicDecoder decoder, QpackVector vector) {
		String name = vector.name();
		assertEquals(name + " insert count", vector.expectedState().insertCount(), decoder.insertCount());
		assertEquals(name + " table size", vector.expectedState().size(), decoder.table().size());
		assertEquals(name + " capacity", vector.tableCapacityAfter(), decoder.capacity());
		assertEquals(name + " MaxEntries", vector.maxEntries(), decoder.table().maxEntries());
		assertEquals(name + " known received count",
			vector.expectedState().knownReceivedCount(),
			decoder.insertCount() - decoder.pendingInsertCountIncrement());

		List<TableEntry> expectedTable = vector.expectedTable();
		assertEquals(name + " entry count", expectedTable.size(), decoder.table().entryCount());
		for (TableEntry entry : expectedTable) {
			long absoluteIndex = entry.absoluteIndex();
			assertTrue(name + " absolute index " + absoluteIndex, decoder.table().isAvailable(absoluteIndex));
			assertEquals(name + " name at " + absoluteIndex,
				entry.field().name(), decoder.table().nameAt(absoluteIndex));
			assertArrayEquals(name + " value at " + absoluteIndex,
				entry.field().value(), decoder.table().valueAt(absoluteIndex));
		}
	}
}
