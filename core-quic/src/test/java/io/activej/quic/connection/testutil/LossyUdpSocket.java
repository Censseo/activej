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

import io.activej.async.exception.AsyncCloseException;
import io.activej.net.socket.udp.IUdpSocket;
import io.activej.net.socket.udp.UdpPacket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;

/**
 * An {@link IUdpSocket} over {@link DatagramNetwork}.
 * <p>
 * This is the whole reason the loss tests are trustworthy: {@code IUdpSocket} is already a three-method
 * interface, so the harness plugs in at an existing seam and <b>no production code changes and no
 * test-only hook in the connection are needed</b>. The code under test is byte-identical to the code
 * that runs over a real socket — the same trick {@code StubHttpClient} plays for HTTP.
 * <p>
 * <b>Ownership (DI-1)</b>: {@link #send} hands its buffer to the fabric and must not recycle it
 * afterwards. Buffers are recycled when they arrive at a closed socket, when {@code send} is called on
 * a closed socket, and when {@link #close} finds an unread inbox.
 */
public final class LossyUdpSocket implements IUdpSocket {
	/** Bounded so a test that never calls {@code receive()} cannot quietly exhaust the pool. */
	private static final int MAX_INBOX = 1024;

	private final DatagramNetwork network;
	private final InetSocketAddress localAddress;

	private final ArrayDeque<UdpPacket> inbox = new ArrayDeque<>();
	private @Nullable SettablePromise<UdpPacket> pendingReceive;
	private boolean closed;

	private int droppedByOverflow;

	public LossyUdpSocket(DatagramNetwork network, InetSocketAddress localAddress) {
		this.network = network;
		this.localAddress = localAddress;
		network.bind(localAddress, this::onReceive);
	}

	private void onReceive(UdpPacket packet) {
		if (closed) {
			packet.recycle();
			return;
		}
		SettablePromise<UdpPacket> pending = pendingReceive;
		if (pending != null) {
			pendingReceive = null;
			pending.set(packet);
			return;
		}
		if (inbox.size() >= MAX_INBOX) {
			UdpPacket oldest = inbox.poll();
			if (oldest != null) oldest.recycle();
			droppedByOverflow++;
		}
		inbox.add(packet);
	}

	@Override
	public Promise<UdpPacket> receive() {
		if (closed) {
			return Promise.ofException(new AsyncCloseException("UDP socket closed"));
		}
		UdpPacket buffered = inbox.poll();
		if (buffered != null) {
			return Promise.of(buffered);
		}
		if (pendingReceive != null) {
			// UdpSocket has one pending read slot; allowing two here would let a test pass where
			// production would break.
			throw new IllegalStateException("Concurrent receive() on the same socket");
		}
		SettablePromise<UdpPacket> promise = new SettablePromise<>();
		pendingReceive = promise;
		return promise;
	}

	@Override
	public Promise<Void> send(UdpPacket packet) {
		if (closed) {
			packet.recycle();
			return Promise.ofException(new AsyncCloseException("UDP socket closed"));
		}
		// getSocketAddress() on an outbound packet is the DESTINATION — the inverse of its meaning on
		// a received packet. That is UdpSocket's existing convention, and this double matches it.
		network.send(localAddress, packet.getSocketAddress(), packet.getBuf());
		// Ownership of the buffer is now the fabric's. Do NOT call packet.recycle(): UdpPacket.recycle
		// recycles the buffer we just handed over, which would be a double recycle.
		return Promise.complete();
	}

	@Override
	public void close() {
		if (closed) return;
		closed = true;
		network.unbind(localAddress);
		UdpPacket packet;
		while ((packet = inbox.poll()) != null) {
			packet.recycle();
		}
		SettablePromise<UdpPacket> pending = pendingReceive;
		if (pending != null) {
			pendingReceive = null;
			pending.setException(new AsyncCloseException("UDP socket closed"));
		}
	}

	@Override
	public InetSocketAddress getLocalAddress() {
		return localAddress;
	}

	/** Datagrams buffered because no {@code receive()} was outstanding. */
	public int inboxSize() {
		return inbox.size();
	}

	public int droppedByOverflow() {
		return droppedByOverflow;
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public String toString() {
		return "LossyUdpSocket{" + localAddress + ", inbox=" + inbox.size() + (closed ? ", closed" : "") + '}';
	}
}
