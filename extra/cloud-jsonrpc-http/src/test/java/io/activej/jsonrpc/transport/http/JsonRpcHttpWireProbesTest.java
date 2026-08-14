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
import io.activej.http.HttpResponse;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 2 probes — the HTTP semantics the JSON-RPC-over-HTTP semantics table rests on, settled
 * against a <b>real socket</b> rather than read out of {@code core-http} (plan §Phase 2, ADR-029).
 * <p>
 * Each probe answers one of research.md §5's unknowns R1–R4 and is a permanent test: the answer it
 * pins is the contract the servlet (T017/T021) and the media-type matcher (T019) are implemented
 * against. The raw-socket pattern — bind {@code :0}, run the eventloop on its own thread, block on a
 * {@code java.net.Socket} — is {@code core-http}'s own {@code HttpServerTest} idiom; the difference
 * is that this module's servers are <b>asked</b> where they landed ({@code getBoundAddresses()})
 * instead of being handed a guessed port (FR-050a, ADR-028).
 */
public final class JsonRpcHttpWireProbesTest {
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

	// R1 --------------------------------------------------------------------------------------------

	/**
	 * R1: what {@code core-http} writes alongside a {@code 204} status line for
	 * {@code HttpResponse.ofCode(204)} with no headers of its own.
	 * <p>
	 * Observed answer (2026-08-14): {@code HTTP/1.1 204 No Content}, a
	 * {@code Content-Length: 0} added automatically ({@code HttpResponse.isContentLengthExpected()}
	 * is always {@code true}, so {@code HttpServerConnection.renderHttpResponse} supplies the zero
	 * header when no body exists), <b>no</b> {@code Content-Type}, and an empty body. The only other
	 * header is the connection header derived from the request.
	 */
	@Test
	public void r1_whatCoreHttpWritesForA204() throws Exception {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop).build();
		server.listen();

		String notification =
			"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"hi\"}}";
		String response = exchange(server, post(notification, "application/json"));

		String[] headAndBody = splitHeadAndBody(response);
		assertEquals("a 204 must carry no body: " + response, "", headAndBody[1]);

