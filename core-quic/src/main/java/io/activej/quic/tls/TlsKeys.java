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

import io.activej.quic.crypto.QuicKeys;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One encryption level's per-direction key pair (data-model.md): the {@code QuicKeys} derived
 * from the level's client and server traffic secrets via the RFC 9001 §5.1 labels
 * ({@link QuicKeys#fromTrafficSecret}). Carried to the caller inside a {@link KeyInstallation};
 * the caller owns the installation from that point (feature-01 {@code QuicKeys} contract:
 * immutable, atomically swapped).
 * <p>
 * {@code ZERO_RTT} is the one level with a single direction — RFC 9001 §4.1.4 derives 0-RTT keys
 * from {@code client_early_traffic_secret} alone, and a server never sends a 0-RTT packet. Such an
 * installation is built by {@link #ofClientOnly(QuicKeys)} and reports a {@code null}
 * {@link #serverKeys()}; the missing direction is deliberately left <b>missing</b> rather than
 * filled with a copy of the client's, because a copy would let a server-side assembler protect a
 * packet under the client's early keys — key and nonce reuse.
 */
public final class TlsKeys {
	private final QuicKeys clientKeys;
	private final @Nullable QuicKeys serverKeys;

	/** A pair of already-derived key sets, one per direction (client-issued / server-issued). */
	public TlsKeys(QuicKeys clientKeys, QuicKeys serverKeys) {
		this.clientKeys = Objects.requireNonNull(clientKeys, "clientKeys");
		this.serverKeys = Objects.requireNonNull(serverKeys, "serverKeys");
	}

	private TlsKeys(QuicKeys clientKeys) {
		this.clientKeys = Objects.requireNonNull(clientKeys, "clientKeys");
		this.serverKeys = null;
	}

	/**
	 * A one-directional installation for {@code ZERO_RTT}: only the client sends 0-RTT packets, so
	 * only the client direction has keys (RFC 9001 §4.1.4).
	 */
	public static TlsKeys ofClientOnly(QuicKeys clientKeys) {
		return new TlsKeys(clientKeys);
	}

	/** The key set protecting packets sent by the client (server side: opens them). */
	public QuicKeys clientKeys() {
		return clientKeys;
	}

	/**
	 * The key set protecting packets sent by the server (client side: opens them), or {@code null}
	 * for the one-directional {@code ZERO_RTT} installation of {@link #ofClientOnly(QuicKeys)}.
	 */
	public @Nullable QuicKeys serverKeys() {
		return serverKeys;
	}

	@Override
	public String toString() {
		return "TlsKeys[" + clientKeys.suite() + "]";
	}
}
