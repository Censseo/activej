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

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * FR-012 — {@link JsonRpcServlet} is <b>path-agnostic</b>: it reads no path segment and no query
 * parameter, so one instance serves identically at a server root, mounted under a
 * {@link RoutingServlet} at {@code /api}, and at two different paths of one routing servlet at
 * once (acceptance scenario US1-5, which settled the "several endpoints or one multiplexing?"
 * question as: one servlet, mounted wherever).
 * <p>
 * The property is proven by <b>comparing answers across mounts</b>, not by introspection: the same
 * request document POSTed at different paths must produce byte-identical raw responses (nothing in
 * a response echoes the request path, so a path-reading servlet is the only thing that could make
 * them differ).
 */
public final class JsonRpcServletPathTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String REQUEST_DOC =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";

	private Eventloop eventloop;
	private JsonRpcServlet servlet;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
	}

	/** Builds a server over {@code mounted} and listens on {@code :0}. */
	private JsonRpcHttpTestServer listen(io.activej.http.AsyncServlet mounted) throws IOException {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(mounted)
			.build();
		server.listen();
		return server;
	}

	/**
	 * The same servlet mounted directly at the server root and under a {@link RoutingServlet} at
	 * {@code /api} answers the same document byte-identically.
	 * <p>
	 * Each server lives strictly within its own {@code exchange(...)}: the shared helper's
	 * time-bounded teardown can stop the eventloop thread only while the exchanged server is the
	 * <b>only</b> live thing on it (a second, still-listening server's accept key keeps the loop
	 * alive), so the two mounts never overlap.
	 */
	@Test
	public void servedIdenticallyAtARootAndUnderARoutingServlet() throws Exception {
		String fromRoot = exchange(eventloop, listen(servlet), post("/", REQUEST_DOC, "application/json"));

		RoutingServlet routed = RoutingServlet.builder(eventloop)
			.with(HttpMethod.POST, "/api", servlet)
			.build();
		String fromApi = exchange(eventloop, listen(routed), post("/api", REQUEST_DOC, "application/json"));

		assertEquals("the servlet must answer identically at a root and under /api", fromRoot, fromApi);
		String[] headAndBody = splitHeadAndBody(fromRoot);
		assertTrue("status line: " + fromRoot, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type: " + fromRoot, headAndBody[0].contains("Content-Type: application/json"));
	}

	/**
	 * One routing servlet mounts the <b>same</b> {@link JsonRpcServlet} at two paths at once —
	 * both paths answer identically (this is the multiplexing shape: one endpoint per mount,
	 * whichever paths the owner chooses). As above, the two servers' lifetimes are sequential:
	 * each mounts both paths, and a different path is exercised against each.
	 */
	@Test
	public void servedIdenticallyAtTwoPathsOfOneRoutingServlet() throws Exception {
		RoutingServlet multi = RoutingServlet.builder(eventloop)
			.with(HttpMethod.POST, "/api", servlet)
			.with(HttpMethod.POST, "/json", servlet)
			.build();

		String fromApi = exchange(eventloop, listen(multi), post("/api", REQUEST_DOC, "application/json"));
		String fromJson = exchange(eventloop, listen(multi), post("/json", REQUEST_DOC, "application/json"));

		assertEquals("the servlet must answer identically at /api and /json", fromApi, fromJson);
		String[] headAndBody = splitHeadAndBody(fromApi);
		assertTrue("status line: " + fromApi, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Type: " + fromApi, headAndBody[0].contains("Content-Type: application/json"));
	}
}
