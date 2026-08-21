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
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.eventloop.Eventloop;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketServlet;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcContractException;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.ClientApi;
import io.activej.jsonrpc.transport.ws.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.StubWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.UserEvents;
import io.activej.jsonrpc.transport.ws.fixtures.UserEventsImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static io.activej.test.TestUtils.assertCompleteFn;
import static java.util.List.of;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The session contract (T008, US1): a session is created on upgrade and deregistered exactly once
 * on close (FR-032), the live set is published as a snapshot (FR-033), a broadcast reaches every
 * session with one session's failure contained (FR-033, US1 scenario 3) and a broken broadcast
 * interface reaching the caller instead of the failure handlers, a client with no handler
 * ignores the notification and stays connected (US1 scenario 2), and a dispatcher from another
 * reactor is refused at build under {@code CHECKS} (FR-031). Rules per FR-079.
 * <p>
 * Two clients on one servlet are two {@link WsPair} servers (each {@code acceptOnce}) sharing the
 * same {@link JsonRpcWsServlet} instance — one servlet, one registry, two real sockets (R3 keeps
 * every awaited chain closed before it returns; the {@code acceptOnce} accept sockets are gone after
 * their one connect, and {@code closeAll()} on each pair closes the rest).
 */
public final class JsonRpcWsSessionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testSessionRegisteredOnUpgradeAndDeregisteredOnClose() {
		// FR-032: each accepted upgrade creates exactly one session, registered before any inbound
		// dispatch and deregistered exactly once when the connection closes — local, remote or failed
		// alike. The upgrade runs onWebSocket (session creation) before the client's connect promise
		// resolves, so the then-callback observes the registered session; the awaited chain ends with
		// the clean close, so by quiescence the server has processed it and deregistered.
		JsonRpcWsServlet servlet = newServlet();
		RefInt registeredCount = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			registeredCount.set(servlet.sessions().size());   // the upgrade registered the session
			return ws.writeMessage(null);                      // clean close, code 1000
		}));

		assertEquals("the session was registered on upgrade", 1, registeredCount.get());
		assertEquals("the session was deregistered on close", 0, servlet.sessions().size());
		pair.closeAll();
	}

	@Test
	public void testSessionsReturnsASnapshotCopy() {
		// FR-033: sessions() is a reactor-confined, read-only snapshot — a copy that does not reflect
		// later mutations. A session closed after the snapshot was taken stays in it; the live set
		// reflects the close.
		JsonRpcWsServlet servlet = newServlet();
		Ref<Set<JsonRpcWsSession>> snapshot = new Ref<>();
		Ref<JsonRpcWsSession> sessionA = new Ref<>();
		RefInt liveAfterClose = new RefInt(-1);
		RefInt snapshotAfterClose = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect().then(ws -> {
			snapshot.set(servlet.sessions());                              // {A}
			sessionA.set(snapshot.get().iterator().next());
			// close the session through its handle — a synchronous close + deregistration
			sessionA.get().closeEx(new AsyncCloseException());
			liveAfterClose.set(servlet.sessions().size());
			snapshotAfterClose.set(snapshot.get().size());
			return ws.writeMessage(null);                                  // close the socket too
		}));

		assertEquals("the snapshot was taken while A was connected", 1, snapshot.get().size());
		assertEquals("the snapshot still lists the closed session (it is a copy)", 1, snapshotAfterClose.get());
		assertEquals("the live set reflects A's close", 0, liveAfterClose.get());
		pair.closeAll();
	}

	@Test
	public void testBroadcastDeliversToEverySession() {
		// FR-033 / US1 independent test: one broadcast reaches every live session exactly once, and a
		// notification is never answered — no response document travels back (§4.1). Each client runs
		// a JsonRpcClient whose peer handler is its own dispatcher implementing UserEvents, so the
		// notification's arrival is observed at the handler, not just at the transport.
		UserEventsImpl eventsA = new UserEventsImpl();
		UserEventsImpl eventsB = new UserEventsImpl();
		JsonRpcWsServlet servlet = newServlet();
		Ref<JsonRpcWsTransport> transportA = new Ref<>();
		Ref<JsonRpcWsTransport> transportB = new Ref<>();
		RefInt sessionsAtBroadcast = new RefInt(-1);
		RefInt deliveredA = new RefInt(-1);
		RefInt deliveredB = new RefInt(-1);
		WsPair pairA = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servlet);

		await(pairA.connect()
			.then(wsA -> {
				transportA.set(JsonRpcWsTransport.of(reactor(), wsA));
				setupClient(reactor(), transportA.get(), eventsA);
				return pairB.connect();
			})
			.then(wsB -> {
				transportB.set(JsonRpcWsTransport.of(reactor(), wsB));
				setupClient(reactor(), transportB.get(), eventsB);
				sessionsAtBroadcast.set(servlet.sessions().size());
				Promise<Void> aDelivered = eventsA.firstInvocation();
				Promise<Void> bDelivered = eventsB.firstInvocation();
				aDelivered.whenComplete(assertCompleteFn());
				bDelivered.whenComplete(assertCompleteFn());
				servlet.broadcast(UserEvents.class, events -> events.userChanged(42));
				return Promises.all(aDelivered, bDelivered);
			})
			.whenResult($ -> {
				deliveredA.set(eventsA.ids().size());
				deliveredB.set(eventsB.ids().size());
			})
			.whenComplete(() -> closeTransports(transportA, transportB)));

		assertEquals("both sessions were live at broadcast time", 2, sessionsAtBroadcast.get());
		assertEquals("client A's handler fired exactly once", 1, deliveredA.get());
		assertEquals("client B's handler fired exactly once", 1, deliveredB.get());
		assertEquals(of(42L), eventsA.ids());
		assertEquals(of(42L), eventsB.ids());
		pairA.closeAll();
		pairB.closeAll();
	}

	@Test
	public void testBroadcastFailureIsContainedToTheFailingSession() {
		// FR-033 / US1 scenario 3: a session that closed between enumeration and send must not prevent
		// the broadcast from reaching the remaining sessions, and the dead session's failure must be
		// routed to its failure handling — never thrown into the iteration. The stale snapshot (taken
		// before the close) still lists the dead session; invoking the notification on it is the exact
		// per-session invocation broadcast performs, and it must be contained.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		UserEventsImpl eventsA = new UserEventsImpl();
		UserEventsImpl eventsB = new UserEventsImpl();
		Ref<JsonRpcWsTransport> transportA = new Ref<>();
		Ref<JsonRpcWsTransport> transportB = new Ref<>();
		Ref<Set<JsonRpcWsSession>> staleSnapshot = new Ref<>();
		Ref<JsonRpcWsSession> sessionA = new Ref<>();
		RefInt liveAfterClose = new RefInt(-1);
		RefInt broadcastDelivered = new RefInt(-1);
		RefInt failuresObserved = new RefInt(-1);
		WsPair pairA = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servlet);

		await(pairA.connect()
			.then(wsA -> {
				transportA.set(JsonRpcWsTransport.of(reactor(), wsA));
				setupClient(reactor(), transportA.get(), eventsA);
				sessionA.set(servlet.sessions().iterator().next());   // identify A by registration order
				return pairB.connect();
			})
			.then(wsB -> {
				transportB.set(JsonRpcWsTransport.of(reactor(), wsB));
				setupClient(reactor(), transportB.get(), eventsB);
				staleSnapshot.set(servlet.sessions());                // {A, B} — the enumeration
				// A closes between enumeration and send: its transport send will now fail
				sessionA.get().closeEx(new AsyncCloseException());
				liveAfterClose.set(servlet.sessions().size());
				// broadcast to the live set — B must still receive it, no exception from A
				Promise<Void> bDelivered = eventsB.firstInvocation();
				bDelivered.whenComplete(assertCompleteFn());
				servlet.broadcast(UserEvents.class, events -> events.userChanged(42));
				return bDelivered;
			})
			.then($ -> {
				broadcastDelivered.set(eventsB.ids().size());
				// the stale snapshot still holds the dead session: invoking the notification on it is
				// contained (routed to the failure handler, not thrown), and the iteration reaches B
				for (JsonRpcWsSession session : staleSnapshot.get()) {
					session.proxy(UserEvents.class).userChanged(99);
				}
				failuresObserved.set(failures.size());
				return Promise.complete();
			})
			.whenComplete(() -> closeTransports(transportA, transportB)));

		assertEquals("the stale snapshot still lists both sessions", 2, staleSnapshot.get().size());
		assertEquals("the live set reflects A's close", 1, liveAfterClose.get());
		assertEquals("the broadcast reached the remaining session", 1, broadcastDelivered.get());
		assertEquals("the dead session's failure was contained, not thrown", 1, failuresObserved.get());
		pairA.closeAll();
		pairB.closeAll();
	}

	@Test
	public void testBroadcastContractErrorReachesTheCallerNotEveryFailureHandler() {
		// FR-033's containment is for per-session faults — a dead connection, the publisher's own throw.
		// A broken broadcast interface is the broadcaster's programming error, and every session's
		// proxy(...) refuses it identically (the contract is a property of the interface): the caller,
		// the only one who can fix it, gets the JsonRpcContractException at the first session — once —
		// rather than N failure-handler reports no operator can act on.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		UserEventsImpl eventsA = new UserEventsImpl();
		UserEventsImpl eventsB = new UserEventsImpl();
		Ref<JsonRpcWsTransport> transportA = new Ref<>();
		Ref<JsonRpcWsTransport> transportB = new Ref<>();
		Ref<JsonRpcContractException> contractError = new Ref<>();
		WsPair pairA = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servlet);

		await(pairA.connect()
			.then(wsA -> {
				transportA.set(JsonRpcWsTransport.of(reactor(), wsA));
				setupClient(reactor(), transportA.get(), eventsA);
				return pairB.connect();
			})
			.then(wsB -> {
				transportB.set(JsonRpcWsTransport.of(reactor(), wsB));
				setupClient(reactor(), transportB.get(), eventsB);
				try {
					servlet.broadcast(BrokenEvents.class, events -> events.userChanged(1));
				} catch (JsonRpcContractException e) {
					contractError.set(e);
				}
				return Promise.complete();
			})
			.whenComplete(() -> closeTransports(transportA, transportB)));

		assertNotNull("the broken interface reached the broadcast caller", contractError.get());
		assertTrue("no contract fault was routed to a session's failure handling", failures.isEmpty());
		assertTrue("no session was invoked before the refusal", eventsA.ids().isEmpty());
		assertTrue("no session was invoked before the refusal", eventsB.ids().isEmpty());
		pairA.closeAll();
		pairB.closeAll();
	}

	@Test
	public void testClientWithNoHandlerIgnoresNotificationAndStaysConnected() {
		// US1 scenario 2: a client that connected but registered no handler (no JsonRpcClient, no
		// peer handler — a raw transport listener that does nothing) receives the broadcast
		// notification and ignores it; §4.1 forbids an answer, and the connection stays healthy — a
		// second broadcast still reaches it.
		JsonRpcWsServlet servlet = newServlet();
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);
		List<byte[]> received = new ArrayList<>();
		SettablePromise<Void> first = new SettablePromise<>();
		SettablePromise<Void> second = new SettablePromise<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		RefInt receivedCount = new RefInt(-1);
		RefInt sessionsAfterBroadcast = new RefInt(-1);

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			clientTransport.get().setListener(listener(doc -> {
				received.add(doc);
				if (received.size() == 1) first.set(null);
				if (received.size() == 2) second.set(null);
			}, e -> {}));
			servlet.broadcast(UserEvents.class, events -> events.userChanged(42));
			servlet.broadcast(UserEvents.class, events -> events.userChanged(43));
			return Promises.all(first, second);
		}).whenResult($ -> {
			receivedCount.set(received.size());
			sessionsAfterBroadcast.set(servlet.sessions().size());
		}).whenComplete(() -> clientTransport.get().closeEx(new AsyncCloseException())));

		assertEquals("the no-handler client received both broadcasts", 2, receivedCount.get());
		assertEquals("the no-handler session is still live", 1, sessionsAfterBroadcast.get());
		pair.closeAll();
	}

	@Test
	public void testDispatcherOnDifferentReactorRefusedUnderChecks() {
		// FR-031: under CHECKS the builder refuses a dispatcher living on a different reactor than
		// the servlet — the two components would otherwise corrupt each other's reactor state.
		Eventloop otherEventloop = Eventloop.create();            // a second, unstarted reactor
		JsonRpcDispatcher otherDispatcher = JsonRpcDispatcher.builder(otherEventloop)
			.withService(UserEvents.class, new UserEventsImpl())
			.build();

		try {
			JsonRpcWsServlet.builder(reactor(), otherDispatcher).build();
			fail("a dispatcher on a different reactor must be refused under CHECKS (FR-031)");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void testPreClosedWebSocketIsNeverRegistered() {
		// review T030 (FR-032/FR-035): a websocket that is already closed when the session's read
		// loop starts fails readMessage() synchronously inside the session constructor — the
		// transport's close latch fires onClosed before the session ref is set, the deregistration
		// callback removes null (a no-op), and the latch is consumed. The servlet must not register
		// such a session: it would be a zombie entry that is never deregistered, a per-connection
		// leak and a failure report on every subsequent broadcast. StubWebSocket's readMessage()
		// fails synchronously once closedEx has run — exactly core-http's doRead behaviour
		// (WebSocket.java:191).
		JsonRpcWsServlet servlet = newServlet();
		StubWebSocket preClosed = new StubWebSocket();
		preClosed.closeEx(new AsyncCloseException("already closed before the upgrade reached onWebSocket"));

		servlet.onWebSocket(preClosed);

		assertTrue("a session whose close fired during construction must never be registered (FR-032): "
				   + servlet.sessions(), servlet.sessions().isEmpty());
	}

	@Test
	public void testBroadcastFloodToASlowClientIsAbsorbedAndDeliveredInOrder() {
		// A8, adversarial plan (FR-017 / FR-095 mirrored into the server→client direction): 5000
		// broadcasts issued in one tight loop, with no reactor tick between them — the client's read
		// loop is fully automatic once setListener has run, so the only way to model "slow to read"
		// from outside is to enqueue the whole flood before the reactor gets a chance to run it: the
		// client has provably drained nothing when the last notification is enqueued.
		// The only per-notification server state is one link of the transport's writeTail promise
		// chain, and it collapses as each write flushes — no second queue, no per-notification
		// object that outlives its send. The server must not throw and must not lose a message.
		//
		// The await point is a trailing client→server call: the session answers it through the SAME
		// transport, so its response document is appended to the write chain BEHIND all 5000
		// notifications. When add()'s promise resolves, every notification has already been
		// delivered — one connection, one ordered message stream, one serial read loop.
		int floodSize = 5000;
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		UserEventsImpl events = new UserEventsImpl();
		Ref<JsonRpcWsTransport> transport = new Ref<>();
		RefInt drainedDuringFlood = new RefInt(-1);
		RefInt sumAfterFlood = new RefInt(-1);
		RefInt sessionsAfterFlood = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect()
			.then(ws -> {
				transport.set(JsonRpcWsTransport.of(reactor(), ws));
				JsonRpcClient client = setupClient(reactor(), transport.get(), events);
				for (int i = 0; i < floodSize; i++) {
					long id = i;
					servlet.broadcast(UserEvents.class, userEvents -> userEvents.userChanged(id));
				}
				drainedDuringFlood.set(events.ids().size());   // the reactor never ran: nothing drained
				return client.proxy(TestApi.class).add(1, 2)
					.whenResult(result -> sumAfterFlood.set(result.sum()));
			})
			.whenResult($ -> sessionsAfterFlood.set(servlet.sessions().size()))
			.whenComplete(() -> transport.get().closeEx(new AsyncCloseException())));

		assertEquals("the client drained nothing while the flood was being enqueued", 0, drainedDuringFlood.get());
		assertEquals("every broadcast reached the client's handler", floodSize, events.ids().size());
		for (int i = 0; i < floodSize; i++) {
			assertEquals("the notifications arrived in order", (long) i, (long) events.ids().get(i));
		}
		assertEquals("the connection still answers after the flood", 3, sumAfterFlood.get());
		assertEquals("the session survived the flood", 1, sessionsAfterFlood.get());
		assertTrue("no send failure was reported: " + failures, failures.isEmpty());
		pair.closeAll();
	}

	@Test
	public void testBroadcastOfARequestResponseMethodIsAnsweredAndLeavesNoTableEntry() {
		// C3, adversarial plan (FR-052): broadcast's invocation is a Consumer, so a
		// request/response method invoked through it has its Promise discarded at the call site.
		// The correlation entry is still created (JsonRpcClient registers before it sends), the
		// client cannot tell the difference — from its side this is an ordinary server-initiated
		// call — and the answer settles a promise nobody holds. Nothing observable may break: no
		// exception escapes the iteration, nothing reaches the failure handler (the caller threw the
		// promise away, the transport did not fail), and the table slot must be released, not leaked.
		//
		// The discarded promise cannot be awaited, so the round trip is pinned twice: the client's
		// own implementation records the invocation, and a SECOND, awaited call on the same session
		// guarantees the discarded call's answer was processed first (one ordered stream) — which is
		// when inFlightCount() must be back to 0.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		RecordingClientApi clientApi = new RecordingClientApi();
		Ref<JsonRpcWsTransport> transport = new Ref<>();
		Ref<String> awaitedResult = new Ref<>();
		RefInt inFlightBeforeBroadcast = new RefInt(-1);
		RefInt inFlightAfterBroadcast = new RefInt(-1);
		RefInt inFlightAfterRoundTrip = new RefInt(-1);
		WsPair pair = WsPair.serverUpgrade(reactor(), servlet);

		await(pair.connect()
			.then(ws -> {
				transport.set(JsonRpcWsTransport.of(reactor(), ws));
				setupClient(reactor(), transport.get(), clientApi);
				JsonRpcWsSession session = servlet.sessions().iterator().next();
				inFlightBeforeBroadcast.set(session.inFlightCount());
				// the returned Promise is discarded — Consumer<T> has nowhere to put it (FR-052)
				servlet.broadcast(ClientApi.class, api -> api.decide(42));
				inFlightAfterBroadcast.set(session.inFlightCount());
				return session.proxy(ClientApi.class).decide(7)
					.whenResult(awaitedResult::set)
					.whenResult($ -> inFlightAfterRoundTrip.set(session.inFlightCount()));
			})
			.whenComplete(() -> transport.get().closeEx(new AsyncCloseException())));

		assertEquals("nothing was in flight before the broadcast", 0, inFlightBeforeBroadcast.get());
		assertEquals("the discarded call still took its table slot", 1, inFlightAfterBroadcast.get());
		assertEquals("the client answered the broadcast call like any other", of(42, 7), clientApi.decided());
		assertEquals("the awaited second call resolved normally", "decided-7", awaitedResult.get());
		assertEquals("the answered-but-orphaned call released its slot", 0, inFlightAfterRoundTrip.get());
		assertTrue("nothing reached the failure handler: " + failures, failures.isEmpty());
		pair.closeAll();
	}

	@Test
	public void testTwoServletsSharingOneDispatcherKeepIndependentRegistries() {
		// C4, adversarial plan (FR-030): two JsonRpcWsServlet instances built from the SAME
		// JsonRpcDispatcher, each on its own WsPair — two server sockets, two registries. Each
		// servlet must see only its own connection, a broadcast on one must never reach the other's
		// client, and the shared dispatcher must still answer client-initiated calls through BOTH
		// connections: it holds no per-session state, it is a service table.
		//
		// "B received nothing" is pinned by a full round trip on B's own connection, issued after
		// A's broadcast was delivered: B's socket has demonstrably been serviced, so a broadcast
		// leaking across registries would already have arrived.
		JsonRpcDispatcher sharedDispatcher = serverDispatcher();
		JsonRpcWsServlet servletA = JsonRpcWsServlet.builder(reactor(), sharedDispatcher).build();
		JsonRpcWsServlet servletB = JsonRpcWsServlet.builder(reactor(), sharedDispatcher).build();
		UserEventsImpl eventsA = new UserEventsImpl();
		UserEventsImpl eventsB = new UserEventsImpl();
		Ref<JsonRpcWsTransport> transportA = new Ref<>();
		Ref<JsonRpcWsTransport> transportB = new Ref<>();
		Ref<JsonRpcClient> clientA = new Ref<>();
		Ref<JsonRpcClient> clientB = new Ref<>();
		Ref<Set<JsonRpcWsSession>> sessionsOfA = new Ref<>();
		Ref<Set<JsonRpcWsSession>> sessionsOfB = new Ref<>();
		RefInt sumThroughA = new RefInt(-1);
		RefInt sumThroughB = new RefInt(-1);
		WsPair pairA = WsPair.serverUpgrade(reactor(), servletA);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servletB);

		await(pairA.connect()
			.then(wsA -> {
				transportA.set(JsonRpcWsTransport.of(reactor(), wsA));
				clientA.set(setupClient(reactor(), transportA.get(), eventsA));
				return pairB.connect();
			})
			.then(wsB -> {
				transportB.set(JsonRpcWsTransport.of(reactor(), wsB));
				clientB.set(setupClient(reactor(), transportB.get(), eventsB));
				sessionsOfA.set(servletA.sessions());
				sessionsOfB.set(servletB.sessions());
				Promise<Void> aDelivered = eventsA.firstInvocation();
				aDelivered.whenComplete(assertCompleteFn());
				servletA.broadcast(UserEvents.class, userEvents -> userEvents.userChanged(42));
				return aDelivered;
			})
			// the shared dispatcher answers both directions of traffic it has never seen before
			.then($ -> Promises.all(
				clientA.get().proxy(TestApi.class).add(1, 2).whenResult(r -> sumThroughA.set(r.sum())),
				clientB.get().proxy(TestApi.class).add(3, 4).whenResult(r -> sumThroughB.set(r.sum()))))
			.whenComplete(() -> closeTransports(transportA, transportB)));

		assertEquals("servlet A's registry holds exactly its own connection", 1, sessionsOfA.get().size());
		assertEquals("servlet B's registry holds exactly its own connection", 1, sessionsOfB.get().size());
		assertTrue("the two registries share no session",
			Collections.disjoint(sessionsOfA.get(), sessionsOfB.get()));
		assertEquals("A's broadcast reached A's client", of(42L), eventsA.ids());
		assertTrue("A's broadcast never crossed into B's registry: " + eventsB.ids(), eventsB.ids().isEmpty());
		assertEquals("the shared dispatcher answered through connection A", 3, sumThroughA.get());
		assertEquals("the shared dispatcher answered through connection B", 7, sumThroughB.get());
		pairA.closeAll();
		pairB.closeAll();
	}

	@Test
	public void testSessionBecomesVisibleExactlyWhenOnWebSocketReturns() {
		// C6, adversarial plan (FR-032/FR-033): the row asks whether the session *being registered* is
		// seen by a sessions() call made during the upgrade, and demands the order relative to
		// sessions.add(session) be pinned rather than assumed. Both readings of "during" are pinned here.
		//
		// phase 1 — INSIDE onWebSocket's own stack frame. JsonRpcWsServlet is final and onWebSocket has
		// no user-overridable hook of its own, but the servlet's dispatcher IS user code and it is
		// reached from inside the session constructor: the constructor builds the per-session client,
		// whose doBuild() installs itself as the transport's listener and starts the read loop — so a
		// websocket whose first readMessage() resolves SYNCHRONOUSLY dispatches an inbound notification
		// before the constructor returns, i.e. before `sessions.add(session)` ever runs. That is the
		// literal scenario, reached through a public seam (withService), and the answer is: the registry
		// is still EMPTY — registration is the last thing onWebSocket does.
		//
		// phase 2 — the closest reachable variant of "immediately after": a composed WebSocketServlet
		// gate (the composition pattern of JsonRpcWsSessionManagementTest's admission-gate tests) whose
		// own onWebSocket calls the servlet's onWebSocket and then, synchronously, asks sessions(). The
		// session IS visible there. Together: registered last, and complete on return.
		Ref<JsonRpcWsServlet> servletRef = new Ref<>();
		RefInt sessionsDuringUpgrade = new RefInt(-1);
		UserEvents probe = id -> sessionsDuringUpgrade.set(servletRef.get().sessions().size());
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.withService(UserEvents.class, probe)
				.build())
			.build();
		servletRef.set(servlet);

		servlet.onWebSocket(new SynchronousInboundWebSocket(UPGRADE_PROBE));

		assertEquals("the probe notification was dispatched inside onWebSocket and saw an empty registry "
					 + "(-1 would mean it was never dispatched synchronously at all)",
			0, sessionsDuringUpgrade.get());
		assertEquals("the session is in the registry by the time onWebSocket returns", 1, servlet.sessions().size());
		servlet.sessions().iterator().next().closeEx(new AsyncCloseException());
		assertEquals("and it deregisters through the one close path", 0, servlet.sessions().size());

		// phase 2 — a real upgrade through a gate that asks the moment onWebSocket returns
		JsonRpcWsServlet gated = newServlet();
		RefInt sessionsRightAfterOnWebSocket = new RefInt(-1);
		WebSocketServlet gate = new WebSocketServlet(reactor()) {
			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				gated.onWebSocket(webSocket);                                // the real upgrade path
				sessionsRightAfterOnWebSocket.set(gated.sessions().size());   // ... asked synchronously after it
			}
		};
		WsPair pair = WsPair.serverUpgrade(reactor(), gate);

		await(pair.connect().then(ws -> ws.writeMessage(null)));           // clean close, code 1000

		assertEquals("the session is visible to the caller the instant onWebSocket returns",
			1, sessionsRightAfterOnWebSocket.get());
		assertEquals("the clean close deregistered it", 0, gated.sessions().size());
		pair.closeAll();
	}

	@Test
	public void testReentrantBroadcastFromAClientHandlerDeliversBothInOrder() {
		// G6, adversarial plan (FR-033): a broadcast's per-session invocation ends up in client code, and
		// that client code calls broadcast() again. Session A's client-side UserEvents handler answers the
		// FIRST broadcast (id 42) by issuing a SECOND broadcast (id 43) on the same servlet, from inside
		// that handler invocation. Neither call may throw or corrupt state, and each must take its OWN
		// independent snapshot: uninvolved session B receives both notifications too, in order, which is
		// what proves the second broadcast enumerated the full live set rather than inheriting anything
		// from the first one's still-unfinished iteration. The recursion is bounded by the CALLER's own
		// guard (the `id != 42` early return below) — the servlet has no such guard and needs none.
		List<Exception> failures = new ArrayList<>();
		JsonRpcWsServlet servlet = JsonRpcWsServlet.builder(reactor(), serverDispatcher())
			.withFailureHandler(failures::add)
			.build();
		RefInt reentrantBroadcasts = new RefInt(0);
		RefInt sessionsAtReentrantBroadcast = new RefInt(-1);
		CountingUserEvents eventsA = new CountingUserEvents(id -> {
			if (id != 42) return;                                   // the caller's own bound on the recursion
			reentrantBroadcasts.inc();
			sessionsAtReentrantBroadcast.set(servlet.sessions().size());
			servlet.broadcast(UserEvents.class, events -> events.userChanged(43));
		});
		CountingUserEvents eventsB = new CountingUserEvents();
		Ref<JsonRpcWsTransport> transportA = new Ref<>();
		Ref<JsonRpcWsTransport> transportB = new Ref<>();
		RefInt sessionsAfterBothBroadcasts = new RefInt(-1);
		WsPair pairA = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servlet);

		await(pairA.connect()
			.then(wsA -> {
				transportA.set(JsonRpcWsTransport.of(reactor(), wsA));
				setupClient(reactor(), transportA.get(), eventsA);
				return pairB.connect();
			})
			.then(wsB -> {
				transportB.set(JsonRpcWsTransport.of(reactor(), wsB));
				setupClient(reactor(), transportB.get(), eventsB);
				Promise<Void> bothOnA = eventsA.deliveries(2);
				Promise<Void> bothOnB = eventsB.deliveries(2);
				bothOnA.whenComplete(assertCompleteFn());
				bothOnB.whenComplete(assertCompleteFn());
				servlet.broadcast(UserEvents.class, events -> events.userChanged(42));
				return Promises.all(bothOnA, bothOnB);
			})
			.whenResult($ -> sessionsAfterBothBroadcasts.set(servlet.sessions().size()))
			.whenComplete(() -> closeTransports(transportA, transportB)));

		assertEquals("the reentrant broadcast was issued exactly once", 1, reentrantBroadcasts.get());
		assertEquals("the reentrant broadcast enumerated the whole live set itself", 2, sessionsAtReentrantBroadcast.get());
		assertEquals("the reentrant caller received both notifications, in order", of(42L, 43L), eventsA.ids());
		assertEquals("the uninvolved session received both notifications, in order", of(42L, 43L), eventsB.ids());
		assertEquals("both sessions survived the reentrancy", 2, sessionsAfterBothBroadcasts.get());
		assertTrue("no broadcast reported a failure: " + failures, failures.isEmpty());
		pairA.closeAll();
		pairB.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring helpers.
	// ---------------------------------------------------------------------------------------------------

	private static JsonRpcWsServlet newServlet() {
		return JsonRpcWsServlet.builder(reactor(), serverDispatcher()).build();
	}

	private static JsonRpcDispatcher serverDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
	}

	/** A client's full inbound wiring: the transport feeds a client whose peer handler is a dispatcher. */
	private static JsonRpcClient setupClient(NioReactor reactor, JsonRpcWsTransport transport, UserEvents events) {
		return JsonRpcClient.builder(reactor, transport)
			.withPeerHandler(JsonRpcDispatcher.builder(reactor)
				.withService(UserEvents.class, events)
				.build())
			.build();
	}

	/** A client's inbound wiring for the server-initiated direction: {@link ClientApi} on the peer handler. */
	private static JsonRpcClient setupClient(NioReactor reactor, JsonRpcWsTransport transport, ClientApi clientApi) {
		return JsonRpcClient.builder(reactor, transport)
			.withPeerHandler(JsonRpcDispatcher.builder(reactor)
				.withService(ClientApi.class, clientApi)
				.build())
			.build();
	}

	private static void closeTransports(Ref<JsonRpcWsTransport> transportA, Ref<JsonRpcWsTransport> transportB) {
		transportA.get().closeEx(new AsyncCloseException());
		transportB.get().closeEx(new AsyncCloseException());
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	/**
	 * An interface that breaks the shared contract (rule 2, FR-022): an abstract method carrying
	 * neither annotation. {@code proxy(BrokenEvents.class)} refuses it identically on every session —
	 * the shape a broadcast must not mistake for a per-session fault.
	 */
	@JsonRpcService("broken")
	private interface BrokenEvents {
		void userChanged(long id);
	}

	private static JsonRpcTransport.Listener listener(Consumer<byte[]> onDocument, Consumer<@Nullable Exception> onClosed) {
		return new JsonRpcTransport.Listener() {
			@Override
			public void onDocument(byte[] document) {
				onDocument.accept(document);
			}

			@Override
			public void onClosed(@Nullable Exception e) {
				onClosed.accept(e);
			}
		};
	}

	/**
	 * {@link ClientApiImpl} that records every server-initiated {@code client.decide} it answered — the
	 * only way to observe a broadcast whose returned {@code Promise} the caller discarded (C3).
	 */
	private static final class RecordingClientApi implements ClientApi {
		private final ClientApi delegate = new ClientApiImpl();
		private final List<Integer> decided = new ArrayList<>();

		@Override
		public Promise<String> decide(int n) {
			decided.add(n);
			return delegate.decide(n);
		}

		@Override
		public Promise<String> fail() {
			return delegate.fail();
		}

		/** Every {@code client.decide} argument answered so far, in order. */
		List<Integer> decided() {
			return decided;
		}
	}

	/** The one inbound document of C6's phase 1: a notification, so the probe answers nothing at all. */
	private static final String UPGRADE_PROBE = "{\"jsonrpc\":\"2.0\",\"method\":\"userEvents.changed\",\"params\":{\"id\":1}}";

	/**
	 * An {@link IWebSocket} whose <b>first</b> {@code readMessage()} resolves synchronously with one TEXT
	 * message and whose second one parks forever — the only way to run user code (the servlet's own
	 * dispatcher) inside {@code onWebSocket}'s stack frame, since {@link JsonRpcWsServlet} is final and
	 * its {@code onWebSocket} exposes no hook (C6). A completed {@code Promise} short-circuits, so the
	 * transport's read-loop callback runs in the caller's frame — inside the session constructor.
	 * Nothing here touches the reactor's selector, so the test needs no socket and no quiescence step.
	 */
	private static final class SynchronousInboundWebSocket implements IWebSocket {
		private final String firstMessage;
		private boolean delivered;
		private @Nullable Exception exception;

		private SynchronousInboundWebSocket(String firstMessage) {
			this.firstMessage = firstMessage;
		}

		@Override
		public Promise<Message> readMessage() {
			if (exception != null) return Promise.ofException(exception);
			if (delivered) return new SettablePromise<>();      // the loop parks: no second document arrives
			delivered = true;
			return Promise.of(Message.text(firstMessage));      // resolved: the callback runs in THIS frame
		}

		@Override
		public Promise<Void> writeMessage(@Nullable Message msg) {
			return exception != null ? Promise.ofException(exception) : Promise.complete();
		}

		@Override
		public void closeEx(Exception e) {
			if (exception == null) exception = e;
		}

		@Override
		public boolean isClosed() {
			return exception != null;
		}

		@Override
		public HttpRequest getRequest() {
			throw new UnsupportedOperationException();
		}

		@Override
		public HttpResponse getResponse() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Promise<Frame> readFrame() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Promise<Void> writeFrame(@Nullable Frame frame) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * {@link UserEventsImpl} with the two things G6 needs and it has not: an await point for the
	 * <i>n</i>-th delivery (not just the first), and a hook run on every delivery — the seam session A
	 * uses to issue the reentrant broadcast from inside its own handler invocation.
	 */
	private static final class CountingUserEvents implements UserEvents {
		private final List<Long> ids = new ArrayList<>();
		private final @Nullable Consumer<Long> onDelivery;
		private int awaited;
		private @Nullable SettablePromise<Void> pending;

		private CountingUserEvents() {
			this(null);
		}

		private CountingUserEvents(@Nullable Consumer<Long> onDelivery) {
			this.onDelivery = onDelivery;
		}

		@Override
		public void userChanged(long id) {
			ids.add(id);
			SettablePromise<Void> pending = this.pending;
			if (pending != null && ids.size() >= awaited) {
				this.pending = null;
				pending.set(null);
			}
			if (onDelivery != null) onDelivery.accept(id);
		}

		/** Completes once {@code n} notifications have been delivered; already-complete if they have. */
		Promise<Void> deliveries(int n) {
			if (ids.size() >= n) return Promise.complete();
			awaited = n;
			SettablePromise<Void> pending = new SettablePromise<>();
			this.pending = pending;
			return pending;
		}

		/** Every delivered id, in order. */
		List<Long> ids() {
			return ids;
		}
	}
}
