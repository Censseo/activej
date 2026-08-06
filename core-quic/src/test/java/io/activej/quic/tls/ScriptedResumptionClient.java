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
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import org.jetbrains.annotations.Nullable;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A scripted TLS 1.3 client that can offer a PSK, for the US3 server-side resumption tests
 * (T058, T059). It follows the {@link ScriptedTlsServer} precedent: a hand-driven peer built from
 * the public message/extension types, re-deriving every secret independently through
 * {@link TlsKeySchedule} so a server derivation bug cannot hide behind a matching one here.
 * <p>
 * It never uses {@link TlsClientEngine} — the client half of resumption is a different task, and
 * driving these tests through it would make them pass or fail for reasons on the wrong side of the
 * wire.
 */
final class ScriptedResumptionClient {

	static final String ALPN_H3 = "h3";
	static final String SERVER_NAME = "example.com";

	static final QuicTransportParameters CLIENT_PARAMS = QuicTransportParameters.defaults(new byte[] {10, 11, 12, 13});

	static final QuicTransportParameters SERVER_PARAMS = new QuicTransportParameters(
		new byte[] {1, 2, 3, 4}, 0, null, QuicTransportParameters.DEFAULT_MAX_UDP_PAYLOAD_SIZE,
		1 << 20, 1 << 16, 1 << 16, 1 << 16, 100, 100,
		QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
		false, null, QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT,
		new byte[] {5, 6, 7, 8}, null, 0);

	/**
	 * Which span of the ClientHello the offered binder is computed over. Only {@link #TRUNCATED} is
	 * RFC 8446 §4.2.11.2 — the other two exist to prove the server rejects them.
	 */
	enum BinderScope {
		/** {@code Truncate(ClientHello)}: everything up to but excluding the binders list. */
		TRUNCATED,
		/** The whole serialized message, binders included. */
		FULL_MESSAGE,
		/** A prefix ending before the identities list, so the identities are unauthenticated. */
		BEFORE_IDENTITIES
	}

	final TranscriptHash transcript = new TranscriptHash();
	final KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
	final byte[] sessionId = {21, 22, 23, 24};

	int[] offeredCipherSuites = {TlsCipherSuite.TLS_AES_128_GCM_SHA256.code()};

	TlsCipherSuite suite;
	TlsKeySchedule schedule;
	byte[] clientHandshakeTraffic;
	byte[] serverHandshakeTraffic;
	byte[] clientApplicationTraffic;
	byte[] serverApplicationTraffic;
	byte[] serverFinishedTranscriptHash;

	/** The PSK the last {@link #resumingClientHello} offered, or {@code null} for a full handshake. */
	byte @Nullable [] offeredPsk;

	/** Whether the ServerHello carried {@code pre_shared_key}, i.e. the offer was accepted. */
	boolean serverAcceptedPsk;

	List<TlsExtension> defaultExtensions() {
		return defaultExtensions(SERVER_NAME);
	}

