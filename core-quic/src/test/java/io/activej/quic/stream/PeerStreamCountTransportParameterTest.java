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

import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * RFC 9000 §18.2 / §4.6: a stream count above 2^60 could name a stream identifier that does not exist.
 * {@code onMaxStreamsFrame} already rejects this for the wire frame; this asserts the same bound holds
 * for the peer's {@code initial_max_streams_bidi} / {@code initial_max_streams_uni} transport
 * parameters, consumed once at establishment by {@link QuicStreamManager#initializeFrom}.
 * <p>
 * {@code QuicConnectionSettings} itself now rejects a value in this range at {@code build()}, so a
 * conforming local configuration can never produce one — this file is exclusively about a
 * non-conforming <em>peer</em>, which is why the peer's parameters are hand-built rather than driven
 * through a real handshake: nothing on the settings side can be coerced into declaring this on the
 * wire, and the transport codec deliberately leaves the bound to this layer to enforce.
 */
public final class PeerStreamCountTransportParameterTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_STREAM_COUNT = 1L << 60;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	/**
	 * Only the two fields under test vary; the rest are placeholders {@code initializeFrom} never
	 * touches. Driven directly rather than through a real handshake, exactly as {@code startClientOnly}
	 * in {@code EstablishmentGateTest} constructs a manager with no peer parameters yet.
	 */
	private static QuicTransportParameters peerWith(long initialMaxStreamsBidi, long initialMaxStreamsUni) {
		return new QuicTransportParameters(
			null, 30_000, null, 1350,
			0, 0, 0, 0, initialMaxStreamsBidi, initialMaxStreamsUni,
			3, 25, true, null, 2,
			new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, null);
	}

	private void startClientOnly() {
		wire.startClient(QuicConnectionSettings.create());
	}

	@Test
	public void aPeerAdvertisedBidiCountAboveTheCapIsATransportParameterError() {
		startClientOnly();
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> clientManager.initializeFrom(peerWith(MAX_STREAM_COUNT + 1, 3)));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("initial_max_streams_bidi"));
	}

	@Test
	public void aPeerAdvertisedUniCountAboveTheCapIsATransportParameterError() {
		startClientOnly();
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> clientManager.initializeFrom(peerWith(100, MAX_STREAM_COUNT + 1)));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("initial_max_streams_uni"));
	}

	@Test
	public void theCapItselfIsAccepted() throws Exception {
		startClientOnly();
		clientManager.initializeFrom(peerWith(MAX_STREAM_COUNT, MAX_STREAM_COUNT));
		// No exception is the assertion; nothing else about the manager is exercised by this test.
	}
}
