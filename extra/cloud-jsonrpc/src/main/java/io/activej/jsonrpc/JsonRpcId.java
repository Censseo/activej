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

package io.activej.jsonrpc;

import java.util.Objects;

/**
 * The request identifier of a JSON-RPC 2.0 envelope — a closed set of exactly three forms, matching the
 * three the specification permits in §4: a {@code String}, a {@code Number}, or the JSON literal
 * {@code null}.
 * <p>
 * <b>Absence is not a form.</b> A message with no {@code id} member is a {@link JsonRpcNotification}, a
 * distinct type. That is what makes "a notification produces no response" a property of the type system
 * rather than a runtime check (FR-011, FR-012).
 * <p>
 * <b>Numbers are 64-bit signed integral.</b> A fractional identifier, and an integral one outside
 * {@code long} range, are refused by the decoder with {@code -32600} (FR-036) — this type cannot represent
 * either, which is the point: no construction path admits a {@code double}, a {@code Number}, a
 * {@code boolean} or an {@code Object}, so a malformed identifier cannot enter the model by accident.
 * <p>
 * <b>Equality is by value, and a string never equals a number.</b> {@code Str("1")} and {@code Num(1)} are
 * different identifiers even though a careless reader would call them equivalent. This matters: the
 * identifier is the sole correlation key between a request and its response (FR-046), so a client keying a
 * pending-call map on it must not conflate the two.
 * <p>
 * Round-tripping: {@link Num} re-encodes to the same digits for every value it accepts. {@link Str}
 * re-encodes with JSON string escaping, which is not byte-identical for an identifier containing an escape
 * — semantically equal, which is all §5 requires.
 */
public sealed interface JsonRpcId permits JsonRpcId.Str, JsonRpcId.Num, JsonRpcId.Null {
	/**
	 * The JSON literal {@code null} identifier — the identifier of an error whose request identifier could
	 * not be recovered (FR-037).
	 */
	JsonRpcId NULL = new Null();

	/**
	 * A string identifier. The wire form is a JSON string.
	 *
	 * @param value the identifier, never {@code null} — the JSON literal {@code null} is {@link Null}, not a
	 *              {@code Str} of a null reference
	 */
	record Str(String value) implements JsonRpcId {
		/** @throws NullPointerException if {@code value} is {@code null} — the null id is {@link #NULL} */
		public Str {
			Objects.requireNonNull(value, "a string id has no null form; use JsonRpcId.NULL");
		}

		@Override
		public String toString() {
			return '"' + value + '"';
		}
	}

	/**
	 * A numeric identifier, 64-bit signed integral. The wire form is a JSON number with no fraction and no
	 * exponent.
	 *
	 * @param value the identifier, anywhere in {@code long} range
	 */
	record Num(long value) implements JsonRpcId {
		@Override
		public String toString() {
			return Long.toString(value);
		}
	}

	/**
	 * The JSON literal {@code null} identifier. A singleton in practice — use {@link JsonRpcId#NULL} — though
	 * every instance is equal to every other, since it is a component-less record.
	 */
	record Null() implements JsonRpcId {
		@Override
		public String toString() {
			return "null";
		}
	}
}
