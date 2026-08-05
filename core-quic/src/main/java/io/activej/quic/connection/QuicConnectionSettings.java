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

import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.quic.QuicConnectionId;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * The tunable limits of a single QUIC connection: datagram size, timeouts, and every bound that
 * protects the endpoint against a peer that sends too much (SI-1, SI-2, SI-3).
 * <p>
 * Immutable, built through a one-shot {@link Builder} (DI-4). Every default is
 * {@link ApplicationSettings}-backed and resolved once at class-initialization time (DI-5), so each
 * bound is safe by default and overridable without configuration code.
 * <p>
 * The system-property namespace is {@link QuicConnection}, not this class — {@code
 * -Dio.activej.quic.connection.QuicConnection.maxDatagramSize=1350} or the short form
 * {@code -DQuicConnection.maxDatagramSize=1350}. The settings describe a connection; the class that
 * merely carries them would be a confusing thing to name in a deployment's configuration.
 * <p>
 * Endpoint-wide limits — {@code maxConnections} and {@code maxHandshakingConnections} — are
 * deliberately <b>not</b> here: they belong to the endpoint that owns the socket, not to a single
 * connection.
 * <p>
 * Six of these limits leave the process: {@code initialMaxData}, the three {@code initialMaxStreamData*}
 * and the two {@code initialMaxStreams*} are advertised to the peer as RFC 9000 §18.2 transport
 * parameters, so raising one hands a remote peer more room. The three stream bounds that stay local —
 * {@code maxOutstandingStreamBytes}, {@code maxReceiveRangesPerStream}, {@code maxPendingStreamOpens} —
 * never appear on the wire.
 * <p>
 * The eight session-resumption bounds are local state in the same sense: RFC 8446 §4.6.1 puts a
 * ticket's lifetime, the keys that seal it and the registers that bound it nowhere on the wire.
 * {@code maxDatagramFrameSize} is the one limit here destined to leave the process, as the RFC 9221 §3
 * {@code max_datagram_frame_size} transport parameter; it defaults to {@code 0}, "DATAGRAM not
 * supported", and nothing encodes it yet — a value set here advertises nothing until the datagram
 * layer is wired behind it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-14">RFC 9000 §14 — Datagram Size</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10.1">RFC 9000 §10.1 — Idle Timeout</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7.2">RFC 9002 §7.2 — Initial and Minimum Congestion Window</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18.2">RFC 9000 §18.2 — Transport Parameter Definitions</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.6.1">RFC 8446 §4.6.1 — New Session Ticket Message</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
 */
public final class QuicConnectionSettings {
	/**
	 * The smallest datagram an endpoint must be able to carry once the handshake is under way
	 * (RFC 9000 §14.1). No configuration may go below it.
	 */
	public static final int MIN_MAX_DATAGRAM_SIZE = 1200;

	/** The largest UDP payload representable in the {@code max_udp_payload_size} parameter (RFC 9000 §18.2). */
	public static final int MAX_MAX_DATAGRAM_SIZE = 65527;

	/**
	 * RFC 9000 §4.6 / §18.2: no stream count may exceed 2^60, since an ordinal occupies the 60 bits above
	 * the two type bits of a 62-bit stream identifier. Duplicated from {@code stream.StreamCounter}
	 * rather than shared, because {@code connection} must not depend on {@code stream} (ADR-016) — this
	 * package validates the value it advertises, the stream layer validates the peer's.
	 */
	private static final long MAX_STREAM_COUNT = 1L << 60;

	/**
	 * A 1-RTT packet's cost beyond its frames, at its widest (RFC 9000 §17.3): the first byte, a 20-byte
	 * destination connection ID, a four-byte packet number and the AEAD tag. The <b>worst</b> case rather
	 * than any particular connection's actual overhead. Duplicated from {@code stream.QuicStreamManager}
	 * rather than shared, because {@code connection} must not depend on {@code stream} (ADR-016) — the
	 * same reason {@link #MAX_STREAM_COUNT} is duplicated above.
	 */
	private static final int MAX_SHORT_HEADER_PACKET_OVERHEAD = 1 + 20 + 4 + 16;

	public static final MemSize DEFAULT_MAX_DATAGRAM_SIZE =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxDatagramSize", MemSize.bytes(1350));
	public static final Duration DEFAULT_MAX_IDLE_TIMEOUT =
		ApplicationSettings.getDuration(QuicConnection.class, "maxIdleTimeout", Duration.ofSeconds(30));
	public static final Duration DEFAULT_HANDSHAKE_TIMEOUT =
		ApplicationSettings.getDuration(QuicConnection.class, "handshakeTimeout", Duration.ofSeconds(10));
	public static final int DEFAULT_MAX_ACK_RANGES =
		ApplicationSettings.getInt(QuicConnection.class, "maxAckRanges", 32);
	public static final MemSize DEFAULT_MAX_CRYPTO_BUFFER_BYTES =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxCryptoBufferBytes", MemSize.kilobytes(64));
	public static final MemSize DEFAULT_MAX_SEND_QUEUE_BYTES =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxSendQueueBytes", MemSize.megabytes(1));
	/** {@code null} means "derive from the RFC 9002 §7.2 formula", which depends on the datagram size. */
	public static final @Nullable MemSize DEFAULT_INITIAL_CONGESTION_WINDOW =
		ApplicationSettings.getMemSize(QuicConnection.class, "initialCongestionWindow", null);
	public static final int DEFAULT_MAX_BUFFERED_DATAGRAMS_AWAITING_KEYS =
		ApplicationSettings.getInt(QuicConnection.class, "maxBufferedDatagramsAwaitingKeys", 4);
	public static final int DEFAULT_CONNECTION_ID_LENGTH =
		ApplicationSettings.getInt(QuicConnection.class, "connectionIdLength", 8);
	/** {@code null} disables keep-alive, which RFC 9000 §10.1.2 makes optional. */
	public static final @Nullable Duration DEFAULT_KEEP_ALIVE_INTERVAL =
		ApplicationSettings.getDuration(QuicConnection.class, "keepAliveInterval", null);

