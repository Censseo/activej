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

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * One {@link StreamCounter} per (initiator × directionality) — RFC 9000 §2.1 and §4.6. Pure
 * arithmetic over ordinals: no reactor, no promise, no buffer.
 * <p>
 * Two behaviours here carry the weight. <b>Allocation</b> (the two locally-initiated counters) must
 * be ascending, gapless and never reused — FR-002. <b>Arrival</b> (the two peer-initiated counters)
 * must implicitly open every lower-numbered stream of the same type — FR-003, RFC 9000 §2.1 — and
 * count all of them against the limit.
 */
public class StreamCounterTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// region allocation — FR-002, RFC 9000 §2.1

	@Test
	public void allocationIsAscendingGaplessAndNeverReused() {
		StreamCounter counter = StreamCounter.create(1000);
		List<Long> allocated = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			assertTrue(counter.canOpen());
			allocated.add(counter.allocate());
		}
		for (int i = 0; i < 1000; i++) {
			assertEquals(i, (long) allocated.get(i));
		}
		assertEquals(1000, counter.nextOrdinal());
		assertEquals(1000, counter.opened());
		assertFalse(counter.canOpen());
	}

	@Test
	public void allocationIsNotReusedAfterStreamsAreReleased() {
		// Releasing a stream frees concurrency, never an identifier (RFC 9000 §2.1).
		StreamCounter counter = StreamCounter.create(3);
		assertEquals(0, counter.allocate());
		assertEquals(1, counter.allocate());
		counter.onStreamReleased();
		counter.onStreamReleased();
		assertEquals(2, counter.allocate());
		assertEquals(3, counter.nextOrdinal());
		assertFalse(counter.canOpen());
	}

	@Test
	public void allocationStopsAtTheLimitAndResumesWhenItIsRaised() {
		StreamCounter counter = StreamCounter.create(2);
		counter.allocate();
		counter.allocate();
		assertFalse(counter.canOpen());
		assertThrows(IllegalStateException.class, counter::allocate);

		assertTrue(counter.onMaxStreams(4));
		assertTrue(counter.canOpen());
		assertEquals(2, counter.allocate());
		assertEquals(3, counter.allocate());
		assertFalse(counter.canOpen());
	}

	@Test
	public void aZeroLimitPermitsNothing() {
		StreamCounter counter = StreamCounter.create(0);
		assertFalse(counter.canOpen());
		assertFalse(counter.isWithinLimit(0));
		assertEquals(0, counter.available());
	}

	// endregion

	// region limits — FR-026, FR-028, RFC 9000 §4.6

	@Test
	public void aLowerMaxStreamsIsIgnoredWithoutError() {
		StreamCounter counter = StreamCounter.create(10);
		assertTrue(counter.onMaxStreams(20));
		assertFalse(counter.onMaxStreams(15));
		assertFalse(counter.onMaxStreams(20));
		assertFalse(counter.onMaxStreams(0));
		assertEquals(20, counter.limit());
	}

	@Test
	public void exceedingTheLimitIsReportedNotThrown() {
		// The counter reports; STREAM_LIMIT_ERROR is the manager's decision (RFC 9000 §4.6).
		StreamCounter counter = StreamCounter.create(3);
		assertTrue(counter.isWithinLimit(0));
		assertTrue(counter.isWithinLimit(2));
		assertFalse(counter.isWithinLimit(3));
		assertFalse(counter.isWithinLimit(Long.MAX_VALUE));
		assertEquals(0, counter.opened());   // a rejected arrival changes nothing
	}

	@Test
	public void theTwoToThe60StreamCountCeilingIsRecognised() {
		// RFC 9000 §19.11: a MAX_STREAMS above 2^60 is a FRAME_ENCODING_ERROR — the manager's call.
		assertTrue(StreamCounter.isValidStreamCount(0));
		assertTrue(StreamCounter.isValidStreamCount(StreamCounter.MAX_STREAM_COUNT));
		assertFalse(StreamCounter.isValidStreamCount(StreamCounter.MAX_STREAM_COUNT + 1));
		assertFalse(StreamCounter.isValidStreamCount(-1));
		assertEquals(1L << 60, StreamCounter.MAX_STREAM_COUNT);
	}

	@Test
	public void anOrdinalPastTheEncodableCeilingIsNeverAdmitted() {
		StreamCounter counter = StreamCounter.create(StreamCounter.MAX_STREAM_COUNT);
		// The highest ordinal is 2^60-1 — exactly what a 62-bit stream identifier can carry.
		assertTrue(counter.isWithinLimit(StreamCounter.MAX_STREAM_COUNT - 1));
		assertFalse(counter.isWithinLimit(StreamCounter.MAX_STREAM_COUNT));
		counter.open(StreamCounter.MAX_STREAM_COUNT - 1);
		assertFalse(counter.canOpen());
	}

	// endregion

	// region implicit opening — FR-003, RFC 9000 §2.1

	@Test
	public void anArrivalOpensEveryLowerNumberedStreamOfTheSameType() {
		StreamCounter counter = StreamCounter.create(100);
		assertEquals(5, counter.newlyOpenedBy(4));

		// The contract: open(ordinal) returns the lowest ordinal this arrival opens, so the caller
		// instantiates every stream in [result, ordinal].
		assertEquals(0, counter.open(4));
		assertEquals(5, counter.opened());
		assertTrue(counter.isOpen(0));
		assertTrue(counter.isOpen(4));
		assertFalse(counter.isOpen(5));
	}

	@Test
	public void theRangeReturnedByOpenIsGaplessAcrossSeveralArrivals() {
		StreamCounter counter = StreamCounter.create(100);
		List<Long> opened = new ArrayList<>();
		for (long ordinal : new long[]{2, 0, 7, 5, 8}) {
			for (long o = counter.open(ordinal); o <= ordinal; o++) {
				opened.add(o);
			}
		}
		// 0..8 exactly once each, in ascending order, despite the arrivals skipping and back-tracking.
		assertEquals(9, opened.size());
		for (int i = 0; i < 9; i++) {
			assertEquals(i, (long) opened.get(i));
		}
		assertEquals(9, counter.opened());
	}

	@Test
	public void anArrivalForAnAlreadyOpenStreamOpensNothing() {
		StreamCounter counter = StreamCounter.create(100);
		counter.open(4);
		assertEquals(0, counter.newlyOpenedBy(2));
		assertEquals(0, counter.newlyOpenedBy(4));

		long firstNew = counter.open(2);
		assertTrue("empty range expected", firstNew > 2);
		assertEquals(5, counter.opened());
	}

	@Test
	public void implicitlyOpenedStreamsCountAgainstTheLimit() {
		// A peer told it may open 3 streams cannot reach ordinal 3 by skipping to it.
		StreamCounter counter = StreamCounter.create(3);
		assertTrue(counter.isWithinLimit(2));
		assertEquals(0, counter.open(2));
		assertEquals(3, counter.opened());
		assertEquals(0, counter.available());
		assertFalse(counter.isWithinLimit(3));
	}

	@Test
	public void newlyOpenedByDoesNotMutate() {
		StreamCounter counter = StreamCounter.create(100);
		assertEquals(8, counter.newlyOpenedBy(7));
		assertEquals(8, counter.newlyOpenedBy(7));
		assertEquals(0, counter.opened());
	}

	@Test
	public void allocationAndArrivalKeepTheSameOpenedCount() {
		// The same counter is never used for both directions, but the invariant must hold either way:
		// `opened` is the highest ordinal opened, plus one.
		StreamCounter local = StreamCounter.create(10);
		local.allocate();
		local.allocate();
		assertEquals(2, local.opened());
		assertEquals(8, local.available());
	}

	// endregion

	// region credit granted as streams are released — FR-028

	@Test
	public void releasingStreamsCountsTowardTheGrantThreshold() {
		StreamCounter counter = StreamCounter.create(100);
		counter.open(99);                       // the peer opens all 100 it is allowed
		assertEquals(100, counter.opened());
		assertEquals(0, counter.available());

		for (int i = 0; i < 49; i++) {
			counter.onStreamReleased();
		}
		assertEquals(49, counter.closed());
		assertFalse(counter.shouldGrantCredit(100));

		counter.onStreamReleased();
		assertEquals(50, counter.closed());
		assertTrue(counter.shouldGrantCredit(100));
	}

	@Test
	public void aGrantRestoresTheFullStreamWindowAboveTheReleasedCount() {
		StreamCounter counter = StreamCounter.create(100);
		counter.open(99);
		for (int i = 0; i < 60; i++) {
			counter.onStreamReleased();
		}
		assertEquals(160, counter.grantCredit(100));
		assertEquals(160, counter.limit());
		assertTrue(counter.isWithinLimit(159));
		assertFalse(counter.isWithinLimit(160));
		assertFalse(counter.shouldGrantCredit(100));
	}

	@Test
	public void aGrantNeverLowersTheLimitAndIsIdempotent() {
		StreamCounter counter = StreamCounter.create(100);
		counter.open(99);
		for (int i = 0; i < 70; i++) {
			counter.onStreamReleased();
		}
		assertEquals(170, counter.grantCredit(100));
		assertEquals(170, counter.grantCredit(100));
		assertEquals(170, counter.grantCredit(20));
		assertEquals(170, counter.limit());
	}

	@Test
	public void grantsAreCappedAtTheEncodableCeiling() {
		StreamCounter counter = StreamCounter.create(StreamCounter.MAX_STREAM_COUNT);
		counter.open(StreamCounter.MAX_STREAM_COUNT - 1);
		counter.onStreamReleased();
		// closed + window would be 2^60 + 1, which no MAX_STREAMS frame may carry (RFC 9000 §19.11).
		assertEquals(StreamCounter.MAX_STREAM_COUNT, counter.grantCredit(StreamCounter.MAX_STREAM_COUNT));
		assertTrue(StreamCounter.isValidStreamCount(counter.limit()));
	}

	@Test
	public void releasingMoreStreamsThanWereOpenedIsACallerBug() {
		StreamCounter counter = StreamCounter.create(10);
		counter.allocate();
		counter.onStreamReleased();
		assertThrows(IllegalStateException.class, counter::onStreamReleased);
	}

	// endregion

	@Test
	public void negativeInputsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> StreamCounter.create(-1));
		StreamCounter counter = StreamCounter.create(10);
		assertThrows(IllegalArgumentException.class, () -> counter.open(-1));
		assertThrows(IllegalArgumentException.class, () -> counter.newlyOpenedBy(-1));
		assertThrows(IllegalArgumentException.class, () -> counter.isWithinLimit(-1));
		assertThrows(IllegalArgumentException.class, () -> counter.grantCredit(-1));
		assertThrows(IllegalArgumentException.class, () -> counter.shouldGrantCredit(-1));
	}

	@Test
	public void toStringCarriesTheCounters() {
		StreamCounter counter = StreamCounter.create(100);
		counter.open(9);
		counter.onStreamReleased();
		String s = counter.toString();
		assertTrue(s, s.contains("10"));
		assertTrue(s, s.contains("100"));
	}
}
