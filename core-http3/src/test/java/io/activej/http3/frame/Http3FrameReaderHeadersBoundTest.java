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
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http3.frame.Http3FrameReader.DATA_CHUNK_SIZE;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * T116: a request stream's reader holds each frame type to <b>its own</b> bound, so an over-declared
 * HEADERS frame is refused at its Length varint rather than allocated at the body bound.
 *
 * <h2>What was wrong</h2>
 * T110 stopped a DATA frame from being allocated at its declared length, but the reader still had one
 * bound for every type on the stream, and {@code Http3RequestStream} built it with
 * {@code max(maxFieldSectionSize, maxBodySize)} — {@link #BODY_BOUND}, 100 MB at the defaults. A
 * HEADERS frame is not chunked and cannot be, since QPACK needs the field section whole, so a peer that
 * declared a {@link #DECLARED}-byte field section passed the only check there was and had that length
 * allocated in full, immediately, on every one of the {@code maxConcurrentRequestStreams} streams it
 * was allowed to open — the same amplification T110 closed for DATA, through the other frame type.
 * {@code Http3RequestStream.decodeFieldSection}'s own {@code maxFieldSectionSize} check could not
 * catch it: it reads the field section that has already been materialized.
 *
 * <h2>What proves it fixed</h2>
 * The same two independent assertions T110's {@link Http3FrameReaderIncrementalDataTest} makes — the
 * exact error code at the exact stage, and what the pool is actually holding, read off
 * {@link ByteBufPool}'s allocation registry (which needs {@code -DByteBufPool.registry=true}, as
 * Surefire sets and a bare IDE run does not) — plus the assertions that the two bounds stayed
 * <i>distinct</i>: a DATA frame far above the field-section bound is still taken, and the control
 * stream's single-bound reader is unchanged.
 */
public final class Http3FrameReaderHeadersBoundTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What {@code Http3RequestStream} hands its reader for HEADERS: {@code maxFieldSectionSize}. */
	private static final long FIELD_SECTION_BOUND = MemSize.kilobytes(64).toLong();

	/** And for DATA: {@code maxBodySize} — the bound that used to cover HEADERS too. */
	private static final long BODY_BOUND = MemSize.megabytes(100).toLong();

	/** Under the old shared bound, so the length itself used to be legal; 800× the real one. */
	private static final long DECLARED = MemSize.megabytes(50).toLong();

	/** What a control stream is read with, all types alike. */
	private static final long CONTROL_BOUND = MemSize.kilobytes(16).toLong();

	@Test
	public void anOverDeclaredHeadersFrameIsRefusedAtItsLengthVarint() {
		ByteBuf wire = frameHeader(HeadersFrame.TYPE, DECLARED);

		Http3FrameReader reader = requestStreamReader();
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(wire));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, e.errorCode());
		assertTrue("the refusal names the field-section bound, not the body one: " + e.getMessage(),
			e.getMessage().contains(String.valueOf(FIELD_SECTION_BOUND)));
		assertFalse("the whole header was consumed before the refusal", wire.canRead());

		wire.recycle();
		reader.recycle();
	}

	@Test
	public void anOverDeclaredHeadersFrameNeverAllocatesItsDeclaredLength() {
		assumeAllocationsAreObservable();

		// Just the frame header and a token 64 bytes of field section: 0.0001% of what it declared. A
		// reader that took the peer's word for the length allocated all 50 MB of it right here.
		ByteBuf wire = frameHeader(HeadersFrame.TYPE, DECLARED);
		wire = appendPattern(wire, 64);

		long baseline = liveAllocatedBytes();
		Http3FrameReader reader = requestStreamReader();
		ByteBuf fed = wire;
		assertThrows(Http3Exception.class, () -> reader.feed(fed));
		long peak = liveAllocatedBytes() - baseline;

		wire.recycle();
		reader.recycle();

		assertTrue("a reader refusing a " + DECLARED + "-byte HEADERS frame held " + peak +
				   " bytes; nothing proportional to a declared length may be allocated before it is bounded",
			peak <= FIELD_SECTION_BOUND);
	}

	@Test
	public void aHeadersFrameWithinTheFieldSectionBoundIsStillDeliveredWhole() throws Http3Exception {
		// Longer than a DATA chunk, so this is also the assertion that HEADERS is not chunked: QPACK
		// cannot decode a field section in instalments.
		int length = DATA_CHUNK_SIZE + 4096;
		ByteBuf wire = frameHeader(HeadersFrame.TYPE, length);
		wire = appendPattern(wire, length);

		Http3FrameReader reader = requestStreamReader();
		HeadersFrame headers = (HeadersFrame) reader.feed(wire);

		assertNotNull(headers);
		assertEquals(length, headers.fieldSection.readRemaining());
		assertFalse(reader.isMidFrame());

		wire.recycle();
		headers.recycle();
		reader.recycle();
	}

	@Test
	public void aDataFrameFarAboveTheFieldSectionBoundIsStillTaken() throws Http3Exception {
		// 16× the field-section bound and well under the body one: the two bounds are distinct, and
		// tightening HEADERS did not tighten the body along with it.
		long declared = FIELD_SECTION_BOUND * 16;
		ByteBuf wire = frameHeader(DataFrame.TYPE, declared);
		wire = appendPattern(wire, DATA_CHUNK_SIZE);

		Http3FrameReader reader = requestStreamReader();
		DataFrame instalment = (DataFrame) reader.feed(wire);

		assertNotNull(instalment);
		assertEquals(DATA_CHUNK_SIZE, instalment.data.readRemaining());
		assertTrue("the frame is unfinished, as its declared length says", reader.isMidFrame());

		wire.recycle();
		instalment.recycle();
		reader.recycle();
	}

	@Test
	public void aDataFrameOverTheBodyBoundIsStillRefused() {
		ByteBuf wire = frameHeader(DataFrame.TYPE, BODY_BOUND + 1);

		Http3FrameReader reader = requestStreamReader();
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(wire));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, e.errorCode());
		assertTrue("the refusal names the body bound: " + e.getMessage(),
			e.getMessage().contains(String.valueOf(BODY_BOUND)));

		wire.recycle();
		reader.recycle();
	}

	@Test
	public void anOverDeclaredSmallFrameTypeIsRefusedBeforeItIsAllocated() {
		// SETTINGS is illegal on a request stream, but the frame sequence only says so once the frame is
		// decoded — and a SETTINGS payload, like every type but DATA, is buffered whole at exactly the
		// length it declares. So it is bounded here, before it is read, rather than there.
		for (long type : new long[] {SettingsFrame.TYPE, GoAwayFrame.TYPE, MaxPushIdFrame.TYPE,
			CancelPushFrame.TYPE}) {
			ByteBuf wire = frameHeader(type, DECLARED);

			Http3FrameReader reader = requestStreamReader();
			Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(wire));

			assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, e.errorCode());
			assertTrue("type 0x" + Long.toHexString(type) + " is held to the tighter of the two bounds: " +
					   e.getMessage(),
				e.getMessage().contains(String.valueOf(FIELD_SECTION_BOUND)));

			wire.recycle();
			reader.recycle();
		}
	}

	@Test
	public void anUnknownFrameTypeKeepsTheWiderBound() throws Http3Exception {
		// An unknown payload is discarded byte by byte and never buffered, so nothing about it is
		// proportional to what it declared and RFC 9114 §9's tolerance stays as wide as the reader's
		// widest bound. Only the header is fed: what is asserted is that it was not refused.
		ByteBuf accepted = frameHeader(0x1fL * 3 + 0x21L, BODY_BOUND);

		Http3FrameReader reader = requestStreamReader();
		assertNull(reader.feed(accepted));
		assertTrue("a GREASE frame over the field-section bound was taken, not refused", reader.isMidFrame());

		accepted.recycle();
		reader.recycle();

		ByteBuf refused = frameHeader(0x1fL * 3 + 0x21L, BODY_BOUND + 1);
		Http3FrameReader bounded = requestStreamReader();
		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD,
			assertThrows(Http3Exception.class, () -> bounded.feed(refused)).errorCode());

		refused.recycle();
		bounded.recycle();
	}

	@Test
	public void theControlStreamReaderStillBoundsEveryTypeAtItsOneBound() throws Http3Exception {
		// What Http3Connection.readControlStream builds. The two-bound constructor left it alone: one
		// bound, applied to every type, since all of a control stream's are varints or pairs of them.
		ByteBuf refused = frameHeader(SettingsFrame.TYPE, CONTROL_BOUND + 1);
		Http3FrameReader reader = new Http3FrameReader(CONTROL_BOUND, Http3Errors.H3_EXCESSIVE_LOAD);
		Http3Exception e = assertThrows(Http3Exception.class, () -> reader.feed(refused));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, e.errorCode());
		assertTrue(e.getMessage().contains(String.valueOf(CONTROL_BOUND)));
		refused.recycle();
		reader.recycle();

		SettingsFrame settings = new SettingsFrame(new long[] {0x06}, new long[] {FIELD_SECTION_BOUND});
		ByteBuf accepted = ByteBufPool.allocate(settings.encodedLength());
		Http3Frames.write(accepted, settings);
		Http3FrameReader within = new Http3FrameReader(CONTROL_BOUND, Http3Errors.H3_EXCESSIVE_LOAD);

		assertEquals("a control frame within the bound is decoded as it always was", settings, within.feed(accepted));

		accepted.recycle();
		within.recycle();
	}

	// ---------------------------------------------------------------- helpers

	/** Exactly what {@code Http3RequestStream.Builder.doBuild} constructs. */
	private static Http3FrameReader requestStreamReader() {
		return new Http3FrameReader(FIELD_SECTION_BOUND, BODY_BOUND, Http3Errors.H3_EXCESSIVE_LOAD);
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
			buf.put((byte) (i * 31 + 7));
		}
		return buf;
	}

	// ---------------------------------------------------------------- measuring the pool

	/** Pooled bytes allocated and not yet recycled, right now — {@code ByteBufRule}'s own registry. */
	private static long liveAllocatedBytes() {
		return ByteBufPool.getStats().getUnrecycledBufs().values().stream()
			.mapToLong(ByteBufPool.Entry::getSize)
			.sum();
	}

	/**
	 * Without {@code -DByteBufPool.registry=true} every measurement here reads 0 and would pass
	 * vacuously, so the test says so and stops instead. Surefire sets it; a bare IDE run does not.
	 */
	private static void assumeAllocationsAreObservable() {
		ByteBuf probe = ByteBufPool.allocate(64);
		boolean observable = liveAllocatedBytes() > 0;
		probe.recycle();
		assumeTrue("ByteBufPool's allocation registry is off; run with -DByteBufPool.registry=true (Surefire does)",
			observable);
	}
}
