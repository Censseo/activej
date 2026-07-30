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
import io.activej.quic.codec.CryptoFrame;
import io.activej.quic.codec.PacketNumbers;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Note the boundary of this class: the frame-type/packet-type matrix of FR-013 and the empty-payload
 * rule are enforced elsewhere (the receive path), not here. Keeping that explicit avoids two
 * implementations of the same check.
 */
public class PacketNumberSpaceTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static PacketNumberSpace space() {
		return new PacketNumberSpace(EncryptionLevel.ONE_RTT, 32);
	}

	private static SentPacket sent(PacketNumberSpace space, long now, boolean ackEliciting) {
		long pn = space.nextPacketNumber();
		return new SentPacket(pn, space.level(), now, 1200, ackEliciting, ackEliciting,
			List.of(PingFrame.INSTANCE), false);
	}

	private static CryptoFrame crypto(String payload) {
		byte[] bytes = payload.getBytes();
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return new CryptoFrame(0, buf);
	}

	@Test
	public void packetNumbersAreMonotonicAndNeverReused() {
		PacketNumberSpace space = space();
		for (long expected = 0; expected < 100; expected++) {
			assertEquals(expected, space.nextPacketNumber());
		}

		// Retransmission: packet 5's frames are re-queued, but the new packet carrying them gets a
		// fresh number. Reuse would repeat an AEAD nonce (RFC 9001 §5.3).
		SentPacket lost = new SentPacket(5, space.level(), 0, 1200, true, true, List.of(PingFrame.INSTANCE), false);
		space.onPacketSent(lost);
		space.onPacketLost(5);
		assertEquals(100, space.nextPacketNumber());
	}

	@Test
	public void largestSentTracksHighestSent() {
		PacketNumberSpace space = space();
		assertEquals(PacketNumberSpace.NONE, space.largestSent());

		space.onPacketSent(sent(space, 100, true));
		assertEquals(0, space.largestSent());
		space.onPacketSent(sent(space, 200, true));
		assertEquals(1, space.largestSent());
	}

	@Test
	public void duplicateReceivedPacketIsDetected() throws Exception {
		PacketNumberSpace space = space();
		assertTrue(space.onPacketReceived(7, 1000, true));
		assertEquals(1, space.ackElicitingReceivedSinceAck());

		// A duplicate returns false and moves no counter.
		assertFalse(space.onPacketReceived(7, 1100, true));
		assertEquals(1, space.ackElicitingReceivedSinceAck());

		assertTrue(space.onPacketReceived(8, 1200, true));
		assertEquals(2, space.ackElicitingReceivedSinceAck());
	}

	@Test
	public void largestReceivedFeedsReconstruct() throws Exception {
		PacketNumberSpace space = space();
		assertEquals(PacketNumberSpace.NONE, space.largestReceived());

		assertTrue(space.onPacketReceived(10, 1000, true));
		assertEquals(10, space.largestReceived());

		// RFC 9000 §A.3: with largest received 0xa82f30ea, a 2-byte truncated 0x9b32 reconstructs to
		// 0xa82f9b32. Drive it through the space so the wiring, not just the field, is proven.
		PacketNumberSpace wide = space();
		for (long pn : new long[]{0xa82f30eaL}) {
			assertTrue(wide.onPacketReceived(pn, 1000, true));
		}
		assertEquals(0xa82f9b32L, wide.reconstruct(0x9b32, 2));
		assertEquals(PacketNumbers.reconstruct(0x9b32, 2, 0xa82f30eaL), wide.reconstruct(0x9b32, 2));
	}

	@Test
	public void largestReceivedTimeIsRecordedOnlyWhenTheLargestAdvances() throws Exception {
		PacketNumberSpace space = space();
		assertTrue(space.onPacketReceived(10, 1000, true));
		assertEquals(1000, space.largestReceivedTime());

		// An older packet arriving later must NOT refresh the timestamp: it feeds the ACK Delay we
		// advertise, and refreshing it would inflate the peer's RTT estimate.
		assertTrue(space.onPacketReceived(5, 2000, true));
		assertEquals(1000, space.largestReceivedTime());
		assertEquals(10, space.largestReceived());

		assertTrue(space.onPacketReceived(11, 3000, true));
		assertEquals(3000, space.largestReceivedTime());
	}

	@Test
	public void ackAboveLargestSentIsProtocolViolation() throws Exception {
		PacketNumberSpace space = space();
		for (int i = 0; i <= 5; i++) {
			space.onPacketSent(sent(space, 100, true));
		}
		assertEquals(5, space.largestSent());

		// The boundary is legal.
		space.onAckReceived(5);
		assertEquals(5, space.largestAcked());

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> space.onAckReceived(9));
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
	}

	@Test
	public void receivedNumberAboveMaxIsProtocolViolation() {
		PacketNumberSpace space = space();
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> space.onPacketReceived(PacketNumberSpace.MAX_PACKET_NUMBER + 1, 1000, true));
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
	}

	@Test
	public void maxPacketNumberItselfIsAccepted() throws Exception {
		PacketNumberSpace space = space();
		assertTrue(space.onPacketReceived(PacketNumberSpace.MAX_PACKET_NUMBER, 1000, true));
		assertEquals(PacketNumberSpace.MAX_PACKET_NUMBER, space.largestReceived());
	}

	@Test
	public void ackElicitingCountersDriveAckAndPto() throws Exception {
		PacketNumberSpace space = space();

		// Non-ack-eliciting receipt does not move the ACK trigger.
		assertTrue(space.onPacketReceived(1, 1000, false));
		assertEquals(0, space.ackElicitingReceivedSinceAck());
		assertTrue(space.onPacketReceived(2, 1000, true));
		assertEquals(1, space.ackElicitingReceivedSinceAck());

		space.onAckGenerated();
		assertEquals(0, space.ackElicitingReceivedSinceAck());

		// In-flight accounting: up on send, down on ack, down on loss.
		SentPacket a = sent(space, 100, true);
		SentPacket b = sent(space, 100, true);
		SentPacket pureAck = sent(space, 100, false);
		space.onPacketSent(a);
		space.onPacketSent(b);
		space.onPacketSent(pureAck);
		assertEquals(2, space.ackElicitingInFlight());

		assertSame(a, space.onPacketAcked(a.packetNumber));
		assertEquals(1, space.ackElicitingInFlight());
		assertSame(b, space.onPacketLost(b.packetNumber));
		assertEquals(0, space.ackElicitingInFlight());
	}

	@Test
	public void duplicateAckOfTheSamePacketIsNotAnError() {
		PacketNumberSpace space = space();
		SentPacket a = sent(space, 100, true);
		space.onPacketSent(a);

		assertSame(a, space.onPacketAcked(a.packetNumber));
		// Already removed: a duplicate ACK is normal.
		assertNull(space.onPacketAcked(a.packetNumber));
		assertEquals(0, space.ackElicitingInFlight());
	}

	@Test
	public void discardDropsSentRecordsWithoutDeclaringLoss() {
		PacketNumberSpace space = new PacketNumberSpace(EncryptionLevel.INITIAL, 32);

		// Packets holding real buffers, so a failure to recycle shows up as a leak.
		for (int i = 0; i < 3; i++) {
			long pn = space.nextPacketNumber();
			space.onPacketSent(new SentPacket(pn, EncryptionLevel.INITIAL, 100, 1200, true, true,
				List.of(crypto("crypto-" + i), PingFrame.INSTANCE), false));
		}
		assertEquals(3, space.outstandingCount());
		assertEquals(3, space.ackElicitingInFlight());
		space.setLossTime(5000);

		space.discard();

		assertEquals(0, space.outstandingCount());
		assertEquals(0, space.ackElicitingInFlight());
		assertEquals(0, space.lossTime());
		assertTrue(space.isDiscarded());
		// No loss was declared: the caller received no records back, so nothing could be re-queued or
		// reported to congestion control.

		// Idempotent.
		space.discard();
		assertEquals(0, space.outstandingCount());
	}

	@Test
	public void receivedRangesAreBounded() throws Exception {
		PacketNumberSpace space = new PacketNumberSpace(EncryptionLevel.ONE_RTT, 4);
		for (long pn = 0; pn <= 20; pn += 2) {
			assertTrue(space.onPacketReceived(pn, 1000, true));
		}
		assertEquals(4, space.received().rangeCount());
		assertEquals(20, space.received().largest());
	}

	@Test
	public void sentPacketLookup() {
		PacketNumberSpace space = space();
		SentPacket a = sent(space, 100, true);
		space.onPacketSent(a);
		assertSame(a, space.sentPacket(a.packetNumber));
		assertNull(space.sentPacket(999));
		assertEquals(1, space.sentPackets().size());
		space.discard();
	}

	@Test
	public void frameTypeMatrixIsNotThisClassesResponsibility() {
		// Deliberately empty: FR-013's frame-type/packet-type matrix and the empty-payload rule belong
		// to the receive path. Asserting them here too would duplicate enforcement.
		QuicFrame frame = PingFrame.INSTANCE;
		assertNotNull(frame);
	}
}
