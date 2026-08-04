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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.recycle.Recyclable;
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static io.activej.common.Utils.nullify;

/**
 * A resumable, synchronous decoder of the RFC 9114 §7.2 {@code Type · Length · Payload} frame
 * layout, fed arbitrarily-fragmented chunks of a QUIC stream (FR-027, SC-011).
 * <p>
 * <b>Ownership</b>: {@link #feed(ByteBuf)} consumes readable bytes from {@code in} and leaves the
 * buffer itself to the caller — it is never recycled here. A frame this method returns holds a
 * retained slice the caller must recycle; the payload of an unknown, non-reserved frame type is
 * discarded byte-by-byte as it arrives and is never buffered at all (RFC 9114 §9 GREASE
 * tolerance).
 * <p>
 * <b>DATA arrives in instalments.</b> Every frame type but DATA is delivered as exactly one
 * {@link Http3Frame} once its whole declared length has arrived — they are all small, bounded by
 * {@link io.activej.http3.Http3Settings#maxFieldSectionSize()} or
 * {@link io.activej.http3.Http3Settings#maxControlFrameSize()}, and HEADERS in particular has to be
 * whole before QPACK can decode it. A DATA frame is instead handed over in {@link #DATA_CHUNK_SIZE}
 * pieces as its payload actually arrives, so <b>one wire DATA frame may produce several
 * {@link DataFrame} instances</b>, each owning its own buffer and each a further stretch of the same
 * body. Nothing distinguishes the last instalment from a whole small frame, and nothing needs to:
 * DATA carries no structure across a frame boundary, RFC 9114 §4.1's sequence admits DATA after DATA,
 * and {@code Content-Length} is reconciled against the body total rather than against any one frame.
 * <p>
 * Between two {@code feed} calls a reader part-way through a frame <b>holds the payload buffer it
 * has begun filling</b>, and that buffer is the reader's to release: a caller that stops reading —
 * an aborted stream, a closed connection, a control stream the peer cut mid-frame — must
 * {@link #recycle()} it, or the partial payload leaks (DI-1).
 * <p>
 * <b>Bounds</b>: every declared Length is checked <i>before</i> anything proportional to it is
 * allocated (SI-4), against the bound belonging to the frame type just parsed — not against one
 * bound covering them all. The two differ by orders of magnitude on a request stream
 * ({@link io.activej.http3.Http3Settings#maxFieldSectionSize()} is 64 KiB by default,
 * {@link io.activej.http3.Http3Settings#maxBodySize()} 100 MB), and a single bound would have to be
 * the wider of them — which is the amplification a HEADERS frame is buffered whole into. A control
 * stream, where every legal type is a varint or a pair of them, passes
 * {@link io.activej.http3.Http3Settings#maxControlFrameSize()} as both.
 * <p>
 * A bound alone is not enough for DATA, which is why the instalments above exist: its bound is a
 * whole body's worth, and a peer that declares a length it then never sends would otherwise hold
 * that much pooled memory per stream, for under a kilobyte of wire input, on every stream it is
 * allowed to open at once. What a reader holds while a DATA frame is in progress is
 * {@link #DATA_CHUNK_SIZE} instead, whatever the declared length says (SI-3).
 */
public final class Http3FrameReader implements Recyclable {
	private static final Set<Long> RESERVED_TYPES = Set.of(0x02L, 0x06L, 0x08L, 0x09L);

	/**
	 * The largest stretch of a DATA frame's payload this reader holds before handing it on, and
	 * therefore the whole of what one reader inside a DATA frame costs, however long the frame
	 * declared itself to be.
	 * <p>
	 * A fixed internal constant rather than an {@link io.activej.common.ApplicationSettings} value on
	 * purpose: it is not a bound on anything a peer or a caller can observe — every caller-facing
	 * limit ({@code maxBodySize}, {@code maxFieldSectionSize}, {@code maxControlFrameSize}) is
	 * already a setting and is enforced elsewhere — but the granularity at which this reader hands
	 * bodies on, which no deployment has a reason to retune. 16 KiB is one QUIC stream window's worth
	 * at this module's defaults and an exact {@link ByteBufPool} slab, so a chunk is allocated
	 * without rounding waste.
	 */
	static final int DATA_CHUNK_SIZE = 16 * 1024;

