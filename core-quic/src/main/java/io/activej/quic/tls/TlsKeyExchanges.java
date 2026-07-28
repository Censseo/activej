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

import java.math.BigInteger;
import javax.crypto.KeyAgreement;
import javax.security.auth.DestroyFailedException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;

import org.jetbrains.annotations.Nullable;

import static io.activej.quic.tls.TlsAlerts.DECODE_ERROR;
import static io.activej.quic.tls.TlsAlerts.ILLEGAL_PARAMETER;

/**
 * ECDHE key-exchange glue (RFC 8446 §7.4, §4.2.7) over the JDK {@code KeyAgreement}/
 * {@code KeyPairGenerator}/{@code KeyFactory} machinery, for the two named groups of the QUIC
 * profile: {@code x25519} (RFC 7748 — JDK {@code "X25519"}) and {@code secp256r1}
 * (JDK {@code "ECDH"} over {@code secp256r1}).
 * <p>
 * Hygiene on top of the JDK (research Decision 11): the peer's key-share bytes are length- and
 * format-checked <b>before any crypto</b> (x25519 = 32 bytes; P-256 = 65-byte uncompressed
 * {@code 0x04} point — RFC 8446 §4.2.8.2), and an all-zero X25519 shared secret aborts with
 * {@code illegal_parameter} (RFC 8446 §7.4.1 low-order point) whether the provider rejects the
 * point itself or returns the zero secret. Off-curve P-256 points surface as
 * {@code decode_error} wherever the JDK detects them (key import or agreement).
 */
public final class TlsKeyExchanges {
	private static final int X25519_KEY_LENGTH = 32;
	private static final int SECP256R1_KEY_SHARE_LENGTH = 65;
	private static final int SECP256R1_COORDINATE_LENGTH = 32;

	private static final ECParameterSpec SECP256R1_PARAMS = secp256r1Params();

	private TlsKeyExchanges() {
	}

