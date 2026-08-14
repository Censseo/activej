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
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The rejection rows of the HTTP semantics table (T026) — rows 1 and 2 of
 * {@code contracts/http-semantics.md} §2, pinned over a real socket against
 * {@link JsonRpcServlet}: a non-{@code POST} method is {@code 405} with {@code Allow: POST}
 * <b>without reading the body</b> (FR-015, FR-096); a {@code Content-Type} outside the allow-list,
 * or absent, is {@code 415} <b>without decoding the body</b> (FR-016).
 * <p>
 * The order is part of the contract: row 1 precedes row 3, so a {@code GET} carrying an oversized
 * declared {@code Content-Length} is {@code 405}, never {@code 413} — that is
 * {@code http-semantics.md} §2's own example, asserted by case 5.
 * <p>
 * The "body was never read" proof is the {@code exchangeHead} fixture (plan decision D4): the
 * request declares a {@code Content-Length} and sends <b>no body bytes</b>, and the response must
 * arrive without the servlet ever waiting for them. A servlet that read the body first would block
 * until the bounded socket read times out — the red state this class observes before T027.
 */
public final class JsonRpcServletRejectionTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private Eventloop eventloop;
	private JsonRpcServlet servlet;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
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

	// row 1 — the method gate --------------------------------------------------------------------

	/**
	 * FR-015 / FR-096, semantics row 1: {@code GET} is not implemented — {@code 405}, header
	 * {@code Allow: POST}, empty body, no {@code Content-Type}. A plain {@code GET} has no body, so
	 * {@code BODY_RECEIVED} is set with the empty body and the standard {@code exchange} works.
	 */
	@Test
	public void aGetYields405WithAllowPostAndAnEmptyBody() throws Exception {
		String response = exchange(eventloop, listen(),
			"GET / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assert405Shape(response);
	}

	/** Same {@code 405} shape for {@code DELETE} — every non-{@code POST} method is refused identically. */
	@Test
	public void aDeleteYieldsTheSame405() throws Exception {
		String response = exchange(eventloop, listen(),
			"DELETE / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assert405Shape(response);
	}

	/**
	 * Same {@code 405} shape for {@code HEAD}. No {@code Content-Length} on this request: the
	 * connection zeroes the declared length only for {@code GET}/{@code DELETE}
	 * ({@code HttpServerConnection.java:266-268}), so a HEAD with a declared length would make the
	 * connection read a body it should not have.
	 */
	@Test
	public void aHeadYieldsTheSame405() throws Exception {
		String response = exchange(eventloop, listen(),
			"HEAD / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assert405Shape(response);
	}

	/**
	 * The "body unread" proof for the method gate: a {@code PUT} declaring {@code Content-Length: 5}
	 * with <b>no body bytes</b> after the head is answered {@code 405} promptly — the head arrives
	 * without the servlet ever waiting for the declared body. A servlet that read the body first
	 * would block until {@code soTimeout} and the test would fail with a
	 * {@code SocketTimeoutException}.
	 */
	@Test
	public void aPutWithADeclaredBodyIs405WithoutReadingIt() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"PUT / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Length: 5\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assert405Shape(head);
	}

	/**
	 * The order is the contract (http-semantics §2): row 1 precedes row 3. A {@code GET} declaring a
	 * 20 MB {@code Content-Length} — under the 100 MB connection tier, so probe R3's connection-tier
	 * {@code 400} cannot pre-empt the servlet — is {@code 405}, never {@code 413}, never {@code 400}.
	 * <p>
	 * The head-only read is deliberate: although the start-line handling zeroes {@code contentLength}
	 * for {@code GET} ({@code HttpServerConnection.java:266-268}), the {@code Content-Length} header
	 * parse re-sets it ({@code AbstractHttpConnection}'s header loop), so the connection waits for the
	 * declared body after the {@code 405} and never closes. The head still arrives promptly — the
	 * method gate fires before any body read — which is exactly what this case pins.
	 */
	@Test
	public void aGetWithAnOversizedDeclaredLengthIs405Not413() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"GET / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Length: 20000000\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assert405Shape(head);
		assertFalse("row 3 must not pre-empt row 1: " + head, head.contains("413"));
		assertFalse("the connection tier must not pre-empt the servlet here: " + head, head.contains("400"));
	}

	// row 2 — the media-type gate ----------------------------------------------------------------

	/**
	 * FR-016, semantics row 2: a {@code POST} with {@code Content-Type: text/plain} is {@code 415} —
	 * empty body, no {@code Content-Type}, no {@code Allow} — and the body is <b>not decoded</b>:
	 * the declared 100-byte body is never sent, and the head still arrives promptly (the
	 * {@code exchangeHead} proof).
	 */
	@Test
	public void aPostWithTextPlainIs415WithoutDecodingTheBody() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: text/plain\r\n" +
				"Content-Length: 100\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue("status line: " + head, headAndBody[0].startsWith("HTTP/1.1 415 Unsupported Media Type"));
		assertNoContentType(head);
		assertFalse("a 415 carries no Allow: " + head, headAndBody[0].contains("Allow"));
		assertEquals("a 415 must carry no body: " + head, "", headAndBody[1]);
	}

	/**
	 * FR-016's strict absence rule: a request with <b>no</b> {@code Content-Type} header at all is
	 * rejected, not assumed — {@code request.getHeader(CONTENT_TYPE)} is {@code null} and
	 * {@code JsonRpcHttpMediaTypes.isAccepted(null)} is {@code false}. No body is declared and none
	 * is read; the {@code 415} answers regardless.
	 */
	@Test
	public void aPostWithoutAnyContentTypeIs415() throws Exception {
		String head = exchangeHead(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue("status line: " + head, headAndBody[0].startsWith("HTTP/1.1 415 Unsupported Media Type"));
		assertNoContentType(head);
		assertEquals("a 415 must carry no body: " + head, "", headAndBody[1]);
	}

	// -------------------------------------------------------------------------------------------

	/** The shared row-1 shape: status line, {@code Allow: POST}, no {@code Content-Type}, empty body. */
	private static void assert405Shape(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 405 Method Not Allowed"));
		assertTrue("Allow: POST must be present: " + response, headAndBody[0].contains("Allow: POST"));
		assertNoContentType(response);
		assertEquals("a 405 must carry no body: " + response, "", headAndBody[1]);
	}

	/** FR-017's rule for any bodyless response: no {@code Content-Type} line, whatever the status. */
	private static void assertNoContentType(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertFalse("no Content-Type may be written: " + response,
			Arrays.stream(headAndBody[0].split("\r\n")).anyMatch(line -> line.startsWith("Content-Type")));
	}
}
