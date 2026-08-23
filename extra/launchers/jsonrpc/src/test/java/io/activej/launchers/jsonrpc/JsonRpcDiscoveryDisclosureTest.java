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

package io.activej.launchers.jsonrpc;

import io.activej.config.Config;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The <b>disclosure</b> half of feature 018's adversarial surface: what a probe can learn about a
 * deployment it is not entitled to read. Oracle throughout: constitution <b>SI-6</b> — "secrets never
 * appear in exception messages, logs, or JMX attributes, and this extends to diagnostic surfaces, which
 * are the easiest place to leak by accident" — plus FR-042 ("off means absent") and
 * {@code contracts/config-keys.md}.
 *
 * <p>Everything here is about the <i>disabled</i> and <i>misconfigured</i> states. The enabled state is
 * a deliberate publication and needs no defending; the interesting question is whether a deployment that
 * never published anything can still be made to admit that discovery exists.
 *
 * <h4>D1 — the unmounted path must be byte-identical to a path nobody ever configured</h4>
 * {@code JsonRpcDiscoveryConfigTest} pins the <i>status</i> (404) of the unmounted discovery path. A
 * status is not indistinguishability: a distinct {@code Content-Type}, a distinct body, an extra header,
 * or headers in a different order would each be an oracle answering "discovery lives here and is switched
 * off" — which is precisely the answer FR-042 refuses to give. This compares the <b>whole response,
 * byte for byte</b> — status line, every header in emission order, and body — against the answer for a
 * path this deployment has demonstrably never heard of, read off a raw socket rather than through an
 * {@code HttpClient} that would normalise exactly the differences under test.
 *
 * <p>Two independent code paths are being compared, which is what makes the test worth writing:
 * {@code RoutingServlet.serve} raises {@code HttpError.notFound404()} for the never-configured path,
 * while {@code JsonRpcDiscoveryServlet.serve}'s {@code null}-document branch raises <i>its own</i>
 * {@code HttpError.notFound404()}. The mount is skipped entirely when the key is empty, so today only
 * the router's path is reachable through the launcher — but the servlet's defensive branch exists exactly
 * so that a mis-wiring stays unprobeable, and D2 exercises it as well.
 *
 * <h4>D2 — enabling discovery must not widen the surface to neighbouring paths</h4>
 * A published document at {@code /openrpc} is a decision about {@code /openrpc}. If the mount were a
 * subtree rather than an exact path, {@code /openrpc/anything} would serve the document too, and a
 * deployment that put the endpoint behind a reverse-proxy rule on the exact path would be bypassable by
 * appending a segment. The neighbours are asserted byte-identical to the never-configured path, so the
 * router cannot even confirm that {@code /openrpc} is special by answering its children differently.
 *
 * <h4>D3 — the fail-closed startup message names the key and carries no value</h4>
 * SI-6's precedent in this platform is {@code Config.combineWith}, which reports the conflicting
 * <b>key path</b> and never the values. {@link JsonRpcServerLauncher}'s config surface follows it, and
 * {@code JsonRpcModule.requiredInfoMember} is the newest member of that family:
 * <pre>{@code
 * "Configuration key 'jsonrpc.discovery.info." + member + "' is required when discovery is enabled, and must not be blank: …"
 * }</pre>
 * Only {@code member} — {@code "title"} or {@code "version"} — is interpolated, and it is a fixed
 * literal from the caller, never config text. {@code JsonRpcDiscoveryConfigTest} asserts that the key is
 * named; nothing asserts the other half, that <b>nothing else is</b>. This launches with three canary
 * values planted in the neighbouring keys — one of which ({@code jsonrpc.discovery.info.version}) is
 * read by the very method that throws, one sibling of it in the same subtree, and one in an unrelated
 * key — and requires every message in the cause chain to be free of all three. A future "helpful"
 * message quoting the config it did find would fail here.
 *
 * <p>The deliberate scope: this covers the <b>exception</b> surface. {@code ConfigModule}'s
 * {@code withEffectiveConfigLogger()} prints configured values by design — that is the operator-facing
 * config dump, not a diagnostic leak, and it is out of scope here.
 *
 * <p>Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * would take the Surefire JVM with it), and every server binds {@code :0} with the port read back from
 * the server (ADR-028).
 */
public class JsonRpcDiscoveryDisclosureTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String DISCOVERY_PATH = "/openrpc";

	/** A path this deployment demonstrably never configured — the control in every comparison below. */
	private static final String NEVER_CONFIGURED = "/zz-never-configured-4f1a2b";

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	// ------------------------------------------------------------------ D1: off means absent, byte for byte

	@Test
	public void withDiscoveryOffTheDiscoveryPathIsByteIdenticalToANeverConfiguredPath() throws Exception {
		JsonRpcServerLauncher launcher = launcher(Config.create());
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			byte[] unknown = rawGet(port, NEVER_CONFIGURED);
			byte[] discovery = rawGet(port, DISCOVERY_PATH);

			assertStartsWith(unknown, "HTTP/1.1 404");
			assertResponsesIdentical(NEVER_CONFIGURED, unknown, DISCOVERY_PATH, discovery);

			// and the same holds for the empty-value spelling of "off", which ADR-042 makes equivalent to
			// an absent key — a deployment that switched discovery back off must not become probeable
			// because the key is still present in its properties file
			assertNoDocument(discovery);
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void anEmptyDiscoveryPathIsByteIdenticalToANeverConfiguredPathToo() throws Exception {
		JsonRpcServerLauncher launcher = launcher(Config.create().with("jsonrpc.discovery.path", ""));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			assertResponsesIdentical(
				NEVER_CONFIGURED, rawGet(port, NEVER_CONFIGURED),
				DISCOVERY_PATH, rawGet(port, DISCOVERY_PATH));
		} finally {
			stop(launcher);
		}
	}

	// ------------------------------------------------------------------ D2: on means on, at one path only

	@Test
	public void withDiscoveryOnTheNeighbouringPathsAreByteIdenticalToANeverConfiguredPath() throws Exception {
		JsonRpcServerLauncher launcher = launcher(Config.create()
			.with("jsonrpc.discovery.path", DISCOVERY_PATH)
			.with("jsonrpc.discovery.info.title", "Disclosure Audit API")
			.with("jsonrpc.discovery.info.version", "1.0.0"));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			// the published path answers, so the comparison below is against a live endpoint rather than a
			// server where nothing is mounted at all
			byte[] published = rawGet(port, DISCOVERY_PATH);
			assertStartsWith(published, "HTTP/1.1 200");
			assertTrue("the published path must serve the document",
				new String(published, UTF_8).contains("\"openrpc\""));

			byte[] unknown = rawGet(port, NEVER_CONFIGURED);
			assertStartsWith(unknown, "HTTP/1.1 404");

			// a child segment: an exact-path mount, never a subtree — otherwise a proxy rule written for
			// the exact path is bypassable by appending anything
			byte[] child = rawGet(port, DISCOVERY_PATH + "/anything");
			assertResponsesIdentical(NEVER_CONFIGURED, unknown, DISCOVERY_PATH + "/anything", child);
			assertNoDocument(child);

			// a near-miss on the last character: no prefix oracle either
			byte[] nearMiss = rawGet(port, DISCOVERY_PATH + "x");
			assertResponsesIdentical(NEVER_CONFIGURED, unknown, DISCOVERY_PATH + "x", nearMiss);
			assertNoDocument(nearMiss);
		} finally {
			stop(launcher);
		}
	}

	// ------------------------------------------------------------------ D3: SI-6 on the fail-closed message

	@Test
	public void theFailClosedStartupMessageNamesTheKeyAndNoConfiguredValue() throws Exception {
		// three canaries: the sibling value the throwing method itself read a moment earlier, the switch
		// value that enabled discovery in the first place, and an unrelated key's value. None of the three
		// may appear anywhere in the cause chain
		String versionCanary = "zzcanary-version-zz";
		String pathCanary = "/zzcanary-discovery-zz";
		String unrelatedCanary = "/zzcanary-rpc-path-zz";

		String messages = messagesOfFailedLaunch(launcher(Config.create()
			.with("jsonrpc.path", unrelatedCanary)
			.with("jsonrpc.discovery.path", pathCanary)
			.with("jsonrpc.discovery.info.version", versionCanary)));

		assertTrue("the failure must name the missing key: " + messages,
			messages.contains("jsonrpc.discovery.info.title"));
		assertCarriesNoValue(messages, versionCanary);
		assertCarriesNoValue(messages, pathCanary);
		assertCarriesNoValue(messages, unrelatedCanary);
	}

	@Test
	public void theNonKeyRejectionNamesTheKeyAndNoConfiguredValue() throws Exception {
		// the other fail-closed family, JsonRpcServerLauncher.rejectNonKeys: a bogus key under the newly
		// admitted discovery subtree is rejected naming the KEY. Its value — which an operator may well
		// have pasted a token into, since jsonrpc.discovery.info.contact reads like a place for one — must
		// not travel with it
		String valueCanary = "zzcanary-nonkey-value-zz";

		String messages = messagesOfFailedLaunch(launcher(
			Config.create().with("jsonrpc.discovery.info.contact", valueCanary)));

		assertTrue("the failure must name the offending key: " + messages,
			messages.contains("jsonrpc.discovery.info.contact"));
		assertCarriesNoValue(messages, valueCanary);
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * One bodyless {@code GET} on a fresh connection, read to EOF as raw bytes — status line, headers in
	 * emission order and body, exactly as they left the socket. An {@code HttpClient} would normalise the
	 * header order and drop the status line, which is the whole of what D1/D2 compare.
	 */
	private static byte[] rawGet(int port, String path) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(10_000);
			socket.getOutputStream().write(
				("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(US_ASCII));
			socket.getOutputStream().flush();
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			for (int read; (read = socket.getInputStream().read(chunk)) != -1; ) {
				received.write(chunk, 0, read);
			}
			return received.toByteArray();
		}
	}

	private static void assertResponsesIdentical(
		String controlPath, byte[] control, String probePath, byte[] probe
	) {
		assertArrayEquals(
			"the answer for " + probePath + " must be byte-identical to the answer for " + controlPath +
			", otherwise it is an oracle.\n  " + controlPath + " -> " + render(control) +
			"\n  " + probePath + " -> " + render(probe),
			control, probe);
	}

	private static void assertStartsWith(byte[] response, String expected) {
		String text = new String(response, UTF_8);
		assertTrue("expected a response starting with '" + expected + "', got " + render(response),
			text.startsWith(expected));
	}

	/** No 404 answer may carry the document, or any fragment of it, however it was reached. */
	private static void assertNoDocument(byte[] response) {
		String text = new String(response, UTF_8);
		assertFalse("a non-published path must never carry the OpenRPC document: " + render(response),
			text.contains("\"openrpc\"") || text.contains("\"methods\"") || text.contains("user.get"));
	}

	private static void assertCarriesNoValue(String messages, String canary) {
		assertFalse("a configured value leaked into the startup failure (SI-6): '" + canary +
					"' appears in:" + messages,
			messages.contains(canary));
	}

	private static String render(byte[] response) {
		return "[" + response.length + " bytes] " +
			   new String(response, UTF_8).replace("\r\n", "\\r\\n");
	}

	/**
	 * Launches on a dedicated thread, requires the launch to fail, and returns every message in the cause
	 * chain joined together — the whole text a diagnostic reader would see.
	 * <p>
	 * The dedicated thread is what keeps a <i>passing</i> launch from hanging Surefire: the info-key check
	 * fires during wiring (before {@code onStartFuture} exists) and {@code rejectNonKeys} fires in
	 * {@code onStart()}, so joining the launching thread is the one wait that covers both.
	 */
	private static String messagesOfFailedLaunch(Launcher launcher) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "jsonrpc-discovery-disclosure-launch");
		thread.start();
		thread.join(30_000);
		if (thread.isAlive()) {
			launcher.shutdown();
			thread.join(30_000);
			throw new AssertionError("startup must fail; it did not");
		}
		Throwable t = failure.get();
		assertTrue("launch must fail", t != null);

		StringBuilder messages = new StringBuilder();
		for (Throwable current = t; current != null; current = current.getCause()) {
			messages.append("\n  ").append(current.getClass().getName()).append(": ").append(current.getMessage());
		}
		return messages.toString();
	}

	private static JsonRpcServerLauncher launcher(Config overrides) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return new AbstractModule() {
					@ProvidesIntoSet
					JsonRpcServiceBinding userApi() {
						return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
					}
				};
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}
}
