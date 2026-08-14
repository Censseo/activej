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

package io.activej.jsonrpc.transport.http.fixtures;

import io.activej.eventloop.Eventloop;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertTrue;

/**
 * The shared raw-socket exchange helpers for this module's Phase 3 tests — the one place the
 * "bind {@code :0}, run the eventloop on its own thread, block on a {@code java.net.Socket}"
 * idiom of {@code core-http}'s {@code HttpServerTest} lives, so five test classes do not each
 * carry their own copy (plan §3, flagged decision 4).
 * <p>
 * The helpers are moved-shape copies of the private ones in
 * {@code JsonRpcHttpWireProbesTest}, including its two lessons: the {@code indexOf}-based
 * head/body split (not {@code String.split} — a bodyless response ends with the delimiter, which
 * is exactly the shape the empty-response tests assert) and the <b>time-bounded</b> teardown (both
 * {@code closeFuture().get(10, s)} and {@code eventloopThread.join(10_000)} bounded, failing fast
 * on a hung loop). This class binds nothing of its own — the harness binds {@code :0} — so it is
 * invisible to the module's {@code :0}-not-{@code getFreePort()} boundary scan.
 */
public final class JsonRpcHttpRawExchange {
	private JsonRpcHttpRawExchange() {}

	/**
	 * Runs one raw exchange against {@code server}: starts {@code eventloop} on its own thread,
	 * blocks on a {@code java.net.Socket} until the server closes the connection (every request
	 * built by {@link #post(String, String, String)} carries {@code Connection: close}), then
	 * closes the server on its loop and joins the thread. The {@code finally} runs on every path,
	 * assertion failures included, and both of its waits are bounded — a failure that kills the
	 * eventloop thread must fail the test quickly, not hang the suite.
	 */
	public static String exchange(Eventloop eventloop, JsonRpcHttpTestServer server, String request) throws Exception {
		return exchange(eventloop, server, request.getBytes(US_ASCII));
	}

	/**
	 * The byte-payload form of {@link #exchange(Eventloop, JsonRpcHttpTestServer, String)} — for
	 * requests whose body is binary (a gzip stream, whose bytes above {@code 0x7F} do not survive a
	 * {@code String} round trip). The head is ASCII and the body is written verbatim.
	 */
	public static String exchange(Eventloop eventloop, JsonRpcHttpTestServer server, byte[] request) throws Exception {
		Thread eventloopThread = new Thread(eventloop);
		eventloopThread.start();
		try {
			try (Socket socket = new Socket()) {
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(5_000);
				socket.connect(new InetSocketAddress("localhost", server.port()));
				OutputStream output = socket.getOutputStream();
				output.write(request);
				output.flush();
				InputStream input = socket.getInputStream();
				return new String(input.readAllBytes(), US_ASCII);
			}
		} finally {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} finally {
				eventloopThread.join(10_000);
				if (eventloopThread.isAlive()) {
					throw new IllegalStateException("the eventloop thread did not stop: a test left it running");
				}
			}
		}
	}

	/**
	 * The "the body was never read" proof (plan phase 4, decision D4): sends {@code request} — a
	 * head declaring a {@code Content-Length} with <b>no body bytes after it</b> — and reads
	 * <b>only up to the first {@code \r\n\r\n}</b>, bounded by the socket's {@code soTimeout}.
	 * <p>
	 * A full {@code readAllBytes()} would hang here: when the servlet answers without reading the
	 * request body, the connection never sets {@code BODY_RECEIVED}, so {@code onHttpMessageComplete}
	 * is never reached and the connection does not close — the pending body is only drained when the
	 * request is recycled. The prompt arrival of the head is exactly the proof: the response came
	 * without the servlet waiting for the declared body. The caller's {@code try}-with-resources
	 * closes the socket afterwards; the server's unread-body drain then errors out on the peer
	 * close and the connection closes — no leak, no hang (pinned by {@code ByteBufRule}).
	 */
	public static String exchangeHead(Eventloop eventloop, JsonRpcHttpTestServer server, String request) throws Exception {
		Thread eventloopThread = new Thread(eventloop);
		eventloopThread.start();
		try {
			try (Socket socket = new Socket()) {
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(5_000);
				socket.connect(new InetSocketAddress("localhost", server.port()));
				OutputStream output = socket.getOutputStream();
				output.write(request.getBytes(US_ASCII));
				output.flush();
				InputStream input = socket.getInputStream();
				ByteArrayOutputStream head = new ByteArrayOutputStream();
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
		} finally {
			try {
				server.closeFuture().get(10, TimeUnit.SECONDS);
			} finally {
				eventloopThread.join(10_000);
				if (eventloopThread.isAlive()) {
					throw new IllegalStateException("the eventloop thread did not stop: a test left it running");
				}
			}
		}
	}

	/** A minimal HTTP/1.1 POST to the server root with an explicit {@code Content-Length} and {@code Connection: close}. */
	public static String post(String body, String contentType) {
		return post("/", body, contentType);
	}

	/** A minimal HTTP/1.1 POST to {@code path} with an explicit {@code Content-Length} and {@code Connection: close}. */
	public static String post(String path, String body, String contentType) {
		return "POST " + path + " HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Type: " + contentType + "\r\n" +
			"Content-Length: " + body.length() + "\r\n" +
			"Connection: close\r\n" +
			"\r\n" + body;
	}

	/**
	 * Splits a raw response into {@code {head, body}} at the first blank line. {@code String.split}
	 * is not used: it produces a trailing empty element for a bodyless response, which is exactly
	 * the shape the empty-response tests assert.
	 */
	public static String[] splitHeadAndBody(String response) {
		int bodyStart = response.indexOf("\r\n\r\n");
		assertTrue("no blank line separating head from body: " + response, bodyStart >= 0);
		return new String[]{response.substring(0, bodyStart), response.substring(bodyStart + 4)};
	}
}
