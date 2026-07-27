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

package io.activej.quic.codec;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;

/**
 * Reads and writes {@link QuicFrame}s (RFC 9000 §19) on a packet-payload {@link ByteBuf}.
 * <p>
 * {@link #read} treats {@code payload} as exactly one packet's decrypted payload: frames that
 * omit an explicit length (a LEN-less STREAM or DATAGRAM frame) consume everything remaining in
 * {@code payload}. Every declared length is checked against the remaining bytes before any
 * allocation (no unbounded allocation from wire-supplied sizes).
 */
public final class QuicFrames {
	private static final long MAX_KNOWN_TYPE = 0x31;

	private QuicFrames() {
	}

	public static void write(ByteBuf out, QuicFrame frame) {
		frame.writeTo(out);
	}

	public static int encodedLength(QuicFrame frame) {
		return frame.encodedLength();
	}

	public static QuicFrame read(ByteBuf payload) throws TruncatedDataException, MalformedDataException {
		long typeLong = QuicVarInts.read(payload);
		if (typeLong > MAX_KNOWN_TYPE) {
			throw new MalformedDataException("Unknown or reserved frame type: 0x" + Long.toHexString(typeLong));
		}
		int type = (int) typeLong;
		switch (type) {
			case PaddingFrame.TYPE:
				return readPadding(payload);
			case PingFrame.TYPE:
				return PingFrame.INSTANCE;
			case AckFrame.TYPE_WITHOUT_ECN:
				return readAck(payload, false);
			case AckFrame.TYPE_WITH_ECN:
				return readAck(payload, true);
			case ResetStreamFrame.TYPE: {
				long streamId = QuicVarInts.read(payload);
				long appErrorCode = QuicVarInts.read(payload);
				long finalSize = QuicVarInts.read(payload);
				return new ResetStreamFrame(streamId, appErrorCode, finalSize);
			}
			case StopSendingFrame.TYPE: {
				long streamId = QuicVarInts.read(payload);
				long appErrorCode = QuicVarInts.read(payload);
				return new StopSendingFrame(streamId, appErrorCode);
			}
			case CryptoFrame.TYPE: {
				long offset = QuicVarInts.read(payload);
				ByteBuf slice = readLengthPrefixedSlice(payload);
				return new CryptoFrame(offset, slice);
			}
			case NewTokenFrame.TYPE:
				return readNewToken(payload);
			case StreamFrame.TYPE_BASE:
			case StreamFrame.TYPE_BASE | StreamFrame.FIN_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.LEN_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.LEN_BIT | StreamFrame.FIN_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.OFF_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.OFF_BIT | StreamFrame.FIN_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.OFF_BIT | StreamFrame.LEN_BIT:
			case StreamFrame.TYPE_BASE | StreamFrame.OFF_BIT | StreamFrame.LEN_BIT | StreamFrame.FIN_BIT:
				return readStream(payload, type);
			case MaxDataFrame.TYPE:
				return new MaxDataFrame(QuicVarInts.read(payload));
			case MaxStreamDataFrame.TYPE: {
				long streamId = QuicVarInts.read(payload);
				long maximum = QuicVarInts.read(payload);
				return new MaxStreamDataFrame(streamId, maximum);
			}
			case MaxStreamsFrame.TYPE_BIDIRECTIONAL:
				return new MaxStreamsFrame(QuicVarInts.read(payload), QuicStreamLimitType.BIDIRECTIONAL);
			case MaxStreamsFrame.TYPE_UNIDIRECTIONAL:
				return new MaxStreamsFrame(QuicVarInts.read(payload), QuicStreamLimitType.UNIDIRECTIONAL);
			case DataBlockedFrame.TYPE:
				return new DataBlockedFrame(QuicVarInts.read(payload));
			case StreamDataBlockedFrame.TYPE: {
				long streamId = QuicVarInts.read(payload);
				long limit = QuicVarInts.read(payload);
				return new StreamDataBlockedFrame(streamId, limit);
			}
			case StreamsBlockedFrame.TYPE_BIDIRECTIONAL:
				return new StreamsBlockedFrame(QuicVarInts.read(payload), QuicStreamLimitType.BIDIRECTIONAL);
			case StreamsBlockedFrame.TYPE_UNIDIRECTIONAL:
				return new StreamsBlockedFrame(QuicVarInts.read(payload), QuicStreamLimitType.UNIDIRECTIONAL);
			case NewConnectionIdFrame.TYPE:
				return readNewConnectionId(payload);
			case RetireConnectionIdFrame.TYPE:
				return new RetireConnectionIdFrame(QuicVarInts.read(payload));
			case PathChallengeFrame.TYPE:
				return new PathChallengeFrame(readFixedBytes(payload, PathChallengeFrame.DATA_LENGTH));
			case PathResponseFrame.TYPE:
				return new PathResponseFrame(readFixedBytes(payload, PathResponseFrame.DATA_LENGTH));
			case ConnectionCloseFrame.TYPE_TRANSPORT: {
				long errorCode = QuicVarInts.read(payload);
				long triggerFrameType = QuicVarInts.read(payload);
				byte[] reason = readReasonPhrase(payload);
				return ConnectionCloseFrame.transport(errorCode, triggerFrameType, reason);
			}
			case ConnectionCloseFrame.TYPE_APPLICATION: {
				long errorCode = QuicVarInts.read(payload);
				byte[] reason = readReasonPhrase(payload);
				return ConnectionCloseFrame.application(errorCode, reason);
			}
			case HandshakeDoneFrame.TYPE:
				return HandshakeDoneFrame.INSTANCE;
			case DatagramFrame.TYPE_WITHOUT_LENGTH:
				return new DatagramFrame(readRestOfBuffer(payload));
			case DatagramFrame.TYPE_WITH_LENGTH:
				return new DatagramFrame(readLengthPrefixedSlice(payload));
			default:
				throw new MalformedDataException("Unknown or reserved frame type: 0x" + Integer.toHexString(type));
		}
	}

