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

import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicTransportException;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.UNBOUND_PORT;
import static io.activej.http3.testutil.Http3ClientFixture.UNCERTIFIED_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T078 / FR-051, FR-060, US2 scenarios 4, 5 and 7: what {@link Http3Client} does when a request cannot
 * be made — a scheme it does not speak, an authority that answers nothing, a certificate it will not
 * accept, and its own {@code close()} with requests still outstanding.
 * <p>
 * A transport failure reaches the caller <b>unwrapped</b> (FR-058c): the RFC 9000 §20 code and the TLS
 * alert the peer chose are exactly what a caller needs, and an {@link Http3Exception} wrapper would
 * replace both with an H3 code that says less.
 */
public final class Http3ClientFailureTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int CONCURRENT_REQUESTS = 4;

	/** How far the clock is moved to reach the QUIC handshake timeout, which is 10 s by default. */
	private static final long PAST_HANDSHAKE_TIMEOUT_MILLIS = 15_000;

	private static final String HELD = "/held";

	private static final String NOTHING_POOLED = "nothing was pooled";

	private final Map<String, SettablePromise<HttpResponse>> pending = new LinkedHashMap<>();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> pending.computeIfAbsent(request.getPath(), $ -> new SettablePromise<>()))
			.start();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void aNonHttpsSchemeFailsBeforeAnySocketWork() {
		Promise<HttpResponse> request = fixture.client()
			.request(HttpRequest.get("http://" + HOST + ":" + Http3ClientFixture.PORT + "/plain").build());

		assertTrue("FR-051: failed synchronously: " + request, request.isException());
		Exception e = request.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertTrue("it names the scheme: " + e.getMessage(), e.getMessage().contains("https"));
		assertTrue("nothing was resolved", fixture.dns().resolved().isEmpty());
		assertEquals("no connection was opened", 0, fixture.client().connectionCount());
		assertEquals("no datagram was sent", 0, fixture.wire().network().sentCount());
	}

	@Test
	public void aRequestWithABodyOnANonHttpsSchemeReleasesThatBody() {
		// FR-051 refuses before any socket work, which makes this the one refusal that owns a body nobody
		// else will ever see. ByteBufRule is the assertion.
		Promise<HttpResponse> request = fixture.client()
			.request(HttpRequest.post("http://" + HOST + ":" + Http3ClientFixture.PORT + "/plain")
				.withBody("a body that goes nowhere".getBytes(UTF_8))
				.build());

		assertTrue(request.isException());
	}

	@Test
	public void anUnresolvableHostFailsWithTheResolversOwnException() {
		fixture.dns().fail(UNCERTIFIED_HOST);

		Exception e = fixture.awaitException(fixture.client()
			.request(HttpRequest.get(url(UNCERTIFIED_HOST, "/x")).build()));

		assertTrue("the resolver's own failure, unwrapped: " + e, e instanceof UnknownHostException);
		assertEquals(NOTHING_POOLED, 0, fixture.client().connectionCount());
	}

	@Test
	public void anUnreachableAuthorityFailsWithTheTransportsOwnException() {
		Promise<HttpResponse> request = fixture.client()
			.request(HttpRequest.get(url(HOST, UNBOUND_PORT, "/x")).build());

		fixture.wire().advance(PAST_HANDSHAKE_TIMEOUT_MILLIS);
		fixture.wire().driveUntil(request::isComplete);

		assertTrue("the handshake never completed: " + request, request.isException());
		Exception e = request.getException();
		assertTrue("FR-058c: the transport's own exception, unwrapped: " + e, e instanceof QuicTransportException);
		assertEquals(NOTHING_POOLED, 0, fixture.client().connectionCount());
	}

	@Test
	public void aCertificateTheClientWillNotAcceptFailsWithTheTlsAlert() {
		// The dev certificate covers `localhost` and `example.test`; RFC 6125 identification against any
		// other name fails, and the alert reaches the caller as the transport error carrying it.
		Exception e = fixture.awaitException(fixture.client()
			.request(HttpRequest.get(url(UNCERTIFIED_HOST, "/x")).build()));

		assertTrue("FR-058c: the transport's own exception, unwrapped: " + e, e instanceof QuicTransportException);
		assertEquals(NOTHING_POOLED, 0, fixture.client().connectionCount());
	}

	@Test
	public void closeFailsEveryOutstandingRequestExactlyOnce() {
		List<Promise<HttpResponse>> requests = new ArrayList<>();
		List<Integer> completions = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			int index = i;
			requests.add(fixture.client().request(HttpRequest.get(url(HOST, "/r" + i)).build())
				.whenComplete(($, e) -> completions.add(index)));
		}
		fixture.wire().driveUntil(() -> pending.size() == CONCURRENT_REQUESTS);
		assertTrue("all four are in flight", requests.stream().noneMatch(Promise::isComplete));

		fixture.client().close();
		loop.tickUntilQuiet();

		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			Promise<HttpResponse> request = requests.get(i);
			assertTrue("request " + i + " failed: " + request, request.isException());
		}
		assertEquals("each promise completed exactly once", CONCURRENT_REQUESTS, completions.size());
		assertEquals(0, fixture.client().connectionCount());
		assertEquals(0, fixture.client().activeRequests());

		// The servlets answer requests nobody is waiting for; nothing may leak (ByteBufRule's assertion).
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			pending.get("/r" + i).set(HttpResponse.ok200().withBody(("late" + i).getBytes(UTF_8)).build());
		}
		loop.tickUntilQuiet();
	}

	@Test
	public void closeWhileAConnectIsInFlightFailsTheRequestsWaitingOnIt() {
		Promise<HttpResponse> request = fixture.client().request(HttpRequest.get(url(HOST, HELD)).build());
		assertFalse("the connect has not completed yet", request.isComplete());

		fixture.client().close();
		loop.tickUntilQuiet();

		assertTrue("the request failed: " + request, request.isException());
		assertEquals(0, fixture.client().connectionCount());
	}

}
