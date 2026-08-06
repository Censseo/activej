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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The single definition of the RFC 8446 §4.2.11.2 binder truncation rule, shared by the client
 * engine (which computes a binder) and the server engine (which verifies one). One definition
 * matters more than usual here: an off-by-N between the two sides produces a handshake that fails
 * only against a foreign peer.
 * <p>
 * The truncated message <b>keeps</b> the 4-byte handshake header and the declared length that counts
 * the binders — only the binders vector itself is removed from the end. Every hash is taken on a
 * standalone {@link MessageDigest} under the <b>ticket's</b> suite hash, never on the connection's
 * rolling {@link TranscriptHash}: that one is bound to the <i>negotiated</i> suite, which is not
 * known until the ServerHello, and binding it early would break every handshake where the server
 * declines the PSK or picks another suite.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.11.2">RFC 8446 §4.2.11.2</a>
 */
final class TlsPskBinders {

	private TlsPskBinders() {
	}

	/** The hash over the complete ClientHello, binder included — what {@code "c e traffic"} is derived against. */
	static byte[] clientHelloHash(TlsCipherSuite suite, byte[] clientHelloBytes) {
		return newDigest(suite).digest(clientHelloBytes);
	}

	/**
	 * The hash over the ClientHello with its trailing binders vector removed — what the binder HMAC
	 * covers.
	 *
	 * @param bindersSectionLength the width of the binders vector including its 2-byte length, i.e.
	 * {@link PreSharedKeyExt#bindersSectionLength()}
	 * @throws IllegalArgumentException if the width does not fall strictly inside the message, which
	 * is a caller bug rather than a wire condition
	 */
	static byte[] truncatedClientHelloHash(TlsCipherSuite suite, byte[] clientHelloBytes, int bindersSectionLength) {
		if (bindersSectionLength <= 0 || bindersSectionLength >= clientHelloBytes.length) {
			throw new IllegalArgumentException("A binders section of " + bindersSectionLength +
				" bytes does not fall inside a ClientHello of " + clientHelloBytes.length + " bytes");
		}
		MessageDigest digest = newDigest(suite);
		digest.update(clientHelloBytes, 0, clientHelloBytes.length - bindersSectionLength);
		return digest.digest();
	}

	/**
	 * Overwrites the trailing {@code binder.length} bytes of a serialized ClientHello with the real
	 * binder, replacing the placeholder that was written to size the message.
	 * <p>
	 * Correct only because this stack offers exactly one identity and writes {@code pre_shared_key}
	 * last (RFC 8446 §4.2.11), so the single binder <i>is</i> the message's tail.
	 */
	static void writeBinderInto(byte[] clientHelloBytes, byte[] binder) {
		if (binder.length == 0 || binder.length > clientHelloBytes.length) {
			throw new IllegalArgumentException("A binder of " + binder.length +
				" bytes does not fit the tail of a ClientHello of " + clientHelloBytes.length + " bytes");
		}
		System.arraycopy(binder, 0, clientHelloBytes, clientHelloBytes.length - binder.length, binder.length);
	}

	/** Constant-time binder comparison (SI-5) — a byte-by-byte compare would be a timing oracle. */
	static boolean verifyBinder(byte[] expected, byte[] actual) {
		return MessageDigest.isEqual(expected, actual);
	}

	private static MessageDigest newDigest(TlsCipherSuite suite) {
		try {
			return MessageDigest.getInstance(suite.hashAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			// SHA-256/SHA-384 are guaranteed present in every JDK provider.
			throw new AssertionError(e);
		}
	}
}
