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

package io.activej.quic.connection;

import io.activej.promise.Promise;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T039 — SI-3: every bound on the endpoint is enforced, and reaching one drops the <i>new</i> attempt
 * rather than disturbing what is already established.
 * <p>
 * That asymmetry is the requirement worth stating: a bound that shed live connections under load
 * would turn a capacity limit into an availability bug, and a bound that queued unvalidated peers
 * would hand an attacker exactly the resource it was meant to protect.
 */
public final class QuicEndpointLimitsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private QuicEndpointFixture fixture;

	@Before
	public void setUp() {
		fixture = new QuicEndpointFixture();
	}

	@After
	public void tearDown() {
		fixture.close();
	}

	private Promise<QuicConnection> connect() {
		return fixture.client(QuicConnectionSettings.create())
			.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
	}

	// ---------------------------------------------------------------- maxConnections

	@Test
	public void theThirdInboundConnectionIsRefusedAtATwoConnectionLimit() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withMaxConnections(2));

		List<Promise<QuicConnection>> first = List.of(connect(), connect());
		fixture.pump();
		assertEquals(2, server.connectionCount());
		for (Promise<QuicConnection> promise : first) {
			assertTrue(promise.isResult());
		}

		Promise<QuicConnection> third = connect();
		fixture.pump();

		assertEquals("the limit was exceeded", 2, server.connectionCount());
		assertEquals(1, server.connectionsRejected());
		// Refused, not queued: the third client simply never hears back and will time out on its own
		// handshake deadline. Nothing about it is retained on the server.
		assertFalse(third.isResult());
		// And the two established connections are untouched.
		for (Promise<QuicConnection> promise : first) {
			assertEquals(QuicConnectionState.ESTABLISHED, promise.getResult().state());
		}
	}

	@Test
	public void closingAConnectionFreesItsSlot() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withMaxConnections(1));

		Promise<QuicConnection> first = connect();
		fixture.pump();
		assertEquals(1, server.connectionCount());

		Promise<QuicConnection> refused = connect();
		fixture.pump();
		assertEquals(1, server.connectionsRejected());
		assertFalse(refused.isResult());

		// closeNow rather than close: a slot frees when the connection is *gone*, not when it starts
		// closing — RFC 9000 §10.2.1 keeps a closing connection addressable for three probe timeouts,
		// and counting it as free before then would let the bound be exceeded.
		server.connectionOf(first.getResult().peerConnectionId()).closeNow();
		assertEquals(0, server.connectionCount());

		Promise<QuicConnection> afterRelease = connect();
		fixture.pump();
		assertTrue("the freed slot was not reusable: " + afterRelease, afterRelease.isResult());
		assertEquals(1, server.connectionCount());
	}

	// ---------------------------------------------------------------- maxHandshakingConnections

	@Test
	public void aFurtherHandshakeIsRefusedWhileTheHandshakingLimitIsSaturated() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withMaxHandshakingConnections(2));

		// A one-way delay is what makes a hop observable: without it every datagram produced during a
		// delivery is itself immediately due, so a single deliverDue() runs the whole handshake and there
		// is no moment at which a connection is visibly half-open.
		fixture.network().withDelay(10);

		connect();
		connect();
		// One hop: both Initials arrive, both server connections are created, and neither has heard the
		// client's Finished yet — so both are still handshaking.
		fixture.advance(10);
		assertEquals(2, server.handshakingConnectionCount());

		Promise<QuicConnection> third = connect();
		fixture.advance(10);

		assertEquals(1, server.connectionsRejected());
		assertEquals(2, server.connectionCount());
		assertFalse(third.isResult());
	}

	@Test
	public void theHandshakingLimitIsSeparateFromAndSmallerThanTheConnectionLimit() {
		// A half-open connection costs a TLS engine and a key schedule for a peer whose address is not
		// yet validated — cheap for an attacker, expensive for us. Hence the two independent bounds.
		assertTrue("maxHandshakingConnections default (" + QuicEndpoint.MAX_HANDSHAKING_CONNECTIONS +
				") should be below maxConnections (" + QuicEndpoint.MAX_CONNECTIONS + ")",
			QuicEndpoint.MAX_HANDSHAKING_CONNECTIONS < QuicEndpoint.MAX_CONNECTIONS);
	}

	@Test
	public void aCompletedHandshakeLeavesTheHandshakingSetButKeepsItsConnectionSlot() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withMaxHandshakingConnections(1).withMaxConnections(4));

		Promise<QuicConnection> first = connect();
		fixture.pump();
		assertTrue(first.isResult());
		assertEquals(0, server.handshakingConnectionCount());
		assertEquals(1, server.connectionCount());

		// The single handshaking slot is free again, so a second client can come in behind the first.
		Promise<QuicConnection> second = connect();
		fixture.pump();
		assertTrue("the handshaking slot was not released: " + second, second.isResult());
		assertEquals(2, server.connectionCount());
	}

	// ---------------------------------------------------------------- outbound limit

	@Test
	public void connectToIsRefusedAtTheConnectionLimit() {
		fixture.server(QuicConnectionSettings.create());
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());
		QuicEndpoint bounded = QuicEndpoint.builder(
				(io.activej.reactor.nio.NioReactor) io.activej.reactor.Reactor.getCurrentReactor(),
				new io.activej.quic.connection.testutil.LossyUdpSocket(fixture.network(),
					new java.net.InetSocketAddress("127.0.0.1", 40888)))
			.withMaxConnections(1)
			.build();
		try {
			Promise<QuicConnection> ok = bounded.connectTo(
				QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			Promise<QuicConnection> refused = bounded.connectTo(
				QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());

			assertFalse(ok.isException());
			assertTrue("the outbound limit was not enforced", refused.isException());
			assertEquals(1, bounded.connectionCount());
		} finally {
			bounded.close();
			client.close();
			fixture.pump();
		}
	}

	// ---------------------------------------------------------------- configuration guards

	@Test
	public void aNonPositiveLimitIsRejectedAtBuildTime() {
		QuicEndpoint.Builder builder = QuicEndpoint.builder(
			(io.activej.reactor.nio.NioReactor) io.activej.reactor.Reactor.getCurrentReactor(),
			new io.activej.quic.connection.testutil.LossyUdpSocket(fixture.network(),
				new java.net.InetSocketAddress("127.0.0.1", 40889)));
		try {
			assertThrows(IllegalArgumentException.class, () -> builder.withMaxConnections(0));
			assertThrows(IllegalArgumentException.class, () -> builder.withMaxHandshakingConnections(0));
		} finally {
			builder.build().close();
		}
	}

	@Test
	public void manyConcurrentClientsAllComplete() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create());
		List<Promise<QuicConnection>> connecting = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			connecting.add(connect());
		}
		fixture.pump();

		for (int i = 0; i < connecting.size(); i++) {
			assertTrue("client " + i + " did not complete: " + connecting.get(i), connecting.get(i).isResult());
		}
		assertEquals(10, server.connectionCount());
		assertEquals(0, server.connectionsRejected());
		// Two routing entries per connection (the client's first DCID and the server's own).
		assertEquals(20, server.routingEntryCount());

		// Closing one connection must not disturb the other nine (SC-002): isolation extends to
		// teardown, not just to the handshake.
		QuicConnection closed = connecting.get(0).getResult();
		server.connectionOf(closed.peerConnectionId()).closeNow();
		fixture.pump();

		assertEquals(9, server.connectionCount());
		for (int i = 1; i < connecting.size(); i++) {
			assertEquals("client " + i + " was disturbed by closing client 0",
				QuicConnectionState.ESTABLISHED, connecting.get(i).getResult().state());
		}
	}
}
