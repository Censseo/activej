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

package io.activej.quic.connection;

import io.activej.common.ApplicationSettings;
import org.jetbrains.annotations.Nullable;

/**
 * A QUIC transport error (RFC 9000 §20). Signalled to the peer as a CONNECTION_CLOSE frame
 * (RFC 9000 §19.19) and delivered to the local caller through its {@code Promise}.
 * <p>
 * Checked, mirroring {@link io.activej.quic.QuicDecryptionException} — a transport error is an
 * expected protocol outcome, not a programming fault.
 * <p>
 * <b>Security (FR-031, SI-6)</b>: the message names the offending protocol element — a frame type,
 * a transport parameter, a limit that was exceeded. It never carries key material, traffic secrets,
 * AEAD keys or IVs, packet plaintext, or an address-validation token.
 * <p>
 * <b>Untrusted text</b>: when this exception represents a CONNECTION_CLOSE received <i>from a
 * peer</i>, {@link #reasonPhrase()} is attacker-controlled text. Pass it to a logger as an
 * argument, never as a format string, and never interpolate it into one.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20">RFC 9000 §20 — Error Codes</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.19">RFC 9000 §19.19 — CONNECTION_CLOSE</a>
 */
public class QuicTransportException extends Exception {
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(QuicTransportException.class, "withStackTrace", false);

	private final long errorCode;
	private final @Nullable Long frameType;
	private final String reasonPhrase;

	public QuicTransportException(long errorCode, String reasonPhrase) {
		this(errorCode, null, reasonPhrase);
	}

	/**
	 * @param errorCode    an RFC 9000 §20 code — a {@link QuicTransportErrors} constant, or
	 *                     {@link QuicTransportErrors#cryptoError(int)} for a TLS alert
	 * @param frameType    the frame type that triggered the error, or {@code null} when no single
	 *                     frame is responsible. RFC 9000 §19.19 makes the field present only for a
	 *                     transport CONNECTION_CLOSE, which is why absence is {@code null} and not a
	 *                     sentinel: a peer may legitimately send an unknown-but-valid type
	 * @param reasonPhrase names the offending protocol element — never key material, a secret, or
	 *                     plaintext (FR-031, SI-6). Stored and reported verbatim, never used as a
	 *                     format string
	 */
	public QuicTransportException(long errorCode, @Nullable Long frameType, String reasonPhrase) {
		super(reasonPhrase);
		this.errorCode = errorCode;
		this.frameType = frameType;
		this.reasonPhrase = reasonPhrase;
	}

	/** The RFC 9000 §20 transport error code to put in, or taken from, a CONNECTION_CLOSE frame. */
	public long errorCode() {
		return errorCode;
	}

	/** The frame type that triggered this error (RFC 9000 §19.19), or {@code null} if not attributable. */
	public @Nullable Long frameType() {
		return frameType;
	}

	/**
	 * The reason phrase. Untrusted text when this represents a peer's CONNECTION_CLOSE.
	 * <p>
	 * Not truncated here: the CONNECTION_CLOSE assembly path bounds what actually reaches the wire,
	 * so this type reports what it was given and does not truncate a second time.
	 */
	public String reasonPhrase() {
		return reasonPhrase;
	}

	@Override
	public final Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("QuicTransportException[")
			.append(QuicTransportErrors.name(errorCode))
			.append("(0x").append(Long.toHexString(errorCode)).append(')');
		if (frameType != null) {
			sb.append(", frameType=0x").append(Long.toHexString(frameType));
		}
		return sb.append(": ").append(reasonPhrase).append(']').toString();
	}
}
