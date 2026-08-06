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
import io.activej.http3.testutil.Http3TestServer;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.stream.QuicStreamResetException;
import io.activej.quic.stream.QuicStreamStopSendingException;
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
import static org.junit.Assert.assertTrue;

/**
 * T087–T090, FR-055 and FR-067 — the rejection path, which the
 * <a href="file:../../../../../../specs/006-h3-advanced/contracts/zero-rtt.md">0-RTT contract</a> states
 * in six points because getting it wrong is worse than having no 0-RTT at all:
 * <ol>
 *   <li>the client discards its 0-RTT keys and <b>all</b> stream state created by 0-RTT;</li>
 *   <li>the request is re-created on a <b>fresh</b> stream in 1-RTT;</li>
 *   <li>the caller's promise resolves <b>once</b>, with the correct response;</li>
 *   <li>the servlet observes the request <b>exactly once</b> — the rejected early-data payload is
 *       never processed;</li>
 *   <li>every {@code ByteBuf} the discarded state held is recycled exactly once (the {@link ByteBufRule}
 *       below, with no {@code @IgnoreLeaks});</li>
 *   <li>the retry is reported through the inspector (T097's, asserted where that counter lands).</li>
 * </ol>
 *
 * <h2>How a rejection is produced</h2>
 * {@link Http3TestServer} with {@code earlyDataEnabled(false)}: it seals and accepts session tickets, so
 * the PSK resumes the session, and omits {@code early_data} from EncryptedExtensions, so it installs no
 * 0-RTT keys and every 0-RTT packet is dropped as undecryptable. That is RFC 8446 §4.2.10's rejection
 * signal, and FR-048 requires it not to fail the handshake. The 425 half of FR-067 uses the same server
 * with early data <i>accepted</i> and a handler that answers {@code 425 (Too Early)} to a request that
 * arrived early — see the note on that test for what it does and does not prove.
 *
 * <h2>How "a fresh stream" is observed</h2>
 * Off the stream ids on which the server was handed a <b>request</b>
 * ({@link Http3TestServer#requestStreamIds()}). RFC 9000 §2.1 spaces client-initiated bidirectional
 * streams four apart, so on a freshly dialled connection the early-data request rides stream {@code 0}
 * and a request re-created after the rejection rides stream {@code 4}. A server handed the request on
 * {@code 0} was handed the early-data stream's own data, retransmitted rather than re-created — which
 * is what this asserts against, so the expected list is exactly {@code [4]}.
 * <p>
 * <b>Why the request rather than the open.</b> The same RFC 9000 §2.1 rule opens every lower-numbered
 * stream of a type implicitly, so a client re-creating its request on stream {@code 4} <i>necessarily</i>
 * makes the server open stream {@code 0} too — empty, and for ever. The stream the server was
 * <i>opened</i> is therefore {@code [0, 4]} whether the client did the right thing or the wrong one, and
 * cannot express the distinction. {@link Http3TestServer#streamsOpened()} carries that raw view, and
 * {@link Http3TestServer#abortedStreamIds()} is what says the discarded stream was never announced.
 */
