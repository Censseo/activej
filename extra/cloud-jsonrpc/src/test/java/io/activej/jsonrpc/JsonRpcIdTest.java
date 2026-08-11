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

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-012, FR-036, research Decision 7 — the request identifier is a closed set of exactly three forms.
 */
public class JsonRpcIdTest {

	@Test
	public void theThreeForms() {
		JsonRpcId string = new JsonRpcId.Str("abc");
		JsonRpcId number = new JsonRpcId.Num(42);
		JsonRpcId nul = JsonRpcId.NULL;

		assertEquals("abc", ((JsonRpcId.Str) string).value());
		assertEquals(42L, ((JsonRpcId.Num) number).value());
		assertTrue(nul instanceof JsonRpcId.Null);

		// exhaustive over the sealed set, with no default branch — this stops compiling if a fourth form appears
		for (JsonRpcId id : List.of(string, number, nul)) {
			String tag = switch (id) {
				case JsonRpcId.Str s -> "str";
				case JsonRpcId.Num n -> "num";
				case JsonRpcId.Null ignored -> "null";
			};
			assertTrue(Set.of("str", "num", "null").contains(tag));
		}
	}

	@Test
	public void permitsExactlyThreeForms() {
		assertTrue("JsonRpcId must be sealed", JsonRpcId.class.isSealed());
		Set<Class<?>> permitted = new HashSet<>(List.of(JsonRpcId.class.getPermittedSubclasses()));
		assertEquals(Set.of(JsonRpcId.Str.class, JsonRpcId.Num.class, JsonRpcId.Null.class), permitted);
	}

	@Test
	public void stringAndNumberAreNeverEqualEvenWhenEquivalent() {
		// the correlation key must not conflate "1" with 1 — a client keying a map on it would answer the wrong call
		assertNotEquals(new JsonRpcId.Str("1"), new JsonRpcId.Num(1));
		assertNotEquals(new JsonRpcId.Num(1), new JsonRpcId.Str("1"));
		assertNotEquals(JsonRpcId.NULL, new JsonRpcId.Str("null"));
		assertNotEquals(JsonRpcId.NULL, new JsonRpcId.Num(0));
	}

	@Test
	public void equalityAndHashCodeAreByValue() {
		assertEquals(new JsonRpcId.Str("abc"), new JsonRpcId.Str("abc"));
		assertEquals(new JsonRpcId.Str("abc").hashCode(), new JsonRpcId.Str("abc").hashCode());
		assertNotEquals(new JsonRpcId.Str("abc"), new JsonRpcId.Str("abd"));

		assertEquals(new JsonRpcId.Num(Long.MIN_VALUE), new JsonRpcId.Num(Long.MIN_VALUE));
		assertEquals(new JsonRpcId.Num(7).hashCode(), new JsonRpcId.Num(7).hashCode());
		assertNotEquals(new JsonRpcId.Num(7), new JsonRpcId.Num(8));

		assertEquals(JsonRpcId.NULL, new JsonRpcId.Null());
		assertEquals(JsonRpcId.NULL.hashCode(), new JsonRpcId.Null().hashCode());

		// usable as a map key, which is the whole point of FR-046
		Map<JsonRpcId, String> byId = Map.of(
			new JsonRpcId.Str("1"), "string one",
			new JsonRpcId.Num(1), "number one",
			JsonRpcId.NULL, "null");
		assertEquals("string one", byId.get(new JsonRpcId.Str("1")));
		assertEquals("number one", byId.get(new JsonRpcId.Num(1)));
		assertEquals("null", byId.get(JsonRpcId.NULL));
	}

	@Test
	public void nullIsASingletonConstant() {
		assertSame(JsonRpcId.NULL, JsonRpcId.NULL);
		assertTrue(JsonRpcId.NULL instanceof JsonRpcId.Null);
	}

	@Test
	public void aStringIdRefusesANullValueAtConstruction() {
		assertThrows(NullPointerException.class, () -> new JsonRpcId.Str(null));
	}

	/**
	 * FR-036 — a fractional, boolean or structured identifier is refused <i>at construction</i>, and the only
	 * refusal strong enough is that no construction path exists at all. The decoder maps such an identifier to
	 * {@code -32600}; it must never be able to build a {@code JsonRpcId} out of one in the first place.
	 * <p>
	 * This test fails the moment someone adds a convenience factory taking a {@code double}, a {@code Number},
	 * a {@code boolean} or an {@code Object} — which is exactly how a fractional id would get in.
	 */
	@Test
	public void fractionalBooleanAndStructuredIdsAreUnrepresentable() {
		Set<Class<?>> forbidden = Set.of(
			double.class, float.class, Double.class, Float.class,
			boolean.class, Boolean.class,
			Number.class, BigDecimal.class, BigInteger.class,
			Object.class, Collection.class, List.class, Map.class, Object[].class);

		List<String> violations = new ArrayList<>();
		for (Class<?> type : List.of(JsonRpcId.class, JsonRpcId.Str.class, JsonRpcId.Num.class, JsonRpcId.Null.class)) {
			List<Executable> constructionPaths = new ArrayList<>(List.of(type.getConstructors()));
			for (Method method : type.getMethods()) {
				if (Modifier.isStatic(method.getModifiers()) &&
					JsonRpcId.class.isAssignableFrom(method.getReturnType())) {
					constructionPaths.add(method);
				}
			}
			for (Executable path : constructionPaths) {
				for (Class<?> parameter : path.getParameterTypes()) {
					if (forbidden.contains(parameter)) {
						violations.add(path + " admits " + parameter.getSimpleName());
					}
				}
			}
		}
		if (!violations.isEmpty()) {
			fail("no construction path may admit a fractional, boolean or structured id:\n\t" +
				 String.join("\n\t", violations));
		}

		// and the two carriers are exactly the two wire forms the specification allows
		assertEquals(String.class, JsonRpcId.Str.class.getRecordComponents()[0].getType());
		assertEquals(1, JsonRpcId.Str.class.getRecordComponents().length);
		assertEquals(long.class, JsonRpcId.Num.class.getRecordComponents()[0].getType());
		assertEquals(1, JsonRpcId.Num.class.getRecordComponents().length);
		assertEquals(0, JsonRpcId.Null.class.getRecordComponents().length);
	}

	@Test
	public void aNumericIdCoversTheWhole64BitSignedRange() {
		// FR-036 refuses an integral id outside this range at the decoder; the value type must carry all of it
		assertEquals(Long.MIN_VALUE, new JsonRpcId.Num(Long.MIN_VALUE).value());
		assertEquals(Long.MAX_VALUE, new JsonRpcId.Num(Long.MAX_VALUE).value());
	}

	@Test
	public void constructorsAreVisibleForPatternMatching() {
		// a smoke check that the permitted subtypes really are records with public canonical constructors
		for (Class<?> type : List.of(JsonRpcId.Str.class, JsonRpcId.Num.class, JsonRpcId.Null.class)) {
			assertTrue(type.getSimpleName() + " must be a record", type.isRecord());
			Constructor<?>[] constructors = type.getConstructors();
			assertEquals(type.getSimpleName() + " must have exactly one public constructor", 1, constructors.length);
		}
	}
}
