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

package io.activej.quic.crypto;

import io.activej.quic.QuicConnectionId;
import io.activej.quic.QuicDecryptionException;
import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RFC 9001 Appendix A.5: the Retry Integrity Tag over the well-known worked example (original
 * DCID {@code 8394c8f03e515708}, Retry token the ASCII string "token"). The fixed key/nonce were
 * cross-checked this session against Cloudflare quiche's {@code RETRY_INTEGRITY_KEY_V1}/
 * {@code RETRY_INTEGRITY_NONCE_V1}; the expected tag was independently computed via Python's
 * {@code cryptography} library (AES-128-GCM) from those same constants, matching the published
 * RFC value.
 */
public class RetryIntegrityTagTest {

	private static final QuicConnectionId ORIGINAL_DCID = QuicConnectionId.of(HexFormat.of().parseHex("8394c8f03e515708"));
	// Retry packet header + token (everything before the tag): long header (type=Retry, unused
	// bits set), version 1, DCID length 0 (empty), SCID length 8 + SCID, token "token".
	private static final byte[] RETRY_HEADER_AND_TOKEN =
		HexFormat.of().parseHex("ff000000010008f067a5502a4262b5746f6b656e");
	private static final byte[] EXPECTED_TAG = HexFormat.of().parseHex("04a265ba2eff4d829058fb3f0f2496ba");

	@Test
	public void computesThePublishedRfc9001AppendixA5Tag() {
		byte[] tag = RetryIntegrityTag.compute(ORIGINAL_DCID, RETRY_HEADER_AND_TOKEN);
		assertEquals(RetryIntegrityTag.TAG_LENGTH, tag.length);
		assertEquals(HexFormat.of().formatHex(EXPECTED_TAG), HexFormat.of().formatHex(tag));
	}

	@Test
	public void verifyAcceptsTheCorrectTag() {
		assertTrue(RetryIntegrityTag.verify(ORIGINAL_DCID, RETRY_HEADER_AND_TOKEN, EXPECTED_TAG));
	}

	@Test
	public void verifyRejectsATamperedTag() {
		byte[] tampered = EXPECTED_TAG.clone();
		tampered[0] ^= 0x01;
		assertFalse(RetryIntegrityTag.verify(ORIGINAL_DCID, RETRY_HEADER_AND_TOKEN, tampered));
	}

	@Test
	public void verifyRejectsATamperedPseudoPacket() {
		byte[] tamperedHeader = RETRY_HEADER_AND_TOKEN.clone();
		tamperedHeader[tamperedHeader.length - 1] ^= 0x01; // flip a bit in the token
		assertFalse(RetryIntegrityTag.verify(ORIGINAL_DCID, tamperedHeader, EXPECTED_TAG));
	}

	@Test
	public void verifyOrThrowAcceptsTheCorrectTagWithoutThrowing() throws QuicDecryptionException {
		RetryIntegrityTag.verifyOrThrow(ORIGINAL_DCID, RETRY_HEADER_AND_TOKEN, EXPECTED_TAG);
	}

	@Test
	public void verifyOrThrowRejectsATamperedTagPerTheSpecsErrorScenario() {
		byte[] tampered = EXPECTED_TAG.clone();
		tampered[0] ^= 0x01;
		try {
			RetryIntegrityTag.verifyOrThrow(ORIGINAL_DCID, RETRY_HEADER_AND_TOKEN, tampered);
			fail("expected QuicDecryptionException");
		} catch (QuicDecryptionException expected) {
			// expected — matches spec.md's "Invalid Retry integrity tag" error scenario
		}
	}
}
