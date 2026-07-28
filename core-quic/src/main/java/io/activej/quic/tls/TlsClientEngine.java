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
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.activej.quic.tls.TlsAlerts.*;

/**
 * The client-side TLS 1.3 handshake engine for the QUIC profile (data-model.md, US4):
 * <pre>
 * START ──emit CH──► WAIT_SH ──SH──► WAIT_EE ──EE──► WAIT_CERT ──Cert──► WAIT_CV ──CV──► WAIT_FIN
 *                      │ HRR random ⇒ TlsHelloRetryRequestException (terminal)            │ Fin──► emit Finished ──► CONNECTED
 *                      └ any failure ⇒ TlsAlertException (terminal)
 * CONNECTED: NewSessionTicket tolerated &amp; discarded (FR-015); KeyUpdate ⇒ unexpected_message (RFC 9001 §6)
 * </pre>
 * The ClientHello offers the three profile cipher suites (RFC 9001 §8.1), a single x25519 key
 * share, the five profile signature schemes (never PKCS#1 v1.5 — FR-010), ALPN {@code h3},
 * {@code psk_key_exchange_modes} and the local {@code quic_transport_parameters}; SNI is sent
 * for a hostname and omitted for an IP literal (FR-019). Seeded with the RFC 8448 §3 randomness
 * hooks, the emitted ClientHello matches the published trace on every trace-defined field
 * (SC-006).
 * <p>
 * The server flight is validated per RFC 8446 restricted by RFC 9001 §8: the ServerHello's
 * version, session-id echo, suite, group and downgrade sentinels; the mandatory
 * {@code quic_transport_parameters} ({@code missing_extension}); the ALPN selection
 * ({@code no_application_protocol}); the certificate chain via the configured
 * {@code X509TrustManager} — never reimplemented (FR-011) — followed by RFC 6125 endpoint
 * identification for hostnames; CertificateVerify against the presented leaf
 * ({@code decrypt_error}); and the server Finished with a constant-time comparison (FR-016).
 * Key installation fires per FR-005/FR-013: the Handshake-level pair rides with the ServerHello
 * result, the 1-RTT pair with the client Finished. Handshake-phase state is shed at completion;
 * the application traffic secrets are retained for key-update readiness (FR-005).
 * <p>
 * The slf4j DEBUG trace (FR-020) prints the message flow and transcript-hash digests only —
 * never keys, shared secrets or traffic secrets (SI-6).
 */
public final class TlsClientEngine implements TlsEngine {
	private static final Logger logger = LoggerFactory.getLogger(TlsClientEngine.class);

	private static final String ALPN_H3 = "h3";

	/** Offered cipher suites, in preference order — the RFC 8448 §3 trace's order (SC-006). */
	private static final TlsCipherSuite[] OFFERED_SUITES = {
		TlsCipherSuite.TLS_AES_128_GCM_SHA256,
		TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256,
		TlsCipherSuite.TLS_AES_256_GCM_SHA384};

	/** Offered signature schemes, in preference order — never PKCS#1 v1.5 (FR-010). */
	private static final SignatureScheme[] OFFERED_SCHEMES = {
		SignatureScheme.ECDSA_SECP256R1_SHA256,
		SignatureScheme.RSA_PSS_RSAE_SHA256,
		SignatureScheme.RSA_PSS_RSAE_SHA384,
		SignatureScheme.RSA_PSS_RSAE_SHA512,
		SignatureScheme.ED25519};

	/** The single key-share group the client offers (the interop default; RFC 8446 §4.2.8). */
	private static final NamedGroup KEY_SHARE_GROUP = NamedGroup.X25519;

	/** RFC 8446 §4.1.3 downgrade sentinels, rejected in any ServerHello random (data-model validation table). */
	private static final byte[] DOWNGRADE_SENTINEL_TLS_1_2 = {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x01};
	private static final byte[] DOWNGRADE_SENTINEL_TLS_1_1 = {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x00};

	private enum State {START, WAIT_SH, WAIT_EE, WAIT_CERT, WAIT_CV, WAIT_FIN, CONNECTED, FAILED}

	private final String remoteName;
	private final boolean hostname;
	private final QuicTransportParameters localTransportParameters;
	private final @Nullable X509TrustManager trustManager;
	private final boolean endpointIdentification;
	private final SecureRandom secureRandom;
	private final Function<NamedGroup, KeyPair> ephemeralKeySource;
	private final int maxHandshakeMessageSize;

