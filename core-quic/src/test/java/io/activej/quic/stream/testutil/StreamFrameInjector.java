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

package io.activej.quic.stream.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.recycle.Recyclers;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.codec.StreamFrame;
import io.activej.quic.connection.QuicConnection;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.connection.QuicConnectionState;
import io.activej.quic.connection.QuicFrameHandler;
import io.activej.quic.connection.QuicTransportException;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.stream.QuicStreamManager;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A hostile-input harness for user story 5: hands a <b>hand-built</b> {@link QuicFrame} straight to an
 * established {@link QuicStreamManager} and captures the {@link QuicTransportException} it answers
 * with — or its absence.
 *
 * <h2>Why the frame is hand-built rather than sent</h2>
 * Every row of the spec's Error Scenarios table describes something a <i>conforming</i> peer never
 * does, so most of them cannot be staged by driving a second endpoint of this implementation: there
 * is no sequence of application calls that makes it contradict a final size, name a stream only its
 * peer may open, or claim an offset past 2^62-1. Constructing the frame directly is the only way to
 * reach the check, and reaching the check is the point.
 * <p>
 * The connection underneath is nevertheless <b>real and established</b> — a {@link QuicWirePair} that
 * has completed a genuine handshake — because a stream manager is inert without it: stream limits,
 * flow-control windows and the peer's transport parameters all arrive with the handshake, and a
 * manager holding none of them would reject hostile input for the wrong reason (FR-042).
 *
 * <h2>Production carries no hook (CHK090)</h2>
 * Injection goes through {@link QuicStreamManager#onFrame} — the same public
 * {@link QuicFrameHandler} method a connection calls — with the same ownership rules. Nothing in
 * {@code src/main} knows this class exists.
 *
 * <h2>What a rejection does, and does not, do here</h2>
 * In production the connection layer turns a {@code QuicTransportException} from a frame handler into
 * a {@code CONNECTION_CLOSE} and tears the connection down. Injecting bypasses that, so the manager
 * stays usable and a test can keep asserting on the state the rejected frame must <i>not</i> have
 * left behind. Use {@link #sendOverTheWire} for the other half of the story: that the code the spec
 * names really does reach the peer.
 *
 * <h2>Buffer ownership (DI-1)</h2>
 * <ul>
 *   <li>{@link #inject} and its assertion wrappers <b>borrow</b> the frame and recycle it afterwards
 *       on every path, exactly as {@code QuicConnection.openAndHandle} does.</li>
 *   <li>{@link #acknowledge} and {@link #lose} <b>pass ownership in</b>, matching
 *       {@code onFrameAcknowledged} / {@code onFrameLost}.</li>
 *   <li>{@link #sendOverTheWire} passes ownership to the sending connection.</li>
 * </ul>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1 — Transport Error Codes</a>
 */
public final class StreamFrameInjector implements AutoCloseable {
	/** The sending side has no stream layer at all, so nothing local stops it from misbehaving. */
	private static final class SilentHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			// Deliberately empty: the hostile side interprets nothing, so that no local rule of this
			// implementation can stop it from sending what the tests need it to send.
		}
	}

	private final ManualEventloop loop;
	private final QuicWirePair wire;
	private final boolean managerOnServer;
	private final List<QuicStream> accepted = new ArrayList<>();

	private @Nullable QuicStreamManager manager;

	private StreamFrameInjector(
		boolean managerOnServer, QuicConnectionSettings receiver, QuicConnectionSettings sender
	) throws MalformedDataException {
		// The manual clock first, so the connections below are built on it rather than on the system
		// clock — see ManualEventloop for why the two cannot be mixed.
		this.loop = new ManualEventloop();
		this.managerOnServer = managerOnServer;
		this.wire = new QuicWirePair();
		if (managerOnServer) {
			wire.withClientFrameHandler(new SilentHandler());
			wire.withServerFrameHandlerFactory(this::buildManager);
			wire.startClient(sender);
			wire.acceptServer(receiver);
		} else {
			wire.withClientFrameHandlerFactory(this::buildManager);
			wire.withServerFrameHandler(new SilentHandler());
			wire.startClient(receiver);
			wire.acceptServer(sender);
		}
		wire.pump();
		assertEquals("the fixture must inject into an established connection",
			QuicConnectionState.ESTABLISHED, receiver().state());
	}

	private QuicStreamManager buildManager(QuicConnection connection) {
		return manager = QuicStreamManager.builder(Reactor.getCurrentReactor(), connection)
			.withStreamListener(accepted::add)
			.build();
	}

	/**
	 * A harness whose <b>server</b> carries the stream manager under test and whose client is a bare
	 * connection with no stream layer — the shape every hostile-input case needs, since the hostile
	 * side must be free of the very rules being tested.
	 *
	 * @param receiverSettings the settings of the endpoint under test: its advertised windows, stream
	 *                         counts and local bounds
	 */
	public static StreamFrameInjector intoServer(QuicConnectionSettings receiverSettings)
		throws MalformedDataException {
		return new StreamFrameInjector(true, receiverSettings, QuicConnectionSettings.create());
	}

	public static StreamFrameInjector intoServer(
		QuicConnectionSettings receiverSettings, QuicConnectionSettings senderSettings
	) throws MalformedDataException {
		return new StreamFrameInjector(true, receiverSettings, senderSettings);
	}

	/**
	 * The mirror image of {@link #intoServer}: the <b>client</b> carries the manager under test. The
	 * two roles see opposite halves of RFC 9000 §2.1's identifier rules, so a rule asserted from one
	 * side only is a rule asserted for half of the identifier space.
	 */
	public static StreamFrameInjector intoClient(QuicConnectionSettings receiverSettings)
		throws MalformedDataException {
		return new StreamFrameInjector(false, receiverSettings, QuicConnectionSettings.create());
	}

	// ---------------------------------------------------------------- the fixture

	/** The stream manager under test, above the receiving connection. */
	public QuicStreamManager manager() {
		QuicStreamManager m = manager;
		assertNotNull("the frame handler factory never ran", m);
		return m;
	}

	/** The connection the manager is the stream layer of. */
	public QuicConnection receiver() {
		return managerOnServer ? wire.server() : wire.client();
	}

	/** The hostile side: a real connection with no stream layer above it. */
	public QuicConnection sender() {
		return managerOnServer ? wire.client() : wire.server();
	}

	public QuicWirePair wire() {
		return wire;
	}

	public ManualEventloop loop() {
		return loop;
	}

	/** Streams the manager announced to its listener, in announcement order (FR-004). */
	public List<QuicStream> accepted() {
		return accepted;
	}

	public QuicStream accepted(int index) {
		assertTrue("expected at least " + (index + 1) + " accepted streams, got " + accepted.size(),
			index < accepted.size());
		return accepted.get(index);
	}

	// ---------------------------------------------------------------- injection

	/**
	 * Routes one hand-built frame, then recycles it — precisely what {@code QuicConnection} does with a
	 * frame it parsed out of a datagram.
	 */
	public void inject(QuicFrame frame) throws QuicTransportException {
		try {
			manager().onFrame(receiver(), EncryptionLevel.ONE_RTT, frame);
		} finally {
			Recyclers.recycle(frame);
		}
	}

	/** {@link #inject} for a run of frames that must all be accepted. */
	public void injectAll(QuicFrame... frames) {
		for (QuicFrame frame : frames) {
			accepts(frame);
		}
	}

	/** Asserts the frame is accepted, and reports the rejection as a failure rather than an error. */
	public void accepts(QuicFrame frame) {
		try {
			inject(frame);
		} catch (QuicTransportException e) {
			fail("the frame was expected to be accepted, but was rejected with " +
				 Long.toHexString(e.errorCode()) + ": " + e.reasonPhrase());
		}
	}

	/** Asserts the frame is rejected, and returns the exception for further assertions. */
	public QuicTransportException rejects(QuicFrame frame) {
		try {
			inject(frame);
		} catch (QuicTransportException e) {
			return e;
		}
		fail("the frame was expected to be rejected, but was accepted");
		throw new AssertionError("unreachable");
	}

	/**
	 * Asserts the frame is rejected with exactly the RFC 9000 §20.1 code the spec's Error Scenarios
	 * table names for it — never merely "some plausible error".
	 */
	public QuicTransportException rejectsWith(long expectedErrorCode, QuicFrame frame) {
		QuicTransportException e = rejects(frame);
		assertEquals("RFC 9000 §20.1 error code, expected 0x" + Long.toHexString(expectedErrorCode) +
					 " but the reason given was: " + e.reasonPhrase(),
			expectedErrorCode, e.errorCode());
		return e;
	}

	/** Hands the frame to {@code onFrameAcknowledged}; <b>ownership passes in</b>. */
	public void acknowledge(QuicFrame frame) {
		manager().onFrameAcknowledged(receiver(), frame);
	}

	/** Hands the frame to {@code onFrameLost}; <b>ownership passes in</b>. */
	public void lose(QuicFrame frame) {
		manager().onFrameLost(receiver(), frame);
	}

	// ---------------------------------------------------------------- the wire, for the peer-visible half

	/**
	 * Puts a hand-built frame on the real wire from the side that has no stream layer, and pumps until
	 * both sides are quiet — so that a violation ends as a {@code CONNECTION_CLOSE} the sender can read
	 * back, which is what the spec's "Connection closed, {@code X}" column actually means.
	 * <p>
	 * <b>Takes ownership of {@code frame}.</b>
	 */
	public void sendOverTheWire(QuicFrame frame) throws QuicTransportException {
		sender().enqueueFrame(frame);
		sender().requestSend();
		wire.pump();
	}

	/** The RFC 9000 §20.1 code the receiver closed with, as the sender saw it, or {@code null}. */
	public @Nullable Long closeCodeSeenBySender() {
		QuicConnection.PeerClose peerClose = sender().peerClose();
		return peerClose == null ? null : peerClose.errorCode();
	}

	/**
	 * Asserts the receiver tore the connection down and told the sender exactly {@code expectedCode}.
	 */
	public void assertConnectionClosedWith(long expectedCode) {
		assertNotEquals("the receiver must not carry on as if nothing happened",
			QuicConnectionState.ESTABLISHED, receiver().state());
		Long code = closeCodeSeenBySender();
		assertNotNull("the sender must be told why the connection ended", code);
		assertEquals("RFC 9000 §20.1 error code on the wire, expected 0x" + Long.toHexString(expectedCode),
			(Long) expectedCode, code);
	}

	// ---------------------------------------------------------------- hand-built frames

	/** A payload whose every byte depends on its offset, so a mix-up cannot go unnoticed. */
	public static ByteBuf payload(long offset, int length) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (int i = 0; i < length; i++) {
			buf.put((byte) (offset + i));
		}
		return buf;
	}

	public static ByteBuf payload(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, bytes.length));
		buf.put(bytes);
		return buf;
	}

	/** A {@code STREAM} frame the <b>test</b> owns, as the connection owns the one it routes. */
	public static StreamFrame stream(long streamId, long offset, boolean fin, int length) {
		return new StreamFrame(streamId, offset, fin, payload(offset, length));
	}

	public static StreamFrame stream(long streamId, long offset, boolean fin, String text) {
		return new StreamFrame(streamId, offset, fin, payload(text));
	}

	@Override
	public void close() {
		// Order matters: closing the connections can enqueue a CONNECTION_CLOSE, and the wire drains it.
		wire.close();
		loop.close();
	}

	@Override
	public String toString() {
		return "StreamFrameInjector{" + receiver().state() + ", accepted=" + accepted.size() + '}';
	}
}
