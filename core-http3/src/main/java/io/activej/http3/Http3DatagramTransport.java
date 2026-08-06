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

/**
 * The send half of one {@link Http3DatagramChannel}, bound to a single request stream at construction.
 * <p>
 * That binding is what keeps QUIC stream IDs out of the HTTP-level API (spec FR-084): the quarter stream
 * ID (RFC 9297 §2.1) that prefixes every HTTP/3 datagram is written here, by the one object that knows
 * which stream this exchange runs on, and the channel above never sees an identifier at all. The
 * production implementation is {@code Http3Connection}'s inner {@code StreamDatagramTransport}, exactly
 * as {@code StreamQpackEncoder} and {@code StreamQpackDecoder} are the per-stream views of that
 * connection's QPACK codecs.
 * <p>
 * Package-private: only an {@code Http3Connection} may build one, because only it can answer whether
 * datagrams were negotiated.
 */
interface Http3DatagramTransport {
	/**
	 * Whether an HTTP/3 datagram sent right now would be carried: both endpoints advertised
	 * {@code max_datagram_frame_size} and {@code SETTINGS_H3_DATAGRAM = 1}, and both SETTINGS have been
	 * exchanged (RFC 9297 §2.1.1, spec FR-083).
	 */
	boolean isAvailable();

	/**
	 * The largest application payload that still fits one DATAGRAM frame at the peer's advertised
	 * {@code max_datagram_frame_size}, after the frame's own overhead and this stream's quarter stream
	 * ID. {@code 0} when datagrams are unavailable.
	 */
	long maxPayloadSize();

	/**
	 * Prefixes {@code payload} with this stream's quarter stream ID and queues it as one RFC 9221
	 * DATAGRAM frame.
	 * <p>
	 * <b>Takes ownership of {@code payload} on every path, refusals included</b> — it is recycled before
	 * this throws, and recycling it again at the call site is a double free.
	 */
	void send(ByteBuf payload) throws Http3DatagramException;

	/**
	 * The channel dropped its oldest queued datagram at {@code maxInboundDatagramsPerStream} (FR-085).
	 * <p>
	 * Reported upwards rather than counted only per exchange because the connection is where an
	 * {@code Inspector} lives, and the bound being per exchange is exactly what makes a connection-wide
	 * total worth having. Defaulted, so a channel built over a stub transport reports nothing.
	 */
	default void onDroppedByQueue() {}

	/**
	 * The channel refused a send whose payload exceeded {@link #maxPayloadSize()} (RFC 9221 §3). Reported
	 * from the channel because it is the side that holds both numbers.
	 */
	default void onRefusedOversize(int payloadBytes, long maxPayloadBytes) {}
}
