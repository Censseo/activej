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
 * Variable-length integer encoding, RFC 9000 §16.
 * <p>
 * The two most significant bits of the first byte select the encoded length (1, 2, 4, or 8
 * bytes), and the remaining bits of those bytes hold the value, most significant byte first.
 * Encoders always emit the minimal length for a value; decoders accept any legal (including
 * non-minimal) length for that value, as required by the RFC.
 */
public final class QuicVarInts {

	/** Largest value a QUIC varint can hold: 2^62 - 1. */
	public static final long MAX_VALUE = (1L << 62) - 1;

	private QuicVarInts() {
	}

	/**
	 * Reads a varint, advancing {@code buf}'s head by its encoded length (RFC 9000 §16).
	 */
	public static long read(ByteBuf buf) throws TruncatedDataException {
		if (buf.readRemaining() < 1) {
			throw new TruncatedDataException("Not enough bytes to read a QUIC varint length prefix");
		}
		int length = 1 << ((buf.peek() & 0xFF) >>> 6);
		if (buf.readRemaining() < length) {
			throw new TruncatedDataException("Not enough bytes to read a " + length + "-byte QUIC varint");
		}
		long value = buf.readByte() & 0x3F;
		for (int i = 1; i < length; i++) {
			value = (value << 8) | (buf.readByte() & 0xFF);
		}
		return value;
	}

	/**
	 * Writes {@code value} in its minimal encoded form (RFC 9000 §16).
	 *
	 * @throws IllegalArgumentException if {@code value} is outside {@code [0, MAX_VALUE]}
	 */
	public static void write(ByteBuf buf, long value) {
		int length = encodedLength(value);
		int prefix = Integer.numberOfTrailingZeros(length) << 6;
		for (int i = 0; i < length; i++) {
			int shift = (length - 1 - i) * 8;
			byte b = (byte) (value >>> shift);
			if (i == 0) {
				b = (byte) ((b & 0x3F) | prefix);
			}
			buf.writeByte(b);
		}
	}

	/**
	 * Returns the minimal encoded length of {@code value}: one of 1, 2, 4, or 8.
	 *
	 * @throws IllegalArgumentException if {@code value} is outside {@code [0, MAX_VALUE]}
	 */
	public static int encodedLength(long value) {
		if (value < 0 || value > MAX_VALUE) {
			throw new IllegalArgumentException("QUIC varint value out of range [0, " + MAX_VALUE + "]: " + value);
		}
		if (value <= 0x3FL) return 1;
		if (value <= 0x3FFFL) return 2;
		if (value <= 0x3FFFFFFFL) return 4;
		return 8;
	}
}
