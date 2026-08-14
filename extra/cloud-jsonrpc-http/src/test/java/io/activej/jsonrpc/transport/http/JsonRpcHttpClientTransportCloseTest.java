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
import io.activej.http.HttpError;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.http.IHttpClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.activej.test.EventloopThread.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The close semantics of {@link JsonRpcHttpClientTransport} (T040): FR-039 — {@code close()} and
 * {@code closeEx(Exception)} are idempotent and fire {@code onClosed} <b>exactly once</b>, and a
 * post-close {@code send} fails immediately with <b>no request issued</b>; FR-040 — exchanges in
 * flight at close deliver nothing and fail their own promises, and the injected {@code IHttpClient}
 * is <b>not</b> closed (structurally impossible — {@code IHttpClient} has no close method — and
 * asserted behaviorally by a second transport on the same client).
 * <p>
 * Two test-side instruments:
 * <ul>
 *     <li>a <b>counting {@link IHttpClient} wrapper</b> around the real {@link HttpClient} — the
 *     proof of "no request issued" is a zero request count after the post-close send. It is not a
 *     {@code StubHttpClient}: the socket is real (FR-056);</li>
 *     <li>a <b>held-response servlet</b> — {@code serve} returns a {@link SettablePromise} the test
 *     completes later, so the in-flight case is deterministic (plan T040, risk a).</li>
 * </ul>
 * Threading is the D4 regime: the persistent server lives on a dedicated {@link EventloopThread},
 * and every transport call goes through {@code loop.submit(...)} — the transport's public methods
 * are reactor-guarded, so a direct JUnit-thread call would throw {@code IllegalStateException}.
 */
public final class JsonRpcHttpClientTransportCloseTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final byte[] ADD_DOCUMENT = utf8(
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}");

	private EventloopThread loop;
	private JsonRpcHttpTestServer server;
	private CountingHttpClient countingClient;
	private JsonRpcServlet servlet;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-client-transport-close-test");
		loop.submit(() -> {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.build();
			servlet = JsonRpcServlet.create(loop.eventloop(), dispatcher);
			HttpClient client = HttpClient.create(loop.eventloop(),
				DnsClient.create(loop.eventloop(), HttpUtils.inetAddress("8.8.8.8")));
			countingClient = new CountingHttpClient(client);
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

	@Test
	public void closeThreeTimesFiresOnClosedOnce() throws Exception {
		JsonRpcHttpClientTransport transport = transport(serving());
		List<Exception> closed = listener(transport);

		loop.submit(transport::close);
		loop.submit(transport::close);
		loop.submit(transport::close);

		assertEquals("close() three times fires onClosed exactly once (FR-039, obligation 6)", 1,
			closed.size());
	}

	@Test
	public void closeExAfterCloseStillFiresOnce() throws Exception {
		JsonRpcHttpClientTransport transport = transport(serving());
		List<Exception> closed = listener(transport);

		loop.submit(transport::close);
		loop.submit(() -> transport.closeEx(HttpError.ofCode(500)));

		assertEquals("closeEx(e) after close() still fires onClosed exactly once, and the second call's " +
						 "cause is not reported (WI-9)",
			1, closed.size());
	}

	@Test
	public void sendAfterCloseFailsImmediatelyWithoutIssuingARequest() throws Exception {
		JsonRpcHttpClientTransport transport = transport(serving());
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = listener(transport, delivered);

		loop.submit(transport::close);
		Exception e = awaitException(loop, loop.submit(() -> transport.send(ADD_DOCUMENT)).toCompletableFuture(),
			"post-close send");

		assertTrue("a post-close send fails with the close's AsyncCloseException, got " + e,
			e instanceof AsyncCloseException);
		assertEquals("no request may be issued after close (FR-039)", 0, (int) loop.submit(() -> countingClient.requests));
		assertTrue("a post-close send delivers nothing", delivered.isEmpty());
		assertEquals(1, closed.size());
	}

	@Test
	public void exchangesInFlightAtCloseDeliverNothingAndFailTheirOwnPromises() throws Exception {
		// a servlet that holds the response until the test releases it
		SettablePromise<HttpResponse> held = new SettablePromise<>();
		JsonRpcHttpClientTransport transport = transport(request -> held);
		List<byte[]> delivered = new ArrayList<>();
		List<Exception> closed = listener(transport, delivered);

		Promise<Void> inFlight = loop.submit(() -> transport.send(ADD_DOCUMENT));

		loop.submit(transport::close);
		Exception e = awaitException(loop, inFlight.toCompletableFuture(), "in-flight send");
		assertTrue("the in-flight send fails with the close's AsyncCloseException, got " + e,
			e instanceof AsyncCloseException);
		assertEquals("onClosed fired exactly once", 1, closed.size());
		assertTrue("nothing is delivered before the held response is released", delivered.isEmpty());

		// release the held response: the transport's callback sees closed, drains the body and
		// delivers nothing — and must not touch its already-failed SettablePromise (plan D9). The
		// server's close waits for its connections, so closeFuture().get() returns only after the
		// drained exchange has completed — deterministic, no sleeps.
		loop.submit(() -> held.set(HttpResponse.ok200().withBody(new byte[]{42}).build()));
		server.closeFuture().get(10, TimeUnit.SECONDS);

		assertTrue("an exchange in flight at close delivers nothing even after the response arrives " +
					   "(FR-040); ByteBufRule pins the drain's recycling",
			delivered.isEmpty());
		assertEquals(1, closed.size());
	}

	@Test
	public void theInjectedHttpClientIsNotClosed() throws Exception {
		JsonRpcHttpClientTransport first = transport(serving());
		listener(first);

		loop.submit(first::close);

		// a second transport on the SAME client still exchanges (FR-040): the client is not owned
		JsonRpcHttpClientTransport second = transport(serving());
		List<byte[]> delivered = new ArrayList<>();
		listener(second, delivered);
		await(loop.submit(() -> second.send(ADD_DOCUMENT)).toCompletableFuture(), "send through transport 2");

		assertEquals(1, delivered.size());
		assertEquals("the shared client served both exchanges", 1, (int) loop.submit(() -> countingClient.requests));
	}

	// ---------------------------------------------------------------------------------------------------
	// The instruments.
	// ---------------------------------------------------------------------------------------------------

	/** The real servlet — the default servlet for the close cases that need no shaping. */
	private AsyncServlet serving() {
		return servlet::serve;
	}

	private JsonRpcHttpClientTransport transport(AsyncServlet servlet) {
		// listen() is reactor-guarded (AbstractReactiveServer) and must run on the loop thread
		server = loop.submit(() -> {
			JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(loop.eventloop())
				.withServlet(servlet)
				.build();
			server.listen();
			return server;
		});
		return JsonRpcHttpClientTransport.create(loop.eventloop(), countingClient,
			"http://127.0.0.1:" + server.port() + "/");
	}

	/** Registers a listener recording only close events; returns the close-event list. */
	private List<Exception> listener(JsonRpcHttpClientTransport transport) {
		return listener(transport, new ArrayList<>());
	}

	/** Registers a listener recording deliveries and close events; returns the close-event list. */
	private List<Exception> listener(JsonRpcHttpClientTransport transport, List<byte[]> delivered) {
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
		return closed;
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

	private static byte[] utf8(String document) {
		return document.getBytes(UTF_8);
	}

	/**
	 * The counting {@link IHttpClient} wrapper — the observable proof of "no request issued". Its
	 * counter is mutated on the loop thread (requests arrive there) and is read via
	 * {@code loop.submit}. Not a {@code StubHttpClient}: the delegate is the real {@link HttpClient}.
	 */
	private static final class CountingHttpClient implements IHttpClient {
		private final HttpClient delegate;
		private int requests;

		private CountingHttpClient(HttpClient delegate) {
			this.delegate = delegate;
		}

		@Override
		public Promise<HttpResponse> request(HttpRequest request) {
			requests++;
			return delegate.request(request);
		}
	}
}
