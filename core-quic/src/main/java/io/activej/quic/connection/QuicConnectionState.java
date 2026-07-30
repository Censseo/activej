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

/**
 * The lifecycle of a QUIC connection (RFC 9000 §10).
 * <p>
 * {@code CLOSING} and {@code DRAINING} are distinct on purpose: an endpoint that <i>initiates</i> a
 * close enters {@code CLOSING} and re-sends its CONNECTION_CLOSE in response to further packets,
 * whereas one that <i>receives</i> a close enters {@code DRAINING} and sends nothing more
 * (RFC 9000 §10.2.1, §10.2.2).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10">RFC 9000 §10 — Connection Termination</a>
 */
public enum QuicConnectionState {
	/** Created but not yet started. */
	IDLE,
	/** The TLS handshake is in progress. */
	HANDSHAKING,
	/** The handshake is confirmed; 1-RTT traffic flows. */
	ESTABLISHED,
	/** We sent CONNECTION_CLOSE and echo it in response to incoming packets (RFC 9000 §10.2.1). */
	CLOSING,
	/** The peer closed, or we finished closing; nothing further is sent (RFC 9000 §10.2.2). */
	DRAINING,
	/** Terminal: all state released. */
	CLOSED;

	/** Whether the connection can still send application data. */
	public boolean isOpen() {
		return this == HANDSHAKING || this == ESTABLISHED;
	}

	/** Whether the connection is shutting down or has shut down. */
	public boolean isTerminating() {
		return this == CLOSING || this == DRAINING || this == CLOSED;
	}
}
