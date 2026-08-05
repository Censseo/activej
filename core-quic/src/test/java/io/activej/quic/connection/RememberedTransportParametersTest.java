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

import io.activej.common.MemSize;
import io.activej.common.ref.Ref;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.function.UnaryOperator;

import static org.junit.Assert.*;

/**
 * T062 — the RFC 9000 §7.4.1 rules a resumption puts on transport parameters: the seven limits a
 * server may not <b>reduce</b> below what it issued with the ticket, and the eight parameters that are
 * never remembered at all.
 * <p>
 * The two halves are deliberately tested from opposite directions. The seven are asserted one at a
 * time, because a check written as one boolean expression passes a test that only ever reduces one
 * parameter it happens to cover. The eight are asserted <i>together</i>, in one case that mutates all
 * of them at once: they are excluded, so no combination of them may produce a violation, and a check
 * that accidentally read one of them would fail here and nowhere else.
 * <p>
 * The two wire cases pin the one thing the pure cases cannot: <b>when</b> the rule applies. A server
 * that fell back to a full handshake is a new session and owes the old one's limits nothing, so the
 * same reduction must close a resumed connection and pass an unresumed one. That distinction is the
 * whole reason {@code TlsEngineResult.resumed()} exists.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-7.4.1">RFC 9000 §7.4.1 — Values of Transport Parameters for 0-RTT</a>
 */
