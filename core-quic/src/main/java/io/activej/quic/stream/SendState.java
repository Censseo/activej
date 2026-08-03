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
 * The states of a stream's sending part (RFC 9000 §3.1).
 *
 * <pre>
 *     o
 *     | Create Stream (Sending)
 *     | Peer Creates Bidirectional Stream
 *     v
 * +-------+  Send STREAM / STREAM_DATA_BLOCKED  +------+
 * | Ready | ----------------------------------> | Send |
 * +-------+                                     +------+
 *     |                                            |
 *     |                       Send STREAM + FIN    |
 *     |                                            v
 *     |                                     +-----------+
 *     |                                     | Data Sent |
 *     |                                     +-----------+
 *     |                                            |
 *     | Send RESET_STREAM                          | Recv All ACKs
 *     v                                            v
 * +------------+   Recv ACK   +------------+   +------------+
 * | Reset Sent | -----------> |Reset Recvd |   | Data Recvd |
 * +------------+              +------------+   +------------+
 * </pre>
 * <p>
 * {@link #RESET_SENT} is reachable from {@link #READY}, {@link #SEND} and {@link #DATA_SENT} alike:
 * an application may abort at any point before every byte has been acknowledged.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.1">RFC 9000 §3.1 — Sending Stream
 * States</a>
 */
public enum SendState {
	/**
	 * This endpoint has no send part — a peer-initiated unidirectional stream (RFC 9000 §2.1). Not
	 * an RFC 9000 §3.1 state; it exists so that {@code sendState()} is total rather than nullable.
	 */
	NONE,

	/** The stream exists and can accept data, but nothing has been sent yet (RFC 9000 §3.1). */
	READY,

	/** At least one {@code STREAM} frame has been sent and the final size is not yet known. */
	SEND,

	/** Every byte, including the one carrying {@code FIN}, has been sent at least once. */
	DATA_SENT,

	/** Every byte, and the {@code FIN}, has been acknowledged — terminal (RFC 9000 §3.1). */
	DATA_RECVD,

	/** {@code RESET_STREAM} (RFC 9000 §19.4) has been sent and is not yet acknowledged. */
	RESET_SENT,

	/** The {@code RESET_STREAM} has been acknowledged — terminal (RFC 9000 §3.1). */
	RESET_RECVD
}
