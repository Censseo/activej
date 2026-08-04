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
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpExceptionFormatter;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
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
	}

	private final AsyncServlet servlet;

	private Http3Settings settings = Http3Settings.create();
	private HttpExceptionFormatter errorFormatter = HttpExceptionFormatter.COMMON_FORMATTER;
	private @Nullable Inspector inspector;
	private @Nullable TlsServerIdentity serverIdentity;
	private @Nullable InetSocketAddress listenAddress;
	private @Nullable IUdpSocket socket;

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
	 * is attached — the four events a server cannot see for itself, because they happen on a connection or
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
	};

	private long connectionsAccepted;
	private long requestsServed;
	private long requestsFailed;
	private long requestsAborted;
	private long requestsTimedOut;

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
	 * FR-058b: the two stream-related transport parameters this feature depends on are supplied as
	 * <i>values</i> here; encoding them is the transport's job and no part of this module's.
	 */
	private QuicConnectionSettings quicSettings() {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(settings.maxConcurrentRequestStreams())
			.withInitialMaxStreamsUni(settings.maxUniStreams())
			.build();
	}

	private TlsEngineFactory serverEngineFactory() {
		TlsServerIdentity identity = serverIdentity;
		return params -> QuicTls.serverEngine(TlsServerConfig.builder(identity, params).build());
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
	 * One {@link Http3Connection} per accepted QUIC connection; its {@code QuicStreamManager} is the
	 * frame handler the transport asked for (FR-058a).
	 */
	private QuicFrameHandler onConnection(QuicConnection quicConnection) {
		connectionsAccepted++;
		// Pruned on accept rather than tracked per connection: a closed connection has nothing left to be
		// told, and this bounds the set by the connections actually alive between two accepts.
		connections.removeIf(connection -> connection.state() == Http3Connection.State.CLOSED);

		Http3Connection h3 = Http3Connection.builder(reactor, quicConnection)
			.withSettings(settings)
			.withRequestStreamListener(this::serve)
			.withEventListener(connectionEvents)
			.build();
		connections.add(h3);
		QuicFrameHandler handler = h3.startAndGetStreamManager();
		// A connection admitted while this server is draining is told so at once: it will be given no
		// request, and every stream it opens is refused with H3_REQUEST_REJECTED (RFC 9114 §5.2).
		if (closed) h3.goAway();
		return handler;
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
			.whenResult(request -> {
				if (inspector != null) inspector.onRequestStarted(this, stream.id(), request.getMethod());
			})
			.then(this::serveSafely)
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

	@Override
	public String toString() {
		return "Http3Server{" +
			(listenAddress != null ? listenAddress : "socket=" + socket) +
			(closed ? ", closed" : "") +
			", activeRequests=" + inFlight.size() + '}';
	}
}
