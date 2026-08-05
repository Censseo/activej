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

package io.activej.quic.connection.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.exception.MalformedDataException;
import io.activej.quic.codec.QuicPackets;
import io.activej.quic.connection.CoalescedPackets;
import io.activej.quic.connection.CoalescedPackets.Kind;
import io.activej.quic.connection.CoalescedPackets.ProtectedPacket;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.quic.tls.InMemoryQuicSessionCache;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTicketKeys;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The 0-RTT half of {@link QuicWirePair}'s fixture: a first connection that earns a session ticket,
 * a second that offers it, and a classifier that says what actually went on the wire.
 * <p>
 * Kept separate from {@code QuicWirePair} because it is a <i>protocol scenario</i> rather than a wire:
 * two connections, one clock and one ticket key ring between them.
 * <p>
 * <b>Ownership (DI-1)</b>: {@link #classify} splits its datagram into retained slices and recycles
 * every one of them before returning; the datagram itself is neither consumed nor modified, exactly as
 * {@link CoalescedPackets#split} leaves it.
 */
public final class ZeroRttWire {
	/** The ALPN every connection in this module negotiates. */
	public static final String ALPN = "h3";

	/** The origin key a ticket earned over {@link QuicWirePair} is filed under. */
	public static final String SERVER_NAME = "localhost";

	public static final int SERVER_PORT = QuicWirePair.SERVER_ADDRESS.getPort();

	private ZeroRttWire() {}

	/** A key ring seeded so a ticket sealed by one connection can be opened by the next. */
	public static QuicTicketKeys ticketKeys() {
		return QuicTicketKeys.create(new SecureRandom(), Duration.ofHours(6).toMillis(),
			Duration.ofHours(1).toMillis(), System.currentTimeMillis());
	}

	/**
	 * Runs one <b>full</b> handshake over a throwaway {@link QuicWirePair} and returns the session
	 * ticket the server issued, or {@code null} if it issued none.
	 * <p>
	 * The pair is closed before returning, so the caller inherits no buffers.
	 */
	public static QuicSessionTicket earnTicket(QuicTicketKeys keys, QuicConnectionSettings settings)
		throws MalformedDataException {
		InMemoryQuicSessionCache cache = InMemoryQuicSessionCache.create(8, System::currentTimeMillis);
		try (QuicWirePair pair = new QuicWirePair()) {
			pair.withServerTlsConfig(builder -> builder.withTicketKeys(keys).withEarlyDataEnabled(true))
				.withClientTlsConfig(builder -> builder.withSessionCache(cache, SERVER_PORT));
			pair.handshake(settings);
		}
		return cache.take(SERVER_NAME, SERVER_PORT, ALPN);
	}

	/**
	 * How many packets of each kind {@code datagram} carries. Reads only unprotected fields, so it sees
	 * exactly what a peer's dispatcher would before any key is applied — which is what makes it a
	 * usable proof that a 0-RTT packet (long header, type {@code 0x1}) really was sent.
	 */
	public static Map<Kind, Integer> classify(ByteBuf datagram, int shortHeaderDcidLength)
		throws MalformedDataException {
		Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
		List<ProtectedPacket> packets =
			CoalescedPackets.split(datagram, shortHeaderDcidLength, QuicPackets.SUPPORTED_VERSION);
		try {
			for (ProtectedPacket packet : packets) {
				counts.merge(packet.kind(), 1, Integer::sum);
			}
		} finally {
			for (ProtectedPacket packet : packets) {
				packet.bytes().recycle();
			}
		}
		return counts;
	}

	/**
	 * Delivers everything the client has queued to the server, counting the 0-RTT packets that crossed.
	 * A datagram that cannot even be split is counted as carrying none rather than failing the caller —
	 * the assertion a test writes here is about 0-RTT, not about the splitter.
	 */
	public static int deliverToServerCountingZeroRtt(QuicWirePair pair) throws MalformedDataException {
		int zeroRtt = 0;
		ByteBuf datagram;
		while ((datagram = pair.clientWire().poll()) != null) {
			zeroRtt += classify(datagram, pair.server().localConnectionId().length())
				.getOrDefault(Kind.ZERO_RTT, 0);
			pair.server().onDatagram(datagram);
		}
		return zeroRtt;
	}

	/**
	 * The server's counterpart, which exists so a test can prove the <b>negative</b> of spec FR-052:
	 * a server never sends a 0-RTT packet, whatever it received in one.
	 */
	public static int deliverToClientCountingZeroRtt(QuicWirePair pair) throws MalformedDataException {
		int zeroRtt = 0;
		ByteBuf datagram;
		while ((datagram = pair.serverWire().poll()) != null) {
			zeroRtt += classify(datagram, pair.client().localConnectionId().length())
				.getOrDefault(Kind.ZERO_RTT, 0);
			pair.client().onDatagram(datagram);
		}
		return zeroRtt;
	}
}
