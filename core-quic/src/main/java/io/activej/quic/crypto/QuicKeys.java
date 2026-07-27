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

import io.activej.quic.QuicConnectionId;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * An immutable AEAD + header-protection key set for one encryption level and direction
 * (RFC 9001 §5). {@code Cipher} instances are created lazily and cached — confined to whichever
 * thread first uses them, per this feature's synchronous, non-shared-state model. Never mutated
 * after construction; a key update (RFC 9001 §6, out of scope for this feature) is an atomic
 * reference swap performed by the caller.
 */
public final class QuicKeys {
	private static final byte[] V1_INITIAL_SALT =
		java.util.HexFormat.of().parseHex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a");

	private final QuicCipherSuite suite;
	private final SecretKeySpec aeadKey;
	private final byte[] iv;
	private final byte[] headerProtectionKey;

	private Cipher aeadCipher;
	private Cipher headerProtectionCipher;

	/** Constructs a key set from already-derived material; validates each array's length against {@code suite}. */
	public QuicKeys(QuicCipherSuite suite, byte[] aeadKeyBytes, byte[] iv, byte[] headerProtectionKey) {
		if (aeadKeyBytes.length != suite.keyLength()) {
			throw new IllegalArgumentException("AEAD key must be " + suite.keyLength() + " bytes for " + suite);
		}
		if (iv.length != suite.ivLength()) {
			throw new IllegalArgumentException("IV must be " + suite.ivLength() + " bytes for " + suite);
		}
		if (headerProtectionKey.length != suite.keyLength()) {
			throw new IllegalArgumentException("Header protection key must be " + suite.keyLength() + " bytes for " + suite);
		}
		this.suite = suite;
		this.aeadKey = new SecretKeySpec(aeadKeyBytes.clone(), suite.keyAlgorithm());
		this.iv = iv.clone();
		this.headerProtectionKey = headerProtectionKey.clone();
	}

	/**
	 * Derives the Initial encryption level's client/server key pair (RFC 9001 §5.2, Appendix A.1)
	 * from the client's chosen Destination Connection ID. Initial keys always use AES-128-GCM,
	 * independent of any later-negotiated cipher suite.
	 */
	public static InitialKeys initial(QuicConnectionId dcid) {
		String hash = "HmacSHA256";
		byte[] initialSecret = Hkdf.extract(hash, V1_INITIAL_SALT, dcid.bytes());
		byte[] clientSecret = Hkdf.expandLabel(hash, initialSecret, "client in", new byte[0], 32);
		byte[] serverSecret = Hkdf.expandLabel(hash, initialSecret, "server in", new byte[0], 32);
		return new InitialKeys(
			deriveKeys(QuicCipherSuite.AES_128_GCM, clientSecret),
			deriveKeys(QuicCipherSuite.AES_128_GCM, serverSecret));
	}

	private static QuicKeys deriveKeys(QuicCipherSuite suite, byte[] secret) {
		String hash = suite.hkdfHash();
		byte[] key = Hkdf.expandLabel(hash, secret, "quic key", new byte[0], suite.keyLength());
		byte[] iv = Hkdf.expandLabel(hash, secret, "quic iv", new byte[0], suite.ivLength());
		byte[] hp = Hkdf.expandLabel(hash, secret, "quic hp", new byte[0], suite.keyLength());
		return new QuicKeys(suite, key, iv, hp);
	}

	public QuicCipherSuite suite() {
		return suite;
	}

	public SecretKeySpec aeadKey() {
		return aeadKey;
	}

	/** Defensive copy of the raw AEAD key bytes (e.g. for test assertions). */
	public byte[] aeadKeyBytes() {
		return aeadKey.getEncoded().clone();
	}

	/** RFC 9001 §5.3: the 12-byte AEAD nonce base, XORed with the packet number per-packet. */
	public byte[] iv() {
		return iv.clone();
	}

	/** RFC 9001 §5.4: the key used to compute the header-protection mask. */
	public byte[] headerProtectionKey() {
		return headerProtectionKey.clone();
	}

	/** A {@link Cipher} for {@link QuicCipherSuite#aeadTransform()}, created once and reused. */
	public Cipher aeadCipher() {
		if (aeadCipher == null) {
			aeadCipher = newCipher(suite.aeadTransform());
		}
		return aeadCipher;
	}

	/** A {@link Cipher} for {@link QuicCipherSuite#headerProtectionTransform()}, created once and reused. */
	public Cipher headerProtectionCipher() {
		if (headerProtectionCipher == null) {
			headerProtectionCipher = newCipher(suite.headerProtectionTransform());
		}
		return headerProtectionCipher;
	}

	private static Cipher newCipher(String transform) {
		try {
			return Cipher.getInstance(transform);
		} catch (GeneralSecurityException e) {
			// AES/GCM, AES/ECB, ChaCha20 and ChaCha20-Poly1305 are guaranteed present in the
			// JDK's default provider.
			throw new AssertionError(e);
		}
	}

	@Override
	public String toString() {
		// Never print key/IV/HP material (constitution Security; SI-6).
		return "QuicKeys[" + suite + "]";
	}
}
