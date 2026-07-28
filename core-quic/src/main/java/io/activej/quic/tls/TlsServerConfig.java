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

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Function;

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
 */
public final class TlsServerConfig {
	private final TlsServerIdentity identity;
	private final QuicTransportParameters localTransportParameters;
	private SecureRandom secureRandom = new SecureRandom();
	private Function<NamedGroup, KeyPair> ephemeralKeySource = TlsKeyExchanges::generateKeyPair;
	private MemSize maxHandshakeMessageSize = TlsMessages.MAX_HANDSHAKE_MESSAGE_SIZE;

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
			return TlsServerConfig.this;
		}
	}
}
