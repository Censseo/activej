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

import io.activej.dns.DnsClient;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpClient;
import io.activej.http.HttpError;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.http.MalformedHttpException;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.test.EventloopThread.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The transport's obligation cases (T036) and failure cases (T038), pinned over a real socket:
 * the real {@link JsonRpcServlet} over {@link TestApi} on a real {@link io.activej.http.HttpServer}
 * bound to {@code :0}, a real {@link HttpClient}, and a real
 * {@link JsonRpcHttpClientTransport}. A thin shaped-servlet wrapper adds test-only paths
 * ({@code /empty}, {@code /fail}, {@code /big}, {@code /weird}) — every other path delegates to the
 * real servlet, which is the only place the JSON-RPC wire handling lives (plan D13, risk a).
 * <p>
 * <b>Threading (plan D4).</b> The JUnit thread never touches a reactive component directly. The
 * persistent server lives on a dedicated {@link EventloopThread} and every reactor-touching
 * operation — including every transport call and every read of a loop-confined list — goes through
 * {@code loop.submit(...)}; the JUnit thread blocks on
 * {@code EventloopThread.await(future, what)} instead of {@code io.activej.promise.TestUtils.await},
 * which would run the loop to quiescence and hang on the listening socket.
 * <p>
 * <b>Cases 1–4 (T036).</b> FR-033: a {@code 2xx} with a body delivers exactly one {@code onDocument};
 * a {@code 204} and a {@code 2xx} with an empty body deliver nothing (obligation 3 — the empty body
 * is a <i>length</i> zero, never a null: probe F3); FR-035: the document is delivered before or as
 * {@code send}'s promise completes, never after. <b>Cases 5–9 (T038).</b> FR-034/FR-036: a {@code 500}
 * and a network failure fail <i>that</i> send's promise only and never reach {@code onClosed};
 * FR-037: the response body is bounded by the transport's own {@code maxBodySize} and an oversize
 * response fails that call alone; FR-043: the response {@code Content-Type} is never inspected.
 */
public final class JsonRpcHttpClientTransportTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Above the transport's default bound (1 MB), below the client's 100 MB connection tier. */
	private static final byte[] BIG_BODY = new byte[2 * 1024 * 1024];

	private static final byte[] ADD_DOCUMENT = utf8(
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}");
	private static final byte[] NOTIFY_DOCUMENT = utf8(
		"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":[\"hi\"]}");

	private EventloopThread loop;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcServlet servlet;
	private HttpClient httpClient;
	private JsonRpcHttpTestServer server;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-client-transport-test");
		loop.submit(() -> {
			dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.build();
			servlet = JsonRpcServlet.create(loop.eventloop(), dispatcher);
			httpClient = HttpClient.create(loop.eventloop(),
				DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8")));
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				throw new AssertionError("the test server did not close cleanly", e);
			}
		}
		loop.close();
	}

	// ---------------------------------------------------------------------------------------------------
	// T036 — the obligation cases.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void aRequestWithABodyDeliversExactlyOneOnDocument() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		await(loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");

		byte[] expected = await(loop.submit(() -> dispatcher.dispatch(ADD_DOCUMENT)).toCompletableFuture(), "dispatch");
		assertEquals("exactly one document must be delivered", 1, delivered.size());
		assertTrue("the delivered bytes are the dispatcher's own bytes, unaltered (FR-013, asserted " +
					   "through the client half)",
			Arrays.equals(expected, delivered.get(0)));
		assertTrue("onClosed must not fire on the happy path", closed.isEmpty());
	}

	@Test
	public void a204DeliversNothing() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		await(loop.submit(() -> transport.send(NOTIFY_DOCUMENT)).toCompletableFuture(), "send");

		// a 204's body arrives as an EMPTY ByteBuf (probe F3), so "nothing" is a delivery count, not a null
		assertEquals("a 204 answers a notification with no document at all (obligation 3)", 0, delivered.size());
		assertTrue(closed.isEmpty());
	}

	@Test
	public void a2xxWithAnEmptyBodyDeliversNothing() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		// a second transport pointed at the /empty path of the same server
		JsonRpcHttpClientTransport transport = transport("/empty");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		await(loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");

		assertEquals("a 200 with Content-Length: 0 is a zero-length body, which must deliver nothing " +
						 "(obligation 3, FR-033)",
			0, delivered.size());
		assertTrue(closed.isEmpty());
	}

	@Test
	public void theDocumentIsDeliveredBeforeOrAsTheSendPromiseCompletesNeverAfter() throws Exception {
		listen(shaped());
		List<String> events = new ArrayList<>();
		List<byte[]> delivered = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		loop.submit(() -> transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				events.add("onDocument");
				delivered.add(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				events.add("onClosed");
			}
		}));

		Promise<Void> sendPromise = loop.submit(() -> {
			Promise<Void> promise = transport.send(ADD_DOCUMENT);
			promise.whenComplete(($, e) -> events.add("sendComplete"));
			return promise;
		});
		await(sendPromise.toCompletableFuture(), "send");

		// deliver-first wiring (plan D2): the listener sees the document before or as the send promise
		// completes, never after — the event order is the proof, and the populated list the companion
		assertEquals("onDocument must precede sendComplete", "onDocument", events.get(0));
		assertEquals("[onDocument, sendComplete]", Arrays.asList("onDocument", "sendComplete").toString(),
			events.toString());
		assertEquals("the document is already delivered by the time send's promise completes", 1,
			delivered.size());
	}

	// ---------------------------------------------------------------------------------------------------
	// T038 — the failure cases.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void a500FailsThatSendsPromiseOnly() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/fail");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		Exception e = awaitException(loop, loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");

		assertTrue("a 500 must fail send's promise with an HttpError, got " + e, e instanceof HttpError);
		assertEquals(500, ((HttpError) e).getCode());
		assertEquals("a 500 must deliver nothing", 0, delivered.size());
		assertTrue("a 500 must not reach onClosed — the next POST may well succeed (FR-036)", closed.isEmpty());
	}

	@Test
	public void aNetworkFailureFailsThatSendsPromiseOnly() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		// close the server on the loop — the next send's connect is refused
		server.closeFuture().get(10, TimeUnit.SECONDS);

		Exception e = awaitException(loop, loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");
		assertNotNull("a connection-refused send must fail its promise", e);
		assertEquals("a network failure must deliver nothing", 0, delivered.size());
		assertTrue("a network failure must not reach onClosed (FR-036)", closed.isEmpty());
	}

	@Test
	public void aSecondInFlightCallIsUnaffectedByA500() throws Exception {
		// the stateful wrapper (plan D10): the FIRST request to arrive gets a 500, everything after
		// delegates to the real servlet. The wrapper counts per server, and this server is per-test.
		AtomicInteger served = new AtomicInteger();
		listen(request -> {
			if (served.getAndIncrement() == 0) return HttpResponse.ofCode(500).toPromise();
			return shaped().serve(request);
		});
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		Promise<Void> first = loop.submit(() -> transport.send(ADD_DOCUMENT));
		Promise<Void> second = loop.submit(() -> transport.send(ADD_DOCUMENT));

		Exception failure1 = outcome(loop, first.toCompletableFuture(), "first concurrent send");
		Exception failure2 = outcome(loop, second.toCompletableFuture(), "second concurrent send");
		// exactly one of the two concurrent sends fails with the 500; the identity is arrival-order-
		// dependent, so the assertions are order-agnostic (plan D10)
		assertEquals("exactly one of the two concurrent sends must fail",
			1, (failure1 != null ? 1 : 0) + (failure2 != null ? 1 : 0));
		Exception failure = failure1 != null ? failure1 : failure2;
		assertTrue("the failed call fails with HttpError 500, got " + failure, failure instanceof HttpError);
		assertEquals(500, ((HttpError) failure).getCode());
		assertEquals("the unaffected call delivers its document", 1, delivered.size());
		assertTrue("neither failure may reach onClosed (FR-036)", closed.isEmpty());
	}

	@Test
	public void anOversizeResponseFailsThatCallAlone() throws Exception {
		listen(shaped());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/big");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		Exception e = awaitException(loop, loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");

		// the 2 MB body crosses the transport's 1 MB bound during accumulation, so loadBody's own
		// MalformedHttpException is the failure (plan F4)
		assertTrue("an oversize response must fail that call with MalformedHttpException, got " + e,
			e instanceof MalformedHttpException);
		assertEquals("an oversize response must deliver nothing", 0, delivered.size());

		// that call alone was affected: a fresh transport on the SAME client still completes
		List<byte[]> after = new ArrayList<>();
		List<Exception> closedAfter = new ArrayList<>();
		JsonRpcHttpClientTransport good = transport("/");
		loop.submit(() -> good.setListener(listener(after, closedAfter)));
		await(loop.submit(() -> good.send(ADD_DOCUMENT)).toCompletableFuture(), "send on the same client");
		assertEquals(1, after.size());
		assertTrue(closedAfter.isEmpty());
	}

	@Test
	public void aNonJsonContentTypeOnTheResponseIsNotInspected() throws Exception {
		AtomicBoolean weirdServed = new AtomicBoolean();
		listen(request -> {
			if (request.getPath().equals("/weird")) {
				weirdServed.set(true);
				// the real servlet produces the document; the wrapper re-headers it text/plain —
				// the one shape the real servlet cannot produce, and the only reason for the branch
				return servlet.serve(request).map(response -> {
					byte[] document = response.takeBody().asArray();
					return HttpResponse.ok200()
						.withHeader(CONTENT_TYPE, "text/plain")
						.withBody(document)
						.build();
				});
			}
			return shaped().serve(request);
		});
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/weird");
		loop.submit(() -> transport.setListener(listener(delivered, closed)));

		await(loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");

		assertTrue("the /weird branch must have served", weirdServed.get());
		assertEquals(1, delivered.size());
		byte[] expected = await(loop.submit(() -> dispatcher.dispatch(ADD_DOCUMENT)).toCompletableFuture(), "dispatch");
		assertTrue("a text/plain body carrying a valid document is delivered unchanged (FR-043)",
			Arrays.equals(expected, delivered.get(0)));
		assertTrue(closed.isEmpty());
	}

	@Test
	public void aThrowingListenerStillCompletesTheSendPromise() throws Exception {
		listen(shaped());
		List<Exception> closed = new ArrayList<>();
		JsonRpcHttpClientTransport transport = transport("/");
		RuntimeException listenerBug = new RuntimeException("listener bug");
		loop.submit(() -> transport.setListener(new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				throw listenerBug;
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				closed.add(e);
			}
		}));

		// the document WAS delivered — the listener's own bug is not an exchange failure: the send
		// completes (never hangs, never fails), and the exception goes to the loop's fatal-error
		// handler, which on a plain EventloopThread logs it. A regression here hangs this await or
		// fails it with the listener's exception.
		await(loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(), "send");
		assertTrue("a listener bug must not reach onClosed (FR-036)", closed.isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------
	// The shared pieces: shaped servlet, listener, transport, outcome helpers.
	// ---------------------------------------------------------------------------------------------------

	/**
	 * The shaped servlet: test-only paths ({@code /empty}, {@code /fail}, {@code /big}) branch;
	 * everything else delegates to the real {@link JsonRpcServlet} — the wrapper never reimplements
	 * the wire handling (plan T036 risk a).
	 */
	private AsyncServlet shaped() {
		return request -> {
			String path = request.getPath();
			if (path.equals("/empty")) return HttpResponse.ok200().toPromise();
			if (path.equals("/fail")) return HttpResponse.ofCode(500).toPromise();
			if (path.equals("/big")) return HttpResponse.ok200().withBody(BIG_BODY).toPromise();
			return servlet.serve(request);
		};
	}

	/** Builds and listens a server over {@code shaped} on the shared loop; the server is per-test (D13). */
	private void listen(AsyncServlet shaped) {
		// listen() is reactor-guarded (AbstractReactiveServer) and must run on the loop thread
		server = loop.submit(() -> {
			JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(loop.eventloop())
				.withServlet(shaped)
				.build();
			server.listen();
			return server;
		});
	}

	/** A transport pointed at {@code path} of the running server, over the shared real client. */
	private JsonRpcHttpClientTransport transport(String path) {
		return JsonRpcHttpClientTransport.create(loop.eventloop(), httpClient,
			"http://127.0.0.1:" + server.port() + path);
	}

	private static JsonRpcTransport.Listener listener(List<byte[]> delivered, List<Exception> closed) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				delivered.add(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				closed.add(e);
			}
		};
	}

	/** Awaits {@code future}; returns {@code null} when it completed, the cause when it failed. */
	private static Exception outcome(EventloopThread loop, CompletableFuture<?> future, String what) {
		try {
			loop.await(future, what);
			return null;
		} catch (IllegalStateException e) {
			return (Exception) e.getCause();
		}
	}

	/** Awaits {@code future} and asserts it failed; returns the cause. */
	private static Exception awaitException(EventloopThread loop, CompletableFuture<?> future, String what) {
		Exception e = outcome(loop, future, what);
		if (e == null) fail("expected '" + what + "' to fail, it completed");
		return e;
	}

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}
}
