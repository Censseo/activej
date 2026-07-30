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

import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.PacketNumbers;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * One QUIC packet number space (RFC 9000 §12.3): the send-side numbering, the receive-side history,
 * and the counters the ACK and loss-recovery logic read. One instance per encryption level — Initial,
 * Handshake, and Application Data (which maps to {@code ONE_RTT}).
 * <p>
 * Time is always a parameter, never read from a clock, so timer behaviour is testable without
 * sleeping (FR-039).
 * <p>
 * Not thread-safe: the owning connection provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.3">RFC 9000 §12.3 — Packet Numbers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-13.2">RFC 9000 §13.2 — Generating Acknowledgements</a>
 */
public final class PacketNumberSpace {
	/** RFC 9000 §12.3: packet numbers are limited to 2^62 - 1. */
	public static final long MAX_PACKET_NUMBER = (1L << 62) - 1;

	/** Returned when nothing has been sent or received yet. */
	public static final long NONE = -1;

	private final EncryptionLevel level;

	private long nextPacketNumber;
	private long largestSent = NONE;
	private long largestReceived = NONE;
	private long largestReceivedTime;
	private final AckRanges received;
	private final Map<Long, SentPacket> sent = new HashMap<>();
	private int ackElicitingReceivedSinceAck;
	private int ackElicitingInFlight;
	private long largestAcked = NONE;
	private long lossTime;
	private boolean discarded;

	public PacketNumberSpace(EncryptionLevel level, int maxAckRanges) {
		this.level = level;
		this.received = new AckRanges(maxAckRanges);
	}

	/**
	 * The next packet number to use, consumed by this call.
	 * <p>
	 * Strictly monotonic and <b>never reused</b>, including for a retransmission (FR-009): a
	 * retransmitted frame travels in a new packet with a new number. Reuse would repeat an AEAD nonce
	 * (RFC 9001 §5.3) — a cryptographic failure, not merely a protocol one. There is deliberately no
	 * way to rewind or to supply a number.
	 */
	public long nextPacketNumber() {
		return nextPacketNumber++;
	}

	public void onPacketSent(SentPacket packet) {
		sent.put(packet.packetNumber, packet);
		if (packet.packetNumber > largestSent) {
			largestSent = packet.packetNumber;
		}
		if (packet.ackEliciting) {
			ackElicitingInFlight++;
		}
	}

	/**
	 * Records a received packet.
	 *
	 * @param now from {@code reactor.currentTimeMillis()}
	 * @return {@code true} when this packet is new, {@code false} when it is a duplicate whose frames
	 *         must not be processed again (FR-010)
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION} when the number exceeds 2^62 - 1
	 */
	public boolean onPacketReceived(long packetNumber, long now, boolean ackEliciting) throws QuicTransportException {
		checkPacketNumber(packetNumber);

		// Return before any counter moves: a replayed packet must not inflate the ACK trigger.
		if (received.contains(packetNumber)) {
			return false;
		}

		received.add(packetNumber);
		if (packetNumber > largestReceived) {
			largestReceived = packetNumber;
			// Only updated when the largest advances: this feeds the ACK Delay we advertise, and
			// refreshing it on an out-of-order arrival would inflate it and corrupt the peer's RTT.
			largestReceivedTime = now;
		}
		if (ackEliciting) {
			ackElicitingReceivedSinceAck++;
		}
		return true;
	}

	/**
	 * Recovers a full packet number from the truncated value on the wire, using this space's
	 * largest-received as the context feature 01's codec deliberately does not keep.
	 *
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION} when the result exceeds 2^62 - 1
	 */
	public long reconstruct(long truncated, int numBytes) throws QuicTransportException {
		long full = PacketNumbers.reconstruct(truncated, numBytes, largestReceived);
		checkPacketNumber(full);
		return full;
	}

