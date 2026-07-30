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

package io.activej.quic.connection;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.CryptoFrame;
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SendQueueTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * Allocates from {@link ByteBufPool} rather than wrapping an array: only pooled buffers are
	 * tracked by {@link ByteBufRule}, so a wrapped buffer would make every leak assertion vacuous.
	 */
	private static CryptoFrame crypto(long offset, String payload) {
		byte[] bytes = payload.getBytes();
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return new CryptoFrame(offset, buf);
	}

	private static List<QuicFrame> drain(SendQueue queue, EncryptionLevel level) {
		List<QuicFrame> out = new ArrayList<>();
		QuicFrame frame;
		while ((frame = queue.poll(level)) != null) {
			out.add(frame);
		}
		return out;
	}

	@Test
	public void fifoWithinALevel() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame a = PingFrame.INSTANCE;
		QuicFrame b = new MaxDataFrame(1000);
		QuicFrame c = PingFrame.INSTANCE;

		queue.enqueue(EncryptionLevel.ONE_RTT, a, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, b, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, c, false);

		List<QuicFrame> polled = drain(queue, EncryptionLevel.ONE_RTT);
		assertEquals(List.of(a, b, c), polled);
		assertTrue(queue.isEmpty());
		assertEquals(0, queue.queuedBytes());
	}

	@Test
	public void levelsAreIndependent() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame initial = new MaxDataFrame(1);
		QuicFrame oneRtt = new MaxDataFrame(2);

		queue.enqueue(EncryptionLevel.INITIAL, initial, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, oneRtt, false);

		assertTrue(queue.hasPending(EncryptionLevel.INITIAL));
		assertTrue(queue.hasPending(EncryptionLevel.ONE_RTT));
		assertFalse(queue.hasPending(EncryptionLevel.HANDSHAKE));

		assertSame(initial, queue.poll(EncryptionLevel.INITIAL));
		assertNull(queue.poll(EncryptionLevel.INITIAL));
		// Draining one level left the other intact.
		assertSame(oneRtt, queue.poll(EncryptionLevel.ONE_RTT));
	}

	@Test
	public void requeuedFramesGoToTheFront() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame a = new MaxDataFrame(1);
		QuicFrame b = new MaxDataFrame(2);
		queue.enqueue(EncryptionLevel.ONE_RTT, a, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, b, false);

		QuicFrame c = new MaxDataFrame(3);
		queue.requeue(EncryptionLevel.ONE_RTT, List.of(c), false);
		assertEquals(List.of(c, a, b), drain(queue, EncryptionLevel.ONE_RTT));
	}

	@Test
	public void requeuedListPreservesItsOwnOrder() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame a = new MaxDataFrame(1);
		QuicFrame b = new MaxDataFrame(2);
		queue.enqueue(EncryptionLevel.ONE_RTT, a, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, b, false);

		QuicFrame d = new MaxDataFrame(3);
		QuicFrame e = new MaxDataFrame(4);
		// A naive addFirst loop would yield E, D, A, B — retransmitting a stream out of order.
		queue.requeue(EncryptionLevel.ONE_RTT, List.of(d, e), false);
		assertEquals(List.of(d, e, a, b), drain(queue, EncryptionLevel.ONE_RTT));
	}

	@Test
	public void byteAccountingTracksEncodedLength() throws Exception {
		SendQueue queue = new SendQueue(4096);
		CryptoFrame frame = crypto(0, "hello world");
		int expected = frame.encodedLength();

		queue.enqueue(EncryptionLevel.HANDSHAKE, frame, false);
		assertEquals(expected, queue.queuedBytes());

		QuicFrame polled = queue.poll(EncryptionLevel.HANDSHAKE);
		assertEquals(0, queue.queuedBytes());
		// Ownership came back to us with the poll.
		Recyclers.recycle(polled);
	}

	@Test
	public void byteAccountingIsGlobalAcrossLevels() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame a = new MaxDataFrame(1);
		QuicFrame b = new MaxDataFrame(2);
		queue.enqueue(EncryptionLevel.INITIAL, a, false);
		long afterFirst = queue.queuedBytes();
		queue.enqueue(EncryptionLevel.ONE_RTT, b, false);
		assertEquals(afterFirst + b.encodedLength(), queue.queuedBytes());

		drain(queue, EncryptionLevel.INITIAL);
		drain(queue, EncryptionLevel.ONE_RTT);
		assertEquals(0, queue.queuedBytes());
	}

	@Test
	public void exceedingMaxSendQueueBytesRaisesInternalError() throws Exception {
		SendQueue queue = new SendQueue(64);
		CryptoFrame accepted = crypto(0, "0123456789");
		queue.enqueue(EncryptionLevel.HANDSHAKE, accepted, false);
		long before = queue.queuedBytes();

		CryptoFrame rejected = crypto(10, "x".repeat(200));
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> queue.enqueue(EncryptionLevel.HANDSHAKE, rejected, false));
		assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());

		// Accounting is unchanged by a failed enqueue, and the queue is still usable.
		assertEquals(before, queue.queuedBytes());
		assertEquals(1, queue.pendingCount(EncryptionLevel.HANDSHAKE));
		queue.enqueue(EncryptionLevel.HANDSHAKE, PingFrame.INSTANCE, false);
		assertEquals(2, queue.pendingCount(EncryptionLevel.HANDSHAKE));

		// The rejected frame's buffer was recycled by the queue, not leaked. ByteBufRule is the
		// enforcement: a leak fails the class. The buffer itself must NOT be inspected here — under
		// the strict Surefire harness (clearOnRecycle=true) touching a recycled ByteBuf throws.
		queue.drop();
	}

	@Test
	public void dropRecyclesEveryRetainedSlice() throws Exception {
		SendQueue queue = new SendQueue(4096);
		queue.enqueue(EncryptionLevel.INITIAL, crypto(0, "initial-crypto"), false);
		queue.enqueue(EncryptionLevel.HANDSHAKE, crypto(0, "handshake-crypto"), false);
		queue.enqueue(EncryptionLevel.HANDSHAKE, crypto(16, "more-handshake"), false);
		// Non-recyclable frames coexist with recyclable ones.
		queue.enqueue(EncryptionLevel.ONE_RTT, PingFrame.INSTANCE, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, new MaxDataFrame(99), false);
		assertTrue(queue.queuedBytes() > 0);

		queue.drop();

		assertEquals(0, queue.queuedBytes());
		assertTrue(queue.isEmpty());
		assertTrue(queue.isDropped());
	}

	@Test
	public void dropIsIdempotent() throws Exception {
		SendQueue queue = new SendQueue(4096);
		queue.enqueue(EncryptionLevel.HANDSHAKE, crypto(0, "abc"), false);
		queue.drop();
		queue.drop();
		assertEquals(0, queue.queuedBytes());
	}

	@Test
	public void enqueueAfterDropRecyclesRatherThanAccumulating() throws Exception {
		SendQueue queue = new SendQueue(4096);
		queue.drop();

		queue.enqueue(EncryptionLevel.ONE_RTT, crypto(0, "discarded"), false);
		assertTrue(queue.isEmpty());
		assertEquals(0, queue.queuedBytes());

		queue.requeue(EncryptionLevel.ONE_RTT, List.of(crypto(0, "also-discarded")), false);
		assertTrue(queue.isEmpty());
	}

	@Test
	public void handlerOwnedFlagIsPreserved() throws Exception {
		SendQueue queue = new SendQueue(4096);
		queue.enqueue(EncryptionLevel.ONE_RTT, new MaxDataFrame(1), true);
		assertTrue(queue.isNextHandlerOwned(EncryptionLevel.ONE_RTT));
		queue.poll(EncryptionLevel.ONE_RTT);

		queue.enqueue(EncryptionLevel.ONE_RTT, new MaxDataFrame(2), false);
		assertFalse(queue.isNextHandlerOwned(EncryptionLevel.ONE_RTT));
		queue.poll(EncryptionLevel.ONE_RTT);

		queue.requeue(EncryptionLevel.ONE_RTT, List.of(new MaxDataFrame(3)), true);
		assertTrue(queue.isNextHandlerOwned(EncryptionLevel.ONE_RTT));
		queue.poll(EncryptionLevel.ONE_RTT);
	}

	@Test
	public void pollRespectsAByteAllowance() throws Exception {
		SendQueue queue = new SendQueue(4096);
		QuicFrame small1 = PingFrame.INSTANCE;      // 1 byte
		QuicFrame small2 = PingFrame.INSTANCE;      // 1 byte
		CryptoFrame big = crypto(0, "x".repeat(100));
		queue.enqueue(EncryptionLevel.ONE_RTT, small1, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, small2, false);
		queue.enqueue(EncryptionLevel.ONE_RTT, big, false);

		List<QuicFrame> taken = new ArrayList<>();
		int used = queue.pollUpTo(EncryptionLevel.ONE_RTT, 5, taken::add);

		assertEquals(2, taken.size());
		assertEquals(2, used);
		// The frame that did not fit stays at the front; the queue did not skip past it.
		assertEquals(1, queue.pendingCount(EncryptionLevel.ONE_RTT));
		assertSame(big, queue.poll(EncryptionLevel.ONE_RTT));
		big.recycle();
	}

	@Test
	public void pollUpToWithZeroAllowanceTakesNothing() throws Exception {
		SendQueue queue = new SendQueue(4096);
		queue.enqueue(EncryptionLevel.ONE_RTT, PingFrame.INSTANCE, false);
		List<QuicFrame> taken = new ArrayList<>();
		assertEquals(0, queue.pollUpTo(EncryptionLevel.ONE_RTT, 0, taken::add));
		assertTrue(taken.isEmpty());
		assertEquals(1, queue.pendingCount(EncryptionLevel.ONE_RTT));
		queue.drop();
	}

	@Test
	public void rejectsNonPositiveBound() {
		assertThrows(IllegalArgumentException.class, () -> new SendQueue(0));
	}
}
