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

/**
 * The RFC 9002 §7 NewReno congestion controller: one window, halved on a congestion event, grown by an
 * acknowledged byte in slow start and by a fraction of one in congestion avoidance.
 * <p>
 * Reactor-free and clock-free — every method that needs a time is given one, so the whole state machine
 * is testable by arithmetic (constitution §IV, FR-039).
 * <p>
 * <b>The recovery period is the part worth reading twice.</b> A congestion event halves the window
 * <i>once</i> per round trip, not once per lost packet: every packet sent before the event is likely to
 * be lost too, and halving for each would collapse the window to its floor on a single burst loss. The
 * period is delimited by send time, not by receive time — {@link #inRecovery(long)} asks "was this
 * packet already in flight when we reduced?", which is the only question that distinguishes new
 * information from the echo of what we already reacted to.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7">RFC 9002 §7 — Congestion Control</a>
 */
public final class NewRenoCongestionController {
	/** RFC 9002 §7.3.1 {@code kLossReductionFactor}: the window is halved on a congestion event. */
	public static final int LOSS_REDUCTION_DIVISOR = 2;

	/** RFC 9002 §7.2 {@code kMinimumWindow}, in datagrams: the window never falls below two packets. */
	public static final int MINIMUM_WINDOW_DATAGRAMS = 2;

	/** Which of the RFC 9002 §7 growth regimes the controller is in. Diagnostic; the arithmetic is the same. */
	public enum State {
		SLOW_START, RECOVERY, CONGESTION_AVOIDANCE
	}

	private final int maxDatagramSize;
	private final long minimumWindow;

	private long congestionWindow;
	/** The window at which slow start ends. {@code Long.MAX_VALUE} until the first congestion event. */
	private long slowStartThreshold = Long.MAX_VALUE;
	private long bytesInFlight;
	/**
	 * The send time at which the current recovery period began, or 0 when there is none. A packet sent at
	 * or before this instant carries no new congestion information.
	 */
	private long recoveryStartTime;

	private long congestionEvents;
	private long persistentCongestionEpisodes;

	public NewRenoCongestionController(int maxDatagramSize, long initialWindow) {
		if (maxDatagramSize < 1) {
			throw new IllegalArgumentException("maxDatagramSize must be positive, got " + maxDatagramSize);
		}
		this.maxDatagramSize = maxDatagramSize;
		this.minimumWindow = (long) MINIMUM_WINDOW_DATAGRAMS * maxDatagramSize;
		if (initialWindow < minimumWindow) {
			throw new IllegalArgumentException("initialWindow (" + initialWindow + ") is below the RFC 9002 §7.2 " +
				"minimum of " + minimumWindow + " bytes");
		}
		this.congestionWindow = initialWindow;
	}

	/** The controller a connection's settings describe (RFC 9002 §7.2, via {@code initialCongestionWindow}). */
	public static NewRenoCongestionController of(QuicConnectionSettings settings) {
		return new NewRenoCongestionController(settings.maxDatagramSize(), settings.initialCongestionWindow());
	}

	// ---------------------------------------------------------------- the window

	/**
	 * Bytes that may be sent right now: the window less what is already in flight.
	 * <p>
	 * Zero does not mean "wait forever" — ACK-only packets, probes and CONNECTION_CLOSE are exempt
	 * (RFC 9002 §7), because a controller that could silence acknowledgements would deadlock the very
	 * feedback it depends on.
	 */
	public long available() {
		return Math.max(0, congestionWindow - bytesInFlight);
	}

	public boolean isBlocked() {
		return available() <= 0;
	}

	/** Records a packet counted as in flight — ack-eliciting or carrying data, never an ACK-only packet. */
	public void onPacketSent(long bytes) {
		bytesInFlight += bytes;
	}

