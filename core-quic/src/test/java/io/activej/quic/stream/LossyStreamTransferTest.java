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
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.DatagramNetwork;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T072 / SC-003 — SC-001 and SC-002 both hold over a path that drops, reorders and duplicates: a
 * single-stream transfer arrives byte-identical, and several concurrent streams arrive whole and
 * uncontaminated, both without deadlock.
 *
 * <h2>Why the endpoint path and not the direct wire</h2>
 * This is the only test in the phase that runs the stack a deployment runs: a real
 * {@link QuicEndpoint} over an {@code IUdpSocket}, dispatching by connection ID, with the loss injected
 * <em>below</em> the socket. Nothing in production carries a hook for it — {@code LossyUdpSocket} plugs
 * into the existing three-method interface — so the code under test is byte-identical to the code that
 * runs over a real socket.
 *
 * <h2>What "without deadlock" means here, and how it is checked</h2>
 * Every stall this layer can produce looks the same from outside: a transfer that never finishes. A
 * credit grant lost and never regenerated, a withheld writer never retried, an outstanding budget
 * credited on loss and never returned — none of them is a wrong answer, so none of them can be caught
 * by comparing bytes. The drive loop therefore counts <b>rounds</b> and fails with a diagnostic, which
 * is both faster and more precise than a wall-clock timeout — and, unlike {@code @Test(timeout = …)},
 * compatible with {@link ManualEventloop}, which installs itself as <i>this</i> thread's reactor.
 * <p>
 * Time is entirely the test's: {@link QuicEndpointFixture#advance} moves one clock shared by the wire,
 * the connections' timers and the eventloop, so probe timeouts and loss detection happen where the test
 * puts them rather than where the machine's speed puts them.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-6">RFC 9002 §6 — Loss Detection</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.2">RFC 9000 §2.2 — Sending and Receiving Data</a>
 */
