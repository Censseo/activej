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

import static io.activej.common.Checks.checkArgument;

/**
 * The QUIC transport error codes of RFC 9000 §20.1, plus the RFC 9001 §4.8 CRYPTO_ERROR range.
 * <p>
 * These are the values carried by a CONNECTION_CLOSE frame's Error Code field (RFC 9000 §19.19),
 * which is a variable-length integer — hence {@code long} rather than {@code int}.
 * <p>
 * The code space is <b>open</b>: RFC 9000 §20.1 lets an endpoint use codes outside the values
 * defined here, and §20.2 gives the application its own space. Receiving an unrecognized code is
 * therefore not a protocol error — it is reported as-is. This is why the type is a constant holder
 * rather than an enum: an enum could not represent a code a peer actually sent.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20">RFC 9000 §20 — Error Codes</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.8">RFC 9001 §4.8 — Error Handling</a>
 */
public final class QuicTransportErrors {
	private QuicTransportErrors() {}

	/** An endpoint uses this with no error signalled; a graceful close (RFC 9000 §20.1). */
	public static final long NO_ERROR = 0x00;

	/** The endpoint encountered an internal error and cannot continue (RFC 9000 §20.1). */
	public static final long INTERNAL_ERROR = 0x01;

	/** The server refused to accept a new connection (RFC 9000 §20.1). */
	public static final long CONNECTION_REFUSED = 0x02;

	/** A peer received more data than it permitted through flow control (RFC 9000 §20.1). */
	public static final long FLOW_CONTROL_ERROR = 0x03;

	/** A peer opened more streams than the advertised limit permitted (RFC 9000 §20.1). */
	public static final long STREAM_LIMIT_ERROR = 0x04;

	/** A frame was received for a stream in a state that does not permit it (RFC 9000 §20.1). */
	public static final long STREAM_STATE_ERROR = 0x05;

	/** A peer changed, or exceeded, a stream's final size (RFC 9000 §20.1). */
	public static final long FINAL_SIZE_ERROR = 0x06;

	/** A frame was malformed: an invalid type, or field values outside the frame's own syntax (RFC 9000 §20.1). */
	public static final long FRAME_ENCODING_ERROR = 0x07;

	/** The peer's transport parameters were malformed, absent, duplicated, or invalid (RFC 9000 §20.1). */
	public static final long TRANSPORT_PARAMETER_ERROR = 0x08;

	/** The peer provided more connection IDs than the {@code active_connection_id_limit} allowed (RFC 9000 §20.1). */
	public static final long CONNECTION_ID_LIMIT_ERROR = 0x09;

	/** The peer violated a protocol requirement not covered by a more specific code (RFC 9000 §20.1). */
	public static final long PROTOCOL_VIOLATION = 0x0a;

	/** A server received a token in an Initial packet that was invalid (RFC 9000 §20.1). */
	public static final long INVALID_TOKEN = 0x0b;

	/**
	 * The application signalled an error. Used only in an APPLICATION_CLOSE frame (type 0x1d);
	 * a transport CONNECTION_CLOSE never carries it (RFC 9000 §20.1).
	 */
	public static final long APPLICATION_ERROR = 0x0c;

	/** More CRYPTO frame data was buffered than the endpoint was willing to hold (RFC 9000 §20.1). */
	public static final long CRYPTO_BUFFER_EXCEEDED = 0x0d;

	/** A key update was not possible, or was requested when it was not permitted (RFC 9000 §20.1). */
	public static final long KEY_UPDATE_ERROR = 0x0e;

	/** The AEAD confidentiality or integrity limit of RFC 9001 §6.6 was reached (RFC 9000 §20.1). */
	public static final long AEAD_LIMIT_REACHED = 0x0f;

	/** No network path over which QUIC could be sent was available (RFC 9000 §20.1). */
	public static final long NO_VIABLE_PATH = 0x10;

	/**
	 * The versions the two endpoints support do not overlap (RFC 9368 §6, in the RFC 9000 §22.5 registry).
	 * <p>
	 * From the version-negotiation extension rather than from RFC 9000 itself, and never sent on the wire
	 * by this implementation — a Version Negotiation packet is answered by giving up, not by a
	 * CONNECTION_CLOSE under keys the peer has just said it cannot read. It exists so the local failure
	 * carries a code that names its cause.
	 */
	public static final long VERSION_NEGOTIATION_ERROR = 0x11;

