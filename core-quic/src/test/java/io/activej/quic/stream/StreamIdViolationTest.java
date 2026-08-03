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
import io.activej.quic.codec.StopSendingFrame;
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
import static org.junit.Assert.assertTrue;

/**
 * T076 — user story 5, scenario 4: a peer opening a stream above the count this endpoint advertised
 * (RFC 9000 §4.6) → {@code STREAM_LIMIT_ERROR} (0x04).
 *
 * <h2>Why one over-limit identifier is the whole test</h2>
 * A frame naming stream <i>N</i> implicitly opens every lower-numbered stream of the same type
 * (RFC 9000 §2.1, FR-003), so the identifier alone decides how many streams the peer is asking for —
 * it can reach the limit in one frame without ever having sent a byte on the streams below. The check
 * is therefore on the arriving <b>ordinal</b>, not on how many streams happen to be open, and it must
 * happen <i>before</i> the run is opened: a receiver that opened first and counted afterwards would
 * let one frame allocate as many streams as its identifier names (SI-4, CHK080).
 * <p>
 * The four stream types are independent counters, so the bidirectional and unidirectional limits are
 * asserted apart, and so is the boundary between them: the highest admissible ordinal is
 * {@code limit - 1}.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class StreamIdViolationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Client-initiated bidirectional streams the server will accept: ordinals 0 and 1, ids 0 and 4. */
	private static final long MAX_BIDI = 2;

	/** Client-initiated unidirectional streams the server will accept: ordinal 0 only, id 2. */
	private static final long MAX_UNI = 1;

	private static final long APP_ERROR_CODE = 0x0FEEDL;

	/** The stream id of client-initiated bidirectional stream number {@code ordinal}. */
	private static long clientBidi(long ordinal) {
		return StreamIds.of(ordinal, true, true);
	}

	/** The stream id of client-initiated unidirectional stream number {@code ordinal}. */
	private static long clientUni(long ordinal) {
		return StreamIds.of(ordinal, true, false);
	}

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(MAX_BIDI)
			.withInitialMaxStreamsUni(MAX_UNI)
			.build());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	// ---------------------------------------------------------------- the bound itself

	@Test
	public void theHighestAdmissibleOrdinalIsAccepted() {
		injector.accepts(stream(clientBidi(MAX_BIDI - 1), 0, false, 0));

		// One frame, and the run below it is opened with it (FR-003).
		assertEquals(MAX_BIDI, injector.manager().openStreamCount());
		assertEquals(MAX_BIDI, injector.manager().streamsAcceptedFromPeer());
		assertEquals(clientBidi(0), injector.accepted(0).id());
		assertEquals(clientBidi(1), injector.accepted(1).id());
	}

	@Test
	public void oneOrdinalPastTheBidirectionalLimitIsAStreamLimitError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			stream(clientBidi(MAX_BIDI), 0, false, "x"));

		assertEquals("the refused run must not have been opened (SI-4)", 0,
			injector.manager().openStreamCount());
		assertEquals(0, injector.manager().streamsAcceptedFromPeer());
		assertTrue("nor announced to the listener", injector.accepted().isEmpty());
	}

	@Test
	public void oneOrdinalPastTheUnidirectionalLimitIsAStreamLimitError() {
		injector.accepts(stream(clientUni(MAX_UNI - 1), 0, false, 0));
		assertEquals(MAX_UNI, injector.manager().openStreamCount());

		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			stream(clientUni(MAX_UNI), 0, false, "x"));

		assertEquals("the one admissible stream stands; nothing more was opened", MAX_UNI,
			injector.manager().openStreamCount());
	}

	@Test
	public void theTwoDirectionsAreCountedApart() {
		// Filling the unidirectional allowance must not consume any of the bidirectional one, and the
		// converse: RFC 9000 §4.6 limits the four types independently.
		injector.accepts(stream(clientUni(0), 0, false, 0));
		injector.accepts(stream(clientBidi(1), 0, false, 0));

		assertEquals(MAX_UNI + MAX_BIDI, injector.manager().openStreamCount());
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR, stream(clientUni(1), 0, false, 0));
	}

	@Test
	public void anOrdinalFarPastTheLimitIsAStreamLimitErrorRatherThanAnAllocation() {
		// The point of checking the identifier rather than the open count: this frame names a stream
		// whose implicit run would be 2^58 long. Nothing may be allocated on its behalf.
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			stream(clientBidi(1L << 58), 0, false, "x"));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void theHighestEncodableOrdinalIsAStreamLimitErrorAndNotAnArithmeticAccident() {
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			stream(clientBidi(StreamIds.MAX_ORDINAL), 0, false, "x"));

		assertEquals(0, injector.manager().openStreamCount());
	}

	// ---------------------------------------------------------------- the other two frames that can open

	@Test
	public void aResetPastTheLimitIsAStreamLimitError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			new ResetStreamFrame(clientBidi(MAX_BIDI), APP_ERROR_CODE, 0));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void stopSendingPastTheLimitIsAStreamLimitError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
			new StopSendingFrame(clientBidi(MAX_BIDI), APP_ERROR_CODE));

		assertEquals(0, injector.manager().openStreamCount());
	}

	// ---------------------------------------------------------------- once the limit has actually risen

	@Test
	public void anOrdinalRefusedBeforeIsAcceptedOnceAReleasedStreamHasRaisedTheLimit() {
		injector.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR, stream(clientUni(MAX_UNI), 0, false, "x"));

		// A released peer-initiated stream is what gives its concurrency credit back and raises the limit
		// this endpoint enforces (FR-028) — atomically with the MAX_STREAMS that announces it, so the two
		// cannot drift apart into a limit the peer was never told about.
		injector.accepts(stream(clientUni(0), 0, true, "done"));
		drainToEnd(injector.accepted(0));
		assertEquals("the stream is released once its only half is terminal (FR-006)", 0,
			injector.manager().openStreamCount());

		injector.accepts(stream(clientUni(MAX_UNI), 0, false, "x"));

		assertEquals(1, injector.manager().openStreamCount());
		drainBuffered(injector.accepted(1));
	}

	// ---------------------------------------------------------------- the peer-visible half

	@Test
	public void aStreamLimitViolationClosesTheConnectionWithStreamLimitError() throws Exception {
		injector.sendOverTheWire(stream(clientBidi(MAX_BIDI), 0, false, "x"));

		injector.assertConnectionClosedWith(QuicTransportErrors.STREAM_LIMIT_ERROR);
	}

	// ---------------------------------------------------------------- helpers

	private static void drainToEnd(QuicStream stream) {
		while (true) {
			Promise<ByteBuf> read = stream.reader().get();
			assertTrue("the reader must not park on a stream that has its FIN", read.isResult());
			ByteBuf buf = read.getResult();
			if (buf == null) return;
			buf.recycle();
		}
	}

	/** Takes whatever is ready, so the harness's leak rule has nothing left to complain about. */
	private static void drainBuffered(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		if (!read.isResult()) return;
		ByteBuf buf = read.getResult();
		if (buf != null) buf.recycle();
	}
}
