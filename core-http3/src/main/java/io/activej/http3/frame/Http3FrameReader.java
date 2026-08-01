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
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.quic.codec.QuicVarInts;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

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
 * <b>Bound</b>: every declared Length is checked against {@code maxFrameSize} — supplied by the
 * caller, since a control stream and a request stream are bounded differently
 * ({@link io.activej.http3.Http3Settings#maxControlFrameSize()} vs. a request-stream bound the
 * reactive layer chooses) — <i>before</i> anything proportional to it is allocated (SI-4).
 * <p>
 * This reader fully buffers a frame's payload before emitting it. That is adequate for control
 * frames (SETTINGS, GOAWAY, …) and for the sizes exercised by this phase's tests; a future
 * reactive layer serving large DATA bodies may need an incremental variant instead of reusing
 * this class outright — buffering a whole multi-megabyte DATA frame defeats the bounded-peak-
 * memory goal of streaming (Phase 9/US6). That refinement is out of this phase's scope.
 */
public final class Http3FrameReader {
	private static final Set<Long> RESERVED_TYPES = Set.of(0x02L, 0x06L, 0x08L, 0x09L);

	private enum State {AWAIT_TYPE, AWAIT_LENGTH, AWAIT_PAYLOAD}

	private final long maxFrameSize;

	private State state = State.AWAIT_TYPE;
	private final VarIntAccumulator typeAcc = new VarIntAccumulator();
	private final VarIntAccumulator lengthAcc = new VarIntAccumulator();

	private long type;
	private long length;
	private boolean discarding;
	private @Nullable ByteBuf payload;
	private long payloadWritten;

	public Http3FrameReader(long maxFrameSize) {
		this.maxFrameSize = maxFrameSize;
	}

	/**
	 * Feeds {@code in}'s currently-readable bytes into the decoder, consuming as many as belong
	 * to the frame currently in progress. Returns the completed frame, or {@code null} if more
	 * bytes are needed — in which case every byte {@code in} offered has been consumed.
	 *
	 * @throws Http3Exception on a reserved frame type, an over-bound declared length, or a
	 *                         frame-specific structural violation (SETTINGS pairing, a truncated
	 *                         single-varint payload)
	 */
	public @Nullable Http3Frame feed(ByteBuf in) throws Http3Exception {
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
					if (length > maxFrameSize) {
						throw new Http3Exception(Http3Errors.H3_FRAME_ERROR,
							"Declared frame length " + length + " exceeds the " + maxFrameSize + "-byte bound");
					}
					discarding = isUnknownType(type);
					payload = discarding ? null : (length == 0 ? ByteBuf.empty() : ByteBufPool.allocate((int) length));
					payloadWritten = 0;
					state = State.AWAIT_PAYLOAD;
				}
				case AWAIT_PAYLOAD -> {
					long remaining = length - payloadWritten;
					if (remaining > 0) {
						int n = (int) Math.min(remaining, in.readRemaining());
						if (n == 0) return null;
						if (!discarding) {
							payload.put(in.array(), in.head(), n);
						}
						in.moveHead(n);
						payloadWritten += n;
					}
					if (payloadWritten < length) return null;

					state = State.AWAIT_TYPE;
					if (discarding) {
						return new UnknownFrame(type, length);
					}
					ByteBuf donePayload = payload;
					payload = null;
					return buildFrame(type, donePayload);
				}
			}
		}
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
