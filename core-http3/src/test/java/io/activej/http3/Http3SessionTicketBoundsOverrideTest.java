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
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.connection.QuicConnectionSettings;
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
import static org.junit.Assume.assumeTrue;

/**
 * T156, the half {@link Http3SessionTicketBoundsTest} cannot reach: the FR-043a bounds
 * {@link Http3Client} hands to its {@code TlsClientConfig} are the transport's
 * {@code ApplicationSettings}-backed settings, so lowering one on the command line lowers what the
 * client's TLS engine enforces — with no consumer configuration anywhere in the picture.
 * <p>
 * {@link QuicConnectionSettings}' defaults are {@code public static final} fields resolved once at
 * class-init (DI-5), so the only way to observe an override is a fresh JVM where the property is
 * already set when that class first loads. Under the headline run neither property is set and both
 * tests here are skipped ({@code Assume}) rather than failed — the shape
 * {@code QuicApplicationSettingsOverrideTest} established. Run this class alone to exercise it:
 * <pre>
 * mvn -pl core-http3 -am test -Dtest=Http3SessionTicketBoundsOverrideTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DargLine='-DQuicConnection.maxSessionTicketsPerConnection=1 -DQuicConnection.maxSessionTicketSize=32b'
 * </pre>
 * Each test asserts against the value it reads back from {@link QuicConnectionSettings} rather than
 * against the literal on that command line, so it proves the property reached the enforcement point
 * without duplicating {@code ApplicationSettings}' parsing rules.
 */
public final class Http3SessionTicketBoundsOverrideTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.build();

	/** What the server issues per handshake; a bound below it is what a refusal needs. */
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
	public void aLoweredTransportTicketCountBoundReachesTheClientsTlsEngine() {
		assumeTrue("run with -DQuicConnection.maxSessionTicketsPerConnection=1 to exercise this (T156)",
			transportSettings().maxSessionTicketsPerConnection() < TICKETS_THE_SERVER_ISSUES);

		start();
		failingExchange();

		assertEquals("the transport's ticket-count bound did not reach the client's TLS engine",
			0, fixture.client().sessionTicketsStored());
		assertEquals(1, fixture.client().requestsFailed());
	}

	@Test
	public void aLoweredTransportTicketSizeBoundReachesTheClientsTlsEngine() {
		assumeTrue("run with -DQuicConnection.maxSessionTicketSize=32b to exercise this (T156)",
			transportSettings().maxSessionTicketSize() <= 32);

		start();
		failingExchange();

		assertEquals("the transport's ticket-size bound did not reach the client's TLS engine",
			0, fixture.client().sessionTicketsStored());
		assertEquals(1, fixture.client().requestsFailed());
	}

	// ---------------------------------------------------------------- harness

	/** The very settings {@code Http3Client.quicSettings()} builds from, read through the same public API. */
	private static QuicConnectionSettings transportSettings() {
		return QuicConnectionSettings.builder().build();
	}

	private void start() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> HttpResponse.ok200().withBody("hello".getBytes(UTF_8)).toPromise())
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON)
			.start();
	}

	private void failingExchange() {
		fixture.awaitException(fixture.client().request(HttpRequest.get(url(HOST, "/")).build())
			.then(response -> response.loadBody())
			.whenResult(ByteBuf::recycle));
		fixture.wire().advance(1);
	}
}
