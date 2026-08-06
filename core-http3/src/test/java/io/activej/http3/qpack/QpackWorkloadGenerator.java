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

import io.activej.common.builder.AbstractBuilder;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * A deterministic, parameterized generator of QPACK workloads — sequences of field sections shaped
 * to exercise a dynamic-table codec pair (T003).
 *
 * <h2>Why this exists at all: the generator path, taken deliberately</h2>
 * T001 verified that the third-party {@code qpackers/qifs} corpus is MIT-licensed and therefore
 * legally vendorable, but that every encoded artefact in it targets a pre-RFC QPACK <b>draft</b>
 * (qpack-02/03/05) whose absolute-index arithmetic and field-section prefix differ from RFC 9204 by
 * silent off-by-ones. A draft-era vector decodes <b>without error</b> and yields the <b>wrong
 * field</b>. Research D-11 therefore records: do not vendor, generate in-repo. This class is that
 * generator.
 * <p>
 * <b>Residual limitation, stated rather than papered over</b> (T001): a corpus produced by
 * round-tripping one encoder against its own decoder <b>cannot catch a bug the two share</b>. If the
 * encoder and decoder agree on a wrong absolute index, every round trip passes. Third-party
 * interoperability therefore remains the job of the live interop harness on {@code master} and of
 * the RFC 9204 Appendix B fixtures (T002, {@link QpackVectors}), which are an independent oracle
 * because their bytes come from the RFC rather than from this code. This generator supplies
 * <b>breadth</b> — capacities, blocked-stream limits, eviction pressure, Huffman, never-indexed
 * fields, intra-section repetition — that Appendix B's six worked examples do not.
 *
 * <h2>What it is designed to cover that Appendix B does not</h2>
 * T002 found four holes in the Appendix B corpus. Each has a knob here:
 *
 * <table>
 *     <caption>Appendix B coverage holes and the parameter that reaches them</caption>
 *     <tr><th>Hole (T002)</th><th>Parameter</th></tr>
 *     <tr><td>No Huffman-coded string</td><td>{@link Builder#withHuffmanEligibleRate}</td></tr>
 *     <tr><td>No never-indexed literal</td><td>{@link Builder#withNeverIndexedRate} +
 *         {@link Builder#withNeverIndexedNames}</td></tr>
 *     <tr><td>No Literal With Post-Base Name Reference</td>
 *         <td>{@link Builder#withIntraSectionRepeatRate} +
 *         {@link Builder#withIntraSectionNameRepeatRate} — a name first inserted <i>while encoding
 *         the current section</i> and then referenced again inside it is exactly what makes an
 *         encoder reach for a post-base representation</td></tr>
 *     <tr><td>No error/malformed-input path</td><td><b>Not covered here, by design.</b> A generator
 *         emits well-formed input; malformed input is T019–T021's job and belongs in adversarial
 *         tests, not in a round-trip corpus</td></tr>
 * </table>
 *
 * <h2>Properties</h2>
 * <ul>
 *     <li><b>JDK only.</b> {@link Random} and nothing else — the zero-third-party rule holds in test
 *     scope too.</li>
 *     <li><b>Reactor-free.</b> No {@code Reactor}, no {@code Promise}, no eventloop, matching the
 *     package it lives in (ADR-016).</li>
 *     <li><b>Deterministic.</b> {@link #generate()} builds a fresh {@code Random(seed)} on every
 *     call, so one generator yields the same workload every time and two generators with the same
 *     parameters yield equal workloads. {@code java.util.Random}'s algorithm is specified by its
 *     Javadoc, so this holds across JDKs.</li>
 *     <li><b>Honest counting.</b> Every rate below is an <i>intent</i>. {@link Coverage} reports
 *     what the workload actually contains, classified by the same predicates the encoder uses
 *     ({@link QpackStaticTable#indexOfNameAndValue}, {@link QpackHuffman#encodedLength}) — so a
 *     test asserts achieved coverage, never a requested rate.</li>
 * </ul>
 *
 * <h2>Field-name casing: the constraint that used to be mandatory, now opt-in</h2>
 * {@code core-http}'s {@link HttpHeaders} registry is case-insensitive and hands back <b>its own</b>
 * canonical spelling for any name it pre-registers — {@code HttpHeaders.of("accept-charset")}
 * returns a token spelled {@code Accept-Charset}. {@link QpackStaticEncoder} used to write a literal
 * name by copying the token's bytes, so such a name went onto the wire <b>uppercase</b>, which RFC
 * 9114 §4.1.1 forbids, and came back with {@link QpackField#nameHadUppercase()} set — breaking
 * structural round-trip equality. Names in the QPACK static table were unaffected, because they are
 * sent as a name <i>reference</i> and never as literal octets. This generator excluded such names so
 * the corpus would not fail on a defect it did not exist to find.
 * <p>
 * <b>That {@code core-http3} defect is fixed</b>: every encoder now takes its literal name octets
 * from {@link QpackField#lowercaseNameBytes()}, so such names do round-trip
 * ({@code QpackRoundTripHarnessTest.uppercaseRegistryNamesAreLowercasedOnTheWire},
 * {@link QpackEncoderFieldNameCaseTest}). The exclusion is therefore <b>off by default</b>, which is
 * the widening a round-trip corpus wants: the default workload carries {@code Proxy-Authorization}
 * and every other uppercase-registry name, and an encoder that stops lowercasing its literal names
 * fails the round trip rather than quietly never being handed one.
 * {@link Builder#withUppercaseRegistryNamesAllowed}{@code (false)} restores the narrow mode for a
 * codec that is deliberately being exercised without that funnel, and every name it then drops is
 * <b>counted</b> rather than silent ({@link Coverage#namesSkippedForUppercase()}).
 *
 * @see QpackRoundTrip the harness that drives a workload through a {@link QpackEncoder} /
 * {@link QpackDecoder} pair
 */
public final class QpackWorkloadGenerator {
	/** The seed used unless a caller picks another, so an unseeded workload is still reproducible. */
	public static final long DEFAULT_SEED = 20260804L;

	/**
	 * The RFC 9204 §7.1 never-indexed default set from {@code contracts/qpack-dynamic.md}:
	 * {@code Authorization}, {@code Proxy-Authorization}, {@code Set-Cookie}. {@code Cookie} is
	 * deliberately absent — it is indexable.
	 */
	public static final Set<HttpHeader> DEFAULT_NEVER_INDEXED_NAMES = Set.of(
		HttpHeaders.of("authorization"),
		HttpHeaders.of("proxy-authorization"),
		HttpHeaders.of("set-cookie"));

	/**
	 * Static-table names whose table value is empty or rarely matched, so a generated value produces
	 * a Literal Field Line with Name Reference rather than an exact Indexed Field Line. These are
	 * also the names real traffic repeats, which is what a dynamic table is for.
	 */
	private static final List<HttpHeader> RECURRING_STATIC_NAMES = List.of(
		HttpHeaders.of("user-agent"),
		HttpHeaders.of("cookie"),
		HttpHeaders.of("referer"),
		HttpHeaders.of("date"),
		HttpHeaders.of("etag"),
		HttpHeaders.of("origin"),
		HttpHeaders.of("server"),
		HttpHeaders.of("accept-language"),
		HttpHeaders.of("content-disposition"),
		HttpHeaders.of("last-modified"),
		HttpHeaders.of("if-none-match"),
		HttpHeaders.of("location"));

	/**
	 * Octets the RFC 7541 Appendix B Huffman code shortens: every one of these costs 5–7 bits, so a
	 * string of them is reliably smaller encoded than literal.
	 */
	private static final byte[] HUFFMAN_FRIENDLY_ALPHABET =
		"abcdefghijklmnopqrstuvwxyz0123456789-_./ =;:".getBytes(US_ASCII);

	/** How many times a value is re-drawn to satisfy its Huffman intent before the intent is given up on. */
	private static final int HUFFMAN_INTENT_ATTEMPTS = 12;

	/** RFC 9204 §3.2.1 per-entry overhead, and RFC 9114 §4.2.2's per-field accounting constant. */
	static final int ENTRY_OVERHEAD = 32;

	private long seed = DEFAULT_SEED;
	private long capacity = 4096;
	private int blockedStreams = 16;
	private int sectionCount = 32;
	private long firstStreamId = 0;
	private int minFieldsPerSection = 4;
	private int maxFieldsPerSection = 16;
	private int minValueLength = 0;
	private int maxValueLength = 48;
	private LengthDistribution lengthDistribution = LengthDistribution.SHORT_BIASED;
	private int vocabularySize = -1;
	private double staticExactRate = 0.20;
	private double staticNameRate = 0.15;
	private double recurringRate = 0.40;
	private double huffmanEligibleRate = 0.60;
	private double neverIndexedRate = 0.08;
	private double intraSectionRepeatRate = 0.15;
	private double intraSectionNameRepeatRate = 0.50;
	private double emptyValueRate = 0.05;
	private boolean uppercaseRegistryNamesAllowed = true;
	private Set<HttpHeader> neverIndexedNames = DEFAULT_NEVER_INDEXED_NAMES;
	private String label = null;

	private QpackWorkloadGenerator() {}

	/** A generator at {@link #DEFAULT_SEED}, 4 KB capacity, 16 blocked streams, 32 sections. */
	public static QpackWorkloadGenerator create() {
		return builder().build();
	}

	public static Builder builder() {
		return new QpackWorkloadGenerator().new Builder();
	}

	/**
	 * The shape of a workload for one cell of T018's matrix — capacity × blocked streams — with the
	 * seed derived from both, so two cells never generate the same bytes and each cell is
	 * reproducible on its own.
	 */
	public static Builder builderFor(long capacity, int blockedStreams) {
		return builder()
			.withSeed(DEFAULT_SEED * 31 + capacity * 7 + blockedStreams)
			.withCapacity(capacity)
			.withBlockedStreams(blockedStreams);
	}

	public final class Builder extends AbstractBuilder<Builder, QpackWorkloadGenerator> {
		private Builder() {}

		/** The {@link Random} seed. Equal seeds and equal parameters give byte-identical workloads. */
		public Builder withSeed(long seed) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.seed = seed;
			return this;
		}

		/**
		 * The dynamic-table capacity in bytes this workload targets. Carried on the {@link Workload}
		 * for the codec pair to be constructed with, and — unless {@link #withVocabularySize} says
		 * otherwise — used to size the recurring vocabulary so that a small capacity genuinely
		 * causes eviction rather than merely fitting.
		 * <p>
		 * {@code 0} is legal and means the static-table-only workload: no vocabulary sizing pressure,
		 * and every representation an encoder picks must be a static one.
		 */
		public Builder withCapacity(long capacity) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.capacity = capacity;
			return this;
		}

		/** The blocked-stream limit this workload is replayed at. Carried on the {@link Workload}. */
		public Builder withBlockedStreams(int blockedStreams) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.blockedStreams = blockedStreams;
			return this;
		}

		/** How many field sections — i.e. how many HTTP/3 exchanges — the workload contains. */
		public Builder withSectionCount(int sectionCount) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.sectionCount = sectionCount;
			return this;
		}

		/**
		 * The QUIC stream id of the first section; successive sections step by 4, the RFC 9000 §2.1
		 * spacing of client-initiated bidirectional streams.
		 */
		public Builder withFirstStreamId(long firstStreamId) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.firstStreamId = firstStreamId;
			return this;
		}

		/** Inclusive bounds on the number of field lines per section. */
		public Builder withFieldsPerSection(int min, int max) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.minFieldsPerSection = min;
			QpackWorkloadGenerator.this.maxFieldsPerSection = max;
			return this;
		}

		/** Inclusive bounds on a generated value's length in octets. A minimum of 0 admits empty values. */
		public Builder withValueLength(int min, int max) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.minValueLength = min;
			QpackWorkloadGenerator.this.maxValueLength = max;
			return this;
		}

		/** How lengths are drawn between those bounds. Real header values are short-biased. */
		public Builder withLengthDistribution(LengthDistribution lengthDistribution) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.lengthDistribution = lengthDistribution;
			return this;
		}

		/**
		 * How many distinct recurring fields exist. Larger than the capacity admits means eviction;
		 * smaller means a table that fills and then only hits. Negative restores the
		 * capacity-derived default (see {@link #derivedVocabularySize()}).
		 */
		public Builder withVocabularySize(int vocabularySize) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.vocabularySize = vocabularySize;
			return this;
		}

		/** Intended share of fields that are an exact static-table name+value hit (Indexed Field Line). */
		public Builder withStaticExactRate(double staticExactRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.staticExactRate = staticExactRate;
			return this;
		}

		/**
		 * Intended share of fields carrying a static-table <i>name</i> with a freshly generated value
		 * (Literal Field Line with Name Reference).
		 */
		public Builder withStaticNameRate(double staticNameRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.staticNameRate = staticNameRate;
			return this;
		}

		/**
		 * Intended share of fields drawn from the recurring vocabulary rather than minted fresh. This
		 * is the knob that decides whether a dynamic table can help at all: at {@code 0} every field
		 * is novel and the best any encoder can do is a literal.
		 */
		public Builder withRecurringRate(double recurringRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.recurringRate = recurringRate;
			return this;
		}

		/**
		 * Intended share of generated values drawn from a Huffman-compressible alphabet; the rest are
		 * uniform random octets, which the RFC 7541 code <b>expands</b>, so an encoder honouring
		 * FR-029 ("Huffman only when it shortens") must send them literal. Both classes in one
		 * workload is what makes the H bit a tested branch rather than a constant.
		 */
		public Builder withHuffmanEligibleRate(double huffmanEligibleRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.huffmanEligibleRate = huffmanEligibleRate;
			return this;
		}

		/**
		 * Intended share of fields drawn from {@link #withNeverIndexedNames} — the fields an encoder
		 * must never insert into the dynamic table and must emit with the RFC 9204 §7.1 {@code N=1}
		 * literal form (FR-022).
		 */
		public Builder withNeverIndexedRate(double neverIndexedRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.neverIndexedRate = neverIndexedRate;
			return this;
		}

		/** The never-indexed name set; defaults to {@link #DEFAULT_NEVER_INDEXED_NAMES}. */
		public Builder withNeverIndexedNames(Set<HttpHeader> neverIndexedNames) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.neverIndexedNames = Set.copyOf(neverIndexedNames);
			return this;
		}

		/**
		 * Intended share of fields that repeat something already emitted <b>in the same section</b>.
		 * An encoder that inserts on first sight then references on second sight has to reach for a
		 * post-base representation, because the entry's absolute index is at or above the section's
		 * Base — which is the only way to reach RFC 9204 §4.5.4/§4.5.6, one of T002's named holes.
		 */
		public Builder withIntraSectionRepeatRate(double intraSectionRepeatRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.intraSectionRepeatRate = intraSectionRepeatRate;
			return this;
		}

		/**
		 * Given an intra-section repeat, the share that repeat the <b>name only</b> with a different
		 * value (→ Literal Field Line With Post-Base Name Reference) rather than the whole field
		 * (→ Indexed Field Line With Post-Base Index).
		 */
		public Builder withIntraSectionNameRepeatRate(double intraSectionNameRepeatRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.intraSectionNameRepeatRate = intraSectionNameRepeatRate;
			return this;
		}

		/** Intended share of freshly generated values that are empty — a legal and easily-missed case. */
		public Builder withEmptyValueRate(double emptyValueRate) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.emptyValueRate = emptyValueRate;
			return this;
		}

		/**
		 * Whether to admit field names that {@code core-http}'s {@link HttpHeaders} registry
		 * pre-registers with an uppercase canonical spelling and that the QPACK static table does not
		 * carry — see the class Javadoc. <b>On by default</b>, because an encoder lowercases its
		 * literal names ({@link QpackField#lowercaseNameBytes()}) and such a name therefore survives a
		 * round trip; admitting it is the wider corpus.
		 * <p>
		 * Pass {@code false} to narrow the corpus back to names no encoder has to lowercase. What that
		 * then drops is counted in {@link Coverage#namesSkippedForUppercase()} rather than silently
		 * shrinking the never-indexed share.
		 */
		public Builder withUppercaseRegistryNamesAllowed(boolean uppercaseRegistryNamesAllowed) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.uppercaseRegistryNamesAllowed = uppercaseRegistryNamesAllowed;
			return this;
		}

		/** A human label carried onto the {@link Workload}, so a parameterized failure names its cell. */
		public Builder withLabel(String label) {
			checkNotBuilt(this);
			QpackWorkloadGenerator.this.label = label;
			return this;
		}

		@Override
		protected QpackWorkloadGenerator doBuild() {
			check(capacity >= 0, "capacity must not be negative");
			check(blockedStreams >= 0, "blockedStreams must not be negative");
			check(sectionCount > 0, "sectionCount must be positive");
			check(firstStreamId >= 0, "firstStreamId must not be negative");
			check(minFieldsPerSection > 0, "minFieldsPerSection must be positive");
			check(maxFieldsPerSection >= minFieldsPerSection, "maxFieldsPerSection < minFieldsPerSection");
			check(minValueLength >= 0, "minValueLength must not be negative");
			check(maxValueLength >= minValueLength, "maxValueLength < minValueLength");
			checkRate(staticExactRate, "staticExactRate");
			checkRate(staticNameRate, "staticNameRate");
			checkRate(recurringRate, "recurringRate");
			checkRate(huffmanEligibleRate, "huffmanEligibleRate");
			checkRate(neverIndexedRate, "neverIndexedRate");
			checkRate(intraSectionRepeatRate, "intraSectionRepeatRate");
			checkRate(intraSectionNameRepeatRate, "intraSectionNameRepeatRate");
			checkRate(emptyValueRate, "emptyValueRate");
			check(staticExactRate + staticNameRate + recurringRate + neverIndexedRate <= 1.0,
				"staticExactRate + staticNameRate + recurringRate + neverIndexedRate exceeds 1.0 — " +
				"the remainder is what mints a fresh field, and it must not be negative");
			return QpackWorkloadGenerator.this;
		}

		private static void checkRate(double rate, String name) {
			check(rate >= 0.0 && rate <= 1.0, name + " must be within [0, 1]");
		}

		private static void check(boolean condition, String message) {
			if (!condition) throw new IllegalArgumentException(message);
		}
	}

	/** How value lengths are drawn between the configured minimum and maximum. */
	public enum LengthDistribution {
		/** Every length in range equally likely. */
		UNIFORM,
		/** Squared toward the minimum — the shape of real header values, with a rare long one. */
		SHORT_BIASED,
		/** Square-rooted toward the maximum — pushes eviction pressure and Huffman gain up. */
		LONG_BIASED;

		int draw(Random random, int min, int max) {
			if (max == min) return min;
			double u = random.nextDouble();
			double shaped = switch (this) {
				case UNIFORM -> u;
				case SHORT_BIASED -> u * u;
				case LONG_BIASED -> Math.sqrt(u);
			};
			int length = min + (int) (shaped * (max - min + 1));
			return Math.min(length, max);
		}
	}

	/** One field section: the fields of a single HTTP/3 exchange, and the stream that carries them. */
	public record Section(long streamId, List<QpackField> fields) {
		public Section {
			fields = List.copyOf(fields);
		}

		/** RFC 9114 §4.2.2 accounted size: Σ {@code len(name) + len(value) + 32}. */
		public long accountedSize() {
			long total = 0;
			for (QpackField field : fields) {
				total += field.name().size() + field.value().length + ENTRY_OVERHEAD;
			}
			return total;
		}

		@Override
		public String toString() {
			return "Section{stream=" + streamId + ", fields=" + fields.size() + ", accounted=" + accountedSize() + '}';
		}
	}

	/**
	 * What a workload <b>actually</b> contains, classified after generation by the same predicates an
	 * encoder uses — never by the requested rate. A test asserts against this, so a knob that stops
	 * having an effect fails a test instead of quietly generating a narrower corpus.
	 *
	 * @param fields                  total field lines across every section
	 * @param staticExactHits         fields that are an exact static-table name+value match
	 * @param staticNameHits          fields whose name is in the static table but whose value is not
	 * @param literalNameFields       fields whose name is in neither the static table nor a prior line
	 * @param recurringFields         fields drawn from the recurring vocabulary
	 * @param distinctFields          distinct name+value pairs across the whole workload
	 * @param huffmanCodedValues      values the RFC 7541 code shortens — an encoder honouring FR-029
	 *                                sends these with {@code H=1}
	 * @param literalValues           values Huffman does not shorten, sent with {@code H=0}
	 * @param emptyValues             zero-length values
	 * @param neverIndexedFields      fields whose name is in the never-indexed set
	 * @param intraSectionRepeats     fields repeating a whole field already in the same section
	 * @param intraSectionNameRepeats fields repeating only the name of an earlier line in the same
	 *                                section, with a different value
	 * @param namesSkippedForUppercase name <i>draws</i> discarded because the interned token carries
	 *                                uppercase and the QPACK static table has no reference for it —
	 *                                see the class Javadoc. Always {@code 0} unless
	 *                                {@link Builder#withUppercaseRegistryNamesAllowed} was set to
	 *                                {@code false}; non-zero means that narrowing dropped real
	 *                                coverage. It is a count of draws, not of distinct names
	 */
	public record Coverage(
		int fields, int staticExactHits, int staticNameHits, int literalNameFields,
		int recurringFields, int distinctFields,
		int huffmanCodedValues, int literalValues, int emptyValues,
		int neverIndexedFields, int intraSectionRepeats, int intraSectionNameRepeats,
		int namesSkippedForUppercase
	) {
		@Override
		public String toString() {
			return "Coverage{fields=" + fields +
				", staticExact=" + staticExactHits +
				", staticName=" + staticNameHits +
				", literalName=" + literalNameFields +
				", recurring=" + recurringFields +
				", distinct=" + distinctFields +
				", huffman=" + huffmanCodedValues +
				", literalValue=" + literalValues +
				", empty=" + emptyValues +
				", neverIndexed=" + neverIndexedFields +
				", intraRepeat=" + intraSectionRepeats +
				", intraNameRepeat=" + intraSectionNameRepeats +
				", skippedUppercase=" + namesSkippedForUppercase + '}';
		}
	}

	/**
	 * A generated workload: the two negotiated parameters it is meant to be replayed at, the sections
	 * themselves, and what they actually contain.
	 *
	 * @param label             a human label for failure messages
	 * @param capacity          the dynamic-table capacity to construct the codec pair with
	 * @param blockedStreams    the blocked-stream limit to construct the codec pair with
	 * @param neverIndexedNames the never-indexed set to construct the encoder with — the encoder
	 *                          reads it from configuration, so a test must hand it the same set the
	 *                          generator drew from
	 * @param sections          the field sections, in the order they are to be encoded
	 * @param coverage          what the sections actually contain
	 */
	public record Workload(
		String label, long capacity, int blockedStreams, Set<HttpHeader> neverIndexedNames,
		List<Section> sections, Coverage coverage
	) {
		public Workload {
			neverIndexedNames = Set.copyOf(neverIndexedNames);
			sections = List.copyOf(sections);
		}

		/** Σ over sections of the RFC 9114 §4.2.2 accounted size — the uncompressed baseline. */
		public long accountedSize() {
			long total = 0;
			for (Section section : sections) {
				total += section.accountedSize();
			}
			return total;
		}

		/** The largest single section's accounted size — what {@code maxFieldSectionSize} must clear. */
		public long largestSectionAccountedSize() {
			long largest = 0;
			for (Section section : sections) {
				largest = Math.max(largest, section.accountedSize());
			}
			return largest;
		}

		@Override
		public String toString() {
			return "Workload{" + label + ", sections=" + sections.size() + ", " + coverage + '}';
		}
	}

	/**
	 * Builds the workload. Deterministic: repeated calls on one generator, and calls on two
	 * generators built with equal parameters, produce equal workloads.
	 */
	public Workload generate() {
		Random random = new Random(seed);
		List<QpackField> vocabulary = generateVocabulary(random);

		List<Section> sections = new ArrayList<>(sectionCount);
		CoverageBuilder coverage = new CoverageBuilder();
		int freshCounter = 0;

		for (int s = 0; s < sectionCount; s++) {
			int fieldCount = minFieldsPerSection +
				(maxFieldsPerSection == minFieldsPerSection ? 0 :
					random.nextInt(maxFieldsPerSection - minFieldsPerSection + 1));

			List<QpackField> fields = new ArrayList<>(fieldCount);
			for (int f = 0; f < fieldCount; f++) {
				QpackField field;
				if (!fields.isEmpty() && random.nextDouble() < intraSectionRepeatRate) {
					QpackField earlier = fields.get(random.nextInt(fields.size()));
					if (random.nextDouble() < intraSectionNameRepeatRate) {
						field = new QpackField(earlier.name(), generateValue(random));
						coverage.intraSectionNameRepeats++;
					} else {
						field = earlier;
						coverage.intraSectionRepeats++;
					}
				} else {
					field = drawField(random, vocabulary, freshCounter++, coverage);
				}
				fields.add(field);
				coverage.count(field);
			}
			sections.add(new Section(firstStreamId + 4L * s, fields));
		}

		return new Workload(
			label != null ? label : defaultLabel(),
			capacity, blockedStreams, neverIndexedNames, sections, coverage.build());
	}

	private String defaultLabel() {
		return "capacity=" + capacity + " blockedStreams=" + blockedStreams + " seed=" + seed;
	}

	/**
	 * The recurring vocabulary — the fields a dynamic table can actually amortize. Sized so its total
	 * RFC 9204 §3.2.1 footprint is roughly three times the capacity, which guarantees eviction
	 * pressure rather than a table that simply fills once and then only hits.
	 */
	private List<QpackField> generateVocabulary(Random random) {
		int size = vocabularySize >= 0 ? vocabularySize : derivedVocabularySize();
		List<QpackField> vocabulary = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			HttpHeader name = random.nextDouble() < 0.5 ?
				RECURRING_STATIC_NAMES.get(random.nextInt(RECURRING_STATIC_NAMES.size())) :
				customName("app", i);
			vocabulary.add(new QpackField(name, generateValue(random)));
		}
		return vocabulary;
	}

	/**
	 * Roughly {@code 3 * capacity} bytes' worth of entries at an assumed ~96 B apiece (a ~20 B name,
	 * a ~24 B value and the 32 B RFC 9204 §3.2.1 overhead, plus slack), clamped to a range that keeps
	 * both a zero capacity and a 64 KB one producing a usable workload.
	 */
	private int derivedVocabularySize() {
		if (capacity == 0) return 12;
		return (int) Math.max(4, Math.min(256, 3 * capacity / 96));
	}

	private QpackField drawField(Random random, List<QpackField> vocabulary, int ordinal, CoverageBuilder coverage) {
		double roll = random.nextDouble();
		double threshold = neverIndexedRate;
		if (roll < threshold) {
			QpackField neverIndexed = drawNeverIndexed(random, coverage);
			if (neverIndexed != null) return neverIndexed;
		}
		threshold += staticExactRate;
		if (roll < threshold) {
			int index = random.nextInt(QpackStaticTable.SIZE);
			return new QpackField(QpackStaticTable.name(index), QpackStaticTable.value(index));
		}
		threshold += staticNameRate;
		if (roll < threshold) {
			HttpHeader name = RECURRING_STATIC_NAMES.get(random.nextInt(RECURRING_STATIC_NAMES.size()));
			return new QpackField(name, generateValue(random));
		}
		threshold += recurringRate;
		if (roll < threshold && !vocabulary.isEmpty()) {
			QpackField recurring = vocabulary.get(random.nextInt(vocabulary.size()));
			coverage.recurringFields++;
			return recurring;
		}
		return new QpackField(customName("fresh", ordinal), generateValue(random));
	}

	/**
	 * A never-indexed field, or {@code null} when every name in the set is uppercase-tainted and
	 * uppercase names have been disallowed — in which case the draw is counted as skipped and the
	 * caller falls through to another representation, so the omission is visible in {@link Coverage}
	 * rather than silently reducing the never-indexed share. Under the default
	 * ({@link Builder#withUppercaseRegistryNamesAllowed} on) nothing is ever skipped here.
	 */
	private QpackField drawNeverIndexed(Random random, CoverageBuilder coverage) {
		List<HttpHeader> admissible = new ArrayList<>(neverIndexedNames.size());
		for (HttpHeader name : neverIndexedNames) {
			if (isAdmissible(name)) admissible.add(name);
			else coverage.namesSkippedForUppercase++;
		}
		if (admissible.isEmpty()) return null;
		// Sorted so the draw does not depend on Set iteration order, which Set.of does not specify.
		admissible.sort(QpackWorkloadGenerator::compareNames);
		return new QpackField(admissible.get(random.nextInt(admissible.size())), generateValue(random));
	}

	private HttpHeader customName(String kind, int ordinal) {
		// "x-qpack-<kind>-<n>": outside the QPACK static table and outside core-http's registry, so it
		// is sent as literal octets exactly as generated and comes back byte-identical.
		return HttpHeaders.of("x-qpack-" + kind + '-' + ordinal);
	}

	/**
	 * Whether a name is admitted into the corpus. Everything is, by default. Uppercase in the interned
	 * token is only reachable at all when the name is sent as literal octets, which happens exactly
	 * when the static table has no index for it — and every encoder lowercases those, so excluding
	 * such names is an opt-in narrowing rather than a correctness requirement. See the class Javadoc.
	 */
	private boolean isAdmissible(HttpHeader name) {
		if (uppercaseRegistryNamesAllowed) return true;
		if (QpackStaticTable.indexOfName(name) != -1) return true;
		return !hasUppercase(name);
	}

	static boolean hasUppercase(HttpHeader name) {
		byte[] bytes = new byte[name.size()];
		name.writeTo(bytes, 0);
		for (byte b : bytes) {
			if (b >= 'A' && b <= 'Z') return true;
		}
		return false;
	}

	private static int compareNames(HttpHeader a, HttpHeader b) {
		return a.toString().compareToIgnoreCase(b.toString());
	}

	/**
	 * One value, honouring the empty-value and Huffman-eligibility intents. The Huffman intent is
	 * checked against {@link QpackHuffman#encodedLength} — the same predicate
	 * {@link QpackStaticEncoder} uses to set the {@code H} bit — and re-drawn a bounded number of
	 * times, growing the length when a short value simply cannot compress. The outcome is
	 * re-classified by {@link CoverageBuilder}, so a given-up intent shows in the coverage rather
	 * than being asserted away.
	 */
	private byte[] generateValue(Random random) {
		if (random.nextDouble() < emptyValueRate) return new byte[0];

		boolean wantHuffman = random.nextDouble() < huffmanEligibleRate;
		int length = lengthDistribution.draw(random, minValueLength, maxValueLength);
		byte[] best = drawValue(random, length, wantHuffman);
		for (int attempt = 0; attempt < HUFFMAN_INTENT_ATTEMPTS && shortensUnderHuffman(best) != wantHuffman; attempt++) {
			// A short string cannot be shortened by any code, so grow toward the intent rather than
			// re-drawing the same impossible length forever.
			if (wantHuffman) length = Math.min(Math.max(length + 4, 8), Math.max(maxValueLength, 32));
			best = drawValue(random, length, wantHuffman);
		}
		return best;
	}

	private byte[] drawValue(Random random, int length, boolean huffmanFriendly) {
		byte[] value = new byte[length];
		if (huffmanFriendly) {
			for (int i = 0; i < length; i++) {
				value[i] = HUFFMAN_FRIENDLY_ALPHABET[random.nextInt(HUFFMAN_FRIENDLY_ALPHABET.length)];
			}
		} else {
			// Uniform octets, 0x80-0xFF included: the RFC 7541 code spends 20+ bits on most of those,
			// so the result is reliably *longer* encoded than literal.
			random.nextBytes(value);
		}
		return value;
	}

	/** The exact predicate {@link QpackStaticEncoder} uses to decide the {@code H} bit (FR-029). */
	static boolean shortensUnderHuffman(byte[] value) {
		return QpackHuffman.encodedLength(value, 0, value.length) < value.length;
	}

	private final class CoverageBuilder {
		private int fields;
		private int staticExactHits;
		private int staticNameHits;
		private int literalNameFields;
		private int recurringFields;
		private int huffmanCodedValues;
		private int literalValues;
		private int emptyValues;
		private int neverIndexedFields;
		private int intraSectionRepeats;
		private int intraSectionNameRepeats;
		private int namesSkippedForUppercase;
		private final Set<String> distinct = new LinkedHashSet<>();

		void count(QpackField field) {
			fields++;
			if (QpackStaticTable.indexOfNameAndValue(field.name(), field.value()) != -1) staticExactHits++;
			else if (QpackStaticTable.indexOfName(field.name()) != -1) staticNameHits++;
			else literalNameFields++;

			if (field.value().length == 0) emptyValues++;
			else if (shortensUnderHuffman(field.value())) huffmanCodedValues++;
			else literalValues++;

			if (neverIndexedNames.contains(field.name())) neverIndexedFields++;
			distinct.add(field.toString());
		}

		Coverage build() {
			return new Coverage(fields, staticExactHits, staticNameHits, literalNameFields,
				recurringFields, distinct.size(),
				huffmanCodedValues, literalValues, emptyValues,
				neverIndexedFields, intraSectionRepeats, intraSectionNameRepeats,
				namesSkippedForUppercase);
		}
	}
}
