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

import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResponse;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.Http3Client;
import io.activej.promise.Promise;
import io.activej.quic.tls.TlsClientConfig;

import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * A minimal HTTP/3 client: performs one {@code GET} against a target that defaults to
 * {@code https://localhost:4433/} — the {@link Http3HelloWorld} server, so the example runs
 * offline — and is overridden by {@code args[0]}. Prints the response status, the negotiated
 * {@link HttpVersion} and the body length, and exits 0.
 * <p>
 * Trust: exactly the committed dev leaf ({@code dev-cert.pem} from this example's own resources,
 * see {@code src/main/resources/README.md}), configured through
 * {@link Http3Client.Builder#withTlsClientConfig} with a {@link X509TrustManager} that accepts
 * only that certificate. This is deliberately <b>not</b> {@code insecureTrustAll} — which would
 * also disable RFC 6125 hostname verification — and <b>not</b>
 * {@code withTlsEngineFactory} — a whole engine factory owns its own {@link TlsClientConfig} and
 * silently opts out of this client's resumption plumbing (research D9). With the trust manager the
 * client keeps building the config, so the hostname check against the target's authority stays
 * live: a request to a server that is not this dev identity fails the handshake.
 * <p>
 * DNS: the resolver short-circuits {@code localhost} and literal IPs via
 * {@link IDnsClient#resolveFromQuery} — no DNS server is needed for the default target. A
 * non-localhost target needs a real resolver (plug in {@code DnsClient} or {@code CachedDnsClient})
 * and its own trust configuration, exactly as the comment in {@code main} says.
 *
 * <h2>Run</h2>
 * With {@link Http3HelloWorld} running, from the repository root:
 * <pre>{@code
 * java -cp "examples/core/http3/target/examples-http3-6.0-SNAPSHOT.jar:$(cat examples/core/http3/target/cp.txt)" Http3ClientExample
 * }</pre>
 * where {@code target/cp.txt} is the module's runtime classpath (see {@link Http3HelloWorld}'s
 * Javadoc for the two-line generation), or simply:
 * <pre>{@code
 * mvn -q -P examples -pl examples/core/http3 exec:java -Dexec.mainClass=Http3ClientExample
 * }</pre>
 * Expect {@code status=200 version=HTTP_3_0 bodyBytes=18}. {@code HTTP_3_0} is the assertion —
 * a 200 over HTTP/1.1 or HTTP/2 means the client fell back and nothing was proved.
 * <p>
 * Against something else:
 * <pre>{@code
 * java -cp "examples/core/http3/target/examples-http3-6.0-SNAPSHOT.jar:$(cat examples/core/http3/target/cp.txt)" Http3ClientExample https://cloudflare-quic.com/
 * }</pre>
 * — note that a non-localhost target also needs a real {@code IDnsClient} and its own trust
 * configuration (this example trusts exactly the dev leaf).
 */
public final class Http3ClientExample {
	private static final String DEFAULT_TARGET = "https://localhost:4433/";

	private Http3ClientExample() {}

	public static void main(String[] args) throws Exception {
		String target = args.length > 0 ? args[0] : DEFAULT_TARGET;

		Eventloop eventloop = Eventloop.builder().withCurrentThread().build();

		Http3Client client = Http3Client.builder(eventloop, loopbackResolver())
			// the narrow seam: this client still builds the TlsClientConfig, so the resumption
			// plumbing stays wired; only the chain validator is replaced by "exactly the dev leaf"
			.withTlsClientConfig(config -> {
				try {
					config.withTrustManager(trustingLeaf(devLeaf()));
				} catch (Exception e) {
					throw new RuntimeException("Failed to load the dev certificate", e);
				}
			})
			.build();

		client.request(HttpRequest.get(target).build())
			.then(response -> {
				int status = response.getCode();
				HttpVersion version = response.getVersion();
				return response.loadBody()
					.whenResult(body -> {
						System.out.println("status=" + status + " version=" + version + " bodyBytes=" + body.readRemaining());
						body.recycle(); // loadBody() hands ownership of the buffer to the caller
					})
					.map($ -> null);
			})
			.whenComplete(($, e) -> {
				if (e != null) {
					System.err.println("REQUEST_FAILED: " + e);
					System.exit(1);
				}
				client.close();
			});

		eventloop.run(); // exits when the reactor has nothing left — i.e. after close() above
	}

	/**
	 * A resolver that answers {@code localhost} and IP literals out of the query itself
	 * ({@link IDnsClient#resolveFromQuery}) and fails anything else — the example's offline
	 * default target needs no DNS server. A deployment pointing at a real hostname replaces this
	 * with {@code DnsClient} / {@code CachedDnsClient}.
	 */
	private static IDnsClient loopbackResolver() {
		return new IDnsClient() {
			@Override
			public Promise<DnsResponse> resolve(DnsQuery query) {
				DnsResponse fromQuery = IDnsClient.resolveFromQuery(query);
				if (fromQuery != null) {
					return Promise.of(fromQuery);
				}
				return Promise.ofException(new IllegalArgumentException(
					"This example resolves localhost and IP literals only; resolve '" +
						query.getDomainName() + "' with a real IDnsClient"));
			}

			@Override
			public void close() {}
		};
	}

	/** Parses the committed dev leaf straight off this example's own classpath. */
	private static X509Certificate devLeaf() throws Exception {
		try (InputStream in = Http3ClientExample.class.getResourceAsStream("/dev-cert.pem")) {
			if (in == null) {
				throw new IllegalStateException("dev-cert.pem is missing from the example's classpath");
			}
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
		}
	}

	/** A trust manager accepting exactly {@code leaf} — the {@code Http3TestTls.trustingLeaf} shape. */
	private static X509TrustManager trustingLeaf(X509Certificate leaf) {
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
}
