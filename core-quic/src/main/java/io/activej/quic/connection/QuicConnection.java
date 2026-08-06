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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.inspector.BaseInspector;
import io.activej.common.recycle.Recyclers;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.QuicDecryptionException;
import io.activej.quic.codec.*;
import io.activej.quic.connection.CoalescedPackets.Kind;
import io.activej.quic.connection.CoalescedPackets.ProtectedPacket;
import io.activej.quic.crypto.InitialKeys;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.crypto.QuicPacketProtection;
import io.activej.quic.crypto.RetryIntegrityTag;
import io.activej.quic.tls.*;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.schedule.ScheduledRunnable;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * One QUIC connection: the reactor-confined state machine that drives a TLS 1.3 handshake to
 * completion over UDP, tracks packet number spaces, and assembles and opens protected packets.
 * <p>
 * <b>Scope of this phase.</b> The handshake (US1) is complete: connection-ID negotiation, transport
 * parameters, CRYPTO reassembly and fragmentation, key installation and discard, immediate ACKs for
 * the handshake spaces, and the handshake deadline. Loss detection, PTO, congestion control, the
 * full termination protocol (draining, idle timeout, keep-alive) and Retry/Version Negotiation are
 * later phases; where a decision belongs to one of those, the code says so rather than guessing.
 * <p>
 * <b>Reactor confinement.</b> Every public method begins with {@code checkInReactorThread(this)}
 * (WI-1). Nothing here is thread-safe and nothing is meant to be.
 * <p>
 * <b>Buffer ownership</b> (DI-1) is the part worth reading twice:
 * <ul>
 *     <li>{@link #onDatagram} <b>owns</b> its argument and recycles it on every path.</li>
 *     <li>{@link QuicPacketProtection#open} recycles the packet it is given, always.</li>
 *     <li>{@link TlsEngine#consume} recycles its input, always; the buffers on its result are ours.</li>
 *     <li>Frames handed to a {@link SendQueue} or held by a {@link SentPacket} are owned by that
 *         structure until it is acked, dropped or discarded.</li>
 *     <li>{@link DatagramSink#send} takes ownership of the datagram.</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000 — QUIC transport</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001">RFC 9001 — Using TLS to Secure QUIC</a>
 */
public final class QuicConnection extends AbstractReactive {
	private static final Logger logger = LoggerFactory.getLogger(QuicConnection.class);

	/** RFC 8446 §6: {@code handshake_failure}, the alert a HelloRetryRequest is reported as (FR-004a). */
	public static final int HELLO_RETRY_REQUEST_ALERT = 40;

	/**
	 * Fresh CRYPTO output is cut into chunks no larger than this before being queued, so that a
	 * server flight bigger than one packet spans several rather than becoming unsendable. Sized well
	 * below the 1200-byte floor every QUIC path must support.
	 */
	private static final int MAX_CRYPTO_CHUNK = 1000;

	/**
	 * A flush emits at most this many datagrams. A frame larger than a packet's allowance would
	 * otherwise sit at the front of its queue forever while other levels kept making progress; this
	 * bounds that into a stall rather than a spin. {@link #MAX_CRYPTO_CHUNK} is what makes it
	 * unreachable in practice.
	 */
	private static final int MAX_DATAGRAMS_PER_FLUSH = 64;

	/**
	 * The order packets are coalesced in: lowest level first (RFC 9000 §12.2).
	 * <p>
	 * An explicit array rather than {@code values()}, so that appending an encryption level cannot
	 * start emitting packets at it by accident — which is why {@code ZERO_RTT} appears here only now
	 * that something sends one. Its position between {@code INITIAL} and {@code HANDSHAKE} is the
	 * RFC's: a datagram carrying a client's ClientHello and its first early data coalesces them in
	 * that order.
	 * <p>
	 * Being in this list does not make a level sendable; {@link #buildPacketFor} still asks for send
	 * keys, which a server never has at {@code ZERO_RTT} (spec FR-052).
	 */
	private static final EncryptionLevel[] LEVEL_ORDER = {
		EncryptionLevel.INITIAL, EncryptionLevel.ZERO_RTT, EncryptionLevel.HANDSHAKE, EncryptionLevel.ONE_RTT};

	private static final byte[] NO_TOKEN = new byte[0];

	/** RFC 9000 §13.2.2: acknowledge at least every second ack-eliciting packet. */
	private static final int ACK_ELICITING_THRESHOLD = 2;

	/**
	 * RFC 9000 §10.2: both the closing and the draining period last three probe timeouts — long enough
	 * for a peer's retransmission to arrive and be answered, short enough to bound the state a closed
	 * connection occupies.
	 */
	public static final int CLOSING_PERIOD_PTO_MULTIPLIER = 3;

	/**
	 * How much of a peer's CONNECTION_CLOSE reason phrase is surfaced through
	 * {@link #peerCloseReason()} (FR-031).
	 * <p>
	 * The phrase is attacker-controlled text: it is bounded here rather than at the codec, because the
	 * codec's own bound is the datagram and a 1200-byte string in a log line is a denial of service of
	 * the reader's attention if not of the process. Never used as a format string (SI-6).
	 */
	public static final int MAX_SURFACED_REASON_BYTES = 256;

	/**
	 * The payload room a client Initial must still have after its Retry token, or the Retry is ignored.
	 * <p>
	 * Any positive allowance is enough for the handshake to <i>progress</i>, but an allowance of a few
	 * bytes would spend a packet's worth of header per byte of ClientHello. This is the point below which
	 * accepting the token is worse than declining it.
	 */
	private static final int MIN_INITIAL_PAYLOAD = 256;

	/**
	 * The ACK frame's Ack Delay field is in microseconds shifted right by the sender's
	 * {@code ack_delay_exponent} (RFC 9000 §19.3). We advertise the default exponent of 3, so one unit
	 * is 8 microseconds and a millisecond is 125 units.
	 */
	private static final long ACK_DELAY_UNITS_PER_MILLI =
		1000 >> QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT;

	/** The client's role, which decides key direction, padding duty and who sends HANDSHAKE_DONE. */
	public enum Role {
		CLIENT, SERVER
	}

	/** Where assembled datagrams go. Phase 4's endpoint implements this over one shared UDP socket. */
	public interface DatagramSink {
		/** Takes ownership of {@code datagram}. */
		void send(InetSocketAddress to, ByteBuf datagram);
	}

	/**
	 * Builds the TLS engine once the connection has chosen its connection IDs.
	 * <p>
	 * The indirection exists because the engine's config needs the local transport parameters, which
	 * carry {@code initial_source_connection_id} — a value only the connection can produce. The caller
	 * supplies identity and trust; the connection supplies the parameters.
	 */
	public interface TlsEngineFactory {
		TlsEngine create(QuicTransportParameters localTransportParameters);
	}

	/**
	 * What a peer said when it closed (RFC 9000 §19.19), surfaced for diagnostics.
	 *
	 * @param isApplication whether the peer sent the application variant (frame type {@code 0x1d}),
	 *                      which carries an application error code rather than a transport one
	 * @param frameType     the frame type the peer blames, 0 when it blames none; always 0 for an
	 *                      application close
	 * @param reason        the reason phrase, decoded as UTF-8 and truncated to
	 *                      {@link #MAX_SURFACED_REASON_BYTES}. <b>Untrusted text</b>: never a format
	 *                      string, never interpolated into an exception message (SI-6, FR-031)
	 */
	public record PeerClose(boolean isApplication, long errorCode, long frameType, String reason) {}

	/**
	 * The optional statistics hook of FR-036, mirroring {@code UdpSocket.Inspector} in {@code core-net}:
	 * an interface declared by the component, defaulting to none, that a later module implements to
	 * publish JMX statistics without this module depending on {@code boot-jmx}.
	 * <p>
	 * It carries no {@link ByteBuf} and no key material by construction — every parameter is a number,
	 * an encryption level or a state (SI-6). The same six events are logged at debug level under the
	 * qlog event vocabulary, so a deployment gets the identical picture from either seam (FR-034).
	 * <p>
	 * <b>Threading</b>: every callback runs on the connection's reactor thread, inside the operation
	 * that produced the event. An implementation that blocks blocks the reactor, and one that throws
	 * fails the operation — accumulate, never act.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9002#section-B">RFC 9002 §B — the variables these events report</a>
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/**
		 * qlog {@code transport:packet_sent}. Fired when the packet is committed to a datagram and
		 * recorded as in flight — the point RFC 9002 §A.5 counts it from — which is just before the
		 * datagram reaches {@link DatagramSink#send}.
		 */
		void onPacketSent(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes,
			boolean ackEliciting);

		/**
		 * qlog {@code transport:packet_received}. Fired only for a packet that authenticated and was
		 * <i>new</i> — a duplicate, a packet failing AEAD and one for a discarded level are all invisible
		 * here and visible in {@link #packetsDropped()}.
		 */
		void onPacketReceived(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes);

		/**
		 * qlog {@code recovery:packet_lost}. Fired once per packet declared lost by RFC 9002 §6.1's
		 * thresholds. A probe retransmission is <i>not</i> loss and is not reported here (RFC 9002 §6.2).
		 */
		void onPacketLost(
			QuicConnection connection, EncryptionLevel level, long packetNumber, int sizeInBytes);

		/**
		 * qlog {@code recovery:metrics_updated}. Fired on each RFC 9002 §5 RTT sample; the estimator is
		 * passed rather than its fields so a JMX implementation can record whichever it publishes.
		 */
		void onMetricsUpdated(QuicConnection connection, RttEstimator rtt);

		/**
		 * qlog {@code recovery:congestion_state_updated}. Fired only on an actual transition, never on a
		 * window change within one state.
		 */
		void onCongestionStateUpdated(
			QuicConnection connection, NewRenoCongestionController.State from,
			NewRenoCongestionController.State to);

		/** Fired on every {@link QuicConnectionState} transition, including the one into {@code CLOSED}. */
		void onStateTransition(QuicConnection connection, QuicConnectionState from, QuicConnectionState to);
	}

	private final Role role;
	private final DatagramSink sink;
	private final InetSocketAddress remoteAddress;
	private final QuicConnectionSettings settings;
	private final long version;

	private final QuicConnectionId localConnectionId;
	/** The client's first DCID: seeds the Initial keys on both sides (RFC 9001 §5.2). */
	private final QuicConnectionId originalDestinationConnectionId;
	private QuicConnectionId peerConnectionId;
	/** The SCID actually seen in the peer's long-header packets — what FR-008 validates against. */
	private QuicConnectionId observedPeerScid;
	/** RFC 9000 §7.2: a client adopts the server's chosen SCID from its first long-header packet, once. */
	private boolean peerScidAdopted;

	private final TlsEngine tls;
	private final EnumMap<EncryptionLevel, LevelKeys> keys = new EnumMap<>(EncryptionLevel.class);
	/**
	 * Keyed by packet number <b>space</b>, not by encryption level: there are three spaces for four
	 * levels, because 0-RTT and 1-RTT packets are numbered and acknowledged together (RFC 9000 §12.3).
	 * Reach one through {@link #space(EncryptionLevel)}.
	 */
	private final EnumMap<EncryptionLevel.Space, PacketNumberSpace> spaces = new EnumMap<>(EncryptionLevel.Space.class);
	private final EnumMap<EncryptionLevel, CryptoStreamAssembler> cryptoIn = new EnumMap<>(EncryptionLevel.class);
	private final EnumMap<EncryptionLevel, Long> cryptoOutOffset = new EnumMap<>(EncryptionLevel.class);
	private final SendQueue sendQueue;
	private final AmplificationBudget amplification;
	/** Packets whose level has no keys yet, held in arrival order up to the configured bound (FR-011). */
	private final List<ProtectedPacket> awaitingKeys = new ArrayList<>();

	/** RFC 9000 §8.1.2: the token from a Retry, echoed in every subsequent Initial. Empty when none. */
	private byte[] retryToken = NO_TOKEN;
	/** The SCID of the Retry that was processed, which the server must echo as {@code retry_source_connection_id}. */
	private @Nullable QuicConnectionId retrySourceConnectionId;

	private final SettablePromise<QuicConnection> establishPromise = new SettablePromise<>();
	private final @Nullable Runnable onClosed;
	/**
	 * Not {@code final} only because {@link Builder#withFrameHandlerFactory} assigns it in
	 * {@code doBuild()} — after the constructor has returned, so the factory sees a whole connection.
	 * Written exactly once, before the connection is reachable by anything else; read-only thereafter.
	 */
	private @Nullable QuicFrameHandler frameHandler;
	private final @Nullable Inspector inspector;
	private QuicConnectionState state = QuicConnectionState.IDLE;
	private @Nullable ScheduledRunnable handshakeDeadline;
	private @Nullable QuicTransportParameters peerTransportParameters;
	/**
	 * The peer's parameters as they were on the connection this one resumes (RFC 9000 §7.4.1), or
	 * {@code null} when this is not a resumption attempt. Client-only; a server remembers nothing.
	 */
	private final @Nullable QuicTransportParameters rememberedTransportParameters;
	private @Nullable String negotiatedAlpn;
	private boolean handshakeConfirmed;
	/**
	 * Latched from {@code TlsEngineResult.resumed()}: this handshake used a pre-shared key rather than
	 * a certificate. Bookkeeping only — the rule it feeds, RFC 9000 §7.4.1's non-reduction check, reads
	 * the result directly at the moment it completes.
	 */
	private boolean sessionResumed;
	/**
	 * Latched from {@code TlsEngineResult.earlyDataAccepted()}: a client's 0-RTT packets were accepted
	 * (client side, the server echoed {@code early_data}) or are being accepted (server side). Never
	 * revoked — RFC 8446 §4.2.10 takes the decision once.
	 */
	private boolean earlyDataAccepted;

	private final RttEstimator rtt = new RttEstimator();
	private final NewRenoCongestionController congestion;
	/**
	 * Set for the duration of one flush when RFC 9002 §7 permits exceeding the window: a probe, which
	 * must go out or the connection cannot recover, and the CONNECTION_CLOSE path.
	 */
	private boolean bypassCongestionWindow;
	private int ptoCount;
	/** RFC 9002 §6.2: one timer serves loss detection and probing; which one is armed depends on state. */
	private @Nullable ScheduledRunnable lossTimer;
	private @Nullable ScheduledRunnable ackTimer;
	/** Set when the 1-RTT space owes an ACK now rather than at the end of its {@code max_ack_delay}. */
	private boolean ackNowOneRtt;

	/** RFC 9000 §10.2: the closing or draining period, whichever this connection entered. */
	private @Nullable ScheduledRunnable closingTimer;
	private @Nullable ScheduledRunnable idleTimer;
	private @Nullable ScheduledRunnable keepAliveTimer;
	/** Retained for the whole closing period so RFC 9000 §10.2.1's re-sends have something to send. */
	private @Nullable ConnectionCloseFrame closingFrame;
	private long closeSentTime;
	private @Nullable PeerClose peerClose;
	/**
	 * RFC 9000 §10.1: the send side restarts the idle timer only for the <i>first</i> ack-eliciting
	 * packet after a receive. Without this flag a probe timeout — which sends an ack-eliciting PING —
	 * would restart the idle timer, and a connection to a vanished peer would probe and re-arm forever
	 * instead of timing out.
	 */
	private boolean ackElicitingSentSinceReceive;

	/**
	 * RFC 9221 DATAGRAM frames waiting for a packet, deliberately <b>not</b> in {@link #sendQueue}.
	 * <p>
	 * Three reasons, each of which would otherwise be a defect. RFC 9221 §3 bounds unreliable sends by a
	 * <i>count</i> while the send queue's bound is global and in bytes. {@code SendQueue.pollUpTo} leaves
	 * a frame that does not fit at the front and never looks past it, which for a DATAGRAM is exactly the
	 * indefinite hold FR-078 forbids — and it would head-of-line-block the stream frames behind it. And
	 * refusal at the bound must not be {@code INTERNAL_ERROR}: refusing an unreliable send is normal.
	 * <p>
	 * Not per level either, so the 0-RTT → 1-RTT re-levelling has nothing to move: the queue is drained
	 * at whatever {@link #applicationSendLevel()} says at flush time.
	 */
	private final ArrayDeque<DatagramFrame> outboundDatagrams = new ArrayDeque<>();

	private long datagramsSent;
	private long datagramsReceived;
	private long datagramFramesSent;
	private long datagramFramesReceived;
	private long datagramFramesDropped;
	private long datagramFramesRefused;
	private long packetsDropped;
	private long packetsLost;
	private long probesSent;
	private long closeResends;
	private long keepAlivesSent;
	private long probeRetransmits;

	/** The last state reported through {@link Inspector#onCongestionStateUpdated}, so only changes fire. */
	private NewRenoCongestionController.State reportedCongestionState;

	private QuicConnection(Builder builder) {
		super(builder.reactor);
		this.role = builder.role;
		this.onClosed = builder.onClosed;
		this.frameHandler = builder.frameHandler;
		this.inspector = builder.inspector;
		this.sink = builder.sink;
		this.remoteAddress = builder.remoteAddress;
		this.settings = builder.settings;
		this.rememberedTransportParameters = builder.rememberedTransportParameters;
		this.version = QuicPackets.SUPPORTED_VERSION;

		SecureRandom random = builder.secureRandom;
		this.localConnectionId = builder.localConnectionId != null
			? builder.localConnectionId
			: QuicConnectionId.random(settings.connectionIdLength(), random);

		if (role == Role.CLIENT) {
			// RFC 9000 §7.2: the client's first DCID is unpredictable and at least 8 bytes, because it
			// is the only input to the Initial keys and therefore the only thing making them
			// path-specific rather than universally computable.
			this.originalDestinationConnectionId = builder.originalDestinationConnectionId != null
				? builder.originalDestinationConnectionId
				: QuicConnectionId.random(Math.max(8, settings.connectionIdLength()), random);
			this.peerConnectionId = originalDestinationConnectionId;
			this.observedPeerScid = originalDestinationConnectionId;
		} else {
			this.originalDestinationConnectionId = builder.originalDestinationConnectionId;
			this.peerConnectionId = builder.peerConnectionId;
			this.observedPeerScid = builder.peerConnectionId;
			this.peerScidAdopted = true;
		}

		this.sendQueue = new SendQueue(settings.maxSendQueueBytes());
		this.congestion = NewRenoCongestionController.of(settings);
		this.reportedCongestionState = congestion.state();
		this.amplification = role == Role.SERVER
			? AmplificationBudget.forServer()
			: AmplificationBudget.validated();

		for (EncryptionLevel level : EncryptionLevel.values()) {
			// Research D-5 audit (a) verdict, keys: ZERO_RTT belongs. A 0-RTT packet is protected with
			// its own AEAD keys derived from the client early traffic secret (RFC 9001 §4.1.4), so the
			// level needs a slot of its own; it simply stays uninstalled until an engine fills it.
			keys.put(level, new LevelKeys(level));
			// Research D-5 audit (b) verdict, CRYPTO reassembly and CRYPTO send offset: ZERO_RTT does
			// NOT belong. RFC 9000 §12.5 forbids a CRYPTO frame in a 0-RTT packet, so the level has no
			// CRYPTO stream at all and a slot for it could only ever stay empty.
			if (level.hasCryptoStream()) {
				cryptoIn.put(level, new CryptoStreamAssembler(settings.maxCryptoBufferBytes()));
				cryptoOutOffset.put(level, 0L);
			}
		}
		// Research D-5 audit (a) verdict, packet number spaces: keyed by SPACE, not by level. 0-RTT and
		// 1-RTT packets share the Application space (RFC 9000 §12.3); a fourth space would number a
		// 0-RTT and a 1-RTT packet identically and leave their acknowledgements indistinguishable. The
		// Application space is built at ONE_RTT because that is the level the recovery code recognises
		// it by (LossDetector reads PacketNumberSpace.level()).
		spaces.put(EncryptionLevel.Space.INITIAL,
			new PacketNumberSpace(EncryptionLevel.INITIAL, settings.maxAckRanges()));
		spaces.put(EncryptionLevel.Space.HANDSHAKE,
			new PacketNumberSpace(EncryptionLevel.HANDSHAKE, settings.maxAckRanges()));
		spaces.put(EncryptionLevel.Space.APPLICATION,
			new PacketNumberSpace(EncryptionLevel.ONE_RTT, settings.maxAckRanges()));

		InitialKeys initial = QuicKeys.initial(originalDestinationConnectionId);
		keys.get(EncryptionLevel.INITIAL).install(
			role == Role.CLIENT ? initial.client() : initial.server(),
			role == Role.CLIENT ? initial.server() : initial.client());

		this.tls = builder.engineFactory.create(TransportParameterValidation.local(
			settings, localConnectionId, role == Role.SERVER ? originalDestinationConnectionId : null));
	}

	public static Builder builder(
		Reactor reactor, Role role, DatagramSink sink, InetSocketAddress remoteAddress,
		TlsEngineFactory engineFactory
	) {
		return new QuicConnection.Builder(reactor, role, sink, remoteAddress, engineFactory);
	}

	public static final class Builder extends AbstractBuilder<Builder, QuicConnection> {
		private final Reactor reactor;
		private final Role role;
		private final DatagramSink sink;
		private final InetSocketAddress remoteAddress;
		private final TlsEngineFactory engineFactory;

		private QuicConnectionSettings settings = QuicConnectionSettings.create();
		private SecureRandom secureRandom = new SecureRandom();
		private @Nullable QuicConnectionId localConnectionId;
		private @Nullable QuicConnectionId peerConnectionId;
		private @Nullable QuicConnectionId originalDestinationConnectionId;
		private @Nullable Runnable onClosed;
		private @Nullable QuicFrameHandler frameHandler;
		private @Nullable Function<QuicConnection, QuicFrameHandler> frameHandlerFactory;
		private @Nullable Inspector inspector;
		private @Nullable QuicTransportParameters rememberedTransportParameters;

		private Builder(
			Reactor reactor, Role role, DatagramSink sink, InetSocketAddress remoteAddress,
			TlsEngineFactory engineFactory
		) {
			this.reactor = reactor;
			this.role = role;
			this.sink = sink;
			this.remoteAddress = remoteAddress;
			this.engineFactory = engineFactory;
		}

		public Builder withSettings(QuicConnectionSettings settings) {
			checkNotBuilt(this);
			this.settings = settings;
			return this;
		}

		/** Inject a seeded instance to make connection-ID generation reproducible (FR-007, SC-006). */
		public Builder withSecureRandom(SecureRandom secureRandom) {
			checkNotBuilt(this);
			this.secureRandom = secureRandom;
			return this;
		}

		public Builder withLocalConnectionId(QuicConnectionId localConnectionId) {
			checkNotBuilt(this);
			this.localConnectionId = localConnectionId;
			return this;
		}

		/** Required on a server: the SCID from the client's first Initial. */
		public Builder withPeerConnectionId(QuicConnectionId peerConnectionId) {
			checkNotBuilt(this);
			this.peerConnectionId = peerConnectionId;
			return this;
		}

		/** Required on a server: the DCID from the client's first Initial, which seeds the Initial keys. */
		public Builder withOriginalDestinationConnectionId(QuicConnectionId originalDestinationConnectionId) {
			checkNotBuilt(this);
			this.originalDestinationConnectionId = originalDestinationConnectionId;
			return this;
		}

		/**
		 * Run exactly once when the connection reaches {@code CLOSED}, whatever ended it. The endpoint
		 * uses this to unregister a connection whose last act was a timer, not a datagram.
		 */
		public Builder withOnClosed(Runnable onClosed) {
			checkNotBuilt(this);
			this.onClosed = onClosed;
			return this;
		}

		/**
		 * Registers the layer above this one (FR-037). Without one, a frame the transport does not own is
		 * a protocol violation rather than something to ignore — we advertise zero streams and no
		 * DATAGRAM support, so a peer sending application data has exceeded a limit it was told.
		 */
		public Builder withFrameHandler(QuicFrameHandler frameHandler) {
			checkNotBuilt(this);
			this.frameHandler = frameHandler;
			return this;
		}

		/**
		 * Registers a frame handler built <i>from</i> the connection itself (FR-038), so the handler can
		 * hold its connection in a final field instead of a mutable back-reference patched in afterwards.
		 * <p>
		 * The factory runs inside {@link #build()}, exactly once, after the constructor has returned and
		 * before the connection is yielded — so every field the handler could touch is already assigned,
		 * and the factory may call back into the connection it is given. Returning {@code null} is the
		 * same as never registering a handler at all.
		 * <p>
		 * <b>Why this exists.</b> Every {@link QuicFrameHandler} method takes the connection as a
		 * parameter, which is enough for a handler that only ever reacts to a frame. A layer that also
		 * <i>originates</i> traffic — a stream manager calling {@link #enqueueFrame} from an application
		 * write — needs its connection outside any callback, and cannot be constructed before it exists.
		 * <p>
		 * Mutually exclusive with {@link #withFrameHandler}: setting both is an {@link IllegalStateException}
		 * at build time. A factory that throws propagates out of {@code build()} with nothing registered.
		 */
		public Builder withFrameHandlerFactory(Function<QuicConnection, QuicFrameHandler> frameHandlerFactory) {
			checkNotBuilt(this);
			this.frameHandlerFactory = frameHandlerFactory;
			return this;
		}

		/**
		 * Registers the optional statistics hook of FR-036. Without one the connection still keeps every
		 * counter and still logs every qlog event; the hook only adds a push seam for a module that
		 * publishes them.
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			this.inspector = inspector;
			return this;
		}

		/**
		 * The peer transport parameters remembered from the session this connection is resuming
		 * (RFC 9000 §7.4.1, spec FR-051b) — what bounds every byte sent in 0-RTT, and what the server's
		 * new parameters are checked against for a forbidden reduction.
		 * <p>
		 * They live here rather than in the TLS config because bounding 0-RTT data against them is the
		 * connection layer's job: it owns the flow-control state, and ADR-013 puts the rule where the
		 * state is. {@code QuicConnection.TlsEngineFactory}'s signature is untouched (research D-6).
		 * <p>
		 * Client-only. A server is <i>told</i> its peer's parameters by the handshake and remembers
		 * nothing across connections, so supplying these on a server is a wiring mistake rather than a
		 * value that would simply be ignored — refused at {@code build()}.
		 * <p>
		 * Supplying them does not by itself send anything in 0-RTT: that needs 0-RTT keys, which need a
		 * ticket the TLS client config was given and a server that accepted it.
		 */
		public Builder withRememberedTransportParameters(QuicTransportParameters rememberedTransportParameters) {
			checkNotBuilt(this);
			this.rememberedTransportParameters = rememberedTransportParameters;
			return this;
		}

		@Override
		protected QuicConnection doBuild() {
			if (frameHandler != null && frameHandlerFactory != null) {
				// Checked here rather than in whichever setter ran second: a builder is order-independent
				// everywhere else in the platform, and silently letting one win would decide which handler a
				// connection routes to by the order two lines happened to be written in.
				throw new IllegalStateException(
					"withFrameHandler(...) and withFrameHandlerFactory(...) are mutually exclusive: " +
						"a connection has exactly one frame handler");
			}
			if (role == Role.SERVER) {
				if (rememberedTransportParameters != null) {
					throw new IllegalStateException(
						"withRememberedTransportParameters(...) is client-only: a server is told its peer's " +
							"parameters by the handshake and remembers nothing across connections (RFC 9000 §7.4.1)");
				}
				if (peerConnectionId == null) {
					throw new IllegalStateException(
						"A server connection needs the client's source connection ID: withPeerConnectionId(...)");
				}
				if (originalDestinationConnectionId == null) {
					throw new IllegalStateException(
						"A server connection needs the client's first destination connection ID (it seeds the " +
							"Initial keys): withOriginalDestinationConnectionId(...)");
				}
			}
			QuicConnection connection = new QuicConnection(this);
			if (frameHandlerFactory != null) {
				// After the constructor, never inside it: a `this` handed out mid-construction would let the
				// factory read fields that are still null. A factory that throws leaves this connection
				// unregistered and unreachable — it was never started, so it holds no timer and no buffer.
				connection.frameHandler = frameHandlerFactory.apply(connection);
			}
			return connection;
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Starts the handshake and returns the promise that completes when it does (FR-001, FR-024).
	 * <p>
	 * On a client this emits the ClientHello; on a server it only arms the deadline, since a server's
	 * first output is a reaction to the client's Initial. Idempotent.
	 */
	public Promise<QuicConnection> start() {
		checkInReactorThread(this);
		if (state != QuicConnectionState.IDLE) {
			return establishPromise;
		}
		transitionTo(QuicConnectionState.HANDSHAKING);
		handshakeDeadline = reactor.delay(settings.handshakeTimeoutMillis(), () -> {
			handshakeDeadline = null;
			if (state == QuicConnectionState.HANDSHAKING) {
				// Not a wire error: there may be no keys to send a CONNECTION_CLOSE under, and the peer
				// may not exist at all. FR-024 asks only that the promise fails and nothing is left over.
				abandon(new QuicTransportException(QuicTransportErrors.NO_ERROR,
					"Handshake did not complete within " + settings.handshakeTimeoutMillis() + " ms"));
			}
		});
		armIdleTimer();
		if (role == Role.CLIENT) {
			// The engine emits the ClientHello from state START; the input is empty because there is
			// nothing from the peer yet (FR-004a).
			try {
				feedTls(EncryptionLevel.INITIAL, ByteBufPool.allocate(1));
			} catch (QuicTransportException e) {
				closeWith(e);
				return establishPromise;
			}
			flush();
		}
		return establishPromise;
	}

	/** The handshake promise, whether or not {@link #start()} has been called yet. */
	public Promise<QuicConnection> whenEstablished() {
		checkInReactorThread(this);
		return establishPromise;
	}

	/**
	 * Closes with {@code NO_ERROR}, telling the peer if there are keys to tell it under, then holds the
	 * RFC 9000 §10.2.1 closing period. Idempotent — a second call emits nothing further.
	 */
	public void close() {
		checkInReactorThread(this);
		closeWith(new QuicTransportException(QuicTransportErrors.NO_ERROR, "Closed locally"));
	}

	/**
	 * Closes and skips the RFC 9000 §10.2 closing period, reaching {@code CLOSED} at once — the peer
	 * still gets one CONNECTION_CLOSE, but no further one.
	 * <p>
	 * For the case where a closing period cannot serve its purpose: the socket is going away, so a
	 * peer's retransmission can neither arrive nor be answered, and holding buffers and a map entry for
	 * three probe timeouts would only outlive the endpoint that owns them. {@link QuicEndpoint#close()}
	 * is the caller this exists for.
	 */
	public void closeNow() {
		checkInReactorThread(this);
		close();
		release();
		markClosed();
	}

	// ---------------------------------------------------------------- receive path (T028)

	/**
	 * Processes one received datagram: every packet coalesced into it, in order (RFC 9000 §12.2).
	 * <p>
	 * <b>Takes ownership</b> of {@code datagram} and recycles it on every path — including the paths
	 * that discard it unread, which is what a draining or closing connection does (RFC 9000 §10.2).
	 * <p>
	 * A packet that cannot be authenticated ends only itself, never the datagram: see the package's
	 * leniency classification for which inputs are tolerated, dropped and rejected.
	 */
	public void onDatagram(ByteBuf datagram) {
		checkInReactorThread(this);
		if (state == QuicConnectionState.CLOSED || state == QuicConnectionState.DRAINING) {
			// RFC 9000 §10.2.2: a draining connection sends nothing, so there is nothing to gain from
			// even looking at this.
			datagram.recycle();
			return;
		}
		datagramsReceived++;
		amplification.onDatagramReceived(datagram.readRemaining());
		if (state == QuicConnectionState.CLOSING) {
			// RFC 9000 §10.2.1: the packet is not processed — its keys may already be gone and its
			// frames can no longer matter — but it does earn a repeat of why we closed.
			datagram.recycle();
			maybeResendConnectionClose();
			return;
		}
		// RFC 9000 §10.1: any datagram from the peer, decryptable or not, is evidence the path is live.
		ackElicitingSentSinceReceive = false;
		armIdleTimer();

		List<ProtectedPacket> packets;
		try {
			packets = CoalescedPackets.split(datagram, localConnectionId.length(), version);
		} catch (MalformedDataException e) {
			// Nothing in the datagram was locatable. It may not even have been ours — dropping it is
			// the only safe response, and it must never become a connection error (RFC 9000 §5.2).
			packetsDropped++;
			return;
		} finally {
			// split() produced retained slices; the datagram itself is ours to release either way.
			datagram.recycle();
		}

		int i = 0;
		try {
			for (; i < packets.size(); i++) {
				// processPacket consumes its packet's buffer on every path.
				processPacket(packets.get(i));
			}
		} catch (QuicTransportException e) {
			for (int j = i + 1; j < packets.size(); j++) {
				packets.get(j).bytes().recycle();
			}
			closeWith(e);
			return;
		}
		flush();
	}

	/** Consumes {@code packet}'s buffer on every path. */
	private void processPacket(ProtectedPacket packet) throws QuicTransportException {
		EncryptionLevel level = packet.level();
		if (level == null) {
			switch (packet.kind()) {
				case VERSION_NEGOTIATION -> onVersionNegotiation(packet);
				case RETRY -> onRetry(packet);
				default -> {
					packetsDropped++;
					packet.bytes().recycle();
				}
			}
			return;
		}
		if (level == EncryptionLevel.ZERO_RTT && role == Role.CLIENT) {
			// Only a client sends 0-RTT, so a client has no 0-RTT receive keys and never will. Without
			// this the packet would go to awaitingKeys and sit there until the bound evicted it — a queue
			// a peer could fill with packets nothing can ever open.
			packetsDropped++;
			packet.bytes().recycle();
			return;
		}
		if (packet.kind() != Kind.ONE_RTT && role == Role.CLIENT && !peerScidAdopted) {
			QuicConnectionId serverScid = packet.sourceConnectionId();
			if (serverScid != null) {
				// RFC 9000 §7.2: from here on we address the server by the connection ID it chose, and
				// this is the value FR-008 checks its initial_source_connection_id against.
				peerConnectionId = serverScid;
				observedPeerScid = serverScid;
				peerScidAdopted = true;
			}
		}

		LevelKeys levelKeys = keys.get(level);
		if (levelKeys.isDiscarded()) {
			// FR-006: a packet for a discarded level is not an error, it is a late retransmission.
			packetsDropped++;
			packet.bytes().recycle();
			return;
		}
		if (!levelKeys.acceptsReceive()) {
			// The receive direction specifically: a client's ZERO_RTT slot holds send keys only, and
			// reading isInstalled() here would have buffered every level whose other half is absent.
			bufferAwaitingKeys(packet);
			return;
		}
		openAndHandle(level, levelKeys, packet.bytes());
	}

	/**
	 * Whether nothing from the server has been processed yet — the window in which an unauthenticated
	 * Version Negotiation or Retry packet may be acted on at all (RFC 9000 §6.1, §17.2.5.2).
	 * <p>
	 * Three conditions, because each closes a different door. Still handshaking rules out an established
	 * connection; no Initial packet received rules out one already talking to a real server; and the
	 * Initial level not yet discarded rules out a <i>confirmed</i> connection, whose keys are gone and
	 * whose {@code largestReceived} therefore no longer tells us anything.
	 */
	private boolean isBeforeAnyServerPacket() {
		return state == QuicConnectionState.HANDSHAKING
			&& !keys.get(EncryptionLevel.INITIAL).isDiscarded()
			&& space(EncryptionLevel.INITIAL).largestReceived() == PacketNumberSpace.NONE;
	}

	// ---------------------------------------------------------------- Version Negotiation (T070)

	/**
	 * RFC 9000 §6: a Version Negotiation packet says the server speaks none of the versions we offered.
	 * <b>Consumes</b> {@code packet}'s buffer.
	 * <p>
	 * There is nothing to negotiate here: this implementation speaks exactly QUIC v1, so the only honest
	 * outcome is a typed failure naming what the server did offer, so the caller can say why rather than
	 * time out.
	 * <p>
	 * Two things make this packet dangerous and both are checked. It is <b>unauthenticated</b> — anyone
	 * who can see an Initial can forge one — so it is accepted only from a server, only while we are
	 * still at the Initial level, and only if it does <i>not</i> list our own version. That last check is
	 * RFC 9000 §6.2's: a genuine server would never send one, so its presence proves the packet is
	 * forged or corrupt, and acting on it would be a trivially mounted downgrade.
	 */
	private void onVersionNegotiation(ProtectedPacket packet) {
		ByteBuf bytes = packet.bytes();
		try {
			if (role != Role.CLIENT || !isBeforeAnyServerPacket()) {
				packetsDropped++;
				return;
			}
			int[] offered;
			try {
				// Version Negotiation is never packet-protected, so the codec's own parser applies.
				QuicPacket parsed = QuicPackets.parse(bytes, localConnectionId.length());
				if (!(parsed instanceof VersionNegotiationPacket vn)) {
					packetsDropped++;
					return;
				}
				offered = vn.supportedVersions();
			} catch (MalformedDataException e) {
				packetsDropped++;
				return;
			}
			for (int offeredVersion : offered) {
				if (Integer.toUnsignedLong(offeredVersion) == version) {
					// RFC 9000 §6.2: our version is in the list, so the packet cannot be genuine.
					packetsDropped++;
					logger.debug("Discarding a Version Negotiation packet that lists our own version");
					return;
				}
			}
			StringBuilder versions = new StringBuilder();
			for (int i = 0; i < offered.length; i++) {
				if (i > 0) versions.append(", ");
				versions.append("0x").append(Integer.toHexString(offered[i]));
			}
			abandon(new QuicTransportException(QuicTransportErrors.VERSION_NEGOTIATION_ERROR,
				"The server does not support QUIC version 0x" + Long.toHexString(version) +
				"; it offered [" + versions + ']'));
		} finally {
			bytes.recycle();
		}
	}

	// ---------------------------------------------------------------- Retry (T072)

	/**
	 * RFC 9000 §17.2.5: a Retry carries a token the client must echo, proving it can receive at the
	 * address it claims. <b>Consumes</b> {@code packet}'s buffer.
	 * <p>
	 * The integrity tag is the only thing making this safe: it is computed over the packet keyed by the
	 * <i>original</i> destination connection ID, which only an endpoint on the path to the real server
	 * could know. A tag failure is therefore an off-path forgery attempt, and RFC 9000 §17.2.5.2 says to
	 * discard it silently — treating it as a connection error would hand any observer a way to kill
	 * connections.
	 * <p>
	 * <b>This implementation never sends a Retry</b> (FR-030); only the client half exists.
	 */
	private void onRetry(ProtectedPacket packet) {
		ByteBuf bytes = packet.bytes();
		try {
			if (role != Role.CLIENT
				|| retrySourceConnectionId != null   // RFC 9000 §17.2.5.2: at most one Retry, ever
				|| !isBeforeAnyServerPacket()) {
				packetsDropped++;
				return;
			}
			// The tag covers everything before it, so the pseudo-header is the packet minus its last 16
			// bytes — read by index, because the codec parse below consumes the buffer.
			int length = bytes.readRemaining();
			if (length <= RetryPacket.INTEGRITY_TAG_LENGTH) {
				packetsDropped++;
				return;
			}
			byte[] headerAndToken = new byte[length - RetryPacket.INTEGRITY_TAG_LENGTH];
			System.arraycopy(bytes.array(), bytes.head(), headerAndToken, 0, headerAndToken.length);

			RetryPacket retry;
			try {
				QuicPacket parsed = QuicPackets.parse(bytes, localConnectionId.length());
				if (!(parsed instanceof RetryPacket typed)) {
					packetsDropped++;
					return;
				}
				retry = typed;
			} catch (MalformedDataException e) {
				packetsDropped++;
				return;
			}
			if (!RetryIntegrityTag.verify(originalDestinationConnectionId, headerAndToken,
				retry.retryIntegrityTag())) {
				packetsDropped++;
				logger.debug("Discarding a Retry packet whose integrity tag does not verify");
				return;
			}
			QuicConnectionId serverScid = retry.sourceConnectionId;
			if (serverScid == null || serverScid.equals(originalDestinationConnectionId)) {
				// RFC 9000 §17.2.5.2: a Retry whose SCID equals the DCID we sent changes nothing and would
				// leave the client re-deriving the same keys forever.
				packetsDropped++;
				return;
			}
			byte[] token = retry.retryToken();
			if (settings.maxDatagramSize()
				- initialPacketOverhead(token.length, serverScid.length(), 4) < MIN_INITIAL_PAYLOAD) {
				// RFC 9000 §17.2.5 bounds neither the token nor the Retry packet, so a server — or anyone
				// who can forge one, since the integrity tag is computed from a published key — can send a
				// token that leaves no room for the ClientHello it is supposed to accompany. Every
				// subsequent Initial would then be built with a non-positive allowance and never sent, and
				// the handshake would stall until its deadline. Ignoring the Retry is explicitly permitted
				// (§17.2.5.2) and leaves the client retransmitting its original Initial.
				packetsDropped++;
				logger.debug("Discarding a Retry whose {}-byte token leaves no room for an Initial packet",
					token.length);
				return;
			}
			restartAfterRetry(serverScid, token);
		} finally {
			bytes.recycle();
		}
	}

	/**
	 * Applies a verified Retry: adopt the server's connection ID, re-derive the Initial keys from it, and
	 * re-send the ClientHello with the token (RFC 9000 §17.2.5.3).
	 * <p>
	 * <b>The packet number is not reset</b> — RFC 9000 §17.2.5.3 forbids it, and doing so would repeat an
	 * AEAD nonce under the new keys' predecessor. The ClientHello is recovered from the Initial packets
	 * still unacknowledged rather than regenerated: re-driving the TLS engine would produce a
	 * <i>different</i> ClientHello, and the transcript hash the handshake is built on would no longer
	 * match what the server sees.
	 */
	private void restartAfterRetry(QuicConnectionId serverScid, byte[] token) {
		logger.debug("Processing a Retry: adopting connection ID {} and a {}-byte token",
			serverScid, token.length);
		this.retrySourceConnectionId = serverScid;
		this.retryToken = token;
		this.peerConnectionId = serverScid;
		this.observedPeerScid = serverScid;
		this.peerScidAdopted = true;

		// RFC 9001 §5.2: after a Retry the Initial secret is re-derived from the Retry's Source Connection
		// ID, which becomes the destination of everything the client sends next. The *original* ID is kept
		// untouched because RFC 9000 §7.3 still validates
		// original_destination_connection_id against it.
		InitialKeys rederived = QuicKeys.initial(serverScid);
		keys.get(EncryptionLevel.INITIAL).install(rederived.client(), rederived.server());

		PacketNumberSpace initial = space(EncryptionLevel.INITIAL);
		List<SentPacket> outstanding = new ArrayList<>(initial.sentPackets().values());
		outstanding.sort((a, b) -> Long.compare(a.packetNumber, b.packetNumber));
		try {
			for (SentPacket sent : outstanding) {
				SentPacket removed = initial.onPacketLost(sent.packetNumber);
				if (removed == null) continue;
				if (removed.inFlight) {
					congestion.onPacketsLost(removed.sizeInBytes);
				}
				requeueLost(removed);
			}
		} catch (QuicTransportException e) {
			closeWith(e);
			return;
		}
		// The server's Retry proves it exists at this address, so the anti-amplification budget the client
		// keeps for itself is irrelevant; what matters is that the new Initial is padded, which the send
		// path does for every client Initial.
		flush();
	}

	/**
	 * Holds a packet whose level has no keys yet (FR-011). Over the bound, the <i>oldest</i> is
	 * dropped: a newer packet is the one more likely to still matter once keys arrive.
	 */
	private void bufferAwaitingKeys(ProtectedPacket packet) {
		logger.debug("{} buffering a {} packet awaiting keys ({} held)", role, packet.kind(), awaitingKeys.size());
		if (awaitingKeys.size() >= settings.maxBufferedDatagramsAwaitingKeys()) {
			packetsDropped++;
			awaitingKeys.remove(0).bytes().recycle();
		}
		awaitingKeys.add(packet);
	}

	/** Replays whatever was waiting for {@code level}'s keys, in arrival order. */
	private void drainAwaitingKeys(EncryptionLevel level) throws QuicTransportException {
		if (awaitingKeys.isEmpty()) return;
		List<ProtectedPacket> ready = new ArrayList<>();
		for (int i = awaitingKeys.size() - 1; i >= 0; i--) {
			if (awaitingKeys.get(i).level() == level) {
				ready.add(0, awaitingKeys.remove(i));
			}
		}
		int i = 0;
		try {
			for (; i < ready.size(); i++) {
				processPacket(ready.get(i));
			}
		} catch (QuicTransportException e) {
			for (int j = i + 1; j < ready.size(); j++) {
				ready.get(j).bytes().recycle();
			}
			throw e;
		}
	}

	private void dropAwaitingKeys(EncryptionLevel level) {
		for (int i = awaitingKeys.size() - 1; i >= 0; i--) {
			if (awaitingKeys.get(i).level() == level) {
				packetsDropped++;
				awaitingKeys.remove(i).bytes().recycle();
			}
		}
	}

	/** Consumes {@code bytes} on every path — {@link QuicPacketProtection#open} guarantees that much. */
	private void openAndHandle(EncryptionLevel level, LevelKeys levelKeys, ByteBuf bytes)
		throws QuicTransportException {
		PacketNumberSpace space = space(level);
		// Captured before open(), which consumes the buffer: the protected size is what a qlog reader
		// and a congestion controller both mean by a packet's size.
		int sizeInBytes = bytes.readRemaining();
		QuicPacketProtection.OpenResult opened;
		try {
			opened = QuicPacketProtection.open(
				levelKeys.receiveKeys(), space.largestReceived(), localConnectionId.length(), bytes);
		} catch (QuicDecryptionException e) {
			packetsDropped++;
			// FR-011: one packet failing AEAD says nothing about the next one in the same datagram, so
			// this returns rather than throws. The integrity-limit counter is the only thing that
			// escalates (RFC 9001 §6.6).
			levelKeys.onDecryptionFailed();
			return;
		} catch (MalformedDataException e) {
			// An envelope we could locate but not parse, still unauthenticated: drop it silently.
			packetsDropped++;
			return;
		}
		levelKeys.onDecryptionSucceeded();

		List<QuicFrame> frames;
		try {
			frames = readFrames(opened.payload);
		} catch (MalformedDataException e) {
			// FR-004a: past AEAD the bytes are authenticated, so a parse failure is the peer's protocol
			// error rather than noise.
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"Could not parse the frames of a " + level + " packet: " + e.getMessage());
		} finally {
			opened.payload.recycle();
		}

		try {
			FrameTypeRules.validateReceived(frames, level, role == Role.CLIENT);
			boolean ackEliciting = isAckEliciting(frames);
			if (!space.onPacketReceived(opened.packetNumber, reactor.currentTimeMillis(), ackEliciting)) {
				return;
			}
			onPacketReceivedEvent(level, opened.packetNumber, sizeInBytes);
			if (level.packetNumberSpace() == EncryptionLevel.Space.APPLICATION && ackEliciting) {
				// By space, not by level: a 0-RTT packet is acknowledged in the Application space like a
				// 1-RTT one, and keying this on ONE_RTT alone would leave a lone 0-RTT packet unscheduled
				// for acknowledgement — the client would sit out a probe timeout for nothing.
				armAckTimer(space);
			}
			if (level == EncryptionLevel.HANDSHAKE) {
				// RFC 9001 §4.9.1 / FR-006: a Handshake packet proves the peer has Handshake keys, so
				// the Initial level can never be needed again.
				discardLevel(EncryptionLevel.INITIAL);
				// RFC 9000 §8.1: a Handshake packet from the client is address validation, which lifts
				// the server's amplification limit.
				amplification.setValidated();
			}
			for (QuicFrame frame : frames) {
				handleFrame(level, frame);
			}
		} finally {
			recycleAll(frames);
		}
	}

	private static List<QuicFrame> readFrames(ByteBuf payload)
		throws MalformedDataException {
		List<QuicFrame> frames = new ArrayList<>();
		try {
			while (payload.readRemaining() > 0) {
				frames.add(QuicFrames.read(payload));
			}
		} catch (MalformedDataException | RuntimeException e) {
			recycleAll(frames);
			throw e;
		}
		return frames;
	}

	private void handleFrame(EncryptionLevel level, QuicFrame frame) throws QuicTransportException {
		if (frame instanceof CryptoFrame crypto) {
			// slice() rather than the payload itself: the assembler takes ownership of what it is
			// given, while `frames` still owns the original for its own recycling sweep.
			ByteBuf assembled = cryptoIn.get(level).add(crypto.offset, crypto.payload.slice());
			if (assembled != null) {
				feedTls(level, assembled);
			}
			return;
		}
		if (frame instanceof AckFrame ack) {
			processAck(level, ack);
			return;
		}
		if (frame instanceof HandshakeDoneFrame) {
			// FR-005: the client confirms the handshake here. FrameTypeRules has already established
			// that only a server can have sent this.
			confirmHandshake();
			return;
		}
		if (frame instanceof ConnectionCloseFrame close) {
			onPeerClose(close);
			return;
		}
		if (frame instanceof PaddingFrame || frame instanceof PingFrame) {
			// Nothing beyond the ack-eliciting accounting already done.
			return;
		}
		if (frame instanceof DatagramFrame datagram) {
			handleDatagramFrame(level, datagram);
			return;
		}
		if (isToleratedTransportFrame(frame)) {
			// Frames that concern paths and connection IDs we do not use. RFC 9000 §12.4 permits ignoring
			// them, and a real peer sends several of them unprompted — NEW_CONNECTION_ID in particular — so
			// treating them as violations would break interoperability rather than enforce anything.
			return;
		}
		if (frameHandler == null && isStreamCreditFrame(frame)) {
			// FR-039: with no layer above, this connection is exactly what it was before feature 04, and
			// what it was before feature 04 is an endpoint that ignored these five. A conforming peer sends
			// them unprompted (RFC 9000 §12.4) whether or not either side ever opens a stream, so closing on
			// one would be a regression against a peer that did nothing wrong.
			return;
		}
		routeToHandler(level, frame);
	}

	/**
	 * One received RFC 9221 DATAGRAM frame, classified totally and in this order (FR-074, FR-077).
	 * <p>
	 * The "advertised nothing" test comes <b>first</b>: a limit of 0 is RFC 9221 §3's encoding of "not
	 * supported", so measuring against it would report every frame as a size problem when the real one is
	 * that the peer sent something this endpoint never offered to receive.
	 * <p>
	 * <b>The size is measured against the frame's minimal wire form</b>, {@code 1 + payload}, not against
	 * {@link DatagramFrame#encodedLength()}. RFC 9221 §3's parameter covers type, optional length field
	 * and payload, but a frame that arrived in the 0x30 form carries no length field and
	 * {@link DatagramFrame} does not record which form it arrived in — {@code encodedLength()} always
	 * reports the wider 0x31 form. Measuring with that would close the connection over a frame a
	 * conforming peer was entitled to send. The send path is the strict half of that asymmetry: it
	 * measures with {@code encodedLength()}, which there <i>is</i> exact, because {@code writeTo} always
	 * emits 0x31.
	 * <p>
	 * With no handler registered the frame is dropped and counted rather than treated as an error. This
	 * endpoint advertised support and then attached nothing to deliver to, which is its own configuration
	 * — the same asymmetry {@link #isStreamCreditFrame} already documents for FR-039.
	 * <p>
	 * The frame is <b>borrowed</b> throughout; {@code openAndHandle}'s sweep owns it.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-3">RFC 9221 §3 — max_datagram_frame_size</a>
	 */
	private void handleDatagramFrame(EncryptionLevel level, DatagramFrame datagram) throws QuicTransportException {
		long limit = settings.maxDatagramFrameSize();
		if (limit == 0) {
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"Received a DATAGRAM frame, but this endpoint advertised no max_datagram_frame_size");
		}
		long minimalWireSize =
			QuicVarInts.encodedLength(DatagramFrame.TYPE_WITHOUT_LENGTH) + datagram.payload.readRemaining();
		if (minimalWireSize > limit) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				"Received a DATAGRAM frame of " + minimalWireSize + " bytes, over the advertised " +
				"max_datagram_frame_size of " + limit);
		}
		if (frameHandler == null) {
			datagramFramesDropped++;
			return;
		}
		datagramFramesReceived++;
		routeToHandler(level, datagram);
	}

	/**
	 * Frames the transport neither acts on nor rejects, whatever is registered above it.
	 * <p>
	 * These concern facilities this feature does not implement but a conforming peer still uses (extra
	 * connection IDs, path validation). Nothing above the transport can act on them either, so they are
	 * dropped rather than routed.
	 * <p>
	 * See {@link #isStreamCreditFrame(QuicFrame)} for the five frames whose treatment instead
	 * <i>depends</i> on whether a handler is registered.
	 */
	private static boolean isToleratedTransportFrame(QuicFrame frame) {
		return frame instanceof NewConnectionIdFrame
			|| frame instanceof RetireConnectionIdFrame
			|| frame instanceof NewTokenFrame
			|| frame instanceof PathChallengeFrame
			|| frame instanceof PathResponseFrame;
	}

	/**
	 * The flow-control and stream-limit frames, whose treatment is the one thing a registered
	 * {@link QuicFrameHandler} changes about frame routing.
	 * <p>
	 * With a handler they are routed, because the credit they carry is only actionable there (feature 04's
	 * stream layer is what spends it). With no handler they join {@link #isToleratedTransportFrame}'s list
	 * and are dropped: nothing can spend the credit, and a peer that announced it has not violated
	 * anything. That asymmetry is FR-039 — attaching no layer must leave the connection behaving exactly
	 * as it did before this feature existed.
	 */
	private static boolean isStreamCreditFrame(QuicFrame frame) {
		return frame instanceof MaxDataFrame
			|| frame instanceof MaxStreamDataFrame
			|| frame instanceof MaxStreamsFrame
			|| frame instanceof DataBlockedFrame
			|| frame instanceof StreamsBlockedFrame;
	}

	/**
	 * Whether this frame is one the transport itself put on the wire, as opposed to one a
	 * {@link QuicFrameHandler} contributed.
	 * <p>
	 * This is what decides who is told when a sent frame is acknowledged or lost, and it is deliberately a
	 * classification of the frame rather than a flag carried on the packet: a datagram routinely mixes an
	 * ACK the transport added with data the handler queued, and a per-packet flag would either hand the
	 * handler an ACK it never sent or recycle data it is still waiting on.
	 */
	private static boolean isTransportOwnedFrame(QuicFrame frame) {
		return frame instanceof AckFrame
			|| frame instanceof CryptoFrame
			|| frame instanceof PingFrame
			|| frame instanceof PaddingFrame
			|| frame instanceof HandshakeDoneFrame
			|| frame instanceof ConnectionCloseFrame;
	}

	/**
	 * Hands a frame the transport does not own to the registered handler, or closes the connection if
	 * there is none (FR-037).
	 * <p>
	 * The frame is <b>borrowed</b>: {@code openAndHandle}'s sweep recycles it once this returns, which is
	 * the contract {@link QuicFrameHandler#onFrame} documents.
	 */
	private void routeToHandler(EncryptionLevel level, QuicFrame frame) throws QuicTransportException {
		if (frameHandler == null) {
			throw new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION,
				"Received a " + frame.getClass().getSimpleName() + " at " + level +
				", but no frame handler is registered and this connection advertises no streams");
		}
		try {
			frameHandler.onFrame(this, level, frame);
		} catch (QuicTransportException e) {
			// FR-038a: the handler's own code, because only the handler knows what the peer violated.
			throw e;
		} catch (RuntimeException e) {
			// The handler's bug, not the peer's. Its state is now unknown, so the connection cannot go on.
			logger.warn("The frame handler threw on a {} at {}", frame.getClass().getSimpleName(), level, e);
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"The frame handler failed: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * Runs a handler callback that has no way to report failure — everything on {@link QuicFrameHandler}
	 * except {@code onFrame}, which throws and is handled by {@link #routeToHandler}.
	 * <p>
	 * These are invoked from the middle of transport bookkeeping: acknowledgement processing, loss
	 * detection, the establishment transition, the final teardown. A {@link RuntimeException} escaping
	 * one of them would reach the eventloop's fatal error handler — which is process-wide, so a single
	 * buggy handler would take down every other connection on the endpoint — and would abandon the loop
	 * it escaped from with buffers half-transferred. It is contained here instead, and only the
	 * connection whose handler misbehaved is closed.
	 * <p>
	 * The close is deferred to the next reactor tick, deliberately: closing from inside an
	 * acknowledgement loop would free the very packet records that loop is iterating.
	 */
	private void notifyHandler(String callback, Runnable body) {
		try {
			body.run();
		} catch (RuntimeException e) {
			// The exception itself is logged, never surfaced: it is the handler's, and may name anything.
			logger.warn("{} the frame handler threw from {}; closing this connection", role, callback, e);
			if (state.isTerminating()) return;
			reactor.post(() -> closeWith(QuicTransportErrors.INTERNAL_ERROR,
				"The frame handler failed in " + callback));
		}
	}

	private static boolean isAckEliciting(List<QuicFrame> frames) {
		for (QuicFrame frame : frames) {
			if (!(frame instanceof PaddingFrame) && !(frame instanceof AckFrame)) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- ACK processing

	private void processAck(EncryptionLevel level, AckFrame ack) throws QuicTransportException {
		PacketNumberSpace space = space(level);
		space.onAckReceived(ack.largestAcked);
		long now = reactor.currentTimeMillis();

		// Captured before the ranges retire it: RFC 9002 §5.1 takes its RTT sample from the largest
		// newly-acknowledged packet, and only when that packet was ack-eliciting — any other packet's
		// send time is ambiguous, because an ACK-only packet may have been sitting unacknowledged.
		SentPacket largestPacket = space.sentPacket(ack.largestAcked);

		long largest = ack.largestAcked;
		long smallest = largest - ack.firstAckRange;
		if (smallest < 0) {
			throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
				(long) AckFrame.TYPE_WITHOUT_ECN, "ACK first range extends below packet number 0");
		}
		boolean ackedAckEliciting = ackRange(space, smallest, largest);

		long[] gaps = ack.gaps;
		long[] lengths = ack.rangeLengths;
		for (int i = 0; i < gaps.length; i++) {
			// RFC 9000 §19.3.1: both fields are encoded one less than their value, hence the -2.
			largest = smallest - gaps[i] - 2;
			smallest = largest - lengths[i];
			if (smallest < 0 || largest < 0) {
				throw new QuicTransportException(QuicTransportErrors.FRAME_ENCODING_ERROR,
					(long) AckFrame.TYPE_WITHOUT_ECN, "ACK range " + i + " extends below packet number 0");
			}
			ackedAckEliciting |= ackRange(space, smallest, largest);
		}

		if (largestPacket != null && largestPacket.ackEliciting) {
			rtt.onRttSample(largestPacket.sentTime, now, peerAckDelayMillis(ack.ackDelay),
				peerMaxAckDelayMillis(), handshakeConfirmed);
			onMetricsUpdatedEvent();
		}
		if (ackedAckEliciting) {
			// RFC 9002 §6.2.1: an acknowledgement of an ack-eliciting packet proves the path is working,
			// so the exponential probe backoff starts over.
			ptoCount = 0;
		}
		// The acknowledgements above are the one way out of RECOVERY, and detectAndRequeueLost reports
		// its own transitions — so both clusters are covered, each at the point it can change the state.
		reportCongestionState();
		detectAndRequeueLost(level, now);
	}


	/**
	 * Retires every packet in {@code [from, to]}. The loop is bounded by our own largest sent packet
	 * number, because {@code onAckReceived} has already rejected an ACK claiming more than that.
	 */
	private boolean ackRange(PacketNumberSpace space, long from, long to) {
		boolean ackEliciting = false;
		for (long pn = from; pn <= to; pn++) {
			SentPacket sent = space.onPacketAcked(pn);
			if (sent == null) continue;
			if (sent.inFlight) {
				congestion.onPacketAcked(sent.sizeInBytes, sent.sentTime);
			}
			ackEliciting |= sent.ackEliciting;
			for (QuicFrame frame : sent.frames) {
				if (frameHandler != null && !isTransportOwnedFrame(frame)) {
					// Ownership passes to the handler, which is why this is not also recycled here.
					notifyHandler("onFrameAcknowledged", () -> frameHandler.onFrameAcknowledged(this, frame));
				} else {
					Recyclers.recycle(frame);
				}
			}
		}
		return ackEliciting;
	}

	/**
	 * Runs loss detection over one space and re-queues what was lost (T053).
	 * <p>
	 * Loss is declared per space because the thresholds are per space: a packet number in the Handshake
	 * space says nothing about one in the Application Data space.
	 */
	private void detectAndRequeueLost(EncryptionLevel level, long now) throws QuicTransportException {
		LossDetector.Detection detection = LossDetector.detectLost(space(level), now, rtt);
		if (detection.lost().isEmpty()) return;

		long lostBytes = 0;
		long newestLostSentTime = 0;
		long oldestLostSentTime = Long.MAX_VALUE;
		boolean anyInFlight = false;
		for (SentPacket lost : detection.lost()) {
			packetsLost++;
			onPacketLostEvent(lost);
			if (lost.inFlight) {
				anyInFlight = true;
				lostBytes += lost.sizeInBytes;
				newestLostSentTime = Math.max(newestLostSentTime, lost.sentTime);
				oldestLostSentTime = Math.min(oldestLostSentTime, lost.sentTime);
			}
			requeueLost(lost);
		}
		if (!anyInFlight) return;

		congestion.onPacketsLost(lostBytes);
		if (isPersistentCongestion(oldestLostSentTime, newestLostSentTime)) {
			logger.debug("{} persistent congestion across {} ms", role, newestLostSentTime - oldestLostSentTime);
			congestion.onPersistentCongestion();
		} else {
			congestion.onCongestionEvent(newestLostSentTime, now);
		}
		reportCongestionState();
	}

	/**
	 * RFC 9002 §7.6: whether the lost packets span longer than three probe timeouts' worth of time, which
	 * is the evidence that the path stopped delivering entirely rather than merely dropped a packet.
	 * <p>
	 * The duration is computed from the <i>unbacked-off</i> probe timeout, deliberately: using the current
	 * backoff would make the threshold grow with each timeout, so a path that had already been probed
	 * several times could never qualify — which is precisely the path most likely to be in persistent
	 * congestion.
	 */
	private boolean isPersistentCongestion(long oldestLostSentTime, long newestLostSentTime) {
		if (!rtt.hasSample() || oldestLostSentTime == Long.MAX_VALUE) return false;
		long pto = rtt.ptoMillis(0, true, peerMaxAckDelayMillis());
		return newestLostSentTime - oldestLostSentTime
			> (long) LossDetector.PERSISTENT_CONGESTION_THRESHOLD * pto;
	}

	/**
	 * Puts a lost packet's retransmittable frames back at the front of their level's queue.
	 * <p>
	 * RFC 9002 §6.5: what is retransmitted is the <i>data</i>, not the packet. An ACK is never resent —
	 * a newer one supersedes it — and PADDING and PING carry nothing; resending them would consume
	 * congestion window to say something already stale. CRYPTO and HANDSHAKE_DONE must be resent,
	 * because nothing else will ever deliver them.
	 */
	private void requeueLost(SentPacket lost) throws QuicTransportException {
		LevelKeys levelKeys = keys.get(lost.level);
		List<QuicFrame> retransmit = new ArrayList<>();
		for (QuicFrame frame : lost.frames) {
			if (frameHandler != null && !isTransportOwnedFrame(frame)) {
				// The transport cannot know whether the handler's data still matters, so the decision — and
				// ownership of the frame — is the handler's (FR-038).
				notifyHandler("onFrameLost", () -> frameHandler.onFrameLost(this, frame));
				continue;
			}
			boolean retransmittable = levelKeys.acceptsSend()
				&& (frame instanceof CryptoFrame || frame instanceof HandshakeDoneFrame);
			if (retransmittable) {
				retransmit.add(frame);
			} else {
				Recyclers.recycle(frame);
			}
		}
		if (!retransmit.isEmpty()) {
			logger.debug("{} re-queueing {} frame(s) of lost {} packet {}",
				role, retransmit.size(), lost.level, lost.packetNumber);
			sendQueue.requeue(lost.level, retransmit, lost.handlerOwned);
		}
	}

	/**
	 * The ACK frame describing what {@code space} has received, or {@code null} when there is nothing
	 * to acknowledge.
	 * <p>
	 * {@code ackDelay} is 0: this phase acknowledges the handshake spaces immediately (T036), and
	 * {@code max_ack_delay} — which applies only to Application Data — arrives with the rest of the
	 * ACK scheduler in Phase 5 (T052).
	 */
	private static @Nullable AckFrame buildAck(PacketNumberSpace space, long now) {
		AckRanges received = space.received();
		if (received.isEmpty()) {
			return null;
		}
		// How long we sat on this acknowledgement, so the peer can subtract it from its RTT sample
		// (RFC 9002 §5.3). Measured from the largest received packet, which is the one being timed.
		long delayMillis = Math.max(0, now - space.largestReceivedTime());
		return AckFrame.withoutEcn(received.largest(), delayMillis * ACK_DELAY_UNITS_PER_MILLI,
			received.firstRangeLength(), received.gaps(), received.rangeLengths());
	}

	/**
	 * Whether {@code space} should put an ACK in the packet being built now.
	 * <p>
	 * The handshake spaces are acknowledged immediately (T036, FR-014): {@code max_ack_delay} applies
	 * only to Application Data (RFC 9000 §18.2), and delaying a handshake ACK would just make the peer
	 * probe. The 1-RTT space acknowledges every second ack-eliciting packet (RFC 9000 §13.2.2) or when
	 * its delay timer has expired, whichever comes first.
	 */
	private boolean shouldAck(PacketNumberSpace space, EncryptionLevel level) {
		if (space.ackElicitingReceivedSinceAck() == 0) return false;
		if (level.packetNumberSpace() != EncryptionLevel.Space.APPLICATION) return true;
		return ackNowOneRtt || space.ackElicitingReceivedSinceAck() >= ACK_ELICITING_THRESHOLD;
	}

	/**
	 * Whether a packet at {@code level} may carry an ACK frame — everything except {@code ZERO_RTT}
	 * (RFC 9000 §12.4, Table 3).
	 * <p>
	 * Checked <b>before</b> building the ACK rather than left to {@code validateForSending}, which
	 * would be an {@code INTERNAL_ERROR} closing the connection over a frame this layer chose to add.
	 */
	private static boolean carriesAcks(EncryptionLevel level) {
		return level != EncryptionLevel.ZERO_RTT;
	}

	/**
	 * The level an application frame is queued at right now: {@code ONE_RTT} once its keys exist,
	 * {@code ZERO_RTT} while a client is sending early data, {@code ONE_RTT} again as the resting
	 * answer so a frame queued before either exists waits for the level that always arrives.
	 * <p>
	 * The {@code role} test is not redundant with the key check, and that is deliberate. A server
	 * never sends a 0-RTT packet (spec FR-052, RFC 9001 §4.6.1); that is already true structurally,
	 * because {@code TlsKeys.ofClientOnly} leaves a server's 0-RTT <i>send</i> slot empty — but a
	 * structural guarantee is exactly the kind a later change to key installation removes silently, so
	 * the rule is also written down where it applies.
	 */
	private EncryptionLevel applicationSendLevel() {
		if (keys.get(EncryptionLevel.ONE_RTT).acceptsSend()) return EncryptionLevel.ONE_RTT;
		if (role == Role.CLIENT && keys.get(EncryptionLevel.ZERO_RTT).acceptsSend()) {
			return EncryptionLevel.ZERO_RTT;
		}
		return EncryptionLevel.ONE_RTT;
	}

	// ---------------------------------------------------------------- TLS driving (T030)

	/** Takes ownership of {@code cryptoBytes} — {@link TlsEngine#consume} recycles it on every path. */
	private void feedTls(EncryptionLevel level, ByteBuf cryptoBytes) throws QuicTransportException {
		TlsEngineResult result;
		try {
			result = tls.consume(level, cryptoBytes);
		} catch (TlsAlertException e) {
			throw cryptoErrorFor(e);
		} catch (TlsHelloRetryRequestException e) {
			throw helloRetryRequestErrorFor(e);
		} catch (TlsProtocolViolationException e) {
			// Ordered before the MalformedDataException arm it extends: a resumption bound broken by the
			// peer is an RFC 9000 §20.1 transport rule, not an RFC 9000 §18 parameter problem.
			throw protocolViolationErrorFor(e);
		} catch (MalformedDataException e) {
			throw transportParameterErrorFor(e);
		}
		applyTlsResult(result);
	}

	private void applyTlsResult(TlsEngineResult result) throws QuicTransportException {
		if (result.resumed()) sessionResumed = true;
		if (result.earlyDataAccepted()) earlyDataAccepted = true;
		// Installations come in firing order (Handshake, then 1-RTT) and must be applied before the
		// CRYPTO output that depends on them can be sent.
		for (KeyInstallation installation : result.keysToInstall()) {
			TlsKeys installed = installation.keys();
			keys.get(installation.level()).install(
				role == Role.CLIENT ? installed.clientKeys() : installed.serverKeys(),
				role == Role.CLIENT ? installed.serverKeys() : installed.clientKeys());
		}
		boolean earlyDataRejected = false;
		if (role == Role.CLIENT
			&& keys.get(EncryptionLevel.ONE_RTT).acceptsSend()
			&& !keys.get(EncryptionLevel.ZERO_RTT).isDiscarded()) {
			// Early data was *offered* exactly when 0-RTT send keys were installed, and refused exactly
			// when the EncryptedExtensions that decides it did not echo early_data (RFC 8446 §4.2.10).
			// Read before the discard on the next line takes the keys away — and sound at this point,
			// because EncryptedExtensions precedes the server Finished on the CRYPTO stream, so the
			// decision is always latched before the 1-RTT keys this branch is guarded by exist.
			earlyDataRejected = keys.get(EncryptionLevel.ZERO_RTT).acceptsSend() && !earlyDataAccepted;
			// RFC 9001 §4.9.3: a client discards its 0-RTT keys when it installs 1-RTT keys — not at the
			// ServerHello, and not later. Whatever it had queued for 0-RTT and never sent must still go
			// out, so it is re-levelled first; discardLevel would otherwise recycle it.
			//
			// Unconditionally, refusal included, and that is the load-bearing part: this queue holds the
			// frames of *every* stream opened while 0-RTT was the application send level, and only the
			// layer above knows which of them describe work it will re-create and which must simply be
			// carried to 1-RTT. Purging them here would leave the second kind with a hole no
			// retransmission fills. What a rejection discards is decided per stream, through
			// onEarlyDataRejected below and dropQueuedFrames.
			sendQueue.moveAll(EncryptionLevel.ZERO_RTT, EncryptionLevel.ONE_RTT);
			discardLevel(EncryptionLevel.ZERO_RTT);
			if (earlyDataRejected && frameHandler != null) {
				// Here, and not at the end of this method, because what the handler discards must be gone
				// from the send queue before anything can flush it — and establishment does flush, through
				// the batch the stream layer opens while it widens its limits. A frame re-levelled a line
				// above and sent a moment later would put a stream the peer never saw on the wire at 1-RTT,
				// which is precisely the retransmission this phase exists to replace with a re-creation.
				//
				// The registry's "move the state before the promise" rule is nonetheless satisfied: the
				// 0-RTT keys are already discarded and the queue already re-levelled, so a handler that
				// enqueues from inside this call is enqueueing at 1-RTT, and one that fails a promise is
				// failing it against state that has finished moving.
				notifyHandler("onEarlyDataRejected", () -> frameHandler.onEarlyDataRejected(this));
			}
		}

		QuicTransportException deferred = null;
		for (Map.Entry<EncryptionLevel, ByteBuf> entry : result.cryptoToSend().entrySet()) {
			// The result's buffers are ours (DI-1); enqueueCrypto consumes each one even if an earlier
			// level already failed, so nothing leaks on the way out.
			try {
				enqueueCrypto(entry.getKey(), entry.getValue());
			} catch (QuicTransportException e) {
				if (deferred == null) deferred = e;
			}
		}
		if (deferred != null) {
			throw deferred;
		}

		if (result.handshakeComplete()) {
			onHandshakeComplete(result);
		}
		// A newly installed level may have packets waiting for exactly these keys.
		for (KeyInstallation installation : result.keysToInstall()) {
			drainAwaitingKeys(installation.level());
		}
	}

	/** Takes ownership of {@code bytes}. */
	private void enqueueCrypto(EncryptionLevel level, ByteBuf bytes) throws QuicTransportException {
		try {
			if (!level.hasCryptoStream()) {
				// Unreachable from the wire: an engine only produces CRYPTO bytes for a level that has a
				// CRYPTO stream, and a 0-RTT packet may not carry a CRYPTO frame (RFC 9000 §12.5). The
				// finally block still recycles, so this path owns its buffer like every other.
				throw new IllegalArgumentException("No CRYPTO stream at encryption level " + level);
			}
			long offset = cryptoOutOffset.get(level);
			while (bytes.readRemaining() > 0) {
				int chunk = Math.min(MAX_CRYPTO_CHUNK, bytes.readRemaining());
				// A retained slice, so the frame is independent of `bytes` and can outlive it in the
				// queue and then in a SentPacket until it is acknowledged.
				ByteBuf slice = bytes.slice(chunk);
				bytes.moveHead(chunk);
				sendQueue.enqueue(level, new CryptoFrame(offset, slice), false);
				offset += chunk;
			}
			cryptoOutOffset.put(level, offset);
		} finally {
			bytes.recycle();
		}
	}

	private void onHandshakeComplete(TlsEngineResult result) throws QuicTransportException {
		QuicTransportParameters peer = result.peerTransportParameters();
		if (peer == null) {
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"The TLS engine completed the handshake without surfacing the peer's transport parameters");
		}
		// FR-008: the ranges and the connection-ID cross-checks, the latter against what was actually
		// on the wire rather than against what we hoped to see.
		TransportParameterValidation.validate(peer, observedPeerScid,
			role == Role.CLIENT ? originalDestinationConnectionId : null,
			// Non-null only when a Retry was processed; a server that sends the parameter without having
			// sent a Retry, or omits it after sending one, fails validation (RFC 9000 §7.3).
			role == Role.CLIENT ? retrySourceConnectionId : null);
		if (role == Role.CLIENT && rememberedTransportParameters != null && result.resumed()) {
			// FR-054: only on a handshake that actually used the ticket. A server that fell back to a
			// full handshake opened a new session and promised nothing, so the same numbers are legal.
			TransportParameterValidation.validateNonReduction(rememberedTransportParameters, peer);
		}
		this.peerTransportParameters = peer;
		this.negotiatedAlpn = result.negotiatedAlpn();

		if (role == Role.SERVER) {
			// FR-005: the server signals confirmation to the client, and confirms itself once the frame
			// is actually on the wire (see sendOneUdpDatagram).
			sendQueue.enqueue(EncryptionLevel.ONE_RTT, HandshakeDoneFrame.INSTANCE, false);
			// RFC 9001 §4.9.3: the handshake is done, so nothing may arrive at 0-RTT that matters. The
			// permitted 3×PTO retention for reordered packets is deliberately not taken (see the
			// package note): a late 0-RTT packet is dropped as a discarded level, and the client
			// retransmits its stream data in 1-RTT.
			discardLevel(EncryptionLevel.ZERO_RTT);
		}

		transitionTo(QuicConnectionState.ESTABLISHED);
		cancelHandshakeDeadline();
		// Both depend on the peer's parameters, so this is the earliest point either can be right.
		armIdleTimer();
		armKeepAliveTimer();
		logger.debug("{} handshake complete, ALPN {}", role, negotiatedAlpn);
		if (frameHandler != null) {
			notifyHandler("onEstablished", () -> frameHandler.onEstablished(this));
		}
		establishPromise.trySet(this);
	}

	/** FR-005/FR-006: on confirmation the Handshake level can never be needed again. */
	private void confirmHandshake() {
		if (handshakeConfirmed) return;
		handshakeConfirmed = true;
		discardLevel(EncryptionLevel.HANDSHAKE);
		logger.debug("{} handshake confirmed", role);
	}

	/**
	 * Retires a level: keys, number space, CRYPTO stream, queued frames and buffered packets.
	 * <p>
	 * Deliberately fires no loss callback (FR-006): the packets of a discarded space are neither lost
	 * nor acknowledged, they simply stop existing. Idempotent.
	 * <p>
	 * A level that <i>shares</i> its packet number space keeps that space alive: discarding 0-RTT keys
	 * must not retire the Application space 1-RTT is numbered in (RFC 9000 §12.3).
	 */
	private void discardLevel(EncryptionLevel level) {
		LevelKeys levelKeys = keys.get(level);
		if (levelKeys.isDiscarded()) return;
		levelKeys.discard();
		PacketNumberSpace space = space(level);
		if (space.level() == level) {
			// RFC 9002 §B.9: the bytes of a discarded space leave the in-flight count without any window
			// reduction — they are neither lost nor acknowledged. Skipping this leaves a connection
			// permanently congestion-blocked by handshake packets it will never hear about again.
			long inFlight = 0;
			for (SentPacket packet : space.sentPackets().values()) {
				if (packet.inFlight) inFlight += packet.sizeInBytes;
			}
			congestion.onSpaceDiscarded(inFlight);
			space.discard();
		}
		if (level.hasCryptoStream()) cryptoIn.get(level).close();
		QuicFrame queued;
		while ((queued = sendQueue.poll(level)) != null) {
			Recyclers.recycle(queued);
		}
		dropAwaitingKeys(level);
	}

	// ---------------------------------------------------------------- send path

	private void flush() {
		if (state == QuicConnectionState.CLOSED || state == QuicConnectionState.DRAINING) {
			return;
		}
		try {
			for (int i = 0; i < MAX_DATAGRAMS_PER_FLUSH; i++) {
				if (!sendOneUdpDatagram()) {
					rearmTimers();
					return;
				}
			}
			logger.warn("Flush hit its {}-datagram bound with work still queued", MAX_DATAGRAMS_PER_FLUSH);
			rearmTimers();
		} catch (QuicTransportException e) {
			closeWith(e);
		}
	}

	/**
	 * Builds and sends at most one <b>UDP datagram</b>; returns whether anything is still pending
	 * afterwards. Named for the UDP datagram it produces, which is a different thing from the RFC 9221
	 * DATAGRAM frames {@link #sendDatagramFrame} queues — several of those can share one of these.
	 */
	private boolean sendOneUdpDatagram() throws QuicTransportException {
		int limit = (int) Math.min(settings.maxDatagramSize(), amplification.remaining());
		if (limit <= 0) {
			// The server is at its 3× anti-amplification limit and must wait to be validated (FR-018).
			return false;
		}

		// RFC 9002 §7: over the window, only an ACK may still go out — and a probe, which is exempt
		// because a controller able to silence the probe would deadlock the recovery it depends on.
		boolean congestionLimited = congestion.isBlocked() && !bypassCongestionWindow;
		Datagram plan = new Datagram();
		try {
			for (EncryptionLevel level : LEVEL_ORDER) {
				buildPacketFor(level, limit, congestionLimited, plan);
			}
		} catch (QuicTransportException | RuntimeException e) {
			for (ByteBuf packet : plan.packets) {
				packet.recycle();
			}
			throw e;
		}

		if (plan.packets.isEmpty()) {
			return false;
		}
		// RFC 9000 §14.1: only a client is obliged to pad, and it pads the whole datagram rather than
		// the packet — the trailing zeros read as PADDING, which CoalescedPackets.split accepts.
		int padTo = role == Role.CLIENT && plan.containsInitial
			? Math.min(PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE, limit)
			: 0;
		ByteBuf datagram = PacketAssembler.coalesce(plan.packets, padTo);
		int size = datagram.readRemaining();
		amplification.onDatagramSent(size);
		datagramsSent++;
		if (plan.ackEliciting && !ackElicitingSentSinceReceive) {
			// RFC 9000 §10.1: the *first* ack-eliciting packet since the last receive restarts our own
			// idle timer, so a connection kept alive only by our traffic does not time out at our end.
			// Only the first: see the field's note on why a probe must not renew it.
			ackElicitingSentSinceReceive = true;
			armIdleTimer();
		}
		sink.send(remoteAddress, datagram);

		if (plan.carriedHandshakeDone) {
			// FR-005: the server confirms on *sending* HANDSHAKE_DONE, which is now.
			confirmHandshake();
		}
		return hasPendingWork();
	}

	/** One datagram under construction: the protected packets so far and what they oblige us to do. */
	private static final class Datagram {
		private final List<ByteBuf> packets = new ArrayList<>();
		private int used;
		private boolean containsInitial;
		private boolean ackEliciting;
		private boolean carriedHandshakeDone;
	}

	/**
	 * Appends at most one packet at {@code level} to {@code plan}, if that level has keys and something
	 * to say and the datagram has room for it.
	 * <p>
	 * On any throw, {@code plan}'s packets are the caller's to recycle — this method never recycles a
	 * packet it has already handed over.
	 */
	private void buildPacketFor(EncryptionLevel level, int limit, boolean congestionLimited, Datagram plan)
		throws QuicTransportException {
		if (level == EncryptionLevel.ZERO_RTT && role == Role.SERVER) {
			// Spec FR-052 / RFC 9001 §4.6.1. A server holds 0-RTT receive keys only, so this is already
			// unreachable; stated anyway, because "unreachable" here rests on a key-installation detail
			// two packages away.
			return;
		}
		LevelKeys levelKeys = keys.get(level);
		if (!levelKeys.acceptsSend()) return;

		// The packet number is not known until we commit to sending, so the allowance is computed
		// against the widest possible encoding. Under-filling is harmless; the opposite would produce a
		// datagram over the path's limit.
		int allowance = limit - plan.used - packetOverhead(level, 4);
		if (allowance <= 0) return;

		PacketNumberSpace space = space(level);
		List<QuicFrame> frames = new ArrayList<>();
		int frameBytes = 0;

		if (carriesAcks(level) && shouldAck(space, level)) {
			AckFrame ack = buildAck(space, reactor.currentTimeMillis());
			if (ack != null && ack.encodedLength() <= allowance) {
				frames.add(ack);
				frameBytes += ack.encodedLength();
				space.onAckGenerated();
				if (level == EncryptionLevel.ONE_RTT) {
					ackNowOneRtt = false;
					cancelAckTimer();
				}
			}
		}
		if (!congestionLimited) {
			frameBytes += sendQueue.pollUpTo(level, allowance - frameBytes, frames::add);
			if (level == applicationSendLevel()) {
				frameBytes += drainOutboundDatagrams(allowance - frameBytes, frames);
			}
		}
		if (frames.isEmpty()) return;

		for (QuicFrame frame : frames) {
			FrameTypeRules.validateForSending(frame, level);
		}

		long packetNumber = space.nextPacketNumber();
		int pnLength = PacketNumbers.encodeLength(packetNumber, space.largestAcked());
		ByteBuf packet = PacketAssembler.assemblePacket(
			new PacketPlanOf(level, packetNumber, space.largestAcked(), frames).toPlan(),
			levelKeys.sendKeys(), peerConnectionId, localConnectionId,
			level == EncryptionLevel.INITIAL ? retryToken : NO_TOKEN, version,
			PacketAssembler.minPayloadForSampling(frameBytes, pnLength));
		plan.packets.add(packet);
		plan.used += packet.readRemaining();
		plan.containsInitial |= level == EncryptionLevel.INITIAL;

		// RFC 9001 §6.6: counted before the packet reaches the wire, so the limit can never be
		// exceeded by the packet that would have crossed it.
		levelKeys.onPacketSent();

		boolean ackEliciting = isAckEliciting(frames);
		plan.ackEliciting |= ackEliciting;
		plan.carriedHandshakeDone |= containsHandshakeDone(frames);
		SentPacket sent = new SentPacket(packetNumber, level, reactor.currentTimeMillis(),
			packet.readRemaining(), ackEliciting, ackEliciting, frames, false);
		if (sent.inFlight) {
			congestion.onPacketSent(sent.sizeInBytes);
		}
		space.onPacketSent(sent);
		onPacketSentEvent(sent);
	}

	/**
	 * Offers every queued RFC 9221 DATAGRAM frame to the packet being built, exactly <b>once</b> each
	 * (FR-078).
	 * <p>
	 * One attempt, not "the next flush", is what makes "never held indefinitely" unambiguous: a frame
	 * that does not fit is recycled and counted here rather than left to compete with newer ones on some
	 * later packet, and the queue is therefore empty again by the time this returns. A datagram whose
	 * value has expired is the whole premise of the extension — holding one would be the defect.
	 * <p>
	 * The loop keeps offering after a frame has been refused, deliberately: the frames are independent
	 * and a smaller one behind a large one is not blocked by it, which is precisely what
	 * {@code SendQueue.pollUpTo}'s head-of-line rule would have done instead.
	 *
	 * @return the bytes added to {@code frames}
	 */
	private int drainOutboundDatagrams(int allowance, List<QuicFrame> frames) {
		int used = 0;
		DatagramFrame datagram;
		while ((datagram = outboundDatagrams.poll()) != null) {
			int size = datagram.encodedLength();
			if (size <= allowance - used) {
				frames.add(datagram);
				used += size;
				datagramFramesSent++;
			} else {
				datagram.recycle();
				datagramFramesDropped++;
			}
		}
		return used;
	}

	private static boolean containsHandshakeDone(List<QuicFrame> frames) {
		for (QuicFrame frame : frames) {
			if (frame instanceof HandshakeDoneFrame) return true;
		}
		return false;
	}

	private boolean hasPendingWork() {
		boolean congestionLimited = congestion.isBlocked() && !bypassCongestionWindow;
		if (!congestionLimited && !outboundDatagrams.isEmpty()
			&& keys.get(applicationSendLevel()).acceptsSend()) {
			// Without this a flush carrying nothing but DATAGRAM frames would report "nothing pending".
			// The key test keeps a queue that no level can yet carry from spinning the flush loop.
			return true;
		}
		for (EncryptionLevel level : LEVEL_ORDER) {
			if (!keys.get(level).acceptsSend()) continue;
			// Queued data over the window is not "pending" for this flush: claiming it were would spin the
			// flush loop to its bound on every call until an ACK arrived.
			if (!congestionLimited && sendQueue.hasPending(level)) return true;
			// carriesAcks keeps the Application space from being consulted twice, once per level.
			if (carriesAcks(level) && shouldAck(space(level), level)) return true;
		}
		return false;
	}

	/**
	 * Bytes a packet at {@code level} costs beyond its frames: the unprotected header and the AEAD
	 * tag. The Length varint is reserved at its widest so this is never an underestimate.
	 */
	private int packetOverhead(EncryptionLevel level, int pnLength) {
		if (level == EncryptionLevel.ONE_RTT) {
			return 1 + peerConnectionId.length() + pnLength + PacketAssembler.AEAD_TAG_LENGTH;
		}
		if (level == EncryptionLevel.INITIAL) {
			return initialPacketOverhead(retryToken.length, peerConnectionId.length(), pnLength);
		}
		return 1                                                     // first byte
			+ 4                                                      // version
			+ 1 + peerConnectionId.length()                          // DCID length + DCID
			+ 1 + localConnectionId.length()                         // SCID length + SCID
			+ 4                                                      // Length varint, widest encoding
			+ pnLength
			+ PacketAssembler.AEAD_TAG_LENGTH;
	}

	/**
	 * The same, for an Initial whose token and destination connection ID are not (yet) this connection's —
	 * which is what lets a Retry be measured before it is adopted.
	 */
	private int initialPacketOverhead(int tokenLength, int peerCidLength, int pnLength) {
		return 1                                                     // first byte
			+ 4                                                      // version
			+ 1 + peerCidLength                                      // DCID length + DCID
			+ 1 + localConnectionId.length()                         // SCID length + SCID
			+ QuicVarInts.encodedLength(tokenLength) + tokenLength   // Token Length varint + token
			+ 4                                                      // Length varint, widest encoding
			+ pnLength
			+ PacketAssembler.AEAD_TAG_LENGTH;
	}

	/** A tiny adapter so the record's positional construction stays readable at the call site. */
	private record PacketPlanOf(
		EncryptionLevel level, long packetNumber, long largestAckedOrSent, List<QuicFrame> frames
	) {
		PacketAssembler.PacketPlan toPlan() {
			return new PacketAssembler.PacketPlan(level, packetNumber, largestAckedOrSent, frames);
		}
	}

	// ---------------------------------------------------------------- timers (T054)

	/**
	 * Arms the loss/probe timer (RFC 9002 §6.2). Loss deadlines win: a packet we can already declare
	 * lost should be retransmitted rather than probed for.
	 * <p>
	 * One timer, not one per space, because only the earliest deadline can fire first — and re-arming on
	 * every send and every ACK keeps it correct without a wheel of its own.
	 */
	private void rearmTimers() {
		cancelLossTimer();
		if (state.isTerminating() || state == QuicConnectionState.CLOSED) return;

		LossDetector.Armed loss = LossDetector.earliestLossTime(spaces.values());
		if (loss != null) {
			lossTimer = reactor.schedule(loss.time(), this::onLossTimer);
			return;
		}
		LossDetector.Armed probe = LossDetector.nextProbe(
			spaces.values(), rtt, ptoCount, peerMaxAckDelayMillis(), handshakeConfirmed);
		if (probe != null) {
			lossTimer = reactor.schedule(probe.time(), this::onLossTimer);
			return;
		}
		EncryptionLevel unvalidated = handshakeProbeLevel();
		if (unvalidated != null) {
			// RFC 9002 §6.2.2.1: while the handshake is unconfirmed the timer is armed even with nothing
			// in flight. Without this the handshake deadlocks: once our flight has been acknowledged and
			// the peer's reply is lost, there is nothing left to declare lost and nothing to probe for, so
			// no timer would be armed and both sides would wait for each other until the idle timeout.
			// Three of fifty seeds in QuicLossRecoveryTest hit exactly that.
			long deadline = reactor.currentTimeMillis()
				+ rtt.ptoMillis(ptoCount, false, peerMaxAckDelayMillis());
			lossTimer = reactor.schedule(deadline, this::onLossTimer);
		}
	}

	/**
	 * The level to probe at when nothing is in flight but the handshake is not finished yet, or
	 * {@code null} once there is no such obligation.
	 * <p>
	 * The highest level with keys, because that is the one the peer is waiting on — probing a level the
	 * peer has already discarded would be answered with a drop.
	 */
	private @Nullable EncryptionLevel handshakeProbeLevel() {
		if (handshakeConfirmed || state.isTerminating()) return null;
		return highestSendableLevel();
	}

	private void onLossTimer() {
		lossTimer = null;
		if (state.isTerminating() || state == QuicConnectionState.CLOSED) return;
		long now = reactor.currentTimeMillis();
		try {
			LossDetector.Armed loss = LossDetector.earliestLossTime(spaces.values());
			if (loss != null && loss.time() <= now) {
				detectAndRequeueLost(loss.level(), now);
			} else {
				sendProbe(now);
			}
			flush();
			rearmTimers();
		} catch (QuicTransportException e) {
			closeWith(e);
		} finally {
			// Scoped to the flush the probe triggered, so an exempt probe cannot leave the window
			// permanently exempt.
			bypassCongestionWindow = false;
		}
	}

	/**
	 * RFC 9002 §6.2.4: on a probe timeout, send an ack-eliciting packet in the space whose timer expired,
	 * carrying unacknowledged data wherever there is any.
	 * <p>
	 * <b>Re-sending the data, not just a PING, is what makes the handshake survive loss.</b> A PING alone
	 * elicits an ACK, and an ACK is enough <i>only</i> when the ordinary thresholds can then act on it —
	 * which needs a later packet in the same space to have been acknowledged. During the handshake that
	 * often cannot happen: if a server's ServerHello is lost, the client has no keys for anything above
	 * Initial, never acknowledges a later Initial packet (an ACK-only packet does not oblige it to), and
	 * so the server's largest-acknowledged never advances past the lost packet. Meanwhile the Handshake
	 * space, whose oldest unacknowledged packet is older still, wins every "earliest deadline" contest,
	 * so the Initial space is never the probed one. Three of fifty seeds in {@code QuicLossRecoveryTest}
	 * deadlocked exactly there.
	 * <p>
	 * Hence the loop over every level with keys rather than only the probe space: RFC 9000 §12.2 puts
	 * them all in one datagram anyway, and RFC 9002 §6.2.4 explicitly permits more than the one required
	 * probe. The PING remains the fallback that guarantees the packet is ack-eliciting when there is
	 * genuinely nothing to re-send.
	 */
	private void sendProbe(long now) throws QuicTransportException {
		LossDetector.Armed probe = LossDetector.nextProbe(
			spaces.values(), rtt, ptoCount, peerMaxAckDelayMillis(), handshakeConfirmed);
		EncryptionLevel level = probe != null ? probe.level() : handshakeProbeLevel();
		if (level == null || !keys.get(level).acceptsSend()) return;
		// Incremented before sending: the next timeout must already be the doubled one, or a persistently
		// black-holed path would be probed at a fixed interval forever (RFC 9002 §6.2.1).
		ptoCount++;
		probesSent++;

		// RFC 9002 §7: the probe leaves regardless of the window.
		bypassCongestionWindow = true;
		boolean carriesData = false;
		// By space, not by level: 0-RTT and 1-RTT share the Application space, so a level loop would
		// probe it twice and re-queue two packets where RFC 9002 §6.2.4 asks for one.
		EnumSet<EncryptionLevel.Space> probed = EnumSet.noneOf(EncryptionLevel.Space.class);
		for (EncryptionLevel candidate : LEVEL_ORDER) {
			if (!keys.get(candidate).acceptsSend()) continue;
			if (!probed.add(candidate.packetNumberSpace())) continue;
			carriesData |= requeueOldestUnacknowledged(space(candidate));
		}
		if (!carriesData && !sendQueue.hasPending(level)) {
			sendQueue.enqueue(level, PingFrame.INSTANCE, false);
		}
		logger.debug("{} probe timeout #{} in the {} space{}",
			role, ptoCount, level, carriesData ? ", re-sending unacknowledged data" : "");
	}

	/**
	 * Moves the retransmittable content of {@code space}'s oldest unacknowledged ack-eliciting packet
	 * back onto its send queue, for a probe to carry.
	 * <p>
	 * Deliberately <b>not</b> counted as a loss <i>event</i>: a probe timeout is not evidence that anything
	 * was lost (RFC 9002 §6.2), so no congestion event is raised and the window is not halved for a packet
	 * that may still be in flight. The packet is removed from the space because ownership of its frames
	 * passes to the queue; a duplicate arriving later is harmless, since the receiver de-duplicates CRYPTO
	 * by offset.
	 * <p>
	 * Its bytes must nevertheless leave {@code bytesInFlight}, exactly as on the three other paths that
	 * remove a packet from a space. Nothing will ever acknowledge a record that no longer exists, so
	 * skipping this leaks in-flight bytes on every probe: after a black-holed interval the window reads as
	 * permanently full, and the connection stays blocked once the path recovers.
	 *
	 * @return whether anything was re-queued
	 */
	private boolean requeueOldestUnacknowledged(PacketNumberSpace space) throws QuicTransportException {
		long oldestNumber = PacketNumberSpace.NONE;
		long oldestTime = Long.MAX_VALUE;
		for (Map.Entry<Long, SentPacket> entry : space.sentPackets().entrySet()) {
			SentPacket packet = entry.getValue();
			if (!packet.ackEliciting || packet.sentTime >= oldestTime) continue;
			oldestTime = packet.sentTime;
			oldestNumber = entry.getKey();
		}
		if (oldestNumber == PacketNumberSpace.NONE) return false;

		SentPacket oldest = space.onPacketLost(oldestNumber);
		if (oldest == null) return false;
		probeRetransmits++;
		if (oldest.inFlight) {
			// Bytes only — onPacketsLost subtracts from bytesInFlight without raising a congestion event.
			congestion.onPacketsLost(oldest.sizeInBytes);
		}
		requeueLost(oldest);
		// The packet's own level, not the space's: a re-queued 0-RTT packet's frames land in the 0-RTT
		// queue, while the Application space calls itself ONE_RTT.
		return sendQueue.hasPending(oldest.level);
	}

	/** RFC 9000 §13.2.1: a 1-RTT ACK may be held back, but never past our advertised {@code max_ack_delay}. */
	private void armAckTimer(PacketNumberSpace space) {
		if (ackNowOneRtt || ackTimer != null) return;
		if (space.ackElicitingReceivedSinceAck() >= ACK_ELICITING_THRESHOLD) {
			ackNowOneRtt = true;
			return;
		}
		ackTimer = reactor.delay(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY, () -> {
			ackTimer = null;
			ackNowOneRtt = true;
			flush();
		});
	}

	private void cancelLossTimer() {
		ScheduledRunnable timer = lossTimer;
		if (timer != null) {
			lossTimer = null;
			timer.cancel();
		}
	}

	private void cancelAckTimer() {
		ScheduledRunnable timer = ackTimer;
		if (timer != null) {
			ackTimer = null;
			timer.cancel();
		}
	}

	private void cancelClosingTimer() {
		ScheduledRunnable timer = closingTimer;
		if (timer != null) {
			closingTimer = null;
			timer.cancel();
		}
	}

	private void cancelIdleTimer() {
		ScheduledRunnable timer = idleTimer;
		if (timer != null) {
			idleTimer = null;
			timer.cancel();
		}
	}

	private void cancelKeepAliveTimer() {
		ScheduledRunnable timer = keepAliveTimer;
		if (timer != null) {
			keepAliveTimer = null;
			timer.cancel();
		}
	}

	// ---------------------------------------------------------------- idle timeout (T061)

	/**
	 * (Re-)arms the idle timer (RFC 9000 §10.1). Called on every received datagram and on every
	 * ack-eliciting packet sent, which is the union of the two conditions the RFC gives for restarting
	 * it — a superset, deliberately: restarting too often can only ever keep a connection alive
	 * longer, never kill a live one early.
	 * <p>
	 * {@code delayBackground} is what makes FR-024 true: a connection sitting idle for its whole
	 * timeout must not be the reason an eventloop refuses to exit.
	 */
	private void armIdleTimer() {
		cancelIdleTimer();
		if (state.isTerminating() || state == QuicConnectionState.IDLE) return;
		long timeout = effectiveIdleTimeoutMillis();
		if (timeout <= 0) return;
		idleTimer = reactor.delayBackground(timeout, () -> {
			idleTimer = null;
			if (state.isTerminating()) return;
			// RFC 9000 §10.1: the connection is discarded *silently*. There is no CONNECTION_CLOSE,
			// because there is no evidence anyone is listening, and no closing period, because there is
			// nothing to answer.
			logger.debug("{} idle timeout after {} ms", role, timeout);
			abandon(new QuicTransportException(QuicTransportErrors.NO_ERROR,
				"Idle timeout after " + timeout + " ms"));
		});
	}

	/**
	 * The effective idle timeout: {@code max(the smaller of the two advertised non-zero timeouts,
	 * 3 × PTO)}, or 0 when neither endpoint advertised one and the connection therefore never idles out
	 * (RFC 9000 §10.1).
	 * <p>
	 * The {@code 3 × PTO} floor exists so a path slow enough that a probe has not yet been answered is
	 * not torn down for being quiet — on such a path, quiet is indistinguishable from waiting.
	 */
	public long effectiveIdleTimeoutMillis() {
		checkInReactorThread(this);
		long local = settings.maxIdleTimeoutMillis();
		long peer = peerTransportParameters == null ? 0 : peerTransportParameters.maxIdleTimeout();
		// 0 means "no limit from this endpoint", so it loses to any non-zero value rather than winning
		// the min() (RFC 9000 §18.2).
		long negotiated;
		if (local == 0) {
			negotiated = peer;
		} else if (peer == 0) {
			negotiated = local;
		} else {
			negotiated = Math.min(local, peer);
		}
		if (negotiated == 0) return 0;
		return Math.max(negotiated, closingPeriodMillis());
	}

	// ---------------------------------------------------------------- keep-alive (T062)

	/**
	 * Arms the opt-in keep-alive PING (RFC 9000 §10.1.2, FR-025). Off unless
	 * {@code QuicConnectionSettings.withKeepAliveInterval} was set, and the settings builder has
	 * already refused an interval above half the local idle timeout.
	 * <p>
	 * A PING is the whole mechanism: it is ack-eliciting, so it restarts the idle timer at both ends,
	 * and it carries nothing.
	 */
	private void armKeepAliveTimer() {
		Long interval = settings.keepAliveIntervalMillis();
		if (interval == null || keepAliveTimer != null) return;
		scheduleKeepAlive(interval);
	}

	private void scheduleKeepAlive(long interval) {
		keepAliveTimer = reactor.delayBackground(interval, () -> {
			keepAliveTimer = null;
			if (state != QuicConnectionState.ESTABLISHED) return;
			try {
				sendQueue.enqueue(EncryptionLevel.ONE_RTT, PingFrame.INSTANCE, false);
			} catch (QuicTransportException e) {
				closeWith(e);
				return;
			}
			keepAlivesSent++;
			flush();
			scheduleKeepAlive(interval);
		});
	}

	/**
	 * RFC 9000 §10.2.2: a received CONNECTION_CLOSE puts this connection into the draining state for
	 * three probe timeouts, during which it sends <b>nothing</b> — not even another CONNECTION_CLOSE,
	 * which is what distinguishes draining from closing.
	 */
	private void onPeerClose(ConnectionCloseFrame close) {
		if (state.isTerminating()) return;
		// SI-6/FR-031: the peer's reason phrase is untrusted text of attacker-chosen length. It is
		// bounded, passed as a logger *argument* and never as a format string, and never put into an
		// exception message.
		String reason = truncatedReason(close.reasonPhrase);
		this.peerClose = new PeerClose(close.isApplication, close.errorCode, close.triggerFrameType, reason);
		logger.debug("Peer closed the connection: {} ({}), frame type {}, reason {}",
			QuicTransportErrors.name(close.errorCode), close.errorCode, close.triggerFrameType, reason);
		QuicTransportException cause = new QuicTransportException(close.errorCode,
			"Peer closed the connection with " + QuicTransportErrors.name(close.errorCode));

		transitionTo(QuicConnectionState.DRAINING);
		release();
		armClosingTimer();
		establishPromise.trySetException(cause);
	}

	/**
	 * Decodes a reason phrase to at most {@link #MAX_SURFACED_REASON_BYTES} bytes' worth of text.
	 * <p>
	 * Truncation is by <i>byte</i> before decoding, so a phrase cut mid-sequence yields a replacement
	 * character rather than an exception — the phrase is diagnostic, and refusing to decode it would
	 * hand a peer a way to hide why it closed.
	 */
	private static String truncatedReason(byte[] reasonPhrase) {
		int length = Math.min(reasonPhrase.length, MAX_SURFACED_REASON_BYTES);
		return new String(reasonPhrase, 0, length, StandardCharsets.UTF_8);
	}

	/**
	 * Closes with a transport error code and reason of the caller's choosing (FR-026). Idempotent: a
	 * second call, or a call after the peer already closed, does nothing and sends nothing.
	 */
	public void closeWith(long errorCode, String reason) {
		checkInReactorThread(this);
		closeWith(new QuicTransportException(errorCode, reason));
	}

	/**
	 * Closes with an RFC 9000 §19.19 <b>application</b> CONNECTION_CLOSE (wire type {@code 0x1d}), for a
	 * protocol layered above this one — HTTP/3's RFC 9114 §8.1 codes are the caller this exists for.
	 * Idempotent, exactly like {@link #closeWith(long, String)}.
	 * <p>
	 * The distinction is not cosmetic. An application code is drawn from RFC 9000 §20.2's own space, and
	 * a peer reading one out of a {@code 0x1c} frame would name it against §20.1's: HTTP/3's
	 * {@code H3_STREAM_CREATION_ERROR} (0x0103) falls inside the transport space's {@code CRYPTO_ERROR}
	 * range, where it reads as a TLS alert.
	 * <p>
	 * RFC 9000 §10.2.3: the {@code 0x1d} form needs 1-RTT keys, so a close before the handshake finishes
	 * goes out as the transport close carrying {@link QuicTransportErrors#APPLICATION_ERROR} the RFC
	 * prescribes in its place. The application's own code is dropped there by design — the peer has no
	 * application-layer state to apply it to yet.
	 * <p>
	 * The establishment promise fails with that same {@code APPLICATION_ERROR} rather than with
	 * {@code applicationErrorCode}: {@link QuicTransportException} reports its code against §20.1, so
	 * putting an application code there would misname it in every log line it reaches. The caller that
	 * chose the code already knows it; nothing is lost that this connection could report honestly.
	 */
	public void closeWithApplicationError(long applicationErrorCode, String reason) {
		checkInReactorThread(this);
		closeWith(applicationErrorCode, null, true,
			new QuicTransportException(QuicTransportErrors.APPLICATION_ERROR, reason));
	}

	private void closeWith(QuicTransportException e) {
		closeWith(e.errorCode(), e.frameType(), false, e);
	}

	/**
	 * Enters the closing state: tells the peer why if there are keys to tell it under, then holds that
	 * CONNECTION_CLOSE for three probe timeouts so a peer that retransmits gets an answer
	 * (RFC 9000 §10.2.1).
	 * <p>
	 * The establishment promise fails <i>now</i>, not at the end of the period — the caller has no
	 * reason to wait out a timeout for an answer already known.
	 * <p>
	 * The one entry into the closing state, transport and application close alike: the two differ only
	 * in which CONNECTION_CLOSE variant they retain, and everything that follows — the closing period,
	 * the re-send budget, what is released and when — is the same protocol either way.
	 */
	private void closeWith(long errorCode, @Nullable Long frameType, boolean isApplication,
		QuicTransportException cause
	) {
		if (state.isTerminating() || state == QuicConnectionState.CLOSED) {
			return;
		}
		boolean wasOpen = state != QuicConnectionState.IDLE;
		transitionTo(QuicConnectionState.CLOSING);
		// Everything except the keys and the close frame goes now: nothing in flight can still be
		// usefully acknowledged, and holding a send queue through the closing period would be a leak
		// measured in a whole PTO.
		releaseForClosing();

		EncryptionLevel level = wasOpen ? highestSendableLevel() : null;
		if (level != null) {
			closingFrame = closingFrameFor(errorCode, frameType, isApplication, level);
			sendConnectionClose();
		}
		armClosingTimer();
		establishPromise.trySetException(cause);
	}

	/**
	 * The CONNECTION_CLOSE to retain for the closing period.
	 * <p>
	 * RFC 9000 §10.2.3: the application form ({@code 0x1d}) may only travel under 1-RTT keys, so below
	 * that it degrades to the transport form carrying {@link QuicTransportErrors#APPLICATION_ERROR} —
	 * the substitute the RFC names for it, and the only thing {@link FrameTypeRules} would let onto the
	 * wire at Initial or Handshake.
	 */
	private static ConnectionCloseFrame closingFrameFor(long errorCode, @Nullable Long frameType,
		boolean isApplication, EncryptionLevel level
	) {
		if (!isApplication) {
			return ConnectionCloseFrame.transport(errorCode, frameType == null ? 0 : frameType, NO_TOKEN);
		}
		return level == EncryptionLevel.ONE_RTT ?
			ConnectionCloseFrame.application(errorCode, NO_TOKEN) :
			ConnectionCloseFrame.transport(QuicTransportErrors.APPLICATION_ERROR, 0, NO_TOKEN);
	}

	/**
	 * Gives up without sending anything and without a closing period — used where there is no peer to
	 * tell (the handshake deadline, the idle timeout).
	 */
	private void abandon(QuicTransportException e) {
		if (state == QuicConnectionState.CLOSED) return;
		release();
		markClosed();
		establishPromise.trySetException(e);
	}

	/**
	 * Builds and sends one datagram carrying nothing but the retained CONNECTION_CLOSE.
	 * <p>
	 * Deliberately not routed through {@link #flush()}: the send queue is already gone by this point,
	 * and a closing connection must emit exactly this one frame and no ACK, PING or CRYPTO that
	 * happened to still be pending. The anti-amplification budget still applies (RFC 9000 §10.2.3) —
	 * closing is not a licence to amplify.
	 */
	private void sendConnectionClose() {
		ConnectionCloseFrame frame = closingFrame;
		if (frame == null) return;
		EncryptionLevel level = highestSendableLevel();
		if (level == null) return;

		int limit = (int) Math.min(settings.maxDatagramSize(), amplification.remaining());
		int pnLength = 4;
		if (frame.encodedLength() + packetOverhead(level, pnLength) > limit) {
			logger.debug("No room to send CONNECTION_CLOSE at {} within {} bytes", level, limit);
			return;
		}

		LevelKeys levelKeys = keys.get(level);
		PacketNumberSpace space = space(level);
		ByteBuf datagram;
		try {
			// RFC 9001 §6.6 accounting still applies to the last packet a connection ever sends.
			levelKeys.onPacketSent();
			long packetNumber = space.nextPacketNumber();
			pnLength = PacketNumbers.encodeLength(packetNumber, space.largestAcked());
			ByteBuf packet = PacketAssembler.assemblePacket(
				new PacketPlanOf(level, packetNumber, space.largestAcked(), List.of(frame)).toPlan(),
				levelKeys.sendKeys(), peerConnectionId, localConnectionId,
				level == EncryptionLevel.INITIAL ? retryToken : NO_TOKEN, version,
				PacketAssembler.minPayloadForSampling(frame.encodedLength(), pnLength));
			// A client Initial still has to fill a 1200-byte datagram (RFC 9000 §14.1) even when all it
			// carries is the reason the client is giving up.
			int padTo = role == Role.CLIENT && level == EncryptionLevel.INITIAL
				? Math.min(PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE, limit)
				: 0;
			datagram = PacketAssembler.coalesce(List.of(packet), padTo);
		} catch (QuicTransportException | RuntimeException suppressed) {
			logger.debug("Could not deliver CONNECTION_CLOSE", suppressed);
			return;
		}
		int size = datagram.readRemaining();
		amplification.onDatagramSent(size);
		datagramsSent++;
		closeSentTime = reactor.currentTimeMillis();
		sink.send(remoteAddress, datagram);
	}

	/**
	 * RFC 9000 §10.2.1: while closing, a received packet earns at most one more CONNECTION_CLOSE, and
	 * no more often than once per probe timeout.
	 * <p>
	 * Both halves of that rule matter. Without the per-packet limit a flood of packets would be
	 * answered one-for-one, which is a reflection amplifier; without the interval a peer whose
	 * retransmissions are themselves lost would drive us at its own rate.
	 */
	private void maybeResendConnectionClose() {
		if (closingFrame == null) return;
		long interval = rtt.ptoMillis(ptoCount, handshakeConfirmed, peerMaxAckDelayMillis());
		if (reactor.currentTimeMillis() - closeSentTime < interval) {
			return;
		}
		closeResends++;
		sendConnectionClose();
	}

	/** Arms the RFC 9000 §10.2 closing/draining period, after which nothing of this connection remains. */
	private void armClosingTimer() {
		cancelClosingTimer();
		long period = closingPeriodMillis();
		// delayBackground, because a connection nobody is waiting on must not keep the eventloop alive
		// for three probe timeouts (FR-024).
		closingTimer = reactor.delayBackground(period, () -> {
			closingTimer = null;
			release();
			markClosed();
		});
	}

	/**
	 * RFC 9000 §10.2: three times the current probe timeout, backoff included — how long the closing or
	 * draining period lasts, and the floor under the idle timeout.
	 */
	public long closingPeriodMillis() {
		checkInReactorThread(this);
		return CLOSING_PERIOD_PTO_MULTIPLIER * rtt.ptoMillis(ptoCount, handshakeConfirmed, peerMaxAckDelayMillis());
	}

	/**
	 * Releases everything the closing state does not need: the send queue, the packets held awaiting
	 * keys, the frames of every unacknowledged packet, and the recovery timers.
	 * <p>
	 * The keys survive, because {@link #sendConnectionClose()} needs them for the whole closing period,
	 * and so do the packet number spaces — a re-sent CONNECTION_CLOSE needs a fresh packet number, and
	 * reusing one would repeat an AEAD nonce.
	 */
	private void releaseForClosing() {
		cancelHandshakeDeadline();
		cancelLossTimer();
		cancelAckTimer();
		cancelIdleTimer();
		cancelKeepAliveTimer();
		// Research D-5 audit verdict: iterate the structures rather than the levels. There are three
		// packet number spaces for four levels, so a per-level loop would abandon the Application space
		// twice, and ZERO_RTT has no CRYPTO stream to close at all.
		for (PacketNumberSpace space : spaces.values()) {
			space.abandonOutstanding();
		}
		for (CryptoStreamAssembler assembler : cryptoIn.values()) {
			assembler.close();
		}
		sendQueue.drop();
		// The datagram queue is not part of the send queue, so it needs its own release right here — the
		// contract is that every queued payload, inbound and outbound, is recycled exactly once on close.
		DatagramFrame datagram;
		while ((datagram = outboundDatagrams.poll()) != null) {
			datagram.recycle();
		}
		for (ProtectedPacket packet : awaitingKeys) {
			packet.bytes().recycle();
		}
		awaitingKeys.clear();
	}

	/**
	 * Releases every buffer this connection owns, keys included. Idempotent (WI-9), and the single
	 * place that has to be right for FR-024's "no residue".
	 */
	private void release() {
		releaseForClosing();
		cancelClosingTimer();
		closingFrame = null;
		// Research D-5 audit verdict: ZERO_RTT belongs in this loop. Its keys, its queued frames and its
		// packets held awaiting keys are all real state a 0-RTT attempt can leave behind, and
		// discardLevel is what releases them; the shared Application space is retired once, at ONE_RTT.
		for (EncryptionLevel level : EncryptionLevel.values()) {
			// Recycles the frames of every unacknowledged packet, and the buffered CRYPTO stream.
			discardLevel(level);
		}
	}

	/** The single transition into {@code CLOSED}, so the {@code onClosed} hook can only fire once. */
	private void markClosed() {
		if (state == QuicConnectionState.CLOSED) return;
		transitionTo(QuicConnectionState.CLOSED);
		if (frameHandler != null) {
			// Already terminal, so notifyHandler's deferred close is a no-op — this only contains the throw.
			notifyHandler("onClosed", () -> frameHandler.onClosed(this));
		}
		if (onClosed != null) {
			try {
				onClosed.run();
			} catch (RuntimeException e) {
				// The endpoint's unregister hook. Same reasoning as notifyHandler: this runs from a timer
				// callback, and letting it escape would kill the reactor rather than one connection.
				logger.warn("{} the onClosed hook threw", role, e);
			}
		}
	}

	private void cancelHandshakeDeadline() {
		ScheduledRunnable deadline = handshakeDeadline;
		if (deadline != null) {
			handshakeDeadline = null;
			deadline.cancel();
		}
	}

	/**
	 * The packet number space {@code level}'s packets are numbered and acknowledged in — three spaces
	 * for four levels, because 0-RTT and 1-RTT share the Application space (RFC 9000 §12.3).
	 * <p>
	 * The single lookup point, so no call site can key a space by encryption level and quietly invent
	 * a fourth one (research D-5 audit (a)).
	 */
	private PacketNumberSpace space(EncryptionLevel level) {
		return spaces.get(level.packetNumberSpace());
	}

	/**
	 * The highest level a <b>control</b> packet may go out at — a CONNECTION_CLOSE or a probe.
	 * <p>
	 * {@code ZERO_RTT} is skipped, and not as an oversight: this picks a level the peer can certainly
	 * read, and a server that refused early data holds no 0-RTT keys at all, so a CONNECTION_CLOSE
	 * sent there would be dropped rather than read. The level below it is always readable.
	 */
	private @Nullable EncryptionLevel highestSendableLevel() {
		for (int i = LEVEL_ORDER.length - 1; i >= 0; i--) {
			EncryptionLevel level = LEVEL_ORDER[i];
			if (level == EncryptionLevel.ZERO_RTT) continue;
			if (keys.get(level).acceptsSend()) {
				return level;
			}
		}
		return null;
	}

	/** The peer's advertised {@code max_ack_delay}, or the RFC 9000 §18.2 default before we know it. */
	private long peerMaxAckDelayMillis() {
		return peerTransportParameters == null
			? QuicTransportParameters.DEFAULT_MAX_ACK_DELAY
			: peerTransportParameters.maxAckDelay();
	}

	/** Converts a peer ACK frame's Ack Delay field to milliseconds using the exponent the peer advertised. */
	private long peerAckDelayMillis(long ackDelayField) {
		long exponent = peerTransportParameters == null
			? QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT
			: peerTransportParameters.ackDelayExponent();
		// The exponent is validated at or below 20, but the Ack Delay field is a varint the peer chooses
		// freely, so the shift overflows above 2^43 and would come back negative. Saturating instead: an
		// absurd delay is clamped to an absurd delay, which the RTT estimator already discards, whereas a
		// negative one would inflate an RTT sample and shrink every timer that derives from it.
		int shift = (int) exponent;
		long shifted = ackDelayField << shift;
		if (shift > 0 && (shifted >> shift) != ackDelayField) {
			shifted = Long.MAX_VALUE;
		}
		return shifted / 1000;
	}

	private static void recycleAll(List<QuicFrame> frames) {
		for (QuicFrame frame : frames) {
			Recyclers.recycle(frame);
		}
	}

	// ---------------------------------------------------------------- FR-004a error mapping (T031)

	/** RFC 9001 §4.8: a TLS alert becomes CONNECTION_CLOSE {@code 0x0100 + alert}. */
	public static QuicTransportException cryptoErrorFor(TlsAlertException e) {
		return new QuicTransportException(QuicTransportErrors.cryptoError(e.alertCode()),
			"TLS handshake failed: " + e.getMessage());
	}

	/**
	 * HelloRetryRequest is unsupported (feature-02 clarification Q1), so it is reported as the
	 * {@code handshake_failure} alert rather than as a distinct transport code.
	 */
	public static QuicTransportException helloRetryRequestErrorFor(TlsHelloRetryRequestException e) {
		return new QuicTransportException(QuicTransportErrors.cryptoError(HELLO_RETRY_REQUEST_ALERT),
			"HelloRetryRequest is not supported: " + e.getMessage());
	}

	/**
	 * A {@link MalformedDataException} out of the TLS engine is always a transport-parameter problem —
	 * the engine surfaces it unchanged precisely so it does <b>not</b> become a TLS alert (RFC 9000
	 * §18 errors are transport errors).
	 */
	public static QuicTransportException transportParameterErrorFor(MalformedDataException e) {
		return new QuicTransportException(QuicTransportErrors.TRANSPORT_PARAMETER_ERROR,
			"Invalid transport parameters: " + e.getMessage());
	}

	/**
	 * A transport rule the peer broke from inside the TLS layer — a resumption bound, or QUIC's
	 * {@code max_early_data_size} rule (RFC 9001 §4.6.1). Reported as RFC 9000 §20.1
	 * {@code PROTOCOL_VIOLATION}, not as a transport-parameter error and not as a TLS alert: neither
	 * names what happened.
	 */
	public static QuicTransportException protocolViolationErrorFor(TlsProtocolViolationException e) {
		return new QuicTransportException(QuicTransportErrors.PROTOCOL_VIOLATION, e.getMessage());
	}

	// ---------------------------------------------------------------- the handler's API (T074)

	/**
	 * Queues a frame contributed by the registered {@link QuicFrameHandler} for the next flush, at the
	 * 1-RTT level.
	 * <p>
	 * <b>Takes ownership</b> of {@code frame} — it is recycled if the queue is full or the connection is
	 * closing, and handed back through {@link QuicFrameHandler#onFrameAcknowledged} or
	 * {@link QuicFrameHandler#onFrameLost} once its fate is known. Does not send: call
	 * {@link #requestSend()} when a batch is ready, so several frames share a datagram.
	 *
	 * @throws QuicTransportException {@code INTERNAL_ERROR} when the send queue's byte bound would be
	 *                                exceeded (FR-040) — back-pressure, not a wire error
	 * @see #sendDatagramFrame(ByteBuf) for RFC 9221 DATAGRAM frames, which carry a policy this raw seam
	 * deliberately does not apply
	 */
	public void enqueueFrame(QuicFrame frame) throws QuicTransportException {
		checkInReactorThread(this);
		if (frameHandler == null) {
			Recyclers.recycle(frame);
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"enqueueFrame requires a frame handler registered at build time");
		}
		EncryptionLevel level = applicationSendLevel();
		try {
			FrameTypeRules.validateForSending(frame, level);
		} catch (QuicTransportException e) {
			// Ownership had already transferred, so the frame is ours to release — SendQueue.enqueue does
			// the same on its own rejection path, and a caller cannot be expected to guess which of the two
			// refused it.
			Recyclers.recycle(frame);
			throw e;
		}
		sendQueue.enqueue(level, frame, true);
	}

	/**
	 * {@link #enqueueFrame}, except that the frame goes to the <b>front</b> of the 1-RTT queue: this is
	 * the entry point for a handler <em>retransmitting</em> something it was told was lost.
	 * <p>
	 * The distinction is not cosmetic. A handler may have queued far more than a congestion window —
	 * {@code QuicStreamManager} bounds itself by {@code maxOutstandingStreamBytes}, which is hundreds of
	 * kilobytes — so an appended retransmission waits out the whole backlog, many round trips. Meanwhile
	 * the receiver holds every byte that arrived after the gap and cannot deliver any of it, and each
	 * further loss opens another gap: the receiver's reassembly bound, not the network, is what ends the
	 * connection. Retransmitting ahead of new data (RFC 9002 §6.5) is what keeps a hole one round trip
	 * wide instead of one backlog wide.
	 * <p>
	 * <b>Takes ownership</b> of {@code frame} on every path, exactly as {@link #enqueueFrame} does.
	 * Frames re-queued by successive calls end up in reverse order relative to each other, which is
	 * deliberate and harmless: what is re-queued here carries its own position (a {@code STREAM} frame
	 * its offset), unlike the {@code CRYPTO} stream the transport re-queues as an ordered list.
	 *
	 * @throws QuicTransportException {@code INTERNAL_ERROR} when the send queue's byte bound would be
	 *                                exceeded (FR-040)
	 */
	public void requeueFrame(QuicFrame frame) throws QuicTransportException {
		checkInReactorThread(this);
		if (frameHandler == null) {
			Recyclers.recycle(frame);
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"requeueFrame requires a frame handler registered at build time");
		}
		EncryptionLevel level = applicationSendLevel();
		try {
			FrameTypeRules.validateForSending(frame, level);
		} catch (QuicTransportException e) {
			Recyclers.recycle(frame);
			throw e;
		}
		sendQueue.requeue(level, List.of(frame), true);
	}

	/**
	 * Removes every frame still queued for transmission that {@code filter} accepts, recycling each one
	 * (spec FR-055) — the seam through which the layer above purges the frames belonging to state it has
	 * just discarded, so they are neither sent nor accounted against the send-queue bound.
	 * <p>
	 * The predicate is the <b>caller's</b>: this layer interprets no stream frame, and deciding which
	 * frames belong to a discarded stream is the stream layer's rule, not the transport's (FR-037).
	 * <p>
	 * Frames already handed to a packet are out of reach here, and correctly so: they are the loss
	 * recovery machinery's, which offers them back through {@link QuicFrameHandler#onFrameLost} for the
	 * handler to release.
	 *
	 * @return how many frames were removed
	 */
	public int dropQueuedFrames(Predicate<QuicFrame> filter) {
		checkInReactorThread(this);
		return sendQueue.removeIf(filter);
	}

	/**
	 * Queues one RFC 9221 DATAGRAM frame carrying {@code payload}, to leave in the next packet this
	 * connection builds (FR-075, FR-078).
	 * <p>
	 * <b>Takes ownership of {@code payload} on every path, refusals included</b> — it is recycled before
	 * this throws, and recycling it again at the call site is a double free. Does not send: call
	 * {@link #requestSend()} when a batch is ready, exactly as {@link #enqueueFrame} requires.
	 * <p>
	 * This is the entry point that carries the RFC 9221 policy. {@link #enqueueFrame} remains the raw
	 * seam and applies none of it, which is what leaves a test able to inject an illegal frame; a caller
	 * that means to send an unreliable datagram wants this one. No {@link QuicFrameHandler} is required:
	 * a DATAGRAM frame with no handler registered is recycled by the transport when its packet is
	 * acknowledged or declared lost, exactly as one with a handler is recycled by the handler.
	 * <p>
	 * The four refusals are resolved in a fixed order, and the peer's limit is read through the same
	 * 0-RTT funnel everything else uses ({@link #earlyTransportParameters()}), so a client may send a
	 * datagram in early data against the limit it remembered, and a server — which has been told nothing
	 * yet — correctly refuses until the handshake completes.
	 *
	 * @throws QuicDatagramException with {@link QuicDatagramException.Reason#CONNECTION_CLOSED},
	 *                               {@link QuicDatagramException.Reason#NOT_NEGOTIATED},
	 *                               {@link QuicDatagramException.Reason#OVERSIZE} or
	 *                               {@link QuicDatagramException.Reason#QUEUE_FULL}. Never a connection
	 *                               error: none of the four is the peer's fault, and none of the four
	 *                               closes anything
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221#section-5">RFC 9221 §5 — Behavior and Usage</a>
	 */
	public void sendDatagramFrame(ByteBuf payload) throws QuicDatagramException {
		checkInReactorThread(this);
		DatagramFrame frame = new DatagramFrame(payload);
		if (state.isTerminating() || sendQueue.isDropped()) {
			throw refuseDatagram(frame, QuicDatagramException.Reason.CONNECTION_CLOSED,
				"the connection is " + state + ", so nothing further will be sent");
		}
		long peerLimit = peerMaxDatagramFrameSize();
		if (peerLimit == 0) {
			throw refuseDatagram(frame, QuicDatagramException.Reason.NOT_NEGOTIATED,
				"the peer advertised no max_datagram_frame_size, so it supports no DATAGRAM frame at all");
		}
		int size = frame.encodedLength();
		if (size > peerLimit) {
			throw refuseDatagram(frame, QuicDatagramException.Reason.OVERSIZE,
				"a DATAGRAM frame of " + size + " bytes exceeds the peer's max_datagram_frame_size of " +
				peerLimit + "; RFC 9221 §3 forbids truncating it");
		}
		if (outboundDatagrams.size() >= settings.maxOutboundDatagrams()) {
			throw refuseDatagram(frame, QuicDatagramException.Reason.QUEUE_FULL,
				"maxOutboundDatagrams (" + settings.maxOutboundDatagrams() + ") datagrams are already " +
				"waiting for a packet");
		}
		outboundDatagrams.add(frame);
	}

	/**
	 * Releases {@code frame} and builds the refusal. Returned rather than thrown so the call site reads
	 * as a {@code throw}, which is what keeps the "every path recycles" rule visible where it applies.
	 */
	private QuicDatagramException refuseDatagram(DatagramFrame frame, QuicDatagramException.Reason reason,
		String message
	) {
		frame.recycle();
		datagramFramesRefused++;
		return new QuicDatagramException(reason, message);
	}

	/**
	 * The largest DATAGRAM frame the peer said it would accept (RFC 9221 §3), or 0 for "none" — which is
	 * also the answer before either parameter set exists.
	 */
	private long peerMaxDatagramFrameSize() {
		if (peerTransportParameters != null) return peerTransportParameters.maxDatagramFrameSize();
		QuicTransportParameters early = earlyTransportParameters();
		return early == null ? 0 : early.maxDatagramFrameSize();
	}

	/** Flushes whatever is queued. Safe to call when nothing is queued, and safe to call repeatedly. */
	public void requestSend() {
		checkInReactorThread(this);
		flush();
	}

	/**
	 * Test-only seam: building a packet the peer will genuinely authenticate needs the real send keys, and
	 * no test can derive 1-RTT keys from outside. Package-private, and never widened — exposing key
	 * material publicly would violate SI-6 however carefully the caller behaved.
	 */
	@Nullable QuicKeys sendKeysForTesting(EncryptionLevel level) {
		return keys.get(level).sendKeys();
	}

	/** Whether a {@link QuicFrameHandler} was registered at build time. */
	public boolean hasFrameHandler() {
		checkInReactorThread(this);
		return frameHandler != null;
	}

	// ---------------------------------------------------------------- diagnostics (T075, T076)

	/*
	 * One method per event, each doing exactly two things: a debug line named for the corresponding
	 * qlog event, and the Inspector notification (FR-034, FR-036). Keeping both in one place is what
	 * stops the two seams from drifting apart, and it is the only place that has to be audited for
	 * SI-6 — no method here may be passed key material, a ByteBuf or a frame payload. The connection's
	 * own identifier is deliberately absent from the log lines: `role` plus the logger's connection
	 * context is what a reader needs, and a connection ID in a log is a routing token.
	 */

	/** qlog {@code transport:packet_sent} (RFC 9002 §A.5 — {@code OnPacketSent}). */
	private void onPacketSentEvent(SentPacket sent) {
		logger.debug("qlog transport:packet_sent {} level={} pn={} size={} ack_eliciting={} in_flight={}",
			role, sent.level, sent.packetNumber, sent.sizeInBytes, sent.ackEliciting, sent.inFlight);
		if (inspector != null) {
			inspector.onPacketSent(this, sent.level, sent.packetNumber, sent.sizeInBytes, sent.ackEliciting);
		}
	}

	/** qlog {@code transport:packet_received} — authenticated and not a duplicate. */
	private void onPacketReceivedEvent(EncryptionLevel level, long packetNumber, int sizeInBytes) {
		logger.debug("qlog transport:packet_received {} level={} pn={} size={}",
			role, level, packetNumber, sizeInBytes);
		if (inspector != null) {
			inspector.onPacketReceived(this, level, packetNumber, sizeInBytes);
		}
	}

	/** qlog {@code recovery:packet_lost} (RFC 9002 §6.1). */
	private void onPacketLostEvent(SentPacket lost) {
		logger.debug("qlog recovery:packet_lost {} level={} pn={} size={}",
			role, lost.level, lost.packetNumber, lost.sizeInBytes);
		if (inspector != null) {
			inspector.onPacketLost(this, lost.level, lost.packetNumber, lost.sizeInBytes);
		}
	}

	/** qlog {@code recovery:metrics_updated} (RFC 9002 §5). */
	private void onMetricsUpdatedEvent() {
		logger.debug("qlog recovery:metrics_updated {} latest_rtt={} smoothed_rtt={} rtt_variance={} " +
					 "min_rtt={} pto_count={} congestion_window={} bytes_in_flight={}",
			role, rtt.latestRtt(), rtt.smoothedRtt(), rtt.rttVar(), rtt.minRtt(), ptoCount,
			congestion.congestionWindow(), congestion.bytesInFlight());
		if (inspector != null) {
			inspector.onMetricsUpdated(this, rtt);
		}
	}

	/**
	 * qlog {@code recovery:congestion_state_updated}, emitted only on an actual transition.
	 * <p>
	 * Called after every cluster of congestion-controller mutations rather than from inside the
	 * controller: the controller is a pure RFC 9002 §7 algorithm with no logger and no reactor, and
	 * comparing against the last reported value here means an event fires once per transition however
	 * many times the state was recomputed on the way.
	 */
	private void reportCongestionState() {
		NewRenoCongestionController.State current = congestion.state();
		if (current == reportedCongestionState) return;
		NewRenoCongestionController.State previous = reportedCongestionState;
		reportedCongestionState = current;
		logger.debug("qlog recovery:congestion_state_updated {} old={} new={} congestion_window={} " +
					 "ssthresh={} bytes_in_flight={}",
			role, previous, current, congestion.congestionWindow(), congestion.slowStartThreshold(),
			congestion.bytesInFlight());
		if (inspector != null) {
			inspector.onCongestionStateUpdated(this, previous, current);
		}
	}

	/**
	 * The single writer of {@link #state} past construction, so no transition can escape the event.
	 * A transition to the state already held is a no-op rather than a repeated event.
	 */
	private void transitionTo(QuicConnectionState next) {
		if (state == next) return;
		QuicConnectionState previous = state;
		state = next;
		logger.debug("{} connection state {} -> {}", role, previous, next);
		if (inspector != null) {
			inspector.onStateTransition(this, previous, next);
		}
	}

	// ---------------------------------------------------------------- accessors

	public QuicConnectionState state() {
		checkInReactorThread(this);
		return state;
	}

	public Role role() {
		checkInReactorThread(this);
		return role;
	}

	/**
	 * The immutable settings this connection was built with.
	 * <p>
	 * A {@link QuicFrameHandler} needs them: the six RFC 9000 §18.2 limits this endpoint
	 * <i>advertised</i> are what its own receive windows must be sized from — the peer's parameters,
	 * reachable through {@link #peerTransportParameters()}, only describe the send side — and the
	 * three local-only stream bounds are not on the wire at all. Exposing the value the connection
	 * already holds is what keeps a handler from being handed a second, possibly divergent, copy.
	 * <p>
	 * The returned object is immutable (its builder is one-shot), so this is a read-only view.
	 */
	public QuicConnectionSettings settings() {
		checkInReactorThread(this);
		return settings;
	}

	public InetSocketAddress remoteAddress() {
		checkInReactorThread(this);
		return remoteAddress;
	}

	public QuicConnectionId localConnectionId() {
		checkInReactorThread(this);
		return localConnectionId;
	}

	public QuicConnectionId peerConnectionId() {
		checkInReactorThread(this);
		return peerConnectionId;
	}

	public QuicConnectionId originalDestinationConnectionId() {
		checkInReactorThread(this);
		return originalDestinationConnectionId;
	}

	/** The peer's validated parameters, or {@code null} before the handshake completes. */
	public @Nullable QuicTransportParameters peerTransportParameters() {
		checkInReactorThread(this);
		return peerTransportParameters;
	}

	public @Nullable String negotiatedAlpn() {
		checkInReactorThread(this);
		return negotiatedAlpn;
	}

	/**
	 * The peer parameters this connection was built to resume against (RFC 9000 §7.4.1), or
	 * {@code null} when it is not a resumption attempt. Always {@code null} on a server.
	 */
	public @Nullable QuicTransportParameters rememberedTransportParameters() {
		checkInReactorThread(this);
		return rememberedTransportParameters;
	}

	/**
	 * The limits that bind <b>early data</b> right now, or {@code null} when there is no early data to
	 * bind — because the handshake has already supplied the real ones, or because no 0-RTT key is
	 * installed in the direction that matters.
	 * <p>
	 * The single funnel the layer above pulls through, deliberately a pull rather than a push: a
	 * callback would be a second way for the stream layer to learn its limits, and the two would have
	 * to agree forever. Once {@link #peerTransportParameters()} is non-null this returns {@code null},
	 * so a caller that consults it after establishment cannot accidentally keep using the remembered
	 * values.
	 * <p>
	 * The two roles answer differently because they know different things. A <b>client</b> sending
	 * 0-RTT has the parameters it remembered, and RFC 9000 §7.4.1 obliges it to obey exactly those. A
	 * <b>server</b> processing 0-RTT has been told nothing yet, so it answers
	 * {@link TransportParameterValidation#WITHOUT_SEND_CREDIT}: it may receive everything it
	 * advertised — which its own settings already describe — and may send nothing until the handshake
	 * completes.
	 */
	public @Nullable QuicTransportParameters earlyTransportParameters() {
		checkInReactorThread(this);
		if (peerTransportParameters != null) return null;
		if (role == Role.CLIENT) {
			return keys.get(EncryptionLevel.ZERO_RTT).acceptsSend() ? rememberedTransportParameters : null;
		}
		return keys.get(EncryptionLevel.ZERO_RTT).acceptsReceive()
			? TransportParameterValidation.WITHOUT_SEND_CREDIT
			: null;
	}

	/**
	 * Whether this handshake resumed a previous session — a pre-shared key was accepted, so no
	 * certificate was exchanged (RFC 8446 §2.2). {@code false} until the decision is taken, and never
	 * revoked afterwards.
	 * <p>
	 * Distinct from {@link #isEarlyDataAccepted()}: a resumed handshake that refuses early data reports
	 * {@code true} here and {@code false} there. Carries no ticket, key or binder material (SI-6).
	 */
	public boolean isSessionResumed() {
		checkInReactorThread(this);
		return sessionResumed;
	}

	/**
	 * Whether early data was accepted on this connection (RFC 8446 §4.2.10, RFC 9001 §4.6.1). A client
	 * reads it as "the 0-RTT packets I sent were taken"; a server as "the 0-RTT packets this peer sends
	 * are to be processed".
	 * <p>
	 * Rejection is signalled by omission and is never a handshake failure, so a client that offered
	 * early data and reached {@link #isHandshakeConfirmed()} with this still {@code false} was refused.
	 */
	public boolean isEarlyDataAccepted() {
		checkInReactorThread(this);
		return earlyDataAccepted;
	}

	/**
	 * Whether an application frame enqueued right now would leave in a <b>0-RTT</b> packet: a client
	 * whose 0-RTT send keys are installed and whose 1-RTT keys are not yet (RFC 9001 §4.6.1).
	 * <p>
	 * The one thing the stream layer needs in order to mark a stream as created in early data, exposed
	 * as this question rather than as the send level itself — the level is the send path's private
	 * decision, and a second caller reading it would be a second thing to keep in step with it.
	 * <p>
	 * False on a server always, and false again on a client the moment 1-RTT keys exist, whether the
	 * early data was accepted or refused.
	 */
	public boolean isSendingEarlyData() {
		checkInReactorThread(this);
		return applicationSendLevel() == EncryptionLevel.ZERO_RTT;
	}

	/** FR-005: the handshake is confirmed, so the Handshake level is gone for good. */
	public boolean isHandshakeConfirmed() {
		checkInReactorThread(this);
		return handshakeConfirmed;
	}

	public boolean isLevelDiscarded(EncryptionLevel level) {
		checkInReactorThread(this);
		return keys.get(level).isDiscarded();
	}

	/**
	 * Whether {@code level}'s keys exist in the direction(s) that level uses — both for the three
	 * bidirectional levels, the one direction this role owns for {@code ZERO_RTT}.
	 */
	public boolean isLevelInstalled(EncryptionLevel level) {
		checkInReactorThread(this);
		return keys.get(level).isEitherDirectionInstalled();
	}

	/** <b>UDP datagrams</b> put on the socket — never RFC 9221 DATAGRAM frames, which are counted apart. */
	public long datagramsSent() {
		checkInReactorThread(this);
		return datagramsSent;
	}

	/** <b>UDP datagrams</b> taken off the socket, several QUIC packets each at most. */
	public long datagramsReceived() {
		checkInReactorThread(this);
		return datagramsReceived;
	}

	/** RFC 9221 DATAGRAM <b>frames</b> placed in a packet. Several may share one UDP datagram. */
	public long datagramFramesSent() {
		checkInReactorThread(this);
		return datagramFramesSent;
	}

	/** RFC 9221 DATAGRAM <b>frames</b> accepted and routed to the frame handler. */
	public long datagramFramesReceived() {
		checkInReactorThread(this);
		return datagramFramesReceived;
	}

	/**
	 * RFC 9221 DATAGRAM <b>frames</b> dropped without an error: queued but unplaceable in the packet they
	 * were offered to (FR-078), or received with no frame handler to deliver them to.
	 */
	public long datagramFramesDropped() {
		checkInReactorThread(this);
		return datagramFramesDropped;
	}

	/** Sends refused by {@link #sendDatagramFrame(ByteBuf)} — not negotiated, oversize, or queue full. */
	public long datagramFramesRefused() {
		checkInReactorThread(this);
		return datagramFramesRefused;
	}

	/** Packets discarded without becoming a connection error: bad AEAD, unknown level, over the bound. */
	public long packetsDropped() {
		checkInReactorThread(this);
		return packetsDropped;
	}

	/**
	 * Whether the handshake deadline is still armed (FR-024). A deadline left armed past completion
	 * would tear down a working connection, so this is worth asserting directly rather than by draining
	 * the reactor — which a live connection's probe timer never lets a test do.
	 */
	public boolean isHandshakeDeadlineArmed() {
		checkInReactorThread(this);
		return handshakeDeadline != null;
	}

	/** Whether the RFC 9002 §6.2 loss/probe timer is armed. */
	public boolean isLossTimerArmed() {
		checkInReactorThread(this);
		return lossTimer != null;
	}

	/** Whether the RFC 9000 §10.1 idle timer is armed. */
	public boolean isIdleTimerArmed() {
		checkInReactorThread(this);
		return idleTimer != null;
	}

	/** Whether the opt-in RFC 9000 §10.1.2 keep-alive PING is armed. */
	public boolean isKeepAliveArmed() {
		checkInReactorThread(this);
		return keepAliveTimer != null;
	}

	/** Whether the RFC 9000 §10.2 closing or draining period is still running. */
	public boolean isClosingPeriodArmed() {
		checkInReactorThread(this);
		return closingTimer != null;
	}

	/**
	 * What the peer said when it closed, or {@code null} if it did not — this connection ended some
	 * other way, or has not ended (FR-031).
	 */
	public @Nullable PeerClose peerClose() {
		checkInReactorThread(this);
		return peerClose;
	}

	/** CONNECTION_CLOSE frames re-sent during the closing period (RFC 9000 §10.2.1). */
	public long closeResends() {
		checkInReactorThread(this);
		return closeResends;
	}

	/** The live RFC 9002 §7 window. Read-only; the connection owns every update. */
	public NewRenoCongestionController congestion() {
		checkInReactorThread(this);
		return congestion;
	}

	/** Unacknowledged packets whose data a probe timeout re-sent (RFC 9002 §6.2.4). Not losses. */
	public long probeRetransmits() {
		checkInReactorThread(this);
		return probeRetransmits;
	}

	/** Keep-alive PINGs sent (FR-025). Zero unless a keep-alive interval was configured. */
	public long keepAlivesSent() {
		checkInReactorThread(this);
		return keepAlivesSent;
	}

	/** The live RFC 9002 §5 estimate. Read-only; the connection owns every update. */
	public RttEstimator rtt() {
		checkInReactorThread(this);
		return rtt;
	}

	/** Consecutive probe timeouts with no acknowledgement of an ack-eliciting packet in between. */
	public int probeTimeoutCount() {
		checkInReactorThread(this);
		return ptoCount;
	}

	public long packetsLost() {
		checkInReactorThread(this);
		return packetsLost;
	}

	public long probesSent() {
		checkInReactorThread(this);
		return probesSent;
	}

	public int packetsAwaitingKeys() {
		checkInReactorThread(this);
		return awaitingKeys.size();
	}

	@Override
	public String toString() {
		return "QuicConnection{" + role + ", " + state + ", " + remoteAddress +
			", local=" + localConnectionId + ", peer=" + peerConnectionId + '}';
	}
}
