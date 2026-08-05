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

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpError;
import io.activej.http.HttpExceptionFormatter;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.EarlyDataRefusal;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Connection.QpackBlockedExit;
import io.activej.http3.Http3Connection.QpackTable;
import io.activej.net.socket.udp.IUdpSocket;
import io.activej.net.socket.udp.UdpSocket;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamException;
import io.activej.quic.tls.QuicReplayGuard;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsServerConfig;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.reactor.AbstractNioReactive;
import io.activej.reactor.net.DatagramSocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static io.activej.common.Utils.nullify;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * An HTTP/3 server (RFC 9114): it serves an existing {@link AsyncServlet}, unmodified, over QUIC
 * (FR-041).
 *
 * <pre>{@code
 * Http3Server server = Http3Server.builder(reactor, servlet)
 *     .withListenAddress(new InetSocketAddress(443))
 *     .withServerIdentity(TlsServerIdentity.fromPem(certChain, privateKey))
 *     .build();
 * server.listen();
 * }</pre>
 *
 * <h2>Not an {@code AbstractReactiveServer}</h2>
 * That base class models a TCP accept loop — a listening socket, a socket per peer, a backlog — and
 * QUIC has none of the three: one UDP socket carries every connection, and a connection is admitted by
 * a packet rather than accepted from a queue (ADR-017, the decision {@code QuicEndpoint} already made).
 *
 * <h2>How it attaches to the transport</h2>
 * Through the one seam feature 03 exposes for an accepted connection —
 * {@link QuicEndpoint.Builder#withFrameHandlerFactory} — which builds one {@link Http3Connection} per
 * QUIC connection and hands back its {@code QuicStreamManager} (FR-058a). Nothing here reaches into
 * {@code core-quic} internals, and nothing subclasses {@link QuicEndpoint} (FR-059).
 *
 * <h2>Bounds</h2>
 * <ul>
 *   <li>concurrent request streams: the QUIC {@code initial_max_streams_bidi} transport parameter this
 *       server advertises, taken from {@link Http3Settings#maxConcurrentRequestStreams()} (FR-046,
 *       FR-058b) — a bound the transport enforces before a stream ever reaches this class;</li>
 *   <li>field-section size and body size: {@link Http3RequestStream}'s, per request;</li>
 *   <li>per-request time: {@link Http3Settings#requestTimeoutMillis()}, on expiry the stream is aborted
 *       with {@code H3_REQUEST_CANCELLED} and whatever the servlet later produces is released
 *       (FR-046a);</li>
 *   <li>shutdown time: {@link Http3Settings#shutdownTimeoutMillis()}, the ceiling on the GOAWAY drain of
 *       {@link #close()}.</li>
 * </ul>
 *
 * <h2>0-RTT, early data and the limit of the replay defence</h2>
 * Off by default: with {@link Http3Settings#zeroRttEnabled()} unset this server issues no session
 * ticket, admits no early data and puts byte-for-byte the phase-1 handshake on the wire. Turning it on
 * accepts that <b>early data is replayable</b> — an observer who captures a 0-RTT flight can send it
 * again, and no handshake state exists to tell the copy from the original — and brings two defences
 * with it (FR-064, FR-069):
 * <ul>
 *   <li>the {@linkplain Builder#withEarlyDataPolicy early-data policy}, which answers anything but an
 *       RFC 9110 §9.2.1 safe method {@code 425 (Too Early)} without invoking the servlet;</li>
 *   <li>a bounded, fail-closed single-use register of ticket identities, so a ticket presented for
 *       early data a second time buys 1-RTT and nothing more.</li>
 * </ul>
 * <b>The register is process-local — and reactor-local: one per {@code Http3Server} instance.</b>
 * Behind a load balancer, an early-data flight replayed to a <i>different</i> instance is <b>not</b>
 * caught by it. What protects that case is the safe-method default policy, which is why that default is
 * not merely advisory and why widening it widens the exposure of a whole deployment rather than of this
 * process. A deployment needing more must supply its own policy; a distributed strike register is out of
 * scope. What each defence refused is readable through {@link #zeroRttRefusedAsReplay()},
 * {@link #zeroRttRefusedAtCapacity()}, {@link #zeroRttRefusedAsExpired()} and
 * {@link #earlyDataRequestsRefused()} — bearing in mind that the first counts the replays <i>this
 * instance</i> caught, not those aimed at the deployment.
 *
 * <h2>Shutting down</h2>
 * {@link #close()} is graceful (FR-019): every connection announces GOAWAY carrying the last request
 * stream it will process, the exchanges already under way are left to finish, and only then does the
 * endpoint — and with it every connection — go. A request stream opened after the announcement is
 * refused with {@code H3_REQUEST_REJECTED} without ever reaching the servlet, so a peer knows to retry
 * it elsewhere. The drain is bounded by {@link Http3Settings#shutdownTimeoutMillis()}, because a peer
 * that never finishes must not be able to keep a closing server open.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114">RFC 9114 — HTTP/3</a>
 */
public final class Http3Server extends AbstractNioReactive implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(Http3Server.class);

	/**
	 * The optional statistics hook of FR-062, following the {@code QuicConnection.Inspector} precedent:
	 * an interface declared by the component, <b>absent by default</b>, that a later module implements to
	 * publish JMX statistics without this module depending on {@code boot-jmx-api}.
	 * <p>
	 * It never <i>gates</i> a counter. Every accessor on this class — {@link #requestsServed()},
	 * {@link #connectionsAccepted()}, the rest — reads the same value with an inspector attached and
	 * without one; this is an additional notification seam, never a replacement for them.
	 * <p>
	 * <b>Never carries</b> a field value, a body byte, a cookie, an authorization credential or key
	 * material (FR-063): every parameter here is a number, an HTTP method or a direction. A byte
	 * <i>count</i> is not a byte.
	 * <p>
	 * <b>Threading</b>: every callback runs on this server's reactor thread, inside the operation that
	 * produced the event. An implementation that blocks blocks the reactor, and one that throws fails that
	 * operation — accumulate, never act.
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/** The peer's request head has been decoded and the servlet is about to be invoked. */
		void onRequestStarted(Http3Server server, long streamId, HttpMethod method);

		/**
		 * The response — a rendered error included — has been written in full, including its FIN.
		 *
		 * @param requestBodyBytes  DATA payload bytes received, framing excluded
		 * @param responseBodyBytes DATA payload bytes sent, framing excluded
		 */
		void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes);

		/**
		 * A request stream was aborted, by this server or by the peer, with an RFC 9114 §8.1 application
		 * error code — never an RFC 9000 §20 transport one (FR-061).
		 */
		void onStreamReset(Http3Server server, long streamId, long errorCode);

		/** A connection is closing with an RFC 9114 §8.1 / RFC 9204 §6 application error code. */
		void onConnectionError(Http3Server server, long errorCode);

		/** A frame of an unknown type was skipped unread, per RFC 9114 §9's GREASE rule. */
		void onFrameDiscarded(Http3Server server, long frameType, long declaredLength);

		/** A GOAWAY was announced by this server ({@code SENT}) or by a peer ({@code RECEIVED}). */
		void onGoAway(Http3Server server, GoAwayDirection direction, long id);

		/**
		 * Entries were inserted into one of a connection's two QPACK dynamic tables (FR-018) — by this
		 * server's encoder ({@link QpackTable#ENCODER}) or by a peer's encoder stream
		 * ({@link QpackTable#DECODER}). Silent on a connection whose negotiated capacity is 0, which is
		 * the default and has no table to insert into.
		 *
		 * @param insertions entries inserted, always at least 1
		 * @param tableBytes RFC 9204 §3.2.1 accounted size that table holds afterwards
		 */
		default void onQpackInsertions(Http3Server server, QpackTable table, int insertions, int tableBytes) {}

		/**
		 * Entries were evicted from one of them to make room (RFC 9204 §3.2.2).
		 *
		 * @param evictions  entries evicted, always at least 1
		 * @param tableBytes RFC 9204 §3.2.1 accounted size that table holds afterwards
		 */
		default void onQpackEvictions(Http3Server server, QpackTable table, int evictions, int tableBytes) {}

		/**
		 * A response field section was encoded against the dynamic table: the numerator and denominator of
		 * a dynamic-table hit rate, reported per section so a consumer picks its own window.
		 *
		 * @param fieldLines        field lines the section carried
		 * @param dynamicReferences those of them emitted as a dynamic-table reference, not a literal
		 */
		default void onQpackFieldSectionEncoded(
			Http3Server server, long streamId, int fieldLines, int dynamicReferences) {}

		/**
		 * A request stream became blocked: a field section of its arrived whole but referenced an insertion
		 * the peer's encoder stream has not delivered yet (RFC 9204 §2.1.2, FR-033). Fired once per stream,
		 * not once per section — a stream already blocked does not become blocked again.
		 *
		 * @param blockedStreams streams blocked now, this one included
		 * @param heldBytes      bytes held across all of them, this section included
		 */
		default void onQpackStreamBlocked(Http3Server server, long streamId, int blockedStreams, long heldBytes) {}

		/**
		 * A blocked stream stopped being blocked, one of the four ways {@link QpackBlockedExit} names. Only
		 * {@code DECODED} is the ordinary end of head-of-line blocking; the other three are a peer that
		 * never sent what it referenced.
		 *
		 * @param blockedMillis  how long the stream was blocked — the delay FR-036 bounds
		 * @param blockedStreams streams still blocked after this one left
		 */
		default void onQpackStreamUnblocked(
			Http3Server server, long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams) {}

		/**
		 * A field section was refused rather than held, because holding it would have exceeded a bound on
		 * blocked sections — which closes the connection (FR-034, FR-035). The two numbers are what was
		 * already held when it arrived, and so say which bound was reached.
		 */
		default void onQpackBlockedSectionRefused(
			Http3Server server, long streamId, int blockedStreams, long heldBytes) {}

		/**
		 * A completed handshake sealed its flight of {@code NewSessionTicket} messages (FR-041). Silent
		 * with {@link Http3Settings#zeroRttEnabled()} off, which issues none. Carries no ticket byte, no
		 * sealing key and no resumption secret (SI-6).
		 *
		 * @param tickets       tickets this handshake issued
		 * @param ticketsIssued tickets issued over this server's life, these included
		 */
		default void onSessionTicketsIssued(Http3Server server, int tickets, long ticketsIssued) {}

		/**
		 * A handshake resumed a session from a ticket this server issued, rather than authenticating with
		 * a certificate (RFC 8446 §2.2), and either did or did not take the early data offered with it.
		 * A refusal is not a failure — it leaves a perfectly good resumed session.
		 *
		 * @param earlyDataAccepted whether this connection's 0-RTT packets are being processed
		 * @param sessionsResumed   resumed handshakes over this server's life, this one included
		 * @param zeroRttAccepted   of those, the ones whose early data was accepted
		 */
		default void onSessionResumed(
			Http3Server server, boolean earlyDataAccepted, long sessionsResumed, long zeroRttAccepted) {}

		/**
		 * Early data was refused, and this says which of the four defences refused it (FR-064, FR-069,
		 * FR-070) — the question {@link #onSessionResumed}'s {@code earlyDataAccepted} raises and cannot
		 * answer. Silent with {@link Http3Settings#zeroRttEnabled()} off, which admits no early data to
		 * refuse.
		 * <p>
		 * The three {@linkplain EarlyDataRefusal register} reasons are reported when a handshake
		 * completes, because that is the first moment on this server's reactor after the register has
		 * spoken; the {@linkplain EarlyDataRefusal#POLICY policy} reason is reported as the request is
		 * answered. A refusal is <b>never</b> a failure in either case — the session still resumes, and a
		 * refused request is still answered.
		 * <p>
		 * The register's reasons collapse: several refusals between two completed handshakes produce one
		 * call carrying the reason's running total, and a refusal on a handshake the peer abandons is
		 * reported alongside the next one that completes. The <b>total is exact either way</b>, which is
		 * why it is what the call carries and why {@link #zeroRttRefusedAsReplay()} and its siblings —
		 * which read it directly — are the counters to alert on. Carries no ticket byte and no ticket
		 * identity (SI-6).
		 *
		 * @param refusals refusals for <i>this reason</i> over this server's life, this one included
		 */
		default void onEarlyDataRefused(Http3Server server, EarlyDataRefusal reason, long refusals) {}

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
		default void onDatagramSent(Http3Server server, long streamId, int payloadBytes, long sent) {}

		/**
		 * An HTTP/3 datagram arrived for the exchange on {@code streamId} and was routed to it. One whose
		 * quarter stream ID named no live exchange is dropped rather than routed (FR-082) and is not
		 * counted here.
		 *
		 * @param received datagrams routed over the connection carrying this exchange, this one included
		 */
		default void onDatagramReceived(Http3Server server, long streamId, int payloadBytes, long received) {}

		/**
		 * An exchange's inbound queue was at {@link Http3Settings#maxInboundDatagramsPerStream()}, so its
		 * <b>oldest</b> datagram was dropped to make room (FR-085) — a reader falling behind, never a
		 * protocol error and never a connection closing.
		 *
		 * @param droppedByQueue queue drops over that connection, this one included
		 */
		default void onDatagramDroppedByQueue(Http3Server server, long streamId, long droppedByQueue) {}

		/**
		 * A DATAGRAM frame carrying an HTTP/3 datagram was declared lost and released rather than
		 * retransmitted (RFC 9221 §5). Carries no stream id: what is known lost is the frame, and its
		 * payload is gone by the time anything could re-derive the exchange from it.
		 *
		 * @param droppedByLoss loss drops over that connection, this one included
		 */
		default void onDatagramDroppedByLoss(Http3Server server, long droppedByLoss) {}

		/**
		 * A send was refused whole because its payload exceeded what the peer's
		 * {@code max_datagram_frame_size} leaves — RFC 9221 §3 forbids splitting a datagram, so truncating
		 * it is not an option. The two sizes are the refusal.
		 *
		 * @param refusedOversize oversize refusals over that connection, this one included
		 */
		default void onDatagramRefusedOversize(
			Http3Server server, long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize) {}
	}

	private final AsyncServlet servlet;

	private Http3Settings settings = Http3Settings.create();
	private Http3EarlyDataPolicy earlyDataPolicy = Http3EarlyDataPolicy.DEFAULT_POLICY;
	private HttpExceptionFormatter errorFormatter = HttpExceptionFormatter.COMMON_FORMATTER;
	private @Nullable Inspector inspector;
	private @Nullable TlsServerIdentity serverIdentity;
	private @Nullable InetSocketAddress listenAddress;
	private @Nullable IUdpSocket socket;

	/**
	 * The keys session tickets are sealed under (FR-060). Supplied through {@link Builder#withTicketKeys}
	 * or generated in {@link #listen()}, and consulted only while {@link Http3Settings#zeroRttEnabled()}
	 * — the HTTP/3 switch is the outer one, so configured keys are ignored with 0-RTT off.
	 */
	private @Nullable QuicTicketKeys ticketKeys;

	private @Nullable QuicEndpoint endpoint;
	private boolean listening;
	private boolean closed;

	/** Whether the endpoint has actually gone; {@link #closed} is set as soon as the drain begins. */
	private boolean shutDown;
	private @Nullable ScheduledRunnable drainTimeout;

	/** Exchanges being served right now; an identity set, since two requests are never the same object. */
	private final Set<Http3RequestStream> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * The request streams this server has taken and not yet seen closed — what the GOAWAY drain waits
	 * for. Deliberately not {@link #inFlight}: an exchange is done the moment its last byte is handed to
	 * the transport, while the stream closes only once the peer has acknowledged that byte, and closing
	 * the connection between those two moments would drop the response the drain existed to deliver.
	 */
	private final Set<QuicStream> openStreams = Collections.newSetFromMap(new IdentityHashMap<>());

	/** One per accepted QUIC connection, so that {@link #close()} can announce GOAWAY on each (FR-019). */
	private final Set<Http3Connection> connections = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * What every connection this server builds reports through, forwarded to the {@link Inspector} if one
	 * is attached — the events a server cannot see for itself, because they happen on a connection or
	 * on a stream rather than inside an exchange.
	 */
	private final Http3EventListener connectionEvents = new Http3EventListener() {
		@Override
		public void onConnectionError(long errorCode) {
			if (inspector != null) inspector.onConnectionError(Http3Server.this, errorCode);
		}

		@Override
		public void onFrameDiscarded(long frameType, long declaredLength) {
			if (inspector != null) inspector.onFrameDiscarded(Http3Server.this, frameType, declaredLength);
		}

		@Override
		public void onGoAway(GoAwayDirection direction, long id) {
			if (inspector != null) inspector.onGoAway(Http3Server.this, direction, id);
		}

		@Override
		public void onStreamReset(long streamId, long errorCode) {
			if (inspector != null) inspector.onStreamReset(Http3Server.this, streamId, errorCode);
		}

		@Override
		public void onQpackInsertions(QpackTable table, int insertions, int tableBytes) {
			if (inspector != null) inspector.onQpackInsertions(Http3Server.this, table, insertions, tableBytes);
		}

		@Override
		public void onQpackEvictions(QpackTable table, int evictions, int tableBytes) {
			if (inspector != null) inspector.onQpackEvictions(Http3Server.this, table, evictions, tableBytes);
		}

		@Override
		public void onQpackFieldSectionEncoded(long streamId, int fieldLines, int dynamicReferences) {
			if (inspector != null) {
				inspector.onQpackFieldSectionEncoded(Http3Server.this, streamId, fieldLines, dynamicReferences);
			}
		}

		@Override
		public void onQpackStreamBlocked(long streamId, int blockedStreams, long heldBytes) {
			if (inspector != null) {
				inspector.onQpackStreamBlocked(Http3Server.this, streamId, blockedStreams, heldBytes);
			}
		}

		@Override
		public void onQpackStreamUnblocked(
			long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams
		) {
			if (inspector != null) {
				inspector.onQpackStreamUnblocked(Http3Server.this, streamId, exit, blockedMillis, blockedStreams);
			}
		}

		@Override
		public void onQpackBlockedSectionRefused(long streamId, int blockedStreams, long heldBytes) {
			if (inspector != null) {
				inspector.onQpackBlockedSectionRefused(Http3Server.this, streamId, blockedStreams, heldBytes);
			}
		}

		@Override
		public void onDatagramSent(long streamId, int payloadBytes, long sent) {
			if (inspector != null) inspector.onDatagramSent(Http3Server.this, streamId, payloadBytes, sent);
		}

		@Override
		public void onDatagramReceived(long streamId, int payloadBytes, long received) {
			if (inspector != null) inspector.onDatagramReceived(Http3Server.this, streamId, payloadBytes, received);
		}

		@Override
		public void onDatagramDroppedByQueue(long streamId, long droppedByQueue) {
			if (inspector != null) inspector.onDatagramDroppedByQueue(Http3Server.this, streamId, droppedByQueue);
		}

		@Override
		public void onDatagramDroppedByLoss(long droppedByLoss) {
			if (inspector != null) inspector.onDatagramDroppedByLoss(Http3Server.this, droppedByLoss);
		}

		@Override
		public void onDatagramRefusedOversize(
			long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize
		) {
			if (inspector != null) {
				inspector.onDatagramRefusedOversize(
					Http3Server.this, streamId, payloadBytes, maxPayloadBytes, refusedOversize);
			}
		}
	};

	private long connectionsAccepted;
	private long requestsServed;
	private long requestsFailed;
	private long requestsAborted;
	private long requestsTimedOut;
	private long sessionsResumed;
	private long zeroRttAccepted;
	private long sessionTicketsIssued;
	private long earlyDataRequestsRefused;

	/**
	 * The single-use replay register every connection this server accepts shares (FR-069), built by
	 * {@link #serverEngineFactory()} and {@code null} with {@link Http3Settings#zeroRttEnabled()} off,
	 * where there is no early data to refuse. Held here so its counters are readable — it is the only
	 * thing that knows why a grant was refused.
	 */
	private @Nullable QuicReplayGuard replayGuard;

	/**
	 * What {@link Inspector#onEarlyDataRefused} has already been told, per register reason. The register
	 * is polled rather than pushed from, so these are what turns a total into an event.
	 */
	private long refusedAsReplayReported;
	private long refusedAtCapacityReported;
	private long refusedAsExpiredReported;

	/**
	 * How many {@code NewSessionTicket} messages {@code TlsServerEngine} seals per completed handshake
	 * — read back off the {@link TlsServerConfig} this server builds rather than set on it, so it stays
	 * the one source of truth {@link #serverEngineFactory()} describes. 0 until a handshake has been
	 * configured, and 0 for the life of a server with 0-RTT off, which issues no ticket at all.
	 */
	private int ticketsPerHandshake;

	private Http3Server(NioReactor reactor, AsyncServlet servlet) {
		super(reactor);
		this.servlet = servlet;
	}

	public static Builder builder(NioReactor reactor, AsyncServlet servlet) {
		return new Http3Server(reactor, servlet).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, Http3Server> {
		private Builder() {}

		/** Where {@link #listen()} binds a UDP socket, unless {@link #withSocket} supplies one. */
		public Builder withListenAddress(InetSocketAddress listenAddress) {
			checkNotBuilt(this);
			Http3Server.this.listenAddress = listenAddress;
			return this;
		}

		/** The port on the wildcard address; the shorthand {@link #withListenAddress} usually wants. */
		public Builder withListenPort(int port) {
			checkNotBuilt(this);
			Http3Server.this.listenAddress = new InetSocketAddress(port);
			return this;
		}

		/**
		 * Serves over a socket somebody else opened, instead of binding one — the escape hatch of
		 * FR-059a, and what an in-process test drives.
		 * <p>
		 * The server still owns the {@link QuicEndpoint} above it, because a frame-handler factory is a
		 * <b>build-time</b> property of an endpoint: an endpoint that already exists cannot be told to
		 * route its connections here, so the socket is the lowest seam a caller can share.
		 */
		public Builder withSocket(IUdpSocket socket) {
			checkNotBuilt(this);
			Http3Server.this.socket = socket;
			return this;
		}

		/** The certificate chain and private key this server presents, loaded by feature 02 (FR-042). */
		public Builder withServerIdentity(TlsServerIdentity serverIdentity) {
			checkNotBuilt(this);
			Http3Server.this.serverIdentity = serverIdentity;
			return this;
		}

		public Builder withSettings(Http3Settings settings) {
			checkNotBuilt(this);
			Http3Server.this.settings = settings;
			return this;
		}

		/**
		 * The keys this server seals session tickets under (FR-060) — supply one to share it between
		 * several {@link Http3Server} instances in a process. Absent, {@link #listen()} generates a set
		 * from a fresh {@link SecureRandom}, on the rotation interval and lifetime
		 * {@link QuicConnectionSettings} carries.
		 * <p>
		 * Keys configured here are <b>ignored</b> until {@link Http3Settings#zeroRttEnabled()} is on: the
		 * HTTP/3 switch is the outer one, and with 0-RTT off this server issues no ticket at all, so a
		 * default deployment is byte-for-byte what phase 1 put on the wire (SC-011).
		 * <p>
		 * Turning 0-RTT on brings {@link Builder#withEarlyDataPolicy the early-data policy} with it: an
		 * unsafe method in early data is answered {@code 425 (Too Early)} without reaching the servlet,
		 * and a request that is accepted carries {@code Early-Data: 1} where the servlet can see it. What
		 * no policy can do is span processes — see {@link Builder#withEarlyDataPolicy} — which is one of
		 * the reasons the flag defaults off.
		 * <p>
		 * The keys are runtime state, not configuration: rotation swaps the key set the way a connection
		 * swaps its packet-protection keys, and nothing about this component's configuration changes after
		 * {@code build()}. They are <b>not</b> thread-safe — one set per reactor.
		 */
		public Builder withTicketKeys(QuicTicketKeys ticketKeys) {
			checkNotBuilt(this);
			Http3Server.this.ticketKeys = ticketKeys;
			return this;
		}

		/**
		 * What this server is willing to run from 0-RTT early data (FR-064, FR-065), replacing
		 * {@link Http3EarlyDataPolicy#DEFAULT_POLICY} — RFC 9110 §9.2.1 safe methods and nothing else.
		 * <p>
		 * Consulted for a request whose HEADERS arrived at {@code ZERO_RTT} and for no other, so a policy
		 * set here costs an ordinary 1-RTT deployment nothing and has no effect at all with
		 * {@link Http3Settings#zeroRttEnabled()} off. A request it refuses is answered
		 * {@code 425 (Too Early)} <b>without the servlet being invoked</b>; one it accepts reaches the
		 * servlet carrying RFC 8470's {@code Early-Data: 1}.
		 * <p>
		 * Widening it widens the exposure of a <b>multi-instance</b> deployment, not only of this process:
		 * the single-use replay register behind 0-RTT is process-local, so behind a load balancer the
		 * safe-method rule is the only thing standing between a replayed flight and a second side effect
		 * at another instance.
		 */
		public Builder withEarlyDataPolicy(Http3EarlyDataPolicy earlyDataPolicy) {
			checkNotBuilt(this);
			Http3Server.this.earlyDataPolicy = earlyDataPolicy;
			return this;
		}

		/**
		 * Renders a failed servlet promise, exactly as {@code HttpServer}'s own does — the same default
		 * and the same output, so an error looks identical over HTTP/1.1 and HTTP/3 (FR-045).
		 */
		public Builder withHttpErrorFormatter(HttpExceptionFormatter errorFormatter) {
			checkNotBuilt(this);
			Http3Server.this.errorFormatter = errorFormatter;
			return this;
		}

		/**
		 * Registers the statistics hook of FR-062. Absent by default, and never required: every counter
		 * this server keeps reads the same with one and without one.
		 * <p>
		 * The inspector is <b>never</b> handed a {@link io.activej.bytebuf.ByteBuf}, a field value or a
		 * body byte — see {@link Inspector}.
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			Http3Server.this.inspector = inspector;
			return this;
		}

		@Override
		protected Http3Server doBuild() {
			return Http3Server.this;
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Opens the UDP socket if one was not supplied, builds the {@link QuicEndpoint} over it and starts
	 * its receive loop.
	 *
	 * @return a promise completing once the server is accepting connections; a socket supplied through
	 * {@link Builder#withSocket} makes it complete synchronously
	 */
	public Promise<Void> listen() {
		checkInReactorThread(this);
		if (closed) return Promise.ofException(new AsyncCloseException("This HTTP/3 server is closed"));
		// The flag rather than `endpoint != null`: opening a socket is asynchronous, and a second call
		// while the first is still resolving would bind a second one.
		if (listening) return Promise.complete();
		if (serverIdentity == null) {
			return Promise.ofException(
				new IllegalStateException("An HTTP/3 server needs a TLS server identity — see withServerIdentity"));
		}
		Exception badTicketKeys = ensureTicketKeys();
		if (badTicketKeys != null) return Promise.ofException(badTicketKeys);
		if (socket != null) {
			listening = true;
			startOn(socket);
			return Promise.complete();
		}
		if (listenAddress == null) {
			return Promise.ofException(
				new IllegalStateException("An HTTP/3 server needs a listen address or a socket"));
		}
		DatagramChannel channel;
		try {
			channel = NioReactor.createDatagramChannel(DatagramSocketSettings.create(), listenAddress, null);
		} catch (IOException e) {
			return Promise.ofException(e);
		}
		listening = true;
		return UdpSocket.connect(reactor, channel)
			.whenResult(opened -> {
				socket = opened;
				startOn(opened);
			})
			.whenException(e -> listening = false)
			.toVoid();
	}

	private void startOn(IUdpSocket udpSocket) {
		endpoint = QuicEndpoint.builder(reactor, udpSocket)
			.withSettings(quicSettings())
			.withServerEngineFactory(serverEngineFactory())
			.withFrameHandlerFactory(this::onConnection)
			.build();
		endpoint.listen();
		logger.info("HTTP/3 server listening: {}", this);
	}

	/**
	 * FR-058b: the transport parameters this feature depends on are supplied as <i>values</i> here;
	 * encoding them is the transport's job and no part of this module's. Two are unconditional — the
	 * stream-related pair — and one is conditional on an {@link Http3Settings} switch. This private
	 * mapping is deliberately the <b>only</b> way an H3-level setting reaches the transport: there is no
	 * public {@code withQuicSettings} pass-through, because a consumer handed the whole
	 * {@link QuicConnectionSettings} could set a parameter that contradicts what this layer requires —
	 * {@code initialMaxStreamsUni < 3} alone breaks the control stream plus both QPACK streams (FR-017).
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
	 * Generates the ticket-sealing keys of FR-060, unless 0-RTT is off or a consumer supplied a set.
	 * <p>
	 * It runs in {@link #listen()} rather than in {@code doBuild()} because it can <b>fail on a
	 * configuration pair {@code QuicConnectionSettings.build()} accepts</b>: that builder refuses only
	 * {@code sessionTicketLifetime > 2 × sessionTicketKeyRotation}, the best case, while
	 * {@link QuicTicketKeys#create} demands the worst — a ticket sealed just before a rotation must still
	 * be openable, which needs {@code lifetime <= (RETAINED_KEYS - 1) × rotation}. The defaults (1 h
	 * lifetime, 6 h rotation) satisfy both; {@code -DQuicConnection.sessionTicketLifetime=10h} satisfies
	 * only the first, and must fail {@code listen()} with a message naming the two settings rather than
	 * throw out of a void path.
	 *
	 * @return the failure {@code listen()} should report, or {@code null} if there is none
	 */
	private @Nullable Exception ensureTicketKeys() {
		if (!settings.zeroRttEnabled() || ticketKeys != null) return null;
		QuicConnectionSettings quic = quicSettings();
		try {
			ticketKeys = QuicTicketKeys.create(new SecureRandom(), quic.sessionTicketKeyRotationMillis(),
				quic.sessionTicketLifetimeMillis(), reactor.currentTimeMillis());
			return null;
		} catch (IllegalArgumentException e) {
			return new IllegalStateException("HTTP/3 0-RTT is enabled, but no session-ticket keys can be built for" +
				" a sessionTicketLifetime of " + quic.sessionTicketLifetimeMillis() + " ms and a" +
				" sessionTicketKeyRotation of " + quic.sessionTicketKeyRotationMillis() + " ms — see the" +
				" QuicConnection.sessionTicketLifetime and QuicConnection.sessionTicketKeyRotation settings", e);
		}
	}

	/**
	 * With {@link Http3Settings#zeroRttEnabled()} off — the default — this is phase 1's factory verbatim:
	 * no ticket keys means no {@code NewSessionTicket} is ever issued, so nothing about a handshake
	 * changes (SC-011).
	 * <p>
	 * The ticket lifetime, the tickets-per-handshake count and the age tolerance are deliberately left
	 * unset: {@link TlsServerConfig}'s defaults already mirror the {@link QuicConnectionSettings} ones,
	 * and with keys present the lifetime follows {@link QuicTicketKeys#ticketLifetimeMillis()} — one
	 * source of truth rather than two that can drift.
	 * <p>
	 * The replay register (spec FR-069, RFC 8446 §8) is built <b>here rather than inside the returned
	 * factory</b>, so every connection this server accepts shares one: a replayed early-data flight
	 * arrives on a <i>new</i> connection by construction, and a per-connection register would refuse
	 * nothing. Its bound is {@link QuicConnectionSettings#maxEarlyDataReplayRecords()}. Turning 0-RTT on
	 * therefore never yields an unguarded server — {@code withEarlyDataEnabled(true)} and
	 * {@code withReplayGuard(...)} are set in the same breath and there is no way to have one without
	 * the other.
	 */
	private TlsEngineFactory serverEngineFactory() {
		TlsServerIdentity identity = serverIdentity;
		if (!settings.zeroRttEnabled()) {
			return params -> QuicTls.serverEngine(TlsServerConfig.builder(identity, params).build());
		}
		QuicTicketKeys keys = ticketKeys;
		QuicReplayGuard guard = QuicReplayGuard.create(quicSettings().maxEarlyDataReplayRecords());
		replayGuard = guard;
		return params -> {
			TlsServerConfig config = TlsServerConfig.builder(identity, params)
				.withCurrentTimeMillis(reactor::currentTimeMillis)
				.withTicketKeys(keys)
				.withEarlyDataEnabled(true)
				.withReplayGuard(guard)
				.build();
			ticketsPerHandshake = config.sessionTicketsPerHandshake();
			return QuicTls.serverEngine(config);
		};
	}

	/**
	 * Shuts down gracefully (FR-019): every connection announces GOAWAY carrying the identifier from which
	 * on it will process nothing — one past the last request stream it took, since RFC 9114 §5.2's
	 * identifier is exclusive — the exchanges already under way are left to finish, and only then does the
	 * endpoint go: closing every connection it holds, each of which aborts its own streams, so an
	 * in-flight request fails once rather than being left pending.
	 * <p>
	 * Idempotent (WI-9): the server counts as closed from the first call, so a second one announces
	 * nothing further and completes nothing a second time. {@link #listen()} refuses from that moment
	 * too, drain or no drain.
	 * <p>
	 * The drain is bounded by {@link Http3Settings#shutdownTimeoutMillis()} — a peer that never finishes
	 * its exchange must not be able to hold a closing server open — and is skipped entirely when there is
	 * nothing left to drain, which is the ordinary case.
	 */
	@Override
	public void close() {
		checkInReactorThread(this);
		if (closed) return;
		closed = true;

		// Announced first, and on a copy: the endpoint may close below, and closing a connection runs
		// continuations that reach back into this set.
		for (Http3Connection connection : new ArrayList<>(connections)) {
			connection.goAway();
		}

		long drainMillis = settings.shutdownTimeoutMillis();
		if (openStreams.isEmpty() || drainMillis == 0) {
			closeNow();
			return;
		}
		logger.info("HTTP/3 server going away, draining {} request stream(s): {}", openStreams.size(), this);
		drainTimeout = reactor.delay(drainMillis, () -> {
			logger.info("HTTP/3 server shutdown drain expired after {} ms with {} request stream(s) left",
				drainMillis, openStreams.size());
			closeNow();
		});
	}

	/** Ends the drain, whether it finished or expired, and releases everything under the endpoint. Idempotent. */
	private void closeNow() {
		if (shutDown) return;
		shutDown = true;
		drainTimeout = nullify(drainTimeout, ScheduledRunnable::cancel);
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

	// ---------------------------------------------------------------- serving

	/**
	 * One {@link Http3Connection} per accepted QUIC connection, which answers with the frame handler the
	 * transport asked for (FR-058a) — its {@code QuicStreamManager} alone, or, with
	 * {@link Http3Settings#datagramsEnabled()}, a composite that also routes RFC 9221 DATAGRAM frames.
	 */
	private QuicFrameHandler onConnection(QuicConnection quicConnection) {
		connectionsAccepted++;
		// Pruned on accept rather than tracked per connection: a closed connection has nothing left to be
		// told, and this bounds the set by the connections actually alive between two accepts.
		connections.removeIf(connection -> connection.state() == Http3Connection.State.CLOSED);

		Http3Connection h3 = Http3Connection.builder(reactor, quicConnection)
			.withSettings(settings)
			.withEarlyDataPolicy(earlyDataPolicy)
			.withRequestStreamListener(this::serve)
			.withEventListener(connectionEvents)
			.build();
		connections.add(h3);
		quicConnection.whenEstablished().whenResult(this::onHandshakeComplete);
		QuicFrameHandler handler = h3.startAndGetFrameHandler();
		// A connection admitted while this server is draining is told so at once: it will be given no
		// request, and every stream it opens is refused with H3_REQUEST_REJECTED (RFC 9114 §5.2).
		if (closed) h3.goAway();
		return handler;
	}

	/**
	 * The resumption bookkeeping of one accepted connection, taken where every part of it is finally
	 * known: whether a pre-shared key was accepted, whether early data was accepted with it, and — since
	 * {@code TlsServerEngine} seals its {@code NewSessionTicket} flight at exactly this point
	 * (RFC 8446 §4.6.1) — how many tickets went out.
	 * <p>
	 * A server keeps none of what it issues, deliberately: a ticket is sealed state, not a dictionary
	 * key into anything this process holds. So the ticket count is the configured per-handshake number
	 * rather than a tally of objects, and it stays 0 with {@link Http3Settings#zeroRttEnabled()} off,
	 * where no key set exists and the engine issues nothing.
	 */
	private void onHandshakeComplete(QuicConnection quicConnection) {
		reportRegisterRefusals();
		if (ticketsPerHandshake > 0) {
			sessionTicketsIssued += ticketsPerHandshake;
			if (inspector != null) {
				inspector.onSessionTicketsIssued(this, ticketsPerHandshake, sessionTicketsIssued);
			}
		}
		if (!quicConnection.isSessionResumed()) return;
		sessionsResumed++;
		boolean earlyData = quicConnection.isEarlyDataAccepted();
		if (earlyData) zeroRttAccepted++;
		if (inspector != null) inspector.onSessionResumed(this, earlyData, sessionsResumed, zeroRttAccepted);
	}

	/**
	 * Turns the register's totals into {@link Inspector#onEarlyDataRefused} calls, at the first moment on
	 * this reactor after the register can have spoken.
	 * <p>
	 * Polled rather than pushed from, because {@link QuicReplayGuard} is a plain data structure consulted
	 * from inside a TLS engine that knows nothing of this server — and because a refusal happens while a
	 * ClientHello is being processed, where there is no connection this class has finished wiring. The
	 * cost of polling is that a burst between two completed handshakes collapses into one call per reason;
	 * the total each call carries is exact regardless, which is what the accessors read.
	 */
	private void reportRegisterRefusals() {
		QuicReplayGuard guard = replayGuard;
		if (guard == null) return;
		refusedAsReplayReported = report(EarlyDataRefusal.REPLAYED, guard.refusedReplayed(), refusedAsReplayReported);
		refusedAtCapacityReported =
			report(EarlyDataRefusal.AT_CAPACITY, guard.refusedAtCapacity(), refusedAtCapacityReported);
		refusedAsExpiredReported = report(EarlyDataRefusal.EXPIRED, guard.refusedExpired(), refusedAsExpiredReported);
	}

	/** @return the new "already reported" mark for {@code reason}, whether or not anything was reported */
	private long report(EarlyDataRefusal reason, long total, long reported) {
		if (total > reported && inspector != null) inspector.onEarlyDataRefused(this, reason, total);
		return total;
	}

	/** FR-043: one client-initiated bidirectional stream is one request. */
	private void serve(Http3RequestStream requestStream) {
		QuicStream stream = requestStream.quicStream();
		inFlight.add(requestStream);
		openStreams.add(stream);

		long timeoutMillis = settings.requestTimeoutMillis();
		ScheduledRunnable timeout = timeoutMillis == 0 ? null : reactor.delay(timeoutMillis, () -> {
			requestsTimedOut++;
			// FR-046a: the abort fails whatever the servlet is waiting on, and releases every buffer this
			// exchange owns. A response the servlet still produces afterwards is released by sendResponse.
			requestStream.abort(Http3Errors.H3_REQUEST_CANCELLED,
				"The request exceeded the " + timeoutMillis + " ms request timeout");
		});

		requestStream.receiveRequest()
			.then(
				request -> {
					if (inspector != null) inspector.onRequestStarted(this, stream.id(), request.getMethod());
					return serveSafely(request);
				},
				// FR-064: the request stream refused this exchange's early data and never handed the
				// request over, so there is nothing here to dispatch — only RFC 8470 §5.2's answer to
				// write, rendered through the same formatter a servlet's own HttpError would take.
				e -> {
					if (!(e instanceof HttpError refusal)) return Promise.ofException(e);
					earlyDataRequestsRefused++;
					if (inspector != null) {
						inspector.onEarlyDataRefused(this, EarlyDataRefusal.POLICY, earlyDataRequestsRefused);
					}
					return errorFormatter.formatException(refusal);
				})
			.then(response -> {
				// Read before the send, which takes ownership of the response.
				int statusCode = response.getCode();
				return requestStream.sendResponse(response)
					.whenResult(() -> {
						if (inspector == null) return;
						inspector.onRequestCompleted(this, stream.id(), statusCode,
							requestStream.bodyBytesReceived(), requestStream.bodyBytesSent());
					});
			})
			.whenComplete(($, e) -> {
				cancel(timeout);
				boolean firstToFinish = inFlight.remove(requestStream);
				if (e == null) {
					requestsServed++;
					return;
				}
				if (firstToFinish) requestsAborted++;
				logger.trace("HTTP/3 request stream {} did not complete: {}", stream.id(), e.toString());
			});

		// The stream can end without the exchange doing so — a peer reset, a timeout, or this server
		// closing while a servlet still holds an unresolved promise, which no Promise can be cancelled
		// out of. This releases the slot at the moment the request stops being served, rather than at the
		// moment a continuation nobody is waiting for finally runs.
		stream.whenClosed().whenComplete(($, e) -> {
			if (inFlight.remove(requestStream)) {
				cancel(timeout);
				requestsAborted++;
			}
			openStreams.remove(stream);
			// FR-019: the last stream this server announced it would process has gone, so the drain is over
			// and there is nothing left for the connections to carry.
			if (closed && openStreams.isEmpty()) closeNow();
		});
	}

	private static void cancel(@Nullable ScheduledRunnable timeout) {
		if (timeout != null) timeout.cancel();
	}

	/**
	 * FR-045: a servlet failure becomes a response through the same {@link HttpExceptionFormatter}
	 * {@code core-http}'s {@code HttpServer} uses, so a rendered error is indistinguishable across
	 * versions — and the stream that carries it ends normally, because a 404 or a 500 is an HTTP outcome
	 * rather than an HTTP/3 protocol violation.
	 * <p>
	 * Two failures are deliberately <b>not</b> rendered: an {@link Http3Exception}, which is this
	 * connection's own protocol error and has already aborted the stream with its RFC 9114 §8.1 code, and
	 * a {@link QuicStreamException}, which is the transport reporting that the stream is already gone.
	 * Rendering either would answer a stream that can no longer carry an answer.
	 */
	private Promise<HttpResponse> serveSafely(HttpRequest request) {
		Promise<HttpResponse> served;
		try {
			served = servlet.serve(request);
		} catch (Exception e) {
			served = Promise.ofException(e);
		}
		return served.then(Promise::of, e -> {
			if (e instanceof Http3Exception || e instanceof QuicStreamException) {
				return Promise.ofException(e);
			}
			requestsFailed++;
			return errorFormatter.formatException(e);
		});
	}

	// ---------------------------------------------------------------- counters (FR-062)

	public long connectionsAccepted() {
		checkInReactorThread(this);
		return connectionsAccepted;
	}

	/** Requests answered with a response fully written, a rendered error included. */
	public long requestsServed() {
		checkInReactorThread(this);
		return requestsServed;
	}

	/** Servlet failures rendered through the error formatter. */
	public long requestsFailed() {
		checkInReactorThread(this);
		return requestsFailed;
	}

	/** Requests whose stream ended without a response — an H3 protocol error, an abort, or a timeout. */
	public long requestsAborted() {
		checkInReactorThread(this);
		return requestsAborted;
	}

	public long requestsTimedOut() {
		checkInReactorThread(this);
		return requestsTimedOut;
	}

	public int activeRequests() {
		checkInReactorThread(this);
		return inFlight.size();
	}

	/**
	 * {@code NewSessionTicket} messages this server has issued (FR-041). 0 with
	 * {@link Http3Settings#zeroRttEnabled()} off.
	 */
	public long sessionTicketsIssued() {
		checkInReactorThread(this);
		return sessionTicketsIssued;
	}

	/** Handshakes that resumed a session from one of those tickets rather than exchanging a certificate. */
	public long sessionsResumed() {
		checkInReactorThread(this);
		return sessionsResumed;
	}

	/**
	 * Of those, the ones whose early data this server accepted, so the peer's 0-RTT packets were
	 * processed (FR-052). Never greater than {@link #sessionsResumed()}.
	 */
	public long zeroRttAccepted() {
		checkInReactorThread(this);
		return zeroRttAccepted;
	}

	/**
	 * Resumption attempts whose early data the single-use register refused because the ticket identity had
	 * already bought a use (FR-069, RFC 8446 §8) — the replay counter, and the one to alert on. Every
	 * refusal left a perfectly good 1-RTT session behind it.
	 * <p>
	 * <b>It counts this process only.</b> The register is process-local and reactor-local, one per
	 * {@code Http3Server}, so a flight replayed onto another worker, process or node is not among these —
	 * see {@link Builder#withEarlyDataPolicy}. 0 with {@link Http3Settings#zeroRttEnabled()} off, which
	 * builds no register.
	 */
	public long zeroRttRefusedAsReplay() {
		checkInReactorThread(this);
		return replayGuard == null ? 0 : replayGuard.refusedReplayed();
	}

	/**
	 * Resumption attempts whose early data the register refused for want of room (FR-070). An
	 * availability signal rather than a security one: the register never drops a live record, so it
	 * degrades towards refusing every new grant instead of admitting a replay, and a number climbing here
	 * says {@code maxEarlyDataReplayRecords} is too small for the ticket lifetime in force.
	 */
	public long zeroRttRefusedAtCapacity() {
		checkInReactorThread(this);
		return replayGuard == null ? 0 : replayGuard.refusedAtCapacity();
	}

	/**
	 * Resumption attempts whose early data the register refused as past the ticket's lifetime. Expected to
	 * stay 0: the TLS engine skips an expired ticket while selecting the pre-shared key, well before the
	 * register is consulted, so this is the register's own defence in depth rather than a live path.
	 */
	public long zeroRttRefusedAsExpired() {
		checkInReactorThread(this);
		return replayGuard == null ? 0 : replayGuard.refusedExpired();
	}

	/**
	 * Requests that arrived at {@code ZERO_RTT} and were answered {@code 425 (Too Early)} by the
	 * {@linkplain Builder#withEarlyDataPolicy early-data policy} without the servlet being invoked
	 * (FR-064). Counted per <i>request</i>, not per connection, and unrelated to the register's three: a
	 * connection whose early data was granted can still have every request in it refused here.
	 * <p>
	 * Under the default policy this is an unsafe method in early data, which the client re-issues once at
	 * 1-RTT (FR-067) — so a non-zero number is ordinary traffic meeting the safe-method rule, and each one
	 * cost a round trip a client-side {@code Http3EarlyData} opt-in could have avoided.
	 */
	public long earlyDataRequestsRefused() {
		checkInReactorThread(this);
		return earlyDataRequestsRefused;
	}

	@Override
	public String toString() {
		return "Http3Server{" +
			(listenAddress != null ? listenAddress : "socket=" + socket) +
			(closed ? ", closed" : "") +
			", activeRequests=" + inFlight.size() + '}';
	}
}
