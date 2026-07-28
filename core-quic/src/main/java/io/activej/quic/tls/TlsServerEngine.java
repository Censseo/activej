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
import io.activej.quic.tls.CertificateMessage.CertificateEntry;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
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
 * The server-side TLS 1.3 handshake engine for the QUIC profile (data-model.md, US3):
 * <pre>
 * START ──CH──► [validate: versions, suites, groups, ALPN h3, qtp present]
 *               ──emit SH (INITIAL level) + EE+Cert+CV+Fin (HANDSHAKE level)──► WAIT_FIN
 *                                                                               │ Fin ok ──► CONNECTED
 *                                                                               └ Fin bad ⇒ decrypt_error
 * CONNECTED: any further handshake message ⇒ unexpected_message
 * </pre>
 * Negotiation is RFC 8446 restricted by RFC 9001 §8: TLS 1.3 only ({@code protocol_version}),
 * client-preference cipher-suite selection, server-preference key-share group (x25519 before
 * secp256r1), ALPN {@code h3} mandatory ({@code no_application_protocol}), mandatory
 * {@code quic_transport_parameters} ({@code missing_extension}), and never HelloRetryRequest —
 * no usable key share is a {@code handshake_failure} (FR-014, clarification Q1).
 * <p>
 * Key installation fires per FR-005/FR-013: the Handshake-level pair rides with the server
 * flight, the 1-RTT pair only after the client's Finished verifies (constant-time,
 * {@code MessageDigest.isEqual}). Handshake-phase state (transcript, handshake traffic
 * secrets, key schedule) is shed at completion; the application traffic secrets are retained
 * for key-update readiness (FR-005, spec §Data &amp; State).
 * <p>
 * The slf4j DEBUG trace (FR-020) prints the message flow and transcript-hash digests only —
 * never keys, shared secrets or traffic secrets (SI-6).
 */
public final class TlsServerEngine implements TlsEngine {
	private static final Logger logger = LoggerFactory.getLogger(TlsServerEngine.class);

	private static final String ALPN_H3 = "h3";

	/** Server key-share group preference (RFC 8446 §4.2.8): x25519 first — the interop default. */
	private static final NamedGroup[] GROUP_PREFERENCE = {NamedGroup.X25519, NamedGroup.SECP256R1};

	/** RFC 8446 §4.1.3 downgrade sentinels, rejected in any ClientHello random (data-model validation table). */
	private static final byte[] DOWNGRADE_SENTINEL_TLS_1_2 = {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x01};
	private static final byte[] DOWNGRADE_SENTINEL_TLS_1_1 = {0x44, 0x4F, 0x57, 0x4E, 0x47, 0x52, 0x44, 0x00};

	private enum State {START, WAIT_FINISHED, CONNECTED, FAILED}

	private final TlsServerIdentity identity;
	private final QuicTransportParameters localTransportParameters;
	private final SecureRandom secureRandom;
	private final Function<NamedGroup, KeyPair> ephemeralKeySource;
	private final int maxHandshakeMessageSize;

	/** Per-level reassembly of split/coalesced CRYPTO bytes (spec §Edge Cases), bounded by the message size bound. */
	private final CryptoReassembly[] pending = newReassemblyBuffers();

	private final TranscriptHash transcript = new TranscriptHash();

	private State state = State.START;
	private @Nullable QuicTransportParameters peerTransportParameters;
	private @Nullable String serverNameIndication;

	// handshake-phase state — shed at completion (spec §Data & State)
	private @Nullable TlsCipherSuite suite;
	private @Nullable TlsKeySchedule keySchedule;
	private byte @Nullable [] clientHandshakeTrafficSecret;
	private byte @Nullable [] serverHandshakeTrafficSecret;
	private byte @Nullable [] serverFinishedTranscriptHash;

	// retained past completion for key-update readiness (FR-005) — never logged
	private byte @Nullable [] clientApplicationTrafficSecret;
	private byte @Nullable [] serverApplicationTrafficSecret;

	TlsServerEngine(TlsServerConfig config) {
		this.identity = config.identity();
		this.localTransportParameters = config.localTransportParameters();
		this.secureRandom = config.secureRandom();
		this.ephemeralKeySource = config.ephemeralKeySource();
		this.maxHandshakeMessageSize = config.maxHandshakeMessageSize().toInt();
	}

