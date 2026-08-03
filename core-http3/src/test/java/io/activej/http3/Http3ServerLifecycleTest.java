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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T069 / FR-021: {@code close()} is idempotent, a second call does nothing, and nothing completes
 * twice on the way out.
 * <p>
 * "Nothing completes twice" is asserted directly rather than inferred: every promise the test holds is
 * given a completion counter, and a second {@code close()} must not move any of them.
 */
public final class Http3ServerLifecycleTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3TestPeer peer;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void closeIsIdempotent() {
		start(request -> HttpResponse.ok200().toPromise());
		assertFalse(server.isClosed());

		server.close();
		assertTrue(server.isClosed());

		server.close();
		server.close();
		assertTrue("a second and third close are no-ops", server.isClosed());
	}

	@Test
	public void listenAfterCloseDoesNotReopenTheServer() {
		start(request -> HttpResponse.ok200().toPromise());
		server.close();

		Promise<Void> listened = server.listen();
		loop.tickUntilQuiet();

		assertTrue("listen() on a closed server refuses rather than reopening: " + listened, listened.isException());
		assertTrue(server.isClosed());
	}

	@Test
	public void anInFlightRequestCompletesExactlyOnceWhenTheServerCloses() {
		List<SettablePromise<HttpResponse>> pending = new ArrayList<>();
		start(request -> {
			SettablePromise<HttpResponse> response = new SettablePromise<>();
			pending.add(response);
			return response;
		});

		int[] completions = {0};
		Promise<Http3TestPeer.Response> request =
			peer.request(Http3TestBytes.requestFields("GET", "/slow"), null);
		request.whenComplete(($, e) -> completions[0]++);

		wire.driveUntil(() -> !pending.isEmpty());
		assertEquals(1, server.activeRequests());

		server.close();
		wire.driveUntil(request::isComplete);
		assertEquals("the peer's request completed once", 1, completions[0]);

		server.close();
		loop.tickUntilQuiet();
		wire.pump();
		loop.tickUntilQuiet();

		assertEquals("the second close completed nothing a second time", 1, completions[0]);
		assertEquals(0, server.activeRequests());

		// The servlet's own promise is still unresolved; resolving it now must be harmless, and its
		// response body must be released rather than leaked (ByteBufRule is the assertion).
		pending.get(0).set(HttpResponse.ok200().withBody("too late".getBytes(UTF_8)).build());
		loop.tickUntilQuiet();
		assertEquals(1, completions[0]);
	}

	// ---------------------------------------------------------------- harness

	private void start(AsyncServlet servlet) {
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				server = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.build();
				server.listen();
				return server;
			})
			.connect();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
