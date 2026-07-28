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

import io.activej.quic.crypto.QuicCipherSuite;
import org.jetbrains.annotations.Nullable;

/**
 * The three TLS 1.3 cipher suites of the QUIC profile (RFC 8446 §B.4, RFC 9001 §8.1), each mapped
 * 1:1 to the feature-01 {@link QuicCipherSuite} that provides its AEAD and header protection.
 * <p>
 * The suite's hash (SHA-256 or SHA-384) drives both the transcript hash (RFC 8446 §4.4.1) and
 * the HKDF used by the key schedule (RFC 8446 §7.1).
 */
public enum TlsCipherSuite {
	TLS_AES_128_GCM_SHA256(0x1301, "SHA-256", QuicCipherSuite.AES_128_GCM),
	TLS_AES_256_GCM_SHA384(0x1302, "SHA-384", QuicCipherSuite.AES_256_GCM),
	TLS_CHACHA20_POLY1305_SHA256(0x1303, "SHA-256", QuicCipherSuite.CHACHA20_POLY1305);

	private final int code;
	private final String hashAlgorithm;
	private final QuicCipherSuite quicCipherSuite;

	TlsCipherSuite(int code, String hashAlgorithm, QuicCipherSuite quicCipherSuite) {
		this.code = code;
		this.hashAlgorithm = hashAlgorithm;
		this.quicCipherSuite = quicCipherSuite;
	}

	/** Resolves a wire code to a suite, or {@code null} for an unknown/GREASE code (tolerated, never selected — RFC 8701). */
	public static @Nullable TlsCipherSuite of(int code) {
		return switch (code) {
			case 0x1301 -> TLS_AES_128_GCM_SHA256;
			case 0x1302 -> TLS_AES_256_GCM_SHA384;
			case 0x1303 -> TLS_CHACHA20_POLY1305_SHA256;
			default -> null;
		};
	}

	/** The 2-byte cipher suite codepoint on the wire (RFC 8446 §B.4). */
	public int code() {
		return code;
	}

	/** JDK {@code MessageDigest} name of this suite's transcript/HKDF hash (RFC 8446 §4.4.1, §7.1). */
	public String hashAlgorithm() {
		return hashAlgorithm;
	}

	/** The feature-01 AEAD/header-protection suite this TLS suite installs keys for. */
	public QuicCipherSuite quicCipherSuite() {
		return quicCipherSuite;
	}
}
