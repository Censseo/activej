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
import io.activej.common.ApplicationSettings;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.inspector.BaseInspector;
import io.activej.net.socket.udp.IUdpSocket;
import io.activej.net.socket.udp.UdpPacket;
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.codec.VersionNegotiationPacket;
import io.activej.quic.connection.CoalescedPackets.Envelope;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.reactor.AbstractNioReactive;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * One UDP socket serving many QUIC connections, dispatched by destination connection ID (US2).
 * <p>
 * A QUIC server is not one-socket-per-connection: every connection on a port shares a single socket,
 * and the destination connection ID — not the source address — is what identifies the connection a
 * datagram belongs to (RFC 9000 §5.2). That is what makes connection migration possible and what
 * makes this class necessary.
 * <p>
 * <b>What may be read before dispatch.</b> Only the unprotected envelope: header form, version and
 * the connection IDs, via {@link CoalescedPackets#peek}. Everything else needs keys, and which keys
 * apply is precisely what the dispatch decides. A datagram that does not resolve to a connection and
 * does not qualify to create one is dropped — never answered, never logged at a level a peer can
 * flood (RFC 9000 §5.2, SI-3).
 * <p>
 * <b>Buffer ownership</b> (DI-1): the endpoint owns every {@link UdpPacket} it receives and either
 * hands its buffer to a connection or recycles it. Datagrams handed to
 * {@link IUdpSocket#send} are the socket's.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.2">RFC 9000 §5.2 — Matching Packets to Connections</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-8.1">RFC 9000 §8.1 — Address Validation During Connection Establishment</a>
 */
public final class QuicEndpoint extends AbstractNioReactive implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(QuicEndpoint.class);

	/**
	 * Live connections on one endpoint (SI-3). Reaching it drops new inbound connection attempts; it
	 * never affects an established connection.
	 */
	public static final int MAX_CONNECTIONS =
		ApplicationSettings.getInt(QuicEndpoint.class, "maxConnections", 10_000);

	/**
	 * Connections still handshaking (SI-3). A separate, much smaller bound than
	 * {@link #MAX_CONNECTIONS}, because a half-open connection costs a TLS engine and a key schedule
	 * for an unvalidated peer — which is the cheap half of the handshake for an attacker and the
	 * expensive half for us.
	 */
	public static final int MAX_HANDSHAKING_CONNECTIONS =
		ApplicationSettings.getInt(QuicEndpoint.class, "maxHandshakingConnections", 1_000);

	/** RFC 9000 §17.2.1: version 0 in a long header identifies a Version Negotiation packet. */
	private static final long VERSION_NEGOTIATION = 0;

	/**
	 * The endpoint's half of the FR-036 statistics hook: the four dispatch-level events, which belong to
	 * no single connection and so cannot be reported by {@link QuicConnection.Inspector}.
	 * <p>
	 * Carries no {@link ByteBuf} and nothing key-derived (SI-6). {@code from} is a peer address, which is
	 * routing data rather than a secret — but note it is <b>unauthenticated</b> on every event here
	 * except {@link #onConnectionCreated}, because dispatch happens before any packet is opened.
	 * <p>
	 * <b>Threading</b>: every callback runs on the endpoint's reactor thread, inside the receive loop.
	 *
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-5.2">RFC 9000 §5.2 — Matching Packets to Connections</a>
	 */
	public interface Inspector extends BaseInspector<Inspector> {
		/** Every datagram off the socket, before it is routed and before anything in it is trusted. */
		void onDatagramReceived(QuicEndpoint endpoint, InetSocketAddress from, int sizeInBytes);

		/**
		 * A datagram that resolved to no connection and did not qualify to create one — the counterpart
		 * of {@link #datagramsDropped()}. Deliberately not logged by the endpoint: it is peer-triggered
		 * and would otherwise be a log-flood channel (SI-3).
		 */
		void onDatagramDropped(QuicEndpoint endpoint, InetSocketAddress from, int sizeInBytes);

		/** An inbound Initial admitted as a new server connection, before its handshake starts. */
		void onConnectionCreated(QuicEndpoint endpoint, QuicConnection connection);

		/**
		 * An inbound connection attempt refused at {@link #MAX_CONNECTIONS} or
		 * {@link #MAX_HANDSHAKING_CONNECTIONS}.
		 */
		void onConnectionRefused(QuicEndpoint endpoint, InetSocketAddress from);
	}

	private final IUdpSocket socket;
	private final QuicConnectionSettings settings;
	private final @Nullable TlsEngineFactory serverEngineFactory;
	private final SecureRandom secureRandom;
	private final int maxConnections;
	private final int maxHandshakingConnections;
	private final @Nullable Inspector inspector;
	private final QuicConnection.@Nullable Inspector connectionInspector;
	private final @Nullable Function<QuicConnection, QuicFrameHandler> frameHandlerFactory;

	/**
	 * Every connection ID that routes to a connection. A server connection is reachable under two: the
	 * DCID the client invented (which the client keeps using until it sees our chosen one) and our own.
	 */
	private final Map<QuicConnectionId, QuicConnection> byConnectionId = new HashMap<>();
	/** The reverse of {@code byConnectionId}, so unregistering a connection does not scan every entry. */
	private final Map<QuicConnection, List<QuicConnectionId>> routedIds = new IdentityHashMap<>();
	private final Set<QuicConnection> connections = new LinkedHashSet<>();
	private final Set<QuicConnection> handshaking = new LinkedHashSet<>();

	private boolean listening;
	private boolean closed;

	private long datagramsReceived;
	private long datagramsDropped;
	private long connectionsAccepted;
	private long connectionsRejected;
	private long versionNegotiationsSent;

	private QuicEndpoint(Builder builder) {
		super(builder.reactor);
		this.socket = builder.socket;
		this.settings = builder.settings;
		this.serverEngineFactory = builder.serverEngineFactory;
		this.secureRandom = builder.secureRandom;
		this.maxConnections = builder.maxConnections;
		this.maxHandshakingConnections = builder.maxHandshakingConnections;
		this.inspector = builder.inspector;
		this.connectionInspector = builder.connectionInspector;
		this.frameHandlerFactory = builder.frameHandlerFactory;
	}

	public static Builder builder(NioReactor reactor, IUdpSocket socket) {
		return new QuicEndpoint.Builder(reactor, socket);
	}

	public static final class Builder extends AbstractBuilder<Builder, QuicEndpoint> {
		private final NioReactor reactor;
		private final IUdpSocket socket;

		private QuicConnectionSettings settings = QuicConnectionSettings.create();
		private @Nullable TlsEngineFactory serverEngineFactory;
		private SecureRandom secureRandom = new SecureRandom();
		private int maxConnections = MAX_CONNECTIONS;
		private int maxHandshakingConnections = MAX_HANDSHAKING_CONNECTIONS;
		private @Nullable Inspector inspector;
		private QuicConnection.@Nullable Inspector connectionInspector;
		private @Nullable Function<QuicConnection, QuicFrameHandler> frameHandlerFactory;

		private Builder(NioReactor reactor, IUdpSocket socket) {
			this.reactor = reactor;
			this.socket = socket;
		}

		public Builder withSettings(QuicConnectionSettings settings) {
			checkNotBuilt(this);
			this.settings = settings;
			return this;
		}

		/**
		 * Makes the endpoint able to <b>accept</b> connections. Without it the endpoint is client-only
		 * and an inbound Initial is dropped — a client that answered unsolicited Initials would be a
		 * reflection amplifier.
		 */
		public Builder withServerEngineFactory(TlsEngineFactory serverEngineFactory) {
			checkNotBuilt(this);
			this.serverEngineFactory = serverEngineFactory;
			return this;
		}

		public Builder withSecureRandom(SecureRandom secureRandom) {
			checkNotBuilt(this);
			this.secureRandom = secureRandom;
			return this;
		}

		public Builder withMaxConnections(int maxConnections) {
			checkNotBuilt(this);
			if (maxConnections < 1) {
				throw new IllegalArgumentException("maxConnections must be at least 1: " + maxConnections);
			}
			this.maxConnections = maxConnections;
			return this;
		}

		public Builder withMaxHandshakingConnections(int maxHandshakingConnections) {
			checkNotBuilt(this);
			if (maxHandshakingConnections < 1) {
				throw new IllegalArgumentException(
					"maxHandshakingConnections must be at least 1: " + maxHandshakingConnections);
			}
			this.maxHandshakingConnections = maxHandshakingConnections;
			return this;
		}

		/**
		 * Registers the dispatch-level statistics hook of FR-036. It observes only what this endpoint
		 * does; a connection's own events come from {@link QuicConnection.Builder#withInspector}.
		 */
		public Builder withInspector(Inspector inspector) {
			checkNotBuilt(this);
			this.inspector = inspector;
			return this;
		}

		/**
		 * Gives every connection this endpoint creates — accepted or dialled — the same
		 * {@link QuicConnection.Inspector}.
		 * <p>
		 * This is the only way to inspect an <b>accepted</b> connection at all: a server connection is
		 * built inside the dispatch, so there is no moment between its construction and its first packet
		 * in which a caller could attach anything to it.
		 */
		public Builder withConnectionInspector(QuicConnection.Inspector connectionInspector) {
			checkNotBuilt(this);
			this.connectionInspector = connectionInspector;
			return this;
		}

		/**
		 * Supplies the layer above the transport — streams, HTTP/3, DATAGRAM — for every connection this
		 * endpoint creates, both the ones it <b>accepts</b> and the ones {@link #connectTo} dials
		 * (FR-039). The factory is invoked exactly once per connection, at construction, before any frame
		 * is routed; see {@link QuicConnection.Builder#withFrameHandlerFactory} for what it may do with
		 * the connection it is handed.
		 * <p>
		 * As with {@link #withConnectionInspector}, this is the only way to reach an <b>accepted</b>
		 * connection at all: a server connection is built inside the dispatch, so there is no moment
		 * between its construction and its first packet in which a caller could attach anything to it.
		 * <p>
		 * Without it the endpoint behaves exactly as it did before this method existed: no handler, zero
		 * streams advertised, and an application frame is a {@code PROTOCOL_VIOLATION}. A factory that
		 * throws while accepting refuses that connection exactly as an over-limit attempt is refused; one
		 * that throws while dialling fails the {@link #connectTo} promise.
		 */
		public Builder withFrameHandlerFactory(Function<QuicConnection, QuicFrameHandler> frameHandlerFactory) {
			checkNotBuilt(this);
			this.frameHandlerFactory = frameHandlerFactory;
			return this;
		}

		@Override
		protected QuicEndpoint doBuild() {
			return new QuicEndpoint(this);
		}
	}

	// ---------------------------------------------------------------- lifecycle (T044)

	/** Starts the receive loop. Idempotent; a no-op once closed. */
	public void listen() {
		checkInReactorThread(this);
		if (listening || closed) return;
		listening = true;
		receiveLoop();
	}

	/**
	 * Drains the socket without recursing per datagram.
	 * <p>
	 * The loop matters: {@link IUdpSocket#receive()} completes synchronously whenever a datagram is
	 * already buffered, so the obvious {@code receive().whenResult(… -> receiveLoop())} recursion would
	 * grow the stack once per queued datagram rather than once per idle wait.
	 */
	private void receiveLoop() {
		while (!closed) {
			Promise<UdpPacket> promise = socket.receive();
			if (!promise.isComplete()) {
				promise.whenComplete((packet, e) -> {
					if (closed) {
						if (packet != null) packet.recycle();
						return;
					}
					if (e != null) {
						onReceiveFailure(e);
						return;
					}
					onPacket(packet);
					receiveLoop();
				});
				return;
			}
			if (promise.isException()) {
				onReceiveFailure(promise.getException());
				return;
			}
			onPacket(promise.getResult());
		}
	}

	private void onReceiveFailure(Exception e) {
		if (closed) return;
		// A UDP receive failure is the socket's, not any one connection's. Closing the endpoint is the
		// only honest response: there is no way to keep serving connections whose datagrams stop arriving.
		logger.warn("QUIC endpoint receive failed; closing the endpoint", e);
		close();
	}

	/**
	 * Closes every connection, then the socket. Idempotent (WI-9).
	 * <p>
	 * Connections are told first and individually: each gets the chance to put a CONNECTION_CLOSE on
	 * the wire while the socket is still open, rather than all of them discovering a dead socket.
	 */
	@Override
	public void close() {
		checkInReactorThread(this);
		if (closed) return;
		// `closed` is set *after* the loop, not before: sendDatagram drops everything once it is set, so
		// setting it first would silently recycle the very CONNECTION_CLOSE frames this method exists to
		// let out, and peers would learn of the shutdown only from their idle timeout. Re-entrancy is
		// still guarded — closeNow cannot reach close(), and the flag is set before the socket goes.
		//
		// A copy: closing a connection fires its onClosed hook, which mutates these collections.
		// closeNow rather than close: the socket is about to go, so no CONNECTION_CLOSE re-send could be
		// delivered anyway, and a closing period would hold buffers past the endpoint's own lifetime.
		for (QuicConnection connection : new ArrayList<>(connections)) {
			connection.closeNow();
		}
		closed = true;
		connections.clear();
		handshaking.clear();
		byConnectionId.clear();
		routedIds.clear();
		socket.close();
	}

	public boolean isClosed() {
		checkInReactorThread(this);
		return closed;
	}

	// ---------------------------------------------------------------- dispatch (T041, T042)

	/** Takes ownership of {@code packet}. */
	private void onPacket(UdpPacket packet) {
		ByteBuf datagram = packet.getBuf();
		InetSocketAddress from = packet.getSocketAddress();
		datagramsReceived++;
		if (inspector != null) {
			inspector.onDatagramReceived(this, from, datagram.readRemaining());
		}

		Envelope envelope = CoalescedPackets.peek(datagram, settings.connectionIdLength());
		if (envelope == null) {
			drop(from, datagram);
			return;
		}

		QuicConnection connection = byConnectionId.get(envelope.destinationConnectionId());
		if (connection != null) {
			// Ownership transfers to the connection, which recycles it on every path.
			connection.onDatagram(datagram);
			return;
		}
		if (!envelope.longHeader()) {
			// A short header with an unknown DCID: a late packet for a connection that is already gone,
			// or someone probing. RFC 9000 §10.3's stateless reset would answer it; that is out of scope
			// for this feature, so it is dropped in silence.
			drop(from, datagram);
			return;
		}
		acceptOrDrop(from, envelope, datagram);
	}

	/** Takes ownership of {@code datagram}. */
	private void acceptOrDrop(InetSocketAddress from, Envelope envelope, ByteBuf datagram) {
		if (serverEngineFactory == null) {
			// Client-only endpoint. Answering here would make us a reflector for anyone who can spoof a
			// source address.
			drop(from, datagram);
			return;
		}
		if (datagram.readRemaining() < PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE) {
			// RFC 9000 §14.1: a datagram carrying a client Initial is at least 1200 bytes. Enforcing it
			// here is what keeps the 3× anti-amplification budget from being computed off a tiny input —
			// and it is checked before the Version Negotiation answer below, because that answer is the one
			// place this endpoint replies to a datagram it has not authenticated at all.
			drop(from, datagram);
			return;
		}
		if (envelope.version() != QuicPackets.SUPPORTED_VERSION) {
			// RFC 9000 §6.1 MUST NOT: version 0 *is* a Version Negotiation packet, and answering one with
			// another is how two endpoints turn a spoofed datagram into an unbounded exchange between them.
			if (envelope.version() != VERSION_NEGOTIATION) {
				sendVersionNegotiation(from, envelope);
			}
			drop(from, datagram);
			return;
		}
		if (!envelope.isInitial()) {
			// RFC 9000 §7.2: only an Initial packet starts a connection. A Handshake or 0-RTT packet whose
			// DCID matched nothing belongs to a connection that is already gone — building a TLS engine and
			// a key schedule for it would fail on the first frame, having spent the cost an attacker wanted.
			drop(from, datagram);
			return;
		}
		QuicConnectionId clientScid = envelope.sourceConnectionId();
		if (clientScid == null) {
			drop(from, datagram);
			return;
		}
		if (byConnectionId.containsKey(envelope.destinationConnectionId())) {
			// The DCID of a client Initial is a value the client invented, so it can name a connection that
			// already exists. Accepting would displace that connection's routing entry; see register.
			drop(from, datagram);
			return;
		}
		if (connections.size() >= maxConnections || handshaking.size() >= maxHandshakingConnections) {
			// SI-3: at the bound we drop rather than queue. A queue of unvalidated peers is the resource
			// an attacker was after in the first place.
			connectionsRejected++;
			if (inspector != null) {
				inspector.onConnectionRefused(this, from);
			}
			drop(from, datagram);
			return;
		}

		QuicConnection connection = buildAcceptedOrRefuse(from, envelope, clientScid);
		if (connection == null) {
			drop(from, datagram);
			return;
		}

		// Reachable under both: the client keeps addressing the DCID it invented until it has seen our
		// chosen connection ID (RFC 9000 §7.2), and its Initial retransmissions will still carry it.
		if (!register(envelope.destinationConnectionId(), connection)
			|| !register(connection.localConnectionId(), connection)) {
			// Only reachable if our own SecureRandom repeated an 8-byte ID; the client's DCID was checked
			// above. Handled anyway, because a half-populated routing table is worse than a dropped
			// datagram. Unwinding is by connection, never by ID: an ID that collided names somebody else.
			// The connection was never started, so it holds no timer and no buffer.
			unregisterAll(connection);
			drop(from, datagram);
			return;
		}
		connections.add(connection);
		handshaking.add(connection);
		connectionsAccepted++;
		if (inspector != null) {
			inspector.onConnectionCreated(this, connection);
		}

		connection.start().whenComplete(($, e) -> handshaking.remove(connection));
		connection.onDatagram(datagram);
	}

	/**
	 * Builds the connection for an admitted client Initial, or {@code null} when it must be refused
	 * instead — which today means only that the frame-handler factory of FR-039 threw.
	 * <p>
	 * The caller still owns the datagram either way; the refusal path here touches only the counters.
	 * <p>
	 * Only {@link io.activej.quic.connection.QuicConnection.Builder#build()} runs inside the
	 * {@code try}, deliberately: it is the one call that invokes {@code frameHandlerFactory}, while
	 * every {@code withXxx} above it is this endpoint's own static configuration, supplied the same way
	 * for every accepted connection and never expected to throw. A bug in that configuration must
	 * propagate rather than be relabelled as "the factory failed" and silently rejected — a server that
	 * silently accepts nothing is the hardest failure there is to find.
	 */
	private @Nullable QuicConnection buildAcceptedOrRefuse(
		InetSocketAddress from, Envelope envelope, QuicConnectionId clientScid
	) {
		QuicConnection.Builder builder = QuicConnection.builder(
				reactor, Role.SERVER, this::sendDatagram, from, serverEngineFactory)
			.withSettings(settings)
			.withSecureRandom(secureRandom)
			.withPeerConnectionId(clientScid)
			.withOriginalDestinationConnectionId(envelope.destinationConnectionId())
			.withOnClosed(() -> unregister(envelope.destinationConnectionId()));
		if (connectionInspector != null) builder.withInspector(connectionInspector);
		if (frameHandlerFactory != null) builder.withFrameHandlerFactory(frameHandlerFactory);

		try {
			return builder.build();
		} catch (RuntimeException e) {
			// The layer above refused to be built for this connection. Refusing is the answer an
			// over-limit attempt gets, and for the same reason: a half-wired connection — one routing
			// frames into a handler that never finished being built — is worse than none. Nothing is left
			// to unwind, since the connection was never registered and never started.
			//
			// Logged, unlike a bound: a bound is peer-triggered and would be a log-flood channel, while a
			// factory that throws is our own bug, and a server that silently accepts nothing is the
			// hardest failure there is to find.
			logger.warn("The frame handler factory failed; refusing this connection", e);
			connectionsRejected++;
			if (inspector != null) {
				inspector.onConnectionRefused(this, from);
			}
			return null;
		}
	}

	/**
	 * RFC 9000 §6.1: answers a long-header packet in an unsupported version with the list of versions this
	 * endpoint does support — which is exactly one.
	 * <p>
	 * <b>The connection IDs are swapped.</b> The client's Source Connection ID becomes our Destination and
	 * its Destination becomes our Source, which is what lets the client match the answer to its Initial: a
	 * Version Negotiation packet is unauthenticated, and echoing the IDs is the only evidence it carries
	 * that it came from the path it claims.
	 * <p>
	 * This is the one reply sent to an unauthenticated datagram, so it is bounded on both sides: the
	 * caller has already required the RFC 9000 §14.1 minimum datagram size, and the answer is a few dozen
	 * bytes — well under the 3× the anti-amplification rule would allow.
	 */
	private void sendVersionNegotiation(InetSocketAddress to, Envelope envelope) {
		QuicConnectionId clientScid = envelope.sourceConnectionId();
		if (clientScid == null) {
			// A long header always carries one; without it there is nothing to address the answer to.
			return;
		}
		VersionNegotiationPacket vn = VersionNegotiationPacket.of(
			clientScid, envelope.destinationConnectionId(),
			new int[]{(int) QuicPackets.SUPPORTED_VERSION});
		ByteBuf out = ByteBufPool.allocate(QuicPackets.encodedLength(vn));
		QuicPackets.write(out, vn);
		versionNegotiationsSent++;
		logger.debug("Answering version 0x{} with a Version Negotiation packet",
			Long.toHexString(envelope.version()));
		sendDatagram(to, out);
	}

	/**
	 * Adds a routing entry, <b>refusing</b> to displace an existing one.
	 * <p>
	 * One of the two IDs a server connection answers to is the Destination Connection ID the client
	 * invented, so it is entirely peer-controlled. Overwriting on collision would let anyone who can
	 * guess or observe a live connection's ID make it unroutable by opening a new connection under it —
	 * a denial of service costing one datagram. Refusing costs the colliding newcomer instead.
	 *
	 * @return {@code false} when the ID already routes elsewhere and nothing was registered
	 */
	private boolean register(QuicConnectionId connectionId, QuicConnection connection) {
		QuicConnection existing = byConnectionId.putIfAbsent(connectionId, connection);
		if (existing == connection) return true;
		if (existing != null) {
			logger.debug("Refusing to route a connection ID that is already in use");
			return false;
		}
		routedIds.computeIfAbsent(connection, $ -> new ArrayList<>(2)).add(connectionId);
		return true;
	}

	/**
	 * Removes every routing entry pointing at the connection registered under {@code anyOfItsIds}.
	 * <p>
	 * The IDs are looked up rather than found by scanning {@code byConnectionId}: a scan is O(all live
	 * connections) per close, which turns shutting down a full endpoint into a quadratic sweep.
	 */
	private void unregister(QuicConnectionId anyOfItsIds) {
		QuicConnection connection = byConnectionId.get(anyOfItsIds);
		if (connection == null) return;
		unregisterAll(connection);
	}

	/** Removes {@code connection} and every routing entry it owns — and only the entries it owns. */
	private void unregisterAll(QuicConnection connection) {
		List<QuicConnectionId> ids = routedIds.remove(connection);
		if (ids != null) {
			for (QuicConnectionId id : ids) {
				byConnectionId.remove(id);
			}
		}
		connections.remove(connection);
		handshaking.remove(connection);
	}

	/** Takes ownership of {@code datagram}. The one place an unroutable datagram is accounted for. */
	private void drop(InetSocketAddress from, ByteBuf datagram) {
		datagramsDropped++;
		if (inspector != null) {
			inspector.onDatagramDropped(this, from, datagram.readRemaining());
		}
		datagram.recycle();
	}

	// ---------------------------------------------------------------- send path (T043)

	/** Takes ownership of {@code datagram}. The per-connection amplification budget is applied upstream. */
	private void sendDatagram(InetSocketAddress to, ByteBuf datagram) {
		if (closed) {
			datagram.recycle();
			return;
		}
		socket.send(UdpPacket.of(datagram, to));
	}

	// ---------------------------------------------------------------- outbound (T045)

	/**
	 * Opens a client connection over this endpoint's socket and starts its handshake.
	 *
	 * @param clientEngineFactory supplies the TLS client engine; the connection supplies the transport
	 *                            parameters it is given
	 */
	public Promise<QuicConnection> connectTo(InetSocketAddress address, TlsEngineFactory clientEngineFactory) {
		return connectTo(address, clientEngineFactory, null);
	}

	/**
	 * The same, for a <b>resumption</b> attempt: {@code rememberedTransportParameters} are the peer
	 * parameters stored with the session ticket the TLS client config is offering (RFC 9000 §7.4.1).
	 * <p>
	 * They are what bounds every byte sent in 0-RTT and what the server's new parameters are checked
	 * against for a forbidden reduction, so a caller offering a ticket without them would be offering
	 * a resumption it has no limits for. {@code null} is the ordinary, non-resuming dial.
	 */
	public Promise<QuicConnection> connectTo(InetSocketAddress address, TlsEngineFactory clientEngineFactory,
		@Nullable QuicTransportParameters rememberedTransportParameters
	) {
		checkInReactorThread(this);
		if (closed) {
			return Promise.ofException(new QuicTransportException(
				QuicTransportErrors.NO_ERROR, "The QUIC endpoint is closed"));
		}
		if (connections.size() >= maxConnections) {
			return Promise.ofException(new QuicTransportException(QuicTransportErrors.INTERNAL_ERROR,
				"The QUIC endpoint is at its " + maxConnections + "-connection limit"));
		}
		QuicConnectionId[] localId = new QuicConnectionId[1];
		QuicConnection connection;
		try {
			connection = QuicConnection.builder(
					reactor, Role.CLIENT, this::sendDatagram, address, clientEngineFactory)
				.withSettings(settings)
				.withSecureRandom(secureRandom)
				.withOnClosed(() -> unregister(localId[0]))
				.initialize(builder -> {
					if (connectionInspector != null) builder.withInspector(connectionInspector);
					if (frameHandlerFactory != null) builder.withFrameHandlerFactory(frameHandlerFactory);
					if (rememberedTransportParameters != null) {
						builder.withRememberedTransportParameters(rememberedTransportParameters);
					}
				})
				.build();
		} catch (RuntimeException e) {
			// A frame-handler factory that threw (FR-039). Reported through the promise, like every other
			// way this method can refuse: a caller that already handles a refused dial should not need a
			// second, synchronous failure path for the same outcome. Nothing was registered.
			return Promise.ofException(e);
		}
		localId[0] = connection.localConnectionId();

		// A client is addressed by the connection ID it advertised, which the server will use as the
		// DCID of everything it sends back.
		register(connection.localConnectionId(), connection);
		connections.add(connection);
		handshaking.add(connection);

		// listen() is idempotent, and a client that never reads would hang on its own handshake.
		listen();
		Promise<QuicConnection> established = connection.start();
		established.whenComplete(($, e) -> handshaking.remove(connection));
		return established;
	}

	// ---------------------------------------------------------------- accessors

	public int connectionCount() {
		checkInReactorThread(this);
		return connections.size();
	}

	public int handshakingConnectionCount() {
		checkInReactorThread(this);
		return handshaking.size();
	}

	public int routingEntryCount() {
		checkInReactorThread(this);
		return byConnectionId.size();
	}

	public @Nullable QuicConnection connectionOf(QuicConnectionId connectionId) {
		checkInReactorThread(this);
		return byConnectionId.get(connectionId);
	}

	public long datagramsReceived() {
		checkInReactorThread(this);
		return datagramsReceived;
	}

	/** Datagrams that resolved to no connection and did not qualify to create one. */
	public long datagramsDropped() {
		checkInReactorThread(this);
		return datagramsDropped;
	}

	public long connectionsAccepted() {
		checkInReactorThread(this);
		return connectionsAccepted;
	}

	/** Version Negotiation packets emitted for unsupported versions (RFC 9000 §6.1). */
	public long versionNegotiationsSent() {
		checkInReactorThread(this);
		return versionNegotiationsSent;
	}

	/** Inbound attempts refused at {@link #MAX_CONNECTIONS} or {@link #MAX_HANDSHAKING_CONNECTIONS}. */
	public long connectionsRejected() {
		checkInReactorThread(this);
		return connectionsRejected;
	}

	public boolean canAccept() {
		checkInReactorThread(this);
		return serverEngineFactory != null;
	}

	@Override
	public String toString() {
		return "QuicEndpoint{" + connections.size() + " connections, " + handshaking.size() +
			" handshaking" + (closed ? ", closed" : "") + '}';
	}
}
