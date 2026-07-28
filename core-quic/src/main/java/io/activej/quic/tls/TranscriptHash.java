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

import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The TLS 1.3 transcript hash (RFC 8446 §4.4.1): a rolling digest over the exact handshake
 * message bytes exchanged, using the negotiated cipher suite's hash.
 * <p>
 * The hash algorithm is suite-dependent (SHA-256 or SHA-384) but the ClientHello must be hashed
 * before the ServerHello reveals the suite — and in this profile (no HelloRetryRequest) the
 * ClientHello is the only message that can precede suite selection. So message bytes are
 * buffered until {@link #bindCipherSuite(TlsCipherSuite)} is called, then the buffered bytes
 * seed the rolling {@link MessageDigest} and every further {@link #update(byte[])} rolls it.
 * {@link #hash()} is repeatable: it snapshots the digest without disturbing the rolling state.
 */
public final class TranscriptHash {

	private byte @Nullable [] buffered = new byte[0];
	private @Nullable MessageDigest digest;

	/** Feeds the exact wire bytes of one handshake message into the transcript. */
	public void update(byte[] messageBytes) {
		MessageDigest rolling = digest;
		if (rolling != null) {
			rolling.update(messageBytes);
			return;
		}
		byte[] previous = buffered;
		byte[] combined = Arrays.copyOf(previous, previous.length + messageBytes.length);
		System.arraycopy(messageBytes, 0, combined, previous.length, messageBytes.length);
		buffered = combined;
	}

	/**
	 * Binds the negotiated suite's hash: the buffered pre-suite bytes (the ClientHello) seed
	 * the rolling digest. One-shot — the suite never changes within a handshake in this profile.
	 *
	 * @throws IllegalStateException if a suite is already bound
	 */
	public void bindCipherSuite(TlsCipherSuite suite) {
		if (digest != null) {
			throw new IllegalStateException("Cipher suite is already bound");
		}
		MessageDigest newDigest;
		try {
			newDigest = MessageDigest.getInstance(suite.hashAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JDK does not provide " + suite.hashAlgorithm(), e);
		}
		newDigest.update(buffered);
		buffered = null;
		digest = newDigest;
	}

	/**
	 * The transcript hash over all message bytes fed so far. Repeatable — the rolling state is
	 * not disturbed, so further {@link #update(byte[])} calls continue the same transcript.
	 *
	 * @throws IllegalStateException if no cipher suite is bound yet (the algorithm is unknown)
	 */
	public byte[] hash() {
		MessageDigest rolling = digest;
		if (rolling == null) {
			throw new IllegalStateException("Cipher suite is not bound yet — the transcript hash algorithm is unknown");
		}
		try {
			return ((MessageDigest) rolling.clone()).digest();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException("JDK MessageDigest is not cloneable", e);
		}
	}
}
