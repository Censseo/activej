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
import io.activej.bytebuf.ByteBufPool;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.PacketNumbers;
import io.activej.quic.codec.PaddingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.QuicFrames;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.crypto.QuicPacketProtection;
import io.activej.quic.tls.EncryptionLevel;

import java.util.List;

/**
 * Builds one UDP datagram: for each encryption level with pending frames, a header, the frames, then
 * {@link QuicPacketProtection#protect}; long-header packets are coalesced ahead of the short-header
 * one, and a datagram containing a client Initial is padded to the RFC 9000 §14.1 minimum.
 * <p>
 * Sizing comes from feature 01's exact {@code encodedLength()}, which is what lets each packet be
 * built with one allocation rather than a grow-and-copy loop.
 * <p>
 * <b>Ownership (DI-1)</b>: the returned datagram is the caller's. Scratch header and payload buffers
 * are recycled here — {@link QuicPacketProtection#protect} consumes neither of its arguments and
 * returns a new buffer, so the scratch buffers must be released explicitly.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.2">RFC 9000 §12.2 — Coalescing Packets</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-14.1">RFC 9000 §14.1 — Initial Datagram Size</a>
 */
public final class PacketAssembler {
	/** The AEAD tag length for every suite RFC 9001 permits (16 bytes). */
	public static final int AEAD_TAG_LENGTH = 16;

	/** RFC 9000 §14.1: a datagram containing a client Initial packet must be at least this large. */
	public static final int MIN_INITIAL_DATAGRAM_SIZE = 1200;

	/**
	 * RFC 9001 §5.4.2: header protection samples 16 bytes starting 4 bytes past the packet number
	 * field, so {@code pnLength + ciphertext} must be at least 20 bytes.
	 */
	public static final int MIN_PROTECTED_LENGTH = 20;

	private PacketAssembler() {}

	/** One packet's worth of input: its level, number, and the frames to carry. */
	public record PacketPlan(
		EncryptionLevel level, long packetNumber, long largestAckedOrSent, List<QuicFrame> frames
	) {}

	/**
	 * Protects a single packet and returns it. The caller owns the result.
	 *
	 * @param minPayloadLength pads the payload with PADDING frames up to this length; used both for
	 *                         the RFC 9001 §5.4.2 sampling minimum and for Initial datagram padding
	 */
	public static ByteBuf assemblePacket(
		PacketPlan plan, QuicKeys keys, QuicConnectionId dcid, QuicConnectionId scid, byte[] token,
		long version, int minPayloadLength
	) {
		int frameBytes = 0;
		for (QuicFrame frame : plan.frames()) {
			frameBytes += frame.encodedLength();
		}
		int payloadLength = Math.max(frameBytes, minPayloadLength);

		ByteBuf payload = ByteBufPool.allocate(payloadLength);
		try {
			for (QuicFrame frame : plan.frames()) {
				QuicFrames.write(payload, frame);
			}
			// PaddingFrame carries a run length, so one frame covers the whole remainder
			// (RFC 9000 §19.1: PADDING is simply a sequence of zero bytes).
			int padding = payloadLength - payload.readRemaining();
			if (padding > 0) {
				QuicFrames.write(payload, new PaddingFrame(padding));
			}

			int pnLength = PacketNumbers.encodeLength(plan.packetNumber(), plan.largestAckedOrSent());
			// The Length field covers the packet number plus the CIPHERTEXT, which is the plaintext
			// plus the AEAD tag — not the plaintext alone.
			int ciphertextLength = payload.readRemaining() + AEAD_TAG_LENGTH;

			ByteBuf header = buildHeader(plan, dcid, scid, token, version, pnLength, ciphertextLength);
			try {
				return QuicPacketProtection.protect(keys, plan.packetNumber(), header, payload);
			} finally {
				header.recycle();
			}
		} finally {
			payload.recycle();
		}
	}

