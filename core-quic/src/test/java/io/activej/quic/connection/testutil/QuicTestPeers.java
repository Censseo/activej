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

package io.activej.quic.connection.testutil;

import io.activej.common.time.CurrentTimeProvider;
import io.activej.eventloop.Eventloop;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.quic.tls.TlsServerConfig;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.test.time.TestCurrentTimeProvider;
import io.activej.test.time.TestCurrentTimeProvider.SettableCurrentTimeProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * Shared fixture for connection-layer tests: a hand-driven clock, the feature-02 dev certificates, and
 * a loopback pair of {@link LossyUdpSocket}s over one {@link DatagramNetwork}.
 * <p>
 * <b>Time never moves on its own.</b> The clock is a {@link SettableCurrentTimeProvider}, so any code
 * waiting on a timer will wait forever unless the test calls {@link #setTime} or {@link #advance}.
 * A promise that depends on a timer will therefore <i>hang</i> rather than fail if the test forgets —
 * that is the most confusing failure mode this fixture can produce, so reach for {@code advance} first
 * when a test appears to stall.
 * <p>
 * The caller still declares {@code EventloopRule}, {@code ByteBufRule} and {@code ActivePromisesRule}
 * as {@code @ClassRule}s: nesting them here would silently disable leak detection.
 * <p>
 * In-process tests should use the synthetic addresses below rather than {@code getFreePort()} — nothing
 * is bound to the OS, so a real port is neither needed nor free of races under {@code -T1C}. Reserve
 * {@code getFreePort()} for the genuinely-bound interop tests.
 */
public final class QuicTestPeers implements AutoCloseable {
	/** The dev keystore fixture's password, per {@code src/test/resources/io/activej/quic/tls/README.md}. */
	private static final char[] KEYSTORE_PASSWORD = "activej-test".toCharArray();

	public static final InetSocketAddress CLIENT_ADDRESS = new InetSocketAddress("127.0.0.1", 40001);
	public static final InetSocketAddress SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 40002);

	/** Cached: parsing a key per test is measurable, and {@link TlsServerIdentity} is immutable and shared by design. */
	private static TlsServerIdentity cachedEcdsaIdentity;
	private static TlsServerIdentity cachedRsaIdentity;

	private final SettableCurrentTimeProvider clock;
	private final DatagramNetwork network;
	private final LossyUdpSocket clientSocket;
	private final LossyUdpSocket serverSocket;

	private long now;

	private QuicTestPeers(long startMillis, long seed) {
		this.now = startMillis;
		this.clock = TestCurrentTimeProvider.settable(TestCurrentTimeProvider.ofConstant(startMillis));
		this.network = new DatagramNetwork(clock, seed);
		this.clientSocket = new LossyUdpSocket(network, CLIENT_ADDRESS);
		this.serverSocket = new LossyUdpSocket(network, SERVER_ADDRESS);
	}

	public static QuicTestPeers loopback() {
		return new QuicTestPeers(1_000_000, 1);
	}

	public static QuicTestPeers loopback(long startMillis, long seed) {
		return new QuicTestPeers(startMillis, seed);
	}

	// ---------------------------------------------------------------- clock

	/**
	 * A clock the test drives by hand.
	 * <p>
	 * Note the API: {@code TestCurrentTimeProvider.settable} takes a {@link CurrentTimeProvider}, not a
	 * timestamp, and the mutator is {@code setTimeProvider} — there is no {@code setTime(long)}. That is
	 * why {@link #setTime} exists, so no test has to know.
	 */
	public static SettableCurrentTimeProvider settableClock(long startMillis) {
		return TestCurrentTimeProvider.settable(TestCurrentTimeProvider.ofConstant(startMillis));
	}

	/** An {@code Eventloop.Builder} whose timestamps come from {@code clock}, making timers testable (FR-039). */
	public static Eventloop.Builder eventloopBuilder(CurrentTimeProvider clock) {
		return Eventloop.builder().withTimeProvider(clock);
	}

	public SettableCurrentTimeProvider clock() {
		return clock;
	}

	public long currentTimeMillis() {
		return now;
	}

	/** Moves the clock to an absolute time and delivers any datagram that has come due. */
	public void setTime(long millis) {
		if (millis < now) throw new IllegalArgumentException("Time must not go backwards: " + millis + " < " + now);
		now = millis;
		clock.setTimeProvider(TestCurrentTimeProvider.ofConstant(millis));
		network.deliverDue();
	}

	/** Moves the clock forward and delivers any datagram that has come due. */
	public void advance(long deltaMillis) {
		setTime(now + deltaMillis);
	}

	/** Delivers whatever is already due without moving the clock. */
	public int pump() {
		return network.deliverDue();
	}

	// ---------------------------------------------------------------- certificates

	/**
	 * The dev ECDSA identity. P-256 signing is the cheapest of the three fixtures, which matters when a
	 * suite runs many handshakes.
	 */
	public static TlsServerIdentity devIdentity() {
		if (cachedEcdsaIdentity == null) {
			cachedEcdsaIdentity = loadPem("ecdsa");
		}
		return cachedEcdsaIdentity;
	}

	public static TlsServerIdentity devRsaIdentity() {
		if (cachedRsaIdentity == null) {
			cachedRsaIdentity = loadPem("rsa");
		}
		return cachedRsaIdentity;
	}

	/** The KeyStore route, so that path has a fixture too. */
	public static TlsServerIdentity devKeystoreIdentity() {
		try (InputStream in = openFixture("rsa-keystore.p12")) {
			// Alias "server", password "activej-test" — see the fixture README.
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(in, KEYSTORE_PASSWORD);
			return TlsServerIdentity.fromKeyStore(keyStore, "server", KEYSTORE_PASSWORD);
		} catch (Exception e) {
			throw new AssertionError("Failed to load the dev keystore fixture", e);
		}
	}

	private static TlsServerIdentity loadPem(String keyType) {
		try {
			return TlsServerIdentity.fromPem(fixture(keyType + "-cert.pem"), fixture(keyType + "-key.pem"));
		} catch (IOException e) {
			throw new AssertionError("Failed to load the dev " + keyType + " certificate fixture", e);
		}
	}

	/**
	 * Resolves a feature-02 test fixture from {@code /io/activej/quic/tls/}.
	 * <p>
	 * Duplicated from {@code TlsServerIdentityTest.fixture} because that helper is package-private in
	 * {@code io.activej.quic.tls} and this fixture lives in a different package.
	 */
	public static Path fixture(String name) {
		try {
			URL resource = QuicTestPeers.class.getResource("/io/activej/quic/tls/" + name);
			if (resource == null) throw new AssertionError(name + " fixture is not on the classpath");
			return Path.of(resource.toURI());
		} catch (URISyntaxException e) {
			throw new AssertionError(e);
		}
	}

	private static InputStream openFixture(String name) {
		InputStream in = QuicTestPeers.class.getResourceAsStream("/io/activej/quic/tls/" + name);
		if (in == null) throw new AssertionError(name + " fixture is not on the classpath");
		return in;
	}

	// ---------------------------------------------------------------- TLS configuration

	/**
	 * A client config that trusts exactly the dev certificate's leaf, so <b>hostname verification stays
	 * live</b> — unlike {@code insecureTrustAll}, which would disable the RFC 6125 check this stack is
	 * supposed to be exercising.
	 * <p>
	 * Duplicated from {@code TlsClientEngineTest.trustingLeaf} for the same package-visibility reason as
	 * {@link #fixture}.
	 */
	public static X509TrustManager trustingLeaf(X509Certificate leaf) {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				throw new CertificateException("Client authentication is not used");
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				if (chain.length == 0 || !chain[0].equals(leaf)) {
					throw new CertificateException("Untrusted server chain");
				}
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}

	/**
	 * A client config builder trusting the dev identity, for {@code localhost}.
	 * <p>
	 * The transport parameters are the caller's: the identification parameters
	 * ({@code initial_source_connection_id}, {@code original_destination_connection_id}) are owned by the
	 * connection layer, so this fixture deliberately does not set them.
	 */
	public static TlsClientConfig.Builder clientConfig(
		String serverName, io.activej.quic.tls.QuicTransportParameters localParams, TlsServerIdentity identity
	) {
		return TlsClientConfig.builder(serverName, localParams)
			.withTrustManager(trustingLeaf(identity.leaf()));
	}

	public static TlsServerConfig.Builder serverConfig(
		TlsServerIdentity identity, io.activej.quic.tls.QuicTransportParameters localParams
	) {
		return TlsServerConfig.builder(identity, localParams);
	}

	// ---------------------------------------------------------------- sockets

	public DatagramNetwork network() {
		return network;
	}

	public LossyUdpSocket clientSocket() {
		return clientSocket;
	}

	public LossyUdpSocket serverSocket() {
		return serverSocket;
	}

	public InetSocketAddress clientAddress() {
		return CLIENT_ADDRESS;
	}

	public InetSocketAddress serverAddress() {
		return SERVER_ADDRESS;
	}

	/** Closes both sockets, then the fabric, recycling anything still in flight. Idempotent. */
	@Override
	public void close() {
		clientSocket.close();
		serverSocket.close();
		network.close();
	}
}
