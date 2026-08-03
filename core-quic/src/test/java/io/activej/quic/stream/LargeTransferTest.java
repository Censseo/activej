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
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
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
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T040 — SC-001 and SC-011: <b>four times</b> the initial flow-control window transferred over one
 * stream, byte for byte, with nothing buffered or outstanding beyond what was configured.
 * <p>
 * This is the motivating case of user story 2, and the one that only passes if every piece of the
 * story is present at once: the writer must be withheld rather than failed, the receiver must grant
 * credit off its own reader's consumption, the grant must reach the sender, and the sender must be
 * <em>retried</em> when it does. A missing retry does not show up as a wrong answer — it shows up as
 * a transfer that never finishes.
 * <p>
 * <b>Why the bound is a round counter and not {@code @Test(timeout = …)}.</b> JUnit's timeout runs the
 * test body on a second thread, and {@link ManualEventloop} installs itself as <i>this</i> thread's
 * reactor — so a wall-clock timeout here would break the fixture rather than guard it. The drive loop
 * counts rounds instead, which is the stronger guarantee anyway: nothing in this fixture blocks or
 * sleeps, so a stall is a bounded number of no-op rounds and fails in milliseconds, on any machine,
 * with a message naming what is missing.
 *
 * <h2>What the instrumentation asserts</h2>
 * After <b>every</b> datagram, not merely at the end:
 * <ul>
 *   <li>the receiver never holds more than one window of undelivered bytes — the whole point of
 *       advertising a window is that it bounds the peer's claim on this endpoint's memory;</li>
 *   <li>the sender never has more than {@code maxOutstandingStreamBytes} in flight (FR-019).</li>
 * </ul>
 * Both are read straight off the live controllers, so a bound that holds only on average — or only
 * once the transfer has settled — fails here.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 */
