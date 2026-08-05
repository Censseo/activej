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
import io.activej.http3.Http3Errors;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.InsertWithLiteralName;
import io.activej.http3.qpack.QpackInstructions.InsertWithNameReference;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.activej.common.Checks.checkState;

/**
 * A {@link QpackEncoder} backed by the RFC 9204 §3.2 dynamic table.
 * <p>
 * Synchronous and non-reactive like the rest of this package (ADR-016, research D-1): it neither
 * owns nor writes the QPACK encoder stream. It <b>accumulates</b> the instructions it wants sent and
 * {@code Http3Connection} drains them with {@link #drainPendingInstructions()}, writing them to that
 * stream <i>before</i> the field section that references them leaves (research D-2). Ordering
 * therefore lives in one place rather than in every encoder.
 * <p>
 * At {@code capacity == 0} this class is byte-for-byte {@link QpackStaticEncoder}: no instruction is
 * ever accumulated, no dynamic representation is ever emitted, and the never-indexed {@code N} bit is
 * suppressed, so a connection that negotiated no table sees exactly phase-1 output (spec FR-040).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 §4.5 — Field Line
 * Representations</a>
 */
public final class QpackDynamicEncoder implements QpackEncoder {
	/**
	 * The stream id for a field section that belongs to no request stream. Such a section cannot be
	 * tracked, so it cannot pin the entries it would reference: {@link #encode(List)} is deliberately
	 * side-effect free and emits phase-1 representations only.
	 */
	public static final long NO_STREAM = -1;

	/**
	 * Bounds instructions accumulated between two drains (SI-3). Reaching it degrades a field to a
	 * literal representation rather than failing — a caller that drains after every section, as
	 * {@code Http3Connection} does, never approaches it.
	 */
	static final int MAX_PENDING_INSTRUCTIONS = 256;

	/** Indexed Field Line, static table: {@code 1 1 index(6+)}. */
	private static final int INDEXED_STATIC_FLAGS = 0xC0;

	/** Indexed Field Line, dynamic table: {@code 1 0 index(6+)}. */
	private static final int INDEXED_DYNAMIC_FLAGS = 0x80;

	private static final int INDEXED_PREFIX = 6;

	/** Literal Field Line with Name Reference: {@code 0 1 N T index(4+)}. */
	private static final int LITERAL_NAME_REF_FLAGS = 0x40;
	private static final int LITERAL_NAME_REF_STATIC_FLAG = 0x10;
	private static final int LITERAL_NAME_REF_NEVER_INDEXED_FLAG = 0x20;
	private static final int LITERAL_NAME_REF_PREFIX = 4;

	/** Literal Field Line with Literal Name: {@code 0 0 1 N H name-len(3+)}. */
	private static final int LITERAL_NAME_FLAGS = 0x20;
	private static final int LITERAL_NAME_NEVER_INDEXED_FLAG = 0x10;
	private static final int LITERAL_NAME_HUFFMAN_FLAG = 0x08;
	private static final int LITERAL_NAME_PREFIX = 3;

	private static final int VALUE_PREFIX = 7;
	private static final int VALUE_HUFFMAN_FLAG = 0x80;

	private final int capacity;
	private final int peerBlockedStreams;
	private final boolean dynamicEnabled;
	private final Set<HttpHeader> neverIndexedNames;

	private final QpackDynamicTable table;
	private final List<EncoderInstruction> pending = new ArrayList<>();

	/** Streams carrying an emitted Required Insert Count the peer has not yet been shown to hold. */
	private final Map<Long, Long> blocked = new HashMap<>();

	private long knownReceivedCount;
	private long fieldsEncoded;
	private long dynamicReferences;

