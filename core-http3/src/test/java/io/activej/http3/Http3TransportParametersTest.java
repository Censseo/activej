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

package io.activej.http3;

import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * T060 / FR-017, FR-046, FR-058b: the two QUIC transport parameters HTTP/3 pins, asserted where they
 * are actually observable — on the peer's side of a completed handshake.
 * <p>
 * The values are feature 04's to encode and this feature's to choose, so what is proved here is the
 * end-to-end wiring: a {@link QuicConnectionSettings} built the way {@code Http3Server}/
 * {@code Http3Client} will build it produces exactly {@code initial_max_streams_uni = 3} and
 * {@code initial_max_streams_bidi = maxConcurrentRequestStreams} at the far end.
 */
public final class Http3TransportParametersTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void theServerAdvertisesThreeUniStreamsAndTheConfiguredBidiCount() {
		Http3Settings settings = Http3Settings.builder()
			.withMaxConcurrentRequestStreams(37)
			.build();
		wire = new Http3WirePair(loop)
			.withServerSettings(quicSettingsFor(settings))
			.connect();

		QuicTransportParameters advertised = wire.clientConnection().peerTransportParameters();
		assertNotNull("the handshake must have supplied the server's parameters", advertised);
		assertEquals("FR-017: the control stream plus both QPACK streams, and nothing more",
			Http3Settings.MAX_UNI_STREAMS, advertised.initialMaxStreamsUni());
		assertEquals("FR-046: maxConcurrentRequestStreams", 37, advertised.initialMaxStreamsBidi());
	}

	@Test
	public void theClientAdvertisesTheSameTwoParameters() {
		Http3Settings settings = Http3Settings.create();
		wire = new Http3WirePair(loop)
			.withClientSettings(quicSettingsFor(settings))
			.connect();

		QuicTransportParameters advertised = wire.serverConnection().peerTransportParameters();
		assertNotNull(advertised);
		assertEquals(Http3Settings.MAX_UNI_STREAMS, advertised.initialMaxStreamsUni());
		assertEquals(settings.maxConcurrentRequestStreams(), advertised.initialMaxStreamsBidi());
	}

	@Test
	public void theTransportDefaultsAlreadyMatchWhatThisFeatureRequires() {
		// FR-058b: feature 04 shipped these as its own defaults, so a deployment that configures
		// nothing is already conforming. This asserts that, so a later change to core-quic's defaults
		// fails here rather than silently widening an HTTP/3 endpoint's exposure.
		wire = new Http3WirePair(loop).connect();

		QuicTransportParameters advertised = wire.clientConnection().peerTransportParameters();
		assertNotNull(advertised);
		assertEquals(Http3Settings.MAX_UNI_STREAMS, advertised.initialMaxStreamsUni());
		assertEquals(Http3Settings.DEFAULT_MAX_CONCURRENT_REQUEST_STREAMS, advertised.initialMaxStreamsBidi());
	}

	/** Exactly the two limits {@code Http3Server}/{@code Http3Client} must pin (FR-017, FR-046). */
	private static QuicConnectionSettings quicSettingsFor(Http3Settings settings) {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsUni(Http3Settings.MAX_UNI_STREAMS)
			.withInitialMaxStreamsBidi(settings.maxConcurrentRequestStreams())
			.build();
	}
}