		Set<String> headerLines = new LinkedHashSet<>(List.of(headAndBody[0].split("\r\n")));
		assertTrue("status line: " + response, headerLines.contains("HTTP/1.1 204 No Content"));
		assertTrue("core-http adds Content-Length: 0 to a bodyless 204: " + response,
			headerLines.contains("Content-Length: 0"));
		assertTrue("no Content-Type may be written: " + response,
			headerLines.stream().noneMatch(line -> line.startsWith("Content-Type")));
	}

	// R2 --------------------------------------------------------------------------------------------

	/**
	 * R2: the status a {@code MalformedHttpException} from {@code HttpMessage.loadBody(int)} produces
	 * by default, observed through a real server.
	 * <p>
	 * Two observed facts (2026-08-14). First, the bound is enforced <b>before each chunk is
	 * appended</b> — a body that fits one 16 kB read slips past a 1 kB bound entirely, so the probe
	 * body is 33 kB (at least three reads) to force the check to fire. Second, once it fires, the
	 * answer is <b>400 Bad Request</b> — never 413: {@code loadBody}'s violation closes the body
	 * stream with the {@code MalformedHttpException}, and the connection's
	 * {@code onMalformedHttpException} answers with the hardcoded
	 * {@code HTTP/1.1 400 Bad Request / Connection: close / Content-Length: 0} before the servlet's
	 * own failure callback runs. It is distinguishable from 405/415 by status alone, but <b>not</b>
	 * suppressible by a servlet catch — a servlet that must answer 413 for the mid-stream case has
	 * to refuse up front (the T028/T030 {@code Content-Length} check) because the intercept fires
	 * first.
	 */
	@Test
	public void r2_statusOfMalformedHttpExceptionFromLoadBody() throws Exception {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withMaxBodySize(MemSize.kilobytes(1))
			.build();
		server.listen();

		String body = "x".repeat(33_000);
		String response = exchange(server, post(body, "application/json"));

		String hardcoded400 = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
		assertTrue(
			"the connection tier's onMalformedHttpException answers first with its hardcoded 400:\n" + response,
			response.startsWith(hardcoded400));
		assertFalse("never a 413: " + response, response.contains("413"));
	}

	// R3 --------------------------------------------------------------------------------------------

	/**
	 * R3: which tier answers a {@code Content-Length} above {@code HttpServer}'s own {@code 100mb}
	 * tier — {@code AbstractHttpConnection.readBody()} or the servlet.
	 * <p>
	 * Observed answer (2026-08-14): the <b>connection tier</b>. {@code readBody()} refuses
	 * {@code contentLength > maxBodySize} before {@code onHeadersReceived} is ever reached, so the
	 * servlet is never entered (the {@code onRequest} counter proves it) and no body byte is read.
	 * The wire form is the same hardcoded 400 + close as R2's, byte-identical.
	 */
	@Test
	public void r3_connectionTierAnswersOversizeContentLength() throws Exception {
		AtomicInteger served = new AtomicInteger();
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withOnRequest($ -> served.incrementAndGet())
			.build();
		server.listen();

		// 200 MB > HttpServer's 100 MB tier; no body byte is sent — the refusal must not wait for one
		String request = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Length: 200000000\r\n" +
			"Connection: close\r\n" +
			"\r\n";
		String response = exchange(server, request);

		assertEquals("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n", response);
		assertEquals("the connection tier refuses before serve() is entered", 0, served.get());
	}

	// R4 --------------------------------------------------------------------------------------------

	/**
	 * R4: whether {@code HttpRequest} exposes a parsed {@code ContentType} or only the raw header
	 * string — and therefore what form T018's media-type allow-list matches against.
	 * <p>
	 * Observed answer (2026-08-14): <b>only the raw header string</b>. Neither {@code HttpRequest}
	 * nor {@code HttpMessage} declares a {@code getContentType()} accessor; the servlet sees
	 * {@code request.getHeader(CONTENT_TYPE)} as the exact wire string, parameters included
	 * ({@code application/json; charset=UTF-8} arrives verbatim). The allow-list must therefore
	 * match the raw string and strip/ignore the {@code charset} parameter itself — which is
	 * precisely FR-016's "parameters MUST be ignored when matching".
	 */
	@Test
	public void r4_contentTypeIsOnlyTheRawHeaderString() throws Exception {
		boolean parsedAccessor = Arrays.stream(HttpRequest.class.getMethods())
			.anyMatch(method -> method.getName().equals("getContentType"));
		assertFalse(
			"HttpRequest exposes only the raw header string; a parsed ContentType accessor would change the form FR-016 matches against",
			parsedAccessor);

		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(request -> HttpResponse.ok200()
				.withBody(request.getHeader(CONTENT_TYPE))
				.toPromise())
			.build();
		server.listen();

		String response = exchange(server, post("{}", "application/json; charset=UTF-8"));

		String[] headAndBody = splitHeadAndBody(response);
		assertEquals("the servlet sees the raw header string, parameters intact: " + response,
			"application/json; charset=UTF-8", headAndBody[1]);
	}

	// sanity ----------------------------------------------------------------------------------------

	/**
	 * Not a probe: the harness's own sanity check. One real exchange through the dispatcher proves
	 * the harness, {@link io.activej.jsonrpc.transport.http.fixtures.TestApi} and the
	 * record-codec derivation all work together over a socket — R1 alone would only exercise the
	 * dispatch-to-nothing path.
	 */
	@Test
	public void harness_reachesTheServiceOverARealSocket() throws Exception {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop).build();
		server.listen();

		String request = post(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}",
			"application/json");
		String response = exchange(server, request);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue(headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue(headAndBody[0].contains("Content-Type: application/json"));
		assertTrue("the record result decodes through the derived codec: " + response,
			headAndBody[1].contains("\"sum\":5"));
	}

	// -----------------------------------------------------------------------------------------------

	/**
	 * Runs one raw exchange against {@code server}: starts the {@code EventloopRule} loop on its own
	 * thread, blocks on a {@code java.net.Socket} until the server closes the connection (every
	 * probe request carries {@code Connection: close}), then closes the server on its loop and joins
	 * the thread. The {@code finally} runs on every path, assertion failures included, and both of
	 * its waits are bounded — a probe failure that kills the eventloop thread must fail the test
	 * quickly, not hang the suite.
	 */
	private String exchange(JsonRpcHttpTestServer server, String request) throws Exception {
		Thread eventloopThread = new Thread(eventloop);
		eventloopThread.start();
		try {
			try (Socket socket = new Socket()) {
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(5_000);
				socket.connect(new InetSocketAddress("localhost", server.port()));
				OutputStream output = socket.getOutputStream();
				output.write(request.getBytes(US_ASCII));
				output.flush();
				InputStream input = socket.getInputStream();
				return new String(input.readAllBytes(), US_ASCII);
			}
		} finally {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} finally {
				eventloopThread.join(10_000);
				if (eventloopThread.isAlive()) {
					throw new IllegalStateException("the eventloop thread did not stop: a probe left it running");
				}
			}
		}
	}

	/** A minimal HTTP/1.1 POST with an explicit {@code Content-Length} and {@code Connection: close}. */
	private static String post(String body, String contentType) {
		return "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: " + contentType + "\r\n" +
			"Content-Length: " + body.length() + "\r\n" +
			"Connection: close\r\n" +
			"\r\n" + body;
	}

	/**
	 * Splits a raw response into {@code {head, body}} at the first blank line. {@code String.split}
	 * is not used: it produces a trailing empty element for a bodyless response, which is exactly
	 * the shape R1 asserts.
	 */
	private static String[] splitHeadAndBody(String response) {
		int bodyStart = response.indexOf("\r\n\r\n");
		assertTrue("no blank line separating head from body: " + response, bodyStart >= 0);
		return new String[]{response.substring(0, bodyStart), response.substring(bodyStart + 4)};
	}
}
