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

/**
 * A TLS 1.3 handshake message (RFC 8446 §4), restricted to the QUIC profile (RFC 9001 §8).
 * Self-sizing wire value: {@link #encodedLength()} includes the 1-byte type and 3-byte length
 * header and always agrees with {@link #writeTo}.
 * <p>
 * The transcript hash (RFC 8446 §4.4.1) is computed over these exact bytes, header included.
 * Read/write dispatch lives in {@link TlsMessages}.
 */
public abstract class TlsHandshakeMessage {

	/** The 1-byte handshake message type codepoint on the wire (RFC 8446 §4). */
	public abstract int type();

	/** Exact encoded length, including the 1-byte type and 3-byte length header. */
	public abstract int encodedLength();

	/** Writes this message, including the 1-byte type and 3-byte length header. */
	public abstract void writeTo(ByteBuf buf);
}
