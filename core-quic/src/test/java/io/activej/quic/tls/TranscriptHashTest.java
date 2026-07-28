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

package io.activej.quic.tls;

import org.junit.Test;

import java.security.MessageDigest;

import static org.junit.Assert.*;

/**
 * Transcript hash (RFC 8446 §4.4.1): buffering before the cipher suite is known, rolling digest
 * afterwards, per-suite hash algorithm, proven against the RFC 8448 §3 published transcript
 * hashes (which also pins the trace constants in {@link Rfc8448} byte-exact).
 */
public class TranscriptHashTest {

	@Test
	public void buffersClientHelloUntilSuiteKnownThenRollsWithSha384() throws Exception {
		// The TLS_AES_256_GCM_SHA384 path: ClientHello is hashed before the suite is known
		// (it is buffered), then the digest rolls per exact message bytes.
		TranscriptHash transcript = new TranscriptHash();
		transcript.update(Rfc8448.CLIENT_HELLO);
		transcript.bindCipherSuite(TlsCipherSuite.TLS_AES_256_GCM_SHA384);
		transcript.update(Rfc8448.SERVER_HELLO);

		MessageDigest independent = MessageDigest.getInstance("SHA-384");
		independent.update(Rfc8448.CLIENT_HELLO);
		independent.update(Rfc8448.SERVER_HELLO);
		assertArrayEquals(independent.digest(), transcript.hash());
	}

	@Test
	public void sha256PathMatchesRfc8448PublishedTranscriptHash() throws Exception {
		// RFC 8448 §3 negotiates TLS_AES_128_GCM_SHA256; the published hash over
		// ClientHello..ServerHello is the acid test for the buffered-then-rolling construction
		// and for the trace constants themselves.
		TranscriptHash transcript = new TranscriptHash();
		transcript.update(Rfc8448.CLIENT_HELLO);
		transcript.bindCipherSuite(TlsCipherSuite.TLS_AES_128_GCM_SHA256);
		transcript.update(Rfc8448.SERVER_HELLO);

		MessageDigest independent = MessageDigest.getInstance("SHA-256");
		independent.update(Rfc8448.CLIENT_HELLO);
		independent.update(Rfc8448.SERVER_HELLO);
		assertArrayEquals(independent.digest(), transcript.hash());

		assertArrayEquals(Rfc8448.HANDSHAKE_TRANSCRIPT_HASH, transcript.hash());
	}

	@Test
	public void hashIsRepeatableWithoutDisturbingTheRollingState() throws Exception {
		TranscriptHash transcript = new TranscriptHash();
		transcript.update(Rfc8448.CLIENT_HELLO);
		transcript.bindCipherSuite(TlsCipherSuite.TLS_AES_128_GCM_SHA256);
		transcript.update(Rfc8448.SERVER_HELLO);

		byte[] first = transcript.hash();
		byte[] second = transcript.hash();
		assertArrayEquals(first, second);

		// Rolling continues after hash(): the full CH..server-Finished transcript must match
		// the published RFC 8448 §3 value used to derive the application traffic secrets.
		transcript.update(Rfc8448.ENCRYPTED_EXTENSIONS);
		transcript.update(Rfc8448.CERTIFICATE);
		transcript.update(Rfc8448.CERTIFICATE_VERIFY);
		transcript.update(Rfc8448.SERVER_FINISHED);
		assertArrayEquals(Rfc8448.SERVER_FINISHED_TRANSCRIPT_HASH, transcript.hash());

		// ...and further still, through the client Finished.
		transcript.update(Rfc8448.CLIENT_FINISHED);
		assertArrayEquals(Rfc8448.CLIENT_FINISHED_TRANSCRIPT_HASH, transcript.hash());
	}

	@Test
	public void chachaPathUsesSha256() throws Exception {
		TranscriptHash transcript = new TranscriptHash();
		transcript.update(Rfc8448.CLIENT_HELLO);
		transcript.bindCipherSuite(TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256);
		transcript.update(Rfc8448.SERVER_HELLO);
		assertArrayEquals(Rfc8448.HANDSHAKE_TRANSCRIPT_HASH, transcript.hash());
	}

	@Test
	public void hashBeforeSuiteBoundThrowsIllegalState() {
		TranscriptHash transcript = new TranscriptHash();
		transcript.update(Rfc8448.CLIENT_HELLO);
		try {
			transcript.hash();
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected: the hash algorithm is suite-dependent, nothing can be produced yet
		}
	}

	@Test
	public void bindingASuiteTwiceThrowsIllegalState() {
		TranscriptHash transcript = new TranscriptHash();
		transcript.bindCipherSuite(TlsCipherSuite.TLS_AES_128_GCM_SHA256);
		try {
			transcript.bindCipherSuite(TlsCipherSuite.TLS_AES_128_GCM_SHA256);
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void digestLengthMatchesTheSuite() {
		for (TlsCipherSuite suite : TlsCipherSuite.values()) {
			TranscriptHash transcript = new TranscriptHash();
			transcript.bindCipherSuite(suite);
			assertEquals(suite == TlsCipherSuite.TLS_AES_256_GCM_SHA384 ? 48 : 32, transcript.hash().length);
		}
	}
}
