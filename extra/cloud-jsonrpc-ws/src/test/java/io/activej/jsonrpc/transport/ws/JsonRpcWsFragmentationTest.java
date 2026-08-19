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
import io.activej.bytebuf.ByteBuf;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefBoolean;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.CountingWebSocket;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static io.activej.http.IWebSocket.Frame;
import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Fragmentation and write-serialisation (T006).
 * <p>
 * <b>Fragmentation (FR-090).</b> core-http's {@code readMessage} joins TEXT + CONTINUATION frames
 * into one message <i>before</i> any JSON-RPC byte reaches the transport (verdict 00-A's contiguity
 * rule is satisfied at the message boundary), so a document split into two frames on the wire must
 * arrive as one contiguous document. The frame-level write API appears here because it is the only
 * way to produce a fragmented message (FR-011 confines frames to tests).
 * <p>
 * <b>Write serialisation (FR-017).</b> {@code WebSocket} enforces one {@code writeMessage} in flight
 * with a {@code checkState}; the transport's internal queue is what makes concurrent sends legal.
 * The {@link CountingWebSocket} wraps each side to observe the invariant directly, and the
 * per-direction receive order proves the FIFO queue preserved order.
 */
public final class JsonRpcWsFragmentationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testFragmentedDocumentReassembledBeforeDecode() {
		// FR-090: one document written as two frames through the client's frame-level API arrives as
		// one contiguous document at the server, which can then answer with it — proving the joined
		// message was complete and decodable.
		byte[] document = "{\"jsonrpc\":\"2.0\",\"method\":\"fragmented\",\"params\":{\"parts\":2}}".getBytes(UTF_8);
		byte[] part1 = Arrays.copyOfRange(document, 0, document.length / 2);
		byte[] part2 = Arrays.copyOfRange(document, document.length / 2, document.length);

