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
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.http3.frame.Http3StreamType.CONTROL;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.goAwayFrame;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * T088 / FR-020: successive GOAWAY identifiers must not increase (RFC 9114 §5.2). A peer may narrow
 * what it will still process; it may never widen it, because a client has already begun retrying
 * everything the first identifier excluded.
 * <p>
 * The endpoint under test is the <b>server</b>'s {@link Http3Connection}, with the bare stream layer of
 * {@link Http3WirePair} standing in for the client — which is what lets the test send the second GOAWAY
 * a conforming peer never would. What the identifier <i>means</i> to a server (a push id, in a space
 * this implementation never grants an id in) is beside the point here: the ordering rule is the same in
 * both directions.
 */
public final class Http3GoAwayOrderingTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

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
	public void aLowerSuccessorIsAccepted() {
		QuicStream control = connectAndOpenPeerControlStream();

		sendGoAway(control, 8);
		assertEquals(8, h3.goAwayReceivedId());
		assertEquals(State.GOING_AWAY, h3.state());

		sendGoAway(control, 4);

		assertEquals("RFC 9114 §5.2: narrowing what will still be processed is legal", 4, h3.goAwayReceivedId());
		assertNotEquals(State.CLOSED, h3.state());
		assertEquals(Http3Connection.NO_ERROR_CODE, h3.closedWithErrorCode());
	}

	@Test
	public void anEqualSuccessorIsAccepted() {
		QuicStream control = connectAndOpenPeerControlStream();

		sendGoAway(control, 4);
		sendGoAway(control, 4);

		assertEquals(4, h3.goAwayReceivedId());
		assertNotEquals(State.CLOSED, h3.state());
		assertEquals(Http3Connection.NO_ERROR_CODE, h3.closedWithErrorCode());
	}

	@Test
	public void aHigherSuccessorIsAnIdError() {
		QuicStream control = connectAndOpenPeerControlStream();

		sendGoAway(control, 4);
		control.writer().accept(goAwayFrame(8));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
		assertEquals("the identifier already announced is the one that stands", 4, h3.goAwayReceivedId());
	}

	@Test
	public void aFirstGoAwayOfAnyIdentifierIsAccepted() {
		QuicStream control = connectAndOpenPeerControlStream();

		sendGoAway(control, 1 << 20);

		assertEquals(1 << 20, h3.goAwayReceivedId());
		assertNotEquals(State.CLOSED, h3.state());
	}

	// ---------------------------------------------------------------- harness

	/** Writes one GOAWAY and drives until the connection has read a frame off the control stream. */
	private void sendGoAway(QuicStream control, long id) {
		long framesBefore = h3.controlFramesReceived();
		control.writer().accept(goAwayFrame(id));
		wire.driveUntil(() -> h3.controlFramesReceived() > framesBefore || h3.state() == State.CLOSED);
	}

	private QuicStream connectAndOpenPeerControlStream() {
		wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withServerHandlerFactory(connection -> {
				h3 = Http3Connection.create(reactor(), connection);
				return h3.streamManager();
			})
			.connect();

		// A well-formed peer control stream: type 0x00 followed by a minimal SETTINGS frame.
		QuicStream control = wire.openNow(wire.clientStreams().openUnidirectional());
		control.writer().accept(concat(
			streamHeader(CONTROL.code()),
			settingsFrame(new long[]{0x01, 0x07}, new long[]{0, 0})));
		wire.driveUntil(() -> h3.state() == State.READY);
		return control;
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