	/** Per-level reassembly of split/coalesced CRYPTO bytes (spec §Edge Cases), bounded by the message size bound. */
	private final CryptoReassembly[] pending = newReassemblyBuffers();

	private final TranscriptHash transcript = new TranscriptHash();

	private State state = State.START;
	private @Nullable QuicTransportParameters peerTransportParameters;
	private @Nullable String negotiatedAlpn;

	// handshake-phase state — shed at completion (spec §Data & State)
	private @Nullable KeyPair ephemeralKeyPair;
	private @Nullable TlsCipherSuite suite;
	private @Nullable TlsKeySchedule keySchedule;
	private @Nullable X509Certificate serverLeaf;
	private byte @Nullable [] clientHandshakeTrafficSecret;
	private byte @Nullable [] serverHandshakeTrafficSecret;

	// retained past completion for key-update readiness (FR-005) — never logged
	private byte @Nullable [] clientApplicationTrafficSecret;
	private byte @Nullable [] serverApplicationTrafficSecret;

	TlsClientEngine(TlsClientConfig config) {
		this.remoteName = config.remoteName();
		this.hostname = config.isHostname();
		this.localTransportParameters = config.localTransportParameters();
		this.trustManager = config.trustManager();
		this.endpointIdentification = config.endpointIdentification();
		this.secureRandom = config.secureRandom();
		this.ephemeralKeySource = config.ephemeralKeySource();
		this.maxHandshakeMessageSize = config.maxHandshakeMessageSize().toInt();
	}

