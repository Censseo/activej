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

package io.activej.quic.stream;

import io.activej.quic.connection.QuicConnection.Role;

import static io.activej.common.Checks.checkArgument;

/**
 * QUIC stream identifiers (RFC 9000 §2.1). Pure: no reactor, no state, no buffers.
 * <p>
 * A stream id is a 62-bit variable-length integer whose two least significant bits classify the
 * stream, and whose remaining 60 bits are the per-type ordinal:
 *
 * <pre>
 * bit 0  initiator      0 = client-initiated, 1 = server-initiated
 * bit 1  directionality 0 = bidirectional,    1 = unidirectional
 * bits 2..61            ordinal within that type, {@code streamId >>> 2}
 * </pre>
 * <p>
 * RFC 9000 §2.1 Table 1 therefore reads: {@code 0x00} client-initiated bidirectional,
 * {@code 0x01} server-initiated bidirectional, {@code 0x02} client-initiated unidirectional,
 * {@code 0x03} server-initiated unidirectional. The four types are independent counters — stream
 * {@code 0} (client bidirectional #0) and stream {@code 2} (client unidirectional #0) are unrelated.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types
 * and Identifiers</a>
 */
public final class StreamIds {
	private StreamIds() {}

	/** Bit 0 of a stream id: set when the stream was initiated by the server (RFC 9000 §2.1). */
	private static final long SERVER_INITIATED_BIT = 0x1;

	/** Bit 1 of a stream id: set when the stream is unidirectional (RFC 9000 §2.1). */
	private static final long UNIDIRECTIONAL_BIT = 0x2;

	/**
	 * The largest ordinal a stream id can carry: {@code 2^60 - 1}, because a stream id is a 62-bit
	 * varint value (RFC 9000 §2.1) and the two low bits are the type.
	 */
	public static final long MAX_ORDINAL = (1L << 60) - 1;

	/**
	 * Whether {@code streamId} was initiated by the client — bit 0 clear (RFC 9000 §2.1).
	 */
	public static boolean isClientInitiated(long streamId) {
		return (streamId & SERVER_INITIATED_BIT) == 0;
	}

	/**
	 * Whether {@code streamId} is bidirectional — bit 1 clear (RFC 9000 §2.1). A unidirectional
	 * stream carries data only from its initiator to its peer.
	 */
	public static boolean isBidirectional(long streamId) {
		return (streamId & UNIDIRECTIONAL_BIT) == 0;
	}

	/**
	 * The ordinal of {@code streamId} within its type, {@code streamId >>> 2} (RFC 9000 §2.1). This
	 * is the value counted by {@code MAX_STREAMS} and {@code STREAMS_BLOCKED} (RFC 9000 §4.6), not
	 * the stream id itself.
	 */
	public static long ordinal(long streamId) {
		return streamId >>> 2;
	}

	/**
	 * The stream id of the {@code ordinal}-th stream of the given type (RFC 9000 §2.1).
	 *
	 * @param ordinal 0 … {@link #MAX_ORDINAL}
	 * @throws IllegalArgumentException if {@code ordinal} is negative or above {@link #MAX_ORDINAL},
	 *                                  which cannot be encoded. The check is unconditional rather
	 *                                  than {@code Checks}-gated: exhausting a stream type is a
	 *                                  reachable runtime condition, and silently wrapping would emit
	 *                                  a stream id belonging to a different type
	 */
	public static long of(long ordinal, boolean clientInitiated, boolean bidirectional) {
		checkArgument(ordinal >= 0 && ordinal <= MAX_ORDINAL,
			() -> "Stream ordinal " + ordinal + " is out of range 0.." + MAX_ORDINAL);
		return (ordinal << 2)
			| (clientInitiated ? 0 : SERVER_INITIATED_BIT)
			| (bidirectional ? 0 : UNIDIRECTIONAL_BIT);
	}

	/**
	 * Whether {@code role} owns the send part of {@code streamId} (RFC 9000 §2.1). Both endpoints
	 * send on a bidirectional stream; only the initiator sends on a unidirectional one.
	 */
	public static boolean canSend(long streamId, Role role) {
		return isBidirectional(streamId) || initiatedBy(streamId, role);
	}

	/**
	 * Whether {@code role} owns the receive part of {@code streamId} (RFC 9000 §2.1). Both endpoints
	 * receive on a bidirectional stream; only the peer of the initiator receives on a unidirectional
	 * one.
	 */
	public static boolean canReceive(long streamId, Role role) {
		return isBidirectional(streamId) || !initiatedBy(streamId, role);
	}

	private static boolean initiatedBy(long streamId, Role role) {
		return isClientInitiated(streamId) == (role == Role.CLIENT);
	}
}
