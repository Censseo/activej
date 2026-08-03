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
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * RFC 9000 §2.2 (sending and receiving data) and §4.5 (final size) — the pure reassembly half.
 * No eventloop: {@link StreamReassembler} holds no reactor state.
 */
public final class StreamReassemblerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static ByteBuf buf(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(bytes.length, 1));
		buf.put(bytes);
		return buf;
	}

	private static String pollString(StreamReassembler reassembler) {
		ByteBuf buf = reassembler.poll();
		assertNotNull(buf);
		return buf.asString(StandardCharsets.US_ASCII);
	}

	// region in-order delivery (FR-008)

	@Test
	public void inOrderDelivery() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf("abcde"));
		assertEquals(5, reassembler.readOffset());
		assertTrue(reassembler.hasReady());
		assertEquals("abcde", pollString(reassembler));

		reassembler.add(5, buf("fghij"));
		assertEquals(10, reassembler.readOffset());
		assertEquals("fghij", pollString(reassembler));

		assertNull(reassembler.poll());
		assertFalse(reassembler.hasReady());
		assertEquals(0, reassembler.pendingRanges());
	}

	@Test
	public void zeroLengthAddIsANoOp() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf(""));
		assertEquals(0, reassembler.readOffset());
		assertNull(reassembler.poll());
		assertEquals(0, reassembler.pendingRanges());
	}

	// endregion

	// region out-of-order buffering (FR-008)

	@Test
	public void outOfOrderBufferedUntilTheGapCloses() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(5, buf("fghij"));
		assertEquals(0, reassembler.readOffset());
		assertEquals(1, reassembler.pendingRanges());
		assertNull(reassembler.poll());

		reassembler.add(0, buf("abcde"));
		assertEquals(10, reassembler.readOffset());
		assertEquals(0, reassembler.pendingRanges());
		assertEquals("abcde", pollString(reassembler));
		assertEquals("fghij", pollString(reassembler));
		assertNull(reassembler.poll());
	}

	@Test
	public void severalGapsCloseAtOnce() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(10, buf("kl"));
		reassembler.add(14, buf("op"));
		assertEquals(2, reassembler.pendingRanges());
		assertEquals(0, reassembler.readOffset());

		// [5, 16) — novel parts are [5, 10) and [12, 14); [10, 12) and [14, 16) are already buffered
		reassembler.add(5, buf("fghijklmnop"));
		// Four pieces, but they are mutually adjacent and so span [5, 16) with no gap inside it: one
		// discontiguous range, the single gap [0, 5) still separating it from the read offset (FR-011).
		assertEquals(1, reassembler.pendingRanges());
		assertEquals(4, reassembler.bufferedPieces());
		assertEquals(0, reassembler.readOffset());
		assertNull(reassembler.poll());

		reassembler.add(0, buf("abcde"));
		assertEquals(16, reassembler.readOffset());
		assertEquals(0, reassembler.pendingRanges());
		assertEquals("abcde", pollString(reassembler));
		assertEquals("fghij", pollString(reassembler));
		assertEquals("kl", pollString(reassembler));
		assertEquals("mn", pollString(reassembler));
		assertEquals("op", pollString(reassembler));
		assertNull(reassembler.poll());
	}

	// endregion

	// region duplicates and overlaps (FR-009)

	@Test
	public void wholeDuplicateOfDeliveredDataIsDiscarded() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf("abcde"));
		assertEquals("abcde", pollString(reassembler));

		reassembler.add(0, buf("abcde"));
		assertEquals(5, reassembler.readOffset());
		assertNull(reassembler.poll());
		assertEquals(0, reassembler.pendingRanges());

		// a strict sub-range of what was already delivered
		reassembler.add(1, buf("bc"));
		assertEquals(5, reassembler.readOffset());
		assertNull(reassembler.poll());
	}

	@Test
	public void wholeDuplicateOfBufferedDataIsDiscarded() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(10, buf("klmno"));
		assertEquals(1, reassembler.pendingRanges());

		reassembler.add(10, buf("klmno"));
		assertEquals(1, reassembler.pendingRanges());

		reassembler.add(11, buf("lmn"));
		assertEquals(1, reassembler.pendingRanges());

		reassembler.add(0, buf("abcdefghij"));
		assertEquals(15, reassembler.readOffset());
		assertEquals("abcdefghij", pollString(reassembler));
		assertEquals("klmno", pollString(reassembler));
		assertNull(reassembler.poll());
	}

	@Test
	public void partialOverlapTrimmedAgainstReadOffset() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf("abcde"));
		assertEquals("abcde", pollString(reassembler));

		// [3, 8) straddles the read offset — only [5, 8) is novel
		reassembler.add(3, buf("defgh"));
		assertEquals(8, reassembler.readOffset());
		assertEquals("fgh", pollString(reassembler));
		assertNull(reassembler.poll());
	}

	@Test
	public void partialOverlapTrimmedAgainstNeighbours() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(4, buf("efg"));   // [4, 7)
		reassembler.add(9, buf("jk"));    // [9, 11)
		assertEquals(2, reassembler.pendingRanges());

		// [2, 12) — novel parts are [2, 4), [7, 9) and [11, 12)
		reassembler.add(2, buf("cdefghijkl"));
		// Five pieces covering [2, 12) without a gap between any two of them: one range (FR-011). The
		// frame closed both gaps it spanned, so what were two ranges are now one.
		assertEquals(1, reassembler.pendingRanges());
		assertEquals(5, reassembler.bufferedPieces());
		assertEquals(0, reassembler.readOffset());

		reassembler.add(0, buf("ab"));
		assertEquals(12, reassembler.readOffset());
		assertEquals(0, reassembler.pendingRanges());

		StringBuilder sb = new StringBuilder();
		ByteBuf b;
		while ((b = reassembler.poll()) != null) {
			sb.append(b.asString(StandardCharsets.US_ASCII));
		}
		assertEquals("abcdefghijkl", sb.toString());
	}

	// endregion

	// region no copy (FR-014)

	@Test
	public void contiguousDataIsHandedOutWithoutCopying() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		ByteBuf inOrder = buf("abcde");
		byte[] inOrderArray = inOrder.array();
		reassembler.add(0, inOrder);
		ByteBuf delivered = reassembler.poll();
		assertNotNull(delivered);
		assertSame(inOrderArray, delivered.array());
		delivered.recycle();
	}

	@Test
	public void trimmedDataIsSlicedNotCopied() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf("abcde"));
		assertEquals("abcde", pollString(reassembler));

		// straddles the read offset: the delivered part must be a slice of the very same array
		ByteBuf straddling = buf("defgh");
		byte[] straddlingArray = straddling.array();
		reassembler.add(3, straddling);
		ByteBuf delivered = reassembler.poll();
		assertNotNull(delivered);
		assertSame(straddlingArray, delivered.array());
		assertEquals("fgh", delivered.asString(StandardCharsets.US_ASCII));

		// split against a buffered neighbour: both novel pieces are slices of the same array
		reassembler.add(12, buf("mn"));
		ByteBuf split = buf("ijklmnop");
		byte[] splitArray = split.array();
		reassembler.add(8, split);
		assertEquals(16, reassembler.readOffset());
		ByteBuf first = reassembler.poll();
		ByteBuf second = reassembler.poll();
		ByteBuf third = reassembler.poll();
		assertNotNull(first);
		assertNotNull(second);
		assertNotNull(third);
		assertSame(splitArray, first.array());
		assertSame(splitArray, third.array());
		assertEquals("ijkl", first.asString(StandardCharsets.US_ASCII));
		assertEquals("mn", second.asString(StandardCharsets.US_ASCII));
		assertEquals("op", third.asString(StandardCharsets.US_ASCII));
	}

	// endregion

	// region range-count bound (FR-011, clarification Q1)

	@Test
	public void rangeCountBoundClosesTheConnection() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(2);

		reassembler.add(10, buf("a"));
		reassembler.add(20, buf("b"));
		assertEquals(2, reassembler.pendingRanges());

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(30, buf("c")));
		assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		// the rejected buffer is recycled by `add`, and the two buffered ones by `close`
		assertEquals(2, reassembler.pendingRanges());

		reassembler.close();
	}

	@Test
	public void aRangeThatBecomesContiguousDoesNotCountAgainstTheBound() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(2);

		reassembler.add(5, buf("fghij"));
		reassembler.add(15, buf("pqrst"));
		assertEquals(2, reassembler.pendingRanges());

		// at the bound, but this range is contiguous with the read offset and never gets buffered
		reassembler.add(0, buf("abcde"));
		assertEquals(10, reassembler.readOffset());
		assertEquals(1, reassembler.pendingRanges());
		assertEquals("abcde", pollString(reassembler));
		assertEquals("fghij", pollString(reassembler));

		reassembler.close();
	}

	/**
	 * FR-011, clarification Q1: the bound is on discontiguous ranges, so pieces that touch end-to-end
	 * are one range however many of them there are. Counting pieces instead would make the bound scale
	 * with the bandwidth-delay product — a window of in-order frames behind one hole — rather than with
	 * how fragmented the peer's sending is.
	 */
	@Test
	public void adjacentPiecesAreOneRangeHoweverManyOfThemThereAre() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(2);

		// One hole at [0, 1), and then a run of single-byte pieces behind it, each adjacent to the last.
		for (int i = 1; i <= 40; i++) {
			reassembler.add(i, buf(String.valueOf((char) ('a' + i % 26))));
			assertEquals("a run of adjacent pieces is one range", 1, reassembler.pendingRanges());
			assertEquals(i, reassembler.bufferedPieces());
		}

		reassembler.add(0, buf("Z"));
		assertEquals(41, reassembler.readOffset());
		assertEquals(0, reassembler.pendingRanges());
		assertEquals(0, reassembler.bufferedPieces());
		reassembler.close();
	}

	/** A piece landing in the one-byte hole between two ranges bridges them into one (as {@code AckRanges} does). */
	@Test
	public void aPieceBridgingTwoRangesMergesThem() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(2);

		reassembler.add(5, buf("f"));    // [5, 6)
		reassembler.add(7, buf("h"));    // [7, 8)
		assertEquals(2, reassembler.pendingRanges());

		reassembler.add(6, buf("g"));    // [6, 7) — bridges the two
		assertEquals(1, reassembler.pendingRanges());
		assertEquals(3, reassembler.bufferedPieces());

		// At the bound of 2 there is now room for one more range again, which piece-counting would deny.
		reassembler.add(10, buf("k"));
		assertEquals(2, reassembler.pendingRanges());
		reassembler.close();
	}

	@Test
	public void offsetBeyondTheProtocolMaximumIsRejected() {
		StreamReassembler reassembler = new StreamReassembler(32);

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(StreamReassembler.MAX_OFFSET - 1, buf("ab")));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, e.errorCode());
		assertEquals(0, reassembler.pendingRanges());
	}

	@Test
	public void negativeOffsetIsRejected() {
		StreamReassembler reassembler = new StreamReassembler(32);

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> reassembler.add(-1, buf("ab")));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, e.errorCode());
	}

	@Test
	public void maxRangesMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new StreamReassembler(0));
	}

	// endregion

	// region release (FR-014, WI-9)

	@Test
	public void closeReleasesBufferedAndReadyBuffers() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);

		reassembler.add(0, buf("abcde"));   // becomes ready
		reassembler.add(10, buf("klmno"));  // stays pending
		assertTrue(reassembler.hasReady());
		assertEquals(1, reassembler.pendingRanges());

		reassembler.close();

		assertTrue(reassembler.isClosed());
		assertFalse(reassembler.hasReady());
		assertEquals(0, reassembler.pendingRanges());
		assertNull(reassembler.poll());

		reassembler.close(); // idempotent
	}

	@Test
	public void addAfterCloseRecyclesItsInput() throws QuicTransportException {
		StreamReassembler reassembler = new StreamReassembler(32);
		reassembler.close();

		reassembler.add(0, buf("abcde"));
		assertEquals(0, reassembler.readOffset());
		assertEquals(0, reassembler.pendingRanges());
		assertNull(reassembler.poll());
	}

	// endregion
}
