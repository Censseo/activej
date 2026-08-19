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
import io.activej.http.HttpException;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocketClient;
import io.activej.http.WebSocketException;
import io.activej.http.WebSocketServlet;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.ClientApi;
import io.activej.jsonrpc.transport.ws.fixtures.HangApi;
import io.activej.jsonrpc.transport.ws.fixtures.HangApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.StubWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.TestApi;
import io.activej.jsonrpc.transport.ws.fixtures.TestApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.UserEvents;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * User story 5 — the server enumerates and manages its sessions (T019, US5, FR-032…FR-037, FR-061):
 * the deliberate, tested session-management surface. Four concerns, each pinned to a requirement:
 * <ul>
 *     <li><b>enumeration cardinality tracks connect/close</b> (US5-1, FR-035): three live
 *     connections are enumerated as three sessions, and closing one through its session handle
 *     removes it from the enumeration and fires the closed client's transport listener;</li>
 *     <li><b>{@code closeEx} through the session handle</b> (US5-2): it purges every server-initiated
 *     in-flight call through the client's single removal path and removes the session from the
 *     enumeration — the management half of the session notion (FR-034);</li>
 *     <li><b>the admission gate</b> (FR-036): a non-{@code 101} {@code onRequest} answer refuses the
 *     upgrade before any session exists. Because {@link JsonRpcWsServlet} is final (contract), the
 *     gate is composed <i>in front</i> of a real servlet — a custom {@link WebSocketServlet} whose
 *     {@code onWebSocket} delegates the accepted socket to the servlet's own upgrade path — and the
 *     refused connection must fail the client handshake with {@code HANDSHAKE_FAILED} (FR-061) and
 *     never reach the registry;</li>
 *     <li><b>off-reactor access fails fast</b> (FR-037, US5-3): a publisher on a non-reactor thread
 *     must not touch the registry or a session directly. The {@link EventloopRule} cannot reproduce
 *     a cross-thread violation — {@code Reactor.inReactorThread()} is trivially true when the loop
 *     has never run — so the servlet and its session live on a real reactor running on its own
 *     dedicated thread ({@link EventloopThread}), and the JUnit thread is exactly the foreign thread
 *     the guards must reject.</li>
 * </ul>
 * <p>
 * <b>Session construction in the close-purge test.</b> A real session registered through the
 * servlet's own {@code onWebSocket} (protected, same package) over an in-memory {@link StubWebSocket}
 * — the same entry point core-http's upgrade path calls, no socket, so the issued calls stay pending
 * deterministically (the role the never-answering {@link HangApi} service plays on a real connection,
 * T013) and the close's effects are fully observable.
 * <p>
 * <b>Quiescence (R3).</b> Every awaited chain closes every channel it opened inside the chain before
 * returning, then {@code WsPair.closeAll()} is the belt-and-suspenders cleanup. Rules per FR-079.
 */
public final class JsonRpcWsSessionManagementTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The server-initiated calls kept in flight by the close-purge test (US5-2). */
	private static final int CALLS = 3;

	/** The close code of the US5-2 closeEx (a non-1000 application code, the purge test's convention). */
	private static final int CLOSE_CODE = 4000;

	/** The successive connect-and-close cycles of the churn test (C5, adversarial plan). */
	private static final int CHURN_CYCLES = 100;

	/** The non-101 answers the code-agnostic refusal test drives the gate with (C7, adversarial plan). */
	private static final int[] REFUSAL_CODES = {500, 302};

	@Test
	public void testEnumerationCardinalityTracksConnectAndClose() {
		// US5-1 / US5 independent test: three clients connect, the server enumerates three sessions;
		// closing one through its session handle removes it from the enumeration and fires the closed
		// client's transport listener — the registry mirrors open connections (FR-035), with no second
		// bound. Three WsPair acceptOnce servers share one servlet instance: one registry, three real
		// sockets; the session closed through its handle is identified by registration order (only A
		// is registered when it is captured).
		JsonRpcWsServlet servlet = newServlet();
		Ref<IWebSocket> aSocket = new Ref<>();
		Ref<IWebSocket> bSocket = new Ref<>();
		Ref<IWebSocket> cSocket = new Ref<>();
		Ref<JsonRpcWsSession> sessionA = new Ref<>();
		RefInt sessionsAtThree = new RefInt(-1);
		RefInt sessionsAfterClose = new RefInt(-1);
		RefInt closedListenerFired = new RefInt(0);
		SettablePromise<Void> aClosed = new SettablePromise<>();
		WsPair pairA = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairB = WsPair.serverUpgrade(reactor(), servlet);
		WsPair pairC = WsPair.serverUpgrade(reactor(), servlet);

		await(pairA.connect()
			.then(ws -> {
				aSocket.set(ws);
				// A's listener observes the close — "the closed client's listener fired"
				JsonRpcWsTransport.of(reactor(), ws).setListener(listener($ -> {}, e -> {
					closedListenerFired.inc();
					aClosed.set(null);
				}));
				sessionA.set(servlet.sessions().iterator().next());   // {A} so far — registration order
				return pairB.connect();
			})
			.then(ws -> {
				bSocket.set(ws);
				return pairC.connect();
			})
			.then(ws -> {
				cSocket.set(ws);
				sessionsAtThree.set(servlet.sessions().size());       // three live connections
				// close A through its session handle (US5 scenario 1); A's own clean close is the
				// belt-and-suspenders for R3 quiescence (the handshake then closes both channels)
				sessionA.get().closeEx(new AsyncCloseException("closing session A"));
				aSocket.get().writeMessage(null).whenComplete(($, e) -> {});
				return aClosed;                                        // until A's listener has fired
			})
			.whenResult($ -> sessionsAfterClose.set(servlet.sessions().size()))   // 2
			.whenComplete(() -> {
				bSocket.get().closeEx(new AsyncCloseException());
				cSocket.get().closeEx(new AsyncCloseException());
			}));

		assertEquals("three live connections are enumerated as three sessions", 3, sessionsAtThree.get());
		assertEquals("the closed session is gone from the enumeration", 2, sessionsAfterClose.get());
		assertEquals("the closed client's transport listener fired exactly once", 1, closedListenerFired.get());
		pairA.closeAll();
		pairB.closeAll();
		pairC.closeAll();
	}

	@Test
	public void testCloseExViaSessionHandlePurgesInFlightCallsAndRemovesSession() {
		// US5-2: closing a session through its handle purges every server-initiated in-flight call
		// through the client's single removal path (FR-034, ADR-030) and removes the session from the
		// enumeration — the management half of the session notion. The session is a real registry
		// citizen, created through the servlet's own upgrade path (onWebSocket, driven directly from
		// this same-package test) over an in-memory StubWebSocket: the issued calls stay pending
		// deterministically — the role the never-answering HangApi service plays on a real connection
		// (T013) — so the close's effects are fully observable without a socket.
		JsonRpcWsServlet servlet = newServletWithHangService();
		StubWebSocket serverSocket = new StubWebSocket();
		servlet.onWebSocket(serverSocket);                 // the 101 upgrade path (FR-032)

		JsonRpcWsSession session = servlet.sessions().iterator().next();
		List<Promise<String>> calls = new ArrayList<>();
		List<Exception> callFailures = new ArrayList<>();
		for (int i = 0; i < CALLS; i++) {
			Promise<String> call = session.proxy(HangApi.class).request(i);
			calls.add(call);
			call.whenException(callFailures::add);
		}
		assertEquals("the server initiated " + CALLS + " calls, all awaiting an answer", CALLS, session.inFlightCount());
		assertEquals("the session is enumerated", Set.of(session), servlet.sessions());

		session.closeEx(new WebSocketException(CLOSE_CODE, "server shutdown"));

		assertEquals("closeEx purged every in-flight call", 0, session.inFlightCount());
		assertEquals("the closed session is no longer enumerated", Set.of(), servlet.sessions());
		assertEquals("every purged call failed", CALLS, callFailures.size());
		for (Exception e : callFailures) {
			assertThat("the purged call failed with the close cause, not " + e, e, instanceOf(WebSocketException.class));
			assertEquals(Integer.valueOf(CLOSE_CODE), ((WebSocketException) e).getCode());
		}
	}

	@Test
	public void testAdmissionGateRefusesUpgradeBeforeAnySessionExists() {
		// FR-036: WebSocketServlet.onRequest is the application's admission seam — a non-101 answer
		// refuses the upgrade before any session exists. JsonRpcWsServlet is final (contract), so the
		// gate is composed IN FRONT of a real servlet: a custom WebSocketServlet whose onWebSocket
		// delegates the accepted socket to the servlet's own upgrade path. The refused connection must
		// fail the client handshake with HANDSHAKE_FAILED (FR-061) and never reach the registry; the
		// control phase proves the same gate, admitting, delivers the socket and registers a session.
		JsonRpcWsServlet wsServlet = newServlet();
		Ref<Boolean> allow = new Ref<>(false);
		WebSocketServlet gate = new WebSocketServlet(reactor()) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				return allow.get() ? super.onRequest(request) : HttpResponse.ofCode(403).toPromise();
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				wsServlet.onWebSocket(webSocket);     // the admitted upgrade flows to the real servlet
			}
		};

		// phase 1 — the gate refuses: the client handshake fails and no session is created
		WsPair pairRefused = WsPair.serverUpgrade(reactor(), gate);
		Exception e = awaitException(JsonRpcWsTransport.connect(reactor(), pairRefused.client(),
			HttpRequest.get("ws://127.0.0.1:" + pairRefused.port()).build()));

		// HANDSHAKE_FAILED (core-http's package-private singleton) is the exact observable: the
		// refusal surfaces as that HttpException with its handshake message (FR-036, FR-061)
		assertThat("the refusal surfaces as HANDSHAKE_FAILED, not " + e, e, instanceOf(HttpException.class));
		assertEquals("Failed to perform a proper opening handshake", e.getMessage());
		assertEquals("the refused connection never became a session", Set.of(), wsServlet.sessions());
		pairRefused.closeAll();

		// phase 2 — control, the gate admits: the same servlet behind the gate registers one session
		allow.set(true);
		WsPair pairAccepted = WsPair.serverUpgrade(reactor(), gate);
		RefInt sessionsAfterAccept = new RefInt(-1);
		await(pairAccepted.connect().then(ws -> {
			sessionsAfterAccept.set(wsServlet.sessions().size());
			return ws.writeMessage(null);
		}));
		assertEquals("an admitted upgrade creates exactly one session", 1, sessionsAfterAccept.get());
		pairAccepted.closeAll();
	}

	@Test
	public void testOffReactorAccessToRegistryAndSessionFailsFast() {
		// FR-037 / US5-3: a publisher on a non-reactor thread must not touch the registry or a session
		// directly — the documented idiom is an explicit hop (reactor.post / BlockingReactorExecutor),
		// and every public method failing fast off-reactor is the enforcement (FR-031, WI-1). The
		// EventloopRule cannot reproduce a cross-thread violation: Reactor.inReactorThread() is
		// trivially true when the loop has never run (Eventloop.eventloopThread == null), so the
		// servlet and its session live on a real reactor running on its own dedicated thread
		// (EventloopThread), and the JUnit thread is exactly the foreign thread the guards must reject.
		EventloopThread loop = EventloopThread.create("ws-session-guard");
		Ref<JsonRpcWsServlet> servlet = new Ref<>();
		Ref<JsonRpcWsSession> session = new Ref<>();
		try {
			loop.submit(() -> {
				JsonRpcDispatcher dispatcher = JsonRpcDispatcher.builder(loop.eventloop())
					.withService(TestApi.class, new TestApiImpl())
					.build();
				servlet.set(JsonRpcWsServlet.builder(loop.eventloop(), dispatcher).build());
				servlet.get().onWebSocket(new StubWebSocket());   // the upgrade path, driven directly
				session.set(servlet.get().sessions().iterator().next());
			});

			expectIllegalState(() -> servlet.get().sessions());
			expectIllegalState(() -> servlet.get().broadcast(UserEvents.class, events -> events.userChanged(1)));
			expectIllegalState(() -> session.get().proxy(ClientApi.class));
			expectIllegalState(() -> session.get().closeEx(new Exception("from the wrong thread")));
		} finally {
			if (session.get() != null) {
				loop.submit(() -> session.get().closeEx(new AsyncCloseException("guard test teardown")));
			}
			loop.close();
		}
	}

	@Test
	public void testRapidChurnOfHundredConnectionsLeavesRegistryEmpty() {
		// C5, adversarial plan: 100 successive connections against ONE servlet, each closed the moment it
		// has registered. The registry must track connect and close through a full churn cycle —
		// cardinality == open connections (FR-035), so exactly one live session at a time here and exactly
		// zero at the end: no leaked registry entry, and no per-iteration ByteBuf accumulation either
		// (ByteBufRule spans the whole burst). One acceptOnce WsPair per cycle — the accept socket must not
		// outlive its one connection (R3) — all of them through the same injected client (FR-065), and each
		// cycle is awaited to quiescence with its channels closed before the next one starts: a churn, never
		// 100 simultaneously open connections.
		JsonRpcWsServlet servlet = newServlet();
		RefInt liveAtConnect = new RefInt(-1);
		IWebSocketClient client = null;

		for (int cycle = 0; cycle < CHURN_CYCLES; cycle++) {
			WsPair pair = client == null ?
				WsPair.serverUpgrade(reactor(), servlet) :
				WsPair.serverUpgrade(reactor(), client, servlet);
			client = pair.client();                            // every later cycle reuses this one client

			await(pair.connect().then(ws -> {
				liveAtConnect.set(servlet.sessions().size());
				return ws.writeMessage(null);                   // clean close, code 1000 — inside the chain (R3)
			}));

			assertEquals("cycle " + cycle + " did not register exactly one session", 1, liveAtConnect.get());
			assertEquals("cycle " + cycle + " left a session in the registry", 0, servlet.sessions().size());
			pair.closeAll();
		}

		assertEquals("the registry is back to empty after " + CHURN_CYCLES + " connect/close cycles",
			Set.of(), servlet.sessions());
	}

	@Test
	public void testAdmissionGateRefusalIsCodeAgnostic() {
		// C7, adversarial plan: FR-036's refusal is not about 403 — ANY non-101 answer refuses the upgrade
		// before any session exists. Same composition as
		// testAdmissionGateRefusesUpgradeBeforeAnySessionExists (a gate WebSocketServlet in front of the
		// real, final JsonRpcWsServlet, delegating onWebSocket to it), with the gate answering 500 (a server
		// error) and then 302 (a redirect) instead of 403 — two other classes of non-101 answer, each with
		// the same outcome: HANDSHAKE_FAILED on the client (FR-061) and a registry that stays empty.
		JsonRpcWsServlet wsServlet = newServlet();
		RefInt refusalCode = new RefInt(0);
		RefInt upgradesDelivered = new RefInt(0);
		WebSocketServlet gate = new WebSocketServlet(reactor()) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				return HttpResponse.ofCode(refusalCode.get()).toPromise();
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				upgradesDelivered.inc();                       // never: the gate never answers 101
				wsServlet.onWebSocket(webSocket);
			}
		};

		for (int code : REFUSAL_CODES) {
			refusalCode.set(code);
			WsPair pair = WsPair.serverUpgrade(reactor(), gate);
			Exception e = awaitException(JsonRpcWsTransport.connect(reactor(), pair.client(),
				HttpRequest.get("ws://127.0.0.1:" + pair.port()).build()));

			assertThat("the " + code + " refusal surfaces as HANDSHAKE_FAILED, not " + e, e, instanceOf(HttpException.class));
			assertEquals("the " + code + " refusal is the very same handshake failure a 403 produces",
				"Failed to perform a proper opening handshake", e.getMessage());
			assertEquals("the " + code + "-refused connection never became a session", Set.of(), wsServlet.sessions());
			pair.closeAll();
		}

		assertEquals("no non-101 answer ever delivered a socket to the servlet", 0, upgradesDelivered.get());
	}

	@Test
	public void testAdmissionGateFailingInOnRequestRefusesBeforeAnySession() {
		// C8, adversarial plan: the gate answers nothing at all — its onRequest fails instead of returning
		// a response. The client's connect promise must fail rather than hang, no session is ever created
		// and no handler is ever invoked: the gate sits strictly in front of the registry (FR-036). The
		// failure surfaces at the client exactly as a refusing status code does — HANDSHAKE_FAILED
		// (FR-061) — so a broken gate is indistinguishable, to a client, from a gate that said no.
		// <p>
		// The failure is expressed the way core-http's own WebSocketClientServerTest.testRejectedWithException
		// expresses it — a failed promise out of onRequest — and NOT as a synchronous {@code throw}, which
		// was measured against this harness and rejected on the evidence: an unchecked throw out of a
		// servlet is a FATAL error by ActiveJ's contract (FatalErrorHandler.handleError routes exactly
		// RuntimeException/Error), so EventloopRule's rethrowing handler surfaces it on the JUnit thread
		// (RuntimeException: boom, verbatim) before the connection is ever answered, the aborted connection
		// stays in the server's `serving` pool so nothing quiesces again (HttpServer's own shutdown log
		// repeats "Waiting for HttpServer{... serving:1 ...}" forever), and WebSocketServlet.serve leaks the
		// request body stream it had already taken — its recycleStream is wired to the promise's exception
		// path only, which a throw bypasses. That leak is core-http's, nothing this module can recycle, and
		// ByteBufRule fails the whole class for it. The oracle below is the same either way: no session, no
		// handler, a client that fails instead of hanging.
		JsonRpcWsServlet wsServlet = newServlet();
		RefInt gateCalls = new RefInt(0);
		RefInt upgradesDelivered = new RefInt(0);
		WebSocketServlet gate = new WebSocketServlet(reactor()) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				gateCalls.inc();
				return Promise.ofException(new ExpectedException("the admission gate blew up"));
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				upgradesDelivered.inc();                       // never: the gate never answered 101
				wsServlet.onWebSocket(webSocket);
			}
		};

		WsPair pair = WsPair.serverUpgrade(reactor(), gate);
		Exception e = awaitException(JsonRpcWsTransport.connect(reactor(), pair.client(),
			HttpRequest.get("ws://127.0.0.1:" + pair.port()).build()));

		// the client fails — it does not hang — and it fails with the same handshake failure a refusing
		// status code produces; the gate's own exception never travels (FR-061)
		assertThat("the failing gate surfaces as HANDSHAKE_FAILED, not " + e, e, instanceOf(HttpException.class));
		assertEquals("Failed to perform a proper opening handshake", e.getMessage());
		assertEquals("the gate was consulted exactly once", 1, gateCalls.get());
		assertEquals("the failing gate never delivered a socket to the servlet", 0, upgradesDelivered.get());
		assertEquals("the refused connection never became a session", Set.of(), wsServlet.sessions());
		pair.closeAll();
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring helpers.
	// ---------------------------------------------------------------------------------------------------

	private static JsonRpcWsServlet newServlet() {
		return JsonRpcWsServlet.builder(reactor(), serverDispatcher()).build();
	}

	/** The servlet whose dispatcher also carries the never-answering HangApi (US5-2's hang service). */
	private static JsonRpcWsServlet newServletWithHangService() {
		return JsonRpcWsServlet.builder(reactor(), JsonRpcDispatcher.builder(reactor())
				.withService(TestApi.class, new TestApiImpl())
				.withService(HangApi.class, new HangApiImpl())
				.build())
			.build();
	}

	/** The single server-side service table: what a client calls the server with. */
	private static JsonRpcDispatcher serverDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(TestApi.class, new TestApiImpl())
			.build();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
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
}
