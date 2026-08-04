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
import static org.junit.Assert.assertThrows;

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

	private void assertRejected(byte[] bytes) {
		QpackException e = assertThrows(QpackException.class, () -> decoder.decode(ByteBuf.wrapForReading(bytes)));
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, e.errorCode());
	}

	@Test(timeout = TIMEOUT_MS)
	public void nonZeroRequiredInsertCountIsRejected() {
		assertRejected(new byte[] {0x01, 0x00});
	}

	@Test(timeout = TIMEOUT_MS)
	public void indexedFieldLineWithDynamicTableReferenceIsRejected() {
		// prefix (RIC=0, S/DeltaBase=0), then "1 0 index(6+)": T=0, dynamic.
		assertRejected(new byte[] {0x00, 0x00, (byte) 0x80});
	}

	@Test(timeout = TIMEOUT_MS)
	public void literalWithNameReferenceDynamicTableReferenceIsRejected() {
		// prefix, then "0 1 N 0 index(4+)": T=0, dynamic.
		assertRejected(new byte[] {0x00, 0x00, 0x40});
	}

	@Test(timeout = TIMEOUT_MS)
	public void postBaseIndexIsRejected() {
		// prefix, then "0 0 0 1 index(4+)".
		assertRejected(new byte[] {0x00, 0x00, 0x10});
	}

	@Test(timeout = TIMEOUT_MS)
	public void postBaseNameReferenceIsRejected() {
		// prefix, then "0 0 0 0 N index(3+)".
		assertRejected(new byte[] {0x00, 0x00, 0x00});
	}

	@Test(timeout = TIMEOUT_MS)
	public void truncatedPrefixIsRejected() {
		// Required Insert Count marker (0xff, 8-bit prefix all-ones) demands a continuation byte
		// that never arrives.
		assertRejected(new byte[] {(byte) 0xff});
	}

	@Test(timeout = TIMEOUT_MS)
	public void emptyBufferIsRejected() {
		assertRejected(new byte[0]);
	}
}
