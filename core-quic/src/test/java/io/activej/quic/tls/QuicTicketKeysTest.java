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

import io.activej.quic.crypto.QuicCipherSuite;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static io.activej.quic.tls.QuicSessionTicketTest.remembered;
import static io.activej.quic.tls.QuicSessionTicketTest.secret;
import static org.junit.Assert.*;

/**
 * T071 — the server's sealing keys: rotation as runtime state (spec FR-060), the
 * lifetime-versus-rotation arithmetic invariant, and the AEAD nonce discipline.
 * <p>
 * The invariant enforced by {@code create} is the <b>worst case</b>, not the best: a key generated at
 * {@code t} is current on {@code [t, t + R)} and dropped at {@code t + RETAINED_KEYS · R}, so a ticket
 * sealed just before a rotation is only openable for {@code R} more milliseconds — hence
 * {@code L <= (RETAINED_KEYS - 1) · R}.
 */
public final class QuicTicketKeysTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long ROTATION = 6 * HOUR;
	private static final long LIFETIME = HOUR;
	private static final long T0 = 1_700_000_000_000L;

	@Test
	public void theSealingSuiteAndRetainedKeyCountAreTheDocumentedOnes() {
		assertEquals(2, QuicTicketKeys.RETAINED_KEYS);
		assertEquals(QuicCipherSuite.AES_256_GCM, QuicTicketKeys.SEALING_SUITE);
	}

	@Test
	public void createAcceptsTheShippedDefaults() {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), ROTATION, LIFETIME, T0);

		assertEquals(ROTATION, keys.rotationIntervalMillis());
		assertEquals(LIFETIME, keys.ticketLifetimeMillis());
		assertEquals(1, keys.retainedKeyCount());
		assertEquals(0, keys.rotationCount());
	}

	@Test
	public void createRefusesALifetimeTheRetainedKeysCannotCover() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, 10 * HOUR, T0));

		assertTrue(e.getMessage().contains("sessionTicketLifetime"));
		assertTrue(e.getMessage().contains("sessionTicketKeyRotation"));
	}

	@Test
	public void createAcceptsTheExactBoundary() {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, 6 * HOUR, T0);

		assertEquals(6 * HOUR, keys.ticketLifetimeMillis());
	}

	@Test
	public void createRefusesNonPositiveIntervals() {
		assertThrows(IllegalArgumentException.class, () -> QuicTicketKeys.create(new SecureRandom(), 0, LIFETIME, T0));
		assertThrows(IllegalArgumentException.class, () -> QuicTicketKeys.create(new SecureRandom(), ROTATION, 0, T0));
		assertThrows(IllegalArgumentException.class, () -> QuicTicketKeys.create(new SecureRandom(), -1, LIFETIME, T0));
	}

	@Test
	public void rotateIfDueIsANoOpBeforeTheInterval() {
		QuicTicketKeys keys = keys();

		assertFalse(keys.rotateIfDue(T0));
		assertFalse(keys.rotateIfDue(T0 + ROTATION - 1));
		assertEquals(0, keys.rotationCount());
		assertEquals(1, keys.retainedKeyCount());
	}

	@Test
	public void rotateIfDueRotatesOnceAtTheInterval() {
		QuicTicketKeys keys = keys();

		assertTrue(keys.rotateIfDue(T0 + ROTATION));

		assertEquals(1, keys.rotationCount());
		assertEquals(2, keys.retainedKeyCount());
	}

	@Test
	public void rotateIfDueClampsAtRetainedKeys() {
		QuicTicketKeys keys = keys();

		assertTrue(keys.rotateIfDue(T0 + 100 * ROTATION));

		assertEquals(QuicTicketKeys.RETAINED_KEYS, keys.rotationCount());
		assertEquals(QuicTicketKeys.RETAINED_KEYS, keys.retainedKeyCount());
	}

	@Test
	public void rotateIfDueRealignsTheRotationGrid() {
		QuicTicketKeys keys = keys();

		assertTrue(keys.rotateIfDue(T0 + ROTATION + 1000));
		assertFalse(keys.rotateIfDue(T0 + 2 * ROTATION - 1));
		assertTrue(keys.rotateIfDue(T0 + 2 * ROTATION));

		assertEquals(2, keys.rotationCount());
	}

	@Test
	public void aBackwardsClockRotatesNothing() {
		QuicTicketKeys keys = keys();

		assertFalse(keys.rotateIfDue(T0 - 10 * ROTATION));

		assertEquals(0, keys.rotationCount());
		assertEquals(1, keys.retainedKeyCount());
	}

	@Test
	public void sealRotatesWhenDue() {
		QuicTicketKeys keys = keys();

		keys.seal(ticket(), T0 + ROTATION);

		assertEquals(1, keys.rotationCount());
	}

	@Test
	public void everySealUsesADistinctNonceUnderTheSameKey() {
		QuicTicketKeys keys = keys();
		QuicSessionTicket ticket = ticket();
		Set<String> nonces = new HashSet<>();

		for (int i = 0; i < 256; i++) {
			byte[] sealed = keys.seal(ticket, T0);
			assertTrue(nonces.add(Arrays.toString(Arrays.copyOfRange(sealed, 1, 13))));
		}

		assertEquals(256, nonces.size());
		assertEquals(0, keys.rotationCount());
	}

	@Test
	public void sealingOverheadIsTwentyNineBytesOverThePlaintext() {
		QuicTicketKeys keys = keys();
		QuicSessionTicket ticket = ticket();

		assertEquals(ticket.plaintextLength() + 29, keys.seal(ticket, T0).length);
	}

	@Test
	public void sealRefusesABlobAboveTheNewSessionTicketWireBound() {
		QuicTicketKeys keys = keys();

		assertThrows(IllegalArgumentException.class, () -> keys.seal(ticketWithSettings(new byte[0x10000]), T0));
	}

	@Test
	public void sealJustBelowTheWireBoundStillWorks() {
		QuicTicketKeys keys = keys();

		byte[] sealed = keys.seal(ticketWithSettings(new byte[60_000]), T0);

		assertTrue(sealed.length <= 0xFFFF);
		assertNotNull(keys.open(sealed));
	}

	@Test
	public void openReadsNoClockAndPerformsNoExpiryCheck() {
		QuicTicketKeys keys = keys();
		byte[] sealed = keys.seal(ticket(), T0);

		QuicSessionTicket opened = keys.open(sealed);

		assertNotNull(opened);
		assertTrue(opened.isExpiredAt(T0 + LIFETIME));
	}

	@Test
	public void openIsTotalOnRandomInput() {
		QuicTicketKeys keys = keys();
		SecureRandom random = new SecureRandom();

		for (int length : new int[]{0, 1, 28, 29, 30, 100, 8192}) {
			byte[] garbage = new byte[length];
			random.nextBytes(garbage);
			assertNull(keys.open(garbage));
		}
	}

	@Test
	public void toStringPrintsTheRetainedCountOnly() {
		QuicTicketKeys keys = keys();

		assertEquals("QuicTicketKeys[retained=1]", keys.toString());

		keys.rotateIfDue(T0 + ROTATION);

		assertEquals("QuicTicketKeys[retained=2]", keys.toString());
	}

	private static QuicTicketKeys keys() {
		return QuicTicketKeys.create(new SecureRandom(), ROTATION, LIFETIME, T0);
	}

	private static QuicSessionTicket ticket() {
		return ticketWithSettings(new byte[]{1, 2, 3});
	}

	private static QuicSessionTicket ticketWithSettings(byte[] applicationSettings) {
		return QuicSessionTicket.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(7)
			.withTransportParameters(remembered())
			.withApplicationSettings(applicationSettings)
			.build();
	}
}
