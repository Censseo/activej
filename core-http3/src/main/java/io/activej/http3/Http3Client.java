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

import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsResponse;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IHttpClient;
import io.activej.http.MalformedHttpException;
import io.activej.http.Protocol;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.net.socket.udp.IUdpSocket;
import io.activej.net.socket.udp.UdpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.tls.QuicTls;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	}

	private final IDnsClient dnsClient;

	private Http3Settings settings = Http3Settings.create();
	private Function<String, TlsEngineFactory> tlsEngineFactory = Http3Client::defaultTlsEngineFactory;
	private @Nullable Inspector inspector;
	private @Nullable IUdpSocket socket;

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

	/** Exchanges in flight; an identity set, since two requests are never the same object. */
	private final Set<Exchange> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * What every connection this client dials reports through, forwarded to the {@link Inspector} if one
	 * is attached — the four events a client cannot see for itself, because they happen on a connection or
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
	};

	/** Requests holding a withheld {@code openBidirectional()}; bounded by {@code maxQueuedRequests}. */
	private int queuedRequests;

	private long requestsIssued;
	private long requestsQueued;
	private long requestsFailed;
	private long requestsTimedOut;
	private long connectionsOpened;
	private long connectionsEvicted;

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
		 */
		public Builder withTlsEngineFactory(Function<String, TlsEngineFactory> tlsEngineFactory) {
			checkNotBuilt(this);
			Http3Client.this.tlsEngineFactory = tlsEngineFactory;
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
		private @Nullable PooledConnection connection;
		private @Nullable Http3RequestStream requestStream;
		private boolean queued;
		private boolean requestSent;

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
			connectionTo(authority, host, port)
				.then(this::openStream)
				.then(this::exchange)
				.subscribe((response, e) -> {
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

		private void succeed(HttpResponse response) {
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
			result.setException(clientVisible(e));
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

	/**
	 * What a caller sees instead of what this client raised, for the one failure {@code core-http} already
	 * has a name for: a response that is not a well-formed HTTP message is a {@link MalformedHttpException}
	 * here exactly as it is from {@code HttpClient}, so code written against {@link IHttpClient} catches
	 * the same type whichever implementation is under it (FR-047).
	 * <p>
	 * Exactly the message-error class is translated. {@code H3_MESSAGE_ERROR} is the code RFC 9114 §4.1.2
	 * reserves for a message that is not fully formed — a missing or duplicated pseudo-header, an uppercase
	 * field name, a connection-specific field, a {@code Content-Length} that disagrees with the body — and
	 * nothing else raised here means "the response was malformed": a limit, a timeout, a rejection and a
	 * transport failure each say something a caller would act on differently, and each keeps its own type
	 * (FR-058c). The {@link Http3Exception} is the cause, so its error code is not lost.
	 * <p>
	 * Client-side only, as {@code contracts/java-api.md} §2.4 states it: a server answers a malformed
	 * request with a stream reset carrying the same code, and has no promise to fail.
	 */
	private static Exception clientVisible(Exception e) {
		if (e instanceof Http3Exception h3 && h3.errorCode() == Http3Errors.H3_MESSAGE_ERROR) {
			return new MalformedHttpException(h3.reason(), h3);
		}
		return e;
	}

	// ---------------------------------------------------------------- the connection pool

	/** One pooled QUIC connection and the bookkeeping FR-049's eviction rule needs. */
	private static final class PooledConnection {
		private final Http3Connection h3;

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
		Promise<QuicConnection> established = quicEndpoint.connectTo(address, tlsEngineFactory.apply(host));
		Http3Connection h3 = justDialled;
		justDialled = null;
		if (h3 == null) {
			// Unreachable short of a QuicEndpoint that stopped applying its frame-handler factory, or a
			// connectTo that refused before building anything — the latter already failed the promise.
			return established.then(($, e) -> Promise.ofException(e != null ?
				e :
				new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "The dialled QUIC connection carries no HTTP/3 layer")));
		}
		return established.map($ -> new PooledConnection(h3));
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
	 * FR-058b: the two stream-related transport parameters this feature depends on are supplied as
	 * <i>values</i>; encoding them is the transport's job and no part of this module's. A client advertises
	 * them for the streams a <i>server</i> might open toward it — which in HTTP/3 is its control and QPACK
	 * streams and nothing else.
	 */
	private QuicConnectionSettings quicSettings() {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(settings.maxConcurrentRequestStreams())
			.withInitialMaxStreamsUni(settings.maxUniStreams())
			.build();
	}

	/** One {@link Http3Connection} per dialled QUIC connection; its stream manager is the frame handler. */
	private QuicFrameHandler onConnection(QuicConnection quicConnection) {
		Http3Connection h3 = Http3Connection.builder(reactor, quicConnection)
			.withSettings(settings)
			.withEventListener(connectionEvents)
			.build();
		justDialled = h3;
		return h3.startAndGetStreamManager();
	}

	private static TlsEngineFactory defaultTlsEngineFactory(String host) {
		return params -> QuicTls.clientEngine(TlsClientConfig.builder(host, params).build());
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
