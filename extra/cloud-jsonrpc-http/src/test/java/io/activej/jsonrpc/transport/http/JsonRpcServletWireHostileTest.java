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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Feature 014 adversarial validation, domain A (hostile peer) — the <b>wire-tier</b> scenarios:
 * the size-bound rows of the HTTP semantics table at a <b>configured 1 kb bound</b> (plan A1, A3),
 * the gzip bomb (A5), the media-type variants (A6), the non-{@code POST} method gate (A7) and the
 * smuggling-ish head vectors (A18). Each is pinned over a real socket; the connection-tier refusal
 * is the hardcoded {@code HTTP/1.1 400 Bad Request / Connection: close / Content-Length: 0} of
 * probes R2/R3, byte-identical for a malformed head and a mid-stream crossing.
 * <p>
 * The {@code 1 kb} bound is this class's own: {@code JsonRpcServlet.Builder.withMaxBodySize} is the
 * per-instance override the servlet contract provides (FR-020), and a small bound keeps the A1/A3
 * bodies cheap. The connection tier's own {@code 100 mb} bound is untouched (the plan's A2 is
 * pinned by {@code JsonRpcHttpWireProbesTest.r3_connectionTierAnswersOversizeContentLength} and the
 * chunked mid-stream crossing by {@code JsonRpcServlet413Test}, so neither is duplicated here).
 */
public final class JsonRpcServletWireHostileTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The connection tier's hardcoded refusal (probe R2's exact wire form). */
	private static final String CONNECTION_TIER_400 =
		"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";

	private Eventloop eventloop;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
	}

	private JsonRpcHttpTestServer listen() throws IOException {
		return listen(MemSize.megabytes(1));
	}

	/** A server over the real servlet with the given body bound, listening on {@code :0}. */
	private JsonRpcHttpTestServer listen(MemSize maxBodySize) throws IOException {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(JsonRpcServlet.builder(eventloop, dispatcher).withMaxBodySize(maxBodySize).build())
			.build();
		server.listen();
		return server;
	}

	// A1 / A3 — the servlet-tier bound at a configured 1 kb ---------------------------------------

	/**
	 * Plan A1 at the configured 1 kb bound: a declared {@code Content-Length} just above the bound
	 * is refused up front with {@code 413}, no body — and the <b>connection is kept</b>: no
	 * {@code Connection: close} is written, the declared body is drained, and a second request on
	 * the same socket is served normally. This is the servlet-tier refusal's distinguishing property
	 * versus the connection tier's {@code 400} + close.
	 */
	@Test
	public void a1ADeclaredOversizeAtThe1kbBoundIs413AndTheConnectionStaysAlive() throws Exception {
		JsonRpcHttpTestServer server = listen(MemSize.kilobytes(1));
		Thread eventloopThread = new Thread(eventloop);
		eventloopThread.start();
		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(10_000);
			socket.connect(new InetSocketAddress("localhost", server.port()));
			OutputStream output = socket.getOutputStream();

			// request 1: CL = 1025, just above the 1 kb bound; the declared body is sent, keep-alive
			String body1 = "x".repeat(1025);
			String head1 = "POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: " + body1.length() + "\r\n" +
				"\r\n";
			output.write(head1.getBytes(US_ASCII));
			output.write(body1.getBytes(US_ASCII));
			output.flush();

			String first = readResponse(socket.getInputStream());
			String[] headAndBody = splitHeadAndBody(first);
			assertTrue("status line: " + first, headAndBody[0].startsWith("HTTP/1.1 413 Payload Too Large"));
			assertEquals("a 413 must carry no body: " + first, "", headAndBody[1]);
			assertFalse("the connection is KEPT — no Connection: close: " + first,
				first.contains("Connection: close"));

			// request 2 on the SAME connection: served normally — the 413 did not close the socket
			String body2 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
			String head2 = "POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: " + body2.length() + "\r\n" +
				"Connection: close\r\n" +
				"\r\n";
			output.write(head2.getBytes(US_ASCII));
			output.write(body2.getBytes(US_ASCII));
			output.flush();

			String second = readResponse(socket.getInputStream());
			String[] secondParts = splitHeadAndBody(second);
			assertTrue("the kept connection serves the next request: " + second,
				secondParts[0].startsWith("HTTP/1.1 200 OK"));
			assertTrue("the second request's answer is the dispatcher's bytes: " + second,
				secondParts[1].contains("\"sum\":5"));
		} finally {
			server.closeFuture().get(10, TimeUnit.SECONDS);
			eventloopThread.join(10_000);
			if (eventloopThread.isAlive()) {
				throw new IllegalStateException("the eventloop thread did not stop");
			}
		}
	}

	/**
	 * Plan A3 at the configured 1 kb bound: a declared {@code Content-Length} exactly equal to the
	 * bound, with a body padded to exactly 1024 bytes, is <b>accepted</b> — the row-3 check is
	 * strict {@code >} (plan decision D2), and {@code loadBody}'s accumulation check fires only
	 * before appending a chunk. Complements the existing default-bound pin in
	 * {@code JsonRpcServlet413Test.aBodyExactlyAtTheBoundIsAccepted}.
	 */
	@Test
	public void a3ABodyExactlyAtTheConfiguredBoundIsAccepted() throws Exception {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2},\"pad\":\"";
		String doc = prefix + "x".repeat(1024 - prefix.length() - 2) + "\"}";
		assertEquals("the padded document must be exactly 1024 bytes", 1024, doc.length());

		String response = exchange(eventloop, listen(MemSize.kilobytes(1)),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: " + doc.length() + "\r\n" +
				"Connection: close\r\n" +
				"\r\n" + doc);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("a body exactly at the configured bound is accepted: " + response,
			headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("the dispatcher's bytes, unaltered: " + response,
			headAndBody[1].contains("\"sum\":3"));
	}

	// A5 — the gzip bomb -------------------------------------------------------------------------

	/**
	 * Plan A5 at a larger ratio than the existing pin: a gzip of 10,000,000 zeros — about 10 KB
	 * compressed, three orders of magnitude under the 1 MB default bound — decompresses to ten
	 * times the bound. {@code loadBody} decodes gzip <b>before</b> accumulating, so the refusal is
	 * the connection tier's hardcoded {@code 400} + close, mid-stream after decompression; a
	 * regression that left the body undecoded would answer {@code 200} with {@code -32700} and fail
	 * this test. The bound applies after decompression, so no unbounded allocation is possible.
	 */
	@Test
	public void a5AGzipBombDecompressingTenTimesTheBoundIsRefusedByTheConnectionTier() throws Exception {
		byte[] compressed = gzip(new byte[10_000_000]);
		assertTrue("10 MB of zeros must compress to well under the default bound",
			compressed.length < 1_048_576);
		assertTrue("the bomb ratio is at least ten to one: " + compressed.length,
			compressed.length < 1_048_576 / 10);

		byte[] request = concat(
			("POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Encoding: gzip\r\n" +
				"Content-Length: " + compressed.length + "\r\n" +
				"Connection: close\r\n" +
				"\r\n").getBytes(US_ASCII), compressed);

		String response = exchange(eventloop, listen(), request);

		assertTrue("the decompressed crossing takes the connection-tier 400 path:\n" + response,
			response.startsWith(CONNECTION_TIER_400));
		assertFalse("a regression that left gzip undecoded would answer 200: " + response,
			response.contains("200"));
		assertFalse("an undecoded body would dispatch and answer -32700: " + response,
			response.contains("-32700"));
	}

	// A6 — the media-type variants ---------------------------------------------------------------

	/**
	 * Plan A6: the media-type gate over the wire, every variant from the plan. The allow-list is
	 * {@code application/json} + the two historical JSON-RPC aliases with parameters ignored and
	 * case-insensitive matching (RFC 2045 §2); absent and {@code text/json} are {@code 415} with no
	 * body read, {@code application/json;charset=utf-8} and {@code Application/JSON} are accepted.
	 * The matcher itself is pinned by {@code JsonRpcHttpMediaTypesTest}; this test pins the
	 * servlet's mapping of the raw header string (probe R4).
	 */
	@Test
	public void a6ContentTypeVariantsFollowTheAllowList() throws Exception {
		String valid = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":1,\"b\":2}}";

		for (String rejected : new String[]{"text/json"}) {
			String response = exchange(eventloop, listen(),
				"POST / HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Type: " + rejected + "\r\n" +
					"Content-Length: " + valid.length() + "\r\n" +
					"Connection: close\r\n" +
					"\r\n" + valid);
			String[] headAndBody = splitHeadAndBody(response);
			assertTrue(rejected + " is 415: " + response,
				headAndBody[0].startsWith("HTTP/1.1 415 Unsupported Media Type"));
			assertEquals("a 415 must carry no body: " + response, "", headAndBody[1]);
		}

		// absent Content-Type (the strictness decision of FR-016, pinned again at the wire level)
		String noType = exchange(eventloop, listen(),
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Length: " + valid.length() + "\r\n" +
				"Connection: close\r\n" +
				"\r\n" + valid);
		assertTrue("an absent Content-Type is 415: " + noType,
			noType.startsWith("HTTP/1.1 415 Unsupported Media Type"));

		for (String accepted : new String[]{"application/json;charset=utf-8", "Application/JSON"}) {
			String response = exchange(eventloop, listen(),
				"POST / HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Type: " + accepted + "\r\n" +
					"Content-Length: " + valid.length() + "\r\n" +
					"Connection: close\r\n" +
					"\r\n" + valid);
			assertTrue(accepted + " is accepted: " + response,
				response.startsWith("HTTP/1.1 200 OK"));
			assertTrue("the request was dispatched: " + response, response.contains("\"sum\":3"));
		}
	}

	// A7 — the method gate -----------------------------------------------------------------------

	/**
	 * Plan A7: {@code OPTIONS} is refused identically to the other non-{@code POST} methods pinned
	 * by {@code JsonRpcServletRejectionTest} — {@code 405} with {@code Allow: POST}, no body, no
	 * {@code Content-Type}.
	 */
	@Test
	public void a7AnOptionsRequestIs405WithAllowPost() throws Exception {
		String response = exchange(eventloop, listen(),
			"OPTIONS / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Connection: close\r\n" +
				"\r\n");

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 405 Method Not Allowed"));
		assertTrue("Allow: POST must be present: " + response, headAndBody[0].contains("Allow: POST"));
		assertEquals("a 405 must carry no body: " + response, "", headAndBody[1]);
	}

	// A18 — smuggling-ish head vectors -----------------------------------------------------------

	/**
	 * Plan A18: framing ambiguity is refused by the connection tier before the servlet is ever
	 * entered — a pair of contradicting {@code Content-Length} headers, a non-numeric
	 * {@code Content-Length}, a header line injected into a value position (no colon) and a bare
	 * {@code LF} inside the head are each {@code MalformedHttpException}s that produce the
	 * hardcoded {@code 400} + close, byte-identical to the mid-stream refusal (probe R2). The
	 * connection closes, so there is no second interpretation of the stream to exploit.
	 */
	@Test
	public void a18SmugglingVectorsAreRejectedByTheConnectionTier() throws Exception {
		String[] requests = {
			// contradicting Content-Length pair — RFC 7230 §3.3.2 requires rejection
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 5\r\n" +
				"Content-Length: 7\r\n" +
				"Connection: close\r\n" +
				"\r\n" + "xxxxxxx",
			// non-numeric Content-Length
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: abc\r\n" +
				"Connection: close\r\n" +
				"\r\n",
			// a header value with an injected second line that has no colon
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"X-Evil: a\r\n" +
				"bogus\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: 2\r\n" +
				"Connection: close\r\n" +
				"\r\n" + "{}",
			// a bare LF inside the head
			"POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"X-Evil: a\n" +
				"Content-Length: 2\r\n" +
				"Connection: close\r\n" +
				"\r\n" + "{}",
		};
		for (String request : requests) {
			String response = exchange(eventloop, listen(), request);
			assertTrue("the connection tier rejects the ambiguous framing:\n" + response,
				response.startsWith(CONNECTION_TIER_400));
			assertFalse("never a servlet answer: " + response, response.contains("200"));
			assertFalse("never a 413/415: " + response, response.contains("413") || response.contains("415"));
		}
	}

	// -------------------------------------------------------------------------------------------

	/** Reads exactly one HTTP response (head up to the blank line, then the declared body). */
	private static String readResponse(InputStream input) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		byte[] delimiter = "\r\n\r\n".getBytes(US_ASCII);
		int matched = 0;
		int b;
		while ((b = input.read()) != -1) {
			head.write(b);
			if (b == delimiter[matched]) {
				if (++matched == delimiter.length) break;
			} else {
				matched = b == delimiter[0] ? 1 : 0;
			}
		}
		String headText = head.toString(US_ASCII);
		int contentLength = 0;
		for (String line : headText.split("\r\n")) {
			if (line.toLowerCase().startsWith("content-length:")) {
				contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
			}
		}
		byte[] body = new byte[contentLength];
		int read = 0;
		while (read < contentLength) {
			int n = input.read(body, read, contentLength - read);
			if (n == -1) break;
			read += n;
		}
		return headText + new String(body, 0, read, US_ASCII);
	}

	private static byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
			gz.write(data);
		}
		return baos.toByteArray();
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