public final class Http3ZeroRttRejectionTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * One connection at a time, so a request to the other authority evicts the first — which is what makes
	 * a <b>second dial to the same authority</b>, and so a resumption, reachable with one server.
	 */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	/** RFC 9000 §2.1: the first and second client-initiated bidirectional streams of a connection. */
	private static final long FIRST_REQUEST_STREAM = 0;
	private static final long SECOND_REQUEST_STREAM = 4;

	/** RFC 8470 §5.2. */
	private static final int TOO_EARLY = 425;

	private static final String PATH = "/resumed";

	/** Enough to make "100 % of requests succeed" (SC-007) a statement rather than an anecdote. */
	private static final int EXCHANGES = 8;

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;
	private @Nullable Http3TestServer server;

	/** The paths the handler was invoked for — the "servlet observes each request exactly once" ledger. */
	private final List<String> served = new ArrayList<>();

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

	// ---------------------------------------------------------------- T087: rejection by omitted extension

	/**
	 * T087, FR-048 + FR-055: a server that resumes the session and refuses the early data with it leaves
	 * the caller with one response, correct, delivered over a request re-created in 1-RTT — and leaves
	 * the server having seen that request exactly once, on a stream that is not the one the early data
	 * went out on.
	 */
	@Test
	public void whenTheServerOmitsTheEarlyDataExtensionTheRequestIsRemadeOnAFreshStreamInOneRtt() {
		start(false, echoHandler());

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it", client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/second");
		assertEquals("the pool's one entry was evicted, so the next request to HOST dials again",
			1, client().connectionsEvicted());

		startRecording();
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		// (3) one response, and the right one
		assertEquals(200, response.getCode());
		assertBody(PATH, response);

		// FR-048: the ticket was offered and taken; refusing the early data is not a handshake failure
		assertEquals(1, client().sessionTicketsOffered());
		assertEquals(1, client().zeroRttAttempted());
		assertEquals(0, client().zeroRttAccepted());
		assertEquals(1, client().zeroRttRejected());
		assertEquals("the PSK resumed the session", 1, server.sessionsResumed());
		assertEquals("and its early data was refused", 0, server.zeroRttAccepted());
		assertEquals("one dial, not a re-dial after the refusal", 1, server.connectionsAccepted());

		// (1) + (2) the 0-RTT stream is gone rather than retransmitted, and the work was re-created
		assertEquals(List.of(SECOND_REQUEST_STREAM), server.requestStreamIds());
		// (1) again, from the wire side: the discarded stream was never announced to a peer that never saw
		// it. Stream 0 exists at the server only because RFC 9000 §2.1 opened it implicitly under stream 4,
		// and it carried nothing.
		assertEquals(List.of(FIRST_REQUEST_STREAM, SECOND_REQUEST_STREAM), server.streamsOpened());
		assertEquals(List.of(), server.abortedStreamIds());
		// (4) exactly once
		assertEquals(List.of(PATH), served);
		assertEquals(0, client().requestsFailed());
		// (6) the fallback is observable rather than invisible
		assertEquals(1, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- T088: rejection by 425 (Too Early)

	/**
	 * T088, FR-067's second rejection signal: a request answered {@code 425 (Too Early)} because it
	 * arrived in early data is retried once, after the handshake, and the caller sees only the final
	 * response.
	 * <p>
	 * <b>What this proves and what it does not.</b> This is a test of the <b>client's reaction</b> to a
	 * 425, not of a server's decision to send one: the RFC 8470 early-data policy that makes that
	 * decision — {@code Http3EarlyDataPolicy}, the {@code Early-Data: 1} indication and the replay
	 * register — is US5's (Phase 7) and does not exist yet. The handler below stands in for it, answering
	 * 425 to a request whose HEADERS were decoded before the handshake completed, which on a connection
	 * that accepted early data is exactly the 0-RTT case. When Phase 7 lands, the same client behaviour
	 * has to hold against the real policy; nothing here needs to change for that.
	 */
	@Test
	public void aRequestAnsweredTooEarlyIsRetriedOnceAndOnlyTheFinalResponseReachesTheCaller() {
		start(true, (request, context) -> {
			if (context.earlyData()) return HttpResponse.ofCode(TOO_EARLY).toPromise();
			return echo(request.getPath());
		});

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");

		startRecording();
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		assertEquals("the caller saw the retry's response, not the refusal", 200, response.getCode());
		assertBody(PATH, response);
		assertEquals("the server took the early data — it was the request that was refused",
			1, client().zeroRttAccepted());
		assertEquals(0, client().zeroRttRejected());
		// The early-data request rode stream 0 and was answered 425; the retry rode a fresh stream.
		assertEquals(List.of(FIRST_REQUEST_STREAM, SECOND_REQUEST_STREAM), server.requestStreamIds());
		assertEquals("the request was served once — the 425 answered without serving it", List.of(PATH), served);
		assertEquals(0, client().requestsFailed());
		assertEquals(1, client().earlyDataRetried());
	}

	/**
	 * The other half of T088, and the bug a naive reading of FR-067 produces: {@code 425} is retried
	 * <b>because the request was sent in early data</b>, not because of its status code. A 425 answering
	 * a request that carried no early data is an ordinary response and must reach the caller untouched —
	 * retrying it would loop against a server that answers 425 unconditionally.
	 */
	@Test
	public void aTooEarlyAnswerToARequestThatCarriedNoEarlyDataIsNotRetried() {
		start(true, (request, context) -> {
			served.add(request.getPath());
			return HttpResponse.ofCode(TOO_EARLY).toPromise();
		});

		// A first dial has no ticket to offer, so nothing about this request went out early.
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, PATH)).build()));

		assertEquals(TOO_EARLY, response.getCode());
		assertEquals(0, client().zeroRttAttempted());
		assertEquals(List.of(FIRST_REQUEST_STREAM), server.requestStreamIds());
		assertEquals(List.of(PATH), served);
		assertEquals("a 425 is retried because the request went out early, never for its status code alone",
			0, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- T089: at most once

	/**
	 * T089, FR-067's "at most once": the retry is the last word. A retry that itself fails fails the
	 * caller's promise once, carrying its own failure, and nothing is re-issued a second time.
	 * <p>
	 * The refusal the server answers with is deliberately {@code H3_REQUEST_REJECTED} — the one code
	 * {@link Http3Exception#isRetryable()} calls retryable, so the transport is offering exactly the
	 * retry opportunity this rule says to decline.
	 */
	@Test
	public void aRetryThatItselfFailsFailsThePromiseOnceWithItsOwnFailure() {
		start(false, (request, context) -> PATH.equals(request.getPath()) ?
			Promise.ofException(new Http3Exception(Http3Errors.H3_REQUEST_REJECTED, "The test server refuses this one")) :
			echo(request.getPath()));

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");

		startRecording();
		Promise<HttpResponse> refused = client().request(HttpRequest.get(url(HOST, PATH)).build());
		int[] completions = {0};
		refused.whenComplete(($, e) -> completions[0]++);
		Exception e = fixture.awaitException(refused);

		assertEquals("the caller's promise completed more than once", 1, completions[0]);
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, applicationErrorCodeOf(e));
		assertEquals(1, client().zeroRttRejected());
		assertEquals(1, client().requestsFailed());
		assertTrue("the request was refused, never served", served.isEmpty());

		// Nothing was re-issued behind the failure: one request reached the server, and driving the wire on
		// gives a second retry every chance to appear.
		assertEquals(1, server.requestStreamIds().size());
		fixture.wire().advance(100);
		assertEquals(1, server.requestStreamIds().size());

		// ... and the one that reached it was the retry, on a stream of its own.
		assertEquals("the failure the caller saw is not the retry's own", SECOND_REQUEST_STREAM, streamIdOf(e));
		assertEquals(List.of(SECOND_REQUEST_STREAM), server.requestStreamIds());
		assertEquals("a retry that fails is still a retry that was issued", 1, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- T090: exactly once (SC-007)

	/**
	 * T090 / SC-007: with rejection unconditional, every request succeeds and the server sees each
	 * exactly once. The alternating authority is what forces a fresh dial per exchange at
	 * {@code maxConnections = 1}, and so a fresh rejection per exchange.
	 */
	@Test
	public void underUnconditionalRejectionEveryRequestSucceedsAndIsServedExactlyOnce() {
		start(false, echoHandler());

		List<String> paths = new ArrayList<>();
		List<Long> expectedStreams = new ArrayList<>();
		for (int i = 0; i < EXCHANGES; i++) {
			String path = "/request-" + i;
			paths.add(path);
			// The first dial to each authority has no ticket to offer, so those two exchanges carry no
			// early data and are served on the connection's first request stream.
			expectedStreams.add(i < 2 ? FIRST_REQUEST_STREAM : SECOND_REQUEST_STREAM);
			exchange(i % 2 == 0 ? HOST : OTHER_HOST, path);
		}

		assertEquals("every request was served, exactly once, in order", paths, served);
		assertEquals(expectedStreams, server.requestStreamIds());
		assertEquals(EXCHANGES - 2, client().zeroRttAttempted());
		assertEquals(client().zeroRttAttempted(), client().zeroRttRejected());
		assertEquals(0, client().zeroRttAccepted());
		assertEquals(0, client().requestsFailed());
		assertEquals("a refused early-data attempt is not a failed handshake",
			EXCHANGES - 2, server.sessionsResumed());
		assertEquals(0, server.zeroRttAccepted());
		assertEquals("one retry per rejected exchange, and no more", EXCHANGES - 2, client().earlyDataRetried());
	}

	// ---------------------------------------------------------------- harness

	private void start(boolean earlyDataEnabled, Http3TestServer.Handler handler) {
		fixture = new Http3ClientFixture(loop)
			.withServerFactory(socket -> server = new Http3TestServer(socket)
				.withEarlyDataEnabled(earlyDataEnabled)
				.withHandler(handler)
				.start())
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(cache)
			.start();
	}

	/** Forgets what the exchanges leading up to the resumption did, so the assertions speak about it alone. */
	private void startRecording() {
		server.startRecording();
		served.clear();
	}

	private Http3TestServer.Handler echoHandler() {
		return (request, context) -> echo(request.getPath());
	}

	private Promise<HttpResponse> echo(String path) {
		served.add(path);
		return HttpResponse.ok200().withBody(path.getBytes(UTF_8)).toPromise();
	}

	/** One complete exchange, leaving the connection to {@code host} idle and pooled. */
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

	/** The stream the failure the caller saw came from — which is how "the retry's own failure" is checked. */
	private static long streamIdOf(Exception e) {
		if (e instanceof QuicStreamResetException reset) return reset.streamId();
		if (e instanceof QuicStreamStopSendingException stopped) return stopped.streamId();
		throw new AssertionError("the caller was failed with something other than a stream failure: " + e, e);
	}

	private static long applicationErrorCodeOf(Exception e) {
		if (e instanceof QuicStreamResetException reset) return reset.applicationErrorCode();
		if (e instanceof QuicStreamStopSendingException stopped) return stopped.applicationErrorCode();
		throw new AssertionError("the caller was failed with something other than a stream failure: " + e, e);
	}
}
