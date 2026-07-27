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
import io.activej.common.recycle.Recyclable;
import io.activej.quic.QuicConnectionId;

/**
 * Short header (1-RTT) packet (RFC 9000 §17.3). Unlike long-header packets, the Destination
 * Connection ID has no length prefix on the wire — the caller must already know its length
 * (fixed by local configuration/negotiation) to parse one; see {@link QuicPackets#parse}.
 * {@link #packetNumber} is the raw truncated value (see {@link QuicPacket}); {@link #payload}
 * always extends to the end of the datagram (a short-header packet is never coalesced with a
 * following one). Owns a retained slice of {@link #payload} — the caller must {@link #recycle()}
 * it after use.
 */
public final class ShortHeaderPacket extends QuicPacket implements Recyclable {
	public final QuicConnectionId destinationConnectionId;
	public final boolean spinBit;
	public final boolean keyPhase;
	public final long packetNumber;
	public final int packetNumberLength;
	public final ByteBuf payload;

	public ShortHeaderPacket(
		QuicConnectionId destinationConnectionId, boolean spinBit, boolean keyPhase,
		long packetNumber, int packetNumberLength, ByteBuf payload
	) {
		if (packetNumberLength < 1 || packetNumberLength > 4) {
			throw new IllegalArgumentException("Packet number length must be in [1, 4]: " + packetNumberLength);
		}
		this.destinationConnectionId = destinationConnectionId;
		this.spinBit = spinBit;
		this.keyPhase = keyPhase;
		this.packetNumber = packetNumber;
		this.packetNumberLength = packetNumberLength;
		this.payload = payload;
	}

	@Override
	public QuicConnectionId destinationConnectionId() {
		return destinationConnectionId;
	}

	@Override
	public int encodedLength() {
		return 1 + destinationConnectionId.length() + packetNumberLength + payload.readRemaining();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int firstByte = 0x40 | (spinBit ? 0x20 : 0) | (keyPhase ? 0x04 : 0) | (packetNumberLength - 1);
		buf.writeByte((byte) firstByte);
		buf.put(destinationConnectionId.bytes());
		PacketNumbers.write(buf, packetNumber, packetNumberLength);
		buf.put(payload.array(), payload.head(), payload.readRemaining());
	}

	@Override
	public void recycle() {
		payload.recycle();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ShortHeaderPacket other)) return false;
		return spinBit == other.spinBit && keyPhase == other.keyPhase
			&& packetNumber == other.packetNumber && packetNumberLength == other.packetNumberLength
			&& destinationConnectionId.equals(other.destinationConnectionId)
			&& ByteBufContents.equals(payload, other.payload);
	}

	@Override
	public int hashCode() {
		int result = destinationConnectionId.hashCode();
		result = 31 * result + Boolean.hashCode(spinBit);
		result = 31 * result + Boolean.hashCode(keyPhase);
		result = 31 * result + Long.hashCode(packetNumber);
		result = 31 * result + packetNumberLength;
		result = 31 * result + ByteBufContents.hashCode(payload);
		return result;
	}
}
