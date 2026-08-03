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
import io.activej.quic.codec.MaxStreamsFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.QuicStreamLimitType;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.codec.StreamsBlockedFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T047 — RFC 9000 §4.6, both directions of the stream-<i>count</i> limit: what a peer may open on us,
 * and what we may open when the peer has granted nothing.
 *
 * <h2>Inbound: the peer over the limit</h2>
 * An arriving frame naming an ordinal at or above the count we advertised is
 * {@code STREAM_LIMIT_ERROR} — and because a frame implicitly opens every lower-numbered stream of its
 * type (RFC 9000 §2.1), checking the named ordinal alone is the whole check: a peer cannot reach a
 * forbidden ordinal by skipping to it.
 *
 * <h2>Outbound: no credit of our own</h2>
 * Exceeding the peer's count is forbidden, so an open with no credit is <b>withheld</b> — its promise
 * stays pending — and the peer is told with {@code STREAMS_BLOCKED} (RFC 9000 §19.14),
 * <b>once per distinct limit value</b> rather than once per waiting open (FR-028). The withheld opens
 * are bounded by {@code maxPendingStreamOpens}: past that, a further open fails at once with
 * {@link QuicStreamLimitException} rather than growing this endpoint's memory on a stalled peer
 * (FR-029, SI-3).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.11">RFC 9000 §19.11 — MAX_STREAMS Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.14">RFC 9000 §19.14 — STREAMS_BLOCKED Frames</a>
 */
