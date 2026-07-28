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

/**
 * One encryption level's per-direction key pair (data-model.md): the {@code QuicKeys} derived
 * from the level's client and server traffic secrets via the RFC 9001 §5.1 labels
 * ({@link QuicKeys#fromTrafficSecret}). Carried to the caller inside a {@link KeyInstallation};
 * the caller owns the installation from that point (feature-01 {@code QuicKeys} contract:
 * immutable, atomically swapped).
 */
public final class TlsKeys {
	private final QuicKeys clientKeys;
	private final QuicKeys serverKeys;

	/** A pair of already-derived key sets, one per direction (client-issued / server-issued). */
	public TlsKeys(QuicKeys clientKeys, QuicKeys serverKeys) {
		this.clientKeys = clientKeys;
		this.serverKeys = serverKeys;
	}

	/** The key set protecting packets sent by the client (server side: opens them). */
	public QuicKeys clientKeys() {
		return clientKeys;
	}

	/** The key set protecting packets sent by the server (client side: opens them). */
	public QuicKeys serverKeys() {
		return serverKeys;
	}

	@Override
	public String toString() {
		return "TlsKeys[" + clientKeys.suite() + "]";
	}
}
