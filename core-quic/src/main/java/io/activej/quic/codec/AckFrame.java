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

import java.util.Arrays;

/**
 * ACK frame (RFC 9000 §19.3), with or without ECN counts. {@link #gaps} and {@link #rangeLengths}
 * are parallel arrays describing the ACK ranges that follow {@link #firstAckRange}.
 */
public final class AckFrame extends QuicFrame {
	public static final int TYPE_WITHOUT_ECN = 0x02;
	public static final int TYPE_WITH_ECN = 0x03;

	public final long largestAcked;
	public final long ackDelay;
	public final long firstAckRange;
	public final long[] gaps;
	public final long[] rangeLengths;
	public final boolean hasEcnCounts;
	public final long ect0Count;
	public final long ect1Count;
	public final long ecnCeCount;

	public AckFrame(
		long largestAcked, long ackDelay, long firstAckRange, long[] gaps, long[] rangeLengths,
		boolean hasEcnCounts, long ect0Count, long ect1Count, long ecnCeCount
	) {
		if (gaps.length != rangeLengths.length) {
			throw new IllegalArgumentException("gaps and rangeLengths must have the same length");
		}
		this.largestAcked = largestAcked;
		this.ackDelay = ackDelay;
		this.firstAckRange = firstAckRange;
		this.gaps = gaps.clone();
		this.rangeLengths = rangeLengths.clone();
		this.hasEcnCounts = hasEcnCounts;
		this.ect0Count = ect0Count;
		this.ect1Count = ect1Count;
		this.ecnCeCount = ecnCeCount;
	}

	public static AckFrame withoutEcn(long largestAcked, long ackDelay, long firstAckRange, long[] gaps, long[] rangeLengths) {
		return new AckFrame(largestAcked, ackDelay, firstAckRange, gaps, rangeLengths, false, 0, 0, 0);
	}

	public static AckFrame withEcn(
		long largestAcked, long ackDelay, long firstAckRange, long[] gaps, long[] rangeLengths,
		long ect0Count, long ect1Count, long ecnCeCount
	) {
		return new AckFrame(largestAcked, ackDelay, firstAckRange, gaps, rangeLengths, true, ect0Count, ect1Count, ecnCeCount);
	}

	public int rangeCount() {
		return gaps.length;
	}

	/** Defensive copy of {@link #gaps}. */
	public long[] gaps() {
		return gaps.clone();
	}

	/** Defensive copy of {@link #rangeLengths}. */
	public long[] rangeLengths() {
		return rangeLengths.clone();
	}

	@Override
	public int encodedLength() {
		int type = hasEcnCounts ? TYPE_WITH_ECN : TYPE_WITHOUT_ECN;
		int length = QuicVarInts.encodedLength(type)
			+ QuicVarInts.encodedLength(largestAcked)
			+ QuicVarInts.encodedLength(ackDelay)
			+ QuicVarInts.encodedLength(gaps.length)
			+ QuicVarInts.encodedLength(firstAckRange);
		for (int i = 0; i < gaps.length; i++) {
			length += QuicVarInts.encodedLength(gaps[i]) + QuicVarInts.encodedLength(rangeLengths[i]);
		}
		if (hasEcnCounts) {
			length += QuicVarInts.encodedLength(ect0Count)
				+ QuicVarInts.encodedLength(ect1Count)
				+ QuicVarInts.encodedLength(ecnCeCount);
		}
		return length;
	}

	@Override
	public void writeTo(ByteBuf buf) {
		QuicVarInts.write(buf, hasEcnCounts ? TYPE_WITH_ECN : TYPE_WITHOUT_ECN);
		QuicVarInts.write(buf, largestAcked);
		QuicVarInts.write(buf, ackDelay);
		QuicVarInts.write(buf, gaps.length);
		QuicVarInts.write(buf, firstAckRange);
		for (int i = 0; i < gaps.length; i++) {
			QuicVarInts.write(buf, gaps[i]);
			QuicVarInts.write(buf, rangeLengths[i]);
		}
		if (hasEcnCounts) {
			QuicVarInts.write(buf, ect0Count);
			QuicVarInts.write(buf, ect1Count);
			QuicVarInts.write(buf, ecnCeCount);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof AckFrame other)) return false;
		return largestAcked == other.largestAcked && ackDelay == other.ackDelay
			&& firstAckRange == other.firstAckRange && Arrays.equals(gaps, other.gaps)
			&& Arrays.equals(rangeLengths, other.rangeLengths) && hasEcnCounts == other.hasEcnCounts
			&& ect0Count == other.ect0Count && ect1Count == other.ect1Count && ecnCeCount == other.ecnCeCount;
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(largestAcked);
		result = 31 * result + Long.hashCode(ackDelay);
		result = 31 * result + Long.hashCode(firstAckRange);
		result = 31 * result + Arrays.hashCode(gaps);
		result = 31 * result + Arrays.hashCode(rangeLengths);
		result = 31 * result + Boolean.hashCode(hasEcnCounts);
		result = 31 * result + Long.hashCode(ect0Count);
		result = 31 * result + Long.hashCode(ect1Count);
		result = 31 * result + Long.hashCode(ecnCeCount);
		return result;
	}
}
