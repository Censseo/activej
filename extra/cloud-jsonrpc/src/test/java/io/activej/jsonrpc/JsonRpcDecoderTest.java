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

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User Story 1 — decoding a well-formed envelope, with its payload left undecoded (FR-030…FR-033, SC-003,
 * SC-008).
 * <p>
 * The malformed-input taxonomy is <b>not</b> this class's subject; that is {@code JsonRpcErrorPathTest}
 * (Phase 4). What is asserted here about failure is only that {@code decode} stays <b>total</b> — no parser
 * exception escapes for any byte sequence.
 */
public class JsonRpcDecoderTest {

	private static final JsonCodec<List<Integer>> INTS = JsonCodecs.ofList(JsonCodecs.ofInteger());

	// ---------------------------------------------------------------------------------------------------
	// T024 — happy path.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void decodesARequestLeavingItsParamsUndecoded() throws MalformedDataException {
		byte[] envelope = utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}");
		JsonRpcRequest request = (JsonRpcRequest) decodeOne(envelope);

		assertEquals(new JsonRpcId.Num(1), request.id());
		assertEquals("sum", request.method());
		assertTrue("the payload must still be raw", request.params() instanceof JsonRpcPayload.Raw);
		assertEquals("[1,2,3]", new String(request.params().toByteArray(), UTF_8));

		// and only now, once the method is known, does the caller pick a codec
		assertEquals(List.of(1, 2, 3), request.params().decode(INTS));
	}

	@Test
	public void decodesANotificationWhenNoIdMemberIsPresent() {
		byte[] envelope = utf8("{\"jsonrpc\":\"2.0\",\"method\":\"update\",\"params\":[1,2,3,4,5]}");
		JsonRpcDecoded decoded = decodeOne(envelope);

		assertTrue("no id member means a notification, not a request with a null id",
			decoded instanceof JsonRpcNotification);
		JsonRpcNotification notification = (JsonRpcNotification) decoded;
		assertEquals("update", notification.method());
		assertEquals("[1,2,3,4,5]", new String(notification.params().toByteArray(), UTF_8));
	}

	@Test
	public void anExplicitNullIdIsARequestNotANotification() {
		// FR-012: absence of an id is the notification type; an explicit null id is a request whose id is null
		JsonRpcDecoded decoded = decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}"));
		assertTrue(decoded instanceof JsonRpcRequest);
		assertEquals(JsonRpcId.NULL, ((JsonRpcRequest) decoded).id());
	}

	@Test
	public void decodesEachIdForm() {
		assertEquals(new JsonRpcId.Str("abc"),
			((JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"p\"}"))).id());
		assertEquals(new JsonRpcId.Num(1),
			((JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"p\"}"))).id());
		assertEquals(new JsonRpcId.Num(-42),
			((JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":-42,\"method\":\"p\"}"))).id());
		assertEquals(new JsonRpcId.Num(Long.MAX_VALUE),
			((JsonRpcRequest) decodeOne(
				utf8("{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807,\"method\":\"p\"}"))).id());
		assertEquals(new JsonRpcId.Num(Long.MIN_VALUE),
			((JsonRpcRequest) decodeOne(
				utf8("{\"jsonrpc\":\"2.0\",\"id\":-9223372036854775808,\"method\":\"p\"}"))).id());
		assertEquals(JsonRpcId.NULL,
			((JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"p\"}"))).id());

		// a string id carrying an escape decodes to the unescaped value — it is a value, not a byte range
		assertEquals(new JsonRpcId.Str("a\"b"),
			((JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":\"a\\\"b\",\"method\":\"p\"}"))).id());
	}

	@Test
	public void absentParamsAndExplicitNullParamsAreDistinguishable() {
		// the whole reason JsonRpcPayload has an Absent state separate from a Raw over the four bytes `null`
		JsonRpcRequest absent = (JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"p\"}"));
		JsonRpcRequest explicitNull =
			(JsonRpcRequest) decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"p\",\"params\":null}"));

		assertTrue(absent.params().isAbsent());
		assertSame(JsonRpcPayload.absent(), absent.params());

		assertFalse(explicitNull.params().isAbsent());
		assertTrue(explicitNull.params() instanceof JsonRpcPayload.Raw);
		assertEquals("null", new String(explicitNull.params().toByteArray(), UTF_8));

		assertFalse(absent.equals(explicitNull));
	}

	@Test
	public void decodesASuccessfulResponseLeavingItsResultUndecoded() throws MalformedDataException {
		JsonRpcDecoded decoded = decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":19}"));
		assertTrue(decoded instanceof JsonRpcResponse);

		JsonRpcResponse response = (JsonRpcResponse) decoded;
		assertEquals(new JsonRpcId.Num(1), response.id());
		assertNull(response.error());
		assertFalse(response.isError());
		assertTrue(response.result() instanceof JsonRpcPayload.Raw);
		assertEquals("19", new String(response.result().toByteArray(), UTF_8));
		assertEquals(Integer.valueOf(19), response.result().decode(JsonCodecs.ofInteger()));
	}

	@Test
	public void decodesAnErrorResponseWithItsCodeAndMessageButNotItsData() throws MalformedDataException {
		JsonRpcDecoded decoded = decodeOne(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"));
		assertTrue(decoded instanceof JsonRpcResponse);

		JsonRpcResponse response = (JsonRpcResponse) decoded;
		assertEquals(new JsonRpcId.Str("1"), response.id());
		assertTrue(response.isError());
		assertTrue(response.result().isAbsent());

		JsonRpcError error = response.error();
		assertNotNull(error);
		assertEquals(-32601, error.code());
		assertEquals("Method not found", error.message());
		assertTrue("an omitted data member is absent, not null", error.data().isAbsent());

		// FR-031: error.data is left undecoded, exactly like params and result
		JsonRpcResponse withData = (JsonRpcResponse) decodeOne(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"m\",\"data\":{\"why\":[1,2]}}}"));
		assertNotNull(withData.error());
		assertFalse(withData.error().data().isAbsent());
		assertEquals("{\"why\":[1,2]}", new String(withData.error().data().toByteArray(), UTF_8));
	}

	@Test
	public void aPeersReservedErrorCodeIsKeptVerbatim() {
		// FR-016: refuse a reserved code locally, accept it from a peer — never discard what the peer meant
		JsonRpcResponse response = (JsonRpcResponse) decodeOne(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}"));
		assertNotNull(response.error());
		assertEquals(-32700, response.error().code());
		assertTrue(response.error().isReserved());
	}

	@Test
	public void decodesFromAnOffsetIntoALargerArray() {
		byte[] framed = utf8("XXXX{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}YYYY");
		JsonRpcInput input = JsonRpcDecoder.decode(framed, 4, framed.length - 8);

		JsonRpcRequest request = (JsonRpcRequest) input;
		assertEquals("sum", request.method());
		assertEquals("[1,2,3]", new String(request.params().toByteArray(), UTF_8));
	}

	@Test
	public void leadingAndTrailingJsonWhitespaceAreTolerated() {
		// a transport handing over an HTTP body that ends in a newline must not be told its JSON is malformed
		JsonRpcRequest request = (JsonRpcRequest) decodeOne(
			utf8("\n\t {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]} \r\n"));
		assertEquals("sum", request.method());
		assertEquals("[1,2,3]", new String(request.params().toByteArray(), UTF_8));
	}

	// ---------------------------------------------------------------------------------------------------
	// T025 — member-order independence. SC-008: the entire semantic justification for deferred decoding.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void paramsBeforeMethodDecodesIdenticallyToParamsAfterMethod() throws MalformedDataException {
		JsonRpcInput canonical = JsonRpcDecoder.decode(
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}"));
		JsonRpcInput reordered = JsonRpcDecoder.decode(
			utf8("{\"params\":[1,2,3],\"method\":\"sum\",\"jsonrpc\":\"2.0\",\"id\":1}"));

		assertEquals("a single-pass decoder that decoded params in place would be invalid on this ordering",
			canonical, reordered);
		assertEquals(List.of(1, 2, 3), ((JsonRpcRequest) reordered).params().decode(INTS));
	}

	@Test
	public void everyPermutationOfTheFourDefinedMembersDecodesTheSame() {
		String jsonrpc = "\"jsonrpc\":\"2.0\"";
		String id = "\"id\":1";
		String method = "\"method\":\"sum\"";
		String params = "\"params\":[1,2,3]";

		JsonRpcRequest expected = canonicalRequest();
		for (String[] order : permutations(new String[]{jsonrpc, id, method, params})) {
			String json = "{" + String.join(",", order) + "}";
			assertEquals(json, expected, decodeOne(utf8(json)));
		}
	}

	@Test
	public void responseMemberOrderIsIrrelevantToo() {
		JsonRpcDecoded ok = decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":19}"));
		assertTrue(ok instanceof JsonRpcResponse);
		assertEquals(ok, decodeOne(utf8("{\"result\":19,\"id\":1,\"jsonrpc\":\"2.0\"}")));

		JsonRpcDecoded failed =
			decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"m\"}}"));
		assertTrue(failed instanceof JsonRpcResponse);
		assertEquals(failed,
			decodeOne(utf8("{\"error\":{\"message\":\"m\",\"code\":-1},\"jsonrpc\":\"2.0\",\"id\":1}")));
	}

	// ---------------------------------------------------------------------------------------------------
	// T026 — the member-dropping guard (FR-033, SC-003).
	//
	// This is the shape AbstractMapJsonCodec.read gets wrong: `reader.skip(); continue;` re-enters readKey()
	// with last() already on the separator skip() consumed, which silently drops EVERY member after the
	// first skipped one. Each case below therefore places a defined member AFTER the unknown one; a decoder
	// carrying that defect fails all three.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anUnknownMemberBeforeTheDefinedOnesDropsNothing() {
		assertDecodesToTheCanonicalRequest(
			"{\"extra\":123,\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}");
	}

	@Test
	public void anUnknownMemberBetweenTheDefinedOnesDropsNothing() {
		assertDecodesToTheCanonicalRequest(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"extra\":123,\"method\":\"sum\",\"params\":[1,2,3]}");
	}

	@Test
	public void anUnknownMemberAfterTheDefinedOnesDropsNothing() {
		assertDecodesToTheCanonicalRequest(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3],\"extra\":123}");
	}

	@Test
	public void unknownMembersEverywhereAtOnceDropNothing() {
		assertDecodesToTheCanonicalRequest(
			"{\"a\":1,\"jsonrpc\":\"2.0\",\"b\":2,\"id\":1,\"c\":3,\"method\":\"sum\",\"d\":4," +
			"\"params\":[1,2,3],\"e\":5}");
	}

	@Test
	public void anUnknownMemberWithAStructuredOrHostileLookingValueDropsNothing() {
		// the unknown value is skipped by the parser, never by a brace scan, so its content cannot matter
		assertDecodesToTheCanonicalRequest(
			"{\"meta\":{\"a\":[1,{\"b\":\"},{[,]\"}],\"c\":\"\\\"\\\\\"},\"jsonrpc\":\"2.0\",\"id\":1," +
			"\"method\":\"sum\",\"params\":[1,2,3]}");
		assertDecodesToTheCanonicalRequest(
			"{\"jsonrpc\":\"2.0\",\"meta\":\"}{,:\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}");
	}

	@Test
	public void anUnknownMemberInsideTheErrorObjectDropsNothing() {
		JsonRpcResponse response = (JsonRpcResponse) decodeOne(utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"extra\":1,\"code\":-1,\"why\":\"x\",\"message\":\"m\"}}"));
		assertNotNull(response.error());
		assertEquals(-1, response.error().code());
		assertEquals("m", response.error().message());
	}

	// ---------------------------------------------------------------------------------------------------
	// T027 — payload capture over the phase-0 corpus shapes.
	//
	// Every fixture below mirrors one of DeferredDecodingTest's, which is where the capture rule was pinned.
	// The assertion is the same in each case: the captured slice reproduces the ORIGINAL bytes exactly, and
	// re-encoding the decoded message re-emits those bytes verbatim.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void capturesAnEscapedQuote() {
		assertParamsCapture("escaped-quote", "[\"a \\\" b\"]");
	}

	@Test
	public void capturesAnEscapedBackslash() {
		assertParamsCapture("escaped-backslash", "[\"a\\\\b\"]");
	}

	@Test
	public void capturesAUnicodeEscape() {
		String slice = "[\"\\u00e9\\u0041\"]";
		assertTrue("the Java lexer ate the \\u escape — this fixture would silently test nothing",
			slice.contains("\\u"));
		JsonRpcPayload captured = assertParamsCapture("unicode-escape", slice);
		assertEquals("the captured BYTE range is longer than the decoded string", 16, captured.size());
	}

	@Test
	public void capturesAnAstralLiteral() {
		JsonRpcPayload captured = assertParamsCapture("non-bmp-literal", "[\"😀\"]");
		assertEquals("getCurrentIndex() counts bytes, not chars", 8, captured.size());
	}

	@Test
	public void capturesAnAstralSurrogatePair() {
		String slice = "[\"\\ud83d\\ude00\"]";
		assertTrue(slice.contains("\\ud83d"));
		assertParamsCapture("non-bmp-escaped", slice);
	}

	@Test
	public void capturesStructuralCharactersInsideAString() {
		// the one fixture that catches a brace-counting capture; we lean on JsonReader.skip() so it cannot break
		assertParamsCapture("structural-chars-in-string", "[\"},{[,]\"]");
	}

	@Test
	public void capturesAWhitespacePaddedValueWithTheTrailingWhitespaceTrimmed() throws MalformedDataException {
		// THE fixture that pins the right-trim. skip() stops one past the separator, so the whitespace between
		// the value and the ',' is inside the raw range; without the trim, the sub-range decode below reports
		// trailing data and every other fixture in this class still passes.
		byte[] envelope =
			utf8("{ \"jsonrpc\" : \"2.0\" , \"id\" : 1 , \"method\" : \"sum\" , \"params\" : [ 1 , 2 ] }");
		JsonRpcRequest request = (JsonRpcRequest) decodeOne(envelope);

		assertEquals(new JsonRpcId.Num(1), request.id());
		assertEquals("sum", request.method());
		assertEquals("[ 1 , 2 ]", new String(request.params().toByteArray(), UTF_8));
		assertEquals(List.of(1, 2), request.params().decode(INTS));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[ 1 , 2 ]}",
			new String(JsonRpcEncoder.encode(request), UTF_8));
	}

	@Test
	public void capturesAnEmptyArray() {
		assertEquals(2, assertParamsCapture("value-empty-array", "[]").size());
	}

	@Test
	public void capturesAnEmptyObject() {
		// the payload legitimately ENDS in '}', which is also the envelope's terminator
		assertEquals(2, assertParamsCapture("value-empty-object", "{}").size());
	}

	@Test
	public void capturesADeeplyNestedPayload() {
		StringBuilder value = new StringBuilder();
		for (int i = 0; i < 32; i++) value.append("{\"a\":");
		value.append('1');
		for (int i = 0; i < 32; i++) value.append('}');
		assertParamsCapture("nested-32", value.toString());
	}

	@Test
	public void capturesBareLiteralResults() {
		// §5 permits ANY JSON value as a result, so the capture has no delimiter to lean on — only skip()'s
		// return. (A bare literal `params` is -32600 by FR-086; that asymmetry is Phase 4's to enforce.)
		assertResultCapture("value-number-integral", "42");
		assertResultCapture("value-number-decimal", "-1.5e10");
		assertResultCapture("value-string", "\"hello\"");
		assertResultCapture("value-boolean", "true");
		assertResultCapture("value-null", "null");
		assertResultCapture("value-string-with-structure", "\"},{[,]\"");
	}

	@Test
	public void aCapturedPayloadPointsIntoTheCallersArrayWithoutCopying() {
		byte[] envelope = utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[1,2,3]}");
		JsonRpcRequest request = (JsonRpcRequest) decodeOne(envelope);

		JsonRpcPayload.Raw raw = (JsonRpcPayload.Raw) request.params();
		assertSame("the payload must reference the envelope, not a copy of it", envelope, raw.view().array());
		assertArrayEquals(utf8("[1,2,3]"), raw.toByteArray());
	}

	// ---------------------------------------------------------------------------------------------------
	// Totality — the full malformed taxonomy is Phase 4's, but nothing may ever throw out of decode().
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void decodeIsTotalOnEveryInput() {
		String[] hostile = {
			"", " ", "\uFEFF{}", "{", "}", "[", "]", "null", "42", "\"x\"", "true",
			"{\"jsonrpc\":\"2.0\"", "{\"jsonrpc\":\"2.0\",\"method\":\"foobar\",\"params\":\"bar\",\"baz]",
			"{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"p\"}", "{\"id\":1,\"method\":\"p\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"p\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"p\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":1}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1,\"error\":{\"code\":1,\"message\":\"m\"}}",
			"{\"jsonrpc\":\"2.0\",\"result\":1}",
			"{} trailing", "{}{}", "{\"a\":}", "{:1}",
		};
		for (String input : hostile) {
			JsonRpcInput result;
			try {
				result = JsonRpcDecoder.decode(input.getBytes(UTF_8));
			} catch (Throwable t) {
				throw new AssertionError("decode threw on <" + input + ">", t);
			}
			assertNotNull(input, result);
		}

		// and on arbitrary bytes that are not text at all
		byte[] noise = new byte[256];
		for (int i = 0; i < noise.length; i++) noise[i] = (byte) i;
		assertNotNull(JsonRpcDecoder.decode(noise));
		assertNotNull(JsonRpcDecoder.decode(new byte[0]));
	}

	@Test
	public void decodeRefusesAnOutOfRangeOffsetOrLength() {
		byte[] envelope = utf8("{}");
		assertIllegalArgument(() -> JsonRpcDecoder.decode(envelope, -1, 2));
		assertIllegalArgument(() -> JsonRpcDecoder.decode(envelope, 0, 3));
		assertIllegalArgument(() -> JsonRpcDecoder.decode(envelope, 1, -1));
		try {
			JsonRpcDecoder.decode(null);
			fail("expected NullPointerException");
		} catch (NullPointerException expected) {
			// the array is the one thing a caller cannot get wrong silently
		}
	}

	/**
	 * A top-level array is a batch, and an <b>empty</b> one is a single error rather than an empty batch.
	 * The full §6 contract — per-element outcomes, batch-ness on the wire, the all-notification case — is
	 * {@code JsonRpcBatchTest}'s subject; this is only the seam between the two.
	 */
	@Test
	public void aTopLevelArrayIsABatchAndAnEmptyOneIsNot() {
		assertTrue(JsonRpcDecoder.decode(utf8("[{\"jsonrpc\":\"2.0\",\"method\":\"p\"}]")) instanceof JsonRpcBatch);
		assertTrue(JsonRpcDecoder.decode(utf8("[]")) instanceof JsonRpcMalformed);
	}

	// ---------------------------------------------------------------------------------------------------

	private static byte[] utf8(String json) {
		return json.getBytes(UTF_8);
	}

	private static JsonRpcDecoded decodeOne(byte[] envelope) {
		JsonRpcInput input = JsonRpcDecoder.decode(envelope);
		if (input instanceof JsonRpcMalformed malformed) {
			throw new AssertionError("expected a decoded message, got " + malformed.error() + " for <" +
									 new String(envelope, UTF_8) + '>');
		}
		return (JsonRpcDecoded) input;
	}

	/**
	 * Asserts the document decodes to the canonical request — <b>by content</b>, not merely by equality with a
	 * baseline decode. Comparing two decodes of two documents would pass vacuously if the decoder were broken
	 * in a way that made both of them the same failure, which is exactly the state a reproduced
	 * {@code skip(); continue;} defect leaves it in.
	 */
	private static void assertDecodesToTheCanonicalRequest(String json) {
		JsonRpcDecoded decoded = decodeOne(utf8(json));
		assertTrue(json, decoded instanceof JsonRpcRequest);

		JsonRpcRequest request = (JsonRpcRequest) decoded;
		assertEquals(json, new JsonRpcId.Num(1), request.id());
		assertEquals(json, "sum", request.method());
		assertEquals(json, "[1,2,3]", new String(request.params().toByteArray(), UTF_8));

		assertEquals(json, canonicalRequest(), decoded);
	}

	/** The canonical request, asserted to be a real request so it can serve as a comparison baseline. */
	private static JsonRpcRequest canonicalRequest() {
		JsonRpcDecoded decoded =
			decodeOne(utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}"));
		assertTrue(decoded instanceof JsonRpcRequest);
		return (JsonRpcRequest) decoded;
	}

	/** Decodes {@code {"jsonrpc":"2.0","id":1,"method":"echo","params":<slice>}} and pins the captured bytes. */
	private static JsonRpcPayload assertParamsCapture(String name, String slice) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":" + slice + "}";
		byte[] envelope = utf8(json);
		JsonRpcRequest request = (JsonRpcRequest) decodeOne(envelope);

		assertArrayEquals(name, utf8(slice), request.params().toByteArray());
		assertArrayEquals(name + ": re-encoding must re-emit the captured bytes verbatim", envelope,
			JsonRpcEncoder.encode(request));
		return request.params();
	}

	/** Decodes {@code {"jsonrpc":"2.0","id":1,"result":<slice>}} and pins the captured bytes. */
	private static JsonRpcPayload assertResultCapture(String name, String slice) {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + slice + "}";
		byte[] envelope = utf8(json);
		JsonRpcResponse response = (JsonRpcResponse) decodeOne(envelope);

		assertArrayEquals(name, utf8(slice), response.result().toByteArray());
		assertArrayEquals(name + ": re-encoding must re-emit the captured bytes verbatim", envelope,
			JsonRpcEncoder.encode(response));
		return response.result();
	}

	private static List<String[]> permutations(String[] items) {
		List<String[]> all = new java.util.ArrayList<>();
		permute(items, 0, all);
		return all;
	}

	private static void permute(String[] items, int from, List<String[]> into) {
		if (from == items.length) {
			into.add(items.clone());
			return;
		}
		for (int i = from; i < items.length; i++) {
			swap(items, from, i);
			permute(items, from + 1, into);
			swap(items, from, i);
		}
	}

	private static void swap(String[] items, int i, int j) {
		String tmp = items[i];
		items[i] = items[j];
		items[j] = tmp;
	}

	private static void assertIllegalArgument(Runnable runnable) {
		try {
			runnable.run();
			fail("expected IllegalArgumentException / IndexOutOfBoundsException");
		} catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
			// bounds on the caller's own array are a programming error, refused rather than classified
		}
	}
}
