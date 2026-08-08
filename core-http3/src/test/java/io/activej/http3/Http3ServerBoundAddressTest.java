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
import io.activej.http3.Http3Connection.State;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDatagramNetwork;
import io.activej.http3.testutil.StubUdpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T005 / contracts §2 — {@link Http3Server#getBoundAddress()}, at every lifecycle point and over
 * every construction path (spec US1 scenarios 1–7, FR-007…FR-010).
 * <p>
 * <b>Why not {@code EventloopRule}.</b> This module's reactive tests run on a {@link ManualEventloop}
 * (hand-driven clock), and the two real-socket cases bind synchronously — {@code listen()} completes
 * before it returns — so a selector-driven loop is neither needed nor usable: a registered key keeps
 * {@code Eventloop.run()} alive, which would make {@code TestUtils.await} hang.
 * <p>
 * <b>The drain case is stub-fabric, the closed case is real-socket, deliberately.</b> The GOAWAY
 * drain needs a live exchange, which the in-process {@link Http3WirePair} drives deterministically;
 * the "after release → {@code null}" half of the contract (FR-008 + FR-004) is a property of the real
 * {@code UdpSocket}'s {@code ClosedChannelException} handling and needs a real channel that is
 * actually closed. {@link StubUdpSocket} models its configured address and keeps reporting it after
 * close — that is its contract — so it can only carry the first half.
 * <p>
 * <b>The foreign-thread case needs a reactor that is running.</b> {@code inReactorThread()} is true
 * for any thread while the loop is stopped, so on a hand-driven loop the guard cannot fire; the case
 * therefore puts the server on a continuously-running {@link EventloopThread}, making the JUnit
 * thread genuinely foreign (FR-010).
 */
public final class Http3ServerBoundAddressTest {
	/** A synthetic fabric address; nothing binds to the OS, so a real free port would be a race. */
	private static final InetSocketAddress SOCKET_ADDRESS = new InetSocketAddress("127.0.0.1", 41400);

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;

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
	public void unlistenedServerHasNoBoundAddress() {
		Http3Server server = serverWithListenPort(0);
		assertNull("before listen() there is no socket to read an address from (FR-008)",
			server.getBoundAddress());
		server.close();
	}

	@Test
	public void zeroPortResolvesToARealPort() {
		Http3Server server = serverWithListenPort(0);
		Promise<Void> listened = server.listen();
		assertTrue("binding :0 completes synchronously: " + listened, listened.isResult());

		InetSocketAddress bound = server.getBoundAddress();
		assertNotNull("the accessor reports the OS-assigned address (FR-007)", bound);
		assertNotEquals("the :0 port was resolved by the OS", 0, bound.getPort());
		server.close();
	}

	@Test
	public void explicitPortIsReportedBack() {
		int port = getFreePort();
		Http3Server server = serverWithListenPort(port);
		assertTrue(server.listen().isResult());

		InetSocketAddress bound = server.getBoundAddress();
		assertNotNull(bound);
		assertEquals("the configured port is reported verbatim", port, bound.getPort());
		server.close();
	}

	@Test
	public void suppliedSocketAddressIsReported() {
		StubDatagramNetwork network = new StubDatagramNetwork();
		StubUdpSocket socket = new StubUdpSocket(network, SOCKET_ADDRESS);
		Http3Server server = Http3Server.builder(reactor(), trivialServlet())
			.withSocket(socket)
			.withServerIdentity(Http3TestTls.devIdentity())
			.build();
		assertTrue(server.listen().isResult());

		assertEquals("the withSocket path reports the supplied socket's own address (FR-009)",
			SOCKET_ADDRESS, server.getBoundAddress());
		server.close();
		network.close();
	}

	@Test
	public void addressSurvivesTheGoawayDrain() {
		wire = new Http3WirePair(loop);
		Http3TestPeer peer = new Http3TestPeer(wire);
		SettablePromise<HttpResponse> held = new SettablePromise<>();
		AtomicBoolean served = new AtomicBoolean();
		Http3Server[] server = new Http3Server[1];
		wire.withServerFactory(socket -> {
				server[0] = Http3Server.builder(reactor(), request -> {
						served.set(true);
						return held;
					})
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.build();
				server[0].listen();
				return server[0];
			})
			.connect();

		assertEquals(Http3WirePair.SERVER_ADDRESS, server[0].getBoundAddress());

		Promise<Http3TestPeer.Response> slow = peer.request(Http3TestBytes.requestFields("GET", "/slow"), null);
		wire.driveUntil(served::get);
		assertEquals("the request is being served when the server closes", 1, server[0].activeRequests());

		server[0].close();
		wire.driveUntil(() -> peer.connection().goAwayReceivedId() != Http3Connection.NO_GOAWAY_ID);

		// FR-008: the drain is under way — the closing flag is set, the socket is not yet released —
		// and the accessor must still report the real address, consulting no closing flag.
		assertTrue(server[0].isClosed());
		assertEquals("the address stays reported throughout the GOAWAY drain",
			Http3WirePair.SERVER_ADDRESS, server[0].getBoundAddress());

		held.set(HttpResponse.ok200().withBody("done".getBytes(UTF_8)).build());
		Http3TestPeer.Response response = await(slow);
		assertEquals(200, response.status());

		// The drain finishes only once the last stream closes; drive the exchange to that point.
		wire.driveUntil(() -> peer.connection().state() == State.CLOSED);
	}

	@Test
	public void closedServerHasNoBoundAddress() {
		Http3Server server = serverWithListenPort(0);
		assertTrue(server.listen().isResult());
		assertNotNull(server.getBoundAddress());

		// Nothing in flight: the drain is empty and the socket is released at once.
		server.close();
		assertNull("after the drain releases the socket, the address is gone (FR-008 + FR-004)",
			server.getBoundAddress());
	}

	@Test
	public void foreignThreadReadIsRefused() {
		// The guard needs a reactor that is actually running on its owning thread: once a loop is
		// stopped, inReactorThread() is true for any thread, so the refusal is untestable on the
		// hand-driven ManualEventloop. A continuously-running EventloopThread makes the JUnit thread
		// genuinely foreign (FR-010).
		try (EventloopThread loop = EventloopThread.create("bound-address-server")) {
			Http3Server server = loop.submit(() -> {
				Http3Server built = Http3Server.builder(loop.eventloop(), trivialServlet())
					.withListenPort(0)
					.withServerIdentity(Http3TestTls.devIdentity())
					.build();
				built.listen();
				return built;
			});
			loop.onClose(server::close);

			assertThrows("a foreign-thread read is refused by the reactor-thread guard",
				IllegalStateException.class, server::getBoundAddress);
		}
	}

	// ---------------------------------------------------------------- harness

	private Http3Server serverWithListenPort(int port) {
		return Http3Server.builder(reactor(), trivialServlet())
			.withListenPort(port)
			.withServerIdentity(Http3TestTls.devIdentity())
			.build();
	}

	private static AsyncServlet trivialServlet() {
		return request -> HttpResponse.ok200().toPromise();
	}

	private <T> T await(Promise<T> promise) {
		wire.driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the promise failed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
