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

import io.activej.common.MemSize;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchangeHead;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * FR-025 (T032): <b>one real exchange (or entry) per path</b> through {@link JsonRpcServlet}, with
 * {@link ByteBufRule} active at class level — the rule turns any buffer the servlet receives or
 * creates without recycling into a build failure. Each case also asserts its status, so a leak and
 * a wrong behaviour are distinguishable failures.
 * <p>
 * The ledger is deliberately sequential (plan §4 T032): the class does not compile before T031
 * ({@code withMaxBodySize} unresolved); cases 6–7 flip green with T027; cases 3–4 need T030's
 * {@code 413} branch plus T031's setter. Case 4's 33 kB body against the 1 kB bound is the R2
 * probe's shape — at least three 16 kB reads, so the crossing is guaranteed mid-stream.
 */
public final class JsonRpcServletBufferTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The connection tier's hardcoded mid-stream refusal (probe R2's exact wire form). */
	private static final String CONNECTION_TIER_400 =
		"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";

	private Eventloop eventloop;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcServlet servlet;
	private JsonRpcServlet smallBoundServlet;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
		smallBoundServlet = JsonRpcServlet.builder(eventloop, dispatcher)
			.withMaxBodySize(MemSize.kilobytes(1))
			.build();
	}

	/** Builds a server over {@code servlet} and listens on {@code :0}. */
	private JsonRpcHttpTestServer listen(JsonRpcServlet servlet) throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		return server;
	}

	/** Path 1 — success: one request dispatches, the encoder's bytes come back, every buffer recycled. */
	@Test
	public void aSuccessfulDispatchRecyclesEverything() throws Exception {
		String doc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
		byte[] expected = await(dispatcher.dispatch(doc.getBytes(US_ASCII)));

		String response = exchange(eventloop, listen(servlet), post(doc, "application/json"));

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body must be the dispatcher's bytes, unaltered: " + response,
			headAndBody[1].equals(new String(expected, US_ASCII)));
	}

	/** Path 2 — an unparseable body dispatches to {@code -32700} inside a {@code 200}, buffers recycled. */
	@Test
	public void anUnparseableBodyRecyclesEverything() throws Exception {
		String doc = "this is not json";
		byte[] expected = await(dispatcher.dispatch(doc.getBytes(US_ASCII)));

		String response = exchange(eventloop, listen(servlet), post(doc, "application/json"));

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32700: " + response, headAndBody[1].contains("\"code\":-32700"));
		assertTrue("the body must be the dispatcher's bytes, unaltered: " + response,
			headAndBody[1].equals(new String(expected, US_ASCII)));
	}

	/**
	 * Path 3 — bound exceeded via {@code Content-Length}: a 1 kB-bound servlet refuses a declared
	 * 2000 bytes with {@code 413}, head-only, before any body byte is read. (Compile-red until T031;
	 * behaviour red until T030 — plan decision D6.)
	 */
	@Test
	public void aDeclaredOversizeContentLengthRecyclesEverything() throws Exception {
		String head = exchangeHead(eventloop, listen(smallBoundServlet),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 2000\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assertTrue("the servlet answers 413 for the declared oversize: " + head,
			head.startsWith("HTTP/1.1 413 Payload Too Large"));
	}

	/**
	 * Path 4 — bound exceeded mid-stream: the same 1 kB-bound servlet, a 33 kB body delivered
	 * <b>chunked</b>. With the row-3 check in place a <b>declared</b> {@code Content-Length} over the
	 * bound is always the servlet's up-front {@code 413} (path 3) — the mid-stream crossing is only
	 * reachable without a declared length, exactly like probe R2's fixture-servlet shape (the
	 * fixture has no row-3 check, which is why the probe saw the {@code 400} with a declared length).
	 * Here {@code loadBody} accumulates the chunked stream, the bound fires mid-accumulation, and the
	 * connection tier answers its hardcoded {@code 400} + close — the R2 wire form.
	 */
	@Test
	public void aMidStreamCrossingRecyclesEverything() throws Exception {
		StringBuilder request = new StringBuilder(
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Transfer-Encoding: chunked\r\n" +
				"Connection: close\r\n" +
				"\r\n");
		String chunk = "x".repeat(11_000);
		for (int i = 0; i < 3; i++) {
			request.append(Integer.toHexString(chunk.length())).append("\r\n").append(chunk).append("\r\n");
		}
		request.append("0\r\n\r\n");

		String response = exchange(eventloop, listen(smallBoundServlet), request.toString());

		assertTrue("the connection tier's hardcoded 400 answers the mid-stream crossing:\n" + response,
			response.startsWith(CONNECTION_TIER_400));
	}

	/**
	 * Path 5 — dispatcher-implementation failure: {@code test.failAccidentally} throws, the total
	 * dispatcher answers {@code -32603} inside a {@code 200} (ADR-033 — the servlet's no-failure
	 * branch design means the error travels as a document), and every buffer on the path is released.
	 */
	@Test
	public void aDispatcherFailureRecyclesEverything() throws Exception {
		String doc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.failAccidentally\"}";
		byte[] expected = await(dispatcher.dispatch(doc.getBytes(US_ASCII)));

		String response = exchange(eventloop, listen(servlet), post(doc, "application/json"));

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("an implementation failure is still 200: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the body carries -32603: " + response, headAndBody[1].contains("\"code\":-32603"));
		assertTrue("the body must be the dispatcher's bytes, unaltered: " + response,
			headAndBody[1].equals(new String(expected, US_ASCII)));
	}

	/** Path 6 — {@code 405}: the method gate answers before anything is read, buffers none. */
	@Test
	public void aWrongMethodRecyclesEverything() throws Exception {
		String response = exchange(eventloop, listen(servlet),
			"GET / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 405 Method Not Allowed"));
		assertTrue("Allow: POST: " + response, headAndBody[0].contains("Allow: POST"));
	}

	/** Path 7 — {@code 415}: the media-type gate answers before anything is decoded, buffers none. */
	@Test
	public void aWrongMediaTypeRecyclesEverything() throws Exception {
		String head = exchangeHead(eventloop, listen(servlet),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: text/plain\r\n" +
				"Content-Length: 100\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		assertTrue("status line: " + head, head.startsWith("HTTP/1.1 415 Unsupported Media Type"));
	}

	/**
	 * Path 8 — off-reactor entry: the guard fires before anything is read (FR-011, WI-1), so no
	 * buffer exists on this path; {@code ByteBufRule} still watches the other seven. The servlet is
	 * built <b>inside</b> {@code loop.submit(...)} — building on the JUnit thread would not fail and
	 * the case would prove nothing (T024's lesson; the T024 caveat is about {@code EventloopRule}
	 * alone being unable to reproduce the violation, not about the rule being harmful alongside a
	 * dedicated loop). The request is bodyless (D7): {@code HttpMessage.recycle()} is
	 * package-private, so a test cannot recycle a request it built with a body.
	 */
	@Test
	public void anOffReactorEntryIsRefusedBeforeAnythingIsRead() {
		EventloopThread loop = EventloopThread.create("jsonrpc-http-buffer-test");
		try {
			AtomicReference<JsonRpcServlet> servletRef = new AtomicReference<>();
			loop.submit(() -> {
				JsonRpcDispatcher loopDispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
					.build();
				servletRef.set(JsonRpcServlet.create(loop.eventloop(), loopDispatcher));
			});
			JsonRpcServlet loopServlet = servletRef.get();
			HttpRequest request = HttpRequest.post("http://localhost/").build();

			try {
				loopServlet.serve(request);
			} catch (Throwable caught) {
				assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
						   caught.getClass(),
					IllegalStateException.class, caught.getClass());
				return;
			}
			fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
		} finally {
			loop.close();
		}
	}
}
