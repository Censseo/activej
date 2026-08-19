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
import io.activej.common.ref.RefBoolean;
import io.activej.common.ref.RefInt;
import io.activej.http.HttpException;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.IWebSocket;
import io.activej.http.IWebSocketClient;
import io.activej.http.WebSocketException;
import io.activej.http.WebSocketServlet;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.CountingWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.StubWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
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

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.assertCompleteFn;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The transport's behavioural contract (T004): one document per TEXT message in both directions
 * (FR-010/FR-012/FR-013), the zero-length refusal (FR-020), the once-only listener (FR-019), the
 * close-cause surfacing, exactly-once {@code onClosed} with the {@code null}/{@code WebSocketException}
 * split of D4, the handshake-failure promise (FR-061) and the never-closed injected client (FR-065).
 * <p>
 * Pure-programmer-error tests (zero-length send, second {@code setListener}, send-after-close) run on
 * the {@link StubWebSocket} — a real connection would force every awaited chain to reach quiescence,
 * which an immediately-failing {@code send} cannot do while the accept socket is open. Wire-behaviour
 * tests run on the real {@link WsPair} and close everything inside the awaited chain (R3).
 */
public final class JsonRpcWsTransportTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testOneDocumentPerTextMessageOutboundAndInbound() {
		// FR-010/FR-012/FR-013: one complete JSON-RPC document per TEXT message, both directions.
		// A single send produces a single unfragmented TEXT frame, and a single inbound TEXT message
		// delivers a single contiguous document.
		List<byte[]> serverReceived = new ArrayList<>();
		List<byte[]> clientReceived = new ArrayList<>();
		RefBoolean closeScheduled = new RefBoolean(false);
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		byte[] doc1 = "{\"jsonrpc\":\"2.0\",\"method\":\"server.to.client\"}".getBytes(UTF_8);
		byte[] doc2 = "{\"jsonrpc\":\"2.0\",\"method\":\"client.to.server\"}".getBytes(UTF_8);

		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			serverTransport.set(transport);
			transport.setListener(listener(doc -> {
				serverReceived.add(doc);
				maybeClose(closeScheduled, 1, 1, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
		});

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			clientTransport.get().setListener(listener(doc -> {
				clientReceived.add(doc);
				maybeClose(closeScheduled, 1, 1, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
			return serverTransport.get().send(doc1)
				.then($ -> clientTransport.get().send(doc2));
		}));

		assertEquals(1, clientReceived.size());               // server's send arrived as exactly one document
		assertArrayEquals(doc1, clientReceived.get(0));
		assertEquals(1, serverReceived.size());               // client's send arrived as exactly one document
		assertArrayEquals(doc2, serverReceived.get(0));
		pair.closeAll();
	}

	@Test
	public void testZeroLengthSendRefusedImmediately() {
		// FR-020: a zero-length array is never a document — refused immediately, before the listener
		// gate, so even a transport that has no listener yet refuses rather than deferring.
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), new StubWebSocket());

		Exception e = awaitException(transport.send(new byte[0]));

		assertThat(e, instanceOf(IllegalArgumentException.class));
	}