	// The six limits advertised to the peer as QUIC transport parameters (RFC 9000 §18.2).
	public static final MemSize DEFAULT_INITIAL_MAX_DATA =
		ApplicationSettings.getMemSize(QuicConnection.class, "initialMaxData", MemSize.megabytes(1));
	public static final MemSize DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL =
		ApplicationSettings.getMemSize(QuicConnection.class, "initialMaxStreamDataBidiLocal", MemSize.kilobytes(256));
	public static final MemSize DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE =
		ApplicationSettings.getMemSize(QuicConnection.class, "initialMaxStreamDataBidiRemote", MemSize.kilobytes(256));
	public static final MemSize DEFAULT_INITIAL_MAX_STREAM_DATA_UNI =
		ApplicationSettings.getMemSize(QuicConnection.class, "initialMaxStreamDataUni", MemSize.kilobytes(256));
	public static final long DEFAULT_INITIAL_MAX_STREAMS_BIDI =
		ApplicationSettings.getLong(QuicConnection.class, "initialMaxStreamsBidi", 100L);
	public static final long DEFAULT_INITIAL_MAX_STREAMS_UNI =
		ApplicationSettings.getLong(QuicConnection.class, "initialMaxStreamsUni", 3L);

	// Three local-only bounds. Never on the wire — they bound what this endpoint keeps for a stream.
	public static final MemSize DEFAULT_MAX_OUTSTANDING_STREAM_BYTES =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxOutstandingStreamBytes", MemSize.kilobytes(512));
	public static final int DEFAULT_MAX_RECEIVE_RANGES_PER_STREAM =
		ApplicationSettings.getInt(QuicConnection.class, "maxReceiveRangesPerStream", 32);
	public static final int DEFAULT_MAX_PENDING_STREAM_OPENS =
		ApplicationSettings.getInt(QuicConnection.class, "maxPendingStreamOpens", 128);

	// Eight session-resumption bounds (RFC 8446 §4.6.1). Every one is local state — a ticket's lifetime,
	// the keys that seal it and the registers that bound it are never on the wire.
	public static final Duration DEFAULT_SESSION_TICKET_LIFETIME =
		ApplicationSettings.getDuration(QuicConnection.class, "sessionTicketLifetime", Duration.ofHours(1));
	public static final Duration DEFAULT_SESSION_TICKET_KEY_ROTATION =
		ApplicationSettings.getDuration(QuicConnection.class, "sessionTicketKeyRotation", Duration.ofHours(6));
	public static final int DEFAULT_SESSION_TICKETS_PER_HANDSHAKE =
		ApplicationSettings.getInt(QuicConnection.class, "sessionTicketsPerHandshake", 2);
	public static final int DEFAULT_MAX_SESSION_TICKETS =
		ApplicationSettings.getInt(QuicConnection.class, "maxSessionTickets", 256);
	public static final int DEFAULT_MAX_EARLY_DATA_REPLAY_RECORDS =
		ApplicationSettings.getInt(QuicConnection.class, "maxEarlyDataReplayRecords", 65536);
	public static final Duration DEFAULT_TICKET_AGE_TOLERANCE =
		ApplicationSettings.getDuration(QuicConnection.class, "ticketAgeTolerance", Duration.ofSeconds(10));
	public static final MemSize DEFAULT_MAX_SESSION_TICKET_SIZE =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxSessionTicketSize", MemSize.kilobytes(8));
	public static final int DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION =
		ApplicationSettings.getInt(QuicConnection.class, "maxSessionTicketsPerConnection", 8);

	// Two unreliable-datagram bounds (RFC 9221 §3). A maxDatagramFrameSize of 0 means "DATAGRAM not
	// supported", which is the default: max_datagram_frame_size is then not advertised at all.
	public static final MemSize DEFAULT_MAX_DATAGRAM_FRAME_SIZE =
		ApplicationSettings.getMemSize(QuicConnection.class, "maxDatagramFrameSize", MemSize.ZERO);
	public static final int DEFAULT_MAX_OUTBOUND_DATAGRAMS =
		ApplicationSettings.getInt(QuicConnection.class, "maxOutboundDatagrams", 64);

	private final int maxDatagramSize;
	private final long maxIdleTimeoutMillis;
	private final long handshakeTimeoutMillis;
	private final int maxAckRanges;
	private final long maxCryptoBufferBytes;
	private final long maxSendQueueBytes;
	private final long initialCongestionWindow;
	private final int maxBufferedDatagramsAwaitingKeys;
	private final int connectionIdLength;
	private final @Nullable Long keepAliveIntervalMillis;
	private final long initialMaxData;
	private final long initialMaxStreamDataBidiLocal;
	private final long initialMaxStreamDataBidiRemote;
	private final long initialMaxStreamDataUni;
	private final long initialMaxStreamsBidi;
	private final long initialMaxStreamsUni;
	private final long maxOutstandingStreamBytes;
	private final int maxReceiveRangesPerStream;
	private final int maxPendingStreamOpens;
	private final long sessionTicketLifetimeMillis;
	private final long sessionTicketKeyRotationMillis;
	private final int sessionTicketsPerHandshake;
	private final int maxSessionTickets;
	private final int maxEarlyDataReplayRecords;
	private final long ticketAgeToleranceMillis;
	private final long maxSessionTicketSize;
	private final int maxSessionTicketsPerConnection;
	private final long maxDatagramFrameSize;
	private final int maxOutboundDatagrams;