	/**
	 * RFC 9002 §7.3.2: an acknowledged byte grows the window by a byte in slow start, and by
	 * {@code max_datagram_size / cwnd} of one in congestion avoidance.
	 *
	 * @param sentTime when the acknowledged packet was sent — a packet from before the current recovery
	 *                 period is <b>not</b> allowed to grow the window, or the reduction we just made
	 *                 would be undone by the acknowledgements already on their way
	 */
	public void onPacketAcked(long bytes, long sentTime) {
		bytesInFlight = Math.max(0, bytesInFlight - bytes);
		if (inRecovery(sentTime)) {
			return;
		}
		if (congestionWindow < slowStartThreshold) {
			congestionWindow += bytes;
			return;
		}
		// Integer arithmetic on purpose: this is a byte count, and accumulating a double here would make
		// the trace irreproducible across platforms for no gain in accuracy.
		congestionWindow += Math.max(1, (long) maxDatagramSize * bytes / congestionWindow);
	}

	/**
	 * RFC 9002 §7.3.1: a congestion event halves the window, but at most once per recovery period.
	 *
	 * @param largestLostSentTime the send time of the newest lost packet — what decides whether this is
	 *                            news or the tail of an event already reacted to
	 * @param now                 the current time, which becomes the new recovery period's start
	 */
	public void onCongestionEvent(long largestLostSentTime, long now) {
		if (inRecovery(largestLostSentTime)) {
			return;
		}
		congestionEvents++;
		recoveryStartTime = now;
		slowStartThreshold = Math.max(minimumWindow, congestionWindow / LOSS_REDUCTION_DIVISOR);
		congestionWindow = slowStartThreshold;
	}

	/** Removes lost bytes from the in-flight count. Separate from the window reduction, which is per event. */
	public void onPacketsLost(long bytes) {
		bytesInFlight = Math.max(0, bytesInFlight - bytes);
	}

	/**
	 * RFC 9002 §7.6: persistent congestion collapses the window to the minimum and restarts slow start.
	 * <p>
	 * Unlike an ordinary congestion event this is not halving but a reset: losing everything across a
	 * span longer than three probe timeouts is evidence the path's capacity is unknown again, not merely
	 * that it is smaller. The threshold is left untouched deliberately — {@code cwnd < ssthresh} is what
	 * puts the controller back in slow start, so it can find the new capacity quickly.
	 */
	public void onPersistentCongestion() {
		persistentCongestionEpisodes++;
		congestionWindow = minimumWindow;
		recoveryStartTime = 0;
	}

	/**
	 * Removes a discarded packet number space's packets from the in-flight count (RFC 9002 §B.9).
	 * <p>
	 * A discarded space's packets are neither lost nor acknowledged — they stop existing — so their bytes
	 * must leave the in-flight count without any window reduction. Forgetting this is how a connection
	 * ends up permanently congestion-blocked by handshake packets it will never hear about again.
	 */
	public void onSpaceDiscarded(long bytes) {
		bytesInFlight = Math.max(0, bytesInFlight - bytes);
	}

	/** Whether a packet sent at {@code sentTime} was already in flight when the window was last reduced. */
	public boolean inRecovery(long sentTime) {
		return recoveryStartTime != 0 && sentTime <= recoveryStartTime;
	}

	public State state() {
		if (recoveryStartTime != 0 && congestionWindow == slowStartThreshold) return State.RECOVERY;
		return congestionWindow < slowStartThreshold ? State.SLOW_START : State.CONGESTION_AVOIDANCE;
	}

	public long congestionWindow() {
		return congestionWindow;
	}

	public long slowStartThreshold() {
		return slowStartThreshold;
	}

	public long bytesInFlight() {
		return bytesInFlight;
	}

	public long minimumWindow() {
		return minimumWindow;
	}

	public long congestionEvents() {
		return congestionEvents;
	}

	public long persistentCongestionEpisodes() {
		return persistentCongestionEpisodes;
	}

	@Override
	public String toString() {
		return "NewRenoCongestionController{" + state() +
			", cwnd=" + congestionWindow +
			", ssthresh=" + (slowStartThreshold == Long.MAX_VALUE ? "none" : slowStartThreshold) +
			", inFlight=" + bytesInFlight +
			", events=" + congestionEvents +
			'}';
	}
}
