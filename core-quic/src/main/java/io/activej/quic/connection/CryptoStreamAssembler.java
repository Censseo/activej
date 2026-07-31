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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the CRYPTO frames of one encryption level — which carry explicit offsets and may overlap,
 * arrive out of order, or duplicate — into the in-order, de-duplicated byte stream that
 * {@code TlsEngine.consume} requires. One instance per encryption level.
 * <p>
 * This is the job the TLS layer delegates upward: feature 02's {@code CryptoReassembly} splits an
 * <i>already-ordered</i> stream into handshake messages. Different problem, different package,
 * deliberately different name.
 * <p>
 * <b>Ownership (DI-1)</b>: {@link #add} takes ownership of its buffer on <b>every</b> path —
 * delivery, buffering, duplicate discard, and every throw. The buffer it returns is the caller's.
 * <p>
 * <b>Bound (FR-028, SI-3)</b>: only <i>out-of-order</i> bytes are buffered, and never more than
 * {@code maxBufferedBytes}; exceeding it closes the connection with {@code CRYPTO_BUFFER_EXCEEDED}. A
 * long in-order handshake never approaches the bound.
 * <p>
 * Not thread-safe: the owning connection provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.6">RFC 9000 §19.6 — CRYPTO Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-7.5">RFC 9000 §7.5 — Cryptographic Message Buffering</a>
 */
public final class CryptoStreamAssembler {
	/** RFC 9000 §19.6: the sum of a CRYPTO frame's Offset and Length must not exceed 2^62 - 1. */
	public static final long MAX_OFFSET = (1L << 62) - 1;

	private final long maxBufferedBytes;

	private long readOffset;
	private final TreeMap<Long, ByteBuf> pending = new TreeMap<>();
	private long bufferedBytes;
	private boolean closed;

	public CryptoStreamAssembler(long maxBufferedBytes) {
		if (maxBufferedBytes < 1) {
			throw new IllegalArgumentException("maxBufferedBytes must be positive, got " + maxBufferedBytes);
		}
		this.maxBufferedBytes = maxBufferedBytes;
	}

	/**
	 * Accepts one CRYPTO frame's data.
	 *
	 * @param offset the frame's Offset field
	 * @param data   the frame's payload; owned by this method on every path
	 * @return the bytes that became contiguous, owned by the caller, or {@code null} if nothing did
	 * @throws QuicTransportException {@code FRAME_ENCODING_ERROR} when the offset range is out of
	 *                                bounds, {@code PROTOCOL_VIOLATION} when buffered data is
	 *                                contradicted, {@code CRYPTO_BUFFER_EXCEEDED} at the bound
	 */
	public @Nullable ByteBuf add(long offset, ByteBuf data) throws QuicTransportException {
		// Bounds before anything else (SI-4): reject before touching state or allocating.
		int length = data.readRemaining();
		if (offset < 0 || offset > MAX_OFFSET - length) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"CRYPTO frame offset range exceeds 2^62-1");
		}

		if (closed) {
			// The level was discarded while this datagram was in flight — normal, not a fault.
			data.recycle();
			return null;
		}

		if (length == 0) {
			// A zero-length CRYPTO frame is legal and has no effect.
			data.recycle();
			return null;
		}

		long end = offset + length;

		if (end <= readOffset) {
			// Entirely already delivered: a silent duplicate discard.
			data.recycle();
			return null;
		}

		if (offset < readOffset) {
			// Straddles the read offset. We no longer hold the delivered prefix, so we cannot compare
			// it — RFC 9000 §19.6 permits discarding already-received data without checking it. This
			// is a deliberate, RFC-sanctioned non-check, unlike the buffered-overlap case below.
			data.moveHead((int) (readOffset - offset));
			offset = readOffset;
			length = data.readRemaining();
		}

		// Overlap against buffered chunks: here we DO hold both copies, so RFC 9000 §19.6 requires
		// that they agree. A mismatch means the peer is rewriting handshake bytes.
		checkAgainstBuffered(offset, data);

		if (offset == readOffset) {
			return deliverFrom(offset, data);
		}

		// Out of order: buffer it, subject to the bound.
		return bufferOutOfOrder(offset, data);
	}

	/**
	 * Verifies that {@code data} agrees with every buffered chunk it overlaps.
	 * Compares without consuming {@code data} and without copying the overlap.
	 */
	private void checkAgainstBuffered(long offset, ByteBuf data) throws QuicTransportException {
		long end = offset + data.readRemaining();
		// Any chunk starting at or before this one may overlap it, as may later-starting ones.
		Map.Entry<Long, ByteBuf> floor = pending.floorEntry(offset);
		if (floor != null) {
			if (!overlapMatches(floor.getKey(), floor.getValue(), offset, data)) {
				data.recycle();
				throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
					"CRYPTO frame contradicts previously buffered data");
			}
		}
		for (Map.Entry<Long, ByteBuf> entry : pending.tailMap(offset, false).entrySet()) {
			if (entry.getKey() >= end) break;
			if (!overlapMatches(entry.getKey(), entry.getValue(), offset, data)) {
				data.recycle();
				throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
					"CRYPTO frame contradicts previously buffered data");
			}
		}
	}

	private static boolean overlapMatches(long bufferedOffset, ByteBuf buffered, long offset, ByteBuf data) {
		long start = Math.max(bufferedOffset, offset);
		long end = Math.min(bufferedOffset + buffered.readRemaining(), offset + data.readRemaining());
		if (start >= end) return true;
		int bufferedFrom = buffered.head() + (int) (start - bufferedOffset);
		int dataFrom = data.head() + (int) (start - offset);
		byte[] bufferedArray = buffered.array();
		byte[] dataArray = data.array();
		for (int i = 0, n = (int) (end - start); i < n; i++) {
			if (bufferedArray[bufferedFrom + i] != dataArray[dataFrom + i]) return false;
		}
		return true;
	}

	/**
	 * Delivers {@code data} plus every buffered chunk that is now contiguous with it, as one buffer.
	 */
	private ByteBuf deliverFrom(long offset, ByteBuf data) {
		long end = offset + data.readRemaining();

		if (pending.isEmpty() || pending.firstKey() > end) {
			// Fast path: in-order arrival with nothing buffered that this delivery touches. No allocation,
			// no copy — the overwhelmingly common case. `data` is not in `pending`, so returning it is safe.
			//
			// The guard is on the *first* buffered offset, not on whether anything extends the run: a chunk
			// entirely covered by this delivery adds no bytes to the output, but it must still be purged and
			// its bytes returned to the bound. RFC 9002 §6.5 retransmits CRYPTO data with fresh segmentation,
			// so "small out-of-order chunk, then a larger retransmission covering it" is a routine sequence —
			// leaving those chunks behind would consume the buffer budget permanently and eventually close an
			// innocent peer's connection with CRYPTO_BUFFER_EXCEEDED.
			readOffset = end;
			return data;
		}

		// Determine the full contiguous run so the output can be sized once.
		long runEnd = end;
		for (Map.Entry<Long, ByteBuf> entry : pending.entrySet()) {
			long chunkOffset = entry.getKey();
			if (chunkOffset > runEnd) break;
			long chunkEnd = chunkOffset + entry.getValue().readRemaining();
			if (chunkEnd > runEnd) {
				runEnd = chunkEnd;
			}
		}

		ByteBuf out = ByteBufPool.allocate((int) (runEnd - offset));
		out.put(data);
		data.recycle();

		while (!pending.isEmpty()) {
			Map.Entry<Long, ByteBuf> entry = pending.firstEntry();
			long chunkOffset = entry.getKey();
			if (chunkOffset > end) break;
			ByteBuf chunk = entry.getValue();
			long chunkEnd = chunkOffset + chunk.readRemaining();
			pending.pollFirstEntry();
			bufferedBytes -= chunk.readRemaining();
			if (chunkEnd > end) {
				chunk.moveHead((int) (end - chunkOffset));
				out.put(chunk);
				end = chunkEnd;
			}
			chunk.recycle();
		}

		readOffset = end;
		return out;
	}

	private @Nullable ByteBuf bufferOutOfOrder(long offset, ByteBuf data) throws QuicTransportException {
		int length = data.readRemaining();
		if (bufferedBytes + length > maxBufferedBytes) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.CRYPTO_BUFFER_EXCEEDED,
				"buffered CRYPTO data would exceed " + maxBufferedBytes + " bytes");
		}
		ByteBuf existing = pending.get(offset);
		if (existing != null) {
			// Same offset already buffered. Keep whichever is longer; the overlap has been verified.
			if (existing.readRemaining() >= length) {
				data.recycle();
				return null;
			}
			pending.remove(offset);
			bufferedBytes -= existing.readRemaining();
			existing.recycle();
		}
		pending.put(offset, data);
		bufferedBytes += length;
		return null;
	}

	/** The next offset awaiting delivery — every byte below it has already been handed to the caller. */
	public long readOffset() {
		return readOffset;
	}

	/** Out-of-order bytes currently held, bounded by {@code maxBufferedBytes}. */
	public long bufferedBytes() {
		return bufferedBytes;
	}

	/**
	 * Discards everything buffered, recycling it. Called when the level's keys are discarded or the
	 * connection closes. Idempotent (WI-9); afterwards {@link #add} recycles its input and returns
	 * {@code null}.
	 */
	public void close() {
		for (ByteBuf buf : pending.values()) {
			buf.recycle();
		}
		pending.clear();
		bufferedBytes = 0;
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}

	/** Never prints buffered bytes: they are handshake plaintext (SI-6). */
	@Override
	public String toString() {
		return "CryptoStreamAssembler{" +
			"readOffset=" + readOffset +
			", bufferedBytes=" + bufferedBytes + '/' + maxBufferedBytes +
			", pendingChunks=" + pending.size() +
			(closed ? ", closed" : "") +
			'}';
	}
}
