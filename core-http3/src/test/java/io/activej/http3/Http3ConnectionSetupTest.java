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
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.frame.SettingsFrame;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T056 / FR-011, FR-012: what an {@code Http3Connection} puts on the wire before anything else.
 * <p>
 * One side is a real {@link Http3Connection}; the other is the bare {@code QuicStreamManager}
 * {@link Http3WirePair} builds by default, so the assertions are about the bytes that crossed the
 * connection rather than about two copies of the same encoder agreeing with each other.
 * <p>
 * No {@code EventloopRule}: {@link ManualEventloop} installs its own reactor on a hand-driven clock.
 */
public final class Http3ConnectionSetupTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final long SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0x01;
	private static final long SETTINGS_MAX_FIELD_SECTION_SIZE = 0x06;
	private static final long SETTINGS_QPACK_BLOCKED_STREAMS = 0x07;

	private final List<QuicStream> peerOpenedStreams = new ArrayList<>();
	private final ByteBufs received = new ByteBufs();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;
	private @Nullable Http3Connection serverH3;
	private @Nullable Http3Connection clientH3;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		received.recycle();
		loop.close();
	}

	@Test
	public void theLocalControlStreamCarriesTypeZeroThenSettings() throws Exception {
		Http3Settings settings = Http3Settings.create();
		connectWithServerH3(settings);

		int expectedLength = QuicVarInts.encodedLength(Http3StreamType.CONTROL.code()) +
							 Http3Frames.encodedLength(expectedSettings(settings));
		wire.driveUntil(() -> received.remainingBytes() >= expectedLength);

		assertEquals("exactly one peer-opened stream, and it is the control stream", 1, peerOpenedStreams.size());
		assertFalse("the control stream is unidirectional (RFC 9114 §6.2)", peerOpenedStreams.get(0).isBidirectional());

		ByteBuf buf = received.takeRemaining();
		try {
			assertEquals("the stream type varint comes first", Http3StreamType.CONTROL.code(), QuicVarInts.read(buf));
			Http3Frame frame = new Http3FrameReader(settings.maxControlFrameSize()).feed(buf);
			assertTrue("the first frame on the control stream is SETTINGS", frame instanceof SettingsFrame);

			Map<Long, Long> advertised = toMap((SettingsFrame) frame);
			assertEquals("exactly the three settings of FR-012", 3, advertised.size());
			assertEquals(Long.valueOf(0), advertised.get(SETTINGS_QPACK_MAX_TABLE_CAPACITY));
			assertEquals(Long.valueOf(0), advertised.get(SETTINGS_QPACK_BLOCKED_STREAMS));
			assertEquals(Long.valueOf(settings.maxFieldSectionSize()), advertised.get(SETTINGS_MAX_FIELD_SECTION_SIZE));
			assertFalse("nothing follows SETTINGS before a request", buf.canRead());
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void maxFieldSectionSizeIsTheConfiguredValue() throws Exception {
		Http3Settings settings = Http3Settings.builder()
			.withMaxFieldSectionSize(MemSize.kilobytes(7))
			.build();
		connectWithServerH3(settings);

		int expectedLength = QuicVarInts.encodedLength(Http3StreamType.CONTROL.code()) +
							 Http3Frames.encodedLength(expectedSettings(settings));
		wire.driveUntil(() -> received.remainingBytes() >= expectedLength);

		ByteBuf buf = received.takeRemaining();
		try {
			QuicVarInts.read(buf);
			SettingsFrame frame = (SettingsFrame) new Http3FrameReader(settings.maxControlFrameSize()).feed(buf);
			assertEquals(Long.valueOf(MemSize.kilobytes(7).toLong()), toMap(frame).get(SETTINGS_MAX_FIELD_SECTION_SIZE));
		} finally {
			buf.recycle();
		}
	}

	@Test
	public void settingsAreSentBeforeThePeerHasSaidAnything() {
		connectWithServerH3(Http3Settings.create());
		wire.driveUntil(() -> serverH3.state() == State.SETTINGS_SENT);

		assertEquals(State.SETTINGS_SENT, serverH3.state());
		assertNull("the peer's settings are absent until its control stream arrives", serverH3.peerSettings());
	}

	@Test
	public void twoHttp3ConnectionsCompleteTheHandshakeAndReachReady() {
		wire = new Http3WirePair(loop)
			.withServerHandlerFactory(connection -> {
				serverH3 = Http3Connection.create(reactor(), connection);
				return serverH3.streamManager();
			})
			.withClientHandlerFactory(connection -> {
				clientH3 = Http3Connection.create(reactor(), connection);
				return clientH3.streamManager();
			})
			.connect();

		wire.driveUntil(() -> serverH3.state() == State.READY && clientH3.state() == State.READY);

		assertEquals(State.READY, serverH3.state());
		assertEquals(State.READY, clientH3.state());
		assertNotNull(serverH3.peerSettings());
		assertNotNull(clientH3.peerSettings());
		assertEquals("the peer advertises the same field-section bound we do",
			Http3Settings.create().maxFieldSectionSize(), serverH3.peerMaxFieldSectionSize());
		assertEquals(0, serverH3.requestStreamCount());
	}

	// ---------------------------------------------------------------- helpers

	private void connectWithServerH3(Http3Settings settings) {
		wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> {
				peerOpenedStreams.add(stream);
				Http3TestBytes.collect(stream, received);
			})
			.withServerHandlerFactory(connection -> {
				serverH3 = Http3Connection.builder(reactor(), connection).withSettings(settings).build();
				serverH3.start();
				return serverH3.streamManager();
			})
			.connect();
	}

	private static SettingsFrame expectedSettings(Http3Settings settings) {
		return new SettingsFrame(
			new long[]{SETTINGS_QPACK_MAX_TABLE_CAPACITY, SETTINGS_MAX_FIELD_SECTION_SIZE, SETTINGS_QPACK_BLOCKED_STREAMS},
			new long[]{0, settings.maxFieldSectionSize(), 0});
	}

	private static Map<Long, Long> toMap(SettingsFrame frame) {
		Map<Long, Long> map = new HashMap<>();
		for (int i = 0; i < frame.identifiers.length; i++) {
			map.put(frame.identifiers[i], frame.values[i]);
		}
		return map;
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}
}
