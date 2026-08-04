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

import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
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
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T093 / FR-040, US8 §5: extended CONNECT and WebSocket over HTTP/3 (RFC 9220) are out of scope, and a
 * request that asks for either is refused with {@code H3_REQUEST_REJECTED} (0x010b) rather than
 * partially handled.
 * <p>
 * {@code H3_REQUEST_REJECTED} and not {@code H3_MESSAGE_ERROR}, which every other field-list rejection
 * uses: nothing is <i>wrong</i> with a CONNECT request. It is well formed and simply asks for a
 * capability this implementation does not have, so it is retryable — the peer may re-issue it against
 * an endpoint that does, and {@link Http3Exception#isRetryable()} says so.
 * <p>
 * Two layers are asserted, because the requirement lives at one and is observable at the other: the
 * field-list validator directly, and a real {@link Http3Server} whose servlet must never see the
 * request at all.
 */
public final class Http3ExtendedConnectRefusalTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Every path a servlet was asked for — empty is the assertion, in every test here. */
	private final List<String> served = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server server;
	private @Nullable Http3TestPeer peer;

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

	// ---------------------------------------------------------------- the validator

	@Test
	public void aConnectRequestIsRejected() {
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "CONNECT"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "/"))));
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, e.errorCode());
		assertTrue("FR-040: nothing was processed, so the peer may re-issue it elsewhere", e.isRetryable());
	}

	@Test
	public void aProtocolPseudoHeaderIsRejected() {
		// RFC 9220's extended CONNECT: the :protocol pseudo-header is what turns a CONNECT into a
		// WebSocket handshake. It is refused on its own, whatever :method carries.
		Http3Exception e = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "/chat"),
			new Field(":protocol", "websocket"))));
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, e.errorCode());
		assertTrue(e.isRetryable());
	}

	@Test
	public void theRefusalNamesNoValueThePeerSupplied() {
		// FR-063: the reason names the construct that was refused, never what it carried.
		String secretAuthority = "internal-secret-host.example";
		String secretPath = "/tunnel?token=super-secret-token";
		String secretProtocol = "s3cret-subprotocol";

		Http3Exception connect = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "CONNECT"),
			new Field(":scheme", "https"),
			new Field(":authority", secretAuthority),
			new Field(":path", secretPath))));
		assertFalse(connect.getMessage(), connect.getMessage().contains(secretAuthority));
		assertFalse(connect.getMessage(), connect.getMessage().contains(secretPath));

		Http3Exception extended = assertThrows(Http3Exception.class, () -> Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", secretAuthority),
			new Field(":path", secretPath),
			new Field(":protocol", secretProtocol))));
		assertFalse(extended.getMessage(), extended.getMessage().contains(secretProtocol));
		assertFalse(extended.getMessage(), extended.getMessage().contains(secretPath));
	}

	@Test
	public void anOrdinaryRequestIsUntouched() throws Http3Exception {
		// The negative control: the refusal keys on CONNECT and on :protocol, and on nothing else.
		Http3Headers.toRequestBuilder(List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.com"),
			new Field(":path", "/"),
			new Field("x-protocol", "websocket"))).build();
	}

	// ---------------------------------------------------------------- a real server

	@Test
	public void aServerResetsAConnectRequestWithoutInvokingTheServlet() {
		start();

		Promise<Http3TestPeer.Response> refused =
			peer.request(Http3TestBytes.requestFields("CONNECT", "/tunnel"), null);
		wire.driveUntil(refused::isComplete);

		assertTrue("the request stream was reset: " + refused, refused.isException());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, applicationErrorCodeOf(refused.getException()));
		assertTrue("FR-040: the servlet never sees a CONNECT", served.isEmpty());
		assertEquals(0, server.requestsServed());
	}

	@Test
	public void aServerResetsAnExtendedConnectRequestWithoutInvokingTheServlet() {
		start();

		List<Field> fields = Http3TestBytes.requestFields("CONNECT", "/chat");
		fields.add(new Field(":protocol", "websocket"));
		Promise<Http3TestPeer.Response> refused = peer.request(fields, null);
		wire.driveUntil(refused::isComplete);

		assertTrue("the request stream was reset: " + refused, refused.isException());
		assertEquals(Http3Errors.H3_REQUEST_REJECTED, applicationErrorCodeOf(refused.getException()));
		assertTrue(served.isEmpty());
	}

	@Test
	public void theConnectionStaysUsableAfterARefusedConnect() {
		// The refusal is a *stream* error: one peer's unsupported request must not cost the connection the
		// requests that follow it.
		start();

		Promise<Http3TestPeer.Response> refused =
			peer.request(Http3TestBytes.requestFields("CONNECT", "/tunnel"), null);
		wire.driveUntil(refused::isComplete);
		assertTrue(refused.isException());

		Promise<Http3TestPeer.Response> ordinary = peer.get("/ok");
		wire.driveUntil(ordinary::isComplete);
		assertTrue("the connection carried the next request: " + ordinary, ordinary.isResult());
		assertEquals(200, ordinary.getResult().status());
		assertEquals(List.of("/ok"), served);
		assertEquals("one QUIC connection carried both", 1, server.connectionsAccepted());
	}

	// ---------------------------------------------------------------- harness

	private void start() {
		AsyncServlet servlet = request -> {
			served.add(request.getPath());
			return HttpResponse.ok200().withBody("fine".getBytes(UTF_8)).toPromise();
		};
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

	/** The application error code the server's reset carried, whatever shape the stream layer reported it in. */
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
