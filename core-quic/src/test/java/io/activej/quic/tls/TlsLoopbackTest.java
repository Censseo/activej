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
import io.activej.quic.crypto.QuicKeys;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.CLIENT_PARAMS;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * SC-003: a full client↔server loopback handshake in-process, per the quickstart sketch, for
 * every fixture key type (RSA, ECDSA P-256, Ed25519). Both engines must complete, negotiate
 * ALPN {@code h3}, surface each other's transport parameters, and install byte-identical
 * {@link QuicKeys} material for the Handshake and 1-RTT levels in both directions.
 * <p>
 * The client really validates the server chain: the fixtures are self-signed dev certificates,
 * so the test uses an explicit trust manager that trusts exactly the fixture leaf — never
 * {@code insecureTrustAll()} (which TlsClientEngineTest exercises separately).
 */
public class TlsLoopbackTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void loopbackRsa() throws Exception {
		loopback("rsa");
	}

	@Test
	public void loopbackEcdsa() throws Exception {
		loopback("ecdsa");
	}

	@Test
	public void loopbackEd25519() throws Exception {
		loopback("ed25519");
	}

	private static void loopback(String keyType) throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(
			fixture(keyType + "-cert.pem"), fixture(keyType + "-key.pem"));
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS).build());
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("localhost", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.build());

		TlsEngineResult clientHelloResult = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		assertFalse(clientHelloResult.handshakeComplete());

		TlsEngineResult serverFlightResult = server.consume(
			EncryptionLevel.INITIAL, clientHelloResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		assertFalse(serverFlightResult.handshakeComplete());

		TlsEngineResult clientHandshakeKeysResult = client.consume(
			EncryptionLevel.INITIAL, serverFlightResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		assertFalse(clientHandshakeKeysResult.handshakeComplete());
		assertTrue(clientHandshakeKeysResult.cryptoToSend().isEmpty());

		TlsEngineResult clientCompletion = client.consume(
			EncryptionLevel.HANDSHAKE, serverFlightResult.cryptoToSend().get(EncryptionLevel.HANDSHAKE));
		assertTrue(clientCompletion.handshakeComplete());
		assertEquals("h3", clientCompletion.negotiatedAlpn());
		assertEquals(SERVER_PARAMS, clientCompletion.peerTransportParameters());

		TlsEngineResult serverCompletion = server.consume(
			EncryptionLevel.HANDSHAKE, clientCompletion.cryptoToSend().get(EncryptionLevel.HANDSHAKE));
		assertTrue(serverCompletion.handshakeComplete());
		assertEquals("h3", serverCompletion.negotiatedAlpn());
		assertEquals(CLIENT_PARAMS, serverCompletion.peerTransportParameters());
		assertTrue(serverCompletion.cryptoToSend().isEmpty());

		// Handshake-level keys: installed by the server with its flight, by the client on the ServerHello
		assertEquals(1, serverFlightResult.keysToInstall().size());
		assertEquals(1, clientHandshakeKeysResult.keysToInstall().size());
		assertSameMaterial(serverFlightResult.keysToInstall().get(0), clientHandshakeKeysResult.keysToInstall().get(0));

		// 1-RTT keys: installed by the client with its Finished, by the server on the client's Finished
		assertEquals(1, clientCompletion.keysToInstall().size());
		assertEquals(1, serverCompletion.keysToInstall().size());
		assertSameMaterial(clientCompletion.keysToInstall().get(0), serverCompletion.keysToInstall().get(0));
	}

	@Test
	public void loopbackWithInsecureTrustAll() throws Exception {
		TlsServerIdentity identity = TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS).build());
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("localhost", CLIENT_PARAMS)
			.insecureTrustAll()
			.build());

		TlsEngineResult clientHelloResult = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		TlsEngineResult serverFlightResult = server.consume(
			EncryptionLevel.INITIAL, clientHelloResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		client.consume(EncryptionLevel.INITIAL, serverFlightResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		TlsEngineResult clientCompletion = client.consume(
			EncryptionLevel.HANDSHAKE, serverFlightResult.cryptoToSend().get(EncryptionLevel.HANDSHAKE));
		TlsEngineResult serverCompletion = server.consume(
			EncryptionLevel.HANDSHAKE, clientCompletion.cryptoToSend().get(EncryptionLevel.HANDSHAKE));

		assertTrue(clientCompletion.handshakeComplete());
		assertTrue(serverCompletion.handshakeComplete());
		assertSameMaterial(clientCompletion.keysToInstall().get(0), serverCompletion.keysToInstall().get(0));
	}

	private static void assertSameMaterial(KeyInstallation first, KeyInstallation second) {
		assertEquals(first.level(), second.level());
		assertSameKeys(first.keys().clientKeys(), second.keys().clientKeys());
		assertSameKeys(first.keys().serverKeys(), second.keys().serverKeys());
	}

	private static void assertSameKeys(QuicKeys first, QuicKeys second) {
		assertArrayEquals(first.aeadKeyBytes(), second.aeadKeyBytes());
		assertArrayEquals(first.iv(), second.iv());
		assertArrayEquals(first.headerProtectionKey(), second.headerProtectionKey());
	}
}
