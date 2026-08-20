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

import io.activej.bytebuf.ByteBufPool;
import io.activej.common.ref.Ref;
import io.activej.dns.DnsClient;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.http.WebSocketConstants.HANDSHAKE_FAILED;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.getFreePort;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * {@link WebSocketServlet#serve} must not strand the request body stream when the upgrade fails.
 * <p>
 * The stream a WebSocket request carries is {@code concat(ofValue(detachReadBuf()), ofSocket(socket))}
 * ({@code HttpServerConnection#processWebSocketRequest}), and {@code detachReadBuf()} hands out an
 * already pooled buffer. Once {@code takeBodyStream()} has transferred ownership, nothing downstream
 * can recover it — {@code HttpMessage#recycle} finds a null field — so a refusal that runs after the
 * take leaks that reference. The servlet now takes the stream only once a legal {@code 101} has been
 * produced; these two cases are the ones that used to run after the take.
 */
public final class WebSocketServletUpgradeFailureTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Ref<Throwable> fatalError = new Ref<>();

	private NioReactor reactor;
	private DnsClient dnsClient;
	private int port;

	@Before
	public void setUp() {
		// Both cases below refuse the upgrade with an UNCHECKED throw, which FatalErrorHandler.handleError
		// routes to the current handler. EventloopRule's handler is rethrow(), which would surface the
		// throw on the JUnit thread before the connection is ever answered and leave that connection
		// parked in the server's `serving` pool, so nothing would quiesce and awaitException would never
		// return. A capturing handler is the repo's existing answer for this exact shape
		// (AbstractHttpConnectionTest#testFatalErrorHandling), and it must be given to the builder:
		// Eventloop.run() installs its own handler on the thread every time it runs, so
		// setThreadFatalErrorHandler() before awaiting would just be overwritten.
		reactor = Eventloop.builder()
			.withCurrentThread()
			.withFatalErrorHandler((e, context) -> fatalError.set(e))
			.build();
		dnsClient = DnsClient.create(reactor, inetAddress("8.8.8.8"));
		port = getFreePort();
	}

	@Test
	public void aThrowFromOnRequestDoesNotStrandTheRequestBodyStream() throws IOException {
		RuntimeException refusal = new RuntimeException("no upgrade for you");
		int liveBuffersBefore = liveBuffers();

		listen(new WebSocketServlet(reactor) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				throw refusal;
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				fail("the upgrade was refused, no web socket may be delivered");
			}
		});

		Exception e = awaitException(HttpClient.create(reactor, dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build()));

		assertEquals("a refused upgrade fails the client the same way a refusing status code does",
			HANDSHAKE_FAILED, e);
		assertSame("an unchecked throw out of a servlet stays a fatal error", refusal, fatalError.get());
		assertEquals("the request body stream the servlet never took is back in the pool",
			liveBuffersBefore, liveBuffers());
	}

	@Test
	public void aOneOhOneCarryingABodyDoesNotStrandTheRequestBodyStreamNorItsOwnBody() throws IOException {
		int liveBuffersBefore = liveBuffers();

		listen(new WebSocketServlet(reactor) {
			@Override
			protected Promise<HttpResponse> onRequest(HttpRequest request) {
				// a 101 must carry neither a body nor a body stream; the servlet rejects this one with
				// an IllegalStateException raised inside .map(...), downstream of every recycler that
				// used to exist
				return HttpResponse.ofCode(101).withBody(ByteBufPool.allocate(1000)).toPromise();
			}

			@Override
			protected void onWebSocket(IWebSocket webSocket) {
				fail("an illegal 101 must not be upgraded");
			}
		});

		Exception e = awaitException(HttpClient.create(reactor, dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build()));

		assertEquals(HANDSHAKE_FAILED, e);
		assertThat(fatalError.get(), instanceOf(IllegalStateException.class));
		assertEquals("Illegal body or stream", fatalError.get().getMessage());
		assertEquals("neither the request body stream nor the rejected response body is stranded",
			liveBuffersBefore, liveBuffers());
	}

	private void listen(WebSocketServlet servlet) throws IOException {
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", servlet)
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();
	}

	/** Pooled buffers allocated but not yet returned — zero of them is what "recycled" means. */
	private static int liveBuffers() {
		return ByteBufPool.getStats().getCreatedItems() - ByteBufPool.getStats().getPoolItems();
	}
}
