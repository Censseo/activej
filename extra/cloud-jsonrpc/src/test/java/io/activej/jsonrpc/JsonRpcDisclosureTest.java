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
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-089 / SC-009 — <b>no emitted error document carries a fragment of the offending input.</b>
 *
 * <h2>Why this needs a test rather than care</h2>
 * The mechanism this feature reuses violates the property by construction:
 * {@code JsonUtils.fromJsonBytes} builds its failure as
 * {@code new MalformedDataException("Unexpected JSON data: " + <the remaining input bytes>)}. So every
 * decode failure this module receives <i>already</i> carries peer input, and "we do not copy it outward" is
 * one careless {@code e.getMessage()} away from being false. {@link #theLoadedGunIsReal()} demonstrates the
 * embedding concretely before the rest of the class demonstrates it never fires.
 *
 * <h2>What is deliberately NOT asserted</h2>
 * Two kinds of peer bytes legitimately appear in an emitted document, and neither is a disclosure:
 * <ul>
 *     <li><b>A recovered {@code id}</b> is echoed by requirement — §5 makes the response id the request's,
 *     and FR-037 requires recovering it. It travels back to the peer that sent it, not onward to that
 *     peer's counterparty, which is the threat FR-089 names. See
 *     {@link #aRecoveredIdIsEchoedByRequirementAndThatIsNotADisclosure()}.</li>
 *     <li><b>A peer's own {@code error.message} / {@code error.data}</b>, when a valid error response is
 *     decoded and re-rendered. Relaying it is the entire point of decoding it. See
 *     {@link #aPeersOwnErrorIsRelayedVerbatimAndThatIsNotADisclosure()}.</li>
 * </ul>
 * Both are pinned so that a future reader cannot mistake them for leaks and "fix" them.
 */
public class JsonRpcDisclosureTest {

	/** Long, unlikely to collide, and not a substring of any fixed error message or of {@code "2.0"}. */
	private static final String MARKER = "MARKER_zzz9f3q_SECRET";

	/**
	 * One malformed envelope per {@code JsonRpcMalformed} construction site in {@link JsonRpcDecoder}, each
	 * carrying the marker somewhere the decoder must read past in order to fail.
	 */
	private static Map<String, byte[]> everyFailurePath() {
		Map<String, byte[]> cases = new LinkedHashMap<>();

		// 1. the catch-all: the member walk throws part-way through
		cases.put("truncated-tail",
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + MARKER + "\",\"params\":[1,2"));
		cases.put("truncated-key", utf8("{\"jsonrpc\":\"2.0\",\"" + MARKER));
		cases.put("unbalanced", utf8("{\"jsonrpc\":\"2.0\",\"method\":\"" + MARKER + "\",\"params\":\"bar\",\"baz]"));

		// 2. the UTF-8 pre-scan
		cases.put("bad-utf8-beside-marker", concat(
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[\"" + MARKER),
			new byte[]{(byte) 0xC0, (byte) 0xAF},
			utf8("\"]}")));
		cases.put("bad-utf8-in-method", concat(
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + MARKER),
			new byte[]{(byte) 0x80},
			utf8("\"}")));

		// 3. inside a batch: a per-element failure, a non-object element, and an array whose own structure
		//    breaks (which is a single whole-document -32700, not a per-element outcome)
		cases.put("batch-element-bad-version",
			utf8("[{\"jsonrpc\":\"1.0\",\"method\":\"" + MARKER + "\"}]"));
		cases.put("batch-non-object-element", utf8("[\"" + MARKER + "\"]"));
		cases.put("batch-structure-broken", utf8("[{\"jsonrpc\":\"2.0\",\"method\":\"" + MARKER + "\""));
		cases.put("batch-mixed-good-and-bad", utf8(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"ok\"},{\"jsonrpc\":\"1.0\",\"method\":\"" + MARKER + "\"}]"));

		// 4. the document is not an object at all
		cases.put("bare-string-document", utf8('"' + MARKER + '"'));
		cases.put("bom-then-marker", concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
			utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + MARKER + "\"}")));

		// 5. trailing data after a complete document
		cases.put("trailing-data", utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\"} " + MARKER));

		// 6. a duplicated defined member
		cases.put("duplicate-method", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + MARKER + "\",\"method\":\"b\"}"));
		cases.put("duplicate-id", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":\"" + MARKER + "\",\"id\":2,\"method\":\"m\"}"));

		// 7. a bad version member
		cases.put("bad-version", utf8("{\"jsonrpc\":\"" + MARKER + "\",\"id\":1,\"method\":\"m\"}"));

		// 8. a structurally invalid id — nothing is recovered, so nothing may be echoed
		cases.put("object-id", utf8("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":\"" + MARKER + "\"},\"method\":\"m\"}"));
		cases.put("array-id", utf8("{\"jsonrpc\":\"2.0\",\"id\":[\"" + MARKER + "\"],\"method\":\"m\"}"));

		// 9. a non-string method
		cases.put("object-method", utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{\"x\":\"" + MARKER + "\"}}"));

		// 10. a bare-literal params
		cases.put("bare-literal-params", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":\"" + MARKER + "\"}"));

		// 11. a response carrying both result and error
		cases.put("both-result-and-error", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + MARKER + "\"," +
			"\"error\":{\"code\":-1,\"message\":\"" + MARKER + "\"}}"));

		// 12. a response with no id
		cases.put("response-without-id", utf8("{\"jsonrpc\":\"2.0\",\"result\":\"" + MARKER + "\"}"));

		// 13. a malformed error object
		cases.put("malformed-error-object", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":\"" + MARKER + "\",\"message\":\"m\"}}"));
		cases.put("error-object-not-an-object", utf8(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":\"" + MARKER + "\"}"));

		return cases;
	}

	/**
	 * Demonstrates that the exception this module receives really does carry peer input, so the rest of this
	 * class is guarding against a live hazard rather than a hypothetical one.
	 */
	@Test
	public void theLoadedGunIsReal() {
		byte[] payload = utf8("[1,2,3] " + MARKER);
		JsonRpcPayload.Raw raw = new JsonRpcPayload.Raw(payload, 0, payload.length);
		try {
			raw.decode(JsonCodecs.ofList(JsonCodecs.ofInteger()));
			fail("expected the trailing-data completeness check to fail");
		} catch (MalformedDataException e) {
			assertTrue(
				"JsonUtils embeds the offending bytes in its own message — if this ever stops being true the " +
				"rest of this class is still correct, but its premise should be re-stated: " + e.getMessage(),
				String.valueOf(e.getMessage()).contains(MARKER));
		}
	}

	/** SC-009 — the marker must appear in no rendered error document, on any failure path. */
	@Test
	public void noRenderedErrorDocumentContainsAnyFragmentOfTheOffendingInput() {
		for (Map.Entry<String, byte[]> probe : everyFailurePath().entrySet()) {
			String name = probe.getKey();
			for (JsonRpcMalformed malformed : failuresOf(name, JsonRpcDecoder.decode(probe.getValue()))) {
				assertRendersWithoutTheMarker(name + " (as a response)",
					JsonRpcEncoder.encode(malformed.toResponse()));
				assertRendersWithoutTheMarker(name + " (as a single document)",
					JsonRpcEncoder.encode(new JsonRpcOutput.Single(malformed.toResponse())));
				assertRendersWithoutTheMarker(name + " (inside a batch)",
					JsonRpcEncoder.encode(new JsonRpcOutput.Batch(List.of(malformed.toResponse()))));
			}
		}
	}

	/**
	 * The valve, as a behavioural property: every internally-classified failure carries one of the nine fixed
	 * {@link JsonRpcErrors} constants <b>by identity</b>, and never a {@code data} payload.
	 * <p>
	 * Identity is the strong form. A decoder that built {@code new JsonRpcError(-32700, e.getMessage(), …)}
	 * would satisfy a code-only assertion and fail this one.
	 */
	@Test
	public void everyInternallyClassifiedFailureCarriesAFixedConstant() {
		for (Map.Entry<String, byte[]> probe : everyFailurePath().entrySet()) {
			for (JsonRpcMalformed malformed : failuresOf(probe.getKey(), JsonRpcDecoder.decode(probe.getValue()))) {
				JsonRpcError error = malformed.error();
				assertTrue(probe.getKey() + ": " + error + " is not one of the fixed constants",
					isOneOfTheNineConstants(error));
				assertTrue(probe.getKey() + ": an emitted error must carry no data", error.data().isAbsent());
			}
		}
	}

	/**
	 * The structural half of the valve: the decoder's source may not reach for a caught exception's text, nor
	 * build an error object of its own.
	 * <p>
	 * The behavioural probe above can only cover the failure paths someone thought to construct. This scan
	 * covers the ones they did not, and it is what fails when a future change adds
	 * {@code new JsonRpcError(-32700, e.getMessage(), …)} on a path with no marker test.
	 */
	@Test
	public void theDecoderNeverReachesForAnExceptionsText() throws IOException {
		String source = readSource("JsonRpcDecoder.java");

		String[] forbiddenForms = {"getMessage", "getLocalizedMessage", "new JsonRpcError(", "printStackTrace"};
		for (String forbidden : forbiddenForms) {
			assertFalse("JsonRpcDecoder must not contain <" + forbidden + ">: an emitted error's message is " +
						"one of the fixed strings of JsonRpcErrors, never anything derived from a caught " +
						"exception (FR-089)", source.contains(forbidden));
		}

		// JsonRpcErrors.of(...) refuses reserved codes and so cannot build a protocol error at all; ofAny is
		// permissive and must appear exactly once — on the path that rebuilds a PEER's error object verbatim
		assertEquals("ofAny belongs only to the peer-error rebuild path", 1, countOccurrences(source, "ofAny("));
	}

	// ---------------------------------------------------------------------------------------------------
	// The two deliberate exceptions, pinned so they are not mistaken for leaks.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aRecoveredIdIsEchoedByRequirementAndThatIsNotADisclosure() {
		// §5 makes the response id the request's, and FR-037 requires recovering it where possible. The id
		// therefore travels back to the peer that sent it — not onward to that peer's counterparty, which is
		// the direction FR-089 forbids. FR-089 scopes its prohibition to `message` and `data` for exactly
		// this reason.
		byte[] envelope = utf8("{\"jsonrpc\":\"1.0\",\"id\":\"" + MARKER + "\",\"method\":\"m\"}");
		JsonRpcMalformed malformed = (JsonRpcMalformed) JsonRpcDecoder.decode(envelope);

		assertEquals(new JsonRpcId.Str(MARKER), malformed.id());
		String rendered = new String(JsonRpcEncoder.encode(malformed.toResponse()), UTF_8);
		assertTrue("the id is echoed, by requirement", rendered.contains(MARKER));
		assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"" + MARKER + "\"," +
					 "\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}", rendered);
	}

	@Test
	public void aPeersOwnErrorIsRelayedVerbatimAndThatIsNotADisclosure() {
		// decoding a peer's error response and re-rendering it reproduces its message and data. That is what
		// decoding it is FOR; discarding them would be the bug (FR-016: never discard what the peer meant).
		byte[] envelope = utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"" +
							   MARKER + "\",\"data\":[\"" + MARKER + "\"]}}");
		JsonRpcResponse response = (JsonRpcResponse) JsonRpcDecoder.decode(envelope);

		assertEquals(MARKER, response.error().message());
		assertTrue(new String(JsonRpcEncoder.encode(response), UTF_8).contains(MARKER));
	}

	// ---------------------------------------------------------------------------------------------------

	/**
	 * Every failure the decoder reported for one probe: either the whole document failed, or a batch reported
	 * per-element outcomes and each malformed one must be just as clean.
	 */
	private static List<JsonRpcMalformed> failuresOf(String name, JsonRpcInput input) {
		if (input instanceof JsonRpcMalformed malformed) return List.of(malformed);
		if (input instanceof JsonRpcBatch batch) {
			List<JsonRpcMalformed> failures = new ArrayList<>();
			for (JsonRpcDecoded element : batch.elements()) {
				if (element instanceof JsonRpcMalformed malformed) failures.add(malformed);
			}
			if (failures.isEmpty()) throw new AssertionError(name + ": no element failed, so nothing is probed");
			return failures;
		}
		throw new AssertionError(name + ": expected a failure, got " + input);
	}

	private static void assertRendersWithoutTheMarker(String what, byte[] rendered) {
		String document = new String(rendered, UTF_8);
		if (document.contains(MARKER)) {
			fail(what + ": the offending input reached the emitted document: " + document);
		}
		// a marker fragment would be just as bad as the whole marker
		for (String fragment : new String[]{"zzz9f3q", "SECRET", "MARKER_"}) {
			if (document.contains(fragment)) {
				fail(what + ": a fragment of the offending input reached the emitted document: " + document);
			}
		}
		// and the bytes, in case a future encoder ever emits something that is not valid UTF-8 text
		assertFalse(what, indexOf(rendered, MARKER.getBytes(UTF_8)) >= 0);
	}

	private static boolean isOneOfTheNineConstants(JsonRpcError error) {
		for (JsonRpcError constant : new JsonRpcError[]{
			JsonRpcErrors.PARSE_ERROR, JsonRpcErrors.INVALID_REQUEST, JsonRpcErrors.METHOD_NOT_FOUND,
			JsonRpcErrors.INVALID_PARAMS, JsonRpcErrors.INTERNAL_ERROR, JsonRpcErrors.REQUEST_TOO_LARGE,
			JsonRpcErrors.BATCH_TOO_LARGE, JsonRpcErrors.NESTING_TOO_DEEP, JsonRpcErrors.INVALID_RESPONSE}) {
			if (error == constant) return true;
		}
		return false;
	}

	private static String readSource(String fileName) throws IOException {
		Path path = Path.of("src", "main", "java", "io", "activej", "jsonrpc", fileName);
		if (!Files.isRegularFile(path)) {
			path = Path.of("extra", "cloud-jsonrpc", "src", "main", "java", "io", "activej", "jsonrpc", fileName);
		}
		return Files.readString(path, UTF_8);
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}

	private static byte[] utf8(String s) {
		return s.getBytes(UTF_8);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) out.write(part, 0, part.length);
		return out.toByteArray();
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
}
