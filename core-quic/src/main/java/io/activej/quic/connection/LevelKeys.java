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

package io.activej.quic.connection;

import io.activej.quic.crypto.QuicCipherSuite;
import io.activej.quic.crypto.QuicKeys;
import io.activej.quic.tls.EncryptionLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The key state of one encryption level: the send and receive {@link QuicKeys}, and the RFC 9001 §6.6
 * AEAD usage counters that bound how long those keys may be used.
 * <p>
 * <b>No key update.</b> This feature does not implement RFC 9001 §6 key rotation: reaching either
 * limit closes the connection with {@code AEAD_LIMIT_REACHED} (FR-027) rather than rotating. That is a
 * deliberate scope decision — the {@link QuicKeys} it wraps is already immutable and swapped as a
 * whole reference, which is the readiness contract a future key update would build on.
 * <p>
 * One instance per encryption level. Not thread-safe: the owning connection provides reactor
 * confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-6.6">RFC 9001 §6.6 — Limits on AEAD Usage</a>
 */
public final class LevelKeys {
	private final EncryptionLevel level;

	private @Nullable QuicKeys sendKeys;
	private @Nullable QuicKeys receiveKeys;
	private long packetsSent;
	private long failedDecryptions;
	private boolean discarded;

	public LevelKeys(EncryptionLevel level) {
		this.level = level;
	}

	/**
	 * The RFC 9001 §6.6 confidentiality limit: the number of packets that may be <b>encrypted</b>
	 * with one key.
	 * <p>
	 * The switch has no {@code default} on purpose — adding a fourth cipher suite must be a compile
	 * error here, not a silently wrong cryptographic bound.
	 */
	public static long confidentialityLimit(QuicCipherSuite suite) {
		return switch (suite) {
			case AES_128_GCM, AES_256_GCM -> 1L << 23;
			case CHACHA20_POLY1305 -> 1L << 62;
		};
	}

	/**
	 * The RFC 9001 §6.6 integrity limit: the number of <b>failed</b> decryptions tolerated for one key.
	 * <p>
	 * No {@code default}, for the same reason as {@link #confidentialityLimit(QuicCipherSuite)}.
	 */
	public static long integrityLimit(QuicCipherSuite suite) {
		return switch (suite) {
			case AES_128_GCM, AES_256_GCM -> 1L << 52;
			case CHACHA20_POLY1305 -> 1L << 36;
		};
	}

	/**
	 * Installs this level's keys in one step — deliberately the only installer, so a state where the
	 * send keys belong to one epoch and the receive keys to another cannot be reached by two calls.
	 * <p>
	 * Either direction may be {@code null}, and exactly one level uses that: {@code ZERO_RTT} is
	 * <b>one-directional by protocol</b>. A client protects 0-RTT packets and never opens one; a server
	 * opens them and may never send one (RFC 9001 §4.6.1, spec FR-052). Passing {@code null} for the
	 * unused half is what makes that a structural property rather than a rule someone has to remember.
	 */
	public void install(@Nullable QuicKeys sendKeys, @Nullable QuicKeys receiveKeys) {
		this.sendKeys = sendKeys;
		this.receiveKeys = receiveKeys;
	}

	/** Whether <b>both</b> directions are installed — true of every bidirectional level, false of 0-RTT. */
	public boolean isInstalled() {
		return sendKeys != null && receiveKeys != null;
	}

	/**
	 * Whether either direction is installed. This is what "the level exists" means for a
	 * one-directional level, and it agrees with {@link #isInstalled()} at every other level.
	 */
	public boolean isEitherDirectionInstalled() {
		return sendKeys != null || receiveKeys != null;
	}

	/**
	 * Whether packets at this level can currently be protected <b>and</b> opened. Equivalent to
	 * {@link #acceptsSend()} {@code &&} {@link #acceptsReceive()}; a one-directional level never
	 * satisfies it, which is why the send and receive paths ask the direction they mean.
	 */
	public boolean accepts() {
		return acceptsSend() && acceptsReceive();
	}

