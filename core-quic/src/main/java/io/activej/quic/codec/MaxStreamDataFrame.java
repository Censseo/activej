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
 * MAX_STREAM_DATA frame (RFC 9000 §19.10).
 */
public final class MaxStreamDataFrame extends QuicFrame {
	public static final int TYPE = 0x11;

	public final long streamId;
	public final long maximum;

	public MaxStreamDataFrame(long streamId, long maximum) {
		this.streamId = streamId;
		this.maximum = maximum;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE)
			+ QuicVarInts.encodedLength(streamId)
			+ QuicVarInts.encodedLength(maximum);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, streamId);
		QuicVarInts.write(buf, maximum);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MaxStreamDataFrame other)) return false;
		return streamId == other.streamId && maximum == other.maximum;
	}

	@Override
	public int hashCode() {
		return Objects.hash(streamId, maximum);
	}
}