public final class LargeTransferTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Tens of kilobytes: small enough that 4× of it is still a quick test, large enough to fragment. */
	private static final int WINDOW = 32 * 1024;

	/** SC-001: four times the initial window, so the transfer cannot complete without credit grants. */
	private static final int PAYLOAD_SIZE = 4 * WINDOW;

	/** Bound on the drive loop. A deadlock must fail loudly and quickly, never hang the build. */
	private static final int MAX_DRIVE_ROUNDS = 2000;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	private long maxOutstandingStreamBytes;
	private long receiveWindow;

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		if (loop != null) loop.close();
	}

	// ---------------------------------------------------------------- fixture

	private void start(QuicConnectionSettings settings, long receiveWindow) throws MalformedDataException {
		loop = new ManualEventloop();
		this.maxOutstandingStreamBytes = settings.maxOutstandingStreamBytes();
		this.receiveWindow = receiveWindow;
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector(PAYLOAD_SIZE + 1)));
				})
				.build());
		wire.handshake(settings);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	/** The window a peer-opened bidirectional stream is given on the receiving side (RFC 9000 §18.2). */
	private static QuicConnectionSettings.Builder smallWindows() {
		return QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(WINDOW))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(WINDOW))
			.withInitialMaxStreamDataUni(MemSize.of(WINDOW));
	}

	/** A pattern whose every byte depends on its offset, so a reorder or a gap cannot go unnoticed. */
	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + (i >>> 16) * 13 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, bytes.length));
		buf.put(bytes);
		return buf;
	}

	/**
	 * Delivers one datagram at a time, checking the memory bounds after each, until {@code done} — or
	 * fails, rather than looping until the build times out.
	 */
	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			while (wire.deliverToServer()) {
				assertBoundsHold();
			}
			while (wire.deliverToClient()) {
				assertBoundsHold();
			}
			loop.tick();
			assertBoundsHold();
			if (done.getAsBoolean()) return;
			// The clock is what releases a delayed ACK, and an ACK is what frees outstanding budget.
			loop.advance(5);
			assertBoundsHold();
			if (done.getAsBoolean()) return;
		}
		fail("the transfer did not complete within " + MAX_DRIVE_ROUNDS + " rounds — a credit grant or " +
			 "the retry of a withheld writer is missing");
	}

	/** SC-011, checked continuously rather than at the end, where every bound trivially holds. */
	private void assertBoundsHold() {
		assertTrue("outstanding stream bytes (" + clientManager.outstandingStreamBytes() +
				   ") must never exceed maxOutstandingStreamBytes (" + maxOutstandingStreamBytes + ")",
			clientManager.outstandingStreamBytes() <= maxOutstandingStreamBytes);
		for (QuicStream stream : serverStreams) {
			ReceivePart receivePart = stream.receivePart();
			if (receivePart == null) continue;
			long buffered = receivePart.highestOffsetReceived() - receivePart.consumedOffset();
			assertTrue("buffered receive bytes (" + buffered + ") must never exceed the advertised " +
					   "window (" + receiveWindow + ")", buffered <= receiveWindow);
		}
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the transfer should have completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	// ---------------------------------------------------------------- SC-001

	@Test
	public void fourTimesTheInitialWindowTransfersByteIdentically() throws Exception {
		start(smallWindows().build(), WINDOW);
		byte[] payload = pattern(PAYLOAD_SIZE, 1);

		QuicStream clientStream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(clientStream.writer());

		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertArrayEquals(payload, drain(serverReads.get(0)));
		assertEquals(PAYLOAD_SIZE, clientManager.bytesSent());
		assertEquals(PAYLOAD_SIZE, serverManager.bytesDelivered());
		assertEquals(ReceiveState.DATA_READ, serverStreams.get(0).receiveState());
		// The transfer genuinely needed credit: a window this small cannot have been passed without it.
		assertTrue("the sender must have been held by a flow-control limit at least once",
			clientManager.timesBlockedByStreamLimit() + clientManager.timesBlockedByConnectionLimit() > 0);
	}

	@Test
	public void bothDirectionsCanRunPastTheirWindowsAtOnce() throws Exception {
		start(smallWindows().build(), WINDOW);
		byte[] request = pattern(PAYLOAD_SIZE, 2);
		byte[] response = pattern(PAYLOAD_SIZE, 3);

		QuicStream clientStream = openClientStream();
		Promise<Void> requestWritten = ChannelSuppliers.ofValue(buf(request)).streamTo(clientStream.writer());
		Promise<ByteBuf> responseRead = clientStream.reader().toCollector(ByteBufs.collector(PAYLOAD_SIZE + 1));

		driveUntil(() -> !serverStreams.isEmpty());
		QuicStream serverStream = serverStreams.get(0);
		Promise<Void> responseWritten = ChannelSuppliers.ofValue(buf(response)).streamTo(serverStream.writer());

		driveUntil(() -> requestWritten.isComplete() && responseWritten.isComplete()
						 && serverReads.get(0).isComplete() && responseRead.isComplete());

		assertArrayEquals(request, drain(serverReads.get(0)));
		assertArrayEquals(response, drain(responseRead));
	}

	// ---------------------------------------------------------------- SC-011, the local bound

	@Test
	public void aSmallOutstandingBudgetBoundsWhatIsInFlightWithoutStallingTheTransfer() throws Exception {
		// The windows are the defaults here, so what binds is the *local* budget rather than either
		// wire limit: the writer is resumed by acknowledgements, not by credit.
		start(QuicConnectionSettings.builder()
			.withMaxOutstandingStreamBytes(MemSize.kilobytes(16))
			.build(), QuicConnectionSettings.create().initialMaxStreamDataBidiRemote());
		byte[] payload = pattern(PAYLOAD_SIZE, 4);

		QuicStream clientStream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(clientStream.writer());

		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		assertArrayEquals(payload, drain(serverReads.get(0)));
		assertEquals(0, clientManager.outstandingStreamBytes());
	}
}
