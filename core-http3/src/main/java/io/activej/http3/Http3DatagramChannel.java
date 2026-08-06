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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.function.Consumer;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The per-exchange HTTP/3 datagram handle (RFC 9297, spec FR-084): unreliable, unordered payloads bound
 * to one request/response exchange, reached from the {@link io.activej.http.HttpMessage} that exchange
 * carries through {@link Http3Datagrams#of}.
 * <p>
 * <b>No QUIC stream ID appears anywhere in this API.</b> The quarter stream ID RFC 9297 §2.1 prefixes
 * every datagram with is written by {@link Http3DatagramTransport}, which is bound to one request stream
 * at construction and is the only object here that knows which.
 * <p>
 * <b>Deliberately not a CSP channel</b> (research D-8). CSP's contract promises a producer that a
 * withheld promise means "wait"; on a channel with no retransmission there is nothing to wait for, and
 * applying backpressure to an unreliable transport is a lie the API would be telling. A bounded
 * drop-oldest queue plus an optional {@linkplain #setReceiveHandler receive handler} is the honest
 * surface: the producer never waits, so no promise can misdescribe what happened.
 * <p>
 * <b>Ownership.</b> {@link #send} takes ownership of its payload on <b>every</b> path, refusals included.
 * {@link #poll} hands ownership of the buffer it returns to the caller. Everything still queued when the
 * exchange ends is recycled by {@link #close()}.
 * <p>
 * <b>Security (SI-6)</b>: every counter and every exception message here carries sizes and counts only,
 * never a byte of a payload.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297">RFC 9297 — HTTP Datagrams</a>
 */
public final class Http3DatagramChannel extends AbstractReactive {
	private final Http3DatagramTransport transport;
	private final int maxQueued;

	/**
	 * The bounded inbound queue (FR-085). Allocated in the constructor, which is reached only when
	 * {@link Http3Settings#datagramsEnabled()} — that is what makes FR-086's "allocates no queue" true
	 * for a consumer that never asked for datagrams: with them off no channel exists at all.
	 */
	private final ArrayDeque<ByteBuf> inbound;

	private @Nullable Consumer<ByteBuf> receiveHandler;
	private boolean closed;

	private long datagramsSent;
	private long datagramsReceived;
	private long datagramsDropped;
	private long datagramsRefused;

	/**
	 * Package-private: only an {@code Http3Connection} may build one, because only it can bind a
	 * transport to a request stream and answer whether datagrams were negotiated.
	 *
	 * @param maxQueued {@link Http3Settings#maxInboundDatagramsPerStream()}; {@code 0} accepts none
	 */
	Http3DatagramChannel(Reactor reactor, Http3DatagramTransport transport, int maxQueued) {
		super(reactor);
		this.transport = transport;
		this.maxQueued = maxQueued;
		this.inbound = new ArrayDeque<>(Math.min(maxQueued, 16));
	}

	// ---------------------------------------------------------------- sending

	/**
	 * Whether a datagram sent right now would be carried: both endpoints advertised
	 * {@code max_datagram_frame_size} and {@code SETTINGS_H3_DATAGRAM = 1}, both SETTINGS have been
	 * exchanged, and this exchange is still running (RFC 9297 §2.1.1, spec FR-083).
	 * <p>
	 * Queryable <b>before</b> the first send, which is the point: a caller with an alternative reliable
	 * path decides which to use without having to provoke a refusal.
	 */
	public boolean isAvailable() {
		checkInReactorThread(this);
		return !closed && transport.isAvailable();
	}

	/**
	 * The largest payload {@link #send} will accept — the peer's {@code max_datagram_frame_size} less the
	 * DATAGRAM frame's own overhead and this exchange's quarter stream ID. {@code 0} when datagrams are
	 * unavailable, so a caller never has to read {@link #isAvailable()} first to interpret it.
	 */
	public long maxPayloadSize() {
		checkInReactorThread(this);
		return closed ? 0 : transport.maxPayloadSize();
	}

	/**
	 * Sends {@code payload} as one HTTP/3 datagram bound to this exchange.
	 * <p>
	 * <b>Takes ownership of {@code payload} on every path, refusals included</b> — it is recycled before
	 * this throws, and recycling it again at the call site is a double free.
	 * <p>
	 * Refused rather than truncated, and refused <b>immediately</b> rather than queued: RFC 9221 §3
	 * forbids splitting a datagram across frames, and a payload held for a negotiation that may never
	 * complete would be unbounded state at the caller's discretion. A zero-length payload is legal and is
	 * delivered as a zero-length payload.
	 * <p>
	 * There is <b>no delivery promise</b>, by design. The transport neither retransmits a lost DATAGRAM
	 * frame (RFC 9221 §5) nor reports its loss to this layer as anything but a counter, so a promise here
	 * could only ever mean "handed to the transport" — which is what a normal return already means.
	 *
	 * @throws Http3DatagramException with {@link Http3DatagramException.Reason#EXCHANGE_ENDED},
	 *                                {@link Http3DatagramException.Reason#NOT_NEGOTIATED},
	 *                                {@link Http3DatagramException.Reason#OVERSIZE} or
	 *                                {@link Http3DatagramException.Reason#QUEUE_FULL}. None of the four
	 *                                is a protocol violation, and none of the four closes anything
	 */
	public void send(ByteBuf payload) throws Http3DatagramException {
		checkInReactorThread(this);
		if (closed) {
			throw refuse(payload, Http3DatagramException.Reason.EXCHANGE_ENDED,
				"this exchange has ended, so nothing further will be sent on it");
		}
		if (!transport.isAvailable()) {
			throw refuse(payload, Http3DatagramException.Reason.NOT_NEGOTIATED,
				"HTTP/3 datagrams are not available on this connection: both endpoints must advertise " +
				"max_datagram_frame_size and SETTINGS_H3_DATAGRAM = 1, and both SETTINGS must have been " +
				"exchanged (RFC 9297 §2.1.1)");
		}
		// Both bounds are checked before the combined buffer is allocated, so a refusal costs no
		// allocation at all — which is what makes a caller probing an unavailable connection cheap.
		long max = transport.maxPayloadSize();
		int size = payload.readRemaining();
		if (size > max) {
			transport.onRefusedOversize(size, max);
			throw refuse(payload, Http3DatagramException.Reason.OVERSIZE,
				"an HTTP/3 datagram payload of " + size + " bytes exceeds the " + max +
				" bytes this connection negotiated; RFC 9221 §3 forbids truncating it");
		}
		try {
			transport.send(payload);
		} catch (Http3DatagramException e) {
			datagramsRefused++;
			throw e;
		}
		datagramsSent++;
	}

	/** Releases {@code payload} and builds the refusal, so the call site reads as a {@code throw}. */
	private Http3DatagramException refuse(ByteBuf payload, Http3DatagramException.Reason reason, String message) {
		payload.recycle();
		datagramsRefused++;
		return new Http3DatagramException(reason, message);
	}

	// ---------------------------------------------------------------- receiving

	/**
	 * The oldest queued datagram, or {@code null} when none is queued. <b>Ownership passes to the
	 * caller</b>, which must recycle it.
	 * <p>
	 * A poll-only surface is unusable on a reactor — nothing would tell a caller when to poll — so
	 * {@link #setReceiveHandler} is the notification half. Polling stays available for a caller that
	 * drives its own schedule.
	 */
	public @Nullable ByteBuf poll() {
		checkInReactorThread(this);
		return inbound.poll();
	}

	/** How many datagrams are queued for this exchange, at most {@link Http3Settings#maxInboundDatagramsPerStream()}. */
	public int queuedCount() {
		checkInReactorThread(this);
		return inbound.size();
	}

	/**
	 * Delivers every datagram to {@code handler} as it arrives instead of queueing it, draining whatever
	 * is already queued into it first so nothing that arrived before the handler is lost. {@code null}
	 * returns this channel to queueing.
	 * <p>
	 * <b>The handler owns every buffer it is given</b> and must recycle it. A handler is not a
	 * backpressure surface: it is called synchronously from the transport's receive path, and there is
	 * nothing for a producer to wait on.
	 */
	public void setReceiveHandler(@Nullable Consumer<ByteBuf> handler) {
		checkInReactorThread(this);
		receiveHandler = handler;
		if (handler == null) return;
		// Drained by polling rather than iterating, so a handler that re-enters this channel finds the
		// queue in a consistent state at every step.
		ByteBuf buf;
		while ((buf = inbound.poll()) != null) {
			handler.accept(buf);
		}
	}

	// ---------------------------------------------------------------- counters (SI-6: numbers only)

	/** Datagrams handed to the transport on this exchange. Not a delivery count — nothing here is reliable. */
	public long datagramsSent() {
		checkInReactorThread(this);
		return datagramsSent;
	}

	/** Datagrams that arrived for this exchange, whether they were queued, delivered or dropped. */
	public long datagramsReceived() {
		checkInReactorThread(this);
		return datagramsReceived;
	}

	/**
	 * Inbound datagrams dropped because the queue was at {@code maxInboundDatagramsPerStream} — the
	 * oldest each time (FR-085) — or because this exchange had already ended.
	 * <p>
	 * Datagrams still queued when {@link #close()} drains the queue are <b>not</b> counted here: they
	 * were never dropped at a bound, and counting them would make this number report the end of every
	 * exchange rather than the pressure the bound was under.
	 */
	public long datagramsDropped() {
		checkInReactorThread(this);
		return datagramsDropped;
	}

	/** Sends refused locally — unavailable, oversize, queue-full or after the exchange ended. */
	public long datagramsRefused() {
		checkInReactorThread(this);
		return datagramsRefused;
	}

	// ---------------------------------------------------------------- lifecycle (the request stream's)

	/**
	 * One datagram has arrived for this exchange; <b>ownership passes in</b>.
	 * <p>
	 * At the bound the <b>oldest</b> queued datagram is dropped and counted, never the newest: a datagram
	 * is unreliable by contract, and holding the stale one in preference to the fresh one is the wrong
	 * way round for every use this surface has.
	 */
	void onDatagram(ByteBuf owned) {
		checkInReactorThread(this);
		datagramsReceived++;
		if (closed) {
			owned.recycle();
			datagramsDropped++;
			return;
		}
		Consumer<ByteBuf> handler = receiveHandler;
		if (handler != null) {
			handler.accept(owned);
			return;
		}
		if (maxQueued == 0) {
			owned.recycle();
			datagramsDropped++;
			transport.onDroppedByQueue();
			return;
		}
		if (inbound.size() >= maxQueued) {
			ByteBuf oldest = inbound.poll();
			if (oldest != null) oldest.recycle();
			datagramsDropped++;
			transport.onDroppedByQueue();
		}
		inbound.add(owned);
	}

	/**
	 * The exchange has ended: everything still queued is recycled and nothing further is accepted or sent.
	 * Idempotent — a second call finds an empty queue rather than recycling anything twice.
	 * <p>
	 * Called by {@link Http3RequestStream} on every terminal path of the exchange, which is the ownership
	 * split this pair is built on: the channel owns the queue, the request stream owns the channel.
	 */
	void close() {
		checkInReactorThread(this);
		if (closed) return;
		closed = true;
		receiveHandler = null;
		ByteBuf buf;
		while ((buf = inbound.poll()) != null) {
			buf.recycle();
		}
	}

	boolean isClosed() {
		return closed;
	}

	@Override
	public String toString() {
		return "Http3DatagramChannel{" + (closed ? "closed" : "open") +
			", queued=" + inbound.size() +
			", sent=" + datagramsSent +
			", received=" + datagramsReceived +
			", dropped=" + datagramsDropped +
			", refused=" + datagramsRefused + '}';
	}
}
