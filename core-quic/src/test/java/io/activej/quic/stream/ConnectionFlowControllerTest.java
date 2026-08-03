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

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Connection-level flow control (RFC 9000 §4.1) is pure arithmetic — no reactor, no promise, no
 * buffer. If a test here needs any of the three, the design has drifted.
 * <p>
 * The load-bearing case is {@link #creditSpentByAnAbortedStreamIsNotReturned()}: FR-023's
 * <em>credit laundering</em> scenario, in which a peer opens a stream, spends connection-level
 * credit on it, aborts it and repeats. Nothing about releasing a stream may hand that credit back.
 */
public class ConnectionFlowControllerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long SEND_LIMIT = 1000;
	private static final long RECEIVE_WINDOW = 1000;

	private static ConnectionFlowController controller() {
		return ConnectionFlowController.create(SEND_LIMIT, RECEIVE_WINDOW);
	}

	// region send side — RFC 9000 §4.1

	@Test
	public void initiallyTheWholeWindowIsAvailableInBothDirections() {
		ConnectionFlowController fc = controller();
		assertEquals(SEND_LIMIT, fc.sendLimit());
		assertEquals(0, fc.sendUsed());
		assertEquals(SEND_LIMIT, fc.sendAvailable());
		assertEquals(RECEIVE_WINDOW, fc.receiveLimit());
		assertEquals(0, fc.receiveUsed());
		assertEquals(RECEIVE_WINDOW, fc.receiveAvailable());
		assertEquals(0, fc.consumedOffset());
	}

	@Test
	public void sendAccountingSumsOverAllStreams() {
		ConnectionFlowController fc = controller();
		fc.onBytesSent(400);   // stream 0
		fc.onBytesSent(250);   // stream 4
		fc.onBytesSent(50);    // stream 8
		assertEquals(700, fc.sendUsed());
		assertEquals(300, fc.sendAvailable());
	}

	@Test
	public void theSendLimitIsTheBoundaryForCanSend() {
		ConnectionFlowController fc = controller();
		fc.onBytesSent(900);
		assertTrue(fc.canSend(100));
		assertFalse(fc.canSend(101));
		fc.onBytesSent(100);
		assertEquals(0, fc.sendAvailable());
		assertTrue(fc.canSend(0));
		assertFalse(fc.canSend(1));
	}

	@Test
	public void maxDataRaisesTheSendLimit() {
		ConnectionFlowController fc = controller();
		fc.onBytesSent(1000);
		assertFalse(fc.canSend(1));
		assertTrue(fc.onMaxData(1500));
		assertEquals(1500, fc.sendLimit());
		assertEquals(500, fc.sendAvailable());
		assertTrue(fc.canSend(500));
	}

	@Test
	public void aLowerMaxDataIsIgnoredWithoutError() {
		// FR-026 / RFC 9000 §4.1: a limit below one already received is ignored, not an error.
		ConnectionFlowController fc = controller();
		assertTrue(fc.onMaxData(1500));
		assertFalse(fc.onMaxData(1200));
		assertFalse(fc.onMaxData(0));
		assertEquals(1500, fc.sendLimit());
		assertFalse(fc.onMaxData(1500));
		assertEquals(1500, fc.sendLimit());
	}

	// endregion

	// region receive side — RFC 9000 §4.1

	@Test
	public void receiveAccountingSumsOverAllStreams() {
		ConnectionFlowController fc = controller();
		assertTrue(fc.onBytesReceived(400));   // stream 3's highest offset moved 0 → 400
		assertTrue(fc.onBytesReceived(250));   // stream 7's highest offset moved 0 → 250
		assertTrue(fc.onBytesReceived(100));   // stream 3's highest offset moved 400 → 500
		assertEquals(750, fc.receiveUsed());
		assertEquals(250, fc.receiveAvailable());
	}

	@Test
	public void receivingExactlyUpToTheLimitIsNotAViolation() {
		ConnectionFlowController fc = controller();
		assertTrue(fc.onBytesReceived(999));
		assertTrue(fc.onBytesReceived(1));
		assertEquals(0, fc.receiveAvailable());
	}

	@Test
	public void receivingPastTheLimitIsReportedAsAViolation() {
		// FR-023: the manager turns a false here into FLOW_CONTROL_ERROR. The counter itself never
		// throws a wire exception.
		ConnectionFlowController fc = controller();
		assertTrue(fc.onBytesReceived(1000));
		assertFalse(fc.onBytesReceived(1));
		assertEquals(1001, fc.receiveUsed());
		assertEquals(-1, fc.receiveAvailable());
	}

	@Test
	public void receiveAccountingSaturatesInsteadOfWrappingAround() {
		// A peer that could wrap the counter negative would look like it had spent nothing.
		ConnectionFlowController fc = controller();
		assertFalse(fc.onBytesReceived(Long.MAX_VALUE));
		assertFalse(fc.onBytesReceived(Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, fc.receiveUsed());
		assertTrue(fc.receiveAvailable() < 0);
	}

	// endregion

	// region FR-023 — credit spent by an aborted stream stays spent

	@Test
	public void creditSpentByAnAbortedStreamIsNotReturned() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(400);   // stream 3
		fc.onBytesReceived(300);   // stream 7
		assertEquals(700, fc.receiveUsed());
		assertEquals(300, fc.receiveAvailable());

		// Stream 3 is aborted and released with the application having read none of its 400 bytes.
		fc.onStreamReleased(400, 0);

		// The peer's spend stands: receiveUsed does not go back to 300, and the abort granted the
		// peer no room at the current limit.
		assertEquals(700, fc.receiveUsed());
		assertEquals(300, fc.receiveAvailable());

		// So the peer may still send only the 300 bytes left under the limit it was given.
		assertTrue(fc.onBytesReceived(300));
		assertFalse(fc.onBytesReceived(1));
	}

	@Test
	public void repeatedOpenSpendAbortCyclesCannotOutrunTheWindow() {
		// The laundering attack itself: five streams, each spending 200 of a 1000-byte window and
		// each aborted immediately. Without FR-023 the sixth would be admitted.
		ConnectionFlowController fc = controller();
		for (int i = 0; i < 5; i++) {
			assertTrue(fc.onBytesReceived(200));
			fc.onStreamReleased(200, 0);
		}
		assertEquals(1000, fc.receiveUsed());
		assertEquals(0, fc.receiveAvailable());
		assertFalse(fc.onBytesReceived(1));
	}

	@Test
	public void releasingAStreamTreatsItsUnreadBytesAsConsumed() {
		// The liveness half of the same hook (FR-006, RFC 9000 §4.5): bytes discarded on abort are
		// bytes the receiver is no longer holding, so they must move the consumed offset — otherwise
		// the connection window shrinks permanently with every abort.
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(400);
		fc.onBytesConsumed(150);
		assertEquals(150, fc.consumedOffset());

		fc.onStreamReleased(400, 150);
		assertEquals(400, fc.consumedOffset());
		assertEquals(400, fc.receiveUsed());
	}

	@Test
	public void releasingAFullyReadStreamConsumesNothingExtra() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(400);
		fc.onBytesConsumed(400);
		fc.onStreamReleased(400, 400);
		assertEquals(400, fc.consumedOffset());
		assertEquals(400, fc.receiveUsed());
	}

	// endregion

	// region FR-025 — the ½-consumed grant threshold

	@Test
	public void consumingAloneDoesNotRaiseTheLimit() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(800);
		fc.onBytesConsumed(800);
		assertEquals(RECEIVE_WINDOW, fc.receiveLimit());
		assertEquals(200, fc.receiveAvailable());
	}

	@Test
	public void theGrantThresholdIsHalfTheWindow() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(1000);
		fc.onBytesConsumed(499);
		assertFalse(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
		fc.onBytesConsumed(1);
		assertTrue(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
	}

	@Test
	public void aGrantRestoresTheFullWindowAboveTheConsumedOffset() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(1000);
		fc.onBytesConsumed(600);
		assertTrue(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1600, fc.grantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1600, fc.receiveLimit());
		assertEquals(600, fc.receiveAvailable());
		assertFalse(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
	}

	@Test
	public void theAdvertisedLimitNeverDecreases() {
		// FR-026 seen from the other side: our own advertised limit is monotone too.
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(1000);
		fc.onBytesConsumed(900);
		assertEquals(1900, fc.grantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1900, fc.grantReceiveCredit(500));
		assertEquals(1900, fc.receiveLimit());
	}

	@Test
	public void grantingTwiceWithoutConsumptionIsIdempotent() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(1000);
		fc.onBytesConsumed(700);
		assertEquals(1700, fc.grantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1700, fc.grantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1700, fc.receiveLimit());
	}

	@Test
	public void aReleaseCanBeWhatCrossesTheGrantThreshold() {
		// A stream aborted mid-window releases its buffered bytes; that is what unblocks the peer.
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(600);
		assertFalse(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
		fc.onStreamReleased(600, 0);
		assertTrue(fc.shouldGrantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(1600, fc.grantReceiveCredit(RECEIVE_WINDOW));
		assertEquals(600, fc.receiveUsed());
	}

	// endregion

	// region caller-bug guards

	@Test
	public void negativeByteCountsAreRejected() {
		ConnectionFlowController fc = controller();
		assertThrows(IllegalArgumentException.class, () -> fc.onBytesSent(-1));
		assertThrows(IllegalArgumentException.class, () -> fc.onBytesReceived(-1));
		assertThrows(IllegalArgumentException.class, () -> fc.onBytesConsumed(-1));
		assertThrows(IllegalArgumentException.class, () -> ConnectionFlowController.create(-1, 0));
		assertThrows(IllegalArgumentException.class, () -> ConnectionFlowController.create(0, -1));
	}

	@Test
	public void toStringCarriesTheCountersAndNoPayload() {
		ConnectionFlowController fc = controller();
		fc.onBytesReceived(400);
		fc.onBytesConsumed(100);
		String s = fc.toString();
		assertTrue(s, s.contains("400"));
		assertTrue(s, s.contains("100"));
	}

	// endregion
}
