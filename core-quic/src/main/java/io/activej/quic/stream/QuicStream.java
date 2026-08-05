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
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * One QUIC stream (RFC 9000 §2): an ordered, reliable byte sequence multiplexed over a connection,
 * with a sending half, a receiving half, or both.
 *
 * <h2>Which halves exist (RFC 9000 §2.1)</h2>
 * <table>
 *   <caption>Halves per stream type and initiator</caption>
 *   <tr><th></th><th>Locally initiated</th><th>Peer initiated</th></tr>
 *   <tr><td>Bidirectional</td><td>send + receive</td><td>send + receive</td></tr>
 *   <tr><td>Unidirectional</td><td>send only</td><td>receive only</td></tr>
 * </table>
 * Touching an absent half through {@link #reader()} or {@link #writer()} is an
 * {@link IllegalStateException} — a caller bug, deliberately distinct from every wire error (FR-007).
 * {@link #sendState()} and {@link #receiveState()} stay total instead, reporting
 * {@link SendState#NONE} / {@link ReceiveState#NONE}.
 *
 * <h2>Buffer ownership (DI-1)</h2>
 * <ul>
 *   <li>{@code reader().get()} hands out an owned slice — <b>the caller recycles it</b>;
 *       {@code null} is end-of-stream.</li>
 *   <li>{@code writer().accept(buf)} <b>takes ownership of {@code buf}</b> on every path, including
 *       rejection and close; {@code accept(null)} writes the end-of-data marker.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Reactive, and confined to its connection's reactor: every public method opens with
 * {@code checkInReactorThread(this)} (FR-040, WI-1). Instances are created by
 * {@link QuicStreamManager}, never directly.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2">RFC 9000 §2 — Streams</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3">RFC 9000 §3 — Stream States</a>
 */
public final class QuicStream extends AbstractReactive {
	private final long streamId;
	private final boolean locallyInitiated;
	private final boolean earlyData;
	private final @Nullable SendPart sendPart;
	private final @Nullable ReceivePart receivePart;
	private final SettablePromise<Void> closePromise = new SettablePromise<>();

	private @Nullable EncryptionLevel arrivalLevel;

	QuicStream(
		Reactor reactor, long streamId, boolean locallyInitiated, boolean earlyData,
		@Nullable SendPart sendPart, @Nullable ReceivePart receivePart
	) {
		super(reactor);
		if (sendPart == null && receivePart == null) {
			throw new IllegalArgumentException("A stream with neither half cannot exist (RFC 9000 §2.1)");
		}
		this.streamId = streamId;
		this.locallyInitiated = locallyInitiated;
		this.earlyData = earlyData;
		this.sendPart = sendPart;
		this.receivePart = receivePart;
	}

	/** This stream's identifier (RFC 9000 §2.1). Immutable. */
	public long id() {
		checkInReactorThread(this);
		return streamId;
	}

	/** Whether both endpoints may send on this stream — bit 1 of the id clear (RFC 9000 §2.1). */
	public boolean isBidirectional() {
		checkInReactorThread(this);
		return StreamIds.isBidirectional(streamId);
	}

	/** Whether <em>this</em> endpoint opened this stream (RFC 9000 §2.1). */
	public boolean isLocallyInitiated() {
		checkInReactorThread(this);
		return locallyInitiated;
	}

	/**
	 * Whether this stream came into existence while application data would have left in a <b>0-RTT</b>
	 * packet (RFC 9001 §4.6.1, spec FR-055). Latched at construction and never revoked: it records where
	 * the stream <i>came from</i>, which stays true however the handshake then turns out.
	 * <p>
	 * It is what lets the layer above tell the work it created on a promise the peer had not yet made
	 * from the work it created afterwards, so that only the first kind is at risk when early data is
	 * refused. It is not "this stream's bytes travelled in 0-RTT packets" — some of them may have been
	 * re-levelled to 1-RTT before anything was flushed at all.
	 */
	public boolean isEarlyData() {
		checkInReactorThread(this);
		return earlyData;
	}

	/**
	 * The {@link EncryptionLevel} this stream's <b>incoming</b> data arrived at (RFC 9000 §12.3, spec
	 * FR-064a) — {@code ZERO_RTT} for data a peer sent in a 0-RTT packet, {@code ONE_RTT} for ordinary
	 * application data, and {@code null} while nothing has arrived on this stream at all.
	 * <p>
	 * This is the receiving counterpart of {@link #isEarlyData()}, and the two answer different
	 * questions: that one is latched from the <em>sending</em> side and is therefore {@code false} on a
	 * server for ever, while this one is what a server needs in order to tell work a peer created on a
	 * promise it had not yet kept — and which it may therefore be replaying — from work created
	 * afterwards.
	 * <p>
	 * {@code ZERO_RTT} once reported is never revoked, and a stream any part of whose data arrived in a
	 * 0-RTT packet reports {@code ZERO_RTT} whatever level the rest of it arrived at. That direction is
	 * the safe one: what matters to the layer above is whether <em>anything</em> here could be a replay,
	 * and the answer must not depend on how a peer chose to split its bytes across packets.
	 */
	public @Nullable EncryptionLevel arrivalLevel() {
		checkInReactorThread(this);
		return arrivalLevel;
	}

	/** Whether this endpoint owns the sending half (RFC 9000 §2.1). */
	public boolean hasSendPart() {
		checkInReactorThread(this);
		return sendPart != null;
	}

	/** Whether this endpoint owns the receiving half (RFC 9000 §2.1). */
	public boolean hasReceivePart() {
		checkInReactorThread(this);
		return receivePart != null;
	}

	/**
	 * The receive half as a CSP supplier. Each {@code get()} resolves with the next contiguous slice,
	 * or {@code null} at end-of-stream. <b>The caller owns and must recycle every buffer it takes.</b>
	 *
	 * @throws IllegalStateException if this endpoint has no receive part (FR-007)
	 */
	public ChannelSupplier<ByteBuf> reader() {
		checkInReactorThread(this);
		if (receivePart == null) {
			throw new IllegalStateException("Stream " + streamId +
				" has no receiving part: it is a locally-initiated unidirectional stream (RFC 9000 §2.1)");
		}
		return receivePart.supplier();
	}

	/**
	 * The send half as a CSP consumer. {@code accept(buf)} <b>takes ownership of {@code buf}</b> and
	 * resolves only once every one of its bytes has been handed to the transport (FR-020).
	 * {@code accept(null)} writes the end-of-data marker (FIN).
	 *
	 * @throws IllegalStateException if this endpoint has no send part (FR-007)
	 */
	public ChannelConsumer<ByteBuf> writer() {
		checkInReactorThread(this);
		if (sendPart == null) {
			throw new IllegalStateException("Stream " + streamId +
				" has no sending part: it is a peer-initiated unidirectional stream (RFC 9000 §2.1)");
		}
		return sendPart.consumer();
	}

	/**
	 * Aborts the send half with an application error code, conveyed reliably as {@code RESET_STREAM}
	 * (RFC 9000 §19.4, FR-031): the frame is retransmitted until acknowledged, while the stream data it
	 * supersedes is not retransmitted at all (FR-018).
	 * <p>
	 * Idempotent — a second call, or one after the half is already terminal, queues nothing (RFC 9000
	 * §3.1 has no transition out of a terminal state). Fails the pending write with
	 * {@link QuicStreamResetException} rather than stranding it (FR-036), releasing its buffer with it,
	 * and the final size is fixed at the highest offset already assigned (RFC 9000 §4.5).
	 * <p>
	 * The <em>receiving</em> half, on a bidirectional stream, is untouched: RFC 9000 §2.4 makes the two
	 * directions independent, and {@link #stopSending} is the other one's verb.
	 *
	 * @param applicationErrorCode a 62-bit code chosen by the application protocol (RFC 9000 §20.2);
	 *                             this layer neither interprets nor validates it
	 * @throws IllegalStateException if this endpoint has no send part (FR-007)
	 */
	public void reset(long applicationErrorCode) {
		checkInReactorThread(this);
		if (sendPart == null) {
			throw new IllegalStateException("Stream " + streamId + " has no sending part to reset");
		}
		sendPart.reset(applicationErrorCode);
	}

	/**
	 * Tells the peer to stop sending, as {@code STOP_SENDING} (RFC 9000 §19.5, FR-032), with an
	 * application error code; the frame is retransmitted until acknowledged or answered.
	 * <p>
	 * Idempotent. Everything buffered but undelivered is released at once, and subsequent reads fail
	 * with {@link QuicStreamStopSendingException} carrying this code — replaced by
	 * {@link QuicStreamResetException} carrying the <em>peer's</em> code once its {@code RESET_STREAM}
	 * answers (RFC 9000 §3.5). Bytes that keep arriving in the meantime are still accounted against
	 * flow control, and discarded rather than buffered (FR-035): the peer spent that credit before it
	 * could have heard, and refunding it would let a stream's window be spent twice.
	 * <p>
	 * The <em>sending</em> half, on a bidirectional stream, is untouched — {@link #reset} is its verb.
	 *
	 * @param applicationErrorCode a 62-bit code chosen by the application protocol (RFC 9000 §20.2)
	 * @throws IllegalStateException if this endpoint has no receive part (FR-007)
	 */
	public void stopSending(long applicationErrorCode) {
		checkInReactorThread(this);
		if (receivePart == null) {
			throw new IllegalStateException("Stream " + streamId + " has no receiving part to stop");
		}
		receivePart.stopSending(applicationErrorCode);
	}

	/**
	 * Completes when both owned halves are terminal and the stream has been released (FR-006); fails
	 * with the connection's exception, unwrapped, if the connection ended first (FR-041).
	 */
	public Promise<Void> whenClosed() {
		checkInReactorThread(this);
		return closePromise;
	}

	/** RFC 9000 §3.1, or {@link SendState#NONE} when this endpoint has no send part. */
	public SendState sendState() {
		checkInReactorThread(this);
		return sendPart == null ? SendState.NONE : sendPart.state();
	}

	/** RFC 9000 §3.2, or {@link ReceiveState#NONE} when this endpoint has no receive part. */
	public ReceiveState receiveState() {
		checkInReactorThread(this);
		return receivePart == null ? ReceiveState.NONE : receivePart.state();
	}

	// ---------------------------------------------------------------- manager-facing internals

	@Nullable SendPart sendPart() {
		return sendPart;
	}

	@Nullable ReceivePart receivePart() {
		return receivePart;
	}

	/**
	 * Records the level a {@code STREAM} frame for this stream arrived at, for {@link #arrivalLevel()}.
	 * <p>
	 * Not first-wins: {@code ZERO_RTT} overwrites a level already recorded and is never overwritten
	 * itself, because a stream whose bytes are partly replayable is a replayable stream — and a peer may
	 * legitimately send the beginning of one at 0-RTT and the rest at 1-RTT.
	 */
	void onDataArrived(EncryptionLevel level) {
		if (arrivalLevel == null || level == EncryptionLevel.ZERO_RTT) {
			arrivalLevel = level;
		}
	}

	/**
	 * Whether every owned half is terminal, which is the release condition of the data model's
	 * lifecycle rule (FR-006): a bidirectional stream needs both, a unidirectional one only the half
	 * it has.
	 */
	boolean isFullyTerminal() {
		return (sendPart == null || sendPart.isTerminal())
			&& (receivePart == null || receivePart.isTerminal());
	}

	/** Called once by the manager when the stream is removed from its map. */
	void onReleased() {
		closePromise.trySet(null);
	}

	/** Called by the manager when the connection ended before this stream did (FR-041). */
	void onConnectionClosed(Exception e) {
		// onConnectionClosed rather than closeEx: it is also the end of any RESET_STREAM or STOP_SENDING
		// this stream was still retransmitting, which an ordinary close of either half is not.
		if (sendPart != null) sendPart.onConnectionClosed(e);
		if (receivePart != null) receivePart.onConnectionClosed(e);
		closePromise.trySetException(e);
	}

	@Override
	public String toString() {
		return "QuicStream{" + streamId +
			(locallyInitiated ? ", local" : ", peer") +
			(StreamIds.isBidirectional(streamId) ? ", bidi" : ", uni") +
			", send=" + (sendPart == null ? SendState.NONE : sendPart.state()) +
			", receive=" + (receivePart == null ? ReceiveState.NONE : receivePart.state()) +
			'}';
	}
}
