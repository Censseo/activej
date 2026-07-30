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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.promise.Promise;
import io.activej.quic.codec.CryptoFrame;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * T077 / SC-010 — hostile input stops at a documented limit with documented behaviour, and the FR-031a
 * leniency classification holds end to end.
 * <p>
 * Three outcomes, and which one applies is the point of every test here:
 * <ul>
 *   <li><b>tolerated</b> — processed and ignored, no state, no error;</li>
 *   <li><b>silently dropped</b> — no state created, no buffer leaked, no answer sent;</li>
 *   <li><b>rejected</b> — the connection closes with a specified error code.</li>
 * </ul>
 * Getting these confused is how a parser becomes a denial-of-service surface: an input that should be
 * dropped but closes the connection hands any observer a kill switch, and one that should be rejected but
 * is ignored leaves a peer waiting on a promise that will never be kept.
 */
public final class QuicHostileInputTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ManualEventloop loop;
	private QuicWirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	private static ByteBuf bytes(byte... content) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, content.length));
		buf.put(content);
		return buf;
	}

	// ---------------------------------------------------------------- silently dropped

	@Test
	public void randomGarbageIsDroppedWithoutCreatingStateOrClosing() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection server = wire.server();
		long droppedBefore = server.packetsDropped();
		int sentBefore = wire.serverWire().datagramsAccepted();

		Random random = new Random(1);
		for (int i = 0; i < 50; i++) {
			byte[] garbage = new byte[1 + random.nextInt(1400)];
			random.nextBytes(garbage);
			server.onDatagram(bytes(garbage));
		}

		// RFC 9000 §5.2: a datagram that cannot be attributed is dropped, never answered — answering would
		// make this a reflector, and closing would make it a kill switch.
		assertEquals(QuicConnectionState.ESTABLISHED, server.state());
		assertEquals("a dropped datagram must not be answered", sentBefore,
			wire.serverWire().datagramsAccepted());
		assertTrue(server.packetsDropped() > droppedBefore);
	}

	@Test
	public void aPacketThatFailsAeadIsDroppedRatherThanRejected() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection client = wire.client();
		long droppedBefore = client.packetsDropped();

		// A well-formed Initial protected with keys derived from the wrong connection ID: located
		// correctly, authenticated never.
		ByteBuf forged = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.INITIAL, 0, 0, List.of(PingFrame.INSTANCE)),
			QuicKeys.initial(client.localConnectionId()).server(),
			client.localConnectionId(), client.peerConnectionId(), new byte[0],
			QuicPackets.SUPPORTED_VERSION, 64);
		client.onDatagram(forged);

		// FR-011: one packet failing AEAD says nothing about the connection. Only the RFC 9001 §6.6
		// integrity limit escalates, and it is 2^52 packets away.
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertTrue(client.packetsDropped() > droppedBefore);
	}

	@Test
	public void repeatedInitialsFromSpoofedAddressesStopAtTheHandshakingBound() {
		try (ManualEventloop endpointLoop = new ManualEventloop();
			 QuicEndpointFixture fixture = new QuicEndpointFixture(endpointLoop, 3)) {
			QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
				builder -> builder.withMaxHandshakingConnections(2));

			// Each client endpoint is a distinct source address, which is exactly what an attacker with a
			// spoofed source range looks like to a server.
			for (int i = 0; i < 6; i++) {
				QuicEndpoint client = fixture.client(QuicConnectionSettings.create());
				client.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
			}
			fixture.network().deliverDue();

			// SI-3: at the bound the server drops rather than queues. A queue of unvalidated peers is the
			// resource the attacker was after; each admitted one costs a TLS engine and a key schedule.
			assertTrue("the handshaking bound was exceeded: " + server.handshakingConnectionCount(),
				server.handshakingConnectionCount() <= 2);
			assertTrue("nothing was refused, so the bound was never reached", server.connectionsRejected() > 0);
		}
	}

	@Test
	public void aTinyInitialDatagramIsDroppedBeforeItCanBeAmplified() {
		try (ManualEventloop endpointLoop = new ManualEventloop();
			 QuicEndpointFixture fixture = new QuicEndpointFixture(endpointLoop, 4)) {
			QuicEndpoint server = fixture.server(QuicConnectionSettings.create());
			long droppedBefore = server.datagramsDropped();

			// A 64-byte "Initial". RFC 9000 §14.1 requires 1200, and the check exists so the 3×
			// anti-amplification budget is never computed off a tiny input.
			ByteBuf tiny = ByteBufPool.allocate(64);
			tiny.tail(64);
			tiny.array()[tiny.head()] = (byte) 0xC0;
			fixture.network().send(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.SERVER_ADDRESS, tiny);
			fixture.network().deliverDue();

			assertEquals(0, server.connectionCount());
			assertTrue(server.datagramsDropped() > droppedBefore);
		}
	}

	// ---------------------------------------------------------------- rejected

	@Test
	public void aPermanentCryptoGapStopsAtTheBufferBoundAndCloses() throws Exception {
		// A peer that sends CRYPTO at a far offset and never fills the gap would otherwise buy unbounded
		// memory with one packet — this is the bound that stops it (FR-040, CRYPTO_BUFFER_EXCEEDED).
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxCryptoBufferBytes(MemSize.kilobytes(4))
			.build();
		CryptoStreamAssembler assembler = new CryptoStreamAssembler(settings.maxCryptoBufferBytes());
		try {
			// Byte 0 is never sent, so nothing can ever be delivered and everything stays buffered. The
			// bound is on what is *held*, not on how far ahead an offset points — a single far-offset frame
			// costs only its own length, so the attack is many gapped frames rather than one distant one.
			QuicTransportException e = assertThrows(QuicTransportException.class, () -> {
				for (int i = 1; i < 200; i++) {
					ByteBuf chunk = ByteBufPool.allocate(64);
					chunk.tail(64);
					assembler.add(i * 128L, chunk);
				}
			});
			assertEquals(QuicTransportErrors.CRYPTO_BUFFER_EXCEEDED, e.errorCode());
			assertEquals("nothing should have been delivered past the permanent gap at offset 0",
				0, assembler.readOffset());
		} finally {
			assembler.close();
		}
	}

	@Test
	public void aSendQueueDrivenPastItsBoundIsRejectedRatherThanGrown() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxDatagramSize(MemSize.bytes(1200))
			.withMaxSendQueueBytes(MemSize.kilobytes(4))
			.build();
		SendQueue queue = new SendQueue(settings.maxSendQueueBytes());
		try {
			QuicTransportException e = assertThrows(QuicTransportException.class, () -> {
				for (int i = 0; i < 100; i++) {
					ByteBuf payload = ByteBufPool.allocate(512);
					payload.tail(512);
					queue.enqueue(EncryptionLevel.ONE_RTT, new CryptoFrame(i * 512L, payload), false);
				}
			});
			// FR-040: back-pressure as a typed error rather than an OutOfMemoryError. The rejected frame is
			// recycled before the throw, which ByteBufRule is what verifies.
			assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		} finally {
			queue.drop();
		}
	}

	@Test
	public void aFrameNoPeerInThatRoleMaySendIsAConnectionError() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection client = wire.client();
		QuicConnection server = wire.server();

		// Only a server may send HANDSHAKE_DONE (RFC 9000 §19.20). A client sending one is a protocol
		// error — and it is an *error* rather than noise precisely because it arrived authenticated: past
		// AEAD the bytes are provably the peer's, which is the line between a rejected input and a dropped
		// one. Accepting it would let a client confirm its own handshake.
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 99, 0,
				List.of(io.activej.quic.codec.HandshakeDoneFrame.INSTANCE)),
			clientSendKeysOf(client), server.localConnectionId(), client.localConnectionId(),
			new byte[0], QuicPackets.SUPPORTED_VERSION, 64);
		server.onDatagram(packet);

		assertTrue("expected the server to reject the frame, it is " + server.state(),
			server.state().isTerminating());
		wire.pump();
		assertNotNull(client.peerClose());
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, client.peerClose().errorCode());
	}

	/** The client's 1-RTT send keys, so a packet the server will actually authenticate can be built. */
	private static QuicKeys clientSendKeysOf(QuicConnection client) {
		QuicKeys keys = client.sendKeysForTesting(EncryptionLevel.ONE_RTT);
		assertNotNull("the client has no 1-RTT keys", keys);
		return keys;
	}

	/** The server's 1-RTT send keys, so a packet the client will actually authenticate can be built. */
	private static QuicKeys serverSendKeysOf(QuicConnection server) {
		QuicKeys keys = server.sendKeysForTesting(EncryptionLevel.ONE_RTT);
		assertNotNull("the server has no 1-RTT keys", keys);
		return keys;
	}

	// ---------------------------------------------------------------- tolerated

	@Test
	public void aDuplicatePacketIsProcessedOnceAndToleratedTwice() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection client = wire.client();
		Promise<QuicConnection> established = client.whenEstablished();

		// A replay is normal on any real path (RFC 9000 §12.5), so it must neither be an error nor be
		// processed twice — the second is what FR-010's de-duplication exists for.
		ByteBuf ping = PacketAssembler.assemblePacket(
			new PacketAssembler.PacketPlan(EncryptionLevel.ONE_RTT, 77, 0, List.of(PingFrame.INSTANCE)),
			serverSendKeysOf(wire.server()), client.localConnectionId(), wire.server().localConnectionId(),
			new byte[0], QuicPackets.SUPPORTED_VERSION, 64);
		ByteBuf replay = ping.slice();
		client.onDatagram(ping);
		client.onDatagram(replay);

		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertTrue(established.isResult());
	}
}
