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
 * CONNECTION_CLOSE frame (RFC 9000 §19.19): {@code 0x1c} closes at the QUIC transport layer and
 * carries {@link #triggerFrameType}; {@code 0x1d} closes at the application layer and has no
 * trigger frame type. {@link #reasonPhrase} is raw UTF-8 bytes, not necessarily valid UTF-8
 * (RFC 9000 explicitly allows an endpoint to send an invalid sequence).
 */
public final class ConnectionCloseFrame extends QuicFrame {
	public static final int TYPE_TRANSPORT = 0x1c;
	public static final int TYPE_APPLICATION = 0x1d;

	public final boolean isApplication;
	public final long errorCode;
	public final long triggerFrameType;
	public final byte[] reasonPhrase;

	private ConnectionCloseFrame(boolean isApplication, long errorCode, long triggerFrameType, byte[] reasonPhrase) {
		this.isApplication = isApplication;
		this.errorCode = errorCode;
		this.triggerFrameType = triggerFrameType;
		this.reasonPhrase = reasonPhrase.clone();
	}

	public static ConnectionCloseFrame transport(long errorCode, long triggerFrameType, byte[] reasonPhrase) {
		return new ConnectionCloseFrame(false, errorCode, triggerFrameType, reasonPhrase);
	}

	public static ConnectionCloseFrame application(long errorCode, byte[] reasonPhrase) {
		return new ConnectionCloseFrame(true, errorCode, 0, reasonPhrase);
	}

	/** Defensive copy of the raw (not necessarily valid UTF-8) reason phrase bytes. */
	public byte[] reasonPhrase() {
		return reasonPhrase.clone();
	}

	@Override
	public int encodedLength() {
		int length = QuicVarInts.encodedLength(isApplication ? TYPE_APPLICATION : TYPE_TRANSPORT)
			+ QuicVarInts.encodedLength(errorCode);
		if (!isApplication) {
			length += QuicVarInts.encodedLength(triggerFrameType);
		}
		length += QuicVarInts.encodedLength(reasonPhrase.length) + reasonPhrase.length;
		return length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, isApplication ? TYPE_APPLICATION : TYPE_TRANSPORT);
		QuicVarInts.write(buf, errorCode);
		if (!isApplication) {
			QuicVarInts.write(buf, triggerFrameType);
		}
		QuicVarInts.write(buf, reasonPhrase.length);
		buf.put(reasonPhrase);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ConnectionCloseFrame other)) return false;
		return isApplication == other.isApplication && errorCode == other.errorCode
			&& triggerFrameType == other.triggerFrameType && Arrays.equals(reasonPhrase, other.reasonPhrase);
	}

	@Override
	public int hashCode() {
		int result = Boolean.hashCode(isApplication);
		result = 31 * result + Long.hashCode(errorCode);
		result = 31 * result + Long.hashCode(triggerFrameType);
		result = 31 * result + Arrays.hashCode(reasonPhrase);
		return result;
	}
}
