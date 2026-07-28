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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CertificateVerify signing and verification (RFC 8446 §4.4.3, FR-010): the exact content
 * construction (64 spaces + context string + 0x00 + transcript hash), sign/verify round-trips
 * for every scheme of the QUIC profile, and the RFC 8448 §3 CertificateVerify signature
 * verified against the published certificate and transcript.
 */
public class TlsSignaturesTest {

	@Test
	public void certificateVerifyContentConstructionIsExact() {
		byte[] transcriptHash = new byte[32];
		for (int i = 0; i < transcriptHash.length; i++) transcriptHash[i] = (byte) i;

		byte[] content = TlsSignatures.certificateVerifyContent(true, transcriptHash);
		byte[] context = "TLS 1.3, server CertificateVerify".getBytes(StandardCharsets.US_ASCII);

		assertEquals(64 + context.length + 1 + 32, content.length);
		for (int i = 0; i < 64; i++) assertEquals(0x20, content[i]);
		assertArrayEquals(context, Arrays.copyOfRange(content, 64, 64 + context.length));
		assertEquals(0, content[64 + context.length]);
		assertArrayEquals(transcriptHash, Arrays.copyOfRange(content, 65 + context.length, content.length));
	}

	@Test
	public void clientCertificateVerifyContentUsesTheClientContextString() {
		byte[] transcriptHash = new byte[32];
		byte[] content = TlsSignatures.certificateVerifyContent(false, transcriptHash);
		byte[] context = "TLS 1.3, client CertificateVerify".getBytes(StandardCharsets.US_ASCII);
		assertArrayEquals(context, Arrays.copyOfRange(content, 64, 64 + context.length));
	}

	@Test
	public void ecdsaP256SignVerifyRoundTrip() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair keyPair = generator.generateKeyPair();
		assertSignVerifyRoundTrip(SignatureScheme.ECDSA_SECP256R1_SHA256, keyPair);
	}

	@Test
	public void rsaPssSignVerifyRoundTrips() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		assertSignVerifyRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA256, keyPair);
		assertSignVerifyRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA384, keyPair);
		assertSignVerifyRoundTrip(SignatureScheme.RSA_PSS_RSAE_SHA512, keyPair);
	}

	@Test
	public void ed25519SignVerifyRoundTrip() throws Exception {
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		assertSignVerifyRoundTrip(SignatureScheme.ED25519, keyPair);
	}

	@Test
	public void tamperedSignatureDoesNotVerify() throws Exception {
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] content = TlsSignatures.certificateVerifyContent(true, new byte[32]);
		byte[] signature = TlsSignatures.sign(SignatureScheme.ED25519, keyPair.getPrivate(), content);

		signature[0] ^= (byte) 0xFF;
		assertFalse(TlsSignatures.verify(SignatureScheme.ED25519, keyPair.getPublic(), content, signature));
	}

	@Test
	public void signatureOverTamperedContentDoesNotVerify() throws Exception {
		KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] content = TlsSignatures.certificateVerifyContent(true, new byte[32]);
		byte[] signature = TlsSignatures.sign(SignatureScheme.ED25519, keyPair.getPrivate(), content);

		content[content.length - 1] ^= (byte) 0xFF;
		assertFalse(TlsSignatures.verify(SignatureScheme.ED25519, keyPair.getPublic(), content, signature));
	}

	@Test
	public void malformedSignatureBytesReturnFalseRatherThanThrow() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair keyPair = generator.generateKeyPair();
		byte[] content = TlsSignatures.certificateVerifyContent(true, new byte[32]);
		// ECDSA signatures are DER-encoded; garbage bytes must surface as "does not verify",
		// not as a leaked SignatureException.
		assertFalse(TlsSignatures.verify(SignatureScheme.ECDSA_SECP256R1_SHA256, keyPair.getPublic(), content, new byte[16]));
	}

	@Test
	public void keySchemeMismatchReturnsFalseRatherThanThrow() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair rsaKeyPair = generator.generateKeyPair();
		byte[] content = TlsSignatures.certificateVerifyContent(true, new byte[32]);
		// A peer-controlled key/scheme mismatch (an ECDSA scheme against the RSA certificate key)
		// must surface as "does not verify" — the engine maps it to decrypt_error — never as an
		// undeclared IllegalArgumentException escaping the engine's checked-exception contract.
		assertFalse(TlsSignatures.verify(SignatureScheme.ECDSA_SECP256R1_SHA256, rsaKeyPair.getPublic(), content, new byte[64]));
	}

	@Test
	public void rfc8448CertificateVerifyVerifiesAgainstPublishedCertificateAndTranscript() throws Exception {
		// RFC 8448 §3: the server's CertificateVerify is rsa_pss_rsae_sha256 (0x0804) over the
		// content built from the transcript hash covering CH..Certificate.
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(Rfc8448.CLIENT_HELLO);
		digest.update(Rfc8448.SERVER_HELLO);
		digest.update(Rfc8448.ENCRYPTED_EXTENSIONS);
		digest.update(Rfc8448.CERTIFICATE);
		byte[] transcriptHash = digest.digest();

		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		X509Certificate certificate = (X509Certificate) certificateFactory
			.generateCertificate(new ByteArrayInputStream(Rfc8448.SERVER_CERTIFICATE_DER));

		byte[] content = TlsSignatures.certificateVerifyContent(true, transcriptHash);
		assertTrue(TlsSignatures.verify(SignatureScheme.RSA_PSS_RSAE_SHA256, certificate.getPublicKey(),
			content, Rfc8448.CERTIFICATE_VERIFY_SIGNATURE));

		// ...and fails the moment the transcript differs (e.g. one message byte flipped).
		transcriptHash[0] ^= (byte) 0xFF;
		byte[] tamperedContent = TlsSignatures.certificateVerifyContent(true, transcriptHash);
		assertFalse(TlsSignatures.verify(SignatureScheme.RSA_PSS_RSAE_SHA256, certificate.getPublicKey(),
			tamperedContent, Rfc8448.CERTIFICATE_VERIFY_SIGNATURE));
	}

	@Test
	public void pkcs1V15SchemesAreNeverOfferedOrAccepted() {
		// FR-010: PKCS#1 v1.5 RSA signatures MUST NOT be used for CertificateVerify (RFC 8446 §4.2.3).
		assertNull(SignatureScheme.of(0x0401)); // rsa_pkcs1_sha256
		assertNull(SignatureScheme.of(0x0501)); // rsa_pkcs1_sha384
		assertNull(SignatureScheme.of(0x0601)); // rsa_pkcs1_sha512
		for (SignatureScheme scheme : SignatureScheme.values()) {
			// PKCS#1 v1.5 JDK algorithm names have the "<digest>withRSA" shape
			assertFalse(scheme.jdkSignatureAlgorithm().endsWith("withRSA"));
		}
	}

	private static void assertSignVerifyRoundTrip(SignatureScheme scheme, KeyPair keyPair) {
		byte[] content = TlsSignatures.certificateVerifyContent(true, new byte[32]);
		byte[] signature = TlsSignatures.sign(scheme, keyPair.getPrivate(), content);
		assertTrue(scheme + " did not verify",
			TlsSignatures.verify(scheme, keyPair.getPublic(), content, signature));
	}
}
