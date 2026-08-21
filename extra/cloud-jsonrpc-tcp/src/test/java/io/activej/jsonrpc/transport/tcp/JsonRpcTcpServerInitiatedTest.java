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

import io.activej.common.exception.MalformedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.json.JsonCodecs;
import io.activej.jsonrpc.JsonRpcError;
import io.activej.jsonrpc.JsonRpcException;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
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
import java.util.function.UnaryOperator;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * User story 4 — the server initiates calls on a session (T017, FR-050…FR-055): the whole
 * server&rarr;client direction over a real framed-TCP connection, driven from
 * {@link JsonRpcTcpSession#proxy(Class)}.
 * <p>
 * <b>No new mechanism is under test here</b> (FR-050). A session is the connection's transport plus
 * one {@code JsonRpcClient} built {@code withPeerHandler(serverDispatcher)}, and that one component is
 * both the inbound dispatch path and the outbound call path. What these tests pin is that the
 * <i>reversed</i> direction behaves exactly like the forward one on this transport: a result comes
 * back, an application error comes back verbatim, an unknown method answers {@code -32601}, an orphan
 * answer is dropped in silence, several interfaces are proxiable at once, and a closed session refuses
 * instead of hanging.
 *
 * <h2>The handshake is a notification, on purpose</h2>
 * Every test establishes the connection with {@code test.note} rather than a call. Two reasons, both
 * load-bearing:
 * <ul>
 *     <li>a client's {@code connect} promise resolving says nothing about the <i>server</i> having
 *     accepted — the two are independent selector events — so a session may not exist yet. Awaiting a
 *     document the server has demonstrably dispatched is the deterministic barrier, and registration
 *     precedes dispatch (FR-032);</li>
 *     <li>§4.1 forbids answering a notification, and — the point — a notification draws
 *     <b>no identifier</b>. Both directions' counters are therefore still at zero afterwards, which is
 *     what lets {@link #testCollidingNumericIdsResolveIndependentlyInBothDirections()} genuinely
 *     observe two {@code Num(1)}s on one wire (FR-051).
 *     </li>
 * </ul>
 *
 * <h2>Quiescence</h2>
 * Every server binds port {@code 0}, is asked where it landed (ADR-028) and accepts once, so the
 * listening socket is gone after the single connection; every awaited chain closes its client before
 * returning. {@code TestUtils.await} runs the loop to quiescence, so a stranded promise hangs the suite
 * rather than passing silently. Rules per FR-079; {@code @IgnoreLeaks} is forbidden module-wide
 * (FR-024) and does not appear.
 */
public final class JsonRpcTcpServerInitiatedTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/**
	 * A response document whose {@code id} correlates to nothing — the hostile/buggy peer's orphan of
	 * FR-052. Written straight onto the wire, because no proxy can produce one.
	 */
	private static final byte[] UNSOLICITED_RESPONSE =
		"{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":\"unsolicited\"}".getBytes(UTF_8);

	// -------------------------------------------------------------------------------------------
	// The plain reverse call.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testServerInitiatedCallCompletesWithTheClientsResult() {
		// US4 scenario 1: the server calls decide() through the session proxy, the client's own
		// dispatcher routes it to the client's implementation, and the server's Promise completes with
		// the result — one round trip in the direction TCP's persistent connection exists to allow.
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		Ref<String> result = new Ref<>();

		await(withSession(server, serverService, clientDispatcher(new ClientApiImpl()), peers ->
			peers.session().proxy(ClientApi.class).decide(42)
				.whenResult(result::set)
				.toVoid()));

		assertEquals("the server's promise completed with the client's answer", "decided-42", result.get());
		closeServer(server);
	}

	@Test
	public void testCollidingNumericIdsResolveIndependentlyInBothDirections() {
		// FR-051: the two directions are two JsonRpcClients owning two correlation tables and two
		// monotonic counters, so both directions' FIRST call draws Num(1). Both are issued from one
		// reactor callback, so both requests are on the wire before either answer can be processed —
		// genuinely concurrent. If the two id spaces shared a table, at least one answer would
		// mis-correlate.
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		Ref<String> serverResult = new Ref<>();
		Ref<TestApi.AddResult> clientResult = new Ref<>();

		await(withSession(server, serverService, clientDispatcher(new ClientApiImpl()), peers -> {
			Promise<String> serverInitiated = peers.session().proxy(ClientApi.class).decide(42);
			Promise<TestApi.AddResult> clientInitiated = peers.client().proxy(TestApi.class).add(2, 3);
			return Promises.all(
				serverInitiated.whenResult(serverResult::set),
				clientInitiated.whenResult(clientResult::set));
		}));

		assertEquals("the server-initiated call resolved with the client's answer", "decided-42", serverResult.get());
		assertEquals("the client-initiated call resolved with the server's answer",
			new TestApi.AddResult(5), clientResult.get());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Failure shapes of the reverse direction.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testApplicationErrorRoundTripsCodeMessageAndDataVerbatim() throws MalformedDataException {
		// US4 scenario 2: the client's implementation fails with an application JsonRpcException carrying
		// a custom code, message and data (ClientApiImpl.fail() — the fixture's one application-error
		// method). The server-side caller's Promise fails with that same error object, nothing rewritten
		// and nothing dropped: an error is a protocol value in this direction too (ADR-033).
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);

		Exception e = awaitException(withSession(server, serverService, clientDispatcher(new ClientApiImpl()),
			peers -> peers.session().proxy(ClientApi.class).fail().toVoid()));

		assertTrue("the server's promise failed with a JsonRpcException, not " + e, e instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) e).getError();
		assertEquals("the application's code travelled verbatim", 42, error.code());
		assertEquals("the application's message travelled verbatim", "the-client-said-no", error.message());
		assertNotNull("the application's data travelled", error.data());
		assertEquals("the application's data travelled verbatim", "detail", error.data().decode(JsonCodecs.ofString()));
		closeServer(server);
	}

	@Test
	public void testCallToAClientWithoutThatServiceFailsWithMethodNotFound() {
		// US4 scenario 3: UnregisteredApi is registered on no dispatcher anywhere, so the client answers
		// -32601 from the ordinary dispatch miss and the server's Promise fails with that JsonRpcException
		// — code and message verbatim. "The client has no such handler" is a protocol answer, not a
		// transport failure: the connection is untouched by it.
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		RefInt sessionsAfterMiss = new RefInt(-1);

		Exception e = awaitException(withSession(server, serverService, clientDispatcher(new ClientApiImpl()),
			peers -> peers.session().proxy(UnregisteredApi.class).ping()
				.whenComplete(() -> sessionsAfterMiss.set(server.sessions().size()))
				.toVoid()));

		assertTrue("the server's promise failed with a JsonRpcException, not " + e, e instanceof JsonRpcException);
		JsonRpcError error = ((JsonRpcException) e).getError();
		assertEquals("the client answered -32601 Method not found", -32601, error.code());
		assertEquals("Method not found", error.message());
		assertEquals("a method-not-found answer did not cost the connection", 1, sessionsAfterMiss.get());
		closeServer(server);
	}

	@Test
	public void testUnsolicitedResponseIsIgnoredSilentlyAndNeverReachesThePeerHandler() {
		// FR-052: a response whose id is in no server-initiated call's table is dropped in silence — no
		// entry created, no failure reported, and above all NO answer written back. The orphan is injected
		// straight onto the wire through the client's raw transport, because no proxy can produce one.
		//
		// "Never reaches the peer handler" is made observable by counting the documents that come BACK to
		// the client: had the server routed the orphan to its dispatcher, the dispatcher would have
		// answered -32600 Invalid Request and that error document would be a third inbound document. Two
		// legitimate documents are expected and exactly two must arrive — the answer to the client's own
		// call, and the server's own request.
		TestApiImpl serverService = new TestApiImpl();
		List<Exception> failures = new ArrayList<>();
		JsonRpcTcpServer server = server(serverService, failures::add);
		Ref<InboundCounter> counter = new Ref<>();
		Ref<String> result = new Ref<>();
		RefInt inFlightAfterOrphan = new RefInt(-1);
		RefInt inFlightAtEnd = new RefInt(-1);

		await(withSession(server, serverService, clientDispatcher(new ClientApiImpl()),
			transport -> {
				InboundCounter wrapper = new InboundCounter(transport);
				counter.set(wrapper);
				return wrapper;
			},
			peers -> peers.transport().send(UNSOLICITED_RESPONSE)
				// a client-initiated round trip written after the orphan on the same connection: once its
				// answer is back, the server has necessarily read and disposed of the orphan already
				.then(() -> peers.client().proxy(TestApi.class).add(2, 3))
				.then(() -> {
					inFlightAfterOrphan.set(peers.session().inFlightCount());
					return peers.session().proxy(ClientApi.class).decide(7);
				})
				.whenResult(result::set)
				.whenResult(() -> inFlightAtEnd.set(peers.session().inFlightCount()))
				.toVoid()));

		assertEquals("the orphan registered no correlation entry", 0, inFlightAfterOrphan.get());
		assertEquals("no failure was reported for the orphan", List.of(), failures);
		assertEquals("the connection stayed healthy: the reverse call resolved", "decided-7", result.get());
		assertEquals("no correlation entry was left behind", 0, inFlightAtEnd.get());
		assertEquals("exactly the two legitimate documents came back — the orphan was never answered",
			2, counter.get().documents);
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Several contracts on one session; a session that is gone.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testTwoDistinctInterfacesAreProxiableOnOneSession() {
		// FR-054: one session proxies two distinct client-facing interfaces, each validated once at its
		// first proxy(...) call, and both answer over the same connection. This is the reuse answer to
		// "several service interfaces per direction" — no new mechanism, just the per-session client
		// proxying two contracts. The same interface asked for twice is one cached instance, which is the
		// complement of the same rule.
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(ClientApi.class, new ClientApiImpl())
			.withService(ClientConfigApi.class, new ClientConfigApiImpl())
			.build();
		Ref<ClientApi> clientApiProxy = new Ref<>();
		Ref<ClientApi> clientApiProxyAgain = new Ref<>();
		Ref<ClientConfigApi> configProxy = new Ref<>();
		Ref<String> decided = new Ref<>();
		Ref<String> configured = new Ref<>();

		await(withSession(server, serverService, clientDispatcher, peers -> {
			clientApiProxy.set(peers.session().proxy(ClientApi.class));
			clientApiProxyAgain.set(peers.session().proxy(ClientApi.class));
			configProxy.set(peers.session().proxy(ClientConfigApi.class));
			return Promises.all(
				clientApiProxy.get().decide(7).whenResult(decided::set),
				configProxy.get().get("theme").whenResult(configured::set));
		}));

		assertNotSame("two distinct interfaces are two distinct proxies",
			clientApiProxy.get(), configProxy.get());
		assertSame("one interface proxied twice is one cached instance",
			clientApiProxy.get(), clientApiProxyAgain.get());
		assertEquals("the first interface's call resolved", "decided-7", decided.get());
		assertEquals("the second interface's call resolved", "value-of-theme", configured.get());
		closeServer(server);
	}

	@Test
	public void testProxyOnAClosedSessionRefusesImmediatelyRatherThanHanging() {
		// FR-055: proxy(...) on a session whose connection is gone must be a clean refusal — not an NPE,
		// not a hang, not a queue of calls waiting for a connection that will never come back. The proxy
		// itself is still handed out (the contract is a property of the interface, not of the socket), and
		// the call it makes fails synchronously with the close cause. Asserted for an interface already
		// proxied before the close AND for one proxied for the first time after it, because those are two
		// different code paths through the client's proxy cache.
		TestApiImpl serverService = new TestApiImpl();
		JsonRpcTcpServer server = server(serverService, null);
		JsonRpcDispatcher clientDispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(ClientApi.class, new ClientApiImpl())
			.withService(ClientConfigApi.class, new ClientConfigApiImpl())
			.build();
		Ref<JsonRpcTcpSession> closed = new Ref<>();
		ExpectedException expected = new ExpectedException("the session was closed");

		await(withSession(server, serverService, clientDispatcher, peers -> {
			// warmed before the close, so the cached-proxy path is exercised too
			peers.session().proxy(ClientApi.class);
			closed.set(peers.session());
			peers.session().closeEx(expected);
			return Promise.complete();
		}));

		ClientApi cached = closed.get().proxy(ClientApi.class);
		ClientConfigApi fresh = closed.get().proxy(ClientConfigApi.class);
		assertNotNull("a closed session still hands out a proxy rather than failing", cached);
		assertNotNull("including for an interface never proxied before the close", fresh);

		Promise<String> refusedCached = cached.decide(7);
		Promise<String> refusedFresh = fresh.get("theme");

		assertTrue("the cached proxy's refusal is immediate", refusedCached.isException());
		assertSame(expected, refusedCached.getException());
		assertTrue("the fresh proxy's refusal is immediate", refusedFresh.isException());
		assertSame(expected, refusedFresh.getException());
		assertTrue("the closed session left the registry", server.sessions().isEmpty());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Fixtures local to this story.
	// -------------------------------------------------------------------------------------------

	/**
	 * Registered on <b>no</b> dispatcher anywhere — the {@code -32601} probe of the server&rarr;client
	 * direction. A wire name nothing answers is the only honest way to reach a dispatch miss.
	 */
	@JsonRpcService("unregistered")
	public interface UnregisteredApi {
		@JsonRpcMethod("ping")
		Promise<String> ping();
	}

	/**
	 * A second client-facing interface, so that one session demonstrably proxies two distinct contracts
	 * (FR-054) rather than one contract twice. Wire name {@code config.get}.
	 */
	@JsonRpcService("config")
	public interface ClientConfigApi {
		@JsonRpcMethod("get")
		Promise<String> get(@JsonRpcParam("key") String key);
	}

	private static final class ClientConfigApiImpl implements ClientConfigApi {
		@Override
		public Promise<String> get(String key) {
			return Promise.of("value-of-" + key);
		}
	}

	/**
	 * Counts the documents delivered <i>to</i> the client — the observable that turns "the orphan never
	 * reached the peer handler" into an assertion rather than an inference: an answered orphan would be
	 * one more inbound document. Everything else is the delegate's own.
	 */
	private static final class InboundCounter implements JsonRpcTransport {
		private final JsonRpcTransport delegate;
		private int documents;

		private InboundCounter(JsonRpcTransport delegate) {
			this.delegate = delegate;
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
					documents++;
					listener.onDocument(document);
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

	// -------------------------------------------------------------------------------------------
	// Wiring.
	// -------------------------------------------------------------------------------------------

	/** One established connection: the client, the raw transport under it, and the server-side session. */
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

	private static <T> Promise<T> withSession(
		JsonRpcTcpServer server, TestApiImpl serverService, JsonRpcDispatcher clientDispatcher,
		Function<Peers, Promise<T>> body
	) {
		return withSession(server, serverService, clientDispatcher, UnaryOperator.identity(), body);
	}

	/**
	 * Connects one client, waits until the server has demonstrably dispatched from it — so the session
	 * exists (FR-032) — runs {@code body}, and closes the client inside the awaited chain so the loop can
	 * quiesce.
	 */
	private static <T> Promise<T> withSession(
		JsonRpcTcpServer server, TestApiImpl serverService, JsonRpcDispatcher clientDispatcher,
		UnaryOperator<JsonRpcTransport> decorator, Function<Peers, Promise<T>> body
	) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, decorator.apply(transport))
					.withPeerHandler(clientDispatcher)
					.build();
				// the handshake: a NOTIFICATION, which §4.1 forbids answering and which draws no id, so
				// both directions' counters are still at zero when the body runs (FR-051)
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