	private enum State {AWAIT_TYPE, AWAIT_LENGTH, AWAIT_PAYLOAD}

	private final long maxHeadersFrameSize;
	private final long maxDataFrameSize;
	private final long oversizeErrorCode;

	private State state = State.AWAIT_TYPE;
	private final VarIntAccumulator typeAcc = new VarIntAccumulator();
	private final VarIntAccumulator lengthAcc = new VarIntAccumulator();

	private long type;
	private long length;
	private boolean discarding;
	private boolean chunked;
	private @Nullable ByteBuf payload;
	private long payloadWritten;
	private boolean recycled;

	/**
	 * One bound for every frame type, reporting an over-bound declared length as
	 * {@link Http3Errors#H3_FRAME_ERROR}.
	 */
	public Http3FrameReader(long maxFrameSize) {
		this(maxFrameSize, maxFrameSize, Http3Errors.H3_FRAME_ERROR);
	}

	/**
	 * As {@link #Http3FrameReader(long)} — <b>one</b> bound for every frame type — but with the caller's
	 * own code for an over-bound declared length, which is a property of the <i>stream</i> the reader is
	 * decoding rather than of the layout. A control stream names {@link Http3Errors#H3_EXCESSIVE_LOAD},
	 * per the wire contract's SETTINGS table; a request stream keeps the default.
	 * <p>
	 * This is the shape a <b>control</b> stream wants: SETTINGS, GOAWAY and MAX_PUSH_ID are the only
	 * types legal on one, all three are varints or pairs of them, and
	 * {@link io.activej.http3.Http3Settings#maxControlFrameSize()} covers all three alike. A request
	 * stream carries two types whose legitimate sizes differ by orders of magnitude and wants
	 * {@link #Http3FrameReader(long, long, long)}.
	 */
	public Http3FrameReader(long maxFrameSize, long oversizeErrorCode) {
		this(maxFrameSize, maxFrameSize, oversizeErrorCode);
	}

	/**
	 * The two bounds a <b>request</b> stream is read with: {@code maxHeadersFrameSize} — the caller's
	 * {@link io.activej.http3.Http3Settings#maxFieldSectionSize()} — for a HEADERS frame, and
	 * {@code maxDataFrameSize} — its {@link io.activej.http3.Http3Settings#maxBodySize()} — for a DATA
	 * one, each applied to the declared Length before a byte of that payload is allocated.
	 * <p>
	 * Keeping them apart is what makes the HEADERS bound the tight one. A HEADERS frame is buffered
	 * <b>whole</b>, because QPACK cannot decode a field section in pieces, so it is allocated at exactly
	 * the length it declares — under a single {@code max(…)} bound that length could be a whole body's
	 * worth, on every stream a peer is allowed to open at once, for a frame header it never follows
	 * through on. The declared-length check is the only thing between that allocation and the peer's
	 * word, so it is the field-section bound that is checked, not the wider of the two.
	 * <p>
	 * Every remaining type is held to whichever bound is smaller: a CANCEL_PUSH, SETTINGS, GOAWAY or
	 * MAX_PUSH_ID frame is buffered whole too — none is legal on a request stream, but each is read
	 * before the frame sequence can say so — and all of them are a varint or a pair of them, so the
	 * tighter bound is still generous by a wide margin. An <b>unknown</b> type is the one exception:
	 * its payload is discarded byte by byte and never buffered at all, so nothing about it is
	 * proportional to what it declared, and it keeps the wider bound so RFC 9114 §9's GREASE tolerance
	 * stays as wide as it can be.
	 */
	public Http3FrameReader(long maxHeadersFrameSize, long maxDataFrameSize, long oversizeErrorCode) {
		this.maxHeadersFrameSize = maxHeadersFrameSize;
		this.maxDataFrameSize = maxDataFrameSize;
		this.oversizeErrorCode = oversizeErrorCode;
	}

