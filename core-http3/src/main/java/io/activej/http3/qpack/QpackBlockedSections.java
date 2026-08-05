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
import io.activej.http3.Http3Errors;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.common.Checks.checkArgument;
import static java.util.Comparator.comparingLong;

/**
 * The field sections {@link QpackDynamicDecoder#decodeOrBlock} handed back as {@link
 * QpackDynamicDecoder.Blocked} — received whole, not decodable yet, held until the peer's encoder
 * stream raises the Insert Count to their Required Insert Count (RFC 9204 §2.1.2, FR-033).
 * <p>
 * Synchronous and non-reactive like the rest of this package (ADR-016): the arrival instant is a
 * parameter rather than a clock read, so the timeout is a bound this class states and the connection's
 * {@code Reactor} is what schedules the check (FR-036).
 *
 * <h4>Three bounds, all three at connection scope</h4>
 * A blocked section is memory a peer chose to make this side hold, so it is bounded by <b>count</b>
 * (the advertised {@code SETTINGS_QPACK_BLOCKED_STREAMS}), by <b>bytes</b> ({@code count ×
 * maxFieldSectionSize}, derived rather than configured) and by <b>time</b>. Each closes the connection
 * with {@code QPACK_DECOMPRESSION_FAILED} (research D-4): a decoder that silently dropped a section
 * instead would leave the two tables disagreeing about what was decoded. The byte bound is stated
 * separately <i>because</i> the count bound is not a memory bound — one blocked stream may hold several
 * sections, so streams and bytes grow independently.
 *
 * <h4>Not the encoder's outstanding-section bound</h4>
 * This bounds what <b>this decoder holds</b> awaiting the peer's insertions.
 * {@code Http3Connection}'s {@code maxOutstandingSections} — {@code 2 × maxConcurrentRequestStreams} —
 * bounds what <b>this endpoint's encoder has emitted and not had acknowledged</b>, which pins entries
 * against eviction in the other direction's table. Two tables, two directions, two bounds: they compose,
 * neither supersedes the other, and reading the two names as one bound would double-count both.
 *
 * <h4>One section per stream, in practice</h4>
 * {@code Http3RequestStream} decodes one field section at a time and does not read the frame behind a
 * held one, so at most one section per stream is ever held here. The per-stream deque is what makes
 * FR-037 hold anyway if that ever stops being true — an ordering invariant expressed where it cannot be
 * forgotten rather than inferred from a caller's read loop.
 *
 * <h4>{@code ByteBuf} ownership</h4>
 * {@link #hold} takes its section on every path, a throw included. {@link #release} hands ownership
 * back to the caller. {@link #discard} and {@link #recycle} are the two release funnels, and
 * {@link #recycle} is the only one that releases everything — the bound failures deliberately do not,
 * so that closing the connection stays the single place held memory goes back (FR-035, research D-3).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-2.1.2">RFC 9204 §2.1.2 — Blocked
 * Streams</a>
 */
public final class QpackBlockedSections {
	private static final boolean CHECKS = Checks.isEnabled(QpackBlockedSections.class);

	/** {@link #earliestDeadlineMillis()} when nothing is held, or when the timeout is disabled. */
	public static final long NO_DEADLINE = -1;

	private final int maxBlockedStreams;
	private final long maxHeldBytes;
	private final long timeoutMillis;

	private final Map<Long, Deque<HeldSection>> byStream = new LinkedHashMap<>();
	private long heldBytes;
	private long arrivals;

	/**
	 * @param maxBlockedStreams   the locally advertised {@code SETTINGS_QPACK_BLOCKED_STREAMS}; 0 holds
	 *                            nothing at all
	 * @param maxFieldSectionSize the RFC 9114 §4.2.2 bound one section is already held to, which is what
	 *                            makes {@code maxBlockedStreams × maxFieldSectionSize} the byte bound
	 * @param timeoutMillis       how long one section may stay blocked; 0 disables the bound
	 */
	public QpackBlockedSections(int maxBlockedStreams, long maxFieldSectionSize, long timeoutMillis) {
		if (CHECKS) checkArgument(maxBlockedStreams >= 0, "maxBlockedStreams must not be negative");
		if (CHECKS) checkArgument(maxFieldSectionSize >= 0, "maxFieldSectionSize must not be negative");
		if (CHECKS) checkArgument(timeoutMillis >= 0, "timeoutMillis must not be negative; 0 disables it");
		this.maxBlockedStreams = maxBlockedStreams;
		this.maxHeldBytes = saturatingProduct(maxBlockedStreams, maxFieldSectionSize);
		this.timeoutMillis = timeoutMillis;
	}

	/**
	 * One held section.
	 *
	 * @param arrivalOrder a connection-wide sequence number, so {@link #release} can surface sections in
	 *                     the order they arrived rather than the order they became decodable (FR-037)
	 */
	public record HeldSection(
		long streamId, long requiredInsertCount, ByteBuf section, long arrivalMillis, long arrivalOrder
	) {}

