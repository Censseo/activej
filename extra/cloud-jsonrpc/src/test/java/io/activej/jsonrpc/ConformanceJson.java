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

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.ObjectConverter;
import com.dslplatform.json.runtime.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The comparison rules of the conformance vector <b>format</b>, for every reader of that format
 * ({@link ConformanceVectors} loads the vectors; this class compares what a peer answered against one).
 *
 * <h2>Why this is a third class and not a shared superclass</h2>
 * Two suites replay these vectors and neither may depend on the other: feature 01's
 * {@code JsonRpcConformanceTest} is a decoder-level test with no transport and no dispatcher and must stay
 * that way, while feature 03's {@code service.AbstractTransportConformanceTest} is the transport-parameterised
 * harness features 04, 06 and 07 subclass. The rules belong to <i>neither</i> — they are the format's, stated
 * once in Contract 3 and implemented once here, next to the loader that reads the same files.
 *
 * <h2>It knows nothing about this module's model, on purpose (FR-062)</h2>
 * As with {@link ConformanceVectors}, there is <b>no import from {@code io.activej.jsonrpc}</b>'s model here:
 * everything is a parsed JSON tree of {@code Map}, {@code List}, {@code String}, {@code Number} and
 * {@code null}. A downstream feature can compare a response knowing only the file format, without compiling
 * against the decoder.
 *
 * <h2>The rules</h2>
 * <ol>
 *     <li>JSON-<b>value</b> equality — object members are order-insensitive and numbers compare by value.
 *     (A vector that sets {@code exactBytes} bypasses this class entirely: its caller compares raw bytes.)</li>
 *     <li>A <b>batch</b> response array is a multiset keyed by {@code id}, never compared by position: §6
 *     guarantees no order and a transport is entitled to reorder (FR-046).</li>
 * </ol>
 */
public final class ConformanceJson {
	private ConformanceJson() {}

	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	/** Parses a document into a tree of {@code Map} / {@code List} / {@code String} / {@code Number}. */
	public static Object parseJson(String json) {
		byte[] bytes = json.getBytes(UTF_8);
		try {
			JsonReader<Object> reader = DSL_JSON.newReader().process(bytes, bytes.length);
			reader.getNextToken();
			return ObjectConverter.deserializeObject(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Rule 1, and rule 2 whenever both sides are arrays. */
	public static void assertJsonEquals(Object expected, Object actual) {
		if (expected instanceof List<?> expectedArray && actual instanceof List<?> actualArray) {
			assertBatchEquals(expectedArray, actualArray);
			return;
		}
		if (!jsonEquals(expected, actual)) {
			assertEquals(String.valueOf(expected), String.valueOf(actual));
			fail("expected <" + expected + "> but was <" + actual + '>');
		}
	}

	/** Rule 2: a batch response array is a multiset keyed by {@code id}; position carries no meaning. */
	private static void assertBatchEquals(List<?> expected, List<?> actual) {
		assertEquals("batch response size", expected.size(), actual.size());

		Map<Object, List<Object>> expectedById = groupById(expected);
		Map<Object, List<Object>> actualById = groupById(actual);
		assertEquals("the set of correlation ids must match", expectedById.keySet(), actualById.keySet());

		for (Map.Entry<Object, List<Object>> group : expectedById.entrySet()) {
			List<Object> remaining = new ArrayList<>(actualById.get(group.getKey()));
			for (Object element : group.getValue()) {
				// consume exactly ONE match: removeIf would take every equal element at once, and three
				// identical -32600 errors would then look like one
				boolean matched = false;
				for (Iterator<Object> candidates = remaining.iterator(); candidates.hasNext(); ) {
					if (jsonEquals(element, candidates.next())) {
						candidates.remove();
						matched = true;
						break;
					}
				}
				if (!matched) {
					fail("no response with id " + group.getKey() + " matched <" + element + "> among " +
						 actualById.get(group.getKey()));
				}
			}
			assertTrue("unmatched responses for id " + group.getKey() + ": " + remaining, remaining.isEmpty());
		}
	}

	private static Map<Object, List<Object>> groupById(List<?> elements) {
		Map<Object, List<Object>> byId = new LinkedHashMap<>();
		for (Object element : elements) {
			byId.computeIfAbsent(String.valueOf(idOf(element)), key -> new ArrayList<>()).add(element);
		}
		return byId;
	}

	/** The {@code id} member of a response element, or {@code null} when it has none. */
	public static @Nullable Object idOf(Object element) {
		return element instanceof Map<?, ?> map ? map.get("id") : null;
	}

	/** Rule 1 as a predicate: JSON-value equality, for a caller that wants to search rather than assert. */
	public static boolean jsonEquals(Object a, Object b) {
		if (a == null || b == null) return a == b;
		if (a instanceof Map<?, ?> mapA && b instanceof Map<?, ?> mapB) {
			if (!mapA.keySet().equals(mapB.keySet())) return false;      // members, order-insensitive
			for (Map.Entry<?, ?> entry : mapA.entrySet()) {
				if (!jsonEquals(entry.getValue(), mapB.get(entry.getKey()))) return false;
			}
			return true;
		}
		if (a instanceof List<?> listA && b instanceof List<?> listB) {
			if (listA.size() != listB.size()) return false;              // a JSON array IS ordered
			for (int i = 0; i < listA.size(); i++) {
				if (!jsonEquals(listA.get(i), listB.get(i))) return false;
			}
			return true;
		}
		if (a instanceof Number numberA && b instanceof Number numberB) {
			// dsl-json yields Long for an integral number and BigDecimal for a fractional one, so a plain
			// equals() would call 19 and 19 different things
			return new BigDecimal(numberA.toString()).compareTo(new BigDecimal(numberB.toString())) == 0;
		}
		return a.equals(b);
	}
}
