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
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3TestBytes.cancelPushFrame;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.maxPushIdFrame;
import static io.activej.http3.testutil.Http3TestBytes.pushPromiseFrame;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * T092 / FR-040, US8 scenarios 1–4: server push is permanently out of scope, and every construct a
 * peer can reach for is refused with its <b>exact</b> RFC 9114 code rather than ignored.
 * <p>
 * This endpoint never sends MAX_PUSH_ID, so its own push limit is 0 for the life of every connection
 * and it never promises a push id. Three consequences are asserted here, one per code:
 * <ul>
 *   <li>a push stream or a PUSH_PROMISE arriving at a <b>client</b> names a push id it never
 *       granted — {@code H3_ID_ERROR} (RFC 9114 §4.6, §7.2.5), and so does a CANCEL_PUSH for a push
 *       id nobody ever promised (RFC 9114 §7.2.3);</li>
 *   <li>MAX_PUSH_ID travelling the wrong way — server to client — is a frame where RFC 9114 §7.2.7
 *       does not permit it: {@code H3_FRAME_UNEXPECTED};</li>
 *   <li>MAX_PUSH_ID travelling the right way is recorded and acted upon by nothing, and a successor
 *       <i>below</i> it is {@code H3_ID_ERROR}.</li>
 * </ul>
 * The peer is always the bare {@code QuicStreamManager} of {@link Http3WirePair}, which is the only
 * way to send frames the module's own encoders would never emit.
 */
