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
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StopSendingFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
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
 * T055 — US4 scenarios 2 and 3: {@link QuicStream#stopSending} (FR-032, FR-033, FR-035).
 *
 * <h2>The two halves of the story, and why they are one test class</h2>
 * <ul>
 *   <li><b>At the peer</b> (FR-033, RFC 9000 §3.5): receiving {@code STOP_SENDING} makes <i>that</i>
 *       endpoint abort its own sending half — the same Reset Sent transition a local
 *       {@link QuicStream#reset} performs, driven from the other end and carrying the <i>asker's</i>
 *       code. Its pending write fails with {@link QuicStreamStopSendingException} and not with
 *       {@link QuicStreamResetException}: "the peer told me to stop" and "the peer gave up" are
 *       different facts, and only one of them is something anybody asked for.</li>
 *   <li><b>Locally</b> (FR-035): bytes the peer had already put in flight keep arriving for a round
 *       trip afterwards. They are <b>still charged</b> to flow control — the peer spent that credit
 *       before it could have heard, and refunding it would let a window be spent twice — and they are
 *       <b>discarded</b> rather than buffered, because there is no reader left to hand them to.</li>
 * </ul>
 *
 * <h2>How the pending write is arranged</h2>
 * The client advertises a 64-byte {@code initial_max_stream_data_bidi_local}, so the server's sending
 * half of the client's stream is held at 64 bytes and a longer write stays withheld — which is what
 * FR-036 is about: an abort must resolve that promise rather than strand it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.5">RFC 9000 §3.5 — Solicited State Transitions</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.5">RFC 9000 §19.5 — STOP_SENDING Frames</a>
 */
public final class StopSendingTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long APP_ERROR_CODE = 0x0BADC0DE_4321L;

	/** The client's window for the server's half of a client-opened stream — small, so a write withholds. */
	private static final int STREAM_WINDOW = 64;

	private static final int MAX_DRIVE_ROUNDS = 300;

	/** A stream layer with a tap on what the <i>other</i> endpoint sent, then handed on unchanged. */
	private static final class TappedStreamLayer implements QuicFrameHandler {
		private final QuicStreamManager delegate;
		final List<StopSendingFrame> stopSendings = new ArrayList<>();
		final List<ResetStreamFrame> resets = new ArrayList<>();

		TappedStreamLayer(QuicStreamManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			if (frame instanceof StopSendingFrame stop) stopSendings.add(stop);
			if (frame instanceof ResetStreamFrame reset) resets.add(reset);
			delegate.onFrame(connection, level, frame);
		}

		@Override
		public void onFrameAcknowledged(QuicConnection connection, QuicFrame frame) {
			delegate.onFrameAcknowledged(connection, frame);
		}

		@Override
		public void onFrameLost(QuicConnection connection, QuicFrame frame) {
			delegate.onFrameLost(connection, frame);
		}

		@Override
		public void onEstablished(QuicConnection connection) {
			delegate.onEstablished(connection);
		}

		@Override
		public void onClosed(QuicConnection connection) {
			delegate.onClosed(connection);
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;
	private TappedStreamLayer clientTap;
	private TappedStreamLayer serverTap;

	private final List<QuicStream> serverStreams = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> {
			clientManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build();
			clientTap = new TappedStreamLayer(clientManager);
			return clientTap;
		});
		wire.withServerFrameHandlerFactory(connection -> {
			serverManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(serverStreams::add)
				.build();
			serverTap = new TappedStreamLayer(serverManager);
			return serverTap;
		});
		// The client's own window for the server's half of a client-opened bidirectional stream is
		// initial_max_stream_data_bidi_local — the RFC 9000 §18.2 naming trap, and the whole point of
		// setting it here rather than on the server.
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

	private static ByteBuf buf(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Opens a stream, makes the server aware of it, and returns both ends. */
	private QuicStream openAndAccept() {
		QuicStream clientStream = openClientStream();
		Promise<Void> hello = clientStream.writer().accept(buf("hello"));
		driveUntil(() -> hello.isComplete() && !serverStreams.isEmpty());
		return clientStream;
	}

	// ---------------------------------------------------------------- scenario 2 (FR-033)

	@Test
	public void thePeersWriterFailsWithTheCodeAndItsSendPartEntersResetSent() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);

		// More than the client's 64-byte window, so part of it is written and the rest is withheld: a
		// pending write, which is what FR-036 requires the abort to resolve.
		Promise<Void> withheld = serverStream.writer().accept(bytes(4 * STREAM_WINDOW, 'x'));
		driveUntil(() -> serverStream.sendState() == SendState.SEND);
		assertFalse("the window is smaller than the write, so it is withheld", withheld.isComplete());

		clientStream.stopSending(APP_ERROR_CODE);
		driveUntil(withheld::isComplete);

		Exception e = withheld.getException();
		assertTrue("expected a QuicStreamStopSendingException, got " + e,
			e instanceof QuicStreamStopSendingException);
		QuicStreamStopSendingException stop = (QuicStreamStopSendingException) e;
		assertEquals("the code is the asker's, carried verbatim (RFC 9000 §20.2)",
			APP_ERROR_CODE, stop.applicationErrorCode());
		assertEquals(serverStream.id(), stop.streamId());

		assertEquals("RFC 9000 §3.5: receiving STOP_SENDING resets this endpoint's own send part",
			SendState.RESET_SENT, serverStream.sendState());
		assertEquals(1, serverTap.stopSendings.size());
		assertEquals(APP_ERROR_CODE, serverTap.stopSendings.get(0).appErrorCode);
		assertEquals(1, serverManager.streamsResetLocally());
	}

	@Test
	public void thePeerAnswersWithAResetStreamCarryingTheSameCode() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> written = serverStream.writer().accept(bytes(STREAM_WINDOW / 2, 'y'));
		driveUntil(written::isComplete);

		clientStream.stopSending(APP_ERROR_CODE);
		driveUntil(() -> !clientTap.resets.isEmpty());

		// RFC 9000 §3.5 pairs the two frames: the answer to a STOP_SENDING is a RESET_STREAM, and it
		// carries the code the asker chose, not one the responder invented.
		assertEquals(1, clientTap.resets.size());
		assertEquals(APP_ERROR_CODE, clientTap.resets.get(0).appErrorCode);
		assertEquals("RFC 9000 §4.5: the final size is whatever had been given an offset",
			STREAM_WINDOW / 2, clientTap.resets.get(0).finalSize);

		// ...and the asker's reader then reports the peer's abort rather than its own request.
		Promise<ByteBuf> read = clientStream.reader().get();
		assertTrue(read.isException());
		Exception e = read.getException();
		assertTrue("expected a QuicStreamResetException, got " + e, e instanceof QuicStreamResetException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamResetException) e).applicationErrorCode());
	}

	@Test
	public void aWriteAfterTheStopTakesItsBufferAndFails() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);

		clientStream.stopSending(APP_ERROR_CODE);
		// Reset Sent, or Reset Recvd if the acknowledgement of its own RESET_STREAM has already come back:
		// which of the two is a matter of ACK scheduling, and neither accepts another byte.
		driveUntil(() -> serverStream.sendState() == SendState.RESET_SENT
						 || serverStream.sendState() == SendState.RESET_RECVD);

		// DI-1: rejection still takes the buffer; ByteBufRule asserts the other half.
		Promise<Void> refused = serverStream.writer().accept(buf("too late"));
		assertTrue(refused.isException());
		assertTrue(refused.getException() instanceof QuicStreamStopSendingException);
	}

	// ---------------------------------------------------------------- scenario 3 (FR-035)

	@Test
	public void bytesStillInFlightAreAccountedAgainstFlowControlButDiscarded() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		ReceivePart receivePart = clientStream.receivePart();
		assertNotNull(receivePart);

		// The server fills the window before it can possibly have heard, which is the ordinary case: a
		// STOP_SENDING takes a round trip and the peer keeps sending for all of it. Every byte is handed
		// to the transport synchronously, so nothing the client does after this can recall them.
		Promise<Void> inFlight = serverStream.writer().accept(bytes(STREAM_WINDOW, 'z'));
		assertTrue(String.valueOf(inFlight.getException()), inFlight.isResult());

		clientStream.stopSending(APP_ERROR_CODE);
		// Before anything crosses back: the reader reports the application's own request, with its own
		// code, since the peer has not had a chance to answer yet.
		Promise<ByteBuf> read = clientStream.reader().get();
		assertTrue(read.isException());
		Exception e = read.getException();
		assertTrue("expected a QuicStreamStopSendingException, got " + e,
			e instanceof QuicStreamStopSendingException);
		assertEquals(APP_ERROR_CODE, ((QuicStreamStopSendingException) e).applicationErrorCode());

		driveUntil(() -> receivePart.highestOffsetReceived() == STREAM_WINDOW);
		assertEquals("FR-035: the peer is still charged for what it sent",
			STREAM_WINDOW, receivePart.highestOffsetReceived());
		assertEquals("...and nothing of it reached the application", 0, clientManager.bytesDelivered());
		assertEquals("...nor is any of it waiting to", 0, receivePart.consumedOffset());
	}

	@Test
	public void bytesBufferedBeforeTheStopAreDiscardedToo() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> written = serverStream.writer().accept(bytes(STREAM_WINDOW / 2, 'q'));
		driveUntil(() -> written.isComplete()
						 && clientStream.receivePart() != null
						 && clientStream.receivePart().highestOffsetReceived() == STREAM_WINDOW / 2);

		// Nobody read them, and the application has now said it never will: they are released at once
		// rather than kept until the connection ends (ByteBufRule is what proves the "released" part).
		clientStream.stopSending(APP_ERROR_CODE);

		Promise<ByteBuf> read = clientStream.reader().get();
		assertTrue("a read must not hand out bytes the application already gave up on", read.isException());
		assertTrue(read.getException() instanceof QuicStreamStopSendingException);
	}

	@Test
	public void theStopIsIdempotentAndSendsOneFrame() {
		QuicStream clientStream = openAndAccept();

		clientStream.stopSending(APP_ERROR_CODE);
		clientStream.stopSending(APP_ERROR_CODE + 1);
		clientStream.stopSending(APP_ERROR_CODE + 2);
		driveUntil(() -> !serverTap.stopSendings.isEmpty());
		wire.pump();

		assertEquals(1, serverTap.stopSendings.size());
		assertEquals(APP_ERROR_CODE, serverTap.stopSendings.get(0).appErrorCode);
	}

	@Test
	public void aLostStopSendingIsReEnqueuedUntilAcknowledged() {
		QuicStream clientStream = openAndAccept();
		clientStream.stopSending(APP_ERROR_CODE);

		// Before it can be acknowledged: the request is what stops the peer, so losing it would cost a
		// whole window of bytes this endpoint will only discard (FR-032).
		clientManager.onFrameLost(wire.client(), new StopSendingFrame(clientStream.id(), APP_ERROR_CODE));
		driveUntil(() -> serverTap.stopSendings.size() > 1);

		assertEquals(2, serverTap.stopSendings.size());
		assertEquals(serverTap.stopSendings.get(0), serverTap.stopSendings.get(1));
	}

	@Test
	public void anAnsweredStopSendingIsNotReEnqueued() {
		QuicStream clientStream = openAndAccept();
		clientStream.stopSending(APP_ERROR_CODE);
		driveUntil(() -> !clientTap.resets.isEmpty());

		int seen = serverTap.stopSendings.size();
		clientManager.onFrameLost(wire.client(), new StopSendingFrame(clientStream.id(), APP_ERROR_CODE));
		wire.pump();
		loop.tick();

		assertEquals("the peer has already stopped: the frame would ask for what has happened",
			seen, serverTap.stopSendings.size());
	}

	// ---------------------------------------------------------------- release (FR-006)

	@Test
	public void aStreamAbandonedInBothDirectionsIsReleased() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		long streamId = clientStream.id();

		// The client stops the server's half and aborts its own, which between them leaves nothing open.
		clientStream.stopSending(APP_ERROR_CODE);
		clientStream.reset(APP_ERROR_CODE);
		driveUntil(() -> serverStream.receiveState() == ReceiveState.RESET_RECVD);

		// RFC 9000 §3.2's Reset Recvd → Reset Read is an *application* action: the server's half stays
		// known to its manager until somebody reads the abort, exactly as Data Recvd → Data Read does.
		assertEquals(1, serverManager.openStreamCount());
		Promise<ByteBuf> observed = serverStream.reader().get();
		assertTrue(observed.isException());
		assertTrue(observed.getException() instanceof QuicStreamResetException);

		driveUntil(() -> clientManager.openStreamCount() == 0 && serverManager.openStreamCount() == 0);

		assertNull(clientManager.streamOf(streamId));
		assertNull(serverManager.streamOf(streamId));
		assertEquals(ReceiveState.RESET_READ, clientStream.receiveState());
		assertEquals(SendState.RESET_RECVD, clientStream.sendState());
	}

	// ---------------------------------------------------------------- RFC 9000 §3.5's "SHOULD NOT"

	@Test
	public void stoppingAStreamThatHasAlreadyFinishedSendsNothing() {
		QuicStream clientStream = openAndAccept();
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> replied = serverStream.writer().accept(buf("done"))
			.then(() -> serverStream.writer().acceptEndOfStream());
		Promise<ByteBuf> read = clientStream.reader().get();
		driveUntil(() -> replied.isComplete() && read.isComplete());
		ByteBuf buf = read.getResult();
		assertNotNull(buf);
		buf.recycle();

		// Every byte is here and the final size is known: RFC 9000 §3.5 says a STOP_SENDING then asks for
		// something that has already happened.
		driveUntil(() -> clientStream.receiveState() == ReceiveState.DATA_RECVD);
		clientStream.stopSending(APP_ERROR_CODE);
		wire.pump();

		assertEquals(List.of(), serverTap.stopSendings);
		assertNull("...and the reader still sees the clean end of the stream",
			clientStream.reader().get().getResult());
	}
}
