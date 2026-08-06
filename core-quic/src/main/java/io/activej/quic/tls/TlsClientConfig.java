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

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Immutable configuration of a client-side {@link TlsEngine} (data-model.md): the remote name
 * (a hostname or an IP literal), the trust policy, the local QUIC transport parameters sent in
 * the ClientHello, and the determinism hooks — {@code SecureRandom} (client random) and the
 * ephemeral-key source — that let tests replay recorded handshakes (RFC 8448 §3), plus the
 * per-engine handshake-message size bound override.
 * <p>
 * Trust policy (FR-011): by default the certificate chain is validated by the platform's
 * default PKIX {@link X509TrustManager} and — when the remote name is a hostname — the leaf
 * certificate must also pass RFC 6125 endpoint identification against that hostname. Both can be
 * tuned: {@link Builder#withTrustManager(X509TrustManager)} substitutes the chain validator,
 * {@link Builder#withEndpointIdentification(boolean)} toggles identification, and the explicitly
 * named {@link Builder#insecureTrustAll()} disables <b>both</b> — for development only.
 * <p>
 * Built once via the one-shot {@link Builder} ({@code AbstractBuilder} convention); the
 * configuration is immutable after {@code build()} and never mutated mid-handshake (spec §Data
 * &amp; State). RFC 9000 §18.2 makes {@code initial_source_connection_id} mandatory in the local
 * transport parameters — a missing one is a configuration error, not a mid-handshake surprise.
 * <p>
 * Resumption (spec FR-051b, research D-6): this configuration — never
 * {@code QuicConnection.TlsEngineFactory}, whose signature is frozen — carries the ticket to offer
 * ({@link Builder#withSessionTicket}), the store to fill with the tickets the server issues
 * ({@link Builder#withSessionCache}), whether early data may be offered
 * ({@link Builder#withEarlyDataEnabled}) and the two FR-043a bounds on an accepted ticket. With none
 * of them set the engine performs a full handshake and behaves exactly as it did in phase 1.
 */
public final class TlsClientConfig {

	/**
	 * Default bound on one sealed session ticket (spec FR-043a), matching
	 * {@code QuicConnectionSettings.maxSessionTicketSize()}'s default — a deployment that tunes that
	 * setting passes the value through {@link Builder#withMaxSessionTicketSize}.
	 */
	private static final MemSize DEFAULT_MAX_SESSION_TICKET_SIZE = MemSize.kilobytes(8);

	/**
	 * Default bound on the post-handshake {@code NewSessionTicket} messages one connection may
	 * deliver (spec FR-043a), matching {@code QuicConnectionSettings.maxSessionTicketsPerConnection()}.
	 */
	private static final int DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION = 8;

	private final String remoteName;
	private final QuicTransportParameters localTransportParameters;
	private @Nullable X509TrustManager trustManager;
	private boolean trustAll;
	private @Nullable Boolean endpointIdentification;
	private SecureRandom secureRandom = new SecureRandom();
	private Function<NamedGroup, KeyPair> ephemeralKeySource = TlsKeyExchanges::generateKeyPair;
	private MemSize maxHandshakeMessageSize = TlsMessages.MAX_HANDSHAKE_MESSAGE_SIZE;
	private @Nullable QuicSessionTicket sessionTicket;
	private @Nullable QuicSessionCache sessionCache;
	private int remotePort;
	private boolean earlyDataEnabled;
	private MemSize maxSessionTicketSize = DEFAULT_MAX_SESSION_TICKET_SIZE;
	private int maxSessionTicketsPerConnection = DEFAULT_MAX_SESSION_TICKETS_PER_CONNECTION;
	private LongSupplier currentTimeMillis = System::currentTimeMillis;

	private TlsClientConfig(String remoteName, QuicTransportParameters localTransportParameters) {
		this.remoteName = requireRemoteName(remoteName);
		this.localTransportParameters = Objects.requireNonNull(localTransportParameters, "localTransportParameters");
	}

	/**
	 * Starts the one-shot builder for a client configuration (FR-011, FR-019).
	 *
	 * @param remoteName the peer's hostname (SNI sent per RFC 6066 §3, RFC 6125 endpoint
	 *        identification) or IP literal (no SNI)
	 * @param localTransportParameters this endpoint's QUIC transport parameters (RFC 9000 §18),
	 *        sent in the ClientHello's {@code quic_transport_parameters} extension (RFC 9001 §8.2)
	 */
	public static Builder builder(String remoteName, QuicTransportParameters localTransportParameters) {
		return new TlsClientConfig(remoteName, localTransportParameters).new Builder();
	}

	/** The name of the remote peer — a hostname (SNI sent, RFC 6125 identification) or an IP literal (FR-019). */
	public String remoteName() {
		return remoteName;
	}

	/** {@code true} when {@link #remoteName()} is a hostname rather than an IPv4/IPv6 literal. */
	public boolean isHostname() {
		return isHostname(remoteName);
	}

	/** The transport parameters sent to the server in the ClientHello (RFC 9001 §8.2). */
	public QuicTransportParameters localTransportParameters() {
		return localTransportParameters;
	}

	/**
	 * The chain validator — the platform default PKIX trust manager unless overridden;
	 * {@code null} in {@link Builder#insecureTrustAll()} mode (validation skipped).
	 */
	public @Nullable X509TrustManager trustManager() {
		return trustManager;
	}

	/**
	 * Whether RFC 6125 endpoint identification runs against {@link #remoteName()} after chain
	 * validation. Default: on for hostnames, off for IP literals; always off in
	 * {@link Builder#insecureTrustAll()} mode.
	 */
	public boolean endpointIdentification() {
		if (trustAll) return false;
		return endpointIdentification != null ? endpointIdentification : isHostname();
	}

	/** Randomness for the ClientHello random (default: a shared {@link SecureRandom}). */
	public SecureRandom secureRandom() {
		return secureRandom;
	}

	/** Ephemeral ECDHE key-pair source for the offered key share (default: JDK generation). */
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
	 * The ticket to offer as a pre-shared key (spec FR-044), or {@code null} for a full handshake —
	 * the phase-1 behaviour. A ticket whose ALPN, server name or cipher suite does not match this
	 * connection is <b>ignored</b> and a full handshake performed (spec FR-047); it is never a
	 * configuration failure, because the safe direction of every resumption decision is the full
	 * handshake.
	 */
	public @Nullable QuicSessionTicket sessionTicket() {
		return sessionTicket;
	}

	/**
	 * The store to fill with the tickets the server issues (spec FR-058), or {@code null} to keep
	 * none — in which case {@link TlsEngineResult#issuedTickets()} is the only route to them.
	 */
	public @Nullable QuicSessionCache sessionCache() {
		return sessionCache;
	}

	/**
	 * The remote UDP port, which completes the {@code (server name, port, ALPN)} origin key
	 * {@link #sessionCache()} is keyed by; {@code 0} when no store is configured. Supplied with the
	 * store because it is meaningless without one.
	 */
	public int remotePort() {
		return remotePort;
	}

	/**
	 * Whether the ClientHello may offer {@code early_data} (spec FR-044). Default {@code false}: a
	 * ticket alone resumes the session without sending anything in 0-RTT, and the decision to send
	 * early data belongs to the layer that knows what it would send — for HTTP/3, one that has
	 * remembered SETTINGS to obey (spec FR-062).
	 */
	public boolean earlyDataEnabled() {
		return earlyDataEnabled;
	}

	/**
	 * Bound on one sealed ticket arriving in a post-handshake {@code NewSessionTicket}
	 * (spec FR-043a), default 8 KB. Exceeding it is a connection error, not a truncation.
	 */
	public MemSize maxSessionTicketSize() {
		return maxSessionTicketSize;
	}

	/**
	 * Bound on the tickets one connection may deliver (spec FR-043a), default 8; {@code 0} accepts
	 * none. Without it a server could buy an unbounded number of PSK derivations on the client's
	 * reactor thread.
	 */
	public int maxSessionTicketsPerConnection() {
		return maxSessionTicketsPerConnection;
	}

	/**
	 * The wall clock the engine reads for a ticket's issue time and for the RFC 8446 §4.2.11.1
	 * obfuscated ticket age; {@code System::currentTimeMillis} by default.
	 * <p>
	 * Injected rather than read directly because {@code io.activej.quic.tls} is reactor-free by
	 * construction (ADR-016) and may not reach {@code reactor.currentTimeMillis()}. A consumer that
	 * also supplies a {@link QuicSessionCache} MUST give both the <b>same</b> supplier — expiry
	 * decided against two different clocks is expiry decided at random.
	 */
	public LongSupplier currentTimeMillis() {
		return currentTimeMillis;
	}

	static boolean isHostname(String remoteName) {
		// IPv6 literals contain ':'; IPv4 literals consist of digits and dots only
		if (remoteName.indexOf(':') >= 0) return false;
		for (int i = 0; i < remoteName.length(); i++) {
			char c = remoteName.charAt(i);
			if (c != '.' && (c < '0' || c > '9')) return true;
		}
		return false;
	}

	private static String requireRemoteName(String remoteName) {
		Objects.requireNonNull(remoteName, "remoteName");
		if (remoteName.isEmpty()) {
			throw new IllegalArgumentException("remoteName must not be empty");
		}
		return remoteName;
	}

	private static X509TrustManager platformTrustManager() {
		try {
			TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
			factory.init((KeyStore) null); // the platform's default CA store
			for (TrustManager trustManager : factory.getTrustManagers()) {
				if (trustManager instanceof X509TrustManager x509TrustManager) {
					return x509TrustManager;
				}
			}
			throw new IllegalStateException("The platform PKIX TrustManagerFactory provides no X509TrustManager");
		} catch (NoSuchAlgorithmException | java.security.KeyStoreException e) {
			throw new IllegalStateException("Cannot initialize the platform PKIX trust manager", e);
		}
	}

	/**
	 * The one-shot {@code AbstractBuilder} for {@link TlsClientConfig}: every {@code withXxx}
	 * is optional (safe defaults per FR-011/FR-017), {@code build()} freezes the configuration.
	 */
	public final class Builder extends AbstractBuilder<Builder, TlsClientConfig> {
		private Builder() {
		}

		/** Substitutes the certificate-chain validator (default: the platform PKIX trust manager). */
		public Builder withTrustManager(X509TrustManager trustManager) {
			checkNotBuilt(this);
			TlsClientConfig.this.trustManager = Objects.requireNonNull(trustManager, "trustManager");
			return this;
		}

		/**
		 * <b>Insecure, development-only</b> mode (FR-011): skips certificate-chain validation
		 * <b>and</b> RFC 6125 endpoint identification entirely — any server certificate,
		 * including self-signed, expired or name-mismatched, is accepted. Never use in
		 * production: the connection is open to man-in-the-middle attacks.
		 */
		public Builder insecureTrustAll() {
			checkNotBuilt(this);
			TlsClientConfig.this.trustAll = true;
			TlsClientConfig.this.trustManager = null;
			return this;
		}

		/** Toggles RFC 6125 endpoint identification (default: on for hostnames, off for IP literals). */
		public Builder withEndpointIdentification(boolean endpointIdentification) {
			checkNotBuilt(this);
			TlsClientConfig.this.endpointIdentification = endpointIdentification;
			return this;
		}

		/** Determinism hook: the randomness behind the ClientHello random. */
		public Builder withSecureRandom(SecureRandom secureRandom) {
			checkNotBuilt(this);
			TlsClientConfig.this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
			return this;
		}

		/** Determinism hook: ephemeral ECDHE key-pair source (tests inject recorded keys). */
		public Builder withEphemeralKeySource(Function<NamedGroup, KeyPair> ephemeralKeySource) {
			checkNotBuilt(this);
			TlsClientConfig.this.ephemeralKeySource = Objects.requireNonNull(ephemeralKeySource, "ephemeralKeySource");
			return this;
		}

		/** Per-engine override of the FR-017 handshake-message size bound (stricter direction). */
		public Builder withMaxHandshakeMessageSize(MemSize maxHandshakeMessageSize) {
			checkNotBuilt(this);
			TlsClientConfig.this.maxHandshakeMessageSize = Objects.requireNonNull(maxHandshakeMessageSize, "maxHandshakeMessageSize");
			return this;
		}

		/**
		 * Offers this ticket as a pre-shared key (spec FR-044). Absent, the engine performs a full
		 * handshake; present but not matching this connection, it is ignored and a full handshake
		 * performed (spec FR-047).
		 */
		public Builder withSessionTicket(QuicSessionTicket sessionTicket) {
			checkNotBuilt(this);
			TlsClientConfig.this.sessionTicket = Objects.requireNonNull(sessionTicket, "sessionTicket");
			return this;
		}

		/**
		 * The store the tickets this server issues are put into, and the remote port that completes
		 * its {@code (server name, port, ALPN)} origin key (spec FR-058).
		 * <p>
		 * The store is read and written on the reactor thread only, and neither blocks nor returns a
		 * {@code Promise} — see {@link QuicSessionCache}.
		 */
		public Builder withSessionCache(QuicSessionCache sessionCache, int remotePort) {
			checkNotBuilt(this);
			if (remotePort <= 0 || remotePort > 65535) {
				throw new IllegalArgumentException("remotePort must be a usable UDP port, got " + remotePort);
			}
			TlsClientConfig.this.sessionCache = Objects.requireNonNull(sessionCache, "sessionCache");
			TlsClientConfig.this.remotePort = remotePort;
			return this;
		}

		/**
		 * Allows the ClientHello to offer {@code early_data} beside the pre-shared key
		 * (spec FR-044). Default {@code false}, which resumes without sending anything in 0-RTT.
		 */
		public Builder withEarlyDataEnabled(boolean earlyDataEnabled) {
			checkNotBuilt(this);
			TlsClientConfig.this.earlyDataEnabled = earlyDataEnabled;
			return this;
		}

		/** Per-engine override of the FR-043a sealed-ticket size bound (default 8 KB). */
		public Builder withMaxSessionTicketSize(MemSize maxSessionTicketSize) {
			checkNotBuilt(this);
			Objects.requireNonNull(maxSessionTicketSize, "maxSessionTicketSize");
			if (maxSessionTicketSize.toLong() <= 0) {
				throw new IllegalArgumentException("maxSessionTicketSize must be positive, got " + maxSessionTicketSize);
			}
			TlsClientConfig.this.maxSessionTicketSize = maxSessionTicketSize;
			return this;
		}

		/** Per-engine override of the FR-043a tickets-per-connection bound (default 8; 0 accepts none). */
		public Builder withMaxSessionTicketsPerConnection(int maxSessionTicketsPerConnection) {
			checkNotBuilt(this);
			if (maxSessionTicketsPerConnection < 0) {
				throw new IllegalArgumentException(
					"maxSessionTicketsPerConnection must not be negative, got " + maxSessionTicketsPerConnection);
			}
			TlsClientConfig.this.maxSessionTicketsPerConnection = maxSessionTicketsPerConnection;
			return this;
		}

		/**
		 * The clock behind ticket issue times and obfuscated ticket ages — pass the very supplier the
		 * {@link QuicSessionCache} was built with (typically {@code reactor::currentTimeMillis}).
		 */
		public Builder withCurrentTimeMillis(LongSupplier currentTimeMillis) {
			checkNotBuilt(this);
			TlsClientConfig.this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
			return this;
		}

		@Override
		protected TlsClientConfig doBuild() {
			if (localTransportParameters.initialSourceConnectionId() == null) {
				throw new IllegalStateException(
					"localTransportParameters.initial_source_connection_id is mandatory for both roles (RFC 9000 §18.2)");
			}
			if (!trustAll && trustManager == null) {
				trustManager = platformTrustManager();
			}
			return TlsClientConfig.this;
		}
	}
}