		Ref<byte[]> serverReceived = new Ref<>();
		Ref<byte[]> clientReceived = new Ref<>();
		RefBoolean closeScheduled = new RefBoolean(false);
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			serverTransport.set(transport);
			transport.setListener(listener(doc -> {
				serverReceived.set(doc);
				maybeClose(closeScheduled, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
		});

		await(pair.connect().then(ws -> {
			Promise<Void> first = ws.writeFrame(Frame.text(ByteBuf.wrapForReading(part1), false));
			return first.then($ -> ws.writeFrame(Frame.next(ByteBuf.wrapForReading(part2), true)))
				.then($ -> {
					clientTransport.set(JsonRpcWsTransport.of(reactor(), ws));
					clientTransport.get().setListener(listener(doc -> {
						clientReceived.set(doc);
						maybeClose(closeScheduled, serverReceived, clientReceived, clientTransport, serverTransport);
					}, e -> {}));
					return serverTransport.get().send(document);   // answer with the joined document
				});
		}));

		assertArrayEquals(document, serverReceived.get());          // joined before decode
		assertArrayEquals(document, clientReceived.get());          // the answer, as one document
		pair.closeAll();
	}

	@Test
	public void testFragmentedDocumentWithInvalidUtf8InLastFragmentRejectedWith1007() {
		// FR-092 + FR-013: UTF-8 validity is a property of the *reassembled* message, not of each frame
		// in isolation (A3, adversarial plan). A first fragment of valid text followed by a final
		// fragment carrying an invalid continuation byte must be refused with close 1007, and no partial
		// document may ever reach onDocument.
		byte[] part1 = "{\"jsonrpc\":\"2.0\",\"method\":\"frag".getBytes(UTF_8);
		byte[] invalidUtf8 = new byte[]{(byte) 0xC3, 0x28};          // not a valid UTF-8 sequence

		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from a fragmented message ending in invalid UTF-8"),
				closed::set));
		});

		await(pair.connect().then(ws -> ws.writeFrame(Frame.text(ByteBuf.wrapForReading(part1), false))
			.then($ -> ws.writeFrame(Frame.next(ByteBuf.wrapForReading(invalidUtf8), true)))));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1007), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
	}

	@Test
	public void testWriteSerialisationOneMessageInFlightOrderPreserved() {
		// FR-017: concurrent sends from both directions complete with exactly one writeMessage in
		// flight per transport, and each direction's receive order matches its send order.
		byte[] docA = "{\"jsonrpc\":\"2.0\",\"method\":\"a\"}".getBytes(UTF_8);
		byte[] docB = "{\"jsonrpc\":\"2.0\",\"method\":\"b\"}".getBytes(UTF_8);
		byte[] docC = "{\"jsonrpc\":\"2.0\",\"method\":\"c\"}".getBytes(UTF_8);
		byte[] docD = "{\"jsonrpc\":\"2.0\",\"method\":\"d\"}".getBytes(UTF_8);
		byte[] docE = "{\"jsonrpc\":\"2.0\",\"method\":\"e\"}".getBytes(UTF_8);
		byte[] docF = "{\"jsonrpc\":\"2.0\",\"method\":\"f\"}".getBytes(UTF_8);

		List<byte[]> serverReceived = new ArrayList<>();
		List<byte[]> clientReceived = new ArrayList<>();
		RefBoolean closeScheduled = new RefBoolean(false);
		Ref<JsonRpcWsTransport> serverTransport = new Ref<>();
		Ref<JsonRpcWsTransport> clientTransport = new Ref<>();
		Ref<CountingWebSocket> serverCounter = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			CountingWebSocket counting = new CountingWebSocket(ws);
			serverCounter.set(counting);
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), counting);
			serverTransport.set(transport);
			transport.setListener(listener(doc -> {
				serverReceived.add(doc);
				maybeClose(closeScheduled, 3, 3, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
		});

		Ref<CountingWebSocket> clientCounter = new Ref<>();
		await(pair.connect().then(ws -> {
			CountingWebSocket counting = new CountingWebSocket(ws);
			clientCounter.set(counting);
			clientTransport.set(JsonRpcWsTransport.of(reactor(), counting));
			clientTransport.get().setListener(listener(doc -> {
				clientReceived.add(doc);
				maybeClose(closeScheduled, 3, 3, serverReceived, clientReceived, clientTransport, serverTransport);
			}, e -> {}));
			// interleave the two directions without awaiting each send: each transport must
			// serialise its own queue
			Promise<Void> serverSends = Promise.complete();
			Promise<Void> clientSends = Promise.complete();
			serverSends = serverSends.then($ -> serverTransport.get().send(docA));
			clientSends = clientSends.then($ -> clientTransport.get().send(docD));
			serverSends = serverSends.then($ -> serverTransport.get().send(docB));
			clientSends = clientSends.then($ -> clientTransport.get().send(docE));
			serverSends = serverSends.then($ -> serverTransport.get().send(docC));
			clientSends = clientSends.then($ -> clientTransport.get().send(docF));
			return Promises.all(serverSends, clientSends);
		}));

		assertEquals(1, serverCounter.get().maxConcurrentWrites());
		assertEquals(1, clientCounter.get().maxConcurrentWrites());
		assertEquals(3, serverReceived.size());                      // client's sends, in order
		assertArrayEquals(docD, serverReceived.get(0));
		assertArrayEquals(docE, serverReceived.get(1));
		assertArrayEquals(docF, serverReceived.get(2));
		assertEquals(3, clientReceived.size());                      // server's sends, in order
		assertArrayEquals(docA, clientReceived.get(0));
		assertArrayEquals(docB, clientReceived.get(1));
		assertArrayEquals(docC, clientReceived.get(2));
		pair.closeAll();
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

	private static void maybeClose(
		RefBoolean closeScheduled, Ref<byte[]> serverReceived, Ref<byte[]> clientReceived,
		Ref<JsonRpcWsTransport> clientTransport, Ref<JsonRpcWsTransport> serverTransport
	) {
		if (closeScheduled.get()) return;
		if (serverReceived.get() == null || clientReceived.get() == null) return;
		closeScheduled.set(true);
		clientTransport.get().closeEx(new AsyncCloseException());
		serverTransport.get().closeEx(new AsyncCloseException());
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
}