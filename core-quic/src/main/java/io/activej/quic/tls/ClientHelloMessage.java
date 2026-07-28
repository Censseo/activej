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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code ClientHello} message (RFC 8446 §4.1.2), HRR-unaware: the legacy fields
 * ({@code legacy_version}, {@code legacy_session_id}, {@code legacy_compression_methods}) are
 * carried verbatim; their RFC 8446 §4.1.2 validation is the engines' job, not the codec's.
 * <p>
 * In the QUIC profile (RFC 9001 §8.2) a ClientHello MUST carry {@code supported_versions}
 * offering TLS 1.3 only, a {@code key_share}, ALPN {@code h3} and
 * {@code quic_transport_parameters} — all negotiated by the engines.
 */
public final class ClientHelloMessage extends TlsHandshakeMessage {
	public static final int TYPE = 1;

	/** RFC 8446 §4.1.2: the only {@code legacy_version} a TLS 1.3 client sends. */
	public static final int LEGACY_VERSION = 0x0303;

	public final int legacyVersion;
	public final byte[] random;
	public final byte[] legacySessionId;
	public final int[] cipherSuites;
	public final int[] compressionMethods;
	public final List<TlsExtension> extensions;

	public ClientHelloMessage(int legacyVersion, byte[] random, byte[] legacySessionId,
			int[] cipherSuites, int[] compressionMethods, List<TlsExtension> extensions) {
		if (random.length != 32) {
			throw new IllegalArgumentException("ClientHello random must be 32 bytes: " + random.length);
		}
		if (legacySessionId.length > 32) {
			throw new IllegalArgumentException("legacy_session_id must be at most 32 bytes: " + legacySessionId.length);
		}
		if (cipherSuites.length == 0 || cipherSuites.length > 0x7FFF) {
			throw new IllegalArgumentException("cipher_suites must hold 1..32767 entries: " + cipherSuites.length);
		}
		if (compressionMethods.length == 0 || compressionMethods.length > 255) {
			throw new IllegalArgumentException("legacy_compression_methods must hold 1..255 entries: " + compressionMethods.length);
		}
		this.legacyVersion = legacyVersion;
		this.random = random.clone();
		this.legacySessionId = legacySessionId.clone();
		this.cipherSuites = cipherSuites.clone();
		this.compressionMethods = compressionMethods.clone();
		this.extensions = List.copyOf(extensions);
	}

	/** Defensive copy of {@link #random}. */
	public byte[] random() {
		return random.clone();
	}

	/** Defensive copy of {@link #legacySessionId}. */
	public byte[] legacySessionId() {
		return legacySessionId.clone();
	}

	/** Defensive copy of {@link #cipherSuites}. */
	public int[] cipherSuites() {
		return cipherSuites.clone();
	}

	/** Defensive copy of {@link #compressionMethods}. */
	public int[] compressionMethods() {
		return compressionMethods.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + 32 + 1 + legacySessionId.length + 2 + cipherSuites.length * 2 +
			1 + compressionMethods.length + 2 + TlsExtensions.encodedListLength(extensions);
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, legacyVersion);
		buf.put(random);
		buf.writeByte((byte) legacySessionId.length);
		buf.put(legacySessionId);
		TlsExtensions.writeShort(buf, cipherSuites.length * 2);
		for (int cipherSuite : cipherSuites) {
			TlsExtensions.writeShort(buf, cipherSuite);
		}
		buf.writeByte((byte) compressionMethods.length);
		for (int compressionMethod : compressionMethods) {
			buf.writeByte((byte) compressionMethod);
		}
		TlsExtensions.writeList(buf, extensions);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ClientHelloMessage other)) return false;
		return legacyVersion == other.legacyVersion &&
			Arrays.equals(random, other.random) &&
			Arrays.equals(legacySessionId, other.legacySessionId) &&
			Arrays.equals(cipherSuites, other.cipherSuites) &&
			Arrays.equals(compressionMethods, other.compressionMethods) &&
			extensions.equals(other.extensions);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(legacyVersion, extensions);
		result = 31 * result + Arrays.hashCode(random);
		result = 31 * result + Arrays.hashCode(legacySessionId);
		result = 31 * result + Arrays.hashCode(cipherSuites);
		result = 31 * result + Arrays.hashCode(compressionMethods);
		return result;
	}
}
