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
import io.activej.quic.QuicConnectionId;

/**
 * Base of the QUIC packet hierarchy (RFC 9000 §17). Every subtype is the <b>unprotected</b>
 * wire representation: a packet number field, where present, holds the raw truncated value
 * exactly as it appears on (or is meant to appear on) the wire — reconstructing it into a full
 * packet number via {@link PacketNumbers#reconstruct} is the caller's job, since that requires
 * number-space context this codec does not keep. Applying or removing AEAD packet protection and
 * header protection is a separate concern (RFC 9001), layered outside this class.
 */
public abstract class QuicPacket {

	QuicPacket() {
	}

	/** The connection ID the packet is addressed to — present in every QUIC packet type. */
	public abstract QuicConnectionId destinationConnectionId();

	/** Exact number of bytes {@link #writeTo} will write. */
	public abstract int encodedLength();

	/** Writes this packet's unprotected wire form to {@code buf}. */
	public abstract void writeTo(ByteBuf buf);
}
