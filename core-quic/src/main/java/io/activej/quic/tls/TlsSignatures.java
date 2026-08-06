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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

/**
 * TLS 1.3 CertificateVerify signing and verification (RFC 8446 §4.4.3, FR-010) over
 * {@code java.security.Signature}, per {@link SignatureScheme}.
 * <p>
 * The signed content is <b>not</b> the raw transcript hash but the RFC 8446 §4.4.3
 * construction: 64 bytes of {@code 0x20}, the ASCII context string
 * ({@code "TLS 1.3, server CertificateVerify"} or the client form), a single {@code 0x00}
 * separator, then the transcript hash. This domain separation is what keeps a
 * CertificateVerify signature from being confused with any other TLS or non-TLS signature.
 * <p>
 * PKCS#1 v1.5 RSA signatures are never used — {@link SignatureScheme} does not contain them.
 */
public final class TlsSignatures {
	private static final byte[] SERVER_CONTEXT = "TLS 1.3, server CertificateVerify".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] CLIENT_CONTEXT = "TLS 1.3, client CertificateVerify".getBytes(StandardCharsets.US_ASCII);
	private static final int PADDING_LENGTH = 64;

	private TlsSignatures() {
	}

	/** The RFC 8446 §4.4.3 content that a CertificateVerify signature is computed over. */
	public static byte[] certificateVerifyContent(boolean server, byte[] transcriptHash) {
		byte[] context = server ? SERVER_CONTEXT : CLIENT_CONTEXT;
		byte[] content = new byte[PADDING_LENGTH + context.length + 1 + transcriptHash.length];
		Arrays.fill(content, 0, PADDING_LENGTH, (byte) 0x20);
		System.arraycopy(context, 0, content, PADDING_LENGTH, context.length);
		content[PADDING_LENGTH + context.length] = 0;
		System.arraycopy(transcriptHash, 0, content, PADDING_LENGTH + context.length + 1, transcriptHash.length);
		return content;
	}

	/**
	 * Signs {@code content} (built via {@link #certificateVerifyContent(boolean, byte[])}) with
	 * {@code privateKey} per {@code scheme}.
	 *
	 * @throws IllegalArgumentException if the key is not usable with the scheme (caller bug —
	 *         the engine selects the scheme from the key type)
	 */
	public static byte[] sign(SignatureScheme scheme, PrivateKey privateKey, byte[] content) {
		Signature signature = newSignature(scheme);
		try {
			signature.initSign(privateKey);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Private key " + privateKey.getAlgorithm() + " is not usable with " + scheme, e);
		}
		try {
			signature.update(content);
			return signature.sign();
		} catch (SignatureException e) {
			throw new IllegalStateException("Failed to sign with " + scheme, e);
		}
	}

	/**
	 * Verifies a CertificateVerify signature. Any failure — a key unusable with the scheme
	 * (e.g. an ECDSA scheme against an RSA certificate key), wrong content, malformed
	 * signature bytes — is reported as {@code false}; nothing about the peer's bytes escapes.
	 * The engine maps {@code false} to {@code decrypt_error}.
	 */
	public static boolean verify(SignatureScheme scheme, PublicKey publicKey, byte[] content, byte[] signatureBytes) {
		Signature signature = newSignature(scheme);
		try {
			signature.initVerify(publicKey);
		} catch (InvalidKeyException e) {
			// A peer-controlled key/scheme mismatch (the certificate key does not fit the
			// CertificateVerify scheme) is a verification failure, not an error — it must not
			// escape as an undeclared exception past the engine's checked-exception contract.
			return false;
		}
		try {
			signature.update(content);
			return signature.verify(signatureBytes);
		} catch (SignatureException e) {
			// Malformed signature encodings (e.g. invalid DER for ECDSA) are a verification failure,
			// not an error — the engine maps false to decrypt_error.
			return false;
		}
	}

	private static Signature newSignature(SignatureScheme scheme) {
		try {
			Signature signature = Signature.getInstance(scheme.jdkSignatureAlgorithm());
			if (scheme.pssParameterSpec() != null) {
				signature.setParameter(scheme.pssParameterSpec());
			}
			return signature;
		} catch (GeneralSecurityException e) {
			// SHA256withECDSA, RSASSA-PSS and Ed25519 are guaranteed present on the Java 17 baseline,
			// and the RFC-pinned PSSParameterSpec is valid by construction.
			throw new AssertionError(e);
		}
	}
}
