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
import io.activej.bytebuf.ByteBufs;
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
 * T048 — US3 scenario 5: what a finished stream gives back (FR-006, FR-028).
 *
 * <h2>Three things, and the last is the one that matters to the peer</h2>
 * <ol>
 *   <li>the stream leaves the manager's map, so it costs nothing further;</li>
 *   <li><b>and not before the application has drained it</b> — a receiving half whose bytes nobody has
 *       read is not terminal, however complete the transfer is on the wire;</li>
 *   <li>its concurrency credit returns to the <b>peer-initiated</b> counter's accounting, and once half
 *       the advertised window has come back the peer is told with a {@code MAX_STREAMS}
 *       (RFC 9000 §19.11), by the same ½ threshold as every other grant in this layer.</li>
 * </ol>
 * Only a peer-initiated type has anything to grant: {@code initial_max_streams_bidi} is a permission
 * <i>this</i> endpoint gave <i>its peer</i>, so it is the peer's streams closing that frees it. A test
 * that asserted "a MAX_STREAMS was sent" would pass for an implementation that granted on every
 * release, which is why the negative half is asserted from both ends here — and why the grant is
 * asserted as a <b>literal count</b> rather than as "some increase".
 *
 * <h2>The windows</h2>
 * Both endpoints advertise a count of two, so the ½ rule fires on the very first release
 * ({@code limit - closed = 1 <= 2/2}) and the granted limit is exactly {@code closed + window = 3}.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.11">RFC 9000 §19.11 — MAX_STREAMS Frames</a>
 */
