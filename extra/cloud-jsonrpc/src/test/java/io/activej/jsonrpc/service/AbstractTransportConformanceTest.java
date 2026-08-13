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

package io.activej.jsonrpc.service;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.ObjectConverter;
import com.dslplatform.json.runtime.Settings;
import io.activej.json.JsonCodecFactory;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.ConformanceVectors;
import io.activej.jsonrpc.ConformanceVectors.Vector;
import io.activej.jsonrpc.service.fixtures.FailingApi;
import io.activej.jsonrpc.service.fixtures.FailingApiImpl;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
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

import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.APPLICATION_ERROR_JSON;
import static io.activej.jsonrpc.service.fixtures.FailingApiImpl.SECRET;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * The transport-parameterised conformance harness (FR-091…FR-096): implement {@link #createTransport} and
 * inherit the whole JSON-RPC 2.0 conformance suite.
 *
 * <pre>{@code
 * public class MyTransportConformanceTest extends AbstractTransportConformanceTest {
 *     @Override
 *     protected JsonRpcTransport createTransport(JsonRpcDispatcher peer) {
 *         return MyTransport.create(reactor, peer::dispatch);   // however this transport joins to a peer
 *     }
 * }
 * }</pre>
 *
 * <h2>What a subject supplies, and what it may not change</h2>
 * <table border="1">
 *     <caption>the extension points</caption>
 *     <tr><th>Member</th><th>Obligation</th></tr>
 *     <tr><td>{@link #createTransport(JsonRpcDispatcher)}</td>
 *         <td><b>required</b> — a transport joined to {@code peer}, on which this harness plays the
 *         <i>client</i> side: it sends the vector's request and reads what comes back</td></tr>
 *     <tr><td>{@link #skippedVectors()}</td>
 *         <td>optional — vectors this transport cannot carry, <b>named individually</b>. Every name must be a
 *         real vector, so a stale entry fails rather than hiding a vector that would otherwise run
 *         (FR-092b)</td></tr>
 *     <tr><td>{@link #awaitDelivery()}</td>
 *         <td>optional — for a transport that still has work outstanding once the reactor has gone idle</td></tr>
 *     <tr><td>{@link #createReorderableTransport(JsonRpcDispatcher)}</td>
 *         <td>optional — a double that can hold and reorder inbound documents (FR-094)</td></tr>
 * </table>
 * The dispatcher, the service interface behind it and the comparison rules are the harness's and are
 * deliberately not overridable: a subject that could choose what {@code subtract} means, or how a response is
 * compared, would be conforming to itself.
 *
 * <h2>The three comparison rules, none of them optional (FR-092a)</h2>
 * <ol>
 *     <li>JSON-<b>value</b> comparison — object members are order-insensitive and numbers compare by value —
 *     unless the vector sets {@code exactBytes}, which switches to raw bytes.</li>
 *     <li>A <b>batch</b> response array is a multiset keyed by {@code id}, never compared by position: §6
 *     guarantees no order and a transport is entitled to reorder.</li>
 *     <li>{@link Vector#expectsNoResponse()} means <b>no document at all</b> — zero bytes, asserted as "the
 *     transport delivered nothing". That is neither the four-byte document {@code null} nor the two-byte
 *     {@code []}, both of which are responses.</li>
 * </ol>
 *
 * <h2>Every vector is replayed, including the ones this feature cannot break</h2>
 * A {@code -32700} parse failure and a {@code -32001} oversize refusal are established by feature 01's decoder,
 * below anything the service layer does — and a <b>transport</b> can still break them, by fragmenting a
 * document, by joining two, or by refusing to carry a megabyte. They are therefore replayed rather than
 * assumed (FR-092b). The five vectors feature 01's own {@code JsonRpcConformanceTest} could only assert
 * structurally, because it had no dispatcher, are replayed here <b>end to end</b>: {@link ConformanceApi}
 * really implements {@code subtract}, {@code sum} and {@code get_data}, and really does not implement
 * {@code foo.get}.
 *
 * <h2>A fresh dispatcher and a fresh transport per exchange</h2>
 * Nothing carries over between vectors: a transport left holding state from the oversize vector must not be
 * what answers the next one. The cost is 30 transports per run, which no transport this SPI is meant for will
 * notice.
 */
public abstract class AbstractTransportConformanceTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	/**
	 * Asserts nothing for an in-memory subject — this feature allocates no {@code ByteBuf} — and is declared
	 * because features 04, 06 and 07 subclass this class and do own buffers (FR-098). A rule added later is a
	 * rule that never protected the tests written in between.
	 */
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The vectors that must be present for this suite to mean what its name says (FR-093). */
	private static final Set<String> REQUIRED_COVERAGE = Set.of(
		"positional-params-subtract",        // a positional call
		"named-params-subtract",             // a named call
		"notification-update",               // a notification producing nothing
		"method-not-found",                  // an unknown method producing -32601
		"batch-mixed",                       // a batch with mixed outcomes
		"batch-all-notifications");          // a batch of only notifications producing nothing

	private static final DslJson<Object> DSL_JSON =
		new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	/** Every notification failure the dispatcher reported, so that none of them passes unnoticed (FR-100). */
	private List<String> notificationFailures;

	@Before
	public void setUpConformance() {
		notificationFailures = new ArrayList<>();
	}

	// ---------------------------------------------------------------------------------------------------
	// The extension points.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Creates a transport joined to {@code peer} — whatever "joined" means for this subject: an in-memory
	 * hand-off, a socket pair, an HTTP client pointed at a server this method starts.
	 * <p>
	 * The harness holds the returned transport's <b>client</b> end: it calls {@link JsonRpcTransport#send} with
	 * a vector's request document and expects {@code peer}'s answer to arrive at the listener the harness
	 * registers immediately afterwards. The transport is closed after every exchange.
	 */
	protected abstract JsonRpcTransport createTransport(JsonRpcDispatcher peer);

	/**
	 * Vectors this transport cannot carry, <b>by name</b>. Empty by default, and every name is checked against
	 * the loaded vector set — a stale skip is a failure, never a silent omission (FR-092b).
	 * <p>
	 * A skip is a statement about the <i>transport</i>, and it belongs in a comment next to the name: "this
	 * transport frames documents at 64 kB, so {@code envelope-too-large} cannot reach the decoder" is a
	 * reason; "it fails" is not.
	 */
	protected Set<String> skippedVectors() {
		return Set.of();
	}

	/**
	 * Waits until this transport has delivered everything it is going to for the document just sent.
	 * <p>
	 * The default does nothing, which is correct for every transport that has finished its work by the time
	 * the reactor next goes idle — {@code io.activej.promise.TestUtils.await} on the {@code send} promise has
	 * already run the eventloop to quiescence by the time this is called. A transport that waits on something
	 * the eventloop does not own (another thread, a real socket with a live keep-alive) overrides this with
	 * whatever "the round trip is over" means for it, including for the vectors that expect <b>no</b> answer,
	 * where there is nothing to wait for and a timeout is the only honest answer.
	 */
	protected void awaitDelivery() {
		// nothing: awaiting the send promise has already run the eventloop to quiescence
	}

	/**
	 * A double of this transport that can hold inbound documents and release them in an order the test
	 * chooses, or {@code null} when this subject cannot be told to reorder — a real socket cannot.
	 * <p>
	 * When it is {@code null}, {@link #responsesAreCorrelatedByIdAloneWhenTheyArriveReordered()} is skipped as
	 * a JUnit assumption, which reports it as skipped rather than as passed.
	 */
	protected @Nullable ReorderableTransport createReorderableTransport(JsonRpcDispatcher peer) {
		return null;
	}

	/** A transport whose inbound delivery order the test controls (FR-094). */
	protected interface ReorderableTransport {
		JsonRpcTransport transport();

		/** Inbound documents accumulate instead of reaching the listener. */
		void startHolding();

		int heldCount();

		/** Delivers everything held, <b>last held first</b>. */
		void releaseInReverseOrder();
	}

	// ---------------------------------------------------------------------------------------------------
	// The replay (FR-092, FR-092a, FR-092b).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theSkipSetNamesOnlyVectorsThatExist() {
		Set<String> names = new HashSet<>();
		for (Vector vector : ConformanceVectors.loadAll()) names.add(vector.name());

		for (String skipped : skippedVectors()) {
			assertTrue("skippedVectors() names '" + skipped + "', which is not a conformance vector. A stale " +
					   "skip hides the vector it was renamed from, which is exactly what FR-092b forbids",
				names.contains(skipped));
		}
		assertTrue("the vectors must cover at least FR-093's minimum: " + REQUIRED_COVERAGE,
			names.containsAll(REQUIRED_COVERAGE));
		for (String required : REQUIRED_COVERAGE) {
			assertFalse(required + " is part of FR-093's minimum coverage and may not be skipped",
				skippedVectors().contains(required));
		}
	}

	@Test
	public void everyVectorReplaysEndToEndThroughTheTransport() {
		List<Vector> vectors = ConformanceVectors.loadAll();
		assertFalse("the vector set must not be empty", vectors.isEmpty());
		Set<String> skipped = skippedVectors();

		List<String> failures = new ArrayList<>();
		List<String> replayed = new ArrayList<>();

		for (Vector vector : vectors) {
			if (skipped.contains(vector.name())) continue;
			try {
				replay(vector);
				replayed.add(vector.name());
			} catch (AssertionError | RuntimeException e) {
				failures.add(vector.name() + " — " + vector.description() + "\n\t\t" + e.getMessage());
			}
		}

		if (!failures.isEmpty()) {
			// the denominator is counted, not computed from the skip set: a skip set naming something that is
			// not a vector is its own failure, above, and must not make this message nonsense as well
			fail(failures.size() + " of " + (failures.size() + replayed.size()) + " vectors failed through " +
				 getClass().getSimpleName() + ":\n\t" + String.join("\n\t", failures));
		}
		assertEquals("every vector not explicitly skipped must have been replayed",
			vectors.size() - skipped.size(), replayed.size());
		assertTrue("no notification may have failed while replaying: " + notificationFailures,
			notificationFailures.isEmpty());
	}

	/** One vector, end to end: the request goes out through the transport and the answer comes back in. */
	private void replay(Vector vector) {
		List<byte[]> inbound = exchange(vector.request().getBytes(UTF_8));

		if (vector.expectsNoResponse()) {
			// rule 3: no document AT ALL, which is neither "null" nor "[]" — both of those are documents
			assertEquals("expected no response document at all, got " + render(inbound), 0, inbound.size());
			return;
		}

		assertEquals("expected exactly one response document, got " + render(inbound), 1, inbound.size());
		byte[] response = inbound.get(0);
		assertTrue("expected a response document, got zero bytes — which is how 'no response' is spelled",
			response.length > 0);
		String actual = new String(response, UTF_8);

		// rule 1: raw bytes only where the vector demands them
		if (vector.exactBytes()) {
			assertEquals(vector.response(), actual);
			return;
		}
		assertJsonEquals(parseJson(vector.response()), parseJson(actual));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-093's two members that no vector carries: -32602, and an application error with data.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void invalidParamsAre32602ThroughTheTransport() {
		// no vector covers -32602: it is the dispatcher's to raise, and feature 01 had no dispatcher
		List<byte[]> inbound = exchange(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subtract\",\"params\":[\"x\",\"y\"]}"));

		assertEquals(render(inbound), 1, inbound.size());
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"),
			parseJson(new String(inbound.get(0), UTF_8)));
	}

	@Test
	public void anApplicationErrorRoundTripsItsCodeMessageAndData() {
		List<byte[]> inbound = exchange(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.failedWithJsonRpc\"}"));

		assertEquals(render(inbound), 1, inbound.size());
		String response = new String(inbound.get(0), UTF_8);
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":" + APPLICATION_ERROR_JSON + '}'),
			parseJson(response));
		assertFalse("no transport may put the local exception's message on the wire", response.contains(SECRET));
	}

	@Test
	public void anInternalFailureIs32603WithNothingDerivedFromTheException() {
		List<byte[]> inbound = exchange(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"fail.thrown\"}"));

		assertEquals(render(inbound), 1, inbound.size());
		String response = new String(inbound.get(0), UTF_8);
		assertJsonEquals(
			parseJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"),
			parseJson(response));
		assertFalse(response, response.contains(SECRET));
		assertFalse("no data member may be derived from an exception", response.contains("\"data\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-094 — correlation is by id alone, and survives an order no client could have predicted.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void responsesAreCorrelatedByIdAloneWhenTheyArriveReordered() {
		JsonRpcDispatcher dispatcher = dispatcher();
		ReorderableTransport reorderable = createReorderableTransport(dispatcher);
		// reported as skipped, not as passed: a subject that cannot reorder has not asserted FR-094
		assumeTrue("this transport cannot be told to reorder its inbound documents",
			reorderable != null);

		JsonRpcClient client = JsonRpcClient.builder(Reactor.getCurrentReactor(), reorderable.transport())
			.withCodecFactory(codecFactory())
			.build();
		ConformanceApi api = client.proxy(ConformanceApi.class);

		reorderable.startHolding();
		Promise<Integer> first = api.subtract(42, 23);      // 19
		Promise<Integer> second = api.subtract(23, 42);     // -19
		Promise<Integer> third = api.subtract(100, 1);      // 99

		assertEquals("three answers are waiting to be delivered", 3, reorderable.heldCount());
		assertEquals(3, client.inFlightCount());
		assertFalse(first.isComplete());
		assertFalse(second.isComplete());
		assertFalse(third.isComplete());

		reorderable.releaseInReverseOrder();

		// nothing about the order they arrived in reaches the caller: the id did all the work
		assertEquals(Integer.valueOf(19), await(first));
		assertEquals(Integer.valueOf(-19), await(second));
		assertEquals(Integer.valueOf(99), await(third));
		assertEquals("every entry left the correlation table", 0, client.inFlightCount());

		client.close();
	}

	// ---------------------------------------------------------------------------------------------------
	// The exchange.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * One document out, everything the transport delivered back in. A fresh dispatcher and a fresh transport,
	 * so no vector inherits another's state.
	 */
	private List<byte[]> exchange(byte[] document) {
		List<byte[]> inbound = new ArrayList<>();
		JsonRpcTransport transport = createTransport(dispatcher());
		transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] delivered) {
				assertNotNull("a transport never delivers a null document", delivered);
				assertTrue("obligation 3: a transport never delivers a zero-length document — 'no response' " +
						   "is the absence of a call, not an empty one", delivered.length > 0);
				inbound.add(delivered);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				// every exchange closes its own transport, so the close is this harness's own doing
			}
		});

		try {
			await(transport.send(document));
			awaitDelivery();
		} finally {
			transport.close();
		}
		return inbound;
	}

	// ---------------------------------------------------------------------------------------------------
	// The peer every subject answers with: the service the vectors call, and nothing more.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * Exactly the methods §7's examples name, and <b>not</b> {@code foo.get} — {@code method-not-found} and
	 * the {@code -32601} element of {@code batch-mixed} depend on that method being absent, so registering it
	 * would quietly turn two vectors into assertions about nothing.
	 */
	@JsonRpcService
	public interface ConformanceApi {
		/** {@code subtract(42, 23) == 19}; the four positional/named vectors and one element of the batch. */
		@JsonRpcMethod("subtract")
		Promise<Integer> subtract(
			@JsonRpcParam("minuend") int minuend, @JsonRpcParam("subtrahend") int subtrahend);

		/** {@code sum(1, 2, 4) == 7} — the first element of {@code batch-mixed}. */
		@JsonRpcMethod("sum")
		Promise<Integer> sum(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);

		/** Answers {@code ["hello", 5]} with no params at all — the last element of {@code batch-mixed}. */
		@JsonRpcMethod("get_data")
		Promise<Data> getData();

		/** The notification inside {@code batch-mixed}: invoked, answered with nothing. */
		@JsonRpcNotification("notify_hello")
		void notifyHello(@JsonRpcParam("value") int value);

		/** The first element of {@code batch-all-notifications}. */
		@JsonRpcNotification("notify_sum")
		void notifySum(@JsonRpcParam("a") int a, @JsonRpcParam("b") int b, @JsonRpcParam("c") int c);
	}

	/**
	 * {@code get_data}'s result. The specification renders it as the heterogeneous array
	 * {@code ["hello", 5]}, which no derived codec produces, so a codec is registered for this type on
	 * {@link #codecFactory()} — the ordinary way a service declares a wire shape of its own.
	 */
	public record Data(String greeting, int answer) {}

	public static final class ConformanceApiImpl implements ConformanceApi {
		@Override
		public Promise<Integer> subtract(int minuend, int subtrahend) {
			return Promise.of(minuend - subtrahend);
		}

		@Override
		public Promise<Integer> sum(int a, int b, int c) {
			return Promise.of(a + b + c);
		}

		@Override
		public Promise<Data> getData() {
			return Promise.of(new Data("hello", 5));
		}

		@Override
		public void notifyHello(int value) {
			// §4.1: a notification has nowhere to put a result, and these two exist to be invoked and to
			// produce nothing — the vectors assert the absence of a response element, not a side effect
		}

		@Override
		public void notifySum(int a, int b, int c) {
			// as above
		}
	}

	/**
	 * The peer of every exchange: the vectors' own service plus {@link FailingApi}, whose {@code fail.*} names
	 * no vector uses and which carries the two error shapes FR-093 needs and no vector has.
	 */
	private JsonRpcDispatcher dispatcher() {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withCodecFactory(codecFactory())
			.withService(ConformanceApi.class, new ConformanceApiImpl())
			.withService(FailingApi.class, new FailingApiImpl())
			// EventloopRule installs a RETHROWING fatal-error handler, so the default route for a
			// notification's failure would fail the run at the point a server would merely log (FR-100)
			.withFailureHandler((descriptor, e) -> notificationFailures.add(descriptor.wireName() + ": " + e))
			.build();
	}

	/** The default factory plus the one wire shape {@link Data} declares. */
	private static JsonCodecFactory codecFactory() {
		return JsonCodecFactory.defaultInstance().rebuild()
			.with(Data.class, ctx -> JsonCodecs
				.ofArrayObject(JsonCodecs.ofString(), JsonCodecs.ofInteger())
				.transform(
					data -> new Object[]{data.greeting(), data.answer()},
					array -> new Data((String) array[0], (Integer) array[1])))
			.build();
	}

	// ---------------------------------------------------------------------------------------------------
	// JSON-value comparison — rules 1 and 2 of FR-092a.
	//
	// Adapted from feature 01's JsonRpcConformanceTest, which is a decoder-level test with no transport and
	// no dispatcher and must stay that way: the rules are the vector FORMAT's, so both readers of the format
	// implement them, and neither is the other's dependency.
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

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}

	/** What the transport delivered, for a failure message — the whole point of which is to be readable. */
	private static String render(List<byte[]> documents) {
		List<String> text = new ArrayList<>(documents.size());
		for (byte[] document : documents) text.add('<' + new String(document, UTF_8) + '>');
		return documents.isEmpty() ? "nothing at all" : String.join(", ", text);
	}
}
