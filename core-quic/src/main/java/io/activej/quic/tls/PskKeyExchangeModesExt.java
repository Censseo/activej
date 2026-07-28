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

import java.util.Arrays;

/**
 * The {@code psk_key_exchange_modes} extension (RFC 8446 §4.2.9). Advertised by the client for
 * interop even though this feature never offers a PSK.
 */
public final class PskKeyExchangeModesExt extends TlsExtension {
	public static final int TYPE = 0x002d;

	/** {@code psk_dhe_ke} — PSK with (EC)DHE key establishment (RFC 8446 §4.2.9). */
	public static final int PSK_DHE_KE = 1;

	public final int[] modes;

	public PskKeyExchangeModesExt(int... modes) {
		if (modes.length == 0) {
			throw new IllegalArgumentException("psk_key_exchange_modes must not be empty");
		}
		this.modes = modes.clone();
	}

	/** Defensive copy of {@link #modes}. */
	public int[] modes() {
		return modes.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 1 + modes.length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, TYPE);
		TlsExtensions.writeShort(buf, encodedLength() - 4);
		buf.writeByte((byte) modes.length);
		for (int mode : modes) {
			buf.writeByte((byte) mode);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof PskKeyExchangeModesExt other)) return false;
		return Arrays.equals(modes, other.modes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(modes);
	}
}
