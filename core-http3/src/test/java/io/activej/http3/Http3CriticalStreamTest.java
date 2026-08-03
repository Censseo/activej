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

package io.activej.http3;

import io.activej.bytebuf.ByteBufs;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.GoAwayFrame;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamStopSendingException;
import io.activej.quic.stream.SendState;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T057 / FR-013, FR-014, FR-015, FR-044: the RFC 9114 §6.2 rules about streams that must exist
 * exactly once, must never close, and must never be created at all.
 * <p>
 * The endpoint under test is always the <b>server</b>'s {@link Http3Connection}, except for the
 * server-initiated bidirectional case, which by definition needs the client to be the one judging.
 * The other side is the bare {@code QuicStreamManager} of {@link Http3WirePair}, which is what lets a
 * test do the things a conforming peer never does.
 */
public final class Http3CriticalStreamTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** A GREASE stream type of the RFC 9114 §6.2.3 {@code 0x1f * N + 0x21} form. */
	private static final long GREASE_STREAM_TYPE = 0x21;

	private final ByteBufs received = new ByteBufs();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Connection h3;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		received.recycle();
		loop.close();
	}

	@Test
	public void aSecondControlStreamIsAStreamCreationError() {
		connectWithServerH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_STREAM_CREATION_ERROR, h3.closedWithErrorCode());
	}

	@Test
	public void aFirstControlFrameOtherThanSettingsIsMissingSettings() {
		connectWithServerH3();
		QuicStream control = wire.openNow(wire.clientStreams().openUnidirectional());
		control.writer().accept(concat(
			streamHeader(Http3StreamType.CONTROL.code()),
			Http3TestBytes.frame(GoAwayFrame.TYPE, new byte[]{0x00})));

		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_MISSING_SETTINGS, h3.closedWithErrorCode());
	}

	@Test
	public void aPeerResetOfTheControlStreamIsAClosedCriticalStream() {
		connectWithServerH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		control.reset(Http3Errors.H3_NO_ERROR);
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_CLOSED_CRITICAL_STREAM, h3.closedWithErrorCode());
	}

	@Test
	public void aCleanEndOfTheControlStreamIsAlsoAClosedCriticalStream() {
		connectWithServerH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		// The sender's half closes normally — RFC 9114 §6.2.1 forbids that just as it forbids a reset.
		control.writer().acceptEndOfStream();
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_CLOSED_CRITICAL_STREAM, h3.closedWithErrorCode());
	}

	@Test
	public void anUnknownUnidirectionalStreamTypeIsAbandonedWithStopSending() {
		connectWithServerH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		QuicStream grease = wire.openNow(wire.clientStreams().openUnidirectional());
		grease.writer().accept(concat(streamHeader(GREASE_STREAM_TYPE), Http3TestBytes.bytes(1, 2, 3, 4)));

		// STOP_SENDING makes the sender abort its own sending half (RFC 9000 §3.5).
		wire.driveUntil(() -> grease.sendState() == SendState.RESET_SENT || grease.sendState() == SendState.RESET_RECVD);

		Promise<Void> afterwards = grease.writer().accept(Http3TestBytes.bytes(5, 6));
		assertTrue("the write must report the peer's STOP_SENDING", afterwards.isException());
		assertTrue(String.valueOf(afterwards.getException()),
			afterwards.getException() instanceof QuicStreamStopSendingException);
		assertEquals(Http3Errors.H3_STREAM_CREATION_ERROR,
			((QuicStreamStopSendingException) afterwards.getException()).applicationErrorCode());

		assertEquals("the stream is abandoned, not buffered", 1, h3.unidirectionalStreamsAbandoned());
		assertNotEquals("abandoning a GREASE stream is not a connection error", State.CLOSED, h3.state());
	}

	@Test
	public void aServerInitiatedBidirectionalStreamIsAStreamCreationError() {
		// The judging endpoint here has to be the client: a server-initiated stream is one the client
		// receives (FR-044, RFC 9114 §6.1).
		wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withClientHandlerFactory(connection -> {
				h3 = Http3Connection.create(reactor(), connection);
				return h3.streamManager();
			})
			.connect();

		wire.driveUntil(() -> h3.state() == State.SETTINGS_SENT);
		QuicStream serverInitiated = wire.openNow(wire.serverStreams().openBidirectional());
		// A stream nobody writes on puts nothing on the wire, so the peer would never learn of it.
		serverInitiated.writer().accept(Http3TestBytes.bytes(0x01));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_STREAM_CREATION_ERROR, h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- helpers

	private void connectWithServerH3() {
		wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withServerHandlerFactory(connection -> {
				h3 = Http3Connection.create(reactor(), connection);
				return h3.streamManager();
			})
			.connect();
	}

	/** A well-formed peer control stream: type {@code 0x00} followed by a minimal SETTINGS frame. */
	private QuicStream openPeerControlStream() {
		QuicStream control = wire.openNow(wire.clientStreams().openUnidirectional());
		control.writer().accept(concat(
			streamHeader(Http3StreamType.CONTROL.code()),
			settingsFrame(new long[]{0x01, 0x07}, new long[]{0, 0})));
		return control;
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
