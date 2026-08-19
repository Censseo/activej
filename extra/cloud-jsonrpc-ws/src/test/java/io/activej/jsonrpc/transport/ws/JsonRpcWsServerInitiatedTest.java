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

package io.activej.jsonrpc.transport.ws;

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefBoolean;
import io.activej.common.ref.RefInt;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.ws.fixtures.ClientApi;
import io.activej.jsonrpc.transport.ws.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.ClientConfig;
import io.activej.jsonrpc.transport.ws.fixtures.ClientConfigImpl;
import io.activej.jsonrpc.transport.ws.fixtures.HangApi;
import io.activej.jsonrpc.transport.ws.fixtures.HangApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.UnregisteredApi;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * User story 2 — the server calls a client and awaits the result (T011, US2, FR-050…FR-054): a
 * server-initiated call travels down the session's {@code JsonRpcClient}, the client's own
 * dispatcher answers it, and the server's {@code Promise} completes with the result — over the same
 * connection that carries the client's own calls in the opposite direction.
 * <p>
 * <b>Setup shape</b> (mirrors the US1 pattern in {@link JsonRpcWsSessionTest}): one
 * {@link WsPair} {@code acceptOnce} server; the servlet's {@code onWebSocket} creates the
 * server-side session; the client side wires its own {@link JsonRpcClient} over a
 * {@link JsonRpcWsTransport} with its own dispatcher as the peer handler — the whole of the
 * server→client direction is feature 012's {@code withPeerHandler(dispatcher)} seam (FR-050), so
 * nothing here is new machinery. The servlet's dispatcher registers {@link TestApi} (what a client
 * calls); the client's dispatcher registers {@link ClientApi} and {@link ClientConfig} (what the
 * server calls); {@link UnregisteredApi} is deliberately registered nowhere, which is what answers
 * {@code -32601} in US2 scenario 3.
 * <p>
 * <b>Independent {@code id} spaces (FR-051).</b> The two directions are two {@code JsonRpcClient}s
 * owning two monotonic counters, so both directions' <i>first</i> calls draw {@code Num(1)} — which
 * is exactly what US2 scenario 2 pins: a same-numbered pair in opposite directions both resolve,
 * each completing the caller in its own direction. Each test issues its calls from inside one
 * reactor callback, so both requests are on the wire before either response can be processed —
 * genuinely concurrent, not sequential.
 * <p>
 * <b>Quiescence (R3).</b> Every awaited chain closes the client transport — which closes the
 * websocket, which closes the server-side session — inside the chain, before it returns; then
 * {@code WsPair.closeAll()} is the belt-and-suspenders cleanup. Rules per FR-079.
 */
public final class JsonRpcWsServerInitiatedTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** A response document whose {@code id} is in no pending table — the orphan answer of FR-052. */
	private static final byte[] UNSOLICITED_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":\"unsolicited\"}".getBytes(UTF_8);

	/**
	 * A second, plausible answer to {@code id} 1 — the id the session's <i>first</i> server-initiated
	 * call draws (FR-051). Injected once that call has already resolved, so its table slot is vacated
	 * and this document is a duplicate, not an answer.
	 */
	private static final byte[] DUPLICATE_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"decided-42\"}".getBytes(UTF_8);

	/**
	 * The same {@code 1}, encoded as the JSON <i>string</i> {@code "1"} instead of the number the request
	 * carried — a {@code JsonRpcId.Str}, which is a different key from {@code JsonRpcId.Num} and therefore
	 * an unknown id (D2, FR-051).
	 */
	private static final byte[] STRING_ID_IMPOSTOR_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"impostor\"}".getBytes(UTF_8);

	/** The honest answer to that same call: {@code id} as the number {@code 1}, the key the entry is under. */
	private static final byte[] NUMERIC_ID_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"answered-42\"}".getBytes(UTF_8);

	@Test
	public void testServerInitiatedCallCompletesWithClientsResult() {
		// US2-1: the server calls decide() through the session proxy; the client's dispatcher routes
		// it to the client's implementation; the server's Promise completes with the result — the
		// whole server→client request path in one round trip over the real socket.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<String> result = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			return session.proxy(ClientApi.class).decide(42)
				.whenResult(r -> result.set(r))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertEquals("the server's promise completed with the client's answer", "decided-42", result.get());
		pair.closeAll();
	}

	@Test
	public void testCollidingNumericIdsResolveIndependentlyInBothDirections() {
		// US2-2 / FR-051: simultaneous calls in opposite directions, both drawing Num(1) from their
		// own direction's counter, must each complete the caller in its own direction. Both requests
		// are issued in one reactor callback, so both are in flight before either response is
		// processed; if the two id spaces shared a table, the second answer would mis-correlate and
		// at least one result would be wrong.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<String> serverResult = new Ref<>();
		Ref<TestApi.AddResult> clientResult = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			JsonRpcClient client = setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			// the first call in each direction — both draw Num(1) from their own counter (FR-051)
			Promise<String> serverInitiated = session.proxy(ClientApi.class).decide(42);
			Promise<TestApi.AddResult> clientInitiated = client.proxy(TestApi.class).add(2, 3);
			return Promises.all(
					serverInitiated.whenResult(r -> serverResult.set(r)),
					clientInitiated.whenResult(r -> clientResult.set(r)))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertEquals("the server-initiated call resolved with the client's answer",
			"decided-42", serverResult.get());
		assertEquals("the client-initiated call resolved with the server's answer",
			new TestApi.AddResult(5), clientResult.get());
		pair.closeAll();
	}

	@Test
	public void testCallToClientWithoutServiceFailsWithMethodNotFound() {
		// US2-3: a client whose dispatcher knows no such service answers -32601 from its default
		// peer-handler behaviour; the server's Promise fails with that JsonRpcException, code and
		// message verbatim.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		Exception e = awaitException(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			// UnregisteredApi is registered on neither dispatcher: the client answers -32601
			return session.proxy(UnregisteredApi.class).ping()
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertTrue("the server's promise failed with a JsonRpcException, not " + e, e instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) e).getError();
		assertEquals("the client answered -32601 Method not found (US2-3)", -32601, error.code());
		assertEquals("Method not found", error.message());
		pair.closeAll();
	}

	@Test
	public void testApplicationErrorRoundTripsCodeMessageDataVerbatim() throws MalformedDataException {
		// US2-4 / FR-072-analog: the client's implementation fails with an application JsonRpcException
		// carrying a custom code, message and data; the server-side caller's Promise fails with the
		// same error object — code, message and data verbatim, nothing rewritten.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		Exception e = awaitException(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			return session.proxy(ClientApi.class).fail()
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertTrue("the server's promise failed with a JsonRpcException, not " + e, e instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) e).getError();
		assertEquals("the application's code travelled verbatim", 42, error.code());
		assertEquals("the application's message travelled verbatim", "the-client-said-no", error.message());
		assertEquals("the application's data travelled verbatim", "detail", error.data().decode(JsonCodecs.ofString()));
		pair.closeAll();
	}

	@Test
	public void testUnsolicitedResponseIsIgnoredSilently() {
		// FR-052: a response whose id is in no server-initiated call's table is ignored silently —
		// no failure handler, no table entry, no response back. The orphan is injected through the
		// client's raw transport; a real server-initiated call issued right after (and answered only
		// once the server has already read and dropped the orphan, since it precedes the request on
		// the wire) proves the connection is healthy and correlation is undisturbed.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<JsonRpcWsSession> session = new Ref<>();
		Ref<String> result = new Ref<>();
		RefInt inFlightAfterOrphan = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			session.set(servlet.sessions().iterator().next());
			return clientTransport.get().send(UNSOLICITED_RESPONSE)
				// the orphan precedes the real request on the wire, so the server has dropped it
				// by the time the real call's answer arrives
				.then($ -> {
					inFlightAfterOrphan.set(session.get().inFlightCount());
					return session.get().proxy(ClientApi.class).decide(42);
				})
				.whenResult(r -> result.set(r))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertEquals("the orphan response registered no table entry", 0, inFlightAfterOrphan.get());
		assertEquals("no failure was reported for the orphan", List.of(), failures);
		assertEquals("the connection stayed healthy: the real call resolved", "decided-42", result.get());
		assertEquals("no table entry was left behind", 0, session.get().inFlightCount());
		pair.closeAll();
	}

	@Test
	public void testDuplicateResponseForAnAlreadyCompletedCallIsIgnoredSilently() {
		// D1, adversarial plan — the doublon variant of FR-052. The session's first server-initiated
		// call draws Num(1) (FR-051) and its real answer completes it, vacating the table slot; the
		// duplicate injected afterwards carries that same id and therefore finds no entry, so it is
		// dropped exactly like an unknown id: no second delivery, no throw, no failure handler. One
		// more real call afterwards proves the correlation table is undisturbed and still healthy.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<JsonRpcWsSession> session = new Ref<>();
		Ref<String> firstResult = new Ref<>();
		Ref<String> followUpResult = new Ref<>();
		RefInt inFlightAfterFirstCall = new RefInt(-1);
		RefInt inFlightAfterDuplicate = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			session.set(servlet.sessions().iterator().next());
			// the very first call of this session, hence id Num(1) — the id the duplicate reuses
			return session.get().proxy(ClientApi.class).decide(42)
				.whenResult(r -> firstResult.set(r))
				.then($ -> {
					inFlightAfterFirstCall.set(session.get().inFlightCount());
					return clientTransport.get().send(DUPLICATE_RESPONSE);
				})
				// the duplicate precedes the follow-up request on the wire, so the server has read and
				// dropped it by the time the follow-up's answer arrives
				.then($ -> {
					inFlightAfterDuplicate.set(session.get().inFlightCount());
					return session.get().proxy(ClientApi.class).decide(7);
				})
				.whenResult(r -> followUpResult.set(r))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertEquals("the real answer completed the call", "decided-42", firstResult.get());
		assertEquals("the completed call vacated its table slot", 0, inFlightAfterFirstCall.get());
		assertEquals("the duplicate registered no table entry", 0, inFlightAfterDuplicate.get());
		assertEquals("no failure was reported for the duplicate", List.of(), failures);
		assertEquals("the connection stayed healthy: the follow-up call resolved", "decided-7", followUpResult.get());
		assertEquals("no table entry was left behind", 0, session.get().inFlightCount());
		pair.closeAll();
	}

	@Test
	public void testTwoDistinctInterfacesProxiedOnOneSession() {
		// FR-054: one session proxies two distinct client-facing interfaces, each validated at its
		// first proxy(...) call; both answer over the same connection. This is the reuse answer to
		// the source feature's "several service interfaces per direction" question — no new
		// mechanism, just the per-session client proxying two contracts.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<ClientApi> clientApiProxy = new Ref<>();
		Ref<ClientConfig> clientConfigProxy = new Ref<>();
		Ref<String> decideResult = new Ref<>();
		Ref<String> configResult = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			clientApiProxy.set(session.proxy(ClientApi.class));
			clientConfigProxy.set(session.proxy(ClientConfig.class));
			return Promises.all(
					clientApiProxy.get().decide(7).whenResult(r -> decideResult.set(r)),
					clientConfigProxy.get().get("theme").whenResult(v -> configResult.set(v)))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertNotSame("two distinct interfaces are two distinct proxies", clientApiProxy.get(), clientConfigProxy.get());
		assertEquals("the first interface's call resolved", "decided-7", decideResult.get());
		assertEquals("the second interface's call resolved", "value-of-theme", configResult.get());
		pair.closeAll();
	}

	@Test
	public void testStringEncodedIdIsADistinctUnknownIdAndLeavesTheNumericCallInFlight() {
		// D2, adversarial plan — the typed-key side of FR-051. The correlation table is keyed by the whole
		// JsonRpcId, so Str("1") and Num(1) are two different keys: a client rewriting the id of its answer
		// as a string produces an id that is in no entry, ignored exactly like the orphan of FR-052 — no
		// failure handler, no match — while the real call keyed by Num(1) stays in flight. The call goes to
		// the never-answering HangApi, so the only answers on the wire are the two injected by hand: the
		// impostor, which resolves nothing, and then the correct numeric-id one, which is what resolves it.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<JsonRpcWsSession> session = new Ref<>();
		Ref<String> result = new Ref<>();
		RefBoolean completedAfterImpostor = new RefBoolean(true);
		RefInt inFlightAfterImpostor = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			JsonRpcClient client = setupClient(reactor(), clientTransport.get());
			session.set(servlet.sessions().iterator().next());
			// the very first call of this session, hence id Num(1) — the id the impostor rewrites as "1";
			// HangApi never answers, so nothing but an injected document can ever resolve it
			Promise<String> hanging = session.get().proxy(HangApi.class).request(42);
			return clientTransport.get().send(STRING_ID_IMPOSTOR_RESPONSE)
				// a client-initiated round trip written after the impostor on the same socket: once its
				// answer is back, the server has necessarily read and disposed of the impostor already
				.then($ -> client.proxy(TestApi.class).add(2, 3))
				.then($ -> {
					completedAfterImpostor.set(hanging.isComplete());
					inFlightAfterImpostor.set(session.get().inFlightCount());
					return clientTransport.get().send(NUMERIC_ID_RESPONSE);
				})
				.then($ -> hanging)
				.whenResult(r -> result.set(r))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertFalse("the string-keyed impostor resolved nothing", completedAfterImpostor.get());
		assertEquals("the real call was still in flight, its Num(1) entry untouched", 1, inFlightAfterImpostor.get());
		assertEquals("no failure was reported for the string-id impostor", List.of(), failures);
		assertEquals("the correct numeric-id answer is what resolved the call", "answered-42", result.get());
		assertEquals("no table entry was left behind", 0, session.get().inFlightCount());
		pair.closeAll();
	}

	@Test
	public void testSameInterfaceProxiedTwiceReturnsTheSameProxy() {
		// D5, adversarial plan — the cache side of FR-054, the complement of
		// testTwoDistinctInterfacesProxiedOnOneSession: proxy(...) twice for the *same* interface on one
		// session is one validation and one instance, because the session's JsonRpcClient caches a proxy per
		// interface. Both references are that one proxy, and a call through each resolves — the cache is a
		// cache, not a functional difference.
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<ClientApi> firstProxy = new Ref<>();
		Ref<ClientApi> secondProxy = new Ref<>();
		Ref<String> firstResult = new Ref<>();
		Ref<String> secondResult = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			setupClient(reactor(), clientTransport.get());
			JsonRpcWsSession session = servlet.sessions().iterator().next();
			firstProxy.set(session.proxy(ClientApi.class));
			secondProxy.set(session.proxy(ClientApi.class));
			return Promises.all(
					firstProxy.get().decide(7).whenResult(r -> firstResult.set(r)),
					secondProxy.get().decide(9).whenResult(r -> secondResult.set(r)))
				.whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException()));
		}));

		assertSame("one interface proxied twice is one cached instance (FR-054)", firstProxy.get(), secondProxy.get());
		assertEquals("a call through the first reference resolved", "decided-7", firstResult.get());
		assertEquals("a call through the second reference resolved", "decided-9", secondResult.get());
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring helpers.
	// ---------------------------------------------------------------------------------------------------

	/** The servlet whose dispatcher answers the client-initiated direction (TestApi). */
	private static JsonRpcWsServlet newServlet() {
		return JsonRpcWsServlet.builder(reactor(), serverDispatcher()).build();
	}

	/** The single server-side service table: what a client calls the server with. */
	private static JsonRpcDispatcher serverDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
	}

	/**
	 * A client's full inbound wiring: the transport feeds a client whose peer handler is the
	 * client's own dispatcher — the whole server→client direction (FR-050). Registers both
	 * client-facing interfaces a server may call, plus the never-answering {@link HangApi} that keeps a
	 * server-initiated call in flight while the D2 test injects answers by hand;
	 * {@link UnregisteredApi} is deliberately absent.
	 */
	private static JsonRpcClient setupClient(NioReactor reactor, JsonRpcWsTransport transport) {
		return JsonRpcClient.builder(reactor, transport)
			.withPeerHandler(JsonRpcDispatcher.builder(reactor)
				.withService(ClientApi.class, new ClientApiImpl())
				.withService(ClientConfig.class, new ClientConfigImpl())
				.withService(HangApi.class, new HangApiImpl())
				.build())
			.build();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
