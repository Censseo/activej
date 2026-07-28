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

import io.activej.bytebuf.ByteBuf;
import io.activej.quic.tls.KeyShareExt.KeyShareEntry;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * SC-006 determinism: a client engine seeded with the RFC 8448 §3 trace's client random and
 * ephemeral x25519 private key emits a ClientHello that matches the published trace on every
 * field and extension the trace defines — random, cipher suites, supported_versions,
 * supported_groups, key_share bytes, signature_algorithms, psk_key_exchange_modes — plus the
 * QUIC-mandatory ALPN {@code h3} and {@code quic_transport_parameters} extensions the plain-TLS
 * trace does not carry.
 * <p>
 * Where the QUIC profile deliberately narrows the trace (only x25519/secp256r1 groups, no
 * PKCS#1 v1.5 signature schemes per FR-010) the emitted values are asserted to be the
 * profile-correct subset of the trace's list, in the trace's order.
 */
public class TlsClientRfc8448Test {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final QuicTransportParameters CLIENT_PARAMS = QuicTransportParameters.defaults(new byte[] {10, 11, 12, 13});

	@Test
	public void seededClientHelloMatchesTheRfc8448Trace() throws Exception {
		TlsEngine client = seededClient("server");
		ClientHelloMessage clientHello = emitClientHello(client);

		// every trace-defined field matches byte-for-byte
		assertEquals(ClientHelloMessage.LEGACY_VERSION, clientHello.legacyVersion);
		assertArrayEquals(Rfc8448.CLIENT_RANDOM, clientHello.random());
		assertArrayEquals(new byte[0], clientHello.legacySessionId());
		assertArrayEquals(new int[] {
				TlsCipherSuite.TLS_AES_128_GCM_SHA256.code(),
				TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256.code(),
				TlsCipherSuite.TLS_AES_256_GCM_SHA384.code()},
			clientHello.cipherSuites());
		assertArrayEquals(new int[] {0}, clientHello.compressionMethods());

		SupportedVersionsExt versions = find(clientHello.extensions, SupportedVersionsExt.class);
		assertNotNull(versions);
		assertFalse(versions.selectedForm);
		assertArrayEquals(new int[] {SupportedVersionsExt.TLS_1_3}, versions.versions());

		// supported_groups: the profile's two groups, in the trace's order (the trace's first two)
		SupportedGroupsExt groups = find(clientHello.extensions, SupportedGroupsExt.class);
		assertNotNull(groups);
		assertArrayEquals(new int[] {NamedGroup.X25519.code(), NamedGroup.SECP256R1.code()}, groups.groupCodes());

		// key_share: the trace's x25519 share, byte-for-byte from the injected ephemeral key
		KeyShareExt keyShare = find(clientHello.extensions, KeyShareExt.class);
		assertNotNull(keyShare);
		assertNotNull(keyShare.clientShares);
		assertEquals(1, keyShare.clientShares.size());
		KeyShareEntry share = keyShare.clientShares.get(0);
		assertEquals(NamedGroup.X25519.code(), share.groupCode);
		assertArrayEquals(Rfc8448.CLIENT_EPHEMERAL_PUBLIC, share.keyExchange());

		// signature_algorithms: every offered scheme except ed25519 appears in the trace's list;
		// PKCS#1 v1.5 (0x0401/0x0501/0x0601) is never offered (FR-010)
		SignatureAlgorithmsExt signatureAlgorithms = find(clientHello.extensions, SignatureAlgorithmsExt.class);
		assertNotNull(signatureAlgorithms);
		int[] traceSchemes = {
			0x0403, 0x0503, 0x0603, 0x0203, 0x0804, 0x0805, 0x0806,
			0x0401, 0x0501, 0x0601, 0x0402, 0x0502, 0x0602, 0x0202};
		for (int scheme : signatureAlgorithms.schemeCodes()) {
			if (scheme == SignatureScheme.ED25519.code()) continue;
			assertTrue("scheme 0x" + Integer.toHexString(scheme) + " is in the trace's list",
				Arrays.stream(traceSchemes).anyMatch(trace -> trace == scheme));
		}
		for (int scheme : signatureAlgorithms.schemeCodes()) {
			assertTrue("PKCS#1 v1.5 is never offered (FR-010)",
				scheme != 0x0401 && scheme != 0x0501 && scheme != 0x0601);
		}
		assertTrue(Arrays.stream(signatureAlgorithms.schemeCodes())
			.anyMatch(scheme -> scheme == SignatureScheme.ECDSA_SECP256R1_SHA256.code()));
		assertTrue(Arrays.stream(signatureAlgorithms.schemeCodes())
			.anyMatch(scheme -> scheme == SignatureScheme.ED25519.code()));

		// psk_key_exchange_modes: exactly psk_dhe_ke(1), matching the trace
		PskKeyExchangeModesExt pskModes = find(clientHello.extensions, PskKeyExchangeModesExt.class);
		assertNotNull(pskModes);
		assertArrayEquals(new int[] {1}, pskModes.modes());

		// the QUIC-mandatory additions the plain-TLS trace does not carry
		AlpnExt alpn = find(clientHello.extensions, AlpnExt.class);
		assertNotNull(alpn);
		assertEquals(List.of("h3"), alpn.protocols);
		QuicTransportParametersExt transportParameters = find(clientHello.extensions, QuicTransportParametersExt.class);
		assertNotNull(transportParameters);
		assertEquals(CLIENT_PARAMS, transportParameters.parameters);

		// the trace connects to the host "server" and carries SNI accordingly
		ServerNameExt serverName = find(clientHello.extensions, ServerNameExt.class);
		assertNotNull(serverName);
		assertEquals("server", serverName.hostName);
	}

	@Test
	public void sniIsSentForAHostnameButNotForAnIpLiteral() throws Exception {
		ClientHelloMessage hostnameHello = emitClientHello(seededClient("example.test"));
		ServerNameExt serverName = find(hostnameHello.extensions, ServerNameExt.class);
		assertNotNull(serverName);
		assertEquals("example.test", serverName.hostName);

		ClientHelloMessage ipv4Hello = emitClientHello(seededClient("127.0.0.1"));
		assertNull(find(ipv4Hello.extensions, ServerNameExt.class));

		ClientHelloMessage ipv6Hello = emitClientHello(seededClient("::1"));
		assertNull(find(ipv6Hello.extensions, ServerNameExt.class));
	}

	private static TlsEngine seededClient(String remoteName) {
		Function<NamedGroup, KeyPair> ephemeralKeySource =
			group -> TlsKeyExchanges.keyPairFromPrivateBytes(group, Rfc8448.CLIENT_EPHEMERAL_PRIVATE);
		return QuicTls.clientEngine(TlsClientConfig.builder(remoteName, CLIENT_PARAMS)
			.withSecureRandom(new SecureRandom() {
				@Override
				public void nextBytes(byte[] bytes) {
					System.arraycopy(Rfc8448.CLIENT_RANDOM, 0, bytes, 0, bytes.length);
				}
			})
			.withEphemeralKeySource(ephemeralKeySource)
			.build());
	}

	private static ClientHelloMessage emitClientHello(TlsEngine client) throws Exception {
		TlsEngineResult result = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		ByteBuf clientHelloBuf = result.cryptoToSend().get(EncryptionLevel.INITIAL);
		assertNotNull("the client emits its ClientHello on the INITIAL-level CRYPTO stream", clientHelloBuf);
		try {
			TlsHandshakeMessage message = TlsMessages.read(clientHelloBuf);
			assertEquals("the INITIAL-level flight holds exactly the ClientHello", 0, clientHelloBuf.readRemaining());
			return (ClientHelloMessage) message;
		} finally {
			clientHelloBuf.recycle();
		}
	}

	private static <T extends TlsExtension> T find(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) {
				return type.cast(extension);
			}
		}
		return null;
	}
}
