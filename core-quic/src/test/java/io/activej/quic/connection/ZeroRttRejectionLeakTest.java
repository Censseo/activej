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

package io.activej.quic.connection;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.MemSize;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.connection.testutil.ZeroRttWire;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
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

import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T091, spec FR-055 / {@code contracts/zero-rtt.md} point 5 — <b>every {@link ByteBuf} held by the
 * 0-RTT state a rejection discards is recycled exactly once</b>, on every path that reaches a
 * rejection. No {@code @IgnoreLeaks} anywhere in this class, deliberately (SC-013).
 *
 * <h2>How "exactly once" is decided</h2>
 * Two instruments, because neither one alone says it:
 * <ol>
 *   <li><b>At least once</b> — {@link ByteBufRule}, which fails the class unless every pooled buffer
 *       came back. That is the leak half, and it is the only half that catches a buffer simply
 *       dropped.</li>
 *   <li><b>At most once</b> — a sentinel. Every payload handed to the transport is a {@link
 *       ByteBuf#slice()} of a buffer this test keeps a reference to, so a correct release takes the
 *       reference count from 2 to 1 and the buffer stays alive and readable. A second release takes it
 *       to 0, and {@link #assertNothingHandedOffWasReleasedTwice} then finds either a cleared array
 *       ({@code ByteBufPool.clearOnRecycle}) or a refusal to read it at all
 *       ({@code ByteBuf.CHECK_RECYCLE}, which this harness turns on implicitly through
 *       {@code ByteBufPool.registry=true} and {@code chk=on}). Either way the case is named rather
 *       than left to surface as corrupt bytes somewhere downstream.</li>
 * </ol>
 * The sentinel is checked after the pair is closed, since a buffer still waiting in a send queue is
 * legitimately unrecycled until then.
 *
 * <h2>The rejection scenarios</h2>
 * All of them refuse early data the way a real peer does — the server's EncryptedExtensions simply
 * omits {@code early_data} (RFC 8446 §4.2.10), configured through
 * {@code TlsServerConfig.withEarlyDataEnabled(false)} — and they differ in <b>where the buffers are</b>
 * when the refusal lands:
 * <table>
 *   <caption>where the discarded state is holding buffers</caption>
 *   <tr><th>case</th><th>the buffers are…</th></tr>
 *   <tr><td>sent</td><td>in flight, retained for loss recovery until acknowledged</td></tr>
 *   <tr><td>flow-controlled</td><td>held behind the remembered {@code initial_max_data} window</td></tr>
 *   <tr><td>queued</td><td>in the {@code ZERO_RTT} send queue, never flushed</td></tr>
 *   <tr><td>mid-flight abort</td><td>anywhere at all — the connection is torn down on the refusal</td></tr>
 * </table>
 * Each case asserts that the refusal really happened before it asserts anything about buffers, so a
 * scenario that quietly stopped exercising 0-RTT fails instead of passing vacuously.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1 — 0-RTT</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.9.3">RFC 9001 §4.9.3 — Discarding 0-RTT Keys</a>
 */
public final class ZeroRttRejectionLeakTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** What the ticket remembers, and therefore the whole of the client's early-data budget. */
	private static final int REMEMBERED_WINDOW = 4 * 1024;

	/** Comfortably past {@link #REMEMBERED_WINDOW}, so a refusal finds buffers on both sides of it. */
	private static final int OVERSIZED_PAYLOAD = 24 * 1024;

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	private final List<Sentinel> handedOff = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		handedOff.clear();
		serverReads.clear();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		wire = null;
		assertNothingHandedOffWasReleasedTwice();
		for (Sentinel sentinel : handedOff) {
			sentinel.parent.recycle();
		}
		handedOff.clear();
		for (Promise<ByteBuf> read : serverReads) {
			if (read.isResult()) read.getResult().recycle();
		}
		serverReads.clear();
		loop.close();
	}

	// ---------------------------------------------------------------- sent, then refused

	/**
	 * The plain shape: three streams' worth of early data leaves in 0-RTT packets, the server refuses,
	 * and the handshake completes anyway. Whatever the connection then does with the refused state —
	 * re-level it, or discard it as FR-055 requires — every buffer it held is accounted for.
	 */
	@Test
	public void earlyDataThatLeftBeforeTheRefusalIsReleasedExactlyOnce() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicSessionTicket ticket = refusingPair(settings);

		wire.startClient(settings);
		for (int i = 0; i < 3; i++) {
			QuicStream stream = openedBeforeTheHandshake();
			ChannelSuppliers.ofValue(handOff(("GET /early/" + i).getBytes(UTF_8)))
				.streamTo(stream.writer());
		}
		wire.acceptServer(settings);

		assertTrue("no 0-RTT packet carried the early data, so nothing was refused",
			ZeroRttWire.deliverToServerCountingZeroRtt(wire) >= 1);
		wire.pump();

		assertRefused(ticket);
		assertTrue("the client kept its 0-RTT keys past the refusal",
			wire.client().isLevelDiscarded(EncryptionLevel.ZERO_RTT));
	}

	// ---------------------------------------------------------------- held behind flow control, then refused

	/**
	 * The remembered {@code initial_max_data} window (RFC 9000 §7.4.1) is smaller than the payload, so
	 * when the refusal lands part of the early data has been sent and the rest is still held in the
	 * stream's send buffer. Both halves are the discarded state's to release.
	 */
	@Test
	public void earlyDataStillHeldByTheRememberedFlowControlWindowIsReleasedExactlyOnce() throws Exception {
		QuicConnectionSettings settings = windowOf(REMEMBERED_WINDOW);
		QuicSessionTicket ticket = refusingPair(settings);

		wire.startClient(settings);
		QuicStream stream = openedBeforeTheHandshake();
		Promise<Void> written = ChannelSuppliers.ofValue(handOff(pattern(OVERSIZED_PAYLOAD)))
			.streamTo(stream.writer());
		wire.acceptServer(settings);

		assertTrue(ZeroRttWire.deliverToServerCountingZeroRtt(wire) >= 1);
		assertFalse("the whole payload fitted the remembered window, so nothing was held back",
			written.isComplete());
		wire.pump();

		assertRefused(ticket);
	}

	// ---------------------------------------------------------------- never flushed, then refused

	/**
	 * Frames enqueued directly on the connection, below the stream layer, and never flushed by an
	 * explicit {@code requestSend()} — so they are the {@code ZERO_RTT} send queue's own contents at the
	 * moment the refusal is applied. This is the one case that reaches
	 * {@code QuicConnection.applyTlsResult}'s 0-RTT queue with something in it by construction rather
	 * than by timing.
	 */
	@Test
	public void framesLeftInTheZeroRttSendQueueWhenTheRefusalArrivesAreReleasedExactlyOnce() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);
		// enqueueFrame needs a handler registered; nothing here reads what arrives, only what is released.
		wire = bareRefusing(keys, ticket)
			.withClientFrameHandler((connection, level, frame) -> {})
			.withServerFrameHandler((connection, level, frame) -> {});

		wire.startClient(settings);
		assertTrue(wire.client().isLevelInstalled(EncryptionLevel.ZERO_RTT));
		long offset = 0;
		for (int i = 0; i < 3; i++) {
			ByteBuf payload = handOff(("early frame " + i).getBytes(UTF_8));
			QuicFrame frame = new StreamFrame(0, offset, false, payload);
			offset += payload.readRemaining();
			wire.client().enqueueFrame(frame);
		}
		wire.acceptServer(settings);
		wire.pump();

		assertRefused(ticket);
	}

	// ---------------------------------------------------------------- refused, then torn down mid-flight

	/**
	 * The refusal is delivered and then the connection is aborted in the same breath, before anything
	 * queued behind the refusal can drain. Nothing here is allowed to leak either: an abort is the path
	 * where a discard and a close race, and a buffer owned by neither is exactly what that produces.
	 */
	@Test
	public void aTeardownImmediatelyAfterTheRefusalReleasesEverythingExactlyOnce() throws Exception {
		QuicConnectionSettings settings = windowOf(REMEMBERED_WINDOW);
		QuicSessionTicket ticket = refusingPair(settings);

		wire.startClient(settings);
		QuicStream stream = openedBeforeTheHandshake();
		ChannelSuppliers.ofValue(handOff(pattern(OVERSIZED_PAYLOAD))).streamTo(stream.writer());
		wire.acceptServer(settings);

		assertTrue(ZeroRttWire.deliverToServerCountingZeroRtt(wire) >= 1);
		// Delivering the server's flight is enough for the client to read EncryptedExtensions and learn
		// that its early data was refused; the teardown then happens with the discard still in progress,
		// and nothing is pumped back the other way.
		//noinspection StatementWithEmptyBody
		while (wire.deliverToClient()) {
			// draining the server's flight, one datagram at a time
		}
		assertTrue("the client never learned the answer, so this aborts before a refusal rather than on one",
			wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());
		assertNotNull(ticket);

		wire.client().closeNow();
	}

	// ---------------------------------------------------------------- twice over the same ticket

	/**
	 * Two refused resumptions in a row, so a per-connection discard that leaked only its second
	 * invocation's buffers — a static or otherwise shared holder — cannot hide behind a single-shot
	 * scenario.
	 */
	@Test
	public void aSecondRefusedResumptionReleasesItsOwnBuffersExactlyOnce() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		for (int attempt = 0; attempt < 2; attempt++) {
			wire = refusing(keys, ticket);
			wire.startClient(settings);
			QuicStream stream = openedBeforeTheHandshake();
			ChannelSuppliers.ofValue(handOff(("GET /attempt/" + attempt).getBytes(UTF_8)))
				.streamTo(stream.writer());
			wire.acceptServer(settings);
			assertTrue(ZeroRttWire.deliverToServerCountingZeroRtt(wire) >= 1);
			wire.pump();
			assertRefused(ticket);
			wire.close();
			wire = null;
		}
	}

	// ---------------------------------------------------------------- harness

	/** Builds a stream-layered pair, having first earned the ticket it will offer. */
	private QuicSessionTicket refusingPair(QuicConnectionSettings settings) throws Exception {
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull("the first handshake issued no ticket, so nothing here resumes", ticket);
		wire = refusing(keys, ticket);
		return ticket;
	}

	/**
	 * A client offering {@code ticket} with early data, against a server that refuses it, with a stream
	 * layer on both ends — the only shape that can write early data through an application API.
	 */
	private QuicWirePair refusing(QuicTicketKeys keys, QuicSessionTicket ticket) {
		return bareRefusing(keys, ticket)
			.withClientFrameHandlerFactory(connection -> clientManager =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build())
			.withServerFrameHandlerFactory(connection -> QuicStreamManager
				.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> serverReads.add(stream.reader().toCollector(ByteBufs.collector())))
				.build());
	}

	/** The same refusal, with no stream layer — for a test that enqueues frames on the connection itself. */
	private QuicWirePair bareRefusing(QuicTicketKeys keys, QuicSessionTicket ticket) {
		QuicWirePair pair = new QuicWirePair();
		pair.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(false))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(true))
			.withClientRememberedTransportParameters(ticket.transportParameters());
		return pair;
	}

	private QuicStream openedBeforeTheHandshake() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("a resumption's remembered limits must let a stream open before the handshake",
			opened.isComplete());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** The precondition every case shares: the session resumed, and the early data was refused. */
	private void assertRefused(QuicSessionTicket ticket) {
		assertNotNull(ticket);
		assertSame("the handshake did not complete", QuicConnectionState.ESTABLISHED, wire.client().state());
		assertSame(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertTrue("the pre-shared key was refused, so no early data was ever at stake",
			wire.client().isSessionResumed());
		assertFalse("the server accepted early data it was configured to refuse",
			wire.client().isEarlyDataAccepted());
		assertFalse(wire.server().isEarlyDataAccepted());
	}

	/**
	 * The "at most once" half. Reads each retained parent buffer, which is only still readable if the
	 * transport released the slice it was given no more times than it was given it.
	 */
	private void assertNothingHandedOffWasReleasedTwice() {
		for (Sentinel sentinel : handedOff) {
			byte[] survived;
			try {
				survived = sentinel.parent.getArray();
			} catch (AssertionError e) {
				throw new AssertionError("a buffer handed to the transport was released more times than" +
										 " it was handed over: this test still holds a reference to it, and" +
										 " the pool has already taken it back", e);
			}
			assertArrayEquals("a buffer handed to the transport was released more than once —" +
							  " its array was cleared while this test still holds a reference",
				sentinel.pattern, survived);
		}
	}

	/**
	 * Wraps {@code pattern} and hands the transport a {@link ByteBuf#slice()} of it, keeping the parent
	 * so {@link #assertNothingHandedOffWasReleasedTwice} can read the array afterwards.
	 */
	private ByteBuf handOff(byte[] pattern) {
		ByteBuf parent = ByteBufPool.allocate(pattern.length);
		parent.put(pattern);
		handedOff.add(new Sentinel(parent, pattern));
		return parent.slice();
	}

	private record Sentinel(ByteBuf parent, byte[] pattern) {}

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
}
