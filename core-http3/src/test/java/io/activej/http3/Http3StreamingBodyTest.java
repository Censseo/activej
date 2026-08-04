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
import io.activej.common.MemSize;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T082 / FR-053, FR-056, SC-012, US6 scenarios 1–2: a body many times the QUIC stream flow-control
 * window, streamed in each direction through the CSP body channels of {@link Http3RequestStream}.
 * <p>
 * Both ends are real {@link Http3RequestStream}s over one in-process QUIC connection — the client half
 * ({@code sendRequest} / {@code receiveResponse}) against the server half ({@code receiveRequest} /
 * {@code sendResponse}) — with no {@code Http3Server} or {@code Http3Client} above them, since what is
 * asserted here is what the two body channels do with the bytes.
 *
 * <h2>How a small window proves the property</h2>
 * Both endpoints advertise a {@link #WINDOW}-byte per-stream receive window, so the sender may never
 * have more than that many unread bytes outstanding: the transport simply refuses to turn further
 * bytes into {@code STREAM} frames until the reader consumes and credit is granted back. A layer that
 * collected the whole body before handing it to QUIC — or before handing it to the application —
 * would therefore either deadlock or need the entire body's worth of window up front. That the
 * transfer completes at all under a window a twelfth of the body is the evidence SC-012 asks for.
 * <p>
 * On top of that, {@link Gauge} measures it directly: it records the peak of <i>produced minus
 * consumed</i>, the bytes that entered one end of the pipeline and have not yet come out of the other.
 * That gap is everything the H3 and QUIC layers are holding at that instant, and
 * {@link #MAX_IN_FLIGHT} bounds it by the window plus a chunk on each side — a constant, while the
 * body it is measured against triples.
 * <p>
 * <b>Ownership</b>: every chunk this test produces is handed to the outbound body consumer, which owns
 * it from then on; every chunk it drains it recycles itself. {@code ByteBufRule} is the assertion that
 * neither side kept one.
 */
public final class Http3StreamingBodyTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * The per-stream flow-control window both endpoints advertise. Small on purpose — every byte past
	 * it has to wait for the receiver to consume, which is the condition this test exists to create.
	 */
	private static final MemSize WINDOW = MemSize.kilobytes(16);

	/** One body chunk, and therefore one DATA frame. */
	private static final int CHUNK = 4096;

	private static final int SHORT_BODY = 4 * WINDOW.toInt();
	private static final int LONG_BODY = 12 * WINDOW.toInt();

	/**
	 * The bound on bytes in flight: a full window of unread data, plus the chunk that may be in hand at
	 * the boundary. Independent of the body — which is the whole point (SC-012). The measured peak is
	 * the window exactly, for a body of {@link #SHORT_BODY} and for one of {@link #LONG_BODY} alike.
	 */
	private static final long MAX_IN_FLIGHT = WINDOW.toLong() + CHUNK;

	private static final String URL = "https://" + Http3TestTls.SERVER_NAME + "/upload";

	private final List<Http3RequestStream> serverStreams = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;

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
	public void aRequestBodyManyWindowsLongStreamsToTheServer() {
		connect();

		Peaks peaks = exchange(LONG_BODY, 0);

		assertInFlightBounded("US6 scenario 1: the request direction", peaks.request());
	}

	@Test
	public void aResponseBodyManyWindowsLongStreamsToTheClient() {
		connect();

		Peaks peaks = exchange(0, LONG_BODY);

		assertInFlightBounded("US6 scenario 2: the response direction", peaks.response());
	}

	@Test
	public void peakRetainedBytesDoNotGrowWithTheBody() {
		connect();

		Peaks small = exchange(SHORT_BODY, SHORT_BODY);
		Peaks large = exchange(LONG_BODY, LONG_BODY);

		// SC-012: the same bound holds for a body three times as long, on two streams of one connection.
		assertInFlightBounded("the request direction of the short body", small.request());
		assertInFlightBounded("the response direction of the short body", small.response());
		assertInFlightBounded("the request direction of the long body", large.request());
		assertInFlightBounded("the response direction of the long body", large.response());

		// And not merely bounded: tripling the body does not move the peak at all, bar a chunk of slack.
		assertTrue("SC-012: the request direction peaked at " + small.request() + " for " + SHORT_BODY +
				   " and at " + large.request() + " for " + LONG_BODY,
			large.request() <= small.request() + CHUNK);
		assertTrue("SC-012: the response direction peaked at " + small.response() + " for " + SHORT_BODY +
				   " and at " + large.response() + " for " + LONG_BODY,
			large.response() <= small.response() + CHUNK);
	}

	// ---------------------------------------------------------------- the exchange

	/** What one exchange retained at its worst moment, in each direction. */
	private record Peaks(long request, long response) {}

	/**
	 * One complete exchange on a fresh request stream: a request body of {@code requestBodySize} bytes
	 * up and a response body of {@code responseBodySize} bytes down, each generated one chunk at a time
	 * and verified one chunk at a time. Either size may be 0, which sends a message with no body.
	 */
	private Peaks exchange(int requestBodySize, int responseBodySize) {
		Gauge up = new Gauge();
		Gauge down = new Gauge();
		int streamIndex = serverStreams.size();

		Http3RequestStream client = requestStream(wire.openNow(wire.clientStreams().openBidirectional()));
		Promise<Void> requestSent = client.sendRequest(HttpRequest.post(URL)
			.withBodyStream(body(requestBodySize, up))
			.build());

		wire.driveUntil(() -> serverStreams.size() > streamIndex);
		Http3RequestStream server = serverStreams.get(streamIndex);

		Promise<HttpRequest> request = server.receiveRequest();
		wire.driveUntil(request::isComplete);
		assertTrue("the request head arrived: " + request, request.isResult());

		Drain requestBody = drain(request.getResult().takeBodyStream(), up);
		wire.driveUntil(() -> requestBody.complete && requestSent.isComplete());
		assertTrue("the request body was written: " + requestSent, requestSent.isResult());
		requestBody.assertReceived(requestBodySize);

		Promise<Void> responseSent = server.sendResponse(HttpResponse.ok200()
			.withBodyStream(body(responseBodySize, down))
			.build());
		Promise<HttpResponse> response = client.receiveResponse();
		wire.driveUntil(response::isComplete);
		assertTrue("the response head arrived: " + response, response.isResult());
		assertEquals(200, response.getResult().getCode());

		Drain responseBody = drain(response.getResult().takeBodyStream(), down);
		wire.driveUntil(() -> responseBody.complete && responseSent.isComplete());
		assertTrue("the response body was written: " + responseSent, responseSent.isResult());
		responseBody.assertReceived(responseBodySize);

		return new Peaks(up.peak, down.peak);
	}

	private void connect() {
		QuicConnectionSettings quic = QuicConnectionSettings.builder()
			// bidi_local bounds what the server may send on the stream the client opened; bidi_remote
			// bounds what the client may send on it. Both ends set both, so both directions are held to
			// the same window whichever way the body travels.
			.withInitialMaxStreamDataBidiLocal(WINDOW)
			.withInitialMaxStreamDataBidiRemote(WINDOW)
			.build();
		wire = new Http3WirePair(loop)
			.withServerSettings(quic)
			.withClientSettings(quic)
			.withServerStreamListener(stream -> serverStreams.add(requestStream(stream)))
			.connect();
	}

	private static Http3RequestStream requestStream(QuicStream stream) {
		return Http3RequestStream.builder(Reactor.getCurrentReactor(), stream)
			.withSettings(Http3Settings.create())
			.build();
	}

	// ---------------------------------------------------------------- the payload

	/** Deterministic, position-dependent, and cheap: byte {@code i} of the body is a function of {@code i}. */
	private static byte patternByte(long index) {
		return (byte) (index * 31 + 7);
	}

	/**
	 * The body as a lazy {@link ChannelSupplier}: one chunk is allocated per {@code get()}, so nothing
	 * of it exists before the layer below asks for it. The consumer owns every chunk it is handed.
	 */
	private ChannelSupplier<ByteBuf> body(int size, Gauge gauge) {
		long[] offset = {0};
		return ChannelSuppliers.ofSupplier(() -> {
			if (offset[0] == size) return null;
			int length = (int) Math.min(CHUNK, size - offset[0]);
			ByteBuf buf = ByteBufPool.allocate(length);
			for (int i = 0; i < length; i++) {
				buf.put(patternByte(offset[0] + i));
			}
			offset[0] += length;
			gauge.produced(length);
			return buf;
		});
	}

	/**
	 * Reads a body to its end, checking each chunk against the pattern at its absolute offset and
	 * recycling it — so the whole body is compared byte for byte without any of it being held.
	 */
	private Drain drain(ChannelSupplier<ByteBuf> body, Gauge gauge) {
		Drain drain = new Drain();
		Promises.repeat(() -> body.get()
				.map(buf -> {
					if (buf == null) return false;
					try {
						int length = buf.readRemaining();
						for (int i = 0; i < length; i++) {
							if (buf.peek(i) != patternByte(drain.received + i) && drain.mismatchAt < 0) {
								drain.mismatchAt = drain.received + i;
							}
						}
						drain.received += length;
						gauge.consumed(length);
					} finally {
						buf.recycle();
					}
					return true;
				}))
			.whenComplete(($, e) -> {
				drain.complete = true;
				drain.failure = e;
			});
		return drain;
	}

	/** One body being read, and what reading it found. */
	private static final class Drain {
		private long received;
		private long mismatchAt = -1;
		private boolean complete;
		private @Nullable Exception failure;

		void assertReceived(long expected) {
			assertNull("the body was read without failing", failure);
			assertEquals("the body arrived whole", expected, received);
			assertEquals("every byte is the byte that was sent", -1, mismatchAt);
		}
	}

	/**
	 * Bytes that entered one end of the pipeline and have not yet left the other — everything the H3
	 * and QUIC layers are holding for this direction — at its peak.
	 */
	private static final class Gauge {
		private long produced;
		private long consumed;
		private long peak;

		void produced(int bytes) {
			produced += bytes;
			observe();
		}

		void consumed(int bytes) {
			consumed += bytes;
			observe();
		}

		private void observe() {
			peak = Math.max(peak, produced - consumed);
		}
	}

	private static void assertInFlightBounded(String what, long peak) {
		assertTrue("SC-012: " + what + " retained " + peak + " bytes at its peak, over the " +
				   MAX_IN_FLIGHT + "-byte bound",
			peak <= MAX_IN_FLIGHT);
	}
}
