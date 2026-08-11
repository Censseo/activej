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

import com.dslplatform.json.JsonWriter;
import io.activej.json.JsonCodec;
import io.activej.json.JsonCodecs;
import io.activej.json.JsonUtils;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-024, FR-040…FR-045, FR-081, FR-082 — the envelope encoder.
 * <p>
 * Every assertion here is on the <b>exact bytes</b>. The encoder's whole reason to fix a member order is to
 * make the rendered document testable byte for byte, so comparing parsed values instead would test nothing.
 */
public class JsonRpcEncoderTest {

	private static final JsonCodec<List<Integer>> INTS = JsonCodecs.ofList(JsonCodecs.ofInteger());

	// ---------------------------------------------------------------------------------------------------
	// T019 — fixed member order, absent optionals omitted, "jsonrpc":"2.0" on every envelope.
	// The orders come from contracts/protocol-core-api.md and data-model.md §6:
	//   request       jsonrpc, id, method, params
	//   notification  jsonrpc,     method, params
	//   response      jsonrpc, id, result | error
	//   error object  code, message, data
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aRequestRendersItsMembersInOrder() {
		assertEncodes(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subtract\",\"params\":[42,23]}",
			new JsonRpcRequest(new JsonRpcId.Num(1), "subtract",
				new JsonRpcPayload.Encoded<>(INTS, List.of(42, 23))));
	}

