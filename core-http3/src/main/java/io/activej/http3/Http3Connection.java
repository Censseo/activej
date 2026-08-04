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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.recycle.Recyclers;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.http3.frame.CancelPushFrame;
import io.activej.http3.frame.GoAwayFrame;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.frame.MaxPushIdFrame;
import io.activej.http3.frame.SettingsFrame;
import io.activej.http3.frame.UnknownFrame;
import io.activej.http3.qpack.QpackDecoder;
import io.activej.http3.qpack.QpackEncoder;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.quic.stream.StreamIds;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * The HTTP/3 layer of one QUIC connection (RFC 9114 §6): the local control stream and its SETTINGS,
 * the peer's control and QPACK streams, and the rules about which of those may exist, how many times,
 * and what may travel on them.
 *
 * <h2>Wiring</h2>
 * One {@code Http3Connection} owns one {@link QuicStreamManager}, and that manager <i>is</i> the
 * {@code QuicFrameHandler} the transport wants — so a server or client attaches through feature 03's
 * per-connection factory seam and hands back {@link #streamManager()}:
 * <pre>{@code
 * QuicEndpoint.builder(reactor, socket)
 *     .withFrameHandlerFactory(connection -> Http3Connection.builder(reactor, connection)
 *         .withSettings(settings)
 *         .withRequestStreamListener(this::serve)
 *         .build()
 *         .startAndGetStreamManager())
 *     .build();
 * }</pre>
 * {@link #start()} is separate from {@code build()} because it originates traffic — it opens the
 * local control stream and writes SETTINGS from a path no incoming frame triggered.
 *
 * <h2>State</h2>
 * <pre>{@code
 * NEW ──local control stream opened, SETTINGS written──► SETTINGS_SENT
 * SETTINGS_SENT ──peer SETTINGS received──► READY
 * READY ──GOAWAY sent or received──► GOING_AWAY
 * any ──close(), transport failure, H3 protocol error──► CLOSED
 * }</pre>
 * {@code transitionTo(next)} is the sole writer of the state field, mirroring {@code QuicConnection}'s
 * rule so no transition can escape its debug line.
 *
 * <h2>Going away</h2>
 * {@link #goAway()} announces the identifier from which on this endpoint will process nothing (RFC 9114
 * §5.2, FR-019) and stops taking new work without ending what is already under way — the connection
 * stays alive precisely so that everything <i>below</i> the identifier can finish. The identifier is
 * exclusive, as §5.2 words it: "requests with the indicated identifier or greater are rejected". On the
 * receiving side (FR-020) a GOAWAY fails every request stream <i>at or above</i> the identifier with a
 * <b>retryable</b> {@code H3_REQUEST_REJECTED}, leaves the rest alone, and a successor <i>higher</i> than one
 * already received is a connection error of type {@code H3_ID_ERROR} — a client has already begun
 * retrying elsewhere everything the first identifier excluded, so widening it afterwards cannot be
 * honoured.
 *
 * <h2>Server push</h2>
 * There is none, ever (FR-040). This endpoint sends no MAX_PUSH_ID and no PUSH_PROMISE, so
 * {@link #pushLimitGranted()} is 0 for the life of every connection and no push id is ever issued —
 * which is what makes every push construct a peer can send refusable with an exact code rather than
 * something to be ignored: a push stream or a PUSH_PROMISE is {@code H3_ID_ERROR}, a CANCEL_PUSH names
 * an id that was never promised and is {@code H3_ID_ERROR} too, and a MAX_PUSH_ID reaching a client
 * travelled the wrong way and is {@code H3_FRAME_UNEXPECTED}. A server records the MAX_PUSH_ID its
 * client sends — the limit may only rise — and does nothing else with it.
 *
 * <h2>Peer-opened streams</h2>
 * A peer-opened stream is delivered exactly once and is never queued (feature 04, ADR-019), so the
 * listener takes ownership synchronously. Unidirectional streams are classified by the RFC 9114 §6.2
 * type varint at their head; a bidirectional one becomes an {@link Http3RequestStream} over this
 * connection's QPACK codecs and settings, and is handed to the
 * {@linkplain Builder#withRequestStreamListener request-stream listener} — which is where an
 * {@link Http3Server} plugs in, and about which this class deliberately knows nothing else.
 *
 * <h2>Reporting an H3 connection error</h2>
 * RFC 9114 requires a connection error to be a QUIC <b>application</b> {@code CONNECTION_CLOSE}
 * (frame type {@code 0x1d}) carrying the RFC 9114 §8.1 code (FR-061), because an H3 code placed in a
 * transport {@code 0x1c} frame would be read against RFC 9000 §20.1's codes — and
 * {@code H3_STREAM_CREATION_ERROR} (0x0103) lands squarely in its {@code CRYPTO_ERROR} range. So a
 * connection error does two things:
 * <ol>
 *   <li>every stream it owns is aborted with the H3 code, as {@code RESET_STREAM}/{@code STOP_SENDING};</li>
 *   <li>{@link QuicConnection#closeWithApplicationError} sends the {@code 0x1d} close carrying that same
 *       code, and it stays readable locally through {@link #closedWithErrorCode()}.</li>
 * </ol>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-6">RFC 9114 §6 — Connections</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9114#section-6.2">RFC 9114 §6.2 — Unidirectional Streams</a>
 */
public final class Http3Connection extends AbstractReactive {
	private static final Logger logger = LoggerFactory.getLogger(Http3Connection.class);

	/** {@link #closedWithErrorCode()} when no H3 error has closed this connection. */
	public static final long NO_ERROR_CODE = -1;

	/** {@link #goAwaySentId()} / {@link #goAwayReceivedId()} when no GOAWAY has been exchanged. */
	public static final long NO_GOAWAY_ID = -1;

	/** {@link #maxPushIdReceived()} when the peer has granted no push id. */
	public static final long NO_PUSH_ID = -1;

	/** {@link #lastRequestStreamAccepted} when this endpoint has taken no request stream at all. */
	private static final long NO_REQUEST_STREAM = -1;

	/**
	 * The first client-initiated bidirectional stream id, and the distance between two consecutive ones.
	 * RFC 9000 §2.1 numbers each of the four stream types independently as {@code ordinal << 2}, so
	 * request streams are 0, 4, 8, … — which is what makes {@code lastRequestStreamAccepted + 4} the
	 * <i>next</i> one and not merely a larger number.
	 */
	private static final long FIRST_REQUEST_STREAM_ID = StreamIds.of(0, true, true);
	private static final long REQUEST_STREAM_ID_STEP = StreamIds.of(1, true, true) - FIRST_REQUEST_STREAM_ID;

	/**
	 * The push ids this endpoint has issued — promised in a PUSH_PROMISE as a server, granted in a
	 * MAX_PUSH_ID as a client. Together they are every push id either side of this connection may name.
	 * <p>
	 * <b>Always empty, and constant.</b> Server push is permanently out of scope (FR-040): this
	 * implementation sends no PUSH_PROMISE, so it promises nothing, and no MAX_PUSH_ID, so it grants
	 * nothing — {@link #pushLimitGranted()} is 0 for the life of every connection. Written as a set that
	 * is genuinely consulted rather than as an assumption, so that the rules resting on it — a push
	 * stream, a PUSH_PROMISE, a CANCEL_PUSH — read as the policy they enforce.
	 */
	private static final Set<Long> PUSH_IDS_PROMISED = Set.of();

	/**
	 * The one QPACK encoder-stream instruction that is meaningful with a local dynamic-table capacity
	 * of 0: RFC 9204 §4.3.1 Set Dynamic Table Capacity, {@code 001} then a five-bit prefix carrying 0.
	 * <p>
	 * A single byte is a complete decision, which is why this needs no cross-buffer accumulation: an
	 * RFC 7541 §5.1 prefix integer is 0 if and only if its prefix bits are 0 — any continuation run
	 * encodes a value of at least {@code 2^5 - 1}, and every capacity above 0 is refused anyway.
	 */
	private static final int SET_DYNAMIC_TABLE_CAPACITY_ZERO = 0x20;

	/** Which end of a connection announced a GOAWAY — what an {@code Inspector}'s {@code onGoAway} reports. */
	public enum GoAwayDirection {
		/** This endpoint announced it: {@link #goAway()}. */
		SENT,
		/** The peer announced it, on its control stream. */
		RECEIVED,
	}

	public enum State {
		/** Constructed; the local control stream has not been opened yet. */
		NEW,
		/** The local control stream carries its type varint and SETTINGS. */
		SETTINGS_SENT,
		/** Both endpoints have exchanged SETTINGS; requests may flow. */
		READY,
		/** A GOAWAY was sent or received: nothing new is taken, what is under way still finishes. */
		GOING_AWAY,
		/** Terminal. */
		CLOSED,
	}

	private final QuicConnection quicConnection;
	private final QuicStreamManager streamManager;
	private final Role role;
	private final Map<Long, Http3RequestStream> requestStreams = new HashMap<>();

	private Http3Settings settings = Http3Settings.create();
	private Consumer<Http3RequestStream> requestStreamListener = stream -> {};
	private Http3EventListener eventListener = Http3EventListener.NONE;
	private QpackEncoder qpackEncoder;
	private QpackDecoder qpackDecoder;

	private State state = State.NEW;
	private boolean started;
	/** {@link #start()}'s preamble, held so that {@link #goAway()} can queue behind it. */
	private Promise<Void> starting = Promise.complete();
	private boolean settingsSent;

	private @Nullable QuicStream localControlStream;
	private @Nullable QuicStream peerControlStream;
	private @Nullable QuicStream peerQpackEncoderStream;
	private @Nullable QuicStream peerQpackDecoderStream;
	private @Nullable SettingsFrame peerSettings;
	private @Nullable Http3Exception closeException;

	private long goAwaySentId = NO_GOAWAY_ID;
	private long goAwayReceivedId = NO_GOAWAY_ID;

	/**
	 * The largest push id the peer has granted this endpoint in a MAX_PUSH_ID frame, or
	 * {@link #NO_PUSH_ID}. Recorded and never read for anything else: a server that never pushes has no
	 * use for a raised limit (FR-040). It is kept because RFC 9114 §7.2.7 makes the limit non-decreasing,
	 * and enforcing that needs the previous value.
	 */
	private long maxPushIdReceived = NO_PUSH_ID;

	/**
	 * The highest client-initiated bidirectional stream this endpoint has taken, or
	 * {@link #NO_REQUEST_STREAM} while it has taken none — which is a state of its own rather than
	 * "stream 0", because RFC 9114 §5.2's identifier is exclusive and the two announce different things:
	 * having taken stream 0 announces 4, having taken nothing announces 0.
	 *
	 * @see #nextRequestStreamId()
	 */
	private long lastRequestStreamAccepted = NO_REQUEST_STREAM;

	private long peerUnidirectionalStreamsAccepted;
	private long requestStreamsRejected;
	private long unidirectionalStreamsAbandoned;
	private long controlFramesReceived;
	private long controlFramesDiscarded;
	private long connectionErrors;

	private Http3Connection(Reactor reactor, QuicConnection quicConnection) {
		super(reactor);
		this.quicConnection = quicConnection;
		this.role = quicConnection.role();
		// Built here rather than in the builder: the listener is this connection's own method, and a
		// peer stream must never arrive before there is something to route it to.
		this.streamManager = QuicStreamManager.builder(reactor, quicConnection)
			.withStreamListener(this::onPeerStream)
			.build();
	}

	public static Builder builder(Reactor reactor, QuicConnection quicConnection) {
		return new Http3Connection(reactor, quicConnection).new Builder();
	}

	/** Default settings, no request-stream listener, {@linkplain #start() started}. */
	public static Http3Connection create(Reactor reactor, QuicConnection quicConnection) {
		Http3Connection connection = builder(reactor, quicConnection).build();
		connection.start();
		return connection;
	}

	public final class Builder extends AbstractBuilder<Builder, Http3Connection> {
		private Builder() {}

		public Builder withSettings(Http3Settings settings) {
			checkNotBuilt(this);
			Http3Connection.this.settings = settings;
			return this;
		}

		/**
		 * Where a peer-opened <b>bidirectional</b> stream goes — a request stream on a server, and
		 * nothing legal at all on a client, which is why this class rejects a server-initiated one
		 * before the listener ever sees it (FR-044).
		 * <p>
		 * The listener is handed an {@link Http3RequestStream} already built over this connection's
		 * QPACK codecs and settings — those are per-connection state (RFC 9204 §4.2), so the connection
		 * is where a request stream can be assembled without a caller re-deriving what it needs. It takes
		 * ownership synchronously; the stream is never queued (ADR-019).
		 */
		public Builder withRequestStreamListener(Consumer<Http3RequestStream> listener) {
			checkNotBuilt(this);
			Http3Connection.this.requestStreamListener = listener;
			return this;
		}

		/**
		 * Where this connection — and every request stream it adopts — reports what an
		 * {@link Http3Server.Inspector} or an {@link Http3Client.Inspector} publishes (FR-062).
		 * <p>
		 * Package-private on purpose: this is how the server or client that <i>built</i> this connection is
		 * told what happened underneath it, not a second public observability contract (see
		 * {@link Http3EventListener}).
		 */
		Builder withEventListener(Http3EventListener eventListener) {
			checkNotBuilt(this);
			Http3Connection.this.eventListener = eventListener;
			return this;
		}

		@Override
		protected Http3Connection doBuild() {
			qpackEncoder = new QpackStaticEncoder();
			qpackDecoder = new QpackStaticDecoder(settings.maxFieldSectionSize());
			return Http3Connection.this;
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Opens the local control stream, writes its type varint and the SETTINGS frame of FR-012, and
	 * moves to {@link State#SETTINGS_SENT}. Idempotent.
	 * <p>
	 * The open stays pending until the QUIC handshake supplies the peer's stream limits, so calling
	 * this from a frame-handler factory — before the connection is established — is the expected use.
	 *
	 * @return a promise completing once every byte of the preamble has been handed to the transport
	 */
	public Promise<Void> start() {
		checkInReactorThread(this);
		if (started) return starting;
		started = true;
		starting = streamManager.openUnidirectional()
			.then(stream -> {
				localControlStream = stream;
				return stream.writer().accept(controlStreamPreamble());
			})
			.whenComplete(($, e) -> {
				if (e != null) {
					logger.debug("The local control stream could not be established: {}", e.toString());
					return;
				}
				settingsSent = true;
				updateState();
			});
		return starting;
	}

	/** {@link #start()} then {@link #streamManager()} — the one-liner a frame-handler factory wants. */
	public QuicStreamManager startAndGetStreamManager() {
		checkInReactorThread(this);
		start();
		return streamManager;
	}

	/**
	 * Opens a client-initiated bidirectional stream and wraps it as an {@link Http3RequestStream} over
	 * this connection's QPACK codecs and settings — the client's counterpart of the peer-opened stream
	 * this connection routes to its {@linkplain Builder#withRequestStreamListener listener} (FR-050).
	 * <p>
	 * The stream is registered here, so it is aborted with the rest when the connection closes or fails,
	 * and its message is released with it (FR-057a) rather than surviving the connection that produced it.
	 * <p>
	 * The promise <b>stays pending</b> while the peer's {@code initial_max_streams_bidi} credit is
	 * exhausted — {@link QuicStreamManager#openBidirectional()} withholds the open and announces
	 * {@code STREAMS_BLOCKED} rather than failing, which is what makes a stream limit backpressure instead
	 * of an application error. A caller that must bound its own waiting does so by counting the promises
	 * it is holding; {@link Http3Client} is the one that does.
	 *
	 * @throws IllegalStateException if this connection is a server's — RFC 9114 §6.1 gives a server no
	 *                               legal use for a bidirectional stream at all
	 */
	public Promise<Http3RequestStream> openRequestStream() {
		checkInReactorThread(this);
		if (role != Role.CLIENT) {
			throw new IllegalStateException("Only a client opens a request stream (RFC 9114 §6.1)");
		}
		if (state == State.CLOSED) {
			return Promise.ofException(closeException != null ?
				closeException :
				new Http3Exception(Http3Errors.H3_NO_ERROR, "The HTTP/3 connection is closed"));
		}
		if (state == State.GOING_AWAY) {
			// RFC 9114 §5.2: neither endpoint initiates a request once either of them has announced that it
			// will process nothing further. Retryable, because nothing was ever sent (FR-020).
			return Promise.ofException(new Http3Exception(Http3Errors.H3_REQUEST_REJECTED,
				"The HTTP/3 connection is going away (GOAWAY, RFC 9114 §5.2)", true));
		}
		return streamManager.openBidirectional().map(this::adoptRequestStream);
	}

	private Http3RequestStream adoptRequestStream(QuicStream stream) {
		long id = stream.id();
		Http3RequestStream requestStream = Http3RequestStream.builder(reactor, stream)
			.withSettings(settings)
			.withQpackEncoder(qpackEncoder)
			.withQpackDecoder(qpackDecoder)
			// The two violations a request stream can observe that the *connection* owns: a PUSH_PROMISE
			// against a push limit of 0 (FR-040), and a frame RFC 9114 §7.2's table does not permit on a
			// request stream at all (FR-024, FR-025). Everything it refuses about the message itself stays
			// on its own stream (FR-037).
			.withConnectionErrorListener(this::closeWithError)
			.withEventListener(eventListener)
			.build();
		requestStreams.put(id, requestStream);
		stream.whenClosed().whenComplete(($, e) -> requestStreams.remove(id));
		return requestStream;
	}

	/**
	 * Announces a graceful shutdown on the local control stream (RFC 9114 §5.2, FR-019): a GOAWAY
	 * carrying the identifier from which on this endpoint will process nothing, and a move to
	 * {@link State#GOING_AWAY}. The connection is <b>not</b> closed — that is the whole point, since
	 * everything below the identifier is still owed an answer.
	 * <p>
	 * What the identifier is depends on who sends it. RFC 9114 §5.2 makes it <b>exclusive</b> — "requests
	 * with the indicated identifier or greater are rejected" — so a <b>server</b> announces one past the
	 * last client-initiated bidirectional stream it has taken, which is the {@linkplain
	 * #nextRequestStreamId() next} such stream id, or the first one ({@code 0}) when it has taken none.
	 * Whatever arrives after this call is refused with {@code H3_REQUEST_REJECTED} on its own stream, so
	 * nothing at or above the announcement can be processed however the peer counts. A <b>client</b>
	 * announces a push id, and this implementation never grants one — it never sends {@code MAX_PUSH_ID},
	 * so its push limit stays 0 — which makes a client's identifier always {@code 0}: no push id at all
	 * is at or above it.
	 * <p>
	 * Repeatable, which is what RFC 9114 §5.2 expects of an endpoint that first says "nothing more" and
	 * then narrows it: a successor identifier is clamped to the one already announced, never allowed
	 * above it. A call on a closed connection does nothing.
	 *
	 * @return a promise completing once the frame has been handed to the transport
	 */
	public Promise<Void> goAway() {
		checkInReactorThread(this);
		if (state == State.CLOSED) return Promise.complete();
		long id = role == Role.SERVER ? nextRequestStreamId() : 0;
		// Never widened: a peer has already begun retrying elsewhere everything the first identifier
		// excluded, and taking it back is exactly what H3_ID_ERROR exists to refuse.
		if (goAwaySentId != NO_GOAWAY_ID) id = Math.min(id, goAwaySentId);
		goAwaySentId = id;
		transitionTo(State.GOING_AWAY);
		logger.debug("{} HTTP/3 connection going away, announcing {}", role, id);
		eventListener.onGoAway(GoAwayDirection.SENT, id);
		// The control stream may still be opening — start() originates its own traffic, so its promise is
		// the only thing that knows when there is a stream to write on. Its failure is not this call's to
		// report: a connection with no control stream has no way to announce anything, and closing is what
		// happens to it next anyway.
		if (localControlStream != null) return writeGoAway(id);
		long announced = id;
		return starting.then($ -> writeGoAway(announced), e -> Promise.complete());
	}

	/**
	 * The identifier a server's GOAWAY announces: the client-initiated bidirectional stream id one past
	 * the last one this endpoint took, since RFC 9114 §5.2's identifier is exclusive — a peer rejects
	 * everything at or above it, and everything actually taken has to stay below it.
	 * <p>
	 * With nothing taken that is {@link #FIRST_REQUEST_STREAM_ID}, which announces that <i>no</i> request
	 * on this connection was processed — the boundary case worth stating, because the same number read
	 * inclusively would have claimed the opposite. No new stream is taken once {@link #goAway()} has set
	 * {@code goAwaySentId}, so what this returns cannot move afterwards and a repeated announcement is
	 * the same one.
	 */
	private long nextRequestStreamId() {
		return lastRequestStreamAccepted == NO_REQUEST_STREAM ?
			FIRST_REQUEST_STREAM_ID :
			lastRequestStreamAccepted + REQUEST_STREAM_ID_STEP;
	}

	private Promise<Void> writeGoAway(long id) {
		QuicStream control = localControlStream;
		if (control == null || state == State.CLOSED) return Promise.complete();
		GoAwayFrame frame = new GoAwayFrame(id);
		ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
		Http3Frames.write(buf, frame);
		// writer() owns the buffer on every path, this one included.
		return control.writer().accept(buf)
			.whenException(e -> logger.debug("The GOAWAY could not be written: {}", e.toString()));
	}

	/**
	 * Closes the connection with an RFC 9114 §8.1 application error code. Idempotent — a second call,
	 * or one after {@link #close()}, does nothing.
	 * <p>
	 * The code reaches the peer twice over: on every stream this connection owns, and in the QUIC
	 * application CONNECTION_CLOSE that {@link QuicConnection#closeWithApplicationError} emits.
	 */
	public void closeWithError(long errorCode, String reason) {
		checkInReactorThread(this);
		closeWithError(new Http3Exception(errorCode, reason));
	}

	/** Closes gracefully: every stream is released and the QUIC connection closes with NO_ERROR. Idempotent. */
	public void close() {
		checkInReactorThread(this);
		if (state == State.CLOSED) return;
		transitionTo(State.CLOSED);
		abortOwnedStreams(Http3Errors.H3_NO_ERROR);
		quicConnection.close();
	}

	// ---------------------------------------------------------------- accessors

	/** The {@code QuicFrameHandler} this connection is the HTTP/3 layer of. */
	public QuicStreamManager streamManager() {
		checkInReactorThread(this);
		return streamManager;
	}

	public State state() {
		checkInReactorThread(this);
		return state;
	}

	public Role role() {
		checkInReactorThread(this);
		return role;
	}

	public Http3Settings settings() {
		checkInReactorThread(this);
		return settings;
	}

	/** The peer's SETTINGS, or {@code null} until its control stream has delivered them. */
	public @Nullable SettingsFrame peerSettings() {
		checkInReactorThread(this);
		return peerSettings;
	}

	/**
	 * The peer's {@code SETTINGS_MAX_FIELD_SECTION_SIZE}, or {@link Long#MAX_VALUE} when it advertised
	 * none — RFC 9114 §7.2.4.1 makes the parameter unlimited by default.
	 */
	public long peerMaxFieldSectionSize() {
		checkInReactorThread(this);
		if (peerSettings == null) return Long.MAX_VALUE;
		for (int i = 0; i < peerSettings.identifiers.length; i++) {
			if (peerSettings.identifiers[i] == SettingsFrame.MAX_FIELD_SECTION_SIZE) {
				return peerSettings.values[i];
			}
		}
		return Long.MAX_VALUE;
	}

	public QpackEncoder qpackEncoder() {
		checkInReactorThread(this);
		return qpackEncoder;
	}

	public QpackDecoder qpackDecoder() {
		checkInReactorThread(this);
		return qpackDecoder;
	}

	/** The identifier this endpoint announced in a GOAWAY, or {@code -1} if it has announced none. */
	public long goAwaySentId() {
		checkInReactorThread(this);
		return goAwaySentId;
	}

	/** The identifier the peer announced in a GOAWAY, or {@code -1} if it has announced none. */
	public long goAwayReceivedId() {
		checkInReactorThread(this);
		return goAwayReceivedId;
	}

	/**
	 * The push limit this endpoint has granted its peer — how many push ids the peer may use.
	 * <p>
	 * <b>Always 0.</b> Server push is permanently out of scope (FR-040), so this implementation never
	 * sends MAX_PUSH_ID, on either role: a client grants no push id and a server promises none. Every
	 * push construct a peer can reach for is therefore refused, and this is the value it is refused
	 * against — stated rather than assumed, so a caller can see the policy instead of inferring it from
	 * the refusals.
	 */
	public long pushLimitGranted() {
		checkInReactorThread(this);
		return PUSH_IDS_PROMISED.size();
	}

	/**
	 * The largest push id the peer granted in a MAX_PUSH_ID frame, or {@link #NO_PUSH_ID} if it granted
	 * none. Recorded on a server so that RFC 9114 §7.2.7's non-decreasing rule can be enforced, and acted
	 * upon by nothing else — this server never pushes.
	 */
	public long maxPushIdReceived() {
		checkInReactorThread(this);
		return maxPushIdReceived;
	}

	/** The H3 error this connection was closed with, or {@code null}. */
	public @Nullable Http3Exception closeException() {
		checkInReactorThread(this);
		return closeException;
	}

	/** The RFC 9114 §8.1 code this connection was closed with, or {@link #NO_ERROR_CODE}. */
	public long closedWithErrorCode() {
		checkInReactorThread(this);
		return closeException == null ? NO_ERROR_CODE : closeException.errorCode();
	}

	/** Peer-opened bidirectional streams currently held, each one exchange in progress. */
	public int requestStreamCount() {
		checkInReactorThread(this);
		return requestStreams.size();
	}

	public long peerUnidirectionalStreamsAccepted() {
		checkInReactorThread(this);
		return peerUnidirectionalStreamsAccepted;
	}

	/** Request streams refused with {@code H3_REQUEST_REJECTED} because this endpoint had gone away (FR-019). */
	public long requestStreamsRejected() {
		checkInReactorThread(this);
		return requestStreamsRejected;
	}

	/** Peer unidirectional streams abandoned unread because their type is not one we serve (FR-015). */
	public long unidirectionalStreamsAbandoned() {
		checkInReactorThread(this);
		return unidirectionalStreamsAbandoned;
	}

	public long controlFramesReceived() {
		checkInReactorThread(this);
		return controlFramesReceived;
	}

	/** Control-stream frames of an unknown type, ignored per RFC 9114 §9's GREASE rule. */
	public long controlFramesDiscarded() {
		checkInReactorThread(this);
		return controlFramesDiscarded;
	}

	public long connectionErrors() {
		checkInReactorThread(this);
		return connectionErrors;
	}

	// ---------------------------------------------------------------- state

	/** The sole writer of {@link #state}, so no transition can escape its debug line. */
	private void transitionTo(State next) {
		if (state == next) return;
		State previous = state;
		state = next;
		logger.debug("{} HTTP/3 connection state {} -> {}", role, previous, next);
	}

	/** Recomputes the handshake half of the state machine; the terminal states are set directly. */
	private void updateState() {
		if (state == State.CLOSED || state == State.GOING_AWAY) return;
		if (settingsSent && peerSettings != null) {
			transitionTo(State.READY);
		} else if (settingsSent) {
			transitionTo(State.SETTINGS_SENT);
		}
	}

	// ---------------------------------------------------------------- the local control stream

	private ByteBuf controlStreamPreamble() {
		SettingsFrame frame = localSettingsFrame();
		ByteBuf buf = ByteBufPool.allocate(
			QuicVarInts.encodedLength(Http3StreamType.CONTROL.code()) + Http3Frames.encodedLength(frame));
		QuicVarInts.write(buf, Http3StreamType.CONTROL.code());
		Http3Frames.write(buf, frame);
		return buf;
	}

	/** FR-012: the three settings this implementation advertises, and no others. */
	private SettingsFrame localSettingsFrame() {
		return new SettingsFrame(
			new long[]{
				SettingsFrame.QPACK_MAX_TABLE_CAPACITY,
				SettingsFrame.MAX_FIELD_SECTION_SIZE,
				SettingsFrame.QPACK_BLOCKED_STREAMS},
			new long[]{
				settings.qpackMaxTableCapacity(),
				settings.maxFieldSectionSize(),
				settings.qpackBlockedStreams()});
	}

	// ---------------------------------------------------------------- peer-opened streams

	private void onPeerStream(QuicStream stream) {
		// A listener that throws closes the QUIC connection with INTERNAL_ERROR, which would hide
		// whatever really happened behind a transport code (feature 04's documented rule).
		try {
			if (state == State.CLOSED) {
				discard(stream, closedWithErrorCode() == NO_ERROR_CODE ? Http3Errors.H3_NO_ERROR : closedWithErrorCode());
				return;
			}
			if (stream.isBidirectional()) {
				onPeerBidirectionalStream(stream);
			} else {
				peerUnidirectionalStreamsAccepted++;
				readStreamType(stream);
			}
		} catch (RuntimeException e) {
			logger.error("Failed to accept a peer-opened stream", e);
			closeWithError(new Http3Exception(Http3Errors.H3_INTERNAL_ERROR, "Failed to accept a peer-opened stream"));
		}
	}

	private void onPeerBidirectionalStream(QuicStream stream) {
		if (!StreamIds.isClientInitiated(stream.id())) {
			// FR-044, RFC 9114 §6.1: a request stream is client-initiated, and nothing else uses a
			// bidirectional stream, so a server-initiated one can never be legal.
			closeWithError(new Http3Exception(Http3Errors.H3_STREAM_CREATION_ERROR,
				"A server-initiated bidirectional stream is never valid in HTTP/3"));
			return;
		}
		if (goAwaySentId != NO_GOAWAY_ID) {
			// FR-019, RFC 9114 §5.2: this endpoint announced that it would process nothing further, so this
			// stream was never processed and its request is safe to retry elsewhere. The stream is rejected,
			// not the connection — everything below the announced identifier is still draining on it.
			requestStreamsRejected++;
			discard(stream, Http3Errors.H3_REQUEST_REJECTED);
			return;
		}
		lastRequestStreamAccepted = Math.max(lastRequestStreamAccepted, stream.id());
		requestStreamListener.accept(adoptRequestStream(stream));
	}

	/**
	 * Reads the RFC 9114 §6.2 type varint off a peer unidirectional stream, then hands the stream and
	 * whatever of the buffer follows the varint to {@link #dispatchUnidirectional}.
	 */
	private void readStreamType(QuicStream stream) {
		ChannelSupplier<ByteBuf> reader = stream.reader();
		VarIntAccumulator accumulator = new VarIntAccumulator();
		Promises.repeat(() -> reader.get()
				.map(buf -> {
					if (buf == null) return false;
					if (!accumulator.feed(buf)) {
						buf.recycle();
						return true;
					}
					dispatchUnidirectional(stream, accumulator.value(), buf);
					return false;
				}))
			.whenException(e ->
				logger.debug("A peer unidirectional stream ended before declaring its type: {}", e.toString()));
	}

	/** Takes ownership of {@code rest} — whatever followed the type varint in the same buffer. */
	private void dispatchUnidirectional(QuicStream stream, long typeCode, ByteBuf rest) {
		switch (Http3StreamType.classify(typeCode)) {
			case CONTROL -> {
				if (peerControlStream != null) {
					rest.recycle();
					closeWithError(new Http3Exception(Http3Errors.H3_STREAM_CREATION_ERROR,
						"A second peer control stream (RFC 9114 §6.2.1)"));
					return;
				}
				peerControlStream = stream;
				readControlStream(stream, rest);
			}
			case QPACK_ENCODER -> {
				if (peerQpackEncoderStream != null) {
					rest.recycle();
					closeWithError(new Http3Exception(Http3Errors.H3_STREAM_CREATION_ERROR,
						"A second peer QPACK encoder stream (RFC 9204 §4.2)"));
					return;
				}
				peerQpackEncoderStream = stream;
				readQpackStream(stream, rest, true);
			}
			case QPACK_DECODER -> {
				if (peerQpackDecoderStream != null) {
					rest.recycle();
					closeWithError(new Http3Exception(Http3Errors.H3_STREAM_CREATION_ERROR,
						"A second peer QPACK decoder stream (RFC 9204 §4.2)"));
					return;
				}
				peerQpackDecoderStream = stream;
				readQpackStream(stream, rest, false);
			}
			case PUSH -> {
				rest.recycle();
				onPushStream();
			}
			case UNKNOWN -> {
				rest.recycle();
				unidirectionalStreamsAbandoned++;
				discard(stream, Http3Errors.H3_STREAM_CREATION_ERROR);
			}
		}
	}

	// ---------------------------------------------------------------- the peer's control stream

	private void readControlStream(QuicStream stream, ByteBuf rest) {
		// One bound for every type, unlike a request stream's two (T116): SETTINGS, GOAWAY and MAX_PUSH_ID
		// are all a varint or a list of pairs of them, and maxControlFrameSize is the bound on all of them.
		Http3FrameReader frameReader =
			new Http3FrameReader(settings.maxControlFrameSize(), Http3Errors.H3_EXCESSIVE_LOAD);
		ChannelSupplier<ByteBuf> reader = stream.reader();
		try {
			if (!feedControlStream(frameReader, rest)) {
				frameReader.recycle();
				return;
			}
		} catch (Http3Exception e) {
			frameReader.recycle();
			closeWithError(e);
			return;
		}
		Promises.repeat(() -> reader.get()
				.map(buf -> buf != null && feedControlStream(frameReader, buf)))
			.whenComplete(($, e) -> {
				// A control stream cut part-way through a frame leaves its reader holding the payload it had
				// begun filling; nothing will ever finish that frame, so this is the path that owes it a
				// release (DI-1).
				frameReader.recycle();
				onCriticalStreamEnded("control", e);
			});
	}

	/** Takes ownership of {@code buf}. Returns false once there is no reason to read further. */
	private boolean feedControlStream(Http3FrameReader frameReader, ByteBuf buf) throws Http3Exception {
		try {
			Http3Frame frame;
			while ((frame = frameReader.feed(buf)) != null) {
				onControlFrame(frame);
				if (state == State.CLOSED) return false;
			}
			return true;
		} finally {
			buf.recycle();
		}
	}

	private void onControlFrame(Http3Frame frame) throws Http3Exception {
		controlFramesReceived++;
		if (peerSettings == null) {
			// RFC 9114 §6.2.1: the first frame on the control stream is SETTINGS, whatever else it
			// might have been — an unknown type does not get GREASE tolerance in this one position.
			if (!(frame instanceof SettingsFrame settingsFrame)) {
				Recyclers.recycle(frame);
				throw new Http3Exception(Http3Errors.H3_MISSING_SETTINGS,
					"The first control-stream frame was type 0x" + Long.toHexString(frame.type()) + ", not SETTINGS");
			}
			validatePeerSettings(settingsFrame);
			peerSettings = settingsFrame;
			updateState();
			return;
		}
		if (frame instanceof SettingsFrame) {
			throw new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
				"A second SETTINGS frame on the control stream (RFC 9114 §7.2.4)");
		}
		if (frame instanceof GoAwayFrame goAway) {
			onGoAway(goAway.id);
			return;
		}
		if (frame instanceof MaxPushIdFrame maxPushId) {
			onMaxPushId(maxPushId.pushId);
			return;
		}
		if (frame instanceof CancelPushFrame cancelPush) {
			onCancelPush(cancelPush.pushId);
			return;
		}
		if (frame instanceof UnknownFrame unknown) {
			controlFramesDiscarded++;
			eventListener.onFrameDiscarded(unknown.type(), unknown.declaredLength);
			return;
		}
		Recyclers.recycle(frame);
		throw new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
			"Frame type 0x" + Long.toHexString(frame.type()) + " is not permitted on a control stream");
	}

	/**
	 * FR-020, RFC 9114 §5.2: the peer will process nothing beyond {@code id}.
	 * <p>
	 * Successive identifiers may narrow and may repeat, but may never widen — by the time the second one
	 * arrives, everything the first excluded is already being retried elsewhere, so a higher successor
	 * would be claiming back requests that have been sent twice. That is a connection error of type
	 * {@code H3_ID_ERROR}, and it leaves the identifier already announced standing.
	 */
	private void onGoAway(long id) throws Http3Exception {
		if (goAwayReceivedId != NO_GOAWAY_ID && id > goAwayReceivedId) {
			throw new Http3Exception(Http3Errors.H3_ID_ERROR,
				"A GOAWAY identifier of " + id + " above the previously announced " + goAwayReceivedId +
				" (RFC 9114 §5.2)");
		}
		goAwayReceivedId = id;
		transitionTo(State.GOING_AWAY);
		eventListener.onGoAway(GoAwayDirection.RECEIVED, id);
		rejectRequestStreamsAtOrAbove(id);
	}

	/**
	 * Fails every request stream at or above the announced identifier with a <b>retryable</b>
	 * {@code H3_REQUEST_REJECTED} (FR-020): RFC 9114 §5.2's identifier is exclusive — "requests with the
	 * indicated identifier or greater are rejected" — so the peer stated it never processed them, which is
	 * exactly the condition under which re-issuing a request elsewhere is safe. Streams strictly below it
	 * are left alone; they are still owed their answers.
	 * <p>
	 * Only a <b>server</b>'s GOAWAY names request streams. A client's carries a push id (RFC 9114 §5.2),
	 * a different space entirely, and reading one as a stream id would abort exchanges the client never
	 * spoke about.
	 */
	private void rejectRequestStreamsAtOrAbove(long id) {
		if (role != Role.CLIENT || requestStreams.isEmpty()) return;
		// A copy: aborting fails pending reads and writes, whose continuations routinely close streams and
		// so re-enter this map.
		for (Http3RequestStream requestStream : new ArrayList<>(requestStreams.values())) {
			long streamId = requestStream.id();
			if (streamId < id) continue;
			requestStreams.remove(streamId);
			requestStream.abort(Http3Errors.H3_REQUEST_REJECTED,
				"The peer is going away and will not process stream " + streamId + " (RFC 9114 §5.2)");
		}
	}

	/**
	 * FR-016. {@code SettingsFrame.read} already rejects both conditions at decode time; repeating the
	 * check here is what makes the <i>connection</i> own the rule rather than inherit it from a decoder
	 * detail, and it costs one pass over at most a handful of identifiers.
	 */
	private static void validatePeerSettings(SettingsFrame frame) throws Http3Exception {
		Set<Long> seen = new HashSet<>();
		for (long identifier : frame.identifiers) {
			if (SettingsFrame.RESERVED_IDENTIFIERS.contains(identifier)) {
				throw new Http3Exception(Http3Errors.H3_SETTINGS_ERROR,
					"Reserved SETTINGS identifier 0x" + Long.toHexString(identifier) + " (RFC 9114 §7.2.4.1)");
			}
			if (!seen.add(identifier)) {
				throw new Http3Exception(Http3Errors.H3_SETTINGS_ERROR,
					"Duplicated SETTINGS identifier 0x" + Long.toHexString(identifier));
			}
		}
	}

	// ---------------------------------------------------------------- server push, refused (FR-040)

	/**
	 * RFC 9114 §6.2.2/§4.6: a push stream, which this endpoint has no way to want.
	 * <p>
	 * A <b>client</b> may be pushed to only within the limit it granted with a MAX_PUSH_ID it sent, and
	 * this implementation sends none — {@link #pushLimitGranted()} is 0 — so every push stream a server
	 * opens names a push id that was never issued: {@code H3_ID_ERROR}. A <b>server</b> refuses one for a
	 * different reason entirely, and so with a different code: only a server pushes at all, so a
	 * client-initiated push stream is a stream that may not be created, {@code H3_STREAM_CREATION_ERROR}.
	 * <p>
	 * Either way this is a <i>connection</i> error, not the abandon-and-continue an unknown stream type
	 * gets (FR-015): a peer that opens a push stream has misread the push limit, and everything else it
	 * concluded from that reading is suspect too.
	 */
	private void onPushStream() {
		if (role != Role.CLIENT) {
			closeWithError(new Http3Exception(Http3Errors.H3_STREAM_CREATION_ERROR,
				"A client-initiated push stream, which only a server may open (RFC 9114 §6.2.2)"));
			return;
		}
		closeWithError(new Http3Exception(Http3Errors.H3_ID_ERROR,
			"A push stream against a push limit of " + PUSH_IDS_PROMISED.size() +
			" — no MAX_PUSH_ID was ever sent (RFC 9114 §4.6)"));
	}

	/**
	 * RFC 9114 §7.2.7: the peer raises the number of pushes it will accept.
	 * <p>
	 * MAX_PUSH_ID is client-to-server only, so a <b>client</b> receiving one is looking at a frame where
	 * RFC 9114 §7.2.7 does not permit it: {@code H3_FRAME_UNEXPECTED} (FR-024). A <b>server</b> records
	 * the value and does nothing whatever with it — this server never pushes (FR-040), so a limit of 7 and
	 * a limit of 7000 buy a client exactly the same nothing. It is recorded because the limit may only
	 * ever <i>rise</i>: a successor below one already received is a connection error of type
	 * {@code H3_ID_ERROR}, and the identifier already announced stands.
	 */
	private void onMaxPushId(long pushId) throws Http3Exception {
		if (role == Role.CLIENT) {
			throw new Http3Exception(Http3Errors.H3_FRAME_UNEXPECTED,
				"A MAX_PUSH_ID frame from a server, which sends none (RFC 9114 §7.2.7)");
		}
		if (maxPushIdReceived != NO_PUSH_ID && pushId < maxPushIdReceived) {
			throw new Http3Exception(Http3Errors.H3_ID_ERROR,
				"A MAX_PUSH_ID of " + pushId + " below the previously granted " + maxPushIdReceived +
				" (RFC 9114 §7.2.7)");
		}
		maxPushIdReceived = pushId;
	}

	/**
	 * RFC 9114 §7.2.3: the peer withdraws, or declines, a promised push.
	 * <p>
	 * {@link #PUSH_IDS_PROMISED} is empty and always will be, so <b>every</b> CANCEL_PUSH this endpoint
	 * receives names a push id neither side ever spoke of — which RFC 9114 §7.2.3 makes a connection error
	 * of type {@code H3_ID_ERROR}, on either role.
	 */
	private void onCancelPush(long pushId) throws Http3Exception {
		if (!PUSH_IDS_PROMISED.contains(pushId)) {
			throw new Http3Exception(Http3Errors.H3_ID_ERROR,
				"A CANCEL_PUSH naming push id " + pushId + ", which was never promised (RFC 9114 §7.2.3)");
		}
	}

	// ---------------------------------------------------------------- the peer's QPACK streams

	private void readQpackStream(QuicStream stream, ByteBuf rest, boolean encoder) {
		String what = encoder ? "QPACK encoder" : "QPACK decoder";
		ChannelSupplier<ByteBuf> reader = stream.reader();
		try {
			if (!feedQpackStream(rest, encoder)) return;
		} catch (Http3Exception e) {
			closeWithError(e);
			return;
		}
		Promises.repeat(() -> reader.get()
				.map(buf -> buf != null && feedQpackStream(buf, encoder)))
			.whenComplete(($, e) -> onCriticalStreamEnded(what, e));
	}

	/**
	 * Takes ownership of {@code buf}. With a dynamic-table capacity of 0 the peer's encoder stream may
	 * carry only {@code Set Dynamic Table Capacity 0}, and its decoder stream nothing at all: nothing
	 * was ever inserted, so there is no section to acknowledge and no insert count to increment
	 * (FR-018, RFC 9204 §4.2).
	 */
	private boolean feedQpackStream(ByteBuf buf, boolean encoder) throws Http3Exception {
		try {
			while (buf.canRead()) {
				int instruction = buf.readByte() & 0xFF;
				if (!encoder) {
					throw new Http3Exception(Http3Errors.QPACK_DECODER_STREAM_ERROR,
						"A QPACK decoder instruction (0x" + Integer.toHexString(instruction) +
						") against a table nothing was ever inserted into");
				}
				if (instruction != SET_DYNAMIC_TABLE_CAPACITY_ZERO) {
					throw new Http3Exception(Http3Errors.QPACK_ENCODER_STREAM_ERROR,
						"A QPACK encoder instruction (0x" + Integer.toHexString(instruction) +
						") other than Set Dynamic Table Capacity 0");
				}
			}
			return state != State.CLOSED;
		} finally {
			buf.recycle();
		}
	}

	// ---------------------------------------------------------------- failure paths

	/**
	 * A critical stream — control or either QPACK stream — must live as long as the connection
	 * (RFC 9114 §6.2, FR-014). Its clean end, its reset, and a protocol violation on it are all
	 * connection errors; only the connection dying first is not.
	 */
	private void onCriticalStreamEnded(String what, @Nullable Exception e) {
		if (state == State.CLOSED) return;
		if (quicConnection.state().isTerminating()) {
			// The transport went first; there is no H3 error to report and nothing left to report it on.
			transitionTo(State.CLOSED);
			return;
		}
		if (e instanceof Http3Exception h3) {
			closeWithError(h3);
			return;
		}
		closeWithError(new Http3Exception(Http3Errors.H3_CLOSED_CRITICAL_STREAM,
			"The peer's " + what + " stream ended (RFC 9114 §6.2)"));
	}

	private void closeWithError(Http3Exception e) {
		if (state == State.CLOSED) return;
		closeException = e;
		connectionErrors++;
		transitionTo(State.CLOSED);
		logger.debug("Closing the HTTP/3 connection with 0x{}: {}", Long.toHexString(e.errorCode()), e.reason());
		// Before the streams this error aborts, so the cause is reported ahead of its consequences.
		eventListener.onConnectionError(e.errorCode());
		abortOwnedStreams(e.errorCode());
		// FR-061: an application close (0x1d) carrying the H3 code itself — never a transport one, whose
		// code space would rename it.
		quicConnection.closeWithApplicationError(e.errorCode(), e.reason());
	}

	private void abortOwnedStreams(long errorCode) {
		discard(localControlStream, errorCode);
		discard(peerControlStream, errorCode);
		discard(peerQpackEncoderStream, errorCode);
		discard(peerQpackDecoderStream, errorCode);
		// A copy: aborting fails pending writes, whose continuations routinely close streams.
		// Through the request stream rather than the QUIC stream, so the message it owns is released with
		// it (FR-057a) instead of surviving the connection that produced it.
		for (Http3RequestStream requestStream : new ArrayList<>(requestStreams.values())) {
			requestStream.abort(errorCode, "The HTTP/3 connection is closing");
		}
		requestStreams.clear();
	}

	/** Aborts whichever halves {@code stream} owns; both verbs are idempotent, so a second call is free. */
	private static void discard(@Nullable QuicStream stream, long errorCode) {
		if (stream == null) return;
		if (stream.hasSendPart()) stream.reset(errorCode);
		if (stream.hasReceivePart()) stream.stopSending(errorCode);
	}

	@Override
	public String toString() {
		return "Http3Connection{" + role + ", " + state +
			(goAwaySentId == NO_GOAWAY_ID ? "" : ", goAwaySent=" + goAwaySentId) +
			(goAwayReceivedId == NO_GOAWAY_ID ? "" : ", goAwayReceived=" + goAwayReceivedId) +
			(closeException == null ? "" : ", closedWith=0x" + Long.toHexString(closeException.errorCode())) +
			", requestStreams=" + requestStreams.size() + '}';
	}

	/**
	 * Accumulates a QUIC varint (RFC 9000 §16) across arbitrarily-fragmented reads, without pool
	 * allocation — its encoded form is at most 8 bytes.
	 * <p>
	 * A deliberate sibling of {@code Http3FrameReader}'s own accumulator rather than a shared one:
	 * exposing it would widen the synchronous frame package's API for six lines, and the stream-type
	 * varint is read once per stream, not once per frame.
	 */
	private static final class VarIntAccumulator {
		private final byte[] bytes = new byte[8];
		private int length;

		/** @return true once a complete varint has been accumulated; {@code in} keeps whatever follows */
		boolean feed(ByteBuf in) {
			if (length == 0) {
				if (!in.canRead()) return false;
				bytes[length++] = in.readByte();
			}
			int needed = 1 << ((bytes[0] & 0xFF) >>> 6);
			while (length < needed && in.canRead()) {
				bytes[length++] = in.readByte();
			}
			return length == needed;
		}

		long value() {
			long value = bytes[0] & 0x3F;
			for (int i = 1; i < length; i++) {
				value = (value << 8) | (bytes[i] & 0xFF);
			}
			return value;
		}
	}
}
