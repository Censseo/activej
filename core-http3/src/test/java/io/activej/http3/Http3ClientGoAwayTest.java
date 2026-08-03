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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDnsClient;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T089 / FR-020, FR-049, US7 §2: a connection that has announced GOAWAY carries no new request. The
 * client retires it — its in-flight work is left to finish — and opens a fresh connection for the next
 * request to that authority.
 * <p>
 * The peer here is a hand-built server-side {@link Http3Connection} rather than an {@link Http3Server},
 * because what has to be controlled is <b>when</b> the announcement happens: an {@code Http3Server}
 * announces only as part of shutting down, which would confound "the client stopped using this
 * connection" with "the server stopped answering at all". It serves through the module's own
 * {@link Http3RequestStream}, and records which connection carried each request — the assertion this
 * story turns on.
 */
public final class Http3ClientGoAwayTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** A request the test parks, so that a connection has something to drain while it is retired. */
	private static final String HELD = "/held";

	private static final String FIRST = "/first";
	private static final String SECOND = "/second";

	/** How many times the retiring peer is made to do it, in the accumulation test. */
	private static final int REPEATED_REQUESTS = 6;

	private final StubDnsClient dns = new StubDnsClient();

	/** Peer answers, resolved by the test — a request is in flight exactly while its entry is unset. */
	private final Map<String, SettablePromise<HttpResponse>> pending = new LinkedHashMap<>();

	/** One entry per accepted QUIC connection, in order; the index is how a test names a connection. */
	private final List<Http3Connection> serverConnections = new ArrayList<>();

	/** {@code "<connection index> <path>"} per request served, so a test can see which connection carried it. */
	private final List<String> served = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Client client;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void afterGoAwayTheNextRequestGoesOnANewConnection() {
		start(Http3Settings.create());
		assertEquals("first", exchange(HOST, FIRST));
		assertEquals(1, serverConnections.size());

		announceGoAway(0);

		assertEquals("second", exchange(HOST, SECOND));

		assertEquals("US7 §2: the retired connection carried nothing new",
			List.of("0 " + FIRST, "1 " + SECOND), served);
		assertEquals("a second connection was opened for the same authority", 2, serverConnections.size());
		assertEquals("the pool holds one connection per authority still", 1, client.connectionCount());
		assertEquals(2, client.connectionsOpened());
	}

	@Test
	public void aRetiredConnectionStillFinishesWhatItAlreadyCarries() {
		start(Http3Settings.create());
		// Parked on connection 0, so that retiring it has something to drain.
		Promise<HttpResponse> held = client.request(HttpRequest.get(url(HOST, HELD)).build());
		wire.driveUntil(() -> pending.containsKey(HELD));

		announceGoAway(0);

		// The next request goes elsewhere ...
		assertEquals("second", exchange(HOST, SECOND));
		assertEquals(List.of("0 " + HELD, "1 " + SECOND), served);
		assertNotEquals("RFC 9114 §5.2: what is at or below the identifier is still owed an answer",
			State.CLOSED, serverConnections.get(0).state());
		assertEquals("a retired connection with a request still on it is held", 1, client.retiredConnectionCount());

		// ... and the request already on the retired connection still gets its answer, on that connection.
		answer(HELD);
		assertEquals("held", body(held));
		assertEquals(2, client.connectionsOpened());
		assertEquals("SI-3: and it is closed the moment it has nothing left to carry",
			0, client.retiredConnectionCount());
	}

	/**
	 * T113 / SI-3: a peer that announces GOAWAY on every connection it accepts makes each request dial a
	 * fresh one. What it must <b>not</b> do is leave the client holding one retired connection per
	 * request: a retired connection is closed as soon as it is carrying nothing, so the bookkeeping stays
	 * bounded however many times the peer does this.
	 */
	@Test
	public void aPeerThatRetiresEveryConnectionDoesNotAccumulateRetiredOnes() {
		start(Http3Settings.create());

		for (int i = 0; i < REPEATED_REQUESTS; i++) {
			// Request i+1 finds connection i going away, retires it — and, since it is carrying nothing by
			// then, closes it rather than adding it to a set nothing prunes.
			assertEquals("r" + i, exchange(HOST, "/r" + i));
			announceGoAway(i);
			assertTrue("SI-3: retired connections do not accumulate, at request " + i + ": " +
					   client.retiredConnectionCount(),
				client.retiredConnectionCount() <= 1);
		}

		assertEquals("every request after the first re-dialled", REPEATED_REQUESTS, serverConnections.size());
		assertEquals(REPEATED_REQUESTS, client.connectionsOpened());
		assertEquals("nothing was left behind", 0, client.retiredConnectionCount());
		assertEquals("the pool holds one connection per authority still", 1, client.connectionCount());
	}

	/**
	 * FR-049: the least-recently-used idle connection is evicted <b>with GOAWAY</b>, not with a bare
	 * close — asserted where it can only be true if the frame crossed the wire, on the peer that decoded
	 * it.
	 */
	@Test
	public void anEvictedIdleConnectionLeavesWithGoAway() {
		start(Http3Settings.builder().withMaxConnections(1).build());
		exchange(HOST, FIRST);
		assertEquals(1, serverConnections.size());

		// A second authority at maxConnections=1: the pool is full and its one entry is idle, so it goes.
		exchange(OTHER_HOST, SECOND);
		wire.advance(1);

		assertEquals(1, client.connectionsEvicted());
		assertEquals(2, serverConnections.size());
		assertEquals("FR-049: the evicted connection left with GOAWAY, not a bare close",
			0, serverConnections.get(0).goAwayReceivedId());
	}

	// ---------------------------------------------------------------- harness

	private void start(Http3Settings clientSettings) {
		wire = new Http3WirePair(loop)
			.withServerHandlerFactory(this::acceptConnection)
			.withClientFactory(socket -> client = Http3Client.builder(reactor(), dns)
				.withSocket(socket)
				.withSettings(clientSettings)
				// Every authority is verified against the dev certificate's own name: what is under test is
				// which connection a request goes on, not endpoint identification.
				.withTlsEngineFactory(host -> Http3TestTls.clientEngineFactory(HOST))
				.build())
			.connect();
	}

	/** One server-side {@link Http3Connection} per accepted QUIC connection, serving from its own index. */
	private QuicFrameHandler acceptConnection(QuicConnection quicConnection) {
		int index = serverConnections.size();
		Http3Connection h3 = Http3Connection.builder(reactor(), quicConnection)
			.withRequestStreamListener(requestStream -> serve(index, requestStream))
			.build();
		serverConnections.add(h3);
		return h3.startAndGetStreamManager();
	}

	private void serve(int connectionIndex, Http3RequestStream requestStream) {
		requestStream.receiveRequest()
			.then(request -> {
				String path = request.getPath();
				served.add(connectionIndex + " " + path);
				return pending.computeIfAbsent(path, $ -> new SettablePromise<>());
			})
			.then(requestStream::sendResponse);
	}

	private void answer(String path) {
		pending.get(path).set(HttpResponse.ok200()
			.withBody(path.substring(1).getBytes(UTF_8))
			.build());
	}

	/** Announces GOAWAY on the connection the client is currently pooling, and lets it arrive. */
	private void announceGoAway(int connectionIndex) {
		Promise<Void> announced = serverConnections.get(connectionIndex).goAway();
		wire.driveUntil(announced::isComplete);
		wire.advance(1);
	}

	/** One complete request/response exchange, returning the body the peer answered with. */
	private String exchange(String host, String path) {
		Promise<HttpResponse> request = client.request(HttpRequest.get(url(host, path)).build());
		wire.driveUntil(() -> pending.containsKey(path) || request.isComplete());
		answer(path);
		return body(request);
	}

	/** Drives {@code request} to its response and reads the whole body out of it. */
	private String body(Promise<HttpResponse> request) {
		wire.driveUntil(request::isComplete);
		if (!request.isResult()) {
			throw new AssertionError("the request failed: " + request, request.getException());
		}
		Promise<ByteBuf> loaded = request.getResult().loadBody();
		wire.driveUntil(loaded::isComplete);
		assertTrue("the body did not load: " + loaded, loaded.isResult());
		ByteBuf body = loaded.getResult();
		try {
			return body.getString(UTF_8);
		} finally {
			body.recycle();
		}
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
