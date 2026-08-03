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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.inspector.AbstractInspector;
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
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T083 — <b>FR-044, FR-045, CHK078</b>: not one byte of stream payload reaches a log line or an
 * {@link QuicStreamManager.Inspector}. The stream layer's half of feature 03's
 * {@code QuicSecretsStayOutOfLogsTest}, and it uses the same technique: a capturing appender over the
 * whole {@code io.activej.quic} subtree at {@code DEBUG}, a real transfer, and a search of every
 * captured line for a needle that must not be there.
 *
 * <h2>What the needle is, and why it is searched for twice</h2>
 * The payload is a distinctive ASCII marker repeated across the transfer, so that a naive log line —
 * {@code logger.debug("received {}", buf.asString())} — would show it verbatim. It is searched for
 * both as text <b>and</b> as its hex encoding, because the other way a payload leaks is a hex dump,
 * and a test that looked only for the text would sail straight past one.
 *
 * <h2>What the inspector records, and why the check is structural too</h2>
 * The inspector records the {@code toString} of every argument of every callback it is handed. Two
 * things are asserted about it: that no recorded value contains the needle (the behavioural check), and
 * that <b>every parameter of every {@code Inspector} method is a primitive or an enum</b> (the
 * structural one). The second is what makes the first durable: a callback that is handed a
 * {@code ByteBuf} might not leak today, but it is a leak waiting for the first implementation that
 * logs what it receives — so FR-044 forbids the parameter, not merely the leak.
 *
 * <h2>The paths driven</h2>
 * An ordinary transfer, a stream aborted mid-flight, and a {@code STOP_SENDING} — the three paths that
 * log at all in this package. The application error codes those two carry <i>are</i> expected in the
 * logs, and are asserted to be there: they are the peer's routing values, not its data, and the
 * distinction between "identifies the event" and "is the payload" is the whole of SI-6.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.4">RFC 9000 §19.4 — RESET_STREAM Frames</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19.5">RFC 9000 §19.5 — STOP_SENDING Frames</a>
 */
public final class StreamPayloadStaysOutOfLogsTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final int MAX_DRIVE_ROUNDS = 400;

	/** Distinctive enough that a coincidental hit is not a thing that can happen. */
	private static final String MARKER = "PAYLOAD-MARKER-c0ffee-DO-NOT-LOG";

	private static final long APP_ERROR_CODE = 0x0BADF00DL;

	/** Records what it is given, as text, so that a needle search covers the inspector seam too. */
	private static final class RecordingStreamInspector
		extends AbstractInspector<QuicStreamManager.Inspector> implements QuicStreamManager.Inspector {

		final List<String> events = new ArrayList<>();

		@Override
		public void onStreamOpened(long streamId, boolean locallyInitiated, boolean bidirectional) {
			events.add("onStreamOpened " + streamId + ' ' + locallyInitiated + ' ' + bidirectional);
		}

		@Override
		public void onStreamClosed(long streamId) {
			events.add("onStreamClosed " + streamId);
		}

		@Override
		public void onStreamReset(long streamId, boolean byPeer, long applicationErrorCode) {
			events.add("onStreamReset " + streamId + ' ' + byPeer + ' ' + applicationErrorCode);
		}

		@Override
		public void onFlowControlBlocked(long streamId, QuicStreamManager.BlockedBy blockedBy) {
			events.add("onFlowControlBlocked " + streamId + ' ' + blockedBy);
		}

		@Override
		public void onLimitGranted(long streamId, long newLimit) {
			events.add("onLimitGranted " + streamId + ' ' + newLimit);
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;
	private QuicStreamManager serverManager;
	private RecordingStreamInspector clientInspector;
	private RecordingStreamInspector serverInspector;

	private final List<QuicStream> serverStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> serverReads = new ArrayList<>();

	private Logger quicLogger;
	private Level originalLevel;
	private ListAppender<ILoggingEvent> appender;

	@Before
	public void setUp() throws MalformedDataException {
		quicLogger = (Logger) LoggerFactory.getLogger("io.activej.quic");
		originalLevel = quicLogger.getLevel();
		// test/logback-test.xml sets io.activej to "off"; this override on the more specific logger wins
		// for the whole io.activej.quic.* subtree (logback resolves effective level by nearest ancestor).
		quicLogger.setLevel(Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		quicLogger.addAppender(appender);

		loop = new ManualEventloop();
		wire = new QuicWirePair();
		clientInspector = new RecordingStreamInspector();
		serverInspector = new RecordingStreamInspector();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withInspector(clientInspector)
				.build());
		wire.withServerFrameHandlerFactory(connection -> serverManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
				.withInspector(serverInspector)
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
		quicLogger.detachAppender(appender);
		quicLogger.setLevel(originalLevel);
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
		fail("the exchange did not settle within " + MAX_DRIVE_ROUNDS + " rounds");
	}

	/** The marker repeated until it fills {@code size} bytes, so every fragment of the transfer carries it. */
	private static ByteBuf markedPayload(int size) {
		byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(size);
		for (int i = 0; i < size; i++) {
			buf.put(marker[i % marker.length]);
		}
		return buf;
	}

	private QuicStream openClientStream() {
		Promise<QuicStream> opened = clientManager.openBidirectional();
		assertTrue(opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	private static void discard(Promise<ByteBuf> collected) {
		if (!collected.isResult()) return;
		ByteBuf buf = collected.getResult();
		if (buf != null) buf.recycle();
	}

	/**
	 * Asserts that neither the marker nor its hex encoding appears in anything the layer said — to a
	 * logger or to an inspector.
	 */
	private void assertNothingLeakedThePayload() {
		String textNeedle = MARKER.toLowerCase();
		// Long enough that a coincidental hit is impossible, short enough to survive a truncating dump.
		String hexNeedle = HexFormat.of()
			.formatHex(MARKER.getBytes(StandardCharsets.US_ASCII))
			.substring(0, 24);

		assertFalse("logging never happened; the appender is not wired up", appender.list.isEmpty());
		for (ILoggingEvent event : appender.list) {
			String text = (event.getFormattedMessage() + " " + throwableTextOf(event)).toLowerCase();
			assertFalse("a log line leaked stream payload: " + text, text.contains(textNeedle));
			assertFalse("a log line hex-dumped stream payload: " + text, text.contains(hexNeedle));
		}

		for (RecordingStreamInspector inspector : List.of(clientInspector, serverInspector)) {
			for (String event : inspector.events) {
				String lower = event.toLowerCase();
				assertFalse("the inspector was handed stream payload: " + event, lower.contains(textNeedle));
				assertFalse("the inspector was handed hex payload: " + event, lower.contains(hexNeedle));
			}
		}
	}

	private static String throwableTextOf(ILoggingEvent event) {
		IThrowableProxy proxy = event.getThrowableProxy();
		return proxy == null ? "" : String.valueOf(proxy.getMessage());
	}

	private boolean anyLogLineContains(String needle) {
		for (ILoggingEvent event : appender.list) {
			if (event.getFormattedMessage().contains(needle)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- an ordinary transfer

	@Test
	public void anOrdinaryTransferPutsNoPayloadByteInALogLineOrAnInspector() {
		int size = 64 * 1024;

		QuicStream stream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(markedPayload(size)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());

		// The transfer really happened — otherwise this test passes vacuously.
		assertEquals(size, clientManager.bytesSent());
		assertEquals(size, serverManager.bytesDelivered());
		discard(serverReads.get(0));

		assertNothingLeakedThePayload();
		assertFalse("the inspector saw the stream open, so it is genuinely attached",
			clientInspector.events.isEmpty());
	}

	// ---------------------------------------------------------------- an abort mid-flight

	@Test
	public void anAbortLogsItsApplicationErrorCodeAndNotOneByteOfWhatWasBeingSent() {
		QuicStream stream = openClientStream();
		Promise<Void> written = stream.writer().accept(markedPayload(32 * 1024));
		driveUntil(() -> !serverStreams.isEmpty());

		stream.reset(APP_ERROR_CODE);
		driveUntil(() -> written.isComplete() && serverManager.streamsResetByPeer() == 1);
		serverReads.forEach(StreamPayloadStaysOutOfLogsTest::discard);

		assertNothingLeakedThePayload();
		// SI-6's other half: the event must still be *identifiable*. The application error code is the
		// peer's routing value, not its data, so it is expected in the log line rather than forbidden.
		assertTrue("the abort must be diagnosable by its application error code",
			anyLogLineContains(Long.toString(APP_ERROR_CODE)));
		assertTrue(serverInspector.events.stream()
			.anyMatch(e -> e.startsWith("onStreamReset") && e.endsWith(String.valueOf(APP_ERROR_CODE))));
	}

	@Test
	public void aStopSendingLogsItsCodeAndNoPayload() {
		QuicStream stream = openClientStream();
		Promise<Void> written = stream.writer().accept(markedPayload(16 * 1024));
		driveUntil(() -> !serverStreams.isEmpty());

		serverStreams.get(0).stopSending(APP_ERROR_CODE);
		driveUntil(() -> written.isComplete() && clientManager.streamsResetLocally() == 1);
		serverReads.forEach(StreamPayloadStaysOutOfLogsTest::discard);

		assertNothingLeakedThePayload();
		assertTrue(anyLogLineContains(Long.toString(APP_ERROR_CODE)));
	}

	// ---------------------------------------------------------------- the structural half of FR-044

	@Test
	public void noInspectorCallbackCanEvenBeHandedABufferOrAnObject() {
		for (Method method : QuicStreamManager.Inspector.class.getDeclaredMethods()) {
			if (method.isSynthetic()) continue;
			for (Class<?> parameter : method.getParameterTypes()) {
				assertTrue("FR-044: Inspector." + method.getName() + " takes a " + parameter.getName() +
						   ", which is neither a primitive nor an enum — a callback that can be handed an" +
						   " object is a payload leak waiting for its first implementation",
					parameter.isPrimitive() || parameter.isEnum());
				assertFalse("an Inspector must never be handed a ByteBuf (FR-044)",
					ByteBuf.class.isAssignableFrom(parameter));
			}
		}
	}

	@Test
	public void everyCounterStaysReadableWithAnInspectorAttached() {
		// FR-043 and FR-044 are independent: attaching the optional seam must not be what makes the plain
		// counters work, and must not disturb them either.
		QuicStream stream = openClientStream();
		Promise<Void> written = ChannelSuppliers.ofValue(markedPayload(4096)).streamTo(stream.writer());
		driveUntil(() -> written.isComplete() && !serverReads.isEmpty() && serverReads.get(0).isComplete());
		discard(serverReads.get(0));

		assertEquals(1, clientManager.streamsOpenedLocally());
		assertEquals(4096, clientManager.bytesSent());
		assertEquals(1, serverManager.streamsAcceptedFromPeer());
		assertEquals(4096, serverManager.bytesDelivered());

		assertTrue("and the inspector agrees with them",
			clientInspector.events.contains("onStreamOpened " + stream.id() + " true true"));
		assertTrue(serverInspector.events.contains("onStreamOpened " + stream.id() + " false true"));
	}
}
