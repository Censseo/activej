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
import io.activej.csp.consumer.AbstractChannelConsumer;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.supplier.AbstractChannelSupplier;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicEndpoint;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicEndpointFixture;
import io.activej.quic.tls.QuicTransportParameters;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T085 / <b>SC-010</b> — one test method per row of feature 05's assumption <b>A-1</b>, the eight
 * capabilities `005-http3-core` fixed as a hard prerequisite before this feature existed
 * ([specs/005-http3-core/spec.md](../../../../../../../../specs/005-http3-core/spec.md), §Assumptions).
 * A row that cannot be satisfied is a blocking finding, not a negotiation — so each method is named
 * for its row and asserts the capability <em>with the shape A-1 states</em>, not merely that something
 * related exists.
 *
 * <table>
 *   <caption>A-1's eight rows and the method that answers each</caption>
 *   <tr><th>#</th><th>Required from feature 04</th><th>Method</th></tr>
 *   <tr><td>1</td><td>open and accept bidi + uni streams, RFC 9000 §2.1 ids per role</td>
 *       <td>{@link #row1_openAndAcceptBidirectionalAndUnidirectionalStreamsWithCorrectIdentifiers()}</td></tr>
 *   <tr><td>2</td><td>ordered, de-duplicated, reliable delivery as owned buffers over a CSP supplier</td>
 *       <td>{@link #row2_orderedDeduplicatedReliableDeliveryAsOwnedBuffersOverACspSupplier()}</td></tr>
 *   <tr><td>3</td><td>writing with an explicit FIN</td>
 *       <td>{@link #row3_writingWithAnExplicitFin()}</td></tr>
 *   <tr><td>4</td><td>{@code RESET_STREAM} / {@code STOP_SENDING} with an application error code</td>
 *       <td>{@link #row4_resetStreamAndStopSendingCarryAnApplicationErrorCode()}</td></tr>
 *   <tr><td>5</td><td>stream + connection flow control, backpressure surfaced to the writer</td>
 *       <td>{@link #row5_streamAndConnectionFlowControlSurfaceBackpressureToTheWriter()}</td></tr>
 *   <tr><td>6</td><td>notification of peer-opened streams</td>
 *       <td>{@link #row6_notificationOfPeerOpenedStreams()}</td></tr>
 *   <tr><td>7</td><td>an endpoint-level handler factory on {@code QuicEndpoint.Builder}</td>
 *       <td>{@link #row7_anEndpointLevelHandlerFactoryReachesAnAcceptedServerConnection()}</td></tr>
 *   <tr><td>8</td><td>configuration of the stream-related transport parameters</td>
 *       <td>{@link #row8_theStreamRelatedTransportParametersAreConfigurable()}</td></tr>
 * </table>
 *
 * <h2>Why the endpoint rather than a wire pair</h2>
 * Every row is driven over {@link QuicEndpointFixture} — two real {@link QuicEndpoint}s over a
 * simulated network — because that is the shape feature 05 will use, and row 7 is only observable
 * there at all: an accepted server connection is built inside {@code QuicEndpoint.acceptOrDrop}, so
 * the endpoint-level factory is the sole way in. Driving every other row through the same fixture
 * means no row passes through a seam feature 05 will not have.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 */
public final class Feature05ContractTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;
	private static final long STEP_MILLIS = 5;

	/** RFC 9114 §8.1 {@code H3_REQUEST_CANCELLED}: an application code feature 05 will really send. */
	private static final long H3_REQUEST_CANCELLED = 0x010C;

	private ManualEventloop loop;
	private QuicEndpointFixture fixture;

	private final List<QuicStreamManager> serverManagers = new ArrayList<>();
	private final List<QuicStream> acceptedStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> acceptedReads = new ArrayList<>();
	private final List<QuicStreamManager> clientManagers = new ArrayList<>();
	private final List<QuicConnection> clientConnections = new ArrayList<>();
	private final List<QuicStream> clientAcceptedStreams = new ArrayList<>();

	/** Whether the server attaches a collector to each accepted stream; off where a reader would grant credit. */
	private boolean collectOnAccept = true;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		fixture = new QuicEndpointFixture(loop, 1);
		collectOnAccept = true;
	}

	@After
	public void tearDown() {
		fixture.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- the wiring feature 05 will use

	/** The server shape of {@code contracts/java-api.md}: one manager per accepted connection. */
	private QuicEndpoint serverWithStreamLayer(QuicConnectionSettings settings) {
		return fixture.server(settings, builder -> builder
			.withFrameHandlerFactory(connection -> {
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream -> {
						acceptedStreams.add(stream);
						if (collectOnAccept && stream.hasReceivePart()) {
							acceptedReads.add(stream.reader().toCollector(ByteBufs.collector()));
						}
					})
					.build();
				serverManagers.add(manager);
				return manager;
			}));
	}

	private QuicStreamManager connectClient(QuicConnectionSettings settings) {
		QuicEndpoint client = fixture.client(settings, builder -> builder
			.withFrameHandlerFactory(connection -> {
				clientConnections.add(connection);
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(clientAcceptedStreams::add)
					.build();
				clientManagers.add(manager);
				return manager;
			}));
		Promise<QuicConnection> connecting = client.connectTo(
			QuicEndpointFixture.SERVER_ADDRESS, QuicEndpointFixture.clientEngineFactory());
		driveUntil(connecting::isComplete);
		assertTrue("the handshake did not complete: " + connecting, connecting.isResult());
		return clientManagers.get(clientManagers.size() - 1);
	}

	private QuicStreamManager connectClient() {
		return connectClient(QuicConnectionSettings.create());
	}

	// ---------------------------------------------------------------- helpers

	private void driveUntil(BooleanSupplier done) {
		for (int round = 0; round < MAX_DRIVE_ROUNDS; round++) {
			fixture.pump();
			if (done.getAsBoolean()) return;
			fixture.advance(STEP_MILLIS);
			if (done.getAsBoolean()) return;
		}
		fail("the exchange did not complete within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, source.length));
		buf.put(source);
		return buf;
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the read never completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}

	private static void discard(Promise<ByteBuf> collected) {
		if (!collected.isResult()) return;
		ByteBuf buf = collected.getResult();
		if (buf != null) buf.recycle();
	}

	private static QuicStream openNow(Promise<QuicStream> opened) {
		assertTrue("the connection is established, so the open resolves at once", opened.isComplete());
		assertTrue(String.valueOf(opened.getException()), opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	// ---------------------------------------------------------------- row 1

	/**
	 * A-1 row 1 — "Open and accept bidirectional and unidirectional streams, with correct RFC 9000 §2.1
	 * stream-ID allocation per role". Feature 05 needs bidirectional streams for requests and
	 * unidirectional ones for the control and QPACK streams, so both kinds and both roles are asserted.
	 */
	@Test
	public void row1_openAndAcceptBidirectionalAndUnidirectionalStreamsWithCorrectIdentifiers() {
		serverWithStreamLayer(QuicConnectionSettings.create());
		QuicStreamManager client = connectClient();

		// RFC 9000 §2.1: client-initiated bidirectional ids are 0, 4, 8…; client-initiated
		// unidirectional ones are 2, 6, 10… — exactly the allocation feature 05's FR-017 assumes when it
		// opens a control stream plus two QPACK streams.
		QuicStream bidi0 = openNow(client.openBidirectional());
		QuicStream uni0 = openNow(client.openUnidirectional());
		QuicStream uni1 = openNow(client.openUnidirectional());
		QuicStream bidi1 = openNow(client.openBidirectional());

		assertEquals(0, bidi0.id());
		assertEquals(4, bidi1.id());
		assertEquals(2, uni0.id());
		assertEquals(6, uni1.id());

		assertTrue(bidi0.isBidirectional());
		assertFalse(uni0.isBidirectional());
		assertTrue("a stream this endpoint opened is locally initiated", bidi0.isLocallyInitiated());

		// A locally-opened unidirectional stream is send-only; the half this endpoint does not own is not
		// merely empty, it is absent (FR-007).
		assertTrue(uni0.hasSendPart());
		assertFalse(uni0.hasReceivePart());
		assertTrue(bidi0.hasSendPart());
		assertTrue(bidi0.hasReceivePart());

		// And the server accepts both kinds, with the same identifiers.
		Promise<Void> b = ChannelSuppliers.ofValue(buf(pattern(64, 1))).streamTo(bidi0.writer());
		Promise<Void> u = ChannelSuppliers.ofValue(buf(pattern(64, 2))).streamTo(uni0.writer());
		driveUntil(() -> b.isComplete() && u.isComplete() && acceptedStreams.size() >= 2);

		// Read off the listener rather than streamOf(...), because a unidirectional stream that has been
		// written whole, FIN'd and drained is *released* by then (FR-006) — its absence from the map is
		// the release working, not the acceptance failing.
		assertEquals(2, acceptedStreams.size());
		QuicStream acceptedBidi = acceptedStreams.get(0);
		QuicStream acceptedUni = acceptedStreams.get(1);
		assertEquals(0, acceptedBidi.id());
		assertEquals(2, acceptedUni.id());
		assertFalse("a stream the peer opened is not locally initiated", acceptedBidi.isLocallyInitiated());
		assertTrue(acceptedBidi.isBidirectional());
		// The accepting side of a peer's unidirectional stream owns only the receiving half.
		assertFalse(acceptedUni.isBidirectional());
		assertTrue(acceptedUni.hasReceivePart());
		assertFalse(acceptedUni.hasSendPart());

		acceptedReads.forEach(Feature05ContractTest::discard);
	}

	// ---------------------------------------------------------------- row 2

	/**
	 * A-1 row 2 — "Ordered, de-duplicated, reliable delivery of stream bytes as owned {@code ByteBuf}s
	 * (or a CSP supplier over them)". Every parser in feature 05 reads through this, so all four
	 * adjectives matter: the bytes arrive in order, exactly once each, whole, and as buffers the caller
	 * owns.
	 * <p>
	 * Reliability is asserted against a <b>lossy</b> network, because a delivery guarantee that only
	 * holds on a perfect one is not a guarantee.
	 */
	@Test
	public void row2_orderedDeduplicatedReliableDeliveryAsOwnedBuffersOverACspSupplier() {
		serverWithStreamLayer(QuicConnectionSettings.create());
		QuicStreamManager client = connectClient();
		// One packet in eight dropped, plus reordering and duplication: retransmission, de-duplication and
		// reassembly all have to work for the bytes below to arrive at all.
		fixture.network().withDropRate(0.125).withReorderRate(0.125).withDuplicateRate(0.125);

		byte[] payload = pattern(48 * 1024, 3);
		QuicStream stream = openNow(client.openBidirectional());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(payload)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !acceptedReads.isEmpty() && acceptedReads.get(0).isComplete());

		assertArrayEquals("every byte, once, in order", payload, drain(acceptedReads.get(0)));

		// "as owned ByteBufs (or a CSP supplier over them)" — and, per FR-047, a CSP type built on the
		// platform's base class rather than a bare interface implementation.
		ChannelSupplier<ByteBuf> reader = acceptedStreams.get(0).reader();
		assertTrue("reader() must be an AbstractChannelSupplier (FR-047, constitution §II)",
			reader instanceof AbstractChannelSupplier);
	}

	// ---------------------------------------------------------------- row 3

	/**
	 * A-1 row 3 — "Writing with an explicit FIN", which is how feature 05 ends a request or response
	 * body. Two shapes are asserted, because feature 05 uses both: {@code accept(null)} as a standalone
	 * end-of-data marker, and the end-of-stream that {@code streamTo} attaches to the final write.
	 */
	@Test
	public void row3_writingWithAnExplicitFin() {
		serverWithStreamLayer(QuicConnectionSettings.create());
		QuicStreamManager client = connectClient();

		QuicStream stream = openNow(client.openBidirectional());
		ChannelConsumer<ByteBuf> writer = stream.writer();
		assertTrue("writer() must be an AbstractChannelConsumer (FR-047, constitution §II)",
			writer instanceof AbstractChannelConsumer);

		Promise<Void> body = writer.accept(buf(pattern(1024, 4)));
		driveUntil(body::isComplete);
		assertTrue(String.valueOf(body.getException()), body.isResult());
		assertFalse("no FIN yet, so the reader must not see end-of-stream",
			acceptedReads.get(0).isComplete());

		// accept(null) is the explicit marker.
		Promise<Void> fin = writer.accept(null);
		driveUntil(() -> fin.isComplete() && acceptedReads.get(0).isComplete());

		assertTrue(fin.isResult());
		assertEquals(1024, drain(acceptedReads.get(0)).length);
		assertEquals("RFC 9000 §3.1: the sending half is done once the FIN is acknowledged",
			SendState.DATA_RECVD, stream.sendState());
		assertEquals(ReceiveState.DATA_READ, acceptedStreams.get(0).receiveState());
	}

	// ---------------------------------------------------------------- row 4

	/**
	 * A-1 row 4 — "{@code RESET_STREAM} and {@code STOP_SENDING} carrying an <b>application</b> error
	 * code", which every per-stream H3 error of feature 05's FR-061 becomes. The code must survive the
	 * round trip intact and reach the other side's <em>typed</em> exception, since that is what feature
	 * 05 will read it out of.
	 */
	@Test
	public void row4_resetStreamAndStopSendingCarryAnApplicationErrorCode() {
		collectOnAccept = false;
		serverWithStreamLayer(QuicConnectionSettings.create());
		QuicStreamManager client = connectClient();

		QuicStream stream = openNow(client.openBidirectional());
		Promise<Void> written = stream.writer().accept(buf(pattern(2048, 5)));
		driveUntil(() -> !acceptedStreams.isEmpty() && written.isComplete());

		// RESET_STREAM, from the sender: the reader's pending and subsequent reads fail with the code.
		QuicStream accepted = acceptedStreams.get(0);
		Promise<ByteBuf> read = accepted.reader().toCollector(ByteBufs.collector());
		stream.reset(H3_REQUEST_CANCELLED);
		driveUntil(read::isComplete);

		assertFalse(read.isResult());
		Exception readFailure = read.getException();
		assertTrue(String.valueOf(readFailure), readFailure instanceof QuicStreamResetException);
		QuicStreamResetException reset = (QuicStreamResetException) readFailure;
		assertEquals(H3_REQUEST_CANCELLED, reset.applicationErrorCode());
		assertEquals(stream.id(), reset.streamId());

		// STOP_SENDING, from the receiver: the writer's subsequent writes fail with the code.
		QuicStream second = openNow(client.openBidirectional());
		Promise<Void> firstWrite = second.writer().accept(buf(pattern(512, 6)));
		driveUntil(() -> acceptedStreams.size() >= 2 && firstWrite.isComplete());

		acceptedStreams.get(1).stopSending(H3_REQUEST_CANCELLED);
		driveUntil(() -> client.streamsResetLocally() == 2);
		Promise<Void> afterStop = second.writer().accept(buf(pattern(64, 7)));
		driveUntil(afterStop::isComplete);

		assertFalse(afterStop.isResult());
		Exception writeFailure = afterStop.getException();
		assertTrue(String.valueOf(writeFailure), writeFailure instanceof QuicStreamStopSendingException);
		QuicStreamStopSendingException stop = (QuicStreamStopSendingException) writeFailure;
		assertEquals(H3_REQUEST_CANCELLED, stop.applicationErrorCode());
		assertEquals(second.id(), stop.streamId());

		acceptedReads.forEach(Feature05ContractTest::discard);
	}

	// ---------------------------------------------------------------- row 5

	/**
	 * A-1 row 5 — "Stream-level and connection-level flow control, with backpressure surfaced to the
	 * writer", which feature 05's FR-056 is defined in terms of. The observable is precisely the one
	 * FR-020 promises: a write resolves only once every byte of it has reached the transport, so while
	 * a limit holds it, the promise stays <em>pending</em> — neither failed nor silently completed.
	 */
	@Test
	public void row5_streamAndConnectionFlowControlSurfaceBackpressureToTheWriter() {
		int window = 4 * 1024;
		collectOnAccept = false;
		// The server's advertised windows are what the client's writer runs into.
		serverWithStreamLayer(QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.of(16 * window))
			.withInitialMaxStreamDataBidiLocal(MemSize.of(window))
			.withInitialMaxStreamDataBidiRemote(MemSize.of(window))
			.withInitialMaxStreamDataUni(MemSize.of(window))
			.build());
		QuicStreamManager client = connectClient();

		QuicStream stream = openNow(client.openBidirectional());
		Promise<Void> written = stream.writer().accept(buf(pattern(4 * window, 8)));
		driveUntil(() -> !acceptedStreams.isEmpty());

		assertFalse("the peer granted one window, so the write is withheld — this *is* the backpressure",
			written.isComplete());
		assertEquals("and it was announced, so the peer knows to grant more (FR-027)", 1,
			client.timesBlockedByStreamLimit());

		// Reading is what grants credit (FR-025), so the application draining the stream is what releases
		// the writer — end to end, with no frame constructed by hand.
		Promise<ByteBuf> read = acceptedStreams.get(0).reader().toCollector(ByteBufs.collector());
		driveUntil(written::isComplete);
		assertTrue(String.valueOf(written.getException()), written.isResult());

		Promise<Void> fin = stream.writer().accept(null);
		driveUntil(() -> fin.isComplete() && read.isComplete());
		assertTrue(fin.isResult());
		assertEquals(4 * window, drain(read).length);
	}

	// ---------------------------------------------------------------- row 6

	/**
	 * A-1 row 6 — "Notification of peer-opened streams", which is how feature 05 accepts request streams
	 * and the peer's unidirectional streams. FR-004 fixes the shape: a listener supplied when the layer
	 * is built, invoked exactly once per stream, before any byte of it is readable — never a queue the
	 * application polls.
	 */
	@Test
	public void row6_notificationOfPeerOpenedStreams() {
		serverWithStreamLayer(QuicConnectionSettings.create());
		QuicStreamManager client = connectClient();

		QuicStream first = openNow(client.openBidirectional());
		QuicStream second = openNow(client.openUnidirectional());
		Promise<Void> a = ChannelSuppliers.ofValue(buf(pattern(128, 9))).streamTo(first.writer());
		Promise<Void> b = ChannelSuppliers.ofValue(buf(pattern(128, 10))).streamTo(second.writer());
		driveUntil(() -> a.isComplete() && b.isComplete() && acceptedReads.size() == 2);

		assertEquals("exactly once per peer-opened stream", 2, acceptedStreams.size());
		assertEquals(first.id(), acceptedStreams.get(0).id());
		assertEquals(second.id(), acceptedStreams.get(1).id());
		// The listener runs before any byte is readable, which is why attaching a reader inside it — as
		// this fixture does, and as feature 05 will — cannot miss the first slice.
		assertEquals(128, drain(acceptedReads.get(0)).length);
		assertEquals(128, drain(acceptedReads.get(1)).length);

		// The client is told about a *server*-opened stream by the same seam, in the other direction.
		QuicStream serverOpened = openNow(serverManagers.get(0).openUnidirectional());
		Promise<Void> c = ChannelSuppliers.ofValue(buf(pattern(64, 11))).streamTo(serverOpened.writer());
		driveUntil(() -> c.isComplete() && !clientAcceptedStreams.isEmpty());

		assertEquals(1, clientAcceptedStreams.size());
		assertEquals(serverOpened.id(), clientAcceptedStreams.get(0).id());
		discard(clientAcceptedStreams.get(0).reader().toCollector(ByteBufs.collector()));
	}

	// ---------------------------------------------------------------- row 7

	/**
	 * A-1 row 7 — "An <b>endpoint-level</b> handler factory on {@code QuicEndpoint.Builder}, so an
	 * accepted server connection can be attached to". Feature 05's {@code Http3Server} has no other way
	 * in (its FR-058a): accepted connections are built inside {@code QuicEndpoint.acceptOrDrop}, and
	 * only {@code QuicConnection.Builder.withFrameHandler} existed before this feature.
	 * <p>
	 * Every other method in this class already depends on the seam — the whole fixture is built on it —
	 * so what this one adds is the properties FR-039 states about it: invoked <b>once per connection</b>,
	 * on <b>accepted</b> connections as well as dialled ones, and the connection is handed <em>to</em>
	 * the factory rather than being reachable only afterwards.
	 */
	@Test
	public void row7_anEndpointLevelHandlerFactoryReachesAnAcceptedServerConnection() {
		List<QuicConnection> handedToFactory = new ArrayList<>();
		int[] factoryCalls = {0};
		fixture.server(QuicConnectionSettings.create(), builder -> builder
			.withFrameHandlerFactory(connection -> {
				factoryCalls[0]++;
				handedToFactory.add(connection);
				QuicStreamManager manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
					.withStreamListener(stream -> {
						acceptedStreams.add(stream);
						acceptedReads.add(stream.reader().toCollector(ByteBufs.collector()));
					})
					.build();
				serverManagers.add(manager);
				return manager;
			}));

		QuicStreamManager firstClient = connectClient();
		QuicStream stream = openNow(firstClient.openBidirectional());
		Promise<Void> written = ChannelSuppliers.ofValue(buf(pattern(256, 12))).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !acceptedReads.isEmpty() && acceptedReads.get(0).isComplete());

		assertEquals("exactly once per accepted connection (FR-039)", 1, factoryCalls[0]);
		assertEquals(1, handedToFactory.size());
		assertNotNull("the factory receives the connection, so the manager can hold it",
			handedToFactory.get(0));
		assertEquals(QuicConnection.Role.SERVER, handedToFactory.get(0).role());
		assertEquals(256, drain(acceptedReads.get(0)).length);

		// A second accepted connection gets its own manager rather than sharing the first's.
		QuicStreamManager secondClient = connectClient();
		QuicStream secondStream = openNow(secondClient.openBidirectional());
		Promise<Void> secondWritten =
			ChannelSuppliers.ofValue(buf(pattern(256, 13))).streamTo(secondStream.writer());
		driveUntil(() -> secondWritten.isComplete() && acceptedReads.size() == 2
						 && acceptedReads.get(1).isComplete());

		assertEquals(2, factoryCalls[0]);
		assertEquals(2, serverManagers.size());
		assertNotSame("each accepted connection gets its own stream layer",
			serverManagers.get(0), serverManagers.get(1));
		assertEquals(256, drain(acceptedReads.get(1)).length);
	}

	// ---------------------------------------------------------------- row 8

	/**
	 * A-1 row 8 — "Configuration of the stream-related transport parameters
	 * ({@code initial_max_streams_bidi}, {@code initial_max_streams_uni}, {@code initial_max_data},
	 * {@code initial_max_stream_data_*})", which feature 05's FR-058b requires for its
	 * {@code initial_max_streams_uni = 3} (its FR-017) and its concurrent-request-stream bound (its
	 * FR-046).
	 * <p>
	 * Configured, advertised <b>and enforced</b>: a parameter a peer never sees is not configuration, and
	 * one this endpoint does not act on is decoration. All three are asserted, over a real handshake.
	 */
	@Test
	public void row8_theStreamRelatedTransportParametersAreConfigurable() {
		// The two values feature 05 names, plus a distinctive window for each of the four data limits.
		QuicConnectionSettings serverSettings = QuicConnectionSettings.builder()
			.withInitialMaxData(MemSize.kilobytes(640))
			.withInitialMaxStreamDataBidiLocal(MemSize.kilobytes(96))
			.withInitialMaxStreamDataBidiRemote(MemSize.kilobytes(112))
			.withInitialMaxStreamDataUni(MemSize.kilobytes(128))
			.withInitialMaxStreamsBidi(100)   // feature 05's FR-046
			.withInitialMaxStreamsUni(3)      // feature 05's FR-017: control + two QPACK streams
			.build();
		serverWithStreamLayer(serverSettings);
		QuicStreamManager client = connectClient();

		// Advertised: the client's view of the peer's parameters is exactly what the server configured.
		// Read off the dialled connection the endpoint-level factory was handed, so this asserts what
		// actually crossed the wire rather than what the settings object says.
		assertEquals(1, clientConnections.size());
		QuicTransportParameters seenByClient = clientConnections.get(0).peerTransportParameters();
		assertNotNull("the handshake must have supplied the peer's parameters", seenByClient);
		assertEquals(MemSize.kilobytes(640).toLong(), seenByClient.initialMaxData());
		assertEquals(MemSize.kilobytes(96).toLong(), seenByClient.initialMaxStreamDataBidiLocal());
		assertEquals(MemSize.kilobytes(112).toLong(), seenByClient.initialMaxStreamDataBidiRemote());
		assertEquals(MemSize.kilobytes(128).toLong(), seenByClient.initialMaxStreamDataUni());
		assertEquals(100, seenByClient.initialMaxStreamsBidi());
		assertEquals(3, seenByClient.initialMaxStreamsUni());

		// Enforced: three unidirectional opens succeed, the fourth is withheld rather than sent.
		openNow(client.openUnidirectional());
		openNow(client.openUnidirectional());
		openNow(client.openUnidirectional());
		Promise<QuicStream> fourth = client.openUnidirectional();
		fixture.pump();

		assertFalse("initial_max_streams_uni = 3 means three, and the fourth waits (FR-029)",
			fourth.isComplete());
		assertEquals(3, client.streamsOpenedLocally());
		assertEquals(1, client.timesBlockedByStreamCountLimit());

		acceptedReads.forEach(Feature05ContractTest::discard);
	}
}
