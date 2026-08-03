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
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import io.activej.quic.codec.StreamFrame;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T026 — the RFC 9000 §3.1 sending state machine, driven directly. No connection: a
 * {@link SendPart} emits through a {@link SendPart.Sink}, and the fake sink here is the whole
 * transport, so what would otherwise be observable only as bytes on a wire is inspectable as a list
 * of {@link StreamFrame}s.
 * <p>
 * What is asserted (FR-005, FR-015, FR-016, research R-03):
 * <ul>
 *   <li>{@code Ready → Send → Data Sent → Data Recvd}, with Data Recvd reached only when
 *       <em>every</em> frame has been acknowledged — a lost frame is not an acknowledgement;</li>
 *   <li>offsets assigned contiguously, in order, across fragmentation and across writes;</li>
 *   <li>{@code FIN} attachable to the final write, and emittable on its own;</li>
 *   <li>the batching rule: one {@code requestSend()} per write, however many frames it produced.</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class SendPartTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long STREAM_ID = 0;
	private static final int MAX_FRAME_DATA = 100;

	/** The transport, reduced to a list. Frames it accepts are its to recycle, as a real one's are. */
	private static final class RecordingSink implements SendPart.Sink {
		final List<StreamFrame> frames = new ArrayList<>();
		long outstandingBudget = 1 << 20;
		long outstanding;
		int requestSendCalls;
		int terminalCalls;

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
		public void onTerminal(SendPart part) {
			terminalCalls++;
		}

		void recycleAll() {
			frames.forEach(StreamFrame::recycle);
			frames.clear();
		}
	}

	private RecordingSink sink;
	private StreamFlowController streamFlowControl;
	private ConnectionFlowController connectionFlowControl;
	private SendPart part;
	private ChannelConsumer<ByteBuf> writer;

	@Before
	public void setUp() {
		sink = new RecordingSink();
		streamFlowControl = new StreamFlowController(1 << 20);
		connectionFlowControl = ConnectionFlowController.create(1 << 20, 1 << 20);
		part = new SendPart(STREAM_ID, streamFlowControl, connectionFlowControl, MAX_FRAME_DATA, sink);
		writer = part.consumer();
	}

	@After
	public void tearDown() {
		part.closeEx(new IllegalStateException("test teardown"));
		sink.recycleAll();
	}

	// ---------------------------------------------------------------- helpers

	private static ByteBuf bytes(int length, char fill) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) fill);
		}
		return buf;
	}

	private static ByteBuf bytes(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private void acknowledgeAll() {
		List<StreamFrame> sent = new ArrayList<>(sink.frames);
		sink.frames.clear();
		for (StreamFrame frame : sent) {
			part.onFrameAcknowledged(frame);
		}
	}

	// ---------------------------------------------------------------- Ready → Send → Data Sent → Data Recvd

	@Test
	public void aFreshPartIsReadyWithNothingWritten() {
		assertEquals(SendState.READY, part.state());
		assertEquals(0, part.writeOffset());
		assertNull(part.finalSize());
		assertEquals(0, part.outstandingBytes());
		assertFalse(part.isTerminal());
	}

	@Test
	public void theFirstWriteMovesReadyToSend() {
		Promise<Void> written = part.write(bytes("hello"));

		assertTrue(written.isComplete());
		assertTrue(written.isResult());
		assertEquals(SendState.SEND, part.state());
		assertEquals(5, part.writeOffset());
		assertNull(part.finalSize());
		assertEquals(1, sink.frames.size());
		assertEquals(0, sink.frames.get(0).offset);
		assertFalse(sink.frames.get(0).fin);
	}

	@Test
	public void aFinAttachedToTheFinalWriteMovesSendToDataSent() {
		part.write(bytes("hello "));
		assertEquals(SendState.SEND, part.state());

		Promise<Void> written = part.write(bytes("world"), true);

		assertTrue(written.isResult());
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(Long.valueOf(11), part.finalSize());
		assertEquals(11, part.writeOffset());
		assertEquals(2, sink.frames.size());
		assertFalse(sink.frames.get(0).fin);
		assertTrue("FIN must ride on the last frame of the final write", sink.frames.get(1).fin);
	}

	@Test
	public void aStandaloneFinIsAZeroLengthFrameAtTheWriteOffset() {
		part.write(bytes("abc"));

		Promise<Void> finished = part.writeFin();

		assertTrue(finished.isResult());
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(Long.valueOf(3), part.finalSize());
		StreamFrame fin = sink.frames.get(sink.frames.size() - 1);
		assertTrue(fin.fin);
		assertEquals(3, fin.offset);
		assertEquals(0, fin.data.readRemaining());
	}

	@Test
	public void anEmptyStreamIsAZeroLengthFinAtOffsetZero() {
		Promise<Void> finished = part.writeFin();

		assertTrue(finished.isResult());
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(Long.valueOf(0), part.finalSize());
		assertEquals(1, sink.frames.size());
		assertTrue(sink.frames.get(0).fin);
		assertEquals(0, sink.frames.get(0).offset);
	}

	@Test
	public void dataSentBecomesDataRecvdOnlyWhenEveryFrameIsAcknowledged() {
		part.write(bytes(250, 'x'), true);
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(250, part.outstandingBytes());
		assertEquals(3, sink.frames.size());

		// Two of the three: still Data Sent.
		StreamFrame first = sink.frames.remove(0);
		part.onFrameAcknowledged(first);
		StreamFrame second = sink.frames.remove(0);
		part.onFrameAcknowledged(second);
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(0, sink.terminalCalls);

		StreamFrame third = sink.frames.remove(0);
		part.onFrameAcknowledged(third);
		assertEquals(SendState.DATA_RECVD, part.state());
		assertTrue(part.isTerminal());
		assertEquals(0, part.outstandingBytes());
		assertEquals(1, sink.terminalCalls);
	}

	@Test
	public void aLostFrameIsNotAnAcknowledgementAndIsResentAtItsOriginalOffset() {
		part.write(bytes("hello"), true);
		assertEquals(1, sink.frames.size());

		StreamFrame lost = sink.frames.remove(0);
		long offset = lost.offset;
		part.onFrameLost(lost);

		// Still Data Sent: loss is not acknowledgement, whatever else the layer does with the frame.
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(0, sink.terminalCalls);
		assertEquals("the lost frame must be re-enqueued at its original offset", 1, sink.frames.size());
		assertEquals(offset, sink.frames.get(0).offset);
		assertTrue(sink.frames.get(0).fin);
		// R-08: the bytes are still outstanding — they are being resent, not forgotten.
		assertEquals(5, part.outstandingBytes());

		acknowledgeAll();
		assertEquals(SendState.DATA_RECVD, part.state());
	}

	// ---------------------------------------------------------------- offsets

	@Test
	public void aWriteLargerThanTheFrameCapIsFragmentedAtContiguousAscendingOffsets() {
		part.write(bytes(250, 'a'));

		assertEquals(3, sink.frames.size());
		assertEquals(0, sink.frames.get(0).offset);
		assertEquals(MAX_FRAME_DATA, sink.frames.get(0).data.readRemaining());
		assertEquals(100, sink.frames.get(1).offset);
		assertEquals(MAX_FRAME_DATA, sink.frames.get(1).data.readRemaining());
		assertEquals(200, sink.frames.get(2).offset);
		assertEquals(50, sink.frames.get(2).data.readRemaining());
		assertEquals(250, part.writeOffset());
	}

	@Test
	public void offsetsNeverReorderAcrossSuccessiveWrites() {
		part.write(bytes(150, 'a'));
		part.write(bytes(150, 'b'));
		part.write(bytes(10, 'c'), true);

		long expected = 0;
		for (StreamFrame frame : sink.frames) {
			assertEquals("offsets must be assigned contiguously, in order", expected, frame.offset);
			expected += frame.data.readRemaining();
		}
		assertEquals(310, expected);
		assertEquals(Long.valueOf(310), part.finalSize());
	}

	@Test
	public void aWriteResolvesOnlyOnceEveryOneOfItsBytesHasBecomeAFrame() {
		// R-06: the promise is the backpressure signal, so it must not resolve early.
		Promise<Void> written = part.write(bytes(250, 'a'));
		assertTrue(written.isResult());
		assertEquals(250, part.writeOffset());
	}

	// ---------------------------------------------------------------- the batching rule (R-03, T035)

	@Test
	public void oneWriteRequestsOneSendHoweverManyFramesItProduced() {
		part.write(bytes(250, 'a'));

		assertEquals(3, sink.frames.size());
		assertEquals("requestSend() is called once per batch, not once per frame", 1, sink.requestSendCalls);
	}

	@Test
	public void aWriteThatProducesNoFrameRequestsNoSend() {
		streamFlowControl.consume(streamFlowControl.available());   // no credit left at all

		Promise<Void> written = part.write(bytes(10, 'a'));

		assertFalse("a blocked write must not resolve", written.isComplete());
		assertEquals(0, sink.frames.size());
		assertEquals(0, sink.requestSendCalls);
	}

	// ---------------------------------------------------------------- the offset invariant (FR-021, FR-022)

	@Test
	public void noOffsetIsEverAssignedAtOrAboveTheStreamLimit() {
		SendPart limited = new SendPart(STREAM_ID, new StreamFlowController(120),
			ConnectionFlowController.create(1 << 20, 1 << 20), MAX_FRAME_DATA, sink);

		Promise<Void> written = limited.write(bytes(250, 'a'));

		assertFalse("the part of the write above the limit must stay withheld", written.isComplete());
		assertEquals(120, limited.writeOffset());
		long highest = 0;
		for (StreamFrame frame : sink.frames) {
			highest = Math.max(highest, frame.offset + frame.data.readRemaining());
		}
		assertEquals("not one byte may be given an offset the peer has not permitted", 120, highest);

		limited.closeEx(new IllegalStateException("test teardown"));
	}

	@Test
	public void noOffsetIsEverAssignedAtOrAboveTheConnectionLimit() {
		ConnectionFlowController tight = ConnectionFlowController.create(80, 1 << 20);
		SendPart limited = new SendPart(STREAM_ID, new StreamFlowController(1 << 20), tight, MAX_FRAME_DATA, sink);

		Promise<Void> written = limited.write(bytes(250, 'a'));

		assertFalse(written.isComplete());
		assertEquals(80, limited.writeOffset());
		assertEquals(80, tight.sendUsed());

		limited.closeEx(new IllegalStateException("test teardown"));
	}

	@Test
	public void theOutstandingBudgetBoundsWhatMayBeInFlight() {
		sink.outstandingBudget = 150;

		Promise<Void> written = part.write(bytes(250, 'a'));

		assertFalse(written.isComplete());
		assertEquals(150, part.outstandingBytes());
		assertEquals(150, sink.outstanding);
		assertEquals(150, part.writeOffset());
	}

	// ---------------------------------------------------------------- the CSP surface

	@Test
	public void theConsumerTakesOwnershipAndAcceptNullWritesFin() {
		Promise<Void> written = writer.accept(bytes("hello"));
		assertTrue(written.isResult());

		Promise<Void> finished = writer.accept(null);
		assertTrue(finished.isResult());
		assertEquals(SendState.DATA_SENT, part.state());
		assertEquals(Long.valueOf(5), part.finalSize());
	}

	@Test
	public void closingReleasesTheWithheldBufferAndFailsItsWrite() {
		streamFlowControl.consume(streamFlowControl.available());
		Promise<Void> written = part.write(bytes(10, 'a'));
		assertFalse(written.isComplete());

		IllegalStateException cause = new IllegalStateException("connection closed");
		part.closeEx(cause);

		assertTrue(written.isComplete());
		assertTrue(written.isException());
		assertSame(cause, written.getException());
		// ByteBufRule is the rest of the assertion: the withheld buffer must have been recycled.
	}

	@Test
	public void aWriteAfterCloseIsStillOwnedAndFails() {
		part.closeEx(new IllegalStateException("connection closed"));

		Promise<Void> written = part.write(bytes("late"));

		assertTrue(written.isException());
	}
}
