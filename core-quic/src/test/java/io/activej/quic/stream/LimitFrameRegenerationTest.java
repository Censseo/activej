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
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import io.activej.quic.codec.*;
import io.activej.quic.connection.LossDetector;
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
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T069 — FR-030 and research R-09: a lost limit or blocked frame is <b>never replayed</b>. RFC 9000
 * §13.3 says so in as many words, and the reason is that both families carry a number that has a
 * current value: replaying a stale one tells the peer less than the truth, and — for a grant — tells it
 * something it may already have superseded.
 * <p>
 * So the repair is a decision taken again from live state, and it has two possible answers:
 * <ul>
 *   <li><b>send the current value</b>, when nothing newer has been decided since. This is not optional
 *       housekeeping: a lost {@code MAX_STREAM_DATA} that is merely dropped deadlocks the stream. The
 *       peer stops at the limit it knows, so it delivers nothing further for the reader to consume, so
 *       no later grant is ever triggered to repair the first one;</li>
 *   <li><b>send nothing at all</b>, when a later frame already carried an equal or higher value. The
 *       peer either has that better information or will learn of its loss on its own, and a second
 *       frame conveying it would be pure overhead.</li>
 * </ul>
 * Blocked announcements are the mirror image: their tracker is rewound so the ordinary blocked check
 * speaks again, which re-announces if the writer is still held and stays silent if it is not.
 *
 * <h2>How the loss is staged</h2>
 * The feature 03 recipe throughout (checklist remediation R-2): blackhole exactly the packet under test
 * and then deliver {@link LossDetector#PACKET_THRESHOLD} + 1 further acknowledged {@code PING}s, so the
 * declaration itself runs. The clock never moves, so a probe timeout cannot stand in for the path being
 * tested.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-13.3">RFC 9000 §13.3 — Retransmission of Information</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 */
public final class LimitFrameRegenerationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Small enough that a few kilobytes cross the half-window threshold twice. */
	private static final int WINDOW = 4096;

	/** A quarter of the window, so consumption lands exactly on the grant thresholds. */
	private static final int QUARTER = WINDOW / 4;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	/** Whether the server's listener attaches a reader — the thing that drives every credit grant. */
	private boolean serverReads = true;

	// What the client was told about the limits the server advertises (RFC 9000 §19.9-§19.11).
	private final List<Long> maxDataAtClient = new ArrayList<>();
	private final List<Long> maxStreamDataAtClient = new ArrayList<>();
	private final List<Long> maxStreamsAtClient = new ArrayList<>();

	// ... and what the server was told about the limits holding the client (RFC 9000 §19.12-§19.14).
	private final List<Long> dataBlockedAtServer = new ArrayList<>();
	private final List<Long> streamDataBlockedAtServer = new ArrayList<>();
	private final List<Long> streamsBlockedAtServer = new ArrayList<>();

	private final List<Promise<ByteBuf>> serverCollected = new ArrayList<>();

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		if (loop != null) {
			loop.tickUntilQuiet();
			loop.close();
		}
	}

	// ---------------------------------------------------------------- fixture

	/** Records the limit frames one side is handed and passes everything straight on. */
	private final class Recorder implements QuicFrameHandler {
		private final QuicFrameHandler delegate;
		private final boolean atClient;

		private Recorder(QuicFrameHandler delegate, boolean atClient) {
			this.delegate = delegate;
			this.atClient = atClient;
		}

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			if (atClient) {
				if (frame instanceof MaxDataFrame f) maxDataAtClient.add(f.maximum);
				if (frame instanceof MaxStreamDataFrame f) maxStreamDataAtClient.add(f.maximum);
				if (frame instanceof MaxStreamsFrame f) maxStreamsAtClient.add(f.maximum);
			} else {
				if (frame instanceof DataBlockedFrame f) dataBlockedAtServer.add(f.limit);
				if (frame instanceof StreamDataBlockedFrame f) streamDataBlockedAtServer.add(f.limit);
				if (frame instanceof StreamsBlockedFrame f) streamsBlockedAtServer.add(f.limit);
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

	private void start(QuicConnectionSettings settings) throws MalformedDataException {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> new Recorder(
			clientManager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build(), true));
		wire.withServerFrameHandlerFactory(connection -> new Recorder(
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					if (serverReads) serverCollected.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build(), false));
		wire.handshake(settings);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		maxDataAtClient.clear();
		maxStreamDataAtClient.clear();
		maxStreamsAtClient.clear();
		dataBlockedAtServer.clear();
		streamDataBlockedAtServer.clear();
		streamsBlockedAtServer.clear();
	}

	/** Windows small enough that four quarter-window writes cross the FR-025 threshold exactly twice. */
	private static QuicConnectionSettings smallDataWindows() {
		return QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(WINDOW))
			.build();
	}

	private static QuicConnectionSettings smallStreamCounts(long uniStreams) {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsUni(uniStreams)
			.build();
	}

	private void pump() {
		wire.pump();
		loop.tick();
		wire.pump();
	}

	private static ByteBuf bytes(int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	private QuicStream open(boolean bidirectional) {
		Promise<QuicStream> opened = bidirectional
			? clientManager.openBidirectional()
			: clientManager.openUnidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		assertTrue(String.valueOf(opened.getException()), opened.isResult());
		return opened.getResult();
	}

	/**
	 * Makes {@code sender}'s most recently blackholed packet <em>declared</em> lost, by putting
	 * {@link LossDetector#PACKET_THRESHOLD} + 1 acknowledged packets behind it (RFC 9002 §6.1.1).
	 * <p>
	 * {@code PING} rather than anything the stream layer sends, so that only the blackholed packet can
	 * account for whatever the peer sees afterwards.
	 */
	private void detectLossAt(QuicConnection sender) {
		long lostBefore = sender.packetsLost();
		for (int i = 0; i <= LossDetector.PACKET_THRESHOLD; i++) {
			try {
				sender.enqueueFrame(PingFrame.INSTANCE);
			} catch (QuicTransportException e) {
				throw new AssertionError(e);
			}
			sender.requestSend();
			pump();
		}
		assertTrue("the packet-number threshold never declared the blackholed packet lost",
			sender.packetsLost() > lostBefore);
	}

	// ---------------------------------------------------------------- MAX_DATA / MAX_STREAM_DATA

	/**
	 * Four quarter-window writes, read as they arrive. The server grants twice, at
	 * {@code 2·QUARTER + WINDOW} and at {@code 4·QUARTER + WINDOW}; {@code blackholeSecond} decides which
	 * of the two never reaches the wire.
	 *
	 * @return the value the surviving state says the limit now is
	 */
	private long twoGrantsWithOneLost(boolean blackholeSecond) throws MalformedDataException {
		start(smallDataWindows());
		QuicStream stream = open(true);
		ChannelConsumer<ByteBuf> writer = stream.writer();

		wire.serverWire().blackhole(!blackholeSecond);
		writer.accept(bytes(QUARTER));
		writer.accept(bytes(QUARTER));
		pump();
		wire.serverWire().blackhole(blackholeSecond);
		writer.accept(bytes(QUARTER));
		writer.accept(bytes(QUARTER));
		pump();
		wire.serverWire().blackhole(false);
		return 4L * QUARTER + WINDOW;
	}

	@Test
	public void aLostMaxStreamDataIsSentAgainCarryingTheLimitCurrentlyInForce() throws Exception {
		long current = twoGrantsWithOneLost(true);
		assertEquals("only the first grant reached the client", List.of(2L * QUARTER + WINDOW),
			maxStreamDataAtClient);

		detectLossAt(wire.server());

		assertEquals("the lost grant must come back carrying the limit in force now",
			List.of(2L * QUARTER + WINDOW, current), maxStreamDataAtClient);
	}

	@Test
	public void aLostMaxDataIsSentAgainCarryingTheLimitCurrentlyInForce() throws Exception {
		long current = twoGrantsWithOneLost(true);
		assertEquals(List.of(2L * QUARTER + WINDOW), maxDataAtClient);

		detectLossAt(wire.server());

		assertEquals(List.of(2L * QUARTER + WINDOW, current), maxDataAtClient);
	}

	@Test
	public void aStaleGrantIsNotSentAgainWhenALaterOneAlreadyCarriedAHigherValue() throws Exception {
		long current = twoGrantsWithOneLost(false);
		// The *first* grant was blackholed and the second, higher one arrived while it was still in
		// flight. Everything the first would have said, the second already said better.
		assertEquals(List.of(current), maxStreamDataAtClient);
		assertEquals(List.of(current), maxDataAtClient);

		detectLossAt(wire.server());

		assertEquals("a superseded grant must not be sent a second time", List.of(current), maxStreamDataAtClient);
		assertEquals(List.of(current), maxDataAtClient);
	}

	// ---------------------------------------------------------------- MAX_STREAMS

	/**
	 * Four unidirectional streams, each written whole and read whole, so all four are released. The
	 * server grants concurrency credit twice, after the second release and after the fourth.
	 */
	private long twoStreamCountGrantsWithOneLost(boolean blackholeSecond) throws MalformedDataException {
		start(smallStreamCounts(4));

		wire.serverWire().blackhole(!blackholeSecond);
		sendWholeUnidirectionalStream();
		sendWholeUnidirectionalStream();
		pump();
		wire.serverWire().blackhole(blackholeSecond);
		sendWholeUnidirectionalStream();
		sendWholeUnidirectionalStream();
		pump();
		wire.serverWire().blackhole(false);
		// The second grant restores the configured window above the four released streams.
		return 4 + 4;
	}

	private void sendWholeUnidirectionalStream() {
		ChannelConsumer<ByteBuf> writer = open(false).writer();
		writer.accept(bytes(16));
		writer.acceptEndOfStream();
		pump();
	}

	@Test
	public void aLostMaxStreamsIsSentAgainCarryingTheCountCurrentlyInForce() throws Exception {
		long current = twoStreamCountGrantsWithOneLost(true);
		assertEquals("only the first grant reached the client", List.of(6L), maxStreamsAtClient);

		detectLossAt(wire.server());

		assertEquals(List.of(6L, current), maxStreamsAtClient);
	}

	@Test
	public void aStaleMaxStreamsIsNotSentAgainWhenALaterOneAlreadyCarriedAHigherCount() throws Exception {
		long current = twoStreamCountGrantsWithOneLost(false);
		assertEquals(List.of(current), maxStreamsAtClient);

		detectLossAt(wire.server());

		assertEquals("a superseded stream-count grant must not be sent a second time",
			List.of(current), maxStreamsAtClient);
	}

	// ---------------------------------------------------------------- DATA_BLOCKED / STREAM_DATA_BLOCKED

	/**
	 * Fills both windows exactly and then writes one byte more, so the announcement is the <b>only</b>
	 * thing in its packet — and blackholes it. Nothing on the server reads, so no grant can arrive to
	 * make the announcement moot.
	 */
	private void blockedAnnouncementLost() throws MalformedDataException {
		serverReads = false;
		start(smallDataWindows());
		ChannelConsumer<ByteBuf> writer = open(true).writer();
		writer.accept(bytes(WINDOW));
		pump();
		assertTrue("filling the window exactly is not being blocked by it", streamDataBlockedAtServer.isEmpty());

		wire.clientWire().blackhole(true);
		writer.accept(bytes(1));
		wire.clientWire().blackhole(false);
		pump();
		assertTrue("the announcement was blackholed", streamDataBlockedAtServer.isEmpty());
		assertTrue(dataBlockedAtServer.isEmpty());
	}

	@Test
	public void aLostStreamDataBlockedIsAnnouncedAgainWhileTheWriterIsStillHeld() throws Exception {
		blockedAnnouncementLost();

		detectLossAt(wire.client());

		// Regenerated from the limit in force, which has not moved — a blocked writer produces nothing
		// for the reader to consume, so nothing can have raised it (RFC 9000 §19.13, FR-027).
		assertEquals(List.of((long) WINDOW), streamDataBlockedAtServer);
	}

	@Test
	public void aLostDataBlockedIsAnnouncedAgainWhileTheConnectionIsStillHeld() throws Exception {
		blockedAnnouncementLost();

		detectLossAt(wire.client());

		assertEquals(List.of((long) WINDOW), dataBlockedAtServer);
	}

	// ---------------------------------------------------------------- STREAMS_BLOCKED

	@Test
	public void aLostStreamsBlockedIsAnnouncedAgainWhileTheOpenIsStillWithheld() throws Exception {
		start(smallStreamCounts(2));
		open(false);
		open(false);
		pump();

		// The third open has no credit, so it is withheld and the peer is told which count is holding it.
		wire.clientWire().blackhole(true);
		Promise<QuicStream> withheld = clientManager.openUnidirectional();
		wire.clientWire().blackhole(false);
		pump();
		assertFalse("an open beyond the peer's count is withheld, not failed", withheld.isComplete());
		assertTrue("the announcement was blackholed", streamsBlockedAtServer.isEmpty());

		detectLossAt(wire.client());

		assertEquals(List.of(2L), streamsBlockedAtServer);
		assertFalse(withheld.isComplete());
	}
}
