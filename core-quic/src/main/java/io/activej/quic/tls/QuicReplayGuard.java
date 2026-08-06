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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The bounded single-use register of ticket identities already accepted for early data — the
 * structure that makes 0-RTT shippable (spec FR-069, FR-070, FR-071; RFC 8446 §8).
 * <p>
 * {@link #tryConsume} is the whole surface: it checks and marks used in one step, because a separate
 * lookup would be an invitation to check-then-act. It answers {@code false} for a ticket that has
 * been presented before, for a ticket that is past its own lifetime, and for a presentation the
 * register has no room for. A {@code false} costs the <i>early data</i> only — the handshake behind
 * it still completes at 1-RTT — so a replayed flight degrades a connection rather than breaking it.
 *
 * <h2>Fail closed (spec FR-070)</h2>
 * <b>A live record is never dropped.</b> When a presentation finds its probe window full of live
 * records, the <i>new</i> presentation is refused; the only way a slot is ever reclaimed is its own
 * ticket's lifetime elapsing, and such a ticket is refused by this register's own expiry check on
 * presentation. So under both readings of "eviction" — capacity-driven (which never happens) and
 * lifetime-driven (where the ticket is refused anyway) — an evicted record can never be replayed.
 * The property is structural rather than argued: there is no code path that overwrites a live slot.
 * <p>
 * The availability cost is real and bounded: as the register fills, the probability that a window of
 * {@link #WINDOW} slots is entirely live grows (roughly {@code load^WINDOW} — about 0.4 % at half
 * load), so the register degrades towards refusing all <i>new</i> 0-RTT grants, never towards
 * admitting a replay. A wedged register costs the 0-RTT optimisation only.
 *
 * <h2>What is stored</h2>
 * A SHA-256 digest of {@link QuicSessionTicket#identity()} — the AEAD-sealed blob, which
 * {@link QuicTicketKeys} has already authenticated by the time a caller gets here. A digest rather
 * than the blob itself for three reasons: a <b>fixed-width</b> record is what turns an entry bound
 * into an actual memory bound (a blob is up to 65535 bytes; at the shipped bound of 65536 entries the
 * table is ~4 MB), a fixed width is what makes every comparison cost the same, and no raw ticket
 * material is held at rest (SI-6). An unkeyed digest is sufficient because identities are
 * <i>server</i>-sealed and nonce-derived: an attacker can neither choose a digest nor steer slot
 * placement.
 *
 * <h2>Constant-time lookup, not-found included (spec FR-071, SI-5)</h2>
 * Every lookup performs exactly {@code min(WINDOW, maxRecords)} comparisons of exactly
 * {@link #DIGEST_LENGTH} bytes through {@link MessageDigest#isEqual}, the same JDK primitive the PSK
 * binder check uses. An empty or expired slot is compared against a fixed-width decoy rather than
 * skipped, so a miss costs what a hit costs. Two residuals are stated rather than hidden: which slots
 * a lookup touches follows from the ticket identity, which travels in the clear in the ClientHello
 * and is not secret; and the accept/refuse answer is observable by design, because the caller acts
 * on it.
 *
 * <h2>Scope of the defence</h2>
 * The register is <b>process-local and reactor-local</b>: it is not thread-safe, it is confined to
 * one reactor like every other piece of connection state in this module, and it is not shared across
 * workers, processes or nodes. A flight replayed onto a different worker eventloop, a different
 * process or a different node behind a load balancer is <i>not</i> caught here — that case is what
 * the safe-method early-data policy defends, which is why that policy's default is not merely
 * advisory. A distributed strike register is explicitly out of scope (spec FR-069).
 * <p>
 * Emptiness after a restart is safe rather than a gap: {@link QuicTicketKeys} never persists its
 * sealing keys, so no ticket issued before a restart can be opened after one.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8446#section-8">RFC 8446 §8</a>
 */
public final class QuicReplayGuard {
	/** Slots probed per lookup — the fixed comparison count that makes the lookup uniform. */
	public static final int WINDOW = 8;

	/** Width of a stored record, and therefore of every comparison: SHA-256. */
	public static final int DIGEST_LENGTH = 32;

	private static final String DIGEST_ALGORITHM = "SHA-256";

	/** The decoy an empty or expired slot is compared against, so the not-found path costs what a hit costs. */
	private static final byte[] ABSENT = new byte[DIGEST_LENGTH];

	private final int maxRecords;
	private final int window;
	private final byte[][] digests;
	private final long[] expiresAt;
	private final MessageDigest sha256;

	private int records;
	private long granted;
	private long refusedReplayed;
	private long refusedAtCapacity;
	private long refusedExpired;
	private long comparisons;

	private QuicReplayGuard(int maxRecords) {
		this.maxRecords = maxRecords;
		this.window = Math.min(WINDOW, maxRecords);
		this.digests = new byte[maxRecords][];
		this.expiresAt = new long[maxRecords];
		try {
			this.sha256 = MessageDigest.getInstance(DIGEST_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("JDK does not provide " + DIGEST_ALGORITHM, e);
		}
	}

	/**
	 * Creates a register holding at most {@code maxRecords} ticket identities, which is
	 * {@code QuicConnectionSettings.maxEarlyDataReplayRecords()} at the call site.
	 * <p>
	 * One register serves a whole server: a per-connection register would catch nothing, since a
	 * replayed flight arrives on a new connection by construction.
	 *
	 * @throws IllegalArgumentException if {@code maxRecords} is below 1
	 */
	public static QuicReplayGuard create(int maxRecords) {
		if (maxRecords < 1) {
			throw new IllegalArgumentException("maxEarlyDataReplayRecords (" + maxRecords +
				") must be at least 1 — the register fails closed, so an empty one refuses every early-data " +
				"attempt rather than admitting one");
		}
		return new QuicReplayGuard(maxRecords);
	}

	/**
	 * Checks whether {@code ticket} may be used for early data and, if so, marks it used — one
	 * indivisible step, so there is no window between the check and the mark.
	 * <p>
	 * A repeat presentation is a return value and never an exception: it is a routine event on a
	 * public port, and the caller's response to it is to skip early data rather than to fail.
	 *
	 * @param ticket the ticket the server has already opened and accepted as a PSK
	 * @param nowMillis the instant the rest of the resumption decision is being judged against — the
	 * register reads no clock of its own
	 * @return {@code true} exactly once per ticket identity, within that ticket's lifetime and while
	 * the register has room; {@code false} on a replay, on an expired ticket and at capacity
	 * @throws IllegalArgumentException if the ticket carries no identity, which means it was never
	 * sealed and so cannot be registered — a caller bug, and the message names the condition without
	 * carrying any ticket material (SI-6)
	 */
	public boolean tryConsume(QuicSessionTicket ticket, long nowMillis) {
		Objects.requireNonNull(ticket, "ticket");
		byte[] identity = ticket.identity();
		if (identity.length == 0) {
			throw new IllegalArgumentException(
				"A session ticket with an empty identity has never been sealed and cannot be registered");
		}
		if (ticket.isExpiredAt(nowMillis)) {
			refusedExpired++;
			return false;
		}

		byte[] digest = sha256.digest(identity);
		int first = slotOf(digest);
		boolean seen = false;
		int reusable = -1;
		for (int probe = 0; probe < window; probe++) {
			int slot = first + probe < maxRecords ? first + probe : first + probe - maxRecords;
			byte[] stored = digests[slot];
			boolean live = stored != null && expiresAt[slot] > nowMillis;
			// an accumulating |= rather than a short-circuiting or, and no early exit of any kind:
			// the scan must cost the same whether or not it finds anything, and ABSENT is what an
			// empty or expired slot is compared against so the not-found path performs the same
			// single full-width comparison a hit does
			seen |= constantTimeEquals(digest, live ? stored : ABSENT);
			reusable = !live && reusable < 0 ? slot : reusable;
		}

		if (seen) {
			refusedReplayed++;
			return false;
		}
		if (reusable < 0) {
			refusedAtCapacity++;
			return false;
		}
		// a slot is reclaimed only when its own ticket has expired, which is why nothing this
		// overwrites could ever have been granted again anyway
		if (digests[reusable] == null) records++;
		digests[reusable] = digest;
		expiresAt[reusable] = ticket.issuedAtMillis() + ticket.lifetimeMillis();
		granted++;
		return true;
	}

	/** The entry bound this register was created with. */
	public int maxRecords() {
		return maxRecords;
	}

	/** Slots currently held, expired-but-not-yet-reclaimed records included. */
	public int records() {
		return records;
	}

	/** Presentations that were granted early data — one per ticket identity, ever. */
	public long granted() {
		return granted;
	}

	/** Presentations refused because the identity was already registered (RFC 8446 §8). */
	public long refusedReplayed() {
		return refusedReplayed;
	}

	/** Presentations refused because the probe window held only live records — the fail-closed path. */
	public long refusedAtCapacity() {
		return refusedAtCapacity;
	}

	/** Presentations refused because the ticket was past its own lifetime. */
	public long refusedExpired() {
		return refusedExpired;
	}

	/** Digest comparisons performed, so the FR-071 uniformity is assertable rather than aspirational. */
	public long comparisons() {
		return comparisons;
	}

	/** Counts only — never a digest, never an identity (SI-6). */
	@Override
	public String toString() {
		return "QuicReplayGuard[" + records + "/" + maxRecords + " records" +
			", granted=" + granted +
			", refusedReplayed=" + refusedReplayed +
			", refusedAtCapacity=" + refusedAtCapacity +
			", refusedExpired=" + refusedExpired + ']';
	}

	/** Constant-time digest comparison (spec FR-071, SI-5); a separate named method so tests can check it. */
	private boolean constantTimeEquals(byte[] a, byte[] b) {
		comparisons++;
		return MessageDigest.isEqual(a, b);
	}

	private int slotOf(byte[] digest) {
		long hash = 0;
		for (int i = 0; i < Long.BYTES; i++) {
			hash = hash << 8 | digest[i] & 0xFF;
		}
		return (int) Long.remainderUnsigned(hash, maxRecords);
	}
}
