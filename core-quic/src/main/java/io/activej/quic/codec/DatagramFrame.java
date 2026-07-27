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
 * DATAGRAM frame (RFC 9221), type codes {@code 0x30} (no Length field, data extends to the end
 * of the packet) and {@code 0x31} (explicit Length field). Owns a retained slice of
 * {@link #payload} — the caller must {@link #recycle()} it after use. {@link #writeTo} always
 * emits the {@code 0x31} form.
 */
public final class DatagramFrame extends QuicFrame implements Recyclable {
	public static final int TYPE_WITHOUT_LENGTH = 0x30;
	public static final int TYPE_WITH_LENGTH = 0x31;

	public final ByteBuf payload;

	public DatagramFrame(ByteBuf payload) {
		this.payload = payload;
	}

	@Override
	public int encodedLength() {
		int len = payload.readRemaining();
		return QuicVarInts.encodedLength(TYPE_WITH_LENGTH) + QuicVarInts.encodedLength(len) + len;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = payload.readRemaining();
		QuicVarInts.write(buf, TYPE_WITH_LENGTH);
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
		if (!(o instanceof DatagramFrame other)) return false;
		return ByteBufContents.equals(payload, other.payload);
	}

	@Override
	public int hashCode() {
		return ByteBufContents.hashCode(payload);
	}
}
