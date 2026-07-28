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

import io.activej.bytebuf.ByteBuf;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code ServerHello} message (RFC 8446 §4.1.3). A ServerHello whose random equals
 * {@link #HELLO_RETRY_REQUEST_RANDOM} is a HelloRetryRequest (RFC 8446 §4.1.4) — classified via
 * {@link #isHelloRetryRequest()}, never processed as a normal ServerHello; the client engine
 * aborts it with {@link TlsHelloRetryRequestException} (FR-014).
 */
public final class ServerHelloMessage extends TlsHandshakeMessage {
	public static final int TYPE = 2;

	/** RFC 8446 §4.1.3: the only {@code legacy_version} a TLS 1.3 server sends. */
	public static final int LEGACY_VERSION = 0x0303;

	/**
	 * The RFC 8446 §4.1.4 special ServerHello random identifying a HelloRetryRequest:
	 * SHA-256 of the string {@code "HelloRetryRequest"}.
	 */
	public static final byte[] HELLO_RETRY_REQUEST_RANDOM = sha256("HelloRetryRequest");

	public final int legacyVersion;
	public final byte[] random;
	public final byte[] sessionIdEcho;
	public final int cipherSuite;
	public final int compressionMethod;
	public final List<TlsExtension> extensions;

	public ServerHelloMessage(int legacyVersion, byte[] random, byte[] sessionIdEcho,
			int cipherSuite, int compressionMethod, List<TlsExtension> extensions) {
		if (random.length != 32) {
			throw new IllegalArgumentException("ServerHello random must be 32 bytes: " + random.length);
		}
		if (sessionIdEcho.length > 32) {
			throw new IllegalArgumentException("session_id_echo must be at most 32 bytes: " + sessionIdEcho.length);
		}
		this.legacyVersion = legacyVersion;
		this.random = random.clone();
		this.sessionIdEcho = sessionIdEcho.clone();
		this.cipherSuite = cipherSuite;
		this.compressionMethod = compressionMethod;
		this.extensions = List.copyOf(extensions);
	}

	/** Defensive copy of {@link #random}. */
	public byte[] random() {
		return random.clone();
	}

	/** Defensive copy of {@link #sessionIdEcho}. */
	public byte[] sessionIdEcho() {
		return sessionIdEcho.clone();
	}

	/** {@code true} if this ServerHello is a HelloRetryRequest (RFC 8446 §4.1.4 special random). */
	public boolean isHelloRetryRequest() {
		return Arrays.equals(random, HELLO_RETRY_REQUEST_RANDOM);
	}

	/** The selected suite as a {@link TlsCipherSuite}, or {@code null} for an unknown/GREASE codepoint. */
	public @Nullable TlsCipherSuite knownCipherSuite() {
		return TlsCipherSuite.of(cipherSuite);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + 32 + 1 + sessionIdEcho.length + 2 + 1 + 2 + TlsExtensions.encodedListLength(extensions);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, legacyVersion);
		buf.put(random);
		buf.writeByte((byte) sessionIdEcho.length);
		buf.put(sessionIdEcho);
		TlsExtensions.writeShort(buf, cipherSuite);
		buf.writeByte((byte) compressionMethod);
		TlsExtensions.writeList(buf, extensions);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ServerHelloMessage other)) return false;
		return legacyVersion == other.legacyVersion &&
			cipherSuite == other.cipherSuite &&
			compressionMethod == other.compressionMethod &&
			Arrays.equals(random, other.random) &&
			Arrays.equals(sessionIdEcho, other.sessionIdEcho) &&
			extensions.equals(other.extensions);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(legacyVersion, cipherSuite, compressionMethod, extensions);
		result = 31 * result + Arrays.hashCode(random);
		result = 31 * result + Arrays.hashCode(sessionIdEcho);
		return result;
	}

	private static byte[] sha256(String string) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(string.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("JDK does not provide SHA-256", e);
		}
	}
}
