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
import io.activej.eventloop.Eventloop;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApi;
import io.activej.jsonrpc.transport.tcp.fixtures.ClientApiImpl;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApi;
import io.activej.jsonrpc.transport.tcp.fixtures.TestApiImpl;
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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.activej.common.exception.FatalErrorHandlers.rethrow;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adversarial test plan, Domain C — sessions &amp; the registry ({@code JsonRpcTcpServer} /
 * {@code JsonRpcTcpSession}, feature 017). C1 and C2 are already covered
 * ({@link JsonRpcTcpServerLifecycleTest#testAConnectionAlreadyClosedAtConstructionIsNeverRegistered()},
 * {@link JsonRpcTcpSessionTest#testEnumerationRacingACloseNeitherThrowsNorTears()}); this class adds the
 * six scenarios that neither file reaches, in priority order:
 *
 * <ul>
 *     <li><b>C7 (P0)</b> — {@code withAcceptFilter} that throws instead of returning a boolean.
 *     {@code AbstractReactiveServer.doAccept} calls {@code acceptFilter.filterAccept(...)} with no
 *     {@code try}/{@code catch} of its own, and {@code Eventloop.onAccept}'s own catch block runs
 *     {@code handleError(fatalErrorHandler, e, acceptCallback)} <b>before</b>
 *     {@code closeChannel(channel, null)} — so what a throwing filter actually does depends entirely on
 *     the reactor's {@code FatalErrorHandler}, which {@code JsonRpcTcpServer} never chooses itself. Two
 *     tests pin the two real outcomes precisely (verified by direct experiment, not assumed) rather than
 *     leaving either one guessed: under the platform's own production default the accepted connection is
 *     closed cleanly and the server keeps accepting normally (no descriptor leak, no DoS); under a
 *     rethrowing handler — the very configuration this whole file's own {@code EventloopRule} installs —
 *     the exception escapes {@code Eventloop.run()} entirely and takes the whole reactor thread down, a
 *     genuinely severe consequence that is nonetheless {@code core-eventloop}/{@code core-net} platform
 *     behavior inherited unchanged by every {@code AbstractReactiveServer}, not a defect introduced by
 *     this module (see the task's reported findings for the full reasoning on why it is documented here
 *     rather than "fixed").</li>
 *     <li><b>C3 (P1)</b> — {@code broadcast(ClientApi.class, api -> api.decide(n))}: a request/response
 *     method fed to a {@code Consumer}-shaped broadcast. Verified against
 *     {@code JsonRpcClient.call(...)} rather than assumed: the correlation entry is registered
 *     <i>before</i> the document is even sent and does not depend on the caller retaining the returned
 *     {@code Promise}, so the discarded call is a completely ordinary, correctly-correlated exchange from
 *     the transport's point of view — it is simply never observed by anyone, and nothing about it is
 *     treated as an unknown-{@code id} orphan internally even though the two are indistinguishable from
 *     the outside.</li>
 *     <li><b>C4 (P1)</b> — two {@code JsonRpcTcpServer}s built over the very same
 *     {@code JsonRpcDispatcher} (FR-030: the dispatcher is the one thing they may share) never share a
 *     registry entry, including after a successful dispatch on each and a cross-server session close.</li>
 *     <li><b>C5 (P1)</b> — 40 sequential connect &rarr; call &rarr; close cycles against one reused,
 *     repeatedly-accepting server (no {@code withAcceptOnce}): the registry returns to exactly zero after
 *     <i>every</i> cycle, never drifting across the burst (FR-035).</li>
 *     <li><b>C6 (P2)</b> — the closest real reentrancy {@code sessions()} can be probed under:
 *     {@code serve()} itself has no synchronous hook into user code (its read loop is entirely
 *     {@code documents.get()}-driven), so {@code withAcceptFilter} — called strictly <i>before</i>
 *     {@code serve()}, in the same synchronous accept path — is the one point where a caller can observe
 *     the registry mid-accept. It must see exactly the sessions already registered, never the connection
 *     currently being accepted.</li>
 *     <li><b>C8 (P2)</b> — {@code withFailureHandler(...)} that itself throws when a broadcast routes a
 *     publisher's failure to it: {@code JsonRpcTcpSession.reportFailure}'s defensive
 *     {@code catch (RuntimeException ignored)} exists but had no test exercising it until now.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * Same shape as the rest of this module: {@code EventloopRule} + {@code ByteBufRule} +
 * {@code ActivePromisesRule}, every server bound to port {@code 0} and asked where it landed
 * (ADR-028, never {@code getFreePort()}), every awaited chain closing what it opened before returning so
 * {@code TestUtils.await} can reach quiescence. C7's two tests are the deliberate exception: each runs on
 * its own dedicated thread and {@code Eventloop} — never the shared {@code EventloopRule} reactor this
 * file otherwise uses — because one of them provokes a genuine, confirmed reactor crash and must not be
 * able to corrupt any sibling test's shared state.
 */
public final class JsonRpcTcpAdversarialSessionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The churn burst's length (C5) — inside the brief's "30 to 50" range. */
	private static final int CHURN_CYCLES = 40;

	// -------------------------------------------------------------------------------------------
	// C7 (P0) — withAcceptFilter that throws.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAcceptFilterThatThrowsClosesTheConnectionAndTheServerKeepsAcceptingUnderProductionDefaults() {
		// C7, the safe half. VERIFIED by direct experiment: Eventloop.create()'s default FatalErrorHandler
		// is Eventloop::logFatalError (log only, never rethrow — core-eventloop/CLAUDE.md's own Error
		// Handling section). Under that default, Eventloop.onAccept's
		//     handleError(fatalErrorHandler, e, acceptCallback); closeChannel(channel, null);
		// runs handleError to completion (it only logs) and THEN closeChannel — so the accepted channel IS
		// closed, the for-loop inside onAccept simply continues to the next tick's accepts, and the reactor
		// itself never stops. This is the platform's normal running configuration (the one
		// JsonRpcTcpServer itself never overrides), and it is NOT the trivial single-filter-exception DoS
		// the scenario worried about — the second, contrasting test below shows precisely when that worry
		// IS realized.
		//
		// Run on its own EventloopThread — the module's prescribed harness for anything that must run on a
		// real, independently-driven reactor — rather than the shared EventloopRule reactor, purely so this
		// test's own construction stays close to every other test in the file without entangling the two
		// C7 scenarios' very different reactor configurations in one instance.
		EventloopThread loop = EventloopThread.create("tcp-c7-default");
		try {
			RefInt filterCalls = new RefInt(0);
			Ref<JsonRpcTcpServer> serverRef = new Ref<>();

			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
					.build();
				JsonRpcTcpServer server = JsonRpcTcpServer.builder(loop.eventloop(), dispatcher)
					.withListenPort(0)
					.withAcceptFilter((channel, local, remote, ssl) -> {
						if (filterCalls.inc() == 1) throw new RuntimeException("boom-from-filter");
						return false; // every later connection is accepted normally
					})
					.build();
				try {
					server.listen();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				serverRef.set(server);
			});
			InetSocketAddress address = loop.submit(() -> serverRef.get().getBoundAddresses().get(0));

			// (a) + (b): the filtered connection registers no session, and the accepted channel IS closed —
			// observed from the client side as a clean onClosed(null) rather than a silent, orphaned hang.
			CompletableFuture<Void> firstClosed = loop.submit(() -> {
				SettablePromise<Void> closed = new SettablePromise<>();
				JsonRpcTcpTransport.connect(loop.eventloop(), address)
					.whenResult(transport -> transport.setListener(new JsonRpcTransport.Listener() {
						@Override
						public void onDocument(byte[] document) {
							closed.trySetException(
								new IllegalStateException("the filtered connection must never reach the dispatcher"));
						}

						@Override
						public void onClosed(@Nullable Exception e) {
							closed.trySet(null);
						}
					}))
					.whenException(closed::trySetException);
				return closed.toCompletableFuture();
			});
			EventloopThread.await(firstClosed, "the filtered connection's close signal");

			assertEquals("the filtered connection never registered a session",
				0, (int) loop.submit(() -> serverRef.get().sessions().size()));

			// (c): a second, unaffected connection is accepted and dispatched completely normally right
			// after — one throwing filter invocation is not a DoS of the whole server under this
			// configuration.
			CompletableFuture<TestApi.AddResult> secondResult = loop.submit(() ->
				JsonRpcTcpTransport.connect(loop.eventloop(), address)
					.then(transport -> {
						JsonRpcClient client = JsonRpcClient.builder(loop.eventloop(), transport).build();
						return client.proxy(TestApi.class).add(20, 22);
					})
					.toCompletableFuture());
			TestApi.AddResult result = EventloopThread.await(secondResult, "the second connection's call");

			assertEquals(42, result.sum());
			assertEquals("the second, unaffected connection registered normally",
				1, (int) loop.submit(() -> serverRef.get().sessions().size()));
			assertEquals("the filter ran exactly once per connection attempt", 2, filterCalls.get());
		} finally {
			loop.close();
		}
	}

	@Test
	public void testAcceptFilterThatThrowsCrashesTheWholeReactorUnderARethrowingFatalErrorHandler() {
		// C7, the severe half — and the one that matches this very file's own EventloopRule convention.
		// FatalErrorHandlers.rethrow() is what EventloopRule installs on every OTHER test in this file's
		// shared reactor; under that configuration, Eventloop.onAccept's handleError(...) call THROWS
		// before closeChannel(channel, null) ever runs, so the accepted channel is never closed and the
		// exception escapes processSelectedKeys, the while(isAlive()) loop and Eventloop.run() itself,
		// taking the entire reactor thread down — not merely refusing the one connection. VERIFIED by
		// direct experiment (thread.isAlive() is false afterward; the identical exception instance is what
		// escaped run()), not assumed.
		//
		// This is real and severe, but it is core-eventloop's Eventloop.onAccept and core-net's
		// AbstractReactiveServer.doAccept — identical, and identically unguarded, for EVERY
		// AbstractReactiveServer (HttpServer, RpcServer, FileSystemServer included), present long before
		// this feature. JsonRpcTcpServer chooses no FatalErrorHandler of its own and composes
		// withAcceptFilter verbatim from the base class, so there is no fix scoped to this module that
		// would not also rewrite shared platform catch-block ordering with a much larger blast radius —
		// see this task's reported findings for the full reasoning. This test exists to pin the real
		// behavior precisely, the same way the test above pins the opposite, safe one.
		//
		// A raw, dedicated, unshared thread + Eventloop (not EventloopThread, which offers no
		// FatalErrorHandler override, and never the shared EventloopRule reactor) — so the crash this test
		// deliberately provokes can never reach or corrupt any other test in this file.
		Eventloop eventloop = Eventloop.builder()
			.withFatalErrorHandler(rethrow())
			.build();
		RuntimeException filterFailure = new RuntimeException("boom-from-filter");
		Ref<Throwable> escaped = new Ref<>();

		Thread thread = new Thread(() -> {
			Reactor.setCurrentReactor(eventloop);
			JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(eventloop)
				.withService(TestApi.class, new TestApiImpl())
				.build();
			JsonRpcTcpServer server = JsonRpcTcpServer.builder(eventloop, dispatcher)
				.withListenPort(0)
				.withAcceptFilter((channel, local, remote, ssl) -> {
					throw filterFailure;
				})
				.build();
			try {
				server.listen();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			InetSocketAddress address = server.getBoundAddresses().get(0);
			// the client-side connect only needs to reach the OS-level accept; what happens to it
			// afterward is not this test's concern, only what happens on the server/reactor side is
			eventloop.post(() -> JsonRpcTcpTransport.connect(eventloop, address).whenException(e -> {}));
			try {
				eventloop.run();
			} catch (Throwable t) {
				escaped.set(t);
			}
		}, "tcp-c7-rethrow");
		thread.setDaemon(true);
		thread.start();
		try {
			thread.join(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("interrupted while waiting for the crash to settle");
		}

		assertFalse("the reactor thread does not survive a rethrown accept-filter exception", thread.isAlive());
		assertSame("the exact exception the filter threw escapes Eventloop.run() unwrapped",
			filterFailure, escaped.get());
	}

	// -------------------------------------------------------------------------------------------
	// C3 (P1) — broadcast invoking a request/response method.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testBroadcastInvokingARequestResponseMethodStillSendsAndTheDiscardedReplyIsHandledSilently() {
		// C3: server.broadcast's invocation is a Consumer<T>, so decide(...)'s returned Promise<String> is
		// simply discarded — a misuse of broadcast (client.decide is request/response, not
		// @JsonRpcNotification), but nothing in broadcast or the session refuses it.
		//
		// VERIFIED against JsonRpcClient.call(...) rather than assumed: pending.put(id, new
		// PendingCall(...)) happens BEFORE the outbound document is sent, and unconditionally on whether
		// the caller keeps the returned Promise — a Java expression-statement simply drops a return value,
		// it does not un-register anything. So the document that leaves the wire is a genuine request
		// carrying a real id (not a notification); the client answers it normally; and the answer
		// correlates against a REAL, still-present table entry, settling the (unwatched) Promise and
		// removing the entry through the ordinary success path — never the unknown-id orphan path. From
		// the outside the two are indistinguishable (no exception, no failure handler, nobody watching),
		// but internally this is an ordinary exchange, not an orphan — the assertions below pin that
		// distinction precisely rather than assume the id was never registered.
		List<Exception> serverFailures = new ArrayList<>();
		JsonRpcTcpServer server = multiSessionServer(serverFailures::add);
		Set<JsonRpcTcpSession> known = new HashSet<>();
		ClientApiImpl clientApi = new ClientApiImpl();
		RefInt inFlightRightAfterBroadcast = new RefInt(-1);
		RefInt inFlightAfterRoundTrip = new RefInt(-1);
		RefInt sessionsAfterRoundTrip = new RefInt(-1);
		Ref<String> retainedResult = new Ref<>();

		await(connect(server, clientApi, known)
			.then(peer -> {
				JsonRpcTcpSession session = peer.session();
				server.broadcast(ClientApi.class, api -> api.decide(7));
				// synchronous, right after broadcast returns: call() registers the entry before send() is
				// even issued, whether or not the caller kept the Promise it returned
				inFlightRightAfterBroadcast.set(session.inFlightCount());
				// a second, RETAINED call on the very same session proxy (FR-063: same cached instance).
				// This transport's read loop delivers one document at a time in arrival order (FR-022), so
				// by the time THIS call's answer resolves, the discarded id=1 answer has already been read,
				// correlated and settled too.
				return session.proxy(ClientApi.class).decide(8)
					.whenResult(retainedResult::set)
					.whenResult($ -> {
						inFlightAfterRoundTrip.set(session.inFlightCount());
						sessionsAfterRoundTrip.set(server.sessions().size());
					})
					.then(() -> shutdown(server, peer));
			}));

		assertEquals("the discarded call was registered exactly like any other outbound request",
			1, inFlightRightAfterBroadcast.get());
		assertEquals("the retained sibling call still resolves normally", "decided-8", retainedResult.get());
		assertEquals("both the discarded and the retained call vacated the correlation table",
			0, inFlightAfterRoundTrip.get());
		assertEquals("the connection and its registration survived the misuse", 1, sessionsAfterRoundTrip.get());
		assertTrue("the discarded-but-correctly-correlated reply never reached the failure handler",
			serverFailures.isEmpty());
	}

	// -------------------------------------------------------------------------------------------
	// C4 (P1) — two servers, one shared dispatcher, independent registries.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testTwoServersSharingOneDispatcherNeverShareARegistryEntry() {
		// C4/FR-030: the dispatcher is the one thing two JsonRpcTcpServer instances may share; the
		// registry never is. Two servers on two independent :0 ports, one connection each, and a
		// cross-check after each connection AND after closing one server's session that the other
		// registry never moved.
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcTcpServer serverA = JsonRpcTcpServer.builder(reactor(), dispatcher).withListenPort(0).build();
		JsonRpcTcpServer serverB = JsonRpcTcpServer.builder(reactor(), dispatcher).withListenPort(0).build();
		try {
			serverA.listen();
			serverB.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		NioReactor reactor = reactor();

		RefInt aAfterA = new RefInt(-1);
		RefInt bAfterA = new RefInt(-1);
		RefInt aAfterB = new RefInt(-1);
		RefInt bAfterB = new RefInt(-1);
		RefInt aAfterCloseOfB = new RefInt(-1);
		RefInt bAfterCloseOfB = new RefInt(-1);
		Ref<Boolean> disjointAfterBoth = new Ref<>(false);

		await(JsonRpcTcpTransport.connect(reactor, boundAddress(serverA))
			.then(transportA -> {
				JsonRpcClient clientA = JsonRpcClient.builder(reactor, transportA).build();
				return clientA.proxy(TestApi.class).add(1, 1)
					.then(resultA -> {
						aAfterA.set(serverA.sessions().size());
						bAfterA.set(serverB.sessions().size());
						return JsonRpcTcpTransport.connect(reactor, boundAddress(serverB));
					})
					.then(transportB -> {
						JsonRpcClient clientB = JsonRpcClient.builder(reactor, transportB).build();
						return clientB.proxy(TestApi.class).add(2, 2)
							.then(resultB -> {
								aAfterB.set(serverA.sessions().size());
								bAfterB.set(serverB.sessions().size());
								disjointAfterBoth.set(Collections.disjoint(serverA.sessions(), serverB.sessions()));
								// close B's session from B's own registry; A must never notice
								for (JsonRpcTcpSession session : List.copyOf(serverB.sessions())) {
									session.closeEx(new ExpectedException("closing B's session"));
								}
								aAfterCloseOfB.set(serverA.sessions().size());
								bAfterCloseOfB.set(serverB.sessions().size());
								clientA.closeEx(new ExpectedException("end of test"));
								clientB.closeEx(new ExpectedException("end of test"));
								return Promises.all(serverA.close(), serverB.close());
							});
					});
			}));

		assertEquals("A registered its own connection", 1, aAfterA.get());
		assertEquals("B saw nothing from A's connection", 0, bAfterA.get());
		assertEquals("A is unaffected by B's own, independent connection", 1, aAfterB.get());
		assertEquals("B registered its own connection", 1, bAfterB.get());
		assertTrue("the two registries never shared a session, even with one live connection each",
			disjointAfterBoth.get());
		assertEquals("A's registry is untouched by B's session closing", 1, aAfterCloseOfB.get());
		assertEquals("B's registry reflects its own close", 0, bAfterCloseOfB.get());
	}

	// -------------------------------------------------------------------------------------------
	// C5 (P1) — rapid churn on one reused, repeatedly-accepting server.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testRapidChurnLeavesTheRegistryExactlyEmptyAfterEveryCycle() {
		// C5/FR-035: 40 sequential connect -> call -> close cycles against ONE server that keeps accepting
		// (no withAcceptOnce) — a churn, never several connections open at once. Each cycle closes the
		// session from the SERVER side (synchronous deregistration inside CloseObservingTransport.onClosed,
		// confirmed by JsonRpcTcpSessionTest's own local-close tests), so the registry's return to zero is
		// asserted deterministically inside the very same promise chain rather than guessed at across a
		// network round trip the peer's own close would need.
		JsonRpcTcpServer server = multiSessionServer(null);
		NioReactor reactor = reactor();
		List<Integer> sizesAfterEachCycle = new ArrayList<>();

		await(churn(server, reactor, CHURN_CYCLES, sizesAfterEachCycle)
			.then(() -> server.close().toVoid()));

		assertEquals("every one of the " + CHURN_CYCLES + " cycles was observed", CHURN_CYCLES, sizesAfterEachCycle.size());
		for (int cycle = 0; cycle < sizesAfterEachCycle.size(); cycle++) {
			assertEquals("cycle " + cycle + " left a residual entry in the registry",
				0, (int) sizesAfterEachCycle.get(cycle));
		}
		assertTrue("the registry is empty once the burst and the server are both done", server.sessions().isEmpty());
	}

	private static Promise<Void> churn(
		JsonRpcTcpServer server, NioReactor reactor, int remainingCycles, List<Integer> sizesAfterEachCycle
	) {
		if (remainingCycles == 0) return Promise.complete();
		return JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(transport -> {
				JsonRpcClient client = JsonRpcClient.builder(reactor, transport).build();
				return client.proxy(TestApi.class).add(1, 1);
			})
			.then(result -> {
				// exactly one live session at this point: the one this cycle just registered — every
				// earlier cycle already closed and deregistered its own before returning here
				JsonRpcTcpSession session = server.sessions().iterator().next();
				session.closeEx(new ExpectedException("churn cycle end"));
				sizesAfterEachCycle.add(server.sessions().size());
				return churn(server, reactor, remainingCycles - 1, sizesAfterEachCycle);
			});
	}

	// -------------------------------------------------------------------------------------------
	// C6 (P2) — sessions() from inside withAcceptFilter, the closest real reentrancy point.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testAcceptFilterSeesOnlyAlreadyRegisteredSessionsNeverTheConnectionBeingAccepted() {
		// C6: serve() itself exposes no synchronous hook into user code — its read loop is entirely
		// documents.get()-driven — so withAcceptFilter, called by AbstractReactiveServer.doAccept strictly
		// BEFORE serve(), is the closest thing to "sessions() called from inside serve()" the real API
		// admits. A first connection registers normally; the filter runs again for a second connection and
		// must see exactly the first (not itself, which serve() has not registered yet), with no exception
		// and no corrupted snapshot.
		Ref<JsonRpcTcpServer> serverRef = new Ref<>();
		List<Integer> observedAtEachFilterCall = new ArrayList<>();
		JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
		JsonRpcTcpServer server = JsonRpcTcpServer.builder(reactor(), dispatcher)
			.withListenPort(0)
			.withAcceptFilter((channel, local, remote, ssl) -> {
				observedAtEachFilterCall.add(serverRef.get().sessions().size());
				return false; // admission is not what this scenario is about: always accept
			})
			.build();
		serverRef.set(server);
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Set<JsonRpcTcpSession> known = new HashSet<>();

		await(connect(server, new ClientApiImpl(), known)
			.then(a -> connect(server, new ClientApiImpl(), known)
				.then(b -> shutdown(server, a, b))));

		assertEquals("the filter ran exactly once per accepted connection", 2, observedAtEachFilterCall.size());
		assertEquals("the filter for connection 1 saw an empty registry — nothing was registered yet",
			0, (int) observedAtEachFilterCall.get(0));
		assertEquals("the filter for connection 2 saw exactly connection 1, never the connection " +
					 "currently being accepted (which serve() had not registered yet)",
			1, (int) observedAtEachFilterCall.get(1));
	}

	// -------------------------------------------------------------------------------------------
	// C8 (P2) — withFailureHandler that itself throws.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testFailureHandlerThatThrowsIsSwallowedAndDoesNotBreakTheBroadcastLoop() {
		// C8: JsonRpcTcpSession.reportFailure already defends against this ("a failure handler that itself
		// fails must not propagate into the broadcast iteration"), but nothing exercised the defense until
		// now. Same shape as JsonRpcTcpSessionTest.testBroadcastFailureIsContainedToTheFailingSession, with
		// the server's OWN failureHandler now also throwing when invoked.
		List<Exception> handlerSaw = new ArrayList<>();
		JsonRpcTcpServer server = multiSessionServer(e -> {
			handlerSaw.add(e);
			throw new HandlerFailure();
		});
		Set<JsonRpcTcpSession> known = new HashSet<>();
		ClientApiImpl eventsA = new ClientApiImpl();
		ClientApiImpl eventsB = new ClientApiImpl();
		RefInt sessionsAfterBroadcast = new RefInt(-1);
		PublisherFailure publisherFailure = new PublisherFailure();

		// deliberately NOT thrown from inside a try/catch below: if the handler's own RuntimeException
		// ever escaped JsonRpcTcpSession.reportFailure, it would propagate straight out of this plain,
		// synchronous broadcast(...) call and fail this test with THAT exception — a real, sufficient
		// assertion that the swallow held, not merely an inference from the surviving session's delivery
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

		assertEquals("the throwing failure handler was invoked exactly once, with the publisher's own failure",
			List.of(publisherFailure), handlerSaw);
		assertEquals("the surviving session still received its notification despite the handler's own throw",
			List.of(42L), eventsB.events());
		assertEquals("the failing session received nothing", List.of(), eventsA.events());
		assertEquals("the handler's own exception cost no session its registration", 2, sessionsAfterBroadcast.get());
	}

	/** A publisher's own failure, thrown from inside a broadcast invocation — the C8 mirror of the same. */
	private static final class PublisherFailure extends RuntimeException {
		private PublisherFailure() {
			super("this session's publisher blew up");
		}
	}

	/** C8's own failure handler that itself fails, to exercise {@code JsonRpcTcpSession.reportFailure}'s defense. */
	private static final class HandlerFailure extends RuntimeException {
		private HandlerFailure() {
			super("the failure handler itself blew up");
		}
	}

	// -------------------------------------------------------------------------------------------
	// Fixture — shared shape with JsonRpcTcpSessionTest, kept local to this file per module convention
	// (every adversarial test file in this module defines its own copies rather than sharing across files).
	// -------------------------------------------------------------------------------------------

	/** One connected peer: the client, and the server-side session that connection produced. */
	private record Peer(JsonRpcClient client, JsonRpcTcpSession session) {}

	/**
	 * A server that keeps accepting (never {@code withAcceptOnce}) — the shape every multi-connection
	 * scenario in this class needs. Its accept socket must be closed <b>inside</b> the awaited chain (by
	 * {@link #shutdown}), because a lingering accept socket keeps {@code Eventloop.isAlive()} true and
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

	/**
	 * Connects one client answering {@code clientService}, and identifies the session it produced as the
	 * one the registry gained since the last call. A real round trip is the barrier: a {@code connect}
	 * promise resolving says nothing about the <i>server</i> having accepted, whereas an answered call
	 * means the server accepted, served and dispatched — and registration precedes dispatch (FR-032).
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

	private static InetSocketAddress boundAddress(JsonRpcTcpServer server) {
		// ADR-028: bind :0 and ask where it landed
		return server.getBoundAddresses().get(0);
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
