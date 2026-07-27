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

package io.activej.quic;

/**
 * Thrown when QUIC packet protection fails to authenticate or decrypt a packet
 * (RFC 9001 §5.3 AEAD failure, or an invalid Retry integrity tag, §5.8).
 * <p>
 * The message carries packet context only (e.g. encryption level, packet number) —
 * never key material, nonces, or plaintext.
 */
public class QuicDecryptionException extends Exception {
	public QuicDecryptionException(String message) {
		super(message);
	}
}
