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
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.Instruction;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackRoundTrip.CodecPair;
import io.activej.http3.qpack.QpackRoundTrip.InstructionDelivery;
import io.activej.http3.qpack.QpackRoundTrip.Report;
import io.activej.http3.qpack.QpackRoundTrip.SectionReport;
import io.activej.http3.qpack.QpackWorkloadGenerator.Section;
import io.activej.http3.qpack.QpackWorkloadGenerator.Workload;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http3.qpack.QpackRoundTrip.UNBOUNDED_FIELD_SECTION;
import static org.junit.Assert.*;

/**
 * T018 — the round trip across capacities {0, 256 B, 4 KB, 64 KB} × blocked streams {0, 1, 16},
 * driven through a <b>real</b> {@link QpackDynamicEncoder} / {@link QpackDynamicDecoder} pair.
 * <p>
 * The T003 harness ({@link QpackWorkloadGenerator} + {@link QpackRoundTrip}) was built for exactly
 * this and needs no change: {@link DynamicPair} is the {@link CodecPair} implementation its two
 * delivery hooks were left open for. What those hooks carry here are the two QPACK unidirectional
 * streams, in process and with no QUIC underneath — encoder-stream instructions are serialized with
 * {@link Instruction#writeTo(ByteBuf)} and fed to a real {@link QpackEncoderStreamReader}, decoder
 * stream instructions likewise into {@link QpackDynamicEncoder#consumeDecoderStream(ByteBuf)}. Both
 * directions therefore cross real wire bytes, not a method call between two objects that happen to
 * agree.
 *
 * <h2>Both delivery orders are run, and they mean different things</h2>
 * {@link InstructionDelivery#BEFORE_SECTION} is research D-2's discipline — instructions first, so
 * nothing ever blocks. {@link InstructionDelivery#AFTER_SECTION} hands the section over first, which
 * is what makes it <i>blocked</i>: {@link QpackDynamicDecoder#decodeOrBlock} then returns
 * {@link Blocked}, and the section decodes on re-entry once the insertions arrive. That path is
 * exercised here; <b>holding</b> a blocked section, and bounding how many and for how long, is US2's
 * {@code QpackBlockedSections} and is deliberately not simulated.
 *
 * <h2>What "smaller" is measured against</h2>
 * A field section's wire size alone flatters a dynamic encoder, because the insertion it references
 * was paid for on the <i>encoder stream</i> and that byte count appears nowhere in
 * {@link SectionReport}. Every comparison here therefore uses {@link RunResult#sectionCosts()} —
 * field-section bytes <b>plus</b> the encoder-stream bytes that section caused — so the first
 * request carries the cost of the table it primed. That is what makes the ≥ 60 % claim of SC-001
 * meaningful rather than an artifact of where the bytes were counted.
 */
public class QpackDynamicRoundTripTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long[] CAPACITIES = {0, 256, 4096, 65536};
	private static final int[] BLOCKED_STREAMS = {0, 1, 16};

	/** Well above anything one workload holds outstanding, since every section is acknowledged at once. */
	private static final int MAX_OUTSTANDING_SECTIONS = 64;

	/** The {@code Http3Settings.qpackMaxInstructionSize()} default (FR-089). */
	private static final long MAX_INSTRUCTION_SIZE = 16 * 1024;

	/** The SC-001 shape: the same request repeated, at the capacity SC-001 names. */
	private static final int REPEATED_REQUEST_CAPACITY = 4096;
	private static final int REPEATED_REQUEST_COUNT = 16;

	// region the matrix

	/**
	 * Every cell of the T018 matrix, under both delivery orders: each section must come back
	 * field-for-field (asserted inside {@link QpackRoundTrip#runSection}), and the two tables must end
	 * the run agreeing about what was inserted and what was acknowledged.
	 */
	@Test
	public void everyMatrixCellRoundTripsUnderBothDeliveryOrders() throws QpackException {
		for (long capacity : CAPACITIES) {
			for (int blockedStreams : BLOCKED_STREAMS) {
				Workload workload = matrixWorkload(capacity, blockedStreams);
				for (InstructionDelivery delivery : InstructionDelivery.values()) {
					RunResult result = run(workload, delivery);
					String cell = workload.label() + '/' + delivery;

					assertEquals(cell, workload.sections().size(), result.report().sectionCount());
					assertEquals(cell, workload.coverage().fields(), result.report().fieldCount());
					assertCodecsAgree(cell, result);
				}
			}
		}
	}

	/**
	 * FR-040: at capacity 0 a dynamic pair is the phase-1 pair. Not merely "also correct" — the same
	 * bytes, section for section, and not one instruction on either stream.
	 */
	@Test
	public void capacityZeroIsByteIdenticalToTheStaticPair() throws QpackException {
		for (int blockedStreams : BLOCKED_STREAMS) {
			Workload workload = matrixWorkload(0, blockedStreams);
			Report staticReport = QpackRoundTrip.run(workload, QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION));

			for (InstructionDelivery delivery : InstructionDelivery.values()) {
				RunResult result = run(workload, delivery);
				String cell = workload.label() + '/' + delivery;

				assertEquals(cell, staticReport.sections(), result.report().sections());
				assertEquals(cell, 0, result.encoderStreamBytes());
				assertEquals(cell, 0, result.decoderStreamBytes());
				assertEquals(cell, 0, result.insertCount());
				assertFalse(cell, result.usesDynamicTable());
			}
		}
	}

	/**
	 * The dynamic table must actually be used at every non-zero capacity, in every blocked-stream
	 * cell — including a peer limit of 0, where FR-020 restricts the encoder to entries the Known
	 * Received Count already covers but does <b>not</b> stop it from inserting them (a warm insertion
	 * pays off on the next request; refusing to insert would make FR-020 at 0 vacuous).
	 */
	@Test
	public void everyNonZeroCapacityInsertsAndReferences() throws QpackException {
		for (long capacity : CAPACITIES) {
			if (capacity == 0) continue;
			for (int blockedStreams : BLOCKED_STREAMS) {
				Workload workload = matrixWorkload(capacity, blockedStreams);
				RunResult result = run(workload, InstructionDelivery.BEFORE_SECTION);
				String cell = workload.label();

				assertTrue(cell, result.usesDynamicTable());
				assertTrue(cell, result.insertCount() > 0);
				assertTrue(cell, result.dynamicReferences() > 0);
				assertTrue(cell, result.encoderStreamBytes() > 0);
				assertTrue(cell, result.decoderStreamBytes() > 0);
			}
		}
	}

	/** A 256 B table holds two or three entries of this corpus, so it must evict rather than fill and stop. */
	@Test
	public void aSmallCapacityEvicts() throws QpackException {
		for (int blockedStreams : BLOCKED_STREAMS) {
			Workload workload = matrixWorkload(256, blockedStreams);
			RunResult result = run(workload, InstructionDelivery.BEFORE_SECTION);
			assertTrue(workload.label(), result.evictedCount() > 0);
		}
	}

	// endregion
	// region blocked sections

	/**
	 * Delivering the insertions <i>after</i> the section that references them is what a blocked
	 * section is. The decoder must surface it as {@link Blocked} — handing the buffer back untouched
	 * rather than failing — and decode it on re-entry once the encoder stream catches up (FR-033).
	 */
	@Test
	public void sectionsBlockAndThenDecodeWhenTheInsertionsArrive() throws QpackException {
		Workload workload = matrixWorkload(REPEATED_REQUEST_CAPACITY, 16);

		RunResult blocking = run(workload, InstructionDelivery.AFTER_SECTION);
		assertTrue(blocking.blockedSections() > 0);

		RunResult ordered = run(workload, InstructionDelivery.BEFORE_SECTION);
		assertEquals(0, ordered.blockedSections());
	}

	/**
	 * FR-020 at a peer limit of 0: the encoder must never emit a Required Insert Count above the Known
	 * Received Count, so no section can block <b>even when the insertions are delivered late</b>. That
	 * is the property that lets a decoder advertise {@code SETTINGS_QPACK_BLOCKED_STREAMS = 0} and
	 * still interoperate, and it is not observable under the RFC-correct delivery order.
	 */
	@Test
	public void nothingBlocksAgainstAPeerLimitOfZeroEvenWhenInstructionsArriveLate() throws QpackException {
		for (long capacity : CAPACITIES) {
			Workload workload = matrixWorkload(capacity, 0);
			RunResult result = run(workload, InstructionDelivery.AFTER_SECTION);
			assertEquals(workload.label(), 0, result.blockedSections());
		}
	}

	// endregion
	// region compression

	/**
	 * SC-001's shape: one request repeated. Request 1 primes the table and pays for every insertion on
	 * the encoder stream; requests 2..N reference what it primed. The claim is ≥ 60 % smaller, and it
	 * holds at all three blocked-stream limits — at 0 the win simply arrives one request later,
	 * because the first section may only reference entries the Known Received Count already covers.
	 */
	@Test
	public void repeatedRequestsAreAtLeastSixtyPercentSmallerThanTheFirst() throws QpackException {
		List<QpackField> request = matrixWorkload(REPEATED_REQUEST_CAPACITY, 16).sections().get(0).fields();

		for (int blockedStreams : BLOCKED_STREAMS) {
			for (InstructionDelivery delivery : InstructionDelivery.values()) {
				String label = "repeated/" + blockedStreams + '/' + delivery;
				RunResult result = run(label, REPEATED_REQUEST_CAPACITY, blockedStreams,
					QpackWorkloadGenerator.DEFAULT_NEVER_INDEXED_NAMES,
					repeated(request, REPEATED_REQUEST_COUNT), delivery);

				double shrinkage = result.subsequentCostShrinkage();
				assertTrue(label + " shrank only " + shrinkage, shrinkage >= 0.60);
			}
		}
	}

	/**
	 * The same repeated request through the phase-1 pair, which has no table to prime: every request
	 * costs what the first one did. Without this the assertion above could be met by a workload whose
	 * later sections are simply smaller.
	 */
	@Test
	public void theStaticPairDoesNotShrinkOnTheSameRepeatedRequest() throws QpackException {
		List<QpackField> request = matrixWorkload(REPEATED_REQUEST_CAPACITY, 16).sections().get(0).fields();
		List<Section> sections = repeated(request, REPEATED_REQUEST_COUNT);

		CodecPair pair = QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION);
		List<SectionReport> reports = new ArrayList<>(sections.size());
		for (int i = 0; i < sections.size(); i++) {
			reports.add(QpackRoundTrip.runSection("static-repeated", i, sections.get(i), pair,
				InstructionDelivery.BEFORE_SECTION));
		}

		Report report = new Report("static-repeated", pair.describe(), reports);
		assertEquals(0.0, report.subsequentSectionShrinkage(), 1e-9);
	}

	/**
	 * On <b>repetitive</b> traffic — the traffic a dynamic table exists for — a 4 KB and a 64 KB table
	 * must beat the static-only encoder on total cost, both QPACK streams included, at every
	 * blocked-stream limit.
	 * <p>
	 * Two exclusions, both deliberate. <b>256 B</b>: an entry is evicted there before it is reused
	 * often enough to repay its insertion, so a win would be an accident of the corpus. <b>The default
	 * corpus</b>, whose fields are 60 % novel: {@link QpackDynamicEncoder} inserts on first sight, so a
	 * field never seen again costs its whole length on the encoder stream and saves nothing — that is
	 * the encoder's recorded insertion policy behaving as specified, not a regression, and asserting a
	 * win against it would be asserting that a table helps traffic that never repeats.
	 */
	@Test
	public void aUsefullySizedTableBeatsTheStaticEncoderOnRepetitiveTraffic() throws QpackException {
		for (long capacity : new long[]{4096, 65536}) {
			for (int blockedStreams : BLOCKED_STREAMS) {
				Workload workload = repetitiveWorkload(capacity, blockedStreams);
				long staticBytes = QpackRoundTrip
					.run(workload, QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION))
					.encodedBytes();

				RunResult result = run(workload, InstructionDelivery.BEFORE_SECTION);
				long dynamicBytes = result.report().encodedBytes()
									+ result.encoderStreamBytes() + result.decoderStreamBytes();

				assertTrue(workload.label() + ": " + dynamicBytes + " vs " + staticBytes,
					dynamicBytes < staticBytes);
			}
		}
	}

	// endregion
	// region never-indexed

	/**
	 * FR-022 across the round trip: a never-indexed field carries the RFC 9204 §7.1 {@code N} bit, so
	 * the decoder reports {@link QpackField#neverIndexed()}, and it is never inserted — which is
	 * observable as the name never appearing in the decoder's table.
	 * <p>
	 * Only asserted above capacity 0, where {@link QpackDynamicEncoder} deliberately suppresses
	 * {@code N} to stay byte-identical to {@link QpackStaticEncoder} (its recorded FR-022/FR-040
	 * tie-break).
	 */
	@Test
	public void neverIndexedFieldsKeepTheirMarkerAndNeverEnterTheTable() throws QpackException {
		for (long capacity : CAPACITIES) {
			if (capacity == 0) continue;
			for (int blockedStreams : BLOCKED_STREAMS) {
				Workload workload = matrixWorkload(capacity, blockedStreams);
				RunResult result = run(workload, InstructionDelivery.BEFORE_SECTION);
				assertTrue(workload.label(), result.neverIndexedFieldsSeen() > 0);
			}
		}
	}

	// endregion
	// region regressions this matrix found

	/**
	 * A table three entries wide against sections of ten distinct fields, so a section evicts entries
	 * it inserted and referenced <b>itself</b>. Two defects met here, both invisible at 4 KB and both
	 * fatal on the wire:
	 * <ol>
	 *     <li>nothing is pinned until the section is tracked, so a reference planned early in a section
	 *     could name an entry a later line of the same section evicted — the encoder now repairs such a
	 *     line to a literal;</li>
	 *     <li>a Required Insert Count more than {@code MaxEntries} ahead of the Known Received Count
	 *     does not fail to reconstruct at the peer, it reconstructs to a <i>different</i> value (RFC
	 *     9204 §4.5.1.1's modulus is {@code 2 × MaxEntries}), which the peer then rejects as a
	 *     reference to an evicted entry.</li>
	 * </ol>
	 * Deterministic, unlike the generated matrix cell that found them.
	 */
	@Test
	public void aTableNarrowerThanOneSectionStillRoundTrips() throws QpackException {
		List<QpackField> fields = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			fields.add(new QpackField(HttpHeaders.of("x-thrash-" + i),
				encodeAscii("0123456789abcdefghijklmnopqrstuvwxyz-" + i)));
		}
		List<Section> sections = repeated(fields, 4);

		for (int blockedStreams : new int[]{1, 16}) {
			for (InstructionDelivery delivery : InstructionDelivery.values()) {
				String label = "thrash/" + blockedStreams + '/' + delivery;
				RunResult result = run(label, 256, blockedStreams,
					QpackWorkloadGenerator.DEFAULT_NEVER_INDEXED_NAMES, sections, delivery);

				assertEquals(label, 4, result.report().sectionCount());
				assertTrue(label, result.evictedCount() > 0);
			}
		}
	}

	/**
	 * FR-040, the case {@code QpackCapacityZeroParityTest}'s corpus does not reach: a never-indexed
	 * name the QPACK static table carries with an <b>empty</b> value. The never-indexed rule must not
	 * skip the exact static index there — that line is an Indexed Field Line, it has no {@code N} bit
	 * to carry, it stores nothing anywhere, and skipping it spends one byte phase 1 does not.
	 */
	@Test
	public void aNeverIndexedNameMatchingAStaticEntryExactlyIsStillIndexedAtCapacityZero() {
		QpackStaticEncoder staticEncoder = new QpackStaticEncoder();
		QpackDynamicEncoder dynamicEncoder =
			new QpackDynamicEncoder(0, 0, 0, 0, Set.of("authorization", "proxy-authorization", "set-cookie"));

		for (String name : new String[]{"authorization", "set-cookie"}) {
			List<QpackField> fields = List.of(new QpackField(HttpHeaders.of(name), new byte[0]));
			byte[] expected = staticEncoder.encode(fields).asArray();
			byte[] actual = dynamicEncoder.encode(4, fields).asArray();
			assertArrayEquals(name, expected, actual);
		}
	}

	// endregion
	// region harness

	private static Workload matrixWorkload(long capacity, int blockedStreams) {
		return QpackWorkloadGenerator.builderFor(capacity, blockedStreams)
			.withSectionCount(24)
			.build()
			.generate();
	}

	/**
	 * A workload whose fields overwhelmingly repeat — a browser talking to one origin, rather than the
	 * matrix corpus's deliberately adversarial 60 % novelty.
	 */
	private static Workload repetitiveWorkload(long capacity, int blockedStreams) {
		return QpackWorkloadGenerator.builderFor(capacity, blockedStreams)
			.withLabel("repetitive capacity=" + capacity + " blockedStreams=" + blockedStreams)
			.withSectionCount(24)
			.withStaticExactRate(0.10)
			.withStaticNameRate(0.05)
			.withNeverIndexedRate(0.05)
			.withRecurringRate(0.75)
			.withVocabularySize(24)
			.build()
			.generate();
	}

	/** The same field section on {@code count} successive request-stream ids (RFC 9000 §2.1 spacing). */
	private static List<Section> repeated(List<QpackField> fields, int count) {
		List<Section> sections = new ArrayList<>(count);
		for (int i = 0; i < count; i++) sections.add(new Section(4L * i, fields));
		return sections;
	}

	private static RunResult run(Workload workload, InstructionDelivery delivery) throws QpackException {
		return run(workload.label() + '/' + delivery, (int) workload.capacity(), workload.blockedStreams(),
			workload.neverIndexedNames(), workload.sections(), delivery);
	}

	private static RunResult run(
		String label, int capacity, int blockedStreams, Set<HttpHeader> neverIndexedNames,
		List<Section> sections, InstructionDelivery delivery
	) throws QpackException {
		DynamicPair pair = new DynamicPair(capacity, blockedStreams, neverIndexedNames);
		try {
			List<SectionReport> reports = new ArrayList<>(sections.size());
			List<Long> costs = new ArrayList<>(sections.size());
			for (int i = 0; i < sections.size(); i++) {
				Section section = sections.get(i);
				pair.beginSection(section.streamId());
				try {
					reports.add(QpackRoundTrip.runSection(label, i, section, pair, delivery));
				} catch (QpackException e) {
					// A round trip must not raise one, so the codec state at the moment it did is the
					// whole diagnosis and the exception alone names no cell.
					throw new AssertionError(label + " section #" + i + " on stream " + section.streamId() +
											 ' ' + pair.describeState(), e);
				}
				costs.add(reports.get(i).encodedBytes() + pair.sectionEncoderStreamBytes);
				// Per section rather than once at the end, so an entry that is inserted and then evicted
				// is still caught at a capacity small enough to evict.
				pair.assertNoNeverIndexedNameWasInserted();
			}
			return new RunResult(new Report(label, pair.describe(), reports), costs, pair);
		} finally {
			pair.encoderStream.recycle();
		}
	}

	/**
	 * The two ends must finish a run holding the same table and the same accounting: everything the
	 * encoder inserted reached the decoder, everything the decoder acknowledged reached the encoder,
	 * and neither stream is left mid-instruction.
	 */
	private static void assertCodecsAgree(String cell, RunResult result) {
		DynamicPair pair = result.pair();
		assertEquals(cell, pair.encoder.insertCount(), pair.decoder.insertCount());
		assertEquals(cell, pair.encoder.insertCount(), pair.encoder.knownReceivedCount());
		assertEquals(cell, 0, pair.encoder.blockedStreamCount());
		assertEquals(cell, 0, pair.decoder.pendingInsertCountIncrement());
		assertEquals(cell, 0, pair.encoderStream.pendingBytes());
		assertFalse(cell, pair.encoder.hasPendingInstructions());
		assertEquals(cell, pair.encoder.capacity(), pair.decoder.capacity());
	}

	private record RunResult(Report report, List<Long> sectionCosts, DynamicPair pair) {
		long encoderStreamBytes() {return pair.encoderStreamBytes;}

		long decoderStreamBytes() {return pair.decoderStreamBytes;}

		int blockedSections() {return pair.blockedSections;}

		int neverIndexedFieldsSeen() {return pair.neverIndexedFieldsSeen;}

		long insertCount() {return pair.encoder.insertCount();}

		long evictedCount() {return pair.encoder.evictedCount();}

		long dynamicReferences() {return pair.encoder.dynamicReferences();}

		boolean usesDynamicTable() {return pair.encoder.usesDynamicTable();}

		/**
		 * How much cheaper, on average, requests 2..N are than request 1 — counting the encoder-stream
		 * bytes each caused, so the table request 1 primed is charged to request 1.
		 */
		double subsequentCostShrinkage() {
			long first = sectionCosts.get(0);
			if (first == 0 || sectionCosts.size() < 2) return 0.0;
			long rest = 0;
			for (int i = 1; i < sectionCosts.size(); i++) rest += sectionCosts.get(i);
			return 1.0 - (double) rest / (sectionCosts.size() - 1) / first;
		}
	}

	/**
	 * One {@link QpackDynamicEncoder} and the {@link QpackDynamicDecoder} that must understand it,
	 * with the two QPACK unidirectional streams simulated in process as real wire bytes.
	 * <p>
	 * <b>{@code ByteBuf} ownership</b>: {@link QpackEncoderStreamReader#feed} takes the encoder-stream
	 * buffer on every path including a throw, so nothing is recycled around it;
	 * {@link QpackDynamicEncoder#consumeDecoderStream} does the opposite and leaves the buffer to this
	 * class, hence the {@code finally}. A {@link Blocked} section is handed back unconsumed and is
	 * owned here until it is passed back in or recycled.
	 */
	private static final class DynamicPair implements CodecPair {
		final QpackDynamicEncoder encoder;
		final QpackDynamicDecoder decoder;
		final QpackEncoderStreamReader encoderStream;

		private final Set<HttpHeader> neverIndexedNames;

		private long currentStreamId = QpackDynamicEncoder.NO_STREAM;
		private long requiredInsertCount;

		long encoderStreamBytes;
		long decoderStreamBytes;
		long sectionEncoderStreamBytes;
		int blockedSections;
		int neverIndexedFieldsSeen;

		DynamicPair(int capacity, int blockedStreams, Set<HttpHeader> neverIndexedNames) {
			this.neverIndexedNames = neverIndexedNames;
			this.encoder = new QpackDynamicEncoder(capacity, capacity, blockedStreams,
				MAX_OUTSTANDING_SECTIONS, fieldNames(neverIndexedNames));
			this.decoder = new QpackDynamicDecoder(capacity, blockedStreams, UNBOUNDED_FIELD_SECTION);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
		}

		void beginSection(long streamId) {
			currentStreamId = streamId;
			requiredInsertCount = 0;
			sectionEncoderStreamBytes = 0;
		}

		@Override
		public QpackEncoder encoder() {
			return fields -> encoder.encode(currentStreamId, fields);
		}

		@Override
		public QpackDecoder decoder() {
			return this::decodeSection;
		}

		private List<QpackField> decodeSection(ByteBuf section) throws QpackException {
			SectionResult result = decoder.decodeOrBlock(section);
			if (result instanceof Blocked blocked) {
				blockedSections++;
				try {
					// The insertions this section references have not been delivered yet — this is
					// InstructionDelivery.AFTER_SECTION, and delivering them is what unblocks it.
					deliverEncoderInstructions();
					result = decoder.decodeOrBlock(blocked.section());
				} catch (QpackException | RuntimeException | Error e) {
					blocked.section().recycle();
					throw e;
				}
				if (result instanceof Blocked stillBlocked) {
					stillBlocked.section().recycle();
					throw new AssertionError("a section still blocked after every insertion was delivered, at " +
											 "Required Insert Count " + stillBlocked.requiredInsertCount());
				}
			}
			Decoded decoded = (Decoded) result;
			requiredInsertCount = decoded.requiredInsertCount();
			assertNeverIndexedMarked(decoded.fields());
			return decoded.fields();
		}

		/**
		 * FR-022 on every decoded section: a field whose name is in the never-indexed set must come
		 * back marked. An <b>exact</b> static-table hit is exempt — such a line is an Indexed Field
		 * Line, which has no {@code N} bit and stores nothing anywhere.
		 */
		private void assertNeverIndexedMarked(List<QpackField> fields) {
			if (!encoder.usesDynamicTable()) return;
			for (QpackField field : fields) {
				if (!neverIndexedNames.contains(field.name())) continue;
				if (QpackStaticTable.indexOfNameAndValue(field.name(), field.value()) != -1) continue;
				neverIndexedFieldsSeen++;
				assertTrue("a never-indexed field lost its N bit: " + field.name(), field.neverIndexed());
			}
		}

		/**
		 * FR-022's other half, asserted once per run because it is a property of the table rather than
		 * of a section: no never-indexed name ever entered it. Read off the decoder's table, so it is
		 * the entries that actually crossed the encoder stream that are checked, not the encoder's
		 * intent. {@code findName} is encoder-only, hence the scan over the available absolute indices.
		 */
		void assertNoNeverIndexedNameWasInserted() {
			QpackDynamicTable table = decoder.table();
			for (long index = table.droppedCount(); index < table.insertCount(); index++) {
				assertFalse("a never-indexed field was inserted: " + table.nameAt(index),
					neverIndexedNames.contains(table.nameAt(index)));
			}
		}

		@Override
		public void deliverEncoderInstructions() throws QpackException {
			List<EncoderInstruction> instructions = encoder.drainPendingInstructions();
			if (instructions.isEmpty()) return;
			ByteBuf buf = writeInstructions(instructions);
			int bytes = buf.readRemaining();
			encoderStreamBytes += bytes;
			sectionEncoderStreamBytes += bytes;
			// feed() owns buf on every path, a throw included.
			encoderStream.feed(buf);
		}

		@Override
		public void deliverDecoderInstructions(long streamId) throws QpackException {
			List<DecoderInstruction> instructions = new ArrayList<>(2);
			if (requiredInsertCount > 0) {
				// RFC 9204 §4.4.1 acknowledges every insertion up to the section's Required Insert Count.
				instructions.add(new SectionAcknowledgment(streamId));
				decoder.onInsertCountAnnounced(requiredInsertCount);
			}
			long increment = decoder.pendingInsertCountIncrement();
			if (increment > 0) {
				instructions.add(new InsertCountIncrement(increment));
				decoder.onInsertCountAnnounced(decoder.insertCount());
			}
			if (instructions.isEmpty()) return;

			ByteBuf buf = writeInstructions(instructions);
			decoderStreamBytes += buf.readRemaining();
			try {
				encoder.consumeDecoderStream(buf);
			} finally {
				buf.recycle();
			}
		}

		@Override
		public String describe() {
			return "QpackDynamicEncoder(" + encoder.capacity() + "B) -> QpackDynamicDecoder";
		}

		/** Both tables side by side — a divergence between them is what any failure here means. */
		String describeState() {
			QpackDynamicTable decoderTable = decoder.table();
			return "encoder{capacity=" + encoder.capacity() +
				   ", insertCount=" + encoder.insertCount() +
				   ", evicted=" + encoder.evictedCount() +
				   ", knownReceived=" + encoder.knownReceivedCount() +
				   ", blockedStreams=" + encoder.blockedStreamCount() +
				   "} decoder{capacity=" + decoder.capacity() +
				   ", insertCount=" + decoder.insertCount() +
				   ", available=[" + decoderTable.droppedCount() + ", " + decoderTable.insertCount() +
				   "), size=" + decoderTable.size() + '}';
		}

		private static ByteBuf writeInstructions(List<? extends Instruction> instructions) {
			int length = 0;
			for (Instruction instruction : instructions) length += instruction.encodedLength();
			ByteBuf out = ByteBufPool.allocate(length);
			for (Instruction instruction : instructions) instruction.writeTo(out);
			return out;
		}

		private static Set<String> fieldNames(Set<HttpHeader> names) {
			Set<String> fieldNames = new HashSet<>(names.size());
			for (HttpHeader name : names) fieldNames.add(name.toString());
			return fieldNames;
		}
	}

	// endregion
}
