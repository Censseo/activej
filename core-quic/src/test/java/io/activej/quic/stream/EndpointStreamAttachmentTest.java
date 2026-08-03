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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T063 / SC-009 / FR-039 — a stream layer attached to an <b>accepted</b> connection through
 * {@link QuicEndpoint.Builder#withFrameHandlerFactory}, exactly as an application would attach one.
 * <p>
 * <b>Why the endpoint and not {@code QuicWirePair}.</b> An accepted server connection is built inside
 * {@code QuicEndpoint.acceptOrDrop}, so there is no moment between its construction and its first
 * packet at which a caller could reach it. The endpoint-level factory is the only way in, and it is
 * the way feature 05's {@code Http3Server} will use — which makes this the one test that proves the
 * capability rather than the mechanism. {@code QuicEndpointFrameHandlerFactoryTest} already covers the
 * seam at the raw {@link io.activej.quic.connection.QuicFrameHandler} level; here the handler is a real
 * {@link QuicStreamManager}.
 * <p>
 * <b>Black box.</b> Nothing below is reached into: no subclass, no package-private accessor, no
 * {@code connectionOf}. Every observation is made through the stream layer the factory returned —
 * a listener firing, bytes arriving — plus the endpoint's own public acceptance counters.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class EndpointStreamAttachmentTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** 16 KiB: several datagrams' worth, comfortably inside every default window. */
	private static final int PAYLOAD_SIZE = 16 * 1024;

	/** Bound on the drive loop; every exchange here settles in a small fraction of it. */
	private static final int MAX_DRIVE_ROUNDS = 400;

	/** Clock step per round. Small enough to land inside a delayed ACK rather than on top of one. */
	private static final long STEP_MILLIS = 5;

	private ManualEventloop loop;
	private QuicEndpointFixture fixture;

	// Everything the server side observed, in acceptance order.
	private final List<QuicConnection> acceptedConnections = new ArrayList<>();
	private final List<QuicStreamManager> serverManagers = new ArrayList<>();
	private final List<QuicStream> acceptedStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> acceptedReads = new ArrayList<>();
	private int serverFactoryCalls;

	// ... and the client side, in dial order.
	private final List<QuicStreamManager> clientManagers = new ArrayList<>();
	private final List<QuicStream> clientAcceptedStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> clientAcceptedReads = new ArrayList<>();
	private int clientFactoryCalls;

	/** Whether an accepted stream is echoed back to its opener once it has been read whole. */
	private boolean echoOnAccept;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new QuicEndpointFixture(loop, 1);
		acceptedConnections.clear();
		serverManagers.clear();
		acceptedStreams.clear();
		acceptedReads.clear();
		clientManagers.clear();
		clientAcceptedStreams.clear();
		clientAcceptedReads.clear();
		serverFactoryCalls = 0;
		clientFactoryCalls = 0;
		echoOnAccept = false;
	}

	@After
	public void tearDown() {
		fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- the wiring under test

	/**
	 * The server shape from {@code contracts/java-api.md}: one factory, one {@link QuicStreamManager}
	 * per accepted connection, one listener per peer-opened stream.
	 */
	private QuicEndpoint serverWithStreamLayer() {
		return fixture.server(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> {
				serverFactoryCalls++;
				acceptedConnections.add(connection);
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream -> {
						acceptedStreams.add(stream);
						Promise<ByteBuf> read = stream.reader().toCollector(ByteBufs.collector());
						acceptedReads.add(read);
						if (echoOnAccept) {
							// Ownership of the collected buffer passes to the supplier, which the writer drains.
							read.whenResult(buf -> ChannelSuppliers.ofValue(buf).streamTo(stream.writer()));
						}
					})
					.build();
				serverManagers.add(manager);
				return manager;
			}));
	}

	/** The client shape from the same contract, dialled and driven to establishment. */
	private QuicStreamManager connectClient() {
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> {
				clientFactoryCalls++;
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream -> {
						clientAcceptedStreams.add(stream);
						clientAcceptedReads.add(stream.reader().toCollector(ByteBufs.collector()));
					})
					.build();
				clientManagers.add(manager);
				return manager;
			}));
		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		driveUntil(connecting::isComplete);
		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		return clientManagers.get(clientManagers.size() - 1);
	}

	// ---------------------------------------------------------------- helpers

	/** A pattern whose every byte depends on its offset, so a reorder or a gap cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(source.length);
		buf.put(source);
		return buf;
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the read never completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	/**
	 * Delivers datagrams and lets timers fire until {@code done}, or fails.
	 * <p>
	 * Both halves are needed: {@code pump()} moves what has already been flushed, while the clock is
	 * what releases a delayed ACK — and it is an ACK that opens the congestion window for the next
	 * burst, so a pump-only loop stalls after the first window.
	 */
	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			fixture.pump();
			if (done.getAsBoolean()) return;
			fixture.advance(STEP_MILLIS);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static QuicStream openNow(Promise<QuicStream> opened) {
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		assertTrue(String.valueOf(opened.getException()), opened.isResult());
		return opened.getResult();
	}

	// ---------------------------------------------------------------- SC-009

	@Test
	public void aStreamOpenedByTheClientReachesTheAcceptedConnectionsListener() {
		QuicEndpoint server = serverWithStreamLayer();
		QuicStreamManager client = connectClient();

		byte[] payload = pattern(PAYLOAD_SIZE, 0);
		QuicStream clientStream = openNow(client.openBidirectional());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(clientStream.writer());

		driveUntil(() -> written.isComplete() && !acceptedReads.isEmpty() && acceptedReads.get(0).isComplete());

		// The listener that fired belongs to the connection the *endpoint* built, which nothing above the
		// endpoint could otherwise have reached. That is the whole of SC-009.
		assertEquals("the listener is invoked once per peer-opened stream", 1, acceptedStreams.size());
		QuicStream serverStream = acceptedStreams.get(0);
		assertEquals(clientStream.id(), serverStream.id());
		assertFalse(serverStream.isLocallyInitiated());
		assertTrue(serverStream.isBidirectional());
		assertArrayEquals(payload, drain(acceptedReads.get(0)));
		assertEquals(ReceiveState.DATA_READ, serverStream.receiveState());

		// The factory ran once, for one connection — not once per datagram, not once per packet.
		assertEquals(1, serverFactoryCalls);
		assertEquals(1, acceptedConnections.size());
		assertEquals(1, server.connectionsAccepted());
		assertEquals(0, server.connectionsRejected());
		assertEquals(PAYLOAD_SIZE, serverManagers.get(0).bytesDelivered());
		assertEquals(1, serverManagers.get(0).streamsAcceptedFromPeer());
	}

	@Test
	public void theAcceptedConnectionsManagerRepliesOnTheSameStream() {
		echoOnAccept = true;
		serverWithStreamLayer();
		QuicStreamManager client = connectClient();

		byte[] payload = pattern(8 * 1024, 1);
		QuicStream clientStream = openNow(client.openBidirectional());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(clientStream.writer());
		Promise<ByteBuf> echoed = clientStream.reader().toCollector(ByteBufs.collector());

		driveUntil(() -> written.isComplete() && echoed.isComplete());

		// An accepted connection's stream layer is a whole stream layer: it sends as well as it receives,
		// which is what makes an HTTP/3 *response* possible over one.
		assertArrayEquals(payload, drain(echoed));
		assertEquals(1, serverFactoryCalls);
	}

	// ---------------------------------------------------------------- exactly once per connection

	@Test
	public void theFactoryRunsExactlyOncePerAcceptedConnection() {
		QuicEndpoint server = serverWithStreamLayer();

		int clients = 3;
		List<byte[]> payloads = new ArrayList<>();
		List<Promise<Void>> writes = new ArrayList<>();
		for (int i = 0; i < clients; i++) {
			QuicStreamManager client = connectClient();
			byte[] payload = pattern(4 * 1024, i + 2);
			payloads.add(payload);
			QuicStream stream = openNow(client.openBidirectional());
			writes.add(ChannelSuppliers.ofValue(buf(payload)).streamTo(stream.writer()));
		}

		driveUntil(() -> acceptedReads.size() == clients &&
			acceptedReads.stream().allMatch(Promise::isComplete) &&
			writes.stream().allMatch(Promise::isComplete));

		assertEquals(clients, serverFactoryCalls);
		assertEquals(clients, clientFactoryCalls);
		assertEquals(clients, server.connectionsAccepted());
		assertEquals(0, server.connectionsRejected());

		// One manager per connection, never shared: a manager shared across connections would carry one
		// connection's stream ids, flow-control credit and counters into another's.
		assertEquals(clients, new HashSet<>(acceptedConnections).size());
		assertEquals(clients, new HashSet<>(serverManagers).size());
		assertEquals(clients, new HashSet<>(clientManagers).size());

		// Every connection carried exactly its own client's stream, and nobody else's bytes.
		assertEquals(clients, acceptedStreams.size());
		for (QuicStreamManager manager : serverManagers) {
			assertEquals(1, manager.streamsAcceptedFromPeer());
			assertEquals(4 * 1024, manager.bytesDelivered());
		}
		// Delivery order is the network's, not the dial order's, so the payloads are matched as a set —
		// each one consumed once, which is what rules out one client's bytes arriving twice.
		List<byte[]> unclaimed = new ArrayList<>(payloads);
		for (Promise<ByteBuf> read : acceptedReads) {
			byte[] received = drain(read);
			assertTrue("no unclaimed client payload matches what arrived",
				unclaimed.removeIf(expected -> Arrays.equals(expected, received)));
		}
		assertTrue("every client's payload arrived exactly once", unclaimed.isEmpty());
	}

	// ---------------------------------------------------------------- the dialled role

	@Test
	public void aDialledConnectionsListenerSeesAStreamTheServerOpened() {
		serverWithStreamLayer();
		connectClient();

		// The client had to open first: a server-initiated stream is what proves the *dialled* connection's
		// listener is wired, and the server's manager is only reachable here through the factory that built it.
		assertEquals(1, serverManagers.size());
		byte[] pushed = pattern(4 * 1024, 9);
		QuicStreamManager server = serverManagers.get(0);
		QuicStream serverStream = openNow(server.openUnidirectional());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(pushed)).streamTo(serverStream.writer());

		driveUntil(() -> written.isComplete() &&
			!clientAcceptedReads.isEmpty() && clientAcceptedReads.get(0).isComplete());

		assertEquals(1, clientAcceptedStreams.size());
		QuicStream received = clientAcceptedStreams.get(0);
		assertFalse(received.isLocallyInitiated());
		assertFalse(received.isBidirectional());
		assertFalse("a peer's unidirectional stream has no send part here", received.hasSendPart());
		assertArrayEquals(pushed, drain(clientAcceptedReads.get(0)));
		assertEquals(1, clientFactoryCalls);
	}
}
