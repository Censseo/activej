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
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T022 — the six RFC 9000 §18.2 flow-control and stream-limit parameters, which feature 03 advertised
 * as {@code 0} and this feature fills in from {@link QuicConnectionSettings}.
 * <p>
 * Three levels, because a mistake at any one of them is invisible at the others: the values are put
 * into the local parameter set at all; they survive the wire encoding; and they arrive at the peer's
 * {@code peerTransportParameters()} through a real handshake.
 * <p>
 * This is the wire-visible behaviour change of feature 04 — a peer that previously could open no
 * stream now can — so the assertions are deliberately exact rather than "non-zero".
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18.2">RFC 9000 §18.2 — Transport Parameter Definitions</a>
 */
public final class TransportParameterAdvertisementTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final QuicConnectionId LOCAL_CID = QuicConnectionId.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
	private static final QuicConnectionId CLIENT_FIRST_DCID = QuicConnectionId.of(new byte[]{9, 9, 9, 9, 9, 9, 9, 9});

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

	/** A set every value of which differs from both the default and from every other value here. */
	private static QuicConnectionSettings distinctSettings() {
		return QuicConnectionSettings.builder()
			.withMaxSendQueueBytes(MemSize.megabytes(8))
			.withInitialMaxData(MemSize.megabytes(3))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(111))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(222))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(333))
			.withInitialMaxStreamsBidi(41)
			.withInitialMaxStreamsUni(17)
			.withMaxOutstandingStreamBytes(MemSize.megabytes(2))
			.build();
	}

	private static void assertCarries(QuicConnectionSettings settings, QuicTransportParameters params) {
		assertEquals("initial_max_data", settings.initialMaxData(), params.initialMaxData());
		assertEquals("initial_max_stream_data_bidi_local",
			settings.initialMaxStreamDataBidiLocal(), params.initialMaxStreamDataBidiLocal());
		assertEquals("initial_max_stream_data_bidi_remote",
			settings.initialMaxStreamDataBidiRemote(), params.initialMaxStreamDataBidiRemote());
		assertEquals("initial_max_stream_data_uni",
			settings.initialMaxStreamDataUni(), params.initialMaxStreamDataUni());
		assertEquals("initial_max_streams_bidi", settings.initialMaxStreamsBidi(), params.initialMaxStreamsBidi());
		assertEquals("initial_max_streams_uni", settings.initialMaxStreamsUni(), params.initialMaxStreamsUni());
	}

	// ---- 1: the local set carries the configured values, not 0 ----

	@Test
	public void theLocalSetCarriesTheConfiguredLimitsRatherThanZero() {
		QuicConnectionSettings settings = distinctSettings();
		QuicTransportParameters params = TransportParameterValidation.local(settings, LOCAL_CID, null);

		assertCarries(settings, params);
		// The whole point: none of the six is the feature-03 placeholder any more.
		assertNotEquals(0, params.initialMaxData());
		assertNotEquals(0, params.initialMaxStreamDataBidiLocal());
		assertNotEquals(0, params.initialMaxStreamDataBidiRemote());
		assertNotEquals(0, params.initialMaxStreamDataUni());
		assertNotEquals(0, params.initialMaxStreamsBidi());
		assertNotEquals(0, params.initialMaxStreamsUni());
	}

	@Test
	public void theSixValuesAreNotTransposed() {
		// The parameters are six adjacent longs in the QuicTransportParameters constructor, so a
		// transposition compiles and only shows up as a peer with the wrong window. Each of the three
		// per-stream limits is distinct in distinctSettings() precisely so this can be asserted.
		QuicTransportParameters params = TransportParameterValidation.local(distinctSettings(), LOCAL_CID, null);

		assertEquals(MemSize.megabytes(3).toLong(), params.initialMaxData());
		assertEquals(MemSize.kilobytes(111).toLong(), params.initialMaxStreamDataBidiLocal());
		assertEquals(MemSize.kilobytes(222).toLong(), params.initialMaxStreamDataBidiRemote());
		assertEquals(MemSize.kilobytes(333).toLong(), params.initialMaxStreamDataUni());
		assertEquals(41, params.initialMaxStreamsBidi());
		assertEquals(17, params.initialMaxStreamsUni());
	}

	@Test
	public void theDefaultSettingsAdvertiseTheDocumentedDefaults() {
		QuicTransportParameters params =
			TransportParameterValidation.local(QuicConnectionSettings.create(), LOCAL_CID, null);

		assertEquals(MemSize.megabytes(1).toLong(), params.initialMaxData());
		assertEquals(MemSize.kilobytes(256).toLong(), params.initialMaxStreamDataBidiLocal());
		assertEquals(MemSize.kilobytes(256).toLong(), params.initialMaxStreamDataBidiRemote());
		assertEquals(MemSize.kilobytes(256).toLong(), params.initialMaxStreamDataUni());
		assertEquals(100, params.initialMaxStreamsBidi());
		assertEquals(3, params.initialMaxStreamsUni());
	}

	@Test
	public void nothingElseAboutTheLocalSetMoved() {
		// FR-039: the surface that feature 03 fixed must be untouched by this change.
		QuicConnectionSettings settings = distinctSettings();
		QuicTransportParameters params =
			TransportParameterValidation.local(settings, LOCAL_CID, CLIENT_FIRST_DCID);

		assertEquals(settings.maxIdleTimeoutMillis(), params.maxIdleTimeout());
		assertEquals(settings.maxDatagramSize(), params.maxUdpPayloadSize());
		assertEquals(QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, params.ackDelayExponent());
		assertEquals(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY, params.maxAckDelay());
		assertEquals(QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT, params.activeConnectionIdLimit());
		assertTrue(params.disableActiveMigration());
		assertArrayEquals(LOCAL_CID.bytes(), params.initialSourceConnectionId());
		assertArrayEquals(CLIENT_FIRST_DCID.bytes(), params.originalDestinationConnectionId());
		assertNull(params.statelessResetToken());
		assertNull(params.preferredAddress());
		assertNull(params.retrySourceConnectionId());
	}

	// ---- max_datagram_frame_size: advertised only when the consumer enabled datagrams (FR-073) ----

	@Test
	public void theDefaultSettingsAdvertiseNoMaxDatagramFrameSize() {
		// RFC 9221 §3 encodes "DATAGRAM not supported" as absence, and datagrams are off by default, so a
		// consumer who never asked for them must be byte-for-byte where phase 1 left them.
		QuicTransportParameters params =
			TransportParameterValidation.local(QuicConnectionSettings.create(), LOCAL_CID, null);

		assertEquals(0, params.maxDatagramFrameSize());
	}

	@Test
	public void theConfiguredMaxDatagramFrameSizeIsAdvertised() {
		QuicConnectionSettings settings = QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(1252))
			.build();
		QuicTransportParameters params = TransportParameterValidation.local(settings, LOCAL_CID, null);

		assertEquals(1252, params.maxDatagramFrameSize());
	}

	@Test
	public void aServerProcessingEarlyDataAdvertisesNoDatagramSupportToItself() {
		// WITHOUT_SEND_CREDIT answers "what may I send before the handshake completes?", and until the
		// client's parameters arrive the honest answer for DATAGRAM is the same as for everything else.
		assertEquals(0, TransportParameterValidation.WITHOUT_SEND_CREDIT.maxDatagramFrameSize());
	}

	@Test
	public void eachSideSeesTheOthersMaxDatagramFrameSizeAfterAHandshake() throws MalformedDataException {
		QuicConnectionSettings clientSettings = QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(700))
			.build();
		QuicConnectionSettings serverSettings = QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(900))
			.build();

		wire.startClient(clientSettings);
		wire.acceptServer(serverSettings);
		wire.pump();

		assertEquals(900, wire.client().peerTransportParameters().maxDatagramFrameSize());
		assertEquals(700, wire.server().peerTransportParameters().maxDatagramFrameSize());
	}

	@Test
	public void anEndpointWithDatagramsOffTellsAnEnabledPeerNothing() throws MalformedDataException {
		// The "on ↔ off" compatibility row: the enabled side advertises, the other does not, and neither
		// closes. The disabled side simply sees 0 and will refuse every send locally.
		wire.startClient(QuicConnectionSettings.builder().withMaxDatagramFrameSize(MemSize.bytes(700)).build());
		wire.acceptServer(QuicConnectionSettings.create());
		wire.pump();

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(0, wire.client().peerTransportParameters().maxDatagramFrameSize());
		assertEquals(700, wire.server().peerTransportParameters().maxDatagramFrameSize());
	}

	@Test
	public void theAdvertisedSetStillPassesOurOwnValidator() throws Exception {
		QuicTransportParameters params =
			TransportParameterValidation.local(distinctSettings(), LOCAL_CID, CLIENT_FIRST_DCID);
		TransportParameterValidation.validate(params, LOCAL_CID, CLIENT_FIRST_DCID, null);
	}

	// ---- 2: the six values survive the wire encoding ----

	@Test
	public void theSixLimitsSurviveAnEncodeDecodeRoundTrip() throws MalformedDataException {
		QuicConnectionSettings settings = distinctSettings();
		QuicTransportParameters original = TransportParameterValidation.local(settings, LOCAL_CID, null);

		ByteBuf buf = ByteBufPool.allocate(original.encodedLength());
		try {
			original.writeTo(buf, false);
			assertEquals("encodedLength() must be exact", original.encodedLength(), buf.readRemaining());
			QuicTransportParameters decoded = QuicTransportParameters.read(buf);
			assertEquals("the whole set must be consumed", 0, buf.readRemaining());

			assertCarries(settings, decoded);
			assertEquals(original, decoded);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void aServerSetWithTheLimitsAlsoRoundTrips() throws MalformedDataException {
		QuicConnectionSettings settings = distinctSettings();
		QuicTransportParameters original =
			TransportParameterValidation.local(settings, LOCAL_CID, CLIENT_FIRST_DCID);

		ByteBuf buf = ByteBufPool.allocate(original.encodedLength());
		try {
			original.writeTo(buf, true);
			QuicTransportParameters decoded = QuicTransportParameters.read(buf);
			assertCarries(settings, decoded);
			assertArrayEquals(CLIENT_FIRST_DCID.bytes(), decoded.originalDestinationConnectionId());
		} finally {
			buf.recycle();
		}
	}

	// ---- 3: the values reach the peer through a real handshake ----

	@Test
	public void eachSideSeesTheOthersConfiguredLimitsAfterAHandshake() throws MalformedDataException {
		QuicConnectionSettings clientSettings = distinctSettings();
		QuicConnectionSettings serverSettings = QuicConnectionSettings.builder()
			.withMaxSendQueueBytes(MemSize.megabytes(8))
			.withInitialMaxData(MemSize.megabytes(5))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(444))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(555))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(666))
			.withInitialMaxStreamsBidi(23)
			.withInitialMaxStreamsUni(5)
			.withMaxOutstandingStreamBytes(MemSize.megabytes(2))
			.build();

		wire.startClient(clientSettings);
		wire.acceptServer(serverSettings);
		wire.pump();

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());

		QuicTransportParameters fromServer = wire.client().peerTransportParameters();
		assertNotNull("the client never saw the server's parameters", fromServer);
		assertCarries(serverSettings, fromServer);

		QuicTransportParameters fromClient = wire.server().peerTransportParameters();
		assertNotNull("the server never saw the client's parameters", fromClient);
		assertCarries(clientSettings, fromClient);
	}

	@Test
	public void theDefaultConfigurationReachesThePeerUnchanged() throws MalformedDataException {
		// SC-009's positive case: with no configuration at all, a peer is told it may open the documented
		// number of streams. Feature 03 told it zero.
		wire.handshake(QuicConnectionSettings.create());

		QuicTransportParameters fromServer = wire.client().peerTransportParameters();
		assertNotNull(fromServer);
		assertEquals(100, fromServer.initialMaxStreamsBidi());
		assertEquals(3, fromServer.initialMaxStreamsUni());
		assertEquals(MemSize.megabytes(1).toLong(), fromServer.initialMaxData());
	}
}
