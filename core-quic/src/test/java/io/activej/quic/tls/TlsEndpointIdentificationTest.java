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

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

/**
 * IP-literal parsing behind RFC 6125 {@code iPAddress} SAN matching (FR-011): strict syntax,
 * network-order bytes, never a DNS lookup. Malformed literals parse to {@code null} — the
 * reference identifier is then treated as a DNS name and simply matches no certificate.
 */
public class TlsEndpointIdentificationTest {

	@Test
	public void ipv4LiteralsParseToNetworkOrderBytes() {
		assertArrayEquals(new byte[] {127, 0, 0, 1}, TlsEndpointIdentification.parseIpLiteral("127.0.0.1"));
		assertArrayEquals(new byte[] {0, 0, 0, 0}, TlsEndpointIdentification.parseIpLiteral("0.0.0.0"));
		assertArrayEquals(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255},
			TlsEndpointIdentification.parseIpLiteral("255.255.255.255"));
	}

	@Test
	public void malformedIpv4LiteralsAreRejected() {
		assertNull(TlsEndpointIdentification.parseIpLiteral("256.1.1.1"));
		assertNull(TlsEndpointIdentification.parseIpLiteral("1.2.3"));
		assertNull(TlsEndpointIdentification.parseIpLiteral("1.2.3.4.5"));
		assertNull("leading zeros are rejected (octal ambiguity)", TlsEndpointIdentification.parseIpLiteral("01.2.3.4"));
		assertNull(TlsEndpointIdentification.parseIpLiteral("1.2.3."));
		assertNull(TlsEndpointIdentification.parseIpLiteral("1.2.3.a"));
		assertNull(TlsEndpointIdentification.parseIpLiteral("example.test"));
		assertNull(TlsEndpointIdentification.parseIpLiteral(""));
	}

	@Test
	public void ipv6LiteralsParseToNetworkOrderBytes() {
		assertArrayEquals(groups(0, 0, 0, 0, 0, 0, 0, 1), TlsEndpointIdentification.parseIpLiteral("::1"));
		assertArrayEquals(groups(0, 0, 0, 0, 0, 0, 0, 0), TlsEndpointIdentification.parseIpLiteral("::"));
		assertArrayEquals(groups(1, 2, 3, 4, 5, 6, 7, 8), TlsEndpointIdentification.parseIpLiteral("1:2:3:4:5:6:7:8"));
		assertArrayEquals(groups(0x2001, 0x0db8, 0, 0, 0, 0, 0, 1), TlsEndpointIdentification.parseIpLiteral("2001:db8::1"));
		assertArrayEquals(groups(0x2001, 0x0db8, 0x85a3, 0, 0, 0x8a2e, 0x0370, 0x7334),
			TlsEndpointIdentification.parseIpLiteral("2001:db8:85a3::8a2e:370:7334"));
		assertArrayEquals(groups(1, 2, 3, 4, 5, 6, 7, 0), TlsEndpointIdentification.parseIpLiteral("1:2:3:4:5:6:7::"));
		assertArrayEquals(groups(0xabcd, 0xef01, 0x2345, 0x6789, 0xabcd, 0xef01, 0x2345, 0x6789),
			TlsEndpointIdentification.parseIpLiteral("abcd:ef01:2345:6789:abcd:ef01:2345:6789"));
	}

	@Test
	public void ipv6EmbeddedIpv4TailParsesAsTwoGroups() {
		assertArrayEquals(groups(0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001),
			TlsEndpointIdentification.parseIpLiteral("::ffff:127.0.0.1"));
		assertArrayEquals(groups(0, 0, 0, 0, 0, 0, 0x0102, 0x0304),
			TlsEndpointIdentification.parseIpLiteral("::1.2.3.4"));
	}

	@Test
	public void malformedIpv6LiteralsAreRejected() {
		assertNull(TlsEndpointIdentification.parseIpLiteral("1::2::3")); // two compression runs
		assertNull(TlsEndpointIdentification.parseIpLiteral("1:2:3:4:5:6:7")); // 7 groups, uncompressed
		assertNull(TlsEndpointIdentification.parseIpLiteral("1:2:3:4:5:6:7:8:9")); // 9 groups
		assertNull(TlsEndpointIdentification.parseIpLiteral("1:2:3:4:5:6:7:8::")); // "::" compresses nothing
		assertNull(TlsEndpointIdentification.parseIpLiteral("12345::")); // a group is 1..4 hex digits
		assertNull(TlsEndpointIdentification.parseIpLiteral("g::1")); // not hex
		assertNull(TlsEndpointIdentification.parseIpLiteral(":1:2:3:4:5:6:7")); // stray leading colon
		assertNull(TlsEndpointIdentification.parseIpLiteral("1.2.3.4::")); // embedded IPv4 outside the tail
		assertNull(TlsEndpointIdentification.parseIpLiteral("::ffff:999.0.0.1")); // bad embedded IPv4
		assertNull(TlsEndpointIdentification.parseIpLiteral(":"));
	}

	private static byte[] groups(int... hextets) {
		byte[] bytes = new byte[16];
		for (int i = 0; i < hextets.length; i++) {
			bytes[i * 2] = (byte) (hextets[i] >>> 8);
			bytes[i * 2 + 1] = (byte) hextets[i];
		}
		return bytes;
	}
}
