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

package io.activej.jsonrpc.transport.http.interop;

import io.activej.eventloop.Eventloop;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.StubHttpClient;
import io.activej.jsonrpc.ConformanceJson;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.http.interop.InteropVectors.Vector;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The frozen interoperability tier (T050 — FR-060…FR-062, FR-065): every vector of
 * {@code http-vectors.json} — real exchanges captured from a real {@code curl} and a real
 * JavaScript {@code fetch()} — is replayed <b>with no network and no external binary</b>, by
 * driving the real {@link JsonRpcServlet} in-process through {@link StubHttpClient} (FR-062,
 * FR-056). This tier is deliberately curl-free: it never probes {@code PATH} and never starts a
 * socket, so it runs identically on a machine with curl removed.
 * <p>
 * <b>Comparison rules</b> (FR-065): the status and the asserted headers — the vector's allow-list,
 * not the full response set — are compared exactly; the body is compared with feature 010's own
 * {@link ConformanceJson} JSON-value rules, never by string equality, so key order and
 * insignificant whitespace are not part of the contract (data-model §3). A change to the response
 * bytes of any frozen vector fails this build.
 * <p>
 * Each assertion message names the vector; every vector is replayed in one pass, so a regression
 * reports the first mismatch of each kind rather than hiding later vectors behind an early failure.
 */
public final class InteropVectorsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private JsonRpcServlet servlet;

	@Before
	public void setUp() {
		Eventloop eventloop = Reactor.getCurrentReactor();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		servlet = JsonRpcServlet.create(eventloop, dispatcher);
	}

	/** Every frozen vector, replayed in-process. */
	@Test
	public void everyFrozenVectorReplaysThroughTheServlet() {
		List<Vector> vectors = InteropVectors.load();
		assertFalse("http-vectors.json must not be empty (FR-061)", vectors.isEmpty());
		for (Vector vector : vectors) {
			replay(vector);
		}
	}

	/**
	 * The required coverage of FR-061, asserted structurally: the four exchange shapes — a single
	 * request, a notification, a batch, one rejection path — from each origin that can produce them.
	 * This pins the file's coverage the way the file's content pins the wire.
	 */
	@Test
	public void theFrozenSetCoversTheRequiredShapesFromBothOrigins() {
		List<Vector> vectors = InteropVectors.load();
		for (String origin : List.of("curl", "fetch")) {
			List<Vector> ofOrigin = vectors.stream()
				.filter(vector -> vector.origin().equals(origin))
				.toList();
			assertTrue("no vectors captured from origin '" + origin + "' (FR-061)", !ofOrigin.isEmpty());
			assertTrue("origin '" + origin + "' has no single-request vector (FR-061)",
				ofOrigin.stream().anyMatch(vector ->
					vector.method().equals("POST") &&
						vector.requestBody() != null && !vector.requestBody().startsWith("[")));
			assertTrue("origin '" + origin + "' has no notification vector (FR-061)",
				ofOrigin.stream().anyMatch(vector ->
					vector.status() == 204 && vector.bodyAbsent()));
			assertTrue("origin '" + origin + "' has no batch vector (FR-061)",
				ofOrigin.stream().anyMatch(vector ->
					vector.method().equals("POST") &&
						vector.requestBody() != null && vector.requestBody().startsWith("[")));
			assertTrue("origin '" + origin + "' has no rejection-path vector (FR-061)",
				ofOrigin.stream().anyMatch(vector -> vector.status() != 200 && vector.status() != 204));
		}
	}

	/**
	 * One vector, replayed: the request is rebuilt exactly as captured — method, recorded headers,
	 * body — and served in-process. The response's status and each asserted header are compared
	 * exactly; the body by {@link ConformanceJson}.
	 * <p>
	 * Everything is read <b>inside</b> the request's promise chain: {@code StubHttpClient} recycles
	 * the response through a posted task once its body stream ends, so by the time {@code await}
	 * returns the message may already be recycled and {@code getCode()}/{@code getHeader()} would
	 * fail their not-recycled guard. The chain reads status and headers first, then materialises
	 * the body with {@code asArray()} — one copy, one recycle — before the recycle post can run.
	 */
	private void replay(Vector vector) {
		String label = "vector '" + vector.name() + "' (" + vector.origin() + "): ";

		HttpRequest.Builder requestBuilder = vector.method().equals("POST") ?
			HttpRequest.post("http://localhost/") :
			HttpRequest.get("http://localhost/");
		for (Map.Entry<String, String> header : vector.requestHeaders().entrySet()) {
			requestBuilder.withHeader(HttpHeaders.of(header.getKey()), header.getValue());
		}
		if (vector.requestBody() != null) {
			requestBuilder.withBody(vector.requestBody().getBytes(UTF_8));
		}

		Recorded recorded = await(StubHttpClient.of(servlet).request(requestBuilder.build())
			.then(response -> {
				int code = response.getCode();
				Map<String, String> actualHeaders = new LinkedHashMap<>();
				for (String name : vector.expectHeaders().keySet()) {
					actualHeaders.put(name, response.getHeader(HttpHeaders.of(name)));
				}
				// A bodiless response (204, 405) has no body stream at all — loadBody would throw;
				// the empty body IS the assertion, so record it without loading.
				return vector.bodyAbsent() ?
					Promise.of(new Recorded(code, actualHeaders, new byte[0])) :
					response.loadBody()
						.map(body -> new Recorded(code, actualHeaders, body.asArray()));
			}));

		assertEquals(label + "status", vector.status(), recorded.code);
		for (Map.Entry<String, String> expected : vector.expectHeaders().entrySet()) {
			assertEquals(label + "header " + expected.getKey(), expected.getValue(),
				recorded.headers.get(expected.getKey()));
		}

		if (vector.bodyAbsent()) {
			assertEquals(label + "a bodiless response must carry no body", 0, recorded.body.length);
			return;
		}
		assertTrue(label + "a body-bearing response must carry one", recorded.body.length > 0);
		Object expectedJson = ConformanceJson.parseJson(vector.body());
		Object actualJson = ConformanceJson.parseJson(new String(recorded.body, UTF_8));
		try {
			ConformanceJson.assertJsonEquals(expectedJson, actualJson);
		} catch (AssertionError e) {
			fail(label + "body mismatch: " + e.getMessage());
		}
	}

	/** What a replayed exchange produced, captured before the response could be recycled. */
	private record Recorded(int code, Map<String, String> headers, byte[] body) {}
}
