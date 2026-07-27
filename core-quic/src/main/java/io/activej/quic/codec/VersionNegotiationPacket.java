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
 * Version Negotiation packet (RFC 9000 §17.2.1), identified on the wire by a zero version field.
 * <p>
 * This type doubles as the classification result for a long-header packet whose version is
 * neither {@code 0} nor the one version this codec understands ({@link QuicPackets#SUPPORTED_VERSION})
 * — {@link #version} then holds that unrecognized value and {@link #supportedVersions} is empty,
 * since a long header's Destination/Source Connection ID fields are the only part of the wire
 * format that RFC 8999 guarantees stays put across QUIC versions; nothing after them is parsed.
 */
public final class VersionNegotiationPacket extends QuicLongHeaderPacket {
	public final int[] supportedVersions;

	public VersionNegotiationPacket(
		long version, QuicConnectionId destinationConnectionId, QuicConnectionId sourceConnectionId,
		int[] supportedVersions
	) {
		super(version, destinationConnectionId, sourceConnectionId);
		this.supportedVersions = supportedVersions.clone();
	}

	public static VersionNegotiationPacket of(QuicConnectionId destinationConnectionId, QuicConnectionId sourceConnectionId, int[] supportedVersions) {
		return new VersionNegotiationPacket(0, destinationConnectionId, sourceConnectionId, supportedVersions);
	}

	/** {@code true} for a genuine Version Negotiation packet; {@code false} for the "unrecognized version" classification (see class javadoc), where {@link #supportedVersions} is always empty. */
	public boolean isVersionNegotiation() {
		return version == 0;
	}

	/** Defensive copy of the versions the server claims to support. */
	public int[] supportedVersions() {
		return supportedVersions.clone();
	}

	@Override
	public int encodedLength() {
		return 1 + 4
			+ 1 + destinationConnectionId.length()
			+ 1 + sourceConnectionId.length()
			+ 4 * supportedVersions.length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) 0x80);
		buf.writeInt((int) version);
		buf.writeByte((byte) destinationConnectionId.length());
		buf.put(destinationConnectionId.bytes());
		buf.writeByte((byte) sourceConnectionId.length());
		buf.put(sourceConnectionId.bytes());
		for (int supportedVersion : supportedVersions) {
			buf.writeInt(supportedVersion);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof VersionNegotiationPacket other)) return false;
		return version == other.version
			&& destinationConnectionId.equals(other.destinationConnectionId)
			&& sourceConnectionId.equals(other.sourceConnectionId)
			&& Arrays.equals(supportedVersions, other.supportedVersions);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(version);
		result = 31 * result + destinationConnectionId.hashCode();
		result = 31 * result + sourceConnectionId.hashCode();
		result = 31 * result + Arrays.hashCode(supportedVersions);
		return result;
	}
}
