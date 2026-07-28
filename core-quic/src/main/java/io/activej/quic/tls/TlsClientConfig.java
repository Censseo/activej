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
 */
public final class TlsClientConfig {
	private final String remoteName;
	private final QuicTransportParameters localTransportParameters;
	private @Nullable X509TrustManager trustManager;
	private boolean trustAll;
	private @Nullable Boolean endpointIdentification;
	private SecureRandom secureRandom = new SecureRandom();
	private Function<NamedGroup, KeyPair> ephemeralKeySource = TlsKeyExchanges::generateKeyPair;
	private MemSize maxHandshakeMessageSize = TlsMessages.MAX_HANDSHAKE_MESSAGE_SIZE;

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
