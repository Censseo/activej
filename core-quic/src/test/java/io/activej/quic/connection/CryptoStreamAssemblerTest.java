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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * The adversarial cases are the point here: a round-trip test would never exercise the overlap,
 * duplicate or bound paths, which is where both the leaks and the protocol violations live.
 */
public class CryptoStreamAssemblerTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long BOUND = 64 * 1024;

	/** Pooled, so {@link ByteBufRule} can actually see a leak. */
	private static ByteBuf buf(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf b = ByteBufPool.allocate(bytes.length);
		b.put(bytes);
		return b;
	}

	/** Reads and recycles, as a real consumer (the TLS engine) would. */
	private static String consume(ByteBuf b) {
		if (b == null) return null;
		String s = b.getString(StandardCharsets.US_ASCII);
		b.recycle();
		return s;
	}

	private static CryptoStreamAssembler assembler() {
		return new CryptoStreamAssembler(BOUND);
	}

	@Test
	public void inOrderDeliversImmediately() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertEquals("abc", consume(a.add(0, buf("abc"))));
		assertEquals(3, a.readOffset());
		assertEquals(0, a.bufferedBytes());

		assertEquals("de", consume(a.add(3, buf("de"))));
		assertEquals(5, a.readOffset());
		assertEquals(0, a.bufferedBytes());
		a.close();
	}

	@Test
	public void outOfOrderBuffersThenReleases() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(5, buf("fgh")));
		assertEquals(3, a.bufferedBytes());
		assertEquals(0, a.readOffset());

		// The whole contiguous run is delivered as one buffer — the TLS engine needs it contiguous.
		assertEquals("abcdefgh", consume(a.add(0, buf("abcde"))));
		assertEquals(8, a.readOffset());
		assertEquals(0, a.bufferedBytes());
		a.close();
	}

	@Test
	public void multipleOutOfOrderChunksCollapseInOneDelivery() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(6, buf("ghi")));
		assertNull(a.add(3, buf("def")));
		assertEquals(6, a.bufferedBytes());

		assertEquals("abcdefghi", consume(a.add(0, buf("abc"))));
		assertEquals(9, a.readOffset());
		assertEquals(0, a.bufferedBytes());
		a.close();
	}

	@Test
	public void exactDuplicateIsDiscarded() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertEquals("abc", consume(a.add(0, buf("abc"))));

		assertNull(a.add(0, buf("abc")));
		assertEquals(3, a.readOffset());
		assertEquals(0, a.bufferedBytes());
		a.close();
	}

	@Test
	public void chunkEntirelyBelowReadOffsetIsDiscardedSilently() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertEquals("0123456789", consume(a.add(0, buf("0123456789"))));

		assertNull(a.add(2, buf("xx")));
		assertEquals(10, a.readOffset());
		a.close();
	}

	@Test
	public void partialOverlapConsistentIsAccepted() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertEquals("abcd", consume(a.add(0, buf("abcd"))));

		// "cdef" at offset 2 re-sends "cd" and adds "ef": only "ef" may be delivered, or the engine
		// would see duplicate handshake bytes.
		assertEquals("ef", consume(a.add(2, buf("cdef"))));
		assertEquals(6, a.readOffset());
		a.close();
	}

	@Test
	public void partialOverlapInconsistentWithBufferedIsProtocolViolation() throws Exception {
		CryptoStreamAssembler a = assembler();
		// Buffer 2..6 = "cdef" out of order, so both copies are held and comparison is possible.
		assertNull(a.add(2, buf("cdef")));

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> a.add(4, buf("XXgh")));
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());

		// The assembler is still consistent, and the offending buffer was recycled (ByteBufRule).
		assertEquals(4, a.bufferedBytes());
		a.close();
	}

	@Test
	public void overlapConsistentWithBufferedIsAccepted() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(2, buf("cdef")));
		// "efgh" at offset 4 agrees on "ef".
		assertNull(a.add(4, buf("efgh")));

		assertEquals("abcdefgh", consume(a.add(0, buf("ab"))));
		assertEquals(8, a.readOffset());
		a.close();
	}

	@Test
	public void straddlingReadOffsetIsAcceptedWithoutComparing() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertEquals("abcd", consume(a.add(0, buf("abcd"))));

		// RFC 9000 §19.6 permits discarding already-received data without checking it: the delivered
		// prefix is no longer held, so "ZZ" here is dropped rather than rejected.
		assertEquals("ef", consume(a.add(2, buf("ZZef"))));
		assertEquals(6, a.readOffset());
		a.close();
	}

	@Test
	public void bufferedBytesBoundRaisesCryptoBufferExceeded() throws Exception {
		CryptoStreamAssembler a = new CryptoStreamAssembler(64);
		// Non-contiguous chunks accumulate: offset 100 onwards, nothing ever becomes deliverable.
		assertNull(a.add(100, buf("x".repeat(32))));
		assertNull(a.add(200, buf("y".repeat(32))));
		assertEquals(64, a.bufferedBytes());

		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> a.add(300, buf("z")));
		assertEquals(QuicTransportErrors.CRYPTO_BUFFER_EXCEEDED, e.errorCode());
		assertEquals(64, a.bufferedBytes());
		a.close();
	}

	@Test
	public void largeInOrderHandshakeDoesNotTripTheBound() throws Exception {
		// A 200 KiB certificate chain arriving in order never buffers, so the 64 KiB bound is
		// irrelevant to it — the bound is on out-of-order bytes only.
		CryptoStreamAssembler a = assembler();
		long offset = 0;
		for (int i = 0; i < 200; i++) {
			ByteBuf out = a.add(offset, buf("k".repeat(1024)));
			assertNotNull(out);
			assertEquals(1024, out.readRemaining());
			out.recycle();
			offset += 1024;
			assertEquals(0, a.bufferedBytes());
		}
		assertEquals(200 * 1024, a.readOffset());
		a.close();
	}

	@Test
	public void closeRecyclesEverythingBuffered() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(10, buf("aaaa")));
		assertNull(a.add(20, buf("bbbb")));
		assertNull(a.add(30, buf("cccc")));
		assertEquals(12, a.bufferedBytes());

		a.close();
		assertEquals(0, a.bufferedBytes());
		assertTrue(a.isClosed());

		// Idempotent.
		a.close();
		assertEquals(0, a.bufferedBytes());
	}

	@Test
	public void addAfterCloseRecyclesAndReturnsNull() throws Exception {
		CryptoStreamAssembler a = assembler();
		a.close();
		assertNull(a.add(0, buf("in-flight")));
		assertEquals(0, a.bufferedBytes());
	}

	@Test
	public void zeroLengthChunkIsHarmless() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(0, ByteBufPool.allocate(0)));
		assertEquals(0, a.readOffset());
		assertEquals(0, a.bufferedBytes());

		assertEquals("abc", consume(a.add(0, buf("abc"))));
		a.close();
	}

	@Test
	public void offsetOverflowIsRejected() {
		CryptoStreamAssembler a = assembler();
		// offset + length would exceed 2^62-1 (RFC 9000 §19.6): rejected before any allocation.
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> a.add(CryptoStreamAssembler.MAX_OFFSET, buf("xx")));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, e.errorCode());

		QuicTransportException negative = assertThrows(QuicTransportException.class,
			() -> a.add(-1, buf("x")));
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, negative.errorCode());
		a.close();
	}

	@Test
	public void offsetExactlyAtTheLimitIsAccepted() throws Exception {
		CryptoStreamAssembler a = assembler();
		// A single byte ending exactly at 2^62-1 is legal.
		assertNull(a.add(CryptoStreamAssembler.MAX_OFFSET - 1, buf("x")));
		assertEquals(1, a.bufferedBytes());
		a.close();
	}

	@Test
	public void duplicateAtTheSameBufferedOffsetKeepsTheLonger() throws Exception {
		CryptoStreamAssembler a = assembler();
		assertNull(a.add(5, buf("ab")));
		assertEquals(2, a.bufferedBytes());

		// A longer chunk at the same offset supersedes the shorter one.
		assertNull(a.add(5, buf("abcd")));
		assertEquals(4, a.bufferedBytes());

		// A shorter one is discarded.
		assertNull(a.add(5, buf("ab")));
		assertEquals(4, a.bufferedBytes());

		assertEquals("12345abcd", consume(a.add(0, buf("12345"))));
		a.close();
	}

	@Test
	public void rejectsNonPositiveBound() {
		assertThrows(IllegalArgumentException.class, () -> new CryptoStreamAssembler(0));
	}
}
