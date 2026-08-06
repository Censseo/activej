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

import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * T056 — session-ticket sealing round trip (spec FR-042, FR-045).
 * <p>
 * Seal → open must recover every plaintext field the resumption decision is made from, and a ticket
 * that cannot be opened — a rotated-out key, a tampered blob, a foreign key set — must produce
 * {@code null}, which the engine reads as "full handshake", never an exception and never an
 * unauthenticated session.
 */
public final class QuicSessionTicketTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long ROTATION = 6 * HOUR;
	private static final long LIFETIME = HOUR;
	private static final long T0 = 1_700_000_000_000L;

	@Test
	public void sealThenOpenRecoversEveryPlaintextField() {
		QuicTicketKeys keys = keys(T0);
		QuicSessionTicket original = ticket();

		byte[] sealed = keys.seal(original, T0);
		QuicSessionTicket opened = keys.open(sealed);

		assertNotNull(opened);
		assertEquals(original.serverName(), opened.serverName());
		assertEquals(original.alpn(), opened.alpn());
		assertEquals(original.cipherSuite(), opened.cipherSuite());
		assertArrayEquals(original.resumptionSecret(), opened.resumptionSecret());
		assertEquals(original.issuedAtMillis(), opened.issuedAtMillis());
		assertEquals(original.lifetimeMillis(), opened.lifetimeMillis());
		assertEquals(original.ticketAgeAdd(), opened.ticketAgeAdd());
		assertEquals(original.transportParameters(), opened.transportParameters());
		assertArrayEquals(original.applicationSettings(), opened.applicationSettings());
	}

	@Test
	public void anOpenedTicketCarriesTheSealedBlobAsItsIdentity() {
		QuicTicketKeys keys = keys(T0);
		byte[] sealed = keys.seal(ticket(), T0);

		QuicSessionTicket opened = keys.open(sealed);

		assertNotNull(opened);
		assertArrayEquals(sealed, opened.identity());
	}

	@Test
	public void anUnsealedTicketHasAnEmptyIdentity() {
		assertEquals(0, ticket().identity().length);
	}

	@Test
	public void aTicketSealedUnderThePreviousKeyStillOpensAfterOneRotation() {
		QuicTicketKeys keys = keys(T0);
		byte[] sealed = keys.seal(ticket(), T0);

		assertTrue(keys.rotateIfDue(T0 + ROTATION));

		assertNotNull(keys.open(sealed));
	}

	@Test
	public void aTicketSealedUnderARotatedOutKeyFailsToOpenAndIsNotAnError() {
		QuicTicketKeys keys = keys(T0);
		byte[] sealed = keys.seal(ticket(), T0);

		keys.rotateIfDue(T0 + ROTATION);
		keys.rotateIfDue(T0 + 2 * ROTATION);

		assertNull(keys.open(sealed));
	}

	@Test
	public void aTicketSealedByAnotherEndpointFailsToOpen() {
		QuicTicketKeys mine = keys(T0);
		QuicTicketKeys theirs = keys(T0);

		assertNull(mine.open(theirs.seal(ticket(), T0)));
	}

	@Test
	public void openIsTotalOnAttackerControlledInput() {
		QuicTicketKeys keys = keys(T0);
		byte[] sealed = keys.seal(ticket(), T0);

		assertNull(keys.open(new byte[0]));
		assertNull(keys.open(new byte[]{1}));
		assertNull(keys.open(new byte[64]));
		assertNull(keys.open(Arrays.copyOf(sealed, sealed.length - 1)));
		assertNull(keys.open(Arrays.copyOf(sealed, sealed.length + 16)));

		byte[] wrongVersion = sealed.clone();
		wrongVersion[0] ^= 0x40;
		assertNull(keys.open(wrongVersion));

		byte[] tamperedNonce = sealed.clone();
		tamperedNonce[3] ^= 0x01;
		assertNull(keys.open(tamperedNonce));

		byte[] tamperedBody = sealed.clone();
		tamperedBody[sealed.length - 1] ^= 0x01;
		assertNull(keys.open(tamperedBody));
	}

	@Test
	public void sealingIsNotDeterministic() {
		QuicTicketKeys keys = keys(T0);
		QuicSessionTicket ticket = ticket();

		assertFalse(Arrays.equals(keys.seal(ticket, T0), keys.seal(ticket, T0)));
	}

	@Test
	public void anEmptyApplicationSettingsBlobRoundTrips() {
		QuicTicketKeys keys = keys(T0);
		QuicSessionTicket ticket = QuicSessionTicket.builder("example.com", "h3",
				TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(0)
			.withTransportParameters(QuicTransportParameters.defaults(new byte[]{1, 2, 3, 4}))
			.build();

		QuicSessionTicket opened = keys.open(keys.seal(ticket, T0));

		assertNotNull(opened);
		assertEquals(0, opened.applicationSettings().length);
		assertEquals(0, opened.ticketAgeAdd());
		assertEquals(TlsCipherSuite.TLS_CHACHA20_POLY1305_SHA256, opened.cipherSuite());
	}

	@Test
	public void everyCipherSuiteRoundTrips() {
		QuicTicketKeys keys = keys(T0);
		for (TlsCipherSuite suite : TlsCipherSuite.values()) {
			QuicSessionTicket ticket = QuicSessionTicket.builder("example.com", "h3", suite, secret(48))
				.withIssuedAt(T0)
				.withLifetime(LIFETIME)
				.withTicketAgeAdd(1)
				.withTransportParameters(remembered())
				.build();
			QuicSessionTicket opened = keys.open(keys.seal(ticket, T0));
			assertNotNull(opened);
			assertEquals(suite, opened.cipherSuite());
		}
	}

	@Test
	public void ageAndExpiryAreMeasuredFromTheIssueTime() {
		QuicSessionTicket ticket = ticket();

		assertEquals(0, ticket.ageMillisAt(T0));
		assertEquals(1000, ticket.ageMillisAt(T0 + 1000));
		assertEquals(0, ticket.ageMillisAt(T0 - 5000));

		assertFalse(ticket.isExpiredAt(T0));
		assertFalse(ticket.isExpiredAt(T0 + LIFETIME - 1));
		assertTrue(ticket.isExpiredAt(T0 + LIFETIME));
		assertTrue(ticket.isExpiredAt(T0 + LIFETIME + 1));
	}

	@Test
	public void isForMatchesServerNameAndAlpnTogether() {
		QuicSessionTicket ticket = ticket();

		assertTrue(ticket.isFor("example.com", "h3"));
		assertFalse(ticket.isFor("other.example.com", "h3"));
		assertFalse(ticket.isFor("example.com", "hq-interop"));
	}

	@Test
	public void rememberableParametersDropsTheEightRfc9000Excludes() {
		QuicTransportParameters full = new QuicTransportParameters(
			new byte[]{1, 2, 3, 4}, 30_000, new byte[16], 1350,
			1_000_000, 256_000, 256_001, 256_002, 100, 3,
			7, 50, true, new byte[]{9, 9}, 4,
			new byte[]{5, 6, 7, 8}, new byte[]{7, 7}, 1252);

		QuicTransportParameters remembered = QuicSessionTicket.rememberableParameters(full);

		assertNull(remembered.originalDestinationConnectionId());
		assertNull(remembered.statelessResetToken());
		assertNull(remembered.preferredAddress());
		assertNull(remembered.initialSourceConnectionId());
		assertNull(remembered.retrySourceConnectionId());
		assertEquals(0, remembered.maxIdleTimeout());
		assertEquals(QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, remembered.ackDelayExponent());
		assertEquals(QuicTransportParameters.DEFAULT_MAX_ACK_DELAY, remembered.maxAckDelay());

		assertEquals(1350, remembered.maxUdpPayloadSize());
		assertEquals(1_000_000, remembered.initialMaxData());
		assertEquals(256_000, remembered.initialMaxStreamDataBidiLocal());
		assertEquals(256_001, remembered.initialMaxStreamDataBidiRemote());
		assertEquals(256_002, remembered.initialMaxStreamDataUni());
		assertEquals(100, remembered.initialMaxStreamsBidi());
		assertEquals(3, remembered.initialMaxStreamsUni());
		assertTrue(remembered.disableActiveMigration());
		assertEquals(4, remembered.activeConnectionIdLimit());
	}

	@Test
	public void aBuiltTicketCanNeverCarryAnExcludedParameter() {
		QuicTransportParameters full = new QuicTransportParameters(
			new byte[]{1, 2, 3, 4}, 30_000, new byte[16], 1350,
			1_000_000, 256_000, 256_001, 256_002, 100, 3,
			7, 50, true, new byte[]{9, 9}, 4,
			new byte[]{5, 6, 7, 8}, new byte[]{7, 7}, 1252);

		QuicSessionTicket ticket = QuicSessionTicket.builder("example.com", "h3",
				TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(1)
			.withTransportParameters(full)
			.build();

		assertEquals(QuicSessionTicket.rememberableParameters(full), ticket.transportParameters());
		assertNull(ticket.transportParameters().initialSourceConnectionId());
	}

	@Test
	public void builderRefusesTheFourMandatoryFieldsWhenUnset() {
		assertThrows(IllegalStateException.class, () -> base().build());
		assertThrows(IllegalStateException.class, () -> base().withLifetime(LIFETIME).withTicketAgeAdd(0)
			.withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base().withIssuedAt(T0).withTicketAgeAdd(0)
			.withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base().withIssuedAt(T0).withLifetime(LIFETIME)
			.withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base().withIssuedAt(T0).withLifetime(LIFETIME)
			.withTicketAgeAdd(0).build());
	}

	@Test
	public void aLegalZeroTicketAgeAddIsDistinguishableFromUnset() {
		QuicSessionTicket ticket = base()
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0).withTransportParameters(remembered())
			.build();

		assertEquals(0, ticket.ticketAgeAdd());
	}

	@Test
	public void builderRefusesOutOfRangeValues() {
		assertThrows(IllegalStateException.class, () -> base()
			.withIssuedAt(T0).withLifetime(0).withTicketAgeAdd(0).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base()
			.withIssuedAt(-1).withLifetime(LIFETIME).withTicketAgeAdd(0).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base()
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(-1).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> base()
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(1L << 32).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> QuicSessionTicket
			.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, new byte[0])
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> QuicSessionTicket
			.builder("", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0).withTransportParameters(remembered()).build());
		assertThrows(IllegalStateException.class, () -> QuicSessionTicket
			.builder("example.com", "", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0).withTransportParameters(remembered()).build());
	}

	@Test
	public void aMaximalTicketAgeAddRoundTrips() {
		QuicTicketKeys keys = keys(T0);
		QuicSessionTicket ticket = base()
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(0xFFFFFFFFL)
			.withTransportParameters(remembered()).build();

		QuicSessionTicket opened = keys.open(keys.seal(ticket, T0));

		assertNotNull(opened);
		assertEquals(0xFFFFFFFFL, opened.ticketAgeAdd());
	}

	@Test
	public void everyByteArrayIsClonedInAndOut() {
		byte[] secret = secret(32);
		byte[] settings = {1, 2, 3};
		byte[] identity = {4, 5, 6};

		QuicSessionTicket ticket = QuicSessionTicket.builder("example.com", "h3",
				TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret)
			.withIdentity(identity)
			.withIssuedAt(T0).withLifetime(LIFETIME).withTicketAgeAdd(1)
			.withTransportParameters(remembered())
			.withApplicationSettings(settings)
			.build();

		secret[0] ^= 0xFF;
		settings[0] ^= 0xFF;
		identity[0] ^= 0xFF;

		assertNotEquals(secret[0], ticket.resumptionSecret()[0]);
		assertNotEquals(settings[0], ticket.applicationSettings()[0]);
		assertNotEquals(identity[0], ticket.identity()[0]);

		ticket.resumptionSecret()[0] ^= 0xFF;
		ticket.applicationSettings()[0] ^= 0xFF;
		ticket.identity()[0] ^= 0xFF;

		assertEquals(1, ticket.applicationSettings()[0]);
		assertEquals(4, ticket.identity()[0]);
	}

	@Test
	public void toStringCarriesOriginAndSuiteOnly() {
		assertEquals("QuicSessionTicket[example.com, h3, TLS_AES_128_GCM_SHA256]", ticket().toString());
	}

	private static QuicSessionTicket.Builder base() {
		return QuicSessionTicket.builder("example.com", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32));
	}

	private static QuicSessionTicket ticket() {
		return base()
			.withIssuedAt(T0)
			.withLifetime(LIFETIME)
			.withTicketAgeAdd(0x0F0F0F0FL)
			.withTransportParameters(remembered())
			.withApplicationSettings(new byte[]{0x04, 0x40, 0x64, 0x06, 0x44, 0x00})
			.build();
	}

	static QuicTransportParameters remembered() {
		return QuicSessionTicket.rememberableParameters(new QuicTransportParameters(
			null, 0, null, 1350,
			1_000_000, 256_000, 256_001, 256_002, 100, 3,
			QuicTransportParameters.DEFAULT_ACK_DELAY_EXPONENT, QuicTransportParameters.DEFAULT_MAX_ACK_DELAY,
			false, null, 4, null, null, 0));
	}

	static byte[] secret(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31 + 7);
		return bytes;
	}

	private static QuicTicketKeys keys(long nowMillis) {
		return QuicTicketKeys.create(new SecureRandom(), ROTATION, LIFETIME, nowMillis);
	}
}
