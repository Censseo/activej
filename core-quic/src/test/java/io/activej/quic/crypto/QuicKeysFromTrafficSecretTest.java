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

package io.activej.quic.crypto;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * {@link QuicKeys#fromTrafficSecret(QuicCipherSuite, byte[])} — the RFC 9001 §5.1
 * {@code quic key}/{@code quic iv}/{@code quic hp} derivation applied to a TLS 1.3 traffic
 * secret (Handshake or 1-RTT level, RFC 8446 §7.1) — pinned against the explicit
 * {@link Hkdf#expandLabel} calls for all three suites, including the SHA-384 path.
 */
public class QuicKeysFromTrafficSecretTest {

	@Test
	public void derivesSameMaterialAsExplicitLabelsForAllSuites() {
		for (QuicCipherSuite suite : QuicCipherSuite.values()) {
			int hashLength = suite.hkdfHash().equals("HmacSHA384") ? 48 : 32;
			byte[] trafficSecret = deterministicBytes(hashLength);

			QuicKeys keys = QuicKeys.fromTrafficSecret(suite, trafficSecret);

			byte[] expectedKey = Hkdf.expandLabel(suite.hkdfHash(), trafficSecret, "quic key", new byte[0], suite.keyLength());
			byte[] expectedIv = Hkdf.expandLabel(suite.hkdfHash(), trafficSecret, "quic iv", new byte[0], suite.ivLength());
			byte[] expectedHp = Hkdf.expandLabel(suite.hkdfHash(), trafficSecret, "quic hp", new byte[0], suite.keyLength());

			assertEquals(suite, keys.suite());
			assertArrayEquals("quic key mismatch for " + suite, expectedKey, keys.aeadKeyBytes());
			assertArrayEquals("quic iv mismatch for " + suite, expectedIv, keys.iv());
			assertArrayEquals("quic hp mismatch for " + suite, expectedHp, keys.headerProtectionKey());
		}
	}

	@Test
	public void mutatingTheCallerSecretAfterDerivationDoesNotAffectTheKeys() {
		byte[] trafficSecret = deterministicBytes(32);
		QuicKeys keys = QuicKeys.fromTrafficSecret(QuicCipherSuite.AES_128_GCM, trafficSecret.clone());

		trafficSecret[0] ^= (byte) 0xFF;
		QuicKeys mutated = QuicKeys.fromTrafficSecret(QuicCipherSuite.AES_128_GCM, trafficSecret);

		assertFalse(java.util.Arrays.equals(keys.aeadKeyBytes(), mutated.aeadKeyBytes()));
	}

	@Test
	public void defensiveCopiesAreReturnedOnEveryAccess() {
		QuicKeys keys = QuicKeys.fromTrafficSecret(QuicCipherSuite.CHACHA20_POLY1305, deterministicBytes(32));

		byte[] iv1 = keys.iv();
		byte[] original = iv1.clone();
		iv1[0] ^= (byte) 0xFF;
		assertArrayEquals(original, keys.iv());

		byte[] hp = keys.headerProtectionKey();
		hp[0] ^= (byte) 0xFF;
		assertFalse(java.util.Arrays.equals(hp, keys.headerProtectionKey()));
	}

	@Test
	public void toStringPrintsTheSuiteOnly() {
		QuicKeys keys = QuicKeys.fromTrafficSecret(QuicCipherSuite.AES_256_GCM, deterministicBytes(48));
		assertEquals("QuicKeys[AES_256_GCM]", keys.toString());
	}

	private static byte[] deterministicBytes(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
		return bytes;
	}
}
