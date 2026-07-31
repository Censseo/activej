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

import io.activej.common.MemSize;
import io.activej.quic.connection.NewRenoCongestionController.State;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T065 — US5 scenarios 1–6: the RFC 9002 §7 window as a pure state machine, against hand-computed
 * traces rather than against the implementation's own formula.
 */
public final class NewRenoCongestionControllerTest {
	private static final int MSS = 1350;

	private static NewRenoCongestionController controller() {
		return new NewRenoCongestionController(MSS, QuicConnectionSettings.initialCongestionWindowFor(MSS));
	}

	// ---------------------------------------------------------------- scenario 1: the initial window

	@Test
	public void theInitialWindowIsTheRfc9002Section72Formula() {
		// min(10 × 1350, max(14720, 2 × 1350)) = min(13500, 14720) = 13500.
		assertEquals(13500, QuicConnectionSettings.initialCongestionWindowFor(MSS));
		assertEquals(13500, controller().congestionWindow());
	}

	@Test
	public void aVeryLargeDatagramSizeIsBoundedByTheFourteenSevenTwentyFloorNotByTenPackets() {
		// At 1500 bytes the 10-packet term (15000) exceeds the 14720 floor, so the floor stops applying.
		assertEquals(14720, QuicConnectionSettings.initialCongestionWindowFor(1500));
		// And at 1200 the 10-packet term wins: the formula is a min of the two, not a max.
		assertEquals(12000, QuicConnectionSettings.initialCongestionWindowFor(1200));
	}

	@Test
	public void theMinimumWindowIsTwoDatagrams() {
		assertEquals(2L * MSS, controller().minimumWindow());
	}

