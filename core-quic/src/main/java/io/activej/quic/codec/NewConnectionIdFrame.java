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
import io.activej.quic.QuicConnectionId;

import java.util.Arrays;
import java.util.Objects;

/**
 * NEW_CONNECTION_ID frame (RFC 9000 §19.15). {@link #connectionId} must be 1-20 bytes
 * (unlike the general {@link QuicConnectionId} range of 0-20) and {@link #statelessResetToken}
 * is always exactly 16 bytes.
 */
public final class NewConnectionIdFrame extends QuicFrame {
	public static final int TYPE = 0x18;
	public static final int STATELESS_RESET_TOKEN_LENGTH = 16;

	public final long sequenceNumber;
	public final long retirePriorTo;
	public final QuicConnectionId connectionId;
	public final byte[] statelessResetToken;

	public NewConnectionIdFrame(long sequenceNumber, long retirePriorTo, QuicConnectionId connectionId, byte[] statelessResetToken) {
		if (connectionId.length() < 1) {
			throw new IllegalArgumentException("NEW_CONNECTION_ID connection ID must be 1-20 bytes, was 0");
		}
		if (statelessResetToken.length != STATELESS_RESET_TOKEN_LENGTH) {
			throw new IllegalArgumentException(
				"Stateless reset token must be " + STATELESS_RESET_TOKEN_LENGTH + " bytes: " + statelessResetToken.length);
		}
		this.sequenceNumber = sequenceNumber;
		this.retirePriorTo = retirePriorTo;
		this.connectionId = connectionId;
		this.statelessResetToken = statelessResetToken.clone();
	}

	/** Defensive copy of the 16-byte stateless reset token. */
	public byte[] statelessResetToken() {
		return statelessResetToken.clone();
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE)
			+ QuicVarInts.encodedLength(sequenceNumber)
			+ QuicVarInts.encodedLength(retirePriorTo)
			+ 1 + connectionId.length()
			+ STATELESS_RESET_TOKEN_LENGTH;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, sequenceNumber);
		QuicVarInts.write(buf, retirePriorTo);
		buf.writeByte((byte) connectionId.length());
		buf.put(connectionId.bytes());
		buf.put(statelessResetToken, 0, STATELESS_RESET_TOKEN_LENGTH);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof NewConnectionIdFrame other)) return false;
		return sequenceNumber == other.sequenceNumber && retirePriorTo == other.retirePriorTo
			&& connectionId.equals(other.connectionId) && Arrays.equals(statelessResetToken, other.statelessResetToken);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sequenceNumber, retirePriorTo, connectionId, Arrays.hashCode(statelessResetToken));
	}
}
