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
import io.activej.http.HttpServer;
import io.activej.http.IWebSocket;
import io.activej.http.WebSocketException;
import io.activej.jsonrpc.JsonRpcLimits;
import io.activej.jsonrpc.transport.JsonRpcTransport;
import io.activej.jsonrpc.transport.ws.fixtures.WsPair;
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
 * <p>
 * <b>Extended by feature 019 (T038)</b> with FR-031's <i>consequence</i>, which the original case
 * leaves implicit: at equal defaults the transport tier fires first, so the envelope's {@code -32001}
 * is unreachable. FR-030's own during-accumulation property is pinned by
 * {@link JsonRpcWsFragmentedOversizeTest}, which lives apart because the fragmented path it has to
 * take trips a core-http leak — and this class must keep its own leak checking intact.
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

	// ---------------------------------------------------------------------------------------------
	// FR-031 (feature 019, T038) — the consequence the case above leaves implicit: it is the TRANSPORT
	// tier doing the cutting, and that is why the envelope's -32001 is unreachable at defaults.
	// FR-030's own property, that the cut happens DURING accumulation, lives in
	// JsonRpcWsFragmentedOversizeTest — separately, because it trips a core-http leak this class must
	// keep checking for.
	// ---------------------------------------------------------------------------------------------

	/**
	 * FR-031's <b>consequence</b>, pinned rather than described: the two tiers' defaults are equal, so
	 * the transport tier fires first and the envelope's {@code -32001 Request too large} is
	 * unreachable at defaults (ADR-039).
	 * <p>
	 * The document here is exactly {@code JsonRpcLimits.MAX_BODY_SIZE + 1} bytes — the size at which
	 * the envelope decoder would answer {@code -32001} if it were ever handed the document. It is not:
	 * nothing reaches the listener at all, so the decoder never runs and the envelope answer cannot be
	 * produced. That is the whole of the "unreachable" claim, and it is a claim about the <i>order</i>
	 * of the tiers rather than about either value — which is why the equality of the two defaults is
	 * asserted first: raise the receiving side's transport tier strictly above the envelope tier and
	 * {@code -32001} becomes reachable again, which is exactly what {@code JsonRpcWsConformanceTest}'s
	 * 2 mb cap buys and why its skip set is empty where the HTTP transport's is not.
	 */
	@Test
	public void testAtEqualDefaultsTheTransportTierFiresFirstSoTheEnvelopesMinus32001IsUnreachable() {
		assertEquals("the consequence pinned by this test rests on the two defaults being equal (ADR-039); " +
					 "if they ever diverge, this test — not a comment — is what says so",
			JsonRpcLimits.MAX_BODY_SIZE.toInt(), HttpServer.MAX_WEB_SOCKET_MESSAGE_SIZE.toInt());

		Ref<Exception> closed = new Ref<>();
		List<byte[]> delivered = new ArrayList<>();
		WsPair pair = WsPair.serverUpgrade(reactor(), ws -> {
			JsonRpcWsTransport transport = JsonRpcWsTransport.of(reactor(), ws);
			transport.setListener(listener(delivered::add, closed::set));
		});

		byte[] oneOverTheEnvelopeTier = new byte[JsonRpcLimits.MAX_BODY_SIZE.toInt() + 1];
		Arrays.fill(oneOverTheEnvelopeTier, (byte) 'a');
		await(pair.connect().then(ws ->
			ws.writeMessage(IWebSocket.Message.text(new String(oneOverTheEnvelopeTier, UTF_8)))
				.whenException(e -> {})));

		assertEquals("the envelope decoder is never reached, so -32001 can never be produced: " + delivered.size() +
					 " document(s) were delivered", List.of(), delivered);
		assertThat(closed.get(), instanceOf(WebSocketException.class));
		assertEquals("the transport tier answers, and its answer is a close code — not a JSON-RPC error",
			Integer.valueOf(1009), ((WebSocketException) closed.get()).getCode());
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