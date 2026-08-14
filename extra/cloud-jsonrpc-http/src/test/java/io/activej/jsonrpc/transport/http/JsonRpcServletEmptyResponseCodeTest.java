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
import java.util.Arrays;

import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.exchange;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.post;
import static io.activej.jsonrpc.transport.http.fixtures.JsonRpcHttpRawExchange.splitHeadAndBody;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * FR-018 — the empty-response status is a builder option on {@link JsonRpcServlet}: {@code 204} by
 * default (Decision 2 — some JSON-RPC clients in the wild expect {@code 200} with an empty body,
 * so {@code 200} is accepted), and <b>any other value is refused at {@code build()}</b>.
 * <p>
 * FR-017's header rule applies to the configured status, not only to {@code 204}: a {@code 200}
 * empty response must carry no {@code Content-Type} either.
 */
public final class JsonRpcServletEmptyResponseCodeTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String NOTIFICATION =
		"{\"jsonrpc\":\"2.0\",\"method\":\"test.notify\",\"params\":{\"message\":\"hi\"}}";

	private Eventloop eventloop;
	private JsonRpcDispatcher dispatcher;

	@Before
	public void setUp() {
		eventloop = Reactor.getCurrentReactor();
		dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
	}

	/** Builds a server over {@code servlet}, listens on {@code :0}, and POSTs the notification to it. */
	private String notificationTo(JsonRpcServlet servlet) throws Exception {
		JsonRpcHttpTestServer server = JsonRpcHttpTestServer.builder(eventloop)
			.withServlet(servlet)
			.build();
		server.listen();
		return exchange(eventloop, server, post(NOTIFICATION, "application/json"));
	}

	private static void assertNoContentType(String response) {
		String[] headAndBody = splitHeadAndBody(response);
		assertFalse("no Content-Type may be written: " + response,
			Arrays.stream(headAndBody[0].split("\r\n")).anyMatch(line -> line.startsWith("Content-Type")));
	}

	/**
	 * {@code withEmptyResponseCode(200)} answers a notification with {@code 200} — an empty body,
	 * still no {@code Content-Type} (FR-017 applies to the configured status, not only to 204).
	 */
	@Test
	public void withEmptyResponseCode200Answers200WithAnEmptyBody() throws Exception {
		JsonRpcServlet servlet = JsonRpcServlet.builder(eventloop, dispatcher)
			.withEmptyResponseCode(200)
			.build();

		String response = notificationTo(servlet);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 200 OK"));
		assertTrue("Content-Length: 0: " + response, headAndBody[0].contains("Content-Length: 0"));
		assertNoContentType(response);
		assertEquals("an empty body: " + response, "", headAndBody[1]);
	}

	/** An explicit {@code withEmptyResponseCode(204)} is the default made explicit. */
	@Test
	public void withEmptyResponseCode204IsTheDefaultMadeExplicit() throws Exception {
		JsonRpcServlet servlet = JsonRpcServlet.builder(eventloop, dispatcher)
			.withEmptyResponseCode(204)
			.build();

		String response = notificationTo(servlet);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 204 No Content"));
		assertNoContentType(response);
		assertEquals("", headAndBody[1]);
	}

	/** The no-configuration {@code create(...)} shortcut pins the default: {@code 204}. */
	@Test
	public void createDefaultsTo204() throws Exception {
		JsonRpcServlet servlet = JsonRpcServlet.create(eventloop, dispatcher);

		String response = notificationTo(servlet);

		String[] headAndBody = splitHeadAndBody(response);
		assertTrue("status line: " + response, headAndBody[0].startsWith("HTTP/1.1 204 No Content"));
		assertNoContentType(response);
		assertEquals("", headAndBody[1]);
	}

	/**
	 * Any other value is refused at {@code build()} with {@link IllegalArgumentException} — no
	 * servlet exists to answer {@code 418}, and this must hold with checks off (the validation is
	 * a promised refusal, not a debug aid).
	 */
	@Test
	public void anyOtherEmptyResponseCodeIsRefusedAtBuild() {
		assertThrows("emptyResponseCode 418 must be refused at build()", IllegalArgumentException.class, () ->
			JsonRpcServlet.builder(eventloop, dispatcher).withEmptyResponseCode(418).build());
	}
}
