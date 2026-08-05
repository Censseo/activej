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
import io.activej.common.Checks;
import io.activej.common.ref.RefInt;
import io.activej.http3.Http3Errors;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

import static io.activej.common.Checks.checkArgument;

/**
 * The seven RFC 9204 instruction forms carried by the two QPACK unidirectional streams — four
 * encoder-stream instructions (§4.3) and three decoder-stream ones (§4.4) — as self-sizing immutable
 * values over the existing {@link QpackIntegers} prefixed integers and {@link QpackHuffman} strings.
 * <p>
 * Pure and synchronous, like the rest of this package (ADR-016): no {@code Reactor}, no
 * {@code Promise}, and no state of its own. It knows the wire layout and nothing else — the table
 * this endpoint keeps, the bounds it enforces, the Known Received Count it tracks and the ordering
 * discipline between an instruction and the field section that references it all live in the codecs
 * and in {@code Http3Connection} that own them.
 * <p>
 * <b>What this class deliberately does not check.</b> Every rejection here is a syntax failure — a
 * prefixed integer that cannot be decoded, a Huffman string whose padding is invalid. Every
 * <i>semantic</i> rule is the caller's: a capacity above the locally advertised maximum, a name
 * reference out of range for its {@code T} bit, a {@code Duplicate} of an evicted entry, an
 * insertion larger than the current capacity, an {@code Insert Count Increment} of 0, and the
 * {@code qpackMaxInstructionSize} bound on how much may be buffered while waiting for one
 * instruction to complete (FR-028, FR-029, FR-030).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.3">RFC 9204 §4.3 — Encoder
 * Instructions</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.4">RFC 9204 §4.4 — Decoder
 * Instructions</a>
 */
public final class QpackInstructions {
	private static final boolean CHECKS = Checks.isEnabled(QpackInstructions.class);

	/** Set Dynamic Table Capacity: {@code 001} then a 5-bit prefix (RFC 9204 §4.3.1). */
	private static final int SET_CAPACITY_FLAGS = 0x20;
	private static final int SET_CAPACITY_PREFIX = 5;

	/** Insert With Name Reference: {@code 1} then the {@code T} bit then a 6-bit prefix (§4.3.2). */
	private static final int NAME_REFERENCE_FLAGS = 0x80;
	private static final int NAME_REFERENCE_STATIC_FLAG = 0x40;
	private static final int NAME_REFERENCE_PREFIX = 6;

	/** Insert With Literal Name: {@code 01} then the {@code H} bit then a 5-bit prefix (§4.3.3). */
	private static final int LITERAL_NAME_FLAGS = 0x40;
	private static final int LITERAL_NAME_HUFFMAN_FLAG = 0x20;
	private static final int LITERAL_NAME_PREFIX = 5;

	/** Duplicate: {@code 000} then a 5-bit prefix (§4.3.4). */
	private static final int DUPLICATE_FLAGS = 0x00;
	private static final int DUPLICATE_PREFIX = 5;

	/** Section Acknowledgment: {@code 1} then a 7-bit prefix (§4.4.1). */
	private static final int SECTION_ACK_FLAGS = 0x80;
	private static final int SECTION_ACK_PREFIX = 7;

	/** Stream Cancellation: {@code 01} then a 6-bit prefix (§4.4.2). */
	private static final int STREAM_CANCELLATION_FLAGS = 0x40;
	private static final int STREAM_CANCELLATION_PREFIX = 6;

	/** Insert Count Increment: {@code 00} then a 6-bit prefix (§4.4.3). */
	private static final int INSERT_COUNT_INCREMENT_FLAGS = 0x00;
	private static final int INSERT_COUNT_INCREMENT_PREFIX = 6;

	/** The value string both insertion instructions end with: {@code H} then a 7-bit prefix. */
	private static final int VALUE_PREFIX = 7;
	private static final int VALUE_HUFFMAN_FLAG = 0x80;

	private QpackInstructions() {}

	/**
	 * One instruction, self-sizing in the shape the rest of this module already uses for wire values:
	 * {@link #encodedLength()} answers before anything is allocated, {@link #writeTo(ByteBuf)} writes
	 * into a buffer the caller owns and has already made room in.
	 */
	public sealed interface Instruction {
		/** The exact number of bytes {@link #writeTo} emits. */
		int encodedLength();

