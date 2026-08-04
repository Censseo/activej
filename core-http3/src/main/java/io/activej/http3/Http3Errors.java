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

package io.activej.http3;

/**
 * The RFC 9114 §8.1 HTTP/3 application error codes, plus the RFC 9204 §6 QPACK error codes.
 * <p>
 * These are the values carried by a QUIC CONNECTION_CLOSE or RESET_STREAM/STOP_SENDING frame's
 * application error code field — a third axis from {@link io.activej.http.HttpError} (a status
 * code) and {@code QuicTransportException} (an RFC 9000 §20 <b>transport</b> code). FR-061 requires
 * the transport and application error spaces stay separate: an H3 protocol violation always closes
 * the connection or resets the stream with one of these codes, never a transport code.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-8.1">RFC 9114 §8.1 — HTTP/3 Error
 * Codes</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-6">RFC 9204 §6 — QPACK Error
 * Codes</a>
 */
public final class Http3Errors {
	private Http3Errors() {}

	/** Graceful shutdown of a connection or stream (RFC 9114 §8.1). */
	public static final long H3_NO_ERROR = 0x0100;

	/** A generic protocol violation not covered by a more specific code (RFC 9114 §8.1). */
	public static final long H3_GENERAL_PROTOCOL_ERROR = 0x0101;

	/** An internal error occurred in the local implementation (RFC 9114 §8.1). */
	public static final long H3_INTERNAL_ERROR = 0x0102;

	/** A stream was created in a way that is not permitted (RFC 9114 §8.1). */
	public static final long H3_STREAM_CREATION_ERROR = 0x0103;

	/** A critical stream (control, or a QPACK encoder/decoder stream) was closed (RFC 9114 §8.1). */
	public static final long H3_CLOSED_CRITICAL_STREAM = 0x0104;

	/** A frame was received in a context where it is not permitted (RFC 9114 §8.1). */
	public static final long H3_FRAME_UNEXPECTED = 0x0105;

	/** A frame violated the RFC 9114 §7.2 layout for its own type (RFC 9114 §8.1). */
	public static final long H3_FRAME_ERROR = 0x0106;

	/** A peer exceeded a size or count bound this implementation is willing to hold (RFC 9114 §8.1). */
	public static final long H3_EXCESSIVE_LOAD = 0x0107;

	/** A required identifier was invalid, out of order, or the wrong type (RFC 9114 §8.1). */
	public static final long H3_ID_ERROR = 0x0108;

	/** A SETTINGS parameter was invalid — a duplicate, a reserved identifier, or an illegal value (RFC 9114 §8.1). */
	public static final long H3_SETTINGS_ERROR = 0x0109;

	/** A frame was received on a control stream before SETTINGS (RFC 9114 §8.1). */
	public static final long H3_MISSING_SETTINGS = 0x010a;

	/**
	 * A request was rejected before any processing occurred; safe to retry on a fresh connection
	 * (RFC 9114 §8.1). One of the retryable conditions surfaced by {@link Http3Exception#isRetryable()}.
	 */
	public static final long H3_REQUEST_REJECTED = 0x010b;

	/** A request was cancelled by either endpoint after processing began (RFC 9114 §8.1). */
	public static final long H3_REQUEST_CANCELLED = 0x010c;

	/** The client's stream ended without a fully-formed request (RFC 9114 §8.1). */
	public static final long H3_REQUEST_INCOMPLETE = 0x010d;

	/** The message semantics were violated — pseudo-headers, field validation, `Content-Length` mismatch (RFC 9114 §8.1). */
	public static final long H3_MESSAGE_ERROR = 0x010e;

	/** The connection established via CONNECT was reset or abnormally closed (RFC 9114 §8.1). */
	public static final long H3_CONNECT_ERROR = 0x010f;

	/** The requested operation cannot be served over HTTP/3; retry over HTTP/1.1 or HTTP/2 (RFC 9114 §8.1). */
	public static final long H3_VERSION_FALLBACK = 0x0110;

	/** A field line could not be decoded, or referenced the dynamic table (RFC 9204 §6). */
	public static final long QPACK_DECOMPRESSION_FAILED = 0x0200;

	/** The peer's QPACK encoder stream carried an instruction this implementation does not accept (RFC 9204 §6). */
	public static final long QPACK_ENCODER_STREAM_ERROR = 0x0201;

	/** The peer's QPACK decoder stream carried an instruction this implementation does not accept (RFC 9204 §6). */
	public static final long QPACK_DECODER_STREAM_ERROR = 0x0202;
}
