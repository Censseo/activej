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
 * A local request to open a stream could not be withheld any longer: the peer has granted no stream
 * credit for this direction (RFC 9000 §4.6), and {@code maxPendingStreamOpens} requests are already
 * waiting for one (FR-029).
 * <p>
 * Withholding rather than violating the peer's limit is what RFC 9000 §4.6 requires; bounding how
 * many requests may be withheld is what keeps a stalled peer from growing this endpoint's memory
 * without limit (SI-3). At the bound the request fails immediately instead of queueing.
 * <p>
 * The message names the {@code maxPendingStreamOpens} setting key so that the fix is greppable from
 * a log line alone (CHK017). The setting is resolved as
 * {@code io.activej.quic.connection.QuicConnection.maxPendingStreamOpens} (or
 * {@code QuicConnection.maxPendingStreamOpens}), default 128.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling
 * Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.14">RFC 9000 §19.14 —
 * STREAMS_BLOCKED Frames</a>
 */
public final class QuicStreamLimitException extends QuicStreamException {
	private final StreamDirection direction;
	private final int maxPendingStreamOpens;

	/**
	 * @param direction             the direction whose RFC 9000 §4.6 credit is exhausted
	 * @param maxPendingStreamOpens the local bound that was reached; the message names the
	 *                              {@code ApplicationSettings} key, so the fix is greppable from one
	 *                              log line (FR-029)
	 */
	public QuicStreamLimitException(StreamDirection direction, int maxPendingStreamOpens) {
		super("Cannot open a " + direction + " stream: the peer has granted no stream credit and " +
			"maxPendingStreamOpens (" + maxPendingStreamOpens + ") requests are already withheld");
		this.direction = direction;
		this.maxPendingStreamOpens = maxPendingStreamOpens;
	}

	/** The direction whose stream credit is exhausted (RFC 9000 §4.6 counts the two separately). */
	public StreamDirection direction() {
		return direction;
	}

	/** The {@code maxPendingStreamOpens} bound that was reached — the setting key named in the message. */
	public int maxPendingStreamOpens() {
		return maxPendingStreamOpens;
	}

	@Override
	public String toString() {
		return "QuicStreamLimitException[direction=" + direction +
			", maxPendingStreamOpens=" + maxPendingStreamOpens + ']';
	}
}
