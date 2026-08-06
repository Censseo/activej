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
import io.activej.http3.qpack.QpackInstructions.Instruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Spec FR-030: every inbound RFC 9204 §4.4 decoder-stream failure closes the connection with
 * {@code QPACK_DECODER_STREAM_ERROR} (0x0202) and nothing else — the scope stays per cause (FR-032) —
 * plus the legal paths that must <b>not</b> fail, and the Known Received Count each of them moves.
 * <p>
 * Bytes go in through {@link QpackDynamicEncoder#consumeDecoderStream}, which is what
 * {@code Http3Connection} will feed. Every buffer is recycled by this test on both the throwing and
 * the returning path, since that method owns nothing (DI-1).
 * <p>
 * No {@code EventloopRule}: this package is synchronous (ADR-016).
 */
public class QpackDecoderStreamTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Set<String> DEFAULT_NEVER_INDEXED =
		Set.of("authorization", "proxy-authorization", "set-cookie");

	private static final int CAPACITY = 4096;
	private static final int SECTIONS = 64;

	private static final long STREAM_0 = 0;
	private static final long STREAM_4 = 4;

	private static QpackDynamicEncoder encoder() {
		return new QpackDynamicEncoder(CAPACITY, CAPACITY, 8, SECTIONS, DEFAULT_NEVER_INDEXED);
	}

	private static void emitSection(QpackDynamicEncoder encoder, long streamId, String name, String value) {
		encoder.encode(streamId, List.of(new QpackField(HttpHeaders.of(name), encodeAscii(value)))).recycle();
	}

	// ------------------------------------------------------------------- FR-030, the four failures

	@Test
	public void anAcknowledgmentForAStreamWithNoOutstandingSectionIsADecoderStreamError() {
		assertDecoderStreamError(encoder(), new SectionAcknowledgment(STREAM_0));
	}

	@Test
	public void aSecondAcknowledgmentForTheSameSectionIsADecoderStreamError() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		feed(encoder, new SectionAcknowledgment(STREAM_0));
		assertDecoderStreamError(encoder, new SectionAcknowledgment(STREAM_0));
	}

	@Test
	public void anInsertCountIncrementOfZeroIsADecoderStreamError() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		assertDecoderStreamError(encoder, new InsertCountIncrement(0));
	}

	@Test
	public void anIncrementPastTheLocalInsertCountIsADecoderStreamError() {
		assertDecoderStreamError(encoder(), new InsertCountIncrement(1));
	}

	@Test
	public void anIncrementOnlyOneAboveTheLocalInsertCountIsADecoderStreamError() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		assertDecoderStreamError(encoder, new InsertCountIncrement(2));
	}

	/**
	 * The increment is checked as {@code increment > insertCount - knownReceivedCount}, never as
	 * {@code knownReceivedCount + increment > insertCount}: at a wire-supplied 2^62 the latter wraps
	 * negative and the bound passes.
	 */
	@Test
	public void aHugeIncrementDoesNotWrapPastTheCheck() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		assertDecoderStreamError(encoder, new InsertCountIncrement(QpackIntegers.MAX_VALUE));
		assertEquals(0, encoder.knownReceivedCount());
	}

	@Test
	public void aMalformedPrefixIntegerIsADecoderStreamError() {
		byte[] overlongContinuation = new byte[13];
		overlongContinuation[0] = 0x3F; // Insert Count Increment, prefix saturated
		for (int i = 1; i < overlongContinuation.length - 1; i++) {
			overlongContinuation[i] = (byte) 0x80;
		}
		assertDecoderStreamError(encoder(), overlongContinuation);
	}

	// ------------------------------------------------------------------------------ the legal paths

	@Test
	public void aStreamCancellationForAnUntrackedStreamIsNotAnError() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		feed(encoder, new StreamCancellation(STREAM_4));
		assertEquals(0, encoder.knownReceivedCount());
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void anAcknowledgmentRaisesTheKnownReceivedCount() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");
		assertEquals(0, encoder.knownReceivedCount());
		assertEquals(2, encoder.blockedStreamCount());

		feed(encoder, new SectionAcknowledgment(STREAM_4));
		assertEquals(2, encoder.knownReceivedCount());
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void anAcknowledgmentNeverLowersTheKnownReceivedCount() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");
		feed(encoder, new SectionAcknowledgment(STREAM_4));
		feed(encoder, new SectionAcknowledgment(STREAM_0));
		assertEquals(2, encoder.knownReceivedCount());
	}

	@Test
	public void anInsertCountIncrementRaisesTheKnownReceivedCount() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");
		feed(encoder, new InsertCountIncrement(1));
		assertEquals(1, encoder.knownReceivedCount());
		feed(encoder, new InsertCountIncrement(1));
		assertEquals(2, encoder.knownReceivedCount());
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void aStreamCancellationUnblocksThatStreamOnly() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");
		assertEquals(2, encoder.blockedStreamCount());

		feed(encoder, new StreamCancellation(STREAM_0));
		assertEquals(1, encoder.blockedStreamCount());
		assertEquals(0, encoder.knownReceivedCount());
	}

	@Test
	public void severalInstructionsInOneBufferAreAllApplied() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");

		ByteBuf buf = ByteBufPool.allocate(32);
		try {
			new SectionAcknowledgment(STREAM_0).writeTo(buf);
			new StreamCancellation(STREAM_4).writeTo(buf);
			encoder.consumeDecoderStream(buf);
			assertFalse(buf.canRead());
		} finally {
			buf.recycle();
		}
		assertEquals(1, encoder.knownReceivedCount());
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void aPartialInstructionConsumesNothingAndIsNotAnError() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		ByteBuf buf = ByteBufPool.allocate(8);
		try {
			buf.writeByte((byte) 0xFF); // Section Acknowledgment, prefix saturated
			buf.writeByte((byte) 0x80); // a continuation byte that never terminates
			encoder.consumeDecoderStream(buf);
			assertEquals(2, buf.readRemaining());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void anEmptyBufferIsNotAnError() throws QpackException {
		QpackDynamicEncoder encoder = encoder();
		ByteBuf buf = ByteBufPool.allocate(8);
		try {
			encoder.consumeDecoderStream(buf);
		} finally {
			buf.recycle();
		}
		assertEquals(0, encoder.knownReceivedCount());
	}

	// -------------------------------------------------------------------- the local release paths

	@Test
	public void aLocalStreamCancellationIsIdempotentAndNeverThrows() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		encoder.onStreamCancelled(STREAM_0);
		encoder.onStreamCancelled(STREAM_0);
		encoder.onStreamCancelled(STREAM_4);
		assertEquals(0, encoder.blockedStreamCount());
	}

	@Test
	public void releaseAllReportsEverySectionItReleased() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		emitSection(encoder, STREAM_4, "x-beta", "2");
		assertEquals(2, encoder.releaseAll());
		assertEquals(0, encoder.blockedStreamCount());
		assertEquals(0, encoder.releaseAll());
	}

	@Test
	public void aCancelledStreamCanNoLongerBeAcknowledged() {
		QpackDynamicEncoder encoder = encoder();
		emitSection(encoder, STREAM_0, "x-alpha", "1");
		encoder.onStreamCancelled(STREAM_0);
		assertDecoderStreamError(encoder, new SectionAcknowledgment(STREAM_0));
	}

	// --------------------------------------------------------------------------------- assertions

	private static void feed(QpackDynamicEncoder encoder, Instruction instruction) throws QpackException {
		ByteBuf buf = QpackInstructions.encode(instruction);
		try {
			encoder.consumeDecoderStream(buf);
		} finally {
			buf.recycle();
		}
	}

	private static void assertDecoderStreamError(QpackDynamicEncoder encoder, Instruction instruction) {
		assertDecoderStreamError(encoder, QpackInstructions.encode(instruction).asArray());
	}

	private static void assertDecoderStreamError(QpackDynamicEncoder encoder, byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(bytes.length, 1));
		QpackException e;
		try {
			buf.write(bytes);
			e = assertThrows(QpackException.class, () -> encoder.consumeDecoderStream(buf));
		} finally {
			buf.recycle();
		}
		assertEquals(Http3Errors.QPACK_DECODER_STREAM_ERROR, e.errorCode());
		assertTrue("RFC 9204 §6 scopes a decoder-stream failure to the connection", e.isConnectionError());
		if (e.reason() == null || e.reason().isBlank()) {
			fail("a QPACK failure must name the offending protocol element");
		}
	}
}
