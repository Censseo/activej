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

import io.activej.async.exception.AsyncCloseException;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClosedTcpSocket;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.net.socket.tcp.TcpSocket;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The server's accept-to-registry lifecycle (T008): where a session comes from, when it is in the
 * registry, when it leaves, and what never enters it at all.
 * <p>
 * The load-bearing assertions are the two orderings. <b>Registration before dispatch</b> (FR-032) is
 * asserted from <i>inside</i> the service implementation — the earliest point a peer's document can
 * reach application code — rather than after the round trip, where a late registration would be
 * invisible. <b>Deregistration exactly once</b> is asserted across all three close kinds (local,
 * remote, failed), because the SPI's exactly-once {@code onClosed} is the single deregistration path
 * and a second path is the defect this shape exists to prevent.
 * <p>
 * The <b>zombie guard</b> (research risk 2) cannot be provoked over a real socket — it is a race
 * between an accept and a peer's reset — so it is expressed with a socket stub that is dead on
 * arrival ({@link ClosedTcpSocket}) and handed to {@code serve} directly. Everything else here runs
 * over real sockets, on servers bound to port {@code 0} and asked where they landed (ADR-028).
 * <p>
 * <b>The drain (FR-038) is US4's half</b> and was added by T019 at the end of this class: closing the
 * server fails every live session's in-flight calls in both directions, and the close completes only
 * once the registry has emptied.
 */
public final class JsonRpcTcpServerLifecycleTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The live sessions the drain test sets up — more than one, because a drain of one proves nothing. */
	private static final int DRAINED_SESSIONS = 3;

	/** One never-answered call per direction per session: what the drain has to fail. */
	private static final int DRAINED_CALLS = DRAINED_SESSIONS * 2;

	/** The drain's own close cause, fixed by {@code JsonRpcTcpServer.onClose}. */
	private static final String DRAIN_CAUSE = "the server is closing";

	// -------------------------------------------------------------------------------------------
	// Registration.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSessionIsRegisteredBeforeTheFirstDispatch() {
		// FR-032: the registry must already hold the session when the first inbound document reaches
		// application code — otherwise a server-initiated call made from a handler would find no session
		// to make it on. Asserted from inside the service implementation, which is the earliest
		// observable point on that path.
		Ref<JsonRpcTcpServer> serverRef = new Ref<>();
		RefInt sessionsAtDispatch = new RefInt(-1);

		JsonRpcTcpServer server = server(dispatcher(new TestApi() {
			@Override
			public Promise<AddResult> add(int a, int b) {
				sessionsAtDispatch.set(serverRef.get().sessions().size());
				return Promise.of(new AddResult(a + b));
			}

			@Override
			public void note(String text) {}
		}), Function.identity());
		serverRef.set(server);

		TestApi.AddResult result = await(withClient(server, client ->
			client.proxy(TestApi.class).add(2, 3)));

		assertEquals(5, result.sum());
		assertEquals("the session was in the registry before the dispatch", 1, sessionsAtDispatch.get());
		closeServer(server);
	}

	@Test
	public void testAConnectionAlreadyClosedAtConstructionIsNeverRegistered() {
		// Research risk 2 / FR-032/FR-035: a connection that dies while its session is being constructed
		// has already spent the transport's exactly-once onClosed — the deregistration callback ran
		// before the session reference existed. Registering afterwards would leave an entry nothing can
		// ever remove: a per-connection leak and a failure report on every subsequent broadcast.
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()), Function.identity());

		server.serve(new ClosedTcpSocket(), loopback());

		assertTrue("a dead-on-arrival connection is never registered", server.sessions().isEmpty());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Deregistration — one path, three causes.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSessionIsDeregisteredOnALocalClose() {
		// FR-032: the server closing a session deregisters it through the same onClosed path a peer close
		// takes — there is no second removal site, so the two can never diverge.
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()), Function.identity());
		RefInt sessionsWhileOpen = new RefInt(-1);

		await(withClient(server, client -> client.proxy(TestApi.class).add(1, 1)
			.whenResult(() -> {
				sessionsWhileOpen.set(server.sessions().size());
				JsonRpcTcpSession session = server.sessions().iterator().next();
				session.closeEx(new ExpectedException("the server hangs up"));
				// idempotent: a second close is not a second deregistration
				session.closeEx(new ExpectedException("again"));
			})
			.toVoid()));

		assertEquals(1, sessionsWhileOpen.get());
		assertTrue("a locally closed session leaves the registry", server.sessions().isEmpty());
		closeServer(server);
	}

	@Test
	public void testSessionIsDeregisteredOnARemoteClose() {
		// FR-032: the peer going away is the common case and takes the same single path. `await` runs the
		// loop to quiescence, so by the time it returns the server has observed the end-of-stream.
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()), Function.identity());
		RefInt sessionsWhileOpen = new RefInt(-1);

		await(withClient(server, client -> client.proxy(TestApi.class).add(1, 1)
			.whenResult(() -> {
				sessionsWhileOpen.set(server.sessions().size());
				client.closeEx(new ExpectedException("the client goes away"));
			})
			.toVoid()));

		assertEquals(1, sessionsWhileOpen.get());
		assertTrue("a remotely closed session leaves the registry", server.sessions().isEmpty());
		closeServer(server);
	}

	@Test
	public void testSessionIsDeregisteredOnAFailedClose() {
		// FR-032 for the third close kind: a framing violation (a bare LF, FR-017) closes the connection
		// with a cause rather than cleanly. The registry must not care which of the three happened.
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()), Function.identity());
		RefInt sessionsAfterFraming = new RefInt(-1);
		NioReactor reactor = reactor();

		await(TcpSocket.connect(reactor, boundAddress(server))
			.then(socket -> {
				// a well-framed document first, so the session certainly exists, then the violation
				SettablePromise<Void> closed = new SettablePromise<>();
				JsonRpcTcpTransport peer = JsonRpcTcpTransport.of(reactor, socket);
				peer.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {
						sessionsAfterFraming.set(server.sessions().size());
						// now the framing violation: a bare LF, written under the transport
						socket.write(ByteBuf.wrapForReading("\n".getBytes(UTF_8)));
					}

					@Override
					public void onClosed(@Nullable Exception e) {
						closed.set(null);
					}
				});
				return peer.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}".getBytes(UTF_8))
					.then(() -> closed)
					.whenComplete(($, e) -> peer.close());
			}));

		assertEquals("the session was live before the violation", 1, sessionsAfterFraming.get());
		assertTrue("a session closed by a framing violation leaves the registry", server.sessions().isEmpty());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// Inherited admission and accept policy — composed, never re-invented.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAcceptOnceAcceptsExactlyOneConnection() {
		// FR-039: withAcceptOnce is the base class's, and it is what lets the conformance subject's
		// per-exchange servers reach quiescence under TestUtils.await. Once the single connection has
		// been accepted the listening socket is closed, so a second connect is refused by the OS.
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()), Function.identity());
		NioReactor reactor = reactor();
		InetSocketAddress address = boundAddress(server);

		Ref<JsonRpcTcpTransport> first = new Ref<>();
		Exception second = awaitException(JsonRpcTcpTransport.connect(reactor, address)
			.whenResult(first::set)
			.then(() -> JsonRpcTcpTransport.connect(reactor, address))
			.whenComplete(($, e) -> {
				if (first.get() != null) first.get().close();
			}));

		assertNotNull("the second connect must be refused", second);
		closeServer(server);
	}

	@Test
	public void testAcceptFilterRefusesTheConnectionBeforeASessionExists() {
		// FR-036: application-level admission is the platform's existing seam, not a JSON-RPC handshake.
		// A refused connection never reaches serve(), so no session is ever created for it — the registry
		// is the wrong place to enforce admission and this pins that it is not asked to.
		//
		// ⚠ A filtered accept does NOT trip withAcceptOnce — the base class returns before that block — so
		// the listening socket stays open and keeps Eventloop.isAlive() true. The server is therefore
		// closed INSIDE the awaited chain; closing it afterwards would never be reached (the await would
		// spin on the selector forever, which is how this test first failed).
		JsonRpcTcpServer server = server(dispatcher(new TestApiImpl()),
			builder -> builder.withAcceptFilter((channel, localAddress, remoteAddress, ssl) -> true));
		NioReactor reactor = reactor();

		await(JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				SettablePromise<Void> closed = new SettablePromise<>();
				transport.setListener(new JsonRpcTransport.Listener() {
					@Override
					public void onDocument(byte[] document) {}

					@Override
					public void onClosed(@Nullable Exception e) {
						closed.set(null);
					}
				});
				return closed.whenComplete(($, e) -> {
					transport.close();
					server.close();
				});
			}));

		assertTrue("a filtered connection never becomes a session", server.sessions().isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// Construction-time refusals.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testDispatcherOnADifferentReactorIsRefusedUnderChecks() {
		// FR-031: under CHECKS the builder refuses a dispatcher living on another reactor — the two would
		// otherwise corrupt each other's reactor-confined state, silently and much later.
		Eventloop otherEventloop = Eventloop.create();                 // a second, unstarted reactor
		JsonRpcDispatcher otherDispatcher = JsonRpcDispatcher.builder(otherEventloop)
			.withService(TestApi.class, new TestApiImpl())
			.build();

		try {
			JsonRpcTcpServer.builder(reactor(), otherDispatcher).withListenPort(0).build();
			fail("a dispatcher on a different reactor must be refused under CHECKS (FR-031)");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	// -------------------------------------------------------------------------------------------
	// The drain (US4, T019).
	// -------------------------------------------------------------------------------------------

	@Test
	public void testClosingTheServerDrainsEveryLiveSessionInBothDirections() {
		// FR-038: closing the server stops accepting (the base class's), then closes every live session —
		// each purging its own in-flight calls with the close cause — and completes only once the registry
		// has emptied. Three sessions, each carrying one never-answered call in EACH direction, so the
		// drain has six promises to settle and no way to settle them but the close.
		//
		// The close is deliberately issued only once every call has been observed to ARRIVE at its peer
		// (HangingService counts arrivals). That is not politeness: closing a socket whose receive buffer
		// still holds unread bytes makes the kernel send a reset instead of an orderly FIN, and the peer's
		// purge cause would then be an IOException rather than the clean AsyncCloseException asserted
		// below. The test would still pass or fail at random, which is worse than either.
		//
		// Two orderings are the real assertions. The registry is EMPTY at the instant the close promise
		// completes, and every server-side purge is recorded BEFORE the close completes — the drain is what
		// the close waits for, not something that happens to run alongside it.
		HangingService serverHang = new HangingService();
		HangingService clientHang = new HangingService();
		JsonRpcTcpServer server = drainServer(serverHang);

		List<JsonRpcClient> clients = new ArrayList<>();
		List<Exception> serverSideFailures = new ArrayList<>();
		List<Exception> clientSideFailures = new ArrayList<>();
		List<String> order = new ArrayList<>();
		RefInt sessionsBeforeClose = new RefInt(-1);
		RefInt sessionsWhenCloseCompleted = new RefInt(-1);
		RefInt inFlightBeforeClose = new RefInt(-1);

		await(connectAll(server, clientHang, clients, DRAINED_SESSIONS)
			.then(() -> {
				SettablePromise<Void> allDrained = new SettablePromise<>();
				RefInt drained = new RefInt(0);
				// client → server: one call each, into the server's never-answering service
				for (JsonRpcClient client : clients) {
					drainOf(client.proxy(HangApi.class).call(1), clientSideFailures, order, "client", drained, allDrained);
				}
				return serverHang.arrived(DRAINED_SESSIONS)
					.then(() -> {
						// server → client: one call each, into the clients' never-answering service
						for (JsonRpcTcpSession session : server.sessions()) {
							drainOf(session.proxy(HangApi.class).call(1), serverSideFailures, order, "server", drained, allDrained);
						}
						return clientHang.arrived(DRAINED_SESSIONS);
					})
					.then(() -> {
						sessionsBeforeClose.set(server.sessions().size());
						inFlightBeforeClose.set(totalInFlight(server));
						Promise<Void> closing = server.close().toVoid()
							.whenComplete(() -> {
								sessionsWhenCloseCompleted.set(server.sessions().size());
								order.add("closed");
							});
						return Promises.all(closing, allDrained);
					});
			}));

		assertEquals("three sessions were live before the close", DRAINED_SESSIONS, sessionsBeforeClose.get());
		assertEquals("each session had one server-initiated call in flight", DRAINED_SESSIONS, inFlightBeforeClose.get());
		assertEquals("the registry was empty when the close completed", 0, sessionsWhenCloseCompleted.get());
		assertEquals("every server-side purge preceded the close's completion",
			DRAINED_SESSIONS, order.indexOf("closed"));
		assertEquals("every call of both directions was settled", DRAINED_CALLS,
			serverSideFailures.size() + clientSideFailures.size());

		assertEquals("every server-initiated call failed", DRAINED_SESSIONS, serverSideFailures.size());
		for (Exception e : serverSideFailures) {
			assertTrue("a server-initiated call failed with the drain's cause, not " + e,
				e instanceof AsyncCloseException);
			assertEquals(DRAIN_CAUSE, e.getMessage());
		}

		assertEquals("every client-initiated call failed", DRAINED_SESSIONS, clientSideFailures.size());
		for (Exception e : clientSideFailures) {
			assertTrue("a client-initiated call failed with a clean close, not " + e,
				e instanceof AsyncCloseException);
		}

		assertEquals("nothing is left in flight anywhere", 0, totalInFlight(server));
		assertTrue("a closed server keeps an empty registry", server.sessions().isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * The one never-answering service of the drain test, registered on <b>both</b> peers: the two
	 * directions are symmetric, so one interface and two implementations cover the whole drain. Wire name
	 * {@code hang.call}.
	 */
	@JsonRpcService("hang")
	public interface HangApi {
		@JsonRpcMethod("call")
		Promise<String> call(@JsonRpcParam("n") int n);
	}

	/**
	 * {@link HangApi}'s never-answering implementation. It counts arrivals so a test can await "the peer
	 * has dispatched all N calls" — which is what makes <i>in flight</i> a fact rather than a hope, and
	 * what guarantees no unread bytes are sitting in a receive buffer when the close is issued.
	 */
	private static final class HangingService implements HangApi {
		private int arrivals;
		private int awaited = Integer.MAX_VALUE;
		private @Nullable SettablePromise<Void> pending;

		@Override
		public Promise<String> call(int n) {
			arrivals++;
			SettablePromise<Void> pending = this.pending;
			if (pending != null && arrivals >= awaited) {
				this.pending = null;
				this.awaited = Integer.MAX_VALUE;
				pending.set(null);
			}
			return new SettablePromise<>();
		}

		/** Completes once {@code count} calls have been dispatched here; already complete if they have. */
		Promise<Void> arrived(int count) {
			if (arrivals >= count) return Promise.complete();
			awaited = count;
			SettablePromise<Void> pending = new SettablePromise<>();
			this.pending = pending;
			return pending;
		}
	}

	/** A server that keeps accepting, since the drain needs more than one live session. */
	private static JsonRpcTcpServer drainServer(HangApi serverService) {
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.withService(HangApi.class, serverService)
				.build())
			.withListenPort(0)
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/**
	 * Connects {@code count} clients one after another, each answering {@code clientService}. A real round
	 * trip per client is the barrier: a {@code connect} promise resolving says nothing about the
	 * <i>server</i> having accepted, whereas an answered call means it accepted, served and dispatched —
	 * and registration precedes dispatch (FR-032).
	 */
	private static Promise<Void> connectAll(
		JsonRpcTcpServer server, HangApi clientService, List<JsonRpcClient> clients, int count
	) {
		if (count == 0) return Promise.complete();
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
					.withPeerHandler(JsonRpcDispatcher.builder(reactor)
						.withService(HangApi.class, clientService)
						.build())
					.build();
				clients.add(client);
				return client.proxy(TestApi.class).add(1, 1)
					.then(() -> connectAll(server, clientService, clients, count - 1));
			});
	}

	/** Records a drained call's failure and its position in the drain, and counts down to {@code allDrained}. */
	private static void drainOf(
		Promise<String> call, List<Exception> failures, List<String> order, String side,
		RefInt drained, SettablePromise<Void> allDrained
	) {
		call.whenComplete(($, e) -> {
			if (e != null) failures.add(e);
			order.add(side);
			if (drained.inc() == DRAINED_CALLS) allDrained.set(null);
		});
	}

	private static int totalInFlight(JsonRpcTcpServer server) {
		int total = 0;
		for (JsonRpcTcpSession session : server.sessions()) {
			total += session.inFlightCount();
		}
		return total;
	}

	private static JsonRpcDispatcher dispatcher(TestApi service) {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, service)
			.build();
	}

	/**
	 * A listening server on port {@code 0}, accepting once — the shape every test in this module uses,
	 * because a lingering accept socket keeps {@code Eventloop.isAlive()} true and hangs the suite
	 * rather than failing it.
	 */
	private static JsonRpcTcpServer server(
		JsonRpcDispatcher dispatcher, Function<JsonRpcTcpServer.Builder, JsonRpcTcpServer.Builder> options
	) {
		JsonRpcTcpServer server = options
			.apply(JsonRpcTcpServer.builder(reactor(), dispatcher).withListenPort(0).withAcceptOnce())
			.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/** Connects one client, runs {@code body} against its {@link JsonRpcClient}, and closes it after. */
	private static <T> Promise<T> withClient(JsonRpcTcpServer server, Function<JsonRpcClient, Promise<T>> body) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return body.apply(client)
					.whenComplete(($, e) -> client.closeEx(new ExpectedException("end of test")));
			});
	}

	private static void closeServer(JsonRpcTcpServer server) {
		await(server.close().toVoid());
	}

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static InetAddress loopback() {
		return InetAddress.getLoopbackAddress();
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
