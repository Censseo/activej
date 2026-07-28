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
 * carries post-handshake messages (NewSessionTicket — tolerated and discarded in this profile).
 */
public enum EncryptionLevel {
	INITIAL,
	HANDSHAKE,
	ONE_RTT
}
