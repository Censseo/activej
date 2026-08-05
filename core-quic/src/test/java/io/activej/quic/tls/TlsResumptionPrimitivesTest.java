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
import io.activej.quic.crypto.Hkdf;
import io.activej.quic.tls.PreSharedKeyExt.PskIdentity;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.MessageDigest;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The resumption wire primitives (G5-1) and key-schedule derivations (G5-2) the client and server
 * engines share: {@code pre_shared_key} (RFC 8446 §4.2.11), {@code early_data} (§4.2.10), the PSK
 * branch of the key schedule (§7.1) and the binder truncation rule (§4.2.11.2).
 * <p>
 * Three shapes, per the module convention: round-trip over both forms of each extension,
 * adversarial input against every declared length, and derivations re-computed independently from
 * {@link Hkdf} so an engine-side bug cannot hide behind the same helper.
 */
public class TlsResumptionPrimitivesTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// ---- pre_shared_key ----

	@Test
	public void preSharedKeyOfferRoundTrips() throws Exception {
		PreSharedKeyExt offer = PreSharedKeyExt.ofClientOffer(
			List.of(new PskIdentity(new byte[] {1, 2, 3, 4, 5}, 0xFFFFFFFFL),
				new PskIdentity(new byte[] {9}, 0)),
			List.of(binder(32, (byte) 0x11), binder(48, (byte) 0x22)));

		assertEquals(41, offer.type());
		assertTrue(offer.isOffer());
		assertEquals(-1, offer.selectedIdentity);

		PreSharedKeyExt parsed = (PreSharedKeyExt) reparse(offer);
		assertTrue(parsed.isOffer());
		assertNotNull(parsed.identities);
		assertNotNull(parsed.binders);
		assertEquals(2, parsed.identities.size());
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, parsed.identities.get(0).identity());
		assertEquals(0xFFFFFFFFL, parsed.identities.get(0).obfuscatedTicketAge());
		assertEquals(0, parsed.identities.get(1).obfuscatedTicketAge());
		assertArrayEquals(binder(32, (byte) 0x11), parsed.binders.get(0));
		assertArrayEquals(binder(48, (byte) 0x22), parsed.binders.get(1));
	}

	@Test
	public void preSharedKeySelectedIdentityRoundTrips() throws Exception {
		PreSharedKeyExt selected = PreSharedKeyExt.ofSelectedIdentity(0);
		assertFalse(selected.isOffer());
		assertNull(selected.identities);
		assertNull(selected.binders);
		assertEquals(6, selected.encodedLength());

		PreSharedKeyExt parsed = (PreSharedKeyExt) reparse(selected);
		assertFalse(parsed.isOffer());
		assertEquals(0, parsed.selectedIdentity);
		assertEquals(PreSharedKeyExt.ofSelectedIdentity(7), reparse(PreSharedKeyExt.ofSelectedIdentity(7)));
	}

	/**
	 * The two forms are told apart by body length, exactly as {@code SupportedVersionsExt} does: an
	 * offer is at least 44 bytes ({@code identities<7..>} + {@code binders<33..>} plus two vector
	 * lengths), so a 2-byte body can only be {@code selected_identity}.
	 */
	@Test
	public void theOfferFormCanNeverBeTwoBytesLong() {
		PreSharedKeyExt smallestOffer = PreSharedKeyExt.ofClientOffer(
			List.of(new PskIdentity(new byte[] {1}, 0)), List.of(binder(32, (byte) 0)));
		assertEquals(4 + 44, smallestOffer.encodedLength());
	}

	@Test
	public void bindersSectionLengthIsTheTruncationWidth() {
		PreSharedKeyExt offer = PreSharedKeyExt.ofClientOffer(
			List.of(new PskIdentity(new byte[] {1, 2, 3}, 5)), List.of(binder(48, (byte) 7)));
		assertEquals(2 + 1 + 48, offer.bindersSectionLength());

		byte[] bytes = serialize(offer);
		// the trailing bindersSectionLength() bytes are exactly the binders vector
		assertEquals((byte) (7 + 47), bytes[bytes.length - 1]);
		assertEquals((byte) 48, bytes[bytes.length - 49]);
		assertEquals((byte) 49, bytes[bytes.length - 50]);
	}

	@Test
	public void mismatchedIdentityAndBinderCountsAreRejected() {
		PreSharedKeyExt offer = PreSharedKeyExt.ofClientOffer(
			List.of(new PskIdentity(new byte[] {1}, 0), new PskIdentity(new byte[] {2}, 0)),
			List.of(binder(32, (byte) 1), binder(32, (byte) 2)));
		byte[] bytes = serialize(offer);
		// drop the second binder from the wire, leaving two identities against one binder
		byte[] truncated = new byte[bytes.length - 33];
		System.arraycopy(bytes, 0, truncated, 0, truncated.length);
		patchLength(truncated, truncated.length - 4);
		patchBindersVectorLength(truncated, 33);

		assertThrows(MalformedDataException.class, () -> parseExtension(truncated));
	}

	@Test
	public void anOverDeclaredIdentitiesVectorIsRejectedBeforeAllocating() {
		byte[] bytes = serialize(PreSharedKeyExt.ofClientOffer(
			List.of(new PskIdentity(new byte[] {1, 2, 3}, 0)), List.of(binder(32, (byte) 3))));
		// identities vector length sits at offset 4
		bytes[4] = (byte) 0x7F;
		bytes[5] = (byte) 0xFF;
		assertThrows(MalformedDataException.class, () -> parseExtension(bytes));
	}

	@Test
	public void anEmptyIdentityAndAnUndersizedBinderAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new PskIdentity(new byte[0], 0));
		assertThrows(IllegalArgumentException.class,
			() -> PreSharedKeyExt.ofClientOffer(List.of(new PskIdentity(new byte[] {1}, 0)), List.of(binder(31, (byte) 0))));
		assertThrows(IllegalArgumentException.class,
			() -> PreSharedKeyExt.ofClientOffer(List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new PskIdentity(new byte[] {1}, 0x1_0000_0000L));
	}

	// ---- early_data ----

	@Test
	public void earlyDataBothFormsRoundTrip() throws Exception {
		EarlyDataExt empty = EarlyDataExt.empty();
		assertEquals(42, empty.type());
		assertFalse(empty.hasMaxEarlyDataSize());
		assertEquals(4, empty.encodedLength());
		assertEquals(empty, reparse(empty));

		EarlyDataExt sized = EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE);
		assertTrue(sized.hasMaxEarlyDataSize());
		assertEquals(8, sized.encodedLength());
		EarlyDataExt parsed = (EarlyDataExt) reparse(sized);
		assertEquals(0xFFFFFFFFL, parsed.maxEarlyDataSize);
		assertEquals(sized, parsed);
		assertNotEquals(empty, sized);
	}

	@Test
	public void anEarlyDataBodyOfAnyOtherLengthIsRejected() {
		for (int bodyLength : new int[] {1, 2, 3, 5, 8}) {
			byte[] bytes = new byte[4 + bodyLength];
			bytes[0] = 0x00;
			bytes[1] = 0x2a;
			bytes[2] = (byte) (bodyLength >>> 8);
			bytes[3] = (byte) bodyLength;
			assertThrows("early_data body of " + bodyLength + " bytes",
				MalformedDataException.class, () -> parseExtension(bytes));
		}
	}

	@Test
	public void earlyDataRejectsANonUint32MaxSize() {
		assertThrows(IllegalArgumentException.class, () -> EarlyDataExt.ofMaxEarlyDataSize(-1));
		assertThrows(IllegalArgumentException.class, () -> EarlyDataExt.ofMaxEarlyDataSize(0x1_0000_0000L));
	}

	// ---- key schedule: the PSK branch ----

	@Test
	public void startWithPskExtractsTheEarlySecretFromThePsk() {
		byte[] psk = bytes(32, 3);
		TlsKeySchedule schedule = TlsKeySchedule.startWithPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, psk);
		assertArrayEquals(Hkdf.extract("HmacSHA256", new byte[0], psk), schedule.earlySecret());

		// the zero-PSK form is untouched
		assertArrayEquals(
			Hkdf.extract("HmacSHA256", new byte[0], new byte[32]),
			TlsKeySchedule.start(TlsCipherSuite.TLS_AES_128_GCM_SHA256).earlySecret());
	}

	@Test
	public void theBinderKeyAndEarlyTrafficSecretMatchAnIndependentDerivation() throws Exception {
		byte[] psk = bytes(48, 11);
		TlsCipherSuite suite = TlsCipherSuite.TLS_AES_256_GCM_SHA384;
		TlsKeySchedule schedule = TlsKeySchedule.startWithPsk(suite, psk);

		byte[] earlySecret = Hkdf.extract("HmacSHA384", new byte[0], psk);
		byte[] emptyHash = MessageDigest.getInstance("SHA-384").digest();
		assertArrayEquals(
			Hkdf.expandLabel("HmacSHA384", earlySecret, "res binder", emptyHash, 48),
			schedule.resumptionBinderKey());

		byte[] clientHelloHash = MessageDigest.getInstance("SHA-384").digest(new byte[] {1, 2, 3});
		assertArrayEquals(
			Hkdf.expandLabel("HmacSHA384", earlySecret, "c e traffic", clientHelloHash, 48),
			schedule.clientEarlyTrafficSecret(clientHelloHash));
	}

	@Test
	public void theBinderKeyAndEarlyTrafficSecretAreEarlyStateOnly() {
		TlsKeySchedule schedule = TlsKeySchedule.startWithPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, bytes(32, 1));
		schedule.mixEcdhe(bytes(32, 2));
		assertThrows(IllegalStateException.class, schedule::resumptionBinderKey);
		assertThrows(IllegalStateException.class, () -> schedule.clientEarlyTrafficSecret(new byte[32]));
	}

	@Test
	public void thePskBinderIsAnHmacUnderTheBinderKeysFinishedKey() {
		TlsKeySchedule schedule = TlsKeySchedule.startWithPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, bytes(32, 5));
		byte[] binderKey = schedule.resumptionBinderKey();
		byte[] truncatedHash = bytes(32, 9);
		assertArrayEquals(
			schedule.verifyData(schedule.finishedKey(binderKey), truncatedHash),
			schedule.pskBinder(binderKey, truncatedHash));
	}

	@Test
	public void theResumptionPskIsExpandedFromTheResumptionMasterSecretAndTheNonce() {
		byte[] resumptionMaster = bytes(32, 13);
		assertArrayEquals(
			Hkdf.expandLabel("HmacSHA256", resumptionMaster, "resumption", new byte[] {0, 1}, 32),
			TlsKeySchedule.resumptionPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, resumptionMaster, new byte[] {0, 1}));

		// a zero-length ticket_nonce is legal (RFC 8446 §4.6.1) and must derive, not throw
		assertArrayEquals(
			Hkdf.expandLabel("HmacSHA256", resumptionMaster, "resumption", new byte[0], 32),
			TlsKeySchedule.resumptionPsk(TlsCipherSuite.TLS_AES_128_GCM_SHA256, resumptionMaster, new byte[0]));

		assertEquals(48, TlsKeySchedule.resumptionPsk(
			TlsCipherSuite.TLS_AES_256_GCM_SHA384, bytes(48, 2), new byte[] {7}).length);
	}

	// ---- the truncation rule ----

	@Test
	public void theTruncatedHashCoversEverythingButTheBindersVector() throws Exception {
		byte[] clientHello = bytes(200, 1);
		int bindersSectionLength = 35;
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(clientHello, 0, clientHello.length - bindersSectionLength);

		assertArrayEquals(digest.digest(), TlsPskBinders.truncatedClientHelloHash(
			TlsCipherSuite.TLS_AES_128_GCM_SHA256, clientHello, bindersSectionLength));
		assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(clientHello),
			TlsPskBinders.clientHelloHash(TlsCipherSuite.TLS_AES_128_GCM_SHA256, clientHello));
	}

	@Test
	public void aTruncationWidthOutsideTheMessageIsACallerBug() {
		byte[] clientHello = bytes(64, 1);
		assertThrows(IllegalArgumentException.class,
			() -> TlsPskBinders.truncatedClientHelloHash(TlsCipherSuite.TLS_AES_128_GCM_SHA256, clientHello, 0));
		assertThrows(IllegalArgumentException.class,
			() -> TlsPskBinders.truncatedClientHelloHash(TlsCipherSuite.TLS_AES_128_GCM_SHA256, clientHello, 64));
		assertThrows(IllegalArgumentException.class,
			() -> TlsPskBinders.truncatedClientHelloHash(TlsCipherSuite.TLS_AES_128_GCM_SHA256, clientHello, -1));
	}

	@Test
	public void writeBinderIntoPatchesTheTrailingBytesOnly() {
		byte[] clientHello = bytes(64, 0);
		byte[] binder = bytes(32, 77);
		byte[] prefix = new byte[32];
		System.arraycopy(clientHello, 0, prefix, 0, 32);

		TlsPskBinders.writeBinderInto(clientHello, binder);
		for (int i = 0; i < 32; i++) {
			assertEquals(prefix[i], clientHello[i]);
			assertEquals(binder[i], clientHello[32 + i]);
		}
		assertThrows(IllegalArgumentException.class, () -> TlsPskBinders.writeBinderInto(new byte[8], binder));
		assertThrows(IllegalArgumentException.class, () -> TlsPskBinders.writeBinderInto(clientHello, new byte[0]));
	}

	@Test
	public void binderVerificationIsAConstantTimeComparison() {
		byte[] expected = bytes(32, 4);
		assertTrue(TlsPskBinders.verifyBinder(expected, expected.clone()));
		byte[] wrong = expected.clone();
		wrong[31] ^= 0x01;
		assertFalse(TlsPskBinders.verifyBinder(expected, wrong));
		assertFalse(TlsPskBinders.verifyBinder(expected, bytes(48, 4)));
	}

	// ---- helpers ----

	private static TlsExtension reparse(TlsExtension extension) throws Exception {
		return parseExtension(serialize(extension));
	}

	private static TlsExtension parseExtension(byte[] bytes) throws Exception {
		ByteBuf buf = ByteBuf.wrapForReading(bytes);
		TlsExtension extension = TlsExtensions.read(buf);
		assertEquals("the extension consumed exactly its declared bytes", 0, buf.readRemaining());
		return extension;
	}

	private static byte[] serialize(TlsExtension extension) {
		ByteBuf buf = ByteBufPool.allocate(extension.encodedLength());
		extension.writeTo(buf);
		return buf.asArray();
	}

	private static void patchLength(byte[] extensionBytes, int bodyLength) {
		extensionBytes[2] = (byte) (bodyLength >>> 8);
		extensionBytes[3] = (byte) bodyLength;
	}

	private static void patchBindersVectorLength(byte[] extensionBytes, int bindersLength) {
		int offset = extensionBytes.length - bindersLength - 2;
		extensionBytes[offset] = (byte) (bindersLength >>> 8);
		extensionBytes[offset + 1] = (byte) bindersLength;
	}

	private static byte[] binder(int length, byte seed) {
		byte[] binder = new byte[length];
		for (int i = 0; i < length; i++) binder[i] = (byte) (seed + i);
		return binder;
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + seed);
		return bytes;
	}
}
