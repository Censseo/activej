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

import io.activej.bytebuf.ByteBufs;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestPeer.Response;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamResetException;
import io.activej.quic.stream.QuicStreamStopSendingException;
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

import static io.activej.http3.frame.Http3StreamType.CONTROL;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.goAwayFrame;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T087 / FR-019, FR-020: a graceful shutdown announces the identifier beyond which nothing will be
 * processed, lets what is at or below it finish, and rejects what is above it <b>retryably</b>.
 * <p>
 * Two endpoints are under test, because the requirement has two halves. The sending half is a real
 * {@link Http3Server} shutting down while it serves a request, watched by the real
 * {@link Http3Connection} inside {@link Http3TestPeer} — so the identifier asserted here is one that
 * genuinely crossed the wire and was decoded by a peer. The receiving half is a client-side
 * {@link Http3Connection} driven by a hand-written server peer, which is the only way to have a request
 * stream <i>above</i> an announced identifier at the moment it arrives.
 */
public final class Http3GoAwayTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The request the server takes before it announces its shutdown, and must therefore finish. */
	private static final String SLOW = "/slow";

	/** The request the peer opens after the announcement, which must never reach the servlet. */
	private static final String LATE = "/late";

	/** Servlet answers, resolved by the test — a request is being served exactly while its entry is unset. */
	private final Map<String, SettablePromise<HttpResponse>> pending = new LinkedHashMap<>();

	/** Whatever a hand-written peer sends us and nothing reads; recycled in {@code tearDown}. */
	private final ByteBufs received = new ByteBufs();

	/** Every request stream a hand-built server-side connection took, so a test can assert it took none. */
	private final List<Http3RequestStream> accepted = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3TestPeer peer;
	private @Nullable Http3Connection clientH3;
	private @Nullable Http3Connection serverH3;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		received.recycle();
		loop.close();
	}

	// ---------------------------------------------------------------- the sending half (US7 §1, §5)

	@Test
	public void closingTheServerAnnouncesPastTheLastStreamItWillProcessAndDrainsIt() {
		startServer();
		Promise<Response> slow = peer.request(Http3TestBytes.requestFields("GET", SLOW), null);
		wire.driveUntil(() -> pending.containsKey(SLOW));
		assertEquals(1, server.activeRequests());

		server.close();
		wire.driveUntil(() -> peer.connection().goAwayReceivedId() != Http3Connection.NO_GOAWAY_ID);

		// FR-019, RFC 9114 §5.2: the identifier is exclusive — "requests with the indicated identifier or
		// greater are rejected" — so the server that took stream 0 before the shutdown started announces the
		// *next* client-initiated bidirectional stream id, 4 (RFC 9000 §2.1). Stream 0 stays below it, which
		// is what says it is still owed its answer.
		assertEquals(4, peer.connection().goAwayReceivedId());
		assertEquals(State.GOING_AWAY, peer.connection().state());
		assertFalse("US7 §1: the request below the identifier is still being served", slow.isComplete());
		assertEquals(1, server.activeRequests());

		answer(SLOW);
		Response response = await(slow);
		assertEquals(200, response.status());
		assertEquals(SLOW.substring(1), response.bodyString());

		// ... and only once it has finished does the QUIC connection go.
		wire.driveUntil(() -> peer.connection().state() == State.CLOSED);
		assertEquals(0, server.activeRequests());
	}

	@Test
	public void aRequestStreamOpenedAfterTheAnnouncementIsRejectedWithoutReachingTheServlet() {
		startServer();
		Promise<Response> slow = peer.request(Http3TestBytes.requestFields("GET", SLOW), null);
		wire.driveUntil(() -> pending.containsKey(SLOW));

		server.close();
		wire.driveUntil(() -> peer.connection().goAwayReceivedId() != Http3Connection.NO_GOAWAY_ID);

		Promise<Response> late = peer.request(Http3TestBytes.requestFields("GET", LATE), null);
		wire.driveUntil(late::isComplete);

		// US7 §5: the stream is rejected, not the connection — and the servlet never saw it.
		assertTrue("the late request failed: " + late, late.isException());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, applicationErrorCodeOf(late.getException()));
		assertFalse("FR-020: the servlet is never invoked for a rejected stream", pending.containsKey(LATE));
		assertEquals(0, server.requestsServed());

		// The rejection did not disturb what was already being served.
		answer(SLOW);
		assertEquals(SLOW.substring(1), await(slow).bodyString());
		assertEquals(1, server.requestsServed());
	}

	@Test
	public void closingAServerWithNothingInFlightAnnouncesAndClosesAtOnce() {
		startServer();
		Promise<Response> served = peer.request(Http3TestBytes.requestFields("GET", SLOW), null);
		wire.driveUntil(() -> pending.containsKey(SLOW));
		answer(SLOW);
		await(served);

		server.close();
		wire.driveUntil(() -> peer.connection().state() == State.CLOSED);

		// Stream 0 was taken and answered, so the exclusive identifier is still the one past it.
		assertEquals(4, peer.connection().goAwayReceivedId());
		assertEquals(0, server.activeRequests());
		assertTrue(server.isClosed());
	}

	/**
	 * The zero-requests-served boundary of RFC 9114 §5.2's <b>exclusive</b> identifier, which is the one
	 * case where announcing the last stream taken and announcing one past it disagree about whether stream
	 * 0 was processed.
	 * <p>
	 * The peer here is a hand-built server-side {@link Http3Connection} rather than an {@link Http3Server}
	 * because the announcement has to happen <b>without</b> the endpoint going with it: a server with
	 * nothing in flight closes as soon as it has announced, which would race the request this test opens
	 * afterwards against the connection disappearing. Announcing directly makes the ordering exact — the
	 * identifier is decoded by the peer before the stream is opened, so the connection genuinely took
	 * nothing before it announced.
	 */
	@Test
	public void aServerThatTookNothingAnnouncesTheFirstRequestStreamIdAndRejectsIt() {
		startServerConnection();
		wire.driveUntil(() -> peer.connection().state() == State.READY);

		Promise<Void> announced = serverH3.goAway();
		wire.driveUntil(() -> peer.connection().goAwayReceivedId() != Http3Connection.NO_GOAWAY_ID);

		// Nothing was ever taken, so the identifier is the *first* client-initiated bidirectional stream id
		// (RFC 9000 §2.1) — which, read exclusively, says that no request on this connection was processed.
		assertTrue("the announcement reached the transport: " + announced, announced.isResult());
		assertEquals(0, serverH3.goAwaySentId());
		assertEquals(0, peer.connection().goAwayReceivedId());

		// ... and the peer's very first request stream *is* that identifier, so it is rejected rather than
		// served — the boundary the inclusive reading got wrong.
		QuicStream stream = wire.openNow(peer.open());
		assertEquals("RFC 9000 §2.1: the first client-initiated bidirectional stream", 0, stream.id());
		Promise<Response> first = stream.writer()
			.accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("GET", LATE)))
			.then(() -> stream.writer().accept(null))
			.then(() -> peer.readResponse(stream));
		wire.driveUntil(first::isComplete);

		assertTrue("the request at the announced identifier failed: " + first, first.isException());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, applicationErrorCodeOf(first.getException()));
		assertTrue("FR-020: no request stream reached the listener: " + accepted, accepted.isEmpty());
	}

	// ---------------------------------------------------------------- the receiving half (US7 §3)

	@Test
	public void aRequestStreamAtTheReceivedIdentifierFailsRetryably() {
		connectWithClientH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> clientH3.state() == State.READY);

		Http3RequestStream below = openRequestStream();
		Http3RequestStream atIdentifier = openRequestStream();
		assertEquals(0, below.id());
		assertEquals(4, atIdentifier.id());
		Promise<?> belowResponse = below.receiveResponse();
		Promise<?> atIdentifierResponse = atIdentifier.receiveResponse();

		// RFC 9114 §5.2's identifier is exclusive, so naming stream 4 says stream 0 was processed and
		// stream 4 was not — the boundary itself is rejected, not kept.
		control.writer().accept(goAwayFrame(atIdentifier.id()));
		wire.driveUntil(atIdentifierResponse::isComplete);

		assertEquals(4, clientH3.goAwayReceivedId());
		assertEquals(State.GOING_AWAY, clientH3.state());
		Exception e = atIdentifierResponse.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) e).errorCode());
		assertTrue("FR-020: retryable, so the caller may re-issue it elsewhere", ((Http3Exception) e).isRetryable());
		assertEquals(Http3RequestStream.State.RESET, atIdentifier.state());

		assertFalse("FR-020: the stream below the identifier is left to finish", belowResponse.isComplete());
		assertFalse(below.isTerminated());
	}

	/**
	 * The receiving half of the zero-requests-served boundary: a peer that processed nothing announces the
	 * first request stream id, and the exchange already open on that very stream is the one the exclusive
	 * reading rejects and the inclusive one would have left hanging.
	 */
	@Test
	public void theFirstRequestStreamFailsRetryablyWhenThePeerProcessedNothing() {
		connectWithClientH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> clientH3.state() == State.READY);

		Http3RequestStream first = openRequestStream();
		assertEquals(0, first.id());
		Promise<?> firstResponse = first.receiveResponse();

		control.writer().accept(goAwayFrame(0));
		wire.driveUntil(firstResponse::isComplete);

		assertEquals(0, clientH3.goAwayReceivedId());
		Exception e = firstResponse.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) e).errorCode());
		assertTrue("FR-020: retryable — the peer processed nothing at all", ((Http3Exception) e).isRetryable());
		assertEquals(Http3RequestStream.State.RESET, first.state());
	}

	@Test
	public void aClientThatReceivedGoAwayOpensNoFurtherRequestStream() {
		connectWithClientH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> clientH3.state() == State.READY);

		control.writer().accept(goAwayFrame(0));
		wire.driveUntil(() -> clientH3.state() == State.GOING_AWAY);

		Promise<Http3RequestStream> opened = clientH3.openRequestStream();
		loop.tickUntilQuiet();

		assertTrue("RFC 9114 §5.2: no new request on a connection that is going away: " + opened,
			opened.isException());
		Exception e = opened.getException();
		assertTrue("an H3 error: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, ((Http3Exception) e).errorCode());
		assertTrue("retryable", ((Http3Exception) e).isRetryable());
	}

	// ---------------------------------------------------------------- harness

	/** A real {@link Http3Server} answering from {@link #pending}, with {@link Http3TestPeer} as its client. */
	private void startServer() {
		AsyncServlet servlet = request -> pending.computeIfAbsent(request.getPath(), $ -> new SettablePromise<>());
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				server = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.build();
				server.listen();
				return server;
			})
			.connect();
	}

	/**
	 * A hand-built server-side {@link Http3Connection} with {@link Http3TestPeer} as its client — for a
	 * test that announces GOAWAY on its own schedule rather than by shutting a server down.
	 */
	private void startServerConnection() {
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerHandlerFactory(connection -> {
				serverH3 = Http3Connection.builder(reactor(), connection)
					.withRequestStreamListener(accepted::add)
					.build();
				return serverH3.startAndGetStreamManager();
			})
			.connect();
	}

	/** A client-side {@link Http3Connection}, with the bare stream layer standing in for the server. */
	private void connectWithClientH3() {
		wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withClientHandlerFactory(connection -> {
				clientH3 = Http3Connection.create(reactor(), connection);
				return clientH3.streamManager();
			})
			.connect();
	}

	/** A well-formed peer control stream: type {@code 0x00} followed by a minimal SETTINGS frame. */
	private QuicStream openPeerControlStream() {
		QuicStream control = wire.openNow(wire.serverStreams().openUnidirectional());
		control.writer().accept(concat(
			streamHeader(CONTROL.code()),
			settingsFrame(new long[]{0x01, 0x07}, new long[]{0, 0})));
		return control;
	}

	private Http3RequestStream openRequestStream() {
		Promise<Http3RequestStream> opened = clientH3.openRequestStream();
		if (!opened.isResult()) {
			throw new AssertionError("the request stream did not open: " + opened, opened.getException());
		}
		return opened.getResult();
	}

	private void answer(String path) {
		pending.get(path).set(HttpResponse.ok200()
			.withBody(path.substring(1).getBytes(UTF_8))
			.build());
	}

	private <T> T await(Promise<T> promise) {
		wire.driveUntil(promise::isComplete);
		if (!promise.isResult()) {
			throw new AssertionError("the promise failed: " + promise, promise.getException());
		}
		return promise.getResult();
	}

	/** The application error code a peer's abort carried, whatever shape the stream layer reported it in. */
	private static long applicationErrorCodeOf(Exception e) {
		if (e instanceof Http3Exception h3) return h3.errorCode();
		if (e instanceof QuicStreamResetException reset) return reset.applicationErrorCode();
		if (e instanceof QuicStreamStopSendingException stopped) return stopped.applicationErrorCode();
		throw new AssertionError("expected an H3 or stream-layer failure, got " + e, e);
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
