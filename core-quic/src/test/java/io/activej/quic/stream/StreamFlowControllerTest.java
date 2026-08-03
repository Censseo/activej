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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 9000 §4.1 (data flow control) — the pure, per-direction half. No buffers, no eventloop.
 */
public final class StreamFlowControllerTest {
	private static final long WINDOW = 1024;

	// region available() (FR-021, FR-024)

	@Test
	public void availableIsLimitMinusUsed() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertEquals(WINDOW, fc.limit());
		assertEquals(0, fc.used());
		assertEquals(WINDOW, fc.available());

		fc.consume(400);
		assertEquals(400, fc.used());
		assertEquals(624, fc.available());

		fc.consume(624);
		assertEquals(0, fc.available());
		assertTrue(fc.isBlocked());
	}

	@Test
	public void aFreshControllerWithNoCreditIsBlocked() {
		StreamFlowController fc = new StreamFlowController(0);

		assertEquals(0, fc.available());
		assertTrue(fc.isBlocked());

		fc.raiseLimit(10);
		assertFalse(fc.isBlocked());
		assertEquals(10, fc.available());
	}

	@Test
	public void negativeInitialLimitIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new StreamFlowController(-1));
	}

	@Test
	public void consumingANegativeAmountIsRejected() {
		StreamFlowController fc = new StreamFlowController(WINDOW);
		assertThrows(IllegalArgumentException.class, () -> fc.consume(-1));
	}

	// endregion

	// region the limit only ever rises (FR-026)

	@Test
	public void theLimitIsRaisedOnlyUpwards() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertTrue(fc.raiseLimit(2048));
		assertEquals(2048, fc.limit());

		// RFC 9000 §4.1: a lower limit is ignored without error, not treated as a protocol violation
		assertFalse(fc.raiseLimit(512));
		assertEquals(2048, fc.limit());

		// an equal limit is a no-op too — nothing to announce, nothing to unblock
		assertFalse(fc.raiseLimit(2048));
		assertEquals(2048, fc.limit());

		assertFalse(fc.raiseLimit(0));
		assertEquals(2048, fc.limit());
	}

	@Test
	public void raisingTheLimitUnblocksTheSender() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		fc.consume(WINDOW);
		assertTrue(fc.isBlocked());
		assertEquals(0, fc.available());

		assertTrue(fc.raiseLimit(WINDOW + 256));
		assertFalse(fc.isBlocked());
		assertEquals(256, fc.available());
	}

	// endregion

	// region receive-side overrun (FR-024)

	@Test
	public void receiveSideOverrunIsDetectable() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		fc.advanceUsedTo(WINDOW);
		assertFalse(fc.isOverrun());
		assertEquals(0, fc.available());

		fc.advanceUsedTo(WINDOW + 1);
		assertTrue(fc.isOverrun());
		assertEquals(-1, fc.available());
	}

	@Test
	public void anIncomingRangeCanBeCheckedBeforeItIsAccepted() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertFalse(fc.exceedsLimit(WINDOW));
		assertTrue(fc.exceedsLimit(WINDOW + 1));
		// the check does not move anything
		assertEquals(0, fc.used());
		assertFalse(fc.isOverrun());
	}

	@Test
	public void theHighestReceivedOffsetNeverDecreases() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		fc.advanceUsedTo(600);
		assertEquals(600, fc.used());

		// a reordered frame ending below the high-water mark must not give credit back
		fc.advanceUsedTo(200);
		assertEquals(600, fc.used());
		assertEquals(424, fc.available());
	}

	// endregion

	// region credit grants (FR-025, clarification Q5)

	@Test
	public void creditIsGrantedAtTheHalfConsumedThreshold() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertFalse(fc.shouldGrantCredit(0, WINDOW));
		assertFalse(fc.shouldGrantCredit(WINDOW / 2 - 1, WINDOW));
		assertTrue(fc.shouldGrantCredit(WINDOW / 2, WINDOW));
		assertTrue(fc.shouldGrantCredit(WINDOW, WINDOW));
	}

	@Test
	public void theGrantedLimitIsConsumedPlusTheConfiguredWindow() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertEquals(WINDOW / 2 + WINDOW, fc.grantedLimit(WINDOW / 2, WINDOW));
		assertEquals(WINDOW + WINDOW, fc.grantedLimit(WINDOW, WINDOW));
	}

	@Test
	public void aFullGrantCycleRestoresTheWindowAboveTheConsumedOffset() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		long consumed = WINDOW / 2;
		assertTrue(fc.shouldGrantCredit(consumed, WINDOW));

		long granted = fc.grantedLimit(consumed, WINDOW);
		assertTrue(fc.raiseLimit(granted));
		assertEquals(consumed + WINDOW, fc.limit());

		// immediately afterwards, a full window is outstanding again, so nothing more is due
		assertFalse(fc.shouldGrantCredit(consumed, WINDOW));

		// ... until the application has consumed half of the fresh window
		assertFalse(fc.shouldGrantCredit(consumed + WINDOW / 2 - 1, WINDOW));
		assertTrue(fc.shouldGrantCredit(consumed + WINDOW / 2, WINDOW));
	}

	@Test
	public void theGrantedLimitIsClampedToTheProtocolMaximum() {
		StreamFlowController fc = new StreamFlowController(StreamFlowController.MAX_LIMIT);

		assertEquals(StreamFlowController.MAX_LIMIT,
			fc.grantedLimit(StreamFlowController.MAX_LIMIT - 1, WINDOW));
	}

	@Test
	public void grantArgumentsAreValidated() {
		StreamFlowController fc = new StreamFlowController(WINDOW);

		assertThrows(IllegalArgumentException.class, () -> fc.shouldGrantCredit(-1, WINDOW));
		assertThrows(IllegalArgumentException.class, () -> fc.shouldGrantCredit(0, 0));
		assertThrows(IllegalArgumentException.class, () -> fc.grantedLimit(-1, WINDOW));
		assertThrows(IllegalArgumentException.class, () -> fc.grantedLimit(0, -1));
	}

	@Test
	public void theWindowUpdateFractionIsAHalf() {
		assertEquals(0.5, StreamFlowController.WINDOW_UPDATE_FRACTION, 0.0);
	}

	// endregion
}
