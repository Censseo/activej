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
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.recycle.Recyclable;
import io.activej.quic.QuicConnectionId;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link QuicPacket}s (RFC 9000 §17) in their <b>unprotected</b> form — see
 * {@link QuicPacket} for what that means for the packet number field. Applying/removing AEAD
 * packet protection and header protection (RFC 9001) is a separate step, layered outside this
 * class, that runs before {@link #parse}/{@link #parseCoalesced} on the receive path and after
 * {@link #write} on the send path.
 */
public final class QuicPackets {
	/** The only QUIC version this codec parses beyond classification: v1. */
	public static final long SUPPORTED_VERSION = 0x00000001L;

	private static final int LONG_HEADER_FORM_BIT = 0x80;
	private static final int FIXED_BIT = 0x40;

	private QuicPackets() {
	}

	public static void write(ByteBuf out, QuicPacket packet) {
		packet.writeTo(out);
	}

	public static int encodedLength(QuicPacket packet) {
		return packet.encodedLength();
	}

	/**
	 * Parses exactly one packet from the head of {@code datagram}, consuming it. Short-header
	 * packets have no self-describing connection ID length, so {@code shortHeaderDcidLength}
	 * (the length locally configured for this connection) is required to parse one.
	 */
	public static QuicPacket parse(ByteBuf datagram, int shortHeaderDcidLength) throws TruncatedDataException, MalformedDataException {
		requireRemaining(datagram, 1);
		int firstByte = datagram.readByte() & 0xFF;
		if ((firstByte & LONG_HEADER_FORM_BIT) == 0) {
			return readShortHeader(datagram, firstByte, shortHeaderDcidLength);
		}

		long version = readUnsignedInt32(datagram);
		QuicConnectionId dcid = readConnectionId(datagram);
		QuicConnectionId scid = readConnectionId(datagram);

		if (version == 0) {
			return readVersionNegotiation(datagram, dcid, scid);
		}
		if (version != SUPPORTED_VERSION) {
			// The rest of the packet's structure is defined by a version we don't understand;
			// only the header form/version/DCID/SCID layout is guaranteed stable (RFC 8999), so
			// there is no reliable way to find where this packet ends within the datagram.
			datagram.moveHead(datagram.readRemaining());
			return new VersionNegotiationPacket(version, dcid, scid, new int[0]);
		}
		if ((firstByte & FIXED_BIT) == 0) {
			throw new MalformedDataException("Long header fixed bit must be 1");
		}

		int longPacketType = (firstByte >> 4) & 0x3;
		return switch (longPacketType) {
			case InitialPacket.LONG_PACKET_TYPE -> readInitial(datagram, firstByte, version, dcid, scid);
			case ZeroRttPacket.LONG_PACKET_TYPE -> readZeroRtt(datagram, firstByte, version, dcid, scid);
			case HandshakePacket.LONG_PACKET_TYPE -> readHandshake(datagram, firstByte, version, dcid, scid);
			default -> readRetry(datagram, version, dcid, scid);
		};
	}

	/**
	 * Splits a coalesced datagram (RFC 9000 §12.2) into its constituent packets, consuming
	 * {@code datagram} entirely. Trailing bytes that do not form a complete, valid packet raise
	 * {@link MalformedDataException} (of which {@link TruncatedDataException} is a subtype).
	 */
	public static List<QuicPacket> parseCoalesced(ByteBuf datagram, int shortHeaderDcidLength) throws TruncatedDataException, MalformedDataException {
		List<QuicPacket> packets = new ArrayList<>();
		try {
			while (datagram.canRead()) {
				packets.add(parse(datagram, shortHeaderDcidLength));
			}
			return packets;
		} catch (MalformedDataException e) {
			// A later packet failing must not leak the owned payload slices of packets already
			// parsed successfully earlier in this datagram.
			for (QuicPacket packet : packets) {
				if (packet instanceof Recyclable recyclable) {
					recyclable.recycle();
				}
			}
			throw e;
		}
	}

	private static QuicPacket readShortHeader(ByteBuf datagram, int firstByte, int dcidLength) throws TruncatedDataException, MalformedDataException {
		if ((firstByte & FIXED_BIT) == 0) {
			throw new MalformedDataException("Short header fixed bit must be 1");
		}
		if (((firstByte >> 3) & 0x3) != 0) {
			throw new MalformedDataException("Short header reserved bits must be 0");
		}
		boolean spinBit = (firstByte & 0x20) != 0;
		boolean keyPhase = (firstByte & 0x04) != 0;
		int pnLength = (firstByte & 0x3) + 1;

		requireRemaining(datagram, dcidLength);
		byte[] dcidBytes = new byte[dcidLength];
		datagram.read(dcidBytes);
		QuicConnectionId dcid = QuicConnectionId.of(dcidBytes);

		long packetNumber = PacketNumbers.read(datagram, pnLength);
		ByteBuf payload = readRestOfBuffer(datagram);
		return new ShortHeaderPacket(dcid, spinBit, keyPhase, packetNumber, pnLength, payload);
	}

	private static InitialPacket readInitial(ByteBuf datagram, int firstByte, long version, QuicConnectionId dcid, QuicConnectionId scid)
		throws TruncatedDataException, MalformedDataException {
		checkReservedBitsZero(firstByte);
		int pnLength = (firstByte & 0x3) + 1;
		byte[] token = readVarIntPrefixedBytes(datagram);
		ByteBuf payload = readLengthPrefixedRegion(datagram, pnLength);
		long packetNumber = PacketNumbers.read(payload, pnLength);
		return new InitialPacket(version, dcid, scid, token, packetNumber, pnLength, payload);
	}

	private static ZeroRttPacket readZeroRtt(ByteBuf datagram, int firstByte, long version, QuicConnectionId dcid, QuicConnectionId scid)
		throws TruncatedDataException, MalformedDataException {
		checkReservedBitsZero(firstByte);
		int pnLength = (firstByte & 0x3) + 1;
		ByteBuf payload = readLengthPrefixedRegion(datagram, pnLength);
		long packetNumber = PacketNumbers.read(payload, pnLength);
		return new ZeroRttPacket(version, dcid, scid, packetNumber, pnLength, payload);
	}

	private static HandshakePacket readHandshake(ByteBuf datagram, int firstByte, long version, QuicConnectionId dcid, QuicConnectionId scid)
		throws TruncatedDataException, MalformedDataException {
		checkReservedBitsZero(firstByte);
		int pnLength = (firstByte & 0x3) + 1;
		ByteBuf payload = readLengthPrefixedRegion(datagram, pnLength);
		long packetNumber = PacketNumbers.read(payload, pnLength);
		return new HandshakePacket(version, dcid, scid, packetNumber, pnLength, payload);
	}

	private static RetryPacket readRetry(ByteBuf datagram, long version, QuicConnectionId dcid, QuicConnectionId scid) throws TruncatedDataException {
		int remaining = datagram.readRemaining();
		if (remaining < RetryPacket.INTEGRITY_TAG_LENGTH) {
			throw new TruncatedDataException("Retry packet missing its " + RetryPacket.INTEGRITY_TAG_LENGTH + "-byte integrity tag");
		}
		byte[] token = new byte[remaining - RetryPacket.INTEGRITY_TAG_LENGTH];
		datagram.read(token);
		byte[] tag = new byte[RetryPacket.INTEGRITY_TAG_LENGTH];
		datagram.read(tag);
		return new RetryPacket(version, dcid, scid, token, tag);
	}

	private static VersionNegotiationPacket readVersionNegotiation(ByteBuf datagram, QuicConnectionId dcid, QuicConnectionId scid) throws MalformedDataException, TruncatedDataException {
		int remaining = datagram.readRemaining();
		if (remaining % 4 != 0) {
			throw new MalformedDataException("Version Negotiation supported-versions list must be a multiple of 4 bytes: " + remaining);
		}
		int[] versions = new int[remaining / 4];
		for (int i = 0; i < versions.length; i++) {
			versions[i] = (int) readUnsignedInt32(datagram);
		}
		return new VersionNegotiationPacket(0, dcid, scid, versions);
	}

	private static void checkReservedBitsZero(int firstByte) throws MalformedDataException {
		if (((firstByte >> 2) & 0x3) != 0) {
			throw new MalformedDataException("Long header reserved bits must be 0");
		}
	}

	private static QuicConnectionId readConnectionId(ByteBuf datagram) throws TruncatedDataException, MalformedDataException {
		requireRemaining(datagram, 1);
		int length = datagram.readByte() & 0xFF;
		if (length > QuicConnectionId.MAX_LENGTH) {
			throw new MalformedDataException("Connection ID length exceeds " + QuicConnectionId.MAX_LENGTH + ": " + length);
		}
		requireRemaining(datagram, length);
		byte[] bytes = new byte[length];
		datagram.read(bytes);
		return QuicConnectionId.of(bytes);
	}

	private static long readUnsignedInt32(ByteBuf datagram) throws TruncatedDataException {
		requireRemaining(datagram, 4);
		return datagram.readInt() & 0xFFFFFFFFL;
	}

	private static byte[] readVarIntPrefixedBytes(ByteBuf datagram) throws TruncatedDataException, MalformedDataException {
		long length = QuicVarInts.read(datagram);
		if (length > datagram.readRemaining()) {
			throw new MalformedDataException("Declared length " + length + " exceeds " + datagram.readRemaining() + " remaining bytes");
		}
		byte[] bytes = new byte[(int) length];
		datagram.read(bytes);
		return bytes;
	}

	/**
	 * Reads the "Length" varint (packet number + payload combined, RFC 9000 §17.2.2) and slices
	 * exactly that many bytes out as an owned region — this is what {@code parseCoalesced} uses
	 * to find the next packet.
	 */
	private static ByteBuf readLengthPrefixedRegion(ByteBuf datagram, int pnLength) throws TruncatedDataException, MalformedDataException {
		long declaredLength = QuicVarInts.read(datagram);
		if (declaredLength < pnLength || declaredLength > datagram.readRemaining()) {
			throw new MalformedDataException(
				"Length " + declaredLength + " invalid (packet number is " + pnLength + " byte(s), " + datagram.readRemaining() + " remain)");
		}
		int len = (int) declaredLength;
		ByteBuf region = datagram.slice(len);
		datagram.moveHead(len);
		return region;
	}

	private static ByteBuf readRestOfBuffer(ByteBuf datagram) {
		int len = datagram.readRemaining();
		ByteBuf slice = datagram.slice(len);
		datagram.moveHead(len);
		return slice;
	}

	private static void requireRemaining(ByteBuf buf, int n) throws TruncatedDataException {
		if (buf.readRemaining() < n) {
			throw new TruncatedDataException("Expected " + n + " more byte(s), only " + buf.readRemaining() + " remain");
		}
	}
}
