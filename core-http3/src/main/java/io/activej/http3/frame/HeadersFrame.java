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
import io.activej.common.recycle.Recyclable;
import io.activej.quic.codec.QuicVarInts;

/**
 * HEADERS frame (RFC 9114 §7.2.2): a QPACK-encoded field section. Owns a retained slice of
 * {@link #fieldSection} — the caller must {@link #recycle()} it.
 * <p>
 * This frame carries opaque bytes only. QPACK decoding is deliberately not this layer's job — it
 * belongs to {@code io.activej.http3.qpack}'s decoder, driven by the reactive request-stream
 * layer, which is what keeps this codec eventloop-free and QPACK-implementation-agnostic.
 */
public final class HeadersFrame extends Http3Frame implements Recyclable {
	public static final long TYPE = 0x01;

	public final ByteBuf fieldSection;

	public HeadersFrame(ByteBuf fieldSection) {
		this.fieldSection = fieldSection;
	}

	@Override
	public long type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		int len = fieldSection.readRemaining();
		return QuicVarInts.encodedLength(TYPE) + QuicVarInts.encodedLength(len) + len;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		int len = fieldSection.readRemaining();
		QuicVarInts.write(buf, TYPE);
		QuicVarInts.write(buf, len);
		buf.put(fieldSection.array(), fieldSection.head(), len);
	}

	@Override
	public void recycle() {
		fieldSection.recycle();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof HeadersFrame other)) return false;
		return ByteBufContents.equals(fieldSection, other.fieldSection);
	}

	@Override
	public int hashCode() {
		return ByteBufContents.hashCode(fieldSection);
	}

	@Override
	public String toString() {
		return "HeadersFrame{" + fieldSection.readRemaining() + " bytes}";
	}
}
