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

import io.activej.common.ApplicationSettings;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;

import java.time.Duration;

/**
 * The tunable limits of an HTTP/3 server or client: every bound that protects an endpoint against a
 * peer that sends too much or waits too long (SI-1, SI-2, SI-3).
 * <p>
 * Immutable, built through a one-shot {@link Builder} (DI-4). Every default is
 * {@link ApplicationSettings}-backed and resolved once at class-initialization time (DI-5), so each
 * bound is safe by default and overridable without configuration code:
 * {@code -Dio.activej.http3.Http3Settings.<setting>} or the short form
 * {@code -DHttp3Settings.<setting>}.
 * <p>
 * Every bound here is enforced <b>unconditionally</b> by the layers that hold the corresponding
 * state — never behind a {@code Checks.isEnabled} guard, because production runs with checks off
 * (FR-009a, WI-10, SI-1).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114">RFC 9114 — HTTP/3</a>
 */
public final class Http3Settings {
	public static final MemSize DEFAULT_MAX_FIELD_SECTION_SIZE =
		ApplicationSettings.getMemSize(Http3Settings.class, "maxFieldSectionSize", MemSize.kilobytes(64));
	public static final MemSize DEFAULT_MAX_BODY_SIZE =
		ApplicationSettings.getMemSize(Http3Settings.class, "maxBodySize", MemSize.megabytes(100));
	public static final MemSize DEFAULT_MAX_CONTROL_FRAME_SIZE =
		ApplicationSettings.getMemSize(Http3Settings.class, "maxControlFrameSize", MemSize.kilobytes(16));
	public static final int DEFAULT_MAX_CONCURRENT_REQUEST_STREAMS =
		ApplicationSettings.getInt(Http3Settings.class, "maxConcurrentRequestStreams", 100);
	public static final int DEFAULT_MAX_CONNECTIONS =
		ApplicationSettings.getInt(Http3Settings.class, "maxConnections", 256);
	public static final int DEFAULT_MAX_QUEUED_REQUESTS =
		ApplicationSettings.getInt(Http3Settings.class, "maxQueuedRequests", 100);
	public static final Duration DEFAULT_REQUEST_TIMEOUT =
		ApplicationSettings.getDuration(Http3Settings.class, "requestTimeout", Duration.ofSeconds(60));

	/**
	 * How long {@code Http3Server.close()} lets the exchanges it announced in its GOAWAY drain before it
	 * closes the connections carrying them anyway (FR-019).
	 * <p>
	 * One second: long enough for a response already on its way to be acknowledged, short enough that
	 * {@code close()} is a close rather than a wait on a peer that may never answer. It is a
	 * <b>ceiling</b>, not a delay — the connections go the moment the last announced exchange finishes,
	 * which is the ordinary case. A server whose requests routinely run longer raises it; {@code 0}
	 * closes at once, announcing GOAWAY without waiting for anything.
	 */
	public static final Duration DEFAULT_SHUTDOWN_TIMEOUT =
		ApplicationSettings.getDuration(Http3Settings.class, "shutdownTimeout", Duration.ofSeconds(1));

	/**
	 * The advertised {@code initial_max_streams_uni} transport parameter value: exactly the control
	 * stream plus both QPACK streams a conforming peer may open (RFC 9114 §6.2). A stated constant
	 * rather than a builder field — FR-017 requires it never be an open-ended "at least 3", since
	 * every unidirectional stream a peer opens costs state even when immediately abandoned for an
	 * unknown type (FR-015). Raising it is this {@code ApplicationSettings} override, not a
	 * {@link Builder} default.
	 */
	public static final int MAX_UNI_STREAMS =
		ApplicationSettings.getInt(Http3Settings.class, "maxUniStreams", 3);

	/** Fixed: this feature never builds a QPACK dynamic table (RFC 9204 §5). */
	public static final int QPACK_MAX_TABLE_CAPACITY = 0;

	/** Fixed: with {@link #QPACK_MAX_TABLE_CAPACITY} at 0, no field line can ever block (RFC 9204 §2.1.2). */
	public static final int QPACK_BLOCKED_STREAMS = 0;

	private final long maxFieldSectionSize;
	private final long maxBodySize;
	private final long maxControlFrameSize;
	private final int maxConcurrentRequestStreams;
	private final int maxConnections;
	private final int maxQueuedRequests;
	private final long requestTimeoutMillis;
	private final long shutdownTimeoutMillis;

