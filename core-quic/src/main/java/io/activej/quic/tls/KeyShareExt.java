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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code key_share} extension (RFC 8446 §4.2.8), in both wire forms: the ClientHello
 * {@code client_shares} list ({@link #clientShares} set, {@link #selectedShare} {@code null})
 * and the ServerHello selected share ({@link #selectedShare} set, {@link #clientShares}
 * {@code null}).
 * <p>
 * Key-exchange bytes are length-checked at parse time for known groups (X25519 = 32 bytes,
 * secp256r1 = 65-byte uncompressed point) so a wrong-length share fails with a decode error
 * before any cryptography runs. Shares for unknown/GREASE groups are parsed and tolerated,
 * never selected (RFC 8701).
 */
public final class KeyShareExt extends TlsExtension {
	public static final int TYPE = 0x0033;

	/** ClientHello form: the offered shares, or {@code null} in the ServerHello form. */
	public final @Nullable List<KeyShareEntry> clientShares;

	/** ServerHello form: the selected share, or {@code null} in the ClientHello form. */
	public final @Nullable KeyShareEntry selectedShare;

	private KeyShareExt(@Nullable List<KeyShareEntry> clientShares, @Nullable KeyShareEntry selectedShare) {
		this.clientShares = clientShares == null ? null : List.copyOf(clientShares);
		this.selectedShare = selectedShare;
	}

	/** ClientHello form (RFC 8446 §4.2.8). */
	public static KeyShareExt ofClientShares(List<KeyShareEntry> clientShares) {
		if (clientShares.isEmpty()) {
			throw new IllegalArgumentException("key_share must offer at least one share");
		}
		return new KeyShareExt(clientShares, null);
	}

	/** ServerHello form (RFC 8446 §4.2.8). */
	public static KeyShareExt ofSelectedShare(KeyShareEntry selectedShare) {
		return new KeyShareExt(null, selectedShare);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		if (selectedShare != null) {
			return 4 + selectedShare.encodedLength();
		}
		int entriesLength = 0;
		for (KeyShareEntry share : clientShares) {
			entriesLength += share.encodedLength();
		}
		return 4 + 2 + entriesLength;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		if (selectedShare != null) {
			selectedShare.writeTo(buf);
			return;
		}
		TlsExtensions.writeShort(buf, encodedLength() - 4 - 2);
		for (KeyShareEntry share : clientShares) {
			share.writeTo(buf);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof KeyShareExt other)) return false;
		return Objects.equals(clientShares, other.clientShares) && Objects.equals(selectedShare, other.selectedShare);
	}

	@Override
	public int hashCode() {
		return Objects.hash(clientShares, selectedShare);
	}

	/**
	 * One {@code KeyShareEntry} (RFC 8446 §4.2.8): a named group plus its public key-exchange
	 * bytes. The group is carried as a raw codepoint so that unknown/GREASE groups round-trip;
	 * {@link #namedGroup()} resolves it when known.
	 */
	public static final class KeyShareEntry {
		public final int groupCode;
		public final byte[] keyExchange;

		public KeyShareEntry(int groupCode, byte[] keyExchange) {
			this.groupCode = groupCode;
			this.keyExchange = keyExchange.clone();
		}

		/** The group as a {@link NamedGroup}, or {@code null} for an unknown/GREASE codepoint. */
		public @Nullable NamedGroup namedGroup() {
			return NamedGroup.of(groupCode);
		}

		/** Defensive copy of {@link #keyExchange}. */
		public byte[] keyExchange() {
			return keyExchange.clone();
		}

		int encodedLength() {
			return 4 + keyExchange.length;
		}

		void writeTo(ByteBuf buf) {
			TlsExtensions.writeShort(buf, groupCode);
			TlsExtensions.writeShort(buf, keyExchange.length);
			buf.put(keyExchange);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof KeyShareEntry other)) return false;
			return groupCode == other.groupCode && Arrays.equals(keyExchange, other.keyExchange);
		}

		@Override
		public int hashCode() {
			return 31 * groupCode + Arrays.hashCode(keyExchange);
		}
	}
}
