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
import io.activej.quic.tls.EncryptionLevel;
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
 * T104, spec <b>FR-064a</b> — an HTTP/3 request stream reports the {@link EncryptionLevel} its
 * <b>HEADERS</b> arrived at: {@code ZERO_RTT} for a request carried in early data, {@code ONE_RTT} for
 * an ordinary one.
 *
 * <h2>Why this is asserted at the HTTP/3 layer and not only at the transport</h2>
 * {@code StreamArrivalLevelTest} proves the transport carries the level forward. What the server-side
 * early-data policy (FR-064) will read, though, is one message's <em>head</em>, and a stream outlives
 * its HEADERS: body DATA keeps arriving afterwards, at a level of its own. The level is therefore
 * latched by {@link Http3RequestStream} at the moment the leading HEADERS frame is in hand, and this
 * test pins that down — without it, a policy could be handed the level of the last body chunk instead.
 *
 * <h2>The connection is not the answer either</h2>
 * A resumed connection offers early data whatever the request turns out to be, so "this connection
 * accepted 0-RTT" says nothing about any particular exchange: an unsafe method is held back by the
 * client's own policy (FR-068) and arrives at 1-RTT on exactly such a connection. That case is
 * asserted here too, because it is the one a per-connection flag would get wrong.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1 — 0-RTT</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8470">RFC 8470 — Using Early Data in HTTP</a>
 */
public final class Http3RequestArrivalLevelTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** One connection at a time, so the second request to an authority is a resumption. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;
	private @Nullable Http3TestServer server;

	private final List<EncryptionLevel> arrivalLevels = new ArrayList<>();

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

	@Test
	public void aRequestCarriedInEarlyDataReportsZeroRttArrival() {
		start();

		exchange(HOST, "/first");
		assertEquals("a request on a fresh connection cannot have arrived in early data",
			List.of(EncryptionLevel.ONE_RTT), arrivalLevels);

		resume();
		exchange(HOST, "/resumed");

		assertEquals("FR-064a: HEADERS carried in a 0-RTT packet must report ZERO_RTT",
			List.of(EncryptionLevel.ZERO_RTT), arrivalLevels);
		assertEquals(1, server.zeroRttAccepted());
	}

	/**
	 * The same connection, a request the client's own policy holds back (FR-068): the connection
	 * accepted early data, and this exchange still arrived at 1-RTT.
	 */
	@Test
	public void aRequestHeldBackOnAResumedConnectionReportsOneRttArrival() {
		start();
		exchange(HOST, "/first");
		resume();

		HttpResponse response = fixture.await(client().request(HttpRequest.post(url(HOST, "/held")).build()));
		assertEquals(200, response.getCode());
		assertBody("/held", response);

		assertEquals("a per-connection flag would have answered ZERO_RTT here",
			List.of(EncryptionLevel.ONE_RTT), arrivalLevels);
		assertEquals("the connection did resume and did accept early data", 1, server.zeroRttAccepted());
	}

	// ---------------------------------------------------------------- harness

	private void start() {
		fixture = new Http3ClientFixture(loop)
			.withServerFactory(socket -> server = new Http3TestServer(socket)
				.withEarlyDataEnabled(true)
				.withHandler((request, context) -> {
					arrivalLevels.add(context.arrivalLevel());
					return HttpResponse.ok200().withBody(request.getPath().getBytes(UTF_8)).toPromise();
				})
				.start())
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(cache)
			.start();
	}

	/** Forces the pooled connection out, so the next request to {@link Http3ClientFixture#HOST} resumes. */
	private void resume() {
		assertTrue("the server issued no ticket, so nothing here resumes", client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/other");
		assertEquals(1, client().connectionsEvicted());
		server.startRecording();
		arrivalLevels.clear();
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
