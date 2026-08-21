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
import io.activej.async.exception.AsyncTimeoutException;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.tcp.fixtures.ClosedTcpSocket;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.promise.Promise;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Adversarial test plan, Domain B — {@code JsonRpcTcpTransport} life, races and close (feature 017).
 * Six scenarios (B1–B6), in priority order, none of which {@link JsonRpcTcpTransportTest} or
 * {@link JsonRpcTcpHostileTest} exercise today:
 *
 * <ul>
 *     <li><b>B1</b> (P1) — a generic read-side I/O failure (not EOF, not a decode fault) closes
 *     exactly once, synchronously, before any {@code send} could ever run.</li>
 *     <li><b>B2</b> (P1) — a write-side failure is a <i>different</i> code path from a read-side one
 *     (FR-026's second half): the {@code send} promise and the close both carry the write's cause.</li>
 *     <li><b>B3</b> (P1) — {@code onDocument} calling {@code closeEx} reentrantly must not let a second
 *     document already sitting in the same socket read reach the listener, and must not leak the bytes
 *     behind it.</li>
 *     <li><b>B4</b> (P1) — {@code onClosed} calling {@code send} reentrantly must fail immediately with
 *     the very cause {@code onClosed} itself received, on both a local close and a read failure.</li>
 *     <li><b>B5</b> (P2) — the one path where {@code send} fails with a cause-less
 *     {@link AsyncCloseException}: immediately after a peer's clean close.</li>
 *     <li><b>B6</b> (P2) — {@code connect(...)}'s timeout parameter is wired end to end against an
 *     address that never answers, not merely against an immediate refusal.</li>
 * </ul>
 *
 * Same harness shape as {@link JsonRpcTcpTransportTest}: real socket pairs, {@code acceptOnce} servers
 * bound to port {@code 0} and asked where they landed (ADR-028), {@code EventloopRule} +
 * {@code ByteBufRule} + {@code ActivePromisesRule}. {@link ClosedTcpSocket} (B1, B4) is the one fixture
 * already in this module's tree, used directly rather than through {@code JsonRpcTcpServer}'s zombie
 * guard, which is its only consumer so far.
 */
public final class JsonRpcTcpAdversarialTransportTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";
	private static final String RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sum\":5}}";

	// -------------------------------------------------------------------------------------------
	// B1 — a generic read-side I/O failure, distinct from the EOF/truncation branches.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testGenericIoReadFailureClosesExactlyOnceBeforeAnySendCanRun() {
		// FR-026's "an I/O failure" branch: ClosedTcpSocket.read() fails synchronously with a plain
		// AsyncCloseException — neither a MalformedDataException (framing bound / bare-LF refusal) nor a
		// TruncatedDataException (mid-message EOF), the two branches JsonRpcTcpTransportTest already pins.
		// setListener() is what starts the read loop (startReading() -> doRead()), and because every
		// promise on this path is already-completed, the whole chain — decode failure, closeEx, closeMedium,
		// signalClose, onClosed — runs SYNCHRONOUSLY inside that one call. No send could ever have reached
		// the socket in between: there was no "in between".
		List<byte[]> delivered = new ArrayList<>();
		RefInt closeCount = new RefInt(0);
		Ref<Exception> cause = new Ref<>();

		JsonRpcTcpTransport transport = JsonRpcTcpTransport.of(reactor(), new ClosedTcpSocket());

		transport.setListener(listener(delivered::add, e -> {
			closeCount.inc();
			cause.set(e);
		}));

		assertTrue("a dead-on-arrival socket can never deliver a document", delivered.isEmpty());
		assertEquals("onClosed fires exactly once, synchronously with setListener", 1, closeCount.get());
		assertNotNull(cause.get());
		assertThat("the generic I/O-failure branch", cause.get(), instanceOf(AsyncCloseException.class));
		assertFalse("distinct from the framing-bound branch", cause.get() instanceof MalformedDataException);
		assertFalse("distinct from the mid-message-EOF branch", cause.get() instanceof TruncatedDataException);
		assertTrue("closed by the time setListener returns", transport.isClosed());

		// confirms "no send could have executed": a send attempted only NOW already sees the closed
		// transport and fails with the SAME cause onClosed received — there is nothing else it could hit.
		Promise<Void> sentAfter = transport.send(REQUEST.getBytes(UTF_8));
		assertTrue(sentAfter.isException());
		assertSame(cause.get(), sentAfter.getException());
	}

	// -------------------------------------------------------------------------------------------
	// B2 — a write-side failure, with the read side kept alive to prove it is a different path.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testWriteFailureFailsTheSendAndClosesTheTransportWithTheSameCause() {
		// FR-026's second half: "a write-side failure MUST close the transport with the write's cause" —
		// a claim distinct from B1's read-side one, exercised through a different code path (send()'s own
		// .whenException(this::closeEx), never doRead()). WriteFailingSocket delegates read() to a REAL,
		// still-connected socket so the read loop stays genuinely alive (proven below by the fact nothing
		// about this failure resembles B1's), and only write() is made to fail.
		ExpectedException writeFailure = new ExpectedException("the write side failed");
		RefInt closeCount = new RefInt(0);
		Ref<Exception> closeCause = new Ref<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			server.setListener(listener(document -> {}, e -> {}));

			JsonRpcTcpTransport client =
				JsonRpcTcpTransport.of(reactor(), new WriteFailingSocket(clientSocket, writeFailure));
			client.setListener(listener(document -> {}, e -> {
				closeCount.inc();
				closeCause.set(e);
			}));

			Promise<Void> sent = client.send(REQUEST.getBytes(UTF_8));

			// (a) the send promise fails with the write's cause
			assertTrue("the refusal is immediate", sent.isException());
			assertSame(writeFailure, sent.getException());

			// (b) the transport closed with THAT SAME cause, exactly once — verified separately, because
			// one does not prove the other (a transport could fail the send without closing, or close
			// with a re-wrapped cause)
			assertEquals(1, closeCount.get());
			assertSame(writeFailure, closeCause.get());
			assertTrue(client.isClosed());

			return Promise.complete();
		});
	}

	// -------------------------------------------------------------------------------------------
	// B3 — closeEx called reentrantly from inside onDocument, mid-delivery.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testCloseFromWithinOnDocumentNeverDeliversASecondDocumentAlreadyInTheSameRead() {
		// The onDocument -> closeEx call chain is entirely synchronous, so "closed" is already true by the
		// time doRead()'s "listener.onDocument(document); doRead();" sequence reaches its second statement
		// — the guard at doRead()'s own top (if (closed) return;) stops it before a second get() is ever
		// issued, even though doc2's bytes already sit in the BinaryChannelSupplier's accumulator from the
		// SAME socket read that produced doc1. ByteBufRule is the other half of this assertion and cannot
		// be written by hand: those bytes are recycled by BinaryChannelSupplier.onCleanup() when
		// closeMedium() closes the accumulator, or this class goes red at the end of its last test.
		ExpectedException cause = new ExpectedException("closing mid-delivery");
		List<byte[]> delivered = new ArrayList<>();
		RefInt closeCount = new RefInt(0);
		Ref<Exception> closeCause = new Ref<>();
		byte[] doc1 = REQUEST.getBytes(UTF_8);

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(
				document -> {
					delivered.add(document);
					server.closeEx(cause);
				},
				e -> {
					closeCount.inc();
					closeCause.set(e);
					closed.set(null);
				}));

			// ONE write carrying BOTH documents: doc2 is already past the socket and into the
			// BinaryChannelSupplier's accumulator by the time onDocument(doc1) runs.
			return writeRaw(clientSocket, REQUEST + "\n" + RESPONSE + "\n").then(() -> closed);
		});

		assertEquals("only doc1 was ever delivered; doc2 must never reach onDocument", 1, delivered.size());
		assertArrayEquals(doc1, delivered.get(0));
		assertEquals("onClosed fires exactly once despite being triggered from inside onDocument",
			1, closeCount.get());
		assertSame(cause, closeCause.get());
	}

	// -------------------------------------------------------------------------------------------
	// B4 — send called reentrantly from inside onClosed, on both a local close and a read failure.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testReentrantSendFromOnClosedFailsWithTheSameCauseOnALocalClose() {
		// closeEx sets closeException/closed BEFORE calling signalClose (see JsonRpcTcpTransport.closeEx):
		// e -> closed=true -> closeException=e -> closeMedium(e) -> signalClose(e) -> onClosed(e). So by the
		// time onClosed runs, send()'s very first check ("if (closed) return
		// Promise.ofException(closedException())") already sees closed==true and closeException==e, and
		// returns e itself — not a re-wrapped copy. No exception escapes this test's onClosed callback,
		// and nothing reaches the socket: the guard fires before socket.write is ever called.
		ExpectedException cause = new ExpectedException("closing locally");
		Ref<Promise<Void>> reentrantSend = new Ref<>();
		RefInt closeCount = new RefInt(0);

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.setListener(listener(document -> {}, e -> {
				closeCount.inc();
				reentrantSend.set(client.send(REQUEST.getBytes(UTF_8)));
			}));

			client.closeEx(cause);

			assertEquals(1, closeCount.get());
			Promise<Void> sent = reentrantSend.get();
			assertNotNull("onClosed ran and captured the reentrant send's promise", sent);
			assertTrue("the reentrant send fails immediately", sent.isException());
			assertSame("the SAME cause onClosed received, not a copy", cause, sent.getException());

			return Promise.complete();
		});
	}

	@Test
	public void testReentrantSendFromOnClosedFailsWithTheSameCauseOnAReadFailure() {
		// The read-failure variant of the same reentrancy, over ClosedTcpSocket (B1's fixture): onClosed
		// still sees closed==true and closeException already armed by the time it runs, whatever kind of
		// failure produced the close.
		Ref<Exception> firstCause = new Ref<>();
		Ref<Promise<Void>> reentrantSend = new Ref<>();
		RefInt closeCount = new RefInt(0);

		JsonRpcTcpTransport transport = JsonRpcTcpTransport.of(reactor(), new ClosedTcpSocket());
		transport.setListener(listener(document -> {}, e -> {
			closeCount.inc();
			firstCause.set(e);
			reentrantSend.set(transport.send(REQUEST.getBytes(UTF_8)));
		}));

		assertEquals(1, closeCount.get());
		Promise<Void> sent = reentrantSend.get();
		assertNotNull(sent);
		assertTrue("the reentrant send fails immediately", sent.isException());
		assertSame("the SAME cause onClosed received", firstCause.get(), sent.getException());
	}

	// -------------------------------------------------------------------------------------------
	// B5 — send immediately after a peer's clean close: the one cause-less AsyncCloseException path.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testSendImmediatelyAfterAPeerCleanCloseFailsWithACauselessAsyncCloseException() {
		// closeCleanly() (JsonRpcTcpTransport, the peer's end-of-output on a message boundary) sets
		// closeException = null before signalling — unlike closeEx, which always arms an explicit cause.
		// send()'s closedException() falls back to a FRESH "the transport is closed" AsyncCloseException
		// only when closeException is null, so this is the one send-refusal path this module produces that
		// does not echo back a cause the caller (or the peer) supplied. Asserted by the exact fallback
		// message rather than by type alone, since closeEx's own AsyncCloseException fallback (an
		// unspecified local close(), covered by testOnClosedFiresExactlyOnceOnALocalClose in
		// JsonRpcTcpTransportTest) shares the same class.
		Ref<Promise<Void>> reentrantSend = new Ref<>();
		RefInt closeCount = new RefInt(0);
		Ref<Exception> peerCloseCause = new Ref<>(new ExpectedException("must be overwritten with null"));

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			SettablePromise<Void> closed = new SettablePromise<>();
			client.setListener(listener(document -> {}, e -> {
				closeCount.inc();
				peerCloseCause.set(e);
				reentrantSend.set(client.send(REQUEST.getBytes(UTF_8)));
				closed.set(null);
			}));

			return writeRaw(serverSocket, RESPONSE + "\n")
				.then(() -> serverSocket.write(null))          // clean EOS, on a message boundary
				.then(() -> closed);
		});

		assertEquals(1, closeCount.get());
		assertNull("a clean peer close carries no cause", peerCloseCause.get());
		Promise<Void> sent = reentrantSend.get();
		assertNotNull(sent);
		assertTrue("the send fails immediately", sent.isException());
		assertThat(sent.getException(), instanceOf(AsyncCloseException.class));
		assertEquals("the generic no-cause fallback, not a captured cause",
			"the transport is closed", sent.getException().getMessage());
	}

	// -------------------------------------------------------------------------------------------
	// B6 — connect(...)'s timeout, wired end to end against an address that never answers.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testConnectToAnUnroutableAddressFailsInBoundedTimeRatherThanHanging() {
		// FR-061 names "timed out" as one of the three ways a connect can fail, alongside "unreachable"
		// and "refused" — both already covered by testConnectFailureFailsThePromiseAndRegistersNothing in
		// JsonRpcTcpTransportTest, neither of which can provoke a timeout (a closed local port refuses
		// immediately). 192.0.2.1 (RFC 5737 TEST-NET-1) is reserved for documentation and never routes, so
		// nothing but the timeout parameter can end this attempt. No absolute-time assertion (WI-17): a
		// bounded await either returns with a failure or the test simply never terminates, which is itself
		// the failing signal a hang would produce.
		NioReactor reactor = reactor();
		InetSocketAddress unroutable = new InetSocketAddress("192.0.2.1", 1);

		Exception e = awaitException(
			JsonRpcTcpTransport.connect(reactor, unroutable, Duration.ofMillis(50), null));

		// Eventloop.connect's own delay(timeout, ...) firing is the expected case; a sandbox that answers
		// "network unreachable" synchronously for this subnet is the documented alternative (research risk
		// named directly in the adversarial plan) and must not fail the test either.
		assertTrue(
			"expected the connect timeout (AsyncTimeoutException) or an immediate network-level IOException, got: " +
			e.getClass().getName() + ": " + e.getMessage(),
			e instanceof AsyncTimeoutException || e instanceof IOException);
	}

	// -------------------------------------------------------------------------------------------
	// Fixtures.
	// -------------------------------------------------------------------------------------------

	/**
	 * A real, still-connected socket for {@code read()} and {@code isReadAvailable()}/{@code isClosed()},
	 * but a write that always fails immediately with a fixed cause — B2's device: proving a write-side
	 * failure is a different code path from a read-side one requires the read side to still be genuinely
	 * alive, not merely unexercised. Every {@code write} recycles the buffer it is given, because a socket
	 * that refuses a write still owns it (DI-1) — the same discipline {@link ClosedTcpSocket} follows.
	 */
	private static final class WriteFailingSocket implements ITcpSocket {
		private final ITcpSocket delegate;
		private final Exception writeFailure;

		WriteFailingSocket(ITcpSocket delegate, Exception writeFailure) {
			this.delegate = delegate;
			this.writeFailure = writeFailure;
		}

		@Override
		public Promise<ByteBuf> read() {
			return delegate.read();
		}

		@Override
		public Promise<Void> write(@Nullable ByteBuf buf) {
			if (buf != null) buf.recycle();
			return Promise.ofException(writeFailure);
		}

		@Override
		public boolean isReadAvailable() {
			return delegate.isReadAvailable();
		}

		@Override
		public boolean isClosed() {
			return delegate.isClosed();
		}

		@Override
		public void closeEx(Exception e) {
			delegate.closeEx(e);
		}
	}

	/**
	 * Establishes one real TCP connection — an {@code acceptOnce} server on port {@code 0} plus a
	 * connected client — hands both raw sockets to {@code body}, and closes everything when the promise it
	 * returns completes. The same shape {@link JsonRpcTcpTransportTest} uses.
	 */
	private static void withSockets(BiFunction<ITcpSocket, ITcpSocket, Promise<Void>> body) {
		NioReactor reactor = reactor();
		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer server = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		listen(server);

		await(TcpSocket.connect(reactor, boundAddress(server))
			.then(clientSocket -> accepted
				.then(serverSocket -> body.apply(clientSocket, serverSocket)
					.whenComplete(($, e) -> {
						clientSocket.close();
						serverSocket.close();
						server.close();
					}))));
	}

	private static Promise<Void> writeRaw(ITcpSocket socket, String text) {
		return socket.write(ByteBuf.wrapForReading(text.getBytes(UTF_8)));
	}

	private static JsonRpcTransport.Listener listener(
		Consumer<byte[]> onDocument, Consumer<@Nullable Exception> onClosed
	) {
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
