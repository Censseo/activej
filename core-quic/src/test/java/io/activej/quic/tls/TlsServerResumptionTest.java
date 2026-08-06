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
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

import static io.activej.quic.tls.ScriptedResumptionClient.*;
import static io.activej.quic.tls.ScriptedResumptionClient.BinderScope.*;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * T059 — server-side PSK resumption (spec FR-045, FR-046, FR-047; RFC 8446 §4.2.11).
 * <p>
 * The whole design of this path is one dividing line, and these tests are organised around it:
 * <b>selection precedes verification.</b> Anything that stops the server from <i>selecting</i> a PSK
 * — an unopenable, expired, stale-aged, wrong-origin or wrong-suite ticket, a client offering only
 * {@code psk_ke}, a server with no sealing keys — falls back to a full handshake in silence, and no
 * branch reports which check refused it. A PSK that <i>was</i> selected and then failed its binder is
 * tampering evidence, and is a fatal {@code decrypt_error} that never degrades to an
 * unauthenticated-but-working session.
 * <p>
 * The ticket under test always comes from the server's own issuance: the test owns the
 * {@link QuicTicketKeys} it configured, so it opens the blob the server sealed and recovers the exact
 * {@link QuicSessionTicket}, PSK included. No client-side resumption code is involved.
 */
