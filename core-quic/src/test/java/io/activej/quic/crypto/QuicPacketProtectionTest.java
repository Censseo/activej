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

package io.activej.quic.crypto;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.quic.QuicConnectionId;
import io.activej.quic.QuicDecryptionException;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.Assert.*;

/**
 * RFC 9001 Appendix A.2 (client Initial, AES-128-GCM), A.3 (server Initial) and A.4
 * (ChaCha20-Poly1305 short header) test vectors — the feature's headline command. These byte
 * arrays were fetched this session from Cloudflare quiche's own {@code packet.rs} test suite
 * (a mature, interop-tested QUIC implementation) rather than transcribed from memory, and their
 * internal consistency was cross-checked (e.g. A.2's Length field 0x449e = 1182 = pnLength(4) +
 * ciphertext(1178), matching the packet's total size of 1200 bytes exactly).
 */
public class QuicPacketProtectionTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final QuicConnectionId DCID_A1 = QuicConnectionId.of(HexFormat.of().parseHex("8394c8f03e515708"));

	private static final String PKT_A2_HEX = "c000000001088394c8f03e5157080000449e7b9aec34d1b1c98dd7689fb8ec11d242b123dc9bd8bab936b47d92ec356c0bab7df5976d27cd449f63300099f3991c260ec4c60d17b31f8429157bb35a1282a643a8d2262cad67500cadb8e7378c8eb7539ec4d4905fed1bee1fc8aafba17c750e2c7ace01e6005f80fcb7df621230c83711b39343fa028cea7f7fb5ff89eac2308249a02252155e2347b63d58c5457afd84d05dfffdb20392844ae812154682e9cf012f9021a6f0be17ddd0c2084dce25ff9b06cde535d0f920a2db1bf362c23e596dee38f5a6cf3948838a3aec4e15daf8500a6ef69ec4e3feb6b1d98e610ac8b7ec3faf6ad760b7bad1db4ba3485e8a94dc250ae3fdb41ed15fb6a8e5eba0fc3dd60bc8e30c5c4287e53805db059ae0648db2f64264ed5e39be2e20d82df566da8dd5998ccabdae053060ae6c7b4378e846d29f37ed7b4ea9ec5d82e7961b7f25a9323851f681d582363aa5f89937f5a67258bf63ad6f1a0b1d96dbd4faddfcefc5266ba6611722395c906556be52afe3f565636ad1b17d508b73d8743eeb524be22b3dcbc2c7468d54119c7468449a13d8e3b95811a198f3491de3e7fe942b330407abf82a4ed7c1b311663ac69890f4157015853d91e923037c227a33cdd5ec281ca3f79c44546b9d90ca00f064c99e3dd97911d39fe9c5d0b23a229a234cb36186c4819e8b9c5927726632291d6a418211cc2962e20fe47feb3edf330f2c603a9d48c0fcb5699dbfe5896425c5bac4aee82e57a85aaf4e2513e4f05796b07ba2ee47d80506f8d2c25e50fd14de71e6c418559302f939b0e1abd576f279c4b2e0feb85c1f28ff18f58891ffef132eef2fa09346aee33c28eb130ff28f5b766953334113211996d20011a198e3fc433f9f2541010ae17c1bf202580f6047472fb36857fe843b19f5984009ddc324044e847a4f4a0ab34f719595de37252d6235365e9b84392b061085349d73203a4a13e96f5432ec0fd4a1ee65accdd5e3904df54c1da510b0ff20dcc0c77fcb2c0e0eb605cb0504db87632cf3d8b4dae6e705769d1de354270123cb11450efc60ac47683d7b8d0f811365565fd98c4c8eb936bcab8d069fc33bd801b03adea2e1fbc5aa463d08ca19896d2bf59a071b851e6c239052172f296bfb5e72404790a2181014f3b94a4e97d117b438130368cc39dbb2d198065ae3986547926cd2162f40a29f0c3c8745c0f50fba3852e566d44575c29d39a03f0cda721984b6f440591f355e12d439ff150aab7613499dbd49adabc8676eef023b15b65bfc5ca06948109f23f350db82123535eb8a7433bdabcb909271a6ecbcb58b936a88cd4e8f2e6ff5800175f113253d8fa9ca8885c2f552e657dc603f252e1a8e308f76f0be79e2fb8f5d5fbbe2e30ecadd220723c8c0aea8078cdfcb3868263ff8f0940054da48781893a7e49ad5aff4af300cd804a6b6279ab3ff3afb64491c85194aab760d58a606654f9f4400e8b38591356fbf6425aca26dc85244259ff2b19c41b9f96f3ca9ec1dde434da7d2d392b905ddf3d1f9af93d1af5950bd493f5aa731b4056df31bd267b6b90a079831aaf579be0a39013137aac6d404f518cfd46840647e78bfe706ca4cf5e9c5453e9f7cfd2b8b4c8d169a44e55c88d4a9a7f94742411092abbdf8b889e5c199d096e3f24788";
	private static final String FRAMES_A2_HEX = "060040f1010000ed0303ebf8fa56f12939b9584a3896472ec40bb863cfd3e86804fe3a47f06a2b69484c00000413011302010000c000000010000e00000b6578616d706c652e636f6dff01000100000a00080006001d0017001800100007000504616c706e000500050100000000003300260024001d00209370b2c9caa47fbabaf4559fedba753de171fa71f50f1ce15d43e994ec74d748002b0003020304000d0010000e0403050306030203080408050806002d00020101001c00024001ffa500320408ffffffffffffffff05048000ffff07048000ffff0801100104800075300901100f088394c8f03e51570806048000ffff";

	private static final String PKT_A3_HEX = "cf000000010008f067a5502a4262b5004075c0d95a482cd0991cd25b0aac406a5816b6394100f37a1c69797554780bb38cc5a99f5ede4cf73c3ec2493a1839b3dbcba3f6ea46c5b7684df3548e7ddeb9c3bf9c73cc3f3bded74b562bfb19fb84022f8ef4cdd93795d77d06edbb7aaf2f58891850abbdca3d20398c276456cbc42158407dd074ee";
	private static final String FRAMES_A3_HEX = "02000000000600405a020000560303eefce7f7b37ba1d1632e96677825ddf73988cfc79825df566dc5430b9a045a1200130100002e00330024001d00209d3c940d89690b84d08a60993c144eca684d1081287c834d5311bcf32bb9da1a002b00020304";

	private static final String SECRET_A4_HEX = "9ac312a7f877468ebe69422748ad00a15443f18203a07d6060f688f30f21632b";
	private static final String PKT_A4_HEX = "4cfe4189655e5cd55c41f69080575d7999c25a5bfb";

	@Test
	public void opensClientInitialV1() throws Exception {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		ByteBuf packet = wrapBytes(PKT_A2_HEX);

		QuicPacketProtection.OpenResult result = QuicPacketProtection.open(clientKeys, -1, DCID_A1.length(), packet);

		assertEquals(2L, result.packetNumber);
		byte[] expectedFramesPrefix = HexFormat.of().parseHex(FRAMES_A2_HEX);
		assertArrayEquals(expectedFramesPrefix, snapshot(result.payload, expectedFramesPrefix.length));
		// RFC 9000 requires client Initial packets to be padded to 1200 bytes; the remainder of
		// the plaintext (1200 - 22 header/PN bytes - 16-byte AEAD tag = 1162) is PADDING (zero bytes).
		assertEquals(1162, result.payload.readRemaining());

		result.payload.recycle();
	}

	@Test
	public void opensServerInitialV1() throws Exception {
		QuicKeys serverKeys = QuicKeys.initial(DCID_A1).server();
		ByteBuf packet = wrapBytes(PKT_A3_HEX);

		QuicPacketProtection.OpenResult result = QuicPacketProtection.open(serverKeys, -1, DCID_A1.length(), packet);

		assertEquals(1L, result.packetNumber);
		byte[] expectedFrames = HexFormat.of().parseHex(FRAMES_A3_HEX);
		assertArrayEquals(expectedFrames, snapshot(result.payload, expectedFrames.length));

		result.payload.recycle();
	}

	@Test
	public void opensChaCha20Poly1305ShortHeaderV1() throws Exception {
		byte[] secret = HexFormat.of().parseHex(SECRET_A4_HEX);
		QuicKeys keys = deriveKeys(QuicCipherSuite.CHACHA20_POLY1305, secret);
		ByteBuf packet = wrapBytes(PKT_A4_HEX);

		// largestPnInSpace = 654,360,563 so the reconstructed value is the published 654,360,564
		QuicPacketProtection.OpenResult result = QuicPacketProtection.open(keys, 654_360_563L, 0, packet);

		assertEquals(654_360_564L, result.packetNumber);
		assertArrayEquals(new byte[] {0x01}, snapshot(result.payload, 1));
		assertEquals(1, result.payload.readRemaining());

		result.payload.recycle();
	}

	@Test
	public void protectReproducesClientInitialV1ByteForByte() throws Exception {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		byte[] fullPacket = HexFormat.of().parseHex(PKT_A2_HEX);
		byte[] framesPrefix = HexFormat.of().parseHex(FRAMES_A2_HEX);

		// header (unprotected form) through the plaintext packet number: first byte carries the
		// true reserved bits (0) and pnLength-1=3, i.e. 0xC0 | 0x03 = 0xC3. Fields: firstByte,
		// version, dcidLen+dcid, scidLen(0), tokenLen(0), Length(0x449e=1182), packetNumber(2).
		String unprotectedHeaderHex = "c3" + "00000001" + "08" + "8394c8f03e515708" + "00" + "00" + "449e" + "00000002";
		ByteBuf header = wrapBytes(unprotectedHeaderHex);

		int payloadLength = fullPacket.length - header.readRemaining() - 16; // 16-byte AEAD tag is appended by seal, not part of the plaintext
		byte[] payloadBytes = new byte[payloadLength];
		System.arraycopy(framesPrefix, 0, payloadBytes, 0, framesPrefix.length);
		ByteBuf payload = ByteBufPool.allocate(payloadLength);
		payload.put(payloadBytes);

		ByteBuf protectedPacket = QuicPacketProtection.protect(clientKeys, 2, header, payload);

		assertArrayEquals(fullPacket, snapshot(protectedPacket, protectedPacket.readRemaining()));

		header.recycle();
		payload.recycle();
		protectedPacket.recycle();
	}

	@Test
	public void corruptedTagThrowsQuicDecryptionExceptionAndRecyclesInput() throws Exception {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		byte[] tampered = HexFormat.of().parseHex(PKT_A2_HEX);
		tampered[tampered.length - 1] ^= 0x01; // flip a bit in the AEAD tag
		ByteBuf packet = ByteBufPool.allocate(tampered.length);
		packet.put(tampered);

		try {
			QuicPacketProtection.open(clientKeys, -1, DCID_A1.length(), packet);
			fail("expected QuicDecryptionException");
		} catch (QuicDecryptionException expected) {
			assertFalse("message must not contain key material", expected.getMessage().contains(HexFormat.of().formatHex(clientKeys.aeadKeyBytes())));
		}
		// QuicPacketProtection.open recycles `packet` internally on every path (including this
		// failure path); ByteBufRule will fail the class if that didn't happen.
	}

	@Test
	public void headerProtectionSampleTooShortThrowsTruncatedDataException() {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		// Enough for the envelope but not enough remaining bytes for a 16-byte HP sample.
		byte[] full = HexFormat.of().parseHex(PKT_A2_HEX);
		byte[] truncated = Arrays.copyOfRange(full, 0, 20);
		ByteBuf packet = ByteBufPool.allocate(truncated.length);
		packet.put(truncated);

		try {
			QuicPacketProtection.open(clientKeys, -1, DCID_A1.length(), packet);
			fail("expected TruncatedDataException");
		} catch (TruncatedDataException expected) {
			// expected
		} catch (MalformedDataException | QuicDecryptionException e) {
			fail("unexpected exception: " + e);
		}
	}

	@Test
	public void protectRejectsPacketBelowRfc9001MinimumProtectedLength() throws Exception {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		// Short header, pnLength=1, no DCID, pn=0; empty payload -> ciphertext is just the 16-byte
		// tag, so pnLength+ciphertext = 17 bytes, below RFC 9001 §5.4.2's 20-byte minimum.
		ByteBuf header = wrapBytes("4000");
		ByteBuf payload = ByteBufPool.allocate(0);

		try {
			QuicPacketProtection.protect(clientKeys, 0, header, payload);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		header.recycle();
		payload.recycle();
	}

	@Test
	public void openRejectsZeroFixedBit() {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		byte[] tampered = HexFormat.of().parseHex(PKT_A2_HEX);
		tampered[0] = (byte) (tampered[0] & ~0x40); // clear the fixed bit; never touched by header protection
		ByteBuf packet = ByteBufPool.allocate(tampered.length);
		packet.put(tampered);

		try {
			QuicPacketProtection.open(clientKeys, -1, DCID_A1.length(), packet);
			fail("expected MalformedDataException");
		} catch (MalformedDataException expected) {
			// expected
		} catch (QuicDecryptionException e) {
			fail("unexpected exception: " + e);
		}
	}

	@Test
	public void openRejectsNegativeShortHeaderDcidLength() {
		byte[] secret = HexFormat.of().parseHex(SECRET_A4_HEX);
		QuicKeys keys = deriveKeys(QuicCipherSuite.CHACHA20_POLY1305, secret);
		ByteBuf packet = wrapBytes(PKT_A4_HEX);

		try {
			QuicPacketProtection.open(keys, 654_360_563L, -1, packet);
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		} catch (MalformedDataException | QuicDecryptionException e) {
			fail("unexpected exception: " + e);
		}
	}

	@Test
	public void openReportsAeadFailureBeforeReservedBitViolation() throws Exception {
		QuicKeys clientKeys = QuicKeys.initial(DCID_A1).client();
		ByteBuf header = wrapBytes("4002"); // short header, pnLength=1, no DCID, pn=2
		ByteBuf payload = ByteBufPool.allocate(4);
		payload.put(new byte[] {1, 2, 3, 4});

		ByteBuf protectedPacket = QuicPacketProtection.protect(clientKeys, 2, header, payload);
		byte[] tampered = snapshot(protectedPacket, protectedPacket.readRemaining());
		// Bit 0x08 is a short-header reserved bit; header protection masks it linearly, so
		// flipping it here has the same effect as flipping it in the unprotected header, and it
		// is part of the AEAD's associated data, so this must fail authentication (RFC 9001
		// §5.4.1's reserved-bit check only applies "after removing both packet and header
		// protection", i.e. after AEAD success) rather than surface as MalformedDataException.
		tampered[0] ^= 0x08;
		ByteBuf toOpen = ByteBufPool.allocate(tampered.length);
		toOpen.put(tampered);

		try {
			QuicPacketProtection.open(clientKeys, -1, 0, toOpen);
			fail("expected QuicDecryptionException");
		} catch (QuicDecryptionException expected) {
			// expected
		} catch (MalformedDataException e) {
			fail("unexpected exception: " + e);
		}

		header.recycle();
		payload.recycle();
		protectedPacket.recycle();
	}

	@Test
	public void protectAndOpenRoundTripAes256Gcm() throws Exception {
		byte[] secret = new byte[48];
		Arrays.fill(secret, (byte) 0x42);
		QuicKeys keys = deriveKeys(QuicCipherSuite.AES_256_GCM, secret);

		ByteBuf header = wrapBytes("4001"); // short header, pnLength=1, no DCID, pn=1
		ByteBuf payload = ByteBufPool.allocate(4);
		payload.put(new byte[] {1, 2, 3, 4});

		ByteBuf protectedPacket = QuicPacketProtection.protect(keys, 1, header, payload);
		QuicPacketProtection.OpenResult result = QuicPacketProtection.open(keys, 0, 0, protectedPacket);

		assertEquals(1L, result.packetNumber);
		assertArrayEquals(new byte[] {1, 2, 3, 4}, snapshot(result.payload, 4));

		header.recycle();
		payload.recycle();
		result.payload.recycle();
	}

	// ---- helpers ----

	private static QuicKeys deriveKeys(QuicCipherSuite suite, byte[] secret) {
		byte[] key = Hkdf.expandLabel(suite.hkdfHash(), secret, "quic key", new byte[0], suite.keyLength());
		byte[] iv = Hkdf.expandLabel(suite.hkdfHash(), secret, "quic iv", new byte[0], suite.ivLength());
		byte[] hp = Hkdf.expandLabel(suite.hkdfHash(), secret, "quic hp", new byte[0], suite.keyLength());
		return new QuicKeys(suite, key, iv, hp);
	}

	private static ByteBuf wrapBytes(String hex) {
		byte[] bytes = HexFormat.of().parseHex(hex);
		ByteBuf buf = ByteBufPool.allocate(bytes.length);
		buf.put(bytes);
		return buf;
	}

	private static byte[] snapshot(ByteBuf buf, int length) {
		return Arrays.copyOfRange(buf.array(), buf.head(), buf.head() + length);
	}
}
