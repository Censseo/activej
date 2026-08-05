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
import io.activej.quic.tls.CertificateMessage.CertificateEntry;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import org.jetbrains.annotations.Nullable;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A scripted TLS 1.3 server for client-engine tests: consumes the client engine's ClientHello,
 * emits the ServerHello plus the EncryptedExtensions/Certificate/CertificateVerify/Finished
 * flight, and re-derives every secret independently via {@link TlsKeySchedule} over the same
 * transcript — so an engine derivation bug cannot hide. The overrides inject the adversarial
 * ServerHello/flight forms of the spec's Error Scenarios table.
 */
final class ScriptedTlsServer {
	static final String ALPN_H3 = "h3";

	static final QuicTransportParameters SERVER_PARAMS = new QuicTransportParameters(
		new byte[] {1, 2, 3, 4}, 0, null, QuicTransportParameters.DEFAULT_MAX_UDP_PAYLOAD_SIZE,
		1 << 20, 1 << 16, 1 << 16, 1 << 16, 100, 100,
		QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
		false, null, QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT,
		new byte[] {5, 6, 7, 8}, null, 0);

	final TranscriptHash transcript = new TranscriptHash();
	final TlsServerIdentity identity;
	final KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
	final byte[] serverRandom = newSecureRandom();

	// adversarial overrides
	byte @Nullable [] serverRandomOverride;
	int shLegacyVersion = ServerHelloMessage.LEGACY_VERSION;
	int cipherSuiteOverride = -1;
	@Nullable KeyShareEntry keyShareOverride;
	byte @Nullable [] sessionIdEchoOverride;
	@Nullable List<TlsExtension> shExtensionsOverride;
	@Nullable List<TlsExtension> eeExtensionsOverride;
	@Nullable List<CertificateEntry> certificateEntriesOverride;

	// resumption script (feature 006): the PSK this server knows, and what it agrees to
	boolean acceptPsk;
	boolean acceptEarlyData;
	@Nullable TlsCipherSuite selectedSuiteOverride;
	byte @Nullable [] psk;
	TlsCipherSuite pskSuite = TlsCipherSuite.TLS_AES_128_GCM_SHA256;

	TlsCipherSuite suite;
	TlsKeySchedule schedule;
	byte[] serverHelloBytes;
	@Nullable String clientServerName;
	@Nullable QuicTransportParameters clientTransportParameters;
	byte[] clientHandshakeTraffic;
	byte[] serverHandshakeTraffic;
	byte[] clientApplicationTraffic;
	byte[] serverApplicationTraffic;
	byte[] serverFinishedTranscriptHash;

	// resumption observations
	@Nullable PreSharedKeyExt offeredPsk;
	@Nullable EarlyDataExt offeredEarlyData;
	@Nullable PskKeyExchangeModesExt offeredPskModes;
	boolean pskAccepted;
	boolean binderVerified;
	int offeredBindersSectionLength;
	byte @Nullable [] clientEarlyTraffic;
	byte @Nullable [] resumptionMasterSecret;

	ScriptedTlsServer(TlsServerIdentity identity) {
		this.identity = identity;
	}

