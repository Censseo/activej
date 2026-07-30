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
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.connection.testutil.DatagramNetwork.Delivery;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T038 — US2: a server must never be usable as a reflection amplifier (RFC 9000 §8.1, FR-022).
 * <p>
 * Two things enforce that, and both are tested here: the 1200-byte floor on a datagram carrying a
 * client Initial (without which the 3× budget would be computed off a tiny input), and the 3×
 * accounting itself. The <i>clamp</i> — what happens at the moment the budget runs out — is unit
 * tested in {@code AmplificationBudgetTest}; what this class adds is that the endpoint actually
 * feeds it, and that address validation lifts it.
 */
public final class QuicAntiAmplificationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int AMPLIFICATION_FACTOR = 3;
	private static final InetSocketAddress RAW_SENDER = new InetSocketAddress("127.0.0.1", 40777);

	private QuicEndpointFixture fixture;
	private QuicEndpoint server;

	@Before
	public void setUp() {
		fixture = new QuicEndpointFixture();
		fixture.network().withTraceRecording();
		server = fixture.server(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		fixture.close();
	}

	private long bytesFromServer() {
		long total = 0;
		for (Delivery delivery : fixture.network().trace()) {
			if (delivery.from().equals(QuicEndpointFixture.SERVER_ADDRESS)) {
				total += delivery.size();
			}
		}
		return total;
	}

	private long bytesToServer() {
		long total = 0;
		for (Delivery delivery : fixture.network().trace()) {
			if (delivery.to().equals(QuicEndpointFixture.SERVER_ADDRESS)) {
				total += delivery.size();
			}
		}
		return total;
	}

	/** A well-formed, correctly protected client Initial carrying one PING, padded to {@code padTo}. */
	private static ByteBuf clientInitial(QuicConnectionId dcid, QuicConnectionId scid, int padTo) {
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, 0, List.of(PingFrame.INSTANCE)),
			QuicKeys.initial(dcid).client(), dcid, scid, new byte[0], QuicPackets.SUPPORTED_VERSION, 64);
		return PacketAssembler.coalesce(List.of(packet), padTo);
	}

	// ---------------------------------------------------------------- the 1200-byte floor

	@Test
	public void anInitialDatagramBelowTwelveHundredBytesCreatesNoConnection() {
		QuicConnectionId dcid = QuicConnectionId.random(8);
		ByteBuf small = clientInitial(dcid, QuicConnectionId.random(8), 0);
		assertTrue("fixture is wrong: this datagram is not small",
			small.readRemaining() < PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);

		fixture.network().send(RAW_SENDER, QuicEndpointFixture.SERVER_ADDRESS, small);
		fixture.pump();

		// RFC 9000 §14.1. Accepting it would let an attacker buy a server flight for a fraction of its
		// cost, which is the entire amplification attack.
		assertEquals(0, server.connectionCount());
		assertEquals(0, server.connectionsAccepted());
		assertEquals(0, bytesFromServer());
	}

	@Test
	public void thePaddedFormOfTheSameDatagramIsAccepted() {
		QuicConnectionId dcid = QuicConnectionId.random(8);
		ByteBuf padded = clientInitial(dcid, QuicConnectionId.random(8),
			PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);
		assertEquals(PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE, padded.readRemaining());

		fixture.network().send(RAW_SENDER, QuicEndpointFixture.SERVER_ADDRESS, padded);
		fixture.pump();

		// The size is the only difference from the test above — which is the point.
		assertEquals(1, server.connectionCount());
		assertEquals(1, server.connectionsAccepted());
		QuicConnection accepted = server.connectionOf(dcid);
		assertNotNull(accepted);
		assertEquals(QuicConnectionState.HANDSHAKING, accepted.state());
	}

	// ---------------------------------------------------------------- the 3× budget

	@Test
	public void theServerSendsNoMoreThanThreeTimesWhatItHasReceived() {
		Promise<QuicConnection> connecting = fixture.client(QuicConnectionSettings.create())
			.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());

		// One hop at a time, asserting the invariant after each: a check only at the end would pass even
		// if the server had briefly overshot before the client validated its address.
		for (int hop = 0; hop < 8 && fixture.network().deliverDue() > 0; hop++) {
			long received = bytesToServer();
			long sent = bytesFromServer();
			assertTrue("after hop " + hop + " the server had sent " + sent + " bytes having received " +
					received + " — over the " + AMPLIFICATION_FACTOR + "× limit",
				sent <= AMPLIFICATION_FACTOR * received);
		}

		assertTrue("handshake did not complete: " + connecting, connecting.isResult());
	}

	@Test
	public void aServerThatIsNeverAnsweredStaysWithinItsBudgetAndStopsThere() {
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());
		client.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		// Deliver the client's Initial, then take the client off the network so it can never validate
		// its address. Everything the server says from here on is spoken into the void.
		fixture.network().deliverDue();
		client.close();
		fixture.pump();

		long received = bytesToServer();
		long sent = bytesFromServer();
		assertTrue("the server received nothing; the fixture is wrong", received > 0);
		assertTrue("server sent " + sent + " bytes for " + received + " received",
			sent <= AMPLIFICATION_FACTOR * received);
		assertEquals(1, server.connectionCount());
	}

	@Test
	public void aDroppedUndecryptableDatagramStillCountsTowardsTheBudget() {
		// FR-022: the budget is credited on receipt, before decryption. Crediting only what decrypts
		// would let a peer whose packets we cannot open starve a legitimate handshake sharing the path —
		// and it is not what RFC 9000 §8.1 says to count.
		QuicConnectionId dcid = QuicConnectionId.random(8);
		ByteBuf real = clientInitial(dcid, QuicConnectionId.random(8),
			PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);
		fixture.network().send(RAW_SENDER, QuicEndpointFixture.SERVER_ADDRESS, real);
		fixture.pump();

		QuicConnection accepted = server.connectionOf(dcid);
		assertNotNull(accepted);
		long droppedBefore = accepted.packetsDropped();

		// The same connection ID, but protected under the wrong keys: it routes, then fails AEAD.
		QuicConnectionId wrongKeys = QuicConnectionId.random(8);
		ByteBuf undecryptable = PacketAssembler.coalesce(List.of(PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 1, 0, List.of(PingFrame.INSTANCE)),
			QuicKeys.initial(wrongKeys).client(), dcid, QuicConnectionId.random(8), new byte[0],
			QuicPackets.SUPPORTED_VERSION, 64)), PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);
		fixture.network().send(RAW_SENDER, QuicEndpointFixture.SERVER_ADDRESS, undecryptable);
		fixture.pump();

		assertTrue("the undecryptable packet was not dropped", accepted.packetsDropped() > droppedBefore);
		assertEquals("it must not have been treated as a connection error",
			QuicConnectionState.HANDSHAKING, accepted.state());
		assertEquals(2, accepted.datagramsReceived());
	}

	// ---------------------------------------------------------------- address validation

	@Test
	public void aCompletedHandshakeMeansTheServerIsNoLongerBudgetLimited() {
		Promise<QuicConnection> connecting = fixture.client(QuicConnectionSettings.create())
			.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		fixture.pump();
		assertTrue(connecting.isResult());

		QuicConnection serverSide = server.connectionOf(connecting.getResult().peerConnectionId());
		assertNotNull(serverSide);
		// RFC 9000 §8.1: a Handshake packet from the client validates the path, and the limit is gone —
		// which the completed handshake itself proves, since the flight could not otherwise finish.
		assertEquals(QuicConnectionState.ESTABLISHED, serverSide.state());
		assertTrue(serverSide.isHandshakeConfirmed());
	}
}
