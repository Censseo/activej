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
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.CryptoFrame;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.QuicFrames;
import io.activej.quic.codec.QuicPacket;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.crypto.InitialKeys;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.crypto.QuicPacketProtection;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The strongest available check on a hand-built header is that feature 01's own {@code open()} can
 * read back what {@code protect()} produced — a byte-level assertion on the header alone would only
 * restate the encoder.
 */
public class PacketAssemblerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final QuicConnectionId DCID = QuicConnectionId.of(
		new byte[]{(byte) 0x83, (byte) 0x94, (byte) 0xc8, (byte) 0xf0, 0x3e, 0x51, 0x57, 0x08});
	private static final QuicConnectionId SCID = QuicConnectionId.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
	private static final long VERSION = QuicPackets.SUPPORTED_VERSION;

	private static QuicKeys clientKeys() {
		return QuicKeys.initial(DCID).client();
	}

	private static QuicKeys serverKeys() {
		return QuicKeys.initial(DCID).server();
	}

	@Test
	public void initialPacketRoundTripsThroughOpen() throws Exception {
		QuicKeys send = clientKeys();
		QuicKeys receive = clientKeys();

		List<QuicFrame> frames = List.of(PingFrame.INSTANCE);
		PacketAssembler.PacketPlan plan =
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, frames);

		ByteBuf packet = PacketAssembler.assemblePacket(
			plan, send, DCID, SCID, new byte[0], VERSION, 64);

		QuicPacketProtection.OpenResult opened =
			QuicPacketProtection.open(receive, -1, DCID.length(), packet);

		assertEquals(0, opened.packetNumber);
		// The first frame back is the PING we put in.
		QuicFrame first = QuicFrames.read(opened.payload);
		assertEquals(PingFrame.INSTANCE, first);
		opened.payload.recycle();
	}

	@Test
	public void handshakePacketRoundTripsThroughOpen() throws Exception {
		QuicKeys keys = serverKeys();
		List<QuicFrame> frames = List.of(PingFrame.INSTANCE);
		PacketAssembler.PacketPlan plan =
			new PacketAssembler.PacketPlan(EncryptionLevel.HANDSHAKE, 7, 6, frames);

		ByteBuf packet = PacketAssembler.assemblePacket(plan, keys, DCID, SCID, new byte[0], VERSION, 64);
		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, 6, DCID.length(), packet);

		assertEquals(7, opened.packetNumber);
		assertEquals(PingFrame.INSTANCE, QuicFrames.read(opened.payload));
		opened.payload.recycle();
	}

	@Test
	public void shortHeaderPacketRoundTripsThroughOpen() throws Exception {
		QuicKeys keys = serverKeys();
		List<QuicFrame> frames = List.of(PingFrame.INSTANCE);
		PacketAssembler.PacketPlan plan =
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 42, 41, frames);

		ByteBuf packet = PacketAssembler.assemblePacket(plan, keys, DCID, SCID, new byte[0], VERSION, 64);
		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, 41, DCID.length(), packet);

		assertEquals(42, opened.packetNumber);
		assertEquals(PingFrame.INSTANCE, QuicFrames.read(opened.payload));
		opened.payload.recycle();
	}

	@Test
	public void unprotectedEnvelopeFieldsSurviveAssembly() {
		QuicKeys keys = clientKeys();
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, 64);

		// NOTE: QuicPackets.parse must NOT be used here. Header protection masks the low 4 bits of the
		// first byte, including the two reserved bits, so parse()'s checkReservedBitsZero rejects most
		// protected packets. Only the genuinely unprotected fields can be read before open().
		byte[] bytes = new byte[packet.readRemaining()];
		System.arraycopy(packet.array(), packet.head(), bytes, 0, bytes.length);

		int firstByte = bytes[0] & 0xFF;
		assertEquals("long header bit", 0x80, firstByte & 0x80);
		assertEquals("fixed bit", 0x40, firstByte & 0x40);
		assertEquals("Initial long packet type", 0x0, (firstByte >> 4) & 0x3);

		// version, dcidLen+dcid, scidLen+scid
		assertEquals(VERSION, ((long) (bytes[1] & 0xFF) << 24) | ((bytes[2] & 0xFF) << 16)
			| ((bytes[3] & 0xFF) << 8) | (bytes[4] & 0xFF));
		assertEquals(DCID.length(), bytes[5] & 0xFF);
		assertEquals(SCID.length(), bytes[5 + 1 + DCID.length()] & 0xFF);
		packet.recycle();
	}

	@Test
	public void payloadIsPaddedToTheRequestedMinimum() throws Exception {
		QuicKeys keys = clientKeys();
		// One PING is 1 byte; ask for 100 bytes of payload.
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, 100);

		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, -1, DCID.length(), packet);
		assertEquals(100, opened.payload.readRemaining());
		opened.payload.recycle();
	}

	@Test
	public void aSingleTinyFrameIsPaddedEnoughToProtect() throws Exception {
		// RFC 9001 §5.4.2 needs pnLength + ciphertext >= 20. A lone 1-byte PING with a 1-byte packet
		// number gives 1 + 1 + 16 = 18, which protect() rejects outright — so the assembler must pad.
		QuicKeys keys = clientKeys();
		int minPayload = PacketAssembler.minPayloadForSampling(1, 1);
		assertTrue("expected padding to be required, got " + minPayload, minPayload >= 3);

		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 0, -1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, minPayload);

		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, -1, DCID.length(), packet);
		assertEquals(0, opened.packetNumber);
		opened.payload.recycle();
	}

	@Test
	public void multipleFramesAllSurvive() throws Exception {
		QuicKeys keys = serverKeys();
		List<QuicFrame> frames = List.of(
			PingFrame.INSTANCE,
			new io.activej.quic.codec.MaxDataFrame(123456),
			PingFrame.INSTANCE);

		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 3, 2, frames),
			keys, DCID, SCID, new byte[0], VERSION, 64);

		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, 2, DCID.length(), packet);
		assertEquals(PingFrame.INSTANCE, QuicFrames.read(opened.payload));
		QuicFrame maxData = QuicFrames.read(opened.payload);
		assertEquals(new io.activej.quic.codec.MaxDataFrame(123456), maxData);
		assertEquals(PingFrame.INSTANCE, QuicFrames.read(opened.payload));
		opened.payload.recycle();
	}

	@Test
	public void cryptoFramePayloadSurvivesAssembly() throws Exception {
		QuicKeys keys = clientKeys();
		byte[] handshakeBytes = new byte[200];
		for (int i = 0; i < handshakeBytes.length; i++) {
			handshakeBytes[i] = (byte) i;
		}
		ByteBuf cryptoPayload = io.activej.bytebuf.ByteBufPool.allocate(handshakeBytes.length);
		cryptoPayload.put(handshakeBytes);
		CryptoFrame crypto = new CryptoFrame(0, cryptoPayload);

		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, List.of(crypto)),
			keys, DCID, SCID, new byte[0], VERSION, 0);
		// The assembler does not consume the frames it was given.
		crypto.recycle();

		QuicPacketProtection.OpenResult opened = QuicPacketProtection.open(keys, -1, DCID.length(), packet);
		CryptoFrame readBack = (CryptoFrame) QuicFrames.read(opened.payload);
		assertEquals(0, readBack.offset);
		byte[] actual = new byte[handshakeBytes.length];
		readBack.payload.array();
		System.arraycopy(readBack.payload.array(), readBack.payload.head(), actual, 0, handshakeBytes.length);
		assertArrayEquals(handshakeBytes, actual);
		readBack.recycle();
		opened.payload.recycle();
	}

	@Test
	public void coalescePadsClientInitialDatagramTo1200() {
		QuicKeys keys = clientKeys();
		ByteBuf initial = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, 64);
		int packetSize = initial.readRemaining();
		assertTrue("a lone Initial is well under 1200", packetSize < PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);

		List<ByteBuf> packets = new ArrayList<>();
		packets.add(initial);
		ByteBuf datagram = PacketAssembler.coalesce(packets, PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);

		assertEquals(PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE, datagram.readRemaining());
		datagram.recycle();
	}

	@Test
	public void coalesceConcatenatesInOrderAndParsesAsCoalesced() throws Exception {
		InitialKeys initialKeys = QuicKeys.initial(DCID);
		// Distinct keys per level, as in production: sharing one key across two packets that both use
		// packet number 0 would repeat an AEAD nonce, and the JDK refuses outright
		// ("Cannot reuse iv for GCM encryption") — a useful independent check on FR-009.
		QuicKeys handshakeKeys = QuicKeys.fromTrafficSecret(
			io.activej.quic.crypto.QuicCipherSuite.AES_128_GCM, new byte[32]);
		ByteBuf initial = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, -1, List.of(PingFrame.INSTANCE)),
			initialKeys.client(), DCID, SCID, new byte[0], VERSION, 64);
		ByteBuf handshake = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.HANDSHAKE, 0, -1, List.of(PingFrame.INSTANCE)),
			handshakeKeys, DCID, SCID, new byte[0], VERSION, 64);

		int expectedTotal = initial.readRemaining() + handshake.readRemaining();
		List<ByteBuf> packets = new ArrayList<>();
		packets.add(initial);
		packets.add(handshake);
		ByteBuf datagram = PacketAssembler.coalesce(packets, 0);
		assertEquals(expectedTotal, datagram.readRemaining());

		// Both packets are locatable in the one datagram: Initial first, then Handshake.
		// CoalescedPackets, not QuicPackets.parseCoalesced — these packets are protected, and
		// parseCoalesced validates reserved bits that header protection has masked.
		List<CoalescedPackets.ProtectedPacket> located =
			CoalescedPackets.split(datagram, DCID.length(), VERSION);
		assertEquals(2, located.size());
		assertEquals(CoalescedPackets.Kind.INITIAL, located.get(0).kind());
		assertEquals(CoalescedPackets.Kind.HANDSHAKE, located.get(1).kind());
		assertEquals(DCID, located.get(0).destinationConnectionId());
		assertEquals(SCID, located.get(0).sourceConnectionId());
		for (CoalescedPackets.ProtectedPacket packet : located) {
			packet.bytes().recycle();
		}
		datagram.recycle();
	}

	@Test
	public void coalesceRecyclesItsInputs() {
		QuicKeys keys = clientKeys();
		List<ByteBuf> packets = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			packets.add(PacketAssembler.assemblePacket(
				new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, i, i - 1, List.of(PingFrame.INSTANCE)),
				keys, DCID, SCID, new byte[0], VERSION, 64));
		}
		ByteBuf datagram = PacketAssembler.coalesce(packets, 0);
		// ByteBufRule fails the class if any of the three inputs leaked.
		datagram.recycle();
	}

	@Test
	public void nothingExceedsTheEffectiveMaxDatagramSize() {
		QuicKeys keys = clientKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		int max = settings.maxDatagramSize();

		// A payload sized to fill the datagram must still fit once header and tag are added.
		int payloadBudget = max - 64 - PacketAssembler.AEAD_TAG_LENGTH;
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 0, -1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, payloadBudget);

		assertTrue("packet of " + packet.readRemaining() + " exceeds " + max, packet.readRemaining() <= max);
		packet.recycle();
	}
}