	/**
	 * @param peerMaxTableCapacity   the peer's {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. This, and not
	 *                               {@code capacity}, is the RFC 9204 §4.5.1.1 {@code MaxEntries} base:
	 *                               the peer reconstructs a Required Insert Count against the value in
	 *                               SETTINGS, which never changes for the connection
	 * @param capacity               the capacity actually requested, i.e.
	 *                               {@code min(peerMaxTableCapacity, local qpackMaxTableCapacity)}. When
	 *                               non-zero, a {@code Set Dynamic Table Capacity} is seeded as the
	 *                               first pending instruction (spec FR-017)
	 * @param peerBlockedStreams     the peer's {@code SETTINGS_QPACK_BLOCKED_STREAMS} (spec FR-020)
	 * @param maxOutstandingSections the bound on unacknowledged field sections (SI-3)
	 * @param neverIndexedFieldNames lowercase field names that must never be indexed, i.e.
	 *                               {@code Http3Settings.qpackNeverIndexedFields()} (spec FR-022)
	 */
	public QpackDynamicEncoder(int peerMaxTableCapacity, int capacity, int peerBlockedStreams,
		int maxOutstandingSections, Set<String> neverIndexedFieldNames
	) {
		if (peerMaxTableCapacity < 0) {
			throw new IllegalArgumentException("peerMaxTableCapacity must not be negative");
		}
		if (capacity < 0 || capacity > peerMaxTableCapacity) {
			throw new IllegalArgumentException("capacity out of range [0, " + peerMaxTableCapacity + ']');
		}
		if (peerBlockedStreams < 0) {
			throw new IllegalArgumentException("peerBlockedStreams must not be negative");
		}
		if (maxOutstandingSections < 0) {
			throw new IllegalArgumentException("maxOutstandingSections must not be negative");
		}
		if (neverIndexedFieldNames == null) {
			throw new IllegalArgumentException("neverIndexedFieldNames must not be null");
		}
		this.capacity = capacity;
		this.peerBlockedStreams = peerBlockedStreams;
		this.dynamicEnabled = capacity > 0;
		this.neverIndexedNames = toHeaders(neverIndexedFieldNames);
		// MaxEntries is derived from the advertised maximum, so the table is built against it and only
		// then narrowed to the negotiated capacity.
		this.table = QpackDynamicTable.forEncoder(peerMaxTableCapacity, maxOutstandingSections);
		this.table.setCapacity(capacity);
		if (dynamicEnabled) {
			this.pending.add(new SetDynamicTableCapacity(capacity));
		}
	}

	private static Set<HttpHeader> toHeaders(Set<String> fieldNames) {
		Set<HttpHeader> headers = new HashSet<>(fieldNames.size());
		for (String fieldName : fieldNames) {
			headers.add(HttpHeaders.of(fieldName.toLowerCase(Locale.ROOT)));
		}
		return headers;
	}

	// ---------------------------------------------------------------------------------- encoding

	/**
	 * Encodes a field section belonging to no request stream — see {@link #NO_STREAM}. Equivalent to
	 * {@code encode(NO_STREAM, fields)}.
	 */
	@Override
	public ByteBuf encode(List<QpackField> fields) {
		return encode(NO_STREAM, fields);
	}

	/**
	 * Encodes one field section for {@code streamId}, choosing representations by the deterministic
	 * FR-021 order and accumulating whatever encoder-stream instructions that choice requires.
	 *
	 * @return a new {@code ByteBuf} the caller owns
	 */
	public ByteBuf encode(long streamId, List<QpackField> fields) {
		boolean mayInsert = dynamicEnabled && streamId != NO_STREAM;
		// Asked before anything is committed: a section that cannot be tracked cannot pin the entries it
		// would reference, so it must be encoded literally (QpackDynamicTable.canTrackSection).
		boolean mayReference = mayInsert && table.canTrackSection();
		boolean mayBlock = mayInsert && (blocked.containsKey(streamId) || blocked.size() < peerBlockedStreams);

		List<Line> lines = new ArrayList<>(fields.size());
		for (QpackField field : fields) {
			lines.add(plan(field, mayInsert, mayReference, mayBlock));
		}

		// Nothing is pinned until trackSection below, so a later line's insertion may have evicted an
		// entry an earlier line of this very section referenced. Such a reference is repaired to a
		// literal rather than emitted: the peer resolves it against a table that no longer holds it and
		// would answer QPACK_DECOMPRESSION_FAILED (RFC 9204 §2.2.3).
		long[] references = new long[fields.size()];
		int referenceCount = 0;
		long highestReferenced = -1;
		for (int i = 0; i < lines.size(); i++) {
			long absoluteIndex = lines.get(i).absoluteIndex();
			if (absoluteIndex < 0) continue;
			if (!table.isAvailable(absoluteIndex)) {
				// Only a field that reached the dynamic-table branches can hold a reference, and a
				// never-indexed one never does, so the repaired literal never carries N.
				lines.set(i, literal(fields.get(i), false));
				continue;
			}
			references[referenceCount++] = absoluteIndex;
			highestReferenced = Math.max(highestReferenced, absoluteIndex);
		}
		long requiredInsertCount = highestReferenced + 1;

		if (referenceCount > 0) {
			checkState(table.trackSection(streamId, requiredInsertCount, Arrays.copyOf(references, referenceCount)),
				"the field section was planned against a table that then refused to track it");
			if (requiredInsertCount > knownReceivedCount) {
				blocked.merge(streamId, requiredInsertCount, Math::max);
			}
		}
		fieldsEncoded += fields.size();
		dynamicReferences += referenceCount;
		return write(lines, requiredInsertCount);
	}

