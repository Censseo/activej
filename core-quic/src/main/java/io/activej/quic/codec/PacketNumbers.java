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

package io.activej.quic.codec;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.TruncatedDataException;

/**
 * Packet number truncation and reconstruction, RFC 9000 §17.1 and Appendix A.2/A.3.
 * <p>
 * A packet number is transmitted as its low 1-4 bytes; the receiver reconstructs the full
 * value from the largest packet number seen so far in the same number space. Pass {@code -1}
 * as the reference packet number to mean "no packet number has been sent/received yet in this
 * space".
 */
public final class PacketNumbers {

	private PacketNumbers() {
	}

	/**
	 * Selects the number of bytes (1-4) needed to encode {@code fullPn} such that a receiver
	 * whose reference is {@code largestAckedOrSent} can unambiguously reconstruct it
	 * (RFC 9000 Appendix A.2). The result is clamped to 4 bytes — the largest packet number
	 * length the wire format can express — even if the true minimal-bits computation would
	 * exceed it; callers are responsible for keeping the gap within a representable range.
	 */
	public static int encodeLength(long fullPn, long largestAckedOrSent) {
		if (fullPn < 0) {
			throw new IllegalArgumentException("Packet number must be non-negative: " + fullPn);
		}
		long diff = fullPn - largestAckedOrSent;
		if (diff < 0) {
			throw new IllegalArgumentException(
				"largestAckedOrSent must not exceed fullPn: " + largestAckedOrSent + " > " + fullPn);
		}
		long numUnacked = diff + 1;
		int minBits = (64 - Long.numberOfLeadingZeros(numUnacked)) + 1;
		int numBytes = (minBits + 7) / 8;
		return Math.max(1, Math.min(numBytes, 4));
	}

	/**
	 * Writes the low {@code length} bytes of {@code fullPn}, most significant byte first.
	 */
	public static void write(ByteBuf buf, long fullPn, int length) {
		if (length < 1 || length > 4) {
			throw new IllegalArgumentException("Packet number length must be in [1, 4]: " + length);
		}
		for (int i = 0; i < length; i++) {
			int shift = (length - 1 - i) * 8;
			buf.writeByte((byte) (fullPn >>> shift));
		}
	}

	/**
	 * Reads {@code length} raw bytes as an unsigned big-endian integer — the truncated,
	 * not-yet-reconstructed packet number as it appears on the wire.
	 */
	public static long read(ByteBuf buf, int length) throws TruncatedDataException {
		if (length < 1 || length > 4) {
			throw new IllegalArgumentException("Packet number length must be in [1, 4]: " + length);
		}
		if (buf.readRemaining() < length) {
			throw new TruncatedDataException("Expected " + length + " packet number byte(s), only " + buf.readRemaining() + " remain");
		}
		long value = 0;
		for (int i = 0; i < length; i++) {
			value = (value << 8) | (buf.readByte() & 0xFF);
		}
		return value;
	}

	/**
	 * Reconstructs the full packet number from its truncated {@code numBytes}-byte form, given
	 * the largest packet number seen so far in the same number space (RFC 9000 Appendix A.3).
	 */
	public static long reconstruct(long truncated, int numBytes, long largestPn) {
		if (numBytes < 1 || numBytes > 4) {
			throw new IllegalArgumentException("Packet number length must be in [1, 4]: " + numBytes);
		}
		int pnBits = numBytes * 8;
		if (truncated < 0 || (pnBits < 64 && truncated >= (1L << pnBits))) {
			throw new IllegalArgumentException(
				"Truncated packet number " + truncated + " does not fit in " + numBytes + " byte(s)");
		}
		long expectedPn = largestPn + 1;
		long pnWin = 1L << pnBits;
		long pnHwin = pnWin / 2;
		long pnMask = pnWin - 1;
		long candidatePn = (expectedPn & ~pnMask) | truncated;
		if (candidatePn + pnHwin <= expectedPn && candidatePn < (1L << 62) - pnWin) {
			return candidatePn + pnWin;
		}
		if (candidatePn > expectedPn + pnHwin && candidatePn >= pnWin) {
			return candidatePn - pnWin;
		}
		return candidatePn;
	}
}