	/** Generates a fresh ephemeral key pair for {@code group} using the JDK default randomness. */
	public static KeyPair generateKeyPair(NamedGroup group) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance(keyAlgorithm(group));
			if (group == NamedGroup.SECP256R1) {
				generator.initialize(new ECGenParameterSpec("secp256r1"));
			}
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			// X25519/EC key generation is guaranteed present on the Java 17 baseline.
			throw new AssertionError(e);
		}
	}

	/**
	 * Reconstructs a full key pair (private key + derived public key) from a raw 32-byte x25519
	 * private scalar — the determinism hook that lets tests replay RFC 8448 §3's ephemeral keys.
	 * The public key is the scalar multiplication with the base point (u = 9), which is exactly
	 * what a {@code KeyAgreement} against the base point computes (RFC 7748 §5).
	 *
	 * @throws UnsupportedOperationException for {@code secp256r1} — the JDK exposes no way to
	 *         derive an EC public key from a raw scalar
	 * @throws IllegalArgumentException if {@code privateKeyBytes} is not exactly 32 bytes
	 */
	public static KeyPair keyPairFromPrivateBytes(NamedGroup group, byte[] privateKeyBytes) {
		if (group != NamedGroup.X25519) {
			throw new UnsupportedOperationException(
				"Raw private-key reconstruction is supported for x25519 only; " +
				"the JDK exposes no way to derive a secp256r1 public key from a raw scalar");
		}
		if (privateKeyBytes.length != X25519_KEY_LENGTH) {
			throw new IllegalArgumentException("x25519 private key must be 32 bytes, got " + privateKeyBytes.length);
		}
		try {
			KeyFactory keyFactory = KeyFactory.getInstance("X25519");
			PrivateKey privateKey = keyFactory.generatePrivate(
				new XECPrivateKeySpec(NamedParameterSpec.X25519, privateKeyBytes.clone()));
			byte[] basePoint = new byte[X25519_KEY_LENGTH];
			basePoint[0] = 9;
			PublicKey basePointKey = keyFactory.generatePublic(
				new XECPublicKeySpec(NamedParameterSpec.X25519, littleEndianToBigInteger(basePoint)));
			KeyAgreement agreement = KeyAgreement.getInstance("X25519");
			agreement.init(privateKey);
			agreement.doPhase(basePointKey, true);
			PublicKey publicKey = keyFactory.generatePublic(
				new XECPublicKeySpec(NamedParameterSpec.X25519, littleEndianToBigInteger(agreement.generateSecret())));
			return new KeyPair(publicKey, privateKey);
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * The wire encoding of a public key for a {@code key_share} entry (RFC 8446 §4.2.8):
	 * 32 little-endian bytes of {@code u} for x25519; the 65-byte uncompressed point for secp256r1.
	 *
	 * @throws IllegalArgumentException if {@code publicKey} does not match {@code group} (caller bug)
	 */
	public static byte[] encodePublicKey(NamedGroup group, PublicKey publicKey) {
		return switch (group) {
			case X25519 -> {
				if (!(publicKey instanceof XECPublicKey xecPublicKey)) {
					throw new IllegalArgumentException("Not an x25519 public key: " + publicKey.getAlgorithm());
				}
				yield bigIntegerToLittleEndian(xecPublicKey.getU(), X25519_KEY_LENGTH);
			}
			case SECP256R1 -> {
				if (!(publicKey instanceof ECPublicKey ecPublicKey)) {
					throw new IllegalArgumentException("Not an EC public key: " + publicKey.getAlgorithm());
				}
				ECPoint point = ecPublicKey.getW();
				byte[] encoded = new byte[SECP256R1_KEY_SHARE_LENGTH];
				encoded[0] = 0x04;
				writePadded(encoded, 1, point.getAffineX());
				writePadded(encoded, 1 + SECP256R1_COORDINATE_LENGTH, point.getAffineY());
				yield encoded;
			}
		};
	}

	/**
	 * Decodes the peer's {@code key_share} bytes into a public key, checking length and format
	 * before any crypto (RFC 8446 §4.2.8).
	 *
	 * @throws TlsAlertException {@code decode_error} on wrong length/format or an invalid
	 *         secp256r1 point; {@code illegal_parameter} if the provider rejects an x25519 key
	 */
	public static PublicKey decodePublicKey(NamedGroup group, byte[] keyExchangeBytes) throws TlsAlertException {
		return switch (group) {
			case X25519 -> {
				if (keyExchangeBytes.length != X25519_KEY_LENGTH) {
					throw new TlsAlertException(DECODE_ERROR,
						"x25519 key share must be 32 bytes, got " + keyExchangeBytes.length);
				}
				try {
					yield KeyFactory.getInstance("X25519")
						.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, littleEndianToBigInteger(keyExchangeBytes)));
				} catch (InvalidKeySpecException e) {
					throw new TlsAlertException(ILLEGAL_PARAMETER, "x25519 public key rejected by the provider");
				} catch (GeneralSecurityException e) {
					throw new AssertionError(e);
				}
			}
			case SECP256R1 -> {
				if (keyExchangeBytes.length != SECP256R1_KEY_SHARE_LENGTH) {
					throw new TlsAlertException(DECODE_ERROR,
						"secp256r1 key share must be a 65-byte uncompressed point, got " + keyExchangeBytes.length + " bytes");
				}
				if (keyExchangeBytes[0] != 0x04) {
					throw new TlsAlertException(DECODE_ERROR, "secp256r1 key share must be an uncompressed point (0x04 prefix)");
				}
				BigInteger x = new BigInteger(1, copyOfRange(keyExchangeBytes, 1, 1 + SECP256R1_COORDINATE_LENGTH));
				BigInteger y = new BigInteger(1, copyOfRange(keyExchangeBytes, 1 + SECP256R1_COORDINATE_LENGTH, SECP256R1_KEY_SHARE_LENGTH));
				try {
					yield KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), SECP256R1_PARAMS));
				} catch (InvalidKeySpecException e) {
					throw new TlsAlertException(DECODE_ERROR, "secp256r1 public key point is not on the curve");
				} catch (GeneralSecurityException e) {
					throw new AssertionError(e);
				}
			}
		};
	}

	/**
	 * Runs the ECDHE agreement against the peer's {@code key_share} bytes (decoded per
	 * {@link #decodePublicKey(NamedGroup, byte[])}) and returns the shared secret.
	 *
	 * @throws TlsAlertException {@code decode_error} on malformed key-share bytes or an invalid
	 *         secp256r1 point; {@code illegal_parameter} on an all-zero x25519 shared secret
	 *         (low-order point, RFC 8446 §7.4.1)
	 */
	public static byte[] agree(NamedGroup group, PrivateKey privateKey, byte[] peerKeyExchangeBytes) throws TlsAlertException {
		PublicKey peerKey = decodePublicKey(group, peerKeyExchangeBytes);
		try {
			KeyAgreement agreement = KeyAgreement.getInstance(agreementAlgorithm(group));
			agreement.init(privateKey);
			agreement.doPhase(peerKey, true);
			byte[] sharedSecret = agreement.generateSecret();
			if (group == NamedGroup.X25519 && isAllZero(sharedSecret)) {
				throw new TlsAlertException(ILLEGAL_PARAMETER,
					"x25519 shared secret is all zero — low-order peer public key (RFC 8446 §7.4.1)");
			}
			return sharedSecret;
		} catch (InvalidKeyException | IllegalStateException e) {
			if (group == NamedGroup.X25519) {
				throw new TlsAlertException(ILLEGAL_PARAMETER,
					"x25519 peer public key rejected — low-order point (RFC 8446 §7.4.1)");
			}
			throw new TlsAlertException(DECODE_ERROR, "secp256r1 public key point is not on the curve");
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	private static String keyAlgorithm(NamedGroup group) {
		return group == NamedGroup.X25519 ? "X25519" : "EC";
	}

	/**
	 * Best-effort destruction of an ephemeral private key once the ECDHE agreement is done with
	 * it (success or failure alike). Keys whose provider cannot destroy them
	 * ({@link DestroyFailedException}) are left to the GC.
	 */
	static void destroyQuietly(@Nullable PrivateKey privateKey) {
		if (privateKey == null) return;
		try {
			privateKey.destroy();
		} catch (DestroyFailedException ignored) {
		}
	}

	private static String agreementAlgorithm(NamedGroup group) {
		return group == NamedGroup.X25519 ? "X25519" : "ECDH";
	}

	private static ECParameterSpec secp256r1Params() {
		try {
			AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			return parameters.getParameterSpec(ECParameterSpec.class);
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	private static BigInteger littleEndianToBigInteger(byte[] littleEndian) {
		byte[] bigEndian = new byte[littleEndian.length];
		for (int i = 0; i < littleEndian.length; i++) {
			bigEndian[i] = littleEndian[littleEndian.length - 1 - i];
		}
		return new BigInteger(1, bigEndian);
	}

	private static byte[] bigIntegerToLittleEndian(BigInteger value, int length) {
		byte[] bigEndian = value.toByteArray();
		byte[] littleEndian = new byte[length];
		for (int i = 0; i < Math.min(bigEndian.length, length); i++) {
			littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
		}
		return littleEndian;
	}

	private static void writePadded(byte[] out, int offset, BigInteger value) {
		byte[] bigEndian = value.toByteArray();
		int start = Math.max(0, bigEndian.length - SECP256R1_COORDINATE_LENGTH);
		int count = Math.min(bigEndian.length, SECP256R1_COORDINATE_LENGTH);
		System.arraycopy(bigEndian, start, out, offset + SECP256R1_COORDINATE_LENGTH - count, count);
	}

	private static byte[] copyOfRange(byte[] bytes, int from, int to) {
		byte[] copy = new byte[to - from];
		System.arraycopy(bytes, from, copy, 0, copy.length);
		return copy;
	}

	private static boolean isAllZero(byte[] bytes) {
		for (byte b : bytes) {
			if (b != 0) return false;
		}
		return true;
	}
}
