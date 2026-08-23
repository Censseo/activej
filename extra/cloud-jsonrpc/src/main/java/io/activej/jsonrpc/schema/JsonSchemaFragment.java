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

package io.activej.jsonrpc.schema;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A JSON Schema <b>draft-07</b> fragment inside the pinned subset, or the boolean {@code true} fallback
 * (FR-022).
 *
 * <p>This is the shape a Content Descriptor's {@code schema} member takes in the emitted OpenRPC document.
 * It is deliberately <b>not</b> a general JSON Schema model: it carries exactly the six members the pinned
 * subset uses and nothing else, so a fragment that cannot be expressed is expressed as {@link Any} rather
 * than approximated. An approximate schema presented as complete would be the specification violation; the
 * boolean is the honest answer.
 *
 * <p>Produced by {@link JsonSchemaMapping#map(java.lang.reflect.Type)} and serialised by
 * {@link JsonSchemaMapping#SCHEMA_CODEC}.
 *
 * <h2>Synchronous by construction</h2>
 * Nothing in this package touches a reactor, a {@code Promise}, a {@code ByteBuf} or a transport — the
 * schema is a pure function of the declared types, computed once at {@code build()} time. {@code
 * ModuleBoundaryTest} asserts it by scanning this file as text.
 */
public sealed interface JsonSchemaFragment permits JsonSchemaFragment.Any, JsonSchemaFragment.Typed {

	/**
	 * The {@code true} fallback — a legal draft-07 schema that <i>constrains nothing</i>, and the documented
	 * marker for a type outside the pinned subset (FR-022). Trivially distinguishable from a described type:
	 * it is a JSON boolean where every described fragment is a JSON object.
	 */
	record Any() implements JsonSchemaFragment {
		/** The canonical instance. {@link JsonSchemaMapping} returns this one, never a fresh record. */
		public static final Any INSTANCE = new Any();
	}

	/**
	 * A described fragment: a {@code type} plus the members the pinned subset attaches to it.
	 *
	 * <p>Which members may be present is a function of {@code type}, and every rule below is enforced by the
	 * compact constructor rather than left as a convention — the mapping is not the only producer once
	 * feature 08's document model reuses this type.
	 *
	 * <table border="1">
	 *     <caption>member applicability</caption>
	 *     <tr><th>{@code type}</th><th>may also carry</th><th>row of the mapping table</th></tr>
	 *     <tr><td>{@code "boolean"} / {@code "integer"} / {@code "number"} / {@code "null"}</td>
	 *         <td>nothing</td><td>scalars, and the {@code void} result (rule M7)</td></tr>
	 *     <tr><td>{@code "string"}</td><td>{@code enumValues}</td><td>{@code String}, {@code char}, {@code enum E}</td></tr>
	 *     <tr><td>{@code "array"}</td><td>{@code items}</td><td>{@code T[]}, {@code List<T>}, {@code Set<T>}</td></tr>
	 *     <tr><td>{@code "object"}</td><td>{@code properties} + {@code required}, <b>or</b>
	 *         {@code additionalProperties}</td><td>{@code record R}, {@code Map<String,V>}</td></tr>
	 * </table>
	 *
	 * <p><b>{@code properties} and {@code additionalProperties} are mutually exclusive</b>: the first says
	 * "a fixed set of members" (a {@code record}), the second says "any member name, this value shape" (a
	 * {@code Map}). Carrying both would describe neither.
	 *
	 * <p>Iteration order is part of the contract: {@code properties} keeps its insertion order (a record's
	 * canonical-constructor order, mirroring feature 011's derived wire contract) and {@code required} keeps
	 * the same order, so the emitted bytes are byte-comparable against a frozen reference (FR-051).
	 *
	 * @param enumValues          non-null only when {@code type} is {@code "string"} — the enum constants in
	 *                            declaration order
	 * @param properties          non-null only when {@code type} is {@code "object"} and the shape has a
	 *                            fixed member set; insertion-ordered
	 * @param required            non-null only alongside {@code properties}; every component of a record is
	 *                            required, in the same order
	 * @param items               non-null only when {@code type} is {@code "array"}; may itself be
	 *                            {@link Any}
	 * @param additionalProperties non-null only when {@code type} is {@code "object"} and the shape has no
	 *                            fixed member set; may itself be {@link Any}
	 */
	record Typed(
		String type,
		@Nullable List<String> enumValues,
		@Nullable Map<String, JsonSchemaFragment> properties,
		@Nullable List<String> required,
		@Nullable JsonSchemaFragment items,
		@Nullable JsonSchemaFragment additionalProperties
	) implements JsonSchemaFragment {

		/** The draft-07 simple types this subset emits (§4.2.1 — {@code null} is one of them). */
		public static final Set<String> TYPES =
			Set.of("string", "integer", "number", "boolean", "array", "object", "null");

		/**
		 * @throws NullPointerException     if {@code type} is {@code null}
		 * @throws IllegalArgumentException if {@code type} is outside {@link #TYPES}, or if a member is
		 *                                  present that the type does not admit
		 */
		public Typed {
			Objects.requireNonNull(type, "type");
			if (!TYPES.contains(type)) {
				throw new IllegalArgumentException("not a draft-07 simple type: \"" + type + "\", expected one of " + TYPES);
			}
			if (properties != null && additionalProperties != null) {
				throw new IllegalArgumentException(
					"properties and additionalProperties are mutually exclusive: the first describes a fixed " +
					"member set (a record), the second an open one (a Map)");
			}
			if (enumValues != null && !type.equals("string")) {
				throw new IllegalArgumentException("enum is emitted for \"string\" only, not \"" + type + '"');
			}
			if (items != null && !type.equals("array")) {
				throw new IllegalArgumentException("items is emitted for \"array\" only, not \"" + type + '"');
			}
			if ((properties != null || additionalProperties != null) && !type.equals("object")) {
				throw new IllegalArgumentException(
					"properties/additionalProperties are emitted for \"object\" only, not \"" + type + '"');
			}
			if (required != null && properties == null) {
				throw new IllegalArgumentException("required names properties, so it cannot appear without them");
			}
			enumValues = enumValues == null ? null : List.copyOf(enumValues);
			// LinkedHashMap, never Map.copyOf: the iteration order IS the emitted member order
			properties = properties == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
			required = required == null ? null : List.copyOf(required);
		}

		/** A scalar fragment: {@code {"type":"<type>"}}. */
		public static Typed of(String type) {
			return new Typed(type, null, null, null, null, null);
		}

		/** {@code {"type":"string","enum":[…]}} — the constants in declaration order. */
		public static Typed ofEnum(List<String> constants) {
			return new Typed("string", constants, null, null, null, null);
		}

		/** {@code {"type":"array","items":…}}. */
		public static Typed array(JsonSchemaFragment items) {
			return new Typed("array", null, null, null, Objects.requireNonNull(items, "items"), null);
		}

		/** {@code {"type":"object","properties":{…},"required":[…]}} — the fixed-member-set shape. */
		public static Typed object(Map<String, JsonSchemaFragment> properties, List<String> required) {
			return new Typed("object", null,
				Objects.requireNonNull(properties, "properties"),
				Objects.requireNonNull(required, "required"),
				null, null);
		}

		/** {@code {"type":"object","additionalProperties":…}} — the open-member-set shape. */
		public static Typed ofMap(JsonSchemaFragment additionalProperties) {
			return new Typed("object", null, null, null, null,
				Objects.requireNonNull(additionalProperties, "additionalProperties"));
		}
	}
}
