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

import io.activej.common.annotation.ExposedInternals;

/**
 * Counts the JSON nesting depth of a contiguous byte array <b>without parsing it</b>, so that an over-deep
 * document can be refused before it ever reaches the parser (FR-052).
 *
 * <h2>Why this cannot be delegated to the parser</h2>
 * This is a <b>verified fact, not a design preference</b>. Disassembly of dsl-json 1.10.0 shows
 * {@code JsonReader.skip()} invoking {@code skip:()B} on itself — <b>one stack frame per nesting level</b> —
 * and the method exposes no depth argument, no depth query, and no hook of any kind. A sufficiently nested
 * document therefore exhausts the stack <i>inside the parser</i>, before any in-parse check could run.
 * <p>
 * Catching {@code StackOverflowError} is not an alternative: an {@code Error} signals that the JVM's own
 * invariants are in doubt, the frame at which it fires is not deterministic, and a limit that is implemented
 * by surviving a crash is not a limit. Raising the thread stack is not one either — it moves the cliff
 * without adding a bound. The only way to enforce a depth bound is to know the depth <b>before</b> parsing,
 * which is what this class is for.
 *
 * <h2>String awareness is the whole difficulty</h2>
 * A scan that simply counted <code>'{'</code> and {@code '['} bytes would be wrong in both directions:
 * <ul>
 *     <li>it would <b>falsely refuse</b> a shallow document whose <i>string content</i> happens to contain
 *     brackets — a peer locked out by data it legitimately sent;</li>
 *     <li>and, once its in-string tracking desynchronised on an escape, it would <b>under-count</b> and let
 *     a genuinely deep document through — the direction that costs a stack.</li>
 * </ul>
 * So the scan tracks whether it is inside a string literal, and honours {@code \\} and {@code \"} while it
 * is. It does <b>not</b> validate JSON: an invalid document that reaches the parser is rejected there and
 * becomes {@code -32700}. The scan's only obligation is never to <i>under</i>-count.
 *
 * <h2>Safe on unvalidated bytes</h2>
 * UTF-8 is self-synchronising — every byte of a multi-byte sequence is {@code >= 0x80} — so no byte of a
 * multi-byte character can collide with <code>'{'</code>, {@code '['}, {@code '"'} or {@code '\'}. The scan is
 * therefore correct on an array whose UTF-8 well-formedness has not yet been checked, and the decoder is
 * free to run it first.
 * <p>
 * Allocates nothing, and runs in one linear pass with an early exit once the bound is passed.
 *
 * <p><b>Not part of the supported API surface</b> (ADR-010). It is exposed so that a transport or a future
 * feature can apply the same bound to bytes it holds before handing them over.
 */
@ExposedInternals
public final class JsonDepthScanner {
	private JsonDepthScanner() {}

	/**
	 * Whether {@code bytes} nests deeper than {@code maxDepth}.
	 * <p>
	 * The bound is inclusive: a document exactly {@code maxDepth} levels deep is accepted.
	 */
	public static boolean exceedsDepth(byte[] bytes, int maxDepth) {
		return scan(bytes, maxDepth) > maxDepth;
	}

	/**
	 * The deepest nesting level in {@code bytes}, counting objects and arrays on <b>one shared counter</b>
	 * (either kind may nest inside the other). {@code 0} for a document with no container at all.
	 */
	public static int maxDepthOf(byte[] bytes) {
		return scan(bytes, Integer.MAX_VALUE);
	}

	/**
	 * The scan itself.
	 *
	 * @param limit stop as soon as the depth exceeds this, so a hostile document costs only as much as it
	 *              takes to prove it is too deep
	 * @return the deepest level reached — exact when it is {@code <= limit}, and merely {@code > limit}
	 * otherwise
	 */
	private static int scan(byte[] bytes, int limit) {
		int depth = 0;
		int deepest = 0;
		boolean inString = false;
		boolean escaped = false;

		for (byte b : bytes) {
			if (inString) {
				if (escaped) {
					// this byte is the escaped one, whatever it is — including '"' and '\\'
					escaped = false;
				} else if (b == '\\') {
					escaped = true;
				} else if (b == '"') {
					inString = false;
				}
				continue;                       // nothing inside a string ever affects the depth
			}
			switch (b) {
				case '"' -> inString = true;
				case '{', '[' -> {
					depth++;
					if (depth > deepest) {
						deepest = depth;
						if (deepest > limit) return deepest;
					}
				}
				// a closer with no opener is malformed JSON, which the parser reports; clamping at zero here
				// keeps a stray one from making a later opener look shallower than it is
				case '}', ']' -> {
					if (depth > 0) depth--;
				}
				default -> {
					// every other byte is inert: whitespace, a number, a bare literal, or any byte of a
					// multi-byte UTF-8 sequence
				}
			}
		}
		return deepest;
	}
}
