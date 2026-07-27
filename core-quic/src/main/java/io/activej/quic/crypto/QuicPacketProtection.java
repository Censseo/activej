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

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.QuicDecryptionException;
import io.activej.quic.codec.PacketNumbers;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.codec.RetryPacket;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

/**
 * Applies and removes QUIC packet protection (RFC 9001 §5.3 AEAD, §5.4 header protection).
 * <p>
 * This operates directly on raw wire bytes rather than on {@code QuicPacket} objects: header
 * protection masks the very bits that say how many packet-number bytes follow, so — unlike
 * {@code QuicPackets} — the packet-number/payload boundary of a still-protected packet cannot be
 * determined without first removing that protection. {@link #open} therefore re-derives the
 * unprotected header fields (version/connection-IDs/token/length — all sent in the clear) itself.
 * <p>
 * Scope: both operations work on a buffer containing exactly one packet (header through the end
 * of its ciphertext) — splitting a coalesced datagram into per-packet regions using the
 * (unprotected) Length field is a connection-layer concern, out of scope here.
 */
public final class QuicPacketProtection {
	private static final int HEADER_PROTECTION_SAMPLE_LENGTH = 16;
	private static final int MAX_ASSUMED_PACKET_NUMBER_LENGTH = 4;
	private static final int FIXED_BIT = 0x40;
	/** RFC 9001 §5.4.2: {@code pnLength + protected-payload length} must be at least this many bytes. */
	private static final int MIN_PROTECTED_LENGTH = HEADER_PROTECTION_SAMPLE_LENGTH + MAX_ASSUMED_PACKET_NUMBER_LENGTH;

	private QuicPacketProtection() {
	}

	/**
	 * The result of {@link #open}: the reconstructed full packet number and the decrypted,
	 * owned payload.
	 */
	public static final class OpenResult {
		public final long packetNumber;
		public final ByteBuf payload;

		public OpenResult(long packetNumber, ByteBuf payload) {
			this.packetNumber = packetNumber;
			this.payload = payload;
		}
	}

	/**
	 * Removes header protection, reconstructs the packet number and AEAD-opens the payload.
	 * {@code packet} is recycled on every path, including failure; on failure, no plaintext is
	 * emitted. {@code shortHeaderDcidLength} is used only if {@code packet} turns out to be a
	 * short-header packet (its DCID has no self-describing length on the wire).
	 */
	public static OpenResult open(QuicKeys keys, long largestPnInSpace, int shortHeaderDcidLength, ByteBuf packet)
		throws QuicDecryptionException, TruncatedDataException, MalformedDataException {
		try {
			int packetStart = packet.head();
			if (packet.readRemaining() < 1) {
				throw new TruncatedDataException("Empty packet");
			}
			int firstByte = packet.array()[packetStart] & 0xFF;
			boolean isLongHeader = (firstByte & 0x80) != 0;
			// RFC 9000 §17.2/§17.3: the fixed bit is never touched by header protection (only the
			// low 4-5 bits are), so it can and must be checked before any crypto is attempted.
			if ((firstByte & FIXED_BIT) == 0) {
				throw new MalformedDataException((isLongHeader ? "Long" : "Short") + " header fixed bit must be 1");
			}
			if (isLongHeader && ((firstByte >> 4) & 0x3) == RetryPacket.LONG_PACKET_TYPE) {
				throw new IllegalArgumentException(
					"Retry packets are never packet-protected; use RetryIntegrityTag instead of QuicPacketProtection.open");
			}

			int headerLength = isLongHeader
				? parseLongHeaderEnvelope(packet, firstByte)
				: parseShortHeaderEnvelope(packet, shortHeaderDcidLength);

			int sampleOffset = packetStart + headerLength + MAX_ASSUMED_PACKET_NUMBER_LENGTH;
			if (packet.tail() < sampleOffset + HEADER_PROTECTION_SAMPLE_LENGTH) {
				throw new TruncatedDataException("Not enough bytes for a header protection sample");
			}
			byte[] array = packet.array();
			byte[] sample = Arrays.copyOfRange(array, sampleOffset, sampleOffset + HEADER_PROTECTION_SAMPLE_LENGTH);
			byte[] mask = headerProtectionMask(keys, sample);

			array[packetStart] ^= (byte) (mask[0] & (isLongHeader ? 0x0F : 0x1F));
			int unmaskedFirstByte = array[packetStart] & 0xFF;
			int pnLength = (unmaskedFirstByte & 0x3) + 1;
			int pnOffset = packetStart + headerLength;
			for (int i = 0; i < pnLength; i++) {
				array[pnOffset + i] ^= mask[1 + i];
			}
			long truncatedPn = 0;
			for (int i = 0; i < pnLength; i++) {
				truncatedPn = (truncatedPn << 8) | (array[pnOffset + i] & 0xFF);
			}
			long fullPn = PacketNumbers.reconstruct(truncatedPn, pnLength, largestPnInSpace);

			int aadLength = headerLength + pnLength;
			byte[] aad = Arrays.copyOfRange(array, packetStart, packetStart + aadLength);
			int ciphertextOffset = packetStart + aadLength;
			int ciphertextLength = packet.tail() - ciphertextOffset;

			byte[] nonce = nonce(keys.iv(), fullPn);
			Cipher aead = keys.aeadCipher();
			aead.init(Cipher.DECRYPT_MODE, keys.aeadKey(), aeadParams(keys.suite(), nonce));
			aead.updateAAD(aad);
			byte[] plaintext;
			try {
				plaintext = aead.doFinal(array, ciphertextOffset, ciphertextLength);
			} catch (AEADBadTagException e) {
				throw new QuicDecryptionException("AEAD open failed for packet number " + fullPn);
			}

			// RFC 9001 §5.4.1 requires this check only "after removing both packet and header
			// protection" — i.e. after AEAD authentication has already succeeded — so that these
			// unauthenticated-until-now header bits can never be used as a decryption oracle.
			int reservedBits = isLongHeader ? (unmaskedFirstByte >> 2) & 0x3 : (unmaskedFirstByte >> 3) & 0x3;
			if (reservedBits != 0) {
				throw new MalformedDataException("Reserved header bits must be 0 after header protection removal");
			}
			return new OpenResult(fullPn, ByteBuf.wrapForReading(plaintext));
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		} finally {
			packet.recycle();
		}
	}

