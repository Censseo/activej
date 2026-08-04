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
 * CANCEL_PUSH frame (RFC 9114 §7.2.3): a single Push ID varint.
 * <p>
 * Structurally always decodable, but semantically always an error in this implementation —
 * nothing is ever promised, since server push is refused entirely (FR-040). That rejection is a
 * connection-layer decision (US8), not this codec's.
 */
public final class CancelPushFrame extends Http3Frame {
	public static final long TYPE = 0x03;

	public final long pushId;

	public CancelPushFrame(long pushId) {
		this.pushId = pushId;
	}

	@Override
	public long type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(pushIdPayloadLength()) + pushIdPayloadLength();
	}

	private int pushIdPayloadLength() {
		return QuicVarInts.encodedLength(pushId);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, pushIdPayloadLength());
		QuicVarInts.write(buf, pushId);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CancelPushFrame other)) return false;
		return pushId == other.pushId;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(pushId);
	}

	@Override
	public String toString() {
		return "CancelPushFrame{pushId=" + pushId + '}';
	}
}