	private QuicConnectionSettings(
		int maxDatagramSize, long maxIdleTimeoutMillis, long handshakeTimeoutMillis, int maxAckRanges,
		long maxCryptoBufferBytes, long maxSendQueueBytes, long initialCongestionWindow,
		int maxBufferedDatagramsAwaitingKeys, int connectionIdLength, @Nullable Long keepAliveIntervalMillis,
		long initialMaxData, long initialMaxStreamDataBidiLocal, long initialMaxStreamDataBidiRemote,
		long initialMaxStreamDataUni, long initialMaxStreamsBidi, long initialMaxStreamsUni,
		long maxOutstandingStreamBytes, int maxReceiveRangesPerStream, int maxPendingStreamOpens,
		long sessionTicketLifetimeMillis, long sessionTicketKeyRotationMillis, int sessionTicketsPerHandshake,
		int maxSessionTickets, int maxEarlyDataReplayRecords, long ticketAgeToleranceMillis,
		long maxSessionTicketSize, int maxSessionTicketsPerConnection, long maxDatagramFrameSize,
		int maxOutboundDatagrams
	) {
		this.maxDatagramSize = maxDatagramSize;
		this.maxIdleTimeoutMillis = maxIdleTimeoutMillis;
		this.handshakeTimeoutMillis = handshakeTimeoutMillis;
		this.maxAckRanges = maxAckRanges;
		this.maxCryptoBufferBytes = maxCryptoBufferBytes;
		this.maxSendQueueBytes = maxSendQueueBytes;
		this.initialCongestionWindow = initialCongestionWindow;
		this.maxBufferedDatagramsAwaitingKeys = maxBufferedDatagramsAwaitingKeys;
		this.connectionIdLength = connectionIdLength;
		this.keepAliveIntervalMillis = keepAliveIntervalMillis;
		this.initialMaxData = initialMaxData;
		this.initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal;
		this.initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote;
		this.initialMaxStreamDataUni = initialMaxStreamDataUni;
		this.initialMaxStreamsBidi = initialMaxStreamsBidi;
		this.initialMaxStreamsUni = initialMaxStreamsUni;
		this.maxOutstandingStreamBytes = maxOutstandingStreamBytes;
		this.maxReceiveRangesPerStream = maxReceiveRangesPerStream;
		this.maxPendingStreamOpens = maxPendingStreamOpens;
		this.sessionTicketLifetimeMillis = sessionTicketLifetimeMillis;
		this.sessionTicketKeyRotationMillis = sessionTicketKeyRotationMillis;
		this.sessionTicketsPerHandshake = sessionTicketsPerHandshake;
		this.maxSessionTickets = maxSessionTickets;
		this.maxEarlyDataReplayRecords = maxEarlyDataReplayRecords;
		this.ticketAgeToleranceMillis = ticketAgeToleranceMillis;
		this.maxSessionTicketSize = maxSessionTicketSize;
		this.maxSessionTicketsPerConnection = maxSessionTicketsPerConnection;
		this.maxDatagramFrameSize = maxDatagramFrameSize;
		this.maxOutboundDatagrams = maxOutboundDatagrams;
	}

	public static QuicConnectionSettings create() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The RFC 9002 §7.2 initial congestion window:
	 * {@code min(10 × maxDatagramSize, max(14720, 2 × maxDatagramSize))}.
	 */
	public static long initialCongestionWindowFor(int maxDatagramSize) {
		return Math.min(10L * maxDatagramSize, Math.max(14720L, 2L * maxDatagramSize));
	}

	/**
	 * The largest DATAGRAM frame that still fits one packet at {@code maxDatagramSize} (RFC 9221 §3) —
	 * what {@code max_datagram_frame_size} should be set to when a consumer enables datagrams without
	 * naming a size (FR-089).
	 * <p>
	 * RFC 9221 §3 measures the parameter over the <b>whole frame</b> — the type byte, the optional
	 * length field and the payload — so the derivation is the datagram allowance minus what a 1-RTT
	 * packet costs around its frames. That cost is taken at its widest, for the same reason the stream
	 * layer takes it that way: advertising a frame size a real packet cannot carry turns into a send
	 * refused at run time, whereas under-advertising costs a few bytes per datagram.
	 * <p>
	 * Floored at {@code 0}, which is the RFC 9221 §3 encoding of "DATAGRAM not supported".
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
	 */
	public static long maxDatagramFrameSizeFor(int maxDatagramSize) {
		return Math.max(0L, (long) maxDatagramSize - MAX_SHORT_HEADER_PACKET_OVERHEAD);
	}

