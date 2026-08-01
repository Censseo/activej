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

/**
 * Asserts every {@link Http3Errors} constant against its RFC 9114 §8.1 / RFC 9204 §6 value.
 */
public class Http3ErrorsTest {
	@Test
	public void rfc9114ApplicationErrorCodes() {
		assertEquals(0x0100, Http3Errors.H3_NO_ERROR);
		assertEquals(0x0101, Http3Errors.H3_GENERAL_PROTOCOL_ERROR);
		assertEquals(0x0102, Http3Errors.H3_INTERNAL_ERROR);
		assertEquals(0x0103, Http3Errors.H3_STREAM_CREATION_ERROR);
		assertEquals(0x0104, Http3Errors.H3_CLOSED_CRITICAL_STREAM);
		assertEquals(0x0105, Http3Errors.H3_FRAME_UNEXPECTED);
		assertEquals(0x0106, Http3Errors.H3_FRAME_ERROR);
		assertEquals(0x0107, Http3Errors.H3_EXCESSIVE_LOAD);
		assertEquals(0x0108, Http3Errors.H3_ID_ERROR);
		assertEquals(0x0109, Http3Errors.H3_SETTINGS_ERROR);
		assertEquals(0x010a, Http3Errors.H3_MISSING_SETTINGS);
		assertEquals(0x010b, Http3Errors.H3_REQUEST_REJECTED);
		assertEquals(0x010c, Http3Errors.H3_REQUEST_CANCELLED);
		assertEquals(0x010d, Http3Errors.H3_REQUEST_INCOMPLETE);
		assertEquals(0x010e, Http3Errors.H3_MESSAGE_ERROR);
		assertEquals(0x010f, Http3Errors.H3_CONNECT_ERROR);
		assertEquals(0x0110, Http3Errors.H3_VERSION_FALLBACK);
	}

	@Test
	public void rfc9204QpackErrorCodes() {
		assertEquals(0x0200, Http3Errors.QPACK_DECOMPRESSION_FAILED);
		assertEquals(0x0201, Http3Errors.QPACK_ENCODER_STREAM_ERROR);
		assertEquals(0x0202, Http3Errors.QPACK_DECODER_STREAM_ERROR);
	}
}
