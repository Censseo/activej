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

import io.activej.quic.crypto.Hkdf;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The TLS 1.3 key schedule (RFC 8446 §7.1) proven byte-exact against the RFC 8448 §3
 * "Simple 1-RTT Handshake" published intermediates (SC-002): early secret, derived secret,
 * handshake secret, {@code c_hs_traffic}/{@code s_hs_traffic}, master secret,
 * {@code c_ap_traffic_0}/{@code s_ap_traffic_0}, exporter + resumption master secrets, both
 * Finished keys and both {@code verify_data} values. The trace negotiates
 * {@code TLS_AES_128_GCM_SHA256}, so every published value is a SHA-256 derivation.
 */
public class TlsKeyScheduleTest {
	private static final TlsCipherSuite TRACE_SUITE = TlsCipherSuite.TLS_AES_128_GCM_SHA256;

	@Test
	public void rfc8448EarlySecretAndDerivedSecret() {
		// zero-PSK early secret: Extract(salt = 0, IKM = zeros(HashLen))
		TlsKeySchedule schedule = TlsKeySchedule.start(TRACE_SUITE);
		assertArrayEquals(Rfc8448.EARLY_SECRET, schedule.earlySecret());
		assertArrayEquals(Rfc8448.DERIVED_SECRET, schedule.derivedSecret());
	}

	@Test
	public void rfc8448HandshakeSecretAndHandshakeTrafficSecrets() {
		TlsKeySchedule schedule = handshakeStageSchedule();
		assertArrayEquals(Rfc8448.HANDSHAKE_SECRET, schedule.handshakeSecret());
		assertArrayEquals(Rfc8448.CLIENT_HANDSHAKE_TRAFFIC_SECRET,
			schedule.clientHandshakeTrafficSecret(Rfc8448.HANDSHAKE_TRANSCRIPT_HASH));
		assertArrayEquals(Rfc8448.SERVER_HANDSHAKE_TRAFFIC_SECRET,
			schedule.serverHandshakeTrafficSecret(Rfc8448.HANDSHAKE_TRANSCRIPT_HASH));
	}

	@Test
	public void rfc8448MasterSecretAndApplicationTrafficSecrets() {
		TlsKeySchedule schedule = masterStageSchedule();
		assertArrayEquals(Rfc8448.MASTER_SECRET, schedule.masterSecret());
		assertArrayEquals(Rfc8448.CLIENT_APPLICATION_TRAFFIC_SECRET_0,
			schedule.clientApplicationTrafficSecret0(Rfc8448.SERVER_FINISHED_TRANSCRIPT_HASH));
		assertArrayEquals(Rfc8448.SERVER_APPLICATION_TRAFFIC_SECRET_0,
			schedule.serverApplicationTrafficSecret0(Rfc8448.SERVER_FINISHED_TRANSCRIPT_HASH));
	}

	@Test
	public void rfc8448ExporterAndResumptionMasterSecrets() {
		TlsKeySchedule schedule = masterStageSchedule();
		assertArrayEquals(Rfc8448.EXPORTER_MASTER_SECRET,
			schedule.exporterMasterSecret(Rfc8448.SERVER_FINISHED_TRANSCRIPT_HASH));
		assertArrayEquals(Rfc8448.RESUMPTION_MASTER_SECRET,
			schedule.resumptionMasterSecret(Rfc8448.CLIENT_FINISHED_TRANSCRIPT_HASH));
	}

	@Test
	public void rfc8448FinishedKeysAndVerifyData() throws Exception {
		TlsKeySchedule schedule = masterStageSchedule();

		// server Finished: the base key is s_hs_traffic; the transcript covers CH..CertificateVerify
		// (RFC 8446 §4.4.4 — the sender's own Finished is NOT part of its verify_data transcript)
		byte[] serverFinishedKey = schedule.finishedKey(Rfc8448.SERVER_HANDSHAKE_TRAFFIC_SECRET);
		assertArrayEquals(Rfc8448.SERVER_FINISHED_KEY, serverFinishedKey);
		byte[] serverFinishedTranscript = sha256(
			Rfc8448.CLIENT_HELLO, Rfc8448.SERVER_HELLO, Rfc8448.ENCRYPTED_EXTENSIONS,
			Rfc8448.CERTIFICATE, Rfc8448.CERTIFICATE_VERIFY);
		assertArrayEquals(Rfc8448.SERVER_VERIFY_DATA, schedule.verifyData(serverFinishedKey, serverFinishedTranscript));

		// client Finished: base key c_hs_traffic; transcript over CH..server Finished
		byte[] clientFinishedKey = schedule.finishedKey(Rfc8448.CLIENT_HANDSHAKE_TRAFFIC_SECRET);
		assertArrayEquals(Rfc8448.CLIENT_FINISHED_KEY, clientFinishedKey);
		assertArrayEquals(Rfc8448.CLIENT_VERIFY_DATA,
			schedule.verifyData(clientFinishedKey, Rfc8448.SERVER_FINISHED_TRANSCRIPT_HASH));

		// the published Finished message payloads ARE the verify_data values (pins the trace constants)
		assertArrayEquals(Rfc8448.SERVER_VERIFY_DATA, messagePayload(Rfc8448.SERVER_FINISHED));
		assertArrayEquals(Rfc8448.CLIENT_VERIFY_DATA, messagePayload(Rfc8448.CLIENT_FINISHED));
	}

