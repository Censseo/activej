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

package io.activej.http3.qpack;

import io.activej.common.ApplicationSettings;
import io.activej.http3.Http3Errors;

/**
 * A QPACK (RFC 9204 §6) field-line-compression failure: an undecodable field section, or an
 * illegal instruction on the peer's encoder/decoder stream.
 * <p>
 * Checked, mirroring {@code io.activej.http3.Http3Exception} in shape — {@link #errorCode()},
 * {@link #reason()}, the {@link #WITH_STACK_TRACE} gate, the {@code fillInStackTrace} override —
 * but this class depends on nothing in {@code io.activej.http3} beyond {@link Http3Errors}, a pure
 * constant holder, so this package stays independent of the reactive layer (see this package's
 * {@code package-info.java}).
 * <p>
 * Carries exactly one of {@link Http3Errors#QPACK_DECOMPRESSION_FAILED},
 * {@link Http3Errors#QPACK_ENCODER_STREAM_ERROR} or {@link Http3Errors#QPACK_DECODER_STREAM_ERROR}.
 * <p>
 * <b>Security (FR-063)</b>: {@link #reason()} names the offending protocol element only — a
 * representation type, a static-table index, a declared length. It must never carry a field name,
 * a field value, or any byte of either.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9204#section-6">RFC 9204 §6 — Error Handling</a>
 */
public final class QpackException extends Exception {
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(QpackException.class, "withStackTrace", false);

	private final long errorCode;
	private final String reason;

	/**
	 * @param errorCode one of {@link Http3Errors#QPACK_DECOMPRESSION_FAILED},
	 *                  {@link Http3Errors#QPACK_ENCODER_STREAM_ERROR} or
	 *                  {@link Http3Errors#QPACK_DECODER_STREAM_ERROR}
	 * @param reason    names the offending protocol element — never a field name, field value, or
	 *                  a byte of either (FR-063). Stored and reported verbatim, never used as a
	 *                  format string
	 */
	public QpackException(long errorCode, String reason) {
		super(reason);
		this.errorCode = errorCode;
		this.reason = reason;
	}

	/** One of the three RFC 9204 §6 codes declared on {@link Http3Errors}. */
	public long errorCode() {
		return errorCode;
	}

	/** Names the offending protocol element. Never a field name, field value, or a byte of either (FR-063). */
	public String reason() {
		return reason;
	}

	@Override
	public Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}

	@Override
	public String toString() {
		return "QpackException[0x" + Long.toHexString(errorCode) + ": " + reason + ']';
	}
}