		/** Writes this instruction into {@code out}, which must have {@link #encodedLength()} bytes of room. */
		void writeTo(ByteBuf out);
	}

	/** An instruction the encoder sends to the peer's decoder on the encoder stream (RFC 9204 §4.3). */
	public sealed interface EncoderInstruction extends Instruction {}

	/** An instruction the decoder sends to the peer's encoder on the decoder stream (RFC 9204 §4.4). */
	public sealed interface DecoderInstruction extends Instruction {}

	// ---------------------------------------------------------------- encoder stream, RFC 9204 §4.3

	/**
	 * RFC 9204 §4.3.1. The first instruction on a local encoder stream, and — as
	 * {@code Set Dynamic Table Capacity 0}, the single byte {@code 0x20} — the only one a phase-1
	 * peer ever sends.
	 */
	public record SetDynamicTableCapacity(long capacity) implements EncoderInstruction {
		public SetDynamicTableCapacity {
			if (CHECKS) checkArgument(capacity >= 0 && capacity <= QpackIntegers.MAX_VALUE, "capacity out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(SET_CAPACITY_PREFIX, capacity);
		}

		@Override
		public void writeTo(ByteBuf out) {
			QpackIntegers.writeInteger(out, SET_CAPACITY_PREFIX, SET_CAPACITY_FLAGS, capacity);
		}
	}

