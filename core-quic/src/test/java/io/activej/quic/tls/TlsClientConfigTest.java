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
import io.activej.quic.connection.testutil.QuicTestPeers;
import io.activej.quic.crypto.QuicKeys;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.CLIENT_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.expectAlert;
import static org.junit.Assert.*;

/**
 * {@link TlsClientConfig.Builder#withTrustedCertificate(X509Certificate)} (feature 008, US2):
 * pinning a single self-signed development leaf, replacing the hand-rolled {@code X509TrustManager}
 * this repository used to copy as {@code trustingLeaf}.
 * <p>
 * The accept/reject cases are driven as a real {@link TlsClientEngine} ↔ {@link TlsServerEngine}
 * loopback (the {@link TlsLoopbackTest} shape — synchronous, reactor-free, no {@code EventloopRule}):
 * the pinned client validates the chain a real server presents and, in the load-bearing
 * {@link #hostnameIsStillVerified()} case, proves the pin is a policy rather than a bypass — unlike
 * {@code insecureTrustAll()}, RFC 6125 endpoint identification stays on.
 * <p>
 * The empty-chain and client-authentication refusals are asserted straight against the trust manager
 * the built config installs: a TLS 1.3 QUIC server never presents an empty certificate list (that is
 * a protocol error the engine rejects before consulting the manager) and never requests client
 * authentication (mTLS is out of scope), so those two decisions are only reachable by direct call.
 * <p>
 * The accept/reject pinning coverage that first lived in
 * {@code QuicTestPeersTest.trustingLeafAcceptsTheDevLeafAndRejectsOthers} relocated here when the
 * {@code trustingLeaf} helper was deleted (FR-019 — the behaviour is preserved, only moved): the
 * accept and the other-leaf rejection are driven through a real handshake, the empty-chain refusal
 * through the installed manager.
 */
