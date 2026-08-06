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
import io.activej.http3.Http3Headers;
import io.activej.http3.qpack.QpackRoundTrip.CodecPair;
import io.activej.http3.qpack.QpackRoundTrip.InstructionDelivery;
import io.activej.http3.qpack.QpackRoundTrip.Report;
import io.activej.http3.qpack.QpackRoundTrip.SectionReport;
import io.activej.http3.qpack.QpackWorkloadGenerator.Coverage;
import io.activej.http3.qpack.QpackWorkloadGenerator.LengthDistribution;
import io.activej.http3.qpack.QpackWorkloadGenerator.Section;
import io.activej.http3.qpack.QpackWorkloadGenerator.Workload;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.qpack.QpackRoundTrip.UNBOUNDED_FIELD_SECTION;
import static org.junit.Assert.*;

/**
 * Proves the T003 generator infrastructure itself works — that
 * {@link QpackWorkloadGenerator} is deterministic and actually reaches the coverage it claims, and
 * that {@link QpackRoundTrip} drives a {@link QpackEncoder} / {@link QpackDecoder} pair correctly
 * and leaks nothing.
 * <p>
 * The only concrete pair that exists before T027/T029 is the capacity-0
 * {@link QpackStaticEncoder} / {@link QpackStaticDecoder}, so that is what everything here runs
 * against. That is a genuine limit on what this file can assert: it verifies the <b>harness</b>,
 * not a dynamic table. T018 ({@code QpackDynamicRoundTripTest}) is what turns the same
 * infrastructure on the real thing across capacities {0, 256 B, 4 KB, 64 KB} × blocked-streams
 * {0, 1, 16}; {@link #everyT018MatrixCellIsGeneratedAndRoundTrips()} below already walks that
 * matrix, through the static pair, so the plumbing at every cell is exercised today.
 * <p>
 * This is a <b>permanent</b> test, not a throwaway smoke run: a generator whose knobs quietly stop
 * having an effect produces a narrower corpus that still passes, which is the precise failure mode
 * T003 exists to prevent.
 */
public class QpackRoundTripHarnessTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The matrix T018 will drive, walked here through the static pair. */
	private static final long[] CAPACITIES = {0, 256, 4096, 65536};
	private static final int[] BLOCKED_STREAMS = {0, 1, 16};

	/**
	 * The one contract-mandated never-indexed name that is both outside the QPACK static table and
	 * interned by {@code core-http} with uppercase — so it is exactly the name the generator's casing
	 * knob decides the fate of.
	 */
	private static final HttpHeader PROXY_AUTHORIZATION = HttpHeaders.of("proxy-authorization");

	// region generator

	@Test
	public void generatorIsDeterministic() {
		Workload a = QpackWorkloadGenerator.create().generate();
		Workload b = QpackWorkloadGenerator.create().generate();
		assertEquals(a, b);

		QpackWorkloadGenerator generator = QpackWorkloadGenerator.builder().withSeed(7).build();
		assertEquals(generator.generate(), generator.generate());
	}

	@Test
	public void differentSeedsProduceDifferentWorkloads() {
		Workload a = QpackWorkloadGenerator.builder().withSeed(1).build().generate();
		Workload b = QpackWorkloadGenerator.builder().withSeed(2).build().generate();
		assertNotEquals(a, b);
	}

	@Test
	public void matrixCellsHaveDistinctDerivedSeeds() {
		List<Workload> workloads = new ArrayList<>();
		for (long capacity : CAPACITIES) {
			for (int blockedStreams : BLOCKED_STREAMS) {
				workloads.add(QpackWorkloadGenerator.builderFor(capacity, blockedStreams).build().generate());
			}
		}
		for (int i = 0; i < workloads.size(); i++) {
			for (int j = i + 1; j < workloads.size(); j++) {
				assertNotEquals("cells " + i + " and " + j + " generated the same workload",
					workloads.get(i).sections(), workloads.get(j).sections());
			}
		}
	}

	/**
	 * The point of the whole class: the knobs reach the four things T002 found RFC 9204 Appendix B
	 * does <b>not</b> cover, minus the error paths, which a round-trip corpus is the wrong tool for.
	 */
	@Test
	public void coverageReachesTheAppendixBHoles() {
		Coverage coverage = QpackWorkloadGenerator.builder()
			.withSectionCount(64)
			.build()
			.generate()
			.coverage();

		// Huffman, both branches of the FR-029 "only when it shortens" decision.
		assertTrue(coverage + " has no Huffman-coded value", coverage.huffmanCodedValues() > 0);
		assertTrue(coverage + " has no value that Huffman would expand", coverage.literalValues() > 0);

		// Never-indexed fields (RFC 9204 §7.1, FR-022).
		assertTrue(coverage + " has no never-indexed field", coverage.neverIndexedFields() > 0);

		// Intra-section repetition: what drives an encoder to a post-base representation.
		assertTrue(coverage + " has no whole-field intra-section repeat", coverage.intraSectionRepeats() > 0);
		assertTrue(coverage + " has no name-only intra-section repeat", coverage.intraSectionNameRepeats() > 0);

		// And the representations phase 1 already has, so a dynamic encoder is compared against them.
		assertTrue(coverage + " has no exact static-table hit", coverage.staticExactHits() > 0);
		assertTrue(coverage + " has no static-table name reference", coverage.staticNameHits() > 0);
		assertTrue(coverage + " has no literal name", coverage.literalNameFields() > 0);
		assertTrue(coverage + " has no empty value", coverage.emptyValues() > 0);
		assertTrue(coverage + " has no repeated field at all", coverage.recurringFields() > 0);
		assertTrue(coverage + " has no distinct fields", coverage.distinctFields() > 1);
	}

	/**
	 * The skip-counting mechanism, in the narrow mode it now belongs to — and the default's wider
	 * coverage, asserted from the same fixture so the two cannot drift apart.
	 * <p>
	 * {@code Proxy-Authorization} is one of the three contract-mandated never-indexed names, it is
	 * <b>not</b> in the QPACK static table, and {@code core-http} registers it with uppercase — so it
	 * is the one name {@code withUppercaseRegistryNamesAllowed(false)} drops, and that drop is
	 * <b>counted</b>, which is the difference between a known gap and a silent one. The exclusion used
	 * to be the default, as a workaround for the encoder casing defect fixed below; now that every
	 * encoder lowercases its literal name octets the default admits the name, nothing is skipped, and
	 * the corpus is wider by exactly that much.
	 */
	@Test
	public void excludingUppercaseRegistryNamesIsOptInAndTheDropIsCounted() {
		assertEquals("proxy-authorization must be outside the QPACK static table for this to mean anything",
			-1, QpackStaticTable.indexOfName(PROXY_AUTHORIZATION));
		assertTrue("core-http must register proxy-authorization with uppercase for this to mean anything",
			QpackWorkloadGenerator.hasUppercase(PROXY_AUTHORIZATION));

		// Narrow mode, opted into on purpose: the name is dropped, and the drop is visible.
		Workload narrowed = neverIndexedHeavy().withUppercaseRegistryNamesAllowed(false).build().generate();
		Coverage narrowedCoverage = narrowed.coverage();
		assertTrue("Proxy-Authorization should be skipped and counted, coverage was " + narrowedCoverage,
			narrowedCoverage.namesSkippedForUppercase() > 0);
		assertTrue("the two admissible never-indexed names should still appear, coverage was " + narrowedCoverage,
			narrowedCoverage.neverIndexedFields() > 0);
		assertFalse("the narrowed workload must not carry " + PROXY_AUTHORIZATION, carries(narrowed, PROXY_AUTHORIZATION));

		// The default: nothing is excluded, so nothing is counted and the name is really in the corpus.
		Workload wide = neverIndexedHeavy().build().generate();
		Coverage wideCoverage = wide.coverage();
		assertEquals("the default admits uppercase-registry names, so nothing is skipped: " + wideCoverage,
			0, wideCoverage.namesSkippedForUppercase());
		assertTrue("the default workload should carry " + PROXY_AUTHORIZATION, carries(wide, PROXY_AUTHORIZATION));
	}

	/** Never-indexed fields at half the workload, so the three-name set is drawn from often. */
	private static QpackWorkloadGenerator.Builder neverIndexedHeavy() {
		return QpackWorkloadGenerator.builder()
			.withSectionCount(64)
			.withNeverIndexedRate(0.5)
			.withStaticExactRate(0.1)
			.withStaticNameRate(0.1)
			.withRecurringRate(0.2);
	}

	private static boolean carries(Workload workload, HttpHeader name) {
		for (Section section : workload.sections()) {
			for (QpackField field : section.fields()) {
				if (field.name().equals(name)) return true;
			}
		}
		return false;
	}

	/**
	 * A workload built with the default knobs round-trips through the static pair even though it now
	 * carries names {@code core-http} interns with uppercase — which is the property that lets the
	 * exclusion be off by default rather than merely being untested.
	 */
	@Test
	public void theWidenedDefaultCorpusRoundTripsThroughTheStaticPair() throws QpackException {
		Workload workload = neverIndexedHeavy().build().generate();
		assertTrue("this test is about the uppercase-registry name", carries(workload, PROXY_AUTHORIZATION));

		Report report = QpackRoundTrip.run(workload, QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION));
		assertEquals(workload.coverage().fields(), report.fieldCount());
	}

	@Test
	public void lengthDistributionsShiftTheAccountedSize() {
		long shortBiased = generatorWith(LengthDistribution.SHORT_BIASED).generate().accountedSize();
		long uniform = generatorWith(LengthDistribution.UNIFORM).generate().accountedSize();
		long longBiased = generatorWith(LengthDistribution.LONG_BIASED).generate().accountedSize();

		assertTrue("SHORT_BIASED " + shortBiased + " should be under UNIFORM " + uniform, shortBiased < uniform);
		assertTrue("UNIFORM " + uniform + " should be under LONG_BIASED " + longBiased, uniform < longBiased);
	}

	private static QpackWorkloadGenerator generatorWith(LengthDistribution distribution) {
		return QpackWorkloadGenerator.builder()
			.withSeed(42)
			.withSectionCount(48)
			.withValueLength(1, 96)
			// Only freshly generated values obey the distribution; static-table hits carry fixed values.
			.withStaticExactRate(0)
			.withRecurringRate(0)
			.withIntraSectionRepeatRate(0)
			.withLengthDistribution(distribution)
			.build();
	}

	@Test
	public void vocabularySizeGrowsWithCapacity() {
		int atZero = distinctFields(0);
		int at4k = distinctFields(4096);
		int at64k = distinctFields(65536);
		assertTrue("distinct fields should grow with capacity: " + atZero + ", " + at4k + ", " + at64k,
			atZero < at4k && at4k < at64k);
	}

	private static int distinctFields(long capacity) {
		return QpackWorkloadGenerator.builder()
			.withSeed(11)
			.withCapacity(capacity)
			.withSectionCount(64)
			.withStaticExactRate(0)
			.withStaticNameRate(0)
			.withRecurringRate(1.0)
			.withNeverIndexedRate(0)
			.withIntraSectionRepeatRate(0)
			.build()
			.generate()
			.coverage()
			.distinctFields();
	}

	@Test
	public void builderRefusesAnImpossibleRateMix() {
		assertThrows(IllegalArgumentException.class, () -> QpackWorkloadGenerator.builder()
			.withStaticExactRate(0.5)
			.withStaticNameRate(0.5)
			.withRecurringRate(0.5)
			.build());
		assertThrows(IllegalArgumentException.class, () -> QpackWorkloadGenerator.builder()
			.withHuffmanEligibleRate(1.5)
			.build());
		assertThrows(IllegalArgumentException.class, () -> QpackWorkloadGenerator.builder()
			.withFieldsPerSection(8, 4)
			.build());
	}

	// endregion

	// region harness

	/**
	 * The self-check the task asks for: the harness genuinely round-trips against the one concrete
	 * codec pair that exists today.
	 */
	@Test
	public void roundTripsThroughTheStaticPair() throws QpackException {
		Workload workload = QpackWorkloadGenerator.builder().withSectionCount(64).build().generate();
		Report report = QpackRoundTrip.run(workload, QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION));

		assertEquals(workload.sections().size(), report.sectionCount());
		assertEquals(workload.coverage().fields(), report.fieldCount());
		assertEquals(workload.accountedSize(), report.accountedSize());
		assertTrue(report.toString(), report.encodedBytes() > 0);
		// The accounted size charges 32 B per field that the wire never carries, so any sane encoder
		// beats it. This is the floor a dynamic encoder must beat by far more (SC-001).
		assertTrue(report.toString(), report.compressionRatio() < 1.0);
	}

	/**
	 * Every cell of T018's matrix generates a workload and round-trips it. The <b>codec</b> is the
	 * static pair at every cell, because nothing else exists yet — so this asserts that the matrix
	 * plumbing works, not that a dynamic table does.
	 */
	@Test
	public void everyT018MatrixCellIsGeneratedAndRoundTrips() throws QpackException {
		for (long capacity : CAPACITIES) {
			for (int blockedStreams : BLOCKED_STREAMS) {
				Workload workload = QpackWorkloadGenerator.builderFor(capacity, blockedStreams)
					.withSectionCount(24)
					.build()
					.generate();

				assertEquals(capacity, workload.capacity());
				assertEquals(blockedStreams, workload.blockedStreams());

				Report report = QpackRoundTrip.run(workload,
					QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION));
				assertEquals(workload.label(), 24, report.sectionCount());
			}
		}
	}

	/**
	 * A capacity-0 pair emits no instruction in either direction, so moving the delivery point across
	 * the section must change nothing. Once T027/T029 land, the same call with
	 * {@link InstructionDelivery#AFTER_SECTION} is what produces a <b>blocked</b> section — this test
	 * pins the "no instructions, no difference" half of that contract now.
	 */
	@Test
	public void instructionDeliveryOrderIsIndifferentForACapacityZeroPair() throws QpackException {
		Workload workload = QpackWorkloadGenerator.builderFor(0, 0).withSectionCount(24).build().generate();

		Report before = QpackRoundTrip.run(workload,
			QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION), InstructionDelivery.BEFORE_SECTION);
		Report after = QpackRoundTrip.run(workload,
			QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION), InstructionDelivery.AFTER_SECTION);

		assertEquals(before.sections(), after.sections());
	}

	/** {@code runSection} is usable on its own, which is what interleaving two streams needs. */
	@Test
	public void sectionsCanBeDrivenIndividuallyAndInterleaved() throws QpackException {
		Workload workload = QpackWorkloadGenerator.builder().withSectionCount(8).build().generate();
		CodecPair pair = QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION);

		List<Section> sections = workload.sections();
		List<SectionReport> reports = new ArrayList<>();
		// Odd indices first, then even: a legal interleaving, and one no whole-workload run produces.
		for (int i = 1; i < sections.size(); i += 2) {
			reports.add(QpackRoundTrip.runSection(workload.label(), i, sections.get(i), pair,
				InstructionDelivery.BEFORE_SECTION));
		}
		for (int i = 0; i < sections.size(); i += 2) {
			reports.add(QpackRoundTrip.runSection(workload.label(), i, sections.get(i), pair,
				InstructionDelivery.BEFORE_SECTION));
		}
		assertEquals(sections.size(), reports.size());
	}

	/**
	 * The SC-001 baseline. A static table learns nothing, so sections 2..N are no smaller than
	 * section 1 in any systematic way — the shrinkage sits around zero. SC-001 asks a 4 KB dynamic
	 * table for ≥ 0.60 on the same shape of workload, so recording the floor here is what makes that
	 * number mean something rather than being asserted into existence.
	 * <p>
	 * The bound is loose because section content still varies; it is not flaky, because the seed is
	 * fixed.
	 */
	@Test
	public void aStaticTableLearnsNothingAcrossSections() throws QpackException {
		Workload workload = QpackWorkloadGenerator.builder()
			.withSeed(99)
			.withSectionCount(48)
			.withFieldsPerSection(12, 12)
			.withValueLength(16, 16)
			.build()
			.generate();

		Report report = QpackRoundTrip.run(workload, QpackRoundTrip.staticTablePair(UNBOUNDED_FIELD_SECTION));
		double shrinkage = report.subsequentSectionShrinkage();
		assertTrue("a static table cannot compress repetition, yet shrinkage was " + shrinkage +
				" — " + report,
			Math.abs(shrinkage) < 0.35);
		assertTrue("SC-001's 0.60 must not already be met by a static table: " + report, shrinkage < 0.60);
	}

	@Test
	public void aMismatchIsReportedWithTheOffendingFieldAndTheWire() {
		Workload workload = QpackWorkloadGenerator.builder().withSectionCount(1).build().generate();
		// A decoder that silently drops the last field: the shape of a real desynchronisation bug.
		CodecPair broken = CodecPair.of(new QpackStaticEncoder(), buf -> {
			List<QpackField> fields = new ArrayList<>(new QpackStaticDecoder(UNBOUNDED_FIELD_SECTION).decode(buf));
			fields.remove(fields.size() - 1);
			return fields;
		});

		AssertionError error = assertThrows(AssertionError.class, () -> QpackRoundTrip.run(workload, broken));
		String message = error.getMessage();
		assertTrue(message, message.contains("did not reproduce the field section"));
		assertTrue(message, message.contains("field count differs"));
		assertTrue(message, message.contains("wire:"));
		assertTrue(message, message.contains(workload.label()));
	}

	// endregion

	// region the defect the casing rule exists for

	/**
	 * <b>The defect this region was written to record, now fixed and pinned from the other side.</b>
	 * <p>
	 * RFC 9114 §4.1.1 requires field names to be lowercase on the wire. {@code Http3Headers}
	 * lowercases on the way out — and then {@code toQpack} interns through {@link HttpHeaders}, whose
	 * registry hands back its <b>own</b> canonical spelling, undoing it. {@link QpackStaticEncoder}
	 * used to write that token's bytes verbatim as the literal name, so {@code accept-charset} — a
	 * perfectly legal HTTP/3 field that the QPACK static table does not carry — left as
	 * {@code Accept-Charset}. It now writes {@link QpackField#lowercaseNameBytes()} instead, which is
	 * the funnel every {@link QpackEncoder} shares, so the round trip closes.
	 * <p>
	 * {@link QpackWorkloadGenerator} therefore no longer excludes such names — the exclusion that was
	 * this defect's workaround is now the opt-in narrow mode
	 * ({@link #excludingUppercaseRegistryNamesIsOptInAndTheDropIsCounted()}), and the default corpus
	 * carries them. The narrow, wire-level statement of the same rule lives in
	 * {@link QpackEncoderFieldNameCaseTest}.
	 */
	@Test
	public void uppercaseRegistryNamesAreLowercasedOnTheWire() throws QpackException {
		// The production outbound path, verbatim: a lowercased Http3Headers.Field through toQpack.
		List<QpackField> encodedFields =
			Http3Headers.toQpack(List.of(new Http3Headers.Field("accept-charset", "utf-8")));

		ByteBuf wire = new QpackStaticEncoder().encode(encodedFields);
		List<QpackField> decoded = new QpackStaticDecoder(UNBOUNDED_FIELD_SECTION).decode(wire);

		assertEquals(1, decoded.size());
		assertEquals("accept-charset must not be in the QPACK static table for this test to mean anything",
			-1, QpackStaticTable.indexOfName(HttpHeaders.of("accept-charset")));
		assertFalse("the wire literal name must not carry uppercase (RFC 9114 §4.1.1)",
			decoded.get(0).nameHadUppercase());
		assertEquals("the field must survive its own encoder", encodedFields.get(0), decoded.get(0));
	}

	/** The same name as a static-table name reference was unaffected even then — which is why the defect was narrow. */
	@Test
	public void staticTableNamesAreImmuneToTheCasingDefect() throws QpackException {
		List<QpackField> encodedFields =
			Http3Headers.toQpack(List.of(new Http3Headers.Field("content-type", "text/plain")));

		ByteBuf wire = new QpackStaticEncoder().encode(encodedFields);
		List<QpackField> decoded = new QpackStaticDecoder(UNBOUNDED_FIELD_SECTION).decode(wire);

		assertEquals(encodedFields, decoded);
		assertFalse(decoded.get(0).nameHadUppercase());
	}

	// endregion
}
