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

import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.tls.*;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.time.TestCurrentTimeProvider;
import io.activej.test.time.TestCurrentTimeProvider.SettableCurrentTimeProvider;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link QuicEndpoint} fixture over the seeded {@link DatagramNetwork}: one server endpoint, any
 * number of client endpoints, and a {@link #pump()} that runs the exchange to a standstill.
 * <p>
 * Endpoints are driven by their own receive loops, so unlike the direct-wire fixture in
 * {@code QuicHandshakeTest} this exercises {@link QuicEndpoint#listen()} and the real dispatch path.
 * Delivery still happens only when the test asks for it — {@link DatagramNetwork} never moves on its
 * own, so a test that forgets {@link #pump()} hangs rather than passing by accident.
 */
public final class QuicEndpointFixture implements AutoCloseable {
	/** Synthetic: nothing binds to the OS, so a real free port would be a race under {@code -T1C}. */
	public static final InetSocketAddress SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 40100);

	private final SettableCurrentTimeProvider clock;
	private final @org.jetbrains.annotations.Nullable ManualEventloop loop;
	private final DatagramNetwork network;
	private final List<LossyUdpSocket> sockets = new ArrayList<>();
	private final List<QuicEndpoint> endpoints = new ArrayList<>();

	private int nextClientPort = 40200;
	private long now = 1_000_000;

	public QuicEndpointFixture() {
		this(1);
	}

	public QuicEndpointFixture(long seed) {
		this.loop = null;
		this.clock = QuicTestPeers.settableClock(1_000_000);
		this.network = new DatagramNetwork(clock, seed);
	}

	/**
	 * Shares one clock between the wire and the eventloop, so a connection's own timers — loss
	 * detection, probes, the idle timeout — advance with {@link #advance}.
	 * <p>
	 * Without this, {@code EventloopRule}'s eventloop runs on the system clock while the network runs on
	 * the fixture clock, and no amount of advancing moves a retransmission.
	 */
	public QuicEndpointFixture(ManualEventloop loop, long seed) {
		this.loop = loop;
		this.clock = loop.clock();
		this.now = loop.currentTimeMillis();
		this.network = new DatagramNetwork(clock, seed);
	}

	public DatagramNetwork network() {
		return network;
	}

	/** The TLS factory a server endpoint needs, using the dev ECDSA identity. */
	public static TlsEngineFactory serverEngineFactory() {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		return params -> QuicTls.serverEngine(TlsServerConfig.builder(identity, params).build());
	}

	/** The TLS factory a client needs, trusting exactly the dev leaf so hostname checks stay live. */
	public static TlsEngineFactory clientEngineFactory(String serverName) {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		return params -> QuicTls.clientEngine(TlsClientConfig.builder(serverName, params)
			.withTrustManager(QuicTestPeers.trustingLeaf(identity.leaf()))
			.build());
	}

	public static TlsEngineFactory clientEngineFactory() {
		return clientEngineFactory("localhost");
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	/** A listening server endpoint at {@link #SERVER_ADDRESS}. */
	public QuicEndpoint server(QuicConnectionSettings settings) {
		return server(settings, builder -> builder);
	}

	/** A listening server endpoint, with {@code customize} applied to its builder before {@code build()}. */
	public QuicEndpoint server(
		QuicConnectionSettings settings, java.util.function.UnaryOperator<QuicEndpoint.Builder> customize
	) {
		LossyUdpSocket socket = new LossyUdpSocket(network, SERVER_ADDRESS);
		sockets.add(socket);
		QuicEndpoint endpoint = customize.apply(QuicEndpoint.builder(reactor(), socket)
				.withSettings(settings)
				.withServerEngineFactory(serverEngineFactory()))
			.build();
		endpoints.add(endpoint);
		endpoint.listen();
		return endpoint;
	}

	/** A client-only endpoint on a fresh synthetic address. It cannot accept, by construction. */
	public QuicEndpoint client(QuicConnectionSettings settings) {
		InetSocketAddress address = new InetSocketAddress("127.0.0.1", nextClientPort++);
		LossyUdpSocket socket = new LossyUdpSocket(network, address);
		sockets.add(socket);
		QuicEndpoint endpoint = QuicEndpoint.builder(reactor(), socket)
			.withSettings(settings)
			.build();
		endpoints.add(endpoint);
		return endpoint;
	}

	/**
	 * Delivers datagrams both ways until nothing more comes due.
	 * <p>
	 * Delivery re-enters the endpoints' receive loops, which usually produce more datagrams, hence the
	 * loop rather than a single call.
	 */
	public void pump() {
		for (int round = 0; round < 64; round++) {
			if (network.deliverDue() == 0) return;
		}
		throw new AssertionError("the exchange did not settle within 64 delivery rounds");
	}

	public long currentTimeMillis() {
		return now;
	}

	/**
	 * Moves the clock forward, then lets everything that became due run: first the connections' own
	 * timers (only when built on a {@link ManualEventloop}), then delivery, then the timers again — a
	 * retransmission is a timer that produces a datagram, and a delivery is a datagram that arms a
	 * timer, so one pass of each is not enough.
	 */
	public void advance(long deltaMillis) {
		now += deltaMillis;
		if (loop != null) {
			loop.setTime(now);
			loop.tickUntilQuiet();
			pump();
			loop.tickUntilQuiet();
			return;
		}
		clock.setTimeProvider(TestCurrentTimeProvider.ofConstant(now));
		pump();
	}

	@Override
	public void close() {
		for (QuicEndpoint endpoint : endpoints) {
			endpoint.close();
		}
		// Endpoints close their own sockets; this covers any socket built without one.
		for (LossyUdpSocket socket : sockets) {
			socket.close();
		}
		network.close();
	}
}
