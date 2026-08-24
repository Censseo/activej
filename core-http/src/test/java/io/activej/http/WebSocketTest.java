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

package io.activej.http;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.IWebSocket.Frame;
import io.activej.http.IWebSocket.Message;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests of {@link WebSocket#readMessage()} itself, one level above
 * {@link WebSocketBufsToFramesTest}: these construct a {@link WebSocket} directly over a fake,
 * pre-built {@code ChannelSupplier<Frame>} -- exactly the seam {@link WebSocketServlet} and
 * {@link HttpClientConnection} construct it through -- with no real socket, decoder or HTTP upgrade
 * involved. {@code WebSocket}'s constructor is package-private for exactly this reason.
 */
public final class WebSocketTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// Small on purpose: the point is to cross it with two ordinary-sized fragments, not to flood.
	private static final int MAX_MESSAGE_SIZE = 10;

	@Test
	public void aFragmentedMessageThatStaysUnderMaxMessageSizeReassemblesNormally() {
		WebSocket webSocket = webSocketOf(
			Frame.text(pooled("Hel"), false),
			Frame.next(pooled("lo"), true));

		Message message = await(webSocket.readMessage());

		assertEquals(Message.MessageType.TEXT, message.getType());
		assertEquals("Hello", message.getText());
	}

	@Test
	public void aFragmentedMessageThatCrossesMaxMessageSizeDoesNotStrandTheCrossingFragmentsPayload() {
		// Six bytes, then six more: the first fragment alone is under the 10-byte cap and is added to
		// messageBufs; the second crosses the running total (6 + 6 = 12 > 10) -- the fragment
		// WebSocket.readMessage used to neither add nor recycle. ByteBufRule (a @ClassRule) fails the
		// whole class at teardown if this regresses.
		WebSocket webSocket = webSocketOf(
			Frame.text(pooled("aaaaaa"), false),
			Frame.next(pooled("bbbbbb"), true));

		Exception e = awaitException(webSocket.readMessage());

		assertTrue(e instanceof WebSocketException);
		assertEquals(Integer.valueOf(1009), ((WebSocketException) e).getCode());
	}

	private static WebSocket webSocketOf(Frame... frames) {
		return new WebSocket(
			HttpRequest.get("http://127.0.0.1/ws").build(),
			HttpResponse.ok200().build(),
			ChannelSuppliers.ofValues(frames),
			ChannelConsumers.recycling(),
			e -> {},
			MAX_MESSAGE_SIZE);
	}

	private static ByteBuf pooled(String ascii) {
		byte[] bytes = ascii.getBytes(UTF_8);
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}
}
