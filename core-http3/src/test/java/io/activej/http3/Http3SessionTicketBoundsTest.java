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
import io.activej.common.MemSize;
import io.activej.common.initializer.Initializer;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

/**
 * T156 — the FR-043a bounds on post-handshake {@code NewSessionTicket} input are the ones configured
 * through {@link Http3Client}, not {@link TlsClientConfig}'s own defaults.
 * <p>
 * The defect this class exists for was invisible by construction: {@code TlsClientConfig}'s hardcoded
 * 8 kB / 8 coincide numerically with the transport's, so asserting the default proves nothing about
 * whether anything is wired at all. Every test here therefore names a <b>non-default</b> bound and
 * asserts the enforced behaviour moves with it, against a real {@link Http3Server} issuing real tickets
 * over a real TLS 1.3 handshake — and the count bound is pinned from both sides, at the value the server
 * needs and one below it, so the boundary is shown to sit exactly where it was configured.
 * <p>
 * Bounds are lowered rather than raised because only a lowered one is observable: a sealed ticket from
 * this server is a few hundred bytes, so raising the size bound to 16 kB admits precisely what 8 kB
 * already admitted.
 * <p>
 * Two behaviours, both specified: a bound of {@code 0} <i>discards</i> (nothing was promised, so nothing
 * is violated), while a ticket past a non-zero bound is a connection error — "closed rather than
 * buffered" — which takes the exchange under way down with it.
 * <p>
 * The other half of the wiring — that the value {@link Http3Client} hands on is the transport's
 * {@code ApplicationSettings}-backed setting rather than a literal — cannot be observed in this JVM,
 * where those are {@code public static final} fields resolved at class-init (DI-5). It has a test of its
 * own, in the shape this repository already uses for that: {@link Http3SessionTicketBoundsOverrideTest}.
 */
public final class Http3SessionTicketBoundsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Nothing here resumes anything; 0-RTT is on because it is what makes the server issue tickets at all. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.build();

	/** What the server issues per handshake, and so what a client with the default bound of 8 stores. */
	private static final int TICKETS_THE_SERVER_ISSUES = 2;

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		fixture = null;
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void withNoBoundNamedTheClientStoresEveryTicketTheServerIssues() {
		start(null);
		exchange();

		assertEquals(TICKETS_THE_SERVER_ISSUES, fixture.client().sessionTicketsStored());
		assertEquals(0, fixture.client().requestsFailed());
	}

	@Test
	public void aCountBoundAtWhatTheServerIssuesAdmitsThemAll() {
		start(config -> config.withMaxSessionTicketsPerConnection(TICKETS_THE_SERVER_ISSUES));
		exchange();

		assertEquals(TICKETS_THE_SERVER_ISSUES, fixture.client().sessionTicketsStored());
		assertEquals(0, fixture.client().requestsFailed());
	}

	@Test
	public void oneBelowThatRefusesTheLastTicketAndCloses() {
		start(config -> config.withMaxSessionTicketsPerConnection(TICKETS_THE_SERVER_ISSUES - 1));
		failingExchange();

		// Both tickets arrive in one flight, so the refusal lands before the peer's SETTINGS have been
		// read — and a ticket is only stored once they have (FR-062). Nothing is stored, and the
		// connection the exchange was running on is gone.
		assertEquals(0, fixture.client().sessionTicketsStored());
		assertEquals(1, fixture.client().requestsFailed());
	}

	@Test
	public void aZeroCountBoundAcceptsNoTicketAtAll() {
		start(config -> config.withMaxSessionTicketsPerConnection(0));
		exchange();

		assertEquals(0, fixture.client().sessionTicketsStored());
		assertEquals("a bound of 0 discards rather than refuses", 0, fixture.client().requestsFailed());
	}

	@Test
	public void aSizeBoundOverARealTicketAdmitsIt() {
		start(config -> config.withMaxSessionTicketSize(MemSize.kilobytes(1)));
		exchange();

		assertEquals(TICKETS_THE_SERVER_ISSUES, fixture.client().sessionTicketsStored());
		assertEquals(0, fixture.client().requestsFailed());
	}

	@Test
	public void aSizeBoundUnderARealTicketRefusesItAndCloses() {
		start(config -> config.withMaxSessionTicketSize(MemSize.bytes(32)));
		failingExchange();

		assertEquals("a sealed ticket is far over 32 bytes", 0, fixture.client().sessionTicketsStored());
		assertEquals(1, fixture.client().requestsFailed());
	}

	// ---------------------------------------------------------------- harness

	private void start(@Nullable Initializer<TlsClientConfig.Builder> tlsClientConfig) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200().withBody("hello".getBytes(UTF_8)).toPromise())
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON);
		if (tlsClientConfig != null) fixture.withTlsClientConfig(tlsClientConfig);
		fixture.start();
	}

	/**
	 * One request, then a step of the wire past it: a {@code NewSessionTicket} is not part of the
	 * exchange and may be answered after the response has been read.
	 */
	private void exchange() {
		HttpResponse response = fixture.await(fixture.client().request(HttpRequest.get(url(HOST, "/")).build()));
		ByteBuf body = fixture.await(response.loadBody());
		body.recycle();
		fixture.wire().advance(1);
	}

	/** The same request against a bound the server's tickets break, which the exchange does not survive. */
	private void failingExchange() {
		fixture.awaitException(fixture.client().request(HttpRequest.get(url(HOST, "/")).build())
			.then(response -> response.loadBody())
			.whenResult(ByteBuf::recycle));
		fixture.wire().advance(1);
	}
}
