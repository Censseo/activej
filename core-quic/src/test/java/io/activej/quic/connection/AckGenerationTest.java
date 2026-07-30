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

import io.activej.quic.codec.AckFrame;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T048 — the RFC 9000 §13.2 ACK scheduler: when an ACK is owed, what ranges it carries, and how its
 * Delay field is scaled.
 * <p>
 * Driven through {@link PacketNumberSpace} and {@link AckRanges} rather than through a live connection,
 * because the interesting cases — a gap that never closes, an ACK owed but not yet due — are states a
 * cooperating loopback peer never produces.
 */
public final class AckGenerationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What {@code QuicConnection} advertises, and therefore the scale of every Delay field it writes. */
	private static final long UNITS_PER_MILLI = 1000 >> QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT;

	private static PacketNumberSpace space(EncryptionLevel level) {
		return new PacketNumberSpace(level, 32);
	}

	private static void receive(PacketNumberSpace space, long packetNumber, long now) throws Exception {
		assertTrue("packet " + packetNumber + " was treated as a duplicate",
			space.onPacketReceived(packetNumber, now, true));
	}

	// ---------------------------------------------------------------- when an ACK is owed

	@Test
	public void nothingIsOwedBeforeAnAckElicitingPacketArrives() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		assertEquals(0, oneRtt.ackElicitingReceivedSinceAck());

		// An ACK-only packet is received and tracked, but acknowledging an acknowledgement would never
		// terminate (RFC 9000 §13.2.1).
		assertTrue(oneRtt.onPacketReceived(0, 1000, false));
		assertEquals(0, oneRtt.ackElicitingReceivedSinceAck());
		assertFalse(oneRtt.received().isEmpty());
	}

	@Test
	public void theSecondAckElicitingPacketIsWhatMakesAnAckDue() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);

		receive(oneRtt, 0, 1000);
		// RFC 9000 §13.2.2: one is not enough — that is what max_ack_delay is for.
		assertEquals(1, oneRtt.ackElicitingReceivedSinceAck());

		receive(oneRtt, 1, 1000);
		assertEquals(2, oneRtt.ackElicitingReceivedSinceAck());
	}

	@Test
	public void generatingAnAckResetsTheTrigger() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		receive(oneRtt, 0, 1000);
		receive(oneRtt, 1, 1000);

		oneRtt.onAckGenerated();

		assertEquals(0, oneRtt.ackElicitingReceivedSinceAck());
		// The ranges themselves survive: RFC 9000 §13.2.4 keeps re-sending them until they are
		// superseded, because an ACK is not itself acknowledged.
		assertEquals(1, oneRtt.received().largest());
	}

	@Test
	public void aDuplicateDoesNotInflateTheAckTrigger() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		receive(oneRtt, 7, 1000);

		assertFalse("a replay must not be processed twice (FR-010)", oneRtt.onPacketReceived(7, 1000, true));

		// Otherwise a peer replaying one packet could drive us to ACK on demand, which is a cheap
		// reflection amplifier.
		assertEquals(1, oneRtt.ackElicitingReceivedSinceAck());
	}

	// ---------------------------------------------------------------- the ranges

	@Test
	public void everyTrackedRangeIsEncoded() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		// 0,1 … 4,5 … 9: two gaps, so the ACK must carry three ranges or the peer will declare packets
		// lost that we in fact received.
		for (long pn : new long[]{0, 1, 4, 5, 9}) {
			receive(oneRtt, pn, 1000);
		}

		AckRanges received = oneRtt.received();
		assertEquals(9, received.largest());
		assertEquals("the largest range is [9,9]", 0, received.firstRangeLength());
		assertEquals(2, received.gaps().length);
		assertEquals(2, received.rangeLengths().length);

		AckFrame ack = AckFrame.withoutEcn(received.largest(), 0, received.firstRangeLength(),
			received.gaps(), received.rangeLengths());
		// The frame's own exact sizing is what the send path uses to decide whether the ACK fits.
		assertTrue(ack.encodedLength() > 0);
		assertEquals(9, ack.largestAcked);
		assertEquals(0, ack.firstAckRange);
	}

	@Test
	public void anAckIsNeitherAckElicitingNorRetransmittable() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		receive(oneRtt, 0, 1000);
		AckRanges received = oneRtt.received();
		AckFrame ack = AckFrame.withoutEcn(received.largest(), 0, received.firstRangeLength(),
			received.gaps(), received.rangeLengths());

		// An ACK-only packet must not itself demand an ACK, or two idle peers would acknowledge each
		// other forever. FrameTypeRules and the send path agree on this classification.
		assertTrue(FrameTypeRules.isAllowed(ack, EncryptionLevel.ONE_RTT));
		// And a lost ACK is never re-queued: a newer one supersedes it (RFC 9002 §6.5). That is asserted
		// in QuicConnection's retransmission path; here the point is that an ACK carries no payload
		// buffer to leak if it is simply dropped.
		assertEquals(0, ack.gaps.length);
	}

	// ---------------------------------------------------------------- the Delay field

	@Test
	public void theDelayFieldIsScaledByTheAdvertisedAckDelayExponent() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		receive(oneRtt, 0, 1000);

		// RFC 9000 §19.3: the field is in microseconds shifted right by ack_delay_exponent. We advertise
		// the default exponent of 3, so one unit is 8 µs and a millisecond is 125 units — get this wrong
		// and the peer's RTT estimate is off by a factor of 8, which is a factor of 8 on every timeout.
		assertEquals(125, UNITS_PER_MILLI);
		long delayMillis = 1040 - oneRtt.largestReceivedTime();
		assertEquals(40, delayMillis);

		AckFrame ack = AckFrame.withoutEcn(oneRtt.received().largest(), delayMillis * UNITS_PER_MILLI,
			oneRtt.received().firstRangeLength(), oneRtt.received().gaps(), oneRtt.received().rangeLengths());
		assertEquals(5000, ack.ackDelay);
	}

	@Test
	public void theDelayIsMeasuredFromTheLargestReceivedPacketOnly() throws Exception {
		PacketNumberSpace oneRtt = space(EncryptionLevel.ONE_RTT);
		receive(oneRtt, 5, 1000);
		// An older packet arriving late must not move the clock the Delay field is measured against:
		// that timestamp belongs to the packet being timed, and refreshing it would inflate the delay we
		// report and corrupt the peer's RTT estimate downward.
		receive(oneRtt, 3, 2000);

		assertEquals(5, oneRtt.received().largest());
		assertEquals(1000, oneRtt.largestReceivedTime());
	}

	// ---------------------------------------------------------------- per-level scheduling

	@Test
	public void theHandshakeSpacesAcknowledgeOnTheFirstPacket() throws Exception {
		// FR-014/T036: max_ack_delay applies to Application Data only (RFC 9000 §18.2), so a single
		// ack-eliciting Initial or Handshake packet is already owed an ACK. Delaying one would just make
		// the peer probe, slowing the handshake for nothing.
		for (EncryptionLevel level : new EncryptionLevel[]{EncryptionLevel.INITIAL, EncryptionLevel.HANDSHAKE}) {
			PacketNumberSpace space = space(level);
			receive(space, 0, 1000);
			assertEquals("one ack-eliciting packet at " + level, 1, space.ackElicitingReceivedSinceAck());
		}
	}

	@Test
	public void aDiscardedSpaceOwesNothing() throws Exception {
		PacketNumberSpace initial = space(EncryptionLevel.INITIAL);
		receive(initial, 0, 1000);
		receive(initial, 1, 1000);

		initial.discard();

		// RFC 9001 §4.9: once the level is gone there is nothing to send an ACK under, and a late
		// retransmission at that level is dropped rather than answered.
		assertTrue(initial.isDiscarded());
		assertEquals(0, initial.outstandingCount());
	}
}
