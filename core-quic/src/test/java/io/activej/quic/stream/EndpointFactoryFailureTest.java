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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T065 / FR-039 — US6 scenario 3: a {@link QuicEndpoint.Builder#withFrameHandlerFactory} that throws
 * while accepting registers no partially-wired connection, increments the endpoint's refusal counter,
 * and leaves the endpoint serviceable for the next attempt.
 * <p>
 * A half-wired connection is worse than none: it would route application frames into a stream layer
 * that never finished being built, and the peer would see a connection that answers its handshake and
 * then nothing. So the endpoint refuses, exactly as it refuses an over-limit attempt, and reuses the
 * counter that already names refusals rather than adding one nobody watches.
 * <p>
 * {@code QuicEndpointFrameHandlerFactoryTest} already proves this at the raw
 * {@link io.activej.quic.connection.QuicFrameHandler} level. What it cannot show is the part that
 * matters to a server: that the endpoint which refused one connection goes on to accept the next one
 * <b>with a working stream layer</b> — a broken factory must cost one connection, not the process.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.2">RFC 9000 §5.2 — Matching Packets to Connections</a>
 */
public final class EndpointFactoryFailureTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;
	private static final long STEP_MILLIS = 5;

	private ManualEventloop loop;
	private QuicEndpointFixture fixture;

	/** Flipped to {@code false} once the factory should start succeeding. */
	private boolean factoryFails = true;
	private int factoryCalls;

	private final List<QuicStream> acceptedStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> acceptedReads = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new QuicEndpointFixture(loop, 1);
		factoryFails = true;
		factoryCalls = 0;
		acceptedStreams.clear();
		acceptedReads.clear();
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

	/**
	 * A server whose stream layer refuses to be built while {@link #factoryFails} is set.
	 * <p>
	 * The manager is built <i>before</i> the throw on the failing path, so the refusal happens with a
	 * fully-constructed stream layer in hand that is then dropped — the case in which a leak or a
	 * dangling registration would actually show up.
	 */
	private QuicEndpoint serverWithFailingStreamLayer() {
		return fixture.server(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> {
				factoryCalls++;
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream -> {
						acceptedStreams.add(stream);
						acceptedReads.add(stream.reader().toCollector(ByteBufs.collector()));
					})
					.build();
				if (factoryFails) throw new IllegalStateException("the stream layer refused to be built");
				return manager;
			}));
	}

	/**
	 * A client whose handshake deadline is short enough that a refused attempt reaches its <b>own</b>
	 * failure well inside {@link #MAX_DRIVE_ROUNDS} — see
	 * {@link #aThrowingFactoryRegistersNothingAndCountsOneRefusal()} for why that has to be assertable.
	 */
	private QuicEndpoint clientEndpoint() {
		return fixture.client(QuicConnectionSettings.builder()
			.withHandshakeTimeout(Duration.ofMillis(200))
			.build());
	}

	private QuicEndpoint clientEndpointWithStreamLayer(QuicStreamManager[] out) {
		return fixture.client(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> out[0] =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build()));
	}

	private static ByteBuf buf(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(source.length);
		buf.put(source);
		return buf;
	}

	// ---------------------------------------------------------------- the refusal

	@Test
	public void aThrowingFactoryRegistersNothingAndCountsOneRefusal() {
		QuicEndpoint server = serverWithFailingStreamLayer();
		QuicEndpoint client = clientEndpoint();

		Promise<QuicConnection> refused = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		// Deliberately no clock advance: the client's Initial is delivered once and never retransmitted,
		// so the refusal count below is exactly the number of accept attempts made.
		fixture.pump();

		assertEquals("the factory ran for the attempt", 1, factoryCalls);
		assertFalse("a half-wired connection was kept: " + refused, refused.isResult());
		assertEquals(0, server.connectionCount());
		assertEquals(0, server.connectionsAccepted());
		assertEquals(1, server.connectionsRejected());
		assertEquals("a refused connection must leave no routing entry", 0, server.routingEntryCount());
		assertEquals("nothing was accepted, so no stream layer ever saw a stream", 0, acceptedStreams.size());

		// Every assertion above is satisfied by a client that simply hangs forever, which is a different
		// bug wearing the same shape. So the attempt must also *end*, and end inside a bounded number of
		// rounds: the server's silent drop is answered by the client's own handshake deadline, and the
		// promise fails rather than being abandoned pending.
		driveUntil(refused::isComplete);
		assertTrue("the refused attempt must reach a failure, not hang: " + refused, refused.isException());
		assertEquals("still nothing accepted once the attempt has run its course", 0, acceptedStreams.size());
		assertEquals(0, server.connectionCount());
		assertEquals(0, server.connectionsAccepted());
	}

	// ---------------------------------------------------------------- and the endpoint lives on

	@Test
	public void theEndpointStillAcceptsAWorkingStreamLayerAfterAFailedFactory() {
		QuicEndpoint server = serverWithFailingStreamLayer();

		QuicEndpoint refusedClient = clientEndpoint();
		Promise<QuicConnection> refused = refusedClient.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		fixture.pump();
		assertFalse(refused.isResult());
		assertEquals(1, server.connectionsRejected());

		// A client that gave up closes, and closing sends a CONNECTION_CLOSE in a padded Initial — which
		// is one more connection attempt as far as this endpoint can tell, refused for the same reason.
		// It is drained here, before the clock moves at all, so the counters below measure the *second*
		// connection rather than the first one's tail.
		refusedClient.close();
		fixture.pump();
		int callsBeforeSecond = factoryCalls;
		long rejectedBeforeSecond = server.connectionsRejected();

		factoryFails = false;
		QuicStreamManager[] clientManager = new QuicStreamManager[1];
		QuicEndpoint client = clientEndpointWithStreamLayer(clientManager);
		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		driveUntil(connecting::isComplete);
		assertTrue("the endpoint stopped accepting: " + connecting, connecting.isResult());

		byte[] payload = "the endpoint outlived its broken factory".getBytes(StandardCharsets.US_ASCII);
		Promise<QuicStream> opened = clientManager[0].openBidirectional();
		assertTrue(String.valueOf(opened.getException()), opened.isResult());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(opened.getResult().writer());

		driveUntil(() -> written.isComplete() && !acceptedReads.isEmpty() && acceptedReads.get(0).isComplete());

		// Serviceable means serviceable all the way up: the second connection carries a stream layer that
		// delivers bytes, not merely a handshake that completed.
		assertEquals(1, acceptedStreams.size());
		Promise<ByteBuf> read = acceptedReads.get(0);
		assertTrue(String.valueOf(read.getException()), read.isResult());
		ByteBuf received = read.getResult();
		try {
			assertArrayEquals(payload, received.getArray());
		} finally {
			received.recycle();
		}

		assertEquals("the second attempt cost exactly one factory call", callsBeforeSecond + 1, factoryCalls);
		assertEquals(1, server.connectionsAccepted());
		assertEquals(1, server.connectionCount());
		assertEquals("an accepted connection must not touch the refusal counter",
			rejectedBeforeSecond, server.connectionsRejected());
	}
}
