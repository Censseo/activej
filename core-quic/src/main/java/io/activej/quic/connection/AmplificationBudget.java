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

/**
 * The RFC 9000 §8.1 anti-amplification limit: before a peer's address is validated, a server must
 * not send more than three times the number of bytes it has received from that address.
 * <p>
 * This is a <b>security control</b>, not a performance heuristic — it is what stops the endpoint from
 * being used to amplify traffic at a spoofed source address. Its arithmetic is therefore
 * unconditional and never gated behind {@link io.activej.common.Checks} (SI-1, WI-10).
 * <p>
 * Bytes received count toward the budget <b>whether or not any packet in the datagram decrypted</b>
 * (RFC 9000 §8.1 counts bytes received from the address). Counting only successful decryptions would
 * deadlock a handshake whose first Initial cannot be decrypted.
 * <p>
 * Not thread-safe: the owning connection provides reactor confinement.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-8.1">RFC 9000 §8.1 — Address Validation during Connection Establishment</a>
 */
public final class AmplificationBudget {
	/** RFC 9000 §8.1: three times the received byte count. */
	private static final int AMPLIFICATION_FACTOR = 3;

	private long bytesReceived;
	private long bytesSent;
	private boolean validated;

	private AmplificationBudget(boolean validated) {
		this.validated = validated;
	}

	/**
	 * A server-role budget. Starts unvalidated, so {@link #remaining()} is 0 until something is
	 * received: a server that has received nothing may send nothing.
	 */
	public static AmplificationBudget forServer() {
		return new AmplificationBudget(false);
	}

	/**
	 * A permanently unlimited budget — for the client role, and for a server once address validation
	 * has completed. A client that received a response has by definition validated the server's
	 * address, so the limit never applies to it.
	 */
	public static AmplificationBudget validated() {
		return new AmplificationBudget(true);
	}

	/**
	 * Records a datagram received from the peer.
	 * <p>
	 * Must be called for <b>every</b> datagram from the address, including ones in which no packet
	 * could be decrypted (RFC 9000 §8.1). Making this conditional on successful decryption deadlocks
	 * handshakes.
	 */
	public void onDatagramReceived(int size) {
		bytesReceived += size;
	}

	/** Records a datagram sent to the peer. Keeps the true total; an overdraft is never forgiven. */
	public void onDatagramSent(int size) {
		bytesSent += size;
	}

	/**
	 * How many more bytes may be sent, or {@link Long#MAX_VALUE} once validated.
	 * <p>
	 * Floored at 0, and never throws — a server sitting at the limit must still be able to arm its
	 * probe timer and ask again later (FR-018). Callers combine this with the congestion window by
	 * taking a minimum, never by adding to it.
	 */
	public long remaining() {
		if (validated) return Long.MAX_VALUE;
		// Saturating multiply: bytesReceived is bounded by real traffic, but the guard keeps the
		// arithmetic honest regardless.
		long allowed = bytesReceived > Long.MAX_VALUE / AMPLIFICATION_FACTOR
			? Long.MAX_VALUE
			: bytesReceived * AMPLIFICATION_FACTOR;
		return Math.max(0, allowed - bytesSent);
	}

	/** Whether a datagram of this size may be sent under the limit. */
	public boolean canSend(int datagramSize) {
		return remaining() >= datagramSize;
	}

	/**
	 * Marks the peer's address validated, permanently disabling the limit (RFC 9000 §8.1).
	 * Idempotent and irreversible — there is no un-validate.
	 */
	public void setValidated() {
		validated = true;
	}

	public boolean isValidated() {
		return validated;
	}

	public long bytesReceived() {
		return bytesReceived;
	}

	public long bytesSent() {
		return bytesSent;
	}

	@Override
	public String toString() {
		return "AmplificationBudget{" +
			"received=" + bytesReceived +
			", sent=" + bytesSent +
			", remaining=" + (validated ? "unlimited" : String.valueOf(remaining())) +
			", validated=" + validated +
			'}';
	}
}
