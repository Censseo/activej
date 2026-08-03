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

package io.activej.quic.stream;

/**
 * The peer, or the local application, aborted a stream's send half with {@code RESET_STREAM}
 * (RFC 9000 §19.4). Every pending read or write on the affected half fails with this exception; the
 * connection stays usable and its other streams are unaffected.
 * <p>
 * The error code is chosen by the <em>application protocol</em> (RFC 9000 §20.2) — it is not an
 * RFC 9000 §20 transport code, and this layer neither interprets nor validates it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.4">RFC 9000 §19.4 —
 * RESET_STREAM Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.5">RFC 9000 §3.5 — Solicited State
 * Transitions</a>
 */
public final class QuicStreamResetException extends QuicStreamException {
	private final long streamId;
	private final long applicationErrorCode;

	/**
	 * @param streamId             the aborted stream (RFC 9000 §2.1)
	 * @param applicationErrorCode the 62-bit code the abort carried (RFC 9000 §19.4). Both go into the
	 *                             message; a payload byte never does (SI-6)
	 */
	public QuicStreamResetException(long streamId, long applicationErrorCode) {
		super("Stream " + streamId + " was reset with application error code " + applicationErrorCode);
		this.streamId = streamId;
		this.applicationErrorCode = applicationErrorCode;
	}

	/** The stream that was reset (RFC 9000 §2.1). */
	public long streamId() {
		return streamId;
	}

	/** The application-chosen error code carried by {@code RESET_STREAM} (RFC 9000 §19.4, §20.2). */
	public long applicationErrorCode() {
		return applicationErrorCode;
	}

	@Override
	public String toString() {
		return "QuicStreamResetException[streamId=" + streamId +
			", applicationErrorCode=" + applicationErrorCode + ']';
	}
}
