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

import io.activej.common.ApplicationSettings;

/**
 * An HTTP/3 application error (RFC 9114 §8.1) or QPACK error (RFC 9204 §6). Signalled to the peer
 * as a QUIC CONNECTION_CLOSE or RESET_STREAM/STOP_SENDING carrying {@link #errorCode()}, and
 * delivered to the local caller through its {@code Promise}.
 * <p>
 * Checked, mirroring {@code io.activej.quic.connection.QuicTransportException} — an H3-level
 * protocol violation is an expected protocol outcome, not a programming fault. Kept distinct from
 * {@link io.activej.http.HttpError} (a status code), {@link io.activej.http.MalformedHttpException}
 * (message-level malformation, still raised so {@code core-http} consumers see a familiar type) and
 * {@code QuicTransportException} (RFC 9000 §20 <b>transport</b> codes) — three different error axes
 * (FR-061).
 * <p>
 * <b>Security (FR-063)</b>: {@link #reason()} names the offending protocol element only — a frame
 * type, a stream state, a limit that was exceeded. It must never carry a field value, a body byte, a
 * cookie or a credential.
 */
public class Http3Exception extends Exception {
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(Http3Exception.class, "withStackTrace", false);

	private final long errorCode;
	private final String reason;
	private final boolean retryable;
	private final boolean connectionScoped;

	/**
	 * @param errorCode an {@link Http3Errors} constant
	 * @param reason    names the offending protocol element — never a field value, body byte, cookie
	 *                  or credential (FR-063). Stored and reported verbatim, never used as a format
	 *                  string
	 */
	public Http3Exception(long errorCode, String reason) {
		this(errorCode, reason, errorCode == Http3Errors.H3_REQUEST_REJECTED);
	}

	/**
	 * As {@link #Http3Exception(long, String)}, but with an explicit {@code retryable} verdict for
	 * the conditions that are retryable without being {@link Http3Errors#H3_REQUEST_REJECTED} at the
	 * call site — a GOAWAY identifier above the announced last stream, or client request-queue
	 * overflow (both raised by later, connection-layer phases).
	 */
	public Http3Exception(long errorCode, String reason, boolean retryable) {
		this(errorCode, reason, retryable, false);
	}

	/**
	 * A violation that must abort the whole <b>connection</b> even though it was observed on a single
	 * request stream, in a case the error <b>code alone cannot express</b>.
	 * <p>
	 * Almost every such case is identifiable from the code — {@code H3_ID_ERROR} and
	 * {@code H3_FRAME_UNEXPECTED} always are, and {@code Http3RequestStream} classifies them that way
	 * without help. QPACK is the exception: RFC 9204 assigns the scope <b>per cause</b> while every
	 * cause shares {@code QPACK_DECOMPRESSION_FAILED} (0x0200), so an invalid static-table index
	 * (§3.1) and an over-long string literal (§7) carry the same code and opposite scopes. This flag
	 * carries the decoder's verdict across the boundary.
	 *
	 * @see io.activej.http3.qpack.QpackException#isConnectionError()
	 */
	public static Http3Exception connectionScoped(long errorCode, String reason) {
		return new Http3Exception(errorCode, reason, false, true);
	}

	private Http3Exception(long errorCode, String reason, boolean retryable, boolean connectionScoped) {
		super(reason);
		this.errorCode = errorCode;
		this.reason = reason;
		this.retryable = retryable;
		this.connectionScoped = connectionScoped;
	}

	/**
	 * Whether this violation aborts the connection despite having been observed on one request stream,
	 * in a case the code alone does not reveal. {@code false} for every exception whose scope is
	 * derivable from {@link #errorCode()} — see {@link #connectionScoped}.
	 */
	public boolean isConnectionScoped() {
		return connectionScoped;
	}

	/** An RFC 9114 §8.1 or RFC 9204 §6 application error code — an {@link Http3Errors} constant. */
	public long errorCode() {
		return errorCode;
	}

	/** Names the offending protocol element. Never a field value, body byte, cookie or credential (FR-063). */
	public String reason() {
		return reason;
	}

	/** Whether the caller may safely re-issue the request, typically on a fresh connection. */
	public boolean isRetryable() {
		return retryable;
	}

	@Override
	public final Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}

	@Override
	public String toString() {
		return "Http3Exception[0x" + Long.toHexString(errorCode) +
			(retryable ? ", retryable" : "") +
			": " + reason + ']';
	}
}
