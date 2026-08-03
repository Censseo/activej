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

/**
 * Connection-level flow control — RFC 9000 §4.1: the limit that bounds the data an endpoint may send
 * <em>across all streams together</em>, carried by {@code MAX_DATA} (§19.9) and announced as blocked
 * by {@code DATA_BLOCKED} (§19.12).
 * <p>
 * Two independent windows, one per direction:
 * <ul>
 *   <li><b>send</b> — {@code sendLimit} is what the peer has granted us (its {@code initial_max_data}
 *       transport parameter, raised by every {@code MAX_DATA} it sends); {@code sendUsed} is the sum
 *       over all streams of the stream's write offset. A byte is never assigned an offset that would
 *       push the sum past the limit (FR-022).</li>
 *   <li><b>receive</b> — {@code receiveLimit} is what we have advertised; {@code receiveUsed} is the
 *       sum over all streams of the stream's <em>highest offset received</em>, and
 *       {@code consumedOffset} is the sum of what the application has actually taken.</li>
 * </ul>
 *
 * <h2>Why receive accounting is by highest offset, and why it is never refunded</h2>
 * {@code receiveUsed} is monotonically non-decreasing. Releasing a stream — for any reason, including
 * an abort by either side — does <b>not</b> subtract the credit that stream already consumed
 * (FR-023). This is the <em>credit-laundering</em> defence: a peer that could reclaim its spend by
 * aborting would open a stream, send a window's worth, abort, and repeat, buffering an unbounded
 * amount at the receiver without the receiver ever having granted more credit. RFC 9000 §4.5 fixes
 * the same accounting from the other end — the final size carried by {@code RESET_STREAM} is what
 * counts toward the connection window, whether or not those bytes were ever delivered.
 * <p>
 * What <em>does</em> move on release is {@link #consumedOffset}: bytes the receiver discarded are
 * bytes it is no longer holding, so they count as consumed and the window may advance above them
 * ({@link #onStreamReleased}). Skipping that would shrink the usable connection window by the size of
 * every aborted stream, permanently.
 *
 * <h2>Granting credit</h2>
 * Credit is granted once the application has consumed at least half of the window currently
 * advertised (FR-025, {@code WINDOW_UPDATE_FRACTION = 1/2}), not when the peer says it is blocked.
 * The new absolute limit is {@code consumedOffset + window}, restoring the full configured window
 * above the consumed offset. The fraction is a deliberate constant and not an
 * {@link io.activej.common.ApplicationSettings} key (clarification Q5): values near 1 flood the peer
 * with {@code MAX_DATA} frames, values near 0 stall it for a full window every time.
 * <p>
 * <b>Pure</b>: no {@link io.activej.reactor.Reactor}, no {@code Promise}, no
 * {@link io.activej.bytebuf.ByteBuf}. Not thread-safe — the owning {@code QuicStreamManager} provides
 * reactor confinement. Its arithmetic bounds peer-driven memory, so it is unconditional and never
 * gated behind {@link io.activej.common.Checks} (SI-1, WI-10).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.2">RFC 9000 §4.2 — Increasing Flow Control Limits</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.5">RFC 9000 §4.5 — Stream Final Size</a>
 */
public final class ConnectionFlowController {
	/**
	 * {@code WINDOW_UPDATE_FRACTION = 1 / WINDOW_UPDATE_FRACTION_DIVISOR} — one half (FR-025,
	 * clarification Q5). Expressed as an integer divisor rather than a {@code double} so the
	 * threshold stays exact for window sizes up to the 2^62-1 offset ceiling.
	 * <p>
	 * The stream-level controller carries the same fraction; they are stated independently because
	 * the two classes are otherwise unrelated, not because the values may drift.
	 */
	public static final int WINDOW_UPDATE_FRACTION_DIVISOR = 2;

	private long sendLimit;
	private long sendUsed;

