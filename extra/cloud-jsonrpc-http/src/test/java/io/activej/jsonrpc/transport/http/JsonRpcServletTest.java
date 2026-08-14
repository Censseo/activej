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
import java.util.Arrays;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The accepted path of {@link JsonRpcServlet} (T015) and its empty-response cases (T020), pinned
 * against the HTTP semantics table's rows 5 and 6
 * ({@code contracts/http-semantics.md} §2) over a real socket.
 * <p>
 * Every case POSTs one JSON-RPC document as {@code application/json} and asserts the raw response:
 * the status line, the {@code Content-Type: application/json} line, and body bytes equal to
 * {@code await(dispatcher.dispatch(doc))} — the servlet's contract is that it writes the
 * dispatcher's bytes <b>unaltered</b> (FR-013), so the expected bytes are read from the dispatcher
 * itself rather than hardcoded. A JSON-RPC error document is a well-formed response, so errors
 * travel inside {@code 200} (FR-014).
 * <p>
 * The expected bytes are computed <b>before</b> the raw exchange starts: the eventloop can only be
 * driven by one thread at a time, and dispatch over {@link TestApiImpl} completes synchronously,
 * so no loop run is needed for it.
 */
public final class JsonRpcServletTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private Eventloop eventloop;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcServlet servlet;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
	}

	/** Builds a server over the {@link JsonRpcServlet} under test and listens on {@code :0}. */
	private JsonRpcHttpTestServer listen() throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		return server;
	}

	/** One raw exchange: {@code document} POSTed as {@code application/json}, full response back. */
	private String exchangeDocument(String document) throws Exception {
		return exchange(eventloop, listen(), post(document, "application/json"));
	}

	/** The dispatcher's own answer for {@code document} — the bytes the servlet must put on the wire unaltered. */
	private byte[] expectedBytes(String document) {
		return await(dispatcher.dispatch(document.getBytes(US_ASCII)));
	}

	/** The count of {@code needle} occurrences in {@code body} — batch-size assertions read as counts, not parses. */
	private static int countOccurrences(String body, String needle) {
		return body.split(needle, -1).length - 1;
	}

	/** FR-017: a response with no body must carry no {@code Content-Type} line, whatever its status. */
	private static void assertNoContentType(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertFalse("no Content-Type may be written: " + response,
			Arrays.stream(headAndBody[0].split("\r\n")).anyMatch(line -> line.startsWith("Content-Type")));
	}

	// T015 — the accepted path ----------------------------------------------------------------

	/**
	 * Acceptance scenario US1-1: a request yields {@code 200} + {@code Content-Type: application/json}
	 * + exactly the encoder's bytes for the corresponding response.
	 */
	@Test
	public void aRequestYields200ApplicationJsonAndTheEncodedBytes() throws Exception {
		String doc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
		byte[] expected = expectedBytes(doc);

		String response = exchangeDocument(doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type must be exactly application/json: " + response,
			headAndBody[0].contains("Content-Type: application/json"));
		assertEquals("the body must be the encoder's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/**
	 * Acceptance scenario US1-2: an unknown method is a JSON-RPC error document inside a {@code 200} —
	 * a well-formed JSON-RPC response is a successful HTTP exchange (FR-014).
	 */
	@Test
	public void anUnknownMethodStillYields200Carrying32601() throws Exception {
		String doc = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.nope\",\"params\":[]}";
		byte[] expected = expectedBytes(doc);

		String response = exchangeDocument(doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an unknown method is still 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32601: " + response, headAndBody[1].contains("\"code\":-32601"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/**
	 * Acceptance scenario US1-3: a body that is not valid JSON yields {@code 200} carrying
	 * {@code -32700 Parse error} — never a non-2xx status.
	 */
	@Test
	public void anUnparseableBodyYields200Carrying32700() throws Exception {
		String doc = "this is not json";
		byte[] expected = expectedBytes(doc);

		String response = exchangeDocument(doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an unparseable body is still 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32700: " + response, headAndBody[1].contains("\"code\":-32700"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/**
	 * Acceptance scenario US1-4: a batch of five requests yields {@code 200} with a five-element
	 * array — element for element, asserted by bytes and by counting the five results.
	 */
	@Test
	public void aBatchOfFiveYields200WithAFiveElementArray() throws Exception {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 1; i <= 5; i++) {
			if (i > 1) sb.append(',');
			sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
				.append(",\"method\":\"test.add\",\"params\":{\"a\":").append(i)
				.append(",\"b\":").append(i).append("}}");
		}
		sb.append(']');
		String doc = sb.toString();
		byte[] expected = expectedBytes(doc);

		String response = exchangeDocument(doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("a batch is still 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("a batch answer is an array: " + response, headAndBody[1].startsWith("["));
		assertEquals("a batch of five is answered element for element: " + response, 5,
			countOccurrences(headAndBody[1], "\"sum\":"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/**
	 * Semantics table §2.2 row 2 (review-1 MEDIUM-1): a <b>zero-length</b> body is as unparseable
	 * as any other non-JSON body, so the dispatcher answers {@code -32700 Parse error} — a
	 * non-empty document — and the HTTP answer is {@code 200} carrying it, <b>never</b> {@code 204}
	 * (the dispatcher's result is not empty, so the empty-response branch cannot fire) and never
	 * {@code 415} (the media type is present and accepted). Reachable by every real-world caller —
	 * {@code curl --data-binary ''}, an empty {@code fetch} body.
	 */
	@Test
	public void aZeroLengthBodyYields200Carrying32700Not204() throws Exception {
		byte[] expected = expectedBytes("");
		assertTrue("the dispatcher answers an empty body with -32700, not 'no response'", expected.length > 0);

		String response = exchangeDocument("");

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an empty body is -32700 inside a 200, never a 204: " + response,
			headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32700: " + response, headAndBody[1].contains("\"code\":-32700"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	// T020 — the empty-response cases ---------------------------------------------------------

	/**
	 * FR-017, semantics table row 5: a notification produces no response document, so the HTTP
	 * answer is {@code 204} with no body and no {@code Content-Type}. Per probe R1's observed wire
	 * form, {@code Content-Length: 0} IS present — nothing to suppress, nothing to add.
	 */
	@Test
	public void aNotificationYields204WithNoBodyAndNoContentType() throws Exception {
		String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"hi\"}}";
		byte[] expected = expectedBytes(notification);
		assertEquals("the dispatcher answers a lone notification with nothing", 0, expected.length);

		String response = exchangeDocument(notification);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 204 No Content"));
		assertTrue("core-http adds Content-Length: 0 to a bodyless 204 (probe R1): " + response,
			headAndBody[0].contains("Content-Length: 0"));
		assertNoContentType(response);
		assertEquals("a notification must carry no body: " + response, "", headAndBody[1]);
	}

	/** An all-notification batch produces no response document either — the same {@code 204} shape. */
	@Test
	public void anAllNotificationBatchYieldsTheSame204() throws Exception {
		String batch = "[" +
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"one\"}}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"two\"}}" +
			"]";
		byte[] expected = expectedBytes(batch);
		assertEquals("the dispatcher answers an all-notification batch with nothing", 0, expected.length);

		String response = exchangeDocument(batch);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an all-notification batch is 204: " + response, headAndBody[0].startsWith("HTTP/1.1 204 No Content"));
		assertNoContentType(response);
		assertEquals("an all-notification batch must carry no body: " + response, "", headAndBody[1]);
	}

	/**
	 * Edge case: a batch mixing one request and one notification yields {@code 200} with <b>only the
	 * request's</b> response — a one-element array, pinned by comparing the body bytes with the
	 * dispatcher's own output for the same document (the dispatcher's "requests only, in request
	 * order" promise, asserted end to end).
	 */
	@Test
	public void aMixedBatchYields200WithOnlyTheRequestsResponse() throws Exception {
		String batch = "[" +
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"ignored\"}}" +
			"]";
		byte[] expected = expectedBytes(batch);
		assertTrue("the dispatcher answers a mixed batch with the requests' responses only", expected.length > 0);

		String response = exchangeDocument(batch);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("a mixed batch is 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type: " + response, headAndBody[0].contains("Content-Type: application/json"));
		assertEquals("one request in, its response alone out — asserted by bytes: " + response, 1,
			countOccurrences(headAndBody[1], "\"sum\":"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/**
	 * Edge case: an empty JSON array {@code []} is invalid per JSON-RPC 2.0 §6 — the envelope
	 * answers {@code -32600 Invalid Request}, which is a non-empty document, so the HTTP answer is
	 * {@code 200} carrying it, explicitly <b>not</b> {@code 204}.
	 */
	@Test
	public void anEmptyArrayYields200Carrying32600Not204() throws Exception {
		String doc = "[]";
		byte[] expected = expectedBytes(doc);
		assertTrue("an empty batch is -32600 Invalid Request, not 'no response'", expected.length > 0);

		String response = exchangeDocument(doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an empty array is -32600 inside a 200, never a 204: " + response,
			headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32600: " + response, headAndBody[1].contains("\"code\":-32600"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}
}
