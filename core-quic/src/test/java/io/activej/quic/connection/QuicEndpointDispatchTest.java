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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.connection.QuicConnection.Role;
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
 * T037 — US2: one socket, many connections, dispatched by destination connection ID.
 */
public final class QuicEndpointDispatchTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private QuicEndpointFixture fixture;
	private QuicEndpoint server;

	@Before
	public void setUp() {
		fixture = new QuicEndpointFixture();
		server = fixture.server(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		fixture.close();
	}

	private Promise<QuicConnection> connect() {
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());
		return client.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
	}

	@Test
	public void oneClientHandshakesThroughTheEndpoint() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();

		assertTrue("handshake did not complete: " + connecting, connecting.isResult());
		assertEquals(1, server.connectionCount());
		assertEquals(1, server.connectionsAccepted());
		assertEquals(QuicConnectionState.ESTABLISHED, connecting.getResult().state());
	}

	@Test
	public void fourClientsOnOneServerSocketGetFourDistinctConnections() {
		List<Promise<QuicConnection>> connecting = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			connecting.add(connect());
		}
		fixture.pump();

		for (int i = 0; i < connecting.size(); i++) {
			assertTrue("client " + i + " did not complete: " + connecting.get(i), connecting.get(i).isResult());
		}
		assertEquals(4, server.connectionCount());
		assertEquals(4, server.connectionsAccepted());

		// Distinct connection IDs are the whole point: the source address is not what identifies a
		// connection (RFC 9000 §5.2), so four connections must have produced four routing identities.
		List<QuicConnectionId> serverSideIds = new ArrayList<>();
		for (Promise<QuicConnection> promise : connecting) {
			QuicConnectionId serverId = promise.getResult().peerConnectionId();
			assertFalse("two connections share a connection ID", serverSideIds.contains(serverId));
			serverSideIds.add(serverId);
		}
	}

	@Test
	public void aServerConnectionIsReachableUnderBothItsOwnIdAndTheClientsFirstDcid() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		QuicConnection clientSide = connecting.getResult();

		// RFC 9000 §7.2: the client addresses the DCID it invented until it has seen the server's, and
		// its Initial retransmissions keep carrying it — so both must route to the same connection.
		QuicConnection viaOriginalDcid = server.connectionOf(clientSide.originalDestinationConnectionId());
		QuicConnection viaServerId = server.connectionOf(clientSide.peerConnectionId());
		assertNotNull(viaOriginalDcid);
		assertSame(viaOriginalDcid, viaServerId);
		assertEquals(Role.SERVER, viaServerId.role());
	}

	@Test
	public void aDatagramForAnUnknownShortHeaderIdIsDroppedNotAnswered() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		assertTrue(connecting.isResult());

		long droppedBefore = server.datagramsDropped();
		int connectionsBefore = server.connectionCount();

		// A short header whose DCID belongs to no connection. RFC 9000 §10.3's stateless reset would
		// answer it; that is out of scope, so silence is the specified behaviour — and answering would
		// make the endpoint a reflector.
		ByteBuf stray = ByteBufPool.allocate(64);
		stray.writeByte((byte) 0x40);            // short header, fixed bit set
		stray.put(new byte[8]);                  // an all-zero DCID of the length we issue
		stray.put(new byte[40]);
		fixture.network().send(new java.net.InetSocketAddress("127.0.0.1", 40999),
			QuicEndpointFixture.SERVER_ADDRESS, stray);
		fixture.pump();

		assertEquals(droppedBefore + 1, server.datagramsDropped());
		assertEquals(connectionsBefore, server.connectionCount());
		assertFalse(server.isClosed());
	}

	@Test
	public void aMalformedDatagramIsDroppedWithoutDisturbingLiveConnections() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		QuicConnection clientSide = connecting.getResult();

		long droppedBefore = server.datagramsDropped();
		// Fixed bit clear: not a QUIC packet at all (RFC 9000 §17.2), and unreadable before any crypto.
		ByteBuf garbage = ByteBufPool.allocate(32);
		garbage.writeByte((byte) 0x00);
		garbage.put(new byte[31]);
		fixture.network().send(new java.net.InetSocketAddress("127.0.0.1", 40999),
			QuicEndpointFixture.SERVER_ADDRESS, garbage);
		fixture.pump();

		assertEquals(droppedBefore + 1, server.datagramsDropped());
		assertEquals(QuicConnectionState.ESTABLISHED, clientSide.state());
		assertEquals(1, server.connectionCount());
	}

	@Test
	public void aClientOnlyEndpointRefusesToAcceptAnInboundInitial() {
		QuicEndpoint clientOnly = fixture.client(QuicConnectionSettings.create());
		assertFalse(clientOnly.canAccept());
		assertTrue(server.canAccept());
	}

	@Test
	public void aClosedConnectionIsUnregisteredFromTheDispatchTable() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		QuicConnection clientSide = connecting.getResult();
		QuicConnectionId serverSideId = clientSide.peerConnectionId();
		assertNotNull(server.connectionOf(serverSideId));
		assertEquals(2, server.routingEntryCount());

		QuicConnection serverSide = server.connectionOf(serverSideId);
		serverSide.close();

		// RFC 9000 §10.2.1: while closing, the entries must *stay* — a peer's retransmission has to keep
		// routing here, or it would never learn why we closed and would wait out its own idle timeout.
		assertSame(serverSide, server.connectionOf(serverSideId));
		assertEquals(2, server.routingEntryCount());

		serverSide.closeNow();

		// Both routing entries go, not just the one that was looked up — a leaked entry would keep a
		// dead connection reachable and would be an unbounded map (DI-8).
		assertNull(server.connectionOf(serverSideId));
		assertNull(server.connectionOf(clientSide.originalDestinationConnectionId()));
		assertEquals(0, server.routingEntryCount());
		assertEquals(0, server.connectionCount());
	}

	@Test
	public void closingTheEndpointClosesEveryConnection() {
		List<Promise<QuicConnection>> connecting = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			connecting.add(connect());
		}
		fixture.pump();
		assertEquals(3, server.connectionCount());

		List<QuicConnection> serverSide = new ArrayList<>();
		for (Promise<QuicConnection> promise : connecting) {
			serverSide.add(server.connectionOf(promise.getResult().peerConnectionId()));
		}
		server.close();

		assertTrue(server.isClosed());
		assertEquals(0, server.connectionCount());
		assertEquals(0, server.routingEntryCount());
		for (QuicConnection connection : serverSide) {
			assertEquals(QuicConnectionState.CLOSED, connection.state());
		}
	}

	@Test
	public void listenAndCloseAreIdempotent() {
		server.listen();
		server.listen();
		assertFalse(server.isClosed());
		server.close();
		server.close();
		assertTrue(server.isClosed());
	}

	@Test
	public void connectToOnAClosedEndpointFailsRatherThanRegisteringAConnection() {
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());
		client.close();
		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());

		assertTrue(connecting.isException());
		assertEquals(0, client.connectionCount());
	}

	@Test
	public void theHandshakingCountFallsBackToZeroOnceEveryHandshakeSettles() {
		for (int i = 0; i < 3; i++) {
			connect();
		}
		fixture.pump();

		assertEquals(3, server.connectionCount());
		assertEquals(0, server.handshakingConnectionCount());
	}
}
