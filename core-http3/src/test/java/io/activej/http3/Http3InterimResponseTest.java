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

import io.activej.async.function.AsyncRunnable;
import io.activej.bytebuf.ByteBuf;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http.MalformedHttpException;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDnsClient;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
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
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T121 / RFC 9114 §4.1: a server may send any number of informational ({@code 1xx}) responses ahead of
 * the final one, and a client consumes each of them and keeps reading. {@code 103 Early Hints} is sent
 * unsolicited by real CDNs, so this is not a corner a client can decline to have an opinion about.
 * <p>
 * What made it worth a test of its own is the shape of getting it wrong: an interim HEADERS frame is
 * still a HEADERS frame, so a client that simply resolved on the first one handed the caller the 1xx
 * <b>and</b> then read the real response's HEADERS as that 1xx's <i>trailer section</i> — a bodyless 103
 * whose trailers were the actual response's headers, and no error anywhere. The frame-sequence grammar
 * has to be told that a HEADERS frame opened nothing, since a frame type is all it ever sees.
 * <p>
 * The peer writes its response head by hand for the same reason {@code Http3MalformedResponseTest} does:
 * {@code sendResponse} would send a 1xx as <i>the</i> response, which is exactly what is not under test.
 * <p>
 * No {@code EventloopRule}: {@link ManualEventloop} installs its own reactor on a hand-driven clock.
 */
public final class Http3InterimResponseTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String STATUS = ":status";

	/** One entry per accepted QUIC connection; kept so the fixture has something to close them with. */
	private final List<Http3Connection> serverConnections = new ArrayList<>();

	private final StubDnsClient dns = new StubDnsClient();

	/** The interim field sections the peer writes before the final response; set per test. */
	private List<List<Field>> interimSections = List.of();

	private Http3Settings clientSettings = Http3Settings.create();

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
	public void anEarlyHintsResponseIsConsumedAndTheFinalResponseDelivered() {
		interimSections = List.of(List.of(new Field(STATUS, "103"), new Field("link", "</style.css>")));

		HttpResponse response = requestExpectingSuccess();

		assertEquals("the final response, not the interim one", 200, response.getCode());
		assertNull("the interim section's fields did not leak onto it", response.getHeader(HttpHeaders.LINK));
		assertNull("and it did not become a trailer section either", Http3Trailers.get(response));

		Promise<ByteBuf> body = response.loadBody();
		wire.driveUntil(body::isComplete);
		assertTrue("the body behind it loaded: " + body, body.isResult());
		// The response, and hence what loadBody() produced from it, is the caller's here (FR-057a).
		ByteBuf buf = body.getResult();
		try {
			assertEquals("done", buf.getString(UTF_8));
		} finally {
			buf.recycle();
		}
	}

	/** A {@code 100 Continue} and two hint responses in a row: the count is what varies, not the rule. */
	@Test
	public void severalInterimResponsesInARowAreAllConsumed() {
		interimSections = List.of(
			List.of(new Field(STATUS, "100")),
			List.of(new Field(STATUS, "103"), new Field("link", "</a.css>")),
			List.of(new Field(STATUS, "103"), new Field("link", "</b.css>")));

		assertEquals(200, requestExpectingSuccess().getCode());
	}

	/**
	 * SI-3: RFC 9114 puts no number on interim responses, and each is a field section this side decodes
	 * and throws away — so without a bound a server holds the stream open, and the caller's promise
	 * unresolved, for as long as it cares to keep sending three-byte HEADERS frames.
	 */
	@Test
	public void moreInterimResponsesThanTheBoundFailsTheExchange() {
		clientSettings = Http3Settings.builder().withMaxInterimResponses(2).build();
		interimSections = List.of(
			List.of(new Field(STATUS, "100")),
			List.of(new Field(STATUS, "100")),
			List.of(new Field(STATUS, "100")));

		Exception e = requestExpectingFailure();
		assertTrue("the module's own error type: " + e, e instanceof Http3Exception);
		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, ((Http3Exception) e).errorCode());
	}

	/** The bound is not off by one: exactly as many as configured still delivers the final response. */
	@Test
	public void exactlyTheBoundManyInterimResponsesIsAccepted() {
		clientSettings = Http3Settings.builder().withMaxInterimResponses(2).build();
		interimSections = List.of(
			List.of(new Field(STATUS, "100")),
			List.of(new Field(STATUS, "103")));

		assertEquals(200, requestExpectingSuccess().getCode());
	}

	/**
	 * An interim response is consumed, not waved through: its field section is still a response field
	 * section, and a malformed one is still a malformed message — surfacing through T114's translation
	 * exactly as a malformed <i>final</i> response does.
	 */
	@Test
	public void aMalformedInterimResponseStillFailsTheExchange() {
		// RFC 9114 §4.3: every pseudo-header precedes every regular field.
		interimSections = List.of(List.of(new Field("link", "</style.css>"), new Field(STATUS, "103")));

		Exception e = requestExpectingFailure();
		assertTrue("the documented client-visible type: " + e, e instanceof MalformedHttpException);
		Throwable cause = e.getCause();
		assertTrue("the H3 failure is the cause: " + cause, cause instanceof Http3Exception);
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, ((Http3Exception) cause).errorCode());
	}

	// ---------------------------------------------------------------- harness

	private HttpResponse requestExpectingSuccess() {
		Promise<HttpResponse> request = issue();
		assertTrue("the request succeeded: " + request, request.isResult());
		return request.getResult();
	}

	private Exception requestExpectingFailure() {
		Promise<HttpResponse> request = issue();
		assertTrue("the request failed: " + request, request.isException());
		return request.getException();
	}

	private Promise<HttpResponse> issue() {
		start();
		Promise<HttpResponse> request = client.request(HttpRequest.get(url(HOST, "/hinted")).build());
		wire.driveUntil(request::isComplete);
		return request;
	}

	private void start() {
		wire = new Http3WirePair(loop)
			.withServerHandlerFactory(this::acceptConnection)
			.withClientFactory(socket -> client = Http3Client.builder(reactor(), dns)
				.withSocket(socket)
				.withSettings(clientSettings)
				.withTlsEngineFactory(Http3TestTls::clientEngineFactory)
				.build())
			.connect();
	}

	private QuicFrameHandler acceptConnection(QuicConnection quicConnection) {
		Http3Connection h3 = Http3Connection.builder(reactor(), quicConnection)
			.withRequestStreamListener(this::answer)
			.build();
		serverConnections.add(h3);
		return h3.startAndGetStreamManager();
	}

	/**
	 * Reads the request through the module's own stream — so the client faces a peer that behaves right
	 * up to the response — then writes every interim head and the final response by hand.
	 */
	private void answer(Http3RequestStream requestStream) {
		requestStream.receiveRequest()
			.whenResult($ -> {
				// The writer owns each buffer on every path.
				ChannelConsumer<ByteBuf> writer = requestStream.quicStream().writer();
				Promises.sequence(interimSections.stream()
						.map(section -> (AsyncRunnable) () -> writer.accept(Http3TestBytes.headersFrame(section))))
					.then(() -> writer.accept(Http3TestBytes.headersFrame(List.of(
						new Field(STATUS, "200"),
						new Field("content-length", "4")))))
					.then(() -> writer.accept(Http3TestBytes.dataFrame("done".getBytes(UTF_8))))
					.then(() -> writer.accept(null));
			});
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
