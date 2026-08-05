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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.http.HttpHeaders;
import io.activej.http3.Http3Errors;
import io.activej.http3.qpack.QpackBlockedSections.HeldSection;
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.Instruction;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static io.activej.http3.qpack.QpackRoundTrip.UNBOUNDED_FIELD_SECTION;
import static org.junit.Assert.*;

/**
 * T039–T042 — the three D-4 bounds on {@link QpackBlockedSections} and the blocking lifecycle they
 * bound, driven through a real {@link QpackDynamicEncoder} / {@link QpackDynamicDecoder} pair with the
 * peer's encoder stream withheld, which is the only thing that makes a section block.
 *
 * <h2>Where the acknowledgment is asserted, and why here at all</h2>
 * A {@code Section Acknowledgment} is written by {@code Http3Connection}, which owns the decoder
 * stream — {@link QpackDynamicDecoder} deliberately names no stream and emits nothing (research D-2).
 * What this test can assert honestly is the <b>obligation</b>: {@link Fixture#decoderStream} is fed by
 * {@link Fixture#surface}, which is the same funnel {@code Http3Connection.StreamQpackDecoder} applies
 * — one acknowledgment per {@link Decoded} whose Required Insert Count is non-zero, and
 * {@code onInsertCountAnnounced} raised to it. That a blocked section produces no {@link Decoded} is
 * therefore exactly "emits no acknowledgment". The wire assertion over a real connection belongs to
 * T044/T046.
 *
 * <h2>Why the encoder is given a blocked-stream limit the decoder does not advertise</h2>
 * FR-020 makes a conforming encoder refuse to block more streams than the peer permitted, so an
 * encoder configured with this decoder's own limit could never produce the traffic T040 and T042 bound.
 * The peer here is therefore deliberately over-eager — which is the case the bounds exist for.
 */
