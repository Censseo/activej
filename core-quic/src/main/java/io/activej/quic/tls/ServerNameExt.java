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
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The {@code server_name} extension (RFC 6066 §3, as used by RFC 8446 §4.2). Only the
 * {@code host_name} name type is interpreted; other name types are parsed and ignored.
 * A {@code null} {@link #hostName} encodes the empty-body form — the form a server sends in
 * EncryptedExtensions to acknowledge SNI (RFC 6066 §3), and the only legal form there. A
 * client connecting by bare IP address omits the extension entirely (FR-019).
 */
public final class ServerNameExt extends TlsExtension {
	public static final int TYPE = 0x0000;

	/** Name type {@code host_name} (RFC 6066 §3). */
	public static final int HOST_NAME_TYPE = 0;

	public final @Nullable String hostName;

	public ServerNameExt(@Nullable String hostName) {
		if (hostName != null && hostName.getBytes(StandardCharsets.US_ASCII).length > 65535) {
			throw new IllegalArgumentException("SNI host name too long");
		}
		this.hostName = hostName;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		int bodyLength = hostName == null ? 0 : 2 + 3 + hostName.getBytes(StandardCharsets.US_ASCII).length;
		return 4 + bodyLength;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		if (hostName != null) {
			byte[] bytes = hostName.getBytes(StandardCharsets.US_ASCII);
			TlsExtensions.writeShort(buf, 3 + bytes.length);
			buf.writeByte((byte) HOST_NAME_TYPE);
			TlsExtensions.writeShort(buf, bytes.length);
			buf.put(bytes);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ServerNameExt other)) return false;
		return Objects.equals(hostName, other.hostName);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(hostName);
	}
}
