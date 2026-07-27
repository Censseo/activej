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
 * STREAM frame (RFC 9000 §19.8), type codes {@code 0x08}-{@code 0x0f} encoding the OFF/LEN/FIN
 * bits. Owns a retained slice of {@link #data} — the caller must {@link #recycle()} it after use.
 * <p>
 * {@link #writeTo} always emits an explicit Length field (the LEN bit) and omits the Offset
 * field only when {@link #offset} is zero. Decoding ({@link QuicFrames#read}) accepts all eight
 * bit combinations, including a LEN-less frame whose data extends to the end of the input.
 */
public final class StreamFrame extends QuicFrame implements Recyclable {
	public static final int TYPE_BASE = 0x08;
	public static final int OFF_BIT = 0x04;
	public static final int LEN_BIT = 0x02;
	public static final int FIN_BIT = 0x01;

	public final long streamId;
	public final long offset;
	public final boolean fin;
	public final ByteBuf data;

	public StreamFrame(long streamId, long offset, boolean fin, ByteBuf data) {
		this.streamId = streamId;
		this.offset = offset;
		this.fin = fin;
		this.data = data;
	}

	@Override
	public int encodedLength() {
		int len = data.readRemaining();
		int length = QuicVarInts.encodedLength(TYPE_BASE | LEN_BIT) + QuicVarInts.encodedLength(streamId);
		if (offset != 0) {
			length += QuicVarInts.encodedLength(offset);
		}
		length += QuicVarInts.encodedLength(len) + len;
		return length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = data.readRemaining();
		int type = TYPE_BASE | LEN_BIT | (offset != 0 ? OFF_BIT : 0) | (fin ? FIN_BIT : 0);
		QuicVarInts.write(buf, type);
		QuicVarInts.write(buf, streamId);
		if (offset != 0) {
			QuicVarInts.write(buf, offset);
		}
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
		if (!(o instanceof StreamFrame other)) return false;
		return streamId == other.streamId && offset == other.offset && fin == other.fin
			&& ByteBufContents.equals(data, other.data);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(streamId);
		result = 31 * result + Long.hashCode(offset);
		result = 31 * result + Boolean.hashCode(fin);
		result = 31 * result + ByteBufContents.hashCode(data);
		return result;
	}
}
