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

package io.activej.http3.testutil;

import io.activej.http.HttpError;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection;
import io.activej.http3.Http3EarlyDataPolicy;
import io.activej.http3.Http3Errors;
import io.activej.http3.Http3Exception;
import io.activej.http3.Http3RequestStream;
import io.activej.http3.Http3Settings;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.stream.QuicStreamException;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsServerConfig;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-built HTTP/3 server half over a real {@link Http3Connection} — the counterpart of
 * {@link Http3TestPeer}, and the seam a rejection test needs.
 * <p>
 * <b>Why not {@code Http3Server}.</b> {@code Http3Server} builds its own {@link TlsServerConfig} and
 * sets {@code earlyDataEnabled(true)} whenever {@code Http3Settings.zeroRttEnabled()} is on, with no
 * builder call in between — deliberately, since the two switches mean the same thing to a consumer.
 * A test of the <b>rejection</b> path needs the pair pulled apart: a server that seals and accepts
 * session tickets (so the PSK resumes) while omitting {@code early_data} from EncryptedExtensions (so
 * the early data is refused), which is exactly {@code withTicketKeys(...)} plus
 * {@code withEarlyDataEnabled(false)}. Everything else here is the production stack: a real
 * {@link QuicEndpoint}, a real TLS 1.3 handshake against {@link Http3TestTls}'s dev identity, and a
 * real {@link Http3Connection} per accepted connection.
 * <p>
 * <b>What "early data" means to a {@link Handler} here.</b> {@link RequestContext#arrivalLevel()} is
 * the {@code EncryptionLevel} the request's HEADERS actually arrived at, read straight off
 * {@link Http3RequestStream#headersArrivalLevel()} (FR-064a), and {@link RequestContext#earlyData()}
 * is that being {@code ZERO_RTT}. Until Phase 7 carried the level through the stream layer this was
 * approximated by "the handshake had not finished yet", which is a property of the connection rather
 * than of the exchange.
 */
public final class Http3TestServer implements AutoCloseable {
	private final StubUdpSocket socket;

	private Http3Settings settings = Http3Settings.create();
	private Http3EarlyDataPolicy earlyDataPolicy = Http3EarlyDataPolicy.DEFAULT_POLICY;
	private boolean earlyDataEnabled = true;
	private Handler handler = (request, context) -> HttpResponse.ok200().toPromise();

	private @Nullable QuicEndpoint endpoint;

	private final List<Long> requestStreamIds = new ArrayList<>();
	private final List<Long> streamsOpened = new ArrayList<>();
	private final List<Long> abortedStreamIds = new ArrayList<>();
	private int connectionsAccepted;
	private int sessionsResumed;
	private int zeroRttAccepted;

	public Http3TestServer(StubUdpSocket socket) {
		this.socket = socket;
	}

	/** What this server answers with. The response is owned by the request stream from the moment it is returned. */
	@FunctionalInterface
	public interface Handler {
		Promise<HttpResponse> serve(HttpRequest request, RequestContext context);
	}

	/**
	 * @param streamId     the request stream this request arrived on — {@code 0}, {@code 4}, {@code 8} …
	 *                     in the order the client opened them (RFC 9000 §2.1)
	 * @param arrivalLevel the encryption level this request's HEADERS arrived at (FR-064a)
	 */
	public record RequestContext(long streamId, @Nullable EncryptionLevel arrivalLevel) {
		/** Whether this request was carried in early data — RFC 8470's question, per exchange. */
		public boolean earlyData() {
			return arrivalLevel == EncryptionLevel.ZERO_RTT;
		}
	}

	// ---------------------------------------------------------------- configuration, before start()

	/**
	 * Whether this server echoes {@code early_data} in EncryptedExtensions. With it {@code false} the
	 * PSK is still accepted and the session still resumes — refusing early data is never a handshake
	 * failure (FR-048) — but the 0-RTT packet-protection keys are never installed, so every 0-RTT packet
	 * is dropped as undecryptable and nothing the client sent in early data reaches a {@link Handler}.
	 */
	public Http3TestServer withEarlyDataEnabled(boolean earlyDataEnabled) {
		checkNotStarted();
		this.earlyDataEnabled = earlyDataEnabled;
		return this;
	}

	public Http3TestServer withSettings(Http3Settings settings) {
		checkNotStarted();
		this.settings = settings;
		return this;
	}

	/**
	 * Replaces {@link Http3EarlyDataPolicy#DEFAULT_POLICY} (FR-065) — which this server inherits from
	 * {@link Http3RequestStream} without asking for it, exactly as any hand-wired consumer does. A test
	 * whose subject is the <b>client's</b> rule sets a permissive one here, so that the server's rule
	 * cannot be what its assertions are reading.
	 */
	public Http3TestServer withEarlyDataPolicy(Http3EarlyDataPolicy earlyDataPolicy) {
		checkNotStarted();
		this.earlyDataPolicy = earlyDataPolicy;
		return this;
	}

	public Http3TestServer withHandler(Handler handler) {
		checkNotStarted();
		this.handler = handler;
		return this;
	}

	/** Builds the endpoint and binds it to the socket. Nothing is accepted until a client dials. */
	public Http3TestServer start() {
		checkNotStarted();
		NioReactor reactor = reactor();
		QuicConnectionSettings quic = quicSettings();
		QuicTicketKeys ticketKeys = QuicTicketKeys.create(new SecureRandom(), quic.sessionTicketKeyRotationMillis(),
			quic.sessionTicketLifetimeMillis(), reactor.currentTimeMillis());
		endpoint = QuicEndpoint.builder(reactor, socket)
			.withSettings(quic)
			.withServerEngineFactory(params -> QuicTls.serverEngine(
				TlsServerConfig.builder(Http3TestTls.devIdentity(), params)
					.withCurrentTimeMillis(reactor::currentTimeMillis)
					.withTicketKeys(ticketKeys)
					.withEarlyDataEnabled(earlyDataEnabled)
					.build()))
			.withFrameHandlerFactory(this::onConnection)
			.build();
		endpoint.listen();
		return this;
	}

	// ---------------------------------------------------------------- what the test reads

	/**
	 * The request streams that actually <b>delivered a request</b>, in order — across every connection
	 * since {@link #startRecording()}.
	 * <p>
	 * <b>Not</b> "the streams this server opened", and the difference is RFC 9000 §2.1: naming a stream
	 * implicitly opens every lower-numbered stream of its type, so a client that re-creates a refused
	 * early-data request on stream {@code 4} necessarily makes the server open stream {@code 0} as well —
	 * empty, and for ever. Recording opens would therefore report {@code [0, 4]} for a re-creation and
	 * {@code [0]} for a retransmission of the early data, which is precisely the distinction a rejection
	 * test needs and would be reading the one number that cannot express it. {@link #streamsOpened()} is
	 * the raw view for a test that wants it.
	 */
	public List<Long> requestStreamIds() {
		return List.copyOf(requestStreamIds);
	}

	/**
	 * Every request stream this server opened, including the ones RFC 9000 §2.1 opened implicitly and the
	 * ones that never carried a byte.
	 */
	public List<Long> streamsOpened() {
		return List.copyOf(streamsOpened);
	}

	/**
	 * The request streams the <b>peer</b> aborted — a {@code RESET_STREAM} or a {@code STOP_SENDING}
	 * naming them. A stream discarded because its early data was refused must never appear here: the
	 * server never saw it, so an abort would be the first it ever heard of it.
	 */
	public List<Long> abortedStreamIds() {
		return List.copyOf(abortedStreamIds);
	}

	public int connectionsAccepted() {
		return connectionsAccepted;
	}

	/** Handshakes that accepted a pre-shared key, whether or not early data went with it. */
	public int sessionsResumed() {
		return sessionsResumed;
	}

	public int zeroRttAccepted() {
		return zeroRttAccepted;
	}

	/** Forgets everything recorded so far, so an assertion can speak about the exchange that follows. */
	public void startRecording() {
		requestStreamIds.clear();
		streamsOpened.clear();
		abortedStreamIds.clear();
		connectionsAccepted = 0;
		sessionsResumed = 0;
		zeroRttAccepted = 0;
	}

	// ---------------------------------------------------------------- serving

	private QuicFrameHandler onConnection(QuicConnection quicConnection) {
		connectionsAccepted++;
		quicConnection.whenEstablished().whenResult(established -> {
			if (!established.isSessionResumed()) return;
			sessionsResumed++;
			if (established.isEarlyDataAccepted()) zeroRttAccepted++;
		});
		return Http3Connection.builder(reactor(), quicConnection)
			.withSettings(settings)
			.withEarlyDataPolicy(earlyDataPolicy)
			.withRequestStreamListener(this::serve)
			.build()
			.startAndGetStreamManager();
	}

	private void serve(Http3RequestStream requestStream) {
		long streamId = requestStream.id();
		streamsOpened.add(streamId);
		requestStream.receiveRequest()
			.then(
				request -> {
					// Recorded here rather than at the open, because an open says nothing: RFC 9000 §2.1 opens
					// every lower-numbered stream of a type implicitly — see requestStreamIds().
					requestStreamIds.add(streamId);
					return handler.serve(request,
						new RequestContext(streamId, requestStream.headersArrivalLevel()));
				},
				// FR-064: the early-data policy refused this request, so there is none to hand a handler —
				// only RFC 8470 §5.2's answer to write. The whole of what a hand-wired consumer owes.
				e -> e instanceof HttpError refusal ?
					HttpResponse.ofCode(refusal.getCode()).toPromise() :
					Promise.ofException(e))
			.then(requestStream::sendResponse)
			.whenException(e -> {
				if (e instanceof QuicStreamException) abortedStreamIds.add(streamId);
				// The stream owns the request it built until a response or an abort releases it (FR-057a),
				// so a handler that refuses still has to end the stream rather than drop it.
				if (!requestStream.isTerminated()) {
					requestStream.abort(errorCodeOf(e), "The test server did not answer this request");
				}
			});
	}

	private static long errorCodeOf(Exception e) {
		return e instanceof Http3Exception h3 ? h3.errorCode() : Http3Errors.H3_INTERNAL_ERROR;
	}

	/** The two stream-count parameters HTTP/3 needs, mirroring {@code Http3Server}'s own mapping (FR-017). */
	private QuicConnectionSettings quicSettings() {
		return QuicConnectionSettings.builder()
			.withInitialMaxStreamsBidi(settings.maxConcurrentRequestStreams())
			.withInitialMaxStreamsUni(settings.maxUniStreams())
			.build();
	}

	@Override
	public void close() {
		// The endpoint closes its connections, each of which aborts the streams under it and closes the
		// socket — the same teardown Http3Server's own closeNow() performs.
		if (endpoint != null) endpoint.close();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private void checkNotStarted() {
		if (endpoint != null) throw new IllegalStateException("Http3TestServer is already started");
	}

	@Override
	public String toString() {
		return "Http3TestServer{" + (earlyDataEnabled ? "earlyDataEnabled" : "earlyDataRefused") +
			   ", connectionsAccepted=" + connectionsAccepted + '}';
	}
}
