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

package io.activej.quic.connection;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a received UDP datagram into the individual <b>still-protected</b> packets coalesced into it
 * (RFC 9000 §12.2), reading only fields that header protection does not touch.
 * <p>
 * This exists because {@code QuicPackets.parse}/{@code parseCoalesced} cannot be used on protected
 * packets: header protection masks the low 4 bits of a long header's first byte (5 for a short header),
 * which includes the two reserved bits, so their {@code checkReservedBitsZero} check rejects most real
 * packets. Those entry points are for <i>unprotected</i> packets — Retry, Version Negotiation, and
 * tests. Locating a protected packet's extent is therefore the connection layer's job, and it is safe
 * because every field needed is unprotected:
 * <ul>
 *   <li>the header form and long packet type (bits 7, 6 and 5-4 of the first byte);</li>
 *   <li>version, connection IDs, and the Initial token;</li>
 *   <li>the Length varint, which gives the packet's extent directly.</li>
 * </ul>
 * A short-header packet carries no Length and therefore always runs to the end of the datagram.
 * <p>
 * Reserved bits are validated later, after AEAD authentication succeeds (RFC 9001 §5.4.1) — checking
 * them earlier would turn them into a decryption oracle.
 * <p>
 * <b>Ownership (DI-1)</b>: each returned {@link ProtectedPacket#bytes()} is a <i>retained slice</i> of
 * {@code datagram}, so the caller must recycle every one of them (or hand each to
 * {@code QuicPacketProtection.open}, which recycles it) <i>and</i> recycle {@code datagram} itself.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.2">RFC 9000 §12.2 — Coalescing Packets</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.4">RFC 9001 §5.4 — Header Protection</a>
 */
public final class CoalescedPackets {
	private CoalescedPackets() {}

	/** RFC 9000 §17.2 long packet types. */
	private static final int TYPE_INITIAL = 0x0;
	private static final int TYPE_ZERO_RTT = 0x1;
	private static final int TYPE_HANDSHAKE = 0x2;
	private static final int TYPE_RETRY = 0x3;

	public enum Kind {
		INITIAL, ZERO_RTT, HANDSHAKE, ONE_RTT, RETRY, VERSION_NEGOTIATION
	}

	/**
	 * One packet located within a datagram.
	 *
	 * @param level {@code null} for Retry and Version Negotiation, neither of which is packet-protected
	 *              and neither of which therefore belongs to an encryption level at all
	 * @param bytes a retained slice covering the whole packet, header included; owned by the caller
	 */
	public record ProtectedPacket(
		Kind kind,
		@Nullable EncryptionLevel level,
		long version,
		QuicConnectionId destinationConnectionId,
		@Nullable QuicConnectionId sourceConnectionId,
		ByteBuf bytes
	) {}

	/**
	 * The routing fields of a datagram's first packet, as read by {@link #peek}.
	 *
	 * @param sourceConnectionId {@code null} for a short header, which carries none
	 * @param longPacketType     the two Long Packet Type bits, or {@link #SHORT_HEADER}. Their meaning is
	 *                           defined per version (RFC 8999), so it is only interpretable once the
	 *                           version has been recognized
	 */
	public record Envelope(
		boolean longHeader,
		long version,
		QuicConnectionId destinationConnectionId,
		@Nullable QuicConnectionId sourceConnectionId,
		int longPacketType
	) {
		/** RFC 9000 §17.2.2: the Long Packet Type of a QUIC v1 Initial packet. */
		public static final int TYPE_INITIAL = 0x0;

		/** {@link #longPacketType} for a short header, which has no such field. */
		public static final int SHORT_HEADER = -1;

		/**
		 * Whether this is a QUIC v1 Initial packet — the only packet type that may create a connection.
		 * The caller must have established that {@link #version} is the one it supports.
		 */
		public boolean isInitial() {
			return longHeader && longPacketType == TYPE_INITIAL;
		}
	}

	/**
	 * Reads just enough of a datagram's first packet to route it: header form, version and the
	 * connection IDs. Nothing here is protected or authenticated, which is exactly why this is all an
	 * endpoint may look at before it knows which connection — and therefore which keys — apply.
	 * <p>
	 * The datagram is neither consumed nor modified.
	 *
	 * @param shortHeaderDcidLength the connection ID length <i>we</i> issue; a short header's DCID has
	 *                              no length on the wire
	 * @return {@code null} when the datagram is too short or its envelope is malformed — a dispatcher
	 *         must drop such a datagram, never treat it as a connection error (RFC 9000 §5.2)
	 */
	public static @Nullable Envelope peek(ByteBuf datagram, int shortHeaderDcidLength) {
		byte[] array = datagram.array();
		int cursor = datagram.head();
		int end = datagram.tail();
		if (end - cursor < 1) return null;
		int firstByte = array[cursor] & 0xFF;

		if ((firstByte & 0x80) == 0) {
			// The fixed bit is never masked by header protection, so it is safe to reject on here. Only a
			// short header is checked: a long header may be a Version Negotiation packet, whose low seven
			// bits are arbitrary (RFC 9000 §17.2.1), and the dispatcher's job is to route it.
			if ((firstByte & 0x40) == 0) return null;
			if (shortHeaderDcidLength < 0 || end - cursor < 1 + shortHeaderDcidLength) return null;
			byte[] dcid = new byte[shortHeaderDcidLength];
			System.arraycopy(array, cursor + 1, dcid, 0, shortHeaderDcidLength);
			return new Envelope(false, 0, QuicConnectionId.of(dcid), null, Envelope.SHORT_HEADER);
		}

		cursor++;
		if (end - cursor < 4) return null;
		long version = ((long) (array[cursor] & 0xFF) << 24)
			| ((long) (array[cursor + 1] & 0xFF) << 16)
			| ((long) (array[cursor + 2] & 0xFF) << 8)
			| (array[cursor + 3] & 0xFF);
		cursor += 4;
		int[] pos = {cursor};
		try {
			QuicConnectionId dcid = readConnectionId(array, pos, end);
			QuicConnectionId scid = readConnectionId(array, pos, end);
			// Bits 4-5 of the first byte. Not header-protected, and not interpreted here: what the value
			// means depends on the version, which only the caller has decided whether it recognizes.
			return new Envelope(true, version, dcid, scid, (firstByte >> 4) & 0x03);
		} catch (MalformedDataException e) {
			return null;
		}
	}

	/**
	 * Locates every packet in {@code datagram}, without consuming it.
	 * <p>
	 * An unrecognized version terminates the scan by design: RFC 8999 stabilizes only the header form,
	 * version and connection IDs, so there is no reliable way to find where such a packet ends.
	 *
	 * @param shortHeaderDcidLength the connection ID length <i>we</i> issued — a short header's DCID has
	 *                              no length on the wire, so getting this wrong misparses silently
	 * @throws MalformedDataException when the <i>first</i> packet's envelope is truncated or
	 *                                self-inconsistent — i.e. nothing in the datagram is usable. A
	 *                                failure after at least one packet has been located ends the scan
	 *                                instead (RFC 9000 §12.2: the remainder of the datagram is
	 *                                discarded), which is also how legal trailing PADDING is handled
	 */
	public static List<ProtectedPacket> split(ByteBuf datagram, int shortHeaderDcidLength, long supportedVersion)
		throws MalformedDataException {
		List<ProtectedPacket> packets = new ArrayList<>();
		int offset = datagram.head();
		int end = datagram.tail();
		try {
			while (offset < end) {
				offset = next(datagram, offset, end, shortHeaderDcidLength, supportedVersion, packets);
				Kind kind = packets.get(packets.size() - 1).kind();
				if (kind == Kind.VERSION_NEGOTIATION || kind == Kind.RETRY || kind == Kind.ONE_RTT) {
					// None of these can be followed by anything locatable: the first two are terminal
					// by RFC 8999/§17.2.5, and a short header has no Length so it took the remainder.
					break;
				}
			}
			return packets;
		} catch (MalformedDataException e) {
			if (packets.isEmpty()) {
				throw e;
			}
			// RFC 9000 §12.2: a packet that cannot be processed ends the datagram; what came before it
			// is still valid. This is the path that accepts trailing PADDING — a run of zero bytes past
			// the last packet has the fixed bit clear, so it is exactly such an unprocessable "packet",
			// and RFC 9000 §14.1 padding of a client Initial datagram routinely produces one.
			return packets;
		}
	}

	/** Appends the packet starting at {@code offset} and returns the offset just past it. */
	private static int next(
		ByteBuf datagram, int offset, int end, int shortHeaderDcidLength, long supportedVersion,
		List<ProtectedPacket> out
	) throws MalformedDataException {
		byte[] array = datagram.array();
		int cursor = offset;
		require(end - cursor, 1);
		int firstByte = array[cursor] & 0xFF;
		boolean longHeader = (firstByte & 0x80) != 0;

		if (!longHeader) {
			// The fixed bit is never masked by header protection (RFC 9000 §17.3), so it can and must be
			// validated before any crypto is attempted.
			if ((firstByte & 0x40) == 0) {
				throw new MalformedDataException("Short header fixed bit must be 1");
			}
			require(end - cursor, 1 + shortHeaderDcidLength);
			byte[] dcid = new byte[shortHeaderDcidLength];
			System.arraycopy(array, cursor + 1, dcid, 0, shortHeaderDcidLength);
			out.add(new ProtectedPacket(Kind.ONE_RTT, EncryptionLevel.ONE_RTT, supportedVersion,
				QuicConnectionId.of(dcid), null, datagram.slice(cursor, end - cursor)));
			return end;
		}

		cursor++;
		require(end - cursor, 4);
		long version = ((long) (array[cursor] & 0xFF) << 24)
			| ((long) (array[cursor + 1] & 0xFF) << 16)
			| ((long) (array[cursor + 2] & 0xFF) << 8)
			| (array[cursor + 3] & 0xFF);
		cursor += 4;

		int[] pos = {cursor};
		QuicConnectionId dcid = readConnectionId(array, pos, end);
		QuicConnectionId scid = readConnectionId(array, pos, end);
		cursor = pos[0];

		if (version == 0 || version != supportedVersion) {
			// Version Negotiation, or a version we cannot locate the end of: take the remainder.
			//
			// Note what is *not* checked here: a Version Negotiation packet's low seven bits are set to an
			// arbitrary value by the server (RFC 9000 §17.2.1), so it has no fixed bit to validate. That is
			// why the long-header fixed-bit check below happens after the version is known rather than with
			// the short-header one above — enforcing it earlier rejects every conforming Version
			// Negotiation packet whose random bit happens to be zero, which is half of them.
			out.add(new ProtectedPacket(Kind.VERSION_NEGOTIATION, null, version, dcid, scid,
				datagram.slice(offset, end - offset)));
			return end;
		}

		// Beyond this point the packet claims to be a version-1 long header, which does have a fixed bit,
		// and it is unprotected (RFC 9000 §17.2) so it is safe to reject on.
		if ((firstByte & 0x40) == 0) {
			throw new MalformedDataException("Long header fixed bit must be 1");
		}

		int packetType = (firstByte >> 4) & 0x3;
		if (packetType == TYPE_RETRY) {
			// Retry (RFC 9000 §17.2.5) carries no Length and is not packet-protected.
			out.add(new ProtectedPacket(Kind.RETRY, null, version, dcid, scid,
				datagram.slice(offset, end - offset)));
			return end;
		}

		if (packetType == TYPE_INITIAL) {
			long tokenLength = readVarInt(array, pos, end);
			cursor = pos[0];
			// SI-4: checked against remaining input before it is used to skip.
			if (tokenLength < 0 || tokenLength > end - cursor) {
				throw new MalformedDataException("Initial token length " + tokenLength + " exceeds the datagram");
			}
			cursor += (int) tokenLength;
			pos[0] = cursor;
		}

		long length = readVarInt(array, pos, end);
		cursor = pos[0];
		if (length < 1 || length > end - cursor) {
			throw new MalformedDataException(
				"Packet Length " + length + " exceeds the " + (end - cursor) + " bytes remaining");
		}
		int packetEnd = cursor + (int) length;

		Kind kind = switch (packetType) {
			case TYPE_INITIAL -> Kind.INITIAL;
			case TYPE_ZERO_RTT -> Kind.ZERO_RTT;
			case TYPE_HANDSHAKE -> Kind.HANDSHAKE;
			default -> throw new MalformedDataException("Unreachable long packet type " + packetType);
		};
		EncryptionLevel level = switch (kind) {
			case INITIAL -> EncryptionLevel.INITIAL;
			case ZERO_RTT -> EncryptionLevel.ZERO_RTT;
			case HANDSHAKE -> EncryptionLevel.HANDSHAKE;
			default -> null;
		};
		out.add(new ProtectedPacket(kind, level, version, dcid, scid,
			datagram.slice(offset, packetEnd - offset)));
		return packetEnd;
	}

	private static QuicConnectionId readConnectionId(byte[] array, int[] pos, int end) throws MalformedDataException {
		require(end - pos[0], 1);
		int length = array[pos[0]++] & 0xFF;
		if (length > QuicConnectionId.MAX_LENGTH) {
			throw new MalformedDataException(
				"Connection ID length " + length + " exceeds " + QuicConnectionId.MAX_LENGTH);
		}
		require(end - pos[0], length);
		byte[] bytes = new byte[length];
		System.arraycopy(array, pos[0], bytes, 0, length);
		pos[0] += length;
		return QuicConnectionId.of(bytes);
	}

	/** RFC 9000 §16 varint, read in place so the datagram is never consumed. */
	private static long readVarInt(byte[] array, int[] pos, int end) throws MalformedDataException {
		require(end - pos[0], 1);
		int first = array[pos[0]] & 0xFF;
		int lengthBytes = 1 << (first >> 6);
		require(end - pos[0], lengthBytes);
		long value = first & 0x3F;
		for (int i = 1; i < lengthBytes; i++) {
			value = (value << 8) | (array[pos[0] + i] & 0xFF);
		}
		pos[0] += lengthBytes;
		return value;
	}

	private static void require(int available, int needed) throws TruncatedDataException {
		if (available < needed) {
			throw new TruncatedDataException("Need " + needed + " bytes, only " + available + " remain");
		}
	}
}
