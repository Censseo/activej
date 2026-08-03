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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the STREAM frames of one receiving part — which carry explicit offsets and may overlap,
 * arrive out of order, or duplicate — into the ordered, de-duplicated byte sequence the application
 * reads (RFC 9000 §2.2). One instance per receiving part.
 * <p>
 * <b>Sibling, not a shared abstraction</b>: this mirrors
 * {@link io.activej.quic.connection.CryptoStreamAssembler} deliberately and visibly — same
 * {@code TreeMap<Long, ByteBuf>} shape, same bounds-check-before-state-touch ordering, same
 * "owns its input on every path" contract — so that a reader of one recognises the other. It is
 * <b>not</b> the same class, and generalising the two was rejected (research R-04) because they
 * differ in the three things that matter:
 * <ul>
 *   <li><b>Delivery</b>: {@code CryptoStreamAssembler} <i>concatenates</i> a contiguous run into one
 *       freshly allocated buffer. This class <b>never copies a byte</b> (FR-014): a contiguous range
 *       moves to the ready queue as a retained slice of the frame it arrived in, which is also what a
 *       {@code ChannelSupplier} wants to hand out one item at a time.</li>
 *   <li><b>Termination</b>: a CRYPTO stream simply ends when its encryption level is discarded. A
 *       stream has a final size, a {@code FIN} and {@code FINAL_SIZE_ERROR} — all owned by the
 *       receiving part above, not by this class.</li>
 *   <li><b>Bounds</b>: CRYPTO bounds <i>bytes</i> and answers with {@code CRYPTO_BUFFER_EXCEEDED};
 *       here bytes are bounded by flow control instead, and the bound is on the number of
 *       discontiguous <i>ranges</i> — plus, as defence in depth, on the number of buffered
 *       <i>pieces</i> — both answered with {@code INTERNAL_ERROR} (FR-011, clarification Q1).</li>
 * </ul>
 *
 * <h2>What a "range" is, and why it is not a buffered piece (FR-011, clarification Q1)</h2>
 * The bound counts <b>gaps</b>, exactly as {@link io.activej.quic.connection.AckRanges} — its named
 * sibling in clarification Q1 — counts genuinely separate runs of packet numbers. Two buffered
 * pieces whose byte ranges touch ({@code end of one == start of the next}) are <b>one</b> range,
 * even though they remain two {@link ByteBuf}s: merging them into one buffer would mean copying,
 * which FR-014 forbids, so only the <i>counting</i> is adjacency-aware.
 * <p>
 * The distinction is the whole point of the bound. Counting pieces would make it scale with the
 * bandwidth-delay product — one lost frame followed by a window of in-order frames is <i>one</i>
 * gap but arbitrarily many pieces — so an ordinary lossy path would trip a defence meant for a peer
 * that fragments pathologically. Counting gaps makes the bound scale with exactly what it is
 * defending against.
 * <p>
 * Buffered <i>pieces</i> are therefore not bounded by {@code maxRanges}. Their primary bound is flow
 * control, which caps buffered bytes at the stream window this endpoint advertised, and no piece is
 * empty.
 *
 * <h2>Why bytes are not the whole story, and the second bound (defence in depth)</h2>
 * Flow control bounds the <i>payload</i>, not what it costs to track. A peer that withholds the very
 * first byte of a stream and then sends every later byte as its own single-byte {@code STREAM} frame
 * keeps {@code rangeCount} at <b>one</b> forever — one gap, one range — while every byte buys itself a
 * {@link TreeMap} entry, a boxed {@link Long} key and a {@link ByteBuf} header. That is roughly two
 * orders of magnitude of JVM heap per byte of nominal window, on every stream at once, and no range
 * bound can see it because the peer never opens a second gap.
 * <p>
 * So the piece count is bounded too, at {@link #PIECES_PER_RANGE} × {@code maxRanges}, with the same
 * {@code INTERNAL_ERROR} the range bound raises — it is the same category of purely local resource
 * bound, so clarification Q1's reasoning carries over unchanged. The two bounds are independent and
 * neither subsumes the other: the range bound catches a peer opening gap after gap, this one catches a
 * peer fragmenting inside a single gap. Deriving it from {@code maxReceiveRangesPerStream} rather than
 * adding a tenth setting keeps one knob for "how much fragmentation this endpoint tolerates", and
 * raising that knob for a genuinely awful path raises both bounds together.
 *
 * <h2>Overlaps</h2>
 * The RFCs differ too: RFC 9000 §19.6 <i>requires</i> a receiver to detect CRYPTO data that
 * contradicts what it already holds, whereas §2.2 only <i>permits</i> that check for STREAM data.
 * This class takes the permission: where ranges overlap, the copy received first wins and the later
 * bytes are discarded unread.
 * <p>
 * <b>Ownership (DI-1, FR-014)</b>: {@link #add} takes ownership of its buffer on <b>every</b> path —
 * delivery, buffering, duplicate discard, and every throw. Buffers it retains are slices of the
 * caller's frame data, never copies, and are released by {@link #poll} handing them on or by
 * {@link #close}. A buffer returned by {@link #poll} is the caller's to recycle.
 * <p>
 * Pure: no {@link io.activej.reactor.Reactor}, no {@code Promise}, no timers. Not thread-safe — the
 * owning receiving part provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.2">RFC 9000 §2.2 — Sending and Receiving Data</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class StreamReassembler {
	/** RFC 9000 §19.8: the sum of a STREAM frame's Offset and Length must not exceed 2^62 - 1. */
	public static final long MAX_OFFSET = (1L << 62) - 1;

	/**
	 * Buffered pieces tolerated per permitted range — the multiplier that turns
	 * {@code maxReceiveRangesPerStream} into this class's second bound.
	 * <p>
	 * A documented constant rather than a setting of its own, following {@code WINDOW_UPDATE_FRACTION}'s
	 * precedent in this package: it is a ratio, not a capacity, and the capacity it scales is already
	 * configurable.
	 * <p>
	 * 64 is chosen to sit an order of magnitude above anything a conforming peer produces and two below
	 * what an adversarial one would. A full 256 KiB stream window behind one gap, carried in MTU-sized
	 * frames, is around 220 pieces — and that is the worst an ordinary lossy or reordering path reaches,
	 * because the pieces behind a gap are bounded by the frames in flight, not by the bytes. The default
	 * bound is 32 × 64 = <b>2048</b> pieces per receiving part, comfortably clear of that, while cutting
	 * the worst case from "one piece per byte of the window" (262 144) by two orders of magnitude.
	 */
	public static final int PIECES_PER_RANGE = 64;

	private final int maxRanges;

	/** {@code maxRanges} × {@link #PIECES_PER_RANGE}, clamped — see the class Javadoc. */
	private final int maxPieces;

	/**
	 * Scratch space for the novel sub-ranges of one arriving frame, as {@code [start, end)} pairs.
	 * A frame can be split by at most every buffered <i>piece</i> it overlaps, and pieces are bounded by
	 * {@code maxPieces} rather than by {@code maxRanges} (adjacent ones count as one range), so this
	 * grows on demand rather than being sized up front. Reused across calls; never escapes.
	 */
	private long[] gaps;

	/** First offset not yet made contiguous — every byte below it has been moved to {@link #ready}. */
	private long readOffset;

	/** Out-of-order pieces, keyed by absolute offset; strictly increasing and non-overlapping. */
	private final TreeMap<Long, ByteBuf> pending = new TreeMap<>();

	/**
	 * Maximal runs of mutually adjacent {@link #pending} pieces — the discontiguous ranges the bound
	 * is on (FR-011). Maintained incrementally by {@link #putPiece} and {@link #drain}, never
	 * recomputed by a scan: a scan would be linear in the piece count, which a peer controls.
	 */
	private int rangeCount;

	/** Contiguous slices awaiting the reader, in stream order. */
	private final ArrayDeque<ByteBuf> ready = new ArrayDeque<>();

	private long readyBytes;
	private boolean closed;

	/**
	 * @param maxRanges the bound on discontiguous buffered ranges — {@code maxReceiveRangesPerStream}
	 *                  (FR-011). Supplied by the caller: this class performs no
	 *                  {@code ApplicationSettings} lookup of its own. It also scales the buffered-piece
	 *                  bound, at {@link #PIECES_PER_RANGE} pieces per range
	 */
	public StreamReassembler(int maxRanges) {
		if (maxRanges < 1) {
			throw new IllegalArgumentException("maxRanges must be positive, got " + maxRanges);
		}
		this.maxRanges = maxRanges;
		// Clamped rather than left to overflow: maxRanges is validated as positive, not as small.
		this.maxPieces = (int) Math.min(Integer.MAX_VALUE, (long) maxRanges * PIECES_PER_RANGE);
		this.gaps = new long[2 * (maxRanges + 1)];
	}

	/**
	 * Whether any buffered piece intersects {@code [offset, end)} or touches it end-to-end.
	 * <p>
	 * This is the whole of the bound check's hard part: inserting a frame's novel sub-ranges leaves
	 * {@code [offset, end)} entirely covered, so every range it meets — including the ones it merely
	 * abuts — collapses with it into <b>one</b> range. The count after the insert is therefore
	 * {@code rangeCount - touched + 1}, which can exceed {@code maxRanges} only when
	 * {@code touched == 0} and the count is already at the bound. So the count of what it touches is
	 * never needed, only whether it touches anything at all — two {@code TreeMap} lookups rather than
	 * a walk over pieces a peer controls the number of.
	 */
	private boolean touchesBufferedRange(long offset, long end) {
		Map.Entry<Long, ByteBuf> below = pending.floorEntry(offset);
		if (below != null && below.getKey() + below.getValue().readRemaining() >= offset) return true;
		Long above = pending.ceilingKey(offset);
		return above != null && above <= end;
	}

	/**
	 * Whether buffering {@code [offset, end)} would push the discontiguous range count past
	 * {@code maxRanges}. {@code offset} has already been trimmed to the read offset.
	 * <p>
	 * Three things make this the whole check:
	 * <ul>
	 *   <li>a range starting at the read offset is delivered rather than buffered, and by construction
	 *       so is every later gap of the same frame, so it can only lower the count;</li>
	 *   <li>otherwise the frame's novel sub-ranges leave {@code [offset, end)} entirely covered, so it
	 *       and every range it meets become one, giving {@code rangeCount - touched + 1};</li>
	 *   <li>that exceeds {@code maxRanges} only when it touches nothing and the count is already at the
	 *       bound.</li>
	 * </ul>
	 */
	private boolean exceedsRangeBound(long offset, long end) {
		return offset != readOffset && rangeCount >= maxRanges && !touchesBufferedRange(offset, end);
	}

	/**
	 * {@link #exceedsRangeBound} as the owning {@link ReceivePart} needs it: an answer <b>before</b> the
	 * frame's bytes are charged to flow control, so that a frame the bound will refuse changes no state
	 * at all (SI-4, CHK080). {@link #add} repeats the check because it is reachable on its own.
	 *
	 * @param offset the frame's Offset field; the caller has already checked that
	 *               {@code offset + length} is within 2^62-1
	 */
	public boolean wouldExceedRangeBound(long offset, int length) {
		if (closed || length == 0) return false;
		long end = offset + length;
		if (end <= readOffset) return false;
		return exceedsRangeBound(Math.max(offset, readOffset), end);
	}

	/**
	 * Whether buffering this frame's novel sub-ranges would push the buffered <i>piece</i> count past
	 * {@code maxPieces} — the second, independent bound described in this class's Javadoc.
	 * <p>
	 * Exact rather than approximate, which is what the two arguments buy: a frame starting at the read
	 * offset is delivered rather than buffered and can only lower the count, and any other frame leaves
	 * the first buffered piece above the read offset, so nothing drains and the count rises by exactly
	 * the number of gaps it fills.
	 *
	 * @param offset   the frame's novel range's start, already trimmed to the read offset
	 * @param gapCount how many pieces buffering it would add, from {@link #collectGaps}
	 */
	private boolean exceedsPieceBound(long offset, int gapCount) {
		return offset != readOffset && pending.size() + gapCount > maxPieces;
	}

	/**
	 * {@link #exceedsPieceBound} as the owning {@link ReceivePart} needs it — the piece-bound twin of
	 * {@link #wouldExceedRangeBound}, asked before the frame's bytes are charged to flow control so that
	 * a refused frame changes no state at all (SI-4, CHK080). {@link #add} repeats the check because it
	 * is reachable on its own.
	 *
	 * @param offset the frame's Offset field; the caller has already checked that
	 *               {@code offset + length} is within 2^62-1
	 */
	public boolean wouldExceedPieceBound(long offset, int length) {
		if (closed || length == 0) return false;
		long end = offset + length;
		if (end <= readOffset) return false;
		long from = Math.max(offset, readOffset);
		// collectGaps only touches the scratch array, so asking is free of consequence; the early return
		// keeps the ordinary in-order arrival, which cannot grow the buffer at all, from paying for it.
		return from != readOffset && exceedsPieceBound(from, collectGaps(from, end));
	}

	/**
	 * Buffers one novel piece — which must be disjoint from every piece already held — keeping
	 * {@link #rangeCount} exact.
	 * <p>
	 * The same three cases as {@link io.activej.quic.connection.AckRanges#add}: a piece that bridges
	 * two runs merges them, one that extends a run on either side leaves the count alone, and one
	 * that touches nothing is a new range.
	 */
	private void putPiece(long from, ByteBuf piece) {
		long to = from + piece.readRemaining();
		Map.Entry<Long, ByteBuf> below = pending.lowerEntry(from);
		boolean touchesBelow = below != null && below.getKey() + below.getValue().readRemaining() == from;
		boolean touchesAbove = pending.containsKey(to);
		pending.put(from, piece);
		rangeCount += 1 - (touchesBelow ? 1 : 0) - (touchesAbove ? 1 : 0);
	}

	/**
	 * Accepts one STREAM frame's data (RFC 9000 §19.8). Whatever becomes contiguous is appended to the
	 * ready queue and can be taken with {@link #poll}; the rest is buffered until its gap closes.
	 * <p>
	 * Bytes at or below {@link #readOffset()}, and bytes already buffered, are discarded — whole
	 * duplicates and partial overlaps alike (FR-009). A partially novel range is <b>sliced</b>, never
	 * copied: only the novel pieces are retained (FR-014).
	 *
	 * @param offset the frame's Offset field — the absolute stream offset of the first byte
	 * @param data   the frame's payload; owned by this method on every path, including every throw
	 * @throws QuicTransportException {@code FRAME_ENCODING_ERROR} when the offset range would exceed
	 *                                2^62-1 (RFC 9000 §19.8); {@code INTERNAL_ERROR} when the frame
	 *                                would push the number of discontiguous buffered ranges past
	 *                                {@code maxRanges} (FR-011, clarification Q1), or the number of
	 *                                buffered pieces past {@code maxRanges × }{@link #PIECES_PER_RANGE}
	 *                                — in neither case is the frame silently dropped, because its bytes
	 *                                are already counted against flow control and the peer will never
	 *                                resend them
	 */
	public void add(long offset, ByteBuf data) throws QuicTransportException {
		// Bounds before anything else (SI-4): reject before touching state. Unconditional, never
		// behind Checks - these values come off the wire.
		int length = data.readRemaining();
		if (offset < 0 || offset > MAX_OFFSET - length) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"STREAM frame offset range exceeds 2^62-1");
		}

		if (closed) {
			// The stream was reset or the connection closed while this datagram was in flight - normal.
			data.recycle();
			return;
		}

		if (length == 0) {
			// A zero-length STREAM frame is legal (it may carry only FIN) and has no effect here.
			data.recycle();
			return;
		}

		long end = offset + length;

		if (end <= readOffset) {
			// Entirely already delivered: a silent duplicate discard (RFC 9000 §2.2).
			data.recycle();
			return;
		}

		if (offset < readOffset) {
			// Straddles the read offset. The delivered prefix is gone, so it cannot be compared - §2.2
			// permits discarding already-received data unchecked. Trimming is a head move, not a copy.
			data.moveHead((int) (readOffset - offset));
			offset = readOffset;
		}

		// The range bound, before a single byte of this frame is retained and before the gap scratch is
		// touched (SI-4, CHK080). ReceivePart asks the same question one layer up, ahead of its own
		// accounting; this is the copy that makes the class safe to use on its own.
		if (exceedsRangeBound(offset, end)) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"discontiguous buffered stream ranges would exceed maxReceiveRangesPerStream=" + maxRanges);
		}

		int gapCount = collectGaps(offset, end);
		if (gapCount == 0) {
			// Every byte is already buffered: discard, keeping the copy received first.
			data.recycle();
			return;
		}

		// The piece bound, second and independent of the range bound above: a peer that fragments inside
		// one gap never raises the range count, so nothing above would catch it. Same INTERNAL_ERROR, same
		// reason — a purely local resource bound RFC 9000 §20.1 assigns no code to.
		if (exceedsPieceBound(offset, gapCount)) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"buffered stream pieces would exceed " + maxPieces +
				" (maxReceiveRangesPerStream=" + maxRanges + " x " + PIECES_PER_RANGE + " pieces per range)");
		}

		if (gapCount == 1 && gaps[0] == offset && gaps[1] == end) {
			// Nothing to trim - retain the buffer itself rather than a slice of it. The common case.
			putPiece(offset, data);
		} else {
			int head = data.head();
			for (int i = 0; i < gapCount; i++) {
				long from = gaps[2 * i];
				long to = gaps[2 * i + 1];
				putPiece(from, data.slice(head + (int) (from - offset), (int) (to - from)));
			}
			data.recycle();
		}

		drain();
	}

	/**
	 * Collects the sub-ranges of {@code [offset, end)} that are not already buffered, into
	 * {@link #gaps}. Reads only; mutates no state but the scratch array.
	 *
	 * @return the number of {@code [start, end)} pairs written
	 */
	private int collectGaps(long offset, long end) {
		int n = 0;
		long cursor = offset;
		Long floorKey = pending.floorKey(offset);
		long from = floorKey != null ? floorKey : offset;
		for (Map.Entry<Long, ByteBuf> entry : pending.subMap(from, true, end, false).entrySet()) {
			long rangeOffset = entry.getKey();
			long rangeEnd = rangeOffset + entry.getValue().readRemaining();
			if (rangeEnd <= cursor) continue;
			if (rangeOffset > cursor) {
				n = appendGap(n, cursor, rangeOffset);
			}
			if (rangeEnd >= end) return n;
			cursor = rangeEnd;
		}
		if (cursor < end) {
			n = appendGap(n, cursor, end);
		}
		return n;
	}

	/** Appends one {@code [from, to)} pair to {@link #gaps}, growing it if this frame is that split. */
	private int appendGap(int n, long from, long to) {
		if (2 * n + 2 > gaps.length) {
			gaps = Arrays.copyOf(gaps, gaps.length * 2);
		}
		gaps[2 * n] = from;
		gaps[2 * n + 1] = to;
		return n + 1;
	}

	/** Moves every piece that is now contiguous with the read offset to the ready queue, intact. */
	private void drain() {
		while (!pending.isEmpty()) {
			Map.Entry<Long, ByteBuf> first = pending.firstEntry();
			if (first.getKey() != readOffset) break;
			pending.pollFirstEntry();
			ByteBuf buf = first.getValue();
			int size = buf.readRemaining();
			readOffset += size;
			readyBytes += size;
			ready.addLast(buf);
			// The piece that just left ended its range unless the next one continues it; when it does,
			// the range survives with one piece fewer and the count must not move.
			if (pending.isEmpty() || pending.firstKey() != readOffset) {
				rangeCount--;
			}
		}
	}

	/**
	 * Takes the next contiguous slice, in stream order (FR-008). The returned buffer is the
	 * <b>caller's</b> to recycle, and is a slice of the frame data it arrived in — never a copy
	 * (FR-014).
	 *
	 * @return the next slice, or {@code null} when nothing is contiguous yet (or after
	 * {@link #close})
	 */
	public @Nullable ByteBuf poll() {
		ByteBuf buf = ready.pollFirst();
		if (buf != null) {
			readyBytes -= buf.readRemaining();
		}
		return buf;
	}

	/** Whether {@link #poll} would return a buffer. */
	public boolean hasReady() {
		return !ready.isEmpty();
	}

	/** Contiguous bytes waiting for the reader — what {@link #poll} would hand out in total. */
	public long readyBytes() {
		return readyBytes;
	}

	/**
	 * The first offset not yet made contiguous: every byte below it has been moved to the ready queue,
	 * though the reader may not have taken it yet. Never decreases.
	 */
	public long readOffset() {
		return readOffset;
	}

	/**
	 * Discontiguous buffered ranges currently held, bounded by {@code maxRanges} (FR-011).
	 * <p>
	 * A run of mutually adjacent pieces is <b>one</b> range, however many pieces it is made of — see
	 * this class's Javadoc for why the bound is on gaps rather than on pieces. Use
	 * {@link #bufferedPieces()} for the piece count.
	 */
	public int pendingRanges() {
		return rangeCount;
	}

	/**
	 * Buffers currently held out of order. At least {@link #pendingRanges()}, and equal to it only when
	 * no two of them are adjacent.
	 * <p>
	 * Bounded twice over, by neither of the same things as the range count: by flow control, since no
	 * piece is empty and buffered bytes cannot pass the advertised stream window, and by
	 * {@code maxRanges × }{@link #PIECES_PER_RANGE}, which is what a peer fragmenting inside one gap
	 * meets long before it meets the window.
	 */
	public int bufferedPieces() {
		return pending.size();
	}

	/** The buffered-piece bound in force — {@code maxRanges × }{@link #PIECES_PER_RANGE}. */
	public int maxBufferedPieces() {
		return maxPieces;
	}

	/**
	 * Discards everything held, recycling it (FR-014). Called when the stream is reset, when the
	 * application abandons the receiving part, or when the connection closes. Idempotent (WI-9);
	 * afterwards {@link #add} recycles its input and does nothing, and {@link #poll} returns
	 * {@code null}.
	 */
	public void close() {
		for (ByteBuf buf : pending.values()) {
			buf.recycle();
		}
		pending.clear();
		rangeCount = 0;
		for (ByteBuf buf : ready) {
			buf.recycle();
		}
		ready.clear();
		readyBytes = 0;
		closed = true;
	}

	/**
	 * Whether {@link #close()} has released everything buffered. A closed reassembler accepts nothing
	 * more and holds no buffer, which is what makes closing it idempotent (WI-9, DI-1).
	 */
	public boolean isClosed() {
		return closed;
	}

	/** Never prints buffered bytes: they are application data (SI-6). */
	@Override
	public String toString() {
		return "StreamReassembler{" +
			"readOffset=" + readOffset +
			", readyBytes=" + readyBytes +
			", pendingRanges=" + rangeCount + '/' + maxRanges +
			", bufferedPieces=" + pending.size() + '/' + maxPieces +
			(closed ? ", closed" : "") +
			'}';
	}
}
