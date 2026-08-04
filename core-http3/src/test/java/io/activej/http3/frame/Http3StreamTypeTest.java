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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Http3StreamTypeTest {

	@Test
	public void classifiesTheFourKnownTypes() {
		assertEquals(Http3StreamType.CONTROL, Http3StreamType.classify(0x00));
		assertEquals(Http3StreamType.PUSH, Http3StreamType.classify(0x01));
		assertEquals(Http3StreamType.QPACK_ENCODER, Http3StreamType.classify(0x02));
		assertEquals(Http3StreamType.QPACK_DECODER, Http3StreamType.classify(0x03));
	}

	@Test
	public void classifiesEverythingElseAsUnknown() {
		assertEquals(Http3StreamType.UNKNOWN, Http3StreamType.classify(0x04));
		assertEquals(Http3StreamType.UNKNOWN, Http3StreamType.classify(0x21)); // GREASE
		assertEquals(Http3StreamType.UNKNOWN, Http3StreamType.classify(Long.MAX_VALUE));
	}

	@Test
	public void codeMatchesTheWireValue() {
		assertEquals(0x00, Http3StreamType.CONTROL.code());
		assertEquals(0x01, Http3StreamType.PUSH.code());
		assertEquals(0x02, Http3StreamType.QPACK_ENCODER.code());
		assertEquals(0x03, Http3StreamType.QPACK_DECODER.code());
	}
}
