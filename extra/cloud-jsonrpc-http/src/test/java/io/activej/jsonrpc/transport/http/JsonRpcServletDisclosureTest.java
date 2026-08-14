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
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchangeHead;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FR-055 of feature 012, inherited (T034): <b>nothing derived from any exception reaches the
 * wire</b> — no exception message, no stack trace, no exception class name, no configuration value
 * — asserted for the {@code 405}, {@code 413} and {@code 415} rejections <b>and</b> for the
 * {@code -32603} document (http-semantics §2.3). Absence is asserted on the <b>full</b> response,
 * head and body: a leak could land in a header.
 * <p>
 * Where the {@code -32603} is rendered: inside the <b>dispatcher</b>
 * ({@code JsonRpcDispatcher}, {@code e instanceof JsonRpcException ? e.getError() :
 * JsonRpcErrors.INTERNAL_ERROR}), whose non-disclosure feature 012's {@code JsonRpcDisclosureTest}
 * already pins at the envelope. Case 4 pins that the servlet adds nothing on top: the wire bytes are
 * the dispatcher's, unaltered — compared against {@code await(dispatcher.dispatch(doc))}, never
 * hardcoded.
 */
public final class JsonRpcServletDisclosureTest {
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

	/**
	 * A {@code 405} discloses nothing: no exception text anywhere in the response, the only
	 * {@code Allow} value is {@code POST}, an empty body, and no {@code Content-Type}.
	 */
	@Test
	public void a405DisclosesNoExceptionAndNoConfig() throws Exception {
		String response = exchange(eventloop, listen(),
			"GET / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 405 Method Not Allowed"));
		String allowLine = Arrays.stream(headAndBody[0].split("\r\n"))
			.filter(line -> line.startsWith("Allow"))
			.findFirst()
			.orElse("");
		assertEquals("the only Allow value is POST: " + response, "Allow: POST", allowLine);
		assertEquals("an empty body: " + response, "", headAndBody[1]);
		assertFalse("no exception-derived text in the head: " + response, headAndBody[0].contains("Exception"));
		assertNoContentType(response);
	}

	/**
	 * A {@code 413} discloses nothing — in particular <b>no configuration value</b>: neither the
	 * declared length (2000000) nor the bound's value (1048576) nor the name {@code maxBodySize}
	 * appears in the response. The body is empty. (The automatic {@code Content-Length: 0} is
	 * core-http's mechanical header — probe R1 — not a configuration disclosure.)
	 */
	@Test
	public void a413DisclosesNoExceptionAndNoConfigValue() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 2000000\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 413 Payload Too Large"));
		assertEquals("an empty body: " + head, "", headAndBody[1]);
		assertFalse("the declared length must not be echoed: " + head, headAndBody[0].contains("2000000"));
		assertFalse("the bound's value must not appear: " + head, headAndBody[0].contains("1048576"));
		assertFalse("the setting's name must not appear: " + head, headAndBody[0].contains("maxBodySize"));
		assertFalse("no exception-derived text in the head: " + head, headAndBody[0].contains("Exception"));
		assertNoContentType(head);
	}

	/**
	 * A {@code 415} discloses nothing: an empty body and no exception-derived text anywhere in the
	 * response.
	 */
	@Test
	public void a415DisclosesNoExceptionAndNoConfig() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: text/plain\r\n" +
				"Content-Length: 100\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 415 Unsupported Media Type"));
		assertEquals("an empty body: " + head, "", headAndBody[1]);
		assertFalse("no exception-derived text in the head: " + head, headAndBody[0].contains("Exception"));
		assertNoContentType(head);
	}

	/**
	 * The {@code -32603} document discloses nothing from the throwable: the wire bytes are the
	 * dispatcher's own — compared, not hardcoded — and the throwable's message and class name appear
	 * nowhere in the full response. {@code TestApiImpl.failAccidentally} throws
	 * {@code IllegalStateException("an accidental failure; its message must never reach the wire")}.
	 */
	@Test
	public void a32603DisclosesNothingFromTheThrowable() throws Exception {
		String doc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.failAccidentally\"}";
		byte[] expected = await(dispatcher.dispatch(doc.getBytes(US_ASCII)));
		assertTrue("the dispatcher answers -32603, a non-empty document", expected.length > 0);

		String response = exchange(eventloop, listen(), post(doc, "application/json"));

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an implementation failure is still 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type: " + response, headAndBody[0].contains("Content-Type: application/json"));
		assertEquals("the wire bytes are the dispatcher's, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
		assertTrue("the document carries -32603: " + response, headAndBody[1].contains("\"code\":-32603"));
		assertTrue("and the standard message: " + response, headAndBody[1].contains("\"message\":\"Internal error\""));
		assertFalse("the throwable's message must never reach the wire: " + response,
			response.contains("an accidental failure"));
		assertFalse("the throwable's class name must never reach the wire: " + response,
			response.contains("IllegalStateException"));
	}

	/** A bodyless response carries no {@code Content-Type} line (FR-017's rule, applied to the rejections). */
	private static void assertNoContentType(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertFalse("no Content-Type may be written: " + response,
			Arrays.stream(headAndBody[0].split("\r\n")).anyMatch(line -> line.startsWith("Content-Type")));
	}
}
