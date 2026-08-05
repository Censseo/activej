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

import io.activej.http.HttpHeader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;

/**
 * The RFC 9204 §3.2 dynamic table: a FIFO of (name, value) entries with insertion at the head,
 * eviction from the tail, and stable absolute indices that survive eviction.
 * <p>
 * There are <b>two per connection and they are never one object</b>: the <i>encoder</i>'s table
 * mirrors what this endpoint has inserted into the peer's decoder, the <i>decoder</i>'s table holds
 * what the peer has inserted into ours. {@link #forEncoder} and {@link #forDecoder} are separate
 * factories precisely so conflating them fails loudly — only an encoder table carries reference
 * counts and name lookups, and asking a decoder table for either throws.
 * <p>
 * Synchronous and non-reactive (research D-1): no {@code Reactor}, no {@code Promise}, no
 * {@code ByteBuf}. Values are heap {@code byte[]}, exactly like {@link QpackField#value()}.
 * Confinement to the reactor thread is a property of the owning {@code Http3Connection}, not of this
 * API.
 *
 * <h4>Absolute indices are 0-based</h4>
 * RFC 9204 §3.2.4: the first entry ever inserted is absolute index {@code 0}, {@link #insertCount()}
 * is the number of insertions, and an index {@code i} is available iff
 * {@code droppedCount() <= i < insertCount()}. (The feature's {@code data-model.md} states the same
 * interval in 1-based form; the set is identical, the origin is not, and this class uses the RFC's.)
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-3.2">RFC 9204 §3.2 — Dynamic Table</a>
 */
public final class QpackDynamicTable {
	/** RFC 9204 §3.2.1: an entry's size is {@code len(name) + len(value) + 32}. */
	public static final int ENTRY_OVERHEAD = 32;

	/** Returned by {@link #findName} / {@link #findNameAndValue} when nothing matches. */
	public static final long NOT_FOUND = -1;

	/** Returned by {@link #insert} / {@link #duplicate} when the entry did not go in. */
	public static final long NOT_INSERTED = -1;

	/** Returned by {@link #acknowledgeSection} when the stream has no outstanding section. */
	public static final long NO_SECTION = -1;

	private static final int INITIAL_RING_SIZE = 8;

	private static final String ENCODER_ONLY = "encoder-only: a decoder table has no reference counts";

	private final int maxCapacity;
	private final boolean encoderRole;
	private final int maxOutstandingSections;

	private final Map<NameKey, Long> newestByName;
	private final Map<NameValue, Long> newestByNameValue;
	private final Map<Long, Deque<Section>> outstandingByStream;

	/**
	 * Reusable scratch for {@link #hashName}. Safe as a field because this class is confined to one
	 * reactor thread by its owning {@code Http3Connection}, and nothing here escapes a single call.
	 */
	private byte[] nameHashScratch = new byte[64];

	private Entry[] ring = new Entry[INITIAL_RING_SIZE];
	private int head;
	private int count;

	private int capacity;
	private int size;
	private long insertCount;
	private int outstandingSections;

	private QpackDynamicTable(int maxCapacity, boolean encoderRole, int maxOutstandingSections) {
		checkArgument(maxCapacity >= 0, "maxCapacity must not be negative");
		checkArgument(maxOutstandingSections >= 0, "maxOutstandingSections must not be negative");
		this.maxCapacity = maxCapacity;
		this.encoderRole = encoderRole;
		this.maxOutstandingSections = maxOutstandingSections;
		this.capacity = maxCapacity;
		this.newestByName = encoderRole ? new HashMap<>() : null;
		this.newestByNameValue = encoderRole ? new HashMap<>() : null;
		this.outstandingByStream = encoderRole ? new HashMap<>() : null;
	}

