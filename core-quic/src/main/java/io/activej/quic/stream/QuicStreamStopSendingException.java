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
 * The peer asked this endpoint to stop sending on a stream, via {@code STOP_SENDING}
 * (RFC 9000 §19.5). The pending write fails with this exception; RFC 9000 §3.5 then has this
 * endpoint abort its own send half with {@code RESET_STREAM}, so the two frames pair up.
 * <p>
 * The error code is chosen by the <em>application protocol</em> (RFC 9000 §20.2) — it is not an
 * RFC 9000 §20 transport code, and this layer neither interprets nor validates it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.5">RFC 9000 §19.5 —
 * STOP_SENDING Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.5">RFC 9000 §3.5 — Solicited State
 * Transitions</a>
 */
public final class QuicStreamStopSendingException extends QuicStreamException {
	private final long streamId;
	private final long applicationErrorCode;

	/**
	 * The peer's request, as {@code STOP_SENDING} (RFC 9000 §19.5).
	 *
	 * @param streamId             the stream the peer wants nothing more on (RFC 9000 §2.1)
	 * @param applicationErrorCode the 62-bit code the request carried. Both go into the message; a
	 *                             payload byte never does (SI-6)
	 */
	public QuicStreamStopSendingException(long streamId, long applicationErrorCode) {
		this("Peer asked to stop sending on stream " + streamId +
			 " with application error code " + applicationErrorCode,
			streamId, applicationErrorCode);
	}

	private QuicStreamStopSendingException(String message, long streamId, long applicationErrorCode) {
		super(message);
		this.streamId = streamId;
		this.applicationErrorCode = applicationErrorCode;
	}

	/**
	 * The same fact seen from the other end: <em>this</em> endpoint abandoned a stream's receiving half
	 * with {@code QuicStream.stopSending} (FR-032), so its reader fails with the code it chose itself.
	 * <p>
	 * A separate factory rather than a separate type, because a reader that wants to know only "no more
	 * data is coming, and here is the code" should not have to catch two exceptions to learn it. Only
	 * the message differs, and only so that a log line does not blame the peer for a local decision.
	 */
	public static QuicStreamStopSendingException requestedLocally(long streamId, long applicationErrorCode) {
		return new QuicStreamStopSendingException(
			"Stream " + streamId + " will accept no more data: this endpoint asked the peer to stop" +
			" with application error code " + applicationErrorCode,
			streamId, applicationErrorCode);
	}

	/** The stream the peer no longer wants data on (RFC 9000 §2.1). */
	public long streamId() {
		return streamId;
	}

	/** The application-chosen error code carried by {@code STOP_SENDING} (RFC 9000 §19.5, §20.2). */
	public long applicationErrorCode() {
		return applicationErrorCode;
	}

	@Override
	public String toString() {
		return "QuicStreamStopSendingException[streamId=" + streamId +
			", applicationErrorCode=" + applicationErrorCode + ']';
	}
}
