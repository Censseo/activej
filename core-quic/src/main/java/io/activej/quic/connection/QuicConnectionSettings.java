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
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-14">RFC 9000 §14 — Datagram Size</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-10.1">RFC 9000 §10.1 — Idle Timeout</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-7.2">RFC 9002 §7.2 — Initial and Minimum Congestion Window</a>
 */
public final class QuicConnectionSettings {
	/**
	 * The smallest datagram an endpoint must be able to carry once the handshake is under way
	 * (RFC 9000 §14.1). No configuration may go below it.
	 */
	public static final int MIN_MAX_DATAGRAM_SIZE = 1200;

	/** The largest UDP payload representable in the {@code max_udp_payload_size} parameter (RFC 9000 §18.2). */
	public static final int MAX_MAX_DATAGRAM_SIZE = 65527;

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

	private QuicConnectionSettings(
		int maxDatagramSize, long maxIdleTimeoutMillis, long handshakeTimeoutMillis, int maxAckRanges,
		long maxCryptoBufferBytes, long maxSendQueueBytes, long initialCongestionWindow,
		int maxBufferedDatagramsAwaitingKeys, int connectionIdLength, @Nullable Long keepAliveIntervalMillis
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
				throw new IllegalArgumentException(
					"maxIdleTimeout (" + maxIdleTimeoutMillis + " ms) must not be negative; 0 disables it (RFC 9000 §18.2)");
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
				connectionIdLength, keepAliveIntervalMillis);
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
			'}';
	}
}
