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

import io.activej.common.MemSize;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.activej.quic.tls.QuicSessionTicketTest.remembered;
import static io.activej.quic.tls.QuicSessionTicketTest.secret;
import static io.activej.quic.tls.ScriptedTlsServer.SERVER_PARAMS;
import static io.activej.quic.tls.TlsClientEngineTest.CLIENT_PARAMS;
import static io.activej.quic.tls.TlsServerIdentityTest.fixture;
import static org.junit.Assert.*;

/**
 * T073 + T074 — the resumption seam (research D-6): everything 0-RTT needs travels through
 * {@link TlsClientConfig}, {@link TlsServerConfig} and {@link TlsEngineResult}, so
 * {@code QuicConnection.TlsEngineFactory}'s signature never changes.
 * <p>
 * Both properties that make the change safe are asserted here rather than reviewed: the additions
 * are <b>additive</b> (every phase-1 accessor and factory behaves exactly as before), and a
 * configuration that supplies no ticket, no store and no sealing keys is byte-for-byte phase 1.
 */
public final class TlsResumptionSeamTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long T0 = 1_700_000_000_000L;

	// ---------------------------------------------------------------- TlsEngineResult (T073)

	@Test
	public void anEmptyResultCarriesNoResumptionOutcome() {
		TlsEngineResult empty = TlsEngineResult.empty();

		assertTrue(empty.cryptoToSend().isEmpty());
		assertTrue(empty.keysToInstall().isEmpty());
		assertNull(empty.negotiatedAlpn());
		assertNull(empty.peerTransportParameters());
		assertFalse(empty.handshakeComplete());

		assertTrue(empty.issuedTickets().isEmpty());
		assertFalse(empty.earlyDataAccepted());
	}

	@Test
	public void thePhase1FactoriesStillProduceNoResumptionOutcome() {
		TlsEngineResult inProgress = TlsEngineResult.of(Map.of(), List.of());
		assertTrue(inProgress.issuedTickets().isEmpty());
		assertFalse(inProgress.earlyDataAccepted());

		TlsEngineResult complete = TlsEngineResult.complete(Map.of(), List.of(), "h3", SERVER_PARAMS);
		assertTrue(complete.issuedTickets().isEmpty());
		assertFalse(complete.earlyDataAccepted());
		assertTrue(complete.handshakeComplete());
		assertEquals("h3", complete.negotiatedAlpn());
		assertEquals(SERVER_PARAMS, complete.peerTransportParameters());
	}

	@Test
	public void anInProgressResultCarriesTheTicketsTheServerIssued() {
		QuicSessionTicket ticket = ticket();

		TlsEngineResult result = TlsEngineResult.of(Map.of(), List.of(), List.of(ticket), false);

		assertEquals(1, result.issuedTickets().size());
		assertSame(ticket, result.issuedTickets().get(0));
		assertFalse(result.handshakeComplete());
		assertFalse(result.earlyDataAccepted());
	}

	@Test
	public void aCompletingResultCarriesTheEarlyDataDecisionBesideTheExistingOutcome() {
		QuicSessionTicket ticket = ticket();

		TlsEngineResult result = TlsEngineResult.complete(
			Map.of(), List.of(), "h3", SERVER_PARAMS, List.of(ticket), true);

		assertTrue(result.handshakeComplete());
		assertEquals("h3", result.negotiatedAlpn());
		assertEquals(SERVER_PARAMS, result.peerTransportParameters());
		assertEquals(List.of(ticket), result.issuedTickets());
		assertTrue(result.earlyDataAccepted());
	}

	@Test
	public void theIssuedTicketsAreAnImmutableSnapshot() {
		List<QuicSessionTicket> source = new ArrayList<>();
		source.add(ticket());

		TlsEngineResult result = TlsEngineResult.of(Map.of(), List.of(), source, false);
		source.add(ticket());

		assertEquals(1, result.issuedTickets().size());
		assertThrows(UnsupportedOperationException.class, () -> result.issuedTickets().add(ticket()));
	}

	@Test
	public void theResultKeepsTicketMaterialOutOfItsStringForm() {
		QuicSessionTicket ticket = ticket();
		TlsEngineResult result = TlsEngineResult.of(Map.of(), List.of(), List.of(ticket), true);

		String printed = String.valueOf(result);

		assertFalse(printed.contains(hex(ticket.resumptionSecret())));
		assertFalse(printed.contains(new String(ticket.resumptionSecret(), StandardCharsets.ISO_8859_1)));
	}

	// ---------------------------------------------------------------- TlsClientConfig (T074)

	@Test
	public void aClientConfigWithoutResumptionIsPhase1() {
		TlsClientConfig config = TlsClientConfig.builder("example.com", CLIENT_PARAMS).build();

		assertNull(config.sessionTicket());
		assertNull(config.sessionCache());
		assertEquals(0, config.remotePort());
		assertFalse(config.earlyDataEnabled());
		assertEquals(MemSize.kilobytes(8), config.maxSessionTicketSize());
		assertEquals(8, config.maxSessionTicketsPerConnection());
	}

	@Test
	public void aClientConfigCarriesTheTicketToOfferAndTheStoreToFill() {
		QuicSessionTicket ticket = ticket();
		QuicSessionCache cache = InMemoryQuicSessionCache.create(4, () -> T0);

		TlsClientConfig config = TlsClientConfig.builder("example.com", CLIENT_PARAMS)
			.withSessionTicket(ticket)
			.withSessionCache(cache, 443)
			.withEarlyDataEnabled(true)
			.build();

		assertSame(ticket, config.sessionTicket());
		assertSame(cache, config.sessionCache());
		assertEquals(443, config.remotePort());
		assertTrue(config.earlyDataEnabled());
	}

	@Test
	public void theClientTicketBoundsAreOverridablePerEngine() {
		TlsClientConfig config = TlsClientConfig.builder("example.com", CLIENT_PARAMS)
			.withMaxSessionTicketSize(MemSize.kilobytes(2))
			.withMaxSessionTicketsPerConnection(0)
			.build();

		assertEquals(MemSize.kilobytes(2), config.maxSessionTicketSize());
		assertEquals(0, config.maxSessionTicketsPerConnection());
	}

	@Test
	public void aClientStoreWithoutAUsablePortIsRefused() {
		QuicSessionCache cache = InMemoryQuicSessionCache.create(4, () -> T0);

		assertThrows(IllegalArgumentException.class,
			() -> TlsClientConfig.builder("example.com", CLIENT_PARAMS).withSessionCache(cache, 0));
		assertThrows(IllegalArgumentException.class,
			() -> TlsClientConfig.builder("example.com", CLIENT_PARAMS).withSessionCache(cache, 65536));
	}

	@Test
	public void theClientTicketBoundsRefuseAnUnusableValue() {
		assertThrows(IllegalArgumentException.class,
			() -> TlsClientConfig.builder("example.com", CLIENT_PARAMS).withMaxSessionTicketSize(MemSize.ZERO));
		assertThrows(IllegalArgumentException.class,
			() -> TlsClientConfig.builder("example.com", CLIENT_PARAMS).withMaxSessionTicketsPerConnection(-1));
	}

	@Test
	public void everyClientResumptionSetterIsRefusedAfterBuild() {
		TlsClientConfig.Builder builder = TlsClientConfig.builder("example.com", CLIENT_PARAMS);
		builder.build();

		QuicSessionCache cache = InMemoryQuicSessionCache.create(4, () -> T0);
		assertThrows(IllegalStateException.class, () -> builder.withSessionTicket(ticket()));
		assertThrows(IllegalStateException.class, () -> builder.withSessionCache(cache, 443));
		assertThrows(IllegalStateException.class, () -> builder.withEarlyDataEnabled(true));
		assertThrows(IllegalStateException.class, () -> builder.withMaxSessionTicketSize(MemSize.kilobytes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withMaxSessionTicketsPerConnection(1));
	}

	// ---------------------------------------------------------------- TlsServerConfig (T074)

	@Test
	public void aServerConfigWithoutSealingKeysIssuesNoTicket() {
		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS).build();

		assertNull(config.ticketKeys());
		assertEquals(2, config.sessionTicketsPerHandshake());
		assertEquals(10_000L, config.ticketAgeToleranceMillis());
		assertEquals(HOUR, config.sessionTicketLifetimeMillis());
	}

	@Test
	public void aServerConfigCarriesTheSealingKeysAndTheTicketPolicy() {
		QuicTicketKeys keys = keys(HOUR);

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys)
			.withSessionTicketsPerHandshake(0)
			.withTicketAgeTolerance(Duration.ofSeconds(3))
			.build();

		assertSame(keys, config.ticketKeys());
		assertEquals(0, config.sessionTicketsPerHandshake());
		assertEquals(3_000L, config.ticketAgeToleranceMillis());
	}

	@Test
	public void theAdvertisedLifetimeDefaultsToWhatTheSealingKeysCanStillOpen() {
		QuicTicketKeys keys = keys(HOUR / 2);

		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys)
			.build();

		assertEquals(keys.ticketLifetimeMillis(), config.sessionTicketLifetimeMillis());
	}

	@Test
	public void anAdvertisedLifetimeLongerThanTheKeysCanOpenIsRefused() {
		TlsServerConfig.Builder builder = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys(HOUR))
			.withSessionTicketLifetime(Duration.ofHours(2));

		assertThrows(IllegalStateException.class, builder::build);
	}

	@Test
	public void aShorterAdvertisedLifetimeIsAccepted() {
		TlsServerConfig config = TlsServerConfig.builder(identity(), SERVER_PARAMS)
			.withTicketKeys(keys(HOUR))
			.withSessionTicketLifetime(Duration.ofMinutes(10))
			.build();

		assertEquals(600_000L, config.sessionTicketLifetimeMillis());
	}

	@Test
	public void theServerTicketPolicyRefusesAnUnusableValue() {
		assertThrows(IllegalArgumentException.class,
			() -> TlsServerConfig.builder(identity(), SERVER_PARAMS).withSessionTicketsPerHandshake(-1));
		assertThrows(IllegalArgumentException.class,
			() -> TlsServerConfig.builder(identity(), SERVER_PARAMS).withTicketAgeTolerance(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class,
			() -> TlsServerConfig.builder(identity(), SERVER_PARAMS).withSessionTicketLifetime(Duration.ZERO));
	}

	@Test
	public void everyServerResumptionSetterIsRefusedAfterBuild() {
		TlsServerConfig.Builder builder = TlsServerConfig.builder(identity(), SERVER_PARAMS);
		builder.build();

		assertThrows(IllegalStateException.class, () -> builder.withTicketKeys(keys(HOUR)));
		assertThrows(IllegalStateException.class, () -> builder.withSessionTicketLifetime(Duration.ofMinutes(1)));
		assertThrows(IllegalStateException.class, () -> builder.withSessionTicketsPerHandshake(1));
		assertThrows(IllegalStateException.class, () -> builder.withTicketAgeTolerance(Duration.ofSeconds(1)));
		assertThrows(IllegalStateException.class, () -> builder.withReplayGuard(QuicReplayGuard.create(8)));
	}

	// ---------------------------------------------------------------- fixtures

	private static QuicSessionTicket ticket() {
		return QuicSessionTicket.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(HOUR)
			.withTicketAgeAdd(0x0F0F0F0FL)
			.withTransportParameters(remembered())
			.build();
	}

	private static QuicTicketKeys keys(long lifetimeMillis) {
		return QuicTicketKeys.create(new SecureRandom(), 6 * HOUR, lifetimeMillis, T0);
	}

	private static @Nullable TlsServerIdentity cachedIdentity;

	private static TlsServerIdentity identity() {
		if (cachedIdentity == null) {
			try {
				cachedIdentity = TlsServerIdentity.fromPem(fixture("ecdsa-cert.pem"), fixture("ecdsa-key.pem"));
			} catch (IOException e) {
				throw new AssertionError("The ECDSA fixture identity must load", e);
			}
		}
		return cachedIdentity;
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return sb.toString();
	}
}