	/**
	 * A {@link QpackEncoder} view bound to one request stream — the seam {@code Http3Connection} hands
	 * to an {@code Http3RequestStream}, so the phase-1 call site keeps its unchanged interface.
	 */
	public QpackEncoder forStream(long streamId) {
		return fields -> encode(streamId, fields);
	}

	/**
	 * The spec FR-021 order, first match winning: static index, dynamic index, insert-then-index, name
	 * reference with a literal value, full literal. A never-indexed field (RFC 9204 §7.1) skips the
	 * first three outright and never takes a <i>dynamic</i> name reference either, so it can contribute
	 * nothing to a Required Insert Count and can never block a stream.
	 */
	private Line plan(QpackField field, boolean mayInsert, boolean mayReference, boolean mayBlock) {
		HttpHeader name = field.name();
		byte[] value = field.value();

		int exactIndex = QpackStaticTable.indexOfNameAndValue(name, value);
		if (field.neverIndexed() || neverIndexedNames.contains(name)) {
			// At capacity 0 the N bit is suppressed anyway, so an exact static index must still be taken
			// here: it stores nothing anywhere, its value is a public constant of the table, and skipping
			// it costs a byte that phase 1 does not spend — which is FR-040 byte identity broken for any
			// never-indexed name the static table carries with an empty value (`authorization`,
			// `set-cookie`).
			return exactIndex != -1 && !dynamicEnabled ?
				new Line(Form.INDEXED_STATIC, exactIndex, -1, null, value, false) :
				literal(field, dynamicEnabled);
		}

		if (exactIndex != -1) {
			return new Line(Form.INDEXED_STATIC, exactIndex, -1, null, value, false);
		}

		long knownIndex = mayInsert ? table.findNameAndValue(name, value) : QpackDynamicTable.NOT_FOUND;
		if (knownIndex != QpackDynamicTable.NOT_FOUND) {
			if (mayReference && isReferenceable(knownIndex, mayBlock)) {
				return new Line(Form.INDEXED_DYNAMIC, -1, knownIndex, null, value, false);
			}
		} else if (mayInsert && pending.size() < MAX_PENDING_INSTRUCTIONS &&
				   table.fits(QpackDynamicTable.entrySize(name, value))
		) {
			// The insertion stands even when this section may not reference it (spec FR-020 at a peer
			// limit of 0): the entry becomes referenceable the moment the Known Received Count covers it.
			long insertedIndex = insertAndRecord(field);
			if (insertedIndex != QpackDynamicTable.NOT_INSERTED &&
				mayReference && isReferenceable(insertedIndex, mayBlock)
			) {
				return new Line(Form.INDEXED_DYNAMIC, -1, insertedIndex, null, value, false);
			}
		}

		int staticNameIndex = QpackStaticTable.indexOfName(name);
		if (staticNameIndex != -1) {
			return new Line(Form.LITERAL_STATIC_NAME, staticNameIndex, -1, null, value, false);
		}
		if (mayReference) {
			long nameIndex = table.findName(name);
			if (nameIndex != QpackDynamicTable.NOT_FOUND && isReferenceable(nameIndex, mayBlock)) {
				return new Line(Form.LITERAL_DYNAMIC_NAME, -1, nameIndex, null, value, false);
			}
		}
		return literal(field, false);
	}

	private static Line literal(QpackField field, boolean neverIndexed) {
		HttpHeader name = field.name();
		int staticNameIndex = QpackStaticTable.indexOfName(name);
		return staticNameIndex != -1 ?
			new Line(Form.LITERAL_STATIC_NAME, staticNameIndex, -1, null, field.value(), neverIndexed) :
			new Line(Form.LITERAL_NAME, -1, -1, field.lowercaseNameBytes(), field.value(), neverIndexed);
	}

