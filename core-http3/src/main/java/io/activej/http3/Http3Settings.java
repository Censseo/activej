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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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

	/**
	 * How many informational ({@code 1xx}) responses a client reads past on one exchange before the
	 * server has said enough. RFC 9114 §4.1 puts no number on it, and each one is a field section this
	 * side decodes and throws away — so without a bound a server could hold a request stream open, and a
	 * caller's promise unresolved, for as long as it cared to keep sending 3-byte HEADERS frames (SI-3).
	 * <p>
	 * Eight: {@code 103 Early Hints} in practice arrives once, occasionally twice, and a
	 * {@code 100 Continue} ahead of it makes three. The bound exists to be far above the legitimate case
	 * and far below an unbounded one.
	 */
	public static final int DEFAULT_MAX_INTERIM_RESPONSES =
		ApplicationSettings.getInt(Http3Settings.class, "maxInterimResponses", 8);
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

	/**
	 * The QPACK dynamic-table capacity this endpoint advertises, in bytes (RFC 9204 §5).
	 * {@code 0} disables the dynamic table, which is the phase-1 behaviour and the phase-2 default
	 * (FR-089, research D-10): enabling it costs a table per direction per connection.
	 */
	public static final int DEFAULT_QPACK_MAX_TABLE_CAPACITY =
		ApplicationSettings.getInt(Http3Settings.class, "qpackMaxTableCapacity", 0);

	/**
	 * How many request streams this endpoint is willing to hold blocked on a dynamic-table insertion it
	 * has not received yet (RFC 9204 §2.1.2). Enough to keep a browser's parallel requests unblocked;
	 * the memory it bounds is {@code qpackBlockedStreams × maxFieldSectionSize} = 1 MB at the 64 KB
	 * field-section default (FR-089).
	 * <p>
	 * This is the <b>configured</b> value, not necessarily the advertised one: with
	 * {@link #DEFAULT_QPACK_MAX_TABLE_CAPACITY} at 0 no field line can ever block, so
	 * {@code Http3Connection} advertises 0 rather than a permission that cannot be used — which is
	 * also what keeps the default SETTINGS frame byte-for-byte what phase 1 sent (SC-011).
	 */
	public static final int DEFAULT_QPACK_BLOCKED_STREAMS =
		ApplicationSettings.getInt(Http3Settings.class, "qpackBlockedStreams", 16);

	/**
	 * The field names the QPACK encoder never inserts into the dynamic table nor references from it,
	 * emitting them as never-indexed literals instead (RFC 9204 §7.1, FR-022).
	 * <p>
	 * Configured as a comma-separated list and compared case-insensitively — every name is trimmed and
	 * lowercased with {@link Locale#ROOT} here and at {@link Builder#withQpackNeverIndexedFields}, since
	 * a field name on the wire is lowercase by RFC 9114 §4.1.1. The resulting set is immutable (DI-5).
	 * <p>
	 * The default is the credential-bearing fields that are not worth compressing.
	 * {@code Cookie} is deliberately <b>absent</b>: it is the largest repeated field in browser traffic
	 * and the main reason the dynamic table pays for itself. A consumer with a compression-oracle threat
	 * model adds it with one builder call. An empty property value never-indexes nothing at all.
	 */
	public static final Set<String> DEFAULT_QPACK_NEVER_INDEXED_FIELDS = parseFieldNames(
		ApplicationSettings.getString(Http3Settings.class, "qpackNeverIndexedFields",
			"authorization,proxy-authorization,set-cookie"));

	/**
	 * Bounds one buffered QPACK encoder- or decoder-stream instruction, which arrives a few bytes at a
	 * time and must be held until complete (FR-028). Matches {@link #DEFAULT_MAX_CONTROL_FRAME_SIZE},
	 * the other per-instruction bound on a critical stream.
	 */
	public static final MemSize DEFAULT_QPACK_MAX_INSTRUCTION_SIZE =
		ApplicationSettings.getMemSize(Http3Settings.class, "qpackMaxInstructionSize", MemSize.kilobytes(16));

	/**
	 * How long a field section blocked on a not-yet-received dynamic-table insertion is held before the
	 * connection gives up on it (FR-036); {@code 0} disables the timeout. A peer that blocks a stream
	 * and never sends the insertion must not hold this side's memory for the life of the connection.
	 */
	public static final Duration DEFAULT_QPACK_BLOCKED_STREAM_TIMEOUT =
		ApplicationSettings.getDuration(Http3Settings.class, "qpackBlockedStreamTimeout", Duration.ofSeconds(10));

	/**
	 * Whether TLS 1.3 session resumption with 0-RTT early data is offered (client) or accepted
	 * (server). Off by default: early data is replayable by anyone who captured it, so it is opted into
	 * rather than out of (FR-089, Constitution III).
	 */
	public static final boolean DEFAULT_ZERO_RTT_ENABLED =
		ApplicationSettings.getBoolean(Http3Settings.class, "zeroRttEnabled", false);

	/**
	 * Whether HTTP/3 datagrams (RFC 9297 over the RFC 9221 QUIC DATAGRAM frame) are supported. Off by
	 * default: while false, {@code SETTINGS_H3_DATAGRAM} and {@code max_datagram_frame_size} are not
	 * advertised and no datagram queue is allocated (FR-089).
	 */
	public static final boolean DEFAULT_DATAGRAMS_ENABLED =
		ApplicationSettings.getBoolean(Http3Settings.class, "datagramsEnabled", false);

	/**
	 * The per-exchange inbound datagram queue depth; the oldest is dropped past it, because a datagram
	 * is unreliable by contract and dropping the newest would deliver a stale one in its place
	 * (FR-085). {@code 0} accepts none.
	 */
	public static final int DEFAULT_MAX_INBOUND_DATAGRAMS_PER_STREAM =
		ApplicationSettings.getInt(Http3Settings.class, "maxInboundDatagramsPerStream", 32);

	private final long maxFieldSectionSize;
	private final long maxBodySize;
	private final long maxControlFrameSize;
	private final int maxConcurrentRequestStreams;
	private final int maxConnections;
	private final int maxQueuedRequests;
	private final int maxInterimResponses;
	private final long requestTimeoutMillis;
	private final long shutdownTimeoutMillis;
	private final int qpackMaxTableCapacity;
	private final int qpackBlockedStreams;
	private final Set<String> qpackNeverIndexedFields;
	private final long qpackMaxInstructionSize;
	private final long qpackBlockedStreamTimeoutMillis;
	private final boolean zeroRttEnabled;
	private final boolean datagramsEnabled;
	private final int maxInboundDatagramsPerStream;

	@SuppressWarnings("java:S107") // one parameter per bound; the Builder is the only caller
	private Http3Settings(
		long maxFieldSectionSize, long maxBodySize, long maxControlFrameSize,
		int maxConcurrentRequestStreams, int maxConnections, int maxQueuedRequests, int maxInterimResponses,
		long requestTimeoutMillis, long shutdownTimeoutMillis,
		int qpackMaxTableCapacity, int qpackBlockedStreams, Set<String> qpackNeverIndexedFields,
		long qpackMaxInstructionSize, long qpackBlockedStreamTimeoutMillis,
		boolean zeroRttEnabled, boolean datagramsEnabled, int maxInboundDatagramsPerStream
	) {
		this.maxFieldSectionSize = maxFieldSectionSize;
		this.maxBodySize = maxBodySize;
		this.maxControlFrameSize = maxControlFrameSize;
		this.maxConcurrentRequestStreams = maxConcurrentRequestStreams;
		this.maxConnections = maxConnections;
		this.maxQueuedRequests = maxQueuedRequests;
		this.maxInterimResponses = maxInterimResponses;
		this.requestTimeoutMillis = requestTimeoutMillis;
		this.shutdownTimeoutMillis = shutdownTimeoutMillis;
		this.qpackMaxTableCapacity = qpackMaxTableCapacity;
		this.qpackBlockedStreams = qpackBlockedStreams;
		this.qpackNeverIndexedFields = qpackNeverIndexedFields;
		this.qpackMaxInstructionSize = qpackMaxInstructionSize;
		this.qpackBlockedStreamTimeoutMillis = qpackBlockedStreamTimeoutMillis;
		this.zeroRttEnabled = zeroRttEnabled;
		this.datagramsEnabled = datagramsEnabled;
		this.maxInboundDatagramsPerStream = maxInboundDatagramsPerStream;
	}

	/**
	 * Splits a comma-separated field-name list into an immutable, lowercased set. Blank entries are
	 * dropped, so an empty configured value yields an empty set rather than a set holding {@code ""}.
	 */
	private static Set<String> parseFieldNames(String commaSeparated) {
		Set<String> names = new HashSet<>();
		for (String name : commaSeparated.split(",")) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed.toLowerCase(Locale.ROOT));
			}
		}
		return Set.copyOf(names);
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
		private int maxInterimResponses = DEFAULT_MAX_INTERIM_RESPONSES;
		private long requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT.toMillis();
		private long shutdownTimeoutMillis = DEFAULT_SHUTDOWN_TIMEOUT.toMillis();
		private long qpackMaxTableCapacity = DEFAULT_QPACK_MAX_TABLE_CAPACITY;
		private int qpackBlockedStreams = DEFAULT_QPACK_BLOCKED_STREAMS;
		private Set<String> qpackNeverIndexedFields = DEFAULT_QPACK_NEVER_INDEXED_FIELDS;
		private long qpackMaxInstructionSize = DEFAULT_QPACK_MAX_INSTRUCTION_SIZE.toLong();
		private long qpackBlockedStreamTimeoutMillis = DEFAULT_QPACK_BLOCKED_STREAM_TIMEOUT.toMillis();
		private boolean zeroRttEnabled = DEFAULT_ZERO_RTT_ENABLED;
		private boolean datagramsEnabled = DEFAULT_DATAGRAMS_ENABLED;
		private int maxInboundDatagramsPerStream = DEFAULT_MAX_INBOUND_DATAGRAMS_PER_STREAM;

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

		/**
		 * Bounds SETTINGS / GOAWAY / control-stream frames. At least 1 byte and at most
		 * {@link Integer#MAX_VALUE}, like {@link #withMaxFieldSectionSize} and for the same reason.
		 */
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

		/**
		 * Informational ({@code 1xx}) responses a client reads past on one exchange (RFC 9114 §4.1);
		 * past it the exchange fails with {@code H3_EXCESSIVE_LOAD}. 0 accepts none, and turns any
		 * interim response into that failure.
		 */
		public Builder withMaxInterimResponses(int maxInterimResponses) {
			checkNotBuilt(this);
			this.maxInterimResponses = maxInterimResponses;
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

		/**
		 * The QPACK dynamic-table capacity advertised to the peer (RFC 9204 §5); a non-zero value is the
		 * single call that enables the dynamic table, 0 disables it (FR-089). At most
		 * {@link Integer#MAX_VALUE}, since it bounds an allocation sized as an {@code int}.
		 */
		public Builder withQpackMaxTableCapacity(MemSize qpackMaxTableCapacity) {
			checkNotBuilt(this);
			this.qpackMaxTableCapacity = qpackMaxTableCapacity.toLong();
			return this;
		}

		/**
		 * Request streams this endpoint permits to be blocked on a not-yet-received dynamic-table
		 * insertion (RFC 9204 §2.1.2, FR-034); 0 never blocks. Advertised only while the dynamic table is
		 * enabled — with a capacity of 0 nothing can block, so 0 is advertised whatever this says.
		 */
		public Builder withQpackBlockedStreams(int qpackBlockedStreams) {
			checkNotBuilt(this);
			this.qpackBlockedStreams = qpackBlockedStreams;
			return this;
		}

		/**
		 * Field names the encoder never indexes, emitting them as never-indexed literals (FR-022).
		 * Names are trimmed and lowercased at {@link #build()}, and the set is copied — the caller's
		 * remains its own. An empty set never-indexes nothing.
		 */
		public Builder withQpackNeverIndexedFields(Set<String> qpackNeverIndexedFields) {
			checkNotBuilt(this);
			this.qpackNeverIndexedFields = qpackNeverIndexedFields;
			return this;
		}

		/**
		 * Bounds one buffered QPACK encoder- or decoder-stream instruction (FR-028). At least 1 byte and
		 * at most {@link Integer#MAX_VALUE}, like {@link #withMaxControlFrameSize} and for the same reason.
		 */
		public Builder withQpackMaxInstructionSize(MemSize qpackMaxInstructionSize) {
			checkNotBuilt(this);
			this.qpackMaxInstructionSize = qpackMaxInstructionSize.toLong();
			return this;
		}

		/** How long a blocked field section is held before the connection gives up; 0 disables it (FR-036). */
		public Builder withQpackBlockedStreamTimeout(Duration qpackBlockedStreamTimeout) {
			checkNotBuilt(this);
			this.qpackBlockedStreamTimeoutMillis = qpackBlockedStreamTimeout.toMillis();
			return this;
		}

		/**
		 * Offers (client) or accepts (server) 0-RTT early data; off by default for replay risk (FR-089).
		 * <p>
		 * Turning it on brings the server-side early-data policy with it (FR-064): a method that is not
		 * safe per RFC 9110 §9.2.1 is answered {@code 425 (Too Early)} without ever reaching the servlet,
		 * and a request that is accepted carries the RFC 8470 {@code Early-Data: 1} indication where the
		 * servlet can read it. See {@code Http3Server.Builder.withEarlyDataPolicy} — including for why
		 * that default is not merely advisory behind a load balancer.
		 */
		public Builder withZeroRttEnabled(boolean zeroRttEnabled) {
			checkNotBuilt(this);
			this.zeroRttEnabled = zeroRttEnabled;
			return this;
		}

		/**
		 * Supports HTTP/3 datagrams (RFC 9297); while false nothing is advertised and no queue is
		 * allocated (FR-089).
		 */
		public Builder withDatagramsEnabled(boolean datagramsEnabled) {
			checkNotBuilt(this);
			this.datagramsEnabled = datagramsEnabled;
			return this;
		}

		/** The per-exchange inbound datagram queue depth, oldest dropped past it; 0 accepts none (FR-085). */
		public Builder withMaxInboundDatagramsPerStream(int maxInboundDatagramsPerStream) {
			checkNotBuilt(this);
			this.maxInboundDatagramsPerStream = maxInboundDatagramsPerStream;
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
			// The control stream's reader casts the same validated declared length to an int as a request
			// stream's does, so this bound needs the same ceiling as the two above: past it the cast wraps
			// negative, no length ever passes the check, and the control stream stalls without a word.
			if (maxControlFrameSize > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"maxControlFrameSize (" + maxControlFrameSize + ") must not exceed " + Integer.MAX_VALUE);
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
			if (maxInterimResponses < 0) {
				throw new IllegalArgumentException(
					"maxInterimResponses (" + maxInterimResponses + ") must not be negative; 0 accepts none");
			}
			if (requestTimeoutMillis < 0) {
				throw new IllegalArgumentException(
					"requestTimeout (" + requestTimeoutMillis + " ms) must not be negative; 0 disables it");
			}
			if (shutdownTimeoutMillis < 0) {
				throw new IllegalArgumentException(
					"shutdownTimeout (" + shutdownTimeoutMillis + " ms) must not be negative; 0 closes at once");
			}
			if (qpackMaxTableCapacity < 0) {
				throw new IllegalArgumentException(
					"qpackMaxTableCapacity (" + qpackMaxTableCapacity + ") must not be negative; 0 disables the dynamic table");
			}
			// Advertised as a varint, but it bounds a table this side allocates and indexes as an int:
			// a capacity above 2^31-1 would wrap negative on the way to the allocator rather than be
			// refused here, where the configuration can still be corrected.
			if (qpackMaxTableCapacity > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"qpackMaxTableCapacity (" + qpackMaxTableCapacity + ") must not exceed " + Integer.MAX_VALUE);
			}
			if (qpackBlockedStreams < 0) {
				throw new IllegalArgumentException(
					"qpackBlockedStreams (" + qpackBlockedStreams + ") must not be negative; 0 never blocks");
			}
			if (qpackNeverIndexedFields == null) {
				throw new IllegalArgumentException("qpackNeverIndexedFields must not be null; an empty set never-indexes nothing");
			}
			for (String fieldName : qpackNeverIndexedFields) {
				if (fieldName == null || fieldName.isBlank()) {
					throw new IllegalArgumentException("qpackNeverIndexedFields must not contain a blank field name");
				}
			}
			if (qpackMaxInstructionSize < 1) {
				throw new IllegalArgumentException(
					"qpackMaxInstructionSize (" + qpackMaxInstructionSize + ") must be at least 1");
			}
			// The same ceiling as the three frame-size bounds above, for the same reason: a buffered
			// instruction's declared length reaches an int-sized check.
			if (qpackMaxInstructionSize > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
					"qpackMaxInstructionSize (" + qpackMaxInstructionSize + ") must not exceed " + Integer.MAX_VALUE);
			}
			if (qpackBlockedStreamTimeoutMillis < 0) {
				throw new IllegalArgumentException(
					"qpackBlockedStreamTimeout (" + qpackBlockedStreamTimeoutMillis + " ms) must not be negative; 0 disables it");
			}
			if (maxInboundDatagramsPerStream < 0) {
				throw new IllegalArgumentException(
					"maxInboundDatagramsPerStream (" + maxInboundDatagramsPerStream + ") must not be negative; 0 accepts none");
			}
			return new Http3Settings(
				maxFieldSectionSize, maxBodySize, maxControlFrameSize,
				maxConcurrentRequestStreams, maxConnections, maxQueuedRequests, maxInterimResponses,
				requestTimeoutMillis, shutdownTimeoutMillis,
				(int) qpackMaxTableCapacity, qpackBlockedStreams, normalizeFieldNames(qpackNeverIndexedFields),
				qpackMaxInstructionSize, qpackBlockedStreamTimeoutMillis,
				zeroRttEnabled, datagramsEnabled, maxInboundDatagramsPerStream);
		}

		/** Lowercased and copied, so a field name compares case-insensitively and the caller's set stays its own. */
		private static Set<String> normalizeFieldNames(Set<String> fieldNames) {
			Set<String> normalized = new HashSet<>(fieldNames.size());
			for (String fieldName : fieldNames) {
				normalized.add(fieldName.toLowerCase(Locale.ROOT));
			}
			return Set.copyOf(normalized);
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

	/** Informational (1xx) responses read past on one exchange; 0 accepts none (RFC 9114 §4.1). */
	public int maxInterimResponses() {
		return maxInterimResponses;
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

	/**
	 * The QPACK dynamic-table capacity, in bytes; {@code 0} disables the dynamic table (RFC 9204 §5).
	 * Defaults to {@link #DEFAULT_QPACK_MAX_TABLE_CAPACITY}.
	 */
	public int qpackMaxTableCapacity() {
		return qpackMaxTableCapacity;
	}

	/**
	 * How many request streams this endpoint permits to be blocked on a not-yet-received dynamic-table
	 * insertion (RFC 9204 §2.1.2); {@code 0} never blocks. Defaults to
	 * {@link #DEFAULT_QPACK_BLOCKED_STREAMS}.
	 * <p>
	 * This is the <b>configured</b> value. What goes on the wire is 0 whenever
	 * {@link #qpackMaxTableCapacity()} is 0, since with no dynamic table nothing can block.
	 */
	public int qpackBlockedStreams() {
		return qpackBlockedStreams;
	}

	/**
	 * The lowercased, immutable set of field names the encoder never indexes (FR-022). Empty
	 * never-indexes nothing.
	 */
	public Set<String> qpackNeverIndexedFields() {
		return qpackNeverIndexedFields;
	}

	/** Bounds one buffered QPACK encoder- or decoder-stream instruction, in bytes (FR-028). */
	public long qpackMaxInstructionSize() {
		return qpackMaxInstructionSize;
	}

	/** How long a blocked field section is held before the connection gives up; 0 disables it (FR-036). */
	public long qpackBlockedStreamTimeoutMillis() {
		return qpackBlockedStreamTimeoutMillis;
	}

	/**
	 * Whether 0-RTT early data is offered (client) or accepted (server). Note that
	 * {@code ApplicationSettings} reads an <b>empty</b> property value as {@code true}, so
	 * {@code -DHttp3Settings.zeroRttEnabled} with no value enables it.
	 */
	public boolean zeroRttEnabled() {
		return zeroRttEnabled;
	}

	/**
	 * Whether HTTP/3 datagrams are supported (RFC 9297). Note that {@code ApplicationSettings} reads an
	 * <b>empty</b> property value as {@code true}, so {@code -DHttp3Settings.datagramsEnabled} with no
	 * value enables them.
	 */
	public boolean datagramsEnabled() {
		return datagramsEnabled;
	}

	/** The per-exchange inbound datagram queue depth, oldest dropped past it; 0 accepts none (FR-085). */
	public int maxInboundDatagramsPerStream() {
		return maxInboundDatagramsPerStream;
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
			", qpackMaxTableCapacity=" + qpackMaxTableCapacity +
			", qpackBlockedStreams=" + qpackBlockedStreams +
			", qpackNeverIndexedFields=" + qpackNeverIndexedFields +
			", qpackMaxInstructionSize=" + qpackMaxInstructionSize +
			", qpackBlockedStreamTimeout=" + qpackBlockedStreamTimeoutMillis + "ms" +
			", zeroRttEnabled=" + zeroRttEnabled +
			", datagramsEnabled=" + datagramsEnabled +
			", maxInboundDatagramsPerStream=" + maxInboundDatagramsPerStream +
			'}';
	}
}
