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
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.MalformedDataException;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpRequest;
import io.activej.http.HttpResponse;
import io.activej.http3.frame.Http3Frame;
import io.activej.http3.frame.Http3FrameReader;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.frame.SettingsFrame;
import io.activej.http3.testutil.Http3ClientFixture;
import io.activej.http3.testutil.Http3TestBytes;
import io.activej.http3.testutil.Http3TestTls;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.http3.testutil.StubDnsClient;
import io.activej.promise.Promise;
import io.activej.quic.codec.QuicVarInts;
import io.activej.quic.connection.QuicConnection.TlsEngineFactory;
import io.activej.quic.stream.QuicStream;
import io.activej.quic.tls.QuicTransportParameters;
import io.activej.quic.tls.TlsEngine;
import io.activej.quic.tls.TlsEngineResult;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T005 + T006 / <b>SC-011</b>: "with all three capabilities disabled, the full phase-1 test suite passes
 * unchanged and the SETTINGS this endpoint sends are byte-for-byte what phase 1 sent."
 * <p>
 * This class is the <b>characterization test</b> for that criterion. It was written and observed green
 * against completely unmodified feature-005 code, <i>before</i> the QPACK dynamic table, 0-RTT and
 * HTTP/3 datagrams existed — so every literal below is a record of what the code did on
 * 2026-08-04, not a restatement of what it was configured to do. Nothing here imports a settings
 * constant that feature 006 changes: the identifiers come from {@link SettingsFrame} (the RFC 9204 /
 * RFC 9114 numbers, which survive) and the values are literals.
 * <p>
 * <b>Re-run this after every settings, transport-parameter or handshake change in phases 2 through 4.</b>
 * It is a net, not a milestone.
 *
 * <h2>Which assertions are load-bearing today, and which are not</h2>
 * Two of the four SC-011 "absence" claims are <b>vacuously true</b> against phase-1 code, and are
 * written down anyway so they are live the moment the capability lands. Reading a green run here as
 * evidence for either of them <i>today</i> would be a mistake:
 * <ul>
 *   <li><b>LIVE</b> — no QPACK encoder/decoder stream is opened by a real {@code Http3Server} or a real
 *       {@code Http3Client}: exactly one peer-observed unidirectional stream, and its first varint is
 *       {@link Http3StreamType#CONTROL}. ({@code Http3QpackStreamTest.weOpenNoQpackStreamOfOurOwn}
 *       proves the same for a bare {@code Http3Connection}; this proves it for the shipped components.)</li>
 *   <li><b>LIVE</b> — no {@code SETTINGS_H3_DATAGRAM} (0x33): the SETTINGS identifier <i>set</i> on the
 *       control stream is exactly {@code {0x01, 0x06, 0x07}}, so any new identifier fails here, not
 *       merely {@code 0x33}.</li>
 *   <li><b>LIVE</b> — no {@code max_datagram_frame_size} (0x20) in the parameters the <b>client</b>
 *       advertises: the recording {@link TlsEngineFactory} below sees this endpoint's own local
 *       {@link QuicTransportParameters}, so the assertion is over bytes this side would emit.</li>
 *   <li><b>WEAK / vacuous today</b> — no {@code max_datagram_frame_size} in the parameters the
 *       <b>server</b> advertises. {@code Http3Server} exposes no TLS-factory seam, so the only
 *       observation available is the record the <i>client</i> parsed — and
 *       {@code QuicTransportParameters.read} <b>skips unknown identifiers</b>, so a {@code 0x20} the
 *       server sent could never reappear in a re-encode of it. This assertion only becomes
 *       load-bearing once {@code QuicTransportParameters} carries the field, which the datagram slice
 *       adds. Until then it proves that <i>nothing known</i> changed, and no more.</li>
 *   <li><b>WEAK / vacuous today</b> — no session ticket is issued. Counted as post-handshake CRYPTO
 *       bytes arriving at the client, which is what a {@code NewSessionTicket} would be and the only
 *       post-handshake CRYPTO message this stack can emit. Phase-1 {@code TlsServerEngine} has no
 *       ticket-issuing code path at all (it only tolerates and discards an inbound one, FR-015), so
 *       the count is zero for want of a sender rather than because a flag is off. It becomes
 *       load-bearing when {@code zeroRttEnabled=false} gates real issuance. {@code handshakeComplete}
 *       is asserted alongside so a zero count cannot pass because the handshake never finished.</li>
 * </ul>
 * That honesty is the point: a vacuous pass documented as vacuous is coverage of an intent; a vacuous
 * pass mistaken for a real one is worse than no test.
 * <p>
 * No {@code EventloopRule} — {@link ManualEventloop} installs its own reactor on a hand-driven clock,
 * per the module convention.
 */
