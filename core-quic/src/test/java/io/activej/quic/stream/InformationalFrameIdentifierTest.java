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
import io.activej.quic.codec.MaxStreamDataFrame;
import io.activej.quic.codec.QuicStreamLimitType;
import io.activej.quic.codec.StreamDataBlockedFrame;
import io.activej.quic.codec.StreamsBlockedFrame;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T101 — the row of the spec's Error Scenarios table that reads "Peer sends a frame only the other
 * role may send, or writes to a receive-only stream → {@code STREAM_STATE_ERROR}", for the three frame
 * types Phase 9 did not reach: {@code MAX_STREAM_DATA} (RFC 9000 §19.10),
 * {@code STREAM_DATA_BLOCKED} (§19.13) and {@code STREAMS_BLOCKED} (§19.14).
 *
 * <h2>Why these three were the gap</h2>
 * {@link StreamStateViolationTest} covers the three frames that carry <i>data or an abort</i> —
 * {@code STREAM}, {@code RESET_STREAM}, {@code STOP_SENDING} — because those are the ones whose
 * handling obviously has to consult the stream. The other three are <b>informational</b>: they carry a
 * limit, and this implementation's response to all three is at most "grant some credit". It is exactly
 * that harmlessness that made silently ignoring an impossible identifier look acceptable, and it is
 * not: RFC 9000 §19.10 and §19.13 both make a frame naming a half this endpoint does not own a
 * {@code STREAM_STATE_ERROR}, and tolerating it hands a peer a probe for which of an endpoint's
 * streams exist.
 *
 * <h2>Which identifier is illegal for whom (RFC 9000 §2.1)</h2>
 * The two frames name opposite halves, so their illegal identifiers are opposites too. Seen from a
 * server:
 * <table>
 *   <caption>Illegal identifiers per frame type, from a server's point of view</caption>
 *   <tr><th>Frame</th><th>Names</th><th>Illegal</th></tr>
 *   <tr><td>{@code MAX_STREAM_DATA}</td><td>this endpoint's <b>sending</b> half</td>
 *       <td>a client-initiated <i>unidirectional</i> stream (id 2): receive-only here</td></tr>
 *   <tr><td>{@code STREAM_DATA_BLOCKED}</td><td>the peer's <b>sending</b> half</td>
 *       <td>a server-initiated <i>unidirectional</i> stream (id 3): send-only here</td></tr>
 * </table>
 * Both are additionally illegal on a <i>locally-initiated</i> identifier this endpoint never opened,
 * which is the second half of §19.10's rule and the general §2.1 one. {@code STREAMS_BLOCKED} names no
 * stream at all, so what it can break instead is the 2^60 count bound it shares with
 * {@code MAX_STREAMS}.
 *
 * <h2>What must stay tolerated</h2>
 * All three arrive in ordinary, conforming operation, so the fix must not turn normal traffic into a
 * connection error. Three cases are asserted to still be accepted: a legal peer-initiated identifier,
 * an identifier naming a stream that was opened and has since been <i>released</i> (a frame still in
 * flight for a finished stream is routine, not a violation), and a first mention of a peer-initiated
 * stream, which opens it and every lower-numbered one of its type (FR-003).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.10">RFC 9000 §19.10 — MAX_STREAM_DATA Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.13">RFC 9000 §19.13 — STREAM_DATA_BLOCKED Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.14">RFC 9000 §19.14 — STREAMS_BLOCKED Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class InformationalFrameIdentifierTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Client-initiated bidirectional #0 — both endpoints own both halves of it. */
	private static final long CLIENT_BIDI = 0;

	/** Server-initiated bidirectional #0 — a server that never opened it has never heard of it. */
	private static final long SERVER_BIDI = 1;

	/** Client-initiated unidirectional #0 — the server may only receive on it. */
	private static final long CLIENT_UNI = 2;

	/** Server-initiated unidirectional #0 — the server may only send on it. */
	private static final long SERVER_UNI = 3;

	private static final long SOME_LIMIT = 1 << 20;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	// ---------------------------------------------------------------- MAX_STREAM_DATA (RFC 9000 §19.10)

	@Test
	public void maxStreamDataOnAReceiveOnlyStreamIsAStreamStateError() {
		// The server has no sending half of a client-initiated unidirectional stream, so there is nothing
		// for a send limit to apply to.
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new MaxStreamDataFrame(CLIENT_UNI, SOME_LIMIT));

		assertEquals("nothing may be opened on behalf of a frame that is refused", 0,
			injector.manager().openStreamCount());
		assertTrue("nor announced to the listener", injector.accepted().isEmpty());
	}

	@Test
	public void maxStreamDataOnALocallyInitiatedStreamNeverOpenedIsAStreamStateError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new MaxStreamDataFrame(SERVER_BIDI, SOME_LIMIT));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void maxStreamDataOnALocallyInitiatedUnidirectionalStreamNeverOpenedIsAStreamStateError() {
		// Legal in principle — the server does own that sending half — but this one does not exist.
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new MaxStreamDataFrame(SERVER_UNI, SOME_LIMIT));
	}

	@Test
	public void maxStreamDataPastTheAdvertisedStreamCountIsAStreamLimitError() throws MalformedDataException {
		// The identifier rules are one rule, so a MAX_STREAM_DATA that would open a run past the count this
		// endpoint advertised is refused exactly as a STREAM frame would be (FR-003, FR-028).
		try (StreamFrameInjector limited = StreamFrameInjector.intoServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(2)
			.build())) {
			limited.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
				new MaxStreamDataFrame(StreamIds.of(2, true, true), SOME_LIMIT));

			assertEquals(0, limited.manager().openStreamCount());
		}
	}

	@Test
	public void maxStreamDataOnAPeerInitiatedBidirectionalStreamOpensItAndRaisesItsLimit() {
		// RFC 9000 §2.1: a frame naming a peer-initiated stream for the first time creates it, and every
		// lower-numbered stream of its type with it (FR-003). This is normal traffic, not a violation.
		injector.accepts(new MaxStreamDataFrame(StreamIds.of(1, true, true), SOME_LIMIT));

		assertEquals(2, injector.manager().openStreamCount());
		QuicStream opened = injector.manager().streamOf(StreamIds.of(1, true, true));
		assertNotNull(opened);
		SendPart sendPart = opened.sendPart();
		assertNotNull("a peer-initiated bidirectional stream has a sending half here", sendPart);
		assertEquals("the limit the frame carried is in force", SOME_LIMIT, sendPart.flowControl().limit());
	}

	@Test
	public void maxStreamDataForAStreamThatWasOpenedAndReleasedIsTolerated() {
		QuicStream opened = openLocalUnidirectional();
		long streamId = opened.id();
		opened.reset(0);
		injector.wire().pump();

		// Not an error and not a resurrection: a frame still in flight for a stream this endpoint has
		// finished with is routine. The distinction from "never opened" is `nextOrdinal`, not the map.
		injector.accepts(new MaxStreamDataFrame(streamId, SOME_LIMIT));
	}

	// ---------------------------------------------------------------- STREAM_DATA_BLOCKED (RFC 9000 §19.13)

	@Test
	public void streamDataBlockedOnASendOnlyStreamIsAStreamStateError() {
		// The peer claims to be blocked sending on a server-initiated unidirectional stream — a half only
		// this endpoint has.
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new StreamDataBlockedFrame(SERVER_UNI, SOME_LIMIT));

		assertEquals(0, injector.manager().openStreamCount());
		assertTrue(injector.accepted().isEmpty());
	}

	@Test
	public void streamDataBlockedOnALocallyInitiatedStreamNeverOpenedIsAStreamStateError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new StreamDataBlockedFrame(SERVER_BIDI, SOME_LIMIT));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void streamDataBlockedPastTheAdvertisedStreamCountIsAStreamLimitError() throws MalformedDataException {
		try (StreamFrameInjector limited = StreamFrameInjector.intoServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamsUni(1)
			.build())) {
			limited.rejectsWith(QuicTransportErrors.STREAM_LIMIT_ERROR,
				new StreamDataBlockedFrame(StreamIds.of(1, true, false), SOME_LIMIT));

			assertEquals(0, limited.manager().openStreamCount());
		}
	}

	@Test
	public void streamDataBlockedOnALegalPeerInitiatedStreamIsAccepted() {
		injector.accepts(stream(CLIENT_UNI, 0, false, "x"));
		QuicStream receiveOnly = injector.accepted(0);

		injector.accepts(new StreamDataBlockedFrame(CLIENT_UNI, SOME_LIMIT));

		assertEquals("an informational frame opens nothing new", 1, injector.manager().openStreamCount());
		drainBuffered(receiveOnly);
	}

	@Test
	public void streamDataBlockedOnABidirectionalStreamIsAcceptedFromEitherEnd() {
		injector.accepts(stream(CLIENT_BIDI, 0, false, "x"));

		injector.accepts(new StreamDataBlockedFrame(CLIENT_BIDI, SOME_LIMIT));

		drainBuffered(injector.accepted(0));
	}

	// ---------------------------------------------------------------- STREAMS_BLOCKED (RFC 9000 §19.14)

	@Test
	public void streamsBlockedAboveTheStreamCountCeilingIsAFrameEncodingError() {
		// RFC 9000 §19.14: a count above 2^60 describes a stream identifier that cannot be encoded. The
		// same bound MAX_STREAMS carries, and for the same reason.
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new StreamsBlockedFrame(StreamCounter.MAX_STREAM_COUNT + 1, QuicStreamLimitType.BIDIRECTIONAL));
	}

	@Test
	public void streamsBlockedAboveTheCeilingIsAFrameEncodingErrorForBothDirections() {
		injector.rejectsWith(QuicTransportErrors.FRAME_ENCODING_ERROR,
			new StreamsBlockedFrame(Long.MAX_VALUE >> 2, QuicStreamLimitType.UNIDIRECTIONAL));
	}

	@Test
	public void streamsBlockedAtTheCeilingItselfIsAccepted() {
		// The bound is inclusive: 2^60 is exactly the number of streams of one type that can exist.
		injector.accepts(new StreamsBlockedFrame(StreamCounter.MAX_STREAM_COUNT, QuicStreamLimitType.BIDIRECTIONAL));
	}

	@Test
	public void anOrdinaryStreamsBlockedIsAccepted() {
		injector.accepts(new StreamsBlockedFrame(100, QuicStreamLimitType.BIDIRECTIONAL));
		injector.accepts(new StreamsBlockedFrame(3, QuicStreamLimitType.UNIDIRECTIONAL));

		assertEquals(0, injector.manager().openStreamCount());
	}

	// ---------------------------------------------------------------- the mirror image, from a client

	@Test
	public void aClientRejectsTheMirrorImagesOfBothIdentifierRules() throws MalformedDataException {
		try (StreamFrameInjector client = StreamFrameInjector.intoClient(QuicConnectionSettings.create())) {
			// To a client, a *client*-initiated unidirectional stream is the send-only one, and a
			// *server*-initiated one is receive-only — the exact opposite of the server's view above.
			client.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
				new MaxStreamDataFrame(SERVER_UNI, SOME_LIMIT));
			client.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
				new StreamDataBlockedFrame(CLIENT_UNI, SOME_LIMIT));
			client.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
				new MaxStreamDataFrame(CLIENT_BIDI, SOME_LIMIT));
		}
	}

	// ---------------------------------------------------------------- the peer-visible half

	@Test
	public void anIdentifierViolationClosesTheConnectionWithStreamStateError() throws Exception {
		injector.sendOverTheWire(new MaxStreamDataFrame(CLIENT_UNI, SOME_LIMIT));

		injector.assertConnectionClosedWith(QuicTransportErrors.STREAM_STATE_ERROR);
	}

	// ---------------------------------------------------------------- helpers

	private QuicStream openLocalUnidirectional() {
		Promise<QuicStream> opened = injector.manager().openUnidirectional();
		assertTrue("the peer grants unidirectional streams by default", opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Takes whatever is ready, so the harness's leak rule has nothing left to complain about. */
	private static void drainBuffered(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		if (!read.isResult()) return;
		ByteBuf buf = read.getResult();
		if (buf != null) buf.recycle();
	}
}