	@Test
	public void anInitialWindowBelowTheMinimumIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> new NewRenoCongestionController(MSS, MSS));
		assertTrue(e.getMessage().contains("minimum"));
	}

	// ---------------------------------------------------------------- scenario 2: slow start

	@Test
	public void inSlowStartTheWindowGrowsByEveryAcknowledgedByte() {
		NewRenoCongestionController cc = controller();
		assertEquals(State.SLOW_START, cc.state());

		cc.onPacketSent(MSS);
		cc.onPacketAcked(MSS, 1000);

		assertEquals(13500 + MSS, cc.congestionWindow());
		assertEquals(0, cc.bytesInFlight());
		assertEquals(State.SLOW_START, cc.state());
	}

	@Test
	public void inFlightBytesAreWhatTheWindowIsMeasuredAgainst() {
		NewRenoCongestionController cc = controller();
		long window = cc.congestionWindow();

		for (int sent = 0; sent < window; sent += MSS) {
			assertFalse("blocked with " + cc.available() + " bytes still available", cc.isBlocked());
			cc.onPacketSent(MSS);
		}

		assertTrue("the window did not stop the sender", cc.isBlocked());
		assertEquals(0, cc.available());
	}

	// ---------------------------------------------------------------- scenario 3: one halving per period

	@Test
	public void aCongestionEventHalvesTheWindow() {
		NewRenoCongestionController cc = controller();
		cc.onPacketSent(4 * MSS);

		cc.onCongestionEvent(1000, 1100);

		assertEquals(13500 / 2, cc.congestionWindow());
		assertEquals(13500 / 2, cc.slowStartThreshold());
		assertEquals(1, cc.congestionEvents());
	}

	@Test
	public void aSecondLossFromTheSamePeriodDoesNotHalveAgain() {
		NewRenoCongestionController cc = controller();
		cc.onCongestionEvent(1000, 1100);
		long afterFirst = cc.congestionWindow();

		// Every packet sent before 1100 was already in flight when we reduced, so its loss is the echo of
		// an event already reacted to. Halving per lost packet would collapse the window to its floor on
		// any burst loss.
		cc.onCongestionEvent(1050, 1200);
		cc.onCongestionEvent(1100, 1300);

		assertEquals(afterFirst, cc.congestionWindow());
		assertEquals(1, cc.congestionEvents());
	}

	@Test
	public void aLossOfAPacketSentAfterTheReductionHalvesAgain() {
		NewRenoCongestionController cc = controller();
		cc.onCongestionEvent(1000, 1100);

		cc.onCongestionEvent(1101, 1200);

		assertEquals(13500 / 4, cc.congestionWindow());
		assertEquals(2, cc.congestionEvents());
	}

	@Test
	public void theWindowNeverFallsBelowTwoDatagrams() {
		NewRenoCongestionController cc = controller();
		long now = 1000;
		// Enough halvings to drive it below the floor several times over.
		for (int i = 0; i < 20; i++) {
			cc.onCongestionEvent(now, now + 1);
			now += 2;
		}
		assertEquals(2L * MSS, cc.congestionWindow());
		// Two datagrams is the floor because one would make every send wait for an acknowledgement of the
		// previous one, which is a stall rather than a rate.
		assertFalse(cc.isBlocked());
	}

	@Test
	public void anAcknowledgementFromBeforeTheReductionDoesNotGrowTheWindow() {
		NewRenoCongestionController cc = controller();
		cc.onPacketSent(2 * MSS);
		cc.onCongestionEvent(1000, 1100);
		long afterReduction = cc.congestionWindow();

		cc.onPacketAcked(MSS, 1000);

		// The reduction would otherwise be undone by the acknowledgements already on their way.
		assertEquals(afterReduction, cc.congestionWindow());
		// The bytes still leave the in-flight count: they genuinely are not in flight any more.
		assertEquals(MSS, cc.bytesInFlight());
	}

	// ---------------------------------------------------------------- scenario 4: congestion avoidance

	@Test
	public void afterRecoveryTheWindowGrowsByAFractionOfADatagramPerAcknowledgement() {
		NewRenoCongestionController cc = controller();
		cc.onCongestionEvent(1000, 1100);
		long window = cc.congestionWindow();
		assertEquals(6750, window);

		cc.onPacketSent(MSS);
		cc.onPacketAcked(MSS, 1200);

		// max_datagram_size × acked / cwnd = 1350 × 1350 / 6750 = 270 bytes, not a whole datagram: this
		// is the linear regime, where the sender probes for capacity a fraction at a time.
		assertEquals(window + 270, cc.congestionWindow());
		assertEquals(State.CONGESTION_AVOIDANCE, cc.state());
	}

	@Test
	public void congestionAvoidanceAlwaysAdvancesByAtLeastOneByte() {
		// With a very large window the exact increment rounds to zero in integer arithmetic; a floor of one
		// byte is what stops the window freezing permanently.
		NewRenoCongestionController cc = new NewRenoCongestionController(MSS, 2L * MSS);
		cc.onCongestionEvent(1000, 1100);
		for (int i = 0; i < 50; i++) {
			cc.onPacketSent(1);
			cc.onPacketAcked(1, 2000 + i);
		}
		assertTrue(cc.congestionWindow() > 2L * MSS);
	}

	// ---------------------------------------------------------------- scenario 5: persistent congestion

	@Test
	public void persistentCongestionCollapsesToTheMinimumAndRestartsSlowStart() {
		NewRenoCongestionController cc = controller();
		cc.onCongestionEvent(1000, 1100);
		assertEquals(State.RECOVERY, cc.state());

		cc.onPersistentCongestion();

		assertEquals(2L * MSS, cc.congestionWindow());
		// cwnd < ssthresh again, so the controller is back in slow start: losing everything across a span
		// longer than three probe timeouts means the path's capacity is unknown, not merely smaller.
		assertEquals(State.SLOW_START, cc.state());
		assertEquals(1, cc.persistentCongestionEpisodes());
	}

	@Test
	public void persistentCongestionClearsTheRecoveryPeriodSoTheNextLossCountsAgain() {
		NewRenoCongestionController cc = controller();
		cc.onCongestionEvent(1000, 1100);
		cc.onPersistentCongestion();

		assertFalse(cc.inRecovery(1050));
		cc.onCongestionEvent(1050, 1150);
		assertEquals(2, cc.congestionEvents());
	}

	// ---------------------------------------------------------------- scenario 6: what bypasses the window

	@Test
	public void aDiscardedSpaceReleasesItsBytesWithoutAnyReduction() {
		NewRenoCongestionController cc = controller();
		cc.onPacketSent(5 * MSS);
		long window = cc.congestionWindow();

		cc.onSpaceDiscarded(5L * MSS);

		assertEquals(0, cc.bytesInFlight());
		// RFC 9002 §B.9: not a loss. Reducing here would halve the window on every successful handshake,
		// since Initial and Handshake spaces are always discarded.
		assertEquals(window, cc.congestionWindow());
		assertEquals(0, cc.congestionEvents());
	}

	@Test
	public void lostBytesLeaveTheInFlightCountEvenWhenNoReductionApplies() {
		NewRenoCongestionController cc = controller();
		cc.onPacketSent(3 * MSS);
		cc.onCongestionEvent(1000, 1100);

		cc.onPacketsLost(3L * MSS);

		// Otherwise the in-flight count would ratchet upward and the connection would end up permanently
		// blocked by packets that are already gone.
		assertEquals(0, cc.bytesInFlight());
	}

	@Test
	public void theInFlightCountNeverGoesNegative() {
		NewRenoCongestionController cc = controller();
		cc.onPacketSent(MSS);

		// A duplicate ACK, or a loss and an acknowledgement of the same packet, must not corrupt the count
		// into a permanently-unblocked state.
		cc.onPacketAcked(MSS, 1000);
		cc.onPacketAcked(MSS, 1000);
		cc.onPacketsLost(10L * MSS);

		assertEquals(0, cc.bytesInFlight());
	}

	@Test
	public void theControllerIsBuiltFromTheConnectionSettings() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxDatagramSize(MemSize.bytes(1200))
			.build();
		NewRenoCongestionController cc = NewRenoCongestionController.of(settings);

		assertEquals(settings.initialCongestionWindow(), cc.congestionWindow());
		assertEquals(2400, cc.minimumWindow());
	}
}
