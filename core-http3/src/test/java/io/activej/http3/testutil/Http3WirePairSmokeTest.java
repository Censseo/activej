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

package io.activej.http3.testutil;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.bytebuf.ByteBufs;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnection.Role;
import io.activej.quic.stream.QuicStream;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the harness itself before anything is built on it (T055): a real {@code QuicEndpoint} pair
 * over {@link StubDatagramNetwork} completes a TLS 1.3 handshake, a client-opened bidirectional stream
 * reaches the server's stream listener, and bytes round-trip in both directions with an explicit FIN.
 * <p>
 * Every later HTTP/3 test in this module rides on exactly this wiring, so a failure here means the
 * transport under those tests is broken rather than the protocol logic they assert.
 * <p>
 * No {@code EventloopRule}: {@link ManualEventloop} installs its own eventloop on a hand-driven clock
 * as the thread's current reactor, and a second eventloop on the system clock would put the transport's
 * timers on real time — the whole point of the manual one.
 */
public final class Http3WirePairSmokeTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private final List<QuicStream> acceptedStreams = new ArrayList<>();
	private final List<Promise<ByteBuf>> acceptedReads = new ArrayList<>();

	private ManualEventloop loop;
	private Http3WirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
		wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> {
				acceptedStreams.add(stream);
				// Attached inside the listener, which is where a peer-opened stream is delivered exactly
				// once and never queued — the same shape Http3Connection will use.
				acceptedReads.add(stream.reader().toCollector(ByteBufs.collector()));
			})
			.connect();
	}

	@After
	public void tearDown() {
		wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	@Test
	public void theHandshakeCompletesAndBothSidesHaveAStreamLayer() {
		assertEquals(Role.CLIENT, wire.clientConnection().role());
		assertEquals(Role.SERVER, wire.serverConnection().role());
		assertNotNull("the endpoint-level factory must have built a manager per connection", wire.clientStreams());
		assertNotNull(wire.serverStreams());
		assertNotNull("the handshake must have supplied the peer's transport parameters",
			wire.clientConnection().peerTransportParameters());
	}

	@Test
	public void aClientStreamReachesTheServerAndTheBytesRoundTrip() {
		byte[] request = pattern(4096, 1);
		QuicStream stream = wire.openNow(wire.clientStreams().openBidirectional());
		Promise<ByteBuf> response = stream.reader().toCollector(ByteBufs.collector());
		// streamTo writes every byte and then FINs the sending half.
		Promise<Void> written = ChannelSuppliers.ofValue(buf(request)).streamTo(stream.writer());

		wire.driveUntil(() ->
			written.isComplete() && !acceptedReads.isEmpty() && acceptedReads.get(0).isComplete());

		assertEquals("exactly once per peer-opened stream", 1, acceptedStreams.size());
		QuicStream accepted = acceptedStreams.get(0);
		assertEquals("RFC 9000 §2.1: the first client-initiated bidirectional stream is 0", 0, accepted.id());
		assertTrue(accepted.isBidirectional());
		assertArrayEquals("every byte, once, in order", request, drain(acceptedReads.get(0)));

		// And back the other way, over the same stream's still-open sending half.
		byte[] reply = pattern(2048, 2);
		Promise<Void> echoed = ChannelSuppliers.ofValue(buf(reply)).streamTo(accepted.writer());
		wire.driveUntil(() -> echoed.isComplete() && response.isComplete());

		assertArrayEquals(reply, drain(response));
	}

	// ---------------------------------------------------------------- helpers

	private static byte[] pattern(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + (i >>> 8) * 7 + seed);
		}
		return bytes;
	}

	private static ByteBuf buf(byte[] source) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, source.length));
		buf.put(source);
		return buf;
	}

	private static byte[] drain(Promise<ByteBuf> collected) {
		assertTrue("the read never completed", collected.isComplete());
		assertTrue(String.valueOf(collected.getException()), collected.isResult());
		ByteBuf buf = collected.getResult();
		byte[] bytes = buf.getArray();
		buf.recycle();
		return bytes;
	}
}
