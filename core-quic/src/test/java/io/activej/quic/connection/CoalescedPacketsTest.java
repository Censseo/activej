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
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.crypto.QuicCipherSuite;
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
 * The property that matters most here: a datagram of <b>protected</b> packets must split correctly,
 * which {@code QuicPackets.parseCoalesced} cannot do because header protection masks the reserved bits
 * it validates. {@link #protectedPacketsThatParseCoalescedWouldRejectSplitFine()} pins exactly that.
 */
public class CoalescedPacketsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final QuicConnectionId DCID = QuicConnectionId.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
	private static final QuicConnectionId SCID = QuicConnectionId.of(new byte[]{9, 10, 11, 12, 13, 14, 15, 16});
	private static final long VERSION = QuicPackets.SUPPORTED_VERSION;

	private static ByteBuf protectedPacket(EncryptionLevel level, long packetNumber, QuicKeys keys) {
		return PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(level, packetNumber, packetNumber - 1, List.of(PingFrame.INSTANCE)),
			keys, DCID, SCID, new byte[0], VERSION, 64);
	}

	private static QuicKeys keys(int seed) {
		byte[] secret = new byte[32];
		secret[0] = (byte) seed;
		return QuicKeys.fromTrafficSecret(QuicCipherSuite.AES_128_GCM, secret);
	}

	private static void recycleAll(List<CoalescedPackets.ProtectedPacket> packets) {
		for (CoalescedPackets.ProtectedPacket packet : packets) {
			packet.bytes().recycle();
		}
	}

	@Test
	public void singleInitialPacket() throws Exception {
		ByteBuf datagram = protectedPacket(EncryptionLevel.INITIAL, 0, keys(1));
		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);

		assertEquals(1, packets.size());
		CoalescedPackets.ProtectedPacket packet = packets.get(0);
		assertEquals(CoalescedPackets.Kind.INITIAL, packet.kind());
		assertEquals(EncryptionLevel.INITIAL, packet.level());
		assertEquals(VERSION, packet.version());
		assertEquals(DCID, packet.destinationConnectionId());
		assertEquals(SCID, packet.sourceConnectionId());
		assertEquals(datagram.readRemaining(), packet.bytes().readRemaining());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void singleShortHeaderPacketTakesTheRemainder() throws Exception {
		ByteBuf datagram = protectedPacket(EncryptionLevel.ONE_RTT, 5, keys(2));
		int size = datagram.readRemaining();
		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);

		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.ONE_RTT, packets.get(0).kind());
		assertEquals(EncryptionLevel.ONE_RTT, packets.get(0).level());
		assertEquals(DCID, packets.get(0).destinationConnectionId());
		assertNull("a short header carries no SCID", packets.get(0).sourceConnectionId());
		assertEquals(size, packets.get(0).bytes().readRemaining());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void initialThenHandshakeThenShortHeader() throws Exception {
		List<ByteBuf> parts = new ArrayList<>();
		parts.add(protectedPacket(EncryptionLevel.INITIAL, 0, keys(1)));
		parts.add(protectedPacket(EncryptionLevel.HANDSHAKE, 0, keys(2)));
		parts.add(protectedPacket(EncryptionLevel.ONE_RTT, 0, keys(3)));
		ByteBuf datagram = PacketAssembler.coalesce(parts, 0);

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(3, packets.size());
		assertEquals(CoalescedPackets.Kind.INITIAL, packets.get(0).kind());
		assertEquals(CoalescedPackets.Kind.HANDSHAKE, packets.get(1).kind());
		assertEquals(CoalescedPackets.Kind.ONE_RTT, packets.get(2).kind());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void protectedPacketsThatParseCoalesceWouldRejectSplitFine() throws Exception {
		// Sweep packet numbers so that at least some packets get a header-protection mask leaving the
		// reserved bits non-zero. split() must handle every one; parseCoalesced rejects those.
		int rejectedByParse = 0;
		for (int pn = 0; pn < 24; pn++) {
			QuicKeys k = keys(50 + pn);
			ByteBuf datagram = protectedPacket(EncryptionLevel.HANDSHAKE, pn, k);

			List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
			assertEquals("pn=" + pn, 1, packets.size());
			assertEquals("pn=" + pn, CoalescedPackets.Kind.HANDSHAKE, packets.get(0).kind());
			recycleAll(packets);

			ByteBuf copy = ByteBufPool.allocate(datagram.readRemaining());
			copy.put(datagram.array(), datagram.head(), datagram.readRemaining());
			try {
				List<io.activej.quic.codec.QuicPacket> parsed = QuicPackets.parseCoalesced(copy, 8);
				for (io.activej.quic.codec.QuicPacket p : parsed) {
					io.activej.common.recycle.Recyclers.recycle(p);
				}
			} catch (MalformedDataException expected) {
				rejectedByParse++;
			}
			copy.recycle();
			datagram.recycle();
		}
		assertTrue(
			"expected parseCoalesced to reject at least one protected packet, proving it is the wrong " +
			"tool for the receive path; it rejected " + rejectedByParse,
			rejectedByParse > 0);
	}

	@Test
	public void splitPacketOpensWithItsKeys() throws Exception {
		QuicKeys k = keys(7);
		ByteBuf datagram = protectedPacket(EncryptionLevel.ONE_RTT, 3, k);
		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);

		// The slice is exactly what open() expects, and open() recycles it.
		QuicPacketProtection.OpenResult opened =
			QuicPacketProtection.open(k, 2, 8, packets.get(0).bytes());
		assertEquals(3, opened.packetNumber);
		opened.payload.recycle();
		datagram.recycle();
	}

	@Test
	public void unknownVersionTerminatesTheScan() throws Exception {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xC0);          // long header, fixed bit, Initial
		datagram.writeInt(0x0a0a0a0a);            // a reserved/GREASE version
		datagram.writeByte((byte) DCID.length());
		datagram.put(DCID.bytes());
		datagram.writeByte((byte) SCID.length());
		datagram.put(SCID.bytes());
		datagram.put(new byte[16]);               // whatever follows is unlocatable

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.VERSION_NEGOTIATION, packets.get(0).kind());
		assertEquals(0x0a0a0a0aL, packets.get(0).version());
		assertNull(packets.get(0).level());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void versionZeroIsVersionNegotiation() throws Exception {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xC0);
		datagram.writeInt(0);
		datagram.writeByte((byte) DCID.length());
		datagram.put(DCID.bytes());
		datagram.writeByte((byte) SCID.length());
		datagram.put(SCID.bytes());
		datagram.writeInt((int) VERSION);

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.VERSION_NEGOTIATION, packets.get(0).kind());
		assertEquals(0, packets.get(0).version());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void retryIsTerminalAndCarriesNoLevel() throws Exception {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xF0);          // long header, fixed bit, type 0x3 = Retry
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) DCID.length());
		datagram.put(DCID.bytes());
		datagram.writeByte((byte) SCID.length());
		datagram.put(SCID.bytes());
		datagram.put(new byte[20]);               // token + integrity tag

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.RETRY, packets.get(0).kind());
		assertNull(packets.get(0).level());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void clearedFixedBitIsRejected() {
		ByteBuf datagram = ByteBufPool.allocate(32);
		datagram.writeByte((byte) 0x80);          // long header, fixed bit CLEARED
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);
		datagram.writeByte((byte) 0);

		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> CoalescedPackets.split(datagram, 8, VERSION));
		assertTrue(e.getMessage(), e.getMessage().contains("fixed bit"));
		datagram.recycle();
	}

	@Test
	public void overDeclaredLengthIsRejectedBeforeSlicing() {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xE0);          // Handshake
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);             // empty DCID
		datagram.writeByte((byte) 0);             // empty SCID
		QuicVarInts.write(datagram, 9999);        // Length far beyond the datagram (SI-4)
		datagram.put(new byte[8]);

		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> CoalescedPackets.split(datagram, 8, VERSION));
		assertTrue(e.getMessage(), e.getMessage().contains("Length"));
		datagram.recycle();
	}

	@Test
	public void overDeclaredInitialTokenLengthIsRejected() {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xC0);          // Initial
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);
		datagram.writeByte((byte) 0);
		QuicVarInts.write(datagram, 5000);        // token length beyond the datagram
		datagram.put(new byte[8]);

		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> CoalescedPackets.split(datagram, 8, VERSION));
		assertTrue(e.getMessage(), e.getMessage().contains("token length"));
		datagram.recycle();
	}

	@Test
	public void truncatedEnvelopeIsRejected() {
		ByteBuf datagram = ByteBufPool.allocate(8);
		datagram.writeByte((byte) 0xC0);
		datagram.writeByte((byte) 0x00);          // version cut short

		assertThrows(MalformedDataException.class, () -> CoalescedPackets.split(datagram, 8, VERSION));
		datagram.recycle();
	}

	@Test
	public void oversizedConnectionIdIsRejected() {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xC0);
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 21);            // > QuicConnectionId.MAX_LENGTH
		datagram.put(new byte[21]);

		MalformedDataException e = assertThrows(MalformedDataException.class,
			() -> CoalescedPackets.split(datagram, 8, VERSION));
		assertTrue(e.getMessage(), e.getMessage().contains("Connection ID length"));
		datagram.recycle();
	}

	@Test
	public void aLaterPacketFailingEndsTheScanAndKeepsWhatCameBefore() throws Exception {
		// A valid Initial followed by a Handshake with an impossible Length. RFC 9000 §12.2: the
		// unprocessable packet ends the datagram, and everything located before it is still valid — so
		// the Initial survives rather than being thrown away with the rest.
		ByteBuf first = protectedPacket(EncryptionLevel.INITIAL, 0, keys(1));
		ByteBuf datagram = ByteBufPool.allocate(first.readRemaining() + 32);
		datagram.put(first.array(), first.head(), first.readRemaining());
		first.recycle();

		datagram.writeByte((byte) 0xE0);
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);
		datagram.writeByte((byte) 0);
		QuicVarInts.write(datagram, 9999);

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.INITIAL, packets.get(0).kind());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void aFirstPacketFailingDiscardsTheWholeDatagram() {
		// Nothing was locatable, so there is nothing to keep and the caller must be told.
		ByteBuf datagram = ByteBufPool.allocate(32);
		datagram.writeByte((byte) 0xE0);
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);
		datagram.writeByte((byte) 0);
		QuicVarInts.write(datagram, 9999);

		assertThrows(MalformedDataException.class, () -> CoalescedPackets.split(datagram, 8, VERSION));
		datagram.recycle();
	}

	@Test
	public void trailingPaddingAfterTheLastPacketIsAccepted() throws Exception {
		// RFC 9000 §14.1 padding of a client Initial datagram leaves a run of zero bytes past the last
		// packet. Their fixed bit is clear, so they are an unprocessable "packet" that must simply end
		// the scan — not invalidate the Initial that precedes them.
		ByteBuf first = protectedPacket(EncryptionLevel.INITIAL, 0, keys(1));
		ByteBuf datagram = ByteBufPool.allocate(first.readRemaining() + 64);
		datagram.put(first.array(), first.head(), first.readRemaining());
		first.recycle();
		datagram.put(new byte[64]);

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 8, VERSION);
		assertEquals(1, packets.size());
		assertEquals(CoalescedPackets.Kind.INITIAL, packets.get(0).kind());

		recycleAll(packets);
		datagram.recycle();
	}

	@Test
	public void zeroLengthConnectionIdsAreAccepted() throws Exception {
		ByteBuf datagram = ByteBufPool.allocate(64);
		datagram.writeByte((byte) 0xE0);
		datagram.writeInt((int) VERSION);
		datagram.writeByte((byte) 0);             // empty DCID — legal (RFC 9000 §5.1)
		datagram.writeByte((byte) 0);             // empty SCID
		QuicVarInts.write(datagram, 20);
		datagram.put(new byte[20]);

		List<CoalescedPackets.ProtectedPacket> packets = CoalescedPackets.split(datagram, 0, VERSION);
		assertEquals(1, packets.size());
		assertEquals(0, packets.get(0).destinationConnectionId().length());
		assertEquals(CoalescedPackets.Kind.HANDSHAKE, packets.get(0).kind());

		recycleAll(packets);
		datagram.recycle();
	}
}
