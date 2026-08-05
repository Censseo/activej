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

import io.activej.quic.crypto.Hkdf;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The TLS 1.3 key schedule (RFC 8446 §7.1), driven by the negotiated suite's hash and built
 * entirely on the feature-01 {@link Hkdf} primitives.
 * <p>
 * The schedule is a three-stage chain, advanced explicitly as the handshake proceeds:
 * <pre>
 *   start(suite)            — early secret = Extract(salt = 0, IKM = zero PSK)   [RFC 8446 §7.1]
 *   mixEcdhe(shared)        — handshake secret = Extract(derived, ECDHE shared secret)
 *   deriveMasterSecret()    — master secret = Extract(derived, 0)
 * </pre>
 * Traffic secrets are pure derivations off the current stage's secret and a transcript hash
 * taken at the RFC-defined point; they never advance the chain. Consumed intermediate secrets
 * are zeroed on transition. Secrets are held as {@code byte[]}, cloned on the way out, and are
 * never logged or printed (SI-6).
 * <p>
 * Proven byte-exact against the RFC 8448 §3 published intermediates (SC-002).
 */
public final class TlsKeySchedule {
	private enum State {EARLY, HANDSHAKE, MASTER}

	private final TlsCipherSuite suite;
	private final String hkdfHash;
	private final int hashLength;
	private final byte[] emptyHash;

	private State state = State.EARLY;
	private byte[] earlySecret;
	private byte @Nullable [] handshakeSecret;
	private byte @Nullable [] masterSecret;

	private TlsKeySchedule(TlsCipherSuite suite, byte @Nullable [] psk) {
		this.suite = suite;
		this.hkdfHash = suite.quicCipherSuite().hkdfHash();
		MessageDigest digest = newDigest(suite);
		this.hashLength = digest.getDigestLength();
		this.emptyHash = digest.digest();

		// RFC 8446 §7.1: early secret = Extract(salt = 0, IKM = PSK), with IKM = zeros(HashLen) when
		// no PSK is in play. Hkdf.extract substitutes a zeros(HashLen) salt for an empty one (RFC 5869 §2.2).
		this.earlySecret = Hkdf.extract(hkdfHash, new byte[0], psk != null ? psk : new byte[hashLength]);
	}

	/**
	 * Starts the schedule for the negotiated suite with the all-zero PSK — the full-handshake form
	 * (RFC 8446 §7.1 with IKM = zeros(HashLen)).
	 */
	public static TlsKeySchedule start(TlsCipherSuite suite) {
		return new TlsKeySchedule(suite, null);
	}

	/**
	 * Starts the schedule seeded with a resumption PSK (RFC 8446 §7.1: early secret =
	 * {@code Extract(salt = 0, IKM = psk)}), the branch that makes {@link #resumptionBinderKey()} and
	 * {@link #clientEarlyTrafficSecret(byte[])} meaningful.
	 * <p>
	 * The {@code suite} is the one the ticket was issued under, which the server must select a suite
	 * with the same hash as (RFC 8446 §4.6.1). {@code psk} is <b>secret material</b> and is not
	 * retained: it is consumed into the early secret here.
	 */
	public static TlsKeySchedule startWithPsk(TlsCipherSuite suite, byte[] psk) {
		if (psk.length == 0) {
			throw new IllegalArgumentException("The resumption PSK must not be empty");
		}
		return new TlsKeySchedule(suite, psk);
	}

	/**
	 * The early secret.
	 *
	 * @throws IllegalStateException once the ECDHE secret has been mixed in (the early secret is zeroed)
	 */
	public byte[] earlySecret() {
		checkState(State.EARLY, "the early secret has already been mixed with the ECDHE shared secret");
		return earlySecret.clone();
	}

	/**
	 * The {@code "derived"} intermediate off the early secret (RFC 8446 §7.1: Derive-Secret with
	 * an empty message list) — the salt of the handshake-secret Extract. Exposed for RFC 8448
	 * verification; the same value is recomputed internally by {@link #mixEcdhe(byte[])}.
	 *
	 * @throws IllegalStateException once the ECDHE secret has been mixed in
	 */
	public byte[] derivedSecret() {
		checkState(State.EARLY, "the early secret has already been mixed with the ECDHE shared secret");
		return deriveSecret(earlySecret, "derived", emptyHash);
	}

	/**
	 * {@code binder_key} — Derive-Secret(early secret, {@code "res binder"}, "") (RFC 8446 §7.1), the
	 * base key of the PSK binder HMAC.
	 * <p>
	 * Callable in the EARLY state only: {@link #mixEcdhe(byte[])} zeroes the early secret, so the
	 * binder must be computed before the ServerHello's key share is agreed — which is the natural
	 * order anyway, since the binder is part of the ClientHello.
	 *
	 * @throws IllegalStateException once the ECDHE secret has been mixed in
	 */
	public byte[] resumptionBinderKey() {
		checkState(State.EARLY, "the binder key must be derived before the ECDHE shared secret is mixed in");
		return deriveSecret(earlySecret, "res binder", emptyHash);
	}

