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

import java.util.Arrays;

/**
 * Initial packet (RFC 9000 §17.2.2). {@link #packetNumber} is the raw truncated value (see
 * {@link QuicPacket}); {@link #payload} is the unprotected packet payload. Owns a retained slice
 * of {@link #payload} — the caller must {@link #recycle()} it after use.
 */
public final class InitialPacket extends QuicLongHeaderPacket implements Recyclable {
	public static final int LONG_PACKET_TYPE = 0x0;

	public final byte[] token;
	public final long packetNumber;
	public final int packetNumberLength;
	public final ByteBuf payload;

	public InitialPacket(
		long version, QuicConnectionId destinationConnectionId, QuicConnectionId sourceConnectionId,
		byte[] token, long packetNumber, int packetNumberLength, ByteBuf payload
	) {
		super(version, destinationConnectionId, sourceConnectionId);
		if (packetNumberLength < 1 || packetNumberLength > 4) {
			throw new IllegalArgumentException("Packet number length must be in [1, 4]: " + packetNumberLength);
		}
		this.token = token.clone();
		this.packetNumber = packetNumber;
		this.packetNumberLength = packetNumberLength;
		this.payload = payload;
	}

	/** Defensive copy of the Initial Token (0 bytes unless the client is retrying with a server-issued token). */
	public byte[] token() {
		return token.clone();
	}

	@Override
	public int encodedLength() {
		return 1 + 4
			+ 1 + destinationConnectionId.length()
			+ 1 + sourceConnectionId.length()
			+ QuicVarInts.encodedLength(token.length) + token.length
			+ QuicVarInts.encodedLength(packetNumberLength + payload.readRemaining())
			+ packetNumberLength
			+ payload.readRemaining();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) (0xC0 | (packetNumberLength - 1)));
		buf.writeInt((int) version);
		buf.writeByte((byte) destinationConnectionId.length());
		buf.put(destinationConnectionId.bytes());
		buf.writeByte((byte) sourceConnectionId.length());
		buf.put(sourceConnectionId.bytes());
		QuicVarInts.write(buf, token.length);
		buf.put(token);
		QuicVarInts.write(buf, packetNumberLength + payload.readRemaining());
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
		if (!(o instanceof InitialPacket other)) return false;
		return version == other.version && packetNumber == other.packetNumber
			&& packetNumberLength == other.packetNumberLength
			&& destinationConnectionId.equals(other.destinationConnectionId)
			&& sourceConnectionId.equals(other.sourceConnectionId)
			&& Arrays.equals(token, other.token)
			&& ByteBufContents.equals(payload, other.payload);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(version);
		result = 31 * result + destinationConnectionId.hashCode();
		result = 31 * result + sourceConnectionId.hashCode();
		result = 31 * result + Arrays.hashCode(token);
		result = 31 * result + ByteBufContents.hashCode(payload);
		result = 31 * result + Long.hashCode(packetNumber);
		result = 31 * result + packetNumberLength;
		return result;
	}
}
