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

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.exception.MalformedDataException;
import io.activej.config.Config;
import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpServer;
import io.activej.inject.Injector;
import io.activej.inject.annotation.ProvidesIntoSet;
import io.activej.inject.module.AbstractModule;
import io.activej.inject.module.Module;
import io.activej.json.JsonUtils;
import io.activej.jsonrpc.schema.OpenRpcDocument;
import io.activej.jsonrpc.schema.OpenRpcMethod;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.transport.ws.JsonRpcWsTransport;
import io.activej.launcher.Launcher;
import io.activej.launchers.jsonrpc.LauncherTestHarness.ReadResponse;
import io.activej.launchers.jsonrpc.fixtures.User;
import io.activej.launchers.jsonrpc.fixtures.UserApi;
import io.activej.launchers.jsonrpc.fixtures.UserApiImpl;
import io.activej.net.PrimaryServer;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.post;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The adversarial half of {@link JsonRpcDiscoveryConfigTest}: the three {@code jsonrpc.discovery.*}
 * keys under attack, on <b>both</b> launchers. Oracle throughout is the code — {@code JsonRpcModule}
 * ({@code discoveryPath}, {@code discoveryInfo}, {@code requiredInfoMember}, {@code mountDiscovery}),
 * {@code JsonRpcServerLauncher.rejectNonKeys} and {@code RoutingServlet} — read before each scenario
 * was written, never "whatever came out of the run".
 *
 * <h4>A1 — a config value is untrusted text that lands inside generated JSON</h4>
 * {@code jsonrpc.discovery.info.title} and {@code .version} are the only two places in this whole
 * feature where an <b>operator-supplied string</b> is copied into a document that is then published
 * to every client. The sharp question is therefore not "is a weird title accepted" but "can a title
 * <b>break out of its JSON string</b>". Four payload families are driven end to end — emoji / RTL /
 * CJK, embedded control characters ({@code NUL}, {@code BEL}, {@code LF}), a quote-plus-backslash
 * payload that closes the string and appends a forged {@code methods} array, and a ~1 MB value — and
 * each is checked three ways: the launcher accepts it (no length rule is documented, so none is
 * asserted), the served bytes are <b>valid JSON</b> (they parse, and carry no raw control character —
 * RFC 8259 §7), and the value <b>decodes back identically</b>. The forged-array payload additionally
 * has to leave the {@code methods} list exactly the fixture's two wire names: escaping that "works"
 * but drops a member would pass a round-trip check and still be a break-out.
 *
 * <h4>A2 — whitespace is not emptiness</h4>
 * Both switches are {@code isEmpty()} tests over a raw config value, and {@code " ".isEmpty()} is
 * {@code false}. The two halves of that turn out to deserve <b>opposite</b> verdicts, and both are
 * pinned here:
 * <ul>
 *     <li>{@code jsonrpc.discovery.path=" "} is <b>correct as it stands</b>. It is neither treated as
 *     "off" (which would silently ignore an operator who asked for discovery) nor mounted (a router
 *     path must start with {@code /}): {@code RoutingServlet}'s own {@code checkArgument} refuses it
 *     at wiring time, so the deployment fails loudly and nothing binds. Pinned as a characterization
 *     test, including the fact that the message is the router's and never names the key — the same
 *     deliberate posture {@code JsonRpcTcpMountAdversarialTest} F2 pins for {@code jsonrpc.tcp.port}.</li>
 *     <li>{@code jsonrpc.discovery.info.title="   "} was a <b>defect</b>, found here and fixed:
 *     {@code isEmpty()} admitted a whitespace-only value, so a deployment could publish a document
 *     with a blank name — precisely the "asked for a document it has not named" state the FR-013
 *     check exists to refuse. {@code requiredInfoMember} now tests {@code isBlank()}. The value is
 *     still <b>never trimmed</b>: {@code "  Padded API  "} is accepted and carried verbatim, which
 *     the regression test below pins so the fix cannot drift into mutating application-supplied
 *     metadata.</li>
 * </ul>
 *
 * <h4>A3 — the second HTTP-mounted collision</h4>
 * {@code JsonRpcDiscoveryConfigTest} covers {@code jsonrpc.discovery.path == jsonrpc.path}. The other
 * route that shares the same {@link io.activej.http.RoutingServlet} is the WebSocket one, and it
 * lands on a <b>different slot</b> ({@code WS_ORDINAL}) rather than a method slot — so the collision
 * resolves for a different reason than the POST one and has to be observed rather than reasoned
 * about: a real upgrade + JSON-RPC call and a plain {@code GET} are both driven against one shared
 * path.
 *
 * <h4>A4 — the closed key set was closed one level deep only</h4>
 * A second defect, found here and fixed: {@code jsonrpc.discovery.info.title.extra=x} used to start
 * silently. The child-key loops walked the immediate children of {@code discovery} and of
 * {@code discovery.info}, so a <b>grandchild of a leaf key</b> configured nothing and was rejected by
 * nothing — while the launcher's own Javadoc promises that "every {@code jsonrpc.discovery.*} key
 * outside the three above" fails startup loudly. The three discovery keys are now checked to be
 * leaves. The {@code ws.*} / {@code tcp.*} subtrees have the same one-level shape and are
 * <b>deliberately left alone</b> (features 015/017 own them); that asymmetry is itself pinned below,
 * so tightening it later is a visible edit rather than a surprise.
 *
 * <h4>A5 — case sensitivity, at three depths</h4>
 * A wrong-case key is only harmless if it is either ignored <i>with discovery off</i> or rejected —
 * never "ignored while something else quietly enables the endpoint". All three depths are probed:
 * {@code Jsonrpc.Discovery.Path} (not under {@code jsonrpc} at all), {@code jsonrpc.Discovery.path}
 * (an unknown child of {@code jsonrpc}, which that launcher's check does not close over) and
 * {@code jsonrpc.discovery.Path} (inside the subtree that <b>is</b> closed, hence refused).
 *
 * <h4>A6 — the multi-worker launcher fails whole, not partly</h4>
 * The info-key read sits in a {@code @Worker} provider there, so the natural worry is N workers
 * failing N times, or worker 1 listening while worker 2 refuses. It cannot happen, and the proof is
 * structural rather than timing-based: the failure fires inside {@code postInjectInstances}, which
 * {@code Launcher.launch} runs <b>before</b> {@code startServices(...)} — so no {@link PrimaryServer}
 * instance is ever constructed, the {@code @Inject} field stays {@code null}, and no dispatcher MBean
 * is ever registered.
 *
 * <p>Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * would take the Surefire JVM with it), and every server binds {@code :0} with the port read back
 * from the server (ADR-028).
 */
