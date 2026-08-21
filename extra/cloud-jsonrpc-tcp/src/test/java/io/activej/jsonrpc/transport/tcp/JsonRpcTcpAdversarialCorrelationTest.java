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
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Adversarial test plan, Domain D — the server&rarr;client correlation table under a hostile or
 * merely honest-but-late peer (FR-051/FR-052/FR-053, feature 012's FR-066/FR-068/FR-070 inherited
 * unchanged). {@link JsonRpcTcpServerInitiatedTest} pins the happy paths and one orphan-by-unknown-id
 * case (T017); this class adds three scenarios that file's fixtures do not reach:
 * <ul>
 *     <li><b>D1 (P0)</b> — a real peer answers a server-initiated call <i>normally</i>, and only
 *     <b>after</b> that answer has already resolved the promise and vacated the table does a second,
 *     forged document carrying the very same {@code id} arrive. The slot is already empty, so this is
 *     — by construction of {@code JsonRpcClient.complete}, which calls the single {@code remove(id)}
 *     and does nothing when it returns {@code null} — <i>exactly</i> the same code path as an id that
 *     was never issued (FR-052): ignored silently, no failure handler, {@code inFlightCount()} stays
 *     at zero, and the connection is unharmed for the next call.</li>
 *     <li><b>D2 (P1)</b> — a call is issued to a peer whose dispatcher never answers natively, so the
 *     numeric entry ({@code JsonRpcId.Num(1)}) is genuinely alone at that key. A forged answer keyed
 *     by the <i>string</i> {@code "1"} must not resolve it: {@link io.activej.jsonrpc.JsonRpcId}'s own
 *     contract is that a string id and a number id are never equal, so the correlation table — keyed
 *     by the whole {@code JsonRpcId}, not a {@code long} — treats the forgery as an orphan of a
 *     different key and leaves the numeric call genuinely in flight until the session itself closes.</li>
 *     <li><b>D3 (P1)</b> — the exact mirror, on this transport, of
 *     {@link JsonRpcTcpRoundTripTest#testThreeConcurrentCallsAreCorrelatedByIdAloneWhenAnswersArriveOutOfOrder()}:
 *     three {@code session.proxy(ClientApi.class).decide(n)} calls issued with no await between them,
 *     answered by a real peer <b>last request first</b>. Only the JSON-RPC {@code id} can pair each
 *     promise with its own answer on this persistent duplex connection (transport SPI obligation 5:
 *     "assume nothing about pairing or order ... responses may arrive in any order relative to the
 *     requests that caused them") — nothing in {@code JsonRpcTcpSession}'s
 *     {@code CloseObservingTransport} wrapper introduces a positional or FIFO pairing of its own.</li>
 * </ul>
 *
 * <h2>Why every scenario forces a same-direction round trip before asserting</h2>
 * A forged or wrongly-keyed response never mutates the correlation table it fails to match — the
 * table's size is identical whether that document has been read yet or not. Checking
 * {@code inFlightCount()} immediately after {@code send()} (which completes when the bytes are
 * <b>written</b>, not when the peer has read them, obligation 4) would therefore prove nothing about
 * whether the forgery was actually processed and rejected. Each scenario below closes that gap the
 * same way {@link JsonRpcTcpServerInitiatedTest} does: a genuine call on the <i>same direction</i>
 * as the forgery, awaited before any assertion — since a connection's inbound framing decoder reads
 * one document at a time and delivers them in the order they arrived (FR-022), a later document's
 * answer being back proves the earlier, forged one was already read and dispatched.
 *
 * <h2>Quiescence</h2>
 * Every server binds port {@code 0} and is asked where it landed (ADR-028), accepts once, and every
 * awaited chain closes its client before returning — the same shape as every other test in this
 * module. {@code TestUtils.await} runs the loop to quiescence, so a stranded promise hangs the suite
 * rather than passing silently; {@code @IgnoreLeaks} is forbidden module-wide (FR-024) and does not
 * appear here either.
 */
public final class JsonRpcTcpAdversarialCorrelationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/**
	 * D1's forgery: the very {@code id} {@code session.proxy(ClientApi.class).decide(42)} draws as the
	 * first server-initiated call on a fresh session ({@code JsonRpcId.Num(1)}), carrying a result no
	 * real peer ever produced. Written only after the genuine answer has already resolved the call and
	 * emptied the slot.
	 */
	private static final byte[] DUPLICATE_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"forged-duplicate\"}".getBytes(UTF_8);

	/**
	 * D2's forgery: the same digits as the first server-initiated call's id, but as a JSON
	 * <b>string</b> — {@code JsonRpcId.Str("1")}, never equal to {@code JsonRpcId.Num(1)} (see
	 * {@link io.activej.jsonrpc.JsonRpcId}'s own Javadoc on that point).
	 */
	private static final byte[] FORGED_STRING_ID_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"forged-string-id\"}".getBytes(UTF_8);

	// -------------------------------------------------------------------------------------------
	// D1 (P0): a duplicate answer for an id the first, genuine answer already vacated.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testDuplicateResponseForAnAlreadyResolvedIdIsIgnoredSilently() {
		TestApiImpl serverService = new TestApiImpl();
		List<Exception> failures = new ArrayList<>();
		JsonRpcTcpServer server = server(serverService, failures::add);
		Ref<String> firstResult = new Ref<>();
		Ref<String> secondResult = new Ref<>();
		RefInt inFlightAfterDuplicate = new RefInt(-1);

		await(withSession(server, serverService, clientDispatcher(new ClientApiImpl()), peers ->
			peers.session().proxy(ClientApi.class).decide(42)
				.whenResult(firstResult::set)
				.then(() -> peers.transport().send(DUPLICATE_RESPONSE))
				// a client-initiated round trip written after the forged duplicate, on the SAME
				// connection: once its answer is back, the server has necessarily already read and
				// disposed of the duplicate (FR-022's serial framing decoder)
				.then(() -> peers.client().proxy(TestApi.class).add(2, 3))
				.then(() -> {
					inFlightAfterDuplicate.set(peers.session().inFlightCount());
					return peers.session().proxy(ClientApi.class).decide(7);
				})
				.whenResult(secondResult::set)
				.toVoid()));

		assertEquals("the first call resolved with the peer's genuine answer", "decided-42", firstResult.get());
		assertEquals("the duplicate found its slot already empty and registered no entry",
			0, inFlightAfterDuplicate.get());
		assertEquals("no failure was reported for the duplicate — same silent path as an unknown id",
			List.of(), failures);
		assertEquals("the connection stayed healthy: a later call resolved too", "decided-7", secondResult.get());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// D2 (P1): a string-keyed forgery must not collide with the numeric in-flight entry.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testForgedStringIdIsIgnoredAsOrphanLeavingTheNumericCallInFlightUntilClose() {
		TestApiImpl serverService = new TestApiImpl();
		List<Exception> failures = new ArrayList<>();
		JsonRpcTcpServer server = server(serverService, failures::add);
		RefInt inFlightAfterInjection = new RefInt(-1);
		Ref<Exception> callFailure = new Ref<>();
		ExpectedException closeCause = new ExpectedException("closing to drain the still-pending numeric call");

		await(withSession(server, serverService, clientDispatcher(hangingClientApi()), peers -> {
			// the client's dispatcher never answers natively (hangingClientApi), so the ONLY document
			// this call could ever be resolved by is the forged one injected below
			Promise<String> pending = peers.session().proxy(ClientApi.class).decide(1);

			return peers.transport().send(FORGED_STRING_ID_RESPONSE)
				// same reasoning as D1: a same-direction round trip proves the forgery was read
				.then(() -> peers.client().proxy(TestApi.class).add(2, 3))
				.then(() -> {
					inFlightAfterInjection.set(peers.session().inFlightCount());
					pending.whenException(callFailure::set);
					// only NOW does the numeric call get to fail — through the ordinary purge, with the
					// close cause, never through the string-keyed forgery
					peers.session().closeEx(closeCause);
					return pending.map($ -> null, e -> null);
				})
				.toVoid();
		}));

		assertEquals("the string-keyed forgery left the numeric entry genuinely in flight",
			1, inFlightAfterInjection.get());
		assertEquals("no failure was reported for the orphan of a different key",
			List.of(), failures);
		assertSame("the call failed only once the session closed, and with the close cause — never " +
				   "resolved by the string-keyed forgery",
			closeCause, callFailure.get());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// D3 (P1): three concurrent server-initiated calls, answered last request first.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testThreeConcurrentServerInitiatedCallsAreCorrelatedByIdAloneWhenAnswersArriveOutOfOrder() {
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		DeferringClientApi clientService = new DeferringClientApi();
		List<String> completionOrder = new ArrayList<>();

		List<String> results = await(withSession(server, serverService, clientDispatcher(clientService), peers -> {
			ClientApi api = peers.session().proxy(ClientApi.class);

			// all three issued before any of them resolves — no await between them
			Promise<String> first = api.decide(1).whenResult(completionOrder::add);
			Promise<String> second = api.decide(2).whenResult(completionOrder::add);
			Promise<String> third = api.decide(3).whenResult(completionOrder::add);

			return clientService.whenArrived(3)
				// the reactor hop matters here exactly as it does in JsonRpcTcpRoundTripTest's mirror
				// scenario: releasing inline, from inside the dispatch of the third request, would send
				// that request's own answer first — genuinely out of order, but not the deterministic
				// reverse this assertion needs
				.then(() -> Promise.complete().async())
				.whenResult(clientService::releaseInReverseOrder)
				.then(() -> Promises.toList(first, second, third));
		}));

		assertEquals("each promise resolved with its OWN answer",
			List.of("decided-1", "decided-2", "decided-3"), results);
		assertEquals("the answers really did arrive out of order",
			List.of("decided-3", "decided-2", "decided-1"), completionOrder);
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Fixtures local to this class.
	// -------------------------------------------------------------------------------------------

	/**
	 * A {@link ClientApi} that never answers {@link #decide(int)} — the fixture D2 needs so that the
	 * numeric entry's only possible resolution is the forged document under test, never a genuine
	 * answer racing it. Mirrors {@code JsonRpcTcpSessionTest.hangingClientApi()}.
	 */
	private static ClientApi hangingClientApi() {
		return new ClientApi() {
			@Override
			public Promise<String> decide(int n) {
				return new SettablePromise<>();
			}

			@Override
			public Promise<String> fail() {
				return new SettablePromise<>();
			}

			@Override
			public void event(long id) {}
		};
	}

	/**
	 * A {@link ClientApi} that answers nothing until told to, then answers <b>last request first</b> —
	 * the out-of-order wire D3 needs, on the server&rarr;client direction. The exact mirror of
	 * {@code JsonRpcTcpRoundTripTest.DeferringApi}, which does the same for {@code TestApi} on the
	 * client&rarr;server direction.
	 */
	private static final class DeferringClientApi implements ClientApi {
		private final List<SettablePromise<String>> pending = new ArrayList<>();
		private final List<String> answers = new ArrayList<>();
		private int awaited = -1;
		private @Nullable SettablePromise<Void> arrived;

		@Override
		public Promise<String> decide(int n) {
			SettablePromise<String> answer = new SettablePromise<>();
			pending.add(answer);
			answers.add("decided-" + n);
			SettablePromise<Void> waiting = arrived;
			if (waiting != null && pending.size() >= awaited) {
				arrived = null;
				waiting.set(null);
			}
			return answer;
		}

		@Override
		public Promise<String> fail() {
			return Promise.ofException(new UnsupportedOperationException("not used by this scenario"));
		}

		@Override
		public void event(long id) {}

		/** Completes once {@code count} calls have reached this peer. */
		Promise<Void> whenArrived(int count) {
			if (pending.size() >= count) return Promise.complete();
			awaited = count;
			SettablePromise<Void> waiting = new SettablePromise<>();
			arrived = waiting;
			return waiting;
		}

		/** Answers every held call, last one first — so the wire order is the reverse of the call order. */
		void releaseInReverseOrder() {
			for (int i = pending.size() - 1; i >= 0; i--) {
				pending.get(i).set(answers.get(i));
			}
			pending.clear();
			answers.clear();
		}
	}

	// -------------------------------------------------------------------------------------------
	// Wiring — the same shape as JsonRpcTcpServerInitiatedTest.
	// -------------------------------------------------------------------------------------------

	/** One established connection: the raw client, the transport under it, and the server-side session. */
	private record Peers(JsonRpcClient client, JsonRpcTcpTransport transport, JsonRpcTcpSession session) {}

	private static JsonRpcTcpServer server(TestApiImpl serverService, @Nullable Consumer<Exception> failureHandler) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, serverService)
			.build();
		JsonRpcTcpServer.Builder builder = JsonRpcTcpServer.builder(reactor(), dispatcher)
			.withListenPort(0)
			.withAcceptOnce();
		if (failureHandler != null) builder.withFailureHandler(failureHandler);
		JsonRpcTcpServer server = builder.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/** The client's inbound wiring: whatever a server may call this client with. */
	private static JsonRpcDispatcher clientDispatcher(ClientApi clientService) {
		return JsonRpcDispatcher.builder(reactor())
			.withService(ClientApi.class, clientService)
			.build();
	}

	/**
	 * Connects one client, waits until the server has demonstrably dispatched from it — so the session
	 * exists (FR-032) — runs {@code body}, and closes the client inside the awaited chain so the loop can
	 * quiesce. The handshake is a NOTIFICATION on purpose (same reasoning as
	 * {@code JsonRpcTcpServerInitiatedTest}): it draws no id, so the server-initiated direction's counter
	 * is still at zero when {@code body} runs, which is what lets D1/D2 predict the forged id exactly.
	 */
	private static <T> Promise<T> withSession(
		JsonRpcTcpServer server, TestApiImpl serverService, JsonRpcDispatcher clientDispatcher,
		Function<Peers, Promise<T>> body
	) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
					.withPeerHandler(clientDispatcher)
					.build();
				client.proxy(TestApi.class).note("established");
				return serverService.firstNote()
					.then(() -> body.apply(new Peers(client, transport, server.sessions().iterator().next())))
					.whenComplete(() -> client.closeEx(new ExpectedException("end of test")));
			});
	}

	private static void closeServer(JsonRpcTcpServer server) {
		await(server.close().toVoid());
	}

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
