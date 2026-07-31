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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.inspector.AbstractInspector;
import io.activej.common.recycle.Recyclers;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T075 / T076 — the diagnostics surface of FR-034 and FR-036: the {@code Inspector} hooks on both the
 * connection and the endpoint, and the qlog-vocabulary debug logging that reports the same six events.
 * <p>
 * The two seams are asserted against each other and against the plain counters, because their whole
 * value is that they agree: an inspector that misses a loss the counter saw would send a JMX consumer
 * looking for a bug in the wrong layer. The log assertions check the qlog event <i>names</i>, since the
 * vocabulary is the interoperable part — a qlog reader keys on {@code recovery:packet_lost}, not on the
 * English around it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-B">RFC 9002 §B — the recovery variables reported here</a>
 */
public final class QuicDiagnosticsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private record PacketEvent(EncryptionLevel level, long packetNumber, int sizeInBytes) {}

	private record Transition(QuicConnectionState from, QuicConnectionState to) {}

	/**
	 * Extends {@link AbstractInspector} rather than implementing {@link QuicConnection.Inspector} bare —
	 * that is the {@code util-common} base the FR-036 seam is built on, and {@code lookup} is what a JMX
	 * module would use to find its own implementation behind a composite one.
	 */
	private static final class RecordingConnectionInspector
		extends AbstractInspector<QuicConnection.Inspector> implements QuicConnection.Inspector {

		private final List<PacketEvent> sent = new ArrayList<>();
		private final List<PacketEvent> received = new ArrayList<>();
		private final List<PacketEvent> lost = new ArrayList<>();
		private final List<Transition> transitions = new ArrayList<>();
		private final List<String> congestionTransitions = new ArrayList<>();
		private int metricsUpdates;

		@Override
		public void onPacketSent(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes,
			boolean ackEliciting
		) {
			sent.add(new PacketEvent(level, packetNumber, sizeInBytes));
		}

		@Override
		public void onPacketReceived(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes
		) {
			received.add(new PacketEvent(level, packetNumber, sizeInBytes));
		}

		@Override
		public void onPacketLost(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes
		) {
			lost.add(new PacketEvent(level, packetNumber, sizeInBytes));
		}

		@Override
		public void onMetricsUpdated(QuicConnection connection, RttEstimator rtt) {
			metricsUpdates++;
		}

		@Override
		public void onCongestionStateUpdated(
			QuicConnection connection, NewRenoCongestionController.State from,
			NewRenoCongestionController.State to
		) {
			congestionTransitions.add(from + "->" + to);
		}

		@Override
		public void onStateTransition(
			QuicConnection connection, QuicConnectionState from, QuicConnectionState to
		) {
			transitions.add(new Transition(from, to));
		}
	}

	private static final class RecordingEndpointInspector
		extends AbstractInspector<QuicEndpoint.Inspector> implements QuicEndpoint.Inspector {

		private int datagramsReceived;
		private int datagramsDropped;
		private final List<QuicConnection> created = new ArrayList<>();
		private final List<InetSocketAddress> refused = new ArrayList<>();

		@Override
		public void onDatagramReceived(QuicEndpoint endpoint, InetSocketAddress from, int sizeInBytes) {
			assertTrue("a received datagram is never empty", sizeInBytes > 0);
			datagramsReceived++;
		}

		@Override
		public void onDatagramDropped(QuicEndpoint endpoint, InetSocketAddress from, int sizeInBytes) {
			datagramsDropped++;
		}

		@Override
		public void onConnectionCreated(QuicEndpoint endpoint, QuicConnection connection) {
			created.add(connection);
		}

		@Override
		public void onConnectionRefused(QuicEndpoint endpoint, InetSocketAddress from) {
			refused.add(from);
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private final RecordingConnectionInspector clientInspector = new RecordingConnectionInspector();
	private final RecordingConnectionInspector serverInspector = new RecordingConnectionInspector();

	private Logger quicLogger;
	private @Nullable Level originalLevel;
	private @Nullable ListAppender<ILoggingEvent> appender;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair()
			.withClientInspector(clientInspector)
			.withServerInspector(serverInspector);
		quicLogger = (Logger) LoggerFactory.getLogger("io.activej.quic");
	}

	@After
	public void tearDown() {
		if (appender != null) {
			quicLogger.detachAppender(appender);
			quicLogger.setLevel(originalLevel);
		}
		wire.close();
		loop.close();
	}

	/**
	 * Captures the whole {@code io.activej.quic} subtree at DEBUG. {@code test/logback-test.xml} turns
	 * {@code io.activej} off, and logback resolves an effective level from the nearest configured
	 * ancestor — so setting it on the more specific logger is what makes these lines visible.
	 */
	private List<String> captureDebugLog() {
		originalLevel = quicLogger.getLevel();
		quicLogger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
		listAppender.start();
		quicLogger.addAppender(listAppender);
		appender = listAppender;
		return new AbstractList<>() {
			@Override
			public String get(int index) {
				return listAppender.list.get(index).getFormattedMessage();
			}

			@Override
			public int size() {
				return listAppender.list.size();
			}
		};
	}

	private static boolean anyLineContains(List<String> lines, String needle) {
		for (String line : lines) {
			if (line.contains(needle)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- the connection inspector (T075)

	@Test
	public void theConnectionInspectorSeesEveryHandshakePacketAndBothStateTransitions() throws Exception {
		wire.handshake(QuicConnectionSettings.create());

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(
			List.of(new Transition(QuicConnectionState.IDLE, QuicConnectionState.HANDSHAKING),
				new Transition(QuicConnectionState.HANDSHAKING, QuicConnectionState.ESTABLISHED)),
			clientInspector.transitions);
		assertEquals(
			List.of(new Transition(QuicConnectionState.IDLE, QuicConnectionState.HANDSHAKING),
				new Transition(QuicConnectionState.HANDSHAKING, QuicConnectionState.ESTABLISHED)),
			serverInspector.transitions);

		// A handshake spans all three levels in both directions; anything less means the hook is wired
		// into one path only.
		assertFalse(clientInspector.sent.isEmpty());
		assertFalse(clientInspector.received.isEmpty());
		assertFalse(serverInspector.sent.isEmpty());
		assertFalse(serverInspector.received.isEmpty());
		for (PacketEvent event : clientInspector.sent) {
			assertNotNull(event.level());
			assertTrue("a sent packet has a size", event.sizeInBytes() > 0);
			assertTrue("a packet number is never negative", event.packetNumber() >= 0);
		}
		for (PacketEvent event : clientInspector.received) {
			assertTrue("a received packet has a size", event.sizeInBytes() > 0);
		}

		// One RTT sample per acknowledged flight; a handshake produces at least one on each side.
		assertTrue(clientInspector.metricsUpdates > 0);
		assertTrue(serverInspector.metricsUpdates > 0);
	}

	@Test
	public void theInspectorSeesEveryPacketNumberExactlyOnceAndNoDuplicates() throws Exception {
		wire.handshake(QuicConnectionSettings.create());

		// A packet number is unique within its space, and the receive event fires only for a *new*
		// packet — so a repeat here would mean a duplicate was processed twice (FR-011).
		List<String> keys = new ArrayList<>();
		for (PacketEvent event : serverInspector.received) {
			String key = event.level() + "#" + event.packetNumber();
			assertFalse("the same packet was reported received twice: " + key, keys.contains(key));
			keys.add(key);
		}
		assertFalse(keys.isEmpty());
	}

	@Test
	public void aConnectionWithoutAnInspectorStillKeepsEveryCounter() throws Exception {
		try (QuicWirePair bare = new QuicWirePair()) {
			bare.handshake(QuicConnectionSettings.create());
			// FR-036's "defaulting to none": the hook is additive, never the thing that makes the
			// counters work.
			assertTrue(bare.client().datagramsSent() > 0);
			assertTrue(bare.client().datagramsReceived() > 0);
			assertEquals(QuicConnectionState.ESTABLISHED, bare.client().state());
		}
	}

	@Test
	public void theInspectorSeesTheTransitionIntoClosing() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		wire.client().closeWith(QuicTransportErrors.NO_ERROR, "done");

		assertEquals(new Transition(QuicConnectionState.ESTABLISHED, QuicConnectionState.CLOSING),
			clientInspector.transitions.get(clientInspector.transitions.size() - 1));
	}

	// ---------------------------------------------------------------- loss and congestion (T075)

	/**
	 * Loses exactly one 1-RTT packet and then acknowledges enough later ones to trip RFC 9002 §6.1.1's
	 * packet-number threshold.
	 * <p>
	 * Deliberately not a seeded lossy network: threshold loss detection during the <i>handshake</i>
	 * hardly ever fires — an ACK can only declare a packet lost once a later packet in the same space
	 * has been acknowledged, which the handshake's lockstep flights rarely produce, so the handshake is
	 * carried by probe timeouts instead (and a probe is not loss, RFC 9002 §6.2). Losing a packet after
	 * establishment is what exercises the declaration path itself, and it does so on every run rather
	 * than on some seeds.
	 */
	private void loseOneEstablishedPacket() throws Exception {
		wire.withClientFrameHandler(new CountingHandler()).withServerFrameHandler(new CountingHandler());
		wire.handshake(QuicConnectionSettings.create());
		assertEquals(0, wire.client().packetsLost());

		wire.clientWire().blackhole(true);
		clientSendsStream(0);
		wire.clientWire().blackhole(false);
		for (int i = 1; i <= LossDetector.PACKET_THRESHOLD + 1; i++) {
			clientSendsStream(i * 4L);
		}
	}

	/** One ack-eliciting 1-RTT packet from the client, delivered and acknowledged. */
	private void clientSendsStream(long streamId) throws Exception {
		ByteBuf data = ByteBufPool.allocate(4);
		data.put(new byte[]{1, 2, 3, 4});
		wire.client().enqueueFrame(new StreamFrame(streamId, 0, true, data));
		wire.client().requestSend();
		wire.pump();
	}

	/**
	 * The inspector's count of lost packets is exactly {@link QuicConnection#packetsLost()}.
	 * <p>
	 * Equality, not "at least one": the counter is what an operator reads and the hook is what JMX
	 * publishes, and the only useful guarantee is that they cannot disagree.
	 */
	@Test
	public void theInspectorsLostCountMatchesTheConnectionCounter() throws Exception {
		loseOneEstablishedPacket();

		assertTrue("the packet-number threshold never declared the dropped packet lost",
			wire.client().packetsLost() > 0);
		assertEquals(wire.client().packetsLost(), clientInspector.lost.size());
		for (PacketEvent event : clientInspector.lost) {
			assertEquals(EncryptionLevel.ONE_RTT, event.level());
			assertTrue(event.sizeInBytes() > 0);
		}
	}

	// ---------------------------------------------------------------- the endpoint inspector (T075)

	@Test
	public void theEndpointInspectorSeesDatagramsAcceptedConnectionsAndRefusals() {
		RecordingEndpointInspector inspector = new RecordingEndpointInspector();
		try (QuicEndpointFixture fixture = new QuicEndpointFixture()) {
			QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
				builder -> builder.withMaxConnections(1).withInspector(inspector));

			Promise<QuicConnection> first = fixture.client(QuicConnectionSettings.create())
				.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			fixture.pump();
			assertTrue(first.isResult());
			assertEquals(1, inspector.created.size());
			assertEquals(0, inspector.refused.size());
			assertTrue(inspector.datagramsReceived > 0);

			Promise<QuicConnection> refused = fixture.client(QuicConnectionSettings.create())
				.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			fixture.pump();
			assertFalse(refused.isResult());

			// Both seams agree, which is the point of having both.
			assertEquals(1, inspector.refused.size());
			assertEquals(server.connectionsRejected(), inspector.refused.size());
			assertEquals(server.connectionsAccepted(), inspector.created.size());
			assertEquals(server.datagramsReceived(), inspector.datagramsReceived);
			assertEquals(server.datagramsDropped(), inspector.datagramsDropped);
			// The refused Initial is the datagram that was dropped.
			assertTrue(inspector.datagramsDropped > 0);

			first.getResult().closeNow();
		}
	}

	@Test
	public void anEndpointConnectionInspectorReachesAnAcceptedServerConnection() {
		RecordingConnectionInspector inspector = new RecordingConnectionInspector();
		try (QuicEndpointFixture fixture = new QuicEndpointFixture()) {
			fixture.server(QuicConnectionSettings.create(),
				builder -> builder.withConnectionInspector(inspector));
			Promise<QuicConnection> connecting = fixture.client(QuicConnectionSettings.create())
				.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			fixture.pump();
			assertTrue(connecting.isResult());

			// The accepted connection is built inside the dispatch, so this propagation is the only way
			// its events are observable at all.
			assertEquals(
				List.of(new Transition(QuicConnectionState.IDLE, QuicConnectionState.HANDSHAKING),
					new Transition(QuicConnectionState.HANDSHAKING, QuicConnectionState.ESTABLISHED)),
				inspector.transitions);
			assertFalse(inspector.sent.isEmpty());
			assertFalse(inspector.received.isEmpty());

			connecting.getResult().closeNow();
		}
	}

	// ---------------------------------------------------------------- the qlog vocabulary (T076)

	@Test
	public void theHandshakeLogsThePacketAndMetricsEventsUnderTheirQlogNames() throws Exception {
		List<String> lines = captureDebugLog();
		wire.handshake(QuicConnectionSettings.create());

		assertTrue("no transport:packet_sent event", anyLineContains(lines, "qlog transport:packet_sent"));
		assertTrue("no transport:packet_received event",
			anyLineContains(lines, "qlog transport:packet_received"));
		assertTrue("no recovery:metrics_updated event",
			anyLineContains(lines, "qlog recovery:metrics_updated"));
	}

	@Test
	public void lossIsLoggedUnderItsQlogName() throws Exception {
		List<String> lines = captureDebugLog();
		loseOneEstablishedPacket();

		assertTrue("no packet was declared lost; the assertion below would be vacuous",
			wire.client().packetsLost() > 0);
		assertTrue("no recovery:packet_lost event", anyLineContains(lines, "qlog recovery:packet_lost"));
	}

	/**
	 * FR-034's second half, SI-6: no log line carries a frame payload.
	 * <p>
	 * A stream payload is the one piece of plaintext the connection layer handles that is neither key
	 * material (already covered by {@code QuicSecretsStayOutOfLogsTest}) nor protocol metadata, and the
	 * events added for T076 are the newest place it could have escaped — {@code packet_sent} runs with
	 * the assembled frame list in scope.
	 */
	@Test
	public void noLogLineContainsAFramePayload() throws Exception {
		byte[] marker = {(byte) 0xC0, (byte) 0xFF, (byte) 0xEE, 0x42, 0x13, 0x37, (byte) 0xAB, (byte) 0xCD};
		String markerHex = HexFormat.of().formatHex(marker);
		String markerText = new String(marker, StandardCharsets.ISO_8859_1);

		List<String> lines = captureDebugLog();
		wire.withClientFrameHandler(new CountingHandler()).withServerFrameHandler(new CountingHandler());
		wire.handshake(QuicConnectionSettings.create());

		ByteBuf data = ByteBufPool.allocate(marker.length);
		data.put(marker);
		wire.client().enqueueFrame(new StreamFrame(0, 0, true, data));
		wire.client().requestSend();
		wire.pump();

		assertFalse("the exchange produced no log lines to check", lines.isEmpty());
		for (String line : lines) {
			String lower = line.toLowerCase();
			assertFalse("a log line leaked a frame payload: " + line, lower.contains(markerHex));
			assertFalse("a log line leaked a frame payload: " + line, line.contains(markerText));
		}
	}

	/** Accepts and recycles whatever it is given; the payload test only needs a registered handler. */
	private static final class CountingHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			// The frame is the connection's; this handler exists only so enqueueFrame is legal.
		}

		@Override
		public void onFrameAcknowledged(QuicConnection connection, QuicFrame frame) {
			Recyclers.recycle(frame);
		}

		@Override
		public void onFrameLost(QuicConnection connection, QuicFrame frame) {
			Recyclers.recycle(frame);
		}
	}
}
