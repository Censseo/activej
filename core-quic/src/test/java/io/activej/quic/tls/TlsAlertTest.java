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

import io.activej.common.MemSize;
import io.activej.quic.tls.CertificateMessage.CertificateEntry;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.CLIENT_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.emitClientHello;
import static io.activej.quic.tls.TlsClientEngineTest.newClient;
import static io.activej.quic.tls.TlsClientEngineTest.recycleOutput;
import static io.activej.quic.tls.TlsClientEngineTest.rsaIdentity;
import static io.activej.quic.tls.TlsClientEngineTest.trustingLeaf;
import static io.activej.quic.tls.TlsClientEngineTest.wrap;
import static org.junit.Assert.*;

/**
 * SC-004: the systematic sweep of the spec's Error Scenarios table — every row not already
 * covered by TlsServerEngineTest (T023) or TlsClientEngineTest (T027) is asserted here to map
 * to its exact exception type and RFC 8446 §6 alert code, with the engine's input buffer
 * recycled on every path ({@link ByteBufRule}).
 * <p>
 * Covered elsewhere (not repeated): no-{@code h3} ALPN overlap / missing ALPN at the server,
 * missing {@code quic_transport_parameters} in a ClientHello, no usable client key share,
 * client/server Finished {@code verify_data} mismatch, unsupported TLS version in a
 * ClientHello, certificate-chain rejection per cause, endpoint-identification mismatch,
 * trust-all completion, tampered CertificateVerify, HelloRetryRequest, ServerHello
 * suite/group/session-id-echo mismatch, NewSessionTicket discard, KeyUpdate rejection.
 */
