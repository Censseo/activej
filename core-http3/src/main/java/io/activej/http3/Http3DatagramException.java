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
 * One HTTP/3 datagram (RFC 9297) was refused locally by
 * {@link Http3DatagramChannel#send(io.activej.bytebuf.ByteBuf)}.
 * <p>
 * Deliberately <b>not</b> an {@link Http3Exception}: nothing here is a protocol violation, nothing here
 * carries an RFC 9114 §8.1 code and nothing here closes a stream or a connection. Refusing an
 * unreliable send is a normal outcome — datagrams were never negotiated on this connection, the payload
 * is larger than the peer said it would accept, the transport's outbound queue is at its bound, or the
 * exchange this handle belongs to has ended.
 * <p>
 * Also deliberately not {@code QuicDatagramException} reused, for two reasons. Its {@code Reason} has no
 * member for {@link Reason#EXCHANGE_ENDED}, which is an HTTP-level condition the transport cannot
 * observe; and an HTTP-level API should not make its caller catch a transport type. (FR-058c's rule
 * that a peer's code survives unwrapped does not apply — a local refusal carries no peer-chosen code at
 * all.) A refusal that originated in the transport keeps its {@code QuicDatagramException} as the
 * {@linkplain #getCause() cause}.
 * <p>
 * Checked, so a caller cannot ignore the fact that the send did not happen. The payload has already been
 * recycled by the time this is thrown, on every one of the four paths.
 * <p>
 * <b>Security (SI-6)</b>: the message names sizes and counts only. It never carries a byte of the
 * payload.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297#section-2.1">RFC 9297 §2.1 — HTTP/3 Datagrams</a>
 */
public final class Http3DatagramException extends Exception {
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(Http3DatagramException.class, "withStackTrace", false);

	/** Why the send was refused. Distinguished because the remedy differs for each. */
	public enum Reason {
		/**
		 * Datagrams are not available on this connection: either endpoint failed to advertise
		 * {@code SETTINGS_H3_DATAGRAM = 1} with {@code max_datagram_frame_size}, or the peer's SETTINGS
		 * have not arrived yet (RFC 9297 §2.1.1, spec FR-083).
		 */
		NOT_NEGOTIATED,
		/** The datagram would exceed {@link Http3DatagramChannel#maxPayloadSize()}; never truncated. */
		OVERSIZE,
		/** The transport's outbound datagram queue is full; nothing is evicted to make room. */
		QUEUE_FULL,
		/** This exchange has ended, or the connection carrying it has. Nothing further will be sent. */
		EXCHANGE_ENDED,
	}

	private final Reason reason;

	/**
	 * @param reason  why the send was refused
	 * @param message names the size, count or setting that refused it — never a payload byte (SI-6).
	 *                Stored and reported verbatim, never used as a format string
	 */
	public Http3DatagramException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	/**
	 * As {@link #Http3DatagramException(Reason, String)}, for a refusal that originated in the
	 * transport: {@code cause} is the {@code QuicDatagramException} that carried it, kept so the
	 * transport-level verdict survives the HTTP-level type.
	 */
	public Http3DatagramException(Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	/** Why the send was refused; the remedy differs per member, so callers switch on it. */
	public Reason reason() {
		return reason;
	}

	/** Suppressed unless {@link #WITH_STACK_TRACE} is set: a refusal is an expected outcome, not a fault. */
	@Override
	public Throwable fillInStackTrace() {
		return WITH_STACK_TRACE ? super.fillInStackTrace() : this;
	}

	@Override
	public String toString() {
		return "Http3DatagramException[" + reason + ": " + getMessage() + ']';
	}
}