public final class Http3PushRefusalTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** Whatever the endpoint under test writes and nothing reads; recycled in {@code tearDown}. */
	private final ByteBufs received = new ByteBufs();

	/** Every stream the endpoint under test opened — one control stream, and nothing else ever. */
	private final List<QuicStream> streamsOpenedByTheEndpoint = new ArrayList<>();

	/** The peer's view of a request stream the client opened, for the PUSH_PROMISE case. */
	private @Nullable QuicStream peerRequestStream;

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

	// ---------------------------------------------------------------- US8 §1 — a push a client never asked for

	@Test
	public void aPushStreamOpenedByTheServerIsAnIdError() {
		connectWithClientH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);
		assertEquals("this client granted no push id, so its push limit is 0", 0, h3.pushLimitGranted());

		QuicStream push = wire.openNow(wire.serverStreams().openUnidirectional());
		push.writer().accept(concat(streamHeader(Http3StreamType.PUSH.code()), Http3TestBytes.bytes(0x00)));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		// RFC 9114 §4.6: a push stream against a push limit of 0 names an id that was never granted.
		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
	}

	@Test
	public void aPushStreamOpenedByAClientIsAStreamCreationError() {
		// The other half of the same dispatch, and deliberately a *different* code: RFC 9114 §6.2.2 refuses
		// this one because only a server pushes at all, not because of any push limit — so a server saying
		// H3_ID_ERROR here would name a rule the client did not break.
		connectWithServerH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		QuicStream push = wire.openNow(wire.clientStreams().openUnidirectional());
		push.writer().accept(concat(streamHeader(Http3StreamType.PUSH.code()), Http3TestBytes.bytes(0x00)));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_STREAM_CREATION_ERROR, h3.closedWithErrorCode());
	}

	@Test
	public void aPushPromiseOnARequestStreamIsAnIdError() {
		connectWithClientH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		Http3RequestStream requestStream = openRequestStream();
		requestStream.sendRequest(HttpRequest.get("https://" + Http3TestTls.SERVER_NAME + "/").build());
		Promise<HttpResponse> response = requestStream.receiveResponse();
		wire.driveUntil(() -> peerRequestStream != null);

		peerRequestStream.writer().accept(pushPromiseFrame(0));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		// RFC 9114 §7.2.5: a client that has sent no MAX_PUSH_ID may not be promised a push — and that is
		// an *id* error, not the generic "this frame does not belong on this stream".
		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
		assertTrue("the request it arrived on fails with it: " + response, response.isException());
		assertEquals(Http3Errors.H3_ID_ERROR, ((Http3Exception) response.getException()).errorCode());
	}

	// ---------------------------------------------------------------- US8 §3 — MAX_PUSH_ID the wrong way

	@Test
	public void aClientReceivingMaxPushIdIsAFrameUnexpected() {
		connectWithClientH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		control.writer().accept(maxPushIdFrame(10));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		// RFC 9114 §7.2.7 makes MAX_PUSH_ID client-to-server only, so this is a direction violation rather
		// than anything about the identifier it carried.
		assertEquals(Http3Errors.H3_FRAME_UNEXPECTED, h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- US8 §2 — MAX_PUSH_ID the right way

	@Test
	public void aServerRecordsMaxPushIdAndActsOnNothing() {
		connectWithServerH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);
		assertEquals(Http3Connection.NO_PUSH_ID, h3.maxPushIdReceived());

		control.writer().accept(maxPushIdFrame(7));
		wire.driveUntil(() -> h3.maxPushIdReceived() == 7);

		// Recorded, and that is the whole of it: the connection is untouched, and this server has still
		// opened nothing but its own control stream — a granted push id buys a peer no push.
		assertEquals(State.READY, h3.state());
		assertEquals(0, h3.pushLimitGranted());
		assertEquals("the endpoint opened its control stream and nothing else", 1, streamsOpenedByTheEndpoint.size());

		// A repeat, and a rise, are both fine — the limit is non-decreasing.
		control.writer().accept(concat(maxPushIdFrame(7), maxPushIdFrame(9)));
		wire.driveUntil(() -> h3.maxPushIdReceived() == 9);
		assertEquals(State.READY, h3.state());
	}

	@Test
	public void aMaxPushIdBelowTheOneAlreadyReceivedIsAnIdError() {
		connectWithServerH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		control.writer().accept(maxPushIdFrame(7));
		wire.driveUntil(() -> h3.maxPushIdReceived() == 7);

		control.writer().accept(maxPushIdFrame(6));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		// RFC 9114 §7.2.7: a limit may only ever rise. The one already announced stands.
		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
		assertEquals(7, h3.maxPushIdReceived());
	}

	// ---------------------------------------------------------------- US8 §4 — CANCEL_PUSH for nothing

	@Test
	public void aCancelPushForAnUnpromisedIdIsAnIdErrorOnAServer() {
		connectWithServerH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		control.writer().accept(cancelPushFrame(0));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		// This server promises no push ever, so every push id a peer cancels is one it was never promised
		// (RFC 9114 §7.2.3) — 0 included, which is the first id that would have existed.
		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
	}

	@Test
	public void aCancelPushForAnUnpromisedIdIsAnIdErrorOnAClient() {
		connectWithClientH3();
		QuicStream control = openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		control.writer().accept(cancelPushFrame(3));
		wire.driveUntil(() -> h3.state() == State.CLOSED);

		assertEquals(Http3Errors.H3_ID_ERROR, h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- what a conforming peer does

	@Test
	public void aConnectionThatSeesNoPushConstructStaysOpen() {
		// The negative control for every assertion above: nothing here refuses anything, so a failure in
		// this test means the push rules fire on a connection that never mentioned push.
		connectWithServerH3();
		openPeerControlStream();
		wire.driveUntil(() -> h3.state() == State.READY);

		assertNotEquals(State.CLOSED, h3.state());
		assertEquals(Http3Connection.NO_ERROR_CODE, h3.closedWithErrorCode());
		assertEquals(Http3Connection.NO_PUSH_ID, h3.maxPushIdReceived());
	}

	// ---------------------------------------------------------------- harness

	/** A server-side {@link Http3Connection}, with the bare stream layer standing in for the client. */
	private void connectWithServerH3() {
		wire = new Http3WirePair(loop)
			.withClientStreamListener(this::onEndpointStream)
			.withServerHandlerFactory(connection -> {
				h3 = Http3Connection.create(reactor(), connection);
				return h3.streamManager();
			})
			.connect();
	}

	/** A client-side {@link Http3Connection}, with the bare stream layer standing in for the server. */
	private void connectWithClientH3() {
		wire = new Http3WirePair(loop)
			.withServerStreamListener(this::onEndpointStream)
			.withClientHandlerFactory(connection -> {
				h3 = Http3Connection.create(reactor(), connection);
				return h3.streamManager();
			})
			.connect();
	}

	private void onEndpointStream(QuicStream stream) {
		if (stream.isBidirectional()) {
			peerRequestStream = stream;
		} else {
			streamsOpenedByTheEndpoint.add(stream);
		}
		Http3TestBytes.collect(stream, received);
	}

	/** A well-formed peer control stream: type {@code 0x00} followed by a minimal SETTINGS frame. */
	private QuicStream openPeerControlStream() {
		QuicStream control = wire.openNow(peerStreams().openUnidirectional());
		control.writer().accept(concat(
			streamHeader(Http3StreamType.CONTROL.code()),
			settingsFrame(new long[]{0x01, 0x07}, new long[]{0, 0})));
		return control;
	}

	/** The bare stream layer of whichever side is <i>not</i> the endpoint under test. */
	private QuicStreamManager peerStreams() {
		return h3.role() == Role.SERVER ? wire.clientStreams() : wire.serverStreams();
	}

	private Http3RequestStream openRequestStream() {
		Promise<Http3RequestStream> opened = h3.openRequestStream();
		if (!opened.isResult()) {
			throw new AssertionError("the request stream did not open: " + opened, opened.getException());
		}
		return opened.getResult();
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
