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
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable outcome of one {@link TlsEngine#consume(EncryptionLevel, ByteBuf)} call
 * (FR-013): CRYPTO bytes to send per encryption level (RFC 9001 §4.1.2), key-installation
 * events in firing order (Handshake before 1-RTT, RFC 9001 §4.9.1), — once, at completion —
 * the negotiated ALPN and the peer's transport parameters (RFC 9000 §18), and the resumption
 * outcome: the session tickets an issuing server handed over and the early-data decision
 * (spec FR-051b, research D-6).
 * <p>
 * Buffer ownership: the {@link ByteBuf} values of {@link #cryptoToSend()} are freshly allocated
 * and <b>caller-owned</b> — the caller MUST recycle every one of them, including on its own
 * failure paths. Everything else is immutable JDK data.
 * <p>
 * A {@link QuicSessionTicket} in {@link #issuedTickets()} is <b>secret material</b>: hand it to a
 * {@link QuicSessionCache} and nowhere else — never to a log line, an exception message or a JMX
 * attribute (spec FR-050, SI-6).
 */
public final class TlsEngineResult {
	private static final TlsEngineResult EMPTY =
		new TlsEngineResult(Map.of(), List.of(), null, null, false, List.of(), false, false);

	private final Map<EncryptionLevel, ByteBuf> cryptoToSend;
	private final List<KeyInstallation> keysToInstall;
	private final @Nullable String negotiatedAlpn;
	private final @Nullable QuicTransportParameters peerTransportParameters;
	private final boolean handshakeComplete;
	private final List<QuicSessionTicket> issuedTickets;
	private final boolean earlyDataAccepted;
	private final boolean resumed;

	private TlsEngineResult(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			@Nullable String negotiatedAlpn, @Nullable QuicTransportParameters peerTransportParameters,
			boolean handshakeComplete, List<QuicSessionTicket> issuedTickets, boolean earlyDataAccepted,
			boolean resumed) {
		this.cryptoToSend = cryptoToSend instanceof EnumMap<EncryptionLevel, ByteBuf> enumMap
			? Collections.unmodifiableMap(enumMap)
			: Map.copyOf(cryptoToSend);
		this.keysToInstall = List.copyOf(keysToInstall);
		this.negotiatedAlpn = negotiatedAlpn;
		this.peerTransportParameters = peerTransportParameters;
		this.handshakeComplete = handshakeComplete;
		this.issuedTickets = List.copyOf(issuedTickets);
		this.earlyDataAccepted = earlyDataAccepted;
		this.resumed = resumed;
	}

	/** A result carrying nothing — no output bytes, no installations, no completion, no ticket. */
	public static TlsEngineResult empty() {
		return EMPTY;
	}

	/** An in-progress result: output bytes and/or key installations, handshake not complete. */
	static TlsEngineResult of(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall) {
		return of(cryptoToSend, keysToInstall, List.of(), false);
	}

	/**
	 * An in-progress result that also carries a resumption outcome: the tickets a post-handshake
	 * {@code NewSessionTicket} flight delivered, and/or the early-data decision.
	 */
	static TlsEngineResult of(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			List<QuicSessionTicket> issuedTickets, boolean earlyDataAccepted) {
		return of(cryptoToSend, keysToInstall, issuedTickets, earlyDataAccepted, false);
	}

	/** The same, for an engine that already knows this handshake is a resumption. */
	static TlsEngineResult of(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			List<QuicSessionTicket> issuedTickets, boolean earlyDataAccepted, boolean resumed) {
		return new TlsEngineResult(cryptoToSend, keysToInstall, null, null, false, issuedTickets, earlyDataAccepted,
			resumed);
	}

	/** The completing result (FR-013): the negotiated ALPN and peer transport parameters surface here. */
	static TlsEngineResult complete(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			String negotiatedAlpn, QuicTransportParameters peerTransportParameters) {
		return complete(cryptoToSend, keysToInstall, negotiatedAlpn, peerTransportParameters, List.of(), false);
	}

	/** The completing result of a resumed handshake, carrying the early-data decision as well. */
	static TlsEngineResult complete(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			String negotiatedAlpn, QuicTransportParameters peerTransportParameters,
			List<QuicSessionTicket> issuedTickets, boolean earlyDataAccepted) {
		return complete(cryptoToSend, keysToInstall, negotiatedAlpn, peerTransportParameters, issuedTickets,
			earlyDataAccepted, false);
	}

	/** The completing result of a resumed handshake, carrying {@link #resumed()} as well. */
	static TlsEngineResult complete(Map<EncryptionLevel, ByteBuf> cryptoToSend, List<KeyInstallation> keysToInstall,
			String negotiatedAlpn, QuicTransportParameters peerTransportParameters,
			List<QuicSessionTicket> issuedTickets, boolean earlyDataAccepted, boolean resumed) {
		return new TlsEngineResult(cryptoToSend, keysToInstall, negotiatedAlpn, peerTransportParameters, true,
			issuedTickets, earlyDataAccepted, resumed);
	}

	/**
	 * CRYPTO bytes to send, per encryption level. The buffers are <b>caller-owned</b> — recycle
	 * them (DI-1). Absent levels have nothing to send.
	 */
	public Map<EncryptionLevel, ByteBuf> cryptoToSend() {
		return cryptoToSend;
	}

	/** Key-installation events in firing order: Handshake level first, 1-RTT after the peer's Finished. */
	public List<KeyInstallation> keysToInstall() {
		return keysToInstall;
	}

	/** The negotiated ALPN protocol ({@code h3}, FR-012), or {@code null} before completion. */
	public @Nullable String negotiatedAlpn() {
		return negotiatedAlpn;
	}

	/** The peer's QUIC transport parameters (RFC 9000 §18), or {@code null} before completion. */
	public @Nullable QuicTransportParameters peerTransportParameters() {
		return peerTransportParameters;
	}

	/** {@code true} exactly once — on the result that completes the handshake. */
	public boolean handshakeComplete() {
		return handshakeComplete;
	}

	/**
	 * The session tickets this result delivered, newest last; empty on every result that delivered
	 * none — which is every result of a phase-1 handshake (spec FR-043).
	 * <p>
	 * Client side only: a {@code NewSessionTicket} flight arrives post-handshake, so a single
	 * {@code consume} may deliver several (the server issues {@code sessionTicketsPerHandshake} of
	 * them). The server does not retain what it issues — a ticket is sealed state, not a dictionary
	 * key — so its results always report an empty list.
	 * <p>
	 * <b>Secret material</b> (spec FR-050, SI-6): store it in a {@link QuicSessionCache}, never in a
	 * log line, an exception message or a JMX attribute.
	 */
	public List<QuicSessionTicket> issuedTickets() {
		return issuedTickets;
	}

	/**
	 * Whether early data is accepted, {@code true} on the result that carries the decision — the
	 * caller latches it; a later result reporting {@code false} does not revoke it.
	 * <p>
	 * Client side: the server echoed {@code early_data} in EncryptedExtensions, so the 0-RTT packets
	 * already sent were accepted. Server side: the PSK and the {@code early_data} extension were both
	 * accepted, so 0-RTT packets from this client are to be processed.
	 * <p>
	 * Rejection is signalled by <b>omission</b> and is never a handshake failure (spec FR-048): a
	 * client that offered early data and reaches {@link #handshakeComplete()} without ever seeing
	 * {@code true} here has been refused, and discards its 0-RTT keys and 0-RTT stream state.
	 */
	public boolean earlyDataAccepted() {
		return earlyDataAccepted;
	}

	/**
	 * Whether a pre-shared key was accepted, so this handshake resumed a previous session rather than
	 * authenticating with a certificate — {@code true} on the completing result of a resumed handshake
	 * and on every result after the decision was taken.
	 * <p>
	 * Distinct from {@link #earlyDataAccepted()}: a resumed handshake that refuses early data reports
	 * {@code true} here and {@code false} there. The connection layer needs exactly this distinction,
	 * because RFC 9000 §7.4.1's non-reduction rule binds the parameters remembered with the ticket to a
	 * handshake that actually used it — a server that fell back to a full handshake is a new session
	 * and owes nothing to the old one's limits.
	 */
	public boolean resumed() {
		return resumed;
	}
}
