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
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import org.jetbrains.annotations.Nullable;

/**
 * The receiving half of one stream: the RFC 9000 §3.2 state machine over a {@link StreamReassembler}
 * and a receive-side {@link StreamFlowController}, exposed to the application as a
 * {@link ChannelSupplier} of contiguous slices.
 *
 * <h2>States (RFC 9000 §3.2)</h2>
 * <pre>
 * Recv ──STREAM with FIN──► Size Known ──every byte below the final size buffered──► Data Recvd
 *                                                                                        │
 *                                                     the application read it all and saw end-of-stream
 *                                                                                        ▼
 *                                                                                   Data Read
 * </pre>
 * The two transitions worth stating explicitly, because both are easy to get backwards:
 * <ul>
 *   <li>A {@code FIN} may arrive <b>before</b> the bytes it terminates — reordering makes that
 *       routine. Size Known is then reached at once while Data Recvd waits for the last gap to close,
 *       so the two are separate states rather than one.</li>
 *   <li>Data Read requires an <b>application</b> action, not a wire event: the stream stays known to
 *       its manager until the reader has taken every byte and observed end-of-stream (FR-006).</li>
 * </ul>
 *
 * <h2>Buffer ownership (DI-1, FR-014)</h2>
 * {@link #onStreamFrame} <b>owns its buffer on every path</b> — buffered, discarded as a duplicate,
 * or rejected with a throw. A buffer handed out by the supplier is the <b>caller's</b> to recycle,
 * and is a slice of the frame it arrived in, never a copy. {@link #closeEx} releases everything still
 * held.
 * <p>
 * Not {@code Reactive} itself: it is owned by a {@link QuicStream}, which is, and which provides the
 * reactor confinement — the same split {@code connection/} uses for {@code PacketNumberSpace} and
 * {@code SendQueue}. It does hold a {@code Promise}, so it is not usable off that reactor.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.5">RFC 9000 §4.5 — Stream Final Size</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class ReceivePart {
	/**
	 * What the owning {@code QuicStreamManager} needs to hear from a receiving part. Both methods are
	 * plain notifications: this class never decides connection-wide policy, in the same way
	 * {@link StreamFlowController} reports a limit violation rather than throwing one.
	 */
	public interface Listener {
		/**
		 * The application took {@code bytes} bytes. Drives connection-level consumed accounting and the
		 * RFC 9000 §4.2 credit grant (FR-025) — which is why it fires on the <b>reader</b>, not on arrival:
		 * a receiver that granted on arrival would be advertising memory it is still holding.
		 */
		default void onBytesConsumed(ReceivePart part, long bytes) {}

		/** The part reached {@link ReceiveState#DATA_READ} or {@link ReceiveState#RESET_READ}. */
		default void onTerminal(ReceivePart part) {}

		/**
		 * The application abandoned this receiving half: queue a {@code STOP_SENDING} carrying
		 * {@code applicationErrorCode} (RFC 9000 §19.5, FR-032), for the first send and for every
		 * retransmission alike — the two fields it carries were fixed at the abandonment.
		 * <p>
		 * Like {@link SendPart.Sink#enqueueReset} this cannot throw and <b>flushes itself</b>: it
		 * originates from an application call, {@code QuicStream.stopSending}, that is not already inside
		 * a batch.
		 *
		 * @return whether a frame was actually queued
		 */
		default boolean enqueueStopSending(long streamId, long applicationErrorCode) {
			return false;
		}
	}

	private static final Listener NO_LISTENER = new Listener() {};

	private final long streamId;
	private final StreamReassembler reassembler;
	private final StreamFlowController flowControl;
	private final Listener listener;
	private final QuicStreamSupplier supplier;

	private ReceiveState state = ReceiveState.RECV;

	/** Never decreases: the highest offset the peer has reached, which is what flow control charges. */
	private long highestOffsetReceived;

	/** Bytes the application has taken; drives the credit grant. */
	private long consumedOffset;

	private @Nullable Long finalSize;

	/** At most one — {@link ChannelSupplier} serialises reads, so no queue of them can form. */
	private @Nullable SettablePromise<ByteBuf> pendingRead;

	/**
	 * The code the application asked the peer to stop sending with, or {@code null} if it never did
	 * (RFC 9000 §19.5). Once set, arriving bytes still move {@link #highestOffsetReceived} — so the peer
	 * keeps being charged for them (FR-035) — but are recycled instead of buffered.
	 */
	private @Nullable Long stopSendingErrorCode;

	/** Whether the peer acknowledged the {@code STOP_SENDING}, which is what ends its retransmission. */
	private boolean stopSendingAcknowledged;

	/** The peer's application error code once its {@code RESET_STREAM} arrived (RFC 9000 §19.4). */
	private @Nullable Long resetErrorCode;

	/**
	 * What a read reports once this part will deliver nothing further: the peer's reset, or the
	 * application's own abandonment. Held rather than recomputed so that every read after the first
	 * reports the same fact.
	 */
	private @Nullable Exception readFailure;

	private boolean closed;

	/**
	 * Whether the <em>connection</em> ended (FR-041), which is the only close that also ends
	 * retransmission of this part's {@code STOP_SENDING} — see {@code SendPart}'s field of the same
	 * name for why the two closes cannot be one flag.
	 */
	private boolean connectionClosed;

	/**
	 * @param streamId             the stream this part belongs to (RFC 9000 §2.1)
	 * @param initialReceiveLimit  this endpoint's own {@code initial_max_stream_data_*} for this
	 *                             stream type — the window <i>we</i> advertise, never the peer's
	 *                             (RFC 9000 §18.2)
	 * @param maxRanges            {@code maxReceiveRangesPerStream} (FR-011)
	 * @param listener             may be {@code null}, which is what makes this class testable with no
	 *                             manager at all
	 */
	public ReceivePart(long streamId, long initialReceiveLimit, int maxRanges, @Nullable Listener listener) {
		this.streamId = streamId;
		this.reassembler = new StreamReassembler(maxRanges);
		this.flowControl = new StreamFlowController(initialReceiveLimit);
		this.listener = listener == null ? NO_LISTENER : listener;
		this.supplier = new QuicStreamSupplier(this);
	}

	// ---------------------------------------------------------------- the wire side

	/**
	 * Accepts one {@code STREAM} frame's fields (RFC 9000 §19.8). Whatever becomes contiguous is made
	 * readable; the rest is buffered until its gap closes.
	 * <p>
	 * <b>Takes ownership of {@code data} on every path</b>, including every throw — the reassembler's
	 * contract, extended over the checks this method performs before reaching it.
	 * <p>
	 * The order of the checks is the contract (SI-4, CHK080): the encoding bound on the offset range,
	 * then the final size, then the advertised limit, then the two buffering bounds — and only once all
	 * of them pass does anything move. A frame that any of them refuses leaves the high-water mark, the
	 * flow-control cursor and the buffered ranges exactly as it found them.
	 *
	 * @param offset the frame's Offset field
	 * @param fin    the frame's FIN bit; the final size is then {@code offset + data.readRemaining()}
	 * @param data   the frame's payload, normally a {@code slice()} of the borrowed frame
	 * @return how many bytes this frame added <b>above the high-water mark</b> — what the caller must
	 * charge to connection-level flow control (RFC 9000 §4.1). Zero for a pure duplicate, so a peer
	 * cannot buy credit back by resending
	 * @throws QuicTransportException {@code FRAME_ENCODING_ERROR} if the offset range would exceed
	 *                                2^62-1; {@code FINAL_SIZE_ERROR} if the frame contradicts a final
	 *                                size, already known or declared by this frame (RFC 9000 §4.5);
	 *                                {@code FLOW_CONTROL_ERROR} if it would exceed the advertised
	 *                                limit (RFC 9000 §4.1); {@code INTERNAL_ERROR} if it would push
	 *                                the buffered range count past {@code maxReceiveRangesPerStream},
	 *                                or the buffered piece count past that times
	 *                                {@link StreamReassembler#PIECES_PER_RANGE}
	 */
	public long onStreamFrame(long offset, boolean fin, ByteBuf data) throws QuicTransportException {
		int length = data.readRemaining();
		// Bounds before state (SI-4), and before anything is retained. Unconditional: these come off
		// the wire. StreamReassembler repeats the check because it is reachable on its own; a caller
		// that skipped it here would compute `end` by overflow below.
		if (offset < 0 || offset > StreamReassembler.MAX_OFFSET - length) {
			data.recycle();
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"STREAM frame on stream " + streamId + " has an offset range exceeding 2^62-1");
		}
		long end = offset + length;

		// Final-size and flow-control violations are the peer's fault regardless of whether this part
		// has since closed for a reason of its own — a local closure unrelated to the peer or the
		// connection (e.g. a downstream CSP failure) must not let a genuinely non-conforming peer go
		// undetected, exactly as onResetStream already validates before its own `closed` check.
		try {
			checkAgainstFinalSize(end, fin);
			if (flowControl.exceedsLimit(end)) {
				throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
					"stream " + streamId + " received data past the advertised limit " + flowControl.limit());
			}
			// Asked here rather than left to the reassembler's own copy of it, so that a frame the bound
			// will refuse moves neither the high-water mark nor the flow-control cursor below (SI-4,
			// CHK080). Skipped while discarding or closed, where nothing is buffered and the bound cannot
			// be reached (FR-035) — and, once closed, the reassembler itself has already been closed.
			if (!closed && !isDiscarding()) {
				if (reassembler.wouldExceedRangeBound(offset, length)) {
					throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
						"stream " + streamId + " would exceed maxReceiveRangesPerStream discontiguous ranges");
				}
				// The second, independent buffering bound: a peer that fragments inside a single gap holds
				// the range count at one while every byte buys its own map entry. See StreamReassembler.
				if (reassembler.wouldExceedPieceBound(offset, length)) {
					throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
						"stream " + streamId + " would exceed " + reassembler.maxBufferedPieces() +
						" buffered pieces (maxReceiveRangesPerStream x StreamReassembler.PIECES_PER_RANGE)");
				}
			}
		} catch (QuicTransportException e) {
			data.recycle();
			throw e;
		}

		if (closed) {
			// Reset, drained, or the connection is gone, while this datagram was in flight — normal, now
			// that the frame itself has been checked against the peer's own declared limits above.
			data.recycle();
			return 0;
		}

		long newBytes = Math.max(0, end - highestOffsetReceived);
		highestOffsetReceived = Math.max(highestOffsetReceived, end);
		flowControl.advanceUsedTo(end);
		if (fin) {
			finalSize = end;
		}

		if (isDiscarding()) {
			// FR-035: the accounting above has already happened, deliberately and in this order — the peer
			// spent this credit and does not get it back for having been asked to stop, or for having reset
			// after the bytes were in flight. What changes is only that nothing is kept: there is no reader
			// left to hand it to.
			data.recycle();
			advanceStateAfterArrival();
			return newBytes;
		}

		// Owns `data` from here on, on every path including its own throw.
		reassembler.add(offset, data);

		advanceStateAfterArrival();
		completePendingRead();
		return newBytes;
	}

	/** Whether arriving bytes are accounted and dropped rather than buffered (FR-034, FR-035). */
	private boolean isDiscarding() {
		return stopSendingErrorCode != null || resetErrorCode != null;
	}

	/**
	 * RFC 9000 §4.5: once a final size is known it is immutable, and no byte may be claimed above it —
	 * whether by a later frame or by a {@code FIN} that contradicts data already accounted for.
	 */
	private void checkAgainstFinalSize(long end, boolean fin) throws QuicTransportException {
		if (finalSize != null) {
			if (end > finalSize) {
				throw new QuicTransportException(QuicTransportErrors.FINAL_SIZE_ERROR,
					"stream " + streamId + " received data past its final size " + finalSize);
			}
			if (fin && end != finalSize) {
				throw new QuicTransportException(QuicTransportErrors.FINAL_SIZE_ERROR,
					"stream " + streamId + " received a second FIN declaring final size " + end +
					" after " + finalSize);
			}
			return;
		}
		if (fin && end < highestOffsetReceived) {
			throw new QuicTransportException(QuicTransportErrors.FINAL_SIZE_ERROR,
				"stream " + streamId + " received a FIN declaring final size " + end +
				" below the " + highestOffsetReceived + " bytes already received");
		}
	}

	private void advanceStateAfterArrival() {
		if (finalSize == null || state != ReceiveState.RECV && state != ReceiveState.SIZE_KNOWN) return;
		if (stopSendingErrorCode != null) {
			// Nothing is buffered and no reader is left, so there is no application action to wait for: the
			// peer's last byte is what finishes this half. Waiting for the reassembler instead would wait
			// forever, since a part that discards never advances its read offset — and RFC 9000 §3.5 lets
			// the peer answer a STOP_SENDING with a plain FIN when it had already sent everything, so this
			// is the ordinary end of an abandoned stream rather than a corner of one.
			state = highestOffsetReceived >= finalSize ? ReceiveState.DATA_READ : ReceiveState.SIZE_KNOWN;
			if (state == ReceiveState.DATA_READ) {
				listener.onTerminal(this);
			}
			return;
		}
		// Data Recvd is "every byte below the final size is buffered" — which the reassembler reports as
		// its read offset having reached the final size. Size Known is everything short of that.
		state = reassembler.readOffset() >= finalSize ? ReceiveState.DATA_RECVD : ReceiveState.SIZE_KNOWN;
	}

	// ---------------------------------------------------------------- the application side

	/**
	 * The receive half as a CSP supplier. Each {@code get()} resolves with the next contiguous slice,
	 * or {@code null} at end-of-stream. <b>The caller owns and must recycle every buffer it takes.</b>
	 */
	public ChannelSupplier<ByteBuf> supplier() {
		return supplier;
	}

	/**
	 * One read, as {@link QuicStreamSupplier} needs it: the next contiguous slice, {@code null} at
	 * end-of-stream, or a promise that stays pending until either becomes true.
	 */
	Promise<ByteBuf> read() {
		if (state == ReceiveState.RESET_RECVD) {
			// RFC 9000 §3.2's Reset Recvd → Reset Read: an application action, not a wire event, exactly as
			// Data Recvd → Data Read is. This is the read that performs it.
			return Promise.ofException(observeReset());
		}
		if (readFailure != null) {
			return Promise.ofException(readFailure);
		}
		if (reassembler.hasReady() || isEndOfStreamDeliverable()) {
			return Promise.of(pollNext());
		}
		if (closed) {
			// Closed with nothing left: the supplier's own closed check normally answers first, so this is
			// only reachable on a part closed while no read was outstanding.
			return Promise.of(null);
		}
		SettablePromise<ByteBuf> promise = new SettablePromise<>();
		pendingRead = promise;
		return promise;
	}

	private boolean isEndOfStreamDeliverable() {
		return state == ReceiveState.DATA_RECVD || state == ReceiveState.DATA_READ;
	}

	/**
	 * Hands out the next slice, or {@code null} for end-of-stream, doing the consumed accounting and
	 * the Data Read transition that go with it. Never delivers end-of-stream while a byte remains.
	 */
	private @Nullable ByteBuf pollNext() {
		ByteBuf buf = reassembler.poll();
		if (buf != null) {
			long taken = buf.readRemaining();
			consumedOffset += taken;
			listener.onBytesConsumed(this, taken);
			return buf;
		}
		if (state == ReceiveState.DATA_RECVD) {
			state = ReceiveState.DATA_READ;
			listener.onTerminal(this);
		}
		return null;
	}

	/** Resolves a parked read, if one is parked and there is now something to hand it. */
	private void completePendingRead() {
		SettablePromise<ByteBuf> promise = pendingRead;
		if (promise == null) return;
		if (!reassembler.hasReady() && !isEndOfStreamDeliverable()) return;
		// Cleared first: the continuation runs synchronously and may ask for the next slice re-entrantly.
		pendingRead = null;
		promise.set(pollNext());
	}

	// ---------------------------------------------------------------- abrupt termination (RFC 9000 §3.5)

	/**
	 * A {@code RESET_STREAM} arrived (RFC 9000 §19.4): RFC 9000 §3.2's Recv/Size Known → Reset Recvd,
	 * with the peer's declared final size and application error code (FR-034).
	 * <p>
	 * The final size is validated <b>before</b> any state moves and <b>whatever</b> state this part is
	 * in, including one it can no longer leave: a second abort, or an abort racing a {@code FIN}, that
	 * declares a <em>different</em> final size is a genuine RFC 9000 §4.5 violation and not a
	 * correction, while one that agrees is a retransmission and a no-op (SC-004).
	 * <p>
	 * From Data Recvd or Data Read this is deliberately ignored beyond that check: every byte is already
	 * here, RFC 9000 §3.2 permits ignoring the reset, and a finished stream must not be resurrected into
	 * a failed one.
	 *
	 * @return how many bytes the declared final size revealed <b>above the high-water mark</b> — what
	 * the caller must charge to connection-level flow control (RFC 9000 §4.5). The peer's declared size
	 * may be higher than anything that actually arrived, and it is charged all the same
	 * @throws QuicTransportException {@code FRAME_ENCODING_ERROR} if the final size exceeds 2^62-1;
	 *                                {@code FINAL_SIZE_ERROR} if it contradicts a final size already
	 *                                known or falls below the bytes already received (RFC 9000 §4.5);
	 *                                {@code FLOW_CONTROL_ERROR} if it exceeds the advertised stream
	 *                                limit (RFC 9000 §4.1)
	 */
	public long onResetStream(long applicationErrorCode, long finalSize) throws QuicTransportException {
		// Bounds before state (SI-4), unconditional: this comes off the wire.
		if (finalSize < 0 || finalSize > StreamReassembler.MAX_OFFSET) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"RESET_STREAM on stream " + streamId + " declared a final size exceeding 2^62-1");
		}
		if (this.finalSize != null && this.finalSize != finalSize) {
			throw new QuicTransportException(QuicTransportErrors.FINAL_SIZE_ERROR,
				"stream " + streamId + " was reset with final size " + finalSize +
				" after " + this.finalSize);
		}
		if (finalSize < highestOffsetReceived) {
			throw new QuicTransportException(QuicTransportErrors.FINAL_SIZE_ERROR,
				"stream " + streamId + " was reset with final size " + finalSize +
				" below the " + highestOffsetReceived + " bytes already received");
		}
		if (flowControl.exceedsLimit(finalSize)) {
			throw new QuicTransportException(QuicTransportErrors.FLOW_CONTROL_ERROR,
				"stream " + streamId + " was reset past the advertised limit " + flowControl.limit());
		}

		if (closed) {
			// Drained, or the connection is gone, while this datagram was in flight — normal.
			return 0;
		}

		long newBytes = Math.max(0, finalSize - highestOffsetReceived);
		highestOffsetReceived = finalSize;
		flowControl.advanceUsedTo(finalSize);
		this.finalSize = finalSize;

		if (state != ReceiveState.RECV && state != ReceiveState.SIZE_KNOWN) {
			return newBytes;
		}
		resetErrorCode = applicationErrorCode;
		QuicStreamResetException failure = new QuicStreamResetException(streamId, applicationErrorCode);
		readFailure = failure;
		// Undelivered bytes are discarded, not delivered after the failure (FR-034): the reassembler
		// releases everything it holds, and every later arrival is dropped by isDiscarding().
		reassembler.close();
		state = ReceiveState.RESET_RECVD;

		SettablePromise<ByteBuf> promise = pendingRead;
		pendingRead = null;
		if (promise != null) {
			// Reset Read on the spot: a parked read *is* the application observing the abort (FR-036).
			promise.setException(observeReset());
		} else if (stopSendingErrorCode != null) {
			// The application already declared it wants nothing more, so there is nobody left to observe
			// the abort and nothing to wait for before this half is terminal.
			observeReset();
		}
		return newBytes;
	}

	/**
	 * Declares that the application wants no more data on this stream, conveyed as {@code STOP_SENDING}
	 * with an application error code (RFC 9000 §19.5, FR-032). Idempotent.
	 * <p>
	 * Everything buffered is released and every later arrival is accounted and dropped (FR-035) —
	 * accounted because the peer spent that credit before it could have heard, and dropped because
	 * there is no reader left. Reads from here on fail with {@link QuicStreamStopSendingException}
	 * carrying the code the application chose, until the peer's {@code RESET_STREAM} replaces it with
	 * the peer's own (RFC 9000 §3.5).
	 * <p>
	 * A no-op outside Recv and Size Known, per RFC 9000 §3.5: from Data Recvd every byte is already
	 * here and from Reset Recvd the peer has already stopped, so in both the frame would ask for
	 * something that has happened.
	 */
	public void stopSending(long applicationErrorCode) {
		if (stopSendingErrorCode != null || connectionClosed) return;
		if (state != ReceiveState.RECV && state != ReceiveState.SIZE_KNOWN) return;
		stopSendingErrorCode = applicationErrorCode;
		QuicStreamStopSendingException failure =
			QuicStreamStopSendingException.requestedLocally(streamId, applicationErrorCode);
		readFailure = failure;
		reassembler.close();

		listener.enqueueStopSending(streamId, applicationErrorCode);
		// After the frame is queued: failing the read runs the application's continuation synchronously,
		// and that continuation routinely closes the reader, which would otherwise get there first.
		SettablePromise<ByteBuf> promise = pendingRead;
		pendingRead = null;
		if (promise != null) {
			promise.setException(failure);
		}
		// The final size may already be known — a FIN that arrived before the application gave up — in
		// which case this half has nothing left to do at all.
		advanceStateAfterArrival();
	}

	/** The {@code STOP_SENDING} was acknowledged, which is what ends its retransmission. */
	public void onStopSendingAcknowledged() {
		stopSendingAcknowledged = true;
	}

	/**
	 * The {@code STOP_SENDING} was declared lost, and is re-enqueued until acknowledged (FR-032): the
	 * request is what stops the peer, so a dropped packet must not turn "I want no more data" into a
	 * window's worth of bytes this endpoint will only discard.
	 * <p>
	 * A no-op once the peer has answered — with its {@code RESET_STREAM} or with the last of its
	 * data — since the frame would then be asking for something that has already happened.
	 */
	public void onStopSendingLost() {
		Long code = stopSendingErrorCode;
		if (code == null || stopSendingAcknowledged || connectionClosed) return;
		if (state != ReceiveState.RECV && state != ReceiveState.SIZE_KNOWN) return;
		listener.enqueueStopSending(streamId, code);
	}

	/**
	 * RFC 9000 §3.2's Reset Recvd → Reset Read, which is terminal and therefore a release condition
	 * (FR-006). Runs once: the state is moved before the listener is told, so the release it triggers
	 * cannot re-enter this.
	 */
	private QuicStreamResetException observeReset() {
		Exception failure = readFailure;
		assert failure instanceof QuicStreamResetException;
		if (state == ReceiveState.RESET_RECVD) {
			state = ReceiveState.RESET_READ;
			listener.onTerminal(this);
		}
		return (QuicStreamResetException) failure;
	}

	// ---------------------------------------------------------------- teardown

	/**
	 * Releases everything held and fails a parked read with {@code e} (FR-041). Idempotent (WI-9);
	 * afterwards {@link #onStreamFrame} still owns and discards its buffer, and a read reports
	 * end-of-stream rather than hanging.
	 */
	public void closeEx(Exception e) {
		if (closed) return;
		closed = true;
		reassembler.close();
		// Before the pending read is failed, and re-entrant through QuicStreamSupplier.onClosed — which
		// returns straight back out, `closed` being already set. Closing first is what makes a read issued
		// from the failed read's own continuation fail too, instead of reporting a false end-of-stream.
		supplier.closeEx(e);
		SettablePromise<ByteBuf> promise = pendingRead;
		pendingRead = null;
		if (promise != null) {
			promise.setException(e);
		}
	}

	/**
	 * The connection ended before this part did (FR-041): {@link #closeEx}, and additionally the end of
	 * this part's own retransmissions — there is no longer a transport to retransmit onto.
	 */
	public void onConnectionClosed(Exception e) {
		connectionClosed = true;
		closeEx(e);
	}

	// ---------------------------------------------------------------- accessors

	/** The RFC 9000 §2.1 identifier of the stream this half belongs to. */
	public long streamId() {
		return streamId;
	}

	/** This half's RFC 9000 §3.2 receiving state. */
	public ReceiveState state() {
		return state;
	}

	/** RFC 9000 §4.1: the highest offset the peer has reached. Never decreases, never refunded. */
	public long highestOffsetReceived() {
		return highestOffsetReceived;
	}

	/** Bytes the application has taken. Drives the FR-025 credit grant. */
	public long consumedOffset() {
		return consumedOffset;
	}

	/** The limit currently advertised to the peer for this stream (RFC 9000 §19.10). */
	public long maxDataOffset() {
		return flowControl.limit();
	}

	/** The final size once known, from a {@code FIN} or from a {@code RESET_STREAM}; else {@code null}. */
	public @Nullable Long finalSize() {
		return finalSize;
	}

	/**
	 * The peer's application error code once its {@code RESET_STREAM} arrived, else {@code null}
	 * (RFC 9000 §19.4, §20.2).
	 */
	public @Nullable Long resetErrorCode() {
		return resetErrorCode;
	}

	/**
	 * The code this endpoint asked the peer to stop sending with, else {@code null}
	 * (RFC 9000 §19.5, §20.2).
	 */
	public @Nullable Long stopSendingErrorCode() {
		return stopSendingErrorCode;
	}

	/** The receive-side controller: the limit advertised to the peer, and what a grant raises (FR-025). */
	public StreamFlowController flowControl() {
		return flowControl;
	}

	/**
	 * Discontiguous ranges this part is holding out of order — the quantity bounded by
	 * {@code maxReceiveRangesPerStream} (FR-011, clarification Q1), and so how close this stream is to
	 * the {@code INTERNAL_ERROR} that bound answers with.
	 * <p>
	 * A run of buffered pieces that touch end-to-end is <b>one</b> range; see
	 * {@link StreamReassembler} for why the bound is on gaps rather than on pieces.
	 */
	public int pendingRanges() {
		return reassembler.pendingRanges();
	}

	/** Buffers held out of order. At least {@link #pendingRanges()}; bounded by flow control, not by it. */
	public int bufferedPieces() {
		return reassembler.bufferedPieces();
	}

	/** Whether this part can no longer change state and the stream may be released (FR-006). */
	public boolean isTerminal() {
		return state == ReceiveState.DATA_READ || state == ReceiveState.RESET_READ;
	}

	/** Never prints buffered bytes: they are application data (SI-6). */
	@Override
	public String toString() {
		return "ReceivePart{stream=" + streamId +
			", " + state +
			", received=" + highestOffsetReceived +
			", consumed=" + consumedOffset +
			(finalSize == null ? "" : ", finalSize=" + finalSize) +
			'}';
	}
}
