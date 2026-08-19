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
import io.activej.http.AbstractHttpConnection;
import io.activej.http.HttpServerConnection;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketException;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.HangApi;
import io.activej.jsonrpc.transport.ws.fixtures.HangApiImpl;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ByteBufRule.IgnoreLeaks;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * User story 3 — a dropped connection strands no promise on either side (T013, US3, FR-053, FR-094,
 * FR-078b): the full purge matrix. For each of the four close kinds, three server-initiated calls
 * (via the session proxy) and three client-initiated calls (via the client proxy) are in flight —
 * the {@link HangApi} implementation <b>never answers</b>, so the only way out is the drop — and the
 * connection is then cut one of four ways:
 * <ul>
 *     <li><b>abrupt TCP loss</b> — the server's underlying {@link ITcpSocket} is closed without any
 *     WebSocket close frame, so the peer's read hits EOF mid-stream and fails with close {@code 1006}
 *     ({@code "Peer did not send CLOSE frame"}), while this side's read fails with the injected
 *     cause;</li>
 *     <li><b>peer clean close (1000)</b> — one side sends a pure close frame with no cause; the
 *     receiving side sees {@code readMessage() == null} / {@code onClosed(null)} and every call in
 *     flight fails with {@link AsyncCloseException};</li>
 *     <li><b>peer error close (non-1000)</b> — one side closes with a {@link WebSocketException}
 *     (code {@code 4001}); the peer's calls fail with that cause;</li>
 *     <li><b>local {@code closeEx}</b> — the session is closed locally with an explicit
 *     {@link WebSocketException} (code {@code 4000}); the cause travels over the wire and the peer's
 *     calls fail with it.</li>
 * </ul>
 * Every row asserts: all six pending promises fail with the close cause or {@link AsyncCloseException}
 * (the error taxonomy of contracts/session-api.md), both {@code inFlightCount()}s read zero, each
 * peer's {@code onClosed} fired exactly once, no failure-handler report, and a call issued from a
 * failure continuation fails immediately without registering a table entry or touching the wire
 * (feature 012 FR-078b, inherited unchanged). {@link ActivePromisesRule} is the final arbiter: a
 * strand leaves a tracked promise pending and fails the class.
 * <p>
 * <b>Session construction.</b> The server side is a real {@link JsonRpcWsSession} built directly
 * (same package, exactly as {@link JsonRpcWsServlet#onWebSocket} does) rather than through the
 * servlet: the servlet's registry hides the transport listener behind its own deregistration, and
 * US3's exactly-once {@code onClosed} assertion needs that signal <i>observable</i>. The session's
 * constructor takes the {@code onClosed} callback the servlet would use for deregistration; here it
 * counts exactly-once instead. The client side mirrors the session's close observation with a
 * counting {@link JsonRpcTransport} wrapper, because the client's {@link JsonRpcClient} occupies the
 * transport's single listener slot.
 * <p>
 * <b>Quiescence (R3).</b> {@code TestUtils.await} runs the eventloop to quiescence, so a drop that
 * fails to close a channel — the purge gap this test exists to catch — hangs the await rather than
 * passing silently. The awaited chain triggers the close and resolves only once all six calls have
 * completed. One caveat is pinned here rather than hidden: <b>core-http's WebSocket close handshake
 * does not tear down the TCP connection once a side has written data frames</b> — its
 * {@code closeSentPromise → closeReceivedPromise → close} chain never completes in that state, so
 * the harness cuts the remaining channels itself (the same mechanism {@link WsPair#closeAll()} uses)
 * <i>inside</i> the awaited chain, after the purge is fully observed. That is a reused-component
 * limitation (SC-007 forbids editing it), not a gap in this module's purge: the purge itself — every
 * call failing, both tables empty, exactly-once {@code onClosed} — is exactly what the rows assert.
 * A second core-http quirk concerns the leak rules: the <b>non-1000</b> close rows ((c), (d)) strand
 * one socket read buffer in the inbound decoder — core-http's {@code WebSocketBufsToFrames} closes
 * the decoder abruptly on a non-1000 close ({@code onCloseReceived → closeEx}), and the read buffer
 * already delivered to the {@code BinaryChannelSupplier} is never recycled (its {@code onCleanup}
 * never runs). The clean-close rows ((a), (b)) are leak-free. {@code @IgnoreLeaks} is therefore
 * class-level — a {@code @ClassRule} cannot honor a method-level opt-out — with the justification
 * below; this is the exact precedent of {@code JsonRpcWsOversizeTest} (same core-http read-pipeline
 * leak, SC-007 forbids the fix, tracked for T018). {@code WsPair.closeAll()} is then the
 * belt-and-suspenders cleanup. Rules per FR-079.
 */
@IgnoreLeaks("the non-1000 close rows (c), (d) strand one core-http socket read buffer in the "
			 + "WebSocketBufsToFrames inbound pipeline (onCloseReceived closes the decoder without "
			 + "recycling the BinaryChannelSupplier's bufs) — reproduced identically by "
			 + "JsonRpcWsOversizeTest. Not a JsonRpcWsTransport leak: its only ByteBuf ownership is "
			 + "the BINARY refusal path (R8), proven leak-free in JsonRpcWsHostileTest, and the "
			 + "clean-close rows (a), (b) are verified leak-free. Core modules may not be modified "
			 + "(SC-007); reported for the Phase 6 hardening pass (T018).")
public final class JsonRpcWsPurgeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The four close kinds of the US3 matrix (FR-094). */
	private enum CloseKind {
		ABRUPT_TCP_LOSS,
		PEER_CLEAN_CLOSE,
		PEER_ERROR_CLOSE,
		LOCAL_CLOSE_EX
	}

	/** Calls per direction of the matrix (US3 independent test: three each way). */
	private static final int CALLS_PER_DIRECTION = 3;

	/** The close code of the peer-error row (a non-1000 application code). */
	private static final int PEER_ERROR_CODE = 4001;

	/** The close code of the local-closeEx row. */
	private static final int LOCAL_CLOSE_CODE = 4000;

	/**
	 * A notification the server's dispatcher does not know, queued behind the client's three requests
	 * in the write queue: when its send completes, every request frame has been written, so the raw
	 * clean-close write that follows can never race a transport write ({@code WebSocket} forbids
	 * concurrent writes, R7/FR-017). The server ignores it — §4.1 forbids answering a notification.
	 */
	private static final byte[] FLUSH_MARKER = "{\"jsonrpc\":\"2.0\",\"method\":\"purge.flush\"}".getBytes(UTF_8);

	@Test
	public void testAbruptTcpLossPurgesCallsInBothDirections() {
		runPurge(CloseKind.ABRUPT_TCP_LOSS);
	}

	@Test
	public void testPeerCleanClosePurgesCallsInBothDirections() {
		runPurge(CloseKind.PEER_CLEAN_CLOSE);
	}

	@Test
	public void testPeerErrorClosePurgesCallsInBothDirections() {
		runPurge(CloseKind.PEER_ERROR_CLOSE);
	}

	@Test
	public void testLocalCloseExPurgesCallsInBothDirections() {
		runPurge(CloseKind.LOCAL_CLOSE_EX);
	}

	// ---------------------------------------------------------------------------------------------------
	// The matrix.
	// ---------------------------------------------------------------------------------------------------

	private void runPurge(CloseKind kind) {
		List<Exception> unexpectedFailures = new ArrayList<>();
		Ref<IWebSocket> serverWebSocket = new Ref<>();
		Ref<JsonRpcWsSession> session = new Ref<>();
		Ref<JsonRpcClient> client = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<ITcpSocket> serverTcp = new Ref<>();
		Ref<ITcpSocket> clientTcp = new Ref<>();
		Ref<Exception> serverClosed = new Ref<>();
		RefInt serverOnClosedCount = new RefInt(0);
		Ref<Exception> clientClosed = new Ref<>();
		RefInt clientOnClosedCount = new RefInt(0);
		RefInt serverInFlightBeforeClose = new RefInt(-1);
		RefInt clientInFlightBeforeClose = new RefInt(-1);
		List<Promise<String>> serverCalls = new ArrayList<>();
		List<Promise<String>> clientCalls = new ArrayList<>();
		List<Exception> serverCallFailures = new ArrayList<>();
		List<Exception> clientCallFailures = new ArrayList<>();
		SettablePromise<Void> allDrained = new SettablePromise<>();
		RefInt drainedCount = new RefInt(0);
		Ref<Exception> reissuedServerError = new Ref<>();
		RefInt reissuedServerInFlight = new RefInt(-1);
		Ref<Exception> reissuedClientError = new Ref<>();
		RefInt reissuedClientInFlight = new RefInt(-1);

		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			serverWebSocket.set(ws);
			serverTcp.set(tcpSocketOf(ws));
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			// the server-side peer: a real session whose onClosed is directly countable (see the class
			// Javadoc — the servlet's registry would hide it)
			session.set(new JsonRpcWsSession(reactor(), transport,
				hangDispatcher(), JsonCodecFactory.defaultInstance(),
				unexpectedFailures::add,
				e -> {
					serverClosed.set(e);
					serverOnClosedCount.inc();
				}));
		});

		await(pair.connect().then(ws -> {
			clientTcp.set(tcpSocketOf(ws));
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			client.set(JsonRpcClient.builder(reactor(), countingClose(clientTransport.get(), clientClosed, clientOnClosedCount))
				.withPeerHandler(hangDispatcher())
				.withFailureHandler(unexpectedFailures::add)
				.build());

			for (int i = 0; i < CALLS_PER_DIRECTION; i++) {
				Promise<String> serverCall = session.get().proxy(HangApi.class).request(i);
				Promise<String> clientCall = client.get().proxy(HangApi.class).request(i);
				serverCalls.add(serverCall);
				clientCalls.add(clientCall);
				serverCall.whenComplete(($, e) -> {
					if (e != null) serverCallFailures.add(e);
					if (drainedCount.inc() == CALLS_PER_DIRECTION * 2) allDrained.set(null);
				});
				clientCall.whenComplete(($, e) -> {
					if (e != null) clientCallFailures.add(e);
					if (drainedCount.inc() == CALLS_PER_DIRECTION * 2) allDrained.set(null);
				});
			}
			serverInFlightBeforeClose.set(session.get().inFlightCount());
			clientInFlightBeforeClose.set(client.get().inFlightCount());

			return triggerClose(kind, ws, clientTransport.get(), session.get(), serverWebSocket.get())
				.then($ -> allDrained)
				.whenResult($ -> {
					// R3 quiescence: core-http's close handshake leaves the TCP channels open once a
					// side has written data frames (see the class Javadoc), so after the purge is
					// observed the harness cuts the remaining channels itself — the same mechanism
					// WsPair.closeAll() uses, moved inside the awaited chain so TestUtils.await can
					// reach quiescence.
					serverTcp.get().closeEx(new AsyncCloseException("purge observed; harness teardown"));
					clientTcp.get().closeEx(new AsyncCloseException("purge observed; harness teardown"));
				});
		}));

		// the matrix ran with calls genuinely in flight when the connection was cut
		assertEquals("the server had " + CALLS_PER_DIRECTION + " calls in flight before the close",
			CALLS_PER_DIRECTION, serverInFlightBeforeClose.get());
		assertEquals("the client had " + CALLS_PER_DIRECTION + " calls in flight before the close",
			CALLS_PER_DIRECTION, clientInFlightBeforeClose.get());

		// every pending promise failed with the close cause or AsyncCloseException
		assertEquals("every server-initiated call failed", CALLS_PER_DIRECTION, serverCallFailures.size());
		assertEquals("every client-initiated call failed", CALLS_PER_DIRECTION, clientCallFailures.size());
		for (Exception e : serverCallFailures) assertFailureCause(kind, true, e);
		for (Exception e : clientCallFailures) assertFailureCause(kind, false, e);

		// both correlation tables drained
		assertEquals("the server's table drained", 0, session.get().inFlightCount());
		assertEquals("the client's table drained", 0, client.get().inFlightCount());

		// exactly-once onClosed on both peers, with the row's cause
		assertOnClosed(kind, serverClosed.get(), clientClosed.get());
		assertEquals("the server-side transport signalled onClosed exactly once", 1, serverOnClosedCount.get());
		assertEquals("the client-side transport signalled onClosed exactly once", 1, clientOnClosedCount.get());

		// nothing escaped to the failure handlers
		assertEquals("no failure-handler report", List.of(), unexpectedFailures);

		// FR-078b: a call issued from a failure continuation — here, the continuation observing a
		// drained call — fails immediately with the close cause and registers no table entry
		serverCalls.get(0).whenException(e -> {
			reissuedServerError.set(session.get().proxy(HangApi.class).request(99).getException());
			reissuedServerInFlight.set(session.get().inFlightCount());
		});
		clientCalls.get(0).whenException(e -> {
			reissuedClientError.set(client.get().proxy(HangApi.class).request(99).getException());
			reissuedClientInFlight.set(client.get().inFlightCount());
		});
		assertTrue("the post-close server call failed immediately (FR-078b): " + reissuedServerError.get(),
			reissuedServerError.get() != null);
		assertTrue("the post-close client call failed immediately (FR-078b): " + reissuedClientError.get(),
			reissuedClientError.get() != null);
		assertFailureCause(kind, true, reissuedServerError.get());
		assertFailureCause(kind, false, reissuedClientError.get());
		assertEquals("the post-close server call registered no table entry", 0, reissuedServerInFlight.get());
		assertEquals("the post-close client call registered no table entry", 0, reissuedClientInFlight.get());

		pair.closeAll();
	}

	/**
	 * Initiates the row's close and returns a promise that resolves once it has been issued. The
	 * await's chain then waits for {@code allDrained} — the moment every pending call has completed —
	 * which is also the point at which {@code TestUtils.await} has run the loop to quiescence.
	 */
	private static Promise<Void> triggerClose(CloseKind kind, IWebSocket clientWebSocket,
		JsonRpcWsTransport clientTransport, JsonRpcWsSession session, IWebSocket serverWebSocket) {
		return switch (kind) {
			case ABRUPT_TCP_LOSS -> {
				// (a) kill the TCP without a WebSocket close frame: the peer's read hits EOF mid-stream
				// and fails with 1006 "Peer did not send CLOSE frame"; this side's read fails with the
				// injected cause (FR-094)
				killServerTcp(serverWebSocket);
				yield Promise.complete();
			}
			case PEER_CLEAN_CLOSE -> clientTransport.send(FLUSH_MARKER)
				// the marker is queued behind the client's three requests, so when it completes every
				// request frame is written and the raw clean-close write can never race (R7)
				.then($ -> clientWebSocket.writeMessage(null));
			case PEER_ERROR_CLOSE -> {
				clientWebSocket.closeEx(new WebSocketException(PEER_ERROR_CODE, "client going away"));
				yield Promise.complete();
			}
			case LOCAL_CLOSE_EX -> {
				session.closeEx(new WebSocketException(LOCAL_CLOSE_CODE, "server shutdown"));
				yield Promise.complete();
			}
		};
	}

	// ---------------------------------------------------------------------------------------------------
	// Assertions.
	// ---------------------------------------------------------------------------------------------------

	private static void assertFailureCause(CloseKind kind, boolean serverSide, Exception e) {
		switch (kind) {
			case ABRUPT_TCP_LOSS -> {
				if (serverSide) {
					// this side's read failed with the injected cause
					assertTrue("the server-side call failed with the injected close cause, not " + e,
						e instanceof AsyncCloseException);
				} else {
					// the peer saw an abrupt EOF with no close frame
					assertWebSocketCode(e, 1006);
				}
			}
			case PEER_CLEAN_CLOSE -> assertTrue("a clean close fails with AsyncCloseException, not " + e,
				e instanceof AsyncCloseException);
			case PEER_ERROR_CLOSE -> assertWebSocketCode(e, PEER_ERROR_CODE);
			case LOCAL_CLOSE_EX -> assertWebSocketCode(e, LOCAL_CLOSE_CODE);
		}
	}

	private static void assertOnClosed(CloseKind kind, @Nullable Exception serverClosed, @Nullable Exception clientClosed) {
		switch (kind) {
			case ABRUPT_TCP_LOSS -> {
				assertTrue("the server's onClosed carried the injected cause, not " + serverClosed,
					serverClosed instanceof AsyncCloseException);
				assertWebSocketCode(clientClosed, 1006);
			}
			case PEER_CLEAN_CLOSE -> {
				assertNull("the clean close fires onClosed(null), not " + serverClosed, serverClosed);
				assertNull("the clean close fires onClosed(null), not " + clientClosed, clientClosed);
			}
			case PEER_ERROR_CLOSE -> {
				assertWebSocketCode(serverClosed, PEER_ERROR_CODE);
				assertWebSocketCode(clientClosed, PEER_ERROR_CODE);
			}
			case LOCAL_CLOSE_EX -> {
				assertWebSocketCode(serverClosed, LOCAL_CLOSE_CODE);
				assertWebSocketCode(clientClosed, LOCAL_CLOSE_CODE);
			}
		}
	}

	private static void assertWebSocketCode(@Nullable Exception e, int code) {
		assertThat("the close carried a WebSocketException with code " + code + ", not " + e,
			e, instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(code), ((WebSocketException) e).getCode());
	}

	// ---------------------------------------------------------------------------------------------------
	// Wiring helpers.
	// ---------------------------------------------------------------------------------------------------

	/** A dispatcher whose one registered service never answers — both sides of the matrix's pending calls. */
	private static JsonRpcDispatcher hangDispatcher() {
		return JsonRpcDispatcher.builder(reactor())
			.withService(HangApi.class, new HangApiImpl())
			.build();
	}

	/**
	 * Mirrors {@link JsonRpcWsSession}'s close observation on the client side: the client's
	 * {@link JsonRpcClient} occupies the transport's single listener slot, so this wrapper interposes
	 * on {@code onClosed} to count it exactly-once for the assertion — everything else is the
	 * delegate's own.
	 */
	private static JsonRpcTransport countingClose(JsonRpcWsTransport transport, Ref<Exception> closed, RefInt onClosedCount) {
		return new JsonRpcTransport() {
			@Override
			public Promise<Void> send(byte[] document) {
				return transport.send(document);
			}

			@Override
			public void setListener(Listener listener) {
				transport.setListener(new Listener() {
					@Override
					public void onDocument(byte[] document) {
						listener.onDocument(document);
					}

					@Override
					public void onClosed(@Nullable Exception e) {
						closed.set(e);
						onClosedCount.inc();
						listener.onClosed(e);
					}
				});
			}

			@Override
			public void closeEx(Exception e) {
				transport.closeEx(e);
			}
		};
	}

	/**
	 * Reaches the server connection's underlying {@link ITcpSocket} and closes it with a cause — the
	 * low-level kill of the abrupt row: the TCP stream ends without any WebSocket close frame, so the
	 * peer's decoder fails the mid-stream read with {@code 1006} ({@code CLOSE_FRAME_MISSING}) while
	 * this side's own read fails with the injected cause. The connection is reached the same way
	 * {@code core-http}'s own tests do ({@code request.getConnection().socket}); the field is
	 * protected in {@link AbstractHttpConnection}, so a test outside {@code io.activej.http} reads it
	 * reflectively.
	 */
	private static void killServerTcp(IWebSocket serverWebSocket) {
		HttpServerConnection connection = (HttpServerConnection) serverWebSocket.getRequest().getConnection();
		try {
			Field socketField = AbstractHttpConnection.class.getDeclaredField("socket");
			socketField.setAccessible(true);
			ITcpSocket socket = (ITcpSocket) socketField.get(connection);
			socket.closeEx(new AsyncCloseException("the server's connection was killed abruptly"));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not reach the server's underlying socket", e);
		}
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private static ITcpSocket tcpSocketOf(IWebSocket webSocket) {
		try {
			// server side: the request carries the HttpServerConnection; client side: the response does
			AbstractHttpConnection connection = webSocket.getRequest().getConnection() != null
				? webSocket.getRequest().getConnection()
				: webSocket.getResponse().getConnection();
			Field socketField = AbstractHttpConnection.class.getDeclaredField("socket");
			socketField.setAccessible(true);
			return (ITcpSocket) socketField.get(connection);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not reach the connection's underlying socket", e);
		}
	}
}
