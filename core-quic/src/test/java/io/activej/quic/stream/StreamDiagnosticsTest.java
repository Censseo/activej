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
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T082 — <b>FR-043</b>: every counter the requirement names is readable <b>with no inspector
 * attached</b>, starts at zero, and moves for the event it is documented to count.
 *
 * <h2>The nine counters, and why "no inspector attached" is the point</h2>
 * FR-043 lists: streams opened locally, streams accepted from the peer, streams reset in each
 * direction, bytes delivered, bytes sent, and the number of times sending was blocked by each of the
 * three limits. FR-044's {@code Inspector} is <b>optional</b>, so a deployment that never attaches one
 * — which is every deployment until feature 07 supplies a JMX implementation — must still be able to
 * see all nine. Every manager in this class is therefore built with the plain builder, exactly as
 * every other test in this package builds one.
 *
 * <h2>Real events, not method calls</h2>
 * Each counter is moved by the event it counts, over a real handshaken {@link QuicWirePair}: a stream
 * is opened, accepted, reset, written to, read from, or held by a limit. A counter that only moved
 * when something poked it directly would prove nothing about the paths that are supposed to move it.
 * <p>
 * The three "blocked" counters need three different peers to produce, because each is a different
 * limit the <i>peer</i> advertises: a small {@code initial_max_stream_data_bidi_remote} for the stream
 * limit, a small {@code initial_max_data} for the connection one, and
 * {@code initial_max_streams_bidi = 0} for the stream-count one. Each gets its own fixture rather
 * than one fixture with everything small, so that a counter cannot pass by being moved by the wrong
 * limit.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.4">RFC 9000 §19.4 — RESET_STREAM Frames</a>
 */
