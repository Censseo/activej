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

import org.jetbrains.annotations.Nullable;

import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/**
 * The signature schemes offered and accepted for TLS 1.3 CertificateVerify in the QUIC profile
 * (RFC 8446 §4.2.3), with their JDK {@code java.security.Signature} algorithm names and, for
 * RSA-PSS, the {@link PSSParameterSpec} the RFC pins (MGF1 with the same hash, salt length equal
 * to the hash length).
 * <p>
 * PKCS#1 v1.5 RSA signatures are deliberately absent — they MUST NOT be used for
 * CertificateVerify in TLS 1.3 (RFC 8446 §4.2.3).
 */
public enum SignatureScheme {
	ECDSA_SECP256R1_SHA256(0x0403, "SHA256withECDSA", null),
	RSA_PSS_RSAE_SHA256(0x0804, "RSASSA-PSS",
		new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, PSSParameterSpec.TRAILER_FIELD_BC)),
	RSA_PSS_RSAE_SHA384(0x0805, "RSASSA-PSS",
		new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, PSSParameterSpec.TRAILER_FIELD_BC)),
	RSA_PSS_RSAE_SHA512(0x0806, "RSASSA-PSS",
		new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, PSSParameterSpec.TRAILER_FIELD_BC)),
	ED25519(0x0807, "Ed25519", null);

	private final int code;
	private final String jdkSignatureAlgorithm;
	private final @Nullable PSSParameterSpec pssParameterSpec;

	SignatureScheme(int code, String jdkSignatureAlgorithm, @Nullable PSSParameterSpec pssParameterSpec) {
		this.code = code;
		this.jdkSignatureAlgorithm = jdkSignatureAlgorithm;
		this.pssParameterSpec = pssParameterSpec;
	}

	/** Resolves a wire code to a scheme, or {@code null} for an unknown/GREASE code (tolerated, never selected — RFC 8701). */
	public static @Nullable SignatureScheme of(int code) {
		return switch (code) {
			case 0x0403 -> ECDSA_SECP256R1_SHA256;
			case 0x0804 -> RSA_PSS_RSAE_SHA256;
			case 0x0805 -> RSA_PSS_RSAE_SHA384;
			case 0x0806 -> RSA_PSS_RSAE_SHA512;
			case 0x0807 -> ED25519;
			default -> null;
		};
	}

	/** The 2-byte signature-scheme codepoint on the wire (RFC 8446 §4.2.3). */
	public int code() {
		return code;
	}

	/** JDK {@code java.security.Signature} algorithm name implementing this scheme. */
	public String jdkSignatureAlgorithm() {
		return jdkSignatureAlgorithm;
	}

	/**
	 * The RFC 8446 §4.2.3-pinned {@link PSSParameterSpec} for RSA-PSS schemes
	 * (hash = MGF1 hash, salt length = hash length), or {@code null} for non-RSA-PSS schemes.
	 */
	public @Nullable PSSParameterSpec pssParameterSpec() {
		return pssParameterSpec;
	}
}
