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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.MemSize;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpMethod;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.GoAwayDirection;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T096 / FR-063, mirroring {@code core-quic}'s {@code QuicSecretsStayOutOfLogsTest} for this module: no
 * log line, no exception message and — above all — <b>no {@code Inspector} call</b> carries a field
 * value, a body byte, a cookie, an authorization credential or key material.
 * <p>
 * The inspector surface is what this test exists for. The other two FR-063 surfaces already have their
 * own coverage — {@code Http3HeadersValidationTest.exceptionMessagesNeverContainSuppliedFieldValues} for
 * exception text, {@code Http3PushRefusalTest} for the refusal messages — but
 * {@link Http3Server.Inspector} and {@link Http3Client.Inspector} are new and are the one place where a
 * future implementer could plausibly reach for "the header that caused this" and hand a credential to a
 * metrics backend. So every callback's arguments are captured verbatim and searched.
 * <p>
 * Three exchanges are driven, each carrying the same secrets in the same places — an {@code
 * Authorization} header, a {@code Cookie}, a body, a {@code Set-Cookie} on the way back — and each
 * ending differently: served, reset mid-body at a size bound, and cut short by a connection error with
 * the request still in flight. The failure paths matter most: that is where an implementation reaches
 * for context to put in a message.
 * <p>
 * Logging is captured at DEBUG over the whole {@code io.activej.http3} subtree; {@code
 * test/logback-test.xml} sets {@code io.activej} to {@code off}, and the override on the more specific
 * logger wins because logback resolves the effective level by nearest ancestor.
 */
public final class Http3SecretsStayOutOfLogsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The needles. Each is long and distinctive enough that a substring hit cannot be a coincidence. */
	private static final String CREDENTIAL = "Bearer s3cr3t-authorization-token-9f2b";
	private static final String COOKIE = "session=s3cr3t-session-cookie-4c8a";
	private static final String REQUEST_BODY_SECRET = "s3cr3t-request-body-payload-11ee";
	private static final String RESPONSE_BODY_SECRET = "s3cr3t-response-body-payload-22ff";
	private static final String SET_COOKIE = "session=s3cr3t-issued-cookie-77dd; HttpOnly";
	private static final String SECRET_HEADER_VALUE = "s3cr3t-custom-header-value-33aa";

	private static final List<String> SECRETS = List.of(
		CREDENTIAL, COOKIE, REQUEST_BODY_SECRET, RESPONSE_BODY_SECRET, SET_COOKIE, SECRET_HEADER_VALUE);

	/** Enough to fit the secrets and their framing, and small enough that a padded body blows past it. */
	private static final MemSize SMALL_BODY_BOUND = MemSize.bytes(512);

	/** Every inspector call, rendered with all of its arguments — the thing under assertion. */
	private final List<String> events = new ArrayList<>();

	private ManualEventloop loop;
	private Logger http3Logger;
	private @Nullable Level originalLevel;
	private ListAppender<ILoggingEvent> appender;

	private @Nullable Http3ClientFixture fixture;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Server rawServer;

	@Before
	public void setUp() {
		loop = new ManualEventloop();

		http3Logger = (Logger) LoggerFactory.getLogger("io.activej.http3");
		originalLevel = http3Logger.getLevel();
		http3Logger.setLevel(Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		http3Logger.addAppender(appender);
	}

	@After
	public void tearDown() {
		http3Logger.detachAppender(appender);
		http3Logger.setLevel(originalLevel);
		if (fixture != null) fixture.close();
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- a served exchange

	@Test
	public void aServedExchangeReportsNothingButNumbers() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> request.loadBody()
				.map(body -> HttpResponse.ok200()
					.withHeader(HttpHeaders.SET_COOKIE, SET_COOKIE)
					.withHeader(HttpHeaders.of("x-secret"), SECRET_HEADER_VALUE)
					.withBody(RESPONSE_BODY_SECRET.getBytes(UTF_8))
					.build()))
			.withServerInspector(new CapturingServerInspector())
			.withClientInspector(new CapturingClientInspector())
			.start();

		HttpResponse response = fixture.await(fixture.client().request(secretRequest()));

		assertEquals(200, response.getCode());
		assertEquals(RESPONSE_BODY_SECRET, body(response));

		// Both sides really did report the exchange, so the assertion below is not vacuous.
		assertTrue("the server inspector was called: " + events, containsEvent("server.onRequestStarted"));
		assertTrue("the server inspector was called: " + events, containsEvent("server.onRequestCompleted"));
		assertTrue("the client inspector was called: " + events, containsEvent("client.onRequestStarted"));
		assertTrue("the client inspector was called: " + events, containsEvent("client.onRequestCompleted"));

		assertNoSecretsAnywhere();
	}

	@Test
	public void aGoAwayReportsNothingButItsIdentifier() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> request.loadBody().map(body -> HttpResponse.ok200().build()))
			.withServerInspector(new CapturingServerInspector())
			.withClientInspector(new CapturingClientInspector())
			.start();

		body(fixture.await(fixture.client().request(secretRequest())));
		fixture.server().close();
		fixture.wire().driveUntil(() -> containsEvent("client.onGoAway"));

		assertTrue("the server announced it: " + events, containsEvent("server.onGoAway SENT"));
		assertTrue("the client heard it: " + events, containsEvent("client.onGoAway RECEIVED"));
		assertNoSecretsAnywhere();
	}

	// ---------------------------------------------------------------- a stream reset

	@Test
	public void aStreamResetWithASecretInFlightReportsOnlyItsCode() {
		fixture = new Http3ClientFixture(loop)
			.withServlet(request -> request.loadBody().map(body -> HttpResponse.ok200().build()))
			.withServerSettings(Http3Settings.builder().withMaxBodySize(SMALL_BODY_BOUND).build())
			.withServerInspector(new CapturingServerInspector())
			.withClientInspector(new CapturingClientInspector())
			.start();

		// The secret is in the first bytes of the body, so it is already through the decoder when the bound
		// is passed — the reset happens with the secret in hand rather than before it was ever seen.
		byte[] oversized = (REQUEST_BODY_SECRET + "x".repeat(SMALL_BODY_BOUND.toInt() * 4)).getBytes(UTF_8);
		Exception failure = fixture.awaitException(fixture.client().request(
			HttpRequest.post(url(HOST, "/upload"))
				.withHeader(HttpHeaders.AUTHORIZATION, CREDENTIAL)
				.withHeader(HttpHeaders.COOKIE, COOKIE)
				.withBody(oversized)
				.build()));

		assertTrue("the server reset the stream: " + events, containsEvent("server.onStreamReset"));
		assertTrue("with H3_EXCESSIVE_LOAD: " + events,
			containsEvent("server.onStreamReset streamId=0 errorCode=0x" +
						  Long.toHexString(Http3Errors.H3_EXCESSIVE_LOAD)));
		assertNoSecretInText("the failure reported to the caller", describe(failure));
		assertNoSecretsAnywhere();
	}

	// ---------------------------------------------------------------- a connection error

	@Test
	public void aConnectionErrorWithASecretInFlightReportsOnlyItsCode() {
		wire = new Http3WirePair(loop);
		Http3TestPeer peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				// A servlet that never answers: the request stays in flight, holding its secrets, until the
				// connection error below aborts it.
				rawServer = Http3Server.builder(reactor(), request -> new SettablePromise<>())
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withInspector(new CapturingServerInspector())
					.build();
				rawServer.listen();
				return rawServer;
			})
			.connect();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/upload");
		fields.add(new Field("authorization", CREDENTIAL));
		fields.add(new Field("cookie", COOKIE));
		Promise<Http3TestPeer.Response> request = peer.requestWithoutFin(fields);
		wire.driveUntil(() -> containsEvent("server.onRequestStarted"));

		// RFC 9114 §6.2.2: only a server pushes, so a push stream a client opens is a connection error —
		// raised while the request above is still in flight, with its credential in the server's hands.
		QuicStream push = wire.openNow(wire.clientStreams().openUnidirectional());
		push.writer().accept(concat(streamHeader(Http3StreamType.PUSH.code()), Http3TestBytes.bytes(0x00)));
		wire.driveUntil(request::isComplete);

		assertTrue("the connection was closed with an H3 code: " + events,
			containsEvent("server.onConnectionError errorCode=0x" +
						  Long.toHexString(Http3Errors.H3_STREAM_CREATION_ERROR)));
		assertTrue("the in-flight request stream was reset with it: " + events,
			containsEvent("server.onStreamReset streamId=0 errorCode=0x" +
						  Long.toHexString(Http3Errors.H3_STREAM_CREATION_ERROR)));
		assertTrue("the request failed: " + request, request.isException());
		assertNoSecretInText("the failure reported to the peer", describe(request.getException()));
		assertNoSecretsAnywhere();
	}

	// ---------------------------------------------------------------- the inspectors

	private final class CapturingServerInspector implements Http3Server.Inspector {
		@Override
		public <T extends Http3Server.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Server server, long streamId, HttpMethod method) {
			record("server.onRequestStarted streamId=" + streamId + " method=" + method, server);
		}

		@Override
		public void onRequestCompleted(
			Http3Server server, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {
			record("server.onRequestCompleted streamId=" + streamId + " status=" + statusCode +
				   " in=" + requestBodyBytes + " out=" + responseBodyBytes, server);
		}

		@Override
		public void onStreamReset(Http3Server server, long streamId, long errorCode) {
			record("server.onStreamReset streamId=" + streamId + " errorCode=0x" + Long.toHexString(errorCode), server);
		}

		@Override
		public void onConnectionError(Http3Server server, long errorCode) {
			record("server.onConnectionError errorCode=0x" + Long.toHexString(errorCode), server);
		}

		@Override
		public void onFrameDiscarded(Http3Server server, long frameType, long declaredLength) {
			record("server.onFrameDiscarded type=0x" + Long.toHexString(frameType) +
				   " length=" + declaredLength, server);
		}

		@Override
		public void onGoAway(Http3Server server, GoAwayDirection direction, long id) {
			record("server.onGoAway " + direction + " id=" + id, server);
		}
	}

	private final class CapturingClientInspector implements Http3Client.Inspector {
		@Override
		public <T extends Http3Client.Inspector> @Nullable T lookup(Class<T> type) {
			return type.isInstance(this) ? type.cast(this) : null;
		}

		@Override
		public void onRequestStarted(Http3Client client, long streamId, HttpMethod method) {
			record("client.onRequestStarted streamId=" + streamId + " method=" + method, client);
		}

		@Override
		public void onRequestCompleted(
			Http3Client client, long streamId, int statusCode, long requestBodyBytes, long responseBodyBytes
		) {
			record("client.onRequestCompleted streamId=" + streamId + " status=" + statusCode +
				   " out=" + requestBodyBytes + " in=" + responseBodyBytes, client);
		}

		@Override
		public void onStreamReset(Http3Client client, long streamId, long errorCode) {
			record("client.onStreamReset streamId=" + streamId + " errorCode=0x" + Long.toHexString(errorCode), client);
		}

		@Override
		public void onConnectionError(Http3Client client, long errorCode) {
			record("client.onConnectionError errorCode=0x" + Long.toHexString(errorCode), client);
		}

		@Override
		public void onFrameDiscarded(Http3Client client, long frameType, long declaredLength) {
			record("client.onFrameDiscarded type=0x" + Long.toHexString(frameType) +
				   " length=" + declaredLength, client);
		}

		@Override
		public void onGoAway(Http3Client client, GoAwayDirection direction, long id) {
			record("client.onGoAway " + direction + " id=" + id, client);
		}

		@Override
		public void onRequestQueued(Http3Client client, int queueDepth) {
			record("client.onRequestQueued depth=" + queueDepth, client);
		}

		@Override
		public void onRequestDequeued(Http3Client client, int queueDepth) {
			record("client.onRequestDequeued depth=" + queueDepth, client);
		}
	}

	/**
	 * Captures one call with every argument it carried — the component included, since its
	 * {@code toString()} is as much part of what an inspector implementation would publish as the
	 * arguments are.
	 */
	private void record(String event, Object component) {
		events.add(event + " [" + component + ']');
	}

	// ---------------------------------------------------------------- assertions

	private void assertNoSecretsAnywhere() {
		assertFalse("no inspector call was captured, so this test proves nothing", events.isEmpty());
		for (String event : events) {
			assertNoSecretInText("an inspector call", event);
		}
		assertFalse("logging never happened; the appender is not wired up", appender.list.isEmpty());
		for (ILoggingEvent logged : appender.list) {
			assertNoSecretInText("a log line", String.valueOf(logged.getFormattedMessage()) + ' ' + throwableTextOf(logged));
		}
	}

	private static void assertNoSecretInText(String what, String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (String secret : SECRETS) {
			assertFalse("FR-063: " + what + " leaked a secret: " + text,
				lower.contains(secret.toLowerCase(Locale.ROOT)));
		}
	}

	private static String throwableTextOf(ILoggingEvent event) {
		IThrowableProxy proxy = event.getThrowableProxy();
		return proxy == null ? "" : String.valueOf(proxy.getMessage());
	}

	/** An exception plus every cause under it — the whole of what a caller could print. */
	private static String describe(@Nullable Throwable e) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = e; current != null; current = current.getCause()) {
			text.append(current).append(' ');
			if (current.getCause() == current) break;
		}
		return text.toString();
	}

	private boolean containsEvent(String prefix) {
		return events.stream().anyMatch(event -> event.startsWith(prefix));
	}

	// ---------------------------------------------------------------- harness

	private static HttpRequest secretRequest() {
		return HttpRequest.post(url(HOST, "/upload"))
			.withHeader(HttpHeaders.AUTHORIZATION, CREDENTIAL)
			.withHeader(HttpHeaders.COOKIE, COOKIE)
			.withHeader(HttpHeaders.of("x-secret"), SECRET_HEADER_VALUE)
			.withBody(REQUEST_BODY_SECRET.getBytes(UTF_8))
			.build();
	}

	/** The body of a delivered response, whose {@code ByteBuf} the caller owns. */
	private String body(HttpResponse response) {
		ByteBuf body = fixture.await(response.loadBody());
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
