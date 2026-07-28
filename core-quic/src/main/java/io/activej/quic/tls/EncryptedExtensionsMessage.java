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

import java.util.List;

/**
 * The {@code EncryptedExtensions} message (RFC 8446 §4.3.1): the first handshake-level message
 * of the server flight. In the QUIC profile it carries the server's
 * {@code quic_transport_parameters} (mandatory, RFC 9001 §8.2) and the selected ALPN.
 */
public final class EncryptedExtensionsMessage extends TlsHandshakeMessage {
	public static final int TYPE = 8;

	public final List<TlsExtension> extensions;

	public EncryptedExtensionsMessage(List<TlsExtension> extensions) {
		this.extensions = List.copyOf(extensions);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + TlsExtensions.encodedListLength(extensions);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		TlsExtensions.writeList(buf, extensions);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof EncryptedExtensionsMessage other)) return false;
		return extensions.equals(other.extensions);
	}

	@Override
	public int hashCode() {
		return extensions.hashCode();
	}
}
