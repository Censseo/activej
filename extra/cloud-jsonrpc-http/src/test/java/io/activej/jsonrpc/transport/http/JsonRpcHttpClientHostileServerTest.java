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

import io.activej.async.exception.AsyncCloseException;
import io.activej.dns.DnsClient;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpClient;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.http.IHttpClient;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.promise.Promise;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.test.EventloopThread.await;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Feature 014 adversarial validation, plan E4 — the client transport against a <b>hostile
 * server</b>: a server that closes mid-response, a server that answers one call twice, a server
 * that answers with the wrong {@code id} format, and a server that answers an id nobody sent.
 * <p>
 * The oracle is the client half of {@code contracts/http-semantics.md} §3 plus feature 012's
 * correlation-table properties (FR-066/068/069): a network failure fails <b>that call's</b>
 * promise only, never {@code onClosed}; a response whose id is in no entry is ignored silently —
 * a duplicate is the same case, since the first emptied the slot; {@code Str("1")} and
 * {@code Num(1)} are different keys, so a string answer to a numeric call never collides and the
 * call stays pending until close. The stack is the real one — {@code JsonRpcClient} over
 * {@code JsonRpcHttpClientTransport} over a real {@code HttpClient} — with a hostile servlet (or a
 * raw socket) as the peer.
 */
public final class JsonRpcHttpClientHostileServerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\":(\\d+)");

	private EventloopThread loop;
	private JsonRpcHttpTestServer server;
	private HostileServlet hostile;
	private IHttpClient httpClient;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-hostile-server-test");
		loop.submit(() -> {
			httpClient = HttpClient.create(loop.eventloop(),
				DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8")));
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			server.closeFuture().get(10, TimeUnit.SECONDS);
		}
		loop.close();
	}

	// E4-1 — a server that closes mid-response ------------------------------------------------

	/**
	 * Plan E4, close mid-response: a raw server writes a {@code 200} head declaring a 100-byte
	 * body, sends ten bytes, and closes. The transport's {@code send} fails with the medium's
	 * exception (here the connection tier's {@code MalformedHttpException} for an incomplete
	 * message — a cause, so not the bare {@code AsyncCloseException}), <b>nothing is delivered</b>,
	 * and {@code onClosed} does not fire — the failure is that call's, not the transport's
	 * (http-semantics §3 rows 3–4, FR-036).
	 */
	@Test
	public void e4_aServerClosingMidResponseFailsThatCallOnly() throws Exception {
		// a raw server on a plain thread: reads the whole request, answers a truncated body, closes
		ServerSocket rawServer = new ServerSocket(0);
		Thread rawThread = new Thread(() -> {
			try (Socket socket = rawServer.accept()) {
				InputStream input = socket.getInputStream();
				ByteArrayOutputStream request = new ByteArrayOutputStream();
				byte[] delimiter = "\r\n\r\n".getBytes(US_ASCII);
				int matched = 0;
				int b;
				while ((b = input.read()) != -1) {
					request.write(b);
					if (b == delimiter[matched]) {
						if (++matched == delimiter.length) break;
					} else {
						matched = b == delimiter[0] ? 1 : 0;
					}
				}
				// drain the declared request body so the exchange is well-formed on the client side
				String head = request.toString(US_ASCII);
				for (String line : head.split("\r\n")) {
					if (line.toLowerCase().startsWith("content-length:")) {
						int length = Integer.parseInt(line.substring("content-length:".length()).trim());
						for (int i = 0; i < length; i++) input.read();
					}
				}
				OutputStream output = socket.getOutputStream();
				output.write(("HTTP/1.1 200 OK\r\n" +
					"Content-Type: application/json\r\n" +
					"Content-Length: 100\r\n" +
					"\r\n" +
					"{\"jsonrpc").getBytes(US_ASCII));
				output.flush();
			} catch (IOException ignored) {
				// the client may close first on its failure path — the assertion is client-side
			}
		});
		rawThread.start();

		JsonRpcHttpClientTransport transport = loop.submit(() -> JsonRpcHttpClientTransport.create(
			loop.eventloop(), httpClient, "http://127.0.0.1:" + rawServer.getLocalPort() + "/"));
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		loop.submit(() -> transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				delivered.add(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				closed.add(e);
			}
		}));

		byte[] document = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}".getBytes(UTF_8);
		Exception e = awaitException(loop, loop.submit(() -> transport.send(document)).toCompletableFuture(),
			"send against a server that closes mid-response");

		assertTrue("the call fails with the medium's exception, got " + e, e != null);
		assertTrue("nothing is delivered", delivered.isEmpty());
		assertTrue("onClosed does not fire for a call failure (FR-036)", closed.isEmpty());

		rawServer.close();
		rawThread.join(10_000);
	}

	// E4-2 — a duplicated response -------------------------------------------------------------

	/**
	 * Plan E4, duplicated response: the hostile servlet answers one request with a <b>batch of two
	 * responses for the same id</b>. The first empties the correlation slot, the second finds no
	 * entry and is ignored silently — no error, no second completion, {@code inFlightCount()} back
	 * to zero (FR-068: one removal per call).
	 */
	@Test
	public void e4_aDuplicatedResponseIsSilentlyIgnoredAfterTheFirst() throws Exception {
		HostileServlet servlet = hostile(HostileServlet.Mode.DUPLICATE);
		JsonRpcClient client = clientWith(servlet);
		TestApi api = loop.submit(() -> client.proxy(TestApi.class));

		CompletableFuture<TestApi.AddResult> call = loop.submit(() -> api.add(2, 3)).toCompletableFuture();
		// pin down that this single call is the served FIRST request before asserting on its answer
		assertTrue("the first request must reach the servlet", servlet.awaitFirstRequest(10, TimeUnit.SECONDS));
		TestApi.AddResult result = await(call, "add against a duplicating server");

		assertEquals("the FIRST answer wins; the duplicate is ignored", 1L, (long) result.sum());
		assertEquals("one call in, one removal — the slot is empty again", 0L, (long) loop.submit(client::inFlightCount));
	}

	// E4-3 — a response with the wrong id format ------------------------------------------------

	/**
	 * Plan E4, wrong {@code id} format: the client sent {@code Num(1)}, the hostile servlet answers
	 * {@code Str("1")}. The correlation table keys on the {@code JsonRpcId} value — a string is a
	 * <b>distinct entry</b>, never a collision with the numeric one, so the first call stays
	 * pending (still in flight when a second, correctly-answered call has completed) and fails only
	 * when the client closes, with the close's exception.
	 */
	@Test
	public void e4_aStringIdAnswerIsADistinctEntryAndNeverCollides() throws Exception {
		HostileServlet servlet = hostile(HostileServlet.Mode.WRONG_FORMAT);
		JsonRpcClient client = clientWith(servlet);
		TestApi api = loop.submit(() -> client.proxy(TestApi.class));

		// call 1 (id 1) — the servlet answers Str("1") → no entry matches → stays pending
		CompletableFuture<TestApi.AddResult> call1 = loop.submit(() -> api.add(2, 3)).toCompletableFuture();
		// pin down that call 1 is the served FIRST request (two calls can race over parallel connections)
		assertTrue("the first request must reach the servlet", servlet.awaitFirstRequest(10, TimeUnit.SECONDS));
		// call 2 (id 2) — the servlet answers correctly (ECHO: the id doubles as the result value)
		TestApi.AddResult result2 = await(loop.submit(() -> api.add(5, 6)).toCompletableFuture(), "second call");
		assertEquals("the correctly-answered call completes with the echoed id", 2L, (long) result2.sum());
		assertEquals("the string-id answer never collided with the numeric entry — call 1 is still pending",
			1L, (long) loop.submit(client::inFlightCount));

		// closing the client fails the still-pending call with the close's exception
		loop.submit(() -> client.closeEx(new AsyncCloseException("hostile server closed")));
		try {
			await(call1, "pending call after close");
			fail("the pending call must fail on close");
		} catch (IllegalStateException e) {
			assertTrue("the pending call fails with the close's AsyncCloseException, got " + e.getCause(),
				e.getCause() instanceof AsyncCloseException);
		}
		assertEquals("the correlation table is empty after close", 0L, (long) loop.submit(client::inFlightCount));
	}

	// E4-4 — a response for an unknown id ------------------------------------------------------

	/**
	 * Plan E4, unknown id: the servlet answers an id no call ever sent. The response is ignored
	 * silently — no exception, no failure handler, no entry created (feature 012's documented
	 * correlation rule) — the call stays pending and fails only on close.
	 */
	@Test
	public void e4_aResponseForAnUnknownIdIsIgnoredAndTheCallFailsOnClose() throws Exception {
		HostileServlet servlet = hostile(HostileServlet.Mode.UNKNOWN_ID);
		JsonRpcClient client = clientWith(servlet);
		TestApi api = loop.submit(() -> client.proxy(TestApi.class));

		CompletableFuture<TestApi.AddResult> call1 = loop.submit(() -> api.add(2, 3)).toCompletableFuture();
		// pin down that call 1 is the served FIRST request (two calls can race over parallel connections)
		assertTrue("the first request must reach the servlet", servlet.awaitFirstRequest(10, TimeUnit.SECONDS));
		// a second, correctly-answered call proves the first response was processed and ignored
		// (ECHO: the id doubles as the result value)
		TestApi.AddResult result2 = await(loop.submit(() -> api.add(5, 6)).toCompletableFuture(), "second call");
		assertEquals("the correctly-answered call completes with the echoed id", 2L, (long) result2.sum());
		assertEquals("the unknown-id response created no entry and completed nothing",
			1L, (long) loop.submit(client::inFlightCount));

		loop.submit(() -> client.closeEx(new AsyncCloseException("hostile server closed")));
		try {
			await(call1, "pending call after close");
			fail("the pending call must fail on close");
		} catch (IllegalStateException e) {
			assertTrue("the pending call fails with the close's AsyncCloseException, got " + e.getCause(),
				e.getCause() instanceof AsyncCloseException);
		}
	}

	// -----------------------------------------------------------------------------------------

	/** Builds a {@link JsonRpcClient} over the real transport pointed at the {@link HostileServlet}. */
	private JsonRpcClient clientWith(HostileServlet servlet) {
		return loop.submit(() -> {
			server = JsonRpcHttpTestServer.builder(loop.eventloop())
				.withServlet(servlet)
				.build();
			server.listen();
			JsonRpcHttpClientTransport transport = JsonRpcHttpClientTransport.create(loop.eventloop(),
				httpClient, "http://127.0.0.1:" + server.port() + "/");
			return JsonRpcClient.builder(loop.eventloop(), transport).build();
		});
	}

	private static HostileServlet hostile(HostileServlet.Mode mode) {
		return new HostileServlet(mode);
	}

	/** Awaits {@code future} and asserts it failed; returns the cause. */
	private static Exception awaitException(EventloopThread loop, CompletableFuture<?> future, String what) {
		try {
			loop.await(future, what);
			fail("expected '" + what + "' to fail, it completed");
			return null;
		} catch (IllegalStateException e) {
			return (Exception) e.getCause();
		}
	}

	/**
	 * The hostile peer: parses the numeric id out of each request and answers according to the
	 * configured mode — echoing the id back (control), answering one id twice as a batch, answering
	 * a <b>string</b> spelling of the numeric id, or answering an id nobody sent. The first request
	 * to reach the servlet takes the configured mode; subsequent ones echo (so a test's second call
	 * can complete). Because the transport may run two calls over separate connections in parallel,
	 * the tests use {@link #firstRequestLatch} to pin down WHICH call the servlet serves first —
	 * without it, the "first request" is an arrival race and the second call hangs.
	 */
	private static final class HostileServlet implements AsyncServlet {
		enum Mode {ECHO, DUPLICATE, WRONG_FORMAT, UNKNOWN_ID}

		private final Mode firstMode;
		private final AtomicInteger requests = new AtomicInteger();
		private final CountDownLatch firstRequestLatch = new CountDownLatch(1);

		private HostileServlet(Mode firstMode) {
			this.firstMode = firstMode;
		}

		/** Awaits the moment the servlet has started serving its very first request. */
		private boolean awaitFirstRequest(long timeout, TimeUnit unit) throws InterruptedException {
			return firstRequestLatch.await(timeout, unit);
		}

		@Override
		public Promise<HttpResponse> serve(HttpRequest request) {
			return request.loadBody()
				.map($ -> {
					byte[] document = request.takeBody().asArray();
					Matcher matcher = ID_PATTERN.matcher(new String(document, UTF_8));
					String id = matcher.find() ? matcher.group(1) : "0";
					boolean first = requests.getAndIncrement() == 0;
					Mode mode = first ? firstMode : Mode.ECHO;
					if (first) {
						firstRequestLatch.countDown();
					}
					String body = switch (mode) {
						case ECHO -> "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sum\":" + id + "}}";
						case DUPLICATE -> "[" +
							"{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sum\":1}}," +
							"{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"sum\":2}}" +
							"]";
						case WRONG_FORMAT -> "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"sum\":1}}";
						case UNKNOWN_ID -> "{\"jsonrpc\":\"2.0\",\"id\":99999,\"result\":{\"sum\":1}}";
					};
					return HttpResponse.ok200()
						.withHeader(CONTENT_TYPE, "application/json")
						.withBody(body.getBytes(UTF_8))
						.build();
				});
		}
	}
}
