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

/**
 * Retry packet (RFC 9000 §17.2.5). Has no packet number and no length prefix on its token — a
 * Retry packet is always the only packet in its datagram. {@link #retryIntegrityTag} is exactly
 * 16 bytes; validating it is RFC 9001 §5.8's job ({@code RetryIntegrityTag}), not this codec's.
 */
public final class RetryPacket extends QuicLongHeaderPacket {
	public static final int LONG_PACKET_TYPE = 0x3;
	public static final int INTEGRITY_TAG_LENGTH = 16;

	public final byte[] retryToken;
	public final byte[] retryIntegrityTag;

	public RetryPacket(
		long version, QuicConnectionId destinationConnectionId, QuicConnectionId sourceConnectionId,
		byte[] retryToken, byte[] retryIntegrityTag
	) {
		super(version, destinationConnectionId, sourceConnectionId);
		if (retryIntegrityTag.length != INTEGRITY_TAG_LENGTH) {
			throw new IllegalArgumentException(
				"Retry integrity tag must be " + INTEGRITY_TAG_LENGTH + " bytes: " + retryIntegrityTag.length);
		}
		this.retryToken = retryToken.clone();
		this.retryIntegrityTag = retryIntegrityTag.clone();
	}

	/** Defensive copy of the Retry Token (opaque bytes the client must echo back). */
	public byte[] retryToken() {
		return retryToken.clone();
	}

	/** Defensive copy of the 16-byte Retry Integrity Tag (RFC 9001 §5.8). */
	public byte[] retryIntegrityTag() {
		return retryIntegrityTag.clone();
	}

	@Override
	public int encodedLength() {
		return 1 + 4
			+ 1 + destinationConnectionId.length()
			+ 1 + sourceConnectionId.length()
			+ retryToken.length
			+ INTEGRITY_TAG_LENGTH;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) 0xF0);
		buf.writeInt((int) version);
		buf.writeByte((byte) destinationConnectionId.length());
		buf.put(destinationConnectionId.bytes());
		buf.writeByte((byte) sourceConnectionId.length());
		buf.put(sourceConnectionId.bytes());
		buf.put(retryToken);
		buf.put(retryIntegrityTag);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof RetryPacket other)) return false;
		return version == other.version
			&& destinationConnectionId.equals(other.destinationConnectionId)
			&& sourceConnectionId.equals(other.sourceConnectionId)
			&& Arrays.equals(retryToken, other.retryToken)
			&& Arrays.equals(retryIntegrityTag, other.retryIntegrityTag);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(version);
		result = 31 * result + destinationConnectionId.hashCode();
		result = 31 * result + sourceConnectionId.hashCode();
		result = 31 * result + Arrays.hashCode(retryToken);
		result = 31 * result + Arrays.hashCode(retryIntegrityTag);
		return result;
	}
}
