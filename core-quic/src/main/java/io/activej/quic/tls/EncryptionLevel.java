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
 * A QUIC encryption level (RFC 9001 §4.1.2) as seen by the TLS handshake engine: each level
 * carries its own reliable, ordered CRYPTO stream. The INITIAL-level stream carries ClientHello
 * (server side) / ServerHello (client side); the HANDSHAKE-level stream carries
 * EncryptedExtensions, Certificate, CertificateVerify and Finished; the ONE_RTT-level stream
 * carries post-handshake messages (NewSessionTicket).
 * <p>
 * {@code ZERO_RTT} is the one level that carries <b>no</b> CRYPTO stream — RFC 9000 §12.5 forbids a
 * CRYPTO frame in a 0-RTT packet — and the one level that does not own a packet number space: it
 * shares the Application space with {@code ONE_RTT} (RFC 9000 §12.3). Both properties are read
 * through {@link #hasCryptoStream()} and {@link #packetNumberSpace()} rather than inferred, and both
 * are asserted by {@code EncryptionLevelTest} (research D-5).
 * <p>
 * {@code ZERO_RTT} is appended <b>last</b> deliberately: {@code values().length} sizes
 * {@code ordinal()}-indexed arrays in {@code TlsClientEngine}, {@code TlsServerEngine},
 * {@code QuicConnection} and {@code SendQueue}, so appending only widens them and leaves every
 * pre-existing ordinal stable.
 */
public enum EncryptionLevel {
	INITIAL(Space.INITIAL, true),
	HANDSHAKE(Space.HANDSHAKE, true),
	ONE_RTT(Space.APPLICATION, true),
	ZERO_RTT(Space.APPLICATION, false);

	/**
	 * The three RFC 9000 §12.3 packet number spaces. There is no fourth, and this type is what makes
	 * that structural rather than a convention: 0-RTT and 1-RTT packets are numbered from the same
	 * sequence, so a per-level space would number a 0-RTT and a 1-RTT packet identically and leave
	 * their acknowledgements indistinguishable.
	 */
	public enum Space {
		INITIAL,
		HANDSHAKE,
		APPLICATION
	}

	private final Space packetNumberSpace;
	private final boolean cryptoStream;

	EncryptionLevel(Space packetNumberSpace, boolean cryptoStream) {
		this.packetNumberSpace = packetNumberSpace;
		this.cryptoStream = cryptoStream;
	}

	/** The packet number space this level's packets are numbered and acknowledged in (RFC 9000 §12.3). */
	public Space packetNumberSpace() {
		return packetNumberSpace;
	}

	/** Whether packets at this level may carry a CRYPTO frame, and therefore whether the level has a CRYPTO stream at all (RFC 9000 §12.5). */
	public boolean hasCryptoStream() {
		return cryptoStream;
	}
}