public class TlsAlertTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	// ---- malformed wire bytes (both roles): decode_error, no allocation of declared sizes ----

	@Test
	public void truncatedClientHelloContentAbortsWithDecodeError() throws Exception {
		TlsEngine server = newServerEngine();
		// declared body 10 bytes, present 10 — but a ClientHello needs 2 + 32 before anything else
		byte[] malformed = new byte[4 + 10];
		malformed[0] = (byte) ClientHelloMessage.TYPE;
		malformed[3] = 10;
		TlsAlertException e = expectAlert(() -> server.consume(EncryptionLevel.INITIAL, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	@Test
	public void innerLengthExceedingRemainingAbortsWithDecodeErrorAndAllocatesNothing() throws Exception {
		TlsEngine server = newServerEngine();
		// a well-framed ClientHello whose legacy_session_id length (30) exceeds the remaining body (4)
		byte[] body = new byte[2 + 32 + 1 + 4];
		body[0] = 0x03;
		body[1] = 0x03;
		body[2 + 32] = 30;
		byte[] malformed = new byte[4 + body.length];
		malformed[0] = (byte) ClientHelloMessage.TYPE;
		malformed[2] = (byte) (body.length >>> 8);
		malformed[3] = (byte) body.length;
		System.arraycopy(body, 0, malformed, 4, body.length);
		TlsAlertException e = expectAlert(() -> server.consume(EncryptionLevel.INITIAL, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	@Test
	public void messageExceedingTheConfiguredBoundAbortsWithDecodeErrorNamingTheBound() throws Exception {
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS)
			.withMaxHandshakeMessageSize(MemSize.kilobytes(1))
			.build());
		byte[] oversized = new byte[4 + 16];
		oversized[0] = (byte) ClientHelloMessage.TYPE;
		oversized[2] = (byte) (2000 >>> 8); // declares 2000 body bytes against a 1024-byte bound
		oversized[3] = (byte) 2000;
		TlsAlertException e = expectAlert(() -> server.consume(EncryptionLevel.INITIAL, wrap(oversized)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
		assertTrue(e.getMessage(), e.getMessage().contains("maxHandshakeMessageSize"));
	}

	@Test
	public void truncatedServerHelloContentAbortsWithDecodeError() throws Exception {
		TlsEngine client = newTrustAllClient();
		emitClientHello(client);
		byte[] malformed = new byte[4 + 10];
		malformed[0] = (byte) ServerHelloMessage.TYPE;
		malformed[3] = 10;
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	@Test
	public void unknownHandshakeMessageTypeAbortsWithUnexpectedMessage() throws Exception {
		TlsEngine server = newServerEngine();
		TlsAlertException e = expectAlert(() ->
			server.consume(EncryptionLevel.INITIAL, wrap(new byte[] {99, 0, 0, 0})));
		assertEquals(TlsAlerts.UNEXPECTED_MESSAGE, e.alertCode());
	}

	// ---- server-side negotiation rows ----

	@Test
	public void downgradeSentinelInClientRandomAbortsWithIllegalParameter() throws Exception {
		TlsEngine server = newServerEngine();
		byte[] random = new byte[32];
		System.arraycopy(new byte[] {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x01}, 0, random, 24, 8);
		TlsAlertException e = expectAlert(() ->
			server.consume(EncryptionLevel.INITIAL, wrap(clientHelloBytes(defaultClientExtensions(), random))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void duplicateTransportParameterIsSurfacedAsTransportParameterErrorNotAnAlert() throws Exception {
		TlsEngine server = newServerEngine();
		List<TlsExtension> extensions = without(defaultClientExtensions(), QuicTransportParametersExt.class);
		// max_idle_timeout (id 8) twice — RFC 9000 §18.1: a duplicate is a transport-parameter error,
		// surfaced unchanged for the connection layer, never mapped to a TLS alert
		extensions.add(new UnknownExtension(QuicTransportParametersExt.TYPE, new byte[] {8, 1, 0, 8, 1, 0}));
		assertThrows(QuicTransportParameters.DuplicateTransportParameterException.class, () ->
			server.consume(EncryptionLevel.INITIAL, wrap(clientHelloBytes(extensions, new byte[32]))));
	}

	@Test
	public void duplicateExtensionAbortsWithIllegalParameter() throws Exception {
		TlsEngine server = newServerEngine();
		List<TlsExtension> extensions = defaultClientExtensions();
		extensions.add(new AlpnExt(List.of("h3"))); // a second ALPN block — RFC 8446 §4.2 forbids duplicate types
		TlsAlertException e = expectAlert(() ->
			server.consume(EncryptionLevel.INITIAL, wrap(clientHelloBytes(extensions, new byte[32]))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void keyShareWithoutSupportedGroupsAbortsWithIllegalParameter() throws Exception {
		TlsEngine server = newServerEngine();
		// a client offering key_share MUST also offer supported_groups (RFC 8446 §4.2.7)
		List<TlsExtension> extensions = without(defaultClientExtensions(), SupportedGroupsExt.class);
		TlsAlertException e = expectAlert(() ->
			server.consume(EncryptionLevel.INITIAL, wrap(clientHelloBytes(extensions, new byte[32]))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void keyShareGroupAbsentFromSupportedGroupsAbortsWithIllegalParameter() throws Exception {
		TlsEngine server = newServerEngine();
		List<TlsExtension> extensions = without(defaultClientExtensions(), SupportedGroupsExt.class);
		// key_share offers x25519, but supported_groups advertises secp256r1 only (RFC 8446 §4.2.8)
		extensions.add(new SupportedGroupsExt(new int[] {NamedGroup.SECP256R1.code()}));
		TlsAlertException e = expectAlert(() ->
			server.consume(EncryptionLevel.INITIAL, wrap(clientHelloBytes(extensions, new byte[32]))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	// ---- client-side ServerHello rows ----

	@Test
	public void unsolicitedServerHelloExtensionAbortsWithUnsupportedExtension() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		// RFC 8446 §4.1.3/§4.2: a ServerHello carries only supported_versions and key_share —
		// a server_name acknowledgement belongs to EncryptedExtensions
		server.shExtensionsOverride = List.of(
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
			KeyShareExt.ofSelectedShare(new KeyShareEntry(
				NamedGroup.X25519.code(), TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, server.keyPair.getPublic()))),
			new ServerNameExt(null));
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.UNSUPPORTED_EXTENSION, e.alertCode());
	}

	@Test
	public void duplicateServerHelloExtensionAbortsWithIllegalParameter() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.shExtensionsOverride = List.of(
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3), // duplicate (RFC 8446 §4.2)
			KeyShareExt.ofSelectedShare(new KeyShareEntry(
				NamedGroup.X25519.code(), TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, server.keyPair.getPublic()))));
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void non0303ServerHelloLegacyVersionAbortsWithIllegalParameter() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.shLegacyVersion = 0x0301;
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void downgradeSentinelInServerRandomAbortsWithIllegalParameter() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		byte[] random = new byte[32];
		System.arraycopy(new byte[] {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x00}, 0, random, 24, 8);
		server.serverRandomOverride = random;
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void wrongLengthKeyShareAbortsWithDecodeErrorBeforeCrypto() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.keyShareOverride = new KeyShareEntry(NamedGroup.X25519.code(), new byte[31]);
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	@Test
	public void allZeroX25519SharedSecretAbortsWithIllegalParameter() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		// a low-order peer point (all-zero public key) — RFC 8446 §7.4.1 contributory check
		server.keyShareOverride = new KeyShareEntry(NamedGroup.X25519.code(), new byte[32]);
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	// ---- client-side EncryptedExtensions rows ----

	@Test
	public void missingTransportParametersInEncryptedExtensionsAbortsWithMissingExtension() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.eeExtensionsOverride = List.of(new AlpnExt(List.of("h3")));
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
		assertEquals(TlsAlerts.MISSING_EXTENSION, e.alertCode());
	}

	@Test
	public void nonH3AlpnSelectionAbortsWithNoApplicationProtocol() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.eeExtensionsOverride = List.of(
			new AlpnExt(List.of("h2")), new QuicTransportParametersExt(SERVER_PARAMS));
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
		assertEquals(TlsAlerts.NO_APPLICATION_PROTOCOL, e.alertCode());
	}

	// ---- client-side Certificate rows ----

	@Test
	public void emptyServerCertificateListAbortsWithBadCertificate() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.certificateEntriesOverride = List.of();
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	@Test
	public void unparseableCertificateBytesAbortWithBadCertificate() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		server.certificateEntriesOverride = List.of(new CertificateEntry(new byte[] {1, 2, 3, 4}, List.of()));
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	@Test
	public void certificateRequestFromServerAbortsWithUnexpectedMessage() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		// CertificateRequest: handshake type 13 — mTLS is out of scope; the server must never send it
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(new byte[] {13, 0, 0, 1, 0})));
		assertEquals(TlsAlerts.UNEXPECTED_MESSAGE, e.alertCode());
	}

	// ---- F-01 (reviews/review-1.md): zero-length wire fields → decode_error, never IAE ----

	@Test
	public void zeroLengthCertificateDataAbortsWithDecodeError() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		// RFC 8446 §4.4.2: certificate_data is 1..2^24-1 bytes — a zero-length entry is malformed
		byte[] malformed = {
			(byte) CertificateMessage.TYPE, 0, 0, 9,
			0,          // certificate_request_context length
			0, 0, 5,    // certificate_list length
			0, 0, 0,    // certificate_data length = 0
			0, 0};      // entry extensions length
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	@Test
	public void zeroLengthCertificateVerifySignatureAbortsWithDecodeError() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		TlsEngine client = newTrustAllClient();
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		// RFC 8446 §4.4.3: signature is 0..2^16-1 opaque bytes, but an empty signature can
		// never verify — rejected at parse time as malformed
		byte[] malformed = {
			(byte) CertificateVerifyMessage.TYPE, 0, 0, 4,
			0x08, 0x04, // rsa_pss_rsae_sha256
			0, 0};      // signature length = 0
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	/**
	 * RFC 8446 §4.6.1 declares {@code opaque ticket_nonce<0..255>} — an <b>empty</b> nonce is legal,
	 * unlike the {@code ticket<1..2^16-1>} beside it (see {@link #zeroLengthTicketAbortsWithDecodeError}).
	 * <p>
	 * This test used to assert the opposite, and that mistake was a real interop failure rather than a
	 * theoretical one: quic-go sends an empty nonce, and because {@code NewSessionTicket} arrives
	 * <b>post-handshake</b>, rejecting it raised {@code CRYPTO_ERROR(50)} and tore down a fully
	 * established connection moments after it started serving traffic. It presented as an HTTP/3 bug
	 * and was not one. Nothing ActiveJ↔ActiveJ could catch it — the peer has to be someone else's.
	 */
	@Test
	public void zeroLengthTicketNonceIsAccepted() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		TlsEngine client = newTrustAllClient();
		completeHandshake(client, server);
		byte[] emptyNonce = {
			(byte) NewSessionTicketMessage.TYPE, 0, 0, 14,
			0, 0, 0, 0, // ticket_lifetime
			0, 0, 0, 0, // ticket_age_add
			0,          // ticket_nonce length = 0 — legal
			0, 1, 9,    // ticket length 1 + ticket byte
			0, 0};      // extensions length
		// Tolerated and discarded exactly like any other ticket (FR-015): no alert, engine alive.
		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT, wrap(emptyNonce));
		assertFalse(result.handshakeComplete());
		assertTrue(result.cryptoToSend().isEmpty());
		assertTrue(result.keysToInstall().isEmpty());
	}

	@Test
	public void zeroLengthTicketAbortsWithDecodeError() throws Exception {
		ScriptedTlsServer server = new ScriptedTlsServer(rsaIdentity());
		TlsEngine client = newTrustAllClient();
		completeHandshake(client, server);
		// RFC 8446 §4.6.1: ticket is 1..2^16-1 bytes
		byte[] malformed = {
			(byte) NewSessionTicketMessage.TYPE, 0, 0, 14,
			0, 0, 0, 0, // ticket_lifetime
			0, 0, 0, 0, // ticket_age_add
			1, 7,       // ticket_nonce length 1 + nonce byte
			0, 0,       // ticket length = 0
			0, 0};      // extensions length
		TlsAlertException e = expectAlert(() ->
			client.consume(EncryptionLevel.ONE_RTT, wrap(malformed)));
		assertEquals(TlsAlerts.DECODE_ERROR, e.alertCode());
	}

	// ---- helpers ----

	private static void completeHandshake(TlsEngine client, ScriptedTlsServer server) throws Exception {
		server.acceptClientHello(emitClientHello(client));
		client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		recycleOutput(client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false))));
	}

	private static TlsEngine newServerEngine() throws Exception {
		return QuicTls.serverEngine(TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS).build());
	}

	private static TlsEngine newTrustAllClient() throws Exception {
		return newClient("example.test", trustingLeaf(rsaIdentity().leaf()));
	}

	private static byte[] clientHelloBytes(List<TlsExtension> extensions, byte[] random) {
		return ScriptedTlsServer.serialize(new ClientHelloMessage(
			ClientHelloMessage.LEGACY_VERSION, random, new byte[0],
			new int[] {TlsCipherSuite.TLS_AES_128_GCM_SHA256.code()}, new int[] {0}, extensions));
	}

	private static List<TlsExtension> defaultClientExtensions() {
		KeyPair keyPair = TlsKeyExchanges.generateKeyPair(NamedGroup.X25519);
		byte[] publicKey = TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, keyPair.getPublic());
		return new ArrayList<>(List.of(
			SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3),
			new SupportedGroupsExt(new int[] {NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()}),
			KeyShareExt.ofClientShares(List.of(new KeyShareEntry(NamedGroup.X25519.code(), publicKey))),
			new SignatureAlgorithmsExt(new int[] {
				SignatureScheme.ECDSA_SECP256R1_SHA256.code(),
				SignatureScheme.RSA_PSS_RSAE_SHA256.code(),
				SignatureScheme.ED25519.code()}),
			new AlpnExt(List.of("h3")),
			new PskKeyExchangeModesExt(new int[] {1}),
			new QuicTransportParametersExt(CLIENT_PARAMS)));
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

	private static TlsAlertException expectAlert(TlsClientEngineTest.ThrowingRunnable runnable) {
		return assertThrows(TlsAlertException.class, runnable::run);
	}
}
