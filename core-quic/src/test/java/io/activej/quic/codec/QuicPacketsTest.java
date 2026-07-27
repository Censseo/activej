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

package io.activej.quic.codec;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Round-trip identity and error paths for every packet type in data-model.md
 * (spec US2 acceptance scenarios).
 */
public class QuicPacketsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Random random = new Random(4004);
	private final SecureRandom secureRandom = new SecureRandom();

	@Test
	public void roundTripsInitialPacket() throws Exception {
		for (int i = 0; i < 5; i++) {
			QuicConnectionId dcid = QuicConnectionId.random(1 + random.nextInt(20), secureRandom);
			QuicConnectionId scid = QuicConnectionId.random(random.nextInt(21), secureRandom);
			byte[] token = randomBytes(random.nextInt(10));
			ByteBuf originalPayload = randomPayload(20);
			byte[] expectedPayload = snapshot(originalPayload);
			long pn = randomVarInt();
			int pnLength = PacketNumbers.encodeLength(pn, pn - 1);

			InitialPacket packet = new InitialPacket(
				QuicPackets.SUPPORTED_VERSION, dcid, scid, token, pn, pnLength, originalPayload);

			ByteBuf encoded = encode(packet);
			QuicPacket decoded = QuicPackets.parse(encoded, dcid.length());
			assertFalse(encoded.canRead());
			assertTrue(decoded instanceof InitialPacket);
			InitialPacket decodedInitial = (InitialPacket) decoded;

			assertEquals(QuicPackets.SUPPORTED_VERSION, decodedInitial.version);
			assertEquals(dcid, decodedInitial.destinationConnectionId);
			assertEquals(scid, decodedInitial.sourceConnectionId);
			assertArrayEquals(token, decodedInitial.token());
			assertEquals(pnLength, decodedInitial.packetNumberLength);
			assertEquals(pn, PacketNumbers.reconstruct(decodedInitial.packetNumber, pnLength, pn - 1));
			assertArrayEquals(expectedPayload, snapshot(decodedInitial.payload));

			originalPayload.recycle();
			decodedInitial.recycle();
			encoded.recycle();
		}
	}

	@Test
	public void roundTripsHandshakePacket() throws Exception {
		roundTripsSimpleLongHeader((dcid, scid, pn, pnLength, payload) ->
			new HandshakePacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, pn, pnLength, payload));
	}

	@Test
	public void roundTripsZeroRttPacket() throws Exception {
		roundTripsSimpleLongHeader((dcid, scid, pn, pnLength, payload) ->
			new ZeroRttPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, pn, pnLength, payload));
	}

	private interface SimpleLongHeaderFactory {
		QuicPacket create(QuicConnectionId dcid, QuicConnectionId scid, long pn, int pnLength, ByteBuf payload);
	}

	private void roundTripsSimpleLongHeader(SimpleLongHeaderFactory factory) throws Exception {
		for (int pnLength = 1; pnLength <= 4; pnLength++) {
			QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
			QuicConnectionId scid = QuicConnectionId.random(4, secureRandom);
			ByteBuf originalPayload = randomPayload(15);
			byte[] expectedPayload = snapshot(originalPayload);
			long pn = (1L << ((pnLength - 1) * 8)); // fits exactly in pnLength bytes, no reconstruction ambiguity

			QuicPacket packet = factory.create(dcid, scid, pn, pnLength, originalPayload);
			ByteBuf encoded = encode(packet);
			QuicPacket decoded = QuicPackets.parse(encoded, dcid.length());
			assertFalse(encoded.canRead());
			assertEquals(packet, decoded);

			ByteBuf decodedPayload = decoded instanceof HandshakePacket h ? h.payload : ((ZeroRttPacket) decoded).payload;
			assertArrayEquals(expectedPayload, snapshot(decodedPayload));

			originalPayload.recycle();
			((io.activej.common.recycle.Recyclable) decoded).recycle();
			encoded.recycle();
		}
	}

	@Test
	public void roundTripsShortHeaderPacketWith1To4BytePacketNumbers() throws Exception {
		for (int pnLength = 1; pnLength <= 4; pnLength++) {
			QuicConnectionId dcid = QuicConnectionId.random(6, secureRandom);
			ByteBuf originalPayload = randomPayload(12);
			byte[] expectedPayload = snapshot(originalPayload);
			long pn = (1L << ((pnLength - 1) * 8)) + 1;

			ShortHeaderPacket packet = new ShortHeaderPacket(dcid, pnLength % 2 == 0, pnLength % 3 == 0, pn, pnLength, originalPayload);
			ByteBuf encoded = encode(packet);
			QuicPacket decoded = QuicPackets.parse(encoded, dcid.length());
			assertFalse(encoded.canRead());
			assertEquals(packet, decoded);
			assertArrayEquals(expectedPayload, snapshot(((ShortHeaderPacket) decoded).payload));

			originalPayload.recycle();
			((ShortHeaderPacket) decoded).recycle();
			encoded.recycle();
		}
	}

	@Test
	public void retryPacketParseExposes16ByteIntegrityTag() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		byte[] token = randomBytes(24);
		byte[] tag = randomBytes(16);

		RetryPacket packet = new RetryPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, token, tag);
		ByteBuf encoded = encode(packet);
		QuicPacket decoded = QuicPackets.parse(encoded, dcid.length());
		assertFalse(encoded.canRead());
		assertTrue(decoded instanceof RetryPacket);
		RetryPacket decodedRetry = (RetryPacket) decoded;
		assertEquals(16, decodedRetry.retryIntegrityTag().length);
		assertArrayEquals(tag, decodedRetry.retryIntegrityTag());
		assertArrayEquals(token, decodedRetry.retryToken());
		assertEquals(packet, decoded);

		encoded.recycle();
	}

	@Test
	public void retryPacketMissingIntegrityTagThrowsTruncatedDataException() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		// Empty token, so the envelope (first byte + version + DCID + SCID) is followed only by
		// the tag: truncating there leaves fewer than INTEGRITY_TAG_LENGTH bytes, unambiguously.
		RetryPacket packet = new RetryPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, new byte[0], randomBytes(16));

		ByteBuf full = encode(packet);
		int envelopeLength = 1 + 4 + 1 + dcid.length() + 1 + scid.length();
		int shortTagLength = 5; // well under RetryPacket.INTEGRITY_TAG_LENGTH (16)
		ByteBuf truncated = ByteBufPool.allocate(envelopeLength + shortTagLength);
		truncated.put(full.array(), full.head(), envelopeLength + shortTagLength);
		full.recycle();

		try {
			QuicPackets.parse(truncated, dcid.length());
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} finally {
			truncated.recycle();
		}
	}

	@Test
	public void versionNegotiationPacketConstructAndParse() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		int[] versions = {0x00000001, 0xff00001d};

		VersionNegotiationPacket packet = VersionNegotiationPacket.of(dcid, scid, versions);
		assertTrue(packet.isVersionNegotiation());
		ByteBuf encoded = encode(packet);
		QuicPacket decoded = QuicPackets.parse(encoded, dcid.length());
		assertFalse(encoded.canRead());
		assertEquals(packet, decoded);
		assertArrayEquals(versions, ((VersionNegotiationPacket) decoded).supportedVersions());

		encoded.recycle();
	}

	@Test
	public void unknownLongHeaderVersionClassifiesAsVersionNegotiationWithoutV1Parse() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(4, secureRandom);
		// A long header with a version this codec does not implement, and type-specific bits
		// that would be nonsense for a v1 Initial packet (proving no v1 parse is attempted).
		ByteBuf buf = ByteBufPool.allocate(64);
		buf.writeByte((byte) 0xFF);
		buf.writeInt(0x000000AA); // unrecognized version, non-zero
		buf.writeByte((byte) dcid.length());
		buf.put(dcid.bytes());
		buf.writeByte((byte) scid.length());
		buf.put(scid.bytes());
		buf.put(new byte[] {1, 2, 3, 4, 5}); // arbitrary trailing bytes, never interpreted

		QuicPacket decoded = QuicPackets.parse(buf, dcid.length());
		assertFalse(buf.canRead());
		assertTrue(decoded instanceof VersionNegotiationPacket);
		VersionNegotiationPacket vn = (VersionNegotiationPacket) decoded;
		assertFalse(vn.isVersionNegotiation());
		assertEquals(0x000000AAL, vn.version);
		assertEquals(dcid, vn.destinationConnectionId);
		assertEquals(scid, vn.sourceConnectionId);
		assertEquals(0, vn.supportedVersions().length);

		buf.recycle();
	}

	@Test
	public void coalescedDatagramSplitsOnLengthFields() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		ByteBuf initialPayload = randomPayload(10);
		ByteBuf handshakePayload = randomPayload(10);
		InitialPacket initial = new InitialPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, new byte[0], 1, 1, initialPayload);
		HandshakePacket handshake = new HandshakePacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, 1, 1, handshakePayload);

		ByteBuf datagram = ByteBufPool.allocate(initial.encodedLength() + handshake.encodedLength());
		QuicPackets.write(datagram, initial);
		QuicPackets.write(datagram, handshake);

		List<QuicPacket> packets = QuicPackets.parseCoalesced(datagram, dcid.length());
		assertFalse(datagram.canRead());
		assertEquals(2, packets.size());
		assertTrue(packets.get(0) instanceof InitialPacket);
		assertTrue(packets.get(1) instanceof HandshakePacket);

		initialPayload.recycle();
		handshakePayload.recycle();
		((InitialPacket) packets.get(0)).recycle();
		((HandshakePacket) packets.get(1)).recycle();
		datagram.recycle();
	}

	@Test
	public void coalescedDatagramWithATruncatedSecondPacketThrowsTruncatedDataExceptionAndRecyclesTheFirst() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		ByteBuf payload = randomPayload(5);
		InitialPacket initial = new InitialPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, new byte[0], 1, 1, payload);

		ByteBuf datagram = ByteBufPool.allocate(initial.encodedLength() + 1);
		QuicPackets.write(datagram, initial);
		// A second packet that announces a long header but is cut off after the first byte —
		// genuinely truncated, not just malformed (spec.md edge case: "a coalesced sequence of
		// packets where a later packet is truncated").
		datagram.writeByte((byte) 0xC0);

		try {
			QuicPackets.parseCoalesced(datagram, dcid.length());
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected — parseCoalesced recycles the first (successfully parsed) packet's
			// owned payload slice before rethrowing, so no leak from the partial result.
		} finally {
			payload.recycle();
			datagram.recycle();
		}
	}

	@Test
	public void coalescedDatagramTrailingGarbageThrowsMalformedDataException() throws Exception {
		QuicConnectionId dcid = QuicConnectionId.random(8, secureRandom);
		QuicConnectionId scid = QuicConnectionId.random(8, secureRandom);
		ByteBuf payload = randomPayload(5);
		InitialPacket initial = new InitialPacket(QuicPackets.SUPPORTED_VERSION, dcid, scid, new byte[0], 1, 1, payload);

		ByteBuf datagram = ByteBufPool.allocate(initial.encodedLength() + 8);
		QuicPackets.write(datagram, initial);
		// Trailing garbage: a long-header first byte + version, but a connection ID length
		// declared far larger than 20 (invalid) and no v1 parse is even attempted before that.
		datagram.writeByte((byte) 0xC0);
		datagram.writeInt(1);
		datagram.writeByte((byte) 200);

		try {
			QuicPackets.parseCoalesced(datagram, dcid.length());
			fail("expected MalformedDataException");
		} catch (MalformedDataException expected) {
			// expected (TruncatedDataException is a subtype and would also satisfy this)
		} finally {
			payload.recycle();
			datagram.recycle();
		}
	}

	@Test
	public void connectionIdLengthOver20InLongHeaderThrowsMalformedDataException() {
		ByteBuf buf = ByteBufPool.allocate(16);
		buf.writeByte((byte) 0xC3); // long header, fixed bit set, arbitrary type bits
		buf.writeInt((int) QuicPackets.SUPPORTED_VERSION);
		buf.writeByte((byte) 21); // invalid: DCID length > 20
		try {
			QuicPackets.parse(buf, 8);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void inputDatagramIsUsableAfterEveryErrorPath() {
		// Truncated: claims long header but has nothing after the first byte.
		ByteBuf buf = ByteBufPool.allocate(4);
		buf.writeByte((byte) 0xC0);
		try {
			QuicPackets.parse(buf, 8);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (MalformedDataException e) {
			fail("unexpected MalformedDataException: " + e);
		} finally {
			buf.recycle();
		}
	}

	// ---- helpers ----

	private static ByteBuf encode(QuicPacket packet) {
		ByteBuf buf = ByteBufPool.allocate(packet.encodedLength());
		QuicPackets.write(buf, packet);
		assertEquals(packet.encodedLength(), buf.readRemaining());
		return buf;
	}

	private static byte[] snapshot(ByteBuf buf) {
		return Arrays.copyOfRange(buf.array(), buf.head(), buf.tail());
	}

	private ByteBuf randomPayload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		buf.put(bytes);
		return buf;
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}

	private long randomVarInt() {
		return 2 + ((random.nextLong() & Long.MAX_VALUE) % 1000);
	}
}
