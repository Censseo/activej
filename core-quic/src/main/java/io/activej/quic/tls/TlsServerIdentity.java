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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The server's immutable TLS identity (FR-009): an X.509 certificate chain plus the private key
 * of its leaf certificate, loaded once at engine construction. The chain populates the
 * Certificate message (RFC 8446 §4.4.2); the key produces the CertificateVerify signature
 * (RFC 8446 §4.4.3) via one of {@link #signatureSchemes()}.
 * <p>
 * Two sources (spec clarification Q2): a PEM X.509 chain via {@code CertificateFactory} plus an
 * <b>unencrypted PKCS#8</b> private key via {@code KeyFactory} (the primary, interop-oriented
 * mkcert/curl dev convention), or a JDK {@link java.security.KeyStore} (JKS/PKCS12). Encrypted
 * PKCS#8 and legacy PKCS#1/SEC1 keys fail at load time with an error naming the file and the
 * unsupported format. The key algorithm is taken from the leaf certificate's public key — the
 * PKCS#8 container is not trusted to declare it.
 * <p>
 * Loading always ends with a sign/verify probe: the private key signs a fixed probe content and
 * the leaf's public key verifies it, so a key that does not match the chain fails fast at
 * configuration time, never mid-handshake.
 * <p>
 * Secrets hygiene (SI-6): {@link #toString()} prints the subject DN and chain length only —
 * never the private key.
 */
public final class TlsServerIdentity {
	private static final byte[] PROBE_CONTENT = "ActiveJ TLS server identity probe".getBytes(StandardCharsets.US_ASCII);

	private static final String PKCS8_PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
	private static final String PKCS8_PEM_FOOTER = "-----END PRIVATE KEY-----";

	private final X509Certificate[] chain;
	private final PrivateKey privateKey;
	private final List<SignatureScheme> signatureSchemes;

	private TlsServerIdentity(X509Certificate[] chain, PrivateKey privateKey) {
		this.chain = chain.clone();
		this.privateKey = privateKey;
		this.signatureSchemes = signatureSchemesFor(chain[0].getPublicKey().getAlgorithm());
		probe();
	}

	/**
	 * Loads an identity from a PEM X.509 certificate chain (leaf first) and an unencrypted
	 * PKCS#8 private key in PEM form (FR-009).
	 *
	 * @throws IllegalArgumentException if the chain cannot be parsed, is empty, or the key file
	 *         is encrypted, in a legacy (PKCS#1/SEC1) format, not valid PKCS#8, or does not
	 *         match the leaf certificate's public key
	 * @throws IOException if either file cannot be read
	 */
	public static TlsServerIdentity fromPem(Path certificateChainPem, Path pkcs8KeyPem) throws IOException {
		List<X509Certificate> chain = readCertificateChain(certificateChainPem);
		PrivateKey privateKey = readPkcs8PrivateKey(pkcs8KeyPem, chain.get(0).getPublicKey().getAlgorithm());
		try {
			return new TlsServerIdentity(chain.toArray(X509Certificate[]::new), privateKey);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(pkcs8KeyPem + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Loads an identity from a JDK {@link java.security.KeyStore} (JKS/PKCS12): the private key
	 * and certificate chain under {@code alias} (FR-009).
	 *
	 * @throws IllegalArgumentException if the alias holds no private key or no X.509 chain, or
	 *         the key does not match the leaf certificate's public key
	 * @throws GeneralSecurityException if the keystore rejects the password or alias lookup
	 */
	public static TlsServerIdentity fromKeyStore(java.security.KeyStore keyStore, String alias, char[] password)
			throws GeneralSecurityException {
		Key key = keyStore.getKey(alias, password);
		if (!(key instanceof PrivateKey privateKey)) {
			throw new IllegalArgumentException("KeyStore holds no private key under alias '" + alias + "'");
		}
		Certificate[] certificateChain = keyStore.getCertificateChain(alias);
		if (certificateChain == null || certificateChain.length == 0) {
			throw new IllegalArgumentException("KeyStore holds no certificate chain under alias '" + alias + "'");
		}
		X509Certificate[] chain = new X509Certificate[certificateChain.length];
		for (int i = 0; i < certificateChain.length; i++) {
			if (!(certificateChain[i] instanceof X509Certificate x509)) {
				throw new IllegalArgumentException(
					"Certificate chain under alias '" + alias + "' is not X.509: " + certificateChain[i].getType());
			}
			chain[i] = x509;
		}
		try {
			return new TlsServerIdentity(chain, privateKey);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("KeyStore alias '" + alias + "': " + e.getMessage(), e);
		}
	}

	/** The certificate chain, leaf first (defensive copy). */
	public X509Certificate[] chain() {
		return chain.clone();
	}

	/** The leaf (end-entity) certificate — the one CertificateVerify proves possession of. */
	public X509Certificate leaf() {
		return chain[0];
	}

	/**
	 * The signature schemes usable with this identity's key, in preference order, for
	 * negotiation against the peer's {@code signature_algorithms} (RFC 8446 §4.2.3, FR-010).
	 */
	public List<SignatureScheme> signatureSchemes() {
		return signatureSchemes;
	}

	/** The leaf's private key — internal to the TLS engine; never logged or printed (SI-6). */
	PrivateKey privateKey() {
		return privateKey;
	}

	@Override
	public String toString() {
		return "TlsServerIdentity[" + chain[0].getSubjectX500Principal().getName() + ", chain of " + chain.length + "]";
	}

	// ---- loading ----

	private static List<X509Certificate> readCertificateChain(Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		List<X509Certificate> chain = new ArrayList<>();
		try {
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			for (Certificate certificate : factory.generateCertificates(new ByteArrayInputStream(bytes))) {
				if (!(certificate instanceof X509Certificate x509)) {
					throw new IllegalArgumentException(path + ": certificate is not X.509: " + certificate.getType());
				}
				chain.add(x509);
			}
		} catch (CertificateException e) {
			throw new IllegalArgumentException(path + ": cannot parse X.509 certificate chain (PEM/DER expected)", e);
		}
		if (chain.isEmpty()) {
			throw new IllegalArgumentException(path + ": no certificates found");
		}
		return chain;
	}

	private static PrivateKey readPkcs8PrivateKey(Path path, String keyAlgorithm) throws IOException {
		String pem = Files.readString(path, StandardCharsets.US_ASCII);
		if (pem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
			throw new IllegalArgumentException(path +
				": encrypted PKCS#8 private keys are not supported (FR-009); " +
				"decrypt it first, e.g. `openssl pkcs8 -in " + path.getFileName() + " -nocrypt`");
		}
		if (pem.contains("-----BEGIN RSA PRIVATE KEY-----") || pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
			throw new IllegalArgumentException(path +
				": legacy PKCS#1/SEC1 private key formats are not supported; " +
				"convert to unencrypted PKCS#8, e.g. `openssl pkcs8 -topk8 -nocrypt -in " + path.getFileName() + "`");
		}
		int begin = pem.indexOf(PKCS8_PEM_HEADER);
		int end = pem.indexOf(PKCS8_PEM_FOOTER);
		if (begin < 0 || end < 0) {
			throw new IllegalArgumentException(path + ": no unencrypted PKCS#8 PEM block (BEGIN PRIVATE KEY) found");
		}
		byte[] der;
		try {
			der = Base64.getMimeDecoder().decode(pem.substring(begin + PKCS8_PEM_HEADER.length(), end));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(path + ": malformed base64 in the PKCS#8 PEM block", e);
		}
		try {
			return KeyFactory.getInstance(keyAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
		} catch (InvalidKeySpecException e) {
			throw new IllegalArgumentException(path + ": not a valid PKCS#8 " + keyAlgorithm + " private key", e);
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException(path + ": JDK does not support the " + keyAlgorithm + " key algorithm", e);
		}
	}

	// ---- scheme selection + probe ----

	private static List<SignatureScheme> signatureSchemesFor(String keyAlgorithm) {
		return switch (keyAlgorithm) {
			case "RSA" -> List.of(
				SignatureScheme.RSA_PSS_RSAE_SHA256,
				SignatureScheme.RSA_PSS_RSAE_SHA384,
				SignatureScheme.RSA_PSS_RSAE_SHA512);
			case "EC" -> List.of(SignatureScheme.ECDSA_SECP256R1_SHA256);
			case "Ed25519", "EdDSA" -> List.of(SignatureScheme.ED25519);
			default -> throw new IllegalArgumentException(
				"Unsupported certificate key algorithm for TLS 1.3 CertificateVerify: " + keyAlgorithm);
		};
	}

	/**
	 * The load-time probe: sign a fixed content with the private key and verify it against the
	 * leaf's public key, so a mismatched key↔cert pair fails at configuration time.
	 */
	private void probe() {
		SignatureScheme scheme = signatureSchemes.get(0);
		byte[] signature = TlsSignatures.sign(scheme, privateKey, PROBE_CONTENT);
		if (!TlsSignatures.verify(scheme, chain[0].getPublicKey(), PROBE_CONTENT, signature)) {
			throw new IllegalArgumentException(
				"the private key does not match the certificate chain's leaf public key (sign/verify probe failed)");
		}
	}
}