	@Test
	public void testSecondSetListenerRefused() {
		// FR-019: the listener is registered once; a second call is a programmer error.
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), new StubWebSocket());
		transport.setListener(listener($ -> {}, e -> {}));

		try {
			transport.setListener(listener($ -> {}, e -> {}));
			fail("second setListener must be refused");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void testCloseBeforeSetListenerIsStillReportedExactlyOnce() {
		// obligation 6 + the setListener javadoc: a close that happens before any listener exists
		// must NOT be swallowed by the latch — the listener registered afterwards is told about it
		// immediately, exactly once, with the close cause (B3, adversarial plan). The latch is armed
		// only at delivery, never in advance.
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		AsyncCloseException cause = new AsyncCloseException("closed before listener");

		transport.closeEx(cause);                             // no listener yet

		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener($ -> {}, e -> {
			closed.set(e);
			onClosedCount.inc();
		}));

		assertEquals(1, onClosedCount.get());                 // delivered exactly once
		assertSame(cause, closed.get());
		transport.closeEx(new AsyncCloseException("second close"));  // idempotent — no re-signal
		assertEquals(1, onClosedCount.get());
	}

	@Test
	public void testSendAfterCloseFailsWithCloseCause() {
		// a send issued after close fails immediately with the close cause, and onClosed fires
		// exactly once with that same cause
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener($ -> {}, e -> {
			closed.set(e);
			onClosedCount.inc();
		}));

		AsyncCloseException cause = new AsyncCloseException("test cause");
		transport.closeEx(cause);

		Exception e = awaitException(transport.send(new byte[]{1}));
		assertSame(cause, e);
		assertSame(cause, closed.get());
		assertEquals(1, onClosedCount.get());               // exactly once (obligation 6)
	}

	@Test
	public void testCloseWhileWriteInFlightFailsThatSendAndEverySendAfterIt() {
		// obligation 6/7 + FR-019: closeEx interrupts a send whose writeMessage is already in flight
		// (B1, adversarial plan). The in-flight send fails with the close cause, onClosed fires exactly
		// once with it, and a send issued after the close fails immediately with that same cause — the
		// write path is never re-entered, so no partial frame is ever produced for it.
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener($ -> {}, e -> {
			closed.set(e);
			onClosedCount.inc();
		}));
		byte[] doc1 = "{\"jsonrpc\":\"2.0\",\"method\":\"in.flight\"}".getBytes(UTF_8);
		byte[] doc2 = "{\"jsonrpc\":\"2.0\",\"method\":\"after.close\"}".getBytes(UTF_8);

		// the write is handed to the socket and left hanging — completeWrite() is deliberately not called
		Promise<Void> inFlight = transport.send(doc1);
		assertFalse(inFlight.isComplete());

		AsyncCloseException cause = new AsyncCloseException("closed while a write was in flight");
		transport.closeEx(cause);                             // the close wins the race

		assertSame(cause, awaitException(inFlight));          // (a) the in-flight send carries the cause
		assertSame(cause, closed.get());                      // (b) onClosed, exactly once, same cause
		assertEquals(1, onClosedCount.get());
		assertTrue(webSocket.isClosed());

		// a send issued after the close never reaches writeMessage: it fails with the same cause and
		// the close is not signalled a second time
		assertSame(cause, awaitException(transport.send(doc2)));
		assertEquals(1, onClosedCount.get());
	}

	@Test
	public void testCleanPeerCloseFiresOnClosedExactlyOnce() {
		// D4: a peer close frame of code 1000 resolves the read loop with null ⇒ onClosed(null),
		// exactly once — a second closeEx must not re-signal.
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		SettablePromise<Void> closedPromise = new SettablePromise<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			serverTransport.set(transport);
			transport.setListener(listener($ -> {}, e -> {
				closed.set(e);
				onClosedCount.inc();
				closedPromise.set(null);
			}));
		});

		await(Promises.all(
			pair.connect().then(ws -> ws.writeMessage(null)),          // clean close, code 1000
			closedPromise.whenComplete(assertCompleteFn())));          // tracked by ActivePromisesRule

		assertNull(closed.get());                                      // clean close carries no cause
		assertEquals(1, onClosedCount.get());
		serverTransport.get().closeEx(new AsyncCloseException());      // idempotent — no second signal
		assertEquals(1, onClosedCount.get());
		pair.closeAll();
	}

	@Test
	public void testNon1000CloseCarriesCodeAndReason() {
		// D4: a non-1000 close frame surfaces as the WebSocketException with the peer's code and
		// reason verbatim.
		Ref<Exception> closed = new Ref<>();
		RefInt onClosedCount = new RefInt(0);
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener($ -> {}, e -> {
				closed.set(e);
				onClosedCount.inc();
			}));
		});

		await(pair.connect().then(ws -> {
			ws.closeEx(new WebSocketException(4321, "Some reason"));
			return Promise.complete();
		}));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(4321), ((WebSocketException) closed.get()).getCode());
		assertEquals("Some reason", ((WebSocketException) closed.get()).getReason());
		assertEquals(1, onClosedCount.get());
		pair.closeAll();
	}

	@Test
	public void testHandshakeRefusalFailsWithHandshakeFailed() {
		// FR-061: the admission gate (onRequest returning a non-101 answer) refuses the upgrade
		// before a session exists; connect's promise fails with HANDSHAKE_FAILED and nothing is
		// registered anywhere.
		WsPair pair = WsPair.serverUpgrade(reactor(), new WebSocketServlet(reactor()) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				return HttpResponse.ofCode(403).toPromise();
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
			}
		});

		HttpRequest request = HttpRequest.get("ws://127.0.0.1:" + pair.port()).build();
		Exception e = awaitException(JsonRpcWsTransport.connect(reactor(), pair.client(), request));

		// HANDSHAKE_FAILED (core-http's package-private singleton) is the observable failure: the
		// refusal surfaces as that HttpException with its handshake message (FR-061)
		assertThat(e, instanceOf(HttpException.class));
		assertEquals("Failed to perform a proper opening handshake", e.getMessage());
		pair.closeAll();
	}

	@Test
	public void testInjectedClientIsNeverClosed() {
		// FR-065: the transport owns only the websocket it wraps. After its connect() succeeded and
		// the transport was closed, the same injected IWebSocketClient must still establish a new
		// connection — proof that the transport never closed it.
		WsPair pair1 = WsPair.serverUpgrade(reactor(), $ -> {});
		IWebSocketClient client = pair1.client();

		JsonRpcWsTransport transport = await(
			JsonRpcWsTransport.connect(reactor(), client,
					HttpRequest.get("ws://127.0.0.1:" + pair1.port()).build())
				.then(t -> {
					t.closeEx(new AsyncCloseException());   // the transport closes its websocket only
					return Promise.of(t);
				}));

		// the same client still works: a second connection through it to a fresh server
		WsPair pair2 = WsPair.serverUpgrade(reactor(), client, $ -> {});
		Ref<IWebSocket> secondSocket = new Ref<>();
		await(pair2.connect().then(ws -> {
			secondSocket.set(ws);
			return ws.writeMessage(null);
		}));
		assertNotNull(secondSocket.get());                     // connect succeeded through the reused client
		pair1.closeAll();
		pair2.closeAll();
	}

	@Test
	public void testPipelinedBurstOfFiftyDocumentsDeliveredInOrder() {
		// A5, adversarial plan: 50 documents fired back-to-back on one transport, none of them awaited
		// before the next is issued — only the first reaches writeMessage, the other 49 queue on the
		// internal writeTail chain (FR-017). They must arrive as 50 separate documents, in emission
		// order, byte-for-byte: message-by-message decoding neither drops nor merges anything (FR-013).
		int count = 50;
		List<byte[]> serverReceived = new ArrayList<>();
		List<byte[]> clientReceived = new ArrayList<>();
		RefBoolean closeScheduled = new RefBoolean(false);
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();

		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			serverTransport.set(transport);
			transport.setListener(listener(doc -> {
				serverReceived.add(doc);
				maybeClose(closeScheduled, count, 0, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
		});

		await(pair.connect().then(ws -> {
			clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
			clientTransport.get().setListener(listener(clientReceived::add, e -> {}));
			List<Promise<Void>> sends = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				// the burst: every send is issued without awaiting the previous one
				sends.add(clientTransport.get().send(document(i)));
			}
			return Promises.all(sends);
		}));

		assertEquals(count, serverReceived.size());           // nothing lost, nothing coalesced
		for (int i = 0; i < count; i++) {
			assertArrayEquals(document(i), serverReceived.get(i));   // and in the exact order sent
		}
		pair.closeAll();
	}

	@Test
	public void testWriteFailureFailsTheInFlightSendAndEverySendQueuedBehindIt() {
		// B2, adversarial plan: writeMessage fails (dead socket) while further sends sit in the
		// writeTail chain. The in-flight send fails with the cause, every queued send fails with that
		// same cause, onClosed fires exactly once, and the chain does not break — a send issued
		// afterwards fails fast with the same cause rather than hanging or reaching the socket
		// (obligation 6/7).
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener($ -> {}, e -> {
			closed.set(e);
			onClosedCount.inc();
		}));

		Promise<Void> send1 = transport.send(document(1));    // handed to writeMessage, left pending
		Promise<Void> send2 = transport.send(document(2));    // queued: writeMessage not attempted yet
		Promise<Void> send3 = transport.send(document(3));
		assertFalse(send1.isComplete());
		assertFalse(send2.isComplete());
		assertFalse(send3.isComplete());

		// the medium dies mid-write — not a closeEx, so what is observed is the transport's own
		// reaction to a failed writeMessage
		ExpectedException cause = new ExpectedException("the socket died mid-write");
		webSocket.failWrite(cause);

		assertSame(cause, awaitException(send1));             // (a) the in-flight send carries the cause
		assertSame(cause, awaitException(send2));             // (b) both queued sends fail with it too
		assertSame(cause, awaitException(send3));
		assertSame(cause, closed.get());                      // (c) onClosed, exactly once, same cause
		assertEquals(1, onClosedCount.get());
		assertTrue(webSocket.isClosed());                     // a failed write closes the medium

		// (d) the chain is neither stuck nor doubly invoked: a further send fails fast with the cause
		assertSame(cause, awaitException(transport.send(document(4))));
		assertEquals(1, onClosedCount.get());
	}

	@Test
	public void testConnectToUnreachablePortFailsWithTheNetworkCause() {
		// B5, adversarial plan: a network-level failure — nothing is listening on the port at all —
		// as opposed to testHandshakeRefusalFailsWithHandshakeFailed's 403 admission-gate refusal.
		// core-http surfaces the refused TCP connect as an HttpException *wrapping* the
		// java.net.ConnectException, which is how it is told apart from the gate's cause-less
		// HANDSHAKE_FAILED singleton. Nothing is registered: the very same injected client
		// establishes a real connection immediately afterwards (FR-061, FR-065).
		WsPair dead = WsPair.serverUpgrade(reactor(), $ -> {});
		int deadPort = dead.port();
		IWebSocketClient client = dead.client();
		dead.closeAll();                                      // nothing is bound to deadPort any more

		HttpRequest request = HttpRequest.get("ws://127.0.0.1:" + deadPort).build();
		Exception e = awaitException(JsonRpcWsTransport.connect(reactor(), client, request));

		assertThat(e, instanceOf(HttpException.class));
		assertThat(e.getCause(), instanceOf(ConnectException.class));   // the network cause, verbatim
		// NOT the admission gate's HANDSHAKE_FAILED, which carries that message and no cause at all
		assertNotEquals("Failed to perform a proper opening handshake", e.getMessage());

		// nothing stray was left registered: a fresh, unrelated connection through the same client works
		WsPair alive = WsPair.serverUpgrade(reactor(), client, $ -> {});
		Ref<IWebSocket> secondSocket = new Ref<>();
		await(alive.connect().then(ws -> {
			secondSocket.set(ws);
			return ws.writeMessage(null);
		}));
		assertNotNull(secondSocket.get());
		alive.closeAll();
	}

	@Test
	public void testSecondCloseExWithADifferentCauseIsDiscardedEntirely() {
		// B6, adversarial plan: two closeEx calls carrying two DISTINCT exception instances. The first
		// one wins outright — onClosed fires exactly once with e1, the in-flight send fails with e1, and
		// a send issued afterwards fails with e1 too. e2 surfaces nowhere at all: it is neither
		// remembered as the close cause nor handed to any promise (WI-9, obligation 6).
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		RefInt onClosedCount = new RefInt(0);
		Ref<Exception> closed = new Ref<>();
		transport.setListener(listener($ -> {}, e -> {
			closed.set(e);
			onClosedCount.inc();
		}));

		Promise<Void> inFlight = transport.send(document(1));   // handed to writeMessage, left pending
		assertFalse(inFlight.isComplete());

		ExpectedException first = new ExpectedException("the first close, which wins");
		ExpectedException second = new ExpectedException("the second close, which is discarded");
		assertNotSame(first, second);
		transport.closeEx(first);
		transport.closeEx(second);                              // idempotent: never re-latches the cause

		assertSame(first, closed.get());                        // (a) onClosed carries e1, literally
		assertEquals(1, onClosedCount.get());                   // (b) exactly once, not once per closeEx
		assertSame(first, awaitException(inFlight));            // (c) the in-flight send carries e1
		assertSame(first, awaitException(transport.send(document(2))));  // (d) and so does a later send
		assertEquals(1, onClosedCount.get());
		assertTrue(webSocket.isClosed());
	}

	@Test
	public void testSendAfterPeerCleanCloseFailsWithACauselessAsyncCloseException() {
		// B7, adversarial plan: the PEER closes cleanly (writeMessage(null), code 1000) and the local
		// side writes afterwards. Unlike testSendAfterCloseFailsWithCloseCause — a LOCAL close with an
		// explicit cause — a clean close carries none (closeCleanly sets closeException = null), so the
		// late send fails with a bare AsyncCloseException and nothing ever reaches the wire for it.
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<CountingWebSocket> serverSocket = new Ref<>();
		Ref<Exception> closed = new Ref<>();
		RefInt onClosedCount = new RefInt(0);
		SettablePromise<Void> closedPromise = new SettablePromise<>();

		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			CountingWebSocket counted = new CountingWebSocket(ws);   // counts every writeMessage attempt
			serverSocket.set(counted);
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), counted);
			serverTransport.set(transport);
			transport.setListener(listener($ -> {}, e -> {
				closed.set(e);
				onClosedCount.inc();
				closedPromise.set(null);
			}));
		});

		await(Promises.all(
			pair.connect().then(ws -> ws.writeMessage(null)),          // the PEER closes cleanly, code 1000
			closedPromise.whenComplete(assertCompleteFn())));          // tracked by ActivePromisesRule

		assertNull(closed.get());                                      // D4: a clean close carries no cause
		assertEquals(1, onClosedCount.get());

		// the local send issued after the peer's clean close
		Exception e = awaitException(serverTransport.get().send(document(1)));
		assertThat(e, instanceOf(AsyncCloseException.class));
		assertNull(e.getCause());                                      // no cause — there was none to carry
		assertEquals(0, serverSocket.get().maxConcurrentWrites());     // writeMessage was never reached
		assertEquals(1, onClosedCount.get());                          // and no second close signal
		pair.closeAll();
	}

	@Test
	public void testQueuedSendReadsTheDocumentArrayWhenItsWriteRuns() {
		// G7, adversarial plan: the SPI says a document "is not retained after the returned promise
		// completes" — which leaves open whether the array is read at send() time or at write time.
		// PINNED, OBSERVED BEHAVIOUR: it is read LAZILY. send() only captures the reference in the
		// writeTail continuation; the byte[] -> String conversion happens inside doWrite, i.e. when the
		// queued write actually runs. A caller that mutates the array after send() returns but before
		// that send's write executes therefore puts the MUTATED bytes on the wire — the array must be
		// treated as owned by the transport until the returned promise completes, exactly as the SPI
		// wording implies. (The first send is unaffected: writeTail is complete, so its doWrite — and
		// its conversion — runs synchronously inside send.)
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		transport.setListener(listener($ -> {}, e -> {}));

		byte[] doc1 = "{\"jsonrpc\":\"2.0\",\"method\":\"in.flight\"}".getBytes(UTF_8);
		byte[] doc2 = "{\"jsonrpc\":\"2.0\",\"method\":\"queued-original\"}".getBytes(UTF_8);
		byte[] mutation = "{\"jsonrpc\":\"2.0\",\"method\":\"queued-MUTATED!\"}".getBytes(UTF_8);
		assertEquals(doc2.length, mutation.length);         // an in-place overwrite, same array identity

		Promise<Void> send1 = transport.send(doc1);         // reaches writeMessage synchronously, pending
		Promise<Void> send2 = transport.send(doc2);         // queued on writeTail: no writeMessage yet
		assertEquals(1, webSocket.writtenTexts().size());   // proof the second write has not run
		assertEquals(new String(doc1, UTF_8), webSocket.writtenTexts().get(0));

		System.arraycopy(mutation, 0, doc2, 0, doc2.length);   // the caller mutates a send it already issued

		webSocket.completeWrite();                          // send1 completes -> send2's doWrite runs now
		await(send1);
		assertEquals(2, webSocket.writtenTexts().size());
		// the mutation IS observed: the conversion happened at write time, not at send time
		assertEquals(new String(mutation, UTF_8), webSocket.writtenTexts().get(1));
		assertNotEquals("{\"jsonrpc\":\"2.0\",\"method\":\"queued-original\"}", webSocket.writtenTexts().get(1));

		webSocket.completeWrite();
		await(send2);
	}

	@Test
	public void testNoDocumentIsEverDeliveredAfterOnClosed() {
		// B9, adversarial plan: the SPI promises "never called after onClosed" — pin the ordering
		// directly rather than relying on it falling out of other tests. The serial read loop issues
		// exactly one readMessage() at a time (FR-090): a document is delivered, doRead() re-issues the
		// next read, and THAT read fails in the very next pass — the closest reproduction of "a read
		// delivers a document, then fails, in the same overall sequence" that StubWebSocket's real
		// one-outstanding-read-at-a-time model allows. A single ordered log proves the document is
		// recorded strictly before the close, and nothing arrives after it.
		StubWebSocket webSocket = new StubWebSocket();
		JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), webSocket);
		List<String> events = new ArrayList<>();
		transport.setListener(listener(
			doc -> events.add("onDocument:" + new String(doc, UTF_8)),
			e -> events.add("onClosed")));

		byte[] doc = "{\"jsonrpc\":\"2.0\",\"method\":\"last-before-close\"}".getBytes(UTF_8);
		webSocket.deliverMessage(IWebSocket.Message.text(new String(doc, UTF_8)));   // delivered synchronously
		// doRead() has already re-issued the next readMessage() by now — fail that one immediately
		webSocket.failRead(new WebSocketException(1002, "peer misbehaved right after the last document"));

		assertEquals(List.of("onDocument:{\"jsonrpc\":\"2.0\",\"method\":\"last-before-close\"}", "onClosed"), events);
	}

	/** A distinct, order-revealing document per index — {@code doc-0} … {@code doc-49} (A5). */
	private static byte[] document(int index) {
		return ("{\"jsonrpc\":\"2.0\",\"method\":\"doc-" + index + "\"}").getBytes(UTF_8);
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	/**
	 * Closes both transports once every expected document has been delivered. Closing while a send
	 * is still in flight on the wire would discard the peer's unread messages (a send promise
	 * completes when <i>written</i>, not when <i>delivered</i>), so the close is triggered from the
	 * listeners rather than from the send chain (R3's closure-inside-the-awaited-chain rule).
	 */
	private static void maybeClose(
		RefBoolean closeScheduled, int expectedServer, int expectedClient,
		List<byte[]> serverReceived, List<byte[]> clientReceived,
		Ref<JsonRpcWsTransport> clientTransport, Ref<JsonRpcWsTransport> serverTransport
	) {
		if (closeScheduled.get()) return;
		if (serverReceived.size() < expectedServer || clientReceived.size() < expectedClient) return;
		closeScheduled.set(true);
		clientTransport.get().closeEx(new AsyncCloseException());
		serverTransport.get().closeEx(new AsyncCloseException());
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
}