public final class StreamReleaseTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What each endpoint advertises per direction: small, so the ½ threshold is reached by one release. */
	private static final long STREAM_COUNT_WINDOW = 2;

	private static final int MAX_DRIVE_ROUNDS = 300;

	/**
	 * A stream layer with a tap on it: every {@code MAX_STREAMS} the <i>other</i> endpoint sent is
	 * recorded, and then handed on unchanged, so both peers keep working exactly as they otherwise would.
	 */
	private static final class TappedStreamLayer implements QuicFrameHandler {
		private final QuicStreamManager delegate;
		final List<Long> maxStreamsBidi = new ArrayList<>();
		final List<Long> maxStreamsUni = new ArrayList<>();

		TappedStreamLayer(QuicStreamManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			if (frame instanceof MaxStreamsFrame max) {
				(max.type == QuicStreamLimitType.BIDIRECTIONAL ? maxStreamsBidi : maxStreamsUni)
					.add(max.maximum);
			}
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
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	/** Whether the server's listener drains what it is given. Off for the "not drained" case. */
	private boolean drainOnAccept = true;

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		serverReads.clear();
		drainOnAccept = true;
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> {
			clientManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build();
			clientTap = new TappedStreamLayer(clientManager);
			return clientTap;
		});
		wire.withServerFrameHandlerFactory(connection -> {
			serverManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					if (drainOnAccept) {
						serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
					}
				})
				.build();
			serverTap = new TappedStreamLayer(serverManager);
			return serverTap;
		});
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(STREAM_COUNT_WINDOW)
			.withInitialMaxStreamsUni(STREAM_COUNT_WINDOW)
			.build();
		wire.startClient(settings);
		wire.acceptServer(settings);
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

	private static ByteBuf buf(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private static String drain(Promise<ByteBuf> collected) {
		assertTrue("the transfer should have completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		String s = buf.getString(StandardCharsets.US_ASCII);
		buf.recycle();
		return s;
	}

	private QuicStream open(StreamDirection direction) {
		Promise<QuicStream> opened = direction == StreamDirection.BIDIRECTIONAL
			? clientManager.openBidirectional()
			: clientManager.openUnidirectional();
		assertTrue("the connection is established and there is credit for two streams",
			opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Runs one bidirectional stream to completion on both halves and both ends. */
	private QuicStream transferAndDrainOneBidirectionalStream() {
		QuicStream clientStream = open(StreamDirection.BIDIRECTIONAL);
		Promise<Void> written = clientStream.writer().accept(buf("request"))
			.then(() -> clientStream.writer().acceptEndOfStream());
		Promise<ByteBuf> clientRead = clientStream.reader().toCollector(ByteBufs.collector());

		driveUntil(() -> !serverStreams.isEmpty());
		QuicStream serverStream = serverStreams.get(0);
		// A bidirectional stream is terminal only when both halves are, so the server must end its own.
		Promise<Void> replied = serverStream.writer().accept(buf("response"))
			.then(() -> serverStream.writer().acceptEndOfStream());

		driveUntil(() -> written.isComplete() && replied.isComplete()
						 && serverReads.get(0).isComplete() && clientRead.isComplete()
						 && serverManager.openStreamCount() == 0 && clientManager.openStreamCount() == 0);

		assertEquals("request", drain(serverReads.get(0)));
		assertEquals("response", drain(clientRead));
		return serverStream;
	}

	/** A frame the <b>test</b> owns, exactly as the connection owns the one it hands to {@code onFrame}. */
	private static StreamFrame frame(long streamId, String data) {
		byte[] array = data.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return new StreamFrame(streamId, 0, false, buf);
	}

	private void route(StreamFrame frame) throws QuicTransportException {
		try {
			serverTap.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			frame.recycle();
		}
	}

	// ---------------------------------------------------------------- removal from the map (FR-006)

	@Test
	public void aFullyTerminalAndDrainedStreamLeavesTheMapOnBothEnds() {
		QuicStream serverStream = transferAndDrainOneBidirectionalStream();

		assertEquals(0, serverManager.openStreamCount());
		assertEquals(0, clientManager.openStreamCount());
		assertNull(serverManager.streamOf(0));
		assertNull(clientManager.streamOf(0));
		assertEquals(ReceiveState.DATA_READ, serverStream.receiveState());
		assertEquals(SendState.DATA_RECVD, serverStream.sendState());
		assertTrue("whenClosed resolves when the stream is released", serverStream.whenClosed().isComplete());
	}

	@Test
	public void aTransferredButUndrainedStreamIsNotReleased() {
		drainOnAccept = false;

		QuicStream clientStream = open(StreamDirection.BIDIRECTIONAL);
		Promise<Void> written = clientStream.writer().accept(buf("request"))
			.then(() -> clientStream.writer().acceptEndOfStream());

		driveUntil(() -> !serverStreams.isEmpty());
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> replied = serverStream.writer().acceptEndOfStream();
		driveUntil(() -> written.isComplete() && replied.isComplete()
						 && serverStream.sendState() == SendState.DATA_RECVD);

		assertEquals("every byte has arrived, but nobody has read them: not terminal (FR-006)",
			ReceiveState.DATA_RECVD, serverStream.receiveState());
		assertEquals(1, serverManager.openStreamCount());
		assertSame(serverStream, serverManager.streamOf(0));
		assertEquals("...and nothing has been granted back", List.of(), clientTap.maxStreamsBidi);

		// The read is what completes it.
		Promise<ByteBuf> read = serverStream.reader().toCollector(ByteBufs.collector());
		driveUntil(() -> read.isComplete() && serverManager.openStreamCount() == 0);
		assertEquals("request", drain(read));
		assertEquals(List.of(3L), clientTap.maxStreamsBidi);
	}

	// ---------------------------------------------------------------- the grant (FR-028)

	@Test
	public void releasingAPeerInitiatedStreamGrantsMaxStreamsAtTheHalfThreshold() {
		transferAndDrainOneBidirectionalStream();
		driveUntil(() -> !clientTap.maxStreamsBidi.isEmpty());

		// closed = 1 of a window of 2, so limit - closed = 1 <= 2/2: the grant is due, and its value is
		// exactly closed + window.
		assertEquals("RFC 9000 §19.11: the new absolute stream count, closed + window",
			List.of(1L + STREAM_COUNT_WINDOW), clientTap.maxStreamsBidi);
		assertEquals("the unidirectional count is a different limit entirely",
			List.of(), clientTap.maxStreamsUni);
	}

	@Test
	public void releasingALocallyInitiatedStreamGrantsNothing() {
		transferAndDrainOneBidirectionalStream();
		driveUntil(() -> !clientTap.maxStreamsBidi.isEmpty());

		// Stream 0 is the client's own, so on the client it is a locally-initiated release: it frees a
		// permission the server never held, and announcing one would hand out credit twice.
		assertEquals("a locally-initiated stream's closure grants the peer nothing",
			List.of(), serverTap.maxStreamsBidi);
		assertEquals(List.of(), serverTap.maxStreamsUni);
	}

	@Test
	public void theGrantedCountIsWhatThePeerMayThenOpen() throws Exception {
		transferAndDrainOneBidirectionalStream();
		driveUntil(() -> !clientTap.maxStreamsBidi.isEmpty());
		assertEquals(List.of(3L), clientTap.maxStreamsBidi);

		// Ordinal 2 was refused before the release (the advertised count was two); the granted limit of
		// three is what makes stream 8 admissible now, which is the whole point of the frame.
		route(frame(4, "second"));
		route(frame(8, "third"));

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertNotNull(serverManager.streamOf(8));

		// ...and the new limit still binds: ordinal 3 is one too far.
		QuicTransportException e = assertThrows(QuicTransportException.class, () -> route(frame(12, "fourth")));
		assertEquals(QuicTransportErrors.STREAM_LIMIT_ERROR, e.errorCode());
	}

	@Test
	public void aUnidirectionalReleaseGrantsUnidirectionalCredit() {
		QuicStream clientStream = open(StreamDirection.UNIDIRECTIONAL);
		Promise<Void> written = clientStream.writer().accept(buf("push"))
			.then(() -> clientStream.writer().acceptEndOfStream());

		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete()
						 && serverManager.openStreamCount() == 0);
		driveUntil(() -> !clientTap.maxStreamsUni.isEmpty());

		assertEquals("push", drain(serverReads.get(0)));
		assertEquals(List.of(1L + STREAM_COUNT_WINDOW), clientTap.maxStreamsUni);
		assertEquals("RFC 9000 §19.11 counts the two directions apart", List.of(),
			clientTap.maxStreamsBidi);
		// A unidirectional stream has one half on each endpoint, so both are done at once.
		assertEquals(0, clientManager.openStreamCount());
	}

	// ---------------------------------------------------------------- STREAMS_BLOCKED is not a request

	@Test
	public void aStreamsBlockedFromThePeerGrantsNothingThatIsNotDue() throws Exception {
		// RFC 9000 §19.14 is informational. A receiver that granted because the peer complained would be
		// handing out concurrency it has not got back — the stream-count twin of FR-025's rule for data.
		wire.client().enqueueFrame(new StreamsBlockedFrame(STREAM_COUNT_WINDOW,
			QuicStreamLimitType.BIDIRECTIONAL));
		wire.client().requestSend();
		wire.pump();

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertEquals("nothing has been released, so nothing is due", List.of(), clientTap.maxStreamsBidi);
	}

	@Test
	public void aRepeatedStreamsBlockedNeverGrantsTheSameCreditTwice() throws Exception {
		transferAndDrainOneBidirectionalStream();
		driveUntil(() -> !clientTap.maxStreamsBidi.isEmpty());
		assertEquals(List.of(3L), clientTap.maxStreamsBidi);

		// The branch exists so that a peer whose MAX_STREAMS was lost can prompt a fresh one once the
		// retransmission phase regenerates limits rather than replaying them. Until then its whole
		// observable contract is that it cannot be used to extract credit: the granted limit is a function
		// of what has been released, so repeating the complaint recomputes the same number.
		for (int i = 0; i < 3; i++) {
			wire.client().enqueueFrame(new StreamsBlockedFrame(3, QuicStreamLimitType.BIDIRECTIONAL));
			wire.client().requestSend();
			wire.pump();
		}

		assertEquals("the limit did not move, so there is nothing to announce", List.of(3L),
			clientTap.maxStreamsBidi);
	}

	@Test
	public void asecondReleaseGrantsAgainByTheSameRule() {
		transferAndDrainOneBidirectionalStream();
		driveUntil(() -> !clientTap.maxStreamsBidi.isEmpty());
		serverStreams.clear();
		serverReads.clear();

		QuicStream clientStream = open(StreamDirection.BIDIRECTIONAL);
		assertEquals("RFC 9000 §2.1: releasing a stream frees concurrency, never an identifier",
			4, clientStream.id());
		Promise<Void> written = clientStream.writer().accept(buf("again"))
			.then(() -> clientStream.writer().acceptEndOfStream());
		Promise<ByteBuf> clientRead = clientStream.reader().toCollector(ByteBufs.collector());

		driveUntil(() -> !serverStreams.isEmpty());
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> replied = serverStream.writer().acceptEndOfStream();
		driveUntil(() -> written.isComplete() && replied.isComplete() && clientRead.isComplete()
						 && serverManager.openStreamCount() == 0);
		driveUntil(() -> clientTap.maxStreamsBidi.size() > 1);

		assertEquals(0, drain(clientRead).length());
		assertEquals("closed is now two, so the limit moves to two plus the window",
			List.of(3L, 4L), clientTap.maxStreamsBidi);
	}
}
