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

import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.tls.QuicTls;
import io.activej.quic.tls.TlsClientConfig;
import io.activej.quic.tls.TlsServerConfig;
import io.activej.quic.tls.TlsServerIdentity;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * The dev TLS identity an {@link Http3WirePair} handshakes with, plus the two {@link TlsEngineFactory}
 * shapes {@code QuicEndpoint} asks for.
 * <p>
 * Everything here is {@code core-quic}'s <b>main</b>-scope API ({@code io.activej.quic.tls}); only the
 * PEM files themselves are test-scope resources over there, so they are copied into this module's own
 * {@code src/test/resources/io/activej/http3/testutil/} rather than reached through a {@code test-jar}
 * edge (research Decision 12 — see the README next to them).
 * <p>
 * The client trusts <b>exactly the dev leaf</b> rather than trusting all, so RFC 6125 hostname
 * verification stays live: a harness that disabled it would let an {@code Http3Client} regression in
 * server-name handling pass unnoticed.
 */
public final class Http3TestTls {
	/** The name the dev certificate is issued for; its SANs cover {@code localhost} and {@code example.test}. */
	public static final String SERVER_NAME = "localhost";

	/** Cached: parsing a key per test is measurable, and {@link TlsServerIdentity} is immutable and shared by design. */
	private static TlsServerIdentity cachedIdentity;

	private Http3TestTls() {}

	/**
	 * The dev ECDSA identity. P-256 signing is the cheapest fixture {@code core-quic} ships, which
	 * matters when a suite runs a handshake per test method.
	 */
	public static TlsServerIdentity devIdentity() {
		if (cachedIdentity == null) {
			try {
				cachedIdentity = TlsServerIdentity.fromPem(fixture("ecdsa-cert.pem"), fixture("ecdsa-key.pem"));
			} catch (IOException e) {
				throw new AssertionError("Failed to load the dev ECDSA certificate fixture", e);
			}
		}
		return cachedIdentity;
	}

	/** The TLS factory a server endpoint needs, using the dev ECDSA identity. */
	public static TlsEngineFactory serverEngineFactory() {
		TlsServerIdentity identity = devIdentity();
		return params -> QuicTls.serverEngine(TlsServerConfig.builder(identity, params).build());
	}

	/** The TLS factory a client needs, trusting exactly the dev leaf so hostname checks stay live. */
	public static TlsEngineFactory clientEngineFactory(String serverName) {
		TlsServerIdentity identity = devIdentity();
		return params -> QuicTls.clientEngine(TlsClientConfig.builder(serverName, params)
			.withTrustManager(trustingLeaf(identity.leaf()))
			.build());
	}

	public static TlsEngineFactory clientEngineFactory() {
		return clientEngineFactory(SERVER_NAME);
	}

	/**
	 * A trust manager accepting exactly {@code leaf} — unlike {@code TlsClientConfig}'s
	 * {@code insecureTrustAll}, which would also disable the endpoint-identification check.
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

	/** Resolves a fixture from {@code /io/activej/http3/testutil/} on the test classpath. */
	public static Path fixture(String name) {
		try {
			URL resource = Http3TestTls.class.getResource("/io/activej/http3/testutil/" + name);
			if (resource == null) throw new AssertionError(name + " fixture is not on the classpath");
			return Path.of(resource.toURI());
		} catch (URISyntaxException e) {
			throw new AssertionError(e);
		}
	}
}
