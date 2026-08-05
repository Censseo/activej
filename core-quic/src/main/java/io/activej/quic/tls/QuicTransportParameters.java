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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.codec.QuicVarInts;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * QUIC transport parameters (RFC 9000 §18), an immutable record of the full v1 parameter set
 * with the RFC 9000 §18.2 defaults applied to absent parameters on decode.
 * <p>
 * Wire format: a sequence of (varint parameter id, varint length, value) in any order. Encode
 * here emits ids in ascending order. Decode tolerates unknown ids (RFC 9000 §18.1) and rejects
 * a duplicate id as a transport-parameter error, surfaced as {@link MalformedDataException} for
 * the connection layer to map to a TRANSPORT_PARAMETER_ERROR CONNECTION_CLOSE (RFC 9000 §10.1).
 * <p>
 * Encode-time enforcement (RFC 9000 §18.2): {@code initial_source_connection_id} is mandatory
 * for both roles and {@code original_destination_connection_id} is mandatory for a server —
 * {@link #writeTo(ByteBuf, boolean)} fails fast when they are absent. Semantic value checks
 * (minimums, connection-ID equality with packet headers) are the connection layer's, per the
 * syntax/semantics split of the codec layer.
 * <p>
 * One parameter outside RFC 9000 is carried here: {@code max_datagram_frame_size} (0x20,
 * RFC 9221 §3), which is also the only varint parameter written <b>conditionally</b> — see
 * {@link #writeTo(ByteBuf)}.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
 */
public record QuicTransportParameters(
	@Nullable byte[] originalDestinationConnectionId,
	long maxIdleTimeout,
	@Nullable byte[] statelessResetToken,
	long maxUdpPayloadSize,
	long initialMaxData,
	long initialMaxStreamDataBidiLocal,
	long initialMaxStreamDataBidiRemote,
	long initialMaxStreamDataUni,
	long initialMaxStreamsBidi,
	long initialMaxStreamsUni,
	long ackDelayExponent,
	long maxAckDelay,
	boolean disableActiveMigration,
	@Nullable byte[] preferredAddress,
	long activeConnectionIdLimit,
	@Nullable byte[] initialSourceConnectionId,
	@Nullable byte[] retrySourceConnectionId,
	long maxDatagramFrameSize) {

	// RFC 9000 §18.2 parameter identifiers
	private static final long ID_ORIGINAL_DESTINATION_CONNECTION_ID = 0x00;
	private static final long ID_MAX_IDLE_TIMEOUT = 0x01;
	private static final long ID_STATELESS_RESET_TOKEN = 0x02;
	private static final long ID_MAX_UDP_PAYLOAD_SIZE = 0x03;
	private static final long ID_INITIAL_MAX_DATA = 0x04;
	private static final long ID_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL = 0x05;
	private static final long ID_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06;
	private static final long ID_INITIAL_MAX_STREAM_DATA_UNI = 0x07;
	private static final long ID_INITIAL_MAX_STREAMS_BIDI = 0x08;
	private static final long ID_INITIAL_MAX_STREAMS_UNI = 0x09;
	private static final long ID_ACK_DELAY_EXPONENT = 0x0a;
	private static final long ID_MAX_ACK_DELAY = 0x0b;
	private static final long ID_DISABLE_ACTIVE_MIGRATION = 0x0c;
	private static final long ID_PREFERRED_ADDRESS = 0x0d;
	private static final long ID_ACTIVE_CONNECTION_ID_LIMIT = 0x0e;
	private static final long ID_INITIAL_SOURCE_CONNECTION_ID = 0x0f;
	private static final long ID_RETRY_SOURCE_CONNECTION_ID = 0x10;
	/** RFC 9221 §3, outside the RFC 9000 §18.2 block and the highest id encoded here. */
	private static final long ID_MAX_DATAGRAM_FRAME_SIZE = 0x20;

	// RFC 9000 §18.2 defaults for absent parameters
	public static final long DEFAULT_MAX_UDP_PAYLOAD_SIZE = 65527;
	public static final long DEFAULT_ACK_DELAY_EXPONENT = 3;
	public static final long DEFAULT_MAX_ACK_DELAY = 25;
	public static final long DEFAULT_ACTIVE_CONNECTION_ID_LIMIT = 2;

	public static final int STATELESS_RESET_TOKEN_LENGTH = 16;

	/**
	 * RFC 9000 §18.2 defaults for every parameter except {@code initial_source_connection_id},
	 * which has no default and is mandatory at encode time.
	 */
	public static QuicTransportParameters defaults(byte @Nullable [] initialSourceConnectionId) {
		return new QuicTransportParameters(
			null, 0, null, DEFAULT_MAX_UDP_PAYLOAD_SIZE, 0, 0, 0, 0, 0, 0,
			DEFAULT_ACK_DELAY_EXPONENT, DEFAULT_MAX_ACK_DELAY, false, null,
			DEFAULT_ACTIVE_CONNECTION_ID_LIMIT, initialSourceConnectionId, null, 0);
	}

	/**
	 * The canonical constructor: defensively clones every {@code byte[]} component and validates
	 * the varint range (RFC 9000 §16) and fixed lengths (RFC 9000 §18) of each parameter.
	 */
	public QuicTransportParameters {
		originalDestinationConnectionId = clone(originalDestinationConnectionId);
		statelessResetToken = clone(statelessResetToken);
		preferredAddress = clone(preferredAddress);
		initialSourceConnectionId = clone(initialSourceConnectionId);
		retrySourceConnectionId = clone(retrySourceConnectionId);
		if (statelessResetToken != null && statelessResetToken.length != STATELESS_RESET_TOKEN_LENGTH) {
			throw new IllegalArgumentException(
				"Stateless reset token must be " + STATELESS_RESET_TOKEN_LENGTH + " bytes: " + statelessResetToken.length);
		}
		checkVarIntRange("max_idle_timeout", maxIdleTimeout);
		checkVarIntRange("max_udp_payload_size", maxUdpPayloadSize);
		checkVarIntRange("initial_max_data", initialMaxData);
		checkVarIntRange("initial_max_stream_data_bidi_local", initialMaxStreamDataBidiLocal);
		checkVarIntRange("initial_max_stream_data_bidi_remote", initialMaxStreamDataBidiRemote);
		checkVarIntRange("initial_max_stream_data_uni", initialMaxStreamDataUni);
		checkVarIntRange("initial_max_streams_bidi", initialMaxStreamsBidi);
		checkVarIntRange("initial_max_streams_uni", initialMaxStreamsUni);
		checkVarIntRange("ack_delay_exponent", ackDelayExponent);
		checkVarIntRange("max_ack_delay", maxAckDelay);
		checkVarIntRange("active_connection_id_limit", activeConnectionIdLimit);
		checkVarIntRange("max_datagram_frame_size", maxDatagramFrameSize);
	}

	@Override
	public byte @Nullable [] originalDestinationConnectionId() {
		return clone(originalDestinationConnectionId);
	}

	@Override
	public byte @Nullable [] statelessResetToken() {
		return clone(statelessResetToken);
	}

	@Override
	public byte @Nullable [] preferredAddress() {
		return clone(preferredAddress);
	}

	@Override
	public byte @Nullable [] initialSourceConnectionId() {
		return clone(initialSourceConnectionId);
	}

	@Override
	public byte @Nullable [] retrySourceConnectionId() {
		return clone(retrySourceConnectionId);
	}

	/** Exact encoded length: varint id + varint length + value per present parameter. */
	public int encodedLength() {
		int length = 0;
		length += encodedBytesParameterLength(ID_ORIGINAL_DESTINATION_CONNECTION_ID, originalDestinationConnectionId);
		length += encodedVarIntParameterLength(ID_MAX_IDLE_TIMEOUT, maxIdleTimeout);
		length += encodedBytesParameterLength(ID_STATELESS_RESET_TOKEN, statelessResetToken);
		length += encodedVarIntParameterLength(ID_MAX_UDP_PAYLOAD_SIZE, maxUdpPayloadSize);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_DATA, initialMaxData);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, initialMaxStreamDataBidiLocal);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, initialMaxStreamDataBidiRemote);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_STREAM_DATA_UNI, initialMaxStreamDataUni);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_STREAMS_BIDI, initialMaxStreamsBidi);
		length += encodedVarIntParameterLength(ID_INITIAL_MAX_STREAMS_UNI, initialMaxStreamsUni);
		length += encodedVarIntParameterLength(ID_ACK_DELAY_EXPONENT, ackDelayExponent);
		length += encodedVarIntParameterLength(ID_MAX_ACK_DELAY, maxAckDelay);
		if (disableActiveMigration) {
			length += QuicVarInts.encodedLength(ID_DISABLE_ACTIVE_MIGRATION) + 1;
		}
		length += encodedBytesParameterLength(ID_PREFERRED_ADDRESS, preferredAddress);
		length += encodedVarIntParameterLength(ID_ACTIVE_CONNECTION_ID_LIMIT, activeConnectionIdLimit);
		length += encodedBytesParameterLength(ID_INITIAL_SOURCE_CONNECTION_ID, initialSourceConnectionId);
		length += encodedBytesParameterLength(ID_RETRY_SOURCE_CONNECTION_ID, retrySourceConnectionId);
		if (maxDatagramFrameSize > 0) {
			length += encodedVarIntParameterLength(ID_MAX_DATAGRAM_FRAME_SIZE, maxDatagramFrameSize);
		}
		return length;
	}

	/**
	 * Writes all present parameters in ascending-id order, without role validation.
	 * The extension wrapper ({@link QuicTransportParametersExt}) uses this form; engines use
	 * {@link #writeTo(ByteBuf, boolean)} so mandatory parameters are enforced per role.
	 * <p>
	 * {@code max_datagram_frame_size} is the one varint parameter written <b>conditionally</b>, unlike
	 * every other one here. RFC 9221 §3 gives absence and 0 the same meaning — "DATAGRAM frames are not
	 * supported" — so a 0 is omitted rather than emitted, which is what leaves an endpoint that never
	 * enabled datagrams byte-for-byte where it was before the parameter existed.
	 */
	public void writeTo(ByteBuf out) {
		writeBytesParameter(out, ID_ORIGINAL_DESTINATION_CONNECTION_ID, originalDestinationConnectionId);
		writeVarIntParameter(out, ID_MAX_IDLE_TIMEOUT, maxIdleTimeout);
		writeBytesParameter(out, ID_STATELESS_RESET_TOKEN, statelessResetToken);
		writeVarIntParameter(out, ID_MAX_UDP_PAYLOAD_SIZE, maxUdpPayloadSize);
		writeVarIntParameter(out, ID_INITIAL_MAX_DATA, initialMaxData);
		writeVarIntParameter(out, ID_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, initialMaxStreamDataBidiLocal);
		writeVarIntParameter(out, ID_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, initialMaxStreamDataBidiRemote);
		writeVarIntParameter(out, ID_INITIAL_MAX_STREAM_DATA_UNI, initialMaxStreamDataUni);
		writeVarIntParameter(out, ID_INITIAL_MAX_STREAMS_BIDI, initialMaxStreamsBidi);
		writeVarIntParameter(out, ID_INITIAL_MAX_STREAMS_UNI, initialMaxStreamsUni);
		writeVarIntParameter(out, ID_ACK_DELAY_EXPONENT, ackDelayExponent);
		writeVarIntParameter(out, ID_MAX_ACK_DELAY, maxAckDelay);
		if (disableActiveMigration) {
			QuicVarInts.write(out, ID_DISABLE_ACTIVE_MIGRATION);
			QuicVarInts.write(out, 0);
		}
		writeBytesParameter(out, ID_PREFERRED_ADDRESS, preferredAddress);
		writeVarIntParameter(out, ID_ACTIVE_CONNECTION_ID_LIMIT, activeConnectionIdLimit);
		writeBytesParameter(out, ID_INITIAL_SOURCE_CONNECTION_ID, initialSourceConnectionId);
		writeBytesParameter(out, ID_RETRY_SOURCE_CONNECTION_ID, retrySourceConnectionId);
		if (maxDatagramFrameSize > 0) {
			writeVarIntParameter(out, ID_MAX_DATAGRAM_FRAME_SIZE, maxDatagramFrameSize);
		}
	}

	/**
	 * Writes all present parameters, enforcing the RFC 9000 §18.2 mandatory identification
	 * parameters: {@code initial_source_connection_id} for both roles and, for a server
	 * ({@code server == true}), {@code original_destination_connection_id}.
	 *
	 * @throws IllegalStateException if a parameter mandatory for the role is absent (caller bug)
	 */
	public void writeTo(ByteBuf out, boolean server) {
		if (initialSourceConnectionId == null) {
			throw new IllegalStateException("initial_source_connection_id is mandatory (RFC 9000 §18.2)");
		}
		if (server && originalDestinationConnectionId == null) {
			throw new IllegalStateException(
				"original_destination_connection_id is mandatory for a server (RFC 9000 §18.2)");
		}
		writeTo(out);
	}

	/**
	 * Reads parameters from {@code in} until it is exhausted. Absent parameters get their
	 * RFC 9000 §18.2 defaults; unknown ids are skipped (RFC 9000 §18.1).
	 * <p>
	 * Decode is syntactic only: the RFC 9000 §18.2 mandatory identification parameters
	 * ({@code initial_source_connection_id} for both roles, plus the role rules) are NOT
	 * enforced here — the connection layer (feature 03) MUST reject a peer's parameters that
	 * lack them before use.
	 *
	 * @throws MalformedDataException on a duplicate id, a declared length exceeding the
	 * remaining bytes, a wrongly-sized fixed value, or trailing bytes in a varint value
	 */
	public static QuicTransportParameters read(ByteBuf in) throws TruncatedDataException, MalformedDataException {
		byte[] originalDestinationConnectionId = null;
		long maxIdleTimeout = 0;
		byte[] statelessResetToken = null;
		long maxUdpPayloadSize = DEFAULT_MAX_UDP_PAYLOAD_SIZE;
		long initialMaxData = 0;
		long initialMaxStreamDataBidiLocal = 0;
		long initialMaxStreamDataBidiRemote = 0;
		long initialMaxStreamDataUni = 0;
		long initialMaxStreamsBidi = 0;
		long initialMaxStreamsUni = 0;
		long ackDelayExponent = DEFAULT_ACK_DELAY_EXPONENT;
		long maxAckDelay = DEFAULT_MAX_ACK_DELAY;
		boolean disableActiveMigration = false;
		byte[] preferredAddress = null;
		long activeConnectionIdLimit = DEFAULT_ACTIVE_CONNECTION_ID_LIMIT;
		byte[] initialSourceConnectionId = null;
		byte[] retrySourceConnectionId = null;
		long maxDatagramFrameSize = 0;

		Set<Long> seen = new HashSet<>();
		while (in.canRead()) {
			long id = QuicVarInts.read(in);
			long valueLength = QuicVarInts.read(in);
			if (valueLength > in.readRemaining()) {
				throw new MalformedDataException(
					"Transport parameter 0x" + Long.toHexString(id) + " declares " + valueLength +
					" value bytes with " + in.readRemaining() + " remaining");
			}
			if (!seen.add(id)) {
				throw new DuplicateTransportParameterException(id);
			}
			int len = (int) valueLength;
			int knownId = id <= ID_MAX_DATAGRAM_FRAME_SIZE ? (int) id : -1;
			switch (knownId) {
				case (int) ID_ORIGINAL_DESTINATION_CONNECTION_ID -> originalDestinationConnectionId = readBytes(in, len);
				case (int) ID_MAX_IDLE_TIMEOUT -> maxIdleTimeout = readVarIntValue(in, len, id);
				case (int) ID_STATELESS_RESET_TOKEN -> {
					if (len != STATELESS_RESET_TOKEN_LENGTH) {
						throw new MalformedDataException(
							"stateless_reset_token must be " + STATELESS_RESET_TOKEN_LENGTH + " bytes: " + len);
					}
					statelessResetToken = readBytes(in, len);
				}
				case (int) ID_MAX_UDP_PAYLOAD_SIZE -> maxUdpPayloadSize = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_DATA -> initialMaxData = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL -> initialMaxStreamDataBidiLocal = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE -> initialMaxStreamDataBidiRemote = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_STREAM_DATA_UNI -> initialMaxStreamDataUni = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_STREAMS_BIDI -> initialMaxStreamsBidi = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_MAX_STREAMS_UNI -> initialMaxStreamsUni = readVarIntValue(in, len, id);
				case (int) ID_ACK_DELAY_EXPONENT -> ackDelayExponent = readVarIntValue(in, len, id);
				case (int) ID_MAX_ACK_DELAY -> maxAckDelay = readVarIntValue(in, len, id);
				case (int) ID_DISABLE_ACTIVE_MIGRATION -> {
					if (len != 0) {
						throw new MalformedDataException("disable_active_migration must be zero-length: " + len);
					}
					disableActiveMigration = true;
				}
				case (int) ID_PREFERRED_ADDRESS -> preferredAddress = readBytes(in, len);
				case (int) ID_ACTIVE_CONNECTION_ID_LIMIT -> activeConnectionIdLimit = readVarIntValue(in, len, id);
				case (int) ID_INITIAL_SOURCE_CONNECTION_ID -> initialSourceConnectionId = readBytes(in, len);
				case (int) ID_RETRY_SOURCE_CONNECTION_ID -> retrySourceConnectionId = readBytes(in, len);
				case (int) ID_MAX_DATAGRAM_FRAME_SIZE -> maxDatagramFrameSize = readVarIntValue(in, len, id);
				default -> in.moveHead(len); // unknown id: tolerated, skipped (RFC 9000 §18.1)
			}
		}
		return new QuicTransportParameters(
			originalDestinationConnectionId, maxIdleTimeout, statelessResetToken, maxUdpPayloadSize,
			initialMaxData, initialMaxStreamDataBidiLocal, initialMaxStreamDataBidiRemote, initialMaxStreamDataUni,
			initialMaxStreamsBidi, initialMaxStreamsUni, ackDelayExponent, maxAckDelay,
			disableActiveMigration, preferredAddress, activeConnectionIdLimit,
			initialSourceConnectionId, retrySourceConnectionId, maxDatagramFrameSize);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof QuicTransportParameters other)) return false;
		return maxIdleTimeout == other.maxIdleTimeout
			&& maxUdpPayloadSize == other.maxUdpPayloadSize
			&& initialMaxData == other.initialMaxData
			&& initialMaxStreamDataBidiLocal == other.initialMaxStreamDataBidiLocal
			&& initialMaxStreamDataBidiRemote == other.initialMaxStreamDataBidiRemote
			&& initialMaxStreamDataUni == other.initialMaxStreamDataUni
			&& initialMaxStreamsBidi == other.initialMaxStreamsBidi
			&& initialMaxStreamsUni == other.initialMaxStreamsUni
			&& ackDelayExponent == other.ackDelayExponent
			&& maxAckDelay == other.maxAckDelay
			&& disableActiveMigration == other.disableActiveMigration
			&& activeConnectionIdLimit == other.activeConnectionIdLimit
			&& maxDatagramFrameSize == other.maxDatagramFrameSize
			&& Arrays.equals(originalDestinationConnectionId, other.originalDestinationConnectionId)
			&& Arrays.equals(statelessResetToken, other.statelessResetToken)
			&& Arrays.equals(preferredAddress, other.preferredAddress)
			&& Arrays.equals(initialSourceConnectionId, other.initialSourceConnectionId)
			&& Arrays.equals(retrySourceConnectionId, other.retrySourceConnectionId);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(
			maxIdleTimeout, maxUdpPayloadSize, initialMaxData,
			initialMaxStreamDataBidiLocal, initialMaxStreamDataBidiRemote, initialMaxStreamDataUni,
			initialMaxStreamsBidi, initialMaxStreamsUni, ackDelayExponent, maxAckDelay,
			disableActiveMigration, activeConnectionIdLimit, maxDatagramFrameSize)
			+ Arrays.hashCode(originalDestinationConnectionId)
			+ Arrays.hashCode(statelessResetToken)
			+ Arrays.hashCode(preferredAddress)
			+ Arrays.hashCode(initialSourceConnectionId)
			+ Arrays.hashCode(retrySourceConnectionId);
	}

	// ---- encode helpers ----

	private static int encodedVarIntParameterLength(long id, long value) {
		return QuicVarInts.encodedLength(id) + QuicVarInts.encodedLength(QuicVarInts.encodedLength(value))
			+ QuicVarInts.encodedLength(value);
	}

	private static int encodedBytesParameterLength(long id, byte @Nullable [] value) {
		if (value == null) return 0;
		return QuicVarInts.encodedLength(id) + QuicVarInts.encodedLength(value.length) + value.length;
	}

	private static void writeVarIntParameter(ByteBuf out, long id, long value) {
		QuicVarInts.write(out, id);
		QuicVarInts.write(out, QuicVarInts.encodedLength(value));
		QuicVarInts.write(out, value);
	}

	private static void writeBytesParameter(ByteBuf out, long id, byte @Nullable [] value) {
		if (value == null) return;
		QuicVarInts.write(out, id);
		QuicVarInts.write(out, value.length);
		out.put(value);
	}

	// ---- decode helpers ----

	private static byte[] readBytes(ByteBuf in, int len) {
		byte[] bytes = new byte[len];
		in.read(bytes);
		return bytes;
	}

	/**
	 * Reads a varint-valued parameter from exactly {@code len} bytes; the varint must consume
	 * the whole declared value (RFC 9000 §18.1 defines the value as a single varint).
	 */
	private static long readVarIntValue(ByteBuf in, int len, long id) throws TruncatedDataException, MalformedDataException {
		byte[] valueBytes = readBytes(in, len);
		ByteBuf value = ByteBuf.wrapForReading(valueBytes);
		long result = QuicVarInts.read(value);
		if (value.canRead()) {
			throw new MalformedDataException(
				"Transport parameter 0x" + Long.toHexString(id) + " varint value has trailing bytes");
		}
		return result;
	}

	private static byte @Nullable [] clone(byte @Nullable [] bytes) {
		return bytes == null ? null : bytes.clone();
	}

	/**
	 * A duplicate transport parameter (RFC 9000 §18.1: a transport-parameter error). A distinct
	 * subtype so the handshake engines can surface it unchanged — the connection layer maps it
	 * to a TRANSPORT_PARAMETER_ERROR CONNECTION_CLOSE (RFC 9000 §10.1), not to a TLS alert —
	 * while generic parse failures become {@code decode_error}. Still a
	 * {@link MalformedDataException}, so raw-codec callers see no API change.
	 */
	public static final class DuplicateTransportParameterException extends MalformedDataException {
		/** @param id the RFC 9000 §18 identifier of the parameter seen twice */
		public DuplicateTransportParameterException(long id) {
			super("Duplicate transport parameter: 0x" + Long.toHexString(id));
		}
	}

	private static void checkVarIntRange(String name, long value) {
		if (value < 0 || value > QuicVarInts.MAX_VALUE) {
			throw new IllegalArgumentException(name + " out of QUIC varint range [0, 2^62-1]: " + value);
		}
	}
}
