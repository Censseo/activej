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

import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.jsonrpc.JsonRpcBatch;
import io.activej.jsonrpc.JsonRpcDecoded;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcId;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcResponse;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * User story 1 (T010): a service is called over <b>one persistent framed-TCP connection</b> — the same
 * annotated interface as on HTTP and WebSocket, a real {@link JsonRpcTcpServer} bound to port {@code 0}
 * and asked where it landed (ADR-028), and a real {@link JsonRpcTcpTransport} dialled into it.
 * <p>
 * The four scenarios, and what each is actually asserting:
 * <ol>
 *     <li><b>Sequential calls.</b> Call, await, call again — <i>on the same connection</i>. The point is
 *     not that two calls work; it is that the second reuses the first's connection, which is the whole
 *     difference between this transport and the HTTP one (there is no per-call exchange to open).</li>
 *     <li><b>Three concurrent calls, correlated by {@code id} alone.</b> Issued with no await between
 *     them, and answered by the server in <b>reverse</b> order — the service defers all three, then
 *     releases them last-first, so the answers are written to the wire in an order the requests were
 *     never sent in. Every promise must still resolve with <i>its own</i> answer. Nothing but the
 *     JSON-RPC {@code id} can produce that, which is exactly the property a persistent duplex
 *     connection needs and a request/response transport gets for free.</li>
 *     <li><b>A notification produces zero response bytes.</b> Asserted on the wire rather than through a
 *     proxy: a raw transport sends the notification, then a plain call, and awaits the call's answer.
 *     Per-direction order is append order (FR-023), so an answer to the notification could only have
 *     arrived <i>before</i> the call's — and exactly one document arrives. Silence is observable on a
 *     persistent connection in a way it is not over HTTP, where "no answer" is a {@code 204}.</li>
 *     <li><b>A batch is answered as one message.</b> Batching is not a client-proxy concept in this
 *     codebase — {@code JsonRpcClient} exposes none, and a batch is simply a document whose top level is
 *     a JSON array ({@link JsonRpcBatch}). So the batch is written as one line by a raw transport, and the
 *     assertion is that <b>one</b> line comes back carrying <b>one</b> array with one member per
 *     non-notification element, each correlated by its own {@code id}.</li>
 * </ol>
 * <b>Quiescence.</b> {@code TestUtils.await} runs the loop until nothing is left to do and
 * {@code Eventloop.isAlive()} counts selector keys, so every server here is {@code withAcceptOnce()} and
 * every connection is closed inside the awaited chain — the persistent-transport shape feature 015
 * established. A test that leaves a socket open hangs the suite rather than failing it.
 */
public final class JsonRpcTcpRoundTripTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	// -------------------------------------------------------------------------------------------
	// Scenario 1: sequential calls on one connection.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSequentialCallsShareOnePersistentConnection() {
		// US1 scenario 1: call, await, call again — over one connection, with one session on the server
		// for the whole exchange. The second call opens nothing: that is the transport's headline
		// difference from JSON-RPC over HTTP POST, where every call is its own exchange.
		JsonRpcTcpServer server = server(new TestApiImpl());
		List<Integer> sums = new ArrayList<>();
		RefInt sessionsDuring = new RefInt(-1);
		RefInt inFlightAfter = new RefInt(-1);

		await(withClient(server, client -> {
			TestApi api = client.proxy(TestApi.class);
			return api.add(2, 3)
				.whenResult(result -> sums.add(result.sum()))
				.then(() -> {
					sessionsDuring.set(server.sessions().size());
					return api.add(20, 22);
				})
				.whenResult(result -> sums.add(result.sum()))
				.whenResult(() -> inFlightAfter.set(client.inFlightCount()))
				.toVoid();
		}));

		assertEquals(List.of(5, 42), sums);
		assertEquals("both calls rode one connection, so one session served them", 1, sessionsDuring.get());
		assertEquals("no correlation entry is left behind", 0, inFlightAfter.get());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Scenario 2: concurrency, correlated by id alone.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testThreeConcurrentCallsAreCorrelatedByIdAloneWhenAnswersArriveOutOfOrder() {
		// US1 scenario 2, and the reason this transport needs a correlation table at all: three calls are
		// issued with no await between them, and the server answers them LAST FIRST. The answers therefore
		// reach the client in an order the requests were never sent in, and only the JSON-RPC id can pair
		// them up — a positional or FIFO pairing would produce three plausible, wrong results.
		DeferringApi service = new DeferringApi();
		JsonRpcTcpServer server = server(service);
		List<Integer> completionOrder = new ArrayList<>();

		List<TestApi.AddResult> results = await(withClient(server, client -> {
			TestApi api = client.proxy(TestApi.class);

			// all three issued before any of them resolves — no await between them
			Promise<TestApi.AddResult> first = api.add(1, 1).whenResult(r -> completionOrder.add(r.sum()));
			Promise<TestApi.AddResult> second = api.add(2, 2).whenResult(r -> completionOrder.add(r.sum()));
			Promise<TestApi.AddResult> third = api.add(3, 3).whenResult(r -> completionOrder.add(r.sum()));

			return service.whenArrived(3)
				// The reactor hop matters: the third request's arrival is signalled from INSIDE add(3, 3),
				// before that call has returned its promise to the dispatcher. Releasing inline would
				// complete a promise nobody is waiting on yet, and the wire order would come out 4, 2, 6 —
				// genuinely out of order, but not the deterministic reverse this assertion is about.
				// (`whenArrived(3).async()` is NOT the hop: async() is a no-op on a promise that is not
				// already complete.)
				.then(() -> Promise.complete().async())
				.whenResult(service::releaseInReverseOrder)
				.then(() -> Promises.toList(first, second, third));
		}));

		assertEquals("each promise resolved with its OWN answer", List.of(2, 4, 6),
			results.stream().map(TestApi.AddResult::sum).toList());
		assertEquals("the answers really did arrive out of order", List.of(6, 4, 2), completionOrder);
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Scenario 3: a notification produces no bytes at all.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testANotificationProducesNoResponseDocument() {
		// US1 scenario 3: §4.1 forbids answering a notification, so the wire carries nothing back. Proven
		// by sending the notification and then a plain call on the same connection: per-direction order is
		// append order (FR-023), so any answer to the notification would have to arrive BEFORE the call's.
		// Exactly one document arrives, and it is the call's.
		TestApiImpl service = new TestApiImpl();
		JsonRpcTcpServer server = server(service);
		Ref<byte[]> answer = new Ref<>();

		RawPeer peer = withRawPeer(server, raw -> raw.transport
			.send(document("{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"noted\"]}"))
			.then(() -> raw.transport.send(document(
				"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"test.add\",\"params\":[2,3]}")))
			.then(raw::nextDocument)
			.whenResult(answer::set)
			.toVoid());

		assertEquals("the notification reached the service", List.of("noted"), service.notes());
		assertEquals("exactly one document came back — none of it the notification's", 1, peer.received.size());
		JsonRpcInput decoded = JsonRpcDecoder.decode(answer.get());
		assertThat(decoded, instanceOf(JsonRpcResponse.class));
		assertEquals("and it is the call's answer", new JsonRpcId.Num(7), ((JsonRpcResponse) decoded).id());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Scenario 4: a batch is one message in and one message out.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testABatchIsAnsweredAsOneMessage() {
		// US1 scenario 3 (the batch half). A batch is not a client-proxy concept — JsonRpcClient exposes no
		// batching API — it is a document whose top level is a JSON array. On this transport that array is
		// ONE LF-terminated line in and ONE LF-terminated line out (§6): three elements in, two members
		// out, because the notification element is answered by nothing at all.
		TestApiImpl service = new TestApiImpl();
		JsonRpcTcpServer server = server(service);
		Ref<byte[]> batchAnswer = new Ref<>();

		RawPeer peer = withRawPeer(server, raw -> raw.transport
			.send(document(
				"[" +
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[1,2]}," +
				"{\"jsonrpc\":\"2.0\",\"method\":\"test.note\",\"params\":[\"batched\"]}," +
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.add\",\"params\":[10,20]}" +
				"]"))
			.then(raw::nextDocument)
			.whenResult(batchAnswer::set)
			// a plain call after it: its answer is the second document, which is how "exactly one
			// document answered the batch" becomes an assertion rather than a timeout
			.then(() -> raw.transport.send(document(
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"test.add\",\"params\":[7,7]}")))
			.then(raw::nextDocument)
			.toVoid());

		assertEquals("the batch was answered by exactly one document, the later call by one more",
			2, peer.received.size());
		assertEquals("the batch's notification element reached the service", List.of("batched"), service.notes());

		byte[] answer = batchAnswer.get();
		assertNotNull(answer);
		assertEquals("the answer to a batch is itself a top-level array", '[', answer[0]);
		JsonRpcInput decoded = JsonRpcDecoder.decode(answer);
		assertThat(decoded, instanceOf(JsonRpcBatch.class));
		JsonRpcBatch batch = (JsonRpcBatch) decoded;
		assertEquals("one member per non-notification element", 2, batch.size());
		assertEquals(List.of(new JsonRpcId.Num(1), new JsonRpcId.Num(2)), ids(batch));
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * A {@link TestApi} that answers nothing until told to, then answers <b>last request first</b> — the
	 * out-of-order wire the correlation assertion needs. Reactor-confined like every service
	 * implementation, so a plain list is enough.
	 */
	private static final class DeferringApi implements TestApi {
		private final List<SettablePromise<AddResult>> pending = new ArrayList<>();
		private final List<Integer> sums = new ArrayList<>();
		private int awaited = -1;
		private @Nullable SettablePromise<Void> arrived;

		@Override
		public Promise<AddResult> add(int a, int b) {
			SettablePromise<AddResult> answer = new SettablePromise<>();
			pending.add(answer);
			sums.add(a + b);
			SettablePromise<Void> waiting = arrived;
			if (waiting != null && pending.size() >= awaited) {
				arrived = null;
				waiting.set(null);
			}
			return answer;
		}

		@Override
		public void note(String text) {}

		/** Completes once {@code count} requests have reached this service. */
		Promise<Void> whenArrived(int count) {
			if (pending.size() >= count) return Promise.complete();
			awaited = count;
			SettablePromise<Void> waiting = new SettablePromise<>();
			arrived = waiting;
			return waiting;
		}

		/** Answers every held request, last one first — so the wire order is the reverse of the request order. */
		void releaseInReverseOrder() {
			for (int i = pending.size() - 1; i >= 0; i--) {
				pending.get(i).set(new AddResult(sums.get(i)));
			}
			pending.clear();
			sums.clear();
		}
	}

	/**
	 * A raw {@link JsonRpcTcpTransport} peer with no {@link JsonRpcClient} above it: it writes whole
	 * documents and hands each received document to whoever asks for the next one. That is what makes
	 * "zero response bytes" and "exactly one message" assertable — a client proxy would decode and discard
	 * the very framing these two scenarios are about.
	 */
	private static final class RawPeer {
		final JsonRpcTcpTransport transport;
		final List<byte[]> received = new ArrayList<>();

		private final Deque<byte[]> undelivered = new ArrayDeque<>();
		private @Nullable SettablePromise<byte[]> waiting;

		private RawPeer(JsonRpcTcpTransport transport) {
			this.transport = transport;
			transport.setListener(new JsonRpcTransport.Listener() {
				@Override
				public void onDocument(byte[] document) {
					received.add(document);
					SettablePromise<byte[]> pending = waiting;
					if (pending != null) {
						waiting = null;
						pending.set(document);
					} else {
						undelivered.addLast(document);
					}
				}

				@Override
				public void onClosed(@Nullable Exception e) {}
			});
		}

		/** The next document this peer receives, or the next already-received one not yet taken. */
		Promise<byte[]> nextDocument() {
			byte[] ready = undelivered.pollFirst();
			if (ready != null) return Promise.of(ready);
			SettablePromise<byte[]> pending = new SettablePromise<>();
			waiting = pending;
			return pending;
		}
	}

	/** A listening server on port {@code 0}, accepting once, dispatching to {@code service}. */
	private static JsonRpcTcpServer server(TestApi service) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, service)
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

	/** Connects one {@link JsonRpcClient}, runs {@code body} against it, and closes it inside the chain. */
	private static <T> Promise<T> withClient(JsonRpcTcpServer server, Function<JsonRpcClient, Promise<T>> body) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return body.apply(client)
					.whenComplete(($, e) -> client.closeEx(new ExpectedException("end of test")));
			});
	}

	/** Connects one raw peer, runs {@code body} against it, closes it inside the chain, and returns it. */
	private static RawPeer withRawPeer(JsonRpcTcpServer server, Function<RawPeer, Promise<Void>> body) {
		NioReactor reactor = reactor();
		Ref<RawPeer> peerRef = new Ref<>();
		await(JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				RawPeer peer = new RawPeer(transport);
				peerRef.set(peer);
				return body.apply(peer)
					.whenComplete(($, e) -> transport.closeEx(new ExpectedException("end of test")));
			}));
		return peerRef.get();
	}

	private static List<JsonRpcId> ids(JsonRpcBatch batch) {
		List<JsonRpcId> ids = new ArrayList<>();
		for (JsonRpcDecoded element : batch.elements()) {
			assertThat(element, instanceOf(JsonRpcResponse.class));
			JsonRpcResponse response = (JsonRpcResponse) element;
			assertFalse("every batch member answered with a result, not an error", response.isError());
			ids.add(response.id());
		}
		return ids;
	}

	private static byte[] document(String json) {
		return json.getBytes(UTF_8);
	}

	private static void closeServer(JsonRpcTcpServer server) {
		await(server.close().toVoid());
	}

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
