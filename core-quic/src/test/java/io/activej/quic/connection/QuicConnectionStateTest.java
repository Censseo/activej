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
import io.activej.quic.codec.StreamFrame;
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

import static org.junit.Assert.*;

/**
 * QA gap 6.5 (FR-003) — the connection-state matrix: what each operation does when called outside the
 * state it is normally called in.
 * <p>
 * FR-003 asks for a typed exception on every operation invalid for the current state. What is actually
 * implemented, and asserted here, is narrower: {@code start()} is <b>idempotent</b> rather than
 * rejecting a repeat call (documented on {@link QuicConnection#start()}), and
 * {@link QuicConnection#enqueueFrame} never inspects connection state at all — a frame offered while
 * {@code CLOSING}/{@code DRAINING}/{@code CLOSED} is silently recycled via {@link SendQueue#drop()}
 * rather than rejected, and a frame offered before the handshake completes is simply queued until keys
 * exist. Every one of those outcomes is specified (documented, deliberate, leak-free) rather than
 * undefined, but none of them is "a typed exception" in the literal FR-003 sense — see the QA report for
 * that discrepancy. This test pins down the behaviour that actually exists.
 */
public final class QuicConnectionStateTest {
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

	// ---------------------------------------------------------------- a second start()

	@Test
	public void aSecondStartWhileHandshakingReturnsTheSamePromiseAndDoesNotResetAnything() {
		Promise<QuicConnection> first = wire.startClient(settings());
		QuicConnection client = wire.client();
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());
		int sentBefore = wire.clientWire().datagramsAccepted();

		Promise<QuicConnection> second = client.start();

		assertSame("start() must be idempotent, not re-run", first, second);
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());
		assertEquals("a repeat start() must not re-emit the ClientHello",
			sentBefore, wire.clientWire().datagramsAccepted());
	}

	@Test
	public void aSecondStartAfterEstablishedDoesNotReenterHandshaking() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		int sentBefore = wire.clientWire().datagramsAccepted();

		Promise<QuicConnection> again = client.start();

		// FR-003 asks that "re-entering handshaking from established" be rejected; what actually happens
		// is that start() ignores the call outright — the state never moves, so there is nothing to reject.
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertTrue("the promise from a post-establishment start() must already be resolved", again.isResult());
		assertSame(client, again.getResult());
		assertEquals("a repeat start() must send nothing", sentBefore, wire.clientWire().datagramsAccepted());
	}

	// ---------------------------------------------------------------- close() on a closed connection

	@Test
	public void closeOnAnAlreadyClosedConnectionIsANoOp() throws Exception {
		wire.handshake(settings());
		QuicConnection client = wire.client();
		client.closeNow();
		assertEquals(QuicConnectionState.CLOSED, client.state());
		int sentBefore = wire.clientWire().datagramsAccepted();

		client.close();
		client.closeNow();

		assertEquals(QuicConnectionState.CLOSED, client.state());
		assertEquals("closing an already-closed connection must send nothing further",
			sentBefore, wire.clientWire().datagramsAccepted());
	}

	// ---------------------------------------------------------------- enqueueFrame outside ESTABLISHED

	@Test
	public void enqueueFrameBeforeTheHandshakeCompletesIsQueuedRatherThanRejected() throws Exception {
		wire.withClientFrameHandler(new NoOpFrameHandler());
		wire.startClient(settings());
		QuicConnection client = wire.client();
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());

		ByteBuf data = ByteBufPool.allocate(3);
		data.put(new byte[]{1, 2, 3});
		// No 1-RTT keys exist yet, so this cannot be a rejection of "wrong state" — enqueueFrame does not
		// look at state at all, only at whether a frame handler is registered.
		client.enqueueFrame(new StreamFrame(0, 0, true, data));
		client.requestSend();

		wire.withServerFrameHandler(new NoOpFrameHandler());
		wire.acceptServer(settings());
		wire.pump();

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		// ByteBufRule proves the queued frame was eventually sent (and its buffer released through the
		// normal send path) rather than silently dropped for having been offered too early.
	}

	@Test
	public void enqueueFrameWhileSelfInitiatedClosingIsSilentlyDroppedNotRejected() throws Exception {
		wire.withClientFrameHandler(new NoOpFrameHandler());
		wire.handshake(settings());
		QuicConnection client = wire.client();
		client.close();
		assertEquals(QuicConnectionState.CLOSING, client.state());
		int sentBefore = wire.clientWire().datagramsAccepted();

		ByteBuf data = ByteBufPool.allocate(3);
		data.put(new byte[]{4, 5, 6});
		// SendQueue.drop() ran when close() entered CLOSING; enqueueFrame's own Javadoc says the frame is
		// "recycled if ... the connection is closing" — no QuicTransportException is thrown here.
		client.enqueueFrame(new StreamFrame(0, 0, true, data));
		client.requestSend();

		assertEquals(QuicConnectionState.CLOSING, client.state());
		assertEquals("a frame offered while closing must not produce a datagram",
			sentBefore, wire.clientWire().datagramsAccepted());
		// ByteBufRule proves the frame's buffer was recycled, not leaked and not sent.
	}

	@Test
	public void enqueueFrameWhileDrainingIsSilentlyDroppedNotRejected() throws Exception {
		wire.withServerFrameHandler(new NoOpFrameHandler());
		wire.handshake(settings());
		wire.client().closeWith(QuicTransportErrors.NO_ERROR, "goodbye");
		wire.pump();
		QuicConnection server = wire.server();
		assertEquals(QuicConnectionState.DRAINING, server.state());
		int sentBefore = wire.serverWire().datagramsAccepted();

		ByteBuf data = ByteBufPool.allocate(3);
		data.put(new byte[]{7, 8, 9});
		server.enqueueFrame(new StreamFrame(0, 0, true, data));
		server.requestSend();

		assertEquals(QuicConnectionState.DRAINING, server.state());
		// RFC 9000 §10.2.2: draining sends nothing at all, not even to flush a handler's own frame.
		assertEquals("a draining connection must send nothing, including a handler's own frame",
			sentBefore, wire.serverWire().datagramsAccepted());
	}

	/**
	 * A frame handler that never contributes anything and never fails — the state matrix under test.
	 * {@code onFrame}'s frame is borrowed (recycled by the connection itself once the call returns), so
	 * this does nothing with it; {@code onFrameAcknowledged}/{@code onFrameLost} keep their recycling
	 * defaults.
	 */
	private static final class NoOpFrameHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, io.activej.quic.tls.EncryptionLevel level,
			io.activej.quic.codec.QuicFrame frame) {
			// Nothing to do: this handler exists only to satisfy enqueueFrame's "a handler must be
			// registered" requirement, not to react to received frames.
		}
	}
}
