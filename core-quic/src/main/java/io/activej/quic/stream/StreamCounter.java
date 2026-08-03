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
 * The stream ordinals of <b>one</b> stream type — RFC 9000 §2.1 (stream types and identifiers) and
 * §4.6 (controlling concurrency). A {@code QuicStreamManager} holds four: local-bidirectional,
 * local-unidirectional, peer-bidirectional and peer-unidirectional. The four are entirely
 * independent: stream 0 (client bidi #0) and stream 2 (client uni #0) are unrelated, and each type
 * has its own limit, its own ordinals and its own credit.
 * <p>
 * A stream identifier is {@code (ordinal << 2) | initiator | directionality}; this class counts the
 * <em>ordinals</em> only, so the same code serves all four types.
 *
 * <h2>Two roles, one counter</h2>
 * <ul>
 *   <li><b>Locally-initiated</b> types use {@link #allocate()}: ordinals are handed out from
 *       {@code nextOrdinal} in ascending order, without gaps and without reuse (FR-002). Releasing a
 *       stream frees concurrency, never an identifier. {@code limit} is what the peer granted us
 *       ({@code initial_max_streams_*}, raised by {@code MAX_STREAMS}); at the limit
 *       {@link #canOpen()} is {@code false} and the manager withholds the open (FR-029).</li>
 *   <li><b>Peer-initiated</b> types use {@link #open(long)}: an arriving frame naming an ordinal
 *       opens it <em>and every lower-numbered ordinal of the same type that was not already open</em>
 *       (RFC 9000 §2.1, FR-003). {@code limit} is what we advertised, and {@code closed} drives the
 *       {@code MAX_STREAMS} grant (FR-028).</li>
 * </ul>
 *
 * <h2>Implicit opening — the shape {@link #open(long)} exposes</h2>
 * {@code open} returns the <b>lowest ordinal this arrival opens</b>, so the caller instantiates the
 * inclusive range {@code [result, ordinal]}:
 * <pre>{@code
 * if (!counter.isWithinLimit(ordinal)) throw new QuicTransportException(STREAM_LIMIT_ERROR, ...);
 * for (long o = counter.open(ordinal); o <= ordinal; o++) {
 *     openStream(streamId(o, type));      // every implicitly opened stream, in ascending order
 * }
 * }</pre>
 * An arrival for an already-open stream returns a value greater than {@code ordinal}, so the loop
 * body does not run — no special case at the call site. {@link #newlyOpenedBy(long)} answers the same
 * question as a count without mutating, for the manager's bookkeeping and for tests.
 *
 * <h2>Limits are reported, never thrown</h2>
 * {@link #isWithinLimit(long)} answers whether an ordinal is admissible; turning {@code false} into
 * {@code STREAM_LIMIT_ERROR} (RFC 9000 §4.6) is the manager's decision, in the same way the codec
 * leaves protocol-semantic ranges to the layer that owns them. Because implicit opening counts every
 * lower ordinal against the limit, checking the arriving ordinal alone is sufficient: a peer cannot
 * reach a forbidden ordinal by skipping to it.
 * <p>
 * <b>Pure</b>: no {@link io.activej.reactor.Reactor}, no {@code Promise}, no
 * {@link io.activej.bytebuf.ByteBuf}. Not thread-safe — the owning manager provides reactor
 * confinement. Its bounds are peer-driven, so the arithmetic is unconditional and never gated behind
 * {@link io.activej.common.Checks} (SI-1, WI-10).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.11">RFC 9000 §19.11 — MAX_STREAMS Frames</a>
 */
public final class StreamCounter {
	/**
	 * RFC 9000 §4.6 / §19.11: no stream count may exceed 2^60, since an ordinal occupies the 60 bits
	 * above the two type bits of a 62-bit stream identifier. The highest usable ordinal is therefore
	 * {@code MAX_STREAM_COUNT - 1}.
	 */
	public static final long MAX_STREAM_COUNT = 1L << 60;

	/**
	 * {@code WINDOW_UPDATE_FRACTION = 1 / WINDOW_UPDATE_FRACTION_DIVISOR} — one half. Stream credit is
	 * granted on the same ½-consumed rule as data credit (FR-028 referring to FR-025), and by the same
	 * reasoning it is a documented constant rather than an {@link io.activej.common.ApplicationSettings}
	 * key (clarification Q5).
	 */
	public static final int WINDOW_UPDATE_FRACTION_DIVISOR = 2;

	private long limit;
	private long opened;
	private long closed;
	private long nextOrdinal;

	private StreamCounter(long limit) {
		this.limit = limit;
	}

	/**
	 * @param initialLimit how many streams of this type the initiator may open — the peer's
	 *                     {@code initial_max_streams_*} for a locally-initiated type, ours for a
	 *                     peer-initiated one
	 */
	public static StreamCounter create(long initialLimit) {
		checkNonNegative(initialLimit, "initialLimit");
		return new StreamCounter(initialLimit);
	}

	/**
	 * Whether {@code count} is a valid RFC 9000 §4.6 / §18.2 / §19.11 stream count — one a
	 * {@code MAX_STREAMS} frame may carry, or an {@code initial_max_streams_bidi} /
	 * {@code initial_max_streams_uni} transport parameter may declare.
	 */
	public static boolean isValidStreamCount(long count) {
		return count >= 0 && count <= MAX_STREAM_COUNT;
	}

	// region allocation — locally-initiated types, RFC 9000 §2.1, FR-002

	/** Whether another locally-initiated stream of this type may be opened under the current limit. */
	public boolean canOpen() {
		return opened < limit;
	}

	/**
	 * Allocates the next ordinal of this type: ascending, gapless, never reused (FR-002).
	 *
	 * @throws IllegalStateException if the limit is reached — a caller bug, not a wire error. The
	 *                               manager checks {@link #canOpen()} and withholds the open instead
	 *                               (FR-029).
	 */
	public long allocate() {
		if (!canOpen()) {
			throw new IllegalStateException("Stream limit reached: " + opened + " of " + limit + " opened");
		}
		long ordinal = nextOrdinal++;
		opened = nextOrdinal;
		return ordinal;
	}

	/** The next ordinal {@link #allocate()} will return. Strictly increasing; never rewound. */
	public long nextOrdinal() {
		return nextOrdinal;
	}

	// endregion

	// region arrival — peer-initiated types, RFC 9000 §2.1, FR-003

	/**
	 * Whether an arriving ordinal is admissible under the current limit. {@code false} is
	 * {@code STREAM_LIMIT_ERROR} (RFC 9000 §4.6) — a decision this class leaves to the manager.
	 * <p>
	 * Sufficient on its own: since every lower ordinal is implicitly opened and counted, an ordinal
	 * below the limit implies that all the streams it opens are below the limit too.
	 */
	public boolean isWithinLimit(long ordinal) {
		checkNonNegative(ordinal, "ordinal");
		return ordinal < limit;
	}

	/** Whether this ordinal has already been opened, explicitly or implicitly. */
	public boolean isOpen(long ordinal) {
		checkNonNegative(ordinal, "ordinal");
		return ordinal < opened;
	}

	/**
	 * How many streams an arrival for {@code ordinal} would open, counting the implicitly opened ones
	 * (RFC 9000 §2.1). {@code 0} if the stream is already open. Does not mutate.
	 */
	public long newlyOpenedBy(long ordinal) {
		checkNonNegative(ordinal, "ordinal");
		if (ordinal < opened) return 0;
		long span = ordinal - opened;
		return span == Long.MAX_VALUE ? Long.MAX_VALUE : span + 1;
	}

	/**
	 * Records the arrival of a frame naming {@code ordinal}, implicitly opening every lower-numbered
	 * ordinal of this type that was not already open (RFC 9000 §2.1, FR-003).
	 * <p>
	 * Callers check {@link #isWithinLimit(long)} first.
	 *
	 * @return the <b>lowest ordinal this arrival opens</b>; the caller instantiates every stream in
	 * the inclusive range {@code [result, ordinal]}. When the stream was already open the result is
	 * greater than {@code ordinal}, making the range empty.
	 */
	public long open(long ordinal) {
		checkNonNegative(ordinal, "ordinal");
		long firstNewlyOpened = opened;
		if (ordinal >= opened) {
			opened = ordinal + 1;
		}
		return firstNewlyOpened;
	}

	// endregion

	// region release and credit — FR-028

	/**
	 * Records that one stream of this type has been fully released (FR-006). Drives the
	 * {@code MAX_STREAMS} grant for peer-initiated types; harmless bookkeeping for local ones.
	 *
	 * @throws IllegalStateException if more streams are released than were opened — a caller bug that
	 *                               would otherwise silently grant the peer credit it never used
	 */
	public void onStreamReleased() {
		if (closed >= opened) {
			throw new IllegalStateException("Released more streams than were opened: " + closed + " of " + opened);
		}
		closed++;
	}

	/** How many streams of this type the initiator may open in total. Never decreases (FR-026). */
	public long limit() {
		return limit;
	}

	/** The highest ordinal opened, plus one — equivalently, how many streams of this type have been opened. */
	public long opened() {
		return opened;
	}

	/** How many streams of this type have been released. */
	public long closed() {
		return closed;
	}

	/** How many more streams of this type may be opened under the current limit, floored at 0. */
	public long available() {
		return Math.max(0, limit - opened);
	}

	/**
	 * Applies a {@code MAX_STREAMS} frame (RFC 9000 §19.11). A value at or below the current limit is
	 * ignored without error (FR-026, RFC 9000 §4.6) — frames may be reordered, and a stale limit is a
	 * normal event. Validating the 2^60 ceiling is the caller's ({@link #isValidStreamCount(long)}).
	 *
	 * @return whether the limit was actually raised (only then is a withheld open worth resuming)
	 */
	public boolean onMaxStreams(long maximumStreams) {
		if (maximumStreams <= limit) return false;
		limit = maximumStreams;
		return true;
	}

	/**
	 * Whether at least half of the advertised stream window has been used up by released streams and a
	 * {@code MAX_STREAMS} frame is therefore due (FR-028, by the FR-025 threshold rule).
	 *
	 * @param maxStreams the configured {@code initial_max_streams_*} — the window size to restore
	 */
	public boolean shouldGrantCredit(long maxStreams) {
		checkNonNegative(maxStreams, "maxStreams");
		return limit - closed <= maxStreams / WINDOW_UPDATE_FRACTION_DIVISOR;
	}

	/**
	 * Raises the limit to {@code closed + maxStreams} (FR-028) and returns it, for a
	 * {@code MAX_STREAMS} frame. Never lowers the limit and never exceeds {@link #MAX_STREAM_COUNT},
	 * so the returned value is always encodable; calling it twice without an intervening release
	 * returns the same value, which is what RFC 9000 §13.3 requires of a retransmitted limit.
	 *
	 * @return the new limit
	 */
	public long grantCredit(long maxStreams) {
		checkNonNegative(maxStreams, "maxStreams");
		long granted = maxStreams > MAX_STREAM_COUNT - closed ? MAX_STREAM_COUNT : closed + maxStreams;
		limit = Math.max(limit, granted);
		return limit;
	}

	// endregion

	private static void checkNonNegative(long value, String name) {
		if (value < 0) throw new IllegalArgumentException(name + " must not be negative, got " + value);
	}

	@Override
	public String toString() {
		return "StreamCounter{" +
			"opened=" + opened + '/' + limit +
			", closed=" + closed +
			", nextOrdinal=" + nextOrdinal +
			'}';
	}
}
