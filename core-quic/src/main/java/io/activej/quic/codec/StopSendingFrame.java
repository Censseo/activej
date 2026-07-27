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
 * STOP_SENDING frame (RFC 9000 §19.5).
 */
public final class StopSendingFrame extends QuicFrame {
	public static final int TYPE = 0x05;

	public final long streamId;
	public final long appErrorCode;

	public StopSendingFrame(long streamId, long appErrorCode) {
		this.streamId = streamId;
		this.appErrorCode = appErrorCode;
	}

	@Override
	public int encodedLength() {
		return QuicVarInts.encodedLength(TYPE)
			+ QuicVarInts.encodedLength(streamId)
			+ QuicVarInts.encodedLength(appErrorCode);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, streamId);
		QuicVarInts.write(buf, appErrorCode);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof StopSendingFrame other)) return false;
		return streamId == other.streamId && appErrorCode == other.appErrorCode;
	}

	@Override
	public int hashCode() {
		return Objects.hash(streamId, appErrorCode);
	}
}