	@Override
	public TlsEngineResult consume(EncryptionLevel level, ByteBuf cryptoBytes)
			throws TlsAlertException, MalformedDataException {
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
					throw new TlsAlertException(UNEXPECTED_MESSAGE, "Unexpected handshake message type: " + type);
				}
				byte[] messageBytes = Arrays.copyOfRange(data, offset, offset + 4 + bodyLength);
				completed |= process(level, parse(messageBytes), messageBytes, cryptoToSend, installations);
				offset += 4 + bodyLength;
			}
			reassembly.discardPrefix(offset);
		} catch (TlsAlertException | MalformedDataException | RuntimeException e) {
			failTerminally(cryptoToSend);
			throw e;
		}
		if (completed) {
			return TlsEngineResult.complete(cryptoToSend, installations, ALPN_H3, peerTransportParameters);
		}
		return installations.isEmpty() && cryptoToSend.isEmpty()
			? TlsEngineResult.empty()
			: TlsEngineResult.of(cryptoToSend, installations);
	}

	// ---- state machine ----

	/**
	 * Processes one complete message. Returns {@code true} when this message completed the
	 * handshake (the client's Finished) — the result surface is assembled by {@link #consume}.
	 */
	private boolean process(EncryptionLevel level, TlsHandshakeMessage message, byte[] messageBytes,
			Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> installations)
			throws TlsAlertException {
		return switch (state) {
			case START -> {
				if (level != EncryptionLevel.INITIAL || !(message instanceof ClientHelloMessage clientHello)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected a ClientHello on the INITIAL-level CRYPTO stream, got message type " + message.type() +
						" on " + level);
				}
				processClientHello(clientHello, messageBytes, cryptoToSend, installations);
				state = State.WAIT_FINISHED;
				yield false;
			}
			case WAIT_FINISHED -> {
				if (level != EncryptionLevel.HANDSHAKE || !(message instanceof FinishedMessage finished)) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Expected the client Finished on the HANDSHAKE-level CRYPTO stream, got message type " +
						message.type() + " on " + level);
				}
				processClientFinished(finished, messageBytes, installations);
				state = State.CONNECTED;
				yield true;
			}
			case CONNECTED -> {
				if (level != EncryptionLevel.ONE_RTT) {
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Post-handshake messages arrive on the ONE_RTT-level CRYPTO stream, got " + level);
				}
				if (!(message instanceof NewSessionTicketMessage)) {
					// covers TLS KeyUpdate too — forbidden in QUIC (RFC 9001 §6)
					throw new TlsAlertException(UNEXPECTED_MESSAGE,
						"Unexpected post-handshake message type " + message.type() + " (RFC 9001 §6)");
				}
				// FR-015: NewSessionTicket is parsed structurally and discarded — no resumption state
				logger.debug("Discarding a NewSessionTicket from the client (FR-015)");
				yield false;
			}
			case FAILED -> throw new IllegalStateException("The handshake has already failed terminally");
		};
	}

	private void processClientHello(ClientHelloMessage clientHello, byte[] clientHelloBytes,
			Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> installations) throws TlsAlertException {
		transcript.update(clientHelloBytes);
		logger.debug("← ClientHello ({} bytes)", clientHelloBytes.length);

		negotiateVersion(clientHello);
		checkDowngradeSentinels(clientHello.random);
		TlsCipherSuite selectedSuite = negotiateCipherSuite(clientHello);
		KeyShareEntry selectedShare = negotiateKeyShare(clientHello);
		negotiateAlpn(clientHello);
		negotiateTransportParameters(clientHello);
		SignatureScheme scheme = negotiateSignatureScheme(clientHello);
		recordServerName(clientHello);

		transcript.bindCipherSuite(selectedSuite);
		suite = selectedSuite;

		// --- ServerHello at the INITIAL level (data-model level mapping) ---
		NamedGroup group = selectedShare.namedGroup();
		assert group != null; // negotiateKeyShare only returns known-group shares
		KeyPair ephemeral = ephemeralKeySource.apply(group);
		byte[] sharedSecret;
		try {
			sharedSecret = TlsKeyExchanges.agree(group, ephemeral.getPrivate(), selectedShare.keyExchange);
		} finally {
			// the ephemeral private key has done its job — destroy it whether or not the agreement succeeded
			TlsKeyExchanges.destroyQuietly(ephemeral.getPrivate());
		}
		byte[] serverRandom = new byte[32];
		secureRandom.nextBytes(serverRandom);
		ServerHelloMessage serverHello = new ServerHelloMessage(
			ServerHelloMessage.LEGACY_VERSION, serverRandom, clientHello.legacySessionId,
			selectedSuite.code(), 0,
			List.of(
				SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
				KeyShareExt.ofSelectedShare(new KeyShareEntry(
					group.code(), TlsKeyExchanges.encodePublicKey(group, ephemeral.getPublic())))));
		byte[] serverHelloBytes = serialize(serverHello);
		transcript.update(serverHelloBytes);
		logger.debug("→ ServerHello: suite {}, group {}", selectedSuite, group);

		keySchedule = TlsKeySchedule.start(selectedSuite);
		keySchedule.mixEcdhe(sharedSecret);
		Arrays.fill(sharedSecret, (byte) 0);
		byte[] clientHelloServerHelloHash = transcript.hash();
		if (logger.isDebugEnabled()) {
			logger.debug("CH..SH transcript hash: {}", HexFormat.of().formatHex(clientHelloServerHelloHash));
		}
		clientHandshakeTrafficSecret = keySchedule.clientHandshakeTrafficSecret(clientHelloServerHelloHash);
		serverHandshakeTrafficSecret = keySchedule.serverHandshakeTrafficSecret(clientHelloServerHelloHash);
		installations.add(new KeyInstallation(EncryptionLevel.HANDSHAKE, new TlsKeys(
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), clientHandshakeTrafficSecret),
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), serverHandshakeTrafficSecret))));
		keySchedule.deriveMasterSecret();
		ByteBuf initialFlight = ByteBufPool.allocate(serverHelloBytes.length);
		initialFlight.put(serverHelloBytes);
		cryptoToSend.put(EncryptionLevel.INITIAL, initialFlight);

		// --- EE + Certificate + CertificateVerify + Finished at the HANDSHAKE level ---
		EncryptedExtensionsMessage encryptedExtensions = new EncryptedExtensionsMessage(List.of(
			new AlpnExt(List.of(ALPN_H3)),
			new QuicTransportParametersExt(localTransportParameters)));
		byte[] eeBytes = serialize(encryptedExtensions);
		transcript.update(eeBytes);

		List<CertificateEntry> entries = new ArrayList<>();
		try {
			for (X509Certificate certificate : identity.chain()) {
				entries.add(new CertificateEntry(certificate.getEncoded(), List.of()));
			}
		} catch (CertificateEncodingException e) {
			throw new IllegalStateException("The configured identity chain cannot be DER-encoded", e);
		}
		byte[] certificateBytes = serialize(new CertificateMessage(new byte[0], entries));
		transcript.update(certificateBytes);

		byte[] signature = TlsSignatures.sign(scheme, identity.privateKey(),
			TlsSignatures.certificateVerifyContent(true, transcript.hash()));
		byte[] certificateVerifyBytes = serialize(new CertificateVerifyMessage(scheme.code(), signature));
		transcript.update(certificateVerifyBytes);
		logger.debug("→ EncryptedExtensions + Certificate + CertificateVerify: scheme {}", scheme);

		byte[] serverFinishedKey = keySchedule.finishedKey(serverHandshakeTrafficSecret);
		byte[] serverVerifyData = keySchedule.verifyData(serverFinishedKey, transcript.hash());
		Arrays.fill(serverFinishedKey, (byte) 0);
		byte[] serverFinishedBytes = serialize(new FinishedMessage(serverVerifyData));
		transcript.update(serverFinishedBytes);
		logger.debug("→ Finished");

		serverFinishedTranscriptHash = transcript.hash();
		clientApplicationTrafficSecret = keySchedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
		serverApplicationTrafficSecret = keySchedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);

		ByteBuf handshakeFlight = ByteBufPool.allocate(
			eeBytes.length + certificateBytes.length + certificateVerifyBytes.length + serverFinishedBytes.length);
		handshakeFlight.put(eeBytes);
		handshakeFlight.put(certificateBytes);
		handshakeFlight.put(certificateVerifyBytes);
		handshakeFlight.put(serverFinishedBytes);
		cryptoToSend.put(EncryptionLevel.HANDSHAKE, handshakeFlight);
	}

	private void processClientFinished(FinishedMessage finished, byte[] clientFinishedBytes,
			List<KeyInstallation> installations) throws TlsAlertException {
		TlsKeySchedule schedule = keySchedule;
		TlsCipherSuite selectedSuite = suite;
		byte[] clientHandshakeTraffic = clientHandshakeTrafficSecret;
		byte[] serverFinishedHash = serverFinishedTranscriptHash;
		assert schedule != null && selectedSuite != null && clientHandshakeTraffic != null && serverFinishedHash != null;

		byte[] clientFinishedKey = schedule.finishedKey(clientHandshakeTraffic);
		byte[] expectedVerifyData = schedule.verifyData(clientFinishedKey, serverFinishedHash);
		Arrays.fill(clientFinishedKey, (byte) 0);
		// FR-016: constant-time comparison
		if (!MessageDigest.isEqual(expectedVerifyData, finished.verifyData)) {
			throw new TlsAlertException(DECRYPT_ERROR, "Client Finished verify_data mismatch");
		}
		transcript.update(clientFinishedBytes);
		logger.debug("← client Finished verified; handshake complete (ALPN {})", ALPN_H3);
		if (logger.isDebugEnabled()) {
			logger.debug("CH..client Finished transcript hash: {}", HexFormat.of().formatHex(transcript.hash()));
		}

		installations.add(new KeyInstallation(EncryptionLevel.ONE_RTT, new TlsKeys(
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), clientApplicationTrafficSecret),
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), serverApplicationTrafficSecret))));

		// shed handshake-phase state (spec §Data & State); application traffic secrets stay for FR-005
		Arrays.fill(clientHandshakeTraffic, (byte) 0);
		Arrays.fill(serverHandshakeTrafficSecret, (byte) 0);
		clientHandshakeTrafficSecret = null;
		serverHandshakeTrafficSecret = null;
		serverFinishedTranscriptHash = null;
		schedule.destroy(); // zeroes the master secret before the schedule is dropped
		keySchedule = null;
		suite = null;
	}

	// ---- ClientHello negotiation (data-model.md order: versions, suites, groups, ALPN, qtp) ----

	private static void negotiateVersion(ClientHelloMessage clientHello) throws TlsAlertException {
		SupportedVersionsExt versions = findExtension(clientHello.extensions, SupportedVersionsExt.class);
		if (versions == null || versions.selectedForm) {
			throw new TlsAlertException(MISSING_EXTENSION, "ClientHello carries no supported_versions extension (RFC 8446 §4.2.1)");
		}
		boolean tls13Offered = false;
		for (int version : versions.versions) {
			if (version == SupportedVersionsExt.TLS_1_3) {
				tls13Offered = true;
			} else if (!isGrease(version)) {
				throw new TlsAlertException(PROTOCOL_VERSION,
					"ClientHello offers TLS version 0x" + Integer.toHexString(version) +
					" — the QUIC profile negotiates TLS 1.3 only (RFC 9001 §8.2)");
			}
		}
		if (!tls13Offered) {
			throw new TlsAlertException(PROTOCOL_VERSION, "ClientHello does not offer TLS 1.3 (RFC 9001 §8.2)");
		}
	}

	private static void checkDowngradeSentinels(byte[] clientRandom) throws TlsAlertException {
		byte[] suffix = Arrays.copyOfRange(clientRandom, clientRandom.length - 8, clientRandom.length);
		if (Arrays.equals(suffix, DOWNGRADE_SENTINEL_TLS_1_2) || Arrays.equals(suffix, DOWNGRADE_SENTINEL_TLS_1_1)) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ClientHello random carries an RFC 8446 §4.1.3 downgrade sentinel");
		}
	}

	private static TlsCipherSuite negotiateCipherSuite(ClientHelloMessage clientHello) throws TlsAlertException {
		for (int code : clientHello.cipherSuites) {
			TlsCipherSuite suite = TlsCipherSuite.of(code);
			if (suite != null) {
				return suite; // client preference order (RFC 8446 §4.1.3 allows either; we honor the client's)
			}
		}
		throw new TlsAlertException(HANDSHAKE_FAILURE, "No common TLS 1.3 cipher suite (FR-007)");
	}

	private static KeyShareEntry negotiateKeyShare(ClientHelloMessage clientHello) throws TlsAlertException {
		KeyShareExt keyShare = findExtension(clientHello.extensions, KeyShareExt.class);
		if (keyShare == null) {
			throw new TlsAlertException(MISSING_EXTENSION, "ClientHello carries no key_share extension (RFC 8446 §4.2.8)");
		}
		if (keyShare.clientShares == null) {
			throw new TlsAlertException(DECODE_ERROR,
				"key_share in a ClientHello must be the client_shares list form (RFC 8446 §4.2.8)");
		}
		SupportedGroupsExt supportedGroups = findExtension(clientHello.extensions, SupportedGroupsExt.class);
		if (supportedGroups == null) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"ClientHello offers key_share without supported_groups (RFC 8446 §4.2.7)");
		}
		// RFC 8446 §4.2.8: a client MUST NOT offer shares for groups absent from its
		// supported_groups — cross-checked before any selection (GREASE shares are not groups)
		for (KeyShareEntry share : keyShare.clientShares) {
			if (!isGrease(share.groupCode) && !isAdvertised(supportedGroups, share.groupCode)) {
				throw new TlsAlertException(ILLEGAL_PARAMETER,
					"ClientHello key_share offers group 0x" + Integer.toHexString(share.groupCode) +
					" that is absent from its supported_groups (RFC 8446 §4.2.8)");
			}
		}
		for (NamedGroup group : GROUP_PREFERENCE) {
			for (KeyShareEntry share : keyShare.clientShares) {
				if (share.groupCode == group.code()) {
					return share;
				}
			}
		}
		// clarification Q1 / FR-014: never send a HelloRetryRequest — fail clearly instead
		throw new TlsAlertException(HANDSHAKE_FAILURE,
			"No usable key share (x25519/secp256r1) — HelloRetryRequest is not supported in this profile");
	}

	private static boolean isAdvertised(SupportedGroupsExt supportedGroups, int groupCode) {
		for (int code : supportedGroups.groupCodes) {
			if (code == groupCode) {
				return true;
			}
		}
		return false;
	}

	private static void negotiateAlpn(ClientHelloMessage clientHello) throws TlsAlertException {
		AlpnExt alpn = findExtension(clientHello.extensions, AlpnExt.class);
		if (alpn == null || !alpn.protocols.contains(ALPN_H3)) {
			throw new TlsAlertException(NO_APPLICATION_PROTOCOL,
				"No 'h3' in the offered ALPN protocols (FR-012, RFC 9001 §8.1)");
		}
	}

	private void negotiateTransportParameters(ClientHelloMessage clientHello) throws TlsAlertException {
		QuicTransportParametersExt transportParameters = findExtension(clientHello.extensions, QuicTransportParametersExt.class);
		if (transportParameters == null) {
			throw new TlsAlertException(MISSING_EXTENSION,
				"ClientHello carries no quic_transport_parameters extension (mandatory in QUIC, RFC 9001 §8.2)");
		}
		peerTransportParameters = transportParameters.parameters;
	}

	private SignatureScheme negotiateSignatureScheme(ClientHelloMessage clientHello) throws TlsAlertException {
		SignatureAlgorithmsExt signatureAlgorithms = findExtension(clientHello.extensions, SignatureAlgorithmsExt.class);
		if (signatureAlgorithms == null) {
			throw new TlsAlertException(MISSING_EXTENSION,
				"ClientHello carries no signature_algorithms extension (RFC 8446 §4.2.3)");
		}
		for (SignatureScheme scheme : identity.signatureSchemes()) {
			for (int code : signatureAlgorithms.schemeCodes) {
				if (code == scheme.code()) {
					return scheme;
				}
			}
		}
		throw new TlsAlertException(HANDSHAKE_FAILURE,
			"No common signature scheme for CertificateVerify with the configured identity (FR-010)");
	}

	private void recordServerName(ClientHelloMessage clientHello) {
		ServerNameExt serverName = findExtension(clientHello.extensions, ServerNameExt.class);
		if (serverName != null && serverName.hostName != null) {
			// FR-019: recorded for the caller; no multi-certificate selection in this feature
			serverNameIndication = serverName.hostName;
			logger.debug("ClientHello SNI: {}", serverName.hostName);
		}
	}

	/**
	 * The SNI host_name the client offered (FR-019), recorded for the caller — {@code null}
	 * when the ClientHello carried none (or has not been processed yet). No multi-certificate
	 * selection is performed on it in this feature.
	 */
	public @Nullable String serverNameIndication() {
		return serverNameIndication;
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
	}

	private void zeroSecrets() {
		if (clientHandshakeTrafficSecret != null) Arrays.fill(clientHandshakeTrafficSecret, (byte) 0);
		if (serverHandshakeTrafficSecret != null) Arrays.fill(serverHandshakeTrafficSecret, (byte) 0);
		if (clientApplicationTrafficSecret != null) Arrays.fill(clientApplicationTrafficSecret, (byte) 0);
		if (serverApplicationTrafficSecret != null) Arrays.fill(serverApplicationTrafficSecret, (byte) 0);
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

	/** RFC 8701 GREASE values: 0x?A?A with both bytes equal — tolerated everywhere lists are parsed. */
	private static boolean isGrease(int value) {
		return (value & 0x0F0F) == 0x0A0A && (value >>> 8) == (value & 0xFF);
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