	byte[] acceptClientHello(byte[] clientHelloBytes) throws Exception {
		ClientHelloMessage clientHello = (ClientHelloMessage) TlsMessages.read(ByteBuf.wrapForReading(clientHelloBytes));
		transcript.update(clientHelloBytes);

		ServerNameExt serverName = find(clientHello.extensions, ServerNameExt.class);
		clientServerName = serverName == null ? null : serverName.hostName;
		QuicTransportParametersExt transportParameters = find(clientHello.extensions, QuicTransportParametersExt.class);
		assertNotNull(transportParameters);
		clientTransportParameters = transportParameters.parameters;

		offeredPsk = find(clientHello.extensions, PreSharedKeyExt.class);
		offeredEarlyData = find(clientHello.extensions, EarlyDataExt.class);
		offeredPskModes = find(clientHello.extensions, PskKeyExchangeModesExt.class);
		verifyOfferedBinder(clientHelloBytes);

		int selectedCode = cipherSuiteOverride != -1
			? cipherSuiteOverride
			: selectedSuiteOverride != null ? selectedSuiteOverride.code() : clientHello.cipherSuites[0];
		suite = TlsCipherSuite.of(selectedCode);

		KeyShareEntry selectedShare = keyShareOverride;
		if (selectedShare == null) {
			KeyShareExt keyShare = find(clientHello.extensions, KeyShareExt.class);
			assertNotNull(keyShare);
			assertNotNull(keyShare.clientShares);
			selectedShare = keyShare.clientShares.get(0);
		}

		ServerHelloMessage serverHello = new ServerHelloMessage(
			shLegacyVersion,
			serverRandomOverride != null ? serverRandomOverride : serverRandom,
			sessionIdEchoOverride != null ? sessionIdEchoOverride : clientHello.legacySessionId,
			selectedCode, 0,
			shExtensionsOverride != null ? shExtensionsOverride : defaultServerHelloExtensions(selectedShare));
		serverHelloBytes = serialize(serverHello);

		if (suite != null) {
			transcript.bindCipherSuite(suite);
			transcript.update(serverHelloBytes);
			KeyShareExt clientKeyShare = find(clientHello.extensions, KeyShareExt.class);
			byte[] sharedSecret = TlsKeyExchanges.agree(
				NamedGroup.X25519, keyPair.getPrivate(), clientKeyShare.clientShares.get(0).keyExchange);
			if (schedule == null) {
				schedule = TlsKeySchedule.start(suite);
			}
			schedule.mixEcdhe(sharedSecret);
			byte[] clientHelloServerHelloHash = transcript.hash();
			clientHandshakeTraffic = schedule.clientHandshakeTrafficSecret(clientHelloServerHelloHash);
			serverHandshakeTraffic = schedule.serverHandshakeTrafficSecret(clientHelloServerHelloHash);
			schedule.deriveMasterSecret();
		}
		return serverHelloBytes;
	}

