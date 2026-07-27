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
 * MAX_STREAMS frame (RFC 9000 §19.11): {@code 0x12} for bidirectional streams,
 * {@code 0x13} for unidirectional. {@link #maximum}'s RFC-mandated 2^60 cap is a protocol-semantic
 * bound this codec deliberately does not enforce — see {@link QuicFrame}'s scope policy.
 */
public final class MaxStreamsFrame extends QuicFrame {
	public static final int TYPE_BIDIRECTIONAL = 0x12;
	public static final int TYPE_UNIDIRECTIONAL = 0x13;

	public final long maximum;
	public final QuicStreamLimitType type;

	public MaxStreamsFrame(long maximum, QuicStreamLimitType type) {
		this.maximum = maximum;
		this.type = type;
	}

	private int type() {
		return type == QuicStreamLimitType.BIDIRECTIONAL ? TYPE_BIDIRECTIONAL : TYPE_UNIDIRECTIONAL;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(type()) + QuicVarInts.encodedLength(maximum);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, type());
		QuicVarInts.write(buf, maximum);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MaxStreamsFrame other)) return false;
		return maximum == other.maximum && type == other.type;
	}

	@Override
	public int hashCode() {
		return Objects.hash(maximum, type);
	}
}
