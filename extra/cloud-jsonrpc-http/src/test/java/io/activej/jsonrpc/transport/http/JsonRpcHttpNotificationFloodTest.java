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
import io.activej.test.EventloopThread;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Feature 014 adversarial validation, plan A16 — an inondation of notifications: 10,000 separate
 * {@code no.such} notifications over one keep-alive connection. Each notification is answered with
 * <b>zero bytes</b> (the {@code 204} empty response; the dispatcher's {@code -32601} is a
 * request's answer, and a notification is never answered — §4.1), the connection stays usable, and
 * the {@link JsonRpcDispatcher.JmxInspector} counts every miss in the aggregate-only
 * {@code methodNotFound} bucket — {@code methodStats} gains no row, because the row set is exactly
 * the registered wire names, frozen at dispatcher {@code build()} (FR-034).
 * <p>
 * The flood is 10,000 <b>separate</b> requests, not one batch: a 10,000-element batch would be
 * refused as {@code -32002} by the batch bound before a single notification dispatched — the plan's
 * "zéro octet en retour" is only reachable request-by-request.
 */
public final class JsonRpcHttpNotificationFloodTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final int FLOOD_SIZE = 10_000;

	private EventloopThread loop;
	private JsonRpcHttpTestServer server;
	private JsonRpcDispatcher.JmxInspector inspector;
	private int port;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-flood-test");
		loop.submit(() -> {
			inspector = new JsonRpcDispatcher.JmxInspector();
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.withInspector(inspector)
				.build();
			server = JsonRpcHttpTestServer.builder(loop.eventloop())
				.withServlet(JsonRpcServlet.create(loop.eventloop(), dispatcher))
				.build();
			try {
				server.listen();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			port = server.port();
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			server.closeFuture().get(10, TimeUnit.SECONDS);
		}
		loop.close();
	}

	@Test
	public void tenThousandUnknownNotificationsAnswerZeroBytesEachAndAreAllCounted() throws Exception {
		String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"no.such\",\"params\":{\"x\":1}}";
		String head = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Length: " + notification.length() + "\r\n" +
			"\r\n";

		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(20_000);
			socket.connect(new InetSocketAddress("127.0.0.1", port));
			OutputStream output = socket.getOutputStream();
			InputStream input = socket.getInputStream();

			for (int i = 0; i < FLOOD_SIZE; i++) {
				output.write(head.getBytes(US_ASCII));
				output.write(notification.getBytes(US_ASCII));
				output.flush();

				String response = readHead(input);
				assertTrue("response " + i + " is the 204 empty answer: " + response,
					response.startsWith("HTTP/1.1 204 No Content"));
				assertTrue("response " + i + " declares an empty body: " + response,
					response.contains("Content-Length: 0"));
			}

			// the connection survived all 10,000 exchanges — one final request is served normally
			String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
			String requestHead = "POST / HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Content-Type: application/json\r\n" +
				"Content-Length: " + request.length() + "\r\n" +
				"Connection: close\r\n" +
				"\r\n";
			output.write(requestHead.getBytes(US_ASCII));
			output.write(request.getBytes(US_ASCII));
			output.flush();

			String finalResponse = readHead(input) + new String(readAll(input), US_ASCII);
			assertTrue("the connection is still alive after the flood: " + finalResponse,
				finalResponse.startsWith("HTTP/1.1 200 OK"));
			assertTrue("the final request was dispatched: " + finalResponse, finalResponse.contains("\"sum\":5"));
		}

		// the stats half: every miss counted in the aggregate-only buckets, no row ever created
		// totalRequests counts the final connection-alive proof request too (10,000 + 1)
		assertEquals("methodNotFound == 10,000 (FR-034: aggregate-only)", (long) FLOOD_SIZE,
			(long) loop.submit(() -> inspector.getMethodNotFound().getTotalCount()));
		assertEquals("totalRequests == 10,001 (the flood plus the final sanity request)", (long) FLOOD_SIZE + 1,
			(long) loop.submit(() -> inspector.getTotalRequests().getTotalCount()));
		assertEquals("totalErrors == 10,000 — the final request succeeded", (long) FLOOD_SIZE,
			(long) loop.submit(() -> inspector.getTotalErrors().getTotalCount()));
		assertEquals("no malformed document in the flood", 0L,
			(long) loop.submit(() -> inspector.getMalformedDocuments().getTotalCount()));
		assertEquals("methodStats rows are exactly the registered wire names — no.such never became a row",
			Set.of("test.add", "test.notify", "test.notifyAsync", "test.failDeliberately",
				"test.failWithData", "test.failAccidentally"),
			loop.submit(() -> inspector.getMethodStats().keySet()));
	}

	/** Reads a response head (up to the blank line) — the 204 carries no body. */
	private static String readHead(InputStream input) throws IOException {
		java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
		byte[] delimiter = "\r\n\r\n".getBytes(US_ASCII);
		int matched = 0;
		int b;
		while ((b = input.read()) != -1) {
			head.write(b);
			if (b == delimiter[matched]) {
				if (++matched == delimiter.length) break;
			} else {
				matched = b == delimiter[0] ? 1 : 0;
			}
		}
		return head.toString(US_ASCII);
	}

	private static byte[] readAll(InputStream input) throws IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		int b;
		while ((b = input.read()) != -1) {
			buffer.write(b);
		}
		return buffer.toByteArray();
	}
}
