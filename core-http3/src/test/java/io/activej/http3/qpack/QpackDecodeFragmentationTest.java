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
import io.activej.bytebuf.ByteBufs;
import io.activej.http.HttpHeaders;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;

/**
 * A field section decodes identically whether it arrives as one contiguous buffer or is
 * reassembled from many single-octet fragments (FR-027, SC-011).
 * <p>
 * {@link QpackDecoder#decode} takes one already-complete encoded field section — by design, the
 * resumability across arbitrary wire boundaries lives one layer up, in the (not-yet-built)
 * {@code Http3FrameReader}, which buffers a HEADERS frame's payload to its declared length before
 * this package ever sees it (data-model.md §3.4/§4.4). What this test proves is that the decoder
 * itself has no hidden dependency on how the complete buffer it is handed was physically built —
 * e.g. no assumption that the backing array is a single contiguous allocation from the original
 * encode — by reconstructing the identical bytes through single-octet fragments glued together
 * with {@link ByteBufs}, the same accumulation primitive a fragment-tolerant reader would use.
 */
public class QpackDecodeFragmentationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void oneOctetAtATimeYieldsSameFieldListAsOneBuffer() throws QpackException {
		List<QpackField> original = List.of(
			new QpackField(HttpHeaders.of(":method"), encodeAscii("GET")),
			new QpackField(HttpHeaders.of("content-type"), encodeAscii("application/octet-stream")),
			new QpackField(HttpHeaders.of("x-custom"), encodeAscii("some value with spaces, and more")));

		QpackStaticEncoder encoder = new QpackStaticEncoder();
		ByteBuf wholeBuf = encoder.encode(original);
		byte[] wireBytes = new byte[wholeBuf.readRemaining()];
		wholeBuf.read(wireBytes);
		wholeBuf.recycle();

		List<QpackField> fromWholeBuffer =
			new QpackStaticDecoder(Long.MAX_VALUE).decode(ByteBuf.wrapForReading(wireBytes.clone()));

		ByteBufs fragments = new ByteBufs();
		for (byte b : wireBytes) {
			fragments.add(ByteBuf.wrapForReading(new byte[] {b}));
		}
		ByteBuf reassembled = fragments.takeRemaining();
		List<QpackField> fromFragments = new QpackStaticDecoder(Long.MAX_VALUE).decode(reassembled);

		assertEquals(original, fromWholeBuffer);
		assertEquals(original, fromFragments);
	}
}
