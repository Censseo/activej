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

import io.activej.common.ref.Ref;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.function.Consumer;

import static io.activej.promise.TestUtils.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The oversize-message refusal (T005, FR-091): the transport tier
 * ({@code HttpServer.maxWebSocketMessageSize}, 1 mb default) is applied DURING accumulation — the
 * server's decoder fires close {@code 1009} on the oversize frame's header, before a complete
 * document exists to decode, so the envelope's {@code -32001} can never be produced.
 * <p>
 * The connection is cut mid-read on purpose (the server rejects the message while the client's
 * payload is still arriving); this used to strand one read buffer in core-http's WebSocket decoder
 * ({@code WebSocketBufsToFrames} never closed its input) and required a documented
 * {@code @IgnoreLeaks} here. Fixed in core-http; this class is leak-checked like any other.
 */
public final class JsonRpcWsOversizeTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	@Test
	public void testOversizeMessageRejectedWith1009MidAccumulation() {
		// FR-091: the transport tier is applied during accumulation — the decoder fires 1009 on the
		// frame header, before a complete document exists to decode.
		Ref<Exception> closed = new Ref<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(
				$ -> fail("no document may be delivered from an oversize message"),
				closed::set));
		});

		byte[] big = new byte[1_200_000];                            // > 1 mb cap
		Arrays.fill(big, (byte) 'a');
		await(pair.connect().then(ws ->
			// the server cuts the connection once the accumulation crosses the cap, so the write's
			// failure is expected and tolerated
			ws.writeMessage(IWebSocket.Message.text(new String(big, UTF_8))).whenException(e -> {})));

		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(1009), ((WebSocketException) closed.get()).getCode());
		pair.closeAll();
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