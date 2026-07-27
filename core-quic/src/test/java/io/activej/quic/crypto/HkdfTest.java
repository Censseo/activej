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

import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * RFC 5869 Appendix A test vectors (SHA-256) plus a structural check of RFC 9001 §5.1's
 * expand-label construction (which is RFC 8446 §7.1's {@code HkdfLabel}).
 */
public class HkdfTest {

	@Test
	public void extractAndExpandTestCase1Basic() {
		byte[] ikm = HexFormat.of().parseHex("0b".repeat(22));
		byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
		byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");

		byte[] prk = Hkdf.extract("HmacSHA256", salt, ikm);
		assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", HexFormat.of().formatHex(prk));

		byte[] okm = Hkdf.expand("HmacSHA256", prk, info, 42);
		assertEquals(
			"3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
			HexFormat.of().formatHex(okm));
	}

	@Test
	public void extractAndExpandTestCase2LongerInputs() {
		StringBuilder ikmHex = new StringBuilder();
		for (int i = 0; i < 80; i++) ikmHex.append(String.format("%02x", i));
		StringBuilder saltHex = new StringBuilder();
		for (int i = 0x60; i < 0xb0; i++) saltHex.append(String.format("%02x", i));
		StringBuilder infoHex = new StringBuilder();
		for (int i = 0xb0; i < 0x100; i++) infoHex.append(String.format("%02x", i));

		byte[] ikm = HexFormat.of().parseHex(ikmHex.toString());
		byte[] salt = HexFormat.of().parseHex(saltHex.toString());
		byte[] info = HexFormat.of().parseHex(infoHex.toString());

		byte[] prk = Hkdf.extract("HmacSHA256", salt, ikm);
		assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", HexFormat.of().formatHex(prk));

		byte[] okm = Hkdf.expand("HmacSHA256", prk, info, 82);
		assertEquals(
			"b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41" +
				"c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
			HexFormat.of().formatHex(okm));
	}

	@Test
	public void extractAndExpandTestCase3ZeroLengthSaltAndInfo() {
		byte[] ikm = HexFormat.of().parseHex("0b".repeat(22));
		byte[] salt = new byte[0];
		byte[] info = new byte[0];

		byte[] prk = Hkdf.extract("HmacSHA256", salt, ikm);
		assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", HexFormat.of().formatHex(prk));

		byte[] okm = Hkdf.expand("HmacSHA256", prk, info, 42);
		assertEquals(
			"8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
			HexFormat.of().formatHex(okm));
	}

	@Test
	public void expandReturnsExactlyTheRequestedLength() {
		byte[] prk = new byte[32];
		byte[] info = new byte[] {1, 2, 3};
		for (int length : new int[] {1, 16, 32, 33, 100}) {
			assertEquals(length, Hkdf.expand("HmacSHA256", prk, info, length).length);
		}
	}

	@Test
	public void expandRejectsLengthBeyond255TimesHashLen() {
		byte[] prk = new byte[32];
		byte[] info = new byte[0];
		// SHA-256 HashLen is 32, so the RFC 5869 §2.3 bound is 255 * 32 = 8160 bytes.
		assertEquals(8160, Hkdf.expand("HmacSHA256", prk, info, 8160).length);
		try {
			Hkdf.expand("HmacSHA256", prk, info, 8161);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * RFC 9001 §5.1 / RFC 8446 §7.1: {@code HkdfLabel} is
	 * {@code uint16 length; opaque label<7..255> = "tls13 " + Label; opaque context<0..255>;}.
	 * Assert the constructed {@code info} byte layout directly, since a wiring bug here would
	 * otherwise only surface indirectly through Phase 5's key-derivation vectors.
	 */
	@Test
	public void expandLabelBuildsTheHkdfLabelStructure() {
		byte[] secret = new byte[32];
		byte[] observedInfo = capturedInfoFor(secret, "quic key", new byte[0], 16);

		assertEquals(0, observedInfo[0]);
		assertEquals(16, observedInfo[1]); // length = 16, big-endian uint16
		assertEquals(6 + "quic key".length(), observedInfo[2]); // label length prefix
		assertArrayEquals("tls13 quic key".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
			java.util.Arrays.copyOfRange(observedInfo, 3, 3 + "tls13 quic key".length()));
		int contextLengthIndex = 3 + "tls13 quic key".length();
		assertEquals(0, observedInfo[contextLengthIndex]); // empty context length prefix
		assertEquals(contextLengthIndex + 1, observedInfo.length);
	}

	@Test
	public void expandLabelWithNonEmptyLength() {
		byte[] secret = new byte[32];
		byte[] observedInfo = capturedInfoFor(secret, "quic iv", new byte[0], 12);
		assertEquals(0, observedInfo[0]);
		assertEquals(12, observedInfo[1]);
	}

	/**
	 * Recomputes what {@link Hkdf#expandLabel} must have passed to {@link Hkdf#expand} by
	 * comparing against a hand-built reference {@code info}, then returns that reference so
	 * callers can assert its shape. This indirectly proves {@code expandLabel} is consistent
	 * with {@code expand} without needing to intercept the call.
	 */
	private static byte[] capturedInfoFor(byte[] secret, String label, byte[] context, int length) {
		byte[] viaExpandLabel = Hkdf.expandLabel("HmacSHA256", secret, label, context, length);
		byte[] labelBytes = ("tls13 " + label).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] info = new byte[2 + 1 + labelBytes.length + 1 + context.length];
		info[0] = (byte) (length >>> 8);
		info[1] = (byte) length;
		info[2] = (byte) labelBytes.length;
		System.arraycopy(labelBytes, 0, info, 3, labelBytes.length);
		info[3 + labelBytes.length] = (byte) context.length;
		System.arraycopy(context, 0, info, 4 + labelBytes.length, context.length);
		byte[] viaExpand = Hkdf.expand("HmacSHA256", secret, info, length);
		assertArrayEquals("expandLabel must equal expand(secret, HkdfLabel(label, context), length)", viaExpand, viaExpandLabel);
		return info;
	}
}
