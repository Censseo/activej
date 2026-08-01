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
 * MAX_PUSH_ID frame (RFC 9114 §7.2.7): a single Push ID varint, client-to-server only. A server
 * receiving this frame is a connection error ({@code H3_FRAME_UNEXPECTED}) — a directionality
 * rule decided by the connection layer (US8), not this codec.
 */
public final class MaxPushIdFrame extends Http3Frame {
	public static final long TYPE = 0x0d;

	public final long pushId;

	public MaxPushIdFrame(long pushId) {
		this.pushId = pushId;
	}

	@Override
	public long type() {
		return TYPE;
	}

	private int pushIdLength() {
		return QuicVarInts.encodedLength(pushId);
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(pushIdLength()) + pushIdLength();
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, pushIdLength());
		QuicVarInts.write(buf, pushId);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MaxPushIdFrame other)) return false;
		return pushId == other.pushId;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(pushId);
	}

	@Override
	public String toString() {
		return "MaxPushIdFrame{pushId=" + pushId + '}';
	}
}
