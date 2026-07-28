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

package io.activej.quic.tls;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.tls.CertificateMessage.CertificateEntry;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * RFC 8448 §3 byte-exact parse/re-serialize (SC-001), randomized round-trip identity for every
 * message subtype in data-model.md (RFC 8446 §4), and the adversarial framing paths of FR-017:
 * declared length vs remaining bytes, the {@code maxHandshakeMessageSize} bound and truncated
 * fixed fields.
 */
public class TlsMessagesTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Random random = new Random(8446);

	// ---- (a) RFC 8448 §3 published messages parse to published values, re-serialize byte-exact ----

	@Test
	public void parsesRfc8448ClientHello() throws Exception {
		ClientHelloMessage message = (ClientHelloMessage) parse(Rfc8448.CLIENT_HELLO);
		assertEquals(ClientHelloMessage.TYPE, message.type());
		assertEquals(0x0303, message.legacyVersion);
		assertArrayEquals(Rfc8448.CLIENT_RANDOM, message.random());
		assertEquals(0, message.legacySessionId.length);
		assertArrayEquals(new int[] {0x1301, 0x1303, 0x1302}, message.cipherSuites());
		assertArrayEquals(new int[] {0}, message.compressionMethods());

		ServerNameExt serverName = findExtension(message.extensions, ServerNameExt.class);
		assertEquals("server", serverName.hostName);
		SupportedVersionsExt versions = findExtension(message.extensions, SupportedVersionsExt.class);
		assertFalse(versions.selectedForm);
		assertArrayEquals(new int[] {0x0304}, versions.versions);
		KeyShareExt keyShare = findExtension(message.extensions, KeyShareExt.class);
		assertNotNull(keyShare.clientShares);
		assertEquals(1, keyShare.clientShares.size());
		assertEquals(NamedGroup.X25519, keyShare.clientShares.get(0).namedGroup());
		assertArrayEquals(Rfc8448.CLIENT_EPHEMERAL_PUBLIC, keyShare.clientShares.get(0).keyExchange());

		assertReserializesTo(Rfc8448.CLIENT_HELLO, message);
	}

	@Test
	public void parsesRfc8448ServerHello() throws Exception {
		ServerHelloMessage message = (ServerHelloMessage) parse(Rfc8448.SERVER_HELLO);
		assertEquals(ServerHelloMessage.TYPE, message.type());
		assertEquals(0x0303, message.legacyVersion);
		assertArrayEquals(Rfc8448.SERVER_RANDOM, message.random());
		assertEquals(0, message.sessionIdEcho.length);
		assertEquals(0x1301, message.cipherSuite);
		assertEquals(TlsCipherSuite.TLS_AES_128_GCM_SHA256, message.knownCipherSuite());
		assertEquals(0, message.compressionMethod);
		assertFalse(message.isHelloRetryRequest());

		KeyShareExt keyShare = findExtension(message.extensions, KeyShareExt.class);
		assertNotNull(keyShare.selectedShare);
		assertEquals(NamedGroup.X25519, keyShare.selectedShare.namedGroup());
		assertArrayEquals(Rfc8448.SERVER_EPHEMERAL_PUBLIC, keyShare.selectedShare.keyExchange());
		SupportedVersionsExt versions = findExtension(message.extensions, SupportedVersionsExt.class);
		assertTrue(versions.selectedForm);
		assertArrayEquals(new int[] {0x0304}, versions.versions);

		assertReserializesTo(Rfc8448.SERVER_HELLO, message);
	}

	@Test
	public void parsesRfc8448EncryptedExtensions() throws Exception {
		EncryptedExtensionsMessage message = (EncryptedExtensionsMessage) parse(Rfc8448.ENCRYPTED_EXTENSIONS);
		assertEquals(EncryptedExtensionsMessage.TYPE, message.type());
		// supported_groups + an unknown record_size_limit + the RFC 6066 empty server_name ack
		assertEquals(3, message.extensions.size());
		assertNotNull(findExtension(message.extensions, SupportedGroupsExt.class));
		assertNotNull(findExtension(message.extensions, ServerNameExt.class));

		assertReserializesTo(Rfc8448.ENCRYPTED_EXTENSIONS, message);
	}

	@Test
	public void parsesRfc8448Certificate() throws Exception {
		CertificateMessage message = (CertificateMessage) parse(Rfc8448.CERTIFICATE);
		assertEquals(CertificateMessage.TYPE, message.type());
		assertEquals(0, message.certificateRequestContext.length);
		assertEquals(1, message.entries.size());
		assertEquals(432, message.entries.get(0).certificateBytes.length); // 0x01b0 per the trace
		assertTrue(message.entries.get(0).extensions.isEmpty());

		assertReserializesTo(Rfc8448.CERTIFICATE, message);
	}

	@Test
	public void parsesRfc8448CertificateVerify() throws Exception {
		CertificateVerifyMessage message = (CertificateVerifyMessage) parse(Rfc8448.CERTIFICATE_VERIFY);
		assertEquals(CertificateVerifyMessage.TYPE, message.type());
		assertEquals(SignatureScheme.RSA_PSS_RSAE_SHA256.code(), message.signatureScheme);
		assertEquals(SignatureScheme.RSA_PSS_RSAE_SHA256, message.knownScheme());
		assertEquals(128, message.signature.length);

		assertReserializesTo(Rfc8448.CERTIFICATE_VERIFY, message);
	}

	@Test
	public void parsesRfc8448FinishedMessages() throws Exception {
		FinishedMessage serverFinished = (FinishedMessage) parse(Rfc8448.SERVER_FINISHED);
		assertEquals(FinishedMessage.TYPE, serverFinished.type());
		assertArrayEquals(Rfc8448.SERVER_VERIFY_DATA, serverFinished.verifyData());
		assertReserializesTo(Rfc8448.SERVER_FINISHED, serverFinished);

		FinishedMessage clientFinished = (FinishedMessage) parse(Rfc8448.CLIENT_FINISHED);
		assertArrayEquals(Rfc8448.CLIENT_VERIFY_DATA, clientFinished.verifyData());
		assertReserializesTo(Rfc8448.CLIENT_FINISHED, clientFinished);
	}

	@Test
	public void parsesRfc8448NewSessionTicketStructureOnly() throws Exception {
		// FR-015: structure parsed, ticket discarded — no resumption state is created anywhere.
		NewSessionTicketMessage message = (NewSessionTicketMessage) parse(Rfc8448.NEW_SESSION_TICKET);
		assertEquals(NewSessionTicketMessage.TYPE, message.type());
		assertEquals(30, message.ticketLifetime);
		assertEquals(0xfad6aac5L, message.ticketAgeAdd);
		assertEquals(2, message.ticketNonce.length);
		assertEquals(178, message.ticket.length); // 0x00b2 per the trace
		assertEquals(1, message.extensions.size());
		assertTrue(message.extensions.get(0) instanceof UnknownExtension);
		assertEquals(0x002a, message.extensions.get(0).type()); // early_data: parsed, never used

		assertReserializesTo(Rfc8448.NEW_SESSION_TICKET, message);
	}

	// ---- (b) round-trip identity over randomized field values ----

	@Test
	public void roundTripsClientHello() throws Exception {
		byte[] p256Key = randomBytes(65);
		p256Key[0] = 0x04;
		ClientHelloMessage message = new ClientHelloMessage(0x0303,
			randomBytes(32),
			randomBytes(random.nextInt(33)),
			new int[] {0x1301, 0x1302, 0x1303, 0x0a0a /* GREASE */},
			new int[] {0},
			List.of(
				SupportedVersionsExt.ofClientVersions(0x0304),
				new SupportedGroupsExt(NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()),
				KeyShareExt.ofClientShares(List.of(
					new KeyShareEntry(NamedGroup.X25519.code(), randomBytes(32)),
					new KeyShareEntry(NamedGroup.SECP256R1.code(), p256Key))),
				new SignatureAlgorithmsExt(SignatureScheme.ECDSA_SECP256R1_SHA256.code(), SignatureScheme.ED25519.code()),
				new AlpnExt(List.of("h3")),
				new ServerNameExt("example.test"),
				new PskKeyExchangeModesExt(PskKeyExchangeModesExt.PSK_DHE_KE),
				new UnknownExtension(0x001c, randomBytes(7))));
		assertRoundTrip(message);
	}

	@Test
	public void roundTripsServerHello() throws Exception {
		ServerHelloMessage message = new ServerHelloMessage(0x0303,
			randomBytes(32),
			randomBytes(random.nextInt(33)),
			0x1302,
			0,
			List.of(
				KeyShareExt.ofSelectedShare(new KeyShareEntry(NamedGroup.X25519.code(), randomBytes(32))),
				SupportedVersionsExt.ofSelectedVersion(0x0304)));
		assertRoundTrip(message);
	}

	@Test
	public void roundTripsEncryptedExtensions() throws Exception {
		assertRoundTrip(new EncryptedExtensionsMessage(List.of(
			new SupportedGroupsExt(NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()),
			new AlpnExt(List.of("h3")),
			new ServerNameExt(null),
			new UnknownExtension(0x001c, randomBytes(4)))));
		assertRoundTrip(new EncryptedExtensionsMessage(List.of()));
	}

	@Test
	public void roundTripsCertificate() throws Exception {
		CertificateMessage message = new CertificateMessage(
			randomBytes(random.nextInt(11)),
			List.of(
				new CertificateEntry(randomBytes(100 + random.nextInt(300)), List.of()),
				new CertificateEntry(randomBytes(100 + random.nextInt(300)),
					List.of(new UnknownExtension(0x0017, randomBytes(3))))));
		assertRoundTrip(message);
	}

	@Test
	public void roundTripsCertificateVerify() throws Exception {
		assertRoundTrip(new CertificateVerifyMessage(
			SignatureScheme.RSA_PSS_RSAE_SHA384.code(), randomBytes(64 + random.nextInt(200))));
		assertRoundTrip(new CertificateVerifyMessage(0x0a0a /* GREASE */, randomBytes(48)));
	}

	@Test
	public void roundTripsFinished() throws Exception {
		assertRoundTrip(new FinishedMessage(randomBytes(32))); // SHA-256
		assertRoundTrip(new FinishedMessage(randomBytes(48))); // SHA-384
	}

	@Test
	public void roundTripsNewSessionTicket() throws Exception {
		NewSessionTicketMessage message = new NewSessionTicketMessage(
			random.nextLong() & 0xFFFFFFFFL,
			random.nextLong() & 0xFFFFFFFFL,
			randomBytes(1 + random.nextInt(255)),
			randomBytes(1 + random.nextInt(300)),
			List.of(new UnknownExtension(0x002a, new byte[] {0, 0, 4, 0})));
		assertRoundTrip(message);
	}

	// ---- (c) unknown message type ----

	@Test
	public void unknownMessageTypeThrowsMalformedDataException() {
		// Includes KeyUpdate (24): forbidden in QUIC (RFC 9001 §6), but at the raw-parse layer
		// it is simply an unknown type (data-model.md); the engines own the alert mapping.
		for (int unknownType : new int[] {0, 24, 99, 255}) {
			ByteBuf buf = ByteBufPool.allocate(4);
			buf.writeByte((byte) unknownType);
			writeUint24(buf, 0);
			try {
				TlsMessages.read(buf);
				fail("expected MalformedDataException for type " + unknownType);
			} catch (MalformedDataException expected) {
				// expected
			} catch (Exception e) {
				fail("unexpected " + e);
			} finally {
				buf.recycle();
			}
		}
	}

	// ---- (d) declared-length bounds (FR-017) ----

	@Test
	public void declaredLengthExceedingRemainingThrowsMalformedDataExceptionWithoutAllocation() {
		ByteBuf buf = ByteBufPool.allocate(4 + 10);
		buf.writeByte((byte) ClientHelloMessage.TYPE);
		writeUint24(buf, 100);
		buf.put(new byte[10]);
		try {
			TlsMessages.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} catch (TlsAlertException e) {
			fail("unexpected TlsAlertException: " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void declaredLengthExceedingConfiguredMaximumThrowsTlsAlertDecodeError() {
		int tooLarge = TlsMessages.MAX_HANDSHAKE_MESSAGE_SIZE.toInt() + 1;
		ByteBuf buf = ByteBufPool.allocate(4 + tooLarge);
		buf.writeByte((byte) CertificateMessage.TYPE);
		writeUint24(buf, tooLarge);
		buf.put(new byte[tooLarge]);
		try {
			TlsMessages.read(buf);
			fail("expected TlsAlertException");
		} catch (MalformedDataException e) {
			fail("unexpected MalformedDataException: " + e);
		} catch (TlsAlertException expected) {
			assertEquals(TlsAlerts.DECODE_ERROR, expected.alertCode());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void certificateEntryCountAboveSixteenThrowsMalformedDataException() {
		// FR-017: at most 16 certificate entries per Certificate message.
		int entries = CertificateMessage.MAX_CERTIFICATE_ENTRIES + 1;
		int entryLength = 3 + 1 + 2; // 3-byte cert length + 1 cert byte + 2-byte extensions length
		ByteBuf buf = ByteBufPool.allocate(4 + 1 + 3 + entries * entryLength);
		buf.writeByte((byte) CertificateMessage.TYPE);
		writeUint24(buf, 1 + 3 + entries * entryLength);
		buf.writeByte((byte) 0); // empty certificate_request_context
		writeUint24(buf, entries * entryLength);
		for (int i = 0; i < entries; i++) {
			writeUint24(buf, 1);
			buf.writeByte((byte) 0x30);
			writeShort(buf, 0);
		}
		try {
			TlsMessages.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} catch (TlsAlertException e) {
			fail("unexpected TlsAlertException: " + e);
		} finally {
			buf.recycle();
		}
	}

	// ---- (e) truncation ----

	@Test
	public void truncatedHeaderThrowsTruncatedDataException() {
		ByteBuf buf = ByteBufPool.allocate(3);
		buf.put(new byte[] {0x01, 0x00, 0x00});
		try {
			TlsMessages.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (Exception e) {
			fail("unexpected " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void truncatedFixedFieldsThrowTruncatedDataException() {
		// ServerHello body of 10 bytes: legacy_version + random alone need 34.
		ByteBuf buf = ByteBufPool.allocate(4 + 10);
		buf.writeByte((byte) ServerHelloMessage.TYPE);
		writeUint24(buf, 10);
		buf.put(new byte[10]);
		try {
			TlsMessages.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (Exception e) {
			fail("unexpected " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void truncatedInnerVectorThrowsTruncatedDataException() {
		// ClientHello body: legacy_version + random + session-id length 20 with 5 bytes present.
		ByteBuf buf = ByteBufPool.allocate(4 + 34 + 1 + 5);
		buf.writeByte((byte) ClientHelloMessage.TYPE);
		writeUint24(buf, 34 + 1 + 5);
		writeShort(buf, 0x0303);
		buf.put(new byte[32]);
		buf.writeByte((byte) 20);
		buf.put(new byte[5]);
		try {
			TlsMessages.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (Exception e) {
			fail("unexpected " + e);
		} finally {
			buf.recycle();
		}
	}

	// ---- helpers ----

	private void assertRoundTrip(TlsHandshakeMessage message) throws Exception {
		ByteBuf buf = encode(message);
		TlsHandshakeMessage decoded = TlsMessages.read(buf);
		assertFalse(buf.canRead());
		assertEquals(message, decoded);
		assertEquals(message.hashCode(), decoded.hashCode());
		buf.recycle();
	}

	private static ByteBuf encode(TlsHandshakeMessage message) {
		ByteBuf buf = ByteBufPool.allocate(message.encodedLength());
		TlsMessages.write(buf, message);
		assertEquals(message.encodedLength(), buf.readRemaining());
		return buf;
	}

	private static void assertReserializesTo(byte[] expected, TlsHandshakeMessage message) {
		ByteBuf buf = encode(message);
		byte[] actual = new byte[buf.readRemaining()];
		buf.read(actual);
		buf.recycle();
		assertArrayEquals(expected, actual);
	}

	private static TlsHandshakeMessage parse(byte[] encoded) throws Exception {
		ByteBuf buf = wrap(encoded);
		try {
			TlsHandshakeMessage message = TlsMessages.read(buf);
			assertFalse(buf.canRead());
			return message;
		} finally {
			buf.recycle();
		}
	}

	private static ByteBuf wrap(byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}

	private static <T extends TlsExtension> T findExtension(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}

	private static void writeShort(ByteBuf buf, int v) {
		buf.writeByte((byte) (v >>> 8));
		buf.writeByte((byte) v);
	}

	private static void writeUint24(ByteBuf buf, int v) {
		buf.writeByte((byte) (v >>> 16));
		buf.writeByte((byte) (v >>> 8));
		buf.writeByte((byte) v);
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}
}
