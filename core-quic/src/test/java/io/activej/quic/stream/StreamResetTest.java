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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T054 — US4 scenarios 1, 4 and 5: what a local {@link QuicStream#reset} does to the other endpoint,
 * to the bytes already in flight, and to the data it supersedes (FR-018, FR-031, FR-034).
 *
 * <h2>Four properties, and the third is the one an end-to-end test would miss</h2>
 * <ol>
 *   <li>the peer's reader fails with {@link QuicStreamResetException} carrying <b>exactly</b> the
 *       application error code the resetting side chose — a code the transport neither interprets nor
 *       rewrites (RFC 9000 §20.2);</li>
 *   <li>bytes that had arrived but nobody had read are <b>discarded</b>, not delivered after the
 *       failure: an abort is not a graceful end-of-stream with an error tacked on;</li>
 *   <li>a {@code STREAM} frame declared lost <b>after</b> the reset is <b>not</b> retransmitted
 *       (FR-018, RFC 9000 §3.1). Asserted at the loss seam rather than by blackholing a datagram,
 *       because what is being tested is a decision the send part makes about a frame handed back to
 *       it, and a lossy network would test the loss detector instead;</li>
 *   <li>every buffer either side held is released exactly once, which is {@code ByteBufRule}'s half of
 *       the test and applies to all three of the above.</li>
 * </ol>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.4">RFC 9000 §19.4 — RESET_STREAM Frames</a>
 */
public final class StreamResetTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** An arbitrary 62-bit application code, big enough that a truncation to 32 bits would show. */
	private static final long APP_ERROR_CODE = 0x0DEFACED_1234L;

	private static final int MAX_DRIVE_ROUNDS = 300;

	/** A stream layer with a tap on the frames the <i>other</i> endpoint sent, then handed on unchanged. */
	private static final class TappedStreamLayer implements QuicFrameHandler {
		private final QuicStreamManager delegate;
		final List<ResetStreamFrame> resets = new ArrayList<>();
		final List<StreamFrame> streamFrames = new ArrayList<>();

		TappedStreamLayer(QuicStreamManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			if (frame instanceof ResetStreamFrame reset) resets.add(reset);
			// The frame is borrowed, so only its scalar fields may outlive this call: a copy with no
			// payload, which is all any assertion here needs.
			if (frame instanceof StreamFrame stream) {
				streamFrames.add(new StreamFrame(stream.streamId, stream.offset, stream.fin, ByteBuf.empty()));
			}
			delegate.onFrame(connection, level, frame);
		}

		@Override
		public void onFrameAcknowledged(QuicConnection connection, QuicFrame frame) {
			delegate.onFrameAcknowledged(connection, frame);
		}

		@Override
		public void onFrameLost(QuicConnection connection, QuicFrame frame) {
			delegate.onFrameLost(connection, frame);
		}

		@Override
		public void onEstablished(QuicConnection connection) {
			delegate.onEstablished(connection);
		}

		@Override
		public void onClosed(QuicConnection connection) {
			delegate.onClosed(connection);
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;
	private TappedStreamLayer clientTap;
	private TappedStreamLayer serverTap;

	private final List<QuicStream> serverStreams = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> {
			clientManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build();
			clientTap = new TappedStreamLayer(clientManager);
			return clientTap;
		});
		wire.withServerFrameHandlerFactory(connection -> {
			serverManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				// Deliberately no reader: several cases here need bytes buffered but undelivered, which a
				// collector attached on acceptance would drain before the abort could reach them.
				.withStreamListener(serverStreams::add)
				.build();
			serverTap = new TappedStreamLayer(serverManager);
			return serverTap;
		});
		wire.handshake(QuicConnectionSettings.create());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			wire.pump();
			loop.tick();
			if (done.getAsBoolean()) return;
			loop.advance(5);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static ByteBuf buf(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Opens a stream, writes {@code payload} and waits for the server to have accepted and buffered it. */
	private QuicStream openAndSend(String payload) {
		QuicStream clientStream = openClientStream();
		Promise<Void> written = clientStream.writer().accept(buf(payload));
		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty()
						 && serverStreams.get(0).receiveState() == ReceiveState.RECV
						 && !serverTap.streamFrames.isEmpty());
		assertTrue(String.valueOf(written.getException()), written.isResult());
		return clientStream;
	}

	private static QuicStreamResetException resetExceptionOf(Promise<?> promise) {
		assertTrue("the read should have completed", promise.isComplete());
		Exception e = promise.getException();
		assertTrue("expected a QuicStreamResetException, got " + e,
			e instanceof QuicStreamResetException);
		return (QuicStreamResetException) e;
	}

	// ---------------------------------------------------------------- scenario 1

	@Test
	public void theResetReachesThePeerWithTheExactApplicationErrorCode() {
		QuicStream clientStream = openAndSend("payload");

		clientStream.reset(APP_ERROR_CODE);
		assertEquals("RFC 9000 §3.1: Ready/Send/Data Sent all go straight to Reset Sent",
			SendState.RESET_SENT, clientStream.sendState());

		QuicStream serverStream = serverStreams.get(0);
		driveUntil(() -> !serverTap.resets.isEmpty());
		Promise<ByteBuf> read = serverStream.reader().get();

		QuicStreamResetException e = resetExceptionOf(read);
		assertEquals("the code is the application's, carried verbatim (RFC 9000 §20.2)",
			APP_ERROR_CODE, e.applicationErrorCode());
		assertEquals(serverStream.id(), e.streamId());
		assertEquals(1, serverTap.resets.size());
		assertEquals(APP_ERROR_CODE, serverTap.resets.get(0).appErrorCode);
		assertEquals("RFC 9000 §4.5: the final size is what had been given an offset",
			"payload".length(), serverTap.resets.get(0).finalSize);
		assertEquals(1, serverManager.streamsResetByPeer());
		assertEquals(1, clientManager.streamsResetLocally());
	}

	@Test
	public void bufferedButUndeliveredBytesAreDiscardedRatherThanDeliveredAfterTheFailure() {
		QuicStream clientStream = openAndSend("bytes nobody has read yet");
		QuicStream serverStream = serverStreams.get(0);

		clientStream.reset(APP_ERROR_CODE);
		driveUntil(() -> serverStream.receiveState() == ReceiveState.RESET_RECVD
						 || serverStream.receiveState() == ReceiveState.RESET_READ);

		// The very first read fails: the buffered bytes are gone, not queued ahead of the failure.
		Promise<ByteBuf> read = serverStream.reader().get();
		assertEquals(APP_ERROR_CODE, resetExceptionOf(read).applicationErrorCode());

		// ...and so does the next one, with the same fact rather than a false end-of-stream.
		Promise<ByteBuf> again = serverStream.reader().get();
		assertTrue("a second read must not report end-of-stream", again.isException());
		assertEquals(APP_ERROR_CODE, resetExceptionOf(again).applicationErrorCode());
	}

	@Test
	public void aParkedReadIsFailedRatherThanLeftPending() {
		QuicStream clientStream = openClientStream();
		Promise<Void> written = clientStream.writer().accept(buf("first"));
		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty());

		QuicStream serverStream = serverStreams.get(0);
		// Drain what has arrived, then park a read on what has not.
		Promise<ByteBuf> delivered = serverStream.reader().get();
		driveUntil(delivered::isComplete);
		ByteBuf buf = delivered.getResult();
		assertNotNull(buf);
		buf.recycle();

		Promise<ByteBuf> parked = serverStream.reader().get();
		assertFalse("nothing more has arrived, so this read is parked", parked.isComplete());

		clientStream.reset(APP_ERROR_CODE);
		driveUntil(parked::isComplete);

		assertEquals(APP_ERROR_CODE, resetExceptionOf(parked).applicationErrorCode());
		assertEquals("a parked read *is* the application observing the abort (RFC 9000 §3.2)",
			ReceiveState.RESET_READ, serverStream.receiveState());
	}

	// ---------------------------------------------------------------- scenario 4 (FR-018)

	@Test
	public void aStreamFrameLostAfterTheResetIsNotRetransmitted() {
		QuicStream clientStream = openAndSend("data that is about to be abandoned");
		long streamId = clientStream.id();
		int framesBefore = serverTap.streamFrames.size();

		clientStream.reset(APP_ERROR_CODE);

		// The connection hands a lost frame back to its handler, which owns it from then on. This is that
		// hand-back, literally: a frame the test allocated, at offsets the stream really used.
		ByteBuf payload = buf("data that is about to be abandoned");
		clientManager.onFrameLost(wire.client(), new StreamFrame(streamId, 0, false, payload));

		driveUntil(() -> !serverTap.resets.isEmpty());
		wire.pump();

		assertEquals("FR-018: an aborted send part never resurrects its data",
			framesBefore, serverTap.streamFrames.size());
		assertEquals("...while the abort itself did travel", 1, serverTap.resets.size());
	}

	@Test
	public void theResetItselfIsReEnqueuedWhenLost() {
		QuicStream clientStream = openAndSend("payload");
		clientStream.reset(APP_ERROR_CODE);
		// Before the abort can be acknowledged, which is the only thing that ends its retransmission.
		assertEquals(SendState.RESET_SENT, clientStream.sendState());

		// RFC 9000 §19.4 makes the abort reliable: unlike the data it supersedes, a lost RESET_STREAM goes
		// back on the wire, carrying the same final size it was fixed with.
		clientManager.onFrameLost(wire.client(),
			new ResetStreamFrame(clientStream.id(), APP_ERROR_CODE, "payload".length()));
		driveUntil(() -> serverTap.resets.size() > 1);

		assertEquals(2, serverTap.resets.size());
		assertEquals(serverTap.resets.get(0), serverTap.resets.get(1));
	}

	@Test
	public void anAcknowledgedResetIsNotReEnqueuedAgain() {
		QuicStream clientStream = openAndSend("payload");
		clientStream.reset(APP_ERROR_CODE);

		// Reset Sent → Reset Recvd on acknowledgement, which is terminal (RFC 9000 §3.1); the client's own
		// receiving half of this bidirectional stream is still open, so the stream itself stays.
		driveUntil(() -> clientStream.sendState() == SendState.RESET_RECVD);

		int seen = serverTap.resets.size();
		clientManager.onFrameLost(wire.client(),
			new ResetStreamFrame(clientStream.id(), APP_ERROR_CODE, "payload".length()));
		wire.pump();
		loop.tick();

		assertEquals("a delivered abort has nothing left to retransmit", seen, serverTap.resets.size());
	}

	// ---------------------------------------------------------------- idempotence (FR-031)

	@Test
	public void aSecondResetQueuesNothing() {
		QuicStream clientStream = openAndSend("payload");

		clientStream.reset(APP_ERROR_CODE);
		clientStream.reset(APP_ERROR_CODE + 1);
		clientStream.reset(APP_ERROR_CODE + 2);
		driveUntil(() -> !serverTap.resets.isEmpty());
		wire.pump();

		assertEquals("RFC 9000 §3.1 has no transition out of Reset Sent but the acknowledgement",
			1, serverTap.resets.size());
		assertEquals("...and the first code is the one that binds",
			APP_ERROR_CODE, serverTap.resets.get(0).appErrorCode);
		assertEquals(1, clientManager.streamsResetLocally());
	}

	@Test
	public void aWriteAfterTheResetTakesItsBufferAndFails() {
		QuicStream clientStream = openAndSend("payload");
		clientStream.reset(APP_ERROR_CODE);

		// DI-1: the writer owns its buffer on every path, rejection included — ByteBufRule is what asserts
		// the second half of that.
		Promise<Void> refused = clientStream.writer().accept(buf("too late"));
		assertTrue(refused.isException());
		Exception e = refused.getException();
		assertTrue("expected a QuicStreamResetException, got " + e, e instanceof QuicStreamResetException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
	}

	// ---------------------------------------------------------------- release (FR-006)

	@Test
	public void bothHalvesTerminalReleasesTheStreamOnBothEnds() {
		QuicStream clientStream = openAndSend("payload");
		QuicStream serverStream = serverStreams.get(0);
		long streamId = clientStream.id();

		// Both directions must end for a bidirectional stream to be released, so both are aborted.
		clientStream.reset(APP_ERROR_CODE);
		driveUntil(() -> serverStream.receiveState() == ReceiveState.RESET_RECVD);
		Promise<ByteBuf> read = serverStream.reader().get();
		assertEquals("the exact type and code, as every sibling asserts: 'some failure' would pass here " +
					 "even if the abort had been reported as a plain close",
			APP_ERROR_CODE, resetExceptionOf(read).applicationErrorCode());
		serverStream.reset(APP_ERROR_CODE + 1);

		Promise<ByteBuf> clientRead = clientStream.reader().get();
		driveUntil(() -> clientRead.isComplete()
						 && clientManager.openStreamCount() == 0 && serverManager.openStreamCount() == 0);

		assertEquals(APP_ERROR_CODE + 1, resetExceptionOf(clientRead).applicationErrorCode());
		assertNull(clientManager.streamOf(streamId));
		assertNull(serverManager.streamOf(streamId));
		assertTrue(clientStream.whenClosed().isComplete());
		assertTrue(serverStream.whenClosed().isComplete());
	}

	// ---------------------------------------------------------------- FR-007 stays FR-007

	@Test
	public void resettingAHalfThisEndpointDoesNotOwnIsACallerBug() {
		Promise<QuicStream> opened = clientManager.openUnidirectional();
		assertTrue(opened.isComplete());
		QuicStream uni = opened.getResult();
		assertNotNull(uni);

		// A locally-initiated unidirectional stream has no receiving half to stop — a caller bug,
		// deliberately distinct from every wire error (FR-007), and unchanged by this phase.
		assertThrows(IllegalStateException.class, () -> uni.stopSending(APP_ERROR_CODE));
		uni.reset(APP_ERROR_CODE);
		assertEquals(SendState.RESET_SENT, uni.sendState());
	}
}
