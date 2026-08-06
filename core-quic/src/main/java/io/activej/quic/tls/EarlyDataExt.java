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

/**
 * The {@code early_data} extension (RFC 8446 §4.2.10), in both of its forms: an <b>empty</b> body in
 * a ClientHello (the client offers early data) and in EncryptedExtensions (the server accepts it),
 * and a {@code uint32 max_early_data_size} in a NewSessionTicket.
 * <p>
 * Omitting the extension from EncryptedExtensions is how a server <b>refuses</b> early data
 * (spec FR-048); that is not a handshake failure and there is no negative form to encode.
 * <p>
 * In QUIC the only legal {@code max_early_data_size} is {@code 0xffffffff} — the transport bounds
 * early data through flow control, not through this field — and a client must treat any other value
 * as a connection error (RFC 9001 §4.6.1). The check belongs to the engine; this type only carries
 * the value.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.10">RFC 8446 §4.2.10</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1</a>
 */
public final class EarlyDataExt extends TlsExtension {
	public static final int TYPE = 42; // 0x002a

	/** The only {@code max_early_data_size} a QUIC server may send (RFC 9001 §4.6.1). */
	public static final long QUIC_MAX_EARLY_DATA_SIZE = 0xFFFFFFFFL;

	private static final EarlyDataExt EMPTY = new EarlyDataExt(-1);

	/** The advertised maximum, or {@code -1} in the empty (ClientHello / EncryptedExtensions) form. */
	public final long maxEarlyDataSize;

	private EarlyDataExt(long maxEarlyDataSize) {
		this.maxEarlyDataSize = maxEarlyDataSize;
	}

	/** The empty form, carried by a ClientHello or by EncryptedExtensions. */
	public static EarlyDataExt empty() {
		return EMPTY;
	}

	/** The NewSessionTicket form, carrying a {@code uint32 max_early_data_size}. */
	public static EarlyDataExt ofMaxEarlyDataSize(long maxEarlyDataSize) {
		if ((maxEarlyDataSize & ~0xFFFFFFFFL) != 0) {
			throw new IllegalArgumentException("max_early_data_size is a uint32: " + maxEarlyDataSize);
		}
		return new EarlyDataExt(maxEarlyDataSize);
	}

	/** Whether this is the NewSessionTicket form; {@code false} for the empty form. */
	public boolean hasMaxEarlyDataSize() {
		return maxEarlyDataSize >= 0;
	}

	/** Same value as {@link #maxEarlyDataSize}: {@code -1} in the empty form, a uint32 otherwise. */
	public long maxEarlyDataSize() {
		return maxEarlyDataSize;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return hasMaxEarlyDataSize() ? 4 + 4 : 4;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		if (hasMaxEarlyDataSize()) {
			TlsExtensions.writeShort(buf, (int) (maxEarlyDataSize >>> 16));
			TlsExtensions.writeShort(buf, (int) maxEarlyDataSize);
		}
	}

	@Override
	public boolean equals(Object o) {
		return this == o || o instanceof EarlyDataExt other && maxEarlyDataSize == other.maxEarlyDataSize;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(maxEarlyDataSize);
	}

	@Override
	public String toString() {
		return hasMaxEarlyDataSize() ? "EarlyDataExt[max=" + maxEarlyDataSize + ']' : "EarlyDataExt[]";
	}
}
