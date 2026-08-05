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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StopSendingFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T093b, spec FR-055 — <b>silent</b> discard of a stream created in early data: the work it describes
 * is going to be re-created on a fresh stream, so the one being discarded must leave no trace, on the
 * wire or in this endpoint's accounting.
 *
 * <h2>Why silent</h2>
 * The peer refused the early data, so it never saw the stream: its 0-RTT packets were dropped
 * undecrypted. A {@code RESET_STREAM} or {@code STOP_SENDING} naming it would therefore <i>open</i> a
 * stream at the peer — RFC 9000 §2.1 opens every lower-numbered stream of a type on first mention —
 * for the sole purpose of aborting it, and on an HTTP/3 server that is a request stream created for
 * nothing.
 *
 * <h2>What must nonetheless be exactly right</h2>
 * Every buffer released once, the concurrency credit returned once, the local ordinal <b>not</b>
 * rewound (RFC 9000 §2.1 has no stream-id reuse), and every other stream on the connection untouched —
 * including the ones whose unsent bytes were re-levelled to 1-RTT and are still owed transmission.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.9.3">RFC 9001 §4.9.3 — Discarding 0-RTT Keys</a>
 */
public final class ZeroRttStreamDiscardTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** RFC 9000 §2.1: client-initiated bidirectional streams are spaced four apart. */
	private static final long FIRST = 0;
	private static final long SECOND = 4;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicConnectionSettings settings = QuicConnectionSettings.create();

	private final List<Sentinel> handedOff = new ArrayList<>();
	private final ServerRecorder server = new ServerRecorder();
	private final List<QuicConnection> rejections = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		handedOff.clear();
		server.reset();
		rejections.clear();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		wire = null;
		assertNothingHandedOffWasReleasedTwice();
		for (Sentinel sentinel : handedOff) {
			sentinel.parent.recycle();
		}
		handedOff.clear();
		loop.close();
	}

	// ---------------------------------------------------------------- (a) nothing on the wire

	/**
	 * The whole point: a discarded early-data stream is never mentioned to the peer. The server here
	 * saw no 0-RTT packet at all, so a {@code RESET_STREAM} for stream {@code 0} would be the first it
	 * ever heard of that stream.
	 */
	@Test
	public void aDiscardedStreamPutsNoResetOrStopSendingOnTheWire() throws Exception {
		refusedEarlyData();
		writeEarlyDataOn(openEarly(), "GET /early");
		refuse();

		clientManager.discardStream(FIRST, discardCause());
		wire.pump();

		assertTrue("a discarded early-data stream must not be announced to a peer that never saw it",
			server.aborts.isEmpty());
		assertFalse("the peer was told about the discarded stream", server.streamsMentioned.contains(FIRST));
	}

	// ---------------------------------------------------------------- (b) released exactly once

	/**
	 * The stream leaves the manager's map through the one release funnel, so its concurrency credit
	 * comes back exactly once. A second discard is a no-op rather than a second release — the funnel
	 * refuses to release a stream it has already removed.
	 */
	@Test
	public void aDiscardedStreamIsReleasedOnceAndASecondDiscardIsANoOp() throws Exception {
		refusedEarlyData();
		writeEarlyDataOn(openEarly(), "GET /early");
		refuse();
		assertEquals(1, clientManager.openStreamCount());

		clientManager.discardStream(FIRST, discardCause());
		assertEquals(0, clientManager.openStreamCount());
		assertEquals(0, clientManager.outstandingStreamBytes());

		clientManager.discardStream(FIRST, discardCause());
		assertEquals(0, clientManager.openStreamCount());
	}

	// ---------------------------------------------------------------- (c) buffers released exactly once

	/**
	 * The payload is larger than the remembered {@code initial_max_data} window, so when the refusal
	 * lands part of it has gone out in 0-RTT packets and the rest is still held in the send part. Both
	 * halves are the discarded stream's to release, and the sentinel in {@link #tearDown} is what says
	 * neither was released twice.
	 */
	@Test
	public void aDiscardedStreamReleasesItsSentAndItsWithheldBuffersExactlyOnce() throws Exception {
		refusedEarlyData(windowOf(2048));
		QuicStream stream = openEarly();
		Promise<Void> written = ChannelSuppliers.ofValue(handOff(pattern(16 * 1024)))
			.streamTo(stream.writer());
		refuse();
		assertFalse("the whole payload fitted the remembered window, so nothing was held back",
			written.isComplete());

		clientManager.discardStream(FIRST, discardCause());
		wire.pump();

		assertTrue("the withheld write was left pending on a stream that no longer exists",
			written.isComplete());
	}

	// ---------------------------------------------------------------- (d) sent frames are released, not requeued

	/**
	 * Finding 4 of this phase, asserted directly: a frame of a discarded stream declared lost after the
	 * discard is <b>released</b>, not put back. It has no part to go back to, and re-queueing it would
	 * put the early-data bytes on the wire under a stream id the peer never heard of.
	 */
	@Test
	public void aFrameOfADiscardedStreamIsReleasedRatherThanRequeuedWhenItIsDeclaredLost() throws Exception {
		refusedEarlyData();
		writeEarlyDataOn(openEarly(), "GET /early");
		refuse();
		clientManager.discardStream(FIRST, discardCause());

		clientManager.onFrameLost(wire.client(), new StreamFrame(FIRST, 0, false, handOff("lost".getBytes(UTF_8))));
		wire.pump();

		assertFalse("the lost frame of a discarded stream was put back on the wire",
			server.streamsMentioned.contains(FIRST));
	}

	// ---------------------------------------------------------------- (e) the ordinal is not rewound

	/**
	 * RFC 9000 §2.1 gives a stream identifier out once. The whole purpose of the discard is that the
	 * work is re-created <i>elsewhere</i>, so the next open must be {@code 4}, not {@code 0} again —
	 * re-using the id would send the re-created request under one the peer has already been told about
	 * by a lost 0-RTT packet it may yet receive out of order.
	 */
	@Test
	public void aDiscardDoesNotRewindTheLocalStreamCounter() throws Exception {
		refusedEarlyData();
		writeEarlyDataOn(openEarly(), "GET /early");
		refuse();
		clientManager.discardStream(FIRST, discardCause());

		Promise<QuicStream> reopened = clientManager.openBidirectional();
		assertTrue(reopened.isResult());
		assertEquals(SECOND, reopened.getResult().id());
	}

	// ---------------------------------------------------------------- (f) every other stream survives

	/**
	 * Only what the caller names is discarded. The stream left alone keeps its identity, its bytes and
	 * its obligations: it is still in the map, it has not been failed, and its early-data bytes are
	 * still outstanding — which is to say it is still owed a retransmission rather than settled.
	 */
	@Test
	public void discardingOneStreamLeavesEveryOtherOneIntact() throws Exception {
		refusedEarlyData();
		QuicStream discarded = openEarly();
		QuicStream survivor = openEarly();
		writeEarlyDataOn(discarded, "GET /discarded");
		writeEarlyDataOn(survivor, "GET /survivor");
		refuse();

		assertEquals(Set.of(FIRST, SECOND), clientManager.earlyDataStreams());
		Promise<Void> survivorClosed = survivor.whenClosed();
		clientManager.discardStream(discarded.id(), discardCause());
		wire.pump();

		assertEquals(1, clientManager.openStreamCount());
		assertNotNull(clientManager.streamOf(survivor.id()));
		assertFalse("the neighbour's discard ended the surviving stream too", survivorClosed.isComplete());
		assertTrue("the survivor's early data was settled along with its neighbour's, so nothing is left" +
				   " to retransmit for it",
			clientManager.outstandingStreamBytes() > 0);
		assertTrue(server.aborts.isEmpty());
	}

	// ---------------------------------------------------------------- the manager forwards, and discards nothing itself

	/**
	 * The transport layer decides nothing here (FR-037). It forwards the rejection and leaves every
	 * stream where it is; which of them may be re-created is the application protocol's question.
	 */
	@Test
	public void theManagerForwardsTheRejectionAndDiscardsNothingOfItsOwn() throws Exception {
		refusedEarlyData();
		writeEarlyDataOn(openEarly(), "GET /early");
		refuse();

		assertEquals(1, rejections.size());
		assertEquals(1, clientManager.openStreamCount());
		assertEquals(Set.of(FIRST), clientManager.earlyDataStreams());
	}

	/** A stream opened after the handshake is not early data, so a rejection sweep can never claim it. */
	@Test
	public void aStreamOpenedAfterTheHandshakeIsNotMarkedAsEarlyData() throws Exception {
		refusedEarlyData();
		refuse();

		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isResult());
		assertFalse(opened.getResult().isEarlyData());
		assertTrue(clientManager.earlyDataStreams().isEmpty());
	}

	// ---------------------------------------------------------------- harness

	private void refusedEarlyData() throws Exception {
		refusedEarlyData(QuicConnectionSettings.create());
	}

	/** A client offering a ticket with early data against a server that refuses it, streams on both ends. */
	private void refusedEarlyData(QuicConnectionSettings connectionSettings) throws Exception {
		this.settings = connectionSettings;
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, connectionSettings);
		assertNotNull("the first handshake issued no ticket, so nothing here resumes", ticket);

		wire = new QuicWirePair()
			.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(false))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(true))
			.withClientRememberedTransportParameters(ticket.transportParameters())
			.withClientFrameHandlerFactory(connection -> clientManager =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withEarlyDataRejectionListener(() -> rejections.add(connection))
					.build())
			.withServerFrameHandler(server);
		wire.startClient(connectionSettings);
	}

	/** Opens a stream while the connection is still offering early data, and checks that it is one. */
	private QuicStream openEarly() {
		assertTrue("the client is no longer sending early data, so this stream would not be one",
			wire.client().isSendingEarlyData());
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("a resumption's remembered limits must let a stream open before the handshake",
			opened.isResult());
		QuicStream stream = opened.getResult();
		assertTrue(stream.isEarlyData());
		return stream;
	}

	private void writeEarlyDataOn(QuicStream stream, String payload) {
		ChannelSuppliers.ofValue(handOff(payload.getBytes(UTF_8))).streamTo(stream.writer());
	}

	/** Completes the handshake against the refusing server and asserts that the refusal really happened. */
	private void refuse() throws Exception {
		wire.acceptServer(settings);
		ZeroRttWire.deliverToServerCountingZeroRtt(wire);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertTrue("the pre-shared key was refused, so no early data was ever at stake",
			wire.client().isSessionResumed());
		assertFalse("the server accepted early data it was configured to refuse",
			wire.client().isEarlyDataAccepted());
		assertTrue(wire.client().isLevelDiscarded(EncryptionLevel.ZERO_RTT));
	}

	private static QuicTransportException discardCause() {
		return new QuicTransportException(QuicTransportErrors.NO_ERROR, "the early data was refused");
	}

	/** Collects what the peer was actually told, without ever retaining a borrowed frame. */
	private static final class ServerRecorder implements QuicFrameHandler {
		private final List<Long> streamsMentioned = new ArrayList<>();
		private final List<Long> aborts = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			if (frame instanceof StreamFrame stream) {
				streamsMentioned.add(stream.streamId);
			} else if (frame instanceof ResetStreamFrame reset) {
				aborts.add(reset.streamId);
			} else if (frame instanceof StopSendingFrame stopSending) {
				aborts.add(stopSending.streamId);
			}
		}

		void reset() {
			streamsMentioned.clear();
			aborts.clear();
		}
	}

	/**
	 * Wraps {@code pattern} and hands the transport a {@link ByteBuf#slice()} of it, keeping the parent
	 * so a second release becomes visible as a cleared array rather than as corruption downstream.
	 */
	private ByteBuf handOff(byte[] pattern) {
		ByteBuf parent = ByteBufPool.allocate(pattern.length);
		parent.put(pattern);
		handedOff.add(new Sentinel(parent, pattern));
		return parent.slice();
	}

	private void assertNothingHandedOffWasReleasedTwice() {
		for (Sentinel sentinel : handedOff) {
			byte[] survived;
			try {
				survived = sentinel.parent.getArray();
			} catch (AssertionError e) {
				throw new AssertionError("a buffer handed to the transport was released more times than it" +
										 " was handed over: this test still holds a reference to it, and the" +
										 " pool has already taken it back", e);
			}
			assertArrayEquals("a buffer handed to the transport was released more than once —" +
							  " its array was cleared while this test still holds a reference",
				sentinel.pattern, survived);
		}
	}

	private record Sentinel(ByteBuf parent, byte[] pattern) {}

	private static QuicConnectionSettings windowOf(int bytes) {
		return QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiLocal(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiRemote(MemSize.bytes(bytes))
			.withInitialMaxStreamDataUni(MemSize.bytes(bytes))
			.build();
	}

	private static byte[] pattern(int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7);
		}
		return bytes;
	}
}
