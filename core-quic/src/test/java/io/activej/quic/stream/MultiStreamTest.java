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
import io.activej.promise.Promise;
import io.activej.promise.Promises;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T046 — SC-002: sixteen bidirectional streams open at once, written to <b>interleaved</b>, each
 * arriving byte-identical and in order, with no byte of one appearing on another (RFC 9000 §2).
 *
 * <h2>Why the writes are round-robin</h2>
 * Writing one stream to completion before starting the next would multiplex nothing: the frames would
 * leave in stream order, and a manager that simply concatenated everything it received would pass.
 * Every round of this test therefore writes one chunk to <em>every</em> stream before any stream gets
 * its second, so the wire genuinely carries frames of sixteen streams intermixed — which
 * {@link #everyStreamIsPartiallyReceivedBeforeAnyIsComplete()} asserts rather than assumes.
 *
 * <h2>How cross-contamination is made detectable</h2>
 * Two independent properties, because either alone is weak:
 * <ul>
 *   <li>the payload byte at offset {@code i} of stream {@code s} is a function of <b>both</b>
 *       {@code i} and {@code s}, so a reorder, a gap, a duplicate and a foreign byte all break the
 *       whole-array comparison;</li>
 *   <li>the first byte of every chunk is a literal <b>marker</b> {@code 0xA0 | s}, and chunk
 *       boundaries are known offsets — so a chunk spliced in from another stream is caught at exactly
 *       the granularity at which the interleaving happens, by a named assertion rather than by luck.</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2">RFC 9000 §2 — Streams</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 */
public final class MultiStreamTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** SC-002's "many concurrent streams", and well below the default {@code initialMaxStreamsBidi} of 100. */
	private static final int STREAM_COUNT = 16;

	/** One round's write per stream. Several frames' worth, so fragmentation is exercised too. */
	private static final int CHUNK_SIZE = 1024;

	private static final int ROUNDS = 8;

	/** 8 KiB per stream, 128 KiB in total — inside every default window, so no credit grant is needed. */
	private static final int PAYLOAD_SIZE = CHUNK_SIZE * ROUNDS;

	/** Bound on the drive loop; the whole exchange settles in a fraction of it. */
	private static final int MAX_DRIVE_ROUNDS = 400;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		serverReads.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
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

	// ---------------------------------------------------------------- payloads

	/** The marker that opens every chunk of stream {@code s}: distinct per stream, by construction. */
	private static byte marker(int s) {
		return (byte) (0xA0 | s);
	}

	/**
	 * Stream {@code s}'s whole payload. Every byte depends on its offset <i>and</i> on the stream, so no
	 * byte of one stream is a plausible byte of another at the same offset.
	 */
	private static byte[] payload(int s) {
		byte[] bytes = new byte[PAYLOAD_SIZE];
		for (int i = 0; i < PAYLOAD_SIZE; i++) {
			bytes[i] = i % CHUNK_SIZE == 0
				? marker(s)
				: (byte) (i * 31 + (i >>> 8) * 7 + s * 101 + 1);
		}
		return bytes;
	}

	private static ByteBuf chunk(byte[] payload, int round) {
		ByteBuf buf = ByteBufPool.allocate(CHUNK_SIZE);
		buf.put(payload, round * CHUNK_SIZE, CHUNK_SIZE);
		return buf;
	}

	// ---------------------------------------------------------------- driving

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

	private List<QuicStream> openStreams() {
		List<QuicStream> opened = new ArrayList<>();
		for (int s = 0; s < STREAM_COUNT; s++) {
			Promise<QuicStream> promise = clientManager.openBidirectional();
			assertTrue("the connection is established and the peer granted 100 streams",
				promise.isComplete());
			QuicStream stream = promise.getResult();
			assertNotNull(stream);
			// RFC 9000 §2.1: the client's bidirectional streams are 0, 4, 8, … — gapless and ascending.
			assertEquals(4L * s, stream.id());
			opened.add(stream);
		}
		assertEquals(STREAM_COUNT, clientManager.openStreamCount());
		return opened;
	}

	/** One chunk on every stream, in stream order, all before any stream's next chunk. */
	private void writeOneRoundToEveryStream(List<QuicStream> streams, List<byte[]> payloads, int round) {
		List<Promise<Void>> writes = new ArrayList<>();
		for (int s = 0; s < STREAM_COUNT; s++) {
			writes.add(streams.get(s).writer().accept(chunk(payloads.get(s), round)));
		}
		Promise<Void> all = Promises.all(writes);
		driveUntil(all::isComplete);
		assertTrue(String.valueOf(all.getException()), all.isResult());
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the transfer should have completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	/** The server stream carrying {@code streamId}, by identity rather than by arrival order. */
	private int indexOfServerStream(long streamId) {
		for (int i = 0; i < serverStreams.size(); i++) {
			if (serverStreams.get(i).id() == streamId) return i;
		}
		throw new AssertionError("the peer never announced stream " + streamId);
	}

	// ---------------------------------------------------------------- SC-002

	@Test
	public void sixteenInterleavedStreamsEachArriveByteIdenticalAndInOrder() {
		List<QuicStream> streams = openStreams();
		List<byte[]> payloads = new ArrayList<>();
		for (int s = 0; s < STREAM_COUNT; s++) {
			payloads.add(payload(s));
		}

		for (int round = 0; round < ROUNDS; round++) {
			writeOneRoundToEveryStream(streams, payloads, round);
		}
		List<Promise<Void>> finished = new ArrayList<>();
		for (QuicStream stream : streams) {
			finished.add(stream.writer().acceptEndOfStream());
		}
		Promise<Void> allFinished = Promises.all(finished);

		driveUntil(() -> allFinished.isComplete()
						 && serverReads.size() == STREAM_COUNT
						 && serverReads.stream().allMatch(Promise::isComplete));

		assertEquals("one listener call per peer-opened stream", STREAM_COUNT, serverStreams.size());
		Set<Long> announced = new HashSet<>();
		for (QuicStream stream : serverStreams) {
			assertTrue("no stream may be announced twice", announced.add(stream.id()));
		}

		for (int s = 0; s < STREAM_COUNT; s++) {
			int index = indexOfServerStream(4L * s);
			byte[] received = drain(serverReads.get(index));
			assertArrayEquals("stream " + s + " arrived corrupted, reordered or spliced",
				payloads.get(s), received);
			// The literal cross-contamination check: every chunk of this stream opens with this stream's
			// marker, so a chunk that came from another stream is caught by name and not by coincidence.
			for (int round = 0; round < ROUNDS; round++) {
				assertEquals("stream " + s + " chunk " + round + " carries another stream's marker",
					marker(s), received[round * CHUNK_SIZE]);
			}
		}

		assertEquals((long) STREAM_COUNT * PAYLOAD_SIZE, clientManager.bytesSent());
		assertEquals((long) STREAM_COUNT * PAYLOAD_SIZE, serverManager.bytesDelivered());
		assertEquals(STREAM_COUNT, clientManager.streamsOpenedLocally());
		assertEquals(STREAM_COUNT, serverManager.streamsAcceptedFromPeer());
	}

	@Test
	public void everyStreamIsPartiallyReceivedBeforeAnyIsComplete() {
		List<QuicStream> streams = openStreams();
		List<byte[]> payloads = new ArrayList<>();
		for (int s = 0; s < STREAM_COUNT; s++) {
			payloads.add(payload(s));
		}

		// One chunk each, and nothing else: this is what "interleaved" has to mean on the wire.
		writeOneRoundToEveryStream(streams, payloads, 0);
		driveUntil(() -> serverStreams.size() == STREAM_COUNT);

		for (QuicStream stream : serverStreams) {
			ReceivePart receivePart = stream.receivePart();
			assertNotNull(receivePart);
			assertEquals("every stream has exactly its first chunk, none of them more",
				CHUNK_SIZE, receivePart.highestOffsetReceived());
			assertNull("no stream has ended: the interleaving is real", receivePart.finalSize());
			assertFalse("...and nothing has been collected yet",
				serverReads.get(indexOfServerStream(stream.id())).isComplete());
		}
		assertEquals(STREAM_COUNT, serverManager.openStreamCount());

		// Finish them so the collectors are not left holding buffers when the connection closes.
		for (int round = 1; round < ROUNDS; round++) {
			writeOneRoundToEveryStream(streams, payloads, round);
		}
		List<Promise<Void>> finished = new ArrayList<>();
		for (QuicStream stream : streams) {
			finished.add(stream.writer().acceptEndOfStream());
		}
		Promise<Void> allFinished = Promises.all(finished);
		driveUntil(() -> allFinished.isComplete() && serverReads.stream().allMatch(Promise::isComplete));
		for (Promise<ByteBuf> read : serverReads) {
			ByteBuf buf = read.getResult();
			assertNotNull(buf);
			buf.recycle();
		}
	}

	// ---------------------------------------------------------------- both directions at once

	@Test
	public void everyStreamCarriesItsOwnReplyBack() {
		List<QuicStream> streams = openStreams();
		List<byte[]> payloads = new ArrayList<>();
		for (int s = 0; s < STREAM_COUNT; s++) {
			payloads.add(payload(s));
		}

		writeOneRoundToEveryStream(streams, payloads, 0);
		List<Promise<Void>> finished = new ArrayList<>();
		for (QuicStream stream : streams) {
			finished.add(stream.writer().acceptEndOfStream());
		}
		Promise<Void> allFinished = Promises.all(finished);
		driveUntil(() -> allFinished.isComplete()
						 && serverReads.size() == STREAM_COUNT
						 && serverReads.stream().allMatch(Promise::isComplete));

		// The server answers on each stream with that stream's own marker, and the client must see each
		// answer on the stream it belongs to.
		List<Promise<ByteBuf>> clientReads = new ArrayList<>();
		for (QuicStream stream : streams) {
			clientReads.add(stream.reader().toCollector(ByteBufs.collector()));
		}
		List<Promise<Void>> replies = new ArrayList<>();
		for (QuicStream serverStream : serverStreams) {
			int s = (int) (serverStream.id() / 4);
			ByteBuf reply = ByteBufPool.allocate(CHUNK_SIZE);
			reply.put(payloads.get(s), 0, CHUNK_SIZE);
			replies.add(serverStream.writer().accept(reply)
				.then(() -> serverStream.writer().acceptEndOfStream()));
		}
		Promise<Void> allReplied = Promises.all(replies);

		driveUntil(() -> allReplied.isComplete() && clientReads.stream().allMatch(Promise::isComplete));

		for (int s = 0; s < STREAM_COUNT; s++) {
			byte[] received = drain(clientReads.get(s));
			assertEquals(CHUNK_SIZE, received.length);
			assertEquals("the reply arrived on the wrong stream", marker(s), received[0]);
			byte[] expected = new byte[CHUNK_SIZE];
			System.arraycopy(payloads.get(s), 0, expected, 0, CHUNK_SIZE);
			assertArrayEquals(expected, received);
		}
	}
}
