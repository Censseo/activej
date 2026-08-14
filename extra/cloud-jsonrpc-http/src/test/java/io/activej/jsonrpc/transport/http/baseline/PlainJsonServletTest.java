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

package io.activej.jsonrpc.transport.http.baseline;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.StubHttpClient;
import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.impl.RawPayloadView;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The retention rule for the US5 denominator (T056 — FR-074a): the reference
 * {@link PlainJsonServlet} is a <b>correctness fixture</b>, and this Surefire-collected test pins
 * it to the JSON-RPC path — the reference servlet must return <b>the same payload</b> as
 * {@link JsonRpcServlet} for the same request. Without this, the denominator drifts from the
 * numerator and the {@code ProtocolOverheadHarness} ratio means nothing.
 * <p>
 * Parity is asserted on the <b>payload</b>, not the response bytes: the JSON-RPC path answers with
 * the response envelope ({@code {"jsonrpc":"2.0","id":1,"result":…}}), the reference servlet with
 * the bare result payload ({@code {"sum":5}}). The envelope's {@code result} member is extracted
 * with the <b>same decoder the dispatch path uses</b> ({@link JsonRpcDecoder#decode(byte[])} —
 * deferred payloads are index pairs into the document array, so the extracted slice is the
 * payload's verbatim bytes) and compared byte-for-byte with the reference response. The assertion
 * is <b>payload equality only — never timing</b> (FR-073, T059): a wall-clock threshold on a
 * shared CI runner would be a flaky build gate, and the harness reports instead of asserts.
 */
public final class PlainJsonServletTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The request shape the harness measures: one {@code test.add} call with named params (FR-071). */
	private static final String CANONICAL_REQUEST =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";

	private JsonRpcServlet jsonRpcServlet;
	private PlainJsonServlet plainServlet;

	@Before
	public void setUp() {
		Eventloop eventloop = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		jsonRpcServlet = JsonRpcServlet.create(eventloop, dispatcher);
		plainServlet = PlainJsonServlet.create(eventloop, TestApi.class, new TestApiImpl(), "test.add");
	}

	/**
	 * The load-bearing parity assertion: for the harness's exact request shape, the reference
	 * servlet's body is byte-identical to the {@code result} member of the JSON-RPC path's response.
	 */
	@Test
	public void thePlainServletReturnsTheJsonRpcPathsResultPayload() {
		Recorded jsonRpc = exchange(jsonRpcServlet, CANONICAL_REQUEST, true);
		Recorded plain = exchange(plainServlet, CANONICAL_REQUEST, true);

		assertEquals("the JSON-RPC path must answer 200: " + jsonRpc, 200, jsonRpc.code);
		byte[] expectedPayload = resultPayload(jsonRpc.body);
		assertArrayEquals("the reference servlet must return the same payload as the JSON-RPC path" +
				" (FR-074a):\n JSON-RPC: " + new String(jsonRpc.body, US_ASCII) +
				"\n reference: " + new String(plain.body, US_ASCII),
			expectedPayload, plain.body);
	}

	/**
	 * The reference response's HTTP shape: {@code 200} + {@code application/json} — the same HTTP
	 * envelope the JSON-RPC path uses, so the ratio isolates the JSON-RPC envelope and dispatch
	 * rather than the HTTP transport.
	 */
	@Test
	public void thePlainResponseIs200WithApplicationJson() {
		Recorded plain = exchange(plainServlet, CANONICAL_REQUEST, true);

		assertEquals("the reference servlet answers 200", 200, plain.code);
		assertEquals("the reference servlet carries the same Content-Type as the JSON-RPC path",
			"application/json", plain.headers.get("Content-Type"));
		assertTrue("the reference servlet must return a non-empty payload", plain.body.length > 0);
	}

	/**
	 * Parity holds for the other calling convention too: {@code ParamsCodec} reads the style from
	 * the first token, and the reference servlet uses the same codec — so a positional-params
	 * request must produce the same payload on both paths as well.
	 */
	@Test
	public void parityHoldsForPositionalParams() {
		String positional = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"test.add\",\"params\":[4,5]}";

		Recorded jsonRpc = exchange(jsonRpcServlet, positional, true);
		Recorded plain = exchange(plainServlet, positional, true);

		assertArrayEquals("positional-params parity (FR-074a): " + new String(jsonRpc.body, US_ASCII),
			resultPayload(jsonRpc.body), plain.body);
	}

	/**
	 * The reference servlet is an endpoint for exactly one wire method: a request that is not a
	 * {@code test.add} request document is refused with a plain HTTP {@code 400} — no JSON-RPC
	 * envelope is produced anywhere on the reference path (FR-070).
	 */
	@Test
	public void aNonCanonicalDocumentIsRefusedWith400NotAnEnvelope() {
		Recorded plain = exchange(plainServlet,
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"hi\"}}", false);

		assertEquals("a non-canonical document is a plain HTTP refusal on the reference path", 400, plain.code);
		assertEquals("the refusal carries no Content-Type — it is not a JSON document", null,
			plain.headers.get("Content-Type"));
		assertEquals("the refusal carries no JSON-RPC envelope", 0, plain.body.length);
	}

	// ---------------------------------------------------------------------------------------------------
	// Helpers.
	// ---------------------------------------------------------------------------------------------------

	/** The {@code result} member of a JSON-RPC response document, as its verbatim bytes (verdict 00-A). */
	private static byte[] resultPayload(byte[] jsonRpcBody) {
		// a response document always decodes to a single JsonRpcDecoded, never a batch (feature 010)
		JsonRpcDecoded decoded = (JsonRpcDecoded) JsonRpcDecoder.decode(jsonRpcBody);
		assertTrue("the JSON-RPC path must answer a response document, got: " +
			decoded.getClass().getSimpleName() + " — " + new String(jsonRpcBody, US_ASCII),
			decoded instanceof JsonRpcResponse);
		JsonRpcResponse response = (JsonRpcResponse) decoded;
		assertTrue("the JSON-RPC path must answer a result, not an error: " + new String(jsonRpcBody, US_ASCII),
			response.error() == null);
		JsonRpcPayload result = response.result();
		assertTrue("a decoded result is a deferred raw slice (verdict 00-A), got: " +
			result.getClass().getSimpleName(), result instanceof JsonRpcPayload.Raw);
		RawPayloadView view = ((JsonRpcPayload.Raw) result).view();
		return Arrays.copyOfRange(view.array(), view.start(), view.end());
	}

	/**
	 * One in-process exchange: the request POSTed as {@code application/json}, response captured.
	 * When {@code bodyExpected} is false the body is not touched — a bodiless response has no body
	 * stream at all and {@code loadBody} would throw; the empty body IS the assertion
	 * ({@code InteropVectorsTest} idiom).
	 */
	private static Recorded exchange(AsyncServlet servlet, String document, boolean bodyExpected) {
		HttpRequest request = HttpRequest.post("http://localhost/")
			.withHeader(CONTENT_TYPE, "application/json")
			.withBody(document.getBytes(US_ASCII))
			.build();
		return await(StubHttpClient.of(servlet).request(request)
			.then(response -> {
				int code = response.getCode();
				Map<String, String> headers = new LinkedHashMap<>();
				String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
				if (contentType != null) headers.put("Content-Type", contentType);
				return bodyExpected ?
					response.loadBody().map(body -> new Recorded(code, headers, body.asArray())) :
					Promise.of(new Recorded(code, headers, new byte[0]));
			}));
	}

	/** What one exchange produced, captured before StubHttpClient's recycle post could run. */
	private record Recorded(int code, Map<String, String> headers, byte[] body) {}
}
