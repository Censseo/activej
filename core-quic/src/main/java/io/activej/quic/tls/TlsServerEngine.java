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
import java.util.function.LongSupplier;

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

	/**
	 * The origin a ticket is bound to when the ClientHello carried no SNI. RFC 6066 §3 forbids a
	 * wildcard in a {@code host_name}, so this can never collide with a real one.
	 */
	private static final String NO_SERVER_NAME = "*";

	/**
	 * How many offered PSK identities are considered before the rest are ignored. Bounds the AEAD
	 * work one ClientHello can buy; a plain constant rather than an {@code ApplicationSettings} key,
	 * since no deployment has a reason to raise it (RFC 8446 §4.2.11 expects a handful).
	 */
	private static final int MAX_OFFERED_PSK_IDENTITIES = 4;

	/** {@code ticket_lifetime} is a uint32 of seconds capped at seven days (RFC 8446 §4.6.1). */
	private static final long MAX_TICKET_LIFETIME_SECONDS = 604_800L;

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

	// resumption configuration (spec FR-041..FR-047); no sealing keys ⇒ phase-1 behaviour throughout
	private final @Nullable QuicTicketKeys ticketKeys;
	private final long sessionTicketLifetimeMillis;
	private final long ticketAgeToleranceMillis;
	private final int sessionTicketsPerHandshake;
	private final boolean earlyDataEnabled;
	private final @Nullable QuicReplayGuard replayGuard;
	private final LongSupplier currentTimeMillis;

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

	// resumption outcome. acceptedPskIdentity >= 0 means this handshake resumed; earlyDataOffered
	// records that the accepted PSK also carried early_data, and earlyDataAccepted that this engine
	// answered it — which is the whole of the RFC 8446 §4.2.10 signal, since a refusal is an omission.
	private int acceptedPskIdentity = -1;
	private boolean earlyDataOffered;
	private boolean earlyDataAccepted;
	private boolean earlyDataDecisionPending;
	private byte @Nullable [] clientEarlyTrafficSecret;
	private long nextTicketNonce;

	TlsServerEngine(TlsServerConfig config) {
		this.identity = config.identity();
		this.localTransportParameters = config.localTransportParameters();
		this.secureRandom = config.secureRandom();
		this.ephemeralKeySource = config.ephemeralKeySource();
		this.maxHandshakeMessageSize = config.maxHandshakeMessageSize().toInt();
		this.ticketKeys = config.ticketKeys();
		this.sessionTicketLifetimeMillis = config.sessionTicketLifetimeMillis();
		this.ticketAgeToleranceMillis = config.ticketAgeToleranceMillis();
		this.sessionTicketsPerHandshake = config.sessionTicketsPerHandshake();
		this.earlyDataEnabled = config.earlyDataEnabled();
		this.replayGuard = config.replayGuard();
		this.currentTimeMillis = config.currentTimeMillis();
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
		if (!level.hasCryptoStream()) {
			// A 0-RTT packet may not carry a CRYPTO frame (RFC 9000 §12.5), so the level has no
			// reassembly slot and reaching here is a caller bug rather than a wire condition. The
			// input has already been recycled above, so the ownership contract holds on this path too.
			throw new IllegalArgumentException("No CRYPTO stream at encryption level " + level);
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
		boolean earlyDataDecision = takeEarlyDataDecision();
		boolean resumed = acceptedPskIdentity >= 0;
		if (completed) {
			// A server never retains what it issues — a ticket is sealed state, not a dictionary key —
			// so issuedTickets stays empty on this side (see TlsEngineResult.issuedTickets()).
			return TlsEngineResult.complete(cryptoToSend, installations, ALPN_H3, peerTransportParameters,
				List.of(), earlyDataDecision, resumed);
		}
		return installations.isEmpty() && cryptoToSend.isEmpty() && !earlyDataDecision && !resumed
			? TlsEngineResult.empty()
			: TlsEngineResult.of(cryptoToSend, installations, List.of(), earlyDataDecision, resumed);
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
				processClientFinished(finished, messageBytes, cryptoToSend, installations);
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
		recordServerName(clientHello);

		transcript.bindCipherSuite(selectedSuite);
		suite = selectedSuite;

		// one instant for the whole resumption decision: the ticket's expiry, its reported age and the
		// replay register must not be judged against three different readings of the clock
		long now = currentTimeMillis.getAsLong();
		AcceptedPsk accepted = selectPreSharedKey(clientHello, clientHelloBytes, selectedSuite, now);
		acceptEarlyData(accepted, clientHelloBytes, selectedSuite, installations, now);
		// RFC 8446 §4.2.3 makes signature_algorithms mandatory only when the client offers
		// certificate authentication, and a PSK-authenticated server signs nothing — so the
		// negotiation moves below PSK selection rather than staying unconditional.
		SignatureScheme scheme = accepted == null ? negotiateSignatureScheme(clientHello) : null;

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
		List<TlsExtension> serverHelloExtensions = new ArrayList<>(List.of(
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
			KeyShareExt.ofSelectedShare(new KeyShareEntry(
				group.code(), TlsKeyExchanges.encodePublicKey(group, ephemeral.getPublic())))));
		if (accepted != null) {
			serverHelloExtensions.add(PreSharedKeyExt.ofSelectedIdentity(accepted.selectedIdentity()));
		}
		ServerHelloMessage serverHello = new ServerHelloMessage(
			ServerHelloMessage.LEGACY_VERSION, serverRandom, clientHello.legacySessionId,
			selectedSuite.code(), 0, serverHelloExtensions);
		byte[] serverHelloBytes = serialize(serverHello);
		transcript.update(serverHelloBytes);
		logger.debug("→ ServerHello: suite {}, group {}, resumed {}", selectedSuite, group, accepted != null);

		// selectPreSharedKey already seeded the schedule with the resumption PSK; mixEcdhe runs
		// either way, so psk_dhe_ke is the only mode and resumption never loses forward secrecy.
		if (keySchedule == null) {
			keySchedule = TlsKeySchedule.start(selectedSuite);
		}
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

		// --- EE + [Certificate + CertificateVerify] + Finished at the HANDSHAKE level ---
		List<TlsExtension> encryptedExtensionsList = new ArrayList<>(List.of(
			new AlpnExt(List.of(ALPN_H3)),
			new QuicTransportParametersExt(localTransportParameters)));
		if (earlyDataAccepted) {
			// RFC 8446 §4.2.10: the empty form. Echoing it is the acceptance; omitting it is the whole
			// of the refusal, which is why there is no negative branch anywhere in this engine.
			encryptedExtensionsList.add(EarlyDataExt.empty());
		}
		EncryptedExtensionsMessage encryptedExtensions = new EncryptedExtensionsMessage(encryptedExtensionsList);
		byte[] eeBytes = serialize(encryptedExtensions);
		transcript.update(eeBytes);

		List<byte[]> flightMessages = new ArrayList<>();
		flightMessages.add(eeBytes);
		if (accepted == null) {
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
			flightMessages.add(certificateBytes);

			byte[] signature = TlsSignatures.sign(scheme, identity.privateKey(),
				TlsSignatures.certificateVerifyContent(true, transcript.hash()));
			byte[] certificateVerifyBytes = serialize(new CertificateVerifyMessage(scheme.code(), signature));
			transcript.update(certificateVerifyBytes);
			flightMessages.add(certificateVerifyBytes);
			logger.debug("→ EncryptedExtensions + Certificate + CertificateVerify: scheme {}", scheme);
		} else {
			// RFC 8446 §4.4.2: a PSK-authenticated server sends neither Certificate nor CertificateVerify
			logger.debug("→ EncryptedExtensions (PSK-authenticated flight)");
		}

		byte[] serverFinishedKey = keySchedule.finishedKey(serverHandshakeTrafficSecret);
		byte[] serverVerifyData = keySchedule.verifyData(serverFinishedKey, transcript.hash());
		Arrays.fill(serverFinishedKey, (byte) 0);
		byte[] serverFinishedBytes = serialize(new FinishedMessage(serverVerifyData));
		transcript.update(serverFinishedBytes);
		flightMessages.add(serverFinishedBytes);
		logger.debug("→ Finished");

		serverFinishedTranscriptHash = transcript.hash();
		clientApplicationTrafficSecret = keySchedule.clientApplicationTrafficSecret0(serverFinishedTranscriptHash);
		serverApplicationTrafficSecret = keySchedule.serverApplicationTrafficSecret0(serverFinishedTranscriptHash);

		cryptoToSend.put(EncryptionLevel.HANDSHAKE, concatenate(flightMessages));
	}

	/**
	 * Selects a PSK from the ClientHello's {@code pre_shared_key} offer, or returns {@code null} for a
	 * full handshake.
	 * <p>
	 * <b>Selection precedes verification, and that split is the whole design.</b> Everything that
	 * stops the server selecting a PSK — a client offering only {@code psk_ke}, no configured sealing
	 * keys, a ticket that cannot be opened, one that is expired, one whose reported age is outside
	 * tolerance, one issued for another origin or another suite — returns {@code null} quietly and
	 * costs the client nothing but a full handshake (spec FR-045, FR-047), and no branch reports which
	 * check refused it. Only a PSK that <i>was</i> selected and then failed its binder is fatal
	 * (RFC 8446 §4.2.11.2): that is tampering evidence, not an unusable ticket.
	 * <p>
	 * The three loud checks above the loop are different in kind: they are a malformed offer rather
	 * than an unusable one, and they fire whether or not the server has sealing keys at all.
	 */
	private @Nullable AcceptedPsk selectPreSharedKey(ClientHelloMessage clientHello, byte[] clientHelloBytes,
			TlsCipherSuite selectedSuite, long now) throws TlsAlertException {
		List<TlsExtension> extensions = clientHello.extensions;
		PreSharedKeyExt offer = findExtension(extensions, PreSharedKeyExt.class);
		if (offer == null) return null;

		if (extensions.get(extensions.size() - 1) != offer) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"pre_shared_key must be the last ClientHello extension (RFC 8446 §4.2.11)");
		}
		if (!offer.isOffer()) {
			throw new TlsAlertException(ILLEGAL_PARAMETER,
				"pre_shared_key in a ClientHello must be the OfferedPsks form (RFC 8446 §4.2.11)");
		}
		PskKeyExchangeModesExt modes = findExtension(extensions, PskKeyExchangeModesExt.class);
		if (modes == null) {
			throw new TlsAlertException(MISSING_EXTENSION,
				"pre_shared_key offered without psk_key_exchange_modes (RFC 8446 §4.2.9)");
		}

		if (!offersPskDheKe(modes)) return null; // FR-046: psk_ke is never accepted
		if (ticketKeys == null) return null;

		String origin = serverNameIndication != null ? serverNameIndication : NO_SERVER_NAME;
		int considered = Math.min(offer.identities.size(), MAX_OFFERED_PSK_IDENTITIES);
		for (int i = 0; i < considered; i++) {
			PreSharedKeyExt.PskIdentity offered = offer.identities.get(i);
			QuicSessionTicket ticket = ticketKeys.open(offered.identity());
			if (ticket == null) continue;
			if (!ticket.isFor(origin, ALPN_H3)) continue;
			if (ticket.cipherSuite() != selectedSuite) continue;
			if (ticket.isExpiredAt(now)) continue;
			long reportedAge = (offered.obfuscatedTicketAge() - ticket.ticketAgeAdd()) & 0xFFFFFFFFL;
			if (Math.abs(reportedAge - ticket.ageMillisAt(now)) > ticketAgeToleranceMillis) continue;

			// --- selected: from here a binder failure is fatal, never a fallback ---
			// the schedule is assigned to the field before the binder is computed, so failTerminally
			// destroys the PSK-seeded early secret on the rejection path too
			keySchedule = TlsKeySchedule.startWithPsk(selectedSuite, ticket.resumptionSecret());
			byte[] binderKey = keySchedule.resumptionBinderKey();
			byte[] expected = keySchedule.pskBinder(binderKey, TlsPskBinders.truncatedClientHelloHash(
				selectedSuite, clientHelloBytes, offer.bindersSectionLength()));
			Arrays.fill(binderKey, (byte) 0);
			if (!TlsPskBinders.verifyBinder(expected, offer.binders.get(i))) {
				throw new TlsAlertException(DECRYPT_ERROR, "PSK binder verification failed (RFC 8446 §4.2.11.2)");
			}

			acceptedPskIdentity = i;
			// RFC 8446 §4.2.10: early data may only ride the first offered identity
			earlyDataOffered = i == 0 && findExtension(extensions, EarlyDataExt.class) != null;
			// the replay register (spec FR-069, RFC 8446 §8) is deliberately not consulted here: a
			// ticket is single-use for *early data*, not for resumption, so spending it on a PSK that
			// then turns out to carry no early-data offer would refuse the next connection's 0-RTT for
			// nothing. acceptEarlyData consults it, at the point the grant is actually made.
			return new AcceptedPsk(i, ticket);
		}
		return null;
	}

	/**
	 * Answers the {@code early_data} offer that rode the accepted pre-shared key: derives
	 * {@code client_early_traffic_secret} over the ClientHello and installs the RFC 9001 §4.1.4 0-RTT
	 * packet-protection keys (spec FR-049).
	 * <p>
	 * The installation is <b>one-directional</b> ({@link TlsKeys#ofClientOnly}) and that is the point:
	 * a server may decrypt 0-RTT packets and may never send one (spec FR-052, RFC 9001 §4.6.1). The
	 * connection layer maps a client-only installation onto its receive slot, so the send slot at this
	 * level is left empty by construction rather than by a rule that could be forgotten.
	 * <p>
	 * Ordering is not free: the key schedule must still be in its EARLY state, which it is between
	 * {@link #selectPreSharedKey} seeding it with the resumption PSK and {@code mixEcdhe} advancing it.
	 * <p>
	 * Off unless {@link TlsServerConfig#earlyDataEnabled()}, which defaults to {@code false} — so a
	 * server that has not opted in resumes sessions in 1-RTT and stays byte-identical to phase 1 on the
	 * wire. Refusing early data is never a handshake failure (spec FR-048).
	 * <p>
	 * <b>The replay register is consulted here</b> (spec FR-069, RFC 8446 §8), on a PSK that has already
	 * been accepted, and the placement is the whole point: a ticket presented a second time loses its
	 * <i>early data</i> and nothing else. The pre-shared key still authenticates the connection, the
	 * certificate flight is still skipped, and the handshake still completes — the same graceful shape
	 * an unopenable or expired ticket already had, with "already used for early data" simply added to
	 * the list of reasons early data does not happen. Failing the handshake instead would hand an
	 * attacker a denial-of-service primitive out of the defence itself.
	 * <p>
	 * The register is judged at the same instant the rest of the resumption decision was, which is why
	 * {@code nowMillis} is threaded down from {@link #processClientHello} rather than read again here.
	 * A null register is unreachable with early data on — {@link TlsServerConfig.Builder#build()}
	 * refuses that pair — and the check remains so that this method is total on its own field.
	 */
	private void acceptEarlyData(@Nullable AcceptedPsk accepted, byte[] clientHelloBytes,
			TlsCipherSuite selectedSuite, List<KeyInstallation> installations, long nowMillis) {
		if (accepted == null || !earlyDataOffered || !earlyDataEnabled) return;
		if (replayGuard != null && !replayGuard.tryConsume(accepted.ticket(), nowMillis)) {
			// the condition, never which ticket it was (SI-6); the register's own counters say which
			// of replay, capacity or expiry refused it
			logger.debug("→ refusing early data: the register did not grant this ticket a use (RFC 8446 §8)");
			return;
		}
		assert keySchedule != null; // selectPreSharedKey seeded it before returning a non-null result
		earlyDataAccepted = true;
		earlyDataDecisionPending = true;
		clientEarlyTrafficSecret = keySchedule.clientEarlyTrafficSecret(
			TlsPskBinders.clientHelloHash(selectedSuite, clientHelloBytes));
		installations.add(new KeyInstallation(EncryptionLevel.ZERO_RTT, TlsKeys.ofClientOnly(
			QuicKeys.fromTrafficSecret(selectedSuite.quicCipherSuite(), clientEarlyTrafficSecret))));
		logger.debug("→ accepting early data (RFC 8446 §4.2.10)");
	}

	/**
	 * Reports the early-data decision once and clears it, so a later result cannot repeat a decision
	 * the caller has already latched.
	 */
	private boolean takeEarlyDataDecision() {
		boolean decision = earlyDataDecisionPending;
		earlyDataDecisionPending = false;
		return decision;
	}

	/**
	 * Whether this handshake accepted early data — latched, so it stays {@code true} after
	 * {@link #takeEarlyDataDecision()} has handed the decision over. Package-private: a test target,
	 * never a public accessor, because everything around it is secret material (SI-6).
	 */
	boolean earlyDataAccepted() {
		return earlyDataAccepted;
	}

	private static boolean offersPskDheKe(PskKeyExchangeModesExt modes) {
		for (int mode : modes.modes) {
			if (mode == PskKeyExchangeModesExt.PSK_DHE_KE) return true;
		}
		return false;
	}

	/** A PSK the server selected: the index to echo, and the ticket it was opened from. */
	private record AcceptedPsk(int selectedIdentity, QuicSessionTicket ticket) {
	}

	private void processClientFinished(FinishedMessage finished, byte[] clientFinishedBytes,
			Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> installations) throws TlsAlertException {
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

		// the resumption master secret needs both this transcript and a live master secret, so
		// issuance has to happen before the shedding block below destroys the schedule
		issueSessionTickets(cryptoToSend, selectedSuite);

		// RFC 9001 §4.9.3: the 0-RTT secret has no purpose past the handshake, so it goes with the rest
		// of the handshake-phase state rather than being retained for a reordered packet (deliberate,
		// conservative deviation — a late 0-RTT packet is dropped and the client retransmits in 1-RTT).
		zeroEarlySecret();

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

	/**
	 * Seals and emits {@code sessionTicketsPerHandshake} {@code NewSessionTicket} messages on the
	 * ONE_RTT CRYPTO stream (spec FR-041, RFC 8446 §4.6.1), each advertising the QUIC-mandated
	 * {@code max_early_data_size = 0xffffffff} (RFC 9001 §4.6.1). With no sealing keys configured this
	 * returns before doing anything, which is the phase-1 behaviour byte for byte.
	 * <p>
	 * The whole body is failure-tolerant on purpose: <b>a ticket that cannot be issued must never fail
	 * a handshake that has already completed.</b> The only consequence of giving up here is that the
	 * client's next connection is a full handshake. The log line names the exception type and nothing
	 * else — a ticket, its nonce and its age-add are all secret material (SI-6).
	 */
	private void issueSessionTickets(Map<EncryptionLevel, ByteBuf> cryptoToSend, TlsCipherSuite selectedSuite) {
		QuicTicketKeys keys = ticketKeys;
		if (keys == null || sessionTicketsPerHandshake == 0) return;
		try {
			long lifetimeSeconds = Math.min(sessionTicketLifetimeMillis / 1000, MAX_TICKET_LIFETIME_SECONDS);
			if (lifetimeSeconds == 0) return;
			// the sealed and the advertised lifetime must be the same number, or the server's own
			// expiry check disagrees with the client's
			long lifetimeMillis = lifetimeSeconds * 1000L;
			long now = currentTimeMillis.getAsLong();
			String origin = serverNameIndication != null ? serverNameIndication : NO_SERVER_NAME;

			byte[] resumptionMaster = keySchedule.resumptionMasterSecret(transcript.hash());
			List<byte[]> messages = new ArrayList<>();
			for (int i = 0; i < sessionTicketsPerHandshake; i++) {
				byte[] nonce = eightByteBigEndian(nextTicketNonce++);
				byte[] psk = TlsKeySchedule.resumptionPsk(selectedSuite, resumptionMaster, nonce);
				long ticketAgeAdd = randomUint32();
				QuicSessionTicket ticket = QuicSessionTicket
					.builder(origin, ALPN_H3, selectedSuite, psk)
					.withIssuedAt(now)
					.withLifetime(lifetimeMillis)
					.withTicketAgeAdd(ticketAgeAdd)
					.withTransportParameters(localTransportParameters)
					.build();
				Arrays.fill(psk, (byte) 0);
				messages.add(serialize(new NewSessionTicketMessage(lifetimeSeconds, ticketAgeAdd, nonce,
					keys.seal(ticket, now),
					List.of(EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE)))));
			}
			Arrays.fill(resumptionMaster, (byte) 0);
			cryptoToSend.put(EncryptionLevel.ONE_RTT, concatenate(messages));
			logger.debug("→ {} NewSessionTicket(s)", messages.size());
		} catch (RuntimeException e) {
			logger.warn("Issuing session tickets failed; this connection resumes nothing ({})",
				e.getClass().getSimpleName());
		}
	}

	private long randomUint32() {
		byte[] bytes = new byte[4];
		secureRandom.nextBytes(bytes);
		return ((long) (bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
	}

	private static byte[] eightByteBigEndian(long value) {
		byte[] bytes = new byte[8];
		for (int i = 0; i < 8; i++) {
			bytes[i] = (byte) (value >>> (56 - 8 * i));
		}
		return bytes;
	}

	/**
	 * One flight buffer holding {@code messages} back to back. The messages are built as
	 * {@code byte[]} first and the single {@link ByteBuf} is allocated last, so no partially-filled
	 * buffer can escape on a failure path (DI-1).
	 */
	private static ByteBuf concatenate(List<byte[]> messages) {
		int total = 0;
		for (byte[] message : messages) {
			total += message.length;
		}
		ByteBuf flight = ByteBufPool.allocate(total);
		for (byte[] message : messages) {
			flight.put(message);
		}
		return flight;
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
			// null at the ZERO_RTT slot, which carries no CRYPTO stream.
			if (reassembly != null) reassembly.clear();
		}
		zeroSecrets();
		if (keySchedule != null) {
			keySchedule.destroy(); // zeroes whichever stage secret the schedule still holds
			keySchedule = null;
		}
	}

	private void zeroEarlySecret() {
		byte[] secret = clientEarlyTrafficSecret;
		if (secret != null) {
			Arrays.fill(secret, (byte) 0);
			clientEarlyTrafficSecret = null;
		}
	}

	private void zeroSecrets() {
		zeroEarlySecret();
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

	/**
	 * One {@code ordinal()}-indexed slot per encryption level, but a buffer only for the levels that
	 * actually carry a CRYPTO stream: {@code ZERO_RTT}'s slot stays {@code null} by construction
	 * (research D-5 audit (b)), because RFC 9000 §12.5 forbids a CRYPTO frame in a 0-RTT packet.
	 */
	private static CryptoReassembly[] newReassemblyBuffers() {
		CryptoReassembly[] buffers = new CryptoReassembly[EncryptionLevel.values().length];
		for (EncryptionLevel level : EncryptionLevel.values()) {
			if (level.hasCryptoStream()) buffers[level.ordinal()] = new CryptoReassembly();
		}
		return buffers;
	}
}
