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

import io.activej.common.Checks;

import static io.activej.common.Checks.checkState;

/**
 * Stream-level flow control for <b>one direction</b> of one stream (RFC 9000 §4.1). Two counters —
 * an absolute {@code limit} and the highest offset {@code used} beneath it — plus the RFC 9000 §4.2
 * rules for moving them.
 * <p>
 * One instance per direction, and the direction decides how the two counters are read:
 * <table>
 *   <caption>Per-direction reading of the same two counters</caption>
 *   <tr><th></th><th>Sending part</th><th>Receiving part</th></tr>
 *   <tr><td>{@code limit}</td>
 *       <td>the highest offset the <i>peer</i> permits, from its {@code MAX_STREAM_DATA}</td>
 *       <td>the highest offset <i>this endpoint</i> advertises to the peer</td></tr>
 *   <tr><td>{@code used}</td>
 *       <td>the write offset — bytes already assigned an offset, moved by {@link #consume}</td>
 *       <td>the highest offset received, moved by {@link #advanceUsedTo}</td></tr>
 *   <tr><td>{@link #available()} &lt; 0</td>
 *       <td>impossible: {@link #consume} is never called past the limit</td>
 *       <td><b>is</b> the {@code FLOW_CONTROL_ERROR} condition — see {@link #isOverrun()}</td></tr>
 * </table>
 * <p>
 * The receiving part's <i>consumed</i> offset — what the application has actually read — is not held
 * here, because it moves independently of everything above (the reader, not the wire, moves it). It
 * is passed in to {@link #shouldGrantCredit} and {@link #grantedLimit}, which are the two decisions
 * that need it.
 * <p>
 * Pure: no {@link io.activej.reactor.Reactor}, no {@code Promise}, no {@link io.activej.bytebuf.ByteBuf}.
 * Not thread-safe — the owning stream provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.2">RFC 9000 §4.2 — Increasing Flow Control Limits</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.10">RFC 9000 §19.10 — MAX_STREAM_DATA Frames</a>
 */
public final class StreamFlowController {
	private static final boolean CHECKS = Checks.isEnabled(StreamFlowController.class);

	/**
	 * The share of the advertised window that must be consumed before more credit is granted
	 * (FR-025, clarification Q5): a receiver grants a new absolute limit of
	 * {@code consumed + window} once the application has read at least half of the window it
	 * advertised.
	 * <p>
	 * <b>Deliberately not an {@code ApplicationSettings} key.</b> Half is what every mainstream QUIC
	 * stack uses, and it is the value that keeps at most one credit-granting frame in flight per
	 * window while never leaving the sender waiting for credit on a path shorter than half a window
	 * of RTT. Values near 1 flood the peer with {@code MAX_STREAM_DATA} frames; values near 0 stall it
	 * for a full window every time. Neither failure is worth exposing as a knob.
	 * <p>
	 * The threshold it produces is a heuristic, so the {@code double} multiplication in
	 * {@link #shouldGrantCredit} is exact enough for every window a deployment would configure.
	 */
	public static final double WINDOW_UPDATE_FRACTION = 0.5;

	/** RFC 9000 §19.10: a limit is a variable-length integer, so it cannot exceed 2^62 - 1. */
	public static final long MAX_LIMIT = (1L << 62) - 1;

	private long limit;
	private long used;

	/**
	 * @param initialLimit the initial absolute limit — the peer's {@code initial_max_stream_data_*}
	 *                     transport parameter on a sending part, this endpoint's own on a receiving
	 *                     part (RFC 9000 §4.1)
	 */
	public StreamFlowController(long initialLimit) {
		if (initialLimit < 0 || initialLimit > MAX_LIMIT) {
			throw new IllegalArgumentException("initialLimit must be within [0, 2^62-1], got " + initialLimit);
		}
		this.limit = initialLimit;
	}

	/** The absolute maximum offset currently permitted on this direction (RFC 9000 §4.1). */
	public long limit() {
		return limit;
	}

	/**
	 * The highest offset used so far: the write offset on a sending part, the highest offset received
	 * on a receiving part. Never decreases.
	 */
	public long used() {
		return used;
	}

	/**
	 * {@code limit - used}. On a sending part this is how many more bytes may be given offsets, and is
	 * never negative (FR-021). On a receiving part a <b>negative</b> value is the peer overrunning the
	 * advertised limit — see {@link #isOverrun()} (FR-024).
	 */
	public long available() {
		return limit - used;
	}

	/** Sending part: whether a writer with bytes to send is currently held by this limit (FR-027). */
	public boolean isBlocked() {
		return used >= limit;
	}

