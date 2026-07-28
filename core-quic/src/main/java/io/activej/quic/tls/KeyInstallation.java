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

/**
 * A key-installation event on a {@link TlsEngineResult} (FR-005, FR-013): the engine has derived
 * one encryption level's per-direction {@link TlsKeys} and hands them to the connection layer
 * (feature 03), which swaps them into its packet protection. Initial-level keys are never an
 * engine output — they remain feature 01's derivation from the client's DCID (RFC 9001 §5.2).
 */
public final class KeyInstallation {
	private final EncryptionLevel level;
	private final TlsKeys keys;

	/** An installation event: {@code keys} are ready to protect {@code level}'s packet number space. */
	public KeyInstallation(EncryptionLevel level, TlsKeys keys) {
		this.level = level;
		this.keys = keys;
	}

	/** The level whose keys are ready — HANDSHAKE, then ONE_RTT after the peer's Finished verifies. */
	public EncryptionLevel level() {
		return level;
	}

	/** The per-direction key pair for {@link #level()}. */
	public TlsKeys keys() {
		return keys;
	}

	@Override
	public String toString() {
		return "KeyInstallation[" + level + ", " + keys + "]";
	}
}
