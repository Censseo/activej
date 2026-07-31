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

import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 9002 §6 loss detection: the packet threshold, the time threshold, and the probe timeout that
 * covers the case where neither can fire because nothing newer was acknowledged.
 * <p>
 * Reactor-free and clock-free — {@code now} is a parameter, so the whole of loss detection is testable
 * by arithmetic rather than by waiting (constitution §IV, FR-039).
 * <p>
 * <b>Why two thresholds.</b> The packet threshold catches loss quickly when traffic keeps flowing: if
 * three later packets arrived, the gap is almost certainly not reordering. The time threshold catches
 * the tail, where there is no later packet to count. Neither can detect the loss of the <i>last</i>
 * packet in flight — nothing will ever be acknowledged after it — which is exactly the hole the probe
 * timeout fills.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-6">RFC 9002 §6 — Loss Detection</a>
 */
public final class LossDetector {
	private LossDetector() {}

	/** RFC 9002 §6.1.1 {@code kPacketThreshold}: the RECOMMENDED value, and the minimum permitted. */
	public static final int PACKET_THRESHOLD = 3;

	/** RFC 9002 §6.2.1 {@code kPersistentCongestionThreshold}. */
	public static final int PERSISTENT_CONGESTION_THRESHOLD = 3;

	/** The outcome of one detection pass over one packet number space. */
	public record Detection(List<SentPacket> lost, long nextLossTime) {}

	/**
	 * Declares lost every packet in {@code space} that either sits {@link #PACKET_THRESHOLD} or more
	 * below the largest acknowledged packet, or was sent longer than {@code rtt.lossDelayMillis()} ago.
	 * <p>
	 * Removes the lost packets from the space and records the earliest deadline at which a packet not yet
	 * lost will become so, in {@link Detection#nextLossTime()} (0 when there is none) — that value is
	 * what the caller arms its loss timer with.
	 *
	 * @return the lost packets, in ascending packet-number order, and the next loss deadline
	 */
	public static Detection detectLost(PacketNumberSpace space, long now, RttEstimator rtt) {
		long largestAcked = space.largestAcked();
		if (largestAcked == PacketNumberSpace.NONE) {
			// Nothing has been acknowledged in this space, so there is no reference point for either
			// threshold. Only the probe timeout applies until the first ACK arrives.
			space.setLossTime(0);
			return new Detection(List.of(), 0);
		}

		long lossDelay = rtt.lossDelayMillis();
		long lostSendTimeBoundary = now - lossDelay;
		List<Long> lostNumbers = new ArrayList<>();
		long nextLossTime = 0;

		for (Map.Entry<Long, SentPacket> entry : space.sentPackets().entrySet()) {
			long packetNumber = entry.getKey();
			if (packetNumber > largestAcked) {
				// Not yet eligible: nothing later has been acknowledged, so its absence proves nothing.
				continue;
			}
			SentPacket packet = entry.getValue();
			if (packetNumber + PACKET_THRESHOLD <= largestAcked || packet.sentTime <= lostSendTimeBoundary) {
				lostNumbers.add(packetNumber);
			} else {
				// Below largestAcked but still young: it will cross the time threshold at this instant
				// unless an ACK retires it first.
				long deadline = packet.sentTime + lossDelay;
				if (nextLossTime == 0 || deadline < nextLossTime) {
					nextLossTime = deadline;
				}
			}
		}

		lostNumbers.sort(null);
		List<SentPacket> lost = new ArrayList<>(lostNumbers.size());
		for (long packetNumber : lostNumbers) {
			SentPacket packet = space.onPacketLost(packetNumber);
			if (packet != null) {
				lost.add(packet);
			}
		}
		space.setLossTime(nextLossTime);
		return new Detection(lost, nextLossTime);
	}

	/**
	 * The earliest armed loss deadline across the given spaces, or {@code null} when none is armed.
	 * <p>
	 * RFC 9002 §6.2: a single timer serves all spaces, set to the earliest deadline; the space it belongs
	 * to is what the handler needs to know in order to act.
	 */
	public static @Nullable Armed earliestLossTime(Iterable<PacketNumberSpace> spaces) {
		Armed earliest = null;
		for (PacketNumberSpace space : spaces) {
			if (space.isDiscarded()) continue;
			long lossTime = space.lossTime();
			if (lossTime == 0) continue;
			if (earliest == null || lossTime < earliest.time()) {
				earliest = new Armed(space.level(), lossTime);
			}
		}
		return earliest;
	}

	/** A deadline and the space it belongs to. */
	public record Armed(EncryptionLevel level, long time) {}

	/**
	 * The space whose probe timer should be armed, and when — RFC 9002 §6.2.1's "earliest time and
	 * space", computed over the earliest ack-eliciting packet still in flight.
	 * <p>
	 * The Application Data space is charged the peer's {@code max_ack_delay}; the handshake spaces are
	 * not, because they are acknowledged immediately (T036) and charging them would only slow recovery
	 * of the handshake itself.
	 *
	 * @param handshakeConfirmed before confirmation the 1-RTT space is not probed (RFC 9002 §6.2.1): the
	 *                           peer may not have its keys yet, so a probe would be undecryptable
	 * @return {@code null} when nothing ack-eliciting is in flight, i.e. there is nothing to probe for
	 */
	public static @Nullable Armed nextProbe(
		Iterable<PacketNumberSpace> spaces, RttEstimator rtt, int ptoCount, long peerMaxAckDelay,
		boolean handshakeConfirmed
	) {
		Armed earliest = null;
		for (PacketNumberSpace space : spaces) {
			if (space.isDiscarded() || space.ackElicitingInFlight() == 0) continue;
			if (space.level() == EncryptionLevel.ONE_RTT && !handshakeConfirmed) continue;

			long oldest = Long.MAX_VALUE;
			for (SentPacket packet : space.sentPackets().values()) {
				if (packet.ackEliciting && packet.sentTime < oldest) {
					oldest = packet.sentTime;
				}
			}
			if (oldest == Long.MAX_VALUE) continue;

			boolean isApplicationData = space.level() == EncryptionLevel.ONE_RTT;
			long deadline = oldest + rtt.ptoMillis(ptoCount, isApplicationData, peerMaxAckDelay);
			if (earliest == null || deadline < earliest.time()) {
				earliest = new Armed(space.level(), deadline);
			}
		}
		return earliest;
	}
}
