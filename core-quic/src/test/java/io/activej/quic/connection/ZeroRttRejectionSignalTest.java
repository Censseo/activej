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

import io.activej.quic.codec.QuicFrame;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T093a, spec FR-055 — the transport's half of the rejection path: the connection tells the layer
 * above that the early data it sent was refused, <b>once</b>, and only once its own state has already
 * moved.
 *
 * <h2>What "already moved" means, and why it is asserted rather than assumed</h2>
 * The architecture registry names "letting a completion promise fail before the state has moved" as an
 * anti-pattern, and this callback is the point where it would bite: a handler is going to tear down
 * streams and re-create work from inside it, and anything it enqueues must land at {@code ONE_RTT}. So
 * the recorder below captures, at the instant of the call, whether the 0-RTT keys were already
 * discarded — a rejection reported while they still existed would let a re-issued request go back out
 * in a 0-RTT packet the server has already said it will not read.
 *
 * <h2>When it must stay silent</h2>
 * A full handshake, a resumption that offered no early data, an <i>accepted</i> offer, and the server
 * side of any of them. Each of those is a case where a handler acting on the signal would discard
 * stream state that is perfectly alive.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.10">RFC 8446 §4.2.10 — Early Data Indication</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.9.3">RFC 9001 §4.9.3 — Discarding 0-RTT Keys</a>
 */
public final class ZeroRttRejectionSignalTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private QuicWirePair wire;

	private final Recorder clientHandler = new Recorder();
	private final Recorder serverHandler = new Recorder();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		clientHandler.reset();
		serverHandler.reset();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		wire = null;
		loop.close();
	}

	/**
	 * The plain rejection: the client offered early data, the server resumed the session and omitted
	 * {@code early_data} (RFC 8446 §4.2.10), and the client's handler is told exactly once.
	 */
	@Test
	public void aRefusedEarlyDataOfferNotifiesTheClientHandlerExactlyOnce() throws Exception {
		handshakeOffering(true, false);

		assertTrue("the session did not resume, so nothing about early data was ever decided",
			wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());
		assertEquals(1, clientHandler.rejections.size());
	}

	/**
	 * The ordering rule of this phase, stated as an assertion: by the time the handler runs, the 0-RTT
	 * keys are gone and {@link QuicConnection#isSendingEarlyData()} is false — so anything the handler
	 * enqueues from inside the callback is queued for 1-RTT.
	 * <p>
	 * And it runs <b>before</b> establishment, deliberately. Establishment is what flushes: the stream
	 * layer widens its limits inside a batch and that batch ends in a {@code requestSend()}. A handler
	 * told any later would find the frames it means to discard already on the wire at 1-RTT, under stream
	 * ids the peer never saw — which is the retransmission this whole path exists to replace with a
	 * re-creation. "After the state has moved" is about <i>this connection's</i> 0-RTT state, and that has
	 * moved in full: keys discarded, queue re-levelled.
	 */
	@Test
	public void theNotificationArrivesOnlyAfterTheZeroRttStateHasMoved() throws Exception {
		handshakeOffering(true, false);

		assertEquals(1, clientHandler.rejections.size());
		Snapshot snapshot = clientHandler.rejections.get(0);
		assertTrue("the handler was told while the 0-RTT keys were still installed", snapshot.zeroRttDiscarded);
		assertFalse("the handler was told while early data would still leave in a 0-RTT packet",
			snapshot.sendingEarlyData);
		assertEquals("the handler was told after establishment, by which time the re-levelled 0-RTT frames" +
					 " have already been flushed",
			QuicConnectionState.HANDSHAKING, snapshot.state);
	}

	/** An accepted offer is not a rejection; a handler acting on one would discard live stream state. */
	@Test
	public void anAcceptedEarlyDataOfferNotifiesNothing() throws Exception {
		handshakeOffering(true, true);

		assertTrue(wire.client().isEarlyDataAccepted());
		assertTrue(clientHandler.rejections.isEmpty());
	}

	/**
	 * A resumption that offered no early data has nothing to reject. The distinction matters because
	 * {@code isEarlyDataAccepted()} is false here too — "not accepted" is not "refused".
	 */
	@Test
	public void aResumptionThatOfferedNoEarlyDataNotifiesNothing() throws Exception {
		handshakeOffering(false, true);

		assertTrue(wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());
		assertTrue(clientHandler.rejections.isEmpty());
	}

	/** The default path, unchanged: no ticket, no offer, no signal (SC-011). */
	@Test
	public void aFullHandshakeNotifiesNothing() throws Exception {
		wire = new QuicWirePair()
			.withClientFrameHandler(clientHandler)
			.withServerFrameHandler(serverHandler);
		wire.handshake(QuicConnectionSettings.create());

		assertFalse(wire.client().isSessionResumed());
		assertTrue(clientHandler.rejections.isEmpty());
		assertTrue(serverHandler.rejections.isEmpty());
	}

	/**
	 * A server refuses early data rather than having its own refused, and it never installs 0-RTT
	 * <i>send</i> keys at all (RFC 9001 §4.6.1), so the signal is a client-side one by construction.
	 */
	@Test
	public void theServerIsNeverNotified() throws Exception {
		handshakeOffering(true, false);

		assertTrue(serverHandler.rejections.isEmpty());
	}

	// ---------------------------------------------------------------- harness

	/**
	 * Earns a ticket over a throwaway connection, then runs a resumption in which the client offers
	 * early data (or not) against a server that echoes {@code early_data} (or not).
	 */
	private void handshakeOffering(boolean clientOffers, boolean serverAccepts) throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull("the first handshake issued no ticket, so nothing here resumes", ticket);

		wire = new QuicWirePair()
			.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(serverAccepts))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(clientOffers))
			.withClientRememberedTransportParameters(ticket.transportParameters())
			.withClientFrameHandler(clientHandler)
			.withServerFrameHandler(serverHandler);
		wire.startClient(settings);
		wire.acceptServer(settings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	/** What the connection looked like at the instant its handler was told the offer was refused. */
	private record Snapshot(boolean zeroRttDiscarded, boolean sendingEarlyData, QuicConnectionState state) {}

	private static final class Recorder implements QuicFrameHandler {
		private final List<Snapshot> rejections = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
		}

		@Override
		public void onEarlyDataRejected(QuicConnection connection) {
			rejections.add(new Snapshot(
				connection.isLevelDiscarded(EncryptionLevel.ZERO_RTT),
				connection.isSendingEarlyData(),
				connection.state()));
		}

		void reset() {
			rejections.clear();
		}
	}
}
