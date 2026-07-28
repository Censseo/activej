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

import java.util.Arrays;

/**
 * The reassembly buffer for one encryption level's CRYPTO stream (RFC 9001 §4.1.2): split or
 * coalesced handshake-message bytes are accumulated until whole messages can be lifted out.
 * <p>
 * Growth is geometric (doubling) and consumed prefixes are compacted away, so a message
 * dribbled in one fragment per packet costs O(n) overall — a plain concatenate-per-fragment
 * scheme is quadratic and exploitable by an unauthenticated peer at the Initial level, where
 * CRYPTO bytes are not flow-controlled (RFC 9000 §4.1). A buffered byte is compacted at most
 * once: the leftover is always a strict prefix of the next incomplete message, and a shift only
 * happens when that message completes.
 * <p>
 * Retained bytes are bounded by the engines: a complete header's declared length is checked
 * against the configured per-message bound before its body is awaited, so the buffer never
 * holds more than one bounded message plus a partial header.
 */
final class CryptoReassembly {
	private byte[] buffer = new byte[0];
	private int length;

	/** The backing array — may be larger than {@link #length()}; only the first {@code length()} bytes are data. */
	byte[] array() {
		return buffer;
	}

	/** The number of buffered, not-yet-consumed bytes. */
	int length() {
		return length;
	}

	/** Appends one CRYPTO-stream chunk, growing the backing array geometrically. */
	void append(byte[] bytes) {
		ensureCapacity(length + bytes.length);
		System.arraycopy(bytes, 0, buffer, length, bytes.length);
		length += bytes.length;
	}

	/** Drops the first {@code n} bytes (the complete messages just processed), compacting the remainder. */
	void discardPrefix(int n) {
		if (n == 0) return;
		length -= n;
		if (length == 0) {
			buffer = new byte[0]; // release the capacity after a burst
			return;
		}
		System.arraycopy(buffer, n, buffer, 0, length);
	}

	/** Releases all buffered bytes (terminal failure). */
	void clear() {
		buffer = new byte[0];
		length = 0;
	}

	private void ensureCapacity(int needed) {
		if (needed <= buffer.length) return;
		int capacity = Math.max(64, buffer.length);
		while (capacity < needed) {
			capacity *= 2;
		}
		buffer = Arrays.copyOf(buffer, capacity);
	}
}
