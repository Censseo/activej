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

import io.activej.quic.crypto.Hkdf;
import io.activej.quic.crypto.QuicCipherSuite;
import io.activej.quic.crypto.QuicKeys;
import org.junit.Test;

import java.util.HexFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Secrets-hygiene regression guard (FR-016, FR-020, SI-6): the {@code toString} of every
 * key-holding type prints type/role/suite only — never private keys, shared secrets, traffic
 * secrets, AEAD keys/IVs, header-protection keys or session tickets. Alert messages name the
 * failing protocol element and carry no secret material.
 */
public class TlsSecretsHygieneTest {

	@Test
	public void tlsKeysAndKeyInstallationToStringsPrintSuiteAndLevelOnly() {
		for (QuicCipherSuite suite : QuicCipherSuite.values()) {
			int hashLength = suite.hkdfHash().equals("HmacSHA384") ? 48 : 32;
			byte[] trafficSecret = deterministicBytes(hashLength);

			QuicKeys clientKeys = QuicKeys.fromTrafficSecret(suite, trafficSecret);
			QuicKeys serverKeys = QuicKeys.fromTrafficSecret(suite, trafficSecret);
			TlsKeys tlsKeys = new TlsKeys(clientKeys, serverKeys);

			assertEquals("TlsKeys[" + suite + "]", tlsKeys.toString());
			for (EncryptionLevel level : EncryptionLevel.values()) {
				assertEquals("KeyInstallation[" + level + ", TlsKeys[" + suite + "]]",
					new KeyInstallation(level, tlsKeys).toString());
			}

			// none of the derived material appears in any rendered form
			String rendered = (tlsKeys + " " + new KeyInstallation(EncryptionLevel.HANDSHAKE, tlsKeys) +
				" " + clientKeys).toLowerCase(Locale.ROOT);
			assertSecretNotRendered(rendered, trafficSecret);
			assertSecretNotRendered(rendered, clientKeys.aeadKeyBytes());
			assertSecretNotRendered(rendered, clientKeys.iv());
			assertSecretNotRendered(rendered, clientKeys.headerProtectionKey());
		}
	}

	@Test
	public void alertExceptionToStringCarriesAlertCodeAndMessageOnly() {
		byte[] sharedSecret = deterministicBytes(32);
		TlsAlertException exception = new TlsAlertException(TlsAlerts.ILLEGAL_PARAMETER,
			"x25519 shared secret is all zero — low-order peer public key (RFC 8446 §7.4.1)");

		String rendered = exception.toString();
		assertEquals("TlsAlertException[illegal_parameter(47): " + exception.getMessage() + "]", rendered);
		assertSecretNotRendered(rendered.toLowerCase(Locale.ROOT), sharedSecret);
	}

	@Test
	public void trafficSecretDerivationLeavesNoTraceInRenderedForms() {
		// the Hkdf/HKDF-Expand-Label layer renders nothing; this pins the assumption that a
		// secret's own hex can never collide with a suite/level name by accident
		byte[] trafficSecret = deterministicBytes(32);
		QuicKeys keys = QuicKeys.fromTrafficSecret(QuicCipherSuite.AES_128_GCM, trafficSecret);
		String expectedKeyHex = HexFormat.of().formatHex(
			Hkdf.expandLabel("HmacSHA256", trafficSecret, "quic key", new byte[0], 16));
		assertFalse(keys.toString().contains(expectedKeyHex));
	}

	private static void assertSecretNotRendered(String rendered, byte[] secret) {
		String hex = HexFormat.of().formatHex(secret);
		assertFalse("rendered form leaks secret material: " + rendered, rendered.contains(hex));
	}

	private static byte[] deterministicBytes(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
		return bytes;
	}
}
