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
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * T064 — early data is bounded by the <b>remembered</b> flow-control limits, not by this connection's
 * (RFC 9000 §7.4.1, spec FR-053/FR-057). The excess is held where every other over-limit write is
 * held — behind the stream and connection flow controllers — and released once the handshake supplies
 * the real limits.
 * <p>
 * The shape is deliberately asymmetric: the ticket remembers a <b>small</b> window and this
 * connection's server advertises a <b>large</b> one. A client that ignored the remembered value and
 * used a default would send more than the ticket permitted; one that used this connection's value
 * could not, because it has not arrived yet. Only obeying the remembered value produces the number
 * this test asserts.
 * <p>
 * The complementary rule is what makes the release safe rather than lucky: RFC 9000 §7.4.1 forbids
 * the server to <i>reduce</i> those limits, so establishment is always an increase — never a
 * retraction that would oblige the sender to un-send bytes it has already put on the wire.
 * {@code RememberedTransportParametersTest} is where that half is enforced.
 * <p>
 * {@code ByteBufRule} under the strict Surefire harness is the "recycled exactly once" half: a double
 * recycle throws at the offending call site, a missing one fails the class.
 */
public final class ZeroRttFlowControlTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What the ticket remembers, and therefore the whole of the client's early-data budget. */
	private static final int REMEMBERED_WINDOW = 4 * 1024;

	/** What this connection's server actually advertises — comfortably more. */
	private static final int ESTABLISHED_WINDOW = 64 * 1024;

	private static final int PAYLOAD_SIZE = 16 * 1024;

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
	public void earlyDataIsBoundedByTheRememberedLimitAndTheRemainderFollowsTheHandshake() throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, windowOf(REMEMBERED_WINDOW));
		assertNotNull("the first handshake issued no ticket", ticket);
		assertEquals("the ticket must carry the small window, or this test proves nothing",
			REMEMBERED_WINDOW, ticket.transportParameters().initialMaxData());

		wire = new QuicWirePair();
		wire.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(true))
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

		wire.startClient(windowOf(ESTABLISHED_WINDOW));
		// The application opens and writes before a single server byte has arrived: only the remembered
		// parameters can possibly have supplied a stream id and a window here.
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("a resumption's remembered limits must let a stream open before the handshake",
			opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		assertEquals(0, stream.id());

		byte[] payload = pattern(PAYLOAD_SIZE);
		Promise<Void> written = stream.writer().acceptAll(List.of(wrap(payload)));
		wire.acceptServer(windowOf(ESTABLISHED_WINDOW));

		int zeroRttPackets = ZeroRttWire.deliverToServerCountingZeroRtt(wire);
		assertTrue("the payload never travelled in a 0-RTT packet", zeroRttPackets >= 1);
		assertEquals("early data must be bounded by the remembered initial_max_data",
			REMEMBERED_WINDOW, clientManager.bytesSent());
		assertFalse("the excess must be held, not failed and not sent", written.isComplete());
		assertEquals(1, serverStreams.size());
		assertTrue("the server received more than the remembered window permitted",
			serverStreams.get(0).receivePart().highestOffsetReceived() <= REMEMBERED_WINDOW);

		driveUntil(written::isComplete);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertTrue("the withheld write never completed", written.isComplete());
		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals("the whole payload must eventually be sent", PAYLOAD_SIZE, clientManager.bytesSent());

		stream.writer().acceptEndOfStream();
		driveUntil(() -> serverReads.get(0).isComplete());
		byte[] received = drain(serverReads.get(0));
		assertArrayEquals(payload, received);
	}

	// ---------------------------------------------------------------- helpers

	private static QuicConnectionSettings windowOf(int bytes) {
		return QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiLocal(MemSize.bytes(bytes))
			.withInitialMaxStreamDataBidiRemote(MemSize.bytes(bytes))
			.withInitialMaxStreamDataUni(MemSize.bytes(bytes))
			.build();
	}

	private static byte[] pattern(int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7);
		}
		return bytes;
	}

	private static ByteBuf wrap(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(source.length);
		buf.put(source);
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

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}
}
