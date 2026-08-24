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

import io.activej.common.MemSize;
import io.activej.common.inspector.AbstractInspector;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethodDescriptor;
import io.activej.jsonrpc.service.fixtures.SlowApi;
import io.activej.jsonrpc.service.fixtures.SlowApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User Story 4 — the three bounds ship enabled, are overridable, and each refuses <b>before</b> paying the
 * cost it exists to prevent (FR-050…FR-054).
 * <p>
 * Feature 019 adds the <b>fourth</b> bound to this class: the dispatcher's in-flight ceiling (FR-033…FR-036).
 * It is not an envelope bound — it lives on {@link JsonRpcDispatcher} rather than on {@link JsonRpcLimits} —
 * but the limits' home is this class, and the source feature's documented command names it, so the suite
 * lives here rather than in a fifth dispatcher test class.
 */
public class JsonRpcLimitsTest {
	// The three envelope cases below need none of these — the reactor never turns and nothing allocates a
	// ByteBuf. The in-flight suite does hold promises across dispatches, and a rule added later is a rule
	// that never protected the tests written in between (the module's convention).
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** One held-invocation fixture per test method — JUnit builds a fresh instance for each. */
	private final SlowApiImpl slow = new SlowApiImpl();
	private final List<Exception> failures = new ArrayList<>();
	private final RecordingInspector inspector = new RecordingInspector();

