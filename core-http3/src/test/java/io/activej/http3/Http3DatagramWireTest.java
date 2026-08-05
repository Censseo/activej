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

package io.activej.http3;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.quic.connection.QuicConnectionSettings;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * T120 and the half of T122 no stub transport can reach: negotiation and connection close, over two real
 * {@link Http3Connection}s on a real QUIC connection.
 * <p>
 * <b>T120, spec FR-083</b>: an HTTP/3 datagram sent before both peers' SETTINGS have been exchanged is
 * refused <b>locally</b> — the payload is recycled and nothing reaches the wire — and the same channel
 * becomes available once they have been, so the refusal is negotiation state rather than a switch that
 * is simply off.
 * <p>
 * <b>T122, contracts/h3-datagrams.md "Ownership Contract"</b>: closing the connection recycles every
 * queued payload exactly once. {@link Http3DatagramOwnershipTest} asserts the same rule at the channel;
 * this asserts that the connection actually reaches it, which is the part a stub cannot answer.
 * <p>
 * The peer whose SETTINGS never arrive is a bare {@code QuicStreamManager} — {@link Http3WirePair}'s
 * default server. It advertises {@code max_datagram_frame_size} like the real one, so the <i>only</i>
 * thing missing in the negative cases is the peer's {@code SETTINGS_H3_DATAGRAM}, which is exactly the
 * condition FR-083 names.
 */
