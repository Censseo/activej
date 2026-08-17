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

package io.activej.jsonrpc.transport.http;

import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.JsonRpcErrors;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Set;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Feature 014 adversarial validation, domains A (hostile peer) + G (transverse) — the
 * <b>document-level</b> scenarios, replayed over a real socket through the real servlet
 * ({@code adversarial-test-plan.md} A8–A14, A19). The wire contract is
 * {@code contracts/http-semantics.md} §2.2: every JSON-RPC outcome — {@code -32700}…{@code -32603},
 * the allocated {@code -32002}…{@code -32004} — is a {@code 200} carrying the dispatcher's bytes,
 * never a failed promise, and nothing derived from the offending input is ever reflected.
 * <p>
 * The oracle is the feature-010 conformance corpus ({@code hardening.json}) where the two disagree
 * with the plan's summary cells: the plan's A14 lists an <b>empty</b> method name among the
 * {@code -32601} cases, but the frozen vector {@code empty-method} — the authoritative contract —
 * refuses it as {@code -32600}; and the plan's A8 blanket {@code -32700} does not cover a
 * {@code NaN}/{@code Infinity}/giant number that sits <i>inside</i> {@code params}: the envelope
 * parses (dsl-json's {@code skip} tolerates the tokens), so the params codec's refusal is the
 * documented {@code -32602} of http-semantics §2.2. Both are asserted here as the contract states
 * them and recorded in the validation report.
 */
public final class JsonRpcServletHostileDocumentTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private Eventloop eventloop;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
	}

	/** A server over the real servlet and the {@code TestApi} dispatcher, listening on {@code :0}. */
	private JsonRpcHttpTestServer listen() throws IOException {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(JsonRpcServlet.create(eventloop, dispatcher))
			.build();
		server.listen();
		return server;
	}

	private static String assert200WithCode(String response, String code) {
		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries " + code + ": " + response, headAndBody[1].contains("\"code\":" + code));
		return headAndBody[1];
	}

	// A8 ------------------------------------------------------------------------------------------

	/**
	 * Plan A8, the genuinely malformed documents: a truncated envelope, two roots back to back, and
	 * the bare literals {@code NaN}, {@code Infinity} and a giant number — each {@code -32700}
	 * inside a {@code 200} (http-semantics §2.2), never a failed promise, and with <b>nothing of the
	 * input reflected</b>: the response contains none of the document's distinctive fragments.
	 */
	@Test
	public void a8TruncatedMultipleRootsAndBareLiteralsAre32700WithNothingReflected() throws Exception {
		String[] variants = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}{\"x\":1}",
			"NaN",
			"Infinity",
			"99999999999999999999999999999999999999999999",
		};
		for (String variant : variants) {
			String response = exchange(eventloop, listen(), post(variant, "application/json"));
			String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.PARSE_ERROR.code()));
			assertFalse("nothing of the offending document may be reflected: " + body,
				body.contains("test.add"));
			assertFalse("nothing of the offending document may be reflected: " + body,
				body.contains("params"));
		}
	}

	/**
	 * Plan A8, invalid UTF-8 as <b>raw bytes</b>: the overlong two-byte encoding of {@code '/'}
	 * ({@code C0 AF}), a bare {@code FF}, and a truncated four-byte sequence — each inside a string
	 * member of an otherwise valid request. The pre-parse UTF-8 scan (FR-084) refuses all three as
	 * {@code -32700}; the requests carry {@code Content-Length} matching the raw byte count.
	 */
	@Test
	public void a8InvalidUtf8InsideAStringIs32700() throws Exception {
		String head = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Connection: close\r\n";
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2},\"pad\":\"";
		byte[][] payloads = {
			{0x40, (byte) 0xC0, (byte) 0xAF},                    // overlong '/'
			{0x40, (byte) 0xFF},                                 // bare FF
			{0x40, (byte) 0xF0, (byte) 0x9F, (byte) 0x92},       // truncated 4-byte sequence
		};
		for (byte[] middle : payloads) {
			byte[] body = new byte[prefix.length() + middle.length + 2];
			System.arraycopy(prefix.getBytes(US_ASCII), 0, body, 0, prefix.length());
			System.arraycopy(middle, 0, body, prefix.length(), middle.length);
			body[body.length - 2] = '"';
			body[body.length - 1] = '}';
			byte[] request = concat(head.getBytes(US_ASCII),
				("Content-Length: " + body.length + "\r\n\r\n").getBytes(US_ASCII), body);
			String response = exchange(eventloop, listen(), request);
			String bodyText = assert200WithCode(response, String.valueOf(JsonRpcErrors.PARSE_ERROR.code()));
			assertFalse("the response must not carry the input's own bytes: " + bodyText,
				bodyText.contains("pad"));
		}
	}

	/**
	 * Plan A8's blanket {@code -32700} does not cover a {@code NaN}/{@code Infinity}/giant number
	 * <b>inside</b> {@code params}: the envelope walk tolerates the tokens, the method resolves, and
	 * the params codec's refusal is the documented {@code -32602 Invalid params} of
	 * http-semantics §2.2 — still a {@code 200}, still nothing of the input reflected, still no
	 * {@code data} member. Recorded as an oracle nuance in the validation report.
	 */
	@Test
	public void a8NanInfinityAndGiantNumbersInsideParamsAre32602WithoutData() throws Exception {
		String[] variants = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":NaN}}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":Infinity}}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":999999999999999999999999999999999999}}",
		};
		for (String variant : variants) {
			String response = exchange(eventloop, listen(), post(variant, "application/json"));
			String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.INVALID_PARAMS.code()));
			assertTrue("the request's id is still reflected: " + body, body.contains("\"id\":1"));
			assertFalse("no data member may appear: " + body, body.contains("data"));
			assertFalse("nothing of the offending document may be reflected: " + body,
				body.contains("9999"));
		}
	}

	// A9 ------------------------------------------------------------------------------------------

	/**
	 * Plan A9: 65 levels of nesting answer {@code -32003} (the pre-parse depth scan, FR-052);
	 * exactly 64 levels pass the scan — the bound is inclusive — and the document is answered on its
	 * merits (here a batch of one non-object element, {@code -32600}), never {@code -32003}.
	 */
	@Test
	public void a9Nesting65Is32003AndExactly64IsAccepted() throws Exception {
		String depth65 = "[".repeat(65) + "]".repeat(65);
		String depth64 = "[".repeat(64) + "]".repeat(64);

		String tooDeep = exchange(eventloop, listen(), post(depth65, "application/json"));
		assert200WithCode(tooDeep, String.valueOf(JsonRpcErrors.NESTING_TOO_DEEP.code()));

		String atBound = exchange(eventloop, listen(), post(depth64, "application/json"));
		String body = assert200WithCode(atBound, String.valueOf(JsonRpcErrors.INVALID_REQUEST.code()));
		assertFalse("a document exactly at the depth bound is accepted, never -32003: " + body,
			body.contains(String.valueOf(JsonRpcErrors.NESTING_TOO_DEEP.code())));
	}

	// A10 -----------------------------------------------------------------------------------------

	/**
	 * Plan A10: a batch of 101 elements is refused as one {@code -32002} document (refused ON the
	 * element that would exceed the bound, FR-054); a batch of exactly 100 is processed — a
	 * {@code 200} carrying a 100-element response array.
	 */
	@Test
	public void a10BatchOf101Is32002And100IsProcessed() throws Exception {
		String batch101 = batchOf(101);
		String batch100 = batchOf(100);

		String tooLarge = exchange(eventloop, listen(), post(batch101, "application/json"));
		String body = assert200WithCode(tooLarge, String.valueOf(JsonRpcErrors.BATCH_TOO_LARGE.code()));
		assertTrue("one document for the whole batch, id null: " + body, body.contains("\"id\":null"));

		String processed = exchange(eventloop, listen(), post(batch100, "application/json"));
		String[] headAndBody = splitHeadAndBody(processed);
		assertTrue("status line: " + processed, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("a 100-element batch is answered element for element: " + processed,
			headAndBody[1].startsWith("["));
		assertEquals("100 results in request order", 100, countOccurrences(headAndBody[1], "\"sum\":"));
		assertFalse("the 100-element batch was processed, not refused: " + processed,
			headAndBody[1].contains("-32002"));
	}

	// A11 -----------------------------------------------------------------------------------------

	/**
	 * Plan A11: a batch mixing a success, an unknown method, an accidental failure, a <b>response
	 * element</b> and a notification is answered with exactly the three requests' responses, in
	 * request order (FR-053a) — the response element and the notification produce nothing. A
	 * batch made only of response elements produces zero bytes (the {@code 204}).
	 */
	@Test
	public void a11MixedBatchAnswersInOrderAndIgnoresResponsesAndNotifications() throws Exception {
		String mixed = "[" +
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.nope\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"test.failAccidentally\"}," +
			"{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"sum\":99}}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"x\"}}" +
			"]";
		String response = exchange(eventloop, listen(), post(mixed, "application/json"));
		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		String body = headAndBody[1];
		int success = body.indexOf("\"result\":{\"sum\":5}");
		int notFound = body.indexOf("\"code\":-32601");
		int internal = body.indexOf("\"code\":-32603");
		assertTrue("the success answer is present: " + body, success >= 0);
		assertTrue("the -32601 answer is present: " + body, notFound >= 0);
		assertTrue("the -32603 answer is present: " + body, internal >= 0);
		assertTrue("order is preserved (FR-053a): success before -32601 before -32603",
			success < notFound && notFound < internal);
		assertEquals("exactly one result, one -32601, one -32603 — the response element and the " +
			"notification are ignored", 1, countOccurrences(body, "\"sum\":"));
		assertFalse("the inbound response element must not be answered: " + body, body.contains("\"id\":99"));

		// a batch made only of response elements: zero bytes — the 204, no body
		String responsesOnly = "[" +
			"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"sum\":1}}," +
			"{\"jsonrpc\":\"2.0\",\"id\":8,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}" +
			"]";
		String responseOnly = exchange(eventloop, listen(), post(responsesOnly, "application/json"));
		String[] headAndBodyOnly = splitHeadAndBody(responseOnly);
		assertTrue("a responses-only batch is the 204 empty response: " + responseOnly,
			headAndBodyOnly[0].startsWith("HTTP/1.1 204 No Content"));
		assertEquals("zero bytes back", "", headAndBodyOnly[1]);
	}

	// A12 -----------------------------------------------------------------------------------------

	/**
	 * Plan A12: only the exact string {@code "2.0"} is a legal version. {@code "1.0"}, an absent
	 * member, a non-string and {@code "2.0 "} (trailing space) are each {@code -32600} with the
	 * request's own id still reflected.
	 */
	@Test
	public void a12VersionVariantsAreAll32600() throws Exception {
		String[] variants = {
			"{\"jsonrpc\":\"1.0\",\"id\":5,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}",
			"{\"id\":5,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}",
			"{\"jsonrpc\":2.0,\"id\":5,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}",
			"{\"jsonrpc\":\"2.0 \",\"id\":5,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}",
		};
		for (String variant : variants) {
			String response = exchange(eventloop, listen(), post(variant, "application/json"));
			String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.INVALID_REQUEST.code()));
			assertTrue("the request's own id is still reflected: " + body, body.contains("\"id\":5"));
		}
	}

	// A13 -----------------------------------------------------------------------------------------

	/**
	 * Plan A13: an {@code id} must be one of the three forms of §4 — the frozen vectors
	 * {@code fractional-id}/{@code id-out-of-long-range} refuse an object, an array, a boolean, a
	 * float and an integral number beyond 64-bit signed range as {@code -32600} with
	 * {@code "id":null} (nothing to recover, FR-036/FR-037). The legal forms are reflected verbatim:
	 * {@code 9223372036854775807} and {@code null}, and a string carrying quotes, a backslash, an
	 * escaped newline, a control character and non-ASCII — re-encoded with correct JSON escaping
	 * (dsl-json escapes {@code "}, {@code \} and control characters; non-ASCII passes as raw UTF-8).
	 * No encoder crash on any variant.
	 */
	@Test
	public void a13ExoticIdsAreRefusedOrReflectedWithoutCrashing() throws Exception {
		String[] refused = {
			"{\"jsonrpc\":\"2.0\",\"id\":{\"o\":1},\"method\":\"test.nope\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":[1,2],\"method\":\"test.nope\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"test.nope\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"test.nope\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":9223372036854775808,\"method\":\"test.nope\"}",
		};
		for (String variant : refused) {
			String response = exchange(eventloop, listen(), post(variant, "application/json"));
			String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.INVALID_REQUEST.code()));
			assertTrue("an unrecoverable id answers id null: " + body, body.contains("\"id\":null"));
		}

		// the largest legal integral id, reflected exactly
		String maxLong = exchange(eventloop, listen(), post(
			"{\"jsonrpc\":\"2.0\",\"id\":9223372036854775807,\"method\":\"test.nope\"}", "application/json"));
		assertTrue("the id is reflected exactly: " + maxLong,
			maxLong.contains("\"id\":9223372036854775807"));

		// id null — a legal id, reflected as null
		String nullId = exchange(eventloop, listen(), post(
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"test.nope\"}", "application/json"));
		assertTrue("null is reflected as null: " + nullId, nullId.contains("\"id\":null"));

		// the string id with quotes/backslash/escaped-newline/control/unicode — the response
		// re-encodes it with correct escaping and the -32601 answer carries it
		String exotic = "{\"jsonrpc\":\"2.0\",\"id\":\"a\\\"b\\\\c\\n\\u0001\\u00e9\\u4e2d\",\"method\":\"test.nope\"}";
		String response = exchange(eventloop, listen(), post(exotic, "application/json"));
		String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.METHOD_NOT_FOUND.code()));
		assertTrue("the string id is re-encoded with correct escaping (\\\", \\\\, \\n, \\u0001): " + body,
			body.contains("\"id\":\"a\\\"b\\\\c\\n\\u0001"));

		// byte-exact half: the wire helper decodes US-ASCII, so the raw-UTF-8 ride of the id's
		// non-ASCII characters is asserted on the dispatcher's own bytes — which the servlet writes
		// verbatim (the module's byte-parity tests pin that identity)
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		String utf8 = new String(io.activej.promise.TestUtils.await(
			dispatcher.dispatch(exotic.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
			java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("the escaped forms survive: " + utf8, utf8.contains("a\\\"b\\\\c\\n\\u0001"));
		assertTrue("non-ASCII rides as raw UTF-8, not mangled: " + utf8, utf8.contains("é中"));
		assertTrue("the answer is still -32601: " + utf8, utf8.contains("\"code\":-32601"));
	}

	// A14 -----------------------------------------------------------------------------------------

	/**
	 * Plan A14: a hostile method name is answered {@code -32601} without the name ever being echoed
	 * — a name with a raw control byte and a name with a distinctive marker are both absent from the
	 * response; a 900 KB name (under the 1 MB body bound) is refused just as cheaply, and the
	 * response stays tiny. An <b>empty</b> name is the frozen vector {@code empty-method}: the value
	 * model refuses it as {@code -32600} (the plan's cell lists it under {@code -32601}; the
	 * conformance corpus is authoritative and is recorded in the validation report).
	 */
	@Test
	public void a14HostileMethodNamesAreNeverEchoed() throws Exception {
		String marker = "ZZZQ_NEVER_ECHOED_ZZZQ";
		String[] variants = {
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no\u0000such\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\u00e9thode\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + marker + "\"}",
		};
		for (String variant : variants) {
			String response = exchange(eventloop, listen(), post(variant, "application/json"));
			String body = assert200WithCode(response, String.valueOf(JsonRpcErrors.METHOD_NOT_FOUND.code()));
			assertFalse("the hostile name must never be echoed: " + body, body.contains("NEVER_ECHOED"));
			assertFalse("the hostile name must never be echoed: " + body, body.contains("no\u0000such"));
			assertFalse("the hostile name must never be echoed: " + body, body.contains("thode"));
			assertEquals("the answer is exactly the -32601 document and nothing else",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}",
				body);
		}

		// a ~900 KB name: under the 1 MB body bound, so the servlet tier lets it through and the
		// dispatcher answers -32601 without echoing a byte of the name
		String hugeName = "HUGEMARKER" + "x".repeat(900_000);
		String huge = exchange(eventloop, listen(), post(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + hugeName + "\"}", "application/json"));
		String hugeBody = assert200WithCode(huge, String.valueOf(JsonRpcErrors.METHOD_NOT_FOUND.code()));
		assertFalse("a 900 KB name is never echoed: " + hugeBody, hugeBody.contains("HUGEMARKER"));
		assertTrue("the answer stays tiny: " + hugeBody.length(), hugeBody.length() < 200);

		// the empty name: -32600 per the frozen empty-method vector, id still reflected
		String empty = exchange(eventloop, listen(), post(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"\"}", "application/json"));
		String emptyBody = assert200WithCode(empty, String.valueOf(JsonRpcErrors.INVALID_REQUEST.code()));
		assertTrue("the id is still reflected: " + emptyBody, emptyBody.contains("\"id\":1"));
	}

	/**
	 * Plan A14's stats half: with a {@link JsonRpcDispatcher.JmxInspector} installed, hostile names
	 * must never create a {@code methodStats} entry — the row set is exactly the registered wire
	 * names, frozen at dispatcher {@code build()} (FR-034), and the miss is counted in the
	 * aggregate-only {@code methodNotFound} bucket.
	 */
	@Test
	public void a14HostileNamesNeverCreateAMethodStatsEntry() {
		JsonRpcDispatcher.JmxInspector inspector = new JsonRpcDispatcher.JmxInspector();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.withInspector(inspector)
			.build();

		byte[] hostile = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ZZZQ_NEVER_ECHOED_ZZZQ\"}".getBytes(US_ASCII);
		byte[] hostileControl = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"no\u0000such\"}".getBytes(US_ASCII);
		io.activej.promise.TestUtils.await(dispatcher.dispatch(hostile));
		io.activej.promise.TestUtils.await(dispatcher.dispatch(hostileControl));

		assertEquals("methodStats rows are exactly the registered wire names, nothing more",
			Set.of("test.add", "test.notify", "test.notifyAsync", "test.failDeliberately",
				"test.failWithData", "test.failAccidentally"),
			inspector.getMethodStats().keySet());
		assertEquals("both misses were counted in the aggregate-only bucket",
			2, inspector.getMethodNotFound().getTotalCount());
	}

	// A19 -----------------------------------------------------------------------------------------

	/**
	 * Plan A19: an empty body and a BOM-prefixed document are {@code -32700} (the empty body is
	 * also pinned by {@code JsonRpcServletTest.aZeroLengthBodyYields200Carrying32700Not204}; the BOM
	 * is the frozen {@code leading-bom} vector — rejected, never silently stripped); surrounding
	 * JSON whitespace is insignificant per RFC 8259 and is accepted.
	 */
	@Test
	public void a19BomIs32700AndSurroundingWhitespaceIsAccepted() throws Exception {
		String bom = exchange(eventloop, listen(), post(
			"\uFEFF{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}",
			"application/json"));
		assert200WithCode(bom, String.valueOf(JsonRpcErrors.PARSE_ERROR.code()));

		String whitespace = exchange(eventloop, listen(), post(
			"  \n\t{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}\r\n ",
			"application/json"));
		assertTrue("whitespace-surrounded JSON is accepted: " + whitespace,
			whitespace.contains("\"result\":{\"sum\":3}"));
	}

	// -------------------------------------------------------------------------------------------

	private static String batchOf(int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
				.append(",\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}");
		}
		return sb.append(']').toString();
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}

	private static byte[] concat(byte[]... parts) {
		int total = 0;
		for (byte[] part : parts) total += part.length;
		byte[] result = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, pos, part.length);
			pos += part.length;
		}
		return result;
	}
}
