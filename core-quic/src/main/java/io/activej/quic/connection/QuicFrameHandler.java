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

import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;

/**
 * The extension point through which a layer above this one — streams, HTTP/3, DATAGRAM — receives the
 * frames the transport does not own, and contributes frames of its own (FR-037, FR-038).
 * <p>
 * The transport keeps for itself exactly the frames that decide connection state: PADDING, PING, ACK,
 * CRYPTO, CONNECTION_CLOSE and HANDSHAKE_DONE. Everything a peer can send that carries <i>application</i>
 * meaning arrives here. The split is not negotiable from this side: a handler that intercepted ACK could
 * break loss recovery, and one that intercepted CRYPTO could break the handshake.
 * <p>
 * <b>Buffer ownership (DI-1) differs per method, and getting it wrong is a leak or a double recycle:</b>
 * <ul>
 *   <li>{@link #onFrame} — the frame is <b>borrowed</b>. Its buffers are recycled by the connection as
 *       soon as the method returns, so a handler that needs the bytes must copy or
 *       {@code slice()} them.</li>
 *   <li>{@link #onFrameAcknowledged} and {@link #onFrameLost} — ownership <b>passes to the handler</b>.
 *       It must recycle the frame, or re-enqueue it (which hands ownership back).</li>
 * </ul>
 * <p>
 * <b>Failure (FR-038a).</b> A {@link QuicTransportException} thrown from any method closes the
 * connection with <i>that</i> error code, which is how a handler reports a peer's protocol violation in
 * its own terms. Any other exception is the handler's own bug and closes the connection with
 * {@code INTERNAL_ERROR} — never silently, because a handler that has thrown has unknown state.
 * <p>
 * Every method runs on the connection's reactor thread.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.4">RFC 9000 §12.4 — Frames and Frame Types</a>
 */
public interface QuicFrameHandler {
	/**
	 * A frame the transport does not own has arrived. The frame is <b>borrowed</b> — copy anything that
	 * must outlive this call.
	 *
	 * @throws QuicTransportException to close the connection with a specific error code, which is how a
	 *                                handler rejects a frame the peer was not permitted to send
	 */
	void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) throws QuicTransportException;

	/**
	 * A frame this handler contributed has been acknowledged. <b>Ownership passes to the handler.</b>
	 * <p>
	 * Default: recycle it. Overriding without recycling, and without re-enqueueing, leaks.
	 */
	default void onFrameAcknowledged(QuicConnection connection, QuicFrame frame) {
		Recyclers.recycle(frame);
	}

	/**
	 * A frame this handler contributed was declared lost. <b>Ownership passes to the handler</b>, which
	 * decides whether the data still matters — the transport cannot know.
	 * <p>
	 * Default: recycle it, i.e. do not retransmit. That is the right default for anything whose value has
	 * expired by the time its loss is known, and the wrong one for reliable stream data, which is why the
	 * decision is the handler's.
	 */
	default void onFrameLost(QuicConnection connection, QuicFrame frame) {
		Recyclers.recycle(frame);
	}

	/**
	 * The connection is established and its 1-RTT keys are installed, so
	 * {@link QuicConnection#enqueueFrame} will now be sent rather than queued behind a handshake.
	 */
	default void onEstablished(QuicConnection connection) {
	}

	/** The connection has ended. Called once, whatever ended it. */
	default void onClosed(QuicConnection connection) {
	}
}