	/**
	 * RFC 9204 §2.1.2: an entry the peer is known to hold may always be referenced; a newer one blocks
	 * the stream until the peer catches up, which the peer's {@code SETTINGS_QPACK_BLOCKED_STREAMS}
	 * budget must permit (spec FR-020).
	 * <p>
	 * A blocking reference carries a second, independent bound. RFC 9204 §4.5.1.1 reconstructs the
	 * Required Insert Count modulo {@code 2 × MaxEntries} and accepts it only up to
	 * {@code TotalNumberOfInserts + MaxEntries}, so a section may run at most {@code MaxEntries} ahead
	 * of what the peer's decoder holds — and the only lower bound this encoder has on that is the Known
	 * Received Count. Beyond it the count reconstructs to a <i>different</i> value rather than failing
	 * to reconstruct, which the peer then rejects as a reference to an evicted entry. Reachable
	 * whenever one field section inserts more entries than the table holds, i.e. at a small capacity.
	 */
	private boolean isReferenceable(long absoluteIndex, boolean mayBlock) {
		if (absoluteIndex < knownReceivedCount) return true;
		return mayBlock && absoluteIndex < knownReceivedCount + table.maxEntries();
	}

	/**
	 * Inserts and accumulates the matching RFC 9204 §4.3 instruction, or does neither.
	 * <p>
	 * The name reference is resolved <b>before</b> the insertion, since the peer resolves it against the
	 * Insert Count it holds when the instruction arrives; and it is abandoned for a literal name when
	 * this very insertion evicted the entry it named, so no instruction can depend on an entry the same
	 * instruction removes.
	 *
	 * @return the new entry's absolute index, or {@link QpackDynamicTable#NOT_INSERTED} — in which case
	 * nothing was accumulated either, because nothing may go on the wire that the peer's table would
	 * not also apply
	 */
	private long insertAndRecord(QpackField field) {
		HttpHeader name = field.name();
		int staticNameIndex = QpackStaticTable.indexOfName(name);
		long nameIndex = staticNameIndex == -1 ? table.findName(name) : QpackDynamicTable.NOT_FOUND;
		long nameRelativeIndex = table.insertCount() - 1 - nameIndex;

		byte[] owned = field.value().clone();
		long absoluteIndex = table.insert(name, owned);
		if (absoluteIndex == QpackDynamicTable.NOT_INSERTED) return QpackDynamicTable.NOT_INSERTED;

		if (staticNameIndex != -1) {
			pending.add(new InsertWithNameReference(true, staticNameIndex, owned));
		} else if (nameIndex != QpackDynamicTable.NOT_FOUND && table.isAvailable(nameIndex)) {
			pending.add(new InsertWithNameReference(false, nameRelativeIndex, owned));
		} else {
			pending.add(new InsertWithLiteralName(field.lowercaseNameBytes(), owned));
		}
		return absoluteIndex;
	}

	private ByteBuf write(List<Line> lines, long requiredInsertCount) {
		ByteBuf out = ByteBufPool.allocate(64);
		QpackIntegers.writeInteger(out, 8, 0, encodedInsertCount(requiredInsertCount));
		QpackIntegers.writeInteger(out, 7, 0, 0); // S = 0, Delta Base = 0, so Base == Required Insert Count
		for (Line line : lines) {
			out = writeLine(out, line, requiredInsertCount);
		}
		return out;
	}

	/**
	 * RFC 9204 §4.5.1.1. {@code MaxEntries} is at least 1 whenever a Required Insert Count is non-zero:
	 * reaching one takes an insertion, and an insertion takes a capacity above one entry's overhead.
	 */
	private long encodedInsertCount(long requiredInsertCount) {
		return requiredInsertCount == 0 ? 0 : (requiredInsertCount % (2 * table.maxEntries())) + 1;
	}

