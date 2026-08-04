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
import io.activej.common.Checks;
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaders;
import io.activej.http3.Http3Errors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.activej.common.Checks.checkArgument;

/**
 * A {@link QpackDecoder} that accepts only the static-table representations of RFC 9204 §4.5.
 * <p>
 * Every representation that would reference a dynamic table — a non-zero Required Insert Count, an
 * Indexed Field Line or Literal Field Line with Name Reference with {@code T=0}, a Post-Base Index,
 * or a Post-Base Name Reference — is rejected with {@link Http3Errors#QPACK_DECOMPRESSION_FAILED},
 * since this implementation never builds one (contracts/wire-protocol.md §4.2).
 * <p>
 * The RFC 9114 §4.2.2 accounted field-section size (Σ {@code len(name) + len(value) + 32}) is
 * checked against {@code maxFieldSectionSize} incrementally, as decoded bytes are produced —
 * including inside Huffman decoding, which can expand the wire form by roughly 2× — so a hostile
 * encoding is rejected before its full decoded expansion is ever materialized (FR-030).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-4.5">RFC 9204 §4.5 — Field Line
 * Representations</a>
 */
public final class QpackStaticDecoder implements QpackDecoder {
	private static final boolean CHECKS = Checks.isEnabled(QpackStaticDecoder.class);

	private final long maxFieldSectionSize;

	public QpackStaticDecoder(long maxFieldSectionSize) {
		if (CHECKS) checkArgument(maxFieldSectionSize >= 0, "maxFieldSectionSize must not be negative");
		this.maxFieldSectionSize = maxFieldSectionSize;
	}

	@Override
	public List<QpackField> decode(ByteBuf encodedFieldSection) throws QpackException {
		try {
			long requiredInsertCount = QpackIntegers.readInteger(encodedFieldSection, 8);
			if (requiredInsertCount != 0) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"non-zero Required Insert Count: this implementation never builds a dynamic table");
			}
			// S + Delta Base. Never used: Base only matters for Post-Base representations, which are
			// rejected outright regardless of its value (see readFieldLine below).
			QpackIntegers.readInteger(encodedFieldSection, 7);

