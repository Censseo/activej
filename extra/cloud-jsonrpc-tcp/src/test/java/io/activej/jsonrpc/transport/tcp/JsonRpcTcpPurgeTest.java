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
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.json.JsonCodecFactory;
import io.activej.jsonrpc.service.JsonRpcClient;
import io.activej.jsonrpc.service.JsonRpcDispatcher;
import io.activej.jsonrpc.service.JsonRpcMethod;
import io.activej.jsonrpc.service.JsonRpcParam;
import io.activej.jsonrpc.service.JsonRpcService;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.net.SocketSettings;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * User story 2 — a dropped or truncated connection strands no promise on either side (T013, FR-053,
 * FR-094, FR-096): the full purge matrix, over <b>real socket pairs</b>. For each of the four close
 * kinds, {@value #CALLS_PER_DIRECTION} calls are in flight client&rarr;server <i>and</i>
 * {@value #CALLS_PER_DIRECTION} server&rarr;client — {@link HangApi}'s implementations on both sides
 * <b>never answer</b>, so the only way out of any of them is the connection dying — and the
 * connection is then cut one of four ways:
 * <ul>
 *     <li><b>abrupt socket loss</b> — the client's underlying {@link TcpSocket} is closed with
 *     {@code SO_LINGER 0}, so the peer receives a reset rather than an orderly end-of-stream: the
 *     server's read fails with the {@link IOException} the reset produces, and this side's own read
 *     loop fails with the cause its CSP chain reports (see {@code assertClientCause} — the two shapes
 *     an externally-killed socket can take, and why the row asserts the invariant rather than one of
 *     them);</li>
 *     <li><b>peer end-of-stream exactly on a message boundary</b> — the client half-closes its output
 *     ({@code socket.write(null)} → {@code shutdownOutput}) right after complete documents, so the
 *     server's decoder sees end-of-stream with nothing accumulated: {@code onClosed(null)}, and every
 *     call in flight fails with {@link AsyncCloseException} (FR-019, US2 scenario 3);</li>
 *     <li><b>peer end-of-stream mid-message</b> — the client writes a <i>partial</i> document (no
 *     terminator) and then half-closes, so the server's decoder ends with bytes accumulated:
 *     {@link TruncatedDataException}, the partial accumulation recycled by the framing stream's own
 *     cleanup — {@link ByteBufRule} is the assertion for that half (FR-019/FR-096, US2 scenario 2);</li>
 *     <li><b>local {@code closeEx}</b> — the session is closed locally with an explicit cause, which
 *     purges the server's table with that very instance and closes the connection under the client.</li>
 * </ul>
 * Every row asserts, on <b>both</b> peers: all {@value #CALLS_PER_DIRECTION} + {@value
 * #CALLS_PER_DIRECTION} pending promises fail with the close cause (or {@link AsyncCloseException}
 * when the close carried none — {@code JsonRpcClient}'s documented substitute, FR-078a), both
 * {@code inFlightCount()}s read zero, each side's close is signalled <b>exactly once</b> with the
 * row's cause, and nothing reached either failure handler. {@link ActivePromisesRule} is the final
 * arbiter of the headline claim: a stranded call leaves the awaited chain unfinished and hangs — it
 * cannot pass silently.
 * <p>
 * <b>The no-reentry rule (US2 scenario 4, feature 012's FR-078b inherited unchanged).</b> Each row
 * additionally issues, <i>from a continuation of a call the purge itself failed</i>, a fresh call on
 * the same client and the same session: both fail immediately, and the send counter recorded just
 * before the close is unchanged afterwards — the reissued calls registered no table entry and never
 * touched the wire. A direct {@code send} on the closed transport is refused with the close cause,
 * and a second {@code closeEx} on transport and session alike fires nothing: a closed connection
 * cannot be resurrected and its close cannot be double-fired.
 * <p>
 * <b>Why the peers are built by hand.</b> The server side is a real {@link JsonRpcTcpSession}
 * constructed directly (same package, exactly as {@link JsonRpcTcpServer#serve} does) on a socket
 * accepted by an {@code acceptOnce} {@link SimpleServer} bound to port {@code 0} and asked where it
 * landed (ADR-028): the session's constructor takes the {@code onClosed} callback the server would
 * use for deregistration, and this test counts it instead — exactly-once is the property under test,
 * so the signal has to be <i>observable</i>. The client side mirrors that with a counting
 * {@link JsonRpcTransport} wrapper, because its {@link JsonRpcClient} occupies the transport's single
 * listener slot. Both peers hold their raw {@link ITcpSocket}, which is what makes the abrupt row a
 * genuine reset and the two end-of-stream rows genuine half-closes rather than simulations.
 * <p>
 * <b>Quiescence.</b> {@code TestUtils.await} runs the loop until nothing is left, so every row must
 * end with both sockets closed — which each close kind achieves by itself here: a peer that observes
 * end-of-stream or a reset closes its own side too (contract §4). A purge gap therefore hangs the
 * suite instead of passing. Rules per FR-079; {@code @IgnoreLeaks} is forbidden module-wide (FR-024)
 * and does not appear.
 */
public final class JsonRpcTcpPurgeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** The four close kinds of the FR-094 matrix. */
	private enum CloseKind {
		ABRUPT_SOCKET_LOSS,
		PEER_EOS_ON_BOUNDARY,
		PEER_EOS_MID_MESSAGE,
		LOCAL_CLOSE_EX
	}

	/** Calls per direction (US2 scenario 1's "N calls one way and M the other"). */
	private static final int CALLS_PER_DIRECTION = 3;

	/** A document cut short of its terminator — the mid-message end-of-stream row's payload. */
	private static final String PARTIAL_DOCUMENT = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"hang.ca";

	/** The document a post-close {@code send} would have put on the wire, if the transport allowed it. */
	private static final byte[] POST_CLOSE_DOCUMENT =
		"{\"jsonrpc\":\"2.0\",\"method\":\"hang.after\"}".getBytes(UTF_8);

	@Test
	public void testAbruptSocketLossPurgesCallsInBothDirections() {
		runPurge(CloseKind.ABRUPT_SOCKET_LOSS);
	}

	@Test
	public void testPeerEndOfStreamOnAMessageBoundaryPurgesCallsInBothDirections() {
		runPurge(CloseKind.PEER_EOS_ON_BOUNDARY);
	}

	@Test
	public void testPeerEndOfStreamMidMessagePurgesCallsInBothDirections() {
		runPurge(CloseKind.PEER_EOS_MID_MESSAGE);
	}

	@Test
	public void testLocalCloseExPurgesCallsInBothDirections() {
		runPurge(CloseKind.LOCAL_CLOSE_EX);
	}

	// -------------------------------------------------------------------------------------------
	// The matrix.
	// -------------------------------------------------------------------------------------------

	private void runPurge(CloseKind kind) {
		NioReactor reactor = reactor();
		ExpectedException injected = new ExpectedException("the connection was cut: " + kind);

		List<Exception> unexpectedFailures = new ArrayList<>();
		HangingService serverService = new HangingService();
		HangingService clientService = new HangingService();

		Ref<JsonRpcTcpSession> sessionRef = new Ref<>();
		Ref<JsonRpcClient> clientRef = new Ref<>();
		Ref<JsonRpcTcpTransport> clientTransportRef = new Ref<>();
		Ref<CountingTransport> countingRef = new Ref<>();

		Ref<Exception> serverClosed = new Ref<>();
		RefInt serverClosedCount = new RefInt(0);

		List<Exception> serverCallFailures = new ArrayList<>();
		List<Exception> clientCallFailures = new ArrayList<>();
		List<Promise<String>> serverCalls = new ArrayList<>();
		List<Promise<String>> clientCalls = new ArrayList<>();
		RefInt serverInFlightBeforeClose = new RefInt(-1);
		RefInt clientInFlightBeforeClose = new RefInt(-1);
		RefInt sendsBeforeClose = new RefInt(-1);
		RefInt drained = new RefInt(0);
		SettablePromise<Void> allDrained = new SettablePromise<>();

		Ref<Exception> reissuedFromServerDrain = new Ref<>();
		Ref<Exception> reissuedFromClientDrain = new Ref<>();

		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer server = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		listen(server);

		await(TcpSocket.connect(reactor, boundAddress(server), null, settingsFor(kind))
			.then(clientSocket -> accepted.then(serverSocket -> {
				// the server peer: a real session, its onClosed observable rather than hidden behind a
				// server's deregistration (see the class Javadoc)
				JsonRpcTcpSession session = new JsonRpcTcpSession(reactor,
					JsonRpcTcpTransport.of(reactor, serverSocket),
					JsonRpcDispatcher.builder(reactor).withService(HangApi.class, serverService).build(),
					JsonCodecFactory.defaultInstance(),
					unexpectedFailures::add,
					e -> {
						serverClosed.set(e);
						serverClosedCount.inc();
					});
				sessionRef.set(session);

				// the client peer: a real client whose peer handler answers nothing either, wrapped so its
				// close signal and its sends are countable
				JsonRpcTcpTransport clientTransport = JsonRpcTcpTransport.of(reactor, clientSocket);
				clientTransportRef.set(clientTransport);
				CountingTransport counting = new CountingTransport(clientTransport);
				countingRef.set(counting);
				JsonRpcClient client = JsonRpcClient.builder(reactor, counting)
					.withPeerHandler(JsonRpcDispatcher.builder(reactor)
						.withService(HangApi.class, clientService)
						.build())
					.withFailureHandler(unexpectedFailures::add)
					.build();
				clientRef.set(client);

				for (int i = 0; i < CALLS_PER_DIRECTION; i++) {
					clientCalls.add(track(client.proxy(HangApi.class).call(i),
						clientCallFailures, drained, allDrained));
				}

				// the server has dispatched every client-initiated call: the client's table is populated and
				// the documents genuinely crossed the wire, so the close below cuts calls truly in flight
				return serverService.arrived(CALLS_PER_DIRECTION)
					.then(() -> {
						for (int i = 0; i < CALLS_PER_DIRECTION; i++) {
							serverCalls.add(track(session.proxy(HangApi.class).call(i),
								serverCallFailures, drained, allDrained));
						}
						return clientService.arrived(CALLS_PER_DIRECTION);
					})
					.then(() -> {
						serverInFlightBeforeClose.set(session.inFlightCount());
						clientInFlightBeforeClose.set(client.inFlightCount());
						sendsBeforeClose.set(counting.sends);

						// US2 scenario 4: user code reacting to a purged call by issuing a new one, while the
						// drain is still running. Both must fail immediately, register nothing and send nothing.
						serverCalls.get(0).whenException(e ->
							reissuedFromServerDrain.set(session.proxy(HangApi.class).call(99).getException()));
						clientCalls.get(0).whenException(e ->
							reissuedFromClientDrain.set(client.proxy(HangApi.class).call(99).getException()));

						return triggerClose(kind, clientSocket, session, injected);
					})
					.then(() -> allDrained)
					.whenComplete(($, e) -> {
						// belt and braces: each close kind already closes both ends, and closing twice is a
						// no-op — but a leftover socket would hang the loop rather than fail the assertion
						clientSocket.close();
						serverSocket.close();
						server.close();
					});
			})));

		JsonRpcTcpSession session = sessionRef.get();
		JsonRpcClient client = clientRef.get();
		CountingTransport counting = countingRef.get();

		// the matrix ran with calls genuinely in flight in both directions when the connection was cut
		assertEquals("the server had " + CALLS_PER_DIRECTION + " calls in flight before the close",
			CALLS_PER_DIRECTION, serverInFlightBeforeClose.get());
		assertEquals("the client had " + CALLS_PER_DIRECTION + " calls in flight before the close",
			CALLS_PER_DIRECTION, clientInFlightBeforeClose.get());

		// every pending promise on both peers completed exceptionally, with the row's cause
		assertEquals("every server-initiated call failed", CALLS_PER_DIRECTION, serverCallFailures.size());
		assertEquals("every client-initiated call failed", CALLS_PER_DIRECTION, clientCallFailures.size());
		for (Exception e : serverCallFailures) assertServerCause(kind, injected, serverClosed.get(), e);
		for (Exception e : clientCallFailures) assertClientCause(kind, injected, counting.closeCause, e);

		// both correlation tables drained (FR-053)
		assertEquals("the server's correlation table is empty", 0, session.inFlightCount());
		assertEquals("the client's correlation table is empty", 0, client.inFlightCount());

		// exactly-once close on both peers, carrying the row's cause (FR-025/FR-094)
		assertEquals("the server side signalled its close exactly once", 1, serverClosedCount.get());
		assertEquals("the client side signalled its close exactly once", 1, counting.closes);
		assertCloseCause(kind, injected, serverClosed.get(), counting.closeCause);

		// nothing escaped to either failure handler
		assertEquals("no failure-handler report", List.of(), unexpectedFailures);

		// US2 scenario 4 / FR-078b: the calls reissued from inside the drain failed immediately …
		assertNotNull("the server-side reissue failed immediately", reissuedFromServerDrain.get());
		assertNotNull("the client-side reissue failed immediately", reissuedFromClientDrain.get());
		assertServerCause(kind, injected, serverClosed.get(), reissuedFromServerDrain.get());
		assertClientCause(kind, injected, counting.closeCause, reissuedFromClientDrain.get());
		// … and neither registered a table entry nor put a byte on the wire
		assertEquals("the reissued calls registered no table entry", 0, session.inFlightCount());
		assertEquals("the reissued calls registered no table entry", 0, client.inFlightCount());
		assertEquals("no document left the client after the close", sendsBeforeClose.get(), counting.sends);

		// a call issued after the purge, from ordinary code, is refused the same way
		Promise<String> afterPurge = client.proxy(HangApi.class).call(100);
		assertTrue("a post-close call fails immediately", afterPurge.isException());
		assertClientCause(kind, injected, counting.closeCause, afterPurge.getException());

		// the no-reentry rule at the transport itself: a closed transport stays closed, refuses the send
		// with the close cause, and a second close fires nothing
		JsonRpcTcpTransport clientTransport = clientTransportRef.get();
		assertTrue("the transport stayed closed", clientTransport.isClosed());
		Promise<Void> refused = clientTransport.send(POST_CLOSE_DOCUMENT);
		assertTrue("a post-close send fails immediately", refused.isException());
		assertNotNull(refused.getException());
		clientTransport.closeEx(new ExpectedException("a second close is a no-op"));
		session.closeEx(new ExpectedException("a second close is a no-op"));
		assertEquals("the client's close was not fired again", 1, counting.closes);
		assertEquals("the server's close was not fired again", 1, serverClosedCount.get());
		assertEquals("no document left the client after the close", sendsBeforeClose.get(), counting.sends);
	}

	/**
	 * Cuts the connection the row's way and resolves once the cut has been issued; the awaited chain then
	 * waits for {@code allDrained}, so the assertions see a purge that has fully run on both peers.
	 */
	private static Promise<Void> triggerClose(
		CloseKind kind, ITcpSocket clientSocket, JsonRpcTcpSession session, Exception injected
	) {
		return switch (kind) {
			case ABRUPT_SOCKET_LOSS -> {
				// SO_LINGER 0 makes this close a reset rather than an orderly FIN, so the peer's read fails
				// with an IOException instead of observing a clean end-of-stream
				clientSocket.closeEx(injected);
				yield Promise.complete();
			}
			// end of output exactly on a message boundary: every document written, nothing accumulated
			case PEER_EOS_ON_BOUNDARY -> clientSocket.write(null);
			// a document cut short of its terminator, then end of output: the peer ends mid-accumulation
			case PEER_EOS_MID_MESSAGE -> clientSocket
				.write(ByteBuf.wrapForReading(PARTIAL_DOCUMENT.getBytes(UTF_8)))
				.then(() -> clientSocket.write(null));
			case LOCAL_CLOSE_EX -> {
				session.closeEx(injected);
				yield Promise.complete();
			}
		};
	}

	// -------------------------------------------------------------------------------------------
	// Assertions.
	// -------------------------------------------------------------------------------------------

	/** What a server-side (server&rarr;client) call must have failed with, per row. */
	private static void assertServerCause(
		CloseKind kind, ExpectedException injected, @Nullable Exception serverClosed, Exception e
	) {
		switch (kind) {
			// the reset surfaces on this side as the I/O failure of the pending read
			case ABRUPT_SOCKET_LOSS -> assertThat(
				"the server-side call failed with the reset's I/O failure, not " + e,
				e, instanceOf(IOException.class));
			// a clean end-of-stream carries no cause, so the client substitutes AsyncCloseException (FR-078a)
			case PEER_EOS_ON_BOUNDARY -> assertThat(
				"a clean peer close fails calls with AsyncCloseException, not " + e,
				e, instanceOf(AsyncCloseException.class));
			case PEER_EOS_MID_MESSAGE -> {
				assertThat("a truncated stream fails calls with TruncatedDataException, not " + e,
					e, instanceOf(TruncatedDataException.class));
				assertSame("the purge used the very cause the close carried", serverClosed, e);
			}
			case LOCAL_CLOSE_EX -> assertSame("the local close cause reaches every purged call", injected, e);
		}
	}

	/** What a client-side (client&rarr;server) call must have failed with, per row. */
	private static void assertClientCause(
		CloseKind kind, ExpectedException injected, @Nullable Exception clientClosed, Exception e
	) {
		switch (kind) {
			case ABRUPT_SOCKET_LOSS -> {
				// The socket died under a live transport, so which cause the CSP chain reports depends on
				// where its read loop was parked: the injected exception when a socket read was pending
				// (TcpSocket.closeEx fails it with the cause), a bare AsyncCloseException when the next
				// read found the socket already closed (TcpSocket.read()'s isClosed() branch — the shape
				// this row actually observes, because ChannelSuppliers.ofSocket's zero-buffer prefetch is
				// parked on the consumer's take, not on a read). Both are "the close cause" as far as the
				// SPI is concerned (FR-078a), so what this row pins is the invariant that matters and not
				// an artefact of the composition: every purged call carries the very cause the close
				// signalled, and that cause is one of the two.
				assertSame("every purged call carries the cause the close signalled", clientClosed, e);
				assertTrue("the abrupt close carried the injected cause or AsyncCloseException, not " +
						   clientClosed,
					clientClosed == injected || clientClosed instanceof AsyncCloseException);
			}
			// the peer answers an end-of-stream with a full close (contract §4), which this side then reads
			// as its own clean end-of-stream — no cause, so AsyncCloseException
			case PEER_EOS_ON_BOUNDARY, PEER_EOS_MID_MESSAGE, LOCAL_CLOSE_EX -> {
				assertThat("the client's calls failed with AsyncCloseException, not " + e,
					e, instanceOf(AsyncCloseException.class));
				assertNull("the client observed a clean close", clientClosed);
			}
		}
	}

	private static void assertCloseCause(
		CloseKind kind, ExpectedException injected, @Nullable Exception serverClosed, @Nullable Exception clientClosed
	) {
		switch (kind) {
			case ABRUPT_SOCKET_LOSS -> {
				assertThat("the server observed the reset, not " + serverClosed,
					serverClosed, instanceOf(IOException.class));
				// the two shapes an externally-killed socket can surface as — see assertClientCause
				assertTrue("the client observed the injected cause or AsyncCloseException, not " + clientClosed,
					clientClosed == injected || clientClosed instanceof AsyncCloseException);
			}
			case PEER_EOS_ON_BOUNDARY -> {
				assertNull("end-of-stream on a boundary is a clean close: onClosed(null)", serverClosed);
				assertNull("the peer's answering close is clean too: onClosed(null)", clientClosed);
			}
			case PEER_EOS_MID_MESSAGE -> {
				assertThat("end-of-stream mid-message closes with TruncatedDataException, not " + serverClosed,
					serverClosed, instanceOf(TruncatedDataException.class));
				assertNull("the peer's answering close is clean: onClosed(null)", clientClosed);
			}
			case LOCAL_CLOSE_EX -> {
				assertSame("the local close cause is what the session observed", injected, serverClosed);
				assertNull("the peer sees the resulting end-of-stream as a clean close", clientClosed);
			}
		}
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * The one service of this test, registered on <b>both</b> peers' dispatchers: the two directions are
	 * symmetric, so one interface and two never-answering implementations cover the whole matrix. The wire
	 * name is {@code hang.call}.
	 */
	@JsonRpcService("hang")
	public interface HangApi {
		@JsonRpcMethod("call")
		Promise<String> call(@JsonRpcParam("n") int n);
	}

	/**
	 * {@link HangApi}'s never-answering implementation: it records each arrival — so a test can await
	 * "the peer has dispatched all N calls", which is what makes "in flight" a fact rather than a hope —
	 * and returns a promise nothing will ever complete. The only way out of such a call is the purge.
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

	/**
	 * Mirrors {@link JsonRpcTcpSession}'s close observation on the client side — its {@link JsonRpcClient}
	 * owns the transport's single listener slot, so this wrapper interposes on {@code onClosed} to count it
	 * — and additionally counts {@code send}s, which is how "the reissued call never touched the wire" is
	 * asserted rather than assumed. Everything else is the delegate's own.
	 */
	private static final class CountingTransport implements JsonRpcTransport {
		private final JsonRpcTcpTransport delegate;
		private int sends;
		private int closes;
		private @Nullable Exception closeCause;

		private CountingTransport(JsonRpcTcpTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public Promise<Void> send(byte[] document) {
			sends++;
			return delegate.send(document);
		}

		@Override
		public void setListener(Listener listener) {
			delegate.setListener(new Listener() {
				@Override
				public void onDocument(byte[] document) {
					listener.onDocument(document);
				}

				@Override
				public void onClosed(@Nullable Exception e) {
					closes++;
					closeCause = e;
					listener.onClosed(e);
				}
			});
		}

		@Override
		public void closeEx(Exception e) {
			delegate.closeEx(e);
		}
	}

	/** Records a call's failure and completes {@code allDrained} once every call of the row has settled. */
	private static Promise<String> track(
		Promise<String> call, List<Exception> failures, RefInt drained, SettablePromise<Void> allDrained
	) {
		call.whenComplete(($, e) -> {
			if (e != null) failures.add(e);
			if (drained.inc() == CALLS_PER_DIRECTION * 2) allDrained.set(null);
		});
		return call;
	}

	/**
	 * {@code SO_LINGER 0} for the abrupt row and nothing else: it turns the client's close into a reset,
	 * so "abrupt socket loss" is a genuinely different event from the orderly half-close of the two
	 * end-of-stream rows rather than a differently-named one.
	 */
	private static @Nullable SocketSettings settingsFor(CloseKind kind) {
		return kind == CloseKind.ABRUPT_SOCKET_LOSS ?
			SocketSettings.builder()
				.withTcpNoDelay(true)
				.withLingerTimeout(Duration.ZERO)
				.build() :
			null;
	}

	private static NioReactor reactor() {
		return Reactor.getCurrentReactor();
	}

	private static void listen(SimpleServer server) {
		try {
			server.listen();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static InetSocketAddress boundAddress(SimpleServer server) {
		// ADR-028: bind :0 and ask where it landed — never allocate a port and hope it is still free
		return server.getBoundAddresses().get(0);
	}
}
