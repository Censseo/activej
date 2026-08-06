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
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T092, spec FR-048 — <b>resumption and early-data acceptance are two outcomes, not one</b>. A server
 * that opens the ticket, accepts the pre-shared key and then simply does not echo {@code early_data}
 * in its EncryptedExtensions has <i>refused early data</i>, not failed the handshake: RFC 8446 §4.2.10
 * makes the echo the acceptance signal and its absence the rejection signal, and RFC 9001 §4.6.1
 * carries that into QUIC unchanged.
 *
 * <h2>Why the two have to be pinned apart</h2>
 * They are latched from one {@code TlsEngineResult} into two adjacent booleans, by two adjacent lines
 * of {@code QuicConnection.applyTlsResult}. Nothing but a test stops a later change from collapsing
 * them into one — and the collapse is silently wrong in the common direction: most deployed servers
 * resume sessions and refuse 0-RTT, so "resumed implies early data accepted" would make an ordinary
 * peer look like it had read data it never read.
 * <p>
 * The three reachable combinations are asserted in one place, so the independence reads as a table
 * rather than being inferred from three files:
 * <table>
 *   <caption>reachable outcomes</caption>
 *   <tr><th>ticket offered</th><th>server</th><th>{@code isSessionResumed}</th><th>{@code isEarlyDataAccepted}</th></tr>
 *   <tr><td>none</td><td>—</td><td>false</td><td>false</td></tr>
 *   <tr><td>yes</td><td>{@code earlyDataEnabled=false}</td><td><b>true</b></td><td><b>false</b></td></tr>
 *   <tr><td>yes</td><td>{@code earlyDataEnabled=true}</td><td>true</td><td>true</td></tr>
 * </table>
 * The fourth combination — early data accepted without resumption — is unreachable by construction:
 * {@code TlsServerEngine.acceptEarlyData} returns immediately on a null accepted PSK and has no other
 * caller. It is stated here rather than asserted, because a test cannot exhibit it.
 *
 * <h2>What this test does not claim</h2>
 * The refused early-data payload's own fate is deliberately not asserted. Discarding the 0-RTT stream
 * state and re-creating the work in 1-RTT is FR-055/FR-067 — T093 at this layer and {@code Http3Client}
 * above it — so an assertion here that the early bytes "still arrive" would be an assertion that the
 * discard never happens. What is asserted is the part FR-048 owns, and that no later phase may change:
 * the handshake completed, and the connection it produced carries 1-RTT work normally.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.2.10">RFC 8446 §4.2.10 — Early Data Indication</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-4.6.1">RFC 9001 §4.6.1 — 0-RTT</a>
 */
