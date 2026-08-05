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

package io.activej.quic.tls;

import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.SecureRandom;

import static io.activej.quic.tls.QuicSessionTicketTest.remembered;
import static io.activej.quic.tls.QuicSessionTicketTest.secret;
import static org.junit.Assert.*;

/**
 * T057 — the two FR-043a bounds on a {@code NewSessionTicket}, which is untrusted input arriving
 * <i>after</i> the handshake: a maximum sealed-ticket size and a maximum number of tickets accepted
 * per connection, both {@code ApplicationSettings}-backed on {@link QuicConnectionSettings}.
 * <p>
 * This class covers the predicates and their composition with a really-sealed ticket. Turning a
 * {@code false} into a CONNECTION_CLOSE — "closed rather than buffered" — is
 * {@code TlsClientEngine}'s (T076); no engine consumes these predicates yet.
 */
public final class QuicSessionTicketBoundsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long T0 = 1_700_000_000_000L;

	@Test
	public void sealedSizeIsWithinTheLimitUpToAndIncludingIt() {
		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(0, 8192));
		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(1, 8192));
		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(8191, 8192));
		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(8192, 8192));
		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(8193, 8192));
		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(Integer.MAX_VALUE, 8192));
	}

	@Test
	public void aNegativeSealedSizeIsNeverWithinTheLimit() {
		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(-1, 8192));
	}

	@Test
	public void aZeroSizeLimitAdmitsNothingReal() {
		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(1, 0));
	}

	@Test
	public void aRealSealedTicketIsMeasuredAgainstTheConfiguredBound() {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
		byte[] sealed = keys.seal(ticket(new byte[64]), T0);

		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(sealed.length,
			QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKET_SIZE.toLong()));
		assertTrue(QuicSessionTicket.isSealedSizeWithinLimit(sealed.length, sealed.length));
		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(sealed.length, sealed.length - 1));
	}

	@Test
	public void aSealedTicketAboveTheDefaultBoundIsRefusedByThePredicate() {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
		long limit = QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKET_SIZE.toLong();
		byte[] sealed = keys.seal(ticket(new byte[(int) limit]), T0);

		assertFalse(QuicSessionTicket.isSealedSizeWithinLimit(sealed.length, limit));
	}

	@Test
	public void countIsWithinTheLimitUntilTheLimitHasBeenReached() {
		assertTrue(QuicSessionTicket.isCountWithinLimit(0, 8));
		assertTrue(QuicSessionTicket.isCountWithinLimit(7, 8));
		assertFalse(QuicSessionTicket.isCountWithinLimit(8, 8));
		assertFalse(QuicSessionTicket.isCountWithinLimit(9, 8));
		assertFalse(QuicSessionTicket.isCountWithinLimit(Integer.MAX_VALUE, 8));
	}

	@Test
	public void aZeroCountLimitAdmitsNoTicketAtAll() {
		assertFalse(QuicSessionTicket.isCountWithinLimit(0, 0));
	}

	@Test
	public void aNegativeAcceptedCountIsNeverWithinTheLimit() {
		assertFalse(QuicSessionTicket.isCountWithinLimit(-1, 8));
	}

	@Test
	public void theDefaultCountBoundAdmitsExactlyThatManyTickets() {
		int limit = QuicConnectionSettings.DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION;

		int accepted = 0;
		while (QuicSessionTicket.isCountWithinLimit(accepted, limit)) accepted++;

		assertEquals(limit, accepted);
	}

	private static QuicSessionTicket ticket(byte[] applicationSettings) {
		return QuicSessionTicket.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(HOUR)
			.withTicketAgeAdd(1)
			.withTransportParameters(remembered())
			.withApplicationSettings(applicationSettings)
			.build();
	}
}
