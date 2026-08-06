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
import io.activej.http3.Http3Connection.EarlyDataRefusal;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.tls.InMemoryQuicSessionCache;
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
 * T112, spec <b>FR-064</b>, <b>FR-069</b>, <b>FR-070</b> — "why did 0-RTT get refused" as an
 * operational signal rather than a silent branch.
 *
 * <p>Early data is refused in two entirely different places, and folding them into one number would
 * lose the only thing an operator wants from it. The replay register refuses a <i>resumption
 * attempt's</i> early data before a single HTTP byte exists; the early-data policy refuses a
 * <i>request</i> that already decoded. One is a defence firing, the other is ordinary traffic meeting
 * the safe-method rule, and a deployment reacts to them differently — so each reason carries its own
 * counter and its own {@link Http3Connection.EarlyDataRefusal} constant.
 *
 * <p>Every counter is asserted through both surfaces the {@link Http3Server.Inspector} contract
 * promises: the accessor and the callback, which must agree, and the accessor must read the same
 * value with no inspector attached at all.
 */
public final class Http3EarlyDataRefusalCountersTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** One pooled connection, so a detour to {@link Http3ClientFixture#OTHER_HOST} forces the redial that resumes. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final Http3Settings ZERO_RTT_OFF = Http3Settings.builder()
		.withMaxConnections(1)
		.build();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	private final List<String> served = new ArrayList<>();
	private final RecordingInspector inspector = new RecordingInspector();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		served.clear();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		fixture = null;
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- FR-069: the register

	/**
	 * A ticket presented for early data a second time is refused by the register, and that refusal is
	 * reported as {@link EarlyDataRefusal#REPLAYED} — never as a capacity or expiry refusal, which mean
	 * something else entirely.
	 */
	@Test
	public void aReplayedTicketIsCountedAndReportedAsAReplay() {
		start(ZERO_RTT_ON, new RepeatingCache());

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it", client().sessionTicketsStored() >= 1);

		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/granted");
		assertEquals("a first presentation is granted, so nothing is refused yet", 0, server().zeroRttRefusedAsReplay());
		assertEquals(List.of(), inspector.refusals);

		exchange(OTHER_HOST, "/third");
		exchange(HOST, "/replayed");

		assertEquals(1, server().zeroRttRefusedAsReplay());
		assertEquals(List.of(refusal(EarlyDataRefusal.REPLAYED, 1)), inspector.refusals);

		// the register's other two reasons are not the same event and must not be conflated with it
		assertEquals(0, server().zeroRttRefusedAtCapacity());
		assertEquals(0, server().zeroRttRefusedAsExpired());
		assertEquals("nothing was refused by the policy — every request here is a GET",
			0, server().earlyDataRequestsRefused());

		// and the refusal costs the early data only
		assertEquals(2, server().sessionsResumed());
		assertEquals(1, server().zeroRttAccepted());
		assertEquals(List.of("/first", "/second", "/granted", "/third", "/replayed"), served);
	}

	// ---------------------------------------------------------------- FR-064: the policy

	/**
	 * An unsafe method the consumer opted into early data is refused by the policy, counted separately
	 * from the register's refusals, and reported as {@link EarlyDataRefusal#POLICY} — on a connection
	 * whose early data the register <i>did</i> grant, which is what makes the two distinguishable.
	 */
	@Test
	public void aPolicyRefusalIsCountedAndReportedSeparatelyFromTheRegister() {
		start(ZERO_RTT_ON, InMemoryQuicSessionCache.create(8, loop::currentTimeMillis));
		resume();

		HttpResponse response = fixture.await(client().request(
			Http3EarlyData.allow(HttpRequest.post(url(HOST, "/resumed")).build())));
		assertEquals("the caller sees only the final outcome (FR-067)", 200, response.getCode());
		assertBody("/resumed", response);

		assertEquals(1, server().earlyDataRequestsRefused());
		assertEquals(List.of(refusal(EarlyDataRefusal.POLICY, 1)), inspector.refusals);

		assertEquals("the register granted this connection its early data", 1, server().zeroRttAccepted());
		assertEquals(0, server().zeroRttRefusedAsReplay());
		assertEquals(0, server().zeroRttRefusedAtCapacity());
		assertEquals(0, server().zeroRttRefusedAsExpired());
	}

