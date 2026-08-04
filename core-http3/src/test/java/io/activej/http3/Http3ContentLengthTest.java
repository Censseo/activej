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

package io.activej.http3;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * {@code Content-Length} reconciliation against the actual total DATA payload received (FR-039).
 * No transport exists yet in this phase, so this is tested as the pure function it is.
 */
public class Http3ContentLengthTest {

	@Test
	public void matchingContentLengthDoesNotThrow() throws Http3Exception {
		Http3Headers.checkContentLength(11, 11);
		Http3Headers.checkContentLength(0, 0);
	}

	@Test
	public void mismatchedContentLengthIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.checkContentLength(100, 42));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void bodyShorterThanDeclaredIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.checkContentLength(10, 0));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}

	@Test
	public void bodyLongerThanDeclaredIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.checkContentLength(1, 2));
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, e.errorCode());
	}
}
