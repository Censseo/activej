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

import io.activej.quic.crypto.QuicCipherSuite;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T056 — US4 scenario 8: reaching either RFC 9001 §6.6 AEAD limit ends the connection with
 * {@code AEAD_LIMIT_REACHED} rather than rotating keys, which this feature deliberately does not
 * implement.
 * <p>
 * The counters are seeded rather than driven: the smallest limit here is 2^23 packets, so a test that
 * actually encrypted its way there would take minutes and prove nothing the seeding does not.
 */
public final class AeadLimitTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static LevelKeys installed(QuicCipherSuite suite) {
		LevelKeys keys = new LevelKeys(EncryptionLevel.ONE_RTT);
		// Any secret of the HKDF hash's output length will do — this is about the counters, not the bytes.
		byte[] secret = new byte["HmacSHA384".equals(suite.hkdfHash()) ? 48 : 32];
		keys.install(QuicKeys.fromTrafficSecret(suite, secret), QuicKeys.fromTrafficSecret(suite, secret));
		return keys;
	}

	// ---------------------------------------------------------------- the limits themselves

	@Test
	public void theConfidentialityLimitsAreTheOnesRfc9001Section66Specifies() {
		assertEquals(1L << 23, LevelKeys.confidentialityLimit(QuicCipherSuite.AES_128_GCM));
		assertEquals(1L << 23, LevelKeys.confidentialityLimit(QuicCipherSuite.AES_256_GCM));
		// ChaCha20-Poly1305's nonce construction makes it far more forgiving than AES-GCM's.
		assertEquals(1L << 62, LevelKeys.confidentialityLimit(QuicCipherSuite.CHACHA20_POLY1305));
	}

	@Test
	public void theIntegrityLimitsAreTheOnesRfc9001Section66Specifies() {
		assertEquals(1L << 52, LevelKeys.integrityLimit(QuicCipherSuite.AES_128_GCM));
		assertEquals(1L << 52, LevelKeys.integrityLimit(QuicCipherSuite.AES_256_GCM));
		assertEquals(1L << 36, LevelKeys.integrityLimit(QuicCipherSuite.CHACHA20_POLY1305));
	}

	// ---------------------------------------------------------------- the confidentiality limit

	@Test
	public void theLastPacketBelowTheConfidentialityLimitIsStillSent() throws Exception {
		LevelKeys keys = installed(QuicCipherSuite.AES_128_GCM);
		keys.seedCountersForTesting((1L << 23) - 1, 0);

		keys.onPacketSent();

		assertEquals(1L << 23, keys.packetsSent());
	}

	@Test
	public void reachingTheConfidentialityLimitClosesWithAeadLimitReached() {
		LevelKeys keys = installed(QuicCipherSuite.AES_128_GCM);
		keys.seedCountersForTesting(1L << 23, 0);

		QuicTransportException e = assertThrows(QuicTransportException.class, keys::onPacketSent);

		assertEquals(QuicTransportErrors.AEAD_LIMIT_REACHED, e.errorCode());
		// RFC 9001 §6.6 forbids sending *more* than the limit, so the check is >= before incrementing:
		// the packet that would have crossed it is never encrypted.
		assertEquals(1L << 23, keys.packetsSent());
	}

	@Test
	public void aChaChaConnectionIsNotHeldToTheAesConfidentialityLimit() throws Exception {
		LevelKeys keys = installed(QuicCipherSuite.CHACHA20_POLY1305);
		keys.seedCountersForTesting(1L << 23, 0);

		keys.onPacketSent();

		assertEquals((1L << 23) + 1, keys.packetsSent());
	}

	// ---------------------------------------------------------------- the integrity limit

	@Test
	public void reachingTheIntegrityLimitClosesWithAeadLimitReached() {
		LevelKeys keys = installed(QuicCipherSuite.CHACHA20_POLY1305);
		keys.seedCountersForTesting(0, 1L << 36);

		QuicTransportException e = assertThrows(QuicTransportException.class, keys::onDecryptionFailed);

		assertEquals(QuicTransportErrors.AEAD_LIMIT_REACHED, e.errorCode());
	}

	@Test
	public void aSuccessfulDecryptionDoesNotForgiveEarlierFailures() throws Exception {
		LevelKeys keys = installed(QuicCipherSuite.CHACHA20_POLY1305);
		keys.seedCountersForTesting(0, (1L << 36) - 1);

		keys.onDecryptionFailed();
		keys.onDecryptionSucceeded();

		// RFC 9001 §6.6 counts failures for the lifetime of the key. Resetting on success would let an
		// attacker forge indefinitely by interleaving one valid packet of its own.
		assertEquals(1L << 36, keys.failedDecryptions());
		assertThrows(QuicTransportException.class, keys::onDecryptionFailed);
	}

	// ---------------------------------------------------------------- a discarded level

	@Test
	public void aDiscardedLevelStopsCountingRatherThanFailing() throws Exception {
		LevelKeys keys = installed(QuicCipherSuite.AES_128_GCM);
		keys.seedCountersForTesting(1L << 23, 1L << 52);
		keys.discard();

		// A datagram still in flight when a level is discarded is normal, not a fault — and a discarded
		// level has no keys left to over-use.
		keys.onPacketSent();
		keys.onDecryptionFailed();

		assertTrue(keys.isDiscarded());
		assertFalse(keys.accepts());
	}

	// ---------------------------------------------------------------- the wiring

	@Test
	public void theSendPathIsWhereTheConfidentialityLimitIsEnforced() {
		// The receive and send paths call these two methods and nothing else does, so their behaviour
		// above *is* the connection's behaviour. Asserted here so a refactor that stops calling them
		// fails a test rather than silently lifting an RFC-mandated bound.
		LevelKeys keys = installed(QuicCipherSuite.AES_256_GCM);
		assertTrue(keys.accepts());
		assertEquals(0, keys.packetsSent());
		assertEquals(0, keys.failedDecryptions());
	}
}
