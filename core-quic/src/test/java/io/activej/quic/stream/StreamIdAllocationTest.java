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
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T095 — FR-002: locally-initiated stream identifiers are allocated <b>in ascending order per type,
 * without gaps and without reuse</b>, and the four types of RFC 9000 §2.1 Table 1 are allocated
 * independently of one another.
 *
 * <h2>Why an open/close cycle rather than a straight run of opens</h2>
 * A straight run of opens proves ascending order and nothing else. The property that actually costs
 * something to get right is what happens <em>after</em> a stream is released: releasing frees
 * <b>concurrency</b>, never an <b>identifier</b> (RFC 9000 §2.1, §4.6). An implementation that reused
 * the ordinal of a closed stream would look correct in every single-stream test and would then hand a
 * peer a stream id it has already seen — which the peer is entitled to treat as a
 * {@code STREAM_STATE_ERROR}, or worse, to confuse with the earlier stream's data. So every test here
 * opens a batch, drives some of it to full release, and asserts the <em>next</em> batch continues
 * above the high-water mark rather than filling the holes.
 *
 * <h2>Why both roles</h2>
 * The identifier's low bit is the initiator, so a client manager and a server manager exercise
 * disjoint halves of the identifier space: {@code 0, 4, 8, …} and {@code 2, 6, 10, …} for the client,
 * {@code 1, 5, 9, …} and {@code 3, 7, 11, …} for the server. A rule asserted from one side only is a
 * rule asserted for half the space.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 */
