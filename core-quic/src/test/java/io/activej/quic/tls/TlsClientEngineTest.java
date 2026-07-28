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
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.List;

import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * The US4 client engine (spec §User Story 4): a scripted server — {@link ScriptedTlsServer},
 * built from the US1 message types and an independently driven {@link TlsKeySchedule}, mirroring
 * TlsServerEngineTest's scripted client — drives a full handshake against the client engine and
 * every client-side alert path of the spec's Error Scenarios table: certificate-chain rejection
 * mapped per cause, RFC 6125 endpoint identification (incl. wildcard rules), the explicit
 * insecure trust-all mode (FR-011), tampered CertificateVerify/Finished ({@code decrypt_error}),
 * HelloRetryRequest (FR-014), ServerHello mismatch validation, post-handshake NewSessionTicket
 * discard (FR-015) and KeyUpdate rejection (RFC 9001 §6).
 */
public class TlsClientEngineTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	static final QuicTransportParameters CLIENT_PARAMS = QuicTransportParameters.defaults(new byte[] {10, 11, 12, 13});

	// ---- the full scripted handshake (acceptance scenario 1) ----

	@Test
	public void fullHandshakeCompletesWithFinishedAndInstalledKeys() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		byte[] clientHelloBytes = emitClientHello(client);
		server.acceptClientHello(clientHelloBytes);

		// the scripted server saw the client's SNI and transport parameters
		assertEquals("example.test", server.clientServerName);
		assertEquals(CLIENT_PARAMS, server.clientTransportParameters);

		TlsEngineResult handshakeKeysResult = client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		try {
			assertFalse(handshakeKeysResult.handshakeComplete());
			assertTrue(handshakeKeysResult.cryptoToSend().isEmpty());
			assertEquals(1, handshakeKeysResult.keysToInstall().size());
			KeyInstallation handshakeKeys = handshakeKeysResult.keysToInstall().get(0);
			assertEquals(EncryptionLevel.HANDSHAKE, handshakeKeys.level());
			assertKeys(server.suite, server.clientHandshakeTraffic, handshakeKeys.keys().clientKeys());
			assertKeys(server.suite, server.serverHandshakeTraffic, handshakeKeys.keys().serverKeys());
		} finally {
			recycleOutput(handshakeKeysResult);
		}

		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
			assertEquals(ScriptedTlsServer.ALPN_H3, completion.negotiatedAlpn());
			assertEquals(SERVER_PARAMS, completion.peerTransportParameters());

			// exactly the 1-RTT installation fires with completion — Handshake keys came earlier
			assertEquals(1, completion.keysToInstall().size());
			KeyInstallation oneRttKeys = completion.keysToInstall().get(0);
			assertEquals(EncryptionLevel.ONE_RTT, oneRttKeys.level());
			assertKeys(server.suite, server.clientApplicationTraffic, oneRttKeys.keys().clientKeys());
			assertKeys(server.suite, server.serverApplicationTraffic, oneRttKeys.keys().serverKeys());

			// the client Finished is emitted at the HANDSHAKE level and verifies
			ByteBuf clientFinishedBuf = completion.cryptoToSend().get(EncryptionLevel.HANDSHAKE);
			assertNotNull("the client Finished is emitted on the HANDSHAKE-level CRYPTO stream", clientFinishedBuf);
			server.assertClientFinished(readBytes(clientFinishedBuf));
		} finally {
			recycleOutput(completion);
		}
	}

	// ---- certificate validation (acceptance scenarios 3, 4, 5) ----

	@Test
	public void expiredChainAbortsWithCertificateExpired() throws Exception {
		TlsAlertException e = expectAlertAtCertificate(rejectingWith(new CertificateExpiredException("notAfter is in the past")));
		assertEquals(TlsAlerts.CERTIFICATE_EXPIRED, e.alertCode());
	}

	@Test
	public void untrustedChainAbortsWithUnknownCa() throws Exception {
		CertificateException rejection = new CertificateException("PKIX path building failed",
			new CertPathBuilderException("unable to find valid certification path to requested target"));
		TlsAlertException e = expectAlertAtCertificate(rejectingWith(rejection));
		assertEquals(TlsAlerts.UNKNOWN_CA, e.alertCode());
	}

	@Test
	public void genericallyRejectedChainAbortsWithBadCertificate() throws Exception {
		TlsAlertException e = expectAlertAtCertificate(rejectingWith(new CertificateException("signature check failed")));
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	@Test
	public void validChainWithSanMismatchFailsEndpointIdentification() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		// the fixture chain covers localhost + example.test — "other.test" is not covered
		TlsEngine client = newClient("other.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> {
			client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		});
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	@Test
	public void wildcardSanMatchingFollowsRfc6125() {
		assertTrue(TlsEndpointIdentification.matches("example.test", "example.test"));
		assertTrue(TlsEndpointIdentification.matches("EXAMPLE.test", "example.TEST"));
		assertTrue(TlsEndpointIdentification.matches("localhost", "localhost"));
		assertTrue(TlsEndpointIdentification.matches("*.example.test", "foo.example.test"));
		assertFalse("a wildcard covers exactly one left-most label",
			TlsEndpointIdentification.matches("*.example.test", "a.b.example.test"));
		assertFalse("a wildcard does not match the bare parent",
			TlsEndpointIdentification.matches("*.example.test", "example.test"));
		assertFalse("partial wildcards are not matched (RFC 6125 §6.4.3)",
			TlsEndpointIdentification.matches("foo*.example.test", "foo1.example.test"));
		assertFalse("a wildcard never spans a bare public suffix (RFC 6125 §6.4.3)",
			TlsEndpointIdentification.matches("*.com", "example.com"));
		assertFalse(TlsEndpointIdentification.matches("*.co.uk", "co.uk"));
		assertFalse(TlsEndpointIdentification.matches("*.example.test", "other.test"));
		assertFalse(TlsEndpointIdentification.matches("example.test", "example.test.evil"));
	}

	@Test
	public void serverFlightDribbledInSingleByteFragmentsCompletes() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		// every level's CRYPTO stream arrives one byte per consume call — reassembly must
		// reassemble split messages (and stay linear, never quadratic, while doing so)
		for (byte b : server.serverHelloBytes) {
			recycleOutput(client.consume(EncryptionLevel.INITIAL, wrap(new byte[] {b})));
		}
		byte[] flight = server.handshakeFlight(false, false);
		TlsEngineResult completion = null;
		for (int i = 0; i < flight.length; i++) {
			TlsEngineResult result = client.consume(EncryptionLevel.HANDSHAKE, wrap(new byte[] {flight[i]}));
			if (i == flight.length - 1) {
				completion = result;
			} else {
				recycleOutput(result);
			}
		}
		assertNotNull(completion);
		try {
			assertTrue(completion.handshakeComplete());
			assertEquals(ScriptedTlsServer.ALPN_H3, completion.negotiatedAlpn());
			assertEquals(1, completion.keysToInstall().size());
			KeyInstallation oneRttKeys = completion.keysToInstall().get(0);
			assertEquals(EncryptionLevel.ONE_RTT, oneRttKeys.level());
			assertKeys(server.suite, server.clientApplicationTraffic, oneRttKeys.keys().clientKeys());
			assertKeys(server.suite, server.serverApplicationTraffic, oneRttKeys.keys().serverKeys());
		} finally {
			recycleOutput(completion);
		}
	}

	@Test
	public void insecureTrustAllSkipsValidationAndIdentification() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		// self-signed fixture chain + a SAN that does not cover the remote name — both skipped
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("untrusted.invalid", CLIENT_PARAMS)
			.insecureTrustAll()
			.build());

		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
		} finally {
			recycleOutput(completion);
		}
	}

	// ---- CertificateVerify / Finished integrity (acceptance scenario 7) ----

	@Test
	public void tamperedCertificateVerifyAbortsWithDecryptError() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(true, false))));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
	}

	@Test
	public void tamperedServerFinishedAbortsWithDecryptError() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, true))));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
	}

	// ---- ServerHello validation (acceptance scenario 6; data-model validation table) ----

	@Test
	public void helloRetryRequestAbortsWithDedicatedException() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		server.serverRandomOverride = ServerHelloMessage.HELLO_RETRY_REQUEST_RANDOM;
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		TlsHelloRetryRequestException e = assertThrows(TlsHelloRetryRequestException.class, () ->
			client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertTrue(e.getMessage().contains("HelloRetryRequest"));
	}

	@Test
	public void unknownSelectedCipherSuiteAbortsWithIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		server.cipherSuiteOverride = 0x00ff;
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void selectedGroupWithoutOfferedShareAbortsWithIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		// the client offers an x25519 share only; the server selects secp256r1 instead
		byte[] p256Point = new byte[65];
		p256Point[0] = 0x04;
		server.keyShareOverride = new KeyShareEntry(NamedGroup.SECP256R1.code(), p256Point);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void sessionIdEchoMismatchAbortsWithIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		server.sessionIdEchoOverride = new byte[] {1, 2, 3}; // the client sent an empty legacy session id
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));

		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	// ---- post-handshake (FR-015, RFC 9001 §6) ----

	@Test
	public void postHandshakeNewSessionTicketIsParsedAndDiscarded() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));
		completeHandshake(client, server);

		byte[] ticket = ScriptedTlsServer.serialize(
			new NewSessionTicketMessage(3600, 12345, new byte[] {1}, new byte[] {9, 9, 9}, List.of()));
		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT, wrap(ticket));
		// FR-015: tolerated and discarded — no output, no keys, no resumption state, engine alive
		assertFalse(result.handshakeComplete());
		assertTrue(result.cryptoToSend().isEmpty());
		assertTrue(result.keysToInstall().isEmpty());
		assertNull(result.negotiatedAlpn());

		TlsEngineResult secondTicket = client.consume(EncryptionLevel.ONE_RTT, wrap(ticket));
		assertFalse(secondTicket.handshakeComplete());
	}

	@Test
	public void tlsKeyUpdateMessageAbortsWithUnexpectedMessage() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf()));
		completeHandshake(client, server);

		// KeyUpdate: handshake type 24, one-byte body (update_not_requested) — forbidden in QUIC (RFC 9001 §6)
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.ONE_RTT, wrap(new byte[] {24, 0, 0, 1, 0})));
		assertEquals(TlsAlerts.UNEXPECTED_MESSAGE, e.alertCode());
	}

	// ---- helpers ----

	private TlsAlertException expectAlertAtCertificate(X509TrustManager rejectingTrustManager) throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", rejectingTrustManager);

		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		return expectAlert(() -> client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
	}

	private void completeHandshake(TlsEngine client, ScriptedTlsServer server) throws Exception {
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
		} finally {
			recycleOutput(completion);
		}
	}

	static byte[] emitClientHello(TlsEngine client) throws Exception {
		TlsEngineResult result = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		ByteBuf clientHelloBuf = result.cryptoToSend().get(EncryptionLevel.INITIAL);
		assertNotNull(clientHelloBuf);
		try {
			return readBytes(clientHelloBuf);
		} finally {
			clientHelloBuf.recycle();
		}
	}

	static TlsEngine newClient(String remoteName, X509TrustManager trustManager) {
		return QuicTls.clientEngine(TlsClientConfig.builder(remoteName, CLIENT_PARAMS)
			.withTrustManager(trustManager)
			.build());
	}

	static X509TrustManager trustingLeaf(X509Certificate leaf) {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				throw new CertificateException("Client authentication is not used");
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				if (chain.length == 0 || !chain[0].equals(leaf)) {
					throw new CertificateException("Untrusted server chain");
				}
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}

	private static X509TrustManager rejectingWith(CertificateException rejection) {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				throw new CertificateException("Client authentication is not used");
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				throw rejection;
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}

	static TlsServerIdentity rsaIdentity() throws Exception {
		return TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
	}

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

	static TlsAlertException expectAlert(ThrowingRunnable runnable) {
		return assertThrows(TlsAlertException.class, runnable::run);
	}

	interface ThrowingRunnable {
		void run() throws Exception;
	}

	static void assertKeys(TlsCipherSuite suite, byte[] trafficSecret, QuicKeys installed) {
		QuicKeys expected = QuicKeys.fromTrafficSecret(suite.quicCipherSuite(), trafficSecret);
		assertArrayEquals(expected.aeadKeyBytes(), installed.aeadKeyBytes());
		assertArrayEquals(expected.iv(), installed.iv());
		assertArrayEquals(expected.headerProtectionKey(), installed.headerProtectionKey());
	}
}