	/**
	 * Applies header protection and AEAD-seals {@code payload}. {@code header} must already
	 * contain every unprotected envelope field plus the plaintext packet number as its final
	 * 1-4 bytes (its first byte's low 2 bits declare that length, exactly as
	 * {@code QuicPackets.write} + {@code PacketNumbers.write} produce); {@code payload} is not
	 * consumed. Returns a new, owned {@code ByteBuf} containing the complete protected packet.
	 */
	public static ByteBuf protect(QuicKeys keys, long packetNumber, ByteBuf header, ByteBuf payload) {
		byte[] headerBytes = Arrays.copyOfRange(header.array(), header.head(), header.tail());
		int firstByte = headerBytes[0] & 0xFF;
		boolean isLongHeader = (firstByte & 0x80) != 0;
		int pnLength = (firstByte & 0x3) + 1;
		int headerLengthWithoutPn = headerBytes.length - pnLength;
		byte[] payloadBytes = Arrays.copyOfRange(payload.array(), payload.head(), payload.tail());

		byte[] nonce = nonce(keys.iv(), packetNumber);
		try {
			Cipher aead = keys.aeadCipher();
			aead.init(Cipher.ENCRYPT_MODE, keys.aeadKey(), aeadParams(keys.suite(), nonce));
			aead.updateAAD(headerBytes);
			byte[] ciphertext = aead.doFinal(payloadBytes);

			if (pnLength + ciphertext.length < MIN_PROTECTED_LENGTH) {
				// Arrays.copyOfRange below would otherwise silently zero-pad past the packet's
				// actual end instead of failing, producing a packet the peer can never open.
				throw new IllegalArgumentException(
					"packet number (" + pnLength + " bytes) plus ciphertext (" + ciphertext.length +
						" bytes) is only " + (pnLength + ciphertext.length) + " bytes, below RFC 9001 §5.4.2's " +
						MIN_PROTECTED_LENGTH + "-byte minimum for header protection sampling; pad the payload " +
						"(e.g. with a PADDING frame) before calling protect()");
			}

			byte[] full = new byte[headerBytes.length + ciphertext.length];
			System.arraycopy(headerBytes, 0, full, 0, headerBytes.length);
			System.arraycopy(ciphertext, 0, full, headerBytes.length, ciphertext.length);

			int sampleOffset = headerLengthWithoutPn + MAX_ASSUMED_PACKET_NUMBER_LENGTH;
			byte[] sample = Arrays.copyOfRange(full, sampleOffset, sampleOffset + HEADER_PROTECTION_SAMPLE_LENGTH);
			byte[] mask = headerProtectionMask(keys, sample);

			full[0] ^= (byte) (mask[0] & (isLongHeader ? 0x0F : 0x1F));
			for (int i = 0; i < pnLength; i++) {
				full[headerLengthWithoutPn + i] ^= mask[1 + i];
			}
			return ByteBuf.wrapForReading(full);
		} catch (GeneralSecurityException e) {
			// Sealing with a valid, correctly-sized key/nonce cannot fail.
			throw new AssertionError(e);
		}
	}

