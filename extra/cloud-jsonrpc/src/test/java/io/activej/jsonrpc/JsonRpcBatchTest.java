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

import io.activej.common.exception.MalformedDataException;
import io.activej.json.JsonCodecs;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User Story 3 — batch semantics (§6, §7; FR-038, FR-039, FR-043, FR-044, FR-087).
 *
 * <h2>The dispatcher is not this feature's</h2>
 * There is no production step from a decoded {@link JsonRpcInput} to a {@link JsonRpcOutput}: choosing what
 * to answer is method dispatch, which Contract 1 explicitly places in feature 03. {@link #answer} below is a
 * <b>test-only</b> stand-in — just enough to assert on rendered documents — and it deliberately lives here
 * rather than on {@link JsonRpcBatch} or {@link JsonRpcOutput}.
 */
public class JsonRpcBatchTest {

	// ---------------------------------------------------------------------------------------------------
	// T043 — a mixed batch decodes element by element (US3 scenario 1).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aMixedBatchOfSixDecodesToSixElementsWithExactlyTwoNotifications() {
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"sum","params":[1,2,4],"id":"1"},\
			{"jsonrpc":"2.0","method":"notify_hello","params":[7]},\
			{"jsonrpc":"2.0","method":"subtract","params":[42,23],"id":"2"},\
			{"jsonrpc":"2.0","method":"foo.get","params":{"name":"myself"},"id":"5"},\
			{"jsonrpc":"2.0","method":"get_data","id":"9"},\
			{"jsonrpc":"2.0","method":"notify_sum","params":[1,2,3]}]""");

		assertEquals(6, batch.size());
		assertEquals(6, batch.elements().size());
		assertEquals(2, count(batch, JsonRpcNotification.class));
		assertEquals(4, count(batch, JsonRpcRequest.class));

		// element order is the document's
		assertEquals(new JsonRpcId.Str("1"), ((JsonRpcRequest) batch.elements().get(0)).id());
		assertEquals("notify_hello", ((JsonRpcNotification) batch.elements().get(1)).method());
		assertEquals(new JsonRpcId.Str("9"), ((JsonRpcRequest) batch.elements().get(4)).id());
		assertEquals("notify_sum", ((JsonRpcNotification) batch.elements().get(5)).method());
	}

	@Test
	public void everyElementsPayloadIsCapturedFromTheOneEnvelopeArray() throws MalformedDataException {
		byte[] envelope = utf8("""
			[{"jsonrpc":"2.0","method":"a","params":[1,2,4],"id":1},\
			{"jsonrpc":"2.0","method":"b","params":{"name":"myself"},"id":2}]""");
		JsonRpcBatch batch = (JsonRpcBatch) JsonRpcDecoder.decode(envelope);

		JsonRpcRequest first = (JsonRpcRequest) batch.elements().get(0);
		JsonRpcRequest second = (JsonRpcRequest) batch.elements().get(1);

		// the offsets are absolute into the ONE array the caller handed over — no per-element copy
		assertSame(envelope, ((JsonRpcPayload.Raw) first.params()).view().array());
		assertSame(envelope, ((JsonRpcPayload.Raw) second.params()).view().array());

		assertEquals(List.of(1, 2, 4), first.params().decode(JsonCodecs.ofList(JsonCodecs.ofInteger())));
		assertEquals("{\"name\":\"myself\"}", new String(second.params().toByteArray(), UTF_8));
	}

	// ---------------------------------------------------------------------------------------------------
	// T044 — one bad element does not poison its siblings (FR-038, US3 scenario 2).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void oneInvalidElementYieldsItsOwnErrorAndLeavesTheRestAlone() {
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"sum","params":[1,2,4],"id":"1"},\
			{"jsonrpc":"1.0","method":"bad","id":"2"},\
			{"jsonrpc":"2.0","method":"notify_hello","params":[7]}]""");

		assertEquals(3, batch.size());
		assertTrue(batch.elements().get(0) instanceof JsonRpcRequest);
		assertTrue(batch.elements().get(2) instanceof JsonRpcNotification);

		JsonRpcMalformed bad = (JsonRpcMalformed) batch.elements().get(1);
		assertSame(JsonRpcErrors.INVALID_REQUEST, bad.error());
		assertEquals("the element's own id is still recovered", new JsonRpcId.Str("2"), bad.id());
	}

	@Test
	public void severalInvalidElementsEachGetTheirOwnOutcome() {
		// §7 "rpc call with invalid Batch (but not empty)" — three non-objects, three individual errors
		JsonRpcBatch batch = decodeBatch("[1,2,3]");
		assertEquals(3, batch.size());
		for (JsonRpcDecoded element : batch.elements()) {
			assertSame(JsonRpcErrors.INVALID_REQUEST, ((JsonRpcMalformed) element).error());
			assertEquals(JsonRpcId.NULL, ((JsonRpcMalformed) element).id());
		}

		String rendered = new String(JsonRpcEncoder.encode(answer(batch, request -> null)), UTF_8);
		assertEquals("[" + INVALID_REQUEST_DOCUMENT + "," + INVALID_REQUEST_DOCUMENT + "," +
					 INVALID_REQUEST_DOCUMENT + "]", rendered);
	}

	@Test
	public void everyNonObjectElementShapeIsRefusedIndividually() {
		JsonRpcBatch batch = decodeBatch("[1,\"x\",true,false,null,[],{},-1.5e10]");
		assertEquals(8, batch.size());
		for (int i = 0; i < 7; i++) {
			assertTrue("element " + i, batch.elements().get(i) instanceof JsonRpcMalformed
									   || batch.elements().get(i) instanceof JsonRpcMessage);
		}
		// only the object element reaches envelope classification; it fails there for its own reason
		assertSame(JsonRpcErrors.INVALID_REQUEST, ((JsonRpcMalformed) batch.elements().get(6)).error());
		for (int i : new int[]{0, 1, 2, 3, 4, 5, 7}) {
			assertSame("element " + i, JsonRpcErrors.INVALID_REQUEST,
				((JsonRpcMalformed) batch.elements().get(i)).error());
		}
	}

	@Test
	public void invalidJsonInsideABatchIsASingleParseErrorForTheWholeDocument() {
		// §7 "rpc call Batch, invalid JSON" — the array structure itself is unreadable past the break, so
		// there are no per-element outcomes to report and the answer is one object, not an array
		JsonRpcInput input = JsonRpcDecoder.decode(utf8("""
			[{"jsonrpc":"2.0","method":"sum","params":[1,2,4],"id":"1"},{"jsonrpc":"2.0","method"]"""));

		assertTrue(input instanceof JsonRpcMalformed);
		assertSame(JsonRpcErrors.PARSE_ERROR, ((JsonRpcMalformed) input).error());
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}",
			new String(JsonRpcEncoder.encode(answer(input, request -> null)), UTF_8));
	}

	// ---------------------------------------------------------------------------------------------------
	// T045 — [] versus [1] (FR-039, FR-043, US3 scenarios 3-4).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anEmptyArrayIsASingleErrorAndNotABatch() {
		JsonRpcInput input = JsonRpcDecoder.decode(utf8("[]"));

		assertFalse("[] must NOT produce a JsonRpcBatch — not even an empty one", input instanceof JsonRpcBatch);
		assertTrue(input instanceof JsonRpcMalformed);

		JsonRpcMalformed malformed = (JsonRpcMalformed) input;
		assertSame(JsonRpcErrors.INVALID_REQUEST, malformed.error());
		assertEquals(JsonRpcId.NULL, malformed.id());

		// and it renders as ONE OBJECT, not an array of one
		String rendered = new String(JsonRpcEncoder.encode(answer(input, request -> null)), UTF_8);
		assertEquals(INVALID_REQUEST_DOCUMENT, rendered);
		assertFalse(rendered.startsWith("["));
	}

	@Test
	public void aSingleElementArrayIsAGenuineBatchAndRendersAsAnArrayOfOne() {
		JsonRpcInput input = JsonRpcDecoder.decode(utf8("[1]"));

		assertTrue("[1] IS a batch — of one element, which happens to be invalid", input instanceof JsonRpcBatch);
		JsonRpcBatch batch = (JsonRpcBatch) input;
		assertEquals(1, batch.size());
		assertSame(JsonRpcErrors.INVALID_REQUEST, ((JsonRpcMalformed) batch.elements().get(0)).error());

		// FR-043: batch-ness survives rendering exactly one response
		String rendered = new String(JsonRpcEncoder.encode(answer(input, request -> null)), UTF_8);
		assertEquals("[" + INVALID_REQUEST_DOCUMENT + "]", rendered);
	}

	@Test
	public void theTwoDocumentsDifferOnlyInBatchNessAndSaySoOnTheWire() {
		// the whole point of the distinction: the same error, one wrapped and one not
		String empty = new String(JsonRpcEncoder.encode(answer(JsonRpcDecoder.decode(utf8("[]")), r -> null)), UTF_8);
		String one = new String(JsonRpcEncoder.encode(answer(JsonRpcDecoder.decode(utf8("[1]")), r -> null)), UTF_8);

		assertEquals(INVALID_REQUEST_DOCUMENT, empty);
		assertEquals("[" + INVALID_REQUEST_DOCUMENT + "]", one);
		assertEquals("the difference is exactly the brackets", "[" + empty + "]", one);
	}

	// ---------------------------------------------------------------------------------------------------
	// T046 — an all-notification batch says nothing at all (FR-044, US3 scenario 5).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anAllNotificationBatchRendersZeroBytes() {
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"notify_sum","params":[1,2,4]},\
			{"jsonrpc":"2.0","method":"notify_hello","params":[7]}]""");

		assertEquals(2, batch.size());
		assertEquals(2, count(batch, JsonRpcNotification.class));

		JsonRpcOutput output = answer(batch, request -> null);
		assertSame("nothing to say is a state, not an empty list", JsonRpcOutput.NONE, output);
		assertArrayEquals(new byte[0], JsonRpcEncoder.encode(output));
	}

	@Test
	public void zeroBytesIsDistinguishableFromTheEmptyArrayDocument() {
		byte[] fromNotifications = JsonRpcEncoder.encode(
			answer(JsonRpcDecoder.decode(utf8("[{\"jsonrpc\":\"2.0\",\"method\":\"n\"}]")), r -> null));
		byte[] fromEmptyArray = JsonRpcEncoder.encode(
			answer(JsonRpcDecoder.decode(utf8("[]")), r -> null));

		assertEquals("an all-notification batch answers with nothing at all", 0, fromNotifications.length);
		assertTrue("an empty array answers with one error object", fromEmptyArray.length > 0);
		assertEquals(INVALID_REQUEST_DOCUMENT, new String(fromEmptyArray, UTF_8));

		// and neither is "[]", which is itself a -32600 on the wire and must never be emitted
		assertFalse("[]".equals(new String(fromNotifications, UTF_8)));
		assertFalse("[]".equals(new String(fromEmptyArray, UTF_8)));
	}

	@Test
	public void aBatchOfNotificationsAndOneRequestAnswersOnlyTheRequest() {
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"notify_sum","params":[1,2,4]},\
			{"jsonrpc":"2.0","method":"get_data","id":9},\
			{"jsonrpc":"2.0","method":"notify_hello","params":[7]}]""");

		String rendered = new String(JsonRpcEncoder.encode(answer(batch, request ->
			JsonRpcResponse.ofResult(request.id(), new JsonRpcPayload.Encoded<>(JsonCodecs.ofString(), "ok")))),
			UTF_8);
		assertEquals("[{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":\"ok\"}]", rendered);
	}

	// ---------------------------------------------------------------------------------------------------
	// T047 — no homogeneity requirement (FR-087).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aBatchMayMixRequestsNotificationsAndResponses() {
		// feature 06's bidirectional WebSocket transport depends on exactly this
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"sum","params":[1,2],"id":1},\
			{"jsonrpc":"2.0","method":"notify","params":[1]},\
			{"jsonrpc":"2.0","id":2,"result":19},\
			{"jsonrpc":"2.0","id":3,"error":{"code":-32601,"message":"Method not found"}}]""");

		assertEquals(4, batch.size());
		assertTrue(batch.elements().get(0) instanceof JsonRpcRequest);
		assertTrue(batch.elements().get(1) instanceof JsonRpcNotification);

		JsonRpcResponse ok = (JsonRpcResponse) batch.elements().get(2);
		assertEquals(new JsonRpcId.Num(2), ok.id());
		assertFalse(ok.isError());
		assertEquals("19", new String(ok.result().toByteArray(), UTF_8));

		JsonRpcResponse failed = (JsonRpcResponse) batch.elements().get(3);
		assertTrue(failed.isError());
		assertNotNull(failed.error());
		assertEquals(-32601, failed.error().code());
		assertEquals("Method not found", failed.error().message());
	}

	@Test
	public void aResponseShapedElementIsNotForcedThroughRequestLogic() {
		// it has no `method`, so classifying it as a request/notification would be the bug
		JsonRpcBatch batch = decodeBatch("[{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":19}]");
		assertTrue(batch.elements().get(0) instanceof JsonRpcResponse);
	}

	// ---------------------------------------------------------------------------------------------------
	// T048 — a nested array element (spec § Edge Cases).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aNestedArrayElementIsInvalidForThatElementOnly() {
		JsonRpcBatch batch = decodeBatch("[[1,2],{\"jsonrpc\":\"2.0\",\"method\":\"m\"}]");

		assertEquals(2, batch.size());
		assertSame("an array is not a Request object", JsonRpcErrors.INVALID_REQUEST,
			((JsonRpcMalformed) batch.elements().get(0)).error());
		assertEquals("m", ((JsonRpcNotification) batch.elements().get(1)).method());
	}

	@Test
	public void aBatchNestedInsideABatchIsRefusedElementWise() {
		// [[…]] must not typecheck as a batch of batches — the inner array is just an invalid element
		JsonRpcBatch batch = decodeBatch("[[{\"jsonrpc\":\"2.0\",\"method\":\"m\"}]]");
		assertEquals(1, batch.size());
		assertSame(JsonRpcErrors.INVALID_REQUEST, ((JsonRpcMalformed) batch.elements().get(0)).error());
	}

	@Test
	public void aDeeplyStructuredElementValueDoesNotConfuseTheElementWalk() {
		// the element walk leans on the parser's skip(), never on bracket counting, so structural characters
		// inside strings and nested containers cannot break element boundaries
		JsonRpcBatch batch = decodeBatch("""
			[{"jsonrpc":"2.0","method":"a","params":["],[{},"],"id":1},\
			{"jsonrpc":"2.0","method":"b","params":{"x":[{"y":"}]"}]},"id":2},\
			{"jsonrpc":"2.0","method":"c"}]""");

		assertEquals(3, batch.size());
		assertEquals("[\"],[{},\"]",
			new String(((JsonRpcRequest) batch.elements().get(0)).params().toByteArray(), UTF_8));
		assertEquals("{\"x\":[{\"y\":\"}]\"}]}",
			new String(((JsonRpcRequest) batch.elements().get(1)).params().toByteArray(), UTF_8));
		assertEquals("c", ((JsonRpcNotification) batch.elements().get(2)).method());
	}

	@Test
	public void whitespaceAroundElementsAndSeparatorsIsTolerated() {
		JsonRpcBatch batch = decodeBatch("""
			[ \s{"jsonrpc":"2.0","method":"a","params":[ 1 , 2 ],"id":1} ,\s
			  {"jsonrpc":"2.0","method":"b"} \s]""");

		assertEquals(2, batch.size());
		assertEquals("[ 1 , 2 ]", new String(((JsonRpcRequest) batch.elements().get(0)).params().toByteArray(), UTF_8));
		assertEquals("b", ((JsonRpcNotification) batch.elements().get(1)).method());
	}

	@Test
	public void trailingDataAfterABatchIsAParseError() {
		JsonRpcInput input = JsonRpcDecoder.decode(utf8("[{\"jsonrpc\":\"2.0\",\"method\":\"m\"}] junk"));
		assertTrue(input instanceof JsonRpcMalformed);
		assertSame(JsonRpcErrors.PARSE_ERROR, ((JsonRpcMalformed) input).error());
	}

	// ---------------------------------------------------------------------------------------------------
	// The test-only stand-in for feature 03's dispatcher.
	// ---------------------------------------------------------------------------------------------------

	/** What a test scenario chooses to answer to a request; {@code null} means "this scenario answers none". */
	@FunctionalInterface
	private interface Responder {
		@Nullable JsonRpcResponse respondTo(JsonRpcRequest request);
	}

	/**
	 * Maps a decoded input to the document a caller would send back.
	 * <p>
	 * <b>Test-only.</b> The production API deliberately has no such step — deciding what to answer is method
	 * dispatch, which belongs to feature 03 (Contract 1). What it encodes here is only the two rules this
	 * feature <i>does</i> own: a notification is answered by nothing, and batch-ness is preserved.
	 */
	private static JsonRpcOutput answer(JsonRpcInput input, Responder responder) {
		List<JsonRpcMessage> responses = new ArrayList<>();
		if (input instanceof JsonRpcBatch batch) {
			for (JsonRpcDecoded element : batch.elements()) collect(element, responder, responses);
			return responses.isEmpty() ? JsonRpcOutput.NONE : new JsonRpcOutput.Batch(responses);
		}
		collect((JsonRpcDecoded) input, responder, responses);
		return responses.isEmpty() ? JsonRpcOutput.NONE : new JsonRpcOutput.Single(responses.get(0));
	}

	private static void collect(JsonRpcDecoded element, Responder responder, List<JsonRpcMessage> into) {
		switch (element) {
			case JsonRpcMalformed malformed -> into.add(malformed.toResponse());
			case JsonRpcNotification ignored -> {
				// §4.1: a notification is answered by nothing at all
			}
			case JsonRpcRequest request -> {
				JsonRpcResponse response = responder.respondTo(request);
				if (response != null) into.add(response);
			}
			case JsonRpcResponse ignored -> {
				// an incoming response is an answer, not a question
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------

	private static final String INVALID_REQUEST_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}";

	private static byte[] utf8(String json) {
		return json.getBytes(UTF_8);
	}

	private static JsonRpcBatch decodeBatch(String json) {
		JsonRpcInput input = JsonRpcDecoder.decode(utf8(json));
		if (!(input instanceof JsonRpcBatch batch)) {
			fail("expected a batch, got " + input + " for <" + json + '>');
			throw new AssertionError();
		}
		return batch;
	}

	private static int count(JsonRpcBatch batch, Class<?> type) {
		int count = 0;
		for (JsonRpcDecoded element : batch.elements()) {
			if (type.isInstance(element)) count++;
		}
		return count;
	}
}
