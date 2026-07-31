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
import io.activej.quic.codec.QuicPackets;
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

	/** A long-header datagram of {@code packetType} at {@code version}, padded past the §14.1 minimum. */
	private static ByteBuf longHeaderDatagram(int packetType, long version) {
		ByteBuf out = ByteBufPool.allocate(1500);
		out.writeByte((byte) (0x80 | 0x40 | (packetType << 4)));
		out.writeInt((int) version);
		out.writeByte((byte) 8);
		out.put(new byte[]{11, 22, 33, 44, 55, 66, 77, 88});   // DCID
		out.writeByte((byte) 8);
		out.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});           // SCID
		out.put(new byte[1200]);
		return out;
	}

	private void sendToServer(ByteBuf datagram) {
		fixture.network().send(new java.net.InetSocketAddress("127.0.0.1", 40999),
			QuicEndpointFixture.SERVER_ADDRESS, datagram);
		fixture.pump();
	}

	@Test
	public void aVersionNegotiationPacketIsNeverAnsweredWithAVersionNegotiationPacket() {
		long sentBefore = server.versionNegotiationsSent();

		// Version 0 in a long header *is* a Version Negotiation packet. RFC 9000 §6.1 forbids answering
		// one with another: two endpoints that both answered would trade them until something gave up,
		// and a spoofed source address turns that into an exchange between two innocent parties.
		sendToServer(longHeaderDatagram(0, 0));

		assertEquals(sentBefore, server.versionNegotiationsSent());
		assertEquals(0, server.connectionCount());
	}

	@Test
	public void anUnsupportedVersionIsStillAnsweredWithVersionNegotiation() {
		long sentBefore = server.versionNegotiationsSent();

		// The counterpart to the test above: refusing version 0 must not have disabled the answer that
		// RFC 9000 §6.1 does require. 0x1a2a3a4a is from the §15 "force negotiation" reserved pattern.
		sendToServer(longHeaderDatagram(0, 0x1a2a3a4aL));

		assertEquals(sentBefore + 1, server.versionNegotiationsSent());
		assertEquals(0, server.connectionCount());
	}

	@Test
	public void onlyAnInitialPacketMayCreateAConnection() {
		// RFC 9000 §7.2. A Handshake (type 2) or 0-RTT (type 1) packet whose DCID matched nothing belongs
		// to a connection that no longer exists; building a TLS engine and a key schedule for it would
		// fail on its first frame, having already spent what an attacker wanted us to spend.
		for (int packetType : new int[]{1, 2, 3}) {
			long droppedBefore = server.datagramsDropped();
			sendToServer(longHeaderDatagram(packetType, QuicPackets.SUPPORTED_VERSION));

			assertEquals("packet type " + packetType + " created a connection", 0, server.connectionCount());
			assertEquals(droppedBefore + 1, server.datagramsDropped());
		}
		assertEquals(0, server.connectionsAccepted());
	}

	@Test
	public void anInitialReusingALiveConnectionsIdIsRefusedRatherThanDisplacingIt() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		QuicConnection clientSide = connecting.getResult();
		QuicConnectionId liveId = clientSide.peerConnectionId();
		QuicConnection serverSide = server.connectionOf(liveId);
		assertNotNull(serverSide);
		int entriesBefore = server.routingEntryCount();

		// The DCID of a client Initial is a value the client invents, so anyone can name a connection that
		// already exists. Overwriting the routing entry would make the live connection unreachable — a
		// denial of service costing one datagram.
		ByteBuf collision = ByteBufPool.allocate(1500);
		collision.writeByte((byte) 0xC0);
		collision.writeInt((int) QuicPackets.SUPPORTED_VERSION);
		byte[] dcid = liveId.bytes();
		collision.writeByte((byte) dcid.length);
		collision.put(dcid);
		collision.writeByte((byte) 8);
		collision.put(new byte[]{9, 8, 7, 6, 5, 4, 3, 2});
		collision.put(new byte[1200]);
		sendToServer(collision);

		assertSame(serverSide, server.connectionOf(liveId));
		assertEquals(entriesBefore, server.routingEntryCount());
		assertEquals(QuicConnectionState.ESTABLISHED, clientSide.state());
	}

	@Test
	public void closingTheEndpointLetsEveryConnectionSayWhyItIsGoing() {
		Promise<QuicConnection> connecting = connect();
		fixture.pump();
		QuicConnection clientSide = connecting.getResult();
		assertEquals(QuicConnectionState.ESTABLISHED, clientSide.state());

		server.close();
		fixture.pump();

		// The javadoc promises each connection "the chance to put a CONNECTION_CLOSE on the wire while the
		// socket is still open". Marking the endpoint closed first would have made sendDatagram recycle
		// every one of them in silence, and the client would have waited out a 30-second idle timeout to
		// learn what it can be told in one datagram.
		assertNotNull("the client was never told the server closed", clientSide.peerClose());
		assertEquals(QuicConnectionState.DRAINING, clientSide.state());
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
