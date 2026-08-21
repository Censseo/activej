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

import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefInt;
import io.activej.jsonrpc.JsonRpcDecoder;
import io.activej.jsonrpc.JsonRpcInput;
import io.activej.jsonrpc.JsonRpcRequest;
import io.activej.jsonrpc.transport.JsonRpcTransport;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The transport's behavioural contract (T005), pinned over <b>real socket pairs</b> — an
 * {@code acceptOnce} {@link SimpleServer} bound to port {@code 0} and asked where it landed
 * (ADR-028), plus a real {@link TcpSocket} client. Nothing here is an in-memory double: the
 * framing, the end-of-stream taxonomy and the write coalescing this transport composes are all
 * properties of the socket layer, and a double would assert them of itself.
 * <p>
 * The matrix, in the order the tasks list it: one-document-per-line round trip (FR-010/FR-011/
 * FR-013); the empty-line framing violation (FR-017); {@code send(new byte[0])} (FR-018); the
 * once-only listener and the send-before-listener refusal (FR-025); send-after-close carrying the
 * close cause; exactly-once {@code onClosed} including the close-before-{@code setListener} case
 * (FR-025); end-of-stream between messages and mid-message (FR-019/FR-096); CRLF acceptance
 * (FR-014); and concurrent sends completing in per-direction order with no queue (FR-023).
 * <p>
 * <b>Quiescence.</b> {@code TestUtils.await} runs the loop until nothing is left to do and
 * {@code Eventloop.isAlive()} counts selector keys, so every test closes both ends inside the
 * awaited chain and every server is {@code withAcceptOnce()} — the persistent-transport shape
 * feature 015 established (research R8). A test that leaves a socket open hangs the suite rather
 * than failing it.
 */
public final class JsonRpcTcpTransportTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static final String REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.add\",\"params\":[2,3]}";
	private static final String RESPONSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sum\":5}}";

	// -------------------------------------------------------------------------------------------
	// Framing: one document per LF-terminated line, both directions.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testOneDocumentPerLineRoundTrip() {
		// FR-010/FR-011/FR-013: one send is one LF-terminated line, and one line is delivered as one
		// contiguous document. Proven in both directions on one connection, which is what makes this a
		// duplex transport rather than a request/response one.
		List<byte[]> serverReceived = new ArrayList<>();
		List<byte[]> clientReceived = new ArrayList<>();
		byte[] request = REQUEST.getBytes(UTF_8);
		byte[] response = RESPONSE.getBytes(UTF_8);

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> answered = new SettablePromise<>();

			server.setListener(listener(
				document -> {
					serverReceived.add(document);
					server.send(response);
				},
				e -> {}));
			client.setListener(listener(
				document -> {
					clientReceived.add(document);
					answered.set(null);
				},
				e -> {}));

			return client.send(request).then(() -> answered);
		});

		assertEquals(1, serverReceived.size());
		assertArrayEquals(request, serverReceived.get(0));
		assertEquals(1, clientReceived.size());
		assertArrayEquals(response, clientReceived.get(0));
	}

	@Test
	public void testCrlfTerminatedDocumentIsAcceptedAndDecodesIdentically() {
		// FR-014 / research D10: the transport trims nothing. A CRLF-terminated line yields a document
		// whose last byte is the carriage return, and the envelope decoder treats it as insignificant
		// trailing whitespace — so a peer on a platform where a newline is CRLF interoperates with zero
		// transport code. Pinned here so a decoder regression fails loudly rather than silently changing
		// the wire contract.
		Ref<byte[]> delivered = new Ref<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> received = new SettablePromise<>();
			server.setListener(listener(document -> {
				delivered.set(document);
				received.set(null);
			}, e -> {}));

			return writeRaw(clientSocket, REQUEST + "\r\n").then(() -> received);
		});

		byte[] document = delivered.get();
		assertNotNull(document);
		assertEquals("the carriage return is left in the document", '\r', document[document.length - 1]);

		JsonRpcInput withCr = JsonRpcDecoder.decode(document);
		JsonRpcInput withoutCr = JsonRpcDecoder.decode(REQUEST.getBytes(UTF_8));
		assertThat(withCr, instanceOf(JsonRpcRequest.class));
		assertThat(withoutCr, instanceOf(JsonRpcRequest.class));
		assertEquals(((JsonRpcRequest) withoutCr).method(), ((JsonRpcRequest) withCr).method());
		assertEquals(((JsonRpcRequest) withoutCr).id(), ((JsonRpcRequest) withCr).id());
	}

	@Test
	public void testEmptyLineIsAFramingViolationThatClosesTheConnection() {
		// FR-017: a bare LF decodes to a zero-length document, which SPI obligation 3 forbids delivering.
		// There is no honest resynchronisation point for a framing violation, so the connection closes —
		// with a fixed-string cause naming the violation and carrying no peer content (FR-097).
		List<byte[]> delivered = new ArrayList<>();
		Ref<Exception> closeCause = new Ref<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(delivered::add, e -> {
				closeCause.set(e);
				closed.set(null);
			}));

			return writeRaw(clientSocket, "\n").then(() -> closed);
		});

		assertTrue("a zero-length document must never reach the listener", delivered.isEmpty());
		assertThat(closeCause.get(), instanceOf(MalformedDataException.class));
		assertTrue("the close cause names the framing violation: " + closeCause.get().getMessage(),
			closeCause.get().getMessage().contains("empty"));
	}

	// -------------------------------------------------------------------------------------------
	// The SPI's refusals.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testZeroLengthSendIsRefusedImmediately() {
		// FR-018: "no response" is the absence of a call, not an empty one — a zero-length array must
		// never become a bare LF on the wire. The promise fails immediately, synchronously, so the
		// refusal is observable without running the loop.
		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.setListener(listener(document -> {}, e -> {}));

			Promise<Void> sent = client.send(new byte[0]);

			assertTrue("the refusal is immediate", sent.isException());
			assertThat(sent.getException(), instanceOf(IllegalArgumentException.class));
			return Promise.complete();
		});
	}

	@Test
	public void testSecondSetListenerIsRefused() {
		// FR-025: the listener slot is filled once. A second listener would mean two owners for one
		// correlation state — a programmer error, refused with IllegalStateException.
		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.setListener(listener(document -> {}, e -> {}));

			try {
				client.setListener(listener(document -> {}, e -> {}));
				fail("a second setListener must be refused (FR-025)");
			} catch (IllegalStateException expected) {
				// expected
			}
			return Promise.complete();
		});
	}

	@Test
	public void testSendBeforeSetListenerIsRefused() {
		// FR-025: setListener comes first — the SPI says so, and a send whose answer has nowhere to be
		// delivered is a bug in the caller, not a case to tolerate.
		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);

			try {
				client.send(REQUEST.getBytes(UTF_8));
				fail("a send before setListener must be refused (FR-025)");
			} catch (IllegalStateException expected) {
				// expected
			}
			return Promise.complete();
		});
	}

	@Test
	public void testSendAfterCloseFailsWithTheCloseCause() {
		// FR-025: after close no document is delivered and a send fails immediately — with the cause the
		// close carried, so a caller learns why rather than merely that.
		ExpectedException expected = new ExpectedException("local close");

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.setListener(listener(document -> {}, e -> {}));
			client.closeEx(expected);

			Promise<Void> sent = client.send(REQUEST.getBytes(UTF_8));

			assertTrue("the refusal is immediate", sent.isException());
			assertSame(expected, sent.getException());
			return Promise.complete();
		});
	}

	@Test
	public void testNonPositiveMaxMessageSizeIsRefused() {
		// A zero transport tier makes no message framable at all: a programmer error, refused under the
		// CHECKS regime (which Surefire runs with -Dchk=on).
		withSockets((clientSocket, serverSocket) -> {
			try {
				JsonRpcTcpTransport.builder(reactor(), clientSocket)
					.withMaxMessageSize(MemSize.ZERO)
					.build();
				fail("a non-positive transport tier must be refused");
			} catch (IllegalArgumentException expected) {
				// expected
			}
			return Promise.complete();
		});
	}

	// -------------------------------------------------------------------------------------------
	// Closing: exactly once, whatever caused it.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testOnClosedFiresExactlyOnceOnALocalClose() {
		// SPI obligation 6: close is idempotent and reported exactly once. Two closeEx calls, one signal.
		RefInt closeCount = new RefInt(0);
		Ref<Exception> cause = new Ref<>();
		ExpectedException first = new ExpectedException("first");

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.setListener(listener(document -> {}, e -> {
				closeCount.inc();
				cause.set(e);
			}));

			client.closeEx(first);
			client.closeEx(new ExpectedException("second"));
			client.close();

			return Promise.complete();
		});

		assertEquals(1, closeCount.get());
		assertSame(first, cause.get());
	}

	@Test
	public void testOnClosedFiresOnceWhenTheCloseHappenedBeforeSetListener() {
		// FR-025: the latch arms at delivery, not at close — a close that happened before any listener
		// existed must still reach the listener that shows up later, exactly once (feature 015's
		// signalClose idiom). Without this, a transport closed during a session's construction would
		// leave its owner waiting forever for a signal that was already spent.
		RefInt closeCount = new RefInt(0);
		Ref<Exception> cause = new Ref<>();
		ExpectedException expected = new ExpectedException("closed before any listener");

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			client.closeEx(expected);

			client.setListener(listener(document -> {}, e -> {
				closeCount.inc();
				cause.set(e);
			}));

			assertEquals("the signal is delivered as soon as a listener exists", 1, closeCount.get());
			client.closeEx(new ExpectedException("second"));
			return Promise.complete();
		});

		assertEquals(1, closeCount.get());
		assertSame(expected, cause.get());
	}

	@Test
	public void testEndOfStreamBetweenMessagesIsACleanClose() {
		// FR-019/FR-096: the peer's end-of-output on a message boundary is its clean close — onClosed(null),
		// exactly once, and the connection closes fully. Read end-of-stream IS the close of the medium:
		// this transport answers a half-close with a full close (contract §4).
		RefInt closeCount = new RefInt(0);
		Ref<Exception> cause = new Ref<>(new ExpectedException("not overwritten"));
		List<byte[]> delivered = new ArrayList<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(delivered::add, e -> {
				closeCount.inc();
				cause.set(e);
				closed.set(null);
			}));

			return writeRaw(clientSocket, REQUEST + "\n")
				.then(() -> clientSocket.write(null))            // end of output, on a message boundary
				.then(() -> closed);
		});

		assertEquals("the document before the close was delivered", 1, delivered.size());
		assertEquals(1, closeCount.get());
		assertNull("a clean peer close carries no cause", cause.get());
	}

	@Test
	public void testEndOfStreamMidMessageIsTruncation() {
		// FR-019/FR-096: end-of-stream with a partial message accumulated is TruncatedDataException — there
		// is no resynchronisation, the connection closes with that cause, and the partial accumulation is
		// recycled. ByteBufRule is the assertion for the recycling half; it cannot be written by hand.
		RefInt closeCount = new RefInt(0);
		Ref<Exception> cause = new Ref<>();
		List<byte[]> delivered = new ArrayList<>();

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> closed = new SettablePromise<>();
			server.setListener(listener(delivered::add, e -> {
				closeCount.inc();
				cause.set(e);
				closed.set(null);
			}));

			return writeRaw(clientSocket, "{\"jsonrpc\":\"2.0\",\"id\":1,\"me")   // no terminator
				.then(() -> clientSocket.write(null))
				.then(() -> closed);
		});

		assertTrue("a partial message is never delivered", delivered.isEmpty());
		assertEquals(1, closeCount.get());
		assertThat(cause.get(), instanceOf(TruncatedDataException.class));
	}

	// -------------------------------------------------------------------------------------------
	// The write path: no queue.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testConcurrentSendsCompleteInPerDirectionOrderWithNoQueue() {
		// FR-023 / research D4: TcpSocket.write coalesces concurrent writes into its own buffer and
		// completes each caller when the batch carrying its bytes has flushed, so per-direction order is
		// append order and this transport adds no writeTail chain of its own. Two sends issued without
		// awaiting the first must arrive in order, and both promises must complete.
		List<String> received = new ArrayList<>();
		List<String> completed = new ArrayList<>();
		byte[] first = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test.note\",\"params\":[\"one\"]}".getBytes(UTF_8);
		byte[] second = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"test.note\",\"params\":[\"two\"]}".getBytes(UTF_8);

		withSockets((clientSocket, serverSocket) -> {
			JsonRpcTcpTransport client = JsonRpcTcpTransport.of(reactor(), clientSocket);
			JsonRpcTcpTransport server = JsonRpcTcpTransport.of(reactor(), serverSocket);
			SettablePromise<Void> bothReceived = new SettablePromise<>();

			server.setListener(listener(document -> {
				received.add(new String(document, UTF_8));
				if (received.size() == 2) bothReceived.set(null);
			}, e -> {}));
			client.setListener(listener(document -> {}, e -> {}));

			// both issued before either resolves: no await between them
			Promise<Void> firstSend = client.send(first).whenResult(() -> completed.add("first"));
			Promise<Void> secondSend = client.send(second).whenResult(() -> completed.add("second"));

			return Promise.complete()
				.then(() -> firstSend)
				.then(() -> secondSend)
				.then(() -> bothReceived);
		});

		assertEquals(List.of(new String(first, UTF_8), new String(second, UTF_8)), received);
		assertEquals(List.of("first", "second"), completed);
	}

	// -------------------------------------------------------------------------------------------
	// The client-side connection path.
	// -------------------------------------------------------------------------------------------

	@Test
	public void testConnectWrapsTheConnectedSocket() {
		// FR-060: the client endpoint is a connect + wrap. The connected transport frames exactly as the
		// accepted one does (FR-062), which is what makes the reverse-direction conformance suite a replay
		// rather than a second implementation.
		List<byte[]> serverReceived = new ArrayList<>();
		byte[] request = REQUEST.getBytes(UTF_8);

		NioReactor reactor = reactor();
		SettablePromise<ITcpSocket> accepted = new SettablePromise<>();
		SimpleServer server = SimpleServer.builder(reactor, accepted::set)
			.withListenPort(0)
			.withAcceptOnce()
			.build();
		listen(server);

		await(JsonRpcTcpTransport.connect(reactor, boundAddress(server))
			.then(client -> accepted.then(serverSocket -> {
				JsonRpcTcpTransport serverTransport = JsonRpcTcpTransport.of(reactor, serverSocket);
				SettablePromise<Void> received = new SettablePromise<>();
				serverTransport.setListener(listener(document -> {
					serverReceived.add(document);
					received.set(null);
				}, e -> {}));
				client.setListener(listener(document -> {}, e -> {}));
				return client.send(request)
					.then(() -> received)
					.whenComplete(($, e) -> {
						client.close();
						serverTransport.close();
					});
			})));

		assertEquals(1, serverReceived.size());
		assertArrayEquals(request, serverReceived.get(0));
	}

	@Test
	public void testConnectFailureFailsThePromiseAndRegistersNothing() {
		// FR-061: a failed connect (unreachable, refused, timed out) fails the connect promise with
		// the underlying cause and nothing is created or registered anywhere. Every other test in this
		// class connects to a live server; this pins the failure path instead of leaving it to
		// "obviously true by composition" (connect().map(...) never runs its lambda on a failed promise).
		NioReactor reactor = reactor();

		// ADR-028: bind :0 to get a genuinely free port, then close before connecting — never guess a
		// free port, and this guarantees nothing is listening on the one we just had.
		SimpleServer server = SimpleServer.builder(reactor, socket -> {}).withListenPort(0).build();
		listen(server);
		InetSocketAddress deadAddress = boundAddress(server);
		await(server.close());

		Exception e = awaitException(JsonRpcTcpTransport.connect(reactor, deadAddress));
		assertThat(e, instanceOf(IOException.class));
	}

	// -------------------------------------------------------------------------------------------
	// Fixture.
	// -------------------------------------------------------------------------------------------

	/**
	 * Establishes one real TCP connection — an {@code acceptOnce} server on port {@code 0} plus a
	 * connected client — hands both raw sockets to {@code body}, and closes everything when the promise
	 * it returns completes. The sockets are handed over raw rather than pre-wrapped so a test can put a
	 * {@link JsonRpcTcpTransport} on either end, both ends, or neither (the raw-bytes framing tests).
	 * Closing a socket a transport already owns is idempotent.
	 * <p>
	 * ⚠ <b>Measured cost, worth knowing before adding tests here.</b> A test that leaves one side as a
	 * raw socket with <i>no read ever issued on it</i> pays a full {@code idleInterval} (1 s) selector
	 * wait before the loop quiesces — 1.003 s per test, reproducibly, and installing a listener on that
	 * side drops it to 0.003 s. It is an eventloop-liveness artefact of the fixture, not a transport
	 * behaviour: production never has an idle socket, because {@code JsonRpcClient.build()} installs the
	 * listener and the read loop keeps a read pending from that moment. Prefer a listener on both ends
	 * when the test does not specifically need raw bytes.
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
