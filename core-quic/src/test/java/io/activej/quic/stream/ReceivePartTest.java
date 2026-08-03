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
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
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
 * T025 — the RFC 9000 §3.2 receiving state machine, driven directly: no connection, no manager, no
 * wire. A {@link ReceivePart} is constructed on its own and fed frame fields, which is the only way to
 * assert the transitions that a loopback test can only observe indirectly.
 * <p>
 * The three things worth breaking the machine down for (FR-005, FR-010):
 * <ul>
 *   <li>{@code Recv → Size Known → Data Recvd → Data Read} — four states, and the last of them
 *       requires an <em>application</em> action, not a wire event;</li>
 *   <li>a {@code FIN} arriving <b>before</b> the bytes it terminates: legal, common on a reordering
 *       path, and the one case where Size Known does not immediately imply Data Recvd;</li>
 *   <li>end-of-stream delivered <b>exactly once</b>, and only once the last gap has closed — a parked
 *       reader must not be handed {@code null} while a hole remains below the final size.</li>
 * </ul>
 * Promises are inspected rather than awaited: half of what is asserted here is that a read stays
 * <i>unresolved</i>, and {@code await} on such a promise would hang instead of failing.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.5">RFC 9000 §4.5 — Stream Final Size</a>
 */
public final class ReceivePartTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long STREAM_ID = 0;
	private static final long WINDOW = 1024;
	private static final int MAX_RANGES = 8;

	private ReceivePart part;
	private ChannelSupplier<ByteBuf> reader;
	private final List<Long> consumedEvents = new ArrayList<>();
	private int terminalEvents;

	@Before
	public void setUp() {
		consumedEvents.clear();
		terminalEvents = 0;
		part = new ReceivePart(STREAM_ID, WINDOW, MAX_RANGES, new ReceivePart.Listener() {
			@Override
			public void onBytesConsumed(ReceivePart part, long bytes) {
				consumedEvents.add(bytes);
			}

			@Override
			public void onTerminal(ReceivePart part) {
				terminalEvents++;
			}
		});
		reader = part.supplier();
	}

	/**
	 * Several cases here deliberately leave data buffered — that is what they are asserting — so the
	 * part is closed for every test rather than for some. {@code closeEx} is idempotent, so the tests
	 * that close it themselves are unaffected, and {@code ByteBufRule} still catches a range this class
	 * failed to release.
	 */
	@After
	public void tearDown() {
		part.closeEx(new IllegalStateException("test teardown"));
	}

	// ---------------------------------------------------------------- helpers

	private static ByteBuf bytes(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private long feed(long offset, String data, boolean fin) throws QuicTransportException {
		return part.onStreamFrame(offset, fin, bytes(data));
	}

	/** Takes a resolved read and returns its contents, recycling the buffer the caller now owns. */
	private static String take(Promise<ByteBuf> promise) {
		assertTrue("the read should already be resolved", promise.isComplete());
		assertTrue(promise.isResult());
		ByteBuf buf = promise.getResult();
		assertNotNull("expected data, got end-of-stream", buf);
		String s = buf.getString(StandardCharsets.US_ASCII);
		buf.recycle();
		return s;
	}

	private static void assertEndOfStream(Promise<ByteBuf> promise) {
		assertTrue("the read should already be resolved", promise.isComplete());
		assertTrue(promise.isResult());
		assertNull("expected end-of-stream", promise.getResult());
	}

	// ---------------------------------------------------------------- Recv → Size Known → Data Recvd → Data Read

	@Test
	public void aFreshPartIsInRecvWithNothingKnown() {
		assertEquals(ReceiveState.RECV, part.state());
		assertEquals(0, part.highestOffsetReceived());
		assertEquals(0, part.consumedOffset());
		assertNull(part.finalSize());
		assertFalse(part.isTerminal());
	}

	@Test
	public void inOrderDataStaysInRecvAndIsReadableImmediately() throws QuicTransportException {
		assertEquals(5, feed(0, "hello", false));
		assertEquals(ReceiveState.RECV, part.state());
		assertEquals(5, part.highestOffsetReceived());

		assertEquals("hello", take(reader.get()));
		assertEquals(5, part.consumedOffset());
		assertEquals(List.of(5L), consumedEvents);

		// Nothing more has arrived and the final size is unknown, so the next read parks.
		assertFalse(reader.get().isComplete());
		assertEquals(ReceiveState.RECV, part.state());
	}

	@Test
	public void theFullHappyPathWalksAllFourStates() throws QuicTransportException {
		feed(0, "hello ", false);
		assertEquals(ReceiveState.RECV, part.state());

		feed(6, "world", true);
		// Every byte below the final size is already buffered, so Size Known is passed through in the
		// same call: the state machine's condition is "all data received", not "FIN received last".
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals(Long.valueOf(11), part.finalSize());

		assertEquals("hello ", take(reader.get()));
		assertEquals("world", take(reader.get()));
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals(0, terminalEvents);

		assertEndOfStream(reader.get());
		assertEquals(ReceiveState.DATA_READ, part.state());
		assertTrue(part.isTerminal());
		assertEquals(1, terminalEvents);
	}

	@Test
	public void aZeroLengthFinAtOffsetZeroIsAnEmptyStreamThatEndsAtOnce() throws QuicTransportException {
		assertEquals(0, feed(0, "", true));
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals(Long.valueOf(0), part.finalSize());

		assertEndOfStream(reader.get());
		assertEquals(ReceiveState.DATA_READ, part.state());
	}

	@Test
	public void aFinAfterEveryByteMovesStraightToDataRecvd() throws QuicTransportException {
		feed(0, "abc", false);
		assertEquals(ReceiveState.RECV, part.state());

		// A zero-length FIN at the current end: legal, and it closes the stream without new data.
		feed(3, "", true);
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals(Long.valueOf(3), part.finalSize());
	}

	// ---------------------------------------------------------------- FIN before the bytes it terminates

	@Test
	public void aFinArrivingBeforeItsPrecedingBytesOnlyReachesSizeKnown() throws QuicTransportException {
		// The FIN-bearing frame is the *last* of the stream by offset, but the first to arrive.
		feed(6, "world", true);
		assertEquals(ReceiveState.SIZE_KNOWN, part.state());
		assertEquals(Long.valueOf(11), part.finalSize());
		assertEquals(11, part.highestOffsetReceived());

		// Nothing is contiguous yet, so a reader parks rather than seeing end-of-stream.
		Promise<ByteBuf> parked = reader.get();
		assertFalse("end-of-stream must not be delivered while a gap remains", parked.isComplete());

		feed(0, "hello ", false);
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals("hello ", take(parked));

		assertEquals("world", take(reader.get()));
		assertEndOfStream(reader.get());
		assertEquals(ReceiveState.DATA_READ, part.state());
	}

	@Test
	public void aGapBelowTheFinalSizeKeepsThePartInSizeKnown() throws QuicTransportException {
		feed(0, "aaa", false);
		feed(6, "ccc", true);
		assertEquals(ReceiveState.SIZE_KNOWN, part.state());
		assertEquals(Long.valueOf(9), part.finalSize());

		assertEquals("aaa", take(reader.get()));
		Promise<ByteBuf> parked = reader.get();
		assertFalse(parked.isComplete());
		assertEquals(ReceiveState.SIZE_KNOWN, part.state());

		feed(3, "bbb", false);
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals("bbb", take(parked));
		assertEquals("ccc", take(reader.get()));
		assertEndOfStream(reader.get());
	}

	// ---------------------------------------------------------------- end-of-stream exactly once

	@Test
	public void endOfStreamIsDeliveredExactlyOnceAndOnlyAfterTheLastGapCloses() throws QuicTransportException {
		feed(3, "def", true);
		assertEquals(ReceiveState.SIZE_KNOWN, part.state());

		Promise<ByteBuf> parked = reader.get();
		assertFalse(parked.isComplete());

		feed(0, "abc", false);
		assertEquals("abc", take(parked));
		assertEquals("def", take(reader.get()));

		assertEndOfStream(reader.get());
		assertEquals(1, terminalEvents);

		// A reader that asks again still sees end-of-stream, and the terminal transition does not repeat.
		assertEndOfStream(reader.get());
		assertEquals("the terminal notification must fire once", 1, terminalEvents);
		assertEquals(ReceiveState.DATA_READ, part.state());
	}

	@Test
	public void aParkedReadIsResolvedByTheFrameThatClosesTheGap() throws QuicTransportException {
		Promise<ByteBuf> parked = reader.get();
		assertFalse(parked.isComplete());

		feed(0, "now", false);
		assertEquals("now", take(parked));
		assertEquals(List.of(3L), consumedEvents);
	}

	@Test
	public void consumedOffsetTracksOnlyWhatTheApplicationTook() throws QuicTransportException {
		feed(0, "12345", false);
		feed(5, "67890", false);
		assertEquals(10, part.highestOffsetReceived());
		assertEquals(0, part.consumedOffset());

		assertEquals("12345", take(reader.get()));
		assertEquals(5, part.consumedOffset());
		assertEquals("67890", take(reader.get()));
		assertEquals(10, part.consumedOffset());
		assertEquals(List.of(5L, 5L), consumedEvents);
	}

	// ---------------------------------------------------------------- final-size violations (FR-012)

	@Test
	public void dataPastAKnownFinalSizeIsAFinalSizeError() throws QuicTransportException {
		feed(0, "abc", true);
		assertEquals(Long.valueOf(3), part.finalSize());

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> feed(3, "d", false));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void aSecondFinDeclaringADifferentFinalSizeIsAFinalSizeError() throws QuicTransportException {
		feed(0, "abc", true);

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> feed(0, "ab", true));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void aFinBelowDataAlreadyReceivedIsAFinalSizeError() throws QuicTransportException {
		feed(4, "efgh", false);
		assertEquals(8, part.highestOffsetReceived());

		// Declaring a final size of 3 contradicts the eight bytes already accounted for.
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> feed(0, "abc", true));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void aRepeatedFinWithTheSameFinalSizeIsAccepted() throws QuicTransportException {
		feed(0, "abc", true);
		feed(0, "abc", true);
		assertEquals(ReceiveState.DATA_RECVD, part.state());
		assertEquals("abc", take(reader.get()));
		assertEndOfStream(reader.get());
	}

	// ---------------------------------------------------------------- accounting and ownership

	@Test
	public void onlyNewlyReceivedBytesAreChargedToConnectionFlowControl() throws QuicTransportException {
		assertEquals(4, feed(0, "abcd", false));
		// A pure duplicate has already been paid for.
		assertEquals(0, feed(0, "abcd", false));
		// A partial overlap charges only the part above the high-water mark.
		assertEquals(2, feed(2, "cdef", false));
		assertEquals(6, part.highestOffsetReceived());
	}

	@Test
	public void everyFrameIsOwnedOnEveryPathIncludingTheThrowingOne() throws QuicTransportException {
		// ByteBufRule is the assertion: a leak on any of these paths fails the class.
		feed(0, "abc", true);                                              // buffered and delivered
		assertThrows(QuicTransportException.class, () -> feed(3, "x", false));  // rejected
		part.closeEx(new IllegalStateException("test teardown"));               // discarded undelivered
	}

	@Test
	public void closingFailsTheParkedReadAndReleasesEverythingHeld() throws QuicTransportException {
		feed(4, "efgh", false);
		Promise<ByteBuf> parked = reader.get();
		assertFalse(parked.isComplete());

		IllegalStateException cause = new IllegalStateException("connection closed");
		part.closeEx(cause);

		assertTrue(parked.isComplete());
		assertTrue(parked.isException());
		assertSame(cause, parked.getException());
	}

	@Test
	public void aFrameArrivingAfterCloseIsStillOwnedAndDiscarded() throws QuicTransportException {
		part.closeEx(new IllegalStateException("connection closed"));
		assertEquals(0, feed(0, "abc", false));
	}

	/**
	 * A local closure unrelated to the peer or the connection — a downstream CSP pipeline failure, say
	 * — must not turn into a free pass for a peer that is genuinely violating the protocol: the checks
	 * run before {@code closed} is even consulted, exactly as {@link #dataPastAKnownFinalSizeIsAFinalSizeError}
	 * proves they do without it.
	 */
	@Test
	public void aFinalSizeViolationArrivingAfterAnUnrelatedLocalCloseIsStillDetected() throws QuicTransportException {
		feed(0, "abc", true);
		assertEquals(Long.valueOf(3), part.finalSize());
		part.closeEx(new IllegalStateException("a downstream pipeline failed, unrelated to this stream"));

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> feed(3, "d", false));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void aFlowControlViolationArrivingAfterAnUnrelatedLocalCloseIsStillDetected() {
		part.closeEx(new IllegalStateException("a downstream pipeline failed, unrelated to this stream"));

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> feed(WINDOW, "x", false));
		assertEquals(QuicTransportErrors.FLOW_CONTROL_ERROR, e.errorCode());
	}
}