	@Override
	public TlsEngineResult consume(EncryptionLevel level, ByteBuf cryptoBytes)
			throws TlsAlertException, TlsHelloRetryRequestException, MalformedDataException {
		byte[] received = new byte[cryptoBytes.readRemaining()];
		cryptoBytes.read(received);
		cryptoBytes.recycle();
		if (state == State.FAILED) {
			throw new IllegalStateException("The handshake has already failed terminally");
		}
		int slot = level.ordinal();
		CryptoReassembly reassembly = pending[slot];
		reassembly.append(received);
		Map<EncryptionLevel, ByteBuf> cryptoToSend = new EnumMap<>(EncryptionLevel.class);
		List<KeyInstallation> installations = new ArrayList<>();
		boolean completed = false;
		try {
			if (state == State.START) {
				if (level != EncryptionLevel.INITIAL) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"The ClientHello is emitted on the INITIAL-level CRYPTO stream, got " + level);
				}
				emitClientHello(cryptoToSend);
				state = State.WAIT_SH;
			}
			byte[] data = reassembly.array();
			int dataLength = reassembly.length();
			int offset = 0;
			while (offset + 4 <= dataLength) {
				int bodyLength = uint24(data, offset + 1);
				if (bodyLength > maxHandshakeMessageSize) {
					throw new TlsAlertException(DECODE_ERROR,
						"Handshake message declares " + bodyLength + " body bytes, exceeding the configured " +
						"maxHandshakeMessageSize of " + maxHandshakeMessageSize);
				}
				if (offset + 4 + bodyLength > dataLength) {
					break; // split message: wait for the rest of this level's CRYPTO stream
				}
				int type = data[offset] & 0xFF;
				if (!isKnownMessageType(type)) {
					// covers TLS KeyUpdate (24) too — forbidden in QUIC (RFC 9001 §6)
					throw new TlsAlertException(UNEXPECTED_MESSAGE, "Unexpected handshake message type: " + type);
				}
				byte[] messageBytes = Arrays.copyOfRange(data, offset, offset + 4 + bodyLength);
				completed |= process(level, parse(messageBytes), messageBytes, cryptoToSend, installations);
				offset += 4 + bodyLength;
			}
			reassembly.discardPrefix(offset);
		} catch (TlsAlertException | TlsHelloRetryRequestException | MalformedDataException | RuntimeException e) {
			failTerminally(cryptoToSend);
			throw e;
		}
		if (completed) {
			return TlsEngineResult.complete(cryptoToSend, installations, negotiatedAlpn, peerTransportParameters);
		}
		return installations.isEmpty() && cryptoToSend.isEmpty()
			? TlsEngineResult.empty()
			: TlsEngineResult.of(cryptoToSend, installations);
	}

	// ---- ClientHello emission (SC-006: deterministic under the seeded hooks) ----

	private void emitClientHello(Map<EncryptionLevel, ByteBuf> cryptoToSend) {
		byte[] clientRandom = new byte[32];
		secureRandom.nextBytes(clientRandom);
		KeyPair ephemeral = ephemeralKeySource.apply(KEY_SHARE_GROUP);
		byte[] publicKeyBytes = TlsKeyExchanges.encodePublicKey(KEY_SHARE_GROUP, ephemeral.getPublic());

		List<TlsExtension> extensions = new ArrayList<>();
		if (hostname) {
			extensions.add(new ServerNameExt(remoteName)); // FR-019: SNI for a hostname, never for an IP literal
		}
		extensions.add(SupportedVersionsExt.ofClientVersions(SupportedVersionsExt.TLS_1_3));
		extensions.add(new SupportedGroupsExt(new int[] {NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()}));
		extensions.add(KeyShareExt.ofClientShares(List.of(new KeyShareEntry(KEY_SHARE_GROUP.code(), publicKeyBytes))));
		int[] schemeCodes = new int[OFFERED_SCHEMES.length];
		for (int i = 0; i < OFFERED_SCHEMES.length; i++) {
			schemeCodes[i] = OFFERED_SCHEMES[i].code();
		}
		extensions.add(new SignatureAlgorithmsExt(schemeCodes));
		extensions.add(new AlpnExt(List.of(ALPN_H3)));
		extensions.add(new PskKeyExchangeModesExt(new int[] {1})); // psk_dhe_ke — no PSK is ever offered
		extensions.add(new QuicTransportParametersExt(localTransportParameters));

		int[] suiteCodes = new int[OFFERED_SUITES.length];
		for (int i = 0; i < OFFERED_SUITES.length; i++) {
			suiteCodes[i] = OFFERED_SUITES[i].code();
		}
		ClientHelloMessage clientHello = new ClientHelloMessage(
			ClientHelloMessage.LEGACY_VERSION, clientRandom, new byte[0], suiteCodes, new int[] {0}, extensions);
		byte[] clientHelloBytes = serialize(clientHello);
		transcript.update(clientHelloBytes);
		ephemeralKeyPair = ephemeral;
		logger.debug("→ ClientHello ({} bytes), SNI {}", clientHelloBytes.length, hostname ? remoteName : "<none>");

		ByteBuf flight = ByteBufPool.allocate(clientHelloBytes.length);
		flight.put(clientHelloBytes);
		cryptoToSend.put(EncryptionLevel.INITIAL, flight);
	}

	// ---- state machine ----

	/**
	 * Processes one complete message. Returns {@code true} when this message completed the
	 * handshake (the server's Finished) — the result surface is assembled by {@link #consume}.
	 */
	private boolean process(EncryptionLevel level, TlsHandshakeMessage message, byte[] messageBytes,
			Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> installations)
			throws TlsAlertException, TlsHelloRetryRequestException {
		return switch (state) {
			case START -> throw new TlsAlertException(UNEXPECTED_MESSAGE,
				"No handshake message is expected before the ClientHello is emitted, got message type " + message.type());
			case WAIT_SH -> {
				if (level != EncryptionLevel.INITIAL || !(message instanceof ServerHelloMessage serverHello)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected a ServerHello on the INITIAL-level CRYPTO stream, got message type " + message.type() +
						" on " + level);
				}
				processServerHello(serverHello, messageBytes, installations);
				state = State.WAIT_EE;
				yield false;
			}
			case WAIT_EE -> {
				if (level != EncryptionLevel.HANDSHAKE || !(message instanceof EncryptedExtensionsMessage encryptedExtensions)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected EncryptedExtensions on the HANDSHAKE-level CRYPTO stream, got message type " +
						message.type() + " on " + level);
				}
				processEncryptedExtensions(encryptedExtensions, messageBytes);
				state = State.WAIT_CERT;
				yield false;
			}
			case WAIT_CERT -> {
				if (level != EncryptionLevel.HANDSHAKE || !(message instanceof CertificateMessage certificate)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected a Certificate on the HANDSHAKE-level CRYPTO stream, got message type " +
						message.type() + " on " + level);
				}
				processCertificate(certificate, messageBytes);
				state = State.WAIT_CV;
				yield false;
			}
			case WAIT_CV -> {
				if (level != EncryptionLevel.HANDSHAKE || !(message instanceof CertificateVerifyMessage certificateVerify)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected a CertificateVerify on the HANDSHAKE-level CRYPTO stream, got message type " +
						message.type() + " on " + level);
				}
				processCertificateVerify(certificateVerify, messageBytes);
				state = State.WAIT_FIN;
				yield false;
			}
			case WAIT_FIN -> {
				if (level != EncryptionLevel.HANDSHAKE || !(message instanceof FinishedMessage finished)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected the server Finished on the HANDSHAKE-level CRYPTO stream, got message type " +
						message.type() + " on " + level);
				}
				processServerFinished(finished, messageBytes, cryptoToSend, installations);
				state = State.CONNECTED;
				yield true;
			}
			case CONNECTED -> {
				if (level != EncryptionLevel.ONE_RTT) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Post-handshake messages arrive on the ONE_RTT-level CRYPTO stream, got " + level);
				}
				if (!(message instanceof NewSessionTicketMessage)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Unexpected post-handshake message type " + message.type() + " (RFC 9001 §6)");
				}
				// FR-015: NewSessionTicket is parsed structurally and discarded — no resumption state
				logger.debug("Discarding a NewSessionTicket from the server (FR-015)");
				yield false;
			}
			case FAILED -> throw new IllegalStateException("The handshake has already failed terminally");
		};
	}

	private void processServerHello(ServerHelloMessage serverHello, byte[] serverHelloBytes,
			List<KeyInstallation> installations) throws TlsAlertException, TlsHelloRetryRequestException {
		logger.debug("← ServerHello ({} bytes)", serverHelloBytes.length);
		if (serverHello.isHelloRetryRequest()) {
			// FR-014, clarification Q1: HRR is detected via the special random and rejected clearly
			throw new TlsHelloRetryRequestException(
				"HelloRetryRequest is not supported in this profile — the server asked to restart the handshake");
		}
		// RFC 8446 §4.1.3/§4.2: the only extensions specified for a (non-PSK) ServerHello are
		// supported_versions and key_share — anything else was never offered by the client
		for (TlsExtension extension : serverHello.extensions) {
			if (!(extension instanceof SupportedVersionsExt) && !(extension instanceof KeyShareExt)) {
				throw new TlsAlertException(UNSUPPORTED_EXTENSION,
					"ServerHello carries unsolicited extension 0x" + Integer.toHexString(extension.type()) +
					" — only supported_versions and key_share are specified for the ServerHello (RFC 8446 §4.1.3)");
			}
		}
		if (serverHello.legacyVersion != ServerHelloMessage.LEGACY_VERSION) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello legacy_version is 0x" + Integer.toHexString(serverHello.legacyVersion) +
				" — TLS 1.3 servers send 0x0303 (RFC 8446 §4.1.3)");
		}
		// the client sent an empty legacy_session_id — the echo must be empty (RFC 8446 §4.1.3)
		if (serverHello.sessionIdEcho.length != 0) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello session_id_echo does not match the client's (empty) legacy_session_id (RFC 8446 §4.1.3)");
		}
		TlsCipherSuite selectedSuite = serverHello.knownCipherSuite();
		if (selectedSuite == null || !isOffered(selectedSuite)) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello selected cipher suite 0x" + Integer.toHexString(serverHello.cipherSuite) +
				" that the client did not offer (RFC 8446 §4.1.3)");
		}
		if (serverHello.compressionMethod != 0) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello selected a non-null legacy compression method (RFC 8446 §4.1.3)");
		}
		SupportedVersionsExt versions = findExtension(serverHello.extensions, SupportedVersionsExt.class);
		if (versions == null || !versions.selectedForm ||
			versions.versions.length != 1 || versions.versions[0] != SupportedVersionsExt.TLS_1_3) {
			throw new TlsAlertException(PROTOCOL_VERSION,
				"ServerHello does not select TLS 1.3 in supported_versions (RFC 9001 §8.2)");
		}
		checkDowngradeSentinels(serverHello.random);
		KeyShareExt keyShare = findExtension(serverHello.extensions, KeyShareExt.class);
		if (keyShare == null || keyShare.selectedShare == null) {
			throw new TlsAlertException(MISSING_EXTENSION,
				"ServerHello carries no selected key_share (RFC 8446 §4.2.8)");
		}
		if (keyShare.selectedShare.groupCode != KEY_SHARE_GROUP.code()) {
			// the client offered exactly one share — any other selection would need HRR (unsupported)
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello selected group 0x" + Integer.toHexString(keyShare.selectedShare.groupCode) +
				" for which the client offered no key share (RFC 8446 §4.2.8)");
		}

		transcript.bindCipherSuite(selectedSuite);
		transcript.update(serverHelloBytes);
		suite = selectedSuite;

		KeyPair ephemeral = ephemeralKeyPair;
		assert ephemeral != null;
		byte[] sharedSecret;
		try {
			sharedSecret = TlsKeyExchanges.agree(KEY_SHARE_GROUP, ephemeral.getPrivate(), keyShare.selectedShare.keyExchange);
		} finally {
			// the ephemeral private key has done its job — destroy it whether or not the agreement succeeded
			TlsKeyExchanges.destroyQuietly(ephemeral.getPrivate());
			ephemeralKeyPair = null;
		}
		keySchedule = TlsKeySchedule.start(selectedSuite);
		keySchedule.mixEcdhe(sharedSecret);
		Arrays.fill(sharedSecret, (byte) 0);
		byte[] clientHelloServerHelloHash = transcript.hash();
		if (logger.isDebugEnabled()) {
			logger.debug("CH..SH transcript hash: {}", HexFormat.of().formatHex(clientHelloServerHelloHash));
		}
		clientHandshakeTrafficSecret = keySchedule.clientHandshakeTrafficSecret(clientHelloServerHelloHash);
		serverHandshakeTrafficSecret = keySchedule.serverHandshakeTrafficSecret(clientHelloServerHelloHash);
		logger.debug("ServerHello: suite {}, group {}", selectedSuite, KEY_SHARE_GROUP);
		installations.add(new KeyInstallation(EncryptionLevel.HANDSHAKE, new TlsKeys(
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), clientHandshakeTrafficSecret),
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), serverHandshakeTrafficSecret))));
		keySchedule.deriveMasterSecret();
	}

	private void processEncryptedExtensions(EncryptedExtensionsMessage encryptedExtensions, byte[] messageBytes)
			throws TlsAlertException {
		logger.debug("← EncryptedExtensions");
		QuicTransportParametersExt transportParameters = findExtension(encryptedExtensions.extensions, QuicTransportParametersExt.class);
		if (transportParameters == null) {
			throw new TlsAlertException(MISSING_EXTENSION,
				"EncryptedExtensions carries no quic_transport_parameters extension (mandatory in QUIC, RFC 9001 §8.2)");
		}
		peerTransportParameters = transportParameters.parameters;

		AlpnExt alpn = findExtension(encryptedExtensions.extensions, AlpnExt.class);
		if (alpn == null || !alpn.protocols.equals(List.of(ALPN_H3))) {
			throw new TlsAlertException(NO_APPLICATION_PROTOCOL,
				"The server did not select ALPN 'h3' (FR-012, RFC 9001 §8.1)");
		}
		negotiatedAlpn = ALPN_H3;
		transcript.update(messageBytes);
	}

	private void processCertificate(CertificateMessage certificate, byte[] messageBytes) throws TlsAlertException {
		logger.debug("← Certificate ({} entries)", certificate.entries.size());
		if (certificate.certificateRequestContext.length != 0) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"Server Certificate carries a non-empty certificate_request_context (RFC 8446 §4.4.2)");
		}
		if (certificate.entries.isEmpty()) {
			throw new TlsAlertException(BAD_CERTIFICATE,
				"Server Certificate carries an empty certificate list (RFC 8446 §4.4.2)");
		}
		X509Certificate[] chain = new X509Certificate[certificate.entries.size()];
		try {
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			for (int i = 0; i < chain.length; i++) {
				chain[i] = (X509Certificate) factory.generateCertificate(
					new ByteArrayInputStream(certificate.entries.get(i).certificateBytes));
			}
		} catch (CertificateException | ClassCastException e) {
			throw new TlsAlertException(BAD_CERTIFICATE,
				"Server certificate bytes are not a parseable X.509 certificate: " + e.getMessage());
		}

		X509TrustManager validator = trustManager;
		if (validator != null) {
			// FR-011: chain validation is delegated to the JDK trust manager — never reimplemented
			try {
				validator.checkServerTrusted(chain, chain[0].getPublicKey().getAlgorithm());
			} catch (CertificateException e) {
				throw new TlsAlertException(alertForCertificateFailure(e),
					"Server certificate chain rejected by the trust manager");
			}
			if (endpointIdentification) {
				TlsEndpointIdentification.verify(chain[0], remoteName);
			}
		} // else: insecureTrustAll() — validation and identification explicitly skipped (FR-011)
		serverLeaf = chain[0];
		transcript.update(messageBytes);
	}

	private void processCertificateVerify(CertificateVerifyMessage certificateVerify, byte[] messageBytes)
			throws TlsAlertException {
		SignatureScheme scheme = certificateVerify.knownScheme();
		if (scheme == null || !isOffered(scheme)) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"CertificateVerify uses signature scheme 0x" + Integer.toHexString(certificateVerify.signatureScheme) +
				" that the client did not offer (RFC 8446 §4.4.3)");
		}
		X509Certificate leaf = serverLeaf;
		assert leaf != null;
		byte[] content = TlsSignatures.certificateVerifyContent(true, transcript.hash());
		if (!TlsSignatures.verify(scheme, leaf.getPublicKey(), content, certificateVerify.signature)) {
			throw new TlsAlertException(DECRYPT_ERROR, "Server CertificateVerify signature does not verify");
		}
		logger.debug("← CertificateVerify verified: scheme {}", scheme);
		transcript.update(messageBytes);
	}

	private void processServerFinished(FinishedMessage finished, byte[] messageBytes,
			Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> installations) throws TlsAlertException {
		TlsKeySchedule schedule = keySchedule;
		TlsCipherSuite selectedSuite = suite;
		byte[] clientHandshakeTraffic = clientHandshakeTrafficSecret;
		byte[] serverHandshakeTraffic = serverHandshakeTrafficSecret;
		assert schedule != null && selectedSuite != null && clientHandshakeTraffic != null && serverHandshakeTraffic != null;

		byte[] serverFinishedKey = schedule.finishedKey(serverHandshakeTraffic);
		byte[] expectedVerifyData = schedule.verifyData(serverFinishedKey, transcript.hash());
		Arrays.fill(serverFinishedKey, (byte) 0);
		// FR-016: constant-time comparison
		if (!MessageDigest.isEqual(expectedVerifyData, finished.verifyData)) {
			throw new TlsAlertException(DECRYPT_ERROR, "Server Finished verify_data mismatch");
		}
		transcript.update(messageBytes);
		logger.debug("← server Finished verified; handshake complete (ALPN {})", negotiatedAlpn);

		byte[] serverFinishedTranscriptHash = transcript.hash();
		if (logger.isDebugEnabled()) {
			logger.debug("CH..server Finished transcript hash: {}", HexFormat.of().formatHex(serverFinishedTranscriptHash));
		}
		clientApplicationTrafficSecret = schedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
		serverApplicationTrafficSecret = schedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);

		byte[] clientFinishedKey = schedule.finishedKey(clientHandshakeTraffic);
		byte[] clientVerifyData = schedule.verifyData(clientFinishedKey, serverFinishedTranscriptHash);
		Arrays.fill(clientFinishedKey, (byte) 0);
		byte[] clientFinishedBytes = serialize(new FinishedMessage(clientVerifyData));
		transcript.update(clientFinishedBytes);
		logger.debug("→ Finished");

		ByteBuf flight = ByteBufPool.allocate(clientFinishedBytes.length);
		flight.put(clientFinishedBytes);
		cryptoToSend.put(EncryptionLevel.HANDSHAKE, flight);
		installations.add(new KeyInstallation(EncryptionLevel.ONE_RTT, new TlsKeys(
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), clientApplicationTrafficSecret),
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), serverApplicationTrafficSecret))));

		// shed handshake-phase state (spec §Data & State); application traffic secrets stay for FR-005
		Arrays.fill(clientHandshakeTraffic, (byte) 0);
		Arrays.fill(serverHandshakeTraffic, (byte) 0);
		clientHandshakeTrafficSecret = null;
		serverHandshakeTrafficSecret = null;
		schedule.destroy(); // zeroes the master secret before the schedule is dropped
		keySchedule = null;
		suite = null;
		serverLeaf = null;
	}

	// ---- helpers ----

	private void failTerminally(Map<EncryptionLevel, ByteBuf> cryptoToSend) {
		state = State.FAILED;
		for (ByteBuf buf : cryptoToSend.values()) {
			buf.recycle();
		}
		for (CryptoReassembly reassembly : pending) {
			reassembly.clear();
		}
		zeroSecrets();
		if (keySchedule != null) {
			keySchedule.destroy(); // zeroes whichever stage secret the schedule still holds
			keySchedule = null;
		}
		if (ephemeralKeyPair != null) {
			TlsKeyExchanges.destroyQuietly(ephemeralKeyPair.getPrivate());
			ephemeralKeyPair = null;
		}
	}

	private void zeroSecrets() {
		if (clientHandshakeTrafficSecret != null) Arrays.fill(clientHandshakeTrafficSecret, (byte) 0);
		if (serverHandshakeTrafficSecret != null) Arrays.fill(serverHandshakeTrafficSecret, (byte) 0);
		if (clientApplicationTrafficSecret != null) Arrays.fill(clientApplicationTrafficSecret, (byte) 0);
		if (serverApplicationTrafficSecret != null) Arrays.fill(serverApplicationTrafficSecret, (byte) 0);
	}

	private static int alertForCertificateFailure(CertificateException failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof CertificateExpiredException) {
				return CERTIFICATE_EXPIRED;
			}
			if (t instanceof CertPathValidatorException validatorException &&
				validatorException.getReason() == CertPathValidatorException.BasicReason.EXPIRED) {
				return CERTIFICATE_EXPIRED;
			}
		}
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof CertPathBuilderException) {
				return UNKNOWN_CA; // no certification path to any trust anchor
			}
		}
		return BAD_CERTIFICATE;
	}

	private static void checkDowngradeSentinels(byte[] serverRandom) throws TlsAlertException {
		byte[] suffix = Arrays.copyOfRange(serverRandom, serverRandom.length - 8, serverRandom.length);
		if (Arrays.equals(suffix, DOWNGRADE_SENTINEL_TLS_1_2) || Arrays.equals(suffix, DOWNGRADE_SENTINEL_TLS_1_1)) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ServerHello random carries an RFC 8446 §4.1.3 downgrade sentinel");
		}
	}

	private static boolean isOffered(TlsCipherSuite suite) {
		for (TlsCipherSuite offered : OFFERED_SUITES) {
			if (offered == suite) return true;
		}
		return false;
	}

	private static boolean isOffered(SignatureScheme scheme) {
		for (SignatureScheme offered : OFFERED_SCHEMES) {
			if (offered == scheme) return true;
		}
		return false;
	}

	private static TlsHandshakeMessage parse(byte[] messageBytes) throws TlsAlertException, MalformedDataException {
		try {
			return TlsMessages.read(ByteBuf.wrapForReading(messageBytes));
		} catch (MalformedDataException e) {
			if (e instanceof QuicTransportParameters.DuplicateTransportParameterException) {
				// RFC 9000 §18.1 transport-parameter error — surfaced unchanged for the connection
				// layer to map to TRANSPORT_PARAMETER_ERROR, not to a TLS alert (spec §Edge Cases)
				throw e;
			}
			throw new TlsAlertException(DECODE_ERROR, e.getMessage());
		}
	}

	private static byte[] serialize(TlsHandshakeMessage message) {
		ByteBuf buf = ByteBufPool.allocate(message.encodedLength());
		message.writeTo(buf);
		return buf.asArray(); // asArray recycles the buffer
	}

	private static @Nullable <T extends TlsExtension> T findExtension(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}

	private static boolean isKnownMessageType(int type) {
		return switch (type) {
			case ClientHelloMessage.TYPE, ServerHelloMessage.TYPE, NewSessionTicketMessage.TYPE,
				EncryptedExtensionsMessage.TYPE, CertificateMessage.TYPE, CertificateVerifyMessage.TYPE,
				FinishedMessage.TYPE -> true;
			default -> false;
		};
	}

	private static int uint24(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
	}

	private static CryptoReassembly[] newReassemblyBuffers() {
		CryptoReassembly[] buffers = new CryptoReassembly[EncryptionLevel.values().length];
		for (int i = 0; i < buffers.length; i++) {
			buffers[i] = new CryptoReassembly();
		}
		return buffers;
	}
}
