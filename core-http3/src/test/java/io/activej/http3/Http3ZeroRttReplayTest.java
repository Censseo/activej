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
 * T107 at the HTTP/3 layer: a production {@link Http3Server} with 0-RTT on <b>supplies a replay
 * register</b>, so a ticket presented for early data a second time buys 1-RTT and nothing more.
 * {@code TlsServerEarlyDataReplayTest} proves the engine consults a register it is handed; this proves
 * a server hands it one, which is the half a consumer actually depends on.
 *
 * <h2>How the replay is staged</h2>
 * A {@link RepeatingCache} that stores the first ticket it is given and then answers every
 * {@code take} with it. That is exactly the client-side rule {@link QuicSessionCache} documents being
 * broken — "{@code take} removes the ticket it returns … re-offering one is what a replay looks like"
 * — which is the point: the server may not rely on a peer honouring it. A real attacker replays a
 * recorded flight rather than a well-behaved client's next dial; the server sees the same thing either
 * way, an identity it has already granted early data to.
 * <p>
 * Everything else here is production code: a real {@link Http3Server}, a real {@link Http3Client},
 * real TLS 1.3 over a real QUIC connection.
 */
public final class Http3ZeroRttReplayTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** One pooled connection, so a detour to {@link #OTHER_HOST} forces the redial that offers the ticket. */
	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private ManualEventloop loop;
	private RepeatingCache cache;
	private @Nullable Http3ClientFixture fixture;

	private final List<String> served = new ArrayList<>();

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
	public void theSameTicketBuysEarlyDataOnceAndTheReplayFallsBackToOneRtt() {
		start();

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it", client().sessionTicketsStored() >= 1);

		exchange(OTHER_HOST, "/second");
		exchange(HOST, "/granted");
		assertEquals("the stored ticket was offered on the redial", 1, client().zeroRttAttempted());
		assertEquals("and a first presentation must be granted", 1, client().zeroRttAccepted());
		assertEquals(0, client().zeroRttRejected());
		assertEquals(1, server().zeroRttAccepted());

		exchange(OTHER_HOST, "/third");
		exchange(HOST, "/replayed");

		assertEquals("the very same ticket was offered again", 2, client().zeroRttAttempted());
		assertEquals("... and must not have bought early data a second time", 1, client().zeroRttAccepted());
		assertEquals(1, client().zeroRttRejected());
		assertEquals(1, server().zeroRttAccepted());

		// The refusal costs the early data and nothing else: the session still resumed, and every
		// request was served exactly once, in order, with the right body.
		assertEquals(2, server().sessionsResumed());
		assertEquals(List.of("/first", "/second", "/granted", "/third", "/replayed"), served);
	}

	// ---------------------------------------------------------------- harness

	private void start() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> {
				String path = request.getPath();
				served.add(path);
				return HttpResponse.ok200().withBody(path.getBytes(UTF_8)).toPromise();
			})
			.withServerSettings(ZERO_RTT_ON)
			.withClientSettings(ZERO_RTT_ON)
			.withSessionCache(cache)
			.start();
	}

	private void exchange(String host, String path) {
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(host, path)).build()));
		ByteBuf body = fixture.await(response.loadBody());
		try {
			assertEquals(path, body.getString(UTF_8));
		} finally {
			body.recycle();
		}
	}

	private Http3Client client() {
		return fixture.client();
	}

	private Http3Server server() {
		return fixture.server();
	}

	/**
	 * A misbehaving store: it keeps the first ticket {@link Http3ClientFixture#HOST} ever issued and
	 * answers every later {@code take} for that origin with the same one, ignoring the single-use rule
	 * and every later ticket. It holds nothing for any other origin, so a dial to
	 * {@link Http3ClientFixture#OTHER_HOST} is always a full handshake and never contributes a 0-RTT
	 * attempt of its own. Nothing else about the client changes.
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