	/** A request the policy accepts refuses nothing, so no counter moves and no event fires. */
	@Test
	public void anAcceptedEarlyDataRequestRefusesNothing() {
		start(ZERO_RTT_ON, InMemoryQuicSessionCache.create(8, loop::currentTimeMillis));
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/resumed")).build()));
		assertEquals(200, response.getCode());
		assertBody("/resumed", response);

		assertEquals(1, server().zeroRttAccepted());
		assertEquals(0, server().earlyDataRequestsRefused());
		assertEquals(0, server().zeroRttRefusedAsReplay());
		assertEquals(List.of(), inspector.refusals);
	}

	// ---------------------------------------------------------------- SC-011: silent with 0-RTT off

	/**
	 * With {@link Http3Settings#zeroRttEnabled()} off there is no register and no early data at all, so
	 * every one of the four counters reads 0 for the life of the server and the callback never fires —
	 * the counters cost a default deployment nothing.
	 */
	@Test
	public void aServerWithZeroRttOffRefusesNothingAndReportsNothing() {
		start(ZERO_RTT_OFF, new RepeatingCache());

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/third");

		assertEquals(0, server().sessionsResumed());
		assertEquals(0, server().zeroRttRefusedAsReplay());
		assertEquals(0, server().zeroRttRefusedAtCapacity());
		assertEquals(0, server().zeroRttRefusedAsExpired());
		assertEquals(0, server().earlyDataRequestsRefused());
		assertEquals(List.of(), inspector.refusals);
	}

	// ---------------------------------------------------------------- the inspector never gates a counter

	/**
	 * The {@link Http3Server.Inspector} contract's standing promise, restated for these four: an accessor
	 * reads the same value with no inspector attached as with one.
	 */
	@Test
	public void theCountersReadTheSameWithNoInspectorAttached() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(this::echo)
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(new RepeatingCache())
			.start();

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/granted");
		exchange(OTHER_HOST, "/third");
		exchange(HOST, "/replayed");

		assertEquals(1, server().zeroRttRefusedAsReplay());
		assertEquals(1, server().zeroRttAccepted());
	}

	// ---------------------------------------------------------------- harness

	private void start(Http3Settings settings, QuicSessionCache cache) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(this::echo)
			.withServerSettings(settings)
			.withClientSettings(settings)
			.withServerInspector(inspector)
			.withSessionCache(cache)
			.start();
	}

	private Promise<HttpResponse> echo(HttpRequest request) {
		String path = request.getPath();
		served.add(path);
		return HttpResponse.ok200().withBody(path.getBytes(UTF_8)).toPromise();
	}

	/** Earns a ticket and forces the pooled connection out, so the next request to {@link #HOST} resumes. */
	private void resume() {
		exchange(HOST, "/first");
		assertTrue("the server issued no ticket, so nothing here resumes", client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/second");
		served.clear();
		inspector.refusals.clear();
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

	private static String refusal(EarlyDataRefusal reason, long refusals) {
		return reason + "@" + refusals;
	}

	private Http3Client client() {
		return fixture.client();
	}

	private Http3Server server() {
		return fixture.server();
	}

	/** Which refusals were reported, in order, each with the running total that came with it. */
	private static final class RecordingInspector implements Http3Server.Inspector {
		private final List<String> refusals = new ArrayList<>();

		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onEarlyDataRefused(Http3Server server, EarlyDataRefusal reason, long refusals) {
			this.refusals.add(refusal(reason, refusals));
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {}
	}

	/**
	 * A misbehaving store: it keeps the first ticket {@link Http3ClientFixture#HOST} ever issued and
	 * answers every later {@code take} for that origin with the same one, which is what a replayed flight
	 * looks like to a server — an identity it has already granted early data to. It holds nothing for any
	 * other origin, so a dial to {@link Http3ClientFixture#OTHER_HOST} is always a full handshake.
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
}
