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
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-14">RFC 9000 §14 — Datagram Size</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10.1">RFC 9000 §10.1 — Idle Timeout</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7.2">RFC 9002 §7.2 — Initial and Minimum Congestion Window</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-18.2">RFC 9000 §18.2 — Transport Parameter Definitions</a>
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

	private QuicConnectionSettings(
		int maxDatagramSize, long maxIdleTimeoutMillis, long handshakeTimeoutMillis, int maxAckRanges,
		long maxCryptoBufferBytes, long maxSendQueueBytes, long initialCongestionWindow,
		int maxBufferedDatagramsAwaitingKeys, int connectionIdLength, @Nullable Long keepAliveIntervalMillis,
		long initialMaxData, long initialMaxStreamDataBidiLocal, long initialMaxStreamDataBidiRemote,
		long initialMaxStreamDataUni, long initialMaxStreamsBidi, long initialMaxStreamsUni,
		long maxOutstandingStreamBytes, int maxReceiveRangesPerStream, int maxPendingStreamOpens
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

			return new QuicConnectionSettings(
				maxDatagramSize, maxIdleTimeoutMillis, handshakeTimeoutMillis, maxAckRanges,
				maxCryptoBufferBytes, maxSendQueueBytes, cwnd, maxBufferedDatagramsAwaitingKeys,
				connectionIdLength, keepAliveIntervalMillis, initialMaxData, initialMaxStreamDataBidiLocal,
				initialMaxStreamDataBidiRemote, initialMaxStreamDataUni, initialMaxStreamsBidi,
				initialMaxStreamsUni, maxOutstandingStreamBytes, maxReceiveRangesPerStream,
				maxPendingStreamOpens);
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
			'}';
	}
}
