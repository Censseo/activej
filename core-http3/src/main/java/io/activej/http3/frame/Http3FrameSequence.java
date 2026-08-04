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
 *
 * <h2>Why PUSH_PROMISE is not just another illegal type</h2>
 * Every violation of this grammar is reported as {@code H3_FRAME_UNEXPECTED} — "a frame arrived where
 * RFC 9114 §7.2's table does not permit it" — with exactly one exception. PUSH_PROMISE
 * ({@code 0x05}) is {@code H3_ID_ERROR} instead, because RFC 9114 §7.2.5 judges it against the
 * <i>push limit</i> rather than against the stream it arrived on: a client that has sent no
 * MAX_PUSH_ID has granted no push id, so a promise names an identifier that was never issued. This
 * implementation never sends MAX_PUSH_ID at all (FR-040, server push is permanently out of scope), so
 * that condition holds on every connection, always. CANCEL_PUSH, SETTINGS, GOAWAY and MAX_PUSH_ID on a
 * request stream stay {@code H3_FRAME_UNEXPECTED}: those are RFC 9114 §7.2.3/§7.2.4/§7.2.6/§7.2.7's own
 * must-be-on-the-control-stream rules, which have nothing to do with push ids.
 */
public final class Http3FrameSequence {
	/**
	 * PUSH_PROMISE (RFC 9114 §7.2.5). It has no dedicated {@link Http3Frame} subtype — push is refused
	 * outright (FR-040), so nothing here ever builds or reads one — and is therefore named by its raw
	 * type code. Deliberately <b>not</b> a member of {@link #ALWAYS_ILLEGAL_ON_REQUEST_STREAM}: it has
	 * its own error code, for the reason set out in this class's Javadoc.
	 */
	private static final long PUSH_PROMISE_TYPE = 0x05L;

	/**
	 * Frame types RFC 9114 §7.2's table never permits on a request stream regardless of sequence
	 * state — the control-only frames (CANCEL_PUSH, SETTINGS, GOAWAY, MAX_PUSH_ID) plus the RFC 9114
	 * §7.2.8 HTTP/2 frame types this implementation never accepts on any stream. Anything else —
	 * including a genuinely unknown or GREASE type (RFC 9114 §9) — is tolerated here: FR-023 requires
	 * unknown, non-reserved types be discarded without failing the connection, and FR-024's enumeration
	 * of request-stream rejections lists only the control-only types below, not "unknown".
	 * <p>
	 * {@link #PUSH_PROMISE_TYPE} is handled separately, with its own code.
	 */
	private static final Set<Long> ALWAYS_ILLEGAL_ON_REQUEST_STREAM = Set.of(
		CancelPushFrame.TYPE, SettingsFrame.TYPE, GoAwayFrame.TYPE, MaxPushIdFrame.TYPE,
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
	 * @throws Http3Exception {@code H3_FRAME_UNEXPECTED} for every violation of this grammar — "a
	 *                        frame arrived where RFC 9114 §7.2's table does not permit it" — except
	 *                        PUSH_PROMISE, which is {@code H3_ID_ERROR} (RFC 9114 §7.2.5; see this
	 *                        class's Javadoc)
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
		if (frameType == PUSH_PROMISE_TYPE) {
			// RFC 9114 §7.2.5, and the one violation here that is not H3_FRAME_UNEXPECTED: a promise is
			// judged against the push limit, which this implementation never raises above 0 (FR-040).
			throw new Http3Exception(Http3Errors.H3_ID_ERROR,
				"A PUSH_PROMISE frame against a push limit of 0 (RFC 9114 §7.2.5)");
		}
		if (ALWAYS_ILLEGAL_ON_REQUEST_STREAM.contains(frameType)) {
			throw unexpected("frame type 0x" + Long.toHexString(frameType) + " is not permitted on a request stream");
		}
		// A genuinely unknown or GREASE frame type (RFC 9114 §9) is tolerated, not a sequence
		// violation — FR-023. The state does not change: it carries no HEADERS/DATA semantics.
		return state;
	}

	/**
	 * Withdraws the leading HEADERS frame just accepted, because its field section turned out to carry an
	 * informational ({@code 1xx}) {@code :status}. RFC 9114 §4.1 lets a server send any number of interim
	 * responses ahead of the final one, and each is a HEADERS frame that opens <i>nothing</i> — the
	 * sequence is still waiting for the one that does. Without this the final response's HEADERS would be
	 * read as the trailer section of the interim one.
	 * <p>
	 * The grammar cannot decide this for itself, which is why it is a separate call: a frame <i>type</i>
	 * is all this class ever sees, and 1xx is a property of the field section behind it. Only the reactive
	 * layer that decoded that section can say so, and only on a response.
	 *
	 * @throws IllegalStateException if anything but the leading HEADERS frame is being withdrawn — a
	 *                               programming error in the caller, not a peer's doing
	 */
	public void withdrawInformationalHeaders() {
		if (state != State.HEADERS_DONE) {
			throw new IllegalStateException(
				"Only a leading HEADERS frame can be withdrawn as informational, not one in state " + state);
		}
		state = State.IDLE;
	}

	private static Http3Exception unexpected(String detail) {
		return new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED, detail);
	}
}
