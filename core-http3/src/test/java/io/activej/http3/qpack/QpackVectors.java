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
import io.activej.http.HttpHeaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Loads a directory of QPACK test vectors — the RFC 9204 Appendix B corpus T002 transcribed under
 * {@code src/test/resources/qpack/rfc9204-appendix-b/}, and anything written in the same format —
 * into {@code (capacity, blockedStreams, encodedSection, expectedFields)} tuples, so a vector is
 * data a test drives rather than a hand-transcribed byte array in a test method (T004).
 * <p>
 * There is no reactor, no {@code Promise} and no eventloop here, matching the package it lives in
 * (ADR-016): a vector is parsed, decoded and asserted synchronously. Parsing uses nothing but the
 * JDK — the zero-third-party rule holds in test scope too, so there is no JSON or YAML here and
 * none is wanted.
 *
 * <h2>Appendix B is one cumulative exchange, not five independent vectors</h2>
 * B.1–B.5 are successive steps of a <b>single connection</b>: B.2's field section references entries
 * B.2's own encoder stream inserted, B.4 duplicates an entry B.2 inserted, B.5 evicts it.
 * {@link #rfc9204AppendixB()} therefore returns them in <b>series order</b>, taken from the
 * corpus's {@code index.txt}, and a test must replay them against <b>one</b> decoder in that order.
 * Each vector's {@link QpackVector#deliveryOrder()} says which of its three byte streams to feed and
 * in what order; {@link QpackVector#expectedTable()} and {@link QpackVector#expectedState()} are the
 * table and counters the RFC shows <i>after</i> that step.
 *
 * <h2>Fixture format</h2>
 * One UTF-8 {@code .vec} file per vector, plus an {@code index.txt} listing the file names in series
 * order — the index exists because a classpath directory is not portably listable once the resources
 * are inside a jar. Whole-line {@code #} comments and blank lines are ignored everywhere; there are
 * no inline comments, since {@code #} is legal inside a field value.
 * <p>
 * A file is a block of {@code key: value} header lines, then {@code [section]} blocks:
 *
 * <pre>{@code
 * name: B.1
 * title: Literal Field Line with Name Reference
 * max-table-capacity: 220
 * blocked-streams: 0
 * delivery-order: field-section
 *
 * [field-section]
 * # Required Insert Count = 0, Base = 0
 * 00 00
 * 51 0b
 * 2f 69 6e 64 65 78 2e 68 74 6d 6c
 *
 * [expected-fields]
 * :path: /index.html
 * }</pre>
 *
 * <table>
 *     <caption>Sections</caption>
 *     <tr><th>Section</th><th>Line shape</th><th>Maps to</th></tr>
 *     <tr>
 *         <td>{@code [encoder-stream]}</td><td rowspan="3">hex octets, any whitespace, any number
 *         of lines, concatenated in order</td><td>{@link QpackVector#encoderStream()}</td>
 *     </tr>
 *     <tr><td>{@code [field-section]}</td><td>{@link QpackVector#encodedSection()}</td></tr>
 *     <tr><td>{@code [decoder-stream]}</td><td>{@link QpackVector#decoderStream()}</td></tr>
 *     <tr>
 *         <td>{@code [expected-fields]}</td><td>{@code name: value}, in wire order</td>
 *         <td>{@link QpackVector#expectedFields()}</td>
 *     </tr>
 *     <tr>
 *         <td>{@code [expected-table]}</td><td>{@code <abs> <ref> <name> <value…>}</td>
 *         <td>{@link QpackVector#expectedTable()}</td>
 *     </tr>
 *     <tr>
 *         <td>{@code [expected-state]}</td><td>{@code insert-count:} / {@code known-received-count:}
 *         / {@code size:}</td><td>{@link QpackVector#expectedState()}</td>
 *     </tr>
 * </table>
 *
 * An absent section is an empty one. An unknown section fails the load — a section is structural,
 * and silently skipping one would drop bytes a vector meant to be fed.
 * <p>
 * Header lines are kept verbatim in {@link QpackVector#headers()}, so a key this class does not
 * model yet (T002 already writes {@code rfc}, {@code continues-from}, {@code max-entries},
 * {@code blocked-delivery-order}, …) is reachable without a change here and without failing the
 * load. Only {@code max-table-capacity} and {@code blocked-streams} are required, because without
 * them there is no tuple.
 */
public final class QpackVectors {
	/** Where T002 transcribed the RFC 9204 Appendix B.1–B.5 worked examples. */
	public static final String RFC_9204_APPENDIX_B = "/qpack/rfc9204-appendix-b";

	/** The series-order manifest every vector directory carries, listing one file name per line. */
	public static final String INDEX_FILE = "index.txt";

	private static final String ENCODER_STREAM = "encoder-stream";
	private static final String FIELD_SECTION = "field-section";
	private static final String DECODER_STREAM = "decoder-stream";
	private static final String EXPECTED_FIELDS = "expected-fields";
	private static final String EXPECTED_TABLE = "expected-table";
	private static final String EXPECTED_STATE = "expected-state";

	private QpackVectors() {}

	/**
	 * One vector: the two negotiated parameters it is decoded at, its three byte streams, and the
	 * fields and table state it must produce.
	 * <p>
	 * The four the tuple is named for are {@link #capacity()}, {@link #blockedStreams()},
	 * {@link #encodedSection()} and {@link #expectedFields()}; the rest is what replaying Appendix B
	 * as one cumulative exchange needs.
	 *
	 * @param name           the vector's {@code name:} header ({@code B.1}, …), the file name if absent
	 * @param title          the {@code title:} header, {@code ""} if absent
	 * @param capacity       {@code max-table-capacity} — the dynamic-table capacity this endpoint
	 *                       advertises and a decoder is constructed with. Not the same as
	 *                       {@link #tableCapacityAfter()}, which is what a Set Dynamic Table Capacity
	 *                       instruction inside {@link #encoderStream()} leaves the table at
	 * @param blockedStreams {@code blocked-streams} — the limit this vector replays at
	 * @param encoderStream  encoder-stream instructions; empty, never {@code null}
	 * @param encodedSection the encoded field section, prefix included; <b>may be empty</b> — B.3 and
	 *                       B.5 send none
	 * @param decoderStream  decoder-stream instructions the RFC shows for this step; may be empty
	 * @param expectedFields the fields the decoder must produce, in wire order
	 * @param expectedTable  the dynamic table after this step, oldest entry first
	 * @param expectedState  insert count, known received count and table size after this step
	 * @param deliveryOrder  which streams to feed, in order — the {@code delivery-order:} header
	 * @param headers        every header line verbatim, unknown keys included
	 */
	public record QpackVector(
		String name, String title, long capacity, long blockedStreams,
		byte[] encoderStream, byte[] encodedSection, byte[] decoderStream,
		List<QpackField> expectedFields, List<TableEntry> expectedTable, ExpectedState expectedState,
		List<String> deliveryOrder, Map<String, String> headers
	) {
		public QpackVector {
			expectedFields = List.copyOf(expectedFields);
			expectedTable = List.copyOf(expectedTable);
			deliveryOrder = List.copyOf(deliveryOrder);
			headers = Map.copyOf(headers);
		}

		/** The dynamic-table capacity before this step, {@code 0} if the header is absent. */
		public long tableCapacityBefore() {
			return longHeader("table-capacity-before", 0);
		}

		/** The dynamic-table capacity after this step — what a Set Dynamic Table Capacity leaves. */
		public long tableCapacityAfter() {
			return longHeader("table-capacity-after", 0);
		}

		/** RFC 9204 §4.5.1.1 MaxEntries, {@code floor(capacity / 32)}, as the fixture states it. */
		public long maxEntries() {
			return longHeader("max-entries", capacity / 32);
		}

		/** The request stream this step's field section belongs to, or {@code -1} for {@code none}. */
		public long streamId() {
			String value = headers.get("stream");
			return value == null || value.equals("none") ? -1 : Long.parseLong(value);
		}

		/** The name of the vector this one continues, or {@code null} for {@code none}. */
		public String continuesFrom() {
			String value = headers.get("continues-from");
			return value == null || value.equals("none") ? null : value;
		}

		/**
		 * The alternative delivery order a vector records when the RFC's presentation order and its
		 * narrated order differ (B.4 delays the encoder-stream packet past the field section, which
		 * is what makes the section blocked). Empty when the vector states only one order.
		 */
		public List<String> blockedDeliveryOrder() {
			String value = headers.get("blocked-delivery-order");
			return value == null ? List.of() : splitList(value);
		}

		public boolean hasEncoderStream() { return encoderStream.length != 0; }

		public boolean hasFieldSection() { return encodedSection.length != 0; }

		public boolean hasDecoderStream() { return decoderStream.length != 0; }

		/**
		 * A fresh readable {@link ByteBuf} over a <b>copy</b> of {@link #encodedSection()}, ready to
		 * hand to {@link QpackDecoder#decode} — which owns and recycles its input, so every decode
		 * of the same vector needs its own buffer.
		 */
		public ByteBuf sectionBuf() { return ByteBuf.wrapForReading(encodedSection.clone()); }

		/** As {@link #sectionBuf()}, for {@link #encoderStream()}. */
		public ByteBuf encoderStreamBuf() { return ByteBuf.wrapForReading(encoderStream.clone()); }

		/** As {@link #sectionBuf()}, for {@link #decoderStream()}. */
		public ByteBuf decoderStreamBuf() { return ByteBuf.wrapForReading(decoderStream.clone()); }

		/** Callers must not mutate the returned array. */
		@Override
		public byte[] encoderStream() { return encoderStream; }

		/** Callers must not mutate the returned array. */
		@Override
		public byte[] encodedSection() { return encodedSection; }

		/** Callers must not mutate the returned array. */
		@Override
		public byte[] decoderStream() { return decoderStream; }

		private long longHeader(String key, long defaultValue) {
			String value = headers.get(key);
			return value == null ? defaultValue : Long.parseLong(value);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof QpackVector that)) return false;
			return name.equals(that.name) && title.equals(that.title) &&
				capacity == that.capacity && blockedStreams == that.blockedStreams &&
				Arrays.equals(encoderStream, that.encoderStream) &&
				Arrays.equals(encodedSection, that.encodedSection) &&
				Arrays.equals(decoderStream, that.decoderStream) &&
				expectedFields.equals(that.expectedFields) && expectedTable.equals(that.expectedTable) &&
				expectedState.equals(that.expectedState) && deliveryOrder.equals(that.deliveryOrder) &&
				headers.equals(that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, title, capacity, blockedStreams,
				Arrays.hashCode(encoderStream), Arrays.hashCode(encodedSection), Arrays.hashCode(decoderStream),
				expectedFields, expectedTable, expectedState, deliveryOrder, headers);
		}

		@Override
		public String toString() {
			return "QpackVector{" + name +
				", capacity=" + capacity +
				", blockedStreams=" + blockedStreams +
				", encoderStream=" + encoderStream.length + "b" +
				", section=" + encodedSection.length + "b" +
				", decoderStream=" + decoderStream.length + "b" +
				", fields=" + expectedFields +
				'}';
		}
	}

	/**
	 * One expected dynamic-table entry.
	 *
	 * @param absoluteIndex the RFC 9204 §3.2.4 absolute index — <b>stable across eviction</b>, so
	 *                      B.5's table starts at 1 rather than 0
	 * @param refCount      the encoder-side reference count the RFC's diagram shows
	 * @param field         the entry's name and value
	 */
	public record TableEntry(long absoluteIndex, int refCount, QpackField field) {
		@Override
		public String toString() {
			return absoluteIndex + " (ref=" + refCount + ") " + field;
		}
	}

	/** The decoder/encoder counters a vector expects after its step. */
	public record ExpectedState(long insertCount, long knownReceivedCount, long size) {
		static final ExpectedState UNSTATED = new ExpectedState(0, 0, 0);
	}

	/** The RFC 9204 Appendix B corpus, in the series order its {@code index.txt} gives. */
	public static List<QpackVector> rfc9204AppendixB() {
		return loadSeries(RFC_9204_APPENDIX_B);
	}

	/**
	 * Every vector a directory's {@link #INDEX_FILE} lists, in the order it lists them.
	 * <p>
	 * The index is read rather than the directory listed, both because the order is semantic (see the
	 * class Javadoc — Appendix B is one cumulative exchange) and because a classpath directory is not
	 * portably listable from inside a jar.
	 *
	 * @param resourceDirectory an absolute classpath path, e.g. {@link #RFC_9204_APPENDIX_B}
	 * @throws AssertionError if the index or any vector it names is absent, unreadable or malformed —
	 *                        a fixture that silently vanishes reads as "covered" when it is not
	 */
	public static List<QpackVector> loadSeries(String resourceDirectory) {
		String directory = resourceDirectory.endsWith("/") ?
			resourceDirectory.substring(0, resourceDirectory.length() - 1) :
			resourceDirectory;

		List<QpackVector> vectors = new ArrayList<>();
		for (String line : readResource(directory + '/' + INDEX_FILE).lines().toList()) {
			String fileName = line.strip();
			if (fileName.isEmpty() || fileName.charAt(0) == '#') continue;
			vectors.add(loadOne(directory + '/' + fileName));
		}
		if (vectors.isEmpty()) {
			throw new AssertionError(directory + '/' + INDEX_FILE +
				" names no vectors — an empty corpus reads as covered when it is not");
		}
		return List.copyOf(vectors);
	}

	/** As {@link #loadSeries(String)}, keyed by {@link QpackVector#name()}, iterating in series order. */
	public static Map<String, QpackVector> loadSeriesByName(String resourceDirectory) {
		Map<String, QpackVector> byName = new LinkedHashMap<>();
		for (QpackVector vector : loadSeries(resourceDirectory)) {
			if (byName.put(vector.name(), vector) != null) {
				throw new AssertionError("Two vectors in " + resourceDirectory +
					" share the name '" + vector.name() + "'");
			}
		}
		return Collections.unmodifiableMap(byName);
	}

	/**
	 * One vector from an absolute classpath path, e.g.
	 * {@code loadOne(RFC_9204_APPENDIX_B + "/b1-literal-field-line-with-name-reference.vec")}.
	 */
	public static QpackVector loadOne(String resourcePath) {
		int slash = resourcePath.lastIndexOf('/');
		return parse(stripExtension(resourcePath.substring(slash + 1)), readResource(resourcePath));
	}

	/**
	 * Parses one vector from its text, for a test that wants an inline vector rather than a file.
	 *
	 * @param defaultName the name to use unless the text carries a {@code name:} header
	 */
	public static QpackVector parse(String defaultName, String text) {
		Map<String, String> headers = new LinkedHashMap<>();
		ByteArrayOutputStream encoderStream = new ByteArrayOutputStream();
		ByteArrayOutputStream fieldSection = new ByteArrayOutputStream();
		ByteArrayOutputStream decoderStream = new ByteArrayOutputStream();
		List<QpackField> expectedFields = new ArrayList<>();
		List<TableEntry> expectedTable = new ArrayList<>();
		Map<String, String> stateLines = new LinkedHashMap<>();

		String section = null;
		int lineNo = 0;
		for (String rawLine : (Iterable<String>) text.lines()::iterator) {
			lineNo++;
			String line = rawLine.strip();
			if (line.isEmpty() || line.charAt(0) == '#') continue;

			if (line.charAt(0) == '[') {
				if (!line.endsWith("]")) throw malformed(defaultName, lineNo, "unterminated section header: " + line);
				section = line.substring(1, line.length() - 1).strip();
				switch (section) {
					// Recognised: the body is parsed per-line below, so opening one does nothing here.
					case ENCODER_STREAM, FIELD_SECTION, DECODER_STREAM, EXPECTED_FIELDS, EXPECTED_TABLE, EXPECTED_STATE -> {}
					default -> throw malformed(defaultName, lineNo, "unknown section [" + section + ']');
				}
				continue;
			}

			if (section == null) {
				Map.Entry<String, String> header = splitKeyValue(defaultName, lineNo, line, "header");
				if (headers.put(header.getKey(), header.getValue()) != null) {
					throw malformed(defaultName, lineNo, "duplicate header `" + header.getKey() + '`');
				}
				continue;
			}

			switch (section) {
				case ENCODER_STREAM -> writeHex(encoderStream, defaultName, lineNo, line);
				case FIELD_SECTION -> writeHex(fieldSection, defaultName, lineNo, line);
				case DECODER_STREAM -> writeHex(decoderStream, defaultName, lineNo, line);
				case EXPECTED_FIELDS -> expectedFields.add(parseExpectedField(defaultName, lineNo, line));
				case EXPECTED_TABLE -> expectedTable.add(parseTableEntry(defaultName, lineNo, line));
				case EXPECTED_STATE -> {
					Map.Entry<String, String> state = splitKeyValue(defaultName, lineNo, line, EXPECTED_STATE);
					stateLines.put(state.getKey(), state.getValue());
				}
				default -> throw new AssertionError(section);
			}
		}

		String name = headers.getOrDefault("name", defaultName);
		long capacity = requiredLong(name, headers, "max-table-capacity");
		long blockedStreams = requiredLong(name, headers, "blocked-streams");

		ExpectedState expectedState = stateLines.isEmpty() ?
			ExpectedState.UNSTATED :
			new ExpectedState(
				stateLong(name, stateLines, "insert-count"),
				stateLong(name, stateLines, "known-received-count"),
				stateLong(name, stateLines, "size"));

		String deliveryOrder = headers.get("delivery-order");

		return new QpackVector(
			name, headers.getOrDefault("title", ""), capacity, blockedStreams,
			encoderStream.toByteArray(), fieldSection.toByteArray(), decoderStream.toByteArray(),
			expectedFields, expectedTable, expectedState,
			deliveryOrder == null ? List.of() : splitList(deliveryOrder),
			headers);
	}

	private static String readResource(String resourcePath) {
		try (InputStream in = QpackVectors.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new AssertionError("QPACK vector resource not found on the classpath: " + resourcePath);
			}
			return new String(in.readAllBytes(), UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Cannot read QPACK vector resource " + resourcePath, e);
		}
	}

	private static QpackField parseExpectedField(String vector, int lineNo, String line) {
		int separator = line.indexOf(": ");
		String name;
		String value;
		if (separator >= 0) {
			name = line.substring(0, separator);
			value = line.substring(separator + 2);
		} else if (line.endsWith(":")) {
			name = line.substring(0, line.length() - 1);
			value = "";
		} else {
			throw malformed(vector, lineNo, "expected `name: value` in [" + EXPECTED_FIELDS + "], found: " + line);
		}
		if (name.isEmpty()) throw malformed(vector, lineNo, "empty field name in [" + EXPECTED_FIELDS + ']');
		return new QpackField(HttpHeaders.of(requireAscii(vector, lineNo, "field name", name)),
			requireAscii(vector, lineNo, "field value", value).getBytes(US_ASCII));
	}

	private static TableEntry parseTableEntry(String vector, int lineNo, String line) {
		// `<abs> <ref> <name> <value…>` — the value is the rest of the line, so it may hold spaces.
		String[] head = line.split("\\s+", 4);
		if (head.length < 3) {
			throw malformed(vector, lineNo,
				"expected `<abs> <ref> <name> <value>` in [" + EXPECTED_TABLE + "], found: " + line);
		}
		long absoluteIndex;
		int refCount;
		try {
			absoluteIndex = Long.parseLong(head[0]);
			refCount = Integer.parseInt(head[1]);
		} catch (NumberFormatException e) {
			throw malformed(vector, lineNo, "[" + EXPECTED_TABLE + "] row starts with a non-number: " + line);
		}
		String value = head.length == 4 ? head[3] : "";
		return new TableEntry(absoluteIndex, refCount,
			new QpackField(HttpHeaders.of(requireAscii(vector, lineNo, "entry name", head[2])),
				requireAscii(vector, lineNo, "entry value", value).getBytes(US_ASCII)));
	}

	private static void writeHex(ByteArrayOutputStream out, String vector, int lineNo, String line) {
		StringBuilder digits = new StringBuilder(line.length());
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (!Character.isWhitespace(c)) digits.append(c);
		}
		if ((digits.length() & 1) != 0) {
			throw malformed(vector, lineNo, "hex line has an odd number of digits: " + line);
		}
		for (int i = 0; i < digits.length(); i += 2) {
			int high = Character.digit(digits.charAt(i), 16);
			int low = Character.digit(digits.charAt(i + 1), 16);
			if (high < 0 || low < 0) {
				throw malformed(vector, lineNo, "hex line has a non-hex digit: " + line);
			}
			out.write((high << 4) | low);
		}
	}

	private static Map.Entry<String, String> splitKeyValue(String vector, int lineNo, String line, String where) {
		int colon = line.indexOf(':');
		if (colon < 0) throw malformed(vector, lineNo, "expected `key: value` in " + where + ", found: " + line);
		String key = line.substring(0, colon).strip();
		if (key.isEmpty()) throw malformed(vector, lineNo, "empty key in " + where + ": " + line);
		return Map.entry(key, line.substring(colon + 1).strip());
	}

	private static long requiredLong(String vector, Map<String, String> headers, String key) {
		String value = headers.get(key);
		if (value == null) throw malformed(vector, 0, "no `" + key + ":` header");
		return parseLong(vector, key, value);
	}

	private static long stateLong(String vector, Map<String, String> state, String key) {
		String value = state.get(key);
		if (value == null) throw malformed(vector, 0, "[" + EXPECTED_STATE + "] has no `" + key + ":`");
		return parseLong(vector, key, value);
	}

	private static long parseLong(String vector, String key, String value) {
		long parsed;
		try {
			parsed = Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw malformed(vector, 0, '`' + key + ":` is not a decimal number: " + value);
		}
		if (parsed < 0) throw malformed(vector, 0, '`' + key + ":` is negative: " + value);
		return parsed;
	}

	private static List<String> splitList(String value) {
		List<String> items = new ArrayList<>();
		for (String item : value.split(",")) {
			String stripped = item.strip();
			if (!stripped.isEmpty()) items.add(stripped);
		}
		return List.copyOf(items);
	}

	private static String requireAscii(String vector, int lineNo, String what, String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < 0x20 || c > 0x7e) {
				throw malformed(vector, lineNo, what +
					" holds a non-printable-ASCII character at offset " + i +
					" — the fixture format has no hex escape for one yet");
			}
		}
		return value;
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot <= 0 ? fileName : fileName.substring(0, dot);
	}

	private static AssertionError malformed(String vector, int lineNo, String problem) {
		return new AssertionError("Malformed QPACK vector '" + vector + '\'' +
			(lineNo > 0 ? " at line " + lineNo : "") + ": " + problem);
	}
}
