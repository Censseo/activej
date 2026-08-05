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
import io.activej.http3.Http3Connection.QpackBlockedExit;
import io.activej.http3.Http3Connection.State;
import io.activej.http3.Http3Headers.Field;
import io.activej.http3.frame.DataFrame;
import io.activej.http3.frame.HeadersFrame;
import io.activej.http3.frame.Http3StreamType;
import io.activej.http3.qpack.QpackDynamicDecoder;
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackDynamicEncoder;
import io.activej.http3.qpack.QpackEncoderStreamReader;
import io.activej.http3.qpack.QpackException;
import io.activej.http3.qpack.QpackInstructions;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.http3.qpack.QpackInstructions.StreamCancellation;
import io.activej.http3.testutil.Http3WirePair;
import io.activej.http3.testutil.ManualEventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.quic.stream.QuicStream;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.activej.http3.testutil.Http3TestBytes.concat;
import static io.activej.http3.testutil.Http3TestBytes.frame;
import static io.activej.http3.testutil.Http3TestBytes.settingsFrame;
import static io.activej.http3.testutil.Http3TestBytes.streamHeader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * US2 at the <b>connection</b> level: a field section whose Required Insert Count exceeds the
 * decoder's Insert Count is <i>held</i>, decoded when the peer's encoder stream catches up, surfaced
 * in arrival order, and released on every terminal path (FR-033, FR-035, FR-037, and FR-025's
 * "unless the connection is already closing").
 *
 * <h4>Which tasks live here</h4>
 * <table>
 *   <tr><td>T043</td><td>{@link #twoSectionsBlockedOnOneStreamSurfaceInArrivalOrder}</td></tr>
 *   <tr><td>T044</td><td>{@link #aResetBlockedStreamIsCancelledAndReleasesItsSection},
 *                        {@link #aConnectionClosedWithASectionHeldReleasesItAndCancelsNothing}</td></tr>
 *   <tr><td>T046</td><td>{@link #aBlockedRequestHeadIsHeldUntilTheInsertionsArrive},
 *                        {@link #aBlockedTrailerSectionDecodesInWireOrder}</td></tr>
 *   <tr><td>T053</td><td>{@link #aHeldSectionEntersAndItsDecodeExitsTheBlockedState},
 *                        {@link #aResetBlockedStreamExitsAsReset},
 *                        {@link #aTimedOutSectionExitsAsTimedOut},
 *                        {@link #aConnectionClosedWithASectionHeldExitsAsClosed},
 *                        {@link #aSectionAboveTheCountBoundIsReportedAsRefused}</td></tr>
 * </table>
 *
 * <h4>How a section is made to block, without a lossy socket</h4>
 * {@code core-quic}'s {@code LossyUdpSocket} / {@code QuicWirePair} are test-scope in another module,
 * and reaching them would mean the {@code test-jar} edge research Decision 12 rejects — which is why
 * {@link Http3WirePair} exists here at all. Nothing is lost: the reordering US2 is about is not
 * datagram loss but <b>stream</b> ordering, and QUIC gives no ordering guarantee <i>between</i>
 * streams. A peer that writes a request stream and only afterwards writes the encoder stream that
 * request depends on produces exactly the arrival order a reordered or lost-and-retransmitted
 * encoder-stream packet produces, deterministically and at one hop rather than at a seed. That
 * ordering is what these tests write.
 *
 * <h4>What is observable, and what is not</h4>
 * A decoded field section reaches a test through {@link HttpRequest}, whose header map is
 * open-addressed and therefore <b>not</b> insertion-ordered — so field <i>order</i> within a request
 * head is not observable here. It is observable for a <b>trailing</b> section, which
 * {@link Http3Trailers#get} hands back as the verbatim {@code List<Field>}; that is why both ordering
 * assertions ride on trailers.
 */
public final class Http3QpackBlockedSectionsWireTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	private static final MemSize CAPACITY = MemSize.kilobytes(4);

	/** What the endpoint under test advertises, and therefore the budget its peer's encoder may spend. */
	private static final int BLOCKED_STREAMS = 16;

	/** RFC 9114 §7.2.4.1 {@code SETTINGS_QPACK_MAX_TABLE_CAPACITY}. */
	private static final long QPACK_MAX_TABLE_CAPACITY = 0x01;

	private final List<Http3WirePair> wires = new ArrayList<>();
	private final List<Recorded> localStreams = new ArrayList<>();

	/** Request <b>heads</b>, as {@code receiveRequest()} resolves them — one HEADERS section each. */
	private final List<HttpRequest> heads = new ArrayList<>();

	/**
	 * What {@link Http3Trailers#get} held once the body finished — a second section on the same stream.
	 * Separate from {@link #heads} because a stream whose <i>trailer</i> section is held has a decoded
	 * head and an unfinished body, and no single list can say both.
	 */
	private final List<@Nullable List<Field>> trailers = new ArrayList<>();

	private final List<Exception> failures = new ArrayList<>();

	/** T053: what the connection reported to its owner about the streams it held sections for. */
	private final List<Entered> entered = new ArrayList<>();
	private final List<Exited> exited = new ArrayList<>();
	private final List<Refused> refused = new ArrayList<>();

	private int streamsAdopted;

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

	// ---------------------------------------------------------------- T046: the Independent Test

	/**
	 * The phase's own Independent Test: a section whose Required Insert Count exceeds the Insert Count
	 * is held rather than failed, and decodes with the right fields once the insertions land (FR-033).
	 * <p>
	 * The two halves matter equally. Before the instructions arrive the request must <b>not</b> have
	 * surfaced and the connection must <b>not</b> have died — phase 1 and phase 3 both close it with
	 * {@code QPACK_DECOMPRESSION_FAILED} here, which is the behaviour US2 replaces.
	 */
	@Test
	public void aBlockedRequestHeadIsHeldUntilTheInsertionsArrive() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		List<Field> fields = requestFields("/blocked");
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), fields)));
		peer.finish(request);
		peer.wire.advance(50);

		assertEquals("the request stream was adopted", 1, streamsAdopted);
		assertEquals("a held section must surface nothing (FR-033)", 0, heads.size());
		assertEquals("holding a section is not a failure", 0, failures.size());
		assertEquals("a held section is not a connection error (FR-033)", State.READY, peer.h3.state());
		assertEquals(-1, peer.h3.closedWithErrorCode());
		assertFalse("nothing may be acknowledged before it decodes (FR-024)",
			containsInstruction(peer.decoderStream(), new SectionAcknowledgment(request.id())));

		peer.deliverAllInstructions();
		peer.wire.driveUntil(() -> !heads.isEmpty());

		assertEquals(1, heads.size());
		HttpRequest received = heads.get(0);
		assertEquals("GET", received.getMethod().toString());
		assertEquals("/blocked", received.getPath());
		for (Field field : fields) {
			if (field.name().startsWith(":")) continue;
			assertEquals(field.name(), field.value(), received.getHeader(io.activej.http.HttpHeaders.of(field.name())));
		}
		assertTrue("a decoded section owes an acknowledgment (FR-024)",
			containsInstruction(peer.decoderStream(), new SectionAcknowledgment(request.id())));
	}

	/**
	 * The same lifecycle for a <b>trailing</b> section, which is the one place a decoded field section's
	 * order survives all the way out to a test: {@link Http3Trailers#get} hands back the very
	 * {@code List<Field>} the decoder produced.
	 * <p>
	 * The request head is encoded with {@link QpackDynamicEncoder#NO_STREAM}, which inserts nothing and
	 * references nothing — so the head arrives decodable and only the trailer section blocks.
	 */
	@Test
	public void aBlockedTrailerSectionDecodesInWireOrder() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		List<Field> trailerFields = List.of(
			new Field("x-alpha", "alpha"),
			new Field("x-beta", "beta"),
			new Field("x-gamma", "gamma"));

		peer.write(request, frame(HeadersFrame.TYPE, peer.encodeStatically(requestFields("/trailers"))));
		peer.write(request, frame(DataFrame.TYPE, "payload".getBytes(StandardCharsets.UTF_8)));
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), trailerFields)));
		peer.finish(request);
		peer.wire.advance(50);

		assertEquals("the head references nothing dynamic, so it does not block", 1, heads.size());
		assertEquals("the trailing section is held, so the body never finishes", 0, trailers.size());
		assertEquals("holding a section is not a failure", 0, failures.size());
		assertEquals(State.READY, peer.h3.state());

		peer.deliverAllInstructions();
		peer.wire.driveUntil(() -> !trailers.isEmpty());

		assertEquals(1, trailers.size());
		assertEquals("FR-033/FR-037: the same fields, in the order they were written", trailerFields, trailers.get(0));
		assertEquals(0, failures.size());
	}

	// ---------------------------------------------------------------- T043: arrival order

	/**
	 * FR-037. Two sections on one stream, blocked together, whose Required Insert Counts are
	 * <b>inverted</b> against their arrival order: the head needs five insertions, the trailer section
	 * needs three. Delivering only the first three insertions therefore makes the <i>second</i> section
	 * decodable while the first is still blocked — which is the only arrangement under which
	 * "in arrival order" and "in unblock order" disagree, and so the only one that can test the rule.
	 * <p>
	 * An implementation that surfaced the trailer section first would hand a field list with no
	 * {@code :method} to the message layer: the request would be refused as malformed rather than
	 * quietly reordered, which is why {@link #failures} is asserted empty as sharply as
	 * {@link #requests} is asserted correct.
	 */
	@Test
	public void twoSectionsBlockedOnOneStreamSurfaceInArrivalOrder() throws QpackException {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();

		// Five insertions: :authority, :path, and the three x-* fields. The two static-exact pseudo-headers
		// insert nothing, so the Required Insert Count is exactly five.
		List<Field> headFields = List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.test"),
			new Field(":path", "/ordering"),
			new Field("x-alpha", "alpha"),
			new Field("x-beta", "beta"),
			new Field("x-gamma", "gamma"));
		// References the third insertion and nothing newer: Required Insert Count three.
		List<Field> trailerFields = List.of(new Field("x-alpha", "alpha"));

		byte[] head = peer.encode(request.id(), headFields);
		List<EncoderInstruction> instructions = peer.drainInstructions();
		byte[] trailing = peer.encode(request.id(), trailerFields);
		assertEquals("the trailer section must need no insertion of its own", 0, peer.drainInstructions().size());
		assertEquals("SetDynamicTableCapacity plus one insertion per new entry", 6, instructions.size());
		assertPartialDeliveryUnblocksOnlyTheTrailerSection(instructions.subList(0, 4), head, trailing);

		peer.write(request, frame(HeadersFrame.TYPE, head));
		peer.write(request, frame(DataFrame.TYPE, "payload".getBytes(StandardCharsets.UTF_8)));
		peer.write(request, frame(HeadersFrame.TYPE, trailing));
		peer.finish(request);
		peer.wire.advance(50);

		assertEquals("both sections are held", 0, heads.size());
		assertEquals(State.READY, peer.h3.state());

		// Insert Count 3: the trailer section could decode now, the head could not.
		peer.openEncoderStream(instructions.subList(0, 4));
		peer.wire.advance(50);

		assertEquals("the head is still blocked, so nothing may surface (FR-037)", 0, heads.size());
		assertEquals("least of all the section behind it (FR-037)", 0, trailers.size());
		assertEquals("surfacing the trailer section first would be a malformed message, not a reorder",
			0, failures.size());
		assertEquals(State.READY, peer.h3.state());

		peer.writeToEncoderStream(instructions.subList(4, instructions.size()));
		peer.wire.driveUntil(() -> !trailers.isEmpty());

		assertEquals(1, heads.size());
		assertEquals("/ordering", heads.get(0).getPath());
		assertEquals("GET", heads.get(0).getMethod().toString());
		assertEquals("the arrival order, not the unblock order (FR-037)", trailerFields, trailers.get(0));
		assertEquals(0, failures.size());
	}

	/**
	 * The premise {@link #twoSectionsBlockedOnOneStreamSurfaceInArrivalOrder} rests on, checked against a
	 * scratch decoder rather than assumed: after {@code prefix} the <b>second</b> section decodes and the
	 * <b>first</b> still blocks. Without this the whole test could quietly degrade into two sections that
	 * unblock together, which asserts nothing about order at all.
	 */
	private static void assertPartialDeliveryUnblocksOnlyTheTrailerSection(
		List<EncoderInstruction> prefix, byte[] head, byte[] trailing
	) throws QpackException {
		QpackDynamicDecoder scratch = new QpackDynamicDecoder(CAPACITY.toInt(), BLOCKED_STREAMS, Long.MAX_VALUE);
		QpackEncoderStreamReader reader = new QpackEncoderStreamReader(scratch, 16 * 1024);
		try {
			reader.feed(instructionBuffer(prefix));
			assertEquals("the prefix must raise the Insert Count to exactly three", 3, scratch.insertCount());

			SectionResult trailingResult = scratch.decodeOrBlock(pooled(trailing));
			if (!(trailingResult instanceof Decoded)) {
				((Blocked) trailingResult).section().recycle();
				throw new AssertionError("the trailer section did not become decodable at Insert Count 3");
			}
			SectionResult headResult = scratch.decodeOrBlock(pooled(head));
			if (!(headResult instanceof Blocked blocked)) {
				throw new AssertionError("the head decoded at Insert Count 3, so the two sections do not " +
										 "unblock at different moments and nothing here tests FR-037");
			}
			blocked.section().recycle();
		} finally {
			reader.recycle();
		}
	}

	// ---------------------------------------------------------------- T044: teardown

	/**
	 * FR-025 and FR-035 on a stream that is reset while a section of its is still held. The peer's
	 * encoder pinned entries for a section it will now never see acknowledged, so a
	 * {@code Stream Cancellation} is owed; the held buffer is owed a recycle, which
	 * {@link ByteBufRule} is what asserts.
	 * <p>
	 * The two assertions before the reset are what keep this from passing for the phase-3 reason: today
	 * the section fails on arrival, the stream is torn down for it, and a cancellation follows from
	 * <i>that</i>. Only a connection still {@code READY}, with nothing acknowledged, has actually held
	 * anything.
	 */
	@Test
	public void aResetBlockedStreamIsCancelledAndReleasesItsSection() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/reset"))));
		peer.wire.advance(50);

		assertEquals("the section is held", 0, heads.size());
		assertEquals(State.READY, peer.h3.state());
		assertFalse(containsInstruction(peer.decoderStream(), new StreamCancellation(request.id())));

		request.reset(Http3Errors.H3_REQUEST_CANCELLED);
		byte[] cancellation = encoded(new StreamCancellation(request.id()));
		peer.wire.driveUntil(() -> contains(peer.decoderStream(), cancellation));

		assertTrue("FR-025: a held, unacknowledged section whose stream is gone", contains(peer.decoderStream(), cancellation));
		assertFalse("nothing ever decoded, so nothing may be acknowledged",
			containsInstruction(peer.decoderStream(), new SectionAcknowledgment(request.id())));
		assertEquals("one stream's reset is not the connection's problem", State.READY, peer.h3.state());
		assertEquals(0, heads.size());
	}

	/**
	 * FR-025's exclusion, for a held section: {@link Http3Connection#close()} transitions to
	 * {@code CLOSED} <b>before</b> aborting the streams it owns, so the aborts it causes emit nothing.
	 * There is no table left for the peer to release anything from, and the decoder stream is on its
	 * way out with them.
	 * <p>
	 * The buffer the held section was holding is still owed a recycle, and {@link ByteBufRule} is what
	 * says whether it got one — that half of FR-035 is exactly what a close path forgets.
	 */
	@Test
	public void aConnectionClosedWithASectionHeldReleasesItAndCancelsNothing() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/closing"))));
		peer.wire.advance(50);

		assertEquals("the section is held", 0, heads.size());
		assertEquals(State.READY, peer.h3.state());
		int before = peer.decoderStream().length;

		peer.h3.close();
		peer.wire.advance(50);

		assertEquals(State.CLOSED, peer.h3.state());
		assertEquals("no decoder-stream byte after the close (FR-025)", before, peer.decoderStream().length);
		assertFalse(containsInstruction(peer.decoderStream(), new StreamCancellation(request.id())));
	}

	// ---------------------------------------------------------------- T050: the decode timeout

	/**
	 * FR-036: a peer may legally block a stream and then simply stop sending, so a held section has a
	 * bounded lifetime — after which the <b>connection</b> closes with {@code QPACK_DECOMPRESSION_FAILED}
	 * (research D-4), because a decoder that silently dropped the section instead would leave the two
	 * tables disagreeing about what was decoded.
	 * <p>
	 * The section's buffer is owed a recycle on this path too, and {@link ByteBufRule} is what says so.
	 */
	@Test
	public void aSectionHeldPastItsTimeoutClosesTheConnection() {
		Peer peer = connect(Duration.ofMillis(100));
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/timeout"))));
		peer.wire.advance(50);

		assertEquals("the section is held, not yet timed out", 0, heads.size());
		assertEquals(State.READY, peer.h3.state());

		peer.wire.driveUntil(() -> peer.h3.state() == State.CLOSED);

		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, peer.h3.closedWithErrorCode());
		assertEquals("a section that timed out never decodes", 0, heads.size());
	}

	/**
	 * The other half of T050, and the bug class it exists to prevent: the timer is <b>cancelled</b> when
	 * the section it was watching is released, not merely outrun. A connection still {@code READY} an
	 * order of magnitude past the deadline is what says the handle was cancelled rather than left armed.
	 */
	@Test
	public void aSectionThatUnblocksInTimeOutlivesItsTimeout() {
		Peer peer = connect(Duration.ofMillis(1000));
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/in-time"))));
		peer.finish(request);
		peer.wire.advance(50);

		assertEquals("the section is held", 0, heads.size());

		peer.deliverAllInstructions();
		peer.wire.driveUntil(() -> !heads.isEmpty());

		assertEquals("/in-time", heads.get(0).getPath());

		peer.wire.advance(10_000);

		assertEquals("the timeout was cancelled with the section, not outrun", State.READY, peer.h3.state());
		assertEquals(-1, peer.h3.closedWithErrorCode());
		assertEquals(0, failures.size());
	}

	// ---------------------------------------------------------------- T053: the counters

	/**
	 * FR-091's entry and exit, on the path they are actually meant to measure: a stream enters the
	 * blocked state when a section of its is held and leaves it when the peer's insertions arrive.
	 * <p>
	 * The two numbers beside them are the ones a consumer cannot compute for itself — how many streams
	 * are blocked <i>now</i>, and how long this one was, which is the head-of-line delay FR-036 bounds.
	 */
	@Test
	public void aHeldSectionEntersAndItsDecodeExitsTheBlockedState() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/counted"))));
		peer.finish(request);
		peer.wire.advance(50);

		assertEquals("one stream blocked: " + entered, 1, entered.size());
		assertEquals(request.id(), entered.get(0).streamId());
		assertEquals("itself included", 1, entered.get(0).blockedStreams());
		assertTrue("a held section holds bytes: " + entered, entered.get(0).heldBytes() > 0);
		assertEquals("nothing has left the blocked state yet: " + exited, 0, exited.size());
		assertEquals(0, refused.size());

		peer.deliverAllInstructions();
		peer.wire.driveUntil(() -> !heads.isEmpty());

		assertEquals(1, exited.size());
		Exited exit = exited.get(0);
		assertEquals(request.id(), exit.streamId());
		assertEquals(QpackBlockedExit.DECODED, exit.exit());
		assertEquals("none left after this one", 0, exit.blockedStreams());
		assertTrue("a duration, not a timestamp: " + exit, exit.blockedMillis() >= 0);
		assertEquals("an exit is not an entry: " + entered, 1, entered.size());
	}

	/** FR-025: the stream carrying a held section is reset, so the section leaves with it. */
	@Test
	public void aResetBlockedStreamExitsAsReset() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/reset-counted"))));
		peer.wire.advance(50);

		assertEquals(1, entered.size());
		assertEquals(0, exited.size());

		request.reset(Http3Errors.H3_REQUEST_CANCELLED);
		peer.wire.driveUntil(() -> !exited.isEmpty());

		assertEquals(1, exited.size());
		assertEquals(request.id(), exited.get(0).streamId());
		assertEquals(QpackBlockedExit.RESET, exited.get(0).exit());
		assertEquals(0, exited.get(0).blockedStreams());
		assertEquals("one stream's reset is not the connection's problem", State.READY, peer.h3.state());
	}

	/** FR-036: the deadline is what ends the block, and the exit says so rather than saying "closing". */
	@Test
	public void aTimedOutSectionExitsAsTimedOut() {
		Peer peer = connect(Duration.ofMillis(100));
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/timeout-counted"))));
		peer.wire.advance(50);

		assertEquals(1, entered.size());
		assertEquals(0, exited.size());

		peer.wire.driveUntil(() -> peer.h3.state() == State.CLOSED);

		assertEquals(1, exited.size());
		assertEquals(request.id(), exited.get(0).streamId());
		assertEquals(QpackBlockedExit.TIMED_OUT, exited.get(0).exit());
		assertTrue("it was blocked for at least its timeout: " + exited,
			exited.get(0).blockedMillis() >= 100);
	}

	/** FR-035's third release: the connection goes, and every section it was holding goes with it. */
	@Test
	public void aConnectionClosedWithASectionHeldExitsAsClosed() {
		Peer peer = connect();
		QuicStream request = peer.openRequestStream();
		peer.write(request, frame(HeadersFrame.TYPE, peer.encode(request.id(), requestFields("/closing-counted"))));
		peer.wire.advance(50);

		assertEquals(1, entered.size());

		peer.h3.close();
		peer.wire.advance(50);

		assertEquals(1, exited.size());
		assertEquals(request.id(), exited.get(0).streamId());
		assertEquals(QpackBlockedExit.CLOSED, exited.get(0).exit());
		assertEquals(0, exited.get(0).blockedStreams());
	}

	/**
	 * FR-034: a peer that blocks more streams than the {@code SETTINGS_QPACK_BLOCKED_STREAMS} it was told
	 * about. The refusal is reported as its own event rather than as an exit, because the section never
	 * entered — and the two numbers beside it are what say <i>which</i> of the three bounds was reached.
	 */
	@Test
	public void aSectionAboveTheCountBoundIsReportedAsRefused() {
		Peer peer = connect(Duration.ofMillis(Http3Settings.create().qpackBlockedStreamTimeoutMillis()), 1);
		QuicStream first = peer.openRequestStream();
		peer.write(first, frame(HeadersFrame.TYPE, peer.encode(first.id(), requestFields("/first-blocked"))));
		peer.wire.advance(50);

		assertEquals(1, entered.size());
		assertEquals(0, refused.size());
		assertEquals(State.READY, peer.h3.state());

		QuicStream second = peer.openRequestStream();
		peer.write(second, frame(HeadersFrame.TYPE, peer.encode(second.id(), requestFields("/second-blocked"))));
		peer.wire.driveUntil(() -> peer.h3.state() == State.CLOSED);

		assertEquals("the second section was refused, not held: " + refused, 1, refused.size());
		assertEquals(second.id(), refused.get(0).streamId());
		assertEquals("the count bound was full without it", 1, refused.get(0).blockedStreams());
		assertEquals("a refused section never entered: " + entered, 1, entered.size());
		assertEquals(Http3Errors.QPACK_DECOMPRESSION_FAILED, peer.h3.closedWithErrorCode());
	}

	// ---------------------------------------------------------------- the endpoint under test

	private Peer connect() {
		return connect(Duration.ofMillis(Http3Settings.create().qpackBlockedStreamTimeoutMillis()));
	}

	private Peer connect(Duration blockedStreamTimeout) {
		return connect(blockedStreamTimeout, BLOCKED_STREAMS);
	}

	/**
	 * @param blockedStreams what the endpoint under test advertises. The peer's encoder is built with
	 *                       {@link #BLOCKED_STREAMS} whatever this says, so lowering it here produces a
	 *                       peer that blocks more streams than it was permitted — FR-034's case.
	 */
	private Peer connect(Duration blockedStreamTimeout, int blockedStreams) {
		Http3Settings settings = Http3Settings.builder()
			.withQpackMaxTableCapacity(CAPACITY)
			.withQpackBlockedStreams(blockedStreams)
			.withQpackBlockedStreamTimeout(blockedStreamTimeout)
			.build();
		Http3Connection[] captured = new Http3Connection[1];
		Http3WirePair wire = new Http3WirePair(loop)
			.withClientStreamListener(stream -> {
				Recorded recorded = new Recorded(stream);
				localStreams.add(recorded);
				collect(recorded);
			})
			.withServerHandlerFactory(connection -> {
				Http3Connection h3 = Http3Connection.builder(Reactor.getCurrentReactor(), connection)
					.withSettings(settings)
					.withRequestStreamListener(this::serve)
					.withEventListener(new CountingEventListener())
					.build();
				captured[0] = h3;
				h3.start();
				return h3.streamManager();
			})
			.connect();
		wires.add(wire);

		Peer peer = new Peer(wire, captured[0]);
		// The peer advertises capacity 0, so the endpoint under test opens no encoder stream of its own:
		// only its decoder — the direction US2 is about — is dynamic.
		peer.openUnidirectional(Http3StreamType.CONTROL.code(),
			settingsFrame(new long[]{QPACK_MAX_TABLE_CAPACITY}, new long[]{0}));
		wire.driveUntil(() -> peer.h3.state() == State.READY);
		return peer;
	}

	/**
	 * Reads the request head and then its whole body, which is what carries a trailing section all the
	 * way to {@link Http3Trailers#get} (a trailer is decoded by the body reader, not by the head).
	 */
	private void serve(Http3RequestStream stream) {
		streamsAdopted++;
		stream.receiveRequest()
			.whenResult(heads::add)
			.then(request -> request.loadBody().map($ -> request))
			.whenComplete((request, e) -> {
				if (e != null) {
					failures.add(e);
					return;
				}
				trailers.add(Http3Trailers.get(request));
				stream.sendResponse(HttpResponse.ok200().build());
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

	// ---------------------------------------------------------------- the hand-driven peer

	/**
	 * The client half: a real QUIC connection with a real {@link QpackDynamicEncoder} on it, driven by
	 * hand so that a test decides when the encoder stream is written relative to the request stream.
	 */
	private final class Peer {
		private final Http3WirePair wire;
		private final Http3Connection h3;
		private final QpackDynamicEncoder encoder =
			new QpackDynamicEncoder(CAPACITY.toInt(), CAPACITY.toInt(), BLOCKED_STREAMS, 16, Set.of());

		/**
		 * Two write chains, because two writes on one CSP consumer may only be issued in order — the
		 * second queued behind the first's promise. Which is also the point: the request stream and the
		 * encoder stream are chained <b>separately</b>, so nothing orders them against each other. That
		 * is precisely the reordering US2 exists for.
		 */
		private Promise<Void> requestChain = Promise.complete();
		private Promise<Void> encoderChain = Promise.complete();

		private @Nullable QuicStream encoderStream;

		private Peer(Http3WirePair wire, Http3Connection h3) {
			this.wire = wire;
			this.h3 = h3;
		}

		// ------------------------------------------------------------ encoding

		/** A section that inserts and references, and therefore blocks until its instructions arrive. */
		byte[] encode(long streamId, List<Field> fields) {
			ByteBuf section = encoder.encode(streamId, Http3Headers.toQpack(fields));
			try {
				return section.getArray();
			} finally {
				section.recycle();
			}
		}

		/** A section that inserts and references nothing — {@link QpackDynamicEncoder#NO_STREAM}. */
		byte[] encodeStatically(List<Field> fields) {
			return encode(QpackDynamicEncoder.NO_STREAM, fields);
		}

		List<EncoderInstruction> drainInstructions() {
			return encoder.drainPendingInstructions();
		}

		// ------------------------------------------------------------ the encoder stream

		/** Opens the one QPACK encoder stream this peer may open (RFC 9204 §4.2) with {@code instructions}. */
		void openEncoderStream(List<EncoderInstruction> instructions) {
			QuicStream stream = wire.openNow(wire.clientStreams().openUnidirectional());
			encoderStream = stream;
			ByteBuf preamble = concat(streamHeader(Http3StreamType.QPACK_ENCODER.code()),
				instructionBuffer(instructions));
			encoderChain = chain(encoderChain, stream, preamble);
		}

		void writeToEncoderStream(List<EncoderInstruction> instructions) {
			if (encoderStream == null) {
				openEncoderStream(instructions);
				return;
			}
			encoderChain = chain(encoderChain, encoderStream, instructionBuffer(instructions));
		}

		/** Everything the encoder has accumulated so far, in one go — the unblocking event. */
		void deliverAllInstructions() {
			writeToEncoderStream(drainInstructions());
		}

		// ------------------------------------------------------------ streams

		QuicStream openRequestStream() {
			return wire.openNow(wire.clientStreams().openBidirectional());
		}

		QuicStream openUnidirectional(long streamType, ByteBuf payload) {
			QuicStream stream = wire.openNow(wire.clientStreams().openUnidirectional());
			stream.writer().accept(concat(streamHeader(streamType), payload));
			return stream;
		}

		void write(QuicStream stream, ByteBuf buf) {
			requestChain = chain(requestChain, stream, buf);
		}

		void finish(QuicStream stream) {
			requestChain = requestChain.then($ -> stream.writer().accept(null), Promise::ofException);
		}

		/** Takes ownership of {@code buf} on every path, a failed chain included (DI-1). */
		private static Promise<Void> chain(Promise<Void> previous, QuicStream stream, ByteBuf buf) {
			return previous.then(
				$ -> stream.writer().accept(buf),
				e -> {
					buf.recycle();
					return Promise.ofException(e);
				});
		}

		/** Every byte the endpoint under test has written on its QPACK decoder stream, payload only. */
		byte[] decoderStream() {
			for (Recorded recorded : localStreams) {
				if (recorded.type() == Http3StreamType.QPACK_DECODER.code()) return recorded.payload();
			}
			return new byte[0];
		}
	}

	// ---------------------------------------------------------------- T053: what the owner is told

	private record Entered(long streamId, int blockedStreams, long heldBytes) {}

	private record Exited(long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams) {}

	private record Refused(long streamId, int blockedStreams, long heldBytes) {}

	/**
	 * The seam an {@link Http3Server} or an {@link Http3Client} forwards to its {@code Inspector} — taken
	 * here instead of an {@code Inspector} because a blocked section needs a peer that writes its request
	 * stream before the encoder stream it depends on, which neither of those two ever does to itself.
	 */
	private final class CountingEventListener implements Http3EventListener {
		@Override
		public void onQpackStreamBlocked(long streamId, int blockedStreams, long heldBytes) {
			entered.add(new Entered(streamId, blockedStreams, heldBytes));
		}

		@Override
		public void onQpackStreamUnblocked(
			long streamId, QpackBlockedExit exit, long blockedMillis, int blockedStreams
		) {
			exited.add(new Exited(streamId, exit, blockedMillis, blockedStreams));
		}

		@Override
		public void onQpackBlockedSectionRefused(long streamId, int blockedStreams, long heldBytes) {
			refused.add(new Refused(streamId, blockedStreams, heldBytes));
		}
	}

	// ---------------------------------------------------------------- helpers

	private static List<Field> requestFields(String path) {
		return List.of(
			new Field(":method", "GET"),
			new Field(":scheme", "https"),
			new Field(":authority", "example.test"),
			new Field(":path", path),
			new Field("x-alpha", "alpha"),
			new Field("x-beta", "beta"),
			new Field("x-gamma", "gamma"));
	}

	/** {@code bytes} in a pooled buffer, for handing to a codec that owns its input. */
	private static ByteBuf pooled(byte[] bytes) {
		ByteBuf buf = ByteBufPool.allocate(Math.max(1, bytes.length));
		buf.put(bytes);
		return buf;
	}

	/** {@code instructions} back to back in one owned buffer — the peer's encoder-stream bytes. */
	private static ByteBuf instructionBuffer(List<EncoderInstruction> instructions) {
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

	private static boolean containsInstruction(byte[] haystack, QpackInstructions.Instruction instruction) {
		return contains(haystack, encoded(instruction));
	}

	private static byte[] encoded(QpackInstructions.Instruction instruction) {
		return QpackInstructions.encode(instruction).asArray();
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

	/**
	 * Every byte one stream ever delivered, copied out of the pool as it arrives — so an assertion may
	 * look at the same prefix twice and {@link ByteBufRule} still sees every buffer recycled.
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
	}
}