public class JsonRpcDiscoveryConfigAdversarialTest {
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

	/** The fixture's two wire names — what {@code methods} must contain, no matter what the info says. */
	private static final List<String> FIXTURE_WIRE_NAMES = List.of("user.get", "user.touch");

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	// -------------------------------------------------------------------------------------------
	// A1 — operator-supplied text inside the generated document (P0)
	// -------------------------------------------------------------------------------------------

	@Test
	public void emojiRtlAndCjkInfoValuesSurviveTheDocumentVerbatim() throws Exception {
		// A1: non-BMP (the rocket is a surrogate pair), right-to-left Arabic and CJK, in both members.
		// Whether the encoder emits them as raw UTF-8 or as backslash-u escapes is its business and is
		// NOT asserted; what IS asserted is that the bytes parse and the values come back identical —
		// including the surrogate pair, which a naive char-wise escaper would split.
		String title = "🚀 مرحبا 日本語 API";
		String version = "1.0.0-β+build.日本";

		Served served = serveWith(title, version);

		assertCarriedVerbatim(served, title, version);
	}

	@Test
	public void controlCharactersInInfoValuesAreEscapedNeverEmittedRaw() throws Exception {
		// A1, the RFC 8259 §7 half: a raw character below U+0020 inside a JSON string is INVALID JSON,
		// and — on the line's other transports — a raw LF is a frame boundary (ADR-044), so an
		// unescaped one in the document would cut the JSON-Lines framing in half. NUL, BEL, LF, CR and
		// TAB are driven through both members at once.
		// built from char codes rather than escape literals: a unicode escape is processed before this
		// file is tokenized, so writing one here would put a raw control character in the SOURCE too
		String title = "a" + (char) 0 + "b" + (char) 7 + "c\nd\re\tf";
		String version = "1" + (char) 1 + "0";

		Served served = serveWith(title, version);

		assertNoRawControlCharacters(served.raw());
		assertCarriedVerbatim(served, title, version);
	}

