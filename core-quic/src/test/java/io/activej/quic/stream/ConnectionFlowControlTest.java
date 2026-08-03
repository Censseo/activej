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
import io.activej.quic.codec.DataBlockedFrame;
import io.activej.quic.codec.MaxDataFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamDataBlockedFrame;
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
 * T037 — user story 2, scenario 3: the <b>connection-level</b> limit binding while the stream still
 * has credit of its own, and the FR-027 rule that keeps a persistently blocked sender from flooding
 * the peer.
 * <p>
 * The two limits are independent, and this is the case that separates them: two streams sharing one
 * connection window can exhaust it between them while neither has spent its own. What must then
 * happen is that the second writer is withheld, {@code DATA_BLOCKED} is announced <b>once</b> for
 * that limit value however many writers are waiting on it and however many times they retry, and a
 * {@code MAX_DATA} raising the limit releases every one of them.
 * <p>
 * The server carries a plain recording handler rather than a stream manager: a peer that never grants
 * credit back is what keeps the sender blocked for as long as the assertions need.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.1">RFC 9000 §4.1 — Data Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.9">RFC 9000 §19.9 — MAX_DATA Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.12">RFC 9000 §19.12 — DATA_BLOCKED Frames</a>
 */
public final class ConnectionFlowControlTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The peer's whole connection window, and — deliberately — each stream's window too. */
	private static final int WINDOW = 40 * 1024;

	/** Records the limit frames the client sends, which is the entire point of the fixture. */
	private static final class RecordingHandler implements QuicFrameHandler {
		final List<Long> dataBlocked = new ArrayList<>();
		final List<Long> streamDataBlocked = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			if (frame instanceof DataBlockedFrame blocked) dataBlocked.add(blocked.limit);
			if (frame instanceof StreamDataBlockedFrame blocked) streamDataBlocked.add(blocked.limit);
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private final RecordingHandler serverHandler = new RecordingHandler();

	@Before
	public void setUp() throws MalformedDataException {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(serverHandler);
		wire.startClient(QuicConnectionSettings.create());
		// Every per-stream window is the whole connection window, so a single stream can never be the
		// one that binds first: only the two of them together can.
		wire.acceptServer(QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(WINDOW))
			.build());
		wire.pump();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	private static ByteBuf bytes(int length, int seed) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) (i * 31 + (i >>> 8) * 7 + seed));
		}
		return buf;
	}

	private QuicStream open() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	private static SendPart sendPartOf(QuicStream stream) {
		SendPart sendPart = stream.sendPart();
		assertNotNull(sendPart);
		return sendPart;
	}

	// ---------------------------------------------------------------- scenario 3

	@Test
	public void theConnectionLimitBindsWhileTheStreamStillHasCreditOfItsOwn() {
		QuicStream a = open();
		QuicStream b = open();

		Promise<Void> writtenA = a.writer().accept(bytes(25 * 1024, 1));
		wire.pump();
		assertTrue("25 KiB is within both windows", writtenA.isResult());

		Promise<Void> writtenB = b.writer().accept(bytes(25 * 1024, 2));
		wire.pump();

		assertFalse("only 15 KiB of the connection window was left", writtenB.isComplete());
		SendPart sendB = sendPartOf(b);
		assertEquals(15 * 1024, sendB.writeOffset());
		assertTrue("the stream limit is not what bound here",
			sendB.flowControl().available() > 0);
		assertEquals("the connection window is spent to the byte", WINDOW,
			sendPartOf(a).writeOffset() + sendB.writeOffset());

		assertEquals("DATA_BLOCKED carries the limit that bound", List.of((long) WINDOW),
			serverHandler.dataBlocked);
		assertEquals("the stream was never the binding limit, so it is never announced",
			List.of(), serverHandler.streamDataBlocked);
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());
		assertEquals(0, clientManager.timesBlockedByStreamLimit());
	}

	// ---------------------------------------------------------------- FR-027

	@Test
	public void asecondWriterBlockedAtTheSameLimitDoesNotAnnounceItAgain() {
		QuicStream a = open();
		QuicStream b = open();

		a.writer().accept(bytes(25 * 1024, 1));
		Promise<Void> writtenB = b.writer().accept(bytes(25 * 1024, 2));
		wire.pump();
		assertFalse(writtenB.isComplete());
		assertEquals(List.of((long) WINDOW), serverHandler.dataBlocked);

		// A second writer runs into the very same limit: the peer already knows.
		Promise<Void> writtenAgainA = a.writer().accept(bytes(10 * 1024, 3));
		wire.pump();

		assertFalse(writtenAgainA.isComplete());
		assertEquals("FR-027: one announcement per distinct limit value, connection-wide",
			List.of((long) WINDOW), serverHandler.dataBlocked);
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());
	}

	@Test
	public void aRaisedLimitReleasesEveryWithheldWriterAndTheNextBlockIsAnnouncedAfresh()
		throws QuicTransportException {
		QuicStream a = open();
		QuicStream b = open();

		Promise<Void> writtenA = a.writer().accept(bytes(25 * 1024, 1));
		Promise<Void> writtenB = b.writer().accept(bytes(25 * 1024, 2));
		wire.pump();
		assertTrue(writtenA.isResult());
		assertFalse(writtenB.isComplete());

		// MAX_DATA lifts the connection window by 20 KiB — enough for B's remaining 10 KiB.
		clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT, new MaxDataFrame(WINDOW + 20 * 1024));
		wire.pump();

		assertTrue(String.valueOf(writtenB.getException()), writtenB.isResult());
		assertEquals(25 * 1024, sendPartOf(b).writeOffset());
		assertEquals(List.of((long) WINDOW), serverHandler.dataBlocked);

		// Blocked again, at a *different* value this time, so it is announced again.
		Promise<Void> writtenAgain = a.writer().accept(bytes(25 * 1024, 3));
		wire.pump();

		assertFalse(writtenAgain.isComplete());
		assertEquals(List.of((long) WINDOW, (long) (WINDOW + 20 * 1024)), serverHandler.dataBlocked);
		assertEquals(2, clientManager.timesBlockedByConnectionLimit());
	}

	@Test
	public void aStaleMaxDataFrameNeitherLowersTheLimitNorResumesAnything() throws QuicTransportException {
		QuicStream a = open();
		QuicStream b = open();

		a.writer().accept(bytes(25 * 1024, 1));
		Promise<Void> writtenB = b.writer().accept(bytes(25 * 1024, 2));
		wire.pump();
		assertFalse(writtenB.isComplete());

		clientManager.onFrame(wire.client(), EncryptionLevel.ONE_RTT, new MaxDataFrame(WINDOW / 2));
		wire.pump();

		assertFalse("RFC 9000 §4.1: a limit below one already in force is ignored (FR-026)",
			writtenB.isComplete());
		assertEquals(15 * 1024, sendPartOf(b).writeOffset());
	}

	@Test
	public void bothLimitsCanBindAtOnceAndBothAreAnnounced() {
		// One stream, writing more than the whole connection window: its own window is the same size,
		// so the two limits are reached by the same byte.
		QuicStream a = open();

		Promise<Void> written = a.writer().accept(bytes(2 * WINDOW, 4));
		wire.pump();

		assertFalse(written.isComplete());
		assertEquals(WINDOW, sendPartOf(a).writeOffset());
		assertEquals(List.of((long) WINDOW), serverHandler.dataBlocked);
		assertEquals(List.of((long) WINDOW), serverHandler.streamDataBlocked);
		assertEquals(1, clientManager.timesBlockedByConnectionLimit());
		assertEquals(1, clientManager.timesBlockedByStreamLimit());
	}
}