			List<QpackField> fields = new ArrayList<>();
			SizeAccountant accountant = new SizeAccountant(maxFieldSectionSize);
			while (encodedFieldSection.canRead()) {
				fields.add(readFieldLine(encodedFieldSection, accountant));
			}
			return fields;
		} finally {
			encodedFieldSection.recycle();
		}
	}

	private static QpackField readFieldLine(ByteBuf buf, SizeAccountant accountant) throws QpackException {
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED, "truncated field line");
		}
		int first = buf.peek() & 0xFF;

		if ((first & 0x80) != 0) {
			// Indexed Field Line: "1 T index(6+)".
			if ((first & 0x40) == 0) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"Indexed Field Line referenced the dynamic table");
			}
			long index = QpackIntegers.readInteger(buf, 6);
			return indexedField(index, accountant);
		}
		if ((first & 0x40) != 0) {
			// Literal Field Line with Name Reference: "0 1 N T index(4+)".
			boolean isStatic = (first & 0x10) != 0;
			if (!isStatic) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"Literal Field Line with Name Reference referenced the dynamic table");
			}
			long nameIndex = QpackIntegers.readInteger(buf, 4);
			return literalWithNameReference(nameIndex, buf, accountant);
		}
		if ((first & 0x20) != 0) {
			// Literal Field Line with Literal Name: "0 0 1 N H name-len(3+)".
			return literalWithLiteralName(first, buf, accountant);
		}
		if ((first & 0x10) != 0) {
			// Indexed Field Line with Post-Base Index: "0 0 0 1 index(4+)".
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"Indexed Field Line with Post-Base Index is not supported: no dynamic table");
		}
		// Literal Field Line with Post-Base Name Reference: "0 0 0 0 N index(3+)".
		throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			"Literal Field Line with Post-Base Name Reference is not supported: no dynamic table");
	}

	private static QpackField indexedField(long index, SizeAccountant accountant) throws QpackException {
		if (index < 0 || index >= QpackStaticTable.SIZE) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"static table index out of range: " + index);
		}
		HttpHeader name = QpackStaticTable.name((int) index);
		byte[] value = QpackStaticTable.value((int) index);
		accountant.add(32 + name.size() + value.length);
		return new QpackField(name, value);
	}

	private static QpackField literalWithNameReference(long nameIndex, ByteBuf buf, SizeAccountant accountant)
		throws QpackException {
		if (nameIndex < 0 || nameIndex >= QpackStaticTable.SIZE) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"static table name index out of range: " + nameIndex);
		}
		HttpHeader name = QpackStaticTable.name((int) nameIndex);
		accountant.add(32 + name.size());
		byte[] value = readLiteralValue(buf, accountant);
		return new QpackField(name, value);
	}

	private static QpackField literalWithLiteralName(int first, ByteBuf buf, SizeAccountant accountant)
		throws QpackException {
		boolean nameHuffman = (first & 0x08) != 0;
		long nameLength = QpackIntegers.readInteger(buf, 3);
		accountant.add(32);
		byte[] nameBytes = readStringBytes(buf, nameHuffman, nameLength, accountant);
		HttpHeader name = internName(nameBytes);
		byte[] value = readLiteralValue(buf, accountant);
		return new QpackField(name, value);
	}

	/** The value literal shape shared by both representations that carry one: {@code H value-len(7+)}. */
	private static byte[] readLiteralValue(ByteBuf buf, SizeAccountant accountant) throws QpackException {
		if (!buf.canRead()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED, "truncated field value length");
		}
		boolean huffman = (buf.peek() & 0x80) != 0;
		long length = QpackIntegers.readInteger(buf, 7);
		return readStringBytes(buf, huffman, length, accountant);
	}

	private static byte[] readStringBytes(ByteBuf buf, boolean huffman, long declaredLength, SizeAccountant accountant)
		throws QpackException {
		if (declaredLength < 0 || declaredLength > buf.readRemaining()) {
			throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"declared string length exceeds the remaining field section bytes");
		}
		int length = (int) declaredLength;
		if (!huffman) {
			byte[] literal = new byte[length];
			buf.read(literal);
			accountant.add(length);
			return literal;
		}

		byte[] encoded = new byte[length];
		buf.read(encoded);
		// Bounded by min(2x encoded length, 64 KiB): a generous starting guess, never the full
		// expansion of a hostile declared length, since the per-byte accountant.add below aborts
		// decoding (and hence further growth of this buffer) the moment the running total exceeds
		// the configured bound. Doubled as a long — `length` is bounded by the field section, which
		// an operator may configure up to Integer.MAX_VALUE, and 2x that is not an int.
		GrowingBytes decoded = new GrowingBytes((int) Math.min(Math.max(length, 1) * 2L, 1 << 16));
		QpackHuffman.decode(encoded, 0, encoded.length, b -> {
			accountant.add(1);
			decoded.write(b);
		});
		return decoded.toByteArray();
	}

	/**
	 * The Huffman decoder's output sink: a plain growing {@code byte[]}, deliberately <b>not</b> a
	 * {@link java.io.ByteArrayOutputStream}, whose {@code write(int)} is {@code synchronized}. This is
	 * one octet per call on the header-decode path of every request and every response, and the array
	 * never escapes {@link #readStringBytes} — so the monitor would be pure per-byte overhead on the
	 * hottest loop in this class.
	 */
	private static final class GrowingBytes {
		private byte[] array;
		private int size;

		GrowingBytes(int initialCapacity) {
			this.array = new byte[initialCapacity];
		}

		void write(byte b) {
			if (size == array.length) {
				// Doubling, so the amortized cost per octet stays constant. Growth past the configured
				// bound cannot happen: the caller's accountant throws first, on the octet before it.
				array = Arrays.copyOf(array, array.length * 2);
			}
			array[size++] = b;
		}

		byte[] toByteArray() {
			return size == array.length ? array : Arrays.copyOf(array, size);
		}
	}

	/** RFC 7541 §5.1/HTTP token case-insensitive hash, matching {@code core-http}'s registry so equals/hashCode agree. */
	private static HttpHeader internName(byte[] bytes) {
		int hash = 0;
		for (byte b : bytes) {
			hash += (b | 0x20);
		}
		return HttpHeaders.of(hash, bytes, 0, bytes.length);
	}

	private static final class SizeAccountant {
		private final long limit;
		private long total;

		SizeAccountant(long limit) {
			this.limit = limit;
		}

		void add(long n) throws QpackException {
			total += n;
			if (total > limit) {
				throw new QpackException(Http3Errors.QPACK_DECOMPRESSION_FAILED,
					"decoded field section exceeded the configured size bound");
			}
		}
	}
}
