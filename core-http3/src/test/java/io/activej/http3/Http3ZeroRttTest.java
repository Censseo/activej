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
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Connection.ZeroRttOutcome;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.StubDatagramNetwork;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionCache;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.OTHER_HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T067, SC-005 — the end-to-end 0-RTT exchange, asserted on the <b>raw bytes</b> of the fabric rather
 * than on internal state: a real {@link Http3Client} resumes a real {@link Http3Server}, and its
 * request leaves in a 0-RTT packet (long header, type {@code 0x01}) before one byte of the server's
 * handshake flight has arrived.
 *
 * <h2>What is proved, and how</h2>
 * The tap on {@link StubDatagramNetwork} sees every datagram at send and at delivery, and every long
 * header's <i>type</i> bits are outside the header-protection mask (RFC 9001 §5.4.1), so a datagram's
 * packet types can be read straight off the wire without a key. Three claims follow from that:
 * <ol>
 *   <li>a client→server datagram of the resumed exchange carries a type-{@code 0x01} packet;</li>
 *   <li>those packets carry <b>more bytes than the request's path</b>, and left before any server
 *       datagram of that exchange arrived — so what rode them is the request, not a bare probe;</li>
 *   <li>the servlet is invoked <b>before</b> the client's {@code Finished} reaches the server, which is
 *       the round trip 0-RTT exists to save.</li>
 * </ol>
 *
 * <h2>Where this narrows SC-005, and why</h2>
 * SC-005 words the third claim as "the response was <b>written</b> before the client's {@code Finished}
 * was processed". What holds today is one step weaker: the request is <i>served</i> that early, but the
 * response bytes wait for the handshake, because a server processing 0-RTT has been granted no send
 * credit — {@code QuicConnection.earlyTransportParameters()} answers
 * {@code TransportParameterValidation.WITHOUT_SEND_CREDIT} on the server side, deliberately, since RFC
 * 9000 §7.4.1's remembered limits are a <i>client</i>'s to obey and a server has been promised nothing
 * until the handshake completes. The exchange still saves its round trip on the request half; the
 * response half is a `core-quic` decision, not an HTTP/3 one, and closing it is not this phase's.
 *
 * <h2>What is deliberately absent</h2>
 * Rejection is US4's (Phase 6) and replay refusal US5's (Phase 7). Nothing here rejects: the server
 * accepts every ticket it issued, and a request accepted from early data reaches the servlet with no
 * {@code Early-Data: 1} field and no {@code 425 (Too Early)} filter in front of it.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-17.2.3">RFC 9000 §17.2.3 — 0-RTT packet</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6">RFC 9001 §4.6 — 0-RTT</a>
 */