	/**
	 * Raises the limit to {@code newLimit}, or leaves it alone if that would not be an increase.
	 * <p>
	 * RFC 9000 §4.1 requires a limit lower than one already in force to be ignored <b>without
	 * error</b> — frames carrying limits can be reordered, so a stale {@code MAX_STREAM_DATA} is a
	 * routine event, not a protocol violation (FR-026).
	 *
	 * @return {@code true} if the limit actually moved — on a sending part, the signal to retry a
	 * blocked writer; on a receiving part, the signal that a {@code MAX_STREAM_DATA} is worth sending
	 */
	public boolean raiseLimit(long newLimit) {
		if (newLimit <= limit) return false;
		limit = Math.min(newLimit, MAX_LIMIT);
		return true;
	}

	/**
	 * Sending part: records that {@code bytes} more bytes have been assigned stream offsets
	 * (RFC 9000 §4.1). The caller must not exceed {@link #available()} — a byte is never given an
	 * offset the peer has not permitted, which is what keeps {@link #available()} non-negative on this
	 * side (FR-021).
	 */
	public void consume(long bytes) {
		if (bytes < 0) {
			throw new IllegalArgumentException("bytes must not be negative, got " + bytes);
		}
		if (CHECKS) checkState(bytes <= limit - used, "consuming past the stream flow control limit");
		used += bytes;
	}

	/**
	 * Receiving part: records that data up to (but excluding) {@code offset} has arrived on this
	 * stream. Monotone — a reordered frame ending below the high-water mark gives no credit back,
	 * because the peer has already spent it (RFC 9000 §4.1, FR-023).
	 */
	public void advanceUsedTo(long offset) {
		if (offset > used) {
			used = offset;
		}
	}

	/**
	 * Receiving part: whether an incoming range ending at {@code endOffset} would exceed the
	 * advertised limit, checked <b>before</b> any of it is accepted (FR-024). A {@code true} result is
	 * the {@code FLOW_CONTROL_ERROR} condition of RFC 9000 §4.1.
	 */
	public boolean exceedsLimit(long endOffset) {
		return endOffset > limit;
	}

	/**
	 * Receiving part: whether the peer has already sent past the advertised limit —
	 * {@code FLOW_CONTROL_ERROR} (RFC 9000 §4.1, FR-024). Equivalent to {@code available() < 0}, named
	 * so the receive-side reading of a negative {@link #available()} is not left to the caller.
	 */
	public boolean isOverrun() {
		return used > limit;
	}

	/**
	 * Receiving part: whether more credit is due — that is, whether the application has consumed at
	 * least {@link #WINDOW_UPDATE_FRACTION} of the currently advertised window (FR-025, clarification
	 * Q5).
	 * <p>
	 * The window above the consumed offset is {@code limit - consumed}; the grant is due once that has
	 * shrunk to half of {@code window} or less. Credit is granted proactively — without waiting for
	 * the peer to announce that it is blocked.
	 *
	 * @param consumed the offset up to which the application has read; not held by this class,
	 *                 because the reader moves it independently of the wire
	 * @param window   the configured receive window for this stream —
	 *                 {@code initialMaxStreamData*}
	 */
	public boolean shouldGrantCredit(long consumed, long window) {
		checkGrantArguments(consumed, window);
		return limit - consumed <= (long) (window * WINDOW_UPDATE_FRACTION);
	}

	/**
	 * Receiving part: the new absolute limit to advertise — {@code consumed + window}, restoring the
	 * full configured window above the consumed offset (FR-025), clamped to {@link #MAX_LIMIT} so it
	 * always fits a variable-length integer.
	 * <p>
	 * A pure function of its arguments: it neither reads nor moves this controller's state. The caller
	 * hands the result to both {@link #raiseLimit} and the {@code MAX_STREAM_DATA} frame, so the
	 * advertised value and the enforced one cannot drift apart.
	 */
	public long grantedLimit(long consumed, long window) {
		checkGrantArguments(consumed, window);
		long granted = consumed + window;
		// consumed + window can overflow only near 2^62, where the clamp is the right answer anyway
		return granted < 0 || granted > MAX_LIMIT ? MAX_LIMIT : granted;
	}

	private static void checkGrantArguments(long consumed, long window) {
		if (consumed < 0) {
			throw new IllegalArgumentException("consumed must not be negative, got " + consumed);
		}
		if (window <= 0) {
			throw new IllegalArgumentException("window must be positive, got " + window);
		}
	}

	@Override
	public String toString() {
		return "StreamFlowController{used=" + used + '/' + limit + '}';
	}
}
