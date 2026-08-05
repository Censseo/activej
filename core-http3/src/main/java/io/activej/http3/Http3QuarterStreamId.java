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

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.codec.QuicVarInts;

/**
 * The Quarter Stream ID that prefixes every HTTP/3 datagram (RFC 9297 §2.1): the request stream's QUIC
 * stream ID divided by four, varint-encoded, followed by the application payload. Dividing works
 * because only client-initiated bidirectional streams — the IDs RFC 9000 §2.1 makes multiples of four
 * — carry a request, so the two low bits are always zero and would be two wasted bits on every
 * datagram.
 * <p>
 * <b>What this validates</b>: the identifier mapping, and nothing else. Three conditions are
 * {@link Http3Errors#H3_DATAGRAM_ERROR} — a stream ID that is not client-initiated bidirectional, a
 * quarter stream ID whose {@code × 4} would leave the RFC 9000 §16 varint range, and a truncated
 * varint.
 * <p>
 * <b>What it deliberately does not validate</b>: whether the stream exists. A datagram for a stream
 * that has completed, been reset, or not yet been opened is <b>not</b> an error — reordering makes it
 * normal on an unreliable channel — and is dropped and counted by the caller, which is the only party
 * that can see stream state (spec FR-082).
 * <p>
 * Buffers are borrowed on every method here: {@link #read} advances the payload's head past the varint
 * and leaves ownership where it found it, and a failed read leaves the head untouched.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9297#section-2.1">RFC 9297 §2.1 — HTTP/3 Datagrams</a>
 */
public final class Http3QuarterStreamId {
	private Http3QuarterStreamId() {
	}

	/** The largest quarter stream ID whose {@code × 4} still fits a QUIC varint: 2^60 − 1. */
	public static final long MAX_VALUE = QuicVarInts.MAX_VALUE >>> 2;

	/** The largest stream ID with a quarter stream ID: {@link #MAX_VALUE} {@code × 4} = 2^62 − 4. */
	public static final long MAX_STREAM_ID = MAX_VALUE << 2;

	/**
	 * The quarter stream ID of {@code streamId} (RFC 9297 §2.1).
	 *
	 * @throws Http3Exception {@code H3_DATAGRAM_ERROR} if {@code streamId} is not a client-initiated
	 *                        bidirectional stream ID within the varint range
	 */
	public static long encode(long streamId) throws Http3Exception {
		validateStreamId(streamId);
		return streamId >>> 2;
	}

	/**
	 * The stream ID {@code quarterStreamId} maps to (RFC 9297 §2.1).
	 * <p>
	 * The range is checked <b>before</b> the multiplication, so an out-of-range quarter stream ID is
	 * refused rather than silently wrapped into a plausible small stream ID.
	 *
	 * @throws Http3Exception {@code H3_DATAGRAM_ERROR} if {@code quarterStreamId × 4} would leave the
	 *                        RFC 9000 §16 varint range
	 */
	public static long decode(long quarterStreamId) throws Http3Exception {
		if (quarterStreamId < 0 || quarterStreamId > MAX_VALUE) {
			throw Http3Exception.connectionScoped(Http3Errors.H3_DATAGRAM_ERROR,
				"Quarter stream ID " + quarterStreamId + " is outside [0, " + MAX_VALUE +
				"], so four times it is not a QUIC stream ID (RFC 9297 §2.1)");
		}
		return quarterStreamId << 2;
	}

	/**
	 * Accepts exactly the stream IDs that have a quarter stream ID: client-initiated bidirectional
	 * (RFC 9000 §2.1 — the two low bits zero) and within the varint range.
	 *
	 * @throws Http3Exception {@code H3_DATAGRAM_ERROR}, connection-scoped: RFC 9297 §2.1 terminates the
	 *                        connection rather than the exchange
	 */
	public static void validateStreamId(long streamId) throws Http3Exception {
		if (streamId < 0 || streamId > MAX_STREAM_ID) {
			throw Http3Exception.connectionScoped(Http3Errors.H3_DATAGRAM_ERROR,
				"Stream ID " + streamId + " is outside [0, " + MAX_STREAM_ID +
				"], so it has no quarter stream ID (RFC 9297 §2.1)");
		}
		if ((streamId & 0x3) != 0) {
			throw Http3Exception.connectionScoped(Http3Errors.H3_DATAGRAM_ERROR,
				"Stream " + streamId + " is not client-initiated bidirectional, " +
				"so it has no quarter stream ID (RFC 9297 §2.1)");
		}
	}

	/** The number of bytes {@link #write} emits for {@code streamId}'s quarter stream ID. */
	public static int encodedLength(long streamId) throws Http3Exception {
		return QuicVarInts.encodedLength(encode(streamId));
	}

	/**
	 * Writes {@code streamId}'s quarter stream ID, leaving {@code buf} positioned for the application
	 * payload. Nothing is written when the stream ID is refused.
	 */
	public static void write(ByteBuf buf, long streamId) throws Http3Exception {
		QuicVarInts.write(buf, encode(streamId));
	}

	/**
	 * Reads the quarter stream ID prefixing an HTTP/3 datagram and returns the stream ID it maps to,
	 * advancing {@code payload}'s head to the application payload — which may legally be empty, and is
	 * then delivered as an empty payload rather than refused.
	 *
	 * @throws Http3Exception {@code H3_DATAGRAM_ERROR} on a truncated varint or a quarter stream ID
	 *                        that maps to no stream ID
	 */
	public static long read(ByteBuf payload) throws Http3Exception {
		long quarterStreamId;
		try {
			quarterStreamId = QuicVarInts.read(payload);
		} catch (TruncatedDataException e) {
			throw Http3Exception.connectionScoped(Http3Errors.H3_DATAGRAM_ERROR,
				"Truncated quarter stream ID in an HTTP/3 datagram (RFC 9297 §2.1)");
		}
		return decode(quarterStreamId);
	}
}
