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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;

import java.util.Arrays;

/**
 * The {@code Finished} message (RFC 8446 §4.4.4): the {@code verify_data} HMAC over the
 * transcript hash, keyed by the direction's Finished key. Its length is the negotiated suite's
 * hash length (32 or 48 bytes); verification MUST be constant-time
 * ({@code MessageDigest.isEqual}) — performed by the engines (FR-016), not the codec.
 */
public final class FinishedMessage extends TlsHandshakeMessage {
	public static final int TYPE = 20;

	public final byte[] verifyData;

	public FinishedMessage(byte[] verifyData) {
		this.verifyData = verifyData.clone();
	}

	/** Defensive copy of {@link #verifyData}. */
	public byte[] verifyData() {
		return verifyData.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + verifyData.length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, verifyData.length);
		buf.put(verifyData);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof FinishedMessage other)) return false;
		return Arrays.equals(verifyData, other.verifyData);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(verifyData);
	}
}
