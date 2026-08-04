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

package io.activej.http3;

import io.activej.dns.DnsClient;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpClient;
import io.activej.http.HttpError;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;

import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

/**
 * T068 / SC-013: <b>one</b> {@link AsyncServlet} instance, served at the same time by {@code core-http}'s
 * {@link HttpServer} over TCP and by {@link Http3Server} over QUIC, answering the same three requests
 * identically.
 * <p>
 * The HTTP/1.1 half is a real server on a loopback port with a real {@link HttpClient}; the HTTP/3 half
 * is the in-process fixture. Both run on the one hand-driven eventloop, so
 * {@link Http3WirePair#driveUntil} drives the datagram fabric and the selector alternately until a
 * promise settles — a socket that is ready is picked up on the next iteration rather than waited on.
 * <p>
 * <b>What "identical" means here</b>: status, body and the headers the servlet itself set.
 * {@code Connection} and {@code Keep-Alive} are excluded by construction — RFC 9114 §4.2 makes them
 * malformed in HTTP/3, which is exactly the difference this comparison is meant to tolerate.
 */
public final class Http3SameServletBothVersionsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The one servlet both transports serve. Stateless, so neither half can influence the other. */
	private static final AsyncServlet SERVLET = request -> switch (request.getPath()) {
		case "/hello" -> HttpResponse.ok200()
			.withHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
			.withBody("Hello from one servlet".getBytes(UTF_8))
			.toPromise();
		case "/echo" -> request.loadBody()
			.map(body -> HttpResponse.ok200()
				.withHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
				.withBody(("echo:" + body.getString(UTF_8)).getBytes(UTF_8))
				.build());
		default -> Promise.ofException(HttpError.notFound404());
	};

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server h3Server;
	private @Nullable Http3TestPeer peer;
	private @Nullable HttpServer httpServer;
	private @Nullable HttpClient httpClient;
	private String url;

	@Before
	public void setUp() throws IOException {
		loop = new ManualEventloop();
		int port = getFreePort();
		url = "http://127.0.0.1:" + port;

		httpServer = HttpServer.builder(reactor(), SERVLET)
			.withListenPort(port)
			.build();
		httpServer.listen();
		// Never queried: every URL below names an IP literal, which IDnsClient answers from the query itself.
		httpClient = HttpClient.builder(reactor(), DnsClient.create(reactor(), HttpUtils.inetAddress("8.8.8.8")))
			.build();

		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				h3Server = Http3Server.builder(reactor(), SERVLET)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.build();
				h3Server.listen();
				return h3Server;
			})
			.connect();
	}

	@After
	public void tearDown() {
		if (httpClient != null) httpClient.stop();
		if (httpServer != null) httpServer.close();
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void aPlainGetIsAnsweredIdentically() {
		assertSameOverBothVersions(200, "Hello from one servlet", "/hello", null);
	}

	@Test
	public void aPostBodyIsEchoedIdentically() {
		assertSameOverBothVersions(200, "echo:payload", "/echo", "payload".getBytes(UTF_8));
	}

	@Test
	public void anHttpErrorIsRenderedIdentically() {
		// The same HttpExceptionFormatter renders both, so even the HTML body must match byte for byte.
		Answer overHttp1 = overHttp1("/nope", null);
		Answer overHttp3 = overHttp3("/nope", null);

		assertEquals(404, overHttp1.status);
		assertEquals(overHttp1.status, overHttp3.status);
		assertEquals(overHttp1.body, overHttp3.body);
	}

	private void assertSameOverBothVersions(int expectedStatus, String expectedBody, String path, byte @Nullable [] body) {
		Answer overHttp1 = overHttp1(path, body);
		Answer overHttp3 = overHttp3(path, body);

		assertEquals(expectedStatus, overHttp1.status);
		assertEquals(expectedBody, overHttp1.body);

		assertEquals("status", overHttp1.status, overHttp3.status);
		assertEquals("body", overHttp1.body, overHttp3.body);
		assertEquals("content-type", overHttp1.contentType, overHttp3.contentType);
		assertEquals("content-length", overHttp1.contentLength, overHttp3.contentLength);
	}

	// ---------------------------------------------------------------- the two transports

	private record Answer(int status, String body, @Nullable String contentType, @Nullable String contentLength) {}

	private Answer overHttp1(String path, byte @Nullable [] body) {
		HttpRequest.Builder builder = body == null ?
			HttpRequest.get(url + path) :
			HttpRequest.post(url + path).withBody(body);
		Promise<Answer> answer = httpClient.request(builder.build())
			.then(response -> response.loadBody()
				.map(loaded -> new Answer(
					response.getCode(),
					loaded.getString(UTF_8),
					response.getHeader(HttpHeaders.CONTENT_TYPE),
					response.getHeader(HttpHeaders.CONTENT_LENGTH))));
		return await(answer);
	}

	private Answer overHttp3(String path, byte @Nullable [] body) {
		Promise<Http3TestPeer.Response> response = body == null ? peer.get(path) : peer.post(path, body);
		Http3TestPeer.Response answered = await(response);
		return new Answer(answered.status(), answered.bodyString(),
			answered.field("content-type"), answered.field("content-length"));
	}

	private <T> T await(Promise<T> promise) {
		wire.driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the exchange did not succeed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
