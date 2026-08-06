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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

import static io.activej.quic.tls.TlsClientEngineTest.*;
import static org.junit.Assert.*;

/**
 * The client half of US3 — resumption and 0-RTT (spec FR-043 … FR-049), driven against
 * {@link ScriptedTlsServer}, which re-derives the PSK binder and the early traffic secret
 * <b>independently</b> so a shared mistake in the truncation rule cannot cancel itself out.
 * <p>
 * Covers T060 (never {@code psk_ke}), T061 (which tickets are offerable), T076 (accepting, bounding
 * and storing {@code NewSessionTicket}), T077 (the offer and the binder) and T078 (the 0-RTT key
 * installation and its RFC 9001 §4.9.3 discard point).
 */
public class TlsClientResumptionTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long NOW = 1_700_000_000_000L;
	private static final byte[] RESUMPTION_SECRET = bytes(32, 7);

	// ---- T077: the offer ----

	@Test
	public void aMatchingTicketIsOfferedAsTheLastExtensionWithAKeyShareBeside() throws Exception {
		ClientHelloMessage clientHello = resumingClientHello(ticket(), false);

		List<TlsExtension> extensions = clientHello.extensions;
		TlsExtension last = extensions.get(extensions.size() - 1);
		assertTrue("pre_shared_key must be the last extension (RFC 8446 §4.2.11)", last instanceof PreSharedKeyExt);

		PreSharedKeyExt offer = (PreSharedKeyExt) last;
		assertTrue(offer.isOffer());
		assertNotNull(offer.identities);
		assertNotNull(offer.binders);
		assertEquals(1, offer.identities.size());
		assertArrayEquals(IDENTITY, offer.identities.get(0).identity());
		assertEquals(32, offer.binders.get(0).length);

		// forward secrecy is not optional: a resuming ClientHello still carries a key share (FR-046)
		KeyShareExt keyShare = find(clientHello.extensions, KeyShareExt.class);
		assertNotNull(keyShare);
		assertNotNull(keyShare.clientShares);
		assertEquals(1, keyShare.clientShares.size());
	}

	@Test
	public void theObfuscatedTicketAgeIsTheAgePlusTheOffsetAsAUint32() throws Exception {
		QuicSessionTicket ticket = ticket();
		ClientHelloMessage clientHello = resumingClientHello(ticket, false, () -> NOW + 5_000);

		PreSharedKeyExt offer = find(clientHello.extensions, PreSharedKeyExt.class);
		assertNotNull(offer);
		assertNotNull(offer.identities);
		assertEquals((5_000 + TICKET_AGE_ADD) & 0xFFFFFFFFL, offer.identities.get(0).obfuscatedTicketAge());
		assertEquals(TlsClientEngine.obfuscatedTicketAge(ticket, NOW + 5_000),
			offer.identities.get(0).obfuscatedTicketAge());

		// a ticket_age_add near the uint32 ceiling wraps rather than overflowing
		QuicSessionTicket wrapping = ticketBuilder().withTicketAgeAdd(0xFFFFFFFFL).build();
		assertEquals(999, TlsClientEngine.obfuscatedTicketAge(wrapping, NOW + 1_000));
	}

	// ---- T060: psk_ke is never offered and never reachable ----

	@Test
	public void pskKeyExchangeModesIsPskDheKeOnlyWithOrWithoutATicket() throws Exception {
		for (ClientHelloMessage clientHello : List.of(plainClientHello(), resumingClientHello(ticket(), true))) {
			PskKeyExchangeModesExt modes = find(clientHello.extensions, PskKeyExchangeModesExt.class);
			assertNotNull(modes);
			assertArrayEquals(new int[] {PskKeyExchangeModesExt.PSK_DHE_KE}, modes.modes());
			for (int mode : modes.modes()) {
				assertNotEquals("psk_ke(0) must never be offered (FR-046)", 0, mode);
			}
		}
	}

	/**
	 * The forward-secrecy guarantee restated as a wire property: because {@code psk_ke} was never
	 * offered, a server accepting the PSK without a {@code key_share} is out of contract, and the
	 * existing {@code missing_extension} check still fires — resumption cannot silently drop the
	 * (EC)DHE exchange.
	 */
	@Test
	public void aPskAcceptanceWithoutAKeyShareStillAbortsWithMissingExtension() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		TlsEngine client = resumingClient(identity, ticket(), false);

		byte[] clientHelloBytes = emitClientHello(client);
		server.shExtensionsOverride = List.of(
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
			PreSharedKeyExt.ofSelectedIdentity(0));
		server.acceptClientHello(clientHelloBytes);

		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.MISSING_EXTENSION, e.alertCode());
	}

	// ---- T061: which tickets are offerable ----

	@Test
	public void theOfferableMatrixIsExactlyOriginAlpnLifetimeAndSuiteHash() {
		TlsCipherSuite[] sha256Only = {TlsCipherSuite.TLS_AES_128_GCM_SHA256, TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256};

		assertTrue(TlsClientEngine.isOfferable(ticket(), "example.test", "h3", NOW, sha256Only));
		assertFalse("null ticket", TlsClientEngine.isOfferable(null, "example.test", "h3", NOW, sha256Only));
		assertFalse("wrong server name",
			TlsClientEngine.isOfferable(ticket(), "other.test", "h3", NOW, sha256Only));
		assertFalse("wrong ALPN",
			TlsClientEngine.isOfferable(ticket(), "example.test", "hq-interop", NOW, sha256Only));
		assertFalse("expired",
			TlsClientEngine.isOfferable(ticket(), "example.test", "h3", NOW + LIFETIME_MILLIS, sha256Only));
		assertTrue("one millisecond before expiry",
			TlsClientEngine.isOfferable(ticket(), "example.test", "h3", NOW + LIFETIME_MILLIS - 1, sha256Only));

		QuicSessionTicket sha384 = QuicSessionTicket.builder("example.test", "h3",
				TlsCipherSuite.TLS_AES_256_GCM_SHA384, bytes(48, 3))
			.withIdentity(IDENTITY)
			.withIssuedAt(NOW)
			.withLifetime(LIFETIME_MILLIS)
			.withTicketAgeAdd(TICKET_AGE_ADD)
			.withTransportParameters(ScriptedTlsServer.SERVER_PARAMS)
			.build();
		assertFalse("a SHA-384 ticket against a SHA-256-only offer",
			TlsClientEngine.isOfferable(sha384, "example.test", "h3", NOW, sha256Only));
		assertTrue("the same ticket once SHA-384 is proposable",
			TlsClientEngine.isOfferable(sha384, "example.test", "h3", NOW, TlsCipherSuite.TLS_AES_256_GCM_SHA384));

		// a ticket that was never sealed carries no identity to offer
		assertFalse(TlsClientEngine.isOfferable(ticketBuilder().withIdentity(new byte[0]).build(),
			"example.test", "h3", NOW, sha256Only));
	}

	@Test
	public void aNonMatchingTicketProducesTheSameClientHelloAsNoTicketAtAll() throws Exception {
		QuicSessionTicket wrongOrigin = QuicSessionTicket.builder("other.test", "h3",
				TlsCipherSuite.TLS_AES_128_GCM_SHA256, RESUMPTION_SECRET)
			.withIdentity(IDENTITY)
			.withIssuedAt(NOW)
			.withLifetime(LIFETIME_MILLIS)
			.withTicketAgeAdd(TICKET_AGE_ADD)
			.withTransportParameters(ScriptedTlsServer.SERVER_PARAMS)
			.build();

		ClientHelloMessage plain = plainClientHello();
		ClientHelloMessage ignored = resumingClientHello(wrongOrigin, true);
		assertNull(find(ignored.extensions, PreSharedKeyExt.class));
		assertNull(find(ignored.extensions, EarlyDataExt.class));
		assertEquals("a ticket that does not match leaves the ClientHello byte-identical (FR-047)",
			extensionTypes(plain), extensionTypes(ignored));
	}

	// ---- T077/T078: the binder verifies and the 0-RTT keys install ----

	@Test
	public void theScriptedServerVerifiesTheBinderAndTheHandshakeCompletesResumed() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		TlsEngine client = resumingClient(identity, ticket(), false);

		completeResumedHandshake(client, server);
		assertTrue("the scripted server verified the binder independently", server.binderVerified);
		assertTrue(server.pskAccepted);
		assertNull("early data was neither offered nor accepted", server.offeredEarlyData);
	}

	@Test
	public void theZeroRttInstallationRidesTheClientHelloAndMatchesTheServersDerivation() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		server.acceptEarlyData = true;
		TlsEngine client = resumingClient(identity, ticket(), true);

		TlsEngineResult helloResult = client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
		byte[] clientHelloBytes;
		try {
			assertEquals(1, helloResult.keysToInstall().size());
			KeyInstallation zeroRtt = helloResult.keysToInstall().get(0);
			assertEquals(EncryptionLevel.ZERO_RTT, zeroRtt.level());
			assertNull("0-RTT is one-directional — a server never sends one (RFC 9001 §4.1.4)",
				zeroRtt.keys().serverKeys());
			assertFalse(helloResult.earlyDataAccepted());

			ByteBuf buf = helloResult.cryptoToSend().get(EncryptionLevel.INITIAL);
			assertNotNull(buf);
			clientHelloBytes = readBytes(buf);

			server.acceptClientHello(clientHelloBytes);
			assertNotNull(server.clientEarlyTraffic);
			assertKeys(TlsCipherSuite.TLS_AES_128_GCM_SHA256, server.clientEarlyTraffic, zeroRtt.keys().clientKeys());
		} finally {
			recycleOutput(helloResult);
		}

		TlsEngineResult serverHelloResult = client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		try {
			assertEquals(1, serverHelloResult.keysToInstall().size());
			assertEquals(EncryptionLevel.HANDSHAKE, serverHelloResult.keysToInstall().get(0).level());
		} finally {
			recycleOutput(serverHelloResult);
		}

		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
			assertTrue("the echoed early_data is the acceptance signal (FR-048)", completion.earlyDataAccepted());
			assertEquals(1, completion.keysToInstall().size());
			assertEquals(EncryptionLevel.ONE_RTT, completion.keysToInstall().get(0).level());
			server.assertClientFinished(readBytes(completion.cryptoToSend().get(EncryptionLevel.HANDSHAKE)));
		} finally {
			recycleOutput(completion);
		}

		// RFC 9001 §4.9.3: the 0-RTT secret is gone the moment 1-RTT keys install
		assertNull(secretField(client, "clientEarlyTrafficSecret"));
	}

	@Test
	public void noZeroRttInstallationWithoutEarlyDataOrWithoutAnOfferableTicket() throws Exception {
		TlsServerIdentity identity = rsaIdentity();

		TlsEngineResult withTicketOnly = firstResult(resumingClient(identity, ticket(), false));
		try {
			assertTrue("a ticket alone resumes but sends nothing in 0-RTT", withTicketOnly.keysToInstall().isEmpty());
		} finally {
			recycleOutput(withTicketOnly);
		}

		TlsEngineResult noTicket = firstResult(newClient("example.test", trustingLeaf(identity.leaf())));
		try {
			assertTrue(noTicket.keysToInstall().isEmpty());
		} finally {
			recycleOutput(noTicket);
		}
	}

	@Test
	public void aRefusedEarlyDataOfferNeverFailsTheHandshakeAndNeverReportsAcceptance() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		server.acceptEarlyData = false; // EncryptedExtensions omits early_data — the refusal signal
		TlsEngine client = resumingClient(identity, ticket(), true);

		server.acceptClientHello(emitClientHello(client));
		TlsEngineResult serverHelloResult = client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes));
		recycleOutput(serverHelloResult);

		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue("a refusal is not a failure (FR-048)", completion.handshakeComplete());
			assertFalse(completion.earlyDataAccepted());
		} finally {
			recycleOutput(completion);
		}
		assertNull(secretField(client, "clientEarlyTrafficSecret"));
	}

	@Test
	public void earlyDataInEncryptedExtensionsThatWasNeverOfferedIsUnsupportedExtension() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		TlsEngine client = resumingClient(identity, ticket(), false); // no early_data offered

		server.acceptClientHello(emitClientHello(client));
		server.eeExtensionsOverride = List.of(
			new AlpnExt(List.of(ScriptedTlsServer.ALPN_H3)),
			new QuicTransportParametersExt(ScriptedTlsServer.SERVER_PARAMS),
			EarlyDataExt.empty());

		TlsAlertException e = expectAlert(() -> {
			recycleOutput(client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		});
		assertEquals(TlsAlerts.UNSUPPORTED_EXTENSION, e.alertCode());
	}

	@Test
	public void earlyDataInEncryptedExtensionsCarryingAMaxSizeIsIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		server.acceptEarlyData = true;
		TlsEngine client = resumingClient(identity, ticket(), true);

		server.acceptClientHello(emitClientHello(client));
		server.eeExtensionsOverride = List.of(
			new AlpnExt(List.of(ScriptedTlsServer.ALPN_H3)),
			new QuicTransportParametersExt(ScriptedTlsServer.SERVER_PARAMS),
			EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE));

		TlsAlertException e = expectAlert(() -> {
			recycleOutput(client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
			client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		});
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void anUnsolicitedPreSharedKeyInTheServerHelloIsStillUnsupportedExtension() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = newClient("example.test", trustingLeaf(identity.leaf())); // offers no PSK
		server.shExtensionsOverride = selectionServerHelloExtensions(server, 0);
		server.acceptClientHello(emitClientHello(client));

		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.UNSUPPORTED_EXTENSION, e.alertCode());
	}

	@Test
	public void aSelectedIdentityOtherThanZeroIsIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		TlsEngine client = resumingClient(identity, ticket(), false);
		server.shExtensionsOverride = selectionServerHelloExtensions(server, 1);
		server.acceptClientHello(emitClientHello(client));

		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	@Test
	public void aPskAcceptedUnderADifferentHashIsIllegalParameter() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = resumingServer(identity);
		TlsEngine client = resumingClient(identity, ticket(), false); // a SHA-256 ticket
		server.selectedSuiteOverride = TlsCipherSuite.TLS_AES_256_GCM_SHA384;
		server.shExtensionsOverride = selectionServerHelloExtensions(server, 0);
		server.acceptClientHello(emitClientHello(client));

		TlsAlertException e = expectAlert(() -> client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		assertEquals(TlsAlerts.ILLEGAL_PARAMETER, e.alertCode());
	}

	// ---- T076: NewSessionTicket ----

	@Test
	public void aTwoTicketFlightSurfacesBothAndStoresBoth() throws Exception {
		InMemoryQuicSessionCache cache = InMemoryQuicSessionCache.create(256, () -> NOW);
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, cache);
		completeHandshakeWith(client, server);

		byte[] flight = concat(
			server.newSessionTicket(3600, 0x11111111L, new byte[] {1}, sealed(1), earlyDataMax()),
			server.newSessionTicket(3600, 0x22222222L, new byte[] {2}, sealed(2), earlyDataMax()));

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT, wrap(flight));
		assertEquals(2, result.issuedTickets().size());
		assertArrayEquals(sealed(1), result.issuedTickets().get(0).identity());
		assertArrayEquals(sealed(2), result.issuedTickets().get(1).identity());
		assertEquals(3_600_000L, result.issuedTickets().get(0).lifetimeMillis());
		assertEquals(0x11111111L, result.issuedTickets().get(0).ticketAgeAdd());
		assertEquals(ScriptedTlsServer.ALPN_H3, result.issuedTickets().get(0).alpn());
		assertEquals("example.test", result.issuedTickets().get(0).serverName());

		// the PSK the client derived is the one the server's own resumption master secret names
		assertArrayEquals(server.resumptionPskFor(new byte[] {1}), result.issuedTickets().get(0).resumptionSecret());
		assertArrayEquals(server.resumptionPskFor(new byte[] {2}), result.issuedTickets().get(1).resumptionSecret());

		// the later put wins on the same origin key, so the cache holds the second ticket
		assertEquals(1, cache.size());
		QuicSessionTicket taken = cache.take("example.test", 443, ScriptedTlsServer.ALPN_H3);
		assertNotNull(taken);
		assertArrayEquals(sealed(2), taken.identity());

		// the eight RFC 9000 §7.4.1 excludes never travel with a ticket
		assertNull(taken.transportParameters().initialSourceConnectionId());
		assertNull(taken.transportParameters().statelessResetToken());
		assertEquals(0, taken.transportParameters().maxIdleTimeout());
	}

	@Test
	public void aZeroLengthTicketNonceStillDerivesAPsk() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(3600, 5, new byte[0], sealed(1), earlyDataMax())));
		assertEquals(1, result.issuedTickets().size());
		assertArrayEquals(server.resumptionPskFor(new byte[0]), result.issuedTickets().get(0).resumptionSecret());
	}

	@Test
	public void aNonQuicMaxEarlyDataSizeIsAProtocolViolation() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		TlsProtocolViolationException e = assertThrows(TlsProtocolViolationException.class,
			() -> client.consume(EncryptionLevel.ONE_RTT, wrap(server.newSessionTicket(
				3600, 5, new byte[] {1}, sealed(1), List.of(EarlyDataExt.ofMaxEarlyDataSize(0xFFFFFFFEL))))));
		assertTrue(e.getMessage().contains("max_early_data_size"));
	}

	@Test
	public void aTicketOverTheSizeBoundIsAProtocolViolation() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		byte[] oversized = new byte[8 * 1024 + 1];
		Arrays.fill(oversized, (byte) 9);
		TlsProtocolViolationException e = assertThrows(TlsProtocolViolationException.class,
			() -> client.consume(EncryptionLevel.ONE_RTT,
				wrap(server.newSessionTicket(3600, 5, new byte[] {1}, oversized, earlyDataMax()))));
		assertTrue(e.getMessage().contains("maxSessionTicketSize"));
	}

	@Test
	public void theNinthTicketAtTheDefaultBoundIsAProtocolViolation() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		for (int i = 0; i < 8; i++) {
			TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT,
				wrap(server.newSessionTicket(3600, 5, new byte[] {(byte) i}, sealed(i), earlyDataMax())));
			assertEquals(1, result.issuedTickets().size());
		}
		TlsProtocolViolationException e = assertThrows(TlsProtocolViolationException.class,
			() -> client.consume(EncryptionLevel.ONE_RTT,
				wrap(server.newSessionTicket(3600, 5, new byte[] {9}, sealed(9), earlyDataMax()))));
		assertTrue(e.getMessage().contains("maxSessionTicketsPerConnection"));
	}

	@Test
	public void aZeroLifetimeTicketIsDiscardedAndTheConnectionSurvives() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		TlsEngineResult discarded = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(0, 5, new byte[] {1}, sealed(1), earlyDataMax())));
		assertTrue(discarded.issuedTickets().isEmpty());
		assertFalse(discarded.handshakeComplete());

		// still alive: the next, well-formed ticket is accepted
		TlsEngineResult accepted = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(3600, 5, new byte[] {2}, sealed(2), earlyDataMax())));
		assertEquals(1, accepted.issuedTickets().size());
	}

	@Test
	public void aTicketLifetimeOverSevenDaysIsClampedRatherThanRefused() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(0xFFFFFFFFL, 5, new byte[] {1}, sealed(1), earlyDataMax())));
		assertEquals(604_800_000L, result.issuedTickets().get(0).lifetimeMillis());
	}

	@Test
	public void withTicketsDisabledNothingIsRetainedAndNothingIsRefused() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = QuicTls.clientEngine(TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustManager(trustingLeaf(identity.leaf()))
			.withMaxSessionTicketsPerConnection(0)
			.build());
		completeHandshakeWith(client, server);

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(3600, 5, new byte[] {1}, sealed(1), earlyDataMax())));
		assertTrue(result.issuedTickets().isEmpty());
		assertNull("no resumption master secret is kept resident", secretField(client, "resumptionMasterSecret"));
	}

	@Test
	public void withoutACacheTheResultIsStillTheRouteToATicket() throws Exception {
		TlsServerIdentity identity = rsaIdentity();
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		TlsEngine client = clientWithCache(identity, null);
		completeHandshakeWith(client, server);

		TlsEngineResult result = client.consume(EncryptionLevel.ONE_RTT,
			wrap(server.newSessionTicket(3600, 5, new byte[] {1}, sealed(1), earlyDataMax())));
		assertEquals(1, result.issuedTickets().size());
	}

	// ---- end-to-end: a ticket the client accepted resumes the next connection ----

	@Test
	public void aTicketAcceptedOnOneConnectionResumesTheNext() throws Exception {
		InMemoryQuicSessionCache cache = InMemoryQuicSessionCache.create(256, () -> NOW);
		TlsServerIdentity identity = rsaIdentity();

		ScriptedTlsServer first = new ScriptedTlsServer(identity);
		TlsEngine firstClient = clientWithCache(identity, cache);
		completeHandshakeWith(firstClient, first);
		TlsEngineResult issued = firstClient.consume(EncryptionLevel.ONE_RTT,
			wrap(first.newSessionTicket(3600, 0x1234L, new byte[] {4, 2}, sealed(1), earlyDataMax())));
		assertEquals(1, issued.issuedTickets().size());

		QuicSessionTicket stored = cache.take("example.test", 443, ScriptedTlsServer.ALPN_H3);
		assertNotNull(stored);

		ScriptedTlsServer second = new ScriptedTlsServer(identity);
		second.acceptPsk = true;
		second.psk = stored.resumptionSecret();
		second.pskSuite = stored.cipherSuite();
		TlsEngine secondClient = resumingClient(identity, stored, false);

		completeResumedHandshake(secondClient, second);
		assertTrue(second.binderVerified);
		assertTrue(second.pskAccepted);
	}

	// ---- helpers ----

	private static final byte[] IDENTITY = {1, 2, 3, 4, 5, 6, 7, 8};
	private static final long LIFETIME_MILLIS = 3_600_000L;
	private static final long TICKET_AGE_ADD = 0x0BADF00DL;

	private static QuicSessionTicket ticket() {
		return ticketBuilder().build();
	}

	private static QuicSessionTicket.Builder ticketBuilder() {
		return QuicSessionTicket.builder("example.test", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, RESUMPTION_SECRET)
			.withIdentity(IDENTITY)
			.withIssuedAt(NOW)
			.withLifetime(LIFETIME_MILLIS)
			.withTicketAgeAdd(TICKET_AGE_ADD)
			.withTransportParameters(ScriptedTlsServer.SERVER_PARAMS);
	}

	private static ScriptedTlsServer resumingServer(TlsServerIdentity identity) {
		ScriptedTlsServer server = new ScriptedTlsServer(identity);
		server.acceptPsk = true;
		server.psk = RESUMPTION_SECRET;
		server.pskSuite = TlsCipherSuite.TLS_AES_128_GCM_SHA256;
		return server;
	}

	private static TlsEngine resumingClient(TlsServerIdentity identity, QuicSessionTicket ticket, boolean earlyData) {
		return resumingClient(identity, ticket, earlyData, () -> NOW);
	}

	private static TlsEngine resumingClient(TlsServerIdentity identity, QuicSessionTicket ticket, boolean earlyData,
			LongSupplier clock) {
		return QuicTls.clientEngine(TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustManager(trustingLeaf(identity.leaf()))
			.withSessionTicket(ticket)
			.withEarlyDataEnabled(earlyData)
			.withCurrentTimeMillis(clock)
			.build());
	}

	private static TlsEngine clientWithCache(TlsServerIdentity identity, InMemoryQuicSessionCache cache) {
		TlsClientConfig.Builder builder = TlsClientConfig.builder("example.test", CLIENT_PARAMS)
			.withTrustManager(trustingLeaf(identity.leaf()))
			.withCurrentTimeMillis(() -> NOW);
		if (cache != null) {
			builder.withSessionCache(cache, 443);
		}
		return QuicTls.clientEngine(builder.build());
	}

	private static ClientHelloMessage plainClientHello() throws Exception {
		return parseClientHello(emitClientHello(
			newClient("example.test", trustingLeaf(rsaIdentity().leaf()))));
	}

	private static ClientHelloMessage resumingClientHello(QuicSessionTicket ticket, boolean earlyData) throws Exception {
		return resumingClientHello(ticket, earlyData, () -> NOW);
	}

	private static ClientHelloMessage resumingClientHello(QuicSessionTicket ticket, boolean earlyData, LongSupplier clock)
			throws Exception {
		return parseClientHello(emitClientHello(resumingClient(rsaIdentity(), ticket, earlyData, clock)));
	}

	private static ClientHelloMessage parseClientHello(byte[] bytes) throws Exception {
		return (ClientHelloMessage) TlsMessages.read(ByteBuf.wrapForReading(bytes));
	}

	/** A hand-built ServerHello extension list that selects a PSK identity, bypassing the scripted default. */
	private static List<TlsExtension> selectionServerHelloExtensions(ScriptedTlsServer server, int selectedIdentity) {
		return List.of(
			SupportedVersionsExt.ofSelectedVersion(SupportedVersionsExt.TLS_1_3),
			KeyShareExt.ofSelectedShare(new KeyShareExt.KeyShareEntry(
				NamedGroup.X25519.code(),
				TlsKeyExchanges.encodePublicKey(NamedGroup.X25519, server.keyPair.getPublic()))),
			PreSharedKeyExt.ofSelectedIdentity(selectedIdentity));
	}

	private static TlsEngineResult firstResult(TlsEngine client) throws Exception {
		return client.consume(EncryptionLevel.INITIAL, ByteBuf.empty());
	}

	private static void completeHandshakeWith(TlsEngine client, ScriptedTlsServer server) throws Exception {
		server.acceptClientHello(emitClientHello(client));
		recycleOutput(client.consume(EncryptionLevel.INITIAL, wrap(server.serverHelloBytes)));
		TlsEngineResult completion = client.consume(EncryptionLevel.HANDSHAKE, wrap(server.handshakeFlight(false, false)));
		try {
			assertTrue(completion.handshakeComplete());
			server.assertClientFinished(readBytes(completion.cryptoToSend().get(EncryptionLevel.HANDSHAKE)));
		} finally {
			recycleOutput(completion);
		}
	}

	private static void completeResumedHandshake(TlsEngine client, ScriptedTlsServer server) throws Exception {
		completeHandshakeWith(client, server);
	}

	private static List<Integer> extensionTypes(ClientHelloMessage clientHello) {
		List<Integer> types = new ArrayList<>();
		for (TlsExtension extension : clientHello.extensions) {
			types.add(extension.type());
		}
		return types;
	}

	private static List<TlsExtension> earlyDataMax() {
		return List.of(EarlyDataExt.ofMaxEarlyDataSize(EarlyDataExt.QUIC_MAX_EARLY_DATA_SIZE));
	}

	private static byte[] sealed(int seed) {
		byte[] blob = new byte[64];
		for (int i = 0; i < blob.length; i++) blob[i] = (byte) (seed * 17 + i);
		return blob;
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] joined = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, joined, first.length, second.length);
		return joined;
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + seed);
		return bytes;
	}

	private static <T extends TlsExtension> T find(List<TlsExtension> extensions, Class<T> type) {
		for (TlsExtension extension : extensions) {
			if (type.isInstance(extension)) return type.cast(extension);
		}
		return null;
	}

	/** Private engine state read reflectively, as {@code EncryptionLevelTest} already does — zeroing is invisible otherwise. */
	private static byte[] secretField(TlsEngine engine, String name) throws Exception {
		Field field = TlsClientEngine.class.getDeclaredField(name);
		field.setAccessible(true);
		return (byte[]) field.get(engine);
	}
}
