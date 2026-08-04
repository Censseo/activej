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
import io.activej.bytebuf.ByteBufs;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3TestBytes.bytes;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T059 / FR-018: with a local QPACK dynamic-table capacity of 0, the peer's encoder stream may carry
 * exactly one instruction and its decoder stream may carry none — and this endpoint opens neither of
 * its own.
 * <p>
 * The instruction bytes are RFC 9204 §4.3/§4.4 pattern prefixes: {@code 001xxxxx} Set Dynamic Table
 * Capacity, {@code 1xxxxxxx} Insert With Name Reference (encoder) or Section Acknowledgment
 * (decoder), {@code 01xxxxxx} Insert With Literal Name or Stream Cancellation.
 */
public final class Http3QpackStreamTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** RFC 9204 §4.3.1 Set Dynamic Table Capacity, capacity 0 — {@code 001} then a zero 5-bit prefix. */
	private static final int SET_DYNAMIC_TABLE_CAPACITY_ZERO = 0x20;

	private final List<Http3WirePair> wires = new ArrayList<>();
	private final List<QuicStream> streamsWeOpened = new ArrayList<>();
	private final ByteBufs received = new ByteBufs();

	private ManualEventloop loop;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		for (Http3WirePair wire : wires) {
			wire.close();
		}
		loop.tickUntilQuiet();
		received.recycle();
		loop.close();
	}

	@Test
	public void theEncoderStreamAcceptsSetDynamicTableCapacityZero() {
		Peer peer = connect();
		peer.openControlStream();
		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), bytes(SET_DYNAMIC_TABLE_CAPACITY_ZERO));

		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);
		peer.wire.advance(50);

		assertEquals(State.READY, peer.h3.state());
		assertEquals("no connection error", -1, peer.h3.closedWithErrorCode());
	}

	@Test
	public void theEncoderStreamRejectsANonZeroCapacity() {
		Peer peer = connect();
		peer.openControlStream();
		// 001 00001 — Set Dynamic Table Capacity 1, above the 0 we advertised.
		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), bytes(0x21));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void theEncoderStreamRejectsAnInsertInstruction() {
		Peer peer = connect();
		peer.openControlStream();
		// 1 T index(6+) — Insert With Name Reference, which needs a dynamic table we do not have.
		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), bytes(0xC0, 0x00));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void theDecoderStreamRejectsEveryInstruction() {
		Peer peer = connect();
		peer.openControlStream();
		// 1 stream-id(7+) — Section Acknowledgment, which we can never owe, having inserted nothing.
		peer.openUnidirectional(Http3StreamType.QPACK_DECODER.code(), bytes(0x80));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.QPACK_DECODER_STREAM_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void aSecondEncoderStreamIsAStreamCreationError() {
		Peer peer = connect();
		peer.openControlStream();
		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), bytes(SET_DYNAMIC_TABLE_CAPACITY_ZERO));
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), bytes(SET_DYNAMIC_TABLE_CAPACITY_ZERO));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.H3_STREAM_CREATION_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void aPeerResetOfTheEncoderStreamIsAClosedCriticalStream() {
		Peer peer = connect();
		peer.openControlStream();
		QuicStream encoder = peer.openUnidirectional(
			Http3StreamType.QPACK_ENCODER.code(), bytes(SET_DYNAMIC_TABLE_CAPACITY_ZERO));
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		encoder.reset(Http3Errors.H3_NO_ERROR);

		peer.driveUntilClosed();
		assertEquals(Http3Errors.H3_CLOSED_CRITICAL_STREAM, peer.h3.closedWithErrorCode());
	}

	@Test
	public void weOpenNoQpackStreamOfOurOwn() throws Exception {
		Peer peer = connect();
		peer.openControlStream();
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);
		// Long enough for a lazily-opened stream to have shown up if there were one.
		peer.wire.advance(200);

		assertEquals("only the control stream (FR-018)", 1, streamsWeOpened.size());
		assertTrue(received.hasRemaining());
		ByteBuf buf = received.takeRemaining();
		try {
			assertEquals(Http3StreamType.CONTROL.code(), QuicVarInts.read(buf));
		} finally {
			buf.recycle();
		}
		assertNotEquals(State.CLOSED, peer.h3.state());
	}

	// ---------------------------------------------------------------- helpers

	private Peer connect() {
		Http3Connection[] captured = new Http3Connection[1];
		Http3WirePair wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> {
				streamsWeOpened.add(stream);
				Http3TestBytes.collect(stream, received);
			})
			.withServerHandlerFactory(connection -> {
				captured[0] = Http3Connection.create(reactor(), connection);
				return captured[0].streamManager();
			})
			.connect();
		wires.add(wire);
		return new Peer(wire, captured[0]);
	}

	private record Peer(Http3WirePair wire, Http3Connection h3) {
		QuicStream openControlStream() {
			return openUnidirectional(Http3StreamType.CONTROL.code(), settingsFrame(new long[]{0x01}, new long[]{0}));
		}

		QuicStream openUnidirectional(long streamType, ByteBuf payload) {
			QuicStream stream = wire.openNow(wire.clientStreams().openUnidirectional());
			stream.writer().accept(concat(streamHeader(streamType), payload));
			return stream;
		}

		void driveUntilClosed() {
			wire.driveUntil(() -> h3.state() == State.CLOSED);
		}
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
