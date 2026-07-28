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
 * Static factories for the TLS 1.3 handshake engines of the QUIC profile (RFC 8446 restricted
 * by RFC 9001 §8). All determinism hooks ({@code SecureRandom}, ephemeral-key source) live
 * inside the role configs, so a fully seeded engine reproduces a recorded handshake byte-for-byte.
 */
public final class QuicTls {

	private QuicTls() {
	}

	/** A server-side handshake engine (US3): one per connection attempt. */
	public static TlsServerEngine serverEngine(TlsServerConfig config) {
		return new TlsServerEngine(config);
	}

	/** A client-side handshake engine (US4): one per connection attempt. */
	public static TlsClientEngine clientEngine(TlsClientConfig config) {
		return new TlsClientEngine(config);
	}
}
