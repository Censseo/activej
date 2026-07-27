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

import java.util.Arrays;

/**
 * PATH_RESPONSE frame (RFC 9000 §19.18). {@link #data} is always exactly 8 bytes.
 */
public final class PathResponseFrame extends QuicFrame {
	public static final int TYPE = 0x1b;
	public static final int DATA_LENGTH = 8;

	public final byte[] data;

	public PathResponseFrame(byte[] data) {
		if (data.length != DATA_LENGTH) {
			throw new IllegalArgumentException("PATH_RESPONSE data must be " + DATA_LENGTH + " bytes: " + data.length);
		}
		this.data = data.clone();
	}

	/** Defensive copy of the 8-byte echoed challenge payload. */
	public byte[] data() {
		return data.clone();
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE) + DATA_LENGTH;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		buf.put(data);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof PathResponseFrame other)) return false;
		return Arrays.equals(data, other.data);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(data);
	}
}
