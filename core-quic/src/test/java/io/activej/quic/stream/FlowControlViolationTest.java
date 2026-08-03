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
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
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
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T039 — user story 2, scenario 5: a peer that spends credit it was never granted (FR-023).
 * <p>
 * Both limits are enforced, and each on its own terms: a stream may not pass the window advertised
 * for <i>it</i>, and the streams together may not pass the connection window even when every one of
 * them is individually within its own. Either overrun is {@code FLOW_CONTROL_ERROR} (RFC 9000 §4.1),
 * and — the half that a "does it throw" test would miss — <b>not one over-limit byte reaches the
 * application</b>: a read parked before the violating frame arrives must still be parked after it.
 * <p>
 * The windows here are small on purpose (1000 and 2000 bytes) so that a single {@code STREAM} frame
 * can carry a whole window and still fit inside one datagram.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class FlowControlViolationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What the receiver advertises per stream. Small enough that a whole window fits one datagram. */
	private static final int STREAM_WINDOW = 1000;

	/** What the receiver advertises for the connection: exactly two full stream windows. */
	private static final int CONNECTION_WINDOW = 2000;

	/** The sender's side: a plain handler, so nothing local stops it from overspending. */
	private static final class RecordingHandler implements QuicFrameHandler {
		final List<String> received = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			received.add(frame.getClass().getSimpleName());
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager serverManager;
	private final RecordingHandler clientHandler = new RecordingHandler();
	private final List<QuicStream> accepted = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		accepted.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandler(clientHandler);
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(accepted::add)
				.build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(CONNECTION_WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(STREAM_WINDOW))
			.build());
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	private static ByteBuf payload(long offset, int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) (offset + i));
		}
		return buf;
	}

	/** A frame the <b>test</b> owns, exactly as the connection owns the one it hands to {@code onFrame}. */
	private static StreamFrame frame(long streamId, long offset, int length) {
		return new StreamFrame(streamId, offset, false, payload(offset, length));
	}

	/** Routes and then recycles, which is precisely what {@code QuicConnection.openAndHandle} does. */
	private void route(StreamFrame frame) throws QuicTransportException {
		try {
			serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			frame.recycle();
		}
	}

	/** Puts one frame on the real wire, from a client that has no stream layer to stop it. */
	private void clientSends(long streamId, long offset, int length) throws QuicTransportException {
		wire.client().enqueueFrame(new StreamFrame(streamId, offset, false, payload(offset, length)));
		wire.client().requestSend();
		wire.pump();
	}

	private static void assertFlowControlError(QuicTransportException e) {
		assertEquals("RFC 9000 §4.1: overspending either window is FLOW_CONTROL_ERROR",
			QuicTransportErrors.FLOW_CONTROL_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- over the real wire

	@Test
	public void aStreamOverrunClosesTheConnectionWithFlowControlError() throws Exception {
		clientSends(0, 0, STREAM_WINDOW + 1);

		assertNotEquals("the receiver must not carry on as if nothing happened",
			QuicConnectionState.ESTABLISHED, wire.server().state());
		QuicConnection.PeerClose peerClose = wire.client().peerClose();
		assertNotNull("the sender must be told why", peerClose);
		assertEquals(QuicTransportErrors.FLOW_CONTROL_ERROR, peerClose.errorCode());
	}

	@Test
	public void aConnectionOverrunClosesTheConnectionWithFlowControlError() throws Exception {
		// Every one of these is within its own stream's window; together they are one byte too many.
		clientSends(0, 0, STREAM_WINDOW);
		clientSends(4, 0, STREAM_WINDOW);
		assertEquals("two full stream windows are exactly the connection window",
			QuicConnectionState.ESTABLISHED, wire.server().state());

		clientSends(8, 0, 1);

		assertNotEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		QuicConnection.PeerClose peerClose = wire.client().peerClose();
		assertNotNull(peerClose);
		assertEquals(QuicTransportErrors.FLOW_CONTROL_ERROR, peerClose.errorCode());
	}

	// ---------------------------------------------------------------- which bytes reach the application

	@Test
	public void aStreamOverrunIsRejectedBeforeAnyOfItIsBuffered() throws Exception {
		// A zero-length frame opens the stream without moving anything the reader could consume — which
		// matters, because consuming would grant credit and hand the peer the very window it is about to
		// be refused (FR-025).
		route(frame(0, 0, 0));
		QuicStream stream = accepted.get(0);
		Promise<ByteBuf> parked = stream.reader().get();
		assertFalse(parked.isComplete());

		QuicTransportException e =
			assertThrows(QuicTransportException.class, () -> route(frame(0, 0, STREAM_WINDOW + 1)));

		assertFlowControlError(e);
		assertFalse("not one over-limit byte may be delivered (FR-023)", parked.isComplete());
		assertEquals("nor accounted as received", 0, receivePartOf(stream).highestOffsetReceived());
	}

	@Test
	public void aStreamOverrunSpreadAcrossTwoFramesIsStillRejected() throws Exception {
		route(frame(0, 0, 600));
		QuicStream stream = accepted.get(0);

		// 600 + 500 is 1100 on a 1000-byte window. Nothing has been read, so no grant has widened it.
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(0, 600, 500)));

		assertFlowControlError(e);
		assertEquals("the accepted prefix stands; the frame that overran does not", 600,
			receivePartOf(stream).highestOffsetReceived());
		ByteBuf prefix = stream.reader().get().getResult();
		assertNotNull(prefix);
		assertEquals(600, prefix.readRemaining());
		prefix.recycle();
	}

	@Test
	public void aConnectionOverrunIsRejectedBeforeAnyOfItIsBuffered() throws Exception {
		route(frame(0, 0, STREAM_WINDOW));
		route(frame(4, 0, STREAM_WINDOW));
		// Stream 8 is brand new and has its whole window; the connection window is what is exhausted.
		route(frame(8, 0, 0));
		QuicStream third = accepted.get(2);
		assertEquals(8, third.id());
		Promise<ByteBuf> parked = third.reader().get();
		assertFalse(parked.isComplete());

		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(8, 0, 1)));

		assertFlowControlError(e);
		assertFalse("not one over-limit byte may be delivered (FR-023)", parked.isComplete());
		assertEquals(0, receivePartOf(third).highestOffsetReceived());
	}

	// ---------------------------------------------------------------- the boundary

	@Test
	public void dataEndingExactlyAtEitherLimitIsAccepted() throws Exception {
		route(frame(0, 0, STREAM_WINDOW));
		route(frame(4, 0, STREAM_WINDOW));

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertEquals(STREAM_WINDOW, receivePartOf(accepted.get(0)).highestOffsetReceived());
		assertEquals(STREAM_WINDOW, receivePartOf(accepted.get(1)).highestOffsetReceived());
	}

	@Test
	public void aRetransmittedDuplicateBuysNoCreditBack() throws Exception {
		route(frame(0, 0, STREAM_WINDOW));
		route(frame(4, 0, STREAM_WINDOW));
		// The whole connection window is spent. A duplicate of what was already accounted must neither
		// be charged again nor refund anything — it is simply absorbed.
		route(frame(0, 0, STREAM_WINDOW));

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(8, 0, 1)));
		assertFlowControlError(e);
	}

	private ReceivePart receivePartOf(QuicStream stream) {
		ReceivePart receivePart = stream.receivePart();
		assertNotNull(receivePart);
		return receivePart;
	}
}
