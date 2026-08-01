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

package io.activej.http3.frame;

import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;

import java.util.Set;

/**
 * The RFC 9114 §4.1 request-stream frame-sequence validator:
 * {@code HEADERS -> DATA* -> HEADERS?}. Extracted as a plain synchronous state machine so it is
 * testable with no transport and no eventloop (research Decision 13) — the reactive
 * {@code Http3RequestStream} drives an instance of this class per request stream and owns
 * everything reactive around it.
 * <p>
 * This class decides only whether a frame <i>type</i> is legal in the current state. It has no
 * opinion on frame payload contents (that is {@code Http3Headers}) or on FIN timing (that is the
 * reactive layer, which is the only place a QUIC stream's end is visible).
 */
public final class Http3FrameSequence {
	/**
	 * Frame types RFC 9114 §7.2's table never permits on a request stream regardless of sequence
	 * state — the control-only frames (CANCEL_PUSH, SETTINGS, PUSH_PROMISE, GOAWAY, MAX_PUSH_ID;
	 * PUSH_PROMISE {@code 0x05} has no dedicated {@link Http3Frame} subtype since push is refused
	 * outright by a later phase, so it is named here by its raw type code) plus the RFC 9114 §7.2.8
	 * HTTP/2 frame types this implementation never accepts on any stream. Anything else — including
	 * a genuinely unknown or GREASE type (RFC 9114 §9) — is tolerated here: FR-023 requires unknown,
	 * non-reserved types be discarded without failing the connection, and FR-024's enumeration of
	 * request-stream rejections lists only the control-only types below, not "unknown".
	 */
	private static final Set<Long> ALWAYS_ILLEGAL_ON_REQUEST_STREAM = Set.of(
		CancelPushFrame.TYPE, SettingsFrame.TYPE, 0x05L /* PUSH_PROMISE */, GoAwayFrame.TYPE, MaxPushIdFrame.TYPE,
		0x02L, 0x06L, 0x08L, 0x09L /* reserved HTTP/2 types, RFC 9114 §7.2.8 */);

	public enum State {
		/** No HEADERS frame received yet. */
		IDLE,
		/** The leading HEADERS frame has been accepted. */
		HEADERS_DONE,
		/** At least one DATA frame has been accepted since the leading HEADERS. */
		BODY,
		/** The optional trailing HEADERS frame has been accepted; the exchange is done but for FIN. */
		TRAILERS_DONE
	}

	private State state = State.IDLE;

	public State state() {
		return state;
	}

	/**
	 * Advances the sequence on receipt (or send) of a frame of type {@code frameType}.
	 *
	 * @return the new state
	 * @throws Http3Exception {@code H3_FRAME_UNEXPECTED} — always this code, since every violation
	 *                        of this grammar is "a frame arrived where RFC 9114 §7.2's table does
	 *                        not permit it"
	 */
	public State accept(long frameType) throws Http3Exception {
		if (frameType == HeadersFrame.TYPE) {
			state = switch (state) {
				case IDLE -> State.HEADERS_DONE;
				case HEADERS_DONE, BODY -> State.TRAILERS_DONE;
				case TRAILERS_DONE -> throw unexpected("a third HEADERS frame");
			};
			return state;
		}
		if (frameType == DataFrame.TYPE) {
			state = switch (state) {
				case IDLE -> throw unexpected("DATA before HEADERS");
				case HEADERS_DONE, BODY -> State.BODY;
				case TRAILERS_DONE -> throw unexpected("DATA after the trailing HEADERS");
			};
			return state;
		}
		if (ALWAYS_ILLEGAL_ON_REQUEST_STREAM.contains(frameType)) {
			throw unexpected("frame type 0x" + Long.toHexString(frameType) + " is not permitted on a request stream");
		}
		// A genuinely unknown or GREASE frame type (RFC 9114 §9) is tolerated, not a sequence
		// violation — FR-023. The state does not change: it carries no HEADERS/DATA semantics.
		return state;
	}

	private static Http3Exception unexpected(String detail) {
		return new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED, detail);
	}
}