	// ---------------------------------------------------------------------------------------------------
	// T052 — the three defaults, their keys, and their error codes.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theThreeDefaults() {
		assertEquals(MemSize.megabytes(1).toLong(), JsonRpcLimits.MAX_BODY_SIZE.toLong());
		assertEquals(100, JsonRpcLimits.MAX_BATCH_SIZE);
		assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);
	}

	@Test
	public void allThreeAreReadableWithoutAnInstance() {
		// FR-053: a transport must be able to consult MAX_BODY_SIZE *during* accumulation, before an envelope
		// array exists — so the values cannot hang off a component this feature deliberately does not have
		for (String field : new String[]{"MAX_BODY_SIZE", "MAX_BATCH_SIZE", "MAX_JSON_DEPTH"}) {
			int modifiers;
			try {
				modifiers = JsonRpcLimits.class.getField(field).getModifiers();
			} catch (NoSuchFieldException e) {
				throw new AssertionError(field + " must be a public field", e);
			}
			assertTrue(field + " must be static", Modifier.isStatic(modifiers));
			assertTrue(field + " must be final — resolved once, never mutated (DI-5)", Modifier.isFinal(modifiers));
			assertTrue(field + " must be public", Modifier.isPublic(modifiers));
		}
		assertEquals("JsonRpcLimits must not be instantiable", 0, JsonRpcLimits.class.getConstructors().length);
		assertTrue(Modifier.isFinal(JsonRpcLimits.class.getModifiers()));
	}

	@Test
	public void eachBoundIsOverridableByItsSimpleNameKey() throws Exception {
		assertOverride("JsonRpcLimits.maxBodySize", "4mb", "MAX_BODY_SIZE", MemSize.megabytes(4).toLong());
		assertOverride("JsonRpcLimits.maxBatchSize", "7", "MAX_BATCH_SIZE", 7L);
		assertOverride("JsonRpcLimits.maxJsonDepth", "9", "MAX_JSON_DEPTH", 9L);
	}

	@Test
	public void eachBoundIsOverridableByItsFullyQualifiedKey() throws Exception {
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxBodySize", "512kb", "MAX_BODY_SIZE",
			MemSize.kilobytes(512).toLong());
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxBatchSize", "3", "MAX_BATCH_SIZE", 3L);
		assertOverride("io.activej.jsonrpc.JsonRpcLimits.maxJsonDepth", "5", "MAX_JSON_DEPTH", 5L);
	}

	@Test
	public void anEnvelopeLongerThanMaxBodySizeIsRefused() {
		int max = JsonRpcLimits.MAX_BODY_SIZE.toInt();

		byte[] tooLong = paddedEnvelope(max + 1);
		assertEquals(max + 1, tooLong.length);
		assertRefused(tooLong, JsonRpcErrors.REQUEST_TOO_LARGE);

		// exactly at the bound is accepted, or the refusal above would prove nothing
		byte[] exactlyAtTheBound = paddedEnvelope(max);
		assertEquals(max, exactlyAtTheBound.length);
		assertTrue("a document exactly at the bound must decode",
			JsonRpcDecoder.decode(exactlyAtTheBound) instanceof JsonRpcRequest);
	}

	@Test
	public void theSizeBoundIsMeasuredOnTheDecodedRegionNotTheWholeArray() {
		// decode(array, offset, length) bounds `length`, so a transport handing over a slice of a bigger
		// buffer is judged on what it actually asked to decode
		int max = JsonRpcLimits.MAX_BODY_SIZE.toInt();
		byte[] small = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}".getBytes(UTF_8);
		byte[] framed = new byte[max + 100];
		System.arraycopy(small, 0, framed, 10, small.length);

		assertTrue(JsonRpcDecoder.decode(framed, 10, small.length) instanceof JsonRpcRequest);
	}

	@Test
	public void aBatchWithMoreElementsThanMaxBatchSizeIsRefused() {
		int max = JsonRpcLimits.MAX_BATCH_SIZE;

		// one document for the whole batch, not one error per excess element (FR-054)
		assertRefused(batchOf(max + 1), JsonRpcErrors.BATCH_TOO_LARGE);
		assertRefused(batchOf(max + 50), JsonRpcErrors.BATCH_TOO_LARGE);

		JsonRpcInput atTheBound = JsonRpcDecoder.decode(batchOf(max));
		assertTrue("a batch exactly at the bound must decode", atTheBound instanceof JsonRpcBatch);
		assertEquals(max, ((JsonRpcBatch) atTheBound).size());
	}

	@Test
	public void anEnvelopeNestedDeeperThanMaxJsonDepthIsRefused() {
		int max = JsonRpcLimits.MAX_JSON_DEPTH;

		assertRefused(nestedParams(max + 1), JsonRpcErrors.NESTING_TOO_DEEP);
		assertRefused(nestedParams(max * 10), JsonRpcErrors.NESTING_TOO_DEEP);

		assertTrue("a document exactly at the bound must decode",
			JsonRpcDecoder.decode(nestedParams(max)) instanceof JsonRpcRequest);
	}

	@Test
	public void allThreeBoundsShipEnabled() {
		// FR-051: a consumer opts OUT by raising a bound, never IN by enabling one. There is no "off" value
		// and no disable switch anywhere.
		assertTrue(JsonRpcLimits.MAX_BODY_SIZE.toLong() > 0);
		assertTrue(JsonRpcLimits.MAX_BATCH_SIZE > 0);
		assertTrue(JsonRpcLimits.MAX_JSON_DEPTH > 0);
	}

	// ---------------------------------------------------------------------------------------------------
	// T053 — ordering. A bound that fires only after the work it was meant to avoid is not a bound.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theDepthRefusalHappensBeforeAnyParsing() {
		// each of these would produce a DIFFERENT error if it ever reached the parser; -32003 winning is the
		// proof that the scan ran first
		int deep = JsonRpcLimits.MAX_JSON_DEPTH + 10;

		// unparseable: nothing but opening brackets. Parsing gives -32700
		assertRefused(("[".repeat(deep)).getBytes(UTF_8), JsonRpcErrors.NESTING_TOO_DEEP);

		// parseable but invalid: a bad version member. Classification gives -32600
		assertRefused(("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"m\",\"params\":" +
					   "[".repeat(deep) + "1" + "]".repeat(deep) + "}").getBytes(UTF_8),
			JsonRpcErrors.NESTING_TOO_DEEP);

		// parseable but malformed UTF-8: the UTF-8 scan gives -32700
		byte[] withBadUtf8 = concat(
			("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":" + "[".repeat(deep)).getBytes(UTF_8),
			new byte[]{(byte) 0xC0, (byte) 0xAF},
			("]".repeat(deep) + "}").getBytes(UTF_8));
		assertRefused(withBadUtf8, JsonRpcErrors.NESTING_TOO_DEEP);
	}

	@Test
	public void theSizeRefusalHappensBeforeTheDepthScan() {
		// the cheapest check runs first: an over-size AND over-deep document is refused on size
		int deep = JsonRpcLimits.MAX_JSON_DEPTH + 10;
		String padding = "x".repeat(JsonRpcLimits.MAX_BODY_SIZE.toInt());
		byte[] both = ("[\"" + padding + "\"," + "[".repeat(deep) + "1" + "]".repeat(deep) + "]").getBytes(UTF_8);

		assertTrue(both.length > JsonRpcLimits.MAX_BODY_SIZE.toInt());
		assertRefused(both, JsonRpcErrors.REQUEST_TOO_LARGE);
	}

	@Test
	public void theBatchRefusalHappensOnTheElementThatExceedsTheBound() {
		// FR-054: the batch is refused on the element that would exceed the bound, BEFORE that element and
		// every element after it is decoded or retained. Element 101 here is unparseable, so a decoder that
		// built the whole list first would report -32700; -32002 is the proof it stopped in time.
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"},");
		}
		json.append("{\"jsonrpc\":");        // truncated — would throw if it were ever parsed
		json.append(']');

		assertRefused(json.toString().getBytes(UTF_8), JsonRpcErrors.BATCH_TOO_LARGE);
	}

	@Test
	public void theBatchRefusalDoesNotDependOnTheExcessElementsBeingValid() {
		// the same document with a well-formed element 101 must give the same answer, or the test above would
		// be passing for the wrong reason
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < JsonRpcLimits.MAX_BATCH_SIZE; i++) {
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"},");
		}
		json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"}]");

		assertRefused(json.toString().getBytes(UTF_8), JsonRpcErrors.BATCH_TOO_LARGE);
	}

	@Test
	public void aRefusedBoundCarriesNoIdAndNoData() {
		// the refusal happens before any member is read, so there is nothing to recover — and an emitted
		// error never carries data (FR-089)
		for (byte[] envelope : new byte[][]{
			paddedEnvelope(JsonRpcLimits.MAX_BODY_SIZE.toInt() + 1),
			batchOf(JsonRpcLimits.MAX_BATCH_SIZE + 1),
			nestedParams(JsonRpcLimits.MAX_JSON_DEPTH + 1)}) {
			JsonRpcMalformed malformed = (JsonRpcMalformed) JsonRpcDecoder.decode(envelope);
			assertEquals(JsonRpcId.NULL, malformed.id());
			assertTrue(malformed.error().data().isAbsent());
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// T030 — the fourth bound: the dispatcher's concurrent in-flight ceiling (FR-033…FR-036).
	//
	// Everything here runs on one reactor thread, so "concurrent" can only mean "entered and not yet
	// completed". SlowApiImpl is what makes that state reachable: its promises stay pending until the test
	// releases them, so the ceiling is filled deliberately rather than raced into.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theInFlightBoundShipsEnabledWithADefaultOfOneThousand() {
		// FR-033: an order of magnitude above maxBatchSize (100), so one full batch is never rejected by
		// default, and far below any reactor-queue comfort zone under hostile fan-out
		assertEquals(1000, JsonRpcDispatcher.MAX_IN_FLIGHT);
		assertTrue("the bound ships on; a consumer opts out by raising it, never by enabling it",
			JsonRpcDispatcher.MAX_IN_FLIGHT > 0);
		assertTrue(JsonRpcDispatcher.MAX_IN_FLIGHT > JsonRpcLimits.MAX_BATCH_SIZE);
	}

	@Test
	public void theInFlightBoundIsAPublicStaticFinalSettingLikeTheEnvelopeBounds() throws Exception {
		int modifiers = JsonRpcDispatcher.class.getField("MAX_IN_FLIGHT").getModifiers();
		assertTrue("MAX_IN_FLIGHT must be static", Modifier.isStatic(modifiers));
		assertTrue("MAX_IN_FLIGHT must be final — resolved once, never mutated (DI-5)",
			Modifier.isFinal(modifiers));
		assertTrue("MAX_IN_FLIGHT must be public", Modifier.isPublic(modifiers));
	}

	@Test
	public void theInFlightBoundIsOverridableByItsSimpleNameKey() throws Exception {
		assertDispatcherOverride("JsonRpcDispatcher.maxInFlight", "17", 17);
	}

	@Test
	public void theInFlightBoundIsOverridableByItsFullyQualifiedKey() throws Exception {
		assertDispatcherOverride("io.activej.jsonrpc.service.JsonRpcDispatcher.maxInFlight", "23", 23);
	}

	@Test
	public void withMaxInFlightOverridesTheSettingForOneDispatcher() {
		// the builder wins over the process-wide default, and the ceiling it sets is the one that fires
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> first = dispatcher.dispatch(call(1, "a"));
		assertEquals(1, slow.pendingCount());

		JsonRpcResponse rejected = single(await(dispatcher.dispatch(call(2, "b"))));
		assertServerBusy(rejected, 2);
		assertEquals("the rejected element never reached the implementation", 1, slow.pendingCount());

		slow.releaseAll();
		assertResult(single(await(first)), 1, "\"a\"");
	}

	@Test
	public void withMaxInFlightRejectsAnythingBelowOneAtBuild() {
		for (int refused : new int[]{0, -1, Integer.MIN_VALUE}) {
			try {
				JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
					.withService(SlowApi.class, slow)
					.withMaxInFlight(refused)
					.build();
				fail("maxInFlight " + refused + " must be refused at build()");
			} catch (IllegalArgumentException e) {
				assertTrue("the message must name the refused value: " + e.getMessage(),
					e.getMessage().contains(Integer.toString(refused)));
			}
		}

		// 1 is the smallest legal ceiling: a dispatcher serving one invocation at a time builds fine
		assertEquals(2, saturable(1).wireNames().size());
	}

	@Test
	public void aBatchBeyondTheCeilingIsAnsweredServerBusyOnExactlyTheExcessInDocumentOrder() {
		JsonRpcDispatcher dispatcher = saturable(2);

		Promise<byte[]> answer = dispatcher.dispatch(
			batch(call(1, "a"), call(2, "b"), call(3, "c"), call(4, "d")));

		// FR-034: elements admitted before the bound is hit proceed; only the excess is rejected
		assertEquals(2, slow.pendingCount());
		assertEquals(List.of("a", "b"), slow.pendingTags());

		slow.releaseAll();
		List<JsonRpcResponse> responses = responses(await(answer));

		assertEquals(4, responses.size());
		assertResult(responses.get(0), 1, "\"a\"");
		assertResult(responses.get(1), 2, "\"b\"");
		assertServerBusy(responses.get(2), 3);
		assertServerBusy(responses.get(3), 4);
		assertTrue("a rejection is not an application fault: " + failures, failures.isEmpty());
	}

	@Test
	public void theAdmittedPrefixOfARejectedBatchIsUnaffected() {
		// the prefix must behave exactly as it would in a batch that was never over the ceiling: same
		// results, same inspector callbacks, same order
		JsonRpcDispatcher dispatcher = saturable(3);

		Promise<byte[]> answer = dispatcher.dispatch(
			batch(call(1, "a"), call(2, "b"), call(3, "c"), call(4, "d"), call(5, "e")));

		assertEquals(List.of("a", "b", "c"), slow.pendingTags());
		assertEquals("only the admitted elements were announced to the inspector",
			List.of("slow.call", "slow.call", "slow.call"), inspector.requests);
		assertEquals(2, inspector.rejected);

		// released out of order: the prefix's answers still come back in document order
		slow.release(2);
		slow.release(0);
		slow.releaseAll();

		List<JsonRpcResponse> responses = responses(await(answer));
		assertEquals(5, responses.size());
		assertResult(responses.get(0), 1, "\"a\"");
		assertResult(responses.get(1), 2, "\"b\"");
		assertResult(responses.get(2), 3, "\"c\"");
		assertServerBusy(responses.get(3), 4);
		assertServerBusy(responses.get(4), 5);
	}

	@Test
	public void aRejectedNotificationEmitsNothingAtAllAndNeverReachesTheFailureHandler() {
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> held = dispatcher.dispatch(call(1, "a"));
		assertEquals(1, slow.pendingCount());

		// FR-035: §4.1 forbids answering a notification, at the bound exactly as everywhere else
		assertEquals("a rejected notification produces zero bytes, not an error document",
			0, await(dispatcher.dispatch(notification("b"))).length);
		assertEquals("the notification never reached the implementation", 1, slow.pendingCount());
		assertEquals(List.of("call(a)"), slow.invocations());
		assertTrue("load shedding is not an application fault: " + failures, failures.isEmpty());

		// … but it is counted, on the same aggregate a rejected request moves
		assertEquals(1, inspector.rejected);
		assertEquals("a rejection announces no request", List.of("slow.call"), inspector.requests);

		slow.releaseAll();
		assertResult(single(await(held)), 1, "\"a\"");
	}

	@Test
	public void aBatchOfNothingButRejectedNotificationsIsZeroBytes() {
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> held = dispatcher.dispatch(call(1, "a"));

		byte[] answer = await(dispatcher.dispatch(
			batch(notification("b"), notification("c"), notification("d"))));

		assertEquals("no response document exists, and it is not \"[]\"", 0, answer.length);
		assertEquals(3, inspector.rejected);
		assertTrue(failures.isEmpty());

		slow.releaseAll();
		await(held);
	}

	@Test
	public void dispatchNeverCompletesExceptionallyWhenEveryElementIsRejected() {
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> held = dispatcher.dispatch(call(1, "a"));

		// FR-036: a rejected element is an ordinary error response element, never a failed promise
		List<JsonRpcResponse> responses = responses(await(dispatcher.dispatch(
			batch(call(2, "b"), call(3, "c"), call(4, "d"), call(5, "e"), call(6, "f")))));
		assertEquals(5, responses.size());
		for (int i = 0; i < responses.size(); i++) {
			assertServerBusy(responses.get(i), i + 2);
		}

		// and a lone rejected request is one ordinary error document
		assertServerBusy(single(await(dispatcher.dispatch(call(7, "g")))), 7);

		slow.releaseAll();
		assertResult(single(await(held)), 1, "\"a\"");
	}

	@Test
	public void theCounterDecrementsOnSuccessfulCompletion() {
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> first = dispatcher.dispatch(call(1, "a"));
		assertServerBusy(single(await(dispatcher.dispatch(call(2, "b")))), 2);

		slow.releaseAll();
		assertResult(single(await(first)), 1, "\"a\"");

		// the slot came back: the very next call is admitted, not rejected
		Promise<byte[]> third = dispatcher.dispatch(call(3, "c"));
		assertEquals(1, slow.pendingCount());
		slow.releaseAll();
		assertResult(single(await(third)), 3, "\"c\"");
		assertEquals("exactly one rejection over the whole test", 1, inspector.rejected);
	}

	@Test
	public void theCounterDecrementsOnFailedCompletion() {
		JsonRpcDispatcher dispatcher = saturable(1);

		Promise<byte[]> first = dispatcher.dispatch(call(1, "a"));
		slow.failAll(new ExpectedException("the handler failed"));
		assertError(single(await(first)), 1, JsonRpcErrors.INTERNAL_ERROR);

		// a failed invocation must free its slot exactly as a successful one does, or the ceiling leaks
		// upwards under load until nothing is ever admitted again
		Promise<byte[]> second = dispatcher.dispatch(call(2, "b"));
		assertEquals(1, slow.pendingCount());
		slow.releaseAll();
		assertResult(single(await(second)), 2, "\"b\"");
		assertEquals("no rejection was ever due", 0, inspector.rejected);
	}

	@Test
	public void aFailedNotificationAlsoFreesItsSlot() {
		JsonRpcDispatcher dispatcher = saturable(1);

		// an admitted notification holds its slot until the invocation completes, so the dispatch promise
		// is still pending here — awaiting it before the release would hang the loop, not the assertion
		Promise<byte[]> admittedNotification = dispatcher.dispatch(notification("a"));
		assertEquals(1, slow.pendingCount());
		slow.failAll(new ExpectedException("the notification failed"));
		assertEquals("a notification still has nowhere to put its answer", 0, await(admittedNotification).length);
		assertEquals("the failure went to the failure handler, as it always has", 1, failures.size());

		Promise<byte[]> admitted = dispatcher.dispatch(call(1, "b"));
		assertEquals(1, slow.pendingCount());
		slow.releaseAll();
		assertResult(single(await(admitted)), 1, "\"b\"");
		assertEquals(0, inspector.rejected);
	}

	@Test
	public void aLookupMissAndAParamsFailureNeverTouchTheCounter() {
		// the counter brackets the handler invocation only: -32601 and -32602 invoke nothing, so they can
		// neither consume a slot nor be rejected by the bound
		JsonRpcDispatcher dispatcher = saturable(1);

		for (int i = 0; i < 20; i++) {
			assertError(single(await(dispatcher.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"no.such\"}").getBytes(UTF_8)))),
				i, JsonRpcErrors.METHOD_NOT_FOUND);
			assertError(single(await(dispatcher.dispatch(
				("{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"slow.call\",\"params\":[1,2,3]}")
					.getBytes(UTF_8)))),
				i, JsonRpcErrors.INVALID_PARAMS);
		}

		Promise<byte[]> admitted = dispatcher.dispatch(call(99, "a"));
		assertEquals("the ceiling is still free after 40 non-invoking elements", 1, slow.pendingCount());
		slow.releaseAll();
		assertResult(single(await(admitted)), 99, "\"a\"");
		assertEquals(0, inspector.rejected);
	}

	// ---------------------------------------------------------------------------------------------------
	// In-flight helpers.
	// ---------------------------------------------------------------------------------------------------

	/** A dispatcher over the held-invocation fixture, with every observation seam this suite reads. */
	private JsonRpcDispatcher saturable(int maxInFlight) {
		return JsonRpcDispatcher.builder(Reactor.getCurrentReactor())
			.withService(SlowApi.class, slow)
			.withMaxInFlight(maxInFlight)
			.withFailureHandler((descriptor, e) -> failures.add(e))
			.withInspector(inspector)
			.build();
	}

	private static byte[] call(long id, String tag) {
		return ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"slow.call\",\"params\":[\"" + tag + "\"]}")
			.getBytes(UTF_8);
	}

	private static byte[] notification(String tag) {
		return ("{\"jsonrpc\":\"2.0\",\"method\":\"slow.notify\",\"params\":[\"" + tag + "\"]}").getBytes(UTF_8);
	}

	private static byte[] batch(byte[]... elements) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < elements.length; i++) {
			if (i > 0) json.append(',');
			json.append(new String(elements[i], UTF_8));
		}
		return json.append(']').toString().getBytes(UTF_8);
	}

	private static List<JsonRpcResponse> responses(byte[] document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document);
		if (!(input instanceof JsonRpcBatch batch)) {
			throw new AssertionError("expected a batch response, got " + input);
		}
		List<JsonRpcResponse> responses = new ArrayList<>(batch.size());
		for (JsonRpcDecoded element : batch.elements()) {
			responses.add((JsonRpcResponse) element);
		}
		return responses;
	}

	private static JsonRpcResponse single(byte[] document) {
		JsonRpcInput input = JsonRpcDecoder.decode(document);
		if (!(input instanceof JsonRpcResponse response)) {
			throw new AssertionError("expected one response, got " + input);
		}
		return response;
	}

	private static void assertResult(JsonRpcResponse response, long id, String rawResult) {
		assertEquals(new JsonRpcId.Num(id), response.id());
		assertFalse("expected a result, got " + response.error(), response.isError());
		assertEquals(rawResult, new String(response.result().toByteArray(), UTF_8));
	}

	private static void assertError(JsonRpcResponse response, long id, JsonRpcError expected) {
		assertEquals(new JsonRpcId.Num(id), response.id());
		assertTrue("expected an error, got " + response.result(), response.isError());
		assertEquals(expected.code(), response.error().code());
	}

	/** {@code -32005 Server busy}, verbatim: the fixed message, no {@code data}, and the element's own id. */
	private static void assertServerBusy(JsonRpcResponse response, long id) {
		assertError(response, id, JsonRpcErrors.SERVER_BUSY);
		assertEquals("Server busy", response.error().message());
		assertTrue("a rejection discloses nothing beyond the fixed message",
			response.error().data().isAbsent());
	}

	/**
	 * Reloads {@link JsonRpcDispatcher} in a child loader with {@code key} set, so its {@code static final}
	 * setting is resolved again — the same trick the envelope bounds above use.
	 */
	private static void assertDispatcherOverride(String key, String value, int expected) throws Exception {
		System.setProperty(key, value);
		try {
			Class<?> reloaded = Class.forName("io.activej.jsonrpc.service.JsonRpcDispatcher", true,
				new ModuleReloadingClassLoader(JsonRpcLimitsTest.class.getClassLoader()));
			assertFalse("the class must have been re-initialised", reloaded == JsonRpcDispatcher.class);
			assertEquals(key + '=' + value, expected, (int) (Integer) reloaded.getField("MAX_IN_FLIGHT").get(null));
		} finally {
			System.clearProperty(key);
		}
	}

	/**
	 * The observation seam this suite reads: what was announced as a request, and how many elements were
	 * shed at the bound. The rejection count is <b>aggregate</b> — a rejected element carries no descriptor
	 * by construction, because it never reached a handler.
	 */
	private static final class RecordingInspector
		extends AbstractInspector<JsonRpcDispatcher.Inspector>
		implements JsonRpcDispatcher.Inspector {
		private final List<String> requests = new ArrayList<>();
		private int rejected;

		@Override
		public void onRequest(JsonRpcMethodDescriptor descriptor) {
			requests.add(descriptor.wireName());
		}

		@Override
		public void onResponse(JsonRpcMethodDescriptor descriptor, long durationMillis) {}

		@Override
		public void onError(JsonRpcMethodDescriptor descriptor, int errorCode, long durationMillis) {}

		@Override
		public void onMethodNotFound(String requestedName) {}

		@Override
		public void onMalformed() {}

		@Override
		public void onRejected() {
			rejected++;
		}
	}

	// ---------------------------------------------------------------------------------------------------

	private static void assertRefused(byte[] envelope, JsonRpcError expected) {
		JsonRpcInput input = JsonRpcDecoder.decode(envelope);
		if (!(input instanceof JsonRpcMalformed malformed)) {
			throw new AssertionError("expected " + expected.message() + ", but it decoded to " + input);
		}
		assertSame("expected " + expected.message() + ", got " + malformed.error(), expected, malformed.error());
	}

	/** A well-formed request padded with a string parameter so the whole document is exactly {@code size} bytes. */
	private static byte[] paddedEnvelope(int size) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[\"";
		String suffix = "\"]}";
		int padding = size - prefix.length() - suffix.length();
		if (padding < 0) throw new IllegalArgumentException("size too small: " + size);
		return (prefix + "x".repeat(padding) + suffix).getBytes(UTF_8);
	}

	/** A batch of {@code count} well-formed notifications. */
	private static byte[] batchOf(int count) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < count; i++) {
			if (i > 0) json.append(',');
			json.append("{\"jsonrpc\":\"2.0\",\"method\":\"n\"}");
		}
		return json.append(']').toString().getBytes(UTF_8);
	}

	/** A well-formed request whose {@code params} nest to exactly {@code depth} total document levels. */
	private static byte[] nestedParams(int depth) {
		int arrays = depth - 1;                 // the envelope object itself is level 1
		return ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":" +
				"[".repeat(arrays) + "]".repeat(arrays) + "}").getBytes(UTF_8);
	}

	private static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] part : parts) total += part.length;
		byte[] joined = new byte[total];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, joined, offset, part.length);
			offset += part.length;
		}
		return joined;
	}

	/**
	 * Reloads {@link JsonRpcLimits} in a child loader with {@code key} set, so the {@code static final} is
	 * resolved again. Without this the settings keys are asserted by nobody and a typo in one is invisible.
	 */
	private static void assertOverride(String key, String value, String field, long expected) throws Exception {
		System.setProperty(key, value);
		try {
			Class<?> reloaded = Class.forName("io.activej.jsonrpc.JsonRpcLimits", true,
				new ModuleReloadingClassLoader(JsonRpcLimitsTest.class.getClassLoader()));
			assertFalse("the class must have been re-initialised", reloaded == JsonRpcLimits.class);

			Object resolved = reloaded.getField(field).get(null);
			long actual = resolved instanceof Integer integer ? integer : ((MemSize) resolved).toLong();
			assertEquals(key + '=' + value, expected, actual);
		} finally {
			System.clearProperty(key);
		}
	}

	/** Defines {@code io.activej.jsonrpc.*} itself so their static initialisers run again. */
	private static final class ModuleReloadingClassLoader extends ClassLoader {
		private ModuleReloadingClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("io.activej.jsonrpc.") || name.endsWith("Test")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					String resource = name.replace('.', '/') + ".class";
					try (InputStream in = getParent().getResourceAsStream(resource)) {
						if (in == null) throw new ClassNotFoundException(name);
						byte[] bytes = in.readAllBytes();
						loaded = defineClass(name, bytes, 0, bytes.length);
					} catch (IOException e) {
						throw new ClassNotFoundException(name, e);
					}
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}

	static {
		// a guard against a future default change silently invalidating every fixture above
		if (JsonRpcLimits.MAX_BATCH_SIZE < 2 || JsonRpcLimits.MAX_JSON_DEPTH < 4) {
			fail("the fixtures in this class assume workable defaults");
		}
	}
}