public final class StreamDiagnosticsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;
	private static final long APP_ERROR_CODE = 0x0D1A6L;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	/**
	 * Whether the accepting side attaches a collector to each stream it is given. Off for the three
	 * "blocked" tests, and that is not a detail: a reader is what grants credit (FR-025), so a server
	 * that reads reopens the very window those tests need to stay shut.
	 */
	private boolean collectOnAccept = true;

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		if (loop != null) loop.close();
	}

	// ---------------------------------------------------------------- fixture

	/**
	 * A handshaken pair whose <b>server</b> collects every accepted stream. Both managers are built with
	 * no inspector, which is the property under test.
	 *
	 * @param clientSettings what this endpoint (the client) advertises
	 * @param serverSettings what the peer advertises — the limits the client's writers run into
	 */
	private void handshake(QuicConnectionSettings clientSettings, QuicConnectionSettings serverSettings)
		throws MalformedDataException {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					if (collectOnAccept && stream.hasReceivePart()) {
						serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
					}
				})
				.build());
		wire.startClient(clientSettings);
		wire.acceptServer(serverSettings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	private void handshake() throws MalformedDataException {
		handshake(QuicConnectionSettings.create(), QuicConnectionSettings.create());
	}

	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			wire.pump();
			loop.tick();
			if (done.getAsBoolean()) return;
			loop.advance(5);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not settle within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static ByteBuf bytes(int size, int seed) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, size));
		for (int i = 0; i < size; i++) {
			buf.put((byte) (i * 31 + seed));
		}
		return buf;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Drains a collector so the leak rule has nothing left to complain about. */
	private static void discard(Promise<ByteBuf> collected) {
		if (!collected.isResult()) return;
		ByteBuf buf = collected.getResult();
		if (buf != null) buf.recycle();
	}

	// ---------------------------------------------------------------- the baseline

	@Test
	public void everyCounterIsZeroOnAFreshManagerAndReadableWithNoInspectorAttached()
		throws MalformedDataException {
		handshake();

		// All nine of FR-043, plus the two accessors that describe the same state, read without throwing
		// and without an inspector ever having been attached.
		assertEquals(0, clientManager.streamsOpenedLocally());
		assertEquals(0, clientManager.streamsAcceptedFromPeer());
		assertEquals(0, clientManager.streamsResetLocally());
		assertEquals(0, clientManager.streamsResetByPeer());
		assertEquals(0, clientManager.bytesDelivered());
		assertEquals(0, clientManager.bytesSent());
		assertEquals(0, clientManager.timesBlockedByConnectionLimit());
		assertEquals(0, clientManager.timesBlockedByStreamLimit());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());
		assertEquals(0, clientManager.openStreamCount());
		assertEquals(0, clientManager.outstandingStreamBytes());

		// And the same on the accepting side, which has a listener but still no inspector.
		assertEquals(0, serverManager.streamsOpenedLocally());
		assertEquals(0, serverManager.streamsAcceptedFromPeer());
		assertEquals(0, serverManager.bytesDelivered());
	}

	// ---------------------------------------------------------------- opened / accepted

	@Test
	public void openingLocallyMovesStreamsOpenedLocallyAndNothingElse() throws MalformedDataException {
		handshake();

		openClientStream();
		clientManager.openUnidirectional();

		assertEquals(2, clientManager.streamsOpenedLocally());
		assertEquals("a stream this endpoint opened is not one it accepted", 0,
			clientManager.streamsAcceptedFromPeer());
		assertEquals(0, clientManager.bytesSent());
	}

	@Test
	public void acceptingFromThePeerMovesStreamsAcceptedFromPeerOnTheReceivingSideOnly()
		throws MalformedDataException {
		handshake();

		QuicStream stream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(bytes(64, 1)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertEquals(1, serverManager.streamsAcceptedFromPeer());
		assertEquals("the accepting side opened nothing itself", 0, serverManager.streamsOpenedLocally());
		assertEquals(1, clientManager.streamsOpenedLocally());
		assertEquals(0, clientManager.streamsAcceptedFromPeer());

		discard(serverReads.get(0));
	}

	@Test
	public void oneIdentifierAcceptsTheWholeImplicitlyOpenedRun() throws MalformedDataException {
		handshake();

		// RFC 9000 §2.1, FR-003: naming the third stream opens the two below it, and all three count.
		clientManager.openBidirectional();
		clientManager.openBidirectional();
		QuicStream third = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(bytes(16, 2)).streamTo(third.writer());
		driveUntil(() -> written.isComplete() && serverManager.streamsAcceptedFromPeer() == 3);

		assertEquals(3, serverManager.streamsAcceptedFromPeer());
		assertEquals(3, clientManager.streamsOpenedLocally());

		serverReads.forEach(StreamDiagnosticsTest::discard);
	}

	// ---------------------------------------------------------------- bytes

	@Test
	public void bytesSentAndBytesDeliveredMoveWithARealTransfer() throws MalformedDataException {
		handshake();
		int size = 40 * 1024;

		QuicStream stream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(bytes(size, 3)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertEquals("every byte given an offset and handed to the transport", size, clientManager.bytesSent());
		assertEquals("nothing was delivered *to* the sender", 0, clientManager.bytesDelivered());
		assertEquals("every byte the application took from a reader", size, serverManager.bytesDelivered());
		assertEquals("the accepting side sent nothing back", 0, serverManager.bytesSent());

		discard(serverReads.get(0));
	}

	@Test
	public void bytesSentCountsARetransmittedByteOnlyOnce() throws MalformedDataException {
		handshake();
		int size = 8 * 1024;

		QuicStream stream = openClientStream();
		// Nothing reaches the peer while the wire is a blackhole, so every one of these frames is lost and
		// retransmitted — and bytesSent must still be exactly `size` afterwards.
		wire.clientWire().blackhole(true);
		Promise<Void> written = ChannelSuppliers.ofValue(bytes(size, 4)).streamTo(stream.writer());
		wire.pump();
		long afterFirstAttempt = clientManager.bytesSent();
		wire.clientWire().blackhole(false);

		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertEquals(size, afterFirstAttempt);
		assertEquals("a retransmitted byte is not a second byte sent", size, clientManager.bytesSent());
		assertEquals(size, serverManager.bytesDelivered());

		discard(serverReads.get(0));
	}

	// ---------------------------------------------------------------- resets, in both directions

	@Test
	public void aLocalAbortMovesStreamsResetLocallyOnTheAbortingSideAndResetByPeerOnTheOther()
		throws MalformedDataException {
		handshake();

		QuicStream stream = openClientStream();
		Promise<Void> written = stream.writer().accept(bytes(1024, 5));
		driveUntil(() -> !serverStreams.isEmpty());

		stream.reset(APP_ERROR_CODE);
		driveUntil(() -> serverManager.streamsResetByPeer() == 1);

		assertEquals(1, clientManager.streamsResetLocally());
		assertEquals("the aborting side was not reset *by* anyone", 0, clientManager.streamsResetByPeer());
		assertEquals(1, serverManager.streamsResetByPeer());
		assertEquals(0, serverManager.streamsResetLocally());
		assertTrue("the withheld write is resolved, not stranded (FR-036)", written.isComplete());

		serverReads.forEach(StreamDiagnosticsTest::discard);
	}

	@Test
	public void aStopSendingForcedAbortStillCountsAsAResetOfThisEndpointsOwn()
		throws MalformedDataException {
		handshake();

		QuicStream stream = openClientStream();
		Promise<Void> written = stream.writer().accept(bytes(2048, 6));
		driveUntil(() -> !serverStreams.isEmpty());

		// RFC 9000 §3.5: the peer's STOP_SENDING makes *this* endpoint abort its own sending half, so the
		// RESET_STREAM on the wire is this endpoint's — which is what streamsResetLocally counts.
		serverStreams.get(0).stopSending(APP_ERROR_CODE);
		driveUntil(() -> clientManager.streamsResetLocally() == 1);

		assertEquals(1, clientManager.streamsResetLocally());
		assertEquals("nothing was reset *by* the peer: a STOP_SENDING is not a RESET_STREAM", 0,
			clientManager.streamsResetByPeer());
		assertTrue(written.isComplete());

		// And the abort is visible to the writer with the peer's code (FR-033).
		Promise<Void> after = stream.writer().accept(bytes(64, 11));
		driveUntil(after::isComplete);
		assertFalse(after.isResult());
		assertTrue(String.valueOf(after.getException()),
			after.getException() instanceof QuicStreamStopSendingException);

		serverReads.forEach(StreamDiagnosticsTest::discard);
	}

	@Test
	public void aResetIsCountedOncePerStreamAndNotOncePerRetransmission() throws MalformedDataException {
		handshake();

		QuicStream stream = openClientStream();
		wire.clientWire().blackhole(true);
		stream.reset(APP_ERROR_CODE);
		// The RESET_STREAM is reliable, so it goes out again for as long as it is lost (FR-031) — the
		// counter must not follow it.
		for (int i = 0; i < 5; i++) {
			wire.pump();
			loop.advance(200);
		}
		wire.clientWire().blackhole(false);
		driveUntil(() -> serverManager.streamsResetByPeer() == 1);

		assertEquals(1, clientManager.streamsResetLocally());
		assertEquals("a retransmitted abort is the same abort", 1, serverManager.streamsResetByPeer());

		serverReads.forEach(StreamDiagnosticsTest::discard);
	}

	// ---------------------------------------------------------------- the three blocked counters

	@Test
	public void aWriterHeldByItsOwnStreamLimitMovesTimesBlockedByStreamLimit()
		throws MalformedDataException {
		int window = 4 * 1024;
		collectOnAccept = false;
		handshake(QuicConnectionSettings.create(), QuicConnectionSettings.builder()
			// What the client may send on a bidirectional stream *it* opened (RFC 9000 §18.2). The
			// connection-level window stays at its 1 MiB default, so this is the only limit that can bind.
			.withInitialMaxStreamDataBidiRemote(MemSize.of(window))
			.build());

		QuicStream stream = openClientStream();
		Promise<Void> written = stream.writer().accept(bytes(4 * window, 7));
		wire.pump();

		assertFalse("the peer granted " + window + " bytes and no more", written.isComplete());
		assertEquals(1, clientManager.timesBlockedByStreamLimit());
		assertEquals("a stream limit is not a connection limit", 0,
			clientManager.timesBlockedByConnectionLimit());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());

		// FR-027: one announcement, and therefore one count, per distinct limit value.
		Promise<Void> second = stream.writer().accept(bytes(64, 8));
		wire.pump();
		assertEquals(1, clientManager.timesBlockedByStreamLimit());

		stream.reset(APP_ERROR_CODE);
		driveUntil(() -> written.isComplete() && second.isComplete());
	}

	@Test
	public void aWriterHeldByTheConnectionLimitMovesTimesBlockedByConnectionLimit()
		throws MalformedDataException {
		// Equal windows, because a build-time invariant forbids a per-stream window above the connection
		// one — a stream may not advertise credit the connection can never honour. The connection limit is
		// therefore made to bind by using *two* streams: neither reaches its own 8 KiB window, but together
		// they exhaust the 8 KiB the connection has.
		int window = 8 * 1024;
		collectOnAccept = false;
		handshake(QuicConnectionSettings.create(), QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(window))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(window))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(window))
			.withInitialMaxStreamDataUni(MemSize.of(window))
			.build());

		QuicStream first = openClientStream();
		Promise<Void> firstWritten = first.writer().accept(bytes(6 * 1024, 9));
		wire.pump();
		assertTrue("6 KiB is below both windows", firstWritten.isResult());
		assertEquals(0, clientManager.timesBlockedByConnectionLimit());

		QuicStream second = openClientStream();
		Promise<Void> secondWritten = second.writer().accept(bytes(6 * 1024, 10));
		wire.pump();

		assertFalse("only 2 KiB of the connection window is left", secondWritten.isComplete());
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());
		assertEquals("the second stream is 2 KiB into its own 8 KiB window, so that limit never bound", 0,
			clientManager.timesBlockedByStreamLimit());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());

		// FR-027 again, connection-wide this time: a second writer at the same limit is the same episode.
		QuicStream third = openClientStream();
		Promise<Void> thirdWritten = third.writer().accept(bytes(1024, 11));
		wire.pump();
		assertFalse(thirdWritten.isComplete());
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());

		second.reset(APP_ERROR_CODE);
		third.reset(APP_ERROR_CODE);
		driveUntil(() -> secondWritten.isComplete() && thirdWritten.isComplete());
	}

	@Test
	public void anOpenHeldByTheStreamCountLimitMovesTimesBlockedByStreamCountLimit()
		throws MalformedDataException {
		handshake(QuicConnectionSettings.create(), QuicConnectionSettings.builder()
			// The peer permits no bidirectional stream at all, so the very first open is withheld (FR-029).
			.withInitialMaxStreamsBidi(0)
			.build());

		Promise<QuicStream> first = clientManager.openBidirectional();
		wire.pump();

		assertFalse("no credit, so the open is withheld rather than failed", first.isComplete());
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());
		assertEquals(0, clientManager.streamsOpenedLocally());
		assertEquals("a stream-count limit is neither of the two data limits", 0,
			clientManager.timesBlockedByConnectionLimit());
		assertEquals(0, clientManager.timesBlockedByStreamLimit());

		// FR-028: several opens waiting on one limit value are one blocking episode.
		Promise<QuicStream> second = clientManager.openBidirectional();
		wire.pump();
		assertFalse(second.isComplete());
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());

		// A unidirectional open, which the peer does permit, is unaffected by the bidirectional block.
		Promise<QuicStream> uni = clientManager.openUnidirectional();
		assertTrue(uni.isResult());
		assertEquals(1, clientManager.streamsOpenedLocally());
		assertEquals(1, clientManager.timesBlockedByStreamCountLimit());
	}

	@Test
	public void anOpenWithheldOnlyBecauseTheHandshakeHasNotFinishedIsNotCountedAsBlocked()
		throws MalformedDataException {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.startClient(QuicConnectionSettings.create());

		// FR-042: before establishment there is no limit value a STREAMS_BLOCKED could truthfully carry, so
		// nothing is announced and nothing is counted.
		Promise<QuicStream> pending = clientManager.openBidirectional();
		assertFalse(pending.isComplete());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());

		wire.acceptServer(QuicConnectionSettings.create());
		wire.pump();

		assertTrue("and it completes once the handshake supplies the limits", pending.isResult());
		assertEquals(1, clientManager.streamsOpenedLocally());
		assertEquals(0, clientManager.timesBlockedByStreamCountLimit());
	}

	// ---------------------------------------------------------------- toString stays a summary

	@Test
	public void toStringSummarisesTheCountersWithoutAnyPayload() throws MalformedDataException {
		handshake();
		QuicStream stream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(bytes(128, 10)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		String printed = clientManager.toString();

		assertTrue(printed, printed.contains("QuicStreamManager{"));
		assertTrue(printed, printed.contains("sent=128"));

		discard(serverReads.get(0));
	}
}
