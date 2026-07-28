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

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link TlsServerIdentity} loading (FR-009): PEM chains + unencrypted PKCS#8 keys for all
 * three key types, a PKCS12 keystore, and the two negative fixtures — an encrypted PKCS#8 key
 * (clear load-time error naming the unsupported format) and a key that does not match the
 * certificate (load-time sign/verify probe failure).
 */
public class TlsServerIdentityTest {

	@Test
	public void loadsRsaFromPem() throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
		assertEquals(1, identity.chain().length);
		assertEquals("RSA", identity.leaf().getPublicKey().getAlgorithm());
		assertEquals("CN=localhost", identity.leaf().getSubjectX500Principal().getName());
		assertEquals(
			List.of(SignatureScheme.RSA_PSS_RSAE_SHA256, SignatureScheme.RSA_PSS_RSAE_SHA384, SignatureScheme.RSA_PSS_RSAE_SHA512),
			identity.signatureSchemes());
	}

	@Test
	public void loadsEcdsaFromPem() throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(fixture("ecdsa-cert.pem"), fixture("ecdsa-key.pem"));
		assertEquals(1, identity.chain().length);
		assertEquals("EC", identity.leaf().getPublicKey().getAlgorithm());
		assertEquals(List.of(SignatureScheme.ECDSA_SECP256R1_SHA256), identity.signatureSchemes());
	}

	@Test
	public void loadsEd25519FromPem() throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(fixture("ed25519-cert.pem"), fixture("ed25519-key.pem"));
		assertEquals(1, identity.chain().length);
		String algorithm = identity.leaf().getPublicKey().getAlgorithm();
		assertTrue(algorithm, algorithm.equals("Ed25519") || algorithm.equals("EdDSA"));
		assertEquals(List.of(SignatureScheme.ED25519), identity.signatureSchemes());
	}

	@Test
	public void loadsFromKeyStore() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (InputStream in = getClass().getResourceAsStream("rsa-keystore.p12")) {
			assertNotNull("rsa-keystore.p12 fixture is on the classpath", in);
			keyStore.load(in, "activej-test".toCharArray());
		}
		TlsServerIdentity identity = TlsServerIdentity.fromKeyStore(keyStore, "server", "activej-test".toCharArray());
		assertEquals(1, identity.chain().length);
		assertEquals("CN=localhost", identity.leaf().getSubjectX500Principal().getName());
		assertEquals("RSA", identity.leaf().getPublicKey().getAlgorithm());
	}

	@Test
	public void encryptedPkcs8FailsNamingTheUnsupportedFormat() throws Exception {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> TlsServerIdentity.fromPem(fixture("ecdsa-cert.pem"), fixture("encrypted-key.pem")));
		String message = e.getMessage();
		assertNotNull(message);
		assertTrue(message, message.contains("encrypted-key.pem"));
		assertTrue(message, message.toLowerCase().contains("encrypted pkcs#8"));
	}

	@Test
	public void mismatchedKeyFailsTheLoadTimeProbe() throws Exception {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("mismatched-key.pem")));
		String message = e.getMessage();
		assertNotNull(message);
		assertTrue(message, message.contains("mismatched-key.pem"));
		assertTrue(message, message.contains("does not match"));
	}

	@Test
	public void toStringPrintsSubjectDnAndChainLengthOnly() throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
		String string = identity.toString();
		assertTrue(string, string.contains("CN=localhost"));
		assertTrue(string, string.contains("1"));
		// secrets hygiene: no key material markers, no PEM/DER payloads
		assertFalse(string, string.contains("PRIVATE"));
		assertFalse(string, string.contains("BEGIN"));
	}

	static Path fixture(String name) {
		try {
			java.net.URL resource = TlsServerIdentityTest.class.getResource(name);
			assertNotNull(name + " fixture is on the classpath", resource);
			return Path.of(resource.toURI());
		} catch (URISyntaxException e) {
			throw new AssertionError(e);
		}
	}
}
