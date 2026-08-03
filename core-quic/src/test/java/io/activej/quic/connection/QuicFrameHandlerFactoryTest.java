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

import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicTestPeers;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T016 / FR-038 — {@link QuicConnection.Builder#withFrameHandlerFactory}: the factory form of handler
 * registration, which exists because a handler that must reach its connection <i>outside</i> a callback
 * (a stream manager writing from a CSP consumer, say) cannot be built before the connection it holds.
 * <p>
 * What is asserted here is the timing contract rather than the wiring: the factory runs exactly once,
 * on a connection whose fields are all assigned, and a factory that fails leaves nothing half-registered.
 */
public final class QuicFrameHandlerFactoryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Does nothing; these tests never deliver a frame, only observe registration. */
	private static final class NoopHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			// Registration is all these tests observe; no frame is ever delivered to it.
		}
	}

	private ManualEventloop loop;
	private QuicWirePair.Wire wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair.Wire();
	}

	@After
	public void tearDown() {
		// The connections built here are never started, so nothing should have reached the wire; draining
		// it anyway keeps a future change from turning into a ByteBufRule failure with no explanation.
		wire.drain();
		loop.close();
	}

	/**
	 * A client builder that is complete but unstarted: {@code build()} runs the whole constructor — keys,
	 * number spaces, the TLS engine — without arming a timer or touching the wire.
	 */
	private QuicConnection.Builder clientBuilder() {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		return QuicConnection.builder(Reactor.getCurrentReactor(), Role.CLIENT, wire, QuicWirePair.SERVER_ADDRESS,
			params -> QuicTls.clientEngine(TlsClientConfig.builder("localhost", params)
				.withTrustManager(QuicTestPeers.trustingLeaf(identity.leaf()))
				.build()));
	}

	// ---------------------------------------------------------------- the factory contract

	@Test
	public void theFactoryRunsExactlyOnceAndReceivesTheConnectionBeingBuilt() {
		List<QuicConnection> seen = new ArrayList<>();
		QuicFrameHandler handler = new NoopHandler();

		QuicConnection connection = clientBuilder()
			.withFrameHandlerFactory(built -> {
				seen.add(built);
				return handler;
			})
			.build();

		assertEquals("the factory must run exactly once per connection", 1, seen.size());
		assertSame("the factory must receive the connection being built", connection, seen.get(0));
		assertTrue(connection.hasFrameHandler());
	}

	@Test
	public void theConnectionHandedToTheFactoryIsFullyConstructed() {
		// The whole point of running the factory in doBuild() rather than inside the constructor: every
		// field a handler could touch is already assigned, so the factory may call back into the
		// connection from its own body. A `this` escaping mid-construction would read nulls here.
		List<QuicConnectionId> idsSeenByTheFactory = new ArrayList<>();
		List<QuicConnectionState> statesSeenByTheFactory = new ArrayList<>();

		QuicConnection connection = clientBuilder()
			.withFrameHandlerFactory(built -> {
				idsSeenByTheFactory.add(built.localConnectionId());
				statesSeenByTheFactory.add(built.state());
				assertEquals(Role.CLIENT, built.role());
				assertEquals(QuicWirePair.SERVER_ADDRESS, built.remoteAddress());
				return new NoopHandler();
			})
			.build();

		assertEquals(List.of(connection.localConnectionId()), idsSeenByTheFactory);
		assertEquals(List.of(QuicConnectionState.IDLE), statesSeenByTheFactory);
	}

	@Test
	public void aConnectionBuiltWithoutEitherFormHasNoHandler() {
		// FR-039's negative case at connection scope: adding the factory must not give a connection a
		// handler it was never asked for.
		assertFalse(clientBuilder().build().hasFrameHandler());
	}

	@Test
	public void theDirectFormStillWorksUnchanged() {
		assertTrue(clientBuilder().withFrameHandler(new NoopHandler()).build().hasFrameHandler());
	}

	// ---------------------------------------------------------------- mutual exclusivity

	@Test
	public void settingBothFormsIsRejectedAtBuildTime() {
		QuicConnection.Builder builder = clientBuilder()
			.withFrameHandler(new NoopHandler())
			.withFrameHandlerFactory(built -> new NoopHandler());

		// At build() rather than at the second withXxx: a builder is order-independent everywhere else in
		// the platform, and rejecting in whichever setter happened to run second would make the failure
		// depend on the order the two lines were written in.
		IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
		assertTrue("the message must name both methods: " + e.getMessage(),
			e.getMessage().contains("withFrameHandler") && e.getMessage().contains("withFrameHandlerFactory"));
	}

	@Test
	public void settingBothFormsIsRejectedInEitherOrder() {
		QuicConnection.Builder builder = clientBuilder()
			.withFrameHandlerFactory(built -> new NoopHandler())
			.withFrameHandler(new NoopHandler());

		assertThrows(IllegalStateException.class, builder::build);
	}

	// ---------------------------------------------------------------- a failing factory

	@Test
	public void aThrowingFactoryPropagatesOutOfBuildAndRegistersNothing() {
		List<QuicConnection> seen = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("no handler for you");

		QuicConnection.Builder builder = clientBuilder()
			.withFrameHandlerFactory(built -> {
				seen.add(built);
				throw failure;
			});

		assertSame(failure, assertThrows(IllegalStateException.class, builder::build));

		// The connection object exists — the constructor had already returned — but nothing was
		// registered on it, and it is unreachable to everyone but this test. Half-wiring it would leave a
		// connection routing frames into a handler that never finished being built.
		assertEquals(1, seen.size());
		assertFalse("a failed factory must leave no handler behind", seen.get(0).hasFrameHandler());
	}

	@Test
	public void aFactoryReturningNullLeavesNoHandler() {
		// Not an error: a factory that decides this particular connection needs no layer above it is
		// indistinguishable from never having called withFrameHandlerFactory at all.
		QuicConnection connection = clientBuilder()
			.withFrameHandlerFactory(built -> null)
			.build();

		assertFalse(connection.hasFrameHandler());
	}

	// ---------------------------------------------------------------- the handler can hold its connection

	@Test
	public void theHandlerCanCaptureTheConnectionItWasBuiltFor() {
		// The reason the seam exists (FR-038): `frameHandler` is final and every QuicFrameHandler method
		// takes the connection as a parameter, so before this a handler could reach its connection only
		// from inside a callback — which a stream layer's write path is not.
		class CapturingHandler implements QuicFrameHandler {
			private final QuicConnection connection;

			CapturingHandler(QuicConnection connection) {
				this.connection = connection;
			}

			@Override
			public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
				// What matters here is the captured field above, not what a frame would do.
			}
		}

		@Nullable CapturingHandler[] built = new CapturingHandler[1];
		QuicConnection connection = clientBuilder()
			.withFrameHandlerFactory(c -> built[0] = new CapturingHandler(c))
			.build();

		assertSame(connection, built[0].connection);
	}
}
