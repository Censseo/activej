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
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicVarInts;
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * T117 / RFC 9114 §9, FR-023, FR-062: a peer may send as many unknown or GREASE frames as it likes on a
 * request stream, in any position, and each must be discarded — <b>without costing a stack frame</b>.
 * <p>
 * In ActiveJ a completed {@link Promise} runs its continuation synchronously, so a de-framing loop
 * written as "ask for the next frame, and if it is one I ignore, ask again" is real recursion whenever
 * the bytes are already in hand — which they are, for every frame after the first in a buffer. A GREASE
 * frame is two bytes on the wire (a one-byte type varint and a zero length), so a peer buys roughly four
 * stack frames per two bytes it sends: tens of thousands of them in a single stream's worth of buffered
 * input, and the {@code StackOverflowError} that follows is not a stream error — it escapes to the
 * eventloop's fatal-error handler and takes every other connection on that reactor with it.
 * <p>
 * The fix is that {@code Http3RequestStream.nextFrame} discards them inside its own loop, so what
 * follows is asserted with a flood far past any legitimate use and, more to the point, far past what a
 * stack would survive if the recursion were still there.
 * <p>
 * No {@code EventloopRule}: {@link ManualEventloop} installs its own reactor on a hand-driven clock.
 */
public final class Http3GreaseFloodTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * Frames per flood. Sized against the stack rather than against the wire: at ~4 frames of recursion
	 * each this is ~80 000 deep, comfortably past the ~10 000 an 512 KiB default thread stack holds, so
	 * the test fails loudly if the skip ever moves back out of {@code nextFrame}'s loop. Two bytes each,
	 * so 40 KB — well inside one QUIC stream's flow-control window, and nothing here is slow.
	 */
	private static final int FLOOD = 20_000;

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
	public void aFloodOfGreaseFramesBeforeHeadersIsDiscardedWithoutRecursing() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		writer.accept(greaseFlood(FLOOD))
			.then(() -> writer.accept(Http3TestBytes.headersFrame(Http3TestBytes.requestFields("GET", "/"))))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Promise<HttpRequest> received = serverStreams.get(0).receiveRequest();
		wire.driveUntil(received::isComplete);

		assertTrue("the request behind the flood decoded: " + received, received.isResult());
		assertEquals("/", received.getResult().getPath());
	}

	@Test
	public void aFloodOfGreaseFramesBetweenBodyFramesIsDiscardedWithoutRecursing() {
		connect();
		QuicStream clientStream = openClientStream();
		ChannelConsumer<ByteBuf> writer = clientStream.writer();

		List<Field> fields = Http3TestBytes.requestFields("POST", "/echo");
		fields.add(new Field("content-length", "10"));
		writer.accept(Http3TestBytes.headersFrame(fields))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("hello".getBytes(UTF_8))))
			.then(() -> writer.accept(greaseFlood(FLOOD)))
			.then(() -> writer.accept(Http3TestBytes.dataFrame("world".getBytes(UTF_8))))
			.then(() -> writer.accept(null));

		wire.driveUntil(() -> !serverStreams.isEmpty());
		Http3RequestStream requestStream = serverStreams.get(0);
		Promise<HttpRequest> received = requestStream.receiveRequest();
		wire.driveUntil(received::isComplete);
		assertTrue("the request decoded: " + received, received.isResult());

		Promise<ByteBuf> body = received.getResult().loadBody();
		wire.driveUntil(body::isComplete);

		assertTrue("the body across the flood loaded: " + body, body.isResult());
		assertEquals("helloworld", body.getResult().getString(UTF_8));
		assertEquals("the flood contributed nothing to the body", 10, requestStream.bodyBytesReceived());
		assertEquals(Http3RequestStream.State.COMPLETE, requestStream.state());

		// FR-057a: the request, and what loadBody() attached to it, stay this stream's — answering is
		// what releases them, and a stream already COMPLETE will not be aborted into doing it later.
		Promise<Void> sent = requestStream.sendResponse(HttpResponse.ok200().build());
		wire.driveUntil(sent::isComplete);
		assertTrue("the response was written: " + sent, sent.isResult());
	}

	/**
	 * One buffer of {@code count} zero-length GREASE frames, {@code 0x1f * N + 0x21} per RFC 9114 §9 —
	 * written as one write on purpose, so they are one buffer's worth of already-in-hand input rather
	 * than {@code count} separate reads with a reactor tick between them.
	 */
	private static ByteBuf greaseFlood(int count) {
		ByteBuf buf = ByteBufPool.allocate(count * 4);
		for (int n = 0; n < count; n++) {
			QuicVarInts.write(buf, 0x1fL * (n % 3) + 0x21L);
			QuicVarInts.write(buf, 0);
		}
		return buf;
	}

	private void connect() {
		wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> serverStreams.add(Http3RequestStream.builder(reactor(), stream)
				.withSettings(Http3Settings.create())
				.build()))
			.connect();
	}

	private QuicStream openClientStream() {
		return wire.openNow(wire.clientStreams().openBidirectional());
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
