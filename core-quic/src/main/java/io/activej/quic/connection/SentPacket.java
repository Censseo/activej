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

import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;

import java.util.List;

/**
 * The metadata RFC 9002's loss-detection and congestion-control algorithms keep about one packet that
 * has been sent (RFC 9002 §A.1). Immutable.
 * <p>
 * <b>Frame ownership (DI-1)</b>: by the time this record exists, the packet's bytes have been
 * protected and handed to the socket. {@link #frames} is retained for one purpose only — to be
 * re-queued if the packet is declared lost. Payload-carrying frames still hold retained
 * {@code ByteBuf} slices, so whoever abandons a {@code SentPacket} <i>without</i> re-queueing its
 * frames must recycle them. {@link PacketNumberSpace#discard()} is that path.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#appendix-A.1">RFC 9002 §A.1 — Tracking Sent Packets</a>
 */
public final class SentPacket {
	public final long packetNumber;
	public final EncryptionLevel level;
	/** From {@code reactor.currentTimeMillis()} — never a wall clock (FR-039). */
	public final long sentTime;
	/** Counted against bytes in flight and against the amplification budget. */
	public final int sizeInBytes;
	/** False for packets carrying only ACK and/or PADDING frames. */
	public final boolean ackEliciting;
	/** False for packets carrying only ACK and/or PADDING frames (RFC 9002 §2). */
	public final boolean inFlight;
	public final List<QuicFrame> frames;
	/** True when contributed by the frame handler; drives the ack/loss notification of FR-038. */
	public final boolean handlerOwned;

	public SentPacket(
		long packetNumber, EncryptionLevel level, long sentTime, int sizeInBytes,
		boolean ackEliciting, boolean inFlight, List<QuicFrame> frames, boolean handlerOwned
	) {
		this.packetNumber = packetNumber;
		this.level = level;
		this.sentTime = sentTime;
		this.sizeInBytes = sizeInBytes;
		this.ackEliciting = ackEliciting;
		this.inFlight = inFlight;
		this.frames = frames;
		this.handlerOwned = handlerOwned;
	}

	@Override
	public String toString() {
		return "SentPacket{" +
			"pn=" + packetNumber +
			", " + level +
			", sentTime=" + sentTime +
			", size=" + sizeInBytes +
			(ackEliciting ? ", ackEliciting" : "") +
			(inFlight ? ", inFlight" : "") +
			", frames=" + frames.size() +
			(handlerOwned ? ", handlerOwned" : "") +
			'}';
	}
}
