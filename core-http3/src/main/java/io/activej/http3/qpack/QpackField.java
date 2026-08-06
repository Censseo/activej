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

import io.activej.http.HttpHeader;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One decoded or to-be-encoded field line: an interned {@link HttpHeader} name plus its raw value
 * bytes. The unit {@link QpackDecoder}/{@link QpackEncoder} exchange with the reactive layer.
 * <p>
 * {@link #value()} is owned by this instance — a plain heap array, not a pooled {@code ByteBuf}, so
 * it needs no recycling. A decoder never returns a value backed by the (recycled) input buffer; see
 * {@link QpackDecoder#decode}.
 */
public final class QpackField {
	private final HttpHeader name;
	private final byte[] value;
	private final boolean nameHadUppercase;
	private final boolean neverIndexed;

	public QpackField(HttpHeader name, byte[] value) {
		this(name, value, false);
	}

	/**
	 * As {@link #QpackField(HttpHeader, byte[])}, recording whether the <b>literal</b> name octets on the
	 * wire contained an uppercase character.
	 * <p>
	 * The flag exists because {@link #name()} cannot answer the question. {@code HttpHeaders} interning
	 * replaces the peer's spelling with the registry's own for any case-insensitive match, so a peer that
	 * sends {@code Content-Type} and one that sends {@code content-type} produce the <b>same</b> token —
	 * and RFC 9114 §4.1.1 requires the first to be rejected. Only the decoder still holds the octets, so
	 * only the decoder can answer, and it reports the fact here rather than enforcing an HTTP/3 rule that
	 * is not QPACK's to enforce (see {@code Http3Headers.fromQpack}).
	 * <p>
	 * Always {@code false} for a static-table reference: RFC 9204 Appendix A's names are lowercase by
	 * construction, so an index can never carry a case violation.
	 */
	public QpackField(HttpHeader name, byte[] value, boolean nameHadUppercase) {
		this(name, value, nameHadUppercase, false);
	}

	/**
	 * As {@link #QpackField(HttpHeader, byte[], boolean)}, additionally marking the field line as
	 * <b>never-indexed</b> (RFC 9204 §7.1): an encoder must neither insert it into the dynamic table
	 * nor reference an entry for it, and must emit the literal form with the {@code N} bit set so no
	 * intermediary indexes it either.
	 * <p>
	 * This is the <i>per-field</i> override. The default never-indexed <i>set</i> — {@code Authorization},
	 * {@code Proxy-Authorization}, {@code Set-Cookie} — is configuration, and lives in
	 * {@code Http3Settings.qpackNeverIndexedFields()}; a dynamic encoder applies both.
	 */
	public QpackField(HttpHeader name, byte[] value, boolean nameHadUppercase, boolean neverIndexed) {
		this.name = name;
		this.value = value;
		this.nameHadUppercase = nameHadUppercase;
		this.neverIndexed = neverIndexed;
	}

	/** This field with the never-indexed marker set; {@code this} when it already is. */
	public QpackField asNeverIndexed() {
		return neverIndexed ? this : new QpackField(name, value, nameHadUppercase, true);
	}

	public HttpHeader name() {
		return name;
	}

	/**
	 * The field name as literal wire octets, lowercased — <b>the only supported way for an encoder to
	 * put a name on the wire as octets rather than as a table index</b> (RFC 9114 §4.1.1: "Field names
	 * MUST be converted to lowercase prior to their encoding").
	 * <p>
	 * {@link #name()} cannot be used for this, and the reason is the mirror image of the one in
	 * {@link #QpackField(HttpHeader, byte[], boolean)}: {@code core-http}'s registry is
	 * case-insensitive, so for any of the ~150 names it has <b>registered</b> it hands back its own
	 * canonically-cased token — {@code HttpHeaders.of("accept-charset")} is the {@code Accept-Charset}
	 * token — silently undoing whatever lowercasing a caller did. The 99 RFC 9204 Appendix A names
	 * hide it, since those are sent as an index and never as octets, so the violation surfaces only
	 * for a legal name outside the static table but inside the registry.
	 * <p>
	 * Lowercasing here rather than at each encoder keeps every present and future {@link QpackEncoder}
	 * — the dynamic-table one included, which needs these same octets for its encoder-stream
	 * instructions — correct by construction. Names are {@code tchar} (RFC 9110 §5.6.2), hence ASCII,
	 * so an octet-wise fold is exact and locale-independent.
	 *
	 * @return a fresh array the caller may keep or mutate
	 */
	public byte[] lowercaseNameBytes() {
		byte[] bytes = new byte[name.size()];
		name.writeTo(bytes, 0);
		for (int i = 0; i < bytes.length; i++) {
			byte b = bytes[i];
			if (b >= 'A' && b <= 'Z') bytes[i] = (byte) (b + ('a' - 'A'));
		}
		return bytes;
	}

	/**
	 * Whether the literal name octets carried an uppercase character — a fact about the wire that
	 * {@link #name()} has already lost. See {@link #QpackField(HttpHeader, byte[], boolean)}.
	 */
	public boolean nameHadUppercase() {
		return nameHadUppercase;
	}

	/**
	 * Whether this field line must never be indexed — see
	 * {@link #QpackField(HttpHeader, byte[], boolean, boolean)}.
	 * <p>
	 * Deliberately <b>excluded</b> from {@link #equals}/{@link #hashCode}/{@link #toString}: it is a
	 * directive about how to <i>represent</i> the field, not part of the field line's identity — two
	 * fields differing only in it carry the same name and the same value, and a round trip through any
	 * encoder/decoder pair is entitled to lose it. Assert it directly, never through equality.
	 */
	public boolean neverIndexed() {
		return neverIndexed;
	}

	/** Callers must not mutate the returned array. */
	public byte[] value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof QpackField that)) return false;
		return name.equals(that.name) && Arrays.equals(value, that.value)
			&& nameHadUppercase == that.nameHadUppercase;
	}

	@Override
	public int hashCode() {
		return 31 * (31 * name.hashCode() + Arrays.hashCode(value)) + Boolean.hashCode(nameHadUppercase);
	}

	@Override
	public String toString() {
		return name + ": " + new String(value, StandardCharsets.ISO_8859_1);
	}
}
