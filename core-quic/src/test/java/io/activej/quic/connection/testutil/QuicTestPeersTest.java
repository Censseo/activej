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
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.net.socket.udp.UdpPacket;
import io.activej.promise.Promise;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Proves the T009/T010 harness assets work before Phases 3-7 depend on them: the {@link LossyUdpSocket}
 * seam, the certificate fixtures, and the hand-driven clock.
 */
public class QuicTestPeersTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static ByteBuf buf(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf b = ByteBufPool.allocate(bytes.length);
		b.put(bytes);
		return b;
	}

	@Test
	public void datagramTravelsClientToServer() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			Promise<UdpPacket> received = peers.serverSocket().receive();
			assertFalse(received.isComplete());

			peers.clientSocket().send(UdpPacket.of(buf("hello"), peers.serverAddress()));
			// Nothing is delivered synchronously.
			assertFalse(received.isComplete());

			peers.pump();
			assertTrue(received.isComplete());

			UdpPacket packet = received.getResult();
			assertEquals("hello", packet.getBuf().getString(StandardCharsets.US_ASCII));
			// The address on a received packet is the SOURCE — what server dispatch keys on.
			assertEquals(peers.clientAddress(), packet.getSocketAddress());
			packet.recycle();
		}
	}

	@Test
	public void datagramArrivingBeforeReceiveIsBuffered() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.clientSocket().send(UdpPacket.of(buf("early"), peers.serverAddress()));
			peers.pump();
			assertEquals(1, peers.serverSocket().inboxSize());

			Promise<UdpPacket> received = peers.serverSocket().receive();
			assertTrue(received.isComplete());
			UdpPacket packet = received.getResult();
			assertEquals("early", packet.getBuf().getString(StandardCharsets.US_ASCII));
			packet.recycle();
			assertEquals(0, peers.serverSocket().inboxSize());
		}
	}

	@Test
	public void concurrentReceiveIsRejected() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.serverSocket().receive();
			// UdpSocket has one pending read slot; a double receive must not silently work here.
			assertThrows(IllegalStateException.class, () -> peers.serverSocket().receive());
		}
	}

	@Test
	public void clockDrivesDelivery() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.network().withDelay(100);
			Promise<UdpPacket> received = peers.serverSocket().receive();

			peers.clientSocket().send(UdpPacket.of(buf("delayed"), peers.serverAddress()));
			peers.advance(99);
			assertFalse(received.isComplete());

			peers.advance(1);
			assertTrue(received.isComplete());
			received.getResult().recycle();
		}
	}

	@Test
	public void timeMustNotGoBackwards() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.advance(100);
			long now = peers.currentTimeMillis();
			assertThrows(IllegalArgumentException.class, () -> peers.setTime(now - 1));
		}
	}

	@Test
	public void droppedDatagramsNeverArriveAndDoNotLeak() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.network().withDropRate(1.0);
			Promise<UdpPacket> received = peers.serverSocket().receive();

			for (int i = 0; i < 50; i++) {
				peers.clientSocket().send(UdpPacket.of(buf("lost-" + i), peers.serverAddress()));
			}
			peers.pump();

			assertFalse(received.isComplete());
			assertEquals(50, peers.network().droppedCount());
		}
	}

	@Test
	public void closingASocketFailsAPendingReceive() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			Promise<UdpPacket> received = peers.serverSocket().receive();
			peers.serverSocket().close();

			assertTrue(received.isComplete());
			assertTrue(received.getException() instanceof AsyncCloseException);
			assertTrue(peers.serverSocket().isClosed());
		}
	}

	@Test
	public void sendOnAClosedSocketRecyclesAndFails() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			peers.clientSocket().close();
			Promise<Void> sent = peers.clientSocket().send(UdpPacket.of(buf("nope"), peers.serverAddress()));
			assertTrue(sent.isComplete());
			assertTrue(sent.getException() instanceof AsyncCloseException);
		}
	}

	@Test
	public void closingASocketDiscardsItsUnreadInbox() {
		try (QuicTestPeers peers = QuicTestPeers.loopback()) {
			for (int i = 0; i < 10; i++) {
				peers.clientSocket().send(UdpPacket.of(buf("unread-" + i), peers.serverAddress()));
			}
			peers.pump();
			assertEquals(10, peers.serverSocket().inboxSize());

			peers.serverSocket().close();
			assertEquals(0, peers.serverSocket().inboxSize());
			// Idempotent.
			peers.serverSocket().close();
		}
	}

	@Test
	public void closeIsIdempotentAcrossTheWholeFixture() {
		QuicTestPeers peers = QuicTestPeers.loopback();
		peers.close();
		peers.close();
	}

	@Test
	public void devCertificatesLoad() {
		TlsServerIdentity ecdsa = QuicTestPeers.devIdentity();
		assertEquals(1, ecdsa.chain().length);
		assertEquals("CN=localhost", ecdsa.leaf().getSubjectX500Principal().getName());
		assertEquals("EC", ecdsa.leaf().getPublicKey().getAlgorithm());
		// Cached: the same immutable identity is reused.
		assertSame(ecdsa, QuicTestPeers.devIdentity());

		TlsServerIdentity rsa = QuicTestPeers.devRsaIdentity();
		assertEquals("RSA", rsa.leaf().getPublicKey().getAlgorithm());

		TlsServerIdentity fromKeystore = QuicTestPeers.devKeystoreIdentity();
		assertEquals("CN=localhost", fromKeystore.leaf().getSubjectX500Principal().getName());
	}

	@Test
	public void settableClockStartsWhereAsked() {
		try (QuicTestPeers peers = QuicTestPeers.loopback(5_000, 7)) {
			assertEquals(5_000, peers.currentTimeMillis());
			assertEquals(5_000, peers.clock().currentTimeMillis());
			peers.advance(250);
			assertEquals(5_250, peers.clock().currentTimeMillis());
		}
	}
}
