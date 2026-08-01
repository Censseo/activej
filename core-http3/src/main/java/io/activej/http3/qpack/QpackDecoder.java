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
 * Decodes one QPACK-encoded field section (RFC 9204 §4.5) into an ordered list of
 * {@link QpackField}s.
 * <p>
 * Shaped so a future dynamic-table implementation (feature 06, out of scope here) can be added
 * without changing this signature (FR-033): the encoded field section already carries everything a
 * dynamic-table decoder would need (the Required Insert Count / Base prefix), so nothing here is
 * static-table-specific except the one implementation this feature ships,
 * {@link QpackStaticDecoder}.
 * <p>
 * <b>Ownership</b>: {@code decode} owns and recycles {@code encodedFieldSection} on every path,
 * success and failure — the platform-wide {@code ByteBuf} ownership convention (mirrors, e.g.,
 * {@code TlsEngine.consume} and {@code QuicPacketProtection.open} in {@code core-quic}). The
 * returned fields' value bytes are freshly allocated, independent of the (by then recycled) input.
 */
public interface QpackDecoder {
	/**
	 * @param encodedFieldSection a complete encoded field section, e.g. one HEADERS frame's payload.
	 *                            Owned and recycled by this call on every path
	 * @return the field lines in wire order
	 * @throws QpackException a QPACK decompression failure — see {@link QpackStaticDecoder} for the
	 *                        exact rejected representations
	 */
	List<QpackField> decode(ByteBuf encodedFieldSection) throws QpackException;
}
