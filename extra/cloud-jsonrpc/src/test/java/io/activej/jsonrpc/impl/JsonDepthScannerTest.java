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

package io.activej.jsonrpc.impl;

import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * FR-052 — the string-aware pre-parse depth scan.
 *
 * <h2>Two failure modes, opposite in effect</h2>
 * A scanner that is not string-aware fails in one of two directions, and this class has fixtures for both:
 * <ul>
 *     <li><b>False refusal</b> — counting {@code {} and {@code [} that sit <i>inside a string literal</i>
 *     rejects a perfectly shallow document because its <i>content</i> looks deep. Harmless-looking, but it
 *     means a peer can be locked out by data it legitimately sent.</li>
 *     <li><b>Bypass</b> — failing to count structural characters that are genuinely outside a string lets a
 *     hostile peer sail past the bound. This is the direction that matters: the bound exists because
 *     {@code JsonReader.skip()} recurses one stack frame per level, so a document that slips through
 *     exhausts the stack <i>inside the parser</i>.</li>
 * </ul>
 * Every fixture below asserts the <b>exact</b> depth rather than just "refused / not refused", because an
 * exact count is what distinguishes a correct scanner from one that is merely wrong in a compensating way.
 */
public class JsonDepthScannerTest {

	// ---------------------------------------------------------------------------------------------------
	// Baseline counting: one shared counter for both container kinds.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void countsObjectAndArrayNestingOnOneSharedCounter() {
		assertEquals(0, depthOf(""));
		assertEquals(0, depthOf("1"));
		assertEquals(0, depthOf("\"just a string\""));
		assertEquals(1, depthOf("{}"));
		assertEquals(1, depthOf("[]"));
		assertEquals(2, depthOf("{\"a\":{}}"));
		assertEquals(2, depthOf("[[]]"));

		// the two kinds nest inside each other, so two separate counters would under-report both
		assertEquals(2, depthOf("{\"a\":[]}"));
		assertEquals(2, depthOf("[{}]"));
		assertEquals(4, depthOf("{\"a\":[{\"b\":[]}]}"));
		assertEquals(6, depthOf("[{\"a\":[{\"b\":[{}]}]}]"));
	}

	@Test
	public void countsThePeakNotTheFinalDepth() {
		// siblings return to the same level; the bound is about the deepest point reached
		assertEquals(2, depthOf("{\"a\":{},\"b\":{},\"c\":{}}"));
		assertEquals(3, depthOf("[{\"a\":[]},{},[]]"));
		assertEquals(1, depthOf("[],[],[]"));
	}

	@Test
	public void aRealisticEnvelopeIsShallow() {
		assertEquals(2, depthOf("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sum\",\"params\":[1,2,3]}"));
		assertEquals(3, depthOf("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m\",\"params\":{\"a\":[1]}}"));
		// a batch is one level deeper than its elements
		assertEquals(3, depthOf("[{\"jsonrpc\":\"2.0\",\"method\":\"m\",\"params\":[1]}]"));
	}

	// ---------------------------------------------------------------------------------------------------
	// Failure mode 1 — FALSE REFUSAL: structural characters inside a string must not count.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void bracesInsideAStringDoNotCount() {
		// a naive byte scan reports 33 here; the document is one level deep
		assertEquals(1, depthOf("{\"a\":\"{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{\"}"));
		assertEquals(1, depthOf("{\"a\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}"));
		assertEquals(1, depthOf("{\"a\":\"{[{[{[{[\"}"));
	}

	@Test
	public void aStringHoldingAWholeJsonDocumentIsStillOneValue() {
		// the classic false-refusal shape: a JSON document carried as a string payload
		String embedded = "{\"a\":{\"b\":{\"c\":{\"d\":[1,2,3]}}}}";
		String escaped = embedded.replace("\\", "\\\\").replace("\"", "\\\"");
		assertEquals("the embedded document's own depth must not leak into the outer count",
			1, depthOf("{\"params\":\"" + escaped + "\"}"));
		// and the same document unescaped really is deep, or the assertion above proves nothing
		assertEquals(5, depthOf(embedded));
	}

	@Test
	public void closingBracketsInsideAStringDoNotUnbalanceTheCounter() {
		// under-counting is the dangerous direction: a ']' inside a string must not pop a real level
		assertEquals(3, depthOf("{\"a\":[\"]]]]]]]]]]\",{\"b\":1}]}"));
		assertEquals(1, depthOf("{\"a\":\"}}}}}}}}}}\"}"));
		assertEquals(1, depthOf("{\"a\":\"}}}}\",\"b\":\"{{{{\"}"));
	}

	@Test
	public void aKeyContainingStructuralCharactersDoesNotCount() {
		assertEquals(1, depthOf("{\"{{{[[[\":1}"));
		assertEquals(2, depthOf("{\"{[\":{\"]}\":1}}"));
	}

	// ---------------------------------------------------------------------------------------------------
	// Failure mode 2 — BYPASS: escapes must not desynchronise the in-string tracking.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void anEscapedQuoteDoesNotEndTheString() {
		// a scanner that treats \" as closing the string thinks it is back OUTSIDE, and then counts the
		// braces that follow — a false refusal here, and a bypass in the mirror case below
		assertEquals(1, depthOf("{\"a\":\"say \\\" then {{{{\"}"));
		assertEquals(2, depthOf("{\"a\":{\"b\":\"\\\"\"}}"));
	}

	@Test
	public void anEscapedBackslashDoesNotEscapeTheFollowingQuote() {
		// "a\\" ends the string: the backslash is escaped, so the quote after it is REAL. A scanner that
		// swallows that quote stays "inside a string" for the rest of the document and counts NOTHING —
		// the bypass direction, and the reason this fixture asserts a depth greater than 1
		assertEquals(3, depthOf("{\"a\":\"trailing backslash \\\\\",\"b\":[{}]}"));
		assertEquals(2, depthOf("{\"a\":\"\\\\\",\"b\":{}}"));

		// an odd number of backslashes escapes the quote; an even number does not
		assertEquals(1, depthOf("{\"a\":\"\\\\\\\"{{{{\"}"));
	}

	@Test
	public void aBypassAttemptUsingAnUnterminatedStringCountsNothingAfterIt() {
		// if a document opens a string and never closes it, everything after is string content. The scan
		// does not validate JSON — the parser rejects this as -32700 — but it must not UNDER-count either,
		// and here there is genuinely nothing to count past the quote
		assertEquals(1, depthOf("{\"a\":\"{{{{{{{{"));
	}

	@Test
	public void otherJsonEscapesInsideAStringAreInert() {
		assertEquals(1, depthOf("{\"a\":\"\\n\\t\\r\\b\\f\\/\\u007b\\u005b\"}"));
		// { is '{' and [ is '[' — as ESCAPES they are string content, not structure
		assertEquals(2, depthOf("{\"a\":\"\\u007b\\u007b\\u007b\",\"b\":[]}"));
	}

	// ---------------------------------------------------------------------------------------------------
	// The bound itself.
	// ---------------------------------------------------------------------------------------------------

	@Test
	public void exceedsDepthIsExclusiveAtTheBound() {
		byte[] eight = utf8(nested(8));
		assertEquals(8, JsonDepthScanner.maxDepthOf(eight));

		assertFalse("a document exactly at the bound is accepted", JsonDepthScanner.exceedsDepth(eight, 8));
		assertTrue(JsonDepthScanner.exceedsDepth(eight, 7));
		assertFalse(JsonDepthScanner.exceedsDepth(eight, 9));
		assertFalse(JsonDepthScanner.exceedsDepth(eight, Integer.MAX_VALUE));
	}

	@Test
	public void scanningStopsOnceTheBoundIsPassed() {
		// early exit is what keeps a hostile deeply-nested document cheap; the reported value is only
		// guaranteed to be "greater than the bound", not the true depth
		byte[] deep = utf8(nested(5000));
		assertTrue(JsonDepthScanner.exceedsDepth(deep, 64));
		assertTrue(JsonDepthScanner.maxDepthOf(deep) >= 65);
	}

	@Test
	public void aDeeplyNestedDocumentIsCountedExactly() {
		for (int depth : new int[]{1, 2, 32, 63, 64, 65, 100}) {
			assertEquals("depth " + depth, depth, JsonDepthScanner.maxDepthOf(utf8(nested(depth))));
		}
	}

	@Test
	public void unbalancedClosersDoNotDriveTheCounterNegative() {
		// the scan does not validate JSON; it must simply never mis-count because of trailing closers
		assertEquals(1, depthOf("}}}}{}"));
		assertEquals(2, depthOf("]]]][[]]"));
		assertEquals(1, depthOf("{}}}}}"));
	}

	@Test
	public void multiByteUtf8CannotBeMistakenForStructure() {
		// UTF-8 is self-synchronising: every byte of a multi-byte sequence is >= 0x80, so none can collide
		// with '{', '[', '"' or '\'. The scan therefore runs safely on bytes it has not yet validated.
		assertEquals(2, depthOf("{\"é😀\":[1]}"));
		assertEquals(1, depthOf("{\"a\":\"😀{{{{\"}"));
	}

	@Test
	public void anEmptyOrTinyArrayIsHandled() {
		assertEquals(0, JsonDepthScanner.maxDepthOf(new byte[0]));
		assertFalse(JsonDepthScanner.exceedsDepth(new byte[0], 1));
		assertEquals(0, depthOf("   "));
	}

	// ---------------------------------------------------------------------------------------------------

	private static int depthOf(String json) {
		return JsonDepthScanner.maxDepthOf(utf8(json));
	}

	private static byte[] utf8(String json) {
		return json.getBytes(UTF_8);
	}

	/** {@code [[[…1…]]]} nested exactly {@code depth} levels. */
	private static String nested(int depth) {
		StringBuilder json = new StringBuilder();
		json.append("[".repeat(depth)).append('1').append("]".repeat(depth));
		return json.toString();
	}
}