public class TlsServerResumptionTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long T0 = 1_700_000_000_000L;
	private static final long HOUR = 3_600_000L;
	private static final long TOLERANCE_MILLIS = 10_000L;

	// ---- loud: a malformed offer, or a selected PSK whose binder does not verify ----

	@Test
	public void corruptedBinderIsDecryptErrorAndNeverASilentFullHandshake() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(client.defaultExtensions(), ticket, 0, TRUNCATED, 0, true);

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(clientHello)));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
		assertEquals(51, e.alertCode());
		// the engine is terminal: the connection layer maps the alert to CONNECTION_CLOSE 0x0133
		assertThrows(IllegalStateException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(clientHello)));
	}

	@Test
	public void preSharedKeyNotLastIsIllegalParameter() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();

		List<TlsExtension> extensions = without(client.defaultExtensions(), ServerNameExt.class);
		extensions.add(PreSharedKeyExt.ofClientOffer(
			List.of(new PreSharedKeyExt.PskIdentity(ticket.identity(), ticket.ticketAgeAdd())),
			List.of(new byte[32])));
		extensions.add(new ServerNameExt(SERVER_NAME));

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(extensions))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void preSharedKeyInTheServerHelloFormIsIllegalParameter() throws Exception {
		QuicTicketKeys keys = newKeys();
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();

		// a 2-byte body parses as selected_identity whichever message carries it, so a ClientHello
		// really can present the wrong form and the engine — not the codec — has to refuse it
		List<TlsExtension> extensions = client.defaultExtensions();
		extensions.add(PreSharedKeyExt.ofSelectedIdentity(0));

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(client.clientHello(extensions))));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void preSharedKeyWithoutPskKeyExchangeModesIsMissingExtension() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		List<TlsExtension> extensions = without(client.defaultExtensions(), PskKeyExchangeModesExt.class);
		byte[] clientHello = client.resumingClientHello(extensions, ticket, 0, TRUNCATED, 0, false);

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(clientHello)));
		assertEquals(TlsAlerts.MISSING_EXTENSION, e.alertCode());
	}

	// ---- quiet: every reason not to select, each a full handshake ----

	@Test
	public void unopenableTicketFallsBackToAFullHandshake() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket issued = issueTicket(keys);
		byte[] tampered = issued.identity();
		tampered[tampered.length - 1] ^= 0x01;

		assertFullHandshake(resume(keys, () -> T0, rebuild(issued, tampered), 0));
	}

	@Test
	public void ticketSealedUnderARotatedOutKeyFallsBack() throws Exception {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), HOUR, HOUR, T0);
		QuicSessionTicket ticket = issueTicket(keys);
		// two rotations retire the key the ticket was sealed under, while the engine's clock —
		// injected separately — keeps the ticket itself well within its lifetime
		assertTrue(keys.rotateIfDue(T0 + HOUR));
		assertTrue(keys.rotateIfDue(T0 + 2 * HOUR));
		assertEquals(QuicTicketKeys.RETAINED_KEYS, keys.retainedKeyCount());

		assertFullHandshake(resume(keys, () -> T0, ticket, 0));
	}

	@Test
	public void expiredTicketFallsBack() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		long expiredAt = T0 + ticket.lifetimeMillis() + 1;
		assertTrue(ticket.isExpiredAt(expiredAt));

		assertFullHandshake(resume(keys, () -> expiredAt, ticket, ticket.lifetimeMillis() + 1));
	}

	@Test
	public void obfuscatedAgeOutsideToleranceFallsBack() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		// the ticket is one second old, but the client claims a minute
		assertFullHandshake(resume(keys, () -> T0 + 1_000, ticket, 60_000));
		// and the same attempt inside the tolerance window is accepted
		assertResumedHandshake(resume(keys, () -> T0 + 1_000, ticket, 1_000 + TOLERANCE_MILLIS - 1), 0);
	}

	/**
	 * T155: the tolerance is not decoration — a widened one accepts exactly what the 10 s default
	 * refuses, on the same ticket, the same clock and the same reported age. This is what makes
	 * {@code -DQuicConnection.ticketAgeTolerance} worth wiring through, and what a test pinned to the
	 * default value could never show.
	 */
	@Test
	public void aWidenedAgeToleranceAcceptsAnAgeTheDefaultRefuses() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		// the ticket is one second old and the client claims 21 s: 20 s of skew, outside 10 s, inside 30 s
		long reportedAge = 21_000;
		long now = T0 + 1_000;

		assertFullHandshake(resume(keys, () -> now, ticket, reportedAge, TOLERANCE_MILLIS));
		assertResumedHandshake(resume(keys, () -> now, ticket, reportedAge, 30_000L), 0);
	}

	@Test
	public void ticketForADifferentServerNameFallsBack() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(
			client.defaultExtensions("other.example.com"), ticket, 0, TRUNCATED, 0, false);

		assertFullHandshake(consumeFlight(engine, clientHello));
	}

	@Test
	public void ticketForADifferentCipherSuiteFallsBack() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		assertEquals(TlsCipherSuite.TLS_AES_128_GCM_SHA256, ticket.cipherSuite());

		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		client.offeredCipherSuites = new int[] {TlsCipherSuite.TLS_AES_256_GCM_SHA384.code()};
		byte[] clientHello = client.resumingClientHello(client.defaultExtensions(), ticket, 0, TRUNCATED, 0, false);

		assertFullHandshake(consumeFlight(engine, clientHello));
	}

	@Test
	public void pskKeOnlyIsNeverAccepted() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		List<TlsExtension> extensions = without(client.defaultExtensions(), PskKeyExchangeModesExt.class);
		extensions.add(new PskKeyExchangeModesExt(0)); // psk_ke — resumption without (EC)DHE
		byte[] clientHello = client.resumingClientHello(extensions, ticket, 0, TRUNCATED, 0, false);

		assertFullHandshake(consumeFlight(engine, clientHello));
	}

	@Test
	public void aServerWithoutTicketKeysFallsBack() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS).build());
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(client.defaultExtensions(), ticket, 0, TRUNCATED, 0, false);

		assertFullHandshake(consumeFlight(engine, clientHello));
	}

	// ---- the truncation point (RFC 8446 §4.2.11.2) ----

	@Test
	public void binderOverTheTruncatedClientHelloIsAccepted() throws Exception {
		QuicTicketKeys keys = newKeys();
		assertResumedHandshake(resume(keys, () -> T0, issueTicket(keys), 0), 0);
	}

	@Test
	public void binderOverTheFullClientHelloIsDecryptError() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(client.defaultExtensions(), ticket, 0, FULL_MESSAGE, 0, false);

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(clientHello)));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
	}

	@Test
	public void binderOverAPrefixEndingBeforeTheIdentitiesIsDecryptError() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		byte[] clientHello = client.resumingClientHello(
			client.defaultExtensions(), ticket, 0, BEFORE_IDENTITIES, 0, false);

		TlsAlertException e = assertThrows(TlsAlertException.class,
			() -> engine.consume(EncryptionLevel.INITIAL, wrap(clientHello)));
		assertEquals(TlsAlerts.DECRYPT_ERROR, e.alertCode());
	}

	// ---- the accepted path ----

	@Test
	public void acceptedPskEchoesSelectedIdentityAndOmitsCertificateAndCertificateVerify() throws Exception {
		QuicTicketKeys keys = newKeys();
		Flight flight = resume(keys, () -> T0, issueTicket(keys), 0);

		ServerHelloMessage serverHello = (ServerHelloMessage) parseAll(flight.initial()).get(0);
		PreSharedKeyExt selected = find(serverHello.extensions, PreSharedKeyExt.class);
		assertNotNull("an accepted PSK must be echoed in the ServerHello (RFC 8446 §4.2.11)", selected);
		assertEquals(0, selected.selectedIdentity);
		assertNull(selected.identities);
		assertNull(selected.binders);

		List<TlsHandshakeMessage> messages = parseAll(flight.handshake());
		assertEquals("a PSK-authenticated flight is EE + Finished only (RFC 8446 §4.4.2)", 2, messages.size());
		assertTrue(messages.get(0) instanceof EncryptedExtensionsMessage);
		assertTrue(messages.get(1) instanceof FinishedMessage);
	}

	@Test
	public void aResumedHandshakeCompletesAndIssuesFreshTickets() throws Exception {
		QuicTicketKeys keys = newKeys();
		QuicSessionTicket ticket = issueTicket(keys);
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();

		TlsEngineResult serverFlight = engine.consume(EncryptionLevel.INITIAL,
			wrap(client.resumingClientHello(client.defaultExtensions(), ticket, 0, TRUNCATED, 0, false)));
		try {
			client.acceptServerFlight(serverFlight, null);
			assertTrue(client.serverAcceptedPsk);
		} finally {
			recycleOutput(serverFlight);
		}

		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished()));
		byte[] oneRtt;
		try {
			assertTrue(completion.handshakeComplete());
			assertEquals(ALPN_H3, completion.negotiatedAlpn());
			assertEquals(CLIENT_PARAMS, completion.peerTransportParameters());
			assertEquals(1, completion.keysToInstall().size());
			assertEquals(EncryptionLevel.ONE_RTT, completion.keysToInstall().get(0).level());
			ByteBuf buf = completion.cryptoToSend().get(EncryptionLevel.ONE_RTT);
			assertNotNull("a resumed handshake issues fresh tickets too", buf);
			oneRtt = readBytes(buf);
		} finally {
			recycleOutput(completion);
		}

		List<TlsHandshakeMessage> tickets = parseAll(oneRtt);
		assertEquals(2, tickets.size());
		for (TlsHandshakeMessage message : tickets) {
			QuicSessionTicket fresh = keys.open(((NewSessionTicketMessage) message).ticket());
			assertNotNull(fresh);
			assertTrue(fresh.isFor(SERVER_NAME, ALPN_H3));
			assertFalse("a ticket issued on a resumed connection must not repeat the one it resumed",
				java.util.Arrays.equals(fresh.resumptionSecret(), ticket.resumptionSecret()));
		}
	}

	// ---- constant time (spec FR-045, SI-5) ----

	@Test
	public void binderVerificationUsesTheJdkConstantTimeComparison() throws IOException {
		// the comparison is a separate named method precisely so this is checkable rather than
		// aspirational; the server engine must reach it and never roll its own
		Path helper = Path.of("src/main/java/io/activej/quic/tls/TlsPskBinders.java");
		assertTrue("the source tree moved: " + helper.toAbsolutePath(), Files.isRegularFile(helper));
		String text = Files.readString(helper, StandardCharsets.UTF_8);

		int start = text.indexOf("static boolean verifyBinder(");
		assertTrue("verifyBinder must stay a separate named method so this check is possible", start >= 0);
		int end = text.indexOf("\n\t}", start);
		assertTrue(end > start);
		String body = text.substring(start, end);

		assertTrue("the PSK binder must be compared with MessageDigest.isEqual (SI-5): " + body,
			body.contains("MessageDigest.isEqual("));
		assertFalse("Arrays.equals is not constant time: " + body, body.contains("Arrays.equals("));

		String engine = Files.readString(
			Path.of("src/main/java/io/activej/quic/tls/TlsServerEngine.java"), StandardCharsets.UTF_8);
		assertTrue("the server engine must verify through the shared definition",
			engine.contains("TlsPskBinders.verifyBinder("));
		assertTrue("and must truncate through the shared definition (RFC 8446 §4.2.11.2)",
			engine.contains("TlsPskBinders.truncatedClientHelloHash("));
	}

	// ---- harness ----

	private record Flight(byte[] initial, byte[] handshake) {
	}

	private static TlsServerIdentity rsaIdentity() throws Exception {
		return TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
	}

	private static QuicTicketKeys newKeys() {
		return QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
	}

	private static TlsEngine resumingServer(QuicTicketKeys keys, LongSupplier clock) throws Exception {
		return resumingServer(keys, clock, TOLERANCE_MILLIS);
	}

	private static TlsEngine resumingServer(QuicTicketKeys keys, LongSupplier clock, long toleranceMillis)
		throws Exception {
		return QuicTls.serverEngine(TlsServerConfig.builder(rsaIdentity(), SERVER_PARAMS)
			.withTicketKeys(keys)
			.withSessionTicketsPerHandshake(2)
			.withTicketAgeTolerance(Duration.ofMillis(toleranceMillis))
			.withCurrentTimeMillis(clock)
			.build());
	}

	/** Runs a full handshake against a ticket-issuing server and opens the first ticket it sealed. */
	private static QuicSessionTicket issueTicket(QuicTicketKeys keys) throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = resumingServer(keys, () -> T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();

		TlsEngineResult serverFlight = engine.consume(
			EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
		try {
			client.acceptServerFlight(serverFlight, identity.leaf());
		} finally {
			recycleOutput(serverFlight);
		}

		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished()));
		byte[] oneRtt;
		try {
			ByteBuf buf = completion.cryptoToSend().get(EncryptionLevel.ONE_RTT);
			assertNotNull(buf);
			oneRtt = readBytes(buf);
		} finally {
			recycleOutput(completion);
		}

		NewSessionTicketMessage message = (NewSessionTicketMessage) parseAll(oneRtt).get(0);
		QuicSessionTicket ticket = keys.open(message.ticket());
		assertNotNull(ticket);
		return ticket;
	}

	/** The same plaintext contents under a different sealed blob — the "unopenable" fixture. */
	private static QuicSessionTicket rebuild(QuicSessionTicket ticket, byte[] identity) {
		return QuicSessionTicket
			.builder(ticket.serverName(), ticket.alpn(), ticket.cipherSuite(), ticket.resumptionSecret())
			.withIdentity(identity)
			.withIssuedAt(ticket.issuedAtMillis())
			.withLifetime(ticket.lifetimeMillis())
			.withTicketAgeAdd(ticket.ticketAgeAdd())
			.withTransportParameters(ticket.transportParameters())
			.build();
	}

	private static Flight resume(QuicTicketKeys keys, LongSupplier clock, QuicSessionTicket ticket,
			long reportedAgeMillis) throws Exception {
		return resume(keys, clock, ticket, reportedAgeMillis, TOLERANCE_MILLIS);
	}

	private static Flight resume(QuicTicketKeys keys, LongSupplier clock, QuicSessionTicket ticket,
			long reportedAgeMillis, long toleranceMillis) throws Exception {
		TlsEngine engine = resumingServer(keys, clock, toleranceMillis);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		return consumeFlight(engine, client.resumingClientHello(
			client.defaultExtensions(), ticket, reportedAgeMillis, TRUNCATED, 0, false));
	}

	private static Flight consumeFlight(TlsEngine engine, byte[] clientHello) throws Exception {
		TlsEngineResult result = engine.consume(EncryptionLevel.INITIAL, wrap(clientHello));
		try {
			ByteBuf initial = result.cryptoToSend().get(EncryptionLevel.INITIAL);
			ByteBuf handshake = result.cryptoToSend().get(EncryptionLevel.HANDSHAKE);
			assertNotNull(initial);
			assertNotNull(handshake);
			return new Flight(readBytes(initial), readBytes(handshake));
		} finally {
			recycleOutput(result);
		}
	}

	private static void assertFullHandshake(Flight flight) throws Exception {
		ServerHelloMessage serverHello = (ServerHelloMessage) parseAll(flight.initial()).get(0);
		assertNull("a full handshake never echoes pre_shared_key",
			find(serverHello.extensions, PreSharedKeyExt.class));

		List<TlsHandshakeMessage> messages = parseAll(flight.handshake());
		assertEquals("a full handshake is EE + Certificate + CertificateVerify + Finished", 4, messages.size());
		assertTrue(messages.get(0) instanceof EncryptedExtensionsMessage);
		assertTrue("a fallback must authenticate with a certificate, never silently skip it",
			messages.get(1) instanceof CertificateMessage);
		assertTrue(messages.get(2) instanceof CertificateVerifyMessage);
		assertTrue(messages.get(3) instanceof FinishedMessage);
	}

	private static void assertResumedHandshake(Flight flight, int selectedIdentity) throws Exception {
		ServerHelloMessage serverHello = (ServerHelloMessage) parseAll(flight.initial()).get(0);
		PreSharedKeyExt selected = find(serverHello.extensions, PreSharedKeyExt.class);
		assertNotNull(selected);
		assertEquals(selectedIdentity, selected.selectedIdentity);
		assertEquals(2, parseAll(flight.handshake()).size());
	}
}
