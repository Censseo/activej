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
import io.activej.http.HttpClient;
import io.activej.http.HttpError;
import io.activej.http.HttpResponse;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.ConformanceJson;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
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
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.jsonrpc.transport.http.fixtures.TestApiImpl.FAIL_WITH_DATA_JSON;
import static io.activej.test.EventloopThread.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * User story 2's acceptance scenarios 1–4 end to end (T046): a Java consumer calls the remote
 * service through {@code JsonRpcClient.proxy(TestApi.class)} with <b>no HTTP code beyond
 * constructing the transport</b> (SC-008).
 * <p>
 * <ul>
 *     <li>scenario 1 — a result decodes: {@code api.add(2, 3)} returns {@code AddResult(sum = 5)};</li>
 *     <li>scenario 2 — a {@code JsonRpcException} round-trips code, message and {@code data}
 *     unaltered (feature 012 FR-072), compared with the test-jar's JSON comparison rules
 *     (ConformanceJson — reusing the comparison helper is not copying a vector, plan D12);</li>
 *     <li>scenario 3 — a notification POSTs, gets {@code 204}, delivers nothing and still completes
 *     its {@code Promise<Void>} — the notification's promise IS the transport's send promise
 *     (feature 012 F11);</li>
 *     <li>scenario 4 — a {@code 500} leaves the correlation table empty, and the first client is
 *     unaffected (no cross-client contamination).</li>
 * </ul>
 * One persistent server over the real {@link JsonRpcServlet} on a dedicated {@link EventloopThread}
 * (plan D4); a shaped wrapper records the last status the servlet produced and answers {@code 500}
 * on {@code /fail}. All proxy calls and all {@code inFlightCount()} reads go through
 * {@code loop.submit(...)} — the proxy's handler checks the reactor thread.
 */
public final class JsonRpcHttpProxyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private EventloopThread loop;
	private JsonRpcHttpTestServer server;
	private HttpClient httpClient;
	private final AtomicInteger lastStatus = new AtomicInteger();
	private final List<Exception> clientFailures = new ArrayList<>();
	private JsonRpcClient client;

	@Before
	public void setUp() throws Exception {
		loop = EventloopThread.create("jsonrpc-http-proxy-test");
		loop.submit(() -> {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.build();
			JsonRpcServlet servlet = JsonRpcServlet.create(loop.eventloop(), dispatcher);
			server = JsonRpcHttpTestServer.builder(loop.eventloop())
				.withServlet(request -> {
					Promise<HttpResponse> response = request.getPath().equals("/fail") ?
						HttpResponse.ofCode(500).toPromise() :
						servlet.serve(request);
					return response.map(res -> {
						lastStatus.set(res.getCode());
						return res;
					});
				})
				.build();
			try {
				server.listen();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
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

	@Test
	public void aResultDecodes() {
		TestApi api = proxy("/");

		TestApi.AddResult result = await(loop.submit(() -> api.add(2, 3)).toCompletableFuture(), "add");

		assertEquals(5, result.sum());
		assertEquals("the correlation table is empty after the answer", 0, (int) loop.submit(client::inFlightCount));
		assertTrue("nothing failed into the failure handler", loop.submit(clientFailures::isEmpty));
	}

	@Test
	public void aJsonRpcExceptionRoundTripsCodeMessageAndData() {
		TestApi api = proxy("/");

		Exception caught = awaitException(loop, loop.submit(() -> api.failWithData()).toCompletableFuture(),
			"failWithData");

		assertTrue("the proxy call fails with the peer's JsonRpcException, got " + caught,
			caught instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) caught).getError();
		assertEquals("the code travels verbatim", 1001, error.code());
		assertEquals("the message travels verbatim", "deliberate failure with data", error.message());
		// the data member travels verbatim too — compared with the test-jar's JSON comparison rules
		// (FR-051's spirit: comparison rules are shared, vectors are not — plan D12)
		ConformanceJson.assertJsonEquals(
			ConformanceJson.parseJson(FAIL_WITH_DATA_JSON),
			ConformanceJson.parseJson(new String(error.data().toByteArray(), UTF_8)));
		assertEquals(0, (int) loop.submit(client::inFlightCount));
	}

	@Test
	public void aNotificationPostsGets204DeliversNothingAndStillCompletes() {
		TestApi api = proxy("/");

		Promise<Void> notify = loop.submit(() -> api.notifyAsync("hi"));
		await(notify.toCompletableFuture(), "notifyAsync");

		assertEquals("the server answered the notification with 204", 204, lastStatus.get());
		assertTrue("a wrongly-delivered document would have surfaced in the failure handler as a decode " +
					   "failure; nothing did",
			loop.submit(clientFailures::isEmpty));
		assertEquals("a notification registers no correlation entry (feature 012 FR-071)",
			0, (int) loop.submit(client::inFlightCount));
	}

	@Test
	public void a500LeavesTheCorrelationTableEmpty() {
		TestApi good = proxy("/");

		JsonRpcClient failingClient = client("/fail");
		TestApi failing = loop.submit(() -> failingClient.proxy(TestApi.class));

		Exception caught = awaitException(loop, loop.submit(() -> failing.add(2, 3)).toCompletableFuture(),
			"add to /fail");

		assertTrue("a 500 fails that call with an HttpError, got " + caught, caught instanceof HttpError);
		assertEquals(500, ((HttpError) caught).getCode());
		assertEquals("a 500 leaves the correlation table empty", 0, (int) loop.submit(failingClient::inFlightCount));

		// no cross-client contamination: the first client still works
		TestApi.AddResult again = await(loop.submit(() -> good.add(4, 5)).toCompletableFuture(), "add on the first client");
		assertEquals(9, again.sum());
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring.
	// ---------------------------------------------------------------------------------------------------

	private TestApi proxy(String path) {
		client = client(path);
		return loop.submit(() -> client.proxy(TestApi.class));
	}

	private JsonRpcClient client(String path) {
		return loop.submit(() -> JsonRpcClient.builder(loop.eventloop(),
				JsonRpcHttpClientTransport.create(loop.eventloop(), httpClient,
					"http://127.0.0.1:" + server.port() + path))
			.withFailureHandler(clientFailures::add)
			.build());
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
}
