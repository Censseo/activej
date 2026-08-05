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
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.quic.tls.QuicSessionTicket;
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
import static org.junit.Assert.assertTrue;

/**
 * <b>SC-008, both defences in one scenario.</b> US5 ships two independent guards against a replayed
 * early-data flight, and every other test exercises exactly one of them:
 * {@code Http3EarlyDataIndicationTest} proves the safe-method policy answers {@code 425} without
 * invoking the servlet (and says so itself: "the half that is not [here]: refusing the second
 * presentation of a ticket is {@code QuicReplayGuard}'s"), while {@code Http3ZeroRttReplayTest} proves
 * a second presentation of a ticket buys no early data. Neither states what happens when both are in
 * play, which is the deployment every consumer actually runs.
 *
 * <h2>The composition this pins down</h2>
 * The two guards sit at different layers and could plausibly undo each other in one direction: the
 * replay register is consulted by {@code TlsServerEngine} during the <i>handshake</i>, and the policy
 * refuses during <i>dispatch</i>, well after. So a natural-looking implementation that only marked a
 * ticket used once its early data had been <i>served</i> would leave the slot unclaimed whenever the
 * policy refused — and an attacker who replayed a flight the policy refuses would get an unlimited
 * supply of fresh 0-RTT grants out of one captured ticket. The register would then be defeated by the
 * very requests it exists to defend against.
 * <p>
 * This asserts the opposite: <b>a policy refusal still spends the ticket.</b> Presentation one is
 * granted early data and answered {@code 425} by the default policy; presentation two of the same
 * ticket is refused as a replay, so the register counted the presentation the policy threw away.
 *
 * <h2>The SC-008 property itself</h2>
 * Across both presentations of one captured flight the servlet runs <b>exactly once per request the
 * caller made</b>, and <b>never once from early data</b> — the two numbers a duplicated side effect
 * would show up in. The caller sees only {@code 200}s (FR-067), so neither defence is visible to it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-8">RFC 8446 §8 — 0-RTT and Anti-Replay</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-9.2.1">RFC 9110 §9.2.1 — Safe Methods</a>
 */
public final class Http3ZeroRttReplaySafetyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** RFC 8470 §5.1's request field, spelled as a consumer would spell it. */
	private static final HttpHeader EARLY_DATA = HttpHeaders.of("Early-Data");

	private static final int TOO_EARLY = 425;

	/** One pooled connection, so a detour to {@link #OTHER_HOST} forces the redial that offers the ticket. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final String EFFECT = "/effect";

	private ManualEventloop loop;
	private RepeatingCache cache;
	private @Nullable Http3ClientFixture fixture;

	private final List<Served> served = new ArrayList<>();
	private final RecordingInspector inspector = new RecordingInspector();

	/** One servlet invocation — the unit a replay would duplicate. */
	private record Served(HttpMethod method, String path, @Nullable String indication) {}

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		cache = new RepeatingCache();
		served.clear();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		fixture = null;
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void aPolicyRefusalStillSpendsTheTicketSoTheReplayOfThatFlightIsCaught() {
		start();
		earnATicket();

		// Presentation one. The ticket has never been seen, so the register grants early data and the
		// POST really does travel at 0-RTT — and the default policy refuses it there, unread.
		post();

		assertEquals("the register granted this presentation", 1, server().zeroRttAccepted());
		assertEquals("... and the policy is what refused the request", 1, server().earlyDataRequestsRefused());
		assertEquals("nothing was a replay yet", 0, server().zeroRttRefusedAsReplay());
		assertEquals("the refused stream never reached the servlet, the retry did",
			List.of(retryStream()), inspector.dispatched);
		assertEquals(List.of(completed(firstStream(), TOO_EARLY), completed(retryStream(), 200)), inspector.completed);

		// Presentation two: the same captured ticket, once more. This is the step the whole register
		// exists for, and it only works if presentation one spent the slot despite being refused.
		evict();
		inspector.clear();
		post();

		assertEquals("the very same ticket was offered a second time", 2, client().zeroRttAttempted());
		assertEquals("and must not have bought early data again", 1, client().zeroRttAccepted());
		assertEquals("the register caught it — so the policy refusal did not release the slot",
			1, server().zeroRttRefusedAsReplay());
		assertEquals("the register granted early data exactly once, ever", 1, server().zeroRttAccepted());
		assertEquals("the policy was never consulted a second time: nothing arrived at 0-RTT to judge",
			1, server().earlyDataRequestsRefused());
		// The server never saw stream 0 here at all — it refused the early data, so it dropped those
		// 0-RTT packets undecrypted and has never heard of the stream they opened. The client discards
		// that stream and re-creates the work on the *next* identifier rather than re-using the one it
		// abandoned, which is core-quic's documented rule and what keeps the two sides' RFC 9000 §2.1
		// numbering in step. So one dispatch, on stream 4, and no 425 anywhere in this presentation.
		assertEquals("one dispatch, and it was not judged as early data",
			List.of(retryStream()), inspector.dispatched);
		assertEquals(List.of(completed(retryStream(), 200)), inspector.completed);

		// SC-008 itself: one execution per request the caller made — the POST twice because the caller
		// asked twice, never twice for one asking — and not one of them carrying RFC 8470's indication,
		// which is what says no execution came from early data. The GET between them is the eviction
		// detour, served by this same server; it is listed rather than filtered so the order is the
		// wire's rather than the assertion's.
		assertEquals(
			List.of(
				new Served(HttpMethod.POST, EFFECT, null),
				new Served(HttpMethod.GET, "/detour", null),
				new Served(HttpMethod.POST, EFFECT, null)),
			served);
		assertEquals("both presentations still resumed the session — a refusal costs the early data only",
			2, server().sessionsResumed());
		assertEquals(0, server().zeroRttRefusedAtCapacity());
		assertEquals(0, server().zeroRttRefusedAsExpired());
	}

	// ---------------------------------------------------------------- harness

	private void start() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> {
				served.add(new Served(request.getMethod(), request.getPath(), request.getHeader(EARLY_DATA)));
				return HttpResponse.ok200().withBody(request.getPath().getBytes(UTF_8)).toPromise();
			})
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON)
			.withServerInspector(inspector)
			.withSessionCache(cache)
			.start();
	}

	/** Earns the one ticket {@link RepeatingCache} will hand back for the rest of the test, then evicts. */
	private void earnATicket() {
		exchange(HOST, "/warmup");
		assertTrue("the server issued a ticket and the cache kept it", client().sessionTicketsStored() >= 1);
		evict();
		served.clear();
		inspector.clear();
	}

	/** Forces the pooled {@link #HOST} connection out, so the next request to it dials and resumes. */
	private void evict() {
		exchange(OTHER_HOST, "/detour");
	}

	/**
	 * The unsafe request a replay would duplicate, opted in to early data explicitly — the client's own
	 * default (FR-068) would otherwise hold a {@code POST} back and there would be nothing to refuse.
	 */
	private void post() {
		HttpResponse response = fixture.await(client().request(
			Http3EarlyData.allow(HttpRequest.post(url(HOST, EFFECT)).build())));
		assertEquals("the caller sees only the final outcome (FR-067)", 200, response.getCode());
		assertBody(EFFECT, response);
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

	/** The first client-initiated bidirectional stream of a connection (RFC 9000 §2.1). */
	private static long firstStream() {
		return 0;
	}

	/** The one the transparent retry opens after it — ids of that type are spaced four apart. */
	private static long retryStream() {
		return 4;
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

	/**
	 * A misbehaving store that keeps the first ticket {@link Http3ClientFixture#HOST} issued and answers
	 * every later {@code take} with it — the client-side single-use rule {@link QuicSessionCache}
	 * documents, deliberately broken. It is how a captured flight is staged without a packet recorder:
	 * the server cannot tell a re-offered ticket from a replayed one, and must not need to.
	 */
	private static final class RepeatingCache implements QuicSessionCache {
		private @Nullable QuicSessionTicket held;

		@Override
		public @Nullable QuicSessionTicket take(String serverName, int port, String alpn) {
			return HOST.equals(serverName) ? held : null;
		}

		@Override
		public void put(String serverName, int port, String alpn, QuicSessionTicket ticket) {
			if (held == null && HOST.equals(serverName)) held = ticket;
		}
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
