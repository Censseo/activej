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
 * The RFC 9002 §5 round-trip-time estimator: {@code min_rtt}, {@code latest_rtt},
 * {@code smoothed_rtt} and {@code rttvar}, in whole milliseconds.
 * <p>
 * Reactor-free and clock-free by design — every method takes the times it needs. Milliseconds rather
 * than microseconds because that is the resolution of {@code Reactor.currentTimeMillis()} and of the
 * timer wheel that consumes these values; a finer unit here would be arithmetic precision the
 * scheduler cannot act on. {@link #GRANULARITY_MILLIS} is the floor that keeps a sub-millisecond path
 * from producing a zero timeout.
 * <p>
 * <b>The {@code ack_delay} subtraction is the subtle part.</b> A peer reports how long it sat on an
 * ACK, and that delay is not network time — but the report is the peer's own claim, so RFC 9002 §5.3
 * bounds it by the {@code max_ack_delay} the peer advertised, and refuses to subtract at all if doing
 * so would push the sample below {@code min_rtt}. Without both guards a peer could inflate its
 * reported delay and drive our RTT estimate — and therefore our loss and probe timers — arbitrarily
 * low, manufacturing spurious retransmissions.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-5">RFC 9002 §5 — Estimating the Round-Trip Time</a>
 */
public final class RttEstimator {
	/** RFC 9002 §6.1.2 {@code kGranularity}: the timer granularity, and the floor on every timeout. */
	public static final long GRANULARITY_MILLIS = 1;

	/** RFC 9002 §6.2.2 {@code kInitialRtt}: the assumed RTT before the first sample. */
	public static final long INITIAL_RTT_MILLIS = 333;

	private long latestRtt;
	private long minRtt;
	private long smoothedRtt = INITIAL_RTT_MILLIS;
	private long rttVar = INITIAL_RTT_MILLIS / 2;
	private boolean hasSample;
	private long samples;

	/**
	 * Folds in one RTT sample, taken from the largest newly-acknowledged ack-eliciting packet
	 * (RFC 9002 §5.1 — only that packet, because only it has an unambiguous send time).
	 *
	 * @param sendTime          when that packet was sent
	 * @param ackTime           when the ACK covering it arrived
	 * @param peerAckDelay      the ACK frame's Ack Delay field, in milliseconds
	 * @param maxAckDelay       the {@code max_ack_delay} the peer advertised, in milliseconds
	 * @param handshakeConfirmed before confirmation the delay is not bounded by {@code max_ack_delay}
	 *                           (RFC 9002 §5.3), because the peer's parameters may not be known yet
	 */
	public void onRttSample(
		long sendTime, long ackTime, long peerAckDelay, long maxAckDelay, boolean handshakeConfirmed
	) {
		long sample = ackTime - sendTime;
		if (sample <= 0) {
			// A non-positive sample means the clock did not advance between send and ack — an in-process
			// test or a coarse clock, not a real measurement. Feeding it in would drag the estimate to
			// zero and make every timer fire immediately.
			sample = GRANULARITY_MILLIS;
		}
		latestRtt = sample;
		samples++;

		if (!hasSample) {
			hasSample = true;
			minRtt = sample;
			smoothedRtt = sample;
			rttVar = sample / 2;
			return;
		}

		minRtt = Math.min(minRtt, sample);
		long ackDelay = peerAckDelay < 0 ? 0 : peerAckDelay;
		if (handshakeConfirmed) {
			ackDelay = Math.min(ackDelay, maxAckDelay);
		}
		long adjusted = sample;
		if (sample >= minRtt + ackDelay) {
			// Only subtract when what is left is still at least min_rtt: otherwise the peer's claimed
			// delay is larger than the path can account for, and believing it would corrupt the estimate.
			adjusted = sample - ackDelay;
		}

		// RFC 9002 §5.3, in integer arithmetic: rttvar first, because it reads the *previous* smoothed_rtt.
		long rttVarSample = Math.abs(smoothedRtt - adjusted);
		rttVar = (3 * rttVar + rttVarSample) / 4;
		smoothedRtt = (7 * smoothedRtt + adjusted) / 8;
	}

	/**
	 * RFC 9002 §6.1.2: {@code kTimeThreshold * max(smoothed_rtt, latest_rtt)}, floored at the timer
	 * granularity. A packet older than this, and behind an acknowledged one, is declared lost.
	 */
	public long lossDelayMillis() {
		long base = Math.max(smoothedRtt, latestRtt);
		// kTimeThreshold is 9/8.
		return Math.max(GRANULARITY_MILLIS, (9 * base) / 8);
	}

	/**
	 * RFC 9002 §6.2.1: the probe timeout, {@code smoothed_rtt + max(4 * rttvar, kGranularity)}, plus
	 * {@code max_ack_delay} for the Application Data space only — the handshake spaces are acknowledged
	 * immediately, so charging them the peer's ACK delay would only make recovery slower.
	 *
	 * @param ptoCount consecutive probe timeouts so far; the timeout doubles per RFC 9002 §6.2.1
	 */
	public long ptoMillis(int ptoCount, boolean includeMaxAckDelay, long maxAckDelay) {
		long pto = smoothedRtt + Math.max(4 * rttVar, GRANULARITY_MILLIS);
		if (includeMaxAckDelay) {
			pto += maxAckDelay;
		}
		// Doubling is capped so a long outage cannot overflow the timer wheel or schedule a probe past
		// any plausible idle timeout.
		int exponent = Math.min(ptoCount, 20);
		return pto << exponent;
	}

	/** Whether any sample has been taken; before that the estimate is {@link #INITIAL_RTT_MILLIS}. */
	public boolean hasSample() {
		return hasSample;
	}

	public long samples() {
		return samples;
	}

	public long latestRtt() {
		return latestRtt;
	}

	public long minRtt() {
		return minRtt;
	}

	public long smoothedRtt() {
		return smoothedRtt;
	}

	public long rttVar() {
		return rttVar;
	}

	@Override
	public String toString() {
		return "RttEstimator{latest=" + latestRtt + "ms, min=" + minRtt + "ms, smoothed=" + smoothedRtt +
			"ms, var=" + rttVar + "ms, samples=" + samples + '}';
	}
}
