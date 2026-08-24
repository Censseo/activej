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

package io.activej.jsonrpc.transport.tcp;

import io.activej.async.exception.AsyncTimeoutException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.fixtures.SlowApi;
import io.activej.jsonrpc.service.fixtures.SlowApiImpl;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * FR-015's <b>transport tier</b>: a response arriving after its call's deadline has expired must be consumed
 * and recycled like any other, and reach nobody.
 *
 * <h2>Why this test is here and not in {@code cloud-jsonrpc}</h2>
 * The core module's {@code JsonRpcTimeoutTest} proves the <i>semantics</i> — no exception, no failure-handler
 * call, no entry created — but its in-memory transport owns no buffers at all: the envelope path is
 * {@code byte[]}-based, so there is nothing there for {@link ByteBufRule} to catch. Only a real transport
 * accumulates a pooled {@code ByteBuf}, scans it for the LF, copies the content out and recycles it. If any
 * of that were conditional on somebody still wanting the answer, this is the suite that would go red — and
 * framed TCP is the transport where the whole accumulate/copy/recycle cycle is this module's own code rather
 * than {@code core-http}'s.
 *
 * <h2>The shape of the exercise</h2>
 * A real {@link JsonRpcTcpServer} on port {@code 0} behind {@link SlowApiImpl}, whose every invocation stays
 * pending until the test releases it; a real {@link JsonRpcTcpTransport} dialled into it; a real
 * {@link JsonRpcClient} with a short {@code callTimeout}. The call expires while the handler is still
 * holding, and only <b>then</b> is the handler released — so the answer is genuinely computed and written
 * after the correlation entry is gone, which a pre-built "late document" could not simulate (its {@code id}
 * would be the test's guess rather than the client's choice).
 * <p>
 * {@link ObservingTransport} wraps the real transport so the test can await the late document's <i>arrival</i>
 * rather than sleep for it: it records each inbound document, hands it to the client unchanged, and only then
 * releases whoever is waiting. Nothing about the buffer lifecycle passes through it — that all happens inside
 * {@link JsonRpcTcpTransport}, below the {@code byte[]} the SPI hands up.
 *
 * <h2>Nothing is asserted inside the promise chain</h2>
 * Deliberate, and this module's guard rail is the reason: an open socket keeps the eventloop alive, so an
 * assertion that throws from inside a chain skips the close and <b>hangs</b> the suite instead of failing it
 * (ADR-040). Every observation is recorded into a ref, every stage is bounded by {@link #GUARD}, the close
 * hangs off a {@code toTry()} that cannot fail, and the assertions run after {@code await} has returned.
 */
public final class JsonRpcTcpTimeoutLeakTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** Long enough for the request to reach the handler over loopback, short enough to keep the suite fast. */
	private static final Duration CALL_TIMEOUT = Duration.ofMillis(50);

	/** The harness's own upper bound — never the thing under test. */
	private static final Duration GUARD = Duration.ofSeconds(5);

	@Test
	public void aLateAnswerAfterExpiryIsConsumedAndRecycledAndReachesNobody() {
		SlowApiImpl service = new SlowApiImpl();
		JsonRpcTcpServer server = server(service);
		List<Exception> failures = new ArrayList<>();
		Ref<Exception> expiry = new Ref<>();
		RefInt inFlightAfterExpiry = new RefInt(-1);
		RefInt heldByTheServer = new RefInt(-1);
		List<byte[]> delivered;

		try {
			ObservingTransport observing = await(JsonRpcTcpTransport.connect(reactor(), boundAddress(server))
				.then(tcp -> {
					ObservingTransport wrapper = new ObservingTransport(tcp);
					JsonRpcClient client = JsonRpcClient.builder(reactor(), wrapper)
						.withCallTimeout(CALL_TIMEOUT)
						.withFailureHandler(failures::add)
						.build();
					SlowApi api = client.proxy(SlowApi.class);

					return Promises.timeout(GUARD, api.call("late")).toTry()
						.then(answer -> {
							expiry.set(answer.getException());
							inFlightAfterExpiry.set(client.inFlightCount());
							// the expiry and the request's arrival at the handler are independent events —
							// on a cold JVM the first exchange of the run can easily take longer than the
							// deadline — so arrival is waited for rather than assumed. What the test needs
							// is only the ORDER: release strictly after expiry
							return Promises.timeout(GUARD, untilEntered(service, 1));
						})
						.then(() -> {
							heldByTheServer.set(service.pendingCount());
							// only now does the answer exist: it is written to a socket nobody is
							// correlating against any more
							service.releaseAll();
							return Promises.timeout(GUARD, wrapper.whenDocumentsDelivered(1));
						})
						.toTry()
						.whenResult(() -> client.closeEx(new ExpectedException("end of test")))
						.map($ -> wrapper);
				}));
			delivered = observing.delivered();
		} finally {
			closeServer(server);
		}

		assertThat(expiry.get(), instanceOf(AsyncTimeoutException.class));
		assertTrue("the wire name must be named: " + expiry.get().getMessage(),
			expiry.get().getMessage().contains("slow.call"));
		assertEquals("the entry was gone before the answer was even computed", 0, inFlightAfterExpiry.get());
		assertEquals("and the handler was still holding it", 1, heldByTheServer.get());
		assertTrue("a late answer is not a failure and has no handler to reach", failures.isEmpty());
		assertEquals("exactly one document came back", 1, delivered.size());
		assertTrue("and it really was the answer, not an error: " + text(delivered.get(0)),
			text(delivered.get(0)).contains("\"result\":\"late\""));
		// ByteBufRule is the other half of this assertion: the accumulation buffer that carried the late
		// answer through JsonRpcTcpTransport was recycled although nothing wanted its content
	}

	@Test
	public void twoLateAnswersOnOneConnectionAreBothConsumedAndRecycled() {
		SlowApiImpl service = new SlowApiImpl();
		JsonRpcTcpServer server = server(service);
		List<Exception> failures = new ArrayList<>();
		RefInt inFlightAfterExpiry = new RefInt(-1);
		RefInt heldByTheServer = new RefInt(-1);
		List<byte[]> delivered;

		try {
			ObservingTransport observing = await(JsonRpcTcpTransport.connect(reactor(), boundAddress(server))
				.then(tcp -> {
					ObservingTransport wrapper = new ObservingTransport(tcp);
					JsonRpcClient client = JsonRpcClient.builder(reactor(), wrapper)
						.withCallTimeout(CALL_TIMEOUT)
						.withFailureHandler(failures::add)
						.build();
					SlowApi api = client.proxy(SlowApi.class);

					// two calls, two ids, two deadlines — all on one persistent connection, which is the
					// case a request/response transport cannot produce
					Promise<String> first = api.call("one");
					Promise<String> second = api.call("two");
					return Promises.timeout(GUARD, Promises.all(first.toTry(), second.toTry())).toTry()
						.then($ -> {
							inFlightAfterExpiry.set(client.inFlightCount());
							return Promises.timeout(GUARD, untilEntered(service, 2));
						})
						.then(() -> {
							heldByTheServer.set(service.pendingCount());
							service.releaseAll();
							return Promises.timeout(GUARD, wrapper.whenDocumentsDelivered(2));
						})
						.toTry()
						.whenResult(() -> client.closeEx(new ExpectedException("end of test")))
						.map($ -> wrapper);
				}));
			delivered = observing.delivered();
		} finally {
			closeServer(server);
		}

		assertEquals(0, inFlightAfterExpiry.get());
		assertEquals(2, heldByTheServer.get());
		assertEquals("both answers came back", 2, delivered.size());
		assertTrue(failures.isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// Harness.
	// -------------------------------------------------------------------------------------------

	/**
	 * Records every inbound document, hands it to the real listener <b>first</b> so the client processes it
	 * exactly as it otherwise would, and only then releases whoever is waiting for it — so a test's own
	 * continuation can never run before the subject has seen the document.
	 * <p>
	 * It touches no buffer: the SPI's currency is a contiguous {@code byte[]}, and everything pooled lives
	 * inside {@link JsonRpcTcpTransport}, below this wrapper.
	 */
	private static final class ObservingTransport implements JsonRpcTransport {
		private final JsonRpcTransport delegate;
		private final List<byte[]> delivered = new ArrayList<>();

		private int awaited = -1;
		private @Nullable SettablePromise<Void> waiting;

		private ObservingTransport(JsonRpcTransport delegate) {
			this.delegate = delegate;
		}

		List<byte[]> delivered() {
			return delivered;
		}

		Promise<Void> whenDocumentsDelivered(int count) {
			if (delivered.size() >= count) return Promise.complete();
			awaited = count;
			SettablePromise<Void> pending = new SettablePromise<>();
			waiting = pending;
			return pending;
		}

		@Override
		public Promise<Void> send(byte[] document) {
			return delegate.send(document);
		}

		@Override
		public void setListener(Listener listener) {
			delegate.setListener(new Listener() {
				@Override
				public void onDocument(byte[] document) {
					delivered.add(document);
					listener.onDocument(document);
					SettablePromise<Void> pending = waiting;
					if (pending != null && delivered.size() >= awaited) {
						waiting = null;
						pending.set(null);
					}
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					listener.onClosed(e);
				}
			});
		}

		@Override
		public void closeEx(Exception e) {
			delegate.closeEx(e);
		}
	}

	/**
	 * Completes once {@code count} invocations have entered {@link SlowApiImpl} and are being held.
	 * <p>
	 * A poll rather than a hook, because {@code SlowApiImpl} is the shared fixture and adding an
	 * arrival callback for one transport's test would be API for one caller. Bounded by {@link #GUARD} at
	 * every call site, so a request that never arrives fails the test instead of spinning.
	 */
	private static Promise<Void> untilEntered(SlowApiImpl service, int count) {
		if (service.pendingCount() >= count) return Promise.complete();
		return Promises.delay(Duration.ofMillis(5)).then(() -> untilEntered(service, count));
	}

	/** A listening server on port {@code 0}, accepting once, dispatching to {@code service}. */
	private static JsonRpcTcpServer server(SlowApi service) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(SlowApi.class, service)
			.build();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), dispatcher)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	private static void closeServer(JsonRpcTcpServer server) {
		await(server.close().toVoid());
	}

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static String text(byte[] document) {
		return new String(document, UTF_8);
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
