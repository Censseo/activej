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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestPeer.Response;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T065 / FR-041, FR-043, SC-005, SC-016: an unmodified {@link AsyncServlet} served over HTTP/3.
 * <p>
 * The server is a real {@link Http3Server} owning its own {@code QuicEndpoint} over the fixture's
 * {@code StubUdpSocket}; the client is {@link Http3TestPeer}, whose control stream, SETTINGS and QPACK
 * behaviour are the module's own but whose request writing is hand-driven — so a round trip here is a
 * round trip over a genuine QUIC connection, not two halves of one abstraction agreeing.
 */
public final class Http3ServerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int CONCURRENT_REQUESTS = 10;

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3TestPeer peer;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void getRoundTrip() {
		List<HttpVersion> servedVersions = new ArrayList<>();
		start(request -> {
			servedVersions.add(request.getVersion());
			return HttpResponse.ok200()
				.withHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
				.withBody("Hello, HTTP/3!".getBytes(UTF_8))
				.toPromise();
		});

		Response response = await(peer.get("/greeting"));

		assertEquals(200, response.status());
		assertEquals("Hello, HTTP/3!", response.bodyString());
		assertEquals("text/plain", response.field("content-type"));
		assertEquals("SC-016: the servlet sees an HTTP/3 request", List.of(HttpVersion.HTTP_3_0), servedVersions);
		assertEquals(1, server.requestsServed());
	}

	@Test
	public void theServletSeesTheRequestTargetAndAuthority() {
		List<String> seen = new ArrayList<>();
		start(request -> {
			seen.add(request.getMethod().name() + " " + request.getPath() + "?" + request.getQuery() +
					 " host=" + request.getHeader(HttpHeaders.HOST));
			return HttpResponse.ok200().toPromise();
		});

		Response response = await(peer.get("/items/7?full=1"));

		assertEquals(200, response.status());
		assertEquals(List.of("GET /items/7?full=1 host=" + Http3TestTls.SERVER_NAME), seen);
	}

	@Test
	public void postRoundTripEchoesTheBody() {
		start(request -> request.loadBody()
			.map(body -> HttpResponse.ok200()
				.withBody(("echo:" + body.getString(UTF_8)).getBytes(UTF_8))
				.build()));

		Response response = await(peer.post("/echo", "payload".getBytes(UTF_8)));

		assertEquals(200, response.status());
		assertEquals("echo:payload", response.bodyString());
	}

	@Test
	public void aBodyCarriedByOneDataFrameLargerThanAChunkRoundTripsWhole() {
		// T110: a single wire DATA frame past Http3FrameReader's chunk is delivered in several
		// instalments, in both directions — the peer sends this body as one frame, and the servlet's
		// echo comes back as one frame too. Content-Length is reconciled against the body total on each
		// side, which is what makes several instalments of one frame indistinguishable from several
		// frames. Nothing else in the suite sends a DATA frame this large: Http3StreamingBodyTest frames
		// every 4 KiB.
		start(request -> request.loadBody()
			.map(body -> HttpResponse.ok200()
				.withBody(body.getArray())
				.build()));

		byte[] body = new byte[40_000];
		for (int i = 0; i < body.length; i++) {
			body[i] = (byte) (i * 31 + 7);
		}

		Response response = await(peer.post("/upload", body));

		assertEquals(200, response.status());
		assertArrayEquals("every byte of a 40 000-byte frame survived being cut into instalments",
			body, response.body());
	}

	@Test
	public void tenConcurrentRequestsCompleteOutOfOrderWithoutSeeingEachOthersData() {
		Map<String, SettablePromise<HttpResponse>> pending = new LinkedHashMap<>();
		// Deliberately unresolved here: the test completes them in reverse order below, so a response
		// that reached the wrong stream shows up as a body that names another request's path.
		start(request -> pending.computeIfAbsent(request.getPath(), $ -> new SettablePromise<>()));

		List<Promise<Response>> requests = new ArrayList<>();
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			requests.add(peer.get("/r" + i));
		}
		wire.driveUntil(() -> pending.size() == CONCURRENT_REQUESTS);

		assertEquals(CONCURRENT_REQUESTS, server.activeRequests());
		for (int i = CONCURRENT_REQUESTS - 1; i >= 0; i--) {
			String path = "/r" + i;
			pending.get(path).set(HttpResponse.ok200()
				.withBody(("body-for" + path).getBytes(UTF_8))
				.build());
		}

		wire.driveUntil(() -> requests.stream().allMatch(Promise::isComplete));
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			Promise<Response> request = requests.get(i);
			assertTrue("request " + i + " completed: " + request, request.isResult());
			assertEquals("body-for/r" + i, request.getResult().bodyString());
		}
		assertEquals(CONCURRENT_REQUESTS, server.requestsServed());
		assertEquals(0, server.activeRequests());
	}

	@Test
	public void theServerAdvertisesItsRequestStreamBoundAsATransportParameter() {
		// FR-046 / FR-058b: the concurrency bound is the QUIC transport parameter and nothing else —
		// there is no second, application-level counter to disagree with it. This asserts the value the
		// server derives from its own settings actually reaches the peer.
		start(request -> HttpResponse.ok200().toPromise(),
			Http3Settings.builder().withMaxConcurrentRequestStreams(7).build());

		QuicTransportParameters advertised = wire.clientConnection().peerTransportParameters();
		assertNotNull("the handshake supplied the server's parameters", advertised);
		assertEquals(7, advertised.initialMaxStreamsBidi());
		assertEquals(Http3Settings.MAX_UNI_STREAMS, advertised.initialMaxStreamsUni());
	}

	@Test
	public void aSecondRequestReusesTheSameConnection() {
		start(request -> HttpResponse.ok200()
			.withBody(request.getPath().getBytes(UTF_8))
			.toPromise());

		assertEquals("/first", await(peer.get("/first")).bodyString());
		assertEquals("/second", await(peer.get("/second")).bodyString());
		assertEquals("one QUIC connection served both", 1, server.connectionsAccepted());
	}

	// ---------------------------------------------------------------- harness

	private void start(AsyncServlet servlet) {
		start(servlet, Http3Settings.create());
	}

	private void start(AsyncServlet servlet, Http3Settings settings) {
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				server = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withSettings(settings)
					.build();
				server.listen();
				return server;
			})
			.connect();
	}

	private <T> T await(Promise<T> promise) {
		wire.driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the request failed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
