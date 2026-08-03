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
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.*;
import io.activej.quic.connection.QuicConnection.PeerClose;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T064 / FR-039 — US6 scenario 2: an endpoint built <b>without</b>
 * {@link QuicEndpoint.Builder#withFrameHandlerFactory} behaves exactly as it did before this feature
 * existed.
 * <p>
 * FR-039 states the negative case as deliberately as the positive one, so it is asserted as
 * deliberately: the handshake still completes, the accepted connection carries no handler, and a peer
 * that sends a {@code STREAM} frame anyway is closed with {@code PROTOCOL_VIOLATION} — which is the
 * right answer, since an endpoint with no stream layer advertises no streams and the peer has
 * exceeded a limit it was given (RFC 9000 §4.6, §11.1).
 * <p>
 * The counters are checked too: a protocol violation by an established peer is <b>not</b> a refused
 * connection attempt, so {@code connectionsAccepted} moves and {@code connectionsRejected} does not.
 * Regressing that would make the endpoint's acceptance accounting lie about a feature that is not
 * even in use.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-11.1">RFC 9000 §11.1 — Connection Errors</a>
 */
public final class EndpointNoFactoryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;
	private static final long STEP_MILLIS = 5;

	private ManualEventloop loop;
	private QuicEndpointFixture fixture;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new QuicEndpointFixture(loop, 1);
	}

	@After
	public void tearDown() {
		fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			fixture.pump();
			if (done.getAsBoolean()) return;
			fixture.advance(STEP_MILLIS);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	// ---------------------------------------------------------------- an untouched endpoint

	@Test
	public void anEndpointWithNoFactoryStillCompletesAHandshakeAndWiresNothing() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create());
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create());

		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		driveUntil(connecting::isComplete);

		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		QuicConnection dialled = connecting.getResult();
		assertEquals(QuicConnectionState.ESTABLISHED, dialled.state());
		// CHK092: a connection with a handler advertises a layer above it that does not exist.
		assertFalse(dialled.hasFrameHandler());
		assertEquals(1, server.connectionsAccepted());
		assertEquals(0, server.connectionsRejected());
		assertEquals(0, server.datagramsDropped());
	}

	// ---------------------------------------------------------------- and an application frame

	@Test
	public void aStreamSentToAnEndpointWithNoFactoryIsAProtocolViolation() {
		QuicEndpoint server = fixture.server(QuicConnectionSettings.create());

		// Only the *client* carries a stream layer. The server's lack of one is what is under test, and a
		// real QuicStreamManager is what makes the STREAM frame a genuine one rather than a hand-built
		// probe — this is the shape a misconfigured deployment actually has.
		QuicStreamManager[] clientManager = new QuicStreamManager[1];
		QuicEndpoint client = fixture.client(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> clientManager[0] =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build()));

		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		driveUntil(connecting::isComplete);
		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		QuicConnection dialled = connecting.getResult();

		Promise<QuicStream> opened = clientManager[0].openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isResult());
		ByteBuf payload = ByteBufPool.allocate(4);
		payload.put(new byte[]{1, 2, 3, 4});
		Promise<Void> written = ChannelSuppliers.ofValue(payload).streamTo(opened.getResult().writer());

		driveUntil(() -> dialled.peerClose() != null);

		PeerClose peerClose = dialled.peerClose();
		assertNotNull("the server accepted a stream it never advertised", peerClose);
		assertFalse("a transport error, not an application one", peerClose.isApplication());
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, peerClose.errorCode());

		// The refusal is a connection error on an *established* connection, not a refused attempt: the
		// endpoint accepted this peer and only then found it non-conforming.
		assertEquals(1, server.connectionsAccepted());
		assertEquals(0, server.connectionsRejected());

		// The write is released rather than left hanging — a stalled writer would be the leak this rule
		// is easiest to regress into.
		driveUntil(written::isComplete);
		assertTrue("the client's write outlived the connection", written.isComplete());
	}
}
