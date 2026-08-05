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
import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.*;
import io.activej.quic.connection.CoalescedPackets.Kind;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T079 — 0-RTT packets on the wire: a client that sends them, a server that reads them, and the one
 * rule that has no exception, <b>a server never sends one</b> (spec FR-052, RFC 9001 §4.6.1).
 * <p>
 * That last rule is asserted <i>negatively</i> rather than by omission. It is true structurally — the
 * server's 0-RTT installation is one-directional, so its send slot is empty — but "we never wrote the
 * code that would do it" is not a test, and the structural guarantee is exactly the kind that a later
 * change to key installation would quietly remove. So one case counts what actually crossed the wire
 * in the server→client direction over a complete 0-RTT exchange, and another asks a server holding
 * live 0-RTT <i>receive</i> keys to send an application frame and checks where it came out.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-17.2.3">RFC 9000 §17.2.3 — 0-RTT packet</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.9.3">RFC 9001 §4.9.3 — Discarding 0-RTT Keys</a>
 */
public final class ZeroRttPacketFlowTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** A client-initiated bidirectional stream (RFC 9000 §2.1). */
	private static final long STREAM_ID = 0;

	private static final byte[] EARLY_PAYLOAD = "GET / early".getBytes(StandardCharsets.UTF_8);

	/** Records what reached it, so a test can say the early data really was processed. */
	private static final class RecordingHandler implements QuicFrameHandler {
		private final List<String> received = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			if (frame instanceof StreamFrame stream) {
				byte[] data = new byte[stream.data.readRemaining()];
				System.arraycopy(stream.data.array(), stream.data.head(), data, 0, data.length);
				received.add(level + ":" + new String(data, StandardCharsets.UTF_8));
			}
		}
	}

	// ---------------------------------------------------------------- the client half

	@Test
	public void theClientSendsAZeroRttPacketAfterTheClientHelloAndBeforeTheHandshakeCompletes() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		RecordingHandler serverHandler = new RecordingHandler();
		try (QuicWirePair pair = resuming(keys, ticket, serverHandler)) {
			pair.startClient(settings);
			assertFalse("0-RTT keys must be installed the moment the ClientHello leaves",
				pair.client().isLevelDiscarded(EncryptionLevel.ZERO_RTT));
			assertTrue(pair.client().isLevelInstalled(EncryptionLevel.ZERO_RTT));

			// The application writes before a single server byte has arrived — the whole point of 0-RTT.
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			pair.acceptServer(settings);

			int zeroRttPackets = ZeroRttWire.deliverToServerCountingZeroRtt(pair);
			assertTrue("no 0-RTT packet reached the server", zeroRttPackets >= 1);
			assertEquals(List.of("ZERO_RTT:" + new String(EARLY_PAYLOAD, StandardCharsets.UTF_8)),
				serverHandler.received);
			pair.pump();
			assertEquals(QuicConnectionState.ESTABLISHED, pair.client().state());
		}
	}

	@Test
	public void theClientDiscardsItsZeroRttKeysOnceOneRttSendKeysArrive() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		try (QuicWirePair pair = resuming(keys, ticket, new RecordingHandler())) {
			pair.startClient(settings);
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			pair.acceptServer(settings);
			pair.pump();

			assertEquals(QuicConnectionState.ESTABLISHED, pair.client().state());
			// RFC 9001 §4.9.3: at the point 1-RTT keys are installed, not at the ServerHello.
			assertTrue("the client kept its 0-RTT keys past the handshake",
				pair.client().isLevelDiscarded(EncryptionLevel.ZERO_RTT));
		}
	}

	// ---------------------------------------------------------------- the negative: a server never sends 0-RTT

	@Test
	public void aServerNeverSendsAZeroRttPacketOverAWholeZeroRttExchange() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		RecordingHandler serverHandler = new RecordingHandler();
		try (QuicWirePair pair = resuming(keys, ticket, serverHandler)) {
			pair.startClient(settings);
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			pair.acceptServer(settings);

			int serverZeroRtt = 0;
			int clientZeroRtt = 0;
			for (int round = 0; round < 8; round++) {
				clientZeroRtt += ZeroRttWire.deliverToServerCountingZeroRtt(pair);
				serverZeroRtt += ZeroRttWire.deliverToClientCountingZeroRtt(pair);
			}
			assertTrue("the exchange never carried 0-RTT at all, so it proves nothing", clientZeroRtt >= 1);
			assertEquals("a server sent a 0-RTT packet (RFC 9001 §4.6.1, FR-052)", 0, serverZeroRtt);
			assertEquals(QuicConnectionState.ESTABLISHED, pair.server().state());
		}
	}

	/**
	 * The same rule from the other side: a server holding live 0-RTT <b>receive</b> keys is asked to
	 * send an application frame while it still has no 1-RTT send keys. It must not reach for the one
	 * level it does hold — the frame waits for 1-RTT, however long that takes.
	 */
	@Test
	public void aServerLevelsItsOwnFramesAtOneRttEvenWhileHoldingZeroRttReceiveKeys() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		RecordingHandler clientHandler = new RecordingHandler();
		RecordingHandler serverHandler = new RecordingHandler();
		try (QuicWirePair pair = resuming(keys, ticket, serverHandler)) {
			pair.withClientFrameHandler(clientHandler);
			pair.startClient(settings);
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			pair.acceptServer(settings);

			// One delivery: the server now holds 0-RTT receive keys and no 1-RTT send keys.
			ZeroRttWire.deliverToServerCountingZeroRtt(pair);
			assertTrue(pair.server().isLevelInstalled(EncryptionLevel.ZERO_RTT));
			assertNotEquals(QuicConnectionState.ESTABLISHED, pair.server().state());

			pair.server().enqueueFrame(new StreamFrame(1, 0, false, wrap("server says hi")));
			pair.server().requestSend();
			assertEquals("the server put an application frame in a 0-RTT packet", 0,
				ZeroRttWire.deliverToClientCountingZeroRtt(pair));

			pair.pump();
			assertEquals(QuicConnectionState.ESTABLISHED, pair.server().state());
			assertEquals("the frame must still arrive, at 1-RTT",
				List.of("ONE_RTT:server says hi"), clientHandler.received);
		}
	}

	// ---------------------------------------------------------------- discard and late arrival

	@Test
	public void aZeroRttPacketArrivingAfterHandshakeCompletionIsDropped() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		RecordingHandler serverHandler = new RecordingHandler();
		try (QuicWirePair pair = resuming(keys, ticket, serverHandler)) {
			pair.startClient(settings);
			pair.acceptServer(settings);

			// Hold one 0-RTT datagram back until the handshake is over, then deliver it late.
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			ByteBuf held = null;
			ByteBuf datagram;
			while ((datagram = pair.clientWire().poll()) != null) {
				if (held == null
					&& ZeroRttWire.classify(datagram, pair.server().localConnectionId().length())
					.getOrDefault(Kind.ZERO_RTT, 0) > 0) {
					held = datagram;
					continue;
				}
				pair.server().onDatagram(datagram);
			}
			assertNotNull("the client sent no 0-RTT packet to hold back", held);
			pair.pump();
			assertEquals(QuicConnectionState.ESTABLISHED, pair.server().state());
			assertTrue("the server kept its 0-RTT keys past the handshake",
				pair.server().isLevelDiscarded(EncryptionLevel.ZERO_RTT));

			long droppedBefore = pair.server().packetsDropped();
			pair.server().onDatagram(held);
			assertTrue("a late 0-RTT packet must be dropped, not processed",
				pair.server().packetsDropped() > droppedBefore);
			assertEquals(List.of(), serverHandler.received);
			assertEquals("a late 0-RTT packet is not an error", QuicConnectionState.ESTABLISHED,
				pair.server().state());
		}
	}

	/**
	 * A client has no 0-RTT <i>receive</i> keys and never will, so a 0-RTT packet arriving at one is
	 * dropped rather than buffered awaiting keys that cannot come — which would otherwise fill the
	 * awaiting-keys list with packets no key will ever open.
	 */
	@Test
	public void aClientDropsAZeroRttPacketInsteadOfBufferingItForeverAwaitingKeys() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		try (QuicWirePair pair = resuming(keys, ticket, new RecordingHandler())) {
			pair.startClient(settings);
			pair.client().enqueueFrame(earlyStreamFrame());
			pair.client().requestSend();
			pair.acceptServer(settings);

			// Reflect the client's own 0-RTT datagram straight back at it.
			ByteBuf reflected = null;
			ByteBuf datagram;
			while ((datagram = pair.clientWire().poll()) != null) {
				if (reflected == null
					&& ZeroRttWire.classify(datagram, pair.server().localConnectionId().length())
					.getOrDefault(Kind.ZERO_RTT, 0) > 0) {
					reflected = datagram;
					continue;
				}
				pair.server().onDatagram(datagram);
			}
			assertNotNull(reflected);
			int bufferedBefore = pair.client().packetsAwaitingKeys();
			long droppedBefore = pair.client().packetsDropped();
			pair.client().onDatagram(reflected);
			assertEquals("a 0-RTT packet must not be buffered on a client", bufferedBefore,
				pair.client().packetsAwaitingKeys());
			assertTrue(pair.client().packetsDropped() > droppedBefore);
			pair.pump();
		}
	}

	// ---------------------------------------------------------------- the frame table (RFC 9000 §12.4)

	@Test
	public void theZeroRttFrameTableIsTheOneRttSetMinusFive() {
		ByteBuf empty = ByteBufPool.allocate(1);
		List<QuicFrame> payloadCarrying = new ArrayList<>();
		try {
			QuicFrame crypto = new CryptoFrame(0, empty.slice());
			QuicFrame newToken = new NewTokenFrame(empty.slice());
			QuicFrame stream = new StreamFrame(0, 0, false, empty.slice());
			payloadCarrying.add(crypto);
			payloadCarrying.add(newToken);
			payloadCarrying.add(stream);

			assertFalse(FrameTypeRules.isAllowed(AckFrame.withoutEcn(0, 0, 0, new long[0], new long[0]),
				EncryptionLevel.ZERO_RTT));
			assertFalse(FrameTypeRules.isAllowed(crypto, EncryptionLevel.ZERO_RTT));
			assertFalse(FrameTypeRules.isAllowed(newToken, EncryptionLevel.ZERO_RTT));
			assertFalse(FrameTypeRules.isAllowed(new PathResponseFrame(new byte[8]), EncryptionLevel.ZERO_RTT));
			assertFalse(FrameTypeRules.isAllowed(HandshakeDoneFrame.INSTANCE, EncryptionLevel.ZERO_RTT));

			assertTrue(FrameTypeRules.isAllowed(PingFrame.INSTANCE, EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new PaddingFrame(1), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(stream, EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new MaxDataFrame(1), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new MaxStreamDataFrame(0, 1), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new MaxStreamsFrame(1, QuicStreamLimitType.BIDIRECTIONAL),
				EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new DataBlockedFrame(1), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new StreamDataBlockedFrame(0, 1), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new StreamsBlockedFrame(1, QuicStreamLimitType.UNIDIRECTIONAL),
				EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new ResetStreamFrame(0, 0, 0), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new StopSendingFrame(0, 0), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(new PathChallengeFrame(new byte[8]), EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(ConnectionCloseFrame.transport(0, 0, new byte[0]),
				EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(ConnectionCloseFrame.application(0, new byte[0]),
				EncryptionLevel.ZERO_RTT));
		} finally {
			for (QuicFrame frame : payloadCarrying) {
				Recyclers.recycle(frame);
			}
			empty.recycle();
		}
	}

	// ---------------------------------------------------------------- helpers

	private static QuicWirePair resuming(QuicTicketKeys keys, QuicSessionTicket ticket,
		QuicFrameHandler serverHandler
	) {
		QuicWirePair pair = new QuicWirePair();
		pair.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(true))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(true))
			.withClientRememberedTransportParameters(ticket.transportParameters())
			.withServerFrameHandler(serverHandler)
			// enqueueFrame needs one registered; a test that cares about what the client received
			// replaces it before startClient.
			.withClientFrameHandler(new RecordingHandler());
		return pair;
	}

	private static StreamFrame earlyStreamFrame() {
		return new StreamFrame(STREAM_ID, 0, false, wrap(new String(EARLY_PAYLOAD, StandardCharsets.UTF_8)));
	}

	private static ByteBuf wrap(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}
}
