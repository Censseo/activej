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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;

import java.util.List;

/**
 * Encodes an ordered list of {@link QpackField}s into one QPACK-encoded field section (RFC 9204
 * §4.5), ready to become an HTTP/3 HEADERS frame payload.
 * <p>
 * Shaped so a future dynamic-table implementation (feature 06, out of scope here) can be added
 * without changing this signature (FR-033) — the only thing that changes is what the Encoded Field
 * Section Prefix and individual representations look like; the shape of "fields in, one buffer out"
 * does not. The one implementation this feature ships is {@link QpackStaticEncoder}.
 * <p>
 * <b>Ownership</b>: {@code encode} consumes nothing owned by the caller and returns a new,
 * caller-owned {@code ByteBuf}.
 */
public interface QpackEncoder {
	/** Encodes {@code fields}, in order, into a new owned {@code ByteBuf}. */
	ByteBuf encode(List<QpackField> fields);
}
