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
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T045 / FR-020: with the peer advertising {@code SETTINGS_QPACK_BLOCKED_STREAMS = 0}, the encoder
 * references only entries the Known Received Count already covers, so <b>nothing it emits can ever
 * block</b>.
 * <p>
 * The instrument matters. Every section here is decoded by a {@link QpackDynamicDecoder} built with a
 * blocked-stream limit of <b>16</b>, not 0 — at 0 the decoder <i>throws</i> on a section it cannot
 * decode yet (RFC 9204 §2.1.2), which would prove the same thing but through an exception that a
 * dozen other causes also raise. At 16 a section that would block comes back as {@link Blocked}, and
 * an assertion on the result type says exactly what FR-020 says. The encoder's own peer limit — the
 * one under test — is a constructor argument and is independent of it.
 * <p>
 * The delivery order is the inverted one throughout: the insertions a section's encode produced reach
 * the decoder <b>after</b> that section, which is the only order under which FR-020 is observable at
 * all. Under the RFC-correct order (research D-2) nothing blocks whatever the encoder references.
 * <p>
 * {@code QpackDynamicRoundTripTest.nothingBlocksAgainstAPeerLimitOfZeroEvenWhenInstructionsArriveLate}
 * asserts the same property over the generated workload matrix. This file is the narrow, hand-built
 * counterpart: it names the entries, checks the Required Insert Count each section actually carried
 * against what the peer held at that moment, and carries the control case that makes the whole thing
 * non-vacuous.
 */
