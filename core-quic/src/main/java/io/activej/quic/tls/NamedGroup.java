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

import org.jetbrains.annotations.Nullable;

/**
 * The two ECDHE named groups of the QUIC profile (RFC 8446 §4.2.7, RFC 9001 §8.1):
 * {@code x25519} and {@code secp256r1}. No other group is advertised or accepted;
 * post-quantum groups are never offered (RFC 8446 named-group registry).
 */
public enum NamedGroup {
	SECP256R1(0x0017),
	X25519(0x001d);

	private final int code;

	NamedGroup(int code) {
		this.code = code;
	}

	/** Resolves a wire code to a group, or {@code null} for an unknown/GREASE code (tolerated, never selected — RFC 8701). */
	public static @Nullable NamedGroup of(int code) {
		return switch (code) {
			case 0x0017 -> SECP256R1;
			case 0x001d -> X25519;
			default -> null;
		};
	}

	/** The 2-byte named-group codepoint on the wire (RFC 8446 §4.2.7). */
	public int code() {
		return code;
	}
}
