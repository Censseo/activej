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
import io.activej.http3.qpack.QpackWorkloadGenerator.Section;
import io.activej.http3.qpack.QpackWorkloadGenerator.Workload;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives a {@link QpackWorkloadGenerator.Workload} through a {@link QpackEncoder} /
 * {@link QpackDecoder} pair and asserts that every section comes back field-for-field (T003).
 * <p>
 * Written entirely against the <b>interfaces</b>, which research D-1 froze for exactly this reason:
 * it runs today against {@link QpackStaticEncoder} / {@link QpackStaticDecoder} at capacity 0, and
 * will run unchanged against {@code QpackDynamicEncoder} / {@code QpackDynamicDecoder} once T027 and
 * T029 land them — the only new code needed then is a {@link CodecPair} implementation wiring the
 * two instruction streams, which is what {@link CodecPair#deliverEncoderInstructions()} and
 * {@link CodecPair#deliverDecoderInstructions(long)} exist for.
 * <p>
 * There is no reactor, no {@code Promise} and no eventloop here (ADR-016). The QPACK <i>codecs</i>
 * are synchronous; only the instruction <i>streams</i> above them are reactive, and those are
 * {@code Http3Connection}'s, not this harness's.
 *
 * <h2>Ordering is the whole point of the seam</h2>
 * Research D-2: the encoder accumulates the encoder-stream instructions it wants sent, and the
 * connection writes them <b>before</b> the field section that references them leaves. That is
 * {@link InstructionDelivery#BEFORE_SECTION}, and under it nothing ever blocks.
 * {@link InstructionDelivery#AFTER_SECTION} deliberately violates it — delivering the instructions
 * only after the section has been handed to the decoder is precisely what makes a section
 * <i>blocked</i>, which is what SC-004 and T018's non-zero blocked-stream cells need. A static pair
 * has no instructions either way, so both orders are identical for it.
 *
 * <h2>{@code ByteBuf} ownership</h2>
 * {@link QpackEncoder#encode} returns a new owned buffer and {@link QpackDecoder#decode} owns and
 * recycles its input on every path, success and failure. This harness therefore recycles exactly
 * once: on any path that abandons an encoded section <i>before</i> handing it to {@code decode}
 * (DI-1). The wire bytes are copied out with {@code getArray()} before decoding, so a failure
 * message can show them without holding the buffer.
 */
public final class QpackRoundTrip {
	/** A field-section size bound generous enough not to be the thing under test. */
	public static final long UNBOUNDED_FIELD_SECTION = Long.MAX_VALUE;

	private QpackRoundTrip() {}

	/**
	 * One encoder and the decoder that must understand it, plus the two hooks that carry the QPACK
	 * instruction streams between them.
	 * <p>
	 * Both hooks default to no-ops, which is exactly right for a capacity-0 pair: it emits no
	 * encoder-stream instruction and needs no acknowledgment. A dynamic pair overrides them —
	 * <b>this interface is the only thing T027/T029 must add to reuse the whole harness.</b>
	 */
	public interface CodecPair {
		QpackEncoder encoder();

		QpackDecoder decoder();

		/**
		 * Hand everything the encoder has accumulated for the encoder stream (RFC 9204 §4.3) to the
		 * decoder's table. Called once per section, either side of {@code decode} depending on
		 * {@link InstructionDelivery}.
		 */
		default void deliverEncoderInstructions() throws QpackException {}

		/**
		 * Hand everything the decoder has accumulated for the decoder stream (RFC 9204 §4.4 — Section
		 * Acknowledgment, Stream Cancellation, Insert Count Increment) back to the encoder. Called
		 * after a section has decoded, with the stream id that carried it.
		 */
		default void deliverDecoderInstructions(long streamId) throws QpackException {}

		/** A description for failure messages. */
		default String describe() {
			return encoder().getClass().getSimpleName() + " -> " + decoder().getClass().getSimpleName();
		}

		static CodecPair of(QpackEncoder encoder, QpackDecoder decoder) {
			return new CodecPair() {
				@Override
				public QpackEncoder encoder() {
					return encoder;
				}

				@Override
				public QpackDecoder decoder() {
					return decoder;
				}
			};
		}
	}

	/**
	 * The capacity-0 pair — the only concrete pair that exists before T027/T029. Useful in its own
	 * right beyond a self-check: FR-040 and T022 require that against a peer advertising capacity 0
	 * a dynamic encoder produce byte-identical output to this one, and that comparison needs this
	 * pair on the other side of it.
	 */
	public static CodecPair staticTablePair(long maxFieldSectionSize) {
		return CodecPair.of(new QpackStaticEncoder(), new QpackStaticDecoder(maxFieldSectionSize));
	}

	/** Where the encoder-stream instructions are delivered relative to the field section. */
	public enum InstructionDelivery {
		/** RFC-correct and research D-2's discipline: instructions first, so nothing ever blocks. */
		BEFORE_SECTION,
		/** Deliberately inverted, so the section arrives before the insertions it references — a blocked section. */
		AFTER_SECTION
	}

	/**
	 * One section's result.
	 *
	 * @param index         position in the workload, 0-based
	 * @param streamId      the QUIC stream the section was carried on
	 * @param fieldCount    field lines in the section
	 * @param encodedBytes  the encoded field section's wire size, prefix included
	 * @param accountedSize the RFC 9114 §4.2.2 accounted size — the uncompressed baseline
	 */
	public record SectionReport(int index, long streamId, int fieldCount, int encodedBytes, long accountedSize) {
		/** Wire size as a fraction of the accounted size; smaller is better compression. */
		public double compressionRatio() {
			return accountedSize == 0 ? 1.0 : (double) encodedBytes / accountedSize;
		}
	}

	/** Every section's result, plus the aggregate SC-001 wants. */
	public record Report(String label, String codecs, List<SectionReport> sections) {
		public Report {
			sections = List.copyOf(sections);
		}

		public int sectionCount() {
			return sections.size();
		}

		public int fieldCount() {
			int total = 0;
			for (SectionReport section : sections) total += section.fieldCount();
			return total;
		}

		public long encodedBytes() {
			long total = 0;
			for (SectionReport section : sections) total += section.encodedBytes();
			return total;
		}

		public long accountedSize() {
			long total = 0;
			for (SectionReport section : sections) total += section.accountedSize();
			return total;
		}

		public double compressionRatio() {
			long accounted = accountedSize();
			return accounted == 0 ? 1.0 : (double) encodedBytes() / accounted;
		}

		public int firstSectionBytes() {
			return sections.get(0).encodedBytes();
		}

		/** Mean wire size of sections 2..N — the "repeated request" side of SC-001. */
		public double meanSubsequentSectionBytes() {
			if (sections.size() < 2) return firstSectionBytes();
			long total = 0;
			for (int i = 1; i < sections.size(); i++) total += sections.get(i).encodedBytes();
			return (double) total / (sections.size() - 1);
		}

		/**
		 * How much smaller, on average, sections 2..N are than section 1 — the quantity SC-001 puts
		 * at ≥ 0.60 for a 4 KB dynamic table over repeated requests. A static-table pair scores
		 * around 0 here, which is the point of measuring it.
		 */
		public double subsequentSectionShrinkage() {
			int first = firstSectionBytes();
			return first == 0 ? 0.0 : 1.0 - meanSubsequentSectionBytes() / first;
		}

		@Override
		public String toString() {
			return "Report{" + label + ", " + codecs +
				", sections=" + sectionCount() +
				", fields=" + fieldCount() +
				", encoded=" + encodedBytes() + "b" +
				", accounted=" + accountedSize() + "b" +
				", ratio=" + String.format("%.3f", compressionRatio()) +
				", shrinkage=" + String.format("%.3f", subsequentSectionShrinkage()) + '}';
		}
	}

	/** {@link #run(Workload, CodecPair, InstructionDelivery)} at the RFC-correct ordering. */
	public static Report run(Workload workload, CodecPair pair) throws QpackException {
		return run(workload, pair, InstructionDelivery.BEFORE_SECTION);
	}

	/**
	 * Encodes every section of {@code workload} and decodes it back through the same pair, asserting
	 * field-for-field equality as it goes.
	 *
	 * @throws AssertionError    on the first section that does not come back identical, naming the
	 *                           section, the field index and both values
	 * @throws QpackException    whatever the decoder raises — a round trip must not produce one, so a
	 *                           caller normally lets it propagate and fail the test
	 */
	public static Report run(Workload workload, CodecPair pair, InstructionDelivery delivery) throws QpackException {
		List<SectionReport> reports = new ArrayList<>(workload.sections().size());
		List<Section> sections = workload.sections();
		for (int i = 0; i < sections.size(); i++) {
			reports.add(runSection(workload.label(), i, sections.get(i), pair, delivery));
		}
		return new Report(workload.label(), pair.describe(), reports);
	}

	/**
	 * One section, end to end. Split out so a test can interleave sections across two streams — which
	 * is what a blocked-stream limit above 0 is about — without reimplementing the ownership rules.
	 */
	public static SectionReport runSection(
		String label, int index, Section section, CodecPair pair, InstructionDelivery delivery
	) throws QpackException {
		List<QpackField> expected = section.fields();

		ByteBuf encoded = pair.encoder().encode(expected);
		byte[] wire;
		try {
			wire = encoded.getArray();
			if (delivery == InstructionDelivery.BEFORE_SECTION) {
				pair.deliverEncoderInstructions();
			}
		} catch (QpackException | RuntimeException | Error e) {
			// Nothing has taken ownership yet: `decode` is what would have recycled it (DI-1).
			encoded.recycle();
			throw e;
		}

		// `decode` owns and recycles `encoded` on every path from here, success and failure alike.
		List<QpackField> actual = pair.decoder().decode(encoded);

		if (delivery == InstructionDelivery.AFTER_SECTION) {
			pair.deliverEncoderInstructions();
		}
		pair.deliverDecoderInstructions(section.streamId());

		assertSameFields(label, index, section, pair, wire, expected, actual);
		return new SectionReport(index, section.streamId(), expected.size(), wire.length, section.accountedSize());
	}

	private static void assertSameFields(
		String label, int index, Section section, CodecPair pair,
		byte[] wire, List<QpackField> expected, List<QpackField> actual
	) {
		if (expected.equals(actual)) return;

		StringBuilder message = new StringBuilder()
			.append("QPACK round trip did not reproduce the field section\n")
			.append("  workload: ").append(label).append('\n')
			.append("  codecs:   ").append(pair.describe()).append('\n')
			.append("  section:  #").append(index).append(" on stream ").append(section.streamId()).append('\n')
			.append("  wire:     ").append(hex(wire)).append('\n');

		int common = Math.min(expected.size(), actual.size());
		for (int i = 0; i < common; i++) {
			if (!expected.get(i).equals(actual.get(i))) {
				message.append("  field #").append(i).append(" differs\n")
					.append("    expected: ").append(describe(expected.get(i))).append('\n')
					.append("    actual:   ").append(describe(actual.get(i))).append('\n');
			}
		}
		if (expected.size() != actual.size()) {
			message.append("  field count differs: expected ").append(expected.size())
				.append(", got ").append(actual.size()).append('\n');
		}
		throw new AssertionError(message.toString());
	}

	/**
	 * A field rendered for a failure message. Includes {@code nameHadUppercase}, because it is part
	 * of {@link QpackField#equals} and is otherwise invisible — a mismatch on it alone reads as two
	 * identical fields that are somehow unequal.
	 */
	private static String describe(QpackField field) {
		return field + "  [nameLen=" + field.name().size() +
			", valueLen=" + field.value().length +
			", nameHadUppercase=" + field.nameHadUppercase() + ']';
	}

	private static String hex(byte[] bytes) {
		StringBuilder out = new StringBuilder(bytes.length * 3);
		for (int i = 0; i < bytes.length; i++) {
			if (i != 0) out.append(i % 16 == 0 ? '\n' : ' ');
			if (i != 0 && i % 16 == 0) out.append("            ");
			out.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16))
				.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		return out.toString();
	}
}
