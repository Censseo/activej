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
import io.activej.http3.qpack.QpackInstructions.Duplicate;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertWithLiteralName;
import io.activej.http3.qpack.QpackInstructions.InsertWithNameReference;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Every FR-029 cause on the peer's QPACK encoder stream, plus the FR-028 size bound (T019).
 * <p>
 * Each closes the <b>connection</b> with {@code QPACK_ENCODER_STREAM_ERROR} (0x0201) and leaks
 * nothing — {@link QpackEncoderStreamReader#feed} owns its input on every path, throw included, and
 * {@link ByteBufRule} fails the build otherwise.
 * <p>
 * The scope matters as much as the code: {@code QpackIntegers} and {@code QpackHuffman} raise
 * {@code QPACK_DECOMPRESSION_FAILED}, which is the <i>field-section</i> code. A malformed integer or
 * Huffman string on this stream is the stream's own error instead (FR-032) — the distinction
 * commit {@code b4df4163d} exists to preserve.
 */
public class QpackEncoderStreamAdversarialTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long TIMEOUT_MS = 5_000;

	private static final int CAPACITY = 220;
	private static final long ROOMY = 16 * 1024;

	private QpackDynamicDecoder decoder;

	private QpackEncoderStreamReader reader(int maxCapacity, long maxInstructionSize) {
		decoder = new QpackDynamicDecoder(maxCapacity, 0, Long.MAX_VALUE);
		return new QpackEncoderStreamReader(decoder, maxInstructionSize);
	}

	/** A reader whose table is already at the Appendix B capacity, as a first instruction would leave it. */
	private QpackEncoderStreamReader started() throws QpackException {
		QpackEncoderStreamReader reader = reader(CAPACITY, ROOMY);
		reader.feed(QpackInstructions.encode(new SetDynamicTableCapacity(CAPACITY)));
		assertEquals(CAPACITY, decoder.capacity());
		return reader;
	}

	private static ByteBuf bytes(int... octets) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(octets.length, 1));
		for (int octet : octets) {
			buf.writeByte((byte) octet);
		}
		return buf;
	}

	private static byte[] ascii(int length, char c) {
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(c);
		}
		return encodeAscii(sb.toString());
	}

	private static QpackException assertRejected(QpackEncoderStreamReader reader, ByteBuf buf) {
		QpackException e = assertThrows(QpackException.class, () -> reader.feed(buf));
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, e.errorCode());
		assertTrue("RFC 9204 §6: an encoder-stream failure is always connection-scoped", e.isConnectionError());
		// The remainder is released on the way out, so a caller that also calls recycle() is safe.
		assertEquals(0, reader.pendingBytes());
		return e;
	}

	private static QpackException assertRejected(QpackEncoderStreamReader reader, EncoderInstruction instruction) {
		return assertRejected(reader, QpackInstructions.encode(instruction));
	}

	// ---------------------------------------------------------------- Set Dynamic Table Capacity

	@Test(timeout = TIMEOUT_MS)
	public void capacityAboveTheAdvertisedMaximumIsRejected() {
		assertRejected(reader(CAPACITY, ROOMY), new SetDynamicTableCapacity(CAPACITY + 1));
	}

	@Test(timeout = TIMEOUT_MS)
	public void capacityAtTheAdvertisedMaximumIsAccepted() throws QpackException {
		QpackEncoderStreamReader reader = reader(CAPACITY, ROOMY);
		assertEquals(1, reader.feed(QpackInstructions.encode(new SetDynamicTableCapacity(CAPACITY))));
		assertEquals(CAPACITY, decoder.capacity());
	}

	@Test(timeout = TIMEOUT_MS)
	public void anyCapacityAboveZeroIsRejectedWhenNoneIsAdvertised() {
		// FR-040: a peer that opens the streams and sends only Set Dynamic Table Capacity 0 is legal.
		assertRejected(reader(0, ROOMY), new SetDynamicTableCapacity(1));
	}

	@Test(timeout = TIMEOUT_MS)
	public void capacityZeroIsAcceptedWhenNoneIsAdvertised() throws QpackException {
		QpackEncoderStreamReader reader = reader(0, ROOMY);
		assertEquals(1, reader.feed(QpackInstructions.encode(new SetDynamicTableCapacity(0))));
		assertEquals(0, decoder.capacity());
	}

	// ---------------------------------------------------------------- name references

	@Test(timeout = TIMEOUT_MS)
	public void staticNameReferenceOutOfRangeIsRejected() throws QpackException {
		// The static table holds indices [0, 99).
		assertRejected(started(), new InsertWithNameReference(true, QpackStaticTable.SIZE, encodeAscii("v")));
	}

	@Test(timeout = TIMEOUT_MS)
	public void dynamicNameReferenceOutOfRangeIsRejected() throws QpackException {
		// Relative index 0 against an empty table names absolute -1.
		assertRejected(started(), new InsertWithNameReference(false, 0, encodeAscii("v")));
	}

	@Test(timeout = TIMEOUT_MS)
	public void dynamicNameReferenceToAnEvictedEntryIsRejected() throws QpackException {
		QpackEncoderStreamReader reader = started();
		// Three entries of 32 + 10 + 60 = 102 bytes: the third evicts the first at a capacity of 220.
		for (int i = 0; i < 3; i++) {
			reader.feed(QpackInstructions.encode(
				new InsertWithLiteralName(encodeAscii("namenamen" + i), ascii(60, 'x'))));
		}
		assertFalse(decoder.table().isAvailable(0));

		// Insert Count 3, relative index 2 -> absolute 0, which has been evicted.
		assertRejected(reader, new InsertWithNameReference(false, 2, encodeAscii("v")));
	}

	// ---------------------------------------------------------------- Duplicate

	@Test(timeout = TIMEOUT_MS)
	public void duplicateOfANeverInsertedEntryIsRejected() throws QpackException {
		assertRejected(started(), new Duplicate(0));
	}

	@Test(timeout = TIMEOUT_MS)
	public void duplicateOfAnEvictedEntryIsRejected() throws QpackException {
		QpackEncoderStreamReader reader = started();
		for (int i = 0; i < 3; i++) {
			reader.feed(QpackInstructions.encode(
				new InsertWithLiteralName(encodeAscii("namenamen" + i), ascii(60, 'x'))));
		}
		assertFalse(decoder.table().isAvailable(0));

		assertRejected(reader, new Duplicate(2));
	}

	@Test(timeout = TIMEOUT_MS)
	public void duplicateOfALiveEntryIsAccepted() throws QpackException {
		QpackEncoderStreamReader reader = started();
		reader.feed(QpackInstructions.encode(new InsertWithNameReference(true, 0, encodeAscii("www.example.com"))));

		assertEquals(1, reader.feed(QpackInstructions.encode(new Duplicate(0))));
		assertEquals(2, decoder.insertCount());
		assertEquals(HttpHeaders.of(":authority"), decoder.table().nameAt(1));
	}

	// ---------------------------------------------------------------- size

	@Test(timeout = TIMEOUT_MS)
	public void insertionLargerThanTheCurrentCapacityIsRejected() throws QpackException {
		// 32 + len(":authority") + 200 = 242, over a capacity of 220.
		assertRejected(started(), new InsertWithNameReference(true, 0, ascii(200, 'a')));
	}

	@Test(timeout = TIMEOUT_MS)
	public void anyInsertionBeforeACapacityInstructionIsRejected() {
		// RFC 9204 §3.2.3: the capacity starts at 0 whatever the advertised maximum, so an encoder that
		// inserts before raising it is inserting into a table with no room.
		assertRejected(reader(CAPACITY, ROOMY), new InsertWithLiteralName(encodeAscii("k"), encodeAscii("v")));
	}

	@Test(timeout = TIMEOUT_MS)
	public void anInstructionAboveTheMaximumInstructionSizeIsRejected() {
		// The bound is checked on the bytes one instruction consumed, not only on what is buffered —
		// otherwise an oversized instruction arriving whole in one read would pass unexamined.
		assertRejected(reader(CAPACITY, 8), new InsertWithLiteralName(encodeAscii("custom-key"), ascii(64, 'z')));
	}

	@Test(timeout = TIMEOUT_MS)
	public void anUnterminatedContinuationRunIsBoundedRatherThanBuffered() {
		QpackEncoderStreamReader reader = reader(CAPACITY, 16);
		// 0x3f opens a 5-bit-prefix continuation that these 0x80 bytes never terminate: each one is
		// "not yet whole", so without the bound this buffer grows for as long as the peer keeps typing.
		int[] run = new int[24];
		run[0] = 0x3f;
		for (int i = 1; i < run.length; i++) {
			run[i] = 0x80;
		}
		assertRejected(reader, bytes(run));
	}

	// ---------------------------------------------------------------- malformed syntax

	@Test(timeout = TIMEOUT_MS)
	public void aMalformedPrefixIntegerIsRejectedAsAnEncoderStreamError() {
		// A *terminated* over-long continuation run: 0x3f then ten 0xff then 0x00. An unterminated one
		// reads as "not yet whole" instead, which is the test above.
		int[] run = new int[12];
		run[0] = 0x3f;
		for (int i = 1; i <= 10; i++) {
			run[i] = 0xff;
		}
		run[11] = 0x00;
		assertRejected(reader(CAPACITY, ROOMY), bytes(run));
	}

	@Test(timeout = TIMEOUT_MS)
	public void aMalformedHuffmanStringIsRejectedAsAnEncoderStreamError() {
		// Insert With Literal Name, H=1, name length 1, then a single 0x00 octet: the code for '0' plus
		// three bits of zero padding, which RFC 7541 §5.2 requires to be all ones.
		assertRejected(reader(CAPACITY, ROOMY), bytes(0x61, 0x00));
	}

	// ---------------------------------------------------------------- incremental parsing

	@Test(timeout = TIMEOUT_MS)
	public void anInstructionSplitAcrossFeedsIsAppliedExactlyOnceWhole() throws QpackException {
		QpackEncoderStreamReader reader = started();
		ByteBuf whole = QpackInstructions.encode(
			new InsertWithLiteralName(encodeAscii("custom-key"), encodeAscii("custom-value")));
		int length = whole.readRemaining();

		ByteBuf firstHalf = ByteBufPool.allocate(length);
		firstHalf.put(whole.array(), whole.head(), length / 2);
		ByteBuf secondHalf = ByteBufPool.allocate(length);
		secondHalf.put(whole.array(), whole.head() + length / 2, length - length / 2);
		whole.recycle();

		assertEquals(0, reader.feed(firstHalf));
		assertTrue("the partial instruction must be retained, not dropped", reader.pendingBytes() > 0);
		assertEquals(0, decoder.insertCount());

		assertEquals(1, reader.feed(secondHalf));
		assertEquals(0, reader.pendingBytes());
		assertEquals(1, decoder.insertCount());
		assertEquals(HttpHeaders.of("custom-key"), decoder.table().nameAt(0));
	}

	@Test(timeout = TIMEOUT_MS)
	public void severalInstructionsInOneFeedAreAllApplied() throws QpackException {
		QpackEncoderStreamReader reader = reader(CAPACITY, ROOMY);
		ByteBuf capacity = QpackInstructions.encode(new SetDynamicTableCapacity(CAPACITY));
		ByteBuf insert = QpackInstructions.encode(
			new InsertWithNameReference(true, 0, encodeAscii("www.example.com")));
		ByteBuf both = ByteBufPool.append(capacity, insert);

		assertEquals(2, reader.feed(both));
		assertEquals(2, reader.instructionsApplied());
		assertEquals(1, decoder.insertCount());
	}

	@Test(timeout = TIMEOUT_MS)
	public void recycleReleasesTheRetainedRemainderAndIsIdempotent() throws QpackException {
		QpackEncoderStreamReader reader = started();
		// One byte of an Insert With Literal Name that never completes.
		assertEquals(0, reader.feed(bytes(0x4a)));
		assertEquals(1, reader.pendingBytes());

		reader.recycle();
		assertEquals(0, reader.pendingBytes());
		reader.recycle();
	}

	// ---------------------------------------------------------------- what the table must carry

	@Test(timeout = TIMEOUT_MS)
	public void aLiteralNameCarriesItsUppercaseSpellingIntoTheTable() throws QpackException {
		QpackEncoderStreamReader reader = started();
		reader.feed(QpackInstructions.encode(new InsertWithLiteralName(encodeAscii("Foo"), encodeAscii("bar"))));

		// RFC 9114 §4.1.1 is enforced by Http3Headers.fromQpack, which only ever sees the interned token
		// — so if the table forgets this, an uppercase name laundered through an insertion gets through.
		assertTrue(decoder.table().nameHadUppercaseAt(0));

		reader.feed(QpackInstructions.encode(new InsertWithLiteralName(encodeAscii("baz"), encodeAscii("bar"))));
		assertFalse(decoder.table().nameHadUppercaseAt(1));
	}

	@Test(timeout = TIMEOUT_MS)
	public void aDuplicateCarriesTheUppercaseSpellingOfWhatItCopies() throws QpackException {
		QpackEncoderStreamReader reader = started();
		reader.feed(QpackInstructions.encode(new InsertWithLiteralName(encodeAscii("Foo"), encodeAscii("bar"))));
		reader.feed(QpackInstructions.encode(new Duplicate(0)));

		assertTrue(decoder.table().nameHadUppercaseAt(1));
	}
}
