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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.connection.CoalescedPackets;
import io.activej.quic.connection.CoalescedPackets.ProtectedPacket;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.quic.tls.TlsServerConfig;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;

/**
 * A client and a server {@link QuicConnection} joined by two hand-pumped queues, with no
 * {@link QuicEndpoint} and no socket in between.
 * <p>
 * Datagrams are queued rather than delivered synchronously: a sink that called the peer's
 * {@code onDatagram} directly would re-enter the sender's own flush loop, a shape no real socket
 * produces and one that would let a re-entrancy bug pass unnoticed.
 * <p>
 * This is the fixture for behaviour that belongs to a <i>connection</i> — termination, ACK scheduling,
 * loss recovery — where an endpoint's dispatch table is noise. It reads the clock from the current
 * reactor, so pairing it with a {@link ManualEventloop} is what makes its timers deterministic.
 */
public final class QuicWirePair implements AutoCloseable {
	public static final InetSocketAddress CLIENT_ADDRESS = QuicTestPeers.CLIENT_ADDRESS;
	public static final InetSocketAddress SERVER_ADDRESS = QuicTestPeers.SERVER_ADDRESS;

	/** The bound on {@link #pump()}: a handshake settles in four or five rounds. */
	private static final int MAX_ROUNDS = 32;

	/** One direction's queue, and the switch that turns it into a black hole. */
	public static final class Wire implements QuicConnection.DatagramSink {
		private final ArrayDeque<ByteBuf> queue = new ArrayDeque<>();
		private boolean blackhole;
		private int datagramsAccepted;

		@Override
		public void send(InetSocketAddress to, ByteBuf datagram) {
			datagramsAccepted++;
			if (blackhole) {
				datagram.recycle();
				return;
			}
			queue.add(datagram);
		}

		/** Every datagram this wire was ever handed, delivered or not. */
		public int datagramsAccepted() {
			return datagramsAccepted;
		}

		public int queued() {
			return queue.size();
		}

		public void blackhole(boolean blackhole) {
			this.blackhole = blackhole;
		}

		public @Nullable ByteBuf poll() {
			return queue.poll();
		}

		public void drain() {
			ByteBuf datagram;
			while ((datagram = queue.poll()) != null) {
				datagram.recycle();
			}
		}
	}

	private final Wire clientWire = new Wire();
	private final Wire serverWire = new Wire();

	private @Nullable QuicConnection client;
	private @Nullable QuicConnection server;
	private @Nullable QuicFrameHandler clientFrameHandler;
	private @Nullable QuicFrameHandler serverFrameHandler;

	/** Registers the layer above the client. Must be called before {@link #startClient}. */
	public QuicWirePair withClientFrameHandler(QuicFrameHandler handler) {
		this.clientFrameHandler = handler;
		return this;
	}

	/** Registers the layer above the server. Must be called before {@link #acceptServer}. */
	public QuicWirePair withServerFrameHandler(QuicFrameHandler handler) {
		this.serverFrameHandler = handler;
		return this;
	}

	public Wire clientWire() {
		return clientWire;
	}

	public Wire serverWire() {
		return serverWire;
	}

	public QuicConnection client() {
		if (client == null) throw new IllegalStateException("startClient has not been called");
		return client;
	}

	public QuicConnection server() {
		if (server == null) throw new IllegalStateException("acceptServer has not been called");
		return server;
	}

	// ---------------------------------------------------------------- construction

	public Promise<QuicConnection> startClient(QuicConnectionSettings settings) {
		return startClient("localhost", settings);
	}

	public Promise<QuicConnection> startClient(String serverName, QuicConnectionSettings settings) {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		client = QuicConnection.builder(Reactor.getCurrentReactor(), Role.CLIENT, clientWire, SERVER_ADDRESS,
				params -> QuicTls.clientEngine(TlsClientConfig.builder(serverName, params)
					.withTrustManager(QuicTestPeers.trustingLeaf(identity.leaf()))
					.build()))
			.withSettings(settings)
			.initialize(builder -> {
				if (clientFrameHandler != null) builder.withFrameHandler(clientFrameHandler);
			})
			.build();
		return client.start();
	}

