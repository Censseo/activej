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
import io.activej.common.recycle.Recyclable;

/**
 * CRYPTO frame (RFC 9000 §19.6). Owns a retained slice of {@link #payload} — the caller must
 * {@link #recycle()} it (or recycle {@link #payload} directly) after use. {@link #writeTo} reads
 * {@link #payload} without consuming it, so the frame remains reusable.
 */
public final class CryptoFrame extends QuicFrame implements Recyclable {
	public static final int TYPE = 0x06;

	public final long offset;
	public final ByteBuf payload;

	public CryptoFrame(long offset, ByteBuf payload) {
		this.offset = offset;
		this.payload = payload;
	}

	@Override
	public int encodedLength() {
		int len = payload.readRemaining();
		return QuicVarInts.encodedLength(TYPE)
			+ QuicVarInts.encodedLength(offset)
			+ QuicVarInts.encodedLength(len)
			+ len;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = payload.readRemaining();
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, offset);
		QuicVarInts.write(buf, len);
		buf.put(payload.array(), payload.head(), len);
	}

	@Override
	public void recycle() {
		payload.recycle();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CryptoFrame other)) return false;
		return offset == other.offset && ByteBufContents.equals(payload, other.payload);
	}

	@Override
	public int hashCode() {
		return 31 * Long.hashCode(offset) + ByteBufContents.hashCode(payload);
	}
}
