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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.connection.CoalescedPackets.ProtectedPacket;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.connection.testutil.QuicTestPeers;
import io.activej.quic.tls.*;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.activej.promise.TestUtils.awaitException;
import static org.junit.Assert.*;

/**
 * T024 — US1 acceptance scenarios 1–6, over a hand-pumped in-process wire.
 * <p>
 * Datagrams are queued rather than delivered synchronously: a sink that called the peer's
 * {@code onDatagram} directly would re-enter the sender's own flush loop, which is a shape no real
 * socket produces and which would let a re-entrancy bug pass unnoticed.
 */
public final class QuicHandshakeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final InetSocketAddress CLIENT_ADDRESS = QuicTestPeers.CLIENT_ADDRESS;
	private static final InetSocketAddress SERVER_ADDRESS = QuicTestPeers.SERVER_ADDRESS;

	/** RFC 8446 §6 {@code bad_certificate} — what the client raises on any chain or hostname failure. */
	private static final int BAD_CERTIFICATE = 42;

	private final Wire clientWire = new Wire();
	private final Wire serverWire = new Wire();

	private QuicConnection client;
	private QuicConnection server;

	private static final class Wire implements QuicConnection.DatagramSink {
		private final ArrayDeque<ByteBuf> queue = new ArrayDeque<>();
		private boolean blackhole;
		private int datagramsAccepted;

		@Override
		public void send(InetSocketAddress to, ByteBuf datagram) {
			datagramsAccepted++;
			if (blackhole) {
				datagram.recycle();
				return;
			}
			queue.add(datagram);
		}

		void drain() {
			ByteBuf datagram;
			while ((datagram = queue.poll()) != null) {
				datagram.recycle();
			}
		}
	}

	@After
	public void cleanUp() {
		// Order matters: closing can enqueue a CONNECTION_CLOSE, so drain afterwards.
		if (client != null) client.close();
		if (server != null) server.close();
		clientWire.drain();
		serverWire.drain();
	}

	// ---------------------------------------------------------------- fixture

	private static QuicConnectionSettings settings(Duration handshakeTimeout) {
		return QuicConnectionSettings.builder()
			.withHandshakeTimeout(handshakeTimeout)
			.build();
	}

	private Promise<QuicConnection> startClient(String serverName, QuicConnectionSettings settings) {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		client = QuicConnection.builder(Reactor.getCurrentReactor(), Role.CLIENT, clientWire, SERVER_ADDRESS,
				params -> QuicTls.clientEngine(TlsClientConfig.builder(serverName, params)
					.withTrustManager(QuicTestPeers.trustingLeaf(identity.leaf()))
					.build()))
			.withSettings(settings)
			.build();
		return client.start();
	}

	/**
	 * Builds the server from the connection IDs of the client's first Initial, exactly as Phase 4's
	 * endpoint will (T042), and hands it that datagram.
	 */
	private Promise<QuicConnection> acceptServer(
		QuicConnectionSettings settings, UnaryOperator<QuicTransportParameters> localParams
	) throws MalformedDataException {
		ByteBuf firstDatagram = clientWire.queue.poll();
		assertNotNull("the client sent no Initial", firstDatagram);

		QuicConnectionId clientDcid;
		QuicConnectionId clientScid;
		// split() reads by index and never consumes the datagram, so it can be forwarded afterwards.
		List<ProtectedPacket> packets = CoalescedPackets.split(firstDatagram, 0, QuicPackets.SUPPORTED_VERSION);
		try {
			ProtectedPacket initial = packets.get(0);
			clientDcid = initial.destinationConnectionId();
			clientScid = initial.sourceConnectionId();
		} finally {
			for (ProtectedPacket packet : packets) {
				packet.bytes().recycle();
			}
		}
		assertNotNull("a client Initial must carry a source connection ID", clientScid);

		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		server = QuicConnection.builder(Reactor.getCurrentReactor(), Role.SERVER, serverWire, CLIENT_ADDRESS,
				params -> QuicTls.serverEngine(
					TlsServerConfig.builder(identity, localParams.apply(params)).build()))
			.withSettings(settings)
			.withPeerConnectionId(clientScid)
			.withOriginalDestinationConnectionId(clientDcid)
			.build();
		Promise<QuicConnection> promise = server.start();
		server.onDatagram(firstDatagram);
		return promise;
	}

	/** Delivers queued datagrams both ways until neither side has anything left to say. */
	private void pump() {
		for (int round = 0; round < 32; round++) {
			boolean progress = false;
			ByteBuf datagram;
			while ((datagram = clientWire.queue.poll()) != null) {
				server.onDatagram(datagram);
				progress = true;
			}
			while ((datagram = serverWire.queue.poll()) != null) {
				client.onDatagram(datagram);
				progress = true;
			}
			if (!progress) return;
		}
		fail("the handshake did not settle within 32 delivery rounds");
	}

	private static QuicTransportException causeOf(Promise<QuicConnection> promise) {
		assertTrue("expected a failure, got " + promise, promise.isException());
		Exception e = promise.getException();
		assertTrue("expected a QuicTransportException, got " + e, e instanceof QuicTransportException);
		return (QuicTransportException) e;
	}

	// ---------------------------------------------------------------- scenario 1

	@Test
	public void scenario1_clientAndServerCompleteAHandshakeOverLoopback() throws Exception {
		Promise<QuicConnection> clientDone = startClient("localhost", settings(Duration.ofSeconds(10)));
		Promise<QuicConnection> serverDone = acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		assertTrue("client handshake did not complete: " + clientDone, clientDone.isResult());
		assertTrue("server handshake did not complete: " + serverDone, serverDone.isResult());
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertEquals(QuicConnectionState.ESTABLISHED, server.state());
		assertEquals("h3", client.negotiatedAlpn());
		assertEquals("h3", server.negotiatedAlpn());
		assertNotNull(client.peerTransportParameters());
		assertNotNull(server.peerTransportParameters());
		// Both sides reached 1-RTT keys, which is the only thing "established" can mean here.
		assertTrue(client.isLevelInstalled(EncryptionLevel.ONE_RTT));
		assertTrue(server.isLevelInstalled(EncryptionLevel.ONE_RTT));
	}

	@Test
	public void scenario1_eachSideSeesTheOthersAdvertisedParameters() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		QuicTransportParameters fromServer = client.peerTransportParameters();
		assertNotNull(fromServer);
		// FR-007/FR-008: the server echoed both identification parameters, and they matched the wire.
		assertArrayEquals(server.localConnectionId().bytes(), fromServer.initialSourceConnectionId());
		assertArrayEquals(client.originalDestinationConnectionId().bytes(),
			fromServer.originalDestinationConnectionId());

		QuicTransportParameters fromClient = server.peerTransportParameters();
		assertNotNull(fromClient);
		assertArrayEquals(client.localConnectionId().bytes(), fromClient.initialSourceConnectionId());
		// A client never sends original_destination_connection_id.
		assertNull(fromClient.originalDestinationConnectionId());
	}

	@Test
	public void scenario1_theClientAdoptsTheServersChosenConnectionId() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		QuicConnectionId firstDcid = client.originalDestinationConnectionId();
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		// RFC 9000 §7.2: after the server's first long-header packet the client addresses it by the
		// connection ID the server chose, not by the unpredictable value that seeded the Initial keys.
		assertEquals(server.localConnectionId(), client.peerConnectionId());
		assertNotEquals(firstDcid, client.peerConnectionId());
		assertEquals(client.localConnectionId(), server.peerConnectionId());
	}

	// ---------------------------------------------------------------- scenario 2

	@Test
	public void scenario2_theClientsFirstDatagramIsPaddedAndCarriesItsSourceConnectionId() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));

		ByteBuf firstDatagram = clientWire.queue.peek();
		assertNotNull(firstDatagram);
		// RFC 9000 §14.1: a datagram carrying a client Initial is at least 1200 bytes, so a server can
		// never be made to amplify from a small one.
		assertTrue("Initial datagram was only " + firstDatagram.readRemaining() + " bytes",
			firstDatagram.readRemaining() >= PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);

		List<ProtectedPacket> packets = CoalescedPackets.split(firstDatagram, 0, QuicPackets.SUPPORTED_VERSION);
		try {
			ProtectedPacket initial = packets.get(0);
			assertEquals(CoalescedPackets.Kind.INITIAL, initial.kind());
			assertEquals(client.localConnectionId(), initial.sourceConnectionId());
			assertEquals(client.originalDestinationConnectionId(), initial.destinationConnectionId());
		} finally {
			for (ProtectedPacket packet : packets) {
				packet.bytes().recycle();
			}
		}
	}

	@Test
	public void scenario2_theClientsFirstDestinationConnectionIdIsAtLeastEightBytes() {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		// RFC 9000 §7.2: shorter than 8 bytes and the Initial keys stop being unpredictable.
		assertTrue(client.originalDestinationConnectionId().length() >= 8);
	}

	// ---------------------------------------------------------------- scenario 3

	@Test
	public void scenario3_aMismatchedInitialSourceConnectionIdIsATransportParameterError() throws Exception {
		Promise<QuicConnection> clientDone = startClient("localhost", settings(Duration.ofSeconds(10)));
		// The server advertises a connection ID it never put on the wire — the exact substitution
		// RFC 9000 §7.3 exists to catch.
		acceptServer(settings(Duration.ofSeconds(10)), params -> new QuicTransportParameters(
			params.originalDestinationConnectionId(), params.maxIdleTimeout(), params.statelessResetToken(),
			params.maxUdpPayloadSize(), params.initialMaxData(), params.initialMaxStreamDataBidiLocal(),
			params.initialMaxStreamDataBidiRemote(), params.initialMaxStreamDataUni(),
			params.initialMaxStreamsBidi(), params.initialMaxStreamsUni(), params.ackDelayExponent(),
			params.maxAckDelay(), params.disableActiveMigration(), params.preferredAddress(),
			params.activeConnectionIdLimit(), new byte[]{9, 9, 9, 9, 9, 9, 9, 9},
			params.retrySourceConnectionId(), params.maxDatagramFrameSize()));
		pump();

		QuicTransportException e = causeOf(clientDone);
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("initial_source_connection_id"));
		// RFC 9000 §10.2.1: an endpoint that closes stays in `closing` for three probe timeouts so a
		// peer's retransmission still gets an answer. The promise, though, fails immediately.
		assertEquals(QuicConnectionState.CLOSING, client.state());
	}

	@Test
	public void scenario3_aMismatchedOriginalDestinationConnectionIdIsATransportParameterError() throws Exception {
		Promise<QuicConnection> clientDone = startClient("localhost", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), params -> new QuicTransportParameters(
			new byte[]{7, 7, 7, 7, 7, 7, 7, 7}, params.maxIdleTimeout(), params.statelessResetToken(),
			params.maxUdpPayloadSize(), params.initialMaxData(), params.initialMaxStreamDataBidiLocal(),
			params.initialMaxStreamDataBidiRemote(), params.initialMaxStreamDataUni(),
			params.initialMaxStreamsBidi(), params.initialMaxStreamsUni(), params.ackDelayExponent(),
			params.maxAckDelay(), params.disableActiveMigration(), params.preferredAddress(),
			params.activeConnectionIdLimit(), params.initialSourceConnectionId(),
			params.retrySourceConnectionId(), params.maxDatagramFrameSize()));
		pump();

		QuicTransportException e = causeOf(clientDone);
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("original_destination_connection_id"));
	}

	// ---------------------------------------------------------------- scenario 4

	@Test
	public void scenario4_aTlsFailureBecomesCryptoErrorPlusTheAlertCode() throws Exception {
		// The certificate is valid but issued for localhost, so RFC 6125 endpoint identification
		// rejects it — a TLS alert, not a transport error.
		Promise<QuicConnection> clientDone = startClient("wrong.invalid", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		QuicTransportException e = causeOf(clientDone);
		// RFC 9001 §4.8: 0x0100 + the RFC 8446 §6 alert description.
		assertTrue("expected a CRYPTO_ERROR, got 0x" + Long.toHexString(e.errorCode()),
			QuicTransportErrors.isCryptoError(e.errorCode()));
		assertEquals(BAD_CERTIFICATE, QuicTransportErrors.alertOf(e.errorCode()));
		assertEquals(QuicTransportErrors.CRYPTO_ERROR_BASE + BAD_CERTIFICATE, e.errorCode());
		assertEquals(QuicConnectionState.CLOSING, client.state());
	}

	@Test
	public void scenario4_theServerLearnsWhyTheClientGaveUp() throws Exception {
		startClient("wrong.invalid", settings(Duration.ofSeconds(10)));
		Promise<QuicConnection> serverDone = acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		// The client's CONNECTION_CLOSE reached the server, so the server fails with the same code
		// rather than sitting on a half-open connection until its deadline.
		QuicTransportException e = causeOf(serverDone);
		assertEquals(QuicTransportErrors.CRYPTO_ERROR_BASE + BAD_CERTIFICATE, e.errorCode());
		// RFC 9000 §10.2.2: the side that *receives* a CONNECTION_CLOSE drains rather than closes — it
		// sends nothing further, which is what distinguishes draining from closing.
		assertEquals(QuicConnectionState.DRAINING, server.state());
		QuicConnection.PeerClose peerClose = server.peerClose();
		assertNotNull(peerClose);
		assertEquals(QuicTransportErrors.CRYPTO_ERROR_BASE + BAD_CERTIFICATE, peerClose.errorCode());
	}

	// ---------------------------------------------------------------- scenario 5

	@Test
	public void scenario5_anUnresponsiveServerFailsTheHandshakeAtTheDeadline() {
		clientWire.blackhole = true;
		Promise<QuicConnection> clientDone = startClient("localhost", settings(Duration.ofMillis(100)));

		Exception e = awaitException(clientDone);
		assertTrue("expected a QuicTransportException, got " + e, e instanceof QuicTransportException);
		assertTrue(e.getMessage().contains("Handshake did not complete"));
		assertEquals(QuicConnectionState.CLOSED, client.state());
		// FR-024: nothing is left over. The client did send its Initial — it simply went nowhere.
		assertTrue(clientWire.datagramsAccepted >= 1);
		assertEquals(0, client.packetsAwaitingKeys());
		// ByteBufRule is what actually proves "no residue": every scratch buffer, every queued CRYPTO
		// slice and every unacknowledged packet's frames had to be recycled by the deadline handler.
	}

	@Test
	public void scenario5_theDeadlineDoesNotFireOnceTheHandshakeHasCompleted() throws Exception {
		Promise<QuicConnection> clientDone = startClient("localhost", settings(Duration.ofMillis(100)));
		acceptServer(settings(Duration.ofMillis(100)), p -> p);
		pump();
		assertTrue(clientDone.isResult());

		// A deadline left armed would tear this connection down 100 ms later. Asserted directly rather
		// than by draining the reactor: an established connection keeps a probe timer armed until its
		// peer acknowledges everything (RFC 9002 §6.2), so the reactor never goes idle to be drained.
		assertFalse("the handshake deadline is still armed", client.isHandshakeDeadlineArmed());
		assertFalse("the handshake deadline is still armed", server.isHandshakeDeadlineArmed());
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
	}

	// ---------------------------------------------------------------- scenario 6

	@Test
	public void scenario6_handshakeDoneConfirmsAndDiscardsTheHandshakeLevel() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		// FR-005/FR-006: the server confirms on sending HANDSHAKE_DONE, the client on receiving it, and
		// both then retire the Handshake keys and number space for good.
		assertTrue("client did not confirm the handshake", client.isHandshakeConfirmed());
		assertTrue("server did not confirm the handshake", server.isHandshakeConfirmed());
		assertTrue(client.isLevelDiscarded(EncryptionLevel.HANDSHAKE));
		assertTrue(server.isLevelDiscarded(EncryptionLevel.HANDSHAKE));
		assertFalse(client.isLevelDiscarded(EncryptionLevel.ONE_RTT));
		assertFalse(server.isLevelDiscarded(EncryptionLevel.ONE_RTT));
	}

	@Test
	public void scenario6_theInitialLevelIsDiscardedAsSoonAsAHandshakePacketArrives() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();

		assertTrue(client.isLevelDiscarded(EncryptionLevel.INITIAL));
		assertTrue(server.isLevelDiscarded(EncryptionLevel.INITIAL));
	}

	@Test
	public void scenario6_aLateInitialPacketIsDroppedRatherThanTreatedAsAnError() throws Exception {
		startClient("localhost", settings(Duration.ofSeconds(10)));
		acceptServer(settings(Duration.ofSeconds(10)), p -> p);
		pump();
		assertTrue(client.isLevelDiscarded(EncryptionLevel.INITIAL));

		long droppedBefore = client.packetsDropped();
		// A retransmission of the server's very first flight, arriving after the level is gone. RFC 9000
		// §12.5 makes this unremarkable, so it must not close a working connection.
		ByteBuf replay = protectedInitialFrom(server);
		client.onDatagram(replay);

		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertTrue(client.packetsDropped() > droppedBefore);
	}

	/** A syntactically well-formed Initial from {@code from}'s perspective, which the peer has retired. */
	private ByteBuf protectedInitialFrom(QuicConnection from) {
		// Built by hand rather than captured, because by this point both sides have long since stopped
		// producing Initial packets.
		return io.activej.quic.connection.PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, 0,
				List.of(io.activej.quic.codec.PingFrame.INSTANCE)),
			io.activej.quic.crypto.QuicKeys.initial(client.originalDestinationConnectionId()).server(),
			client.localConnectionId(), from.localConnectionId(), new byte[0],
			QuicPackets.SUPPORTED_VERSION, 64);
	}
}
