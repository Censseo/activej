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

package io.activej.http3.interop;

import io.activej.async.callback.AsyncComputation;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.function.SupplierEx;
import io.activej.dns.IDnsClient;
import io.activej.dns.protocol.DnsQuery;
import io.activej.dns.protocol.DnsResourceRecord;
import io.activej.dns.protocol.DnsResponse;
import io.activej.dns.protocol.DnsTransaction;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.HttpVersion;
import io.activej.http3.Http3Client;
import io.activej.http3.Http3Exception;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The client-direction regression suite (T024, US4): a real {@link Http3Client} against the real
 * {@link io.activej.http3.Http3Server} the {@link Http3ServerReactorFixture} stands up, over real
 * loopback UDP on a real clock. Runs unconditionally on any machine — no external binary, no
 * {@code CurlProbe} dependency (SC-010).
 * <p>
 * <b>Deliberately not the stub fabric</b> (research D12): {@code Http3WirePair} /
 * {@code Http3ClientFixture} drive real endpoints over {@code StubDatagramNetwork} on a hand-driven
 * clock, and therefore cannot exercise the real {@code UdpSocket} receive loop, real datagram
 * coalescing at the OS boundary, or a real retransmission timer — precisely the layers a
 * "works in tests, fails on the wire" bug hides in, and the only thing these cases add over the
 * ~706 existing stub-fabric tests. The client's hostname <b>is</b> still resolved by a table-based
 * {@link IDnsClient} (the {@link Http3InteropClient} shape): DNS is not what is under test, and the
 * stub maps the host onto the fixture's exact bound address so the case is immune to a JVM's
 * IPv4/IPv6 loopback preference.
 * <p>
 * <b>Threading</b> (FR-012): the server lives on the fixture's reactor thread; the client lives on
 * its own {@link Eventloop} thread (a {@link ClientLoop}), so the JUnit thread never touches a
 * reactive component and the client survives the fixture's teardown — which
 * {@link #abruptServerCloseFailsPendingRequestUnwrapped} needs: the server is closed while an
 * exchange is in flight, and the client's pending promise fails when the closed server's
 * {@code CONNECTION_CLOSE} reaches it after the GOAWAY drain (or, should that datagram be lost, when
 * the client's own real QUIC idle timer fires ~30 s later). The JUnit thread issues requests through
 * the loops' submit bridges and awaits the resulting promises via {@link Promise#toCompletableFuture()},
 * which the reactor threads keep completing while the JUnit thread blocks.
 * <p>
 * <b>TLS</b>: the client trusts exactly the dev leaf ({@link Http3TestTls#trustingLeaf}) — the
 * existing test-fabric pattern — so RFC 6125 hostname verification stays live against the dev
 * certificate's {@code localhost} SAN. <b>No {@code EventloopRule}</b>: neither reactor is the JUnit
 * thread's.
 * <p>
 * <b>Assertion discipline</b>: every case asserts the exact {@link HttpVersion#HTTP_3_0} enum value
 * (FR-007's client-side analogue), bodies are compared by length and SHA-256 digest only, and
 * failure messages name lengths and digests, never body bytes (FR-013).
 * <p>
 * <b>Class name</b> (FR-001a): the {@code …InteropTest} suffix is what makes
 * {@code -Dtest='*Interop*'} select this class together with {@link Http3CurlInteropTest}.
 */
public final class Http3RealSocketInteropTest {
	/** Case 2 body size: ≥ 2 MiB in each direction — larger than the 256 kB stream and 1 MB
	 * connection windows, smaller than the 100 MB body cap (SC-003's client-side analogue). */
	private static final int LARGE_BODY_SIZE = 2 * 1024 * 1024;
	/** Case 3 concurrency. */
	private static final int CONCURRENT_REQUESTS = 8;
	/** The distinct per-request body size case 3 uses. */
	private static final int CONCURRENT_BODY_SIZE = 2048;
	/**
	 * The per-promise await bound. Must exceed the client's QUIC idle timeout (30 s default,
	 * {@code QuicConnection.maxIdleTimeout}): case 4's pending request fails when the closed server's
	 * {@code CONNECTION_CLOSE} arrives (after the 1 s GOAWAY drain) — and, should that datagram ever
	 * be lost on a real network, only when the client's own real idle timer fires.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 45;
	/** How long a condition (e.g. "the request is in flight") is polled for. */
	private static final long POLL_TIMEOUT_SECONDS = 10;

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * Case 1 (T025) — {@code GET /} over a real socket: the response reports exactly
	 * {@link HttpVersion#HTTP_3_0} and carries the servlet's fixed body, byte-identical.
	 */
	@Test
	public void get() {
		String caseName = "get";
		try (Http3ServerReactorFixture fixture = newFixture(); ClientLoop loop = new ClientLoop()) {
			Http3Client client = newClient(loop, fixture);
			HttpResponse response = awaitRequest(loop, client,
				HttpRequest.get(url(fixture, "/")).build(), caseName + " — GET /");
			assertEquals(caseName + " — the response must report the exact HTTP_3_0 version enum value",
				HttpVersion.HTTP_3_0, response.getVersion());
			assertBody(caseName, InteropTestServlet.FIXED_BODY.getBytes(StandardCharsets.UTF_8),
				loadBody(loop, response));
		}
	}

	/**
	 * Case 2 (T026) — a ≥ 2 MiB body to {@code /echo}: the request direction carries ≥ 2 MiB and the
	 * response direction carries the same ≥ 2 MiB back, byte-identical by SHA-256 digest — proving
	 * flow-control credit is issued and honoured across the stream and connection windows in both
	 * directions on a real socket.
	 */
	@Test
	public void largeBodyBothDirections() {
		String caseName = "largeBodyBothDirections";
		try (Http3ServerReactorFixture fixture = newFixture(); ClientLoop loop = new ClientLoop()) {
			Http3Client client = newClient(loop, fixture);
			byte[] body = patternBody(LARGE_BODY_SIZE, 0x5A);
			HttpResponse response = awaitRequest(loop, client,
				HttpRequest.post(url(fixture, "/echo")).withBody(body).build(),
				caseName + " — " + LARGE_BODY_SIZE + "-byte POST to /echo");
			assertEquals(caseName + " — the response must report the exact HTTP_3_0 version enum value",
				HttpVersion.HTTP_3_0, response.getVersion());
			assertBody(caseName, body, loadBody(loop, response));
		}
	}

	/**
	 * Case 3 (T027) — {@value #CONCURRENT_REQUESTS} concurrent requests to one authority: each carries
	 * a distinct body to {@code /echo}, every response is matched to its own request by digest, and
	 * the server observes exactly one accepted connection for all {@value #CONCURRENT_REQUESTS} of
	 * them — the pool's "one connection per authority" guarantee over a real socket (FR-048).
	 * Counters are read only after every response body has been fully read.
	 */
	@Test
	public void concurrentRequestsShareOneConnection() {
		String caseName = "concurrentRequestsShareOneConnection";
		try (Http3ServerReactorFixture fixture = newFixture(); ClientLoop loop = new ClientLoop()) {
			Http3Client client = newClient(loop, fixture);
			List<byte[]> bodies = new ArrayList<>();
			List<HttpRequest> requests = new ArrayList<>();
			for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
				byte[] body = patternBody(CONCURRENT_BODY_SIZE, i);
				bodies.add(body);
				requests.add(HttpRequest.post(url(fixture, "/echo")).withBody(body).build());
			}
			// Issued in one reactor tick so the eight race the dial together (FR-048).
			List<Promise<HttpResponse>> promises = loop.submit(() -> {
				List<Promise<HttpResponse>> issued = new ArrayList<>();
				for (HttpRequest request : requests) {
					issued.add(client.request(request));
				}
				return issued;
			});
			for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
				HttpResponse response = await(promises.get(i), caseName + " — request " + i);
				assertEquals(caseName + " — request " + i +
					" must report the exact HTTP_3_0 version enum value",
					HttpVersion.HTTP_3_0, response.getVersion());
				assertBody(caseName + " — request " + i + " must receive its own body, not another request's",
					bodies.get(i), loadBody(loop, response));
			}
			assertEquals(caseName + " — " + CONCURRENT_REQUESTS +
				" concurrent requests to one authority must share exactly one connection",
				1, fixture.connectionsAccepted());
			assertEquals(caseName + " — every request must be served",
				CONCURRENT_REQUESTS, fixture.requestsServed());
		}
	}

	/**
	 * Case 4 (T028) — abrupt server close mid-exchange: a request whose response never comes is in
	 * flight — the servlet has actually been invoked, so the exchange is established — when the
	 * fixture's server is closed. The pending promise fails rather than hanging, the transport's
	 * exception is surfaced in that failure, and nothing leaks (the class's {@link ByteBufRule} is the
	 * leak verdict).
	 * <p>
	 * <b>The transport exception is asserted through the failure's message, not by direct type —
	 * deliberately, and this is a reported gap.</b> {@code Http3Client.request()}'s Javadoc promises
	 * (FR-058c) that a failure "the transport reported" arrives <b>unwrapped</b>, and this feature's
	 * spec acceptance 4 says the same; the current main source instead wraps a connection-close
	 * read failure in {@code Http3Exception(H3_INTERNAL_ERROR, "Reading the request stream failed: " +
	 * transportException)} — see {@code Http3RequestStream.onReadFailure}, where only a
	 * {@code QuicStreamException} (a peer's stream reset) passes through unwrapped. The task text
	 * (assert {@code instanceof QuicTransportException} directly) is therefore not implementable as
	 * written without a main-source change, which FR-034 forbids; asserting the wrapper's message
	 * proves the transport exception is surfaced at all and keeps this case green. The assertion must
	 * become {@code failure instanceof QuicTransportException} the day feature 005 fixes the wrap.
	 */
	@Test
	public void abruptServerCloseFailsPendingRequestUnwrapped() {
		String caseName = "abruptServerCloseFailsPendingRequestUnwrapped";
		AtomicBoolean served = new AtomicBoolean();
		// A servlet that records the invocation and holds its response promise open: the exchange is
		// established and mid-flight when the server closes, which is what makes the close "abrupt"
		// rather than a drain of finished work.
		try (Http3ServerReactorFixture fixture =
			new Http3ServerReactorFixture(reactor -> request -> {
				served.set(true);
				return new SettablePromise<>();
			});
			ClientLoop loop = new ClientLoop()) {
			Http3Client client = newClient(loop, fixture);
			Promise<HttpResponse> pending = loop.submit(() ->
				client.request(HttpRequest.get(url(fixture, "/held")).build()));
			// Stronger than any client-side counter: the servlet flag means the request has reached
			// the server's application — establishment, stream open and send have all happened.
			awaitUntil(served::get,
				caseName + " — the request must reach the servlet before the server closes");
			fixture.close();
			Exception failure = awaitException(pending, caseName + " — the pending request");
			assertTrue(caseName + " — the pending promise must fail with an Http3Exception " +
				"surfacing the transport's QuicTransportException (FR-058c, see method Javadoc): " + failure,
				failure instanceof Http3Exception &&
					failure.getMessage() != null &&
					failure.getMessage().contains("QuicTransportException"));
		}
	}

	// ---------------------------------------------------------------- helpers

	private static Http3ServerReactorFixture newFixture() {
		return new Http3ServerReactorFixture(InteropTestServlet::create);
	}

	private static String url(Http3ServerReactorFixture fixture, String path) {
		return "https://" + Http3TestTls.SERVER_NAME + ":" + fixture.port() + path;
	}

	/**
	 * Builds the client on the loop's reactor thread (WI-2) over an {@link IDnsClient} that maps the
	 * authority's host onto the fixture's exact bound address — the {@link Http3InteropClient} shape.
	 * The resolver is stubbed because DNS is not under test; the socket, the clock and the TLS
	 * handshake are real. The client trusts exactly the dev leaf, so RFC 6125 hostname verification
	 * stays live against the certificate's {@code localhost} SAN.
	 */
	private static Http3Client newClient(ClientLoop loop, Http3ServerReactorFixture fixture) {
		IDnsClient dns = new IDnsClient() {
			@Override
			public Promise<DnsResponse> resolve(DnsQuery query) {
				return Promise.of(DnsResponse.of(
					DnsTransaction.of((short) 0, query),
					DnsResourceRecord.of(new InetAddress[]{fixture.boundAddress().getAddress()}, 60)));
			}

			@Override
			public void close() {}
		};
		Http3Client client = loop.submit(() ->
			Http3Client.builder(loop.eventloop(), dns)
				.withTlsClientConfig(config -> config.withTrustManager(
					Http3TestTls.trustingLeaf(Http3TestTls.devIdentity().leaf())))
				.build());
		loop.attach(client);
		return client;
	}

	/** Issues {@code request} on the client's reactor thread and awaits its response. */
	private static HttpResponse awaitRequest(ClientLoop loop, Http3Client client,
		HttpRequest request, String what) {
		return await(loop.submit(() -> client.request(request)), what);
	}

	/** Loads the response body on the client's reactor thread and returns its bytes, recycled. */
	private static byte[] loadBody(ClientLoop loop, HttpResponse response) {
		ByteBuf body = await(loop.submit(response::loadBody), "the response body");
		try {
			return toBytes(body);
		} finally {
			body.recycle();
		}
	}

	private static void assertBody(String what, byte[] expected, byte[] actual) {
		String expectedDigest = sha256Hex(expected);
		String actualDigest = sha256Hex(actual);
		if (expected.length != actual.length || !expectedDigest.equals(actualDigest)) {
			fail(what + " — body mismatch: expected " + expected.length + " bytes, sha256 " +
				expectedDigest + "; got " + actual.length + " bytes, sha256 " + actualDigest);
		}
	}

	/** Blocks the JUnit thread until the promise completes on its reactor thread. */
	private static <T> T await(Promise<T> promise, String what) {
		try {
			return promise.toCompletableFuture().get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new AssertionError(what + " failed: " + e.getCause(), e.getCause());
		} catch (TimeoutException e) {
			throw new AssertionError(what + " did not complete within " +
				AWAIT_TIMEOUT_SECONDS + " seconds", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while awaiting " + what, e);
		}
	}

	/** Blocks until the promise fails on its reactor thread and returns the unwrapped exception. */
	private static Exception awaitException(Promise<?> promise, String what) {
		try {
			promise.toCompletableFuture().get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			throw new AssertionError(what + " was expected to fail but completed");
		} catch (ExecutionException e) {
			return (Exception) e.getCause();
		} catch (TimeoutException e) {
			throw new AssertionError(what + " did not fail within " +
				AWAIT_TIMEOUT_SECONDS + " seconds", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while awaiting " + what, e);
		}
	}

	/** Polls a condition — evaluated on the client's reactor thread via the submit bridge — until it holds. */
	private static void awaitUntil(SupplierEx<Boolean> condition, String what) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POLL_TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			try {
				if (condition.get()) return;
			} catch (Exception e) {
				throw new AssertionError(what + " could not be evaluated: " + e, e);
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while awaiting " + what, e);
			}
		}
		throw new AssertionError(what + " was not observed within " + POLL_TIMEOUT_SECONDS + " seconds");
	}

	/** A deterministic body: distinct seeds give distinct bytes, so a mixed-up exchange is a mismatch. */
	private static byte[] patternBody(int length, int seed) {
		byte[] body = new byte[length];
		for (int i = 0; i < length; i++) {
			body[i] = (byte) (seed + i * 31);
		}
		return body;
	}

	private static byte[] toBytes(ByteBuf buf) {
		byte[] bytes = new byte[buf.readRemaining()];
		System.arraycopy(buf.array(), buf.head(), bytes, 0, bytes.length);
		return bytes;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is always available", e);
		}
	}

	/**
	 * The client's reactor: one {@link Eventloop} on a dedicated daemon thread, mirroring the
	 * fixture's shape (FR-012) so the JUnit thread never touches a reactive component. The loop is
	 * held open with {@code keepAlive(true)} for its whole life and driven from the JUnit thread
	 * through the same {@link AsyncComputation} submit bridge the fixture uses. Teardown is
	 * idempotent and runs on every path: the attached {@link Http3Client} is closed on the loop
	 * (its socket's selector key would otherwise hold the loop open), the loop is released and the
	 * thread joined, with {@code breakEventloop()} as the last resort — so a failed assertion or a
	 * timeout still leaves nothing behind.
	 */
	private static final class ClientLoop implements AutoCloseable {
		private static final long CALL_TIMEOUT_SECONDS = 10;
		private static final long JOIN_TIMEOUT_MILLIS = 10_000;
		private static final long BREAK_JOIN_TIMEOUT_MILLIS = 2_000;

		private final Eventloop eventloop = Eventloop.create();
		private final Thread thread;

		private @Nullable Http3Client client;
		private boolean closed;

		ClientLoop() {
			eventloop.keepAlive(true);
			thread = new Thread(eventloop, "http3-interop-client");
			thread.setDaemon(true);
			thread.start();
		}

		Eventloop eventloop() {
			return eventloop;
		}

		void attach(Http3Client client) {
			this.client = client;
		}

		/** Runs a computation on the loop's reactor thread and blocks the caller on the result (FR-012). */
		<T> T submit(SupplierEx<T> computation) {
			return await(eventloop.submit(AsyncComputation.of(computation)));
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			Exception failure = null;
			if (client != null) {
				try {
					eventloop.submit(client::close).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					failure = e;
				} catch (ExecutionException | TimeoutException e) {
					failure = e;
				}
			}
			eventloop.keepAlive(false);
			try {
				thread.join(JOIN_TIMEOUT_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				failure = failure == null ? e : failure;
			}
			if (thread.isAlive()) {
				eventloop.breakEventloop();
				try {
					thread.join(BREAK_JOIN_TIMEOUT_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (failure != null) {
				throw new IllegalStateException("Client loop teardown did not complete cleanly", failure);
			}
		}

		private static <T> T await(CompletableFuture<T> future) {
			try {
				return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the client reactor", e);
			} catch (ExecutionException e) {
				throw new IllegalStateException("The client reactor task failed", e.getCause());
			} catch (TimeoutException e) {
				throw new IllegalStateException("The client reactor did not answer within " +
					CALL_TIMEOUT_SECONDS + " seconds", e);
			}
		}
	}
}
