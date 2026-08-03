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
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T056 — SC-004: an abort racing an end-of-data marker, in both orders, plus the pending operation
 * either of them has to resolve (FR-036).
 *
 * <h2>Whoever arrives first wins, and the loser is not a correction</h2>
 * The two frames that can fix a stream's final size — a {@code STREAM} carrying {@code FIN} and a
 * {@code RESET_STREAM} — can arrive in either order, because a datagram carrying one can be reordered
 * past the other. The rule RFC 9000 §3.2 and §4.5 give between them is not "the last one wins" and not
 * "the first one wins and the second is ignored" either. It is:
 * <ul>
 *   <li>the <b>first</b> to arrive decides what the reader observes — a clean end-of-stream or a
 *       {@link QuicStreamResetException} — and the second changes nothing about that;</li>
 *   <li>but the second is still <b>checked</b> against the final size the first fixed, because
 *       disagreeing about a final size is a genuine {@code FINAL_SIZE_ERROR} whichever frame does it,
 *       and ignoring the second frame entirely would silently accept the contradiction (RFC 9000
 *       §4.5).</li>
 * </ul>
 * That second clause is the one an implementation gets wrong by being lenient, so it is asserted from
 * both directions here.
 *
 * <h2>How the race is staged</h2>
 * Frames are routed into the receiving endpoint's manager directly, exactly as its connection would —
 * the frame is <b>borrowed</b> and recycled by the caller the moment {@code onFrame} returns. Driving
 * a genuine reorder over the wire would be testing the loss detector, and could not stage the
 * contradicting case at all, since a conforming peer never sends one.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.5">RFC 9000 §4.5 — Stream Final Size</a>
 */