	public static final class Builder extends AbstractBuilder<Builder, QuicConnectionSettings> {
		private int maxDatagramSize = DEFAULT_MAX_DATAGRAM_SIZE.toInt();
		private long maxIdleTimeoutMillis = DEFAULT_MAX_IDLE_TIMEOUT.toMillis();
		private long handshakeTimeoutMillis = DEFAULT_HANDSHAKE_TIMEOUT.toMillis();
		private int maxAckRanges = DEFAULT_MAX_ACK_RANGES;
		private long maxCryptoBufferBytes = DEFAULT_MAX_CRYPTO_BUFFER_BYTES.toLong();
		private long maxSendQueueBytes = DEFAULT_MAX_SEND_QUEUE_BYTES.toLong();
		private @Nullable Long initialCongestionWindow =
			DEFAULT_INITIAL_CONGESTION_WINDOW == null ? null : DEFAULT_INITIAL_CONGESTION_WINDOW.toLong();
		private int maxBufferedDatagramsAwaitingKeys = DEFAULT_MAX_BUFFERED_DATAGRAMS_AWAITING_KEYS;
		private int connectionIdLength = DEFAULT_CONNECTION_ID_LENGTH;
		private @Nullable Long keepAliveIntervalMillis =
			DEFAULT_KEEP_ALIVE_INTERVAL == null ? null : DEFAULT_KEEP_ALIVE_INTERVAL.toMillis();
		private long initialMaxData = DEFAULT_INITIAL_MAX_DATA.toLong();
		private long initialMaxStreamDataBidiLocal = DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL.toLong();
		private long initialMaxStreamDataBidiRemote = DEFAULT_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE.toLong();
		private long initialMaxStreamDataUni = DEFAULT_INITIAL_MAX_STREAM_DATA_UNI.toLong();
		private long initialMaxStreamsBidi = DEFAULT_INITIAL_MAX_STREAMS_BIDI;
		private long initialMaxStreamsUni = DEFAULT_INITIAL_MAX_STREAMS_UNI;
		private long maxOutstandingStreamBytes = DEFAULT_MAX_OUTSTANDING_STREAM_BYTES.toLong();
		private int maxReceiveRangesPerStream = DEFAULT_MAX_RECEIVE_RANGES_PER_STREAM;
		private int maxPendingStreamOpens = DEFAULT_MAX_PENDING_STREAM_OPENS;
		private long sessionTicketLifetimeMillis = DEFAULT_SESSION_TICKET_LIFETIME.toMillis();
		private long sessionTicketKeyRotationMillis = DEFAULT_SESSION_TICKET_KEY_ROTATION.toMillis();
		private int sessionTicketsPerHandshake = DEFAULT_SESSION_TICKETS_PER_HANDSHAKE;
		private int maxSessionTickets = DEFAULT_MAX_SESSION_TICKETS;
		private int maxEarlyDataReplayRecords = DEFAULT_MAX_EARLY_DATA_REPLAY_RECORDS;
		private long ticketAgeToleranceMillis = DEFAULT_TICKET_AGE_TOLERANCE.toMillis();
		private long maxSessionTicketSize = DEFAULT_MAX_SESSION_TICKET_SIZE.toLong();
		private int maxSessionTicketsPerConnection = DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION;
		private long maxDatagramFrameSize = DEFAULT_MAX_DATAGRAM_FRAME_SIZE.toLong();
		private int maxOutboundDatagrams = DEFAULT_MAX_OUTBOUND_DATAGRAMS;

		private Builder() {}

		public Builder withMaxDatagramSize(MemSize maxDatagramSize) {
			checkNotBuilt(this);
			this.maxDatagramSize = maxDatagramSize.toInt();
			return this;
		}

		public Builder withMaxIdleTimeout(Duration maxIdleTimeout) {
			checkNotBuilt(this);
			this.maxIdleTimeoutMillis = maxIdleTimeout.toMillis();
			return this;
		}

		public Builder withHandshakeTimeout(Duration handshakeTimeout) {
			checkNotBuilt(this);
			this.handshakeTimeoutMillis = handshakeTimeout.toMillis();
			return this;
		}

		public Builder withMaxAckRanges(int maxAckRanges) {
			checkNotBuilt(this);
			this.maxAckRanges = maxAckRanges;
			return this;
		}

		public Builder withMaxCryptoBufferBytes(MemSize maxCryptoBufferBytes) {
			checkNotBuilt(this);
			this.maxCryptoBufferBytes = maxCryptoBufferBytes.toLong();
			return this;
		}

		public Builder withMaxSendQueueBytes(MemSize maxSendQueueBytes) {
			checkNotBuilt(this);
			this.maxSendQueueBytes = maxSendQueueBytes.toLong();
			return this;
		}

		public Builder withInitialCongestionWindow(MemSize initialCongestionWindow) {
			checkNotBuilt(this);
			this.initialCongestionWindow = initialCongestionWindow.toLong();
			return this;
		}

		public Builder withMaxBufferedDatagramsAwaitingKeys(int maxBufferedDatagramsAwaitingKeys) {
			checkNotBuilt(this);
			this.maxBufferedDatagramsAwaitingKeys = maxBufferedDatagramsAwaitingKeys;
			return this;
		}

		public Builder withConnectionIdLength(int connectionIdLength) {
			checkNotBuilt(this);
			this.connectionIdLength = connectionIdLength;
			return this;
		}

		/**
		 * Sends a PING often enough to keep the connection from idling out (RFC 9000 §10.1.2).
		 * Must not exceed half the idle timeout — see the {@link #doBuild()} check (FR-025).
		 */
		public Builder withKeepAliveInterval(Duration keepAliveInterval) {
			checkNotBuilt(this);
			this.keepAliveIntervalMillis = keepAliveInterval.toMillis();
			return this;
		}

		/**
		 * The connection-wide receive credit advertised as {@code initial_max_data} (RFC 9000 §18.2).
		 * Must be at least as large as every per-stream limit — see the {@link #doBuild()} check.
		 */
		public Builder withInitialMaxData(MemSize initialMaxData) {
			checkNotBuilt(this);
			this.initialMaxData = initialMaxData.toLong();
			return this;
		}

		/** Advertised as {@code initial_max_stream_data_bidi_local} (RFC 9000 §18.2). */
		public Builder withInitialMaxStreamDataBidiLocal(MemSize initialMaxStreamDataBidiLocal) {
			checkNotBuilt(this);
			this.initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal.toLong();
			return this;
		}

		/** Advertised as {@code initial_max_stream_data_bidi_remote} (RFC 9000 §18.2). */
		public Builder withInitialMaxStreamDataBidiRemote(MemSize initialMaxStreamDataBidiRemote) {
			checkNotBuilt(this);
			this.initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote.toLong();
			return this;
		}

		/** Advertised as {@code initial_max_stream_data_uni} (RFC 9000 §18.2). */
		public Builder withInitialMaxStreamDataUni(MemSize initialMaxStreamDataUni) {
			checkNotBuilt(this);
			this.initialMaxStreamDataUni = initialMaxStreamDataUni.toLong();
			return this;
		}

