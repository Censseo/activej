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
 * RETIRE_CONNECTION_ID frame (RFC 9000 §19.16).
 */
public final class RetireConnectionIdFrame extends QuicFrame {
	public static final int TYPE = 0x19;

	public final long sequenceNumber;

	public RetireConnectionIdFrame(long sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(sequenceNumber);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, sequenceNumber);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof RetireConnectionIdFrame other)) return false;
		return sequenceNumber == other.sequenceNumber;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(sequenceNumber);
	}
}
