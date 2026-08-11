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
import io.activej.jsonrpc.ConformanceVectors.Vector;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User Story 5 — replays every conformance vector (FR-060…FR-063, SC-001, SC-002).
 *
 * <h2>Why there are two kinds of assertion</h2>
 * This feature has <b>no dispatcher</b> (Contract 1 places method dispatch in feature 03), so for a vector
 * like {@code positional-params-subtract} nothing here can compute {@code subtract(42,23) = 19}. Its
 * {@code response} is canned ground truth from the specification, not something derivable by executing
 * anything. Vectors therefore split in two:
 * <ul>
 *     <li><b>Decode-determined</b> — every malformed, notification, and batch-structural vector. The whole
 *     response follows from decoding alone, so the assertion is end-to-end:
 *     {@code render(decode(request))} equals {@code response}.</li>
 *     <li><b>{@linkplain #REQUIRES_DISPATCH Dispatch-determined}</b> — the four {@code subtract} vectors and
 *     {@code method-not-found}. Their responses cannot be derived here, so the assertion is split: the
 *     <i>request</i> must decode to a structurally valid message with the right id and method, and the
 *     <i>response</i> — which is itself a valid JSON-RPC document — must survive a decode/encode round trip
 *     unchanged. That exercises this feature on realistic response shapes without pretending to dispatch.</li>
 * </ul>
 * {@code batch-mixed} is both at once, and is handled on its own.
 *
 * <h2>Comparison rules (Contract 3)</h2>
 * JSON-value equality by default — object members order-insensitive, numbers by value. A batch response
 * array is compared as a <b>multiset keyed by {@code id}</b>, never by position (FR-046). {@code exactBytes}
 * switches to raw-byte comparison.
 *
 * <h2>No transport harness here (FR-064)</h2>
 * This is a decoder-level test. Nothing in this class is parameterised over a transport or a connection;
 * that harness needs the transport SPI and belongs to feature 03.
 */
public class JsonRpcConformanceTest {

	/** Every vector across both files. Asserted, so a silently dropped vector fails the build (SC-001). */
	private static final int EXPECTED_VECTOR_COUNT = 30;

	/**
	 * Vectors whose {@code response} is a <b>dispatch</b> outcome — a computed result, or a method that does
	 * not resolve. Nothing in this module can derive them; see the class Javadoc.
	 */
	private static final Set<String> REQUIRES_DISPATCH = Set.of(
		"positional-params-subtract",
		"positional-params-subtract-reversed",
		"named-params-subtract",
		"named-params-subtract-reordered",
		"method-not-found");

	/** Both kinds at once: four dispatch responses and one decode-determined error, in one array. */
	private static final String MIXED = "batch-mixed";

	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	// ---------------------------------------------------------------------------------------------------
	// The vector set itself.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theWholeVectorSetIsPresent() {
		List<Vector> all = ConformanceVectors.loadAll();
		assertEquals("a silently dropped vector must fail the build", EXPECTED_VECTOR_COUNT, all.size());
		assertEquals(15, ConformanceVectors.load(ConformanceVectors.SPEC_EXAMPLES).size());
		assertEquals(15, ConformanceVectors.load(ConformanceVectors.HARDENING).size());
	}

	@Test
	public void everyVectorIsNamedUniquelyAndDescribed() {
		Set<String> names = new HashSet<>();
		for (Vector vector : ConformanceVectors.loadAll()) {
			assertTrue("duplicate vector name: " + vector.name(), names.add(vector.name()));
			assertFalse(vector.name(), vector.name().isBlank());
			assertFalse(vector.name() + " needs a description of the property it pins",
				vector.description().isBlank());
			assertFalse(vector.name() + " needs a request document", vector.request().isEmpty());
			assertTrue("names are stable and kebab-case: " + vector.name(),
				vector.name().matches("[a-z0-9]+(-[a-z0-9]+)*"));
		}
	}

	@Test
	public void theFifteenSpecExamplesAreTheNormativeOnes() {
		// SC-001: 100% of §7's examples, each by its stable name
		assertEquals(List.of(
				"positional-params-subtract", "positional-params-subtract-reversed", "named-params-subtract",
				"named-params-subtract-reordered", "notification-update", "notification-foobar",
				"method-not-found", "invalid-json", "invalid-request-object", "batch-invalid-json",
				"batch-empty-array", "batch-invalid-single", "batch-invalid-three", "batch-mixed",
				"batch-all-notifications"),
			ConformanceVectors.load(ConformanceVectors.SPEC_EXAMPLES).stream().map(Vector::name).toList());
	}

	@Test
	public void theFifteenHardeningVectorsCoverThisFeaturesOwnDecisions() {
		assertEquals(List.of(
				"envelope-too-large", "batch-too-large", "nesting-too-deep", "invalid-response-both",
				"invalid-response-neither", "duplicate-member", "wrong-jsonrpc-version",
				"missing-jsonrpc-version", "fractional-id", "id-out-of-long-range", "params-bare-literal",
				"leading-bom", "unknown-members-around-defined", "params-before-method", "empty-method"),
			ConformanceVectors.load(ConformanceVectors.HARDENING).stream().map(Vector::name).toList());
	}

	@Test
	public void everyErrorCodeThisFeatureDefinesHasAVector() {
		// SC-002: all nine codes. -32601/-32602/-32603 are the dispatcher's to raise, so only -32601 appears
		// among the vectors as a peer's document; the other two are covered by JsonRpcErrorsTest.
		Set<Integer> codes = new HashSet<>();
		for (Vector vector : ConformanceVectors.loadAll()) {
			if (vector.expectsNoResponse()) continue;
			collectCodes(parseJson(vector.response()), codes);
		}
		for (int code : new int[]{-32700, -32600, -32601, -32001, -32002, -32003, -32004}) {
			assertTrue("no vector exercises " + code, codes.contains(code));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// The replay.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyVectorReplays() {
		List<String> failures = new ArrayList<>();
		int decodeDetermined = 0;
		int dispatchDetermined = 0;

		for (Vector vector : ConformanceVectors.loadAll()) {
			try {
				if (vector.name().equals(MIXED)) {
					replayMixedBatch(vector);
					dispatchDetermined++;
				} else if (REQUIRES_DISPATCH.contains(vector.name())) {
					replayDispatchDetermined(vector);
					dispatchDetermined++;
				} else {
					replayDecodeDetermined(vector);
					decodeDetermined++;
				}
			} catch (AssertionError | RuntimeException e) {
				failures.add(vector.name() + " — " + vector.description() + "\n\t\t" + e.getMessage());
			}
		}

		if (!failures.isEmpty()) {
			fail(failures.size() + " of " + EXPECTED_VECTOR_COUNT + " vectors failed:\n\t" +
				 String.join("\n\t", failures));
		}
		assertEquals("every vector must be replayed by exactly one strategy",
			EXPECTED_VECTOR_COUNT, decodeDetermined + dispatchDetermined);
		assertEquals(24, decodeDetermined);
		assertEquals(6, dispatchDetermined);
	}

	/** The whole response follows from decoding: assert it end to end. */
	private void replayDecodeDetermined(Vector vector) {
		byte[] rendered = render(vector, JsonRpcDecoder.decode(vector.request().getBytes(UTF_8)));

		if (vector.expectsNoResponse()) {
			assertEquals("expected no response document at all, got <" + new String(rendered, UTF_8) + '>',
				0, rendered.length);
			return;
		}
		assertTrue("expected a response document, got zero bytes", rendered.length > 0);

		if (vector.exactBytes()) {
			assertEquals(vector.response(), new String(rendered, UTF_8));
			return;
		}
		assertJsonEquals(parseJson(vector.response()), parseJson(new String(rendered, UTF_8)));
	}

	/**
	 * The response is a dispatch outcome this module cannot derive, so assert what it <i>can</i>: the request
	 * decodes to a structurally valid message, and the response document survives a round trip.
	 */
	private void replayDispatchDetermined(Vector vector) {
		JsonRpcInput request = JsonRpcDecoder.decode(vector.request().getBytes(UTF_8));
		assertFalse("the request of a dispatch-determined vector must be VALID, got " + request,
			request instanceof JsonRpcMalformed);
		assertTrue(request instanceof JsonRpcRequest || request instanceof JsonRpcNotification);

		assertNotNull("a dispatch-determined vector needs a response document", vector.response());
		assertRoundTrips(vector.response());

		// the response's id must be the request's — §5, and the whole basis of correlation
		if (request instanceof JsonRpcRequest decoded) {
			Object responseTree = parseJson(vector.response());
			assertJsonEquals(idOf(parseJson(vector.request())), idOf(responseTree));
			assertNotNull(decoded.id());
		}
	}

	/**
	 * {@code batch-mixed} carries both kinds in one array. Correlation is by {@code id} alone — never by
	 * position (FR-046), which is why the invalid element's error is looked up by its {@code null} id rather
	 * than by index.
	 */
	private void replayMixedBatch(Vector vector) {
		JsonRpcInput input = JsonRpcDecoder.decode(vector.request().getBytes(UTF_8));
		assertTrue("the mixed batch must decode as a batch", input instanceof JsonRpcBatch);

		JsonRpcBatch batch = (JsonRpcBatch) input;
		assertEquals("six elements in, five responses out — the notification answers nothing", 6, batch.size());
		assertEquals(4, count(batch, JsonRpcRequest.class));
		assertEquals(1, count(batch, JsonRpcNotification.class));
		assertEquals(1, count(batch, JsonRpcMalformed.class));

		// the one decode-determined element: {"foo": "boo"} has no jsonrpc member
		JsonRpcMalformed invalid = (JsonRpcMalformed) batch.elements().get(3);
		assertEquals(JsonRpcId.NULL, invalid.id());
		assertEquals(-32600, invalid.error().code());

		// and its rendered document must be present, by id, among the expected responses
		Object expected = parseJson(vector.response());
		assertTrue(expected instanceof List);
		Object rendered = parseJson(new String(JsonRpcEncoder.encode(invalid.toResponse()), UTF_8));
		assertTrue("the -32600 element must appear in the expected batch response, keyed by its null id",
			((List<?>) expected).stream().anyMatch(element -> jsonEquals(element, rendered)));

		// every response element is a valid JSON-RPC document in its own right
		assertRoundTrips(vector.response());
	}

	/** A response document decodes and re-encodes to the same JSON value (FR-046 correlation shapes). */
	private void assertRoundTrips(String document) {
		JsonRpcInput decoded = JsonRpcDecoder.decode(document.getBytes(UTF_8));
		assertFalse("a vector's own response document must itself be valid JSON-RPC, got " + decoded,
			decoded instanceof JsonRpcMalformed);

		byte[] reEncoded = decoded instanceof JsonRpcBatch batch ?
			JsonRpcEncoder.encode(new JsonRpcOutput.Batch(messagesOf(batch))) :
			JsonRpcEncoder.encode((JsonRpcMessage) decoded);

		assertJsonEquals(parseJson(document), parseJson(new String(reEncoded, UTF_8)));
	}

	private static List<JsonRpcMessage> messagesOf(JsonRpcBatch batch) {
		List<JsonRpcMessage> messages = new ArrayList<>();
		for (JsonRpcDecoded element : batch.elements()) {
			assertTrue("a response document's elements must all be valid messages, got " + element,
				element instanceof JsonRpcMessage);
			messages.add((JsonRpcMessage) element);
		}
		return messages;
	}

	// ---------------------------------------------------------------------------------------------------
	// Rendering — the test-only stand-in for feature 03's dispatcher, as in JsonRpcBatchTest.
	// ---------------------------------------------------------------------------------------------------

	private static byte[] render(Vector vector, JsonRpcInput input) {
		List<JsonRpcMessage> responses = new ArrayList<>();
		if (input instanceof JsonRpcBatch batch) {
			for (JsonRpcDecoded element : batch.elements()) collect(vector, element, responses);
			return JsonRpcEncoder.encode(
				responses.isEmpty() ? JsonRpcOutput.NONE : new JsonRpcOutput.Batch(responses));
		}
		collect(vector, (JsonRpcDecoded) input, responses);
		return JsonRpcEncoder.encode(
			responses.isEmpty() ? JsonRpcOutput.NONE : new JsonRpcOutput.Single(responses.get(0)));
	}

	private static void collect(Vector vector, JsonRpcDecoded element, List<JsonRpcMessage> into) {
		switch (element) {
			case JsonRpcMalformed malformed -> into.add(malformed.toResponse());
			case JsonRpcNotification ignored -> {
				// §4.1: a notification is answered by nothing at all
			}
			case JsonRpcResponse ignored -> {
				// an incoming response is an answer, not a question
			}
			case JsonRpcRequest request -> fail(vector.name() + " decodes to a valid request (" +
												request.method() + "), so its response needs a dispatcher — " +
												"it belongs in REQUIRES_DISPATCH, not the decode-determined set");
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// JSON-value comparison (Contract 3 rules 1 and 2).
	// ---------------------------------------------------------------------------------------------------

	private static void assertJsonEquals(Object expected, Object actual) {
		if (expected instanceof List<?> expectedArray && actual instanceof List<?> actualArray) {
			assertBatchEquals(expectedArray, actualArray);
			return;
		}
		if (!jsonEquals(expected, actual)) {
			assertEquals(String.valueOf(expected), String.valueOf(actual));
			fail("expected <" + expected + "> but was <" + actual + '>');
		}
	}

	/** A batch response array is a multiset keyed by {@code id}: order carries no meaning (FR-046). */
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

	private static Object idOf(Object element) {
		return element instanceof Map<?, ?> map ? map.get("id") : null;
	}

	private static boolean jsonEquals(Object a, Object b) {
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

	private static void collectCodes(Object tree, Set<Integer> into) {
		if (tree instanceof Map<?, ?> map) {
			Object code = map.get("code");
			if (map.containsKey("message") && code instanceof Number number) into.add(number.intValue());
			for (Object value : map.values()) collectCodes(value, into);
		} else if (tree instanceof List<?> list) {
			for (Object element : list) collectCodes(element, into);
		}
	}

	private static Object parseJson(String json) {
		byte[] bytes = json.getBytes(UTF_8);
		try {
			JsonReader<Object> reader = DSL_JSON.newReader().process(bytes, bytes.length);
			reader.getNextToken();
			return ObjectConverter.deserializeObject(reader);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static int count(JsonRpcBatch batch, Class<?> type) {
		int count = 0;
		for (JsonRpcDecoded element : batch.elements()) {
			if (type.isInstance(element)) count++;
		}
		return count;
	}
}
