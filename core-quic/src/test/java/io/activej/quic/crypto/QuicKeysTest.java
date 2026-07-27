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

package io.activej.quic.crypto;

import io.activej.quic.QuicConnectionId;
import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertEquals;

/**
 * RFC 9001 Appendix A.1: initial secrets/keys/IVs/header-protection keys derived from the
 * DCID {@code 8394c8f03e515708}. These vectors were cross-checked this session against
 * Cloudflare quiche's own {@code derive_initial_secrets_v1} test and independently re-derived
 * from just the fixed v1 salt and the DCID via a from-scratch HKDF implementation — both matched
 * bit-for-bit before being hard-coded here.
 */
public class QuicKeysTest {

	private static final QuicConnectionId DCID = QuicConnectionId.of(HexFormat.of().parseHex("8394c8f03e515708"));

	@Test
	public void derivesClientInitialKeys() {
		InitialKeys keys = QuicKeys.initial(DCID);
		QuicKeys client = keys.client();

		assertEquals(QuicCipherSuite.AES_128_GCM, client.suite());
		assertHex("1f369613dd76d5467730efcbe3b1a22d", client.aeadKeyBytes());
		assertHex("fa044b2f42a3fd3b46fb255c", client.iv());
		assertHex("9f50449e04a0e810283a1e9933adedd2", client.headerProtectionKey());
	}

	@Test
	public void derivesServerInitialKeys() {
		InitialKeys keys = QuicKeys.initial(DCID);
		QuicKeys server = keys.server();

		assertEquals(QuicCipherSuite.AES_128_GCM, server.suite());
		assertHex("cf3a5331653c364c88f0f379b6067e37", server.aeadKeyBytes());
		assertHex("0ac1493ca1905853b0bba03e", server.iv());
		assertHex("c206b8d9b9f0f37644430b490eeaa314", server.headerProtectionKey());
	}

	@Test
	public void toStringDoesNotLeakKeyMaterial() {
		QuicKeys client = QuicKeys.initial(DCID).client();
		String s = client.toString();
		assertEquals(-1, s.indexOf("1f369613"));
		assertEquals(-1, s.indexOf("fa044b2f"));
		assertEquals(-1, s.indexOf("9f50449e"));
	}

	private static void assertHex(String expectedHex, byte[] actual) {
		assertEquals(expectedHex, HexFormat.of().formatHex(actual));
	}
}
