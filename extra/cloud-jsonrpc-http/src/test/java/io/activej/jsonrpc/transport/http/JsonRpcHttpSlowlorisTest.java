/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import io.activej.http.HttpServer;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
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
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Feature 014 adversarial validation, plan A17 (slowloris): a stalled or dribbled request is
 * bounded by the connection tier's {@code http.readWriteTimeout}, and a connection left idle after
 * an exchange is bounded by {@code keepAliveTimeout} — connections are closed, and nothing leaks.
 * <p>
 * The mechanism is a <b>request-phase deadline</b>, not a per-read idle timer: the server keeps
 * each connection in a pool stamped when it entered, and a 1 s sweep
 * ({@code HttpServer.scheduleExpiredConnectionsCheck}) closes any connection whose phase has
 * outlived the deadline. Data flowing does <b>not</b> extend it — a start line dribbled slower
 * than the deadline is closed even while bytes keep arriving — which is exactly the plan's
 * requirement (a dribble cannot hold a connection open forever; with the 60 s default, a 1-byte-
 * per-30 s dribble dies at the first sweep past 60 s).
 * <p>
 * Timing-tolerant by construction (WI-17): the server is built with <b>short</b> timeouts — the
 * mechanism is the contract, not the 60 s default — and every assertion waits inside a generous
 * window (the sweep granularity is 1 s on top of the configured timeout) without ever asserting a
 * wall-clock value.
 */
public final class JsonRpcHttpSlowlorisTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final Duration READ_WRITE_TIMEOUT = Duration.ofMillis(300);
	private static final Duration KEEP_ALIVE_TIMEOUT = Duration.ofMillis(800);

	private EventloopThread loop;
	private HttpServer server;
	private int port;

	@Before
	public void setUp() {
		loop = EventloopThread.create("jsonrpc-http-slowloris-test");
		loop.submit(() -> {
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
				.withService(TestApi.class, new TestApiImpl())
				.build();
			server = HttpServer.builder(loop.eventloop(), JsonRpcServlet.create(loop.eventloop(), dispatcher))
				.withListenPort(0)
				.withReadWriteTimeout(READ_WRITE_TIMEOUT)
				.withKeepAliveTimeout(KEEP_ALIVE_TIMEOUT)
				.build();
			try {
				server.listen();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			port = server.getBoundAddresses().get(0).getPort();
		});
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			server.closeFuture().get(10, TimeUnit.SECONDS);
		}
		loop.close();
	}

	/**
	 * A connection that opens and sends <b>nothing</b> is closed once its request phase outlives
	 * {@code readWriteTimeout}: the first sweep past the deadline closes it, and the peer observes
	 * the close. The window is generous (the configured deadline is 300 ms plus the 1 s sweep
	 * granularity; the assertion allows the whole 10 s socket timeout).
	 */
	@Test
	public void aSilentConnectionIsClosedByTheReadWriteTimeout() throws Exception {
		try (Socket socket = new Socket()) {
			socket.setSoTimeout(10_000);
			socket.connect(new InetSocketAddress("127.0.0.1", port));
			InputStream input = socket.getInputStream();
			assertEquals("the server closes the stalled connection", -1, input.read());
		}
	}

	/**
	 * A start line dribbled one byte per interval <b>past the deadline</b> is closed even though
	 * bytes keep flowing: the phase deadline is not an idle timer, so the dribble cannot extend the
	 * connection's life. This is the plan's own scenario at reduced scale — with the 60 s default,
	 * a 1-byte-per-30 s dribble dies the same way. The dribble writes may or may not hit the
	 * closed socket (EPIPE); the assertion is that the connection is closed with <b>no response
	 * bytes</b> before the request could complete.
	 */
	@Test
	public void aDribblePastTheDeadlineIsClosedEvenWithDataFlowing() throws Exception {
		String head = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Length: 67\r\n" +
			"\r\n";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
		byte[] headBytes = head.getBytes(US_ASCII);

		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(10_000);
			socket.connect(new InetSocketAddress("127.0.0.1", port));
			OutputStream output = socket.getOutputStream();
			InputStream input = socket.getInputStream();

			// dribble the whole start line at 100 ms per byte: 1 s of dribbling against a 300 ms
			// phase deadline — the connection must die even though data keeps arriving
			int sent = 0;
			try {
				for (int i = 0; i < headBytes.length; i++) {
					output.write(headBytes[i]);
					output.flush();
					sent++;
					Thread.sleep(100);
				}
				output.write(body.getBytes(US_ASCII));
				output.flush();
			} catch (IOException ignored) {
				// the server closed the connection mid-dribble — the point of this case
			}

			// the connection is closed and NO response was ever produced (the request never completed)
			byte[] received = readAvailable(input);
			assertEquals("a request that outlives the deadline is closed with no response bytes: " +
				"dribbled " + sent + " bytes", 0, received.length);
		}
	}

	/**
	 * The tolerated direction: a request delivered as many small writes — but completing each phase
	 * inside the deadline — is served normally, and the keep-alive idle connection is then closed
	 * by {@code keepAliveTimeout}. Fragmented delivery is a property of real networks; the server
	 * must neither fail on it nor hold the idle connection forever.
	 */
	@Test
	public void aFragmentedRequestWithinTheDeadlineIsServedAndIdleClosesByKeepAlive() throws Exception {
		String head = "POST / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: application/json\r\n" +
			"Content-Length: 67\r\n" +
			"\r\n";
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}";
		assertEquals(67, body.length());
		byte[] headBytes = head.getBytes(US_ASCII);

		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(10_000);
			socket.connect(new InetSocketAddress("127.0.0.1", port));
			OutputStream output = socket.getOutputStream();
			InputStream input = socket.getInputStream();

			// deliver byte-by-byte with no artificial delay — every write inside the deadline
			for (byte b : headBytes) {
				output.write(b);
			}
			output.write(body.getBytes(US_ASCII));
			output.flush();

			// the exchange completes, then the keep-alive idle connection is closed by the server's
			// keepAliveTimeout — readAll returns only when the peer closes
			String response = new String(readAll(input), US_ASCII);
			assertTrue("the fragmented request is served: " + response,
				response.startsWith("HTTP/1.1 200 OK"));
			assertTrue("the dispatcher's bytes: " + response, response.contains("\"sum\":5"));
		}
	}

	/** Reads everything currently available and returns it; never blocks past the first byte. */
	private static byte[] readAvailable(InputStream input) throws IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		int first = input.read();
		if (first == -1) return new byte[0];
		buffer.write(first);
		byte[] chunk = new byte[1024];
		int n;
		while ((n = input.read(chunk)) != -1) {
			buffer.write(chunk, 0, n);
		}
		return buffer.toByteArray();
	}

	/** Reads until the peer closes. */
	private static byte[] readAll(InputStream input) throws IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		int b;
		while ((b = input.read()) != -1) {
			buffer.write(b);
		}
		return buffer.toByteArray();
	}
}