public final class StreamLimitTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Records the stream-count frames the endpoint under test puts on the wire. */
	private static final class RecordingHandler implements QuicFrameHandler {
		final List<Long> streamsBlockedBidi = new ArrayList<>();
		final List<Long> streamsBlockedUni = new ArrayList<>();
		final List<Long> maxStreamsBidi = new ArrayList<>();
		final List<Long> maxStreamsUni = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			if (frame instanceof StreamsBlockedFrame blocked) {
				(blocked.type == QuicStreamLimitType.BIDIRECTIONAL ? streamsBlockedBidi : streamsBlockedUni)
					.add(blocked.limit);
			}
			if (frame instanceof MaxStreamsFrame max) {
				(max.type == QuicStreamLimitType.BIDIRECTIONAL ? maxStreamsBidi : maxStreamsUni)
					.add(max.maximum);
			}
		}
	}

	private ManualEventloop loop;
	private @Nullable QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final RecordingHandler peerHandler = new RecordingHandler();
	private final List<QuicStream> accepted = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		accepted.clear();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- fixtures

	/** A server with a stream layer, and a bare client the test drives frames through by hand. */
	private void withStreamManagerOnTheServer(QuicConnectionSettings serverSettings)
		throws MalformedDataException {
		wire = new QuicWirePair();
		wire.withClientFrameHandler(peerHandler);
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(accepted::add)
				.build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(serverSettings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	/**
	 * A client with a stream layer, and a bare server that records what the client says. The
	 * <b>server's</b> settings are what bound the client's opens: {@code initial_max_streams_*} is a
	 * grant to one's peer (RFC 9000 §18.2).
	 */
	private void withStreamManagerOnTheClient(
		QuicConnectionSettings clientSettings, QuicConnectionSettings serverSettings
	) throws MalformedDataException {
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(peerHandler);
		wire.startClient(clientSettings);
		wire.acceptServer(serverSettings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	private static QuicConnectionSettings grantingStreams(long bidi, long uni) {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(bidi)
			.withInitialMaxStreamsUni(uni)
			.build();
	}

	private static StreamFrame frame(long streamId, String data) {
		byte[] array = data.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return new StreamFrame(streamId, 0, false, buf);
	}

	/** Routes and then recycles, which is precisely what {@code QuicConnection.openAndHandle} does. */
	private void route(StreamFrame frame) throws QuicTransportException {
		try {
			serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			frame.recycle();
		}
	}

	/** Puts one frame on the real wire from the peer, which has no stream layer to stop it. */
	private void peerSends(QuicConnection peer, QuicFrame frame) throws QuicTransportException {
		peer.enqueueFrame(frame);
		peer.requestSend();
		wire.pump();
	}

	// ---------------------------------------------------------------- inbound (T050)

	@Test
	public void aPeerOpeningPastTheAdvertisedCountClosesTheConnectionWithStreamLimitError()
		throws Exception {
		withStreamManagerOnTheServer(grantingStreams(2, 1));

		// Ordinals 0 and 1 are the two the server granted; stream 8 is ordinal 2, one too far.
		peerSends(wire.client(), frame(8, "over the limit"));

		assertNotEquals("the receiver must not carry on as if nothing happened",
			QuicConnectionState.ESTABLISHED, wire.server().state());
		QuicConnection.PeerClose peerClose = wire.client().peerClose();
		assertNotNull("the sender must be told why", peerClose);
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, peerClose.errorCode());
	}

	@Test
	public void theHighestPermittedOrdinalIsAcceptedAndTheNextOneIsNot() throws Exception {
		withStreamManagerOnTheServer(grantingStreams(2, 1));

		// Stream 4 is ordinal 1 — the last one granted — and implicitly opens stream 0 as well.
		route(frame(4, "second"));
		assertEquals(List.of(0L, 4L), accepted.stream().map(QuicStream::id).toList());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(8, "third")));
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, e.errorCode());
		assertEquals("the refused stream must not have been created", 2, serverManager.openStreamCount());
	}

	@Test
	public void implicitOpeningIsCountedAgainstTheLimitToo() throws Exception {
		withStreamManagerOnTheServer(grantingStreams(2, 1));

		// One frame, naming ordinal 3: the streams it would implicitly open are what exceed the count,
		// which is exactly the case a check on "how many are open" would miss.
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(12, "fourth")));
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, e.errorCode());
		assertEquals(0, serverManager.openStreamCount());
		assertEquals(0, accepted.size());
	}

	@Test
	public void theTwoDirectionsAreLimitedIndependently() throws Exception {
		withStreamManagerOnTheServer(grantingStreams(2, 1));

		// Stream 2 is the client's unidirectional ordinal 0 — the only one granted — and its type's
		// exhaustion says nothing about the bidirectional streams that are still available.
		route(frame(2, "push"));
		route(frame(0, "request"));
		assertEquals(2, serverManager.openStreamCount());

		// Stream 6 is unidirectional ordinal 1, one past the unidirectional grant.
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(6, "second push")));
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- outbound: withheld (T051)

	@Test
	public void anOpenWithNoCreditIsWithheldRatherThanFailed() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> opened = clientManager.openBidirectional();

		assertFalse("FR-029: withheld, not failed — the peer may still raise the limit",
			opened.isComplete());
		assertEquals("nothing may be allocated without credit", 0, clientManager.openStreamCount());
		assertEquals(0, clientManager.streamsOpenedLocally());
	}

	@Test
	public void streamsBlockedIsAnnouncedOncePerLimitValue() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> first = clientManager.openBidirectional();
		wire.pump();
		assertEquals("RFC 9000 §19.14: the limit the sender is blocked at, which is zero",
			List.of(0L), peerHandler.streamsBlockedBidi);
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());

		Promise<QuicStream> second = clientManager.openBidirectional();
		Promise<QuicStream> third = clientManager.openBidirectional();
		wire.pump();

		assertEquals("FR-028: once per limit value, not once per withheld open",
			List.of(0L), peerHandler.streamsBlockedBidi);
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());
		assertFalse(first.isComplete());
		assertFalse(second.isComplete());
		assertFalse(third.isComplete());
	}

	@Test
	public void theTwoDirectionsAreAnnouncedSeparately() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		clientManager.openBidirectional();
		clientManager.openUnidirectional();
		clientManager.openUnidirectional();
		wire.pump();

		assertEquals(List.of(0L), peerHandler.streamsBlockedBidi);
		assertEquals("RFC 9000 §19.14 counts the two directions apart", List.of(0L),
			peerHandler.streamsBlockedUni);
		assertEquals("one blocking episode per direction", 2,
			clientManager.timesBlockedByStreamCountLimit());
	}

	@Test
	public void anOpenThatHasCreditIsNotAnnouncedAtAll() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(1, 0));

		Promise<QuicStream> opened = clientManager.openBidirectional();
		wire.pump();

		assertTrue(opened.isComplete());
		assertEquals(0, opened.getResult().id());
		assertEquals(List.of(), peerHandler.streamsBlockedBidi);
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());
	}

	// ---------------------------------------------------------------- outbound: MAX_STREAMS (T052)

	@Test
	public void aWithheldOpenCompletesWhenMaxStreamsRaisesTheLimit() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> opened = clientManager.openBidirectional();
		wire.pump();
		assertFalse(opened.isComplete());

		peerSends(wire.server(), new MaxStreamsFrame(1, QuicStreamLimitType.BIDIRECTIONAL));

		assertTrue("the withheld open resumes on MAX_STREAMS", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		assertEquals("RFC 9000 §2.1: the client's first bidirectional stream is id 0", 0, stream.id());
		assertEquals(1, clientManager.openStreamCount());
		assertEquals(1, clientManager.streamsOpenedLocally());
	}

	@Test
	public void withheldOpensAreServedInOrderAndTheRestStayWithheld() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> first = clientManager.openBidirectional();
		Promise<QuicStream> second = clientManager.openBidirectional();
		Promise<QuicStream> third = clientManager.openBidirectional();
		wire.pump();

		peerSends(wire.server(), new MaxStreamsFrame(2, QuicStreamLimitType.BIDIRECTIONAL));

		assertTrue(first.isComplete());
		assertTrue(second.isComplete());
		assertFalse("two streams' worth of credit serves exactly two opens", third.isComplete());
		assertEquals(0, first.getResult().id());
		assertEquals("ordinals are handed out in order, without gaps", 4, second.getResult().id());
		// Still blocked, now at a different value — so the announcement is due again, and again once.
		assertEquals(List.of(0L, 2L), peerHandler.streamsBlockedBidi);
		assertEquals(2, clientManager.timesBlockedByStreamCountLimit());
	}

	@Test
	public void maxStreamsForOneDirectionDoesNotResumeTheOther() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> bidi = clientManager.openBidirectional();
		Promise<QuicStream> uni = clientManager.openUnidirectional();
		wire.pump();

		peerSends(wire.server(), new MaxStreamsFrame(1, QuicStreamLimitType.UNIDIRECTIONAL));

		assertTrue(uni.isComplete());
		assertEquals("RFC 9000 §2.1: the client's first unidirectional stream is id 2", 2, uni.getResult().id());
		assertFalse("the bidirectional grant is a different limit entirely", bidi.isComplete());
	}

	@Test
	public void aMaxStreamsAtOrBelowTheCurrentLimitChangesNothing() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(1, 0));

		Promise<QuicStream> first = clientManager.openBidirectional();
		assertTrue(first.isComplete());
		Promise<QuicStream> second = clientManager.openBidirectional();
		wire.pump();
		assertFalse(second.isComplete());
		assertEquals(List.of(1L), peerHandler.streamsBlockedBidi);

		// FR-026: frames can be reordered, so a stale limit is a normal event, not an error.
		peerSends(wire.server(), new MaxStreamsFrame(1, QuicStreamLimitType.BIDIRECTIONAL));

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertFalse(second.isComplete());
		assertEquals("nothing moved, so nothing is re-announced", List.of(1L), peerHandler.streamsBlockedBidi);
	}

	@Test
	public void aMaxStreamsAboveTheEncodableMaximumIsAFrameEncodingError() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		// RFC 9000 §19.11: a count above 2^60 would permit a stream identifier that cannot exist, since an
		// ordinal occupies the 60 bits above a stream id's two type bits. The codec leaves this
		// protocol-semantic bound to this layer, so this is where it must be caught — and caught as
		// FRAME_ENCODING_ERROR rather than as an internal failure when the identifier is later built.
		peerSends(wire.server(),
			new MaxStreamsFrame(StreamCounter.MAX_STREAM_COUNT + 1, QuicStreamLimitType.BIDIRECTIONAL));

		assertNotEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		QuicConnection.PeerClose peerClose = wire.server().peerClose();
		assertNotNull(peerClose);
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, peerClose.errorCode());
	}

	@Test
	public void aMaxStreamsAtTheEncodableMaximumIsAccepted() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> opened = clientManager.openBidirectional();
		peerSends(wire.server(),
			new MaxStreamsFrame(StreamCounter.MAX_STREAM_COUNT, QuicStreamLimitType.BIDIRECTIONAL));

		assertEquals("2^60 itself is the maximum, not one past it",
			QuicConnectionState.ESTABLISHED, wire.client().state());
		assertTrue(opened.isComplete());
		assertEquals(0, opened.getResult().id());
	}

	// ---------------------------------------------------------------- outbound: the bound (FR-029)

	@Test
	public void pastMaxPendingStreamOpensAFurtherOpenFailsImmediately() throws Exception {
		withStreamManagerOnTheClient(
			QuicConnectionSettings.builder().withMaxPendingStreamOpens(2).build(),
			grantingStreams(0, 0));

		Promise<QuicStream> first = clientManager.openBidirectional();
		Promise<QuicStream> second = clientManager.openBidirectional();
		Promise<QuicStream> third = clientManager.openBidirectional();
		wire.pump();

		assertFalse(first.isComplete());
		assertFalse(second.isComplete());
		assertTrue("SI-3: the withheld opens are bounded, and past the bound the caller is told at once",
			third.isComplete());
		QuicStreamLimitException e = (QuicStreamLimitException) third.getException();
		assertNotNull(e);
		assertEquals(StreamDirection.BIDIRECTIONAL, e.direction());
		assertEquals(2, e.maxPendingStreamOpens());
		assertTrue("the message must name the setting to grep for: " + e.getMessage(),
			e.getMessage().contains("maxPendingStreamOpens"));
		// The bound is on what is withheld, not on how loudly it is announced.
		assertEquals(List.of(0L), peerHandler.streamsBlockedBidi);
	}

	@Test
	public void theBoundIsSharedByBothDirections() throws Exception {
		withStreamManagerOnTheClient(
			QuicConnectionSettings.builder().withMaxPendingStreamOpens(2).build(),
			grantingStreams(0, 0));

		clientManager.openBidirectional();
		clientManager.openUnidirectional();
		Promise<QuicStream> third = clientManager.openUnidirectional();
		wire.pump();

		assertTrue("maxPendingStreamOpens bounds the withheld opens of the connection, not of a direction",
			third.isComplete());
		assertTrue(third.getException() instanceof QuicStreamLimitException);
		assertEquals(StreamDirection.UNIDIRECTIONAL,
			((QuicStreamLimitException) third.getException()).direction());
	}

	// ---------------------------------------------------------------- the two withholding reasons meet

	@Test
	public void anOpenWithheldBeforeEstablishmentStaysWithheldIfTheHandshakeGrantsNothing()
		throws Exception {
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(peerHandler);
		wire.startClient(QuicConnectionSettings.create());

		// FR-042: withheld because no limit is known yet — and there is no value a STREAMS_BLOCKED could
		// truthfully carry, so nothing is announced and nothing is counted.
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertFalse(opened.isComplete());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());

		wire.acceptServer(grantingStreams(0, 0));
		wire.pump();

		// The handshake supplied a limit of zero, so the reason it is withheld changes and the peer is
		// told — one deque, two reasons, and the caller sees neither.
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertFalse("the open must not be failed by a limit the peer may still raise", opened.isComplete());
		assertEquals(List.of(0L), peerHandler.streamsBlockedBidi);
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());

		peerSends(wire.server(), new MaxStreamsFrame(1, QuicStreamLimitType.BIDIRECTIONAL));
		assertTrue(opened.isComplete());
		assertEquals(0, opened.getResult().id());
	}

	@Test
	public void aWithheldOpenFailsWithTheConnectionsExceptionWhenTheConnectionEnds() throws Exception {
		withStreamManagerOnTheClient(QuicConnectionSettings.create(), grantingStreams(0, 0));

		Promise<QuicStream> opened = clientManager.openBidirectional();
		wire.pump();
		assertFalse(opened.isComplete());

		wire.client().closeNow();

		assertTrue("FR-041: a withheld open must never be stranded", opened.isComplete());
		assertTrue(String.valueOf(opened.getException()),
			opened.getException() instanceof QuicTransportException);
	}
}
