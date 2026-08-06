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

import static io.activej.quic.tls.QuicSessionTicketTest.remembered;
import static io.activej.quic.tls.QuicSessionTicketTest.secret;
import static org.junit.Assert.*;

/**
 * T065 — the client's ticket store (spec FR-058, FR-059): bounded LRU at {@code maxSessionTickets},
 * expired entries discarded on lookup, keyed by (server name, port, ALPN), and {@code take} removing
 * what it returns so a ticket is offered at most once (RFC 8446 §C.4).
 * <p>
 * The clock is an injected {@link java.util.function.LongSupplier} with no default: the reactive
 * owner passes {@code reactor::currentTimeMillis}, which keeps this type reactor-free (ADR-016) and
 * keeps every expiry assertion here deterministic without an eventloop.
 */
public final class InMemoryQuicSessionCacheTest {

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long HOUR = 3_600_000L;
	private static final long T0 = 1_700_000_000_000L;

	private long now = T0;

	@Test
	public void takeReturnsNullForAnOriginNeverStored() {
		InMemoryQuicSessionCache cache = cache(256);

		assertNull(cache.take("example.com", 443, "h3"));
		assertEquals(0, cache.size());
	}

	@Test
	public void putThenTakeRoundTripsAndTakeRemoves() {
		InMemoryQuicSessionCache cache = cache(256);
		QuicSessionTicket ticket = ticket("example.com", "h3");

		cache.put("example.com", 443, "h3", ticket);
		assertEquals(1, cache.size());

		assertSame(ticket, cache.take("example.com", 443, "h3"));
		assertEquals(0, cache.size());
		assertNull(cache.take("example.com", 443, "h3"));
	}

	@Test
	public void entriesAreKeyedByServerNamePortAndAlpnTogether() {
		InMemoryQuicSessionCache cache = cache(256);
		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));

		assertNull(cache.take("other.example.com", 443, "h3"));
		assertNull(cache.take("example.com", 8443, "h3"));
		assertNull(cache.take("example.com", 443, "hq-interop"));
		assertEquals(1, cache.size());

		assertNotNull(cache.take("example.com", 443, "h3"));
	}

	@Test
	public void twoPortsOfTheSameHostAreTwoOrigins() {
		InMemoryQuicSessionCache cache = cache(256);

		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));
		cache.put("example.com", 8443, "h3", ticket("example.com", "h3"));

		assertEquals(2, cache.size());
		assertNotNull(cache.take("example.com", 443, "h3"));
		assertNotNull(cache.take("example.com", 8443, "h3"));
	}

	@Test
	public void theBoundIsEnforcedByEvictingTheLeastRecentlyUsedOrigin() {
		InMemoryQuicSessionCache cache = cache(2);

		cache.put("a.example.com", 443, "h3", ticket("a.example.com", "h3"));
		cache.put("b.example.com", 443, "h3", ticket("b.example.com", "h3"));
		cache.put("c.example.com", 443, "h3", ticket("c.example.com", "h3"));

		assertEquals(2, cache.size());
		assertNull(cache.take("a.example.com", 443, "h3"));
		assertNotNull(cache.take("b.example.com", 443, "h3"));
		assertNotNull(cache.take("c.example.com", 443, "h3"));
	}

	@Test
	public void aRefreshedOriginIsNotTheEldest() {
		InMemoryQuicSessionCache cache = cache(2);

		cache.put("a.example.com", 443, "h3", ticket("a.example.com", "h3"));
		cache.put("b.example.com", 443, "h3", ticket("b.example.com", "h3"));
		cache.put("a.example.com", 443, "h3", ticket("a.example.com", "h3"));
		cache.put("c.example.com", 443, "h3", ticket("c.example.com", "h3"));

		assertNull(cache.take("b.example.com", 443, "h3"));
		assertNotNull(cache.take("a.example.com", 443, "h3"));
		assertNotNull(cache.take("c.example.com", 443, "h3"));
	}

	@Test
	public void aLaterPutSupersedesTheTicketHeldForThatOrigin() {
		InMemoryQuicSessionCache cache = cache(256);
		QuicSessionTicket second = ticket("example.com", "h3");

		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));
		cache.put("example.com", 443, "h3", second);

		assertEquals(1, cache.size());
		assertSame(second, cache.take("example.com", 443, "h3"));
	}

	@Test
	public void anExpiredEntryIsDiscardedOnLookup() {
		InMemoryQuicSessionCache cache = cache(256);
		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));

		now = T0 + HOUR;

		assertNull(cache.take("example.com", 443, "h3"));
		assertEquals(0, cache.size());
	}

	@Test
	public void anEntryIsUsableUpToTheInstantBeforeExpiry() {
		InMemoryQuicSessionCache cache = cache(256);
		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));

		now = T0 + HOUR - 1;

		assertNotNull(cache.take("example.com", 443, "h3"));
	}

	@Test
	public void aZeroBoundMakesPutANoOp() {
		InMemoryQuicSessionCache cache = cache(0);

		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));

		assertEquals(0, cache.size());
		assertNull(cache.take("example.com", 443, "h3"));
	}

	@Test
	public void putRefusesATicketIssuedForAnotherOrigin() {
		InMemoryQuicSessionCache cache = cache(256);

		assertThrows(IllegalArgumentException.class,
			() -> cache.put("other.example.com", 443, "h3", ticket("example.com", "h3")));
		assertThrows(IllegalArgumentException.class,
			() -> cache.put("example.com", 443, "hq-interop", ticket("example.com", "h3")));

		assertEquals(0, cache.size());
	}

	@Test
	public void clearEmptiesTheStore() {
		InMemoryQuicSessionCache cache = cache(256);
		cache.put("a.example.com", 443, "h3", ticket("a.example.com", "h3"));
		cache.put("b.example.com", 443, "h3", ticket("b.example.com", "h3"));

		cache.clear();

		assertEquals(0, cache.size());
		assertNull(cache.take("a.example.com", 443, "h3"));
	}

	@Test
	public void createRefusesANegativeBoundAndANullClock() {
		assertThrows(IllegalArgumentException.class, () -> InMemoryQuicSessionCache.create(-1, () -> T0));
		assertThrows(NullPointerException.class, () -> InMemoryQuicSessionCache.create(256, null));
	}

	@Test
	public void toStringCarriesSizeAndBoundOnly() {
		InMemoryQuicSessionCache cache = cache(256);
		cache.put("example.com", 443, "h3", ticket("example.com", "h3"));

		assertEquals("InMemoryQuicSessionCache[size=1, max=256]", cache.toString());
		assertEquals(256, cache.maxSessionTickets());
	}

	private InMemoryQuicSessionCache cache(int maxSessionTickets) {
		return InMemoryQuicSessionCache.create(maxSessionTickets, () -> now);
	}

	private static QuicSessionTicket ticket(String serverName, String alpn) {
		return QuicSessionTicket.builder(serverName, alpn, TlsCipherSuite.TLS_AES_128_GCM_SHA256, secret(32))
			.withIssuedAt(T0)
			.withLifetime(HOUR)
			.withTicketAgeAdd(1)
			.withTransportParameters(remembered())
			.build();
	}
}