	private List<TlsExtension> defaultServerHelloExtensions(KeyShareEntry selectedShare) throws Exception {
		List<TlsExtension> extensions = new ArrayList<>();
		extensions.add(SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3));
		extensions.add(KeyShareExt.ofSelectedShare(new KeyShareEntry(
			selectedShare.groupCode,
			keyShareOverride != null
				? keyShareOverride.keyExchange
				: TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, keyPair.getPublic()))));
		if (pskAccepted) {
			extensions.add(PreSharedKeyExt.ofSelectedIdentity(0));
		}
		return extensions;
	}

	/**
	 * Re-derives the binder from scratch: the binders-section width is recomputed from the
	 * <b>parsed</b> extension rather than read back from the writer's own
	 * {@link PreSharedKeyExt#bindersSectionLength()}, and the truncated hash is taken on a bare
	 * {@link MessageDigest}, so an off-by-N in either the writer or {@code TlsPskBinders} shows up
	 * here instead of cancelling itself out.
	 */
	private void verifyOfferedBinder(byte[] clientHelloBytes) throws Exception {
		PreSharedKeyExt offer = offeredPsk;
		if (!acceptPsk || offer == null) return;
		assertNotNull("a scripted server that accepts a PSK must be given one", psk);
		assertNotNull(offer.binders);

		int bindersWidth = 2;
		for (byte[] binder : offer.binders) {
			bindersWidth += 1 + binder.length;
		}
		offeredBindersSectionLength = bindersWidth;

		MessageDigest digest = MessageDigest.getInstance(pskSuite.hashAlgorithm());
		digest.update(clientHelloBytes, 0, clientHelloBytes.length - bindersWidth);
		byte[] truncatedHash = digest.digest();

		schedule = TlsKeySchedule.startWithPsk(pskSuite, psk);
		byte[] binderKey = schedule.resumptionBinderKey();
		byte[] expectedBinder = schedule.pskBinder(binderKey, truncatedHash);
		binderVerified = MessageDigest.isEqual(expectedBinder, offer.binders.get(0));
		assertTrue("the offered PSK binder verifies against an independent derivation", binderVerified);

		clientEarlyTraffic = schedule.clientEarlyTrafficSecret(
			MessageDigest.getInstance(pskSuite.hashAlgorithm()).digest(clientHelloBytes));
		pskAccepted = true;
	}

	byte[] handshakeFlight(boolean tamperCertificateVerify, boolean tamperFinished) throws Exception {
		List<TlsExtension> eeExtensions = eeExtensionsOverride != null
			? eeExtensionsOverride
			: defaultEncryptedExtensions();
		byte[] eeBytes = serialize(new EncryptedExtensionsMessage(eeExtensions));
		transcript.update(eeBytes);

		List<byte[]> parts = new ArrayList<>();
		parts.add(eeBytes);
		// RFC 8446 §4.4.2: a PSK-authenticated server sends neither Certificate nor CertificateVerify —
		// the pre-shared key is the authentication. Scripting them anyway would drive the client through
		// a flight no conforming server sends, which is exactly the thing this fixture must not do.
		if (!pskAccepted) {
			List<CertificateEntry> entries = certificateEntriesOverride;
			if (entries == null) {
				entries = new ArrayList<>();
				for (X509Certificate certificate : identity.chain()) {
					entries.add(new CertificateEntry(certificate.getEncoded(), List.of()));
				}
			}
			byte[] certificateBytes = serialize(new CertificateMessage(new byte[0], entries));
			transcript.update(certificateBytes);
			parts.add(certificateBytes);

			SignatureScheme scheme = identity.signatureSchemes().get(0);
			byte[] signature = TlsSignatures.sign(scheme, identity.privateKey(),
				TlsSignatures.certificateVerifyContent(true, transcript.hash()));
			if (tamperCertificateVerify) {
				signature[signature.length - 1] ^= 0x01;
			}
			byte[] certificateVerifyBytes = serialize(new CertificateVerifyMessage(scheme.code(), signature));
			transcript.update(certificateVerifyBytes);
			parts.add(certificateVerifyBytes);
		}

		byte[] verifyData = schedule.verifyData(schedule.finishedKey(serverHandshakeTraffic), transcript.hash());
		if (tamperFinished) {
			verifyData[verifyData.length - 1] ^= 0x01;
		}
		byte[] finishedBytes = serialize(new FinishedMessage(verifyData));
		if (!tamperFinished) {
			transcript.update(finishedBytes);
			serverFinishedTranscriptHash = transcript.hash();
			clientApplicationTraffic = schedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
			serverApplicationTraffic = schedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);
		}
		parts.add(finishedBytes);

		int total = 0;
		for (byte[] part : parts) {
			total += part.length;
		}
		byte[] flight = new byte[total];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, flight, offset, part.length);
			offset += part.length;
		}
		return flight;
	}

	private List<TlsExtension> defaultEncryptedExtensions() {
		List<TlsExtension> extensions = new ArrayList<>();
		extensions.add(new AlpnExt(List.of(ALPN_H3)));
		extensions.add(new QuicTransportParametersExt(SERVER_PARAMS));
		if (acceptEarlyData) {
			// RFC 8446 §4.2.10: echoing early_data is the acceptance; omitting it is the refusal
			extensions.add(EarlyDataExt.empty());
		}
		return extensions;
	}

	void assertClientFinished(byte[] clientFinishedBytes) throws Exception {
		FinishedMessage finished = (FinishedMessage) TlsMessages.read(ByteBuf.wrapForReading(clientFinishedBytes));
		byte[] expectedVerifyData = schedule.verifyData(
			schedule.finishedKey(clientHandshakeTraffic), serverFinishedTranscriptHash);
		assertTrue("client Finished verify_data",
			MessageDigest.isEqual(expectedVerifyData, finished.verifyData));
		transcript.update(clientFinishedBytes);
		resumptionMasterSecret = schedule.resumptionMasterSecret(transcript.hash());
	}

	/** A post-handshake {@code NewSessionTicket} (RFC 8446 §4.6.1), serialized ready to feed the client. */
	byte[] newSessionTicket(long lifetimeSeconds, long ticketAgeAdd, byte[] nonce, byte[] ticketBytes,
			List<TlsExtension> extensions) {
		return serialize(new NewSessionTicketMessage(lifetimeSeconds, ticketAgeAdd, nonce, ticketBytes, extensions));
	}

	/** The PSK a ticket with this nonce names (RFC 8446 §4.6.1) — the client must derive the same one. */
	byte[] resumptionPskFor(byte[] nonce) {
		assertNotNull("assertClientFinished must run before a ticket can be issued", resumptionMasterSecret);
		return TlsKeySchedule.resumptionPsk(suite, resumptionMasterSecret, nonce);
	}

	static byte[] serialize(TlsHandshakeMessage message) {
		ByteBuf buf = ByteBufPool.allocate(message.encodedLength());
		message.writeTo(buf);
		return buf.asArray(); // asArray recycles the buffer
	}

	static <T extends TlsExtension> @Nullable T find(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}

	private static byte[] newSecureRandom() {
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		return random;
	}
}
