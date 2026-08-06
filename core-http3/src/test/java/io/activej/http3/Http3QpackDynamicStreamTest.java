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
import io.activej.http.HttpHeaders;
import io.activej.http.HttpResponse;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3Frames;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.qpack.QpackDynamicDecoder;
import io.activej.http3.qpack.QpackDynamicEncoder;
import io.activej.http3.qpack.QpackEncoderStreamReader;
import io.activej.http3.qpack.QpackException;
import io.activej.http3.qpack.QpackField;
import io.activej.http3.qpack.QpackInstructions;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.SetDynamicTableCapacity;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.http3.qpack.QpackStaticDecoder;
import io.activej.http3.qpack.QpackStaticEncoder;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promises;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.activej.http3.testutil.Http3TestBytes.bytes;
import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.headersFrame;
import static io.activej.http3.testutil.Http3TestBytes.requestFields;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * US1 at the <b>connection</b> level: which unidirectional streams a dynamic-table connection opens,
 * when, and what the first bytes on each of them are (FR-017, FR-019, FR-023 through FR-026, FR-040).
 * <p>
 * The direction asymmetry is what most of these assert. The local <b>decoder</b> depends on nothing
 * the peer says — our own advertised maximum bounds what the peer's encoder may do — so its stream
 * opens at {@code start()}. The local <b>encoder</b> depends on the peer's SETTINGS, which arrive
 * afterwards, so its stream opens then and its first instruction is the {@code Set Dynamic Table
 * Capacity} that the negotiation produced.
 */