public class TlsClientConfigTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Test
	public void trustsExactlyTheConfiguredCertificate() throws Exception {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS).build());
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("localhost", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.build());

		completeHandshake(client, server);
	}

	@Test
	public void rejectsAnyOtherCertificate() throws Exception {
		TlsServerIdentity dev = QuicTestPeers.devIdentity();
		TlsServerIdentity other = QuicTestPeers.devRsaIdentity();
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(dev, SERVER_PARAMS).build());
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("localhost", CLIENT_PARAMS)
			.withTrustedCertificate(other.leaf()) // the server presents the dev leaf, not this one
			.build());

		TlsAlertException e = expectHandshakeFailure(client, server);
		// the pinning manager throws a plain CertificateException — mapped to bad_certificate
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	@Test
	public void rejectsAnEmptyChain() {
		X509TrustManager trustManager = pinnedTrustManager(QuicTestPeers.devIdentity().leaf());
		assertThrows(CertificateException.class,
			() -> trustManager.checkServerTrusted(new X509Certificate[0], "EC"));
	}

	@Test
	public void rejectsAnExpiredPinnedCertificate() {
		X509Certificate leaf = QuicTestPeers.devIdentity().leaf();
		X509TrustManager trustManager = pinnedTrustManager(leaf);
		// no expired-certificate fixture exists in the test resources, so the expiry is simulated
		// by a wrapper whose checkValidity throws — equality still holds because it compares the
		// encoded bytes, which the wrapper delegates
		assertThrows(java.security.cert.CertificateExpiredException.class,
			() -> trustManager.checkServerTrusted(new X509Certificate[] {new ExpiredCertificate(leaf)}, "EC"));
	}

	@Test
	public void refusesClientAuthentication() {
		X509TrustManager trustManager = pinnedTrustManager(QuicTestPeers.devIdentity().leaf());
		CertificateException e = assertThrows(CertificateException.class,
			() -> trustManager.checkClientTrusted(new X509Certificate[] {QuicTestPeers.devIdentity().leaf()}, "EC"));
		assertTrue(e.getMessage(), e.getMessage().contains("Client authentication is not used"));
	}

	@Test
	public void hostnameIsStillVerified() throws Exception {
		TlsServerIdentity identity = QuicTestPeers.devIdentity();
		TlsEngine server = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS).build());
		// the dev leaf covers "localhost" and "example.test" only — this is the case that
		// distinguishes the pin from insecureTrustAll(), which would complete this handshake
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("wrong-host.test", CLIENT_PARAMS)
			.withTrustedCertificate(identity.leaf())
			.build());

		TlsAlertException e = expectHandshakeFailure(client, server);
		assertEquals(TlsAlerts.BAD_CERTIFICATE, e.alertCode());
	}

	// ---- helpers ----

	/** The trust manager a {@code withTrustedCertificate} config installs — the object the engine consults. */
	private static X509TrustManager pinnedTrustManager(X509Certificate leaf) {
		X509TrustManager trustManager = TlsClientConfig.builder("localhost", CLIENT_PARAMS)
			.withTrustedCertificate(leaf)
			.build()
			.trustManager();
		assertNotNull(trustManager);
		return trustManager;
	}

	/** Drives the loopback to completion, asserting both sides complete and install identical 1-RTT keys. */
	private static void completeHandshake(TlsEngine client, TlsEngine server) throws Exception {
		TlsEngineResult clientHelloResult = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		assertFalse(clientHelloResult.handshakeComplete());

		TlsEngineResult serverFlightResult = server.consume(
			EncryptionLevel.INITIAL, clientHelloResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		assertFalse(serverFlightResult.handshakeComplete());

		TlsEngineResult clientHandshakeKeysResult = client.consume(
			EncryptionLevel.INITIAL, serverFlightResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		assertFalse(clientHandshakeKeysResult.handshakeComplete());

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

		// 1-RTT keys: the client installs them with its Finished, the server on the client's Finished —
		// both sides must derive byte-identical material
		assertEquals(1, clientCompletion.keysToInstall().size());
		assertEquals(1, serverCompletion.keysToInstall().size());
		assertSameKeys(clientCompletion.keysToInstall().get(0).keys().clientKeys(),
			serverCompletion.keysToInstall().get(0).keys().clientKeys());
		assertSameKeys(clientCompletion.keysToInstall().get(0).keys().serverKeys(),
			serverCompletion.keysToInstall().get(0).keys().serverKeys());
	}

	/** Drives the loopback up to the Certificate flight and returns the client's rejection alert. */
	private static TlsAlertException expectHandshakeFailure(TlsEngine client, TlsEngine server) throws Exception {
		TlsEngineResult clientHelloResult = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		TlsEngineResult serverFlightResult = server.consume(
			EncryptionLevel.INITIAL, clientHelloResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		client.consume(EncryptionLevel.INITIAL, serverFlightResult.cryptoToSend().get(EncryptionLevel.INITIAL));
		// the Certificate message — and with it chain validation and endpoint identification —
		// arrives in the HANDSHAKE-level flight
		return expectAlert(() ->
			client.consume(EncryptionLevel.HANDSHAKE, serverFlightResult.cryptoToSend().get(EncryptionLevel.HANDSHAKE)));
	}

	private static void assertSameKeys(QuicKeys first, QuicKeys second) {
		assertArrayEquals(first.aeadKeyBytes(), second.aeadKeyBytes());
		assertArrayEquals(first.iv(), second.iv());
		assertArrayEquals(first.headerProtectionKey(), second.headerProtectionKey());
	}

	/** A delegating certificate whose validity window has closed; every other method delegates. */
	private static final class ExpiredCertificate extends X509Certificate {
		private final X509Certificate delegate;

		private ExpiredCertificate(X509Certificate delegate) {
			this.delegate = delegate;
		}

		@Override
		public void checkValidity() throws java.security.cert.CertificateExpiredException {
			throw new java.security.cert.CertificateExpiredException("simulated expiry");
		}

		@Override
		public void checkValidity(java.util.Date date) throws java.security.cert.CertificateExpiredException {
			throw new java.security.cert.CertificateExpiredException("simulated expiry");
		}

		@Override
		public byte[] getEncoded() throws java.security.cert.CertificateEncodingException {return delegate.getEncoded();}

		@Override
		public void verify(java.security.PublicKey key) {throw new UnsupportedOperationException();}

		@Override
		public void verify(java.security.PublicKey key, String sigProvider) {throw new UnsupportedOperationException();}

		@Override
		public String toString() {return delegate.toString();}

		@Override
		public java.security.PublicKey getPublicKey() {return delegate.getPublicKey();}

		@Override
		public int getVersion() {return delegate.getVersion();}

		@Override
		public java.math.BigInteger getSerialNumber() {return delegate.getSerialNumber();}

		@Override
		public java.security.Principal getIssuerDN() {return delegate.getIssuerDN();}

		@Override
		public java.security.Principal getSubjectDN() {return delegate.getSubjectDN();}

		@Override
		public java.util.Date getNotBefore() {return delegate.getNotBefore();}

		@Override
		public java.util.Date getNotAfter() {return delegate.getNotAfter();}

		@Override
		public byte[] getTBSCertificate() throws java.security.cert.CertificateEncodingException {return delegate.getTBSCertificate();}

		@Override
		public byte[] getSignature() {return delegate.getSignature();}

		@Override
		public String getSigAlgName() {return delegate.getSigAlgName();}

		@Override
		public String getSigAlgOID() {return delegate.getSigAlgOID();}

		@Override
		public byte[] getSigAlgParams() {return delegate.getSigAlgParams();}

		@Override
		public boolean[] getIssuerUniqueID() {return delegate.getIssuerUniqueID();}

		@Override
		public boolean[] getSubjectUniqueID() {return delegate.getSubjectUniqueID();}

		@Override
		public boolean[] getKeyUsage() {return delegate.getKeyUsage();}

		@Override
		public int getBasicConstraints() {return delegate.getBasicConstraints();}
	}
}
