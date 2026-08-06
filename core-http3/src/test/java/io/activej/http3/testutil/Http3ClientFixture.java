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

import io.activej.common.initializer.Initializer;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Client;
import io.activej.http3.Http3EarlyDataPolicy;
import io.activej.http3.Http3Server;
import io.activej.http3.Http3Settings;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.X509TrustManager;

import java.util.function.Function;

/**
 * A real {@link Http3Server} and a real {@link Http3Client} on opposite ends of one
 * {@link Http3WirePair} — the fixture every {@code Http3Client} test drives.
 * <p>
 * Both endpoints are the production components over {@link StubUdpSocket}s, so an assertion here is an
 * assertion about bytes that crossed a genuine QUIC connection. What is synthetic is the fabric, the
 * clock and the resolver: {@link StubDnsClient} maps every name onto the fixture's server address, so
 * {@link #HOST} and {@link #OTHER_HOST} are <b>two authorities served by one server</b> — which is what
 * makes "one connection per authority" testable without a second server.
 * <p>
 * Both names are SANs of the dev certificate, so RFC 6125 endpoint identification stays live for each.
 */
public final class Http3ClientFixture implements AutoCloseable {
	/** The port every authority in these tests carries; the fixture's server is the only thing bound. */
	public static final int PORT = Http3WirePair.SERVER_ADDRESS.getPort();

	/** A port nothing binds — the unreachable authority. */
	public static final int UNBOUND_PORT = PORT + 137;

	public static final String HOST = Http3TestTls.SERVER_NAME;

	/** A second authority, also a SAN of the dev certificate, resolving to the same server. */
	public static final String OTHER_HOST = "example.test";

	/** A name the dev certificate does <b>not</b> cover, so the RFC 6125 check refuses it. */
	public static final String UNCERTIFIED_HOST = "not-in-the-cert.test";

	private final ManualEventloop loop;
	private final StubDnsClient dns = new StubDnsClient();

	private AsyncServlet servlet = request -> HttpResponse.ok200().toPromise();
	private Http3Settings serverSettings = Http3Settings.create();
	private Http3Settings clientSettings = Http3Settings.create();
	private Http3EarlyDataPolicy serverEarlyDataPolicy = Http3EarlyDataPolicy.DEFAULT_POLICY;
	/**
	 * {@code null} means "let {@link Http3Client} build its own {@link io.activej.quic.tls.TlsClientConfig}
	 * and only hand it the dev trust manager" — which is what keeps the client's resumption plumbing live,
	 * since a whole engine factory opts out of it. Set, it replaces that entirely.
	 */
	private @Nullable Function<String, TlsEngineFactory> tlsEngineFactory;

	/** Applied after the dev trust manager, so a test may name a {@link TlsClientConfig} setting of its own. */
	private @Nullable Initializer<TlsClientConfig.Builder> tlsClientConfig;

	private @Nullable QuicSessionCache sessionCache;
	private Http3Server.@Nullable Inspector serverInspector;
	private Http3Client.@Nullable Inspector clientInspector;

	/** Set, it replaces the {@link Http3Server} this fixture would have built — see {@link #withServerFactory}. */
	private @Nullable Function<StubUdpSocket, AutoCloseable> serverFactory;

	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3Client client;

	public Http3ClientFixture(ManualEventloop loop) {
		this.loop = loop;
	}

	// ---------------------------------------------------------------- configuration, before start()

	public Http3ClientFixture withServlet(AsyncServlet servlet) {
		this.servlet = servlet;
		return this;
	}

	public Http3ClientFixture withServerSettings(Http3Settings serverSettings) {
		this.serverSettings = serverSettings;
		return this;
	}

	/** Replaces the server's safe-methods-only early-data policy (FR-065); the default one is used otherwise. */
	public Http3ClientFixture withServerEarlyDataPolicy(Http3EarlyDataPolicy serverEarlyDataPolicy) {
		this.serverEarlyDataPolicy = serverEarlyDataPolicy;
		return this;
	}

	public Http3ClientFixture withClientSettings(Http3Settings clientSettings) {
		this.clientSettings = clientSettings;
		return this;
	}

	/**
	 * Substitutes the client's per-authority TLS factory — the seam a certificate-failure test needs.
	 * <p>
	 * A factory supplied here <b>opts the client out of its resumption plumbing</b>, exactly as it does
	 * in production, so a 0-RTT test must leave this alone and let the fixture's default trust manager
	 * do its work.
	 */
	public Http3ClientFixture withTlsEngineFactory(Function<String, TlsEngineFactory> tlsEngineFactory) {
		this.tlsEngineFactory = tlsEngineFactory;
		return this;
	}

