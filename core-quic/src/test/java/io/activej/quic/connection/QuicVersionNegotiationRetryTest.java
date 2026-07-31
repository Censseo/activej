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
import io.activej.promise.Promise;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.codec.RetryPacket;
import io.activej.quic.codec.VersionNegotiationPacket;
import io.activej.quic.connection.testutil.ManualEventloop;
import io.activej.quic.connection.testutil.QuicWirePair;
import io.activej.quic.crypto.RetryIntegrityTag;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T069 — US6 scenarios 1–4. Both packet types are <b>unauthenticated</b> (a Retry only by a tag keyed on
 * the original connection ID), so every test here is as much about what is <i>refused</i> as about what
 * works: an off-path observer that could make either one take effect would have a connection-killing or
 * downgrade primitive.
 */
public final class QuicVersionNegotiationRetryTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	/** A version from the RFC 9000 §15 reserved "force negotiation" pattern — never a real one. */
	private static final long UNSUPPORTED_VERSION = 0x1a2a3a4aL;

	private ManualEventloop loop;
	private QuicWirePair wire;

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

	private Promise<QuicConnection> startClient() {
		return wire.startClient(QuicConnectionSettings.create());
	}

	/** The client's first Initial, consumed so a synthesised answer can be delivered in its place. */
	private QuicConnection clientAfterInitial() {
		startClient();
		ByteBuf initial = wire.clientWire().poll();
		assertNotNull("the client sent no Initial", initial);
		initial.recycle();
		return wire.client();
	}

	private static ByteBuf datagramOf(io.activej.quic.codec.QuicPacket packet) {
		ByteBuf out = ByteBufPool.allocate(QuicPackets.encodedLength(packet));
		QuicPackets.write(out, packet);
		return out;
	}

	// ---------------------------------------------------------------- scenario 1: no shared version

	@Test
	public void scenario1_aVersionNegotiationListingNoSupportedVersionFailsWithTheOfferedVersions() {
		QuicConnection client = clientAfterInitial();
		Promise<QuicConnection> establishing = client.whenEstablished();

		client.onDatagram(datagramOf(VersionNegotiationPacket.of(
			client.localConnectionId(), client.originalDestinationConnectionId(),
			new int[]{(int) UNSUPPORTED_VERSION, 0x51303530})));

		assertTrue("the client is still waiting: " + establishing, establishing.isException());
		QuicTransportException e = (QuicTransportException) establishing.getException();
		assertEquals(QuicTransportErrors.VERSION_NEGOTIATION_ERROR, e.errorCode());
		// The offered versions belong in the message: without them the caller cannot tell an obsolete
		// server from an unreachable one.
		assertTrue("the offered versions are not reported: " + e.getMessage(),
			e.getMessage().contains("1a2a3a4a"));
		assertEquals(QuicConnectionState.CLOSED, client.state());
	}

	@Test
	public void aVersionNegotiationIsAnsweredWithSilenceOnTheWire() {
		QuicConnection client = clientAfterInitial();
		int sentBefore = wire.clientWire().datagramsAccepted();

		client.onDatagram(datagramOf(VersionNegotiationPacket.of(
			client.localConnectionId(), client.originalDestinationConnectionId(),
			new int[]{(int) UNSUPPORTED_VERSION})));

		// There are no keys the server has said it can read, so a CONNECTION_CLOSE would be noise at best.
		assertEquals(sentBefore, wire.clientWire().datagramsAccepted());
	}

	// ---------------------------------------------------------------- scenario 2: our own version listed

	@Test
	public void scenario2_aVersionNegotiationListingOurOwnVersionIsDiscardedAsInvalid() {
		QuicConnection client = clientAfterInitial();
		Promise<QuicConnection> establishing = client.whenEstablished();
		long droppedBefore = client.packetsDropped();

		client.onDatagram(datagramOf(VersionNegotiationPacket.of(
			client.localConnectionId(), client.originalDestinationConnectionId(),
			new int[]{(int) QuicPackets.SUPPORTED_VERSION, (int) UNSUPPORTED_VERSION})));

		// RFC 9000 §6.2: a real server would never send this, so its presence proves the packet is forged
		// or corrupt. Acting on it would be a downgrade any on-path observer could mount.
		assertFalse("the client acted on an invalid Version Negotiation packet", establishing.isComplete());
		assertTrue(client.packetsDropped() > droppedBefore);
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());
	}

	@Test
	public void aVersionNegotiationArrivingAfterTheHandshakeKeysAreInstalledIsIgnored() throws Exception {
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection client = wire.client();
		long droppedBefore = client.packetsDropped();

		client.onDatagram(datagramOf(VersionNegotiationPacket.of(
			client.localConnectionId(), client.originalDestinationConnectionId(),
			new int[]{(int) UNSUPPORTED_VERSION})));

		// An established connection cannot be renegotiated, so a late Version Negotiation packet is only
		// ever an attempt to tear one down.
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
		assertTrue(client.packetsDropped() > droppedBefore);
	}

	// ---------------------------------------------------------------- scenario 3: a valid Retry

	/** A Retry from {@code serverScid}, with a correctly computed integrity tag over the original DCID. */
	private static ByteBuf validRetry(QuicConnection client, QuicConnectionId serverScid, byte[] token) {
		RetryPacket untagged = new RetryPacket(QuicPackets.SUPPORTED_VERSION,
			client.localConnectionId(), serverScid, token, new byte[RetryPacket.INTEGRITY_TAG_LENGTH]);
		// The tag covers the whole packet up to the tag itself, so it is computed over the encoding with a
		// placeholder tag and then substituted in.
		ByteBuf encoded = datagramOf(untagged);
		int headerLength = encoded.readRemaining() - RetryPacket.INTEGRITY_TAG_LENGTH;
		byte[] headerAndToken = new byte[headerLength];
		System.arraycopy(encoded.array(), encoded.head(), headerAndToken, 0, headerLength);
		encoded.recycle();

		byte[] tag = RetryIntegrityTag.compute(client.originalDestinationConnectionId(), headerAndToken);
		return datagramOf(new RetryPacket(QuicPackets.SUPPORTED_VERSION,
			client.localConnectionId(), serverScid, token, tag));
	}

	@Test
	public void scenario3_aValidRetryRestartsTheHandshakeWithTheToken() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId serverScid = QuicConnectionId.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
		byte[] token = {'t', 'o', 'k', 'e', 'n'};
		int sentBefore = wire.clientWire().datagramsAccepted();

		client.onDatagram(validRetry(client, serverScid, token));

		// RFC 9000 §17.2.5.3: the client now addresses the server by the ID the Retry chose, and re-sends
		// its ClientHello — the *same* one, since a different ClientHello would change the transcript hash
		// the whole handshake is built on.
		assertEquals(serverScid, client.peerConnectionId());
		assertEquals("the client did not re-send its Initial", sentBefore + 1,
			wire.clientWire().datagramsAccepted());
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());

		ByteBuf resent = wire.clientWire().poll();
		assertNotNull(resent);
		try {
			// RFC 9000 §14.1 still applies to the re-sent Initial, token and all.
			assertTrue("the re-sent Initial datagram was only " + resent.readRemaining() + " bytes",
				resent.readRemaining() >= PacketAssembler.MIN_INITIAL_DATAGRAM_SIZE);
		} finally {
			resent.recycle();
		}
	}

	@Test
	public void scenario3_theOriginalDestinationConnectionIdIsRetainedForValidation() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId original = client.originalDestinationConnectionId();
		QuicConnectionId serverScid = QuicConnectionId.of(new byte[]{9, 9, 9, 9, 9, 9, 9, 9});

		client.onDatagram(validRetry(client, serverScid, new byte[]{1}));

		// RFC 9000 §7.3: the server must still echo the *original* ID as
		// original_destination_connection_id, so losing it here would make every post-Retry handshake fail
		// transport-parameter validation.
		assertEquals(original, client.originalDestinationConnectionId());
		assertNotEquals(original, client.peerConnectionId());
	}

	// ---------------------------------------------------------------- scenario 4: a Retry to refuse

	@Test
	public void scenario4_aRetryWithABadIntegrityTagIsDiscardedSilently() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId serverScid = QuicConnectionId.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
		QuicConnectionId before = client.peerConnectionId();
		long droppedBefore = client.packetsDropped();

		byte[] forgedTag = new byte[RetryPacket.INTEGRITY_TAG_LENGTH];
		client.onDatagram(datagramOf(new RetryPacket(QuicPackets.SUPPORTED_VERSION,
			client.localConnectionId(), serverScid, new byte[]{7}, forgedTag)));

		// The tag is keyed on the original destination connection ID, which only something on the path to
		// the real server could know. RFC 9000 §17.2.5.2 says discard, not close: closing would hand any
		// observer a way to kill connections.
		assertEquals(before, client.peerConnectionId());
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());
		assertTrue(client.packetsDropped() > droppedBefore);
	}

	@Test
	public void scenario4_aSecondRetryIsDiscarded() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId first = QuicConnectionId.of(new byte[]{1, 1, 1, 1, 1, 1, 1, 1});
		client.onDatagram(validRetry(client, first, new byte[]{1}));
		assertEquals(first, client.peerConnectionId());
		ByteBuf resent = wire.clientWire().poll();
		if (resent != null) resent.recycle();

		QuicConnectionId second = QuicConnectionId.of(new byte[]{2, 2, 2, 2, 2, 2, 2, 2});
		// The tag would not verify anyway now that the keys moved on, but the single-Retry rule is checked
		// first and independently: RFC 9000 §17.2.5.2 allows exactly one, and a loop of Retries would be a
		// handshake that never completes.
		client.onDatagram(validRetry(client, second, new byte[]{2}));

		assertEquals(first, client.peerConnectionId());
	}

	@Test
	public void scenario4_aRetryWhoseTokenLeavesNoRoomForAnInitialIsDiscarded() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId serverScid = QuicConnectionId.of(new byte[]{5, 5, 5, 5, 5, 5, 5, 5});
		QuicConnectionId before = client.peerConnectionId();
		long droppedBefore = client.packetsDropped();
		int sentBefore = wire.clientWire().datagramsAccepted();

		// RFC 9000 §17.2.5 bounds neither the token nor the Retry packet. A token that fills the datagram
		// leaves a non-positive payload allowance in every subsequent Initial, so the client would build
		// no packet at all and stall silently until its handshake deadline. The tag verifies — this is a
		// server, or anything on the path, that is merely being unreasonable rather than forging.
		byte[] hugeToken = new byte[QuicConnectionSettings.create().maxDatagramSize()];
		client.onDatagram(validRetry(client, serverScid, hugeToken));

		assertEquals(before, client.peerConnectionId());
		assertEquals(QuicConnectionState.HANDSHAKING, client.state());
		assertTrue(client.packetsDropped() > droppedBefore);
		assertEquals("a discarded Retry must not provoke a re-send", sentBefore,
			wire.clientWire().datagramsAccepted());
	}

	@Test
	public void aRetryWithATokenThatStillLeavesWorkingRoomIsAccepted() {
		QuicConnection client = clientAfterInitial();
		QuicConnectionId serverScid = QuicConnectionId.of(new byte[]{6, 6, 6, 6, 6, 6, 6, 6});

		// The other side of the bound: real servers issue tokens of a few dozen to a couple of hundred
		// bytes, and none of those may be refused.
		client.onDatagram(validRetry(client, serverScid, new byte[128]));

		assertEquals(serverScid, client.peerConnectionId());
		ByteBuf resent = wire.clientWire().poll();
		assertNotNull("the client did not re-send its Initial", resent);
		resent.recycle();
	}

	@Test
	public void aRetryArrivingAfterAServerInitialIsDiscarded() throws Exception {
		// A Retry may only precede the server's first Initial (RFC 9000 §17.2.5.2). By the time a real
		// server packet has been processed, a Retry can only be an attempt to reset a live handshake.
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection client = wire.client();
		QuicConnectionId before = client.peerConnectionId();

		client.onDatagram(validRetry(client, QuicConnectionId.of(new byte[]{3, 3, 3, 3, 3, 3, 3, 3}),
			new byte[]{3}));

		assertEquals(before, client.peerConnectionId());
		assertEquals(QuicConnectionState.ESTABLISHED, client.state());
	}

	@Test
	public void aServerNeverProcessesARetry() throws Exception {
		// FR-030: this implementation never issues one, and a server receiving one is being attacked or
		// misconfigured — either way there is nothing to do with it.
		wire.handshake(QuicConnectionSettings.create());
		QuicConnection server = wire.server();
		QuicConnectionId before = server.peerConnectionId();
		long droppedBefore = server.packetsDropped();

		server.onDatagram(datagramOf(new RetryPacket(QuicPackets.SUPPORTED_VERSION,
			server.localConnectionId(), QuicConnectionId.of(new byte[]{4, 4, 4, 4, 4, 4, 4, 4}),
			new byte[]{4}, new byte[RetryPacket.INTEGRITY_TAG_LENGTH])));

		assertEquals(before, server.peerConnectionId());
		assertTrue(server.packetsDropped() > droppedBefore);
	}
}
