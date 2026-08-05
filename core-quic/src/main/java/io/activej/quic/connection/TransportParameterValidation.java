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
import io.activej.quic.tls.QuicTransportParameters;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The RFC 9000 §18.2 <b>semantic</b> checks on a peer's transport parameters (FR-008).
 * <p>
 * Feature 02's codec validates syntax — varint ranges and fixed lengths — and deliberately leaves the
 * value ranges and the connection-ID cross-checks here, because a semantic violation is a
 * <i>connection</i> error and the connection layer is what owns the decision to close.
 * <p>
 * Every failure is {@code TRANSPORT_PARAMETER_ERROR}. Parameters that are parsed but out of scope for
 * this feature — {@code stateless_reset_token}, {@code preferred_address} — are deliberately not
 * rejected, and unknown parameters are ignored per RFC 9000 §18.1.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18.2">RFC 9000 §18.2 — Transport Parameter Definitions</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-7.3">RFC 9000 §7.3 — Authenticating Connection IDs</a>
 */
public final class TransportParameterValidation {
	private TransportParameterValidation() {}

	/** RFC 9000 §18.2: an endpoint must be able to receive a 1200-byte UDP payload. */
	public static final long MIN_MAX_UDP_PAYLOAD_SIZE = 1200;
	/** RFC 9000 §18.2: values above 20 are invalid. */
	public static final long MAX_ACK_DELAY_EXPONENT = 20;
	/** RFC 9000 §18.2: values of 2^14 or greater are invalid. */
	public static final long MAX_MAX_ACK_DELAY = 1 << 14;
	/** RFC 9000 §18.2: the limit must be at least 2. */
	public static final long MIN_ACTIVE_CONNECTION_ID_LIMIT = 2;

	/**
	 * The parameters a server processing early data may be assumed to have advertised: every
	 * {@code initial_max_*} at <b>zero</b>, everything else at its RFC 9000 §18.2 default.
	 * <p>
	 * This is the honest server-side answer to "what may I send before the handshake completes?", and
	 * the answer is <i>nothing</i>. A server accepting 0-RTT knows what it may <b>receive</b> — the
	 * limits it advertised itself, which its own settings already supply — but it has been told nothing
	 * about the client's, because the client's transport parameters arrive with the ClientHello it has
	 * not yet finished processing. Sending under an assumed limit would be sending under a guess.
	 * <p>
	 * Deliberately not {@code null}: a stream layer given {@code null} would have to encode "no credit"
	 * a second way, and two encodings of the same state is how one of them ends up untested.
	 */
	public static final QuicTransportParameters WITHOUT_SEND_CREDIT = new QuicTransportParameters(
		null, 0, null,
		QuicTransportParameters.DEFAULT_MAX_UDP_PAYLOAD_SIZE,
		0, 0, 0, 0, 0, 0,
		QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT,
		QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
		false, null,
		QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT,
		null, null, 0);

