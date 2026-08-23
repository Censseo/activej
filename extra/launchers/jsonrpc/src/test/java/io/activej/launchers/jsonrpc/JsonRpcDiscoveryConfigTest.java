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

import io.activej.async.callback.AsyncComputation;
import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.inject.Injector;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * US2, launcher half (FR-003, FR-013, FR-041, FR-042; {@code contracts/config-keys.md}): the three
 * {@code jsonrpc.discovery.*} keys, on <b>both</b> launchers.
 *
 * <h4>What is pinned here</h4>
 * <ul>
 *     <li><b>Presence is the switch</b> (ADR-042). {@code jsonrpc.discovery.path} carrying a value
 *     enables {@code rpc.discover} on the dispatcher <i>and</i> mounts a {@code GET} endpoint at
 *     that path — on {@link JsonRpcModule#rootServlet} and on
 *     {@link MultithreadedJsonRpcServerLauncher}'s {@code @Worker rootServlet} alike, the module's
 *     standing guard rail against editing one route site without the other.</li>
 *     <li><b>Fail-closed info keys</b> (ADR-037, FR-013). OpenRPC requires {@code info.title} and
 *     {@code info.version}; a deployment that enables discovery without one has asked for a document
 *     it has not described, so startup fails <b>naming the missing key</b>. The launcher never
 *     invents metadata.</li>
 *     <li><b>The non-key check admits the subtree, and only the subtree.</b> The three real keys
 *     start cleanly; a scalar {@code jsonrpc.discovery} — the plausible typo — and any other child
 *     of {@code jsonrpc.discovery} / {@code jsonrpc.discovery.info} are rejected at startup naming
 *     the key, the shape {@code jsonrpc.ws.*} and {@code jsonrpc.tcp.*} already have. The
 *     pre-existing rejections are unchanged, asserted rather than assumed.</li>
 *     <li><b>Off means absent</b> (FR-042). With no discovery key the path is answered exactly as
 *     any unmapped path (the router's {@code 404}), {@code rpc.discover} is {@code -32601} like any
 *     unregistered wire name, and the dispatcher holds no document at all — so there is nothing to
 *     probe and nothing to disclose on upgrade.</li>
 *     <li><b>One document, not two that agree</b> (FR-040). The {@code GET} body and the
 *     {@code result} member of the {@code rpc.discover} answer are compared, so the endpoint cannot
 *     drift from the method.</li>
 *     <li><b>The co-mount composes</b>. {@code jsonrpc.discovery.path} may equal {@code jsonrpc.path},
 *     and the method-agnostic mount is what keeps that working: {@code POST} still reaches the
 *     JSON-RPC servlet, {@code GET} reaches the discovery servlet, and any other method gets the
 *     discovery servlet's own {@code 405} + {@code Allow: GET} rather than the router's bare
 *     {@code 404}. Observed on a real server rather than inferred from {@code RoutingServlet}.</li>
 * </ul>
 *
 * Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * would take the Surefire JVM with it), and every server binds {@code :0} with the port read back
 * from the server (ADR-028).
 */
public class JsonRpcDiscoveryConfigTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String DISCOVERY_PATH = "/openrpc";
	private static final String TITLE = "Launcher Demo API";
	private static final String VERSION = "4.2.1";

	private static final String POST_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"user.get\",\"params\":[42]}";
	private static final String POST_RESULT = "\"result\":{\"id\":42,\"name\":\"user-42\"}";
	private static final String DISCOVER_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"rpc.discover\"}";

	/** The three keys a deployment sets to switch discovery on. */
	private static Config discoveryEnabled() {
		return Config.create()
			.with("jsonrpc.discovery.path", DISCOVERY_PATH)
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION);
	}

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	// ---------------------------------------------------------------- the route is mounted, both sites

	@Test
	public void discoveryKeysMountTheGetRouteOnTheSingleWorkerLauncher() throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(discoveryEnabled());
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			GetResponse document = get(Reactor.getCurrentReactor(), port, DISCOVERY_PATH);
			assertEquals(200, document.code());
			assertEquals("application/json", document.contentType());
			assertDescribesTheFixture(document.body());

			// FR-040: the GET endpoint and rpc.discover serve ONE array — not two that agree
			ReadResponse discover = post(Reactor.getCurrentReactor(), port, "/", DISCOVER_DOCUMENT);
			assertEquals(200, discover.code());
			assertTrue("the rpc.discover answer must carry the same document: " + discover.body(),
				discover.body().contains("\"result\":" + document.body()));

			// the POST route is untouched by the co-mount
			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void discoveryKeysMountTheGetRouteOnTheMultithreadedLauncher() throws Exception {
		// the second route site: MultithreadedJsonRpcServerLauncher's @Worker rootServlet. Each worker
		// computes the same document from the same contracts, so whichever worker the PrimaryServer
		// hands the connection to answers identically
		MultithreadedJsonRpcServerLauncher launcher = multiWorkerLauncher(discoveryEnabled(), null);
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.primaryServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			// one fresh connection per probe, so both workers are reached round-robin
			for (int i = 0; i < 4; i++) {
				GetResponse document = get(Reactor.getCurrentReactor(), port, DISCOVERY_PATH);
				assertEquals(200, document.code());
				assertEquals("application/json", document.contentType());
				assertDescribesTheFixture(document.body());
			}

			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void discoveryCoMountedOnThePostPathLeavesBothRoutesAnswering() throws Exception {
		// The load-bearing reason the GET route is mounted METHOD-AGNOSTICALLY (JsonRpcModule.mountDiscovery):
		// jsonrpc.discovery.path may legitimately equal jsonrpc.path, and nothing refuses that. RoutingServlet
		// prefers a method-specific slot over its any-method one, so POST keeps reaching JsonRpcServlet while
		// GET — and every other method — reaches the discovery servlet, which answers a non-GET with its OWN
		// 405 + Allow: GET. Mounting on GET alone would instead let a non-GET fall through to the router's
		// bare 404. OBSERVED here on a real server, not inferred from the router's source.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.path", "/")
			.with("jsonrpc.discovery.path", "/")
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			GetResponse document = request(Reactor.getCurrentReactor(), port, HttpMethod.GET, "/");
			assertEquals(200, document.code());
			assertEquals("application/json", document.contentType());
			assertDescribesTheFixture(document.body());

			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals("POST must still reach the JSON-RPC servlet on the shared path", 200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));

			GetResponse refused = request(Reactor.getCurrentReactor(), port, HttpMethod.DELETE, "/");
			assertEquals("a non-GET on the shared path is the discovery servlet's own 405, not a router 404",
				405, refused.code());
			assertEquals("GET", refused.allow());
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- fail-closed info keys (ADR-037)

	@Test
	public void aMissingInfoTitleFailsStartupNamingTheKey() throws Exception {
		assertStartupFailsNaming(singleWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.path", DISCOVERY_PATH)
				.with("jsonrpc.discovery.info.version", VERSION)),
			"jsonrpc.discovery.info.title");
	}

	@Test
	public void aMissingInfoVersionFailsStartupNamingTheKey() throws Exception {
		assertStartupFailsNaming(singleWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.path", DISCOVERY_PATH)
				.with("jsonrpc.discovery.info.title", TITLE)),
			"jsonrpc.discovery.info.version");
	}

	@Test
	public void aMissingInfoKeyFailsStartupOnTheMultithreadedLauncherToo() throws Exception {
		// the same fail-closed read is duplicated across both launchers' dispatcher providers — the
		// guard rail applies to it exactly as it does to the two route sites
		assertStartupFailsNaming(multiWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.path", DISCOVERY_PATH)
				.with("jsonrpc.discovery.info.version", VERSION), null),
			"jsonrpc.discovery.info.title");
	}

	// ---------------------------------------------------------------- the non-key check

	@Test
	public void aScalarDiscoveryValueFailsStartupNamingTheKey() throws Exception {
		// `jsonrpc.discovery=/openrpc` is the plausible typo for jsonrpc.discovery.path: the node carries
		// no children, so a child-key loop alone would walk an empty map and start silently with discovery
		// OFF. Rejected outright, exactly as a scalar jsonrpc.ws / jsonrpc.tcp is
		assertStartupFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.discovery", DISCOVERY_PATH)),
			"'jsonrpc.discovery'");
	}

	@Test
	public void anUnknownDiscoveryChildKeyFailsStartupNamingTheKey() throws Exception {
		assertStartupFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.discovery.bogus", "1")),
			"jsonrpc.discovery.bogus");
	}

	@Test
	public void anUnknownDiscoveryInfoChildKeyFailsStartupNamingTheKey() throws Exception {
		assertStartupFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.discovery.info.contact", "nobody@example.com")),
			"jsonrpc.discovery.info.contact");
	}

	@Test
	public void thePreExistingNonKeyRejectionsAreUnchanged() throws Exception {
		// the regression half of the admission change: admitting the discovery subtree must not have
		// widened — or narrowed — the check. A jsonrpc.ws.* non-key and a feature-09 non-key are still
		// rejected, naming the key and (for the latter) the property that does work, exactly as before
		assertStartupFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.ws.bogus", "1")),
			"jsonrpc.ws.bogus");

		assertStartupFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.maxBatchSize", "10")),
			"jsonrpc.maxBatchSize", "-DJsonRpcLimits.maxBatchSize");
	}

	@Test
	public void theThreeDiscoveryKeysAreAdmittedByTheNonKeyCheck() throws Exception {
		// the positive half: the three real keys must NOT trip the fail-closed check. Launching to a
		// completed start future is the assertion — onStart() is where rejectNonKeys runs
		JsonRpcServerLauncher launcher = singleWorkerLauncher(discoveryEnabled());
		LauncherTestHarness.launch(launcher);
		stop(launcher);
	}

	// ---------------------------------------------------------------- discovery off (FR-042)

	@Test
	public void withNoDiscoveryKeyThePathIsUnmappedAndTheDispatcherHoldsNoDocument() throws Exception {
		AtomicReference<Injector> injectorRef = new AtomicReference<>();
		JsonRpcServerLauncher launcher = new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				injectorRef.set(injector);
			}

			@Override
			Config config() {
				return super.config().overrideWith(Config.create().with("http.listenAddresses", "0"));
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			// FR-042: the endpoint does not exist — the router's own 404, indistinguishable from any
			// path this deployment never mounted
			GetResponse unmapped = get(Reactor.getCurrentReactor(), port, DISCOVERY_PATH);
			assertEquals(404, unmapped.code());

			// FR-004: and rpc.discover is -32601, indistinguishable from any unregistered wire name
			ReadResponse discover = post(Reactor.getCurrentReactor(), port, "/", DISCOVER_DOCUMENT);
			assertEquals(200, discover.code());
			assertTrue("rpc.discover must be method-not-found when discovery is off: " + discover.body(),
				discover.body().contains("-32601"));

			// no withDiscovery(...) reached the builder at all — null IS the whole of "off"
			JsonRpcDispatcher dispatcher = injectorRef.get().getInstance(JsonRpcDispatcher.class);
			assertNull("the dispatcher must hold no document when discovery is off",
				await(discoveryDocumentOf(launcher.httpServer.getReactor(), dispatcher)));

			// and the pre-existing routes are byte-for-byte what they were
			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void anEmptyDiscoveryPathIsTheSameAsNoKeyAtAll() throws Exception {
		// ADR-042: an EMPTY value is off, exactly as an absent one — and the two info keys are then not
		// required, because nothing was enabled
		JsonRpcServerLauncher launcher = singleWorkerLauncher(
			Config.create().with("jsonrpc.discovery.path", ""));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			GetResponse unmapped = get(Reactor.getCurrentReactor(), port, DISCOVERY_PATH);
			assertEquals(404, unmapped.code());

			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- helpers

	/** The document must be a real OpenRPC document describing this module's fixture service. */
	private static void assertDescribesTheFixture(String body) {
		assertTrue("not an OpenRPC document: " + body, body.contains("\"openrpc\""));
		assertTrue("the application's title must be carried verbatim: " + body,
			body.contains("\"title\":\"" + TITLE + "\""));
		assertTrue("the application's version must be carried verbatim: " + body,
			body.contains("\"version\":\"" + VERSION + "\""));
		assertTrue("every registered wire name must be described: " + body, body.contains("user.get"));
		assertTrue("every registered wire name must be described: " + body, body.contains("user.touch"));
	}

	private record GetResponse(int code, String contentType, String allow, String body) {}

	private static GetResponse get(NioReactor reactor, int port, String path) {
		return request(reactor, port, HttpMethod.GET, path);
	}

	/** One bodyless request, one fresh connection; everything asserted on is captured inside the exchange. */
	private static GetResponse request(NioReactor reactor, int port, HttpMethod method, String path) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest request = HttpRequest.builder(method, "http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONNECTION, "close")
			.build();
		return await(httpClient.request(request)
			.then(response -> response.loadBody()
				.map(body -> new GetResponse(
					response.getCode(),
					response.getHeader(HttpHeaders.CONTENT_TYPE),
					response.getHeader(HttpHeaders.ALLOW),
					body.getString(UTF_8)))));
	}

	private static Promise<byte[]> discoveryDocumentOf(Reactor reactor, JsonRpcDispatcher dispatcher) {
		return Promise.ofCompletionStage(
			((Eventloop) reactor).submit(AsyncComputation.of(dispatcher::discoveryDocument)));
	}

	/**
	 * Launches on a dedicated thread and asserts that startup <b>fails</b> with a message naming every
	 * {@code needle}, wherever in the cause chain it was raised.
	 * <p>
	 * The thread is what makes the <b>failing-first</b> state observable rather than fatal: with the check
	 * not yet implemented the launcher starts happily and blocks in {@code awaitShutdown()} forever, so
	 * this helper tears it down and reports a failed assertion instead of hanging Surefire. The two
	 * failure sites also differ in kind — the info-key check fires during <b>wiring</b> (before
	 * {@code onStartFuture} exists at all, so waiting on that future would time out) while
	 * {@code rejectNonKeys} fires in {@code onStart()} — and joining the launching thread covers both.
	 */
	private static void assertStartupFailsNaming(Launcher launcher, String... needles) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				failure.set(t);
			}
		}, "jsonrpc-discovery-config-launch");
		thread.start();
		thread.join(30_000);
		if (thread.isAlive()) {
			// the failing-first state: nothing was rejected and the launcher is running
			launcher.shutdown();
			thread.join(30_000);
			throw new AssertionError("startup must fail, naming " + String.join(" and ", needles));
		}
		Throwable t = failure.get();
		if (t == null) throw new AssertionError("launch returned without failing");
		for (String needle : needles) {
			assertCauseChainNames(t, needle);
		}
	}

	/** A startup failure must NAME the offending key, wherever in the cause chain it was raised. */
	private static void assertCauseChainNames(Throwable failure, String needle) {
		StringBuilder seen = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			String message = current.getMessage();
			if (message == null) continue;
			seen.append("\n  ").append(current.getClass().getSimpleName()).append(": ").append(message);
			if (message.contains(needle)) return;
		}
		throw new AssertionError("no message in the cause chain names '" + needle + "':" + seen);
	}

	private static JsonRpcServerLauncher singleWorkerLauncher(Config overrides) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			Config config() {
				// :0 keeps the test off the 8080 default — a rejection raised in onStart() runs only
				// after the service graph has started, so the bind must not be able to fail first
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	private static MultithreadedJsonRpcServerLauncher multiWorkerLauncher(
		Config overrides, AtomicReference<Injector> injectorRef
	) {
		return new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				if (injectorRef != null) injectorRef.set(injector);
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", "2"))
					.overrideWith(overrides);
			}

			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	/** The fixture service binding every test launches with. */
	private static Module businessLogic() {
		return new AbstractModule() {
			@ProvidesIntoSet
			JsonRpcServiceBinding userApi() {
				return new JsonRpcServiceBinding(UserApi.class, new UserApiImpl());
			}
		};
	}
}
