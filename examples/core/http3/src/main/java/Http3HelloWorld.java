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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launchers.http3.Http3ServerLauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ten-line HTTP/3 hello-world: an {@link Http3ServerLauncher} subclass that serves a fixed
 * body on {@code /} at {@code https://localhost:4433/}. That is the whole program — the launcher
 * wires the {@code Eventloop}, the {@code Http3Server}, {@code Config} and the service graph, and
 * its lifecycle closes the server (GOAWAY drain) and the reactor on shutdown (FR-021).
 * <p>
 * Runs with <b>no arguments</b>. The dev identity ({@code dev-cert.pem} / {@code dev-key.pem},
 * see {@code src/main/resources/README.md}) is loaded from this example's own resources: {@code main}
 * extracts it to a temporary directory and sets the standard {@code config.http3.certificateChain}
 * / {@code config.http3.privateKey} system properties — the very keys {@code -Dconfig.*} overrides,
 * and the same precedence applies (programmatic defaults → {@code http3-server.properties} on the
 * classpath → system properties). An explicit {@code -Dconfig.http3.certificateChain=…} on the
 * command line is respected and replaces the dev identity.
 *
 * <h2>Run</h2>
 * Build once (installs the new {@code activej-launchers-http3} and its peers into the local
 * repository so the examples resolve them):
 * <pre>{@code
 * mvn -P examples -pl examples/core/http3 -am install -DskipTests
 * }</pre>
 * Then either (a) let Maven run it with the module's exact classpath:
 * <pre>{@code
 * mvn -q -P examples -pl examples/core/http3 exec:java -Dexec.mainClass=Http3HelloWorld
 * }</pre>
 * or (b) assemble the classpath yourself — the built jar plus the module's runtime dependencies:
 * <pre>{@code
 * mvn -q -P examples -pl examples/core/http3 dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile=target/cp.txt
 * java -cp "examples/core/http3/target/examples-http3-6.0-SNAPSHOT.jar:$(cat examples/core/http3/target/cp.txt)" Http3HelloWorld
 * }</pre>
 * (both commands assume the repository root as the working directory). Expect the log line
 * {@code HTTP/3 Server is now available at https://localhost:4433}.
 *
 * <h2>Prove it speaks HTTP/3</h2>
 * The version is the assertion — a 200 over HTTP/1.1 or HTTP/2 means the client fell back and
 * nothing was proved:
 * <pre>{@code
 * curl --http3 -k -sS -w '\nVER=%{http_version} ST=%{http_code}\n' https://localhost:4433/
 * }</pre>
 * Expect {@code VER=3 ST=200}. ({@code -k} trusts the self-signed dev certificate; an HTTP/3 curl
 * such as {@code ymuski/curl-http3} is required — see {@code specs/007-interop-examples/quickstart.md}.)
 *
 * <h2>Chrome (manual)</h2>
 * {@code --ignore-certificate-errors} is <b>not honoured for QUIC</b> — BoringSSL still fails the
 * chain and it surfaces as {@code ERR_QUIC_PROTOCOL_ERROR}. Pin the certificate's SPKI instead:
 * <pre>{@code
 * SPKI=$(openssl x509 -pubkey -noout -in examples/core/http3/src/main/resources/dev-cert.pem \
 *   | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64)
 *
 * google-chrome --headless=new --disable-gpu --no-sandbox \
 *   --enable-quic --origin-to-force-quic-on=localhost:4433 \
 *   --ignore-certificate-errors-spki-list="$SPKI" \
 *   --log-net-log=netlog.json --dump-dom https://localhost:4433/
 * }</pre>
 * The netlog must show {@code QUIC_SESSION_VERSION_NEGOTIATED {"version": "RFCv1"}} and an
 * {@code HTTP3_HEADERS_DECODED} event carrying {@code :status: 200}.
 *
 * @see Http3ServerLauncher
 */
public final class Http3HelloWorld extends Http3ServerLauncher {
	/** The body served on {@code /} — the documented, assertable response. */
	private static final String MESSAGE = "Hello from HTTP/3!";

	private static final String DEV_CERT = "dev-cert.pem";
	private static final String DEV_KEY = "dev-key.pem";

	/** The only thing a subclass of {@link Http3ServerLauncher} must supply: the business logic. */
	@Override
	protected Module getBusinessLogicModule() {
		return new AbstractModule() {
			@Provides
			public AsyncServlet servlet() {
				return request -> HttpResponse.ok200()
					.withPlainText(MESSAGE)
					.toPromise();
			}
		};
	}

	/**
	 * Extracts the dev identity from this example's resources into a temporary directory and points
	 * the standard launcher config keys at it — unless the operator already set them with
	 * {@code -Dconfig.http3.certificateChain} / {@code -Dconfig.http3.privateKey}, which win.
	 * <p>
	 * Extraction rather than a classpath-URL path because {@code TlsServerIdentity.fromPem} needs
	 * real files: a {@code jar:} URL has no {@code Path}, and a relative path would break from any
	 * working directory but the module's. A temporary directory works from the jar, from
	 * {@code target/classes} and from anywhere.
	 */
	private static void prepareDevIdentityConfig() throws IOException {
		if (System.getProperty("config.http3.certificateChain") != null ||
			System.getProperty("config.http3.privateKey") != null) {
			return; // an explicit -Dconfig.* override replaces the dev identity entirely
		}
		Path dir = Files.createTempDirectory("activej-http3-example");
		dir.toFile().deleteOnExit();
		extractResource(DEV_CERT, dir.resolve(DEV_CERT));
		extractResource(DEV_KEY, dir.resolve(DEV_KEY));
		System.setProperty("config.http3.certificateChain", dir.resolve(DEV_CERT).toString());
		System.setProperty("config.http3.privateKey", dir.resolve(DEV_KEY).toString());
	}

	private static void extractResource(String name, Path target) throws IOException {
		try (InputStream in = Http3HelloWorld.class.getResourceAsStream('/' + name)) {
			if (in == null) {
				throw new IOException("Resource " + name + " is missing from the example's classpath");
			}
			Files.copy(in, target);
		}
	}

	/**
	 * Runs the example with no arguments. Shutdown — {@code Ctrl-C} or {@code shutdown()} — runs
	 * through the launcher lifecycle: the service graph stops the server (GOAWAY, draining
	 * in-flight exchanges within {@code http3.settings.shutdownTimeout}) and then joins the
	 * reactor thread, so nothing is left running when the JVM exits.
	 */
	public static void main(String[] args) throws Exception {
		prepareDevIdentityConfig();
		new Http3HelloWorld().launch(args);
	}
}