public final class RememberedTransportParametersTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final byte[] SCID = {1, 2, 3, 4};
	private static final byte[] OTHER_SCID = {9, 9, 9, 9};
	private static final byte[] RESET_TOKEN = new byte[16];

	/** Everything a server may not reduce, set well above its default so a reduction is expressible. */
	private static final QuicTransportParameters REMEMBERED = new QuicTransportParameters(
		null,                                                    // original_destination_connection_id: excluded
		0,                                                       // max_idle_timeout: excluded
		null,                                                    // stateless_reset_token: excluded
		65527,
		1_000_000,                                               // initial_max_data
		200_000,                                                 // initial_max_stream_data_bidi_local
		300_000,                                                 // initial_max_stream_data_bidi_remote
		400_000,                                                 // initial_max_stream_data_uni
		100,                                                     // initial_max_streams_bidi
		7,                                                       // initial_max_streams_uni
		QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT,       // excluded
		QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,            // excluded
		false,
		null,                                                    // preferred_address: excluded
		4,                                                       // active_connection_id_limit
		null,                                                    // initial_source_connection_id: excluded
		null);                                                   // retry_source_connection_id: excluded

	// ---------------------------------------------------------------- the seven non-reducible limits

	@Test
	public void aReducedActiveConnectionIdLimitIsAProtocolViolation() {
		assertReductionRefused("active_connection_id_limit", with(p -> withActiveConnectionIdLimit(p, 2)));
	}

	@Test
	public void aReducedInitialMaxDataIsAProtocolViolation() {
		assertReductionRefused("initial_max_data", with(p -> withInitialMaxData(p, 999_999)));
	}

	@Test
	public void aReducedInitialMaxStreamDataBidiLocalIsAProtocolViolation() {
		assertReductionRefused("initial_max_stream_data_bidi_local",
			with(p -> withStreamData(p, 199_999, p.initialMaxStreamDataBidiRemote(), p.initialMaxStreamDataUni())));
	}

	@Test
	public void aReducedInitialMaxStreamDataBidiRemoteIsAProtocolViolation() {
		assertReductionRefused("initial_max_stream_data_bidi_remote",
			with(p -> withStreamData(p, p.initialMaxStreamDataBidiLocal(), 299_999, p.initialMaxStreamDataUni())));
	}

	@Test
	public void aReducedInitialMaxStreamDataUniIsAProtocolViolation() {
		assertReductionRefused("initial_max_stream_data_uni",
			with(p -> withStreamData(p, p.initialMaxStreamDataBidiLocal(), p.initialMaxStreamDataBidiRemote(), 399_999)));
	}

	@Test
	public void aReducedInitialMaxStreamsBidiIsAProtocolViolation() {
		assertReductionRefused("initial_max_streams_bidi", with(p -> withStreams(p, 99, p.initialMaxStreamsUni())));
	}

	@Test
	public void aReducedInitialMaxStreamsUniIsAProtocolViolation() {
		assertReductionRefused("initial_max_streams_uni", with(p -> withStreams(p, p.initialMaxStreamsBidi(), 6)));
	}

	@Test
	public void identicalParametersAreAccepted() throws QuicTransportException {
		TransportParameterValidation.validateNonReduction(REMEMBERED, REMEMBERED);
	}

	@Test
	public void raisingEverySevenLimitIsAccepted() throws QuicTransportException {
		QuicTransportParameters raised = new QuicTransportParameters(
			null, 0, null, 65527,
			2_000_000, 400_000, 600_000, 800_000, 200, 14,
			QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
			false, null, 8, SCID, null);
		TransportParameterValidation.validateNonReduction(REMEMBERED, raised);
	}

	// ---------------------------------------------------------------- the eight excludes

	@Test
	public void allEightExcludedParametersMayChangeAtOnce() throws QuicTransportException {
		// Every one of RFC 9000 §7.4.1's excludes moved in the direction that would be a reduction if it
		// were covered: a smaller max_idle_timeout, different ack-delay values, a foreign source
		// connection ID, and four parameters that were absent and are now present.
		QuicTransportParameters actual = new QuicTransportParameters(
			OTHER_SCID,                                          // original_destination_connection_id
			1,                                                   // max_idle_timeout
			RESET_TOKEN,                                         // stateless_reset_token
			65527,
			REMEMBERED.initialMaxData(),
			REMEMBERED.initialMaxStreamDataBidiLocal(),
			REMEMBERED.initialMaxStreamDataBidiRemote(),
			REMEMBERED.initialMaxStreamDataUni(),
			REMEMBERED.initialMaxStreamsBidi(),
			REMEMBERED.initialMaxStreamsUni(),
			0,                                                   // ack_delay_exponent
			1,                                                   // max_ack_delay
			false,
			new byte[] {7},                                      // preferred_address
			REMEMBERED.activeConnectionIdLimit(),
			SCID,                                                // initial_source_connection_id
			OTHER_SCID);                                         // retry_source_connection_id
		TransportParameterValidation.validateNonReduction(REMEMBERED, actual);
	}

	/**
	 * The complement of {@code QuicSessionTicketTest.rememberableParametersDropsTheEightRfc9000Excludes},
	 * stated here as the reason the case above is safe: the filter leaves nothing behind for the
	 * non-reduction check to compare, so the two rules cannot disagree about which eight they mean.
	 */
	@Test
	public void rememberableParametersLeavesEachExcludeNullOrAtItsDefault() {
		QuicTransportParameters full = new QuicTransportParameters(
			OTHER_SCID, 30_000, RESET_TOKEN, 65527,
			1, 2, 3, 4, 5, 6,
			5, 50, true, new byte[] {7}, 4, SCID, OTHER_SCID);
		QuicTransportParameters remembered = QuicSessionTicket.rememberableParameters(full);

		assertNull(remembered.originalDestinationConnectionId());
		assertEquals(0, remembered.maxIdleTimeout());
		assertNull(remembered.statelessResetToken());
		assertEquals(QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, remembered.ackDelayExponent());
		assertEquals(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY, remembered.maxAckDelay());
		assertNull(remembered.preferredAddress());
		assertNull(remembered.initialSourceConnectionId());
		assertNull(remembered.retrySourceConnectionId());
	}

	// ---------------------------------------------------------------- on the wire

	/**
	 * The rule where it actually bites: a resumed connection whose server now advertises less
	 * {@code initial_max_data} than the ticket promised is closed with {@code PROTOCOL_VIOLATION}.
	 */
	@Test
	public void aResumingServerThatReducesInitialMaxDataClosesWithProtocolViolation() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicConnectionSettings generous = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(512))
			.build();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, generous);
		assertNotNull("the first handshake issued no ticket", ticket);

		QuicConnectionSettings stingy = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(64))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(32))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(32))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(32))
			.build();
		try (QuicWirePair pair = new QuicWirePair()) {
			pair.withServerTlsConfig(builder -> builder.withTicketKeys(keys))
				.withClientTlsConfig(builder -> builder.withSessionTicket(ticket))
				.withClientRememberedTransportParameters(ticket.transportParameters());
			Ref<Exception> failure = new Ref<>();
			pair.startClient(generous).whenException(failure::set);
			pair.acceptServer(stingy);
			pair.pump();

			assertTrue("the client should have closed on the reduction",
				pair.client().state().isTerminating() || pair.client().state() == QuicConnectionState.CLOSED);
			assertNotNull("the handshake promise did not fail", failure.get());
			assertTrue("expected a transport error, got " + failure.get(),
				failure.get() instanceof QuicTransportException);
			QuicTransportException e = (QuicTransportException) failure.get();
			assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
			assertTrue("the failure must name the parameter, got: " + e.getMessage(),
				e.getMessage().contains("initial_max_data"));
		}
	}

	/**
	 * The same reduction, on a handshake that did <b>not</b> resume: nothing was promised, so nothing
	 * is owed. Without the {@code resumed()} gate this connection would close too.
	 */
	@Test
	public void aFullHandshakeMayAdvertiseLessThanARememberedTicketPromised() throws Exception {
		QuicConnectionSettings generous = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(512))
			.build();
		QuicConnectionSettings stingy = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(64))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(32))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(32))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(32))
			.build();
		try (QuicWirePair pair = new QuicWirePair()) {
			// Remembered parameters, but no ticket offered and no ticket keys on the server, so the
			// handshake is a full one.
			pair.withClientRememberedTransportParameters(REMEMBERED);
			pair.startClient(generous);
			pair.acceptServer(stingy);
			pair.pump();

			assertEquals(QuicConnectionState.ESTABLISHED, pair.client().state());
			assertNull(pair.client().peerClose());
			assertNull(pair.server().peerClose());
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void assertReductionRefused(String parameterName, QuicTransportParameters actual) {
		QuicTransportException e = assertThrows(QuicTransportException.class,
			() -> TransportParameterValidation.validateNonReduction(REMEMBERED, actual));
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
		assertTrue("the message must name the parameter, got: " + e.getMessage(),
			e.getMessage().contains(parameterName));
	}

	private static QuicTransportParameters with(UnaryOperator<QuicTransportParameters> mutation) {
		return mutation.apply(REMEMBERED);
	}

	private static QuicTransportParameters withInitialMaxData(QuicTransportParameters base, long initialMaxData) {
		return new QuicTransportParameters(base.originalDestinationConnectionId(), base.maxIdleTimeout(),
			base.statelessResetToken(), base.maxUdpPayloadSize(), initialMaxData,
			base.initialMaxStreamDataBidiLocal(), base.initialMaxStreamDataBidiRemote(),
			base.initialMaxStreamDataUni(), base.initialMaxStreamsBidi(), base.initialMaxStreamsUni(),
			base.ackDelayExponent(), base.maxAckDelay(), base.disableActiveMigration(), base.preferredAddress(),
			base.activeConnectionIdLimit(), base.initialSourceConnectionId(), base.retrySourceConnectionId());
	}

	private static QuicTransportParameters withStreamData(QuicTransportParameters base, long bidiLocal,
		long bidiRemote, long uni
	) {
		return new QuicTransportParameters(base.originalDestinationConnectionId(), base.maxIdleTimeout(),
			base.statelessResetToken(), base.maxUdpPayloadSize(), base.initialMaxData(), bidiLocal, bidiRemote, uni,
			base.initialMaxStreamsBidi(), base.initialMaxStreamsUni(), base.ackDelayExponent(), base.maxAckDelay(),
			base.disableActiveMigration(), base.preferredAddress(), base.activeConnectionIdLimit(),
			base.initialSourceConnectionId(), base.retrySourceConnectionId());
	}

	private static QuicTransportParameters withStreams(QuicTransportParameters base, long bidi, long uni) {
		return new QuicTransportParameters(base.originalDestinationConnectionId(), base.maxIdleTimeout(),
			base.statelessResetToken(), base.maxUdpPayloadSize(), base.initialMaxData(),
			base.initialMaxStreamDataBidiLocal(), base.initialMaxStreamDataBidiRemote(),
			base.initialMaxStreamDataUni(), bidi, uni, base.ackDelayExponent(), base.maxAckDelay(),
			base.disableActiveMigration(), base.preferredAddress(), base.activeConnectionIdLimit(),
			base.initialSourceConnectionId(), base.retrySourceConnectionId());
	}

	private static QuicTransportParameters withActiveConnectionIdLimit(QuicTransportParameters base, long limit) {
		return new QuicTransportParameters(base.originalDestinationConnectionId(), base.maxIdleTimeout(),
			base.statelessResetToken(), base.maxUdpPayloadSize(), base.initialMaxData(),
			base.initialMaxStreamDataBidiLocal(), base.initialMaxStreamDataBidiRemote(),
			base.initialMaxStreamDataUni(), base.initialMaxStreamsBidi(), base.initialMaxStreamsUni(),
			base.ackDelayExponent(), base.maxAckDelay(), base.disableActiveMigration(), base.preferredAddress(),
			limit, base.initialSourceConnectionId(), base.retrySourceConnectionId());
	}
}
