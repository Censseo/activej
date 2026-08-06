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
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The RFC 9204 §4.5.1 encoded field section prefix, now that it carries something (T021).
 * <p>
 * Phase 1 wrote Required Insert Count 0 / Delta Base 0 and rejected anything else; every failure
 * below is a way the reconstruction of §4.5.1.1 can fail against a real Insert Count, and each must
 * close the <b>connection</b> with {@code QPACK_DECOMPRESSION_FAILED} (0x0200) — the per-cause scope
 * table in {@code docs/http/spec.md}, unchanged by this feature (FR-031, FR-032).
 * <p>
 * A truncation is the deliberate exception: the section is undecodable but the format is not in
 * dispute, so it stays stream-scoped exactly as {@code QpackAdversarialTest} pins it for phase 1.
 */
public class QpackSectionPrefixTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long TIMEOUT_MS = 5_000;

	/** The Appendix B capacity: {@code MaxEntries = floor(220 / 32) = 6}, {@code FullRange = 12}. */
	private static final int CAPACITY = 220;

	private static QpackDynamicDecoder decoder() {
		return decoder(CAPACITY, 0);
	}

	private static QpackDynamicDecoder decoder(int maxCapacity, int blockedStreams) {
		return new QpackDynamicDecoder(maxCapacity, blockedStreams, Long.MAX_VALUE);
	}

	/** Inserts {@code entries} distinct entries straight into the decoder's table, as the peer's encoder stream would. */
	private static QpackDynamicDecoder filled(int entries) {
		QpackDynamicDecoder decoder = decoder();
		decoder.table().setCapacity(CAPACITY);
		for (int i = 0; i < entries; i++) {
			decoder.table().insert(HttpHeaders.of("x" + i), encodeAscii("v" + i));
		}
		return decoder;
	}

	/** A pooled buffer — so {@link ByteBufRule} really sees a leak — over {@code Integer} octets and ASCII strings. */
	private static ByteBuf buf(Object... parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (Object part : parts) {
			if (part instanceof Integer octet) {
				bytes.write(octet);
			} else {
				byte[] ascii = encodeAscii((String) part);
				bytes.write(ascii, 0, ascii.length);
			}
		}
		byte[] array = bytes.toByteArray();
		ByteBuf buf = ByteBufPool.allocate(Math.max(array.length, 1));
		buf.put(array);
		return buf;
	}

	private static QpackException assertRejected(QpackDynamicDecoder decoder, Object... parts) {
		QpackException e = assertThrows(QpackException.class, () -> decoder.decode(buf(parts)));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		return e;
	}

	private static void assertConnectionScoped(QpackDynamicDecoder decoder, Object... parts) {
		assertTrue("expected a connection-scoped failure", assertRejected(decoder, parts).isConnectionError());
	}

	private static void assertStreamScoped(QpackDynamicDecoder decoder, Object... parts) {
		assertFalse("expected a stream-scoped failure", assertRejected(decoder, parts).isConnectionError());
	}

	// ---------------------------------------------------------------- the prefix that decodes

	@Test(timeout = TIMEOUT_MS)
	public void staticOnlySectionAtZeroPrefixDecodes() throws QpackException {
		// RFC 9204 Appendix B.1: Required Insert Count 0, Base 0, then a static name reference.
		SectionResult result = decoder().decodeOrBlock(buf(0x00, 0x00, 0x51, 0x0b, "/index.html"));

		Decoded decoded = (Decoded) result;
		assertEquals(0, decoded.requiredInsertCount());
		assertEquals(List.of(new QpackField(HttpHeaders.of(":path"), encodeAscii("/index.html"))), decoded.fields());
	}

	@Test(timeout = TIMEOUT_MS)
	public void postBaseAndRelativeIndicesResolveAgainstTheBase() throws QpackException {
		QpackDynamicDecoder decoder = filled(2);

		// Encoded Insert Count 3 -> Required Insert Count 2; S=1, Delta Base 1 -> Base = 2 - 1 - 1 = 0.
		// Then two Indexed Field Lines With Post-Base Index: Base + 0 and Base + 1.
		Decoded decoded = (Decoded) decoder.decodeOrBlock(buf(0x03, 0x81, 0x10, 0x11));

		assertEquals(2, decoded.requiredInsertCount());
		assertEquals(
			List.of(
				new QpackField(HttpHeaders.of("x0"), encodeAscii("v0")),
				new QpackField(HttpHeaders.of("x1"), encodeAscii("v1"))),
			decoded.fields());
	}

	@Test(timeout = TIMEOUT_MS)
	public void relativeIndexResolvesAgainstTheBaseWithoutSign() throws QpackException {
		QpackDynamicDecoder decoder = filled(2);

		// Encoded Insert Count 3 -> Required Insert Count 2; S=0, Delta Base 0 -> Base = 2.
		// Indexed Field Line, dynamic, relative 0 -> absolute = 2 - 0 - 1 = 1.
		Decoded decoded = (Decoded) decoder.decodeOrBlock(buf(0x03, 0x00, 0x80));

		assertEquals(List.of(new QpackField(HttpHeaders.of("x1"), encodeAscii("v1"))), decoded.fields());
	}

	@Test(timeout = TIMEOUT_MS)
	public void neverIndexedLiteralIsSurfacedAsSuch() throws QpackException {
		// "001" N=1 H=0, name length 3 -> 0x33; then a plain value of length 3.
		Decoded decoded = (Decoded) decoder().decodeOrBlock(buf(0x00, 0x00, 0x33, "foo", 0x03, "bar"));

		QpackField field = decoded.fields().get(0);
		assertEquals(HttpHeaders.of("foo"), field.name());
		assertEquals("bar", new String(field.value(), java.nio.charset.StandardCharsets.US_ASCII));
		assertTrue("RFC 9204 §7.1: the N bit must survive the decode", field.neverIndexed());
	}

	// ---------------------------------------------------------------- Required Insert Count

	@Test(timeout = TIMEOUT_MS)
	public void encodedInsertCountAboveFullRangeIsAConnectionError() {
		// FullRange = 2 * MaxEntries = 12; 13 cannot name any insert count.
		assertConnectionScoped(decoder(), 0x0d, 0x00);
	}

	@Test(timeout = TIMEOUT_MS)
	public void nonZeroEncodedInsertCountAtCapacityZeroIsAConnectionError() {
		// MaxEntries is 0, so FullRange is 0: the reconstruction's divisor. The check must come first,
		// or this is an ArithmeticException rather than a protocol error.
		assertConnectionScoped(decoder(0, 0), 0x01, 0x00);
	}

	@Test(timeout = TIMEOUT_MS)
	public void requiredInsertCountAboveTheInsertCountIsAConnectionErrorAtBlockedStreamsZero() {
		// Encoded 3 -> Required Insert Count 2, against an Insert Count of 0. RFC 9204 §2.1.2: a section
		// that would block when the advertised limit is 0 is a connection error (FR-031).
		assertConnectionScoped(decoder(), 0x03, 0x00, 0x80);
	}

	@Test(timeout = TIMEOUT_MS)
	public void truncatedRequiredInsertCountIsStreamScoped() {
		// An 8-bit prefix of all ones demands a continuation byte that never arrives.
		assertStreamScoped(decoder(), 0xff);
	}

	@Test(timeout = TIMEOUT_MS)
	public void emptySectionIsStreamScoped() {
		assertStreamScoped(decoder());
	}

	// ---------------------------------------------------------------- Base

	@Test(timeout = TIMEOUT_MS)
	public void baseUnderflowAtRequiredInsertCountZeroIsAConnectionError() {
		// S=1, Delta Base 0 -> Base = 0 - 0 - 1 = -1.
		assertConnectionScoped(decoder(), 0x00, 0x80);
	}

	@Test(timeout = TIMEOUT_MS)
	public void baseUnderflowAtNonZeroRequiredInsertCountIsAConnectionError() {
		// Required Insert Count 2; S=1, Delta Base 2 -> Base = 2 - 2 - 1 = -1.
		assertConnectionScoped(filled(2), 0x03, 0x82);
	}

	@Test(timeout = TIMEOUT_MS)
	public void truncatedDeltaBaseIsStreamScoped() {
		assertStreamScoped(decoder(), 0x00);
	}

	// ---------------------------------------------------------------- dynamic references

	@Test(timeout = TIMEOUT_MS)
	public void referenceToAnAbsoluteIndexThatNeverExistedIsAConnectionError() {
		// Base 0, Indexed Field Line dynamic relative 0 -> absolute = -1.
		assertConnectionScoped(decoder(), 0x00, 0x00, 0x80);
	}

	@Test(timeout = TIMEOUT_MS)
	public void referenceAtOrAboveTheRequiredInsertCountIsAConnectionError() {
		// RFC 9204 §4.5.1.2: a reference at or above the declared Required Insert Count is a connection
		// error even when the entry is present — Required Insert Count 2, Base 2, post-base 0 -> absolute 2.
		assertConnectionScoped(filled(3), 0x03, 0x00, 0x10);
	}

	@Test(timeout = TIMEOUT_MS)
	public void referenceToAnEvictedEntryIsAConnectionError() {
		QpackDynamicDecoder decoder = decoder();
		decoder.table().setCapacity(CAPACITY);
		// Four entries of 32 + 10 + 20 = 62 bytes: the fourth evicts the first at a capacity of 220.
		for (int i = 0; i < 4; i++) {
			decoder.table().insert(HttpHeaders.of("xxxxxxxxx" + i), encodeAscii("vvvvvvvvvvvvvvvvvvv" + i));
		}
		assertFalse(decoder.table().isAvailable(0));

		// Encoded 5 -> Required Insert Count 4; S=0, Delta Base 0 -> Base 4; relative 3 -> absolute 0.
		assertConnectionScoped(decoder, 0x05, 0x00, 0x83);
	}

	@Test(timeout = TIMEOUT_MS)
	public void literalWithADynamicNameReferenceOutOfRangeIsAConnectionError() {
		// "01" N=0 T=0, relative index 0, against an empty table -> absolute = -1.
		assertConnectionScoped(decoder(), 0x00, 0x00, 0x40, 0x00);
	}

	// ---------------------------------------------------------------- the blocked seam

	@Test(timeout = TIMEOUT_MS)
	public void aBlockedSectionIsHandedBackWholeWhenBlockingIsAllowed() throws QpackException {
		ByteBuf section = buf(0x03, 0x00, 0x80);
		int length = section.readRemaining();

		SectionResult result = decoder(CAPACITY, 1).decodeOrBlock(section);

		Blocked blocked = (Blocked) result;
		assertEquals(2, blocked.requiredInsertCount());
		assertEquals("nothing may be consumed: the caller re-enters through decodeOrBlock",
			length, blocked.section().readRemaining());
		assertEquals(0x03, blocked.section().peek() & 0xFF);
		blocked.section().recycle();
	}

	@Test(timeout = TIMEOUT_MS)
	public void decodeRefusesAndRecyclesASectionDecodeOrBlockWouldHold() {
		// decode() is the non-blocking entry point of the frozen QpackDecoder contract: it owns its
		// input on every path, so a section it cannot decode is released here rather than held.
		assertConnectionScoped(decoder(CAPACITY, 1), 0x03, 0x00, 0x80);
	}

	// ---------------------------------------------------------------- the counters the connection reads

	@Test(timeout = TIMEOUT_MS)
	public void insertCountIncrementIsWhatTheEncoderHasNotBeenToldAbout() {
		QpackDynamicDecoder decoder = filled(3);
		assertEquals(3, decoder.insertCount());
		assertEquals(3, decoder.pendingInsertCountIncrement());

		decoder.onInsertCountAnnounced(2);
		assertEquals(1, decoder.pendingInsertCountIncrement());

		// Never lowers: a Section Acknowledgment for an older section must not un-announce an insertion.
		decoder.onInsertCountAnnounced(1);
		assertEquals(1, decoder.pendingInsertCountIncrement());

		decoder.onInsertCountAnnounced(3);
		assertEquals(0, decoder.pendingInsertCountIncrement());
	}

	@Test(timeout = TIMEOUT_MS)
	public void theAdvertisedSettingsAreReadableBack() {
		QpackDynamicDecoder decoder = decoder(CAPACITY, 4);
		assertEquals(CAPACITY, decoder.maxCapacity());
		assertEquals(4, decoder.blockedStreams());
		// RFC 9204 §3.2.3: the dynamic table capacity starts at 0 and only Set Dynamic Table Capacity
		// raises it — the advertised maximum is a ceiling, not an initial value.
		assertEquals(0, decoder.capacity());
	}
}
