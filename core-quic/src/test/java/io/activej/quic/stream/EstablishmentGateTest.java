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
import io.activej.common.exception.MalformedDataException;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T097 — FR-042: <b>nothing this layer produces reaches the wire before the connection is
 * established</b>.
 *
 * <h2>Three claims, and why the third needs its own test</h2>
 * <ol>
 *   <li>an open issued before establishment is <b>withheld</b>, not failed;</li>
 *   <li>it resolves on {@code onEstablished};</li>
 *   <li>and <b>not one {@code STREAM} frame</b> — nor any other frame this layer originates — is put
 *       on the wire in the meantime.</li>
 * </ol>
 * The first two are already asserted at the promise level by
 * {@link QuicStreamManagerFrameRoutingTest#anOpenIssuedBeforeEstablishmentIsWithheldUntilTheHandshakeCompletes}
 * and by {@code StreamLimitTest.anOpenWithheldBeforeEstablishmentStaysWithheldIfTheHandshakeGrantsNothing};
 * this class re-states them so that FR-042 has one home, and adds the third, which no promise
 * assertion can see. The distinction matters: an implementation that allocated a stream id
 * optimistically and queued its frames would satisfy both promise assertions and still violate
 * RFC 9000 §4 — until the handshake completes, the peer has granted no stream credit and no
 * flow-control window, so a byte sent under the assumption of either is a byte sent past a limit.
 *
 * <h2>How the wire is observed</h2>
 * A tap sits above the peer's connection and records every frame it is handed, before delegating to
 * the peer's own stream layer. Frames that arrive during the handshake are therefore visible even
 * though the handshake itself carries CRYPTO frames the transport keeps for itself: only what
 * {@code QuicFrameHandler} is given can have come from the layer under test (FR-037).
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4">RFC 9000 §4 — Flow Control</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-4.6">RFC 9000 §4.6 — Controlling Concurrency</a>
 */
public final class EstablishmentGateTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Records every frame the peer's frame-handler seam is offered, in arrival order. */
	private static final class FrameTap implements QuicFrameHandler {
		final List<QuicFrame> received = new ArrayList<>();

		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			// Recorded by class rather than retained: the frame is borrowed, and its payload is the
			// connection's to recycle the moment this returns (DI-1).
			received.add(frame);
		}

		List<Class<?>> kinds() {
			List<Class<?>> kinds = new ArrayList<>();
			for (QuicFrame frame : received) {
				kinds.add(frame.getClass());
			}
			return kinds;
		}
	}

	private ManualEventloop loop;
	private QuicWirePair wire;
	private QuicStreamManager clientManager;

	private final FrameTap serverTap = new FrameTap();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		serverTap.received.clear();
		wire = new QuicWirePair();
		wire.withClientFrameHandlerFactory(connection -> clientManager =
			QuicStreamManager.builder(Reactor.getCurrentReactor(), connection).build());
		wire.withServerFrameHandler(serverTap);
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	// ---------------------------------------------------------------- helpers

	/** Starts the client only: it is handshaking, and the server does not exist yet. */
	private void startClientOnly() {
		wire.startClient(QuicConnectionSettings.create());
		assertNotEquals("the client cannot be established before a server has answered",
			QuicConnectionState.ESTABLISHED, wire.client().state());
	}

	private void completeTheHandshake() throws MalformedDataException {
		wire.acceptServer(QuicConnectionSettings.create());
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	private static ByteBuf payload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	// ---------------------------------------------------------------- the promise half (FR-042)

	@Test
	public void anOpenIssuedBeforeEstablishmentIsWithheldRatherThanFailed() throws Exception {
		startClientOnly();

		Promise<QuicStream> bidi = clientManager.openBidirectional();
		Promise<QuicStream> uni = clientManager.openUnidirectional();

		assertFalse("FR-042: withheld, not failed — a stream id cannot be allocated before the peer has" +
					" said how many streams it will accept", bidi.isComplete());
		assertFalse(uni.isComplete());
		assertEquals("nothing may be allocated before establishment", 0, clientManager.openStreamCount());
		assertEquals(0, clientManager.streamsOpenedLocally());

		completeTheHandshake();

		assertTrue("the withheld open resolves on onEstablished", bidi.isComplete());
		assertTrue(uni.isComplete());
		QuicStream bidiStream = bidi.getResult();
		QuicStream uniStream = uni.getResult();
		assertNotNull(bidiStream);
		assertNotNull(uniStream);
		// RFC 9000 §2.1: the client's first bidirectional stream is 0, its first unidirectional one is 2.
		assertEquals(0, bidiStream.id());
		assertEquals(2, uniStream.id());
		assertEquals(2, clientManager.streamsOpenedLocally());
	}

	// ---------------------------------------------------------------- the wire half (FR-042, FR-037)

	@Test
	public void noFrameThisLayerOriginatesReachesTheWireBeforeEstablishment() throws Exception {
		startClientOnly();

		// Everything an application can ask for before the handshake finishes: two opens, in both
		// directions. None of them can name a stream, so none of them can put a byte on the wire.
		Promise<QuicStream> bidi = clientManager.openBidirectional();
		Promise<QuicStream> uni = clientManager.openUnidirectional();

		completeTheHandshake();

		// Not merely "no STREAM frame": no STREAMS_BLOCKED either. Announcing a stream-count limit before
		// the peer has stated one would carry a value this endpoint invented.
		assertEquals("FR-042: the stream layer contributes nothing until the handshake supplies the peer's" +
					 " limits, and the transport keeps every handshake frame for itself (FR-037)",
			List.of(), serverTap.kinds());
		assertTrue(bidi.isComplete());
		assertTrue(uni.isComplete());
	}

	@Test
	public void aWriteIssuedBeforeEstablishmentSendsNothingUntilTheHandshakeCompletes() throws Exception {
		startClientOnly();

		// The write cannot even be issued before the stream exists, so this is the earliest a write can be
		// attached: to the withheld open's own continuation, which is exactly what an application layered
		// on this would write.
		QuicStream[] stream = new QuicStream[1];
		Promise<Void> written = clientManager.openBidirectional()
			.then(opened -> {
				stream[0] = opened;
				return opened.writer().accept(payload(64));
			});

		assertFalse("the open is withheld, so the write has not begun", written.isComplete());
		assertNull(stream[0]);
		assertEquals("no STREAM frame can exist before the stream does", List.of(), serverTap.kinds());

		completeTheHandshake();

		assertNotNull("the open resolved on establishment and its continuation ran", stream[0]);
		assertTrue("with the peer's window known, the write completes", written.isComplete());
		assertTrue(String.valueOf(written.getException()), written.isResult());
		// ...and only now does a STREAM frame appear, at offset 0 of the stream that was finally allocated.
		assertEquals(List.of(StreamFrame.class), serverTap.kinds());
		StreamFrame frame = (StreamFrame) serverTap.received.get(0);
		assertEquals(0, frame.streamId);
		assertEquals(0, frame.offset);
	}

	@Test
	public void sendingBeginsOnEstablishmentAndNotBefore() throws Exception {
		startClientOnly();
		Promise<QuicStream> opened = clientManager.openBidirectional();

		// The datagrams the client has queued so far are the handshake's; the assertion is that none of
		// them carries anything this layer produced, which the tap proves once they are delivered.
		completeTheHandshake();

		QuicStream stream = opened.getResult();
		assertNotNull(stream);
		assertEquals("nothing was sent for this stream during the handshake", List.of(), serverTap.kinds());

		Promise<Void> written = stream.writer().accept(payload(16));
		wire.pump();

		assertTrue(String.valueOf(written.getException()), written.isResult());
		assertEquals("sending begins after onEstablished", List.of(StreamFrame.class), serverTap.kinds());
	}
}
