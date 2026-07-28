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
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.CertificateMessage.CertificateEntry;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * The US3 server engine (spec §User Story 3): a scripted client drives a full handshake —
 * ClientHello → server flight (ServerHello at the INITIAL level, EncryptedExtensions,
 * Certificate, CertificateVerify, Finished at the HANDSHAKE level) → client Finished —
 * asserting the data-model level mapping, key-installation ordering (Handshake before 1-RTT),
 * completion surface (ALPN {@code h3}, peer transport parameters) and every alert path of the
 * spec's Error Scenarios table. The scripted client re-derives every secret independently via
 * {@link TlsKeySchedule} over the same transcript, so an engine derivation bug cannot hide.
 */
public class TlsServerEngineTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final String ALPN_H3 = "h3";

	private static final QuicTransportParameters CLIENT_PARAMS = QuicTransportParameters.defaults(new byte[] {10, 11, 12, 13});

	private static final QuicTransportParameters SERVER_PARAMS = new QuicTransportParameters(
		new byte[] {1, 2, 3, 4}, 0, null, QuicTransportParameters.DEFAULT_MAX_UDP_PAYLOAD_SIZE,
		1 << 20, 1 << 16, 1 << 16, 1 << 16, 100, 100,
		QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
		false, null, QuicTransportParameters.DEFAULT_ACTIVE_CONNECTION_ID_LIMIT,
		new byte[] {5, 6, 7, 8}, null);

	// ---- the full scripted handshake (acceptance scenarios 1 + 2) ----

	@Test
	public void fullHandshakeInstallsHandshakeKeysBeforeOneRttAndCompletes() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = newServerEngine(identity);
		ScriptedClient client = new ScriptedClient();

		TlsEngineResult serverFlight = engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
		try {
			assertFalse(serverFlight.handshakeComplete());
			assertNull(serverFlight.negotiatedAlpn());
			assertNull(serverFlight.peerTransportParameters());

			// exactly the Handshake-level installation fires with the flight — never 1-RTT early
			assertEquals(1, serverFlight.keysToInstall().size());
			KeyInstallation handshakeKeys = serverFlight.keysToInstall().get(0);
			assertEquals(EncryptionLevel.HANDSHAKE, handshakeKeys.level());

			client.acceptServerFlight(serverFlight, identity.leaf());
			assertKeys(client.suite, client.clientHandshakeTraffic, handshakeKeys.keys().clientKeys());
			assertKeys(client.suite, client.serverHandshakeTraffic, handshakeKeys.keys().serverKeys());
		} finally {
			recycleOutput(serverFlight);
		}

		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished(false)));
		try {
			assertTrue(completion.handshakeComplete());
			assertEquals(ALPN_H3, completion.negotiatedAlpn());
			assertEquals(CLIENT_PARAMS, completion.peerTransportParameters());
			assertTrue(completion.cryptoToSend().isEmpty());

			assertEquals(1, completion.keysToInstall().size());
			KeyInstallation oneRttKeys = completion.keysToInstall().get(0);
			assertEquals(EncryptionLevel.ONE_RTT, oneRttKeys.level());
			assertKeys(client.suite, client.clientApplicationTraffic, oneRttKeys.keys().clientKeys());
			assertKeys(client.suite, client.serverApplicationTraffic, oneRttKeys.keys().serverKeys());
		} finally {
			recycleOutput(completion);
		}
	}

	// ---- alert paths (acceptance scenarios 3, 4, 6; error scenarios table) ----

	@Test
	public void clientHelloDribbledInSingleByteFragmentsCompletes() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = newServerEngine(identity);
		ScriptedClient client = new ScriptedClient();

		// the ClientHello arrives one byte per consume call — reassembly must reassemble the
		// split message (and stay linear, never quadratic, while doing so)
		byte[] clientHelloBytes = client.clientHello(client.defaultExtensions());
		TlsEngineResult serverFlight = null;
		for (int i = 0; i < clientHelloBytes.length; i++) {
			TlsEngineResult result = engine.consume(EncryptionLevel.INITIAL, wrap(new byte[] {clientHelloBytes[i]}));
			if (i == clientHelloBytes.length - 1) {
				serverFlight = result;
			} else {
				recycleOutput(result);
			}
		}
		assertNotNull(serverFlight);
		try {
			assertFalse(serverFlight.handshakeComplete());
			assertEquals(1, serverFlight.keysToInstall().size());
			client.acceptServerFlight(serverFlight, identity.leaf());
		} finally {
			recycleOutput(serverFlight);
		}
		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished(false)));
		try {
			assertTrue(completion.handshakeComplete());
		} finally {
			recycleOutput(completion);
		}
	}

	@Test
	public void noH3OverlapAbortsWithNoApplicationProtocol() throws Exception {
		TlsEngine engine = newServerEngine(rsaIdentity());
		ScriptedClient client = new ScriptedClient();
		List<TlsExtension> extensions = without(client.defaultExtensions(), AlpnExt.class);
		extensions.add(new AlpnExt(List.of("h2", "hq-interop")));

		TlsAlertException e = expectAlert(() -> engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(extensions))));
		assertEquals(TlsAlerts.NO_APPLICATION_PROTOCOL, e.alertCode());
	}

	@Test
	public void missingAlpnAbortsWithNoApplicationProtocol() throws Exception {
		TlsEngine engine = newServerEngine(rsaIdentity());
		ScriptedClient client = new ScriptedClient();

		TlsAlertException e = expectAlert(() -> engine.consume(
			EncryptionLevel.INITIAL, wrap(client.clientHello(without(client.defaultExtensions(), AlpnExt.class)))));
		assertEquals(TlsAlerts.NO_APPLICATION_PROTOCOL, e.alertCode());
	}

	@Test
	public void missingQuicTransportParametersAbortsWithMissingExtension() throws Exception {
		TlsEngine engine = newServerEngine(rsaIdentity());
		ScriptedClient client = new ScriptedClient();

		TlsAlertException e = expectAlert(() -> engine.consume(
			EncryptionLevel.INITIAL, wrap(client.clientHello(without(client.defaultExtensions(), QuicTransportParametersExt.class)))));
		assertEquals(TlsAlerts.MISSING_EXTENSION, e.alertCode());
	}

	@Test
	public void noUsableKeyShareAbortsWithHandshakeFailureAndNeverSendsHrr() throws Exception {
		TlsEngine engine = newServerEngine(rsaIdentity());
		ScriptedClient client = new ScriptedClient();
		List<TlsExtension> extensions = without(client.defaultExtensions(), KeyShareExt.class);
		// a GREASE group and a group outside the profile (secp521r1, advertised consistently in
		// supported_groups per RFC 8446 §4.2.8) — nothing the server can use
		extensions.add(KeyShareExt.ofClientShares(List.of(
			new KeyShareEntry(0x0a0a, new byte[32]),
			new KeyShareEntry(0x0019, new byte[133]))));
		List<TlsExtension> advertised = without(extensions, SupportedGroupsExt.class);
		advertised.add(new SupportedGroupsExt(new int[] {NamedGroup.X25519.code(), NamedGroup.SECP256R1.code(), 0x0019}));

		TlsAlertException e = expectAlert(() -> engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(advertised))));
		assertEquals(TlsAlerts.HANDSHAKE_FAILURE, e.alertCode());
	}

	@Test
	public void wrongVerifyDataAbortsWithDecryptErrorAndInstallsNoOneRttKeys() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = newServerEngine(identity);
		ScriptedClient client = new ScriptedClient();

		TlsEngineResult serverFlight = engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
		try {
			client.acceptServerFlight(serverFlight, identity.leaf());
		} finally {
			recycleOutput(serverFlight);
		}

		TlsAlertException e = expectAlert(() -> engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished(true))));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
	}

	@Test
	public void unsupportedTlsVersionAbortsWithProtocolVersion() throws Exception {
		TlsEngine engine = newServerEngine(rsaIdentity());
		ScriptedClient client = new ScriptedClient();
		List<TlsExtension> extensions = without(client.defaultExtensions(), SupportedVersionsExt.class);
		extensions.add(SupportedVersionsExt.ofClientVersions(0x0303));

		TlsAlertException e = expectAlert(() -> engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(extensions))));
		assertEquals(TlsAlerts.PROTOCOL_VERSION, e.alertCode());
	}

	@Test
	public void ecdsaAndEd25519IdentitiesCompleteTheHandshake() throws Exception {
		for (String keyType : new String[] {"ecdsa", "ed25519"}) {
			TlsServerIdentity identity = TlsServerIdentity.fromPem(
				fixture(keyType + "-cert.pem"), fixture(keyType + "-key.pem"));
			TlsEngine engine = newServerEngine(identity);
			ScriptedClient client = new ScriptedClient();

			TlsEngineResult serverFlight = engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
			try {
				client.acceptServerFlight(serverFlight, identity.leaf());
			} finally {
				recycleOutput(serverFlight);
			}
			TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished(false)));
			try {
				assertTrue(completion.handshakeComplete());
			} finally {
				recycleOutput(completion);
			}
		}
	}

	// ---- scripted client (independent re-derivation of every secret) ----

	private static TlsServerIdentity rsaIdentity() throws Exception {
		return TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
	}

	private static TlsEngine newServerEngine(TlsServerIdentity identity) {
		return QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS)
			.withSecureRandom(new SecureRandom())
			.build());
	}

	private static ByteBuf wrap(byte[] bytes) {
		return ByteBuf.wrapForReading(bytes);
	}

	private static void recycleOutput(TlsEngineResult result) {
		for (ByteBuf buf : result.cryptoToSend().values()) {
			buf.recycle();
		}
	}

	private static byte[] readBytes(ByteBuf buf) {
		byte[] bytes = new byte[buf.readRemaining()];
		buf.read(bytes);
		return bytes;
	}

	private static TlsAlertException expectAlert(ThrowingRunnable runnable) {
		return assertThrows(TlsAlertException.class, runnable::run);
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private static List<TlsExtension> without(List<TlsExtension> extensions, Class<? extends TlsExtension> type) {
		List<TlsExtension> result = new ArrayList<>();
		for (TlsExtension extension : extensions) {
			if (!type.isInstance(extension)) {
				result.add(extension);
			}
		}
		return result;
	}

	private static <T extends TlsExtension> T find(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}

	private static void assertKeys(TlsCipherSuite suite, byte[] trafficSecret, QuicKeys installed) {
		QuicKeys expected = QuicKeys.fromTrafficSecret(suite.quicCipherSuite(), trafficSecret);
		assertArrayEquals(expected.aeadKeyBytes(), installed.aeadKeyBytes());
		assertArrayEquals(expected.iv(), installed.iv());
		assertArrayEquals(expected.headerProtectionKey(), installed.headerProtectionKey());
	}

	private record ParsedMessage(TlsHandshakeMessage message, byte[] bytes, int nextOffset) {
	}

	private static ParsedMessage parseOne(byte[] data, int offset) throws Exception {
		int bodyLength = ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
		int total = 4 + bodyLength;
		byte[] messageBytes = Arrays.copyOfRange(data, offset, offset + total);
		return new ParsedMessage(TlsMessages.read(ByteBuf.wrapForReading(messageBytes)), messageBytes, offset + total);
	}

	private static byte[] serialize(TlsHandshakeMessage message) {
		ByteBuf buf = ByteBufPool.allocate(message.encodedLength());
		message.writeTo(buf);
		return buf.asArray(); // asArray recycles the buffer
	}

	/**
	 * A scripted TLS 1.3 client: builds the ClientHello via the US1 message types with its own
	 * injected key share, then re-derives the whole key schedule from the observed wire bytes —
	 * asserting the server flight's structure, CertificateVerify signature and Finished
	 * {@code verify_data} against independently computed values.
	 */
	private static final class ScriptedClient {
		final TranscriptHash transcript = new TranscriptHash();
		final KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
		final byte[] sessionId = {21, 22, 23, 24};

		TlsCipherSuite suite;
		TlsKeySchedule schedule;
		byte[] clientHandshakeTraffic;
		byte[] serverHandshakeTraffic;
		byte[] clientApplicationTraffic;
		byte[] serverApplicationTraffic;
		byte[] serverFinishedTranscriptHash;

		List<TlsExtension> defaultExtensions() {
			byte[] publicKey = TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, keyPair.getPublic());
			return new ArrayList<>(List.of(
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
				new PskKeyExchangeModesExt(new int[] {1}),
				new QuicTransportParametersExt(CLIENT_PARAMS)));
		}

		byte[] clientHello(List<TlsExtension> extensions) {
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			ClientHelloMessage clientHello = new ClientHelloMessage(
				ClientHelloMessage.LEGACY_VERSION, random, sessionId,
				new int[] {TlsCipherSuite.TLS_AES_128_GCM_SHA256.code()},
				new int[] {0}, extensions);
			byte[] bytes = serialize(clientHello);
			transcript.update(bytes);
			return bytes;
		}

		void acceptServerFlight(TlsEngineResult serverFlight, X509Certificate identityLeaf) throws Exception {
			// --- ServerHello at the INITIAL level (data-model level mapping) ---
			ByteBuf serverHelloBuf = serverFlight.cryptoToSend().get(EncryptionLevel.INITIAL);
			assertNotNull("ServerHello must be emitted at the INITIAL level", serverHelloBuf);
			byte[] serverHelloBytes = readBytes(serverHelloBuf);
			ParsedMessage parsed = parseOne(serverHelloBytes, 0);
			assertEquals("INITIAL-level flight holds exactly the ServerHello", serverHelloBytes.length, parsed.nextOffset());
			ServerHelloMessage serverHello = (ServerHelloMessage) parsed.message();

			assertEquals(ServerHelloMessage.LEGACY_VERSION, serverHello.legacyVersion);
			assertArrayEquals(sessionId, serverHello.sessionIdEcho());
			suite = serverHello.knownCipherSuite();
			assertNotNull(suite);
			SupportedVersionsExt versions = find(serverHello.extensions, SupportedVersionsExt.class);
			assertNotNull(versions);
			assertTrue(versions.selectedForm);
			assertArrayEquals(new int[] {SupportedVersionsExt.TLS_1_3}, versions.versions());
			KeyShareExt keyShare = find(serverHello.extensions, KeyShareExt.class);
			assertNotNull(keyShare);
			assertNotNull(keyShare.selectedShare);
			assertEquals(NamedGroup.X25519.code(), keyShare.selectedShare.groupCode);

			transcript.bindCipherSuite(suite);
			transcript.update(serverHelloBytes);

			byte[] sharedSecret = TlsKeyExchanges.agree(
				NamedGroup.X25519, keyPair.getPrivate(), keyShare.selectedShare.keyExchange());
			schedule = TlsKeySchedule.start(suite);
			schedule.mixEcdhe(sharedSecret);
			byte[] clientHelloServerHelloHash = transcript.hash();
			clientHandshakeTraffic = schedule.clientHandshakeTrafficSecret(clientHelloServerHelloHash);
			serverHandshakeTraffic = schedule.serverHandshakeTrafficSecret(clientHelloServerHelloHash);

			// --- EE + Certificate + CertificateVerify + Finished at the HANDSHAKE level ---
			ByteBuf flightBuf = serverFlight.cryptoToSend().get(EncryptionLevel.HANDSHAKE);
			assertNotNull("EE+Cert+CV+Fin must be emitted at the HANDSHAKE level", flightBuf);
			byte[] flightBytes = readBytes(flightBuf);

			ParsedMessage ee = parseOne(flightBytes, 0);
			EncryptedExtensionsMessage encryptedExtensions = (EncryptedExtensionsMessage) ee.message();
			transcript.update(ee.bytes());
			AlpnExt alpn = find(encryptedExtensions.extensions, AlpnExt.class);
			assertNotNull(alpn);
			assertEquals(List.of(ALPN_H3), alpn.protocols);
			QuicTransportParametersExt transportParameters = find(encryptedExtensions.extensions, QuicTransportParametersExt.class);
			assertNotNull(transportParameters);
			assertEquals(SERVER_PARAMS, transportParameters.parameters);

			ParsedMessage cert = parseOne(flightBytes, ee.nextOffset());
			CertificateMessage certificate = (CertificateMessage) cert.message();
			transcript.update(cert.bytes());
			assertEquals(0, certificate.certificateRequestContext.length);
			assertEquals(1, certificate.entries.size());
			CertificateEntry entry = certificate.entries.get(0);
			assertArrayEquals(identityLeaf.getEncoded(), entry.certificateBytes());

			ParsedMessage cv = parseOne(flightBytes, cert.nextOffset());
			CertificateVerifyMessage certificateVerify = (CertificateVerifyMessage) cv.message();
			SignatureScheme scheme = certificateVerify.knownScheme();
			assertNotNull(scheme);
			byte[] content = TlsSignatures.certificateVerifyContent(true, transcript.hash());
			assertTrue("CertificateVerify must verify against the presented chain",
				TlsSignatures.verify(scheme, identityLeaf.getPublicKey(), content, certificateVerify.signature()));
			transcript.update(cv.bytes());

			ParsedMessage fin = parseOne(flightBytes, cv.nextOffset());
			assertEquals("HANDSHAKE-level flight holds exactly EE+Cert+CV+Fin", flightBytes.length, fin.nextOffset());
			FinishedMessage serverFinished = (FinishedMessage) fin.message();
			byte[] expectedVerifyData = schedule.verifyData(
				schedule.finishedKey(serverHandshakeTraffic), transcript.hash());
			assertArrayEquals("server Finished verify_data", expectedVerifyData, serverFinished.verifyData());
			transcript.update(fin.bytes());

			serverFinishedTranscriptHash = transcript.hash();
			schedule.deriveMasterSecret();
			clientApplicationTraffic = schedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
			serverApplicationTraffic = schedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);
		}

		byte[] clientFinished(boolean tamperVerifyData) {
			byte[] verifyData = schedule.verifyData(
				schedule.finishedKey(clientHandshakeTraffic), serverFinishedTranscriptHash);
			if (tamperVerifyData) {
				verifyData[verifyData.length - 1] ^= 0x01;
			}
			return serialize(new FinishedMessage(verifyData));
		}
	}
}