public final class LossyStreamTransferTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** SC-003's floor. Every datagram is drawn against it independently, handshake included. */
	private static final double DROP_RATE = 0.10;

	private static final double REORDER_RATE = 0.10;
	private static final double DUPLICATE_RATE = 0.10;

	/** One millisecond of path delay, so a reordered datagram has something to be reordered behind. */
	private static final long DELAY_MILLIS = 1;

	/**
	 * A hundred-odd datagrams per stream: comfortably inside the default windows, and large enough that
	 * the observed drop rate over the seed set is a statement about the configured rate rather than
	 * about which seed was picked.
	 */
	private static final int PAYLOAD_SIZE = 128 * 1024;

	private static final int CONCURRENT_STREAMS = 4;

	/** Bound on the drive loop: a deadlock fails in milliseconds with a diagnostic, never hangs. */
	private static final int MAX_DRIVE_ROUNDS = 4000;

	/** Clock step per round. Small enough to land inside a delayed ACK rather than on top of one. */
	private static final long STEP_MILLIS = 5;

	/**
	 * Distinct seeds, because one seed is one loss pattern: which packet is dropped decides which
	 * recovery path runs, and a stall that only a particular interleaving reaches would otherwise sit
	 * unnoticed until it reached a deployment.
	 */
	private static final long[] SEEDS = {1, 7, 42};

	/**
	 * Timeouts far beyond the virtual time this test can consume, so that a stall fails as a round-count
	 * assertion naming what is missing rather than as an idle connection with no explanation.
	 *
	 * <h2>{@code maxReceiveRangesPerStream} is left at its default, and that is the point</h2>
	 * This test once had to raise it to 204. {@link StreamReassembler} counted <b>buffered pieces</b>
	 * rather than discontiguous ranges, so one hole plus <i>n</i> in-order frames behind it counted as
	 * <i>n</i> ranges: the bound scaled with the <b>bandwidth-delay product</b> — how much arrives while
	 * a retransmission is in flight — instead of with how fragmented the peer's sending is, and at the
	 * default 32 a 10% loss rate killed the connection with {@code INTERNAL_ERROR} long before it had
	 * delivered its payload.
	 * <p>
	 * Feature 04's T078 corrected the counting to what FR-011 and clarification Q1 actually specify —
	 * gaps, adjacency-aware, mirroring {@code AckRanges} — so ordinary loss now costs one range per
	 * outstanding retransmission, and the default is ample. A future regression in that counting will
	 * show up here as a connection dying under loss, which is exactly where it should show up.
	 */
	private static QuicConnectionSettings settings() {
		return QuicConnectionSettings.builder()
			.withMaxIdleTimeout(Duration.ofMinutes(10))
			.withHandshakeTimeout(Duration.ofMinutes(5))
			.build();
	}

	/** A pattern whose every byte depends on its offset and its stream, so a mix-up cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + (i >>> 16) * 13 + seed * 101);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(source.length);
		buf.put(source);
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

	private static void driveUntil(
		QuicEndpointFixture fixture, List<QuicTransportException> failures, BooleanSupplier done, String what
	) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			fixture.pump();
			if (done.getAsBoolean()) return;
			// The clock is what releases a delayed ACK, a probe timeout and a loss-detection deadline —
			// the three things a lossy path depends on entirely.
			fixture.advance(STEP_MILLIS);
			if (done.getAsBoolean()) return;
		}
		fail(what + " within " + MAX_DRIVE_ROUNDS + " rounds — a retransmission, a credit grant or the " +
			 "retry of a withheld writer is missing. " + fixture.network() +
			 (failures.isEmpty() ? "" : " Transport errors raised by a stream layer: " + failures));
	}

	/**
	 * One client endpoint dialling one server endpoint across a seeded lossy path, with a stream layer on
	 * each — the wiring of {@code contracts/java-api.md}, and the one feature 05 will use.
	 */
	private static final class LossyPair implements AutoCloseable {
		final ManualEventloop loop = new ManualEventloop();
		final QuicEndpointFixture fixture;
		final List<Promise<ByteBuf>> serverReads = new ArrayList<>();
		final List<QuicStreamManager> serverManagers = new ArrayList<>();
		final List<QuicTransportException> handlerFailures = new ArrayList<>();

		QuicStreamManager client;

		LossyPair(long seed) {
			this.fixture = new QuicEndpointFixture(loop, seed);
			fixture.network()
				.withDropRate(DROP_RATE)
				.withReorderRate(REORDER_RATE)
				.withDuplicateRate(DUPLICATE_RATE)
				.withDelay(DELAY_MILLIS);

			QuicConnectionSettings settings = settings();
			fixture.server(settings, builder -> builder.withFrameHandlerFactory(connection -> {
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream ->
						serverReads.add(stream.reader().toCollector(ByteBufs.collector())))
					.build();
				serverManagers.add(manager);
				return reporting(manager);
			}));
			QuicEndpoint clientEndpoint = fixture.client(settings, builder -> builder
				.withFrameHandlerFactory(connection -> reporting(client =
					QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build())));

			Promise<QuicConnection> connecting = clientEndpoint.connectTo(
				QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			driveUntil(fixture, handlerFailures, connecting::isComplete,
				"the handshake never completed at seed " + seed);
			assertTrue(String.valueOf(connecting.getException()), connecting.isResult());
		}

		/**
		 * Records the transport error a handler raised before letting it close the connection. A
		 * CONNECTION_CLOSE carries no reason phrase on the wire (SI-6), so without this a connection
		 * that dies of a bound being exceeded is indistinguishable from one that merely stalled.
		 */
		private QuicFrameHandler reporting(QuicFrameHandler delegate) {
			return new QuicFrameHandler() {
				@Override
				public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame)
					throws QuicTransportException {
					try {
						delegate.onFrame(connection, level, frame);
					} catch (QuicTransportException e) {
						handlerFailures.add(e);
						throw e;
					}
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
			};
		}

		QuicStream open() {
			Promise<QuicStream> opened = client.openBidirectional();
			assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
			assertTrue(String.valueOf(opened.getException()), opened.isResult());
			return opened.getResult();
		}

		DatagramNetwork network() {
			return fixture.network();
		}

		@Override
		public void close() {
			fixture.close();
			loop.tickUntilQuiet();
			loop.close();
		}
	}

	/**
	 * The seeded path must genuinely have dropped things, or every assertion above it is vacuous — the
	 * failure mode the feature 03 recipe exists to rule out.
	 * <p>
	 * Per run this is only "something was dropped": one transfer is a few dozen datagrams, and at a 10%
	 * rate that is a sample small enough for an exact-rate assertion to be a statement about the seed
	 * rather than about the transport. The rate itself is asserted over the whole seed set by
	 * {@link Losses#assertTheRateWasReallyMet()}.
	 */
	private static final class Losses {
		private int sent;
		private int dropped;
		private int duplicated;

		void add(DatagramNetwork network) {
			assertTrue("the seeded path dropped nothing at all: " + network, network.droppedCount() > 0);
			sent += network.sentCount();
			dropped += network.droppedCount();
			duplicated += network.duplicatedCount();
		}

		/**
		 * Half the configured rate, over every seed together: the draw is a Bernoulli one, so an exact
		 * 10% would be an assertion about the seed. What is being ruled out is a fabric that quietly
		 * stopped dropping — the difference between a loss test and a lossless one.
		 */
		void assertTheRateWasReallyMet() {
			assertTrue("SC-003 asks for at least 10% loss; " + dropped + " of " + sent +
					   " datagrams were dropped", dropped * 20 >= sent);
			assertTrue("no datagram was ever duplicated, so per-space de-duplication went untested",
				duplicated > 0);
		}
	}

	// ---------------------------------------------------------------- SC-001 under loss

	@Test
	public void oneStreamArrivesByteIdenticalAcrossALossyPath() {
		Losses losses = new Losses();
		for (long seed : SEEDS) {
			try (LossyPair pair = new LossyPair(seed)) {
				byte[] payload = pattern(PAYLOAD_SIZE, 1);
				QuicStream stream = pair.open();
				Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(stream.writer());

				driveUntil(pair.fixture, pair.handlerFailures,
					() -> written.isComplete() && !pair.serverReads.isEmpty()
						  && pair.serverReads.get(0).isComplete(),
					"seed " + seed + ": the single-stream transfer never completed");

				assertTrue("seed " + seed + ": " + written.getException() + " " + pair.handlerFailures,
					written.isResult());
				assertArrayEquals("seed " + seed, payload, drain(pair.serverReads.get(0)));
				assertEquals(PAYLOAD_SIZE, pair.serverManagers.get(0).bytesDelivered());
				losses.add(pair.network());
			}
		}
		losses.assertTheRateWasReallyMet();
	}

	// ---------------------------------------------------------------- SC-002 under loss

	@Test
	public void concurrentStreamsArriveWholeAndUncontaminatedAcrossALossyPath() {
		Losses losses = new Losses();
		for (long seed : SEEDS) {
			try (LossyPair pair = new LossyPair(seed)) {
				List<byte[]> payloads = new ArrayList<>();
				List<Promise<Void>> writes = new ArrayList<>();
				List<Long> openedIds = new ArrayList<>();
				for (int i = 0; i < CONCURRENT_STREAMS; i++) {
					// Different sizes as well as different contents: a stream that received another's
					// bytes but the right count would still pass a length-only check.
					byte[] payload = pattern(PAYLOAD_SIZE / (i + 1), i + 2);
					payloads.add(payload);
					QuicStream stream = pair.open();
					openedIds.add(stream.id());
					writes.add(ChannelSuppliers.ofValue(buf(payload)).streamTo(stream.writer()));
				}

				driveUntil(pair.fixture, pair.handlerFailures,
					() -> writes.stream().allMatch(Promise::isComplete)
						  && pair.serverReads.size() == CONCURRENT_STREAMS
						  && pair.serverReads.stream().allMatch(Promise::isComplete),
					"seed " + seed + ": the concurrent transfers never completed");

				// Every stream whole, exactly once, and none of them carrying another's bytes. Matched as
				// a set because arrival order across streams is the lossy path's to decide.
				List<byte[]> unclaimed = new ArrayList<>(payloads);
				for (Promise<ByteBuf> read : pair.serverReads) {
					byte[] received = drain(read);
					assertTrue("seed " + seed + ": no unclaimed payload matches what arrived on a stream",
						unclaimed.removeIf(expected -> Arrays.equals(expected, received)));
				}
				assertTrue("seed " + seed + ": every payload arrived exactly once", unclaimed.isEmpty());
				assertEquals(CONCURRENT_STREAMS, new ArrayList<>(openedIds).stream().distinct().count());
				assertEquals(CONCURRENT_STREAMS, pair.serverManagers.get(0).streamsAcceptedFromPeer());
				losses.add(pair.network());
			}
		}
		losses.assertTheRateWasReallyMet();
	}
}
