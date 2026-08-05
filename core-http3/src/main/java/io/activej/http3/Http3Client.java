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

import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.common.initializer.Initializer;
import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsResponse;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IHttpClient;
import io.activej.http.MalformedHttpException;
import io.activej.http.Protocol;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Connection.QpackBlockedExit;
import io.activej.http3.Http3Connection.QpackTable;
import io.activej.http3.Http3Connection.ZeroRttOutcome;
import io.activej.http3.frame.SettingsFrame;
import io.activej.net.socket.udp.IUdpSocket;
import io.activej.net.socket.udp.UdpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.reactor.AbstractNioReactive;
import io.activej.reactor.net.DatagramSocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * An HTTP/3 client (RFC 9114): an {@link IHttpClient}, so a caller's {@link HttpRequest} /
 * {@link HttpResponse} code is the code it already had (FR-047).
 *
 * <pre>{@code
 * Http3Client client = Http3Client.create(reactor, dnsClient);
 * Promise<HttpResponse> response = client.request(HttpRequest.get("https://example.com/").build());
 * }</pre>
 *
 * <h2>Not a mode of {@code HttpClient}</h2>
 * {@code core-http}'s client is TCP-bound down to its connection pool — a socket per connection, a
 * keep-alive list per address, a request at a time per connection. HTTP/3 has none of those: one UDP
 * socket carries every connection, and a connection carries as many concurrent requests as the peer
 * grants streams. So this is a separate component implementing the same interface (FR-047), which is the
 * substitutability that matters.
 *
 * <h2>One endpoint, one connection per authority</h2>
 * A single {@link QuicEndpoint} on an ephemeral port is shared by every pooled connection (FR-059a),
 * attached through the same {@code withFrameHandlerFactory} seam {@link Http3Server} uses. The pool is
 * keyed by <b>authority</b> — (scheme, host, port) — and concurrent requests racing to the same
 * authority share one in-flight connect promise rather than opening several connections (FR-048).
 *
 * <h2>Bounds</h2>
 * <ul>
 *   <li>{@link Http3Settings#maxConnections()} pooled connections. At the bound the least-recently-used
 *       <b>idle</b> connection is evicted; with every connection busy the request fails immediately with
 *       a <b>retryable</b> error naming the setting key, and the pool never grows (FR-049). <b>Idle</b> is
 *       the strict reading: a connection still streaming a response body into a caller's hands is busy,
 *       because an exchange ends at the response head while the transfer behind it does not.</li>
 *   <li>{@link Http3Settings#maxQueuedRequests()} requests waiting for bidirectional-stream credit. A
 *       request that finds no credit waits — the transport withholds the open and tells the peer with
 *       {@code STREAMS_BLOCKED} — until {@code MAX_STREAMS} grants some; past the bound the request fails
 *       immediately, retryably, naming the key (FR-050).</li>
 *   <li>{@link Http3Settings#requestTimeoutMillis()} per request, <b>queued time included</b>, resetting
 *       the stream with {@code H3_REQUEST_CANCELLED} on expiry (FR-052).</li>
 * </ul>
 *
 * <h2>A connection that has gone away</h2>
 * A GOAWAY on a pooled connection retires it (RFC 9114 §5.2, US7 §2): the next request to that authority
 * opens a fresh connection instead, while the retired one stays open for the requests it is already
 * carrying — the peer named the identifier below which it will still answer, and this client holds it to
 * that. It is closed as soon as it is carrying nothing, so a peer that GOAWAYs every connection it
 * accepts leaves nothing accumulating behind it ({@link #retiredConnectionCount()}). A request that races
 * the announcement, and finds the connection going away as it asks for a stream, fails with a
 * <b>retryable</b> {@code H3_REQUEST_REJECTED} naming the condition, because nothing of it was ever sent.
 *
 * <h2>Ownership</h2>
 * A request handed to {@link #request} is owned by this client, exactly as {@code HttpClient} owns one:
 * every refusal releases its body. The {@link HttpResponse} the promise delivers is the <b>caller's</b>,
 * and so is whatever {@code loadBody()} produces from it — a response whose body is never consumed holds
 * on to whatever the transport had already buffered, until {@link #close()} releases it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114">RFC 9114 — HTTP/3</a>
 */
public final class Http3Client extends AbstractNioReactive implements IHttpClient, AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(Http3Client.class);

	/** The port an {@code https} authority carries implicitly (RFC 9110 §4.2.2). */
	private static final int DEFAULT_HTTPS_PORT = 443;

	/** The only ALPN this client negotiates, and so the only one a session ticket is ever keyed under. */
	private static final String ALPN_H3 = "h3";

	/** RFC 8470 §5.2 — the server declined to process a request that arrived in early data. */
	private static final int TOO_EARLY = 425;

	/**
	 * RFC 9110 §9.2.1's safe methods, the client-side default of FR-068 — deliberately the same set the
	 * default server-side early-data policy accepts, so an ordinary application never provokes a
	 * rejection round trip. Held here rather than on {@code HttpMethod}: safety is an HTTP semantic this
	 * module reads, and `core-http` gains nothing from this feature (contracts/core-http-delta.md).
	 */
	private static final Set<HttpMethod> SAFE_METHODS =
		EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

	/**
	 * The optional statistics hook of FR-062, the client's half of {@link Http3Server.Inspector} and
	 * following the same {@code QuicConnection.Inspector} precedent: an interface declared by the
	 * component, <b>absent by default</b>, that a later module implements to publish JMX statistics
	 * without this module depending on {@code boot-jmx-api}.
	 * <p>
	 * It never <i>gates</i> a counter. Every accessor on this class — {@link #requestsIssued()},
	 * {@link #queuedRequestCount()}, the rest — reads the same value with an inspector attached and
	 * without one; this is an additional notification seam, never a replacement for them.
	 * <p>
	 * <b>Never carries</b> a field value, a body byte, a cookie, an authorization credential or key
	 * material (FR-063): every parameter here is a number, an HTTP method or a direction. A byte
	 * <i>count</i> is not a byte, and no authority string is reported either.
	 * <p>
	 * <b>Threading</b>: every callback runs on this client's reactor thread, inside the operation that
	 * produced the event. An implementation that blocks blocks the reactor, and one that throws fails that
	 * operation — accumulate, never act.
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/** A request stream has been opened and the request is about to go out on it. */
		void onRequestStarted(Http3Client client, long streamId, HttpMethod method);

		/**
		 * The response <b>head</b> has been delivered to the caller, which is where an exchange ends here:
		 * from that moment the body is the caller's to read, so the byte counts are those transferred by
		 * then rather than the whole of a response this client no longer owns.
		 *
		 * @param requestBodyBytes  DATA payload bytes sent, framing excluded
		 * @param responseBodyBytes DATA payload bytes received so far, framing excluded
		 */
		void onRequestCompleted(
			Http3Client client, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes);

		/**
		 * A request stream was aborted, by this client or by the peer, with an RFC 9114 §8.1 application
		 * error code — never an RFC 9000 §20 transport one (FR-061).
		 */
		void onStreamReset(Http3Client client, long streamId, long errorCode);

		/** A connection is closing with an RFC 9114 §8.1 / RFC 9204 §6 application error code. */
		void onConnectionError(Http3Client client, long errorCode);

		/** A frame of an unknown type was skipped unread, per RFC 9114 §9's GREASE rule. */
		void onFrameDiscarded(Http3Client client, long frameType, long declaredLength);

		/** A GOAWAY was announced by this client ({@code SENT}) or by a peer ({@code RECEIVED}). */
		void onGoAway(Http3Client client, GoAwayDirection direction, long id);

		/**
		 * A request found no bidirectional-stream credit and is waiting for {@code MAX_STREAMS} (FR-050).
		 *
		 * @param queueDepth requests waiting after this one joined
		 */
		void onRequestQueued(Http3Client client, int queueDepth);

		/**
		 * A queued request stopped waiting — granted a stream, timed out, or failed.
		 *
		 * @param queueDepth requests still waiting after this one left
		 */
		void onRequestDequeued(Http3Client client, int queueDepth);

		/**
		 * Entries were inserted into one of a connection's two QPACK dynamic tables (FR-018) — by this
		 * client's encoder ({@link QpackTable#ENCODER}) or by a peer's encoder stream
		 * ({@link QpackTable#DECODER}). Silent on a connection whose negotiated capacity is 0, which is
		 * the default and has no table to insert into.
		 *
		 * @param insertions entries inserted, always at least 1
		 * @param tableBytes RFC 9204 §3.2.1 accounted size that table holds afterwards
		 */
		default void onQpackInsertions(Http3Client client, QpackTable table, int insertions, int tableBytes) {}

		/**
		 * Entries were evicted from one of them to make room (RFC 9204 §3.2.2).
		 *
		 * @param evictions  entries evicted, always at least 1
		 * @param tableBytes RFC 9204 §3.2.1 accounted size that table holds afterwards
		 */
		default void onQpackEvictions(Http3Client client, QpackTable table, int evictions, int tableBytes) {}

		/**
		 * A request field section was encoded against the dynamic table: the numerator and denominator of
		 * a dynamic-table hit rate, reported per section so a consumer picks its own window.
		 *
		 * @param fieldLines        field lines the section carried
		 * @param dynamicReferences those of them emitted as a dynamic-table reference, not a literal
		 */
		default void onQpackFieldSectionEncoded(
			Http3Client client, long streamId, int fieldLines, int dynamicReferences) {}

		/**
		 * A request stream became blocked: a field section of its arrived whole but referenced an insertion
		 * the peer's encoder stream has not delivered yet (RFC 9204 §2.1.2, FR-033). Fired once per stream,
		 * not once per section — a stream already blocked does not become blocked again.
		 *
		 * @param blockedStreams streams blocked now, this one included
		 * @param heldBytes      bytes held across all of them, this section included
		 */
		default void onQpackStreamBlocked(Http3Client client, long streamId, int blockedStreams, long heldBytes) {}

		/**
		 * A blocked stream stopped being blocked, one of the four ways {@link QpackBlockedExit} names. Only
		 * {@code DECODED} is the ordinary end of head-of-line blocking; the other three are a peer that
		 * never sent what it referenced.
		 *
		 * @param blockedMillis  how long the stream was blocked — the delay FR-036 bounds
		 * @param blockedStreams streams still blocked after this one left
		 */
		default void onQpackStreamUnblocked(
			Http3Client client, long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams) {}

		/**
		 * A field section was refused rather than held, because holding it would have exceeded a bound on
		 * blocked sections — which closes the connection (FR-034, FR-035). The two numbers are what was
		 * already held when it arrived, and so say which bound was reached.
		 */
		default void onQpackBlockedSectionRefused(
			Http3Client client, long streamId, int blockedStreams, long heldBytes) {}

		/**
		 * A connection was dialled offering a stored session ticket (FR-058). Silent with
		 * {@link Http3Settings#zeroRttEnabled()} off, and on the first dial to any origin, which has
		 * nothing to offer.
		 *
		 * @param ticketsOffered tickets offered over this client's life, this one included
		 */
		default void onSessionTicketOffered(Http3Client client, long ticketsOffered) {}

		/**
		 * A session ticket was put into the {@linkplain Builder#withSessionCache ticket store}, carrying
		 * the HTTP/3 SETTINGS of the connection that issued it (FR-062). Carries no ticket byte, no
		 * resumption secret and no binder (SI-6).
		 *
		 * @param ticketsStored tickets stored over this client's life, this one included
		 */
		default void onSessionTicketStored(Http3Client client, long ticketsStored) {}

		/**
		 * Early data went out on a dialled connection: a stored ticket was offered <b>and</b> it carried
		 * the remembered SETTINGS RFC 9114 §7.2.4.2 requires, so the request left in a 0-RTT packet
		 * rather than waiting for the handshake (FR-051).
		 *
		 * @param attempted 0-RTT attempts over this client's life, this one included
		 */
		default void onZeroRttAttempted(Http3Client client, long attempted) {}

		/**
		 * The server's decision on one attempt, reported once per attempt when the handshake completes.
		 * A rejection is not a failure — the session still resumed, and only the early data was refused.
		 *
		 * @param accepted attempts accepted over this client's life
		 * @param rejected attempts rejected over this client's life
		 */
		default void onZeroRttDecision(
			Http3Client client, ZeroRttOutcome outcome, long accepted, long rejected) {}

		/**
		 * A request whose early data was rejected has been re-issued on a fresh 1-RTT stream (FR-067) —
		 * the one event that would otherwise make the fallback invisible, since the caller sees only the
		 * final outcome and every other counter reads as though nothing happened.
		 * <p>
		 * Fired once per retry, after the superseded attempt has been let go of and before the new one
		 * starts, for both rejection signals alike: a refused {@code early_data} extension and a
		 * {@code 425 (Too Early)} answer. A retry is not counted twice and a retry that itself fails is
		 * still counted — it was issued.
		 * <p>
		 * Carries numbers only: no ticket byte, no resumption secret, no binder (SI-6, FR-050).
		 *
		 * @param retried retries over this client's life, this one included
		 */
		default void onEarlyDataRetried(Http3Client client, long retried) {}

		/**
		 * An HTTP/3 datagram was handed to the transport for the exchange on {@code streamId} (FR-079).
		 * Silent with {@link Http3Settings#datagramsEnabled()} off, which is the default and sends none.
		 * <p>
		 * "Sent" is not "delivered" and cannot be: RFC 9221 §5 neither retransmits a lost DATAGRAM frame
		 * nor acknowledges it usefully, so {@link #onDatagramDroppedByLoss} is the only negative signal
		 * there is, and even it is silent about a datagram lost without being noticed.
		 *
		 * @param payloadBytes the application payload's length — a size, never a byte of it (SI-6)
		 * @param sent         datagrams sent over the connection carrying this exchange, this one included
		 */
		default void onDatagramSent(Http3Client client, long streamId, int payloadBytes, long sent) {}

		/**
		 * An HTTP/3 datagram arrived for the exchange on {@code streamId} and was routed to it. One whose
		 * quarter stream ID named no live exchange is dropped rather than routed (FR-082) and is not
		 * counted here.
		 *
		 * @param received datagrams routed over the connection carrying this exchange, this one included
		 */
		default void onDatagramReceived(Http3Client client, long streamId, int payloadBytes, long received) {}

		/**
		 * An exchange's inbound queue was at {@link Http3Settings#maxInboundDatagramsPerStream()}, so its
		 * <b>oldest</b> datagram was dropped to make room (FR-085) — a reader falling behind, never a
		 * protocol error and never a connection closing.
		 *
		 * @param droppedByQueue queue drops over that connection, this one included
		 */
		default void onDatagramDroppedByQueue(Http3Client client, long streamId, long droppedByQueue) {}

		/**
		 * A DATAGRAM frame carrying an HTTP/3 datagram was declared lost and released rather than
		 * retransmitted (RFC 9221 §5). Carries no stream id: what is known lost is the frame, and its
		 * payload is gone by the time anything could re-derive the exchange from it.
		 *
		 * @param droppedByLoss loss drops over that connection, this one included
		 */
		default void onDatagramDroppedByLoss(Http3Client client, long droppedByLoss) {}

		/**
		 * A send was refused whole because its payload exceeded what the peer's
		 * {@code max_datagram_frame_size} leaves — RFC 9221 §3 forbids splitting a datagram, so truncating
		 * it is not an option. The two sizes are the refusal.
		 *
		 * @param refusedOversize oversize refusals over that connection, this one included
		 */
		default void onDatagramRefusedOversize(
			Http3Client client, long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize) {}
	}

	private final IDnsClient dnsClient;

	private Http3Settings settings = Http3Settings.create();

	/**
	 * {@code null} means "the built-in default", which is what makes "a consumer set one" distinguishable
	 * — and a consumer-supplied factory owns its own {@link TlsClientConfig} and so opts out of this
	 * client's resumption plumbing entirely (FR-058, research D-6).
	 */
	private @Nullable Function<String, TlsEngineFactory> tlsEngineFactory;

	/** Applied to every {@link TlsClientConfig} this client builds; a no-op unless a consumer set one. */
	private Initializer<TlsClientConfig.Builder> tlsClientConfig = builder -> {};

	private @Nullable Inspector inspector;
	private @Nullable IUdpSocket socket;

	/**
	 * The ticket store: the consumer's, or the bounded in-memory default built lazily on first use — and
	 * only when {@link Http3Settings#zeroRttEnabled()}, so 0-RTT off allocates nothing (SC-011).
	 */
	private @Nullable QuicSessionCache sessionCache;

	private @Nullable QuicEndpoint endpoint;
	private @Nullable Promise<QuicEndpoint> opening;
	private boolean closed;

	/**
	 * The pool, in access order: iteration visits the least recently used first, which is exactly the
	 * order FR-049 evicts in. Bounded by {@link Http3Settings#maxConnections()}.
	 */
	private final Map<String, PooledConnection> pool = new LinkedHashMap<>(16, 0.75f, true);

	/** FR-048: one entry per authority being dialled, shared by every request that arrives meanwhile. */
	private final Map<String, SettablePromise<PooledConnection>> connecting = new LinkedHashMap<>();

	/**
	 * Connections a GOAWAY retired (US7 §2): no new request goes on one, but the requests it is already
	 * carrying are still owed their answers, so it is held here rather than dropped — a connection nothing
	 * references is a connection nothing would close.
	 * <p>
	 * Bounded by the exchanges actually outstanding (SI-3): a connection is only ever <i>entered</i> here
	 * while it is carrying something, and it is closed and dropped the moment it stops — see
	 * {@link #retire} and {@link #connectionFreed}. {@link #retiredConnectionCount()} reports the size.
	 */
	private final Set<PooledConnection> retired = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * The {@link Http3Connection} the frame-handler factory just built, handed across the one statement
	 * that separates them: {@code QuicConnection.Builder.doBuild} applies the factory <b>synchronously</b>
	 * inside {@link QuicEndpoint#connectTo}, so the field is written and read within one call, never held.
	 */
	private @Nullable Http3Connection justDialled;

	/**
	 * The {@link Resumption} built for the connection being dialled right now, handed to
	 * {@link #onConnection} across the same single statement {@link #justDialled} is handed back over —
	 * the frame-handler factory runs synchronously inside {@link QuicEndpoint#connectTo}, so it is
	 * written and read within one call and never held.
	 */
	private @Nullable Resumption pendingResumption;

	/** Exchanges in flight; an identity set, since two requests are never the same object. */
	private final Set<Exchange> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * What every connection this client dials reports through, forwarded to the {@link Inspector} if one
	 * is attached — the events a client cannot see for itself, because they happen on a connection or
	 * on a stream rather than inside an exchange.
	 */
	private final Http3EventListener connectionEvents = new Http3EventListener() {
		@Override
		public void onConnectionError(long errorCode) {
			if (inspector != null) inspector.onConnectionError(Http3Client.this, errorCode);
		}

		@Override
		public void onFrameDiscarded(long frameType, long declaredLength) {
			if (inspector != null) inspector.onFrameDiscarded(Http3Client.this, frameType, declaredLength);
		}

		@Override
		public void onGoAway(GoAwayDirection direction, long id) {
			if (inspector != null) inspector.onGoAway(Http3Client.this, direction, id);
		}

		@Override
		public void onStreamReset(long streamId, long errorCode) {
			if (inspector != null) inspector.onStreamReset(Http3Client.this, streamId, errorCode);
		}

		@Override
		public void onQpackInsertions(QpackTable table, int insertions, int tableBytes) {
			if (inspector != null) inspector.onQpackInsertions(Http3Client.this, table, insertions, tableBytes);
		}

		@Override
		public void onQpackEvictions(QpackTable table, int evictions, int tableBytes) {
			if (inspector != null) inspector.onQpackEvictions(Http3Client.this, table, evictions, tableBytes);
		}

		@Override
		public void onQpackFieldSectionEncoded(long streamId, int fieldLines, int dynamicReferences) {
			if (inspector != null) {
				inspector.onQpackFieldSectionEncoded(Http3Client.this, streamId, fieldLines, dynamicReferences);
			}
		}

		@Override
		public void onQpackStreamBlocked(long streamId, int blockedStreams, long heldBytes) {
			if (inspector != null) {
				inspector.onQpackStreamBlocked(Http3Client.this, streamId, blockedStreams, heldBytes);
			}
		}

		@Override
		public void onQpackStreamUnblocked(
			long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams
		) {
			if (inspector != null) {
				inspector.onQpackStreamUnblocked(Http3Client.this, streamId, exit, blockedMillis, blockedStreams);
			}
		}

		@Override
		public void onQpackBlockedSectionRefused(long streamId, int blockedStreams, long heldBytes) {
			if (inspector != null) {
				inspector.onQpackBlockedSectionRefused(Http3Client.this, streamId, blockedStreams, heldBytes);
			}
		}

		@Override
		public void onDatagramSent(long streamId, int payloadBytes, long sent) {
			if (inspector != null) inspector.onDatagramSent(Http3Client.this, streamId, payloadBytes, sent);
		}

		@Override
		public void onDatagramReceived(long streamId, int payloadBytes, long received) {
			if (inspector != null) inspector.onDatagramReceived(Http3Client.this, streamId, payloadBytes, received);
		}

		@Override
		public void onDatagramDroppedByQueue(long streamId, long droppedByQueue) {
			if (inspector != null) inspector.onDatagramDroppedByQueue(Http3Client.this, streamId, droppedByQueue);
		}

		@Override
		public void onDatagramDroppedByLoss(long droppedByLoss) {
			if (inspector != null) inspector.onDatagramDroppedByLoss(Http3Client.this, droppedByLoss);
		}

		@Override
		public void onDatagramRefusedOversize(
			long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize
		) {
			if (inspector != null) {
				inspector.onDatagramRefusedOversize(
					Http3Client.this, streamId, payloadBytes, maxPayloadBytes, refusedOversize);
			}
		}
	};

	/** Requests holding a withheld {@code openBidirectional()}; bounded by {@code maxQueuedRequests}. */
	private int queuedRequests;

	private long requestsIssued;
	private long requestsQueued;
	private long requestsFailed;
	private long requestsTimedOut;
	private long connectionsOpened;
	private long connectionsEvicted;
	private long sessionTicketsOffered;
	private long sessionTicketsStored;
	private long zeroRttAttempted;
	private long zeroRttAccepted;
	private long zeroRttRejected;
	private long earlyDataRetried;

	private Http3Client(NioReactor reactor, IDnsClient dnsClient) {
		super(reactor);
		this.dnsClient = dnsClient;
	}

	/**
	 * @param dnsClient resolves an authority's host, exactly as {@code HttpClient} does — the pool is
	 *                  keyed by the authority, so this is consulted once per connection rather than once
	 *                  per request
	 */
	public static Builder builder(NioReactor reactor, IDnsClient dnsClient) {
		return new Http3Client(reactor, dnsClient).new Builder();
	}

	public static Http3Client create(NioReactor reactor, IDnsClient dnsClient) {
		return builder(reactor, dnsClient).build();
	}

	public final class Builder extends AbstractBuilder<Builder, Http3Client> {
		private Builder() {}

		public Builder withSettings(Http3Settings settings) {
			checkNotBuilt(this);
			Http3Client.this.settings = settings;
			return this;
		}

		/**
		 * The TLS engine this client presents to a given authority host, as a factory per host: the
		 * hostname is what SNI carries and what RFC 6125 endpoint identification is checked against, and
		 * the QUIC transport parameters a {@link TlsClientConfig} also needs exist only per connection —
		 * so one shared config could serve neither. The default trusts the platform's PKIX store.
		 * <p>
		 * A factory supplied here owns its own {@link TlsClientConfig} and therefore <b>opts out of this
		 * client's resumption plumbing</b>: no session ticket is offered, no ticket store is filled, and
		 * {@link Builder#withSessionCache} has no effect. A consumer wanting both must plumb
		 * {@code withSessionTicket} / {@code withSessionCache} / {@code withEarlyDataEnabled} onto its own
		 * config (research D-6 — {@code TlsEngineFactory}'s signature is deliberately untouched).
		 */
		public Builder withTlsEngineFactory(Function<String, TlsEngineFactory> tlsEngineFactory) {
			checkNotBuilt(this);
			Http3Client.this.tlsEngineFactory = tlsEngineFactory;
			return this;
		}

		/**
		 * Applied to every {@link TlsClientConfig} this client builds — the seam for a private trust
		 * store, a pinned leaf or a {@code SecureRandom} of the consumer's own, <b>without</b> giving up
		 * the resumption plumbing that {@link #withTlsEngineFactory} opts out of.
		 * <p>
		 * It is the narrower of the two hooks on purpose: this client keeps building the config, so the
		 * ticket to offer, the store to fill and the early-data switch stay where research D-6 put them.
		 * It has no effect at all when a whole engine factory is supplied, which owns its own config.
		 */
		public Builder withTlsClientConfig(Initializer<TlsClientConfig.Builder> tlsClientConfig) {
			checkNotBuilt(this);
			Http3Client.this.tlsClientConfig = Objects.requireNonNull(tlsClientConfig, "tlsClientConfig");
			return this;
		}

		/**
		 * The store this client takes session tickets from and puts them into, keyed by
		 * {@code (server name, port, ALPN)} — one a consumer can share between several {@link Http3Client}
		 * instances in a process, or persist across restarts (FR-059).
		 * <p>
		 * Absent, a bounded in-memory LRU of {@link QuicConnectionSettings#maxSessionTickets()} entries is
		 * built on first use, and only when {@link Http3Settings#zeroRttEnabled()} — with 0-RTT off,
		 * nothing here is reached and nothing is allocated.
		 * <p>
		 * The store is read and written on the reactor thread only, and neither blocks nor returns a
		 * {@code Promise}; <b>persisting tickets extends the replay window the consumer is accepting</b>.
		 * See {@link QuicSessionCache}.
		 */
		public Builder withSessionCache(QuicSessionCache sessionCache) {
			checkNotBuilt(this);
			Http3Client.this.sessionCache = Objects.requireNonNull(sessionCache, "sessionCache");
			return this;
		}

		/**
		 * Dials over a socket somebody else opened, instead of binding an ephemeral one — the same escape
		 * hatch {@link Http3Server.Builder#withSocket} offers, and for the same reason: a frame-handler
		 * factory is a build-time property of a {@link QuicEndpoint}, so the socket is the lowest seam a
		 * caller can share.
		 */
		public Builder withSocket(IUdpSocket socket) {
			checkNotBuilt(this);
			Http3Client.this.socket = socket;
			return this;
		}

		/**
		 * Registers the statistics hook of FR-062. Absent by default, and never required: every counter
		 * this client keeps reads the same with one and without one.
		 * <p>
		 * The inspector is <b>never</b> handed a {@link io.activej.bytebuf.ByteBuf}, a field value or a
		 * body byte — see {@link Inspector}.
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			Http3Client.this.inspector = inspector;
			return this;
		}

		@Override
		protected Http3Client doBuild() {
			return Http3Client.this;
		}
	}

	// ---------------------------------------------------------------- the request path

	/**
	 * Issues {@code request} over HTTP/3, connecting to its authority if nothing is pooled for it.
	 * <p>
	 * Takes ownership of {@code request}: every path that refuses it releases its body.
	 *
	 * @return a promise failing with {@link Http3Exception} for anything this client decided — a
	 * non-{@code https} scheme, a full pool, a full queue, an expired timeout — with
	 * {@link MalformedHttpException} for a response that is not a well-formed HTTP message, exactly as
	 * {@code HttpClient} reports its own, and, unwrapped, with whatever the transport reported for
	 * anything this client did not decide (FR-058c)
	 */
	@Override
	public Promise<HttpResponse> request(HttpRequest request) {
		checkInReactorThread(this);
		if (closed) {
			return refuse(request, new Http3Exception(Http3Errors.H3_NO_ERROR, "This HTTP/3 client is closed"));
		}
		// FR-051: before any socket work, and before the resolver is even consulted.
		Protocol protocol = request.getProtocol();
		if (protocol != Protocol.HTTPS) {
			return refuse(request, new Http3Exception(Http3Errors.H3_GENERAL_PROTOCOL_ERROR,
				"HTTP/3 requires the https scheme; this request carries " +
				(protocol == null ? "none" : protocol.lowercase())));
		}
		String hostAndPort = request.getHostAndPort();
		if (hostAndPort == null) {
			return refuse(request, new Http3Exception(Http3Errors.H3_GENERAL_PROTOCOL_ERROR,
				"An HTTP/3 request needs an absolute URL carrying an authority"));
		}

		requestsIssued++;
		Exchange exchange = new Exchange(request, authorityOf(hostAndPort), hostOf(hostAndPort), portOf(hostAndPort));
		inFlight.add(exchange);
		exchange.start();
		return exchange.result;
	}

	/**
	 * FR-068: whether {@code request} may be put in a 0-RTT packet, which is to say whether it is safe to
	 * have sent it should the server then refuse the early data.
	 * <p>
	 * Two conditions, both necessary:
	 * <ul>
	 *   <li><b>Replayable.</b> No body — mechanical, and it applies to an opted-in request as much as to
	 *       any other. A rejected request is re-issued by re-sending this very {@link HttpRequest}, and a
	 *       message's body stream can be taken only once, so a retry of a body-bearing request would send
	 *       the same request <i>without</i> its body rather than replaying it.</li>
	 *   <li><b>Safe, or opted in.</b> A method that is safe per RFC 9110 §9.2.1 mirrors the default
	 *       server-side policy of RFC 8470, so an ordinary application never provokes a rejection round
	 *       trip; {@link Http3EarlyData#allow} is how a consumer says otherwise for one request.</li>
	 * </ul>
	 * A request this refuses is <b>held back</b> until the handshake completes, never failed: it was
	 * never attempted, so it is never rejected and never retried.
	 */
	private static boolean permitsInEarlyData(HttpRequest request) {
		if (request.hasBody()) return false;
		return SAFE_METHODS.contains(request.getMethod()) || Http3EarlyData.isAllowed(request);
	}

	/** Takes ownership of {@code request} — a refusal owns the body nobody else will ever read. */
	private Promise<HttpResponse> refuse(HttpRequest request, Exception e) {
		requestsFailed++;
		Http3RequestStream.releaseMessage(request);
		return Promise.ofException(e);
	}

	/**
	 * One request, from the moment it is accepted to the moment its promise completes — including the
	 * timeout that covers all of it, queued time included (FR-052).
	 */
	private final class Exchange {
		private final SettablePromise<HttpResponse> result = new SettablePromise<>();
		private final HttpRequest request;
		private final String authority;
		private final String host;
		private final int port;

		private final @Nullable ScheduledRunnable timeout;

		/*
		 * Per-attempt state. An exchange has one attempt, or — when its early data was refused — two
		 * (FR-067). Everything below is reset by releaseAttempt(); everything above and below the next
		 * comment block belongs to the exchange and survives a retry.
		 */
		private @Nullable PooledConnection connection;
		private @Nullable Http3RequestStream requestStream;
		private boolean queued;
		private boolean requestSent;
		/** Whether <i>this attempt</i> went out on a stream created while 0-RTT was the send level. */
		private boolean sentAsEarlyData;

		/**
		 * The generation of the attempt in flight. An attempt that has been superseded — its stream
		 * discarded, its promises failed on the way out — still reports its outcome, synchronously, from
		 * inside the very call that supersedes it; comparing against this is what drops that outcome
		 * instead of letting it complete the caller's promise with an internal failure.
		 */
		private int attempt;

		/** FR-067's "at most once". Set before the discard that provokes the retry, never cleared. */
		private boolean retried;

		/**
		 * Set by {@link #finish()} <b>before</b> the abort it precedes, because aborting fails whatever
		 * read or write is in flight and those continuations re-enter here. {@code result.isComplete()} is
		 * not the guard: the result is completed last, so during the abort it still reads as pending.
		 */
		private boolean finished;
		private @Nullable Exception failure;

		Exchange(HttpRequest request, String authority, String host, int port) {
			this.request = request;
			this.authority = authority;
			this.host = host;
			this.port = port;

			long timeoutMillis = settings.requestTimeoutMillis();
			this.timeout = timeoutMillis == 0 ? null : reactor.delay(timeoutMillis, () -> {
				requestsTimedOut++;
				fail(new Http3Exception(Http3Errors.H3_REQUEST_CANCELLED,
					"The request exceeded the " + timeoutMillis + " ms request timeout"));
			});
		}

		void start() {
			startAttempt();
		}

		/**
		 * One attempt at this exchange: a connection, a stream on it, and the request/response pair over
		 * that stream. Run once ordinarily, and a second time when the first attempt's early data was
		 * refused (FR-067).
		 * <p>
		 * The generation is captured rather than read back, so a superseded attempt's outcome — which
		 * arrives synchronously from inside the discard that superseded it — is dropped here rather than
		 * reaching {@link #succeed} or {@link #fail}. That is the difference between a caller seeing the
		 * retry's answer and a caller seeing the internal failure of an attempt nobody is waiting for.
		 */
		private void startAttempt() {
			int myAttempt = attempt;
			connectionTo(authority, host, port)
				.then(this::openStream)
				.then(this::exchange)
				.subscribe((response, e) -> {
					if (myAttempt != attempt) {
						// This attempt has been superseded; its response, if any, is nobody's but ours.
						if (response != null) Http3RequestStream.releaseMessage(response);
						return;
					}
					if (e == null) {
						succeed(response);
					} else {
						fail(e);
					}
				});
		}

		/**
		 * FR-050: the transport withholds an open it has no credit for, so a pending promise <i>is</i> the
		 * queue. This bounds how many of them this client will hold at once — a different question from
		 * {@code QuicConnectionSettings.maxPendingStreamOpens}, which bounds withheld opens per connection
		 * whatever asked for them.
		 */
		private Promise<Http3RequestStream> openStream(PooledConnection pooled) {
			if (finished) {
				// The timeout expired while the connect was in flight; nothing more is owed a stream.
				return Promise.ofException(failure);
			}
			if (!pooled.handshake.isComplete() && !permitsInEarlyData(request)) {
				// FR-068: held back, not refused. A stream opened now would put this request in a 0-RTT
				// packet, and it is one that must not be replayed — so it waits for the handshake instead,
				// which is one round trip rather than a rejection round trip. Nothing else changes: the
				// request timeout still covers the wait (FR-052), and the connection is not held meanwhile,
				// so it is not this exchange that keeps it out of an eviction.
				return pooled.handshake.then(() -> openStream(pooled));
			}
			if (queuedRequests >= settings.maxQueuedRequests()) {
				return Promise.ofException(new Http3Exception(Http3Errors.H3_REQUEST_REJECTED,
					"No bidirectional stream credit, and " + settings.maxQueuedRequests() +
					" requests are already queued — see the maxQueuedRequests setting", true));
			}
			// Held from here, so this connection counts as busy for FR-049's eviction rule until the
			// exchange finishes — whether or not it ever gets a stream.
			connection = pooled;
			pooled.inFlight++;

			Promise<Http3RequestStream> opening = pooled.h3.openRequestStream();
			if (opening.isComplete()) return opening;
			queued = true;
			queuedRequests++;
			requestsQueued++;
			if (inspector != null) inspector.onRequestQueued(Http3Client.this, queuedRequests);
			return opening.whenComplete(this::dequeue);
		}

		private void dequeue() {
			if (!queued) return;
			queued = false;
			queuedRequests--;
			if (inspector != null) inspector.onRequestDequeued(Http3Client.this, queuedRequests);
		}

		private Promise<HttpResponse> exchange(Http3RequestStream stream) {
			requestStream = stream;
			// The transport's own latch, read once: after the handshake it still reports where this stream
			// came from, which is exactly the question a rejection asks (FR-055).
			sentAsEarlyData = stream.isEarlyData();
			if (finished) {
				// Expired between the open resolving and this running; the stream is aborted rather than
				// left holding a request nobody is waiting for.
				stream.abort(Http3Errors.H3_REQUEST_CANCELLED, "The request expired before it was sent");
				return Promise.ofException(failure);
			}
			if (inspector != null) inspector.onRequestStarted(Http3Client.this, stream.id(), request.getMethod());
			// The read is started before the write: a response head that arrives while a large body is
			// still going out is then already being read, rather than waiting behind flow control.
			Promise<HttpResponse> received = stream.receiveResponse();
			requestSent = true;
			return stream.sendRequest(request).then(() -> received);
		}

		// ------------------------------------------------------------ the two rejection signals (FR-067)

		/**
		 * The transport signal: the server accepted the pre-shared key and refused the early data, so
		 * nothing this attempt sent was ever decrypted. Called for every in-flight exchange on the
		 * connection that was refused; the ones this attempt does not own return immediately.
		 * <p>
		 * The <b>order</b> of the four steps is the whole of the correctness here, and it is the registry
		 * anti-pattern this phase exists to avoid. {@code retried} and the generation move <i>first</i>,
		 * because the discard two lines below fails this attempt's in-flight read and write
		 * <b>synchronously</b> and their continuation is this exchange's own failure handler. With the
		 * flag not yet set, that handler would complete the caller's promise with an internal failure and
		 * the retry would never happen at all.
		 */
		private void retryAfterEarlyDataRejection(Http3Connection h3) {
			if (finished || retried || !sentAsEarlyData) return;
			PooledConnection pooled = connection;
			if (pooled == null || pooled.h3 != h3) return;
			Http3RequestStream stream = requestStream;
			beginRetry();
			releaseAttempt();
			if (stream != null) h3.discardEarlyData(stream);
			reactor.post(this::startAttempt);
		}

		/**
		 * The {@code 425 (Too Early)} signal (RFC 8470 §5.2): the server took the early data but declined
		 * to process this request from it. Re-issued on a fresh stream once the handshake is done.
		 * <p>
		 * The trigger is "this request went out in early data <b>and</b> the answer is 425", never the
		 * status code alone. A 425 answering a request that carried no early data is an ordinary response
		 * and belongs to the caller: retrying it would loop for ever against a server that answers 425
		 * unconditionally.
		 *
		 * @return whether the response was taken over and the exchange re-issued
		 */
		private boolean retryAfterTooEarly(HttpResponse response) {
			if (finished || retried || !sentAsEarlyData) return false;
			if (response.getCode() != TOO_EARLY) return false;
			PooledConnection pooled = connection;
			if (pooled == null) return false;
			Http3RequestStream stream = requestStream;
			beginRetry();
			// Nobody will ever be handed this response, so this is the path that owes its body a release.
			Http3RequestStream.releaseMessage(response);
			releaseAttempt();
			// Unlike the transport signal, the server *has* seen this stream — it answered on it — so the
			// ordinary abort is right and the silent discard would be wrong.
			if (stream != null && !stream.isTerminated()) {
				stream.abort(Http3Errors.H3_REQUEST_CANCELLED,
					"The request was answered 425 (Too Early) and is being re-issued");
			}
			reactor.post(this::startAttempt);
			return true;
		}

		/** The bookkeeping both signals share, before either of them touches anything that can fail. */
		private void beginRetry() {
			retried = true;
			attempt++;
			earlyDataRetried++;
			if (inspector != null) inspector.onEarlyDataRetried(Http3Client.this, earlyDataRetried);
		}

		/**
		 * Lets go of everything one attempt held — its queue slot, its connection slot, its stream — and
		 * of nothing the exchange owns: the caller's promise, the request and the timeout that covers the
		 * whole of it (FR-052) all survive into the next attempt.
		 * <p>
		 * The connection is <b>not</b> freed through {@link #connectionFreed}: the retry re-acquires it
		 * through the ordinary pool path a tick later, and treating it as idle in between would invite an
		 * eviction of the very connection the retry is about to ask for.
		 */
		private void releaseAttempt() {
			dequeue();
			PooledConnection pooled = connection;
			connection = null;
			requestStream = null;
			requestSent = false;
			sentAsEarlyData = false;
			if (pooled != null && pooled.inFlight > 0) pooled.inFlight--;
		}

		private void succeed(HttpResponse response) {
			// Before any bookkeeping: a 425 answering early data is not this exchange's outcome at all.
			if (retryAfterTooEarly(response)) return;
			// Read before finish(), whose continuations may complete the caller's promise and re-enter here.
			Http3RequestStream stream = requestStream;
			int statusCode = response.getCode();
			// The head is here, the body may not be: the connection carries this exchange until the stream
			// that delivers it is done (T112).
			if (!finish(stream)) {
				// The timeout won the race, so nobody is waiting for this response and it is ours to release.
				Http3RequestStream.releaseMessage(response);
				return;
			}
			if (inspector != null && stream != null) {
				inspector.onRequestCompleted(Http3Client.this, stream.id(), statusCode,
					stream.bodyBytesSent(), stream.bodyBytesReceived());
			}
			result.set(response);
		}

		private void fail(Exception e) {
			// Nothing is left streaming to a caller that never got a response, so the connection is free the
			// moment this exchange is.
			if (!finish(null)) return;
			failure = e;
			requestsFailed++;
			// The stream took ownership of the request; short of that, this is the path that owes it a
			// release (FR-057a).
			if (!requestSent) Http3RequestStream.releaseMessage(request);
			// Aborting after the bookkeeping: the abort fails whatever read or write is in flight, and those
			// continuations re-enter here.
			Http3RequestStream stream = requestStream;
			requestStream = null;
			if (stream != null && !stream.isTerminated()) {
				stream.abort(errorCodeOf(e), "The request was cancelled");
			}
			// Translated last: everything above works in this module's own terms, and only what the caller
			// sees is stated in core-http's (T114).
			result.setException(Http3RequestStream.clientVisible(e));
		}

		/**
		 * @param receiving the stream still delivering this exchange's response body, or {@code null} if
		 *                  nothing is — the connection keeps carrying the former until it is done (T112)
		 * @return whether this call is the one that ended the exchange; every other is a no-op
		 */
		private boolean finish(@Nullable Http3RequestStream receiving) {
			if (finished) return false;
			finished = true;
			if (timeout != null) timeout.cancel();
			dequeue();
			inFlight.remove(this);
			PooledConnection pooled = connection;
			connection = null;
			if (pooled == null) return true;
			if (pooled.inFlight > 0) pooled.inFlight--;
			if (receiving == null || receiving.isTerminated()) {
				connectionFreed(pooled);
				return true;
			}
			// FR-049 evicts an idle connection and US7 §2 closes a retired one; both would take this body
			// with them, so the connection counts as busy until the stream carrying it is done.
			pooled.receiving.add(receiving);
			receiving.whenReceiveComplete()
				.whenComplete(() -> {
					pooled.receiving.remove(receiving);
					connectionFreed(pooled);
				});
			return true;
		}

		private long errorCodeOf(Exception e) {
			return e instanceof Http3Exception h3 ? h3.errorCode() : Http3Errors.H3_REQUEST_CANCELLED;
		}
	}

	// ---------------------------------------------------------------- the connection pool

	/** One pooled QUIC connection and the bookkeeping FR-049's eviction rule needs. */
	private static final class PooledConnection {
		private final Http3Connection h3;

		/**
		 * Completes when this connection's handshake has settled, one way or the other. Already complete
		 * for every connection handed over the ordinary way, since that one waits for the handshake before
		 * it is pooled at all; pending only for the 0-RTT hand-back of {@link #earlyDataConnection}, which
		 * is the whole case it exists for — a request that may <b>not</b> travel in early data (FR-068)
		 * waits on this rather than opening a stream that would.
		 */
		private final SettablePromise<Void> handshake = new SettablePromise<>();

		/** Exchanges on this connection, each from the moment it asks for a stream to its response head. */
		private int inFlight;

		/**
		 * Streams whose response head has been delivered but whose body is still streaming into a caller's
		 * hands — the other half of "busy" (T112). One entry per response being read, so it is as small as
		 * the number of exchanges this connection carried at once.
		 */
		private final List<Http3RequestStream> receiving = new ArrayList<>();

		PooledConnection(Http3Connection h3) {
			this.h3 = h3;
		}

		/**
		 * Whether this connection is carrying nothing: no exchange is in flight on it, and no response it
		 * delivered is still being read.
		 * <p>
		 * An exchange ends at the response <b>head</b>, so counting exchanges alone would call a connection
		 * whose body is half-transferred idle — and evicting it (FR-049) or closing it once retired (US7 §2)
		 * would sever that transfer. Hence the second half.
		 * <p>
		 * Prunes as it reads, because one of the two ways a response stops being read raises no event at
		 * all: a caller that never touches a body leaves its stream where it is forever, and a stream nobody
		 * reads holds nothing an eviction could sever — see {@link Http3RequestStream#isReceivingBody()}.
		 */
		boolean isIdle() {
			receiving.removeIf(stream -> !stream.isReceivingBody());
			return inFlight == 0 && receiving.isEmpty();
		}

		/**
		 * Whether a new request may go on this connection. A GOAWAY in either direction ends that
		 * (RFC 9114 §5.2, US7 §2) without ending the connection: what it is already carrying is still owed
		 * an answer, so it is retired rather than closed.
		 */
		boolean isUsable() {
			Http3Connection.State state = h3.state();
			return state != Http3Connection.State.CLOSED && state != Http3Connection.State.GOING_AWAY;
		}
	}

	/**
	 * The connection for {@code authority}: the pooled one, the one already being dialled for it
	 * (FR-048), or a new one — for which room is made first (FR-049).
	 */
	private Promise<PooledConnection> connectionTo(String authority, String host, int port) {
		PooledConnection pooled = pool.get(authority);
		if (pooled != null) {
			if (pooled.isUsable()) return Promise.of(pooled);
			pool.remove(authority);
			retire(pooled);
		}
		SettablePromise<PooledConnection> inFlightConnect = connecting.get(authority);
		if (inFlightConnect != null) return inFlightConnect;

		Exception noRoom = makeRoomFor(authority);
		if (noRoom != null) return Promise.ofException(noRoom);

		SettablePromise<PooledConnection> connect = new SettablePromise<>();
		connecting.put(authority, connect);
		dial(host, port)
			.whenComplete((connection, e) -> {
				connecting.remove(authority);
				if (e != null) {
					connect.trySetException(e);
					return;
				}
				if (closed || connect.isComplete()) {
					// close() failed everyone who was waiting on this dial, so what it produced serves
					// nobody — and a connection nothing is pooling is a connection nothing would close.
					connection.h3.close();
					return;
				}
				pool.put(authority, connection);
				connectionsOpened++;
				connect.set(connection);
			});
		return connect;
	}

	/**
	 * Sets a GOAWAY'd connection aside: it carries no further request, and it stays open for whatever it
	 * already has (US7 §2).
	 * <p>
	 * A connection carrying <i>nothing</i> is closed instead of held, here and for everything already
	 * retired that has gone quiet since. That is what bounds this set (SI-3): a peer that announces GOAWAY
	 * on every connection it accepts makes every request dial a fresh one, and an entry left behind per
	 * request would grow without limit while nothing pruned it. Idleness is the whole condition and it is
	 * the strict one — a response whose body is still streaming into a caller's hands keeps its connection
	 * busy (T112), so closing an idle one severs nothing.
	 */
	private void retire(PooledConnection connection) {
		if (connection.h3.state() == Http3Connection.State.CLOSED) return;
		// A connection that went quiet without an exchange of ours ending — one whose response nobody ever
		// read, one its peer closed on its own — raises no event {@link #connectionFreed} would see, so this
		// is where those are collected.
		List<PooledConnection> leaving = new ArrayList<>();
		for (PooledConnection entry : retired) {
			if (entry.h3.state() == Http3Connection.State.CLOSED || entry.isIdle()) leaving.add(entry);
		}
		leaving.forEach(retired::remove);
		if (connection.isIdle()) {
			leaving.add(connection);
		} else {
			retired.add(connection);
			logger.trace("Retired an HTTP/3 connection that announced GOAWAY, with {} request(s) still on it",
				connection.inFlight);
		}
		// Closed after every bookkeeping change, never during one: closing aborts the streams a connection
		// owns, and those continuations re-enter here.
		for (PooledConnection entry : leaving) {
			entry.h3.close();
		}
	}

	/**
	 * An exchange let go of {@code connection} — its response head failed, or the body behind it finished
	 * streaming. A retired connection with nothing left to carry is closed at that moment rather than held
	 * until {@link #close()}, which is what keeps {@link #retired} bounded (SI-3, T113).
	 */
	private void connectionFreed(PooledConnection connection) {
		// Dropped before the close, which aborts the streams on this connection and re-enters here.
		if (!retired.remove(connection)) return;
		if (!connection.isIdle()) {
			retired.add(connection);
			return;
		}
		connection.h3.close();
	}

	/**
	 * FR-049: at the bound, evicts the least-recently-used idle connection — the pool iterates in access
	 * order, so the first idle entry is it. With every connection busy the pool does not grow and the
	 * request is refused, retryably, naming the setting key.
	 *
	 * @return the refusal, or {@code null} if there is room
	 */
	private @Nullable Exception makeRoomFor(String authority) {
		int maxConnections = settings.maxConnections();
		// Connections being dialled count: they are about to occupy a slot each.
		while (pool.size() + connecting.size() >= maxConnections) {
			PooledConnection evictable = null;
			String evictableAuthority = null;
			for (Map.Entry<String, PooledConnection> entry : pool.entrySet()) {
				if (entry.getValue().isIdle()) {
					evictable = entry.getValue();
					evictableAuthority = entry.getKey();
					break;
				}
			}
			if (evictable == null) {
				return new Http3Exception(Http3Errors.H3_REQUEST_REJECTED,
					"Every one of the " + maxConnections + " pooled connections has a request in flight" +
					" — see the maxConnections setting", true);
			}
			pool.remove(evictableAuthority);
			connectionsEvicted++;
			// FR-049: the evicted connection leaves with GOAWAY rather than a bare close, so the peer learns
			// that this client will process nothing further on it instead of finding a connection gone. Only
			// an idle connection is ever chosen, so there is nothing to drain and the close follows the
			// announcement straight away — but it follows it, rather than replacing it.
			Http3Connection leaving = evictable.h3;
			leaving.goAway().whenComplete(($, e) -> leaving.close());
			logger.trace("Evicted the idle HTTP/3 connection to {} to make room for {}", evictableAuthority, authority);
		}
		return null;
	}

	/** Resolves the host, dials it, and hands back the {@link Http3Connection} the factory built for it. */
	private Promise<PooledConnection> dial(String host, int port) {
		return endpoint()
			.then(quicEndpoint -> resolve(host)
				.then(address -> connect(quicEndpoint, new InetSocketAddress(address, port), host)));
	}

	private Promise<PooledConnection> connect(QuicEndpoint quicEndpoint, InetSocketAddress address, String host) {
		justDialled = null;
		Resumption resumption = resumptionFor(host, address.getPort());
		pendingResumption = resumption;
		Promise<QuicConnection> established;
		try {
			established = resumption == null ?
				quicEndpoint.connectTo(address, tlsEngineFactory().apply(host)) :
				quicEndpoint.connectTo(address, resumption.tlsEngineFactory(), resumption.rememberedParameters());
		} finally {
			pendingResumption = null;
		}
		Http3Connection h3 = justDialled;
		justDialled = null;
		if (h3 == null) {
			// Unreachable short of a QuicEndpoint that stopped applying its frame-handler factory, or a
			// connectTo that refused before building anything — the latter already failed the promise.
			return established.then(($, e) -> Promise.ofException(e != null ?
				e :
				new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "The dialled QUIC connection carries no HTTP/3 layer")));
		}
		if (h3.permitsEarlyData()) {
			return Promise.of(earlyDataConnection(h3, established));
		}
		return established.map($ -> {
			PooledConnection pooled = new PooledConnection(h3);
			// Nothing was ever held back on this path: the handshake is already over by the time anybody
			// can hold this connection at all.
			pooled.handshake.set(null);
			return pooled;
		});
	}

	/**
	 * FR-051: hands the caller a connection whose handshake is still running, which is the whole of
	 * 0-RTT. The request that follows opens its stream against the {@linkplain
	 * QuicConnection#earlyTransportParameters() remembered} transport parameters and leaves in a 0-RTT
	 * packet rather than waiting for a {@code Finished} it does not need.
	 * <p>
	 * Reached only when {@link Http3Connection#permitsEarlyData()} holds — a stored ticket was offered
	 * <b>and</b> it carried the remembered HTTP/3 SETTINGS of RFC 9114 §7.2.4.2 — which is false for
	 * every dial made with {@link Http3Settings#zeroRttEnabled()} off, so the default path is phase 1's
	 * verbatim (SC-011).
	 * <p>
	 * A handshake that then <b>fails</b> loses nothing: {@code QuicConnection} closes,
	 * {@code QuicStreamManager} fails every stream and every withheld open with the transport
	 * exception, and that is what the caller's promise carries — the same failure it would have carried
	 * from the dial, arriving through the stream instead. What it does need is the close below: the
	 * dial's promise no longer reports the failure to anybody, so a connection that died handshaking
	 * would otherwise sit in the pool reading as usable and be handed to every later request for that
	 * authority.
	 * <p>
	 * A handshake that succeeds while <b>refusing</b> the early data is a different matter and is
	 * <b>US4's</b> (Phase 6, T093–T096): this reports the decision and nothing more, so the request
	 * rides QUIC loss recovery back out at 1-RTT rather than being re-created on a fresh stream. That
	 * is the reason {@code zeroRttEnabled} ships {@code false}.
	 */
	private PooledConnection earlyDataConnection(Http3Connection h3, Promise<QuicConnection> established) {
		zeroRttAttempted++;
		if (inspector != null) inspector.onZeroRttAttempted(this, zeroRttAttempted);
		PooledConnection pooled = new PooledConnection(h3);
		// One subscription on the dial, not two: everything this connection owes the handshake's outcome is
		// settled from inside it, in order.
		established.whenComplete((quicConnection, e) -> {
			if (e != null) {
				// Closed first, settled second: a request held back from early data resumes on this
				// promise, and it must find a connection that is already gone rather than open a stream on
				// one that is about to be.
				h3.close();
				pooled.handshake.trySetException(e);
				return;
			}
			boolean accepted = quicConnection.isEarlyDataAccepted();
			if (accepted) {
				zeroRttAccepted++;
			} else {
				zeroRttRejected++;
			}
			if (inspector != null) {
				inspector.onZeroRttDecision(this,
					accepted ? ZeroRttOutcome.ACCEPTED : ZeroRttOutcome.REJECTED,
					zeroRttAccepted, zeroRttRejected);
			}
			// Last: it releases the requests that were held back from early data (FR-068), and they open
			// streams — which must find every counter of this connection already settled.
			pooled.handshake.trySet(null);
		});
		return pooled;
	}

	/**
	 * One connection's early data was refused (FR-067). Every exchange in flight <b>on that connection</b>
	 * that went out in early data takes itself back and re-issues on a fresh 1-RTT stream; everything else
	 * — a different connection's exchange, one that was held back from early data, one already retried —
	 * returns untouched.
	 * <p>
	 * Over a copy of the set, because a retry lets go of its attempt and that removes nothing from
	 * {@link #inFlight} but does run continuations that can.
	 */
	private void onEarlyDataRejected(Http3Connection h3) {
		for (Exchange exchange : new ArrayList<>(inFlight)) {
			exchange.retryAfterEarlyDataRejection(h3);
		}
	}

	private Promise<InetAddress> resolve(String host) {
		return dnsClient.resolve4(host).map(Http3Client::firstAddressOf);
	}

	private static InetAddress firstAddressOf(DnsResponse response) {
		InetAddress[] ips = response.getRecord() == null ? null : response.getRecord().getIps();
		if (ips == null || ips.length == 0) {
			throw new IllegalStateException("The resolver answered with no address");
		}
		return ips[0];
	}

	// ---------------------------------------------------------------- the endpoint

	/**
	 * The one {@link QuicEndpoint} every pooled connection shares (FR-059a), opened on first use.
	 * Memoized as a promise, so several requests racing the socket open share it as they share a connect.
	 */
	private Promise<QuicEndpoint> endpoint() {
		if (endpoint != null) return Promise.of(endpoint);
		if (opening != null) return opening;
		if (socket != null) {
			startOn(socket);
			return Promise.of(endpoint);
		}
		DatagramChannel channel;
		try {
			// A null bind address is an ephemeral port on the wildcard address, which is every port a
			// client needs.
			channel = NioReactor.createDatagramChannel(DatagramSocketSettings.create(), null, null);
		} catch (IOException e) {
			return Promise.ofException(e);
		}
		opening = UdpSocket.connect(reactor, channel)
			.map(opened -> {
				socket = opened;
				startOn(opened);
				return endpoint;
			})
			.whenComplete(() -> opening = null);
		return opening;
	}

	private void startOn(IUdpSocket udpSocket) {
		endpoint = QuicEndpoint.builder(reactor, udpSocket)
			.withSettings(quicSettings())
			.withFrameHandlerFactory(this::onConnection)
			.build();
	}

	/**
	 * FR-058b: the transport parameters this feature depends on are supplied as <i>values</i>; encoding
	 * them is the transport's job and no part of this module's. Two are unconditional — the stream-related
	 * pair, which a client advertises for the streams a <i>server</i> might open toward it, in HTTP/3 its
	 * control and QPACK streams and nothing else — and one is conditional on an {@link Http3Settings}
	 * switch. This private mapping is deliberately the <b>only</b> way an H3-level setting reaches the
	 * transport: there is no public {@code withQuicSettings} pass-through, because a consumer handed the
	 * whole {@link QuicConnectionSettings} could set a parameter that contradicts what this layer requires
	 * — {@code initialMaxStreamsUni < 3} alone breaks the control stream plus both QPACK streams (FR-017).
	 * <p>
	 * With {@link Http3Settings#datagramsEnabled()} false — the default (FR-089) — nothing conditional is
	 * called at all, so the builder produces exactly the value phase 1 produced, keeping
	 * {@code max_datagram_frame_size} unadvertised (SC-011). Guarding rather than passing an explicit 0
	 * is the point: 0 happens to be {@link QuicConnectionSettings#DEFAULT_MAX_DATAGRAM_FRAME_SIZE} today,
	 * and the default path must not depend on that staying true.
	 * <p>
	 * {@link Http3Settings#zeroRttEnabled()} is <b>deliberately not mapped here</b>, and its absence is a
	 * decision rather than an omission: 0-RTT needs a ticket store, sealing keys and a replay register,
	 * none of which is a connection setting — they arrive on the TLS configs and the {@code QuicConnection}
	 * builder in the 0-RTT slice (research D-6). The eight session-resumption bounds already on
	 * {@code QuicConnectionSettings} are the transport's own defaults and stay untouched until then.
	 * <p>
	 * Three further {@link Http3Settings} fields have no counterpart by construction:
	 * {@code maxInboundDatagramsPerStream} is per-exchange H3 state owned by {@code Http3RequestStream}
	 * and never a QUIC parameter, {@code maxOutboundDatagrams} is QUIC-level with its own default and no
	 * H3 switch, and the five QPACK settings are H3-only — they travel in SETTINGS, not in the handshake.
	 */
	private QuicConnectionSettings quicSettings() {
		QuicConnectionSettings.Builder builder = QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(settings.maxConcurrentRequestStreams())
			.withInitialMaxStreamsUni(settings.maxUniStreams());
		if (settings.datagramsEnabled()) {
			builder.withMaxDatagramFrameSize(MemSize.bytes(QuicConnectionSettings.maxDatagramFrameSizeFor(
				QuicConnectionSettings.DEFAULT_MAX_DATAGRAM_SIZE.toInt())));
		}
		return builder.build();
	}

	/**
	 * One {@link Http3Connection} per dialled QUIC connection, which answers with the frame handler: its
	 * stream manager alone, or, with {@link Http3Settings#datagramsEnabled()}, a composite that also
	 * routes RFC 9221 DATAGRAM frames.
	 */
	private QuicFrameHandler onConnection(QuicConnection quicConnection) {
		Http3Connection.Builder builder = Http3Connection.builder(reactor, quicConnection)
			.withSettings(settings)
			.withEventListener(connectionEvents)
			.withEarlyDataRejectionListener(this::onEarlyDataRejected);
		Resumption resumption = pendingResumption;
		if (resumption != null) {
			SettingsFrame remembered = resumption.rememberedSettings();
			if (remembered != null) builder.withRememberedSettings(remembered);
			builder.withPeerSettingsListener(resumption::onPeerSettings);
		}
		Http3Connection h3 = builder.build();
		justDialled = h3;
		return h3.startAndGetFrameHandler();
	}

	private Function<String, TlsEngineFactory> tlsEngineFactory() {
		Function<String, TlsEngineFactory> configured = tlsEngineFactory;
		return configured != null ? configured : this::defaultTlsEngineFactory;
	}

	private TlsEngineFactory defaultTlsEngineFactory(String host) {
		return params -> {
			TlsClientConfig.Builder builder = TlsClientConfig.builder(host, params);
			tlsClientConfig.initialize(builder);
			return QuicTls.clientEngine(builder.build());
		};
	}

	// ---------------------------------------------------------------- 0-RTT (FR-058, FR-059, FR-062)

	/**
	 * The store tickets are taken from and put into: the consumer's, else a bounded in-memory LRU built
	 * on first use.
	 * <p>
	 * The clock is {@code reactor::currentTimeMillis} — the very supplier every {@link TlsClientConfig}
	 * built here is given, which {@code TlsClientConfig}'s Javadoc requires, so that a ticket's issue
	 * time and the store's expiry check are read off one clock.
	 */
	private QuicSessionCache sessionCache() {
		QuicSessionCache cache = sessionCache;
		if (cache == null) {
			cache = InMemoryQuicSessionCache.create(quicSettings().maxSessionTickets(), reactor::currentTimeMillis);
			sessionCache = cache;
		}
		return cache;
	}

	/**
	 * The resumption attempt for one dial, or {@code null} when this client is not making one — which is
	 * every dial with {@link Http3Settings#zeroRttEnabled()} off (the default), and every dial made with
	 * a consumer-supplied TLS engine factory, which owns its own {@link TlsClientConfig}.
	 * <p>
	 * With 0-RTT off the caller's path is literally phase 1's: today's {@code defaultTlsEngineFactory}
	 * and today's two-argument {@code connectTo}, byte for byte (SC-011).
	 */
	private @Nullable Resumption resumptionFor(String host, int port) {
		if (!settings.zeroRttEnabled() || tlsEngineFactory != null) return null;
		return new Resumption(host, port, sessionCache());
	}

	/**
	 * One dialled connection's resumption state, and the {@link QuicSessionCache} the TLS engine writes
	 * its tickets into — the two are one unit deliberately: the transport parameters a ticket remembers
	 * and the HTTP/3 SETTINGS it remembers must never be stored apart, because using either without the
	 * other is a protocol violation waiting to happen (FR-062, RFC 9114 §7.2.4.2).
	 */
	private final class Resumption implements QuicSessionCache {
		private final String host;
		private final int port;
		private final QuicSessionCache store;

		private final @Nullable QuicSessionTicket offered;
		private final @Nullable SettingsFrame remembered;

		/** This connection's own SETTINGS, once its control stream has delivered them. */
		private @Nullable SettingsFrame peerSettings;

		/**
		 * Tickets that arrived before the peer's SETTINGS did — the orders are independent. Bounded by
		 * the same per-connection ticket count the TLS engine enforces, so a server cannot grow it by
		 * sending tickets and never sending SETTINGS (SI-3).
		 */
		private final List<QuicSessionTicket> pending = new ArrayList<>();
		private final int maxPending;

		/**
		 * FR-043a's two bounds on post-handshake {@code NewSessionTicket} input, read from the transport's
		 * settings once per dial and handed to the {@link TlsClientConfig} that enforces them. Read here
		 * rather than in {@link #tlsEngineFactory()} so that {@link #maxPending} and the engine's own count
		 * bound cannot come from two lookups that disagree.
		 */
		private final MemSize maxSessionTicketSize;
		private final int maxSessionTicketsPerConnection;

		Resumption(String host, int port, QuicSessionCache store) {
			this.host = host;
			this.port = port;
			this.store = store;
			QuicConnectionSettings quic = quicSettings();
			this.maxSessionTicketSize = MemSize.bytes(quic.maxSessionTicketSize());
			this.maxSessionTicketsPerConnection = quic.maxSessionTicketsPerConnection();
			this.maxPending = maxSessionTicketsPerConnection;

			QuicSessionTicket taken = store.take(host, port, ALPN_H3);
			if (taken != null && taken.isExpiredAt(reactor.currentTimeMillis())) taken = null;
			this.offered = taken;
			// FR-062's single decision point: a ticket with no remembered SETTINGS still resumes the
			// session, it just may not carry early data.
			this.remembered = taken == null ? null : Http3RememberedSettings.of(taken, settings.maxControlFrameSize());
		}

		@Nullable SettingsFrame rememberedSettings() {
			return remembered;
		}

		/** RFC 9000 §7.4.1: what 0-RTT data must be bounded by, and what the server may not reduce below. */
		@Nullable QuicTransportParameters rememberedParameters() {
			return offered == null ? null : offered.transportParameters();
		}

		/**
		 * Research D-6: this is the <b>only</b> place resumption is plumbed. {@code TlsEngineFactory}'s
		 * signature is untouched; the ticket to offer, the store to fill and the early-data switch all
		 * travel on {@link TlsClientConfig}.
		 * <p>
		 * FR-043a's two bounds are applied <b>before</b> {@link Builder#withTlsClientConfig}'s initializer,
		 * so a consumer that names either explicitly still wins; the transport's setting is the default
		 * they replace, not a value written over theirs.
		 */
		TlsEngineFactory tlsEngineFactory() {
			return params -> {
				TlsClientConfig.Builder builder = TlsClientConfig.builder(host, params)
					.withMaxSessionTicketSize(maxSessionTicketSize)
					.withMaxSessionTicketsPerConnection(maxSessionTicketsPerConnection);
				tlsClientConfig.initialize(builder);
				builder.withCurrentTimeMillis(reactor::currentTimeMillis).withSessionCache(this, port);
				if (offered != null) {
					builder.withSessionTicket(offered);
					sessionTicketsOffered++;
					if (inspector != null) inspector.onSessionTicketOffered(Http3Client.this, sessionTicketsOffered);
				}
				// FR-062: no remembered SETTINGS, no early data — valid ticket or not.
				if (remembered != null) builder.withEarlyDataEnabled(true);
				return QuicTls.clientEngine(builder.build());
			};
		}

		void onPeerSettings(SettingsFrame settingsFrame) {
			peerSettings = settingsFrame;
			if (pending.isEmpty()) return;
			List<QuicSessionTicket> flushing = new ArrayList<>(pending);
			pending.clear();
			for (QuicSessionTicket ticket : flushing) {
				store(ticket, settingsFrame);
			}
		}

		/**
		 * Never called: {@code TlsClientEngine} reads the ticket to offer from
		 * {@code TlsClientConfig.withSessionTicket} and takes nothing from the store itself — this client
		 * has already taken it, in this object's constructor. Answering {@code null} rather than
		 * delegating keeps a second take (and so a second replay-window consumption) impossible.
		 */
		@Override
		public @Nullable QuicSessionTicket take(String serverName, int remotePort, String alpn) {
			return null;
		}

		@Override
		public void put(String serverName, int remotePort, String alpn, QuicSessionTicket ticket) {
			SettingsFrame settingsFrame = peerSettings;
			if (settingsFrame == null) {
				if (pending.size() >= maxPending) {
					logger.debug("Discarding a session ticket for {}: {} are already awaiting this peer's SETTINGS",
						host, pending.size());
					return;
				}
				pending.add(ticket);
				return;
			}
			store(ticket, settingsFrame);
		}

		/**
		 * Re-issues {@code ticket} carrying the peer's SETTINGS and hands it to the real store. Everything
		 * else is preserved verbatim — identity, issue time, lifetime, {@code ticket_age_add}, transport
		 * parameters, server name, ALPN, cipher suite and resumption secret — so what is stored differs
		 * from what the engine built in exactly one field.
		 */
		private void store(QuicSessionTicket ticket, SettingsFrame settingsFrame) {
			QuicSessionTicket withSettings = QuicSessionTicket
				.builder(ticket.serverName(), ticket.alpn(), ticket.cipherSuite(), ticket.resumptionSecret())
				.withIdentity(ticket.identity())
				.withIssuedAt(ticket.issuedAtMillis())
				.withLifetime(ticket.lifetimeMillis())
				.withTicketAgeAdd(ticket.ticketAgeAdd())
				.withTransportParameters(ticket.transportParameters())
				.withApplicationSettings(Http3RememberedSettings.encode(settingsFrame))
				.build();
			// Keyed by the ticket's own server name and ALPN, which is what InMemoryQuicSessionCache.put
			// checks the ticket against — and which equals (host, "h3") by construction, since the engine
			// takes the first from the TlsClientConfig built above and negotiates only the second.
			store.put(withSettings.serverName(), port, withSettings.alpn(), withSettings);
			sessionTicketsStored++;
			if (inspector != null) inspector.onSessionTicketStored(Http3Client.this, sessionTicketsStored);
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Fails every outstanding request exactly once, closes every pooled connection and the endpoint under
	 * them. Idempotent (WI-9).
	 */
	@Override
	public void close() {
		checkInReactorThread(this);
		if (closed) return;
		closed = true;

		Http3Exception closing = new Http3Exception(Http3Errors.H3_NO_ERROR, "This HTTP/3 client is closed");
		// Copies throughout: failing a request runs its caller's continuation, which routinely issues
		// another request or closes something.
		for (SettablePromise<PooledConnection> connect : new ArrayList<>(connecting.values())) {
			connect.trySetException(closing);
		}
		connecting.clear();
		for (Exchange exchange : new ArrayList<>(inFlight)) {
			exchange.fail(closing);
		}
		inFlight.clear();
		for (PooledConnection connection : new ArrayList<>(pool.values())) {
			connection.h3.close();
		}
		pool.clear();
		for (PooledConnection connection : new ArrayList<>(retired)) {
			connection.h3.close();
		}
		retired.clear();
		justDialled = null;
		pendingResumption = null;
		queuedRequests = 0;

		if (endpoint != null) {
			endpoint.close();
			endpoint = null;
		} else if (socket != null) {
			socket.close();
		}
	}

	public boolean isClosed() {
		checkInReactorThread(this);
		return closed;
	}

	// ---------------------------------------------------------------- authority parsing

	/** The pool key: the scheme is always {@code https} here, so the host and port are the whole of it. */
	private static String authorityOf(String hostAndPort) {
		return "https://" + hostAndPort.toLowerCase(Locale.ROOT);
	}

	private static String hostOf(String hostAndPort) {
		int colon = portColonIn(hostAndPort);
		return colon == -1 ? hostAndPort : hostAndPort.substring(0, colon);
	}

	private static int portOf(String hostAndPort) {
		int colon = portColonIn(hostAndPort);
		if (colon == -1) return DEFAULT_HTTPS_PORT;
		try {
			return Integer.parseInt(hostAndPort.substring(colon + 1));
		} catch (NumberFormatException e) {
			return DEFAULT_HTTPS_PORT;
		}
	}

	/**
	 * The colon separating the port, or {@code -1} if the authority carries none. An IPv6 literal is
	 * bracketed (RFC 3986 §3.2.2), so its own colons end at the {@code ']'} — without that, the last colon
	 * of {@code [::1]} would be read as a port separator.
	 */
	private static int portColonIn(String hostAndPort) {
		int colon = hostAndPort.lastIndexOf(':');
		if (colon == -1) return -1;
		return colon > hostAndPort.lastIndexOf(']') ? colon : -1;
	}

	// ---------------------------------------------------------------- counters (FR-062)

	/** Requests accepted by {@link #request}, whatever became of them. */
	public long requestsIssued() {
		checkInReactorThread(this);
		return requestsIssued;
	}

	/** Requests that had to wait for bidirectional-stream credit at least once (FR-050). */
	public long requestsQueued() {
		checkInReactorThread(this);
		return requestsQueued;
	}

	public long requestsFailed() {
		checkInReactorThread(this);
		return requestsFailed;
	}

	public long requestsTimedOut() {
		checkInReactorThread(this);
		return requestsTimedOut;
	}

	public long connectionsOpened() {
		checkInReactorThread(this);
		return connectionsOpened;
	}

	/** Idle connections closed to make room at {@link Http3Settings#maxConnections()} (FR-049). */
	public long connectionsEvicted() {
		checkInReactorThread(this);
		return connectionsEvicted;
	}

	public int connectionCount() {
		checkInReactorThread(this);
		return pool.size();
	}

	/**
	 * Connections a GOAWAY retired that are still carrying something (US7 §2). Pooled connections are not
	 * among them: a retired connection has left the pool, takes no further request, and is closed as soon
	 * as what it carries is done — so this counts what a peer's GOAWAYs are keeping alive, and is bounded
	 * rather than growing per request (SI-3).
	 */
	public int retiredConnectionCount() {
		checkInReactorThread(this);
		return retired.size();
	}

	/** Requests holding a withheld stream open right now; bounded by {@code maxQueuedRequests}. */
	public int queuedRequestCount() {
		checkInReactorThread(this);
		return queuedRequests;
	}

	public int activeRequests() {
		checkInReactorThread(this);
		return inFlight.size();
	}

	/**
	 * Connections dialled offering a session ticket for resumption (FR-058). Zero with
	 * {@link Http3Settings#zeroRttEnabled()} off, and zero on the first connection to any origin.
	 */
	public long sessionTicketsOffered() {
		checkInReactorThread(this);
		return sessionTicketsOffered;
	}

	/**
	 * Session tickets put into the {@linkplain Builder#withSessionCache ticket store}, each carrying the
	 * HTTP/3 SETTINGS of the connection that issued it (FR-062). A ticket the peer sent but whose
	 * SETTINGS never arrived is not counted, because it was never stored.
	 */
	public long sessionTicketsStored() {
		checkInReactorThread(this);
		return sessionTicketsStored;
	}

	/**
	 * Connections dialled with early data actually going out on them (FR-051): a ticket was offered and
	 * it carried the remembered SETTINGS RFC 9114 §7.2.4.2 requires. Never greater than
	 * {@link #sessionTicketsOffered()}, and 0 with {@link Http3Settings#zeroRttEnabled()} off.
	 */
	public long zeroRttAttempted() {
		checkInReactorThread(this);
		return zeroRttAttempted;
	}

	/** Attempts whose early data the server took, decided once per attempt at handshake completion. */
	public long zeroRttAccepted() {
		checkInReactorThread(this);
		return zeroRttAccepted;
	}

	/**
	 * Attempts whose early data the server refused. Not a failure — the session resumed, and every
	 * request that went out in that early data is re-created on a fresh 1-RTT stream (FR-055, FR-067),
	 * which {@link #earlyDataRetried()} counts.
	 */
	public long zeroRttRejected() {
		checkInReactorThread(this);
		return zeroRttRejected;
	}

	/**
	 * Requests re-issued because their early data was rejected (FR-067), counting both signals — a
	 * refused {@code early_data} extension and a {@code 425 (Too Early)} answer.
	 * <p>
	 * Per <b>request</b>, unlike {@link #zeroRttRejected()}, which is per connection: a rejected
	 * connection carrying no request retries nothing, and one carrying several retries each of them. At
	 * most one retry per request, so this can never exceed {@link #requestsIssued()}.
	 */
	public long earlyDataRetried() {
		checkInReactorThread(this);
		return earlyDataRetried;
	}

	@Override
	public String toString() {
		return "Http3Client{" +
			"connections=" + pool.size() +
			", connecting=" + connecting.size() +
			", activeRequests=" + inFlight.size() +
			", queued=" + queuedRequests +
			(closed ? ", closed" : "") + '}';
	}
}
