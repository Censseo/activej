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

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The default {@link QuicSessionCache}: a bounded least-recently-used store of at most
 * {@code maxSessionTickets} tickets, one per origin (spec FR-058).
 * <p>
 * Everything the interface promises holds here — reactor-thread-only, non-blocking, no
 * {@code Promise}. Nothing survives the process, which is the deliberate default: a ticket that does
 * not outlive the client cannot be replayed after it (spec FR-060).
 * <p>
 * Expiry is evaluated <b>on lookup only</b>; there is no sweep, because a store bounded at a few
 * hundred entries does not need one and a timer here would drag a reactor into a package that has
 * none. The expired entry is removed when it is looked up.
 * <p>
 * <b>The clock is injected and has no default.</b> The reactive owner passes
 * {@code reactor::currentTimeMillis}, which honours {@code core-quic}'s "never read the clock
 * directly" guard rail while keeping this type free of any reactor dependency (ADR-016).
 * <p>
 * Not thread-safe, by construction: it is confined to the reactor that owns it.
 */
public final class InMemoryQuicSessionCache implements QuicSessionCache {
	private final int maxSessionTickets;
	private final LongSupplier currentTimeMillis;
	private final Map<Origin, QuicSessionTicket> tickets;

	private InMemoryQuicSessionCache(int maxSessionTickets, LongSupplier currentTimeMillis) {
		this.maxSessionTickets = maxSessionTickets;
		this.currentTimeMillis = currentTimeMillis;
		this.tickets = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Origin, QuicSessionTicket> eldest) {
				return size() > InMemoryQuicSessionCache.this.maxSessionTickets;
			}
		};
	}

	/**
	 * @param maxSessionTickets the LRU bound in entries; {@code 0} makes {@link #put} a no-op, which
	 *        is how a consumer turns client-side resumption off without a null store
	 * @param currentTimeMillis the clock expiry is judged against — {@code reactor::currentTimeMillis}
	 *        in production
	 * @throws IllegalArgumentException if {@code maxSessionTickets} is negative
	 */
	public static InMemoryQuicSessionCache create(int maxSessionTickets, LongSupplier currentTimeMillis) {
		Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
		if (maxSessionTickets < 0) {
			throw new IllegalArgumentException("maxSessionTickets (" + maxSessionTickets + ") must not be negative");
		}
		return new InMemoryQuicSessionCache(maxSessionTickets, currentTimeMillis);
	}

	@Override
	public @Nullable QuicSessionTicket take(String serverName, int port, String alpn) {
		QuicSessionTicket ticket = tickets.remove(new Origin(serverName, port, alpn));
		if (ticket == null || ticket.isExpiredAt(currentTimeMillis.getAsLong())) return null;
		return ticket;
	}

	/**
	 * @throws IllegalArgumentException if the ticket was not issued for this origin — a caller bug,
	 * deliberately distinct from every wire error, since offering a ticket for the wrong origin is a
	 * protocol violation the client would commit against itself (spec FR-047)
	 */
	@Override
	public void put(String serverName, int port, String alpn, QuicSessionTicket ticket) {
		Objects.requireNonNull(ticket, "ticket");
		if (!ticket.isFor(serverName, alpn)) {
			throw new IllegalArgumentException(
				"Ticket was issued for " + ticket.serverName() + "/" + ticket.alpn() +
				", not for " + serverName + "/" + alpn);
		}
		if (maxSessionTickets == 0) return;
		tickets.put(new Origin(serverName, port, alpn), ticket);
	}

	/** Entries currently held, expired ones included — they are dropped when looked up, not swept. */
	public int size() {
		return tickets.size();
	}

	/** The configured LRU bound in entries. */
	public int maxSessionTickets() {
		return maxSessionTickets;
	}

	/** Discards every stored ticket. */
	public void clear() {
		tickets.clear();
	}

	@Override
	public String toString() {
		return "InMemoryQuicSessionCache[size=" + size() + ", max=" + maxSessionTickets + ']';
	}

	private record Origin(String serverName, int port, String alpn) {
	}
}
