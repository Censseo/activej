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
import io.activej.quic.connection.QuicConnection.PeerClose;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.*;

/**
 * T055 — US4 scenarios 1–7: the four ways a connection ends, each with its own state, duration and
 * effect on what is still in flight.
 * <p>
 * Every timer here runs on {@link ManualEventloop}'s hand-set clock, so nothing sleeps and the suite's
 * duration is independent of the timeouts configured (SC-011).
 */
public final class QuicTerminationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ManualEventloop loop;
	private QuicWirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	private static QuicConnectionSettings settings() {
		return QuicConnectionSettings.create();
	}

	private static QuicConnectionSettings settings(Duration idleTimeout) {
		return QuicConnectionSettings.builder().withMaxIdleTimeout(idleTimeout).build();
	}

	// ---------------------------------------------------------------- scenario 1: local close

	@Test
	public void scenario1_aLocalCloseSendsConnectionCloseAndEntersTheClosingState() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		int sentBefore = wire.clientWire().datagramsAccepted();

		client.close();

		assertEquals(QuicConnectionState.CLOSING, client.state());
		assertEquals("exactly one CONNECTION_CLOSE datagram", sentBefore + 1,
			wire.clientWire().datagramsAccepted());
		assertTrue("the closing period is not armed", client.isClosingPeriodArmed());
		// Everything a live connection needs is already gone; only the keys and the close frame remain.
		assertFalse(client.isLossTimerArmed());
		assertFalse(client.isIdleTimerArmed());
		assertFalse(client.isHandshakeDeadlineArmed());
	}

	@Test
	public void scenario1_theClosingPeriodLastsThreeProbeTimeoutsAndThenReleasesEverything() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		client.close();
		long period = client.closingPeriodMillis();
		assertTrue("a closing period of " + period + " ms is not three probe timeouts", period > 0);

		// One millisecond short of the deadline the connection is still addressable, because a peer's
		// retransmission arriving now still deserves an answer (RFC 9000 §10.2.1).
		loop.advance(period - 1);
		assertEquals(QuicConnectionState.CLOSING, client.state());

		loop.advance(2);
		assertEquals(QuicConnectionState.CLOSED, client.state());
		assertFalse(client.isClosingPeriodArmed());
		// ByteBufRule is what proves the release actually happened.
	}

	@Test
	public void scenario7_aSecondCloseSendsNothingFurther() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		client.close();
		int afterFirst = wire.clientWire().datagramsAccepted();

		client.close();
		client.close();

		assertEquals("close() is idempotent", afterFirst, wire.clientWire().datagramsAccepted());
		assertEquals(QuicConnectionState.CLOSING, client.state());
	}

	@Test
	public void aCloseWithAChosenCodeAndReasonUsesThem() throws Exception {
		wire.handshake(settings());
		wire.client().closeWith(QuicTransportErrors.APPLICATION_ERROR, "no longer needed");
		wire.pump();

		PeerClose peerClose = wire.server().peerClose();
		assertNotNull("the server never saw the close", peerClose);
		assertEquals(QuicTransportErrors.APPLICATION_ERROR, peerClose.errorCode());
	}

	// ---------------------------------------------------------------- scenario 2: rate-limited re-send

	@Test
	public void scenario2_aPacketArrivingWithinOneProbeTimeoutDoesNotEarnAnotherConnectionClose() throws Exception {
		wire.handshake(settings());
		QuicConnection server = wire.server();
		// A datagram the client will send but never see answered, kept back so it can be delivered into
		// the server's closing state.
		wire.client().close();
		server.close();
		int afterClose = wire.serverWire().datagramsAccepted();

		assertTrue("nothing to deliver into the closing state", wire.deliverToServer());

		// RFC 9000 §10.2.1: at most one CONNECTION_CLOSE per probe timeout, and no time has passed.
		assertEquals(afterClose, wire.serverWire().datagramsAccepted());
		assertEquals(0, server.closeResends());
	}

	@Test
	public void scenario2_aPacketArrivingAfterOneProbeTimeoutEarnsExactlyOneMore() throws Exception {
		wire.handshake(settings());
		QuicConnection server = wire.server();
		wire.client().close();
		server.close();
		int afterClose = wire.serverWire().datagramsAccepted();

		// One probe timeout is a third of the closing period, so this stays inside the period.
		loop.advance(server.closingPeriodMillis() / 3 + 1);
		assertEquals("the closing period ended early", QuicConnectionState.CLOSING, server.state());

		assertTrue(wire.deliverToServer());
		assertEquals(afterClose + 1, wire.serverWire().datagramsAccepted());
		assertEquals(1, server.closeResends());
	}

	// ---------------------------------------------------------------- scenario 3: peer close

	@Test
	public void scenario3_aReceivedConnectionCloseDrainsAndSurfacesWhatThePeerSaid() throws Exception {
		wire.handshake(settings());
		wire.client().closeWith(QuicTransportErrors.PROTOCOL_VIOLATION, "unacceptable");
		int serverSentBefore = wire.serverWire().datagramsAccepted();
		wire.pump();

		QuicConnection server = wire.server();
		assertEquals(QuicConnectionState.DRAINING, server.state());
		// RFC 9000 §10.2.2: a draining endpoint sends nothing at all — not even a CONNECTION_CLOSE of
		// its own, which is the whole difference from closing.
		assertEquals(serverSentBefore, wire.serverWire().datagramsAccepted());

		PeerClose peerClose = server.peerClose();
		assertNotNull(peerClose);
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, peerClose.errorCode());
		assertFalse("a transport close, not an application one", peerClose.isApplication());
		assertEquals(0, peerClose.frameType());
	}

	@Test
	public void scenario3_aDrainingConnectionIgnoresFurtherDatagramsAndThenClosesForGood() throws Exception {
		wire.handshake(settings());
		wire.client().closeWith(QuicTransportErrors.NO_ERROR, "goodbye");
		wire.pump();
		QuicConnection server = wire.server();
		assertEquals(QuicConnectionState.DRAINING, server.state());
		long dropped = server.packetsDropped();
		int sent = wire.serverWire().datagramsAccepted();

		// Anything at all: draining means it is not even looked at, so it need not be a real packet.
		ByteBuf replay = ByteBufPool.allocate(32);
		replay.tail(32);
		server.onDatagram(replay);
		assertEquals("a draining connection must not parse or answer", sent,
			wire.serverWire().datagramsAccepted());
		assertEquals(dropped, server.packetsDropped());

		loop.advance(server.closingPeriodMillis() + 1);
		assertEquals(QuicConnectionState.CLOSED, server.state());
	}

	@Test
	public void aPeersReasonPhraseIsBoundedBeforeItIsSurfaced() throws Exception {
		wire.handshake(settings());
		// A reason phrase far longer than anything worth reading, which a peer is free to send.
		StringBuilder reason = new StringBuilder();
		while (reason.length() < QuicConnection.MAX_SURFACED_REASON_BYTES * 4) {
			reason.append("overlong-");
		}
		wire.client().closeWith(QuicTransportErrors.INTERNAL_ERROR, reason.toString());
		wire.pump();

		PeerClose peerClose = wire.server().peerClose();
		assertNotNull(peerClose);
		// FR-031: bounded, not rejected — refusing to decode it would hide why the peer closed.
		assertTrue("the reason phrase was surfaced unbounded: " + peerClose.reason().length(),
			peerClose.reason().length() <= QuicConnection.MAX_SURFACED_REASON_BYTES);
	}

	// ---------------------------------------------------------------- scenario 4: idle timeout

	@Test
	public void scenario4_anIdleConnectionTimesOutSilently() throws Exception {
		wire.handshake(settings(Duration.ofSeconds(5)));
		QuicConnection client = wire.client();
		assertTrue("the idle timer is not armed on an established connection", client.isIdleTimerArmed());

		long timeout = client.effectiveIdleTimeoutMillis();
		int clientSentBefore = wire.clientWire().datagramsAccepted();

		loop.advance(timeout + 1);

		assertEquals(QuicConnectionState.CLOSED, client.state());
		// RFC 9000 §10.1: silent. The peer is left to reach its own conclusion — which is exactly why a
		// CONNECTION_CLOSE here would be wrong: there is no evidence anyone is listening. Probes may
		// have gone out in the meantime, never a close, so the server never learns a reason.
		wire.pump();
		assertNull(wire.server().peerClose());
		assertTrue(wire.clientWire().datagramsAccepted() >= clientSentBefore);
	}

	@Test
	public void scenario4_theIdleTimeoutIsTheSmallerAdvertisedValueFlooredAtThreeProbeTimeouts() throws Exception {
		// A 10 ms timeout is below 3 × PTO, so the floor wins: a path slow enough that a probe has not
		// yet been answered must not be torn down for being quiet, however small a timeout was asked for.
		wire.handshake(settings(Duration.ofMillis(10)));
		QuicConnection client = wire.client();

		long floor = client.closingPeriodMillis();
		assertTrue("3 × PTO came out as " + floor + " ms, which is not above the 10 ms asked for", floor > 10);
		assertEquals(floor, client.effectiveIdleTimeoutMillis());
	}

	@Test
	public void anAdvertisedIdleTimeoutAboveTheFloorIsUsedAsIs() throws Exception {
		wire.handshake(settings(Duration.ofSeconds(5)));
		QuicConnection client = wire.client();

		// Both peers advertised 5 s and 3 × PTO is well below it, so the negotiated value stands.
		assertTrue(client.closingPeriodMillis() < 5000);
		assertEquals(5000, client.effectiveIdleTimeoutMillis());
	}

	@Test
	public void aZeroIdleTimeoutOnBothSidesDisablesTheTimerEntirely() throws Exception {
		wire.handshake(settings(Duration.ZERO));
		QuicConnection client = wire.client();

		// RFC 9000 §18.2: 0 means "no limit from this endpoint", and with neither endpoint imposing one
		// the connection simply never idles out.
		assertEquals(0, client.effectiveIdleTimeoutMillis());
		assertFalse(client.isIdleTimerArmed());
	}

	@Test
	public void aReceivedDatagramRestartsTheIdleTimer() throws Exception {
		wire.handshake(settings(Duration.ofSeconds(5)));
		QuicConnection client = wire.client();
		QuicConnection server = wire.server();
		long timeout = client.effectiveIdleTimeoutMillis();

		// Two thirds of the way to the deadline, then traffic, then two thirds again: past the original
		// deadline but not past the restarted one. A busy connection must not die on a schedule.
		loop.advance(timeout * 2 / 3);
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		server.closeWith(QuicTransportErrors.NO_ERROR, "just to generate a datagram");
		// pump rather than one delivery: probe timeouts fired while the clock advanced, so the server's
		// queue holds those probes ahead of the close.
		wire.pump();

		loop.advance(timeout * 2 / 3);
		// The client ended by draining on the peer's close and then running out its draining period —
		// having a peerClose at all is what proves the received datagram, not the idle timer, decided
		// its fate. The idle timer would have fired silently, leaving this null.
		assertNotNull("the connection idled out instead of draining", client.peerClose());
	}

	// ---------------------------------------------------------------- scenario 5: keep-alive

	@Test
	public void scenario5_aKeepAlivePingPreventsTheIdleTimeout() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxIdleTimeout(Duration.ofSeconds(10))
			.withKeepAliveInterval(Duration.ofSeconds(2))
			.build();
		wire.handshake(settings);
		QuicConnection client = wire.client();
		assertTrue("the keep-alive timer is not armed", client.isKeepAliveArmed());

		long timeout = client.effectiveIdleTimeoutMillis();
		// Well past the idle timeout, in keep-alive-sized steps with delivery in between — which is what
		// a live path looks like.
		for (int i = 0; i < 8; i++) {
			loop.advance(2000);
			wire.pump();
		}

		assertTrue("the connection idled out despite the keep-alive: " + client.state(),
			client.state() == QuicConnectionState.ESTABLISHED);
		assertTrue("no keep-alive PING was sent within " + timeout + " ms", client.keepAlivesSent() > 0);
	}

	@Test
	public void aKeepAliveIntervalAboveHalfTheIdleTimeoutIsRefusedAtBuildTime() {
		// FR-025: a keep-alive that cannot beat the timeout it exists to prevent is a configuration bug,
		// and one whose symptom — a connection dying anyway — points nowhere near its cause.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
			QuicConnectionSettings.builder()
				.withMaxIdleTimeout(Duration.ofSeconds(10))
				.withKeepAliveInterval(Duration.ofSeconds(6))
				.build());
		assertTrue(e.getMessage().contains("keepAliveInterval"));
	}

	@Test
	public void keepAliveIsOffUnlessConfigured() throws Exception {
		wire.handshake(settings());
		assertFalse(wire.client().isKeepAliveArmed());
		assertEquals(0, wire.client().keepAlivesSent());
	}

	// ---------------------------------------------------------------- scenario 6: close mid-processing

	@Test
	public void scenario6_closingWhileADatagramIsBeingProcessedLeavesNoTimerAndNoBuffer() throws Exception {
		wire.handshake(settings());
		QuicConnection server = wire.server();
		// The client closes; delivering that datagram drives the server's close from *inside* its own
		// receive path, which is the re-entrant case (frames in hand, a flush pending).
		wire.client().closeWith(QuicTransportErrors.FRAME_ENCODING_ERROR, "mid-processing");
		assertTrue(wire.deliverToServer());

		assertEquals(QuicConnectionState.DRAINING, server.state());
		assertFalse(server.isLossTimerArmed());
		assertFalse(server.isIdleTimerArmed());
		assertFalse(server.isKeepAliveArmed());
		assertFalse(server.isHandshakeDeadlineArmed());
		assertEquals(0, server.packetsAwaitingKeys());

		loop.advance(server.closingPeriodMillis() + 1);
		assertEquals(QuicConnectionState.CLOSED, server.state());
	}

	@Test
	public void closeNowSkipsTheClosingPeriodButStillTellsThePeer() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		int sentBefore = wire.clientWire().datagramsAccepted();

		client.closeNow();

		assertEquals(QuicConnectionState.CLOSED, client.state());
		assertFalse(client.isClosingPeriodArmed());
		assertEquals("the peer still gets exactly one CONNECTION_CLOSE", sentBefore + 1,
			wire.clientWire().datagramsAccepted());
		wire.pump();
		assertNotNull(wire.server().peerClose());
	}
}
