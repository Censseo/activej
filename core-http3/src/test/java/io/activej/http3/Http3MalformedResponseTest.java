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
import io.activej.csp.consumer.ChannelConsumer;
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
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;

import static java.nio.charset.StandardCharsets.UTF_8;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.PORT;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T114 / spec.md Error Scenarios, {@code contracts/java-api.md} §2.4: a response that is not a
 * well-formed HTTP message fails a caller's {@link Http3Client#request} promise with
 * {@link MalformedHttpException} — the type {@code core-http}'s own client raises for its own malformed
 * responses, so code written against {@code IHttpClient} catches one type rather than two (FR-047).
 * <p>
 * The peer here writes its response head <b>by hand</b> onto the request stream, because a field section
 * this module's own encoder would emit is by construction well-formed: what is under test is what the
 * client does with bytes RFC 9114 §4.3.2 forbids, not what the encoder produces.
 * <p>
 * The translation is narrow on purpose, and the last test says so: only {@code H3_MESSAGE_ERROR} — the
 * code RFC 9114 §4.1.2 reserves for a message that is not fully formed — becomes a
 * {@code MalformedHttpException}. A limit, a timeout, a rejection and a transport failure each mean
 * something a caller would act on differently, and each keeps its own type (FR-058c).
 */
public final class Http3MalformedResponseTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String STATUS = ":status";

	/** One entry per accepted QUIC connection; kept so the fixture has something to close them with. */
	private final List<Http3Connection> serverConnections = new ArrayList<>();

	private final StubDnsClient dns = new StubDnsClient();

	/** The response head the peer writes, as a field section; set per test. */
	private List<Field> responseFields = List.of();

	/** DATA to write after the response head, or {@code null} to FIN straight after it. */
	private byte @Nullable [] responseBody;

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
	public void aResponseWithNoStatusFailsWithMalformedHttpException() {
		// RFC 9114 §4.3.2: a response field section carries exactly one ":status".
		assertMalformed(requestAnsweredWith(List.of(new Field("content-type", "text/plain"))));
	}

	@Test
	public void aResponseWithTwoStatusesFailsWithMalformedHttpException() {
		assertMalformed(requestAnsweredWith(List.of(new Field(STATUS, "200"), new Field(STATUS, "404"))));
	}

	@Test
	public void aResponseWithAPseudoHeaderAfterARegularFieldFailsWithMalformedHttpException() {
		// RFC 9114 §4.3: every pseudo-header precedes every regular field.
		assertMalformed(requestAnsweredWith(
			List.of(new Field("content-type", "text/plain"), new Field(STATUS, "200"))));
	}

	/**
	 * The T114 residual, and the harder half of FR-047: a {@code Content-Length} that disagrees with the
	 * body can only be discovered when the body <b>ends</b>, so it surfaces on the body channel rather
	 * than on the promise {@code request(...)} returned. That path used to deliver a raw
	 * {@code Http3Exception} while every head-level malformation delivered {@code MalformedHttpException}
	 * — so a caller doing the documented thing caught a bad {@code :status} and missed a short body.
	 */
	@Test
	public void aContentLengthThatDisagreesWithTheBodyFailsWithMalformedHttpException() {
		responseFields = List.of(new Field(STATUS, "200"), new Field("content-length", "10"));
		responseBody = "short".getBytes(UTF_8);
		start();

		Promise<HttpResponse> request = client.request(HttpRequest.get(url(HOST, "/short")).build());
		wire.driveUntil(request::isComplete);
		assertTrue("the head is well-formed, so the response itself resolves: " + request, request.isResult());

		Promise<ByteBuf> body = request.getResult().loadBody();
		wire.driveUntil(body::isComplete);
		assertTrue("the body is shorter than it declared: " + body, body.isException());
		assertMalformed(body.getException());
	}

	/** The other half of the contract: nothing that is not a malformed <i>message</i> is translated. */
	@Test
	public void aFailureThatIsNotAMalformedMessageKeepsItsOwnType() {
		start();

		Promise<HttpResponse> request = client.request(
			HttpRequest.get("http://" + HOST + ":" + PORT + "/plain").build());

		assertTrue("FR-051: refused before any socket work: " + request, request.isException());
		Exception e = request.getException();
		assertFalse("a refusal is not a malformed message: " + e, e instanceof MalformedHttpException);
		assertTrue("this client's own error type: " + e, e instanceof Http3Exception);
	}

	// ---------------------------------------------------------------- harness

	/**
	 * The documented client-visible type, carrying the H3 failure as its cause so that the RFC 9114 §8.1
	 * code the stream was reset with is not lost on the way out.
	 */
	private static void assertMalformed(Exception e) {
		assertTrue("the documented client-visible type: " + e, e instanceof MalformedHttpException);
		Throwable cause = e.getCause();
		assertTrue("the H3 failure is the cause: " + cause, cause instanceof Http3Exception);
		assertEquals(Http3Errors.H3_MESSAGE_ERROR, ((Http3Exception) cause).errorCode());
	}

	/** Issues one request against a peer answering with {@code fields}, and returns how it failed. */
	private Exception requestAnsweredWith(List<Field> fields) {
		responseFields = fields;
		start();

		Promise<HttpResponse> request = client.request(HttpRequest.get(url(HOST, "/malformed")).build());
		wire.driveUntil(request::isComplete);

		assertTrue("the response was refused: " + request, request.isException());
		return request.getException();
	}

	private void start() {
		wire = new Http3WirePair(loop)
			.withServerHandlerFactory(this::acceptConnection)
			.withClientFactory(socket -> client = Http3Client.builder(reactor(), dns)
				.withSocket(socket)
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
	 * Reads the request through the module's own stream — so the client faces a peer that behaves right up
	 * to the response — and then writes the head by hand, since {@code sendResponse} would refuse to emit
	 * a field section like these.
	 */
	private void answer(Http3RequestStream requestStream) {
		requestStream.receiveRequest()
			.whenResult($ -> {
				// The writer owns the buffer on every path, this one included.
				ChannelConsumer<ByteBuf> writer = requestStream.quicStream().writer();
				writer.accept(Http3TestBytes.headersFrame(responseFields))
					.then(() -> responseBody == null ?
						Promise.complete() :
						writer.accept(Http3TestBytes.dataFrame(responseBody)))
					.then(() -> writer.accept(null));
			});
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
