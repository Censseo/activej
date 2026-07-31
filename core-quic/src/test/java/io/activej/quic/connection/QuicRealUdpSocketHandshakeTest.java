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

import io.activej.net.socket.udp.UdpSocket;
import io.activej.promise.Promise;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.DatagramSocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.*;

/**
 * QA gap 6.1 (SC-001) — every other test in this package goes through {@code LossyUdpSocket} over the
 * in-process {@code DatagramNetwork}, deliberately, for determinism. That leaves the {@code IUdpSocket}
 * contract against ActiveJ's real {@link UdpSocket} — buffer ownership on receive/send, the
 * synchronous-completion behaviour {@link QuicEndpoint}'s receive loop depends on, actual datagram
 * delivery — unexercised. This is the one test that binds two real OS sockets on loopback and drives a
 * handshake through it.
 * <p>
 * Deliberately kept to this single test: it depends on real loopback I/O and a real {@code Eventloop}
 * selector rather than a seeded clock, so it does not belong in the deterministic suite (QA test plan
 * §6.1).
 * <p>
 * Note the two sides do <b>not</b> reach {@code ESTABLISHED} at the same instant: per RFC 8446 §4.4.4 a
 * TLS 1.3 client completes as soon as it has verified the server's Finished, one flight earlier than the
 * server — which only completes once the client's own Finished has crossed the network and been
 * processed. Over the deterministic in-process fixture that gap is invisible because {@code pump()}
 * drains every queue repeatedly; over a real socket it is a genuine extra network hop, so this test
 * waits on <i>both</i> sides' establishment promises rather than assuming the client's implies the
 * server's.
 */
public final class QuicRealUdpSocketHandshakeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void aHandshakeCompletesOverRealLoopbackUdpSockets() throws Exception {
		NioReactor reactor = (NioReactor) Reactor.getCurrentReactor();

		DatagramChannel serverChannel = NioReactor.createDatagramChannel(
			DatagramSocketSettings.create(), new InetSocketAddress("127.0.0.1", 0), null);
		InetSocketAddress serverAddress = (InetSocketAddress) serverChannel.getLocalAddress();
		DatagramChannel clientChannel = NioReactor.createDatagramChannel(
			DatagramSocketSettings.create(), new InetSocketAddress("127.0.0.1", 0), null);

		QuicEndpoint[] serverHolder = new QuicEndpoint[1];
		QuicEndpoint[] clientHolder = new QuicEndpoint[1];
		QuicConnectionState[] clientState = new QuicConnectionState[1];
		QuicConnectionState[] serverState = new QuicConnectionState[1];
		boolean[] serverSawTheConnection = new boolean[1];

		Promise<QuicConnection> handshake = UdpSocket.connect(reactor, serverChannel)
			.then(serverSocket -> {
				QuicEndpoint server = QuicEndpoint.builder(reactor, serverSocket)
					.withSettings(QuicConnectionSettings.create())
					.withServerEngineFactory(QuicEndpointFixture.serverEngineFactory())
					.build();
				server.listen();
				serverHolder[0] = server;
				return UdpSocket.connect(reactor, clientChannel);
			})
			.then(clientSocket -> {
				QuicEndpoint client = QuicEndpoint.builder(reactor, clientSocket)
					.withSettings(QuicConnectionSettings.create())
					.build();
				clientHolder[0] = client;
				return client.connectTo(serverAddress, QuicEndpointFixture.clientEngineFactory());
			});

		Promise<Void> bothEstablishedThenClose = handshake
			.then(clientConnection -> {
				clientState[0] = clientConnection.state();
				QuicConnection serverConnection = serverHolder[0].connectionOf(clientConnection.peerConnectionId());
				serverSawTheConnection[0] = serverConnection != null;
				if (serverConnection == null) {
					return Promise.complete();
				}
				// The server's own TLS engine completes one flight later than the client's (see class
				// Javadoc) — wait for it explicitly rather than assuming it already happened.
				return serverConnection.whenEstablished()
					.whenResult(sc -> serverState[0] = sc.state())
					.toVoid();
			})
			.whenComplete(($, e) -> {
				// A real UdpSocket's receive loop stays armed forever (there is always another datagram
				// that might arrive), so the eventloop would never go idle and await() below would never
				// return without this — unlike LossyUdpSocket, closing is not optional bookkeeping here.
				if (clientHolder[0] != null) clientHolder[0].close();
				if (serverHolder[0] != null) serverHolder[0].close();
			});

		await(bothEstablishedThenClose);

		assertEquals(QuicConnectionState.ESTABLISHED, clientState[0]);
		assertTrue("the server never accepted a connection", serverSawTheConnection[0]);
		assertEquals(QuicConnectionState.ESTABLISHED, serverState[0]);
	}
}
