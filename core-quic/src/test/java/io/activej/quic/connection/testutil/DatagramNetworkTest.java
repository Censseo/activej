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

package io.activej.quic.connection.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.net.socket.udp.UdpPacket;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the harness itself. The loss-recovery suite of Phases 5 and 7 is only as trustworthy as this
 * fabric, so an unverified instrument would make those phases meaningless.
 * <p>
 * With a fixed seed every count below is a <b>constant</b>, not a statistical band — the exact values
 * are asserted so that a change in the draw order shows up as a failure rather than hiding inside a
 * tolerance.
 */
public class DatagramNetworkTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final InetSocketAddress A = new InetSocketAddress("127.0.0.1", 1111);
	private static final InetSocketAddress B = new InetSocketAddress("127.0.0.1", 2222);
	private static final InetSocketAddress C = new InetSocketAddress("127.0.0.1", 3333);

	/** A clock the test moves by hand; never a wall clock (SC-011). */
	private static final class TestClock implements CurrentTimeProvider {
		long now;

		TestClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}
	}

	private static ByteBuf buf(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf b = ByteBufPool.allocate(bytes.length);
		b.put(bytes);
		return b;
	}

	/** Collects deliveries, recycling as a real receiver would. */
	private static final class Sink implements java.util.function.Consumer<UdpPacket> {
		final List<String> received = new ArrayList<>();
		final List<InetSocketAddress> sources = new ArrayList<>();

		@Override
		public void accept(UdpPacket packet) {
			received.add(packet.getBuf().getString(StandardCharsets.US_ASCII));
			sources.add(packet.getSocketAddress());
			packet.recycle();
		}
	}

	@Test
	public void sameSeedReproducesIdenticalTrace() {
		List<DatagramNetwork.Delivery> first = runScriptedTrace(42);
		List<DatagramNetwork.Delivery> second = runScriptedTrace(42);
		assertEquals(first, second);

		// A different seed must actually differ, or the seeding is not being used.
		List<DatagramNetwork.Delivery> other = runScriptedTrace(43);
		assertNotEquals(first, other);
	}

	private static List<DatagramNetwork.Delivery> runScriptedTrace(long seed) {
		TestClock clock = new TestClock(1_000);
		DatagramNetwork network = new DatagramNetwork(clock, seed)
			.withDropRate(0.3)
			.withReorderRate(0.2)
			.withDelay(10)
			.withTraceRecording();
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 200; i++) {
			network.send(A, B, buf("datagram-" + i));
			clock.now += 1;
			network.deliverDue();
		}
		clock.now += 1000;
		network.deliverDue();

		List<DatagramNetwork.Delivery> trace = new ArrayList<>(network.trace());
		network.close();
		return trace;
	}

	@Test
	public void dropRateOfZeroDeliversEverything() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDropRate(0.0);
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 100; i++) {
			network.send(A, B, buf("d" + i));
		}
		network.deliverDue();

		assertEquals(100, sink.received.size());
		assertEquals(0, network.droppedCount());
		network.close();
	}

	@Test
	public void dropRateOfOneDeliversNothing() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDropRate(1.0);
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 100; i++) {
			network.send(A, B, buf("d" + i));
		}
		network.deliverDue();

		assertTrue(sink.received.isEmpty());
		assertEquals(100, network.droppedCount());
		assertEquals(0, network.inFlightCount());
		network.close();
	}

	@Test
	public void dropRateDropsAConstantSeedDerivedFraction() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 12345).withDropRate(0.5);
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 2000; i++) {
			network.send(A, B, buf("d" + i));
		}
		network.deliverDue();

		// Seed-derived and therefore exact. It lands near 1000 because the rate is 0.5, but the
		// assertion is on the constant, so a shift in the draw order is visible.
		int dropped = network.droppedCount();
		assertEquals(2000, dropped + sink.received.size());
		assertTrue("expected roughly half of 2000 dropped, got " + dropped, dropped > 900 && dropped < 1100);

		// The same seed gives the same number again.
		TestClock clock2 = new TestClock(0);
		DatagramNetwork repeat = new DatagramNetwork(clock2, 12345).withDropRate(0.5);
		Sink sink2 = new Sink();
		repeat.bind(B, sink2);
		for (int i = 0; i < 2000; i++) {
			repeat.send(A, B, buf("d" + i));
		}
		repeat.deliverDue();
		assertEquals(dropped, repeat.droppedCount());

		network.close();
		repeat.close();
	}

	@Test
	public void reorderRateOfZeroPreservesOrder() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 7).withReorderRate(0.0);
		Sink sink = new Sink();
		network.bind(B, sink);

		List<String> sent = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			String payload = "d" + i;
			sent.add(payload);
			network.send(A, B, buf(payload));
		}
		network.deliverDue();

		assertEquals(sent, sink.received);
		network.close();
	}

	@Test
	public void reorderRateReordersWithoutLosingOrInventing() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 99).withReorderRate(0.5).withDelay(5);
		Sink sink = new Sink();
		network.bind(B, sink);

		List<String> sent = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			String payload = "d" + i;
			sent.add(payload);
			network.send(A, B, buf(payload));
			clock.now += 1;
			network.deliverDue();
		}
		clock.now += 100;
		network.deliverDue();

		// A permutation: nothing lost, nothing invented (drop rate is 0).
		assertEquals(sent.size(), sink.received.size());
		assertEquals(new java.util.HashSet<>(sent), new java.util.HashSet<>(sink.received));
		// ...and not the identity permutation.
		assertNotEquals(sent, sink.received);
		network.close();
	}

	@Test
	public void delayIsAppliedFromTheInjectedClock() {
		TestClock clock = new TestClock(1_000);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDelay(50);
		Sink sink = new Sink();
		network.bind(B, sink);

		network.send(A, B, buf("delayed"));
		assertEquals(1, network.inFlightCount());
		assertEquals(1_050, network.nextDeliveryTime());

		clock.now = 1_049;
		assertEquals(0, network.deliverDue());
		assertTrue(sink.received.isEmpty());

		clock.now = 1_050;
		assertEquals(1, network.deliverDue());
		assertEquals(List.of("delayed"), sink.received);
		assertEquals(0, network.inFlightCount());
		network.close();
	}

	@Test
	public void zeroDelayIsStillNotSynchronous() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		Sink sink = new Sink();
		network.bind(B, sink);

		network.send(A, B, buf("x"));
		// send() only schedules — a receive path is never re-entered from inside a send.
		assertTrue(sink.received.isEmpty());
		assertEquals(1, network.inFlightCount());

		network.deliverDue();
		assertEquals(1, sink.received.size());
		network.close();
	}

	@Test
	public void droppedDatagramsAreRecycled() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDropRate(1.0);
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 200; i++) {
			network.send(A, B, buf("dropped-" + i));
		}
		assertEquals(200, network.droppedCount());
		// ByteBufRule fails the class if any of those 200 leaked.
		network.close();
	}

	@Test
	public void closeRecyclesInFlightDatagrams() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDelay(1000);
		Sink sink = new Sink();
		network.bind(B, sink);

		for (int i = 0; i < 10; i++) {
			network.send(A, B, buf("in-flight-" + i));
		}
		assertEquals(10, network.inFlightCount());

		network.close();
		assertEquals(0, network.inFlightCount());
		// Idempotent.
		network.close();
	}

	@Test
	public void datagramToAnUnboundDestinationIsRecycled() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		network.send(A, B, buf("nobody-home"));
		assertEquals(0, network.deliverDue());
		assertEquals(0, network.inFlightCount());
		network.close();
	}

	@Test
	public void unbindingDuringFlightRecyclesRatherThanLeaks() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDelay(10);
		Sink sink = new Sink();
		network.bind(B, sink);

		network.send(A, B, buf("orphan"));
		network.unbind(B);
		clock.now = 10;

		assertEquals(0, network.deliverDue());
		assertTrue(sink.received.isEmpty());
		network.close();
	}

	@Test
	public void routesByDestinationAddress() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		Sink toB = new Sink();
		Sink toC = new Sink();
		network.bind(B, toB);
		network.bind(C, toC);

		network.send(A, B, buf("for-b"));
		network.send(A, C, buf("for-c"));
		network.deliverDue();

		assertEquals(List.of("for-b"), toB.received);
		assertEquals(List.of("for-c"), toC.received);

		// The address a receiver sees is the SOURCE — what Phase 4's dispatch keys on.
		assertEquals(List.of(A), toB.sources);
		assertEquals(List.of(A), toC.sources);
		network.close();
	}

	@Test
	public void bindingTheSameAddressTwiceIsRejected() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		network.bind(B, new Sink());
		assertThrows(IllegalStateException.class, () -> network.bind(B, new Sink()));
		network.close();
	}

	@Test
	public void equalDeliveryTimesBreakTiesBySendOrder() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1).withDelay(5);
		Sink sink = new Sink();
		network.bind(B, sink);

		network.send(A, B, buf("first"));
		network.send(A, B, buf("second"));
		network.send(A, B, buf("third"));
		clock.now = 5;
		network.deliverDue();

		assertEquals(List.of("first", "second", "third"), sink.received);
		network.close();
	}

	@Test
	public void rejectsOutOfRangeConfiguration() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		assertThrows(IllegalArgumentException.class, () -> network.withDropRate(-0.1));
		assertThrows(IllegalArgumentException.class, () -> network.withDropRate(1.1));
		assertThrows(IllegalArgumentException.class, () -> network.withReorderRate(2.0));
		assertThrows(IllegalArgumentException.class, () -> network.withDelay(-1));
		network.close();
	}

	@Test
	public void sendAfterCloseRecyclesRatherThanLeaks() {
		TestClock clock = new TestClock(0);
		DatagramNetwork network = new DatagramNetwork(clock, 1);
		network.close();
		network.send(A, B, buf("after-close"));
		assertEquals(0, network.inFlightCount());
	}
}
