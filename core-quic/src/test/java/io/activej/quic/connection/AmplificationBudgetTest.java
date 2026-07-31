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

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The RFC 9000 §8.1 limit is pure arithmetic — no clock, no buffers, no reactor. If a test here needs
 * time, the design has drifted.
 */
public class AmplificationBudgetTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void initiallyNothingMayBeSent() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		assertEquals(0, budget.remaining());
		assertFalse(budget.canSend(1));
		assertFalse(budget.isValidated());
	}

	@Test
	public void threeTimesReceivedMayBeSent() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(1200);
		assertEquals(3600, budget.remaining());
		assertTrue(budget.canSend(3600));
		assertFalse(budget.canSend(3601));
	}

	@Test
	public void sentBytesAreDeducted() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(1200);
		budget.onDatagramSent(1000);
		assertEquals(2600, budget.remaining());
		budget.onDatagramSent(2600);
		assertEquals(0, budget.remaining());
	}

	@Test
	public void multiplierAppliesToBytesNotDatagrams() {
		// Asymmetric sizes: a per-datagram implementation would get this wrong.
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(100);
		budget.onDatagramReceived(1400);
		assertEquals(3 * 1500, budget.remaining());
	}

	@Test
	public void failedDecryptionsStillCount() {
		// There is deliberately only one receive entry point, so a datagram that fails to decrypt
		// raises the budget exactly like one that succeeds (RFC 9000 §8.1). A budget that only
		// counted decryptable bytes would deadlock a handshake after a Retry.
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(1200); // undecryptable, as far as this class is concerned
		assertEquals(3600, budget.remaining());
	}

	@Test
	public void remainingIsFlooredAtZero() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(100);   // budget 300
		budget.onDatagramSent(1000);      // overdraft of 700
		assertEquals(0, budget.remaining());
		assertFalse(budget.canSend(1));

		// The overdraft is not forgiven: the real bytesSent is kept, so receiving 100 more does not
		// hand back a full 300.
		budget.onDatagramReceived(100);
		assertEquals(0, budget.remaining());
		assertEquals(1000, budget.bytesSent());
		assertEquals(200, budget.bytesReceived());

		// Only once 3 × received actually overtakes sent does anything become sendable.
		budget.onDatagramReceived(200);   // 3 × 400 = 1200 > 1000
		assertEquals(200, budget.remaining());
	}

	@Test
	public void validationDisablesTheBudget() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(10);
		budget.onDatagramSent(10_000);
		assertEquals(0, budget.remaining());

		budget.setValidated();
		assertTrue(budget.isValidated());
		assertEquals(Long.MAX_VALUE, budget.remaining());
		assertTrue(budget.canSend(Integer.MAX_VALUE));

		budget.onDatagramSent(1_000_000);
		assertEquals(Long.MAX_VALUE, budget.remaining());
	}

	@Test
	public void validationIsIrreversible() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.setValidated();
		budget.setValidated();
		assertTrue(budget.isValidated());
		assertEquals(Long.MAX_VALUE, budget.remaining());
	}

	@Test
	public void clientRoleIsUnlimitedFromTheStart() {
		AmplificationBudget budget = AmplificationBudget.validated();
		assertTrue(budget.isValidated());
		assertEquals(Long.MAX_VALUE, budget.remaining());
		assertTrue(budget.canSend(65527));
		budget.onDatagramSent(65527);
		assertEquals(Long.MAX_VALUE, budget.remaining());
	}

	@Test
	public void canSendAgreesWithRemainingAtTheBoundary() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(400); // budget 1200
		assertTrue(budget.canSend(1200));
		assertFalse(budget.canSend(1201));
		budget.onDatagramSent(1200);
		assertTrue(budget.canSend(0));
		assertFalse(budget.canSend(1));
	}

	@Test
	public void hugeReceivedCountDoesNotOverflow() {
		AmplificationBudget budget = AmplificationBudget.forServer();
		budget.onDatagramReceived(Integer.MAX_VALUE);
		for (int i = 0; i < 4; i++) {
			budget.onDatagramReceived(Integer.MAX_VALUE);
		}
		assertTrue("remaining must stay positive, not wrap negative", budget.remaining() > 0);
	}
}