	/** Whether a packet at this level can currently be protected. */
	public boolean acceptsSend() {
		return sendKeys != null && !discarded;
	}

	/** Whether a packet at this level can currently be opened. */
	public boolean acceptsReceive() {
		return receiveKeys != null && !discarded;
	}

	/**
	 * Accounts for one packet about to be encrypted at this level.
	 * <p>
	 * Checked with {@code >=} <b>before</b> incrementing: RFC 9001 §6.6 forbids sending more than the
	 * limit, so the limit is a count that must not be exceeded.
	 *
	 * @throws QuicTransportException {@code AEAD_LIMIT_REACHED} when the confidentiality limit is reached
	 */
	public void onPacketSent() throws QuicTransportException {
		if (discarded) return;
		QuicKeys keys = sendKeys;
		if (keys == null) return;
		if (packetsSent >= confidentialityLimit(keys.suite())) {
			throw new QuicTransportException(QuicTransportErrors.AEAD_LIMIT_REACHED,
				"AEAD confidentiality limit reached at " + level + " for " + keys.suite());
		}
		packetsSent++;
	}

	/**
	 * Accounts for one packet that failed AEAD authentication at this level.
	 *
	 * @throws QuicTransportException {@code AEAD_LIMIT_REACHED} when the integrity limit is reached
	 */
	public void onDecryptionFailed() throws QuicTransportException {
		if (discarded) return;
		QuicKeys keys = receiveKeys;
		if (keys == null) return;
		if (failedDecryptions >= integrityLimit(keys.suite())) {
			throw new QuicTransportException(QuicTransportErrors.AEAD_LIMIT_REACHED,
				"AEAD integrity limit reached at " + level + " for " + keys.suite());
		}
		failedDecryptions++;
	}

	/**
	 * Accounts for a successful decryption — which is to say, it does nothing.
	 * <p>
	 * Present so the receive path reads symmetrically, and to record that the integrity counter is
	 * <b>not</b> reset on success: RFC 9001 §6.6 counts failures for the lifetime of the key, and
	 * resetting them would defeat the limit.
	 */
	public void onDecryptionSucceeded() {
	}

	/**
	 * Discards this level's keys (RFC 9001 §4.9). Packets at a discarded level are dropped silently,
	 * and the counter methods become no-ops — a datagram still in flight when a level is discarded is
	 * normal, not a fault. Idempotent.
	 */
	public void discard() {
		discarded = true;
		// Dropping the references lets the cached Ciphers be collected.
		sendKeys = null;
		receiveKeys = null;
	}

	public boolean isDiscarded() {
		return discarded;
	}

	public EncryptionLevel level() {
		return level;
	}

	public @Nullable QuicKeys sendKeys() {
		return sendKeys;
	}

	public @Nullable QuicKeys receiveKeys() {
		return receiveKeys;
	}

	public long packetsSent() {
		return packetsSent;
	}

	public long failedDecryptions() {
		return failedDecryptions;
	}

	/**
	 * Test-only seam: reaching a 2^23 or 2^52 limit by looping is not viable, so tests seed the
	 * counters directly.
	 */
	void seedCountersForTesting(long packetsSent, long failedDecryptions) {
		this.packetsSent = packetsSent;
		this.failedDecryptions = failedDecryptions;
	}

	/** Never prints key material, IVs or header-protection keys (SI-6) — only the suite name. */
	@Override
	public String toString() {
		QuicKeys keys = sendKeys != null ? sendKeys : receiveKeys;
		return "LevelKeys{" +
			level +
			", suite=" + (keys == null ? "none" : keys.suite()) +
			", packetsSent=" + packetsSent +
			", failedDecryptions=" + failedDecryptions +
			", installed=" + isInstalled() +
			", discarded=" + discarded +
			'}';
	}
}