	/**
	 * Builds the server from the connection IDs of the client's first Initial — exactly as
	 * {@link QuicEndpoint} does — and hands it that datagram.
	 */
	public Promise<QuicConnection> acceptServer(QuicConnectionSettings settings) throws MalformedDataException {
		ByteBuf firstDatagram = clientWire.queue.poll();
		if (firstDatagram == null) throw new IllegalStateException("the client sent no Initial");

		QuicConnectionId clientDcid;
		QuicConnectionId clientScid;
		// split() reads by index and never consumes the datagram, so it can be forwarded afterwards.
		List<ProtectedPacket> packets = CoalescedPackets.split(firstDatagram, 0, QuicPackets.SUPPORTED_VERSION);
		try {
			ProtectedPacket initial = packets.get(0);
			clientDcid = initial.destinationConnectionId();
			clientScid = initial.sourceConnectionId();
		} finally {
			for (ProtectedPacket packet : packets) {
				packet.bytes().recycle();
			}
		}
		if (clientScid == null) throw new IllegalStateException("a client Initial must carry a source connection ID");

		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		server = QuicConnection.builder(Reactor.getCurrentReactor(), Role.SERVER, serverWire, CLIENT_ADDRESS,
				params -> QuicTls.serverEngine(TlsServerConfig.builder(identity, params).build()))
			.withSettings(settings)
			.withPeerConnectionId(clientScid)
			.withOriginalDestinationConnectionId(clientDcid)
			.initialize(builder -> {
				if (serverFrameHandler != null) builder.withFrameHandler(serverFrameHandler);
			})
			.build();
		Promise<QuicConnection> promise = server.start();
		server.onDatagram(firstDatagram);
		return promise;
	}

	/** Starts both sides with {@code handler} above the server, and pumps to a standstill. */
	public void handshakeWithServerFrameHandler(QuicConnectionSettings settings, QuicFrameHandler handler)
		throws MalformedDataException {
		withServerFrameHandler(handler);
		handshake(settings);
	}

	/** Starts both sides and pumps to a standstill, leaving an established connection. */
	public void handshake(QuicConnectionSettings settings) throws MalformedDataException {
		startClient(settings);
		acceptServer(settings);
		pump();
	}

	// ---------------------------------------------------------------- delivery

	/** Delivers queued datagrams both ways until neither side has anything left to say. */
	public void pump() {
		for (int round = 0; round < MAX_ROUNDS; round++) {
			boolean progress = false;
			ByteBuf datagram;
			while ((datagram = clientWire.queue.poll()) != null) {
				server().onDatagram(datagram);
				progress = true;
			}
			while ((datagram = serverWire.queue.poll()) != null) {
				client().onDatagram(datagram);
				progress = true;
			}
			if (!progress) return;
		}
		throw new AssertionError("the exchange did not settle within " + MAX_ROUNDS + " delivery rounds");
	}

	/** Delivers one datagram from the client to the server, if there is one. */
	public boolean deliverToServer() {
		ByteBuf datagram = clientWire.queue.poll();
		if (datagram == null) return false;
		server().onDatagram(datagram);
		return true;
	}

	/** Delivers one datagram from the server to the client, if there is one. */
	public boolean deliverToClient() {
		ByteBuf datagram = serverWire.queue.poll();
		if (datagram == null) return false;
		client().onDatagram(datagram);
		return true;
	}

	@Override
	public void close() {
		// Order matters: closing can enqueue a CONNECTION_CLOSE, so drain afterwards.
		if (client != null) client.closeNow();
		if (server != null) server.closeNow();
		clientWire.drain();
		serverWire.drain();
	}
}
