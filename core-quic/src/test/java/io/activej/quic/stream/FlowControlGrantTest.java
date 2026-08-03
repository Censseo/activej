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

package io.activej.quic.stream;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.MaxStreamDataFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T038 — user story 2, scenario 4: the receiver grants credit <b>proactively</b>, on its own reader's
 * consumption, and by an exact amount.
 * <p>
 * Two properties, and the first is the one that is easy to get subtly wrong (FR-025, clarification Q5):
 * <ul>
 *   <li>the trigger is the <b>application having read</b> at least half the advertised window — not a
 *       {@code STREAM_DATA_BLOCKED} or {@code DATA_BLOCKED} from the peer, and not the bytes merely
 *       having <i>arrived</i>. This test never sends a blocked frame, and asserts that arrival alone
 *       grants nothing;</li>
 *   <li>the new absolute limit is exactly {@code consumed + window} — asserted as a literal number,
 *       because "some increase" would pass for a great many wrong implementations.</li>
 * </ul>
 * The receiver is a {@link QuicStreamManager} on a genuinely handshaken connection, fed hand-built
 * {@code STREAM} frames; the sender is a recording handler that captures the limit frames the receiver
 * puts on the wire.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.2">RFC 9000 §4.2 — Increasing Flow Control Limits</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.9">RFC 9000 §19.9 — MAX_DATA Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.10">RFC 9000 §19.10 — MAX_STREAM_DATA Frames</a>
 */
