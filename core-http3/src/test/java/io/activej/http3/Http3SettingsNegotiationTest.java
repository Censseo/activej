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
import io.activej.common.MemSize;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * T058 / FR-016: what the peer's SETTINGS frame may and may not contain.
 * <p>
 * Every rejection here is a <b>connection</b> error, so each case needs its own connection — hence
 * the per-case {@link #connect} rather than one wire built in {@code @Before}. They share the
 * eventloop, and each {@link Http3WirePair} owns a separate fabric, so there is no address clash.
 */
public final class Http3SettingsNegotiationTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The RFC 9114 §7.2.4.1 HTTP/2 SETTINGS identifiers, reserved in HTTP/3. */
	private static final long[] RESERVED_IDENTIFIERS = {0x02, 0x03, 0x04, 0x05};

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
	public void aDuplicatedIdentifierIsASettingsError() {
		Peer peer = connect(Http3Settings.create());
		peer.sendOnControlStream(settingsFrame(new long[]{0x06, 0x06}, new long[]{1024, 2048}));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.H3_SETTINGS_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void everyReservedIdentifierIsASettingsError() {
		for (long reserved : RESERVED_IDENTIFIERS) {
			Peer peer = connect(Http3Settings.create());
			peer.sendOnControlStream(settingsFrame(new long[]{reserved}, new long[]{0}));

			peer.driveUntilClosed();
			assertEquals("reserved SETTINGS identifier 0x" + Long.toHexString(reserved),
				Http3Errors.H3_SETTINGS_ERROR, peer.h3.closedWithErrorCode());
		}
	}

	@Test
	public void unknownNonReservedIdentifiersAreIgnored() {
		Peer peer = connect(Http3Settings.create());
		// 0x21 and 0x1f * 2 + 0x21 are GREASE values of RFC 9114 §7.2.4.1's reserved-for-grease form.
		peer.sendOnControlStream(settingsFrame(
			new long[]{0x21, 0x5f, 0x06},
			new long[]{Long.MAX_VALUE >> 2, 1, 4096}));

		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		assertEquals(State.READY, peer.h3.state());
		assertNotNull(peer.h3.peerSettings());
		assertEquals("the identifier we do understand still applies", 4096, peer.h3.peerMaxFieldSectionSize());
	}

	@Test
	public void aControlFrameOverTheBoundIsExcessiveLoad() {
		Http3Settings tight = Http3Settings.builder()
			.withMaxControlFrameSize(MemSize.bytes(32))
			.build();
		Peer peer = connect(tight);

		long[] identifiers = new long[24];
		long[] values = new long[identifiers.length];
		for (int i = 0; i < identifiers.length; i++) {
			identifiers[i] = 0x1fL * (i + 1) + 0x21;
			values[i] = i;
		}
		peer.sendOnControlStream(settingsFrame(identifiers, values));

		peer.driveUntilClosed();
		assertEquals(Http3Errors.H3_EXCESSIVE_LOAD, peer.h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- helpers

	private Peer connect(Http3Settings settings) {
		Http3Connection[] captured = new Http3Connection[1];
		Http3WirePair wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> Http3TestBytes.collect(stream, received))
			.withServerHandlerFactory(connection -> {
				captured[0] = Http3Connection.builder(reactor(), connection).withSettings(settings).build();
				captured[0].start();
				return captured[0].streamManager();
			})
			.connect();
		wires.add(wire);
		return new Peer(wire, captured[0]);
	}

	private record Peer(Http3WirePair wire, Http3Connection h3) {
		/** Opens the peer's control stream and writes {@code frame} straight after its type varint. */
		void sendOnControlStream(ByteBuf frame) {
			QuicStream control = wire.openNow(wire.clientStreams().openUnidirectional());
			control.writer().accept(concat(streamHeader(Http3StreamType.CONTROL.code()), frame));
		}

		void driveUntilClosed() {
			wire.driveUntil(() -> h3.state() == State.CLOSED);
		}
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
