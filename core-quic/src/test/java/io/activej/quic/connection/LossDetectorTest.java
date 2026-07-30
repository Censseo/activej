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

import io.activej.quic.codec.PingFrame;
import io.activej.quic.tls.EncryptionLevel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * T047 — the RFC 9002 §6 thresholds and the probe timer, driven entirely by arithmetic.
 * <p>
 * {@code now} is a parameter throughout, so none of this waits on a clock (FR-039).
 */
public final class LossDetectorTest {
	private static final long T0 = 1_000_000;

	private static PacketNumberSpace space() {
		return new PacketNumberSpace(EncryptionLevel.ONE_RTT, 32);
	}

	/** Sends packet {@code pn} at {@code sentTime}, carrying a PING so it is ack-eliciting. */
	private static void send(PacketNumberSpace space, long pn, long sentTime, boolean ackEliciting) {
		// onPacketSent is what advances largestSent; nextPacketNumber only hands out the next value.
		space.onPacketSent(new SentPacket(pn, EncryptionLevel.ONE_RTT, sentTime, 100,
			ackEliciting, ackEliciting, List.of(PingFrame.INSTANCE), false));
	}

	private static RttEstimator rttOf(long rttMillis) {
		RttEstimator rtt = new RttEstimator();
		rtt.onRttSample(T0, T0 + rttMillis, 0, 25, true);
		return rtt;
	}

	// ---------------------------------------------------------------- the packet threshold

