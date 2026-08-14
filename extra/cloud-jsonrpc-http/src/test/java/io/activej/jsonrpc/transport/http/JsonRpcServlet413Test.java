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
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchangeHead;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Row 3 of the HTTP semantics table (T028): the request-size bound, pinned over a real socket with
 * the servlet's <b>default</b> bound ({@code JsonRpcLimits.MAX_BODY_SIZE}, 1 MB) — T028 runs before
 * T031 and must not need the builder setter.
 * <p>
 * The two-tier outcome IS the requirement (FR-022, FR-023 as amended by probe R2):
 * <ul>
 *     <li>a declared {@code Content-Length} above the bound yields the servlet's {@code 413}
 *     <b>without reading the body</b> — the {@code exchangeHead} proof, case 1;</li>
 *     <li>a body crossing the bound mid-stream is answered by the <b>connection tier's</b>
 *     hardcoded {@code HTTP/1.1 400 Bad Request / Connection: close / Content-Length: 0} before the
 *     servlet's failure callback can run — the R2 wire form, cases 2–3;</li>
 *     <li>a body exactly <b>at</b> the bound is accepted — {@code loadBody}'s check fires only
 *     before appending a chunk, so an accumulator reaching exactly {@code max} completes, and the
 *     servlet's up-front comparison is strict {@code >}, case 4 (plan decision D2).</li>
 * </ul>
 * The ledger: case 1 was red (a {@code SocketTimeoutException} — the pre-T030 servlet calls
 * {@code loadBody} and waits for a body that never comes) until T030; case 2's original 400
 * expectation described the pre-T030 servlet and is superseded (see the case's javadoc — with the
 * row-3 check, a declared oversize is the servlet's {@code 413}, and the connection-tier {@code 400}
 * is only reachable for bodies without a declared length); cases 3–4 pin core-http's own behaviour
 * and are green by construction (plan decision D8).
 */
