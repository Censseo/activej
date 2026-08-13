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

import io.activej.jsonrpc.JsonRpcBatch;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcOutput;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * User story 4, the dispatch half — a batch is fanned out element by element and the responses of the
 * elements that produce one are assembled (FR-052…FR-055).
 *
 * <h2>How "concurrently" is asserted without a clock</h2>
 * A sequential implementation would invoke element <i>n</i>&nbsp;+&nbsp;1 only after element <i>n</i>'s promise
 * had settled. {@link SlowApiImpl} settles nothing until the test says so, so
 * {@link #everyElementIsDispatchedBeforeAnyOfThemCompletes()} can assert that <b>all three</b> implementations
 * ran while <b>none</b> of their promises had completed. That is the observable content of "independently and
 * concurrently" (FR-053) on a single-threaded reactor, and it needs neither a sleep nor a timing assumption.
 *
 * <h2>Request order is asserted, and deliberately not relied on</h2>
 * FR-053a makes the assembled array deterministic — request order, skipping the elements that produce nothing
 * — so a batch test can be an equality assertion instead of a search. The <i>guarantee</i> stays absent: the
 * three promises below are settled in <b>reverse</b> order and the document still comes back in request order,
 * which is exactly the property a consumer must not lean on. Correlation by {@code id} under reordering is
 * asserted at the other end of the wire, by {@code AbstractTransportConformanceTest}.
 */
public class JsonRpcBatchDispatchTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	/** Asserts nothing today — this feature allocates no {@code ByteBuf} — and is declared for FR-098. */
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private SlowApiImpl slow;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		slow = new SlowApiImpl();
		dispatcher = JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, slow)
			.build();
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-053 — independent, concurrent fan-out.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void everyElementIsDispatchedBeforeAnyOfThemCompletes() {
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"slow.answer\",\"params\":[\"b\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"slow.answer\",\"params\":[\"c\"]}]"));

		assertEquals("a sequential fan-out would have invoked only the first element (FR-053)",
			List.of("a", "b", "c"), slow.invocations());
		assertFalse("the batch cannot be complete while its elements are not", batch.isComplete());

		// settled in reverse, so that request order below cannot be an accident of completion order
		slow.complete("c");
		assertFalse("the batch completes only when EVERY element has (FR-053)", batch.isComplete());
		slow.complete("b");
		assertFalse(batch.isComplete());
		slow.complete("a");

		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"a!\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"b!\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":\"c!\"}",
			inner(text(await(batch))));
	}

	@Test
	public void oneFailingElementDoesNotDisturbTheOthers() {
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"slow.answer\",\"params\":[\"boom\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"slow.answer\",\"params\":[\"c\"]}]"));

		slow.complete("a");
		slow.fail("boom");
		slow.complete("c");

		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"a!\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":\"c!\"}",
			inner(text(await(batch))));
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-053a — request order, which nothing downstream relies on.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void responsesAreEmittedInRequestOrderWhateverOrderTheySettleIn() {
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":\"first\",\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":\"second\",\"method\":\"slow.answer\",\"params\":[\"b\"]}]"));

		slow.complete("b");
		slow.complete("a");

		List<String> ids = idsOf(text(await(batch)));
		assertEquals("the answers keep the order of the questions, not of their settlement (FR-053a)",
			List.of("first", "second"), ids);
	}

	@Test
	public void anElementThatProducesNothingIsSkippedRatherThanPaddedWithNull() {
		// the notification sits between the two requests: request order is the order of the PRODUCING
		// elements, so the array is two long and nothing marks the gap
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"slow.answer\",\"params\":[\"b\"]}]"));

		slow.complete("a");
		slow.complete("b");

		assertEquals(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"a!\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"b!\"}]",
			text(await(batch)));
		assertEquals(List.of("a", "ping", "b"), slow.invocations());
	}

	// ---------------------------------------------------------------------------------------------------
	// The mixed batch of FR-052…FR-054: one of each kind, in one array.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aMixedBatchProducesExactlyTheElementsItShould() {
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}," +
			"{\"foo\":\"boo\"}]"));

		slow.complete("a");

		assertEquals("three elements in, two responses out: the notification answers nothing and the " +
					 "malformed element answers -32600",
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"a!\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}]",
			text(await(batch)));
		assertEquals("the notification is invoked even though it is answered with nothing",
			List.of("a", "ping"), slow.invocations());
	}

	@Test
	public void aMalformedElementIsAnsweredWithoutInvokingAnything() {
		// FR-051: feature 01 already produced the normative error object; the dispatcher re-derives nothing
		byte[] response = await(dispatcher.dispatch(utf8("[1,2,3]")));

		assertEquals(
			"[{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}]",
			text(response));
		assertEquals(List.of(), slow.invocations());
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-054 — nothing produced is zero bytes, and zero bytes is not "[]".
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aBatchOfOnlyNotificationsProducesAZeroLengthDocument() {
		byte[] response = await(dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}]")));

		// the whole point of FR-054, asserted on the raw length rather than on anything "empty-ish": the
		// two-byte document "[]" is itself a -32600 by §6, so emitting it here would be emitting an error
		assertEquals("an all-notification batch answers nothing at all", 0, response.length);
		assertNotEquals("zero bytes is not the two-byte document []", 2, response.length);
		assertFalse("[]".equals(text(response)));
		assertEquals(List.of("ping", "ping"), slow.invocations());
	}

	@Test
	public void aBatchOfOnlyNotificationsAndInboundResponsesProducesAZeroLengthDocument() {
		byte[] response = await(dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":42}]")));

		assertEquals(0, response.length);
	}

	@Test
	public void theStructuredEntryPointSaysTheSameThingWithJsonRpcOutputNone() {
		JsonRpcBatch batch = (JsonRpcBatch) JsonRpcDecoder.decode(utf8(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}]"));

		JsonRpcOutput output = await(dispatcher.dispatch(batch));

		assertTrue("nothing produced is JsonRpcOutput.none(), never an empty batch (FR-054)",
			output instanceof JsonRpcOutput.None);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-052 — an inbound response inside a batch.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anInboundResponseElementProducesNothingAndIsNotAnError() {
		Promise<byte[]> batch = dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slow.answer\",\"params\":[\"a\"]}," +
			"{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":\"the peer's own answer\"}]"));

		slow.complete("a");
		String response = text(await(batch));

		assertEquals("a bidirectional transport carries the peer's answers on the same channel: the response " +
					 "element is neither answered nor turned into an error (FR-052)",
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"a!\"}]", response);
		assertFalse(response, response.contains("error"));
		assertFalse(response, response.contains("99"));
	}

	@Test
	public void anInboundErrorResponseElementIsEquallySilent() {
		byte[] response = await(dispatcher.dispatch(utf8(
			"[{\"jsonrpc\":\"2.0\",\"id\":99,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}]")));

		assertEquals("an error the peer sent is an answer, not a question", 0, response.length);
	}

	// ---------------------------------------------------------------------------------------------------
	// FR-055 — no bound of the dispatcher's own.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theDispatcherAddsNoBatchBoundOfItsOwn() {
		// JsonRpcLimits.maxBatchSize is 100 and belongs to the decoder; a batch under it is dispatched whole
		StringBuilder document = new StringBuilder("[");
		for (int i = 0; i < 100; i++) {
			if (i != 0) document.append(',');
			document.append("{\"jsonrpc\":\"2.0\",\"method\":\"slow.ping\"}");
		}
		byte[] response = await(dispatcher.dispatch(utf8(document.append(']').toString())));

		assertEquals(0, response.length);
		assertEquals("every element of a 100-element batch reached the implementation (FR-055)",
			100, slow.invocations().size());
	}

	// ---------------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * A service that answers nothing until the test says so — the seam that makes "concurrently" observable.
	 */
	@JsonRpcService("slow")
	public interface SlowApi {
		@JsonRpcMethod("answer")
		Promise<String> answer(@JsonRpcParam("value") String value);

		@JsonRpcNotification("ping")
		void ping();
	}

	public static final class SlowApiImpl implements SlowApi {
		private final List<String> invocations = new ArrayList<>();
		private final Map<String, SettablePromise<String>> pending = new LinkedHashMap<>();

		@Override
		public Promise<String> answer(String value) {
			invocations.add(value);
			SettablePromise<String> promise = new SettablePromise<>();
			pending.put(value, promise);
			return promise;
		}

		@Override
		public void ping() {
			invocations.add("ping");
		}

		/** Settles one outstanding invocation with {@code value + '!'}. */
		void complete(String value) {
			take(value).set(value + '!');
		}

		/** Fails one outstanding invocation with an exception carrying nothing the wire may see. */
		void fail(String value) {
			take(value).setException(new IllegalStateException("the far side of " + value + " gave up"));
		}

		List<String> invocations() {
			return invocations;
		}

		private SettablePromise<String> take(String value) {
			SettablePromise<String> promise = pending.remove(value);
			if (promise == null) throw new AssertionError("nothing is pending for " + value);
			return promise;
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}

	private static String text(byte[] document) {
		return new String(document, UTF_8);
	}

	/** The batch array's contents, so an assertion can read as the list of elements it is about. */
	private static String inner(String batch) {
		assertTrue(batch, batch.startsWith("[") && batch.endsWith("]"));
		return batch.substring(1, batch.length() - 1);
	}

	/** Every {@code "id":<value>} of a batch document, in emitted order. */
	private static List<String> idsOf(String batch) {
		List<String> ids = new ArrayList<>();
		int from = 0;
		while (true) {
			int at = batch.indexOf("\"id\":\"", from);
			if (at < 0) return ids;
			int start = at + "\"id\":\"".length();
			int end = batch.indexOf('"', start);
			ids.add(batch.substring(start, end));
			from = end;
		}
	}
}