	/**
	 * A table mirroring the peer's decoder: it carries per-entry reference counts and the two
	 * name lookups an encoder needs to choose a representation.
	 *
	 * @param maxOutstandingSections the bound on unacknowledged field sections (SI-3) — a peer that
	 *                               never acknowledges must not grow this endpoint's bookkeeping
	 *                               without limit. At the bound {@link #canTrackSection()} is false
	 *                               and the encoder must fall back to literal representations.
	 */
	public static QpackDynamicTable forEncoder(int maxCapacity, int maxOutstandingSections) {
		return new QpackDynamicTable(maxCapacity, true, maxOutstandingSections);
	}

	/** A table holding what the peer inserted: no reference counts, no name lookups. */
	public static QpackDynamicTable forDecoder(int maxCapacity) {
		return new QpackDynamicTable(maxCapacity, false, 0);
	}

	/**
	 * RFC 9204 §3.2.1 entry size. Saturates at {@link Integer#MAX_VALUE} rather than overflowing:
	 * such an entry cannot fit any capacity, and saturation keeps {@link #fits} answering that.
	 */
	public static int entrySize(HttpHeader name, byte[] value) {
		long entrySize = (long) name.size() + value.length + ENTRY_OVERHEAD;
		return entrySize > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) entrySize;
	}

	/** {@link #entrySize(HttpHeader, byte[])} for an already-assembled field. */
	public static int entrySize(QpackField field) {
		return entrySize(field.name(), field.value());
	}

	/** The locally advertised {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}; {@link #capacity()} never exceeds it. */
	public int maxCapacity() {
		return maxCapacity;
	}

	/** The current capacity, set by {@code Set Dynamic Table Capacity} (RFC 9204 §4.3.1). */
	public int capacity() {
		return capacity;
	}

	/** The sum of {@link #entrySize} over the entries currently held; never above {@link #capacity()}. */
	public int size() {
		return size;
	}

	/** The entries available now — {@link #insertCount()} minus {@link #droppedCount()}. */
	public int entryCount() {
		return count;
	}

	/** Total insertions ever. Monotonic: never decreases, never resets, unaffected by eviction. */
	public long insertCount() {
		return insertCount;
	}

	/** The absolute index of the oldest available entry, i.e. how many have been evicted. */
	public long droppedCount() {
		return insertCount - count;
	}

	/**
	 * RFC 9204 §4.5.1.1 {@code MaxEntries}: {@code floor(maxCapacity / 32)}. Derived from the
	 * advertised <b>maximum</b>, not from the current {@link #capacity()} — the peer reconstructs a
	 * Required Insert Count against the value in SETTINGS, which never changes.
	 */
	public long maxEntries() {
		return maxCapacity / ENTRY_OVERHEAD;
	}

	/**
	 * Applies {@code Set Dynamic Table Capacity}, evicting from the tail <b>within this call</b> so
	 * {@code size <= capacity} holds again before the next instruction is processed.
	 * <p>
	 * The bound check is unconditional rather than {@code CHECKS}-gated — a deliberate deviation from
	 * this package's convention, because the value is wire-derived and this is its last line of
	 * defence (spec FR-009, SI-1). Callers parsing an encoder-stream instruction should reject an
	 * out-of-range capacity with {@code QPACK_ENCODER_STREAM_ERROR} before reaching here.
	 *
	 * @return {@code false}, changing nothing, iff the reduction would have to evict a referenced
	 * entry. A decoder table has no reference counts and always returns {@code true}.
	 * @throws IllegalArgumentException if {@code newCapacity} is negative or above {@link #maxCapacity()}
	 */
	public boolean setCapacity(int newCapacity) {
		if (newCapacity < 0 || newCapacity > maxCapacity) {
			throw new IllegalArgumentException("QPACK dynamic table capacity out of range [0, " + maxCapacity + "]");
		}
		int freed = 0;
		int evictions = 0;
		while (size - freed > newCapacity) {
			Entry entry = entryAtOffset(evictions);
			if (entry.refCount > 0) return false;
			freed += entry.size;
			evictions++;
		}
		for (int i = 0; i < evictions; i++) {
			evictOldest();
		}
		capacity = newCapacity;
		return true;
	}

	/** Whether an entry of this size could ever be held at the current capacity (RFC 9204 §3.2.2). */
	public boolean fits(int entrySize) {
		return entrySize <= capacity;
	}

	/**
	 * Appends an entry, evicting from the tail as needed.
	 * <p>
	 * Takes ownership of {@code value}: callers must not mutate it afterwards, exactly as for
	 * {@link QpackStaticTable#value(int)}.
	 *
	 * @return the new entry's absolute index, or {@link #NOT_INSERTED} when the entry alone exceeds
	 * the capacity (spec FR-012) or when only referenced entries remain to evict (spec FR-013). In
	 * both cases nothing changed and {@link #insertCount()} did not advance, because nothing went on
	 * the wire — the caller emits a literal representation instead.
	 */
	public long insert(HttpHeader name, byte[] value) {
		return insert(name, value, false);
	}

	/**
	 * As {@link #insert(HttpHeader, byte[])}, carrying whether the <b>literal</b> name octets that
	 * produced this entry held an uppercase character — see
	 * {@link QpackField#QpackField(HttpHeader, byte[], boolean)} for why {@code name} cannot answer it.
	 * <p>
	 * The flag has to live in the table because an {@code Insert With Literal Name} and the field line
	 * that later references it are two different reads: without it, a name whose case RFC 9114 §4.1.1
	 * requires rejecting would be laundered clean by the round trip through the table.
	 */
	public long insert(HttpHeader name, byte[] value, boolean nameHadUppercase) {
		int entrySize = entrySize(name, value);
		if (entrySize > capacity) return NOT_INSERTED;
		int evictions = evictionPlan(entrySize);
		if (evictions < 0) return NOT_INSERTED;
		for (int i = 0; i < evictions; i++) {
			evictOldest();
		}
		return append(new Entry(name, value, entrySize, nameHadUppercase));
	}

	/**
	 * Appends a copy of an existing entry (RFC 9204 §4.3.4), so the original can age out of the
	 * eviction window while the value stays available at a fresh index.
	 *
	 * @return the copy's absolute index, or {@link #NOT_INSERTED} if {@code absoluteIndex} is not
	 * available (which a caller processing an encoder-stream instruction reports as
	 * {@code QPACK_ENCODER_STREAM_ERROR}) or if the copy does not fit
	 */
	public long duplicate(long absoluteIndex) {
		if (!isAvailable(absoluteIndex)) return NOT_INSERTED;
		Entry entry = entryAt(absoluteIndex);
		// Read the entry out before planning eviction: a Duplicate may legally evict the very entry
		// it copies, which is precisely what the instruction exists for.
		HttpHeader name = entry.name;
		byte[] value = entry.value;
		boolean nameHadUppercase = entry.nameHadUppercase;
		return insert(name, value, nameHadUppercase);
	}

	/**
	 * Whether appending a copy of the entry at {@code absoluteIndex} would evict the original — the case
	 * RFC 9204 §4.3.4 exists for: the entry sits close enough to the FIFO tail that making room for its
	 * own copy pushes it out.
	 * <p>
	 * {@code false} for an index that is not {@link #isAvailable available}, for a table under no
	 * eviction pressure, and when the eviction could not be made at all because a referenced entry is in
	 * the way — so an encoder may read it as "the entry is about to age out, and duplicating it will
	 * work".
	 */
	public boolean duplicateWouldEvict(long absoluteIndex) {
		if (!isAvailable(absoluteIndex)) return false;
		int evictions = evictionPlan(entryAt(absoluteIndex).size);
		return evictions > 0 && droppedCount() + evictions > absoluteIndex;
	}

	/** RFC 9204 §3.2.4: {@code droppedCount() <= absoluteIndex < insertCount()}. */
	public boolean isAvailable(long absoluteIndex) {
		return absoluteIndex >= droppedCount() && absoluteIndex < insertCount;
	}

	/**
	 * @throws IllegalArgumentException if the index is not {@link #isAvailable available} — that is a
	 * programmer error. A <i>wire-driven</i> miss is the codec's to detect with {@link #isAvailable}
	 * and map to {@code QPACK_DECOMPRESSION_FAILED}.
	 */
	public HttpHeader nameAt(long absoluteIndex) {
		return requireAvailable(absoluteIndex).name;
	}

	/** As {@link #nameAt}; callers must not mutate the returned array. */
	public byte[] valueAt(long absoluteIndex) {
		return requireAvailable(absoluteIndex).value;
	}

	/** As {@link #nameAt}. See {@link #insert(HttpHeader, byte[], boolean)}. */
	public boolean nameHadUppercaseAt(long absoluteIndex) {
		return requireAvailable(absoluteIndex).nameHadUppercase;
	}

	/**
	 * RFC 9204 §3.2.5 relative index against a field section's Base.
	 * <p>
	 * Pure arithmetic that never throws and <b>may return a negative value</b> — an underflowing Base
	 * is exactly the {@code QPACK_DECOMPRESSION_FAILED} case of spec FR-031, and the caller detects
	 * it with {@link #isAvailable}. {@code long} throughout, so a wire-supplied 2^62 index cannot wrap.
	 */
	public static long absoluteFromRelative(long relativeIndex, long base) {
		return base - 1 - relativeIndex;
	}

	/** RFC 9204 §3.2.6 post-base index against a field section's Base. See {@link #absoluteFromRelative}. */
	public static long absoluteFromPostBase(long postBaseIndex, long base) {
		return base + postBaseIndex;
	}

	/** RFC 9204 §3.2.5 relative index on the <b>encoder stream</b>, whose base is the Insert Count. */
	public long absoluteFromEncoderRelative(long relativeIndex) {
		return insertCount - 1 - relativeIndex;
	}

	/**
	 * The newest available entry with this exact name and value, or {@link #NOT_FOUND}.
	 * <p>
	 * <b>O(1) average in the table's size</b>, and required to stay so: the encoder asks this once per
	 * field line, and a scan over a 4 KB table per field is a measurable regression against
	 * {@link QpackStaticEncoder}'s array lookup. See {@link #hashName} for what that costs and why the
	 * key is not the {@link HttpHeader} itself.
	 *
	 * @throws IllegalStateException on a decoder table, which never queries by name
	 */
	public long findNameAndValue(HttpHeader name, byte[] value) {
		checkState(encoderRole, "name/value lookup is encoder-only");
		Long absoluteIndex = newestByNameValue.get(new NameValue(nameKey(name), value));
		return absoluteIndex != null ? absoluteIndex : NOT_FOUND;
	}

	/**
	 * The newest available entry with this name, whatever its value, or {@link #NOT_FOUND}.
	 * <p>
	 * <b>O(1) average in the table's size</b>, on the same terms as {@link #findNameAndValue}.
	 *
	 * @throws IllegalStateException on a decoder table, which never queries by name
	 */
	public long findName(HttpHeader name) {
		checkState(encoderRole, "name lookup is encoder-only");
		Long absoluteIndex = newestByName.get(nameKey(name));
		return absoluteIndex != null ? absoluteIndex : NOT_FOUND;
	}

	/**
	 * Whether another field section may be tracked. <b>An encoder must ask this before committing to
	 * any dynamic representation</b>: when it is false the section must be encoded literally, because
	 * a section that cannot be tracked cannot pin the entries it would reference.
	 * <p>
	 * The bound exists because a peer that simply never acknowledges would otherwise grow this
	 * bookkeeping without limit (SI-3).
	 *
	 * @throws IllegalStateException on a decoder table
	 */
	public boolean canTrackSection() {
		checkState(encoderRole, ENCODER_ONLY);
		return outstandingSections < maxOutstandingSections;
	}

	/**
	 * Records that a field section on {@code streamId} references these absolute indices, pinning
	 * each against eviction until the section is acknowledged, its stream is cancelled, or the
	 * connection closes. This is the <b>only</b> place a reference count is incremented, mirroring
	 * {@link #releaseSection}.
	 * <p>
	 * A section referencing nothing dynamic is deliberately <b>not</b> tracked and returns
	 * {@code true}: RFC 9204 §4.4.1 acknowledges only sections with a non-zero Required Insert Count,
	 * so tracking one would both waste memory and make a conforming peer look like it acknowledged a
	 * stream with no outstanding section.
	 *
	 * @return {@code false}, touching no reference count, when {@link #canTrackSection()} is false
	 * @throws IllegalStateException on a decoder table
	 * @throws IllegalArgumentException if an index is not {@link #isAvailable available}
	 */
	public boolean trackSection(long streamId, long requiredInsertCount, long[] absoluteIndices) {
		checkState(encoderRole, ENCODER_ONLY);
		if (requiredInsertCount == 0 && absoluteIndices.length == 0) return true;
		if (outstandingSections >= maxOutstandingSections) return false;
		Section section = new Section(streamId, requiredInsertCount, absoluteIndices.clone());
		for (long absoluteIndex : section.absoluteIndices()) {
			requireAvailable(absoluteIndex).refCount++;
		}
		outstandingByStream.computeIfAbsent(streamId, id -> new ArrayDeque<>()).addLast(section);
		outstandingSections++;
		return true;
	}

	/**
	 * RFC 9204 §4.4.1 {@code Section Acknowledgment}: releases the <b>oldest</b> outstanding section
	 * on this stream — sections are acknowledged in the order they were sent.
	 *
	 * @return that section's Required Insert Count, which the caller raises the Known Received Count
	 * to, or {@link #NO_SECTION} when the stream has no outstanding section (spec FR-030's
	 * {@code QPACK_DECODER_STREAM_ERROR})
	 * @throws IllegalStateException on a decoder table
	 */
	public long acknowledgeSection(long streamId) {
		checkState(encoderRole, ENCODER_ONLY);
		Deque<Section> sections = outstandingByStream.get(streamId);
		if (sections == null) return NO_SECTION;
		Section section = sections.pollFirst();
		if (sections.isEmpty()) outstandingByStream.remove(streamId);
		outstandingSections--;
		releaseSection(section);
		return section.requiredInsertCount();
	}

	/**
	 * RFC 9204 §4.4.2 {@code Stream Cancellation}, and the local stream-reset path: releases every
	 * outstanding section on this stream.
	 *
	 * @return how many sections were released; {@code 0} when there were none
	 * @throws IllegalStateException on a decoder table
	 */
	public int cancelStream(long streamId) {
		checkState(encoderRole, ENCODER_ONLY);
		Deque<Section> sections = outstandingByStream.remove(streamId);
		if (sections == null) return 0;
		outstandingSections -= sections.size();
		for (Section section : sections) {
			releaseSection(section);
		}
		return sections.size();
	}

	/**
	 * Connection close: releases every outstanding section on every stream.
	 *
	 * @return how many sections were released
	 * @throws IllegalStateException on a decoder table
	 */
	public int releaseAll() {
		checkState(encoderRole, ENCODER_ONLY);
		List<Section> sections = new ArrayList<>(outstandingSections);
		for (Deque<Section> streamSections : outstandingByStream.values()) {
			sections.addAll(streamSections);
		}
		outstandingByStream.clear();
		outstandingSections = 0;
		for (Section section : sections) {
			releaseSection(section);
		}
		return sections.size();
	}

	/** Field sections emitted but not yet acknowledged, cancelled or released. */
	public int outstandingSectionCount() {
		checkState(encoderRole, ENCODER_ONLY);
		return outstandingSections;
	}

	/** Outstanding references to this entry; {@code 0} for an index that is not available. */
	int referenceCountOf(long absoluteIndex) {
		return isAvailable(absoluteIndex) ? entryAt(absoluteIndex).refCount : 0;
	}

	/**
	 * The release funnel (research D-3): the <b>single</b> place a reference count is ever lowered,
	 * reached from all three terminal paths — section acknowledged, stream cancelled, connection
	 * closed. A second decrement site is the defect this shape exists to prevent, and
	 * {@code QpackDynamicTableRefCountTest.singleDecrementSiteInSource} asserts there is none.
	 * <p>
	 * Each caller removes the {@link Section} from {@code outstandingByStream} <b>before</b> calling
	 * this, which is what makes a double release structurally unreachable rather than merely
	 * defended against: a second acknowledgment finds nothing and returns {@link #NO_SECTION}.
	 * <p>
	 * A referenced entry can never be evicted and {@link #setCapacity} refuses a reduction that would
	 * evict one, so the entry is guaranteed present. The {@link IllegalStateException} can therefore
	 * only fire if a second decrement site is ever added.
	 */
	private void releaseSection(Section section) {
		for (long absoluteIndex : section.absoluteIndices()) {
			Entry entry = isAvailable(absoluteIndex) ? entryAt(absoluteIndex) : null;
			if (entry == null || entry.refCount <= 0) {
				throw new IllegalStateException("QPACK dynamic table: releasing absolute index " + absoluteIndex +
												" which holds no outstanding reference");
			}
			entry.refCount--;
		}
	}

	/**
	 * Plans an eviction without performing one: how many tail entries must go for {@code needed}
	 * bytes to fit, or {@code -1} if they cannot because a referenced entry blocks the scan.
	 * <p>
	 * The two phases are load-bearing rather than stylistic, and this is the invariant the class
	 * exists to protect: <b>eviction is atomic with the insertion that caused it</b>. The encoder's
	 * table is a mirror of the peer's decoder table, and the peer evicts only as a side effect of an
	 * insertion instruction it receives. Evicting locally and then abandoning the insertion
	 * desynchronises the two tables, and the peer's next reference then resolves to the wrong entry.
	 * <p>
	 * The scan stops at the first referenced entry rather than skipping it: the table is a FIFO, so
	 * nothing behind it is reachable for eviction either.
	 */
	private int evictionPlan(int needed) {
		int freed = 0;
		int evictions = 0;
		while (size - freed + needed > capacity) {
			if (evictions == count) return -1;
			Entry entry = entryAtOffset(evictions);
			if (entry.refCount > 0) return -1;
			freed += entry.size;
			evictions++;
		}
		return evictions;
	}

	private long append(Entry entry) {
		if (count == ring.length) grow();
		ring[(head + count) & (ring.length - 1)] = entry;
		count++;
		size += entry.size;
		long absoluteIndex = insertCount++;
		if (encoderRole) {
			NameKey name = nameKey(entry.name);
			newestByName.put(name, absoluteIndex);
			newestByNameValue.put(new NameValue(name, entry.value), absoluteIndex);
		}
		return absoluteIndex;
	}

	private void evictOldest() {
		long absoluteIndex = droppedCount();
		Entry entry = ring[head];
		ring[head] = null;
		head = (head + 1) & (ring.length - 1);
		count--;
		size -= entry.size;
		if (encoderRole) {
			// FIFO: when the evicted index is still the mapped one it was the last holder of that
			// name (or name/value), so removing is exact and never orphans a newer entry.
			NameKey name = nameKey(entry.name);
			newestByName.remove(name, absoluteIndex);
			newestByNameValue.remove(new NameValue(name, entry.value), absoluteIndex);
		}
	}

	private void grow() {
		Entry[] grown = new Entry[ring.length * 2];
		for (int i = 0; i < count; i++) {
			grown[i] = ring[(head + i) & (ring.length - 1)];
		}
		ring = grown;
		head = 0;
	}

	private Entry entryAtOffset(int offsetFromOldest) {
		return ring[(head + offsetFromOldest) & (ring.length - 1)];
	}

	private Entry entryAt(long absoluteIndex) {
		return entryAtOffset((int) (absoluteIndex - droppedCount()));
	}

	private Entry requireAvailable(long absoluteIndex) {
		if (!isAvailable(absoluteIndex)) {
			throw new IllegalArgumentException("QPACK dynamic table absolute index " + absoluteIndex +
												" is not available in [" + droppedCount() + ", " + insertCount + ")");
		}
		return entryAt(absoluteIndex);
	}

	private NameKey nameKey(HttpHeader name) {
		return new NameKey(name, hashName(name));
	}

	/**
	 * A case-insensitive <b>polynomial</b> hash over the field name's octets — the reason
	 * {@link NameKey} exists at all.
	 * <p>
	 * {@link HttpHeader#hashCode()} is the <i>sum</i> of the name's lowercased octets, which is exactly
	 * right for the open-addressed registry it was written for and unusable as a {@link HashMap} key
	 * here: the sums of same-length names occupy a couple of hundred values, so distinct names collapse
	 * onto the same bucket in the thousands, and {@link HttpHeader} is not {@link Comparable}, so
	 * {@code HashMap} cannot treeify the bucket either. The result is a linked-list walk — a linear scan
	 * reached through the back door of a weak hash, which is what the spec's O(1)-average requirement
	 * for {@link #findName} and {@link #findNameAndValue} forbids. Measured before this hash existed:
	 * 2 560 distinct names produced 83 distinct hashes and a worst bucket of 124, and {@code findName}
	 * cost ten times more at ten times the table
	 * ({@code QpackDynamicTableLookupComplexityTest.lookupCostIsFlatAsTheTableGrowsTenfold}).
	 * <p>
	 * The cost is {@code O(name length)} — bounded, short, and above all independent of the table's
	 * size, which is the property being bought. {@code | 0x20} lowercases ASCII letters, so the hash
	 * agrees with {@link HttpHeader#equals}'s case-insensitivity; it also folds a few non-letters
	 * together, which a hash is entitled to do.
	 */
	private int hashName(HttpHeader name) {
		int nameLength = name.size();
		if (nameHashScratch.length < nameLength) {
			nameHashScratch = new byte[Math.max(nameLength, nameHashScratch.length * 2)];
		}
		name.writeTo(nameHashScratch, 0);
		int hash = 1;
		for (int i = 0; i < nameLength; i++) {
			hash = 31 * hash + (nameHashScratch[i] | 0x20);
		}
		return hash;
	}

	private static final class Entry {
		private final HttpHeader name;
		private final byte[] value;
		private final int size;
		private final boolean nameHadUppercase;

		/**
		 * Unacknowledged field sections referencing this entry. Named exactly this: the single
		 * decrement site is asserted mechanically by {@code QpackDynamicTableRefCountTest}.
		 */
		private int refCount;

		private Entry(HttpHeader name, byte[] value, int size, boolean nameHadUppercase) {
			this.name = name;
			this.value = value;
			this.size = size;
			this.nameHadUppercase = nameHadUppercase;
		}
	}

	/** One emitted, not-yet-released field section and the dynamic entries it pinned. */
	private record Section(long streamId, long requiredInsertCount, long[] absoluteIndices) {}

	/**
	 * A field name as a map key, carrying the hash {@link HttpHeader#hashCode()} does not provide —
	 * see {@link #hashName}. Equality is {@link HttpHeader}'s and therefore still case-insensitive;
	 * only the hash is this record's own, so the two indices behave identically and are merely fast.
	 */
	private record NameKey(HttpHeader name, int hash) {
		@Override
		public boolean equals(Object o) {
			return this == o || o instanceof NameKey that && name.equals(that.name);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private record NameValue(NameKey name, byte[] value) {
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof NameValue that)) return false;
			return name.equals(that.name) && Arrays.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return 31 * name.hashCode() + Arrays.hashCode(value);
		}
	}
}
