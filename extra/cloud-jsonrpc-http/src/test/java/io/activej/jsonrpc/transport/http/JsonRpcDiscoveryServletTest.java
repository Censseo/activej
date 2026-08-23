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

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpError;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
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

import static io.activej.http.HttpHeaders.ALLOW;
import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link JsonRpcDiscoveryServlet} against {@code contracts/openrpc-mapping.md} §4 (T018, FR-041):
 * a {@code GET} on the configured path answers {@code 200} + {@code Content-Type: application/json}
 * + the dispatcher's pre-computed document bytes, verbatim.
 * <p>
 * <b>The load-bearing case is repetition.</b> {@link JsonRpcDispatcher#discoveryDocument()} hands out
 * <b>the</b> array — one build-time constant, shared with the {@code rpc.discover} table entry and
 * reused for the life of the dispatcher — so the response must be a <i>read-only view</i> of it and
 * never a buffer anything can recycle, clear or advance on the array's behalf. Three cases assert
 * that jointly, and each would fail loudly under a wrong ownership choice:
 * <ul>
 *     <li>{@link #theResponseWrapsTheDispatchersOwnArrayRatherThanCopyingIt()} pins the identity —
 *     the response body's backing array <b>is</b> the dispatcher's array;</li>
 *     <li>{@link #twoGetsThroughOneServletServeTheSameBytesAndLeaveTheArrayIntact()} drains and
 *     releases the first response's body exactly as the connection tier does, then serves a second
 *     request. A pooled buffer over that array would have been zeroed by the first recycle
 *     ({@code clearOnRecycle=true} under Surefire); one shared buffer reused across requests would
 *     have had its read cursor consumed and would answer the second request with nothing;</li>
 *     <li>{@link #theArraySurvivesTwoRealExchangesThroughTheConnectionTier()} runs the same two
 *     requests through a real socket, so the recycle under test is <b>core-http's own</b>
 *     ({@code renderHttpMessage} copies the body into the pooled write buffer and then recycles it),
 *     not the test's.</li>
 * </ul>
 * {@link ByteBufRule} covers the other half: nothing this servlet touches may leak either.
 */
public final class JsonRpcDiscoveryServletTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The path this feature's launcher key mounts the endpoint on; the servlet itself is path-agnostic. */
	private static final String DISCOVERY_PATH = "/rpc.discover";

	/** Both members are application-supplied — nothing derives a title from a class or a version from a POM. */
	private static final String TITLE = "JSON-RPC over HTTP test service";
	private static final String VERSION = "1.2.3";

	private Eventloop eventloop;
	private JsonRpcDispatcher dispatcher;
	private JsonRpcDiscoveryServlet servlet;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.withDiscovery(TITLE, VERSION)
			.build();
		servlet = JsonRpcDiscoveryServlet.create(eventloop, dispatcher);
	}

	/** One bodyless {@code GET} on the discovery path. Bodyless deliberately: {@code HttpMessage.recycle()} is package-private, so a test cannot recycle a request it built with a body. */
	private static HttpRequest get() {
		return HttpRequest.get("http://localhost" + DISCOVERY_PATH).build();
	}

	/** Builds a server over the servlet under test and listens on {@code :0} (ADR-028 — never {@code getFreePort()}). */
	private JsonRpcHttpTestServer listen() throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		return server;
	}

	/**
	 * Reads one response body <b>the way the connection tier does</b>, and releases it the same way:
	 * {@code AbstractHttpConnection.renderHttpMessage} allocates a pooled write buffer, {@code put}s
	 * the body into it — which advances the body's {@code head} cursor — and then recycles the body it
	 * was handed. Mirroring those three steps is what gives this test teeth: a {@code takeBody()} plus
	 * {@code asArray()} copies from {@code head} without moving it, so it could not tell a per-request
	 * wrapper from one shared buffer handed out twice.
	 */
	private static byte[] drainLikeAConnection(HttpResponse response) {
		ByteBuf body = response.takeBody();
		ByteBuf written = ByteBufPool.allocate(body.readRemaining());
		written.put(body);
		body.recycle();
		return written.asArray();
	}

	/** A raw HTTP/1.1 {@code GET} on the discovery path, {@code Connection: close} so the exchange helper can read to EOF. */
	private static String rawGet() {
		return
			"GET " + DISCOVERY_PATH + " HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Connection: close\r\n" +
			"\r\n";
	}

	// The pinned success row of contracts/openrpc-mapping.md §4 ------------------------------------

	/**
	 * §4 row "Success": {@code 200}, {@code Content-Type: application/json}, body = the dispatcher's
	 * pre-computed document bytes. The expected bytes are read from the dispatcher rather than
	 * hardcoded — the servlet's whole contract is that it serves <b>that</b> document, so a test that
	 * spelled the JSON out would be asserting the generator, not the endpoint.
	 */
	@Test
	public void aGetYields200ApplicationJsonAndTheDispatchersDocumentBytes() {
		byte[] document = dispatcher.discoveryDocument();
		assertNotNull("discovery is enabled on this dispatcher, so the document exists", document);

		HttpResponse response = await(servlet.serve(get()));

		assertEquals(200, response.getCode());
		assertEquals("application/json", response.getHeader(CONTENT_TYPE));
		// takeBody() first (ownership out of the message), then asArray() (copies AND recycles in one
		// call) — the module's inbound idiom, applied here to read a response the same safe way
		assertArrayEquals("the body must be the dispatcher's document, verbatim",
			document, response.takeBody().asArray());
	}

	/**
	 * The zero-copy pin: the response body is a <b>view</b> over the dispatcher's own array, not a
	 * copy of it. This is what makes the endpoint allocation-free per request — and it is exactly
	 * what makes the two repetition cases below meaningful, since a copy would survive any ownership
	 * mistake and prove nothing.
	 */
	@Test
	public void theResponseWrapsTheDispatchersOwnArrayRatherThanCopyingIt() {
		byte[] document = dispatcher.discoveryDocument();

		HttpResponse response = await(servlet.serve(get()));

		ByteBuf body = response.takeBody();
		assertSame("the response must wrap the dispatcher's array, never copy it", document, body.array());
		assertEquals("the view must expose the whole document", document.length, body.readRemaining());
		body.recycle();
	}

	/**
	 * <b>The ownership test.</b> Two {@code GET}s through one servlet instance, each response drained
	 * and released exactly as the connection tier does it ({@link #drainLikeAConnection(HttpResponse)}),
	 * and the second must still carry the complete document. Two wrong ownership choices fail here:
	 * a pooled buffer over the dispatcher's array would be <b>zeroed</b> by the first recycle (Surefire
	 * runs with {@code ByteBufPool.clearOnRecycle=true}), and one shared {@code ByteBuf} reused across
	 * requests would have its read cursor consumed by the first drain and answer the second with
	 * <b>zero bytes</b>.
	 */
	@Test
	public void twoGetsThroughOneServletServeTheSameBytesAndLeaveTheArrayIntact() {
		byte[] expected = dispatcher.discoveryDocument().clone();

		byte[] first = drainLikeAConnection(await(servlet.serve(get())));
		byte[] second = drainLikeAConnection(await(servlet.serve(get())));

		assertArrayEquals("first GET", expected, first);
		assertArrayEquals("second GET, after the first response's body was recycled", expected, second);
		assertArrayEquals("the dispatcher's long-lived array must be untouched by having been served",
			expected, dispatcher.discoveryDocument());
		assertSame("and it must still be the same array — nothing may replace it either",
			dispatcher.discoveryDocument(), dispatcher.discoveryDocument());
	}

	/**
	 * The same repetition, but the recycle under test is <b>core-http's</b>: over a real socket
	 * {@code renderHttpMessage} copies the body into the pooled write buffer and then recycles the
	 * body it was handed. Two sequential exchanges over one servlet instance therefore exercise the
	 * production release path twice against one long-lived array.
	 */
	@Test
	public void theArraySurvivesTwoRealExchangesThroughTheConnectionTier() throws Exception {
		// computed before any exchange: the eventloop can only be driven by one thread at a time, and
		// the raw exchange runs it on its own thread
		String expected = new String(dispatcher.discoveryDocument(), US_ASCII);

		String first = exchange(eventloop, listen(), rawGet());
		String second = exchange(eventloop, listen(), rawGet());

		for (String response : new String[]{first, second}) {
			String[] headAndBody = splitHeadAndBody(response);
			assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
			assertTrue("Content-Type must be exactly application/json: " + response,
				headAndBody[0].contains("Content-Type: application/json"));
			assertEquals("the body must be the dispatcher's document, verbatim: " + response,
				expected, headAndBody[1]);
		}
		assertEquals("the dispatcher's array must be untouched by two real exchanges",
			expected, new String(dispatcher.discoveryDocument(), US_ASCII));
	}

	// The pinned rejection rows ---------------------------------------------------------------------

	/**
	 * §4 row "Other methods on that path": {@code 405}. The gate is the servlet's own, with
	 * {@code Allow: GET} — {@code RoutingServlet} answers an unmapped method {@code 404}
	 * ({@code HttpError.notFound404()}), never {@code 405}, so a servlet that trusted the routing
	 * layer for this row could not produce the pinned status at all.
	 */
	@Test
	public void aNonGetMethodIsRefusedWith405AndAllowGet() {
		HttpResponse response = await(servlet.serve(HttpRequest.post("http://localhost" + DISCOVERY_PATH).build()));

		assertEquals(405, response.getCode());
		assertEquals("GET", response.getHeader(ALLOW));
		assertNull("a rejection carries no body and therefore no Content-Type", response.getHeader(CONTENT_TYPE));
	}

	/**
	 * §4 row "Discovery disabled": the path must answer <b>exactly as any unmapped path</b> (FR-042).
	 * The route is not mounted at all in that case — that is the launcher's job — so this is the
	 * defensive branch: reached only by a mis-wiring or a direct caller, it answers with the very
	 * {@code HttpError.notFound404()} {@code RoutingServlet} raises for a path it does not know, so a
	 * disabled deployment stays unprobeable even when something mounts the endpoint by mistake. Never
	 * an NPE, and never a {@code 500} advertising that discovery exists but is off.
	 */
	@Test
	public void discoveryDisabledAnswersExactlyLikeAnUnmappedPath() {
		JsonRpcDispatcher noDiscovery = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		assertNull("no withDiscovery(...) means no document at all", noDiscovery.discoveryDocument());
		JsonRpcDiscoveryServlet unmounted = JsonRpcDiscoveryServlet.create(eventloop, noDiscovery);

		Exception e = awaitException(unmounted.serve(get()));

		assertTrue("the disabled endpoint must raise core-http's own not-found error, got " + e,
			e instanceof HttpError);
		assertEquals(404, ((HttpError) e).getCode());
	}
}
