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

package io.activej.jsonrpc.transport.tcp.baseline;

import io.activej.async.exception.AsyncCloseException;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpClient;
import io.activej.http.HttpServer;
import io.activej.http.HttpUtils;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.http.JsonRpcHttpClientTransport;
import io.activej.jsonrpc.transport.http.JsonRpcServlet;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpServer;
import io.activej.jsonrpc.transport.tcp.JsonRpcTcpTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The cross-transport comparison harness (T028 — SC-009, research D11, ADR-029): the <b>same</b>
 * JSON-RPC request shape, answered by the <b>same</b> {@link JsonRpcDispatcher} over the <b>same</b>
 * service implementation, carried once over framed TCP ({@link JsonRpcTcpServer} +
 * {@link JsonRpcTcpTransport#connect}) and once over HTTP POST ({@code JsonRpcServlet} + a real
 * {@code HttpServer}/{@code HttpClient}) — both on loopback, both driven by one reactor.
 * <p>
 * <b>Not a test.</b> This class has no {@code @Test} method and its name matches none of Surefire's
 * default include patterns ({@code *Test}, {@code Test*}, {@code *Tests}, {@code *TestCase}), so it
 * adds no permanent build cost (ADR-029). It <b>reports</b> and never <b>asserts</b> a wall-clock
 * threshold — a timing assertion on a shared runner is a flaky build gate. Its one assertion is the
 * parity self-check before timing: both transports must answer the same request with a
 * <b>byte-identical</b> response document, or the harness refuses to measure two things that are not
 * the same thing.
 * <p>
 * <b>Running it</b> (exec-maven-plugin 3.4.1 is in the root POM's pluginManagement):
 * <pre>{@code
 * mvn -P extra -pl extra/cloud-jsonrpc-tcp -am test-compile
 * mvn -P extra -pl extra/cloud-jsonrpc-tcp exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=io.activej.jsonrpc.transport.tcp.baseline.TransportOverheadHarness
 * }</pre>
 * Optional arguments: {@code segments segmentRounds warmup} (defaults 13 5000 5000).
 * <p>
 * <b>What the figure means.</b> Both legs share everything above the transport — the request
 * document bytes, the envelope decode, the params decode, the dispatch table, the single reflective
 * service invocation, the result rendering and the response document bytes. The measured difference
 * is therefore the transport itself: on TCP one LF-terminated line each way over an already-open
 * connection; on HTTP a full request line, request headers, a status line and response headers each
 * way, plus the servlet's method/content-type/length gates. Both legs measure a full round trip —
 * {@code send}, then the answer delivered to the listener — not a one-way write.
 * <p>
 * <b>The HTTP leg is deliberately measured in its most favourable configuration.</b>
 * {@code HttpClient.KEEP_ALIVE_TIMEOUT} defaults to {@code 0}, which would make every call pay a
 * fresh TCP connect and compare a persistent transport with a non-persistent one. The harness sets
 * the client's keep-alive to 30 s (matching {@code HttpServer}'s own default) so both legs ride one
 * connection for the whole run. The reported difference is therefore a <b>lower bound</b> on what a
 * default-configured HTTP client would show.
 * <p>
 * <b>Measurement design</b> (feature 013's {@code ProtocolOverheadHarness}, re-expressed for two
 * transports rather than two servlets). Both legs run in alternating timed <b>segments</b> of
 * {@code segmentRounds} round trips, with the order flipped every segment so drift cancels. The
 * headline figure is the <b>median of the per-segment ratios</b>: a GC pause or a scheduler hiccup
 * inflates one segment, not the median. The report carries the per-segment spread beside each
 * median, the ratio spread, and states <i>indistinguishable at this precision</i> when the two
 * absolute bands straddle (WI-17).
 * <p>
 * <b>Threats to validity.</b> One reactor thread carries both peers of both legs, so every round
 * trip includes two selector wake-ups on the same thread — the absolute numbers are not a
 * deployment's latency. Both legs pay it identically, which is what makes the ratio the meaningful
 * figure. Loopback removes the network entirely, which favours the leg with more bytes on the wire
 * (HTTP); on a real link the difference would widen, not narrow. Pooled {@code ByteBuf} behaviour is
 * the production default here, not Surefire's leak-hunting configuration.
 */
public final class TransportOverheadHarness {
	/** The request shape both transports carry: one {@code test.add} call with named params. */
	private static final byte[] REQUEST =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":{\"a\":2,\"b\":3}}".getBytes(US_ASCII);

	/** Matches {@code HttpServer}'s own default, so the HTTP leg rides one connection like the TCP one. */
	private static final Duration HTTP_KEEP_ALIVE = Duration.ofSeconds(30);

	/** Bytes consumed by the timed loops — printed so the JIT cannot prove the work dead (anti-DCE). */
	private static long documentSink;

	private TransportOverheadHarness() {}

	public static void main(String[] args) throws Exception {
		int segments = 13;
		int segmentRounds = 5000;
		int warmup = 5000;
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

		// one dispatcher, one service instance, both legs — the numerator and the denominator differ
		// in the transport and in nothing else
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();

		JsonRpcTcpServer tcpServer = JsonRpcTcpServer.builder(eventloop, dispatcher)
			.withListenPort(0)                                      // ADR-028: bind :0, then ask
			.build();
		tcpServer.listen();

		HttpServer httpServer = HttpServer.builder(eventloop, JsonRpcServlet.create(eventloop, dispatcher))
			.withListenPort(0)
			.build();
		httpServer.listen();

		HttpClient httpClient = HttpClient.builder(eventloop,
				DnsClient.create(eventloop, HttpUtils.inetAddress("8.8.8.8")))   // IP literal: no DNS traffic
			.withKeepAliveTimeout(HTTP_KEEP_ALIVE)
			.build();

		int tcpPort = tcpServer.getBoundAddresses().get(0).getPort();
		int httpPort = httpServer.getBoundAddresses().get(0).getPort();

		Report report = new Report(segments, segmentRounds, warmup, tcpPort, httpPort);

		eventloop.post(() -> JsonRpcTcpTransport.connect(eventloop, new InetSocketAddress("127.0.0.1", tcpPort))
			.then(tcpTransport -> {
				Leg tcp = new Leg(tcpTransport);
				Leg http = new Leg(
					JsonRpcHttpClientTransport.create(eventloop, httpClient, "http://127.0.0.1:" + httpPort + "/"));
				return parity(tcp, http)
					.then(() -> run(tcp, report.warmup))
					.then(() -> run(http, report.warmup))
					.then(() -> measureSegments(tcp, http, report))
					.whenComplete(($, e) -> {
						tcp.transport.closeEx(new AsyncCloseException("end of harness"));
						http.transport.closeEx(new AsyncCloseException("end of harness"));
					});
			})
			.whenComplete(($, e) -> {
				report.failure = e;
				tcpServer.close();
				httpServer.close();
				httpClient.stop();
			}));

		eventloop.run();

		if (report.failure != null) throw report.failure;
		report.print();
	}

	private static void usage() {
		System.err.println("usage: TransportOverheadHarness [segments] [segmentRounds] [warmup]  " +
						   "(defaults 13 5000 5000)");
	}

	// -------------------------------------------------------------------------------------------
	// The parity self-check: refuse to measure two things that are not the same thing.
	// -------------------------------------------------------------------------------------------

	/**
	 * One round trip on each leg before any timing, asserting the two response documents are
	 * byte-identical. They must be: the same request document reaches the same dispatcher over the
	 * same service, and neither transport is allowed to touch a document's bytes.
	 */
	private static Promise<Void> parity(Leg tcp, Leg http) {
		return tcp.roundTrip()
			.then(tcpAnswer -> http.roundTrip()
				.then(httpAnswer -> Arrays.equals(tcpAnswer, httpAnswer) ?
					Promise.complete() :
					Promise.ofException(new IllegalStateException(
						"the two transports answered the same request differently — there is no " +
						"common denominator to measure:\n  TCP : " + new String(tcpAnswer, US_ASCII) +
						"\n  HTTP: " + new String(httpAnswer, US_ASCII)))));
	}

	// -------------------------------------------------------------------------------------------
	// Measurement.
	// -------------------------------------------------------------------------------------------

	/** The alternating segment loop; fills the report's three arrays. */
	private static Promise<Void> measureSegments(Leg tcp, Leg http, Report report) {
		return Promises.loop(0,
				s -> s < report.segments,
				s -> {
					// the order flips every segment, so a slow drift cancels across the run
					Promise<Void> segment = (s & 1) == 0 ?
						measure(tcp, report.segmentRounds)
							.whenResult(ns -> report.tcpNs[s] = ns)
							.then(() -> measure(http, report.segmentRounds))
							.whenResult(ns -> report.httpNs[s] = ns)
							.toVoid() :
						measure(http, report.segmentRounds)
							.whenResult(ns -> report.httpNs[s] = ns)
							.then(() -> measure(tcp, report.segmentRounds))
							.whenResult(ns -> report.tcpNs[s] = ns)
							.toVoid();
					return segment
						.whenResult(() -> report.ratios[s] = report.tcpNs[s] / report.httpNs[s])
						.map($ -> s + 1);
				})
			.toVoid();
	}

	/** One timed segment: {@code rounds} round trips on one leg, returned as ns/op. */
	private static Promise<Double> measure(Leg leg, int rounds) {
		long start = System.nanoTime();
		return run(leg, rounds)
			.map($ -> (double) (System.nanoTime() - start) / rounds);
	}

	/** {@code rounds} sequential round trips: send the request, await the answer, consume it. */
	private static Promise<Void> run(Leg leg, int rounds) {
		return Promises.loop(0,
				i -> i < rounds,
				i -> leg.roundTrip().map(answer -> {
					documentSink += answer.length;      // anti-DCE: the answer is read, then printed
					return i + 1;
				}))
			.toVoid();
	}

	// -------------------------------------------------------------------------------------------
	// One measured leg: a transport plus a one-outstanding-call correlation slot.
	// -------------------------------------------------------------------------------------------

	/**
	 * A transport driven at the SPI level rather than through a {@code JsonRpcClient} proxy: the
	 * proxy layer is identical on both legs, so measuring through it would add the same constant to
	 * both figures and dilute the ratio. Exactly one call is outstanding at a time, so a single
	 * pending slot is the whole correlation this needs.
	 */
	private static final class Leg {
		private final JsonRpcTransport transport;
		private @Nullable SettablePromise<byte[]> pending;

		private Leg(JsonRpcTransport transport) {
			this.transport = transport;
			transport.setListener(new JsonRpcTransport.Listener() {
				@Override
				public void onDocument(byte[] document) {
					SettablePromise<byte[]> answer = pending;
					pending = null;
					if (answer != null) answer.set(document);
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					SettablePromise<byte[]> answer = pending;
					pending = null;
					if (answer != null) {
						answer.setException(e != null ? e : new AsyncCloseException("transport closed"));
					}
				}
			});
		}

		/**
		 * Send the request document, then resolve with the answer document. The pending slot is armed
		 * <b>before</b> the send, because the HTTP transport may deliver the answer before its
		 * {@code send} promise completes (SPI obligation 4) while the TCP transport completes
		 * {@code send} at the write, long before the answer arrives.
		 */
		private Promise<byte[]> roundTrip() {
			SettablePromise<byte[]> answer = new SettablePromise<>();
			pending = answer;
			return transport.send(REQUEST).then(() -> answer);
		}
	}

	// -------------------------------------------------------------------------------------------
	// The report.
	// -------------------------------------------------------------------------------------------

	/** Everything the run collected, printed after the reactor has stopped. */
	private static final class Report {
		private final int segments;
		private final int segmentRounds;
		private final int warmup;
		private final int tcpPort;
		private final int httpPort;
		private final double[] tcpNs;
		private final double[] httpNs;
		private final double[] ratios;
		private @Nullable Exception failure;

		private Report(int segments, int segmentRounds, int warmup, int tcpPort, int httpPort) {
			this.segments = segments;
			this.segmentRounds = segmentRounds;
			this.warmup = warmup;
			this.tcpPort = tcpPort;
			this.httpPort = httpPort;
			this.tcpNs = new double[segments];
			this.httpNs = new double[segments];
			this.ratios = new double[segments];
		}

		private void print() {
			double tcpMedian = median(tcpNs);
			double httpMedian = median(httpNs);
			double tcpMin = Arrays.stream(tcpNs).min().orElseThrow();
			double tcpMax = Arrays.stream(tcpNs).max().orElseThrow();
			double httpMin = Arrays.stream(httpNs).min().orElseThrow();
			double httpMax = Arrays.stream(httpNs).max().orElseThrow();
			double ratioMedian = median(ratios);
			double ratioMin = Arrays.stream(ratios).min().orElseThrow();
			double ratioMax = Arrays.stream(ratios).max().orElseThrow();
			long tcpFasterSegments = Arrays.stream(ratios).filter(r -> r < 1).count();
			boolean straddles = tcpMin <= httpMax && httpMin <= tcpMax;

			System.out.println("=== JSON-RPC transport overhead: framed TCP vs HTTP POST, same dispatcher ===");
			System.out.println("request shape : " + new String(REQUEST, US_ASCII));
			System.out.println("environment   : " + System.getProperty("java.vm.name") + " " +
							   System.getProperty("java.version") + " / " + System.getProperty("os.name") + " " +
							   System.getProperty("os.arch") + " / " +
							   Runtime.getRuntime().availableProcessors() + " cpus");
			System.out.println("loopback ports: TCP :" + tcpPort + ", HTTP :" + httpPort +
							   "   (one reactor thread carries both peers of both legs)");
			System.out.println("HTTP keep-alive: " + HTTP_KEEP_ALIVE + " (client default is 0, raised so both " +
							   "legs ride one connection)");
			System.out.println("segments      : " + segments + " x " + segmentRounds +
							   " round trips, warmup: " + warmup + " round trips per leg");
			System.out.printf("TCP  : median %10.1f ns/op   spread [%.1f, %.1f] ns/op (%d segments)%n",
				tcpMedian, tcpMin, tcpMax, segments);
			System.out.printf("HTTP : median %10.1f ns/op   spread [%.1f, %.1f] ns/op (%d segments)%n",
				httpMedian, httpMin, httpMax, segments);
			System.out.printf("ratio (TCP / HTTP), median of per-segment ratios: %.3f  ->  " +
							  "HTTP costs ~ +%.1f%% per round trip   (ratio spread [%.3f, %.3f])%n",
				ratioMedian, (1 / ratioMedian - 1) * 100, ratioMin, ratioMax);
			System.out.printf("absolute bands (ns/op): TCP [%.1f, %.1f] vs HTTP [%.1f, %.1f]  ->  %s%n",
				tcpMin, tcpMax, httpMin, httpMax,
				straddles ? "bands straddle: indistinguishable at this precision" : "bands do not overlap");
			System.out.println("TCP faster than HTTP in " + tcpFasterSegments + "/" + segments + " segments");
			System.out.println("answer bytes consumed across all rounds (anti-DCE): " + documentSink);
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
}
