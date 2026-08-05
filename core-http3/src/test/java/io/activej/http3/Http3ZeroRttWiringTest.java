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
import io.activej.http3.frame.SettingsFrame;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T082 / T084, FR-058 and FR-062: the client stores a session ticket <b>with the server's HTTP/3
 * SETTINGS attached</b> and offers it on the next dial to the same origin, and none of that happens
 * while {@code zeroRttEnabled} is off.
 * <p>
 * This is the wiring, not the 0-RTT exchange: whether early data actually crossed the wire, whether it
 * was accepted, and what happens when it is rejected are US4/US5's (Phase 6, Phase 7) and the
 * capstone's. What is asserted here is that the pieces this phase added meet — the ticket store, the
 * remembered SETTINGS blob, and the {@link io.activej.quic.tls.TlsClientConfig} the client builds.
 * <p>
 * Eviction is what makes a <b>second dial to the same origin</b> reachable with one server: at
 * {@code maxConnections=1} a request to the other authority evicts the first connection, so the third
 * exchange has to dial {@link Http3ClientFixture#HOST} again — and the ticket it stored is keyed by
 * that origin.
 */
public final class Http3ZeroRttWiringTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final Http3Settings ZERO_RTT_OFF = Http3Settings.builder()
		.withMaxConnections(1)
		.build();

	private ManualEventloop loop;
	private RecordingCache cache;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		cache = new RecordingCache(InMemoryQuicSessionCache.create(8, loop::currentTimeMillis));
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void aTicketIsStoredCarryingTheServersSettingsAndOfferedOnTheNextDialToThatOrigin() {
		start(ZERO_RTT_ON, ZERO_RTT_ON);

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it",
			client().sessionTicketsStored() >= 1);
		assertEquals(0, client().sessionTicketsOffered());

		QuicSessionTicket stored = cache.lastPut;
		assertNotNull(stored);
		assertEquals(HOST, stored.serverName());
		assertEquals("h3", stored.alpn());

		// FR-062: the ticket and the SETTINGS that go with it are one unit.
		SettingsFrame remembered =
			Http3RememberedSettings.of(stored, ZERO_RTT_ON.maxControlFrameSize());
		assertNotNull("the stored ticket carries the server's SETTINGS", remembered);
		assertEquals(ZERO_RTT_ON.maxFieldSectionSize(),
			Http3RememberedSettings.valueOf(remembered, SettingsFrame.MAX_FIELD_SECTION_SIZE, -1));

		// The pool is full and its one entry is idle, so this evicts the connection to HOST.
		exchange(OTHER_HOST, "/second");
		assertEquals(1, client().connectionsEvicted());

		// ... which makes the third exchange dial HOST again, offering what the first one stored.
		exchange(HOST, "/third");
		assertEquals("the stored ticket was offered on the redial", 1, client().sessionTicketsOffered());
	}

	@Test
	public void withZeroRttOffNoTicketIsEverStoredOrOffered() {
		start(ZERO_RTT_OFF, ZERO_RTT_OFF);

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/third");

		assertEquals(0, client().sessionTicketsStored());
		assertEquals(0, client().sessionTicketsOffered());
		assertNull("the store was never touched", cache.lastPut);
		assertEquals(0, cache.puts);
	}

	@Test
	public void aClientWithZeroRttOnStoresNothingFromAServerWithItOff() {
		start(ZERO_RTT_OFF, ZERO_RTT_ON);

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/third");

		assertEquals("no ticket keys means no NewSessionTicket at all", 0, client().sessionTicketsStored());
		assertEquals(0, client().sessionTicketsOffered());
	}

	// ---------------------------------------------------------------- harness

	private void start(Http3Settings serverSettings, Http3Settings clientSettings) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200()
				.withBody(request.getPath().substring(1).getBytes(UTF_8))
				.toPromise())
			.withServerSettings(serverSettings)
			.withClientSettings(clientSettings)
			.withSessionCache(cache)
			.start();
	}

	/** One complete exchange, leaving the connection to {@code host} idle and pooled. */
	private void exchange(String host, String path) {
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(host, path)).build()));
		ByteBuf body = fixture.await(response.loadBody());
		try {
			assertEquals(path.substring(1), body.getString(UTF_8));
		} finally {
			body.recycle();
		}
	}

	private Http3Client client() {
		return fixture.client();
	}

	/**
	 * The consumer-supplied store of FR-059, wrapped so the test can see the ticket that went in without
	 * {@link QuicSessionCache#take} removing it.
	 */
	private static final class RecordingCache implements QuicSessionCache {
		private final QuicSessionCache delegate;

		private @Nullable QuicSessionTicket lastPut;
		private int puts;

		RecordingCache(QuicSessionCache delegate) {
			this.delegate = delegate;
		}

		@Override
		public @Nullable QuicSessionTicket take(String serverName, int port, String alpn) {
			return delegate.take(serverName, port, alpn);
		}

		@Override
		public void put(String serverName, int port, String alpn, QuicSessionTicket ticket) {
			lastPut = ticket;
			puts++;
			delegate.put(serverName, port, alpn, ticket);
		}
	}
}
