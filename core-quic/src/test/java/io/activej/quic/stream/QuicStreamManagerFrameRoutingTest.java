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
import io.activej.common.exception.MalformedDataException;
import io.activej.common.recycle.Recyclers;
import io.activej.promise.Promise;
import io.activej.quic.codec.DataBlockedFrame;
import io.activej.quic.codec.DatagramFrame;
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.MaxStreamDataFrame;
import io.activej.quic.codec.MaxStreamsFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.QuicStreamLimitType;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StopSendingFrame;
import io.activej.quic.codec.StreamDataBlockedFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.codec.StreamsBlockedFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * T027 — how a {@code STREAM} frame becomes a {@link QuicStream}, asserted at the routing seam rather
 * than end-to-end (FR-004, FR-014).
 * <p>
 * Two properties a loopback test cannot see:
 * <ul>
 *   <li>the stream listener fires <b>exactly once</b> per peer-opened stream, and <b>before</b> any
 *       byte of it is readable — an application that hands the stream to a servlet must be able to
 *       attach a reader without racing the frame that opened it;</li>
 *   <li>the frame is <b>borrowed</b>: what the receiving part keeps is a {@code slice()} of the
 *       frame's payload, sharing its backing array, not a copy of it. The connection recycles the
 *       frame the moment {@code onFrame} returns, which this test reproduces literally.</li>
 * </ul>
 * The manager is attached to a genuinely handshaken connection — it needs the peer's transport
 * parameters to size a send window — and is then fed hand-built frames directly, which is what keeps
 * the assertions about routing rather than about the wire.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.8">RFC 9000 §19.8 — STREAM Frames</a>
 */
public final class QuicStreamManagerFrameRoutingTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager serverManager;

	private final List<QuicStream> accepted = new ArrayList<>();
	private Consumer<QuicStream> onAccepted = stream -> {};

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		accepted.clear();
		onAccepted = stream -> {};
		wire = new QuicWirePair();
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					accepted.add(stream);
					onAccepted.accept(stream);
				})
				.build());
		wire.handshake(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	/** A frame the <b>test</b> owns, exactly as the connection owns the one it hands to {@code onFrame}. */
	private static StreamFrame frame(long streamId, long offset, boolean fin, String data) {
		byte[] array = data.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
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

	private static String take(Promise<ByteBuf> promise) {
		assertTrue("the read should already be resolved", promise.isComplete());
		ByteBuf buf = promise.getResult();
		assertNotNull("expected data, got end-of-stream", buf);
		String s = buf.getString(StandardCharsets.US_ASCII);
		buf.recycle();
		return s;
	}

	// ---------------------------------------------------------------- opening

	@Test
	public void aStreamFrameForANewIdOpensTheStreamAndInvokesTheListenerOnce() throws QuicTransportException {
		assertEquals(0, serverManager.openStreamCount());

		route(frame(0, 0, false, "hello"));

		assertEquals(1, accepted.size());
		QuicStream stream = accepted.get(0);
		assertEquals(0, stream.id());
		assertTrue(stream.isBidirectional());
		assertFalse("stream 0 was opened by the client, and this manager is the server",
			stream.isLocallyInitiated());
		assertSame(stream, serverManager.streamOf(0));
		assertEquals(1, serverManager.openStreamCount());
		assertEquals(1, serverManager.streamsAcceptedFromPeer());
	}

	@Test
	public void furtherFramesForTheSameStreamDoNotInvokeTheListenerAgain() throws QuicTransportException {
		route(frame(0, 0, false, "hello "));
		route(frame(0, 6, false, "world"));
		route(frame(0, 11, true, ""));

		assertEquals("the listener is invoked exactly once per stream", 1, accepted.size());
		QuicStream stream = accepted.get(0);
		String first = take(stream.reader().get());
		String second = take(stream.reader().get());
		assertEquals("hello world", first + second);
		assertNull("end-of-stream after the FIN", stream.reader().get().getResult());
	}

	@Test
	public void theListenerRunsBeforeAnyByteOfTheStreamIsReadable() throws QuicTransportException {
		List<Boolean> readableWhenListenerRan = new ArrayList<>();
		List<Promise<ByteBuf>> readsTakenByTheListener = new ArrayList<>();
		onAccepted = stream -> {
			Promise<ByteBuf> read = stream.reader().get();
			readableWhenListenerRan.add(read.isComplete());
			readsTakenByTheListener.add(read);
		};

		route(frame(0, 0, false, "hello"));

		assertEquals(1, readableWhenListenerRan.size());
		assertFalse("no byte may be readable before the listener has seen the stream",
			readableWhenListenerRan.get(0));
		// ...and the read the listener parked is resolved by the very frame that opened the stream.
		assertEquals("hello", take(readsTakenByTheListener.get(0)));
	}

	@Test
	public void aFrameForAHigherOrdinalImplicitlyOpensEveryLowerOneOfItsType() throws QuicTransportException {
		// RFC 9000 §2.1: streams 0, 4 and 8 are client bidirectional ordinals 0, 1 and 2.
		route(frame(8, 0, false, "third"));

		assertEquals(3, accepted.size());
		assertEquals(List.of(0L, 4L, 8L), accepted.stream().map(QuicStream::id).toList());
		assertEquals(3, serverManager.openStreamCount());
		// Only the named stream carries data; the implicitly opened ones are merely open.
		assertEquals("third", take(serverManager.streamOf(8).reader().get()));
		assertFalse(serverManager.streamOf(0).reader().get().isComplete());
		assertFalse(serverManager.streamOf(4).reader().get().isComplete());
	}

	@Test
	public void aPeerInitiatedUnidirectionalStreamHasNoSendPart() throws QuicTransportException {
		// RFC 9000 §2.1: stream 2 is client unidirectional ordinal 0.
		route(frame(2, 0, false, "push"));

		QuicStream stream = serverManager.streamOf(2);
		assertNotNull(stream);
		assertFalse(stream.isBidirectional());
		assertTrue(stream.hasReceivePart());
		assertFalse(stream.hasSendPart());
		assertEquals(SendState.NONE, stream.sendState());
		assertThrows(IllegalStateException.class, stream::writer);
		assertEquals("push", take(stream.reader().get()));
	}

	// ---------------------------------------------------------------- borrowing, not copying

	@Test
	public void theFramesPayloadIsRetainedBySlicingRatherThanCopied() throws QuicTransportException {
		StreamFrame frame = frame(0, 0, false, "hello");
		byte[] backingArray = frame.data.array();
		try {
			serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			// The connection recycles the frame as soon as onFrame returns; the receiving part's slice is
			// what keeps the array alive.
			frame.recycle();
		}

		Promise<ByteBuf> read = accepted.get(0).reader().get();
		assertTrue(read.isComplete());
		ByteBuf delivered = read.getResult();
		assertNotNull(delivered);
		assertSame("FR-014: the payload must be sliced, never copied", backingArray, delivered.array());
		assertEquals("hello", delivered.getString(StandardCharsets.US_ASCII));
		delivered.recycle();
	}

	@Test
	public void aZeroLengthFrameOpensAStreamAndRetainsNothing() throws QuicTransportException {
		route(frame(0, 0, false, ""));

		assertEquals(1, accepted.size());
		assertFalse(accepted.get(0).reader().get().isComplete());
	}

	// ---------------------------------------------------------------- locally-opened streams

	@Test
	public void aLocallyOpenedStreamDoesNotInvokeTheStreamListener() {
		Promise<QuicStream> opened = serverManager.openBidirectional();

		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		// RFC 9000 §2.1: the server's first bidirectional stream is id 1.
		assertEquals(1, stream.id());
		assertTrue(stream.isLocallyInitiated());
		assertTrue(stream.hasSendPart());
		assertTrue(stream.hasReceivePart());
		assertEquals(SendState.READY, stream.sendState());
		assertEquals(ReceiveState.RECV, stream.receiveState());
		assertEquals("the listener announces the peer's streams, never ours", 0, accepted.size());
		assertEquals(1, serverManager.streamsOpenedLocally());
	}

	@Test
	public void anOpenIssuedBeforeEstablishmentIsWithheldUntilTheHandshakeCompletes() throws MalformedDataException {
		// FR-042: a stream id cannot be allocated before the peer has said how many streams it will
		// accept, so the request waits rather than failing. Needs its own pair — the one in setUp is
		// already established.
		QuicStreamManager[] clientManager = new QuicStreamManager[1];
		QuicWirePair earlyWire = new QuicWirePair();
		earlyWire.withClientFrameHandlerFactory(connection -> clientManager[0] =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		try {
			earlyWire.startClient(QuicConnectionSettings.create());
			Promise<QuicStream> opened = clientManager[0].openBidirectional();
			assertFalse("an open issued before establishment is withheld, not failed", opened.isComplete());

			earlyWire.acceptServer(QuicConnectionSettings.create());
			earlyWire.pump();

			assertTrue("the withheld open resolves on establishment", opened.isComplete());
			QuicStream stream = opened.getResult();
			assertNotNull(stream);
			assertEquals(0, stream.id());
		} finally {
			earlyWire.close();
		}
	}

	// ---------------------------------------------------------------- the frame set itself (FR-037, T099)

	/**
	 * FR-037 and [data-model.md](../../../../../../../../specs/004-quic-streams/data-model.md): this
	 * layer handles exactly nine frame types — {@code STREAM}, {@code RESET_STREAM},
	 * {@code STOP_SENDING}, {@code MAX_DATA}, {@code MAX_STREAM_DATA}, {@code MAX_STREAMS},
	 * {@code DATA_BLOCKED}, {@code STREAM_DATA_BLOCKED}, {@code STREAMS_BLOCKED} — no more, no fewer.
	 * <p>
	 * "No fewer" is what this method asserts: every one of the nine is <em>accepted</em> rather than
	 * falling through to the unknown-frame branch, which would end the connection on ordinary traffic.
	 */
	@Test
	public void allNineHandledFrameTypesAreAccepted() throws QuicTransportException {
		QuicFrame[] theNine = {
			frame(0, 0, false, "x"),                                              // STREAM        §19.8
			new ResetStreamFrame(4, 0, 0),                                        // RESET_STREAM  §19.4
			new StopSendingFrame(1, 0),                                           // STOP_SENDING  §19.5
			new MaxDataFrame(1 << 20),                                            // MAX_DATA      §19.9
			new MaxStreamDataFrame(1, 1 << 20),                                   // MAX_STREAM_DATA §19.10
			new MaxStreamsFrame(50, QuicStreamLimitType.BIDIRECTIONAL),           // MAX_STREAMS   §19.11
			new DataBlockedFrame(1 << 20),                                        // DATA_BLOCKED  §19.12
			new StreamDataBlockedFrame(0, 1 << 20),                               // STREAM_DATA_BLOCKED §19.13
			new StreamsBlockedFrame(50, QuicStreamLimitType.BIDIRECTIONAL),       // STREAMS_BLOCKED §19.14
		};
		// Stream 1 is server-initiated, so this server must have opened it for STOP_SENDING and
		// MAX_STREAM_DATA to be legal identifiers at all (RFC 9000 §19.5, §19.10).
		assertTrue(serverManager.openBidirectional().isResult());

		for (QuicFrame frame : theNine) {
			try {
				serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame);
			} finally {
				Recyclers.recycle(frame);
			}
		}

		// The two streams the run above opened, drained so the leak rule has nothing to report.
		for (QuicStream stream : accepted) {
			Promise<ByteBuf> read = stream.reader().get();
			if (read.isResult() && read.getResult() != null) read.getResult().recycle();
		}
	}

	/**
	 * FR-037's other half, and the {@code DATAGRAM} row of the spec's Out of Scope list: RFC 9221
	 * datagrams are feature 06's, so this endpoint advertises no support for them and a peer sending one
	 * has exceeded what it was granted. It must be <b>rejected</b> rather than silently absorbed —
	 * absorbing it would leave the peer believing an unreliable datagram service exists.
	 */
	@Test
	public void aDatagramFrameIsAProtocolViolationAndNotSilentlyAbsorbed() {
		ByteBuf payload = ByteBufPool.allocate(4);
		payload.put(new byte[]{1, 2, 3, 4});
		DatagramFrame frame = new DatagramFrame(payload);
		try {
			QuicTransportException e = assertThrows(QuicTransportException.class,
				() -> serverManager.onFrame(wire.server(), EncryptionLevel.ONE_RTT, frame));
			assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
			assertTrue(e.reasonPhrase(), e.reasonPhrase().contains("DatagramFrame"));
		} finally {
			frame.recycle();
		}
	}
}