	private static void checkPacketNumber(long packetNumber) throws QuicTransportException {
		if (packetNumber < 0 || packetNumber > MAX_PACKET_NUMBER) {
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"packet number out of range: " + packetNumber);
		}
	}

	/**
	 * Validates an incoming ACK frame's Largest Acknowledged against what we have actually sent.
	 *
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION} when the peer acknowledges a packet we
	 *                                never sent (FR-019)
	 */
	public void onAckReceived(long largestAckedInFrame) throws QuicTransportException {
		if (largestAckedInFrame > largestSent) {
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"ACK acknowledges packet " + largestAckedInFrame + " but the largest sent in " + level +
				" was " + largestSent);
		}
		if (largestAckedInFrame > largestAcked) {
			largestAcked = largestAckedInFrame;
		}
	}

	/**
	 * Removes an acknowledged packet and returns its record, so the caller can take an RTT sample and
	 * notify the frame handler (FR-038). Ownership of the record's frames passes to the caller.
	 *
	 * @return {@code null} when already removed — a duplicate ACK is normal, not an error
	 */
	public @Nullable SentPacket onPacketAcked(long packetNumber) {
		SentPacket packet = sent.remove(packetNumber);
		if (packet != null && packet.ackEliciting) {
			ackElicitingInFlight--;
		}
		return packet;
	}

	/** Removes a packet declared lost, returning its record so its frames can be re-queued. */
	public @Nullable SentPacket onPacketLost(long packetNumber) {
		SentPacket packet = sent.remove(packetNumber);
		if (packet != null && packet.ackEliciting) {
			ackElicitingInFlight--;
		}
		return packet;
	}

	/** Resets the ACK trigger after an ACK has been generated for this space (FR-014). */
	public void onAckGenerated() {
		ackElicitingReceivedSinceAck = 0;
	}

	/**
	 * Discards this space when its keys are discarded (RFC 9001 §4.9).
	 * <p>
	 * The outstanding packets are dropped, <b>not</b> declared lost: Initial and Handshake spaces are
	 * discarded during a perfectly successful handshake, and treating their packets as losses would
	 * trigger a spurious congestion reduction on every connection. Their frames are recycled here,
	 * since nobody will re-queue them. Idempotent (WI-9).
	 */
	public void discard() {
		abandonOutstanding();
		discarded = true;
	}

	/**
	 * Drops the outstanding packets and recycles their frames without retiring the space.
	 * <p>
	 * This is what the closing state needs (RFC 9000 §10.2.1): nothing in flight will ever be
	 * acknowledged or usefully retransmitted, but the space must still hand out packet numbers for the
	 * CONNECTION_CLOSE re-sends. Idempotent.
	 */
	public void abandonOutstanding() {
		for (SentPacket packet : sent.values()) {
			for (QuicFrame frame : packet.frames) {
				Recyclers.recycle(frame);
			}
		}
		sent.clear();
		ackElicitingInFlight = 0;
		lossTime = 0;
	}

	public EncryptionLevel level() {
		return level;
	}

	public long largestSent() {
		return largestSent;
	}

	public long largestReceived() {
		return largestReceived;
	}

	/** The time the largest received packet arrived — the basis of the next ACK's Delay field. */
	public long largestReceivedTime() {
		return largestReceivedTime;
	}

	public long largestAcked() {
		return largestAcked;
	}

	public AckRanges received() {
		return received;
	}

	public int ackElicitingReceivedSinceAck() {
		return ackElicitingReceivedSinceAck;
	}

	public int ackElicitingInFlight() {
		return ackElicitingInFlight;
	}

	public int outstandingCount() {
		return sent.size();
	}

	public @Nullable SentPacket sentPacket(long packetNumber) {
		return sent.get(packetNumber);
	}

	/** The outstanding packets, for the loss detector to scan. */
	public Map<Long, SentPacket> sentPackets() {
		return sent;
	}

	/** The earliest time-threshold loss deadline in this space, 0 when none. */
	public long lossTime() {
		return lossTime;
	}

	public void setLossTime(long lossTime) {
		this.lossTime = lossTime;
	}

	public boolean isDiscarded() {
		return discarded;
	}

	@Override
	public String toString() {
		return "PacketNumberSpace{" +
			level +
			", nextPn=" + nextPacketNumber +
			", largestSent=" + largestSent +
			", largestReceived=" + largestReceived +
			", largestAcked=" + largestAcked +
			", outstanding=" + sent.size() +
			", ackElicitingInFlight=" + ackElicitingInFlight +
			", received=" + received +
			(discarded ? ", discarded" : "") +
			'}';
	}
}
