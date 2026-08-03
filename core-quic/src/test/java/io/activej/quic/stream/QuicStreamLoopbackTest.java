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
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.MalformedDataException;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
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
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T028 — user story 1 end to end: ordered, reliable, de-duplicated bytes over one bidirectional
 * stream between two in-process peers, over feature 03's {@code QuicWirePair} and
 * {@code ManualEventloop}.
 * <p>
 * Acceptance scenarios 1 and 2: the client opens a bidirectional stream, writes three buffers and an
 * end-of-data marker; the server's listener is invoked once with a stream carrying those bytes
 * concatenated in write order, followed by end-of-stream exactly once; and a reply travels back on
 * the same stream. {@code ByteBufRule} carries the "no leak" half of the independent test.
 * <p>
 * <b>Payload size.</b> The pattern is 200 KiB — several hundred kilobytes, as the story asks, and
 * deliberately below the 256 KiB default {@code initialMaxStreamDataBidiLocal}/{@code Remote} and the
 * 1 MiB default {@code initialMaxData}. Transferring <i>more</i> than the initial window is user
 * story 2's subject: it needs credit grants and the resumption of a withheld write, which this phase
 * does not yet implement, so a larger payload here would exercise machinery that does not exist
 * rather than the machinery that does.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2">RFC 9000 §2 — Streams</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3">RFC 9000 §3 — Stream States</a>
 */
