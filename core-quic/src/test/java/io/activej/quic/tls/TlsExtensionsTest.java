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
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Round-trip identity and malformed/truncated-input error paths for every extension subtype in
 * data-model.md (RFC 8446 §4.2, RFC 9001 §8.2), plus GREASE/unknown tolerance (RFC 8701).
 */
public class TlsExtensionsTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final Random random = new Random(71);

	@Test
	public void roundTripsSupportedVersionsClientListForm() throws Exception {
		assertRoundTrip(SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3));
		assertRoundTrip(SupportedVersionsExt.ofClientVersions(0x0304, 0x0303));
	}

	@Test
	public void roundTripsSupportedVersionsServerSelectedForm() throws Exception {
		assertRoundTrip(SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3));
	}

	@Test
	public void parsesRfc8448SupportedVersionsBytes() throws Exception {
		// RFC 8448 §3 ClientHello extension: 00 2b 00 03 02 03 04 (CH list form, TLS 1.3 only)
		SupportedVersionsExt clientForm = (SupportedVersionsExt) parse(hex("002b0003020304"));
		assertFalse(clientForm.selectedForm);
		assertArrayEquals(new int[] {0x0304}, clientForm.versions);

		// RFC 8448 §3 ServerHello extension: 00 2b 00 02 03 04 (SH selected form)
		SupportedVersionsExt selectedForm = (SupportedVersionsExt) parse(hex("002b00020304"));
		assertTrue(selectedForm.selectedForm);
		assertArrayEquals(new int[] {0x0304}, selectedForm.versions);
	}

	@Test
	public void roundTripsKeyShareClientSharesForm() throws Exception {
		byte[] x25519Key = randomBytes(32);
		byte[] p256Key = randomBytes(65);
		p256Key[0] = 0x04; // uncompressed point form
		byte[] unknownGroupKey = randomBytes(17); // unknown/GREASE group: any length tolerated
		KeyShareExt ext = KeyShareExt.ofClientShares(List.of(
			new KeyShareEntry(NamedGroup.X25519.code(), x25519Key),
			new KeyShareEntry(NamedGroup.SECP256R1.code(), p256Key),
			new KeyShareEntry(0x0a0a, unknownGroupKey)));
		assertRoundTrip(ext);
	}

	@Test
	public void roundTripsKeyShareServerSelectedForm() throws Exception {
		KeyShareExt ext = KeyShareExt.ofSelectedShare(
			new KeyShareEntry(NamedGroup.X25519.code(), Rfc8448.SERVER_EPHEMERAL_PUBLIC));
		assertRoundTrip(ext);
	}

	@Test
	public void parsesRfc8448ServerKeyShareBytes() throws Exception {
		// RFC 8448 §3 ServerHello extension: 00 33 00 24 00 1d 00 20 <32-byte x25519 public key>
		KeyShareExt ext = (KeyShareExt) parse(hex(
			"00330024001d0020c9828876112095fe66762bdbf7c672e156d6cc253b833df1dd69b1b04e751f0f"));
		assertNull(ext.clientShares);
		assertNotNull(ext.selectedShare);
		assertEquals(NamedGroup.X25519, ext.selectedShare.namedGroup());
		assertArrayEquals(Rfc8448.SERVER_EPHEMERAL_PUBLIC, ext.selectedShare.keyExchange());
	}

	@Test
	public void wrongLengthKeyShareThrowsMalformedDataException() {
		// x25519 key exchange must be exactly 32 bytes (RFC 8446 §4.2.8); 31 is a decode error
		// surfaced before any crypto runs.
		byte[] body = new byte[4 + 2 + 31];
		body[0] = 0x00;
		body[1] = 0x23; // client_shares list length: 35
		body[2] = 0x00;
		body[3] = 0x1d; // x25519
		body[4] = 0x00;
		body[5] = 0x1f; // declared key_exchange length: 31
		ByteBuf buf = ByteBufPool.allocate(4 + body.length);
		writeShort(buf, KeyShareExt.TYPE);
		writeShort(buf, body.length);
		buf.put(body);
		try {
			TlsExtensions.read(buf);
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
	public void roundTripsSupportedGroups() throws Exception {
		SupportedGroupsExt ext = new SupportedGroupsExt(
			NamedGroup.X25519.code(), NamedGroup.SECP256R1.code(), 0x0a0a /* GREASE */);
		assertRoundTrip(ext);
		SupportedGroupsExt decoded = (SupportedGroupsExt) parse(encode(ext));
		assertEquals(List.of(NamedGroup.X25519, NamedGroup.SECP256R1), decoded.knownGroups());
	}

	@Test
	public void roundTripsSignatureAlgorithms() throws Exception {
		assertRoundTrip(new SignatureAlgorithmsExt(
			SignatureScheme.ECDSA_SECP256R1_SHA256.code(),
			SignatureScheme.RSA_PSS_RSAE_SHA256.code(),
			SignatureScheme.RSA_PSS_RSAE_SHA384.code(),
			SignatureScheme.RSA_PSS_RSAE_SHA512.code(),
			SignatureScheme.ED25519.code(),
			0x0401 /* rsa_pkcs1_sha256: parsed, never selected */));
	}

	@Test
	public void roundTripsAlpn() throws Exception {
		assertRoundTrip(new AlpnExt(List.of("h3")));
		assertRoundTrip(new AlpnExt(List.of("h3", "hq-interop")));
	}

	@Test
	public void roundTripsServerName() throws Exception {
		assertRoundTrip(new ServerNameExt("example.test"));
		assertRoundTrip(new ServerNameExt(null));
	}

	@Test
	public void parsesRfc8448ServerNameBytes() throws Exception {
		// RFC 8448 §3 ClientHello extension: 00 00 00 0b 00 09 00 00 06 "server"
		ServerNameExt ext = (ServerNameExt) parse(hex("0000000b0009000006736572766572"));
		assertEquals("server", ext.hostName);
	}

	@Test
	public void roundTripsPskKeyExchangeModes() throws Exception {
		assertRoundTrip(new PskKeyExchangeModesExt(PskKeyExchangeModesExt.PSK_DHE_KE));
	}

	@Test
	public void roundTripsQuicTransportParameters() throws Exception {
		QuicTransportParameters parameters = QuicTransportParameters.defaults(new byte[] {1, 2, 3, 4});
		assertRoundTrip(new QuicTransportParametersExt(parameters));
	}

	@Test
	public void unknownAndGreaseExtensionsParseToOpaqueForm() throws Exception {
		// GREASE value (RFC 8701) and an unassigned codepoint: parsed as opaque bytes,
		// re-serialized identically, never interpreted or echoed back by the engine.
		for (int type : new int[] {0x0a0a, 0x1a1a, 0x0065}) {
			byte[] data = randomBytes(11);
			UnknownExtension ext = new UnknownExtension(type, data);
			ByteBuf encoded = encode(ext);
			TlsExtension decoded = TlsExtensions.read(encoded);
			assertFalse(encoded.canRead());
			assertTrue(decoded instanceof UnknownExtension);
			assertEquals(type, ((UnknownExtension) decoded).typeCode);
			assertArrayEquals(data, ((UnknownExtension) decoded).data());
			encoded.recycle();
		}
	}

	@Test
	public void duplicateExtensionTypeAbortsWithIllegalParameter() {
		// RFC 8446 §4.2: a given extension block MUST NOT carry two extensions of the same type
		ByteBuf buf = ByteBufPool.allocate(32);
		TlsExtensions.write(buf, SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3));
		TlsExtensions.write(buf, SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3));
		try {
			TlsAlertException e = assertThrows(TlsAlertException.class, () -> TlsExtensions.readList(buf));
			assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void duplicateUnknownExtensionTypeAbortsWithIllegalParameter() {
		// the no-duplicate rule covers unknown and GREASE codepoints alike (RFC 8446 §4.2)
		ByteBuf buf = ByteBufPool.allocate(32);
		TlsExtensions.write(buf, new UnknownExtension(0x0065, new byte[] {1, 2, 3}));
		TlsExtensions.write(buf, new UnknownExtension(0x0065, new byte[] {4, 5, 6}));
		try {
			TlsAlertException e = assertThrows(TlsAlertException.class, () -> TlsExtensions.readList(buf));
			assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void distinctExtensionTypesInOneBlockAreAccepted() throws Exception {
		ByteBuf buf = ByteBufPool.allocate(64);
		TlsExtensions.write(buf, SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3));
		TlsExtensions.write(buf, new AlpnExt(List.of("h3")));
		TlsExtensions.write(buf, new UnknownExtension(0x0065, new byte[] {1, 2, 3}));
		try {
			List<TlsExtension> extensions = TlsExtensions.readList(buf);
			assertEquals(3, extensions.size());
			assertFalse(buf.canRead());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void truncatedExtensionHeaderThrowsTruncatedDataException() {
		ByteBuf buf = ByteBufPool.allocate(3);
		buf.put(new byte[] {0x00, 0x2b, 0x00});
		try {
			TlsExtensions.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (MalformedDataException e) {
			fail("unexpected MalformedDataException: " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void declaredLengthExceedingRemainingThrowsMalformedDataExceptionWithoutAllocation() {
		// Declared body length 0xFFFF with only 3 bytes present: rejected before any allocation
		// of the declared size.
		ByteBuf buf = ByteBufPool.allocate(7);
		writeShort(buf, SupportedVersionsExt.TYPE);
		writeShort(buf, 0xFFFF);
		buf.put(new byte[] {1, 2, 3});
		try {
			TlsExtensions.read(buf);
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
	public void truncatedBodyThrowsTruncatedDataException() {
		// supported_versions CH body: list declares 4 bytes of versions, only 2 are present
		// inside the declared extension body.
		ByteBuf buf = ByteBufPool.allocate(7);
		writeShort(buf, SupportedVersionsExt.TYPE);
		writeShort(buf, 3);
		buf.put(new byte[] {4, 0x03, 0x04});
		try {
			TlsExtensions.read(buf);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (MalformedDataException e) {
			fail("unexpected MalformedDataException: " + e);
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void trailingBytesInBodyThrowMalformedDataException() {
		// SH selected form is exactly 2 bytes; a 4-byte body has trailing garbage.
		ByteBuf buf = ByteBufPool.allocate(8);
		writeShort(buf, SupportedVersionsExt.TYPE);
		writeShort(buf, 4);
		buf.put(new byte[] {0x03, 0x04, 0x03, 0x04});
		try {
			TlsExtensions.read(buf);
			fail("expected MalformedDataException");
		} catch (TruncatedDataException e) {
			fail("unexpected TruncatedDataException: " + e);
		} catch (MalformedDataException expected) {
			// expected
		} finally {
			buf.recycle();
		}
	}

	// ---- helpers ----

	private void assertRoundTrip(TlsExtension extension) throws Exception {
		ByteBuf buf = encode(extension);
		TlsExtension decoded = TlsExtensions.read(buf);
		assertFalse(buf.canRead());
		assertEquals(extension, decoded);
		assertEquals(extension.hashCode(), decoded.hashCode());
		buf.recycle();
	}

	private static ByteBuf encode(TlsExtension extension) {
		ByteBuf buf = ByteBufPool.allocate(extension.encodedLength());
		TlsExtensions.write(buf, extension);
		assertEquals(extension.encodedLength(), buf.readRemaining());
		return buf;
	}

	private static TlsExtension parse(ByteBuf encoded) throws Exception {
		try {
			TlsExtension extension = TlsExtensions.read(encoded);
			assertFalse(encoded.canRead());
			return extension;
		} finally {
			encoded.recycle();
		}
	}

	private static TlsExtension parse(byte[] encoded) throws Exception {
		return parse(wrap(encoded));
	}

	private static ByteBuf wrap(byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}

	private static void writeShort(ByteBuf buf, int v) {
		buf.writeByte((byte) (v >>> 8));
		buf.writeByte((byte) v);
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return bytes;
	}

	private static byte[] hex(String hex) {
		return java.util.HexFormat.of().parseHex(hex);
	}
}
