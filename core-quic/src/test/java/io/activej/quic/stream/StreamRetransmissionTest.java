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
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.promise.Promise;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
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
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T067 — FR-017: a {@code STREAM} frame declared lost goes back on the wire <b>at its original
 * offsets</b>, ahead of any data queued for the same stream afterwards, and it does so whether the
 * sending half is still open or has been closed normally after its {@code FIN}.
 *
 * <h2>Why the loss is staged by hand</h2>
 * A seeded lossy network almost never produces a <i>detected</i> loss during ordinary operation, and a
 * test that merely switches one on passes vacuously (feature 03's recipe, checklist remediation R-2,
 * research R-10). Threshold loss needs a <em>later</em> packet in the same number space to be
 * acknowledged (RFC 9002 §6.1.1), so every case here blackholes exactly one 1-RTT packet and then
 * delivers {@link LossDetector#PACKET_THRESHOLD} + 1 more ack-eliciting ones, which is what makes the
 * declaration itself run — on every seed, on every machine.
 * <p>
 * The later packets are bare {@code PING}s rather than more stream data: a probe frame the transport
 * owns cannot itself reach {@link QuicStreamManager#onFrameLost}, so nothing but the blackholed packet
 * can account for what the server sees.
 *
 * <h2>The Data Sent state is not the end of retransmission (RFC 9000 §3.1)</h2>
 * {@link #dataLostAfterTheWriterWasClosedNormallyIsStillResent()} and
 * {@link #aLostFinIsStillResentAfterTheWriterWasClosedNormally()} are the cases that distinguish "this
 * writer accepts no more data" from "this writer has given up on the data it already wrote". A part
 * that wrote a clean {@code FIN} and closed sits in Data Sent until <em>every</em> frame is
 * acknowledged, and a frame lost in that window must be resent; only an abort (Reset Sent) releases
 * one instead (FR-018).
 *
 * <h2>Time never moves here</h2>
 * Deliberately: a probe timeout also re-queues unacknowledged data (RFC 9002 §6.2), so a test that let
 * one fire could not tell threshold retransmission from probe retransmission. With the clock frozen the
 * only recovery path open is the one under test, which is also why a dropped frame fails as a
 * transfer that never completes rather than as one that completes late.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-6.1">RFC 9002 §6.1 — Detecting Lost Packets</a>
 */
public final class StreamRetransmissionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Comfortably inside one {@code STREAM} frame, so one write is one frame is one packet. */
	private static final int CHUNK = 400;

	/** Several congestion windows' worth: enough that an appended retransmission would arrive last. */
	private static final int BACKLOG = 64 * 1024;

	/** Bound on the drive loop. A dropped frame must fail loudly and quickly, never hang the build. */
	private static final int MAX_DRIVE_ROUNDS = 200;

	/** What the server's frame handler saw arrive, in arrival order. */
	private record Arrival(long streamId, long offset, int length, boolean fin) {}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	private final List<Arrival> arrivals = new ArrayList<>();
	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		if (loop != null) {
			loop.tickUntilQuiet();
			loop.close();
		}
	}

	// ---------------------------------------------------------------- fixture

	/**
	 * Records every {@code STREAM} frame the server is handed and passes it straight on. The frame is
	 * borrowed, so only its scalar header is kept — never a byte of payload (SI-6).
	 */
	private final class RecordingHandler implements QuicFrameHandler {
		private final QuicFrameHandler delegate;

		private RecordingHandler(QuicFrameHandler delegate) {
			this.delegate = delegate;
		}

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
			throws QuicTransportException {
			if (frame instanceof StreamFrame streamFrame) {
				arrivals.add(new Arrival(streamFrame.streamId, streamFrame.offset,
					streamFrame.data.readRemaining(), streamFrame.fin));
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

	private void start() throws MalformedDataException {
		loop = new ManualEventloop();
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> new RecordingHandler(
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build()));
		wire.handshake(settings);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		// Whatever the handshake left in flight is not part of any scenario below.
		arrivals.clear();
	}

	// ---------------------------------------------------------------- helpers

	/** A pattern whose every byte depends on its offset, so a reorder or a gap cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] source, int from, int to) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, to - from));
		buf.put(source, from, to - from);
		return buf;
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the read never completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		assertTrue(String.valueOf(opened.getException()), opened.isResult());
		return opened.getResult();
	}

	/** Delivers everything queued both ways and lets any posted continuation run. The clock stays put. */
	private void pump() {
		wire.pump();
		loop.tick();
		wire.pump();
	}

	private void driveUntil(BooleanSupplier done, String what) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			pump();
			if (done.getAsBoolean()) return;
		}
		fail(what + " — nothing further is in flight and the clock is frozen, so the lost frame was " +
			 "either dropped or queued behind a backlog, rather than resent ahead of it");
	}

	/**
	 * Blackholes whatever {@code sender} flushes while {@code action} runs, then makes the loss
	 * <em>detected</em>: {@link LossDetector#PACKET_THRESHOLD} + 1 further ack-eliciting packets, each
	 * delivered and acknowledged, so the blackholed packet number falls the threshold behind the largest
	 * acknowledged one (RFC 9002 §6.1.1).
	 */
	private void blackholeAndDetectLoss(QuicWirePair.Wire senderWire, QuicConnection sender, Runnable action) {
		long lostBefore = sender.packetsLost();
		senderWire.blackhole(true);
		action.run();
		senderWire.blackhole(false);

		for (int i = 0; i <= LossDetector.PACKET_THRESHOLD; i++) {
			// A PING is ack-eliciting and transport-owned, so it advances the packet number without ever
			// reaching the stream layer's own loss handling.
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

	private List<Arrival> arrivalsOn(long streamId) {
		List<Arrival> selected = new ArrayList<>();
		for (Arrival arrival : arrivals) {
			if (arrival.streamId() == streamId) selected.add(arrival);
		}
		return selected;
	}

	private int indexOfArrivalAt(long streamId, long offset) {
		for (int i = 0; i < arrivals.size(); i++) {
			Arrival arrival = arrivals.get(i);
			if (arrival.streamId() == streamId && arrival.offset() == offset) return i;
		}
		return -1;
	}

	// ---------------------------------------------------------------- FR-017, the live send part

	@Test
	public void aLostStreamFrameComesBackAtItsOriginalOffsetsAheadOfLaterData() throws Exception {
		start();
		byte[] payload = pattern(6 * CHUNK, 1);
		QuicStream stream = openClientStream();
		ChannelConsumer<ByteBuf> writer = stream.writer();
		long streamId = stream.id();

		// A *middle* frame is the one that never reaches the wire, so the ordering assertion below has
		// something to say: the hole it leaves cannot be filled by anything but its retransmission.
		writer.accept(buf(payload, 0, CHUNK));
		pump();
		blackholeAndDetectLoss(wire.clientWire(), wire.client(), () -> {
			writer.accept(buf(payload, CHUNK, 2 * CHUNK));
			// Delivered, and undeliverable to the application: they sit past the hole.
			wire.clientWire().blackhole(false);
			writer.accept(buf(payload, 2 * CHUNK, 3 * CHUNK));
			writer.accept(buf(payload, 3 * CHUNK, 4 * CHUNK));
			pump();
		});
		// The loss is declared, so the retransmission is already queued. Anything written from here on is
		// *later* data on the same stream, and must not overtake it (FR-017).
		writer.accept(buf(payload, 4 * CHUNK, 5 * CHUNK));
		writer.accept(buf(payload, 5 * CHUNK, 6 * CHUNK));
		Promise<Void> finished = writer.acceptEndOfStream();
		driveUntil(() -> finished.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the stream never completed");

		// Byte-for-byte, which is the only assertion that proves the retransmission carried the *same*
		// offsets rather than merely the same number of bytes.
		assertArrayEquals(payload, drain(serverReads.get(0)));

		int resent = indexOfArrivalAt(streamId, CHUNK);
		assertTrue("the lost frame never came back at offset " + CHUNK, resent >= 0);
		assertEquals("it came back at its original offsets, neither merged nor re-fragmented",
			CHUNK, arrivals.get(resent).length());
		assertTrue("the retransmission arrived after the data that overtook it while it was missing",
			resent > indexOfArrivalAt(streamId, 3L * CHUNK));
		for (int i = 4; i < 6; i++) {
			assertTrue("the retransmission must precede data queued after its loss was declared",
				resent < indexOfArrivalAt(streamId, (long) i * CHUNK));
		}
		// Exactly one arrival per offset: a frame resent when it was not lost is a wasted window.
		assertEquals(7, arrivalsOn(streamId).size());
	}

	/**
	 * FR-017's "ahead of new data", in the case that makes it matter: a backlog already queued behind the
	 * lost frame when its loss is declared.
	 * <p>
	 * The stream layer bounds itself by {@code maxOutstandingStreamBytes} — hundreds of kilobytes — while
	 * the connection sends at the congestion window, so an application write leaves far more queued than
	 * one round trip can carry. A retransmission appended to that queue waits it out, and every byte the
	 * receiver takes in the meantime is a byte it must buffer and cannot deliver, because the gap this
	 * frame fills is in front of all of them.
	 */
	@Test
	public void aRetransmissionOvertakesTheBacklogAlreadyQueuedBehindIt() throws Exception {
		start();
		byte[] payload = pattern(CHUNK + BACKLOG, 5);
		QuicStream stream = openClientStream();
		ChannelConsumer<ByteBuf> writer = stream.writer();
		long streamId = stream.id();

		wire.clientWire().blackhole(true);
		writer.accept(buf(payload, 0, CHUNK));
		wire.clientWire().blackhole(false);
		// Many congestion windows' worth, all queued in one write and all behind the lost frame.
		Promise<Void> backlogWritten = writer.accept(buf(payload, CHUNK, payload.length));
		assertTrue("the whole backlog fits the outstanding budget and the send queue",
			backlogWritten.isComplete());
		Promise<Void> finished = writer.acceptEndOfStream();

		driveUntil(() -> finished.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the backlogged stream never completed");

		assertArrayEquals(payload, drain(serverReads.get(0)));
		int resent = indexOfArrivalAt(streamId, 0);
		int total = arrivalsOn(streamId).size();
		assertTrue("the lost frame never came back at offset 0", resent >= 0);
		assertTrue("the retransmission arrived " + resent + " frames into a stream of " + total +
				   ", so it was appended behind the backlog rather than sent ahead of it",
			resent < total / 2);
	}

	@Test
	public void aRetransmissionIsNotCountedAsNewlySentBytes() throws Exception {
		start();
		byte[] payload = pattern(5 * CHUNK, 2);
		QuicStream stream = openClientStream();
		ChannelConsumer<ByteBuf> writer = stream.writer();

		blackholeAndDetectLoss(wire.clientWire(), wire.client(),
			() -> writer.accept(buf(payload, 0, CHUNK)));
		for (int i = 1; i < 5; i++) {
			writer.accept(buf(payload, i * CHUNK, (i + 1) * CHUNK));
		}
		Promise<Void> finished = writer.acceptEndOfStream();
		driveUntil(() -> finished.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the stream never completed");

		assertArrayEquals(payload, drain(serverReads.get(0)));
		// FR-043: bytesSent counts bytes given an offset, and a resent frame is not new data. A counter
		// that double-counted here would report a throughput the connection never achieved.
		assertEquals(5 * CHUNK, clientManager.bytesSent());
	}

	// ---------------------------------------------------------------- RFC 9000 §3.1 Data Sent

	/**
	 * The regression this phase exists for: a send part that wrote everything, wrote its {@code FIN} and
	 * was then closed by its pipeline is in <b>Data Sent</b>, not in a state that may abandon data. Before
	 * the Phase 8 fix {@code SendPart.onFrameLost} released the frame whenever the writer was
	 * {@code closed}, which silently and permanently lost the bytes of every normally-finished stream that
	 * met a dropped packet.
	 */
	@Test
	public void dataLostAfterTheWriterWasClosedNormallyIsStillResent() throws Exception {
		start();
		byte[] payload = pattern(3 * CHUNK, 3);
		QuicStream stream = openClientStream();
		ChannelConsumer<ByteBuf> writer = stream.writer();

		// The first chunk is blackholed; the rest of the stream, its FIN and its close all succeed, so by
		// the time the loss is declared the writer accepts nothing more.
		wire.clientWire().blackhole(true);
		writer.accept(buf(payload, 0, CHUNK));
		wire.clientWire().blackhole(false);
		writer.accept(buf(payload, CHUNK, 2 * CHUNK));
		writer.accept(buf(payload, 2 * CHUNK, 3 * CHUNK));
		Promise<Void> finished = writer.acceptEndOfStream();
		writer.close();
		pump();

		assertTrue("every write reached the transport", finished.isComplete());
		assertEquals(SendState.DATA_SENT, stream.sendState());

		// A second stream supplies the later packets, since the first one has nothing left to say.
		detectLossWithSpareTraffic();

		driveUntil(() -> !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the closed-but-unacknowledged stream never delivered its lost first chunk");
		assertArrayEquals(payload, drain(serverReads.get(0)));
	}

	@Test
	public void aLostFinIsStillResentAfterTheWriterWasClosedNormally() throws Exception {
		start();
		byte[] payload = pattern(2 * CHUNK, 4);
		QuicStream stream = openClientStream();
		ChannelConsumer<ByteBuf> writer = stream.writer();

		writer.accept(buf(payload, 0, CHUNK));
		writer.accept(buf(payload, CHUNK, 2 * CHUNK));
		pump();
		// Only the end-of-data marker is blackholed. Every byte has arrived; what has not is the fact that
		// there are no more (RFC 9000 §4.5), so the reader can never finish without the FIN coming back.
		wire.clientWire().blackhole(true);
		Promise<Void> finished = writer.acceptEndOfStream();
		wire.clientWire().blackhole(false);
		writer.close();
		assertTrue(finished.isComplete());

		detectLossWithSpareTraffic();

		driveUntil(() -> !serverReads.isEmpty() && serverReads.get(0).isComplete(),
			"the lost FIN never came back, so the stream has no final size");
		assertArrayEquals(payload, drain(serverReads.get(0)));
		assertEquals(ReceiveState.DATA_READ, serverStreams.get(0).receiveState());
	}

	/**
	 * Makes the client's outstanding loss detectable when the stream under test has nothing more to send:
	 * {@link LossDetector#PACKET_THRESHOLD} + 1 acknowledged {@code PING}s.
	 */
	private void detectLossWithSpareTraffic() {
		long lostBefore = wire.client().packetsLost();
		for (int i = 0; i <= LossDetector.PACKET_THRESHOLD; i++) {
			try {
				wire.client().enqueueFrame(PingFrame.INSTANCE);
			} catch (QuicTransportException e) {
				throw new AssertionError(e);
			}
			wire.client().requestSend();
			pump();
		}
		assertTrue("the packet-number threshold never declared the blackholed packet lost",
			wire.client().packetsLost() > lostBefore);
	}
}
