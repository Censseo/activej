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
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * T122, contracts/h3-datagrams.md "Ownership Contract": {@link Http3DatagramChannel#send} takes
 * ownership of its payload on <b>every</b> path — accepted and all four refusals alike — and the inbound
 * queue owns every buffer it holds until it is polled, dropped or drained.
 * <p>
 * Asserted with pooled-buffer counts around each call rather than inferred from {@link ByteBufRule},
 * which would catch a leak but not a double recycle, and would report either only at the end of the
 * class rather than at the case that caused it.
 */
public final class Http3DatagramOwnershipTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	private StubTransport transport;
	private int liveBuffersBefore;

	@Before
	public void setUp() {
		transport = new StubTransport();
		liveBuffersBefore = liveBuffers();
	}

	// ---------------------------------------------------------------- send, all four paths

	@Test
	public void anAcceptedSendHandsThePayloadOn() {
		Http3DatagramChannel channel = channel();
		ByteBuf payload = payload(16);
		try {
			channel.send(payload);
		} catch (Http3DatagramException e) {
			throw new AssertionError(e);
		}

		assertEquals(1, transport.sends);
		assertEquals(1, channel.datagramsSent());
		assertEquals(0, channel.datagramsRefused());
		assertNothingIsStillHeld();
	}

	@Test
	public void aSendRefusedForUnavailabilityStillOwnsThePayload() {
		transport.available = false;
		Http3DatagramChannel channel = channel();

		assertEquals(Http3DatagramException.Reason.NOT_NEGOTIATED, refuse(channel, payload(16)));
		// Refused before the transport is reached at all, so nothing partially-built was left behind.
		assertEquals(0, transport.sends);
		assertEquals(1, channel.datagramsRefused());
		assertEquals(0, channel.datagramsSent());
		assertNothingIsStillHeld();
	}

	@Test
	public void aSendRefusedForOversizeStillOwnsThePayload() {
		transport.maxPayloadSize = 8;
		Http3DatagramChannel channel = channel();

		assertEquals(Http3DatagramException.Reason.OVERSIZE, refuse(channel, payload(9)));
		assertEquals(0, transport.sends);
		assertEquals(1, channel.datagramsRefused());
		assertNothingIsStillHeld();
	}

	@Test
	public void exactlyTheMaximumPayloadSizeIsAccepted() {
		transport.maxPayloadSize = 8;
		Http3DatagramChannel channel = channel();
		ByteBuf payload = payload(8);
		try {
			channel.send(payload);
		} catch (Http3DatagramException e) {
			throw new AssertionError(e);
		}

		assertEquals(1, transport.sends);
		assertNothingIsStillHeld();
	}

	@Test
	public void aSendRefusedByTheTransportQueueStillOwnsThePayload() {
		transport.refuseWith = Http3DatagramException.Reason.QUEUE_FULL;
		Http3DatagramChannel channel = channel();

		assertEquals(Http3DatagramException.Reason.QUEUE_FULL, refuse(channel, payload(16)));
		// The transport was reached and owns the payload on its own refusal path too.
		assertEquals(1, transport.sends);
		assertEquals(1, channel.datagramsRefused());
		assertEquals(0, channel.datagramsSent());
		assertNothingIsStillHeld();
	}

	@Test
	public void aSendOnAnEndedExchangeStillOwnsThePayload() {
		Http3DatagramChannel channel = channel();
		channel.close();

		assertEquals(Http3DatagramException.Reason.EXCHANGE_ENDED, refuse(channel, payload(16)));
		assertEquals(0, transport.sends);
		assertEquals(1, channel.datagramsRefused());
		assertNothingIsStillHeld();
	}

	@Test
	public void aZeroLengthPayloadIsLegal() {
		Http3DatagramChannel channel = channel();
		try {
			channel.send(ByteBufPool.allocate(0));
		} catch (Http3DatagramException e) {
			throw new AssertionError(e);
		}

		assertEquals(1, transport.sends);
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- the inbound queue

	@Test
	public void aPolledDatagramLeavesTheQueuesOwnership() {
		Http3DatagramChannel channel = channel();
		ByteBuf delivered = payload(4);
		channel.onDatagram(delivered);

		ByteBuf polled = channel.poll();
		assertSame(delivered, polled);
		polled.recycle();
		assertNothingIsStillHeld();
	}

	@Test
	public void aDroppedDatagramIsRecycledExactlyOnce() {
		Http3DatagramChannel channel = channel(1);
		channel.onDatagram(payload(4));
		channel.onDatagram(payload(4));
		assertEquals(1, channel.datagramsDropped());

		ByteBuf survivor = channel.poll();
		assertNull(channel.poll());
		if (survivor != null) survivor.recycle();
		assertNothingIsStillHeld();
	}

	@Test
	public void closeDrainsAndRecyclesEverythingQueued() {
		Http3DatagramChannel channel = channel();
		for (int i = 0; i < 5; i++) {
			channel.onDatagram(payload(4));
		}
		channel.close();
		assertEquals(0, channel.queuedCount());
		assertNothingIsStillHeld();

		// The second close finds nothing left rather than recycling a second time.
		channel.close();
		assertNothingIsStillHeld();
	}

	@Test
	public void aDatagramArrivingAfterCloseIsRecycled() {
		Http3DatagramChannel channel = channel();
		channel.close();
		channel.onDatagram(payload(4));

		assertEquals(0, channel.queuedCount());
		assertNothingIsStillHeld();
	}

	// ---------------------------------------------------------------- helpers

	private Http3DatagramChannel channel() {
		return channel(Http3Settings.DEFAULT_MAX_INBOUND_DATAGRAMS_PER_STREAM);
	}

	private Http3DatagramChannel channel(int maxQueued) {
		return new Http3DatagramChannel(Reactor.getCurrentReactor(), transport, maxQueued);
	}

	/** Sends and asserts a refusal, returning the reason. */
	private static Http3DatagramException.Reason refuse(Http3DatagramChannel channel, ByteBuf payload) {
		try {
			channel.send(payload);
			fail("the send should have been refused");
			throw new AssertionError();
		} catch (Http3DatagramException e) {
			return e.reason();
		}
	}

	/** Pooled buffers allocated but not yet returned — the same idiom `core-quic`'s ownership tests use. */
	private static int liveBuffers() {
		return ByteBufPool.getStats().getCreatedItems() - ByteBufPool.getStats().getPoolItems();
	}

	private void assertNothingIsStillHeld() {
		assertEquals("every path owns its payload exactly once", liveBuffersBefore, liveBuffers());
	}

	private static ByteBuf payload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	/** Takes ownership on both of its paths, exactly as the production transport must. */
	private static final class StubTransport implements Http3DatagramTransport {
		private boolean available = true;
		private long maxPayloadSize = 1200;
		private @Nullable Http3DatagramException.Reason refuseWith;
		private int sends;

		@Override
		public boolean isAvailable() {
			return available;
		}

		@Override
		public long maxPayloadSize() {
			return available ? maxPayloadSize : 0;
		}

		@Override
		public void send(ByteBuf payload) throws Http3DatagramException {
			sends++;
			payload.recycle();
			if (refuseWith != null) throw new Http3DatagramException(refuseWith, "refused by the stub transport");
		}
	}
}
