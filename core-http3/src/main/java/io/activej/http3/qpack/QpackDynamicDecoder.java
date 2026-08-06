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
import io.activej.common.Checks;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http3.Http3Errors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.activej.common.Checks.checkArgument;

/**
 * A {@link QpackDecoder} that resolves the RFC 9204 §4.5 field-line representations against a
 * {@link QpackDynamicTable} the peer's encoder stream fills — the five representations
 * {@link QpackStaticDecoder} rejects included.
 * <p>
 * Synchronous and non-reactive like the rest of this package (ADR-016, research D-1): no
 * {@code Reactor}, no {@code Promise}, no {@code checkInReactorThread}. Confinement is a property of
 * the owning {@code Http3Connection}.
 *
 * <h4>Two entry points, one decode</h4>
 * {@link #decode} is the frozen {@link QpackDecoder} contract: it owns and recycles its input on
 * every path, and a section it cannot decode <i>yet</i> is a failure. {@link #decodeOrBlock} is the
 * same decode with the RFC 9204 §2.1.2 blocked case surfaced instead of failed — the caller gets its
 * buffer back untouched and re-enters once {@link #insertCount()} has caught up. Holding the section
 * in the meantime, and bounding how many and how long, belongs to the caller (feature 006 US2), not
 * here: this class keeps no per-stream state and its API deliberately names no stream.
 *
 * <h4>Required Insert Count, and why capacity 0 needs a guard</h4>
 * RFC 9204 §4.5.1.1 reconstructs the count modulo {@code 2 × MaxEntries}, where
 * {@code MaxEntries = floor(SETTINGS_QPACK_MAX_TABLE_CAPACITY / 32)} — the advertised
 * <b>maximum</b>, never the current capacity. At a maximum of 0 that modulus is 0, so any non-zero
 * encoded count is rejected before the division rather than dividing by it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 §4.5 — Field Line
 * Representations</a>
 */
public final class QpackDynamicDecoder implements QpackDecoder {
	private static final boolean CHECKS = Checks.isEnabled(QpackDynamicDecoder.class);

	private final QpackDynamicTable table;
	private final int maxCapacity;
	private final int blockedStreams;
	private final long maxFieldSectionSize;

	private long announcedInsertCount;

	/**
	 * @param maxCapacity         the locally advertised {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. The
	 *                            table starts at capacity <b>0</b> regardless: RFC 9204 §4.3.1's
	 *                            {@code Set Dynamic Table Capacity} is what raises it, and this value
	 *                            is only the ceiling that instruction may not exceed
	 * @param blockedStreams      the locally advertised {@code SETTINGS_QPACK_BLOCKED_STREAMS}. At 0,
	 *                            a section that would block is a connection error rather than a
	 *                            {@link Blocked} result (RFC 9204 §2.1.2)
	 * @param maxFieldSectionSize the RFC 9114 §4.2.2 <b>accounted</b> bound, checked incrementally
	 *                            against decoded output exactly as {@link QpackStaticDecoder} checks it
	 */
	public QpackDynamicDecoder(int maxCapacity, int blockedStreams, long maxFieldSectionSize) {
		if (CHECKS) checkArgument(blockedStreams >= 0, "blockedStreams must not be negative");
		if (CHECKS) checkArgument(maxFieldSectionSize >= 0, "maxFieldSectionSize must not be negative");
		this.table = QpackDynamicTable.forDecoder(maxCapacity);
		this.table.setCapacity(0);
		this.maxCapacity = maxCapacity;
		this.blockedStreams = blockedStreams;
		this.maxFieldSectionSize = maxFieldSectionSize;
	}

	/** What {@link #decodeOrBlock} produced: either the field lines, or the section handed back unconsumed. */
	public sealed interface SectionResult permits Decoded, Blocked {}

	/**
	 * @param requiredInsertCount the reconstructed RFC 9204 §4.5.1.1 count. Non-zero is what obliges
	 *                            the caller to emit a {@code Section Acknowledgment} (FR-024)
	 */
	public record Decoded(List<QpackField> fields, long requiredInsertCount) implements SectionResult {}

