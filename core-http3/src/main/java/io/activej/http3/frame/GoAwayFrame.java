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
import io.activej.quic.codec.QuicVarInts;

/**
 * GOAWAY frame (RFC 9114 §7.2.6): a single varint — a client-initiated bidirectional stream ID
 * when sent by a server, a push ID when sent by a client.
 * <p>
 * Whether successive identifiers are non-increasing, and which streams complete versus fail
 * retryably, is the connection layer's decision (RFC 9114 §5.2, US7) — this type only carries the
 * value.
 */
public final class GoAwayFrame extends Http3Frame {
	public static final long TYPE = 0x07;

	public final long id;

	public GoAwayFrame(long id) {
		this.id = id;
	}

	@Override
	public long type() {
		return TYPE;
	}

	private int idLength() {
		return QuicVarInts.encodedLength(id);
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(idLength()) + idLength();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, idLength());
		QuicVarInts.write(buf, id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof GoAwayFrame other)) return false;
		return id == other.id;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}

	@Override
	public String toString() {
		return "GoAwayFrame{id=" + id + '}';
	}
}