	private static int parseLongHeaderEnvelope(ByteBuf packet, int firstByte) throws TruncatedDataException, MalformedDataException {
		int start = packet.head();
		packet.moveHead(1);
		requireRemaining(packet, 4);
		packet.moveHead(4); // version
		skipConnectionId(packet);
		skipConnectionId(packet);
		int longPacketType = (firstByte >> 4) & 0x3;
		if (longPacketType == 0) { // Initial: has a Token field
			long tokenLength = QuicVarInts.read(packet);
			if (tokenLength > packet.readRemaining()) {
				throw new MalformedDataException("Token length " + tokenLength + " exceeds " + packet.readRemaining() + " remaining bytes");
			}
			packet.moveHead((int) tokenLength);
		}
		QuicVarInts.read(packet); // Length (value unused: this buffer holds exactly one packet)
		return packet.head() - start;
	}

	private static int parseShortHeaderEnvelope(ByteBuf packet, int dcidLength) throws TruncatedDataException {
		if (dcidLength < 0) {
			throw new IllegalArgumentException("shortHeaderDcidLength must not be negative: " + dcidLength);
		}
		int start = packet.head();
		packet.moveHead(1);
		requireRemaining(packet, dcidLength);
		packet.moveHead(dcidLength);
		return packet.head() - start;
	}

	private static void skipConnectionId(ByteBuf packet) throws TruncatedDataException, MalformedDataException {
		requireRemaining(packet, 1);
		int length = packet.readByte() & 0xFF;
		if (length > QuicConnectionId.MAX_LENGTH) {
			throw new MalformedDataException("Connection ID length exceeds " + QuicConnectionId.MAX_LENGTH + ": " + length);
		}
		requireRemaining(packet, length);
		packet.moveHead(length);
	}

	private static void requireRemaining(ByteBuf buf, int n) throws TruncatedDataException {
		if (buf.readRemaining() < n) {
			throw new TruncatedDataException("Expected " + n + " more byte(s), only " + buf.readRemaining() + " remain");
		}
	}

	/** RFC 9001 §5.3: nonce = IV XOR packet number, left-padded with zeros to the IV's length. */
	private static byte[] nonce(byte[] iv, long packetNumber) {
		byte[] nonce = iv.clone();
		for (int i = 0; i < 8; i++) {
			nonce[nonce.length - 1 - i] ^= (byte) (packetNumber >>> (8 * i));
		}
		return nonce;
	}

	private static AlgorithmParameterSpec aeadParams(QuicCipherSuite suite, byte[] nonce) {
		return suite == QuicCipherSuite.CHACHA20_POLY1305 ? new IvParameterSpec(nonce) : new GCMParameterSpec(128, nonce);
	}

	/**
	 * RFC 9001 §5.4.3/§5.4.4: AES suites mask with the first 5 bytes of AES-ECB-encrypting the
	 * sample; ChaCha20 masks with 5 bytes of ChaCha20 keystream, using the sample's first 4
	 * bytes as a <b>little-endian</b> block counter and its last 12 bytes as the nonce.
	 */
	private static byte[] headerProtectionMask(QuicKeys keys, byte[] sample) throws GeneralSecurityException {
		Cipher cipher = keys.headerProtectionCipher();
		if (keys.suite() == QuicCipherSuite.CHACHA20_POLY1305) {
			int counter = (sample[0] & 0xFF) | ((sample[1] & 0xFF) << 8) | ((sample[2] & 0xFF) << 16) | ((sample[3] & 0xFF) << 24);
			byte[] chachaNonce = Arrays.copyOfRange(sample, 4, 16);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.headerProtectionKey(), "ChaCha20"),
				new ChaCha20ParameterSpec(chachaNonce, counter));
			return cipher.doFinal(new byte[5]);
		}
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.headerProtectionKey(), "AES"));
		byte[] block = cipher.doFinal(sample);
		return Arrays.copyOf(block, 5);
	}
}