	/**
	 * The section could not be decoded yet.
	 *
	 * @param section the caller's buffer again, with its read position <b>restored</b>: nothing was
	 *                consumed and nothing recycled. The caller owns it, and must either recycle it or
	 *                hand it back to {@link #decodeOrBlock} once {@link #insertCount()} has reached
	 *                {@code requiredInsertCount}
	 */
	public record Blocked(long requiredInsertCount, ByteBuf section) implements SectionResult {}

	/**
	 * The {@link QpackDecoder} entry point, which cannot wait: a section that would block is a
	 * connection error here, since this signature has nowhere to put one. A caller that can hold a
	 * blocked section calls {@link #decodeOrBlock} instead.
	 */
	@Override
	public List<QpackField> decode(ByteBuf encodedFieldSection) throws QpackException {
		SectionResult result = decodeOrBlock(encodedFieldSection);
		if (result instanceof Decoded decoded) return decoded.fields();
		((Blocked) result).section().recycle();
		throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			"a field section whose Required Insert Count exceeds the Insert Count, on an entry point that cannot hold it");
	}

	/**
	 * As {@link #decode}, reporting a section that blocks rather than failing it.
	 *
	 * @return {@link Decoded}, having consumed and recycled {@code encodedFieldSection}; or
	 * {@link Blocked}, having consumed and recycled <b>nothing</b>
	 * @throws QpackException on every failure {@link #decode} raises, the input recycled
	 */
	public SectionResult decodeOrBlock(ByteBuf encodedFieldSection) throws QpackException {
		int head = encodedFieldSection.head();
		boolean release = true;
		try {
			long requiredInsertCount = readRequiredInsertCount(encodedFieldSection);
			if (requiredInsertCount > table.insertCount()) {
				if (blockedStreams == 0) {
					// RFC 9204 §2.1.2: a section may block only within the advertised limit, and 0 is
					// the limit at which every blocked section exceeds it (FR-031).
					throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
						"a field section blocked on an unarrived insertion, with a blocked-stream limit of 0");
				}
				encodedFieldSection.head(head);
				release = false;
				return new Blocked(requiredInsertCount, encodedFieldSection);
			}

			long base = readBase(encodedFieldSection, requiredInsertCount);
			List<QpackField> fields = new ArrayList<>();
			SizeAccountant accountant = new SizeAccountant(maxFieldSectionSize);
			while (encodedFieldSection.canRead()) {
				fields.add(readFieldLine(encodedFieldSection, base, requiredInsertCount, accountant));
			}
			return new Decoded(fields, requiredInsertCount);
		} finally {
			if (release) encodedFieldSection.recycle();
		}
	}

	/** The locally advertised {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. */
	public int maxCapacity() {
		return maxCapacity;
	}

	/** The current capacity, after whatever {@code Set Dynamic Table Capacity} the peer last sent. */
	public int capacity() {
		return table.capacity();
	}

	/** The locally advertised {@code SETTINGS_QPACK_BLOCKED_STREAMS}. */
	public int blockedStreams() {
		return blockedStreams;
	}

	/** RFC 9204 §2.1.4 Insert Count: how many entries the peer's encoder stream has inserted here. */
	public long insertCount() {
		return table.insertCount();
	}

	/** RFC 9204 §3.2.2: entries this table has dropped from its tail, over the life of the connection. */
	public long evictedCount() {
		return table.droppedCount();
	}

	/** RFC 9204 §3.2.1 accounted size the table holds now, at most {@link #capacity()}. */
	public int tableSize() {
		return table.size();
	}

	/**
	 * FR-026: the RFC 9204 §4.4.3 {@code Insert Count Increment} still owed to the peer, or 0.
	 * <p>
	 * A pure query — emitting the instruction and then calling {@link #onInsertCountAnnounced} is the
	 * caller's, because only the caller owns the decoder stream those bytes go out on (research D-2).
	 */
	public long pendingInsertCountIncrement() {
		return table.insertCount() - announcedInsertCount;
	}

	/**
	 * The single funnel raising what the peer's encoder is known to have been told: a
	 * {@code Section Acknowledgment} passes that section's Required Insert Count (RFC 9204 §4.4.1
	 * acknowledges every insertion up to it), an {@code Insert Count Increment} passes
	 * {@link #insertCount()}. Never lowers — an acknowledgment for an older section arriving after a
	 * newer one must not un-announce an insertion.
	 */
	public void onInsertCountAnnounced(long insertCount) {
		if (insertCount > announcedInsertCount) announcedInsertCount = insertCount;
	}

	/** The table the peer's encoder stream inserts into — {@link QpackEncoderStreamReader} and tests only. */
	QpackDynamicTable table() {
		return table;
	}

	// ---------------------------------------------------------------- RFC 9204 §4.5.1, the prefix

	/**
	 * RFC 9204 §4.5.1.1's reconstruction, verbatim, with the {@code MaxEntries == 0} guard the
	 * pseudocode does not need and an implementation does.
	 */
	private long readRequiredInsertCount(ByteBuf buf) throws QpackException {
		long encodedInsertCount = QpackIntegers.readInteger(buf, 8);
		if (encodedInsertCount == 0) return 0;

		long maxEntries = table.maxEntries();
		if (maxEntries == 0) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a non-zero Encoded Required Insert Count against a maximum dynamic table capacity of 0");
		}
		long fullRange = 2 * maxEntries;
		if (encodedInsertCount > fullRange) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"an Encoded Required Insert Count above 2 × MaxEntries");
		}
		long maxValue = table.insertCount() + maxEntries;
		long maxWrapped = maxValue / fullRange * fullRange;
		long requiredInsertCount = maxWrapped + encodedInsertCount - 1;
		if (requiredInsertCount > maxValue) {
			if (requiredInsertCount <= fullRange) {
				throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"a Required Insert Count that cannot be reconstructed within the §4.5.1.1 bounds");
			}
			requiredInsertCount -= fullRange;
		}
		if (requiredInsertCount == 0) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a Required Insert Count that reconstructs to 0 from a non-zero encoding");
		}
		return requiredInsertCount;
	}

	/** RFC 9204 §4.5.1.2: {@code S} then Delta Base, resolved against the Required Insert Count. */
	private static long readBase(ByteBuf buf, long requiredInsertCount) throws QpackException {
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED, "truncated field section prefix");
		}
		boolean negative = (buf.peek() & 0x80) != 0;
		long deltaBase = QpackIntegers.readInteger(buf, 7);
		long base = negative ? requiredInsertCount - deltaBase - 1 : requiredInsertCount + deltaBase;
		if (base < 0) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a field section Base that underflows");
		}
		return base;
	}

	// ---------------------------------------------------------------- RFC 9204 §4.5.2 – §4.5.6

	private QpackField readFieldLine(ByteBuf buf, long base, long requiredInsertCount, SizeAccountant accountant)
		throws QpackException {
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED, "truncated field line");
		}
		int first = buf.peek() & 0xFF;

		if ((first & 0x80) != 0) {
			// Indexed Field Line: "1 T index(6+)".
			boolean isStatic = (first & 0x40) != 0;
			long index = QpackIntegers.readInteger(buf, 6);
			return isStatic ?
				staticIndexedField(index, accountant) :
				dynamicIndexedField(QpackDynamicTable.absoluteFromRelative(index, base), requiredInsertCount, accountant);
		}
		if ((first & 0x40) != 0) {
			// Literal Field Line with Name Reference: "0 1 N T index(4+)".
			boolean neverIndexed = (first & 0x20) != 0;
			boolean isStatic = (first & 0x10) != 0;
			long index = QpackIntegers.readInteger(buf, 4);
			return isStatic ?
				staticNamedLiteral(index, neverIndexed, buf, accountant) :
				dynamicNamedLiteral(QpackDynamicTable.absoluteFromRelative(index, base), requiredInsertCount,
					neverIndexed, buf, accountant);
		}
		if ((first & 0x20) != 0) {
			// Literal Field Line with Literal Name: "0 0 1 N H name-len(3+)".
			boolean neverIndexed = (first & 0x10) != 0;
			boolean nameHuffman = (first & 0x08) != 0;
			long nameLength = QpackIntegers.readInteger(buf, 3);
			accountant.add(32);
			byte[] nameBytes = readStringBytes(buf, nameHuffman, nameLength, accountant);
			// Recorded before interning, which is where the peer's own spelling stops being observable
			// (see QpackField's constructor Javadoc and QpackStaticDecoder's identical note).
			boolean nameHadUppercase = hasUppercase(nameBytes);
			HttpHeader name = internName(nameBytes);
			byte[] value = readLiteralValue(buf, accountant);
			return new QpackField(name, value, nameHadUppercase, neverIndexed);
		}
		if ((first & 0x10) != 0) {
			// Indexed Field Line with Post-Base Index: "0 0 0 1 index(4+)".
			long index = QpackIntegers.readInteger(buf, 4);
			return dynamicIndexedField(QpackDynamicTable.absoluteFromPostBase(index, base), requiredInsertCount,
				accountant);
		}
		// Literal Field Line with Post-Base Name Reference: "0 0 0 0 N index(3+)".
		boolean neverIndexed = (first & 0x08) != 0;
		long index = QpackIntegers.readInteger(buf, 3);
		return dynamicNamedLiteral(QpackDynamicTable.absoluteFromPostBase(index, base), requiredInsertCount,
			neverIndexed, buf, accountant);
	}

	private static QpackField staticIndexedField(long index, SizeAccountant accountant) throws QpackException {
		int staticIndex = requireStaticIndex(index);
		HttpHeader name = QpackStaticTable.name(staticIndex);
		byte[] value = QpackStaticTable.value(staticIndex);
		accountant.add(32L + name.size() + value.length);
		return new QpackField(name, value);
	}

	private QpackField dynamicIndexedField(long absoluteIndex, long requiredInsertCount, SizeAccountant accountant)
		throws QpackException {
		requireDynamicIndex(absoluteIndex, requiredInsertCount);
		HttpHeader name = table.nameAt(absoluteIndex);
		byte[] value = table.valueAt(absoluteIndex);
		accountant.add(32L + name.size() + value.length);
		// The table keeps its own copy; the field must not alias it, since a decoded value is the
		// caller's to hold for the lifetime of the message while the entry may be evicted under it.
		return new QpackField(name, value.clone(), table.nameHadUppercaseAt(absoluteIndex));
	}

	private static QpackField staticNamedLiteral(long index, boolean neverIndexed, ByteBuf buf,
		SizeAccountant accountant) throws QpackException {
		HttpHeader name = QpackStaticTable.name(requireStaticIndex(index));
		accountant.add(32L + name.size());
		byte[] value = readLiteralValue(buf, accountant);
		return new QpackField(name, value, false, neverIndexed);
	}

	private QpackField dynamicNamedLiteral(long absoluteIndex, long requiredInsertCount, boolean neverIndexed,
		ByteBuf buf, SizeAccountant accountant) throws QpackException {
		requireDynamicIndex(absoluteIndex, requiredInsertCount);
		HttpHeader name = table.nameAt(absoluteIndex);
		boolean nameHadUppercase = table.nameHadUppercaseAt(absoluteIndex);
		accountant.add(32L + name.size());
		byte[] value = readLiteralValue(buf, accountant);
		return new QpackField(name, value, nameHadUppercase, neverIndexed);
	}

	private static int requireStaticIndex(long index) throws QpackException {
		if (index < 0 || index >= QpackStaticTable.SIZE) {
			// RFC 9204 §3.1: an invalid static table index MUST be a connection error.
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"static table index out of range: " + index);
		}
		return (int) index;
	}

	/**
	 * RFC 9204 §2.2.3 and §4.5.1.2: an entry that has been evicted or never existed, <b>and</b> one at
	 * or above the section's own declared Required Insert Count, are both connection errors — the
	 * second because such a reference contradicts the count the section itself promised to block on.
	 */
	private void requireDynamicIndex(long absoluteIndex, long requiredInsertCount) throws QpackException {
		if (absoluteIndex < 0 || absoluteIndex >= requiredInsertCount) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a dynamic table reference outside the section's Required Insert Count");
		}
		if (!table.isAvailable(absoluteIndex)) {
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a dynamic table reference to an evicted or never-inserted entry");
		}
	}

	// ---------------------------------------------------------------- string literals
	// Duplicated from QpackStaticDecoder rather than extracted: that file is pinned byte-for-byte by
	// the SC-011 characterization test and is shared surface during a parallel phase. Extraction into
	// a package-private helper is a follow-up, not a change to make under both at once.

	/** The value literal shape both representations that carry one end with: {@code H value-len(7+)}. */
	private static byte[] readLiteralValue(ByteBuf buf, SizeAccountant accountant) throws QpackException {
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED, "truncated field value length");
		}
		boolean huffman = (buf.peek() & 0x80) != 0;
		long length = QpackIntegers.readInteger(buf, 7);
		return readStringBytes(buf, huffman, length, accountant);
	}

	private static byte[] readStringBytes(ByteBuf buf, boolean huffman, long declaredLength, SizeAccountant accountant)
		throws QpackException {
		if (declaredLength < 0 || declaredLength > buf.readRemaining()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"declared string length exceeds the remaining field section bytes");
		}
		int length = (int) declaredLength;
		if (!huffman) {
			byte[] literal = new byte[length];
			buf.read(literal);
			accountant.add(length);
			return literal;
		}

		byte[] encoded = new byte[length];
		buf.read(encoded);
		// Bounded by min(2x encoded length, 64 KiB): the per-octet accountant.add below aborts decoding,
		// and hence further growth, the moment the running total exceeds the configured bound.
		GrowingBytes decoded = new GrowingBytes((int) Math.min(Math.max(length, 1) * 2L, 1 << 16));
		QpackHuffman.decode(encoded, 0, encoded.length, b -> {
			accountant.add(1);
			decoded.write(b);
		});
		return decoded.toByteArray();
	}

	/** Package-private so {@link QpackEncoderStreamReader} shares one answer with this decoder. */
	static boolean hasUppercase(byte[] nameBytes) {
		for (byte b : nameBytes) {
			if (b >= 'A' && b <= 'Z') return true;
		}
		return false;
	}

	/** RFC 7541 §5.1/HTTP token case-insensitive hash, matching {@code core-http}'s registry so equals/hashCode agree. */
	static HttpHeader internName(byte[] bytes) {
		int hash = 0;
		for (byte b : bytes) {
			hash += (b | 0x20);
		}
		return HttpHeaders.of(hash, bytes, 0, bytes.length);
	}

	/** See {@code QpackStaticDecoder.GrowingBytes} — an unsynchronized growing {@code byte[]} sink. */
	private static final class GrowingBytes {
		private byte[] array;
		private int size;

		GrowingBytes(int initialCapacity) {
			this.array = new byte[initialCapacity];
		}

		void write(byte b) {
			if (size == array.length) {
				array = Arrays.copyOf(array, array.length * 2);
			}
			array[size++] = b;
		}

		byte[] toByteArray() {
			return size == array.length ? array : Arrays.copyOf(array, size);
		}
	}

	private static final class SizeAccountant {
		private final long limit;
		private long total;

		SizeAccountant(long limit) {
			this.limit = limit;
		}

		void add(long n) throws QpackException {
			total += n;
			if (total > limit) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"decoded field section exceeded the configured size bound");
			}
		}
	}
}