	private long receiveLimit;
	private long receiveUsed;
	private long consumedOffset;

	private ConnectionFlowController(long sendLimit, long receiveLimit) {
		this.sendLimit = sendLimit;
		this.receiveLimit = receiveLimit;
	}

	/**
	 * @param initialSendLimit    the peer's {@code initial_max_data} — how much we may send before it
	 *                            grants more
	 * @param initialReceiveLimit our own {@code initial_max_data} — how much the peer may send before
	 *                            we grant more
	 */
	public static ConnectionFlowController create(long initialSendLimit, long initialReceiveLimit) {
		checkNonNegative(initialSendLimit, "initialSendLimit");
		checkNonNegative(initialReceiveLimit, "initialReceiveLimit");
		return new ConnectionFlowController(initialSendLimit, initialReceiveLimit);
	}

	// region send side — RFC 9000 §4.1

	/** The highest total offset we may reach across all streams. Never decreases. */
	public long sendLimit() {
		return sendLimit;
	}

	/** The sum over all streams of the stream's write offset. Never decreases. */
	public long sendUsed() {
		return sendUsed;
	}

	/**
	 * How many more bytes may be assigned an offset across all streams, floored at 0.
	 * <p>
	 * Floored rather than signed because, unlike the receive side, an overdraft here can only be a
	 * local bug: charges are gated by {@link #canSend(long)} and the limit only ever rises. Following
	 * {@code AmplificationBudget}, the true total is still kept — an overdraft is never forgiven.
	 */
	public long sendAvailable() {
		return Math.max(0, sendLimit - sendUsed);
	}

	/** Whether {@code bytes} more may be sent under the current limit. Overflow-safe. */
	public boolean canSend(long bytes) {
		return bytes <= sendAvailable();
	}

	/**
	 * Charges {@code bytes} to the connection-level send window — the amount by which one stream's
	 * write offset advanced.
	 * <p>
	 * Callers gate this with {@link #canSend(long)}; the charge itself is recorded unconditionally so
	 * a bug shows up as a stalled sender rather than as silently exceeding the peer's limit.
	 */
	public void onBytesSent(long bytes) {
		checkNonNegative(bytes, "bytes");
		sendUsed = saturatingAdd(sendUsed, bytes);
	}

	/**
	 * Applies a {@code MAX_DATA} frame (RFC 9000 §19.9). A value at or below the current limit is
	 * ignored without error (FR-026, RFC 9000 §4.1) — frames may be reordered, and a stale limit is a
	 * normal event rather than a protocol violation.
	 *
	 * @return whether the limit was actually raised (only then is a blocked sender worth waking)
	 */
	public boolean onMaxData(long maximumData) {
		if (maximumData <= sendLimit) return false;
		sendLimit = maximumData;
		return true;
	}

	// endregion

	// region receive side — RFC 9000 §4.1

	/** The highest total offset we have advertised to the peer. Never decreases (FR-026). */
	public long receiveLimit() {
		return receiveLimit;
	}

	/**
	 * The sum over all streams of the stream's highest offset received. Never decreases, and in
	 * particular is <b>not</b> reduced when a stream is reset or released (FR-023).
	 */
	public long receiveUsed() {
		return receiveUsed;
	}

	/**
	 * {@code receiveLimit - receiveUsed}. <b>May be negative</b>, and a negative value <em>is</em> the
	 * {@code FLOW_CONTROL_ERROR} condition — this is the asymmetry with {@link #sendAvailable()},
	 * which is floored because only a local bug can drive it below zero.
	 */
	public long receiveAvailable() {
		return receiveLimit - receiveUsed;
	}

	/** The sum over all streams of the bytes the application has taken, plus bytes discarded on release. */
	public long consumedOffset() {
		return consumedOffset;
	}

