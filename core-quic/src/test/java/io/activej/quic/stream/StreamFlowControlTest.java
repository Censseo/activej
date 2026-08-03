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
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.MaxStreamDataFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportErrors;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T036 — user story 2, scenarios 1 and 2: <b>stream-level</b> flow control as the application sees it.
 * <p>
 * The three properties, all of them FR-019/FR-020/FR-022:
 * <ul>
 *   <li>a write whose bytes have no credit left <b>stays unresolved</b> — it is neither failed nor
 *       queued somewhere else, because the withheld promise <i>is</i> the backpressure signal;</li>
 *   <li>while it is withheld, <b>not one byte</b> above the limit reaches the transport;</li>
 *   <li>a {@code MAX_STREAM_DATA} that raises the limit past what the write needs resolves it, with
 *       every byte at its original, contiguous offset.</li>
 * </ul>
 * Asserted at two levels, deliberately: on a bare {@link SendPart}, where "reaching the transport" is
 * a list of frames and nothing else can be blamed; and on a {@link QuicStreamManager} attached to a
 * real handshaken connection, where the {@code MAX_STREAM_DATA} arrives as a frame rather than as a
 * method call.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.10">RFC 9000 §19.10 — MAX_STREAM_DATA Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.13">RFC 9000 §19.13 — STREAM_DATA_BLOCKED Frames</a>
 */
