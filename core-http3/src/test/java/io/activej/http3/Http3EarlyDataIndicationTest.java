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
import io.activej.http.HttpHeader;
import io.activej.http.HttpHeaderValue;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
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
import java.util.Map;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T102, spec <b>FR-064</b>, <b>FR-065</b>, <b>FR-066</b> — the server-side early-data policy, end to end
 * over a real {@link Http3Server}: what reaches the servlet from 0-RTT, what does not, and what the
 * servlet is told about it.
 *
 * <h2>The two halves of one rule</h2>
 * A request whose HEADERS arrived at {@code ZERO_RTT} is judged before it is dispatched. Accepted, it
 * reaches the servlet carrying RFC 8470's {@code Early-Data: 1} — so application code can apply its own
 * rule on top of the deployment's. Refused, it is answered {@code 425 (Too Early)} and the servlet is
 * <b>never invoked</b>, which is the whole security property: a captured early-data flight replayed at
 * the origin cannot run a side effect twice if it never runs once.
 *
 * <h2>Why the assertions are made through the inspector</h2>
 * The client retries a 425 answering early data exactly once, transparently (FR-067), so a caller never
 * sees the refusal — by design. {@link Http3Server.Inspector#onRequestStarted} fires only where the
 * servlet is about to be invoked and {@link Http3Server.Inspector#onRequestCompleted} carries the status
 * actually written, so the pair of them is the wire truth: which streams reached the servlet, and what
 * each stream was answered.
 *
 * <h2>What this test cannot state yet</h2>
 * That a <b>replayed</b> early-data flight runs at most once (SC-008) is only half here. The half that
 * is: nothing unsafe runs from early data at all under the default policy, so a replay cannot duplicate
 * a side effect. The half that is not: refusing the <i>second presentation of a ticket</i> is
 * {@code QuicReplayGuard}'s, and it is not consulted by {@code TlsServerEngine} until T107.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8470">RFC 8470 — Using Early Data in HTTP</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-9.2.1">RFC 9110 §9.2.1 — Safe Methods</a>
 */
public final class Http3EarlyDataIndicationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** RFC 8470 §5.1's request field, spelled as a consumer would spell it — the token is case-insensitive. */
	private static final HttpHeader EARLY_DATA = HttpHeaders.of("Early-Data");

	private static final int TOO_EARLY = 425;

	/** The first request stream of a connection, and the one the retry opens after it (RFC 9000 §2.1). */
	private static final long FIRST_STREAM = 0;
	private static final long SECOND_STREAM = 4;

	/** One connection at a time, so the second request to an authority is a resumption. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final String PATH = "/resumed";

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;

	private final List<Served> served = new ArrayList<>();
	private final RecordingInspector inspector = new RecordingInspector();

	/**
	 * One servlet invocation.
	 *
	 * @param indication  the value of the one {@code Early-Data} field the servlet saw, or {@code null}
	 * @param indications how many {@code Early-Data} field lines the request carried — a peer's own claim
	 *                    must not survive beside this server's verdict
	 */
	private record Served(HttpMethod method, String path, @Nullable String indication, int indications) {}

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

	// ---------------------------------------------------------------- FR-066: the indication

	/** FR-066: a request the policy accepts from early data reaches the servlet carrying {@code Early-Data: 1}. */
	@Test
	public void aRequestAcceptedFromEarlyDataCarriesTheRfc8470Indication() {
		start();
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals(List.of(new Served(HttpMethod.GET, PATH, "1", 1)), served);
		assertEquals("the request really did travel in early data", 1, server().zeroRttAccepted());
		assertEquals(List.of(FIRST_STREAM), inspector.dispatched);
		assertEquals(List.of(completed(FIRST_STREAM, 200)), inspector.completed);
		assertEquals("nothing was refused, so nothing was retried", 0, client().earlyDataRetried());
	}

	/** The indication is exactly a statement about early data: an ordinary 1-RTT request carries none. */
	@Test
	public void anOrdinaryRequestCarriesNoIndication() {
		start();

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/first")).build()));

		assertEquals(200, response.getCode());
		assertBody("/first", response);
		assertEquals(List.of(new Served(HttpMethod.GET, "/first", null, 0)), served);
		assertEquals(0, server().zeroRttAccepted());
	}

	/**
	 * The indication a servlet reads is <b>this server's verdict</b>, not the peer's claim: a client that
	 * sends an {@code Early-Data} field of its own in early data has it replaced, not appended to, so
	 * there is exactly one field line and it says what actually happened.
	 */
	@Test
	public void aPeerCannotForgeTheIndicationOnItsOwnEarlyData() {
		start();
		resume();

		HttpResponse response = fixture.await(client().request(
			HttpRequest.get(url(HOST, PATH)).withHeader(EARLY_DATA, "0").build()));

		assertEquals(200, response.getCode());
		assertEquals(List.of(new Served(HttpMethod.GET, PATH, "1", 1)), served);
	}

	// ---------------------------------------------------------------- FR-064: the refusal

	/**
	 * SC-008's shape. A {@code POST} the consumer opted into early data (FR-068) is refused by the
	 * server's default policy: the servlet is not invoked, stream {@code 0} is answered {@code 425}, and
	 * the client's one transparent retry (FR-067) runs it once at 1-RTT — where it carries no indication,
	 * because by then it is an ordinary request.
	 */
	@Test
	public void anUnsafeMethodInEarlyDataIsRefusedWithoutReachingTheServlet() {
		start();
		resume();

		HttpResponse response = fixture.await(client().request(
			Http3EarlyData.allow(HttpRequest.post(url(HOST, PATH)).build())));

		assertEquals("the caller sees only the final outcome (FR-067)", 200, response.getCode());
		assertBody(PATH, response);
		assertEquals("exactly one execution, and it was not the early one",
			List.of(new Served(HttpMethod.POST, PATH, null, 0)), served);
		assertEquals("the refused stream never reached the servlet", List.of(SECOND_STREAM), inspector.dispatched);
		assertEquals(List.of(completed(FIRST_STREAM, TOO_EARLY), completed(SECOND_STREAM, 200)), inspector.completed);
		assertEquals(1, client().earlyDataRetried());
		assertEquals("the connection did accept the early data — the policy is what refused the request",
			1, server().zeroRttAccepted());
	}

	// ---------------------------------------------------------------- FR-065: the policy is replaceable

	/**
	 * T111, FR-065: a consumer-supplied policy replaces the default through the builder. A wider one
	 * accepts a {@code POST} from early data — which then reaches the servlet with the indication, and
	 * costs no round trip.
	 */
	@Test
	public void aWiderConsumerPolicyAcceptsWhatTheDefaultRefuses() {
		startWithPolicy(request -> true);
		resume();

		HttpResponse response = fixture.await(client().request(
			Http3EarlyData.allow(HttpRequest.post(url(HOST, PATH)).build())));

		assertEquals(200, response.getCode());
		assertEquals(List.of(new Served(HttpMethod.POST, PATH, "1", 1)), served);
		assertEquals(List.of(FIRST_STREAM), inspector.dispatched);
		assertEquals(List.of(completed(FIRST_STREAM, 200)), inspector.completed);
		assertEquals(0, client().earlyDataRetried());
	}

	/**
	 * And a narrower one refuses what the default accepts — including a safe method. The retry is judged
	 * by nothing: a request that arrives at 1-RTT is not early data, so the policy is not consulted for it
	 * and a deployment that refuses everything early still serves everything.
	 */
	@Test
	public void aNarrowerConsumerPolicyRefusesEvenASafeMethod() {
		startWithPolicy(request -> false);
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		assertEquals(200, response.getCode());
		assertBody(PATH, response);
		assertEquals(List.of(new Served(HttpMethod.GET, PATH, null, 0)), served);
		assertEquals(List.of(SECOND_STREAM), inspector.dispatched);
		assertEquals(List.of(completed(FIRST_STREAM, TOO_EARLY), completed(SECOND_STREAM, 200)), inspector.completed);
		assertEquals(1, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- harness

	private void start() {
		startWith(null);
	}

	private void startWithPolicy(Http3EarlyDataPolicy policy) {
		startWith(policy);
	}

	private void startWith(@Nullable Http3EarlyDataPolicy policy) {
		Http3ClientFixture built = new Http3ClientFixture(loop)
			.withServlet(request -> {
				served.add(new Served(request.getMethod(), request.getPath(),
					request.getHeader(EARLY_DATA), indicationsIn(request)));
				return HttpResponse.ok200().withBody(request.getPath().getBytes(UTF_8)).toPromise();
			})
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON)
			.withServerInspector(inspector)
			.withSessionCache(cache);
		if (policy != null) built.withServerEarlyDataPolicy(policy);
		fixture = built.start();
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
		served.clear();
		inspector.clear();
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

	private static int indicationsIn(HttpRequest request) {
		int indications = 0;
		for (Map.Entry<HttpHeader, HttpHeaderValue> field : request.getHeaders()) {
			if (field.getKey().equals(EARLY_DATA)) indications++;
		}
		return indications;
	}

	private static String completed(long streamId, int statusCode) {
		return streamId + " -> " + statusCode;
	}

	private Http3Client client() {
		return fixture.client();
	}

	private Http3Server server() {
		return fixture.server();
	}

	/** Which streams reached the servlet, and what each stream was actually answered. */
	private static final class RecordingInspector implements Http3Server.Inspector {
		private final List<Long> dispatched = new ArrayList<>();
		private final List<String> completed = new ArrayList<>();

		void clear() {
			dispatched.clear();
			completed.clear();
		}

		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {
			dispatched.add(streamId);
		}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {
			completed.add(completed(streamId, statusCode));
		}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {}
	}
}
