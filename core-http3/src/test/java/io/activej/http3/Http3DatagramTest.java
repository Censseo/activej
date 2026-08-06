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
import io.activej.bytebuf.ByteBufPool;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDatagramNetwork;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T123, User Story 6's Independent Test: HTTP/3 datagrams (RFC 9297) between a real {@link Http3Client}
 * and a real {@link Http3Server}, both directions bound to <b>one</b> request stream, across send,
 * receive, loss, drop and close.
 * <p>
 * Everything here goes over a genuine QUIC connection — real TLS 1.3, real packet protection, real loss
 * detection — with only the socket and the clock synthetic ({@link Http3ClientFixture}). The exchange is
 * deliberately held open by a servlet that does not answer until the test tells it to: an HTTP/3
 * datagram is bound to a live exchange, so an exchange that ended before the first datagram would be
 * testing {@code EXCHANGE_ENDED} instead.
 * <p>
 * The five scenarios and what each is for:
 * <ul>
 *   <li><b>send/receive</b> — N each way arrive intact and in order on the exchange they were bound to,
 *       reached only through {@link Http3Datagrams#of} on a message each side already holds (FR-084);</li>
 *   <li><b>size</b> — exactly {@link Http3DatagramChannel#maxPayloadSize()} crosses, one byte more is
 *       refused whole rather than truncated or split (RFC 9221 §3);</li>
 *   <li><b>loss</b> — a lost DATAGRAM frame is released and <b>not</b> retransmitted (RFC 9221 §5), and
 *       the exchange carries on;</li>
 *   <li><b>drop</b> — past {@code maxInboundDatagramsPerStream} the <b>oldest</b> queued datagram goes,
 *       counted, with neither the connection nor the exchange affected (FR-085);</li>
 *   <li><b>close</b> — a connection closed with datagrams still queued recycles every one of them.</li>
 * </ul>
 * Buffer accounting is {@code ByteBufRule}'s across all five: every payload this class allocates crosses
 * an ownership boundary at least once, and one recycled twice or not at all fails the class.
 */
public final class Http3DatagramTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Http3Settings DATAGRAMS_ON = Http3Settings.builder()
		.withDatagramsEnabled(true)
		.build();

	/** Enough that "N each way" is an exchange rather than a round trip, and well inside the queue bound. */
	private static final int DATAGRAMS_EACH_WAY = 8;

	/** Deliberately far below the default 32, so overflowing it costs six datagrams rather than thirty-four. */
	private static final int QUEUE_DEPTH = 4;

	private static final int PAYLOAD_BYTES = 24;

	private final List<HttpRequest> served = new ArrayList<>();

	/** The servlet's answer, withheld so the exchange — and with it both channels — stays live. */
	private final SettablePromise<HttpResponse> answer = new SettablePromise<>();

	private final ServerEvents serverEvents = new ServerEvents();
	private final ClientEvents clientEvents = new ClientEvents();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;
	private @Nullable Promise<HttpResponse> response;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (fixture != null) {
			// Answering releases the request the server is holding; a test that closed first finds the
			// exchange already gone and the response released on the spot.
			if (!answer.isComplete()) answer.set(HttpResponse.ok200().build());
			loop.tickUntilQuiet();
			fixture.close();
		}
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- send and receive

	@Test
	public void datagramsFlowBothWaysBoundToTheOneRequestStream() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		for (int i = 0; i < DATAGRAMS_EACH_WAY; i++) {
			send(exchange.client, i);
			send(exchange.server, mirrored(i));
		}
		drive(() ->
			exchange.client.queuedCount() == DATAGRAMS_EACH_WAY &&
			exchange.server.queuedCount() == DATAGRAMS_EACH_WAY);

		for (int i = 0; i < DATAGRAMS_EACH_WAY; i++) {
			assertPayload(exchange.server.poll(), i);
			assertPayload(exchange.client.poll(), mirrored(i));
		}
		assertNull(exchange.server.poll());
		assertNull(exchange.client.poll());

		assertEquals(DATAGRAMS_EACH_WAY, exchange.client.datagramsSent());
		assertEquals(DATAGRAMS_EACH_WAY, exchange.client.datagramsReceived());
		assertEquals(DATAGRAMS_EACH_WAY, exchange.server.datagramsSent());
		assertEquals(DATAGRAMS_EACH_WAY, exchange.server.datagramsReceived());
		assertEquals(0, exchange.client.datagramsDropped());
		assertEquals(0, exchange.server.datagramsDropped());
	}

	@Test
	public void everyDatagramNamesTheStreamTheExchangeRunsOn() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		send(exchange.client, 0);
		send(exchange.server, 1);
		drive(() -> exchange.client.queuedCount() == 1 && exchange.server.queuedCount() == 1);
		recycle(exchange.client.poll());
		recycle(exchange.server.poll());

		// FR-084 keeps the stream id out of Http3DatagramChannel's API; the FR-062 inspector is where it
		// legitimately appears, and it is what says a datagram reached the exchange it was bound to.
		assertEquals(Set.of(clientEvents.requestStreamId), clientEvents.streamIds);
		assertEquals(Set.of(serverEvents.requestStreamId), serverEvents.streamIds);
		assertEquals(clientEvents.requestStreamId, serverEvents.requestStreamId);
	}

	@Test
	public void theExchangeStillCompletesNormallyAfterCarryingDatagrams() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		send(exchange.client, 0);
		drive(() -> exchange.server.queuedCount() == 1);
		recycle(exchange.server.poll());

		answer.set(HttpResponse.ok200().build());
		HttpResponse received = fixture.await(response);
		assertEquals(200, received.getCode());
		recycle(fixture.await(received.loadBody()));

		// Both channels are closed when the QUIC stream carrying the exchange is, which is once the peer
		// has acknowledged the last byte rather than the moment the response head was read — so this is
		// driven rather than asserted on the spot. It has to happen, and driveUntil fails if it does not.
		drive(() -> !exchange.client.isAvailable() && !exchange.server.isAvailable());
		assertEquals("FR-085: drained when the exchange ends", 0, exchange.client.queuedCount());
		assertEquals(0, exchange.server.queuedCount());
	}

	// ---------------------------------------------------------------- the negotiated size

	@Test
	public void aPayloadAtTheNegotiatedMaximumCrossesAndOneByteMoreIsRefusedWhole() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		long max = exchange.client.maxPayloadSize();
		assertTrue("a negotiated channel reports what it will carry", max > 0);
		assertEquals("both ends negotiated the same bound", max, exchange.server.maxPayloadSize());

		send(exchange.client, 1, (int) max);
		drive(() -> exchange.server.queuedCount() == 1);
		ByteBuf received = exchange.server.poll();
		assertNotNull(received);
		assertEquals("the largest datagram this connection admits crossed whole", max, received.readRemaining());
		recycle(received);

		Http3DatagramException refused = assertThrows(Http3DatagramException.class,
			() -> exchange.client.send(payload(2, (int) max + 1)));
		assertEquals(Http3DatagramException.Reason.OVERSIZE, refused.reason());
		assertEquals(1, clientEvents.refusedOversize);

		drive(() -> true);
		assertEquals("nothing over the bound reached the peer, whole or in part", 0, exchange.server.queuedCount());
		assertEquals("and the connection is unharmed", 1, exchange.server.datagramsReceived());
	}

	// ---------------------------------------------------------------- loss

	@Test
	public void aLostDatagramIsReleasedRatherThanRetransmitted() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);
		StubDatagramNetwork network = fixture.wire().network();
		step();

		network.dropNextFrom(Http3WirePair.CLIENT_ADDRESS, 1);
		send(exchange.client, 0);
		step();
		assertEquals(1, network.droppedCount());
		assertEquals("what was lost never arrived", 0, exchange.server.queuedCount());

		// One per packet, so the ACK of three packets past the lost one declares it lost by RFC 9002
		// §6.1.1's packet threshold rather than by waiting a timer out.
		for (int i = 1; i <= 3; i++) {
			send(exchange.client, i);
			step();
		}
		drive(() -> clientEvents.droppedByLoss == 1);
		// Loss being *known* is where a retransmission would be attempted, so the assertion is made after
		// several more rounds rather than on the tick that declared it.
		for (int i = 0; i < 4; i++) {
			step();
		}

		assertEquals("RFC 9221 §5: released, never resent", 3, exchange.server.queuedCount());
		for (int i = 1; i <= 3; i++) {
			assertPayload(exchange.server.poll(), i);
		}
		assertNull(exchange.server.poll());
		assertEquals(4, clientEvents.sent);
		assertEquals(3, serverEvents.received);
	}

	@Test
	public void aLossLeavesTheExchangeAndTheConnectionIntact() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);
		StubDatagramNetwork network = fixture.wire().network();
		step();

		network.dropNextFrom(Http3WirePair.CLIENT_ADDRESS, 1);
		send(exchange.client, 0);
		step();

		send(exchange.client, 1);
		drive(() -> exchange.server.queuedCount() == 1);
		assertPayload(exchange.server.poll(), 1);

		answer.set(HttpResponse.ok200().build());
		HttpResponse received = fixture.await(response);
		assertEquals("an unreliable channel losing one is not the exchange failing", 200, received.getCode());
		recycle(fixture.await(received.loadBody()));
	}

	// ---------------------------------------------------------------- the inbound queue

	@Test
	public void pastTheQueueBoundTheOldestIsDroppedAndCounted() {
		Http3Settings shallowQueue = Http3Settings.builder()
			.withDatagramsEnabled(true)
			.withMaxInboundDatagramsPerStream(QUEUE_DEPTH)
			.build();
		Exchange exchange = start(shallowQueue, DATAGRAMS_ON);

		int overflowing = QUEUE_DEPTH + 2;
		for (int i = 0; i < overflowing; i++) {
			send(exchange.server, i);
			step();
		}
		drive(() -> clientEvents.received == overflowing);

		assertEquals("never unbounded growth", QUEUE_DEPTH, exchange.client.queuedCount());
		assertEquals(overflowing - QUEUE_DEPTH, exchange.client.datagramsDropped());
		assertEquals(overflowing - QUEUE_DEPTH, clientEvents.droppedByQueue);

		// FR-085: the OLDEST went, so what is left is the newest QUEUE_DEPTH of them, still in order.
		for (int i = overflowing - QUEUE_DEPTH; i < overflowing; i++) {
			assertPayload(exchange.client.poll(), i);
		}
		assertNull(exchange.client.poll());
	}

	@Test
	public void afullQueueClosesNeitherTheExchangeNorTheConnection() {
		Http3Settings shallowQueue = Http3Settings.builder()
			.withDatagramsEnabled(true)
			.withMaxInboundDatagramsPerStream(QUEUE_DEPTH)
			.build();
		Exchange exchange = start(shallowQueue, DATAGRAMS_ON);

		for (int i = 0; i < QUEUE_DEPTH + 2; i++) {
			send(exchange.server, i);
			step();
		}
		drive(() -> clientEvents.received == QUEUE_DEPTH + 2);

		assertEquals("a reader falling behind is not a protocol error", 0, clientEvents.connectionErrors);
		assertTrue("and the channel still carries", exchange.client.isAvailable());

		answer.set(HttpResponse.ok200().build());
		HttpResponse received = fixture.await(response);
		assertEquals(200, received.getCode());
		recycle(fixture.await(received.loadBody()));
	}

	// ---------------------------------------------------------------- close

	@Test
	public void closingWithDatagramsQueuedRecyclesEveryOneOfThem() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		for (int i = 0; i < DATAGRAMS_EACH_WAY; i++) {
			send(exchange.server, i);
		}
		drive(() -> exchange.client.queuedCount() == DATAGRAMS_EACH_WAY);

		fixture.client().close();
		loop.tickUntilQuiet();

		// That the queue was drained is asserted here; that every buffer in it was recycled exactly once
		// is ByteBufRule's, which sees every payload this class ever allocated.
		assertEquals(0, exchange.client.queuedCount());
		assertNull(exchange.client.poll());
		assertFalse(exchange.client.isAvailable());
	}

	@Test
	public void aSendAfterTheExchangeHasEndedIsRefusedWithThePayloadRecycled() {
		Exchange exchange = start(DATAGRAMS_ON, DATAGRAMS_ON);

		answer.set(HttpResponse.ok200().build());
		HttpResponse received = fixture.await(response);
		recycle(fixture.await(received.loadBody()));
		drive(() -> !exchange.server.isAvailable());

		Http3DatagramException refused = assertThrows(Http3DatagramException.class,
			() -> exchange.server.send(payload(0, PAYLOAD_BYTES)));
		assertEquals(Http3DatagramException.Reason.EXCHANGE_ENDED, refused.reason());
	}

	// ---------------------------------------------------------------- off by default (SC-011)

	@Test
	public void aDefaultExchangeHasNoDatagramChannelOnEitherSide() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> {
				served.add(request);
				return answer;
			})
			.withServerInspector(serverEvents)
			.withClientInspector(clientEvents)
			.start();

		HttpRequest request = HttpRequest.get(url(HOST, "/datagrams")).build();
		response = fixture.client().request(request);
		fixture.wire().driveUntil(() -> !served.isEmpty());

		assertNull("FR-086: off by default, so no channel and no queue exists at all",
			Http3Datagrams.of(request));
		assertNull(Http3Datagrams.of(served.get(0)));
	}

	// ---------------------------------------------------------------- SI-6

	/**
	 * Structural rather than sampled, exactly as {@code Http3QpackInspectorTest} argues: a parameter list
	 * that admits no {@code String}, no {@code byte[]} and no {@code ByteBuf} cannot carry a payload byte,
	 * whatever a future call site passes it.
	 */
	@Test
	public void noDatagramCounterCanCarryAnythingButANumber() {
		assertParametersAreNumbers(Http3Server.Inspector.class, Http3Server.class);
		assertParametersAreNumbers(Http3Client.Inspector.class, Http3Client.class);
	}

	@Test
	public void everyDatagramEventAConnectionReportsIsOnBothInspectors() {
		Set<String> reported = datagramMethodNames(Http3EventListener.class);
		assertEquals("sent, received, dropped by queue, dropped by loss, refused oversize",
			5, reported.size());
		assertEquals(reported, datagramMethodNames(Http3Server.Inspector.class));
		assertEquals(reported, datagramMethodNames(Http3Client.Inspector.class));
	}

	private static Set<String> datagramMethodNames(Class<?> type) {
		Set<String> names = new TreeSet<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().startsWith("onDatagram")) names.add(method.getName());
		}
		return names;
	}

	private static void assertParametersAreNumbers(Class<?> inspector, Class<?> component) {
		int found = 0;
		for (Method method : inspector.getDeclaredMethods()) {
			if (!method.getName().startsWith("onDatagram")) continue;
			found++;
			Class<?>[] parameters = method.getParameterTypes();
			assertEquals(component, parameters[0]);
			for (int i = 1; i < parameters.length; i++) {
				Class<?> parameter = parameters[i];
				assertTrue(method + " carries " + parameter.getSimpleName(),
					parameter.isPrimitive() || parameter.isEnum());
			}
			assertTrue(method + " must not break an existing implementation", method.isDefault());
		}
		assertEquals(5, found);
	}

	// ---------------------------------------------------------------- the fixture

	/** One channel per end of the same exchange — the only two handles this whole class works through. */
	private record Exchange(Http3DatagramChannel client, Http3DatagramChannel server) {}

	/**
	 * Dials, issues one request, and returns both ends' channels once the exchange is live on both. The
	 * servlet withholds its answer, so the exchange — and with it both channels — stays open until a test
	 * ends it.
	 */
	private Exchange start(Http3Settings clientSettings, Http3Settings serverSettings) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> {
				served.add(request);
				return answer;
			})
			.withClientSettings(clientSettings)
			.withServerSettings(serverSettings)
			.withServerInspector(serverEvents)
			.withClientInspector(clientEvents)
			.start();

		HttpRequest request = HttpRequest.get(url(HOST, "/datagrams")).build();
		response = fixture.client().request(request);
		fixture.wire().driveUntil(() -> !served.isEmpty());

		Http3DatagramChannel client = Http3Datagrams.of(request);
		assertNotNull("the client reaches the channel through the request it issued", client);
		Http3DatagramChannel server = Http3Datagrams.of(served.get(0));
		assertNotNull("a servlet reaches the channel through the request it is serving", server);
		drive(() -> client.isAvailable() && server.isAvailable());
		return new Exchange(client, server);
	}

	private void drive(java.util.function.BooleanSupplier done) {
		fixture.wire().driveUntil(done);
	}

	/** One clock tick and one delivery round — enough to put the next send in a packet of its own. */
	private void step() {
		fixture.wire().advance(1);
	}

	private static void send(Http3DatagramChannel channel, int mark) {
		send(channel, mark, PAYLOAD_BYTES);
	}

	private static void send(Http3DatagramChannel channel, int mark, int length) {
		try {
			channel.send(payload(mark, length));
		} catch (Http3DatagramException e) {
			throw new AssertionError("the send should have been carried", e);
		}
	}

	/** A payload whose every byte is {@code mark}, so a delivered one identifies which send it was. */
	private static ByteBuf payload(int mark, int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) mark);
		}
		return buf;
	}

	/** The server's marks, kept clear of the client's so a misrouted datagram cannot pass for the right one. */
	private static int mirrored(int mark) {
		return mark + 100;
	}

	private static void assertPayload(@Nullable ByteBuf buf, int mark) {
		assertNotNull("a datagram was expected here", buf);
		assertEquals(PAYLOAD_BYTES, buf.readRemaining());
		for (int i = 0; i < PAYLOAD_BYTES; i++) {
			assertEquals("byte " + i + " of the payload marked " + mark, (byte) mark, buf.array()[buf.head() + i]);
		}
		buf.recycle();
	}

	private static void recycle(@Nullable ByteBuf buf) {
		assertNotNull(buf);
		buf.recycle();
	}

	// ---------------------------------------------------------------- the two recorders

	private static final class ServerEvents implements Http3Server.Inspector {
		private long requestStreamId = -1;
		private final Set<Long> streamIds = new TreeSet<>();

		private long sent;
		private long received;
		private long droppedByQueue;
		private long droppedByLoss;
		private long refusedOversize;

		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {
			requestStreamId = streamId;
		}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes) {}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {}

		@Override
		public void onDatagramSent(Http3Server server, long streamId, int payloadBytes, long sent) {
			streamIds.add(streamId);
			this.sent = sent;
		}

		@Override
		public void onDatagramReceived(Http3Server server, long streamId, int payloadBytes, long received) {
			streamIds.add(streamId);
			this.received = received;
		}

		@Override
		public void onDatagramDroppedByQueue(Http3Server server, long streamId, long droppedByQueue) {
			this.droppedByQueue = droppedByQueue;
		}

		@Override
		public void onDatagramDroppedByLoss(Http3Server server, long droppedByLoss) {
			this.droppedByLoss = droppedByLoss;
		}

		@Override
		public void onDatagramRefusedOversize(
			Http3Server server, long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize
		) {
			this.refusedOversize = refusedOversize;
		}
	}

	private static final class ClientEvents implements Http3Client.Inspector {
		private long requestStreamId = -1;
		private final Set<Long> streamIds = new TreeSet<>();

		private long connectionErrors;
		private long sent;
		private long received;
		private long droppedByQueue;
		private long droppedByLoss;
		private long refusedOversize;

		@Override
		public <T extends Http3Client.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Client client, long streamId, HttpMethod method) {
			requestStreamId = streamId;
		}

		@Override
		public void onRequestCompleted(
			Http3Client client, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes) {}

		@Override
		public void onStreamReset(Http3Client client, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Client client, long errorCode) {
			connectionErrors++;
		}

		@Override
		public void onFrameDiscarded(Http3Client client, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Client client, GoAwayDirection direction, long id) {}

		@Override
		public void onRequestQueued(Http3Client client, int queueDepth) {}

		@Override
		public void onRequestDequeued(Http3Client client, int queueDepth) {}

		@Override
		public void onDatagramSent(Http3Client client, long streamId, int payloadBytes, long sent) {
			streamIds.add(streamId);
			this.sent = sent;
		}

		@Override
		public void onDatagramReceived(Http3Client client, long streamId, int payloadBytes, long received) {
			streamIds.add(streamId);
			this.received = received;
		}

		@Override
		public void onDatagramDroppedByQueue(Http3Client client, long streamId, long droppedByQueue) {
			this.droppedByQueue = droppedByQueue;
		}

		@Override
		public void onDatagramDroppedByLoss(Http3Client client, long droppedByLoss) {
			this.droppedByLoss = droppedByLoss;
		}

		@Override
		public void onDatagramRefusedOversize(
			Http3Client client, long streamId, int payloadBytes, long maxPayloadBytes, long refusedOversize
		) {
			this.refusedOversize = refusedOversize;
		}
	}
}
