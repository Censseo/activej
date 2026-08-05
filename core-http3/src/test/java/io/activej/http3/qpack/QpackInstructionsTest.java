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
import io.activej.http3.Http3Errors;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.Duplicate;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.InsertWithLiteralName;
import io.activej.http3.qpack.QpackInstructions.InsertWithNameReference;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Round-trip and byte-exact vectors for all seven RFC 9204 instruction forms — four on the encoder
 * stream (§4.3) and three on the decoder stream (§4.4).
 * <p>
 * The boundary values exercised per form are the ones where an N-bit prefix integer changes shape:
 * {@code 2^N - 2} (last value that fits in the prefix octet), {@code 2^N - 1} (first value that
 * spills into a continuation byte), {@code 2^N + 126} (last single-continuation-byte value) and
 * {@code 2^N + 127} (first two-continuation-byte value), plus 0 and
 * {@link QpackIntegers#MAX_VALUE}.
 */
public class QpackInstructionsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// 5-bit prefix (Set Dynamic Table Capacity, Duplicate, Insert With Literal Name's name length).
	private static final long[] FIVE_BIT = {0, 1, 30, 31, 32, 158, 159, 1337, 100_000, QpackIntegers.MAX_VALUE};

	// 6-bit prefix (Insert With Name Reference index, Stream Cancellation, Insert Count Increment).
	private static final long[] SIX_BIT = {0, 1, 62, 63, 64, 190, 191, 1337, 100_000, QpackIntegers.MAX_VALUE};

	// 7-bit prefix (Section Acknowledgment stream id, and every value-string length).
	private static final long[] SEVEN_BIT = {0, 1, 126, 127, 128, 254, 255, 1337, 100_000, QpackIntegers.MAX_VALUE};

	// ------------------------------------------------------------------ encoder stream, RFC 9204 §4.3

	@Test
	public void setDynamicTableCapacityRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long capacity : FIVE_BIT) {
			assertEncoderRoundTrip(new SetDynamicTableCapacity(capacity));
		}
	}

	@Test
	public void duplicateRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long index : FIVE_BIT) {
			assertEncoderRoundTrip(new Duplicate(index));
		}
	}

	@Test
	public void insertWithNameReferenceRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long nameIndex : SIX_BIT) {
			for (boolean staticTable : new boolean[] {true, false}) {
				assertEncoderRoundTrip(new InsertWithNameReference(staticTable, nameIndex, ascii("value")));
			}
		}
	}

	@Test
	public void insertWithNameReferenceRoundTripsAcrossValueLengthBoundaries() throws QpackException {
		for (int length : new int[] {0, 1, 126, 127, 128, 254, 255, 1000}) {
			assertEncoderRoundTrip(new InsertWithNameReference(true, 3, incompressible(length)));
		}
	}

	@Test
	public void insertWithLiteralNameRoundTripsAcrossBothLengthBoundaries() throws QpackException {
		for (int nameLength : new int[] {1, 30, 31, 32, 158, 159}) {
			for (int valueLength : new int[] {0, 1, 126, 127, 128, 254, 255}) {
				assertEncoderRoundTrip(new InsertWithLiteralName(incompressible(nameLength), incompressible(valueLength)));
			}
		}
	}

	// ------------------------------------------------------------------ decoder stream, RFC 9204 §4.4

	@Test
	public void sectionAcknowledgmentRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long streamId : SEVEN_BIT) {
			assertDecoderRoundTrip(new SectionAcknowledgment(streamId));
		}
	}

	@Test
	public void streamCancellationRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long streamId : SIX_BIT) {
			assertDecoderRoundTrip(new StreamCancellation(streamId));
		}
	}

	@Test
	public void insertCountIncrementRoundTripsAcrossPrefixBoundaries() throws QpackException {
		for (long increment : SIX_BIT) {
			assertDecoderRoundTrip(new InsertCountIncrement(increment));
		}
	}

	// ------------------------------------------------------------------ byte-exact wire layout

	@Test
	public void encodedBytesMatchTheRfcLayout() {
		// The single byte phase 1 accepts as the only legal encoder-stream instruction.
		assertWire(new SetDynamicTableCapacity(0), 0x20);
		// RFC 9204 Appendix B.2 — Set Dynamic Table Capacity = 220.
		assertWire(new SetDynamicTableCapacity(220), 0x3f, 0xbd, 0x01);
		assertWire(new Duplicate(0), 0x00);
		assertWire(new Duplicate(2), 0x02);
		assertWire(new SectionAcknowledgment(0), 0x80);
		assertWire(new SectionAcknowledgment(4), 0x84);
		assertWire(new StreamCancellation(0), 0x40);
		assertWire(new StreamCancellation(8), 0x48);
		assertWire(new InsertCountIncrement(0), 0x00);
		assertWire(new InsertCountIncrement(1), 0x01);

		// "1 T=1 index=0" then "H=1 length=12" then the RFC 7541 C.4.1 Huffman form of the value.
		assertWire(new InsertWithNameReference(true, 0, ascii("www.example.com")),
			0xc0, 0x8c, 0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff);
	}

	/**
	 * The encoder-stream byte sequence of RFC 9204 Appendix B, which uses the literal (non-Huffman)
	 * string form throughout — the form this encoder does not choose, and therefore the one only a
	 * decode-direction test can cover.
	 */
	@Test
	public void rfcAppendixBEncoderStreamDecodes() throws QpackException {
		ByteBuf buf = wire(
			0x3f, 0xbd, 0x01,
			0xc0, 0x0f, "www.example.com",
			0xc1, 0x0c, "/sample/path",
			0x4a, "custom-key", 0x0c, "custom-value",
			0x81, 0x0d, "custom-value2",
			0x02);

		assertEquals(new SetDynamicTableCapacity(220), QpackInstructions.readEncoderInstruction(buf));
		assertEquals(new InsertWithNameReference(true, 0, ascii("www.example.com")),
			QpackInstructions.readEncoderInstruction(buf));
		assertEquals(new InsertWithNameReference(true, 1, ascii("/sample/path")),
			QpackInstructions.readEncoderInstruction(buf));
		assertEquals(new InsertWithLiteralName(ascii("custom-key"), ascii("custom-value")),
			QpackInstructions.readEncoderInstruction(buf));
		assertEquals(new InsertWithNameReference(false, 1, ascii("custom-value2")),
			QpackInstructions.readEncoderInstruction(buf));
		assertEquals(new Duplicate(2), QpackInstructions.readEncoderInstruction(buf));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	@Test
	public void severalDecoderInstructionsDecodeBackToBackFromOneBuffer() throws QpackException {
		ByteBuf buf = wire(0x84, 0x48, 0x01);
		assertEquals(new SectionAcknowledgment(4), QpackInstructions.readDecoderInstruction(buf));
		assertEquals(new StreamCancellation(8), QpackInstructions.readDecoderInstruction(buf));
		assertEquals(new InsertCountIncrement(1), QpackInstructions.readDecoderInstruction(buf));
		assertFalse(buf.canRead());
		buf.recycle();
	}

	@Test
	public void huffmanIsChosenOnlyWhenItShortens() {
		ByteBuf compressible = QpackInstructions.encode(new InsertWithNameReference(true, 0, ascii("www.example.com")));
		assertEquals(0x80, compressible.array()[compressible.head() + 1] & 0x80);
		compressible.recycle();

		ByteBuf incompressible = QpackInstructions.encode(new InsertWithNameReference(true, 0, incompressible(4)));
		assertEquals(0, incompressible.array()[incompressible.head() + 1] & 0x80);
		incompressible.recycle();

		ByteBuf literalName = QpackInstructions.encode(new InsertWithLiteralName(ascii("custom-key"), incompressible(4)));
		// Insert With Literal Name carries its H bit at 0x20, not 0x80.
		assertEquals(0x20, literalName.array()[literalName.head()] & 0x20);
		literalName.recycle();
	}

	// ------------------------------------------------------------------ incomplete input

	@Test
	public void anEmptyBufferReadsAsNull() throws QpackException {
		ByteBuf empty = ByteBuf.wrapForReading(new byte[0]);
		assertNull(QpackInstructions.readEncoderInstruction(empty));
		assertNull(QpackInstructions.readDecoderInstruction(empty));
		empty.recycle();
	}

	@Test
	public void everyProperPrefixOfAnInstructionReadsAsNullAndConsumesNothing() throws QpackException {
		EncoderInstruction[] instructions = {
			new SetDynamicTableCapacity(100_000),
			new Duplicate(159),
			new InsertWithNameReference(true, 191, ascii("www.example.com")),
			new InsertWithLiteralName(incompressible(40), incompressible(200)),
		};
		for (EncoderInstruction instruction : instructions) {
			byte[] whole = toBytes(instruction);
			for (int prefix = 0; prefix < whole.length; prefix++) {
				ByteBuf partial = ByteBuf.wrapForReading(java.util.Arrays.copyOf(whole, prefix));
				assertNull("prefix=" + prefix + " of " + instruction, QpackInstructions.readEncoderInstruction(partial));
				assertEquals("prefix=" + prefix + " of " + instruction, prefix, partial.readRemaining());
				partial.recycle();
			}
		}
	}

	@Test
	public void aTruncatedDecoderInstructionReadsAsNullAndConsumesNothing() throws QpackException {
		// Stream Cancellation with a 6-bit prefix marker and a continuation byte that never terminates.
		ByteBuf buf = wire(0x7f, 0x80);
		assertNull(QpackInstructions.readDecoderInstruction(buf));
		assertEquals(2, buf.readRemaining());
		buf.recycle();
	}

	// ------------------------------------------------------------------ malformed input

	@Test
	public void aMalformedPrefixIntegerIsAnEncoderStreamConnectionError() {
		ByteBuf buf = wire(0x3f,
			0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00);
		QpackException e = assertThrows(QpackException.class, () -> QpackInstructions.readEncoderInstruction(buf));
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, e.errorCode());
		assertTrue(e.isConnectionError());
		buf.recycle();
	}

	@Test
	public void aMalformedPrefixIntegerIsADecoderStreamConnectionError() {
		ByteBuf buf = wire(0x7f,
			0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00);
		QpackException e = assertThrows(QpackException.class, () -> QpackInstructions.readDecoderInstruction(buf));
		assertEquals(Http3Errors.QPACK_DECODER_STREAM_ERROR, e.errorCode());
		assertTrue(e.isConnectionError());
		buf.recycle();
	}

	@Test
	public void aMalformedHuffmanStringIsAnEncoderStreamConnectionError() {
		// Insert With Literal Name, H=1, name length 2, then two whole bytes of 1-bits: 16 leftover
		// bits that never complete the EOS code, so the padding is structurally invalid.
		ByteBuf buf = wire(0x62, 0xff, 0xff);
		QpackException e = assertThrows(QpackException.class, () -> QpackInstructions.readEncoderInstruction(buf));
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, e.errorCode());
		assertTrue(e.isConnectionError());
		buf.recycle();
	}

	/** Re-scoping a primitive's failure to its stream must add a code, not lose the cause (FR-032). */
	@Test
	public void aRescopedFailureKeepsTheCauseInItsReason() {
		ByteBuf buf = wire(0x62, 0xff, 0xff);
		QpackException e = assertThrows(QpackException.class, () -> QpackInstructions.readEncoderInstruction(buf));
		assertTrue(e.reason(), e.reason().contains("padding"));
		buf.recycle();
	}

	/** FR-063: a value type that a debug line may print must not carry the octets it holds. */
	@Test
	public void toStringCarriesLengthsRatherThanContents() {
		assertFalse(new InsertWithLiteralName(ascii("secret-name"), ascii("secret-value")).toString()
			.contains("secret"));
		assertFalse(new InsertWithNameReference(true, 0, ascii("secret-value")).toString()
			.contains("secret"));
	}

	// ------------------------------------------------------------------ helpers

	private static void assertEncoderRoundTrip(EncoderInstruction original) throws QpackException {
		ByteBuf buf = QpackInstructions.encode(original);
		assertEquals(original.toString(), original.encodedLength(), buf.readRemaining());
		assertEquals(original, QpackInstructions.readEncoderInstruction(buf));
		assertFalse(original.toString(), buf.canRead());
		buf.recycle();
	}

	private static void assertDecoderRoundTrip(DecoderInstruction original) throws QpackException {
		ByteBuf buf = QpackInstructions.encode(original);
		assertEquals(original.toString(), original.encodedLength(), buf.readRemaining());
		assertEquals(original, QpackInstructions.readDecoderInstruction(buf));
		assertFalse(original.toString(), buf.canRead());
		buf.recycle();
	}

	private static void assertWire(QpackInstructions.Instruction instruction, int... expected) {
		byte[] actual = toBytes(instruction);
		byte[] want = new byte[expected.length];
		for (int i = 0; i < expected.length; i++) {
			want[i] = (byte) expected[i];
		}
		assertArrayEquals(instruction.toString(), want, actual);
		assertEquals(instruction.toString(), expected.length, instruction.encodedLength());
	}

	private static byte[] toBytes(QpackInstructions.Instruction instruction) {
		ByteBuf buf = QpackInstructions.encode(instruction);
		byte[] bytes = new byte[buf.readRemaining()];
		buf.read(bytes);
		buf.recycle();
		return bytes;
	}

	private static ByteBuf wire(Object... parts) {
		ByteBuf buf = ByteBufPool.allocate(1024);
		for (Object part : parts) {
			if (part instanceof String s) {
				buf.write(ascii(s));
			} else {
				buf.writeByte(((Integer) part).byteValue());
			}
		}
		return buf;
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	/** {@code n} copies of {@code '$'} — a 13-bit Huffman symbol, so the literal form is always shorter. */
	private static byte[] incompressible(int n) {
		byte[] bytes = new byte[n];
		java.util.Arrays.fill(bytes, (byte) '$');
		return bytes;
	}
}