	/**
	 * Feeds {@code in}'s currently-readable bytes into the decoder, consuming as many as belong
	 * to the frame currently in progress. Returns the completed frame — or, for a DATA frame longer
	 * than {@link #DATA_CHUNK_SIZE}, the next instalment of it — or {@code null} if more bytes are
	 * needed, in which case every byte {@code in} offered has been consumed.
	 * <p>
	 * A caller with several frames' worth of input in hand calls this in a loop until it returns
	 * {@code null}; {@link #isMidFrame()} then says whether the reader stopped between frames or
	 * inside one.
	 *
	 * @throws Http3Exception on a reserved frame type, an over-bound declared length, or a
	 *                         frame-specific structural violation (SETTINGS pairing, a truncated
	 *                         single-varint payload)
	 */
	public @Nullable Http3Frame feed(ByteBuf in) throws Http3Exception {
		if (recycled) {
			// A caller bug, deliberately distinct from every wire error: the partial payload this reader
			// held is gone, and resuming from where it stopped is no longer possible.
			throw new IllegalStateException("This Http3FrameReader has been recycled");
		}
		while (true) {
			switch (state) {
				case AWAIT_TYPE -> {
					if (!typeAcc.feed(in)) return null;
					type = typeAcc.value();
					typeAcc.reset();
					if (RESERVED_TYPES.contains(type)) {
						throw new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
							"Reserved HTTP/2 frame type: 0x" + Long.toHexString(type));
					}
					state = State.AWAIT_LENGTH;
				}
				case AWAIT_LENGTH -> {
					if (!lengthAcc.feed(in)) return null;
					length = lengthAcc.value();
					lengthAcc.reset();
					long bound = boundFor(type);
					if (length > bound) {
						throw new Http3Exception(oversizeErrorCode,
							"Declared frame length " + length + " exceeds the " + bound + "-byte bound");
					}
					discarding = isUnknownType(type);
					chunked = type == DataFrame.TYPE;
					// Nothing proportional to the declared length is allocated here: a payload buffer is
					// taken only once payload bytes are actually in hand, and for DATA it is a chunk of
					// them rather than all of them.
					payload = discarding || length != 0 ? null : ByteBuf.empty();
					payloadWritten = 0;
					state = State.AWAIT_PAYLOAD;
				}
				case AWAIT_PAYLOAD -> {
					return readPayload(in);
				}
			}
		}
	}

	/**
	 * Takes as much of the payload in progress as {@code in} carries and there is room for, and returns
	 * the frame that completes, the DATA instalment that fills, or {@code null} if neither yet.
	 */
	private @Nullable Http3Frame readPayload(ByteBuf in) throws Http3Exception {
		long remaining = length - payloadWritten;
		if (remaining > 0) {
			int available = in.readRemaining();
			// Nothing is taken for a payload no byte of which is in hand — an empty feed leaves a reader
			// between two instalments holding nothing at all.
			if (available == 0) return null;
			if (!discarding && payload == null) {
				payload = ByteBufPool.allocate((int) (chunked ? Math.min(remaining, DATA_CHUNK_SIZE) : remaining));
			}
			// A chunk is never left full on return, so there is always room here for a byte.
			int room = discarding ? Integer.MAX_VALUE : payload.writeRemaining();
			int n = (int) Math.min(Math.min(remaining, room), available);
			if (!discarding) {
				payload.put(in.array(), in.head(), n);
			}
			in.moveHead(n);
			payloadWritten += n;
			remaining -= n;
		}
		if (remaining > 0) {
			// A DATA frame longer than one chunk: the stretch that has arrived goes to the caller now, and
			// the reader stays inside the frame for the rest of the length it declared. Every other type is
			// held until it is whole.
			if (!chunked || payload.writeRemaining() != 0) return null;
			ByteBuf chunk = payload;
			payload = null;
			return new DataFrame(chunk);
		}

		state = State.AWAIT_TYPE;
		if (discarding) {
			return new UnknownFrame(type, length);
		}
		ByteBuf donePayload = payload;
		payload = null;
		return buildFrame(type, donePayload);
	}

	/**
	 * Whether this reader stopped <b>inside</b> a frame rather than between two — a partly-read
	 * Type or Length varint, or a payload short of its declared length.
	 * <p>
	 * A caller reads it after {@link #feed} to tell an input that ended cleanly from one that ended
	 * mid-frame (RFC 9114 §7.1). {@code feed} returning {@code null} is not that answer on its own,
	 * and neither is it returning a frame: a DATA frame delivered in instalments returns a frame
	 * from the middle of itself.
	 */
	public boolean isMidFrame() {
		return state != State.AWAIT_TYPE || typeAcc.isStarted();
	}

	/**
	 * Releases the payload of the frame this reader is part-way through — the one buffer it owns
	 * between two {@link #feed} calls — for a caller that has stopped reading.
	 * <p>
	 * Idempotent, and a no-op for a reader that is between frames rather than inside one. The reader is
	 * <b>terminal</b> afterwards: a further {@link #feed} throws {@link IllegalStateException} rather
	 * than silently resuming a frame whose first bytes no longer exist.
	 */
	@Override
	public void recycle() {
		recycled = true;
		payload = nullify(payload, ByteBuf::recycle);
	}

	/**
	 * The bound the declared Length of a {@code type} frame is held to — see
	 * {@link #Http3FrameReader(long, long, long)} for why each type gets the one it does. Read at the
	 * moment the Length varint parses, which is before anything proportional to it exists.
	 */
	private long boundFor(long type) {
		if (type == DataFrame.TYPE) return maxDataFrameSize;
		if (type == HeadersFrame.TYPE) return maxHeadersFrameSize;
		// Nothing is ever allocated for an unknown type, so the wider bound costs nothing and tolerates
		// more; everything else is buffered whole at exactly its declared length, so it takes the tighter.
		return isUnknownType(type) ?
			Math.max(maxHeadersFrameSize, maxDataFrameSize) :
			Math.min(maxHeadersFrameSize, maxDataFrameSize);
	}

	private static boolean isUnknownType(long type) {
		return type != DataFrame.TYPE && type != HeadersFrame.TYPE && type != CancelPushFrame.TYPE
			&& type != SettingsFrame.TYPE && type != GoAwayFrame.TYPE && type != MaxPushIdFrame.TYPE;
	}

	private static Http3Frame buildFrame(long type, ByteBuf payload) throws Http3Exception {
		if (type == DataFrame.TYPE) {
			return new DataFrame(payload);
		}
		if (type == HeadersFrame.TYPE) {
			return new HeadersFrame(payload);
		}
		try {
			if (type == CancelPushFrame.TYPE) {
				return new CancelPushFrame(readSingleVarInt(payload, "CANCEL_PUSH"));
			}
			if (type == SettingsFrame.TYPE) {
				return SettingsFrame.read(payload);
			}
			if (type == GoAwayFrame.TYPE) {
				return new GoAwayFrame(readSingleVarInt(payload, "GOAWAY"));
			}
			if (type == MaxPushIdFrame.TYPE) {
				return new MaxPushIdFrame(readSingleVarInt(payload, "MAX_PUSH_ID"));
			}
			throw new AssertionError("Unreachable: type 0x" + Long.toHexString(type) + " was not classified as unknown");
		} finally {
			payload.recycle();
		}
	}

	private static long readSingleVarInt(ByteBuf payload, String frameName) throws Http3Exception {
		try {
			return QuicVarInts.read(payload);
		} catch (TruncatedDataException e) {
			throw new Http3Exception(Http3Errors.H3_FRAME_ERROR, frameName + " payload does not contain a complete varint");
		}
	}

	/**
	 * Accumulates a QUIC varint (RFC 9000 §16) across arbitrarily-fragmented {@link #feed} calls,
	 * without pool allocation — its own encoded form is at most 8 bytes.
	 */
	private static final class VarIntAccumulator {
		private final byte[] bytes = new byte[8];
		private int len = 0;

		/** @return true once a complete varint has been accumulated */
		boolean feed(ByteBuf in) {
			if (len == 0) {
				if (!in.canRead()) return false;
				bytes[len++] = in.readByte();
			}
			int needed = 1 << ((bytes[0] & 0xFF) >>> 6);
			while (len < needed && in.canRead()) {
				bytes[len++] = in.readByte();
			}
			return len == needed;
		}

		/** Whether some but not all of a varint has been accumulated. */
		boolean isStarted() {
			return len != 0;
		}

		long value() {
			long value = bytes[0] & 0x3F;
			for (int i = 1; i < len; i++) {
				value = (value << 8) | (bytes[i] & 0xFF);
			}
			return value;
		}

		void reset() {
			len = 0;
		}
	}
}
