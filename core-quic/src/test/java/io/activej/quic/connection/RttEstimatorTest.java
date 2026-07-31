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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T046 — the RFC 9002 §5 arithmetic, asserted against hand-computed values rather than against the
 * implementation's own formula.
 */
public final class RttEstimatorTest {
	private static final long NO_MAX_ACK_DELAY = 25;

	private static RttEstimator withFirstSample(long rttMillis) {
		RttEstimator rtt = new RttEstimator();
		rtt.onRttSample(1000, 1000 + rttMillis, 0, NO_MAX_ACK_DELAY, true);
		return rtt;
	}

	@Test
	public void beforeAnySampleTheEstimateIsTheRfcInitialRtt() {
		RttEstimator rtt = new RttEstimator();
		assertFalse(rtt.hasSample());
		assertEquals(RttEstimator.INITIAL_RTT_MILLIS, rtt.smoothedRtt());
		assertEquals(RttEstimator.INITIAL_RTT_MILLIS / 2, rtt.rttVar());
	}

	@Test
	public void theFirstSampleSetsEverythingDirectly() {
		// RFC 9002 §5.2: smoothed_rtt = latest_rtt, rttvar = latest_rtt / 2. No smoothing on the first
		// sample, because there is nothing to smooth against.
		RttEstimator rtt = withFirstSample(100);
		assertTrue(rtt.hasSample());
		assertEquals(100, rtt.latestRtt());
		assertEquals(100, rtt.minRtt());
		assertEquals(100, rtt.smoothedRtt());
		assertEquals(50, rtt.rttVar());
		assertEquals(1, rtt.samples());
	}

	@Test
	public void theSecondSampleSmoothsBySevenEighthsAndThreeQuarters() {
		RttEstimator rtt = withFirstSample(100);
		// A 200 ms sample with no ack delay. rttvar reads the OLD smoothed_rtt (100), so:
		//   rttvar_sample = |100 - 200| = 100  ->  rttvar = (3*50 + 100)/4 = 62
		//   smoothed_rtt = (7*100 + 200)/8 = 112
		rtt.onRttSample(2000, 2200, 0, NO_MAX_ACK_DELAY, true);
		assertEquals(200, rtt.latestRtt());
		assertEquals(100, rtt.minRtt());
		assertEquals(62, rtt.rttVar());
		assertEquals(112, rtt.smoothedRtt());
	}

	@Test
	public void minRttOnlyEverFalls() {
		RttEstimator rtt = withFirstSample(100);
		rtt.onRttSample(2000, 2300, 0, NO_MAX_ACK_DELAY, true);
		assertEquals(100, rtt.minRtt());
		rtt.onRttSample(3000, 3040, 0, NO_MAX_ACK_DELAY, true);
		assertEquals(40, rtt.minRtt());
		rtt.onRttSample(4000, 4500, 0, NO_MAX_ACK_DELAY, true);
		assertEquals("min_rtt must not rise", 40, rtt.minRtt());
	}

	// ---------------------------------------------------------------- the ack_delay guards

	@Test
	public void aReportedAckDelayIsSubtractedFromTheSample() {
		RttEstimator rtt = withFirstSample(100);
		// A 200 ms sample of which the peer says 20 ms was its own delay: the network part is 180 ms.
		//   rttvar_sample = |100 - 180| = 80  ->  rttvar = (3*50 + 80)/4 = 57
		//   smoothed_rtt = (7*100 + 180)/8 = 110
		rtt.onRttSample(2000, 2200, 20, NO_MAX_ACK_DELAY, true);
		assertEquals(57, rtt.rttVar());
		assertEquals(110, rtt.smoothedRtt());
	}

	@Test
	public void anAckDelayIsCappedAtThePeersAdvertisedMaxAckDelayOnceTheHandshakeIsConfirmed() {
		RttEstimator rtt = withFirstSample(100);
		// The peer claims 500 ms of ACK delay but advertised max_ack_delay = 25. RFC 9002 §5.3 caps it at
		// 25, so 175 ms is used, not -300. Believing the claim would let a peer drive our RTT estimate —
		// and every timer derived from it — arbitrarily low, manufacturing spurious retransmissions.
		rtt.onRttSample(2000, 2200, 500, 25, true);
		assertEquals((7 * 100 + 175) / 8, rtt.smoothedRtt());
	}

	@Test
	public void anAckDelayIsNotCappedBeforeTheHandshakeIsConfirmed() {
		RttEstimator first = withFirstSample(100);
		RttEstimator second = withFirstSample(100);
		// Before confirmation the peer's max_ack_delay may not be known yet (RFC 9002 §5.3), so the
		// reported value stands — the min_rtt floor below is what keeps it safe.
		first.onRttSample(2000, 2200, 50, 25, false);
		second.onRttSample(2000, 2200, 50, 25, true);
		assertNotEquals(first.smoothedRtt(), second.smoothedRtt());
		assertEquals((7 * 100 + 150) / 8, first.smoothedRtt());
	}

