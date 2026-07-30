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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

	/** The order packets are coalesced in: lowest level first (RFC 9000 §12.2). */
	private static final EncryptionLevel[] LEVEL_ORDER = {
		EncryptionLevel.INITIAL, EncryptionLevel.HANDSHAKE, EncryptionLevel.ONE_RTT};

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
	private final EnumMap<EncryptionLevel, PacketNumberSpace> spaces = new EnumMap<>(EncryptionLevel.class);
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
	private final @Nullable QuicFrameHandler frameHandler;
	private QuicConnectionState state = QuicConnectionState.IDLE;
	private @Nullable ScheduledRunnable handshakeDeadline;
	private @Nullable QuicTransportParameters peerTransportParameters;
	private @Nullable String negotiatedAlpn;
	private boolean handshakeConfirmed;

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

	private long datagramsSent;
	private long datagramsReceived;
	private long packetsDropped;
	private long packetsLost;
	private long probesSent;
	private long closeResends;
	private long keepAlivesSent;
	private long probeRetransmits;

	private QuicConnection(Builder builder) {
		super(builder.reactor);
		this.role = builder.role;
		this.onClosed = builder.onClosed;
		this.frameHandler = builder.frameHandler;
		this.sink = builder.sink;
		this.remoteAddress = builder.remoteAddress;
		this.settings = builder.settings;
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
		this.amplification = role == Role.SERVER
			? AmplificationBudget.forServer()
			: AmplificationBudget.validated();

		for (EncryptionLevel level : EncryptionLevel.values()) {
			keys.put(level, new LevelKeys(level));
			spaces.put(level, new PacketNumberSpace(level, settings.maxAckRanges()));
			cryptoIn.put(level, new CryptoStreamAssembler(settings.maxCryptoBufferBytes()));
			cryptoOutOffset.put(level, 0L);
		}

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

		@Override
		protected QuicConnection doBuild() {
			if (role == Role.SERVER) {
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
			return new QuicConnection(this);
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
		state = QuicConnectionState.HANDSHAKING;
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
	 * Processes one received datagram. <b>Takes ownership</b> of {@code datagram} and recycles it on
	 * every path.
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
				// 0-RTT is never offered by this implementation, so a 0-RTT packet cannot be ours.
				default -> {
					packetsDropped++;
					packet.bytes().recycle();
				}
			}
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
		if (!levelKeys.isInstalled()) {
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
			&& spaces.get(EncryptionLevel.INITIAL).largestReceived() == PacketNumberSpace.NONE;
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
			restartAfterRetry(serverScid, retry.retryToken());
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

		PacketNumberSpace initial = spaces.get(EncryptionLevel.INITIAL);
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
		PacketNumberSpace space = spaces.get(level);
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
			if (level == EncryptionLevel.ONE_RTT && ackEliciting) {
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
		if (isToleratedTransportFrame(frame)) {
			// Frames that concern paths and limits we do not use. RFC 9000 §12.4 permits ignoring them, and
			// a real peer sends several of them unprompted — NEW_CONNECTION_ID in particular — so treating
			// them as violations would break interoperability rather than enforce anything.
			return;
		}
		routeToHandler(level, frame);
	}

	/**
	 * Frames the transport neither acts on nor rejects.
	 * <p>
	 * The distinction from the frames below matters: these concern facilities this feature does not
	 * implement but a conforming peer still uses (extra connection IDs, path validation, flow-control
	 * credit we never spend). The frames that fall through to the handler are the ones carrying
	 * <i>application</i> data, which a peer may not send at all given the zero stream limits and absent
	 * DATAGRAM support we advertise.
	 */
	private static boolean isToleratedTransportFrame(QuicFrame frame) {
		return frame instanceof NewConnectionIdFrame
			|| frame instanceof RetireConnectionIdFrame
			|| frame instanceof NewTokenFrame
			|| frame instanceof PathChallengeFrame
			|| frame instanceof PathResponseFrame
			|| frame instanceof MaxDataFrame
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
		PacketNumberSpace space = spaces.get(level);
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
		}
		if (ackedAckEliciting) {
			// RFC 9002 §6.2.1: an acknowledgement of an ack-eliciting packet proves the path is working,
			// so the exponential probe backoff starts over.
			ptoCount = 0;
		}
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
					frameHandler.onFrameAcknowledged(this, frame);
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
		LossDetector.Detection detection = LossDetector.detectLost(spaces.get(level), now, rtt);
		if (detection.lost().isEmpty()) return;

		long lostBytes = 0;
		long newestLostSentTime = 0;
		long oldestLostSentTime = Long.MAX_VALUE;
		boolean anyInFlight = false;
		for (SentPacket lost : detection.lost()) {
			packetsLost++;
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
				frameHandler.onFrameLost(this, frame);
				continue;
			}
			boolean retransmittable = levelKeys.accepts()
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
		if (level != EncryptionLevel.ONE_RTT) return true;
		return ackNowOneRtt || space.ackElicitingReceivedSinceAck() >= ACK_ELICITING_THRESHOLD;
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
		} catch (MalformedDataException e) {
			throw transportParameterErrorFor(e);
		}
		applyTlsResult(result);
	}

	private void applyTlsResult(TlsEngineResult result) throws QuicTransportException {
		// Installations come in firing order (Handshake, then 1-RTT) and must be applied before the
		// CRYPTO output that depends on them can be sent.
		for (KeyInstallation installation : result.keysToInstall()) {
			TlsKeys installed = installation.keys();
			keys.get(installation.level()).install(
				role == Role.CLIENT ? installed.clientKeys() : installed.serverKeys(),
				role == Role.CLIENT ? installed.serverKeys() : installed.clientKeys());
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
		this.peerTransportParameters = peer;
		this.negotiatedAlpn = result.negotiatedAlpn();

		if (role == Role.SERVER) {
			// FR-005: the server signals confirmation to the client, and confirms itself once the frame
			// is actually on the wire (see sendDatagram).
			sendQueue.enqueue(EncryptionLevel.ONE_RTT, HandshakeDoneFrame.INSTANCE, false);
		}

		state = QuicConnectionState.ESTABLISHED;
		cancelHandshakeDeadline();
		// Both depend on the peer's parameters, so this is the earliest point either can be right.
		armIdleTimer();
		armKeepAliveTimer();
		logger.debug("{} handshake complete, ALPN {}", role, negotiatedAlpn);
		if (frameHandler != null) {
			frameHandler.onEstablished(this);
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
	 */
	private void discardLevel(EncryptionLevel level) {
		LevelKeys levelKeys = keys.get(level);
		if (levelKeys.isDiscarded()) return;
		levelKeys.discard();
		PacketNumberSpace space = spaces.get(level);
		// RFC 9002 §B.9: the bytes of a discarded space leave the in-flight count without any window
		// reduction — they are neither lost nor acknowledged. Skipping this leaves a connection
		// permanently congestion-blocked by handshake packets it will never hear about again.
		long inFlight = 0;
		for (SentPacket packet : space.sentPackets().values()) {
			if (packet.inFlight) inFlight += packet.sizeInBytes;
		}
		congestion.onSpaceDiscarded(inFlight);
		space.discard();
		cryptoIn.get(level).close();
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
				if (!sendDatagram()) {
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

	/** Builds and sends at most one datagram; returns whether anything is still pending afterwards. */
	private boolean sendDatagram() throws QuicTransportException {
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
		LevelKeys levelKeys = keys.get(level);
		if (!levelKeys.accepts()) return;

		// The packet number is not known until we commit to sending, so the allowance is computed
		// against the widest possible encoding. Under-filling is harmless; the opposite would produce a
		// datagram over the path's limit.
		int allowance = limit - plan.used - packetOverhead(level, 4);
		if (allowance <= 0) return;

		PacketNumberSpace space = spaces.get(level);
		List<QuicFrame> frames = new ArrayList<>();
		int frameBytes = 0;

		if (shouldAck(space, level)) {
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
	}

	private static boolean containsHandshakeDone(List<QuicFrame> frames) {
		for (QuicFrame frame : frames) {
			if (frame instanceof HandshakeDoneFrame) return true;
		}
		return false;
	}

	private boolean hasPendingWork() {
		boolean congestionLimited = congestion.isBlocked() && !bypassCongestionWindow;
		for (EncryptionLevel level : LEVEL_ORDER) {
			if (!keys.get(level).accepts()) continue;
			// Queued data over the window is not "pending" for this flush: claiming it were would spin the
			// flush loop to its bound on every call until an ACK arrived.
			if (!congestionLimited && sendQueue.hasPending(level)) return true;
			if (shouldAck(spaces.get(level), level)) return true;
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
		return 1                                                     // first byte
			+ 4                                                      // version
			+ 1 + peerConnectionId.length()                          // DCID length + DCID
			+ 1 + localConnectionId.length()                         // SCID length + SCID
			+ (level == EncryptionLevel.INITIAL                      // Token Length varint + token
				? QuicVarInts.encodedLength(retryToken.length) + retryToken.length : 0)
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
		if (level == null || !keys.get(level).accepts()) return;
		// Incremented before sending: the next timeout must already be the doubled one, or a persistently
		// black-holed path would be probed at a fixed interval forever (RFC 9002 §6.2.1).
		ptoCount++;
		probesSent++;

		// RFC 9002 §7: the probe leaves regardless of the window.
		bypassCongestionWindow = true;
		boolean carriesData = false;
		for (EncryptionLevel candidate : LEVEL_ORDER) {
			if (!keys.get(candidate).accepts()) continue;
			carriesData |= requeueOldestUnacknowledged(spaces.get(candidate));
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
	 * Deliberately <b>not</b> counted as a loss: a probe timeout is not evidence that anything was lost
	 * (RFC 9002 §6.2), and counting it would misreport the path and — once Phase 7 lands — halve a
	 * congestion window for a packet that may still be in flight. The packet is removed from the space
	 * because ownership of its frames passes to the queue; a duplicate arriving later is harmless, since
	 * the receiver de-duplicates CRYPTO by offset.
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
		requeueLost(oldest);
		return sendQueue.hasPending(space.level());
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

		state = QuicConnectionState.DRAINING;
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
	 * Enters the closing state: tells the peer why if there are keys to tell it under, then holds that
	 * CONNECTION_CLOSE for three probe timeouts so a peer that retransmits gets an answer
	 * (RFC 9000 §10.2.1).
	 * <p>
	 * The establishment promise fails <i>now</i>, not at the end of the period — the caller has no
	 * reason to wait out a timeout for an answer already known.
	 */
	private void closeWith(QuicTransportException e) {
		if (state.isTerminating() || state == QuicConnectionState.CLOSED) {
			return;
		}
		boolean wasOpen = state != QuicConnectionState.IDLE;
		state = QuicConnectionState.CLOSING;
		// Everything except the keys and the close frame goes now: nothing in flight can still be
		// usefully acknowledged, and holding a send queue through the closing period would be a leak
		// measured in a whole PTO.
		releaseForClosing();

		if (wasOpen && highestSendableLevel() != null) {
			closingFrame = ConnectionCloseFrame.transport(
				e.errorCode(), e.frameType() == null ? 0 : e.frameType(), NO_TOKEN);
			sendConnectionClose();
		}
		armClosingTimer();
		establishPromise.trySetException(e);
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
		PacketNumberSpace space = spaces.get(level);
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
		for (EncryptionLevel level : EncryptionLevel.values()) {
			spaces.get(level).abandonOutstanding();
			cryptoIn.get(level).close();
		}
		sendQueue.drop();
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
		for (EncryptionLevel level : EncryptionLevel.values()) {
			// Recycles the frames of every unacknowledged packet, and the buffered CRYPTO stream.
			discardLevel(level);
		}
	}

	/** The single transition into {@code CLOSED}, so the {@code onClosed} hook can only fire once. */
	private void markClosed() {
		if (state == QuicConnectionState.CLOSED) return;
		state = QuicConnectionState.CLOSED;
		if (frameHandler != null) {
			frameHandler.onClosed(this);
		}
		if (onClosed != null) {
			onClosed.run();
		}
	}

	private void cancelHandshakeDeadline() {
		ScheduledRunnable deadline = handshakeDeadline;
		if (deadline != null) {
			handshakeDeadline = null;
			deadline.cancel();
		}
	}

	private @Nullable EncryptionLevel highestSendableLevel() {
		for (int i = LEVEL_ORDER.length - 1; i >= 0; i--) {
			if (keys.get(LEVEL_ORDER[i]).accepts()) {
				return LEVEL_ORDER[i];
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
		// The exponent is validated at or below 20 (TransportParameterValidation), so this cannot overflow.
		return (ackDelayField << exponent) / 1000;
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
	 */
	public void enqueueFrame(QuicFrame frame) throws QuicTransportException {
		checkInReactorThread(this);
		if (frameHandler == null) {
			Recyclers.recycle(frame);
			throw new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"enqueueFrame requires a frame handler registered at build time");
		}
		try {
			FrameTypeRules.validateForSending(frame, EncryptionLevel.ONE_RTT);
		} catch (QuicTransportException e) {
			// Ownership had already transferred, so the frame is ours to release — SendQueue.enqueue does
			// the same on its own rejection path, and a caller cannot be expected to guess which of the two
			// refused it.
			Recyclers.recycle(frame);
			throw e;
		}
		sendQueue.enqueue(EncryptionLevel.ONE_RTT, frame, true);
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

	// ---------------------------------------------------------------- accessors

	public QuicConnectionState state() {
		checkInReactorThread(this);
		return state;
	}

	public Role role() {
		checkInReactorThread(this);
		return role;
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

	/** FR-005: the handshake is confirmed, so the Handshake level is gone for good. */
	public boolean isHandshakeConfirmed() {
		checkInReactorThread(this);
		return handshakeConfirmed;
	}

	public boolean isLevelDiscarded(EncryptionLevel level) {
		checkInReactorThread(this);
		return keys.get(level).isDiscarded();
	}

	public boolean isLevelInstalled(EncryptionLevel level) {
		checkInReactorThread(this);
		return keys.get(level).isInstalled();
	}

	public long datagramsSent() {
		checkInReactorThread(this);
		return datagramsSent;
	}

	public long datagramsReceived() {
		checkInReactorThread(this);
		return datagramsReceived;
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
