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
import io.activej.test.rules.ByteBufRule.IgnoreLeaks;
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
 * <b>{@code @IgnoreLeaks} — with justification.</b> This test deliberately cuts the connection
 * mid-read: the server rejects the message while the client's payload is still arriving and the
 * server's raw-read pipeline still holds one 16 kb read buffer ({@code TcpSocket.onReadReady}). That
 * buffer is stranded by core-http's read pipeline when a connection is closed mid-read — reproduced
 * identically by a plain WebSocket client/server with <i>no</i> transport and no session (a scratch
 * repro confirmed it). It is therefore a pre-existing leak in the reused core-http stack, not in this
 * module: {@code JsonRpcWsTransport}'s only ByteBuf ownership is the BINARY refusal path (R8), which
 * {@code JsonRpcWsHostileTest#testBinaryMessageRejectedWith1003AndPayloadRecycled} proves leak-free.
 * Core modules may not be modified (SC-007), so the leak-scan opt-out is the honest scoping here; the
 * leak is reported for the Phase 6 hardening pass (T018). Kept in its own class so the opt-out does
 * not silence the leak scan for the transport's own recycle proof.
 */
@IgnoreLeaks("FR-091's mandated oversize test cuts the connection mid-read; core-http strands one "
			 + "read buffer on that path (reproduced without this module). Not a JsonRpcWsTransport "
			 + "leak — its only ByteBuf ownership, the BINARY refusal path, is leak-checked in "
			 + "JsonRpcWsHostileTest. Core modules may not be touched (SC-007).")
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