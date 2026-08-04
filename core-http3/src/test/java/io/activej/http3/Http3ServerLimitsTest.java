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
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestPeer;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T067 / FR-030, FR-046a, FR-054: the three bounds a peer can push a server past, each with the exact
 * RFC 9114 §8.1 code it must be aborted with.
 * <p>
 * Every bound is asserted from the <b>client's</b> side, as the application error code the abort
 * carries, because that is the only thing a peer can actually observe — and it is what a conformance
 * suite would check.
 */
public final class Http3ServerLimitsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

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

	@Test
	public void aFieldSectionOverTheBoundIsExcessiveLoad() {
		start(request -> HttpResponse.ok200().toPromise(),
			Http3Settings.builder().withMaxFieldSectionSize(MemSize.bytes(128)).build());

		List<Field> fields = Http3TestBytes.requestFields("GET", "/big");
		fields.add(new Field("x-padding", "p".repeat(4096)));

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, abortCodeOf(peer.request(fields, null)));
	}

	/**
	 * T116: the HEADERS half of T110's amplification — a field section declaring 50 MB that never arrives,
	 * against a server left at its <b>default</b> settings, where the reader's bound used to be
	 * {@code max(maxFieldSectionSize, maxBodySize)} = 100 MB and this length passed it.
	 * <p>
	 * {@link #aFieldSectionOverTheBoundIsExcessiveLoad()} above is the ordinary case: a field section that
	 * is really there and really too big, refused once it has arrived. This is the adversarial one, and
	 * the two differ in the only way that matters here — a HEADERS frame cannot be read in instalments, so
	 * a declared length that is not checked <i>before</i> the payload is allocated is a declared length a
	 * peer can spend a frame header on and have reserved in full.
	 * {@code Http3FrameReaderHeadersBoundTest} is where the memory itself is measured.
	 */
	@Test
	public void anOverDeclaredFieldSectionIsRefusedBeforeItIsAllocated() {
		start(request -> HttpResponse.ok200().toPromise(), Http3Settings.create());

		Promise<Http3TestPeer.Response> request = peer.open().then(stream -> {
			ChannelConsumer<ByteBuf> writer = stream.writer();
			// The frame header, 64 bytes of field section, and then silence.
			return writer
				.accept(Http3TestBytes.overDeclaredHeadersFrame(MemSize.megabytes(50).toLong(), new byte[64]))
				.then(() -> peer.readResponse(stream));
		});

		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, abortCodeOf(request));
		assertEquals("the stream is gone rather than holding a reservation", 0, server.activeRequests());
	}

	@Test
	public void aBodyOverTheBoundResetsTheStream() {
		start(request -> request.loadBody().map(body -> HttpResponse.ok200().build()),
			Http3Settings.builder().withMaxBodySize(MemSize.bytes(16)).build());

		byte[] body = "x".repeat(1024).getBytes(UTF_8);
		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, abortCodeOf(peer.post("/upload", body)));
	}

	/**
	 * T110: the amplification both reviewers of phases 6–12 found — a DATA frame header declaring 50 MB
	 * of body that never arrives, which the reader used to allocate in full the moment the Length varint
	 * parsed, on every one of the {@code maxConcurrentRequestStreams} streams a peer may hold open at
	 * once.
	 * <p>
	 * Asserted here from the servlet's side, since that is what an over-declared frame is observable as
	 * end to end: the bytes that did arrive reach the body channel while the frame they belong to is
	 * still unfinished, instead of being held back for a completion that never comes.
	 * {@code Http3FrameReaderIncrementalDataTest} is where the memory itself is measured.
	 */
	@Test
	public void anOverDeclaredDataFrameDeliversWhatArrivedInsteadOfWaitingForTheRest() {
		List<Integer> instalments = new ArrayList<>();
		start(request -> {
			ChannelSupplier<ByteBuf> body = request.takeBodyStream();
			return Promises.repeat(() -> body.get()
					.map(buf -> {
						if (buf == null) return false;
						instalments.add(buf.readRemaining());
						buf.recycle();
						return true;
					}))
				.map($ -> HttpResponse.ok200().build());
		}, Http3Settings.create());

		byte[] sent = new byte[20 * 1024];
		Promise<QuicStream> opened = peer.open();
		wire.driveUntil(opened::isComplete);
		QuicStream stream = opened.getResult();
		ChannelConsumer<ByteBuf> writer = stream.writer();
		Promise<Void> written = writer.accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("POST", "/lie")))
			.then(() -> writer.accept(Http3TestBytes.overDeclaredDataFrame(MemSize.megabytes(50).toLong(), sent)));
		// And then the peer goes quiet, holding the stream open. Everything that follows is the server
		// deciding what to do with 20 KiB of a body that claims to be 50 MB.
		wire.driveUntil(written::isComplete);
		wire.pump();
		loop.tickUntilQuiet();

		long received = instalments.stream().mapToLong(Integer::longValue).sum();
		assertTrue("the servlet saw " + received + " bytes of a frame that never finishes", received > 0);
		assertTrue("and not the " + sent.length + " bytes it would have taken to fill a chunk twice over",
			received < sent.length);
		assertEquals("the request is still in flight rather than answered or aborted", 1, server.activeRequests());
	}

	@Test
	public void aStalledRequestIsCancelledWhenTheRequestTimeoutElapses() {
		List<Promise<ByteBuf>> servletBodies = new ArrayList<>();
		start(request -> {
			// Never completes: the peer opened the stream, sent its headers and then went quiet.
			Promise<ByteBuf> body = request.loadBody();
			servletBodies.add(body);
			return body.map(loaded -> HttpResponse.ok200().build());
		}, Http3Settings.builder().withRequestTimeout(Duration.ofMillis(100)).build());

		Promise<Http3TestPeer.Response> request =
			peer.requestWithoutFin(Http3TestBytes.requestFields("POST", "/stall"));
		wire.driveUntil(() -> !servletBodies.isEmpty());
		assertFalse("the request is in flight, waiting on a body that never arrives", servletBodies.get(0).isComplete());

		wire.advance(200);
		wire.driveUntil(request::isComplete);

		assertEquals(Http3Errors.H3_REQUEST_CANCELLED, abortCodeOf(request));
		assertTrue("the servlet's in-flight promise was failed rather than left pending",
			servletBodies.get(0).isException());
		assertEquals(1, server.requestsTimedOut());
		assertEquals(0, server.activeRequests());
	}

	// ---------------------------------------------------------------- harness

	/**
	 * Drives {@code request} to its failure and returns the application error code the abort carried.
	 * <p>
	 * Either half of the abort may be the one the peer notices first — {@code RESET_STREAM} fails its
	 * read, {@code STOP_SENDING} fails its write — and both carry the same code, so the assertion is on
	 * the code rather than on which verb won the race.
	 */
	private long abortCodeOf(Promise<?> request) {
		wire.driveUntil(request::isComplete);
		assertTrue("the request was aborted rather than answered: " + request, request.isException());
		Exception e = request.getException();
		if (e instanceof QuicStreamResetException reset) return reset.applicationErrorCode();
		if (e instanceof QuicStreamStopSendingException stopped) return stopped.applicationErrorCode();
		throw new AssertionError("the abort did not reach the peer as a stream abort: " + e, e);
	}

	private void start(AsyncServlet servlet, Http3Settings settings) {
		wire = new Http3WirePair(loop);
		peer = new Http3TestPeer(wire);
		wire.withServerFactory(socket -> {
				server = Http3Server.builder(reactor(), servlet)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withSettings(settings)
					.build();
				server.listen();
				return server;
			})
			.connect();
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