	List<TlsExtension> defaultExtensions(@Nullable String serverName) {
		byte[] publicKey = TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, keyPair.getPublic());
		List<TlsExtension> extensions = new ArrayList<>(List.of(
			SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3),
			new SupportedGroupsExt(new int[] {NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()}),
			KeyShareExt.ofClientShares(List.of(new KeyShareEntry(NamedGroup.X25519.code(), publicKey))),
			new SignatureAlgorithmsExt(new int[] {
				SignatureScheme.ECDSA_SECP256R1_SHA256.code(),
				SignatureScheme.RSA_PSS_RSAE_SHA256.code(),
				SignatureScheme.RSA_PSS_RSAE_SHA384.code(),
				SignatureScheme.RSA_PSS_RSAE_SHA512.code(),
				SignatureScheme.ED25519.code()}),
			new AlpnExt(List.of(ALPN_H3)),
			new PskKeyExchangeModesExt(PskKeyExchangeModesExt.PSK_DHE_KE),
			new QuicTransportParametersExt(CLIENT_PARAMS)));
		if (serverName != null) {
			extensions.add(new ServerNameExt(serverName));
		}
		return extensions;
	}

	/** A ClientHello carrying exactly {@code extensions}, in that order. Feeds the transcript. */
	byte[] clientHello(List<TlsExtension> extensions) {
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		ClientHelloMessage clientHello = new ClientHelloMessage(
			ClientHelloMessage.LEGACY_VERSION, random, sessionId,
			offeredCipherSuites.clone(), new int[] {0}, extensions);
		byte[] bytes = serialize(clientHello);
		transcript.update(bytes);
		return bytes;
	}

	/**
	 * A ClientHello whose last extension is {@code pre_shared_key} offering {@code ticket}, with a
	 * binder computed over {@code scope}.
	 * <p>
	 * Two passes: the message is serialized once with an all-zero binder of the same length, the
	 * binder is computed over the requested span of <i>that</i> encoding, and the message is
	 * serialized again with the real binder. Both encodings have identical length, so the truncation
	 * offset measured in the first pass is exactly the one the server will measure.
	 *
	 * @param reportedAgeMillis the age the client claims, before RFC 8446 §4.2.11.1 obfuscation
	 * @param binderLength {@code 0} for the suite's natural hash length, or an override (a length
	 *        below 32 is an illegal {@code PskBinderEntry} and must be refused by the parser)
	 */
	byte[] resumingClientHello(List<TlsExtension> earlierExtensions, QuicSessionTicket ticket,
			long reportedAgeMillis, BinderScope scope, int binderLength, boolean corruptBinder) {
		byte[] identity = ticket.identity();
		long obfuscatedAge = (reportedAgeMillis + ticket.ticketAgeAdd()) & 0xFFFFFFFFL;
		PreSharedKeyExt.PskIdentity pskIdentity = new PreSharedKeyExt.PskIdentity(identity, obfuscatedAge);

		byte[] psk = ticket.resumptionSecret();
		int length = binderLength > 0 ? binderLength : psk.length;

		byte[] probe = serializeWithBinder(earlierExtensions, pskIdentity, new byte[length]);
		int bindersLength = 2 + 1 + length;
		int identitiesLength = 2 + 2 + identity.length + 4;
		int hashedLength = switch (scope) {
			case TRUNCATED -> probe.length - bindersLength;
			case FULL_MESSAGE -> probe.length;
			case BEFORE_IDENTITIES -> probe.length - bindersLength - identitiesLength;
		};

		TlsKeySchedule binderSchedule = TlsKeySchedule.startWithPsk(ticket.cipherSuite(), psk);
		byte[] binderKey = binderSchedule.resumptionBinderKey();
		byte[] binder = binderSchedule.pskBinder(binderKey, hashPrefix(ticket.cipherSuite(), probe, hashedLength));
		if (binder.length != length) {
			binder = Arrays.copyOf(binder, length);
		}
		if (corruptBinder) {
			binder[binder.length - 1] ^= 0x01;
		}

		offeredPsk = psk;
		byte[] bytes = serializeWithBinder(earlierExtensions, pskIdentity, binder);
		assertEquals("both serialization passes must have the same length", probe.length, bytes.length);
		transcript.update(bytes);
		return bytes;
	}

	private byte[] serializeWithBinder(List<TlsExtension> earlierExtensions,
			PreSharedKeyExt.PskIdentity identity, byte[] binder) {
		List<TlsExtension> extensions = new ArrayList<>(earlierExtensions);
		extensions.add(PreSharedKeyExt.ofClientOffer(List.of(identity), List.of(binder)));
		byte[] random = new byte[32];
		Arrays.fill(random, (byte) 0x5A);
		return serialize(new ClientHelloMessage(
			ClientHelloMessage.LEGACY_VERSION, random, sessionId,
			offeredCipherSuites.clone(), new int[] {0}, extensions));
	}

	/**
	 * Parses and validates the server flight, deriving every secret independently. A flight without
	 * Certificate/CertificateVerify is a PSK-authenticated one (RFC 8446 §4.4.2) and is only accepted
	 * when the ServerHello echoed {@code pre_shared_key}.
	 */
	void acceptServerFlight(TlsEngineResult serverFlight, @Nullable X509Certificate identityLeaf) throws Exception {
		ByteBuf serverHelloBuf = serverFlight.cryptoToSend().get(EncryptionLevel.INITIAL);
		assertNotNull("ServerHello must be emitted at the INITIAL level", serverHelloBuf);
		byte[] serverHelloBytes = readBytes(serverHelloBuf);
		ParsedMessage parsed = parseOne(serverHelloBytes, 0);
		assertEquals(serverHelloBytes.length, parsed.nextOffset());
		ServerHelloMessage serverHello = (ServerHelloMessage) parsed.message();

		suite = serverHello.knownCipherSuite();
		assertNotNull(suite);
		PreSharedKeyExt selected = find(serverHello.extensions, PreSharedKeyExt.class);
		serverAcceptedPsk = selected != null;
		KeyShareExt keyShare = find(serverHello.extensions, KeyShareExt.class);
		assertNotNull(keyShare);
		assertNotNull(keyShare.selectedShare);

		transcript.bindCipherSuite(suite);
		transcript.update(serverHelloBytes);

		byte[] sharedSecret = TlsKeyExchanges.agree(
			NamedGroup.X25519, keyPair.getPrivate(), keyShare.selectedShare.keyExchange());
		schedule = serverAcceptedPsk
			? TlsKeySchedule.startWithPsk(suite, offeredPsk)
			: TlsKeySchedule.start(suite);
		schedule.mixEcdhe(sharedSecret);
		byte[] clientHelloServerHelloHash = transcript.hash();
		clientHandshakeTraffic = schedule.clientHandshakeTrafficSecret(clientHelloServerHelloHash);
		serverHandshakeTraffic = schedule.serverHandshakeTrafficSecret(clientHelloServerHelloHash);

		ByteBuf flightBuf = serverFlight.cryptoToSend().get(EncryptionLevel.HANDSHAKE);
		assertNotNull("EE+…+Fin must be emitted at the HANDSHAKE level", flightBuf);
		byte[] flightBytes = readBytes(flightBuf);

		ParsedMessage ee = parseOne(flightBytes, 0);
		assertTrue(ee.message() instanceof EncryptedExtensionsMessage);
		transcript.update(ee.bytes());

		int offset = ee.nextOffset();
		ParsedMessage next = parseOne(flightBytes, offset);
		if (next.message() instanceof CertificateMessage certificate) {
			assertFalse("a PSK-authenticated server sends no Certificate (RFC 8446 §4.4.2)", serverAcceptedPsk);
			transcript.update(next.bytes());
			assertNotNull(identityLeaf);
			assertArrayEquals(identityLeaf.getEncoded(), certificate.entries.get(0).certificateBytes());

			ParsedMessage cv = parseOne(flightBytes, next.nextOffset());
			CertificateVerifyMessage certificateVerify = (CertificateVerifyMessage) cv.message();
			SignatureScheme scheme = certificateVerify.knownScheme();
			assertNotNull(scheme);
			assertTrue("CertificateVerify must verify against the presented chain",
				TlsSignatures.verify(scheme, identityLeaf.getPublicKey(),
					TlsSignatures.certificateVerifyContent(true, transcript.hash()), certificateVerify.signature()));
			transcript.update(cv.bytes());
			offset = cv.nextOffset();
		} else {
			assertTrue("only a PSK-authenticated flight may omit Certificate", serverAcceptedPsk);
			offset = ee.nextOffset();
		}

		ParsedMessage fin = parseOne(flightBytes, offset);
		assertEquals("the HANDSHAKE flight holds nothing past Finished", flightBytes.length, fin.nextOffset());
		FinishedMessage serverFinished = (FinishedMessage) fin.message();
		assertArrayEquals("server Finished verify_data",
			schedule.verifyData(schedule.finishedKey(serverHandshakeTraffic), transcript.hash()),
			serverFinished.verifyData());
		transcript.update(fin.bytes());

		serverFinishedTranscriptHash = transcript.hash();
		schedule.deriveMasterSecret();
		clientApplicationTraffic = schedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
		serverApplicationTraffic = schedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);
	}

	/** The client Finished, fed into the transcript so {@link #resumptionMasterSecret()} is computable. */
	byte[] clientFinished() {
		byte[] verifyData = schedule.verifyData(
			schedule.finishedKey(clientHandshakeTraffic), serverFinishedTranscriptHash);
		byte[] bytes = serialize(new FinishedMessage(verifyData));
		transcript.update(bytes);
		return bytes;
	}

	/** {@code res_master} over CH..client Finished — the base of every ticket the server just issued. */
	byte[] resumptionMasterSecret() {
		return schedule.resumptionMasterSecret(transcript.hash());
	}

	/**
	 * The suite's hash over the first {@code length} bytes of {@code bytes}. Deliberately not
	 * {@code TlsPskBinders.truncatedClientHelloHash}, which only expresses the one <i>correct</i>
	 * truncation — proving the server rejects the wrong ones needs arbitrary prefixes.
	 */
	private static byte[] hashPrefix(TlsCipherSuite suite, byte[] bytes, int length) {
		try {
			MessageDigest digest = MessageDigest.getInstance(suite.hashAlgorithm());
			digest.update(bytes, 0, length);
			return digest.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	// ---- shared static helpers ----

	static ByteBuf wrap(byte[] bytes) {
		return ByteBuf.wrapForReading(bytes);
	}

	static void recycleOutput(TlsEngineResult result) {
		for (ByteBuf buf : result.cryptoToSend().values()) {
			buf.recycle();
		}
	}

	static byte[] readBytes(ByteBuf buf) {
		byte[] bytes = new byte[buf.readRemaining()];
		buf.read(bytes);
		return bytes;
	}

	static byte[] serialize(TlsHandshakeMessage message) {
		ByteBuf buf = ByteBufPool.allocate(message.encodedLength());
		message.writeTo(buf);
		return buf.asArray(); // asArray recycles the buffer
	}

	static @Nullable <T extends TlsExtension> T find(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}

	static List<TlsExtension> without(List<TlsExtension> extensions, Class<? extends TlsExtension> type) {
		List<TlsExtension> result = new ArrayList<>();
		for (TlsExtension extension : extensions) {
			if (!type.isInstance(extension)) {
				result.add(extension);
			}
		}
		return result;
	}

	static ParsedMessage parseOne(byte[] data, int offset) throws Exception {
		int bodyLength = ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
		int total = 4 + bodyLength;
		byte[] messageBytes = Arrays.copyOfRange(data, offset, offset + total);
		return new ParsedMessage(TlsMessages.read(ByteBuf.wrapForReading(messageBytes)), messageBytes, offset + total);
	}

	static List<TlsHandshakeMessage> parseAll(byte[] data) throws Exception {
		List<TlsHandshakeMessage> messages = new ArrayList<>();
		int offset = 0;
		while (offset < data.length) {
			ParsedMessage parsed = parseOne(data, offset);
			messages.add(parsed.message());
			offset = parsed.nextOffset();
		}
		return messages;
	}

	record ParsedMessage(TlsHandshakeMessage message, byte[] bytes, int nextOffset) {
	}
}
