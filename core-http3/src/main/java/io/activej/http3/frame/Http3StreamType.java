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

/**
 * The unidirectional stream types of RFC 9114 §6.2, identified by the varint every such stream
 * begins with.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-6.2">RFC 9114 §6.2 — Unidirectional
 * Streams</a>
 */
public enum Http3StreamType {
	/** Exactly one per peer; the first frame on it must be SETTINGS. */
	CONTROL(0x00),
	/** Never accepted in this implementation — the advertised push limit is 0. */
	PUSH(0x01),
	/** Accepted; only a peer's {@code Set Dynamic Table Capacity 0} instruction is legal on it. */
	QPACK_ENCODER(0x02),
	/** Accepted; any instruction on it is illegal, since nothing is ever inserted. */
	QPACK_DECODER(0x03),
	/** Any type code not listed above — abandoned via {@code STOP_SENDING}, nothing buffered. */
	UNKNOWN(-1);

	private final long code;

	Http3StreamType(long code) {
		this.code = code;
	}

	/**
	 * The wire code for this type. Meaningless for {@link #UNKNOWN}, which stands for every code
	 * not otherwise listed here — the caller already has the actual code it read off the wire.
	 */
	public long code() {
		return code;
	}

	/**
	 * Classifies a stream-type code read off the wire. Never throws: an unrecognized code — this
	 * includes GREASE values (RFC 9114 §9) — classifies as {@link #UNKNOWN} rather than being
	 * rejected, per RFC 9114 §6.2's abandon-not-fail rule.
	 */
	public static Http3StreamType classify(long code) {
		if (code == CONTROL.code) return CONTROL;
		if (code == PUSH.code) return PUSH;
		if (code == QPACK_ENCODER.code) return QPACK_ENCODER;
		if (code == QPACK_DECODER.code) return QPACK_DECODER;
		return UNKNOWN;
	}
}
