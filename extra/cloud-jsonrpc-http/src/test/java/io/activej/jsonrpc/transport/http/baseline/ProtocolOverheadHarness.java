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

package io.activej.jsonrpc.transport.http.baseline;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcPayload;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.impl.RawPayloadView;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.http.fixtures.TestApi;
import io.activej.jsonrpc.transport.http.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;

import java.util.Arrays;

import static io.activej.http.HttpHeaders.CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The US5 comparison harness (T057 — FR-071, FR-073, FR-074a, ADR-029): runs {@link JsonRpcServlet}
 * and the reference {@link PlainJsonServlet} over the <b>same request shape</b> — in-process, on
 * the reactor — and reports both figures and their ratio.
 * <p>
 * <b>Not a test.</b> This class has no {@code @Test} method and its name matches none of Surefire's
 * include patterns, so it adds no permanent build cost (FR-074a, ADR-029); it is invoked explicitly.
 * It <b>reports</b> and never <b>asserts</b> a wall-clock threshold — a timing assertion on a
 * shared CI runner is a flaky build gate (FR-073, T059). The only assertion it makes is the parity
 * self-check before timing: the reference servlet's payload must equal the JSON-RPC path's
 * {@code result} member, or the harness refuses to measure a drifted denominator (FR-074a).
 * <p>
 * <b>Running it</b> (exec-maven-plugin 3.4.1 is in the root POM's pluginManagement):
 * <pre>{@code
 * mvn -P extra -pl extra/cloud-jsonrpc-http -am test-compile
 * mvn -P extra -pl extra/cloud-jsonrpc-http exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=io.activej.jsonrpc.transport.http.baseline.ProtocolOverheadHarness
 * }</pre>
 * Optional arguments: {@code segments segmentRounds warmup} (defaults 13 200000 100000).
 * <p>
 * <b>What the figure means.</b> Both paths share the HTTP gates, the body conversion
 * ({@code loadBody → takeBody → asArray}), the request-document decode ({@code JsonRpcDecoder}),
 * the params decode ({@code ParamsCodec}), the single reflective service invocation and the result
 * rendering ({@code JsonUtils} + the result codec). The measured difference is therefore the
 * dispatch table and the response-envelope construction/encoding — a conservative lower bound on
 * the full JSON-RPC overhead, since the request-side envelope decode is common to both paths.
 * <p>
 * <b>Measurement design.</b> Both servlets run in alternating timed <b>segments</b> of
 * {@code segmentRounds} rounds, with the order flipped every segment so drift cancels. The
 * headline figure is the <b>median of the per-segment ratios</b>: a GC pause or scheduler hiccup
 * inflates one segment, not the median — so the ratio is far more stable than either absolute
 * figure on a shared machine. The report carries the per-segment spread beside each median, the
 * ratio spread, and states <i>indistinguishable at this precision</i> when the two absolute bands
 * straddle (WI-17).
 */
public final class ProtocolOverheadHarness {
	/** The request shape both servlets measure: one {@code test.add} call with named params (FR-071). */
	private static final byte[] REQUEST =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}".getBytes(US_ASCII);

	/** Bytes consumed by the timed loops — printed so the JIT cannot prove the work dead (anti-DCE). */
	private static long payloadSink;

	private ProtocolOverheadHarness() {}

	public static void main(String[] args) throws Exception {
		int segments = 13;
		int segmentRounds = 200_000;
		int warmup = 100_000;
		if (args.length > 3) {
			usage();
			return;
		}
		try {
			if (args.length > 0) segments = Integer.parseInt(args[0]);
			if (args.length > 1) segmentRounds = Integer.parseInt(args[1]);
			if (args.length > 2) warmup = Integer.parseInt(args[2]);
		} catch (NumberFormatException e) {
			usage();
			return;
		}
		if (segments <= 0 || segmentRounds <= 0 || warmup <= 0) {
			usage();
			return;
		}

		Eventloop eventloop = Eventloop.builder()
			.withCurrentThread()
			.build();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcServlet jsonRpcServlet = JsonRpcServlet.create(eventloop, dispatcher);
		PlainJsonServlet plainServlet = PlainJsonServlet.create(
			eventloop, TestApi.class, new TestApiImpl(), "test.add");

		// ---- the parity self-check: refuse to measure a denominator that has drifted (FR-074a) ---
		byte[] jsonRpcBody = serveOnce(jsonRpcServlet);
		byte[] plainBody = serveOnce(plainServlet);
		byte[] expectedPayload = resultPayload(jsonRpcBody);
		if (!Arrays.equals(expectedPayload, plainBody)) {
			throw new AssertionError(
				"the reference servlet's payload has drifted from the JSON-RPC path (FR-074a):\n" +
					" JSON-RPC result: " + new String(expectedPayload, US_ASCII) +
					"\n reference body: " + new String(plainBody, US_ASCII));
		}

		// ---- measure: alternating segment pairs; the median of per-segment ratios is the figure --
		run(jsonRpcServlet, warmup);
		run(plainServlet, warmup);
		double[] jsonRpcNsPerSegment = new double[segments];
		double[] plainNsPerSegment = new double[segments];
		double[] ratios = new double[segments];
		for (int s = 0; s < segments; s++) {
			// the order flips every segment, so a slow drift cancels across the run
			if ((s & 1) == 0) {
				jsonRpcNsPerSegment[s] = measure(jsonRpcServlet, segmentRounds);
				plainNsPerSegment[s] = measure(plainServlet, segmentRounds);
			} else {
				plainNsPerSegment[s] = measure(plainServlet, segmentRounds);
				jsonRpcNsPerSegment[s] = measure(jsonRpcServlet, segmentRounds);
			}
			ratios[s] = jsonRpcNsPerSegment[s] / plainNsPerSegment[s];
		}

		// ---- report: medians + spreads, the median ratio, and the straddle verdict (WI-17) ---------
		double jsonRpcMedian = median(jsonRpcNsPerSegment);
		double plainMedian = median(plainNsPerSegment);
		double jsonRpcMin = Arrays.stream(jsonRpcNsPerSegment).min().orElseThrow();
		double jsonRpcMax = Arrays.stream(jsonRpcNsPerSegment).max().orElseThrow();
		double plainMin = Arrays.stream(plainNsPerSegment).min().orElseThrow();
		double plainMax = Arrays.stream(plainNsPerSegment).max().orElseThrow();
		double ratioMedian = median(ratios);
		double ratioMin = Arrays.stream(ratios).min().orElseThrow();
		double ratioMax = Arrays.stream(ratios).max().orElseThrow();
		long jsonRpcFasterSegments = Arrays.stream(ratios).filter(r -> r < 1).count();
		boolean straddles = jsonRpcMin <= plainMax && plainMin <= jsonRpcMax;

		System.out.println("=== JSON-RPC over HTTP — protocol overhead (JsonRpcServlet vs PlainJsonServlet) ===");
		System.out.println("request shape: " + new String(REQUEST, US_ASCII));
		System.out.println("segments: " + segments + " x " + segmentRounds + " rounds, warmup: " + warmup);
		System.out.printf("JsonRpcServlet  : median %8.1f ns/op   spread [%.1f, %.1f] ns/op (%d segments)%n",
			jsonRpcMedian, jsonRpcMin, jsonRpcMax, segments);
		System.out.printf("PlainJsonServlet: median %8.1f ns/op   spread [%.1f, %.1f] ns/op (%d segments)%n",
			plainMedian, plainMin, plainMax, segments);
		System.out.printf("ratio (JsonRpc / plain), median of per-segment ratios: %.3f  ->  protocol overhead ~ +%.1f%%" +
				"   (ratio spread [%.3f, %.3f])%n",
			ratioMedian, (ratioMedian - 1) * 100, ratioMin, ratioMax);
		System.out.printf("overhead bands (ns/op): [%.1f, %.1f] vs [%.1f, %.1f]  ->  %s%n",
			jsonRpcMin, jsonRpcMax, plainMin, plainMax,
			straddles ? "bands straddle — indistinguishable at this precision" : "bands do not overlap");
		System.out.println("JsonRpc faster than plain in " + jsonRpcFasterSegments + "/" + segments + " segments");
		System.out.println("payload bytes consumed across all rounds (anti-DCE): " + payloadSink);
	}

	private static void usage() {
		System.err.println("usage: ProtocolOverheadHarness [segments] [segmentRounds] [warmup]  " +
			"(defaults 13 200000 100000)");
	}

	// ---------------------------------------------------------------------------------------------------
	// Measurement.
	// ---------------------------------------------------------------------------------------------------

	/** One timed segment: {@code rounds} rounds of {@code serve()}, returned as ns/op. */
	private static double measure(AsyncServlet servlet, int rounds) throws Exception {
		long start = System.nanoTime();
		run(servlet, rounds);
		long elapsed = System.nanoTime() - start;
		return (double) elapsed / rounds;
	}

	/** {@code count} rounds of serve() through the same request shape, consuming each response body. */
	private static void run(AsyncServlet servlet, int count) throws Exception {
		for (int i = 0; i < count; i++) {
			HttpRequest request = HttpRequest.post("http://localhost/")
				.withHeader(CONTENT_TYPE, "application/json")
				.withBody(REQUEST)
				.build();
			Promise<HttpResponse> promise = servlet.serve(request);
			// the harness measures a synchronous chain: if either path ever became async, the
			// figure would be garbage, so this is a hard failure rather than a silent one
			if (!promise.isComplete()) {
				throw new IllegalStateException(
					"serve() did not complete synchronously — the harness measures the in-process synchronous path");
			}
			// the response body is read (copies + recycles) so the work is not dead-code-eliminated
			payloadSink += promise.getResult().loadBody().getResult().asArray().length;
		}
	}

	/** One serve, asserted to answer {@code 200}; returns the response body bytes. */
	private static byte[] serveOnce(AsyncServlet servlet) throws Exception {
		HttpRequest request = HttpRequest.post("http://localhost/")
			.withHeader(CONTENT_TYPE, "application/json")
			.withBody(REQUEST)
			.build();
		HttpResponse response = servlet.serve(request).getResult();
		if (response.getCode() != 200) {
			throw new AssertionError("expected 200, got " + response.getCode());
		}
		return response.loadBody().getResult().asArray();
	}

	/** The {@code result} member of a JSON-RPC response document, as its verbatim bytes (verdict 00-A). */
	private static byte[] resultPayload(byte[] jsonRpcBody) {
		JsonRpcDecoded decoded = (JsonRpcDecoded) JsonRpcDecoder.decode(jsonRpcBody);
		if (!(decoded instanceof JsonRpcResponse response)) {
			throw new AssertionError("the JSON-RPC path must answer a response document, got: " +
				decoded.getClass().getSimpleName());
		}
		if (response.error() != null) {
			throw new AssertionError("the JSON-RPC path must answer a result, not an error");
		}
		if (!(response.result() instanceof JsonRpcPayload.Raw raw)) {
			throw new AssertionError("a decoded result is a deferred raw slice (verdict 00-A), got: " +
				response.result().getClass().getSimpleName());
		}
		RawPayloadView view = raw.view();
		return Arrays.copyOfRange(view.array(), view.start(), view.end());
	}

	/** The median of {@code values}; the mean of the two middles when even. */
	private static double median(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int middle = sorted.length / 2;
		return sorted.length % 2 == 0 ?
			(sorted[middle - 1] + sorted[middle]) / 2 :
			sorted[middle];
	}
}
