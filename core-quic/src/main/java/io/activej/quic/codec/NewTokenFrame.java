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
 * NEW_TOKEN frame (RFC 9000 §19.7). Owns a retained slice of {@link #token} — the caller must
 * {@link #recycle()} it after use. A NEW_TOKEN frame's token MUST NOT be empty; that RFC
 * constraint is enforced by the decoder ({@link QuicFrames#read}), not by this constructor.
 */
public final class NewTokenFrame extends QuicFrame implements Recyclable {
	public static final int TYPE = 0x07;

	public final ByteBuf token;

	public NewTokenFrame(ByteBuf token) {
		this.token = token;
	}

	@Override
	public int encodedLength() {
		int len = token.readRemaining();
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(len) + len;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = token.readRemaining();
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, len);
		buf.put(token.array(), token.head(), len);
	}

	@Override
	public void recycle() {
		token.recycle();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof NewTokenFrame other)) return false;
		return ByteBufContents.equals(token, other.token);
	}

	@Override
	public int hashCode() {
		return ByteBufContents.hashCode(token);
	}
}
