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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * The header-compression benchmark SC-001 requires (feature 006, T145): with a 4 KB QPACK dynamic
 * table, requests 2..N of a fixed browser-like request sequence must encode <b>at least 60 % smaller
 * than request 1</b>.
 *
 * <h2>Running it</h2>
 * The whole {@code benchmarks/} tree is profile-gated, so this needs {@code -P examples}:
 * <pre>{@code
 * mvn -P examples -pl benchmarks/http3 -am -DskipTests compile
 * mvn -P examples -pl benchmarks/http3 exec:java \
 *     -Dexec.mainClass=io.activej.http3.qpack.QpackCompressionBenchmark
 * }</pre>
 * It exits non-zero if the SC-001 claim does not hold, so it is usable as a check and not only as a
 * report. Optional arguments: {@code <capacity-bytes> <requests>}.
 *
 * <h2>What is measured, and against what</h2>
 * Two byte counts per request, because reporting only the first would flatter the encoder:
 * <ul>
 *     <li><b>wire</b> — the field section, the bytes that travel on the request stream. This is what
 *     SC-001 literally names, and what a packet capture of that stream shows.</li>
 *     <li><b>total</b> — the field section <i>plus</i> the encoder-stream bytes that section caused.
 *     The table request 1 primes is paid for on the encoder stream, and that byte count appears
 *     nowhere in the field section; charging it to the request that caused it is what makes the
 *     comparison honest, and it is the accounting {@code QpackDynamicRoundTripTest} already uses.</li>
 * </ul>
 * <b>SC-001's literal framing — field section of requests 2..N against request 1's field section —
 * reads 0 % here, and that is the encoder being better than the criterion assumed, not worse.</b>
 * {@link QpackDynamicEncoder} inserts a field and references the entry it just inserted <i>within the
 * same section</i>, so request 1's field section is already fully indexed (20 B against phase 1's
 * 493 B) and the priming cost sits on the encoder stream instead. There is nothing left for requests
 * 2..N to shrink on that stream. The reduction the criterion is about is therefore asserted twice, on
 * the two comparisons that do carry it:
 * <ul>
 *     <li><b>vs req 1</b> — total cost of requests 2..N against request 1's total cost. This is
 *     SC-001's shape with the encoder stream counted where it belongs.</li>
 *     <li><b>vs phase 1</b> — total cost of requests 2..N against what {@link QpackStaticEncoder}
 *     spends on the very same request. This is the question a consumer actually asks: what does
 *     turning the dynamic table on buy?</li>
 * </ul>
 * The {@link QpackStaticEncoder} row is the control: it has no table to prime, so it spends the same
 * bytes on every request. Without it, a shrinking sequence could be an artifact of the corpus rather
 * than the dynamic table doing its job.
 *
 * <h2>Not a JMH benchmark</h2>
 * The quantity of interest is <b>bytes on the wire</b>, which is exact and deterministic — one run of
 * the sequence answers it completely, and a JMH harness would only add timing noise to a measurement
 * that has none. QPACK <i>throughput</i> is a separate question, bounded by the already-bounded field
 * section size, and is covered by {@code QpackDynamicTableLookupComplexityTest} rather than here.
 *
 * <h2>{@code ByteBuf} ownership</h2>
 * {@link QpackEncoder#encode} hands back a buffer this class owns; {@link QpackDecoder#decode} and
 * {@link QpackDynamicDecoder#decodeOrBlock} own their input on every path except a
 * {@link Blocked} result, which hands it back; {@link QpackEncoderStreamReader#feed} owns its input on
 * every path including a throw; {@link QpackDynamicEncoder#consumeDecoderStream} owns nothing. Every
 * path here recycles exactly once (DI-1).
 */
public final class QpackCompressionBenchmark {
	/** The capacity SC-001 names. */
	private static final int DEFAULT_CAPACITY = 4096;

	/** Enough repetitions for the steady state to dominate, few enough to print. */
	private static final int DEFAULT_REQUESTS = 32;

	/** SC-001's threshold. */
	private static final double TARGET_REDUCTION = 0.60;

	/** The capacities reported for context; {@link #DEFAULT_CAPACITY} is the one SC-001 is asserted at. */
	private static final int[] REPORTED_CAPACITIES = {0, 256, 4096, 65536};

	private static final int BLOCKED_STREAMS = Http3Settings.DEFAULT_QPACK_BLOCKED_STREAMS;
	private static final long MAX_INSTRUCTION_SIZE = Http3Settings.DEFAULT_QPACK_MAX_INSTRUCTION_SIZE.toLong();

	/** Well above what one connection holds outstanding here: every section is acknowledged at once. */
	private static final int MAX_OUTSTANDING_SECTIONS = 64;

	/** A field-section bound generous enough not to be the thing under test. */
	private static final long UNBOUNDED_FIELD_SECTION = Long.MAX_VALUE;

	private QpackCompressionBenchmark() {}

	public static void main(String[] args) throws QpackException {
		int capacity = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_CAPACITY;
		int requests = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REQUESTS;

		List<List<QpackField>> fixedSequence = fixedSequence(requests);
		List<List<QpackField>> browsingSequence = browsingSequence(requests);

		System.out.println("QPACK header compression — SC-001");
		System.out.printf(Locale.ROOT, "  requests=%d, blockedStreams=%d, neverIndexed=%s%n",
			requests, BLOCKED_STREAMS, Http3Settings.DEFAULT_QPACK_NEVER_INDEXED_FIELDS);
		System.out.println();

		report("fixed browser-like request, repeated", fixedSequence);
		System.out.println();
		report("browsing sequence (:path and referer vary)", browsingSequence);
		System.out.println();

		double phaseOneBytes = staticRun(fixedSequence).meanSubsequentTotalBytes();
		Report claim = dynamicRun(capacity, fixedSequence);
		double versusFirst = claim.totalCostReduction();
		double versusPhaseOne = claim.reductionAgainst(phaseOneBytes);
		boolean pass = versusFirst >= TARGET_REDUCTION && versusPhaseOne >= TARGET_REDUCTION;

		System.out.printf(Locale.ROOT,
			"SC-001 at a %d B table: requests 2..%d cost %.1f B against request 1's %d B (%.1f %% smaller) " +
			"and against phase 1's %.1f B (%.1f %% smaller) — %s (target %.0f %%)%n",
			capacity, requests,
			claim.meanSubsequentTotalBytes(), claim.firstTotalBytes(), 100 * versusFirst,
			phaseOneBytes, 100 * versusPhaseOne,
			pass ? "PASS" : "FAIL", 100 * TARGET_REDUCTION);
		System.out.printf(Locale.ROOT,
			"  request 1's own field section is already %d B against phase 1's %d B: this encoder " +
			"references the entries it inserts within the same section, so the priming cost is on the " +
			"encoder stream and not on the request stream%n",
			claim.firstFieldSectionBytes(), staticRun(fixedSequence).firstFieldSectionBytes());

		if (!pass) System.exit(1);
	}

	private static void report(String label, List<List<QpackField>> sequence) throws QpackException {
		System.out.println(label);
		System.out.printf(Locale.ROOT, "  %-28s %10s %11s %14s %15s %10s %11s%n",
			"encoder", "req 1 wire", "req 1 total", "mean 2..N wire", "mean 2..N total",
			"vs req 1", "vs phase 1");

		Report staticRun = staticRun(sequence);
		double phaseOneBytes = staticRun.meanSubsequentTotalBytes();
		System.out.println("  " + staticRun.render(phaseOneBytes));
		for (int capacity : REPORTED_CAPACITIES) {
			System.out.println("  " + dynamicRun(capacity, sequence).render(phaseOneBytes));
		}
	}

	// region measurement

	/** One request's cost: what left on the request stream, and what its encoder stream cost. */
	private record RequestCost(int fieldSectionBytes, int encoderStreamBytes) {
		int totalBytes() {
			return fieldSectionBytes + encoderStreamBytes;
		}
	}

	private record Report(String encoder, List<RequestCost> costs) {
		int firstFieldSectionBytes() {
			return costs.get(0).fieldSectionBytes();
		}

		int firstTotalBytes() {
			return costs.get(0).totalBytes();
		}

		double meanSubsequentFieldSectionBytes() {
			if (costs.size() < 2) return firstFieldSectionBytes();
			long total = 0;
			for (int i = 1; i < costs.size(); i++) total += costs.get(i).fieldSectionBytes();
			return (double) total / (costs.size() - 1);
		}

		double meanSubsequentTotalBytes() {
			if (costs.size() < 2) return firstTotalBytes();
			long total = 0;
			for (int i = 1; i < costs.size(); i++) total += costs.get(i).totalBytes();
			return (double) total / (costs.size() - 1);
		}

		long encoderStreamBytes() {
			long total = 0;
			for (RequestCost cost : costs) total += cost.encoderStreamBytes();
			return total;
		}

		/**
		 * SC-001's shape with the encoder stream counted where it belongs: what requests 2..N cost
		 * against what request 1 cost, both streams included.
		 */
		double totalCostReduction() {
			return reductionAgainst(firstTotalBytes());
		}

		/** What requests 2..N cost against some other per-request baseline — phase 1's, in practice. */
		double reductionAgainst(double baselineBytes) {
			return baselineBytes == 0 ? 0 : 1 - meanSubsequentTotalBytes() / baselineBytes;
		}

		String render(double phaseOneBytes) {
			return String.format(Locale.ROOT, "%-28s %9dB %10dB %13dB %14.1fB %9.1f%% %10.1f%%",
				encoder, firstFieldSectionBytes(), firstTotalBytes(),
				(int) meanSubsequentFieldSectionBytes(), meanSubsequentTotalBytes(),
				100 * totalCostReduction(), 100 * reductionAgainst(phaseOneBytes));
		}
	}

	/** Phase 1's encoder — the control that has no table to prime. */
	private static Report staticRun(List<List<QpackField>> sequence) throws QpackException {
		QpackStaticEncoder encoder = new QpackStaticEncoder();
		QpackStaticDecoder decoder = new QpackStaticDecoder(UNBOUNDED_FIELD_SECTION);

		List<RequestCost> costs = new ArrayList<>(sequence.size());
		for (List<QpackField> fields : sequence) {
			ByteBuf section = encoder.encode(fields);
			int sectionBytes = section.readRemaining();
			// decode owns the buffer on every path.
			assertRoundTrip(fields, decoder.decode(section));
			costs.add(new RequestCost(sectionBytes, 0));
		}
		return new Report("QpackStaticEncoder", costs);
	}

	private static Report dynamicRun(int capacity, List<List<QpackField>> sequence) throws QpackException {
		Connection connection = new Connection(capacity);
		try {
			List<RequestCost> costs = new ArrayList<>(sequence.size());
			for (int i = 0; i < sequence.size(); i++) {
				// RFC 9000 §2.1 spaces client-initiated bidirectional stream ids 4 apart.
				costs.add(connection.exchange(4L * i, sequence.get(i)));
			}
			return new Report("QpackDynamicEncoder(" + capacity + "B)", costs);
		} finally {
			connection.recycle();
		}
	}

	/**
	 * One encoder, the decoder that must understand it, and the two QPACK unidirectional streams
	 * between them carried as real wire bytes rather than as a method call between two objects that
	 * happen to agree.
	 * <p>
	 * Instructions are delivered <b>before</b> the field section that references them (research D-2's
	 * discipline), which is what {@code Http3Connection} does and what keeps a section from ever
	 * blocking.
	 */
	private static final class Connection {
		private final QpackDynamicEncoder encoder;
		private final QpackDynamicDecoder decoder;
		private final QpackEncoderStreamReader encoderStream;

		Connection(int capacity) {
			this.encoder = new QpackDynamicEncoder(capacity, capacity, BLOCKED_STREAMS,
				MAX_OUTSTANDING_SECTIONS, Http3Settings.DEFAULT_QPACK_NEVER_INDEXED_FIELDS);
			this.decoder = new QpackDynamicDecoder(capacity, BLOCKED_STREAMS, UNBOUNDED_FIELD_SECTION);
			this.encoderStream = new QpackEncoderStreamReader(decoder, MAX_INSTRUCTION_SIZE);
		}

		RequestCost exchange(long streamId, List<QpackField> fields) throws QpackException {
			ByteBuf section = encoder.encode(streamId, fields);
			int sectionBytes = section.readRemaining();

			int instructionBytes;
			try {
				instructionBytes = deliverEncoderInstructions();
			} catch (QpackException | RuntimeException | Error e) {
				// Nothing has taken ownership yet: decodeOrBlock is what would have recycled it.
				section.recycle();
				throw e;
			}

			SectionResult result = decoder.decodeOrBlock(section);
			if (result instanceof Blocked blocked) {
				blocked.section().recycle();
				throw new AssertionError("a section blocked although every insertion was delivered first, " +
										 "at Required Insert Count " + blocked.requiredInsertCount());
			}
			Decoded decoded = (Decoded) result;
			assertRoundTrip(fields, decoded.fields());
			deliverDecoderInstructions(streamId, decoded.requiredInsertCount());

			return new RequestCost(sectionBytes, instructionBytes);
		}

		private int deliverEncoderInstructions() throws QpackException {
			List<EncoderInstruction> instructions = encoder.drainPendingInstructions();
			if (instructions.isEmpty()) return 0;
			ByteBuf buf = write(instructions);
			int bytes = buf.readRemaining();
			// feed owns buf on every path, a throw included.
			encoderStream.feed(buf);
			return bytes;
		}

		private void deliverDecoderInstructions(long streamId, long requiredInsertCount) throws QpackException {
			List<DecoderInstruction> instructions = new ArrayList<>(2);
			if (requiredInsertCount > 0) {
				// RFC 9204 §4.4.1 acknowledges every insertion up to the section's Required Insert Count.
				instructions.add(new SectionAcknowledgment(streamId));
				decoder.onInsertCountAnnounced(requiredInsertCount);
			}
			long increment = decoder.pendingInsertCountIncrement();
			if (increment > 0) {
				instructions.add(new InsertCountIncrement(increment));
				decoder.onInsertCountAnnounced(decoder.insertCount());
			}
			if (instructions.isEmpty()) return;

			ByteBuf buf = write(instructions);
			try {
				encoder.consumeDecoderStream(buf);
			} finally {
				buf.recycle();
			}
		}

		void recycle() {
			encoderStream.recycle();
		}

		private static ByteBuf write(List<? extends Instruction> instructions) {
			int length = 0;
			for (Instruction instruction : instructions) length += instruction.encodedLength();
			ByteBuf out = ByteBufPool.allocate(length);
			for (Instruction instruction : instructions) instruction.writeTo(out);
			return out;
		}
	}

	/**
	 * A compression number measured against an encoder that does not reproduce the field section is
	 * worth nothing, so every section this benchmark counts is decoded back and compared.
	 */
	private static void assertRoundTrip(List<QpackField> expected, List<QpackField> actual) {
		if (expected.equals(actual)) return;
		throw new AssertionError("QPACK round trip did not reproduce the field section: expected " +
								 expected + ", got " + actual);
	}

	// endregion
	// region the corpus

	/**
	 * SC-001's shape: <b>one</b> browser-like request, repeated. Request 1 primes the table and pays
	 * for every insertion on the encoder stream; requests 2..N reference what it primed.
	 */
	private static List<List<QpackField>> fixedSequence(int requests) {
		List<List<QpackField>> sequence = new ArrayList<>(requests);
		for (int i = 0; i < requests; i++) {
			sequence.add(browserRequest("/index.html", "https://www.example.com/"));
		}
		return sequence;
	}

	/**
	 * The same connection loading a page: {@code :path} and {@code referer} change per request while
	 * everything a browser repeats — user agent, accept headers, the cookie — does not. Closer to real
	 * traffic than the fixed sequence, and reported alongside it so the SC-001 number is not read as
	 * the best case only.
	 */
	private static List<List<QpackField>> browsingSequence(int requests) {
		List<List<QpackField>> sequence = new ArrayList<>(requests);
		sequence.add(browserRequest("/index.html", "https://www.example.com/"));
		for (int i = 1; i < requests; i++) {
			sequence.add(browserRequest("/static/asset-" + i + ".js", "https://www.example.com/index.html"));
		}
		return sequence;
	}

	/** A field section shaped like Chrome's, cookie included — the field the dynamic table is for. */
	private static List<QpackField> browserRequest(String path, String referer) {
		List<QpackField> fields = new ArrayList<>(16);
		add(fields, ":method", "GET");
		add(fields, ":scheme", "https");
		add(fields, ":authority", "www.example.com");
		add(fields, ":path", path);
		add(fields, "sec-ch-ua", "\"Chromium\";v=\"145\", \"Not(A:Brand\";v=\"24\"");
		add(fields, "sec-ch-ua-mobile", "?0");
		add(fields, "sec-ch-ua-platform", "\"Linux\"");
		add(fields, "upgrade-insecure-requests", "1");
		add(fields, "user-agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
								  "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36");
		add(fields, "accept", "text/html,application/xhtml+xml,application/xml;q=0.9," +
							  "image/avif,image/webp,image/apng,*/*;q=0.8");
		add(fields, "sec-fetch-site", "same-origin");
		add(fields, "sec-fetch-mode", "navigate");
		add(fields, "sec-fetch-dest", "document");
		add(fields, "referer", referer);
		add(fields, "accept-encoding", "gzip, deflate, br, zstd");
		add(fields, "accept-language", "en-GB,en-US;q=0.9,en;q=0.8");
		add(fields, "cookie", "session=8f14e45fceea167a5a36dedd4bea2543; " +
							  "_ga=GA1.2.1234567890.1700000000; _gid=GA1.2.9876543210.1700000000; " +
							  "consent=granted; theme=dark");
		return fields;
	}

	private static void add(List<QpackField> fields, String name, String value) {
		fields.add(new QpackField(HttpHeaders.of(name), value.getBytes(US_ASCII)));
	}

	// endregion
}