public final class Http3ZeroRttTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** RFC 9000 §17.2's long-header type field, the two bits a mask never covers. */
	private static final int INITIAL = 0x00;
	private static final int ZERO_RTT = 0x01;
	private static final int HANDSHAKE = 0x02;

	/** Not a long-header type: what {@link #packetTypesOf} reports for a 1-RTT (short header) packet. */
	private static final int ONE_RTT = -1;

	/**
	 * Long enough that a 0-RTT packet carrying it cannot be mistaken for the control-stream preamble,
	 * which is under 30 bytes. All-lowercase, so RFC 7541 Huffman shrinks it to 5 bits per character —
	 * the assertion is against the <b>compressed</b> floor, not the literal length.
	 */
	private static final String LONG_PATH = "/" + "abcdefghij".repeat(48);

	private static final int LONG_PATH_HUFFMAN_FLOOR = LONG_PATH.length() * 5 / 8;

	private static final Http3Settings ZERO_RTT_ON = Http3Settings.builder()
		.withZeroRttEnabled(true)
		.withMaxConnections(1)
		.build();

	private static final Http3Settings ZERO_RTT_OFF = Http3Settings.builder()
		.withMaxConnections(1)
		.build();

	private ManualEventloop loop;
	private QuicSessionCache cache;
	private @Nullable Http3ClientFixture fixture;

	/** Every datagram the fabric carried since the last {@link #startRecording()}, in order. */
	private final List<Observed> observed = new ArrayList<>();

	/** Paths the servlet was invoked for while no client Handshake datagram had yet been delivered. */
	private final List<String> servedBeforeClientFinished = new ArrayList<>();

	private boolean clientHandshakeDelivered;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		cache = InMemoryQuicSessionCache.create(8, loop::currentTimeMillis);
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- the exchange itself

	@Test
	public void theResumedRequestTravelsInAZeroRttPacketAndIsServedBeforeTheClientsFinished() {
		start(ZERO_RTT_ON, ZERO_RTT_ON);

		exchange(HOST, "/first");
		assertTrue("the server issued a ticket and the client stored it", client().sessionTicketsStored() >= 1);
		assertEquals("nothing to resume on a first connection", 0, client().zeroRttAttempted());

		// The pool holds one connection, and its one entry is idle, so this evicts the one to HOST — which
		// is what makes the third exchange dial HOST again rather than reuse what is already open.
		exchange(OTHER_HOST, "/second");
		assertEquals(1, client().connectionsEvicted());

		startRecording();
		exchange(HOST, LONG_PATH);

		assertEquals("the stored ticket was offered on the redial", 1, client().sessionTicketsOffered());
		assertEquals("early data went out with it", 1, client().zeroRttAttempted());
		assertEquals("and the server took it", 1, client().zeroRttAccepted());
		assertEquals(0, client().zeroRttRejected());
		assertEquals(1, server().sessionsResumed());
		assertEquals(1, server().zeroRttAccepted());

		// (1) A 0-RTT packet really crossed the wire, read off the unprotected type bits.
		List<Observed> earlyClientDatagrams = theOpeningFlightOfTheResumedDial();
		assertTrue("no 0-RTT packet was sent: " + observed,
			earlyClientDatagrams.stream().anyMatch(datagram -> datagram.types.contains(ZERO_RTT)));

		// (2) ... carrying more than the request path's compressed size, so it is the request that rode
		// it rather than the control-stream preamble alone.
		int earlyBytes = earlyClientDatagrams.stream()
			.filter(datagram -> datagram.types.contains(ZERO_RTT))
			.mapToInt(datagram -> datagram.size)
			.sum();
		assertTrue("only " + earlyBytes + " bytes of early data, less than the request path's " +
				   LONG_PATH_HUFFMAN_FLOOR + " compressed bytes",
			earlyBytes > LONG_PATH_HUFFMAN_FLOOR);

		// ... and the very first datagram of the exchange is still the Initial carrying the ClientHello:
		// RFC 9001 §4.6 puts 0-RTT after it, never instead of it.
		assertTrue(earlyClientDatagrams.get(0).types.contains(INITIAL));

		// (3) The servlet ran a full round trip early — before the client had even seen the ServerHello
		// it would have needed in order to send its Finished.
		assertEquals(List.of(LONG_PATH), servedBeforeClientFinished);
	}

	/** FR-052, RFC 9001 §4.6.1: a server decrypts 0-RTT and answers in 1-RTT; it never sends one. */
	@Test
	public void theServerNeverSendsAZeroRttPacket() {
		start(ZERO_RTT_ON, ZERO_RTT_ON);

		startRecording();
		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, LONG_PATH);

		assertEquals(1, client().zeroRttAccepted());
		assertTrue("at least one 0-RTT packet must have been sent for this to mean anything",
			observed.stream().anyMatch(datagram -> datagram.fromClient && datagram.types.contains(ZERO_RTT)));
		assertFalse("a server sent a 0-RTT packet: " + observed,
			observed.stream().anyMatch(datagram -> !datagram.fromClient && datagram.types.contains(ZERO_RTT)));
	}

	/**
	 * SC-011: with {@code zeroRttEnabled} off — the default — the same three exchanges put no 0-RTT
	 * packet on the wire at all, and every resumption counter stays 0.
	 */
	@Test
	public void withZeroRttOffNothingLeavesAtZeroRtt() {
		start(ZERO_RTT_OFF, ZERO_RTT_OFF);

		startRecording();
		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, LONG_PATH);

		assertFalse("a 0-RTT packet crossed the wire with 0-RTT off: " + observed,
			observed.stream().anyMatch(datagram -> datagram.types.contains(ZERO_RTT)));
		assertEquals(0, client().sessionTicketsOffered());
		assertEquals(0, client().sessionTicketsStored());
		assertEquals(0, client().zeroRttAttempted());
		assertEquals(0, client().zeroRttAccepted());
		assertEquals(0, client().zeroRttRejected());
		assertEquals(0, server().sessionTicketsIssued());
		assertEquals(0, server().sessionsResumed());
		assertEquals(0, server().zeroRttAccepted());
		assertTrue("nothing was served before a Finished, because nothing was served early",
			servedBeforeClientFinished.isEmpty());
	}

	/**
	 * A dial that hands its connection over before the handshake finishes has given up its only way of
	 * reporting a handshake that then fails — so the connection has to report itself gone, or the pool
	 * keeps handing out a dead entry to every later request for that authority.
	 */
	@Test
	public void aHandshakeThatFailsAfterTheEarlyHandoverLeavesNothingUsableInThePool() {
		start(ZERO_RTT_ON, ZERO_RTT_ON);

		exchange(HOST, "/first");
		assertTrue(client().sessionTicketsStored() >= 1);
		exchange(OTHER_HOST, "/second");

		// The server is gone; its address is unbound, so the redial's Initial reaches nobody.
		server().close();
		fixture.wire().pump();
		loop.tickUntilQuiet();

		Promise<HttpResponse> refused = client().request(HttpRequest.get(url(HOST, "/third")).build());
		assertEquals("the ticket was still offered", 1, client().zeroRttAttempted());
		// Past the handshake deadline: nothing answers, so the connection gives up on its own.
		fixture.wire().advance(QuicConnectionSettings.create().handshakeTimeoutMillis() + 1);
		fixture.awaitException(refused);
		assertEquals("a handshake that failed took no early-data decision", 0, client().zeroRttAccepted());
		assertEquals(0, client().zeroRttRejected());

		// The next attempt for that authority finds nothing usable pooled, so it dials rather than failing
		// on a connection that is already gone — and the entry it drops leaves the pool empty.
		Promise<HttpResponse> next = client().request(HttpRequest.get(url(HOST, "/fourth")).build());
		fixture.wire().advance(QuicConnectionSettings.create().handshakeTimeoutMillis() + 1);
		fixture.awaitException(next);
		assertEquals("a connection that died handshaking was left in the pool", 0, client().connectionCount());
		assertEquals(0, client().retiredConnectionCount());
	}

	/** T085: the counters the inspectors publish agree with the ones the components hold. */
	@Test
	public void theInspectorsReportTheSameResumptionCountersTheComponentsHold() {
		RecordingServerInspector serverInspector = new RecordingServerInspector();
		RecordingClientInspector clientInspector = new RecordingClientInspector();
		fixture = fixture(ZERO_RTT_ON, ZERO_RTT_ON)
			.withServerInspector(serverInspector)
			.withClientInspector(clientInspector)
			.start();
		tap();

		exchange(HOST, "/first");
		exchange(OTHER_HOST, "/second");
		exchange(HOST, LONG_PATH);

		assertEquals(client().sessionTicketsOffered(), clientInspector.ticketsOffered);
		assertEquals(client().sessionTicketsStored(), clientInspector.ticketsStored);
		assertEquals(client().zeroRttAttempted(), clientInspector.attempted);
		assertEquals(List.of(ZeroRttOutcome.ACCEPTED), clientInspector.outcomes);
		assertEquals(client().zeroRttAccepted(), clientInspector.accepted);
		assertEquals(client().zeroRttRejected(), clientInspector.rejected);

		assertEquals(server().sessionTicketsIssued(), serverInspector.ticketsIssued);
		assertNotEquals("a server with 0-RTT on issues tickets", 0, serverInspector.ticketsIssued);
		assertEquals(server().sessionsResumed(), serverInspector.sessionsResumed);
		assertEquals(server().zeroRttAccepted(), serverInspector.zeroRttAccepted);
		assertEquals(List.of(Boolean.TRUE), serverInspector.earlyDataDecisions);
	}

	// ---------------------------------------------------------------- harness

	private Http3ClientFixture fixture(Http3Settings serverSettings, Http3Settings clientSettings) {
		return new Http3ClientFixture(loop)
			.withServlet(request -> {
				String path = request.getPath();
				if (!clientHandshakeDelivered) servedBeforeClientFinished.add(path);
				return HttpResponse.ok200().withBody(path.getBytes(UTF_8)).toPromise();
			})
			.withServerSettings(serverSettings)
			.withClientSettings(clientSettings)
			.withSessionCache(cache);
	}

	private void start(Http3Settings serverSettings, Http3Settings clientSettings) {
		fixture = fixture(serverSettings, clientSettings).start();
		tap();
	}

	/**
	 * Installs the wire tap. Safe to do after {@code start()}: the fixture builds both endpoints and
	 * dials nothing, so no datagram exists until a request is issued.
	 */
	private void tap() {
		fixture.wire().network().observe((event, from, to, datagram) -> {
			boolean fromClient = from.getPort() == Http3WirePair.CLIENT_ADDRESS.getPort();
			List<Integer> types = packetTypesOf(datagram);
			if (event == StubDatagramNetwork.Event.DELIVERED) {
				// The client's first Handshake-level packet is the one carrying its Finished; nothing
				// else it sends at that level exists in this implementation.
				if (fromClient && types.contains(HANDSHAKE)) clientHandshakeDelivered = true;
				return;
			}
			observed.add(new Observed(fromClient, types, datagram.length));
		});
	}

	/** Forgets everything recorded so far, so an assertion can speak about the exchange that follows. */
	private void startRecording() {
		observed.clear();
		servedBeforeClientFinished.clear();
		clientHandshakeDelivered = false;
	}

	/**
	 * The client's opening flight on the connection being dialled: from its Initial to the server's
	 * first long-header answer.
	 * <p>
	 * Bounded by long headers on purpose. A pooled connection to another authority is still exchanging
	 * 1-RTT acknowledgements while this dial happens, and those datagrams belong to neither end of the
	 * claim; a long header at this point can only be the new connection's, whose handshake is the only
	 * one running. Everything in the window was therefore sent before the server had said anything the
	 * client could have heard — on the strength of the stored ticket alone.
	 */
	private List<Observed> theOpeningFlightOfTheResumedDial() {
		List<Observed> flight = new ArrayList<>();
		boolean started = false;
		for (Observed datagram : observed) {
			if (!started) {
				if (!datagram.fromClient || !datagram.types.contains(INITIAL)) continue;
				started = true;
			} else if (!datagram.fromClient &&
					   (datagram.types.contains(INITIAL) || datagram.types.contains(HANDSHAKE))) {
				break;
			}
			if (datagram.fromClient) flight.add(datagram);
		}
		assertFalse("the dial never sent an Initial: " + observed, flight.isEmpty());
		return flight;
	}

	private void exchange(String host, String path) {
		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(host, path)).build()));
		ByteBuf body = fixture.await(response.loadBody());
		try {
			assertEquals(path, body.getString(UTF_8));
		} finally {
			body.recycle();
		}
	}

	private Http3Client client() {
		return fixture.client();
	}

	private Http3Server server() {
		return fixture.server();
	}

	private record Observed(boolean fromClient, List<Integer> types, int size) {
		@Override
		public String toString() {
			return (fromClient ? "C->S " : "S->C ") + size + "B " + types;
		}
	}

	// ---------------------------------------------------------------- reading packet types off the wire

	/**
	 * The packet types coalesced into one UDP datagram, in order, {@link #ONE_RTT} for a short header.
	 * <p>
	 * Only unprotected fields are read: RFC 9001 §5.4.1's header-protection mask covers a long header's
	 * low <b>four</b> bits, leaving the header form, the fixed bit and the two type bits in the clear,
	 * and the Length field is outside the protected region entirely. Nothing here needs a key, which is
	 * exactly why this is a fair reading of what a passive observer on the path would see.
	 */
	private static List<Integer> packetTypesOf(byte[] datagram) {
		List<Integer> types = new ArrayList<>();
		int position = 0;
		while (position < datagram.length) {
			int first = datagram[position] & 0xFF;
			// RFC 9000 §14.1 pads a client Initial datagram with zero bytes past the last packet.
			if (first == 0) break;
			if ((first & 0x80) == 0) {
				types.add(ONE_RTT);
				break;
			}
			int type = (first >> 4) & 0x03;
			types.add(type);
			// Retry (0x03) carries no Length field, and nothing may follow it.
			if (type == 0x03) break;
			position += 1 + 4;
			position += 1 + (datagram[position] & 0xFF);
			position += 1 + (datagram[position] & 0xFF);
			if (type == INITIAL) {
				long[] token = varInt(datagram, position);
				position = (int) (token[1] + token[0]);
			}
			long[] length = varInt(datagram, position);
			position = (int) (length[1] + length[0]);
		}
		return types;
	}

	/** RFC 9000 §16. @return {@code {value, the position just past the varint}} */
	private static long[] varInt(byte[] bytes, int position) {
		int length = 1 << ((bytes[position] & 0xFF) >> 6);
		long value = bytes[position] & 0x3F;
		for (int i = 1; i < length; i++) {
			value = (value << 8) | (bytes[position + i] & 0xFF);
		}
		return new long[]{value, position + length};
	}

	// ---------------------------------------------------------------- inspectors

	private static final class RecordingServerInspector implements Http3Server.Inspector {
		private long ticketsIssued;
		private long sessionsResumed;
		private long zeroRttAccepted;
		private final List<Boolean> earlyDataDecisions = new ArrayList<>();

		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {}

		@Override
		public void onSessionTicketsIssued(Http3Server server, int tickets, long issued) {
			assertTrue(tickets > 0);
			ticketsIssued = issued;
		}

		@Override
		public void onSessionResumed(Http3Server server, boolean earlyDataAccepted, long resumed, long accepted) {
			earlyDataDecisions.add(earlyDataAccepted);
			sessionsResumed = resumed;
			zeroRttAccepted = accepted;
		}
	}

	private static final class RecordingClientInspector implements Http3Client.Inspector {
		private long ticketsOffered;
		private long ticketsStored;
		private long attempted;
		private long accepted;
		private long rejected;
		private final List<ZeroRttOutcome> outcomes = new ArrayList<>();

		@Override
		public <T extends Http3Client.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Client client, long streamId, HttpMethod method) {}

		@Override
		public void onRequestCompleted(
			Http3Client client, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {}

		@Override
		public void onStreamReset(Http3Client client, long streamId, long errorCode) {}

		@Override
		public void onConnectionError(Http3Client client, long errorCode) {}

		@Override
		public void onFrameDiscarded(Http3Client client, long frameType, long declaredLength) {}

		@Override
		public void onGoAway(Http3Client client, GoAwayDirection direction, long id) {}

		@Override
		public void onRequestQueued(Http3Client client, int queueDepth) {}

		@Override
		public void onRequestDequeued(Http3Client client, int queueDepth) {}

		@Override
		public void onSessionTicketOffered(Http3Client client, long offered) {
			ticketsOffered = offered;
		}

		@Override
		public void onSessionTicketStored(Http3Client client, long stored) {
			ticketsStored = stored;
		}

		@Override
		public void onZeroRttAttempted(Http3Client client, long zeroRttAttempted) {
			attempted = zeroRttAttempted;
		}

		@Override
		public void onZeroRttDecision(
			Http3Client client, ZeroRttOutcome outcome, long zeroRttAccepted, long zeroRttRejected
		) {
			outcomes.add(outcome);
			accepted = zeroRttAccepted;
			rejected = zeroRttRejected;
		}
	}
}