	@Test
	public void nextApplicationTrafficSecretMatchesRfc8446Section72() {
		// RFC 8446 §7.2: application_traffic_secret_N+1 = HKDF-Expand-Label(secret_N, "traffic upd", "", HashLen)
		TlsKeySchedule schedule = masterStageSchedule();
		byte[] expected = Hkdf.expandLabel("HmacSHA256", Rfc8448.CLIENT_APPLICATION_TRAFFIC_SECRET_0,
			"traffic upd", new byte[0], 32);
		assertArrayEquals(expected, schedule.nextApplicationTrafficSecret(Rfc8448.CLIENT_APPLICATION_TRAFFIC_SECRET_0));

		byte[] expectedServer = Hkdf.expandLabel("HmacSHA256", Rfc8448.SERVER_APPLICATION_TRAFFIC_SECRET_0,
			"traffic upd", new byte[0], 32);
		assertArrayEquals(expectedServer, schedule.nextApplicationTrafficSecret(Rfc8448.SERVER_APPLICATION_TRAFFIC_SECRET_0));
	}

	@Test
	public void sha384SuiteDerivesHashLengthSecretsConsistently() throws Exception {
		// TLS_AES_256_GCM_SHA384 has no published RFC 8448 §3 values, but the whole chain must
		// run on the SHA-384 HKDF and produce HashLen = 48 secrets; verify_data is pinned against
		// an independently computed HmacSHA384 over the transcript hash.
		TlsKeySchedule schedule = TlsKeySchedule.start(TlsCipherSuite.TLS_AES_256_GCM_SHA384);
		assertEquals(48, schedule.earlySecret().length);
		assertEquals(48, schedule.derivedSecret().length);

		byte[] sharedSecret = new byte[32];
		for (int i = 0; i < sharedSecret.length; i++) sharedSecret[i] = (byte) (i + 1);
		schedule.mixEcdhe(sharedSecret);
		assertEquals(48, schedule.handshakeSecret().length);

		byte[] chShTranscript = sha384(Rfc8448.CLIENT_HELLO, Rfc8448.SERVER_HELLO);
		byte[] sHsTraffic = schedule.serverHandshakeTrafficSecret(chShTranscript);
		assertEquals(48, sHsTraffic.length);

		schedule.deriveMasterSecret();
		assertEquals(48, schedule.masterSecret().length);

		byte[] serverFinishedKey = schedule.finishedKey(sHsTraffic);
		assertEquals(48, serverFinishedKey.length);

		Mac independentMac = Mac.getInstance("HmacSHA384");
		independentMac.init(new SecretKeySpec(serverFinishedKey, "HmacSHA384"));
		byte[] expectedVerifyData = independentMac.doFinal(chShTranscript);
		assertArrayEquals(expectedVerifyData, schedule.verifyData(serverFinishedKey, chShTranscript));
	}

	@Test
	public void handshakeSecretIsUnavailableBeforeEcdheIsMixed() {
		TlsKeySchedule schedule = TlsKeySchedule.start(TRACE_SUITE);
		try {
			schedule.handshakeSecret();
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected: the handshake secret does not exist before the ECDHE shared secret is mixed in
		}
	}

	@Test
	public void masterSecretDerivationIsRejectedBeforeHandshakeStage() {
		TlsKeySchedule schedule = TlsKeySchedule.start(TRACE_SUITE);
		try {
			schedule.deriveMasterSecret();
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void masterSecretIsUnavailableBeforeDerived() {
		TlsKeySchedule schedule = handshakeStageSchedule();
		try {
			schedule.masterSecret();
			fail("expected IllegalStateException");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	private static TlsKeySchedule handshakeStageSchedule() {
		TlsKeySchedule schedule = TlsKeySchedule.start(TRACE_SUITE);
		schedule.mixEcdhe(Rfc8448.ECDHE_SHARED_SECRET);
		return schedule;
	}

	private static TlsKeySchedule masterStageSchedule() {
		TlsKeySchedule schedule = handshakeStageSchedule();
		schedule.deriveMasterSecret();
		return schedule;
	}

	private static byte[] sha256(byte[]... chunks) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for (byte[] chunk : chunks) digest.update(chunk);
		return digest.digest();
	}

	private static byte[] sha384(byte[]... chunks) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-384");
		for (byte[] chunk : chunks) digest.update(chunk);
		return digest.digest();
	}

	private static byte[] messagePayload(byte[] handshakeMessage) {
		byte[] payload = new byte[handshakeMessage.length - 4];
		System.arraycopy(handshakeMessage, 4, payload, 0, payload.length);
		return payload;
	}
}
