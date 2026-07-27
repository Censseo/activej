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

/**
 * The three AEAD/header-protection combinations RFC 9001 §5.3/§5.4 defines for QUIC, with their
 * key sizes and the JDK transform names / HKDF hash used to derive them.
 */
public enum QuicCipherSuite {
	AES_128_GCM("AES/GCM/NoPadding", "AES", 16, "AES/ECB/NoPadding", "HmacSHA256"),
	AES_256_GCM("AES/GCM/NoPadding", "AES", 32, "AES/ECB/NoPadding", "HmacSHA384"),
	CHACHA20_POLY1305("ChaCha20-Poly1305", "ChaCha20", 32, "ChaCha20", "HmacSHA256");

	private final String aeadTransform;
	private final String keyAlgorithm;
	private final int keyLength;
	private final String headerProtectionTransform;
	private final String hkdfHash;

	QuicCipherSuite(String aeadTransform, String keyAlgorithm, int keyLength, String headerProtectionTransform, String hkdfHash) {
		this.aeadTransform = aeadTransform;
		this.keyAlgorithm = keyAlgorithm;
		this.keyLength = keyLength;
		this.headerProtectionTransform = headerProtectionTransform;
		this.hkdfHash = hkdfHash;
	}

	/** JDK {@code Cipher} transform name for this suite's AEAD (RFC 9001 §5.3). */
	public String aeadTransform() {
		return aeadTransform;
	}

	/** {@code SecretKeySpec} algorithm name matching {@link #aeadTransform()}. */
	public String keyAlgorithm() {
		return keyAlgorithm;
	}

	/** AEAD key length in bytes; also the header-protection key length (RFC 9001 §5.4). */
	public int keyLength() {
		return keyLength;
	}

	/** RFC 9001 §5.3: the AEAD nonce/IV length is 12 bytes for every suite this codec supports. */
	public int ivLength() {
		return 12;
	}

	/** JDK {@code Cipher} transform name for this suite's header protection (RFC 9001 §5.4). */
	public String headerProtectionTransform() {
		return headerProtectionTransform;
	}

	/** HMAC algorithm HKDF uses to derive this suite's keys (RFC 9001 §5.2, Table 2). */
	public String hkdfHash() {
		return hkdfHash;
	}
}
