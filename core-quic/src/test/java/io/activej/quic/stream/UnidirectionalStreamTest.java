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
 * T049 — a unidirectional stream has exactly one half on each endpoint, and touching the absent one is
 * an {@link IllegalStateException} rather than any kind of wire error (RFC 9000 §2.1, FR-007).
 * <p>
 * The distinction is the point. A missing half is a <b>caller</b> bug — the application asked a
 * send-only stream for a reader — and must never be confused with a peer's protocol violation, which is
 * a {@link io.activej.quic.connection.QuicTransportException} closing the connection. Both halves of
 * the rule are asserted here, on a real pair of endpoints rather than on a hand-built stream, so that
 * "which half exists" is decided by the same code path that a served connection uses.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3">RFC 9000 §3 — Stream States</a>
 */
public final class UnidirectionalStreamTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 200;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();
	private final List<QuicStream> clientStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> clientReads = new ArrayList<>();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		serverStreams.clear();
		serverReads.clear();
		clientStreams.clear();
		clientReads.clear();
		wire = new QuicWirePair();
		// Both sides listen: a unidirectional stream is announced to whichever endpoint did not open it,
		// and this test asserts that from both ends.
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					clientStreams.add(stream);
					clientReads.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build());
		wire.handshake(QuicConnectionSettings.create());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

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

	private static ByteBuf buf(String s) {
		byte[] array = s.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, array.length));
		buf.put(array);
		return buf;
	}

	private static String drain(Promise<ByteBuf> collected) {
		assertTrue("the transfer should have completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		String s = buf.getString(StandardCharsets.US_ASCII);
		buf.recycle();
		return s;
	}

	private QuicStream openClientUni() {
		Promise<QuicStream> opened = clientManager.openUnidirectional();
		assertTrue("the connection is established and the peer granted unidirectional credit",
			opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	// ---------------------------------------------------------------- the locally-opened half

	@Test
	public void aLocallyOpenedUnidirectionalStreamIsSendOnly() {
		QuicStream stream = openClientUni();

		// RFC 9000 §2.1 Table 1: the client's first unidirectional stream is id 2.
		assertEquals(2, stream.id());
		assertFalse(stream.isBidirectional());
		assertTrue(stream.isLocallyInitiated());

		assertTrue(stream.hasSendPart());
		assertFalse(stream.hasReceivePart());
		assertEquals(SendState.READY, stream.sendState());
		assertEquals("a total accessor reports NONE rather than throwing", ReceiveState.NONE,
			stream.receiveState());

		IllegalStateException e = assertThrows(IllegalStateException.class, stream::reader);
		assertTrue("the message must name the stream and say why it has no reader: " + e.getMessage(),
			e.getMessage().contains("2") && e.getMessage().contains("no receiving part"));
		// stopSending needs the half this endpoint does not have, and fails the same way — a caller bug,
		// distinct from the UnsupportedOperationException that user story 4 will replace it with.
		assertThrows(IllegalStateException.class, () -> stream.stopSending(0));
	}

	@Test
	public void unidirectionalOrdinalsAreCountedApartFromBidirectionalOnes() {
		QuicStream bidi = clientManager.openBidirectional().getResult();
		QuicStream uni = openClientUni();
		QuicStream secondUni = openClientUni();

		assertNotNull(bidi);
		// RFC 9000 §2.1: the four types are independent counters, so stream 0 and stream 2 are both
		// "the client's first" of their own type.
		assertEquals(0, bidi.id());
		assertEquals(2, uni.id());
		assertEquals(6, secondUni.id());
		assertTrue(bidi.hasReceivePart());
		assertFalse(uni.hasReceivePart());
	}

	// ---------------------------------------------------------------- the peer-opened half

	@Test
	public void aPeerOpenedUnidirectionalStreamIsReadOnly() {
		QuicStream clientStream = openClientUni();
		Promise<Void> written = clientStream.writer().accept(buf("push"));

		driveUntil(() -> written.isComplete() && !serverStreams.isEmpty());

		assertEquals(1, serverStreams.size());
		QuicStream serverStream = serverStreams.get(0);
		assertEquals(2, serverStream.id());
		assertFalse(serverStream.isBidirectional());
		assertFalse(serverStream.isLocallyInitiated());

		assertFalse(serverStream.hasSendPart());
		assertTrue(serverStream.hasReceivePart());
		assertEquals("a total accessor reports NONE rather than throwing", SendState.NONE,
			serverStream.sendState());
		assertEquals(ReceiveState.RECV, serverStream.receiveState());

		IllegalStateException e = assertThrows(IllegalStateException.class, serverStream::writer);
		assertTrue("the message must name the stream and say why it has no writer: " + e.getMessage(),
			e.getMessage().contains("2") && e.getMessage().contains("no sending part"));
		// reset() needs the half this endpoint does not have, and fails the same way.
		assertThrows(IllegalStateException.class, () -> serverStream.reset(0));

		Promise<Void> finished = clientStream.writer().acceptEndOfStream();
		driveUntil(() -> finished.isComplete() && serverReads.get(0).isComplete());
		assertEquals("push", drain(serverReads.get(0)));
	}

	@Test
	public void theServersOwnUnidirectionalStreamIsSendOnlyToo() {
		Promise<QuicStream> opened = serverManager.openUnidirectional();
		assertTrue(opened.isComplete());
		QuicStream serverStream = opened.getResult();
		assertNotNull(serverStream);

		// RFC 9000 §2.1 Table 1: the server's first unidirectional stream is id 3.
		assertEquals(3, serverStream.id());
		assertTrue(serverStream.hasSendPart());
		assertFalse(serverStream.hasReceivePart());
		assertThrows(IllegalStateException.class, serverStream::reader);

		Promise<Void> written = serverStream.writer().accept(buf("server push"))
			.then(() -> serverStream.writer().acceptEndOfStream());

		driveUntil(() -> written.isComplete() && !clientReads.isEmpty() && clientReads.get(0).isComplete());

		assertEquals(1, clientStreams.size());
		QuicStream received = clientStreams.get(0);
		assertEquals(3, received.id());
		assertFalse("the peer's unidirectional stream is read-only here as well", received.hasSendPart());
		assertTrue(received.hasReceivePart());
		assertThrows(IllegalStateException.class, received::writer);
		assertEquals("server push", drain(clientReads.get(0)));
	}
}
