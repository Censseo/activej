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

package io.activej.http3.qpack;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.http.HttpHeaders;
import io.activej.http3.Http3Settings;
import io.activej.http3.qpack.QpackDynamicDecoder.Blocked;
import io.activej.http3.qpack.QpackDynamicDecoder.Decoded;
import io.activej.http3.qpack.QpackDynamicDecoder.SectionResult;
import io.activej.http3.qpack.QpackInstructions.DecoderInstruction;
import io.activej.http3.qpack.QpackInstructions.EncoderInstruction;
import io.activej.http3.qpack.QpackInstructions.InsertCountIncrement;
import io.activej.http3.qpack.QpackInstructions.Instruction;
import io.activej.http3.qpack.QpackInstructions.SectionAcknowledgment;
import io.activej.test.rules.ByteBufRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.activej.bytebuf.ByteBufStrings.encodeAscii;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * T147 — the per-connection allocation ceiling the spec's performance table states: <b>two</b> dynamic
 * tables, each bounded by the configured capacity, plus the two QPACK stream buffers, and
 * <b>no per-request allocation proportional to table size</b>. Framework consumers pay this per
 * connection, so it has to be a number rather than an intention.
 * <p>
 * Four things are asserted, each about a different way the ceiling could be broken:
 * <ol>
 *     <li>each codec declares exactly one {@link QpackDynamicTable} field, so a connection holds two
 *     and not one per request or one per stream;</li>
 *     <li>neither table object is ever <i>replaced</i> across hundreds of requests, and neither ever
 *     exceeds its capacity — the ring inside grows by doubling to the entry count the capacity
 *     admits, and stops;</li>
 *     <li>the number of pooled buffers one request costs is <b>exactly constant</b> once the table is
 *     primed, so nothing accumulates with request count;</li>
 *     <li>and that same constant does not change when the table holds forty times as many entries,
 *     which is the "proportional to table size" clause stated as a measurement.</li>
 * </ol>
 *
 * <h2>How allocation is counted</h2>
 * {@code ByteBufPool.getStats().getCreatedItems() + getReusedItems()} is the number of
 * {@link ByteBufPool#allocate} calls that went through a slab — this project's own accounting, no new
 * dependency, and exact rather than sampled. It is only maintained when {@code ByteBufPool.stats} is
 * on, which the root {@code pom.xml} sets for Surefire; a bare IDE run without it skips the two
 * counting tests rather than passing them vacuously.
 *
 * <h2>{@code ByteBuf} ownership</h2>
 * As {@code QpackDynamicRoundTripTest}: {@link QpackEncoderStreamReader#feed} and
 * {@link QpackDecoderStreamReader#feed} own their input on every path including a throw;
 * {@link QpackDynamicDecoder#decodeOrBlock} owns its input except on a {@link Blocked} result, which
 * hands it back.
 */
public class QpackAllocationCeilingTest {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	/** The capacity SC-001 and the spec's performance table both name. */
	private static final int CAPACITY = 4096;

	/** Wide enough to hold {@link #FILLER_ENTRIES} plus a browser request without evicting either. */
	private static final int WIDE_CAPACITY = 65_536;

	private static final int FILLER_ENTRIES = 600;

	/** Enough requests that anything accumulating per request would be unmistakable. */
	private static final int REQUESTS = 400;

	/**
	 * Requests 1..{@code WARM} may still be priming the table; the constant-cost claim is about the
	 * steady state that follows, which is where a connection spends its life.
	 */
	private static final int WARM = 8;

	private static final int BLOCKED_STREAMS = Http3Settings.DEFAULT_QPACK_BLOCKED_STREAMS;
	private static final long MAX_INSTRUCTION_SIZE = Http3Settings.DEFAULT_QPACK_MAX_INSTRUCTION_SIZE.toLong();
	private static final int MAX_OUTSTANDING_SECTIONS = 64;
	private static final long UNBOUNDED_FIELD_SECTION = Long.MAX_VALUE;

	// region the two tables

	/**
	 * One table per codec, so two per connection — the number the ceiling is stated in. A second table
	 * field on either codec, or a table created per section, would double the per-connection cost
	 * without changing a single behavioural test.
	 */
	@Test
	public void eachCodecDeclaresExactlyOneDynamicTable() {
		assertEquals("QpackDynamicEncoder must hold exactly one dynamic table",
			1, dynamicTableFields(QpackDynamicEncoder.class).size());
		assertEquals("QpackDynamicDecoder must hold exactly one dynamic table",
			1, dynamicTableFields(QpackDynamicDecoder.class).size());
	}

	/**
	 * Neither table is ever replaced, neither ever exceeds its capacity, and nothing is left pinned or
	 * buffered between requests — over {@link #REQUESTS} exchanges on one connection.
	 */
	@Test
	public void neitherTableIsReplacedNorGrowsPastCapacityAcrossManyRequests() throws Exception {
		try (Connection connection = new Connection(CAPACITY)) {
			QpackDynamicTable encoderTable = encoderTable(connection.encoder);
			QpackDynamicTable decoderTable = connection.decoder.table();

			List<QpackField> request = browserRequest();
			for (int i = 0; i < REQUESTS; i++) {
				connection.exchange(4L * i, request);

				assertSame("request #" + i + " replaced the encoder's table",
					encoderTable, encoderTable(connection.encoder));
				assertSame("request #" + i + " replaced the decoder's table",
					decoderTable, connection.decoder.table());
				assertTrue("request #" + i + ": encoder table " + connection.encoder.tableSize() +
						   " B is over the " + CAPACITY + " B capacity",
					connection.encoder.tableSize() <= CAPACITY);
				assertTrue("request #" + i + ": decoder table " + connection.decoder.tableSize() +
						   " B is over the " + CAPACITY + " B capacity",
					connection.decoder.tableSize() <= CAPACITY);

				// Nothing is held between requests: no stream blocked, no section left pinned, and
				// neither stream reader part-way through an instruction.
				assertEquals("request #" + i + " left a stream blocked", 0, connection.encoder.blockedStreamCount());
				assertEquals("request #" + i + " left encoder-stream bytes buffered",
					0, connection.encoderStream.pendingBytes());
				assertEquals("request #" + i + " left decoder-stream bytes buffered",
					0, connection.decoderStream.pendingBytes());
			}

			// The steady state is a primed table, not a growing one: every request after the first few
			// referenced what was already there.
			assertEquals("the two tables disagree about what was inserted",
				connection.encoder.insertCount(), connection.decoder.insertCount());
			assertTrue("a repeated request must stop inserting once the table holds it, but insert count " +
					   "reached " + connection.encoder.insertCount() + " over " + REQUESTS + " requests",
				connection.encoder.insertCount() < 2L * request.size());
		}
	}

	// endregion
	// region per-request allocation

	/**
	 * The number of pooled buffers one request costs is <b>exactly</b> the same for request
	 * {@link #WARM} and for request {@link #REQUESTS}. Not bounded, not on average — identical, which
	 * is the only shape that rules out a slow accumulation.
	 */
	@Test
	public void pooledBuffersPerRequestAreConstantOnceTheTableIsPrimed() throws Exception {
		try (Connection connection = new Connection(CAPACITY)) {
			List<QpackField> request = browserRequest();
			long[] perRequest = new long[REQUESTS];
			for (int i = 0; i < REQUESTS; i++) {
				long before = allocations();
				connection.exchange(4L * i, request);
				perRequest[i] = allocations() - before;
			}

			assumeCountingIsOn(perRequest[0]);

			long steadyState = perRequest[WARM];
			System.out.printf("QPACK per-connection allocation: %d requests at a %d B table cost %d pooled " +
							  "buffers each from request #%d on (request #0 cost %d), table settled at %d B%n",
				REQUESTS, CAPACITY, steadyState, WARM, perRequest[0], connection.encoder.tableSize());
			assertTrue("a request must cost at least one pooled buffer; counting is not working",
				steadyState > 0);
			for (int i = WARM; i < REQUESTS; i++) {
				assertEquals("request #" + i + " cost " + perRequest[i] + " pooled buffers where request #" +
							 WARM + " cost " + steadyState + " — per-request allocation must not grow with " +
							 "request count",
					steadyState, perRequest[i]);
			}
		}
	}

	/**
	 * The "proportional to table size" clause, measured. The same request, in the same steady state,
	 * once against a table holding a browser request's worth of entries and once against one holding
	 * {@link #FILLER_ENTRIES} more — roughly forty times as many. An index lookup allocates nothing, so
	 * the per-request cost must not move; anything proportional would be forty times larger.
	 * <p>
	 * The encoded field section is compared too, and for a different reason: it is exact, it needs no
	 * allocator at all, and a dynamic reference into a table forty times larger may legitimately cost a
	 * byte or two more varint — but only that.
	 */
	@Test
	public void perRequestCostDoesNotGrowWithTableOccupancy() throws Exception {
		Measurement narrow = steadyState(CAPACITY, 0);
		Measurement wide = steadyState(WIDE_CAPACITY, FILLER_ENTRIES);

		assumeCountingIsOn(narrow.pooledBuffers());
		System.out.printf("QPACK per-request cost against table occupancy: %d entries -> %d pooled buffers, " +
						  "%d B field section; %d entries -> %d pooled buffers, %d B field section%n",
			narrow.tableEntries(), narrow.pooledBuffers(), narrow.fieldSectionBytes(),
			wide.tableEntries(), wide.pooledBuffers(), wide.fieldSectionBytes());

		assertTrue("the wide run must actually hold a much larger table, held " + wide.tableEntries() +
				   " entries against " + narrow.tableEntries(),
			wide.tableEntries() > narrow.tableEntries() * 10);

		assertTrue("a request cost " + wide.pooledBuffers() + " pooled buffers against a table of " +
				   wide.tableEntries() + " entries and " + narrow.pooledBuffers() + " against one of " +
				   narrow.tableEntries() + " — per-request allocation must not scale with table size",
			wide.pooledBuffers() <= narrow.pooledBuffers() + 1);

		assertTrue("the field section grew from " + narrow.fieldSectionBytes() + " B to " +
				   wide.fieldSectionBytes() + " B against a table " +
				   (wide.tableEntries() / Math.max(narrow.tableEntries(), 1)) + " times larger — a dynamic " +
				   "reference is an index, not a copy",
			wide.fieldSectionBytes() <= narrow.fieldSectionBytes() + 8);
	}

	// endregion
	// region harness

	/** One steady-state request's cost, plus how many entries the table held while it was measured. */
	private record Measurement(long pooledBuffers, int fieldSectionBytes, int tableEntries) {}

	/**
	 * Primes a connection with {@code fillerEntries} unrelated entries, then repeats one browser
	 * request until the encoder has stopped inserting, and measures the request after that.
	 */
	private static Measurement steadyState(int capacity, int fillerEntries) throws QpackException {
		try (Connection connection = new Connection(capacity)) {
			long streamId = 0;
			for (List<QpackField> filler : fillerSections(fillerEntries)) {
				connection.exchange(streamId += 4, filler);
			}

			List<QpackField> request = browserRequest();
			for (int i = 0; i < WARM; i++) {
				connection.exchange(streamId += 4, request);
			}

			long before = allocations();
			int fieldSectionBytes = connection.exchange(streamId + 4, request);
			long pooledBuffers = allocations() - before;

			return new Measurement(pooledBuffers, fieldSectionBytes, encoderTable(connection.encoder).entryCount());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** {@code allocate()} calls that went through a slab, as this project already counts them. */
	private static long allocations() {
		ByteBufPool.ByteBufPoolStats stats = ByteBufPool.getStats();
		return (long) stats.getCreatedItems() + stats.getReusedItems();
	}

	private static void assumeCountingIsOn(long observed) {
		assumeTrue("ByteBufPool.stats must be on for allocation counting to mean anything; " +
				   "the root pom sets it for Surefire", observed > 0);
	}

	private static List<Field> dynamicTableFields(Class<?> codec) {
		List<Field> fields = new ArrayList<>();
		for (Field field : codec.getDeclaredFields()) {
			if (field.getType() == QpackDynamicTable.class) fields.add(field);
		}
		return fields;
	}

	private static QpackDynamicTable encoderTable(QpackDynamicEncoder encoder) throws ReflectiveOperationException {
		Field field = QpackDynamicEncoder.class.getDeclaredField("table");
		field.setAccessible(true);
		return (QpackDynamicTable) field.get(encoder);
	}

	/**
	 * One encoder, the decoder that must understand it, and <b>both</b> QPACK stream readers — the two
	 * stream buffers the ceiling names, each holding at most one partial instruction.
	 */
	private static final class Connection implements AutoCloseable {
		final QpackDynamicEncoder encoder;
		final QpackDynamicDecoder decoder;
		final QpackEncoderStreamReader encoderStream;
		final QpackDecoderStreamReader decoderStream;

		Connection(int capacity) {
			this.encoder = new QpackDynamicEncoder(capacity, capacity, BLOCKED_STREAMS,
				MAX_OUTSTANDING_SECTIONS, Http3Settings.DEFAULT_QPACK_NEVER_INDEXED_FIELDS);
			this.decoder = new QpackDynamicDecoder(capacity, BLOCKED_STREAMS, UNBOUNDED_FIELD_SECTION);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
			this.decoderStream = new QpackDecoderStreamReader(encoder, MAX_INSTRUCTION_SIZE);
		}

		/** @return the encoded field section's wire size */
		int exchange(long streamId, List<QpackField> fields) throws QpackException {
			ByteBuf section = encoder.encode(streamId, fields);
			int fieldSectionBytes = section.readRemaining();
			try {
				// Instructions first (research D-2), so nothing ever blocks.
				deliverEncoderInstructions();
			} catch (QpackException | RuntimeException | Error e) {
				section.recycle();
				throw e;
			}

			SectionResult result = decoder.decodeOrBlock(section);
			if (result instanceof Blocked blocked) {
				blocked.section().recycle();
				throw new AssertionError("a section blocked although every insertion was delivered first");
			}
			Decoded decoded = (Decoded) result;
			if (!fields.equals(decoded.fields())) {
				throw new AssertionError("the round trip did not reproduce the field section");
			}
			deliverDecoderInstructions(streamId, decoded.requiredInsertCount());
			return fieldSectionBytes;
		}

		private void deliverEncoderInstructions() throws QpackException {
			List<EncoderInstruction> instructions = encoder.drainPendingInstructions();
			if (instructions.isEmpty()) return;
			// feed owns the buffer on every path, a throw included.
			encoderStream.feed(write(instructions));
		}

		private void deliverDecoderInstructions(long streamId, long requiredInsertCount) throws QpackException {
			List<DecoderInstruction> instructions = new ArrayList<>(2);
			if (requiredInsertCount > 0) {
				instructions.add(new SectionAcknowledgment(streamId));
				decoder.onInsertCountAnnounced(requiredInsertCount);
			}
			long increment = decoder.pendingInsertCountIncrement();
			if (increment > 0) {
				instructions.add(new InsertCountIncrement(increment));
				decoder.onInsertCountAnnounced(decoder.insertCount());
			}
			if (instructions.isEmpty()) return;
			decoderStream.feed(write(instructions));
		}

		@Override
		public void close() {
			encoderStream.recycle();
			decoderStream.recycle();
		}

		private static ByteBuf write(List<? extends Instruction> instructions) {
			int length = 0;
			for (Instruction instruction : instructions) length += instruction.encodedLength();
			ByteBuf out = ByteBufPool.allocate(length);
			for (Instruction instruction : instructions) instruction.writeTo(out);
			return out;
		}
	}

	// endregion
	// region the corpus

	/** A field section shaped like a browser's, cookie included — the field the dynamic table is for. */
	private static List<QpackField> browserRequest() {
		List<QpackField> fields = new ArrayList<>(16);
		add(fields, ":method", "GET");
		add(fields, ":scheme", "https");
		add(fields, ":authority", "www.example.com");
		add(fields, ":path", "/index.html");
		add(fields, "user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
								  "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36");
		add(fields, "accept", "text/html,application/xhtml+xml,application/xml;q=0.9," +
							  "image/avif,image/webp,image/apng,*/*;q=0.8");
		add(fields, "accept-encoding", "gzip, deflate, br, zstd");
		add(fields, "accept-language", "en-GB,en-US;q=0.9,en;q=0.8");
		add(fields, "referer", "https://www.example.com/");
		add(fields, "cookie", "session=8f14e45fceea167a5a36dedd4bea2543; " +
							  "_ga=GA1.2.1234567890.1700000000; consent=granted; theme=dark");
		return fields;
	}

	/** Sections of unrelated fields, purely to occupy the table before the request under measurement. */
	private static List<List<QpackField>> fillerSections(int entries) {
		List<List<QpackField>> sections = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		List<QpackField> section = new ArrayList<>();
		for (int i = 0; i < entries; i++) {
			String name = "x-filler-" + i;
			if (!seen.add(name)) continue;
			section.add(new QpackField(HttpHeaders.of(name), encodeAscii("filler-value-" + i)));
			if (section.size() == 8) {
				sections.add(section);
				section = new ArrayList<>();
			}
		}
		if (!section.isEmpty()) sections.add(section);
		return sections;
	}

	private static void add(List<QpackField> fields, String name, String value) {
		fields.add(new QpackField(HttpHeaders.of(name), encodeAscii(value)));
	}

	// endregion
}
