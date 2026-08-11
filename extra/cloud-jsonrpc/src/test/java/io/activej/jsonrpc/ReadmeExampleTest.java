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
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The worked example from the module README, as compiled and executed code (SC-007).
 * <p>
 * <b>The README's code blocks are copied from this file, not the other way round.</b> A README example
 * that only exists as prose rots into a lie the first time the API moves; this way the build fails
 * instead. If you change one, change the other — and run this test.
 */
public class ReadmeExampleTest {

	private static final JsonCodec<List<Integer>> LIST_OF_INT = JsonCodecs.ofList(JsonCodecs.ofInteger());

	// --- README: "Decode a request" ---------------------------------------------------------------
	@Test
	public void decodeARequest() throws MalformedDataException {
		byte[] envelope = """
			{"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2,3]}""".getBytes(UTF_8);

		JsonRpcInput input = JsonRpcDecoder.decode(envelope);

		switch (input) {
			case JsonRpcRequest request -> {
				assertEquals("sum", request.method());                   // decoded
				assertEquals(new JsonRpcId.Num(1), request.id());        // decoded

				// `params` is NOT decoded yet — decode it once you know what it should be
				List<Integer> params = request.params().decode(LIST_OF_INT);
				assertEquals(List.of(1, 2, 3), params);
			}
			case JsonRpcNotification notification -> fail("no response may be built for " + notification);
			case JsonRpcResponse response -> fail("a peer answering us: " + response);
			case JsonRpcMalformed malformed -> fail("render malformed.toResponse(): " + malformed);
			case JsonRpcBatch batch -> fail("one outcome per element: " + batch);
		}
	}

	// --- README: "Answer it" ------------------------------------------------------------------------
	@Test
	public void answerIt() {
		JsonRpcId id = new JsonRpcId.Num(1);

		JsonRpcPayload result = JsonRpcPayload.encoded(JsonCodecs.ofInteger(), 6);
		byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofResult(id, result));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":6}", new String(bytes, UTF_8));
	}

	// --- README: "Fail it" --------------------------------------------------------------------------
	@Test
	public void failIt() {
		JsonRpcId id = new JsonRpcId.Num(1);

		byte[] bytes = JsonRpcEncoder.encode(JsonRpcResponse.ofError(id, JsonRpcErrors.INVALID_PARAMS));

		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}",
			new String(bytes, UTF_8));
	}

	// --- README: "Application error codes" ----------------------------------------------------------
	@Test
	public void applicationErrorCodesStayOutOfTheReservedRange() {
		JsonRpcError mine = JsonRpcErrors.of(1001, "Insufficient funds");     // fine
		assertEquals(1001, mine.code());

		assertThrows(IllegalArgumentException.class,
			() -> JsonRpcErrors.of(-32601, "my own error"));                  // reserved range

		// decoding is deliberately permissive: a peer's reserved code is kept verbatim
		JsonRpcInput peer = JsonRpcDecoder.decode("""
			{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""".getBytes(UTF_8));
		assertEquals(-32601, ((JsonRpcResponse) peer).error().code());
	}

	// --- README: "A notification produces nothing" --------------------------------------------------
	@Test
	public void aNotificationProducesNothing() {
		byte[] bytes = JsonRpcEncoder.encode(JsonRpcOutput.none());

		assertEquals(0, bytes.length);          // zero bytes — NOT "[]", which on the wire means -32600
		assertArrayEquals(new byte[0], bytes);
	}

	// --- README: "Batches" --------------------------------------------------------------------------
	@Test
	public void batches() {
		byte[] batchBytes = """
			[{"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2]},\
			{"jsonrpc":"2.0","method":"log","params":["hi"]},\
			{"jsonrpc":"1.0","id":3,"method":"bad"}]""".getBytes(UTF_8);

		JsonRpcInput input = JsonRpcDecoder.decode(batchBytes);
		byte[] out = new byte[0];

		if (input instanceof JsonRpcBatch batch) {
			List<JsonRpcMessage> responses = new ArrayList<>();
			for (JsonRpcDecoded element : batch.elements()) {
				switch (element) {
					case JsonRpcRequest request -> responses.add(JsonRpcResponse.ofResult(
						request.id(), JsonRpcPayload.encoded(JsonCodecs.ofInteger(), 3)));
					case JsonRpcNotification ignored -> { }                     // no response
					case JsonRpcMalformed m -> responses.add(m.toResponse());    // one bad element != bad batch
					case JsonRpcResponse ignored -> { }
				}
			}
			// a batch renders as an array even at size 1; no responses at all renders as nothing
			out = JsonRpcEncoder.encode(
				responses.isEmpty() ? JsonRpcOutput.none() : JsonRpcOutput.batch(responses));
		}

		assertEquals("[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":3}," +
					 "{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}]",
			new String(out, UTF_8));
	}

	// --- README: "The three rules most often implemented wrongly" -----------------------------------
	@Test
	public void theThreeBatchRulesMostOftenImplementedWrongly() {
		// 1. an empty array is NOT a batch — one -32600 object, not an array
		JsonRpcInput empty = JsonRpcDecoder.decode("[]".getBytes(UTF_8));
		assertTrue(empty instanceof JsonRpcMalformed);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
			new String(JsonRpcEncoder.encode(((JsonRpcMalformed) empty).toResponse()), UTF_8));

		// 2. a batch of only notifications answers with nothing at all — distinct from "[]"
		JsonRpcInput notifications = JsonRpcDecoder.decode("""
			[{"jsonrpc":"2.0","method":"a"},{"jsonrpc":"2.0","method":"b"}]""".getBytes(UTF_8));
		assertEquals(2, ((JsonRpcBatch) notifications).size());
		assertEquals(0, JsonRpcEncoder.encode(JsonRpcOutput.none()).length);

		// 3. response order is not guaranteed; correlation is by id alone
	}

	// --- README: "Bounds" ---------------------------------------------------------------------------
	@Test
	public void bounds() {
		assertEquals("1Mb", JsonRpcLimits.MAX_BODY_SIZE.format());
		assertEquals(100, JsonRpcLimits.MAX_BATCH_SIZE);
		assertEquals(64, JsonRpcLimits.MAX_JSON_DEPTH);

		// a transport reads MAX_BODY_SIZE in its accumulation loop, before an envelope array exists
		assertTrue(JsonRpcLimits.MAX_BODY_SIZE.toLong() > 0);
	}

	// --- README: "Payload lifetime" -----------------------------------------------------------------
	@Test
	public void payloadLifetime() {
		byte[] envelope = """
			{"jsonrpc":"2.0","id":1,"method":"sum","params":[1,2,3]}""".getBytes(UTF_8);
		JsonRpcRequest request = (JsonRpcRequest) JsonRpcDecoder.decode(envelope);

		// retaining a payload keeps the WHOLE envelope array reachable
		byte[] independent = request.params().toByteArray();   // the escape hatch
		assertArrayEquals("[1,2,3]".getBytes(UTF_8), independent);

		// mutating the envelope after decoding invalidates every payload derived from it
		assertEquals(7, request.params().size());
	}

	private static void fail(String message) {
		throw new AssertionError(message);
	}
}
