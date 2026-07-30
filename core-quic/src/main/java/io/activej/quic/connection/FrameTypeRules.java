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

import io.activej.quic.codec.*;
import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Which frame types may appear in which packet type (RFC 9000 §12.4, Table 3), enforced in both
 * directions (FR-013).
 * <p>
 * Initial and Handshake packets carry only CRYPTO, ACK, CONNECTION_CLOSE (transport form) and PADDING —
 * and PING. Everything else is 1-RTT only. HANDSHAKE_DONE is further restricted: only a server sends it.
 * <p>
 * A decrypted payload must also contain at least one frame; an empty payload is a
 * {@code PROTOCOL_VIOLATION} (RFC 9000 §12.4).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.4">RFC 9000 §12.4 — Frames and Frame Types</a>
 */
public final class FrameTypeRules {
	private FrameTypeRules() {}

	/**
	 * Whether {@code frame} is permitted in a packet at {@code level}.
	 * <p>
	 * The switch is over concrete frame classes rather than wire type codes, so a frame type added by a
	 * later feature must be classified here explicitly rather than defaulting to "allowed".
	 */
	public static boolean isAllowed(QuicFrame frame, EncryptionLevel level) {
		boolean handshakeLevel = level == EncryptionLevel.INITIAL || level == EncryptionLevel.HANDSHAKE;
		if (frame instanceof PaddingFrame
			|| frame instanceof PingFrame
			|| frame instanceof AckFrame
			|| frame instanceof CryptoFrame) {
			return true;
		}
		if (frame instanceof ConnectionCloseFrame close) {
			// RFC 9000 §12.4: only the transport form (0x1c) is allowed at Initial/Handshake; the
			// application form (0x1d) requires 1-RTT keys.
			return !handshakeLevel || !close.isApplication;
		}
		// Everything below is 1-RTT only.
		return !handshakeLevel;
	}

	/**
	 * Validates every frame of a received payload.
	 *
	 * @param frames the frames read from one packet's decrypted payload
	 * @param level  the encryption level the packet arrived at
	 * @param peerIsServer whether the frames came from a server; only a server may send HANDSHAKE_DONE
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION} on an empty payload or a misplaced frame
	 */
	public static void validateReceived(List<QuicFrame> frames, EncryptionLevel level, boolean peerIsServer)
		throws QuicTransportException {
		if (frames.isEmpty()) {
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"a decrypted payload must contain at least one frame");
		}
		for (QuicFrame frame : frames) {
			if (!isAllowed(frame, level)) {
				throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION, frameTypeOf(frame),
					frame.getClass().getSimpleName() + " is not permitted in a " + level + " packet");
			}
			if (frame instanceof HandshakeDoneFrame && !peerIsServer) {
				// RFC 9000 §19.20: a client that receives HANDSHAKE_DONE from a client is looking at a
				// protocol violation, not a stray frame.
				throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
					frameTypeOf(frame), "HANDSHAKE_DONE may only be sent by a server");
			}
		}
	}

	/**
	 * Guards the send path with the same table, so a local bug cannot put a frame on the wire that the
	 * peer is required to reject.
	 *
	 * @throws QuicTransportException {@code INTERNAL_ERROR} — this is our own fault, not the peer's
	 */
	public static void validateForSending(QuicFrame frame, EncryptionLevel level) throws QuicTransportException {
		if (!isAllowed(frame, level)) {
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"refusing to send " + frame.getClass().getSimpleName() + " in a " + level + " packet");
		}
	}

	/**
	 * The RFC 9000 §12.4 type code, for a CONNECTION_CLOSE frame's Frame Type field (RFC 9000 §19.19).
	 * <p>
	 * Returns {@code null} when the type is not one a single code identifies — STREAM, MAX_STREAMS,
	 * STREAMS_BLOCKED and DATAGRAM all span several codes, and the field is optional, so omitting it is
	 * correct rather than guessing.
	 */
	private static @Nullable Long frameTypeOf(QuicFrame frame) {
		// An if-chain rather than a pattern switch: this module compiles at -source 17, where patterns
		// in switch are not available (CI runs JDK 21/25, but the release target is 17).
		if (frame instanceof PaddingFrame) return (long) PaddingFrame.TYPE;
		if (frame instanceof PingFrame) return (long) PingFrame.TYPE;
		if (frame instanceof AckFrame) {
			return (long) (((AckFrame) frame).hasEcnCounts ? AckFrame.TYPE_WITH_ECN : AckFrame.TYPE_WITHOUT_ECN);
		}
		if (frame instanceof CryptoFrame) return (long) CryptoFrame.TYPE;
		if (frame instanceof HandshakeDoneFrame) return (long) HandshakeDoneFrame.TYPE;
		if (frame instanceof NewTokenFrame) return (long) NewTokenFrame.TYPE;
		if (frame instanceof MaxDataFrame) return (long) MaxDataFrame.TYPE;
		if (frame instanceof MaxStreamDataFrame) return (long) MaxStreamDataFrame.TYPE;
		if (frame instanceof DataBlockedFrame) return (long) DataBlockedFrame.TYPE;
		if (frame instanceof StreamDataBlockedFrame) return (long) StreamDataBlockedFrame.TYPE;
		if (frame instanceof ResetStreamFrame) return (long) ResetStreamFrame.TYPE;
		if (frame instanceof StopSendingFrame) return (long) StopSendingFrame.TYPE;
		if (frame instanceof NewConnectionIdFrame) return (long) NewConnectionIdFrame.TYPE;
		if (frame instanceof RetireConnectionIdFrame) return (long) RetireConnectionIdFrame.TYPE;
		if (frame instanceof PathChallengeFrame) return (long) PathChallengeFrame.TYPE;
		if (frame instanceof PathResponseFrame) return (long) PathResponseFrame.TYPE;
		if (frame instanceof ConnectionCloseFrame) {
			return (long) (((ConnectionCloseFrame) frame).isApplication
				? ConnectionCloseFrame.TYPE_APPLICATION : ConnectionCloseFrame.TYPE_TRANSPORT);
		}
		return null;
	}
}
