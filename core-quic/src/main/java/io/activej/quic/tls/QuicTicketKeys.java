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
import io.activej.quic.crypto.QuicCipherSuite;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * The server's session-ticket sealing keys (data-model.md §2): a current key plus
 * {@link #RETAINED_KEYS} − 1 previous ones, so a ticket sealed just before a rotation stays openable
 * for the rest of its lifetime.
 * <p>
 * <b>Rotation is runtime state, not reconfiguration</b> (spec FR-060). Keys are generated from the
 * configured {@code SecureRandom}, never persisted and never reloaded: tickets do not survive a
 * server restart, which is the safe default — a restart is the one moment a replay register is
 * empty.
 * <p>
 * <b>Sealing scheme</b>, built entirely from the primitives {@code core-quic} already has (no new
 * algorithm):
 * <pre>
 * sealed := 0x01 (format version) || nonce[12] || AES-256-GCM(key, nonce, aad = sealed[0..13), plaintext)
 * </pre>
 * The overhead over the plaintext is 29 bytes. The nonce is a 4-byte per-key random prefix followed
 * by an 8-byte big-endian per-key counter — a <i>hard</i> no-reuse guarantee within one key's life
 * rather than the probabilistic one a random 96-bit nonce gives. Since keys are never persisted, the
 * counter can never restart under a key that has already sealed something.
 * <p>
 * <b>There is no key identifier on the wire.</b> {@link #open} trial-decrypts newest-key-first over
 * at most {@link #RETAINED_KEYS} keys, which costs at most two AEAD passes over a bounded blob and
 * leaks no server key-epoch marker.
 * <p>
 * <b>{@link #open} is total on attacker-controlled input</b> — it is fed the PSK identity out of a
 * ClientHello. It returns {@code null} for a short blob, an unknown format version, an AEAD tag
 * failure under every retained key, or a malformed plaintext, and it never throws and never signals
 * a protocol error: an unopenable ticket is a full handshake (spec FR-045). It reads no clock and
 * performs no expiry check — {@link QuicSessionTicket#isExpiredAt} is the engine's call, and both
 * outcomes are the same full handshake.
 * <p>
 * <b>Not thread-safe.</b> Like every other piece of connection state in this module it is confined to
 * one reactor; the nonce counter and the key ring are plain mutable fields.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-4.6.1">RFC 8446 §4.6.1</a>
 */
public final class QuicTicketKeys {
	/** Current key plus one previous, which is what keeps a ticket openable across one rotation. */
	public static final int RETAINED_KEYS = 2;

	/** Tickets are sealed under AES-256-GCM whatever suite the resumed session negotiated. */
	public static final QuicCipherSuite SEALING_SUITE = QuicCipherSuite.AES_256_GCM;

	private static final byte FORMAT_VERSION = 0x01;
	private static final int NONCE_PREFIX_LENGTH = 4;
	private static final int TAG_LENGTH = 16;
	private static final int HEADER_LENGTH = 1 + SEALING_SUITE.ivLength();

	/** {@code NewSessionTicket.ticket} is {@code opaque ticket<1..2^16-1>} (RFC 8446 §4.6.1). */
	private static final int MAX_SEALED_LENGTH = 0xFFFF;

	private final SecureRandom secureRandom;
	private final long rotationIntervalMillis;
	private final long ticketLifetimeMillis;
	private final SealingKey[] keys = new SealingKey[RETAINED_KEYS];
	private final Cipher cipher;

	private long lastRotationMillis;
	private long rotationCount;

	private QuicTicketKeys(SecureRandom secureRandom, long rotationIntervalMillis, long ticketLifetimeMillis,
			long nowMillis) {
		this.secureRandom = secureRandom;
		this.rotationIntervalMillis = rotationIntervalMillis;
		this.ticketLifetimeMillis = ticketLifetimeMillis;
		this.lastRotationMillis = nowMillis;
		this.keys[0] = newKey();
		try {
			this.cipher = Cipher.getInstance(SEALING_SUITE.aeadTransform());
		} catch (GeneralSecurityException e) {
			throw new AssertionError("JDK does not provide " + SEALING_SUITE.aeadTransform(), e);
		}
	}

	/**
	 * Generates the first sealing key and fixes the rotation grid at {@code nowMillis}.
	 * <p>
	 * The lifetime-versus-rotation invariant is enforced here in its <b>worst case</b>: a key
	 * generated at {@code t} is current on {@code [t, t + R)} and dropped at
	 * {@code t + RETAINED_KEYS · R}, so a ticket sealed an instant before a rotation is only openable
	 * for another {@code R}. Openability for the whole lifetime therefore needs
	 * {@code L <= (RETAINED_KEYS - 1) · R}. The shipped defaults (1 h lifetime, 6 h rotation) satisfy
	 * it comfortably. Violating it is refused rather than warned about, because the failure it
	 * produces otherwise is silent: tickets stop opening some hours before they expire and every
	 * affected client quietly falls back to a full handshake.
	 *
	 * @throws IllegalArgumentException if either interval is non-positive, or if the lifetime
	 * exceeds what the retained keys can cover
	 */
	public static QuicTicketKeys create(SecureRandom secureRandom, long rotationIntervalMillis,
			long ticketLifetimeMillis, long nowMillis) {
		Objects.requireNonNull(secureRandom, "secureRandom");
		if (rotationIntervalMillis <= 0) {
			throw new IllegalArgumentException(
				"sessionTicketKeyRotation (" + rotationIntervalMillis + " ms) must be positive");
		}
		if (ticketLifetimeMillis <= 0) {
			throw new IllegalArgumentException(
				"sessionTicketLifetime (" + ticketLifetimeMillis + " ms) must be positive");
		}
		if (ticketLifetimeMillis > (RETAINED_KEYS - 1) * rotationIntervalMillis) {
			throw new IllegalArgumentException(
				"sessionTicketLifetime (" + ticketLifetimeMillis + " ms) must not exceed " +
				(RETAINED_KEYS - 1) + " × sessionTicketKeyRotation (" + rotationIntervalMillis + " ms) = " +
				((RETAINED_KEYS - 1) * rotationIntervalMillis) + " ms: a ticket sealed an instant before a " +
				"rotation is openable for only one more rotation interval, so a longer lifetime strands live " +
				"tickets under a key that no longer exists");
		}
		return new QuicTicketKeys(secureRandom, rotationIntervalMillis, ticketLifetimeMillis, nowMillis);
	}

	/**
	 * Rotates if due, then seals {@code ticket} under the current key. The result is the opaque
	 * {@code NewSessionTicket.ticket} blob, which is also the PSK identity the client will offer.
	 *
	 * @throws IllegalArgumentException if the sealed blob would exceed the RFC 8446 §4.6.1
	 * {@code ticket<1..2^16-1>} wire bound
	 * @throws IllegalStateException if the current key's nonce counter is exhausted (unreachable at
	 * 2^63 seals under one key)
	 */
	public byte[] seal(QuicSessionTicket ticket, long nowMillis) {
		Objects.requireNonNull(ticket, "ticket");
		rotateIfDue(nowMillis);

		int plaintextLength = ticket.plaintextLength();
		int sealedLength = HEADER_LENGTH + plaintextLength + TAG_LENGTH;
		if (sealedLength > MAX_SEALED_LENGTH) {
			throw new IllegalArgumentException("Sealed session ticket would be " + sealedLength +
				" bytes, above the RFC 8446 §4.6.1 ticket<1..65535> bound");
		}

		SealingKey key = keys[0];
		byte[] sealed = new byte[sealedLength];
		sealed[0] = FORMAT_VERSION;
		byte[] nonce = key.nextNonce();
		System.arraycopy(nonce, 0, sealed, 1, nonce.length);

		ByteBuf plaintext = ByteBufPool.allocate(plaintextLength);
		try {
			ticket.writePlaintextTo(plaintext);
			cipher.init(Cipher.ENCRYPT_MODE, key.key, new GCMParameterSpec(128, nonce));
			cipher.updateAAD(sealed, 0, HEADER_LENGTH);
			cipher.doFinal(plaintext.array(), plaintext.head(), plaintext.readRemaining(), sealed, HEADER_LENGTH);
		} catch (GeneralSecurityException e) {
			throw new AssertionError("Session ticket sealing failed with a correctly-sized key and buffer", e);
		} finally {
			plaintext.recycle();
		}
		return sealed;
	}

	/**
	 * Opens a sealed blob, or returns {@code null} when it cannot be opened under any retained key.
	 * <p>
	 * {@code null} is not an error and never becomes one: it means "perform a full handshake"
	 * (spec FR-045). No branch of this method reports <i>why</i> a blob was refused, and no blob byte
	 * reaches any message (SI-6).
	 */
	public @Nullable QuicSessionTicket open(byte[] sealedTicket) {
		if (sealedTicket == null || sealedTicket.length <= HEADER_LENGTH + TAG_LENGTH) return null;
		if (sealedTicket[0] != FORMAT_VERSION) return null;

		byte[] nonce = new byte[SEALING_SUITE.ivLength()];
		System.arraycopy(sealedTicket, 1, nonce, 0, nonce.length);
		for (SealingKey key : keys) {
			if (key == null) continue;
			byte[] plaintext;
			try {
				cipher.init(Cipher.DECRYPT_MODE, key.key, new GCMParameterSpec(128, nonce));
				cipher.updateAAD(sealedTicket, 0, HEADER_LENGTH);
				plaintext = cipher.doFinal(sealedTicket, HEADER_LENGTH, sealedTicket.length - HEADER_LENGTH);
			} catch (GeneralSecurityException e) {
				continue;
			}
			try {
				QuicSessionTicket contents = QuicSessionTicket.readPlaintext(ByteBuf.wrapForReading(plaintext));
				return QuicSessionTicket
					.builder(contents.serverName(), contents.alpn(), contents.cipherSuite(), contents.resumptionSecret())
					.withIdentity(sealedTicket)
					.withIssuedAt(contents.issuedAtMillis())
					.withLifetime(contents.lifetimeMillis())
					.withTicketAgeAdd(contents.ticketAgeAdd())
					.withTransportParameters(contents.transportParameters())
					.withApplicationSettings(contents.applicationSettings())
					.build();
			} catch (MalformedDataException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Rotates the key ring if at least one rotation interval has elapsed since the last rotation,
	 * and re-aligns the grid so rotations stay on the original phase. At most
	 * {@link #RETAINED_KEYS} rotations are performed however long the gap — rotating past the ring
	 * only discards keys that are already gone. A clock that has moved backwards rotates nothing.
	 *
	 * @return whether anything was rotated
	 */
	public boolean rotateIfDue(long nowMillis) {
		long elapsed = nowMillis - lastRotationMillis;
		if (elapsed < rotationIntervalMillis) return false;

		long due = elapsed / rotationIntervalMillis;
		int rotations = (int) Math.min(due, RETAINED_KEYS);
		for (int i = 0; i < rotations; i++) {
			System.arraycopy(keys, 0, keys, 1, keys.length - 1);
			keys[0] = newKey();
		}
		rotationCount += rotations;
		lastRotationMillis = nowMillis - elapsed % rotationIntervalMillis;
		return true;
	}

	/** The configured {@code sessionTicketKeyRotation}, in milliseconds. */
	public long rotationIntervalMillis() {
		return rotationIntervalMillis;
	}

	/**
	 * The configured {@code sessionTicketLifetime}, in milliseconds — the value a
	 * {@code NewSessionTicket} advertises and the one {@link QuicSessionTicket#isExpiredAt} is built
	 * from. Read it from here rather than duplicating the setting.
	 */
	public long ticketLifetimeMillis() {
		return ticketLifetimeMillis;
	}

	/** How many keys are currently retained: 1 until the first rotation, {@link #RETAINED_KEYS} after. */
	public int retainedKeyCount() {
		int count = 0;
		for (SealingKey key : keys) {
			if (key != null) count++;
		}
		return count;
	}

	/** How many rotations this key set has actually performed since {@code create}. */
	public long rotationCount() {
		return rotationCount;
	}

	@Override
	public String toString() {
		return "QuicTicketKeys[retained=" + retainedKeyCount() + ']';
	}

	private SealingKey newKey() {
		byte[] keyBytes = new byte[SEALING_SUITE.keyLength()];
		secureRandom.nextBytes(keyBytes);
		byte[] noncePrefix = new byte[NONCE_PREFIX_LENGTH];
		secureRandom.nextBytes(noncePrefix);
		return new SealingKey(new SecretKeySpec(keyBytes, SEALING_SUITE.keyAlgorithm()), noncePrefix);
	}

	/**
	 * One sealing key with its own nonce prefix and counter. The counter is what makes nonce reuse
	 * under this key impossible rather than merely improbable.
	 */
	private static final class SealingKey {
		private final SecretKeySpec key;
		private final byte[] noncePrefix;
		private long counter;

		private SealingKey(SecretKeySpec key, byte[] noncePrefix) {
			this.key = key;
			this.noncePrefix = noncePrefix;
		}

		private byte[] nextNonce() {
			if (counter == Long.MAX_VALUE) {
				throw new IllegalStateException("Session-ticket sealing nonce counter is exhausted for this key");
			}
			long value = counter++;
			byte[] nonce = new byte[SEALING_SUITE.ivLength()];
			System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_LENGTH);
			for (int i = 0; i < 8; i++) {
				nonce[NONCE_PREFIX_LENGTH + i] = (byte) (value >>> (56 - 8 * i));
			}
			return nonce;
		}
	}
}
