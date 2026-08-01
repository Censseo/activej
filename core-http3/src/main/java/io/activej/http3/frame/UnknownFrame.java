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
 * Any frame type not otherwise defined by this codec (RFC 9114 §9's GREASE tolerance, and every
 * currently-unassigned type, including {@code PUSH_PROMISE} — this codec does not interpret it
 * structurally; whether it is legal is a connection-layer decision, US8).
 * <p>
 * Its payload is discarded as it is read, never buffered — {@link #declaredLength} records how
 * many bytes were skipped, but this value carries none of them. Consequently {@link #writeTo}
 * reproduces only the Type/Length header, not a byte-identical re-encoding of whatever was
 * originally received; {@link #encodedLength()} is exact for that header, which is all this type
 * ever writes.
 */
public final class UnknownFrame extends Http3Frame {
	private final long type;
	public final long declaredLength;

	public UnknownFrame(long type, long declaredLength) {
		this.type = type;
		this.declaredLength = declaredLength;
	}

	@Override
	public long type() {
		return type;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(type) + QuicVarInts.encodedLength(declaredLength);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, type);
		QuicVarInts.write(buf, declaredLength);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof UnknownFrame other)) return false;
		return type == other.type && declaredLength == other.declaredLength;
	}

	@Override
	public int hashCode() {
		return 31 * Long.hashCode(type) + Long.hashCode(declaredLength);
	}

	@Override
	public String toString() {
		return "UnknownFrame{type=0x" + Long.toHexString(type) + ", declaredLength=" + declaredLength + '}';
	}
}
