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

package io.activej.json;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.JsonReader;
import com.dslplatform.json.ObjectConverter;
import com.dslplatform.json.runtime.Settings;
import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Pins the rule that captures an envelope member's <b>undecoded</b> byte range, so that a payload whose codec is not yet
 * known can be decoded later — the deferred-decoding technique a JSON-RPC envelope needs, since {@code params} cannot be
 * decoded until {@code method} has been read and JSON-RPC 2.0 does not constrain member order.
 * <p>
 * <b>Provenance.</b> This is the retained correctness net of phase-0 spike 00-A, whose conclusion was <i>feasible but
 * costly</i>: the rule below is exact and was pinned against all 18 fixtures of this corpus, while the throughput
 * measurement did not support the deferral on a small envelope. The measurement harness was deleted with the verdict;
 * only the correctness assertions survive, and no figure or performance claim is made here. The verdict, its figures and
 * its threats to validity are recorded once in {@code specs/009-jsonrpc-spikes/verdicts.md}.
 *
 * <h2>The capture rule</h2>
 * Four facts are load-bearing, all derived from failing assertions against {@link #corpus()} rather than from reading
 * dsl-json's source:
 * <ul>
 *     <li>{@link JsonReader#readKey()} consumes the key, the {@code ':'} and the whitespace after it, and leaves
 *     {@code last()} on the value's <b>first</b> byte. So {@code start = getCurrentIndex() - 1}, with <b>no</b>
 *     left-trim.</li>
 *     <li>{@link JsonReader#skip()} skips one whole value <b>and then consumes the following separator</b>, returning
 *     it. Every subsequent decision must use that returned byte; calling {@code getNextToken()} again to "find" the
 *     separator consumes the next key's opening quote instead, and silently drops every member after the first.</li>
 *     <li>{@code end = getCurrentIndex() - 1} is one past the value <b>plus any whitespace</b> that sat between the
 *     value and the separator, so it needs a <b>right-trim</b> of {@code ' '}, {@code '\t'}, {@code '\n'},
 *     {@code '\r'}. For {@code { "params" : [ 1 , 2 ] }} the raw slice is {@code "[ 1 , 2 ] "} and the trimmed one is
 *     {@code "[ 1 , 2 ]"}. Without the trim the sub-range decode leaves trailing data unconsumed, which
 *     {@code JsonUtils.fromJsonBytes} reports as {@code MalformedDataException}.</li>
 *     <li>An <b>empty envelope</b> {@code {}} has no members and {@code readKey()} throws on it, so the loop is
 *     guarded by {@code if (getNextToken() != '}')}.</li>
 * </ul>
 *
 * <h2>Applicability: contiguous {@code byte[]} only</h2>
 * The captured {@code [start, end)} pair is an index into the array handed to
 * {@link JsonReader#process(byte[], int)}. It does <b>not</b> survive {@code JsonReader.process(InputStream)}: a
 * {@code read()} that exhausts the buffer calls {@code prepareNextBlock()}, which shifts the buffer contents and
 * resets {@code currentIndex}. Across a refill a previously captured pair is not merely stale but meaningless. <b>The
 * technique is therefore restricted to the contiguous-{@code byte[]} mode that {@link JsonUtils#fromJsonBytes} uses</b>,
 * which is the mode every current ActiveJ JSON entry point takes. A payload arriving in pieces must be <b>joined
 * first</b> — see the {@code fragmented-arrival} fixture, which is the same finding reached from the other direction.
 * An undecoded-payload representation must therefore hold either a reference to the <b>whole contiguous envelope
 * array</b> plus a {@code [start, end)} pair, or an eagerly-copied sub-array; it <b>cannot</b> hold a reader.
 *
 * <h2>A latent bug this test does not fix</h2>
 * {@link AbstractMapJsonCodec}{@code .read} uses the same primitive methods, but its skip branch reads
 * {@code reader.skip(); continue;}, which re-enters {@code readKey()} with {@code last()} already on the {@code ','}
 * that {@code skip()} consumed — the exact mistake {@link #skipAlreadyConsumesTheSeparatorAndGetNextTokenDoesNot} pins
 * against. It is recorded here rather than fixed: the fix belongs to a change that can carry its own regression test.
 */
public final class DeferredDecodingTest {
	/**
	 * Mirrors {@link JsonUtils}'s own reader configuration so the parser under test is the one production actually gets.
	 * {@code Settings.<Object>withRuntime()} rather than a bare {@code new DslJson<>()}: identical in behaviour here (an
	 * ActiveJ {@link JsonCodec} never consults dsl-json's converter registry), but identical in configuration too, which
	 * removes the question.
	 */
	private static final DslJson<Object> DSL_JSON = new DslJson<>(Settings.<Object>withRuntime().includeServiceLoader());

	// Four readers, constructed once and reused via process(...). They are separate instances rather than one shared
	// instance because two of the strategies below run two passes, and a second process(...) on a reader in use would
	// clobber the first pass's state.
	private static final JsonReader<Object> SCAN_READER = DSL_JSON.newReader();
	private static final JsonReader<Object> TREE_READER = DSL_JSON.newReader();
	private static final JsonReader<Object> VALUE_READER = DSL_JSON.newReader();
	private static final JsonReader<Object> REPARSE_READER = DSL_JSON.newReader();

	/** The target codecs the strategies decode through — shared, so the comparison isolates deferral. */
	private static final JsonCodec<List<Integer>> PARAMS_CODEC = JsonCodecs.ofList(JsonCodecs.ofInteger());
	private static final JsonCodec<String> METHOD_CODEC = JsonCodecs.ofString();

	/** The one shape all four strategies must produce, so they are comparable by a single {@code assertEquals}. */
	record Decoded(String method, List<Integer> params) {}

	private static final Decoded EXPECTED = new Decoded("sum", List.of(1, 2, 3));

	/** Decoded cleanly, but not equal to the direct decode — the rule is wrong about <i>what</i> the value is. */
	private static final String MISMATCH = "MISMATCH";

	/** The captured range would not parse at all — the rule is wrong about <i>where</i> the value is. */
	private static final String PARSE_FAILURE = "PARSE-FAILURE";

	/** One captured envelope member: its key, and the half-open byte range of its undecoded value. */
	record Member(String key, int start, int end) {}

	// region the corpus
	//
	// Two properties of how this corpus is used are load-bearing:
	//
	//   - it is exercised WHOLE by capturedRangesReDecodeEqualToTheDirectDecodeAcrossTheWholeCorpus(), which is the
	//     assertion the capture rule is pinned by;
	//   - `expected` is expressed in dsl-json's GENERIC-TREE types, not the obvious Java ones: an integral number
	//     decodes to Long (never Integer), a fractional one to BigDecimal (whose equals is scale-sensitive), an array to
	//     ArrayList and an object to LinkedHashMap.

	/** The envelope member every fixture defers. */
	static final String DEFERRED_KEY = "params";

	/** The fixture the four strategies are all valid on — strategy (d) needs {@code method} to precede {@code params}. */
	static final String BASE = "base-params-after-method";

	/**
	 * The nesting depth of the {@code nested-32} fixture. A fixture parameter recorded as a <b>data point</b> for a
	 * future {@code maxJsonDepth} bound, explicitly <b>not</b> a proposed limit: this test adds no
	 * {@code ApplicationSettings} key and no limit.
	 */
	static final int NESTING_DEPTH = 32;

	/**
	 * One corpus entry.
	 * <p>
	 * The record's generated {@code equals}/{@code hashCode} are identity-based over the array components. That is
	 * harmless here: fixtures are only ever iterated and named, never compared.
	 *
	 * @param name        the identifier a failure message leads with
	 * @param property    what this fixture would catch if the capture rule broke — quoted verbatim by the failure
	 *                    messages
	 * @param payload     the canonical, contiguous envelope every strategy runs against
	 * @param deferredKey the envelope member whose value {@code expected} describes
	 * @param expected    the deferred member's value in dsl-json's generic-tree types (see above)
	 * @param fragments   {@code null} unless this fixture also exercises fragmented arrival. The offsets a capture
	 *                    produces are valid only against the array they were captured from, so a fragmented arrival must
	 *                    be <b>joined before capture</b>, never captured per fragment — the same finding this class's
	 *                    Javadoc reaches from the {@code process(InputStream)} side
	 */
	record Fixture(
		String name,
		String property,
		byte[] payload,
		String deferredKey,
		Object expected,
		byte[][] fragments
	) {
		public boolean isFragmented() {return fragments != null;}

		public String json() {return new String(payload, UTF_8);}

		@Override
		public String toString() {return name;}
	}

	static Fixture of(String name, String property, String json, String deferredKey, Object expected) {
		return new Fixture(name, property, json.getBytes(UTF_8), deferredKey, expected, null);
	}

	static Fixture fragmentedAt(
		String name, String property, String json, String deferredKey, Object expected, int... splits
	) {
		byte[] payload = json.getBytes(UTF_8);
		return new Fixture(name, property, payload, deferredKey, expected, splitAt(payload, splits));
	}

	private static final Fixture BASE_FIXTURE = of(BASE,
		"the common JSON-RPC shape: the codec-selecting key precedes the payload, so single-pass (d) is valid",
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}",
		DEFERRED_KEY, new ArrayList<>(List.of(1L, 2L, 3L)));

	private static final Fixture PARAMS_FIRST = of("params-before-method",
		"JSON-RPC 2.0 does not constrain member order; this is the case deferral exists for",
		"{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":[1,2,3],\"method\":\"sum\"}",
		DEFERRED_KEY, new ArrayList<>(List.of(1L, 2L, 3L)));

	// Every JSON escape below is written with a DOUBLED backslash in Java source. Java's \\uXXXX is a *lexer* escape
	// processed before string escapes, so "é" in source is the character e-acute and not the six ASCII bytes
	// \ u 0 0 e 9 that the JSON escape needs. Getting this wrong silently degrades the unicode-escape fixture into a
	// duplicate plain-string fixture — it would still pass while testing nothing, which is why the corpus assertion
	// checks that the payload really does contain a backslash followed by 'u'.

	private static final Fixture ESCAPED_QUOTE = of("escaped-quote",
		"a '\"' inside a string must not end the captured value — catches any hand-rolled quote scan",
		"{\"method\":\"echo\",\"params\":[\"a \\\" b\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("a \" b")));

	private static final Fixture ESCAPED_BACKSLASH = of("escaped-backslash",
		"a backslash before the closing quote — catches a scanner that treats it as escaping the quote",
		"{\"method\":\"echo\",\"params\":[\"a\\\\b\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("a\\b")));

	private static final Fixture UNICODE_ESCAPE = of("unicode-escape",
		"\\uXXXX escapes: the captured byte range is longer than the decoded string",
		"{\"method\":\"echo\",\"params\":[\"\\u00e9\\u0041\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("éA")));

	private static final Fixture NON_BMP_LITERAL = of("non-bmp-literal",
		"a 4-byte UTF-8 sequence inside params — pins that getCurrentIndex() counts bytes, not chars",
		"{\"method\":\"echo\",\"params\":[\"" + "😀" + "\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("😀")));

	private static final Fixture NON_BMP_ESCAPED = of("non-bmp-escaped",
		"the same astral code point as a JSON surrogate pair: 12 source bytes for one code point, so the captured " +
		"range and the decoded string agree on nothing except the value",
		"{\"method\":\"echo\",\"params\":[\"\\ud83d\\ude00\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("😀")));

	private static final Fixture WHITESPACE_PADDED = of("whitespace-padded",
		"whitespace around every ':' and ',' — without it nothing in the corpus exercises the right-trim, and a " +
		"capture rule that omits the trim would pass the whole corpus while being wrong. This is the fixture that " +
		"makes the trim a pinned property rather than a claim",
		"{ \"method\" : \"sum\" , \"params\" : [ 1 , 2 ] }",
		DEFERRED_KEY, new ArrayList<>(List.of(1L, 2L)));

	private static final Fixture STRUCTURAL_CHARS_IN_STRING = of("structural-chars-in-string",
		"'{', '}', '[', ']' and ',' inside a string — the single fixture that catches a brace-counting capture; we " +
		"rely on JsonReader.skip() precisely so this cannot break",
		"{\"method\":\"echo\",\"params\":[\"},{[,]\"]}",
		DEFERRED_KEY, new ArrayList<>(List.of("},{[,]")));

	private static final Fixture VALUE_NULL = of("value-null",
		"a null payload: 'no value' is a declared expectation, not an absent one",
		"{\"method\":\"nop\",\"params\":null}",
		DEFERRED_KEY, null);

	private static final Fixture VALUE_NUMBER_INTEGRAL = of("value-number-integral",
		"an integral number decodes to Long, never Integer — an expectation written as 42 fails here, loudly",
		"{\"method\":\"nop\",\"params\":42}",
		DEFERRED_KEY, 42L);

	private static final Fixture VALUE_NUMBER_DECIMAL = of("value-number-decimal",
		"a fractional number decodes to BigDecimal, whose equals is scale-sensitive — the one value type whose " +
		"expected form cannot be guessed",
		"{\"method\":\"nop\",\"params\":-1.5e10}",
		DEFERRED_KEY, new BigDecimal("-1.5E+10"));

	private static final Fixture VALUE_STRING = of("value-string",
		"a bare string payload: the captured range must include both quotes",
		"{\"method\":\"nop\",\"params\":\"hello\"}",
		DEFERRED_KEY, "hello");

	private static final Fixture VALUE_BOOLEAN = of("value-boolean",
		"a bare literal payload: the capture has no delimiter to lean on, only skip()'s return",
		"{\"method\":\"nop\",\"params\":true}",
		DEFERRED_KEY, Boolean.TRUE);

	private static final Fixture VALUE_EMPTY_ARRAY = of("value-empty-array",
		"an empty array: start and end are two bytes apart and must not collapse",
		"{\"method\":\"nop\",\"params\":[]}",
		DEFERRED_KEY, new ArrayList<>());

	private static final Fixture VALUE_EMPTY_OBJECT = of("value-empty-object",
		"an empty object as the payload — the value legitimately ENDS in '}', which is also the envelope's " +
		"terminator; not to be confused with an empty envelope, which has no members at all and cannot be a Fixture",
		"{\"method\":\"nop\",\"params\":{}}",
		DEFERRED_KEY, new LinkedHashMap<>());

	private static Fixture nested32() {
		StringBuilder value = new StringBuilder();
		for (int i = 0; i < NESTING_DEPTH; i++) value.append("{\"a\":");
		value.append("1");
		for (int i = 0; i < NESTING_DEPTH; i++) value.append('}');

		Object expected = 1L;                                   // the innermost number is a Long, not an Integer
		for (int i = 0; i < NESTING_DEPTH; i++) {
			Map<String, Object> level = new LinkedHashMap<>();
			level.put("a", expected);
			expected = level;
		}
		return of("nested-32",
			"an object nested exactly " + NESTING_DEPTH + " levels as the deferred payload — the depth counted as " +
			"the number of '{' characters in the payload value, innermost value 1. A data point for a future " +
			"maxJsonDepth, not a proposed bound",
			"{\"method\":\"deep\",\"params\":" + value + "}",
			DEFERRED_KEY, expected);
	}

	private static Fixture fragmented() {
		String json = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"echo\",\"params\":[\"😀\\u00e9\"]}";
		byte[] payload = json.getBytes(UTF_8);
		int emoji = indexOf(payload, "😀".getBytes(UTF_8));
		int escape = indexOf(payload, "\\u00e9".getBytes(UTF_8));
		if (emoji < 0 || escape < 0) {
			throw new AssertionError("the fragmented fixture lost its astral character or its \\u escape — the " +
									 "Java-lexer escape trap struck: json=<" + json + '>');
		}
		return fragmentedAt("fragmented-arrival",
			"the envelope arrives in 4 byte[] pieces, two of which split a 4-byte UTF-8 sequence and a \\uXXXX " +
			"escape down the middle; the capture technique needs the contiguous join, and this is where that is proved",
			json, DEFERRED_KEY, new ArrayList<>(List.of("😀é")),
			// 5 splits the envelope's first key; emoji + 2 lands on a UTF-8 continuation byte; escape + 3 lands
			// inside the é escape; the last one splits the two closing brackets from the rest
			5, emoji + 2, escape + 3, payload.length - 3);
	}

	private static final List<Fixture> CORPUS = List.of(
		BASE_FIXTURE,
		PARAMS_FIRST,
		ESCAPED_QUOTE,
		ESCAPED_BACKSLASH,
		UNICODE_ESCAPE,
		NON_BMP_LITERAL,
		NON_BMP_ESCAPED,
		WHITESPACE_PADDED,
		STRUCTURAL_CHARS_IN_STRING,
		VALUE_NULL,
		VALUE_NUMBER_INTEGRAL,
		VALUE_NUMBER_DECIMAL,
		VALUE_STRING,
		VALUE_BOOLEAN,
		VALUE_EMPTY_ARRAY,
		VALUE_EMPTY_OBJECT,
		nested32(),
		fragmented());

	/** The whole corpus, in a stable declaration order so a run's failure list is reproducible. */
	static List<Fixture> corpus() {
		return CORPUS;
	}

	/** The one fixture all four strategies are valid on — strategy (d) needs {@code method} to precede {@code params}. */
	static Fixture base() {
		return BASE_FIXTURE;
	}

	/** Looks a fixture up by name — the corpus grows, so an index would silently drift. */
	static Fixture byName(String name) {
		for (Fixture fixture : CORPUS) {
			if (fixture.name().equals(name)) return fixture;
		}
		throw new IllegalArgumentException("no such fixture: " + name);
	}

	/** Joins fragments back into the contiguous form a capture is valid against. */
	static byte[] join(byte[][] parts) {
		int total = 0;
		for (byte[] part : parts) total += part.length;
		byte[] joined = new byte[total];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, joined, offset, part.length);
			offset += part.length;
		}
		return joined;
	}

	/**
	 * Splits at strictly increasing, in-range offsets. A silently clamped split point would make the fixture claim a
	 * property it does not have, so an out-of-order or out-of-range offset is refused.
	 */
	static byte[][] splitAt(byte[] payload, int... offsets) {
		int previous = 0;
		for (int offset : offsets) {
			if (offset <= previous || offset >= payload.length) {
				throw new IllegalArgumentException(
					"split offsets must be strictly increasing and within (0, " + payload.length + "), got " +
					Arrays.toString(offsets));
			}
			previous = offset;
		}
		byte[][] parts = new byte[offsets.length + 1][];
		int start = 0;
		for (int i = 0; i < offsets.length; i++) {
			parts[i] = Arrays.copyOfRange(payload, start, offsets[i]);
			start = offsets[i];
		}
		parts[offsets.length] = Arrays.copyOfRange(payload, start, payload.length);
		return parts;
	}

	/** The first index of {@code needle} in {@code haystack}, or {@code -1}. */
	static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	/** Counts occurrences of one byte — used to assert {@code nested-32} really is 32 levels deep. */
	static int countBytes(byte[] bytes, byte value) {
		int count = 0;
		for (byte b : bytes) {
			if (b == value) count++;
		}
		return count;
	}

	// endregion

	// region the capture rule

	/**
	 * Trims trailing JSON whitespace off a captured range.
	 * <p>
	 * Load-bearing, not cosmetic: {@code skip()} stops one past the separator, so any whitespace between the value and
	 * the separator is inside the raw range. See {@link #decodeTree}, which is what makes a missing trim fail loudly.
	 */
	static int rtrimJsonWhitespace(byte[] bytes, int start, int end) {
		int e = end;
		while (e > start) {
			byte b = bytes[e - 1];
			if (b != ' ' && b != '\t' && b != '\n' && b != '\r') break;
			e--;
		}
		return e;
	}

	/**
	 * Captures the byte range of every member's value in one pass, decoding none of them.
	 * <p>
	 * {@code bytes} must be the very array the reader was {@code process(...)}ed with — the offsets index into it.
	 */
	static List<Member> captureMembers(JsonReader<?> reader, byte[] bytes) throws IOException {
		List<Member> members = new ArrayList<>();
		reader.getNextToken();                          // '{'
		if (reader.getNextToken() != '}') {             // an empty envelope has no members; readKey() would throw
			while (true) {
				String key = reader.readKey();          // consumes the key, the ':' and the whitespace after it
				int start = reader.getCurrentIndex() - 1;   // last() is already the value's first byte
				byte next = reader.skip();              // skips the value AND consumes the following ',' or '}'
				int end = rtrimJsonWhitespace(bytes, start, reader.getCurrentIndex() - 1);
				members.add(new Member(key, start, end));
				if (next != ',') break;
				reader.getNextToken();                  // position on the '"' of the next key — ONCE, not twice
			}
		}
		return members;
	}

	/**
	 * Materialises a generic tree over {@code bytes[0, length)} and insists the value consumed all of it.
	 * <p>
	 * The completeness check reproduces {@link JsonUtils}'s own. It is what turns a missing right-trim into a failure
	 * instead of a silent pass, so it must not be "simplified" away.
	 */
	static Object decodeTree(byte[] bytes, int length) throws IOException {
		JsonReader<Object> reader = TREE_READER.process(bytes, length);
		reader.getNextToken();
		Object value = ObjectConverter.deserializeObject(reader);
		if (reader.length() != reader.getCurrentIndex()) {
			throw new IllegalStateException(
				"trailing data after the value: length=" + reader.length() + " currentIndex=" + reader.getCurrentIndex());
		}
		return value;
	}

	/**
	 * Decodes a captured slice through an ordinary {@link JsonCodec}.
	 * <p>
	 * Reproduces the shape of {@link JsonUtils#fromJsonBytes} — including the completeness check — against this class's
	 * own reader rather than calling it, because it resolves its reader through a private {@code ThreadLocal}: calling
	 * it in some strategies and not others would put a {@code ThreadLocal.get()} on one side of the comparison only.
	 */
	static <T> T decodeWith(JsonCodec<T> codec, byte[] bytes) throws IOException {
		JsonReader<Object> reader = VALUE_READER.process(bytes, bytes.length);
		reader.getNextToken();
		T value = codec.read(reader);
		if (reader.length() != reader.getCurrentIndex()) {
			throw new IllegalStateException(
				"trailing data after the value: length=" + reader.length() + " currentIndex=" + reader.getCurrentIndex());
		}
		return value;
	}

	/** The copy a captured range costs. Part of what a capture using only dsl-json's public API is worth. */
	static byte[] slice(byte[] payload, Member member) {
		return Arrays.copyOfRange(payload, member.start(), member.end());
	}

	@Test
	public void anEmptyEnvelopeHasNoMembersAndDoesNotThrow() throws Exception {
		byte[] tight = "{}".getBytes(UTF_8);
		assertEquals(List.of(), captureMembers(SCAN_READER.process(tight, tight.length), tight));

		byte[] spaced = "{ }".getBytes(UTF_8);
		assertEquals(List.of(), captureMembers(SCAN_READER.process(spaced, spaced.length), spaced));
	}

	// endregion

	// region the correctness gate — the whole corpus, every member of every envelope

	@Test
	@SuppressWarnings("unchecked")
	public void capturedRangesReDecodeEqualToTheDirectDecodeAcrossTheWholeCorpus() throws Exception {
		List<String> failures = new ArrayList<>();

		for (Fixture fixture : corpus()) {
			byte[] payload = fixture.payload();

			// 1. the reference: one direct tree decode of the whole envelope
			Map<String, Object> direct = (Map<String, Object>) decodeTree(payload, payload.length);

			// 2. fixture self-consistency, so a wrong `expected` is reported as a fixture bug and not misattributed
			// to the capture rule. This is where an expectation written in the obvious Java types rather than
			// dsl-json's generic-tree ones fails, loudly and by name.
			assertEquals(message(fixture, "declared expected vs direct decode"),
				fixture.expected(), direct.get(fixture.deferredKey()));

			// 3. capture
			List<Member> members = captureMembers(SCAN_READER.process(payload, payload.length), payload);

			// 4. the member set: catches a loop that drops the last member or double-consumes a separator,
			// independently of any decode. `direct` is a LinkedHashMap, so its key order is document order.
			assertEquals(message(fixture, "captured keys"),
				new ArrayList<>(direct.keySet()), members.stream().map(Member::key).toList());

			// 5. every member's range — not just the deferred one
			for (Member member : members) {
				byte[] copy = slice(payload, member);
				Object expected = direct.get(member.key());
				Object reDecoded;
				try {
					reDecoded = decodeTree(copy, copy.length);
				} catch (Exception e) {
					failures.add(captureFailure(PARSE_FAILURE, fixture, member, payload, expected, e));
					continue;
				}
				if (!Objects.equals(expected, reDecoded)) {
					failures.add(captureFailure(MISMATCH, fixture, member, payload, expected, reDecoded));
					continue;
				}

				// 6. the boundary bytes, so an off-by-one that happens to still decode is caught anyway.
				// Derived from failures, in this order: `start` on ':' was the first form tried and it decoded
				// nothing; `end` inclusive of the separator was the second and it decoded the value plus trailing
				// data; `end` untrimmed was the third and only `decodeTree`'s completeness check saw it.
				assertNotEquals(message(fixture, "start must not sit on ':'"), ':', (char) payload[member.start()]);
				assertFalse(message(fixture, "start must not sit on whitespace"), isJsonWhitespace(payload[member.start()]));
				assertFalse(message(fixture, "end must exclude trailing whitespace"),
					isJsonWhitespace(payload[member.end() - 1]));
				byte after = payload[member.end()];
				assertTrue(message(fixture, "the byte at end must be the separator, the envelope's '}' or whitespace, " +
									   "was '" + (char) after + '\''),
					after == ',' || after == '}' || isJsonWhitespace(after));
			}

			if (fixture.name().equals("nested-32")) {
				// minus one for the envelope's own '{'
				assertEquals(message(fixture, "the nesting depth must be exactly " + NESTING_DEPTH),
					NESTING_DEPTH, countBytes(payload, (byte) '{') - 1);
			}
			if (fixture.name().equals("unicode-escape")) {
				// guards the Java-lexer escape trap: a fixture that silently became a plain-string fixture would
				// still pass everything above while testing nothing
				assertTrue(message(fixture, "the payload must contain a real backslash followed by 'u'"),
					fixture.json().contains("\\u00e9"));
			}
			if (fixture.isFragmented()) {
				assertFragmentation(fixture);
			}
		}

		if (!failures.isEmpty()) {
			// deliberately not fail-fast: a rule that fails 1 fixture of the corpus is a very different signal from one
			// that fails all of it, and the corpus exists to tell them apart
			throw new AssertionError(failures.size() + " capture failure(s):\n" + String.join("\n", failures));
		}
	}

	/**
	 * The fragmented-arrival assertions. They belong to that fixture's intent but can only execute here, where a reader
	 * and the capture rule exist.
	 */
	private void assertFragmentation(Fixture fixture) {
		byte[][] fragments = fixture.fragments();

		// tautological under `fragmentedAt`, and written anyway as the regression guard for the day a fragment is
		// hand-declared rather than derived from the payload
		assertArrayEquals(message(fixture, "joining the fragments must reproduce the canonical payload"),
			fixture.payload(), join(fragments));
		assertTrue(message(fixture, "at least 3 fragments"), fragments.length >= 3);

		// The boundaries are recovered from the fragment lengths rather than re-declared, so the two properties below
		// are checked against where the splits actually landed. Guessing at a fragment's leading bytes was the first
		// form tried and it silently accepted a split three bytes off.
		int escapeStart = indexOf(fixture.payload(), "\\u00e9".getBytes(UTF_8));
		assertTrue(message(fixture, "the payload must contain a real \\uXXXX escape"), escapeStart >= 0);

		boolean splitsAUtf8Sequence = false;
		boolean splitsAnEscape = false;
		int boundary = 0;
		for (int i = 0; i < fragments.length - 1; i++) {
			boundary += fragments[i].length;
			if ((fixture.payload()[boundary] & 0xC0) == 0x80) splitsAUtf8Sequence = true;
			if (boundary > escapeStart && boundary < escapeStart + "\\u00e9".length()) splitsAnEscape = true;
		}
		assertTrue(message(fixture, "one split must land mid-UTF-8, on a continuation byte"), splitsAUtf8Sequence);
		assertTrue(message(fixture, "one split must land inside the \\uXXXX escape"), splitsAnEscape);

		// what makes the join non-optional rather than cosmetic
		byte[] first = fragments[0];
		assertThrows(message(fixture, "the first fragment alone must not decode"), Exception.class,
			() -> decodeTree(first, first.length));
	}

	private static boolean isJsonWhitespace(byte b) {
		return b == ' ' || b == '\t' || b == '\n' || b == '\r';
	}

	/** The short diagnostic, for the assertions that are not about a captured range. */
	static String message(Fixture fixture, String what) {
		return String.format(Locale.ROOT, "capture fixture=%s property=<%s>: %s",
			fixture.name(), fixture.property(), what);
	}

	/**
	 * The range diagnostic. A capture bug found six months from now is found by reading this string, so it carries the
	 * fixture, what the fixture would catch, the captured range, the slice, its surrounding bytes as text with
	 * {@code »…«} around the capture, and the same window as hex.
	 * <p>
	 * The window is indexed over the {@code byte[]} and decoded piecewise, never via
	 * {@code new String(payload).substring(...)}: that indexes by <b>char</b>, and half this corpus exists to break
	 * char/byte confusion. A window that starts or ends mid-sequence therefore renders as U+FFFD, which is why the hex
	 * field is present and is the authoritative rendering.
	 */
	static String captureFailure(
		String kind, Fixture fixture, Member member, byte[] payload, Object expected, Object actualOrCause
	) {
		int from = Math.max(0, member.start() - 12);
		int to = Math.min(payload.length, member.end() + 12);
		String slice = new String(payload, member.start(), member.end() - member.start(), UTF_8);
		String context =
			new String(payload, from, member.start() - from, UTF_8) +
			'»' + slice + '«' +
			new String(payload, member.end(), to - member.end(), UTF_8);
		return String.format(Locale.ROOT,
			"capture %s fixture=%s property=<%s> key=%s captured=[%d,%d) slice=<%s> context=<%s> bytes=<%s> " +
			"expected=<%s> actual=<%s>",
			kind, fixture.name(), fixture.property(), member.key(), member.start(), member.end(),
			slice, context, hex(payload, from, to), expected, actualOrCause);
	}

	private static String hex(byte[] bytes, int from, int to) {
		StringBuilder builder = new StringBuilder((to - from) * 3);
		for (int i = from; i < to; i++) {
			if (i > from) builder.append(' ');
			builder.append(String.format(Locale.ROOT, "%02x", bytes[i]));
		}
		return builder.toString();
	}

	@Test
	public void aCaptureFailureMessageNamesTheFixtureTheRangeAndTheSurroundingBytes() {
		Fixture fixture = base();
		Member wrong = new Member("params", 40, 44);            // deliberately off, so no real bug is needed

		String message = captureFailure(MISMATCH, fixture, wrong, fixture.payload(), List.of(1L, 2L, 3L), List.of(1L, 2L));

		assertTrue(message, message.contains(fixture.name()));
		assertTrue(message, message.contains("property=<" + fixture.property() + '>'));
		assertTrue(message, message.contains("key=params"));
		assertTrue(message, message.contains("captured=[40,44)"));
		assertTrue(message, message.contains("context=<"));
		assertTrue(message, message.contains("bytes=<"));
		assertTrue(message, message.contains("»"));
		assertTrue(message, message.contains("«"));
	}

	@Test
	public void aParseFailureIsDistinguishableFromASilentlyWrongDecode() {
		Fixture fixture = base();
		Member member = new Member("params", 48, 55);

		String parseFailure = captureFailure(PARSE_FAILURE, fixture, member, fixture.payload(), null,
			new IllegalStateException("trailing data"));
		String mismatch = captureFailure(MISMATCH, fixture, member, fixture.payload(), List.of(1L), List.of(2L));

		assertTrue(parseFailure, parseFailure.contains(PARSE_FAILURE));
		assertFalse(parseFailure, parseFailure.contains(MISMATCH));
		assertTrue(mismatch, mismatch.contains(MISMATCH));
		assertFalse(mismatch, mismatch.contains(PARSE_FAILURE));
	}

	// endregion

	// region strategy (a) — offset capture, copy the sub-range, decode

	/**
	 * Strategy (a) — the shape a deferred-decoding envelope reader takes.
	 * <p>
	 * It captures the range of <b>every</b> member in one pass and decodes <b>nothing</b> during that pass;
	 * {@code method} and {@code params} are then each decoded from their captured slice. It is deliberately
	 * <b>order-independent</b>: decoding {@code method} in place would be an optimisation valid only when
	 * {@code method} precedes {@code params}, which is what strategy (d) is here to show is not general.
	 */
	Decoded offsetCapture(byte[] payload) throws IOException {
		List<Member> members = captureMembers(SCAN_READER.process(payload, payload.length), payload);
		String method = null;
		List<Integer> params = null;
		for (Member member : members) {
			switch (member.key()) {
				case "method" -> method = decodeWith(METHOD_CODEC, slice(payload, member));
				case "params" -> params = decodeWith(PARAMS_CODEC, slice(payload, member));
				// captured but never decoded — this branch is what makes (a) order-independent, not dead code
				default -> {}
			}
		}
		return new Decoded(method, params);
	}

	@Test
	public void strategyAProducesTheExpectedDecode() throws Exception {
		assertEquals(EXPECTED, offsetCapture(base().payload()));
	}

	// endregion

	// region strategy (b) — materialise a generic tree, then decode from it

	/**
	 * Strategy (b) — the generic tree, over the <b>whole envelope</b>, because that is what materialising a tree costs
	 * in practice. Tree-decoding only the {@code params} sub-range would be strategy (a) with a tree decode, which is
	 * a different question.
	 * <p>
	 * {@link #toIntList} is part of (b)'s price, exactly as the array copy is part of (a)'s: the tree is not the answer,
	 * and converting it to the target type is work the other strategies never do. {@link #decodeTree} applies the same
	 * completeness check as every other strategy, so all four decode to one standard of "fully consumed".
	 */
	@SuppressWarnings("unchecked")
	Decoded genericTree(byte[] payload) throws IOException {
		Map<String, Object> envelope = (Map<String, Object>) decodeTree(payload, payload.length);
		String method = (String) envelope.get("method");
		List<Integer> params = toIntList(envelope.get("params"));
		return new Decoded(method, params);
	}

	private static List<Integer> toIntList(Object value) {
		List<?> raw = (List<?>) value;                          // an ArrayList of Long — never of Integer
		List<Integer> result = new ArrayList<>(raw.size());
		for (Object element : raw) result.add(Math.toIntExact((Long) element));
		return result;
	}

	@Test
	public void strategyBProducesTheExpectedDecode() throws Exception {
		assertEquals(EXPECTED, genericTree(base().payload()));
	}

	// endregion

	// region strategy (c) — a full second parse of the whole envelope

	/**
	 * Strategy (c) — the naive baseline every JSON-RPC implementation reaches for first: scan once to learn which
	 * codec {@code params} needs, then re-parse the whole envelope from byte 0 to decode it.
	 */
	Decoded fullSecondParse(byte[] payload) throws IOException {
		String method = scanForMethod(payload);                 // pass 1
		List<Integer> params = reparseForParams(payload);       // pass 2
		return new Decoded(method, params);
	}

	/**
	 * Pass 1: find {@code method}, skip everything else.
	 * <p>
	 * The asymmetry in the loop is the defect any envelope reader can ship: after {@code codec.read(reader)} the
	 * separator has <b>not</b> been consumed, so {@code getNextToken()} is needed; after {@code skip()} it <b>has</b>,
	 * and calling {@code getNextToken()} again is the bug {@link AbstractMapJsonCodec} carries today.
	 */
	String scanForMethod(byte[] payload) throws IOException {
		JsonReader<Object> reader = SCAN_READER.process(payload, payload.length);
		reader.getNextToken();                                  // '{'
		String method = null;
		if (reader.getNextToken() != '}') {                     // an empty envelope has no members
			while (true) {
				String key = reader.readKey();
				byte next;
				if (key.equals("method")) {
					method = METHOD_CODEC.read(reader);
					next = reader.getNextToken();               // after a codec read, last() is the value's LAST byte
				} else {
					next = reader.skip();                       // skip() already consumed the separator
				}
				if (next != ',') break;
				reader.getNextToken();
			}
		}
		return method;
	}

	/** Pass 2: the same loop with the branches swapped, plus the completeness check the other strategies apply. */
	List<Integer> reparseForParams(byte[] payload) throws IOException {
		JsonReader<Object> reader = REPARSE_READER.process(payload, payload.length);
		reader.getNextToken();                                  // '{'
		List<Integer> params = null;
		if (reader.getNextToken() != '}') {
			while (true) {
				String key = reader.readKey();
				byte next;
				if (key.equals("params")) {
					params = PARAMS_CODEC.read(reader);
					next = reader.getNextToken();               // see scanForMethod: the asymmetry is deliberate
				} else {
					next = reader.skip();
				}
				if (next != ',') break;
				reader.getNextToken();
			}
		}
		if (reader.length() != reader.getCurrentIndex()) {
			throw new IllegalStateException(
				"trailing data after the envelope: length=" + reader.length() +
				" currentIndex=" + reader.getCurrentIndex());
		}
		return params;
	}

	@Test
	public void strategyCProducesTheExpectedDecode() throws Exception {
		assertEquals(EXPECTED, fullSecondParse(base().payload()));
	}

	/**
	 * Pins the separator asymmetry directly — a comment does not fail a build. Three shapes: a skipped member before
	 * the decoded one, after it, and both. A loop carrying the {@link AbstractMapJsonCodec} bug fails the first and
	 * the third.
	 */
	@Test
	public void skipAlreadyConsumesTheSeparatorAndGetNextTokenDoesNot() throws Exception {
		assertEquals("sum", scanForMethod("{\"a\":1,\"method\":\"sum\",\"b\":2}".getBytes(UTF_8)));
		assertEquals("sum", scanForMethod("{\"method\":\"sum\",\"b\":2}".getBytes(UTF_8)));
		assertEquals("sum", scanForMethod("{\"a\":1,\"method\":\"sum\"}".getBytes(UTF_8)));
	}

	// endregion

	// region strategy (d) — single-pass, no deferral: the floor, and why it is not an option

	/**
	 * The fourth data point: no deferral at all.
	 * <p>
	 * Valid <b>only</b> when the codec-selecting key precedes the payload, which is why it is a data point and not a
	 * strategy. {@link SubclassJsonCodec}{@code .read} is the same shape in production code — tag first, payload decoded
	 * in place — for the single-member case; it is cited, not copied, because an envelope has more than one member.
	 * <p>
	 * JSON-RPC 2.0 does not constrain member order, so (d) is a floor rather than an option, and
	 * {@link #strategyDIsInvalidWhenParamsPrecedesMethod} is the assertion that keeps that fact from being forgotten.
	 */
	Decoded singlePass(byte[] payload) throws IOException {
		JsonReader<Object> reader = SCAN_READER.process(payload, payload.length);
		reader.getNextToken();                                  // '{'
		String method = null;
		List<Integer> params = null;
		if (reader.getNextToken() != '}') {                     // an empty envelope has no members
			while (true) {
				String key = reader.readKey();
				byte next;
				switch (key) {
					case "method" -> {
						method = METHOD_CODEC.read(reader);
						next = reader.getNextToken();
					}
					case "params" -> {
						if (method == null) {
							throw new IllegalStateException(
								"single-pass is invalid on this envelope: 'params' precedes 'method', so the codec " +
								"selecting it is not yet known — this is the case deferral exists for");
						}
						params = PARAMS_CODEC.read(reader);
						next = reader.getNextToken();
					}
					default -> next = reader.skip();            // skip() consumed the separator — see scanForMethod
				}
				if (next != ',') break;
				reader.getNextToken();
			}
		}
		return new Decoded(method, params);
	}

	@Test
	public void strategyDProducesTheExpectedDecodeOnTheMethodFirstFixture() throws Exception {
		assertEquals(EXPECTED, singlePass(base().payload()));
	}

	@Test
	public void strategyDIsInvalidWhenParamsPrecedesMethod() {
		Fixture paramsFirst = byName("params-before-method");

		IllegalStateException e =
			assertThrows(IllegalStateException.class, () -> singlePass(paramsFirst.payload()));

		assertTrue(e.getMessage(), e.getMessage().contains("deferral"));
	}

	// endregion
}
