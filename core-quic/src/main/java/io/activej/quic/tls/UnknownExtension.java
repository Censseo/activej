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
 * An extension of an unknown or GREASE type (RFC 8446 §4.2, RFC 8701): parsed into its opaque
 * body bytes, tolerated, and never interpreted or echoed back by the engines. Re-serializes to
 * the identical bytes.
 */
public final class UnknownExtension extends TlsExtension {
	public final int typeCode;
	public final byte[] data;

	public UnknownExtension(int typeCode, byte[] data) {
		this.typeCode = typeCode;
		this.data = data.clone();
	}

	/** Defensive copy of {@link #data}. */
	public byte[] data() {
		return data.clone();
	}

	@Override
	public int type() {
		return typeCode;
	}

	@Override
	public int encodedLength() {
		return 4 + data.length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		TlsExtensions.writeShort(buf, typeCode);
		TlsExtensions.writeShort(buf, data.length);
		buf.put(data);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof UnknownExtension other)) return false;
		return typeCode == other.typeCode && Arrays.equals(data, other.data);
	}

	@Override
	public int hashCode() {
		return 31 * typeCode + Arrays.hashCode(data);
	}
}