public final class JsonRpcServlet413Test {
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

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
	}

	/**
	 * Builds a server over the servlet wrapped in a counting servlet — {@code served} observes that
	 * {@code serve()} WAS entered (the connection tier's {@code 400} is only ever written without
	 * it, probe R3), and that the refusal came from inside the servlet. {@code withOnRequest} fires
	 * only in the fixture servlet, which {@code withServlet} replaces.
	 */
	private JsonRpcHttpTestServer listen(AtomicInteger served) throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(request -> {
				served.incrementAndGet();
				return servlet.serve(request);
			})
			.build();
		server.listen();
		return server;
	}

	/**
	 * FR-022, semantics row 3: a declared {@code Content-Length} of 2 MB — above the 1 MB default
	 * bound, below the 100 MB connection tier — yields {@code 413} <b>without reading the body</b>.
	 * The declared body is never sent, and the head still arrives promptly: {@code Content-Length}
	 * alone decided it. {@code served == 1} proves the servlet answered (only it writes {@code 413}).
	 */
	@Test
	public void aDeclaredOversizeContentLengthIs413WithoutReadingTheBody() throws Exception {
		AtomicInteger served = new AtomicInteger();
		String head = exchangeHead(eventloop, listen(served),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 2000000\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue("status line: " + head, headAndBody[0].startsWith("HTTP/1.1 413 Payload Too Large"));
		assertNoContentType(head);
		assertEquals("a 413 must carry no body: " + head, "", headAndBody[1]);
		assertEquals("the servlet answered (only it writes 413)", 1, served.get());
	}

	/**
	 * FR-022's precedence over the mid-stream path, pinned with the plan's own declared length:
	 * a {@code Content-Length} of 1,100,000 — above the 1 MB default bound, below the 100 MB
	 * connection tier — is answered by the servlet's {@code 413} <b>up front</b>, before any body
	 * byte is read. Through the real servlet the connection-tier {@code 400} is <b>not reachable</b>
	 * with a declared {@code Content-Length}: row 3 pre-empts it for every declared oversize, so the
	 * mid-stream crossing is the chunked path's (case 3) and the gzip-decompressed path's
	 * (JsonRpcServletGzipTest case 1). The plan's original expectation of the R2 {@code 400} for
	 * this request described the pre-T030 servlet (no row-3 check) and is superseded by the
	 * implemented contract. {@code served == 1}: the servlet answered — the connection tier only
	 * ever writes its {@code 400} without entering the servlet (probe R3).
	 */
	@Test
	public void aBodyCrossingTheBoundMidStreamIsTheConnectionTiers400() throws Exception {
		AtomicInteger served = new AtomicInteger();
		String head = exchangeHead(eventloop, listen(served),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 1100000\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(head);
		assertTrue("row 3 answers the declared oversize before any body read: " + head,
			headAndBody[0].startsWith("HTTP/1.1 413 Payload Too Large"));
		assertFalse("the connection tier's 400 is not reachable with a declared Content-Length: " + head,
			head.contains("400"));
		assertEquals("a 413 must carry no body: " + head, "", headAndBody[1]);
		assertEquals("the servlet answered (only it writes 413)", 1, served.get());
	}

	/**
	 * FR-021/FR-022: a chunked body with <b>no</b> {@code Content-Length} is bounded identically —
	 * there is no declared length for the servlet's row-3 check to refuse, so the refusal happens
	 * during accumulation inside {@code loadBody}, and the connection tier answers its same
	 * hardcoded {@code 400}. Chunks total 1.1 MB; the connection's chunked decoder is bounded at its
	 * own 100 MB tier, so this crossing is the servlet-tier bound's.
	 */
	@Test
	public void aChunkedBodyWithoutContentLengthIsBoundedTheSame() throws Exception {
		StringBuilder request = new StringBuilder(
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Transfer-Encoding: chunked\r\n" +
				"Connection: close\r\n" +
				"\r\n");
		String chunk = "x".repeat(11_000);
		for (int i = 0; i < 100; i++) {
			request.append(Integer.toHexString(chunk.length())).append("\r\n").append(chunk).append("\r\n");
		}
		request.append("0\r\n\r\n");

		String response = exchange(eventloop, listen(new AtomicInteger()), request.toString());

		assertTrue("the chunked crossing takes the same connection-tier path:\n" + response,
			response.startsWith(CONNECTION_TIER_400));
		assertFalse("never the servlet's 413: " + response, response.contains("413"));
	}

	/**
	 * The {@code >} vs {@code >=} nuance (plan decision D2), pinned at the default bound: a valid
	 * JSON-RPC request padded to <b>exactly</b> 1,048,576 bytes — via an unknown member with a large
	 * string value, which the envelope ignores — is accepted: {@code 200} + the dispatcher's bytes.
	 * This also pins that the default IS {@code JsonRpcLimits.MAX_BODY_SIZE}: a smaller default
	 * would {@code 413} here, a larger one would pass case 1.
	 */
	@Test
	public void aBodyExactlyAtTheBoundIsAccepted() throws Exception {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2},\"pad\":\"";
		String doc = prefix + "x".repeat(1_048_576 - prefix.length() - 2) + "\"}";
		assertEquals("the padded document must be exactly MAX_BODY_SIZE bytes", 1_048_576, doc.length());
		byte[] expected = await(dispatcher.dispatch(doc.getBytes(US_ASCII)));
		assertTrue("the padded document is still a valid request", expected.length > 0);

		String response = exchange(eventloop, listen(new AtomicInteger()),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: " + doc.length() + "\r\n" +
				"Connection: close\r\n" +
				"\r\n" + doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("a body exactly at the bound is accepted: " + response,
			headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertEquals("the body must be the dispatcher's bytes, unaltered: " + response,
			new String(expected, US_ASCII), headAndBody[1]);
	}

	/** A bodyless response carries no {@code Content-Type} line (FR-017's rule, applied to the 413). */
	private static void assertNoContentType(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertFalse("no Content-Type may be written: " + response,
			Arrays.stream(headAndBody[0].split("\r\n")).anyMatch(line -> line.startsWith("Content-Type")));
	}
}
