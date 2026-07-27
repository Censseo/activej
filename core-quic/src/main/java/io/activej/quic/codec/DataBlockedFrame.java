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

/**
 * DATA_BLOCKED frame (RFC 9000 §19.12).
 */
public final class DataBlockedFrame extends QuicFrame {
	public static final int TYPE = 0x14;

	public final long limit;

	public DataBlockedFrame(long limit) {
		this.limit = limit;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(limit);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, limit);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DataBlockedFrame other)) return false;
		return limit == other.limit;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(limit);
	}
}
