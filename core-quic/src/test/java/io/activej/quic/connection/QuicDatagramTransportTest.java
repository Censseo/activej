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
import io.activej.common.MemSize;
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.codec.DatagramFrame;
import io.activej.quic.codec.PingFrame;
import io.activej.quic.codec.QuicFrame;
import io.activej.quic.connection.QuicDatagramException.Reason;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.tls.EncryptionLevel;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * T115 / T117 — RFC 9221 DATAGRAM frames at the transport: per-level legality, the inbound size bound in
 * both of its failure modes, and the bounded outbound queue.
 *
 * <h2>Terminology</h2>
 * A <b>DATAGRAM frame</b> (RFC 9221) travels inside a QUIC packet, which travels inside a <b>UDP
 * datagram</b>. {@link QuicConnection#datagramsSent()} counts UDP datagrams and
 * {@link QuicConnection#datagramFramesSent()} counts DATAGRAM frames; they are unrelated numbers.
 *
 * <h2>Why frames are injected with {@code enqueueFrame}</h2>
 * {@link QuicConnection#enqueueFrame} is the raw seam and applies no datagram policy, which is what lets
 * this class put an oversize or otherwise illegal DATAGRAM frame on the wire — something
 * {@link QuicConnection#sendDatagramFrame} refuses by design, and therefore something a conforming peer
 * built from this code could never stage.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221">RFC 9221 — An Unreliable Datagram Extension to QUIC</a>
 */
public final class QuicDatagramTransportTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private ManualEventloop loop;
	private QuicWirePair wire;

	/** What the server's handler was given, by frame class — never a payload byte (SI-6). */
	private final List<String> serverReceived = new ArrayList<>();
	/** Copies of the payloads the server was given, taken inside {@code onFrame} since the frame is borrowed. */
	private final List<byte[]> serverPayloads = new ArrayList<>();

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new QuicWirePair();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.close();
	}

	private final class ServerHandler implements QuicFrameHandler {
		@Override
		public void onFrame(QuicConnection connection, EncryptionLevel level, QuicFrame frame) {
			serverReceived.add(frame.getClass().getSimpleName());
			if (frame instanceof DatagramFrame datagram) {
				// Borrowed: copied out here or it is gone by the time this returns.
				serverPayloads.add(datagram.payload.getArray());
			}
		}
	}

	/** A handler that does nothing, for the side whose behaviour is not under test. */
	private static QuicFrameHandler idleHandler() {
		return (connection, level, frame) -> {};
	}

	private static QuicConnectionSettings settings(long maxDatagramFrameSize) {
		return QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(maxDatagramFrameSize))
			.build();
	}

	private static QuicConnectionSettings generousSettings() {
		return settings(QuicConnectionSettings.maxDatagramFrameSizeFor(
			QuicConnectionSettings.create().maxDatagramSize()));
	}

	/**
	 * Starts a pair whose client and server may advertise different limits, with a handler on both sides
	 * unless {@code serverHasHandler} says otherwise.
	 */
	private void handshake(QuicConnectionSettings clientSettings, QuicConnectionSettings serverSettings,
		boolean serverHasHandler
	) throws MalformedDataException {
		wire.withClientFrameHandler(idleHandler());
		if (serverHasHandler) wire.withServerFrameHandler(new ServerHandler());
		wire.startClient(clientSettings);
		wire.acceptServer(serverSettings);
		wire.pump();
		assertEquals(QuicConnectionState.ESTABLISHED, wire.client().state());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		serverReceived.clear();
		serverPayloads.clear();
	}

	private static ByteBuf payload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) (i * 31 + 5));
		}
		return buf;
	}

	private static byte[] payloadBytes(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = (byte) (i * 31 + 5);
		}
		return bytes;
	}

	/** Puts one hand-built DATAGRAM frame on the wire, bypassing every outbound policy. */
	private void clientInjects(int payloadLength) throws Exception {
		wire.client().enqueueFrame(new DatagramFrame(payload(payloadLength)));
		wire.client().requestSend();
		wire.pump();
	}

	// ---------------------------------------------------------------- T115a: per-level legality

	@Test
	public void aDatagramFrameIsLegalAtZeroRttAndOneRttOnly() {
		ByteBuf buf = payload(4);
		try {
			DatagramFrame frame = new DatagramFrame(buf);
			// RFC 9221 §5: DATAGRAM frames are permitted in 0-RTT and 1-RTT packets, and nowhere else.
			assertFalse(FrameTypeRules.isAllowed(frame, EncryptionLevel.INITIAL));
			assertFalse(FrameTypeRules.isAllowed(frame, EncryptionLevel.HANDSHAKE));
			assertTrue(FrameTypeRules.isAllowed(frame, EncryptionLevel.ZERO_RTT));
			assertTrue(FrameTypeRules.isAllowed(frame, EncryptionLevel.ONE_RTT));
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void aReceivedDatagramFrameAtAHandshakeLevelIsAProtocolViolation() {
		// Asserted against the rules rather than over the wire on purpose: after a handshake, an injected
		// Initial or Handshake packet is dropped as a discarded level long before these rules run, so a
		// wire-level version of this case would pass whatever the rules said.
		ByteBuf buf = payload(4);
		try {
			List<QuicFrame> frames = List.of(new DatagramFrame(buf));
			for (EncryptionLevel level : List.of(EncryptionLevel.INITIAL, EncryptionLevel.HANDSHAKE)) {
				QuicTransportException e = assertThrows(QuicTransportException.class,
					() -> FrameTypeRules.validateReceived(frames, level, true));
				assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, e.errorCode());
				assertNull("DATAGRAM spans two type codes, so no single code identifies it", e.frameType());
			}
			FrameTypeRules.validateReceived(frames, EncryptionLevel.ZERO_RTT, false);
			FrameTypeRules.validateReceived(frames, EncryptionLevel.ONE_RTT, true);
		} catch (QuicTransportException e) {
			throw new AssertionError("DATAGRAM must be permitted at 0-RTT and 1-RTT", e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void sendingADatagramFrameAtAHandshakeLevelIsRefusedAsOurOwnFault() {
		ByteBuf buf = payload(4);
		try {
			DatagramFrame frame = new DatagramFrame(buf);
			QuicTransportException e = assertThrows(QuicTransportException.class,
				() -> FrameTypeRules.validateForSending(frame, EncryptionLevel.HANDSHAKE));
			assertEquals(QuicTransportErrors.INTERNAL_ERROR, e.errorCode());
		} finally {
			buf.recycle();
		}
	}

	// ---------------------------------------------------------------- T115b: inbound enforcement

	@Test
	public void aDatagramFrameReceivedWithNothingAdvertisedIsAProtocolViolation() throws Exception {
		// FR-074a. The endpoint said "I do not support DATAGRAM" by omitting the parameter, so the frame is
		// the peer's violation rather than a size problem — and this must be decided before the size check,
		// or a limit of 0 would report the wrong code for every frame that arrived.
		handshake(generousSettings(), QuicConnectionSettings.create(), true);

		clientInjects(8);

		assertTrue(wire.server().state().isTerminating());
		assertNotNull(wire.client().peerClose());
		assertEquals(QuicTransportErrors.PROTOCOL_VIOLATION, wire.client().peerClose().errorCode());
		assertEquals(List.of(), serverReceived);
	}

	@Test
	public void aDatagramFrameLargerThanAdvertisedIsAFrameEncodingError() throws Exception {
		// FR-074b: the peer was told the bound and exceeded it, which is a malformed frame rather than a
		// misunderstanding about support.
		handshake(generousSettings(), settings(32), true);

		clientInjects(64);

		assertTrue(wire.server().state().isTerminating());
		assertEquals(QuicTransportErrors.FRAME_ENCODING_ERROR, wire.client().peerClose().errorCode());
		assertEquals(List.of(), serverReceived);
	}

	@Test
	public void aConformingDatagramFrameReachesTheFrameHandlerWithItsPayloadIntact() throws Exception {
		handshake(generousSettings(), generousSettings(), true);

		clientInjects(300);

		assertEquals(List.of("DatagramFrame"), serverReceived);
		assertArrayEquals(payloadBytes(300), serverPayloads.get(0));
		assertEquals(1, wire.server().datagramFramesReceived());
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@Test
	public void aZeroLengthDatagramFrameIsLegalAndDelivered() throws Exception {
		// RFC 9221 §4: the payload may be empty, and an empty H3 datagram is a real thing to send.
		handshake(generousSettings(), generousSettings(), true);

		wire.client().enqueueFrame(new DatagramFrame(ByteBufPool.allocate(0)));
		wire.client().requestSend();
		wire.pump();

		assertEquals(List.of("DatagramFrame"), serverReceived);
		assertEquals(0, serverPayloads.get(0).length);
	}

	@Test
	public void theInboundBoundIsMeasuredAgainstTheMinimalWireForm() throws Exception {
		// RFC 9221 §3 measures the parameter over the whole frame, and the 0x30 form has no Length field.
		// DatagramFrame does not record which form it arrived in and its encodedLength() reports the wider
		// 0x31 form, so measuring with that would close a connection over a frame a conforming peer was
		// entitled to send. 301 = 1 type byte + 300 payload bytes is exactly at the bound.
		handshake(generousSettings(), settings(301), true);

		clientInjects(300);

		assertEquals(List.of("DatagramFrame"), serverReceived);
		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
	}

	@Test
	public void aDatagramFrameWithNoHandlerIsDroppedAndCountedRatherThanClosing() throws Exception {
		// The same asymmetry FR-039 already documents for the credit frames: this endpoint advertised
		// support and then attached nothing to deliver to, which is its own configuration rather than the
		// peer's violation. Closing would blame the wrong party.
		handshake(generousSettings(), generousSettings(), false);

		clientInjects(16);

		assertEquals(QuicConnectionState.ESTABLISHED, wire.server().state());
		assertEquals(1, wire.server().datagramFramesDropped());
		assertEquals(0, wire.server().datagramFramesReceived());
		assertNull(wire.client().peerClose());
	}

	// ---------------------------------------------------------------- T117: the outbound queue

	@Test
	public void aQueuedDatagramFrameCrossesTheWireAndIsCounted() throws Exception {
		handshake(generousSettings(), generousSettings(), true);

		wire.client().sendDatagramFrame(payload(120));
		wire.client().requestSend();
		wire.pump();

		assertEquals(1, wire.client().datagramFramesSent());
		assertEquals(List.of("DatagramFrame"), serverReceived);
		assertArrayEquals(payloadBytes(120), serverPayloads.get(0));
	}

	@Test
	public void sendDatagramFrameRefusesWhenThePeerAdvertisedNothing() throws Exception {
		handshake(generousSettings(), QuicConnectionSettings.create(), true);

		QuicDatagramException e = assertThrows(QuicDatagramException.class,
			() -> wire.client().sendDatagramFrame(payload(16)));

		assertEquals(Reason.NOT_NEGOTIATED, e.reason());
		assertEquals(1, wire.client().datagramFramesRefused());
		// ByteBufRule proves the payload was recycled: ownership transferred at the call, refusal included.
	}

	@Test
	public void sendDatagramFrameRefusesAnOversizePayloadRatherThanTruncatingIt() throws Exception {
		// FR-075. Outbound is measured with encodedLength(), which is exact here because writeTo always
		// emits the 0x31 form — strict in what we send, lenient in what we accept.
		handshake(generousSettings(), settings(301), true);

		QuicDatagramException e = assertThrows(QuicDatagramException.class,
			() -> wire.client().sendDatagramFrame(payload(300)));

		assertEquals(Reason.OVERSIZE, e.reason());
		assertTrue("the message must name both numbers", e.getMessage().contains("301"));
		assertEquals(0, wire.client().datagramFramesSent());
	}

	@Test
	public void sendDatagramFrameRefusesAtTheOutboundBoundRatherThanQueueing() throws Exception {
		// FR-078: refused, never queued-and-waited and never evicting what is already queued — an
		// unreliable send that is refused at once is honest, while one that silently displaced another is
		// not.
		QuicConnectionSettings clientSettings = QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(QuicConnectionSettings.maxDatagramFrameSizeFor(
				QuicConnectionSettings.create().maxDatagramSize())))
			.withMaxOutboundDatagrams(2)
			.build();
		handshake(clientSettings, generousSettings(), true);

		wire.client().sendDatagramFrame(payload(16));
		wire.client().sendDatagramFrame(payload(16));
		QuicDatagramException e = assertThrows(QuicDatagramException.class,
			() -> wire.client().sendDatagramFrame(payload(16)));

		assertEquals(Reason.QUEUE_FULL, e.reason());
		assertEquals(1, wire.client().datagramFramesRefused());

		wire.client().requestSend();
		wire.pump();
		// The two already queued are unaffected: the refusal did not evict either of them.
		assertEquals(2, wire.client().datagramFramesSent());
		assertEquals(List.of("DatagramFrame", "DatagramFrame"), serverReceived);
	}

	@Test
	public void aDatagramFrameThatCannotBePlacedInTheNextPacketIsDroppedAndCounted() throws Exception {
		// FR-078's other half: one attempt at the packet under construction, then dropped. "The next
		// packet" rather than "some later flush" is what makes "never held indefinitely" unambiguous, and
		// it is why the queue is empty again afterwards.
		handshake(generousSettings(), generousSettings(), true);

		for (int i = 0; i < 3; i++) {
			wire.client().sendDatagramFrame(payload(800));
		}
		wire.client().requestSend();
		wire.pump();

		assertEquals(1, wire.client().datagramFramesSent());
		assertEquals(2, wire.client().datagramFramesDropped());
		assertEquals(List.of("DatagramFrame"), serverReceived);
	}

	@Test
	public void sendDatagramFrameRefusesOnAClosingConnection() throws Exception {
		handshake(generousSettings(), generousSettings(), true);
		wire.client().close();

		QuicDatagramException e = assertThrows(QuicDatagramException.class,
			() -> wire.client().sendDatagramFrame(payload(16)));

		assertEquals(Reason.CONNECTION_CLOSED, e.reason());
	}

	@Test
	public void closingRecyclesEveryQueuedOutboundDatagramFrame() throws Exception {
		handshake(generousSettings(), generousSettings(), true);

		wire.client().sendDatagramFrame(payload(64));
		wire.client().sendDatagramFrame(payload(64));
		wire.client().closeNow();

		// ByteBufRule is the assertion: the contract is that every queued payload, inbound and outbound, is
		// recycled exactly once when the connection ends.
		assertEquals(0, wire.client().datagramFramesSent());
	}

	@Test
	public void aQueuedDatagramFrameCountsAsPendingWork() throws Exception {
		// Without this a flush carrying only DATAGRAM frames would report "nothing pending" and the
		// datagrams would sit until some other frame happened to schedule a packet.
		handshake(generousSettings(), generousSettings(), true);
		wire.client().enqueueFrame(PingFrame.INSTANCE);
		wire.client().requestSend();
		wire.pump();
		serverReceived.clear();

		wire.client().sendDatagramFrame(payload(32));
		wire.client().requestSend();
		wire.pump();

		assertEquals(List.of("DatagramFrame"), serverReceived);
	}
}