	private static ByteBuf writeLine(ByteBuf out, Line line, long base) {
		switch (line.form()) {
			case INDEXED_STATIC -> {
				out = ByteBufPool.ensureWriteRemaining(out, 8);
				QpackIntegers.writeInteger(out, INDEXED_PREFIX, INDEXED_STATIC_FLAGS, line.staticIndex());
			}
			case INDEXED_DYNAMIC -> {
				out = ByteBufPool.ensureWriteRemaining(out, 16);
				QpackIntegers.writeInteger(out, INDEXED_PREFIX, INDEXED_DYNAMIC_FLAGS, base - 1 - line.absoluteIndex());
			}
			case LITERAL_STATIC_NAME -> {
				out = ByteBufPool.ensureWriteRemaining(out, 16 + line.value().length);
				int flags = LITERAL_NAME_REF_FLAGS | LITERAL_NAME_REF_STATIC_FLAG |
							(line.neverIndexed() ? LITERAL_NAME_REF_NEVER_INDEXED_FLAG : 0);
				QpackIntegers.writeInteger(out, LITERAL_NAME_REF_PREFIX, flags, line.staticIndex());
				out = writeStringLiteral(out, line.value(), VALUE_PREFIX, 0, VALUE_HUFFMAN_FLAG);
			}
			case LITERAL_DYNAMIC_NAME -> {
				out = ByteBufPool.ensureWriteRemaining(out, 16 + line.value().length);
				int flags = LITERAL_NAME_REF_FLAGS |
							(line.neverIndexed() ? LITERAL_NAME_REF_NEVER_INDEXED_FLAG : 0);
				QpackIntegers.writeInteger(out, LITERAL_NAME_REF_PREFIX, flags, base - 1 - line.absoluteIndex());
				out = writeStringLiteral(out, line.value(), VALUE_PREFIX, 0, VALUE_HUFFMAN_FLAG);
			}
			case LITERAL_NAME -> {
				out = ByteBufPool.ensureWriteRemaining(out, 16 + line.nameBytes().length + line.value().length);
				int flags = LITERAL_NAME_FLAGS | (line.neverIndexed() ? LITERAL_NAME_NEVER_INDEXED_FLAG : 0);
				out = writeStringLiteral(out, line.nameBytes(), LITERAL_NAME_PREFIX, flags, LITERAL_NAME_HUFFMAN_FLAG);
				out = writeStringLiteral(out, line.value(), VALUE_PREFIX, 0, VALUE_HUFFMAN_FLAG);
			}
		}
		return out;
	}

	/**
	 * Writes one {@code H|N... length(prefixBits+) octets} literal string, choosing Huffman only when
	 * it shortens the representation — the same rule {@link QpackStaticEncoder} applies, which is what
	 * keeps the two encoders byte-comparable at capacity 0 (RFC 7541 §5.2, phase-1 FR-029).
	 */
	private static ByteBuf writeStringLiteral(ByteBuf out, byte[] data, int prefixBits, int baseFlags, int huffmanFlag) {
		int huffmanLength = QpackHuffman.encodedLength(data, 0, data.length);
		boolean huffman = huffmanLength < data.length;
		int length = huffman ? huffmanLength : data.length;
		int flags = huffman ? (baseFlags | huffmanFlag) : baseFlags;

		out = ByteBufPool.ensureWriteRemaining(out, QpackIntegers.encodedLength(prefixBits, length) + length);
		QpackIntegers.writeInteger(out, prefixBits, flags, length);
		if (huffman) {
			QpackHuffman.encode(out, data, 0, data.length);
		} else {
			out.write(data);
		}
		return out;
	}

	// ------------------------------------------------------------------ instruction accumulation

	/** Whether {@link #drainPendingInstructions()} would return anything. */
	public boolean hasPendingInstructions() {
		return !pending.isEmpty();
	}

	/**
	 * The encoder-stream instructions accumulated so far, in the order they must be written, clearing
	 * them. The result holds values rather than buffers, so nothing here can leak (research D-2, DI-1);
	 * the caller sizes one buffer from {@link QpackInstructions.Instruction#encodedLength()} and calls
	 * {@link QpackInstructions.Instruction#writeTo(ByteBuf)}.
	 */
	public List<EncoderInstruction> drainPendingInstructions() {
		if (pending.isEmpty()) return List.of();
		List<EncoderInstruction> drained = List.copyOf(pending);
		pending.clear();
		return drained;
	}

	// ------------------------------------------------------------------- inbound decoder stream

	/**
	 * Applies every whole RFC 9204 §4.4 instruction at the head of {@code buf}, leaving a trailing
	 * partial instruction unconsumed so the caller may append the bytes that follow and retry.
	 * <p>
	 * <b>Ownership</b>: the caller keeps and recycles {@code buf} on every path, this one included.
	 *
	 * @throws QpackException {@link Http3Errors#QPACK_DECODER_STREAM_ERROR} at connection scope
	 */
	public void consumeDecoderStream(ByteBuf buf) throws QpackException {
		DecoderInstruction instruction;
		while ((instruction = QpackInstructions.readDecoderInstruction(buf)) != null) {
			applyDecoderInstruction(instruction);
		}
	}

