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

package io.activej.jsonrpc.impl;

import io.activej.common.annotation.ExposedInternals;

/**
 * The internal view of a raw JSON-RPC payload: the envelope array it points into, and the {@code [start, end)}
 * byte range within it.
 * <p>
 * <b>Not part of the supported API surface</b> (FR-026, ADR-010). The supported operations on a payload are
 * {@code decode(codec)}, {@code toByteArray()}, {@code size()} and {@code isAbsent()}; this view exists so
 * that the transports and the dispatcher of this idea (features 03, 04, 06, 07) can re-emit a captured slice
 * without a copy. Keeping the pair out of the supported surface preserves the freedom to switch the raw
 * payload's representation to an eager sub-copy later without a breaking change.
 * <p>
 * The array is <b>not</b> copied — mutating it invalidates every payload derived from it. Treat it as
 * read-only.
 *
 * @param array the whole contiguous envelope array; never {@code null}, never copied
 * @param start the index of the payload's first byte, inclusive
 * @param end   the index one past the payload's last byte, exclusive
 */
@ExposedInternals
public record RawPayloadView(byte[] array, int start, int end) {
	/**
	 * Validates {@code 0 <= start <= end <= array.length} <b>unconditionally</b> — the same rule, for the same
	 * reason, as the raw payload itself (FR-021). This is a public constructor reachable with an index pair
	 * derived from hostile input; a check behind {@code CHECKS} would be no check at all in production.
	 */
	public RawPayloadView {
		if (array == null) throw new NullPointerException("array");
		if (start < 0 || end < start || end > array.length) {
			throw new IllegalArgumentException(
				"payload range [" + start + ", " + end + ") is out of bounds for an array of " + array.length);
		}
	}

	/** The number of bytes in the range. */
	public int size() {
		return end - start;
	}
}