public final class QuicStreamLoopbackTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** 200 KiB: "several hundred kilobytes", comfortably under every default window. */
	private static final int PAYLOAD_SIZE = 200 * 1024;

	/** Bound on the drive loop; the whole transfer settles in well under a tenth of this. */
	private static final int MAX_DRIVE_ROUNDS = 400;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	/**
	 * Whether the listener attaches a collector to each accepted stream. On for every test but the one
	 * that reads slice by slice — which is the point of scenario 2 and cannot share a collector.
	 */
	private boolean collectOnAccept = true;

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		serverReads.clear();
		collectOnAccept = true;
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					if (collectOnAccept) {
						serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
					}
				})
				.build());
		wire.handshake(QuicConnectionSettings.create());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	/** A pattern whose every byte depends on its offset, so a reorder or a gap cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf slice(byte[] source, int from, int to) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, to - from));
		buf.put(source, from, to - from);
		return buf;
	}

	/**
	 * Delivers datagrams and lets timers fire until {@code done}, or fails.
	 * <p>
	 * Both halves are needed: {@code pump()} moves what has already been flushed, while the clock is
	 * what releases a delayed ACK — and it is an ACK that opens the congestion window for the next
	 * burst, so a pump-only loop stalls after the first window.
	 */
	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			wire.pump();
			loop.tick();
			if (done.getAsBoolean()) return;
			loop.advance(5);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the transfer should have completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		// RFC 9000 §2.1: the client's first bidirectional stream is id 0.
		assertEquals(0, stream.id());
		assertTrue(stream.isLocallyInitiated());
		return stream;
	}

	// ---------------------------------------------------------------- scenario 1

	@Test
	public void threeBuffersAndAnEndMarkerArriveConcatenatedInWriteOrder() {
		byte[] payload = pattern(PAYLOAD_SIZE, 0);
		int third = PAYLOAD_SIZE / 3;

		QuicStream clientStream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValues(
				slice(payload, 0, third),
				slice(payload, third, 2 * third),
				slice(payload, 2 * third, PAYLOAD_SIZE))
			.streamTo(clientStream.writer());

		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertEquals("the listener is invoked once per peer-opened stream", 1, serverStreams.size());
		QuicStream serverStream = serverStreams.get(0);
		assertEquals(0, serverStream.id());
		assertFalse(serverStream.isLocallyInitiated());
		assertTrue(serverStream.isBidirectional());

		assertArrayEquals(payload, drain(serverReads.get(0)));
		assertEquals(ReceiveState.DATA_READ, serverStream.receiveState());
		// Data Sent or Data Recvd: whether the last acknowledgement has arrived by the time the reader
		// finished is a matter of ACK scheduling, not of this story.
		assertTrue(String.valueOf(clientStream.sendState()),
			clientStream.sendState() == SendState.DATA_SENT || clientStream.sendState() == SendState.DATA_RECVD);
		assertEquals(PAYLOAD_SIZE, clientManager.bytesSent());
		assertEquals(PAYLOAD_SIZE, serverManager.bytesDelivered());
	}

	// ---------------------------------------------------------------- scenario 2

	@Test
	public void eachReadReturnsTheNextContiguousBytesAndEndOfStreamComesExactlyOnce() {
		collectOnAccept = false;
		byte[] payload = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.US_ASCII);

		QuicStream clientStream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValues(
				slice(payload, 0, 10),
				slice(payload, 10, 25),
				slice(payload, 25, payload.length))
			.streamTo(clientStream.writer());

		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty());

		ChannelSupplier<ByteBuf> reader = serverStreams.get(0).reader();
		ByteBufs assembled = new ByteBufs();
		int endOfStreamObservations = 0;
		for (int i = 0; i < 32; i++) {
			Promise<ByteBuf> read = reader.get();
			driveUntil(read::isComplete);
			ByteBuf buf = read.getResult();
			if (buf == null) {
				endOfStreamObservations++;
				break;
			}
			assertTrue("a read must never return an empty slice", buf.readRemaining() > 0);
			assembled.add(buf);
		}

		assertEquals("end-of-stream is reported exactly once", 1, endOfStreamObservations);
		ByteBuf all = assembled.takeRemaining();
		try {
			assertArrayEquals(payload, all.getArray());
		} finally {
			all.recycle();
		}
		assertEquals(ReceiveState.DATA_READ, serverStreams.get(0).receiveState());
	}

	// ---------------------------------------------------------------- both directions

	@Test
	public void theServerRepliesOnTheSameBidirectionalStream() {
		byte[] request = pattern(64 * 1024, 1);
		byte[] response = pattern(96 * 1024, 2);

		QuicStream clientStream = openClientStream();
		Promise<Void> requestWritten = ChannelSuppliers.ofValue(slice(request, 0, request.length))
			.streamTo(clientStream.writer());
		Promise<ByteBuf> responseRead = clientStream.reader().toCollector(ByteBufs.collector());

		driveUntil(() -> requestWritten.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());
		assertArrayEquals(request, drain(serverReads.get(0)));

		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> responseWritten = ChannelSuppliers.ofValue(slice(response, 0, response.length))
			.streamTo(serverStream.writer());

		driveUntil(() -> responseWritten.isComplete() && responseRead.isComplete());
		assertArrayEquals(response, drain(responseRead));
	}

	// ---------------------------------------------------------------- release (FR-006)

	@Test
	public void aFullyTransferredAndDrainedStreamIsReleasedByBothPeers() {
		QuicStream clientStream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(slice(pattern(1024, 3), 0, 1024))
			.streamTo(clientStream.writer());
		Promise<ByteBuf> clientRead = clientStream.reader().toCollector(ByteBufs.collector());

		driveUntil(() -> !serverStreams.isEmpty());
		// A bidirectional stream is released only when both halves are terminal, so the server must end
		// its own send half too.
		Promise<Void> replied = serverStreams.get(0).writer().acceptEndOfStream();

		driveUntil(() -> written.isComplete() && replied.isComplete()
						 && serverReads.get(0).isComplete() && clientRead.isComplete()
						 && serverManager.openStreamCount() == 0 && clientManager.openStreamCount() == 0);

		assertEquals(1024, drain(serverReads.get(0)).length);
		assertEquals(0, drain(clientRead).length);
		assertNull(serverManager.streamOf(0));
		assertNull(clientManager.streamOf(0));
	}
}