	private static QuicFrame readPadding(ByteBuf payload) {
		int count = 1;
		while (payload.canRead() && payload.peek() == 0) {
			payload.moveHead(1);
			count++;
		}
		return new PaddingFrame(count);
	}

	private static QuicFrame readAck(ByteBuf payload, boolean hasEcn) throws TruncatedDataException, MalformedDataException {
		long largestAcked = QuicVarInts.read(payload);
		long ackDelay = QuicVarInts.read(payload);
		long rangeCountLong = QuicVarInts.read(payload);
		if (rangeCountLong > (long) payload.readRemaining() / 2) {
			throw new MalformedDataException(
				"ACK range count " + rangeCountLong + " cannot fit in " + payload.readRemaining() + " remaining bytes");
		}
		int rangeCount = (int) rangeCountLong;
		long firstAckRange = QuicVarInts.read(payload);
		long smallest = largestAcked - firstAckRange;
		if (smallest < 0) {
			throw new MalformedDataException(
				"ACK first range " + firstAckRange + " exceeds largest acknowledged " + largestAcked);
		}
		long[] gaps = new long[rangeCount];
		long[] rangeLengths = new long[rangeCount];
		for (int i = 0; i < rangeCount; i++) {
			long gap = QuicVarInts.read(payload);
			long rangeLength = QuicVarInts.read(payload);
			long afterGap = smallest - gap - 2;
			if (afterGap < 0) {
				throw new MalformedDataException("ACK range gap " + gap + " underflows the packet number space");
			}
			smallest = afterGap - rangeLength;
			if (smallest < 0) {
				throw new MalformedDataException("ACK range length " + rangeLength + " underflows the packet number space");
			}
			gaps[i] = gap;
			rangeLengths[i] = rangeLength;
		}
		if (hasEcn) {
			long ect0 = QuicVarInts.read(payload);
			long ect1 = QuicVarInts.read(payload);
			long ecnCe = QuicVarInts.read(payload);
			return AckFrame.withEcn(largestAcked, ackDelay, firstAckRange, gaps, rangeLengths, ect0, ect1, ecnCe);
		}
		return AckFrame.withoutEcn(largestAcked, ackDelay, firstAckRange, gaps, rangeLengths);
	}