public final class AbortRacesTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long APP_ERROR_CODE = 0x0FACADE_9L;

	/** The client's window for the server's half of a client-opened stream — small, so a write withholds. */
	private static final int STREAM_WINDOW = 32;

	private static final int MAX_DRIVE_ROUNDS = 300;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				// No reader attached: every case here needs bytes buffered but undelivered.
				.withStreamListener(serverStreams::add)
				.build());
		wire.startClient(QuicConnectionSettings.builder()
			.withInitialMaxStreamDataBidiLocal(MemSize.bytes(STREAM_WINDOW))
			.build());
		wire.acceptServer(QuicConnectionSettings.create());
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			wire.pump();
			loop.tick();
			if (done.getAsBoolean()) return;
			loop.advance(5);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static ByteBuf bytes(int length, char fill) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) fill);
		}
		return buf;
	}

	/** A frame the <b>test</b> owns, exactly as the connection owns the one it hands to {@code onFrame}. */
	private static StreamFrame streamFrame(long streamId, long offset, boolean fin, String data) {
		byte[] array = data.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return new StreamFrame(streamId, offset, fin, buf);
	}

	/** Routes and then recycles, which is precisely what {@code QuicConnection.openAndHandle} does. */
	private void routeToServer(StreamFrame frame) throws QuicTransportException {
		try {
			serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			frame.recycle();
		}
	}

	private void routeToServer(ResetStreamFrame frame) throws QuicTransportException {
		serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
	}

	/** Opens client stream 0 and makes the server aware of it, with three bytes nobody has read. */
	private QuicStream openAndAccept() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isComplete());
		QuicStream clientStream = opened.getResult();
		assertNotNull(clientStream);
		Promise<Void> written = clientStream.writer().accept(bytes(3, 'a'));
		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty());
		return clientStream;
	}

	// ---------------------------------------------------------------- reset after a clean finish

	@Test
	public void aResetArrivingAfterEveryByteAndTheFinDoesNotResurrectTheStream() throws Exception {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		QuicStream clientStream = opened.getResult();
		assertNotNull(clientStream);
		long streamId = clientStream.id();

		routeToServer(streamFrame(streamId, 0, true, "complete"));
		QuicStream serverStream = serverStreams.get(0);
		assertEquals("every byte and the FIN are here, so the reader has a clean end waiting",
			ReceiveState.DATA_RECVD, serverStream.receiveState());

		// A RESET_STREAM agreeing with the final size already fixed. RFC 9000 §3.2 permits ignoring it,
		// and SC-004 requires it: a finished stream must not become a failed one.
		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, "complete".length()));

		assertEquals(ReceiveState.DATA_RECVD, serverStream.receiveState());
		assertEquals("...and no abort was counted", 0, serverManager.streamsResetByPeer());

		Promise<ByteBuf> read = serverStream.reader().get();
		assertTrue("the bytes are still there, not discarded", read.isResult());
		ByteBuf buf = read.getResult();
		assertNotNull(buf);
		assertEquals("complete", buf.getString(StandardCharsets.US_ASCII));
		buf.recycle();

		assertNull("...and end-of-stream follows, not a failure",
			serverStream.reader().get().getResult());
		assertEquals(ReceiveState.DATA_READ, serverStream.receiveState());
	}

	@Test
	public void aResetContradictingAFinalSizeAlreadyFixedByAFinIsAFinalSizeError() throws Exception {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		QuicStream clientStream = opened.getResult();
		assertNotNull(clientStream);
		long streamId = clientStream.id();

		routeToServer(streamFrame(streamId, 0, true, "complete"));

		// The stream is finished, and the abort is therefore a no-op — but "no-op" is about state, not
		// about validation: RFC 9000 §4.5 makes the final size immutable, and disagreeing about it is a
		// connection error whichever frame does the disagreeing.
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 99)));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- FIN after a reset

	@Test
	public void aFinArrivingAfterAResetNeitherUnResetsTheStreamNorChangesItsFinalSize() throws Exception {
		QuicStream clientStream = openAndAccept();
		long streamId = clientStream.id();
		QuicStream serverStream = serverStreams.get(0);
		ReceivePart receivePart = serverStream.receivePart();
		assertNotNull(receivePart);

		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 8));
		assertEquals(ReceiveState.RESET_RECVD, serverStream.receiveState());
		assertEquals(Long.valueOf(8), receivePart.finalSize());

		// A reordered STREAM frame carrying the FIN at exactly the size the reset declared: consistent,
		// so not an error — and it neither un-resets the half nor gives the reader anything.
		routeToServer(streamFrame(streamId, 3, true, "abcde"));

		assertEquals(ReceiveState.RESET_RECVD, serverStream.receiveState());
		assertEquals(Long.valueOf(8), receivePart.finalSize());
		assertEquals("FR-035: it is still charged, and still discarded", 8, receivePart.highestOffsetReceived());
		assertEquals(0, serverManager.bytesDelivered());

		Promise<ByteBuf> read = serverStream.reader().get();
		assertTrue("the abort is what the reader observes, not the late FIN", read.isException());
		Exception e = read.getException();
		assertTrue("expected a QuicStreamResetException, got " + e, e instanceof QuicStreamResetException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
		assertEquals(ReceiveState.RESET_READ, serverStream.receiveState());
	}

	@Test
	public void aFinContradictingTheFinalSizeAResetFixedIsAFinalSizeError() throws Exception {
		QuicStream clientStream = openAndAccept();
		long streamId = clientStream.id();

		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 8));

		// A FIN declaring a different final size, after the reset fixed one. The second message does not
		// win: it is a genuine RFC 9000 §4.5 violation.
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> routeToServer(streamFrame(streamId, 3, true, "abcdefghij")));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void dataAboveTheFinalSizeAResetFixedIsAFinalSizeError() throws Exception {
		QuicStream clientStream = openAndAccept();
		long streamId = clientStream.id();

		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 8));

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> routeToServer(streamFrame(streamId, 8, false, "past the end")));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	@Test
	public void aResetBelowTheBytesAlreadyReceivedIsAFinalSizeError() throws Exception {
		QuicStream clientStream = openAndAccept();
		long streamId = clientStream.id();
		routeToServer(streamFrame(streamId, 3, false, "1234567890"));

		// Thirteen bytes have been received; a final size of five would retroactively unsay eight of
		// them, which RFC 9000 §4.5 forbids.
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 5)));
		assertEquals(QuicTransportErrors.FINAL_SIZE_ERROR, e.errorCode());
	}

	// ---------------------------------------------------------------- a repeated abort

	@Test
	public void aRetransmittedResetAgreeingWithTheFirstIsANoOp() throws Exception {
		QuicStream clientStream = openAndAccept();
		long streamId = clientStream.id();
		QuicStream serverStream = serverStreams.get(0);

		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 8));
		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE, 8));
		// Even one carrying a different code: the first abort is the one the reader observed, and the
		// second cannot rewrite it. The final size is what a retransmission must agree about.
		routeToServer(new ResetStreamFrame(streamId, APP_ERROR_CODE + 1, 8));

		assertEquals("counted once per stream, never per retransmission", 1, serverManager.streamsResetByPeer());
		Promise<ByteBuf> read = serverStream.reader().get();
		assertTrue("the read should have completed", read.isComplete());
		// The exact type first, as every sibling here does: a blind cast reports a ClassCastException —
		// or an NPE on a promise that never completed — instead of naming what the reader actually saw.
		Exception e = read.getException();
		assertTrue("expected a QuicStreamResetException, got " + e, e instanceof QuicStreamResetException);
		assertEquals("the first abort's code binds; the retransmissions cannot rewrite it",
			APP_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
	}

	// ---------------------------------------------------------------- FIN answering a local stop

	@Test
	public void aFinAnsweringALocalStopSendingFinishesTheHalfInsteadOfStrandingIt() throws Exception {
		// A unidirectional stream, so the receiving half is the whole of what the server owns and its
		// fate alone decides whether the stream is released (FR-006).
		Promise<QuicStream> opened = clientManager.openUnidirectional();
		QuicStream clientStream = opened.getResult();
		assertNotNull(clientStream);
		long streamId = clientStream.id();
		Promise<Void> written = clientStream.writer().accept(bytes(3, 'a'));
		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty());
		QuicStream serverStream = serverStreams.get(0);

		serverStream.stopSending(APP_ERROR_CODE);
		assertEquals(1, serverManager.openStreamCount());

		// RFC 9000 §3.5 lets the peer answer a STOP_SENDING with the last of its data rather than a
		// RESET_STREAM, when it had already sent everything. Nothing is buffered and no reader is left,
		// so this arrival is the end of the half — waiting for the reassembler instead would wait for a
		// read offset that a discarding part never advances, which is to say forever.
		routeToServer(streamFrame(streamId, 3, true, "bcd"));

		assertEquals(ReceiveState.DATA_READ, serverStream.receiveState());
		assertEquals("FR-035: still charged for every byte it sent", 6,
			serverStream.receivePart().highestOffsetReceived());
		assertEquals("...and none of it delivered", 0, serverManager.bytesDelivered());
		assertEquals(0, serverManager.openStreamCount());
		assertNull(serverManager.streamOf(streamId));
	}

	// ---------------------------------------------------------------- FR-036: a pending write

	@Test
	public void aResetWhileAWriteIsPendingFailsThatWriteRatherThanStrandingIt() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);

		// More than the client's window, so the tail of it is withheld indefinitely: the exact shape
		// FR-036 exists for, since nothing else will ever resolve this promise.
		Promise<Void> withheld = serverStream.writer().accept(bytes(8 * STREAM_WINDOW, 'w'));
		driveUntil(() -> serverStream.sendState() == SendState.SEND);
		assertFalse(withheld.isComplete());

		serverStream.reset(APP_ERROR_CODE);

		assertTrue("FR-036: an abort resolves every pending operation on the affected half",
			withheld.isComplete());
		Exception e = withheld.getException();
		assertTrue("expected a QuicStreamResetException, got " + e, e instanceof QuicStreamResetException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
		assertEquals(SendState.RESET_SENT, serverStream.sendState());
		assertEquals("RFC 9000 §4.5: the final size is what was written, not what was offered",
			Long.valueOf(STREAM_WINDOW), serverStream.sendPart().finalSize());
	}

	@Test
	public void aPeerStopSendingWhileAWriteIsPendingFailsThatWriteToo() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);

		Promise<Void> withheld = serverStream.writer().accept(bytes(8 * STREAM_WINDOW, 'w'));
		driveUntil(() -> serverStream.sendState() == SendState.SEND);
		assertFalse(withheld.isComplete());

		clientStream.stopSending(APP_ERROR_CODE);
		driveUntil(withheld::isComplete);

		Exception e = withheld.getException();
		assertTrue("expected a QuicStreamStopSendingException, got " + e,
			e instanceof QuicStreamStopSendingException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamStopSendingException) e).applicationErrorCode());
	}

	// ---------------------------------------------------------------- both ends abort at once

	@Test
	public void bothEndsAbortingSimultaneouslySettlesWithoutEitherSideHanging() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		long streamId = clientStream.id();

		// Neither abort has crossed when the other is issued, which is the genuinely simultaneous case a
		// reordering test cannot reach.
		clientStream.reset(APP_ERROR_CODE);
		serverStream.reset(APP_ERROR_CODE + 1);
		clientStream.stopSending(APP_ERROR_CODE + 2);
		serverStream.stopSending(APP_ERROR_CODE + 3);

		driveUntil(() -> clientManager.openStreamCount() == 0 && serverManager.openStreamCount() == 0);

		assertNull(clientManager.streamOf(streamId));
		assertNull(serverManager.streamOf(streamId));
		assertTrue(clientStream.whenClosed().isComplete());
		assertTrue(serverStream.whenClosed().isComplete());
	}
}
