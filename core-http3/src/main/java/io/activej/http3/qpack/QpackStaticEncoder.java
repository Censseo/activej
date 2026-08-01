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
import io.activej.bytebuf.ByteBufPool;
import io.activej.http.HttpHeader;

import java.util.List;

/**
 * A {@link QpackEncoder} that only ever emits static-table representations (RFC 9204 §4.5), since
 * this implementation never builds a dynamic table.
 * <p>
 * Preference order per field, per contracts/wire-protocol.md §4.2: Indexed Field Line when both the
 * name and value match a static-table entry exactly; otherwise Literal Field Line with Name
 * Reference when only the name matches; otherwise Literal Field Line with Literal Name. The Encoded
 * Field Section Prefix is always Required Insert Count {@code 0}, {@code S=0}, Delta Base {@code 0}
 * (FR-032).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 §4.5 — Field Line
 * Representations</a>
 */
public final class QpackStaticEncoder implements QpackEncoder {
	/** Indexed Field Line, static table: {@code 1 1 index(6+)}. */
	private static final int INDEXED_STATIC_FLAGS = 0xC0;

	/** Literal Field Line with Name Reference, static table, never-indexed unset: {@code 0 1 0 1 index(4+)}. */
	private static final int LITERAL_NAME_REF_STATIC_FLAGS = 0x50;

	/** Literal Field Line with Literal Name, never-indexed unset: {@code 0 0 1 0 H name-len(3+)}. */
	private static final int LITERAL_LITERAL_NAME_FLAGS = 0x20;

	private static final int LITERAL_NAME_HUFFMAN_FLAG = 0x08;

	private static final int VALUE_HUFFMAN_FLAG = 0x80;

	@Override
	public ByteBuf encode(List<QpackField> fields) {
		ByteBuf out = ByteBufPool.allocate(64);
		QpackIntegers.writeInteger(out, 8, 0, 0); // Required Insert Count = 0
		QpackIntegers.writeInteger(out, 7, 0, 0); // S = 0, Delta Base = 0
		for (QpackField field : fields) {
			out = encodeField(out, field);
		}
		return out;
	}

	private static ByteBuf encodeField(ByteBuf out, QpackField field) {
		HttpHeader name = field.name();
		byte[] value = field.value();

		int exactIndex = QpackStaticTable.indexOfNameAndValue(name, value);
		if (exactIndex != -1) {
			out = ByteBufPool.ensureWriteRemaining(out, 8);
			QpackIntegers.writeInteger(out, 6, INDEXED_STATIC_FLAGS, exactIndex);
			return out;
		}

		int nameIndex = QpackStaticTable.indexOfName(name);
		if (nameIndex != -1) {
			out = ByteBufPool.ensureWriteRemaining(out, 16 + value.length);
			QpackIntegers.writeInteger(out, 4, LITERAL_NAME_REF_STATIC_FLAGS, nameIndex);
			return writeStringLiteral(out, value, 7, 0, VALUE_HUFFMAN_FLAG);
		}

		byte[] nameBytes = new byte[name.size()];
		name.writeTo(nameBytes, 0);
		out = ByteBufPool.ensureWriteRemaining(out, 16 + nameBytes.length + value.length);
		out = writeStringLiteral(out, nameBytes, 3, LITERAL_LITERAL_NAME_FLAGS, LITERAL_NAME_HUFFMAN_FLAG);
		return writeStringLiteral(out, value, 7, 0, VALUE_HUFFMAN_FLAG);
	}

	/**
	 * Writes one {@code H|N... length(prefixBits+) bytes} literal string, choosing Huffman only
	 * when it is shorter than the literal (RFC 7541 §5.2, FR-029).
	 *
	 * @param baseFlags   the fixed flag bits of the representation (with the H bit left clear)
	 * @param huffmanFlag the single bit to OR into {@code baseFlags} when Huffman is chosen
	 */
	private static ByteBuf writeStringLiteral(ByteBuf out, byte[] data, int prefixBits, int baseFlags, int huffmanFlag) {
		int huffmanLength = QpackHuffman.encodedLength(data, 0, data.length);
		boolean huffman = huffmanLength < data.length;
		int length = huffman ? huffmanLength : data.length;
		int flags = huffman ? (baseFlags | huffmanFlag) : baseFlags;

		out = ByteBufPool.ensureWriteRemaining(out, QpackIntegers.encodedLength(prefixBits, length) + length);
		QpackIntegers.writeInteger(out, prefixBits, flags, length);
		if (huffman) {
			QpackHuffman.encode(out, data, 0, data.length);
		} else {
			out.write(data);
		}
		return out;
	}
}
