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

/**
 * One RFC 9221 DATAGRAM frame was refused locally by {@link QuicConnection#sendDatagramFrame(io.activej.bytebuf.ByteBuf)}.
 * <p>
 * Deliberately <b>not</b> a {@link QuicTransportException}: nothing here is a wire error and nothing here
 * closes the connection. Refusing an unreliable send is a normal outcome — the peer never negotiated
 * datagrams, the payload is larger than the peer said it would accept, or this endpoint's outbound queue
 * is at its bound — and reporting any of the four as {@code INTERNAL_ERROR} would tear down a working
 * connection over a message that was never obliged to arrive.
 * <p>
 * Checked, so the caller cannot ignore the fact that the send did not happen. The payload has already
 * been recycled by the time this is thrown, on every one of the four paths.
 * <p>
 * <b>Security (SI-6)</b>: the message names sizes, counts and the setting that bounds them. It never
 * carries a byte of the payload.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
 */
public final class QuicDatagramException extends Exception {
	public static final boolean WITH_STACK_TRACE =
		ApplicationSettings.getBoolean(QuicDatagramException.class, "withStackTrace", false);

	/** Why the send was refused. Distinguished because the remedy differs for each. */
	public enum Reason {
		/** The peer advertised no {@code max_datagram_frame_size}, or advertised 0 (RFC 9221 §3). */
		NOT_NEGOTIATED,
		/** The frame would exceed the peer's advertised {@code max_datagram_frame_size}; never truncated. */
		OVERSIZE,
		/** {@code maxOutboundDatagrams} datagrams are already waiting for a packet. */
		QUEUE_FULL,
		/** The connection is closing, draining or closed, so nothing further will be sent. */
		CONNECTION_CLOSED
	}

	private final Reason reason;

	public QuicDatagramException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

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
		return "QuicDatagramException[" + reason + ": " + getMessage() + ']';
	}
}
