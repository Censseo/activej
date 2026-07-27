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

package io.activej.quic.codec;

import io.activej.bytebuf.ByteBuf;

import java.util.Arrays;

/**
 * Content-based equality/hashCode for the {@code ByteBuf} fields of frame/packet value types.
 * {@code ByteBuf} itself is identity-based (it is a mutable, refcounted resource), so value
 * types that own one must compare readable bytes explicitly to give {@code equals}/{@code
 * hashCode} the value semantics the rest of each type already has.
 */
final class ByteBufContents {
	private ByteBufContents() {
	}

	static boolean equals(ByteBuf a, ByteBuf b) {
		return Arrays.equals(a.array(), a.head(), a.tail(), b.array(), b.head(), b.tail());
	}

	static int hashCode(ByteBuf buf) {
		int result = 1;
		for (int i = buf.head(); i < buf.tail(); i++) {
			result = 31 * result + buf.at(i);
		}
		return result;
	}
}