	/**
	 * Adds a {@link TlsClientConfig} initializer of the test's own, applied after the dev trust manager and
	 * therefore <b>after</b> whatever {@link io.activej.http3.Http3Client} itself put on that builder — which
	 * is what makes it the seam for asserting that a consumer's explicit bound still wins.
	 */
	public Http3ClientFixture withTlsClientConfig(Initializer<TlsClientConfig.Builder> tlsClientConfig) {
		this.tlsClientConfig = tlsClientConfig;
		return this;
	}

	/** The client's session-ticket store — the seam a 0-RTT test needs to survive a closed client. */
	public Http3ClientFixture withSessionCache(QuicSessionCache sessionCache) {
		this.sessionCache = sessionCache;
		return this;
	}

	/** Attaches the FR-062 statistics hook to the server; absent otherwise, exactly as in production. */
	public Http3ClientFixture withServerInspector(Http3Server.Inspector serverInspector) {
		this.serverInspector = serverInspector;
		return this;
	}

	/** Attaches the FR-062 statistics hook to the client; absent otherwise, exactly as in production. */
	public Http3ClientFixture withClientInspector(Http3Client.Inspector clientInspector) {
		this.clientInspector = clientInspector;
		return this;
	}

	/**
	 * Serves with a server the test builds itself, instead of the {@link Http3Server} this fixture would
	 * have built — the seam for a TLS configuration {@code Http3Server} has no builder call for, which
	 * {@code earlyDataEnabled(false)} is (see {@link Http3TestServer}). The client half is unchanged, so
	 * everything else a client test relies on — the pool, the resolver, the ticket store — still holds.
	 * <p>
	 * {@link #server()} then has nothing to report and throws. Whatever the factory returns is closed by
	 * {@link #close()}.
	 */
	public Http3ClientFixture withServerFactory(Function<StubUdpSocket, AutoCloseable> serverFactory) {
		this.serverFactory = serverFactory;
		return this;
	}

	/** Builds both endpoints and binds their sockets. Nothing is dialled — the client decides that. */
	public Http3ClientFixture start() {
		wire = new Http3WirePair(loop)
			.withServerFactory(serverFactory != null ? serverFactory : socket -> {
				Http3Server.Builder serverBuilder = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withSettings(serverSettings)
					.withEarlyDataPolicy(serverEarlyDataPolicy);
				if (serverInspector != null) serverBuilder.withInspector(serverInspector);
				server = serverBuilder.build();
				server.listen();
				return server;
			})
			.withClientFactory(socket -> {
				Http3Client.Builder clientBuilder = Http3Client.builder(reactor(), dns)
					.withSocket(socket)
					.withSettings(clientSettings);
				if (tlsEngineFactory != null) {
					clientBuilder.withTlsEngineFactory(tlsEngineFactory);
				} else {
					X509TrustManager devLeaf = Http3TestTls.trustingLeaf(Http3TestTls.devIdentity().leaf());
					clientBuilder.withTlsClientConfig(config -> {
						config.withTrustManager(devLeaf);
						if (tlsClientConfig != null) tlsClientConfig.initialize(config);
					});
				}
				if (sessionCache != null) clientBuilder.withSessionCache(sessionCache);
				if (clientInspector != null) clientBuilder.withInspector(clientInspector);
				return client = clientBuilder.build();
			})
			.connect();
		return this;
	}

	// ---------------------------------------------------------------- accessors

	public Http3Client client() {
		return require(client, "client");
	}

	public Http3Server server() {
		return require(server, "server");
	}

	public Http3WirePair wire() {
		return require(wire, "wire");
	}

	public StubDnsClient dns() {
		return dns;
	}

	public static String url(String host, String path) {
		return url(host, PORT, path);
	}

	public static String url(String host, int port, String path) {
		return "https://" + host + ":" + port + path;
	}

	// ---------------------------------------------------------------- driving

	/** Drives the exchange until {@code promise} completes, then asserts it succeeded. */
	public <T> T await(Promise<T> promise) {
		wire().driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the promise failed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	/** Drives the exchange until {@code promise} completes, then asserts it failed. */
	public Exception awaitException(Promise<?> promise) {
		wire().driveUntil(promise::isComplete);
		if (!promise.isException()) {
			throw new AssertionError("the promise was expected to fail but did not: " + promise);
		}
		return promise.getException();
	}

	@Override
	public void close() {
		if (wire != null) wire.close();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private static <T> T require(@Nullable T value, String what) {
		if (value == null) throw new IllegalStateException(what + " is not available — call start() first");
		return value;
	}
}
