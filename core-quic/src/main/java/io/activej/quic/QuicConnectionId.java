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

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A QUIC connection ID (RFC 9000 §5.1): an opaque value of 0-20 bytes used to identify a
 * connection independently of the network path. Immutable; value equality.
 */
public final class QuicConnectionId {
	public static final int MAX_LENGTH = 20;

	private static final QuicConnectionId EMPTY = new QuicConnectionId(new byte[0]);
	private static final SecureRandom SHARED_RANDOM = new SecureRandom();

	private final byte[] bytes;

	private QuicConnectionId(byte[] bytes) {
		this.bytes = bytes;
	}

	/** Wraps a defensive copy of {@code bytes}. @throws IllegalArgumentException if longer than {@value #MAX_LENGTH} bytes. */
	public static QuicConnectionId of(byte[] bytes) {
		if (bytes.length == 0) {
			return EMPTY;
		}
		checkLength(bytes.length);
		return new QuicConnectionId(bytes.clone());
	}

	/** Generates a random connection ID of {@code length} bytes using a shared {@link SecureRandom} instance. */
	public static QuicConnectionId random(int length) {
		return random(length, SHARED_RANDOM);
	}

	/** Generates a random connection ID of {@code length} bytes using {@code random} (tests inject a seeded instance for determinism). */
	public static QuicConnectionId random(int length, SecureRandom random) {
		checkLength(length);
		if (length == 0) {
			return EMPTY;
		}
		byte[] bytes = new byte[length];
		random.nextBytes(bytes);
		return new QuicConnectionId(bytes);
	}

	private static void checkLength(int length) {
		if (length < 0 || length > MAX_LENGTH) {
			throw new IllegalArgumentException(
				"Connection ID length must be in [0, " + MAX_LENGTH + "]: " + length);
		}
	}

	/** Defensive copy of the connection ID's bytes. */
	public byte[] bytes() {
		return bytes.clone();
	}

	/** Length in bytes, always in {@code [0, MAX_LENGTH]}. */
	public int length() {
		return bytes.length;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof QuicConnectionId other)) return false;
		return Arrays.equals(bytes, other.bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return "QuicConnectionId[" + bytes.length + " bytes]";
	}
}
