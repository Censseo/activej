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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The {@code application_layer_protocol_negotiation} extension (RFC 7301, RFC 9001 §8.1).
 * {@code h3} is the only protocol negotiated by this feature (FR-012); other offered protocol
 * ids are parsed and ignored.
 */
public final class AlpnExt extends TlsExtension {
	public static final int TYPE = 0x0010;

	public final List<String> protocols;

	public AlpnExt(List<String> protocols) {
		if (protocols.isEmpty()) {
			throw new IllegalArgumentException("ALPN protocol list must not be empty");
		}
		for (String protocol : protocols) {
			byte[] bytes = protocol.getBytes(StandardCharsets.US_ASCII);
			if (bytes.length == 0 || bytes.length > 255) {
				throw new IllegalArgumentException("ALPN protocol id must be 1..255 bytes: " + bytes.length);
			}
		}
		this.protocols = List.copyOf(protocols);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		int listLength = 0;
		for (String protocol : protocols) {
			listLength += 1 + protocol.getBytes(StandardCharsets.US_ASCII).length;
		}
		return 4 + 2 + listLength;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, encodedLength() - 4 - 2);
		for (String protocol : protocols) {
			byte[] bytes = protocol.getBytes(StandardCharsets.US_ASCII);
			buf.writeByte((byte) bytes.length);
			buf.put(bytes);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof AlpnExt other)) return false;
		return protocols.equals(other.protocols);
	}

	@Override
	public int hashCode() {
		return protocols.hashCode();
	}
}