public final class ZeroRttResumptionWithoutEarlyDataTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;

	private static final byte[] EARLY_PAYLOAD = "GET /early".getBytes(StandardCharsets.UTF_8);
	private static final byte[] FALLBACK_PAYLOAD = "GET /after-the-refusal".getBytes(StandardCharsets.UTF_8);
	private static final byte[] ANSWER_PAYLOAD = "200 answered at 1-RTT".getBytes(StandardCharsets.UTF_8);

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
		wire = null;
		// The server-side collectors are the one place this test owns a buffer the fixture cannot see.
		for (Promise<ByteBuf> read : serverReads) {
			if (read.isResult()) read.getResult().recycle();
		}
		serverReads.clear();
		loop.close();
	}

	// ---------------------------------------------------------------- FR-048: the refusal is not a failure

	@Test
	public void aServerThatAcceptsThePskButOmitsEarlyDataStillCompletesTheHandshake() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull("the first handshake issued no ticket, so nothing here resumes", ticket);

		wire = resuming(keys, ticket, false);
		Promise<QuicConnection> started = wire.startClient(settings);
		assertTrue("0-RTT keys must be installed the moment the ClientHello leaves, refusal or not",
			wire.client().isLevelInstalled(EncryptionLevel.ZERO_RTT));
		wire.acceptServer(settings);
		wire.pump();

		assertTrue("the handshake failed: " + started.getException(), started.isResult());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());

		assertTrue("the pre-shared key was not accepted, so this proves nothing about early data",
			wire.client().isSessionResumed());
		assertTrue(wire.server().isSessionResumed());
		assertFalse("early data was accepted by a server configured to refuse it",
			wire.client().isEarlyDataAccepted());
		assertFalse(wire.server().isEarlyDataAccepted());

		// RFC 9001 §4.9.3: whatever the answer was, the client holds no 0-RTT keys past the 1-RTT install.
		assertTrue(wire.client().isLevelDiscarded(EncryptionLevel.ZERO_RTT));
	}

	/** The table in this class's Javadoc, asserted. */
	@Test
	public void resumptionAndEarlyDataAcceptanceAreIndependentOutcomes() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		wire = new QuicWirePair();
		wire.withServerTlsConfig(builder -> ZeroRttWire.acceptingEarlyData(builder, keys));
		wire.handshake(settings);
		assertFalse("a handshake that offered no ticket reported a resumption", wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());
		wire.close();

		wire = resuming(keys, ticket, false);
		wire.startClient(settings);
		wire.acceptServer(settings);
		wire.pump();
		assertTrue(wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());
		wire.close();

		wire = resuming(keys, ticket, true);
		wire.startClient(settings);
		wire.acceptServer(settings);
		wire.pump();
		assertTrue(wire.client().isSessionResumed());
		assertTrue("the accepting configuration must accept, or the refusing one proves nothing",
			wire.client().isEarlyDataAccepted());
	}

	// ---------------------------------------------------------------- and the connection is usable afterwards

	/**
	 * The whole shape, with early data genuinely offered and genuinely written: the client opens a
	 * stream and writes before a single server byte has arrived, the server refuses the early data, and
	 * the connection that establishes carries a fresh 1-RTT exchange in both directions.
	 * <p>
	 * The fresh stream is what "falls back to 1-RTT" means at this layer. Re-issuing the <i>same</i>
	 * request is the layer above's job (FR-067, {@code Http3Client}), which is why the early payload's
	 * own arrival is not asserted either way.
	 */
	@Test
	public void aRefusedEarlyDataOfferLeavesAFullyUsableOneRttConnection() throws Exception {
		QuicConnectionSettings settings = QuicConnectionSettings.create();
		QuicTicketKeys keys = ZeroRttWire.ticketKeys();
		QuicSessionTicket ticket = ZeroRttWire.earnTicket(keys, settings);
		assertNotNull(ticket);

		wire = resumingWithStreams(keys, ticket);
		wire.startClient(settings);

		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue("a resumption's remembered limits must let a stream open before the handshake",
			opened.isComplete());
		QuicStream early = opened.getResult();
		assertNotNull(early);
		ChannelSuppliers.ofValue(wrap(EARLY_PAYLOAD)).streamTo(early.writer());

		wire.acceptServer(settings);
		wire.pump();

		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertTrue(wire.client().isSessionResumed());
		assertFalse(wire.client().isEarlyDataAccepted());

		QuicStream fallback = openNow(clientManager.openBidirectional());
		Promise<ByteBuf> answered = fallback.reader().toCollector(ByteBufs.collector());
		Promise<Void> sent = ChannelSuppliers.ofValue(wrap(FALLBACK_PAYLOAD)).streamTo(fallback.writer());
		driveUntil(() -> sent.isComplete() && serverReceived(FALLBACK_PAYLOAD));

		assertTrue("the 1-RTT write never completed on a connection whose handshake succeeded",
			sent.isResult());
		assertTrue("the server never received the work re-issued at 1-RTT", serverReceived(FALLBACK_PAYLOAD));

		// ... and the answer comes back, so the refusal cost a round trip rather than the connection.
		ChannelSuppliers.ofValue(wrap(ANSWER_PAYLOAD)).streamTo(serverStreamCarrying(FALLBACK_PAYLOAD).writer());
		driveUntil(answered::isComplete);
		assertArrayEquals(ANSWER_PAYLOAD, drain(answered));
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	// ---------------------------------------------------------------- harness

	private QuicWirePair resuming(QuicTicketKeys keys, QuicSessionTicket ticket, boolean serverAcceptsEarlyData) {
		QuicWirePair pair = new QuicWirePair();
		pair.withServerTlsConfig(builder -> ZeroRttWire.acceptingEarlyData(builder, keys, serverAcceptsEarlyData))
			.withClientTlsConfig(builder -> builder.withSessionTicket(ticket).withEarlyDataEnabled(true))
			.withClientRememberedTransportParameters(ticket.transportParameters());
		return pair;
	}

	/** The same, with a stream layer on both ends — the only shape that can write early data. */
	private QuicWirePair resumingWithStreams(QuicTicketKeys keys, QuicSessionTicket ticket) {
		QuicWirePair pair = resuming(keys, ticket, false);
		pair.withClientFrameHandlerFactory(connection -> clientManager =
				QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build())
			.withServerFrameHandlerFactory(connection -> QuicStreamManager
				.builder(Reactor.getCurrentReactor(), connection)
				.withStreamListener(stream -> {
					serverStreams.add(stream);
					serverReads.add(stream.reader().toCollector(ByteBufs.collector()));
				})
				.build());
		return pair;
	}

	private boolean serverReceived(byte[] payload) {
		for (int i = 0; i < serverReads.size(); i++) {
			if (carries(i, payload)) return true;
		}
		return false;
	}

	private QuicStream serverStreamCarrying(byte[] payload) {
		for (int i = 0; i < serverReads.size(); i++) {
			if (carries(i, payload)) return serverStreams.get(i);
		}
		throw new AssertionError("no server stream carried the expected payload");
	}

	/**
	 * Whether the server's collector for stream {@code i} finished with exactly {@code payload}. The
	 * collected buffer stays the collector's and is read without being consumed, so the same promise can
	 * be asked again on the next drive round and its buffer is still recycled exactly once, in
	 * {@link #tearDown}.
	 */
	private boolean carries(int index, byte[] payload) {
		Promise<ByteBuf> read = serverReads.get(index);
		if (!read.isResult()) return false;
		ByteBuf buf = read.getResult();
		if (buf.readRemaining() != payload.length) return false;
		for (int i = 0; i < payload.length; i++) {
			if (buf.array()[buf.head() + i] != payload[i]) return false;
		}
		return true;
	}

	private QuicStream openNow(Promise<QuicStream> opened) {
		driveUntil(opened::isComplete);
		if (!opened.isResult()) throw new AssertionError("the stream open failed", opened.getException());
		return opened.getResult();
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

	private static ByteBuf wrap(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(source.length);
		buf.put(source);
		return buf;
	}
}
