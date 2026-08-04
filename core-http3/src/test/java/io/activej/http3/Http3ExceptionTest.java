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

import static org.junit.Assert.*;

public class Http3ExceptionTest {
	@Test
	public void exposesErrorCodeAndReason() {
		Http3Exception e = new Http3Exception(Http3Errors.H3_FRAME_ERROR, "bad frame");
		assertEquals(Http3Errors.H3_FRAME_ERROR, e.errorCode());
		assertEquals("bad frame", e.reason());
		assertEquals("bad frame", e.getMessage());
	}

	@Test
	public void requestRejectedIsRetryable() {
		assertTrue(new Http3Exception(Http3Errors.H3_REQUEST_REJECTED, "server draining").isRetryable());
	}

	@Test
	public void mostErrorsAreNotRetryableByDefault() {
		assertFalse(new Http3Exception(Http3Errors.H3_FRAME_ERROR, "bad frame").isRetryable());
		assertFalse(new Http3Exception(Http3Errors.H3_MESSAGE_ERROR, "bad pseudo-header").isRetryable());
		assertFalse(new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "oops").isRetryable());
	}

	@Test
	public void explicitRetryableOverrideCoversGoAwayAndQueueOverflow() {
		// GOAWAY-above-identifier and client queue overflow are raised with H3_REQUEST_REJECTED too,
		// but from call sites in later (blocked) phases that may want to state retryability explicitly.
		Http3Exception goAway = new Http3Exception(Http3Errors.H3_REQUEST_REJECTED, "stream id above GOAWAY", true);
		assertTrue(goAway.isRetryable());

		Http3Exception explicitlyNotRetryable = new Http3Exception(Http3Errors.H3_REQUEST_REJECTED, "won't retry", false);
		assertFalse(explicitlyNotRetryable.isRetryable());
	}

	@Test
	public void messageCarriesOnlyTheSuppliedReason() {
		String reason = "field section too large";
		Http3Exception e = new Http3Exception(Http3Errors.H3_EXCESSIVE_LOAD, reason);
		assertEquals(reason, e.getMessage());
		assertTrue(e.toString().endsWith(": " + reason + "]"));
	}
}
