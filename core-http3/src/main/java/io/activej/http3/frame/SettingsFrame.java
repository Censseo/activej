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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.TruncatedDataException;
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * SETTINGS frame (RFC 9114 §7.2.4): a sequence of identifier/value varint pairs, at most one per
 * identifier.
 * <p>
 * The reserved HTTP/2 SETTINGS identifiers ({@code 0x02}-{@code 0x05}, RFC 9114 §7.2.4.1) and a
 * duplicated identifier are rejected here, at decode time — RFC 9114 requires it, and it is
 * structural rather than a stream-sequence concern (research Decision 13). Unknown, non-reserved
 * identifiers — including GREASE values (RFC 9114 §9) — are decoded and carried without
 * complaint; whether to act on them is a connection-layer decision.
 */
public final class SettingsFrame extends Http3Frame {
	public static final long TYPE = 0x04;

	/** {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} (RFC 9204 §5). */
	public static final long QPACK_MAX_TABLE_CAPACITY = 0x01;

	/** {@code SETTINGS_MAX_FIELD_SECTION_SIZE} (RFC 9114 §7.2.4.1). */
	public static final long MAX_FIELD_SECTION_SIZE = 0x06;

	/** {@code SETTINGS_QPACK_BLOCKED_STREAMS} (RFC 9204 §5). */
	public static final long QPACK_BLOCKED_STREAMS = 0x07;

	/**
	 * {@code SETTINGS_H3_DATAGRAM} (RFC 9297 §2.1.1): 1 offers HTTP/3 datagrams, 0 (and absence)
	 * declines them. Any other value is malformed — {@link #isValidH3DatagramValue}.
	 * <p>
	 * This is <b>setting identifier</b> 0x33, which is not
	 * {@link io.activej.http3.Http3Errors#H3_DATAGRAM_ERROR} — <b>error code</b> 0x33. RFC 9297
	 * assigns the two in independent registries and the numeric coincidence means nothing.
	 */
	public static final long H3_DATAGRAM = 0x33;

	/** The RFC 9114 §7.2.4.1 HTTP/2 SETTINGS identifiers, reserved and rejected in HTTP/3. */
	public static final Set<Long> RESERVED_IDENTIFIERS = Set.of(0x02L, 0x03L, 0x04L, 0x05L);

	public final long[] identifiers;
	public final long[] values;

	public SettingsFrame(long[] identifiers, long[] values) {
		if (identifiers.length != values.length) {
			throw new IllegalArgumentException("identifiers and values must be the same length");
		}
		this.identifiers = identifiers;
		this.values = values;
	}

	@Override
	public long type() {
		return TYPE;
	}

	private int payloadLength() {
		int length = 0;
		for (int i = 0; i < identifiers.length; i++) {
			length += QuicVarInts.encodedLength(identifiers[i]) + QuicVarInts.encodedLength(values[i]);
		}
		return length;
	}

	@Override
	public int encodedLength() {
		int payloadLength = payloadLength();
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(payloadLength) + payloadLength;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, payloadLength());
		for (int i = 0; i < identifiers.length; i++) {
			QuicVarInts.write(buf, identifiers[i]);
			QuicVarInts.write(buf, values[i]);
		}
	}

	/**
	 * Parses a SETTINGS payload already fully buffered to its declared length (RFC 9114 §7.2.4).
	 *
	 * @throws Http3Exception {@code H3_FRAME_ERROR} if the payload does not decode to a whole
	 *                        number of identifier/value pairs; {@code H3_SETTINGS_ERROR} for a
	 *                        reserved or duplicated identifier
	 */
	public static SettingsFrame read(ByteBuf payload) throws Http3Exception {
		int pairCount = 0;
		long[] ids = new long[4];
		long[] vals = new long[4];
		Set<Long> seen = new HashSet<>();
		while (payload.canRead()) {
			long id;
			long value;
			try {
				id = QuicVarInts.read(payload);
				value = QuicVarInts.read(payload);
			} catch (TruncatedDataException e) {
				throw new Http3Exception(Http3Errors.H3_FRAME_ERROR,
					"SETTINGS payload is not a whole number of identifier/value pairs");
			}
			if (RESERVED_IDENTIFIERS.contains(id)) {
				throw new Http3Exception(Http3Errors.H3_SETTINGS_ERROR,
					"Reserved SETTINGS identifier: 0x" + Long.toHexString(id));
			}
			if (!seen.add(id)) {
				throw new Http3Exception(Http3Errors.H3_SETTINGS_ERROR,
					"Duplicated SETTINGS identifier: 0x" + Long.toHexString(id));
			}
			if (pairCount == ids.length) {
				ids = Arrays.copyOf(ids, ids.length * 2);
				vals = Arrays.copyOf(vals, vals.length * 2);
			}
			ids[pairCount] = id;
			vals[pairCount] = value;
			pairCount++;
		}
		return new SettingsFrame(Arrays.copyOf(ids, pairCount), Arrays.copyOf(vals, pairCount));
	}

	/**
	 * Whether {@code value} is one of the two {@link #H3_DATAGRAM} values RFC 9297 §2.1.1 defines.
	 * <p>
	 * A predicate rather than a throwing check, because {@link #read} deliberately does not act on
	 * identifiers whose meaning is a connection-level concern: an endpoint that never enabled datagrams
	 * treats 0x33 as any other unknown identifier. The connection layer calls this on a received value
	 * and closes with {@code H3_SETTINGS_ERROR} (0x0109) when it is false.
	 */
	public static boolean isValidH3DatagramValue(long value) {
		return value == 0 || value == 1;
	}

	/**
	 * RFC 9297 §2.1.1's cross-check between the two halves of the same offer: a peer that sends
	 * {@link #H3_DATAGRAM} = 1 must also have advertised the RFC 9221 §3
	 * {@code max_datagram_frame_size} transport parameter, since without it there is no frame to carry
	 * an HTTP/3 datagram in. False is {@code H3_SETTINGS_ERROR} (0x0109) at the connection layer.
	 *
	 * @param h3DatagramValue       the peer's {@link #H3_DATAGRAM} value; 0 when it sent none
	 * @param maxDatagramFrameSize  the peer's advertised {@code max_datagram_frame_size}; 0 when absent
	 */
	public static boolean isH3DatagramSettingConsistent(long h3DatagramValue, long maxDatagramFrameSize) {
		return h3DatagramValue != 1 || maxDatagramFrameSize > 0;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof SettingsFrame other)) return false;
		return Arrays.equals(identifiers, other.identifiers) && Arrays.equals(values, other.values);
	}

	@Override
	public int hashCode() {
		return 31 * Arrays.hashCode(identifiers) + Arrays.hashCode(values);
	}

	@Override
	public String toString() {
		return "SettingsFrame{identifiers=" + Arrays.toString(identifiers) + ", values=" + Arrays.toString(values) + '}';
	}
}
