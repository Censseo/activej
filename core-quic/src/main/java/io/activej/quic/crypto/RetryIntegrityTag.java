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
import io.activej.quic.QuicDecryptionException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The Retry Integrity Tag (RFC 9001 §5.8): a 128-bit AES-128-GCM tag over an empty plaintext,
 * with a fixed key and nonce, authenticating a Retry packet against the connection ID the
 * client originally chose. Not derived via HKDF — the key/nonce are fixed constants in the RFC.
 */
public final class RetryIntegrityTag {
	public static final int TAG_LENGTH = 16;

	private static final byte[] KEY_V1 = HexFormat.of().parseHex("be0c690b9f66575a1d766b54e368c84e");
	private static final byte[] NONCE_V1 = HexFormat.of().parseHex("461599d35d632bf2239825bb");

	private RetryIntegrityTag() {
	}

	/**
	 * Computes the tag over the Retry Pseudo-Packet: a 1-byte length prefix and the original
	 * destination connection ID, followed by the Retry packet's header and token (everything
	 * up to, but excluding, the tag field itself).
	 */
	public static byte[] compute(QuicConnectionId originalDcid, byte[] retryHeaderAndToken) {
		byte[] odcidBytes = originalDcid.bytes();
		byte[] pseudo = new byte[1 + odcidBytes.length + retryHeaderAndToken.length];
		pseudo[0] = (byte) odcidBytes.length;
		System.arraycopy(odcidBytes, 0, pseudo, 1, odcidBytes.length);
		System.arraycopy(retryHeaderAndToken, 0, pseudo, 1 + odcidBytes.length, retryHeaderAndToken.length);

		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_V1, "AES"), new GCMParameterSpec(128, NONCE_V1));
			cipher.updateAAD(pseudo);
			return cipher.doFinal(); // empty plaintext: doFinal() output is exactly the 16-byte tag
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	/** Constant-time comparison against a freshly computed tag. */
	public static boolean verify(QuicConnectionId originalDcid, byte[] retryHeaderAndToken, byte[] tag) {
		return MessageDigest.isEqual(compute(originalDcid, retryHeaderAndToken), tag);
	}

	/**
	 * Convenience wrapper matching this feature's "Invalid Retry integrity tag" error scenario:
	 * throws {@link QuicDecryptionException} (no key material, no plaintext) instead of
	 * returning {@code false}.
	 */
	public static void verifyOrThrow(QuicConnectionId originalDcid, byte[] retryHeaderAndToken, byte[] tag) throws QuicDecryptionException {
		if (!verify(originalDcid, retryHeaderAndToken, tag)) {
			throw new QuicDecryptionException("Invalid Retry integrity tag");
		}
	}
}
