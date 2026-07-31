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

package io.activej.quic.connection;

/**
 * The set of packet numbers received in one packet number space, kept as bounded, descending,
 * disjoint, non-adjacent ranges — the shape an ACK frame encodes (RFC 9000 §19.3.1).
 * <p>
 * Invariants, all restored on every {@link #add(long)}:
 * <ul>
 *   <li>ranges are disjoint and non-adjacent — an insert that bridges two ranges merges them;</li>
 *   <li>ranges are ordered largest-first, matching ACK frame encoding order;</li>
 *   <li>the range count never exceeds {@code maxRanges} — at the bound the <b>smallest</b> range is
 *       dropped (FR-015). The largest is never dropped: it is the one the peer most needs
 *       acknowledged.</li>
 * </ul>
 * <p>
 * Ranges are stored rather than individual numbers so that memory is bounded by the range count
 * (DI-8), not by traffic volume.
 * <p>
 * Not thread-safe: the owning connection provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-13.2">RFC 9000 §13.2 — Generating Acknowledgements</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.3">RFC 9000 §19.3 — ACK Frames</a>
 */
public final class AckRanges {
	/** Returned by {@link #largest()} when nothing has been received. */
	public static final long NONE = -1;

	private final int maxRanges;

	/** Inclusive range bounds, index 0 holding the largest range. {@code starts[i] <= ends[i]}. */
	private long[] starts;
	private long[] ends;
	private int count;

	public AckRanges(int maxRanges) {
		if (maxRanges < 1) throw new IllegalArgumentException("maxRanges must be at least 1, got " + maxRanges);
		this.maxRanges = maxRanges;
		int initial = Math.min(maxRanges, 8);
		this.starts = new long[initial];
		this.ends = new long[initial];
	}

	/**
	 * Records a received packet number, restoring every invariant.
	 * <p>
	 * Inserting a number already covered is a no-op, so this is safe to call before duplicate
	 * detection has run.
	 */
	public void add(long packetNumber) {
		// Descending scan for the first range whose start is at or below packetNumber. At most
		// maxRanges (default 32) entries, so a linear scan beats a binary search and keeps the
		// merge cases legible.
		int i = 0;
		while (i < count && starts[i] > packetNumber) {
			i++;
		}

		// Range i (if any) lies at or below packetNumber; range i-1 (if any) lies strictly above it.
		if (i < count && packetNumber <= ends[i]) {
			return; // already covered — a duplicate insert is a no-op
		}

		boolean touchesBelow = i < count && ends[i] + 1 == packetNumber;
		boolean touchesAbove = i > 0 && starts[i - 1] - 1 == packetNumber;

		if (touchesBelow && touchesAbove) {
			// packetNumber is the one value bridging two ranges: collapse them into one.
			starts[i - 1] = starts[i];
			removeAt(i);
		} else if (touchesBelow) {
			ends[i] = packetNumber;
		} else if (touchesAbove) {
			starts[i - 1] = packetNumber;
		} else {
			insertAt(i, packetNumber);
			if (count > maxRanges) {
				// FR-015: drop the smallest range, never the largest.
				count--;
			}
		}
	}

	private void insertAt(int index, long packetNumber) {
		if (count == starts.length) grow();
		System.arraycopy(starts, index, starts, index + 1, count - index);
		System.arraycopy(ends, index, ends, index + 1, count - index);
		starts[index] = packetNumber;
		ends[index] = packetNumber;
		count++;
	}

	private void removeAt(int index) {
		System.arraycopy(starts, index + 1, starts, index, count - index - 1);
		System.arraycopy(ends, index + 1, ends, index, count - index - 1);
		count--;
	}

	private void grow() {
		int newLength = Math.min(Math.max(starts.length * 2, 4), maxRanges + 1);
		long[] newStarts = new long[newLength];
		long[] newEnds = new long[newLength];
		System.arraycopy(starts, 0, newStarts, 0, count);
		System.arraycopy(ends, 0, newEnds, 0, count);
		starts = newStarts;
		ends = newEnds;
	}

	/** Whether this packet number has already been recorded — the duplicate check of FR-010. */
	public boolean contains(long packetNumber) {
		for (int i = 0; i < count; i++) {
			if (packetNumber > ends[i]) return false; // descending: no later range can contain it
			if (packetNumber >= starts[i]) return true;
		}
		return false;
	}

	/** The highest packet number recorded, or {@link #NONE} when empty. Maps to the ACK frame's Largest Acknowledged. */
	public long largest() {
		return count == 0 ? NONE : ends[0];
	}

	/**
	 * The ACK frame's First ACK Range field: the number of packets below Largest Acknowledged that
	 * are <b>additionally</b> covered by the first range (RFC 9000 §19.3.1). A single-packet range
	 * therefore yields 0, not 1.
	 */
	public long firstRangeLength() {
		if (count == 0) throw new IllegalStateException("No ranges recorded");
		return ends[0] - starts[0];
	}

	public int rangeCount() {
		return count;
	}

	public boolean isEmpty() {
		return count == 0;
	}

	/**
	 * The ACK frame's Gap fields (RFC 9000 §19.3.1): for each range after the first, the number of
	 * contiguous unacknowledged packets preceding it, minus one. Ranges are non-adjacent by
	 * construction, so the encoding subtracts two.
	 */
	public long[] gaps() {
		long[] gaps = new long[Math.max(0, count - 1)];
		for (int i = 0; i + 1 < count; i++) {
			gaps[i] = starts[i] - ends[i + 1] - 2;
		}
		return gaps;
	}

	/**
	 * The ACK frame's ACK Range Length fields (RFC 9000 §19.3.1): for each range after the first,
	 * the number of packets it covers, minus one.
	 */
	public long[] rangeLengths() {
		long[] lengths = new long[Math.max(0, count - 1)];
		for (int i = 1; i < count; i++) {
			lengths[i - 1] = ends[i] - starts[i];
		}
		return lengths;
	}

	/**
	 * Drops ranges the peer has confirmed receiving our ACK for — an ACK of an ACK (RFC 9000 §13.2.4).
	 * Every range entirely at or below {@code largestAckedByPeer} is discarded. Idempotent.
	 */
	public void pruneBelow(long largestAckedByPeer) {
		int kept = count;
		while (kept > 0 && ends[kept - 1] <= largestAckedByPeer) {
			kept--;
		}
		count = kept;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) sb.append(", ");
			if (starts[i] == ends[i]) sb.append(ends[i]);
			else sb.append(starts[i]).append('-').append(ends[i]);
		}
		return sb.append(']').toString();
	}
}
