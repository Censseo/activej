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
import io.activej.common.builder.AbstractBuilder;
import org.jetbrains.annotations.Nullable;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Immutable configuration of a server-side {@link TlsEngine} (data-model.md): the server
 * identity, the local QUIC transport parameters sent in EncryptedExtensions, and the
 * determinism hooks — {@code SecureRandom} and the ephemeral-key source — that let tests replay
 * recorded handshakes (RFC 8448 §3), plus the per-engine handshake-message size bound override.
 * <p>
 * Built once via the one-shot {@link Builder} ({@code AbstractBuilder} convention); the
 * configuration is immutable after {@code build()} and never mutated mid-handshake (spec §Data
 * &amp; State).
 * <p>
 * The local transport parameters are validated at {@code build()}: RFC 9000 §18.2 makes
 * {@code initial_source_connection_id} mandatory for both roles and
 * {@code original_destination_connection_id} mandatory for a server — a missing one is a
 * configuration error, not a mid-handshake surprise.
 * <p>
 * Resumption (spec FR-051b, research D-6): this configuration — never
 * {@code QuicConnection.TlsEngineFactory}, whose signature is frozen — carries the keys tickets are
 * sealed under ({@link Builder#withTicketKeys}), the lifetime they are advertised with, the number
 * issued per handshake, the obfuscated-age tolerance a resumption attempt is checked against and the
 * single-use replay register early data is checked against ({@link Builder#withReplayGuard}).
 * With no key set the engine issues no ticket and behaves exactly as it did in phase 1.
 */
public final class TlsServerConfig {

	/** Advertised ticket lifetime when no sealing keys state one, matching {@code QuicConnectionSettings.sessionTicketLifetime()}. */
	private static final long DEFAULT_SESSION_TICKET_LIFETIME_MILLIS = 3_600_000L;

	/** {@code NewSessionTicket} messages issued per handshake, matching {@code QuicConnectionSettings.sessionTicketsPerHandshake()}. */
	private static final int DEFAULT_SESSION_TICKETS_PER_HANDSHAKE = 2;

	/** Obfuscated-age tolerance (RFC 8446 §4.2.11.1), matching {@code QuicConnectionSettings.ticketAgeTolerance()}. */
	private static final long DEFAULT_TICKET_AGE_TOLERANCE_MILLIS = 10_000L;

	private final TlsServerIdentity identity;
	private final QuicTransportParameters localTransportParameters;
	private SecureRandom secureRandom = new SecureRandom();
	private Function<NamedGroup, KeyPair> ephemeralKeySource = TlsKeyExchanges::generateKeyPair;
	private MemSize maxHandshakeMessageSize = TlsMessages.MAX_HANDSHAKE_MESSAGE_SIZE;
	private LongSupplier currentTimeMillis = System::currentTimeMillis;
	private @Nullable QuicTicketKeys ticketKeys;

	// 0 means "unset" and is resolved at build() — from the sealing keys when there are any, so the
	// advertised lifetime can never outlive what those keys are still able to open.
	private long sessionTicketLifetimeMillis;
	private int sessionTicketsPerHandshake = DEFAULT_SESSION_TICKETS_PER_HANDSHAKE;
	private long ticketAgeToleranceMillis = DEFAULT_TICKET_AGE_TOLERANCE_MILLIS;
	private boolean earlyDataEnabled;
	private @Nullable QuicReplayGuard replayGuard;

	private TlsServerConfig(TlsServerIdentity identity, QuicTransportParameters localTransportParameters) {
		this.identity = Objects.requireNonNull(identity, "identity");
		this.localTransportParameters = Objects.requireNonNull(localTransportParameters, "localTransportParameters");
	}

	/**
	 * Starts the one-shot builder for a server configuration (FR-009, FR-013).
	 *
	 * @param identity the server's certificate chain + private key, loaded once and immutable
	 * @param localTransportParameters this endpoint's QUIC transport parameters (RFC 9000 §18),
	 *        sent in EncryptedExtensions' {@code quic_transport_parameters} extension
	 *        (RFC 9001 §8.2)
	 */
	public static Builder builder(TlsServerIdentity identity, QuicTransportParameters localTransportParameters) {
		return new TlsServerConfig(identity, localTransportParameters).new Builder();
	}

	/** The server's certificate chain + private key (FR-009). */
	public TlsServerIdentity identity() {
		return identity;
	}

	/** The transport parameters sent to the client in EncryptedExtensions (RFC 9001 §8.2). */
	public QuicTransportParameters localTransportParameters() {
		return localTransportParameters;
	}

	/** Randomness for the ServerHello random (default: a shared {@link SecureRandom}). */
	public SecureRandom secureRandom() {
		return secureRandom;
	}

	/** Ephemeral ECDHE key-pair source per selected group (default: JDK generation). */
	public Function<NamedGroup, KeyPair> ephemeralKeySource() {
		return ephemeralKeySource;
	}

	/**
	 * Per-engine bound on one handshake message's declared size (FR-017), default
	 * {@link TlsMessages#MAX_HANDSHAKE_MESSAGE_SIZE}. The global {@code ApplicationSettings}
	 * bound in {@link TlsMessages} remains as a backstop — the effective bound is the stricter
	 * of the two.
	 */
	public MemSize maxHandshakeMessageSize() {
		return maxHandshakeMessageSize;
	}

	/**
	 * The clock the engine reads to stamp a ticket, to expire one, and to check a resumption
	 * attempt's obfuscated age (RFC 8446 §4.2.11.1); {@code System::currentTimeMillis} by default.
	 * <p>
	 * A reactor-bound consumer passes {@code reactor::currentTimeMillis}, so that ticket ages follow
	 * the same clock as every other timer in the connection; a test injects a fixed one. The
	 * {@code System} default is confined to {@code tls/}, which has no reactor of its own and must
	 * not grow one (ADR-016) — it is not the {@code connection/} timer rule being bent.
	 */
	public LongSupplier currentTimeMillis() {
		return currentTimeMillis;
	}

	/**
	 * The keys session tickets are sealed under and opened with (spec FR-042), or {@code null} to
	 * issue none — the phase-1 behaviour, in which every handshake is a full handshake.
	 * <p>
	 * <b>Secret material</b> (spec FR-050, SI-6). The keys are runtime state, not configuration:
	 * they rotate as the server runs, and they are never persisted, so tickets do not survive a
	 * restart (spec FR-060).
	 */
	public @Nullable QuicTicketKeys ticketKeys() {
		return ticketKeys;
	}

	/**
	 * The {@code ticket_lifetime} advertised in {@code NewSessionTicket} (RFC 8446 §4.6.1). Defaults
	 * to what {@link #ticketKeys()} can still open, so the server never advertises a ticket that
	 * outlives the key ring; 1 h when there are no keys.
	 */
	public long sessionTicketLifetimeMillis() {
		return sessionTicketLifetimeMillis;
	}

	/**
	 * The {@code NewSessionTicket} messages issued once the handshake completes (RFC 8446 §4.6.1),
	 * default 2 — a client can resume twice without an extra round trip. {@code 0} issues none.
	 */
	public int sessionTicketsPerHandshake() {
		return sessionTicketsPerHandshake;
	}

	/**
	 * How far a resumption attempt's obfuscated ticket age (RFC 8446 §4.2.11.1) may differ from what
	 * the server computes before the ticket is refused, default 10 s (spec FR-045). A refusal falls
	 * back to a full handshake; it is never a handshake failure.
	 */
	public long ticketAgeToleranceMillis() {
		return ticketAgeToleranceMillis;
	}

	/**
	 * Whether this server accepts <b>early data</b> on a resumed handshake (spec FR-048, FR-049) —
	 * echoing {@code early_data} in EncryptedExtensions and installing the RFC 9001 §4.1.4 0-RTT
	 * read keys. Default {@code false}: resumption alone saves the certificate flight, and 0-RTT is
	 * the part that carries a replay exposure, so it is a separate, explicit opt-in.
	 * <p>
	 * <b>Off is the safe value and the default for a reason.</b> Turning it on without a
	 * {@link #replayGuard()} would accept a replayed early-data flight as readily as a fresh one
	 * (spec FR-069+, RFC 8446 §8), so {@code build()} refuses that pair outright.
	 */
	public boolean earlyDataEnabled() {
		return earlyDataEnabled;
	}

	/**
	 * The single-use register early data is checked against before it is accepted (spec FR-069,
	 * RFC 8446 §8), or {@code null} when {@link #earlyDataEnabled()} is off and there is no early data
	 * to check — the two are refused together at {@code build()}.
	 * <p>
	 * One register serves a whole server and is shared by every connection's configuration: a replayed
	 * flight arrives on a new connection by construction, so a per-connection register would catch
	 * nothing. It is not thread-safe, so "a whole server" means one reactor's worth of connections —
	 * under a worker pool each worker owns its own, and a replay across workers is uncaught, exactly as
	 * a replay across processes or nodes is.
	 * <p>
	 * {@code null} is legal and is what a configuration that never enables {@link #earlyDataEnabled()}
	 * wants; it costs nothing there, because early data is what a replay buys. With early data on there
	 * is no such choice to make — {@code build()} throws rather than let a consumer reach a
	 * replay-vulnerable 0-RTT server with no signal.
	 */
	public @Nullable QuicReplayGuard replayGuard() {
		return replayGuard;
	}

	/**
	 * The one-shot {@code AbstractBuilder} for {@link TlsServerConfig}: every {@code withXxx}
	 * is optional (safe defaults per FR-017), {@code build()} freezes the configuration.
	 */
	public final class Builder extends AbstractBuilder<Builder, TlsServerConfig> {
		private Builder() {
		}

		/** Determinism hook: the randomness behind the ServerHello random. */
		public Builder withSecureRandom(SecureRandom secureRandom) {
			checkNotBuilt(this);
			TlsServerConfig.this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
			return this;
		}

		/** Determinism hook: ephemeral ECDHE key-pair source (tests inject recorded keys). */
		public Builder withEphemeralKeySource(Function<NamedGroup, KeyPair> ephemeralKeySource) {
			checkNotBuilt(this);
			TlsServerConfig.this.ephemeralKeySource = Objects.requireNonNull(ephemeralKeySource, "ephemeralKeySource");
			return this;
		}

		/** Per-engine override of the FR-017 handshake-message size bound (stricter direction). */
		public Builder withMaxHandshakeMessageSize(MemSize maxHandshakeMessageSize) {
			checkNotBuilt(this);
			TlsServerConfig.this.maxHandshakeMessageSize = Objects.requireNonNull(maxHandshakeMessageSize, "maxHandshakeMessageSize");
			return this;
		}

		/**
		 * The clock behind ticket issue times, expiry and the obfuscated-age check. A reactor-bound
		 * consumer passes {@code reactor::currentTimeMillis}; tests inject a fixed supplier.
		 */
		public Builder withCurrentTimeMillis(LongSupplier currentTimeMillis) {
			checkNotBuilt(this);
			TlsServerConfig.this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
			return this;
		}

		/**
		 * The keys session tickets are sealed under (spec FR-042). Absent, the server issues no
		 * ticket and every handshake is a full handshake — the phase-1 behaviour.
		 */
		public Builder withTicketKeys(QuicTicketKeys ticketKeys) {
			checkNotBuilt(this);
			TlsServerConfig.this.ticketKeys = Objects.requireNonNull(ticketKeys, "ticketKeys");
			return this;
		}

		/**
		 * The advertised {@code ticket_lifetime} (RFC 8446 §4.6.1). Unset, it follows
		 * {@link #withTicketKeys}; set, it may not exceed what those keys can still open.
		 */
		public Builder withSessionTicketLifetime(Duration sessionTicketLifetime) {
			checkNotBuilt(this);
			Objects.requireNonNull(sessionTicketLifetime, "sessionTicketLifetime");
			if (sessionTicketLifetime.toMillis() <= 0) {
				throw new IllegalArgumentException("sessionTicketLifetime must be positive, got " + sessionTicketLifetime);
			}
			TlsServerConfig.this.sessionTicketLifetimeMillis = sessionTicketLifetime.toMillis();
			return this;
		}

		/** The {@code NewSessionTicket} messages issued per handshake (default 2; 0 issues none). */
		public Builder withSessionTicketsPerHandshake(int sessionTicketsPerHandshake) {
			checkNotBuilt(this);
			if (sessionTicketsPerHandshake < 0) {
				throw new IllegalArgumentException(
					"sessionTicketsPerHandshake must not be negative, got " + sessionTicketsPerHandshake);
			}
			TlsServerConfig.this.sessionTicketsPerHandshake = sessionTicketsPerHandshake;
			return this;
		}

		/**
		 * Accepts early data on a resumed handshake (spec FR-048). Default {@code false}, which resumes
		 * in 1-RTT and leaves the wire byte-identical to phase 1. Turning it on requires
		 * {@link #withReplayGuard}; {@code build()} refuses the pair otherwise.
		 */
		public Builder withEarlyDataEnabled(boolean earlyDataEnabled) {
			checkNotBuilt(this);
			TlsServerConfig.this.earlyDataEnabled = earlyDataEnabled;
			return this;
		}

		/**
		 * The single-use register early data is checked against (spec FR-069). Mandatory once
		 * {@link #withEarlyDataEnabled} is on — see {@link #replayGuard()}.
		 * <p>
		 * The same instance goes to every connection of one server; building one per connection
		 * compiles, runs, and defends nothing.
		 */
		public Builder withReplayGuard(QuicReplayGuard replayGuard) {
			checkNotBuilt(this);
			TlsServerConfig.this.replayGuard = Objects.requireNonNull(replayGuard, "replayGuard");
			return this;
		}

		/** The obfuscated-ticket-age tolerance a resumption attempt is checked against (default 10 s, spec FR-045). */
		public Builder withTicketAgeTolerance(Duration ticketAgeTolerance) {
			checkNotBuilt(this);
			Objects.requireNonNull(ticketAgeTolerance, "ticketAgeTolerance");
			if (ticketAgeTolerance.isNegative()) {
				throw new IllegalArgumentException("ticketAgeTolerance must not be negative, got " + ticketAgeTolerance);
			}
			TlsServerConfig.this.ticketAgeToleranceMillis = ticketAgeTolerance.toMillis();
			return this;
		}

		@Override
		protected TlsServerConfig doBuild() {
			if (localTransportParameters.initialSourceConnectionId() == null) {
				throw new IllegalStateException(
					"localTransportParameters.initial_source_connection_id is mandatory for both roles (RFC 9000 §18.2)");
			}
			if (localTransportParameters.originalDestinationConnectionId() == null) {
				throw new IllegalStateException(
					"localTransportParameters.original_destination_connection_id is mandatory for a server (RFC 9000 §18.2)");
			}
			if (ticketKeys == null) {
				if (sessionTicketLifetimeMillis == 0) {
					sessionTicketLifetimeMillis = DEFAULT_SESSION_TICKET_LIFETIME_MILLIS;
				}
			} else if (sessionTicketLifetimeMillis == 0) {
				sessionTicketLifetimeMillis = ticketKeys.ticketLifetimeMillis();
			} else if (sessionTicketLifetimeMillis > ticketKeys.ticketLifetimeMillis()) {
				// A ticket advertised for longer than the key ring retains its sealing key presents as
				// a resumption that silently stops working, so it is refused at configuration time.
				throw new IllegalStateException(
					"sessionTicketLifetime of " + sessionTicketLifetimeMillis + " ms exceeds the " +
					ticketKeys.ticketLifetimeMillis() + " ms the configured ticketKeys can still open");
			}
			if (earlyDataEnabled && replayGuard == null) {
				// Unguarded 0-RTT accepts a replayed early-data flight as readily as a fresh one
				// (RFC 8446 §8), and nothing downstream can tell the two apart — so the combination is
				// refused here rather than at the first replay.
				throw new IllegalStateException(
					"earlyDataEnabled without a replayGuard is 0-RTT with no replay protection at all" +
					" (RFC 8446 §8) — set withReplayGuard(...), or leave early data off");
			}
			return TlsServerConfig.this;
		}
	}
}