	@Test
	public void anAckDelayThatWouldPushTheSampleBelowMinRttIsIgnoredEntirely() {
		RttEstimator rtt = withFirstSample(100);
		// sample (110) < min_rtt (100) + ack_delay (25), so no subtraction happens at all. The path
		// cannot account for the claimed delay, so the claim is not believed.
		rtt.onRttSample(2000, 2110, 25, 25, true);
		assertEquals((7 * 100 + 110) / 8, rtt.smoothedRtt());
	}

	@Test
	public void aNonPositiveSampleIsFlooredAtTheTimerGranularity() {
		RttEstimator rtt = new RttEstimator();
		// An in-process test or a coarse clock can produce ackTime == sendTime. Taking that at face value
		// would drag every derived timeout to zero and make the connection probe continuously.
		rtt.onRttSample(1000, 1000, 0, NO_MAX_ACK_DELAY, true);
		assertEquals(RttEstimator.GRANULARITY_MILLIS, rtt.latestRtt());
		assertEquals(RttEstimator.GRANULARITY_MILLIS, rtt.smoothedRtt());
	}

	// ---------------------------------------------------------------- derived timeouts

	@Test
	public void theLossDelayIsNineEighthsOfTheLargerOfSmoothedAndLatest() {
		RttEstimator rtt = withFirstSample(100);
		// Both are 100 after one sample: 9 * 100 / 8 = 112.
		assertEquals(112, rtt.lossDelayMillis());

		rtt.onRttSample(2000, 2400, 0, NO_MAX_ACK_DELAY, true);
		// latest_rtt (400) now exceeds smoothed_rtt, and RFC 9002 §6.1.2 takes the max of the two so a
		// sudden RTT spike does not immediately look like loss.
		assertEquals(400, rtt.latestRtt());
		assertEquals(9 * 400 / 8, rtt.lossDelayMillis());
	}

	@Test
	public void theLossDelayNeverFallsBelowTheTimerGranularity() {
		RttEstimator rtt = new RttEstimator();
		rtt.onRttSample(1000, 1000, 0, NO_MAX_ACK_DELAY, true);
		assertTrue(rtt.lossDelayMillis() >= RttEstimator.GRANULARITY_MILLIS);
	}

	@Test
	public void theProbeTimeoutIsSmoothedRttPlusFourRttVar() {
		RttEstimator rtt = withFirstSample(100);
		// smoothed 100, rttvar 50: 100 + max(200, 1) = 300. No max_ack_delay for a handshake space.
		assertEquals(300, rtt.ptoMillis(0, false, 25));
		// The Application Data space is charged the peer's max_ack_delay (RFC 9002 §6.2.1).
		assertEquals(325, rtt.ptoMillis(0, true, 25));
	}

	@Test
	public void theProbeTimeoutDoublesPerConsecutiveTimeout() {
		RttEstimator rtt = withFirstSample(100);
		long base = rtt.ptoMillis(0, false, 25);
		assertEquals(base * 2, rtt.ptoMillis(1, false, 25));
		assertEquals(base * 4, rtt.ptoMillis(2, false, 25));
		assertEquals(base * 8, rtt.ptoMillis(3, false, 25));
	}

	@Test
	public void theProbeTimeoutBackoffIsCappedSoItCannotOverflow() {
		RttEstimator rtt = withFirstSample(100);
		// A long black-hole must not shift the timeout past what a long can hold, which would wrap to a
		// negative deadline and fire the probe immediately and forever.
		assertTrue(rtt.ptoMillis(1000, false, 25) > 0);
		assertEquals(rtt.ptoMillis(20, false, 25), rtt.ptoMillis(1000, false, 25));
	}

	@Test
	public void theRttVarFloorKeepsTheProbeTimeoutAboveTheGranularity() {
		RttEstimator rtt = new RttEstimator();
		// Repeated identical samples drive rttvar towards 0; the max(4*rttvar, kGranularity) term is what
		// stops the probe timeout collapsing onto smoothed_rtt exactly.
		for (int i = 0; i < 40; i++) {
			rtt.onRttSample(i * 1000, i * 1000 + 50, 0, NO_MAX_ACK_DELAY, true);
		}
		assertEquals(0, rtt.rttVar());
		assertEquals(50 + RttEstimator.GRANULARITY_MILLIS, rtt.ptoMillis(0, false, 25));
	}
}
