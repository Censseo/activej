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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.ResetStreamFrame;
import io.activej.quic.codec.StopSendingFrame;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicTransportErrors;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.stream.testutil.StreamFrameInjector;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.quic.stream.testutil.StreamFrameInjector.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T075 — user story 5, scenario 3 (FR-005, FR-007): a peer naming a stream it has no business naming,
 * and an application touching a half its stream does not have.
 *
 * <h2>Two categories that must not be confused</h2>
 * <table>
 *   <caption>What each category is and how it surfaces</caption>
 *   <tr><th>Category</th><th>Cause</th><th>Surfaced as</th></tr>
 *   <tr><td><b>Wire violation</b></td>
 *       <td>the peer sent a frame RFC 9000 §2.1 says only the other role may send, or named a
 *           locally-initiated stream this endpoint never opened</td>
 *       <td>{@code QuicTransportException(STREAM_STATE_ERROR)} (0x05) — the connection is over</td></tr>
 *   <tr><td><b>Caller misuse</b></td>
 *       <td>the application asked for the send half of a receive-only stream, or the receive half of a
 *           send-only one</td>
 *       <td>plain {@link IllegalStateException} — a programmer error, nothing to do with the peer, and
 *           deliberately outside both exception hierarchies a {@code catch} block would name
 *           (FR-007)</td></tr>
 * </table>
 * The second row is what an implementation gets wrong by reaching for the nearest protocol exception,
 * so its <b>type</b> is asserted here, not merely the fact that something was thrown.
 *
 * <h2>Which identifiers are illegal for whom (RFC 9000 §2.1)</h2>
 * A unidirectional stream carries data only from its initiator. So, seen from a server:
 * {@code STREAM} or {@code RESET_STREAM} on a <i>server-initiated unidirectional</i> stream (id 3)
 * arrives on a half only the server may send; {@code STOP_SENDING} on a <i>client-initiated
 * unidirectional</i> stream (id 2) asks the server to stop a send half it does not have. Both are
 * {@code STREAM_STATE_ERROR}. The rules are mirror images, so {@link StreamFrameInjector#intoClient}
 * asserts the other half of the identifier space too.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-2.1">RFC 9000 §2.1 — Stream Types and Identifiers</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-3">RFC 9000 §3 — Stream States</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class StreamStateViolationTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Client-initiated bidirectional #0 — both endpoints own both halves of it. */
	private static final long CLIENT_BIDI = 0;

	/** Server-initiated bidirectional #0 — a server that never opened it has never heard of it. */
	private static final long SERVER_BIDI = 1;

	/** Client-initiated unidirectional #0 — the server may only receive on it. */
	private static final long CLIENT_UNI = 2;

	/** Server-initiated unidirectional #0 — the server may only send on it. */
	private static final long SERVER_UNI = 3;

	private static final long APP_ERROR_CODE = 0x0BADCAFEL;

	private StreamFrameInjector injector;

	@Before
	public void setUp() throws MalformedDataException {
		injector = StreamFrameInjector.intoServer(QuicConnectionSettings.create());
	}

	@After
	public void tearDown() {
		injector.close();
	}

	// ---------------------------------------------------------------- a frame only the other role may send

	@Test
	public void streamDataOnAStreamOnlyThisEndpointMaySendOnIsAStreamStateError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR, stream(SERVER_UNI, 0, false, "x"));

		assertEquals("nothing may be opened on behalf of a frame that is refused", 0,
			injector.manager().openStreamCount());
		assertTrue(injector.accepted().isEmpty());
	}

	@Test
	public void aResetOnAStreamOnlyThisEndpointMaySendOnIsAStreamStateError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new ResetStreamFrame(SERVER_UNI, APP_ERROR_CODE, 0));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void stopSendingOnAStreamThisEndpointMayOnlyReceiveOnIsAStreamStateError() {
		// The server has no sending half of a client-initiated unidirectional stream, so there is nothing
		// for a STOP_SENDING to stop (RFC 9000 §19.5).
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new StopSendingFrame(CLIENT_UNI, APP_ERROR_CODE));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void aClientRejectsAFrameOnlyAClientMaySendOn() throws MalformedDataException {
		// The mirror image: to a client, a client-initiated unidirectional stream is send-only.
		try (StreamFrameInjector client = StreamFrameInjector.intoClient(QuicConnectionSettings.create())) {
			client.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR, stream(CLIENT_UNI, 0, false, "x"));
			client.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
				new StopSendingFrame(SERVER_UNI, APP_ERROR_CODE));
		}
	}

	// ---------------------------------------------------------------- a locally-initiated stream never opened

	@Test
	public void streamDataOnALocallyInitiatedStreamNeverOpenedIsAStreamStateError() {
		// RFC 9000 §19.8: only this endpoint can open a server-initiated stream, and it has opened none.
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR, stream(SERVER_BIDI, 0, false, "x"));

		assertEquals(0, injector.manager().openStreamCount());
	}

	@Test
	public void aResetOnALocallyInitiatedStreamNeverOpenedIsAStreamStateError() {
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new ResetStreamFrame(SERVER_BIDI, APP_ERROR_CODE, 0));
	}

	@Test
	public void stopSendingOnALocallyInitiatedStreamNeverOpenedIsAStreamStateError() {
		// STOP_SENDING on a server-initiated unidirectional stream is legal in principle — the server does
		// own that send half — but this one was never opened, so it does not exist.
		injector.rejectsWith(QuicTransportErrors.STREAM_STATE_ERROR,
			new StopSendingFrame(SERVER_UNI, APP_ERROR_CODE));
	}

	@Test
	public void aFrameForALocallyInitiatedStreamThatWasOpenedAndReleasedIsIgnored() {
		QuicStream opened = openLocalUnidirectional();
		long streamId = opened.id();
		opened.reset(APP_ERROR_CODE);

		// Not an error and not a resurrection: a frame still in flight for a stream this endpoint has
		// finished with is routine. The distinction from the case above is `nextOrdinal`, not the map.
		injector.accepts(new StopSendingFrame(streamId, APP_ERROR_CODE));
	}

	// ---------------------------------------------------------------- caller misuse (FR-007)

	@Test
	public void writingToAReceiveOnlyStreamIsAnIllegalStateExceptionAndNotAWireError() {
		injector.accepts(stream(CLIENT_UNI, 0, false, "x"));
		QuicStream receiveOnly = injector.accepted(0);
		assertEquals(CLIENT_UNI, receiveOnly.id());
		assertFalse(receiveOnly.hasSendPart());

		IllegalStateException e = assertThrows(IllegalStateException.class, receiveOnly::writer);

		assertNotAWireError(e);
		assertEquals("a total accessor stays total", SendState.NONE, receiveOnly.sendState());
		drain(receiveOnly);
	}

	@Test
	public void resettingAReceiveOnlyStreamIsAnIllegalStateException() {
		injector.accepts(stream(CLIENT_UNI, 0, false, "x"));
		QuicStream receiveOnly = injector.accepted(0);

		assertNotAWireError(assertThrows(IllegalStateException.class, () -> receiveOnly.reset(APP_ERROR_CODE)));
		drain(receiveOnly);
	}

	@Test
	public void readingFromASendOnlyStreamIsAnIllegalStateExceptionAndNotAWireError() {
		QuicStream sendOnly = openLocalUnidirectional();
		assertFalse(sendOnly.hasReceivePart());

		IllegalStateException e = assertThrows(IllegalStateException.class, sendOnly::reader);

		assertNotAWireError(e);
		assertEquals(ReceiveState.NONE, sendOnly.receiveState());
	}

	@Test
	public void stoppingASendOnlyStreamIsAnIllegalStateException() {
		QuicStream sendOnly = openLocalUnidirectional();

		assertNotAWireError(assertThrows(IllegalStateException.class,
			() -> sendOnly.stopSending(APP_ERROR_CODE)));
	}

	@Test
	public void bothHalvesOfABidirectionalStreamAreReachable() {
		injector.accepts(stream(CLIENT_BIDI, 0, false, "x"));
		QuicStream bidi = injector.accepted(0);

		assertTrue(bidi.hasSendPart());
		assertTrue(bidi.hasReceivePart());
		assertNotNull(bidi.reader());
		assertNotNull(bidi.writer());
		drain(bidi);
	}

	// ---------------------------------------------------------------- the peer-visible half

	@Test
	public void aStateViolationClosesTheConnectionWithStreamStateError() throws Exception {
		injector.sendOverTheWire(stream(SERVER_UNI, 0, false, "x"));

		injector.assertConnectionClosedWith(QuicTransportErrors.STREAM_STATE_ERROR);
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * FR-007, and the whole point of T081: caller misuse must be visibly outside <b>both</b> hierarchies
	 * a {@code catch} block would name for a peer condition.
	 */
	private static void assertNotAWireError(Throwable e) {
		// Declared as Throwable deliberately: `e instanceof QuicTransportException` does not even compile
		// against a static type of IllegalStateException, which is the distinctness this asserts, but a
		// test cannot state a property the compiler refuses to let it write.
		assertTrue("caller misuse is an IllegalStateException", e instanceof IllegalStateException);
		assertFalse("caller misuse must not be a transport error", e instanceof QuicTransportException);
		assertFalse("caller misuse must not be a stream error", e instanceof QuicStreamException);
		assertTrue("and it must stay unchecked", e instanceof RuntimeException);
		assertNull("nor may it smuggle a wire error in as a cause", e.getCause());
	}

	private QuicStream openLocalUnidirectional() {
		Promise<QuicStream> opened = injector.manager().openUnidirectional();
		assertTrue("the peer grants unidirectional streams by default", opened.isResult());
		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		return stream;
	}

	/** Takes the buffered byte so that the harness's leak rule has nothing left to complain about. */
	private static void drain(QuicStream stream) {
		Promise<ByteBuf> read = stream.reader().get();
		assertTrue(read.isResult());
		ByteBuf buf = read.getResult();
		assertNotNull(buf);
		buf.recycle();
	}
}
