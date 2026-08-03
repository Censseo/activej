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
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T018 / FR-039 — {@link QuicEndpoint.Builder#withFrameHandlerFactory}: the endpoint-level seam that
 * reaches connections nothing else can.
 * <p>
 * An <b>accepted</b> connection is built inside {@code acceptOrDrop}, so there is no moment between its
 * construction and its first packet in which a caller could attach a handler to it — exactly the gap
 * {@code withConnectionInspector} already exists to close for diagnostics. Without this, a server could
 * never carry a layer above the transport.
 * <p>
 * The negative case is asserted just as deliberately: an endpoint built without a factory must behave
 * byte-for-byte as it did before this method existed.
 */
public final class QuicEndpointFrameHandlerFactoryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Records the frames routed to it; every frame here is borrowed, so nothing is retained. */
	private static final class RecordingHandler implements QuicFrameHandler {
		private final List<String> received = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			received.add(frame.getClass().getSimpleName());
		}
	}

	private QuicEndpointFixture fixture;

	@Before
	public void setUp() {
		fixture = new QuicEndpointFixture();
	}

	@After
	public void tearDown() {
		fixture.close();
	}

	private Promise<QuicConnection> connect(QuicEndpoint client) {
		return client.connectTo(QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
	}

	private QuicEndpoint client() {
		return fixture.client(QuicConnectionSettings.create());
	}

	// ---------------------------------------------------------------- both roles

	@Test
	public void theFactoryIsAppliedToAnAcceptedConnection() {
		List<QuicConnection> built = new ArrayList<>();
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> {
				built.add(connection);
				return new RecordingHandler();
			}));

		Promise<QuicConnection> connecting = connect(client());
		fixture.pump();

		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		assertEquals("one accepted connection, one factory call", 1, built.size());
		// The factory saw the *server* side, which is the one nothing above the endpoint can otherwise
		// reach — the client's own connection came from connectTo and was never in this endpoint.
		assertSame(server.connectionOf(connecting.getResult().peerConnectionId()), built.get(0));
		assertTrue(built.get(0).hasFrameHandler());
	}

	@Test
	public void theFactoryIsAppliedToADialledConnection() {
		fixture.server(QuicConnectionSettings.create());
		List<QuicConnection> built = new ArrayList<>();
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> {
				built.add(connection);
				return new RecordingHandler();
			}));

		Promise<QuicConnection> connecting = connect(client);
		// connectTo builds its connection synchronously, so the factory has already run — before the
		// first Initial goes out, which is what "before any frame is routed" has to mean on a dialled
		// connection.
		assertEquals(1, built.size());

		fixture.pump();

		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		assertSame(connecting.getResult(), built.get(0));
		assertTrue(connecting.getResult().hasFrameHandler());
	}

	@Test
	public void everyConnectionGetsItsOwnHandlerExactlyOnce() {
		List<QuicConnection> built = new ArrayList<>();
		List<QuicFrameHandler> handlers = new ArrayList<>();
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> {
				built.add(connection);
				RecordingHandler handler = new RecordingHandler();
				handlers.add(handler);
				return handler;
			}));

		List<Promise<QuicConnection>> connecting = List.of(connect(client()), connect(client()), connect(client()));
		fixture.pump();

		for (Promise<QuicConnection> promise : connecting) {
			assertTrue("the handshake did not complete: " + promise, promise.isResult());
		}
		assertEquals(3, server.connectionCount());
		// Three calls, three distinct connections, three distinct handlers: a handler shared between
		// connections would be a per-connection stream layer with cross-connection state.
		assertEquals(3, built.size());
		assertEquals(3, new java.util.HashSet<>(built).size());
		assertEquals(3, new java.util.HashSet<>(handlers).size());
	}

	@Test
	public void aFactoryRegisteredHandlerIsWiredIntoTheRoutingPath() throws Exception {
		RecordingHandler serverHandler = new RecordingHandler();
		fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> serverHandler));
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> new RecordingHandler()));

		Promise<QuicConnection> connecting = connect(client);
		fixture.pump();
		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());

		ByteBuf data = ByteBufPool.allocate(4);
		data.put(new byte[]{1, 2, 3, 4});
		connecting.getResult().enqueueFrame(new StreamFrame(0, 0, true, data));
		connecting.getResult().requestSend();
		fixture.pump();

		// Registered, not merely remembered: the handler the factory returned is the one the accepted
		// connection routes application frames to.
		assertEquals(List.of("StreamFrame"), serverHandler.received);
	}

	// ---------------------------------------------------------------- FR-039: unused means unchanged

	@Test
	public void withoutAFactoryTheEndpointBehavesExactlyAsBefore() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create());
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());

		Promise<QuicConnection> connecting = connect(client);
		fixture.pump();

		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		QuicConnection dialled = connecting.getResult();
		QuicConnection accepted = server.connectionOf(dialled.peerConnectionId());

		// CHK092: the new builder method must not hand a connection a handler nobody asked for — a
		// connection with one advertises a layer above it that does not exist.
		assertFalse(dialled.hasFrameHandler());
		assertNotNull(accepted);
		assertFalse(accepted.hasFrameHandler());
		assertEquals(QuicConnectionState.ESTABLISHED, dialled.state());
		assertEquals(QuicConnectionState.ESTABLISHED, accepted.state());
		assertEquals(1, server.connectionCount());
		assertEquals(1, server.connectionsAccepted());
		assertEquals(0, server.connectionsRejected());
		assertEquals(0, server.datagramsDropped());
	}

	// ---------------------------------------------------------------- a failing factory

	@Test
	public void aFactoryThatThrowsWhileAcceptingRegistersNothingAndLeavesTheEndpointServiceable() {
		boolean[] fail = {true};
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> {
				if (fail[0]) throw new IllegalStateException("the layer above refused to be built");
				return new RecordingHandler();
			}));

		Promise<QuicConnection> refused = connect(client());
		fixture.pump();

		// Dropped exactly as an over-limit attempt is: nothing registered, nothing routed, and the
		// counter that already names refusals records it rather than a new one nobody watches.
		assertFalse("a half-wired connection was kept: " + refused, refused.isResult());
		assertEquals(0, server.connectionCount());
		assertEquals(0, server.connectionsAccepted());
		assertEquals(1, server.connectionsRejected());
		assertEquals(0, server.routingEntryCount());

		// And the endpoint is still an endpoint: a broken factory refuses connections, it does not kill
		// the receive loop or poison the dispatch table.
		fail[0] = false;
		Promise<QuicConnection> accepted = connect(client());
		fixture.pump();

		assertTrue("the endpoint stopped accepting: " + accepted, accepted.isResult());
		assertEquals(1, server.connectionCount());
		assertEquals(1, server.connectionsAccepted());
		assertEquals(1, server.connectionsRejected());
		assertTrue(server.connectionOf(accepted.getResult().peerConnectionId()).hasFrameHandler());
	}

	@Test
	public void aFactoryThatThrowsWhileDiallingFailsTheConnectToPromise() {
		fixture.server(QuicConnectionSettings.create());
		IllegalStateException failure = new IllegalStateException("the layer above refused to be built");
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create(),
			builder -> builder.withFrameHandlerFactory(connection -> {
				throw failure;
			}));

		Promise<QuicConnection> connecting = connect(client);

		// A failed dial is the caller's to see: connectTo already reports its refusals through the
		// promise, and throwing out of it instead would make one failure mode look unlike the others.
		assertTrue("the failure was swallowed: " + connecting, connecting.isException());
		assertSame(failure, connecting.getException());
		assertEquals(0, client.connectionCount());
		assertEquals(0, client.routingEntryCount());
	}
}
