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

package io.activej.launchers.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.config.Config;
import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResourceRecord;
import io.activej.dns.protocol.DnsResponse;
import io.activej.dns.protocol.DnsTransaction;
import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.Http3Client;
import io.activej.http3.Http3Settings;
import io.activej.inject.Injector;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Provides;
import io.activej.launcher.Launcher;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.tls.TlsServerIdentity;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static io.activej.promise.Promise.ofCompletionStage;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The smoke and behavioural suite for {@link Http3ServerLauncher} (Phase 5, US3).
 * <p>
 * {@code testsInjector} is T030 — the test that had to exist <b>before</b>
 * {@link Http3ServerServiceAdapter} did. The planned "Unsupported service … Use register()
 * methods" wiring failure does <b>not</b> reproduce on this branch: {@code ServiceGraphModule}
 * auto-adapts {@code AutoCloseable} and {@code Http3Server} is one, so the graph silently adapts it
 * (never {@code listen()}, off-reactor {@code close()} on stop). The genuinely observed red is
 * behavioural and is documented in {@code specs/007-interop-examples/task-results/T030.md}; the
 * serve test below is what proves the adapter's existence, and
 * {@code gracefulStopFinishesInFlightExchange} (T037) makes T031's stop contract a tested property.
 */
public class Http3ServerLauncherTest {
	/** The fixed body the serve tests' servlet answers. */
	private static final String FIXED_TEXT = "Hello from the HTTP/3 launcher!";
	/** The dev identity of T035 — the client trusts exactly this leaf, RFC 6125 stays live. */
	private static final Path DEV_CERT_PATH = fixture("ecdsa-cert.pem");
	private static final Path DEV_KEY_PATH = fixture("ecdsa-key.pem");
	/** Per-promise await bound for cross-reactor promises (the launcher's reactor is another thread). */
	private static final long AWAIT_TIMEOUT_SECONDS = 45;
	/** How long a condition (e.g. "the exchange reached the servlet") is polled for. */
	private static final long POLL_TIMEOUT_SECONDS = 10;

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The bridge into the launcher's reactor, handed over by the test subclass's {@code @Provides @Eager} probe. */
	private static volatile ClientProbe probe;

	@BeforeClass
	public static void beforeClass() {
		Injector.useSpecializer();
	}

