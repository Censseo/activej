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

/**
 * Base of the HTTP/3 frame hierarchy (RFC 9114 §7.2). Every subtype is immutable and knows how
 * to write itself, including its own leading Type/Length varints — the same *self-sizing wire
 * value* pattern as {@code core-quic}'s {@code QuicFrame}.
 * <p>
 * <b>Scope policy</b> (research Decision 13, mirroring {@code core-quic}'s ADR-013): this codec
 * enforces wire-<i>syntax</i> only. Whether a frame of this type is <i>allowed</i> on the stream
 * it arrived on, in the state that stream is in, is not this class's decision — that is
 * {@link Http3FrameSequence} and the reactive connection layer above it.
 */
public abstract class Http3Frame {

	Http3Frame() {
	}

	/**
	 * The RFC 9114 §7.2 frame type — a QUIC varint (RFC 9000 §16).
	 */
	public abstract long type();

	/**
	 * Exact number of bytes {@link #writeTo} will write, including the Type and Length varints.
	 */
	public abstract int encodedLength();

	/**
	 * Writes this frame — Type, Length, then payload — to {@code buf}.
	 */
	public abstract void writeTo(ByteBuf buf);
}
