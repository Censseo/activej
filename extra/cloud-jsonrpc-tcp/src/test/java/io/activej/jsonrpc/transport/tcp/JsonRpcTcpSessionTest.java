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
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.EventloopThread;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The session's core (T008): one connection, one {@link JsonRpcClient}, both directions.
 * <p>
 * A session adds no mechanism of its own (FR-050). It is the connection's transport plus one
 * {@code JsonRpcClient} built with {@code withPeerHandler(serverDispatcher)}, and <i>that one
 * component</i> is simultaneously the inbound dispatch path and the outbound server&rarr;client call
 * path. These tests pin that composition — an inbound call answered, an outbound call resolved, the
 * in-flight count observed, and the close purging through feature 012's single removal path — and
 * nothing beyond it.
 * <p>
 * <b>Completed by US4 (T018)</b> with the management half of the same surface: {@code sessions()}
 * snapshot semantics, enumeration racing a close, broadcast failure containment (and the contract-error
 * carve-out from it), and the off-reactor guard. The full server-initiated matrix stays in
 * {@link JsonRpcTcpServerInitiatedTest} (T017) and the server-drain assertions in
 * {@link JsonRpcTcpServerLifecycleTest} (T019).
 */
public final class JsonRpcTcpSessionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testInboundCallIsAnsweredThroughTheServerDispatcher() {
		// FR-050: the session's client dispatches what it receives through the server's dispatcher — the
		// same single service table every session on the server shares.
		JsonRpcTcpServer server = server();

		TestApi.AddResult result = await(withClient(server, null, client ->
			client.proxy(TestApi.class).add(20, 22)));

