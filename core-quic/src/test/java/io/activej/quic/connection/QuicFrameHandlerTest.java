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
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicTransportParameters;
import org.jetbrains.annotations.Nullable;
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
 * T073 / SC-012 — the {@link QuicFrameHandler} contract: which frames reach it, which never do, what it
 * can contribute, and what its exceptions do to the connection.
 */
public final class QuicFrameHandlerTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Records what it was given, and recycles everything it is handed ownership of. */
	private static final class RecordingHandler implements QuicFrameHandler {
		private final List<String> received = new ArrayList<>();
		private final List<String> acknowledged = new ArrayList<>();
		private final List<String> lost = new ArrayList<>();
		private int established;
		private int closed;
		private @Nullable RuntimeException throwOnFrame;
		private @Nullable QuicTransportException failOnFrame;

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			received.add(frame.getClass().getSimpleName());
			if (failOnFrame != null) throw failOnFrame;
			if (throwOnFrame != null) throw throwOnFrame;
		}

		@Override
		public void onFrameAcknowledged(QuicConnection connection, QuicFrame frame) {
			acknowledged.add(frame.getClass().getSimpleName());
			Recyclers.recycle(frame);
		}

		@Override
		public void onFrameLost(QuicConnection connection, QuicFrame frame) {
			lost.add(frame.getClass().getSimpleName());
			Recyclers.recycle(frame);
		}

		@Override
		public void onEstablished(QuicConnection connection) {
			established++;
		}

		@Override
		public void onClosed(QuicConnection connection) {
			closed++;
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private final RecordingHandler handler = new RecordingHandler();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		// The client always carries a handler of its own: enqueueFrame requires one, since a connection
		// with no layer above it has nothing that could legitimately contribute a frame. Which handler the
		// *server* carries is what each test varies.
		wire = new QuicWirePair().withClientFrameHandler(new RecordingHandler());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	/** An established pair whose <b>server</b> carries the handler under test. */
	private void handshakeWithHandler() throws Exception {
		wire.handshakeWithServerFrameHandler(QuicConnectionSettings.create(), handler);
	}

	/**
	 * Delivers one frame to the server inside a 1-RTT packet built by the client.
	 * <p>
	 * The client carries a handler of its own — {@link QuicConnection#enqueueFrame} requires one, since a
	 * connection with no layer above it has nothing that could legitimately contribute a frame.
	 */
	private void clientSends(QuicFrame frame) throws Exception {
		wire.client().enqueueFrame(frame);
		wire.client().requestSend();
		wire.pump();
	}

	// ---------------------------------------------------------------- what reaches the handler

	@Test
	public void aStreamFrameReachesTheHandler() throws Exception {
		handshakeWithHandler();

		ByteBuf data = ByteBufPool.allocate(4);
		data.put(new byte[]{1, 2, 3, 4});
		clientSends(new StreamFrame(0, 0, true, data));

		assertEquals(List.of("StreamFrame"), handler.received);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@Test
	public void theTransportFramesNeverReachTheHandler() throws Exception {
		handshakeWithHandler();
		// The handshake itself carried CRYPTO, ACK, PADDING and HANDSHAKE_DONE in both directions.
		assertEquals(List.of(), handler.received);

		clientSends(PingFrame.INSTANCE);

		// PING is ack-eliciting and nothing else; intercepting it would give a handler a way to break the
		// transport's own liveness accounting.
		assertEquals(List.of(), handler.received);
	}

	@Test
	public void toleratedTransportFramesAreIgnoredRatherThanRouted() throws Exception {
		handshakeWithHandler();

		clientSends(new MaxDataFrame(1 << 20));

		// Flow-control credit for a connection that never sends application data is information with
		// nowhere to go. RFC 9000 §12.4 permits ignoring it, and real peers send these unprompted — so
		// routing or rejecting them would break interoperability rather than enforce anything.
		assertEquals(List.of(), handler.received);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	// ---------------------------------------------------------------- no handler registered

	@Test
	public void anApplicationFrameWithNoHandlerIsAProtocolViolation() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection server = wire.server();

		ByteBuf data = ByteBufPool.allocate(1);
		data.put(new byte[]{7});
		clientSends(new StreamFrame(0, 0, true, data));

		// FR-037: this connection advertised zero streams, so a peer sending stream data exceeded a limit
		// it was told about — silence would leave it waiting for a response that can never come.
		assertTrue("expected the server to close, it is " + server.state(), server.state().isTerminating());
		assertNotNull(wire.client().peerClose());
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, wire.client().peerClose().errorCode());
	}

	// ---------------------------------------------------------------- contributing frames

	@Test
	public void aContributedFrameAppearsInAnOutgoingPacketAndIsAcknowledged() throws Exception {
		handshakeWithHandler();
		QuicConnection server = wire.server();

		ByteBuf data = ByteBufPool.allocate(3);
		data.put(new byte[]{9, 9, 9});
		server.enqueueFrame(new StreamFrame(4, 0, true, data));
		server.requestSend();
		wire.pump();

		// A single ack-eliciting 1-RTT packet is acknowledged only once max_ack_delay elapses (RFC 9000
		// §13.2.1) — the two-packet trigger has not been reached — so the clock has to move before the
		// acknowledgement exists to be delivered.
		loop.advance(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY + 5);
		wire.pump();

		assertEquals(List.of("StreamFrame"), handler.acknowledged);
	}

	@Test
	public void enqueueFrameWithoutAHandlerIsRefusedAndRecyclesTheFrame() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection server = wire.server();

		ByteBuf data = ByteBufPool.allocate(2);
		data.put(new byte[]{1, 2});
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> server.enqueueFrame(new StreamFrame(0, 0, true, data)));

		assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		// ByteBufRule proves the frame's buffer was recycled rather than dropped on the floor: the caller
		// had already handed over ownership by the time the refusal happened.
	}

	// ---------------------------------------------------------------- the handler's failures (FR-038a)

	@Test
	public void aHandlersTransportExceptionClosesWithItsOwnCode() throws Exception {
		handshakeWithHandler();
		handler.failOnFrame = new QuicTransportException(
			QuicTransportErrors.STREAM_LIMIT_ERROR, "too many streams");

		ByteBuf data = ByteBufPool.allocate(1);
		data.put(new byte[]{1});
		clientSends(new StreamFrame(0, 0, true, data));

		// FR-038a: the handler's code, not a generic one — only the handler knows what the peer violated,
		// and flattening it would leave the peer unable to distinguish a limit breach from a crash.
		assertNotNull(wire.client().peerClose());
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, wire.client().peerClose().errorCode());
	}

	@Test
	public void anyOtherExceptionFromTheHandlerClosesWithInternalError() throws Exception {
		handshakeWithHandler();
		handler.throwOnFrame = new IllegalStateException("handler bug");

		ByteBuf data = ByteBufPool.allocate(1);
		data.put(new byte[]{1});
		clientSends(new StreamFrame(0, 0, true, data));

		// The handler's own bug. Its state is unknown afterwards, so continuing would be worse than
		// closing — and reporting the peer's code would blame the wrong party.
		assertNotNull(wire.client().peerClose());
		assertEquals(QuicTransportErrors.INTERNAL_ERROR, wire.client().peerClose().errorCode());
	}

	// ---------------------------------------------------------------- lifecycle

	@Test
	public void theHandlerIsToldWhenTheConnectionIsEstablishedAndWhenItEnds() throws Exception {
		handshakeWithHandler();
		assertEquals(1, handler.established);
		assertEquals(0, handler.closed);

		wire.server().closeNow();

		assertEquals("onClosed must fire exactly once", 1, handler.closed);
		wire.server().closeNow();
		assertEquals(1, handler.closed);
	}
}
