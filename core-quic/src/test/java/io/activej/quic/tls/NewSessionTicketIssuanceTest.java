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

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.activej.quic.tls.ScriptedResumptionClient.*;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * T058 — {@code NewSessionTicket} issuance conformance (spec FR-041, FR-042; RFC 8446 §4.6.1,
 * RFC 9001 §4.6.1).
 * <p>
 * Every ticket the server issues must carry an {@code early_data} extension whose
 * {@code max_early_data_size} is exactly {@code 0xffffffff} — the only value RFC 9001 §4.6.1 permits
 * in QUIC — and must be openable by the very keys that sealed it, carrying the origin, the suite and
 * the RFC 9000 §7.4.1-filtered transport parameters. The phase-1 net is the first test: with no
 * sealing keys configured the completion flight is empty, byte for byte as before.
 * <p>
 * The <b>client</b> half of FR-043 (a {@code max_early_data_size} other than {@code 0xffffffff}
 * closing with {@code PROTOCOL_VIOLATION}) belongs to the client engine and is not covered here; the
 * zero-length {@code ticket_nonce} regression net is, because the issuing side is where a
 * "harden the vector bound" mistake would be re-introduced.
 */
public class NewSessionTicketIssuanceTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long T0 = 1_700_000_000_000L;
	private static final long HOUR = 3_600_000L;
	private static final long SEVEN_DAYS_SECONDS = 604_800L;

	@Test
	public void noTicketKeysIssuesNothingAndLeavesTheCompletionFlightEmpty() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS)
			.withCurrentTimeMillis(() -> T0)
			.build());

		TlsEngineResult completion = completeHandshake(engine, identity, new ScriptedResumptionClient());
		try {
			assertTrue("phase-1 behaviour: nothing is emitted once the handshake completes",
				completion.cryptoToSend().isEmpty());
			assertTrue(completion.issuedTickets().isEmpty());
		} finally {
			recycleOutput(completion);
		}
	}

	@Test
	public void everyIssuedTicketCarriesEarlyDataWithExactly0xffffffff() throws Exception {
		for (NewSessionTicketMessage ticket : issue(2, 6 * HOUR, HOUR, null)) {
			EarlyDataExt earlyData = find(ticket.extensions, EarlyDataExt.class);
			assertNotNull("RFC 9001 §4.6.1 requires early_data on every QUIC NewSessionTicket", earlyData);
			assertTrue(earlyData.hasMaxEarlyDataSize());
			assertEquals(0xFFFFFFFFL, earlyData.maxEarlyDataSize);
			assertEquals(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE, earlyData.maxEarlyDataSize);
		}
	}

	@Test
	public void theConfiguredNumberOfTicketsIsIssuedOnTheOneRttCryptoStream() throws Exception {
		for (int count : new int[] {1, 2, 5}) {
			assertEquals(count, issue(count, 6 * HOUR, HOUR, null).size());
		}
	}

	@Test
	public void sessionTicketsPerHandshakeZeroIssuesNone() throws Exception {
		assertEquals(List.of(), issue(0, 6 * HOUR, HOUR, null));
	}

	@Test
	public void eachTicketCarriesADistinctNonceAndADistinctTicketAgeAdd() throws Exception {
		List<NewSessionTicketMessage> tickets = issue(5, 6 * HOUR, HOUR, null);
		Set<String> nonces = new HashSet<>();
		Set<Long> ageAdds = new HashSet<>();
		for (NewSessionTicketMessage ticket : tickets) {
			assertTrue("ticket_nonce must be distinct per ticket (RFC 8446 §4.6.1)",
				nonces.add(java.util.Arrays.toString(ticket.ticketNonce())));
			assertTrue("ticket_age_add must be freshly random per ticket (RFC 8446 §4.6.1)",
				ageAdds.add(ticket.ticketAgeAdd));
			assertEquals(0, ticket.ticketAgeAdd & ~0xFFFFFFFFL);
		}
		assertEquals(5, nonces.size());
	}

	@Test
	public void theAdvertisedLifetimeIsInSecondsAndEqualsTheSealedLifetime() throws Exception {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
		List<NewSessionTicketMessage> tickets = issue(2, keys, null);
		for (NewSessionTicketMessage message : tickets) {
			assertEquals(HOUR / 1000, message.ticketLifetime);
			QuicSessionTicket opened = keys.open(message.ticket());
			assertNotNull("the server must be able to open what it just sealed", opened);
			assertEquals("the sealed and advertised lifetimes must be the same number",
				message.ticketLifetime * 1000, opened.lifetimeMillis());
			assertEquals(T0, opened.issuedAtMillis());
			assertEquals(message.ticketAgeAdd, opened.ticketAgeAdd());
		}
	}

	@Test
	public void theAdvertisedLifetimeNeverExceedsTheSevenDayCap() throws Exception {
		long eightDays = 8 * 24 * HOUR;
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), eightDays, eightDays, T0);
		for (NewSessionTicketMessage message : issue(2, keys, null)) {
			assertEquals("RFC 8446 §4.6.1 caps ticket_lifetime at seven days", SEVEN_DAYS_SECONDS, message.ticketLifetime);
			QuicSessionTicket opened = keys.open(message.ticket());
			assertNotNull(opened);
			assertEquals(SEVEN_DAYS_SECONDS * 1000, opened.lifetimeMillis());
		}
	}

	@Test
	public void theSealedTicketCarriesTheOriginSuiteAndRememberableTransportParameters() throws Exception {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
		QuicSessionTicket opened = keys.open(issue(1, keys, null).get(0).ticket());
		assertNotNull(opened);

		assertEquals(SERVER_NAME, opened.serverName());
		assertEquals(ALPN_H3, opened.alpn());
		assertEquals(TlsCipherSuite.TLS_AES_128_GCM_SHA256, opened.cipherSuite());
		assertTrue(opened.isFor(SERVER_NAME, ALPN_H3));
		assertEquals(QuicSessionTicket.rememberableParameters(SERVER_PARAMS), opened.transportParameters());
		// RFC 9000 §7.4.1: the excluded parameters can never travel with a ticket
		assertNull(opened.transportParameters().originalDestinationConnectionId());
		assertNull(opened.transportParameters().initialSourceConnectionId());
		assertNull(opened.transportParameters().statelessResetToken());
	}

	@Test
	public void theSealedResumptionSecretIsTheRfc8446ResumptionPsk() throws Exception {
		QuicTicketKeys keys = QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, HOUR, T0);
		ScriptedResumptionClient client = new ScriptedResumptionClient();
		List<NewSessionTicketMessage> messages = issue(2, keys, client);

		byte[] resumptionMaster = client.resumptionMasterSecret();
		for (NewSessionTicketMessage message : messages) {
			QuicSessionTicket opened = keys.open(message.ticket());
			assertNotNull(opened);
			assertArrayEquals(
				"PSK = HKDF-Expand-Label(resumption_master_secret, \"resumption\", ticket_nonce, HashLen)",
				TlsKeySchedule.resumptionPsk(opened.cipherSuite(), resumptionMaster, message.ticketNonce()),
				opened.resumptionSecret());
		}
	}

	@Test
	public void aZeroLengthTicketNonceStillParses() throws Exception {
		// the landed-master regression net: `opaque ticket_nonce<0..255>` really is <0..255>
		NewSessionTicketMessage message = new NewSessionTicketMessage(3600, 0xDEADBEEFL,
			new byte[0], new byte[] {1, 2, 3, 4},
			List.of(EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE)));

		TlsHandshakeMessage parsed = TlsMessages.read(ByteBuf.wrapForReading(serialize(message)));
		assertEquals(message, parsed);
		assertEquals(0, ((NewSessionTicketMessage) parsed).ticketNonce().length);
	}

	// ---- harness ----

	private static TlsServerIdentity rsaIdentity() throws Exception {
		return TlsServerIdentity.fromPem(fixture("rsa-cert.pem"), fixture("rsa-key.pem"));
	}

	private static List<NewSessionTicketMessage> issue(int ticketsPerHandshake, long rotationMillis,
			long lifetimeMillis, ScriptedResumptionClient client) throws Exception {
		return issue(ticketsPerHandshake,
			QuicTicketKeys.create(new SecureRandom(), rotationMillis, lifetimeMillis, T0), client);
	}

	private static List<NewSessionTicketMessage> issue(int ticketsPerHandshake, QuicTicketKeys keys,
			ScriptedResumptionClient client) throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		TlsEngine engine = QuicTls.serverEngine(TlsServerConfig.builder(identity, SERVER_PARAMS)
			.withTicketKeys(keys)
			.withSessionTicketsPerHandshake(ticketsPerHandshake)
			.withTicketAgeTolerance(Duration.ofSeconds(10))
			.withCurrentTimeMillis(() -> T0)
			.build());

		TlsEngineResult completion = completeHandshake(engine, identity,
			client == null ? new ScriptedResumptionClient() : client);
		byte[] oneRtt;
		try {
			assertTrue("a server never retains what it seals", completion.issuedTickets().isEmpty());
			ByteBuf buf = completion.cryptoToSend().get(EncryptionLevel.ONE_RTT);
			oneRtt = buf == null ? new byte[0] : readBytes(buf);
			assertNull("a NewSessionTicket flight rides the ONE_RTT CRYPTO stream only",
				completion.cryptoToSend().get(EncryptionLevel.HANDSHAKE));
		} finally {
			recycleOutput(completion);
		}

		List<NewSessionTicketMessage> tickets = new ArrayList<>();
		for (TlsHandshakeMessage message : parseAll(oneRtt)) {
			tickets.add((NewSessionTicketMessage) message);
		}
		return tickets;
	}

	private static TlsEngineResult completeHandshake(TlsEngine engine, TlsServerIdentity identity,
			ScriptedResumptionClient client) throws Exception {
		TlsEngineResult serverFlight = engine.consume(
			EncryptionLevel.INITIAL, wrap(client.clientHello(client.defaultExtensions())));
		try {
			client.acceptServerFlight(serverFlight, identity.leaf());
		} finally {
			recycleOutput(serverFlight);
		}
		TlsEngineResult completion = engine.consume(EncryptionLevel.HANDSHAKE, wrap(client.clientFinished()));
		assertTrue(completion.handshakeComplete());
		return completion;
	}
}