	/**
	 * {@code client_early_traffic_secret} — Derive-Secret(early secret, {@code "c e traffic"},
	 * ClientHello) (RFC 8446 §7.1), the secret the 0-RTT packet protection keys are derived from via
	 * RFC 9001 §5.1.
	 * <p>
	 * The transcript hash is over the <b>complete</b> ClientHello, binder included — unlike the
	 * binder's own, which is over the truncated message. Callable in the EARLY state only, for the
	 * same reason as {@link #resumptionBinderKey()}.
	 *
	 * @throws IllegalStateException once the ECDHE secret has been mixed in
	 */
	public byte[] clientEarlyTrafficSecret(byte[] clientHelloTranscriptHash) {
		checkState(State.EARLY, "the client early traffic secret must be derived before the ECDHE shared secret is mixed in");
		return deriveSecret(earlySecret, "c e traffic", clientHelloTranscriptHash);
	}

	/**
	 * The PSK binder (RFC 8446 §4.2.11.2): {@code HMAC(finished key of the binder key, truncated
	 * ClientHello hash)} — computed exactly like a Finished {@code verify_data}, but over the
	 * ClientHello with its binders vector removed.
	 */
	public byte[] pskBinder(byte[] binderKey, byte[] truncatedClientHelloHash) {
		return verifyData(finishedKey(binderKey), truncatedClientHelloHash);
	}

	/**
	 * The resumption PSK a {@code NewSessionTicket} names (RFC 8446 §4.6.1):
	 * {@code HKDF-Expand-Label(resumption_master_secret, "resumption", ticket_nonce, HashLen)}.
	 * <p>
	 * Deliberately <b>static</b>: a ticket arrives long after the handshake completed and the schedule
	 * that produced the resumption master secret was destroyed, so the derivation cannot be an
	 * instance method without keeping a whole schedule resident for it. A zero-length
	 * {@code ticket_nonce} is legal and derives normally.
	 */
	public static byte[] resumptionPsk(TlsCipherSuite suite, byte[] resumptionMasterSecret, byte[] ticketNonce) {
		return Hkdf.expandLabel(suite.quicCipherSuite().hkdfHash(), resumptionMasterSecret, "resumption",
			ticketNonce, newDigest(suite).getDigestLength());
	}

	/**
	 * {@link #resumptionPsk(TlsCipherSuite, byte[], byte[])} against this schedule's own suite — the
	 * form the issuing side uses, which still holds the schedule when it seals a ticket.
	 */
	public byte[] resumptionPsk(byte[] resumptionMasterSecret, byte[] ticketNonce) {
		return Hkdf.expandLabel(hkdfHash, resumptionMasterSecret, "resumption", ticketNonce, hashLength);
	}

	/**
	 * Mixes the ECDHE shared secret in: {@code handshake secret = Extract(derived, shared)}
	 * (RFC 8446 §7.1). Consumes (and zeroes) the early secret.
	 */
	public void mixEcdhe(byte[] sharedSecret) {
		checkState(State.EARLY, "the ECDHE shared secret has already been mixed in");
		byte[] derived = deriveSecret(earlySecret, "derived", emptyHash);
		handshakeSecret = Hkdf.extract(hkdfHash, derived, sharedSecret);
		Arrays.fill(earlySecret, (byte) 0);
		Arrays.fill(derived, (byte) 0);
		state = State.HANDSHAKE;
	}

	/**
	 * The handshake secret.
	 *
	 * @throws IllegalStateException before {@link #mixEcdhe(byte[])} or after {@link #deriveMasterSecret()}
	 */
	public byte[] handshakeSecret() {
		byte[] secret = handshakeSecret;
		if (secret == null) {
			throw new IllegalStateException("The handshake secret is not available in the " + state + " state");
		}
		return secret.clone();
	}

	/** {@code c_hs_traffic} — Derive-Secret(handshake secret, "c hs traffic", CH..SH) (RFC 8446 §7.1). */
	public byte[] clientHandshakeTrafficSecret(byte[] clientHelloServerHelloTranscriptHash) {
		return deriveSecret(handshakeSecret(), "c hs traffic", clientHelloServerHelloTranscriptHash);
	}

	/** {@code s_hs_traffic} — Derive-Secret(handshake secret, "s hs traffic", CH..SH) (RFC 8446 §7.1). */
	public byte[] serverHandshakeTrafficSecret(byte[] clientHelloServerHelloTranscriptHash) {
		return deriveSecret(handshakeSecret(), "s hs traffic", clientHelloServerHelloTranscriptHash);
	}

	/**
	 * Derives the master secret: {@code master secret = Extract(derived, 0)} (RFC 8446 §7.1).
	 * Consumes (and zeroes) the handshake secret.
	 */
	public void deriveMasterSecret() {
		byte[] secret = handshakeSecret();
		byte[] derived = deriveSecret(secret, "derived", emptyHash);
		masterSecret = Hkdf.extract(hkdfHash, derived, new byte[hashLength]);
		Arrays.fill(secret, (byte) 0);
		Arrays.fill(derived, (byte) 0);
		handshakeSecret = null;
		state = State.MASTER;
	}

