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
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicTransportException;
import org.jetbrains.annotations.Nullable;

/**
 * The sending half of one stream: the RFC 9000 §3.1 state machine, offset assignment and
 * fragmentation into {@code STREAM} frames, exposed to the application as a CSP
 * {@link ChannelConsumer}.
 *
 * <h2>States (RFC 9000 §3.1)</h2>
 * <pre>
 * Ready ──first write──► Send ──FIN written──► Data Sent ──every frame acknowledged──► Data Recvd
 *   └──────────────────────┴────── reset / STOP_SENDING ──► Reset Sent ──acknowledged──► Reset Recvd
 * </pre>
 * Data Recvd needs <em>every</em> frame acknowledged, not merely every byte sent: a lost frame is not
 * an acknowledgement, however the layer above chooses to deal with it (research R-08).
 * <p>
 * Reset Sent is reachable from all three of the states before it and is the same transition whether the
 * application called {@link #reset} or the peer sent a {@code STOP_SENDING} (RFC 9000 §3.5, FR-033);
 * only the code and the typed failure differ. Once in it, a lost {@code STREAM} frame is released
 * rather than resent (FR-018) while the {@code RESET_STREAM} itself is re-enqueued until acknowledged
 * (RFC 9000 §19.4).
 *
 * <h2>The offset invariant</h2>
 * A byte is <b>never</b> assigned an offset that either flow-control limit has not permitted
 * (FR-021, FR-022) — the stream-level limit from the peer's {@code MAX_STREAM_DATA}, and the
 * connection-level one from its {@code MAX_DATA}. When a write cannot progress, its promise is
 * <b>withheld</b> rather than failed or queued: that is what makes CSP's backpressure equal QUIC's
 * flow control (research R-06), and it bounds the send side to <b>one</b> partially-consumed buffer,
 * because {@link ChannelConsumer} serialises writes.
 *
 * <h2>Batching (research R-03)</h2>
 * Frames are handed to {@link Sink#enqueueFrame} one at a time and {@link Sink#requestSend} is called
 * <b>once</b> at the end of the batch, so a burst shares a datagram instead of paying a flush per
 * frame.
 *
 * <h2>Buffer ownership (DI-1)</h2>
 * {@link #write} <b>takes ownership</b> of its buffer on every path, including rejection and close.
 * A frame handed to the sink is the sink's; a frame handed back through
 * {@link #onFrameAcknowledged} or {@link #onFrameLost} is this part's again.
 * <p>
 * Not {@code Reactive} itself: it is owned by a {@link QuicStream}, which is, and which provides the
 * reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class SendPart {
	/**
	 * The transport, reduced to what a sending part needs of it. In production the owning
	 * {@code QuicStreamManager} implements this over {@code QuicConnection.enqueueFrame} and
	 * {@code requestSend}; a test can implement it as a list, which is what keeps this state machine
	 * assertable without a connection.
	 */
	public interface Sink {
		/**
		 * How many more bytes may be outstanding across <em>every</em> sending part of the connection —
		 * {@code maxOutstandingStreamBytes} minus what is already in flight (FR-019). A local bound, not
		 * a wire one: it keeps the stream layer from filling the connection's send queue.
		 */
		long outstandingBytesAvailable();

		/**
		 * Records a change in this connection's outstanding stream bytes: positive when a frame is
		 * enqueued, negative when one is acknowledged. Never on loss — a lost frame is still
		 * outstanding, it is awaiting retransmission (research R-08).
		 */
		void onOutstandingBytesChanged(long delta);

		/** Queues one {@code STREAM} frame. <b>Takes ownership of {@code frame}</b> on every path. */
		void enqueueFrame(StreamFrame frame) throws QuicTransportException;

		/**
		 * Queues one {@code STREAM} frame that is being <b>retransmitted</b>, ahead of everything already
		 * queued (FR-017, RFC 9002 §6.5). <b>Takes ownership of {@code frame}</b> on every path.
		 * <p>
		 * Ahead, not behind, because this part may have queued far more than a congestion window — up to
		 * {@code maxOutstandingStreamBytes} — and an appended retransmission would wait out the whole
		 * backlog while the receiver sits on undeliverable bytes past the gap. The default is the
		 * appending one, so a {@link Sink} that keeps a list needs to know nothing about this.
		 */
		default void requeueFrame(StreamFrame frame) throws QuicTransportException {
			enqueueFrame(frame);
		}

		/** Called <b>once</b> after a batch of {@link #enqueueFrame} calls (research R-03). */
		void requestSend();

		/**
		 * Queues one {@code RESET_STREAM} (RFC 9000 §19.4) — for the first send and for every
		 * retransmission alike, since the three fields it carries are fixed at the moment of the abort and
		 * a re-sent one must be identical (RFC 9000 §13.3).
		 * <p>
		 * Unlike {@link #enqueueFrame} this cannot throw and this <b>flushes itself</b>: a reset has no
		 * write promise to fail and no batch of its own, and it originates from an application call —
		 * {@code QuicStream.reset} — that is not already inside one. An implementation that is inside a
		 * batch is expected to nest rather than flush early.
		 *
		 * @return whether a frame was actually queued
		 */
		default boolean enqueueReset(long streamId, long applicationErrorCode, long finalSize) {
			return false;
		}

		/**
		 * This part has bytes to send and is held by <em>its own</em> stream limit: queue a
		 * {@code STREAM_DATA_BLOCKED} carrying {@code limit} (RFC 9000 §19.13, FR-027).
		 * <p>
		 * The per-stream FR-027 de-duplication is done by the caller — {@link SendPart} announces a limit
		 * value at most once — so an implementation may queue unconditionally.
		 *
		 * @return whether a frame was actually queued, which is what decides whether the batch needs a
		 * {@link #requestSend}
		 */
		default boolean onStreamDataBlocked(long streamId, long limit) {
			return false;
		}

		/**
		 * This part has bytes to send and is held by the <em>connection-wide</em> limit: queue a
		 * {@code DATA_BLOCKED} carrying {@code limit} (RFC 9000 §19.12, FR-027).
		 * <p>
		 * Unlike {@link #onStreamDataBlocked}, the de-duplication here is <b>the implementation's</b>:
		 * every sending part of the connection shares one limit, so several of them can reach it, and
		 * announcing once per part would be exactly the flood FR-027 forbids.
		 *
		 * @return whether a frame was actually queued
		 */
		default boolean onDataBlocked(long limit) {
			return false;
		}

		/** The part reached {@link SendState#DATA_RECVD} or {@link SendState#RESET_RECVD}. */
		default void onTerminal(SendPart part) {}

		/**
		 * Whether {@link #hasPendingWrite()} just became true or false. Lets an implementation track the
		 * (usually small) set of parts actually worth retrying on a connection-wide event, rather than
		 * walking every sending part of the connection to find them.
		 */
		default void onPendingWriteChanged(SendPart part, boolean pending) {}

		/**
		 * The part entered {@link SendState#RESET_SENT} — by {@link SendPart#reset} or by the peer's
		 * {@code STOP_SENDING} (RFC 9000 §3.5). Fires <b>once</b> per part, which is what makes it the
		 * right place to count aborted streams (FR-043); {@link #enqueueReset} fires again per
		 * retransmission and is not.
		 */
		default void onReset(SendPart part) {}
	}

	private final long streamId;
	private final StreamFlowController flowControl;
	private final ConnectionFlowController connectionFlowControl;
	private final int maxFrameDataSize;
	private final Sink sink;
	private final QuicStreamConsumer consumer;

	private SendState state = SendState.READY;

	/** The next absolute offset to assign. Never rewound. */
	private long writeOffset;

	private @Nullable Long finalSize;

	/** This part's share of the connection-wide outstanding budget. */
	private long outstandingBytes;

	/** Frames enqueued and not yet acknowledged; Data Recvd is this reaching zero. */
	private int unacknowledgedFrames;

	/** At most one — {@link ChannelConsumer} serialises writes (research R-06). */
	private @Nullable SettablePromise<Void> pendingWrite;

	/** The partially-consumed buffer of {@link #pendingWrite}; non-null exactly when it is. */
	private @Nullable ByteBuf pendingBuf;

	/** Whether {@link #pendingBuf}'s last byte carries the {@code FIN} bit. */
	private boolean pendingFin;

	/**
	 * The stream limit value last announced with a {@code STREAM_DATA_BLOCKED} frame, or {@code -1} if
	 * none ever was (FR-027).
	 * <p>
	 * {@code -1} rather than {@code 0} because {@code 0} is a limit a peer can genuinely advertise, and
	 * being blocked at zero is precisely the case that most needs announcing. Since a limit only ever
	 * rises, "not the value last announced" and "not yet announced at this limit" are the same test —
	 * so a persistently blocked writer retried a hundred times still says it once.
	 */
	private long blockedAnnouncedAt = -1;

	/**
	 * The application error code this part was aborted with, or {@code null} if it never was
	 * (RFC 9000 §19.4). Non-null exactly while the state is {@link SendState#RESET_SENT} or
	 * {@link SendState#RESET_RECVD}, and it is what a retransmitted {@code RESET_STREAM} carries.
	 */
	private @Nullable Long resetErrorCode;

	/**
	 * What a write reports once this part accepts no more of them: the reset exception, the
	 * stop-sending one, or the connection's own. Set together with the condition that caused it, so a
	 * write never has to work out which of the three it is (FR-036).
	 */
	private @Nullable Exception writeFailure;

	/**
	 * Whether the writer accepts no further data — because the application closed it, because the
	 * pipeline feeding it closed it in reaction to a failed write, or because the connection ended.
	 */
	private boolean closed;

	/**
	 * Whether the <em>connection</em> ended (FR-041), which is the only close that also ends
	 * retransmission of this part's {@code RESET_STREAM}.
	 * <p>
	 * Distinct from {@link #closed} because the two are routinely reached together and mean opposite
	 * things: a {@link #reset} fails its pending write, whose CSP pipeline reacts by closing the
	 * consumer, which closes this part — and a part that took that for connection teardown would stop
	 * retransmitting the abort it had just issued.
	 */
	private boolean connectionClosed;

	/**
	 * Re-entrancy guard. Completing a write runs its continuation synchronously, and that continuation
	 * is normally the next {@code accept(...)} of the very same CSP pipeline — so a nested {@link #pump}
	 * is not exotic, it is the common case once a withheld write resumes. Letting the outer loop pick up
	 * the newly-set pending buffer instead keeps offset assignment in one place and keeps the batch to
	 * one {@link Sink#requestSend}.
	 */
	private boolean pumping;

	/**
	 * @param streamId              the stream this part belongs to (RFC 9000 §2.1)
	 * @param flowControl           the send-side controller, initialised from the <b>peer's</b>
	 *                              {@code initial_max_stream_data_*} for this stream type
	 *                              (RFC 9000 §18.2)
	 * @param connectionFlowControl the connection-wide controller, shared by every sending part
	 * @param maxFrameDataSize      the most payload one {@code STREAM} frame may carry, sized so the
	 *                              frame always fits a datagram (research R-08)
	 */
	public SendPart(
		long streamId, StreamFlowController flowControl, ConnectionFlowController connectionFlowControl,
		int maxFrameDataSize, Sink sink
	) {
		if (maxFrameDataSize < 1) {
			throw new IllegalArgumentException("maxFrameDataSize must be positive, got " + maxFrameDataSize);
		}
		this.streamId = streamId;
		this.flowControl = flowControl;
		this.connectionFlowControl = connectionFlowControl;
		this.maxFrameDataSize = maxFrameDataSize;
		this.sink = sink;
		this.consumer = new QuicStreamConsumer(this);
	}

	// ---------------------------------------------------------------- the application side

	/**
	 * The send half as a CSP consumer. {@code accept(buf)} <b>takes ownership of {@code buf}</b>;
	 * {@code accept(null)} writes the end-of-data marker (FIN).
	 */
	public ChannelConsumer<ByteBuf> consumer() {
		return consumer;
	}

	/** {@link #write(ByteBuf, boolean)} without a {@code FIN}. <b>Takes ownership of {@code buf}.</b> */
	public Promise<Void> write(ByteBuf buf) {
		return write(buf, false);
	}

	/**
	 * Writes {@code buf}, optionally marking its last byte as the end of the stream (RFC 9000 §19.8).
	 * <p>
	 * <b>Takes ownership of {@code buf} on every path</b>, including rejection and close. The promise
	 * resolves only once <em>every</em> byte has become a {@code STREAM} frame handed to the transport
	 * (FR-020, research R-06); while either flow-control limit or the outstanding budget blocks
	 * progress it stays pending.
	 *
	 * @throws IllegalStateException never — a write after the FIN, or after close, fails the returned
	 *                               promise instead, since a CSP consumer reports through its promise
	 */
	public Promise<Void> write(ByteBuf buf, boolean fin) {
		if (writeFailure != null) {
			// Aborted, or the connection ended: the typed failure is reported verbatim, so an application
			// that resets and writes again sees the reset rather than a generic "closed".
			buf.recycle();
			return Promise.ofException(writeFailure);
		}
		if (closed) {
			buf.recycle();
			return Promise.ofException(new IllegalStateException(
				"Stream " + streamId + " send part is closed"));
		}
		if (finalSize != null) {
			buf.recycle();
			return Promise.ofException(new IllegalStateException(
				"Stream " + streamId + " already wrote its end-of-data marker at offset " + finalSize));
		}
		if (pendingWrite != null) {
			buf.recycle();
			return Promise.ofException(new IllegalStateException(
				"Stream " + streamId + " already has a write in flight; a ChannelConsumer serialises writes"));
		}
		SettablePromise<Void> promise = new SettablePromise<>();
		pendingWrite = promise;
		pendingBuf = buf;
		pendingFin = fin;
		sink.onPendingWriteChanged(this, true);
		pump();
		return promise;
	}

	/**
	 * Writes the end-of-data marker on its own, as a zero-length {@code STREAM} frame carrying
	 * {@code FIN} at the current write offset (RFC 9000 §19.8). This is {@code accept(null)} on the
	 * consumer.
	 */
	public Promise<Void> writeFin() {
		// ByteBuf.empty() is a shared, un-refcounted zero-length buffer: slicing it copies nothing and
		// recycling it is a no-op, which is exactly right for a frame that carries no payload.
		return write(ByteBuf.empty(), true);
	}

	// ---------------------------------------------------------------- the transport side

	/**
	 * Turns as much of the pending buffer into {@code STREAM} frames as both flow-control limits and
	 * the outstanding budget permit, then requests <b>one</b> send for the whole batch (research R-03).
	 * <p>
	 * <b>This is also the resume entry point.</b> Nothing here schedules its own retry, deliberately:
	 * a withheld write is released by an <em>event</em> — a {@code MAX_STREAM_DATA} or {@code MAX_DATA}
	 * raising a limit, or an acknowledgement freeing outstanding budget — and the owning
	 * {@code QuicStreamManager}, which is the only thing that sees those events, calls this again. Any
	 * of them may of course leave the part exactly as blocked as it was, so a wasted call is harmless
	 * and expected; a <em>missing</em> one is a deadlock, which is why the entry point is one method
	 * rather than three conditions.
	 */
	void pump() {
		if (pumping || pendingBuf == null || isSendTerminated()) return;
		pumping = true;
		boolean enqueuedAny = false;
		try {
			while (pendingBuf != null && !isSendTerminated()) {
				long allowance = Math.min(
					Math.min(flowControl.available(), connectionFlowControl.sendAvailable()),
					sink.outstandingBytesAvailable());
				int chunk = (int) Math.min(Math.min(pendingBuf.readRemaining(), maxFrameDataSize),
					Math.max(0, allowance));
				boolean last = chunk == pendingBuf.readRemaining();
				if (chunk == 0 && !last) {
					// Blocked. The write stays pending and the buffer stays owned until something raises a
					// limit or frees budget and pumps again.
					enqueuedAny |= announceBlocked();
					break;
				}

				boolean fin = last && pendingFin;
				// A retained slice, never a copy (FR-014): the frame and the pending buffer share one array.
				StreamFrame frame = new StreamFrame(streamId, writeOffset, fin, pendingBuf.slice(chunk));
				pendingBuf.moveHead(chunk);

				// Accounted before the enqueue can throw, and never rolled back: an offset once assigned is
				// spent, exactly as on the receive side.
				writeOffset += chunk;
				flowControl.consume(chunk);
				connectionFlowControl.onBytesSent(chunk);
				outstandingBytes += chunk;
				unacknowledgedFrames++;
				sink.onOutstandingBytesChanged(chunk);
				if (state == SendState.READY) {
					state = SendState.SEND;
				}
				if (fin) {
					finalSize = writeOffset;
					state = SendState.DATA_SENT;
				}

				sink.enqueueFrame(frame);
				enqueuedAny = true;

				if (last) {
					completePendingWrite();
				}
			}
		} catch (QuicTransportException e) {
			// The send queue is full, or the frame was refused. Either is connection-fatal and the sink's
			// owner is what closes the connection; here the write simply fails, with its buffer.
			failPendingWrite(e);
		} finally {
			pumping = false;
		}
		if (enqueuedAny) {
			sink.requestSend();
		}
	}

	/**
	 * Tells the peer which limit is holding this writer (RFC 9000 §19.12–§19.13, FR-027).
	 * <p>
	 * The two limits are announced independently, because they bind independently and can bind at the
	 * same byte. Neither the outstanding budget nor anything else local is announced: the peer cannot
	 * act on a bound it did not set, and a frame it cannot act on is a frame that should not be sent.
	 *
	 * @return whether anything was queued, so the caller knows whether the batch still needs a flush
	 */
	private boolean announceBlocked() {
		boolean queued = false;
		if (flowControl.isBlocked()) {
			long limit = flowControl.limit();
			// A limit only rises, so "different from the last announced" is the whole of FR-027's rule.
			if (blockedAnnouncedAt != limit) {
				blockedAnnouncedAt = limit;
				queued = sink.onStreamDataBlocked(streamId, limit);
			}
		}
		if (connectionFlowControl.sendAvailable() == 0) {
			// De-duplicated by the sink, not here: the limit is shared by every sending part.
			queued |= sink.onDataBlocked(connectionFlowControl.sendLimit());
		}
		return queued;
	}

	private void completePendingWrite() {
		ByteBuf buf = pendingBuf;
		SettablePromise<Void> promise = pendingWrite;
		pendingBuf = null;
		pendingWrite = null;
		pendingFin = false;
		if (buf != null) buf.recycle();
		if (promise != null) {
			sink.onPendingWriteChanged(this, false);
			promise.set(null);
		}
	}

	private void failPendingWrite(Exception e) {
		ByteBuf buf = pendingBuf;
		SettablePromise<Void> promise = pendingWrite;
		pendingBuf = null;
		pendingWrite = null;
		pendingFin = false;
		if (buf != null) buf.recycle();
		if (promise != null) {
			sink.onPendingWriteChanged(this, false);
			promise.setException(e);
		}
	}

	/**
	 * A frame this part contributed has been acknowledged. <b>Takes ownership of {@code frame}</b> and
	 * recycles it.
	 * <p>
	 * This is the only event that frees outstanding budget (research R-08) and the only one that can
	 * complete the RFC 9000 §3.1 Data Sent → Data Recvd transition.
	 */
	public void onFrameAcknowledged(StreamFrame frame) {
		int length = frame.data.readRemaining();
		frame.recycle();
		outstandingBytes -= length;
		sink.onOutstandingBytesChanged(-length);
		unacknowledgedFrames--;
		if (state == SendState.DATA_SENT && unacknowledgedFrames == 0) {
			state = SendState.DATA_RECVD;
			sink.onTerminal(this);
		}
	}

	/**
	 * A frame this part contributed was declared lost. <b>Takes ownership of {@code frame}</b>, and
	 * hands it straight back to the transport at its original offsets (FR-017) — which is what makes
	 * stream data reliable, and why {@code QuicFrameHandler}'s recycling default is wrong for it.
	 * <p>
	 * The outstanding budget is deliberately <b>not</b> credited on that path: the bytes are still
	 * outstanding, they are being resent. Crediting them there would let the budget drift upward under
	 * sustained loss, which is exactly when it matters (research R-08).
	 * <p>
	 * Once this part has been aborted the opposite holds, and both halves of that are load-bearing: the
	 * frame is <b>released rather than resent</b> (FR-018, RFC 9000 §3.1 — a reset send part never
	 * resurrects its data), and precisely because it will never be resent its share of the budget
	 * <b>is</b> credited back, or the connection would lose that much capacity permanently.
	 * <p>
	 * An <em>abort</em> is the only thing that stops a retransmission; {@link #closed} deliberately is
	 * not. A part that wrote its {@code FIN} and whose pipeline then closed the writer is in RFC 9000
	 * §3.1's <b>Data Sent</b> state, which lasts until every frame is acknowledged and exists precisely
	 * so that what was already written stays reliable — treating "accepts no more writes" as "abandons
	 * what was written" loses the data of every normally-finished stream that meets a dropped packet.
	 * The one close that does end retransmission is the connection's own ({@link #connectionClosed}),
	 * because then there is no transport left to retransmit onto (FR-041).
	 */
	public void onFrameLost(StreamFrame frame) {
		if (isReset() || connectionClosed) {
			int length = frame.data.readRemaining();
			frame.recycle();
			releaseOutstanding(length);
			return;
		}
		try {
			// Ahead of everything already queued, not behind it: see Sink.requeueFrame.
			sink.requeueFrame(frame);
			sink.requestSend();
		} catch (QuicTransportException e) {
			// requeueFrame owns the frame on its throwing path too, so there is nothing left to release.
			failPendingWrite(e);
		}
	}

	/**
	 * Gives back the budget of bytes that will never be sent again, on loss after an abort.
	 * <p>
	 * The frame count is decremented whatever the length, because a zero-length frame — the bare
	 * {@code FIN} of RFC 9000 §19.8 — is still one frame this part is waiting on; only the byte
	 * accounting has nothing to give back.
	 */
	private void releaseOutstanding(int length) {
		unacknowledgedFrames--;
		if (length == 0) return;
		outstandingBytes -= length;
		sink.onOutstandingBytesChanged(-length);
	}

	/**
	 * The {@code STREAM_DATA_BLOCKED} that announced {@code limit} was declared lost (RFC 9000 §19.13,
	 * FR-027).
	 * <p>
	 * <b>Regenerated, never replayed</b> (RFC 9000 §13.3): what the peer needs is the limit holding this
	 * writer <em>now</em>, so the announcement bookkeeping is rewound and the ordinary blocked check is
	 * allowed to speak again — which re-announces at whatever the current limit is, or says nothing at
	 * all if the limit has since risen and this writer is no longer held by it.
	 * <p>
	 * A no-op unless the lost frame carried the value last announced: a later announcement already
	 * carried better information, and the stale one is moot.
	 */
	public void onStreamDataBlockedLost(long limit) {
		if (blockedAnnouncedAt != limit || connectionClosed) return;
		if (!flowControl.isBlocked() || pendingBuf == null || isSendTerminated()) return;
		blockedAnnouncedAt = -1;
		pump();
	}

	// ---------------------------------------------------------------- abrupt termination (RFC 9000 §3.5)

	/**
	 * Aborts this part with an application error code, conveyed reliably as {@code RESET_STREAM}
	 * (RFC 9000 §19.4, FR-031). Idempotent: a second call, or one after the part is already terminal,
	 * queues nothing.
	 * <p>
	 * The pending write fails with {@link QuicStreamResetException} rather than being left stranded
	 * (FR-036), and its withheld buffer is released with it.
	 */
	public void reset(long applicationErrorCode) {
		doReset(applicationErrorCode, new QuicStreamResetException(streamId, applicationErrorCode));
	}

	/**
	 * The peer asked this endpoint to stop sending (RFC 9000 §19.5), which RFC 9000 §3.5 makes into the
	 * very same transition, driven from the other end and carrying <em>the peer's</em> code (FR-033).
	 * <p>
	 * The pending write fails with {@link QuicStreamStopSendingException}, not with
	 * {@link QuicStreamResetException}: "the peer told me to stop" and "the peer gave up on its own
	 * half" are different facts, and only the first of them is something the application asked for.
	 */
	public void onStopSending(long applicationErrorCode) {
		doReset(applicationErrorCode, new QuicStreamStopSendingException(streamId, applicationErrorCode));
	}

	/**
	 * RFC 9000 §3.1's Ready/Send/Data Sent → Reset Sent transition: the final size is fixed at whatever
	 * has been given an offset, the {@code RESET_STREAM} is queued, and only then is the pending write
	 * failed — taking its unsent remainder with it.
	 * <p>
	 * That last ordering is deliberate, not incidental. Failing the write runs the application's
	 * continuation synchronously, and that continuation routinely writes, resets or closes again; doing
	 * it after the state has moved and the frame is queued is what makes every one of those a no-op
	 * rather than a second abort.
	 */
	private void doReset(long applicationErrorCode, Exception failure) {
		if (connectionClosed || isReset() || state == SendState.DATA_RECVD) return;
		resetErrorCode = applicationErrorCode;
		// Whatever has been given an offset is what the peer must expect (RFC 9000 §4.5); after a FIN this
		// is the value already there, since writeOffset stopped moving when the final size was fixed.
		finalSize = writeOffset;
		state = SendState.RESET_SENT;
		writeFailure = failure;
		sink.onReset(this);
		sink.enqueueReset(streamId, applicationErrorCode, writeOffset);
		failPendingWrite(failure);
	}

	/**
	 * The {@code RESET_STREAM} was acknowledged: RFC 9000 §3.1's Reset Sent → Reset Recvd, which is
	 * terminal and therefore a release condition (FR-006).
	 */
	public void onResetAcknowledged() {
		if (state != SendState.RESET_SENT) return;
		state = SendState.RESET_RECVD;
		sink.onTerminal(this);
	}

	/**
	 * The {@code RESET_STREAM} was declared lost. Unlike a lost {@code STREAM} frame of a reset part,
	 * this one is <b>re-enqueued</b>: RFC 9000 §19.4 makes the abort itself reliable, so the peer learns
	 * of it however many packets are dropped (FR-031). The frame is regenerated rather than replayed
	 * because its three fields were fixed at the abort and cannot have moved since.
	 * <p>
	 * A no-op once the reset has been acknowledged, or if this part was never reset at all — the frame
	 * has already done its job, and re-queueing it would put a second copy in the send queue.
	 */
	public void onResetLost() {
		Long code = resetErrorCode;
		if (code == null || connectionClosed || state != SendState.RESET_SENT) return;
		assert finalSize != null;
		sink.enqueueReset(streamId, code, finalSize);
	}

	// ---------------------------------------------------------------- teardown

	/**
	 * Releases the withheld buffer and fails its write with {@code e} (FR-041). Idempotent (WI-9);
	 * afterwards a write still takes ownership of its buffer and fails.
	 * <p>
	 * Deliberately leaves the RFC 9000 §3.1 state alone: this is also the path a CSP pipeline takes when
	 * it closes its consumer in reaction to the very write a {@link #reset} just failed, and a reset that
	 * forgot it had been reset would stop retransmitting its {@code RESET_STREAM}.
	 */
	public void closeEx(Exception e) {
		if (closed) return;
		closed = true;
		if (writeFailure == null) {
			writeFailure = e;
		}
		consumer.closeEx(e);
		failPendingWrite(e);
	}

	/**
	 * The connection ended before this part did (FR-041): {@link #closeEx}, and additionally the end of
	 * this part's own retransmissions — there is no longer a transport to retransmit onto.
	 */
	public void onConnectionClosed(Exception e) {
		connectionClosed = true;
		closeEx(e);
	}

	private boolean isSendTerminated() {
		return closed
			|| state == SendState.DATA_RECVD
			|| state == SendState.RESET_SENT
			|| state == SendState.RESET_RECVD;
	}

	/** Whether this part has been aborted, locally or by the peer's {@code STOP_SENDING}. */
	public boolean isReset() {
		return state == SendState.RESET_SENT || state == SendState.RESET_RECVD;
	}

	// ---------------------------------------------------------------- accessors

	/** The RFC 9000 §2.1 identifier of the stream this half belongs to. */
	public long streamId() {
		return streamId;
	}

	/** This half's RFC 9000 §3.1 sending state. */
	public SendState state() {
		return state;
	}

	/** The next absolute offset to assign. Never rewound. */
	public long writeOffset() {
		return writeOffset;
	}

	/** The highest offset the peer permits on this stream (RFC 9000 §19.10). */
	public long maxDataOffset() {
		return flowControl.limit();
	}

	/**
	 * The final size once the {@code FIN} has been written or the part has been aborted; else
	 * {@code null} (RFC 9000 §4.5). An abort fixes it at the highest offset assigned so far, which is
	 * what its {@code RESET_STREAM} carries.
	 */
	public @Nullable Long finalSize() {
		return finalSize;
	}

	/**
	 * The application error code this part was aborted with — by {@link #reset} or by the peer's
	 * {@code STOP_SENDING} — or {@code null} if it never was (RFC 9000 §19.4, §20.2).
	 */
	public @Nullable Long resetErrorCode() {
		return resetErrorCode;
	}

	/** This part's share of {@code maxOutstandingStreamBytes} currently in flight. */
	public long outstandingBytes() {
		return outstandingBytes;
	}

	/**
	 * Credits back whatever of this part's share of {@code maxOutstandingStreamBytes} is still
	 * outstanding, because this part is about to leave the manager's map for good (FR-006) and no
	 * {@link #onFrameAcknowledged} or {@link #onFrameLost} for its already-sent frames will ever be
	 * routed here again.
	 * <p>
	 * Reachable only on the {@link SendState#RESET_RECVD} path: the {@code RESET_STREAM} itself
	 * becoming acknowledged is what makes this part terminal, and unlike {@code Data Recvd} that
	 * transition does not wait for {@link #unacknowledgedFrames} to reach zero — some of this part's
	 * {@code STREAM} frames sent before the abort may still be genuinely in flight. FR-018 already
	 * guarantees none of them will ever be resent, so settling the budget now rather than waiting for a
	 * settlement that can no longer reach this part is correct, not merely convenient — without it the
	 * bytes are gone from the budget forever, which is exactly the kind of permanent leak the outstanding
	 * budget exists to prevent.
	 *
	 * @return whether any budget was actually credited back. A part whose outstanding frames are all
	 *         zero-length (the bare {@code FIN} of RFC 9000 §19.8) has frames to forget but no bytes to
	 *         give back, and only the bytes can have a writer waiting on them
	 */
	boolean settleOutstandingOnRelease() {
		long settled = outstandingBytes;
		if (settled > 0) {
			sink.onOutstandingBytesChanged(-settled);
			outstandingBytes = 0;
		}
		unacknowledgedFrames = 0;
		return settled > 0;
	}

	/**
	 * Whether a write is waiting on credit or budget — that is, whether {@link #pump()} has anything to
	 * do. The manager consults this before retrying every sending part on a connection-wide event, so a
	 * {@code MAX_DATA} does not walk into the state machine of streams that were never blocked.
	 */
	public boolean hasPendingWrite() {
		return pendingWrite != null;
	}

	/**
	 * The send-side controller — the limit a {@code MAX_STREAM_DATA} raises, and the one
	 * {@link #pump()} refuses to assign an offset at or beyond (RFC 9000 §19.10, FR-022).
	 */
	public StreamFlowController flowControl() {
		return flowControl;
	}

	/** Whether this part can no longer change state and the stream may be released (FR-006). */
	public boolean isTerminal() {
		return state == SendState.DATA_RECVD || state == SendState.RESET_RECVD;
	}

	/** Never prints buffered bytes: they are application data (SI-6). */
	@Override
	public String toString() {
		return "SendPart{stream=" + streamId +
			", " + state +
			", writeOffset=" + writeOffset + '/' + flowControl.limit() +
			", outstanding=" + outstandingBytes +
			(finalSize == null ? "" : ", finalSize=" + finalSize) +
			'}';
	}
}
