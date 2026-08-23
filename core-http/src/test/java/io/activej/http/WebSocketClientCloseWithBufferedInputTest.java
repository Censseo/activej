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

import io.activej.async.exception.AsyncCloseException;
import io.activej.common.ref.Ref;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.http.IWebSocket.Message;
import static io.activej.promise.TestUtils.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Regression test for a {@link ByteBuf} leak on the client side of a WebSocket that is closed while
 * inbound messages are still buffered and unread.
 *
 * <h2>The defect</h2>
 * {@link HttpClientConnection#bindWebSocketTransformers} used to call {@code closeWebSocketConnection}
 * from exactly one place — the {@code closeSentPromise -> closeReceivedPromise} chain. That chain
 * never fires when <b>this</b> side closes first: the peer's CLOSE frame is never read, so
 * {@code closeReceivedPromise} never settles. The raw socket was still torn down, by the CSP cascade
 * that runs when the {@code WebSocket}'s frame streams are closed, so from the outside everything
 * looked correct — the eventloop went quiescent and no exception was raised. But
 * {@link HttpClientConnection#onClosed()} never ran, and with it neither {@code response.recycle()}
 * nor {@code stashedBufs.recycle()} — and {@code stashedBufs} holds the pooled buffer the {@code 101}
 * response head was parsed out of, which the response's header tokens point into. One 16 kB pooled
 * {@code ByteBuf} was lost per such connection.
 *
 * <h2>Why buffered input is the trigger</h2>
 * With nothing buffered, the client always has a socket read in flight, so the peer's teardown reaches
 * the connection through the ordinary read-error path and {@code onClosed()} runs anyway. It is only
 * when the decoder already has undelivered bytes — no read outstanding, nothing left to fail — that
 * the connection object is orphaned. That is why this test leaves several answers unread rather than
 * one: a single unread answer does not reproduce it.
 *
 * <h2>The oracle is {@link ByteBufRule}</h2>
 * There is nothing to assert about the observable behaviour, which was correct before and after: the
 * client closes, the server sees it, the loop quiesces. The leak is the whole defect, so the class
 * rule is the assertion, and the two {@code assert}s below only pin the premise — that the exchange
 * really happened and really left messages unread.
 */
public final class WebSocketClientCloseWithBufferedInputTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Enough answers that the ones after the first are certainly still buffered when the client closes. */
	private static final int ANSWERS = 5;

	private Eventloop eventloop;

	@Before
	public void setUp() {
		eventloop = (Eventloop) Reactor.getCurrentReactor();
	}

	@Test
	public void closingAClientWebSocketWithUnreadMessagesBufferedRecyclesTheConnectionsBuffers() throws IOException {
		Ref<Integer> answersWritten = new Ref<>(0);
		// one request in, ANSWERS answers out, all written in the same turn so they land in one socket
		// read on the client — no timing assumption is needed beyond that
		HttpServer server = HttpServer.builder(eventloop, RoutingServlet.builder(eventloop)
				.withWebSocket("/", webSocket -> webSocket.readMessage()
					.whenResult(message -> {if (message != null) message.recycle();})
					.then(() -> {
						Promise<Void> writes = Promise.complete();
						for (int i = 0; i < ANSWERS; i++) {
							int index = i;
							writes = writes.then(() -> webSocket.writeMessage(Message.text("answer-" + index))
								.whenResult(() -> answersWritten.set(answersWritten.get() + 1)));
						}
						return writes;
					})
					.whenComplete(() -> webSocket.closeEx(new AsyncCloseException("server done"))))
				.build())
			.withListenPort(0)
			.withAcceptOnce()
			.withReadWriteTimeout(Duration.ZERO)
			.build();
		server.listen();
		int port = server.getBoundAddresses().get(0).getPort();

		IWebSocketClient client = HttpClient.create(eventloop, DnsClient.create(eventloop, inetAddress("8.8.8.8")));
		Ref<String> firstAnswer = new Ref<>();

		await(client.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.writeMessage(Message.text("request"))
				// exactly one message is consumed; the other ANSWERS-1 stay buffered inside the client's
				// decoder, which is precisely the state that used to orphan the connection
				.then(webSocket::readMessage)
				.whenResult(message -> firstAnswer.set(message.getText()))
				.whenComplete(() -> webSocket.closeEx(new AsyncCloseException("client hangs up")))
				.toVoid()));

		server.close();
		eventloop.run();

		assertNotNull("the client never received an answer, so nothing was ever buffered", firstAnswer.get());
		assertEquals("answer-0", firstAnswer.get());
		assertEquals("every answer must have been written, so the unread ones really were in flight",
			Integer.valueOf(ANSWERS), answersWritten.get());
	}
}