public final class Http3QpackDynamicStreamTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final MemSize CAPACITY = MemSize.kilobytes(4);

	/** RFC 9114 §7.2.4.1 {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. */
	private static final long QPACK_MAX_TABLE_CAPACITY = 0x01;

	private final List<Http3WirePair> wires = new ArrayList<>();
	private final List<Recorded> serverStreams = new ArrayList<>();
	private final List<Http3RequestStream> served = new ArrayList<>();

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
		loop.close();
	}

	// ---------------------------------------------------------------- the two local streams

	@Test
	public void enablingTheTableOpensTheDecoderStreamBeforeThePeerHasSaidAnything() {
		Peer peer = connect(dynamicSettings());
		peer.wire.advance(50);

		assertEquals("control and decoder, and nothing else (FR-023)", 2, serverStreams.size());
		assertEquals(Http3StreamType.CONTROL.code(), serverStreams.get(0).type());
		assertEquals(Http3StreamType.QPACK_DECODER.code(), serverStreams.get(1).type());
		assertEquals("no encoder stream before the peer's SETTINGS (FR-019)", 0, streamsOfType(Http3StreamType.QPACK_ENCODER));
		assertNotEquals(State.CLOSED, peer.h3.state());
	}

	@Test
	public void thePeersCapacityOpensTheEncoderStreamWithSetDynamicTableCapacityFirst() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> streamsOfType(Http3StreamType.QPACK_ENCODER) == 1);
		peer.wire.advance(50);

		assertEquals(State.READY, peer.h3.state());
		Recorded encoderStream = onlyStreamOfType(Http3StreamType.QPACK_ENCODER);
		// FR-017: the first instruction on the stream, not merely an instruction somewhere on it.
		assertStartsWith(encoded(new SetDynamicTableCapacity(CAPACITY.toInt())), encoderStream.payload());
	}

	@Test
	public void aSmallerPeerCapacityIsWhatTheEncoderRequests() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(1024);
		peer.wire.driveUntil(() -> streamsOfType(Http3StreamType.QPACK_ENCODER) == 1);

		// FR-019: min(local maximum, peer advertisement), and never above what the peer permits.
		assertStartsWith(encoded(new SetDynamicTableCapacity(1024)), onlyStreamOfType(Http3StreamType.QPACK_ENCODER).payload());
	}

	@Test
	public void aPeerAdvertisingZeroGetsNoEncoderStreamAndPhaseOneFieldSections() throws QpackException {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(0);
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		QuicStream request = peer.openRequestStream(headersFrame(requestFields("GET", "/")));
		Recorded response = new Recorded(request);
		collect(response);
		peer.wire.driveUntil(() -> response.frameCount() >= 1);
		peer.wire.advance(50);

		assertEquals("no encoder stream against a peer capacity of 0 (FR-019)", 0, streamsOfType(Http3StreamType.QPACK_ENCODER));
		// FR-040 byte identity, asserted without knowing which fields the response carries: a static
		// decode succeeds only for a representation the phase-1 encoder could have produced, and
		// re-encoding it reproduces the very bytes if that is what produced them.
		byte[] section = response.frame(0);
		List<QpackField> fields = new QpackStaticDecoder(Http3Settings.DEFAULT_MAX_FIELD_SECTION_SIZE.toLong())
			.decode(ByteBuf.wrapForReading(section));
		ByteBuf reencoded = new QpackStaticEncoder().encode(fields);
		try {
			assertArrayEquals(section, reencoded.getArray());
		} finally {
			reencoded.recycle();
		}
	}

	// ---------------------------------------------------------------- the decoder stream's traffic

	@Test
	public void aPeerInsertionIsAnnouncedWithAnInsertCountIncrement() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		QpackDynamicEncoder peerEncoder = peerEncoder();
		ByteBuf section = peerEncoder.encode(0, io.activej.http3.Http3Headers.toQpack(requestFields("GET", "/")));
		section.recycle();
		peer.openQpackEncoderStream(instructions(peerEncoder.drainPendingInstructions()));

		Recorded decoderStream = onlyStreamOfType(Http3StreamType.QPACK_DECODER);
		byte[] expected = encoded(new InsertCountIncrement(peerEncoder.insertCount()));
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), expected));

		assertTrue("FR-026: the insertions the peer made, announced back", contains(decoderStream.payload(), expected));
	}

	@Test
	public void aSectionWithANonZeroRequiredInsertCountIsAcknowledged() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		QpackDynamicEncoder peerEncoder = peerEncoder();
		ByteBuf section = peerEncoder.encode(0, io.activej.http3.Http3Headers.toQpack(requestFields("GET", "/")));
		peer.openQpackEncoderStream(instructions(peerEncoder.drainPendingInstructions()));
		Recorded decoderStream = onlyStreamOfType(Http3StreamType.QPACK_DECODER);
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), encoded(new InsertCountIncrement(1))));

		QuicStream request = peer.openRequestStream(headersFrameOf(section));
		byte[] acknowledgment = encoded(new SectionAcknowledgment(request.id()));
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), acknowledgment));

		assertTrue("FR-024", contains(decoderStream.payload(), acknowledgment));
		assertEquals(1, served.size());
	}

	@Test
	public void aResetRequestStreamIsCancelled() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		QpackDynamicEncoder peerEncoder = peerEncoder();
		ByteBuf section = peerEncoder.encode(0, io.activej.http3.Http3Headers.toQpack(requestFields("GET", "/")));
		peer.openQpackEncoderStream(instructions(peerEncoder.drainPendingInstructions()));
		Recorded decoderStream = onlyStreamOfType(Http3StreamType.QPACK_DECODER);
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), encoded(new InsertCountIncrement(1))));

		QuicStream request = peer.openRequestStream(headersFrameOf(section));
		peer.wire.driveUntil(() -> served.size() == 1);
		request.reset(Http3Errors.H3_REQUEST_CANCELLED);

		byte[] cancellation = encoded(new StreamCancellation(request.id()));
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), cancellation));
		assertTrue("FR-025", contains(decoderStream.payload(), cancellation));
	}

	@Test
	public void aResetWhileTheConnectionIsClosingIsNotCancelled() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		QpackDynamicEncoder peerEncoder = peerEncoder();
		ByteBuf section = peerEncoder.encode(0, io.activej.http3.Http3Headers.toQpack(requestFields("GET", "/")));
		peer.openQpackEncoderStream(instructions(peerEncoder.drainPendingInstructions()));
		Recorded decoderStream = onlyStreamOfType(Http3StreamType.QPACK_DECODER);
		peer.wire.driveUntil(() -> contains(decoderStream.payload(), encoded(new InsertCountIncrement(1))));

		QuicStream request = peer.openRequestStream(headersFrameOf(section));
		peer.wire.driveUntil(() -> served.size() == 1);
		peer.wire.advance(20);

		int before = decoderStream.payload().length;
		// The connection closing is what aborts the stream, and FR-025 excludes exactly that case: there
		// is no table left for the peer to release anything from.
		peer.h3.close();
		peer.wire.advance(50);

		assertEquals(State.CLOSED, peer.h3.state());
		assertEquals("no decoder-stream byte after the close (FR-025)", before, decoderStream.payload().length);
		assertFalse(contains(decoderStream.payload(), encoded(new StreamCancellation(request.id()))));
	}

	// ---------------------------------------------------------------- the peer's streams, dynamic

	/**
	 * The routing of the peer's <b>decoder</b> stream really did move: RFC 9204 §4.4.2 makes cancelling
	 * a stream that carried no section legal, and phase 1 refuses every decoder instruction there is —
	 * so accepting this one can only mean the dynamic parser saw it.
	 */
	@Test
	public void aStreamCancellationIsAcceptedOnceTheEncoderExists() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> streamsOfType(Http3StreamType.QPACK_ENCODER) == 1);

		peer.openUnidirectional(Http3StreamType.QPACK_DECODER.code(),
			QpackInstructions.encode(new StreamCancellation(0)));
		peer.wire.advance(50);

		assertEquals(State.READY, peer.h3.state());
		assertEquals("no connection error", -1, peer.h3.closedWithErrorCode());
	}

	@Test
	public void anAcknowledgmentOfANonExistentSectionIsADecoderStreamError() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> streamsOfType(Http3StreamType.QPACK_ENCODER) == 1);

		peer.openUnidirectional(Http3StreamType.QPACK_DECODER.code(),
			QpackInstructions.encode(new SectionAcknowledgment(0)));

		peer.wire.driveUntil(() -> peer.h3.state() == State.CLOSED);
		assertEquals(Http3Errors.QPACK_DECODER_STREAM_ERROR, peer.h3.closedWithErrorCode());
	}

	@Test
	public void aCapacityAboveTheAdvertisedMaximumIsAnEncoderStreamError() {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);

		ByteBuf tooLarge = QpackInstructions.encode(new SetDynamicTableCapacity(2 * CAPACITY.toInt()));
		peer.openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), tooLarge);

		peer.wire.driveUntil(() -> peer.h3.state() == State.CLOSED);
		assertEquals(Http3Errors.QPACK_ENCODER_STREAM_ERROR, peer.h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- the compression this exists for

	/**
	 * SC-003 / SC-001 over a real connection: two exchanges, and the second response's field section
	 * references what the first one inserted.
	 * <p>
	 * The peer advertises a blocked-stream limit of <b>0</b>, which is what makes this a test of the
	 * whole loop rather than of the encoder alone. At that limit RFC 9204 §2.1.2 lets the encoder
	 * reference only entries the Known Received Count already covers, so the second response can be
	 * smaller <i>only</i> if the first one's instructions reached this peer's encoder-stream reader
	 * (T034's drain), this peer's {@code Insert Count Increment} reached the connection's decoder-stream
	 * reader (T033a), and it advanced the encoder's Known Received Count. A section that arrived before
	 * the instructions it needs would not shrink — it would fail to decode at all.
	 */
	@Test
	public void aSecondResponseReferencesWhatTheFirstOneInserted() throws QpackException {
		Peer peer = connect(dynamicSettings());
		peer.openControlStream(CAPACITY.toInt());
		peer.wire.driveUntil(() -> streamsOfType(Http3StreamType.QPACK_ENCODER) == 1);
		Recorded encoderStream = onlyStreamOfType(Http3StreamType.QPACK_ENCODER);

		QpackDynamicDecoder peerDecoder = new QpackDynamicDecoder(CAPACITY.toInt(), 0, Long.MAX_VALUE);
		QpackEncoderStreamReader peerReader = new QpackEncoderStreamReader(peerDecoder, 16 * 1024);
		QuicStream peerDecoderStream = peer.openUnidirectional(Http3StreamType.QPACK_DECODER.code(), bytes());
		Exchange exchange = new Exchange(peer, peerDecoder, peerReader, encoderStream, peerDecoderStream);
		try {
			List<QpackField> first = exchange.run();
			List<QpackField> second = exchange.run();

			assertEquals("the same response, decoded the same way", first, second);
			assertTrue("nothing was inserted at all", peerDecoder.insertCount() > 0);
			// Guards the ratio below against passing vacuously on two sections that were both tiny.
			assertTrue("a first response of " + exchange.sectionLength(0) + " bytes is not worth compressing",
				exchange.sectionLength(0) > 60);
			assertTrue("SC-001: response 2 was " + exchange.sectionLength(1) + " bytes against response 1's " +
					   exchange.sectionLength(0),
				exchange.sectionLength(1) * 5 <= exchange.sectionLength(0) * 2);
		} finally {
			peerReader.recycle();
		}
	}

	// ---------------------------------------------------------------- the default, unchanged

	@Test
	public void theDefaultsOpenNoQpackStreamAtAll() {
		Peer peer = connect(Http3Settings.create());
		peer.openControlStream(0);
		peer.wire.driveUntil(() -> peer.h3.state() == State.READY);
		peer.wire.advance(200);

		assertEquals("only the control stream (SC-011)", 1, serverStreams.size());
		assertEquals(Http3StreamType.CONTROL.code(), serverStreams.get(0).type());
		assertEquals(State.READY, peer.h3.state());
	}

	// ---------------------------------------------------------------- helpers

	private static Http3Settings dynamicSettings() {
		return Http3Settings.builder().withQpackMaxTableCapacity(CAPACITY).build();
	}

	/** A peer-side encoder generous enough to reference on its very first section, not only on its second. */
	private static QpackDynamicEncoder peerEncoder() {
		return new QpackDynamicEncoder(CAPACITY.toInt(), CAPACITY.toInt(), 1, 16, Set.of());
	}

	private Peer connect(Http3Settings settings) {
		Http3Connection[] captured = new Http3Connection[1];
		Http3WirePair wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> {
				Recorded recorded = new Recorded(stream);
				serverStreams.add(recorded);
				collect(recorded);
			})
			.withServerHandlerFactory(connection -> {
				Http3Connection h3 = Http3Connection.builder(reactor(), connection)
					.withSettings(settings)
					.withRequestStreamListener(this::serve)
					.build();
				captured[0] = h3;
				h3.start();
				return h3.streamManager();
			})
			.connect();
		wires.add(wire);
		return new Peer(wire, captured[0]);
	}

	/**
	 * The response is the same every time and carries fields no static-table entry covers, so what the
	 * second exchange saves is exactly what the dynamic table bought.
	 */
	private void serve(Http3RequestStream stream) {
		served.add(stream);
		stream.receiveRequest()
			.whenComplete((request, e) -> {
				if (e != null) return;
				stream.sendResponse(HttpResponse.ok200()
					.withHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
					.withHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate, max-age=0")
					.withHeader(HttpHeaders.of("x-request-id"), "0123456789abcdef0123456789abcdef")
					.build());
			});
	}

	private void collect(Recorded recorded) {
		Promises.repeat(() -> recorded.stream.reader().get()
				.map(buf -> {
					if (buf == null) return false;
					recorded.append(buf);
					return true;
				}))
			.whenException(e -> {});
	}

	private int streamsOfType(Http3StreamType type) {
		int count = 0;
		for (Recorded recorded : serverStreams) {
			if (recorded.type() == type.code()) count++;
		}
		return count;
	}

	private Recorded onlyStreamOfType(Http3StreamType type) {
		assertEquals("exactly one " + type + " stream", 1, streamsOfType(type));
		for (Recorded recorded : serverStreams) {
			if (recorded.type() == type.code()) return recorded;
		}
		throw new AssertionError();
	}

	private static byte[] encoded(QpackInstructions.Instruction instruction) {
		return QpackInstructions.encode(instruction).asArray();
	}

	/** The instructions, back to back, as one owned buffer — the peer's encoder-stream preamble. */
	private static ByteBuf instructions(List<EncoderInstruction> instructions) {
		int length = 0;
		for (EncoderInstruction instruction : instructions) {
			length += instruction.encodedLength();
		}
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, length));
		for (EncoderInstruction instruction : instructions) {
			instruction.writeTo(buf);
		}
		return buf;
	}

	/** {@code bytes[from, to)} in a pooled buffer, for handing to a codec that owns its input. */
	private static ByteBuf pooled(byte[] bytes, int from, int to) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, to - from));
		buf.put(bytes, from, to - from);
		return buf;
	}

	private static ByteBuf headersFrameOf(ByteBuf section) {
		HeadersFrame frame = new HeadersFrame(section);
		try {
			ByteBuf buf = ByteBufPool.allocate(Http3Frames.encodedLength(frame));
			Http3Frames.write(buf, frame);
			return buf;
		} finally {
			frame.recycle();
		}
	}

	private static void assertStartsWith(byte[] expected, byte[] actual) {
		assertTrue("expected " + Arrays.toString(expected) + " at the head of " + Arrays.toString(actual),
			actual.length >= expected.length &&
			Arrays.equals(Arrays.copyOf(actual, expected.length), expected));
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static Reactor reactor() {
		return Reactor.getCurrentReactor();
	}

	private record Peer(Http3WirePair wire, Http3Connection h3) {
		void openControlStream(long qpackMaxTableCapacity) {
			openUnidirectional(Http3StreamType.CONTROL.code(),
				settingsFrame(new long[]{QPACK_MAX_TABLE_CAPACITY}, new long[]{qpackMaxTableCapacity}));
		}

		void openQpackEncoderStream(ByteBuf payload) {
			openUnidirectional(Http3StreamType.QPACK_ENCODER.code(), payload);
		}

		QuicStream openUnidirectional(long streamType, ByteBuf payload) {
			QuicStream stream = wire.openNow(wire.clientStreams().openUnidirectional());
			stream.writer().accept(concat(streamHeader(streamType), payload));
			return stream;
		}

		QuicStream openRequestStream(ByteBuf headers) {
			QuicStream stream = wire.openNow(wire.clientStreams().openBidirectional());
			stream.writer().accept(headers);
			return stream;
		}
	}

	/**
	 * One request/response round driven from the peer's side, with a real {@link QpackDynamicDecoder}
	 * standing in for what a peer's decoder does: apply the encoder-stream instructions that have
	 * arrived, decode the response's field section against them, and announce the insertions back so the
	 * connection's Known Received Count can advance (RFC 9204 §4.4.3).
	 */
	private final class Exchange {
		private final Peer peer;
		private final QpackDynamicDecoder decoder;
		private final QpackEncoderStreamReader reader;
		private final Recorded encoderStream;
		private final QuicStream decoderStream;
		private final List<Integer> sectionLengths = new ArrayList<>();

		private int instructionsFed;

		private Exchange(Peer peer, QpackDynamicDecoder decoder, QpackEncoderStreamReader reader,
			Recorded encoderStream, QuicStream decoderStream
		) {
			this.peer = peer;
			this.decoder = decoder;
			this.reader = reader;
			this.encoderStream = encoderStream;
			this.decoderStream = decoderStream;
		}

		List<QpackField> run() throws QpackException {
			QuicStream stream = peer.openRequestStream(headersFrame(requestFields("GET", "/")));
			Recorded response = new Recorded(stream);
			collect(response);
			peer.wire.driveUntil(() -> response.frameCount() >= 1);

			byte[] instructions = encoderStream.payload();
			if (instructions.length > instructionsFed) {
				reader.feed(pooled(instructions, instructionsFed, instructions.length));
				instructionsFed = instructions.length;
			}
			byte[] section = response.frame(0);
			sectionLengths.add(section.length);
			List<QpackField> fields = decoder.decode(pooled(section, 0, section.length));

			long increment = decoder.pendingInsertCountIncrement();
			if (increment > 0) {
				decoder.onInsertCountAnnounced(decoder.insertCount());
				decoderStream.writer().accept(QpackInstructions.encode(new InsertCountIncrement(increment)));
			}
			peer.wire.advance(20);
			return fields;
		}

		int sectionLength(int index) {
			return sectionLengths.get(index);
		}
	}

	/**
	 * Every byte one stream ever delivered, copied out of the pool as it arrives — so an assertion may
	 * look at the same prefix twice, and {@code ByteBufRule} still sees every buffer recycled.
	 */
	private static final class Recorded {
		private final QuicStream stream;
		private byte[] bytes = {};

		private Recorded(QuicStream stream) {
			this.stream = stream;
		}

		void append(ByteBuf buf) {
			byte[] next = Arrays.copyOf(bytes, bytes.length + buf.readRemaining());
			System.arraycopy(buf.getArray(), 0, next, bytes.length, buf.readRemaining());
			bytes = next;
			buf.recycle();
		}

		/** The RFC 9114 §6.2 stream-type varint, or −1 while no byte has arrived. */
		long type() {
			if (bytes.length == 0) return -1;
			int length = 1 << ((bytes[0] & 0xFF) >>> 6);
			if (bytes.length < length) return -1;
			long value = bytes[0] & 0x3F;
			for (int i = 1; i < length; i++) {
				value = (value << 8) | (bytes[i] & 0xFF);
			}
			return value;
		}

		/** Whatever followed the stream-type varint. */
		byte[] payload() {
			if (type() == -1) return new byte[0];
			return Arrays.copyOfRange(bytes, 1 << ((bytes[0] & 0xFF) >>> 6), bytes.length);
		}

		/** The payload of the {@code index}-th complete frame on a <b>bidirectional</b> stream. */
		byte[] frame(int index) {
			ByteBuf buf = ByteBuf.wrapForReading(bytes);
			for (int i = 0; ; i++) {
				readVarInt(buf);
				int length = (int) readVarInt(buf);
				byte[] payload = new byte[length];
				buf.drainTo(payload, 0, length);
				if (i == index) return payload;
			}
		}

		int frameCount() {
			ByteBuf buf = ByteBuf.wrapForReading(bytes);
			int count = 0;
			while (buf.canRead()) {
				int head = buf.head();
				if (!hasVarInt(buf)) break;
				readVarInt(buf);
				if (!hasVarInt(buf)) {
					buf.head(head);
					break;
				}
				long length = readVarInt(buf);
				if (buf.readRemaining() < length) {
					buf.head(head);
					break;
				}
				buf.moveHead((int) length);
				count++;
			}
			return count;
		}

		private static boolean hasVarInt(ByteBuf buf) {
			return buf.canRead() && buf.readRemaining() >= 1 << ((buf.peek() & 0xFF) >>> 6);
		}

		private static long readVarInt(ByteBuf buf) {
			int first = buf.readByte() & 0xFF;
			int length = 1 << (first >>> 6);
			long value = first & 0x3F;
			for (int i = 1; i < length; i++) {
				value = (value << 8) | (buf.readByte() & 0xFF);
			}
			return value;
		}
	}
}
