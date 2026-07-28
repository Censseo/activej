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

/**
 * The {@code Certificate} message (RFC 8446 §4.4.2): a certificate request context (empty in
 * this profile — the server never requests a client certificate) plus the certificate list.
 * Each entry carries the DER certificate bytes (copied once into a JDK array for
 * {@code CertificateFactory}) and its per-entry extensions.
 * <p>
 * FR-017 bounds: at most {@link #MAX_CERTIFICATE_ENTRIES} entries per message; each certificate
 * is bounded by the 3-byte wire length and the enclosing message size.
 */
public final class CertificateMessage extends TlsHandshakeMessage {
	public static final int TYPE = 11;

	/** FR-017: maximum certificate entries per Certificate message (constant, not tunable). */
	public static final int MAX_CERTIFICATE_ENTRIES = 16;

	public final byte[] certificateRequestContext;
	public final List<CertificateEntry> entries;

	public CertificateMessage(byte[] certificateRequestContext, List<CertificateEntry> entries) {
		if (certificateRequestContext.length > 255) {
			throw new IllegalArgumentException(
				"certificate_request_context must be at most 255 bytes: " + certificateRequestContext.length);
		}
		if (entries.size() > MAX_CERTIFICATE_ENTRIES) {
			throw new IllegalArgumentException(
				"Certificate message must hold at most " + MAX_CERTIFICATE_ENTRIES + " entries: " + entries.size());
		}
		this.certificateRequestContext = certificateRequestContext.clone();
		this.entries = List.copyOf(entries);
	}

	/** Defensive copy of {@link #certificateRequestContext}. */
	public byte[] certificateRequestContext() {
		return certificateRequestContext.clone();
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		int entriesLength = 0;
		for (CertificateEntry entry : entries) {
			entriesLength += entry.encodedLength();
		}
		return 4 + 1 + certificateRequestContext.length + 3 + entriesLength;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		buf.writeByte((byte) certificateRequestContext.length);
		buf.put(certificateRequestContext);
		TlsMessages.writeUint24(buf, encodedLength() - 4 - 1 - certificateRequestContext.length - 3);
		for (CertificateEntry entry : entries) {
			entry.writeTo(buf);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CertificateMessage other)) return false;
		return Arrays.equals(certificateRequestContext, other.certificateRequestContext) &&
			entries.equals(other.entries);
	}

	@Override
	public int hashCode() {
		return 31 * Arrays.hashCode(certificateRequestContext) + entries.hashCode();
	}

	/**
	 * One {@code CertificateEntry} (RFC 8446 §4.4.2): DER certificate bytes plus per-entry
	 * extensions (e.g. {@code status_request} — never requested in this profile).
	 */
	public static final class CertificateEntry {
		public final byte[] certificateBytes;
		public final List<TlsExtension> extensions;

		public CertificateEntry(byte[] certificateBytes, List<TlsExtension> extensions) {
			if (certificateBytes.length == 0 || certificateBytes.length > 0xFFFFFF) {
				throw new IllegalArgumentException(
					"certificate_data must be 1..16777215 bytes: " + certificateBytes.length);
			}
			this.certificateBytes = certificateBytes.clone();
			this.extensions = List.copyOf(extensions);
		}

		/** Defensive copy of {@link #certificateBytes}. */
		public byte[] certificateBytes() {
			return certificateBytes.clone();
		}

		int encodedLength() {
			return 3 + certificateBytes.length + 2 + TlsExtensions.encodedListLength(extensions);
		}

		void writeTo(ByteBuf buf) {
			TlsMessages.writeUint24(buf, certificateBytes.length);
			buf.put(certificateBytes);
			TlsExtensions.writeList(buf, extensions);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CertificateEntry other)) return false;
			return Arrays.equals(certificateBytes, other.certificateBytes) &&
				extensions.equals(other.extensions);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(certificateBytes) + extensions.hashCode();
		}
	}
}