	/** The base of the CRYPTO_ERROR range: a TLS alert {@code a} maps to {@code 0x0100 + a} (RFC 9001 §4.8). */
	public static final long CRYPTO_ERROR_BASE = 0x0100;

	/** The inclusive top of the CRYPTO_ERROR range (RFC 9001 §4.8). */
	public static final long CRYPTO_ERROR_MAX = 0x01ff;

	/**
	 * The CRYPTO_ERROR code for a TLS alert, per RFC 9001 §4.8: {@code 0x0100 + alert}.
	 * <p>
	 * The argument is validated unconditionally rather than behind {@link io.activej.common.Checks} —
	 * an out-of-range alert would silently forge a different error code, and production runs with
	 * checks off.
	 *
	 * @param alert an RFC 8446 §6 alert description code, 0-255
	 */
	public static long cryptoError(int alert) {
		checkArgument(alert >= 0 && alert <= 0xff, "TLS alert code out of range: " + alert);
		return CRYPTO_ERROR_BASE + alert;
	}

	/** Whether {@code code} lies in the RFC 9001 §4.8 CRYPTO_ERROR range. */
	public static boolean isCryptoError(long code) {
		return code >= CRYPTO_ERROR_BASE && code <= CRYPTO_ERROR_MAX;
	}

	/**
	 * The TLS alert carried by a CRYPTO_ERROR code — the inverse of {@link #cryptoError(int)}.
	 * Used when reporting a CONNECTION_CLOSE received <i>from</i> a peer.
	 *
	 * @throws IllegalArgumentException if {@code code} is not a CRYPTO_ERROR
	 */
	public static int alertOf(long code) {
		checkArgument(isCryptoError(code), "Not a CRYPTO_ERROR code: " + code);
		return (int) (code - CRYPTO_ERROR_BASE);
	}

	/**
	 * A human-readable name for an error code, for diagnostics and {@code toString}.
	 * <p>
	 * Never throws: it is called from failure-handling and logging paths, including while another
	 * error is already being processed. An unrecognized code renders as {@code UNKNOWN(0x…)}.
	 */
	public static String name(long code) {
		if (isCryptoError(code)) return "CRYPTO_ERROR(" + alertOf(code) + ")";
		if (code == NO_ERROR) return "NO_ERROR";
		if (code == INTERNAL_ERROR) return "INTERNAL_ERROR";
		if (code == CONNECTION_REFUSED) return "CONNECTION_REFUSED";
		if (code == FLOW_CONTROL_ERROR) return "FLOW_CONTROL_ERROR";
		if (code == STREAM_LIMIT_ERROR) return "STREAM_LIMIT_ERROR";
		if (code == STREAM_STATE_ERROR) return "STREAM_STATE_ERROR";
		if (code == FINAL_SIZE_ERROR) return "FINAL_SIZE_ERROR";
		if (code == FRAME_ENCODING_ERROR) return "FRAME_ENCODING_ERROR";
		if (code == TRANSPORT_PARAMETER_ERROR) return "TRANSPORT_PARAMETER_ERROR";
		if (code == CONNECTION_ID_LIMIT_ERROR) return "CONNECTION_ID_LIMIT_ERROR";
		if (code == PROTOCOL_VIOLATION) return "PROTOCOL_VIOLATION";
		if (code == INVALID_TOKEN) return "INVALID_TOKEN";
		if (code == APPLICATION_ERROR) return "APPLICATION_ERROR";
		if (code == CRYPTO_BUFFER_EXCEEDED) return "CRYPTO_BUFFER_EXCEEDED";
		if (code == KEY_UPDATE_ERROR) return "KEY_UPDATE_ERROR";
		if (code == AEAD_LIMIT_REACHED) return "AEAD_LIMIT_REACHED";
		if (code == NO_VIABLE_PATH) return "NO_VIABLE_PATH";
		if (code == VERSION_NEGOTIATION_ERROR) return "VERSION_NEGOTIATION_ERROR";
		return "UNKNOWN(0x" + Long.toHexString(code) + ")";
	}
}