		/** How many bidirectional streams the peer may open, advertised as {@code initial_max_streams_bidi}. */
		public Builder withInitialMaxStreamsBidi(long initialMaxStreamsBidi) {
			checkNotBuilt(this);
			this.initialMaxStreamsBidi = initialMaxStreamsBidi;
			return this;
		}

		/** How many unidirectional streams the peer may open, advertised as {@code initial_max_streams_uni}. */
		public Builder withInitialMaxStreamsUni(long initialMaxStreamsUni) {
			checkNotBuilt(this);
			this.initialMaxStreamsUni = initialMaxStreamsUni;
			return this;
		}

		/**
		 * Local only, never on the wire: how many written-but-unacknowledged bytes one stream may hold.
		 * Must stay below {@code maxSendQueueBytes} — see the {@link #doBuild()} check (FR-019).
		 */
		public Builder withMaxOutstandingStreamBytes(MemSize maxOutstandingStreamBytes) {
			checkNotBuilt(this);
			this.maxOutstandingStreamBytes = maxOutstandingStreamBytes.toLong();
			return this;
		}

		/** Local only: the bound on out-of-order receive ranges one stream may track before it is a protocol error. */
		public Builder withMaxReceiveRangesPerStream(int maxReceiveRangesPerStream) {
			checkNotBuilt(this);
			this.maxReceiveRangesPerStream = maxReceiveRangesPerStream;
			return this;
		}

		/** Local only: the bound on stream opens awaiting credit before an open is refused rather than queued. */
		public Builder withMaxPendingStreamOpens(int maxPendingStreamOpens) {
			checkNotBuilt(this);
			this.maxPendingStreamOpens = maxPendingStreamOpens;
			return this;
		}

		/**
		 * Local only: how long a session ticket this endpoint issues stays usable for resumption
		 * (RFC 8446 §4.6.1, which caps it at 7 days). Bounds the replay window a ticket opens.
		 */
		public Builder withSessionTicketLifetime(Duration sessionTicketLifetime) {
			checkNotBuilt(this);
			this.sessionTicketLifetimeMillis = sessionTicketLifetime.toMillis();
			return this;
		}

		/**
		 * Local only: how often the key sealing issued tickets (RFC 8446 §4.6.1) is replaced. Two keys are
		 * retained, so this must be at least half of {@code sessionTicketLifetime} — see the
		 * {@link #doBuild()} check.
		 */
		public Builder withSessionTicketKeyRotation(Duration sessionTicketKeyRotation) {
			checkNotBuilt(this);
			this.sessionTicketKeyRotationMillis = sessionTicketKeyRotation.toMillis();
			return this;
		}

		/** Local only: how many NewSessionTicket messages this endpoint issues per handshake (RFC 8446 §4.6.1); 0 issues none. */
		public Builder withSessionTicketsPerHandshake(int sessionTicketsPerHandshake) {
			checkNotBuilt(this);
			this.sessionTicketsPerHandshake = sessionTicketsPerHandshake;
			return this;
		}

		/** Local only: the entries this endpoint's client-side ticket cache holds before evicting the least recently used (RFC 8446 §4.6.1). */
		public Builder withMaxSessionTickets(int maxSessionTickets) {
			checkNotBuilt(this);
			this.maxSessionTickets = maxSessionTickets;
			return this;
		}

		/**
		 * Local only: the entries the single-use register guarding early-data replay holds (RFC 8446 §8).
		 * An evicted record is treated as <i>used</i>, so the bound refuses early data rather than admitting a replay.
		 */
		public Builder withMaxEarlyDataReplayRecords(int maxEarlyDataReplayRecords) {
			checkNotBuilt(this);
			this.maxEarlyDataReplayRecords = maxEarlyDataReplayRecords;
			return this;
		}

		/** Local only: the clock-skew allowance on the obfuscated ticket-age check (RFC 8446 §4.2.10); 0 allows none. */
		public Builder withTicketAgeTolerance(Duration ticketAgeTolerance) {
			checkNotBuilt(this);
			this.ticketAgeToleranceMillis = ticketAgeTolerance.toMillis();
			return this;
		}

		/** Local only: the bound on one sealed session ticket (RFC 8446 §4.6.1), checked before anything is allocated for it. */
		public Builder withMaxSessionTicketSize(MemSize maxSessionTicketSize) {
			checkNotBuilt(this);
			this.maxSessionTicketSize = maxSessionTicketSize.toLong();
			return this;
		}

		/** Local only: the post-handshake NewSessionTicket messages one connection may deliver before it is a protocol error (RFC 8446 §4.6.1). */
		public Builder withMaxSessionTicketsPerConnection(int maxSessionTicketsPerConnection) {
			checkNotBuilt(this);
			this.maxSessionTicketsPerConnection = maxSessionTicketsPerConnection;
			return this;
		}

		/**
		 * The largest DATAGRAM frame this endpoint is willing to receive, advertised as
		 * {@code max_datagram_frame_size} (RFC 9221 §3). 0 — the default — means DATAGRAM is not supported
		 * and the parameter is not advertised at all. {@link #maxDatagramFrameSizeFor(int)} derives the
		 * largest value that still fits one packet.
		 */
		public Builder withMaxDatagramFrameSize(MemSize maxDatagramFrameSize) {
			checkNotBuilt(this);
			this.maxDatagramFrameSize = maxDatagramFrameSize.toLong();
			return this;
		}

		/** Local only: the outbound datagrams that may await a packet before a send is refused rather than queued (RFC 9221 §5). */
		public Builder withMaxOutboundDatagrams(int maxOutboundDatagrams) {
			checkNotBuilt(this);
			this.maxOutboundDatagrams = maxOutboundDatagrams;
			return this;
		}

