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
import io.activej.bytebuf.ByteBufs;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.frame.SettingsFrame;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.tls.QuicSessionTicket;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.quic.tls.TlsCipherSuite;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T063 / FR-062, FR-063, RFC 9114 §7.2.4.2: the HTTP/3 SETTINGS a session ticket remembers.
 * <p>
 * The connection under test is the <b>client</b> — it is the side that remembers and obeys — so the
 * pair is built with {@link Http3WirePair#withClientHandlerFactory} and the raw peer on the other end
 * plays the server's control stream by hand.
 * <p>
 * Every rejection here is a connection error, so each case dials its own connection, as
 * {@code Http3SettingsNegotiationTest} does. The wire-level assertion that no 0-RTT packet leaves
 * without remembered SETTINGS belongs to T067, not to this file.
 */
public final class Http3RememberedSettingsTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long MAX_FIELD_SECTION_SIZE = SettingsFrame.MAX_FIELD_SECTION_SIZE;
	private static final long MAX_CONTROL_FRAME_SIZE = Http3Settings.create().maxControlFrameSize();

	private final List<Http3WirePair> wires = new ArrayList<>();
	private final ByteBufs received = new ByteBufs();

	private ManualEventloop loop;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		for (Http3WirePair wire : wires) {
			wire.close();
		}
		loop.tickUntilQuiet();
		received.recycle();
		loop.close();
	}

	@Test
	public void aRememberedValueIsObeyedUntilTheServersOwnSettingsArrive() {
		Peer peer = connect(settings(4096));

		assertEquals("the remembered value applies before the server has said anything",
			4096, peer.h3.peerMaxFieldSectionSize());

		peer.sendOnControlStream(settingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{8192}));
		peer.driveUntilReady();

		assertEquals("the server's own value replaces it the moment it lands",
			8192, peer.h3.peerMaxFieldSectionSize());
	}

	@Test
	public void aServerReducingARelieduponValueIsASettingsError() {
		Peer peer = connect(settings(4096));

		peer.sendOnControlStream(settingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{1024}));
		peer.driveUntilClosed();

		assertEquals(State.CLOSED, peer.h3.state());
		assertEquals(Http3Errors.H3_SETTINGS_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void aServerRaisingARelieduponValueIsAccepted() {
		Peer peer = connect(settings(4096));

		peer.sendOnControlStream(settingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{8192}));
		peer.driveUntilReady();

		assertEquals(State.READY, peer.h3.state());
	}

	@Test
	public void aServerOmittingARelieduponValueIsAccepted() {
		Peer peer = connect(settings(4096));

		// An omitted 0x06 is unlimited (RFC 9114 §7.2.4.1), which is a raise, not a reduction. 0x21 is a
		// GREASE identifier, so the frame is a real SETTINGS frame that simply does not name 0x06.
		peer.sendOnControlStream(settingsFrame(new long[]{0x21}, new long[]{0}));
		peer.driveUntilReady();

		assertEquals(State.READY, peer.h3.state());
		assertEquals(Long.MAX_VALUE, peer.h3.peerMaxFieldSectionSize());
	}

	@Test
	public void withoutRememberedSettingsALowValueIsNotAnError() {
		Peer peer = connect(null);

		peer.sendOnControlStream(settingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{1}));
		peer.driveUntilReady();

		assertEquals("phase-1 behaviour is untouched with nothing remembered", State.READY, peer.h3.state());
		assertEquals(1, peer.h3.peerMaxFieldSectionSize());
	}

	@Test
	public void aConnectionWithoutRememberedSettingsPermitsNoEarlyData() {
		Peer without = connect(null);
		assertFalse(without.h3.permitsEarlyData());
		assertNull(without.h3.rememberedSettings());

		Peer with = connect(settings(4096));
		assertTrue(with.h3.permitsEarlyData());
		assertNotNull(with.h3.rememberedSettings());
	}

	@Test
	public void aTicketCarryingNoRememberedSettingsYieldsNoEarlyData() {
		QuicSessionTicket bare = ticketBuilder().build();
		assertEquals(0, bare.applicationSettings().length);
		assertNull("no remembered SETTINGS, no early data (FR-062)",
			Http3RememberedSettings.of(bare, MAX_CONTROL_FRAME_SIZE));

		QuicSessionTicket carrying = ticketBuilder()
			.withApplicationSettings(Http3RememberedSettings.encode(settings(4096)))
			.build();
		SettingsFrame remembered = Http3RememberedSettings.of(carrying, MAX_CONTROL_FRAME_SIZE);
		assertNotNull(remembered);
		assertEquals(4096, Http3RememberedSettings.valueOf(remembered, MAX_FIELD_SECTION_SIZE, -1));
	}

	@Test
	public void theRememberedSettingsBlobRoundTrips() {
		SettingsFrame original = new SettingsFrame(
			new long[]{MAX_FIELD_SECTION_SIZE, 0x21},
			new long[]{4096, 7});
		byte[] blob = Http3RememberedSettings.encode(original);

		SettingsFrame decoded = Http3RememberedSettings.decode(blob, MAX_CONTROL_FRAME_SIZE);
		assertNotNull(decoded);
		assertArrayEquals(original.identifiers, decoded.identifiers);
		assertArrayEquals(original.values, decoded.values);
		assertEquals(original, decoded);

		assertNull("empty", Http3RememberedSettings.decode(new byte[0], MAX_CONTROL_FRAME_SIZE));
		assertNull("truncated", Http3RememberedSettings.decode(
			Arrays.copyOf(blob, blob.length - 1), MAX_CONTROL_FRAME_SIZE));
		assertNull("oversized against the bound", Http3RememberedSettings.decode(blob, 1));
		assertNull("trailing garbage", Http3RememberedSettings.decode(
			Arrays.copyOf(blob, blob.length + 3), MAX_CONTROL_FRAME_SIZE));
		assertNull("a GOAWAY frame is not SETTINGS", Http3RememberedSettings.decode(
			bytesOf(Http3TestBytes.goAwayFrame(0)), MAX_CONTROL_FRAME_SIZE));
		assertNull("a DATA frame is not SETTINGS, and its payload must not leak", Http3RememberedSettings.decode(
			bytesOf(Http3TestBytes.dataFrame(new byte[]{1, 2, 3, 4})), MAX_CONTROL_FRAME_SIZE));
		assertNull("a reserved HTTP/2 frame type", Http3RememberedSettings.decode(
			bytesOf(Http3TestBytes.frame(0x02, new byte[]{0})), MAX_CONTROL_FRAME_SIZE));
	}

	@Test
	public void discardingRememberedSettingsDisablesTheNonReductionCheck() {
		Peer peer = connect(settings(4096));
		assertTrue(peer.h3.permitsEarlyData());

		// The Phase 6 seam (T093-T096): early data was rejected, so nothing in it was relied upon.
		peer.h3.discardRememberedSettings();
		assertFalse(peer.h3.permitsEarlyData());

		peer.sendOnControlStream(settingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{1024}));
		peer.driveUntilReady();

		assertEquals(State.READY, peer.h3.state());
		assertEquals(1024, peer.h3.peerMaxFieldSectionSize());
	}

	// ---------------------------------------------------------------- helpers

	private static SettingsFrame settings(long maxFieldSectionSize) {
		return new SettingsFrame(new long[]{MAX_FIELD_SECTION_SIZE}, new long[]{maxFieldSectionSize});
	}

	private static QuicSessionTicket.Builder ticketBuilder() {
		return QuicSessionTicket
			.builder("localhost", "h3", TlsCipherSuite.TLS_AES_128_GCM_SHA256,
				"not-a-real-secret".getBytes(StandardCharsets.UTF_8))
			.withIssuedAt(1_000)
			.withLifetime(60_000)
			.withTicketAgeAdd(0)
			.withTransportParameters(QuicTransportParameters.defaults(new byte[]{1, 2, 3, 4}));
	}

	private static byte[] bytesOf(ByteBuf buf) {
		try {
			return buf.getArray();
		} finally {
			buf.recycle();
		}
	}

	private Peer connect(@Nullable SettingsFrame remembered) {
		Http3Connection[] captured = new Http3Connection[1];
		Http3WirePair wire = new Http3WirePair(loop)
			.withServerStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withClientHandlerFactory(connection -> {
				Http3Connection.Builder builder = Http3Connection.builder(reactor(), connection);
				if (remembered != null) builder.withRememberedSettings(remembered);
				captured[0] = builder.build();
				captured[0].start();
				return captured[0].streamManager();
			})
			.connect();
		wires.add(wire);
		return new Peer(wire, captured[0]);
	}

	private record Peer(Http3WirePair wire, Http3Connection h3) {
		/** Opens the raw peer's control stream and writes {@code frame} straight after its type varint. */
		void sendOnControlStream(ByteBuf frame) {
			QuicStream control = wire.openNow(wire.serverStreams().openUnidirectional());
			control.writer().accept(concat(streamHeader(Http3StreamType.CONTROL.code()), frame));
		}

		void driveUntilReady() {
			wire.driveUntil(() -> h3.state() == State.READY || h3.state() == State.CLOSED);
		}

		void driveUntilClosed() {
			wire.driveUntil(() -> h3.state() == State.CLOSED);
		}
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