public final class Http3DefaultsUnchangedTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/**
	 * The whole of what a default-configured endpoint writes on its control stream before anything
	 * else, byte for byte:
	 * <pre>
	 * 00                 stream type varint — Http3StreamType.CONTROL
	 * 04                 SETTINGS frame type
	 * 09                 payload length varint (9)
	 * 01 00              SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0
	 * 06 80 01 00 00     SETTINGS_MAX_FIELD_SECTION_SIZE   = 65536, as a 4-byte QUIC varint
	 * 07 00              SETTINGS_QPACK_BLOCKED_STREAMS    = 0
	 * </pre>
	 * A prefix assertion, not an equality one: a GOAWAY may legitimately follow on the same stream.
	 */
	private static final byte[] PHASE_ONE_CONTROL_STREAM_PREFIX = {
		0x00,
		0x04, 0x09,
		0x01, 0x00,
		0x06, (byte) 0x80, 0x01, 0x00, 0x00,
		0x07, 0x00};

	/** RFC 9297 {@code SETTINGS_H3_DATAGRAM}; must not appear while datagrams are off. */
	private static final long SETTINGS_H3_DATAGRAM = 0x33;

	/** RFC 9221 {@code max_datagram_frame_size}; must not appear while datagrams are off. */
	private static final long MAX_DATAGRAM_FRAME_SIZE = 0x20;

	/**
	 * The RFC 9000 §18.2 identifiers a phase-1 client puts on the wire. Every varint parameter is
	 * always encoded, even at zero; the four byte-array parameters appear only when present, and a
	 * client has only {@code initial_source_connection_id} (0x0f).
	 */
	private static final Set<Long> PHASE_ONE_CLIENT_TRANSPORT_PARAMETER_IDS = Set.of(
		0x01L,  // max_idle_timeout
		0x03L,  // max_udp_payload_size
		0x04L,  // initial_max_data
		0x05L,  // initial_max_stream_data_bidi_local
		0x06L,  // initial_max_stream_data_bidi_remote
		0x07L,  // initial_max_stream_data_uni
		0x08L,  // initial_max_streams_bidi
		0x09L,  // initial_max_streams_uni
		0x0aL,  // ack_delay_exponent
		0x0bL,  // max_ack_delay
		0x0cL,  // disable_active_migration
		0x0eL,  // active_connection_id_limit
		0x0fL); // initial_source_connection_id

	/** The same, plus the {@code original_destination_connection_id} (0x00) only a server sends. */
	private static final Set<Long> PHASE_ONE_SERVER_TRANSPORT_PARAMETER_IDS = union(
		PHASE_ONE_CLIENT_TRANSPORT_PARAMETER_IDS, 0x00L);

	private static final AsyncServlet SERVLET = request -> HttpResponse.ok200().toPromise();

	/** Every unidirectional stream the peer opened toward us — the QPACK-stream claim reads this. */
	private final List<QuicStream> peerUniStreams = new ArrayList<>();

	/** Everything the peer opened, of either direction. */
	private final List<QuicStream> peerStreams = new ArrayList<>();

	/** Bytes of the peer's unidirectional streams; the control stream is the only one there is. */
	private final ByteBufs uniBytes = new ByteBufs();

	/** Bytes of everything else, drained so nothing sits in a reassembler at teardown. */
	private final ByteBufs otherBytes = new ByteBufs();

	private final StubDnsClient dns = new StubDnsClient();

	private ManualEventloop loop;
	private @Nullable Http3WirePair wire;

	@Before
	public void setUp() {
		loop = new ManualEventloop();
	}

	@After
	public void tearDown() {
		if (wire != null) wire.close();
		loop.tickUntilQuiet();
		uniBytes.recycle();
		otherBytes.recycle();
		loop.close();
	}

	// ---------------------------------------------------------------- T005: the SETTINGS bytes

	@Test
	public void theServerEmitsExactlyThePhaseOneSettingsBytes() {
		connectToRealServer();
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);

		assertArrayEquals("the server's control-stream preamble is byte-for-byte phase 1 (SC-011)",
			PHASE_ONE_CONTROL_STREAM_PREFIX, takeUniPrefix());
	}

	@Test
	public void theClientEmitsExactlyThePhaseOneSettingsBytes() {
		Http3Client client = connectWithRealClient(Http3TestTls::clientEngineFactory);
		issueOneRequest(client);
		// Driven on the byte count, never on the promise: the peer is a bare QuicStreamManager that will
		// never answer, so the request resolves only when close() fails it.
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);

		assertArrayEquals("the client's control-stream preamble is byte-for-byte phase 1 (SC-011)",
			PHASE_ONE_CONTROL_STREAM_PREFIX, takeUniPrefix());
	}

	// ---------------------------------------------------------------- T005: the transport parameters

	@Test
	public void theServerAdvertisesExactlyThePhaseOneTransportParameters() {
		connectToRealServer();

		QuicTransportParameters advertised = wire.clientConnection().peerTransportParameters();
		assertNotNull("the handshake must have supplied the server's parameters", advertised);
		assertPhaseOneTransportParameterValues(advertised);
		assertNotNull("a server sends original_destination_connection_id (RFC 9000 §18.2)",
			advertised.originalDestinationConnectionId());
	}

	@Test
	public void theClientAdvertisesExactlyThePhaseOneTransportParameters() throws Exception {
		QuicTransportParameters[] local = new QuicTransportParameters[1];
		Http3Client client = connectWithRealClient(recordingLocalParameters(local));
		issueOneRequest(client);
		wire.driveUntil(() -> local[0] != null);

		assertNotNull("the connection must have built its local parameters", local[0]);
		assertPhaseOneTransportParameterValues(local[0]);
		assertNull("a client sends no original_destination_connection_id",
			local[0].originalDestinationConnectionId());
		assertEquals("the exact phase-1 parameter set — a stray 0x20 would break this",
			PHASE_ONE_CLIENT_TRANSPORT_PARAMETER_IDS, transportParameterIdentifiers(local[0]));
	}

	// ---------------------------------------------------------------- T006: no QPACK stream

	@Test
	public void theServerOpensNoQpackStream() throws Exception {
		connectToRealServer();
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);
		// Long enough for a lazily-opened stream to have shown up if there were one.
		wire.advance(200);

		assertOnlyTheControlStreamWasOpened();
	}

	@Test
	public void theClientOpensNoQpackStream() throws Exception {
		Http3Client client = connectWithRealClient(Http3TestTls::clientEngineFactory);
		issueOneRequest(client);
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);
		wire.advance(200);

		assertOnlyTheControlStreamWasOpened();
		assertEquals("the request stream, and nothing else, beside the control stream",
			2, peerStreams.size());
	}

	// ---------------------------------------------------------------- T006: no SETTINGS_H3_DATAGRAM

	@Test
	public void theServerSendsNoH3DatagramSetting() throws Exception {
		connectToRealServer();
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);

		assertPhaseOneSettingsIdentifiers(decodeSettings());
	}

	@Test
	public void theClientSendsNoH3DatagramSetting() throws Exception {
		Http3Client client = connectWithRealClient(Http3TestTls::clientEngineFactory);
		issueOneRequest(client);
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);

		assertPhaseOneSettingsIdentifiers(decodeSettings());
	}

	// ---------------------------------------------------------------- T006: no max_datagram_frame_size

	/**
	 * <b>Weak today, deliberately.</b> See the class Javadoc: {@code QuicTransportParameters.read} skips
	 * unknown identifiers, so this can never observe a {@code 0x20} the server actually sent. It is
	 * written now so it is live the moment the field exists on the record.
	 */
	@Test
	public void theServerAdvertisesNoMaxDatagramFrameSize() throws Exception {
		connectToRealServer();

		QuicTransportParameters advertised = wire.clientConnection().peerTransportParameters();
		assertNotNull(advertised);
		Set<Long> identifiers = transportParameterIdentifiers(advertised);
		assertFalse("max_datagram_frame_size (0x20) must be absent while datagrams are off",
			identifiers.contains(MAX_DATAGRAM_FRAME_SIZE));
		assertEquals("the exact phase-1 server parameter set",
			PHASE_ONE_SERVER_TRANSPORT_PARAMETER_IDS, identifiers);
	}

	/** Live: the recorded record is the one this endpoint encodes, not one round-tripped through a parser. */
	@Test
	public void theClientAdvertisesNoMaxDatagramFrameSize() throws Exception {
		QuicTransportParameters[] local = new QuicTransportParameters[1];
		Http3Client client = connectWithRealClient(recordingLocalParameters(local));
		issueOneRequest(client);
		wire.driveUntil(() -> local[0] != null);

		assertNotNull(local[0]);
		assertFalse("max_datagram_frame_size (0x20) must be absent while datagrams are off",
			transportParameterIdentifiers(local[0]).contains(MAX_DATAGRAM_FRAME_SIZE));
	}

	// ---------------------------------------------------------------- T006: no session ticket

	/**
	 * <b>Weak today, deliberately.</b> Phase-1 {@code TlsServerEngine} has no ticket-issuing path, so
	 * zero post-handshake CRYPTO bytes is the absence of a sender rather than the absence of a
	 * capability. {@code handshakeComplete} is asserted alongside so the count cannot be zero because
	 * the handshake never finished.
	 */
	@Test
	public void noSessionTicketIsIssuedToTheClient() {
		boolean[] handshakeComplete = {false};
		int[] postHandshakeCryptoBytes = {0};

		Function<String, TlsEngineFactory> recording = host -> params -> {
			TlsEngine delegate = Http3TestTls.clientEngineFactory(host).create(params);
			return (level, cryptoBytes) -> {
				// Read before delegating: consume() owns and recycles cryptoBytes on every path.
				int readable = cryptoBytes.readRemaining();
				if (handshakeComplete[0]) postHandshakeCryptoBytes[0] += readable;
				TlsEngineResult result = delegate.consume(level, cryptoBytes);
				if (result.handshakeComplete()) handshakeComplete[0] = true;
				return result;
			};
		};

		Http3Client client = connectWithRealClient(recording);
		issueOneRequest(client);
		wire.driveUntil(() -> uniBytes.remainingBytes() >= PHASE_ONE_CONTROL_STREAM_PREFIX.length);
		// Long enough for a ticket sent right after the handshake to have arrived if there were one.
		wire.advance(200);

		assertTrue("the handshake must have completed, or a zero count would pass for the wrong reason",
			handshakeComplete[0]);
		assertEquals("no post-handshake CRYPTO byte reaches the client — no NewSessionTicket (SC-011)",
			0, postHandshakeCryptoBytes[0]);
	}

	// ---------------------------------------------------------------- fixtures

	/** A real {@link Http3Server} over the fixture's socket, observed by a raw QUIC client. */
	private void connectToRealServer() {
		wire = new Http3WirePair(loop)
			.withServerFactory(socket -> {
				Http3Server server = Http3Server.builder(reactor(), SERVLET)
					.withSocket(socket)
					.withServerIdentity(Http3TestTls.devIdentity())
					.withSettings(Http3Settings.create())
					.build();
				server.listen();
				return server;
			})
			.withClientStreamListener(this::observe)
			.connect();
	}

	/** A real {@link Http3Client} over the fixture's socket, observed by a raw QUIC server. */
	private Http3Client connectWithRealClient(Function<String, TlsEngineFactory> tlsEngineFactory) {
		Http3Client[] captured = new Http3Client[1];
		wire = new Http3WirePair(loop)
			.withServerStreamListener(this::observe)
			.withClientFactory(socket -> captured[0] = Http3Client.builder(reactor(), dns)
				.withSocket(socket)
				.withSettings(Http3Settings.create())
				.withTlsEngineFactory(tlsEngineFactory)
				.build())
			.connect();
		return captured[0];
	}

	/**
	 * Nothing dials until a request is issued. The promise never resolves against a raw QUIC peer — it
	 * is failed by {@code wire.close()} — so no test drives on it.
	 */
	private void issueOneRequest(Http3Client client) {
		Promise<HttpResponse> ignored =
			client.request(HttpRequest.get(Http3ClientFixture.url(Http3ClientFixture.HOST, "/")).build());
		ignored.whenException(e -> {});
	}

	private void observe(QuicStream stream) {
		peerStreams.add(stream);
		if (stream.isBidirectional()) {
			Http3TestBytes.collect(stream, otherBytes);
		} else {
			peerUniStreams.add(stream);
			Http3TestBytes.collect(stream, uniBytes);
		}
	}

	/** The local {@link QuicTransportParameters} <i>this</i> endpoint advertises — the only byte-level seam. */
	private static Function<String, TlsEngineFactory> recordingLocalParameters(QuicTransportParameters[] into) {
		return host -> params -> {
			into[0] = params;
			return Http3TestTls.clientEngineFactory(host).create(params);
		};
	}

	// ---------------------------------------------------------------- assertions

	private void assertOnlyTheControlStreamWasOpened() throws Exception {
		assertEquals("no QPACK encoder or decoder stream is opened (FR-018, SC-011)",
			1, peerUniStreams.size());
		assertFalse("the control stream is unidirectional (RFC 9114 §6.2)",
			peerUniStreams.get(0).isBidirectional());

		ByteBuf buf = uniBytes.takeRemaining();
		try {
			assertEquals("and it is the control stream", Http3StreamType.CONTROL.code(), QuicVarInts.read(buf));
		} finally {
			buf.recycle();
		}
	}

	private void assertPhaseOneSettingsIdentifiers(SettingsFrame frame) {
		Map<Long, Long> advertised = toMap(frame);
		assertEquals("exactly the three phase-1 identifiers — SETTINGS_H3_DATAGRAM (0x33) included in what may not appear",
			Set.of(SettingsFrame.QPACK_MAX_TABLE_CAPACITY,
				SettingsFrame.MAX_FIELD_SECTION_SIZE,
				SettingsFrame.QPACK_BLOCKED_STREAMS),
			advertised.keySet());
		assertFalse(advertised.containsKey(SETTINGS_H3_DATAGRAM));
		assertEquals(Long.valueOf(0), advertised.get(SettingsFrame.QPACK_MAX_TABLE_CAPACITY));
		assertEquals(Long.valueOf(65536), advertised.get(SettingsFrame.MAX_FIELD_SECTION_SIZE));
		assertEquals(Long.valueOf(0), advertised.get(SettingsFrame.QPACK_BLOCKED_STREAMS));
	}

	/** The phase-1 golden values, all of them {@code core-quic}'s own defaults as HTTP/3 leaves them. */
	private static void assertPhaseOneTransportParameterValues(QuicTransportParameters p) {
		assertEquals("FR-017: the control stream plus both QPACK streams", 3, p.initialMaxStreamsUni());
		assertEquals("FR-046: maxConcurrentRequestStreams", 100, p.initialMaxStreamsBidi());
		assertEquals("initial_max_data — 1 MB", 1024 * 1024, p.initialMaxData());
		assertEquals("initial_max_stream_data_bidi_local — 256 KB", 256 * 1024, p.initialMaxStreamDataBidiLocal());
		assertEquals("initial_max_stream_data_bidi_remote — 256 KB", 256 * 1024, p.initialMaxStreamDataBidiRemote());
		assertEquals("initial_max_stream_data_uni — 256 KB", 256 * 1024, p.initialMaxStreamDataUni());
		assertEquals("max_idle_timeout — 30 s", 30_000, p.maxIdleTimeout());
		assertEquals("max_udp_payload_size — the 1350-byte datagram size", 1350, p.maxUdpPayloadSize());
		assertEquals("ack_delay_exponent — the RFC 9000 §18.2 default", 3, p.ackDelayExponent());
		assertEquals("max_ack_delay — the RFC 9000 §18.2 default", 25, p.maxAckDelay());
		assertEquals("active_connection_id_limit — the RFC 9000 §18.2 default", 2, p.activeConnectionIdLimit());
		assertTrue("disable_active_migration: migration is out of scope", p.disableActiveMigration());
		assertNull("stateless_reset_token is out of scope", p.statelessResetToken());
		assertNull("preferred_address is out of scope", p.preferredAddress());
		assertNull("retry_source_connection_id is only set by a Retry-issuing server", p.retrySourceConnectionId());
		assertNotNull("initial_source_connection_id is mandatory (RFC 9000 §18.2)", p.initialSourceConnectionId());
	}

	// ---------------------------------------------------------------- helpers

	private byte[] takeUniPrefix() {
		ByteBuf buf = uniBytes.takeRemaining();
		try {
			byte[] prefix = new byte[PHASE_ONE_CONTROL_STREAM_PREFIX.length];
			buf.read(prefix);
			return prefix;
		} finally {
			buf.recycle();
		}
	}

	private SettingsFrame decodeSettings() throws Exception {
		ByteBuf buf = uniBytes.takeRemaining();
		try {
			assertEquals(Http3StreamType.CONTROL.code(), QuicVarInts.read(buf));
			Http3Frame frame = new Http3FrameReader(Http3Settings.create().maxControlFrameSize()).feed(buf);
			assertTrue("the first frame on the control stream is SETTINGS", frame instanceof SettingsFrame);
			return (SettingsFrame) frame;
		} finally {
			buf.recycle();
		}
	}

	/**
	 * The set of RFC 9000 §18 parameter identifiers {@code params} encodes to — the shape a new
	 * parameter such as {@code max_datagram_frame_size} would show up in.
	 */
	private static Set<Long> transportParameterIdentifiers(QuicTransportParameters params)
		throws MalformedDataException {
		ByteBuf buf = ByteBufPool.allocate(params.encodedLength());
		try {
			params.writeTo(buf);
			Set<Long> identifiers = new HashSet<>();
			while (buf.canRead()) {
				long id = QuicVarInts.read(buf);
				long length = QuicVarInts.read(buf);
				buf.moveHead((int) length);
				identifiers.add(id);
			}
			return identifiers;
		} finally {
			buf.recycle();
		}
	}

	private static Map<Long, Long> toMap(SettingsFrame frame) {
		Map<Long, Long> map = new LinkedHashMap<>();
		for (int i = 0; i < frame.identifiers.length; i++) {
			map.put(frame.identifiers[i], frame.values[i]);
		}
		return map;
	}

	private static Set<Long> union(Set<Long> base, long extra) {
		Set<Long> all = new HashSet<>(base);
		all.add(extra);
		return Set.copyOf(all);
	}

	private static NioReactor reactor() {
		return (NioReactor) Reactor.getCurrentReactor();
	}
}