public final class StreamIdAllocationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Generous, so that no test here is ever measuring the stream-count limit by accident. */
	private static final long STREAM_COUNT_WINDOW = 64;

	private static final int BATCH = 5;
	private static final int MAX_DRIVE_ROUNDS = 400;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	/** Every buffer a peer collected, recycled in {@link #tearDown} so no path can leak one. */
	private final List<Promise<ByteBuf>> collected = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		collected.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(this::finishPeerStream)
				.build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(this::finishPeerStream)
				.build());
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(STREAM_COUNT_WINDOW)
			.withInitialMaxStreamsUni(STREAM_COUNT_WINDOW)
			.build();
		wire.startClient(settings);
		wire.acceptServer(settings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
		for (Promise<ByteBuf> promise : collected) {
			if (promise.isResult()) promise.getResult().recycle();
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Ends both halves the receiving endpoint owns, so that a stream the other end has finished with is
	 * released there rather than left half-open — which is what makes "some are closed" reproducible.
	 */
	private void finishPeerStream(QuicStream stream) {
		collected.add(stream.reader().toCollector(ByteBufs.collector()));
		if (stream.hasSendPart()) {
			stream.writer().acceptEndOfStream();
		}
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

	private QuicStream open(QuicStreamManager manager, StreamDirection direction) {
		Promise<QuicStream> opened = direction == StreamDirection.BIDIRECTIONAL
			? manager.openBidirectional()
			: manager.openUnidirectional();
		assertTrue("the connection is established and credit is generous, so the open resolves at once",
			opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	private List<Long> openBatch(QuicStreamManager manager, StreamDirection direction, int count) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(open(manager, direction).id());
		}
		return ids;
	}

	/** Runs one locally-opened stream to full release on the endpoint that opened it (FR-006). */
	private void releaseFully(QuicStreamManager manager, long streamId) {
		QuicStream stream = manager.streamOf(streamId);
		assertNotNull("stream " + streamId + " is not open", stream);
		if (stream.hasReceivePart()) {
			collected.add(stream.reader().toCollector(ByteBufs.collector()));
		}
		stream.writer().acceptEndOfStream();
		driveUntil(() -> manager.streamOf(streamId) == null);
		assertNull("the stream must leave the manager's map to count as released (FR-006)",
			manager.streamOf(streamId));
	}

	/**
	 * The whole of FR-002 as one assertion over a run of identifiers: the right type bits, ascending,
	 * consecutive per type, and never repeated.
	 */
	private static void assertAllocatedInOrder(
		List<Long> ids, long firstExpectedOrdinal, boolean clientInitiated, boolean bidirectional
	) {
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < ids.size(); i++) {
			long id = ids.get(i);
			assertEquals("RFC 9000 §2.1: the ordinal must be the next one of this type, with no gap",
				StreamIds.of(firstExpectedOrdinal + i, clientInitiated, bidirectional), id);
			assertEquals("bit 0 of the identifier is the initiator", clientInitiated,
				StreamIds.isClientInitiated(id));
			assertEquals("bit 1 of the identifier is the directionality", bidirectional,
				StreamIds.isBidirectional(id));
			assertTrue("identifier " + id + " was allocated twice", seen.add(id));
			if (i > 0) {
				assertTrue("identifiers must ascend", id > ids.get(i - 1));
			}
		}
	}

	/**
	 * Opens a batch, releases every second stream of it, opens another batch, and asserts the second
	 * batch continued above the first rather than filling the holes the releases left.
	 */
	private void assertOpenCloseCycleNeverReusesAnOrdinal(
		QuicStreamManager manager, StreamDirection direction, boolean clientInitiated
	) {
		boolean bidirectional = direction == StreamDirection.BIDIRECTIONAL;

		List<Long> first = openBatch(manager, direction, BATCH);
		assertAllocatedInOrder(first, 0, clientInitiated, bidirectional);

		List<Long> released = new ArrayList<>();
		for (int i = 0; i < first.size(); i += 2) {
			releaseFully(manager, first.get(i));
			released.add(first.get(i));
		}

		List<Long> second = openBatch(manager, direction, BATCH);
		// The ordinals continue from BATCH, not from the holes: releasing frees concurrency, never an
		// identifier. This single expectation is what a reusing implementation fails.
		assertAllocatedInOrder(second, BATCH, clientInitiated, bidirectional);
		for (long id : second) {
			assertTrue("a released identifier must never be handed out again: " + id,
				!released.contains(id));
			assertTrue("the second batch must continue above the first", id > first.get(first.size() - 1));
		}

		// And once more, after releasing part of the second batch too — the property has to survive
		// repetition, since the first cycle could be passed by an implementation that only ever grows.
		for (int i = 1; i < second.size(); i += 2) {
			releaseFully(manager, second.get(i));
		}
		List<Long> third = openBatch(manager, direction, 2);
		assertAllocatedInOrder(third, 2L * BATCH, clientInitiated, bidirectional);
	}

	// ---------------------------------------------------------------- the four types (FR-002)

	@Test
	public void clientInitiatedBidirectionalIdentifiersAscendWithoutGapOrReuse() {
		assertOpenCloseCycleNeverReusesAnOrdinal(clientManager, StreamDirection.BIDIRECTIONAL, true);
	}

	@Test
	public void clientInitiatedUnidirectionalIdentifiersAscendWithoutGapOrReuse() {
		assertOpenCloseCycleNeverReusesAnOrdinal(clientManager, StreamDirection.UNIDIRECTIONAL, true);
	}

	@Test
	public void serverInitiatedBidirectionalIdentifiersAscendWithoutGapOrReuse() {
		assertOpenCloseCycleNeverReusesAnOrdinal(serverManager, StreamDirection.BIDIRECTIONAL, false);
	}

	@Test
	public void serverInitiatedUnidirectionalIdentifiersAscendWithoutGapOrReuse() {
		assertOpenCloseCycleNeverReusesAnOrdinal(serverManager, StreamDirection.UNIDIRECTIONAL, false);
	}

	// ---------------------------------------------------------------- independence of the four counters

	@Test
	public void theTwoDirectionsOfOneRoleAreCountedIndependently() {
		// Interleaved deliberately: a shared counter would show up as 0, 6, 8, 14 rather than as two
		// runs of consecutive ordinals.
		List<Long> bidi = new ArrayList<>();
		List<Long> uni = new ArrayList<>();
		for (int i = 0; i < BATCH; i++) {
			bidi.add(open(clientManager, StreamDirection.BIDIRECTIONAL).id());
			uni.add(open(clientManager, StreamDirection.UNIDIRECTIONAL).id());
		}

		assertEquals(List.of(0L, 4L, 8L, 12L, 16L), bidi);
		assertEquals(List.of(2L, 6L, 10L, 14L, 18L), uni);
		assertAllocatedInOrder(bidi, 0, true, true);
		assertAllocatedInOrder(uni, 0, true, false);
	}

	@Test
	public void theTwoRolesAllocateFromDisjointHalvesOfTheIdentifierSpace() {
		List<Long> clientBidi = openBatch(clientManager, StreamDirection.BIDIRECTIONAL, BATCH);
		List<Long> serverBidi = openBatch(serverManager, StreamDirection.BIDIRECTIONAL, BATCH);
		List<Long> clientUni = openBatch(clientManager, StreamDirection.UNIDIRECTIONAL, BATCH);
		List<Long> serverUni = openBatch(serverManager, StreamDirection.UNIDIRECTIONAL, BATCH);

		assertEquals(List.of(0L, 4L, 8L, 12L, 16L), clientBidi);
		assertEquals(List.of(1L, 5L, 9L, 13L, 17L), serverBidi);
		assertEquals(List.of(2L, 6L, 10L, 14L, 18L), clientUni);
		assertEquals(List.of(3L, 7L, 11L, 15L, 19L), serverUni);

		// The four runs together are 20 distinct identifiers — no type can collide with another.
		Set<Long> all = new HashSet<>();
		all.addAll(clientBidi);
		all.addAll(serverBidi);
		all.addAll(clientUni);
		all.addAll(serverUni);
		assertEquals(4 * BATCH, all.size());
	}
}
