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
import io.activej.common.MemSize;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpHeaders;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDnsClient;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.function.Consumer;

import static io.activej.http3.testutil.Http3ClientFixture.HOST;
import static io.activej.http3.testutil.Http3ClientFixture.url;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T115 / FR-030, FR-054: the two bounds a peer can push a <b>client</b> past — the response's field
 * section and the response's body — each with the RFC 9114 §8.1 code it must be refused with.
 * <p>
 * {@link Http3ServerLimitsTest} is the same pair from the server's side. The enforcement is one
 * implementation for both roles ({@code Http3RequestStream.decodeFieldSection} and
 * {@code readBody} serve {@code buildRequest} and {@code buildResponse} alike), so what is new here is
 * not the code path but the direction it runs in: a client reading a response it did not ask to be
 * this large, and the shape that failure reaches a caller in — a failed promise rather than a stream
 * reset the peer observes.
 * <p>
 * The peer is a real {@link Http3Server} answering with a response larger than the client will take,
 * which nothing on the sending side refuses: neither bound is enforced outbound, and a server is under
 * no obligation to consult the client's advertised {@code SETTINGS_MAX_FIELD_SECTION_SIZE}. So an
 * oversized response is exactly what an ordinary server produces for a client configured this
 * tightly, rather than something only a hostile peer would send.
 */
public final class Http3ClientLimitsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final StubDnsClient dns = new StubDnsClient();

	private ManualEventloop loop;
	private @Nullable Http3ClientFixture fixture;

	/** The hand-written peer of the last test, which builds its half of the wire itself. */
	private @Nullable Http3WirePair hostileWire;
	private @Nullable Http3Client hostileClient;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (fixture != null) fixture.close();
		if (hostileWire != null) hostileWire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void aResponseFieldSectionOverTheBoundIsExcessiveLoad() {
		start(request -> HttpResponse.ok200()
				.withHeader(HttpHeaders.of("x-padding"), "p".repeat(4096))
				.toPromise(),
			Http3Settings.builder().withMaxFieldSectionSize(MemSize.bytes(128)).build());

		Exception e = fixture.awaitException(client().request(HttpRequest.get(url(HOST, "/big-head")).build()));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, errorCodeOf(e));
		assertEquals("the exchange is over rather than left in flight", 0, client().activeRequests());
	}

	/**
	 * The head arrives — a body bound says nothing about it — and the failure lands on the read that
	 * carries the body past the bound.
	 * <p>
	 * Drained chunk by chunk rather than through {@code loadBody()} on purpose: {@code core-http}'s own
	 * collector holds the same number as its limit (the response is built {@code withMaxBodySize} from
	 * these settings), so a {@code loadBody()} assertion would not say which of the two limits fired.
	 * This one can only be the H3 one.
	 */
	@Test
	public void aResponseBodyOverTheBoundFailsTheBodyRead() {
		start(request -> HttpResponse.ok200()
				.withBody("x".repeat(1024).getBytes(UTF_8))
				.toPromise(),
			Http3Settings.builder().withMaxBodySize(MemSize.bytes(16)).build());

		HttpResponse response = fixture.await(client().request(HttpRequest.get(url(HOST, "/big-body")).build()));
		assertEquals("FR-054 bounds the body, not the head", 200, response.getCode());

		Exception e = fixture.awaitException(drain(response.takeBodyStream()));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, errorCodeOf(e));
	}

	/**
	 * T116: the adversarial variant of the first test — a response field section <b>declaring</b> 50 MB
	 * that never arrives, at the client's <b>default</b> settings, where the reader's bound used to be
	 * {@code max(maxFieldSectionSize, maxBodySize)} = 100 MB and this length passed it unnoticed.
	 * <p>
	 * {@link #aResponseFieldSectionOverTheBoundIsExcessiveLoad()} is the case an ordinary server produces:
	 * a field section really there and really too big, refused once it is whole. This one is a case only a
	 * hostile server produces, and it is the one that used to cost memory rather than a stream — a HEADERS
	 * frame is buffered whole, since QPACK cannot decode a field section in pieces, so the declared length
	 * has to be bounded before the payload is allocated rather than after it is materialized.
	 * <p>
	 * The peer here is therefore not an {@link Http3Server} but a bare {@link Http3Connection} writing the
	 * frame header by hand: no server this module builds would emit one.
	 * {@code Http3FrameReaderHeadersBoundTest} is where the memory itself is measured.
	 */
	@Test
	public void anOverDeclaredResponseFieldSectionIsRefusedBeforeItIsAllocated() {
		startHostilePeer(requestStream -> requestStream.receiveRequest()
			.whenResult($ -> {
				// The writer owns the buffer on every path. The frame header, 64 bytes of field section,
				// and then silence — no FIN, so nothing but the bound ends this exchange.
				ChannelConsumer<ByteBuf> writer = requestStream.quicStream().writer();
				writer.accept(Http3TestBytes.overDeclaredHeadersFrame(
					MemSize.megabytes(50).toLong(), new byte[64]));
			}));

		Promise<HttpResponse> request =
			hostileClient.request(HttpRequest.get(url(HOST, "/over-declared")).build());
		hostileWire.driveUntil(request::isComplete);

		assertTrue("the response was refused: " + request, request.isException());
		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, errorCodeOf(request.getException()));
		assertEquals("the exchange is over rather than left in flight", 0, hostileClient.activeRequests());
	}

	// ---------------------------------------------------------------- harness

	/** Reads {@code body} to its end or its failure, recycling every chunk it is handed on the way. */
	private static Promise<Void> drain(ChannelSupplier<ByteBuf> body) {
		return Promises.repeat(() -> body.get()
			.map(buf -> {
				if (buf == null) return false;
				buf.recycle();
				return true;
			}));
	}

	private static long errorCodeOf(Exception e) {
		assertTrue("refused in this module's own terms: " + e, e instanceof Http3Exception);
		return ((Http3Exception) e).errorCode();
	}

	private void start(AsyncServlet servlet, Http3Settings clientSettings) {
		fixture = new Http3ClientFixture(loop)
			.withServlet(servlet)
			.withClientSettings(clientSettings)
			.start();
	}

	/**
	 * A real {@link Http3Client} against a peer that is a bare {@link Http3Connection} rather than an
	 * {@link Http3Server}: it reads the request through this module's own stream, so the client faces
	 * something well-behaved right up to the response, and then answers with bytes no encoder here would
	 * emit. {@code Http3MalformedResponseTest} uses the same shape for the same reason.
	 */
	private void startHostilePeer(Consumer<Http3RequestStream> answer) {
		hostileWire = new Http3WirePair(loop)
			.withServerHandlerFactory(quicConnection -> Http3Connection.builder(reactor(), quicConnection)
				.withRequestStreamListener(answer)
				.build()
				.startAndGetStreamManager())
			.withClientFactory(socket -> hostileClient = Http3Client.builder(reactor(), dns)
				.withSocket(socket)
				.withTlsEngineFactory(Http3TestTls::clientEngineFactory)
				.build())
			.connect();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}

	private Http3Client client() {
		return fixture.client();
	}
}