		assertEquals(42, result.sum());
		closeServer(server);
	}

	@Test
	public void testServerInitiatedCallResolvesThroughTheSessionProxy() {
		// FR-050/FR-051: the same component answers the reverse direction. The server's proxy call and the
		// client's own call draw ids from two independent counters — two clients, two correlation tables —
		// which is what makes a bidirectional connection possible without a second mechanism.
		JsonRpcTcpServer server = server();
		Ref<String> decided = new Ref<>();

		await(withClient(server, new ClientApiImpl(), client ->
			client.proxy(TestApi.class).add(1, 1)
				.then(() -> session(server).proxy(ClientApi.class).decide(7))
				.whenResult(decided::set)
				.toVoid()));

		assertEquals("decided-7", decided.get());
		closeServer(server);
	}

	@Test
	public void testInFlightCountTracksServerInitiatedCalls() {
		// FR-034: inFlightCount() is the observed value — the diagnostic that makes "the table is empty"
		// an assertion rather than an inference, and where feature 09's bound will later attach.
		JsonRpcTcpServer server = server();
		RefInt idle = new RefInt(-1);
		RefInt inFlight = new RefInt(-1);

		await(withClient(server, hangingClientApi(), client ->
			client.proxy(TestApi.class).add(1, 1)
				.then(() -> {
					JsonRpcTcpSession session = session(server);
					idle.set(session.inFlightCount());
					Promise<String> neverAnswered = session.proxy(ClientApi.class).decide(7);
					inFlight.set(session.inFlightCount());
					session.closeEx(new ExpectedException("end of test"));
					return neverAnswered.map($ -> null, e -> null);
				})
				.toVoid()));

		assertEquals("an idle session has nothing in flight", 0, idle.get());
		assertEquals("the server-initiated call is counted", 1, inFlight.get());
		closeServer(server);
	}

	@Test
	public void testCloseIsIdempotentAndPurgesServerInitiatedCalls() {
		// FR-053: closing purges every server-initiated call through feature 012's single removal path,
		// with the close cause. Purging is a lifecycle act, not a timeout — there is no deadline here and
		// none is implied (feature 09 owns that).
		JsonRpcTcpServer server = server();
		Ref<Exception> failure = new Ref<>();
		RefInt afterClose = new RefInt(-1);
		ExpectedException expected = new ExpectedException("the session was closed");

		await(withClient(server, hangingClientApi(), client ->
			client.proxy(TestApi.class).add(1, 1)
				.then(() -> {
					JsonRpcTcpSession session = session(server);
					Promise<String> neverAnswered = session.proxy(ClientApi.class).decide(7);
					session.closeEx(expected);
					session.closeEx(new ExpectedException("a second close is a no-op"));
					afterClose.set(session.inFlightCount());
					return neverAnswered.map($ -> null, e -> {
						failure.set(e);
						return null;
					});
				})
				.toVoid()));

		assertSame("the in-flight call failed with the close cause", expected, failure.get());
		assertEquals("the correlation table is empty after the purge", 0, afterClose.get());
		assertTrue("the session left the registry", server.sessions().isEmpty());
		closeServer(server);
	}

	@Test
	public void testCallsOnAClosedSessionFailImmediately() {
		// FR-055: a server-initiated call must not outlive its session. There is no queue of calls waiting
		// for a connection that is gone, so the call fails at once — with the close cause, synchronously.
		JsonRpcTcpServer server = server();
		Ref<JsonRpcTcpSession> closedSession = new Ref<>();
		ExpectedException expected = new ExpectedException("the session was closed");

		await(withClient(server, new ClientApiImpl(), client ->
			client.proxy(TestApi.class).add(1, 1)
				.whenResult(() -> {
					JsonRpcTcpSession session = session(server);
					closedSession.set(session);
					session.closeEx(expected);
				})
				.toVoid()));

		Promise<String> refused = closedSession.get().proxy(ClientApi.class).decide(7);

		assertTrue("the refusal is immediate", refused.isException());
		assertSame(expected, refused.getException());
		closeServer(server);
	}

	// -------------------------------------------------------------------------------------------
	// US4 (T018): the management surface — enumeration, broadcast, and the reactor guard.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSessionsIsASnapshotUnaffectedByLaterRegistrationsAndClosures() {
		// FR-033: sessions() publishes a reactor-confined, read-only COPY. Both directions are pinned
		// here, because a snapshot that tracked either one would be a live view under another name: a
		// registration after the call does not grow it, and a deregistration after the call does not
		// shrink it. That is what makes an iteration impossible to invalidate by a connection dying
		// inside it — which is exactly what broadcast relies on.
		JsonRpcTcpServer server = multiSessionServer(null);
		Set<JsonRpcTcpSession> known = new HashSet<>();
		Ref<Set<JsonRpcTcpSession>> snapshot = new Ref<>();
		RefInt liveAfterSecondConnect = new RefInt(-1);
		RefInt snapshotAfterSecondConnect = new RefInt(-1);
		RefInt liveAfterClose = new RefInt(-1);
		RefInt snapshotAfterClose = new RefInt(-1);

		await(connect(server, new ClientApiImpl(), known)
			.then(a -> {
				snapshot.set(server.sessions());                       // {A}
				return connect(server, new ClientApiImpl(), known)
					.then(b -> {
						snapshotAfterSecondConnect.set(snapshot.get().size());
						liveAfterSecondConnect.set(server.sessions().size());
						a.session().closeEx(new ExpectedException("A hangs up"));
						snapshotAfterClose.set(snapshot.get().size());
						liveAfterClose.set(server.sessions().size());
						return shutdown(server, a, b);
					});
			}));

		assertEquals("the snapshot was taken while only A was connected", 1, snapshot.get().size());
		assertEquals("a registration after the call did not grow the snapshot", 1, snapshotAfterSecondConnect.get());
		assertEquals("the live set did see the second registration", 2, liveAfterSecondConnect.get());
		assertEquals("a deregistration after the call did not shrink the snapshot", 1, snapshotAfterClose.get());
		assertEquals("the live set did see A leave", 1, liveAfterClose.get());
		expectReadOnly(snapshot.get());
	}

	@Test
	public void testEnumerationRacingACloseNeitherThrowsNorTears() {
		// FR-032/FR-033: a snapshot is iterated while the live registry is being mutated by the very
		// closes that iteration triggers — the concurrent-modification shape, on the one thread where it
		// could still bite through re-entrancy rather than through threads.
		//
		// Each of the three sessions carries a server-initiated call that will never be answered, and each
		// call's failure continuation re-enumerates from INSIDE its own session's close. That re-entrant
		// enumeration is the sharpest form of "racing a close": the purge runs before deregistration (the
		// client's onClosed handling precedes the server's, by construction of CloseObservingTransport),
		// so each continuation sees its own session still registered and every not-yet-closed one too —
		// 3, then 2, then 1, whatever order the snapshot happens to enumerate in. A torn state would show
		// up as a repeated or skipped number; a ConcurrentModificationException would fail outright.
		JsonRpcTcpServer server = multiSessionServer(null);
		Set<JsonRpcTcpSession> known = new HashSet<>();
		List<Integer> observedFromInsideAClose = new ArrayList<>();
		RefInt sessionsBeforeTheSweep = new RefInt(-1);

		await(connect(server, hangingClientApi(), known)
			.then(a -> connect(server, hangingClientApi(), known)
				.then(b -> connect(server, hangingClientApi(), known)
					.then(c -> {
						Set<JsonRpcTcpSession> snapshot = server.sessions();
						sessionsBeforeTheSweep.set(snapshot.size());
						List<Promise<Void>> purged = new ArrayList<>();
						for (JsonRpcTcpSession session : snapshot) {
							purged.add(session.proxy(ClientApi.class).decide(7)
								.map($ -> null, e -> {
									observedFromInsideAClose.add(server.sessions().size());
									return null;
								}));
						}
						// the sweep: the live registry is mutated by every iteration of the snapshot
						for (JsonRpcTcpSession session : snapshot) {
							session.closeEx(new ExpectedException("the sweep closes this one"));
						}
						return Promises.all(purged).then(() -> shutdown(server, a, b, c));
					}))));

		assertEquals("three live sessions were enumerated", 3, sessionsBeforeTheSweep.get());
		assertEquals("every re-entrant enumeration saw a coherent, monotonically shrinking registry",
			List.of(3, 2, 1), observedFromInsideAClose);
		assertTrue("the sweep emptied the registry", server.sessions().isEmpty());
	}

	@Test
	public void testBroadcastFailureIsContainedToTheFailingSession() {
		// US4 scenario 4 / FR-033: broadcast invokes the caller's own code once per session, and that code
		// may fail. One session's invocation must not cost the others their message: the failure is routed
		// to that session's failure handling and the iteration continues.
		//
		// The failing session is identified by the identity of its proxy — a JsonRpcClient caches one proxy
		// per interface, so the instance broadcast hands the invocation for session A IS the one captured
		// beforehand. That makes "exactly A fails" deterministic without depending on the snapshot's
		// (unspecified) iteration order.
		List<Exception> failures = new ArrayList<>();
		JsonRpcTcpServer server = multiSessionServer(failures::add);
		Set<JsonRpcTcpSession> known = new HashSet<>();
		ClientApiImpl eventsA = new ClientApiImpl();
		ClientApiImpl eventsB = new ClientApiImpl();
		RefInt sessionsAfterBroadcast = new RefInt(-1);
		// deliberately NOT io.activej.test.ExpectedException: that one is checked, and broadcast's
		// invocation is a Consumer — the only failure it can express synchronously is unchecked, which is
		// exactly the shape JsonRpcTcpServer.broadcast contains
		PublisherFailure publisherFailure = new PublisherFailure();

		await(connect(server, eventsA, known)
			.then(a -> connect(server, eventsB, known)
				.then(b -> {
					ClientApi doomed = a.session().proxy(ClientApi.class);
					Promise<Void> delivered = eventsB.firstEvent();
					server.broadcast(ClientApi.class, api -> {
						if (api == doomed) throw publisherFailure;
						api.event(42);
					});
					sessionsAfterBroadcast.set(server.sessions().size());
					return delivered.then(() -> shutdown(server, a, b));
				})));

		assertEquals("exactly one session's failure was reported", 1, failures.size());
		assertSame("and it was the publisher's own exception, contained not thrown",
			publisherFailure, failures.get(0));
		assertEquals("the surviving session received its notification", List.of(42L), eventsB.events());
		assertEquals("the failing session received nothing", List.of(), eventsA.events());
		assertEquals("a contained failure cost no session its registration", 2, sessionsAfterBroadcast.get());
	}

	@Test
	public void testBroadcastContractErrorReachesTheCallerNotEveryFailureHandler() {
		// FR-033's containment is for per-session faults — a dead connection, the publisher's own throw.
		// A broken broadcast interface is the broadcaster's programming error, and every session's
		// proxy(...) refuses it identically (the contract is a property of the interface): the caller,
		// the only one who can fix it, gets the JsonRpcContractException at the first session — once —
		// rather than N failure-handler reports no operator can act on.
		List<Exception> failures = new ArrayList<>();
		JsonRpcTcpServer server = multiSessionServer(failures::add);
		Set<JsonRpcTcpSession> known = new HashSet<>();
		ClientApiImpl eventsA = new ClientApiImpl();
		ClientApiImpl eventsB = new ClientApiImpl();
		Ref<JsonRpcContractException> contractError = new Ref<>();

		await(connect(server, eventsA, known)
			.then(a -> connect(server, eventsB, known)
				.then(b -> {
					try {
						server.broadcast(BrokenClientApi.class, api -> api.event(1));
					} catch (JsonRpcContractException e) {
						contractError.set(e);
					}
					return shutdown(server, a, b);
				})));

		assertNotNull("the broken interface reached the broadcast caller", contractError.get());
		assertEquals("no contract fault was routed to a session's failure handling", List.of(), failures);
		assertEquals("no session was invoked before the refusal", List.of(), eventsA.events());
		assertEquals("no session was invoked before the refusal", List.of(), eventsB.events());
	}

	@Test
	public void testOffReactorAccessToTheRegistryAndASessionFailsFast() {
		// FR-037 / WI-1: a publisher on a foreign thread must hop explicitly (reactor.submit /
		// BlockingReactorExecutor); every public method failing fast off-reactor is the enforcement.
		//
		// EventloopRule cannot reproduce a cross-thread violation — Reactor.inReactorThread() is trivially
		// true when the loop has never actively run — so the server and its session live on a real reactor
		// running on its own dedicated thread (EventloopThread), and the JUnit thread is exactly the
		// foreign thread the guards must reject. This is `extra/cloud-jsonrpc`'s documented reactor-guard
		// gotcha, second application.
		//
		// The connection is an idle socket stub rather than a real one: a real socket would need the JUnit
		// thread to drive it while blocked, and what is under test is the guard, not the medium. The
		// transport, the session and the registry are all the production ones.
		EventloopThread loop = EventloopThread.create("tcp-session-guard");
		Ref<JsonRpcTcpServer> server = new Ref<>();
		Ref<JsonRpcTcpSession> session = new Ref<>();
		try {
			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
					.build();
				// never listen(): no port, no accept socket — serve() is the registration path under test
				server.set(JsonRpcTcpServer.builder(loop.eventloop(), dispatcher).build());
				server.get().serve(new IdleTcpSocket(), InetAddress.getLoopbackAddress());
				session.set(server.get().sessions().iterator().next());
			});
			assertNotNull("the stub connection registered a session on the foreign loop", session.get());

			expectIllegalState(() -> server.get().sessions());
			expectIllegalState(() -> server.get().broadcast(ClientApi.class, api -> api.event(1)));
			expectIllegalState(() -> session.get().proxy(ClientApi.class));
			expectIllegalState(() -> session.get().inFlightCount());
			expectIllegalState(() -> session.get().closeEx(new ExpectedException("from the wrong thread")));
		} finally {
			loop.submit(() -> {
				if (session.get() != null) session.get().closeEx(new AsyncCloseException("guard test teardown"));
				if (server.get() != null) server.get().close();
			});
			loop.close();
		}
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * A publisher's own failure, thrown from inside a broadcast invocation. Unchecked by necessity:
	 * {@code broadcast}'s invocation is a {@link Consumer}, so an unchecked throw is the only failure it
	 * can express synchronously — and the only one {@code JsonRpcTcpServer.broadcast} can contain.
	 */
	private static final class PublisherFailure extends RuntimeException {
		private PublisherFailure() {
			super("this session's publisher blew up");
		}
	}

	/**
	 * An interface that breaks the shared contract (rule 2, FR-022): an abstract method carrying
	 * neither annotation. {@code proxy(BrokenClientApi.class)} refuses it identically on every session —
	 * the shape a broadcast must not mistake for a per-session fault.
	 */
	@JsonRpcService("broken")
	private interface BrokenClientApi {
		void event(long id);
	}

	/** A {@link ClientApi} that never answers — the in-flight call a purge has to complete. */
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

	private static JsonRpcTcpSession session(JsonRpcTcpServer server) {
		return server.sessions().iterator().next();
	}

	private static JsonRpcTcpServer server() {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
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

	/**
	 * Connects one client. When {@code clientService} is non-{@code null} the client answers the
	 * server's own calls through its own dispatcher — the wiring that makes the connection duplex
	 * (FR-063).
	 */
	private static <T> Promise<T> withClient(
		JsonRpcTcpServer server, ClientApi clientService, Function<JsonRpcClient, Promise<T>> body
	) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient.Builder builder = JsonRpcClient.builder(reactor, transport);
				if (clientService != null) {
					builder.withPeerHandler(JsonRpcDispatcher.builder(reactor)
						.withService(ClientApi.class, clientService)
						.build());
				}
				JsonRpcClient client = builder.build();
				return body.apply(client)
					.whenComplete(($, e) -> client.closeEx(new ExpectedException("end of test")));
			});
	}

	/**
	 * A server that keeps accepting — the shape the enumeration and broadcast tests need, since one
	 * session cannot demonstrate a registry. Its accept socket is closed <b>inside</b> the awaited chain
	 * by {@link #shutdown}, because a lingering accept socket keeps {@code Eventloop.isAlive()} true and
	 * hangs the suite rather than failing it.
	 */
	private static JsonRpcTcpServer multiSessionServer(@Nullable Consumer<Exception> failureHandler) {
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcTcpServer.Builder builder = JsonRpcTcpServer.builder(reactor(), dispatcher).withListenPort(0);
		if (failureHandler != null) builder.withFailureHandler(failureHandler);
		JsonRpcTcpServer server = builder.build();
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return server;
	}

	/** One connected peer: the client, and the server-side session that connection produced. */
	private record Peer(JsonRpcClient client, JsonRpcTcpSession session) {}

	/**
	 * Connects one client answering {@code clientService}, and identifies the session it produced as the
	 * one the registry gained. A real round trip is the barrier: a client's {@code connect} promise
	 * resolving says nothing about the <i>server</i> having accepted, whereas an answered call means the
	 * server accepted, served and dispatched — and registration precedes dispatch (FR-032).
	 */
	private static Promise<Peer> connect(
		JsonRpcTcpServer server, ClientApi clientService, Set<JsonRpcTcpSession> known
	) {
		NioReactor reactor = reactor();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport)
					.withPeerHandler(JsonRpcDispatcher.builder(reactor)
						.withService(ClientApi.class, clientService)
						.build())
					.build();
				return client.proxy(TestApi.class).add(1, 1)
					.map($ -> {
						JsonRpcTcpSession added = server.sessions().stream()
							.filter(session -> !known.contains(session))
							.findFirst()
							.orElseThrow(() -> new AssertionError("the connection registered no session"));
						known.add(added);
						return new Peer(client, added);
					});
			});
	}

	/** Closes every client and then the server, inside the awaited chain, so the loop can quiesce. */
	private static Promise<Void> shutdown(JsonRpcTcpServer server, Peer... peers) {
		for (Peer peer : peers) {
			peer.client().closeEx(new ExpectedException("end of test"));
		}
		return server.close().toVoid();
	}

	/** Requires {@code snapshot} to refuse mutation — a copy that could be written to is not a snapshot. */
	private static void expectReadOnly(Set<JsonRpcTcpSession> snapshot) {
		try {
			snapshot.clear();
		} catch (UnsupportedOperationException expected) {
			return;
		}
		fail("sessions() must publish a read-only snapshot (FR-033)");
	}

	/** Calls {@code action}, here on the JUnit thread, and requires exactly an {@link IllegalStateException}. */
	private static void expectIllegalState(Runnable action) {
		try {
			action.run();
		} catch (Throwable caught) {
			assertSame("checkInReactorThread must reject a foreign thread with IllegalStateException, not " +
					   caught.getClass(),
				IllegalStateException.class, caught.getClass());
			return;
		}
		fail("expected a call from the JUnit thread (never the reactor thread here) to throw, got nothing");
	}

	/**
	 * A connection that is up but silent: its read never resolves, so nothing is ever decoded and the
	 * session simply exists. What the guard test needs is a registered session on a foreign reactor, not a
	 * medium — the transport, the session and the registry above it are all the production ones. Every
	 * {@code write} recycles the buffer it is given, because a socket that swallows a write still owns it
	 * (DI-1).
	 */
	private static final class IdleTcpSocket implements ITcpSocket {
		@Override
		public Promise<ByteBuf> read() {
			return new SettablePromise<>();
		}

		@Override
		public Promise<Void> write(@Nullable ByteBuf buf) {
			if (buf != null) buf.recycle();
			return Promise.complete();
		}

		@Override
		public boolean isReadAvailable() {
			return false;
		}

		@Override
		public boolean isClosed() {
			return false;
		}

		@Override
		public void closeEx(Exception e) {
			// nothing underneath to release: this socket is a stub of the medium, not of the transport
		}
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
