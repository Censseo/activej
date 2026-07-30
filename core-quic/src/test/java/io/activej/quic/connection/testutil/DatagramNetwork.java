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
import io.activej.common.time.CurrentTimeProvider;
import io.activej.net.socket.udp.UdpPacket;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A seeded, in-process datagram fabric: routes datagrams between bound addresses while dropping,
 * reordering and delaying them under a fixed seed, so a loss scenario reproduces byte-identically
 * across runs.
 * <p>
 * <b>Delivery is explicit.</b> {@link #send} only schedules; nothing is handed to a receiver until
 * {@link #deliverDue()} runs, and then only datagrams whose delivery time has arrived according to
 * the injected clock. That keeps two properties the loss-recovery suite depends on:
 * <ul>
 *   <li>a {@code send} never re-enters the sender's own receive path synchronously, so real
 *       reentrancy bugs are not masked;</li>
 *   <li>time is entirely the test's to control — no sleeping, and no dependence on the eventloop's
 *       timer wheel, which with a frozen clock would either spin or never fire.</li>
 * </ul>
 * <p>
 * <b>Ownership (DI-1)</b>: a buffer passed to {@link #send} belongs to the fabric. It is recycled on
 * every path that does not deliver it — a drop, an unbound destination, or {@link #close()}.
 * <p>
 * <b>Random draw order</b> is part of the reproducibility contract: exactly one draw per {@code send},
 * for the drop decision. Reordering uses a deterministic extra delay rather than a second draw, so
 * adding or removing a configuration knob does not shift every existing trace.
 */
public final class DatagramNetwork {
	/** One datagram in flight. */
	private static final class InFlight implements Comparable<InFlight> {
		final long deliveryTime;
		final long sequence;
		final InetSocketAddress from;
		final InetSocketAddress to;
		final ByteBuf payload;

		InFlight(long deliveryTime, long sequence, InetSocketAddress from, InetSocketAddress to, ByteBuf payload) {
			this.deliveryTime = deliveryTime;
			this.sequence = sequence;
			this.from = from;
			this.to = to;
			this.payload = payload;
		}

		@Override
		public int compareTo(InFlight other) {
			int byTime = Long.compare(deliveryTime, other.deliveryTime);
			// Ties break by send order, so equal-time deliveries are deterministic.
			return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
		}
	}

	/** A delivered datagram, recorded when tracing is on. */
	public record Delivery(InetSocketAddress from, InetSocketAddress to, int size, long deliveryTime) {}

	private final CurrentTimeProvider clock;
	private final Random random;

	private final Map<InetSocketAddress, Consumer<UdpPacket>> receivers = new HashMap<>();
	private final PriorityQueue<InFlight> inFlight = new PriorityQueue<>();
	private final List<Delivery> trace = new ArrayList<>();

	private double dropRate;
	private double reorderRate;
	private long delayMillis;
	private boolean recordTrace;
	private long sequence;
	private boolean closed;

	private int sentCount;
	private int droppedCount;
	private int deliveredCount;

	public DatagramNetwork(CurrentTimeProvider clock, long seed) {
		this.clock = clock;
		this.random = new Random(seed);
	}

	public DatagramNetwork withDropRate(double dropRate) {
		if (dropRate < 0 || dropRate > 1) throw new IllegalArgumentException("dropRate must be in [0, 1]");
		this.dropRate = dropRate;
		return this;
	}

	public DatagramNetwork withReorderRate(double reorderRate) {
		if (reorderRate < 0 || reorderRate > 1) throw new IllegalArgumentException("reorderRate must be in [0, 1]");
		this.reorderRate = reorderRate;
		return this;
	}

	public DatagramNetwork withDelay(long delayMillis) {
		if (delayMillis < 0) throw new IllegalArgumentException("delay must not be negative");
		this.delayMillis = delayMillis;
		return this;
	}

	public DatagramNetwork withTraceRecording() {
		this.recordTrace = true;
		return this;
	}

	public void bind(InetSocketAddress address, Consumer<UdpPacket> receiver) {
		if (receivers.putIfAbsent(address, receiver) != null) {
			throw new IllegalStateException("Address already bound: " + address);
		}
	}

	public void unbind(InetSocketAddress address) {
		receivers.remove(address);
	}

	/**
	 * Schedules a datagram. Takes ownership of {@code payload} — it is recycled if dropped, if the
	 * destination is unbound at delivery time, or if the fabric is closed while it is in flight.
	 */
	public void send(InetSocketAddress from, InetSocketAddress to, ByteBuf payload) {
		if (closed) {
			payload.recycle();
			return;
		}
		sentCount++;

		// Draw exactly once, for the drop decision.
		if (dropRate > 0 && random.nextDouble() < dropRate) {
			droppedCount++;
			payload.recycle();
			return;
		}

		long extra = 0;
		if (reorderRate > 0 && random.nextDouble() < reorderRate) {
			// A deterministic extra delay, enough to be overtaken by the next datagram.
			extra = delayMillis > 0 ? delayMillis : 1;
		}

		inFlight.add(new InFlight(clock.currentTimeMillis() + delayMillis + extra, sequence++, from, to, payload));
	}

	/**
	 * Delivers every datagram whose delivery time has arrived. Idempotent: a datagram is removed
	 * before it is handed over, so it cannot be delivered twice.
	 *
	 * @return how many datagrams were delivered
	 */
	public int deliverDue() {
		long now = clock.currentTimeMillis();
		int delivered = 0;
		InFlight entry;
		while ((entry = inFlight.peek()) != null && entry.deliveryTime <= now) {
			inFlight.poll();
			Consumer<UdpPacket> receiver = receivers.get(entry.to);
			if (receiver == null) {
				// Destination closed while the datagram was in flight: drop it, do not leak it.
				entry.payload.recycle();
				continue;
			}
			if (recordTrace) {
				trace.add(new Delivery(entry.from, entry.to, entry.payload.readRemaining(), entry.deliveryTime));
			}
			deliveredCount++;
			delivered++;
			// The address a receiver sees is the SOURCE, matching UdpSocket's convention — this is
			// what server dispatch keys on.
			receiver.accept(UdpPacket.of(entry.payload, entry.from));
		}
		return delivered;
	}

	/** Datagrams scheduled but not yet delivered. */
	public int inFlightCount() {
		return inFlight.size();
	}

	/** The delivery time of the earliest in-flight datagram, or -1 when nothing is in flight. */
	public long nextDeliveryTime() {
		InFlight entry = inFlight.peek();
		return entry == null ? -1 : entry.deliveryTime;
	}

	public int sentCount() {
		return sentCount;
	}

	public int droppedCount() {
		return droppedCount;
	}

	public int deliveredCount() {
		return deliveredCount;
	}

	/** The recorded delivery trace; empty unless {@link #withTraceRecording()} was called. */
	public List<Delivery> trace() {
		return trace;
	}

	/**
	 * Recycles every datagram still in flight and forgets every binding. Without this, a test that
	 * ends mid-flight leaks and {@code ByteBufRule} blames the wrong component. Idempotent.
	 */
	public void close() {
		InFlight entry;
		while ((entry = inFlight.poll()) != null) {
			entry.payload.recycle();
		}
		receivers.clear();
		closed = true;
	}

	@Override
	public String toString() {
		return "DatagramNetwork{" +
			"dropRate=" + dropRate +
			", reorderRate=" + reorderRate +
			", delay=" + delayMillis + "ms" +
			", sent=" + sentCount +
			", dropped=" + droppedCount +
			", delivered=" + deliveredCount +
			", inFlight=" + inFlight.size() +
			'}';
	}
}
