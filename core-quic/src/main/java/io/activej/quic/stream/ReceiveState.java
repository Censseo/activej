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
 * The states of a stream's receiving part (RFC 9000 §3.2).
 *
 * <pre>
 *     o
 *     | Recv STREAM / STREAM_DATA_BLOCKED / RESET_STREAM
 *     | Create Bidirectional Stream (Sending)
 *     v
 * +------+   Recv STREAM + FIN   +------------+   Recv All Data   +------------+
 * | Recv | --------------------> | Size Known | ----------------> | Data Recvd |
 * +------+                       +------------+                   +------------+
 *     |                                |                                |
 *     | Recv RESET_STREAM              | Recv RESET_STREAM              | App Read All Data
 *     v                                v                                v
 * +-------------+                                                 +-----------+
 * | Reset Recvd | -- App Read Reset --> +------------+            | Data Read |
 * +-------------+                       | Reset Read |            +-----------+
 *                                       +------------+
 * </pre>
 * <p>
 * {@link #DATA_READ} and {@link #RESET_READ} are the terminal states, and both require an
 * <em>application</em> action, not merely a wire event: the stream stays known to the manager until
 * the application has drained it (RFC 9000 §3.2).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3.2">RFC 9000 §3.2 — Receiving
 * Stream States</a>
 */
public enum ReceiveState {
	/**
	 * This endpoint has no receive part — a locally-initiated unidirectional stream (RFC 9000 §2.1).
	 * Not an RFC 9000 §3.2 state; it exists so that {@code receiveState()} is total rather than
	 * nullable.
	 */
	NONE,

	/** Data is arriving and the final size is not yet known (RFC 9000 §3.2). */
	RECV,

	/** {@code FIN} has arrived, so the final size is known, but gaps remain (RFC 9000 §4.5). */
	SIZE_KNOWN,

	/** Every byte up to the final size has arrived; the application has not read it all yet. */
	DATA_RECVD,

	/** The application has read every byte and observed end-of-stream — terminal. */
	DATA_READ,

	/** {@code RESET_STREAM} (RFC 9000 §19.4) has arrived; the application has not observed it yet. */
	RESET_RECVD,

	/** The application has observed the reset — terminal (RFC 9000 §3.2). */
	RESET_READ
}
