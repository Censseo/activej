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
 * Base of the QUIC frame hierarchy (RFC 9000 §19). Every subtype is immutable and knows how to
 * write itself, including its own leading type-code varint.
 * <p>
 * <b>Scope policy</b>: this codec enforces wire-<i>syntax</i> bounds only (declared lengths and
 * counts checked against remaining bytes, per FR-014) — it never enforces RFC-specified
 * protocol-<i>semantic</i> value ranges, such as MAX_STREAMS/STREAMS_BLOCKED's 2^60 cap (RFC 9000
 * §19.11/§19.14) or NEW_CONNECTION_ID's "Retire Prior To ≤ Sequence Number" invariant (§19.15).
 * Values outside those ranges still round-trip; rejecting them (a connection error, not a parse
 * error) is the connection layer's job.
 */
public abstract class QuicFrame {

	QuicFrame() {
	}

	/**
	 * Exact number of bytes {@link #writeTo} will write, including the type code.
	 */
	public abstract int encodedLength();

	/**
	 * Writes this frame, including its type code, to {@code buf}.
	 */
	public abstract void writeTo(ByteBuf buf);
}
