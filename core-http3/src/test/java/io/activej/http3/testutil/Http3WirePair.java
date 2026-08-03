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

import io.activej.http3.Http3Settings;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One client {@link QuicEndpoint} and one server {@link QuicEndpoint}, handshaken over
 * {@link StubDatagramNetwork} in process — the transport every HTTP/3 protocol test drives.
 * <p>
 * <b>What is real and what is not.</b> Real: the endpoints, the connections, the TLS 1.3 handshake
 * (against the dev ECDSA identity of {@link Http3TestTls}), the packet protection, loss detection,
 * congestion control, the stream layer and its flow control. Not real: the UDP socket and the clock.
 * So an assertion here is an assertion about bytes that genuinely crossed a QUIC connection, with no
 * loopback port to race for and no wall clock to sleep on.
 * <p>
 * <b>Local to {@code core-http3} on purpose.</b> {@code core-quic} has a richer version of this
 * ({@code QuicEndpointFixture} over a seeded lossy {@code DatagramNetwork}), but it is test-scope in
 * another module and reaching it would mean a {@code test-jar} edge that publishes that module's test
 * internals for a convenience — rejected by research Decision 12. The loss/reorder/duplicate machinery
 * is not re-created here because reliable delivery is feature 03/04's tested responsibility, not
 * something an HTTP/3 test should be re-proving.
 * <p>
 * <b>Nothing moves on its own.</b> {@link #pump()} delivers datagrams, {@link #advance} moves the
 * shared clock and lets timers fire, {@link #driveUntil} alternates the two until a condition holds.
 * A test that forgets to drive sees a promise stay pending forever rather than a flaky pass.
 * <p>
 * Typical use:
 * <pre>{@code
 * loop = new ManualEventloop();
 * wire = new Http3WirePair(loop)
 *     .withServerStreamListener(acceptedStreams::add)
 *     .connect();
 *
 * QuicStream stream = wire.openNow(wire.clientStreams().openBidirectional());
 * Promise<Void> written = ChannelSuppliers.ofValue(buf).streamTo(stream.writer());
 * wire.driveUntil(written::isComplete);
 * }</pre>
 * ... and in {@code @After}: {@code wire.close(); loop.tickUntilQuiet(); loop.close();}
 */
public final class Http3WirePair implements AutoCloseable {
	/** Synthetic: nothing binds to the OS, so a real free port would be a race under {@code -T1C}. */
	public static final InetSocketAddress SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 40300);

	public static final InetSocketAddress CLIENT_ADDRESS = new InetSocketAddress("127.0.0.1", 40301);

	/** How many delivery rounds {@link #pump()} runs before it declares the exchange non-terminating. */
	private static final int MAX_PUMP_ROUNDS = 64;

	/** How many pump/advance rounds {@link #driveUntil} runs before it fails the test. */
	private static final int MAX_DRIVE_ROUNDS = 400;

	/** The clock step {@link #driveUntil} takes between delivery rounds; enough to fire a probe timeout. */
	private static final long STEP_MILLIS = 5;

	private final ManualEventloop loop;
	private final StubDatagramNetwork network = new StubDatagramNetwork();

	private QuicConnectionSettings serverSettings = QuicConnectionSettings.create();
	private QuicConnectionSettings clientSettings = QuicConnectionSettings.create();
	private Consumer<QuicStream> serverStreamListener = stream -> {};
	private Consumer<QuicStream> clientStreamListener = stream -> {};
	private @Nullable Function<QuicConnection, QuicFrameHandler> serverHandlerFactory;
	private @Nullable Function<QuicConnection, QuicFrameHandler> clientHandlerFactory;
	private @Nullable Function<StubUdpSocket, AutoCloseable> serverFactory;
	private @Nullable Function<StubUdpSocket, AutoCloseable> clientFactory;
	private String serverName = Http3TestTls.SERVER_NAME;

	private @Nullable StubUdpSocket serverSocket;
	private @Nullable StubUdpSocket clientSocket;
	private @Nullable QuicEndpoint serverEndpoint;
	private @Nullable QuicEndpoint clientEndpoint;
	private @Nullable QuicConnection serverConnection;
	private @Nullable QuicConnection clientConnection;
	private @Nullable QuicStreamManager serverStreams;
	private @Nullable QuicStreamManager clientStreams;
	private @Nullable AutoCloseable externalServer;
	private @Nullable AutoCloseable externalClient;

	private boolean connected;

	/**
	 * @param loop the eventloop whose hand-driven clock this pair shares. Sharing it is what makes a
	 *             retransmission, a probe timeout or an idle timeout reachable from {@link #advance} —
	 *             an eventloop on the system clock would leave every transport timer on real time.
	 */
	public Http3WirePair(ManualEventloop loop) {
		this.loop = loop;
	}

	// ---------------------------------------------------------------- configuration, before connect()

	public Http3WirePair withServerSettings(QuicConnectionSettings settings) {
		checkNotConnected();
		this.serverSettings = settings;
		return this;
	}

	public Http3WirePair withClientSettings(QuicConnectionSettings settings) {
		checkNotConnected();
		this.clientSettings = settings;
		return this;
	}

	/**
	 * What the server does with a stream its peer opened. Invoked exactly once per stream, before any
	 * byte of it is readable, so a reader attached inside the listener cannot miss the first slice.
	 */
	public Http3WirePair withServerStreamListener(Consumer<QuicStream> listener) {
		checkNotConnected();
		this.serverStreamListener = listener;
		return this;
	}

	/** The same, for streams the <b>server</b> opens — the control and QPACK streams, in HTTP/3 terms. */
	public Http3WirePair withClientStreamListener(Consumer<QuicStream> listener) {
		checkNotConnected();
		this.clientStreamListener = listener;
		return this;
	}

	/**
	 * Replaces the default per-connection {@link QuicStreamManager} on the server side with a handler of
	 * the test's own — an {@code Http3Connection}, once one exists.
	 * <p>
	 * {@link #serverStreams()} then reports whatever the factory returned if it is a
	 * {@code QuicStreamManager}, and throws otherwise: a handler that is not one has no stream layer to
	 * hand out.
	 */
	public Http3WirePair withServerHandlerFactory(Function<QuicConnection, QuicFrameHandler> factory) {
		checkNotConnected();
		this.serverHandlerFactory = factory;
		return this;
	}

	/** The client-side counterpart of {@link #withServerHandlerFactory}. */
	public Http3WirePair withClientHandlerFactory(Function<QuicConnection, QuicFrameHandler> factory) {
		checkNotConnected();
		this.clientHandlerFactory = factory;
		return this;
	}

	/**
	 * Hands the server-side {@link StubUdpSocket} to the test instead of building a {@link QuicEndpoint}
	 * over it — for a component that owns its own endpoint, which {@code Http3Server} does.
	 * <p>
	 * A frame-handler factory is a <b>build-time</b> property of {@code QuicEndpoint}, so a server that
	 * installs one cannot be attached to an endpoint somebody else already built; the socket is the
	 * lowest seam both sides can share. The factory is invoked before the client dials, and whatever it
	 * returns is closed by {@link #close()}.
	 * <p>
	 * {@link #serverEndpoint()} and {@link #serverStreams()} then have nothing to report and throw.
	 */
	public Http3WirePair withServerFactory(Function<StubUdpSocket, AutoCloseable> serverFactory) {
		checkNotConnected();
		this.serverFactory = serverFactory;
		return this;
	}

	/**
	 * The client-side counterpart of {@link #withServerFactory} — for a component that owns its own
	 * {@link QuicEndpoint} and dials on its own schedule, which {@code Http3Client} does.
	 * <p>
	 * With one installed, {@link #connect()} builds the server and hands the client socket over, and
	 * <b>dials nothing</b>: opening a connection is the client's decision, not the fixture's. So
	 * {@link #clientConnection()}, {@link #clientEndpoint()} and {@link #clientStreams()} have nothing to
	 * report and throw; {@link #pump()}, {@link #advance} and {@link #driveUntil} work unchanged, and are
	 * how a test drives the handshake the client starts. Whatever the factory returns is closed by
	 * {@link #close()}.
	 */
	public Http3WirePair withClientFactory(Function<StubUdpSocket, AutoCloseable> clientFactory) {
		checkNotConnected();
		this.clientFactory = clientFactory;
		return this;
	}

	/** The name the client verifies the server certificate against; the dev identity covers {@code localhost}. */
	public Http3WirePair withServerName(String serverName) {
		checkNotConnected();
		this.serverName = serverName;
		return this;
	}

	// ---------------------------------------------------------------- the handshake

	/**
	 * Builds both endpoints, dials the server and drives the exchange until the handshake completes.
	 * Fails the test rather than returning half-connected.
	 */
	public Http3WirePair connect() {
		checkNotConnected();
		connected = true;

		serverSocket = new StubUdpSocket(network, SERVER_ADDRESS);
		if (serverFactory != null) {
			externalServer = serverFactory.apply(serverSocket);
		} else {
			serverEndpoint = QuicEndpoint.builder(reactor(), serverSocket)
				.withSettings(serverSettings)
				.withServerEngineFactory(Http3TestTls.serverEngineFactory())
				// The endpoint-level factory is the only seam that reaches an ACCEPTED connection: a server
				// connection is built inside QuicEndpoint.acceptOrDrop, so QuicConnection.Builder never sees it.
				.withFrameHandlerFactory(connection -> {
					serverConnection = connection;
					QuicFrameHandler handler = serverHandlerFactory != null ?
						serverHandlerFactory.apply(connection) :
						streamManager(connection, serverStreamListener);
					if (handler instanceof QuicStreamManager manager) serverStreams = manager;
					return handler;
				})
				.build();
			serverEndpoint.listen();
		}

		clientSocket = new StubUdpSocket(network, CLIENT_ADDRESS);
		if (clientFactory != null) {
			externalClient = clientFactory.apply(clientSocket);
			return this;
		}
		clientEndpoint = QuicEndpoint.builder(reactor(), clientSocket)
			.withSettings(clientSettings)
			.withFrameHandlerFactory(connection -> {
				QuicFrameHandler handler = clientHandlerFactory != null ?
					clientHandlerFactory.apply(connection) :
					streamManager(connection, clientStreamListener);
				if (handler instanceof QuicStreamManager manager) clientStreams = manager;
				return handler;
			})
			.build();

		Promise<QuicConnection> connecting =
			clientEndpoint.connectTo(SERVER_ADDRESS, Http3TestTls.clientEngineFactory(serverName));
		driveUntil(connecting::isComplete);
		if (!connecting.isResult()) {
			throw new AssertionError("the QUIC handshake did not complete: " + connecting, connecting.getException());
		}
		clientConnection = connecting.getResult();
		return this;
	}

	private static QuicStreamManager streamManager(QuicConnection connection, Consumer<QuicStream> listener) {
		return QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
			.withStreamListener(listener)
			.build();
	}

	// ---------------------------------------------------------------- accessors

	public StubDatagramNetwork network() {
		return network;
	}

	public ManualEventloop loop() {
		return loop;
	}

	public QuicEndpoint serverEndpoint() {
		return require(serverEndpoint, "serverEndpoint");
	}

	public QuicEndpoint clientEndpoint() {
		return require(clientEndpoint, "clientEndpoint");
	}

	public StubUdpSocket serverSocket() {
		return require(serverSocket, "serverSocket");
	}

	public StubUdpSocket clientSocket() {
		return require(clientSocket, "clientSocket");
	}

	/** The accepted server-side connection — where {@code peerTransportParameters()} shows the client's values. */
	public QuicConnection serverConnection() {
		return require(serverConnection, "serverConnection");
	}

	/** The dialled client-side connection. */
	public QuicConnection clientConnection() {
		return require(clientConnection, "clientConnection");
	}

	public QuicStreamManager serverStreams() {
		return require(serverStreams, "serverStreams");
	}

	public QuicStreamManager clientStreams() {
		return require(clientStreams, "clientStreams");
	}

	// ---------------------------------------------------------------- driving the exchange

	/**
	 * Delivers datagrams both ways until nothing more is queued, without moving the clock.
	 * <p>
	 * Delivery re-enters the endpoints' receive loops, which usually produce more datagrams, hence the
	 * loop rather than a single call.
	 */
	public void pump() {
		for (int round = 0; round < MAX_PUMP_ROUNDS; round++) {
			if (network.deliverDue() == 0) return;
		}
		throw new AssertionError("the exchange did not settle within " + MAX_PUMP_ROUNDS + " delivery rounds");
	}

	/**
	 * Moves the clock forward, then lets everything that became due run: first the connections' own
	 * timers, then delivery, then the timers again — a retransmission is a timer that produces a
	 * datagram, and a delivery is a datagram that arms a timer, so one pass of each is not enough.
	 */
	public void advance(long deltaMillis) {
		loop.setTime(loop.currentTimeMillis() + deltaMillis);
		loop.tickUntilQuiet();
		pump();
		loop.tickUntilQuiet();
	}

	/** Alternates delivery and clock steps until {@code done} holds; fails the test if it never does. */
	public void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			pump();
			loop.tickUntilQuiet();
			if (done.getAsBoolean()) return;
			advance(STEP_MILLIS);
			if (done.getAsBoolean()) return;
		}
		throw new AssertionError("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	/**
	 * The result of a stream open on an established connection, which resolves synchronously unless a
	 * stream-count limit withholds it.
	 */
	public QuicStream openNow(Promise<QuicStream> opened) {
		if (!opened.isComplete()) {
			throw new AssertionError("the open is still pending — a stream-count limit is withholding it");
		}
		if (!opened.isResult()) {
			throw new AssertionError("the open failed: " + opened.getException(), opened.getException());
		}
		return opened.getResult();
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * Closes both endpoints (and, with them, their sockets), then the fabric — which recycles anything
	 * still queued, so {@code ByteBufRule} blames a real leak rather than an unfinished exchange.
	 * Idempotent.
	 */
	@Override
	public void close() {
		if (clientEndpoint != null) clientEndpoint.close();
		if (serverEndpoint != null) serverEndpoint.close();
		closeExternal(externalClient, "client");
		closeExternal(externalServer, "server");
		// An Http3Server closes gracefully (FR-019): it announces GOAWAY and defers closing its endpoint
		// until the exchanges it announced finish or its shutdown timeout fires. Nothing else moves the
		// clock at teardown, so this does — without it, a connection held open by a request nobody will
		// ever answer would still be holding buffers when ByteBufRule counts them. A pair whose server was
		// configured with a longer shutdown timeout than the default has to drive its own drain.
		loop.setTime(loop.currentTimeMillis() + Http3Settings.DEFAULT_SHUTDOWN_TIMEOUT.toMillis() + 1);
		loop.tickUntilQuiet();
		// Endpoints close their own sockets; this covers a socket built without one.
		if (clientSocket != null) clientSocket.close();
		if (serverSocket != null) serverSocket.close();
		network.close();
	}

	private static void closeExternal(@Nullable AutoCloseable external, String what) {
		if (external == null) return;
		try {
			external.close();
		} catch (Exception e) {
			throw new AssertionError("the externally-built " + what + " failed to close", e);
		}
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private void checkNotConnected() {
		if (connected) throw new IllegalStateException("Http3WirePair is already connected");
	}

	private static <T> T require(@Nullable T value, String what) {
		if (value == null) {
			throw new IllegalStateException(what + " is not available — call connect() first" +
											", and note that a custom handler factory may not create one");
		}
		return value;
	}

	@Override
	public String toString() {
		return "Http3WirePair{" + network + (connected ? ", connected" : ", not connected") + '}';
	}
}
