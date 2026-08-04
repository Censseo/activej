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

	public QpackField(HttpHeader name, byte[] value) {
		this.name = name;
		this.value = value;
	}

	public HttpHeader name() {
		return name;
	}

	/** Callers must not mutate the returned array. */
	public byte[] value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof QpackField that)) return false;
		return name.equals(that.name) && Arrays.equals(value, that.value);
	}

	@Override
	public int hashCode() {
		return 31 * name.hashCode() + Arrays.hashCode(value);
	}

	@Override
	public String toString() {
		return name + ": " + new String(value, StandardCharsets.ISO_8859_1);
	}
}
