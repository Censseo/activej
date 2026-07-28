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

import java.util.Arrays;

/**
 * The {@code CertificateVerify} message (RFC 8446 §4.4.3): a signature scheme plus the
 * signature over the RFC 8446 §4.4.3 content (64 spaces, the context string
 * {@code "TLS 1.3, server CertificateVerify"}, a zero byte and the transcript hash).
 * Production and verification of the signature itself live in {@code TlsSignatures} (US2).
 */
public final class CertificateVerifyMessage extends TlsHandshakeMessage {
	public static final int TYPE = 15;

	public final int signatureScheme;
	public final byte[] signature;

	public CertificateVerifyMessage(int signatureScheme, byte[] signature) {
		if (signature.length == 0 || signature.length > 0xFFFF) {
			throw new IllegalArgumentException("signature must be 1..65535 bytes: " + signature.length);
		}
		this.signatureScheme = signatureScheme;
		this.signature = signature.clone();
	}

	/** Defensive copy of {@link #signature}. */
	public byte[] signature() {
		return signature.clone();
	}

	/** The scheme as a {@link SignatureScheme}, or {@code null} for an unknown/GREASE codepoint. */
	public @Nullable SignatureScheme knownScheme() {
		return SignatureScheme.of(signatureScheme);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public int encodedLength() {
		return 4 + 2 + 2 + signature.length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		buf.writeByte((byte) TYPE);
		TlsMessages.writeUint24(buf, encodedLength() - 4);
		TlsExtensions.writeShort(buf, signatureScheme);
		TlsExtensions.writeShort(buf, signature.length);
		buf.put(signature);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CertificateVerifyMessage other)) return false;
		return signatureScheme == other.signatureScheme && Arrays.equals(signature, other.signature);
	}

	@Override
	public int hashCode() {
		return 31 * signatureScheme + Arrays.hashCode(signature);
	}
}