	@Test
	public void testsInjector() {
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			public AsyncServlet servlet() {
				throw new UnsupportedOperationException();
			}
		};
		launcher.testInjector();
	}

	/**
	 * T033, case A — FR-025: a launcher with no certificate configured at all fails startup naming
	 * the <b>required config key</b> ({@code http3.certificateChain}), with no key material in the
	 * message, and no socket was ever bound (the binding never completed — there is no server
	 * instance and {@code getStartFuture()} never completed).
	 */
	@Test
	public void certificateKeysMissing() {
		Throwable[] error = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			public AsyncServlet servlet() {
				return request -> HttpResponse.ok200().toPromise();
			}

			@Override
			Config config() {
				// overrideWith replaces the http3 subtree: certificateChain and privateKey are absent
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				error[0] = throwable;
			}
		};

		launchCapturing(launcher, error);

		assertStartupFailed(error,
			"the message must name the required config key",
			"http3.certificateChain");
		assertNoKeyMaterial(error);
		assertNoServerWasBound(launcher);
	}

	/**
	 * T033, case B — FR-025: an unparseable certificate path fails startup naming the <b>config key
	 * and the path</b>, with no key material in the message, and no socket was ever bound.
	 */
	@Test
	public void certificatePathUnparseable() {
		Throwable[] error = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			public AsyncServlet servlet() {
				return request -> HttpResponse.ok200().toPromise();
			}

			@Override
			Config config() {
				// a real, readable file that is not a PEM — the README next to the fixtures
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", fixture("README.md").toString())
					.with("http3.privateKey", DEV_KEY_PATH.toString()));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				error[0] = throwable;
			}
		};

		launchCapturing(launcher, error);

		assertStartupFailed(error,
			"the message must name the config key",
			"http3.certificateChain");
		assertStartupFailed(error,
			"the message must name the offending path",
			fixture("README.md").toString());
		assertNoKeyMaterial(error);
		assertNoServerWasBound(launcher);
	}

	/**
	 * T033, case C — FR-025: a certificate-chain value that is not even a valid path fails startup
	 * naming the <b>config key</b>, with no key material in the message, and no socket was ever
	 * bound. {@code ConfigConverters.ofPath} rethrows {@code Paths.get}'s {@code InvalidPathException}
	 * as a plain {@code IllegalArgumentException}, so the launcher must wrap it with the key name
	 * rather than letting it escape raw.
	 */
	@Test
	public void certificatePathValueUnparseable() {
		Throwable[] error = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			public AsyncServlet servlet() {
				return request -> HttpResponse.ok200().toPromise();
			}

			@Override
			Config config() {
				// a value Paths.get cannot parse: an embedded NUL is an InvalidPathException on every OS
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", "bad\u0000path")
					.with("http3.privateKey", DEV_KEY_PATH.toString()));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				error[0] = throwable;
			}
		};

		launchCapturing(launcher, error);

		assertStartupFailed(error,
			"the message must name the config key",
			"http3.certificateChain");
		assertNoKeyMaterial(error);
		assertNoServerWasBound(launcher);
	}

	/**
	 * T036 — the serve test: a launched {@link Http3ServerLauncher} serves one request over HTTP/3
	 * and shuts down. The launcher binds port {@code 0} — the OS-assigned port is read back through
	 * {@code Http3Server.getBoundAddress()} (FR-023), eliminating the free-then-rebind race of a
	 * pre-allocated port — and the client half is wired <b>through the
	 * launcher's own DI</b>: the test subclass's {@code @Provides} methods are scanned by
	 * {@code getInternalModule}, so the {@link ClientProbe} (and the {@link Http3Client} it runs,
	 * on its own dedicated loop — see its Javadoc for why) is created during launch and handed to
	 * the test through a static field.
	 */
	@Test
	public void servesOneRequest() {
		probe = null;
		Throwable[] fatal = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			AsyncServlet servlet() {
				return request -> HttpResponse.ok200()
					.withPlainText(FIXED_TEXT)
					.toPromise();
			}

			@Provides
			IDnsClient dnsClient() {
				return loopbackResolver();
			}

			@Provides
			@Eager
			ClientProbe probe(NioReactor launcherReactor, IDnsClient dnsClient) {
				Http3ServerLauncherTest.probe = ClientProbe.create((Eventloop) launcherReactor, dnsClient,
					config -> config.withTrustedCertificate(devLeaf()));
				return Http3ServerLauncherTest.probe;
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", DEV_CERT_PATH.toString())
					.with("http3.privateKey", DEV_KEY_PATH.toString()));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				// the launcher's default onFatalError is System.exit(-1) — capture instead, so a
				// fatal error fails this test rather than killing the whole Surefire JVM
				fatal[0] = throwable;
			}
		};

		launchOnThread(launcher);
		try {
			// Launcher futures must be awaited with TestUtils.await: Promise.ofCompletionStage hops back
			// into the *current* reactor (EventloopRule's), so the loop must be pumped while waiting —
			// a bare toCompletableFuture().get() would deadlock (the launch thread is not a reactor).
			await(ofCompletionStage(launcher.getStartFuture()));

			ClientProbe probe = Http3ServerLauncherTest.probe;
			assertNotNull("the test's @Eager ClientProbe must have been created during launch", probe);

			int port = boundPort(launcher, probe);
			HttpResponse response = requestWithHandshakeRetry(probe, "https://localhost:" + port + "/", "GET / over HTTP/3");
			assertEquals("the response must report the exact HTTP_3_0 version enum value",
				HttpVersion.HTTP_3_0, response.getVersion());
			ByteBuf body = awaitBounded(probe.onClient(() -> {
				return response.loadBody();
			}), "the response body");
			// asArray() recycles the buffer itself (CSP ownership: the body is ours)
			assertArrayEquals("the body must be byte-identical to the servlet's fixed text",
				FIXED_TEXT.getBytes(StandardCharsets.UTF_8),
				body.asArray());
			assertNoFatalError(fatal);

			probe.closeClient();
			launcher.shutdown();
			await(ofCompletionStage(launcher.getCompleteFuture()));
		} finally {
			// A failure between launch and shutdown must not park anything: closeClient() and
			// shutdown() are idempotent, the launch thread is a daemon, and the complete future is
			// deliberately NOT awaited here — a failed test reports its own error instead of waiting
			// on a launcher that may never complete.
			if (Http3ServerLauncherTest.probe != null) {
				try {
					Http3ServerLauncherTest.probe.closeClient();
				} catch (Exception ignored) {
					// the test already failed; the daemon client thread cannot hang the JVM
				}
			}
			launcher.shutdown();
		}
	}

	/**
	 * T037 — the D5 stop-contract property (SC-006a): an exchange <b>in flight</b> when the
	 * launcher is stopped completes rather than being severed, and the JVM is left with no
	 * non-daemon thread. {@link Http3ServerServiceAdapter#stop} makes this a tested property on
	 * its own, without relying on the graph's stop ordering: it calls {@code close()}, releases the
	 * reactor, and joins its thread — which {@code Eventloop}'s liveness rule keeps from exiting
	 * while the drain's scheduled task and the endpoint's socket key exist, so the join returns
	 * exactly when the GOAWAY drain (bounded by {@code http3.settings.shutdownTimeout}) has
	 * finished — and only then does the launch complete.
	 * <p>
	 * The assertion is temporal: the servlet's response promise is completed <b>after</b>
	 * {@code shutdown()} was requested, and the request still resolves {@code 200} with the body.
	 * The client is closed <b>after</b> the response completes — on the client's own loop, which is
	 * independent of the launcher's, and closing it first is what lets the server's drain finish
	 * early instead of riding out its ceiling.
	 */
	@Test
	public void gracefulStopFinishesInFlightExchange() {
		AtomicBoolean served = new AtomicBoolean();
		SettablePromise<HttpResponse> heldResponse = new SettablePromise<>();
		probe = null;
		Throwable[] fatal = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			AsyncServlet servlet() {
				return request -> {
					served.set(true);
					return heldResponse;
				};
			}

			@Provides
			IDnsClient dnsClient() {
				return loopbackResolver();
			}

			@Provides
			@Eager
			ClientProbe probe(NioReactor launcherReactor, IDnsClient dnsClient) {
				Http3ServerLauncherTest.probe = ClientProbe.create((Eventloop) launcherReactor, dnsClient,
					config -> config.withTrustedCertificate(devLeaf()));
				return Http3ServerLauncherTest.probe;
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", DEV_CERT_PATH.toString())
					.with("http3.privateKey", DEV_KEY_PATH.toString())
					// a generous GOAWAY drain ceiling: the exchange must survive the stop
					.with("http3.settings.shutdownTimeout", "10 seconds"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				// the launcher's default onFatalError is System.exit(-1) — capture instead, so a
				// fatal error fails this test rather than killing the whole Surefire JVM
				fatal[0] = throwable;
			}
		};

		launchOnThread(launcher);
		try {
			await(ofCompletionStage(launcher.getStartFuture()));

			ClientProbe probe = Http3ServerLauncherTest.probe;
			assertNotNull("the test's @Eager ClientProbe must have been created during launch", probe);

			String url = "https://localhost:" + boundPort(launcher, probe) + "/";
			Promise<HttpResponse> inFlight = null;
			for (int attempt = 1; attempt <= 3; attempt++) {
				inFlight = probe.onClient(() -> probe.client().request(HttpRequest.get(url).build()));
				// Wait for either the servlet invocation (the exchange is established) or a terminal
				// promise failure (the dial failed before reaching the application). The promise
				// completes on the client's reactor thread, so only its CompletableFuture view is
				// safe to poll from the JUnit thread (core-promise: never read a Promise's state
				// across threads); the deadline keeps the poll itself from hanging a broken dial.
				CompletableFuture<HttpResponse> future = inFlight.toCompletableFuture();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POLL_TIMEOUT_SECONDS);
				while (!served.get() && !future.isDone() && System.nanoTime() < deadline) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new AssertionError("Interrupted while awaiting the servlet invocation", e);
					}
				}
				if (served.get()) {
					break;
				}
				Exception failure;
				try {
					future.get(0, TimeUnit.SECONDS);
					throw new AssertionError("the request completed without reaching the servlet");
				} catch (ExecutionException e) {
					failure = (Exception) e.getCause();
				} catch (TimeoutException e) {
					throw new AssertionError("the request neither reached the servlet nor failed within " +
						POLL_TIMEOUT_SECONDS + " seconds");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted while awaiting the servlet invocation", e);
				}
				if (isHandshakeTimeout(failure)) {
					// a QUIC handshake that did not complete within the client's 10 s bound under a
					// saturated parallel build is environmental — dial again; anything else is a bug
					continue;
				}
				throw new AssertionError("the request failed before reaching the servlet: " + failure, failure);
			}
			if (!served.get()) {
				throw new AssertionError("the request never reached the servlet after 3 dial attempts");
			}

			// stop requested while the exchange is in flight — the servlet's promise is still pending
			launcher.shutdown();

			// complete the servlet's response on the reactor, after the stop was requested: the drain
			// must deliver the in-flight exchange, not sever it
			probe.onLauncher(() -> heldResponse.set(
				HttpResponse.ok200().withPlainText(FIXED_TEXT).build()));

			HttpResponse response = awaitBounded(inFlight, "the in-flight exchange after launcher.shutdown()");
			assertEquals("the drained exchange must resolve with the exact HTTP_3_0 version enum value",
				HttpVersion.HTTP_3_0, response.getVersion());
			ByteBuf body = awaitBounded(probe.onClient(() -> {
				return response.loadBody();
			}), "the in-flight exchange's body");
			assertArrayEquals("the drained exchange must carry the servlet's fixed text",
				FIXED_TEXT.getBytes(StandardCharsets.UTF_8),
				body.asArray());
			assertNoFatalError(fatal);

			probe.closeClient();
			await(ofCompletionStage(launcher.getCompleteFuture()));
			assertNoNonDaemonThreadsLeft();
		} finally {
			// A failure between launch and shutdown must not park anything: closeClient() and
			// shutdown() are idempotent, the launch thread is a daemon, and the complete future is
			// deliberately NOT awaited here — a failed test reports its own error instead of waiting
			// on a launcher that may never complete.
			if (Http3ServerLauncherTest.probe != null) {
				try {
					Http3ServerLauncherTest.probe.closeClient();
				} catch (Exception ignored) {
					// the test already failed; the daemon client thread cannot hang the JVM
				}
			}
			launcher.shutdown();
		}
	}

	/**
	 * T024 — FR-020: the launcher logs the address it is <b>bound</b> to, not the one it was
	 * configured with. The configured port is {@code 0} (the OS assigns one); with the pre-fix
	 * code the log line read {@code https://localhost/127.0.0.1:0} — the configured value read
	 * back out of {@code Config}. This case captures the log and asserts the line carries the
	 * real, non-zero port — the assertion that distinguishes the fix from the workaround.
	 * <p>
	 * The {@code io.activej} logger is <b>off</b> by default on the test classpath
	 * ({@code activej-test}'s {@code logback-test.xml}), which would make the launcher's own
	 * {@code logger.isInfoEnabled()} gate swallow the line; the test raises the
	 * {@code io.activej.launchers.http3} logger to INFO and captures through a
	 * {@link ListAppender} before launching, and restores both afterwards.
	 */
	@Test
	public void logsTheBoundAddressNotTheConfiguredOne() {
		Logger launcherLogger = (Logger) LoggerFactory.getLogger("io.activej.launchers.http3");
		Level originalLevel = launcherLogger.getLevel();
		launcherLogger.setLevel(Level.INFO);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		launcherLogger.addAppender(appender);
		Throwable[] fatal = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			public AsyncServlet servlet() {
				return request -> HttpResponse.ok200().toPromise();
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", DEV_CERT_PATH.toString())
					.with("http3.privateKey", DEV_KEY_PATH.toString()));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				fatal[0] = throwable;
			}
		};
		try {
			launchOnThread(launcher);
			await(ofCompletionStage(launcher.getStartFuture()));
			assertNoFatalError(fatal);

			String message = appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(m -> m != null && m.contains("HTTP/3 Server is now available at https://"))
				.findFirst()
				.orElse(null);
			assertNotNull("the launcher must log its serving address in onStart()", message);
			int loggedPort = Integer.parseInt(message.substring(message.lastIndexOf(':') + 1));
			assertTrue("with a configured port of 0 the log must carry the real, non-zero bound port: " + message,
				loggedPort > 0);

			launcher.shutdown();
			await(ofCompletionStage(launcher.getCompleteFuture()));
		} finally {
			launcher.shutdown();
			launcherLogger.detachAppender(appender);
			launcherLogger.setLevel(originalLevel);
		}
	}

	/**
	 * T025 — FR-022, the launcher-level mirror of {@code HttpServerLauncherTest.bindZeroPort}:
	 * configuring port {@code 0} yields a server reachable on a real, non-zero port discovered
	 * through {@code Http3Server.getBoundAddress()} (SC-001).
	 * <p>
	 * The read is deliberately more verbose than the HTTP/1.1 equivalent, which reads
	 * {@code getBoundAddresses()} straight from the JUnit thread:
	 * {@code Http3Server.getBoundAddress()} carries the reactor-thread guard FR-010 keeps — a
	 * deliberate divergence from {@code AbstractReactiveServer} — so the port must be read through
	 * the {@link ClientProbe#onLauncher} submit bridge. That verbosity is the price of the guard,
	 * not an accident (FR-027): do not "simplify" this test by deleting the guard.
	 */
	@Test
	public void bindZeroPort() {
		probe = null;
		Throwable[] fatal = new Throwable[1];
		Http3ServerLauncher launcher = new Http3ServerLauncher() {
			@Provides
			AsyncServlet servlet() {
				return request -> HttpResponse.ok200()
					.withPlainText(FIXED_TEXT)
					.toPromise();
			}

			@Provides
			IDnsClient dnsClient() {
				return loopbackResolver();
			}

			@Provides
			@Eager
			ClientProbe probe(NioReactor launcherReactor, IDnsClient dnsClient) {
				Http3ServerLauncherTest.probe = ClientProbe.create((Eventloop) launcherReactor, dnsClient,
					config -> config.withTrustedCertificate(devLeaf()));
				return Http3ServerLauncherTest.probe;
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create()
					.with("http3.listenAddresses", "localhost:0")
					.with("http3.certificateChain", DEV_CERT_PATH.toString())
					.with("http3.privateKey", DEV_KEY_PATH.toString()));
			}

			@Override
			protected void onFatalError(Throwable throwable) {
				// the launcher's default onFatalError is System.exit(-1) — capture instead, so a
				// fatal error fails this test rather than killing the whole Surefire JVM
				fatal[0] = throwable;
			}
		};

		launchOnThread(launcher);
		try {
			await(ofCompletionStage(launcher.getStartFuture()));

			ClientProbe probe = Http3ServerLauncherTest.probe;
			assertNotNull("the test's @Eager ClientProbe must have been created during launch", probe);

			int port = boundPort(launcher, probe);
			HttpResponse response = requestWithHandshakeRetry(probe,
				"https://localhost:" + port + "/",
				"GET / over HTTP/3 on the port discovered through getBoundAddress()");
			assertEquals("the response must report the exact HTTP_3_0 version enum value",
				HttpVersion.HTTP_3_0, response.getVersion());
			ByteBuf body = awaitBounded(probe.onClient(() -> {
				return response.loadBody();
			}), "the response body");
			// asArray() recycles the buffer itself (CSP ownership: the body is ours)
			assertArrayEquals("the body must be byte-identical to the servlet's fixed text",
				FIXED_TEXT.getBytes(StandardCharsets.UTF_8),
				body.asArray());
			assertNoFatalError(fatal);

			probe.closeClient();
			launcher.shutdown();
			await(ofCompletionStage(launcher.getCompleteFuture()));
		} finally {
			// A failure between launch and shutdown must not park anything: closeClient() and
			// shutdown() are idempotent, the launch thread is a daemon, and the complete future is
			// deliberately NOT awaited here — a failed test reports its own error instead of waiting
			// on a launcher that may never complete.
			if (Http3ServerLauncherTest.probe != null) {
				try {
					Http3ServerLauncherTest.probe.closeClient();
				} catch (Exception ignored) {
					// the test already failed; the daemon client thread cannot hang the JVM
				}
			}
			launcher.shutdown();
		}
	}

	/**
	 * T038 — FR-029: the feature-006 capabilities ({@code qpackMaxTableCapacity},
	 * {@code zeroRttEnabled}, {@code datagramsEnabled}) are <b>not</b> launcher config keys. A
	 * config that sets them must be ignored — the settings stay at {@code Http3Settings}' own
	 * defaults; a key that were read would have changed them. Each capability stays a knowing
	 * opt-in via {@code getBusinessLogicModule()}. The source half of the evidence is the grep for
	 * the three names over {@code launchers/http3/src/main} (zero hits — recorded in
	 * task-results/T038.md).
	 */
	@Test
	public void feature006KeysNotExposed() {
		Config config = Config.create()
			.with("qpackMaxTableCapacity", "64kb")
			.with("zeroRttEnabled", "true")
			.with("datagramsEnabled", "true");

		Http3Settings settings = Http3Settings.builder()
			.initialize(Initializers.ofHttp3Settings(config))
			.build();

		assertEquals("qpackMaxTableCapacity must stay at its default — no key reads it",
			0, settings.qpackMaxTableCapacity());
		assertEquals("zeroRttEnabled must stay off — no key reads it",
			false, settings.zeroRttEnabled());
		assertEquals("datagramsEnabled must stay off — no key reads it",
			false, settings.datagramsEnabled());
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Launches {@code launcher} on a dedicated <b>daemon</b> thread, so the JUnit thread never
	 * blocks the reactor — and a test failure that leaves {@code launch()} parked in
	 * {@code awaitShutdown()} (no {@code try/finally} is airtight) cannot hang the Surefire JVM.
	 */
	private static void launchOnThread(Http3ServerLauncher launcher) {
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * A table-based resolver mapping the authority's host onto the loopback address — the
	 * {@code Http3InteropClient} shape. DNS is not under test; the socket, the clock and the TLS
	 * handshake are real. Never contacts any server.
	 */
	private static IDnsClient loopbackResolver() {
		return new IDnsClient() {
			@Override
			public Promise<DnsResponse> resolve(DnsQuery query) {
				return Promise.of(DnsResponse.of(
					DnsTransaction.of((short) 0, query),
					DnsResourceRecord.of(new InetAddress[]{InetAddress.getLoopbackAddress()}, 60)));
			}

			@Override
			public void close() {}
		};
	}

	/**
	 * Asserts the JVM is left with no non-daemon thread beyond the test's own thread and the
	 * surefire JVM's {@code main}. The launcher's eventloop thread is joined by the adapter's stop
	 * wait, the launch thread exits when {@code launch()} returns, and the service graph's
	 * pool threads die after ~10 ms idle — so the assertion is retried briefly rather than racing
	 * the pool's keep-alive. Threads are enumerated through the root {@link ThreadGroup}, which
	 * costs nothing like the JVM-wide stack snapshot of {@code Thread.getAllStackTraces()} would.
	 */
	private static void assertNoNonDaemonThreadsLeft() {
		Thread current = Thread.currentThread();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			List<Thread> offenders = nonDaemonThreadsExcept(current);
			if (offenders.isEmpty()) {
				return;
			}
			if (System.nanoTime() >= deadline) {
				throw new AssertionError("Non-daemon threads left after the launcher completed: " +
					offenders.stream().map(Thread::getName).toList());
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while awaiting thread reaping", e);
			}
		}
	}

	private static List<Thread> nonDaemonThreadsExcept(Thread except) {
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null) {
			root = root.getParent();
		}
		Thread[] threads;
		int count;
		do {
			// activeCount is an estimate; enumerate may silently truncate to the array length — retry
			// with a bigger array until the count proves nothing was cut off
			int estimate = root.activeCount() + 16;
			threads = new Thread[estimate];
			count = root.enumerate(threads, true);
		} while (count == threads.length);
		return Arrays.stream(threads, 0, count)
			.filter(thread -> !thread.isDaemon() && thread.isAlive() &&
				thread != except && !thread.getName().equals("main"))
			.toList();
	}

	/** Asserts the launcher's {@code onFatalError} never fired (it would have killed the JVM by default). */
	private static void assertNoFatalError(Throwable[] fatal) {
		if (fatal[0] != null) {
			throw new AssertionError("a fatal error escaped the launcher's launch flow", fatal[0]);
		}
	}

	/**
	 * The port the launcher's server is <b>actually</b> bound to, read through the
	 * {@link ClientProbe#onLauncher} submit bridge the reactor-thread guard on
	 * {@code Http3Server.getBoundAddress()} requires (FR-010) — the JUnit thread must never touch
	 * a reactive component directly (SI-9). Asserts the {@code :0} listen address resolved to a
	 * real, non-zero port (FR-022).
	 */
	private static int boundPort(Http3ServerLauncher launcher, ClientProbe probe) {
		InetSocketAddress bound = probe.onLauncher(() -> launcher.http3Server.getBoundAddress());
		assertNotNull("the server must report its bound address once listen() has completed", bound);
		assertNotEquals("a :0 listen address must resolve to a real, non-zero port (FR-022)", 0, bound.getPort());
		return bound.getPort();
	}

	/**
	 * Launches {@code launcher} on a dedicated thread and captures whatever escapes
	 * {@code launch()}. A {@code @Provides}-time exception (like a missing or unparseable
	 * certificate) bypasses {@code startServices}, so {@code getStartFuture()} never completes —
	 * it must not be awaited; the launch thread's captured throwable is the only signal.
	 */
	private static void launchCapturing(Http3ServerLauncher launcher, Throwable[] error) {
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				error[0] = t;
			}
		});
		thread.start();
		try {
			thread.join(30_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while joining the launch thread", e);
		}
		if (thread.isAlive()) {
			throw new AssertionError("launch() did not fail within 30 seconds — the launcher started instead of failing");
		}
	}

	/** Asserts a launch failure whose message chain contains {@code expected} (FR-025). */
	private static void assertStartupFailed(Throwable[] error, String what, String expected) {
		if (error[0] == null) {
			throw new AssertionError("launch() must have failed: " + what);
		}
		Throwable t = error[0];
		while (t != null) {
			if (t.getMessage() != null && t.getMessage().contains(expected)) {
				return;
			}
			t = t.getCause();
		}
		throw new AssertionError("launch failure " + error[0] + ": " + what + " — no message in the " +
			"cause chain contains '" + expected + "'");
	}

	/** Asserts no PEM key material leaks into the failure (SI-6, FR-025). */
	private static void assertNoKeyMaterial(Throwable[] error) {
		Throwable t = error[0];
		while (t != null) {
			String message = t.getMessage();
			if (message != null && (message.contains("BEGIN") || message.contains("PRIVATE KEY"))) {
				throw new AssertionError("launch failure must never carry key material, " +
					"but a message contains PEM text: " + message);
			}
			t = t.getCause();
		}
	}

	/** Asserts the binding never completed: no server instance was injected, no socket was bound. */
	private static void assertNoServerWasBound(Http3ServerLauncher launcher) {
		if (launcher.http3Server != null) {
			throw new AssertionError("the @Provides server() binding must not have completed, " +
				"so the launcher's Http3Server field must not be injected");
		}
		if (launcher.getStartFuture().toCompletableFuture().isDone()) {
			throw new AssertionError("getStartFuture() must never complete when the server binding fails");
		}
	}

	/**
	 * Blocks the JUnit thread until the promise completes on the launcher's reactor thread. Only
	 * for promises completing on a <b>foreign</b> reactor — {@link TestUtils#await} is the right
	 * helper for launcher futures, which hop back into the current reactor.
	 */
	private static <T> T awaitBounded(Promise<T> promise, String what) {
		try {
			return promise.toCompletableFuture().get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError(what + " failed: " + e.getCause(), e.getCause());
		} catch (TimeoutException e) {
			throw new AssertionError(what + " did not complete within " +
				AWAIT_TIMEOUT_SECONDS + " seconds", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while awaiting " + what, e);
		}
	}

	private static TlsServerIdentity devIdentity() {
		try {
			return TlsServerIdentity.fromPem(DEV_CERT_PATH, DEV_KEY_PATH);
		} catch (Exception e) {
			throw new AssertionError("Failed to load the dev ECDSA certificate fixture", e);
		}
	}

	/**
	 * Issues the request and awaits its response, redialing when the QUIC handshake hit the
	 * client's 10 s bound — an environmental failure under a saturated {@code -T1C} parallel build
	 * (the launcher is listening; the machine is not keeping up with real-time retransmissions).
	 * Every other failure propagates unchanged, and the assertions on the response are exact.
	 */
	private static HttpResponse requestWithHandshakeRetry(ClientProbe probe, String url, String what) {
		Exception last = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				Promise<HttpResponse> promise = probe.onClient(() ->
					probe.client().request(HttpRequest.get(url).build()));
				return awaitBounded(promise, what + " (attempt " + attempt + ")");
			} catch (AssertionError e) {
				Throwable cause = e.getCause();
				if (cause instanceof QuicTransportException && isHandshakeTimeout((Exception) cause)) {
					last = (Exception) cause;
					continue;
				}
				throw e;
			}
		}
		throw new AssertionError(what + " — the QUIC handshake did not complete within the client's " +
			"10 s bound in 3 attempts (parallel-build load): " + last, last);
	}

	private static boolean isHandshakeTimeout(Exception failure) {
		return failure instanceof QuicTransportException qte &&
			qte.getMessage() != null &&
			qte.getMessage().contains("Handshake did not complete");
	}

	private static X509Certificate devLeaf() {
		return devIdentity().leaf();
	}

	/** Resolves a fixture from {@code /io/activej/launchers/http3/} on the test classpath. */
	private static Path fixture(String name) {
		try {
			URL resource = Http3ServerLauncherTest.class.getResource("/io/activej/launchers/http3/" + name);
			if (resource == null) throw new AssertionError(name + " fixture is not on the classpath");
			return Path.of(resource.toURI());
		} catch (URISyntaxException e) {
			throw new AssertionError(e);
		}
	}
}
