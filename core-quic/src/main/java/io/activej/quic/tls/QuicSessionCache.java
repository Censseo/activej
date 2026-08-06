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

/**
 * The client's store of {@link QuicSessionTicket}s, keyed by origin — server name, port and ALPN
 * (spec FR-058, FR-059). {@link InMemoryQuicSessionCache} is the default implementation; a consumer
 * supplies their own to share one store between several clients in a process, or to persist it.
 * <p>
 * <b>Reactor-thread-only and non-blocking.</b> No method returns a {@code Promise} and no method may
 * block, because this sits on the connection-establishment critical path: a resumption attempt reads
 * the store before the ClientHello can be written. A consumer whose backing store is a disk or a
 * network service must hand off through their own mechanism and answer {@code take} from memory —
 * this interface deliberately offers no place to await anything.
 * <p>
 * {@link #take} <b>removes</b> the ticket it returns. A ticket is offered at most once
 * (RFC 8446 §C.4): re-offering one is what a replay looks like, and the store is the only place that
 * can cheaply prevent it on the client side.
 * <p>
 * <b>Persisting tickets extends the replay window the consumer is accepting.</b> A ticket that
 * survives a process restart is a ticket an attacker who obtains the store can replay for the whole
 * of its remaining lifetime; the in-memory default deliberately loses everything on exit
 * (spec FR-060). A persisting implementation is making that trade knowingly.
 * <p>
 * Implementations must keep ticket contents out of logs, exception messages, {@code toString} and
 * JMX attributes — a ticket carries resumption secret material (spec FR-050, SI-6).
 */
public interface QuicSessionCache {

	/**
	 * Removes and returns a usable ticket for this origin, or {@code null} when there is none — no
	 * entry, or an entry that has expired, which is discarded rather than returned.
	 * <p>
	 * {@code null} always means "perform a full handshake", never an error.
	 */
	@Nullable QuicSessionTicket take(String serverName, int port, String alpn);

	/**
	 * Stores a ticket for this origin, superseding any ticket already held for it. Implementations
	 * are bounded (spec FR-058) and may drop this ticket or an older one rather than grow.
	 */
	void put(String serverName, int port, String alpn, QuicSessionTicket ticket);
}