	/**
	 * The unprotected header through the plaintext packet number, exactly as
	 * {@code QuicPackets.write} + {@code PacketNumbers.write} would produce it — which is what
	 * {@link QuicPacketProtection#protect} requires.
	 * <p>
	 * Written here rather than via {@code QuicPackets.write} because that method emits header and
	 * payload together and derives the Length field from the <i>plaintext</i> size, whereas the wire
	 * needs it to cover the ciphertext.
	 */
	private static ByteBuf buildHeader(
		PacketPlan plan, QuicConnectionId dcid, QuicConnectionId scid, byte[] token,
		long version, int pnLength, int ciphertextLength
	) {
		EncryptionLevel level = plan.level();
		int size = switch (level) {
			case INITIAL -> 1 + 4 + 1 + dcid.length() + 1 + scid.length()
				+ QuicVarInts.encodedLength(token.length) + token.length
				+ QuicVarInts.encodedLength(pnLength + ciphertextLength) + pnLength;
			case HANDSHAKE -> 1 + 4 + 1 + dcid.length() + 1 + scid.length()
				+ QuicVarInts.encodedLength(pnLength + ciphertextLength) + pnLength;
			case ONE_RTT -> 1 + dcid.length() + pnLength;
			// RFC 9000 §17.2.3: a 0-RTT packet is a long header of type 0x1, laid out exactly like a
			// Handshake one — version, both connection IDs, Length, packet number — and carrying no
			// token. Only a client ever builds one (spec FR-052).
			case ZERO_RTT -> 1 + 4 + 1 + dcid.length() + 1 + scid.length()
				+ QuicVarInts.encodedLength(pnLength + ciphertextLength) + pnLength;
		};

		ByteBuf header = ByteBufPool.allocate(size);
		switch (level) {
			case INITIAL -> {
				// 0xC0: long header + fixed bit + type 0x0, reserved bits zero.
				header.writeByte((byte) (0xC0 | (pnLength - 1)));
				header.writeInt((int) version);
				header.writeByte((byte) dcid.length());
				header.put(dcid.bytes());
				header.writeByte((byte) scid.length());
				header.put(scid.bytes());
				QuicVarInts.write(header, token.length);
				header.put(token);
				QuicVarInts.write(header, pnLength + ciphertextLength);
			}
			case HANDSHAKE -> {
				// 0xE0: long header + fixed bit + type 0x2.
				header.writeByte((byte) (0xE0 | (pnLength - 1)));
				header.writeInt((int) version);
				header.writeByte((byte) dcid.length());
				header.put(dcid.bytes());
				header.writeByte((byte) scid.length());
				header.put(scid.bytes());
				QuicVarInts.write(header, pnLength + ciphertextLength);
			}
			case ZERO_RTT -> {
				// 0xD0: long header + fixed bit + type 0x1 (RFC 9000 §17.2.3), reserved bits zero.
				header.writeByte((byte) (0xD0 | (pnLength - 1)));
				header.writeInt((int) version);
				header.writeByte((byte) dcid.length());
				header.put(dcid.bytes());
				header.writeByte((byte) scid.length());
				header.put(scid.bytes());
				QuicVarInts.write(header, pnLength + ciphertextLength);
			}
			case ONE_RTT -> {
				// 0x40: short header + fixed bit; spin and key phase both zero (no key update here).
				header.writeByte((byte) (0x40 | (pnLength - 1)));
				header.put(dcid.bytes());
			}
		}
		PacketNumbers.write(header, plan.packetNumber(), pnLength);
		return header;
	}

	/**
	 * The payload padding a packet needs so that {@code pnLength + ciphertext} reaches the RFC 9001
	 * §5.4.2 sampling minimum. Returns 0 when the frames already suffice.
	 */
	public static int minPayloadForSampling(int frameBytes, int pnLength) {
		int required = MIN_PROTECTED_LENGTH - pnLength - AEAD_TAG_LENGTH;
		return Math.max(0, Math.max(frameBytes, required));
	}

	/**
	 * Coalesces already-protected packets into one datagram, padding to {@code padTo} when it is
	 * larger than the total (RFC 9000 §14.1 for client Initials).
	 * <p>
	 * Every input buffer is recycled; the returned datagram is the caller's.
	 */
	public static ByteBuf coalesce(List<ByteBuf> packets, int padTo) {
		int total = 0;
		for (ByteBuf packet : packets) {
			total += packet.readRemaining();
		}
		int size = Math.max(total, padTo);
		ByteBuf datagram = ByteBufPool.allocate(size);
		try {
			for (ByteBuf packet : packets) {
				datagram.put(packet);
			}
			// Trailing zero bytes past the last packet read as PADDING frames of the final packet's
			// number space (RFC 9000 §12.2), which is exactly the intent.
			while (datagram.readRemaining() < padTo) {
				datagram.writeByte((byte) 0);
			}
			return datagram;
		} finally {
			for (ByteBuf packet : packets) {
				packet.recycle();
			}
		}
	}
}