	private static QuicFrame readNewToken(ByteBuf payload) throws TruncatedDataException, MalformedDataException {
		ByteBuf slice = readLengthPrefixedSlice(payload);
		if (slice.readRemaining() == 0) {
			slice.recycle();
			throw new MalformedDataException("NEW_TOKEN token must not be empty");
		}
		return new NewTokenFrame(slice);
	}

	private static QuicFrame readStream(ByteBuf payload, int type) throws TruncatedDataException, MalformedDataException {
		boolean hasOffset = (type & StreamFrame.OFF_BIT) != 0;
		boolean hasLength = (type & StreamFrame.LEN_BIT) != 0;
		boolean fin = (type & StreamFrame.FIN_BIT) != 0;
		long streamId = QuicVarInts.read(payload);
		long offset = hasOffset ? QuicVarInts.read(payload) : 0;
		ByteBuf data = hasLength ? readLengthPrefixedSlice(payload) : readRestOfBuffer(payload);
		return new StreamFrame(streamId, offset, fin, data);
	}

	private static QuicFrame readNewConnectionId(ByteBuf payload) throws TruncatedDataException, MalformedDataException {
		long sequenceNumber = QuicVarInts.read(payload);
		long retirePriorTo = QuicVarInts.read(payload);
		requireRemaining(payload, 1);
		int cidLength = payload.readByte() & 0xFF;
		if (cidLength < 1 || cidLength > QuicConnectionId.MAX_LENGTH) {
			throw new MalformedDataException(
				"NEW_CONNECTION_ID length must be in [1, " + QuicConnectionId.MAX_LENGTH + "]: " + cidLength);
		}
		if (cidLength > payload.readRemaining()) {
			throw new MalformedDataException(
				"NEW_CONNECTION_ID length " + cidLength + " exceeds " + payload.readRemaining() + " remaining bytes");
		}
		byte[] cidBytes = new byte[cidLength];
		payload.read(cidBytes);
		QuicConnectionId connectionId = QuicConnectionId.of(cidBytes);
		byte[] token = readFixedBytes(payload, NewConnectionIdFrame.STATELESS_RESET_TOKEN_LENGTH);
		return new NewConnectionIdFrame(sequenceNumber, retirePriorTo, connectionId, token);
	}

	private static byte[] readReasonPhrase(ByteBuf payload) throws TruncatedDataException, MalformedDataException {
		long length = QuicVarInts.read(payload);
		if (length > payload.readRemaining()) {
			throw new MalformedDataException(
				"CONNECTION_CLOSE reason phrase length " + length + " exceeds " + payload.readRemaining() + " remaining bytes");
		}
		byte[] reason = new byte[(int) length];
		payload.read(reason);
		return reason;
	}

	private static void requireRemaining(ByteBuf buf, int n) throws TruncatedDataException {
		if (buf.readRemaining() < n) {
			throw new TruncatedDataException("Expected " + n + " more byte(s), only " + buf.readRemaining() + " remain");
		}
	}

	private static byte[] readFixedBytes(ByteBuf payload, int n) throws TruncatedDataException {
		requireRemaining(payload, n);
		byte[] bytes = new byte[n];
		payload.read(bytes);
		return bytes;
	}

	private static ByteBuf readLengthPrefixedSlice(ByteBuf payload) throws TruncatedDataException, MalformedDataException {
		long length = QuicVarInts.read(payload);
		if (length > payload.readRemaining()) {
			throw new MalformedDataException(
				"Declared length " + length + " exceeds " + payload.readRemaining() + " remaining bytes");
		}
		int len = (int) length;
		ByteBuf slice = payload.slice(len);
		payload.moveHead(len);
		return slice;
	}

	private static ByteBuf readRestOfBuffer(ByteBuf payload) {
		int len = payload.readRemaining();
		ByteBuf slice = payload.slice(len);
		payload.moveHead(len);
		return slice;
	}
}
