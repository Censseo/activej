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
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T104 — <b>FR-064a</b>: the stream layer carries the arrival {@link EncryptionLevel} forward, so a
 * receiving endpoint can tell a stream whose data arrived in a <b>0-RTT</b> packet from one whose data
 * arrived at 1-RTT.
 *
 * <h2>Why {@link QuicStream#isEarlyData()} does not answer this</h2>
 * That flag is latched from {@code QuicConnection.isSendingEarlyData()}, which is a property of the
 * <em>sending</em> side and therefore {@code false} on a server for ever. The endpoint that has to
 * apply an early-data policy is precisely the server, and what it needs to know is where the bytes it
 * is about to act on <em>came from</em> — spec FR-064a's "its data arrived at {@code ZERO_RTT}".
 * {@link QuicStream#arrivalLevel()} is that, and the two accessors are deliberately independent: this
 * test asserts both on the same pair of streams so a future change cannot quietly collapse one into
 * the other.
 *
 * <h2>Why a real 0-RTT handshake rather than an injected frame</h2>
 * The level a frame arrives at is decided by the packet it was protected with, three layers below
 * this one. An injected frame would let the test choose the answer it is asserting; a resumed
 * connection carrying genuine 0-RTT packets does not.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1 — 0-RTT</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-12.3">RFC 9000 §12.3 — Packet Numbers</a>
 */
public final class StreamArrivalLevelTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int WINDOW = 64 * 1024;

	private static final int MAX_DRIVE_ROUNDS = 400;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		serverStreams.clear();
		serverReads.clear();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.close();
	}

	@Test
	public void aStreamWhoseDataArrivedInEarlyDataReportsZeroRttAndAnOrdinaryOneReportsOneRtt() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, windowOf(WINDOW));
		assertNotNull("the first handshake issued no ticket", ticket);

		resume(ticket, keys);

		wire.startClient(windowOf(WINDOW));
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("a resumption's remembered limits must let a stream open before the handshake",
			opened.isComplete());
		QuicStream earlyStream = opened.getResult();
		assertNotNull(earlyStream);
		earlyStream.writer().accept(text("early"));

		wire.acceptServer(windowOf(WINDOW));
		int zeroRttPackets = ZeroRttWire.deliverToServerCountingZeroRtt(wire);
		assertTrue("the request never travelled in a 0-RTT packet, so this test proves nothing",
			zeroRttPackets >= 1);

		assertEquals(1, serverStreams.size());
		QuicStream serverEarly = serverStreams.get(0);
		assertEquals("FR-064a: data that arrived in a 0-RTT packet must report ZERO_RTT",
			EncryptionLevel.ZERO_RTT, serverEarly.arrivalLevel());
		assertEquals("the arrival level is about the receiving side; the send-side latch is a server's false",
			false, serverEarly.isEarlyData());

		driveUntil(() -> wire.client().state() == QuicConnectionState.ESTABLISHED);
		assertEquals("an established connection must not revoke what already arrived",
			EncryptionLevel.ZERO_RTT, serverEarly.arrivalLevel());

		Promise<QuicStream> second = clientManager.openBidirectional();
		assertTrue(second.isComplete());
		QuicStream lateStream = second.getResult();
		assertNotNull(lateStream);
		lateStream.writer().accept(text("ordinary"));
		driveUntil(() -> serverStreams.size() >= 2);

		QuicStream serverLate = serverStreams.get(1);
		assertEquals("a request sent after the handshake must report 1-RTT arrival",
			EncryptionLevel.ONE_RTT, serverLate.arrivalLevel());
		assertEquals(EncryptionLevel.ZERO_RTT, serverEarly.arrivalLevel());

		earlyStream.writer().acceptEndOfStream();
		lateStream.writer().acceptEndOfStream();
		driveUntil(() -> serverReads.size() >= 2 && serverReads.stream().allMatch(Promise::isComplete));
		assertEquals("early", drain(serverReads.get(0)));
		assertEquals("ordinary", drain(serverReads.get(1)));

		// The FIN above travelled at 1-RTT on a stream that began at 0-RTT: a stream any part of whose
		// bytes could be a replay stays reportable as one.
		assertEquals("a 1-RTT continuation must not overwrite a 0-RTT arrival",
			EncryptionLevel.ZERO_RTT, serverEarly.arrivalLevel());
		assertEquals(EncryptionLevel.ONE_RTT, serverLate.arrivalLevel());
	}

	/**
	 * The locally-opened half of the same rule: nothing has arrived on a stream this endpoint opened and
	 * has not yet been written to, so it reports no arrival level at all rather than guessing one.
	 */
	@Test
	public void aStreamNothingHasArrivedOnHasNoArrivalLevel() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, windowOf(WINDOW));
		assertNotNull(ticket);

		resume(ticket, keys);
		wire.startClient(windowOf(WINDOW));

		QuicStream clientStream = clientManager.openBidirectional().getResult();
		assertNotNull(clientStream);
		assertNull("a stream nothing has arrived on must report no arrival level",
			clientStream.arrivalLevel());
		assertTrue("the client's own send-side latch is the accessor that was already there",
			clientStream.isEarlyData());

		wire.acceptServer(windowOf(WINDOW));
		driveUntil(() -> wire.client().state() == QuicConnectionState.ESTABLISHED);
	}

	// ---------------------------------------------------------------- helpers

	private void resume(QuicSessionTicket ticket, QuicTicketKeys keys) {
		wire = new QuicWirePair();
		wire.withServerTlsConfig(builder -> ZeroRttWire.acceptingEarlyData(builder, keys))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(true))
			.withClientRememberedTransportParameters(ticket.transportParameters())
			.withClientFrameHandlerFactory(connection -> clientManager =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build())
			.withServerFrameHandlerFactory(connection -> QuicStreamManager
				.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build());
	}

	private static QuicConnectionSettings windowOf(int bytes) {
		return QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiLocal(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiRemote(MemSize.bytes(bytes))
			.withInitialMaxStreamDataUni(MemSize.bytes(bytes))
			.build();
	}

	private static ByteBuf text(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}

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

	private static String drain(Promise<ByteBuf> collected) {
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		String value = buf.getString(StandardCharsets.US_ASCII);
		buf.recycle();
		return value;
	}
}