	/**
	 * Charges the connection-level receive window with the amount by which one stream's highest
	 * received offset advanced. The same call accounts for the final size learned from a
	 * {@code RESET_STREAM} frame (RFC 9000 §4.5), which may exceed the highest offset ever delivered.
	 * <p>
	 * The charge is applied even when it overruns the limit — the peer has already spent the credit,
	 * and the connection is about to be closed anyway. The addition saturates at
	 * {@link Long#MAX_VALUE} rather than wrapping, so an overrun can never be made to look like an
	 * underspend.
	 *
	 * @return {@code false} if the peer has now exceeded the advertised limit; the caller closes the
	 * connection with {@code FLOW_CONTROL_ERROR} (FR-023). This class never throws a wire exception:
	 * that decision belongs to the manager.
	 */
	public boolean onBytesReceived(long bytes) {
		checkNonNegative(bytes, "bytes");
		receiveUsed = saturatingAdd(receiveUsed, bytes);
		return receiveUsed <= receiveLimit;
	}

	/** Records bytes the application has taken; drives the {@link #shouldGrantReceiveCredit} threshold. */
	public void onBytesConsumed(long bytes) {
		checkNonNegative(bytes, "bytes");
		consumedOffset = saturatingAdd(consumedOffset, bytes);
	}

	/**
	 * Folds a released stream's receive accounting back into the connection (FR-006).
	 * <p>
	 * The bytes that stream was charged but the application never took are discarded, so they count as
	 * consumed and the window may advance above them. {@link #receiveUsed} is deliberately left
	 * untouched: an abort does not return credit the peer already spent (FR-023).
	 *
	 * @param streamHighestOffsetReceived the stream's final accounted offset — its highest offset
	 *                                    received, or its final size if that is higher (RFC 9000 §4.5)
	 * @param streamConsumedOffset        how much of it the application actually took
	 */
	public void onStreamReleased(long streamHighestOffsetReceived, long streamConsumedOffset) {
		checkNonNegative(streamHighestOffsetReceived, "streamHighestOffsetReceived");
		checkNonNegative(streamConsumedOffset, "streamConsumedOffset");
		if (streamHighestOffsetReceived > streamConsumedOffset) {
			onBytesConsumed(streamHighestOffsetReceived - streamConsumedOffset);
		}
	}

	/**
	 * Whether at least half of the advertised window has been consumed and a {@code MAX_DATA} frame is
	 * therefore due (FR-025).
	 *
	 * @param receiveWindow the configured {@code initial_max_data} — the window size to restore
	 */
	public boolean shouldGrantReceiveCredit(long receiveWindow) {
		checkNonNegative(receiveWindow, "receiveWindow");
		return receiveLimit - consumedOffset <= receiveWindow / WINDOW_UPDATE_FRACTION_DIVISOR;
	}

	/**
	 * Raises the advertised limit to {@code consumedOffset + receiveWindow} (FR-025) and returns it,
	 * for a {@code MAX_DATA} frame. Never lowers the limit, so a smaller window or a stale call is a
	 * no-op; calling it twice without intervening consumption returns the same value, which is exactly
	 * what RFC 9000 §13.3 requires of a retransmitted limit.
	 *
	 * @return the new advertised limit
	 */
	public long grantReceiveCredit(long receiveWindow) {
		checkNonNegative(receiveWindow, "receiveWindow");
		receiveLimit = Math.max(receiveLimit, saturatingAdd(consumedOffset, receiveWindow));
		return receiveLimit;
	}

	// endregion

	private static long saturatingAdd(long a, long b) {
		long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}

	private static void checkNonNegative(long value, String name) {
		if (value < 0) throw new IllegalArgumentException(name + " must not be negative, got " + value);
	}

	@Override
	public String toString() {
		return "ConnectionFlowController{" +
			"sendUsed=" + sendUsed + '/' + sendLimit +
			", receiveUsed=" + receiveUsed + '/' + receiveLimit +
			", consumed=" + consumedOffset +
			'}';
	}
}
