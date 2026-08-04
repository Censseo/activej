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
import io.activej.http3.Http3Errors;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Every representation this implementation must reject because it would need a dynamic table
 * (FR-031, SC-010), plus a truncated prefix. Each case -> {@code QPACK_DECOMPRESSION_FAILED}, no
 * leak ({@link ByteBufRule} fails the build otherwise — the decoder's {@code finally} recycles the
 * input on every path) and no hang (each test carries a timeout).
 */
public class QpackAdversarialTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long TIMEOUT_MS = 5_000;

	private final QpackStaticDecoder decoder = new QpackStaticDecoder(Long.MAX_VALUE);

	private QpackException assertRejected(byte[] bytes) {
		QpackException e = assertThrows(QpackException.class, () -> decoder.decode(ByteBuf.wrapForReading(bytes)));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
		return e;
	}

	/**
	 * The peer's encoder and this decoder disagree about the compression format itself, so nothing that
	 * follows on <b>any</b> stream is trustworthy: RFC 9204 §2.2.3, §3.1 and §4.5.1 all require (or, for
	 * a larger-than-expected Required Insert Count, permit) a connection error.
	 */
	private void assertConnectionScoped(byte[] bytes) {
		assertTrue("expected a connection-scoped failure", assertRejected(bytes).isConnectionError());
	}

	/**
	 * A local limit or a bounded truncation: the field section is undecodable, but the format is not in
	 * dispute and the static table holds no cross-section state, so only this stream dies (RFC 9204 §7).
	 */
	private void assertStreamScoped(byte[] bytes) {
		assertFalse("expected a stream-scoped failure", assertRejected(bytes).isConnectionError());
	}

	@Test(timeout = TIMEOUT_MS)
	public void nonZeroRequiredInsertCountIsRejected() {
		// RFC 9204 §4.5.1: larger than expected MAY be a connection error, and with no dynamic table the
		// expected value is always 0 — the peer believes we share a table.
		assertConnectionScoped(new byte[] {0x01, 0x00});
	}

	@Test(timeout = TIMEOUT_MS)
	public void indexedFieldLineWithDynamicTableReferenceIsRejected() {
		// prefix (RIC=0, S/DeltaBase=0), then "1 0 index(6+)": T=0, dynamic.
		assertConnectionScoped(new byte[] {0x00, 0x00, (byte) 0x80});
	}

	@Test(timeout = TIMEOUT_MS)
	public void literalWithNameReferenceDynamicTableReferenceIsRejected() {
		// prefix, then "0 1 N 0 index(4+)": T=0, dynamic.
		assertConnectionScoped(new byte[] {0x00, 0x00, 0x40});
	}

	@Test(timeout = TIMEOUT_MS)
	public void postBaseIndexIsRejected() {
		// prefix, then "0 0 0 1 index(4+)".
		assertConnectionScoped(new byte[] {0x00, 0x00, 0x10});
	}

	@Test(timeout = TIMEOUT_MS)
	public void postBaseNameReferenceIsRejected() {
		// prefix, then "0 0 0 0 N index(3+)".
		assertConnectionScoped(new byte[] {0x00, 0x00, 0x00});
	}

	/**
	 * RFC 9204 §3.1: "When the decoder encounters an invalid static table index in a field line
	 * representation, it MUST treat this as a connection error of type QPACK_DECOMPRESSION_FAILED."
	 * <p>
	 * This one bites <b>today</b>, unlike the dynamic-table cases above: the static table is the table
	 * this implementation actually has.
	 */
	@Test(timeout = TIMEOUT_MS)
	public void staticTableIndexOutOfRangeIsAConnectionError() {
		// prefix, then Indexed Field Line "1 1 index(6+)" with index = SIZE (99): 6-bit prefix all ones
		// (63) plus a continuation of 99 - 63 = 36.
		assertConnectionScoped(new byte[] {0x00, 0x00, (byte) 0xFF, 0x24});
	}

	/** RFC 9204 §3.1, via the Literal Field Line with Name Reference form. */
	@Test(timeout = TIMEOUT_MS)
	public void staticTableNameIndexOutOfRangeIsAConnectionError() {
		// prefix, then "0 1 N 1 index(4+)" with index = 99: 4-bit prefix all ones (15) + continuation 84.
		assertConnectionScoped(new byte[] {0x00, 0x00, 0x5F, 0x54});
	}

	@Test(timeout = TIMEOUT_MS)
	public void truncatedPrefixIsRejected() {
		// Required Insert Count marker (0xff, 8-bit prefix all-ones) demands a continuation byte
		// that never arrives. Bounded truncation: the stream dies, the connection does not.
		assertStreamScoped(new byte[] {(byte) 0xff});
	}

	@Test(timeout = TIMEOUT_MS)
	public void emptyBufferIsRejected() {
		assertStreamScoped(new byte[0]);
	}
}
