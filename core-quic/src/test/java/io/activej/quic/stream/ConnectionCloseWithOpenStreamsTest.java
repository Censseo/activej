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
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T057 — FR-041 and SC-007: what the end of a connection does to the streams still on it.
 *
 * <h2>Unwrapped, all of them, once</h2>
 * <ul>
 *   <li><b>Unwrapped</b>: a transport failure surfaces as feature 03's {@link QuicTransportException},
 *       <b>not</b> wrapped in a {@link QuicStreamException}. Feature 05 reads the RFC 9000 §20 code off
 *       it to decide what to tell the application, and a wrapper would hide it. This is asserted
 *       negatively as well as positively, because a wrapper is exactly the mistake a tidy-looking
 *       implementation makes.</li>
 *   <li><b>All of them</b>: every pending read, every pending write and every withheld open — the last
 *       of which lives in a different structure from the other two and is the one an implementation
 *       forgets.</li>
 *   <li><b>Once</b>: closing twice, or a second {@code onClosed} from any source, must neither fail a
 *       promise twice nor recycle a buffer twice (WI-9). {@code ByteBufRule} answers the second half;
 *       a promise completed twice throws on its own, so the assertion is that nothing throws.</li>
 * </ul>
 *
 * <h2>How each pending thing is arranged</h2>
 * The server advertises a two-stream bidirectional limit and a 16-byte
 * {@code initial_max_stream_data_bidi_remote}, so on the client: two streams open and the third
 * withheld (FR-029), and a write longer than 16 bytes withheld at the stream limit (FR-022). The
 * parked read needs no arrangement — a stream nobody replies on has one by construction.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10">RFC 9000 §10 — Connection Termination</a>
 */
public final class ConnectionCloseWithOpenStreamsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What the server advertises: two bidirectional streams, so the third open is withheld. */
	private static final long STREAM_COUNT_WINDOW = 2;

	/** The server's window for the client's half of a client-opened stream — small, so a write withholds. */
	private static final int STREAM_WINDOW = 16;

	private static final long PEER_ERROR_CODE = 0x1234L;

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
				.withStreamListener(serverStreams::add)
				.build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(STREAM_COUNT_WINDOW)
			.withInitialMaxStreamDataBidiRemote(MemSize.bytes(STREAM_WINDOW))
			.build());
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
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

	private QuicStream open() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("there is credit for this one", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Every kind of pending operation this layer can hold, on one connection at one moment. */
	private record Pending(
		QuicStream writing, Promise<Void> withheldWrite,
		QuicStream reading, Promise<ByteBuf> parkedRead,
		Promise<QuicStream> withheldOpen
	) {}

	private Pending arrangeEveryKindOfPendingOperation() {
		QuicStream writing = open();
		QuicStream reading = open();

		// Longer than the 16-byte window, so its tail is withheld and nothing but an event will move it.
		Promise<Void> withheldWrite = writing.writer().accept(bytes(8 * STREAM_WINDOW, 'w'));
		// A short write, purely so the server learns this stream exists — RFC 9000 §2.1 has a stream come
		// into being at the peer when a frame for it arrives, and a reader alone sends nothing.
		Promise<Void> announced = reading.writer().accept(bytes(1, 'r'));
		// Nobody will ever reply on this one.
		Promise<ByteBuf> parkedRead = reading.reader().get();
		// The third of a two-stream window: withheld in the manager's own deque, not on any stream.
		Promise<QuicStream> withheldOpen = clientManager.openBidirectional();

		driveUntil(() -> serverStreams.size() == 2 && announced.isComplete());
		assertFalse("the write is held at the stream limit", withheldWrite.isComplete());
		assertFalse("nothing has been sent back", parkedRead.isComplete());
		assertFalse("the peer granted two streams and both are open", withheldOpen.isComplete());
		return new Pending(writing, withheldWrite, reading, parkedRead, withheldOpen);
	}

	private static QuicTransportException transportExceptionOf(String what, Promise<?> promise) {
		assertTrue(what + " should have been failed, not left pending", promise.isComplete());
		Exception e = promise.getException();
		assertNotNull(what + " should have failed", e);
		assertFalse("FR-041: the connection's failure reaches " + what + " unwrapped, so that the" +
					" RFC 9000 §20 code stays visible — got " + e,
			e instanceof QuicStreamException);
		assertTrue("expected a QuicTransportException for " + what + ", got " + e,
			e instanceof QuicTransportException);
		return (QuicTransportException) e;
	}

	// ---------------------------------------------------------------- a local close

	@Test
	public void aLocalCloseFailsEveryPendingReadWriteAndOpen() {
		Pending pending = arrangeEveryKindOfPendingOperation();

		wire.client().closeNow();

		transportExceptionOf("the withheld write", pending.withheldWrite());
		transportExceptionOf("the parked read", pending.parkedRead());
		transportExceptionOf("the withheld open", pending.withheldOpen());
		transportExceptionOf("whenClosed of the writing stream", pending.writing().whenClosed());
		transportExceptionOf("whenClosed of the reading stream", pending.reading().whenClosed());

		assertEquals("the map is cleared, whatever state its streams were in", 0,
			clientManager.openStreamCount());
	}

	// ---------------------------------------------------------------- a peer CONNECTION_CLOSE

	@Test
	public void aPeerCloseReachesEveryPendingOperationWithItsOwnErrorCode() {
		Pending pending = arrangeEveryKindOfPendingOperation();

		wire.server().closeWith(QuicTransportErrors.INTERNAL_ERROR, "the server gave up");
		// Draining first, then closed: RFC 9000 §10.2 has the receiver of a CONNECTION_CLOSE wait out
		// three PTOs before it is done, and it is *that* transition the frame handler is told about.
		driveUntil(() -> clientManager.openStreamCount() == 0);
		assertEquals(QuicConnectionState.CLOSED, wire.client().state());

		assertEquals("the peer's RFC 9000 §20 code, not a substitute for it",
			QuicTransportErrors.INTERNAL_ERROR,
			transportExceptionOf("the withheld write", pending.withheldWrite()).errorCode());
		assertEquals(QuicTransportErrors.INTERNAL_ERROR,
			transportExceptionOf("the parked read", pending.parkedRead()).errorCode());
		assertEquals(QuicTransportErrors.INTERNAL_ERROR,
			transportExceptionOf("the withheld open", pending.withheldOpen()).errorCode());
	}

	@Test
	public void anApplicationCloseCarriesItsApplicationCodeThrough() {
		Pending pending = arrangeEveryKindOfPendingOperation();

		// An application-level CONNECTION_CLOSE (RFC 9000 §19.19, type 0x1d) is what feature 05 will send
		// for an H3 error that does warrant killing the connection, so its code has to survive the trip.
		wire.server().closeWith(PEER_ERROR_CODE, "the application gave up");
		// Draining first, then closed: RFC 9000 §10.2 has the receiver of a CONNECTION_CLOSE wait out
		// three PTOs before it is done, and it is *that* transition the frame handler is told about.
		driveUntil(() -> clientManager.openStreamCount() == 0);
		assertEquals(QuicConnectionState.CLOSED, wire.client().state());

		assertEquals(PEER_ERROR_CODE,
			transportExceptionOf("the parked read", pending.parkedRead()).errorCode());
	}

	// ---------------------------------------------------------------- idempotence (WI-9)

	@Test
	public void aRepeatedCloseChangesNothingAndThrowsNothing() {
		Pending pending = arrangeEveryKindOfPendingOperation();

		wire.client().closeNow();
		Exception first = pending.parkedRead().getException();
		assertNotNull(first);

		// Three more, by every route there is: the connection's own, and the handler seam the connection
		// drives it through. A promise completed twice throws, and a buffer recycled twice fails
		// ByteBufRule, so "nothing happened" is a real assertion here rather than a vacuous one.
		wire.client().closeNow();
		clientManager.onClosed(wire.client());
		clientManager.onClosed(wire.client());

		assertSame("the first failure is the one that stands", first, pending.parkedRead().getException());
		assertEquals(0, clientManager.openStreamCount());
	}

	@Test
	public void aCloseAfterTheStreamsHaveBeenAbortedIsStillIdempotent() {
		Pending pending = arrangeEveryKindOfPendingOperation();

		// A reset already failed the write and left a RESET_STREAM being retransmitted; the connection
		// ending underneath it must neither re-fail that promise nor leave the abort retransmitting.
		pending.writing().reset(PEER_ERROR_CODE);
		pending.reading().stopSending(PEER_ERROR_CODE);
		Exception writeFailure = pending.withheldWrite().getException();
		Exception readFailure = pending.parkedRead().getException();
		assertTrue("the abort is a stream-scoped failure, unlike the connection's",
			writeFailure instanceof QuicStreamResetException);
		assertTrue(readFailure instanceof QuicStreamStopSendingException);

		wire.client().closeNow();
		clientManager.onClosed(wire.client());

		assertSame(writeFailure, pending.withheldWrite().getException());
		assertSame(readFailure, pending.parkedRead().getException());
		transportExceptionOf("the withheld open", pending.withheldOpen());
		assertEquals(0, clientManager.openStreamCount());
	}

	// ---------------------------------------------------------------- buffers (FR-041, SC-007)

	@Test
	public void everyBufferAStreamStillHoldsIsReleased() {
		QuicStream writing = open();
		QuicStream reading = open();

		// Held on the send side: the withheld tail of a write, owned by the sending part until something
		// releases it. Held on the receive side: bytes buffered for a reader that never came.
		Promise<Void> withheldWrite = writing.writer().accept(bytes(8 * STREAM_WINDOW, 'w'));
		Promise<Void> delivered = reading.writer().accept(bytes(STREAM_WINDOW, 'r'));
		driveUntil(() -> serverStreams.size() == 2 && delivered.isComplete());

		QuicStream serverSide = serverStreams.get(1);
		driveUntil(() -> {
			ReceivePart part = serverSide.receivePart();
			return part != null && part.highestOffsetReceived() == STREAM_WINDOW;
		});
		assertEquals("nobody read them, so they are still held", 0, serverManager.bytesDelivered());

		wire.client().closeNow();
		wire.server().closeNow();

		assertTrue(withheldWrite.isException());
		assertEquals(0, clientManager.openStreamCount());
		assertEquals(0, serverManager.openStreamCount());
		// ByteBufRule is the assertion: the withheld tail and the unread buffered range were both
		// released by the teardown, and neither of them twice.
	}
}
