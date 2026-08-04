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
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.queue.ChannelZeroBuffer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T077 / FR-049: the connection pool is bounded. At the bound the least-recently-used <b>idle</b>
 * connection is evicted to make room; when every pooled connection has a request in flight, a new
 * authority is refused with a retryable error and the pool does not grow.
 * <p>
 * {@code maxConnections} is set to 1 and then 2 here rather than the 256 default, because what is under
 * test is the behaviour at the bound, not the value of the bound.
 * <p>
 * These tests assert that the evicted connection is <i>gone</i>. That it leaves with GOAWAY first, as
 * FR-049 requires, is asserted where it can only be true if the frame crossed the wire — on the peer
 * that decoded it, in
 * {@link Http3ClientGoAwayTest#anEvictedIdleConnectionLeavesWithGoAway()} — because an
 * {@link Http3Server} exposes no per-connection state a test here could read it from.
 */
public final class Http3ClientPoolTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** A third authority; see {@link #start} for why it needs no certificate of its own. */
	private static final String THIRD_HOST = "third.example";

	private static final String HELD = "/held";

	/** A response whose body the test releases chunk by chunk; see the streaming-eviction test. */
	private static final String STREAMED = "/streamed";

	private static final String FIRST_CHUNK = "first";
	private static final String LAST_CHUNK = "last";

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
	public void atTheBoundTheLeastRecentlyUsedIdleConnectionIsEvicted() {
		start(1);

		exchange(HOST, "/first");
		assertEquals(1, client().connectionCount());
		assertEquals(1, fixture.server().connectionsAccepted());

		// The pool is full and its one entry is idle, so the second authority evicts it.
		exchange(OTHER_HOST, "/second");

		assertEquals("FR-049: the pool never grows past its bound", 1, client().connectionCount());
		assertEquals("a second connection was opened", 2, fixture.server().connectionsAccepted());
		assertEquals(1, client().connectionsEvicted());

		// The evicted authority reconnects rather than reusing a connection that is gone.
		exchange(HOST, "/third");
		assertEquals(1, client().connectionCount());
		assertEquals(3, fixture.server().connectionsAccepted());
		assertEquals(2, client().connectionsEvicted());
	}

	@Test
	public void theLeastRecentlyUsedIdleConnectionIsTheOneEvicted() {
		start(2);

		exchange(HOST, "/a");
		exchange(OTHER_HOST, "/b");
		// Touching HOST again makes OTHER_HOST the least recently used of the two.
		exchange(HOST, "/c");
		assertEquals(2, client().connectionCount());
		assertEquals(2, fixture.server().connectionsAccepted());

		exchange(THIRD_HOST, "/d");

		assertEquals(2, client().connectionCount());
		assertEquals(3, fixture.server().connectionsAccepted());
		// HOST survived: reusing it opens nothing new.
		exchange(HOST, "/e");
		assertEquals(3, fixture.server().connectionsAccepted());
	}

	/**
	 * T112: an exchange ends at the response <b>head</b>, but the transfer behind it does not — and an
	 * eviction is a GOAWAY followed by a close, which would sever exactly that transfer. So a connection
	 * whose response body is still streaming into a caller's hands is <b>busy</b>, and at
	 * {@code maxConnections} a new authority is refused rather than served at its expense.
	 */
	@Test
	public void aConnectionStreamingAResponseBodyIsNotEvictedUntilTheBodyIsDone() {
		start(1);

		// A response the test writes chunk by chunk, so its body is provably mid-transfer below.
		ChannelZeroBuffer<ByteBuf> responseBody = new ChannelZeroBuffer<>();
		Promise<HttpResponse> request = client().request(HttpRequest.get(url(HOST, STREAMED)).build());
		fixture.wire().driveUntil(() -> pending.containsKey(STREAMED));
		pending.get(STREAMED).set(HttpResponse.ok200().withBodyStream(responseBody.getSupplier()).build());

		HttpResponse response = fixture.await(request);
		ChannelSupplier<ByteBuf> body = response.takeBodyStream();
		responseBody.put(ByteBuf.wrapForReading(FIRST_CHUNK.getBytes(UTF_8)));
		Promise<ByteBuf> firstChunk = body.get();
		assertEquals(FIRST_CHUNK, take(firstChunk));
		assertEquals("the exchange is over: the head reached its caller", 0, client().activeRequests());

		// The pool is full and its one entry is mid-transfer, so there is nothing idle to evict.
		Promise<HttpResponse> refused = client().request(HttpRequest.get(url(OTHER_HOST, "/refused")).build());

		assertTrue("refused rather than served by evicting a live transfer: " + refused, refused.isException());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) refused.getException()).errorCode());
		assertEquals("the streaming connection was not evicted", 0, client().connectionsEvicted());
		assertEquals("no second connection was opened", 1, fixture.server().connectionsAccepted());

		// The body arrives in full, on the connection the eviction would have taken with it ...
		responseBody.put(ByteBuf.wrapForReading(LAST_CHUNK.getBytes(UTF_8)));
		assertEquals(LAST_CHUNK, take(body.get()));
		responseBody.put(null);
		Promise<Void> drained = body.streamTo(ChannelConsumers.recycling());
		fixture.wire().driveUntil(drained::isComplete);
		assertTrue("the body finished: " + drained, drained.isResult());

		// ... and once it has, that connection is idle again and evictable like any other.
		exchange(OTHER_HOST, "/second");
		assertEquals(1, client().connectionsEvicted());
		assertEquals(2, fixture.server().connectionsAccepted());
	}

	@Test
	public void withEveryConnectionBusyTheRequestIsRefusedAndThePoolDoesNotGrow() {
		start(1);

		Promise<HttpResponse> held = client().request(HttpRequest.get(url(HOST, HELD)).build());
		fixture.wire().driveUntil(() -> pending.containsKey(HELD));
		assertEquals(1, client().connectionCount());

		Promise<HttpResponse> refused = client().request(HttpRequest.get(url(OTHER_HOST, "/refused")).build());

		assertTrue("FR-049: refused immediately, not queued: " + refused, refused.isException());
		Exception e = refused.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertTrue("retryable", ((Http3Exception) e).isRetryable());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) e).errorCode());
		assertTrue("it names the setting key: " + e.getMessage(), e.getMessage().contains("maxConnections"));
		assertEquals("the pool did not grow", 1, client().connectionCount());
		assertEquals("no second connection was opened", 1, fixture.server().connectionsAccepted());
		assertEquals("nothing was evicted", 0, client().connectionsEvicted());

		// The busy connection is untouched by the refusal and still finishes its own exchange.
		answer(HELD);
		assertEquals("held", body(fixture.await(held)));
	}

	// ---------------------------------------------------------------- harness

	private void start(int maxConnections) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> pending.computeIfAbsent(request.getPath(), $ -> new SettablePromise<>()))
			.withClientSettings(Http3Settings.builder().withMaxConnections(maxConnections).build())
			// Every authority is verified against the dev certificate's own name: what is under test is
			// pooling, and the certificate carries two SANs where this class needs three authorities.
			.withTlsEngineFactory(host -> Http3TestTls.clientEngineFactory(HOST))
			.start();
	}

	/** One complete exchange, leaving the connection to {@code host} idle and pooled. */
	private void exchange(String host, String path) {
		Promise<HttpResponse> request = client().request(HttpRequest.get(url(host, path)).build());
		fixture.wire().driveUntil(() -> pending.containsKey(path));
		answer(path);
		assertEquals(path.substring(1), body(fixture.await(request)));
	}

	private void answer(String path) {
		pending.get(path).set(HttpResponse.ok200()
			.withBody(path.substring(1).getBytes(UTF_8))
			.build());
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

	/** Drives one body chunk out of a stream and recycles it — the chunk is the caller's (CSP contract). */
	private String take(Promise<ByteBuf> chunk) {
		ByteBuf buf = fixture.await(chunk);
		try {
			return buf.getString(UTF_8);
		} finally {
			buf.recycle();
		}
	}
}
