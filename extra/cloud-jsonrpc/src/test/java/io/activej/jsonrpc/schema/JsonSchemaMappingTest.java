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

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Any;
import io.activej.jsonrpc.schema.JsonSchemaFragment.Typed;
import io.activej.types.TypeT;
import org.junit.Test;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Pins the normative Java&nbsp;{@code Type}&nbsp;→&nbsp;JSON&nbsp;Schema draft-07 subset of
 * {@code specs/018-jsonrpc-schema-discovery/data-model.md} and
 * {@code contracts/openrpc-mapping.md}, row by row, plus the two properties the whole feature rests
 * on: the mapping is <b>total</b> (every type has an answer) and everything outside the subset emits
 * the JSON Schema boolean {@code true} rather than an approximation (FR-022).
 *
 * <p>The fragments are asserted twice over — structurally, and as the bytes
 * {@link JsonSchemaMapping#SCHEMA_CODEC} writes — because the frozen reference documents (FR-051)
 * compare bytes, and a structural equality that serialises differently would pass here and fail
 * there.
 *
 * <p>No {@code EventloopRule}, no {@code ByteBufRule}: this package is deliberately synchronous and
 * reactor-free, and a rule here would be the first import that made it otherwise.
 */
public final class JsonSchemaMappingTest {

	// ---------------------------------------------------------------------------------------------
	// Fixtures. Local to this test on purpose: the reference fixture in schema/fixtures/ belongs to
	// the frozen-document tests, and a shared fixture would couple a mapping row to a document row.
	// ---------------------------------------------------------------------------------------------

	/** The {@code enum E} row. Declaration order is the emitted order — deliberately not alphabetical. */
	public enum Colour {RED, GREEN, BLUE}

	/** The flat {@code record R} row: two components, two different scalar rows. */
	public record Point(int x, int y) {}

	/** Recursion inside the subset: a {@code record} whose components include another {@code record}. */
	public record Segment(String label, Point from, Point to) {}

	/** A component the subset does not describe ⇒ the <b>whole</b> record falls back (data-model.md). */
	public record Audited(String name, UUID id) {}

	/** A cycle cannot be expressed without {@code $ref}, which the pinned subset does not carry. */
	public record Node(String value, Node next) {}

	/** A generic record, so binding a component's type variable is exercised rather than assumed. */
	public record Box<T>(String label, T value) {}

	// ---------------------------------------------------------------------------------------------
	// The scalar rows
	// ---------------------------------------------------------------------------------------------

	@Test
	public void booleans() {
		assertJson("{\"type\":\"boolean\"}", boolean.class);
		assertJson("{\"type\":\"boolean\"}", Boolean.class);
	}

	@Test
	public void integers() {
		assertJson("{\"type\":\"integer\"}", byte.class);
		assertJson("{\"type\":\"integer\"}", short.class);
		assertJson("{\"type\":\"integer\"}", int.class);
		assertJson("{\"type\":\"integer\"}", long.class);

		assertJson("{\"type\":\"integer\"}", Byte.class);
		assertJson("{\"type\":\"integer\"}", Short.class);
		assertJson("{\"type\":\"integer\"}", Integer.class);
		assertJson("{\"type\":\"integer\"}", Long.class);

		// the pair most easily confused with the row below
		assertJson("{\"type\":\"integer\"}", BigInteger.class);
	}

	@Test
	public void numbers() {
		assertJson("{\"type\":\"number\"}", float.class);
		assertJson("{\"type\":\"number\"}", double.class);
		assertJson("{\"type\":\"number\"}", Float.class);
		assertJson("{\"type\":\"number\"}", Double.class);
		assertJson("{\"type\":\"number\"}", BigDecimal.class);
	}

	@Test
	public void strings() {
		assertJson("{\"type\":\"string\"}", String.class);
		assertJson("{\"type\":\"string\"}", char.class);
		assertJson("{\"type\":\"string\"}", Character.class);
	}

	@Test
	public void enums() {
		JsonSchemaFragment fragment = JsonSchemaMapping.map(Colour.class);

		Typed typed = typed(fragment);
		assertEquals("string", typed.type());
		assertEquals(List.of("RED", "GREEN", "BLUE"), typed.enumValues());
		assertNull(typed.properties());
		assertNull(typed.items());
		assertNull(typed.additionalProperties());

		assertJson("{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]}", Colour.class);
	}

	// ---------------------------------------------------------------------------------------------
	// The container rows
	// ---------------------------------------------------------------------------------------------

	@Test
	public void arrays() {
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", String[].class);
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}",
			String[][].class);
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":" +
				   "{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}}",
			Point[].class);
	}

	@Test
	public void listsAndSets() {
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", new TypeT<List<String>>() {}.getType());
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\",\"BLUE\"]}}",
			new TypeT<Set<Colour>>() {}.getType());
		// nesting: the element type is mapped by the same walk, not by a special case
		assertJson("{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}",
			new TypeT<List<List<Integer>>>() {}.getType());
	}

	@Test
	public void stringKeyedMaps() {
		JsonSchemaFragment fragment = JsonSchemaMapping.map(new TypeT<Map<String, Integer>>() {}.getType());

		Typed typed = typed(fragment);
		assertEquals("object", typed.type());
		assertEquals(new Typed("integer", null, null, null, null, null), typed.additionalProperties());
		// a Map has no fixed property set: properties/required must stay absent (they are mutually
		// exclusive with additionalProperties)
		assertNull(typed.properties());
		assertNull(typed.required());

		assertJson("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}",
			new TypeT<Map<String, Integer>>() {}.getType());
		assertJson("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}",
			new TypeT<Map<String, List<String>>>() {}.getType());
	}

	// ---------------------------------------------------------------------------------------------
	// The record row — component names and canonical-constructor order are the wire contract
	// ---------------------------------------------------------------------------------------------

	@Test
	public void records() {
		Typed typed = typed(JsonSchemaMapping.map(Point.class));

		assertEquals("object", typed.type());
		assertEquals(List.of("x", "y"), List.copyOf(typed.properties().keySet()));
		assertEquals(List.of("x", "y"), typed.required());
		assertEquals(new Typed("integer", null, null, null, null, null), typed.properties().get("x"));
		assertEquals(new Typed("integer", null, null, null, null, null), typed.properties().get("y"));
		assertNull(typed.additionalProperties());

		assertJson("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}," +
				   "\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}", Point.class);
	}

	@Test
	public void nestedRecordsRecurse() {
		Typed typed = typed(JsonSchemaMapping.map(Segment.class));

		assertEquals(List.of("label", "from", "to"), List.copyOf(typed.properties().keySet()));
		assertEquals(List.of("label", "from", "to"), typed.required());

		Typed from = typed(typed.properties().get("from"));
		assertEquals("object", from.type());
		assertEquals(List.of("x", "y"), List.copyOf(from.properties().keySet()));

		assertJson("{\"type\":\"object\",\"properties\":{" +
				   "\"label\":{\"type\":\"string\"}," +
				   "\"from\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}," +
				   "\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}," +
				   "\"to\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"}," +
				   "\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}}," +
				   "\"required\":[\"label\",\"from\",\"to\"]}", Segment.class);
	}

	@Test
	public void genericRecordComponentIsBound() {
		assertJson("{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"}," +
				   "\"value\":{\"type\":\"integer\"}},\"required\":[\"label\",\"value\"]}",
			new TypeT<Box<Integer>>() {}.getType());
	}

	// ---------------------------------------------------------------------------------------------
	// The void result — rule M7
	// ---------------------------------------------------------------------------------------------

	@Test
	public void voidResultIsTheNullSchema() {
		// The input convention: a descriptor whose resultCodec() is null (⟺ void / Promise<Void>) is
		// described by mapping void.class. Void.class answers identically, and VOID_RESULT is the
		// same fragment under a name, so a caller never has to construct a Type to say "no result".
		assertJson("{\"type\":\"null\"}", void.class);
		assertJson("{\"type\":\"null\"}", Void.class);

		assertEquals(JsonSchemaMapping.map(void.class), JsonSchemaMapping.VOID_RESULT);
		assertEquals("{\"type\":\"null\"}", write(JsonSchemaMapping.VOID_RESULT));
	}

	// ---------------------------------------------------------------------------------------------
	// The marked partial fallback (FR-022)
	// ---------------------------------------------------------------------------------------------

	@Test
	public void mapWithNonStringKeyFallsBack() {
		assertFallback(new TypeT<Map<Integer, String>>() {}.getType());
		assertFallback(new TypeT<Map<Colour, BigDecimal>>() {}.getType());
		assertFallback(new TypeT<Map<UUID, String>>() {}.getType());
	}

	@Test
	public void typesWithNoRowAtAllFallBack() {
		assertFallback(UUID.class);
		assertFallback(Instant.class);
		assertFallback(Object.class);
		assertFallback(new TypeT<Optional<String>>() {}.getType());
		// a raw container carries no element type, so there is nothing honest to put in "items"
		assertFallback(List.class);
		assertFallback(Map.class);
		// primitive arrays: JsonCodecFactory deliberately refuses them (base64 vs numeric is an
		// undecided wire question), so this mapping does not invent an answer either
		assertFallback(byte[].class);
		assertFallback(int[].class);
	}

	@Test
	public void recordWithAnOutOfSubsetComponentFallsBackWhole() {
		// data-model.md: "a record with any component outside the subset" is in the fallback row —
		// an object shape listing every component as required would present itself as complete.
		assertFallback(Audited.class);
	}

	@Test
	public void recursiveRecordFallsBack() {
		// the cycle's back-edge is outside the subset (no $ref), so by the rule above the whole
		// record is
		assertFallback(Node.class);
	}

	@Test
	public void aFallbackInsideAContainerStaysInsideIt() {
		// only the record row collapses; a container describes what it can and marks its element
		assertJson("{\"type\":\"array\",\"items\":true}", new TypeT<List<UUID>>() {}.getType());
		assertJson("{\"type\":\"object\",\"additionalProperties\":true}",
			new TypeT<Map<String, Instant>>() {}.getType());
	}

	@Test
	public void theFallbackIsTheJsonBooleanTrue() {
		assertEquals("true", write(Any.INSTANCE));
		assertEquals("true", write(new Any()));
		assertSame(Any.INSTANCE, JsonSchemaMapping.map(UUID.class));
	}

	// ---------------------------------------------------------------------------------------------
	// Totality and the shared fragment type
	// ---------------------------------------------------------------------------------------------

	@Test
	public void theMappingIsTotal() {
		for (Type type : List.<Type>of(
			boolean.class, int.class, double.class, String.class, Colour.class, Point.class,
			Segment.class, Audited.class, Node.class, UUID.class, Object.class, byte[].class,
			String[].class, List.class, new TypeT<List<String>>() {}.getType(),
			new TypeT<Map<String, Point>>() {}.getType(), new TypeT<Map<Integer, Point>>() {}.getType(),
			void.class, Void.class)
		) {
			assertNotNull("no fragment for " + type, JsonSchemaMapping.map(type));
		}
	}

	@Test
	public void aNullTypeIsAProgrammerError() {
		assertThrows(NullPointerException.class, () -> JsonSchemaMapping.map(null));
	}

	@Test
	public void propertiesAndAdditionalPropertiesAreMutuallyExclusive() {
		Map<String, JsonSchemaFragment> properties = Map.of("x", new Typed("integer", null, null, null, null, null));
		assertThrows(IllegalArgumentException.class, () ->
			new Typed("object", null, properties, List.of("x"), null, Any.INSTANCE));
	}

	@Test
	public void theCodecRoundTripsEveryShape() throws MalformedDataException {
		for (JsonSchemaFragment fragment : List.of(
			Any.INSTANCE,
			JsonSchemaMapping.map(boolean.class),
			JsonSchemaMapping.map(Colour.class),
			JsonSchemaMapping.map(String[].class),
			JsonSchemaMapping.map(Segment.class),
			JsonSchemaMapping.map(new TypeT<Map<String, Integer>>() {}.getType()),
			JsonSchemaMapping.VOID_RESULT)
		) {
			String json = write(fragment);
			JsonSchemaFragment read = JsonUtils.fromJson(JsonSchemaMapping.SCHEMA_CODEC, json);
			assertEquals(json, fragment, read);
			assertEquals(json, write(read));
		}
	}

	@Test
	public void theCodecEmitsMembersInTheContractOrder() {
		// type, enum, properties, required, items, additionalProperties — the fixed order of the
		// frozen comparison (rule M10). properties/additionalProperties never co-occur, so this is
		// asserted as the two orders they each participate in.
		Map<String, JsonSchemaFragment> properties = new LinkedHashMap<>();
		properties.put("b", new Typed("string", null, null, null, null, null));
		properties.put("a", new Typed("integer", null, null, null, null, null));

		assertEquals("{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"}," +
					 "\"a\":{\"type\":\"integer\"}},\"required\":[\"b\",\"a\"]}",
			write(new Typed("object", null, properties, List.of("b", "a"), null, null)));

		assertEquals("{\"type\":\"array\",\"items\":true}",
			write(new Typed("array", null, null, null, Any.INSTANCE, null)));
	}

	// ---------------------------------------------------------------------------------------------

	private static void assertJson(String expected, Type type) {
		JsonSchemaFragment fragment = JsonSchemaMapping.map(type);
		assertTrue("expected a described fragment for " + type + ", got the fallback",
			fragment instanceof Typed);
		assertEquals(type.toString(), expected, write(fragment));
	}

	private static void assertFallback(Type type) {
		assertEquals("expected the boolean-true fallback for " + type,
			Any.INSTANCE, JsonSchemaMapping.map(type));
		assertEquals("true", write(JsonSchemaMapping.map(type)));
	}

	private static Typed typed(JsonSchemaFragment fragment) {
		assertTrue("expected a described fragment, got " + fragment, fragment instanceof Typed);
		return (Typed) fragment;
	}

	private static String write(JsonSchemaFragment fragment) {
		return JsonUtils.toJson(JsonSchemaMapping.SCHEMA_CODEC, fragment);
	}
}
