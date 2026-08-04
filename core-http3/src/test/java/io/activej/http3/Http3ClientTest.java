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
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
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
import static io.activej.http3.testutil.Http3ClientFixture.PORT;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T074 / FR-047, FR-048, US2 scenarios 1–3 and 6, SC-016: {@link Http3Client} against a real
 * {@link Http3Server} over one in-process QUIC fabric — GET, POST, an error status, a no-body response,
 * and the one-connection-per-authority rule.
 * <p>
 * Both halves are production components, so a round trip here crosses a genuine QUIC connection with a
 * genuine TLS 1.3 handshake rather than two mocks agreeing.
 * <p>
 * <b>Response ownership</b>: the {@link HttpResponse} a request resolves with is the caller's, and so is
 * the {@code ByteBuf} its {@code loadBody()} produces — which is why {@link #body} recycles it. A leak
 * here is {@code ByteBufRule}'s to report.
 */
public final class Http3ClientTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

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
	public void getRoundTrip() {
		List<HttpVersion> servedVersions = new ArrayList<>();
		start(request -> {
			servedVersions.add(request.getVersion());
			return HttpResponse.ok200()
				.withHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
				.withBody("Hello, HTTP/3!".getBytes(UTF_8))
				.toPromise();
		});

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/greeting")).build()));

		assertEquals(200, response.getCode());
		assertEquals("SC-016: the response reports HTTP/3", HttpVersion.HTTP_3_0, response.getVersion());
		assertEquals("text/plain", response.getHeader(HttpHeaders.CONTENT_TYPE));
		assertEquals("Hello, HTTP/3!", body(response));
		assertEquals(List.of(HttpVersion.HTTP_3_0), servedVersions);
		assertEquals(1, client().requestsIssued());
	}

	@Test
	public void theServerSeesTheMethodTargetAndAuthority() {
		List<String> seen = new ArrayList<>();
		start(request -> {
			seen.add(request.getMethod().name() + " " + request.getPathAndQuery() +
					 " host=" + request.getHeader(HttpHeaders.HOST));
			return HttpResponse.ok200().toPromise();
		});

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/items/7?full=1")).build()));

		assertEquals(200, response.getCode());
		assertEquals("", body(response));
		assertEquals(List.of("GET /items/7?full=1 host=" + HOST + ":" + PORT), seen);
	}

	@Test
	public void postRoundTripCarriesTheBodyBothWays() {
		start(request -> request.loadBody()
			.map(received -> HttpResponse.ok200()
				.withBody(("echo:" + received.getString(UTF_8)).getBytes(UTF_8))
				.build()));

		HttpResponse response = fixture.await(client().request(HttpRequest.post(url(HOST, "/echo"))
			.withBody("payload".getBytes(UTF_8))
			.build()));

		assertEquals(200, response.getCode());
		assertEquals("echo:payload", body(response));
	}

	@Test
	public void aResponseWithNoBodyResolvesWithAnEmptyBody() {
		start(request -> HttpResponse.ofCode(204).toPromise());

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/nothing")).build()));

		assertEquals(204, response.getCode());
		assertEquals("US2 scenario 6: an empty body, needing no further reads", "", body(response));
	}

	@Test
	public void anErrorStatusIsAnOrdinaryResponse() {
		start(request -> HttpResponse.ofCode(404)
			.withBody("no such thing".getBytes(UTF_8))
			.toPromise());

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/missing")).build()));

		assertEquals(404, response.getCode());
		assertEquals("no such thing", body(response));
	}

	@Test
	public void twoRequestsToOneAuthorityShareOneConnection() {
		start(request -> HttpResponse.ok200()
			.withBody(request.getPath().getBytes(UTF_8))
			.toPromise());

		assertEquals("/first", body(fixture.await(client().request(HttpRequest.get(url(HOST, "/first")).build()))));
		assertEquals("/second", body(fixture.await(client().request(HttpRequest.get(url(HOST, "/second")).build()))));

		assertEquals("FR-048: one QUIC connection per authority", 1, client().connectionCount());
		assertEquals(1, fixture.server().connectionsAccepted());
		assertEquals(2, client().requestsIssued());
	}

	@Test
	public void twoAuthoritiesGetTwoConnections() {
		start(request -> HttpResponse.ok200()
			.withBody(String.valueOf(request.getHeader(HttpHeaders.HOST)).getBytes(UTF_8))
			.toPromise());

		String first = body(fixture.await(client().request(HttpRequest.get(url(HOST, "/x")).build())));
		String second = body(fixture.await(client().request(HttpRequest.get(url(OTHER_HOST, "/x")).build())));

		assertEquals(HOST + ":" + PORT, first);
		assertEquals(OTHER_HOST + ":" + PORT, second);
		assertEquals("FR-048: the pool is keyed by (scheme, host, port)", 2, client().connectionCount());
		assertEquals(2, fixture.server().connectionsAccepted());
	}

	@Test
	public void closeIsIdempotentAndEmptiesThePool() {
		start(request -> HttpResponse.ok200().toPromise());
		body(fixture.await(client().request(HttpRequest.get(url(HOST, "/")).build())));

		assertEquals(1, client().connectionCount());
		client().close();
		client().close();

		assertTrue(client().isClosed());
		assertEquals(0, client().connectionCount());
	}

	@Test
	public void aRequestIssuedAfterCloseFailsWithoutTouchingTheNetwork() {
		start(request -> HttpResponse.ok200().toPromise());
		client().close();
		int resolvesBefore = fixture.dns().resolved().size();

		Exception e = fixture.awaitException(client().request(HttpRequest.get(url(HOST, "/")).build()));

		assertTrue("a closed client refuses: " + e, e instanceof Http3Exception);
		assertEquals("nothing was resolved", resolvesBefore, fixture.dns().resolved().size());
		assertEquals(0, client().connectionCount());
	}

	// ---------------------------------------------------------------- harness

	private void start(AsyncServlet servlet) {
		fixture = new Http3ClientFixture(loop).withServlet(servlet).start();
	}

	private Http3Client client() {
		return fixture.client();
	}

	/** The body of a delivered response, whose {@code ByteBuf} the caller owns. */
	private String body(HttpResponse response) {
		ByteBuf body = fixture.await(response.loadBody());
		try {
			return body.getString(UTF_8);
		} finally {
			body.recycle();
		}
	}
}