	/**
	 * RFC 9000 §7.4.1: a server resuming a session may <b>raise</b> the seven limits below, never lower
	 * them beneath the values it issued with the ticket (spec FR-054). {@code max_datagram_frame_size} is
	 * checked alongside them under RFC 9221 §3's own rule, which exists for the same reason.
	 * <p>
	 * The rule exists because 0-RTT data is already on the wire by the time these parameters arrive.
	 * The client sent it against what it remembered; a server that then advertised less would have
	 * received data it never permitted, and no recovery is possible — the bytes cannot be un-sent. So
	 * the reduction is the error, not the data.
	 * <p>
	 * Everything else is free to move, including the eight parameters RFC 9000 §7.4.1 forbids
	 * remembering at all ({@code ack_delay_exponent}, {@code max_ack_delay},
	 * {@code initial_source_connection_id}, {@code original_destination_connection_id},
	 * {@code preferred_address}, {@code retry_source_connection_id}, {@code stateless_reset_token},
	 * {@code max_idle_timeout}) — {@code QuicSessionTicket.rememberableParameters} has already cleared
	 * them, so there is nothing here for a comparison to read.
	 * <p>
	 * Applies only to a handshake that <b>actually resumed</b>. A server that fell back to a full
	 * handshake opened a new session and owes the old one's limits nothing; the caller gates on
	 * {@code TlsEngineResult.resumed()} for exactly that reason.
	 *
	 * @param remembered the parameters stored with the ticket that was offered
	 * @param actual     the parameters this handshake delivered
	 * @throws QuicTransportException {@code PROTOCOL_VIOLATION}, naming the parameter, the value that
	 *                                was promised and the value that replaced it
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
	 */
	public static void validateNonReduction(QuicTransportParameters remembered, QuicTransportParameters actual)
		throws QuicTransportException {
		checkNotReduced("active_connection_id_limit",
			remembered.activeConnectionIdLimit(), actual.activeConnectionIdLimit());
		checkNotReduced("initial_max_data",
			remembered.initialMaxData(), actual.initialMaxData());
		checkNotReduced("initial_max_stream_data_bidi_local",
			remembered.initialMaxStreamDataBidiLocal(), actual.initialMaxStreamDataBidiLocal());
		checkNotReduced("initial_max_stream_data_bidi_remote",
			remembered.initialMaxStreamDataBidiRemote(), actual.initialMaxStreamDataBidiRemote());
		checkNotReduced("initial_max_stream_data_uni",
			remembered.initialMaxStreamDataUni(), actual.initialMaxStreamDataUni());
		checkNotReduced("initial_max_streams_bidi",
			remembered.initialMaxStreamsBidi(), actual.initialMaxStreamsBidi());
		checkNotReduced("initial_max_streams_uni",
			remembered.initialMaxStreamsUni(), actual.initialMaxStreamsUni());
		// RFC 9221 §3, not RFC 9000 §7.4.1: a client that remembered a non-zero value may have put a
		// DATAGRAM frame in early data against it, and the same "the bytes cannot be un-sent" argument
		// applies. Reported with the same code, since the remedy is identical.
		checkNotReduced("max_datagram_frame_size",
			remembered.maxDatagramFrameSize(), actual.maxDatagramFrameSize());
	}