		@Override
		protected QuicConnectionSettings doBuild() {
			if (maxDatagramSize < MIN_MAX_DATAGRAM_SIZE || maxDatagramSize > MAX_MAX_DATAGRAM_SIZE) {
				throw new IllegalArgumentException(
					"maxDatagramSize (" + maxDatagramSize + " bytes) must be between " + MIN_MAX_DATAGRAM_SIZE +
					" and " + MAX_MAX_DATAGRAM_SIZE + " bytes (RFC 9000 §14.1)");
			}
			if (connectionIdLength < 0 || connectionIdLength > QuicConnectionId.MAX_LENGTH) {
				throw new IllegalArgumentException(
					"connectionIdLength (" + connectionIdLength + ") must be between 0 and " +
					QuicConnectionId.MAX_LENGTH + " (RFC 9000 §5.1)");
			}
			if (maxAckRanges < 1) {
				throw new IllegalArgumentException("maxAckRanges (" + maxAckRanges + ") must be at least 1");
			}
			if (maxCryptoBufferBytes < 1) {
				throw new IllegalArgumentException(
					"maxCryptoBufferBytes (" + maxCryptoBufferBytes + ") must be at least 1");
			}
			if (maxSendQueueBytes < maxDatagramSize) {
				throw new IllegalArgumentException(
					"maxSendQueueBytes (" + maxSendQueueBytes + ") must be at least maxDatagramSize (" +
					maxDatagramSize + "), or no datagram could ever be assembled");
			}
			if (handshakeTimeoutMillis <= 0) {
				throw new IllegalArgumentException(
					"handshakeTimeout (" + handshakeTimeoutMillis + " ms) must be positive");
			}
			if (maxIdleTimeoutMillis < 0) {
				throw new IllegalArgumentException("maxIdleTimeout (" + maxIdleTimeoutMillis +
					" ms) must not be negative; 0 disables it (RFC 9000 §18.2)");
			}
			if (maxBufferedDatagramsAwaitingKeys < 0) {
				throw new IllegalArgumentException(
					"maxBufferedDatagramsAwaitingKeys (" + maxBufferedDatagramsAwaitingKeys + ") must not be negative");
			}
			if (keepAliveIntervalMillis != null) {
				if (keepAliveIntervalMillis <= 0) {
					throw new IllegalArgumentException(
						"keepAliveInterval (" + keepAliveIntervalMillis + " ms) must be positive");
				}
				if (maxIdleTimeoutMillis != 0 && keepAliveIntervalMillis * 2 > maxIdleTimeoutMillis) {
					throw new IllegalArgumentException(
						"keepAliveInterval (" + keepAliveIntervalMillis + " ms) must not exceed half of " +
						"maxIdleTimeout (" + maxIdleTimeoutMillis + " ms), i.e. " + (maxIdleTimeoutMillis / 2) + " ms");
				}
			}

			if (initialMaxStreamsBidi < 0 || initialMaxStreamsBidi > MAX_STREAM_COUNT) {
				throw new IllegalArgumentException(
					"initialMaxStreamsBidi (" + initialMaxStreamsBidi + ") must be between 0 and " +
					MAX_STREAM_COUNT + " — a count above 2^60 could name a stream identifier that does not " +
					"exist (RFC 9000 §18.2)");
			}
			if (initialMaxStreamsUni < 0 || initialMaxStreamsUni > MAX_STREAM_COUNT) {
				throw new IllegalArgumentException(
					"initialMaxStreamsUni (" + initialMaxStreamsUni + ") must be between 0 and " +
					MAX_STREAM_COUNT + " — a count above 2^60 could name a stream identifier that does not " +
					"exist (RFC 9000 §18.2)");
			}
			if (maxReceiveRangesPerStream < 1) {
				throw new IllegalArgumentException(
					"maxReceiveRangesPerStream (" + maxReceiveRangesPerStream + ") must be at least 1");
			}
			if (maxPendingStreamOpens < 0) {
				throw new IllegalArgumentException(
					"maxPendingStreamOpens (" + maxPendingStreamOpens + ") must not be negative");
			}
			// The stream layer draws from the same send queue as the connection's own control frames, so a
			// per-stream allowance that reaches the queue's bound is a connection kill, not back-pressure.
			if (maxOutstandingStreamBytes >= maxSendQueueBytes) {
				throw new IllegalArgumentException(
					"maxOutstandingStreamBytes (" + maxOutstandingStreamBytes + ") must be below " +
					"maxSendQueueBytes (" + maxSendQueueBytes + "), or the stream layer can fill the send " +
					"queue and the connection dies with INTERNAL_ERROR on its next control frame");
			}
			long largestStreamData = Math.max(initialMaxStreamDataBidiLocal,
				Math.max(initialMaxStreamDataBidiRemote, initialMaxStreamDataUni));
			if (largestStreamData > initialMaxData) {
				String offender =
					initialMaxStreamDataBidiLocal == largestStreamData ? "initialMaxStreamDataBidiLocal" :
					initialMaxStreamDataBidiRemote == largestStreamData ? "initialMaxStreamDataBidiRemote" :
						"initialMaxStreamDataUni";
				throw new IllegalArgumentException(
					offender + " (" + largestStreamData + ") must not exceed initialMaxData (" + initialMaxData +
					"), or a stream advertises credit the connection window can never honour");
			}

			long cwnd = initialCongestionWindow != null
				? initialCongestionWindow
				: initialCongestionWindowFor(maxDatagramSize);
			if (cwnd < 2L * maxDatagramSize) {
				throw new IllegalArgumentException(
					"initialCongestionWindow (" + cwnd + ") must be at least 2 × maxDatagramSize (" +
					(2L * maxDatagramSize) + "), the RFC 9002 §7.2 minimum window");
			}

			if (sessionTicketLifetimeMillis <= 0) {
				throw new IllegalArgumentException(
					"sessionTicketLifetime (" + sessionTicketLifetimeMillis + " ms) must be positive");
			}
			if (sessionTicketKeyRotationMillis <= 0) {
				throw new IllegalArgumentException(
					"sessionTicketKeyRotation (" + sessionTicketKeyRotationMillis + " ms) must be positive");
			}
			if (ticketAgeToleranceMillis < 0) {
				throw new IllegalArgumentException("ticketAgeTolerance (" + ticketAgeToleranceMillis +
					" ms) must not be negative; 0 allows no clock skew (RFC 8446 §4.2.10)");
			}
			if (sessionTicketsPerHandshake < 0) {
				throw new IllegalArgumentException("sessionTicketsPerHandshake (" + sessionTicketsPerHandshake +
					") must not be negative; 0 issues no ticket (RFC 8446 §4.6.1)");
			}
			if (maxSessionTickets < 0) {
				throw new IllegalArgumentException("maxSessionTickets (" + maxSessionTickets +
					") must not be negative; 0 caches none");
			}
			if (maxEarlyDataReplayRecords < 1) {
				throw new IllegalArgumentException("maxEarlyDataReplayRecords (" + maxEarlyDataReplayRecords +
					") must be at least 1 — the register fails closed, so an empty one refuses every early-data " +
					"attempt rather than admitting one");
			}
			if (maxSessionTicketsPerConnection < 0) {
				throw new IllegalArgumentException(
					"maxSessionTicketsPerConnection (" + maxSessionTicketsPerConnection + ") must not be negative");
			}
			// A peer-declared ticket length is checked against this before anything is allocated for it (SI-4),
			// and that check is against an int, so a bound above Integer.MAX_VALUE could never be reached.
			if (maxSessionTicketSize < 1 || maxSessionTicketSize > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"maxSessionTicketSize (" + maxSessionTicketSize + ") must be between 1 and " +
					Integer.MAX_VALUE + " bytes");
			}
			// Two keys are retained across one rotation, which is what keeps a ticket sealed under the
			// previous key openable (data-model.md §2, QuicTicketKeys). Rotating faster than half the
			// lifetime therefore strands live tickets under a key that no longer exists, and each of them
			// silently degrades to a full handshake — refused here rather than warned about, like the other
			// configurations in this class that cannot work. The subtraction, rather than 2 × rotation,
			// keeps the comparison from overflowing on an absurd Duration.
			if (sessionTicketKeyRotationMillis < sessionTicketLifetimeMillis - sessionTicketKeyRotationMillis) {
				throw new IllegalArgumentException(
					"sessionTicketKeyRotation (" + sessionTicketKeyRotationMillis + " ms) must be at least half " +
					"of sessionTicketLifetime (" + sessionTicketLifetimeMillis + " ms), i.e. " +
					((sessionTicketLifetimeMillis + 1) / 2) + " ms, or a ticket outlives the two keys retained " +
					"across one rotation and can no longer be opened by the endpoint that issued it");
			}

