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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T076 / FR-050, FR-052, SC-015: with the peer's {@code initial_max_streams_bidi} credit exhausted,
 * requests wait in a <b>bounded</b> queue rather than failing, and are sent as {@code MAX_STREAMS}
 * grants credit — while overflow fails immediately, retryably, naming the setting key, and a request
 * that spends its whole life queued still expires on the request timeout.
 * <p>
 * The server advertises one concurrent request stream, so the bound under test is the transport's own
 * and needs no injection: the second request genuinely has nowhere to go.
 */
public final class Http3ClientQueueTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long REQUEST_TIMEOUT_MILLIS = 100;

	private static final String WARM_UP = "/warm-up";
	private static final String HELD = "/held";
	private static final String QUEUED = "/queued";

	/** Servlet answers, resolved by the test — a request is in flight exactly while its entry is unset. */
	private final Map<String, SettablePromise<HttpResponse>> pending = new LinkedHashMap<>();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void queuedRequestsAreSentAsCreditIsGranted() {
		start(Http3Settings.create());
		warmUp();

		List<Promise<HttpResponse>> requests = issue("/r0", "/r1", "/r2");
		fixture.wire().driveUntil(() -> pending.containsKey("/r0"));

		assertEquals("only the first request has a stream", 1, servedPaths());
		assertEquals("FR-050: the other two wait for MAX_STREAMS", 2, client().queuedRequestCount());
		assertTrue("nothing failed while queued", requests.stream().noneMatch(Promise::isException));

		answer("/r0");
		assertEquals("r0", body(fixture.await(requests.get(0))));
		fixture.wire().driveUntil(() -> pending.containsKey("/r1"));
		assertEquals(1, client().queuedRequestCount());

		answer("/r1");
		assertEquals("r1", body(fixture.await(requests.get(1))));
		fixture.wire().driveUntil(() -> pending.containsKey("/r2"));

		answer("/r2");
		assertEquals("r2", body(fixture.await(requests.get(2))));
		assertEquals(0, client().queuedRequestCount());
		assertEquals("one connection served all three", 1, fixture.server().connectionsAccepted());
	}

	@Test
	public void theQueueNeverExceedsItsBoundAndOverflowFailsRetryably() {
		start(Http3Settings.builder().withMaxQueuedRequests(2).build());
		warmUp();

		// The warm-up spent the peer's single stream credit and nothing has been driven since, so all four
		// arrive with nowhere to go: the first two take the queue's two slots, the rest are refused.
		List<Promise<HttpResponse>> requests = issue("/r0", "/r1", "/r2", "/r3");

		assertEquals("FR-050: the queue is held at its bound", 2, client().queuedRequestCount());
		assertFalse("the queued requests are still waiting", requests.get(0).isComplete());
		assertFalse("the queued requests are still waiting", requests.get(1).isComplete());
		assertOverflowed(requests.get(2));
		assertOverflowed(requests.get(3));

		// The two the queue held still complete, so the refusals cost it nothing.
		for (int i = 0; i < 2; i++) {
			String path = "/r" + i;
			fixture.wire().driveUntil(() -> pending.containsKey(path));
			answer(path);
			assertEquals("r" + i, body(fixture.await(requests.get(i))));
		}
		assertEquals(0, client().queuedRequestCount());
	}

	private static void assertOverflowed(Promise<HttpResponse> overflowed) {
		assertTrue("FR-050: the overflowing request failed at once: " + overflowed, overflowed.isException());
		Exception e = overflowed.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertTrue("FR-050: retryable", ((Http3Exception) e).isRetryable());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) e).errorCode());
		assertTrue("it names the setting key: " + e.getMessage(), e.getMessage().contains("maxQueuedRequests"));
	}

	@Test
	public void aRequestQueuedPastTheRequestTimeoutFails() {
		start(Http3Settings.builder().withRequestTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MILLIS)).build());
		warmUp();

		List<Promise<HttpResponse>> requests = issue(HELD, QUEUED);
		fixture.wire().driveUntil(() -> pending.containsKey(HELD));
		assertEquals("FR-052: it is queued, not in flight", 1, client().queuedRequestCount());

		fixture.wire().advance(REQUEST_TIMEOUT_MILLIS + 10);
		fixture.wire().driveUntil(() -> requests.stream().allMatch(Promise::isComplete));

		Promise<HttpResponse> queued = requests.get(1);
		assertTrue("the queued request expired: " + queued, queued.isException());
		Exception e = queued.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertEquals("FR-052: H3_REQUEST_CANCELLED", Http3Errors.H3_REQUEST_CANCELLED, ((Http3Exception) e).errorCode());
		assertTrue("it names the timeout: " + e.getMessage(), e.getMessage().contains("timeout"));
		assertTrue("both requests expired", requests.get(0).isException());
		assertEquals(2, client().requestsTimedOut());

		// The servlet answers a request nobody is waiting for; it must not leak (ByteBufRule's assertion).
		answer(HELD);
		loop.tickUntilQuiet();
	}

	// ---------------------------------------------------------------- harness

	private void start(Http3Settings clientSettings) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> pending.computeIfAbsent(request.getPath(), $ -> new SettablePromise<>()))
			// One concurrent request stream, so the second request genuinely has no credit.
			.withServerSettings(Http3Settings.builder().withMaxConcurrentRequestStreams(1).build())
			.withClientSettings(clientSettings)
			.start();
	}

	/** Completes one exchange, so the pool holds an established connection before the burst. */
	private void warmUp() {
		Promise<HttpResponse> warmUp = client().request(HttpRequest.get(url(HOST, WARM_UP)).build());
		fixture.wire().driveUntil(() -> pending.containsKey(WARM_UP));
		answer(WARM_UP);
		body(fixture.await(warmUp));
		assertEquals(1, client().connectionCount());
		// Cleared so that pending.size() counts only what the burst below put on a stream.
		pending.clear();
	}

	private List<Promise<HttpResponse>> issue(String... paths) {
		List<Promise<HttpResponse>> requests = new ArrayList<>();
		for (String path : paths) {
			requests.add(client().request(HttpRequest.get(url(HOST, path)).build()));
		}
		return requests;
	}

	private void answer(String path) {
		pending.get(path).set(HttpResponse.ok200()
			.withBody(path.substring(1).getBytes(UTF_8))
			.build());
	}

	private int servedPaths() {
		return pending.size();
	}

	private Http3Client client() {
		return fixture.client();
	}

	private String body(HttpResponse response) {
		ByteBuf body = fixture.await(response.loadBody());
		try {
			return body.getString(UTF_8);
		} finally {
			body.recycle();
		}
	}
}
