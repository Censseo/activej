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

import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Frames awaiting transmission, in send order, one queue per encryption level (RFC 9000 §12.4 governs
 * which frame types are legal at which level; this queue only keeps them apart).
 * <p>
 * <b>Ownership (DI-1)</b>: the queue owns every frame handed to it. It recycles payload-carrying
 * frames on {@link #drop()} and on an enqueue rejected by the byte bound. A frame handed out by
 * {@link #poll} or {@link #pollUpTo} becomes the caller's again.
 * <p>
 * Frames re-queued after loss go to the <b>front</b> of their level's queue: retransmission precedes
 * new data. The whole queue is dropped when the connection enters closing or draining.
 * <p>
 * The byte bound is global across levels (FR-040); exceeding it is an {@code INTERNAL_ERROR}, since a
 * queue that cannot be drained is a local fault, not a peer's.
 * <p>
 * Not thread-safe: the owning connection provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.4">RFC 9000 §12.4 — Frames and Frame Types</a>
 */
public final class SendQueue {
	private static final class Entry {
		final QuicFrame frame;
		final boolean handlerOwned;
		/**
		 * Cached so the byte accounting cannot drift: enqueue and poll must add and subtract the same
		 * number, even if {@code encodedLength()} were ever to become non-constant.
		 */
		final int encodedLength;

		Entry(QuicFrame frame, boolean handlerOwned) {
			this.frame = frame;
			this.handlerOwned = handlerOwned;
			this.encodedLength = frame.encodedLength();
		}
	}

	private final Map<EncryptionLevel, ArrayDeque<Entry>> pending = new EnumMap<>(EncryptionLevel.class);
	private final long maxQueuedBytes;

	private long queuedBytes;
	private boolean dropped;

	public SendQueue(long maxQueuedBytes) {
		if (maxQueuedBytes < 1) {
			throw new IllegalArgumentException("maxQueuedBytes must be positive, got " + maxQueuedBytes);
		}
		this.maxQueuedBytes = maxQueuedBytes;
		// Research D-5 audit (a) verdict: ZERO_RTT belongs here. A queue is per *level*, not per
		// packet number space — a frame queued for 0-RTT travels in a 0-RTT packet under its own keys
		// and must not be drained into a 1-RTT one — so every level gets its own deque.
		for (EncryptionLevel level : EncryptionLevel.values()) {
			pending.put(level, new ArrayDeque<>());
		}
	}

	/**
	 * Appends a frame for first transmission.
	 * <p>
	 * On rejection the frame is recycled before the exception is thrown — the caller has already
	 * handed over ownership, and a connection that is about to close cannot use it.
	 *
	 * @throws QuicTransportException {@code INTERNAL_ERROR} when the byte bound would be exceeded (FR-040)
	 */
	public void enqueue(EncryptionLevel level, QuicFrame frame, boolean handlerOwned) throws QuicTransportException {
		if (dropped) {
			// The connection is closing; nothing more will ever be sent.
			Recyclers.recycle(frame);
			return;
		}
		Entry entry = new Entry(frame, handlerOwned);
		if (queuedBytes + entry.encodedLength > maxQueuedBytes) {
			Recyclers.recycle(frame);
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"send queue would exceed " + maxQueuedBytes + " bytes");
		}
		pending.get(level).addLast(entry);
		queuedBytes += entry.encodedLength;
	}

	/**
	 * Puts lost frames back at the front of their level's queue, preserving their relative order.
	 * <p>
	 * The list is walked in reverse so that repeated {@code addFirst} calls do not invert it —
	 * retransmitting a CRYPTO stream out of order would be a protocol bug.
	 *
	 * @throws QuicTransportException {@code INTERNAL_ERROR} when the byte bound would be exceeded
	 */
	public void requeue(EncryptionLevel level, List<QuicFrame> frames, boolean handlerOwned) throws QuicTransportException {
		if (dropped) {
			for (QuicFrame frame : frames) {
				Recyclers.recycle(frame);
			}
			return;
		}
		ArrayDeque<Entry> deque = pending.get(level);
		for (int i = frames.size() - 1; i >= 0; i--) {
			QuicFrame frame = frames.get(i);
			Entry entry = new Entry(frame, handlerOwned);
			if (queuedBytes + entry.encodedLength > maxQueuedBytes) {
				// Recycle this frame and everything not yet re-queued from the list.
				for (int j = i; j >= 0; j--) {
					Recyclers.recycle(frames.get(j));
				}
				throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
					"send queue would exceed " + maxQueuedBytes + " bytes while retransmitting");
			}
			deque.addFirst(entry);
			queuedBytes += entry.encodedLength;
		}
	}

	/**
	 * Re-levels every frame still queued at {@code from} to {@code to}, preserving their order and
	 * their {@code handlerOwned} flag (RFC 9001 §4.9.3).
	 * <p>
	 * The one caller is a client that has just installed 1-RTT keys: whatever it queued for 0-RTT and
	 * did not manage to send must still be sent, and the level it was queued for no longer exists.
	 * They go to the <b>front</b> of the target queue, because everything queued at 0-RTT was queued
	 * before anything 1-RTT could have been, and a stream's bytes must not overtake each other.
	 * <p>
	 * {@code queuedBytes} is deliberately untouched: nothing left the queue, so nothing was freed.
	 * A no-op once the queue has been dropped, exactly like {@link #enqueue} and {@link #requeue}.
	 */
	public void moveAll(EncryptionLevel from, EncryptionLevel to) {
		if (dropped || from == to) return;
		ArrayDeque<Entry> source = pending.get(from);
		ArrayDeque<Entry> target = pending.get(to);
		Entry entry;
		while ((entry = source.pollLast()) != null) {
			target.addFirst(entry);
		}
	}

	/**
	 * Removes every queued frame {@code filter} accepts, at <b>every</b> level, recycling each one — the
	 * seam a layer above uses to purge the frames of state it has just discarded (spec FR-055).
	 * <p>
	 * Every level is swept rather than one, because {@link #moveAll} may already have re-levelled the
	 * very frames the caller means. What is left keeps its order and its {@code handlerOwned} flag.
	 * <p>
	 * {@code queuedBytes} is decremented by exactly what leaves — unlike {@link #moveAll}, where nothing
	 * leaves. A drift here would silently move the {@code maxSendQueueBytes} bound rather than fail.
	 *
	 * @param filter judged on the frame alone; this queue interprets nothing about it
	 * @return how many frames were removed
	 */
	public int removeIf(Predicate<QuicFrame> filter) {
		int removed = 0;
		for (ArrayDeque<Entry> deque : pending.values()) {
			for (Iterator<Entry> it = deque.iterator(); it.hasNext(); ) {
				Entry entry = it.next();
				if (!filter.test(entry.frame)) continue;
				it.remove();
				queuedBytes -= entry.encodedLength;
				Recyclers.recycle(entry.frame);
				removed++;
			}
		}
		return removed;
	}

	/** Takes the next frame at this level, or {@code null} when there is none. Ownership passes to the caller. */
	public @Nullable QuicFrame poll(EncryptionLevel level) {
		Entry entry = pending.get(level).pollFirst();
		if (entry == null) return null;
		queuedBytes -= entry.encodedLength;
		return entry.frame;
	}

	/**
	 * Hands out frames at this level while they fit within {@code allowanceBytes}, in order.
	 * <p>
	 * A frame that does not fit stays at the front: the queue never looks past it for a smaller one,
	 * because that would reorder the stream it belongs to.
	 *
	 * @return the number of bytes handed out
	 */
	public int pollUpTo(EncryptionLevel level, int allowanceBytes, Consumer<QuicFrame> out) {
		ArrayDeque<Entry> deque = pending.get(level);
		int used = 0;
		Entry entry;
		while ((entry = deque.peekFirst()) != null && used + entry.encodedLength <= allowanceBytes) {
			deque.pollFirst();
			used += entry.encodedLength;
			queuedBytes -= entry.encodedLength;
			out.accept(entry.frame);
		}
		return used;
	}

	/** Whether the frame at the front of this level's queue, if any, was contributed by the frame handler (FR-038). */
	public boolean isNextHandlerOwned(EncryptionLevel level) {
		Entry entry = pending.get(level).peekFirst();
		return entry != null && entry.handlerOwned;
	}

	public boolean hasPending(EncryptionLevel level) {
		return !pending.get(level).isEmpty();
	}

	public boolean isEmpty() {
		for (ArrayDeque<Entry> deque : pending.values()) {
			if (!deque.isEmpty()) return false;
		}
		return true;
	}

	public long queuedBytes() {
		return queuedBytes;
	}

	public int pendingCount(EncryptionLevel level) {
		return pending.get(level).size();
	}

	/**
	 * Drops every queued frame, recycling the ones that carry buffers. Called when the connection
	 * enters closing or draining. Idempotent (WI-9). After this, {@code enqueue} silently recycles
	 * whatever it is given rather than accumulating for a connection that will never send again.
	 */
	public void drop() {
		for (ArrayDeque<Entry> deque : pending.values()) {
			Entry entry;
			while ((entry = deque.pollFirst()) != null) {
				Recyclers.recycle(entry.frame);
			}
		}
		queuedBytes = 0;
		dropped = true;
	}

	public boolean isDropped() {
		return dropped;
	}

	@Override
	public String toString() {
		return "SendQueue{" +
			"initial=" + pending.get(EncryptionLevel.INITIAL).size() +
			", zeroRtt=" + pending.get(EncryptionLevel.ZERO_RTT).size() +
			", handshake=" + pending.get(EncryptionLevel.HANDSHAKE).size() +
			", oneRtt=" + pending.get(EncryptionLevel.ONE_RTT).size() +
			", queuedBytes=" + queuedBytes + '/' + maxQueuedBytes +
			(dropped ? ", dropped" : "") +
			'}';
	}
}
