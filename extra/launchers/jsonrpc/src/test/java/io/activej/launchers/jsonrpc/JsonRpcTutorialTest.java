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

import io.activej.dns.DnsClient;
import io.activej.http.HttpClient;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.launchers.jsonrpc.tutorial.GreetingServerLauncher;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.stop;
import static io.activej.launchers.jsonrpc.LauncherTestHarness.unregisterDispatcherBeans;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * US4, FR-071: the tutorial-regression test for
 * <a href="../../../../../../../../../docs/cloud-extras/jsonrpc-tutorial.md">
 * {@code docs/cloud-extras/jsonrpc-tutorial.md}</a>.
 *
 * <h4>Why this test lives here</h4>
 * The tutorial starts the <b>provided launcher</b> and validates it over <b>HTTP</b>, and this module is
 * the only one holding both (research Decision 8). {@code extra/cloud-jsonrpc}'s own
 * {@code ModuleBoundaryTest} refuses {@code io.activej.http} module-wide, so the page's HTTP-level proof
 * cannot live beside the document-content assertions in {@code OpenRpcSchemaTest}.
 *
 * <h4>What is pinned, and why it is pinned twice</h4>
 * A tutorial rots in two directions, so both are closed:
 * <ol>
 *     <li><b>Page → implementation.</b> Every {@code curl} body the page prints is replayed as that exact
 *     literal against a real running {@link GreetingServerLauncher}, and the answer is compared to the
 *     page's printed response <b>in full</b> — not {@code contains}, not a JSON-equality that would let
 *     member order drift. A response the page could not have produced is a red build.</li>
 *     <li><b>Implementation → page.</b> The page file itself is read and each literal is required to
 *     appear in it, inside its {@code -d '…'} quoting. Changing a response here without editing the page
 *     fails, which is the half a launch-and-assert test cannot cover — and the half FR-071 is about.</li>
 * </ol>
 *
 * <h4>The two deliberate divergences from the page, and nothing else</h4>
 * <ul>
 *     <li><b>The port.</b> The page uses the launcher's built-in default, {@code localhost:8080}, so its
 *     commands are copy-pasteable. This test binds {@code :0} and reads the real port back from the
 *     server (ADR-028) — a hard-coded port in a test is refused across this module. Only the authority
 *     of the URL differs; path, method, headers and body are the page's.</li>
 *     <li><b>Where the properties come from.</b> The page puts them in
 *     {@code src/main/resources/jsonrpc-server.properties}, which {@link JsonRpcServerLauncher} loads from
 *     the classpath. A file by that name in <i>this module's</i> test resources would be loaded by every
 *     other launcher test in the JVM, so the same text is held here as a literal and pushed through the
 *     launcher's <b>other</b> documented layer instead — {@code -Dconfig.<key>=<value>}, which the page
 *     names too. Same keys, same values, same {@code Config}; only the layer differs, and it is the
 *     higher-priority one. The page's block and this literal are compared byte-for-byte by
 *     {@link #theTutorialPagePrintsThePropertiesTheLauncherIsStartedWith()}.</li>
 * </ul>
 * The tutorial's four Java types are real files under {@code io.activej.launchers.jsonrpc.tutorial} and are
 * the ones started here; the page prints their declarations without the {@code package}/{@code import}
 * lines, the house style of the {@code docs/} pages.
 *
 * <p>Every launching test overrides {@code onFatalError} (FR-057 — the default {@code System.exit(-1)}
 * would take the Surefire JVM with it).
 */
public class JsonRpcTutorialTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();
	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The page's {@code src/main/resources/jsonrpc-server.properties}, verbatim. */
	private static final String TUTORIAL_PROPERTIES = """
		jsonrpc.path=/rpc
		jsonrpc.discovery.path=/openrpc
		jsonrpc.discovery.info.title=Greeting API
		jsonrpc.discovery.info.version=1.0.0
		""";

	private static final String RPC_PATH = "/rpc";
	private static final String DISCOVERY_PATH = "/openrpc";

	/** The base URL the page's commands carry — the launcher's built-in default. */
	private static final String TUTORIAL_BASE_URL = "http://localhost:8080";

	private static final String JSON = "application/json";
	/** What {@code curl -d} sends when no {@code Content-Type} header is given — the page's §Troubleshooting. */
	private static final String CURL_DEFAULT_CONTENT_TYPE = "application/x-www-form-urlencoded";

	/** The OpenRPC document the two discovery routes serve, byte-for-byte. */
	private static final String DOCUMENT =
		"{\"openrpc\":\"1.4.0\"," +
		"\"info\":{\"title\":\"Greeting API\",\"version\":\"1.0.0\"}," +
		"\"methods\":[" +
		"{\"name\":\"greeting.hello\"," +
		"\"params\":[{\"name\":\"name\",\"required\":true,\"schema\":{\"type\":\"string\"}}]," +
		"\"result\":{\"name\":\"result\",\"required\":true,\"schema\":{\"type\":\"object\"," +
		"\"properties\":{\"message\":{\"type\":\"string\"},\"language\":{\"type\":\"string\"}}," +
		"\"required\":[\"message\",\"language\"]}}}," +
		"{\"name\":\"greeting.seen\"," +
		"\"params\":[{\"name\":\"name\",\"required\":true,\"schema\":{\"type\":\"string\"}}]}" +
		"]}";

	/**
	 * One documented step: what the page tells a reader to send, and what it tells them to expect back.
	 *
	 * @param heading           the page section this step belongs to — the assertion message when it fails
	 * @param contentType       the request's {@code Content-Type}, or {@code null} to send none
	 * @param requestBody       the exact bytes of the page's {@code curl -d '…'} argument
	 * @param expectedCode      the documented HTTP status
	 * @param expectedType      the documented response {@code Content-Type}, or {@code null} for none
	 * @param expectedBody      the documented response body, in full
	 */
	private record Step(
		String heading, String path, HttpMethod method, @Nullable String contentType,
		@Nullable String requestBody, int expectedCode, @Nullable String expectedType, String expectedBody
	) {
		static Step post(String heading, String requestBody, String expectedBody) {
			return new Step(heading, RPC_PATH, HttpMethod.POST, JSON, requestBody, 200, JSON, expectedBody);
		}
	}

	// ---------------------------------------------------------------- the page's walkthrough, in its order

	/** §Your first call. */
	private static final Step CALL = Step.post("Your first call",
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"greeting.hello\",\"params\":{\"name\":\"Ada\"}}",
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"message\":\"Hello, Ada!\",\"language\":\"en\"}}");

	/** §Positional parameters — the same method, the other calling convention. */
	private static final Step POSITIONAL = Step.post("Positional parameters",
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"greeting.hello\",\"params\":[\"Ada\"]}",
		"{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"message\":\"Hello, Ada!\",\"language\":\"en\"}}");

	/** §Choosing an error — the application's own code, chosen by the implementation. */
	private static final Step APPLICATION_ERROR = Step.post("Choosing an error",
		"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"greeting.hello\",\"params\":{\"name\":\"\"}}",
		"{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":{\"code\":1001,\"message\":\"Name must not be blank\"}}");

	/** §Choosing an error — a name nobody registered. */
	private static final Step METHOD_NOT_FOUND = Step.post("Choosing an error",
		"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"greeting.goodbye\",\"params\":{\"name\":\"Ada\"}}",
		"{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");

	/** §Choosing an error — bytes that are not JSON at all. */
	private static final Step PARSE_ERROR = Step.post("Choosing an error",
		"{\"jsonrpc\":\"2.0\",\"id\":5,",
		"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}");

	/** §Notifications — no {@code id}, no answer, {@code 204 No Content}. */
	private static final Step NOTIFICATION = new Step("Notifications", RPC_PATH, HttpMethod.POST, JSON,
		"{\"jsonrpc\":\"2.0\",\"method\":\"greeting.seen\",\"params\":{\"name\":\"Ada\"}}",
		204, null, "");

	/** §Batches — three documents in, two answers out, the notification silent. */
	private static final Step BATCH = Step.post("Batches",
		"[{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"greeting.hello\",\"params\":{\"name\":\"Ada\"}}," +
		"{\"jsonrpc\":\"2.0\",\"method\":\"greeting.seen\",\"params\":{\"name\":\"Ada\"}}," +
		"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"greeting.hello\",\"params\":{\"name\":\"Grace\"}}]",
		"[{\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"message\":\"Hello, Ada!\",\"language\":\"en\"}}," +
		"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"message\":\"Hello, Grace!\",\"language\":\"en\"}}]");

	/** §Discovery — the read-only {@code GET} endpoint. */
	private static final Step DISCOVERY_GET = new Step("Discovery", DISCOVERY_PATH, HttpMethod.GET,
		null, null, 200, JSON, DOCUMENT);

	/** §Discovery — the same document, as a JSON-RPC method. */
	private static final Step DISCOVER = Step.post("Discovery",
		"{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"rpc.discover\"}",
		"{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":" + DOCUMENT + "}");

	/** §Troubleshooting — {@code curl -d} without a {@code Content-Type} header is a {@code 415}. */
	private static final Step MISSING_CONTENT_TYPE = new Step("Troubleshooting", RPC_PATH, HttpMethod.POST,
		CURL_DEFAULT_CONTENT_TYPE,
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"greeting.hello\",\"params\":{\"name\":\"Ada\"}}",
		415, null, "");

	/** Every step, in the order the page walks through them. */
	private static final List<Step> STEPS = List.of(
		CALL, POSITIONAL, APPLICATION_ERROR, METHOD_NOT_FOUND, PARSE_ERROR,
		NOTIFICATION, BATCH, DISCOVERY_GET, DISCOVER, MISSING_CONTENT_TYPE);

	@Before
	@After
	public void cleanBeans() throws Exception {
		unregisterDispatcherBeans();
	}

	/**
	 * Publishes the page's properties through {@code -Dconfig.*}, plus the one divergence — {@code :0}.
	 * <p>
	 * Set and cleared per test method: {@code Config.ofSystemProperties("config")} is read once, while the
	 * launcher is wiring, and a key left behind would silently reconfigure every later launcher in this JVM.
	 */
	@Before
	public void publishTutorialConfig() {
		tutorialProperties().forEach((key, value) -> System.setProperty("config." + key, (String) value));
		System.setProperty("config.http.listenAddresses", "0");
	}

	@After
	public void withdrawTutorialConfig() {
		tutorialProperties().forEach((key, value) -> System.clearProperty("config." + key));
		System.clearProperty("config.http.listenAddresses");
	}

	// ---------------------------------------------------------------- page -> implementation

	@Test
	public void everyDocumentedExchangeAnswersExactlyAsThePagePrintsIt() throws Exception {
		GreetingServerLauncher launcher = tutorialLauncher();
		LauncherTestHarness.launch(launcher);
		try {
			int port = boundPortOf(launcher);
			assertNotEquals("the kernel must have assigned a real port", 0, port);

			for (Step step : STEPS) {
				Probe probe = send(Reactor.getCurrentReactor(), port, step);
				String where = "§" + step.heading() + ", " + step.method() + " " + step.path();
				assertEquals(where + ": documented status", step.expectedCode(), probe.code());
				assertEquals(where + ": documented Content-Type", step.expectedType(), probe.contentType());
				assertEquals(where + ": documented response body", step.expectedBody(), probe.body());
			}
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void theTwoDiscoveryRoutesServeOneDocument() throws Exception {
		// FR-040, restated at tutorial level: the page prints the document once and claims rpc.discover
		// carries the same bytes. Proven against the running server rather than asserted in prose.
		GreetingServerLauncher launcher = tutorialLauncher();
		LauncherTestHarness.launch(launcher);
		try {
			int port = boundPortOf(launcher);
			String fromGet = send(Reactor.getCurrentReactor(), port, DISCOVERY_GET).body();
			String fromMethod = send(Reactor.getCurrentReactor(), port, DISCOVER).body();
			assertEquals("{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":" + fromGet + "}", fromMethod);
		} finally {
			stop(launcher);
		}
	}

	@Test
	public void theTroubleshootingTableIsTrueOfTheRunningServer() throws Exception {
		// The page's § Troubleshooting names two statuses that are easy to get backwards, and its first
		// draft did get one of them backwards. Both are OBSERVED here rather than inferred from
		// RoutingServlet, which is the same reason JsonRpcDiscoveryConfigTest observes its own co-mount.
		GreetingServerLauncher launcher = tutorialLauncher();
		LauncherTestHarness.launch(launcher);
		try {
			int port = boundPortOf(launcher);
			NioReactor reactor = Reactor.getCurrentReactor();

			// jsonrpc.path is mounted on POST ONLY, so a GET is an unmatched route and gets the router's
			// bare 404. JsonRpcServlet's own 405 + Allow: POST is unreachable through this launcher.
			Probe getOnRpcPath = send(reactor, port, HttpMethod.GET, RPC_PATH, null, null);
			assertEquals("a GET on jsonrpc.path is the router's 404, not a 405", 404, getOnRpcPath.code());
			assertNull("the router emits no Allow header", getOnRpcPath.allow());

			// the discovery route is mounted METHOD-AGNOSTICALLY, so the servlet's own method gate answers
			Probe deleteOnDiscoveryPath = send(reactor, port, HttpMethod.DELETE, DISCOVERY_PATH, null, null);
			assertEquals("a non-GET on jsonrpc.discovery.path is the servlet's own 405",
				405, deleteOnDiscoveryPath.code());
			assertEquals("GET", deleteOnDiscoveryPath.allow());
		} finally {
			stop(launcher);
		}
	}

	// ---------------------------------------------------------------- implementation -> page

	@Test
	public void theTutorialPageQuotesEveryRequestAndResponseVerbatim() throws Exception {
		String page = readTutorialPage();
		for (Step step : STEPS) {
			String requestBody = step.requestBody();
			if (requestBody != null) {
				assertTrue(
					"§" + step.heading() + ": the page must carry this curl body verbatim, in its own " +
					"quoting — otherwise a reader's copy-paste sends something this test never ran:\n  -d '" +
					requestBody + "'",
					page.contains("-d '" + requestBody + "'"));
			}
			if (!step.expectedBody().isEmpty()) {
				assertTrue(
					"§" + step.heading() + ": the page must print this response verbatim:\n  " +
					step.expectedBody(),
					page.contains(step.expectedBody()));
			}
		}
	}

	@Test
	public void theTutorialPagePrintsThePropertiesTheLauncherIsStartedWith() throws Exception {
		String page = readTutorialPage();
		assertTrue(
			"the page's jsonrpc-server.properties block must be the one this test launches with:\n" +
			TUTORIAL_PROPERTIES,
			page.contains(TUTORIAL_PROPERTIES));
	}

	@Test
	public void theTutorialPageAddressesTheDefaultPortAndBothDiscoveryRoutes() throws Exception {
		String page = readTutorialPage();
		assertTrue("the page's commands must target the launcher's built-in default authority",
			page.contains(TUTORIAL_BASE_URL + RPC_PATH));
		assertTrue("the page must show the read-only discovery GET",
			page.contains("curl -s " + TUTORIAL_BASE_URL + DISCOVERY_PATH));
	}

	@Test
	public void theTutorialPagePointsAtTheOtherTransportsAndIsLinkedFromTheDomainPage() throws Exception {
		String page = readTutorialPage();
		// FR-070: the HTTP spine is walked through; WebSocket, TCP and the bidirectional exchange are
		// marked pointers at the example module, not three parallel walkthroughs
		assertTrue("the page must point at the example module for the other transports",
			page.contains("extra/examples/jsonrpc"));
		assertTrue("the page must name the WebSocket key", page.contains("jsonrpc.ws.path"));
		assertTrue("the page must name the TCP key", page.contains("jsonrpc.tcp.port"));

		String domainPage = Files.readString(repositoryRoot().resolve("docs/cloud-extras/spec.md"), UTF_8);
		assertTrue("the domain page must link the tutorial — an unlinked page is an unfindable page",
			domainPage.contains("jsonrpc-tutorial.md"));
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * The page's launcher, unmodified but for {@code onFatalError}.
	 * <p>
	 * Nothing about the configuration is overridden here: {@link #publishTutorialConfig()} has already put
	 * the page's own properties on the {@code -Dconfig.*} layer that {@link JsonRpcServerLauncher#config()}
	 * reads. The tutorial's class therefore carries no test affordance at all.
	 */
	private static GreetingServerLauncher tutorialLauncher() {
		return new GreetingServerLauncher() {
			@Override
			protected void onFatalError(Throwable throwable) {}
		};
	}

	/** The bound port of a launcher started on {@code :0} (ADR-028) — never the configured one. */
	private static int boundPortOf(JsonRpcServerLauncher launcher) {
		return launcher.httpServer.getBoundAddresses().get(0).getPort();
	}

	private static Properties tutorialProperties() {
		Properties properties = new Properties();
		try {
			properties.load(new StringReader(TUTORIAL_PROPERTIES));
		} catch (IOException e) {
			throw new AssertionError("the tutorial's properties block is not a properties file", e);
		}
		return properties;
	}

	private record Probe(int code, @Nullable String contentType, @Nullable String allow, String body) {}

	private static Probe send(NioReactor reactor, int port, Step step) {
		return send(reactor, port, step.method(), step.path(), step.contentType(), step.requestBody());
	}

	/** One exchange, one fresh connection; everything asserted on is captured inside the exchange. */
	private static Probe send(
		NioReactor reactor, int port, HttpMethod method, String path,
		@Nullable String contentType, @Nullable String requestBody
	) {
		HttpClient httpClient = HttpClient.create(reactor, DnsClient.create(reactor, inetAddress("8.8.8.8")));
		HttpRequest.Builder builder = HttpRequest.builder(method, "http://127.0.0.1:" + port + path)
			.withHeader(HttpHeaders.CONNECTION, "close");
		if (contentType != null) builder.withHeader(HttpHeaders.CONTENT_TYPE, contentType);
		if (requestBody != null) builder.withBody(requestBody.getBytes(UTF_8));
		return await(httpClient.request(builder.build())
			.then(response -> response.loadBody()
				.map(body -> new Probe(
					response.getCode(),
					response.getHeader(HttpHeaders.CONTENT_TYPE),
					response.getHeader(HttpHeaders.ALLOW),
					body.getString(UTF_8)))));
	}

	/**
	 * The tutorial page, or a failure that names why it is not there.
	 * <p>
	 * ⚠ {@code .gitignore} excludes {@code docs/} wholesale in this repository — every domain page is an
	 * untracked local artefact — so a <b>fresh clone has no page for the four assertions below to read</b>
	 * and they fail here rather than anywhere subtler. That is a repository-convention conflict with
	 * FR-070/FR-071, not a defect in the tutorial: the four launch-and-assert tests above touch no file
	 * and pass regardless. Resolve it by tracking {@code docs/}, not by softening this into a skip — a
	 * page-drift guard that quietly passes when the page is missing guards nothing.
	 */
	private static String readTutorialPage() throws IOException {
		Path page = repositoryRoot().resolve("docs/cloud-extras/jsonrpc-tutorial.md");
		if (!Files.isRegularFile(page)) {
			throw new AssertionError(
				"the tutorial page is missing: " + page + "\n" +
				"FR-070 requires it, and these assertions are what keep it from drifting (FR-071).\n" +
				"Note that .gitignore excludes docs/ in this repository, so a fresh clone never has it.");
		}
		return Files.readString(page, UTF_8);
	}

	/**
	 * The repository root, found by walking up from the module directory Surefire runs in.
	 * <p>
	 * Deliberately not a skip-if-absent lookup: a missing page is exactly the failure FR-071 asks this test
	 * to raise, and a test that quietly passes when the artefact it guards is gone guards nothing.
	 */
	private static Path repositoryRoot() {
		for (Path path = Paths.get("").toAbsolutePath(); path != null; path = path.getParent()) {
			if (Files.isDirectory(path.resolve("docs/cloud-extras"))) return path;
		}
		throw new AssertionError(
			"no repository root above " + Paths.get("").toAbsolutePath() + " contains docs/cloud-extras");
	}
}
