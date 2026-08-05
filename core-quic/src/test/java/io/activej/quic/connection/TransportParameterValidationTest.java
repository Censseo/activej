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
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T025 — every row of data-model.md §"Transport-parameter validation".
 */
public final class TransportParameterValidationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final byte[] PEER_SCID_BYTES = {1, 2, 3, 4, 5, 6, 7, 8};
	private static final QuicConnectionId PEER_SCID = QuicConnectionId.of(PEER_SCID_BYTES);
	private static final byte[] CLIENT_FIRST_DCID_BYTES = {9, 9, 9, 9, 9, 9, 9, 9};
	private static final QuicConnectionId CLIENT_FIRST_DCID = QuicConnectionId.of(CLIENT_FIRST_DCID_BYTES);
	private static final byte[] RETRY_SCID_BYTES = {7, 7, 7, 7};
	private static final QuicConnectionId RETRY_SCID = QuicConnectionId.of(RETRY_SCID_BYTES);

	/** A parameter set that passes every rule, as a baseline to mutate one field at a time. */
	private static QuicTransportParameters valid() {
		return new QuicTransportParameters(
			null, 30_000, null, 1350,
			0, 0, 0, 0, 0, 0,
			3, 25, true, null, 2,
			PEER_SCID_BYTES, null, 0);
	}

	private static QuicTransportParameters with(
		long maxUdpPayloadSize, long ackDelayExponent, long maxAckDelay, long activeConnectionIdLimit
	) {
		return new QuicTransportParameters(
			null, 30_000, null, maxUdpPayloadSize,
			0, 0, 0, 0, 0, 0,
			ackDelayExponent, maxAckDelay, true, null, activeConnectionIdLimit,
			PEER_SCID_BYTES, null, 0);
	}

	private static void expectTransportParameterError(QuicTransportParameters peer, String expectedInMessage) {
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validate(peer, PEER_SCID, null, null));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue("expected '" + expectedInMessage + "' in: " + e.getMessage(),
			e.getMessage().contains(expectedInMessage));
	}

	// ---- the happy path ----

	@Test
	public void aFullyValidPeerSetPasses() throws Exception {
		TransportParameterValidation.validate(valid(), PEER_SCID, null, null);
	}

	// ---- max_udp_payload_size >= 1200 ----

	@Test
	public void maxUdpPayloadSizeBelow1200IsRejected() {
		expectTransportParameterError(with(1199, 3, 25, 2), "max_udp_payload_size");
	}

	@Test
	public void maxUdpPayloadSizeOfExactly1200IsAccepted() throws Exception {
		TransportParameterValidation.validate(with(1200, 3, 25, 2), PEER_SCID, null, null);
	}

	// ---- ack_delay_exponent <= 20 ----

	@Test
	public void ackDelayExponentAbove20IsRejected() {
		expectTransportParameterError(with(1350, 21, 25, 2), "ack_delay_exponent");
	}

	@Test
	public void ackDelayExponentOfExactly20IsAccepted() throws Exception {
		TransportParameterValidation.validate(with(1350, 20, 25, 2), PEER_SCID, null, null);
	}

	// ---- max_ack_delay < 2^14 ----

	@Test
	public void maxAckDelayOfExactly2Pow14IsRejected() {
		expectTransportParameterError(with(1350, 3, 1 << 14, 2), "max_ack_delay");
	}

	@Test
	public void maxAckDelayJustBelow2Pow14IsAccepted() throws Exception {
		TransportParameterValidation.validate(with(1350, 3, (1 << 14) - 1, 2), PEER_SCID, null, null);
	}

	// ---- active_connection_id_limit >= 2 ----

	@Test
	public void activeConnectionIdLimitBelow2IsRejected() {
		expectTransportParameterError(with(1350, 3, 25, 1), "active_connection_id_limit");
	}

	@Test
	public void activeConnectionIdLimitOfExactly2IsAccepted() throws Exception {
		TransportParameterValidation.validate(with(1350, 3, 25, 2), PEER_SCID, null, null);
	}

	// ---- initial_source_connection_id ----

	@Test
	public void missingInitialSourceConnectionIdIsRejected() {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, null, 1350, 0, 0, 0, 0, 0, 0, 3, 25, true, null, 2, null, null, 0);
		expectTransportParameterError(peer, "initial_source_connection_id is missing");
	}

	@Test
	public void initialSourceConnectionIdNotMatchingTheObservedScidIsRejected() {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, null, 1350, 0, 0, 0, 0, 0, 0, 3, 25, true, null, 2,
			new byte[]{8, 7, 6, 5, 4, 3, 2, 1}, null, 0);
		expectTransportParameterError(peer, "initial_source_connection_id does not match");
	}

	// ---- original_destination_connection_id (server -> client only) ----

	@Test
	public void originalDestinationConnectionIdIsNotRequiredWhenValidatingOnTheServerSide() throws Exception {
		// clientsFirstDcid == null means "we are the server looking at a client's parameters".
		TransportParameterValidation.validate(valid(), PEER_SCID, null, null);
	}

	@Test
	public void missingOriginalDestinationConnectionIdIsRejectedOnTheClientSide() {
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validate(valid(), PEER_SCID, CLIENT_FIRST_DCID, null));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("original_destination_connection_id is missing"));
	}

	@Test
	public void originalDestinationConnectionIdNotMatchingTheFirstDcidIsRejected() {
		QuicTransportParameters peer = new QuicTransportParameters(
			new byte[]{0, 0, 0, 0, 0, 0, 0, 0}, 30_000, null, 1350, 0, 0, 0, 0, 0, 0,
			3, 25, true, null, 2, PEER_SCID_BYTES, null, 0);
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validate(peer, PEER_SCID, CLIENT_FIRST_DCID, null));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("original_destination_connection_id does not match"));
	}

	@Test
	public void matchingOriginalDestinationConnectionIdIsAccepted() throws Exception {
		QuicTransportParameters peer = new QuicTransportParameters(
			CLIENT_FIRST_DCID_BYTES, 30_000, null, 1350, 0, 0, 0, 0, 0, 0,
			3, 25, true, null, 2, PEER_SCID_BYTES, null, 0);
		TransportParameterValidation.validate(peer, PEER_SCID, CLIENT_FIRST_DCID, null);
	}

	// ---- retry_source_connection_id: present iff a Retry was processed ----

	@Test
	public void retrySourceConnectionIdPresentWithoutARetryIsRejected() {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, null, 1350, 0, 0, 0, 0, 0, 0, 3, 25, true, null, 2,
			PEER_SCID_BYTES, RETRY_SCID_BYTES, 0);
		expectTransportParameterError(peer, "retry_source_connection_id is present although no Retry");
	}

	@Test
	public void retrySourceConnectionIdMissingAfterARetryIsRejected() {
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validate(valid(), PEER_SCID, null, RETRY_SCID));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("retry_source_connection_id is missing"));
	}

	@Test
	public void retrySourceConnectionIdNotMatchingTheRetryIsRejected() {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, null, 1350, 0, 0, 0, 0, 0, 0, 3, 25, true, null, 2,
			PEER_SCID_BYTES, new byte[]{1, 1, 1, 1}, 0);
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validate(peer, PEER_SCID, null, RETRY_SCID));
		assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, e.errorCode());
		assertTrue(e.getMessage().contains("retry_source_connection_id does not match"));
	}

	@Test
	public void matchingRetrySourceConnectionIdIsAccepted() throws Exception {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, null, 1350, 0, 0, 0, 0, 0, 0, 3, 25, true, null, 2,
			PEER_SCID_BYTES, RETRY_SCID_BYTES, 0);
		TransportParameterValidation.validate(peer, PEER_SCID, null, RETRY_SCID);
	}

	// ---- parsed and deliberately ignored ----

	@Test
	public void statelessResetTokenAndPreferredAddressAreIgnoredNotRejected() throws Exception {
		QuicTransportParameters peer = new QuicTransportParameters(
			null, 30_000, new byte[QuicTransportParameters.STATELESS_RESET_TOKEN_LENGTH], 1350,
			0, 0, 0, 0, 0, 0, 3, 25, true,
			// A minimal but well-formed preferred_address: 4-byte IPv4 + port + 16-byte IPv6 + port
			// + CID length + CID + 16-byte reset token. Contents are irrelevant — it must simply
			// not be rejected.
			new byte[4 + 2 + 16 + 2 + 1 + 16], 2, PEER_SCID_BYTES, null, 0);
		TransportParameterValidation.validate(peer, PEER_SCID, null, null);
	}

	@Test
	public void unknownParametersAreIgnoredByTheCodec() throws Exception {
		// RFC 9000 §18.1: an unrecognized parameter id must be skipped, not rejected. The codec owns
		// this, so the assertion is that a set carrying one still round-trips and then validates.
		QuicTransportParameters original = valid();
		ByteBuf buf = ByteBufPool.allocate(original.encodedLength() + 32);
		try {
			original.writeTo(buf, false);
			// An unknown parameter: id 0x3fff (4-byte varint), length 2, value {0, 0}.
			buf.writeByte((byte) 0xbf);
			buf.writeByte((byte) 0xff);
			buf.writeByte((byte) 2);
			buf.writeByte((byte) 0);
			buf.writeByte((byte) 0);
			QuicTransportParameters parsed = QuicTransportParameters.read(buf);
			assertEquals(0, buf.readRemaining());
			TransportParameterValidation.validate(parsed, PEER_SCID, null, null);
		} finally {
			buf.recycle();
		}
	}

	// ---- duplicate parameter ----

	@Test
	public void aDuplicateParameterIsSurfacedAsMalformedDataAndMapsToTransportParameterError() {
		QuicTransportParameters original = valid();
		ByteBuf buf = ByteBufPool.allocate(2 * original.encodedLength() + 16);
		try {
			original.writeTo(buf, false);
			// max_udp_payload_size (id 0x03) a second time.
			buf.writeByte((byte) 0x03);
			buf.writeByte((byte) 2);
			buf.writeByte((byte) 0x45);
			buf.writeByte((byte) 0x46);
			MalformedDataException e = assertThrows(MalformedDataException.class,
				() -> QuicTransportParameters.read(buf));
			// FR-004a: the connection layer maps any MalformedDataException out of parameter
			// decoding to TRANSPORT_PARAMETER_ERROR rather than to a TLS alert.
			assertEquals(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
				QuicConnection.transportParameterErrorFor(e).errorCode());
		} finally {
			buf.recycle();
		}
	}

	// ---- the locally advertised set ----

	@Test
	public void theLocalSetAdvertisesTheDocumentedValues() {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicConnectionId local = QuicConnectionId.of(PEER_SCID_BYTES);
		QuicTransportParameters params = TransportParameterValidation.local(settings, local, null);

		assertEquals(settings.maxIdleTimeoutMillis(), params.maxIdleTimeout());
		assertEquals(settings.maxDatagramSize(), params.maxUdpPayloadSize());
		assertEquals(QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, params.ackDelayExponent());
		assertEquals(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY, params.maxAckDelay());
		assertEquals(QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT, params.activeConnectionIdLimit());
		assertTrue(params.disableActiveMigration());
		assertArrayEquals(PEER_SCID_BYTES, params.initialSourceConnectionId());
		assertNull(params.originalDestinationConnectionId());
		assertNull(params.retrySourceConnectionId());
		assertNull(params.statelessResetToken());
		assertNull(params.preferredAddress());
		// Feature 04 filled these in from the settings; TransportParameterAdvertisementTest owns the
		// detail, this only pins that the local set is derived from the settings and not from constants.
		assertEquals(settings.initialMaxData(), params.initialMaxData());
		assertEquals(settings.initialMaxStreamsBidi(), params.initialMaxStreamsBidi());
		assertEquals(settings.initialMaxStreamsUni(), params.initialMaxStreamsUni());
	}

	@Test
	public void aServerLocalSetCarriesTheOriginalDestinationConnectionId() {
		QuicTransportParameters params = TransportParameterValidation.local(
			QuicConnectionSettings.create(), PEER_SCID, CLIENT_FIRST_DCID);
		assertArrayEquals(CLIENT_FIRST_DCID_BYTES, params.originalDestinationConnectionId());
	}

	// ---- the local set must satisfy our own validator ----

	@Test
	public void ourOwnAdvertisedSetPassesOurValidator() throws Exception {
		QuicTransportParameters params = TransportParameterValidation.local(
			QuicConnectionSettings.create(), PEER_SCID, CLIENT_FIRST_DCID);
		TransportParameterValidation.validate(params, PEER_SCID, CLIENT_FIRST_DCID, null);
	}
}