	@Test
	public void aQuoteAndBackslashPayloadCannotBreakOutOfTheJsonString() throws Exception {
		// A1, the injection half — the sharpest check in this class. The version payload closes its own
		// string, closes the info object and opens a forged `methods` array carrying a method the
		// service does not implement. If the encoder concatenated instead of escaping, the served
		// document would parse (that is what makes the payload dangerous) and would advertise
		// "admin.deleteEverything" to every client that reads it.
		String title = "He said \"hi\"\\";
		String version = "1.0\",\"x\":1},\"methods\":[{\"name\":\"admin.deleteEverything\",\"params\":[]}],\"z\":\"";

		Served served = serveWith(title, version);

		assertCarriedVerbatim(served, title, version);
		// the forged text IS present in the bytes — inside the escaped version string, where it belongs
		// and where it is inert. What must be absent is its UNESCAPED form: the payload becoming an
		// actual member of the document rather than characters of a value
		assertTrue("the payload must be present, escaped, inside the version value: " + served.raw(),
			served.raw().contains("admin.deleteEverything"));
		assertFalse("the forged entry must never become a real member: " + served.raw(),
			served.raw().contains("{\"name\":\"admin.deleteEverything\""));

		// and the same bytes ride rpc.discover — where the document is re-emitted as a RAW slice into
		// the JSON-RPC envelope, so a broken escape there would corrupt the response as well
		JsonRpcServerLauncher launcher = singleWorkerLauncher(discoveryEnabled(title, version));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			ReadResponse discover = post(Reactor.getCurrentReactor(), port, "/", DISCOVER_DOCUMENT);
			assertEquals(200, discover.code());
			assertTrue("rpc.discover must carry the very same escaped bytes: " + discover.body(),
				discover.body().contains("\"result\":" + served.raw()));
			assertFalse("the forged entry must never become a real member of the rpc.discover answer: "
				+ discover.body(), discover.body().contains("{\"name\":\"admin.deleteEverything\""));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void aOneMegabyteInfoTitleIsAcceptedAndServedIntact() throws Exception {
		// A1: no length rule is documented for either info member, so none is asserted — what IS
		// asserted is that the absence of a rule is honest. A ~1 MB title must not be truncated by the
		// generator, refused by the servlet's zero-copy withBody(byte[]) path, or clipped by the
		// connection tier on the way out (the document rides an HTTP response body, which has no
		// jsonrpc.maxBodySize equivalent — that key bounds inbound POST bodies only).
		String title = "A".repeat(1024 * 1024);

		Served served = serveWith(title, VERSION);

		assertTrue("the served document must be at least as large as the title it carries: "
			+ served.raw().length(), served.raw().length() > 1024 * 1024);
		assertCarriedVerbatim(served, title, VERSION);
	}

	// -------------------------------------------------------------------------------------------
	// A2 — whitespace-only values (P0)
	// -------------------------------------------------------------------------------------------

	@Test
	public void aWhitespaceOnlyDiscoveryPathIsRefusedLoudlyRatherThanSilentlyDisabled() throws Exception {
		// A2, CHARACTERIZATION — the behaviour is judged CORRECT and is pinned, not changed.
		// JsonRpcModule.discoveryPath tests isEmpty(), so " " is NOT "off": the endpoint is switched on
		// and the path is handed to RoutingServlet, which refuses anything that does not start with
		// "/" (doMap's checkArgument). Both alternatives would be worse: treating " " as off would
		// silently ignore an operator who asked for discovery, and mounting it would create a route no
		// HTTP client can address. The failure is the ROUTER's message and deliberately does not name
		// the config key — the same posture JsonRpcTcpMountAdversarialTest F2 pins for a hostile
		// jsonrpc.tcp.port, where the raw JDK message stands unwrapped.
		Failure failure = launchExpectingFailure(singleWorkerLauncher(Config.create()
			.with("jsonrpc.discovery.path", " ")
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION)));

		System.out.println("A2 [discovery.path=\" \"] -> " + failure.chain());
		assertTrue("a non-'/' path must be refused by the router: " + failure.chain(),
			failure.chain().contains("Invalid path"));
		assertFalse("the router's refusal is deliberately NOT re-wrapped to name the config key — the same "
					+ "posture jsonrpc.tcp.port's raw JDK failures are pinned with: " + failure.chain(),
			failure.chain().contains("jsonrpc.discovery.path"));
		assertNothingEverListened(failure.launcher());
	}

