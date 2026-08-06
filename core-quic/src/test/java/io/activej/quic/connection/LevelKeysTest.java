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

import io.activej.quic.QuicConnectionId;
import io.activej.quic.crypto.InitialKeys;
import io.activej.quic.crypto.QuicCipherSuite;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

public class LevelKeysTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static QuicKeys keysFor(QuicCipherSuite suite) {
		// A traffic secret of the suite's hash length is all fromTrafficSecret needs.
		byte[] secret = new byte[suite == QuicCipherSuite.AES_256_GCM ? 48 : 32];
		for (int i = 0; i < secret.length; i++) {
			secret[i] = (byte) i;
		}
		return QuicKeys.fromTrafficSecret(suite, secret);
	}

	private static LevelKeys installed(QuicCipherSuite suite) {
		LevelKeys level = new LevelKeys(EncryptionLevel.ONE_RTT);
		level.install(keysFor(suite), keysFor(suite));
		return level;
	}

	@Test
	public void limitsPerSuite() {
		// All three suites, not two: AES_256_GCM shares the AES-GCM limits, and a switch that omitted
		// it would silently apply a wrong cryptographic bound.
		assertEquals(1L << 23, LevelKeys.confidentialityLimit(QuicCipherSuite.AES_128_GCM));
		assertEquals(1L << 23, LevelKeys.confidentialityLimit(QuicCipherSuite.AES_256_GCM));
		assertEquals(1L << 62, LevelKeys.confidentialityLimit(QuicCipherSuite.CHACHA20_POLY1305));

		assertEquals(1L << 52, LevelKeys.integrityLimit(QuicCipherSuite.AES_128_GCM));
		assertEquals(1L << 52, LevelKeys.integrityLimit(QuicCipherSuite.AES_256_GCM));
		assertEquals(1L << 36, LevelKeys.integrityLimit(QuicCipherSuite.CHACHA20_POLY1305));

		// Exhaustive over the enum — a new suite must not fall through to a default.
		for (QuicCipherSuite suite : QuicCipherSuite.values()) {
			assertTrue(LevelKeys.confidentialityLimit(suite) > 0);
			assertTrue(LevelKeys.integrityLimit(suite) > 0);
		}
	}

	@Test
	public void freshLevelIsNotInstalled() {
		LevelKeys level = new LevelKeys(EncryptionLevel.HANDSHAKE);
		assertFalse(level.isInstalled());
		assertFalse(level.accepts());
		assertFalse(level.isDiscarded());
		assertEquals(EncryptionLevel.HANDSHAKE, level.level());
		assertEquals(0, level.packetsSent());
		assertEquals(0, level.failedDecryptions());
	}

	@Test
	public void installingKeysMakesThemCurrent() {
		QuicKeys send = keysFor(QuicCipherSuite.AES_128_GCM);
		QuicKeys receive = keysFor(QuicCipherSuite.AES_128_GCM);
		LevelKeys level = new LevelKeys(EncryptionLevel.INITIAL);
		level.install(send, receive);

		assertTrue(level.isInstalled());
		assertTrue(level.accepts());
		assertSame(send, level.sendKeys());
		assertSame(receive, level.receiveKeys());
	}

	@Test
	public void keySwapIsAtomic() {
		LevelKeys level = new LevelKeys(EncryptionLevel.ONE_RTT);
		QuicKeys sendA = keysFor(QuicCipherSuite.AES_128_GCM);
		QuicKeys receiveA = keysFor(QuicCipherSuite.AES_128_GCM);
		level.install(sendA, receiveA);

		QuicKeys sendB = keysFor(QuicCipherSuite.CHACHA20_POLY1305);
		QuicKeys receiveB = keysFor(QuicCipherSuite.CHACHA20_POLY1305);
		level.install(sendB, receiveB);

		// Both directions moved together; no mixed epoch is reachable because install() is the only
		// installer and takes both.
		assertSame(sendB, level.sendKeys());
		assertSame(receiveB, level.receiveKeys());
	}

	@Test
	public void initialKeysCanBeInstalled() {
		InitialKeys initial = QuicKeys.initial(QuicConnectionId.random(8));
		LevelKeys level = new LevelKeys(EncryptionLevel.INITIAL);
		level.install(initial.client(), initial.server());
		assertTrue(level.accepts());
		assertEquals(QuicCipherSuite.AES_128_GCM, level.sendKeys().suite());
	}

	@Test
	public void confidentialityLimitRaisesAeadLimitReached() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		long limit = LevelKeys.confidentialityLimit(QuicCipherSuite.AES_128_GCM);

		// Seeded rather than looped: 2^23 iterations is not a test.
		level.seedCountersForTesting(limit - 1, 0);
		level.onPacketSent(); // the last permitted packet
		assertEquals(limit, level.packetsSent());

		QuicTransportException e = assertThrows(QuicTransportException.class, level::onPacketSent);
		assertEquals(QuicTransportErrors.AEAD_LIMIT_REACHED, e.errorCode());
		// The rejected packet was not counted.
		assertEquals(limit, level.packetsSent());
	}

	@Test
	public void integrityLimitRaisesAeadLimitReached() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.CHACHA20_POLY1305);
		long limit = LevelKeys.integrityLimit(QuicCipherSuite.CHACHA20_POLY1305);

		level.seedCountersForTesting(0, limit - 1);
		level.onDecryptionFailed();
		assertEquals(limit, level.failedDecryptions());

		QuicTransportException e = assertThrows(QuicTransportException.class, level::onDecryptionFailed);
		assertEquals(QuicTransportErrors.AEAD_LIMIT_REACHED, e.errorCode());
	}

	@Test
	public void successfulDecryptionDoesNotResetTheFailureCounter() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		level.onDecryptionFailed();
		level.onDecryptionFailed();
		assertEquals(2, level.failedDecryptions());

		level.onDecryptionSucceeded();
		// RFC 9001 §6.6 counts failures for the lifetime of the key.
		assertEquals(2, level.failedDecryptions());
	}

	@Test
	public void discardedLevelDropsPacketsSilently() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		level.discard();

		assertTrue(level.isDiscarded());
		assertFalse(level.accepts());
		assertFalse(level.isInstalled());

		// No exception: a datagram in flight when a level is discarded is normal.
		level.onPacketSent();
		level.onDecryptionFailed();
		level.onDecryptionSucceeded();

		// Idempotent.
		level.discard();
		assertTrue(level.isDiscarded());
	}

	@Test
	public void discardAtTheLimitStillDoesNotThrow() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		level.seedCountersForTesting(LevelKeys.confidentialityLimit(QuicCipherSuite.AES_128_GCM), 0);
		level.discard();
		level.onPacketSent(); // must not throw once discarded
	}

	@Test
	public void countersStartAtZeroAndOrdinaryUseIsFarFromTheLimits() throws Exception {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		assertEquals(0, level.packetsSent());
		assertEquals(0, level.failedDecryptions());

		for (int i = 0; i < 1000; i++) {
			level.onPacketSent();
		}
		for (int i = 0; i < 100; i++) {
			level.onDecryptionFailed();
		}
		assertEquals(1000, level.packetsSent());
		assertEquals(100, level.failedDecryptions());
	}

	@Test
	public void noSecretsInToString() {
		LevelKeys level = installed(QuicCipherSuite.AES_128_GCM);
		String s = level.toString();

		assertTrue(s.contains("AES_128_GCM"));
		QuicKeys keys = level.sendKeys();
		assertFalse("AEAD key bytes must not appear", s.contains(hex(keys.aeadKeyBytes())));
		assertFalse("IV must not appear", s.contains(hex(keys.iv())));
		assertFalse("HP key must not appear", s.contains(hex(keys.headerProtectionKey())));
	}

	/**
	 * {@code ZERO_RTT} is one-directional by protocol: a client protects 0-RTT packets and never opens
	 * one, a server opens them and may never send one (RFC 9001 §4.6.1). Both halves are asserted,
	 * because the two roles reach opposite conclusions from the same installation.
	 */
	@Test
	public void aZeroRttLevelIsInstalledInExactlyOneDirection() {
		LevelKeys clientSide = new LevelKeys(EncryptionLevel.ZERO_RTT);
		clientSide.install(keysFor(QuicCipherSuite.AES_128_GCM), null);
		assertTrue(clientSide.acceptsSend());
		assertFalse(clientSide.acceptsReceive());
		assertFalse("accepts() means both directions", clientSide.accepts());
		assertFalse(clientSide.isInstalled());
		assertTrue(clientSide.isEitherDirectionInstalled());

		LevelKeys serverSide = new LevelKeys(EncryptionLevel.ZERO_RTT);
		serverSide.install(null, keysFor(QuicCipherSuite.AES_128_GCM));
		assertFalse(serverSide.acceptsSend());
		assertTrue(serverSide.acceptsReceive());
		assertFalse(serverSide.accepts());
		assertTrue(serverSide.isEitherDirectionInstalled());
	}

	@Test
	public void anUninstalledOrDiscardedLevelAcceptsNeitherDirection() {
		LevelKeys never = new LevelKeys(EncryptionLevel.ZERO_RTT);
		assertFalse(never.acceptsSend());
		assertFalse(never.acceptsReceive());
		assertFalse(never.isEitherDirectionInstalled());

		LevelKeys discarded = installed(QuicCipherSuite.AES_128_GCM);
		discarded.discard();
		assertFalse(discarded.acceptsSend());
		assertFalse(discarded.acceptsReceive());
		assertFalse(discarded.accepts());
	}

	/** A one-directional level must not blow up on the counters of the direction it does not have. */
	@Test
	public void theCountersOfAnAbsentDirectionAreNoOps() throws Exception {
		LevelKeys clientSide = new LevelKeys(EncryptionLevel.ZERO_RTT);
		clientSide.install(keysFor(QuicCipherSuite.AES_128_GCM), null);
		clientSide.onDecryptionFailed();
		assertEquals(0, clientSide.failedDecryptions());
		clientSide.onPacketSent();
		assertEquals(1, clientSide.packetsSent());

		LevelKeys serverSide = new LevelKeys(EncryptionLevel.ZERO_RTT);
		serverSide.install(null, keysFor(QuicCipherSuite.AES_128_GCM));
		serverSide.onPacketSent();
		assertEquals(0, serverSide.packetsSent());
		serverSide.onDecryptionFailed();
		assertEquals(1, serverSide.failedDecryptions());
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
