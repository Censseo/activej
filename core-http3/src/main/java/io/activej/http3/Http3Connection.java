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
import io.activej.http3.qpack.QpackBlockedSections;
import io.activej.http3.qpack.QpackBlockedSections.HeldSection;
import io.activej.http3.qpack.QpackDecoder;
import io.activej.http3.qpack.QpackDecoderStreamReader;
import io.activej.http3.qpack.QpackDynamicDecoder;
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackDynamicEncoder;
import io.activej.http3.qpack.QpackEncoder;
import io.activej.http3.qpack.QpackEncoderStreamReader;
import io.activej.http3.qpack.QpackException;
import io.activej.http3.qpack.QpackField;
import io.activej.http3.qpack.QpackInstructions;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.quic.stream.StreamIds;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.common.Utils.nullify;
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

	/**
	 * Which of a connection's two QPACK dynamic tables an {@code Inspector}'s insertion and eviction
	 * counts belong to. RFC 9204 §3.2 gives every connection one per direction, and they are never the
	 * same object — a report that did not say which would be two numbers added together.
	 */
	public enum QpackTable {
		/** The table this endpoint inserts into and encodes its own field sections from. */
		ENCODER,
		/** The table the peer's encoder stream inserts into and this endpoint decodes with. */
		DECODER,
	}

	/**
	 * Why a stream stopped being blocked on a QPACK field section — what an {@code Inspector}'s
	 * {@code onQpackStreamUnblocked} reports. A section leaves the hold in exactly these four ways, and
	 * only the first of them is the one head-of-line blocking is supposed to end in: the other three are
	 * a peer that never sent what it referenced, seen from three different sides.
	 */
	public enum QpackBlockedExit {
		/** The peer's insertions arrived: the section left the hold to be decoded (FR-033). */
		DECODED,
		/** Its request stream was reset or abandoned while it was still held (FR-025). */
		RESET,
		/** It stayed blocked past {@link Http3Settings#qpackBlockedStreamTimeoutMillis()} (FR-036). */
		TIMED_OUT,
		/** The connection is closing, and everything it was holding goes with it (FR-035). */
		CLOSED,
	}

	/**
	 * What a server decided about the early data a resumption attempt carried — what an
	 * {@code Inspector}'s {@code onZeroRttDecision} reports. Rejection is signalled by the server
	 * omitting {@code early_data} from EncryptedExtensions and is never a handshake failure
	 * (RFC 8446 §4.2.10), so the two are a decision rather than a success and a failure.
	 */
	public enum ZeroRttOutcome {
		/** The server echoed {@code early_data}: the 0-RTT packets already sent were taken. */
		ACCEPTED,
		/** The server did not echo it: the session resumed, the early data did not. */
		REJECTED,
	}

	/**
	 * Why a server refused early data — what an {@code Inspector}'s {@code onEarlyDataRefused} reports,
	 * and the operational half of {@link ZeroRttOutcome#REJECTED}, which says only <i>that</i> it
	 * happened. The four are not variations of one condition: three are the single-use replay register
	 * (spec FR-069, FR-070) answering a resumption attempt before a single HTTP byte exists, and the
	 * fourth is the {@linkplain Http3EarlyDataPolicy early-data policy} (spec FR-064) answering a
	 * request that has already decoded. A deployment reacts to them differently, so nothing here folds
	 * two of them together.
	 */
	public enum EarlyDataRefusal {
		/**
		 * The register had already granted this ticket identity a use (RFC 8446 §8) — a replayed flight,
		 * or a client re-offering a ticket the single-use rule said it must discard. The security signal.
		 */
		REPLAYED,
		/**
		 * The register had no room: the presentation's probe window held only live records, and a live
		 * record is never dropped (spec FR-070). Not a security event but an availability one — 0-RTT is
		 * degrading towards refusing every <i>new</i> grant, and {@code maxEarlyDataReplayRecords} is the
		 * knob.
		 */
		AT_CAPACITY,
		/**
		 * The register refused a ticket past its own lifetime. Defence in depth: a server built by
		 * {@link Http3Server} never reaches it, because the TLS engine skips an expired ticket while
		 * selecting the pre-shared key, well before the register is consulted.
		 */
		EXPIRED,
		/**
		 * The early-data policy declined a request that arrived at {@code ZERO_RTT} — answered
		 * {@code 425 (Too Early)} (RFC 8470) without the servlet being invoked. Under the default policy
		 * this is an unsafe method in early data, which is ordinary traffic rather than an attack.
		 */
		POLICY,
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
	private Http3EarlyDataPolicy earlyDataPolicy = Http3EarlyDataPolicy.DEFAULT_POLICY;
	private Consumer<Http3RequestStream> requestStreamListener = stream -> {};
	private Http3EventListener eventListener = Http3EventListener.NONE;
	private QpackEncoder qpackEncoder;
	private QpackDecoder qpackDecoder;

	/**
	 * The dynamic half of QPACK, when a capacity was negotiated for it. The two directions appear at
	 * different moments, and that asymmetry is the whole shape of this wiring: the <b>decoder</b>
	 * depends on nothing the peer says — this endpoint's own advertised maximum bounds what the peer's
	 * encoder may do — so it exists from {@code build()}; the <b>encoder</b> may not request more than
	 * the peer's {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} (RFC 9204 §3.2.3), which arrives later, so
	 * it exists only once the peer's SETTINGS have landed.
	 * <p>
	 * {@link #qpackEncoder} / {@link #qpackDecoder} alias whichever implementation is live, and stay
	 * what {@link #qpackEncoder()} / {@link #qpackDecoder()} return.
	 */
	private @Nullable QpackDynamicDecoder qpackDynamicDecoder;
	private @Nullable QpackDynamicEncoder qpackDynamicEncoder;

	private @Nullable QpackEncoderStreamReader qpackEncoderStreamReader;
	private @Nullable QpackDecoderStreamReader qpackDecoderStreamReader;

	/**
	 * The field sections received whole but not decodable yet, and the three bounds on holding them
	 * (FR-033 – FR-036). Non-null exactly when {@link #qpackDynamicDecoder} is: with no dynamic table
	 * nothing can block, which is why capacity 0 is phase-1 behaviour byte for byte (SC-011).
	 */
	private @Nullable QpackBlockedSections blockedSections;

	/**
	 * One waiter per blocked section, keyed by stream: what {@link StreamQpackDecoder} handed its
	 * request stream in place of the fields, and what {@link #resumeBlockedSections()} settles.
	 * <p>
	 * Keyed by <i>stream</i> rather than by section because an {@link Http3RequestStream} decodes one
	 * field section at a time and does not read the frame behind a held one — so a stream can have at
	 * most one section outstanding here, whatever {@link QpackBlockedSections} is prepared to hold.
	 * <p>
	 * It is also the register of <i>which streams are blocked</i> — the entry and exit counters of FR-091
	 * are per stream, and this is the only structure keyed that way.
	 */
	private final Map<Long, BlockedStream> blockedSectionWaiters = new HashMap<>();

	/**
	 * @param blockedSinceMillis when the stream became blocked, which is what makes an exit report a
	 *                           duration. Not read back off {@link QpackBlockedSections}: its timestamps
	 *                           are per <i>section</i>, and a stream that held two would have two.
	 */
	private record BlockedStream(SettablePromise<List<QpackField>> waiter, long blockedSinceMillis) {}

	/** {@link #rearmBlockedSectionTimeout()}'s handle — the one timer FR-036 needs, never a second one. */
	private @Nullable ScheduledRunnable blockedSectionTimeout;

	/**
	 * What {@link #abortOwnedStreams} reports the sections still held as leaving for. {@code CLOSED},
	 * unless FR-036's deadline is what closed the connection — in which case they are all leaving for
	 * that reason, and reporting them as merely "closing" would hide the bound that fired.
	 */
	private QpackBlockedExit blockedSectionExit = QpackBlockedExit.CLOSED;

	/**
	 * What {@link #reportQpackDecoderTable()} has already reported of the peer-driven table's two
	 * cumulative counters. Watermarks rather than a snapshot taken around each feed, because the codec
	 * holding those counters is reachable only through a nullable field — the same shape
	 * {@link #flushInsertCountIncrement()} beside it uses for the same reason.
	 */
	private long qpackDecoderInsertionsReported;
	private long qpackDecoderEvictionsReported;

	private final LocalQpackStream localQpackEncoderStream = new LocalQpackStream(Http3StreamType.QPACK_ENCODER);
	private final LocalQpackStream localQpackDecoderStream = new LocalQpackStream(Http3StreamType.QPACK_DECODER);

	/**
	 * What every {@link Http3RequestStream} this connection adopts reports to, forwarding to
	 * {@link #eventListener} unchanged and taking one action of its own: a stream reset is where
	 * FR-025's {@code Stream Cancellation} comes from. {@code Http3RequestStream.abortWith} fires it
	 * exactly once per stream, which is what makes it the single funnel rather than a second one —
	 * {@code QuicStream.whenClosed()} could not serve, since it completes <i>normally</i> on a peer
	 * reset and so cannot tell an abort from a clean close.
	 */
	private final Http3EventListener requestStreamEventListener = new Http3EventListener() {
		@Override
		public void onConnectionError(long errorCode) {
			eventListener.onConnectionError(errorCode);
		}

		@Override
		public void onFrameDiscarded(long frameType, long declaredLength) {
			eventListener.onFrameDiscarded(frameType, declaredLength);
		}

		@Override
		public void onGoAway(GoAwayDirection direction, long id) {
			eventListener.onGoAway(direction, id);
		}

		@Override
		public void onStreamReset(long streamId, long errorCode) {
			onRequestStreamAborted(streamId, errorCode);
			eventListener.onStreamReset(streamId, errorCode);
		}
	};

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

	/**
	 * The SETTINGS this peer sent on the connection whose ticket was used to resume this one, decoded
	 * from that ticket by {@link Http3Client} (FR-062). {@code null} on every connection that is not a
	 * resumption attempt, which is every connection with 0-RTT off — the default.
	 */
	private @Nullable SettingsFrame rememberedSettings;

	/** Where this connection reports the peer's SETTINGS once they land, so a ticket can carry them. */
	private @Nullable Consumer<SettingsFrame> peerSettingsListener;

	/** Where this connection reports that the peer refused its early data (spec FR-055, FR-067). */
	private @Nullable Consumer<Http3Connection> earlyDataRejectionListener;

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
			.withEarlyDataRejectionListener(this::onEarlyDataRejected)
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
		 * What every request stream this connection adopts is willing to run from early data (FR-064,
		 * FR-065). Defaults to {@link Http3EarlyDataPolicy#DEFAULT_POLICY}, so a connection wired by
		 * hand is protected without asking; {@link Http3Server.Builder#withEarlyDataPolicy} is what a
		 * consumer of the server normally sets.
		 */
		public Builder withEarlyDataPolicy(Http3EarlyDataPolicy earlyDataPolicy) {
			checkNotBuilt(this);
			Http3Connection.this.earlyDataPolicy = earlyDataPolicy;
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

		/**
		 * The SETTINGS the peer sent on the connection whose ticket resumed this one (FR-062,
		 * RFC 9114 §7.2.4.2). Setting them is what makes this connection {@linkplain #permitsEarlyData()
		 * permit early data}, and what makes a later reduction of a relied-upon value an
		 * {@code H3_SETTINGS_ERROR}.
		 * <p>
		 * Package-private, like {@link #withEventListener}: the blob these came out of is
		 * {@link Http3Client}'s to decode, and a consumer handing a connection SETTINGS no peer ever sent
		 * would be asserting a history that did not happen.
		 */
		Builder withRememberedSettings(SettingsFrame rememberedSettings) {
			checkNotBuilt(this);
			Http3Connection.this.rememberedSettings = rememberedSettings;
			return this;
		}

		/**
		 * Called once, with the peer's SETTINGS, at the moment they are applied — so that whoever built
		 * this connection can remember them beside the session ticket the peer issues (FR-062).
		 * <p>
		 * It is invoked from the control-stream read path, which reports RFC 9114 §8.1 errors, so a
		 * listener that throws is logged and the connection carries on: the only implementation writes
		 * into a <b>consumer-supplied</b> {@code QuicSessionCache}, and a broken store must not kill a
		 * working connection.
		 */
		Builder withPeerSettingsListener(Consumer<SettingsFrame> peerSettingsListener) {
			checkNotBuilt(this);
			Http3Connection.this.peerSettingsListener = peerSettingsListener;
			return this;
		}

		/**
		 * Called once, on the reactor thread, when the peer refused this connection's early data
		 * (spec FR-055, FR-067) — after this connection has already dropped its remembered SETTINGS and
		 * <b>before</b> it sweeps away whatever early-data request streams the listener does not claim.
		 * <p>
		 * That order is what makes a transparent retry possible: the listener is {@link Http3Client}'s, it
		 * knows which of the exchanges in flight went out in early data, and it takes them back before
		 * anything else can fail them. Package-private for the same reason
		 * {@link #withPeerSettingsListener} is — this is how the client that <i>built</i> this connection
		 * is told what happened underneath it.
		 */
		Builder withEarlyDataRejectionListener(Consumer<Http3Connection> listener) {
			checkNotBuilt(this);
			Http3Connection.this.earlyDataRejectionListener = listener;
			return this;
		}

		@Override
		protected Http3Connection doBuild() {
			int localCapacity = settings.qpackMaxTableCapacity();
			if (localCapacity > 0) {
				QpackDynamicDecoder decoder = new QpackDynamicDecoder(localCapacity,
					settings.qpackBlockedStreams(), settings.maxFieldSectionSize());
				qpackDynamicDecoder = decoder;
				qpackDecoder = decoder;
				qpackEncoderStreamReader = new QpackEncoderStreamReader(decoder, settings.qpackMaxInstructionSize());
				// The count is read back off the decoder, not off the settings, for the reason
				// localSettingsFrame() gives: advertisement, decoder behaviour and holding capacity are one
				// value with three consumers, and this is what stops them drifting.
				blockedSections = new QpackBlockedSections(decoder.blockedStreams(),
					settings.maxFieldSectionSize(), settings.qpackBlockedStreamTimeoutMillis());
			} else {
				qpackDecoder = new QpackStaticDecoder(settings.maxFieldSectionSize());
			}
			// The peer has said nothing yet, and RFC 9204 §3.2.3 makes an unadvertised capacity 0 — so
			// this is the correct encoder until SETTINGS arrive, not a placeholder for one. It stays the
			// encoder for the life of a connection whose peer advertises 0 (FR-019, FR-040).
			qpackEncoder = new QpackStaticEncoder();
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
		// After the control stream, so that one keeps the lowest unidirectional stream id, and outside
		// `starting`, so that a QPACK stream's failure cannot hide the control stream's. The decoder
		// stream needs nothing from the peer — what the peer's encoder may do is bounded by the maximum
		// this endpoint advertises — so this is the earliest it can be opened (FR-023).
		if (qpackDynamicDecoder != null) localQpackDecoderStream.open(null);
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
			// Wrapped unconditionally, capacity 0 included: the views delegate to the static codecs
			// byte-for-byte there, and one code path is worth more than the indirection it costs.
			.withQpackEncoder(new StreamQpackEncoder(id))
			.withQpackFieldSectionDecoder(new StreamQpackDecoder(id))
			.withEarlyDataPolicy(earlyDataPolicy)
			// The two violations a request stream can observe that the *connection* owns: a PUSH_PROMISE
			// against a push limit of 0 (FR-040), and a frame RFC 9114 §7.2's table does not permit on a
			// request stream at all (FR-024, FR-025). Everything it refuses about the message itself stays
			// on its own stream (FR-037).
			.withConnectionErrorListener(this::closeWithError)
			.withEventListener(requestStreamEventListener)
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
		return peerSetting(SettingsFrame.MAX_FIELD_SECTION_SIZE, Long.MAX_VALUE);
	}

	/**
	 * The SETTINGS remembered from the connection whose ticket resumed this one, or {@code null} —
	 * which is every connection that is not a resumption attempt.
	 */
	public @Nullable SettingsFrame rememberedSettings() {
		checkInReactorThread(this);
		return rememberedSettings;
	}

	/**
	 * FR-062, RFC 9114 §7.2.4.2: no remembered SETTINGS, no early data — valid session ticket or not.
	 * A client that sent a field section in 0-RTT without them would have encoded it against limits the
	 * server never stated.
	 */
	public boolean permitsEarlyData() {
		checkInReactorThread(this);
		return rememberedSettings != null;
	}

	/**
	 * Early data was rejected, so nothing in it was relied upon, and the RFC 9114 §7.2.4.2 non-reduction
	 * rule no longer applies to this connection.
	 * <p>
	 * Called from {@link #onEarlyDataRejected()} and nowhere else. Until a rejection is known, the
	 * non-reduction check applies whenever early data was <i>offered</i> rather than only when it was
	 * <i>accepted</i> — conservative, and the only information available at the moment SETTINGS arrive.
	 */
	void discardRememberedSettings() {
		checkInReactorThread(this);
		rememberedSettings = null;
	}

	/**
	 * The peer refused this connection's early data (spec FR-055, FR-067), in three steps whose order is
	 * the whole substance of this method:
	 * <ol>
	 *   <li><b>Forget the remembered SETTINGS.</b> Nothing that was encoded against them was processed,
	 *       so a later SETTINGS frame reducing one of their values breaks no promise — see
	 *       {@link #discardRememberedSettings()}. Done first, because it must hold for every SETTINGS
	 *       frame that can arrive after this point, including one already being parsed.</li>
	 *   <li><b>Tell the listener</b>, which is the client's chance to claim the exchanges it sent in
	 *       early data and re-issue them on fresh streams. It claims them by discarding their streams,
	 *       so what it does not claim is still here for step three.</li>
	 *   <li><b>Sweep.</b> Every request stream still marked as early data is discarded: it was created
	 *       against a promise the peer did not keep, nobody is waiting for it any more, and leaving it
	 *       would hold a {@code QuicStream} that can never complete.</li>
	 * </ol>
	 * The sweep walks a copy: discarding a stream fails whatever is parked on it, and those continuations
	 * routinely re-enter this class.
	 */
	private void onEarlyDataRejected() {
		checkInReactorThread(this);
		discardRememberedSettings();
		if (earlyDataRejectionListener != null) {
			earlyDataRejectionListener.accept(this);
		}
		for (Http3RequestStream requestStream : new ArrayList<>(requestStreams.values())) {
			if (requestStream.isEarlyData()) discardEarlyData(requestStream);
		}
	}

	/**
	 * Abandons {@code requestStream} <b>silently</b> because the early data it carried was refused: the
	 * H3 half is torn down and the QUIC stream is discarded without a {@code RESET_STREAM} or a
	 * {@code STOP_SENDING} (spec FR-055). The peer dropped this stream's 0-RTT packets undecrypted, so it
	 * has never heard of it, and naming it now would open a request stream at the server for nothing.
	 * <p>
	 * Idempotent per stream: the second call finds nothing left in either half.
	 */
	void discardEarlyData(Http3RequestStream requestStream) {
		checkInReactorThread(this);
		Exception cause = earlyDataRejection();
		long streamId = requestStream.id();
		// Dropped from the map, the H3 half torn down and the QPACK references released *before* the QUIC
		// stream is discarded, because that discard fails whatever is parked on this stream and those
		// continuations re-enter here. Each of them then finds the work already done, rather than doing a
		// second, different version of it (the registry's "move the state before the promise" rule).
		requestStreams.remove(streamId);
		requestStream.discardEarlyData(cause);
		onEarlyDataStreamDiscarded(streamId, Http3Errors.H3_REQUEST_REJECTED);
		streamManager.discardStream(streamId, cause);
	}

	/**
	 * What a discarded early-data exchange fails with, for anything still holding one. Retryable, and
	 * that is exactly what it says: nothing of the request was processed, because nothing of it was ever
	 * decrypted.
	 */
	private static Http3Exception earlyDataRejection() {
		return new Http3Exception(Http3Errors.H3_REQUEST_REJECTED,
			"The server refused this connection's early data (RFC 8446 §4.2.10)", true);
	}

	/**
	 * The value the peer advertised for {@code identifier}, or {@code defaultValue} if it advertised
	 * none — which for every setting this connection reads is the RFC's own default for an omitted one.
	 * <p>
	 * Until the peer's own SETTINGS arrive, a resumed connection reads the {@linkplain
	 * #rememberedSettings() remembered} ones instead of the default (FR-063, RFC 9114 §7.2.4.2). That
	 * one substitution <i>is</i> "obey the remembered SETTINGS until the server's own arrive": it flows
	 * into {@link #peerMaxFieldSectionSize()}, which is what bounds an outgoing field section.
	 */
	private long peerSetting(long identifier, long defaultValue) {
		SettingsFrame frame = peerSettings != null ? peerSettings : rememberedSettings;
		if (frame == null) return defaultValue;
		return Http3RememberedSettings.valueOf(frame, identifier, defaultValue);
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

	/**
	 * FR-012, FR-038: the three settings this implementation advertises, and no others.
	 * <p>
	 * Both QPACK values are read back off the <b>decoder that was actually built</b> rather than off
	 * {@link #settings}, so there is one source of truth for what this endpoint can honour. That is what
	 * makes phase 2's departure fall out instead of being a special case: a capacity of 0 builds no
	 * decoder and advertises 0 for <b>both</b> (SC-011 byte identity, since RFC 9204 §2.1.2 makes a
	 * blocked-stream permission meaningless without a table), while a configured capacity advertises the
	 * limit the decoder was built with and {@link QpackBlockedSections} sized itself from — one value,
	 * three consumers, no drift.
	 */
	private SettingsFrame localSettingsFrame() {
		QpackDynamicDecoder decoder = qpackDynamicDecoder;
		return new SettingsFrame(
			new long[]{
				SettingsFrame.QPACK_MAX_TABLE_CAPACITY,
				SettingsFrame.MAX_FIELD_SECTION_SIZE,
				SettingsFrame.QPACK_BLOCKED_STREAMS},
			new long[]{
				decoder == null ? 0 : decoder.maxCapacity(),
				settings.maxFieldSectionSize(),
				decoder == null ? 0 : decoder.blockedStreams()});
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
			Http3RememberedSettings.validateNoReduction(rememberedSettings, settingsFrame);
			peerSettings = settingsFrame;
			updateState();
			onPeerSettingsApplied();
			notifyPeerSettings(settingsFrame);
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

	// ---------------------------------------------------------------- the local QPACK streams

	/**
	 * The peer's SETTINGS have landed, which is the earliest moment the encoder half of QPACK can
	 * exist: RFC 9204 §3.2.3 makes the peer's advertised {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY} the
	 * ceiling on what this endpoint may request, and a peer that advertises none permits 0 (FR-019).
	 * The encoder stream opens here too, and its preamble <i>is</i> the encoder's first drained
	 * instruction — which is what makes FR-017's "a {@code Set Dynamic Table Capacity} first"
	 * structural rather than asserted.
	 * <p>
	 * Must not throw: its caller reports RFC 9114 §8.1 errors, and a runtime failure raised here would
	 * escape as one.
	 */
	private void onPeerSettingsApplied() {
		int localCapacity = settings.qpackMaxTableCapacity();
		if (localCapacity == 0) return;
		// Every wire-sourced value is clamped before it reaches an int constructor, so a peer advertising
		// 2^62 cannot wrap into range. Clamping the advertised maximum does shift RFC 9204 §4.5.1.1's
		// MaxEntries, but this endpoint's own table is orders of magnitude below the wrap point, so no
		// encoded insert count it ever writes differs.
		int peerMaxTableCapacity = clampToInt(peerSetting(SettingsFrame.QPACK_MAX_TABLE_CAPACITY, 0));
		int capacity = Math.min(localCapacity, peerMaxTableCapacity);
		if (capacity <= 0) return;
		int peerBlockedStreams = clampToInt(peerSetting(SettingsFrame.QPACK_BLOCKED_STREAMS, 0));
		// Derived rather than configured (WI-14): a setting of its own would be a new Http3Settings field
		// plus a CHANGELOG entry. Two sections per concurrent request stream covers a request and its
		// response, and the multiplication is widened first so the bound cannot overflow into a small one.
		// Not the same bound as qpackBlockedStreams, and not comparable to it: this counts sections this
		// endpoint's *encoder* has emitted and not had acknowledged, pinning entries in the table it
		// encodes from, while qpackBlockedStreams counts sections this endpoint's *decoder* holds awaiting
		// the peer's insertions (see QpackBlockedSections). Two tables, two directions, two bounds.
		int maxOutstandingSections = clampToInt(2L * settings.maxConcurrentRequestStreams());
		QpackDynamicEncoder encoder = new QpackDynamicEncoder(peerMaxTableCapacity, capacity,
			peerBlockedStreams, maxOutstandingSections, settings.qpackNeverIndexedFields());
		qpackDynamicEncoder = encoder;
		qpackEncoder = encoder;
		qpackDecoderStreamReader = new QpackDecoderStreamReader(encoder, settings.qpackMaxInstructionSize());
		localQpackEncoderStream.open(instructionBuffer(encoder.drainPendingInstructions()));
	}

	/**
	 * Reports the peer's SETTINGS to whoever built this connection, so a session ticket can carry them
	 * (FR-062). The listener writes into a store this module does not own, so a failure there is logged
	 * and swallowed rather than allowed to escape into the control-stream read path, where it would
	 * close a connection that is working.
	 */
	private void notifyPeerSettings(SettingsFrame settingsFrame) {
		Consumer<SettingsFrame> listener = peerSettingsListener;
		if (listener == null) return;
		try {
			listener.accept(settingsFrame);
		} catch (RuntimeException e) {
			logger.warn("A peer-SETTINGS listener failed on {}", this, e);
		}
	}

	private static int clampToInt(long value) {
		return (int) Math.min(Math.max(value, 0), Integer.MAX_VALUE);
	}

	/**
	 * The one place encoder-stream instructions are written (research D-2). Called from
	 * {@link StreamQpackEncoder#encode} between the encode that accumulated them and the return that
	 * hands the field section to whoever writes it — a second drain site would be the registry's
	 * "second route into release bookkeeping" in another costume.
	 */
	private void drainQpackInstructions(QpackDynamicEncoder encoder) {
		if (!encoder.hasPendingInstructions()) return;
		localQpackEncoderStream.write(instructionBuffer(encoder.drainPendingInstructions()));
	}

	/**
	 * {@code instructions} back to back in one owned buffer. They are values, never buffers, so nothing
	 * can leak between the encode that accumulated them and this (DI-1).
	 */
	private static ByteBuf instructionBuffer(List<EncoderInstruction> instructions) {
		int length = 0;
		for (EncoderInstruction instruction : instructions) {
			length += instruction.encodedLength();
		}
		ByteBuf buf = ByteBufPool.allocate(length);
		for (EncoderInstruction instruction : instructions) {
			instruction.writeTo(buf);
		}
		return buf;
	}

	private void writeDecoderInstruction(DecoderInstruction instruction) {
		localQpackDecoderStream.write(QpackInstructions.encode(instruction));
	}

	/**
	 * FR-026, RFC 9204 §4.4.3: the insertions this endpoint has processed that no
	 * {@code Section Acknowledgment} already covered. Called after every successful read of the peer's
	 * encoder stream, so an entry the peer inserted speculatively becomes referenceable without waiting
	 * for a request to name it.
	 */
	private void flushInsertCountIncrement() {
		QpackDynamicDecoder decoder = qpackDynamicDecoder;
		if (decoder == null) return;
		long increment = decoder.pendingInsertCountIncrement();
		if (increment <= 0) return;
		writeDecoderInstruction(new InsertCountIncrement(increment));
		decoder.onInsertCountAnnounced(decoder.insertCount());
	}

	/**
	 * FR-018: what the peer's encoder stream just inserted into — and evicted from — the table this
	 * endpoint decodes with. Called after every successful read of that stream, and after
	 * {@link #flushInsertCountIncrement()}, so nothing is reported ahead of what the wire owes.
	 * <p>
	 * The watermarks advance <i>before</i> the listener is called: an implementation that throws fails
	 * the operation it was reporting on, and must not also make the next report count these insertions
	 * a second time.
	 */
	private void reportQpackDecoderTable() {
		QpackDynamicDecoder decoder = qpackDynamicDecoder;
		if (decoder == null) return;
		long insertions = decoder.insertCount() - qpackDecoderInsertionsReported;
		long evictions = decoder.evictedCount() - qpackDecoderEvictionsReported;
		qpackDecoderInsertionsReported = decoder.insertCount();
		qpackDecoderEvictionsReported = decoder.evictedCount();
		reportQpackTable(QpackTable.DECODER, insertions, evictions, decoder.tableSize());
	}

	/**
	 * The one place a dynamic-table counter becomes an event (FR-018). A counter that did not move is
	 * not an event, and every argument is a number — a field name or a value has no way to reach an
	 * inspector from here (SI-6).
	 */
	private void reportQpackTable(QpackTable table, long insertions, long evictions, int tableBytes) {
		if (insertions > 0) eventListener.onQpackInsertions(table, clampToInt(insertions), tableBytes);
		if (evictions > 0) eventListener.onQpackEvictions(table, clampToInt(evictions), tableBytes);
	}

	// ---------------------------------------------------------------- blocked field sections (FR-033–FR-037)

	/**
	 * The single acknowledgment funnel (FR-024), taken by a section that decoded on arrival and by one
	 * that decoded after waiting alike — the two paths must owe the peer the same thing, and one funnel
	 * is how that stops being a coincidence.
	 */
	private List<QpackField> onSectionDecoded(long streamId, Decoded decoded) {
		QpackDynamicDecoder decoder = qpackDynamicDecoder;
		if (decoder != null && decoded.requiredInsertCount() > 0) {
			writeDecoderInstruction(new SectionAcknowledgment(streamId));
			// RFC 9204 §4.4.1: the acknowledgment itself tells the peer's encoder that every insertion up
			// to this count arrived, so an Insert Count Increment measured from anywhere below it would
			// count those insertions twice.
			decoder.onInsertCountAnnounced(decoded.requiredInsertCount());
		}
		return decoded.fields();
	}

	/**
	 * RFC 9204 §2.1.2, FR-033: the section waits here instead of failing, and its request stream waits on
	 * the promise this returns.
	 * <p>
	 * Exceeding the count or the byte bound is a <b>connection</b> error (FR-034, FR-035), and it needs no
	 * escalation path of its own: {@code hold} has already recycled the section and raises a
	 * {@code connectionError}-marked {@link QpackException}, which travels the route every QPACK failure
	 * takes — {@code decodeFieldSection} → {@code Http3Exception.connectionScoped} → {@code abortWith} →
	 * the connection-error listener → {@link #closeWithError}.
	 */
	private Promise<List<QpackField>> holdBlockedSection(long streamId, Blocked blocked) {
		QpackBlockedSections sections = blockedSections;
		if (sections == null) {
			// Unreachable: a section can only block against a dynamic decoder, and the two are built together.
			blocked.section().recycle();
			return Promise.ofException(QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
				"a field section blocked on an unarrived insertion, which this endpoint does not hold"));
		}
		long now = reactor.currentTimeMillis();
		try {
			sections.hold(streamId, blocked.requiredInsertCount(), blocked.section(), now);
		} catch (QpackException e) {
			eventListener.onQpackBlockedSectionRefused(streamId, blockedSectionWaiters.size(), sections.heldBytes());
			return Promise.ofException(e);
		}
		rearmBlockedSectionTimeout();
		SettablePromise<List<QpackField>> waiter = new SettablePromise<>();
		BlockedStream previous = blockedSectionWaiters.get(streamId);
		blockedSectionWaiters.put(streamId,
			new BlockedStream(waiter, previous == null ? now : previous.blockedSinceMillis()));
		if (previous == null) {
			eventListener.onQpackStreamBlocked(streamId, blockedSectionWaiters.size(), sections.heldBytes());
			return waiter;
		}
		// Unreachable — a request stream issues no second decode while its first is pending — and failed
		// rather than skipped, because stranding the earlier caller would be the worse of the two. No entry
		// is reported for it: a stream already blocked does not become blocked again.
		previous.waiter().trySetException(QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
			"two field sections blocked at once on stream " + streamId));
		return waiter;
	}

	/**
	 * The single exit funnel of FR-091's counters: a stream stops being blocked in exactly the four ways
	 * {@link QpackBlockedExit} names, and routing all four through here is what makes an entry without an
	 * exit unreachable rather than merely unlikely.
	 *
	 * @return the waiter its request stream is holding, or {@code null} if that stream was not blocked
	 */
	private @Nullable SettablePromise<List<QpackField>> unblock(long streamId, QpackBlockedExit exit) {
		BlockedStream blocked = blockedSectionWaiters.remove(streamId);
		if (blocked == null) return null;
		eventListener.onQpackStreamUnblocked(streamId, exit,
			reactor.currentTimeMillis() - blocked.blockedSinceMillis(), blockedSectionWaiters.size());
		return blocked.waiter();
	}

	/**
	 * Settles every section the peer's latest insertions made decodable, <b>in arrival order</b> (FR-037)
	 * — the decode path of FR-035's three releases.
	 *
	 * <h4>Two guarantees make FR-037 hold, and only one of them is load-bearing</h4>
	 * The load-bearing one is structural and lives in {@link Http3RequestStream}: a request stream reads
	 * its frames strictly in sequence, so while one of its field sections is held the <i>next</i> HEADERS
	 * frame is never even taken off the QUIC stream — a later section cannot overtake an earlier one
	 * because it does not exist here yet. The second is {@link QpackBlockedSections#release} draining each
	 * stream from its head and sorting globally by arrival order, which is what keeps the first from being
	 * the only thing standing between a peer and a reordered message.
	 * <p>
	 * Every waiter is settled synchronously, and settling one routinely resets a stream or closes the
	 * connection from inside this loop. That is safe only because {@link QpackBlockedSections#release}
	 * has already handed the sections over — nothing here reads the structure again — and because the
	 * per-iteration {@code remove} and {@code CLOSED} check below are what keep a section whose waiter has
	 * meanwhile gone from being decoded into nobody's hands. Neither is optional.
	 */
	private void resumeBlockedSections() {
		QpackDynamicDecoder decoder = qpackDynamicDecoder;
		QpackBlockedSections sections = blockedSections;
		if (decoder == null || sections == null || sections.isEmpty()) return;
		List<HeldSection> released = sections.release(decoder.insertCount());
		if (released.isEmpty()) return;
		rearmBlockedSectionTimeout();
		for (HeldSection held : released) {
			SettablePromise<List<QpackField>> waiter = unblock(held.streamId(), QpackBlockedExit.DECODED);
			if (waiter == null || state == State.CLOSED) {
				held.section().recycle();
				continue;
			}
			try {
				SectionResult result = decoder.decodeOrBlock(held.section());
				if (result instanceof Decoded decoded) {
					waiter.set(onSectionDecoded(held.streamId(), decoded));
				} else {
					// Unreachable: the Insert Count only rises, and release() hands back only sections at or
					// below it. Stated as a failure rather than re-held, so it cannot become a silent loop.
					((Blocked) result).section().recycle();
					waiter.setException(QpackException.connectionError(Http3Errors.QPACK_DECOMPRESSION_FAILED,
						"a released field section that blocked again"));
				}
			} catch (QpackException e) {
				waiter.setException(e);
			}
		}
	}

	/**
	 * FR-036's one timer, re-armed from <b>every</b> site that changes what is held — after a hold, after
	 * a release, after a discard — and cancelled before it is re-scheduled, always. One entry point is
	 * what makes "a timer that outlives what it was watching" unreachable rather than merely unlikely.
	 * <p>
	 * {@link QpackBlockedSections#earliestDeadlineMillis()} is already an absolute reactor-clock
	 * timestamp, since {@code arrivalMillis} came from {@code reactor.currentTimeMillis()} — so
	 * {@code schedule(timestamp, …)} is the exact fit rather than a departure from the module's
	 * {@code reactor.delay(...)} rule: {@code delay(ms, r)} <i>is</i>
	 * {@code schedule(currentTimeMillis() + ms, r)}, and both are driven by the same hand-set clock a
	 * test installs.
	 */
	private void rearmBlockedSectionTimeout() {
		blockedSectionTimeout = nullify(blockedSectionTimeout, ScheduledRunnable::cancel);
		QpackBlockedSections sections = blockedSections;
		if (sections == null || state == State.CLOSED) return;
		long deadline = sections.earliestDeadlineMillis();
		if (deadline == QpackBlockedSections.NO_DEADLINE) return;
		blockedSectionTimeout = reactor.schedule(deadline, this::onBlockedSectionDeadline);
	}

	/**
	 * FR-036: a peer may legally block a stream and then simply stop sending, so a held section has a
	 * bounded lifetime and exceeding it closes the <b>connection</b> with
	 * {@code QPACK_DECOMPRESSION_FAILED} (research D-4).
	 * <p>
	 * Nothing is released here. {@link QpackBlockedSections#checkTimeout} deliberately holds on to what
	 * it refuses, so that {@link #abortOwnedStreams} stays the single place held memory goes back and
	 * FR-035 and FR-036 cannot disagree about which of them owed the recycle.
	 */
	private void onBlockedSectionDeadline() {
		// First: this handle has run, and a later cancel must not be aimed at a stale one.
		blockedSectionTimeout = null;
		QpackBlockedSections sections = blockedSections;
		if (sections == null || state == State.CLOSED) return;
		try {
			sections.checkTimeout(reactor.currentTimeMillis());
		} catch (QpackException e) {
			// Before the close, because the close is what releases them and this is what says why.
			blockedSectionExit = QpackBlockedExit.TIMED_OUT;
			closeWithError(new Http3Exception(e.errorCode(), e.reason()));
			return;
		}
		// Clock granularity, or a section released in the same turn: nothing is due yet, so re-aim.
		rearmBlockedSectionTimeout();
	}

	/**
	 * A request stream ended abnormally: two of the three terminal paths into the reference-release
	 * bookkeeping of both tables (research D-3). The peer's encoder learns it will never acknowledge
	 * this stream's section, this endpoint's own encoder releases what that stream pinned, and a section
	 * this endpoint was still <b>holding</b> for that stream is released with them (FR-025, FR-035).
	 * <p>
	 * FR-025's "unless the connection is already closing" is the state check, and it is exact because
	 * {@link #close()} and {@link #closeWithError} both transition to {@code CLOSED} <i>before</i>
	 * aborting the streams they own. Over-emitting a {@code Stream Cancellation} is safe — RFC 9204
	 * §4.4.2 gives its receipt no "no outstanding section" error, unlike §4.4.1 — while under-emitting
	 * pins the peer's table, so it fires on every abnormal termination.
	 */
	private void onRequestStreamAborted(long streamId, long errorCode) {
		onRequestStreamEnded(streamId, errorCode, true);
	}

	/**
	 * The same bookkeeping for a stream the peer <b>never saw</b> — an early-data exchange discarded
	 * because the server refused the early data (spec FR-055).
	 * <p>
	 * Everything local still has to happen: this endpoint's encoder releases what the stream pinned, a
	 * held section is released, a blocked waiter is failed. What must <b>not</b> happen is the
	 * {@code Stream Cancellation}: RFC 9204 §4.4.2 has it tell the peer's encoder that a section it sent
	 * will never be acknowledged, and the peer sent none — the stream's 0-RTT packets were dropped
	 * undecrypted, so naming it would announce a stream that does not exist there.
	 */
	private void onEarlyDataStreamDiscarded(long streamId, long errorCode) {
		onRequestStreamEnded(streamId, errorCode, false);
	}

	private void onRequestStreamEnded(long streamId, long errorCode, boolean tellThePeer) {
		if (state == State.CLOSED) return;
		if (tellThePeer && qpackDynamicDecoder != null) writeDecoderInstruction(new StreamCancellation(streamId));
		if (qpackDynamicEncoder != null) qpackDynamicEncoder.onStreamCancelled(streamId);
		QpackBlockedSections sections = blockedSections;
		// The reset path of FR-035's three: discard recycles every section held for this stream, and the
		// timeout is re-armed because the deadline it was watching may have been this stream's.
		if (sections != null && sections.discard(streamId) > 0) rearmBlockedSectionTimeout();
		SettablePromise<List<QpackField>> waiter = unblock(streamId, QpackBlockedExit.RESET);
		if (waiter != null) {
			waiter.trySetException(new Http3Exception(errorCode,
				"Request stream " + streamId + " ended while a field section of its was still blocked"));
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
	 * Takes ownership of {@code buf}, and routes it per <b>direction</b> and per <b>codec</b> rather
	 * than per connection: the decoder half of QPACK exists from {@code build()} while the encoder half
	 * appears only when the peer's SETTINGS arrive, so the peer's decoder stream may legitimately be
	 * read by the capacity-0 path first and by the dynamic one afterwards — and before that switch the
	 * capacity-0 path is the correct answer anyway, since nothing has been inserted for the peer to
	 * acknowledge.
	 * <p>
	 * <b>Ownership differs between the two branches.</b> Both readers take {@code buf} on every path,
	 * a throw included, so nothing here may recycle it; {@link #feedQpackStreamAtCapacityZero} owns and
	 * recycles it itself.
	 */
	private boolean feedQpackStream(ByteBuf buf, boolean encoder) throws Http3Exception {
		QpackEncoderStreamReader encoderStreamReader = qpackEncoderStreamReader;
		if (encoder && encoderStreamReader != null) {
			try {
				encoderStreamReader.feed(buf);
			} catch (QpackException e) {
				// Per-cause scope is already resolved inside QpackException and FR-032 forbids widening it,
				// so the code travels unchanged rather than being re-derived here.
				throw new Http3Exception(e.errorCode(), e.reason());
			}
			// Before the increment, not after: a resumed section's Section Acknowledgment already tells the
			// peer's encoder that every insertion up to its Required Insert Count arrived (RFC 9204 §4.4.1),
			// so acknowledging first leaves flushInsertCountIncrement the smaller remainder to send instead
			// of counting those insertions twice.
			resumeBlockedSections();
			flushInsertCountIncrement();
			reportQpackDecoderTable();
			return state != State.CLOSED;
		}
		QpackDecoderStreamReader decoderStreamReader = qpackDecoderStreamReader;
		if (!encoder && decoderStreamReader != null) {
			try {
				decoderStreamReader.feed(buf);
			} catch (QpackException e) {
				throw new Http3Exception(e.errorCode(), e.reason());
			}
			return state != State.CLOSED;
		}
		return feedQpackStreamAtCapacityZero(buf, encoder);
	}

	/**
	 * Takes ownership of {@code buf}. With a dynamic-table capacity of 0 the peer's encoder stream may
	 * carry only {@code Set Dynamic Table Capacity 0}, and its decoder stream nothing at all: nothing
	 * was ever inserted, so there is no section to acknowledge and no insert count to increment
	 * (FR-018, RFC 9204 §4.2).
	 * <p>
	 * Phase 1's body, kept verbatim rather than routed through the dynamic readers, because D-10 and
	 * SC-011 require capacity-0 behaviour to be phase 1 <i>exactly</i> — down to which instruction is
	 * refused with which code.
	 */
	private boolean feedQpackStreamAtCapacityZero(ByteBuf buf, boolean encoder) throws Http3Exception {
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
		localQpackEncoderStream.abort(errorCode);
		localQpackDecoderStream.abort(errorCode);
		// Each holds the partial instruction it was part-way through; nothing will ever finish it (DI-1).
		if (qpackEncoderStreamReader != null) qpackEncoderStreamReader.recycle();
		if (qpackDecoderStreamReader != null) qpackDecoderStreamReader.recycle();
		// A copy: aborting fails pending writes, whose continuations routinely close streams.
		// Through the request stream rather than the QUIC stream, so the message it owns is released with
		// it (FR-057a) instead of surviving the connection that produced it.
		for (Http3RequestStream requestStream : new ArrayList<>(requestStreams.values())) {
			requestStream.abort(errorCode, "The HTTP/3 connection is closing");
		}
		requestStreams.clear();
		// The third terminal path into the release bookkeeping (research D-3), and the only one that
		// releases every stream at once. Both directions, and there is no fourth: a section held here goes
		// back when it decodes (resumeBlockedSections), when its stream is reset (onRequestStreamAborted)
		// or here — and this is the only one of the three that releases every section at once (FR-035).
		if (qpackDynamicEncoder != null) qpackDynamicEncoder.releaseAll();
		blockedSectionTimeout = nullify(blockedSectionTimeout, ScheduledRunnable::cancel);
		if (blockedSections != null) blockedSections.recycle();
		if (!blockedSectionWaiters.isEmpty()) {
			// Over a copy of the keys: failing a waiter re-enters abortWith, which is idempotent and by now
			// already terminal, but which must not find a waiter this loop has yet to reach — and unblock()
			// removes as it goes, so a re-entrant path finds nothing rather than a stale entry.
			List<Long> streamIds = new ArrayList<>(blockedSectionWaiters.keySet());
			Http3Exception failure = new Http3Exception(errorCode, "The HTTP/3 connection is closing");
			for (Long streamId : streamIds) {
				SettablePromise<List<QpackField>> waiter = unblock(streamId, blockedSectionExit);
				if (waiter != null) waiter.trySetException(failure);
			}
		}
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
	 * One locally-opened QPACK unidirectional stream (RFC 9204 §4.2): its type varint, its preamble,
	 * and an ordered chain of writes.
	 * <p>
	 * <b>The chain is the ordering guarantee.</b> An encoder instruction must reach the peer before the
	 * field section that references it, and two writes issued in order can only arrive in order if the
	 * second is queued behind the first's promise. Every write takes ownership of its buffer on every
	 * path — this stream already aborted included — and a failure resets the chain to a completed
	 * promise, since one failed write must not strand every later one.
	 * <p>
	 * A write failure is a debug line, not an escalation, exactly as the local control stream's is
	 * ({@link #writeGoAway}): a peer that {@code STOP_SENDING}s a stream this endpoint opened has ended
	 * this side of it, and there is nothing left to announce on a stream that is gone. The <i>peer's</i>
	 * QPACK streams stay critical as phase 1 makes them; this is about the two we open.
	 */
	private final class LocalQpackStream {
		private final Http3StreamType type;

		private @Nullable QuicStream stream;
		private Promise<Void> chain = Promise.complete();
		private boolean opened;
		private boolean closed;
		private long abortCode = Http3Errors.H3_NO_ERROR;

		private LocalQpackStream(Http3StreamType type) {
			this.type = type;
		}

		/**
		 * Opens the stream and writes its RFC 9114 §6.2 type varint followed by {@code preamble}.
		 * Takes ownership of {@code preamble}, which may be {@code null} for a type varint alone.
		 */
		void open(@Nullable ByteBuf preamble) {
			if (opened || closed) {
				if (preamble != null) preamble.recycle();
				return;
			}
			opened = true;
			int preambleLength = preamble == null ? 0 : preamble.readRemaining();
			ByteBuf buf = ByteBufPool.allocate(QuicVarInts.encodedLength(type.code()) + preambleLength);
			QuicVarInts.write(buf, type.code());
			if (preamble != null) {
				buf.put(preamble);
				preamble.recycle();
			}
			chain = streamManager.openUnidirectional()
				.then(
					local -> {
						if (closed) {
							buf.recycle();
							discard(local, abortCode);
							return Promise.complete();
						}
						stream = local;
						// writer() owns the buffer on every path, this one included.
						return local.writer().accept(buf);
					},
					e -> {
						// The open failed, so nothing ever took the buffer.
						buf.recycle();
						return Promise.<Void>ofException(e);
					})
				.then($ -> Promise.complete(), this::logAndContinue);
		}

		/** Takes ownership of {@code buf} on every path, this stream already aborted included. */
		void write(ByteBuf buf) {
			chain = chain
				.then(
					$ -> {
						QuicStream open = stream;
						if (open == null || closed) {
							buf.recycle();
							return Promise.complete();
						}
						return open.writer().accept(buf);
					},
					e -> {
						buf.recycle();
						return Promise.<Void>ofException(e);
					})
				.then($ -> Promise.complete(), this::logAndContinue);
		}

		void abort(long errorCode) {
			closed = true;
			abortCode = errorCode;
			discard(stream, errorCode);
			stream = null;
		}

		/** Resets the chain to a completed promise, so one failure cannot strand every later write. */
		private Promise<Void> logAndContinue(Exception e) {
			logger.debug("The local {} stream could not be written: {}", type, e.toString());
			return Promise.complete();
		}
	}

	/**
	 * The per-request-stream {@link QpackEncoder} view, and the one place the FR-017/D-2 ordering
	 * discipline lives: the instructions a section's encoding produced are drained to the encoder stream
	 * <b>after</b> the encode that accumulated them and <b>before</b> the section is returned, because
	 * the caller writes the section the moment it has it.
	 * <p>
	 * {@code QpackDynamicEncoder.forStream(id)} is deliberately not used: a plain view cannot express a
	 * drain that has to interleave between the encode and the return.
	 * <p>
	 * The FR-018 counters are read across that same encode and reported <b>after</b> the drain: they
	 * write nothing to any stream, so nothing in them can reorder what does.
	 */
	private final class StreamQpackEncoder implements QpackEncoder {
		private final long streamId;

		private StreamQpackEncoder(long streamId) {
			this.streamId = streamId;
		}

		@Override
		public ByteBuf encode(List<QpackField> fields) {
			QpackDynamicEncoder encoder = qpackDynamicEncoder;
			// qpackEncoder never holds one of these wrappers — it is the static encoder or the dynamic
			// one — so the delegation cannot recurse.
			if (encoder == null) return qpackEncoder.encode(fields);
			// Snapshotted rather than watermarked, the encoder being in hand here: what one encode
			// contributed to the four cumulative counters is what FR-018 reports (SI-6 — four numbers).
			long insertions = encoder.insertCount();
			long evictions = encoder.evictedCount();
			long fieldLines = encoder.fieldsEncoded();
			long dynamicReferences = encoder.dynamicReferences();
			ByteBuf section = encoder.encode(streamId, fields);
			drainQpackInstructions(encoder);
			reportQpackTable(QpackTable.ENCODER, encoder.insertCount() - insertions,
				encoder.evictedCount() - evictions, encoder.tableSize());
			eventListener.onQpackFieldSectionEncoded(streamId,
				clampToInt(encoder.fieldsEncoded() - fieldLines),
				clampToInt(encoder.dynamicReferences() - dynamicReferences));
			return section;
		}
	}

	/**
	 * The per-request-stream field-section decoder: it decodes as the connection's decoder does, owes the
	 * peer an acknowledgment for every section that referenced the dynamic table (FR-024), and — this
	 * being the half a bare {@link QpackDecoder} cannot express — <b>holds</b> a section whose Required
	 * Insert Count has not arrived rather than failing it (FR-033).
	 * <p>
	 * With no dynamic table there is nothing to wait for, so that path delegates to the connection's
	 * static decoder and completes synchronously: capacity 0 is phase-1 behaviour byte for byte (SC-011).
	 */
	private final class StreamQpackDecoder implements Http3FieldSectionDecoder {
		private final long streamId;

		private StreamQpackDecoder(long streamId) {
			this.streamId = streamId;
		}

		@Override
		public Promise<List<QpackField>> decode(ByteBuf encodedFieldSection) {
			QpackDynamicDecoder decoder = qpackDynamicDecoder;
			try {
				if (decoder == null) return Promise.of(qpackDecoder.decode(encodedFieldSection));
				SectionResult result = decoder.decodeOrBlock(encodedFieldSection);
				if (result instanceof Decoded decoded) return Promise.of(onSectionDecoded(streamId, decoded));
				// Blocked hands the buffer back untouched, so holdBlockedSection is what owns it from here.
				return holdBlockedSection(streamId, (Blocked) result);
			} catch (QpackException e) {
				// Both decoders own and recycle their input on every path, this throw included (DI-1).
				return Promise.ofException(e);
			}
		}
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
