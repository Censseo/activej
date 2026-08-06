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
import io.activej.bytebuf.ByteBufPool;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T121, spec FR-085: the per-exchange inbound queue is bounded, and at the bound the <b>oldest</b>
 * HTTP/3 datagram is dropped and counted — never the newest, never unbounded growth, and never a
 * connection error.
 * <p>
 * Driven directly over a stub {@link Http3DatagramTransport}: a drop-oldest assertion needs no wire, and
 * a wire would only make the reordering it models harder to stage than the thing being asserted.
 */
public final class Http3DatagramQueueTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private static final int MAX_QUEUED = Http3Settings.DEFAULT_MAX_INBOUND_DATAGRAMS_PER_STREAM;

	private final StubTransport transport = new StubTransport();

	@Test
	public void atTheBoundTheOldestIsDroppedAndCounted() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		int fed = MAX_QUEUED + 3;
		for (int i = 0; i < fed; i++) {
			channel.onDatagram(payload(i));
		}

		assertEquals(MAX_QUEUED, channel.queuedCount());
		assertEquals(3, channel.datagramsDropped());
		assertEquals(fed, channel.datagramsReceived());

		// The three that went are 0, 1 and 2 — the oldest, not the newest.
		for (int i = 3; i < fed; i++) {
			assertEquals(i, take(channel));
		}
		assertEquals(0, channel.queuedCount());
		assertNull(channel.poll());
	}

	@Test
	public void aDepthOfZeroAcceptsNoneAndCountsEveryOne() {
		Http3DatagramChannel channel = channel(0);
		for (int i = 0; i < 5; i++) {
			channel.onDatagram(payload(i));
		}

		assertEquals(0, channel.queuedCount());
		assertEquals(5, channel.datagramsDropped());
		assertEquals(5, channel.datagramsReceived());
		assertNull(channel.poll());
	}

	@Test
	public void aFullQueueIsNotAnError() {
		Http3DatagramChannel channel = channel(2);
		for (int i = 0; i < 100; i++) {
			channel.onDatagram(payload(i));
		}

		// Nothing about a full queue reaches the transport: RFC 9297 gives no way to tell a peer to slow
		// down on an unreliable channel, and a connection error over one is exactly what FR-085 forbids.
		assertEquals(0, transport.sends);
		assertEquals(2, channel.queuedCount());
		assertEquals(98, channel.datagramsDropped());
		assertEquals(98, take(channel));
		assertEquals(99, take(channel));
	}

	@Test
	public void aReceiveHandlerTakesDeliveryInsteadOfTheQueue() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		List<Integer> seen = new ArrayList<>();
		channel.setReceiveHandler(buf -> {
			seen.add((int) buf.get());
			buf.recycle();
		});

		channel.onDatagram(payload(7));
		channel.onDatagram(payload(8));

		assertEquals(List.of(7, 8), seen);
		assertEquals(0, channel.queuedCount());
		assertEquals(0, channel.datagramsDropped());
	}

	@Test
	public void settingAHandlerDrainsWhatIsAlreadyQueued() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		channel.onDatagram(payload(1));
		channel.onDatagram(payload(2));
		assertEquals(2, channel.queuedCount());

		List<Integer> seen = new ArrayList<>();
		channel.setReceiveHandler(buf -> {
			seen.add((int) buf.get());
			buf.recycle();
		});

		assertEquals(List.of(1, 2), seen);
		assertEquals(0, channel.queuedCount());
	}

	@Test
	public void clearingTheHandlerReturnsToQueueing() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		channel.setReceiveHandler(ByteBuf::recycle);
		channel.onDatagram(payload(1));
		channel.setReceiveHandler(null);
		channel.onDatagram(payload(2));

		assertEquals(1, channel.queuedCount());
		assertEquals(2, take(channel));
	}

	@Test
	public void closeDrainsTheQueueAndIsIdempotent() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		for (int i = 0; i < 5; i++) {
			channel.onDatagram(payload(i));
		}
		channel.close();
		assertEquals(0, channel.queuedCount());
		assertNull(channel.poll());

		channel.close();
		assertEquals(0, channel.queuedCount());
	}

	@Test
	public void aClosedChannelTakesNoFurtherDatagram() {
		Http3DatagramChannel channel = channel(MAX_QUEUED);
		channel.close();
		channel.onDatagram(payload(1));

		assertEquals(0, channel.queuedCount());
		assertEquals(1, channel.datagramsDropped());
		assertTrue(channel.datagramsReceived() >= 0);
	}

	// ---------------------------------------------------------------- helpers

	private Http3DatagramChannel channel(int maxQueued) {
		return new Http3DatagramChannel(Reactor.getCurrentReactor(), transport, maxQueued);
	}

	/** The marker byte of the oldest queued datagram; the buffer is the caller's, so it is recycled here. */
	private static int take(Http3DatagramChannel channel) {
		ByteBuf buf = channel.poll();
		if (buf == null) throw new AssertionError("the queue was empty");
		int marker = buf.get();
		buf.recycle();
		return marker;
	}

	private static ByteBuf payload(int marker) {
		ByteBuf buf = ByteBufPool.allocate(1);
		buf.put((byte) marker);
		return buf;
	}

	/** Available and generous, so nothing in this class is refused for a reason it is not testing. */
	private static final class StubTransport implements Http3DatagramTransport {
		private int sends;

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public long maxPayloadSize() {
			return 1200;
		}

		@Override
		public void send(ByteBuf payload) {
			sends++;
			payload.recycle();
		}
	}
}