	private static void checkNotReduced(String parameter, long remembered, long actual)
		throws QuicTransportException {
		if (actual >= remembered) return;
		throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
			"the server reduced " + parameter + " from the " + remembered + " it issued with the session " +
			"ticket to " + actual + ", which RFC 9000 §7.4.1 forbids on a resumed connection");
	}

	/**
	 * Validates the peer's parameters against the connection IDs actually observed on the wire.
	 *
	 * @param peer                     the parameters from {@code TlsEngineResult.peerTransportParameters()}
	 * @param observedPeerScid         the source connection ID seen in that peer's long-header packets
	 * @param clientsFirstDcid         the DCID the client put in its first Initial; checked against the
	 *                                 server's {@code original_destination_connection_id}. Pass
	 *                                 {@code null} when validating on the server side
	 * @param retrySourceConnectionId  the SCID of a Retry that was processed, or {@code null} when none
	 *                                 was; the peer's {@code retry_source_connection_id} must be present
	 *                                 exactly when this is
	 * @throws QuicTransportException {@code TRANSPORT_PARAMETER_ERROR}, naming the offending parameter
	 */
	public static void validate(
		QuicTransportParameters peer,
		QuicConnectionId observedPeerScid,
		@Nullable QuicConnectionId clientsFirstDcid,
		@Nullable QuicConnectionId retrySourceConnectionId
	) throws QuicTransportException {
		if (peer.maxUdpPayloadSize() < MIN_MAX_UDP_PAYLOAD_SIZE) {
			throw error("max_udp_payload_size (" + peer.maxUdpPayloadSize() + ") is below the required " +
				MIN_MAX_UDP_PAYLOAD_SIZE);
		}
		if (peer.ackDelayExponent() > MAX_ACK_DELAY_EXPONENT) {
			throw error("ack_delay_exponent (" + peer.ackDelayExponent() + ") exceeds " + MAX_ACK_DELAY_EXPONENT);
		}
		if (peer.maxAckDelay() >= MAX_MAX_ACK_DELAY) {
			throw error("max_ack_delay (" + peer.maxAckDelay() + " ms) must be below " + MAX_MAX_ACK_DELAY);
		}
		if (peer.activeConnectionIdLimit() < MIN_ACTIVE_CONNECTION_ID_LIMIT) {
			throw error("active_connection_id_limit (" + peer.activeConnectionIdLimit() + ") is below " +
				MIN_ACTIVE_CONNECTION_ID_LIMIT);
		}

		// RFC 9000 §7.3: the peer must echo, in initial_source_connection_id, the same value it put in
		// the Source Connection ID of its packets. This is what binds the handshake to the observed path.
		byte[] declaredScid = peer.initialSourceConnectionId();
		if (declaredScid == null) {
			throw error("initial_source_connection_id is missing");
		}
		if (!Arrays.equals(declaredScid, observedPeerScid.bytes())) {
			throw error("initial_source_connection_id does not match the source connection ID observed on the wire");
		}

		if (clientsFirstDcid != null) {
			// Server -> client only: proves the server saw the DCID the client chose, which is also the
			// value that seeded the Initial keys.
			byte[] declaredOriginal = peer.originalDestinationConnectionId();
			if (declaredOriginal == null) {
				throw error("original_destination_connection_id is missing");
			}
			if (!Arrays.equals(declaredOriginal, clientsFirstDcid.bytes())) {
				throw error("original_destination_connection_id does not match the destination connection ID " +
					"sent in the first Initial packet");
			}
		}

		byte[] declaredRetryScid = peer.retrySourceConnectionId();
		if (retrySourceConnectionId == null) {
			if (declaredRetryScid != null) {
				throw error("retry_source_connection_id is present although no Retry was processed");
			}
		} else {
			if (declaredRetryScid == null) {
				throw error("retry_source_connection_id is missing although a Retry was processed");
			}
			if (!Arrays.equals(declaredRetryScid, retrySourceConnectionId.bytes())) {
				throw error("retry_source_connection_id does not match the Retry packet's source connection ID");
			}
		}

		// stateless_reset_token and preferred_address are parsed and deliberately ignored: stateless
		// reset and connection migration are both out of scope for this feature. Unknown parameters are
		// ignored by the codec per RFC 9000 §18.1.
	}

	/**
	 * The local parameters this endpoint advertises (RFC 9000 §18.2).
	 * <p>
	 * The connection-wide and per-stream receive credits ({@code initial_max_data},
	 * {@code initial_max_stream_data_bidi_local}, {@code initial_max_stream_data_bidi_remote},
	 * {@code initial_max_stream_data_uni}) and the stream counts the peer may open
	 * ({@code initial_max_streams_bidi}, {@code initial_max_streams_uni}) all carry their configured
	 * {@link QuicConnectionSettings} values, so a peer may open streams up to those bounds and send that
	 * much data before waiting for a MAX_DATA or MAX_STREAM_DATA update. {@code QuicConnectionSettings}
	 * guarantees at build time that no {@code initial_max_stream_data*} exceeds {@code initial_max_data},
	 * so nothing advertised here promises credit the connection window cannot honour.
	 * <p>
	 * Active migration is disabled because migration is out of scope, and
	 * {@code stateless_reset_token} / {@code preferred_address} are absent for the same reason.
	 *
	 * @param originalDestinationConnectionId set by a server to the DCID from the client's first
	 *                                        Initial; {@code null} on a client
	 */
	public static QuicTransportParameters local(
		QuicConnectionSettings settings,
		QuicConnectionId localConnectionId,
		@Nullable QuicConnectionId originalDestinationConnectionId
	) {
		return new QuicTransportParameters(
			originalDestinationConnectionId == null ? null : originalDestinationConnectionId.bytes(),
			settings.maxIdleTimeoutMillis(),
			null,                                        // stateless_reset_token: out of scope
			settings.maxDatagramSize(),
			settings.initialMaxData(),
			settings.initialMaxStreamDataBidiLocal(),
			settings.initialMaxStreamDataBidiRemote(),
			settings.initialMaxStreamDataUni(),
			settings.initialMaxStreamsBidi(),
			settings.initialMaxStreamsUni(),
			QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT,
			QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
			true,                                        // disable_active_migration: migration out of scope
			null,                                        // preferred_address: out of scope
			QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT,
			localConnectionId.bytes(),
			null,                                        // retry_source_connection_id: set by a Retry-issuing server
			settings.maxDatagramFrameSize());            // 0 — the default — is not encoded at all (RFC 9221 §3)
	}

	private static QuicTransportException error(String message) {
		return new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR, message);
	}
}
