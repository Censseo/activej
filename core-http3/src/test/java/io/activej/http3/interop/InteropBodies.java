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

package io.activej.http3.interop;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The body fixtures shared by the interop suites — one copy of each, so the two suites cannot
 * drift (a changed seed range in one and not the other would weaken the "distinct seeds give
 * distinct bytes" mismatch detection both rely on).
 */
final class InteropBodies {
	private InteropBodies() {}

	/** A deterministic body: distinct seeds give distinct bytes, so a mixed-up exchange is a mismatch. */
	static byte[] patternBody(int length, int seed) {
		byte[] body = new byte[length];
		for (int i = 0; i < length; i++) {
			body[i] = (byte) (seed + i * 31);
		}
		return body;
	}

	static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is always available", e);
		}
	}
}
