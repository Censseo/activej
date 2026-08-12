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

import io.activej.common.exception.MalformedDataException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.Assert.assertEquals;

/**
 * The one branch of {@link AbstractMapJsonCodec} and {@link AbstractArrayJsonCodec} no shipped codec reaches: the
 * {@code decoder(...) == null} skip, by which a subclass ignores a member it does not know.
 *
 * <p>It used to be written {@code reader.skip(); continue;}, which re-entered {@code readKey()} with {@code last()}
 * already past the separator {@code skip()} had consumed — eating the next key's opening quote and silently dropping
 * every remaining member. The cursor fact that makes it wrong ({@code skip()} skips one whole value <b>and</b> consumes
 * the following separator, <b>returning</b> it) is pinned independently by
 * {@link DeferredDecodingTest#skipAlreadyConsumesTheSeparatorAndGetNextTokenDoesNot}; this file pins the two templates
 * that now rely on it.
 *
 * <p>Every case below fails before the repair, and none of them is reachable from the codecs this module ships — every
 * {@code decoder(...)} override here either returns a codec or throws. Hence the two purpose-built subclasses: without
 * them the branch has no test at all.
 *
 * <p>Bare JUnit 4, no {@code @ClassRule}: {@code activej-test} is not on this module's classpath and nothing here
 * touches a {@code ByteBuf}, a {@code Promise} or a reactor.
 */
public class SkipBranchTest {

	// ---------------------------------------------------------------- the two subclasses under test

	/**
	 * Accumulates a {@code {"key": int}} object into a {@link LinkedHashMap}, ignoring one nominated key — the
	 * "tolerate unknown members" shape the skip branch exists for.
	 */
	static final class SkippingMapCodec extends AbstractMapJsonCodec<Map<String, Integer>, Map<String, Integer>, Integer> {
		private static final JsonCodec<Integer> VALUE_CODEC = JsonCodecs.ofInteger();

		private final String ignoredKey;

		SkippingMapCodec(String ignoredKey) {this.ignoredKey = ignoredKey;}

		@Override
		protected Iterator<JsonMapEntry<Integer>> iterate(Map<String, Integer> item) {
			return item.entrySet().stream().map(JsonMapEntry::of).iterator();
		}

		@Override
		protected JsonEncoder<Integer> encoder(String key, int index, Map<String, Integer> item, Integer value) {
			return VALUE_CODEC;
		}

		@Override
		protected JsonDecoder<Integer> decoder(String key, int index, Map<String, Integer> accumulator) {
			return key.equals(ignoredKey) ? null : VALUE_CODEC;
		}

		@Override
		protected Map<String, Integer> accumulator() {
			return new LinkedHashMap<>();
		}

		@Override
		protected void accumulate(Map<String, Integer> accumulator, String key, int index, Integer value) {
			accumulator.put(key, value);
		}

		@Override
		protected Map<String, Integer> result(Map<String, Integer> accumulator, int count) {
			return accumulator;
		}
	}

	/**
	 * Accumulates an {@code [int, ...]} array into a list, ignoring the element at one nominated <b>position</b>.
	 *
	 * <p>The position is counted here rather than taken from the {@code index} parameter, and that is not a stylistic
	 * choice: {@code index} is the count of <i>accumulated</i> elements, which a skip does not advance, so
	 * {@code decoder} sees the same value twice around one. Keying the fixture off it would make "skip position 1" mean
	 * "skip everything from position 1 on", and the test would pass for the wrong reason. The template's index
	 * semantics are out of scope for this repair.
	 */
	static final class SkippingArrayCodec extends AbstractArrayJsonCodec<List<Integer>, List<Integer>, Integer> {
		private static final JsonCodec<Integer> ELEMENT_CODEC = JsonCodecs.ofInteger();

		private final int ignoredPosition;

		private int position;

		SkippingArrayCodec(int ignoredPosition) {this.ignoredPosition = ignoredPosition;}

		@Override
		protected Iterator<Integer> iterate(List<Integer> item) {
			return item.iterator();
		}

		@Override
		protected JsonEncoder<Integer> encoder(int index, List<Integer> item, Integer value) {
			return ELEMENT_CODEC;
		}

		@Override
		protected JsonDecoder<Integer> decoder(int index, List<Integer> accumulator) {
			return position++ == ignoredPosition ? null : ELEMENT_CODEC;
		}

		@Override
		protected List<Integer> accumulator() {
			return new ArrayList<>();
		}

		@Override
		protected void accumulate(List<Integer> accumulator, int index, Integer value) {
			accumulator.add(value);
		}

		@Override
		protected List<Integer> result(List<Integer> accumulator, int count) {
			return accumulator;
		}
	}

	// ---------------------------------------------------------------- the map template

	/**
	 * The headline case: a skipped member in the middle. Before the repair the misplaced cursor met {@code c}'s
	 * separator where its opening quote was expected — here loudly, as {@code readKey()} refused it, but the same
	 * misplacement on a payload whose next bytes happen to parse is a member silently gone.
	 */
	@Test
	public void mapSkippedMemberInTheMiddleKeepsTheMembersAfterIt() throws MalformedDataException {
		assertEquals(
			Map.ofEntries(entry("a", 1), entry("c", 3)),
			JsonUtils.fromJson(new SkippingMapCodec("b"), "{\"a\":1,\"b\":2,\"c\":3}"));
	}

	/** The skipped member last: {@code skip()} returns {@code '}'}, which must end the loop rather than restart it. */
	@Test
	public void mapSkippedMemberLastCompletesWithoutAParseError() throws MalformedDataException {
		assertEquals(
			Map.of("a", 1),
			JsonUtils.fromJson(new SkippingMapCodec("b"), "{\"a\":1,\"b\":2}"));
	}

	/** The skipped member first — the branch taken on the very first iteration, with nothing accumulated yet. */
	@Test
	public void mapSkippedMemberFirstKeepsTheMembersAfterIt() throws MalformedDataException {
		assertEquals(
			Map.ofEntries(entry("a", 1), entry("c", 3)),
			JsonUtils.fromJson(new SkippingMapCodec("b"), "{\"b\":2,\"a\":1,\"c\":3}"));
	}

	// ---------------------------------------------------------------- the array template

	/** The same defect, same shape, in the array template. */
	@Test
	public void arraySkippedElementInTheMiddleKeepsTheElementsAfterIt() throws MalformedDataException {
		assertEquals(
			List.of(10, 30),
			JsonUtils.fromJson(new SkippingArrayCodec(1), "[10,20,30]"));
	}

	/** The skipped element last: {@code skip()} returns {@code ']'}. */
	@Test
	public void arraySkippedElementLastCompletesWithoutAParseError() throws MalformedDataException {
		assertEquals(
			List.of(10),
			JsonUtils.fromJson(new SkippingArrayCodec(1), "[10,20]"));
	}

	/**
	 * The skipped element first — mirrors {@code mapSkippedMemberFirstKeepsTheMembersAfterIt}.
	 * {@code AbstractArrayJsonCodec.read} is a separate hand-written state machine from the map
	 * template's, so a regression confined to its own first-iteration path would not be caught by the
	 * map-side test above.
	 */
	@Test
	public void arraySkippedElementFirstKeepsTheElementsAfterIt() throws MalformedDataException {
		assertEquals(
			List.of(20, 30),
			JsonUtils.fromJson(new SkippingArrayCodec(0), "[10,20,30]"));
	}
}