public class QpackBlockedSectionsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int CAPACITY = 4096;

	/** The {@code Http3Settings.qpackMaxInstructionSize()} default (FR-089). */
	private static final long MAX_INSTRUCTION_SIZE = 16 * 1024;

	/** Well above anything one test holds outstanding: the encoder's own bound is not under test here. */
	private static final int MAX_OUTSTANDING_SECTIONS = 1024;

	/** A peer that blocks as many streams as it likes, which is what the three bounds are for. */
	private static final int OVER_EAGER_PEER = 1024;

	private static final long TIMEOUT_MILLIS = 10_000;

	/** RFC 9000 §2.1 spaces client-initiated bidirectional stream ids four apart. */
	private static final long STREAM = 0;
	private static final long SECOND_STREAM = 4;
	private static final long THIRD_STREAM = 8;

	// region T039 — the blocking lifecycle

	/**
	 * FR-033, FR-037, FR-024: a section whose Required Insert Count is 5 against an Insert Count of 3 is
	 * held rather than failed, surfaces nothing and acknowledges nothing; the two insertions that raise
	 * the count to 5 unblock it; the same buffer then decodes, field for field, in wire order, and the
	 * acknowledgment it then owes is emitted exactly once.
	 */
	@Test
	public void aSectionBlockedOnUnarrivedInsertionsDecodesInWireOrderOnceTheyArrive() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 16, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			List<QpackField> fields = novelFields("x-lifecycle", 5);
			ByteBuf section = fixture.encode(STREAM, fields);
			List<EncoderInstruction> insertions = fixture.drain();
			assertEquals("the encoder is expected to insert every novel field of this section",
				5, insertions.size());

			fixture.deliver(insertions.subList(0, 3));
			assertEquals(3, fixture.decoder.insertCount());

			assertNull("a blocked section must surface no fields", fixture.feed(STREAM, section, 0));
			assertEquals(1, fixture.blocked.sectionCount());
			assertEquals(1, fixture.blocked.blockedStreamCount());
			assertEquals(5, fixture.blocked.held().get(0).requiredInsertCount());
			assertTrue("a blocked section must acknowledge nothing", fixture.decoderStream.isEmpty());
			// FR-026: the three insertions that did arrive are still owed an Insert Count Increment,
			// which is what a Section Acknowledgment would otherwise have covered.
			assertEquals(3, fixture.decoder.pendingInsertCountIncrement());

			assertTrue("an Insert Count of 3 must not release a section that needs 5",
				fixture.blocked.release(fixture.decoder.insertCount()).isEmpty());
			assertEquals(1, fixture.blocked.sectionCount());

			fixture.deliver(insertions.subList(3, 5));
			assertEquals(5, fixture.decoder.insertCount());

			List<List<QpackField>> surfaced = fixture.resume();
			assertEquals(1, surfaced.size());
			assertEquals(fields, surfaced.get(0));
			assertEquals(List.of(new SectionAcknowledgment(STREAM)), fixture.decoderStream);
			assertEquals("RFC 9204 §4.4.1: the acknowledgment covers every insertion up to its count",
				0, fixture.decoder.pendingInsertCountIncrement());
			assertTrue(fixture.blocked.isEmpty());
			assertEquals(0, fixture.blocked.heldBytes());
			assertEquals(0, fixture.blocked.sectionCount());
			assertEquals(QpackBlockedSections.NO_DEADLINE, fixture.blocked.earliestDeadlineMillis());
		} finally {
			fixture.close();
		}
	}

	/**
	 * The same lifecycle read off the decoder rather than off the holder: {@link
	 * QpackDynamicDecoder#decodeOrBlock} hands the caller's buffer back <b>unconsumed</b>, so the object
	 * held is the object that arrived and re-entering with it is what decodes it.
	 */
	@Test
	public void theBlockedSectionIsTheCallersOwnBufferHandedBackUnread() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 16, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			List<QpackField> fields = novelFields("x-identity", 4);
			ByteBuf section = fixture.encode(STREAM, fields);
			int wireBytes = section.readRemaining();
			List<EncoderInstruction> insertions = fixture.drain();

			SectionResult result = fixture.decoder.decodeOrBlock(section);
			assertTrue(result instanceof Blocked);
			Blocked blocked = (Blocked) result;
			assertSame(section, blocked.section());
			assertEquals(wireBytes, blocked.section().readRemaining());
			fixture.blocked.hold(STREAM, blocked.requiredInsertCount(), blocked.section(), 0);
			assertEquals(wireBytes, fixture.blocked.heldBytes());

			fixture.deliver(insertions);
			List<HeldSection> released = fixture.blocked.release(fixture.decoder.insertCount());
			assertEquals(1, released.size());
			assertSame(section, released.get(0).section());
			assertEquals(fields, fixture.surface(released.get(0)));
		} finally {
			fixture.close();
		}
	}

	// endregion
	// region T040 — the count bound

	/**
	 * FR-034: with {@code qpackBlockedStreams = N}, stream {@code N + 1} blocking is
	 * {@code QPACK_DECOMPRESSION_FAILED} (0x0200) at <b>connection</b> scope — RFC 9204 §2.1.2 makes
	 * exceeding the advertised limit a connection error, and a decoder that dropped the section instead
	 * would leave the two tables disagreeing (research D-4).
	 */
	@Test
	public void blockingOneMoreStreamThanAdvertisedClosesTheConnection() throws QpackException {
		int limit = 2;
		Fixture fixture = new Fixture(CAPACITY, limit, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-a", 3)), 0));
			assertNull(fixture.feed(SECOND_STREAM, fixture.encode(SECOND_STREAM, novelFields("x-b", 3)), 0));
			assertEquals(limit, fixture.blocked.blockedStreamCount());

			ByteBuf third = fixture.encode(THIRD_STREAM, novelFields("x-c", 3));
			QpackException e = assertThrows(QpackException.class, () -> fixture.feed(THIRD_STREAM, third, 0));

			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
			assertTrue("the blocked-stream limit is a connection error (RFC 9204 §2.1.2)", e.isConnectionError());
			assertEquals("the refused section must not be held", limit, fixture.blocked.blockedStreamCount());
			assertEquals(limit, fixture.blocked.sectionCount());
		} finally {
			fixture.close();
		}
	}

	/**
	 * The bound counts <b>streams</b>, not sections: a second section on a stream that is already
	 * blocked stays within a limit of 1, because RFC 9204 §2.1.2's limit is on how many streams a
	 * decoder will hold. What that leaves unbounded is memory, which is T042's bound and not this one.
	 */
	@Test
	public void aSecondSectionOnAnAlreadyBlockedStreamDoesNotCountAsASecondStream() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 1, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-first", 3)), 0));
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-second", 3)), 0));

			assertEquals(1, fixture.blocked.blockedStreamCount());
			assertEquals(2, fixture.blocked.sectionCount());

			ByteBuf other = fixture.encode(SECOND_STREAM, novelFields("x-other", 3));
			QpackException e = assertThrows(QpackException.class, () -> fixture.feed(SECOND_STREAM, other, 0));
			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
			assertTrue(e.isConnectionError());
		} finally {
			fixture.close();
		}
	}

	/**
	 * A limit of 0 holds nothing. {@link QpackDynamicDecoder#decodeOrBlock} already fails such a section
	 * itself before this class is ever reached (its own RFC 9204 §2.1.2 guard), so the assertion is that
	 * the two agree on the code rather than that a second rule fires.
	 */
	@Test
	public void aLimitOfZeroHoldsNothingAtEitherLayer() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 0, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			ByteBuf section = fixture.encode(STREAM, novelFields("x-zero", 3));
			QpackException e = assertThrows(QpackException.class, () -> fixture.feed(STREAM, section, 0));
			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
			assertTrue(e.isConnectionError());
			assertTrue(fixture.blocked.isEmpty());
			assertEquals(0, fixture.blocked.maxHeldBytes());
		} finally {
			fixture.close();
		}
	}

	// endregion
	// region T041 — the time bound

	/**
	 * FR-036: a peer may legally block a stream and then simply stop sending, so a held section has a
	 * bounded lifetime. Expiry is {@code QPACK_DECOMPRESSION_FAILED} at connection scope, and the held
	 * buffers go back through the close path's single release funnel.
	 * <p>
	 * The clock is a parameter, not a reading: this package takes no {@code Reactor} (ADR-016), so the
	 * timeout is a bound stated here and scheduled by {@code Http3Connection} (T050). That makes the
	 * assertion exact without a {@code ManualEventloop}.
	 */
	@Test
	public void aSectionHeldPastTheTimeoutClosesTheConnectionAndReleasesItsBuffers() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 16, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			long arrival = 1_000;
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-slow", 4)), arrival));
			long heldBytes = fixture.blocked.heldBytes();
			assertTrue(heldBytes > 0);
			assertEquals(arrival + TIMEOUT_MILLIS, fixture.blocked.earliestDeadlineMillis());

			fixture.blocked.checkTimeout(arrival);
			fixture.blocked.checkTimeout(arrival + TIMEOUT_MILLIS - 1);
			assertEquals(1, fixture.blocked.sectionCount());

			QpackException e = assertThrows(QpackException.class,
				() -> fixture.blocked.checkTimeout(arrival + TIMEOUT_MILLIS));
			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
			assertTrue("the blocked-section timeout is a connection error (research D-4)", e.isConnectionError());

			// What a closing connection does, and the only funnel that releases held sections (FR-035).
			fixture.blocked.recycle();
			assertTrue(fixture.blocked.isEmpty());
			assertEquals(0, fixture.blocked.sectionCount());
			assertEquals(0, fixture.blocked.heldBytes());
			assertEquals(QpackBlockedSections.NO_DEADLINE, fixture.blocked.earliestDeadlineMillis());
			fixture.blocked.recycle();
		} finally {
			fixture.close();
		}
	}

	/** The deadline is the <b>oldest</b> section's, so a later arrival never postpones an earlier one. */
	@Test
	public void theDeadlineFollowsTheOldestHeldSection() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 16, UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-old", 3)), 100));
			assertNull(fixture.feed(SECOND_STREAM, fixture.encode(SECOND_STREAM, novelFields("x-new", 3)), 5_000));

			assertEquals(100 + TIMEOUT_MILLIS, fixture.blocked.earliestDeadlineMillis());
			QpackException e = assertThrows(QpackException.class,
				() -> fixture.blocked.checkTimeout(100 + TIMEOUT_MILLIS));
			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
			assertTrue(e.isConnectionError());
		} finally {
			fixture.close();
		}
	}

	/** {@code qpackBlockedStreamTimeout = 0} disables the bound, as {@code Http3Settings} documents. */
	@Test
	public void aTimeoutOfZeroNeverExpires() throws QpackException {
		Fixture fixture = new Fixture(CAPACITY, 16, UNBOUNDED_FIELD_SECTION, 0);
		try {
			assertNull(fixture.feed(STREAM, fixture.encode(STREAM, novelFields("x-forever", 3)), 0));
			assertEquals(QpackBlockedSections.NO_DEADLINE, fixture.blocked.earliestDeadlineMillis());
			fixture.blocked.checkTimeout(Long.MAX_VALUE);
			assertEquals(1, fixture.blocked.sectionCount());
		} finally {
			fixture.close();
		}
	}

	// endregion
	// region T042 — the byte bound

	/**
	 * FR-035 and research D-4's explicit point: the count bound is <b>not</b> a memory bound, because one
	 * blocked stream may hold any number of sections. This drives exactly that traffic — every section on
	 * the same two streams, so the count never approaches its limit — and asserts the byte bound directly,
	 * by walking the {@link ByteBuf}s actually retained and summing them, rather than inferring the figure
	 * from the count or trusting the holder's own counter.
	 */
	@Test
	public void heldMemoryStaysWithinTheDerivedByteBoundEvenWhileTheStreamCountDoesNotGrow()
		throws QpackException {
		int maxBlockedStreams = 2;
		long maxFieldSectionSize = 256;
		Fixture fixture = new Fixture(65536, OVER_EAGER_PEER, maxBlockedStreams, maxFieldSectionSize,
			UNBOUNDED_FIELD_SECTION, TIMEOUT_MILLIS);
		try {
			long bound = (long) maxBlockedStreams * maxFieldSectionSize;
			assertEquals(bound, fixture.blocked.maxHeldBytes());

			QpackException refused = null;
			int held = 0;
			for (int i = 0; i < 256 && refused == null; i++) {
				long streamId = i % 2 == 0 ? STREAM : SECOND_STREAM;
				ByteBuf section = fixture.encode(streamId, novelFields("x-bytes-" + i, 6));
				// What Http3Connection does after every encode (research D-2), and the reason the peer's
				// insertions are in flight rather than absent: they are written, they just have not
				// overtaken the request stream. Not draining would fill the encoder's bounded pending
				// queue and silently turn the rest of this loop into unblockable literal sections.
				fixture.drain();
				try {
					if (fixture.feed(streamId, section, 0) == null) held++;
				} catch (QpackException e) {
					refused = e;
				}
				assertRetainedBytesWithin(fixture.blocked, bound);
				assertTrue("the count bound must not be what fires here",
					fixture.blocked.blockedStreamCount() <= maxBlockedStreams);
			}

			assertNotNull("the byte bound never fired within 256 sections", refused);
			assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, refused.errorCode());
			assertTrue("the byte bound is a connection error (research D-4)", refused.isConnectionError());
			assertTrue("more than one section must have been held before the bound fired", held > 1);
			assertRetainedBytesWithin(fixture.blocked, bound);
		} finally {
			fixture.close();
		}
	}

	/** The derived figure at the shipped defaults: 16 blocked streams × a 64 KB field section = 1 MB. */
	@Test
	public void theByteBoundIsTheProductTheDefaultsImply() {
		assertEquals(1024L * 1024, new QpackBlockedSections(16, 64 * 1024, TIMEOUT_MILLIS).maxHeldBytes());
		assertEquals(0, new QpackBlockedSections(0, 64 * 1024, TIMEOUT_MILLIS).maxHeldBytes());
		assertEquals(Long.MAX_VALUE,
			new QpackBlockedSections(16, Long.MAX_VALUE, TIMEOUT_MILLIS).maxHeldBytes());
	}

	/**
	 * The direct assertion T042 asks for: the bound is checked against the buffers themselves, and the
	 * holder's own {@code heldBytes()} is checked against them too, so a counter that drifted from what
	 * is retained fails here rather than silently widening the bound.
	 */
	private static void assertRetainedBytesWithin(QpackBlockedSections blocked, long bound) {
		long retained = 0;
		for (HeldSection held : blocked.held()) {
			assertTrue("a held section must still be readable", held.section().canRead());
			retained += held.section().readRemaining();
		}
		assertEquals("heldBytes() must equal what is actually retained", retained, blocked.heldBytes());
		assertTrue("retained " + retained + " bytes, above the " + bound + " byte bound", retained <= bound);
	}

	// endregion
	// region fixture

	/**
	 * One {@link QpackDynamicEncoder}, the {@link QpackDynamicDecoder} that must understand it, the
	 * peer's encoder stream as real wire bytes, and the {@link QpackBlockedSections} the decoder's
	 * {@link Blocked} results are held in.
	 * <p>
	 * <b>{@code ByteBuf} ownership</b>: {@link QpackEncoderStreamReader#feed} owns its buffer on every
	 * path including a throw; {@link QpackDynamicDecoder#decodeOrBlock} owns its input except when it
	 * returns {@link Blocked}; {@link QpackBlockedSections#hold} owns the section it is given on every
	 * path. So nothing here recycles a section itself, and {@link #close} is the one leak-free exit.
	 */
	private static final class Fixture {
		final QpackDynamicEncoder encoder;
		final QpackDynamicDecoder decoder;
		final QpackEncoderStreamReader encoderStream;
		final QpackBlockedSections blocked;
		final List<DecoderInstruction> decoderStream = new ArrayList<>();

		Fixture(int capacity, int blockedStreams, long maxFieldSectionSize, long timeoutMillis) {
			this(capacity, OVER_EAGER_PEER, blockedStreams, maxFieldSectionSize, maxFieldSectionSize,
				timeoutMillis);
		}

		/**
		 * @param peerBlockedStreams  what the <b>encoder</b> believes it may block, deliberately not what
		 *                            this decoder advertises — see the class Javadoc
		 * @param maxFieldSectionSize the figure the byte bound is derived from
		 * @param decodeBound         the decoder's own RFC 9114 §4.2.2 accounted bound, separate so the
		 *                            byte bound can be scaled down without failing a decode
		 */
		Fixture(int capacity, int peerBlockedStreams, int blockedStreams, long maxFieldSectionSize,
			long decodeBound, long timeoutMillis) {
			this.encoder = new QpackDynamicEncoder(capacity, capacity, peerBlockedStreams,
				MAX_OUTSTANDING_SECTIONS, Set.of());
			this.decoder = new QpackDynamicDecoder(capacity, blockedStreams, decodeBound);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
			this.blocked = new QpackBlockedSections(blockedStreams, maxFieldSectionSize, timeoutMillis);
			try {
				// The Set Dynamic Table Capacity the encoder queues at construction (RFC 9204 §4.3.1):
				// without it the decoder's table stays at capacity 0 and no insertion could be applied.
				deliver(drain());
			} catch (QpackException e) {
				throw new AssertionError("the encoder's own opening instruction was rejected", e);
			}
		}

		ByteBuf encode(long streamId, List<QpackField> fields) {
			return encoder.encode(streamId, fields);
		}

		List<EncoderInstruction> drain() {
			return encoder.drainPendingInstructions();
		}

		void deliver(List<EncoderInstruction> instructions) throws QpackException {
			if (instructions.isEmpty()) return;
			encoderStream.feed(writeInstructions(instructions));
		}

		/** @return the decoded fields, or {@code null} when the section was held */
		@Nullable List<QpackField> feed(long streamId, ByteBuf section, long nowMillis) throws QpackException {
			SectionResult result = decoder.decodeOrBlock(section);
			if (result instanceof Blocked pending) {
				blocked.hold(streamId, pending.requiredInsertCount(), pending.section(), nowMillis);
				return null;
			}
			return accept(streamId, (Decoded) result);
		}

		/** Every section the current Insert Count releases, decoded, in arrival order. */
		List<List<QpackField>> resume() throws QpackException {
			List<List<QpackField>> surfaced = new ArrayList<>();
			for (HeldSection held : blocked.release(decoder.insertCount())) {
				surfaced.add(surface(held));
			}
			return surfaced;
		}

		List<QpackField> surface(HeldSection held) throws QpackException {
			SectionResult result = decoder.decodeOrBlock(held.section());
			if (result instanceof Blocked stillBlocked) {
				stillBlocked.section().recycle();
				throw new AssertionError("a released section blocked again at Required Insert Count " +
										 stillBlocked.requiredInsertCount());
			}
			return accept(held.streamId(), (Decoded) result);
		}

		/**
		 * The funnel {@code Http3Connection.StreamQpackDecoder} applies, reproduced so "no acknowledgment
		 * while blocked" is an assertion about behaviour rather than about an absence of code.
		 */
		private List<QpackField> accept(long streamId, Decoded decoded) {
			if (decoded.requiredInsertCount() > 0) {
				decoderStream.add(new SectionAcknowledgment(streamId));
				decoder.onInsertCountAnnounced(decoded.requiredInsertCount());
			}
			return decoded.fields();
		}

		void close() {
			blocked.recycle();
			encoderStream.recycle();
		}
	}

	private static ByteBuf writeInstructions(List<? extends Instruction> instructions) {
		int length = 0;
		for (Instruction instruction : instructions) length += instruction.encodedLength();
		ByteBuf buf = ByteBufPool.allocate(length);
		for (Instruction instruction : instructions) instruction.writeTo(buf);
		return buf;
	}

	/** Fields no static table entry covers and no earlier section used, so every one of them is inserted. */
	private static List<QpackField> novelFields(String prefix, int count) {
		List<QpackField> fields = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			fields.add(new QpackField(HttpHeaders.of(prefix + '-' + i),
				encodeAscii("value-" + prefix + '-' + i + "-0123456789abcdef")));
		}
		return fields;
	}

	// endregion
}