	/**
	 * Applies one already-parsed decoder-stream instruction.
	 *
	 * @throws QpackException {@link Http3Errors#QPACK_DECODER_STREAM_ERROR} at connection scope
	 */
	public void applyDecoderInstruction(DecoderInstruction instruction) throws QpackException {
		if (instruction instanceof SectionAcknowledgment acknowledgment) {
			long requiredInsertCount = table.acknowledgeSection(acknowledgment.streamId());
			if (requiredInsertCount == QpackDynamicTable.NO_SECTION) {
				throw QpackException.connectionError(Http3Errors.QPACK_DECODER_STREAM_ERROR,
					"section acknowledgment for a stream with no outstanding section");
			}
			advanceKnownReceivedCount(requiredInsertCount);
		} else if (instruction instanceof StreamCancellation cancellation) {
			// RFC 9204 §4.4.2: cancelling a stream that never carried a section is legal, and says only
			// that the peer will never acknowledge one.
			onStreamCancelled(cancellation.streamId());
		} else if (instruction instanceof InsertCountIncrement insertCountIncrement) {
			long increment = insertCountIncrement.increment();
			if (increment == 0) {
				throw QpackException.connectionError(Http3Errors.QPACK_DECODER_STREAM_ERROR,
					"insert count increment of 0");
			}
			// Written against the headroom rather than as knownReceivedCount + increment > insertCount:
			// a wire-supplied increment near 2^62 makes that sum wrap negative and pass (SI-4).
			if (increment > table.insertCount() - knownReceivedCount) {
				throw QpackException.connectionError(Http3Errors.QPACK_DECODER_STREAM_ERROR,
					"insert count increment above the local insert count");
			}
			advanceKnownReceivedCount(knownReceivedCount + increment);
		}
	}

	private void advanceKnownReceivedCount(long requiredInsertCount) {
		if (requiredInsertCount <= knownReceivedCount) return;
		knownReceivedCount = requiredInsertCount;
		blocked.values().removeIf(emitted -> emitted <= knownReceivedCount);
	}

	// -------------------------------------------------------------------------- release funnels

	/**
	 * The local stream-reset path: releases every entry this stream's unacknowledged sections pinned.
	 * Idempotent, and never throws — a stream that referenced nothing dynamic has nothing to release.
	 */
	public void onStreamCancelled(long streamId) {
		table.cancelStream(streamId);
		blocked.remove(streamId);
	}

	/**
	 * Connection close: releases every outstanding section on every stream.
	 *
	 * @return how many sections were released
	 */
	public int releaseAll() {
		blocked.clear();
		return table.releaseAll();
	}

	// --------------------------------------------------------------------------------- counters

	/** Whether a dynamic table was negotiated at all; false means byte-for-byte phase-1 output. */
	public boolean usesDynamicTable() {
		return dynamicEnabled;
	}

	/** The capacity this encoder set on its own table, at most the peer's advertised maximum. */
	public int capacity() {
		return capacity;
	}

	/** Insertions ever made into this encoder's table. Monotonic, unaffected by eviction. */
	public long insertCount() {
		return table.insertCount();
	}

	/** Entries evicted to make room — the absolute index of the oldest entry still available. */
	public long evictedCount() {
		return table.droppedCount();
	}

	/** RFC 9204 §3.2.1 accounted size the table holds now, at most {@link #capacity()}. */
	public int tableSize() {
		return table.size();
	}

	/** RFC 9204 §2.1.4: insertions the peer's decoder is known to have processed. */
	public long knownReceivedCount() {
		return knownReceivedCount;
	}

	/** Streams carrying a section whose Required Insert Count the Known Received Count has not reached. */
	public int blockedStreamCount() {
		return blocked.size();
	}

	/** Field lines encoded in any form — the hit-rate denominator against {@link #dynamicReferences()}. */
	public long fieldsEncoded() {
		return fieldsEncoded;
	}

	/** Field lines emitted as a dynamic-table reference — the hit-rate numerator against {@link #fieldsEncoded()}. */
	public long dynamicReferences() {
		return dynamicReferences;
	}

	private enum Form {
		INDEXED_STATIC, INDEXED_DYNAMIC, LITERAL_STATIC_NAME, LITERAL_DYNAMIC_NAME, LITERAL_NAME
	}

	/** One planned field line: {@code absoluteIndex} is {@code -1} unless the line references the dynamic table. */
	private record Line(
		Form form, int staticIndex, long absoluteIndex, byte[] nameBytes, byte[] value, boolean neverIndexed
	) {}
}
