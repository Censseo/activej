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
import io.activej.bytebuf.ByteBufs;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.LossDetector;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T068 — research R-08: the outstanding-bytes budget of FR-019 moves on <b>acknowledgement</b> and not
 * on loss.
 * <p>
 * The two halves of that rule are opposites of each other and both are load-bearing:
 * <ul>
 *   <li>a lost frame that will be <b>resent</b> is still outstanding, so crediting its bytes back would
 *       let the budget drift upward — by exactly one frame per loss, cumulatively, which is worst
 *       precisely under the sustained loss the bound exists for;</li>
 *   <li>a lost frame that will <b>never</b> be resent — one belonging to a part in RFC 9000 §3.1's Reset
 *       Sent (FR-018) — must give its bytes back, or the connection permanently loses that much of its
 *       local send capacity, with no acknowledgement ever coming to release it.</li>
 * </ul>
 * The first half is asserted directly against a {@link SendPart} and its {@link SendPart.Sink}, where
 * the budget is a number rather than an emergent property, and then again over a real connection under
 * repeated staged loss, where {@code maxOutstandingStreamBytes} is checked after <em>every</em>
 * datagram rather than once at the end.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 */
public final class OutstandingBudgetTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long STREAM_ID = 0;
	private static final int MAX_FRAME_DATA = 100;

	/** Bound on the drive loop. A budget that never drains must fail loudly, never hang the build. */
	private static final int MAX_DRIVE_ROUNDS = 400;

	/** The transport, reduced to a list — the same shape {@code SendPartTest} uses. */
	private static final class RecordingSink implements SendPart.Sink {
		final List<StreamFrame> frames = new ArrayList<>();
		long outstandingBudget = 1 << 20;
		long outstanding;
		int resets;

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
		public void requestSend() {}

		@Override
		public boolean enqueueReset(long streamId, long applicationErrorCode, long finalSize) {
			resets++;
			return true;
		}

		void recycleAll() {
			frames.forEach(StreamFrame::recycle);
			frames.clear();
		}
	}

	private RecordingSink sink;
	private SendPart part;

	@Before
	public void setUp() {
		sink = new RecordingSink();
		part = new SendPart(STREAM_ID, new StreamFlowController(1 << 20),
			ConnectionFlowController.create(1 << 20, 1 << 20), MAX_FRAME_DATA, sink);
	}

	@After
	public void tearDown() {
		part.closeEx(new IllegalStateException("test teardown"));
		sink.recycleAll();
		if (wire != null) wire.close();
		if (loop != null) {
			loop.tickUntilQuiet();
			loop.close();
		}
	}

	private static ByteBuf bytes(int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	/** Takes the frame the sink was last handed, as the transport would hand it back. */
	private StreamFrame takeLastFrame() {
		return sink.frames.remove(sink.frames.size() - 1);
	}

	// ---------------------------------------------------------------- the rule, stated directly

	@Test
	public void lossLeavesTheBudgetReservedBecauseTheBytesAreStillOutstanding() {
		part.write(bytes(60));
		assertEquals(60, part.outstandingBytes());
		assertEquals(60, sink.outstanding);

		part.onFrameLost(takeLastFrame());

		// Not "reserved again": never released. The frame is on its way back out, so the bytes it holds
		// have not stopped being in flight for a single moment (research R-08).
		assertEquals(60, part.outstandingBytes());
		assertEquals(60, sink.outstanding);
		assertEquals("the lost frame goes back to the transport", 1, sink.frames.size());
		assertEquals(0, sink.frames.get(0).offset);
	}

	@Test
	public void repeatedLossOfTheSameFrameChargesItsBytesExactlyOnce() {
		part.write(bytes(60));
		for (int i = 0; i < 10; i++) {
			part.onFrameLost(takeLastFrame());
			assertEquals("the budget must not drift with the number of retransmissions",
				60, part.outstandingBytes());
			assertEquals(60, sink.outstanding);
		}

		part.onFrameAcknowledged(takeLastFrame());
		assertEquals(0, part.outstandingBytes());
		assertEquals(0, sink.outstanding);
		assertEquals(SendState.SEND, part.state());
	}

	@Test
	public void acknowledgementIsTheOnlyThingThatFreesTheBudget() {
		part.write(bytes(60));
		part.write(bytes(40));
		assertEquals(100, part.outstandingBytes());

		part.onFrameAcknowledged(sink.frames.remove(0));
		assertEquals(40, part.outstandingBytes());
		assertEquals(40, sink.outstanding);
		part.onFrameAcknowledged(sink.frames.remove(0));
		assertEquals(0, part.outstandingBytes());
		assertEquals(0, sink.outstanding);
	}

	@Test
	public void aWriterClosedAfterItsFinKeepsTheBudgetOfALostFrame() {
		part.write(bytes(60));
		part.writeFin();
		part.closeEx(new IllegalStateException("the pipeline closed the writer"));
		assertEquals(SendState.DATA_SENT, part.state());

		// RFC 9000 §3.1 Data Sent: closed to new writes, not done with the old ones. The frame is resent
		// and its budget stays booked, exactly as for a part still open.
		StreamFrame lost = sink.frames.remove(0);
		part.onFrameLost(lost);
		assertEquals(60, part.outstandingBytes());
		assertEquals(60, sink.outstanding);
		assertEquals(0, sink.frames.get(sink.frames.size() - 1).offset);
	}

	@Test
	public void lossAfterAResetGivesTheBudgetBackBecauseNothingWillEverResendIt() {
		part.write(bytes(60));
		part.write(bytes(40));
		assertEquals(100, part.outstandingBytes());

		part.reset(7);
		assertEquals(SendState.RESET_SENT, part.state());
		assertEquals(1, sink.resets);
		// The abort supersedes the data (FR-018), so the budget is the only thing left to settle — and
		// nothing else ever will, since an aborted part's frames are never acknowledged.
		assertEquals("a reset does not on its own release what is in flight", 100, part.outstandingBytes());

		part.onFrameLost(sink.frames.remove(0));
		assertEquals(40, part.outstandingBytes());
		assertEquals(40, sink.outstanding);
		part.onFrameLost(sink.frames.remove(0));
		assertEquals(0, part.outstandingBytes());
		assertEquals(0, sink.outstanding);
		assertTrue("an aborted part never resends its data", sink.frames.isEmpty());
	}

	@Test
	public void aLostZeroLengthFinOfAResetPartSettlesTooRatherThanStrandingTheState() {
		part.write(bytes(60));
		part.writeFin();
		part.reset(9);

		for (StreamFrame frame : new ArrayList<>(sink.frames)) {
			sink.frames.remove(frame);
			part.onFrameLost(frame);
		}
		// Both frames settled, the bare FIN among them: it carries no bytes but is still one frame the
		// part was waiting on, and a count that ignored it would never reach zero.
		assertEquals(0, part.outstandingBytes());
		assertEquals(0, sink.outstanding);
	}

	// ---------------------------------------------------------------- the bound, over a real connection

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private long maxOutstandingStreamBytes;

	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	private void startWire(QuicConnectionSettings settings) throws MalformedDataException {
		loop = new ManualEventloop();
		maxOutstandingStreamBytes = settings.maxOutstandingStreamBytes();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection ->
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> serverReads.add(
					stream.reader().toCollector(ByteBufs.collector())))
				.build());
		wire.handshake(settings);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	private void pump() {
		wire.pump();
		loop.tick();
		wire.pump();
		assertBoundHolds();
	}

	private void assertBoundHolds() {
		assertTrue("outstanding stream bytes (" + clientManager.outstandingStreamBytes() +
				   ") must never exceed maxOutstandingStreamBytes (" + maxOutstandingStreamBytes + ")",
			clientManager.outstandingStreamBytes() <= maxOutstandingStreamBytes);
		assertTrue("outstanding stream bytes must never go negative",
			clientManager.outstandingStreamBytes() >= 0);
	}

	private void driveUntil(BooleanSupplier done, String what) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			pump();
			if (done.getAsBoolean()) return;
			loop.advance(5);
			assertBoundHolds();
			if (done.getAsBoolean()) return;
		}
		fail(what);
	}

	/** One staged, detected loss of the client's most recent packet (feature 03's recipe). */
	private void loseOneClientPacket(Runnable action) {
		long lostBefore = wire.client().packetsLost();
		wire.clientWire().blackhole(true);
		action.run();
		wire.clientWire().blackhole(false);
		for (int i = 0; i <= LossDetector.PACKET_THRESHOLD; i++) {
			try {
				wire.client().enqueueFrame(PingFrame.INSTANCE);
			} catch (QuicTransportException e) {
				throw new AssertionError(e);
			}
			wire.client().requestSend();
			pump();
		}
		assertTrue("the packet-number threshold never declared the blackholed packet lost",
			wire.client().packetsLost() > lostBefore);
	}

	@Test
	public void underSustainedLossTheBudgetNeverDriftsAboveItsBound() throws Exception {
		// A budget far smaller than the flow-control windows, so it is the *local* bound that binds and a
		// drift of one frame per loss would show up as an assertion rather than as a slow transfer.
		startWire(QuicConnectionSettings.builder()
			.withMaxOutstandingStreamBytes(MemSize.kilobytes(8))
			.build());

		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isResult());
		QuicStream stream = opened.getResult();
		ChannelConsumer<ByteBuf> writer = stream.writer();

		int chunks = 24;
		int chunkSize = 900;
		byte[] payload = new byte[chunks * chunkSize];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i * 31 + (i >>> 8) * 7);
		}
		for (int i = 0; i < chunks; i++) {
			int from = i * chunkSize;
			ByteBuf chunk = ByteBufPool.allocate(chunkSize);
			chunk.put(payload, from, chunkSize);
			// Every third chunk is blackholed and then declared lost, so the same data is retransmitted
			// several times over the course of one transfer.
			if (i % 3 == 0) {
				loseOneClientPacket(() -> writer.accept(chunk));
			} else {
				writer.accept(chunk);
				pump();
			}
			assertBoundHolds();
		}
		Promise<Void> finished = writer.acceptEndOfStream();
		driveUntil(() -> finished.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the lossy transfer never completed — a retransmission or a budget refill is missing");

		ByteBuf received = serverReads.get(0).getResult();
		byte[] bytes = received.getArray();
		received.recycle();
		assertArrayEquals(payload, bytes);
		// Everything acknowledged, so the budget is back where it started. A leak here is what a budget
		// credited on loss *and* on the acknowledgement of the retransmission would look like.
		assertEquals(0, clientManager.outstandingStreamBytes());
		assertEquals(payload.length, clientManager.bytesSent());
	}

	/**
	 * A {@code RESET_STREAM} becoming acknowledged is what makes a send part terminal (RFC 9000 §3.1's
	 * Reset Sent → Reset Recvd), and that transition does not wait for the {@code STREAM} frames sent
	 * before the abort to be settled — some may still be genuinely in flight. Once
	 * {@code releaseIfTerminal} removes the stream, {@code onFrameAcknowledged}/{@code onFrameLost} for
	 * one of those older frames finds no part to credit: without settling the budget at release time,
	 * that share of {@code maxOutstandingStreamBytes} would be gone forever.
	 * <p>
	 * Driven directly through the manager's {@code QuicFrameHandler} methods, the same technique
	 * {@code QuicStreamManagerFrameRoutingTest} uses, because staging the real packet race (the peer's
	 * ACK for the abort's own packet reaching the sender before the earlier data packet's fate is known)
	 * needs no less precision than fabricating it directly.
	 */
	@Test
	public void aStreamReleasedWhileAnEarlierFrameIsStillOutstandingDoesNotLeakItsBudget() throws Exception {
		startWire(QuicConnectionSettings.builder().build());

		// Unidirectional and locally-initiated: no receive half, so isFullyTerminal() needs only the send
		// part to reach RESET_RECVD — a bidirectional stream's still-open receive half would otherwise
		// hold the stream in the map regardless of what the send half does.
		Promise<QuicStream> opened = clientManager.openUnidirectional();
		assertTrue(opened.isResult());
		QuicStream stream = opened.getResult();
		long streamId = stream.id();

		ChannelConsumer<ByteBuf> writer = stream.writer();
		writer.accept(bytes(50));
		assertEquals(50, clientManager.outstandingStreamBytes());

		stream.reset(3);

		// The RESET_STREAM's own packet is acknowledged — the manager never learns anything about the
		// fate of the STREAM frame sent moments before it, exactly as a genuine reordering would leave it.
		clientManager.onFrameAcknowledged(wire.client(), new ResetStreamFrame(streamId, 3, 50));

		// Settled already, at release — not left waiting for a settlement that can no longer reach this
		// stream, because it is no longer in the manager's map.
		assertEquals("the reset stream's outstanding budget must be credited back at release, " +
			"not stranded waiting for a frame this stream can no longer be charged or credited through",
			0, clientManager.outstandingStreamBytes());

		// The original frame's fate arrives later still — a stale notification for a stream that has
		// already left the map, and must not double-credit or throw.
		clientManager.onFrameLost(wire.client(), new StreamFrame(streamId, 0, false, bytes(50)));
		assertEquals(0, clientManager.outstandingStreamBytes());
	}

	/**
	 * The budget settled at release time (see the test above) is connection-wide credit: freeing it is
	 * an event that can unblock a writer, and the guard rail of FR-019 requires every such event to pump
	 * the writers it might have released. No acknowledgement can do it in this event's place — the
	 * frames whose fate would have carried that acknowledgement left the manager's map with the released
	 * stream, and when those fates arrive later they find no part and retry nobody.
	 * <p>
	 * Two locally-initiated unidirectional streams share a budget of exactly one payload: the first
	 * stream's frame fills it, the second stream's write is withheld whole. Aborting the first and
	 * acknowledging its {@code RESET_STREAM} releases the stream and settles its budget — the withheld
	 * write must then complete with no further wire event at all.
	 */
	@Test
	public void budgetSettledAtReleaseIsOfferedToAWriterWithheldAtIt() throws Exception {
		startWire(QuicConnectionSettings.builder()
			.withMaxOutstandingStreamBytes(MemSize.bytes(100))
			.build());

		Promise<QuicStream> openedA = clientManager.openUnidirectional();
		Promise<QuicStream> openedB = clientManager.openUnidirectional();
		assertTrue(openedA.isResult());
		assertTrue(openedB.isResult());
		QuicStream streamA = openedA.getResult();
		QuicStream streamB = openedB.getResult();

		// The whole budget is now held by stream A's frame, none of it acknowledged or lost.
		Promise<Void> writtenA = streamA.writer().accept(bytes(100));
		assertTrue(writtenA.isResult());
		assertEquals(100, clientManager.outstandingStreamBytes());

		// Nothing of the budget is left, so stream B's write is withheld whole — not one byte of it is
		// in flight to carry a later acknowledgement.
		Promise<Void> writtenB = streamB.writer().accept(bytes(50));
		assertFalse("stream B's write must genuinely block on the outstanding budget", writtenB.isComplete());
		SendPart sendPartB = streamB.sendPart();
		assertNotNull(sendPartB);
		assertTrue(sendPartB.hasPendingWrite());

		streamA.reset(3);

		// The abort's own acknowledgement releases stream A with its data frame still outstanding. The
		// settle that then frees the budget is the only event stream B will ever get: stream A's frame,
		// whose fate could have carried one, is gone from the map along with its stream.
		clientManager.onFrameAcknowledged(wire.client(), new ResetStreamFrame(streamA.id(), 3, 100));

		assertTrue("the budget freed by the release must be offered to the withheld writer",
			writtenB.isResult());
		assertFalse(sendPartB.hasPendingWrite());
		assertEquals("stream B's bytes are outstanding now; stream A's are settled",
			50, clientManager.outstandingStreamBytes());
	}
}
