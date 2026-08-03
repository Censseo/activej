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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.stream.testutil.StreamFrameInjector;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.quic.stream.testutil.StreamFrameInjector.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * T074 — user story 5, scenarios 1 and 2 (FR-012): a peer that contradicts a stream's final size.
 * <p>
 * RFC 9000 §4.5 makes a final size <b>immutable once known</b>, and it can become known in three
 * ways — a {@code STREAM} frame carrying {@code FIN}, a {@code RESET_STREAM} declaring it, or (as a
 * lower bound) the bytes already received. Every way of contradicting it afterwards is
 * {@code FINAL_SIZE_ERROR} (0x06), and the RFC is explicit that this is a <b>connection</b> error and
 * not a stream one: a receiver that has committed to a length cannot renegotiate it.
 * <p>
 * The four ways to contradict it, all asserted here:
 * <ul>
 *   <li>data whose end lies <b>above</b> a known final size;</li>
 *   <li>a second {@code FIN} declaring a <b>different</b> final size, above or below the first;</li>
 *   <li>a {@code FIN} declaring a size <b>below</b> bytes already accounted for;</li>
 *   <li>a {@code RESET_STREAM} declaring a size that disagrees with a {@code FIN}, and the reverse
 *       order (kept short here — {@link AbortRacesTest} owns the race in full, SC-004).</li>
 * </ul>
 * Frames are hand-built and injected, because a conforming peer cannot be made to send any of them:
 * see {@link StreamFrameInjector}.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.5">RFC 9000 §4.5 — Stream Final Size</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class FinalSizeViolationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Client-initiated bidirectional stream #0, so the server owns a receiving half of it. */
	private static final long STREAM_ID = 0;

	private static final long APP_ERROR_CODE = 0x0FACADEL;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	private ReceivePart receivePart() {
		ReceivePart receivePart = injector.accepted(0).receivePart();
		assertNotNull(receivePart);
		return receivePart;
	}

	// ---------------------------------------------------------------- scenario 1: data past the final size

	@Test
	public void dataStartingPastAKnownFinalSizeIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));
		assertEquals((Long) 5L, receivePart().finalSize());

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 5, false, "!"));

		assertEquals("the rejected frame must leave nothing behind", 5,
			receivePart().highestOffsetReceived());
	}

	@Test
	public void dataStraddlingAKnownFinalSizeIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		// Starts inside what was already received, ends one byte past the final size. The overlapping
		// prefix does not excuse the suffix (RFC 9000 §4.5).
		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 3, false, "lo!"));
	}

	@Test
	public void dataEndingExactlyAtTheFinalSizeIsAccepted() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		// A retransmission of a prefix, and of the whole thing: both end at the final size, so neither
		// contradicts it. The boundary is the case a "greater or equal" check would get wrong.
		injector.accepts(stream(STREAM_ID, 3, false, "lo"));
		injector.accepts(stream(STREAM_ID, 0, false, "hello"));

		assertEquals(5, receivePart().highestOffsetReceived());
	}

	@Test
	public void dataPastAFinalSizeDeclaredByAResetIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, false, "hel"));
		injector.accepts(new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, 5));
		assertEquals((Long) 5L, receivePart().finalSize());

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 5, false, "!"));
	}

	// ---------------------------------------------------------------- scenario 2: a second, different FIN

	@Test
	public void aSecondFinDeclaringALargerFinalSizeIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 0, true, "hello!"));

		assertEquals("the first final size stands", (Long) 5L, receivePart().finalSize());
	}

	@Test
	public void aSecondFinDeclaringASmallerFinalSizeIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		// Below the first, and so also below the bytes already received: still a contradiction, and the
		// one a receiver that only checked "past the final size" would silently accept.
		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 0, true, "hell"));

		assertEquals((Long) 5L, receivePart().finalSize());
	}

	@Test
	public void aRepeatedFinDeclaringTheSameFinalSizeIsAccepted() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		// A retransmitted FIN is the ordinary case, not a violation: the same three fields, twice.
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));
		// And a zero-length FIN at the final size says the same thing in fewer bytes.
		injector.accepts(stream(STREAM_ID, 5, true, 0));

		assertEquals((Long) 5L, receivePart().finalSize());
	}

	@Test
	public void aFinBelowBytesAlreadyReceivedIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, false, "hello world"));
		assertNull("no FIN yet, so no final size", receivePart().finalSize());

		// The peer now claims the stream ends at 5, having already sent 11 bytes. RFC 9000 §4.5: the
		// received data is itself a lower bound on the final size.
		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 0, true, "hello"));

		assertNull(receivePart().finalSize());
		assertEquals(11, receivePart().highestOffsetReceived());
	}

	// ---------------------------------------------------------------- the two frames disagreeing

	@Test
	public void aResetDisagreeingWithAFinIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, true, "hello"));

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR,
			new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, 6));
	}

	@Test
	public void aFinDisagreeingWithAResetIsAFinalSizeError() {
		injector.accepts(stream(STREAM_ID, 0, false, "hel"));
		injector.accepts(new ResetStreamFrame(STREAM_ID, APP_ERROR_CODE, 5));

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 0, true, "hello!"));
	}

	// ---------------------------------------------------------------- nothing over-long reaches the reader

	@Test
	public void notOneBytePastTheFinalSizeIsDelivered() {
		injector.accepts(stream(STREAM_ID, 0, true, 0));   // an empty stream: final size 0

		QuicStream stream = injector.accepted(0);
		Promise<ByteBuf> read = stream.reader().get();
		assertNull("end-of-stream, not a byte", read.getResult());

		injector.rejectsWith(QuicTransportErrors.FINAL_SIZE_ERROR, stream(STREAM_ID, 0, false, "x"));

		Promise<ByteBuf> after = stream.reader().get();
		assertFalse("a rejected frame must not resurrect the stream", after.isException());
		assertNull(after.getResult());
	}

	// ---------------------------------------------------------------- the peer-visible half

	@Test
	public void aFinalSizeViolationClosesTheConnectionWithFinalSizeError() throws Exception {
		injector.sendOverTheWire(stream(STREAM_ID, 0, true, "hello"));
		injector.sendOverTheWire(stream(STREAM_ID, 5, false, "!"));

		injector.assertConnectionClosedWith(QuicTransportErrors.FINAL_SIZE_ERROR);
	}
}