public final class Http3DatagramWireTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final Http3Settings DATAGRAMS_ON = Http3Settings.builder()
		.withDatagramsEnabled(true)
		.build();

	private final List<Http3RequestStream> acceptedStreams = new ArrayList<>();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Connection clientH3;
	private @Nullable Http3Connection serverH3;
	private @Nullable Promise<HttpResponse> clientResponse;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		loop.close();
	}

	// ---------------------------------------------------------------- T120: before negotiation

	@Test
	public void aSendBeforeThePeerSettingsArriveIsRefusedWithThePayloadRecycled() {
		connect(false);
		assertFalse("the peer has sent no SETTINGS", clientH3.datagramsAvailable());

		Http3DatagramChannel channel = clientChannel();
		assertFalse(channel.isAvailable());
		assertEquals("an unavailable channel carries nothing, so no caller has to read isAvailable() first",
			0, channel.maxPayloadSize());

		int before = liveBuffers();
		Http3DatagramException refused =
			assertThrows(Http3DatagramException.class, () -> channel.send(payload(64)));

		assertEquals(Http3DatagramException.Reason.NOT_NEGOTIATED, refused.reason());
		assertEquals("the payload is recycled before the refusal is thrown", before, liveBuffers());
		assertEquals(1, channel.datagramsRefused());
		assertEquals(0, channel.datagramsSent());
		assertEquals("nothing reached the transport", 0, clientH3.datagramsSent());
	}

	@Test
	public void theRefusalNamesTheRuleAndNoPayloadByte() {
		connect(false);
		Http3DatagramChannel channel = clientChannel();

		Http3DatagramException refused =
			assertThrows(Http3DatagramException.class, () -> channel.send(payload(64)));

		// SI-6: sizes and rules, never a byte of what was refused.
		assertTrue(refused.getMessage(), refused.getMessage().contains("SETTINGS"));
	}

	@Test
	public void aRefusedSendLeavesTheConnectionUsable() {
		connect(false);
		Http3DatagramChannel channel = clientChannel();

		for (int i = 0; i < 8; i++) {
			assertThrows(Http3DatagramException.class, () -> channel.send(payload(64)));
		}

		assertEquals(8, channel.datagramsRefused());
		assertEquals("a local refusal is not a protocol error", Http3Connection.State.SETTINGS_SENT, clientH3.state());
	}

	// ---------------------------------------------------------------- T120: after negotiation

	@Test
	public void theSameChannelBecomesAvailableOnceBothSettingsHaveBeenExchanged() {
		connect(true);
		assertTrue(clientH3.datagramsAvailable());
		assertTrue(serverH3.datagramsAvailable());

		Http3DatagramChannel channel = clientChannel();
		assertTrue(channel.isAvailable());
		assertTrue("a negotiated channel reports what it will carry", channel.maxPayloadSize() > 0);

		int before = liveBuffers();
		send(channel, payload(64));
		wire.pump();

		assertEquals(1, channel.datagramsSent());
		assertEquals(1, clientH3.datagramsSent());
		assertEquals(0, channel.datagramsRefused());
		assertEquals("the payload left this layer and came back through the frame's fate", before, liveBuffers());
	}

	@Test
	public void aDatagramReachesTheChannelBoundToTheSameExchange() {
		connect(true);
		Http3DatagramChannel clientChannel = clientChannel();
		Http3DatagramChannel serverChannel = serverChannel();

		send(serverChannel, payload(4));
		wire.driveUntil(() -> clientChannel.queuedCount() == 1);

		ByteBuf received = clientChannel.poll();
		assertNotNull(received);
		assertEquals(4, received.readRemaining());
		received.recycle();
		assertEquals(1, clientH3.datagramsReceived());
	}

	// ---------------------------------------------------------------- T122: connection close

	@Test
	public void closingTheConnectionRecyclesEveryQueuedDatagramExactlyOnce() {
		connect(true);
		Http3DatagramChannel clientChannel = clientChannel();
		Http3DatagramChannel serverChannel = serverChannel();

		int queued = 6;
		for (int i = 0; i < queued; i++) {
			send(serverChannel, payload(32));
		}
		wire.driveUntil(() -> clientChannel.queuedCount() == queued);

		clientH3.close();
		loop.tickUntilQuiet();

		// The drain having run is the assertion here; that it recycled rather than dropped is ByteBufRule's,
		// which sees every buffer this class ever took and fails the class if one of them stayed out.
		assertEquals(0, clientChannel.queuedCount());
		assertNull(clientChannel.poll());
		assertFalse(clientChannel.isAvailable());
	}

	@Test
	public void anExchangeThatEndsDrainsItsQueueWithoutClosingTheConnection() {
		connect(true);
		Http3DatagramChannel clientChannel = clientChannel();
		Http3DatagramChannel serverChannel = serverChannel();

		for (int i = 0; i < 4; i++) {
			send(serverChannel, payload(32));
		}
		wire.driveUntil(() -> clientChannel.queuedCount() == 4);

		acceptedStreams.get(0).abort(Http3Errors.H3_REQUEST_CANCELLED, "the test is done with this exchange");
		wire.driveUntil(clientChannel::isClosed);

		assertEquals("FR-085: drained when the exchange ends", 0, clientChannel.queuedCount());
		assertEquals("one exchange ending is not the connection ending", Http3Connection.State.READY, clientH3.state());
	}

	@Test
	public void aSendOnAnEndedExchangeIsRefusedWithThePayloadRecycled() {
		connect(true);
		Http3DatagramChannel clientChannel = clientChannel();

		acceptedStreams.get(0).abort(Http3Errors.H3_REQUEST_CANCELLED, "the test is done with this exchange");
		wire.driveUntil(() -> !clientChannel.isAvailable());

		int before = liveBuffers();
		Http3DatagramException refused =
			assertThrows(Http3DatagramException.class, () -> clientChannel.send(payload(32)));

		assertEquals(Http3DatagramException.Reason.EXCHANGE_ENDED, refused.reason());
		assertEquals(before, liveBuffers());
	}

	// ---------------------------------------------------------------- harness

	/**
	 * One client {@link Http3Connection} with datagrams on, and a server that is either the same or —
	 * with {@code withServerH3} false — {@link Http3WirePair}'s bare {@code QuicStreamManager}, which
	 * never sends SETTINGS at all. Both sides advertise {@code max_datagram_frame_size} either way, so
	 * the transport half of the negotiation is never what a negative case is observing.
	 */
	private void connect(boolean withServerH3) {
		wire = new Http3WirePair(loop)
			.withServerSettings(quicSettings())
			.withClientSettings(quicSettings())
			.withClientHandlerFactory(connection -> {
				clientH3 = Http3Connection.builder(reactor(), connection)
					.withSettings(DATAGRAMS_ON)
					.build();
				return clientH3.startAndGetFrameHandler();
			});
		if (withServerH3) {
			wire.withServerHandlerFactory(connection -> {
				serverH3 = Http3Connection.builder(reactor(), connection)
					.withSettings(DATAGRAMS_ON)
					.withRequestStreamListener(acceptedStreams::add)
					.build();
				return serverH3.startAndGetFrameHandler();
			});
		}
		wire.connect();
	}

	private static QuicConnectionSettings quicSettings() {
		return QuicConnectionSettings.builder()
			.withMaxDatagramFrameSize(MemSize.bytes(QuicConnectionSettings.maxDatagramFrameSizeFor(
				QuicConnectionSettings.DEFAULT_MAX_DATAGRAM_SIZE.toInt())))
			.build();
	}

	/**
	 * Opens one request stream, issues its request and returns the channel that request carries — which is
	 * the only way to reach one (FR-084: no QUIC stream ID appears in this API).
	 */
	private Http3DatagramChannel clientChannel() {
		Promise<Http3RequestStream> opened = clientH3.openRequestStream();
		wire.driveUntil(opened::isComplete);
		Http3RequestStream stream = opened.getResult();
		HttpRequest request = HttpRequest.get("https://" + Http3TestTls.SERVER_NAME + "/datagrams").build();
		// Takes ownership of the request, and attaches the channel to it before it does.
		stream.sendRequest(request);
		// Awaited but never answered: an exchange with nobody reading it would leave the stream open past
		// the abort these tests end it with, and the channel's lifecycle is the stream's.
		clientResponse = stream.receiveResponse();
		wire.pump();
		Http3DatagramChannel channel = Http3Datagrams.of(request);
		assertNotNull("the client reaches the channel through the request it issued", channel);
		return channel;
	}

	/** The server's view of the same exchange, reached through the request the server received. */
	private Http3DatagramChannel serverChannel() {
		wire.driveUntil(() -> !acceptedStreams.isEmpty());
		Promise<HttpRequest> received = acceptedStreams.get(0).receiveRequest();
		wire.driveUntil(received::isComplete);
		Http3DatagramChannel channel = Http3Datagrams.of(received.getResult());
		assertNotNull("a servlet reaches the channel through the request it is serving", channel);
		return channel;
	}

	private static void send(Http3DatagramChannel channel, ByteBuf payload) {
		try {
			channel.send(payload);
		} catch (Http3DatagramException e) {
			throw new AssertionError("the send should have been carried", e);
		}
	}

	private static ByteBuf payload(int length) {
		ByteBuf buf = ByteBufPool.allocate(length);
		for (int i = 0; i < length; i++) {
			buf.put((byte) i);
		}
		return buf;
	}

	/** Pooled buffers allocated but not yet returned — the idiom {@link Http3DatagramOwnershipTest} uses. */
	private static int liveBuffers() {
		return ByteBufPool.getStats().getCreatedItems() - ByteBufPool.getStats().getPoolItems();
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