	/**
	 * RFC 9204 §4.3.2. {@code staticTable} is the {@code T} bit: {@code true} indexes the static
	 * table, {@code false} the dynamic table by relative index.
	 * <p>
	 * {@code value} is the <b>decoded</b> octets; whether they travel Huffman-coded is decided here,
	 * and only when that shortens them (RFC 7541 §5.2, phase-1 FR-029). Callers must not mutate the
	 * array they pass or the one {@link #value()} returns.
	 */
	public record InsertWithNameReference(boolean staticTable, long nameIndex, byte[] value)
		implements EncoderInstruction {

		public InsertWithNameReference {
			if (CHECKS) checkArgument(nameIndex >= 0 && nameIndex <= QpackIntegers.MAX_VALUE, "nameIndex out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(NAME_REFERENCE_PREFIX, nameIndex) + stringLength(value, VALUE_PREFIX);
		}

		@Override
		public void writeTo(ByteBuf out) {
			int flags = staticTable ? NAME_REFERENCE_FLAGS | NAME_REFERENCE_STATIC_FLAG : NAME_REFERENCE_FLAGS;
			QpackIntegers.writeInteger(out, NAME_REFERENCE_PREFIX, flags, nameIndex);
			writeString(out, value, VALUE_PREFIX, 0, VALUE_HUFFMAN_FLAG);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof InsertWithNameReference that && staticTable == that.staticTable
				&& nameIndex == that.nameIndex && Arrays.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return 31 * (31 * Boolean.hashCode(staticTable) + Long.hashCode(nameIndex)) + Arrays.hashCode(value);
		}

		/** Lengths, never octets: a debug line printing this must not become a field-value leak (FR-063). */
		@Override
		public String toString() {
			return "InsertWithNameReference[static=" + staticTable + ", nameIndex=" + nameIndex +
				   ", valueLength=" + value.length + ']';
		}
	}

	/**
	 * RFC 9204 §4.3.3. Both strings are the <b>decoded</b> octets, each Huffman-coded only when that
	 * shortens it.
	 * <p>
	 * RFC 9114 §4.1.1 requires the name to be lowercase on the wire; that is the encoder's business,
	 * not this codec's — see {@link QpackField#lowercaseNameBytes()} for why the distinction matters.
	 * Callers must not mutate the arrays they pass or the ones the accessors return.
	 */
	public record InsertWithLiteralName(byte[] name, byte[] value) implements EncoderInstruction {
		@Override
		public int encodedLength() {
			return stringLength(name, LITERAL_NAME_PREFIX) + stringLength(value, VALUE_PREFIX);
		}

		@Override
		public void writeTo(ByteBuf out) {
			writeString(out, name, LITERAL_NAME_PREFIX, LITERAL_NAME_FLAGS, LITERAL_NAME_HUFFMAN_FLAG);
			writeString(out, value, VALUE_PREFIX, 0, VALUE_HUFFMAN_FLAG);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof InsertWithLiteralName that
				   && Arrays.equals(name, that.name) && Arrays.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(name) + Arrays.hashCode(value);
		}

		/** Lengths, never octets (FR-063). */
		@Override
		public String toString() {
			return "InsertWithLiteralName[nameLength=" + name.length + ", valueLength=" + value.length + ']';
		}
	}

	/** RFC 9204 §4.3.4. {@code index} is relative to the most recent insertion. */
	public record Duplicate(long index) implements EncoderInstruction {
		public Duplicate {
			if (CHECKS) checkArgument(index >= 0 && index <= QpackIntegers.MAX_VALUE, "index out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(DUPLICATE_PREFIX, index);
		}

		@Override
		public void writeTo(ByteBuf out) {
			QpackIntegers.writeInteger(out, DUPLICATE_PREFIX, DUPLICATE_FLAGS, index);
		}
	}

	// ---------------------------------------------------------------- decoder stream, RFC 9204 §4.4

	/** RFC 9204 §4.4.1. Carries the id of the request stream whose field section was decoded. */
	public record SectionAcknowledgment(long streamId) implements DecoderInstruction {
		public SectionAcknowledgment {
			if (CHECKS) checkArgument(streamId >= 0 && streamId <= QpackIntegers.MAX_VALUE, "streamId out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(SECTION_ACK_PREFIX, streamId);
		}

		@Override
		public void writeTo(ByteBuf out) {
			QpackIntegers.writeInteger(out, SECTION_ACK_PREFIX, SECTION_ACK_FLAGS, streamId);
		}
	}

	/** RFC 9204 §4.4.2. Carries the id of a stream abandoned with a field section unacknowledged. */
	public record StreamCancellation(long streamId) implements DecoderInstruction {
		public StreamCancellation {
			if (CHECKS) checkArgument(streamId >= 0 && streamId <= QpackIntegers.MAX_VALUE, "streamId out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(STREAM_CANCELLATION_PREFIX, streamId);
		}

		@Override
		public void writeTo(ByteBuf out) {
			QpackIntegers.writeInteger(out, STREAM_CANCELLATION_PREFIX, STREAM_CANCELLATION_FLAGS, streamId);
		}
	}

	/**
	 * RFC 9204 §4.4.3. An increment of 0 is a decoder-stream error, but a well-formed one — the
	 * caller rejects it (FR-030), this codec parses it.
	 */
	public record InsertCountIncrement(long increment) implements DecoderInstruction {
		public InsertCountIncrement {
			if (CHECKS) checkArgument(increment >= 0 && increment <= QpackIntegers.MAX_VALUE, "increment out of range");
		}

		@Override
		public int encodedLength() {
			return QpackIntegers.encodedLength(INSERT_COUNT_INCREMENT_PREFIX, increment);
		}

		@Override
		public void writeTo(ByteBuf out) {
			QpackIntegers.writeInteger(out, INSERT_COUNT_INCREMENT_PREFIX, INSERT_COUNT_INCREMENT_FLAGS, increment);
		}
	}

	// ---------------------------------------------------------------- encoding

	/** Encodes one instruction into a new buffer the caller owns and must recycle. */
	public static ByteBuf encode(Instruction instruction) {
		ByteBuf out = ByteBufPool.allocate(instruction.encodedLength());
		instruction.writeTo(out);
		return out;
	}

	// ---------------------------------------------------------------- decoding

	/**
	 * Reads one encoder-stream instruction (RFC 9204 §4.3) from the head of {@code buf}, consuming
	 * exactly its bytes. The caller keeps and recycles {@code buf}.
	 *
	 * @return the instruction, or {@code null} if {@code buf} does not yet hold one whole — in which
	 * case <b>nothing is consumed</b>, so the caller may append the bytes that follow and retry.
	 * Bounding how long that may go on is the caller's job (FR-028): this codec cannot tell a peer
	 * that is slow from one that is silent
	 * @throws QpackException {@link Http3Errors#QPACK_ENCODER_STREAM_ERROR} at connection scope, for
	 *                        an instruction that is present but malformed. The read position is left
	 *                        undefined, since the connection does not survive this
	 */
	public static @Nullable EncoderInstruction readEncoderInstruction(ByteBuf buf) throws QpackException {
		if (!buf.canRead()) return null;
		int head = buf.head();
		int first = buf.peek() & 0xFF;
		EncoderInstruction instruction;
		if ((first & 0x80) != 0) {
			instruction = readInsertWithNameReference(buf);
		} else if ((first & 0x40) != 0) {
			instruction = readInsertWithLiteralName(buf);
		} else if ((first & 0x20) != 0) {
			instruction = hasWholePrefixedInteger(buf, SET_CAPACITY_PREFIX) ?
				new SetDynamicTableCapacity(readInteger(buf, SET_CAPACITY_PREFIX, Http3Errors.QPACK_ENCODER_STREAM_ERROR)) :
				null;
		} else {
			instruction = hasWholePrefixedInteger(buf, DUPLICATE_PREFIX) ?
				new Duplicate(readInteger(buf, DUPLICATE_PREFIX, Http3Errors.QPACK_ENCODER_STREAM_ERROR)) :
				null;
		}
		if (instruction == null) buf.head(head);
		return instruction;
	}

	/**
	 * Reads one decoder-stream instruction (RFC 9204 §4.4) from the head of {@code buf}, consuming
	 * exactly its bytes. The caller keeps and recycles {@code buf}.
	 *
	 * @return the instruction, or {@code null} if {@code buf} does not yet hold one whole, having
	 * consumed nothing — see {@link #readEncoderInstruction}
	 * @throws QpackException {@link Http3Errors#QPACK_DECODER_STREAM_ERROR} at connection scope, for
	 *                        an instruction that is present but malformed
	 */
	public static @Nullable DecoderInstruction readDecoderInstruction(ByteBuf buf) throws QpackException {
		if (!buf.canRead()) return null;
		int head = buf.head();
		int first = buf.peek() & 0xFF;
		DecoderInstruction instruction;
		if ((first & 0x80) != 0) {
			instruction = hasWholePrefixedInteger(buf, SECTION_ACK_PREFIX) ?
				new SectionAcknowledgment(readInteger(buf, SECTION_ACK_PREFIX, Http3Errors.QPACK_DECODER_STREAM_ERROR)) :
				null;
		} else if ((first & 0x40) != 0) {
			instruction = hasWholePrefixedInteger(buf, STREAM_CANCELLATION_PREFIX) ?
				new StreamCancellation(readInteger(buf, STREAM_CANCELLATION_PREFIX, Http3Errors.QPACK_DECODER_STREAM_ERROR)) :
				null;
		} else {
			instruction = hasWholePrefixedInteger(buf, INSERT_COUNT_INCREMENT_PREFIX) ?
				new InsertCountIncrement(readInteger(buf, INSERT_COUNT_INCREMENT_PREFIX, Http3Errors.QPACK_DECODER_STREAM_ERROR)) :
				null;
		}
		if (instruction == null) buf.head(head);
		return instruction;
	}

	private static @Nullable InsertWithNameReference readInsertWithNameReference(ByteBuf buf) throws QpackException {
		boolean staticTable = (buf.peek() & NAME_REFERENCE_STATIC_FLAG) != 0;
		if (!hasWholePrefixedInteger(buf, NAME_REFERENCE_PREFIX)) return null;
		long nameIndex = readInteger(buf, NAME_REFERENCE_PREFIX, Http3Errors.QPACK_ENCODER_STREAM_ERROR);
		byte[] value = readString(buf, VALUE_PREFIX, VALUE_HUFFMAN_FLAG, Http3Errors.QPACK_ENCODER_STREAM_ERROR);
		return value == null ? null : new InsertWithNameReference(staticTable, nameIndex, value);
	}

	private static @Nullable InsertWithLiteralName readInsertWithLiteralName(ByteBuf buf) throws QpackException {
		byte[] name = readString(buf, LITERAL_NAME_PREFIX, LITERAL_NAME_HUFFMAN_FLAG, Http3Errors.QPACK_ENCODER_STREAM_ERROR);
		if (name == null) return null;
		byte[] value = readString(buf, VALUE_PREFIX, VALUE_HUFFMAN_FLAG, Http3Errors.QPACK_ENCODER_STREAM_ERROR);
		return value == null ? null : new InsertWithLiteralName(name, value);
	}

	// ---------------------------------------------------------------- string literals

	/** The bytes {@link #writeString} emits for {@code data}, Huffman decision included. */
	private static int stringLength(byte[] data, int prefixBits) {
		int huffmanLength = QpackHuffman.encodedLength(data, 0, data.length);
		int length = Math.min(huffmanLength, data.length);
		return QpackIntegers.encodedLength(prefixBits, length) + length;
	}

	/**
	 * Writes one {@code H|flags length(prefixBits+) octets} string, choosing Huffman only when it is
	 * shorter than the literal — the same rule {@link QpackStaticEncoder} applies to a field line
	 * (RFC 7541 §5.2, phase-1 FR-029), so the two encoders stay comparable byte for byte.
	 */
	private static void writeString(ByteBuf out, byte[] data, int prefixBits, int baseFlags, int huffmanFlag) {
		int huffmanLength = QpackHuffman.encodedLength(data, 0, data.length);
		boolean huffman = huffmanLength < data.length;
		int length = huffman ? huffmanLength : data.length;
		QpackIntegers.writeInteger(out, prefixBits, huffman ? baseFlags | huffmanFlag : baseFlags, length);
		if (huffman) {
			QpackHuffman.encode(out, data, 0, data.length);
		} else {
			out.write(data);
		}
	}

	/** @return the decoded octets, or {@code null} if the string is not yet fully buffered */
	private static byte @Nullable [] readString(ByteBuf buf, int prefixBits, int huffmanFlag, long errorCode)
		throws QpackException {
		if (!buf.canRead()) return null;
		boolean huffman = (buf.peek() & huffmanFlag) != 0;
		if (!hasWholePrefixedInteger(buf, prefixBits)) return null;
		long declaredLength = readInteger(buf, prefixBits, errorCode);
		if (declaredLength > buf.readRemaining()) return null;

		byte[] encoded = new byte[(int) declaredLength];
		buf.read(encoded);
		if (!huffman) return encoded;

		// RFC 7541 Appendix B's shortest code is 5 bits, so the decoded form is at most 8/5 of the
		// encoded one — tight enough to size the output once rather than grow it, and proportional to
		// bytes already materialized rather than to a length the peer declared.
		byte[] decoded = new byte[(int) Math.min((long) encoded.length * 8 / 5, Integer.MAX_VALUE)];
		RefInt size = new RefInt(0);
		try {
			QpackHuffman.decode(encoded, 0, encoded.length, b -> decoded[size.value++] = b);
		} catch (QpackException e) {
			throw QpackException.connectionError(errorCode, e.reason());
		}
		return size.value == decoded.length ? decoded : Arrays.copyOf(decoded, size.value);
	}

	// ---------------------------------------------------------------- prefixed integers

	/**
	 * Whether the prefixed integer at the head of {@code buf} is fully buffered, checked without
	 * consuming anything so that an instruction split across two reads can simply be retried.
	 * <p>
	 * A continuation run that never terminates within the buffer reads as "not yet complete", which is
	 * correct: the bytes present are genuinely ambiguous. It is the caller's
	 * {@code qpackMaxInstructionSize} bound that turns a peer sending such a run forever into a
	 * connection error rather than unbounded buffering (FR-028).
	 */
	private static boolean hasWholePrefixedInteger(ByteBuf buf, int prefixBits) {
		byte[] array = buf.array();
		int tail = buf.tail();
		int pos = buf.head();
		if (pos >= tail) return false;
		int prefixMax = (1 << prefixBits) - 1;
		if ((array[pos] & prefixMax) != prefixMax) return true;
		while (++pos < tail) {
			if ((array[pos] & 0x80) == 0) return true;
		}
		return false;
	}

	/**
	 * {@link QpackIntegers#readInteger} re-scoped: it raises {@code QPACK_DECOMPRESSION_FAILED},
	 * which is the field-section code. A malformed integer on one of the QPACK streams is that
	 * stream's error instead, at connection scope (RFC 9204 §6, FR-029/FR-030, FR-032). The reason
	 * text is carried through unchanged — those messages name protocol elements only (FR-063).
	 */
	private static long readInteger(ByteBuf buf, int prefixBits, long errorCode) throws QpackException {
		try {
			return QpackIntegers.readInteger(buf, prefixBits);
		} catch (QpackException e) {
			throw QpackException.connectionError(errorCode, e.reason());
		}
	}
}
