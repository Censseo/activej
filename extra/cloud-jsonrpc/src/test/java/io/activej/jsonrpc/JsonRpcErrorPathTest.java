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

import java.io.ByteArrayOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * User Story 2 — every malformed input class becomes a normative error document (FR-034…FR-037, FR-084,
 * FR-086, Contract 2's "Rejected" table).
 * <p>
 * The code-to-input mapping asserted here is Contract 2's, member for member. Where this feature's behaviour
 * is a strictness decision rather than a transcription of JSON-RPC 2.0, the test says so.
 */
public class JsonRpcErrorPathTest {

	// ---------------------------------------------------------------------------------------------------
	// T032 — -32700 Parse error. Each of these carries id: null: the specification's own §7 example for
	// invalid JSON answers with "id": null, so a -32700 never echoes an identifier even when the bytes
	// before the failure happened to contain one.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void truncatedJsonIsAParseError() {
		assertParseError("{\"jsonrpc\":\"2.0\",\"id\":1,\"meth");
		assertParseError("{\"jsonrpc\":\"2.0\",\"method\":\"foobar\",\"params\":\"bar\",\"baz]");
		assertParseError("{");
		assertParseError("{\"jsonrpc\":\"2.0\"");
		assertParseError("");
		assertParseError("   ");
	}

	@Test
	public void aValueThatIsNotAnObjectOrAnArrayIsAParseError() {
		assertParseError("null");
		assertParseError("42");
		assertParseError("\"a string\"");
		assertParseError("true");
	}

	@Test
	public void aLeadingByteOrderMarkIsRejectedNotStripped() {
		// FR-084: silently stripping a BOM lets two implementations disagree about identical bytes. The same
		// document without the BOM must decode, or this test would pass for the wrong reason.
		String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\"}";
		assertTrue(JsonRpcDecoder.decode(json.getBytes(UTF_8)) instanceof JsonRpcRequest);

		byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, json.getBytes(UTF_8));
		assertParseError(withBom, "a leading UTF-8 BOM");

		// and a BOM in the middle of the document is not special-cased into acceptance either
		assertParseError(concat("{\"jsonrpc\":\"2.0\",".getBytes(UTF_8),
			new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "\"id\":1}".getBytes(UTF_8)), "an embedded BOM");
	}

	/**
	 * FR-084 — malformed UTF-8 anywhere in the envelope is {@code -32700}.
	 * <p>
	 * <b>This cannot be delegated to the parser</b>, which was established by probing dsl-json 1.10.0 rather
	 * than assumed: {@code JsonReader.skip()} validates no UTF-8 at all, and decoding a string containing an
	 * <b>overlong</b> encoding of {@code '/'} ({@code C0 AF}) silently yields {@code "/"} — two different byte
	 * sequences producing one string, which is the encoding-confusion attack the requirement exists to stop.
	 * A UTF-8-encoded surrogate ({@code ED A0 80}) likewise yields a lone surrogate, and a bare {@code FF}
	 * yields an {@code ArrayIndexOutOfBoundsException} rather than a parse failure.
	 */
	@Test
	public void malformedUtf8IsAParseError() {
		int[][] invalid = {
			{0xFF},                     // never valid in UTF-8
			{0xFE},
			{0x80},                     // a lone continuation byte
			{0xC3},                     // a truncated 2-byte sequence
			{0xE2, 0x82},               // a truncated 3-byte sequence
			{0xC0, 0xAF},               // an overlong encoding of '/'
			{0xC1, 0xBF},               // an overlong encoding of '?'
			{0xE0, 0x80, 0xAF},         // a 3-byte overlong encoding
			{0xF0, 0x80, 0x80, 0xAF},   // a 4-byte overlong encoding
			{0xED, 0xA0, 0x80},         // U+D800, a UTF-16 surrogate half encoded as UTF-8
			{0xED, 0xBF, 0xBF},         // U+DFFF
			{0xF5, 0x80, 0x80, 0x80},   // beyond U+10FFFF
			{0xF8, 0x88, 0x80, 0x80},   // a 5-byte sequence, which UTF-8 has never had
			{0xE2, 0x28, 0xA1},         // an invalid continuation byte
		};
		for (int[] sequence : invalid) {
			// inside the params payload, which this layer never decodes — so only an explicit scan can catch it
			assertParseError(concat(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[\"".getBytes(UTF_8),
				raw(sequence),
				"\"]}".getBytes(UTF_8)), "params containing " + describe(sequence));

			// and inside a member this layer does decode
			assertParseError(concat(
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"".getBytes(UTF_8),
				raw(sequence),
				"\"}".getBytes(UTF_8)), "method containing " + describe(sequence));
		}
	}

	@Test
	public void wellFormedMultiByteUtf8IsAccepted() {
		// the scan must not be so eager that it refuses legal documents
		String[] legal = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[\"héllo\"]}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[\"😀\"]}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[\"߿ࠀ￿\"]}",
			"{\"jsonrpc\":\"2.0\",\"id\":\"héllo 😀\",\"method\":\"échó\"}",
		};
		for (String json : legal) {
			JsonRpcInput input = JsonRpcDecoder.decode(json.getBytes(UTF_8));
			assertFalse(json + " -> " + input, input instanceof JsonRpcMalformed);
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// T033 — -32600 Invalid Request.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aNonStringMethodIsInvalid() {
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"method\":1,\"params\":\"bar\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{\"a\":1}}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":[\"m\"]}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":true}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null}");
	}

	@Test
	public void anEmptyMethodIsInvalid() {
		// a strictness decision of this feature: the value model refuses an empty method name, so a document
		// carrying one must become an error rather than an exception
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"method\":\"\"}");
	}

	@Test
	public void aMissingOrWrongVersionMemberIsInvalid() {
		assertInvalidRequest("{\"id\":1,\"method\":\"m\"}");                        // missing entirely
		assertInvalidRequest("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"m\"}");    // JSON-RPC 1.0
		assertInvalidRequest("{\"jsonrpc\":\"2\",\"id\":1,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.00\",\"id\":1,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":2.0,\"id\":1,\"method\":\"m\"}");        // a number, not a string
		assertInvalidRequest("{\"jsonrpc\":null,\"id\":1,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":[\"2.0\"],\"id\":1,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":{\"v\":\"2.0\"},\"id\":1,\"method\":\"m\"}");
	}

	@Test
	public void anEscapedSpellingOfTheVersionIsStillTheVersion() {
		// "2.0" is the same JSON string as "2.0"; refusing it would be a byte-comparison bug dressed up
		// as strictness
		assertTrue(JsonRpcDecoder.decode(
			"{\"jsonrpc\":\"2\\u002E0\",\"id\":1,\"method\":\"m\"}".getBytes(UTF_8)) instanceof JsonRpcRequest);
	}

	@Test
	public void aMalformedIdIsInvalid() {
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"m\"}");          // fractional
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1.0,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1e3,\"method\":\"m\"}");          // an exponent
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"m\"}");         // boolean
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":false,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"m\"}");           // object
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":[],\"method\":\"m\"}");           // array
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"m\"}");
	}

	@Test
	public void anIdOutsideThe64BitSignedRangeIsInvalid() {
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":99999999999999999999,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":9223372036854775808,\"method\":\"m\"}");   // MAX + 1
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":-9223372036854775809,\"method\":\"m\"}");  // MIN - 1

		// and the boundaries themselves are accepted, or the refusal above would prove nothing
		assertTrue(JsonRpcDecoder.decode("{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807,\"method\":\"m\"}"
			.getBytes(UTF_8)) instanceof JsonRpcRequest);
		assertTrue(JsonRpcDecoder.decode("{\"jsonrpc\":\"2.0\",\"id\":-9223372036854775808,\"method\":\"m\"}"
			.getBytes(UTF_8)) instanceof JsonRpcRequest);
	}

	@Test
	public void aDuplicatedDefinedMemberIsInvalid() {
		// FR-034: "last one wins" is precisely how two implementations read one document differently, so a
		// repeated defined member is refused rather than resolved
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\",\"method\":\"b\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[1],\"params\":[2]}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1,\"result\":2}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"jsonrpc\":\"1.0\"}");

		// a repeated UNKNOWN member is not a defined member and stays ignorable
		assertTrue(JsonRpcDecoder.decode(
			"{\"extra\":1,\"extra\":2,\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}".getBytes(UTF_8))
			instanceof JsonRpcRequest);
	}

	@Test
	public void theFirstOccurrenceWinsNothingWhenAMemberIsDuplicated() {
		// the point of refusing is that neither occurrence is authoritative; assert the decode fails rather
		// than silently choosing "a" or "b"
		JsonRpcMalformed malformed = assertInvalidRequest(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\",\"method\":\"b\"}");
		assertEquals("the id is unambiguous here, so it is recovered", new JsonRpcId.Num(1), malformed.id());
	}

	@Test
	public void aDuplicatedIdMemberRecoversNoId() {
		// which of the two the peer meant is exactly what is unknowable, so echoing either would invite the
		// mis-correlation the refusal exists to prevent
		JsonRpcMalformed malformed = assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"m\"}");
		assertEquals(JsonRpcId.NULL, malformed.id());
	}

	// ---------------------------------------------------------------------------------------------------
	// T034 — the params/result asymmetry (FR-086, §4.2 vs §5).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aBareLiteralParamsMemberIsInvalid() {
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":\"x\"}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":42}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":-1.5e10}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":true}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":false}");
		assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":42}");       // on a notification too
	}

	@Test
	public void theStructuredAndNullParamsFormsAreAccepted() {
		// §4.2 has exactly two structured forms, and an explicit null is not a bare literal in this sense —
		// it is the absence of arguments, spelled out
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[1,2]}")
			instanceof JsonRpcRequest);
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"a\":1}}")
			instanceof JsonRpcRequest);
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":[]}")
			instanceof JsonRpcRequest);
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{}}") instanceof JsonRpcRequest);
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":null}") instanceof JsonRpcRequest);
		assertTrue(decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"}") instanceof JsonRpcRequest);
	}

	@Test
	public void aBareLiteralResultIsDeliberatelyNotRestricted() {
		// §5 permits ANY JSON value as a result. The asymmetry with params is intentional (FR-086) and this
		// is the test that stops someone "tidying it up" into symmetry.
		for (String result : new String[]{"\"x\"", "42", "-1.5e10", "true", "false", "null", "[]", "{}"}) {
			String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}";
			JsonRpcInput input = decode(json);
			assertTrue(json + " -> " + input, input instanceof JsonRpcResponse);
			assertEquals(json, result, new String(((JsonRpcResponse) input).result().toByteArray(), UTF_8));
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// T035 — -32004 Invalid response (FR-013, Contract 2).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aResponseCarryingBothResultAndErrorIsInvalid() {
		assertInvalidResponse(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":19,\"error\":{\"code\":-1,\"message\":\"m\"}}");
		assertInvalidResponse(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"m\"},\"result\":null}");
	}

	@Test
	public void aResponseCarryingNeitherResultNorErrorIsInvalid() {
		// a document with no method and no result/error is the `invalid-response-neither` shape
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":null}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\"}");
	}

	@Test
	public void aResponseWithoutAnIdIsInvalid() {
		// §5 makes id required on a Response; its absence is the peer's fault, not a notification
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"result\":19}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-1,\"message\":\"m\"}}");
	}

	@Test
	public void aMalformedErrorObjectIsAnInvalidResponse() {
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"x\",\"message\":\"m\"}}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":2}}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"m\"}}");     // no code
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1}}");           // no message
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":\"boom\"}");                // not an object
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":[]}");
		assertInvalidResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"code\":-2,\"message\":\"m\"}}");
	}

	// ---------------------------------------------------------------------------------------------------
	// T036 — id recovery (FR-037).
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aFailureAfterAValidIdCarriesThatId() {
		assertEquals(new JsonRpcId.Str("abc"),
			assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":42}").id());
		assertEquals(new JsonRpcId.Num(7),
			assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"\"}").id());
		assertEquals(JsonRpcId.NULL,
			assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":42}").id());
		assertEquals(new JsonRpcId.Num(7),
			assertInvalidRequest("{\"jsonrpc\":\"1.0\",\"id\":7,\"method\":\"m\"}").id());
		assertEquals(new JsonRpcId.Num(7),
			assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"m\",\"params\":42}").id());
		assertEquals(new JsonRpcId.Num(7), assertInvalidResponse(
			"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":1,\"error\":{\"code\":-1,\"message\":\"m\"}}").id());
	}

	@Test
	public void idRecoveryDoesNotDependOnMemberPosition() {
		// the walk captures every member before it validates any, so an id is recoverable wherever it sits.
		// Anything else would make the ERROR document member-order-dependent, which FR-032 forbids for the
		// success path and would be just as wrong here.
		assertEquals(new JsonRpcId.Num(7),
			assertInvalidRequest("{\"method\":42,\"jsonrpc\":\"2.0\",\"id\":7}").id());
		assertEquals(new JsonRpcId.Num(7),
			assertInvalidRequest("{\"jsonrpc\":\"1.0\",\"method\":\"m\",\"id\":7}").id());
	}

	@Test
	public void aFailureBeforeAnyIdCouldBeReadCarriesNull() {
		// no id member at all
		assertEquals(JsonRpcId.NULL, assertInvalidRequest("{\"jsonrpc\":\"1.0\",\"method\":\"m\"}").id());
		// the id member is itself the malformed one
		assertEquals(JsonRpcId.NULL, assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"m\"}").id());
		assertEquals(JsonRpcId.NULL, assertInvalidRequest("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"m\"}").id());
		// the document never parsed, so no member was ever read
		assertEquals(JsonRpcId.NULL, assertParseError("{\"jsonrpc\":\"2.0\",\"id\":1,\"meth").id());
	}

	// ---------------------------------------------------------------------------------------------------

	private static JsonRpcInput decode(String json) {
		return JsonRpcDecoder.decode(json.getBytes(UTF_8));
	}

	private static JsonRpcMalformed assertMalformed(byte[] envelope, JsonRpcError expected, String what) {
		JsonRpcInput input = JsonRpcDecoder.decode(envelope);
		if (!(input instanceof JsonRpcMalformed malformed)) {
			throw new AssertionError(what + ": expected " + expected.message() + ", but it decoded to " + input);
		}
		assertEquals(what, expected.code(), malformed.error().code());
		assertEquals(what, expected.message(), malformed.error().message());
		assertTrue(what + ": an emitted error carries no data", malformed.error().data().isAbsent());
		return malformed;
	}

	private static JsonRpcMalformed assertParseError(String json) {
		JsonRpcMalformed malformed = assertParseError(json.getBytes(UTF_8), json);
		assertEquals(json + ": a parse error never echoes an id", JsonRpcId.NULL, malformed.id());
		return malformed;
	}

	private static JsonRpcMalformed assertParseError(byte[] envelope, String what) {
		JsonRpcMalformed malformed = assertMalformed(envelope, JsonRpcErrors.PARSE_ERROR, what);
		assertEquals(what + ": a parse error never echoes an id", JsonRpcId.NULL, malformed.id());
		return malformed;
	}

	private static JsonRpcMalformed assertInvalidRequest(String json) {
		return assertMalformed(json.getBytes(UTF_8), JsonRpcErrors.INVALID_REQUEST, json);
	}

	private static JsonRpcMalformed assertInvalidResponse(String json) {
		return assertMalformed(json.getBytes(UTF_8), JsonRpcErrors.INVALID_RESPONSE, json);
	}

	private static byte[] raw(int[] values) {
		byte[] bytes = new byte[values.length];
		for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
		return bytes;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) out.write(part, 0, part.length);
		return out.toByteArray();
	}

	private static String describe(int[] sequence) {
		StringBuilder hex = new StringBuilder();
		for (int value : sequence) hex.append(String.format("%02X ", value));
		return hex.toString().trim();
	}
}
