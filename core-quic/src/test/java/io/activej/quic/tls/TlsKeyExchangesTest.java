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

import org.junit.Test;

import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * ECDHE primitives (RFC 8446 §7.4, §4.2.7) for the two groups of the QUIC profile:
 * generate/agree round-trips, the RFC 8448 §3 x25519 determinism replay, and the hygiene
 * checks of research Decision 11 — length checks before any crypto ({@code decode_error}),
 * the explicit all-zero X25519 shared-secret check ({@code illegal_parameter}, RFC 8446 §7.4.1),
 * and malformed P-256 points mapped to alerts.
 */
public class TlsKeyExchangesTest {

	@Test
	public void x25519GenerateEncodeAgreeRoundTrip() throws Exception {
		KeyPair a = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
		KeyPair b = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);

		byte[] aPublic = TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, a.getPublic());
		byte[] bPublic = TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, b.getPublic());
		assertEquals(32, aPublic.length);
		assertEquals(32, bPublic.length);

		byte[] secretAB = TlsKeyExchanges.agree(NamedGroup.X25519, a.getPrivate(), bPublic);
		byte[] secretBA = TlsKeyExchanges.agree(NamedGroup.X25519, b.getPrivate(), aPublic);
		assertEquals(32, secretAB.length);
		assertArrayEquals(secretAB, secretBA);
		assertFalse(isAllZero(secretAB));
	}

	@Test
	public void secp256r1GenerateEncodeAgreeRoundTrip() throws Exception {
		KeyPair a = TlsKeyExchanges.generateKeyPair(NamedGroup.SECP256R1);
		KeyPair b = TlsKeyExchanges.generateKeyPair(NamedGroup.SECP256R1);

		byte[] aPublic = TlsKeyExchanges.encodePublicKey(NamedGroup.SECP256R1, a.getPublic());
		byte[] bPublic = TlsKeyExchanges.encodePublicKey(NamedGroup.SECP256R1, b.getPublic());
		assertEquals(65, aPublic.length);
		assertEquals(0x04, aPublic[0] & 0xFF);
		assertEquals(65, bPublic.length);

		byte[] secretAB = TlsKeyExchanges.agree(NamedGroup.SECP256R1, a.getPrivate(), bPublic);
		byte[] secretBA = TlsKeyExchanges.agree(NamedGroup.SECP256R1, b.getPrivate(), aPublic);
		assertEquals(32, secretAB.length);
		assertArrayEquals(secretAB, secretBA);
		assertFalse(isAllZero(secretAB));
	}

	@Test
	public void rfc8448ClientPrivatePlusServerPublicYieldsPublishedSharedSecret() throws Exception {
		// RFC 8448 §3: the client's ephemeral x25519 private key and the server's ephemeral
		// public key must produce the published ECDHE shared secret.
		KeyPair clientKeyPair = TlsKeyExchanges.keyPairFromPrivateBytes(NamedGroup.X25519, Rfc8448.CLIENT_EPHEMERAL_PRIVATE);
		assertArrayEquals(Rfc8448.CLIENT_EPHEMERAL_PUBLIC,
			TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, clientKeyPair.getPublic()));

		byte[] secret = TlsKeyExchanges.agree(NamedGroup.X25519, clientKeyPair.getPrivate(), Rfc8448.SERVER_EPHEMERAL_PUBLIC);
		assertArrayEquals(Rfc8448.ECDHE_SHARED_SECRET, secret);
	}

	@Test
	public void rfc8448ServerPrivatePlusClientPublicYieldsTheSameSharedSecret() throws Exception {
		KeyPair serverKeyPair = TlsKeyExchanges.keyPairFromPrivateBytes(NamedGroup.X25519, Rfc8448.SERVER_EPHEMERAL_PRIVATE);
		assertArrayEquals(Rfc8448.SERVER_EPHEMERAL_PUBLIC,
			TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, serverKeyPair.getPublic()));

		byte[] secret = TlsKeyExchanges.agree(NamedGroup.X25519, serverKeyPair.getPrivate(), Rfc8448.CLIENT_EPHEMERAL_PUBLIC);
		assertArrayEquals(Rfc8448.ECDHE_SHARED_SECRET, secret);
	}

	@Test
	public void publicKeyDecodeEncodeRoundTripsExactly() throws Exception {
		for (NamedGroup group : NamedGroup.values()) {
			KeyPair keyPair = TlsKeyExchanges.generateKeyPair(group);
			byte[] encoded = TlsKeyExchanges.encodePublicKey(group, keyPair.getPublic());
			byte[] reencoded = TlsKeyExchanges.encodePublicKey(group, TlsKeyExchanges.decodePublicKey(group, encoded));
			assertArrayEquals(encoded, reencoded);
		}
	}

	@Test
	public void wrongLengthX25519KeyShareFailsBeforeCrypto() {
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
		for (int length : new int[] {0, 31, 33, 64}) {
			try {
				TlsKeyExchanges.agree(NamedGroup.X25519, keyPair.getPrivate(), new byte[length]);
				fail("expected TlsAlertException for " + length + " bytes");
			} catch (TlsAlertException expected) {
				assertEquals(TlsAlerts.DECODE_ERROR, expected.alertCode());
			}
		}
	}

	@Test
	public void wrongLengthSecp256r1KeyShareFailsBeforeCrypto() {
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.SECP256R1);
		for (int length : new int[] {0, 64, 66}) {
			byte[] keyShare = new byte[length];
			if (length > 0) keyShare[0] = 0x04;
			try {
				TlsKeyExchanges.agree(NamedGroup.SECP256R1, keyPair.getPrivate(), keyShare);
				fail("expected TlsAlertException for " + length + " bytes");
			} catch (TlsAlertException expected) {
				assertEquals(TlsAlerts.DECODE_ERROR, expected.alertCode());
			}
		}
	}

	@Test
	public void secp256r1KeyShareWithoutUncompressedPointPrefixFailsBeforeCrypto() {
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.SECP256R1);
		byte[] keyShare = new byte[65];
		keyShare[0] = 0x05; // not the uncompressed-point prefix 0x04 (RFC 8446 §4.2.8.2)
		try {
			TlsKeyExchanges.agree(NamedGroup.SECP256R1, keyPair.getPrivate(), keyShare);
			fail("expected TlsAlertException");
		} catch (TlsAlertException expected) {
			assertEquals(TlsAlerts.DECODE_ERROR, expected.alertCode());
		}
	}

	@Test
	public void allZeroX25519SharedSecretIsRejectedAsIllegalParameter() {
		// RFC 8446 §7.4.1: an all-zero X25519 shared secret (a crafted low-order point — here the
		// all-zero public key) MUST abort with illegal_parameter, whether the JDK provider
		// rejects the point itself or returns the zero secret for us to check explicitly.
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
		try {
			TlsKeyExchanges.agree(NamedGroup.X25519, keyPair.getPrivate(), new byte[32]);
			fail("expected TlsAlertException");
		} catch (TlsAlertException expected) {
			assertEquals(TlsAlerts.ILLEGAL_PARAMETER, expected.alertCode());
		}
	}

	@Test
	public void malformedSecp256r1PointIsMappedToAnAlert() {
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.SECP256R1);
		byte[] keyShare = new byte[65];
		keyShare[0] = 0x04;
		Arrays.fill(keyShare, 1, 65, (byte) 0x01); // (x, y) = (1, 1) — inside the field, off the curve
		try {
			TlsKeyExchanges.agree(NamedGroup.SECP256R1, keyPair.getPrivate(), keyShare);
			fail("expected TlsAlertException");
		} catch (TlsAlertException expected) {
			assertEquals(TlsAlerts.DECODE_ERROR, expected.alertCode());
		}
	}

	@Test
	public void rawPrivateKeyHookRejectsWrongLength() {
		try {
			TlsKeyExchanges.keyPairFromPrivateBytes(NamedGroup.X25519, new byte[31]);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected: a local determinism-hook misuse, not a wire error
		}
	}

	private static boolean isAllZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0) return false;
		}
		return true;
	}
}
