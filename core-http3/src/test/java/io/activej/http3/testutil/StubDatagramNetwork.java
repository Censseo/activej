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

package io.activej.http3.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.net.socket.udp.UdpPacket;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * A reliable, in-process datagram fabric: a FIFO queue of datagrams routed between bound addresses.
 * Nothing is dropped, reordered, duplicated or delayed.
 * <p>
 * <b>Why the simplification.</b> {@code core-quic}'s {@code DatagramNetwork} is seeded and lossy
 * because loss recovery is what it tests. Reliable ordered delivery is feature 03/04's already-tested
 * responsibility, so an HTTP/3 test that re-simulated loss would be asserting someone else's contract
 * through a slower, flakier fixture (research Decision 12). What survives from that class is the part
 * HTTP/3 does need: delivery is <b>explicit</b>.
 * <p>
 * <b>Delivery is explicit.</b> {@link #send} only enqueues; nothing reaches a receiver until
 * {@link #deliverDue()} runs. That keeps two properties every protocol-level assertion here depends
 * on:
 * <ul>
 *   <li>a {@code send} never re-enters the sender's own receive path synchronously, so real
 *       reentrancy bugs are not masked;</li>
 *   <li>a test that forgets to pump hangs rather than passing by accident.</li>
 * </ul>
 * <p>
 * <b>Ownership (DI-1)</b>: a buffer passed to {@link #send} belongs to the fabric. It is recycled on
 * every path that does not deliver it — an unbound destination at delivery time, a send after
 * {@link #close()}, or a {@link #close()} with datagrams still queued.
 */
public final class StubDatagramNetwork {
	/** One datagram waiting for {@link #deliverDue()}. */
	private record InFlight(InetSocketAddress from, InetSocketAddress to, ByteBuf payload) {}

	/**
	 * Sees every datagram this fabric carries, at {@link #send} and again at delivery.
	 * <p>
	 * The payload is handed over as a <b>copy</b>: the real buffer stays the fabric's, so an observer
	 * cannot consume, retain or recycle what a connection is still going to read (DI-1).
	 */
	@FunctionalInterface
	public interface DatagramObserver {
		void onDatagram(Event event, InetSocketAddress from, InetSocketAddress to, byte[] datagram);
	}

	/** Which end of the wire an observed datagram was at. */
	public enum Event {SENT, DELIVERED}

	private final Map<InetSocketAddress, Consumer<UdpPacket>> receivers = new HashMap<>();
	private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();

	private @Nullable DatagramObserver observer;

	private boolean closed;

	private int sentCount;
	private int deliveredCount;

	/**
	 * Installs the tap of {@link DatagramObserver}; absent otherwise, so a test that does not ask for it
	 * pays neither the copy nor the callback. At most one — the second call replaces the first.
	 */
	public void observe(DatagramObserver observer) {
		this.observer = observer;
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
	 * Enqueues a datagram for the next {@link #deliverDue()}. Takes ownership of {@code payload} — it is
	 * recycled if the destination is unbound by delivery time, or if the fabric is closed.
	 */
	public void send(InetSocketAddress from, InetSocketAddress to, ByteBuf payload) {
		if (closed) {
			payload.recycle();
			return;
		}
		sentCount++;
		notifyObserver(Event.SENT, from, to, payload);
		inFlight.add(new InFlight(from, to, payload));
	}

	private void notifyObserver(Event event, InetSocketAddress from, InetSocketAddress to, ByteBuf payload) {
		DatagramObserver current = observer;
		if (current == null) return;
		byte[] copy = new byte[payload.readRemaining()];
		System.arraycopy(payload.array(), payload.head(), copy, 0, copy.length);
		current.onDatagram(event, from, to, copy);
	}

	/**
	 * Hands every datagram queued when the call started to its bound receiver, in send order.
	 * <p>
	 * Only that snapshot is delivered: a receiver that sends in response enqueues <i>behind</i> it, so
	 * one call is one hop across the wire rather than an unbounded cascade. That is what makes
	 * {@link Http3WirePair#pump()}'s round count a meaningful bound rather than a formality.
	 *
	 * @return how many datagrams were delivered
	 */
	public int deliverDue() {
		int queued = inFlight.size();
		int delivered = 0;
		for (int i = 0; i < queued; i++) {
			InFlight entry = requireNonNull(inFlight.poll());
			Consumer<UdpPacket> receiver = receivers.get(entry.to());
			if (receiver == null) {
				// Destination closed while the datagram was queued: drop it, do not leak it.
				entry.payload().recycle();
				continue;
			}
			deliveredCount++;
			delivered++;
			notifyObserver(Event.DELIVERED, entry.from(), entry.to(), entry.payload());
			// The address a receiver sees is the SOURCE, matching UdpSocket's convention — this is what
			// server dispatch keys on.
			receiver.accept(UdpPacket.of(entry.payload(), entry.from()));
		}
		return delivered;
	}

	/** Datagrams enqueued but not yet delivered. */
	public int inFlightCount() {
		return inFlight.size();
	}

	public int sentCount() {
		return sentCount;
	}

	public int deliveredCount() {
		return deliveredCount;
	}

	/**
	 * Recycles every queued datagram and forgets every binding. Without this, a test that ends
	 * mid-flight leaks and {@code ByteBufRule} blames the wrong component. Idempotent.
	 */
	public void close() {
		InFlight entry;
		while ((entry = inFlight.poll()) != null) {
			entry.payload().recycle();
		}
		receivers.clear();
		closed = true;
	}

	@Override
	public String toString() {
		return "StubDatagramNetwork{" +
			"sent=" + sentCount +
			", delivered=" + deliveredCount +
			", inFlight=" + inFlight.size() +
			(closed ? ", closed" : "") +
			'}';
	}
}
