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

import java.util.Objects;

/**
 * STREAMS_BLOCKED frame (RFC 9000 §19.14): {@code 0x16} for bidirectional streams,
 * {@code 0x17} for unidirectional.
 */
public final class StreamsBlockedFrame extends QuicFrame {
	public static final int TYPE_BIDIRECTIONAL = 0x16;
	public static final int TYPE_UNIDIRECTIONAL = 0x17;

	public final long limit;
	public final QuicStreamLimitType type;

	public StreamsBlockedFrame(long limit, QuicStreamLimitType type) {
		this.limit = limit;
		this.type = type;
	}

	private int type() {
		return type == QuicStreamLimitType.BIDIRECTIONAL ? TYPE_BIDIRECTIONAL : TYPE_UNIDIRECTIONAL;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(type()) + QuicVarInts.encodedLength(limit);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, type());
		QuicVarInts.write(buf, limit);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof StreamsBlockedFrame other)) return false;
		return limit == other.limit && type == other.type;
	}

	@Override
	public int hashCode() {
		return Objects.hash(limit, type);
	}
}