public final class StreamFlowControlTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long STREAM_ID = 0;
	private static final int MAX_FRAME_DATA = 100;
	private static final int STREAM_LIMIT = 120;

	/** The peer's window for a bidirectional stream <i>we</i> opened, in the loopback half of this test. */
	private static final int PEER_STREAM_WINDOW = 8 * 1024;

	/** The transport, reduced to a list — plus the blocked announcements, which are the point here. */
	private static final class RecordingSink implements SendPart.Sink {
		final List<StreamFrame> frames = new ArrayList<>();
		final List<Long> streamDataBlocked = new ArrayList<>();
		final List<Long> dataBlocked = new ArrayList<>();
		long outstandingBudget = 1 << 20;
		long outstanding;
		int requestSendCalls;

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
			requestSendCalls++;
		}

		@Override
		public boolean onStreamDataBlocked(long streamId, long limit) {
			streamDataBlocked.add(limit);
			return true;
		}

		@Override
		public boolean onDataBlocked(long limit) {
			dataBlocked.add(limit);
			return true;
		}

		long bytesEnqueued() {
			long total = 0;
			for (StreamFrame frame : frames) {
				total += frame.data.readRemaining();
			}
			return total;
		}

		long highestOffsetEnqueued() {
			long highest = 0;
			for (StreamFrame frame : frames) {
				highest = Math.max(highest, frame.offset + frame.data.readRemaining());
			}
			return highest;
		}

		void recycleAll() {
			frames.forEach(StreamFrame::recycle);
			frames.clear();
		}
	}

	/** Records what it is given by class name; never grants credit back. */
	private static final class RecordingHandler implements QuicFrameHandler {
		final List<String> received = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			received.add(frame.getClass().getSimpleName());
		}
	}

	private RecordingSink sink;
	private StreamFlowController streamFlowControl;
	private SendPart part;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	@Before
	public void setUp() {
		sink = new RecordingSink();
		streamFlowControl = new StreamFlowController(STREAM_LIMIT);
		part = new SendPart(STREAM_ID, streamFlowControl,
			ConnectionFlowController.create(1 << 20, 1 << 20), MAX_FRAME_DATA, sink);
	}

	@After
	public void tearDown() {
		part.closeEx(new IllegalStateException("test teardown"));
		sink.recycleAll();
		if (wire != null) wire.close();
		if (loop != null) loop.close();
	}

	// ---------------------------------------------------------------- helpers

	/** A pattern whose every byte depends on its offset, so a reorder or a gap cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, bytes.length));
		buf.put(bytes);
		return buf;
	}

	/** Starts a pair whose <b>server</b> only records, so no credit is ever granted back. */
	private RecordingHandler handshakeWithASilentServer(int peerStreamWindow) throws MalformedDataException {
		loop = new ManualEventloop();
		RecordingHandler serverHandler = new RecordingHandler();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(serverHandler);
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			// What the client may send on a bidirectional stream *it* opened (RFC 9000 §18.2).
			.withInitialMaxStreamDataBidiRemote(MemSize.of(peerStreamWindow))
			.build());
		wire.pump();
		return serverHandler;
	}

	// ---------------------------------------------------------------- scenario 1: withheld, and silent

	@Test
	public void aWriteWithoutCreditStaysUnresolvedAndPutsNothingAboveTheLimitOnTheWire() {
		Promise<Void> written = part.write(buf(pattern(250, 1)));

		assertFalse("a write blocked by the stream limit must be withheld, not failed", written.isComplete());
		assertEquals("not one byte may be given an offset the peer has not permitted",
			STREAM_LIMIT, sink.highestOffsetEnqueued());
		assertEquals(STREAM_LIMIT, sink.bytesEnqueued());
		assertEquals(STREAM_LIMIT, part.writeOffset());
		assertTrue(part.hasPendingWrite());
	}

	@Test
	public void aWriteWithNoCreditAtAllProducesNoFrameAndNoSend() {
		streamFlowControl.consume(streamFlowControl.available());

		Promise<Void> written = part.write(buf(pattern(50, 2)));

		assertFalse(written.isComplete());
		assertEquals(0, sink.frames.size());
		assertEquals("a batch that produced only a blocked announcement still flushes once",
			1, sink.requestSendCalls);
		assertEquals(List.of((long) STREAM_LIMIT), sink.streamDataBlocked);
	}

	// ---------------------------------------------------------------- scenario 2: resumed by MAX_STREAM_DATA

	@Test
	public void raisingTheStreamLimitResumesTheWithheldWriteAtContiguousOffsets() {
		byte[] expected = pattern(250, 3);
		Promise<Void> written = part.write(buf(expected));
		assertFalse(written.isComplete());

		assertTrue(streamFlowControl.raiseLimit(250));
		part.pump();

		assertTrue("the write resolves once every one of its bytes has become a frame", written.isResult());
		assertEquals(250, part.writeOffset());
		assertEquals(250, sink.highestOffsetEnqueued());
		int offset = 0;
		for (StreamFrame frame : sink.frames) {
			assertEquals("offsets must stay contiguous across the block", offset, frame.offset);
			byte[] actual = frame.data.getArray();
			for (int i = 0; i < actual.length; i++) {
				assertEquals("byte " + (offset + i) + " must survive the block unchanged",
					expected[offset + i], actual[i]);
			}
			offset += actual.length;
		}
		assertEquals(250, offset);
	}

	@Test
	public void aLimitThatIsNotAnIncreaseChangesNothing() {
		part.write(buf(pattern(250, 4)));

		assertFalse("RFC 9000 §4.1: a stale limit is ignored without error (FR-026)",
			streamFlowControl.raiseLimit(STREAM_LIMIT));
		assertFalse(streamFlowControl.raiseLimit(7));
		part.pump();

		assertEquals(STREAM_LIMIT, part.writeOffset());
		assertEquals(STREAM_LIMIT, sink.highestOffsetEnqueued());
	}

	@Test
	public void raisingTheLimitPartWayResumesOnlyAsFarAsTheNewLimit() {
		Promise<Void> written = part.write(buf(pattern(250, 5)));

		streamFlowControl.raiseLimit(200);
		part.pump();

		assertFalse("still short of the write's 250 bytes", written.isComplete());
		assertEquals(200, part.writeOffset());
		assertEquals(200, sink.highestOffsetEnqueued());

		streamFlowControl.raiseLimit(1000);
		part.pump();

		assertTrue(written.isResult());
		assertEquals(250, part.writeOffset());
	}

	// ---------------------------------------------------------------- FR-027, at the stream level

	@Test
	public void eachDistinctStreamLimitIsAnnouncedAtMostOnce() {
		part.write(buf(pattern(250, 6)));
		assertEquals(List.of((long) STREAM_LIMIT), sink.streamDataBlocked);

		// Still blocked at the same limit, however many times something retries.
		part.pump();
		part.pump();
		assertEquals("FR-027: one announcement per distinct limit value",
			List.of((long) STREAM_LIMIT), sink.streamDataBlocked);

		streamFlowControl.raiseLimit(200);
		part.pump();
		assertEquals(List.of((long) STREAM_LIMIT, 200L), sink.streamDataBlocked);
	}

	@Test
	public void aWriteBlockedOnlyByTheOutstandingBudgetAnnouncesNothing() {
		// The budget is a local bound, not a limit the peer granted — there is nothing to tell it about.
		RecordingSink budgetSink = new RecordingSink();
		budgetSink.outstandingBudget = 150;
		SendPart budgeted = new SendPart(STREAM_ID, new StreamFlowController(1 << 20),
			ConnectionFlowController.create(1 << 20, 1 << 20), MAX_FRAME_DATA, budgetSink);
		try {
			Promise<Void> written = budgeted.write(buf(pattern(250, 7)));

			assertFalse(written.isComplete());
			assertEquals(150, budgeted.writeOffset());
			assertEquals(List.of(), budgetSink.streamDataBlocked);
			assertEquals(List.of(), budgetSink.dataBlocked);
		} finally {
			budgeted.closeEx(new IllegalStateException("test teardown"));
			budgetSink.recycleAll();
		}
	}

	// ---------------------------------------------------------------- the same thing, over a connection

	@Test
	public void aMaxStreamDataFrameResumesAWithheldWriteOnALiveConnection() throws Exception {
		RecordingHandler serverHandler = handshakeWithASilentServer(PEER_STREAM_WINDOW);

		QuicStream stream = clientManager.openBidirectional().getResult();
		assertNotNull(stream);
		Promise<Void> written = stream.writer().accept(buf(pattern(4 * PEER_STREAM_WINDOW, 8)));
		wire.pump();

		assertFalse("the peer granted " + PEER_STREAM_WINDOW + " bytes and no more", written.isComplete());
		SendPart sendPart = stream.sendPart();
		assertNotNull(sendPart);
		assertEquals(PEER_STREAM_WINDOW, sendPart.writeOffset());
		assertTrue("the peer must be told the sender is blocked (FR-027)",
			serverHandler.received.contains("StreamDataBlockedFrame"));
		assertEquals(1, clientManager.timesBlockedByStreamLimit());

		// The peer opens the window the whole way; the write finishes without another prompt.
		clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT,
			new MaxStreamDataFrame(stream.id(), 4L * PEER_STREAM_WINDOW));
		wire.pump();

		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals(4 * PEER_STREAM_WINDOW, sendPart.writeOffset());
		assertEquals(4 * PEER_STREAM_WINDOW, clientManager.bytesSent());
	}

	@Test
	public void aStaleMaxStreamDataFrameOnALiveConnectionNeitherLowersTheLimitNorResumesTheWrite()
		throws Exception {
		handshakeWithASilentServer(PEER_STREAM_WINDOW);

		QuicStream stream = clientManager.openBidirectional().getResult();
		assertNotNull(stream);
		Promise<Void> written = stream.writer().accept(buf(pattern(4 * PEER_STREAM_WINDOW, 9)));
		wire.pump();
		assertFalse(written.isComplete());

		clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT,
			new MaxStreamDataFrame(stream.id(), PEER_STREAM_WINDOW / 2));
		wire.pump();

		SendPart sendPart = stream.sendPart();
		assertNotNull(sendPart);
		assertFalse(written.isComplete());
		assertEquals(PEER_STREAM_WINDOW, sendPart.maxDataOffset());
		assertEquals(PEER_STREAM_WINDOW, sendPart.writeOffset());
	}

	/**
	 * RFC 9000 §19.10: "An endpoint that receives a MAX_STREAM_DATA frame for a […] stream it has not
	 * opened MUST terminate the connection with error STREAM_STATE_ERROR." Stream 400 is
	 * client-initiated bidirectional, and this endpoint is the client, so a limit for it names a sending
	 * half that does not exist.
	 * <p>
	 * <b>Corrected in T101.</b> This method previously asserted the frame was ignored, which was the
	 * behaviour of the pre-T101 routing but not the behaviour RFC 9000 §19.10 requires: tolerating it
	 * lets a peer probe which of an endpoint's streams exist, and leaves the one row of the Error
	 * Scenarios table these frames fall under untested. The tolerated case is the *released* stream —
	 * asserted in {@link InformationalFrameIdentifierTest} — which is a different fact.
	 */
	@Test
	public void aMaxStreamDataFrameForALocallyInitiatedStreamNeverOpenedIsAStreamStateError()
		throws Exception {
		handshakeWithASilentServer(PEER_STREAM_WINDOW);

		QuicTransportException e = assertThrows(QuicTransportException.class, () ->
			clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT,
				new MaxStreamDataFrame(400, 1 << 20)));

		assertEquals(QuicTransportErrors.STREAM_STATE_ERROR, e.errorCode());
		assertEquals("nothing may be opened on behalf of a frame that is refused", 0,
			clientManager.openStreamCount());
	}

	// ---------------------------------------------------------------- retryBlockedWriters, T126 regression

	/**
	 * {@link QuicStreamManager#retryBlockedWriters()} retries the tracked {@code pendingWriters} set
	 * rather than walking every open stream. The risk of that optimisation is a stall: a writer the set
	 * fails to track is one a {@code MAX_DATA} can never reach again.
	 * <p>
	 * Nine unidirectional streams share one connection-level window (RFC 9000 §4.1). Eight of them write
	 * little enough to complete <em>synchronously</em> and are never pending; the ninth is deliberately
	 * driven past what is left of the window, so its write is genuinely withheld while every other stream
	 * merely sits in the manager's stream map with nothing to retry. Raising the connection-level limit
	 * with a {@code MAX_DATA} (RFC 9000 §19.9) must retry that one write and complete it — a gap in the
	 * tracked set around it would hang this test rather than fail an assertion.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.9">RFC 9000 §19.9 — MAX_DATA Frames</a>
	 */
	@Test
	public void maxDataRetriesExactlyTheOneStreamGenuinelyBlockedAmongManyOpenOnes() throws Exception {
		int streamCount = 9;
		int blockedIndex = streamCount - 1;
		int easyChunk = 8_000;
		long easyTotal = (long) easyChunk * blockedIndex;
		// Only 6,000 bytes of connection-level window survive the eight easy writes — comfortably
		// less than the 15,000-byte payload the ninth stream is about to attempt.
		long connectionWindow = easyTotal + 6_000;
		// Per-stream, not connection-wide, and large enough that the ninth stream's whole payload fits
		// under it: the only limit this test drives is the connection-level one.
		long perStreamWindow = 20_000;

		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamsUni(streamCount)
			.withInitialMaxData(MemSize.of(connectionWindow))
			// The per-stream windows default to 256 KiB, which the build-time check refuses to pair
			// with a connection-level window this small (RFC 9000 §4.1: a stream cannot be trusted
			// with more credit than the connection could ever honour) — so each is capped down, but
			// still well above what either the easy or the blocked write ever needs from it alone.
			.withInitialMaxStreamDataUni(MemSize.of(perStreamWindow))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(perStreamWindow))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(perStreamWindow))
			.build());
		wire.pump();

		List<QuicStream> streams = new ArrayList<>();
		for (int s = 0; s < streamCount; s++) {
			Promise<QuicStream> opened = clientManager.openUnidirectional();
			assertTrue("the connection is established and the peer granted " + streamCount + " uni streams",
				opened.isComplete());
			QuicStream stream = opened.getResult();
			assertNotNull(stream);
			streams.add(stream);
		}

		// Eight streams, each comfortably under both the stream-level window and what the connection
		// window has left: every one of these writes completes without ever becoming pending.
		for (int s = 0; s < blockedIndex; s++) {
			Promise<Void> written = streams.get(s).writer().accept(buf(pattern(easyChunk, s)));
			assertTrue("stream " + s + " had nothing to block it and must complete synchronously",
				written.isResult());
			SendPart easyPart = streams.get(s).sendPart();
			assertNotNull(easyPart);
			assertFalse(easyPart.hasPendingWrite());
		}
		assertEquals(easyTotal, clientManager.bytesSent());

		// The ninth stream asks for far more than the connection window has left, but comfortably
		// under its own generous per-stream window — the block below must be the connection's alone.
		byte[] bigPayload = pattern(15_000, blockedIndex);
		Promise<Void> blockedWrite = streams.get(blockedIndex).writer().accept(buf(bigPayload));
		wire.pump();

		SendPart blockedPart = streams.get(blockedIndex).sendPart();
		assertNotNull(blockedPart);
		assertFalse("the ninth stream must genuinely block on the connection-level window",
			blockedWrite.isComplete());
		assertTrue("only this part's write is withheld", blockedPart.hasPendingWrite());
		assertEquals(connectionWindow - easyTotal, blockedPart.writeOffset());
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());

		// None of the other eight streams ever had a pending write, and still do not: it must be
		// possible to retry the ninth without walking (or needing anything from) any of these.
		for (int s = 0; s < blockedIndex; s++) {
			SendPart easyPart = streams.get(s).sendPart();
			assertNotNull(easyPart);
			assertFalse(easyPart.hasPendingWrite());
		}

		// The peer grants a connection window large enough for the whole of the ninth stream's write.
		clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT,
			new MaxDataFrame(easyTotal + bigPayload.length));
		wire.pump();

		assertTrue(String.valueOf(blockedWrite.getException()), blockedWrite.isResult());
		assertFalse("retryBlockedWriters must have found and pumped this part", blockedPart.hasPendingWrite());
		assertEquals(bigPayload.length, blockedPart.writeOffset());
		assertEquals(easyTotal + bigPayload.length, clientManager.bytesSent());
	}
}
