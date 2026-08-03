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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpError;
import io.activej.http.HttpExceptionFormatter;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestPeer.Response;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T066 / FR-045: a failed servlet promise is rendered by the same {@link HttpExceptionFormatter}
 * {@code core-http}'s {@code HttpServer} uses, and the stream that carries the rendering ends
 * <b>normally</b> — a 404 or a 500 is an HTTP outcome, not an HTTP/3 protocol violation, so nothing on
 * that stream is reset and the connection stays usable.
 * <p>
 * The formatter is pinned to {@link HttpExceptionFormatter#DEFAULT_FORMATTER} rather than left at
 * {@code COMMON_FORMATTER}: the latter switches to the stack-trace-rendering debug formatter when
 * {@code idea_rt.jar} is on the class path, which would make the "no stack trace" assertion pass under
 * Maven and fail in an IDE.
 */
public final class Http3ServerErrorTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3TestPeer peer;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void anHttpErrorIsRenderedAndTheStreamEndsNormally() {
		start(request -> Promise.ofException(HttpError.notFound404()));

		Response response = await(peer.get("/missing"));

		assertEquals(404, response.status());
		assertTrue("the default formatter's HTML body: " + response.bodyString(),
			response.bodyString().contains("404"));
		assertEquals("no-store", response.field("cache-control"));
		assertEquals("the stream carried a rendered response, so nothing was reset", 0, server.requestsAborted());
	}

	@Test
	public void anUnexpectedExceptionYields500WithNoStackTrace() {
		start(request -> Promise.ofException(new ExpectedException("a servlet blew up: secretDetail")));

		Response response = await(peer.get("/boom"));

		assertEquals(500, response.status());
		String body = response.bodyString();
		assertFalse("the default formatter leaks nothing about an unknown exception: " + body,
			body.contains("secretDetail"));
		assertFalse(body.contains("ExpectedException"));
		assertFalse(body.contains("io.activej.http3"));
		assertEquals(1, server.requestsFailed());
	}

	@Test
	public void aThrownExceptionIsRenderedTheSameWayAsAFailedPromise() {
		start(request -> {
			throw new ExpectedException("thrown, not returned");
		});

		assertEquals(500, await(peer.get("/throws")).status());
	}

	@Test
	public void theConnectionStaysUsableAfterAFailedRequest() {
		start(request -> request.getPath().equals("/boom") ?
			Promise.ofException(new ExpectedException("boom")) :
			HttpResponse.ok200().withBody("fine".getBytes(UTF_8)).toPromise());

		assertEquals(500, await(peer.get("/boom")).status());

		Response second = await(peer.get("/ok"));
		assertEquals(200, second.status());
		assertEquals("fine", second.bodyString());
		assertEquals("one QUIC connection served both", 1, server.connectionsAccepted());
	}

	// ---------------------------------------------------------------- harness

	private void start(AsyncServlet servlet) {
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				server = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withHttpErrorFormatter(HttpExceptionFormatter.DEFAULT_FORMATTER)
					.build();
				server.listen();
				return server;
			})
			.connect();
	}

	private <T> T await(Promise<T> promise) {
		wire.driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the request failed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