public final class FlowControlGrantTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The receiver's per-stream window: what it advertises, and what a grant restores above `consumed`. */
	private static final int STREAM_WINDOW = 4096;

	/** The receiver's connection-wide window. Twice the stream window, so the two thresholds differ. */
	private static final int CONNECTION_WINDOW = 8192;

	/** Records the limit frames the receiver sends back. */
	private static final class RecordingHandler implements QuicFrameHandler {
		final List<Long> maxData = new ArrayList<>();
		final List<long[]> maxStreamData = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			if (frame instanceof MaxDataFrame max) maxData.add(max.maximum);
			if (frame instanceof MaxStreamDataFrame max) maxStreamData.add(new long[]{max.streamId, max.maximum});
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager serverManager;
	private final RecordingHandler clientHandler = new RecordingHandler();
	private final List<QuicStream> accepted = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		accepted.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandler(clientHandler);
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(accepted::add)
				.build());
		wire.startClient(QuicConnectionSettings.create());
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(CONNECTION_WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(STREAM_WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(STREAM_WINDOW))
			.build());
		wire.pump();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	/** A frame the <b>test</b> owns, exactly as the connection owns the one it hands to {@code onFrame}. */
	private static StreamFrame frame(long streamId, long offset, boolean fin, int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) (offset + i));
		}
		return new StreamFrame(streamId, offset, fin, buf);
	}

	/** Routes and then recycles, which is precisely what {@code QuicConnection.openAndHandle} does. */
	private void route(StreamFrame frame) throws QuicTransportException {
		try {
			serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			frame.recycle();
		}
	}

	/** Takes exactly one slice from the reader; the caller owns nothing afterwards. */
	private static int read(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		assertTrue("the bytes have already arrived, so the read resolves at once", read.isComplete());
		ByteBuf buf = read.getResult();
		if (buf == null) return -1;
		int length = buf.readRemaining();
		buf.recycle();
		return length;
	}

	private ReceivePart receivePartOf(QuicStream stream) {
		ReceivePart receivePart = stream.receivePart();
		assertNotNull(receivePart);
		return receivePart;
	}

	// ---------------------------------------------------------------- the reader is the trigger

	@Test
	public void bytesMerelyArrivingGrantNothing() throws Exception {
		route(frame(0, 0, false, STREAM_WINDOW));
		wire.pump();

		assertEquals("the window is full but nothing has been read: no credit is due",
			List.of(), clientHandler.maxStreamData);
		assertEquals(List.of(), clientHandler.maxData);
		assertEquals(STREAM_WINDOW, receivePartOf(accepted.get(0)).maxDataOffset());
	}

	@Test
	public void aReadBelowTheHalfWindowThresholdGrantsNothing() throws Exception {
		// 1000 of 4096 read leaves 3096 of window above the consumed offset — more than half.
		route(frame(0, 0, false, 1000));
		wire.pump();
		assertEquals(1000, read(accepted.get(0)));
		wire.pump();

		assertEquals(List.of(), clientHandler.maxStreamData);
		assertEquals(List.of(), clientHandler.maxData);
	}

	// ---------------------------------------------------------------- the exact grant (FR-025)

	@Test
	public void theStreamGrantIsExactlyConsumedPlusTheConfiguredWindow() throws Exception {
		// 3000 of 4096 read leaves 1096 of window — at or below half, so a grant is due.
		route(frame(0, 0, false, 3000));
		wire.pump();
		QuicStream stream = accepted.get(0);
		assertEquals(3000, read(stream));
		wire.pump();

		assertEquals("one grant, for one stream", 1, clientHandler.maxStreamData.size());
		long[] granted = clientHandler.maxStreamData.get(0);
		assertEquals("the grant names the stream it belongs to", 0, granted[0]);
		assertEquals("FR-025: the new absolute limit is consumed + window",
			3000 + STREAM_WINDOW, granted[1]);
		assertEquals("what is advertised and what is enforced must be the same number",
			3000 + STREAM_WINDOW, receivePartOf(stream).maxDataOffset());
		// The connection window is twice as large, so its own threshold is not reached yet.
		assertEquals(List.of(), clientHandler.maxData);
		assertEquals("the peer never said it was blocked; the grant is proactive (FR-025)",
			0, serverManager.timesBlockedByStreamLimit());
	}

	@Test
	public void theConnectionGrantIsExactlyConsumedPlusTheConfiguredWindow() throws Exception {
		// A whole stream window read is half of the connection window: both thresholds are met at once.
		route(frame(0, 0, false, STREAM_WINDOW));
		wire.pump();
		assertEquals(STREAM_WINDOW, read(accepted.get(0)));
		wire.pump();

		assertEquals(List.of(STREAM_WINDOW + (long) CONNECTION_WINDOW), clientHandler.maxData);
		assertEquals(1, clientHandler.maxStreamData.size());
		assertEquals(STREAM_WINDOW + (long) STREAM_WINDOW, clientHandler.maxStreamData.get(0)[1]);
	}

	@Test
	public void creditIsGrantedOncePerWindowRatherThanOncePerRead() throws Exception {
		route(frame(0, 0, false, 1500));
		route(frame(0, 1500, false, 1500));
		wire.pump();
		QuicStream stream = accepted.get(0);

		// First slice: 1500 of 4096 consumed, still above half a window left.
		assertEquals(1500, read(stream));
		wire.pump();
		assertEquals(List.of(), clientHandler.maxStreamData);

		// Second slice: 3000 consumed, the threshold is crossed exactly once.
		assertEquals(1500, read(stream));
		wire.pump();
		assertEquals(1, clientHandler.maxStreamData.size());
		assertEquals(3000 + STREAM_WINDOW, clientHandler.maxStreamData.get(0)[1]);
	}

	// ---------------------------------------------------------------- the grant is what lets more arrive

	@Test
	public void theGrantedLimitIsWhatTheReceiverThenAccepts() throws Exception {
		route(frame(0, 0, false, 3000));
		wire.pump();
		QuicStream stream = accepted.get(0);
		read(stream);
		wire.pump();
		assertEquals(3000 + STREAM_WINDOW, receivePartOf(stream).maxDataOffset());

		// Bytes that would have been a FLOW_CONTROL_ERROR a moment ago are now within the window.
		route(frame(0, 3000, false, 2000));

		assertEquals(5000, receivePartOf(stream).highestOffsetReceived());
		assertEquals(2000, read(stream));
	}
}
