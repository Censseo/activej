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

package io.activej.http3.frame;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.frame.Http3FrameReader.DATA_CHUNK_SIZE;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * T110: a DATA frame costs {@link Http3FrameReader#DATA_CHUNK_SIZE} while it is being read, whatever
 * length it declared — the amplification a peer would otherwise buy with a frame header it never
 * follows through on.
 *
 * <h2>What was wrong</h2>
 * The reader used to allocate a frame's whole declared length the moment its Length varint parsed and
 * passed the bound check, before one payload byte had arrived. On a request stream that bound is
 * {@code max(maxFieldSectionSize, maxBodySize)} — {@link #REQUEST_STREAM_BOUND} at this module's
 * defaults — so a peer that opened its allowed number of concurrent request streams and put one
 * over-declared DATA header on each forced gigabytes of pooled allocation for under a kilobyte of
 * wire input. Both reviewers of phases 6–12 found it independently.
 *
 * <h2>What proves it fixed</h2>
 * Two independent assertions, because each catches a different way of getting this wrong:
 * <ul>
 *     <li>the payload that <b>has</b> arrived comes out of a frame that never finishes — a reader
 *     that still buffered the whole declared length would return nothing at all from the
 *     {@link #ACTUALLY_SENT} bytes a stalling peer sends, since the frame never completes;</li>
 *     <li>and what the pool is actually holding, read straight off {@link ByteBufPool}'s allocation
 *     registry, never exceeds one chunk over the baseline. That one needs
 *     {@code -DByteBufPool.registry=true}, which Surefire sets and a bare IDE run does not.</li>
 * </ul>
 * Only DATA is incremental: HEADERS has to be whole before QPACK can decode it, and the control-stream
 * types are single varints or pairs of them.
 * {@link #aHeadersFrameLongerThanAChunkIsStillDeliveredWhole()} is the assertion that this fix left
 * them alone. What keeps <b>them</b> from being the same amplification vector is a bound rather than an
 * instalment — the reader holds each type to its own, {@code maxFieldSectionSize} for HEADERS and
 * {@code maxControlFrameSize} on a control stream, checked before the payload is allocated. That is
 * T116's correction and {@link Http3FrameReaderHeadersBoundTest}'s subject; when this class was written
 * a request stream's reader still had one bound covering every type on it, and a HEADERS frame could
 * declare {@link #REQUEST_STREAM_BOUND} as freely as a DATA one.
 */
public final class Http3FrameReaderIncrementalDataTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What {@code Http3RequestStream} hands its reader: {@code max(maxFieldSectionSize, maxBodySize)}. */
	private static final long REQUEST_STREAM_BOUND = MemSize.megabytes(100).toLong();

	/** Under the bound, so the length itself is legal — the lie is that it never arrives. */
	private static final long DECLARED = MemSize.megabytes(50).toLong();

	/**
	 * All the peer ever sends of it before going quiet: 0.04% of what it declared, and just past one
	 * chunk — so that the bytes it did send have to come out of the reader while the frame they belong
	 * to is still unfinished.
	 */
	private static final int ACTUALLY_SENT = DATA_CHUNK_SIZE + 4096;

	/** Long enough for two whole chunks and a remainder, so the last instalment is a short one. */
	private static final int LONG_FRAME = 2 * DATA_CHUNK_SIZE + 7232;

	@Test
	public void anOverDeclaredDataFrameIsDeliveredAsItArrivesRatherThanWaitedFor() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(DECLARED);
		wire = appendPattern(wire, ACTUALLY_SENT);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, 1024);
		wire.recycle();

		// The peer stalls here, having sent 20 KiB of the 50 MB it promised. Before this fix nothing at
		// all came out of that — the reader sat on a 50 MB buffer waiting for a frame that never ends.
		assertEquals("the chunk that arrived was handed on, from inside an unfinished frame",
			1, delivered.size());
		assertEquals(DATA_CHUNK_SIZE, delivered.get(0).readRemaining());
		assertEquals("the instalment's buffer is a chunk, not a declared length",
			DATA_CHUNK_SIZE, delivered.get(0).array().length);
		assertTrue("the frame is unfinished: 50 MB was declared and 20 KiB arrived", reader.isMidFrame());

		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void anOverDeclaredDataFrameNeverAllocatesItsDeclaredLength() throws Http3Exception {
		assumeAllocationsAreObservable();

		ByteBuf wire = dataFrameHeader(DECLARED);
		wire = appendPattern(wire, ACTUALLY_SENT);

		long baseline = liveAllocatedBytes();
		long peak = 0;
		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		while (wire.canRead()) {
			ByteBuf part = wire.slice(Math.min(1024, wire.readRemaining()));
			wire.moveHead(part.readRemaining());
			Http3Frame frame;
			// Each instalment is released the moment it is measured, so what is left over the baseline is
			// exactly what the reader itself is holding — the number the review put at ~100 MB per stream.
			while ((frame = reader.feed(part)) != null) {
				((DataFrame) frame).recycle();
			}
			part.recycle();
			peak = Math.max(peak, liveAllocatedBytes() - baseline);
		}
		wire.recycle();
		reader.recycle();

		assertTrue("a reader inside a 50 MB DATA frame held " + peak + " bytes, over the " +
				   DATA_CHUNK_SIZE + "-byte chunk it is allowed",
			peak <= DATA_CHUNK_SIZE);
	}

	@Test
	public void aDataFrameLongerThanAChunkArrivesAsInstalmentsThatReassemble() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(LONG_FRAME);
		wire = appendPattern(wire, LONG_FRAME);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, LONG_FRAME * 2);
		wire.recycle();

		assertEquals("one wire frame, three instalments", 3, delivered.size());
		assertEquals(DATA_CHUNK_SIZE, delivered.get(0).readRemaining());
		assertEquals(DATA_CHUNK_SIZE, delivered.get(1).readRemaining());
		assertEquals(LONG_FRAME - 2 * DATA_CHUNK_SIZE, delivered.get(2).readRemaining());
		assertPattern(delivered);
		assertFalse("the declared length arrived in full", reader.isMidFrame());

		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void theReaderStaysMidFrameBetweenTheInstalmentsOfOneDataFrame() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(LONG_FRAME);
		wire = appendPattern(wire, DATA_CHUNK_SIZE);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, LONG_FRAME * 2);
		wire.recycle();

		// A frame in hand no longer means the reader is between frames — which is what tells
		// Http3RequestStream that a FIN here truncated a frame (RFC 9114 §7.1) rather than ended cleanly.
		assertEquals(1, delivered.size());
		assertTrue("a whole chunk came out from the middle of an unfinished frame", reader.isMidFrame());

		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void aDataFrameWithinOneChunkIsStillDeliveredWhole() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(19);
		wire = appendPattern(wire, 19);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, 1);
		wire.recycle();

		assertEquals("a short DATA frame is one frame, byte-at-a-time feeding included", 1, delivered.size());
		assertEquals(19, delivered.get(0).readRemaining());
		assertFalse(reader.isMidFrame());

		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void aZeroLengthDataFrameIsStillOneEmptyFrame() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(0);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, 1);
		wire.recycle();

		// RFC 9114 §7.2.1 permits it and it means nothing; it must still not be swallowed.
		assertEquals(1, delivered.size());
		assertEquals(0, delivered.get(0).readRemaining());
		assertFalse(reader.isMidFrame());

		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void aChunkedDataFrameAbandonedPartWayLeavesNothingBehind() throws Http3Exception {
		ByteBuf wire = dataFrameHeader(LONG_FRAME);
		wire = appendPattern(wire, DATA_CHUNK_SIZE + 1000);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<ByteBuf> delivered = feedInSlices(reader, wire, 4096);
		wire.recycle();

		// The stream is reset here: one instalment is out, and the 1000 bytes of the next are the
		// reader's to release. ByteBufRule is the assertion (DI-1).
		assertEquals(1, delivered.size());
		delivered.forEach(ByteBuf::recycle);
		reader.recycle();
	}

	@Test
	public void aHeadersFrameLongerThanAChunkIsStillDeliveredWhole() throws Http3Exception {
		int length = DATA_CHUNK_SIZE + 4096;
		ByteBuf wire = frameHeader(HeadersFrame.TYPE, length);
		wire = appendPattern(wire, length);

		Http3FrameReader reader = new Http3FrameReader(REQUEST_STREAM_BOUND);
		List<Http3Frame> frames = new ArrayList<>();
		Http3Frame frame;
		while ((frame = reader.feed(wire)) != null) {
			frames.add(frame);
		}
		wire.recycle();

		// QPACK needs the field section whole, so nothing about this fix touches any type but DATA. What
		// bounds a HEADERS frame is maxFieldSectionSize, which the reader applies to the declared length
		// itself — a uniform bound is passed here, and Http3FrameReaderHeadersBoundTest is where the pair
		// a request stream really uses is exercised.
		assertEquals(1, frames.size());
		HeadersFrame headers = (HeadersFrame) frames.get(0);
		assertEquals(length, headers.fieldSection.readRemaining());
		assertFalse(reader.isMidFrame());

		headers.recycle();
		reader.recycle();
	}

	// ---------------------------------------------------------------- helpers

	/** Feeds {@code wire} in slices of at most {@code sliceSize}, collecting every DATA payload delivered. */
	private static List<ByteBuf> feedInSlices(Http3FrameReader reader, ByteBuf wire, int sliceSize)
		throws Http3Exception {
		List<ByteBuf> delivered = new ArrayList<>();
		do {
			ByteBuf part = wire.slice(Math.min(sliceSize, wire.readRemaining()));
			wire.moveHead(part.readRemaining());
			Http3Frame frame;
			while ((frame = reader.feed(part)) != null) {
				delivered.add(((DataFrame) frame).data);
			}
			part.recycle();
		} while (wire.canRead());
		return delivered;
	}

	/** Every instalment in order, against the pattern at its absolute offset in the body. */
	private static void assertPattern(List<ByteBuf> delivered) {
		int offset = 0;
		for (ByteBuf chunk : delivered) {
			for (int i = 0; i < chunk.readRemaining(); i++) {
				assertEquals("byte " + (offset + i) + " of the reassembled body",
					patternByte(offset + i), chunk.peek(i));
			}
			offset += chunk.readRemaining();
		}
	}

	private static ByteBuf dataFrameHeader(long declaredLength) {
		return frameHeader(DataFrame.TYPE, declaredLength);
	}

	private static ByteBuf frameHeader(long type, long declaredLength) {
		ByteBuf buf = ByteBufPool.allocate(
			QuicVarInts.encodedLength(type) + QuicVarInts.encodedLength(declaredLength));
		QuicVarInts.write(buf, type);
		QuicVarInts.write(buf, declaredLength);
		return buf;
	}

	private static ByteBuf appendPattern(ByteBuf buf, int length) {
		buf = ByteBufPool.ensureWriteRemaining(buf, length);
		for (int i = 0; i < length; i++) {
			buf.put(patternByte(i));
		}
		return buf;
	}

	private static byte patternByte(int index) {
		return (byte) (index * 31 + 7);
	}

	// ---------------------------------------------------------------- measuring the pool

	/**
	 * Pooled bytes allocated and not yet recycled, right now — the registry {@code ByteBufRule} reads
	 * for its leak check, summed by allocation size instead of listed.
	 */
	private static long liveAllocatedBytes() {
		return ByteBufPool.getStats().getUnrecycledBufs().values().stream()
			.mapToLong(ByteBufPool.Entry::getSize)
			.sum();
	}

	/**
	 * The registry is only kept under {@code -DByteBufPool.registry=true}, which the Surefire
	 * configuration in the root pom sets and a bare IDE run does not. Without it every measurement here
	 * reads 0, which would pass vacuously — so the test says so and stops instead.
	 */
	private static void assumeAllocationsAreObservable() {
		ByteBuf probe = ByteBufPool.allocate(64);
		boolean observable = liveAllocatedBytes() > 0;
		probe.recycle();
		assumeTrue("ByteBufPool's allocation registry is off; run with -DByteBufPool.registry=true (Surefire does)",
			observable);
	}
}