	private Http3Settings(
		long maxFieldSectionSize, long maxBodySize, long maxControlFrameSize,
		int maxConcurrentRequestStreams, int maxConnections, int maxQueuedRequests, long requestTimeoutMillis,
		long shutdownTimeoutMillis
	) {
		this.maxFieldSectionSize = maxFieldSectionSize;
		this.maxBodySize = maxBodySize;
		this.maxControlFrameSize = maxControlFrameSize;
		this.maxConcurrentRequestStreams = maxConcurrentRequestStreams;
		this.maxConnections = maxConnections;
		this.maxQueuedRequests = maxQueuedRequests;
		this.requestTimeoutMillis = requestTimeoutMillis;
		this.shutdownTimeoutMillis = shutdownTimeoutMillis;
	}

	public static Http3Settings create() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder extends AbstractBuilder<Builder, Http3Settings> {
		private long maxFieldSectionSize = DEFAULT_MAX_FIELD_SECTION_SIZE.toLong();
		private long maxBodySize = DEFAULT_MAX_BODY_SIZE.toLong();
		private long maxControlFrameSize = DEFAULT_MAX_CONTROL_FRAME_SIZE.toLong();
		private int maxConcurrentRequestStreams = DEFAULT_MAX_CONCURRENT_REQUEST_STREAMS;
		private int maxConnections = DEFAULT_MAX_CONNECTIONS;
		private int maxQueuedRequests = DEFAULT_MAX_QUEUED_REQUESTS;
		private long requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT.toMillis();
		private long shutdownTimeoutMillis = DEFAULT_SHUTDOWN_TIMEOUT.toMillis();

		private Builder() {}

		/**
		 * Bounds the RFC 9114 §4.2.2 accounted field-section size, checked on decoded output as produced.
		 * At least 1 byte and at most {@link Integer#MAX_VALUE}; {@link #build()} refuses anything else.
		 */
		public Builder withMaxFieldSectionSize(MemSize maxFieldSectionSize) {
			checkNotBuilt(this);
			this.maxFieldSectionSize = maxFieldSectionSize.toLong();
			return this;
		}

		/**
		 * Bounds total DATA payload per message. At most {@link Integer#MAX_VALUE}, like
		 * {@link #withMaxFieldSectionSize} and for the same reason; 0 accepts no body at all.
		 */
		public Builder withMaxBodySize(MemSize maxBodySize) {
			checkNotBuilt(this);
			this.maxBodySize = maxBodySize.toLong();
			return this;
		}

		/** Bounds SETTINGS / GOAWAY / control-stream frames. */
		public Builder withMaxControlFrameSize(MemSize maxControlFrameSize) {
			checkNotBuilt(this);
			this.maxControlFrameSize = maxControlFrameSize.toLong();
			return this;
		}

		/** Advertised as the QUIC bidirectional-stream transport parameter (FR-046). */
		public Builder withMaxConcurrentRequestStreams(int maxConcurrentRequestStreams) {
			checkNotBuilt(this);
			this.maxConcurrentRequestStreams = maxConcurrentRequestStreams;
			return this;
		}

		/** {@code Http3Client} pool size; the least-recently-used idle connection is evicted past it (FR-049). */
		public Builder withMaxConnections(int maxConnections) {
			checkNotBuilt(this);
			this.maxConnections = maxConnections;
			return this;
		}

		/** Requests waiting for stream credit; overflow fails immediately with a retryable error (FR-050). */
		public Builder withMaxQueuedRequests(int maxQueuedRequests) {
			checkNotBuilt(this);
			this.maxQueuedRequests = maxQueuedRequests;
			return this;
		}

		/** Per request on both sides, queued time included (FR-046a, FR-052). */
		public Builder withRequestTimeout(Duration requestTimeout) {
			checkNotBuilt(this);
			this.requestTimeoutMillis = requestTimeout.toMillis();
			return this;
		}

		/** The ceiling on the GOAWAY drain of {@code Http3Server.close()}; 0 closes at once (FR-019). */
		public Builder withShutdownTimeout(Duration shutdownTimeout) {
			checkNotBuilt(this);
			this.shutdownTimeoutMillis = shutdownTimeout.toMillis();
			return this;
		}

		@Override
		protected Http3Settings doBuild() {
			if (maxFieldSectionSize < 1) {
				throw new IllegalArgumentException("maxFieldSectionSize (" + maxFieldSectionSize + ") must be at least 1");
			}
			// Both of these end up as a request stream's maxFrameSize, and Http3FrameReader allocates a
			// validated declared length as an int: a bound above 2^31-1 would let a length through that
			// wraps negative on the way to ByteBufPool.allocate, instead of being refused as excessive load.
			if (maxFieldSectionSize > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"maxFieldSectionSize (" + maxFieldSectionSize + ") must not exceed " + Integer.MAX_VALUE);
			}
			if (maxBodySize < 0) {
				throw new IllegalArgumentException("maxBodySize (" + maxBodySize + ") must not be negative");
			}
			if (maxBodySize > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"maxBodySize (" + maxBodySize + ") must not exceed " + Integer.MAX_VALUE);
			}
			if (maxControlFrameSize < 1) {
				throw new IllegalArgumentException("maxControlFrameSize (" + maxControlFrameSize + ") must be at least 1");
			}
			if (maxConcurrentRequestStreams < 1) {
				throw new IllegalArgumentException(
					"maxConcurrentRequestStreams (" + maxConcurrentRequestStreams + ") must be at least 1");
			}
			if (maxConnections < 1) {
				throw new IllegalArgumentException("maxConnections (" + maxConnections + ") must be at least 1");
			}
			if (maxQueuedRequests < 0) {
				throw new IllegalArgumentException("maxQueuedRequests (" + maxQueuedRequests + ") must not be negative");
			}
			if (requestTimeoutMillis < 0) {
				throw new IllegalArgumentException(
					"requestTimeout (" + requestTimeoutMillis + " ms) must not be negative; 0 disables it");
			}
			if (shutdownTimeoutMillis < 0) {
				throw new IllegalArgumentException(
					"shutdownTimeout (" + shutdownTimeoutMillis + " ms) must not be negative; 0 closes at once");
			}
			return new Http3Settings(
				maxFieldSectionSize, maxBodySize, maxControlFrameSize,
				maxConcurrentRequestStreams, maxConnections, maxQueuedRequests, requestTimeoutMillis,
				shutdownTimeoutMillis);
		}
	}

	/** RFC 9114 §4.2.2 accounted field-section size bound: Σ (len(name) + len(value) + 32). */
	public long maxFieldSectionSize() {
		return maxFieldSectionSize;
	}

	public long maxBodySize() {
		return maxBodySize;
	}

	public long maxControlFrameSize() {
		return maxControlFrameSize;
	}

	public int maxConcurrentRequestStreams() {
		return maxConcurrentRequestStreams;
	}

	/** Fixed at {@link #MAX_UNI_STREAMS} — not a per-instance builder field; see FR-017. */
	public int maxUniStreams() {
		return MAX_UNI_STREAMS;
	}

	public int maxConnections() {
		return maxConnections;
	}

	public int maxQueuedRequests() {
		return maxQueuedRequests;
	}

	/** 0 disables the request timeout. */
	public long requestTimeoutMillis() {
		return requestTimeoutMillis;
	}

	/**
	 * The ceiling on how long a graceful shutdown waits for the exchanges it announced to finish; 0
	 * closes at once (FR-019).
	 */
	public long shutdownTimeoutMillis() {
		return shutdownTimeoutMillis;
	}

	/** Fixed at {@link #QPACK_MAX_TABLE_CAPACITY} — this feature never builds a QPACK dynamic table. */
	public int qpackMaxTableCapacity() {
		return QPACK_MAX_TABLE_CAPACITY;
	}

	/** Fixed at {@link #QPACK_BLOCKED_STREAMS}. */
	public int qpackBlockedStreams() {
		return QPACK_BLOCKED_STREAMS;
	}

	@Override
	public String toString() {
		return "Http3Settings{" +
			"maxFieldSectionSize=" + maxFieldSectionSize +
			", maxBodySize=" + maxBodySize +
			", maxControlFrameSize=" + maxControlFrameSize +
			", maxConcurrentRequestStreams=" + maxConcurrentRequestStreams +
			", maxUniStreams=" + MAX_UNI_STREAMS +
			", maxConnections=" + maxConnections +
			", maxQueuedRequests=" + maxQueuedRequests +
			", requestTimeout=" + requestTimeoutMillis + "ms" +
			", shutdownTimeout=" + shutdownTimeoutMillis + "ms" +
			'}';
	}
}
