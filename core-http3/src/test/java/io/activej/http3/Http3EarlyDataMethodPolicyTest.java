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
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestServer;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T096, spec FR-068 — <b>what the client is willing to put in early data</b>. The point of the rule is
 * that an ordinary application never provokes a rejection round trip: a request that must not be
 * replayed is held back until the handshake finishes rather than sent and then taken back.
 *
 * <h2>The default</h2>
 * A method that is safe per RFC 9110 §9.2.1 — {@code GET}, {@code HEAD}, {@code OPTIONS},
 * {@code TRACE} — <b>and</b> no body. The second half is not decoration: a retry re-sends the very same
 * {@code HttpRequest}, whose body stream a first attempt has already taken, so a request with a body
 * cannot be replayed and a retry of one would silently send a different message.
 *
 * <h2>Held back, not refused</h2>
 * An ineligible request on a connection whose handshake is still running waits for it. It was never
 * attempted, so it is never rejected and never retried — {@link Http3Client#earlyDataRetried()} stays
 * {@code 0} even against a server that refuses every offer.
 *
 * <h2>How "it did not go early" is observed</h2>
 * The connection's early data is its own: a resumed connection offers it whatever the first request
 * turns out to be, because the offer is made by the {@code ClientHello}. What changes is the
 * <b>stream</b> the request rides. A request held back is opened after the handshake, on a connection
 * whose first request stream is therefore {@code 0} — the same identifier an early request would have
 * used, but reached without a rejection: {@link Http3Client#zeroRttRejected()} counts the connection,
 * and {@link Http3Client#earlyDataRetried()} counts the request. Only the second one distinguishes
 * "held back" from "sent and taken back", which is why both are asserted.
 */
public final class Http3EarlyDataMethodPolicyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** One connection at a time, so the second request to an authority is a resumption. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final long FIRST_REQUEST_STREAM = 0;

	private static final String PATH = "/resumed";

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;
	private @Nullable Http3TestServer server;

	private final List<HttpMethod> served = new ArrayList<>();
	private final List<Boolean> servedEarly = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		cache = InMemoryQuicSessionCache.create(8, loop::currentTimeMillis);
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- the default rule

	/**
	 * A {@code POST} is not safe, so it waits for the handshake — against a server that <b>accepts</b>
	 * early data, which is what makes this a statement about the client's own policy rather than about
	 * the server's answer.
	 */
	@Test
	public void anUnsafeMethodIsHeldBackUntilTheHandshakeFinishes() {
		start(true);
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.post(url(HOST, PATH)).build()));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals(List.of(HttpMethod.POST), served);
		assertEquals("the POST reached the servlet as early data", List.of(false), servedEarly);
		assertEquals("the connection still offered early data — the request simply did not ride it",
			1, client().zeroRttAttempted());
		assertEquals("a request that was never sent early cannot have been rejected",
			0, client().earlyDataRetried());
		assertEquals(List.of(FIRST_REQUEST_STREAM), server.requestStreamIds());
	}

	/** The same, against a refusing server: nothing was at risk, so nothing is retried. */
	@Test
	public void anUnsafeMethodProvokesNoRejectionRoundTripAtAll() {
		start(false);
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.post(url(HOST, PATH)).build()));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals("the refusal is the connection's; no request of ours was in it",
			1, client().zeroRttRejected());
		assertEquals(0, client().earlyDataRetried());
		assertEquals(List.of(FIRST_REQUEST_STREAM), server.requestStreamIds());
		assertEquals(List.of(false), servedEarly);
	}

	/** A safe method is unchanged: it rides the early data, and against a refusing server it is retried. */
	@Test
	public void aSafeMethodStillTravelsInEarlyData() {
		start(true);
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals(List.of(HttpMethod.GET), served);
		assertEquals("a GET on a resumed connection did not reach the servlet as early data",
			List.of(true), servedEarly);
		assertEquals(1, client().zeroRttAccepted());
		assertEquals(0, client().earlyDataRetried());
	}

	/**
	 * A body makes a request unreplayable whatever its method, and a {@code GET} may carry one. Held
	 * back for exactly the reason a {@code POST} is: a retry would re-send a message whose body the
	 * first attempt has already consumed.
	 */
	@Test
	public void aSafeMethodCarryingABodyIsStillHeldBack() {
		start(true);
		resume();

		HttpResponse response = fixture.await(client().request(
			HttpRequest.get(url(HOST, PATH)).withBody("payload".getBytes(UTF_8)).build()));

		assertEquals(200, response.getCode());
		assertEquals("a request that cannot be replayed must not be exposed to a rejection",
			List.of(false), servedEarly);
		assertEquals(0, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- the opt-in

	/** {@link Http3EarlyData#allow} opts a request past the method rule, and nothing else changes. */
	@Test
	public void anOptedInUnsafeMethodDoesTravelInEarlyData() {
		start(true);
		resume();

		HttpResponse response = fixture.await(client().request(
			Http3EarlyData.allow(HttpRequest.post(url(HOST, PATH)).build())));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals(List.of(HttpMethod.POST), served);
		assertEquals("the opt-in did not put the request in early data", List.of(true), servedEarly);
		assertEquals(1, client().zeroRttAccepted());
	}

	/** The opt-in is a marker on one request, not a mode: the next request is judged on its own. */
	@Test
	public void theOptInDoesNotOutliveTheRequestItWasPutOn() {
		assertFalse(Http3EarlyData.isAllowed(HttpRequest.post(url(HOST, PATH)).build()));
		assertTrue(Http3EarlyData.isAllowed(Http3EarlyData.allow(HttpRequest.post(url(HOST, PATH)).build())));
		assertFalse(Http3EarlyData.isAllowed(HttpRequest.get(url(HOST, PATH)).build()));
	}

	/**
	 * The opt-in does <b>not</b> reach past the replayability rule, and that is deliberate: a body-bearing
	 * request is not something a consumer can consent to having replayed, because the retry would not
	 * replay it — it would send the same request without its body.
	 */
	@Test
	public void anOptedInRequestWithABodyIsStillHeldBack() {
		start(true);
		resume();

		HttpResponse response = fixture.await(client().request(Http3EarlyData.allow(
			HttpRequest.post(url(HOST, PATH)).withBody("payload".getBytes(UTF_8)).build())));

		assertEquals(200, response.getCode());
		assertEquals(List.of(false), servedEarly);
		assertEquals(0, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- harness

	private void start(boolean earlyDataEnabled) {
		fixture = new Http3ClientFixture(loop)
			.withServerFactory(socket -> server = new Http3TestServer(socket)
				.withEarlyDataEnabled(earlyDataEnabled)
				// This class states what the *client* is willing to send early (FR-068). The server's own
				// rule (FR-064) would otherwise answer 425 to the opted-in POST below and provoke exactly
				// the round trip these assertions are about; it is tested in Http3EarlyDataIndicationTest.
				.withEarlyDataPolicy(request -> true)
				.withHandler((request, context) -> {
					served.add(request.getMethod());
					servedEarly.add(context.earlyData());
					return HttpResponse.ok200().withBody(request.getPath().getBytes(UTF_8)).toPromise();
				})
				.start())
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(cache)
			.start();
	}

	/**
	 * Earns a ticket and forces the pooled connection out, so the next request to {@link Http3ClientFixture#HOST}
	 * dials again and resumes. Everything recorded up to here is forgotten.
	 */
	private void resume() {
		exchange(HOST, "/first");
		assertTrue("the server issued no ticket, so nothing here resumes", client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/second");
		assertEquals(1, client().connectionsEvicted());
		server.startRecording();
		served.clear();
		servedEarly.clear();
	}

	private void exchange(String host, String path) {
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(host, path)).build()));
		assertEquals(200, response.getCode());
		assertBody(path, response);
	}

	private void assertBody(String expected, HttpResponse response) {
		ByteBuf body = fixture.await(response.loadBody());
		try {
			assertEquals(expected, body.getString(UTF_8));
		} finally {
			body.recycle();
		}
	}

	private Http3Client client() {
		return fixture.client();
	}
}