	@Test
	public void aWhitespaceOnlyInfoTitleIsRefusedNamingTheKey() throws Exception {
		// A2, the DEFECT this class found (fixed in JsonRpcModule.requiredInfoMember: isEmpty ->
		// isBlank). "   " is not a name. The FR-013 check exists so that a published document is never
		// unnamed, and an emptiness test that admits whitespace lets exactly that document out — under
		// a title every consumer UI renders as blank.
		assertFailsNaming(singleWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.path", DISCOVERY_PATH)
				.with("jsonrpc.discovery.info.title", "   ")
				.with("jsonrpc.discovery.info.version", VERSION)),
			"jsonrpc.discovery.info.title");
	}

	@Test
	public void aWhitespaceOnlyInfoVersionIsRefusedNamingTheKey() throws Exception {
		// A2: the same rule on the other required member, with a tab/newline mix rather than spaces —
		// isBlank() covers every Character.isWhitespace, which a hand-rolled " ".equals(...) would not.
		assertFailsNaming(singleWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.path", DISCOVERY_PATH)
				.with("jsonrpc.discovery.info.title", TITLE)
				.with("jsonrpc.discovery.info.version", "\t\n ")),
			"jsonrpc.discovery.info.version");
	}

	@Test
	public void surroundingWhitespaceIsCarriedVerbatimAndNeverTrimmed() throws Exception {
		// A2, the regression guard on the fix above: the emptiness CHECK trims, the VALUE never does.
		// Trimming the value would be the obvious "helpful" implementation and would violate the rule
		// the whole Info Object is built on — application-supplied, carried as supplied (FR-013, M9).
		String title = "  Padded API  ";
		String version = " 1.0 ";

		Served served = serveWith(title, version);

		assertCarriedVerbatim(served, title, version);
		assertTrue("the padding must be present in the served bytes, not silently stripped: " + served.raw(),
			served.raw().contains("\"title\":\"  Padded API  \""));
	}

	// -------------------------------------------------------------------------------------------
	// A3 — collision with the WebSocket mount (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void discoveryCoMountedOnTheWebSocketPathLeavesBothRoutesAnswering() throws Exception {
		// A3: the second co-mount case. Unlike the POST collision (which resolves because a
		// method-specific slot outranks the any-method one), this one resolves because a WebSocket
		// upgrade is routed by PROTOCOL: RoutingServlet.tryServe picks WS_ORDINAL for a ws:// request,
		// and getOrDefault deliberately does NOT fall back from WS_ORDINAL to the any-method slot. So
		// the two mounts occupy different slots on the same path node and neither shadows the other —
		// OBSERVED here with a real upgrade and a real call, not inferred from the router's source.
		String shared = "/ws";
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.ws.path", shared)
			.with("jsonrpc.discovery.path", shared)
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			// a plain GET on the shared path is the discovery document
			GetResponse document = get(port, shared);
			assertEquals(200, document.code());
			assertEquals("application/json", document.contentType());
			assertEquals(FIXTURE_WIRE_NAMES, wireNames(decode(document.body())));

			// ... and a real WebSocket upgrade on the very same path still reaches the JSON-RPC session
			assertEquals(new User(7, "user-7"), await(wsGetUser(port, shared, 7)));

			// ... and a non-GET is the discovery servlet's own 405, never the router's bare 404
			GetResponse refused = request(port, HttpMethod.DELETE, shared);
			assertEquals(405, refused.code());
			assertEquals("GET", refused.allow());

			// ... and the POST route is untouched by either
			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void aWildcardDiscoveryPathMakesTheDocumentTheCatchAllAnswer() throws Exception {
		// A3, CHARACTERIZATION: the launcher passes jsonrpc.discovery.path to RoutingServlet verbatim,
		// so the router's full pattern grammar — including the "/*" tail wildcard — is part of this
		// key's accepted surface. Mounting "/*" therefore turns the document into the answer for every
		// path the deployment does not otherwise route, which is a real (and deliberate, since the
		// operator wrote it) amplification of the disclosure surface. Pinned so that a future decision
		// to refuse wildcards here is a visible edit rather than an accident.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.discovery.path", "/*")
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			for (String path : List.of("/", "/openrpc", "/nothing/here/at/all")) {
				GetResponse document = get(port, path);
				assertEquals("a wildcard mount answers every GET path: " + path, 200, document.code());
				assertEquals(FIXTURE_WIRE_NAMES, wireNames(decode(document.body())));
			}

			// the POST route still wins on its own path — the wildcard lives in the fallback slot
			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	// -------------------------------------------------------------------------------------------
	// A4 — the closed child-key set, at the leaves (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void aGrandchildOfInfoTitleFailsStartupNamingTheKey() throws Exception {
		// A4, the second DEFECT this class found (fixed in rejectNonKeys: the three discovery keys are
		// now required to be leaves). `jsonrpc.discovery.info.title.extra` is a jsonrpc.discovery.*
		// key outside the three real ones — which JsonRpcServerLauncher's own Javadoc says fails
		// startup loudly — yet the child-key loop only ever saw `title`, which is admitted, and the
		// grandchild configured nothing while being rejected by nothing.
		assertFailsNaming(singleWorkerLauncher(discoveryEnabled(TITLE, VERSION)
				.with("jsonrpc.discovery.info.title.extra", "x")),
			"jsonrpc.discovery.info.title.extra");
	}

	@Test
	public void aGrandchildOfDiscoveryPathFailsStartupNamingTheKey() throws Exception {
		// A4: the same hole under the other leaf key, and with discovery otherwise perfectly valid —
		// so nothing else would have failed and the typo would have shipped.
		assertFailsNaming(singleWorkerLauncher(discoveryEnabled(TITLE, VERSION)
				.with("jsonrpc.discovery.path.enabled", "true")),
			"jsonrpc.discovery.path.enabled");
	}

	@Test
	public void aGrandchildOfInfoVersionIsRejectedEvenWhileDiscoveryIsOff() throws Exception {
		// A4: rejection does not depend on the endpoint being switched on — exactly like the
		// pre-existing `jsonrpc.discovery.bogus` rule, and for the same reason: the operator who wrote
		// the key is owed the news that it does nothing, whatever else the file says.
		assertFailsNaming(singleWorkerLauncher(Config.create()
				.with("jsonrpc.discovery.info.version.major", "4")),
			"jsonrpc.discovery.info.version.major");
	}

	@Test
	public void theWsAndTcpSubtreesKeepTheirOneLevelCheckByDesign() throws Exception {
		// A4, CHARACTERIZATION of a deliberate ASYMMETRY. `jsonrpc.ws.path.bogus` and
		// `jsonrpc.tcp.port.bogus` are tolerated in exactly the way `jsonrpc.discovery.path.bogus` no
		// longer is: those two subtrees belong to features 015 and 017, and tightening them is their
		// owners' call, not a discovery feature's. Pinned so the inconsistency is a recorded decision
		// with a test naming it, rather than something a later reader discovers by surprise.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("jsonrpc.ws.path", "/ws")
			.with("jsonrpc.ws.path.bogus", "1")
			.with("jsonrpc.tcp.port.bogus", "1"));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals("the launcher must have started with both tolerated grandchildren", 200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	// -------------------------------------------------------------------------------------------
	// A5 — case sensitivity (P1)
	// -------------------------------------------------------------------------------------------

	@Test
	public void wrongCaseKeysOutsideTheClosedSubtreeAreIgnoredAndLeaveDiscoveryOff() throws Exception {
		// A5: `Jsonrpc.Discovery.Path` is not under `jsonrpc` at all, and `jsonrpc.Discovery.path` is an
		// unknown CHILD of jsonrpc — a level rejectNonKeys does not close over (it names four
		// feature-09 keys and three subtrees, not an allow-list of every jsonrpc.* child). Neither can
		// enable anything: Config keys are case-sensitive, so both leave discovery OFF and the failure
		// mode is a route that does not exist — the fail-closed direction. Note the two info keys are
		// supplied in their CORRECT spelling here and are still not required, because nothing switched
		// discovery on.
		JsonRpcServerLauncher launcher = singleWorkerLauncher(Config.create()
			.with("Jsonrpc.Discovery.Path", DISCOVERY_PATH)
			.with("jsonrpc.Discovery.path", DISCOVERY_PATH)
			.with("jsonrpc.discovery.info.title", TITLE)
			.with("jsonrpc.discovery.info.version", VERSION));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();

			assertEquals("a wrong-case key must not have mounted anything", 404, get(port, DISCOVERY_PATH).code());

			ReadResponse discover = post(Reactor.getCurrentReactor(), port, "/", DISCOVER_DOCUMENT);
			assertEquals(200, discover.code());
			assertTrue("rpc.discover must stay method-not-found: " + discover.body(),
				discover.body().contains("-32601"));

			ReadResponse call = post(Reactor.getCurrentReactor(), port, "/", POST_DOCUMENT);
			assertEquals(200, call.code());
			assertTrue("unexpected body: " + call.body(), call.body().contains(POST_RESULT));
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void aWrongCaseChildInsideTheClosedSubtreeIsRejectedNamingTheKey() throws Exception {
		// A5: one level deeper the answer flips, and that is the right flip. `jsonrpc.discovery.Path`
		// IS inside the subtree the check closes over, so it is not "an unrelated key" — it is a
		// non-key, refused by name, which is what tells the operator their capital P did nothing.
		assertFailsNaming(
			singleWorkerLauncher(Config.create().with("jsonrpc.discovery.Path", DISCOVERY_PATH)),
			"jsonrpc.discovery.Path");
	}

	// -------------------------------------------------------------------------------------------
	// A6 — the multi-worker launcher fails whole (P0)
	// -------------------------------------------------------------------------------------------

	@Test
	public void aBlankInfoTitleFailsTheWholeMultithreadedLauncherBeforeAnyWorkerStarts() throws Exception {
		// A6: the info read lives in a @Worker provider on this launcher, so "worker 1 up, worker 2
		// refused" is the failure mode worth ruling out. It cannot occur, and the proof is structural:
		// the read happens while the PrimaryServer's dependency graph is being instantiated inside
		// Launcher.postInjectInstances, which runs BEFORE startServices(...). Asserted through the
		// injector captured in onInit (peekInstance never constructs): no PrimaryServer object exists,
		// so no acceptor was ever built, let alone bound — and no dispatcher MBean was registered,
		// which JmxModule's LauncherService only does once services start.
		MultithreadedJsonRpcServerLauncher launcher = multiWorkerLauncher(Config.create()
			.with("jsonrpc.discovery.path", DISCOVERY_PATH)
			.with("jsonrpc.discovery.info.title", "   ")
			.with("jsonrpc.discovery.info.version", VERSION), 4);
		Failure failure = launchExpectingFailure(launcher);

		System.out.println("A6 [multithreaded, blank title] -> " + failure.chain());
		assertTrue("the missing key must be named: " + failure.chain(),
			failure.chain().contains("jsonrpc.discovery.info.title"));
		assertNull("postInjectInstances never completed, so the launcher's own @Inject field is untouched",
			launcher.primaryServer);

		Injector injector = capturedInjectors.get(failure.launcher());
		assertNull("no acceptor may exist: the failure precedes startServices(...)",
			injector.peekInstance(PrimaryServer.class));
		assertTrue("no worker may have reached the started state (JMX registration is a service)",
			LauncherTestHarness.dispatcherBeans().isEmpty());
	}

	@Test
	public void aGrandchildNonKeyFailsTheMultithreadedLauncherToo() throws Exception {
		// A6: the other failure SITE — rejectNonKeys runs in onStart(), which Launcher.launch calls
		// AFTER startServices(...), so this one does bind and then unwind. Both launchers share the
		// one helper, and the multi-worker launcher must not be the copy that drifts (the module's
		// standing two-route-sites hazard applies to the two onStart() sites just as much).
		assertFailsNaming(multiWorkerLauncher(discoveryEnabled(TITLE, VERSION)
				.with("jsonrpc.discovery.info.title.extra", "x"), 2),
			"jsonrpc.discovery.info.title.extra");
	}

	// -------------------------------------------------------------------------------------------
	// Shared harness
	// -------------------------------------------------------------------------------------------

	/** The three keys a deployment sets to switch discovery on, with the info values under test. */
	private static Config discoveryEnabled(String title, String version) {
		return Config.create()
			.with("jsonrpc.discovery.path", DISCOVERY_PATH)
			.with("jsonrpc.discovery.info.title", title)
			.with("jsonrpc.discovery.info.version", version);
	}

	private record Served(String raw, OpenRpcDocument decoded) {}

	/**
	 * Launches with the given info values, {@code GET}s the discovery document once and returns it both
	 * raw and decoded. The launcher is stopped before returning — everything asserted on is captured.
	 */
	private static Served serveWith(String title, String version) throws Exception {
		JsonRpcServerLauncher launcher = singleWorkerLauncher(discoveryEnabled(title, version));
		LauncherTestHarness.launch(launcher);
		try {
			int port = launcher.httpServer.getBoundAddresses().get(0).getPort();
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			GetResponse document = get(port, DISCOVERY_PATH);
			assertEquals(200, document.code());
			assertEquals("application/json", document.contentType());
			return new Served(document.body(), decode(document.body()));
		} finally {
			stop(launcher);
		}
	}

	/** Both info members come back byte-identical, and the method list is untouched by whatever they carried. */
	private static void assertCarriedVerbatim(Served served, String title, String version) {
		assertEquals("the title must decode back to exactly what the deployment configured",
			title, served.decoded().info().title());
		assertEquals("the version must decode back to exactly what the deployment configured",
			version, served.decoded().info().version());
		assertEquals("the described method set must be exactly the fixture's, whatever the info carried",
			FIXTURE_WIRE_NAMES, wireNames(served.decoded()));
	}

	private static List<String> wireNames(OpenRpcDocument document) {
		return document.methods().stream().map(OpenRpcMethod::name).sorted().toList();
	}

	/** RFC 8259 §7: a character below {@code U+0020} inside a JSON string MUST be escaped. */
	private static void assertNoRawControlCharacters(String json) {
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c < 0x20) {
				fail(String.format("an unescaped control character U+%04X at offset %d makes the document " +
								   "invalid JSON (RFC 8259 §7) and would cut JSON-Lines framing in half",
					(int) c, i));
			}
		}
	}

	private static OpenRpcDocument decode(String json) {
		try {
			return JsonUtils.fromJson(OpenRpcDocument.CODEC, json);
		} catch (MalformedDataException e) {
			throw new AssertionError("the served document is not a decodable OpenRPC document: " + json, e);
		}
	}

	private record GetResponse(int code, String contentType, String allow, String body) {}

	private static GetResponse get(int port, String path) {
		return request(port, HttpMethod.GET, path);
	}

	/** One bodyless request, one fresh connection; everything asserted on is captured inside the exchange. */
	private static GetResponse request(int port, HttpMethod method, String path) {
		NioReactor reactor = Reactor.getCurrentReactor();
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

	/** One WebSocket upgrade, one call, one close — all inside the awaited chain. */
	private static Promise<User> wsGetUser(int port, String path, long id) {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		return JsonRpcWsTransport.connect(reactor, httpClient,
				HttpRequest.get("ws://127.0.0.1:" + port + path).build())
			.then(transport -> JsonRpcClient.builder(reactor, transport).build()
				.proxy(UserApi.class).getUser(id)
				.whenComplete(() -> transport.closeEx(new AsyncCloseException())));
	}

	private record Failure(Launcher launcher, Throwable throwable) {
		/** The whole cause chain, flattened, for substring assertions and failure messages alike. */
		String chain() {
			StringBuilder sb = new StringBuilder();
			for (Throwable current = throwable; current != null; current = current.getCause()) {
				if (!sb.isEmpty()) sb.append(" <- ");
				sb.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
			}
			return sb.toString();
		}
	}

	private static void assertFailsNaming(Launcher launcher, String... needles) throws Exception {
		Failure failure = launchExpectingFailure(launcher);
		for (String needle : needles) {
			assertTrue("no message in the cause chain names '" + needle + "': " + failure.chain(),
				failure.chain().contains(needle));
		}
	}

	/**
	 * Launches on a dedicated thread and returns the failure, wherever in the lifecycle it was raised.
	 * <p>
	 * The two discovery failure sites differ in kind and a single-mechanism probe misses one of them:
	 * the info-key check fires during <b>wiring</b> ({@code postInjectInstances}, before
	 * {@code onStartFuture} is ever touched — polling that future alone would hang), while
	 * {@code rejectNonKeys} fires in {@code onStart()}, which completes it exceptionally and then
	 * unwinds. {@code Launcher.launch} rethrows in both cases, so the launch thread's escaped
	 * {@link Throwable} is the one observation that covers both; the start future is polled only to
	 * detect the <b>failing-first</b> state — nothing was refused and the launcher is up in
	 * {@code awaitShutdown()} — and tear it down instead of hanging Surefire.
	 */
	private static Failure launchExpectingFailure(Launcher launcher) throws Exception {
		AtomicReference<Throwable> escaped = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			try {
				launcher.launch(Launcher.NO_ARGS);
			} catch (Throwable t) {
				escaped.set(t);
			}
		}, "jsonrpc-discovery-adversarial-launch");
		thread.start();

		CompletableFuture<Void> startFuture = launcher.getStartFuture().toCompletableFuture();
		long deadline = System.currentTimeMillis() + 30_000;
		while (thread.isAlive()
			   && !(startFuture.isDone() && !startFuture.isCompletedExceptionally())
			   && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		if (thread.isAlive()) {
			launcher.shutdown();
			thread.join(30_000);
			throw new AssertionError("startup must fail — the launcher started and is running instead");
		}
		Throwable t = escaped.get();
		if (t == null) throw new AssertionError("launch() returned without failing");
		return new Failure(launcher, t);
	}

	/**
	 * No socket was ever opened by the failed launcher. {@code peekInstance} never constructs, so a
	 * {@code null} {@code HttpServer} is the strongest form of the claim — the object does not exist;
	 * and if some other binding happened to build one first, {@code listen()} is only ever called from
	 * {@code startServices(...)}, which a wiring failure never reaches.
	 */
	private static void assertNothingEverListened(Launcher launcher) {
		Injector injector = capturedInjectors.get(launcher);
		HttpServer httpServer = injector.peekInstance(HttpServer.class);
		if (httpServer == null) return;
		assertTrue("no listener may have been bound by a launcher that failed at wiring time",
			httpServer.getBoundAddresses().isEmpty());
	}

	/** Every launcher built here registers its {@link Injector} in {@code onInit}, before anything is created. */
	private static final Map<Launcher, Injector> capturedInjectors = new ConcurrentHashMap<>();

	private static JsonRpcServerLauncher singleWorkerLauncher(Config overrides) {
		return new JsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				capturedInjectors.put(this, injector);
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

	private static MultithreadedJsonRpcServerLauncher multiWorkerLauncher(Config overrides, int workers) {
		return new MultithreadedJsonRpcServerLauncher() {
			@Override
			protected Module getBusinessLogicModule() {
				return businessLogic();
			}

			@Override
			protected void onInit(Injector injector) {
				capturedInjectors.put(this, injector);
			}

			@Override
			Config config() {
				return super.config()
					.overrideWith(Config.create().with("http.listenAddresses", "0"))
					.overrideWith(Config.create().with("workers", String.valueOf(workers)))
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
