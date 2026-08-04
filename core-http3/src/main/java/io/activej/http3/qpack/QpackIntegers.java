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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.http3.Http3Errors;

import static io.activej.common.Checks.checkArgument;

/**
 * RFC 9204 §4.1.1 (by reference to RFC 7541 §5.1) N-bit-prefix integers.
 * <p>
 * <b>Not</b> {@code io.activej.quic.codec.QuicVarInts} — a genuinely different byte layout that
 * merely shares the word "integer" (see this package's {@code package-info.java}). A prefix of
 * {@code N} bits shares its first octet with representation-identifying flag bits (e.g. {@code T}
 * or {@code N}/{@code H}): if the value fits in {@code N} bits (strictly less than {@code 2^N - 1})
 * it is encoded directly there; otherwise the {@code N}-bit field is set to all ones and the
 * remainder is encoded as a base-128 continuation sequence — 7 value bits plus a continuation bit
 * per byte, least-significant group first.
 * <p>
 * The flag bits are the caller's concern: {@link #readInteger} consumes and decodes the whole first
 * octet, discarding everything outside the low {@code prefixBits} bits, so a caller that needs the
 * flag bits must {@link ByteBuf#peek()} them <b>before</b> calling this method (peeking does not
 * consume). {@link #writeInteger} takes those flag bits pre-shifted into position (their low
 * {@code prefixBits} bits must be zero) and ORs the encoded prefix into them.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.1.1">RFC 9204 §4.1.1 — Prefixed
 * Integers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7541#section-5.1">RFC 7541 §5.1 — Integer
 * Representation</a>
 */
public final class QpackIntegers {
	private static final boolean CHECKS = Checks.isEnabled(QpackIntegers.class);

	/**
	 * The largest value this implementation accepts, chosen to match the platform's other
	 * wire-integer bound ({@code QuicVarInts.MAX_VALUE}) even though the two formats are unrelated:
	 * {@code 2^62 - 1}. Nothing in RFC 9204 needs a value anywhere near this large — static-table
	 * indices, field lengths and the field-section prefix are all tiny — so this is purely a safety
	 * ceiling against a hostile encoding, not a protocol requirement.
	 */
	public static final long MAX_VALUE = (1L << 62) - 1;

	/**
	 * Bounds the number of base-128 continuation bytes a single integer may spend, independent of
	 * the {@link #MAX_VALUE} check below: a hostile encoding can keep the continuation bit set while
	 * contributing zero value bits forever (every continuation byte {@code 0x80}), which never
	 * overflows and would otherwise loop until the buffer is exhausted. This check is unconditional
	 * (never behind {@link Checks#isEnabled}) because it defends against untrusted wire input, not a
	 * programmer error.
	 */
	private static final int MAX_CONTINUATION_BYTES = 10;

	private QpackIntegers() {}

	/**
	 * Reads an N-bit-prefix integer, consuming the first octet (whatever its flag bits) plus any
	 * continuation bytes.
	 *
	 * @param prefixBits the prefix width in bits, {@code [1, 8]}
	 * @throws QpackException {@link Http3Errors#QPACK_DECOMPRESSION_FAILED} if the buffer ends
	 *                        before the integer completes, if the continuation run exceeds
	 *                        {@link #MAX_CONTINUATION_BYTES} bytes without terminating, or if the
	 *                        decoded value would exceed {@link #MAX_VALUE}
	 */
	public static long readInteger(ByteBuf buf, int prefixBits) throws QpackException {
		if (CHECKS) checkArgument(prefixBits >= 1 && prefixBits <= 8, "prefixBits must be in [1, 8]");
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"truncated prefixed-integer prefix octet");
		}
		int prefixMax = (1 << prefixBits) - 1;
		int firstByte = buf.readByte() & 0xFF;
		long value = firstByte & prefixMax;
		if (value < prefixMax) {
			return value;
		}

		int shift = 0;
		int continuationBytes = 0;
		while (true) {
			if (continuationBytes >= MAX_CONTINUATION_BYTES) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"prefixed-integer continuation exceeded " + MAX_CONTINUATION_BYTES + " bytes");
			}
			if (!buf.canRead()) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"truncated prefixed-integer continuation");
			}
			int b = buf.readByte() & 0xFF;
			continuationBytes++;
			long addend = b & 0x7F;
			// A plain "value += addend << shift; if (value > MAX_VALUE) throw" is not safe here:
			// once shift grows large enough, addend << shift can itself sit close to Long.MAX_VALUE,
			// and value + contribution can then wrap around past Long.MAX_VALUE into a negative
			// number before the comparison ever runs, silently defeating the bound. Checking the
			// shift for bit loss and the headroom before adding avoids both failure modes.
			if (shift >= Long.SIZE || (addend << shift) >>> shift != addend) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"prefixed integer exceeded the maximum accepted value");
			}
			long contribution = addend << shift;
			if (contribution > MAX_VALUE - value) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"prefixed integer exceeded the maximum accepted value");
			}
			value += contribution;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
		}
	}

	/**
	 * Writes an N-bit-prefix integer. {@code flagBits} is the representation-identifying byte with
	 * its low {@code prefixBits} bits already zero — e.g. {@code 0xC0} for an Indexed Field Line's
	 * {@code T=1} pattern with a 6-bit prefix; pass {@code 0} where the whole first octet is the
	 * prefix (an 8-bit prefix).
	 *
	 * @param prefixBits the prefix width in bits, {@code [1, 8]}
	 */
	public static void writeInteger(ByteBuf buf, int prefixBits, int flagBits, long value) {
		if (CHECKS) checkArgument(prefixBits >= 1 && prefixBits <= 8, "prefixBits must be in [1, 8]");
		if (CHECKS) checkArgument(value >= 0 && value <= MAX_VALUE, "value out of range [0, " + MAX_VALUE + "]");
		int prefixMax = (1 << prefixBits) - 1;
		if (value < prefixMax) {
			buf.writeByte((byte) (flagBits | value));
			return;
		}
		buf.writeByte((byte) (flagBits | prefixMax));
		long remaining = value - prefixMax;
		while (remaining >= 128) {
			buf.writeByte((byte) ((remaining & 0x7F) | 0x80));
			remaining >>>= 7;
		}
		buf.writeByte((byte) remaining);
	}

	/** The exact number of bytes {@link #writeInteger} emits for {@code value} at this prefix width. */
	public static int encodedLength(int prefixBits, long value) {
		if (CHECKS) checkArgument(prefixBits >= 1 && prefixBits <= 8, "prefixBits must be in [1, 8]");
		if (CHECKS) checkArgument(value >= 0 && value <= MAX_VALUE, "value out of range [0, " + MAX_VALUE + "]");
		int prefixMax = (1 << prefixBits) - 1;
		if (value < prefixMax) {
			return 1;
		}
		long remaining = value - prefixMax;
		int length = 1;
		while (remaining >= 128) {
			length++;
			remaining >>>= 7;
		}
		return length + 1;
	}
}