	/**
	 * Holds a section {@link QpackDynamicDecoder#decodeOrBlock} could not decode yet.
	 *
	 * @throws QpackException at connection scope when the count or the byte bound would be exceeded;
	 *                        {@code section} is recycled before it is raised
	 */
	public void hold(long streamId, long requiredInsertCount, ByteBuf section, long arrivalMillis)
		throws QpackException {
		int bytes = section.readRemaining();
		Deque<HeldSection> queue = byStream.get(streamId);
		if (queue == null && byStream.size() >= maxBlockedStreams) {
			section.recycle();
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"more blocked streams than the advertised SETTINGS_QPACK_BLOCKED_STREAMS of " + maxBlockedStreams);
		}
		if (heldBytes + bytes > maxHeldBytes) {
			section.recycle();
			throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"blocked field sections above the " + maxHeldBytes + " bytes they may be held in");
		}
		if (queue == null) {
			queue = new ArrayDeque<>();
			byStream.put(streamId, queue);
		}
		queue.addLast(new HeldSection(streamId, requiredInsertCount, section, arrivalMillis, arrivals++));
		heldBytes += bytes;
	}

	/**
	 * The sections {@code insertCount} has made decodable, in <b>arrival</b> order, removed from here and
	 * owned by the caller.
	 * <p>
	 * A stream is drained only from its head: a later section of the same stream stays blocked behind an
	 * earlier one even once its own Required Insert Count is satisfied, which is FR-037's ordering
	 * invariant expressed where it cannot be forgotten.
	 */
	public List<HeldSection> release(long insertCount) {
		List<HeldSection> released = null;
		for (Iterator<Map.Entry<Long, Deque<HeldSection>>> it = byStream.entrySet().iterator(); it.hasNext(); ) {
			Deque<HeldSection> queue = it.next().getValue();
			while (!queue.isEmpty() && queue.peekFirst().requiredInsertCount() <= insertCount) {
				HeldSection held = queue.pollFirst();
				heldBytes -= held.section().readRemaining();
				if (released == null) released = new ArrayList<>();
				released.add(held);
			}
			if (queue.isEmpty()) it.remove();
		}
		if (released == null) return List.of();
		released.sort(comparingLong(HeldSection::arrivalOrder));
		return released;
	}

	/**
	 * Releases everything held for one stream, which is what a reset or abandoned request stream leaves
	 * behind (FR-025, FR-035).
	 *
	 * @return how many sections were discarded — non-zero is what obliges the caller to emit a
	 * {@code Stream Cancellation}
	 */
	public int discard(long streamId) {
		Deque<HeldSection> queue = byStream.remove(streamId);
		if (queue == null) return 0;
		int discarded = 0;
		for (HeldSection held : queue) {
			heldBytes -= held.section().readRemaining();
			held.section().recycle();
			discarded++;
		}
		return discarded;
	}

	/**
	 * FR-036: raises the connection error the moment the oldest held section has been blocked for
	 * {@link #timeoutMillis()}. Holds nothing back — {@link #recycle} is what releases the sections, so
	 * that the close path this throw drives is the one place they go back.
	 */
	public void checkTimeout(long nowMillis) throws QpackException {
		if (timeoutMillis == 0) return;
		HeldSection oldest = oldest();
		if (oldest == null || nowMillis - oldest.arrivalMillis() < timeoutMillis) return;
		throw QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			"a field section blocked for longer than the " + timeoutMillis + " ms it may be held for");
	}

	/** When {@link #checkTimeout} would next fire, or {@link #NO_DEADLINE}. */
	public long earliestDeadlineMillis() {
		if (timeoutMillis == 0) return NO_DEADLINE;
		HeldSection oldest = oldest();
		return oldest == null ? NO_DEADLINE : oldest.arrivalMillis() + timeoutMillis;
	}

	/** Releases every held section; idempotent, and the funnel a closing connection uses (FR-035). */
	public void recycle() {
		for (Deque<HeldSection> queue : byStream.values()) {
			for (HeldSection held : queue) held.section().recycle();
		}
		byStream.clear();
		heldBytes = 0;
	}

	/** RFC 9204 §2.1.2's count: the streams currently blocked, not the sections they hold. */
	public int blockedStreamCount() {
		return byStream.size();
	}

	/** The sections held, which is {@link #blockedStreamCount()} or more — one stream may hold several. */
	public int sectionCount() {
		int count = 0;
		for (Deque<HeldSection> queue : byStream.values()) count += queue.size();
		return count;
	}

	/** Whether nothing is held, so the caller may cancel its timeout check. */
	public boolean isEmpty() {
		return byStream.isEmpty();
	}

	/** The bytes held now, summed over the sections themselves. */
	public long heldBytes() {
		return heldBytes;
	}

	/** {@code maxBlockedStreams × maxFieldSectionSize}, derived rather than configured (research D-4). */
	public long maxHeldBytes() {
		return maxHeldBytes;
	}

	/** The locally advertised {@code SETTINGS_QPACK_BLOCKED_STREAMS} this instance was built with. */
	public int maxBlockedStreams() {
		return maxBlockedStreams;
	}

	/** How long one section may stay blocked; {@code 0} means the time bound is disabled. */
	public long timeoutMillis() {
		return timeoutMillis;
	}

	/** Every held section in arrival order — this package's tests and diagnostics only. */
	List<HeldSection> held() {
		List<HeldSection> all = new ArrayList<>();
		for (Deque<HeldSection> queue : byStream.values()) all.addAll(queue);
		all.sort(comparingLong(HeldSection::arrivalOrder));
		return all;
	}

	private @Nullable HeldSection oldest() {
		HeldSection oldest = null;
		for (Deque<HeldSection> queue : byStream.values()) {
			HeldSection head = queue.peekFirst();
			if (head != null && (oldest == null || head.arrivalOrder() < oldest.arrivalOrder())) oldest = head;
		}
		return oldest;
	}

	private static long saturatingProduct(int count, long size) {
		if (count == 0 || size == 0) return 0;
		return size > Long.MAX_VALUE / count ? Long.MAX_VALUE : count * size;
	}

	@Override
	public String toString() {
		return "QpackBlockedSections{streams=" + byStream.size() + '/' + maxBlockedStreams +
			   ", sections=" + sectionCount() +
			   ", bytes=" + heldBytes + '/' + maxHeldBytes +
			   ", timeout=" + timeoutMillis + "ms}";
	}
}
