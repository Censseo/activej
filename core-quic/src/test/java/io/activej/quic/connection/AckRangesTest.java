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

package io.activej.quic.connection;

import io.activej.quic.codec.AckFrame;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link AckRanges} is pure arithmetic over {@code long}s — no reactor, no buffers. {@code ByteBufRule}
 * is declared for consistency with the rest of the module's test classes.
 * <p>
 * The RFC 9000 §19.3.1 encodings are all "minus one" forms, so the expected values below are derived
 * from the RFC text rather than from intuitive inclusive counts.
 */
public class AckRangesTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static AckRanges ranges() {
		return new AckRanges(32);
	}

	@Test
	public void emptyRanges() {
		AckRanges r = ranges();
		assertEquals(0, r.rangeCount());
		assertTrue(r.isEmpty());
		assertEquals(AckRanges.NONE, r.largest());
		assertFalse(r.contains(0));
		assertThrows(IllegalStateException.class, r::firstRangeLength);
	}

	@Test
	public void insertSingleAndQuery() {
		AckRanges r = ranges();
		r.add(5);
		assertEquals(1, r.rangeCount());
		assertEquals(5, r.largest());
		assertTrue(r.contains(5));
		assertFalse(r.contains(4));
		assertFalse(r.contains(6));
		// A single-packet range covers no *additional* packets below Largest Acknowledged.
		assertEquals(0, r.firstRangeLength());
	}

	@Test
	public void adjacentRangesMerge() {
		AckRanges r = ranges();
		r.add(5);
		r.add(6);
		r.add(4);
		assertEquals(1, r.rangeCount());
		assertEquals(6, r.largest());
		// 4..6 is three packets, so First ACK Range is 2 — the count of additional packets below 6.
		assertEquals(2, r.firstRangeLength());
		assertTrue(r.contains(4));
		assertTrue(r.contains(5));
		assertTrue(r.contains(6));
		assertFalse(r.contains(3));
		assertFalse(r.contains(7));
	}

	@Test
	public void nonAdjacentRangesStaySeparate() {
		AckRanges r = ranges();
		r.add(1);
		r.add(3);
		assertEquals(2, r.rangeCount());
		assertEquals(3, r.largest());
		assertTrue(r.contains(1));
		assertTrue(r.contains(3));
		assertFalse(r.contains(2));
	}

	@Test
	public void descendingOrderMaintained() {
		AckRanges r = ranges();
		for (long pn : new long[]{1, 9, 5, 7, 3}) {
			r.add(pn);
		}
		// Five mutually non-adjacent numbers stay five ranges, largest first.
		assertEquals(5, r.rangeCount());
		assertEquals(9, r.largest());
		assertEquals("[9, 7, 5, 3, 1]", r.toString());
	}

	@Test
	public void duplicateInsertIsNoOp() {
		AckRanges r = ranges();
		r.add(5);
		r.add(5);
		assertEquals(1, r.rangeCount());
		assertEquals(5, r.largest());

		// A number already inside a multi-packet range is also a no-op.
		r.add(6);
		r.add(7);
		assertEquals(1, r.rangeCount());
		r.add(6);
		assertEquals(1, r.rangeCount());
		assertEquals(7, r.largest());
		assertEquals(2, r.firstRangeLength());
	}

	@Test
	public void mergeOfTwoRangesViaBridgingNumber() {
		AckRanges r = ranges();
		r.add(1);
		r.add(2);
		r.add(3);
		r.add(5);
		r.add(6);
		r.add(7);
		assertEquals(2, r.rangeCount());

		// 4 bridges 1..3 and 5..7 — a merge that extends one side only would leave two ranges.
		r.add(4);
		assertEquals(1, r.rangeCount());
		assertEquals(7, r.largest());
		assertEquals(6, r.firstRangeLength());
		assertEquals("[1-7]", r.toString());
	}

	@Test
	public void boundDropsOldest() {
		AckRanges r = new AckRanges(32);
		// 32 mutually non-adjacent numbers: 0, 2, 4, ... 62.
		for (long pn = 0; pn <= 62; pn += 2) {
			r.add(pn);
		}
		assertEquals(32, r.rangeCount());
		assertEquals(62, r.largest());
		assertTrue(r.contains(0));

		r.add(64);
		// FR-015: still bounded, the newest is kept and the smallest is gone.
		assertEquals(32, r.rangeCount());
		assertEquals(64, r.largest());
		assertTrue(r.contains(64));
		assertTrue(r.contains(62));
		assertFalse(r.contains(0));
	}

	@Test
	public void boundOfOneKeepsOnlyTheLargest() {
		AckRanges r = new AckRanges(1);
		r.add(10);
		r.add(20);
		assertEquals(1, r.rangeCount());
		assertEquals(20, r.largest());
		assertFalse(r.contains(10));
	}

	@Test
	public void pruneOnAckOfAck() {
		AckRanges r = ranges();
		for (long pn = 1; pn <= 10; pn++) {
			r.add(pn);
		}
		assertEquals(1, r.rangeCount());

		// A single 1..10 range is not fully covered by 6, so it survives intact.
		r.pruneBelow(6);
		assertEquals(10, r.largest());

		AckRanges split = ranges();
		split.add(1);
		split.add(3);
		split.add(5);
		split.add(10);
		assertEquals(4, split.rangeCount());

		split.pruneBelow(5);
		assertEquals(1, split.rangeCount());
		assertEquals(10, split.largest());

		// Idempotent.
		split.pruneBelow(5);
		assertEquals(1, split.rangeCount());
		assertEquals(10, split.largest());
	}

	@Test
	public void mapsOntoAckFrame() {
		AckRanges r = ranges();
		// Ranges 1..3, 5..7, 10..10 — inserted out of order to exercise the merge paths too.
		for (long pn : new long[]{10, 5, 6, 7, 1, 2, 3}) {
			r.add(pn);
		}
		assertEquals(3, r.rangeCount());
		assertEquals("[10, 5-7, 1-3]", r.toString());

		long largestAcked = r.largest();
		long firstAckRange = r.firstRangeLength();
		long[] gaps = r.gaps();
		long[] rangeLengths = r.rangeLengths();

		assertEquals(10, largestAcked);
		// First range is the single packet 10 -> 0 additional packets.
		assertEquals(0, firstAckRange);
		// Gap to 5..7: packets 8 and 9 are missing (2 of them) -> encoded as 2 - 1 = 1... but the
		// encoding subtracts two in total because ranges are non-adjacent: 10 - 7 - 2 = 1.
		// Gap to 1..3: 10 - 7 = smallest of previous range is 5, so 5 - 3 - 2 = 0.
		assertArrayEquals(new long[]{1, 0}, gaps);
		// 5..7 covers 3 packets -> 2; 1..3 covers 3 packets -> 2.
		assertArrayEquals(new long[]{2, 2}, rangeLengths);

		// The values feed AckFrame directly.
		AckFrame frame = AckFrame.withoutEcn(largestAcked, 0, firstAckRange, gaps, rangeLengths);
		assertEquals(10, frame.largestAcked);
		assertEquals(0, frame.firstAckRange);
		assertEquals(2, frame.rangeCount());
		assertArrayEquals(new long[]{1, 0}, frame.gaps());
		assertArrayEquals(new long[]{2, 2}, frame.rangeLengths());
	}

	@Test
	public void gapsAndLengthsAreEmptyForASingleRange() {
		AckRanges r = ranges();
		r.add(4);
		assertEquals(0, r.gaps().length);
		assertEquals(0, r.rangeLengths().length);
	}

	@Test
	public void largePacketNumbersDoNotOverflow() {
		AckRanges r = ranges();
		long max = (1L << 62) - 1;
		r.add(max);
		r.add(max - 1);
		assertEquals(1, r.rangeCount());
		assertEquals(max, r.largest());
		assertEquals(1, r.firstRangeLength());
	}
}