	/**
	 * The master secret.
	 *
	 * @throws IllegalStateException before {@link #deriveMasterSecret()}
	 */
	public byte[] masterSecret() {
		byte[] secret = masterSecret;
		if (secret == null) {
			throw new IllegalStateException("The master secret has not been derived yet");
		}
		return secret.clone();
	}

	/** {@code c_ap_traffic_0} — Derive-Secret(master secret, "c ap traffic", CH..server Finished) (RFC 8446 §7.1). */
	public byte[] clientApplicationTrafficSecret0(byte[] clientHelloServerFinishedTranscriptHash) {
		return deriveSecret(masterSecret(), "c ap traffic", clientHelloServerFinishedTranscriptHash);
	}

	/** {@code s_ap_traffic_0} — Derive-Secret(master secret, "s ap traffic", CH..server Finished) (RFC 8446 §7.1). */
	public byte[] serverApplicationTrafficSecret0(byte[] clientHelloServerFinishedTranscriptHash) {
		return deriveSecret(masterSecret(), "s ap traffic", clientHelloServerFinishedTranscriptHash);
	}

	/** {@code exp_master} — Derive-Secret(master secret, "exp master", CH..server Finished) (RFC 8446 §7.1). */
	public byte[] exporterMasterSecret(byte[] clientHelloServerFinishedTranscriptHash) {
		return deriveSecret(masterSecret(), "exp master", clientHelloServerFinishedTranscriptHash);
	}

	/** {@code res_master} — Derive-Secret(master secret, "res master", CH..client Finished) (RFC 8446 §7.1). Computed for completeness; resumption is unused in this profile (FR-015). */
	public byte[] resumptionMasterSecret(byte[] clientHelloClientFinishedTranscriptHash) {
		return deriveSecret(masterSecret(), "res master", clientHelloClientFinishedTranscriptHash);
	}

	/** The Finished key off a handshake traffic secret (RFC 8446 §4.4.4: HKDF-Expand-Label(base, "finished", "", HashLen)). */
	public byte[] finishedKey(byte[] baseTrafficSecret) {
		return Hkdf.expandLabel(hkdfHash, baseTrafficSecret, "finished", new byte[0], hashLength);
	}

	/** {@code verify_data} = HMAC(finished key, transcript hash) (RFC 8446 §4.4.4). */
	public byte[] verifyData(byte[] finishedKey, byte[] transcriptHash) {
		try {
			Mac mac = Mac.getInstance(hkdfHash);
			mac.init(new SecretKeySpec(finishedKey, hkdfHash));
			return mac.doFinal(transcriptHash);
		} catch (GeneralSecurityException e) {
			// HmacSHA256/HmacSHA384 are guaranteed present in every JDK provider.
			throw new AssertionError(e);
		}
	}

	/**
	 * The application-traffic-secret update function (RFC 8446 §7.2:
	 * {@code HKDF-Expand-Label(secret_N, "traffic upd", "", HashLen)}), exposed for QUIC
	 * key-update readiness (FR-005) — the update logic itself is the connection layer's.
	 */
	public byte[] nextApplicationTrafficSecret(byte[] applicationTrafficSecret) {
		return Hkdf.expandLabel(hkdfHash, applicationTrafficSecret, "traffic upd", new byte[0], hashLength);
	}

	/** The suite this schedule runs on. */
	public TlsCipherSuite suite() {
		return suite;
	}

	/**
	 * Zeroes every secret this schedule still holds — the early, handshake or master secret,
	 * whichever stages have not been consumed yet — and drops the references. Called by the
	 * engines on terminal failure (mid-schedule) and at handshake completion (the master
	 * secret), so no chain secret is left to wait for the GC. Traffic secrets derived earlier
	 * are the caller's own copies and are unaffected.
	 */
	public void destroy() {
		Arrays.fill(earlySecret, (byte) 0);
		byte[] handshake = handshakeSecret;
		if (handshake != null) {
			Arrays.fill(handshake, (byte) 0);
			handshakeSecret = null;
		}
		byte[] master = masterSecret;
		if (master != null) {
			Arrays.fill(master, (byte) 0);
			masterSecret = null;
		}
	}

	private byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash) {
		return Hkdf.expandLabel(hkdfHash, secret, label, transcriptHash, hashLength);
	}

	private void checkState(State expected, String message) {
		if (state != expected) {
			throw new IllegalStateException("Invalid key-schedule state " + state + ": " + message);
		}
	}

	private static MessageDigest newDigest(TlsCipherSuite suite) {
		try {
			return MessageDigest.getInstance(suite.hashAlgorithm());
		} catch (NoSuchAlgorithmException e) {
			// SHA-256/SHA-384 are guaranteed present in every JDK provider.
			throw new AssertionError(e);
		}
	}
}