	@Test
	public void nothingIsLostBeforeTheFirstAcknowledgement() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn < 10; pn++) {
			send(space, pn, T0, true);
		}
		// No largest_acked means no reference point for either threshold; only the probe timeout applies.
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 100_000, rttOf(100));
		assertTrue(detection.lost().isEmpty());
		assertEquals(0, detection.nextLossTime());
		assertEquals(10, space.outstandingCount());
	}

	@Test
	public void aPacketThreePlacesBelowTheLargestAcknowledgedIsLost() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn <= 3; pn++) {
			send(space, pn, T0, true);
		}
		space.onAckReceived(3);
		space.onPacketAcked(3);

		// kPacketThreshold is 3: packet 0 is 3 below 3, so three later packets arrived without it. That is
		// no longer plausibly reordering.
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 1, rttOf(1000));
		assertEquals(1, detection.lost().size());
		assertEquals(0, detection.lost().get(0).packetNumber);
	}

	@Test
	public void aPacketTwoPlacesBelowTheLargestAcknowledgedIsNotYetLost() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn <= 2; pn++) {
			send(space, pn, T0, true);
		}
		space.onAckReceived(2);
		space.onPacketAcked(2);

		// Two later packets is within the reordering the threshold deliberately tolerates.
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 1, rttOf(1000));
		assertTrue(detection.lost().isEmpty());
		// But a time-threshold deadline is now armed for them.
		assertTrue(detection.nextLossTime() > 0);
	}

	@Test
	public void aPacketAboveTheLargestAcknowledgedIsNeverLost() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn <= 5; pn++) {
			send(space, pn, T0, true);
		}
		space.onAckReceived(1);
		space.onPacketAcked(1);

		// Packets 2..5 are newer than anything acknowledged, so their absence proves nothing at all —
		// they may simply still be in flight, however long ago they were sent.
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 1_000_000, rttOf(10));
		for (SentPacket lost : detection.lost()) {
			assertTrue("packet " + lost.packetNumber + " is above largest_acked", lost.packetNumber < 1);
		}
		assertNotNull(space.sentPacket(5));
	}

	// ---------------------------------------------------------------- the time threshold

	@Test
	public void aPacketOlderThanTheLossDelayIsLostEvenWithinThePacketThreshold() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		send(space, 1, T0 + 5000, true);
		space.onAckReceived(1);
		space.onPacketAcked(1);

		RttEstimator rtt = rttOf(100);
		long lossDelay = rtt.lossDelayMillis();
		// Packet 0 is only one place below largest_acked, so the packet threshold does not reach it — the
		// time threshold is what catches this tail case.
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + lossDelay + 1, rtt);
		assertEquals(1, detection.lost().size());
		assertEquals(0, detection.lost().get(0).packetNumber);
	}

	@Test
	public void aPacketYoungerThanTheLossDelayArmsADeadlineInsteadOfBeingLost() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		send(space, 1, T0, true);
		space.onAckReceived(1);
		space.onPacketAcked(1);

		RttEstimator rtt = rttOf(100);
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 1, rtt);
		assertTrue(detection.lost().isEmpty());
		// The deadline is exactly when it will cross the threshold, so the caller can arm one timer.
		assertEquals(T0 + rtt.lossDelayMillis(), detection.nextLossTime());
		assertEquals(detection.nextLossTime(), space.lossTime());
	}

	@Test
	public void theEarliestDeadlineIsTheOneReported() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0 + 500, true);
		send(space, 1, T0, true);
		send(space, 2, T0 + 900, true);
		space.onAckReceived(2);
		space.onPacketAcked(2);

		RttEstimator rtt = rttOf(2000);
		LossDetector.Detection detection = LossDetector.detectLost(space, T0 + 1000, rtt);
		// Packet 1 is the oldest of those still eligible, so its deadline comes first.
		assertEquals(T0 + rtt.lossDelayMillis(), detection.nextLossTime());
	}

	@Test
	public void aLostPacketIsRemovedFromTheSpaceSoItIsNotDeclaredLostTwice() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn <= 3; pn++) {
			send(space, pn, T0, true);
		}
		space.onAckReceived(3);
		space.onPacketAcked(3);

		assertEquals(1, LossDetector.detectLost(space, T0 + 1, rttOf(1000)).lost().size());
		// Retransmission has already been queued for it; declaring it lost again would duplicate the data
		// and corrupt the in-flight accounting.
		assertTrue(LossDetector.detectLost(space, T0 + 1, rttOf(1000)).lost().isEmpty());
		assertNull(space.sentPacket(0));
	}

	@Test
	public void lostPacketsComeBackInAscendingPacketNumberOrder() throws Exception {
		PacketNumberSpace space = space();
		for (long pn = 0; pn <= 6; pn++) {
			send(space, pn, T0, true);
		}
		space.onAckReceived(6);
		space.onPacketAcked(6);

		// Order matters for retransmission: CRYPTO frames carry stream offsets, and re-queueing them out
		// of order would put the later offset in front of the earlier one.
		List<SentPacket> lost = LossDetector.detectLost(space, T0 + 1, rttOf(1000)).lost();
		assertEquals(4, lost.size());
		for (int i = 1; i < lost.size(); i++) {
			assertTrue(lost.get(i - 1).packetNumber < lost.get(i).packetNumber);
		}
	}

	// ---------------------------------------------------------------- the probe timer

	@Test
	public void noProbeIsArmedWhenNothingAckElicitingIsInFlight() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, false);
		// An ACK-only packet elicits no response, so waiting for one would be waiting forever.
		assertNull(LossDetector.nextProbe(List.of(space), rttOf(100), 0, 25, true));
	}

	@Test
	public void theProbeIsArmedFromTheOldestAckElicitingPacketInFlight() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		send(space, 1, T0 + 5000, true);

		RttEstimator rtt = rttOf(100);
		LossDetector.Armed probe = LossDetector.nextProbe(List.of(space), rtt, 0, 25, true);
		assertNotNull(probe);
		assertEquals(EncryptionLevel.ONE_RTT, probe.level());
		assertEquals(T0 + rtt.ptoMillis(0, true, 25), probe.time());
	}

	@Test
	public void theOneRttSpaceIsNotProbedBeforeTheHandshakeIsConfirmed() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		// RFC 9002 §6.2.1: the peer may not have 1-RTT keys yet, so a probe would be undecryptable and
		// would only burn the anti-amplification budget.
		assertNull(LossDetector.nextProbe(List.of(space), rttOf(100), 0, 25, false));
		assertNotNull(LossDetector.nextProbe(List.of(space), rttOf(100), 0, 25, true));
	}

	@Test
	public void aDiscardedSpaceIsNeitherProbedNorScannedForLoss() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		send(space, 1, T0, true);
		space.onAckReceived(1);
		space.onPacketAcked(1);
		space.discard();

		// FR-006: a discarded space's packets are neither lost nor acknowledged — they stop existing.
		assertNull(LossDetector.nextProbe(List.of(space), rttOf(100), 0, 25, true));
		assertNull(LossDetector.earliestLossTime(List.of(space)));
	}

	@Test
	public void aDiscardedSpacesLossDeadlineIsNotReported() throws Exception {
		PacketNumberSpace armed = space();
		send(armed, 0, T0, true);
		send(armed, 1, T0, true);
		armed.onAckReceived(1);
		armed.onPacketAcked(1);
		LossDetector.detectLost(armed, T0 + 1, rttOf(100));
		assertNotNull(LossDetector.earliestLossTime(List.of(armed)));

		armed.discard();
		assertNull(LossDetector.earliestLossTime(List.of(armed)));
	}

	@Test
	public void theEarliestLossTimeAcrossSpacesWins() throws Exception {
		PacketNumberSpace early = new PacketNumberSpace(EncryptionLevel.HANDSHAKE, 32);
		PacketNumberSpace late = space();
		early.onPacketSent(new SentPacket(0, EncryptionLevel.HANDSHAKE, T0, 100, true, true,
			List.of(PingFrame.INSTANCE), false));
		early.onPacketSent(new SentPacket(1, EncryptionLevel.HANDSHAKE, T0, 100, true, true,
			List.of(PingFrame.INSTANCE), false));
		early.onAckReceived(1);
		early.onPacketAcked(1);
		send(late, 0, T0 + 10_000, true);
		send(late, 1, T0 + 10_000, true);
		late.onAckReceived(1);
		late.onPacketAcked(1);

		RttEstimator rtt = rttOf(100);
		LossDetector.detectLost(early, T0 + 1, rtt);
		LossDetector.detectLost(late, T0 + 1, rtt);

		// RFC 9002 §6.2: one timer serves every space, set to whichever deadline comes first — and the
		// handler needs to know which space that was in order to act on it.
		LossDetector.Armed earliest = LossDetector.earliestLossTime(List.of(early, late));
		assertNotNull(earliest);
		assertEquals(EncryptionLevel.HANDSHAKE, earliest.level());
	}

	@Test
	public void theProbeDeadlineGrowsWithThePtoCount() throws Exception {
		PacketNumberSpace space = space();
		send(space, 0, T0, true);
		RttEstimator rtt = rttOf(100);

		long first = LossDetector.nextProbe(List.of(space), rtt, 0, 25, true).time();
		long second = LossDetector.nextProbe(List.of(space), rtt, 1, 25, true).time();
		long third = LossDetector.nextProbe(List.of(space), rtt, 2, 25, true).time();
		assertTrue(second > first);
		assertEquals(second - T0, 2 * (first - T0));
		assertEquals(third - T0, 4 * (first - T0));
	}

	@Test
	public void thePacketThresholdIsTheRfcRecommendedValue() throws Exception {
		// RFC 9002 §6.1.1 fixes the RECOMMENDED value at 3 and forbids anything lower; a smaller value
		// would turn ordinary reordering into spurious retransmission.
		assertEquals(3, LossDetector.PACKET_THRESHOLD);
	}
}