public final class QpackEncoderBlockedStreamsZeroTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int CAPACITY = 4096;
	private static final int MAX_OUTSTANDING_SECTIONS = 16;
	private static final long MAX_INSTRUCTION_SIZE = 16 * 1024;

	/** Generous enough that a blocked section is reported rather than refused; see the class Javadoc. */
	private static final int REVEALING_BLOCKED_STREAMS = 16;

	private static final int ROUNDS = 6;

	// ---------------------------------------------------------------- FR-020

	@Test
	public void noSectionEverBlocksWhenThePeerAdvertisesZero() throws QpackException {
		Exchange exchange = new Exchange(0);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				Round result = exchange.run(round);
				if (result.blocked()) {
					fail("section #" + round + " blocked at Required Insert Count " +
						 result.requiredInsertCount() + " against a peer blocked-stream limit of 0 (FR-020)");
				}
			}
		} finally {
			exchange.recycle();
		}
	}

	/**
	 * The same runs, read the other way round: RFC 9204 §2.1.2's condition itself. A section blocks
	 * exactly when its Required Insert Count exceeds the Insert Count the peer's decoder held when the
	 * section arrived, so asserting that it never does is asserting FR-020 at its own definition
	 * rather than at its consequence.
	 */
	@Test
	public void everySectionsRequiredInsertCountIsCoveredBeforeItArrives() throws QpackException {
		Exchange exchange = new Exchange(0);
		try {
			for (int round = 0; round < ROUNDS; round++) {
				Round result = exchange.run(round);
				assertTrue("section #" + round + " carried Required Insert Count " +
						   result.requiredInsertCount() + " against an Insert Count of " +
						   result.insertCountOnArrival() + " (FR-020)",
					result.requiredInsertCount() <= result.insertCountOnArrival());
			}
		} finally {
			exchange.recycle();
		}
	}

	/**
	 * Non-vacuity, twice over. A limit of 0 must cost compression, not correctness: the encoder still
	 * inserts, and still references what the peer is known to hold — an encoder that fell back to
	 * literals everywhere would pass both tests above and buy nothing.
	 */
	@Test
	public void theEncoderStillInsertsAndStillReferencesWhatThePeerHolds() throws QpackException {
		Exchange exchange = new Exchange(0);
		try {
			int firstSectionBytes = exchange.run(0).sectionBytes();
			int lastSectionBytes = firstSectionBytes;
			for (int round = 1; round < ROUNDS; round++) {
				lastSectionBytes = exchange.run(round).sectionBytes();
			}

			assertTrue("nothing was ever inserted", exchange.encoder.insertCount() > 0);
			assertTrue("nothing dynamic was ever referenced", exchange.encoder.dynamicReferences() > 0);
			assertTrue("the last section (" + lastSectionBytes + " bytes) did not shrink against the first (" +
					   firstSectionBytes + " bytes)",
				lastSectionBytes < firstSectionBytes);
		} finally {
			exchange.recycle();
		}
	}

	/**
	 * The control. Exactly the same workload and the same inverted delivery order, with the one thing
	 * under test changed: a peer limit of 16 does produce a blocked section. Without this, every
	 * assertion above could hold because the fixture never gets near the condition.
	 */
	@Test
	public void aPeerLimitAboveZeroIsWhatMakesTheSameWorkloadBlock() throws QpackException {
		Exchange exchange = new Exchange(REVEALING_BLOCKED_STREAMS);
		try {
			boolean blocked = false;
			for (int round = 0; round < ROUNDS; round++) {
				blocked |= exchange.run(round).blocked();
			}
			assertTrue("no section blocked even at a peer limit of " + REVEALING_BLOCKED_STREAMS +
					   " — the fixture never reaches the condition FR-020 excludes", blocked);
		} finally {
			exchange.recycle();
		}
	}

	// ---------------------------------------------------------------- the exchange

	/** What one round produced, in the terms RFC 9204 §2.1.2 states the blocking condition in. */
	private record Round(boolean blocked, long requiredInsertCount, long insertCountOnArrival, int sectionBytes) {}

	/**
	 * One encoder, one decoder, and the two instruction streams between them — with the encoder
	 * stream deliberately delivered <b>after</b> the section it belongs to.
	 * <p>
	 * The Known Received Count advances the only way it can: this endpoint's decoder announces what it
	 * has inserted (RFC 9204 §4.4.3 Insert Count Increment) and acknowledges the sections it decoded
	 * (§4.4.1), and both are fed back into the encoder. That closed loop is what makes "covered by the
	 * Known Received Count" mean "the peer already holds it".
	 */
	private static final class Exchange {
		final QpackDynamicEncoder encoder;
		final QpackDynamicDecoder decoder;
		final QpackEncoderStreamReader encoderStream;

		Exchange(int peerBlockedStreams) {
			this.encoder = new QpackDynamicEncoder(CAPACITY, CAPACITY, peerBlockedStreams,
				MAX_OUTSTANDING_SECTIONS, Set.of());
			this.decoder = new QpackDynamicDecoder(CAPACITY, REVEALING_BLOCKED_STREAMS, Long.MAX_VALUE);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
		}

		Round run(int round) throws QpackException {
			long streamId = 4L * round;
			List<QpackField> fields = requestFields(round);

			ByteBuf section = encoder.encode(streamId, fields);
			List<EncoderInstruction> instructions = encoder.drainPendingInstructions();
			int sectionBytes = section.readRemaining();
			long insertCountOnArrival = decoder.insertCount();

			// decodeOrBlock owns the section on every path but the blocked one, and the pending
			// instructions are values rather than buffers — so a throw from here leaks nothing.
			SectionResult result = decoder.decodeOrBlock(section);
			if (result instanceof Blocked blocked) {
				// The buffer is the caller's again, untouched. Deliver what it was waiting for and
				// re-enter, so that even the control case leaves the two tables in step.
				deliver(instructions);
				result = decoder.decodeOrBlock(blocked.section());
				if (result instanceof Blocked stillBlocked) {
					stillBlocked.section().recycle();
					throw new AssertionError("still blocked after every insertion was delivered, at " +
											 stillBlocked.requiredInsertCount());
				}
				acknowledge(streamId, (Decoded) result);
				assertEquals("the section did not survive the round trip", fields, ((Decoded) result).fields());
				return new Round(true, ((Decoded) result).requiredInsertCount(), insertCountOnArrival, sectionBytes);
			}

			Decoded decoded = (Decoded) result;
			assertEquals("the section did not survive the round trip", fields, decoded.fields());
			deliver(instructions);
			acknowledge(streamId, decoded);
			return new Round(false, decoded.requiredInsertCount(), insertCountOnArrival, sectionBytes);
		}

		/** RFC 9204 §4.3, this endpoint's inbound encoder stream. */
		private void deliver(List<EncoderInstruction> instructions) throws QpackException {
			if (instructions.isEmpty()) return;
			int length = 0;
			for (EncoderInstruction instruction : instructions) {
				length += instruction.encodedLength();
			}
			ByteBuf buf = ByteBufPool.allocate(length);
			for (EncoderInstruction instruction : instructions) {
				instruction.writeTo(buf);
			}
			// feed() owns the buffer on every path, a throw included.
			encoderStream.feed(buf);
		}

		/** RFC 9204 §4.4, the decoder stream going back — the only way the Known Received Count moves. */
		private void acknowledge(long streamId, Decoded decoded) throws QpackException {
			if (decoded.requiredInsertCount() > 0) {
				encoder.applyDecoderInstruction(new QpackInstructions.SectionAcknowledgment(streamId));
				decoder.onInsertCountAnnounced(decoded.requiredInsertCount());
			}
			long increment = decoder.pendingInsertCountIncrement();
			if (increment > 0) {
				encoder.applyDecoderInstruction(new QpackInstructions.InsertCountIncrement(increment));
				decoder.onInsertCountAnnounced(decoder.insertCount());
			}
		}

		void recycle() {
			encoderStream.recycle();
		}
	}

	/**
	 * A request whose fields mostly recur — which is what gives the dynamic table anything to do — with
	 * one field per round that does not, so every round has something new to insert and the table never
	 * goes quiet.
	 */
	private static List<QpackField> requestFields(int round) {
		List<QpackField> fields = new ArrayList<>();
		fields.add(field(":method", "GET"));
		fields.add(field(":scheme", "https"));
		fields.add(field(":authority", "example.test"));
		fields.add(field(":path", "/resource/" + round));
		fields.add(field("user-agent", "activej-http3-test/6.0 (qpack, dynamic table)"));
		fields.add(field("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
		fields.add(field("accept-language", "en-GB,en;q=0.9"));
		fields.add(field("cookie", "session=0123456789abcdef0123456789abcdef; theme=dark"));
		fields.add(field("x-round", Integer.toString(round)));
		return fields;
	}

	private static QpackField field(String name, String value) {
		HttpHeader header = HttpHeaders.of(name);
		return new QpackField(header, value.getBytes(StandardCharsets.ISO_8859_1));
	}
}