			if (maxDatagramFrameSize < 0 || maxDatagramFrameSize > MAX_MAX_DATAGRAM_SIZE) {
				throw new IllegalArgumentException(
					"maxDatagramFrameSize (" + maxDatagramFrameSize + ") must be between 0 and " +
					MAX_MAX_DATAGRAM_SIZE + " bytes; 0 means DATAGRAM is not supported (RFC 9221 §3)");
			}
			if (maxOutboundDatagrams < 0) {
				throw new IllegalArgumentException(
					"maxOutboundDatagrams (" + maxOutboundDatagrams + ") must not be negative");
			}

			return new QuicConnectionSettings(
				maxDatagramSize, maxIdleTimeoutMillis, handshakeTimeoutMillis, maxAckRanges,
				maxCryptoBufferBytes, maxSendQueueBytes, cwnd, maxBufferedDatagramsAwaitingKeys,
				connectionIdLength, keepAliveIntervalMillis, initialMaxData, initialMaxStreamDataBidiLocal,
				initialMaxStreamDataBidiRemote, initialMaxStreamDataUni, initialMaxStreamsBidi,
				initialMaxStreamsUni, maxOutstandingStreamBytes, maxReceiveRangesPerStream,
				maxPendingStreamOpens, sessionTicketLifetimeMillis, sessionTicketKeyRotationMillis,
				sessionTicketsPerHandshake, maxSessionTickets, maxEarlyDataReplayRecords,
				ticketAgeToleranceMillis, maxSessionTicketSize, maxSessionTicketsPerConnection,
				maxDatagramFrameSize, maxOutboundDatagrams);
		}
	}

	public int maxDatagramSize() {
		return maxDatagramSize;
	}

	/** 0 disables the idle timeout (RFC 9000 §18.2). */
	public long maxIdleTimeoutMillis() {
		return maxIdleTimeoutMillis;
	}

	public long handshakeTimeoutMillis() {
		return handshakeTimeoutMillis;
	}

	public int maxAckRanges() {
		return maxAckRanges;
	}

	public long maxCryptoBufferBytes() {
		return maxCryptoBufferBytes;
	}

	public long maxSendQueueBytes() {
		return maxSendQueueBytes;
	}

	public long initialCongestionWindow() {
		return initialCongestionWindow;
	}

	public int maxBufferedDatagramsAwaitingKeys() {
		return maxBufferedDatagramsAwaitingKeys;
	}

	public int connectionIdLength() {
		return connectionIdLength;
	}

	/** {@code null} when keep-alive is disabled. */
	public @Nullable Long keepAliveIntervalMillis() {
		return keepAliveIntervalMillis;
	}

	/** Advertised as {@code initial_max_data} (RFC 9000 §18.2). */
	public long initialMaxData() {
		return initialMaxData;
	}

	/** Advertised as {@code initial_max_stream_data_bidi_local} (RFC 9000 §18.2). */
	public long initialMaxStreamDataBidiLocal() {
		return initialMaxStreamDataBidiLocal;
	}

	/** Advertised as {@code initial_max_stream_data_bidi_remote} (RFC 9000 §18.2). */
	public long initialMaxStreamDataBidiRemote() {
		return initialMaxStreamDataBidiRemote;
	}

	/** Advertised as {@code initial_max_stream_data_uni} (RFC 9000 §18.2). */
	public long initialMaxStreamDataUni() {
		return initialMaxStreamDataUni;
	}

	/** Advertised as {@code initial_max_streams_bidi} (RFC 9000 §18.2). */
	public long initialMaxStreamsBidi() {
		return initialMaxStreamsBidi;
	}

	/** Advertised as {@code initial_max_streams_uni} (RFC 9000 §18.2). */
	public long initialMaxStreamsUni() {
		return initialMaxStreamsUni;
	}

	/**
	 * Local only: the written-but-unacknowledged bytes one stream may hold. Always below
	 * {@link #maxSendQueueBytes()}.
	 */
	public long maxOutstandingStreamBytes() {
		return maxOutstandingStreamBytes;
	}

	/** Local only: the out-of-order receive ranges one stream may track. */
	public int maxReceiveRangesPerStream() {
		return maxReceiveRangesPerStream;
	}

	/** Local only: the stream opens that may wait for credit before an open is refused. */
	public int maxPendingStreamOpens() {
		return maxPendingStreamOpens;
	}

	/** Local only: how long an issued session ticket stays usable for resumption (RFC 8446 §4.6.1). */
	public long sessionTicketLifetimeMillis() {
		return sessionTicketLifetimeMillis;
	}

	/** Local only: how often the ticket-sealing key is replaced. Always at least half {@link #sessionTicketLifetimeMillis()}. */
	public long sessionTicketKeyRotationMillis() {
		return sessionTicketKeyRotationMillis;
	}

	/** Local only: the NewSessionTicket messages issued per handshake (RFC 8446 §4.6.1); 0 issues none. */
	public int sessionTicketsPerHandshake() {
		return sessionTicketsPerHandshake;
	}

	/** Local only: the entries the client-side ticket cache holds before evicting the least recently used. */
	public int maxSessionTickets() {
		return maxSessionTickets;
	}

	/** Local only: the entries the fail-closed early-data replay register holds (RFC 8446 §8). */
	public int maxEarlyDataReplayRecords() {
		return maxEarlyDataReplayRecords;
	}

	/** Local only: the clock-skew allowance on the obfuscated ticket-age check (RFC 8446 §4.2.10); 0 allows none. */
	public long ticketAgeToleranceMillis() {
		return ticketAgeToleranceMillis;
	}

	/** Local only: the bound on one sealed session ticket, checked before it is allocated for (SI-4). */
	public long maxSessionTicketSize() {
		return maxSessionTicketSize;
	}

	/** Local only: the post-handshake NewSessionTicket messages one connection may deliver. */
	public int maxSessionTicketsPerConnection() {
		return maxSessionTicketsPerConnection;
	}

	/**
	 * Advertised as {@code max_datagram_frame_size} (RFC 9221 §3). 0 means DATAGRAM is not supported and
	 * the parameter is not advertised at all.
	 */
	public long maxDatagramFrameSize() {
		return maxDatagramFrameSize;
	}

	/** Local only: the outbound datagrams that may await a packet before a send is refused (RFC 9221 §5). */
	public int maxOutboundDatagrams() {
		return maxOutboundDatagrams;
	}

	@Override
	public String toString() {
		return "QuicConnectionSettings{" +
			"maxDatagramSize=" + maxDatagramSize +
			", maxIdleTimeout=" + maxIdleTimeoutMillis + "ms" +
			", handshakeTimeout=" + handshakeTimeoutMillis + "ms" +
			", maxAckRanges=" + maxAckRanges +
			", maxCryptoBufferBytes=" + maxCryptoBufferBytes +
			", maxSendQueueBytes=" + maxSendQueueBytes +
			", initialCongestionWindow=" + initialCongestionWindow +
			", maxBufferedDatagramsAwaitingKeys=" + maxBufferedDatagramsAwaitingKeys +
			", connectionIdLength=" + connectionIdLength +
			", keepAliveInterval=" + (keepAliveIntervalMillis == null ? "disabled" : keepAliveIntervalMillis + "ms") +
			", initialMaxData=" + initialMaxData +
			", initialMaxStreamDataBidiLocal=" + initialMaxStreamDataBidiLocal +
			", initialMaxStreamDataBidiRemote=" + initialMaxStreamDataBidiRemote +
			", initialMaxStreamDataUni=" + initialMaxStreamDataUni +
			", initialMaxStreamsBidi=" + initialMaxStreamsBidi +
			", initialMaxStreamsUni=" + initialMaxStreamsUni +
			", maxOutstandingStreamBytes=" + maxOutstandingStreamBytes +
			", maxReceiveRangesPerStream=" + maxReceiveRangesPerStream +
			", maxPendingStreamOpens=" + maxPendingStreamOpens +
			", sessionTicketLifetime=" + sessionTicketLifetimeMillis + "ms" +
			", sessionTicketKeyRotation=" + sessionTicketKeyRotationMillis + "ms" +
			", sessionTicketsPerHandshake=" + sessionTicketsPerHandshake +
			", maxSessionTickets=" + maxSessionTickets +
			", maxEarlyDataReplayRecords=" + maxEarlyDataReplayRecords +
			", ticketAgeTolerance=" + ticketAgeToleranceMillis + "ms" +
			", maxSessionTicketSize=" + maxSessionTicketSize +
			", maxSessionTicketsPerConnection=" + maxSessionTicketsPerConnection +
			", maxDatagramFrameSize=" + maxDatagramFrameSize +
			", maxOutboundDatagrams=" + maxOutboundDatagrams +
			'}';
	}
}
