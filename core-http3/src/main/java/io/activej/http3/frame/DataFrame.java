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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.recycle.Recyclable;
import io.activej.quic.codec.QuicVarInts;

/**
 * DATA frame (RFC 9114 §7.2.1): opaque request/response body bytes. Owns a retained slice of
 * {@link #data} — the caller must {@link #recycle()} it.
 * <p>
 * A zero-length DATA frame is legal (RFC 9114 does not forbid it) and carries no meaning of its
 * own — in particular it is <b>not</b> an end-of-body signal, which is a QUIC stream FIN, not a
 * frame boundary.
 */
public final class DataFrame extends Http3Frame implements Recyclable {
	public static final long TYPE = 0x00;

	public final ByteBuf data;

	public DataFrame(ByteBuf data) {
		this.data = data;
	}

	@Override
	public long type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		int len = data.readRemaining();
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(len) + len;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = data.readRemaining();
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, len);
		buf.put(data.array(), data.head(), len);
	}

	@Override
	public void recycle() {
		data.recycle();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DataFrame other)) return false;
		return ByteBufContents.equals(data, other.data);
	}

	@Override
	public int hashCode() {
		return ByteBufContents.hashCode(data);
	}

	@Override
	public String toString() {
		return "DataFrame{" + data.readRemaining() + " bytes}";
	}
}
