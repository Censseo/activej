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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HKDF (RFC 5869) extract/expand, plus the TLS 1.3 expand-label construction
 * (RFC 8446 §7.1, used by RFC 9001 §5.1) QUIC key derivation is built on. {@code hmacAlgorithm}
 * is {@code "HmacSHA256"} or {@code "HmacSHA384"}, selected by {@link QuicCipherSuite}.
 */
public final class Hkdf {

	private Hkdf() {
	}

	/** RFC 5869 §2.2: {@code PRK = HMAC-Hash(salt, IKM)}. */
	public static byte[] extract(String hmacAlgorithm, byte[] salt, byte[] ikm) {
		return hmac(hmacAlgorithm, salt, ikm);
	}

	/**
	 * RFC 5869 §2.3: iteratively derives {@code length} bytes of output keying material.
	 *
	 * @throws IllegalArgumentException if {@code length} exceeds the RFC's {@code 255 * HashLen}
	 * bound — the single-byte counter would otherwise silently wrap and corrupt the output
	 */
	public static byte[] expand(String hmacAlgorithm, byte[] prk, byte[] info, int length) {
		int hashLength = macLength(hmacAlgorithm);
		if (length > 255L * hashLength) {
			throw new IllegalArgumentException(
				"length " + length + " exceeds RFC 5869 §2.3's bound of 255 * HashLen for " + hmacAlgorithm);
		}
		byte[] okm = new byte[length];
		byte[] t = new byte[0];
		int written = 0;
		int counter = 1;
		while (written < length) {
			byte[] input = new byte[t.length + info.length + 1];
			System.arraycopy(t, 0, input, 0, t.length);
			System.arraycopy(info, 0, input, t.length, info.length);
			input[input.length - 1] = (byte) counter;
			t = hmac(hmacAlgorithm, prk, input);
			int chunk = Math.min(t.length, length - written);
			System.arraycopy(t, 0, okm, written, chunk);
			written += chunk;
			counter++;
		}
		return okm;
	}

	/**
	 * RFC 8446 §7.1 {@code HkdfLabel}: {@code uint16 length; opaque label<7..255> = "tls13 " +
	 * label; opaque context<0..255> = context;}, expanded via {@link #expand}.
	 */
	public static byte[] expandLabel(String hmacAlgorithm, byte[] secret, String label, byte[] context, int length) {
		byte[] labelBytes = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
		byte[] info = new byte[2 + 1 + labelBytes.length + 1 + context.length];
		info[0] = (byte) (length >>> 8);
		info[1] = (byte) length;
		info[2] = (byte) labelBytes.length;
		System.arraycopy(labelBytes, 0, info, 3, labelBytes.length);
		int contextLengthIndex = 3 + labelBytes.length;
		info[contextLengthIndex] = (byte) context.length;
		System.arraycopy(context, 0, info, contextLengthIndex + 1, context.length);
		return expand(hmacAlgorithm, secret, info, length);
	}

	private static byte[] hmac(String hmacAlgorithm, byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance(hmacAlgorithm);
			mac.init(new SecretKeySpec(key.length == 0 ? new byte[mac.getMacLength()] : key, hmacAlgorithm));
			return mac.doFinal(data);
		} catch (GeneralSecurityException e) {
			// HmacSHA256/HmacSHA384 are guaranteed present in every JDK provider.
			throw new AssertionError(e);
		}
	}

	private static int macLength(String hmacAlgorithm) {
		try {
			return Mac.getInstance(hmacAlgorithm).getMacLength();
		} catch (GeneralSecurityException e) {
			// HmacSHA256/HmacSHA384 are guaranteed present in every JDK provider.
			throw new AssertionError(e);
		}
	}
}
