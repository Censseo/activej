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
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.codec.DatagramFrame;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
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
 * T116 / T128 — RFC 9221 §5: a <b>DATAGRAM frame</b> declared lost is <b>not</b> retransmitted, and its
 * payload is recycled exactly once.
 *
 * <h2>What this asserts, and why it is a characterization test</h2>
 * Nothing in the loss path is new work. A sent DATAGRAM frame is not in
 * {@code QuicConnection.isTransportOwnedFrame}'s list, so it reaches the registered handler on both the
 * acknowledgement and the loss route; {@link QuicFrameHandler#onFrameLost}'s <b>default</b> recycles
 * without re-enqueueing, which is exactly RFC 9221 §5; and {@code requeueLost} re-queues only CRYPTO and
 * HANDSHAKE_DONE. This class asserts that rather than assuming it, and it is written to pass against the
 * code as it stood <i>before</i> this phase so that a later change to any of those three is what breaks
 * it (spec FR-076, research D-7).
 *
 * <h2>Why the loss is staged by hand rather than with a lossy network</h2>
 * A seeded lossy path almost never produces a <i>detected</i> loss, and a test that merely switches one
 * on passes vacuously (feature 03's recipe). Threshold loss needs a <em>later</em> packet in the same
 * number space to be acknowledged (RFC 9002 §6.1.1), so exactly one 1-RTT packet is blackholed and
 * {@link LossDetector#PACKET_THRESHOLD} + 1 further ack-eliciting packets are then delivered.
 *
 * <h2>Terminology</h2>
 * Three things here are called "datagram". This class only ever loses a <b>DATAGRAM frame</b> (RFC 9221),
 * carried inside a QUIC packet, carried inside a <b>UDP datagram</b>. {@code datagramsSent()} on
 * {@link QuicConnection} counts the third of those, never the first.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-5">RFC 9221 §5 — Behavior and Usage</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-6.1">RFC 9002 §6.1 — Detecting Lost Packets</a>
 */
public final class QuicDatagramLossTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int PAYLOAD_LENGTH = 64;

	private ManualEventloop loop;
	private QuicWirePair wire;

	/** What the server was handed, by frame class — never a payload byte (SI-6). */
	private final List<String> serverReceived = new ArrayList<>();

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

	/**
	 * Both sides configured for datagrams, so this reads the same before and after the transport learns
	 * what {@code maxDatagramFrameSize} means: it is a local bound either way.
	 */
	private static QuicConnectionSettings datagramSettings() {
		return QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(
				QuicConnectionSettings.maxDatagramFrameSizeFor(QuicConnectionSettings.create().maxDatagramSize())))
			.build();
	}

	/** Records what arrives and recycles nothing: {@code onFrame} borrows its frame. */
	private final class ServerHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			serverReceived.add(frame.getClass().getSimpleName());
		}
	}

	/** Records the loss and then delegates to the interface default, which is what recycles. */
	private static final class DefaultDelegatingHandler implements QuicFrameHandler {
		private final List<String> lost = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
		}

		@Override
		public void onFrameLost(QuicConnection connection, QuicFrame frame) {
			lost.add(frame.getClass().getSimpleName());
			QuicFrameHandler.super.onFrameLost(connection, frame);
		}
	}

	private void handshakeWith(QuicFrameHandler clientHandler) throws MalformedDataException {
		wire.withClientFrameHandler(clientHandler);
		wire.withServerFrameHandler(new ServerHandler());
		wire.handshake(datagramSettings());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		serverReceived.clear();
	}

	private static ByteBuf payload() {
		ByteBuf buf = ByteBufPool.allocate(PAYLOAD_LENGTH);
		for (int i = 0; i < PAYLOAD_LENGTH; i++) {
			buf.put((byte) (i * 31 + 7));
		}
		return buf;
	}

	/**
	 * Sends one DATAGRAM frame into a black hole and then makes its loss <em>detected</em>: the further
	 * packets are bare PINGs, which the transport owns, so nothing but the blackholed packet can account
	 * for what a handler is told.
	 */
	private void sendOneDatagramFrameAndLoseIt() throws QuicTransportException {
		QuicConnection client = wire.client();
		long lostBefore = client.packetsLost();

		wire.clientWire().blackhole(true);
		client.enqueueFrame(new DatagramFrame(payload()));
		client.requestSend();
		wire.clientWire().blackhole(false);

		for (int i = 0; i <= LossDetector.PACKET_THRESHOLD; i++) {
			client.enqueueFrame(PingFrame.INSTANCE);
			client.requestSend();
			wire.pump();
		}
		assertTrue("the packet-number threshold never declared the blackholed packet lost",
			client.packetsLost() > lostBefore);
	}

	/** Nothing is queued for retransmission, and nothing reached the peer on a later packet. */
	private void assertNothingWasRetransmitted() {
		assertEquals("RFC 9221 §5: a lost DATAGRAM frame must not be re-queued",
			0, wire.client().dropQueuedFrames(frame -> frame instanceof DatagramFrame));
		wire.pump();
		assertEquals("no DATAGRAM frame may cross the wire after the one that was lost",
			List.of(), serverReceived);
	}

	// ---------------------------------------------------------------- the hook itself

	@Test
	public void aLostDatagramFrameReachesTheHandlersLossHookExactlyOnce() throws Exception {
		DefaultDelegatingHandler handler = new DefaultDelegatingHandler();
		handshakeWith(handler);

		sendOneDatagramFrameAndLoseIt();

		assertEquals(List.of("DatagramFrame"), handler.lost);
		assertNothingWasRetransmitted();
	}

	@Test
	public void theUnoverriddenDefaultRecyclesTheLostDatagramFrame() throws Exception {
		// No onFrameLost override at all, so RFC 9221 §5's behaviour is entirely the interface default's.
		// ByteBufRule is what proves the payload was recycled — exactly once, since a double recycle would
		// have thrown inside the loss loop.
		handshakeWith(new QuicFrameHandler() {
			@Override
			public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			}
		});

		sendOneDatagramFrameAndLoseIt();

		assertNothingWasRetransmitted();
	}

	// ---------------------------------------------------------------- the production route

	@Test
	public void theStreamManagerAlsoDropsALostDatagramFrameRatherThanResendingIt() throws Exception {
		// QuicStreamManager is what Http3Server and Http3Client register, so this is the path a real
		// deployment takes: its onFrameLost falls through every stream case to a plain recycle.
		wire.withClientFrameHandlerFactory(connection ->
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(new ServerHandler());
		wire.handshake(datagramSettings());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		serverReceived.clear();

		sendOneDatagramFrameAndLoseIt();

		assertNothingWasRetransmitted();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}
}
