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

import com.dslplatform.json.BoolConverter;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.json.ObjectJsonCodec;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Typed;
import io.activej.types.Types;

import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps an already-bound Java {@link Type} to a JSON Schema draft-07 {@link JsonSchemaFragment}, over the
 * pinned subset of {@code specs/018-jsonrpc-schema-discovery/data-model.md}, and serialises the result.
 *
 * <h2>The subset (FR-021)</h2>
 * <table border="1">
 *     <caption>the normative mapping table</caption>
 *     <tr><th>Java {@code Type} (bound)</th><th>fragment</th></tr>
 *     <tr><td>{@code boolean} / {@code Boolean}</td><td>{@code {"type":"boolean"}}</td></tr>
 *     <tr><td>{@code byte} / {@code short} / {@code int} / {@code long} + boxed, {@code BigInteger}</td>
 *         <td>{@code {"type":"integer"}}</td></tr>
 *     <tr><td>{@code float} / {@code double} + boxed, {@code BigDecimal}</td><td>{@code {"type":"number"}}</td></tr>
 *     <tr><td>{@code String}, {@code char} / {@code Character}</td><td>{@code {"type":"string"}}</td></tr>
 *     <tr><td>{@code enum E}</td><td>{@code {"type":"string","enum":[…declaration order…]}}</td></tr>
 *     <tr><td>{@code T[]} (reference component), {@code List<T>}, {@code Set<T>}</td>
 *         <td>{@code {"type":"array","items":…}}</td></tr>
 *     <tr><td>{@code Map<String,V>}</td><td>{@code {"type":"object","additionalProperties":…}}</td></tr>
 *     <tr><td>{@code record R}, every component inside the subset</td>
 *         <td>{@code {"type":"object","properties":{…},"required":[…]}} — component names, canonical
 *         constructor order</td></tr>
 *     <tr><td>{@code void} / {@code Void}</td><td>{@code {"type":"null"}} (rule M7)</td></tr>
 *     <tr><td>anything else</td><td>the boolean {@code true} (FR-022)</td></tr>
 * </table>
 *
 * <h2>What it reads, and what it refuses to read (FR-023)</h2>
 * The walk uses {@code java.lang.reflect} and nothing else: it resolves <b>no codec</b>, inspects no
 * implementation class and loads no class through any classloader or codegen mechanism. The declared type is
 * the only source, because it is the only <i>honest</i> one — a codec is behaviour, not structure.
 * <p>
 * Its dispatch order deliberately mirrors {@code JsonCodecFactory}'s registration order (records before
 * {@code List}/{@code Set}/{@code Map}, reference arrays only), so a type describes itself the way it
 * encodes itself. It does not <i>call</i> that factory.
 *
 * <h2>Two rules worth reading twice</h2>
 * <ol>
 *     <li><b>A record with any component outside the subset falls back as a whole</b> — not to an object
 *     whose offending property is {@code true}. An object listing every component as {@code required}
 *     presents itself as a complete description, which is precisely what FR-022 forbids for a shape that is
 *     not completely described. A container is different: {@code List<UUID>} is
 *     {@code {"type":"array","items":true}}, because the array-ness <i>is</i> completely described and the
 *     element is visibly marked.</li>
 *     <li><b>A cycle's back-edge is outside the subset.</b> Expressing a cycle needs {@code $ref}, which the
 *     pinned subset does not carry, so the repeated record maps to the fallback — and what happens to the
 *     record <i>containing</i> the cycle then follows from rule 1, in its two halves. A cycle closing on a
 *     bare component collapses the whole record: {@code record Node(String value, Node next)} is
 *     {@code true}. A cycle closing inside a container does not, because the container describes itself
 *     completely and marks its element: {@code record Tree(String name, List<Tree> children)} is an object
 *     whose {@code children} is {@code {"type":"array","items":true}}. Both answers are honest about the
 *     same fact; the second is simply able to say more of what it knows.</li>
 * </ol>
 *
 * <h2>The {@code void} convention</h2>
 * {@code JsonRpcMethodDescriptor.resultCodec() == null} means — and only ever means — a declared result of
 * {@code void} or {@code Promise<Void>} (feature 012, FR-030). A caller holding such a descriptor describes
 * it with {@link #VOID_RESULT}, equivalently {@code map(void.class)}; {@code Void.class} answers
 * identically. This class never sees a {@code Promise} type: unwrapping one happened during contract
 * validation, and {@code io.activej.promise} is not importable in this package.
 *
 * <h2>Thread-safety</h2>
 * Stateless, synchronous, no cache, no reactor. Every call is a fresh walk over immutable reflection data,
 * so the class is safe to use from any thread — including none in particular, which is how the offline
 * export uses it.
 */
public final class JsonSchemaMapping {
	private JsonSchemaMapping() {}

	private static final Typed BOOLEAN = Typed.of("boolean");
	private static final Typed INTEGER = Typed.of("integer");
	private static final Typed NUMBER = Typed.of("number");
	private static final Typed STRING = Typed.of("string");

	/**
	 * The fragment for a {@code void} / {@code Promise<Void>} result: {@code {"type":"null"}} (rule M7).
	 * The wire value of such a call is always the JSON literal {@code null}, and draft-07 §4.2.1 admits
	 * {@code null} as a simple type — so this is exact, not a fallback.
	 * <p>
	 * Equal to {@code map(void.class)}; named so a caller holding a {@code resultCodec() == null}
	 * descriptor never has to conjure a {@link Type} to say "no result".
	 */
	public static final JsonSchemaFragment VOID_RESULT = Typed.of("null");

	private static final Set<Class<?>> INTEGER_TYPES = Set.of(
		byte.class, short.class, int.class, long.class,
		Byte.class, Short.class, Integer.class, Long.class,
		BigInteger.class);

	private static final Set<Class<?>> NUMBER_TYPES = Set.of(
		float.class, double.class,
		Float.class, Double.class,
		BigDecimal.class);

	/**
	 * Maps one bound type to its fragment. <b>Total</b>: every type has an answer, and the answer for
	 * everything outside the subset is {@link Any#INSTANCE} (FR-022). Never throws for a type it cannot
	 * describe — being unable to describe something is a documented outcome here, not a failure.
	 *
	 * @param type the declared type, with every type variable already bound — exactly what
	 *             {@code JsonRpcParamDescriptor.type()} carries. A {@link TypeVariable} or a wildcard
	 *             cannot be described and yields the fallback.
	 * @throws NullPointerException if {@code type} is {@code null}; a caller describing "no result" passes
	 *                              {@code void.class}, or uses {@link #VOID_RESULT}
	 */
	public static JsonSchemaFragment map(Type type) {
		Objects.requireNonNull(type, "type");
		return map(type, Set.of());
	}

	/**
	 * @param enclosingRecords the record types currently being walked — the cycle guard. Copied rather than
	 *                         mutated, so two sibling components never see each other's path.
	 */
	private static JsonSchemaFragment map(Type type, Set<Class<?>> enclosingRecords) {
		if (type instanceof Class<?> cls) return mapClass(cls, enclosingRecords);
		if (type instanceof ParameterizedType parameterized) return mapParameterized(parameterized, enclosingRecords);
		if (type instanceof GenericArrayType genericArray) {
			return Typed.array(map(genericArray.getGenericComponentType(), enclosingRecords));
		}
		// a TypeVariable or a wildcard: contract validation already refused these on a service interface
		// (rule 7), so reaching one here means the type was never bound - and an unbound type cannot be
		// described honestly
		return Any.INSTANCE;
	}

	private static JsonSchemaFragment mapClass(Class<?> cls, Set<Class<?>> enclosingRecords) {
		if (cls == void.class || cls == Void.class) return VOID_RESULT;
		if (cls == boolean.class || cls == Boolean.class) return BOOLEAN;
		if (INTEGER_TYPES.contains(cls)) return INTEGER;
		if (NUMBER_TYPES.contains(cls)) return NUMBER;
		if (cls == String.class || cls == char.class || cls == Character.class) return STRING;

		Class<?> enumClass = enumClassOf(cls);
		if (enumClass != null) return mapEnum(enumClass);

		// before the container checks, mirroring JsonCodecFactory's Record-before-List/Set/Map order: a
		// record that also implements a collection interface describes itself by its components
		if (cls.isRecord()) return mapRecord(cls, cls, enclosingRecords);

		if (cls.isArray()) {
			Class<?> component = cls.getComponentType();
			// primitive arrays are outside the subset on purpose: JsonCodecFactory refuses them because
			// base64-versus-numeric is an undecided wire question, and describing what is not decided
			// would be the approximation FR-022 forbids
			if (component.isPrimitive()) return Any.INSTANCE;
			return Typed.array(map(component, enclosingRecords));
		}

		// a raw List/Set/Map carries no element type, and everything else - UUID, Instant, Optional,
		// Object, an arbitrary class - has no row at all
		return Any.INSTANCE;
	}

	private static JsonSchemaFragment mapParameterized(ParameterizedType type, Set<Class<?>> enclosingRecords) {
		if (!(type.getRawType() instanceof Class<?> raw)) return Any.INSTANCE;
		Type[] arguments = type.getActualTypeArguments();

		if (raw.isRecord()) return mapRecord(raw, type, enclosingRecords);

		if (arguments.length == 1 && (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw))) {
			return Typed.array(map(arguments[0], enclosingRecords));
		}

		if (arguments.length == 2 && Map.class.isAssignableFrom(raw)) {
			// the subset admits a String-keyed map only: any other key type is a JSON object key that the
			// schema cannot describe as one, so the whole map falls back
			if (arguments[0] != String.class) return Any.INSTANCE;
			return Typed.ofMap(map(arguments[1], enclosingRecords));
		}

		return Any.INSTANCE;
	}

	/** {@code getEnumConstants()} order is declaration order, which is what the enum's wire form uses. */
	private static JsonSchemaFragment mapEnum(Class<?> enumClass) {
		Object[] constants = enumClass.getEnumConstants();
		List<String> names = new ArrayList<>(constants.length);
		for (Object constant : constants) {
			names.add(((Enum<?>) constant).name());
		}
		return Typed.ofEnum(names);
	}

	/**
	 * @param raw  the record class
	 * @param type the same class, or the parameterized form carrying its type arguments
	 */
	private static JsonSchemaFragment mapRecord(Class<?> raw, Type type, Set<Class<?>> enclosingRecords) {
		// a cycle cannot be expressed without $ref, so the back-edge is outside the subset. What that costs
		// the record containing it depends on how it holds it: a bare component makes the whole record fall
		// back (the rule below), a container absorbs it and is described with a marked element
		if (enclosingRecords.contains(raw)) return Any.INSTANCE;

		Map<TypeVariable<?>, Type> bindings;
		try {
			bindings = Types.getAllTypeBindings(type);
		} catch (RuntimeException e) {
			return Any.INSTANCE;
		}

		Set<Class<?>> path = new HashSet<>(enclosingRecords);
		path.add(raw);

		RecordComponent[] components = raw.getRecordComponents();
		Map<String, JsonSchemaFragment> properties = new LinkedHashMap<>(); // canonical constructor order
		List<String> required = new ArrayList<>(components.length);
		for (RecordComponent component : components) {
			Type componentType;
			try {
				componentType = Types.bind(component.getGenericType(), bindings);
			} catch (RuntimeException e) {
				return Any.INSTANCE;
			}
			JsonSchemaFragment fragment = map(componentType, path);
			// one component outside the subset makes the whole record undescribed: an object listing every
			// component as required would present itself as complete (FR-022)
			if (!(fragment instanceof Typed)) return Any.INSTANCE;
			properties.put(component.getName(), fragment);
			required.add(component.getName());
		}
		return Typed.object(properties, required);
	}

	/**
	 * The enum class behind {@code cls}, or {@code null} when it is not an enum. An enum constant with a
	 * class body is an anonymous subclass whose {@code isEnum()} is {@code false} — a declared type is never
	 * one of those, but answering correctly costs one line.
	 */
	private static Class<?> enumClassOf(Class<?> cls) {
		if (cls.isEnum()) return cls;
		Class<?> superclass = cls.getSuperclass();
		return superclass != null && superclass.isEnum() ? superclass : null;
	}

	// -------------------------------------------------------------------------------------------------
	// Serialization. A hand-assembled ObjectJsonCodec graph, not a derived record codec (research
	// Decision 3): the fallback is a JSON boolean, which no record shape expresses, and every member but
	// "type" is omitted when absent - "absent" and "null" are different documents to an OpenRPC consumer.
	// -------------------------------------------------------------------------------------------------

	/**
	 * The recursion knot. {@code items}, {@code additionalProperties} and every value of {@code properties}
	 * are themselves fragments, so the object codec needs the very codec it is a part of; this indirection
	 * dereferences {@link #SCHEMA_CODEC} at call time, when the static initialiser has finished.
	 */
	private static final JsonCodec<JsonSchemaFragment> NESTED = new JsonCodec<>() {
		@Override
		public void write(JsonWriter writer, JsonSchemaFragment value) {
			SCHEMA_CODEC.write(writer, value);
		}

		@Override
		public JsonSchemaFragment read(JsonReader<?> reader) throws IOException {
			return SCHEMA_CODEC.read(reader);
		}
	};

	/**
	 * The described shape. Member order is the builder's field order — {@code type}, {@code enum},
	 * {@code properties}, {@code required}, {@code items}, {@code additionalProperties} — and it is part of
	 * the frozen comparison (rule M10).
	 * <p>
	 * Every member but {@code type} is registered with a {@code null} default, which is
	 * {@code BuilderArray}'s encode-side omission: a member equal to its default is not written at all,
	 * never written as {@code null}.
	 */
	@SuppressWarnings("unchecked")
	private static final ObjectJsonCodec<Typed, Object[]> TYPED_CODEC = ObjectJsonCodec
		.<Typed>builder(array -> new Typed(
			(String) array[0],
			(List<String>) array[1],
			(Map<String, JsonSchemaFragment>) array[2],
			(List<String>) array[3],
			(JsonSchemaFragment) array[4],
			(JsonSchemaFragment) array[5]))
		.with("type", Typed::type, JsonCodecs.ofString())
		.with("enum", Typed::enumValues, JsonCodecs.ofList(JsonCodecs.ofString()), null)
		.with("properties", Typed::properties, JsonCodecs.ofMap(NESTED), null)
		.with("required", Typed::required, JsonCodecs.ofList(JsonCodecs.ofString()), null)
		.with("items", Typed::items, NESTED, null)
		.with("additionalProperties", Typed::additionalProperties, NESTED, null)
		.build();

	/**
	 * The codec for a fragment, and the one every consumer of this package should use — {@link Any} writes
	 * the JSON literal {@code true}, {@link Typed} writes an object carrying only its present members, in
	 * the fixed order {@code type}, {@code enum}, {@code properties}, {@code required}, {@code items},
	 * {@code additionalProperties}.
	 * <p>
	 * Decoding is supported and exact — the emitted form reads back equal — although nothing in this
	 * feature consumes an OpenRPC document; it exists so a round trip can be asserted rather than assumed.
	 */
	public static final JsonCodec<JsonSchemaFragment> SCHEMA_CODEC = new JsonCodec<>() {
		@Override
		public void write(JsonWriter writer, JsonSchemaFragment value) {
			switch (value) {
				case Any ignored -> BoolConverter.serialize(true, writer);
				case Typed typed -> TYPED_CODEC.write(writer, typed);
			}
		}

		@Override
		public JsonSchemaFragment read(JsonReader<?> reader) throws IOException {
			if (reader.last() == JsonWriter.OBJECT_START) return TYPED_CODEC.read(reader);
			if (BoolConverter.deserialize(reader)) return Any.INSTANCE;
			throw reader.newParseError(
				"a JSON Schema fragment of this subset is an object or the boolean true, never false");
		}
	};
}
