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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
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
 * T095/T096 — FR-021: {@code writer().accept(buf)} <b>takes ownership of {@code buf} on every path</b>,
 * and releases it <b>exactly once</b> — never zero times, never twice.
 *
 * <h2>What proves "exactly once" here</h2>
 * Two independent mechanisms, and neither alone is sufficient:
 * <ul>
 *   <li><b>Never zero.</b> A path that forgets to recycle leaves a pooled buffer live.
 *       {@link #assertNothingIsStillHeld()} asserts that directly, at the end of each case, by
 *       comparing the pool's created and returned counts — and {@link ByteBufRule} repeats the check
 *       for the class as a whole.</li>
 *   <li><b>Never twice.</b> Under the Surefire harness {@code ByteBufPool.registry=true} makes
 *       {@code ByteBuf.CHECK_RECYCLE} true, so the second {@code recycle()} of a buffer whose
 *       reference count already reached zero throws {@code "Attempt to use recycled ByteBuf"} at the
 *       offending call site rather than corrupting a later allocation. A double release therefore
 *       fails this test as an error, not as a wrong number.</li>
 * </ul>
 *
 * <h2>The five paths</h2>
 * <ol>
 *   <li><b>accepted</b> — credit is available and every byte becomes a {@code STREAM} frame;</li>
 *   <li><b>withheld then accepted</b> — flow control stops the write half way, the buffer stays owned
 *       by the sending part, and a raised limit finishes it;</li>
 *   <li><b>reset while pending</b> — the withheld remainder goes with the failed write (FR-036);</li>
 *   <li><b>connection closed while pending</b> — likewise, with the connection's own failure (FR-041);</li>
 *   <li><b>rejected outright</b> — after the end-of-data marker, after an abort, after the writer was
 *       closed, and while another write is still in flight. A rejected write is still an
 *       <em>accepted</em> buffer: the promise fails, and the buffer is released all the same.</li>
 * </ol>
 * The first four are asserted on a bare {@link SendPart} — {@code part.consumer()} is the very object
 * {@code QuicStream.writer()} returns — so that nothing but the buffer under test is ever allocated and
 * the pool assertion is exact. The last two tests repeat the reset and close cases through a real
 * handshaken connection, where the failure arrives as a frame rather than as a method call.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 */
public final class WriterOwnershipTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long STREAM_ID = 0;

	/** Small on purpose: a write longer than this is withheld half way through, which is case 2. */
	private static final int STREAM_WINDOW = 16;

	private static final int MAX_FRAME_DATA = 8;
	private static final int PAYLOAD = 40;

	private static final long RESET_CODE = 0x99L;

	/** Enough for a write to reach the peer and its acknowledgements to come back; never a wait. */
	private static final int DRIVE_ROUNDS = 8;

	/** The transport reduced to a list of frames the <b>test</b> then owns and recycles. */
	private static final class RecordingSink implements SendPart.Sink {
		final List<StreamFrame> frames = new ArrayList<>();
		final List<Long> resets = new ArrayList<>();
		long outstandingBudget = 1 << 20;
		long outstanding;

		@Override
		public long outstandingBytesAvailable() {
			return outstandingBudget - outstanding;
		}

		@Override
		public void onOutstandingBytesChanged(long delta) {
			outstanding += delta;
		}

		@Override
		public void enqueueFrame(StreamFrame frame) {
			frames.add(frame);
		}

		@Override
		public void requestSend() {
			// Deliberately empty: there is no transport here, and the batching contract is asserted by
			// StreamFlowControlTest rather than by this one.
		}

		@Override
		public boolean enqueueReset(long streamId, long applicationErrorCode, long finalSize) {
			resets.add(applicationErrorCode);
			return true;
		}

		void recycleFrames() {
			for (StreamFrame frame : frames) {
				frame.recycle();
			}
			frames.clear();
		}
	}

	private RecordingSink sink;
	private SendPart part;
	private int liveBuffersBefore;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	@Before
	public void setUp() {
		sink = new RecordingSink();
		part = new SendPart(STREAM_ID, new StreamFlowController(STREAM_WINDOW),
			ConnectionFlowController.create(1 << 20, 1 << 20), MAX_FRAME_DATA, sink);
		liveBuffersBefore = liveBuffers();
	}

	@After
	public void tearDown() {
		sink.recycleFrames();
		if (wire != null) {
			wire.close();
			wire = null;
		}
		if (loop != null) {
			loop.close();
			loop = null;
		}
	}

	// ---------------------------------------------------------------- helpers

	/** Pooled buffers allocated but not yet returned — zero of them is what "released" means. */
	private static int liveBuffers() {
		return ByteBufPool.getStats().getCreatedItems() - ByteBufPool.getStats().getPoolItems();
	}

	/**
	 * Every pooled buffer this case allocated is back in the pool. Called only after the frames the sink
	 * captured have been recycled, because a frame legitimately holds a retained slice of the very
	 * buffer under test (FR-014 — sliced, never copied).
	 */
	private void assertNothingIsStillHeld() {
		sink.recycleFrames();
		assertEquals("FR-021: accept(buf) owns buf on every path, so nothing may still be held",
			liveBuffersBefore, liveBuffers());
	}

	private static ByteBuf payload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	private static Exception failureOf(Promise<Void> promise) {
		assertTrue("the write should have been resolved, not left pending", promise.isComplete());
		Exception e = promise.getException();
		assertNotNull("the write should have failed", e);
		return e;
	}

	/** A write longer than the window: half of it goes out, the rest stays owned by the sending part. */
	private Promise<Void> withheldWrite() {
		Promise<Void> written = part.consumer().accept(payload(PAYLOAD));
		assertFalse("the write must be withheld, not failed — that is the backpressure signal",
			written.isComplete());
		assertEquals("only what the window permitted may have left", STREAM_WINDOW, part.writeOffset());
		return written;
	}

	// ---------------------------------------------------------------- 1: accepted

	@Test
	public void aWriteThatFitsTheWindowIsAcceptedAndItsBufferReleased() {
		Promise<Void> written = part.consumer().accept(payload(STREAM_WINDOW));

		assertTrue("every byte had credit, so the write resolves at once", written.isComplete());
		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals(STREAM_WINDOW / MAX_FRAME_DATA, sink.frames.size());
		assertNothingIsStillHeld();
	}

	@Test
	public void theEndOfDataMarkerCarriesNoBufferToLeak() {
		// accept(null) writes a zero-length FIN over ByteBuf.empty(), which is shared and un-refcounted:
		// the case exists because "recycle what you are given" must stay true for a buffer that is not
		// pooled at all.
		Promise<Void> written = part.consumer().accept(null);

		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals(SendState.DATA_SENT, part.state());
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- 2: withheld, then accepted

	@Test
	public void aWithheldWriteReleasesItsBufferOnlyWhenTheRaisedLimitFinishesIt() {
		Promise<Void> written = withheldWrite();

		// Nothing but an event releases a withheld write — here, the peer's MAX_STREAM_DATA.
		assertTrue(part.flowControl().raiseLimit(PAYLOAD));
		part.pump();

		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals(PAYLOAD, part.writeOffset());
		assertEquals(PAYLOAD / MAX_FRAME_DATA, sink.frames.size());
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- 3: reset while pending

	@Test
	public void aResetWhileTheWriteIsPendingReleasesTheWithheldRemainder() {
		Promise<Void> written = withheldWrite();

		part.reset(RESET_CODE);

		Exception e = failureOf(written);
		assertTrue("FR-036: the pending write is failed, not stranded — got " + e,
			e instanceof QuicStreamResetException);
		assertEquals(RESET_CODE, ((QuicStreamResetException) e).applicationErrorCode());
		assertEquals(List.of(RESET_CODE), sink.resets);
		assertEquals(SendState.RESET_SENT, part.state());
		assertNothingIsStillHeld();
	}

	@Test
	public void aPeerStopSendingWhileTheWriteIsPendingReleasesTheWithheldRemainder() {
		Promise<Void> written = withheldWrite();

		// RFC 9000 §3.5 makes STOP_SENDING the same transition, driven from the other end; only the typed
		// failure differs, and the buffer must go the same way.
		part.onStopSending(RESET_CODE);

		Exception e = failureOf(written);
		assertTrue("got " + e, e instanceof QuicStreamStopSendingException);
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- 4: connection closed while pending

	@Test
	public void aConnectionCloseWhileTheWriteIsPendingReleasesTheWithheldRemainder() {
		Promise<Void> written = withheldWrite();

		ExpectedException closed = new ExpectedException("the connection ended");
		part.onConnectionClosed(closed);

		assertEquals("FR-041: the connection's own failure reaches the write unwrapped",
			closed, failureOf(written));
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- 5: rejected outright

	@Test
	public void aWriteAfterTheEndOfDataMarkerIsRejectedAndItsBufferReleased() {
		assertTrue(part.consumer().accept(null).isResult());

		Promise<Void> written = part.consumer().accept(payload(PAYLOAD));

		assertTrue("a write past the final size is a caller error, reported through the promise — got " +
				   failureOf(written),
			failureOf(written) instanceof IllegalStateException);
		assertNothingIsStillHeld();
	}

	@Test
	public void aWriteAfterAnAbortIsRejectedAndItsBufferReleased() {
		part.reset(RESET_CODE);

		Promise<Void> written = part.consumer().accept(payload(PAYLOAD));

		Exception e = failureOf(written);
		assertTrue("the typed abort is reported verbatim rather than as a generic close — got " + e,
			e instanceof QuicStreamResetException);
		assertNothingIsStillHeld();
	}

	@Test
	public void aWriteOnAClosedWriterIsRejectedAndItsBufferReleased() {
		// Rejected by AbstractChannelConsumer itself, before SendPart ever sees the buffer — the one
		// rejection path that is not this package's code, and therefore the one worth pinning down.
		part.consumer().closeEx(new ExpectedException("the pipeline gave up"));

		Promise<Void> written = part.consumer().accept(payload(PAYLOAD));

		assertTrue("got " + failureOf(written), failureOf(written) instanceof ExpectedException);
		assertNothingIsStillHeld();
	}

	@Test
	public void aSecondWriteIssuedWhileOneIsInFlightIsRejectedAndItsBufferReleased() {
		Promise<Void> first = withheldWrite();

		// A ChannelConsumer serialises writes, so this is a caller bug — but the buffer is the callee's
		// the moment it is handed over, bug or not.
		Promise<Void> second = part.consumer().accept(payload(PAYLOAD));

		assertTrue("got " + failureOf(second), failureOf(second) instanceof IllegalStateException);
		assertFalse("the first write must be untouched by the second one's rejection", first.isComplete());

		part.onConnectionClosed(new ExpectedException("done"));
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- the same two, over a real connection

	private QuicStream openOverTheWire() throws MalformedDataException {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection ->
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamDataBidiRemote(MemSize.bytes(STREAM_WINDOW))
			.build());
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());

		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Delivers what the write produced and lets the acknowledgements come back; no waiting on real time. */
	private void drive() {
		for (int round = 0; round < DRIVE_ROUNDS; round++) {
			wire.pump();
			loop.tick();
			loop.advance(5);
		}
	}

	@Test
	public void aResetOnARealStreamReleasesTheWithheldRemainder() throws Exception {
		QuicStream stream = openOverTheWire();
		Promise<Void> written = stream.writer().accept(payload(8 * STREAM_WINDOW));
		drive();
		assertFalse("the write is held at the peer's stream window", written.isComplete());

		stream.reset(RESET_CODE);

		assertTrue("got " + failureOf(written), failureOf(written) instanceof QuicStreamResetException);
	}

	@Test
	public void aConnectionCloseOnARealStreamReleasesTheWithheldRemainder() throws Exception {
		QuicStream stream = openOverTheWire();
		Promise<Void> written = stream.writer().accept(payload(8 * STREAM_WINDOW));
		drive();
		assertFalse("the write is held at the peer's stream window", written.isComplete());

		wire.client().closeNow();

		assertTrue("FR-041: the connection's RFC 9000 §20 failure reaches the write unwrapped — got " +
				   failureOf(written),
			failureOf(written) instanceof QuicTransportException);
	}
}
