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

package io.activej.jsonrpc.transport.http;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpMethod;
import io.activej.http.RoutingServlet;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpTestServer;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchangeHead;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The adversarial suite for the <b>opt-in HTTP {@code GET} discovery endpoint</b> (feature 018,
 * FR-041/FR-042) — {@link JsonRpcDiscoveryServlet} <b>as it is actually deployed</b>: behind a
 * {@link RoutingServlet} mounted the way {@code JsonRpcModule.mountDiscovery} mounts it
 * (<b>method-agnostically</b>, {@code with(path, servlet)} and never {@code with(GET, path, servlet)}),
 * on a real {@link io.activej.http.HttpServer} bound to {@code :0}, reached over real sockets.
 * <p>
 * <b>What this class deliberately does not re-test.</b> {@link JsonRpcDiscoveryServletTest} already
 * pins the §4 rows and the ownership mechanism — identity of the served array, survival across two
 * drains, survival across two real exchanges, the {@code 405} and the disabled-{@code 404} branches
 * through direct {@code serve(...)} calls. Nothing here repeats those; every scenario below is a
 * <i>hostile combination</i> the single-request happy path cannot reach: the full HTTP method matrix
 * through the router, adversarial request targets, 128 genuinely concurrent sockets against one shared
 * array, headers the endpoint never implemented, a peer that aborts while the server still holds
 * unflushed responses, and request framing the connection tier must refuse before {@code serve()}
 * exists at all.
 * <p>
 * Two of them sharpen a claim the existing suite states but cannot see. FR-042's "indistinguishable
 * from any unmapped path" is a statement about <b>bytes</b>, so
 * {@link #aDisabledEndpointIsByteIdenticalToAnUnmappedPathOnTheWire()} compares two complete raw
 * responses rather than an exception's type and code — that is the assertion an operator's scanner
 * would make. And the launcher's load-bearing mounting rule (a discovery path may equal the JSON-RPC
 * path) is only observable when both routes exist at once, which is
 * {@link #discoveryAndTheJsonRpcPostRouteCoexistOnOneSharedPath()}.
 *
 * <h2>Two findings this class exists to pin, both characterizations rather than defects</h2>
 * <ul>
 *     <li><b>{@code HEAD} is not special-cased.</b> RFC 9110 §9.3.2 says a {@code HEAD} response
 *     should carry the header section a {@code GET} would produce, with no body. This endpoint does
 *     <b>not</b> do that: the gate is a single {@code request.getMethod() != HttpMethod.GET}, so
 *     {@code HEAD} takes the same branch as {@code POST} and receives {@code 405} + {@code Allow: GET}
 *     + {@code Content-Length: 0}. That is deliberate and it is safe — {@code Allow: GET} tells the
 *     caller exactly what the resource supports, and a {@code 405} answer to {@code HEAD} is itself
 *     bodyless, so no framing rule is violated. It is pinned by
 *     {@link #headIsRefusedLikeAnyOtherNonGetRatherThanMirroringTheGetHeaders()} so that adding
 *     {@code HEAD} support later is a deliberate change to a red test, not a silent one.</li>
 *     <li><b>The router matches more request targets than the configured path spells.</b>
 *     {@code /rpc.discover/}, {@code /rpc.discover//} and {@code /rpc%2Ediscover} all reach the
 *     endpoint, because {@code UrlParser.pollUrlPart} yields an empty final segment for a trailing
 *     slash and percent-decodes each segment before the lookup. This is {@code core-http}'s routing
 *     behaviour, identical for every servlet mounted anywhere in the platform, and it is
 *     <b>not</b> a widening of the discovery surface: the same document is served, no other route
 *     exists to reach, and the negative cases that would matter — an encoded slash, a traversal
 *     segment, a different case, a longer path — are all {@code 404}. Both halves are pinned, by
 *     {@link #aTrailingSlashOrAPercentEncodedSegmentStillReachesTheSameRoute()} and by
 *     {@link #noTraversalEncodedSlashOrCaseVariantEverReachesTheRoute()}.</li>
 * </ul>
 *
 * <h2>The servlet-entry counter</h2>
 * Every route below is mounted behind a counting wrapper, so "the connection tier refused this before
 * the servlet existed" is an <b>assertion</b> and not an inference from a status code the servlet
 * could also have produced. {@code 400} is the connection tier's hardcoded answer
 * ({@code HttpServerConnection.MALFORMED_HTTP_RESPONSE}); {@code 404} on an unmapped target is
 * {@code RoutingServlet}'s. In both cases the counter must read zero.
 */
public final class JsonRpcDiscoveryServletAdversarialTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The path feature 018's launcher key mounts on; the servlet itself reads no path segment. */
	private static final String DISCOVERY_PATH = "/rpc.discover";

	private static final String TITLE = "JSON-RPC over HTTP adversarial service";
	private static final String VERSION = "9.9.9";

	/** 128 sockets at once against one shared array — comfortably above the "at least 100" the plan asks for. */
	private static final int CONCURRENT_REQUESTS = 128;
	private static final int CONCURRENT_THREADS = 32;

	/**
	 * Pipelined {@code GET}s per aborting connection. The document is under 1 kB, so <b>one</b> response
	 * cannot outrun a socket buffer and "abort mid-write" would be unreachable with a single request.
	 * Four hundred of them are ~400 kB of queued response, which no send/receive buffer pair absorbs —
	 * so the {@code RST} necessarily lands while the server still holds unflushed bytes of some
	 * response. This is what makes the scenario real rather than nominal.
	 */
	private static final int PIPELINED_REQUESTS_PER_ABORT = 400;
	private static final int ABORTING_CONNECTIONS = 5;

	private Eventloop eventloop;
	private JsonRpcDispatcher dispatcher;
	private AsyncServlet root;

	/** The document, read on the JUnit thread while it is still the reactor thread — never mid-exchange. */
	private byte[] document;
	private String documentText;

	/** Incremented on entry to {@code serve()}; the proof that a refusal happened above the servlet. */
	private final AtomicInteger servletEntries = new AtomicInteger();

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.withDiscovery(TITLE, VERSION)
			.build();
		document = dispatcher.discoveryDocument();
		assertNotNull("discovery is enabled on this dispatcher, so the document exists", document);
		documentText = new String(document, US_ASCII);
		servletEntries.set(0);

		JsonRpcDiscoveryServlet discovery = JsonRpcDiscoveryServlet.create(eventloop, dispatcher);
		// mounted exactly as JsonRpcModule.mountDiscovery mounts it: with(path, servlet), the
		// any-method slot — NOT with(GET, path, servlet). That is what puts every verb in front of the
		// servlet's own gate instead of the router's bare 404, and it is the shape scenario 1 probes.
		root = RoutingServlet.builder(eventloop)
			.with(DISCOVERY_PATH, request -> {
				servletEntries.incrementAndGet();
				return discovery.serve(request);
			})
			.build();
	}

	// ---------------------------------------------------------------------------------------------
	// 1 — the HTTP method matrix through the mounted route.
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code GET} is the control; {@code POST}, {@code PUT}, {@code DELETE}, {@code PATCH},
	 * {@code OPTIONS} and {@code TRACE} must each be refused {@code 405} + {@code Allow: GET} with no
	 * body and no {@code Content-Type}.
	 * <p>
	 * Run through the router, not through a direct {@code serve(...)} call, because that is where the
	 * mounting decision could break the contract: mounted on the {@code GET} slot alone these six
	 * would fall through to {@code RoutingServlet}'s bare {@code 404} — a different status, no
	 * {@code Allow} header, and an HTML body. The servlet is nevertheless <i>entered</i> for each one
	 * (the counter proves it), which is the whole point of the any-method mount.
	 */
	@Test
	public void everyMethodOtherThanGetIsRefused405WithAllowGetAndNoBody() throws Exception {
		assertServedDocument(rawExchange(request("GET", DISCOVERY_PATH)));

		for (String method : new String[]{"POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE"}) {
			servletEntries.set(0);
			String response = rawExchange(bodylessRequestWithDeclaredLength(method, DISCOVERY_PATH));

			assertRefused405(method, response);
			assertEquals(method + " must reach the servlet's own gate, not the router's 404",
				1, servletEntries.get());
		}
	}

	/**
	 * <b>The {@code HEAD} finding, pinned.</b> RFC 9110 §9.3.2 describes a {@code HEAD} response as
	 * {@code GET}'s header section without the body; this endpoint instead answers {@code 405} +
	 * {@code Allow: GET}, because its gate is one {@code != GET} comparison and {@code core-http}
	 * has no {@code HEAD}-to-{@code GET} rewriting anywhere in the server path
	 * ({@code HttpMethod.HEAD} appears in {@code HttpServerConnection} only as an enum constant).
	 * <p>
	 * Recorded as behaviour, not endorsed as ideal: the answer is well-formed, self-describing
	 * ({@code Allow: GET} names the one method that works) and correctly bodyless, and a client that
	 * wanted the document's length can ask for the document. Supporting {@code HEAD} later means
	 * changing this test on purpose.
	 */
	@Test
	public void headIsRefusedLikeAnyOtherNonGetRatherThanMirroringTheGetHeaders() throws Exception {
		String response = rawExchange(request("HEAD", DISCOVERY_PATH));

		assertRefused405("HEAD", response);
		assertEquals("HEAD reaches the servlet like any other verb under the any-method mount",
			1, servletEntries.get());
		String head = splitHeadAndBody(response)[0];
		assertFalse("a 405 to HEAD must not advertise the document's media type: " + head,
			head.contains("Content-Type"));
		assertTrue("...and must state a zero length rather than GET's: " + head,
			head.contains("Content-Length: 0"));
	}

	/**
	 * A {@code POST} that <b>declares a megabyte and sends none of it</b>. The refusal must arrive
	 * without the endpoint waiting for a single body byte — otherwise the discovery route would be a
	 * cheaper slowloris target than the JSON-RPC route beside it, which answers its own row 1 the same
	 * way.
	 * <p>
	 * Proven by reading only up to the blank line ({@code exchangeHead}): a full read would hang here,
	 * since a servlet that answers without draining the declared body leaves the connection waiting on
	 * a body that never comes. Prompt arrival of the head <b>is</b> the proof.
	 */
	@Test
	public void aPostDeclaringAMegabyteBodyIsRefusedBeforeAnyBodyByteIsRead() throws Exception {
		String request =
			"POST " + DISCOVERY_PATH + " HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Length: 1048576\r\n" +
			"Connection: close\r\n" +
			"\r\n";

		String head = exchangeHead(eventloop, listen(), request);

		assertTrue("the 405 must arrive before the declared body: " + head,
			head.startsWith("HTTP/1.1 405 Method Not Allowed"));
		assertTrue("Allow: GET must be present: " + head, head.contains("Allow: GET"));
		assertEquals(1, servletEntries.get());
	}

	// ---------------------------------------------------------------------------------------------
	// 2 — query strings.
	// ---------------------------------------------------------------------------------------------

	/**
	 * The servlet reads no query parameter (its Javadoc says "path-agnostic … and no query parameter"),
	 * so three hostile query strings must all produce the <b>same</b> answer as a bare {@code GET}:
	 * an ordinary pair, a 4 kB one (below {@code MAX_HEADER_LINE_SIZE}, so it is a legal request line
	 * rather than a framing test — the framing tier gets its own scenario below), and one carrying
	 * percent-encoded control characters including {@code NUL}, {@code LF} and {@code CR}.
	 * <p>
	 * The assertion is byte equality against the plain {@code GET}'s whole raw response, not just a
	 * status check: anything that echoed a query parameter, varied a header, or truncated the body
	 * would differ.
	 */
	@Test
	public void aQueryStringIsIgnoredWhateverItContains() throws Exception {
		String plain = rawExchange(request("GET", DISCOVERY_PATH));
		assertServedDocument(plain);

		String[] queries = {
			"?foo=bar",
			"?q=" + "a".repeat(4000),
			"?ctl=%00%0A%0D%09%7F&nested=%2e%2e%2f&wide=%F0%9F%92%A3",
		};
		for (String query : queries) {
			String response = rawExchange(request("GET", DISCOVERY_PATH + query));

			assertServedDocument(response);
			assertEquals("the query string '" + query + "' must change nothing at all", plain, response);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// 3 — request-target variations.
	// ---------------------------------------------------------------------------------------------

	/**
	 * The negative half of the routing characterization: none of these reaches the endpoint, and every
	 * one of them is answered by {@code RoutingServlet}'s ordinary {@code 404} — the same answer any
	 * unknown path gets, with the servlet never entered.
	 * <ul>
	 *     <li>{@code //rpc.discover} — the empty first segment is matched at the root, which holds no
	 *     servlet;</li>
	 *     <li>{@code /rpc.discover/%2e%2e} and {@code /%2e%2e/rpc.discover} — a decoded {@code ..} is
	 *     an ordinary segment name here, not a traversal operator: nothing normalises it away, so it
	 *     simply fails to match a route;</li>
	 *     <li>{@code /rpc.discover%2f} — <b>the load-bearing one</b>: the encoded slash is decoded
	 *     <i>after</i> the segment split, so it can never manufacture a new path segment. The lookup
	 *     key becomes the literal {@code "rpc.discover/"}, which matches nothing;</li>
	 *     <li>{@code /RPC.DISCOVER} — segment lookup is a case-sensitive map;</li>
	 *     <li>{@code /rpc.discoverer} and {@code /rpc.discover/extra} — no prefix matching.</li>
	 * </ul>
	 * Each answer is additionally checked not to contain {@code "openrpc"}: a {@code 404} that
	 * nonetheless leaked the document would be the actual disaster this scenario is looking for.
	 */
	@Test
	public void noTraversalEncodedSlashOrCaseVariantEverReachesTheRoute() throws Exception {
		String[] targets = {
			"//rpc.discover",
			"/rpc.discover/%2e%2e",
			"/%2e%2e/rpc.discover",
			"/rpc.discover%2f",
			"/RPC.DISCOVER",
			"/rpc.discoverer",
			"/rpc.discover/extra",
		};
		for (String target : targets) {
			servletEntries.set(0);
			String response = rawExchange(request("GET", target));

			assertTrue("'" + target + "' must answer exactly as an unmapped path: " + response,
				response.startsWith("HTTP/1.1 404 Not Found"));
			assertFalse("'" + target + "' must not disclose the document: " + response,
				response.contains("openrpc"));
			assertEquals("'" + target + "' must never reach the servlet", 0, servletEntries.get());
		}
	}

	/**
	 * The positive half, and a <b>characterization of {@code core-http}'s router</b> rather than of
	 * this feature: a trailing slash, a doubled trailing slash and a percent-encoded segment
	 * ({@code /rpc%2Ediscover}) all reach the mounted servlet and receive the identical document.
	 * <p>
	 * {@code UrlParser.pollUrlPart} yields an empty final segment for a trailing slash — and
	 * {@code RoutingServlet.tryServe} treats an empty segment as "this node" — while each non-empty
	 * segment is percent-decoded before the map lookup. Both rules are platform-wide and apply to every
	 * mounted servlet; neither widens what discovery exposes, since the only thing behind the route is
	 * the document a {@code GET} on the exact path already returns. Pinned so that a future change to
	 * either rule is visible from this module.
	 */
	@Test
	public void aTrailingSlashOrAPercentEncodedSegmentStillReachesTheSameRoute() throws Exception {
		for (String target : new String[]{"/rpc.discover/", "/rpc.discover//", "/rpc%2Ediscover"}) {
			servletEntries.set(0);
			String response = rawExchange(request("GET", target));

			assertServedDocument(response);
			assertEquals("'" + target + "' reached the route, so the servlet ran exactly once",
				1, servletEntries.get());
		}
	}

	/**
	 * A request target whose percent-encoding is invalid ({@code %zz}) never reaches the servlet:
	 * {@code UrlParser} cannot decode the segment, {@code RoutingServlet.tryServe} raises
	 * {@code HttpError.badRequest400("Path contains bad percent encoding")}, and the server renders a
	 * {@code 400}. Discovery inherits that for free — pinned here because the alternative (a decoder
	 * that silently passed the raw bytes through as a segment name) would be a routing surprise worth
	 * catching in this module too.
	 */
	@Test
	public void aTargetWithBadPercentEncodingIsRefusedBeforeTheServlet() throws Exception {
		String response = rawExchange(request("GET", DISCOVERY_PATH + "%zz"));

		assertTrue("bad percent encoding must be a 400: " + response,
			response.startsWith("HTTP/1.1 400 Bad Request"));
		assertFalse("...and must not disclose the document: " + response, response.contains("openrpc"));
		assertEquals("the servlet must never be entered", 0, servletEntries.get());
	}

	/**
	 * <b>FR-042 taken literally, on the wire.</b> The contract says a disabled deployment must be
	 * <i>unprobeable</i>: the path answers "exactly as any unmapped path". {@link JsonRpcDiscoveryServletTest}
	 * checks the servlet raises {@code HttpError.notFound404()}; that is the mechanism, not the claim.
	 * The claim is about <b>bytes</b>, and the only way an operator's scanner could tell the two apart
	 * is by comparing complete responses — status line, every header, and the rendered error body.
	 * <p>
	 * So this mounts a discovery servlet over a dispatcher with <b>no</b> {@code withDiscovery(...)} at
	 * one path on a server that also has a genuinely unmapped path, and requires the two raw responses
	 * to be <b>identical strings</b>. A difference of a single character — a distinct message, a
	 * different length, an extra header — would mean a disabled endpoint advertises its own existence,
	 * which is precisely what the {@code 404}-instead-of-{@code 500} decision was made to avoid.
	 */
	@Test
	public void aDisabledEndpointIsByteIdenticalToAnUnmappedPathOnTheWire() throws Exception {
		JsonRpcDispatcher noDiscovery = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		assertNull("no withDiscovery(...) means no document at all", noDiscovery.discoveryDocument());
		AsyncServlet disabledRoot = RoutingServlet.builder(eventloop)
			.with(DISCOVERY_PATH, JsonRpcDiscoveryServlet.create(eventloop, noDiscovery))
			.build();

		String fromDisabledEndpoint = exchange(eventloop, listen(disabledRoot), request("GET", DISCOVERY_PATH));
		String fromNeverMountedPath = exchange(eventloop, listen(disabledRoot), request("GET", "/nothing-is-here"));

		assertTrue("a disabled endpoint must answer 404: " + fromDisabledEndpoint,
			fromDisabledEndpoint.startsWith("HTTP/1.1 404 Not Found"));
		assertEquals(
			"FR-042: a disabled discovery path must be indistinguishable from a path that was never mounted",
			fromNeverMountedPath, fromDisabledEndpoint);
	}

	/**
	 * The co-mount the launcher explicitly permits: {@code jsonrpc.discovery.path} equal to
	 * {@code jsonrpc.path}. {@code RoutingServlet.getOrDefault} prefers a method-specific slot over its
	 * any-method one, so on <b>one</b> path {@code POST} must still reach {@link JsonRpcServlet} and
	 * dispatch a real call, while {@code GET} reaches discovery and every other verb reaches
	 * discovery's {@code 405} + {@code Allow: GET}.
	 * <p>
	 * Adversarial because the failure mode is silent in both directions: mount discovery on the
	 * {@code GET} slot instead and {@code PUT} degrades to the router's bare {@code 404}; let discovery
	 * take the {@code POST} slot and the JSON-RPC endpoint disappears behind a {@code 405}. Neither
	 * would fail a test of either servlet alone — only the combination shows it.
	 * <p>
	 * Note the honest consequence of the shared path: {@code Allow: GET} is discovery's answer and does
	 * not mention the {@code POST} the same path accepts. That is the price of the any-method mount and
	 * is pinned here rather than left to be discovered by a client that trusts {@code Allow}.
	 */
	@Test
	public void discoveryAndTheJsonRpcPostRouteCoexistOnOneSharedPath() throws Exception {
		AsyncServlet sharedRoot = RoutingServlet.builder(eventloop)
			.with(HttpMethod.POST, DISCOVERY_PATH, JsonRpcServlet.create(eventloop, dispatcher))
			.with(DISCOVERY_PATH, JsonRpcDiscoveryServlet.create(eventloop, dispatcher))
			.build();
		String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";

		String get = exchange(eventloop, listen(sharedRoot), request("GET", DISCOVERY_PATH));
		String post = exchange(eventloop, listen(sharedRoot), post(DISCOVERY_PATH, call, "application/json"));
		String put = exchange(eventloop, listen(sharedRoot), request("PUT", DISCOVERY_PATH, "Content-Length: 0"));

		assertServedDocument(get);
		String[] postHeadAndBody = splitHeadAndBody(post);
		assertTrue("POST must still reach the JSON-RPC servlet: " + post,
			postHeadAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("...and dispatch the call for real: " + post, postHeadAndBody[1].contains("\"sum\":5"));
		assertFalse("...and must not have been answered by the discovery endpoint: " + post,
			postHeadAndBody[1].contains("openrpc"));
		assertRefused405("PUT on the shared path", put);
	}

	// ---------------------------------------------------------------------------------------------
	// 4 — 128 concurrent sockets against one shared array.
	// ---------------------------------------------------------------------------------------------

	/**
	 * <b>The sharpest test of the zero-copy design.</b> {@code discoveryDocument()} hands out one
	 * long-lived array and {@code HttpResponse.withBody(byte[])} wraps it per request; 128 real,
	 * simultaneous connections are what would expose the two ways that can go wrong — a shared
	 * {@code ByteBuf} whose read cursor is consumed by whichever response renders first, or a pooled
	 * wrapper the connection tier recycles (and, under Surefire's {@code clearOnRecycle=true},
	 * <b>zeroes</b>) out from under the other 127.
	 * <p>
	 * Genuinely concurrent: the eventloop runs on its own thread for the duration and 32 client threads
	 * drive 128 blocking {@code java.net.Socket} exchanges, released together by a latch. Every
	 * response is required to be byte-identical to the document, and the array itself — identity and
	 * content — is re-checked after the loop has stopped.
	 */
	@Test
	public void oneHundredAndTwentyEightConcurrentGetsAllReceiveTheByteIdenticalDocument() throws Exception {
		List<String> failures = Collections.synchronizedList(new ArrayList<>());

		withRunningServer(port -> {
			ExecutorService clients = Executors.newFixedThreadPool(CONCURRENT_THREADS);
			CountDownLatch startLine = new CountDownLatch(1);
			CountDownLatch finished = new CountDownLatch(CONCURRENT_REQUESTS);
			try {
				for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
					int index = i;
					clients.execute(() -> {
						try {
							startLine.await();
							String response = blockingExchange(port, request("GET", DISCOVERY_PATH));
							String[] headAndBody = splitHeadAndBody(response);
							if (!headAndBody[0].startsWith("HTTP/1.1 200 OK")) {
								failures.add("request " + index + " status: " + headAndBody[0]);
							} else if (!documentText.equals(headAndBody[1])) {
								failures.add("request " + index + " body differs, length " +
											 headAndBody[1].length() + " vs " + documentText.length());
							}
						} catch (Exception e) {
							failures.add("request " + index + " threw " + e);
						} finally {
							finished.countDown();
						}
					});
				}
				startLine.countDown();
				assertTrue("all " + CONCURRENT_REQUESTS + " concurrent requests must finish within 60s",
					finished.await(60, TimeUnit.SECONDS));
			} finally {
				clients.shutdownNow();
			}
		});

		assertTrue("every concurrent GET must carry the identical document: " + failures, failures.isEmpty());
		assertEquals("all " + CONCURRENT_REQUESTS + " requests reached the servlet",
			CONCURRENT_REQUESTS, servletEntries.get());
		assertEquals("the shared array must be byte-identical after 128 concurrent services",
			documentText, new String(dispatcher.discoveryDocument(), US_ASCII));
		assertSame("...and it must still be the same array object — nothing may replace it either",
			document, dispatcher.discoveryDocument());
	}

	// ---------------------------------------------------------------------------------------------
	// 5 — headers the endpoint never claimed to implement.
	// ---------------------------------------------------------------------------------------------

	/**
	 * No caching, no validators, no partial content: {@code contracts/openrpc-mapping.md} §4 pins
	 * {@code 200} + {@code application/json} + the document, and nothing else, and the servlet's
	 * builder deliberately carries no {@code ETag} option (its Javadoc names that as the seam a later,
	 * additive option would sit on).
	 * <p>
	 * So {@code If-None-Match}, {@code If-Modified-Since} and {@code Range: bytes=0-10} — individually
	 * and all three at once — must each yield the <b>whole</b> document with {@code 200}, never a
	 * {@code 304} and never a {@code 206}. The response is additionally required to advertise none of
	 * the mechanisms it does not implement: no {@code ETag}, no {@code Last-Modified}, no
	 * {@code Accept-Ranges}, no {@code Content-Range}. Silently ignoring an unimplemented conditional
	 * is the correct HTTP behaviour; <i>appearing</i> to implement one would not be.
	 */
	@Test
	public void conditionalAndRangeHeadersAreIgnoredAndTheWholeDocumentIsAlwaysReturned() throws Exception {
		String[][] headerSets = {
			{"If-None-Match: \"any-etag\""},
			{"If-Modified-Since: Sat, 01 Jan 2050 00:00:00 GMT"},
			{"Range: bytes=0-10"},
			{"If-None-Match: *", "If-Modified-Since: Sat, 01 Jan 2050 00:00:00 GMT", "Range: bytes=0-10"},
		};
		for (String[] headers : headerSets) {
			String response = rawExchange(request("GET", DISCOVERY_PATH, headers));

			assertServedDocument(response);
			String head = splitHeadAndBody(response)[0];
			for (String unimplemented : new String[]{"ETag", "Last-Modified", "Accept-Ranges", "Content-Range"}) {
				assertFalse("the endpoint claims no " + unimplemented + " support: " + head,
					head.contains(unimplemented));
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// 6 — a peer that aborts while the server still holds unflushed responses.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Five connections, each pipelining {@value #PIPELINED_REQUESTS_PER_ABORT} {@code GET}s and then
	 * vanishing with an {@code RST} ({@code SO_LINGER 0}) without reading a byte. The receive buffer is
	 * squeezed to 512 bytes, so the server's writes stall almost immediately and roughly 400 kB of
	 * responses are still queued when the connection dies — the abort therefore lands <b>mid-document</b>,
	 * repeatedly, which one request of a sub-kilobyte document could never guarantee.
	 * <p>
	 * Three things must hold, and each corresponds to a way the wrap-don't-own design could fail:
	 * <ul>
	 *     <li>no {@code ByteBuf} leaks — {@link #byteBufRule} covers the write buffers abandoned
	 *     mid-flight, which is where an aborted write would strand one;</li>
	 *     <li>no unhandled exception reaches the reactor — {@link EventloopRule}'s handler
	 *     <b>rethrows</b>, which kills the loop thread, which turns into a teardown failure in
	 *     {@link #withRunningServer}; a broken-pipe write must therefore be handled, not escape;</li>
	 *     <li>the endpoint is unharmed: a subsequent ordinary {@code GET} on the same server returns
	 *     the byte-identical document, and the dispatcher's array is still the same object with the
	 *     same content. A response that had taken ownership of that array would have surrendered it to
	 *     the pool on one of the ~2000 aborted writes.</li>
	 * </ul>
	 */
	@Test
	public void aPeerAbortingMidResponseLeavesNoLeakAndAnUnharmedEndpoint() throws Exception {
		StringBuilder pipeline = new StringBuilder();
		for (int i = 0; i < PIPELINED_REQUESTS_PER_ABORT; i++) {
			// keep-alive on purpose: 'Connection: close' would let the server stop after the first
			pipeline.append("GET ").append(DISCOVERY_PATH).append(" HTTP/1.1\r\nHost: localhost\r\n\r\n");
		}
		byte[] pipelined = pipeline.toString().getBytes(US_ASCII);

		withRunningServer(port -> {
			for (int i = 0; i < ABORTING_CONNECTIONS; i++) {
				try (Socket socket = new Socket()) {
					socket.setTcpNoDelay(true);
					socket.setReceiveBufferSize(512);
					socket.connect(new InetSocketAddress("localhost", port), 5_000);
					OutputStream output = socket.getOutputStream();
					output.write(pipelined);
					output.flush();
					// let the server get well into writing before the peer disappears
					Thread.sleep(20);
					// SO_LINGER 0 turns close() into an RST: no FIN, no graceful drain
					socket.setSoLinger(true, 0);
				}
			}

			// non-vacuity: the scenario is only meaningful if the server really was writing when the
			// peers vanished. Observed 624…1896 of the 2000 pipelined requests served across three
			// runs; a hundred responses is ~100 kB of queued output, far past any buffer pair, so the
			// bound is deliberately loose while still failing a run where nothing was in flight.
			assertTrue("the server must have been mid-flight when the peers aborted, served " +
					   servletEntries.get(), servletEntries.get() >= 100);

			String[] headAndBody = splitHeadAndBody(blockingExchange(port, request("GET", DISCOVERY_PATH)));
			assertTrue("the endpoint must still answer after the aborts: " + headAndBody[0],
				headAndBody[0].startsWith("HTTP/1.1 200 OK"));
			assertEquals("...with the document intact", documentText, headAndBody[1]);
		});

		assertEquals("the shared array must be byte-identical after every aborted write",
			documentText, new String(dispatcher.discoveryDocument(), US_ASCII));
		assertSame("...and it must still be the same array object — nothing may replace it either",
			document, dispatcher.discoveryDocument());
	}

	// ---------------------------------------------------------------------------------------------
	// 7 — request framing the connection tier must refuse before serve() exists.
	// ---------------------------------------------------------------------------------------------

	/**
	 * Four shapes that must never reach {@code serve()}, each answered by the connection tier's
	 * hardcoded {@code 400} + {@code Connection: close} + {@code Content-Length: 0}
	 * ({@code HttpServerConnection.MALFORMED_HTTP_RESPONSE}):
	 * <ul>
	 *     <li>an <b>unterminated</b> header line past {@code MAX_HEADER_LINE_SIZE} (8 kB). Unterminated
	 *     is the operative word — the bound is checked on the <i>unparsed remainder</i> when no CRLF
	 *     has been found yet, so a complete 9 kB header line is accepted while a 9 kB line still
	 *     waiting for its terminator is refused. That distinction is {@code core-http}'s and is worth
	 *     knowing before writing an assertion about "long headers";</li>
	 *     <li>the same, on the request line;</li>
	 *     <li>an obs-fold continuation line (RFC 9112 §5.2 — refused outright, not unfolded);</li>
	 *     <li>two conflicting {@code Content-Length} headers, the classic request-smuggling primitive.</li>
	 * </ul>
	 * The counter reading zero is the point: discovery inherits every one of these gates for free and
	 * adds no gap of its own, because the servlet is simply not reached.
	 */
	@Test
	public void malformedFramingIsRefusedByTheConnectionTierBeforeTheServlet() throws Exception {
		String[][] cases = {
			{"an unterminated 9 kB header line",
				"GET " + DISCOVERY_PATH + " HTTP/1.1\r\nHost: localhost\r\nX-Long: " + "a".repeat(9000)},
			{"an unterminated 9 kB request line",
				"GET /" + "a".repeat(9000)},
			{"an obs-fold continuation line",
				"GET " + DISCOVERY_PATH + " HTTP/1.1\r\nHost: localhost\r\nX-Fold: a\r\n b\r\nConnection: close\r\n\r\n"},
			{"conflicting Content-Length headers",
				"GET " + DISCOVERY_PATH + " HTTP/1.1\r\nHost: localhost\r\n" +
				"Content-Length: 1\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"},
		};
		for (String[] testCase : cases) {
			servletEntries.set(0);
			String response = rawExchange(testCase[1]);

			assertEquals(testCase[0] + " must be the connection tier's own 400: " + response,
				"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n", response);
			assertEquals(testCase[0] + " must never reach the servlet", 0, servletEntries.get());
		}
	}

	/**
	 * An unknown method token and an unknown HTTP version are refused in {@code onStartLine}, before a
	 * request object exists at all — so they are <i>not</i> the servlet's {@code 405}, and the answer
	 * carries no {@code Allow} header. Pinned to keep the two refusal tiers distinguishable: a
	 * regression that turned an unparseable start line into a servlet-level {@code 405} would mean the
	 * parser had started accepting it.
	 */
	@Test
	public void anUnknownMethodOrVersionIsRefusedByTheParserAndNeverBecomesA405() throws Exception {
		String[][] cases = {
			{"an unknown method token", "BREW " + DISCOVERY_PATH + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"},
			{"an unknown HTTP version", "GET " + DISCOVERY_PATH + " HTTP/9.9\r\nHost: localhost\r\nConnection: close\r\n\r\n"},
		};
		for (String[] testCase : cases) {
			servletEntries.set(0);
			String response = rawExchange(testCase[1]);

			assertEquals(testCase[0] + " must be the connection tier's own 400: " + response,
				"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n", response);
			assertFalse(testCase[0] + " must not produce an Allow header: " + response,
				response.contains("Allow"));
			assertEquals(testCase[0] + " must never reach the servlet", 0, servletEntries.get());
		}
	}

	/**
	 * The honest counter-example to the scenario above, and the reason it is written as a
	 * characterization rather than left implicit: {@code core-http} <b>tolerates</b> a start line
	 * terminated by a bare {@code LF}. {@code readStartLine} scans for {@code LF} alone, while the
	 * "Bare LF in header line" refusal lives in {@code readHeaders} and applies only from the second
	 * line on. Such a request is therefore routed and served normally.
	 * <p>
	 * That is the platform's leniency decision, identical for every servlet, and it is <b>not</b> a
	 * discovery gap: the request is unambiguous, the document served is the same one a well-formed
	 * request receives, and nothing about the response varies. Pinned so that the previous test's
	 * "the parser refuses malformed start lines" is read with the correct scope.
	 */
	@Test
	public void aStartLineTerminatedByABareLineFeedIsToleratedByTheParserAndServedNormally() throws Exception {
		String response = rawExchange(
			"GET " + DISCOVERY_PATH + " HTTP/1.1\nHost: localhost\r\nConnection: close\r\n\r\n");

		assertServedDocument(response);
		assertEquals(1, servletEntries.get());
	}

	// ---------------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------------

	/**
	 * A server over the mounted router, bound to port {@code 0} and asked where it landed (ADR-028 —
	 * the harness reads {@code getBoundAddresses()}; this module allocates no port by hand).
	 */
	private JsonRpcHttpTestServer listen() throws IOException {
		return listen(root);
	}

	/** The same, over a router a single test assembles itself (the disabled and co-mount scenarios). */
	private JsonRpcHttpTestServer listen(AsyncServlet mounted) throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(mounted)
			.build();
		server.listen();
		return server;
	}

	/** One raw request against a freshly bound server, read to EOF. Every request here sets {@code Connection: close}. */
	private String rawExchange(String request) throws Exception {
		return exchange(eventloop, listen(), request);
	}

	private static String request(String method, String target, String... extraHeaders) {
		StringBuilder sb = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
			.append("Host: localhost\r\n");
		for (String header : extraHeaders) {
			sb.append(header).append("\r\n");
		}
		return sb.append("Connection: close\r\n\r\n").toString();
	}

	/** The body-bearing verbs get an explicit zero length, so the method gate is the only thing under test. */
	private static String bodylessRequestWithDeclaredLength(String method, String target) {
		return switch (method) {
			case "POST", "PUT", "PATCH" -> request(method, target, "Content-Length: 0");
			default -> request(method, target);
		};
	}

	/**
	 * Runs {@code work} against a bound server while the eventloop runs on its own thread — the shape
	 * the shared {@code JsonRpcHttpRawExchange} helper uses, generalised to many client interactions
	 * instead of one. The server is built and {@code listen()}ed on the JUnit thread first (legal:
	 * {@code Eventloop.inReactorThread()} is true for every thread until {@code run()} claims one), and
	 * both teardown waits are bounded so a killed loop thread fails fast instead of hanging the suite.
	 */
	private void withRunningServer(ClientWork work) throws Exception {
		JsonRpcHttpTestServer server = listen();
		Thread loopThread = new Thread(eventloop);
		loopThread.start();
		try {
			work.run(server.port());
		} finally {
			try {
				server.closeFuture().get(30, TimeUnit.SECONDS);
			} finally {
				loopThread.join(30_000);
				if (loopThread.isAlive()) {
					throw new IllegalStateException("the eventloop thread did not stop: a test left it running");
				}
			}
		}
	}

	/** One blocking request/response over a fresh socket against an already-running server. */
	private static String blockingExchange(int port, String request) throws IOException {
		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(30_000);
			socket.connect(new InetSocketAddress("localhost", port), 30_000);
			OutputStream output = socket.getOutputStream();
			output.write(request.getBytes(US_ASCII));
			output.flush();
			InputStream input = socket.getInputStream();
			return new String(input.readAllBytes(), US_ASCII);
		}
	}

	private void assertServedDocument(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type must be exactly application/json: " + response,
			headAndBody[0].contains("Content-Type: application/json"));
		assertEquals("the body must be the dispatcher's document, verbatim", documentText, headAndBody[1]);
	}

	private static void assertRefused405(String what, String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertTrue(what + " must be refused 405: " + response,
			headAndBody[0].startsWith("HTTP/1.1 405 Method Not Allowed"));
		assertTrue(what + " must carry Allow: GET: " + response, headAndBody[0].contains("Allow: GET"));
		assertEquals(what + " must carry no body: " + response, "", headAndBody[1]);
	}

	@FunctionalInterface
	private interface ClientWork {
		void run(int port) throws Exception;
	}
}