	@Test
	public void aRequestOmitsAnAbsentParamsMemberEntirely() {
		// FR-041: absent optionals are omitted, never emitted as null
		String rendered = encode(new JsonRpcRequest(new JsonRpcId.Num(1), "ping"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", rendered);
		assertFalse(rendered.contains("params"));
		assertFalse(rendered.contains("null"));
	}

	@Test
	public void aRequestRendersEachIdForm() {
		assertTrue(encode(new JsonRpcRequest(new JsonRpcId.Str("abc"), "ping")).contains("\"id\":\"abc\","));
		assertTrue(encode(new JsonRpcRequest(new JsonRpcId.Num(-9223372036854775808L), "ping"))
			.contains("\"id\":-9223372036854775808,"));
		assertTrue(encode(new JsonRpcRequest(JsonRpcId.NULL, "ping")).contains("\"id\":null,"));
	}

	@Test
	public void aStringIdIsEscapedNotEmittedVerbatim() {
		String rendered = encode(new JsonRpcRequest(new JsonRpcId.Str("a\"b\\c\nd"), "ping"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"a\\\"b\\\\c\\nd\",\"method\":\"ping\"}", rendered);
	}

	@Test
	public void aMethodNameIsEscapedToo() {
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"method\":\"a\\\"b\"}",
			encode(new JsonRpcNotification("a\"b")));
	}

	@Test
	public void aNotificationRendersItsMembersInOrder() {
		assertEncodes(
			"{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1,2,3,4,5]}",
			new JsonRpcNotification("update", new JsonRpcPayload.Encoded<>(INTS, List.of(1, 2, 3, 4, 5))));
	}

	@Test
	public void aSuccessfulResponseRendersItsMembersInOrder() {
		assertEncodes(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":19}",
			JsonRpcResponse.ofResult(new JsonRpcId.Num(1),
				new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 19)));
	}

	@Test
	public void aFailedResponseRendersTheErrorObjectInOrder() {
		assertEncodes(
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}",
			JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR));
	}

	@Test
	public void anErrorObjectOmitsAbsentDataAndRendersItWhenPresent() {
		String withoutData = encode(JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.INVALID_REQUEST));
		assertFalse(withoutData.contains("data"));
		assertFalse(withoutData.contains("null"));

		JsonRpcError withData = JsonRpcErrors.ofAny(-32003, "Nesting too deep",
			new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 64));
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32003,\"message\":\"Nesting too deep\",\"data\":64}}",
			encode(JsonRpcResponse.ofError(new JsonRpcId.Num(1), withData)));
	}

	@Test
	public void anErrorMessageIsEscaped() {
		JsonRpcError error = JsonRpcErrors.of(-1, "he said \"no\"");
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"he said \\\"no\\\"\"}}",
			encode(JsonRpcResponse.ofError(new JsonRpcId.Num(1), error)));
	}

	@Test
	public void everyEnvelopeCarriesTheVersionMember() {
		List<JsonRpcMessage> everyKind = List.of(
			new JsonRpcRequest(new JsonRpcId.Num(1), "sum"),
			new JsonRpcNotification("update"),
			JsonRpcResponse.ofResult(new JsonRpcId.Num(1), new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 1)),
			JsonRpcResponse.ofError(JsonRpcId.NULL, JsonRpcErrors.PARSE_ERROR));

		for (JsonRpcMessage message : everyKind) {
			String rendered = encode(message);
			assertTrue(rendered, rendered.startsWith("{\"jsonrpc\":\"2.0\","));
		}

		// and inside a batch, on every element
		String batch = new String(JsonRpcEncoder.encode(new JsonRpcOutput.Batch(everyKind)), UTF_8);
		assertEquals(4, countOccurrences(batch, "\"jsonrpc\":\"2.0\""));
	}

	// ---------------------------------------------------------------------------------------------------
	// T020 — no id member on a notification, zero bytes for None, batch-ness at size one.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aNotificationEmitsNoIdMemberAtAll() {
		// FR-042: not "id":null, not "id":"" — no id member whatsoever
		String rendered = encode(new JsonRpcNotification("update",
			new JsonRpcPayload.Encoded<>(INTS, List.of(1, 2))));
		assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1,2]}", rendered);
		assertFalse(rendered.contains("id"));
		assertFalse(rendered.contains("null"));
	}

	@Test
	public void noneRendersZeroBytes() {
		// FR-044: "nothing to send" is zero bytes — not "null", not "{}", not "[]"
		byte[] rendered = JsonRpcEncoder.encode(JsonRpcOutput.NONE);
		assertEquals(0, rendered.length);
		assertArrayEquals(new byte[0], rendered);

		byte[] again = JsonRpcEncoder.encode(new JsonRpcOutput.None());
		assertEquals(0, again.length);
	}

	@Test
	public void aBatchOfOneStillRendersAsAnArray() {
		// FR-043: batch-ness survives rendering exactly one response
		assertEquals(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}]",
			new String(JsonRpcEncoder.encode(new JsonRpcOutput.Batch(
				List.of(JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.METHOD_NOT_FOUND)))), UTF_8));
	}

	@Test
	public void aBatchOfSeveralRendersACommaSeparatedArray() {
		assertEquals(
			"[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":7},{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":8}]",
			new String(JsonRpcEncoder.encode(new JsonRpcOutput.Batch(List.of(
				JsonRpcResponse.ofResult(new JsonRpcId.Num(1), new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 7)),
				JsonRpcResponse.ofResult(new JsonRpcId.Num(2), new JsonRpcPayload.Encoded<>(JsonCodecs.ofInteger(), 8))
			))), UTF_8));
	}

	@Test
	public void aSingleRendersOneBareDocumentNotAnArray() {
		String rendered = new String(JsonRpcEncoder.encode(
			new JsonRpcOutput.Single(new JsonRpcNotification("update"))), UTF_8);
		assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"update\"}", rendered);
	}

	// ---------------------------------------------------------------------------------------------------
	// T021 — a Raw payload is re-emitted byte-identically; an Encoded payload goes through its own codec.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aRawPayloadIsReEmittedByteIdentically() {
		// the slice carries whitespace and member order that a decode/re-encode round trip would normalise
		// away — so an implementation that parsed and rebuilt the value fails here rather than passing by luck
		byte[] envelope = "{\"params\":[ 1 ,  2,3 , { \"b\" : true, \"a\":null } ]}".getBytes(UTF_8);
		int start = "{\"params\":".length();
		int end = envelope.length - 1;
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, start, end);
		String slice = new String(raw.toByteArray(), UTF_8);
		assertEquals("[ 1 ,  2,3 , { \"b\" : true, \"a\":null } ]", slice);

		String rendered = encode(new JsonRpcRequest(new JsonRpcId.Num(1), "echo", raw));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":" + slice + "}", rendered);

		// and the captured bytes appear verbatim in the output, byte for byte
		byte[] out = JsonRpcEncoder.encode(new JsonRpcRequest(new JsonRpcId.Num(1), "echo", raw));
		int at = indexOf(out, raw.toByteArray());
		assertTrue("the captured slice must appear verbatim in the output", at >= 0);
	}

	@Test
	public void aRawPayloadWithMultiByteUtf8SurvivesVerbatim() {
		byte[] envelope = "{\"params\":[\"héllo 😀\"]}".getBytes(UTF_8);
		int start = 10;
		int end = envelope.length - 1;
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, start, end);

		byte[] out = JsonRpcEncoder.encode(new JsonRpcNotification("echo", raw));
		assertTrue(indexOf(out, raw.toByteArray()) >= 0);
		assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":[\"héllo 😀\"]}", new String(out, UTF_8));
	}

	@Test
	public void aRawResultAndRawErrorDataAreReEmittedVerbatimToo() {
		byte[] envelope = "{\"result\":{ \"z\":1 , \"a\":2 }}".getBytes(UTF_8);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(envelope, 10, envelope.length - 1);
		assertEquals("{ \"z\":1 , \"a\":2 }", new String(raw.toByteArray(), UTF_8));

		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{ \"z\":1 , \"a\":2 }}",
			encode(JsonRpcResponse.ofResult(new JsonRpcId.Num(1), raw)));

		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"m\",\"data\":{ \"z\":1 , \"a\":2 }}}",
			encode(JsonRpcResponse.ofError(new JsonRpcId.Num(1), JsonRpcErrors.of(-1, "m", raw))));
	}

	@Test
	public void anEncodedPayloadIsWrittenThroughItsOwnCodec() {
		JsonCodec<Map<String, Integer>> codec = JsonCodecs.ofMap(JsonCodecs.ofInteger());
		JsonRpcPayload params = new JsonRpcPayload.Encoded<>(codec, Map.of("subtrahend", 23));

		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"subtract\",\"params\":{\"subtrahend\":23}}",
			encode(new JsonRpcRequest(new JsonRpcId.Num(3), "subtract", params)));
	}

	// ---------------------------------------------------------------------------------------------------
	// T022 — the encoder returns an owned byte[]; the shared ThreadLocal JsonWriter never leaks between calls.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void theEncoderNeverHandsBackAJsonWriter() {
		// FR-045: JsonUtils keeps its JsonWriter in a ThreadLocal and hands the same instance to the next
		// caller on this thread, so a returned writer is invalidated by the very next encode
		for (Method method : JsonRpcEncoder.class.getMethods()) {
			if (JsonWriter.class.isAssignableFrom(method.getReturnType())) {
				fail("JsonRpcEncoder." + method.getName() + " returns a JsonWriter");
			}
		}
		assertEquals(byte[].class, methodReturn("encode", JsonRpcOutput.class));
		assertEquals(byte[].class, methodReturn("encode", JsonRpcMessage.class));
	}

	@Test
	public void twoEncodesOnOneThreadDoNotClobberEachOther() {
		byte[] first = JsonRpcEncoder.encode(new JsonRpcRequest(new JsonRpcId.Num(1), "first",
			new JsonRpcPayload.Encoded<>(INTS, List.of(1, 1, 1))));
		byte[] firstSnapshot = first.clone();

		byte[] second = JsonRpcEncoder.encode(new JsonRpcRequest(new JsonRpcId.Num(2), "second",
			new JsonRpcPayload.Encoded<>(INTS, List.of(2, 2, 2, 2, 2, 2, 2, 2))));

		assertNotSame("each encode must own its array", first, second);
		assertArrayEquals("the first result must survive the second encode", firstSnapshot, first);
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"first\",\"params\":[1,1,1]}",
			new String(first, UTF_8));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"second\",\"params\":[2,2,2,2,2,2,2,2]}",
			new String(second, UTF_8));

		// a third, shorter encode must not leave a tail of the second behind
		byte[] third = JsonRpcEncoder.encode(new JsonRpcNotification("x"));
		assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"x\"}", new String(third, UTF_8));
		assertArrayEquals(firstSnapshot, first);
	}

	@Test
	public void theReturnedArrayDoesNotAliasTheWritersInternalBuffer() {
		byte[] rendered = JsonRpcEncoder.encode(new JsonRpcNotification("ping"));
		byte[] snapshot = rendered.clone();

		// drive the shared ThreadLocal writer hard through an unrelated JsonUtils call
		JsonUtils.toJsonBytes(INTS, List.of(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9));
		JsonUtils.toJsonBytes(JsonCodecs.ofString(), "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");

		assertArrayEquals("a previously returned array must be immune to later writer use", snapshot, rendered);
		assertEquals(rendered.length, new String(rendered, UTF_8).getBytes(UTF_8).length);
	}

	/**
	 * The trap this pins: {@code JsonUtils.toJsonWriter} calls {@code reset()} on the <b>same</b> ThreadLocal
	 * writer the outer encode is already writing into. An encoder that rendered an {@code Encoded} payload by
	 * calling {@code payload.toByteArray()} — which goes through {@code JsonUtils.toJsonBytes} — would wipe
	 * everything written so far and emit a truncated document. The payload must be written through
	 * {@code Encoded.codec()} straight into the current writer instead.
	 */
	@Test
	public void anEncodedPayloadIsNotRenderedThroughJsonUtilsMidWrite() {
		byte[] out = JsonRpcEncoder.encode(new JsonRpcRequest(new JsonRpcId.Num(42), "aMethodWithALongishName",
			new JsonRpcPayload.Encoded<>(INTS, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))));

		String rendered = new String(out, UTF_8);
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"aMethodWithALongishName\"," +
			"\"params\":[1,2,3,4,5,6,7,8,9,10]}",
			rendered);
		assertTrue("the envelope prefix must not have been wiped by a nested reset()",
			rendered.startsWith("{\"jsonrpc\":\"2.0\""));
	}

	@Test
	public void theCodecComposesWithJsonUtilsRatherThanReplacingIt() {
		// FR-081: the encoder is JsonCodec-shaped, so JsonUtils.toJsonBytes drives it and no parallel entry
		// point exists. A consumer holding only the codec must get the same bytes as JsonRpcEncoder.encode.
		JsonRpcMessage message = JsonRpcResponse.ofResult(new JsonRpcId.Str("a"),
			new JsonRpcPayload.Encoded<>(JsonCodecs.ofString(), "ok"));

		assertArrayEquals(
			JsonRpcEncoder.encode(message),
			JsonUtils.toJsonBytes(JsonRpcEncoder.MESSAGE_CODEC, message));
		assertArrayEquals(
			JsonRpcEncoder.encode(new JsonRpcOutput.Single(message)),
			JsonUtils.toJsonBytes(JsonRpcEncoder.OUTPUT_CODEC, new JsonRpcOutput.Single(message)));
		assertEquals(
			"{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"result\":\"ok\"}",
			JsonUtils.toJson(JsonRpcEncoder.MESSAGE_CODEC, message));
	}

	@Test
	public void theCodecsRefuseToDecode() {
		// FR-081: decoding cannot be a JsonCodec — it defers payloads, detects duplicate members and yields
		// per-element batch outcomes. These codecs are encode-only, and say so rather than half-working.
		assertThrows(() -> JsonRpcEncoder.MESSAGE_CODEC.read(null));
		assertThrows(() -> JsonRpcEncoder.OUTPUT_CODEC.read(null));
		assertThrows(() -> JsonRpcEncoder.ERROR_CODEC.read(null));
	}

	// ---------------------------------------------------------------------------------------------------

	private static String encode(JsonRpcMessage message) {
		return new String(JsonRpcEncoder.encode(message), UTF_8);
	}

	private static void assertEncodes(String expected, JsonRpcMessage message) {
		assertEquals(expected, encode(message));
		// the two entry points must agree: a Single wraps nothing around the document
		assertEquals(expected, new String(JsonRpcEncoder.encode(new JsonRpcOutput.Single(message)), UTF_8));
	}

	private static Class<?> methodReturn(String name, Class<?>... parameters) {
		try {
			return JsonRpcEncoder.class.getMethod(name, parameters).getReturnType();
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	private static void assertThrows(ThrowingRunnable runnable) {
		try {
			runnable.run();
		} catch (UnsupportedOperationException expected) {
			return;
		} catch (Exception e) {
			throw new AssertionError("expected UnsupportedOperationException, got " + e, e);
		}
		fail("expected UnsupportedOperationException");
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
